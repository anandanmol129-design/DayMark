package com.anmol.daymark

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Build
import android.provider.MediaStore
import android.view.HapticFeedbackConstants
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

internal val FOCUS_RANGE = 1..180

/** Degrees the wheel turns per minute. */
private const val WHEEL_STEP_DEG = 6f

/**
 * Picks a timer length by turning a wheel: tick marks round the edge of a dial seen side on, a
 * minute apart, with every fifth one labelled. Drag left for more time and right for less, or
 * flick it; it settles on a whole minute. Each minute it passes clicks and ticks under the finger.
 * [onSettle] gets the new length once the wheel comes to rest.
 */
@Composable
internal fun TimeWheel(minutes: Int, color: Color, onSettle: (Int) -> Unit, modifier: Modifier = Modifier, height: Dp = 58.dp, onTurn: (Int) -> Unit = {}) {
    val context = LocalContext.current
    val view = LocalView.current
    val pos = remember { Animatable(minutes.toFloat()) }
    var touching by remember { mutableStateOf(false) }
    var spinning by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val settle by rememberUpdatedState(onSettle)
    val turn by rememberUpdatedState(onTurn)
    // A new length from elsewhere (another card, the editor) while the wheel is at rest.
    LaunchedEffect(minutes) { if (!touching && !spinning && pos.value.roundToInt() != minutes) pos.snapTo(minutes.toFloat()) }
    // One click and tick per minute passed, only while it's being turned.
    LaunchedEffect(Unit) {
        snapshotFlow { pos.value.roundToInt() }.distinctUntilChanged().drop(1).collect { m ->
            if (touching || spinning) {
                view.performHapticFeedback(if (Build.VERSION.SDK_INT >= 34) HapticFeedbackConstants.SEGMENT_FREQUENT_TICK else HapticFeedbackConstants.CLOCK_TICK)
                WheelClick.play(context)
                turn(m)
            }
        }
    }
    val measurer = rememberTextMeasurer()
    val ink = TodoText
    val muted = TodoMuted
    val shade = TodoBackground
    Box(
        modifier.fillMaxWidth().height(height).clip(RoundedCornerShape(18.dp)).background(veil(0.05f))
            .semantics { contentDescription = "Timer length, $minutes minutes. Drag sideways to change." }
            .pointerInput(Unit) {
                val radius = size.width / 2f / sin(Math.toRadians(80.0)).toFloat()
                val pxPerMinute = radius * (WHEEL_STEP_DEG * PI.toFloat() / 180f)
                val tracker = VelocityTracker()
                detectHorizontalDragGestures(
                    onDragStart = { touching = true; tracker.resetTracking(); scope.launch { pos.stop() } },
                    onDragEnd = {
                        touching = false
                        val v = tracker.calculateVelocity().x
                        val target = (pos.value - v / pxPerMinute * .22f).roundToInt().coerceIn(FOCUS_RANGE)
                        spinning = true
                        scope.launch {
                            pos.animateTo(target.toFloat(), spring(dampingRatio = .85f, stiffness = 90f))
                            spinning = false
                            settle(target)
                        }
                    },
                    onDragCancel = { touching = false; scope.launch { pos.animateTo(pos.value.roundToInt().toFloat()); settle(pos.value.roundToInt()) } }
                ) { change, dx ->
                    change.consume()
                    tracker.addPosition(change.uptimeMillis, change.position)
                    scope.launch { pos.snapTo((pos.value - dx / pxPerMinute).coerceIn(FOCUS_RANGE.first.toFloat() - .4f, FOCUS_RANGE.last.toFloat() + .4f)) }
                }
            }
    ) {
        Canvas(Modifier.matchParentSize()) {
            val v = pos.value
            val cx = size.width / 2f
            val radius = cx / sin(Math.toRadians(80.0)).toFloat()
            val stepRad = WHEEL_STEP_DEG * PI.toFloat() / 180f
            val first = (v - 16).toInt()
            val last = (v + 16).toInt() + 1
            for (m in first..last) {
                if (m !in FOCUS_RANGE) continue
                val theta = (m - v) * stepRad
                if (abs(theta) >= PI.toFloat() / 2f * .97f) continue
                val c = cos(theta)
                val x = cx + radius * sin(theta)
                val major = m % 5 == 0
                val h = (if (major) 20.dp.toPx() else 11.dp.toPx()) * (.55f + .45f * c)
                val w = (if (major) 2.4.dp.toPx() else 1.6.dp.toPx()) * (.4f + .6f * c)
                val alpha = (c * c) * if (major) .9f else .5f
                val top = 10.dp.toPx()
                drawRoundRect(ink.copy(alpha = alpha), Offset(x - w / 2, top), Size(w, h), CornerRadius(w / 2))
                if (major) {
                    val label = measurer.measure("$m", TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium, color = muted.copy(alpha = c * c)))
                    scale(c.coerceAtLeast(.2f), 1f, pivot = Offset(x, 0f)) {
                        drawText(label, topLeft = Offset(x - label.size.width / 2f, size.height - label.size.height - 6.dp.toPx()))
                    }
                }
            }
            // The edges turn away into shadow, like a drum.
            // (Two plain two-colour gradients: a multi-stop one crashes the emulator's renderer.)
            val edge = size.width * .22f
            drawRect(Brush.horizontalGradient(listOf(shade.copy(alpha = .55f), shade.copy(alpha = 0f)), startX = 0f, endX = edge), size = Size(edge, size.height))
            drawRect(Brush.horizontalGradient(listOf(shade.copy(alpha = 0f), shade.copy(alpha = .55f)), startX = size.width - edge, endX = size.width), topLeft = Offset(size.width - edge, 0f), size = Size(edge, size.height))
            // The pointer: a bar in the tracker's colour at the middle.
            val pw = 3.5.dp.toPx()
            drawRoundRect(color, Offset(cx - pw / 2, 6.dp.toPx()), Size(pw, size.height - 12.dp.toPx() - 14.dp.toPx()), CornerRadius(pw / 2))
        }
    }
}


