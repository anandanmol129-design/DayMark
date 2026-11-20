package com.anmol.daymark

import android.app.AppOpsManager
import android.app.Service
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Process
import android.provider.Settings
import android.telecom.TelecomManager
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/** One app used during a focus session and for how long it was on screen. */
data class AppUse(val packageName: String, val label: String, val millis: Long)

/**
 * Deep focus: while a deep session runs and Daymark isn't on screen, [DeepFocusService] covers
 * everything with an overlay (calls still get through). Usage access also lets each finished
 * session record which apps were opened during it.
 */
internal object DeepFocus {
    /** Set by MainActivity: whether Daymark itself is on screen (then nothing needs covering). */
    @Volatile var appVisible = false
    @Volatile var service: DeepFocusService? = null

    fun canOverlay(context: Context) = Settings.canDrawOverlays(context)

    fun hasUsageAccess(context: Context): Boolean {
        val ops = context.getSystemService(AppOpsManager::class.java)
        return ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName) == AppOpsManager.MODE_ALLOWED
    }

    fun overlaySettings(context: Context) = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + context.packageName)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    fun usageSettings(context: Context) = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS, Uri.parse("package:" + context.packageName)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** Starts or stops the covering service to match the running session. */
    fun sync(context: Context, run: FocusRun?) {
        val app = context.applicationContext
        val wanted = run != null && run.deep && !run.isBreak && canOverlay(app)
        val intent = Intent(app, DeepFocusService::class.java)
        if (wanted) runCatching { app.startForegroundService(intent) }
        else if (service != null) runCatching { app.stopService(intent) }
    }

    /** Re-check straight away (Daymark just went to the background or came back). */
    fun poke() { service?.refresh() }

    /** The app on screen most recently, from usage events of the last few minutes (needs usage access). */
    fun foregroundPackage(context: Context): String? {
        if (!hasUsageAccess(context)) return null
        val usm = context.getSystemService(UsageStatsManager::class.java)
        val now = System.currentTimeMillis()
        val events = usm.queryEvents(now - 10 * 60_000L, now) ?: return null
        val e = UsageEvents.Event()
        var last: String? = null
        while (events.hasNextEvent()) {
            events.getNextEvent(e)
            if (e.eventType == UsageEvents.Event.ACTIVITY_RESUMED) last = e.packageName
        }
        return last
    }

    /** Apps a call can come in on; deep focus steps aside for them. */
    fun callApps(context: Context): Set<String> = buildSet {
        runCatching { context.getSystemService(TelecomManager::class.java)?.defaultDialerPackage }.getOrNull()?.let(::add)
        add("com.android.server.telecom"); add("com.android.incallui"); add("com.google.android.dialer"); add("com.android.dialer")
    }

    /** How long each app was on screen between [from] and [to], longest first (empty without usage access). */
    fun appsDuring(context: Context, from: Long, to: Long): List<AppUse> {
        if (to <= from || !hasUsageAccess(context)) return emptyList()
        val usm = context.getSystemService(UsageStatsManager::class.java)
        val events = runCatching { usm.queryEvents(from, to) }.getOrNull() ?: return emptyList()
        val totals = HashMap<String, Long>()
        val e = UsageEvents.Event()
        var current: String? = null
        var since = from
        fun close(at: Long) { current?.let { totals[it] = (totals[it] ?: 0L) + (at - since).coerceAtLeast(0) }; current = null }
        while (events.hasNextEvent()) {
            events.getNextEvent(e)
            when (e.eventType) {
                UsageEvents.Event.ACTIVITY_RESUMED -> { close(e.timeStamp); current = e.packageName; since = e.timeStamp }
                UsageEvents.Event.ACTIVITY_PAUSED -> if (e.packageName == current) close(e.timeStamp)
                UsageEvents.Event.SCREEN_NON_INTERACTIVE, UsageEvents.Event.KEYGUARD_SHOWN -> close(e.timeStamp)
            }
        }
        close(to)
        val skip = setOf(context.packageName, "com.android.systemui", homePackage(context))
        val pm = context.packageManager
        return totals.filter { (pkg, ms) -> pkg !in skip && ms >= 5_000 }
            .map { (pkg, ms) -> AppUse(pkg, runCatching { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg.substringAfterLast('.')), ms) }
            .sortedByDescending { it.millis }
    }

    private fun homePackage(context: Context): String? = runCatching {
        context.packageManager.resolveActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)?.activityInfo?.packageName
    }.getOrNull()
}

internal fun appsSummary(apps: List<AppUse>, max: Int = 3): String =
    apps.take(max).joinToString(" · ") { "${it.label} ${spanText(it.millis)}" } + if (apps.size > max) " · +${apps.size - max} more" else ""

