package com.anmol.daymark

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.compose.animation.animateContentSize
import android.view.WindowManager
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.foundation.gestures.detectTapGestures
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import androidx.compose.animation.togetherWith
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.geometry.Offset
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularWavyProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.graphics.shapes.Morph
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/*
 * Focus: a pomodoro timer and time tracker, on its own page beside Habits (as Saved sits beside Notes).
 * Each thing you track is a TIMER card in the Habits tab, so backups carry it; its finished sessions,
 * with their notes, live on the card in [BoardCard.sessions]. The session that's running lives in
 * [FocusClock], saved to its own prefs so it survives the app being closed.
 */

/** One finished stretch of focus on a tracker, with whatever you wrote during or after it. */
data class FocusSession(
    val id: String = UUID.randomUUID().toString(),
    val startedAt: Long,
    val endedAt: Long,
    val focusedMillis: Long,
    val plannedMinutes: Int,
    val note: String = "",
    /** Ran the whole timer (rather than being stopped early). */
    val completed: Boolean = false,
    /** Apps on screen during the session, longest first (needs usage access). */
    val apps: List<AppUse> = emptyList(),
    /** Ran with deep focus on. */
    val deep: Boolean = false
)

/** The timer lengths offered first; [FOCUS_MORE] sit behind "Custom". */
internal val FOCUS_PRESETS = listOf(25, 45, 60)
internal val FOCUS_MORE = listOf(5, 10, 15, 20, 30, 50, 75, 90, 120)
internal const val FOCUS_DEFAULT_MINUTES = 25
internal const val FOCUS_BREAK_MINUTES = 5
private const val SESSION_LIMIT = 1000

internal fun focusMinutes(card: BoardCard) = card.durationMinutes.takeIf { it > 0 } ?: FOCUS_DEFAULT_MINUTES

internal fun FocusSession.toJson(): JSONObject = JSONObject().apply {
    put("id", id); put("start", startedAt); put("end", endedAt); put("focused", focusedMillis)
    put("planned", plannedMinutes); put("note", note); put("done", completed); put("deep", deep)
    if (apps.isNotEmpty()) put("apps", JSONArray().apply { apps.forEach { put(JSONObject().put("p", it.packageName).put("l", it.label).put("m", it.millis)) } })
}

internal fun JSONArray?.toSessions(): List<FocusSession> = if (this == null) emptyList() else (0 until length()).mapNotNull { i ->
    val o = optJSONObject(i) ?: return@mapNotNull null
    FocusSession(
        id = o.optString("id").ifBlank { UUID.randomUUID().toString() },
        startedAt = o.optLong("start"), endedAt = o.optLong("end"), focusedMillis = o.optLong("focused").coerceAtLeast(0),
        plannedMinutes = o.optInt("planned"), note = o.optString("note"), completed = o.optBoolean("done"), deep = o.optBoolean("deep"),
        apps = o.optJSONArray("apps")?.let { a -> (0 until a.length()).mapNotNull { j -> a.optJSONObject(j)?.let { AppUse(it.optString("p"), it.optString("l"), it.optLong("m")) } } }.orEmpty()
    )
}.sortedBy { it.startedAt }.takeLast(SESSION_LIMIT)

/** Adds a finished session to its tracker and its time to the tracker's total. */
internal fun BoardSnapshot.withSession(trackerId: String, session: FocusSession) = copy(cards = cards.map {
    if (it.id != trackerId) it else it.copy(sessions = (it.sessions + session).takeLast(SESSION_LIMIT), trackedMillis = it.trackedMillis + session.focusedMillis)
})

internal fun BoardSnapshot.editSession(trackerId: String, sessionId: String, note: String) = copy(cards = cards.map {
    if (it.id != trackerId) it else it.copy(sessions = it.sessions.map { s -> if (s.id == sessionId) s.copy(note = note) else s })
})

internal fun BoardSnapshot.withoutSession(trackerId: String, sessionId: String) = copy(cards = cards.map { card ->
    if (card.id != trackerId) card else {
        val gone = card.sessions.firstOrNull { it.id == sessionId }
        card.copy(sessions = card.sessions.filter { it.id != sessionId }, trackedMillis = (card.trackedMillis - (gone?.focusedMillis ?: 0)).coerceAtLeast(0))
    }
})

private fun dayOf(millis: Long) = Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalDate().toEpochDay()

/** Focused time on [day] (epoch day) across the given trackers. */
internal fun focusedOn(trackers: List<BoardCard>, day: Long) = trackers.sumOf { t -> t.sessions.filter { dayOf(it.startedAt) == day }.sumOf { it.focusedMillis } }

/** "25:00", or "1:05:00" past an hour. */
internal fun clockText(millis: Long): String {
    val total = (millis.coerceAtLeast(0) + 999) / 1000
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
}

/** "1h 20m", "45m", or "< 1m". */
internal fun spanText(millis: Long): String = if (millis in 1 until 60_000) "< 1m" else formatDuration(millis)

private fun sessionWhen(s: FocusSession): String {
    val start = Instant.ofEpochMilli(s.startedAt).atZone(ZoneId.systemDefault())
    return formatDue(start.toLocalDate().toEpochDay()) + " · " + formatClock(start.hour * 60 + start.minute)
}

/** The running (or paused) session; a pomodoro break is one too, with [isBreak]. */
data class FocusRun(
    val trackerId: String,
    val title: String,
    val colorIndex: Int,
    val plannedMillis: Long,
    val startedAt: Long,
    /** When the current stretch started; null while paused. */
    val runningSince: Long?,
    /** Time focused before [runningSince]. */
    val elapsedBefore: Long = 0,
    val note: String = "",
    val isBreak: Boolean = false,
    /** Deep focus: other apps are covered while this runs (see DeepFocus.kt). */
    val deep: Boolean = false
) {
    val paused get() = runningSince == null
    fun elapsed(now: Long) = elapsedBefore + (runningSince?.let { (now - it).coerceAtLeast(0) } ?: 0)
    fun remaining(now: Long) = (plannedMillis - elapsed(now)).coerceAtLeast(0)
    fun endsAt(): Long? = runningSince?.let { it + plannedMillis - elapsedBefore }
    val color get() = Color(HabitPalette[colorIndex.coerceIn(HabitPalette.indices)].toInt())
}

/** The one running session, kept in prefs, with its end alarm and ongoing notification. */
internal object FocusClock {
    private const val PREFS = "daymark_focus"
    private const val KEY = "run"
    const val CHANNEL_RUNNING = "focus_running"
    const val CHANNEL_DONE = "focus_done"
    const val ACTION_DONE = "com.anmol.daymark.focus.DONE"
    const val NOTE_ONGOING = 0x2F0C05
    private const val NOTE_DONE = 0x2F0C06

    var run by mutableStateOf<FocusRun?>(null)
        private set
    /** Wall-clock time the screen last ticked to; the timer UI reads this. */
    var now by mutableLongStateOf(System.currentTimeMillis())
    private var loaded = false