/** A short, soft mechanical tick, made once in memory (no sound files) and replayed for each minute. */
internal object WheelClick {
    private var track: AudioTrack? = null
    private var last = 0L

    private fun build(): AudioTrack? = runCatching {
        val rate = 44_100
        val n = rate * 9 / 1000 // 9 ms
        val pcm = ShortArray(n)
        val rnd = java.util.Random(7)
        for (i in 0 until n) {
            val t = i / rate.toDouble()
            val env = kotlin.math.exp(-t * 900)
            // A woody click: a low knock, a brighter ring, and a breath of noise for the detent.
            val s = .55 * sin(2 * PI * 1900 * t) + .3 * sin(2 * PI * 3400 * t) + .25 * (rnd.nextDouble() * 2 - 1)
            pcm[i] = (s * env * .42 * Short.MAX_VALUE).toInt().toShort()
        }
        AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
            .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setTransferMode(AudioTrack.MODE_STATIC)
            .setBufferSizeInBytes(n * 2)
            .build().also { it.write(pcm, 0, n) }
    }.getOrNull()

    fun play(@Suppress("UNUSED_PARAMETER") context: Context) {
        val now = System.nanoTime() / 1_000_000
        if (now - last < 28) return // a fast spin blurs into a whirr, not a buzz
        last = now
        val t = track ?: build().also { track = it } ?: return
        runCatching { t.pause(); t.flush(); t.reloadStaticData(); t.play() }
    }
}

/**
 * The apps you can still open from full screen during deep focus: Phone, Messages and Camera, and
 * three of your own choosing. Deep focus's cover steps aside for all of them.
 */
internal object FocusDock {
    private const val PREFS = "daymark_focus"
    private const val KEY = "dock_apps"
    const val SLOTS = 3

    var custom by mutableStateOf(List(SLOTS) { "" })
        private set
    private var loaded = false

    fun load(context: Context) {
        if (loaded) return
        loaded = true
        custom = (context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)?.split('|') ?: emptyList()).let { saved -> List(SLOTS) { saved.getOrNull(it).orEmpty() } }
    }

    fun setSlot(context: Context, index: Int, packageName: String) {
        custom = custom.toMutableList().also { it[index] = packageName }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, custom.joinToString("|")).apply()
    }

    fun phone() = Intent(Intent.ACTION_DIAL).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    fun messages(): Intent = Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_MESSAGING).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    fun camera() = Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    fun launch(context: Context, packageName: String): Intent? = context.packageManager.getLaunchIntentForPackage(packageName)?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    private fun packageOf(context: Context, intent: Intent): String? =
        runCatching { context.packageManager.resolveActivity(intent, 0)?.activityInfo?.packageName?.takeIf { it != "android" } }.getOrNull()

    /** Every app deep focus lets through: the dock's, plus whatever a call can come in on. */
    fun allowedPackages(context: Context): Set<String> = buildSet {
        addAll(DeepFocus.callApps(context))
        listOf(phone(), messages(), camera()).forEach { intent -> packageOf(context, intent)?.let(::add) }
        addAll(custom.filter { it.isNotBlank() })
    }
}

@Composable
internal fun rememberAppIcon(packageName: String): ImageBitmap? {
    val context = LocalContext.current
    return remember(packageName) {
        if (packageName.isBlank()) null
        else runCatching { context.packageManager.getApplicationIcon(packageName).toBitmap(120, 120).asImageBitmap() }.getOrNull()
    }
}

/** One app in the dock: a glyph (for the fixed three) or the app's own icon, in a round button. */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
internal fun DockButton(icon: ZenIcon?, appIcon: ImageBitmap?, description: String, size: Dp, tint: Color, fill: Color, onClick: () -> Unit, onLongClick: (() -> Unit)? = null) {
    Box(
        Modifier.size(size).clip(CircleShape).background(fill)
            .then(if (icon == null && appIcon == null) Modifier.border(1.5.dp, TodoMuted.copy(alpha = .5f), CircleShape) else Modifier)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center
    ) {
        when {
            appIcon != null -> Image(appIcon, contentDescription = null, modifier = Modifier.size(size * .62f).clip(CircleShape))
            icon != null -> Glyph(icon, tint, size * .42f)
            else -> Glyph(ZenIcon.Plus, TodoMuted, size * .38f)
        }
    }
}