/** Keeps deep focus going: shows the covering overlay whenever another app would be on screen. */
class DeepFocusService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private var overlay: View? = null
    private var timeText: TextView? = null
    private var titleText: TextView? = null
    private var allowed: Set<String> = emptySet()
    private var allowedAt = 0L
    private var foreground: String? = null
    private var queriedTo = 0L
    private val tick = object : Runnable {
        override fun run() { refresh(); handler.postDelayed(this, 400) }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        DeepFocus.service = this
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        FocusClock.load(this)
        val run = FocusClock.run
        val notification = run?.let { FocusClock.ongoingNotification(this, it) }
        if (notification == null) { stopSelf(); return START_NOT_STICKY }
        runCatching { startForeground(FocusClock.NOTE_ONGOING, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE) }
            .onFailure { stopSelf(); return START_NOT_STICKY }
        handler.removeCallbacks(tick)
        handler.post(tick)
        return START_STICKY
    }

    fun refresh() {
        val run = FocusClock.run
        val now = System.currentTimeMillis()
        if (run == null || !run.deep || run.isBreak || (!run.paused && run.remaining(now) <= 0L) || !DeepFocus.canOverlay(this)) {
            hide(); stopSelf(); return
        }
        // Phone, Messages, Camera, your dock apps and calls get through.
        if (now - allowedAt > 5_000) { allowed = FocusDock.allowedPackages(this); allowedAt = now }
        val letThrough = !DeepFocus.appVisible && currentApp(now)?.let { it in allowed } == true
        if (DeepFocus.appVisible || letThrough) hide() else show(run)
        timeText?.text = clockText(run.remaining(now))
        titleText?.text = if (run.paused) "${run.title} · paused" else run.title
    }

    /** The app on screen, read from usage events since the last check (null without usage access). */
    private fun currentApp(now: Long): String? {
        if (!DeepFocus.hasUsageAccess(this)) return null
        val usm = getSystemService(android.app.usage.UsageStatsManager::class.java)
        val from = if (queriedTo == 0L) now - 10 * 60_000L else queriedTo - 1_000
        val events = runCatching { usm.queryEvents(from, now) }.getOrNull() ?: return foreground
        val e = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(e)
            if (e.eventType == UsageEvents.Event.ACTIVITY_RESUMED) foreground = e.packageName
        }
        queriedTo = now
        return foreground
    }

    private fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)

    private fun show(run: FocusRun) {
        if (overlay != null) return
        val accent = HabitPalette[run.colorIndex.coerceIn(HabitPalette.indices)].toInt()
        val ink = 0xFFEDEAE3.toInt()
        val muted = 0xFFA39F96.toInt()
        fun text(size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, size); setTextColor(color); gravity = Gravity.CENTER
            if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(32f).toInt(), 0, dp(32f).toInt(), 0)
            addView(text(12f, muted, true).apply { text = "DEEP FOCUS"; letterSpacing = .14f })
            titleText = text(24f, ink, true).apply { text = run.title; setPadding(0, dp(10f).toInt(), 0, 0) }
            addView(titleText)
            timeText = text(76f, ink, true).apply { fontFeatureSettings = "tnum"; text = clockText(run.remaining(System.currentTimeMillis())) }
            addView(timeText)
            addView(text(15f, muted).apply { text = "Other apps are paused until this session ends. Calls still come through."; setPadding(0, dp(4f).toInt(), 0, dp(36f).toInt()) })
            addView(text(16f, 0xFF14201A.toInt(), true).apply {
                text = "Back to Daymark"
                background = GradientDrawable().apply { cornerRadius = dp(28f); setColor(accent) }
                setPadding(dp(28f).toInt(), dp(16f).toInt(), dp(28f).toInt(), dp(16f).toInt())
                setOnClickListener {
                    runCatching { startActivity(WidgetActions.appIntent(this@DeepFocusService, WidgetActions.FOCUS_OPEN)) }
                }
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            addView(text(13f, muted).apply {
                text = "Hold to end deep focus"
                setPadding(dp(16f).toInt(), dp(22f).toInt(), dp(16f).toInt(), dp(16f).toInt())
                setOnLongClickListener { FocusClock.setDeep(this@DeepFocusService, false); true }
            })
        }
        val root = FrameLayout(this).apply {
            // Fully opaque: nothing of the app underneath shows through. A wash of the tracker's colour at the top.
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(blend(accent, 0xFF141413.toInt(), .28f), 0xFF141413.toInt(), 0xFF141413.toInt()))
            addView(column, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            isClickable = true
            isFocusable = true
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply { layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS; fitInsetsTypes = 0 }
        runCatching { getSystemService(WindowManager::class.java).addView(root, params); overlay = root }
    }

    private fun blend(a: Int, b: Int, t: Float): Int {
        fun ch(shift: Int) = (((a shr shift) and 0xFF) * t + ((b shr shift) and 0xFF) * (1 - t)).toInt()
        return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    private fun hide() {
        val view = overlay ?: return
        overlay = null; timeText = null; titleText = null
        runCatching { getSystemService(WindowManager::class.java).removeView(view) }
    }

    override fun onDestroy() {
        handler.removeCallbacks(tick)
        hide()
        if (DeepFocus.service === this) DeepFocus.service = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        // A session still running (deep focus was just turned off) keeps its countdown notification.
        FocusClock.refreshNotification(this)
        super.onDestroy()
    }
}