    fun load(context: Context) {
        if (loaded) return
        loaded = true
        run = read(context)
        deepTrackers = prefs(context).getStringSet("deep_trackers", null)?.toSet().orEmpty()
        aod = prefs(context).getBoolean("aod", false)
        FocusDock.load(context)
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun read(context: Context): FocusRun? = runCatching {
        val o = JSONObject(prefs(context).getString(KEY, null) ?: return null)
        FocusRun(
            o.getString("tracker"), o.optString("title"), o.optInt("color"), o.getLong("planned"), o.getLong("start"),
            if (o.isNull("since")) null else o.getLong("since"), o.optLong("before"), o.optString("note"), o.optBoolean("break"), o.optBoolean("deep")
        )
    }.getOrNull()

    private fun write(context: Context, next: FocusRun?) {
        run = next
        now = System.currentTimeMillis()
        prefs(context).edit().apply {
            if (next == null) remove(KEY) else putString(KEY, JSONObject().apply {
                put("tracker", next.trackerId); put("title", next.title); put("color", next.colorIndex); put("planned", next.plannedMillis)
                put("start", next.startedAt); put("since", next.runningSince ?: JSONObject.NULL); put("before", next.elapsedBefore)
                put("note", next.note); put("break", next.isBreak); put("deep", next.deep)
            }.toString())
        }.apply()
    }

    fun start(context: Context, tracker: BoardCard, minutes: Int, deep: Boolean = false) {
        val t = System.currentTimeMillis()
        write(context, FocusRun(tracker.id, tracker.title, habitColorIndex(tracker), minutes * 60_000L, t, t, deep = deep))
        arm(context)
    }

    /** Turns deep focus on or off for the running session. */
    fun setDeep(context: Context, on: Boolean) {
        val r = run ?: return
        write(context, r.copy(deep = on))
        arm(context)
    }

    /** Settings › Focus: keep the full-screen timer on, dimming to an always-on display when left alone. */
    var aod by mutableStateOf(false)
        private set

    fun setAod(context: Context, on: Boolean) {
        aod = on
        prefs(context).edit().putBoolean("aod", on).apply()
    }

    /** Trackers whose deep focus toggle is on (remembered between sessions). */
    var deepTrackers by mutableStateOf<Set<String>>(emptySet())
        private set

    fun setDeepTracker(context: Context, id: String, on: Boolean) {
        deepTrackers = if (on) deepTrackers + id else deepTrackers - id
        prefs(context).edit().putStringSet("deep_trackers", deepTrackers).apply()
    }

    fun startBreak(context: Context, after: FocusRun?, minutes: Int = FOCUS_BREAK_MINUTES) {
        val t = System.currentTimeMillis()
        write(context, FocusRun(after?.trackerId.orEmpty(), "Break", after?.colorIndex ?: 0, minutes * 60_000L, t, t, isBreak = true))
        arm(context)
    }

    fun pause(context: Context) {
        val r = run ?: return
        if (r.paused) return
        write(context, r.copy(runningSince = null, elapsedBefore = r.elapsed(System.currentTimeMillis())))
        arm(context)
    }

    fun resume(context: Context) {
        val r = run ?: return
        if (!r.paused) return
        write(context, r.copy(runningSince = System.currentTimeMillis()))
        arm(context)
    }

    fun addMinutes(context: Context, minutes: Int) {
        val r = run ?: return
        write(context, r.copy(plannedMillis = r.plannedMillis + minutes * 60_000L))
        arm(context)
    }

    fun setNote(context: Context, note: String) {
        val r = run ?: return
        write(context, r.copy(note = note))
    }

    /** Ends the run. For a focus run, returns the session to record (null for a break). */
    fun finish(context: Context, completed: Boolean): Pair<FocusRun, FocusSession?>? {
        val r = run ?: return null
        val t = System.currentTimeMillis()
        val focused = if (completed) r.plannedMillis else r.elapsed(t).coerceAtMost(r.plannedMillis)
        val ended = if (completed) (r.endsAt() ?: t).coerceAtMost(t) else t
        write(context, null)
        arm(context)
        if (r.isBreak) return r to null
        return r to FocusSession(
            startedAt = r.startedAt, endedAt = ended, focusedMillis = focused,
            plannedMinutes = (r.plannedMillis / 60_000L).toInt(), note = r.note.trim(), completed = completed, deep = r.deep,
            apps = DeepFocus.appsDuring(context, r.startedAt, ended)
        )
    }

    /** Sets (or clears) the end-of-timer alarm and the ongoing notification to match [run]. */
    private fun arm(context: Context) {
        val app = context.applicationContext
        val am = app.getSystemService(AlarmManager::class.java)
        val nm = app.getSystemService(NotificationManager::class.java)
        val pending = PendingIntent.getBroadcast(app, 0, Intent(app, FocusReceiver::class.java).setAction(ACTION_DONE), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        am.cancel(pending)
        val r = run
        if (r == null) { DeepFocus.sync(app, null); nm.cancel(NOTE_ONGOING); return }
        nm.cancel(NOTE_DONE)
        r.endsAt()?.let { at ->
            runCatching {
                if (am.canScheduleExactAlarms()) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
                else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
            }
        }
        DeepFocus.sync(app, r)
        if (!Reminders.notificationsAllowed(app)) return
        runCatching { nm.notify(NOTE_ONGOING, ongoingNotification(app, r)) }
    }

    /** Re-posts the countdown notification for the running session (after deep focus lets go of it). */
    fun refreshNotification(context: Context) {
        val app = context.applicationContext
        val r = run ?: return
        if (Reminders.notificationsAllowed(app)) runCatching { app.getSystemService(NotificationManager::class.java).notify(NOTE_ONGOING, ongoingNotification(app, r)) }
    }

    /** The ongoing countdown notification; deep focus's service shows it as its own. */
    fun ongoingNotification(context: Context, r: FocusRun): Notification {
        val app = context.applicationContext
        ensureChannels(app)
        val title = if (r.isBreak) "Break" else r.title.ifBlank { "Focus" }
        val builder = Notification.Builder(app, CHANNEL_RUNNING)
            .setSmallIcon(R.drawable.ic_notification_reminder)
            .setContentTitle(title)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_PROGRESS)
            .setContentIntent(openPending(app))
        val end = r.endsAt()
        if (end != null) builder.setUsesChronometer(true).setChronometerCountDown(true).setWhen(end).setShowWhen(true)
            .setContentText(if (r.isBreak) "Break · back soon" else if (r.deep) "Deep focus" else "Focusing")
        else builder.setShowWhen(false).setContentText("Paused · ${clockText(r.remaining(System.currentTimeMillis()))} left")
        return builder.build()
    }

    fun openPending(context: Context): PendingIntent = PendingIntent.getActivity(
        context, 0x2F0C, WidgetActions.appIntent(context, WidgetActions.FOCUS_OPEN), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    fun ensureChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_RUNNING) == null) nm.createNotificationChannel(
            NotificationChannel(CHANNEL_RUNNING, "Focus timer", NotificationManager.IMPORTANCE_LOW).apply { description = "The focus session that's running" }
        )
        if (nm.getNotificationChannel(CHANNEL_DONE) == null) nm.createNotificationChannel(
            NotificationChannel(CHANNEL_DONE, "Focus finished", NotificationManager.IMPORTANCE_HIGH).apply { description = "When a focus session or break ends" }
        )
    }

    /** The timer ran out (from the alarm): swap the ongoing notification for a "time's up" one. */
    fun postDone(context: Context) {
        val app = context.applicationContext
        val r = read(app) ?: return
        val nm = app.getSystemService(NotificationManager::class.java)
        nm.cancel(NOTE_ONGOING)
        ensureChannels(app)
        if (!Reminders.notificationsAllowed(app)) return
        val notification = Notification.Builder(app, CHANNEL_DONE)
            .setSmallIcon(R.drawable.ic_notification_reminder)
            .setContentTitle(if (r.isBreak) "Break’s over" else "Time’s up · ${r.title}")
            .setContentText(if (r.isBreak) "Ready for the next session?" else "${spanText(r.plannedMillis)} of focus. Add a note about it?")
            .setCategory(Notification.CATEGORY_ALARM)
            .setAutoCancel(true)
            .setContentIntent(openPending(app))
            .build()
        runCatching { nm.notify(NOTE_DONE, notification) }
    }
}

class FocusReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == FocusClock.ACTION_DONE) FocusClock.postDone(context)
    }
}

/** Which Focus sheet or overlay is open; shared by the page (inside the pager) and the overlays at the app's root. */
internal object FocusUi {
    /** null: no editor; "": a new tracker; otherwise the id of the tracker being edited. */
    var editor by mutableStateOf<String?>(null)
    /** (tracker id, session id) of the session whose notes are open. */
    var sheet by mutableStateOf<Pair<String, String>?>(null)
    /** The sheet is for a session that just ended, so it offers a break. */
    var justEnded by mutableStateOf(false)
    var fullScreen by mutableStateOf(false)
    /** Where full screen grows from (the start pill's bounds, and its colour); null to just fade in. */
    var launchFrom by mutableStateOf<Pair<androidx.compose.ui.geometry.Rect, Color>?>(null)
    /** Tracker whose deep focus toggle is waiting on the setup dialog. */
    var deepSetup by mutableStateOf<String?>(null)
}

// ---------------------------------------------------------------------------------------------
// The page

@Composable
internal fun FocusPage(
    trackers: List<BoardCard>,
    onStart: (BoardCard, Int) -> Unit,
    onSetMinutes: (BoardCard, Int) -> Unit,
    onStop: () -> Unit,
    onDelete: (BoardCard) -> Unit,
    modifier: Modifier = Modifier
) {
    val run = FocusClock.run
    val today = LocalDate.now().toEpochDay()
    val todayTotal = focusedOn(trackers, today)
    val todayCount = trackers.sumOf { t -> t.sessions.count { dayOf(it.startedAt) == today } }
    // Which trackers are fanned out to show their history; tapping the card toggles it.
    var expanded by rememberSaveable { mutableStateOf(listOf<String>()) }
    LazyColumn(modifier, contentPadding = PaddingValues(bottom = 24.dp)) {
        item(key = "header") {
            Column(Modifier.fillMaxWidth().padding(start = 22.dp, end = 16.dp, top = 14.dp, bottom = 10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(todayLabel(), color = TodoMuted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp, modifier = Modifier.weight(1f))
                    SettingsButton()
                }
                Text("Focus", color = TodoText, fontSize = 34.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 6.dp))
                Text(
                    when {
                        todayCount > 0 -> "${spanText(todayTotal)} focused today · $todayCount session${if (todayCount == 1) "" else "s"}"
                        trackers.isEmpty() -> "A timer for whatever you're working on"
                        else -> "Nothing focused yet today"
                    },
                    color = TodoMuted, fontSize = 15.sp, modifier = Modifier.padding(top = 2.dp)
                )
            }
        }
        if (run != null) item(key = "active") {
            Box(Modifier.animateItem().padding(horizontal = 14.dp, vertical = 6.dp)) { ActiveSessionCard(run, onStop) }
        }
        if (trackers.isEmpty()) item(key = "empty") {
            Column(Modifier.fillMaxWidth().padding(top = 70.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Glyph(ZenIcon.Timer, TodoMuted, 40.dp)
                Text("Nothing to track yet", color = TodoText, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 14.dp))
                Text("Tap + to add what you spend time on", color = TodoMuted, fontSize = 14.sp, modifier = Modifier.padding(top = 4.dp))
            }
        } else {
            item(key = "trackersLabel") { SectionLabel("Timers", trackers.size.toString(), modifier = Modifier.animateItem().padding(start = 14.dp, end = 14.dp, top = 8.dp)) }
            items(trackers, key = { it.id }) { tracker ->
                val open = tracker.id in expanded
                // No placement spring: the stack's own height animates, and the cards below must follow it exactly.
                Box(Modifier.animateItem(placementSpec = null).padding(horizontal = 14.dp, vertical = 8.dp)) {
                    TrackerStack(
                        tracker, today, open, running = run?.trackerId == tracker.id && !run.isBreak, busy = run != null,
                        onToggle = { expanded = if (open) expanded - tracker.id else expanded + tracker.id },
                        onStart = { onStart(tracker, focusMinutes(tracker)) },
                        onSetMinutes = { onSetMinutes(tracker, it) },
                        onEdit = { FocusUi.editor = tracker.id },
                        onDelete = { onDelete(tracker) }
                    )
                }
            }
        }
    }
}

/** How many sessions a stack holds when fanned out. */
private const val STACK_HISTORY = 20

/**
 * A tracker as a stack of notifications: the tracker's card on top (name, timer, deep focus, start)
 * with its past sessions tucked behind it, peeking out underneath. Tapping the card fans the
 * sessions out into a list below it; tapping again gathers them back into the stack.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TrackerStack(
    tracker: BoardCard, today: Long, expanded: Boolean, running: Boolean, busy: Boolean,
    onToggle: () -> Unit, onStart: () -> Unit, onSetMinutes: (Int) -> Unit, onEdit: () -> Unit, onDelete: () -> Unit
) {
    val history = remember(tracker.sessions) { tracker.sessions.asReversed().take(STACK_HISTORY) }
    val progress = remember { androidx.compose.animation.core.Animatable(if (expanded) 1f else 0f) }
    LaunchedEffect(expanded) {
        progress.animateTo(if (expanded) 1f else 0f, spring(dampingRatio = if (expanded) .72f else .82f, stiffness = if (expanded) 240f else 300f))
    }
    val haptics = LocalHapticFeedback.current
    val color = habitColor(tracker)
    StackLayout(
        progress = { progress.value },
        top = {
            TrackerCard(tracker, today, running, busy, expanded, onToggle = { haptics.performHapticFeedback(HapticFeedbackType.SegmentTick); onToggle() }, onStart, onSetMinutes, onEdit, onDelete)
        },
        count = if (history.isEmpty()) 1 else history.size,
        item = { i ->
            // The words on a tucked-away card fade out, so only its edge shows in the stack.
            val reveal = { stackLocal(progress.value, i, if (history.isEmpty()) 1 else history.size) }
            if (history.isEmpty()) EmptyHistoryCard(reveal)
            else {
                val s = history[i]
                HistoryCard(s, color, reveal) { FocusUi.justEnded = false; FocusUi.sheet = tracker.id to s.id }
            }
        }
    )
}

private const val STACK_STAGGER = .07f

/** How far item [i] of [count] is through its own move, given the stack's overall [p]rogress. */
private fun stackLocal(p: Float, i: Int, count: Int): Float {
    val span = 1f + STACK_STAGGER * (minOf(count, 8) - 1).coerceAtLeast(0)
    return ((p.coerceIn(0f, 1.08f) * span) - STACK_STAGGER * minOf(i, 7)).coerceIn(0f, 1.08f)
}

/**
 * Lays the top card over [count] items. At progress 0 the items sit behind the top card, narrower
 * and squashed, with the first two peeking out below like a notification stack; at 1 they hang in
 * a list underneath. Each item moves a little after the one above it, so the stack cascades open.
 */
@Composable
private fun StackLayout(progress: () -> Float, top: @Composable () -> Unit, count: Int, item: @Composable (Int) -> Unit) {
    val density = LocalDensity.current
    val peek = with(density) { 9.dp.roundToPx() }
    val gap = with(density) { 8.dp.roundToPx() }
    androidx.compose.ui.layout.Layout(
        content = {
            Box { top() }
            repeat(count) { i -> Box { item(i) } }
        }
    ) { measurables, constraints ->
        val loose = constraints.copy(minHeight = 0, maxHeight = androidx.compose.ui.unit.Constraints.Infinity)
        val topPlaceable = measurables[0].measure(loose)
        val items = measurables.drop(1).map { it.measure(loose) }
        val p = progress().coerceIn(0f, 1.08f)
        val topH = topPlaceable.height
        val visibleBehind = minOf(items.size, 2)
        val collapsedH = topH + peek * visibleBehind
        var y = topH + gap
        val openY = items.map { pl -> y.also { y += pl.height + gap } }
        val expandedH = if (items.isEmpty()) topH else y - gap
        val height = (collapsedH + (expandedH - collapsedH) * p.coerceAtLeast(0f)).toInt().coerceAtLeast(topH)
        layout(constraints.maxWidth, height) {
            items.forEachIndexed { i, pl ->
                // Later items start a beat later and all finish together.
                val local = stackLocal(p, i, items.size)
                val depth = minOf(i, 2) + 1 // 1, 2, 3 (the third and beyond hide behind the second)
                val stackBottom = topH + peek * minOf(depth, 2)
                val stackY = stackBottom - pl.height
                val yNow = stackY + ((openY[i] - stackY) * local).toInt()
                pl.placeWithLayer(0, yNow, zIndex = (count - i).toFloat()) {
                    val squash = (topH.toFloat() / pl.height).coerceAtMost(1f)
                    transformOrigin = androidx.compose.ui.graphics.TransformOrigin(.5f, 1f)
                    scaleX = (1f - .045f * depth) + (.045f * depth) * local.coerceAtMost(1f)
                    scaleY = squash + (1f - squash) * local.coerceAtMost(1f)
                    alpha = if (i >= 2) local.coerceIn(0f, 1f) else 1f - (1f - local.coerceIn(0f, 1f)) * .25f * depth
                }
            }
            topPlaceable.place(0, 0, zIndex = (count + 1).toFloat())
        }
    }
}

/** The short card: colour, name and time so far, deep focus and start; then the timer lengths. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TrackerCard(
    tracker: BoardCard, today: Long, running: Boolean, busy: Boolean, expanded: Boolean,
    onToggle: () -> Unit, onStart: () -> Unit, onSetMinutes: (Int) -> Unit, onEdit: () -> Unit, onDelete: () -> Unit
) {
    val context = LocalContext.current
    val color = habitColor(tracker)
    val minutes = focusMinutes(tracker)
    val todayMillis = remember(tracker.sessions, today) { tracker.sessions.filter { dayOf(it.startedAt) == today }.sumOf { it.focusedMillis } }
    val total = remember(tracker.sessions) { tracker.sessions.sumOf { it.focusedMillis } }
    val deep = tracker.id in FocusClock.deepTrackers
    var menu by remember { mutableStateOf(false) }
    var more by remember { mutableStateOf(false) }
    val haptics = LocalHapticFeedback.current
    val shape = RoundedCornerShape(22.dp)
    Surface(color = TodoCard, shape = shape, border = GlassStroke, shadowElevation = if (Look.light) 2.dp else 0.dp, modifier = Modifier.glassBlur(shape).fillMaxWidth().clip(shape).combinedClickable(onClick = onToggle, onLongClick = { menu = true })) {
        Column(Modifier.padding(start = 16.dp, end = 14.dp, top = 14.dp, bottom = 14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(10.dp).clip(CircleShape).background(color))
                Column(Modifier.weight(1f).padding(start = 10.dp)) {
                    Text(tracker.title, color = TodoText, fontSize = 17.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        listOfNotNull(
                            if (running) "Running now" else if (todayMillis > 0) "${spanText(todayMillis)} today" else null,
                            "${spanText(total)} total", "${tracker.sessions.size} session${if (tracker.sessions.size == 1) "" else "s"}"
                        ).joinToString(" · "),
                        color = if (running) color else TodoMuted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp)
                    )
                }
                DeepToggle(deep, color) { on ->
                    haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                    if (on && !DeepFocus.canOverlay(context)) FocusUi.deepSetup = tracker.id
                    else FocusClock.setDeepTracker(context, tracker.id, on)
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("Edit") }, onClick = { menu = false; onEdit() })
                    DropdownMenuItem(text = { Text("Delete", color = Danger) }, onClick = { menu = false; onDelete() })
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                // The length, live as the wheel turns, then the wheel itself.
                var shown by remember(minutes) { mutableStateOf(minutes) }
                Column(Modifier.width(58.dp)) {
                    Text("$shown", color = TodoText, fontSize = 26.sp, fontWeight = FontWeight.Bold, style = androidx.compose.ui.text.TextStyle(fontFeatureSettings = "tnum"))
                    Text("min", color = TodoMuted, fontSize = 12.sp)
                }
                TimeWheel(minutes, color, onSettle = { if (it != minutes) onSetMinutes(it) }, modifier = Modifier.weight(1f), onTurn = { shown = it })
                val turn by animateFloatAsState(if (expanded) 180f else 0f, spring(dampingRatio = .6f, stiffness = Spring.StiffnessMediumLow), label = "chevron")
                Glyph(ZenIcon.ChevronDown, TodoMuted, 18.dp, Modifier.padding(start = 8.dp).graphicsLayer { rotationZ = turn })
            }
            Spacer(Modifier.height(12.dp))
            HoldToStart(color, minutes, running, busy, onStart)
        }
    }
}

/** How long the start pill has to be held. */
private const val HOLD_TO_START_MS = 5_000

/**
 * Lock in (deep focus) on or off: a padlock that, locking, flips its body over to the right to a
 * filled face while the shackle drops and clicks into it; unlocking pops the shackle and flips back.
 */
@Composable
private fun DeepToggle(on: Boolean, color: Color, onChange: (Boolean) -> Unit) {
    val fill by animateColorAsState(if (on) color.copy(alpha = .2f) else veil(0.06f), tween(220), label = "deepFill")
    val tint by animateColorAsState(if (on) color else TodoMuted, tween(220), label = "deepTint")
    Row(
        Modifier.height(44.dp).clip(CircleShape).background(fill).clickable { onChange(!on) }
            .semantics { contentDescription = if (on) "Locked in: other apps are covered during sessions" else "Lock in" }
            .animateContentSize(spring(dampingRatio = .6f, stiffness = Spring.StiffnessMediumLow))
            .padding(start = 12.dp, end = 15.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        LockGlyph(on, tint, 20.dp)
        androidx.compose.animation.AnimatedContent(
            on, label = "lockText",
            transitionSpec = { (fadeIn(tween(180, delayMillis = 90)) + androidx.compose.animation.slideInVertically { it / 2 }) togetherWith fadeOut(tween(90)) }
        ) { locked ->
            Text(if (locked) "Locked in" else "Lock in", color = tint, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 7.dp))
        }
    }
}

/** The padlock itself, drawn on a 24-unit grid like the app's other icons. */
@Composable
private fun LockGlyph(locked: Boolean, tint: Color, size: Dp) {
    val haptics = LocalHapticFeedback.current
    // The body's turn in degrees: +180 each time it locks or unlocks; at odd half-turns the filled face shows.
    val flip = remember { androidx.compose.animation.core.Animatable(if (locked) 180f else 0f) }
    // 1 = shackle up and swung open, 0 = clicked shut.
    val shackle = remember { androidx.compose.animation.core.Animatable(if (locked) 0f else 1f) }
    val first = remember { mutableStateOf(true) }
    LaunchedEffect(locked) {
        if (first.value) { first.value = false; return@LaunchedEffect }
        kotlinx.coroutines.coroutineScope {
            launch { flip.animateTo(flip.value + 180f, tween(340, easing = androidx.compose.animation.core.FastOutSlowInEasing)) }
            launch {
                if (locked) {
                    kotlinx.coroutines.delay(170)
                    shackle.animateTo(0f, spring(dampingRatio = .38f, stiffness = 900f))
                } else shackle.animateTo(1f, spring(dampingRatio = .5f, stiffness = 500f))
            }
            if (locked) launch { kotlinx.coroutines.delay(230); haptics.performHapticFeedback(HapticFeedbackType.Confirm) }
        }
    }
    androidx.compose.foundation.Canvas(Modifier.size(size)) {
        val u = this.size.width / 24f
        val stroke = Stroke(2f * u, cap = StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round)
        // Shackle: up and turned about its left leg while open.
        val open = shackle.value
        val path = androidx.compose.ui.graphics.Path().apply {
            moveTo(7f * u, 11f * u); lineTo(7f * u, 7f * u)
            arcTo(androidx.compose.ui.geometry.Rect(7f * u, 2f * u, 17f * u, 12f * u), 180f, 180f, false)
            lineTo(17f * u, 11f * u)
        }
        withTransform({
            translate(0f, -3.2f * u * open)
            rotate(-24f * open, pivot = Offset(7f * u, 11f * u))
        }) { drawPath(path, tint, style = stroke) }
        // Body: squashed by the cosine of its turn, so it reads as flipping over to the right.
        val turn = flip.value
        val sx = kotlin.math.cos(Math.toRadians(turn.toDouble())).toFloat()
        val filled = (kotlin.math.floor((turn + 90f) / 180f).toInt() % 2) == 1
        withTransform({ scale(kotlin.math.abs(sx).coerceAtLeast(.04f), 1f, pivot = Offset(12f * u, 16.5f * u)) }) {
            val topLeft = Offset(3f * u, 11f * u)
            val bodySize = androidx.compose.ui.geometry.Size(18f * u, 11f * u)
            val corner = androidx.compose.ui.geometry.CornerRadius(2.5f * u)
            if (filled) {
                drawRoundRect(tint, topLeft, bodySize, corner)
                drawCircle(Color.Black.copy(alpha = .28f), 1.6f * u, Offset(12f * u, 16.5f * u))
            } else drawRoundRect(tint, topLeft, bodySize, corner, style = stroke)
        }
    }
}

/**
 * The start button: a wide pill to press and hold. It fills with the timer's colour over
 * [HOLD_TO_START_MS], ticking each second; letting go early drains it. When it fills, the session
 * starts and full screen grows out of the pill.
 */
@Composable
private fun HoldToStart(color: Color, minutes: Int, running: Boolean, busy: Boolean, onStart: () -> Unit) {
    val haptics = LocalHapticFeedback.current
    val fill = remember { androidx.compose.animation.core.Animatable(0f) }
    var bounds by remember { mutableStateOf<androidx.compose.ui.geometry.Rect?>(null) }
    val start by androidx.compose.runtime.rememberUpdatedState(onStart)
    val shape = CircleShape
    val enabled = !busy
    val seconds = (HOLD_TO_START_MS / 1000f * (1f - fill.value)).let { kotlin.math.ceil(it).toInt() }
    Box(
        Modifier.fillMaxWidth().height(58.dp)
            .onGloballyPositioned { bounds = it.boundsInRoot() }
            .clip(shape).background(if (enabled || running) color.copy(alpha = .18f) else veil(0.06f))
            .then(
                if (running) Modifier.clickable { FocusUi.fullScreen = true }
                else if (!enabled) Modifier
                else Modifier.pointerInput(Unit) {
                    detectTapGestures(onPress = {
                        haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                        coroutineScope {
                            val holding = launch {
                                var lastSecond = 0
                                fill.animateTo(1f, tween(((1f - fill.value) * HOLD_TO_START_MS).toInt().coerceAtLeast(1), easing = LinearEasing)) {
                                    val s = (value * HOLD_TO_START_MS / 1000f).toInt()
                                    if (s > lastSecond) { lastSecond = s; haptics.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick) }
                                }
                                haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                                FocusUi.launchFrom = bounds?.let { it to color }
                                start()
                                fill.snapTo(0f)
                            }
                            tryAwaitRelease()
                            if (holding.isActive) {
                                holding.cancel()
                                launch { fill.animateTo(0f, spring(dampingRatio = .8f, stiffness = 300f)) }
                            }
                        }
                    })
                }
            )
            .semantics { contentDescription = if (running) "Open the running session" else "Hold to start a $minutes minute session" },
        contentAlignment = Alignment.Center
    ) {
        // The fill grows from the left as it's held.
        Box(Modifier.matchParentSize().graphicsLayer { scaleX = fill.value; transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0f, .5f) }.background(color))
        val onFill = fill.value > .5f
        Row(verticalAlignment = Alignment.CenterVertically) {
            Glyph(if (running) ZenIcon.Maximize else ZenIcon.Play, if (onFill) OnAccent else if (enabled || running) color else TodoMuted, 18.dp)
            Spacer(Modifier.width(10.dp))
            Text(
                when {
                    running -> "Running · open"
                    !enabled -> "Another session is running"
                    fill.value > 0f -> "Keep holding · $seconds"
                    else -> "Hold to start · ${durationLabel(minutes)}"
                },
                color = if (onFill) OnAccent else if (enabled || running) TodoText else TodoMuted, fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                style = androidx.compose.ui.text.TextStyle(fontFeatureSettings = "tnum")
            )
        }
    }
}

/** A past session in the stack: when, how long, its notes and the apps used. */
@Composable
private fun HistoryCard(session: FocusSession, color: Color, reveal: () -> Float, onOpen: () -> Unit) {
    val shape = RoundedCornerShape(18.dp)
    Surface(color = TodoCard, shape = shape, border = GlassStroke, shadowElevation = if (Look.light) 1.dp else 0.dp, modifier = Modifier.glassBlur(shape).fillMaxWidth().clip(shape).clickable(onClick = onOpen)) {
        Column(Modifier.graphicsLayer { alpha = ((reveal() - .35f) / .65f).coerceIn(0f, 1f) }.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(sessionWhen(session), color = TodoText, fontSize = 14.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                if (session.deep) { Glyph(ZenIcon.Lock, TodoMuted, 12.dp); Spacer(Modifier.width(6.dp)) }
                Text(spanText(session.focusedMillis), color = if (session.completed) color else TodoMuted, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                if (session.completed) { Spacer(Modifier.width(4.dp)); Glyph(ZenIcon.CircleCheck, color, 13.dp) }
            }
            if (session.note.isNotBlank()) Text(session.note, color = TodoText.copy(alpha = .85f), fontSize = 14.sp, lineHeight = 19.sp, maxLines = 4, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
            else Text("No notes · tap to add", color = TodoMuted.copy(alpha = .8f), fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
            if (session.apps.isNotEmpty()) MetaLine(appsSummary(session.apps), TodoMuted, 12.sp, Modifier.padding(top = 6.dp), leading = ZenIcon.Board)
        }
    }
}

@Composable
private fun EmptyHistoryCard(reveal: () -> Float) {
    val shape = RoundedCornerShape(18.dp)
    Surface(color = TodoCard, shape = shape, border = GlassStroke, modifier = Modifier.glassBlur(shape).fillMaxWidth().clip(shape)) {
        Text("No sessions yet. Start one and it’ll show up here.", color = TodoMuted, fontSize = 13.sp, modifier = Modifier.graphicsLayer { alpha = ((reveal() - .35f) / .65f).coerceIn(0f, 1f) }.padding(horizontal = 16.dp, vertical = 14.dp))
    }
}

/** Turning deep focus on: what it does, and the two permissions it needs. */
@Composable
internal fun DeepFocusSetup(trackerId: String, onClose: () -> Unit) {
    val context = LocalContext.current
    var overlay by remember { mutableStateOf(DeepFocus.canOverlay(context)) }
    var usage by remember { mutableStateOf(DeepFocus.hasUsageAccess(context)) }
    // Coming back from Settings: pick up what was granted.
    LaunchedEffect(Unit) { while (true) { overlay = DeepFocus.canOverlay(context); usage = DeepFocus.hasUsageAccess(context); kotlinx.coroutines.delay(700) } }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onClose,
        containerColor = GlassSheet,
        icon = { Glyph(ZenIcon.Lock, TodoBlue, 26.dp) },
        title = { Text("Deep focus", color = TodoText) },
        text = {
            Column {
                Text("While a deep focus session runs, Daymark covers every other app until the timer ends. Calls still come through, and you can hold “end deep focus” if you really need to.", color = TodoMuted, fontSize = 14.sp, lineHeight = 20.sp)
                Spacer(Modifier.height(16.dp))
                PermissionRow("Display over other apps", "To cover other apps", overlay) { context.startActivity(DeepFocus.overlaySettings(context)) }
                Spacer(Modifier.height(10.dp))
                PermissionRow("Usage access", "To let calls through and record which apps you open during a session", usage) {
                    runCatching { context.startActivity(DeepFocus.usageSettings(context)) }.onFailure { context.startActivity(Intent(android.provider.Settings.ACTION_USAGE_ACCESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                }
            }
        },
        confirmButton = {
            androidx.compose.material3.TextButton(enabled = overlay, onClick = { FocusClock.setDeepTracker(context, trackerId, true); onClose() }) { Text("Turn on", color = if (overlay) TodoBlue else TodoMuted) }
        },
        dismissButton = { androidx.compose.material3.TextButton(onClick = onClose) { Text("Cancel", color = TodoMuted) } }
    )
}

@Composable
private fun PermissionRow(title: String, why: String, granted: Boolean, onAllow: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, color = TodoText, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            Text(why, color = TodoMuted, fontSize = 12.sp, lineHeight = 16.sp)
        }
        Spacer(Modifier.width(10.dp))
        if (granted) Glyph(ZenIcon.CircleCheck, TodoBlue, 22.dp)
        else Surface(color = CalendarBlue, shape = CircleShape, modifier = Modifier.clip(CircleShape).clickable(onClick = onAllow)) {
            Text("Allow", color = OnFill, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp))
        }
    }
}

/** A selectable timer length; the chosen one fills with the tracker's colour and rounds into a pill. */
@Composable
private fun MinuteChip(label: String, selected: Boolean, color: Color, trailing: ZenIcon? = null, onClick: () -> Unit) {
    val corner by animateFloatAsState(if (selected) 50f else 30f, spring(dampingRatio = .55f, stiffness = Spring.StiffnessMediumLow), label = "chipCorner")
    val fill by animateColorAsState(if (selected) color else veil(0.06f), tween(220), label = "chipFill")
    val shape = RoundedCornerShape(percent = (corner.coerceIn(0f, 50f)).toInt())
    Row(
        Modifier.clip(shape).background(fill).then(if (selected) Modifier else Modifier.border(GlassStroke, shape)).clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, color = if (selected) OnAccent else TodoText, fontSize = 13.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium)
        if (trailing != null) { Spacer(Modifier.width(4.dp)); Glyph(trailing, if (selected) OnAccent else TodoMuted, 14.dp) }
    }
}

// ---------------------------------------------------------------------------------------------
// The running session

/** The running session, in a card at the top of the page: ring, controls and a note. */
@Composable
private fun ActiveSessionCard(run: FocusRun, onStop: () -> Unit) {
    val context = LocalContext.current
    val shape = RoundedCornerShape(28.dp)
    val color = run.color
    Surface(color = TodoCard, shape = shape, border = GlassStroke, modifier = Modifier.glassBlur(shape).fillMaxWidth().clip(shape)) {
        Column(Modifier.background(Brush.verticalGradient(listOf(color.copy(alpha = .14f), Color.Transparent))).padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(10.dp).clip(CircleShape).background(color))
                Text(
                    (if (run.isBreak) "BREAK" else if (run.deep) "DEEP FOCUS" else "FOCUS") + if (run.paused) " · PAUSED" else "", color = TodoMuted, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp,
                    modifier = Modifier.padding(start = 8.dp).weight(1f)
                )
                HeaderIcon(ZenIcon.Maximize, "Full screen") { FocusUi.fullScreen = true }
            }
            if (!run.isBreak) Text(run.title, color = TodoText, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.fillMaxWidth().padding(start = 18.dp))
            Spacer(Modifier.height(10.dp))
            FocusRing(run, 210.dp, 46.sp)
            Spacer(Modifier.height(16.dp))
            FocusControls(run, 76.dp, onStop)
            if (!run.isBreak) {
                Spacer(Modifier.height(16.dp))
                NoteBox(run.note, { FocusClock.setNote(context, it) }, minHeight = 64.dp)
            }
        }
    }
}

/** The countdown: a Material 3 Expressive wavy ring that flattens while paused, time in the middle. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun FocusRing(run: FocusRun, size: Dp, textSize: TextUnit) {
    val density = LocalDensity.current
    val wave by animateFloatAsState(if (run.paused) 0f else 1f, spring(dampingRatio = .6f, stiffness = Spring.StiffnessLow), label = "wave")
    val strokeWidth = with(density) { (size / 22).toPx() }
    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        CircularWavyProgressIndicator(
            progress = { (run.elapsed(FocusClock.now) / run.plannedMillis.toFloat()).coerceIn(0f, 1f) },
            modifier = Modifier.matchParentSize(),
            color = run.color,
            trackColor = veil(0.09f),
            stroke = Stroke(strokeWidth, cap = StrokeCap.Round),
            trackStroke = Stroke(strokeWidth, cap = StrokeCap.Round),
            amplitude = { wave },
            wavelength = size / 7
        )
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            RemainingText(run, textSize)
            Text(
                if (run.paused) "Paused" else "of ${durationLabel((run.plannedMillis / 60_000L).toInt())}",
                color = TodoMuted, fontSize = (textSize.value * .3f).sp
            )
        }
    }
}

/** Only this text re-reads the clock every tick. */
@Composable
private fun RemainingText(run: FocusRun, textSize: TextUnit) {
    Text(
        clockText(run.remaining(FocusClock.now)), color = TodoText, fontSize = textSize, fontWeight = FontWeight.SemiBold,
        style = androidx.compose.ui.text.TextStyle(fontFeatureSettings = "tnum")
    )
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
private val PlayMorph by lazy { Morph(MaterialShapes.Square, MaterialShapes.Cookie9Sided) }

/**
 * Stop · Pause/Resume · +5 min. The big middle button is a Material 3 Expressive shape: a slowly
 * turning scalloped cookie while the timer runs, morphing to a soft square when paused.
 */
@Composable
private fun FocusControls(run: FocusRun, size: Dp, onStop: () -> Unit) {
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    val morph by animateFloatAsState(if (run.paused) 0f else 1f, spring(dampingRatio = .45f, stiffness = Spring.StiffnessMediumLow), label = "playMorph")
    val spin = rememberInfiniteTransition(label = "spin")
    val angle by spin.animateFloat(0f, 360f, infiniteRepeatable(tween(24_000, easing = LinearEasing), RepeatMode.Restart), label = "angle")
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
        RoundButton(ZenIcon.Stop, if (run.isBreak) "Skip break" else "Finish session", size * .66f) { haptics.performHapticFeedback(HapticFeedbackType.Confirm); onStop() }
        Box(
            Modifier.size(size).clip(CircleShape).clickable {
                haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                if (run.paused) FocusClock.resume(context) else FocusClock.pause(context)
            }.semantics { contentDescription = if (run.paused) "Resume" else "Pause" },
            contentAlignment = Alignment.Center
        ) {
            val shape = remember(morph) { MorphShape(PlayMorph, morph.coerceIn(0f, 1f)) }
            Box(Modifier.matchParentSize().graphicsLayer { rotationZ = if (run.paused) 0f else angle * morph }.clip(shape).background(run.color))
            Glyph(if (run.paused) ZenIcon.Play else ZenIcon.Pause, OnAccent, size * .36f)
        }
        Box(
            Modifier.size(size * .66f).clip(CircleShape).background(veil(0.08f)).clickable { FocusClock.addMinutes(context, 5) }
                .semantics { contentDescription = "Add five minutes" },
            contentAlignment = Alignment.Center
        ) { Text("+5", color = TodoText, fontSize = (size.value * .2f).sp, fontWeight = FontWeight.SemiBold) }
    }
}

@Composable
private fun RoundButton(icon: ZenIcon, description: String, size: Dp, onClick: () -> Unit) {
    Box(
        Modifier.size(size).clip(CircleShape).background(veil(0.08f)).clickable(onClick = onClick).semantics { contentDescription = description },
        contentAlignment = Alignment.Center
    ) { Glyph(icon, TodoText, size * .38f) }
}

/** The tracker's start button: a filled circle that squishes into a cookie when pressed. */
@Composable
private fun PlayButton(color: Color, enabled: Boolean, description: String, onClick: () -> Unit) {
    val fill by animateColorAsState(if (enabled) color else veil(0.08f), tween(200), label = "playFill")
    Box(
        Modifier.size(44.dp).clip(RoundedCornerShape(16.dp)).background(fill).clickable(enabled = enabled, onClick = onClick).semantics { contentDescription = description },
        contentAlignment = Alignment.Center
    ) { Glyph(ZenIcon.Play, if (enabled) OnAccent else TodoMuted, 18.dp) }
}

@Composable
private fun NoteBox(value: String, onChange: (String) -> Unit, minHeight: Dp, textSize: TextUnit = 15.sp) {
    val shape = RoundedCornerShape(18.dp)
    Box(Modifier.fillMaxWidth().heightIn(min = minHeight).clip(shape).background(veil(0.05f)).border(GlassStroke, shape).padding(horizontal = 14.dp, vertical = 12.dp)) {
        SheetField(value = value, onValueChange = onChange, hint = "Notes for this session…", textSize = textSize, singleLine = false)
    }
}

// ---------------------------------------------------------------------------------------------
// Full screen

/**
 * The running session on its own: system bars hidden, laid out as tiles. A big countdown with a
 * wavy progress line, pause/play, +5 min, when it ends, finish and notes, then an app dock (Phone,
 * Messages, Camera and three of your own) that deep focus lets through.
 *
 * With the always-on display setting, the screen stays awake and, after a few seconds untouched,
 * dims to outlines on black at low brightness, drifting a little now and then against burn-in. A tap wakes it.
 */
@Composable
internal fun FocusFullScreen(onStop: () -> Unit) {
    val run = FocusClock.run
    AnimatedVisibility(
        visible = FocusUi.fullScreen && run != null,
        // Started from a pill: the pill itself grows into the screen (below), so no fade here.
        enter = if (FocusUi.launchFrom != null) androidx.compose.animation.EnterTransition.None
            else fadeIn(tween(260)) + scaleIn(spring(dampingRatio = .75f, stiffness = Spring.StiffnessMediumLow), initialScale = .92f),
        exit = fadeOut(tween(200)) + scaleOut(tween(200), targetScale = .96f),
        modifier = Modifier.zIndex(10f)
    ) {
        val shown = FocusClock.run ?: return@AnimatedVisibility
        val from = remember { FocusUi.launchFrom }
        val grow = remember { androidx.compose.animation.core.Animatable(if (from != null) 0f else 1f) }
        LaunchedEffect(Unit) {
            if (from != null) {
                // Let the first (heavy) frames of full screen land before the growth starts, so none of it is skipped.
                androidx.compose.runtime.withFrameNanos { }; androidx.compose.runtime.withFrameNanos { }
                grow.animateTo(1f, spring(dampingRatio = .86f, stiffness = 170f))
                FocusUi.launchFrom = null
            }
        }
        val context = LocalContext.current
        val view = LocalView.current
        val aod = FocusClock.aod
        var dim by remember { mutableStateOf(false) }
        var lastTouch by remember { mutableLongStateOf(System.currentTimeMillis()) }
        val window = (view.context as? android.app.Activity)?.window
        DisposableEffect(Unit) {
            val controller = window?.let { WindowCompat.getInsetsController(it, view) }
            controller?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller?.hide(WindowInsetsCompat.Type.systemBars())
            onDispose {
                controller?.show(WindowInsetsCompat.Type.systemBars())
                view.keepScreenOn = false
                window?.attributes = window?.attributes?.apply { screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE }
            }
        }
        LaunchedEffect(aod) { view.keepScreenOn = aod; if (!aod) dim = false }
        LaunchedEffect(lastTouch, aod) { if (aod) { kotlinx.coroutines.delay(8_000); dim = true } }
        LaunchedEffect(dim) {
            window?.attributes = window?.attributes?.apply { screenBrightness = if (dim) .04f else WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE }
        }
        BackHandler { if (dim) { dim = false; lastTouch = System.currentTimeMillis() } else FocusUi.fullScreen = false }
        var writing by rememberSaveable { mutableStateOf(false) }
        var pickSlot by remember { mutableStateOf<Int?>(null) }

        val glow by animateFloatAsState(if (dim) 0f else 1f, tween(900), label = "aodGlow")
        val ink by animateColorAsState(if (dim) Color(0xFF6E6A63) else TodoText, tween(900), label = "aodInk")
        // AOD drifts a few pixels each minute so the same pixels aren't lit all night.
        val minute = (FocusClock.now / 60_000L).toInt()
        val drift = if (dim) ((minute % 5) - 2) * 3 else 0
        val back = if (dim) Color.Black else TodoBackground
        Box(
            Modifier.fillMaxSize()
                .graphicsLayer {
                    // Clip to a rounded box that grows from the pill to the whole screen.
                    val f = from ?: return@graphicsLayer
                    val g = grow.value.coerceIn(0f, 1.02f)
                    if (g >= 1f) return@graphicsLayer
                    val r = f.first
                    val left = r.left * (1 - g); val top = r.top * (1 - g)
                    val right = r.right + (size.width - r.right) * g; val bottom = r.bottom + (size.height - r.bottom) * g
                    val radius = (r.height / 2f) * (1 - g) + 0f
                    clip = true
                    shape = object : androidx.compose.ui.graphics.Shape {
                        override fun createOutline(size: androidx.compose.ui.geometry.Size, layoutDirection: androidx.compose.ui.unit.LayoutDirection, density: androidx.compose.ui.unit.Density) =
                            androidx.compose.ui.graphics.Outline.Rounded(androidx.compose.ui.geometry.RoundRect(left, top, right, bottom, androidx.compose.ui.geometry.CornerRadius(radius)))
                    }
                }
                .background(back)
                .background(Brush.radialGradient(listOf(shown.color.copy(alpha = (if (Look.light) .22f else .16f) * glow), Color.Transparent), radius = 1400f))
                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Initial)
                            lastTouch = System.currentTimeMillis()
                            // The first touch on a dimmed screen only wakes it.
                            if (dim) { event.changes.forEach { it.consume() }; dim = false }
                        }
                    }
                }
        ) {
            Column(
                Modifier.fillMaxSize().graphicsLayer { alpha = if (from == null) 1f else ((grow.value - .35f) / .65f).coerceIn(0f, 1f) }
                    .statusBarsPadding().navigationBarsPadding().imePadding().padding(horizontal = 16.dp, vertical = 10.dp)
                    .graphicsLayer { translationX = drift.dp.toPx(); translationY = (drift * 2).dp.toPx() }
            ) {
                Row(Modifier.fillMaxWidth().graphicsLayer { alpha = glow }, verticalAlignment = Alignment.CenterVertically) {
                    HeaderIcon(ZenIcon.Minimize, "Leave full screen") { FocusUi.fullScreen = false }
                    Spacer(Modifier.weight(1f))
                    if (shown.deep) { Glyph(ZenIcon.Lock, TodoMuted, 13.dp); Spacer(Modifier.width(6.dp)) }
                    Text(
                        (if (shown.isBreak) "BREAK" else if (shown.deep) "DEEP FOCUS" else "FOCUS") + if (shown.paused) " · PAUSED" else "",
                        color = TodoMuted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.4.sp
                    )
                    Spacer(Modifier.weight(1f))
                    if (!shown.isBreak) HeaderIcon(ZenIcon.Pencil, if (writing) "Hide notes" else "Write notes", tint = if (writing) shown.color else TodoText) { writing = !writing }
                    else Spacer(Modifier.size(42.dp))
                }
                Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.Center) {
                    Spacer(Modifier.height(8.dp))
                    // The countdown.
                    Tile(dim, Modifier.fillMaxWidth()) {
                        Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 22.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            if (!shown.isBreak) Text(shown.title, color = if (dim) ink else TodoMuted, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                                val size = with(LocalDensity.current) { (maxWidth.toPx() / 4.2f).toSp() }
                                Text(
                                    clockText(shown.remaining(FocusClock.now)), color = ink, fontSize = size, fontWeight = if (dim) FontWeight.Light else FontWeight.Bold,
                                    style = androidx.compose.ui.text.TextStyle(fontFeatureSettings = "tnum"), maxLines = 1
                                )
                            }
                            if (!dim) FocusLine(shown, Modifier.fillMaxWidth().padding(top = 6.dp))
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    Row(Modifier.fillMaxWidth().height(156.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Tile(dim, Modifier.weight(1f).fillMaxHeight()) {
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                if (dim) Text(if (shown.paused) "Paused" else "Focusing", color = ink, fontSize = 16.sp)
                                else PlayPauseButton(shown, 104.dp)
                            }
                        }
                        Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Tile(dim, Modifier.fillMaxWidth().weight(1f), onClick = if (dim) null else ({ FocusClock.addMinutes(context, 5) })) {
                                Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                                    Text("+5", color = ink, fontSize = 26.sp, fontWeight = FontWeight.Bold)
                                    Text("min", color = TodoMuted, fontSize = 12.sp)
                                }
                            }
                            Tile(dim, Modifier.fillMaxWidth().weight(1f)) {
                                Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                                    Text("ENDS", color = TodoMuted, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp)
                                    Spacer(Modifier.width(8.dp))
                                    val end = shown.endsAt()?.let { Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()) }
                                    Text(end?.let { formatClock(it.hour * 60 + it.minute) } ?: "Paused", color = ink, fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
                                }
                            }
                        }
                    }
                    if (!dim) {
                        Spacer(Modifier.height(10.dp))
                        Tile(false, Modifier.fillMaxWidth().height(58.dp), onClick = onStop) {
                            Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                                Glyph(ZenIcon.Stop, TodoText, 18.dp)
                                Spacer(Modifier.width(10.dp))
                                Text(if (shown.isBreak) "Skip break" else "Finish session", color = TodoText, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                            }
                        }
                        AnimatedVisibility(writing && !shown.isBreak) {
                            Box(Modifier.padding(top = 10.dp)) { NoteBox(shown.note, { FocusClock.setNote(context, it) }, minHeight = 110.dp, textSize = 16.sp) }
                        }
                    }
                }
                AnimatedVisibility(!dim, enter = fadeIn(tween(300)), exit = fadeOut(tween(600))) {
                    FocusDockRow(shown, onPick = { pickSlot = it })
                }
            }
            // While it grows, the pill's colour washes the whole shape and fades as the timer appears.
            if (from != null && grow.value < 1f) Box(Modifier.matchParentSize().background(from.second.copy(alpha = (1f - grow.value / .6f).coerceIn(0f, 1f))))
        }
        pickSlot?.let { slot ->
            val apps = remember { installedApps(context) }
            AppPickerDialog(apps, onDismiss = { pickSlot = null }, onSelect = { FocusDock.setSlot(context, slot, it.packageName); pickSlot = null })
        }
    }
}

/** One tile of the full-screen layout; on the always-on display, only its outline. */
@Composable
private fun Tile(dim: Boolean, modifier: Modifier, onClick: (() -> Unit)? = null, content: @Composable () -> Unit) {
    val shape = RoundedCornerShape(30.dp)
    val fill by animateColorAsState(if (dim) Color.Transparent else TodoCard, tween(900), label = "tileFill")
    val edge by animateColorAsState(if (dim) Color(0xFF4A463F) else Color.Transparent, tween(900), label = "tileEdge")
    Box(
        modifier.clip(shape).background(fill).border(1.dp, edge, shape).then(if (dim) Modifier else Modifier.border(GlassStroke, shape))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
    ) { content() }
}

/** Progress as a Material 3 Expressive wavy line that lies flat while paused. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun FocusLine(run: FocusRun, modifier: Modifier) {
    val wave by animateFloatAsState(if (run.paused) 0f else 1f, spring(dampingRatio = .6f, stiffness = Spring.StiffnessLow), label = "lineWave")
    androidx.compose.material3.LinearWavyProgressIndicator(
        progress = { (run.elapsed(FocusClock.now) / run.plannedMillis.toFloat()).coerceIn(0f, 1f) },
        modifier = modifier.height(14.dp), color = run.color, trackColor = veil(0.09f), amplitude = { wave }
    )
}

/** The big morphing pause/play button on its own. */
@Composable
private fun PlayPauseButton(run: FocusRun, size: Dp) {
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    val morph by animateFloatAsState(if (run.paused) 0f else 1f, spring(dampingRatio = .45f, stiffness = Spring.StiffnessMediumLow), label = "playMorphFull")
    val spin = rememberInfiniteTransition(label = "spinFull")
    val angle by spin.animateFloat(0f, 360f, infiniteRepeatable(tween(24_000, easing = LinearEasing), RepeatMode.Restart), label = "angleFull")
    Box(
        Modifier.size(size).clip(CircleShape).clickable {
            haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
            if (run.paused) FocusClock.resume(context) else FocusClock.pause(context)
        }.semantics { contentDescription = if (run.paused) "Resume" else "Pause" },
        contentAlignment = Alignment.Center
    ) {
        val shape = remember(morph) { MorphShape(PlayMorph, morph.coerceIn(0f, 1f)) }
        Box(Modifier.matchParentSize().graphicsLayer { rotationZ = if (run.paused) 0f else angle * morph }.clip(shape).background(run.color))
        Glyph(if (run.paused) ZenIcon.Play else ZenIcon.Pause, OnAccent, size * .36f)
    }
}

/** Phone, Messages and Camera, then three slots of your own (tap an empty one to choose, hold a full one to clear). */
@Composable
private fun FocusDockRow(run: FocusRun, onPick: (Int) -> Unit) {
    val context = LocalContext.current
    fun open(intent: Intent?) { if (intent != null) runCatching { context.startActivity(intent) } }
    Column(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        if (run.deep && !DeepFocus.hasUsageAccess(context)) Text("Allow usage access so these open during deep focus", color = TodoMuted, fontSize = 12.sp, modifier = Modifier.padding(bottom = 8.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.clip(CircleShape).background(TodoCard).border(GlassStroke, CircleShape).padding(6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                DockButton(ZenIcon.Phone, null, "Phone", 50.dp, TodoText, veil(0.06f), onClick = { open(FocusDock.phone()) })
                DockButton(ZenIcon.Message, null, "Messages", 50.dp, TodoText, veil(0.06f), onClick = { open(FocusDock.messages()) })
                DockButton(ZenIcon.Camera, null, "Camera", 50.dp, TodoText, veil(0.06f), onClick = { open(FocusDock.camera()) })
            }
            Row(Modifier.clip(CircleShape).background(TodoCard).border(GlassStroke, CircleShape).padding(6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FocusDock.custom.forEachIndexed { i, pkg ->
                    val icon = rememberAppIcon(pkg)
                    if (pkg.isBlank() || icon == null) DockButton(null, null, "Add an app", 50.dp, TodoMuted, Color.Transparent, onClick = { onPick(i) })
                    else DockButton(null, icon, pkg, 50.dp, TodoText, veil(0.06f), onClick = { open(FocusDock.launch(context, pkg)) }, onLongClick = { FocusDock.setSlot(context, i, "") })
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Sheets

/** Add or edit a tracker: its name, timer length and colour. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TrackerEditor(editing: BoardCard?, starter: BoardCard, onDismiss: () -> Unit, onSave: (BoardCard) -> Unit, onDelete: (BoardCard) -> Unit) {
    var title by rememberSaveable(editing?.id) { mutableStateOf(editing?.title.orEmpty()) }
    var minutes by rememberSaveable(editing?.id) { mutableStateOf(editing?.let(::focusMinutes) ?: FOCUS_DEFAULT_MINUTES) }
    var colorIndex by rememberSaveable(editing?.id) { mutableStateOf(habitColorIndex(editing ?: starter)) }
    var more by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { if (editing == null) runCatching { focus.requestFocus() } }
    val canSave = title.isNotBlank()
    fun save() { if (canSave) onSave((editing ?: starter).copy(title = title.trim(), durationMinutes = minutes, habitColor = colorIndex)) }
    val accent = Color(HabitPalette[colorIndex].toInt())
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = GlassSheet,
        dragHandle = null,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
    ) {
        Column(Modifier.fillMaxWidth().imePadding().navigationBarsPadding()) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(top = 22.dp, bottom = 14.dp)) {
                Text(if (editing == null) "NEW TIMER" else "EDIT TIMER", color = TodoMuted, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp)
                Spacer(Modifier.height(10.dp))
                SheetField(value = title, onValueChange = { title = it }, hint = "e.g. Deep work, Study, Reading", textSize = 22.sp, modifier = Modifier.focusRequester(focus), imeAction = ImeAction.Send, onSend = ::save)
                Spacer(Modifier.height(18.dp))
                Text("TIMER", color = TodoMuted, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp)
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    var shown by remember(minutes) { mutableStateOf(minutes) }
                    Column(Modifier.width(64.dp)) {
                        Text("$shown", color = TodoText, fontSize = 28.sp, fontWeight = FontWeight.Bold, style = androidx.compose.ui.text.TextStyle(fontFeatureSettings = "tnum"))
                        Text("min", color = TodoMuted, fontSize = 12.sp)
                    }
                    TimeWheel(minutes, accent, onSettle = { minutes = it }, modifier = Modifier.weight(1f), onTurn = { shown = it })
                }
                Text("Turn the wheel to set how long each session runs.", color = TodoMuted, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
                Spacer(Modifier.height(18.dp))
                Text("COLOUR", color = TodoMuted, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp)
                Spacer(Modifier.height(10.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    HabitPalette.forEachIndexed { i, argb ->
                        val selected = i == colorIndex
                        Box(
                            Modifier.size(34.dp).clip(CircleShape).border(2.dp, if (selected) TodoText else Color.Transparent, CircleShape).padding(4.dp)
                                .clip(CircleShape).background(Color(argb.toInt())).clickable { colorIndex = i }
                                .semantics { contentDescription = HabitColorNames[i] },
                            contentAlignment = Alignment.Center
                        ) { if (selected) Glyph(ZenIcon.Check, OnAccent, 14.dp) }
                    }
                }
            }
            HorizontalDivider(color = TodoBorder)
            Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                if (editing != null) Text("Delete", color = Danger, fontSize = 15.sp, modifier = Modifier.clip(RoundedCornerShape(12.dp)).clickable { onDelete(editing) }.padding(horizontal = 10.dp, vertical = 10.dp))
                Spacer(Modifier.weight(1f))
                Surface(color = if (canSave) accent else TodoCardRaised, shape = RoundedCornerShape(14.dp), modifier = Modifier.clip(RoundedCornerShape(14.dp)).clickable(enabled = canSave) { save() }) {
                    Text(if (editing == null) "Add timer" else "Save", color = if (canSave) OnAccent else TodoMuted, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp))
                }
            }
        }
    }
}

/** A finished session: how long, when, and its notes to write or edit. Notes save as the sheet closes. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SessionSheet(
    tracker: BoardCard, session: FocusSession, justEnded: Boolean,
    onSaveNote: (String) -> Unit, onDelete: () -> Unit, onBreak: () -> Unit, onClose: () -> Unit
) {
    var note by rememberSaveable(session.id) { mutableStateOf(session.note) }
    val color = habitColor(tracker)
    fun close() { if (note.trim() != session.note) onSaveNote(note.trim()); onClose() }
    ModalBottomSheet(
        onDismissRequest = ::close,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = GlassSheet,
        dragHandle = null,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
    ) {
        Column(Modifier.fillMaxWidth().imePadding().navigationBarsPadding()) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(top = 22.dp, bottom = 14.dp)) {
                Text(
                    if (justEnded) (if (session.completed) "SESSION DONE" else "SESSION SAVED") else "SESSION",
                    color = TodoMuted, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp
                )
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(12.dp).clip(CircleShape).background(color))
                    Text(tracker.title, color = TodoText, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 10.dp))
                }
                val end = Instant.ofEpochMilli(session.endedAt).atZone(ZoneId.systemDefault())
                Text(
                    "${spanText(session.focusedMillis)} focused · ${sessionWhen(session)}" + formatClock(end.hour * 60 + end.minute).let { e -> if (sessionWhen(session).endsWith(e)) "" else " – $e" },
                    color = TodoMuted, fontSize = 14.sp, modifier = Modifier.padding(top = 6.dp)
                )
                if (session.apps.isNotEmpty()) {
                    Spacer(Modifier.height(16.dp))
                    Text("APPS USED", color = TodoMuted, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp)
                    session.apps.take(6).forEach { app ->
                        Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(app.label, color = TodoText, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                            Text(spanText(app.millis), color = TodoMuted, fontSize = 13.sp)
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
                Text("NOTES", color = TodoMuted, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp)
                Spacer(Modifier.height(8.dp))
                val shape = RoundedCornerShape(18.dp)
                Box(Modifier.fillMaxWidth().heightIn(min = 120.dp).clip(shape).background(veil(0.05f)).border(GlassStroke, shape).padding(horizontal = 14.dp, vertical = 12.dp)) {
                    SheetField(value = note, onValueChange = { note = it }, hint = "What did you get done? What's next?", textSize = 16.sp, singleLine = false)
                }
            }
            HorizontalDivider(color = TodoBorder)
            Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Delete", color = Danger, fontSize = 15.sp, modifier = Modifier.clip(RoundedCornerShape(12.dp)).clickable { onDelete() }.padding(horizontal = 10.dp, vertical = 10.dp))
                Spacer(Modifier.weight(1f))
                if (justEnded && FocusClock.run == null) {
                    Surface(color = veil(0.07f), shape = RoundedCornerShape(14.dp), border = GlassStroke, modifier = Modifier.clip(RoundedCornerShape(14.dp)).clickable { if (note.trim() != session.note) onSaveNote(note.trim()); onBreak() }) {
                        MetaLine("$FOCUS_BREAK_MINUTES min break", TodoText, 15.sp, Modifier.padding(horizontal = 14.dp, vertical = 12.dp), leading = ZenIcon.Leaf, unbounded = true)
                    }
                    Spacer(Modifier.width(8.dp))
                }
                Surface(color = color, shape = RoundedCornerShape(14.dp), modifier = Modifier.clip(RoundedCornerShape(14.dp)).clickable { close() }) {
                    Text("Save", color = OnAccent, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp))
                }
            }
        }
    }
}
