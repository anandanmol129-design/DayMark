package com.anmol.daymark

import android.os.Build
import android.view.HapticFeedbackConstants
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.SheetValue
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
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
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sin

/** Radians the drum turns per step: about seven values show round the front of it. */
private const val GEAR_STEP_RAD = .42f

/**
 * A spinning gear standing on its edge: [count] values round a drum, the one at the front picked. Drag it up or
 * down, or flick it and let it spin out; it settles on a whole value with a click and a tick for each one it passes.
 * Teeth run down both sides and turn with it, and a pawl on each side marks the front. [looping] gears go round
 * forever (days, months, hours); others stop at their ends. Tapping above or below the front turns it one step.
 * [onChange] gets the new index once it comes to rest.
 */
@Composable
internal fun GearWheel(
    count: Int,
    index: Int,
    label: (Int) -> String,
    onChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
    looping: Boolean = true,
    accent: Color = CalendarBlue,
    enabled: Boolean = true,
    width: Dp = 76.dp,
    description: String = ""
) {
    val context = LocalContext.current
    val view = LocalView.current
    val pos = remember { Animatable(index.toFloat()) }
    var touching by remember { mutableStateOf(false) }
    var spinning by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val change by rememberUpdatedState(onChange)
    val labelOf by rememberUpdatedState(label)
    fun wrap(i: Int) = if (looping) Math.floorMod(i, count) else i.coerceIn(0, count - 1)
    fun bounded(p: Float) = if (looping) p else p.coerceIn(-.45f, count - 1 + .45f)

    // A new value from elsewhere (the other gears, a clamped day): turn the short way round to it.
    LaunchedEffect(index, count) {
        if (touching || spinning) return@LaunchedEffect
        val here = pos.value.roundToInt()
        if (wrap(here) == index) return@LaunchedEffect
        val target = if (looping) here + (Math.floorMod(index - wrap(here) + count / 2, count) - count / 2) else index
        spinning = true
        pos.animateTo(target.toFloat(), spring(dampingRatio = .8f, stiffness = 160f))
        spinning = false
    }
    LaunchedEffect(Unit) {
        snapshotFlow { pos.value.roundToInt() }.distinctUntilChanged().drop(1).collect {
            if (touching || spinning) {
                view.performHapticFeedback(if (Build.VERSION.SDK_INT >= 34) HapticFeedbackConstants.SEGMENT_FREQUENT_TICK else HapticFeedbackConstants.CLOCK_TICK)
                WheelClick.play(context)
            }
        }
    }
    fun settleAt(target: Int, velocity: Float = 0f) {
        val end = if (looping) target else target.coerceIn(0, count - 1)
        spinning = true
        scope.launch {
            pos.animateTo(end.toFloat(), spring(dampingRatio = .82f, stiffness = 70f), initialVelocity = velocity)
            spinning = false
            change(wrap(end))
        }
    }

    val measurer = rememberTextMeasurer()
    val ink = TodoText
    val muted = TodoMuted
    val shade = TodoBackground
    val body = veil(0.07f)
    val tooth = veil(0.22f)
    val itemHeight = 40.dp
    Box(
        modifier.width(width).height(itemHeight * 5.2f)
            .alpha(if (enabled) 1f else .4f)
            .semantics { contentDescription = "$description ${labelOf(wrap(pos.value.roundToInt()))}. Swipe up or down to change." }
            .pointerInput(enabled, count, looping) {
                if (!enabled) return@pointerInput
                val px = itemHeight.toPx()
                val tracker = VelocityTracker()
                detectVerticalDragGestures(
                    onDragStart = { touching = true; tracker.resetTracking(); scope.launch { pos.stop() } },
                    onDragEnd = {
                        touching = false
                        val v = -tracker.calculateVelocity().y / px // steps per second, positive turns forward
                        settleAt((pos.value + v * .28f).roundToInt(), v)
                    },
                    onDragCancel = { touching = false; settleAt(pos.value.roundToInt()) }
                ) { change, dy ->
                    change.consume()
                    tracker.addPosition(change.uptimeMillis, change.position)
                    scope.launch { pos.snapTo(bounded(pos.value - dy / px)) }
                }
            }
            .pointerInput(enabled, count, looping) {
                if (!enabled) return@pointerInput
                detectTapGestures { tap ->
                    val step = when {
                        tap.y < size.height / 2f - itemHeight.toPx() / 2 -> -1
                        tap.y > size.height / 2f + itemHeight.toPx() / 2 -> 1
                        else -> 0
                    }
                    if (step != 0 && !spinning) settleAt(pos.value.roundToInt() + step)
                }
            }
    ) {
        Canvas(Modifier.fillMaxWidth().height(itemHeight * 5.2f)) {
            val v = pos.value
            val cy = size.height / 2f
            val teeth = 7.dp.toPx()
            val radius = itemHeight.toPx() / GEAR_STEP_RAD
            val half = (Math.PI / 2).toFloat()
            // The drum itself, between the two rows of teeth.
            drawRoundRect(body, Offset(teeth, 0f), Size(size.width - teeth * 2, size.height), CornerRadius(16.dp.toPx()))
            // Teeth: two to a step, fixed to the drum, so they slide by as it turns and squash toward the top and bottom.
            val toothStep = GEAR_STEP_RAD / 2f
            val phase = v * GEAR_STEP_RAD
            var k = floor((phase - half) / toothStep).toInt()
            while (true) {
                val theta = k * toothStep - phase
                k++
                if (theta < -half) continue
                if (theta > half) break
                val c = cos(theta)
                val y = cy + radius * sin(theta)
                val h = 5.dp.toPx() * c
                if (h < .6f) continue
                val color = tooth.copy(alpha = tooth.alpha * (.3f + .7f * c))
                drawRoundRect(color, Offset(1.dp.toPx(), y - h / 2), Size(teeth - 1.dp.toPx(), h), CornerRadius(1.5.dp.toPx()))
                drawRoundRect(color, Offset(size.width - teeth, y - h / 2), Size(teeth - 1.dp.toPx(), h), CornerRadius(1.5.dp.toPx()))
            }
            // The values round the front of the drum.
            val first = floor(v - 4).toInt()
            for (i in first..first + 9) {
                if (!looping && i !in 0 until count) continue
                val theta = (i - v) * GEAR_STEP_RAD
                if (abs(theta) >= half * .96f) continue
                val c = cos(theta)
                val y = cy + radius * sin(theta)
                if (y < 0 || y > size.height) continue
                val near = (1f - abs(i - v)).coerceIn(0f, 1f)
                val color = androidx.compose.ui.graphics.lerp(muted, ink, near).copy(alpha = (c * c).coerceIn(0f, 1f))
                val text = measurer.measure(labelOf(wrap(i)), TextStyle(fontSize = 22.sp, fontWeight = if (near > .5f) FontWeight.Bold else FontWeight.Medium, color = color))
                scale(1f, c.coerceAtLeast(.05f), pivot = Offset(size.width / 2f, y)) {
                    drawText(text, topLeft = Offset((size.width - text.size.width) / 2f, y - text.size.height / 2f))
                }
            }
            // Shadow where the drum turns away (two plain two-colour gradients; multi-stop ones upset the emulator).
            val fade = size.height * .3f
            drawRect(Brush.verticalGradient(listOf(shade.copy(alpha = .85f), shade.copy(alpha = 0f)), startY = 0f, endY = fade), topLeft = Offset(teeth, 0f), size = Size(size.width - teeth * 2, fade))
            drawRect(Brush.verticalGradient(listOf(shade.copy(alpha = 0f), shade.copy(alpha = .85f)), startY = size.height - fade, endY = size.height), topLeft = Offset(teeth, size.height - fade), size = Size(size.width - teeth * 2, fade))
            // The window at the front, and a pawl on each side that the teeth click past.
            val band = itemHeight.toPx() * 1.05f
            drawRoundRect(accent.copy(alpha = .9f), Offset(teeth + 2.dp.toPx(), cy - band / 2), Size(size.width - teeth * 2 - 4.dp.toPx(), band), CornerRadius(12.dp.toPx()), style = Stroke(2.dp.toPx()))
            val pawl = 6.dp.toPx()
            drawPath(Path().apply { moveTo(0f, cy - pawl); lineTo(teeth + 1.dp.toPx(), cy); lineTo(0f, cy + pawl); close() }, accent)
            drawPath(Path().apply { moveTo(size.width, cy - pawl); lineTo(size.width - teeth - 1.dp.toPx(), cy); lineTo(size.width, cy + pawl); close() }, accent)
        }
    }
}

private fun two(n: Int) = n.toString().padStart(2, '0')

/** A header for a gear sheet: what it is on the left, then any [extra] buttons and Done. */
@Composable
private fun GearSheetHeader(title: String, subtitle: String, onDone: () -> Unit, extra: @Composable () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, color = TodoMuted, fontSize = 14.sp)
            Text(subtitle, color = TodoText, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, lineHeight = 21.sp)
        }
        extra()
        Spacer(Modifier.width(8.dp))
        Surface(color = TodoBlue, shape = RoundedCornerShape(14.dp), modifier = Modifier.clip(RoundedCornerShape(14.dp)).clickable(onClick = onDone)) {
            Text("Done", color = OnAccent, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 18.dp, vertical = 12.dp))
        }
    }
}

@Composable
private fun GearCaption(text: String, width: Dp) =
    Text(text, color = TodoMuted, fontSize = 12.sp, fontWeight = FontWeight.Medium, textAlign = androidx.compose.ui.text.style.TextAlign.Center, modifier = Modifier.width(width).padding(bottom = 6.dp))

/**
 * Jump to a date by spinning three gears: day, month, year. The calendar behind follows as soon as a gear comes to
 * rest ([onChange]); a day past the end of a shorter month rolls back to its last day.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun GearDateSheet(initial: LocalDate, onChange: (LocalDate) -> Unit, onDismiss: () -> Unit) {
    var day by remember { mutableIntStateOf(initial.dayOfMonth) }
    var month by remember { mutableIntStateOf(initial.monthValue) }
    var year by remember { mutableIntStateOf(initial.year.coerceIn(GEAR_YEARS)) }
    val length = YearMonth.of(year, month).lengthOfMonth()
    val date = LocalDate.of(year, month, day.coerceAtMost(length))
    val report by rememberUpdatedState(onChange)
    LaunchedEffect(day, month, year) {
        if (day > length) day = length
        report(LocalDate.of(year, month, day.coerceAtMost(length)))
    }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberBottomSheetState(SheetValue.Hidden, setOf(SheetValue.Hidden, SheetValue.Expanded)),
        containerColor = GlassSheet.copy(alpha = 1f),
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
    ) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            GearSheetHeader("Go to date", date.format(DateTimeFormatter.ofPattern("EEE, d MMM yyyy", Locale.getDefault())), onDismiss) {
                val today = LocalDate.now()
                SheetChip("Today", TodoText, ZenIcon.Calendar) { day = today.dayOfMonth; month = today.monthValue; year = today.year }
            }
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    GearCaption("DD", 84.dp)
                    GearWheel(31, day - 1, { two(it + 1) }, { day = it + 1 }, width = 84.dp, description = "Day")
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    GearCaption("MM", 84.dp)
                    GearWheel(12, month - 1, { two(it + 1) }, { month = it + 1 }, width = 84.dp, description = "Month")
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    GearCaption("YYYY", 108.dp)
                    GearWheel(GEAR_YEARS.last - GEAR_YEARS.first + 1, year - GEAR_YEARS.first, { (GEAR_YEARS.first + it).toString() }, { year = GEAR_YEARS.first + it }, looping = false, width = 108.dp, description = "Year")
                }
            }
        }
    }
}

private val GEAR_YEARS = 1970..2100

/** "2 hours", "1 hour 30 min", "45 min". */
internal fun formatSpan(minutes: Int): String {
    val h = minutes / 60
    val m = minutes % 60
    return when {
        h == 0 -> "$m min"
        m == 0 -> "$h hour${if (h == 1) "" else "s"}"
        else -> "$h hour${if (h == 1) "" else "s"} $m min"
    }
}

/**
 * An event's start and end on four gears: hour and minute for each. Turning the start carries the end with it
 * (the length stays); turning the end changes the length. All day puts the gears to rest.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun EventTimeSheet(start: Int?, duration: Int, onDone: (start: Int?, duration: Int) -> Unit, onDismiss: () -> Unit) {
    // Opening the gears means a time is wanted, so they start turned on even for an all-day item.
    var allDay by remember { mutableStateOf(false) }
    var from by remember { mutableIntStateOf(start ?: nextHalfHour()) }
    var length by remember { mutableIntStateOf(if (duration > 0) duration else 60) }
    val to = (from + length) % (24 * 60)
    val summary = if (allDay) "All day" else "${formatClock(from)} – ${formatClock(to)} · ${formatSpan(length)}"
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberBottomSheetState(SheetValue.Hidden, setOf(SheetValue.Hidden, SheetValue.Expanded)),
        containerColor = GlassSheet.copy(alpha = 1f),
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
    ) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            GearSheetHeader("Time", summary, { onDone(if (allDay) null else from, if (allDay) 0 else length) }) {
                SheetChip("All day", if (allDay) TodoBlue else TodoText, if (allDay) ZenIcon.Check else ZenIcon.Sun) { allDay = !allDay }
            }
            Spacer(Modifier.height(14.dp))
            val w = 66.dp
            fun setEnd(minute: Int) { length = Math.floorMod(minute - from, 24 * 60).let { if (it == 0) 24 * 60 else it }.coerceAtMost(24 * 60 - 1) }
            Row(horizontalArrangement = Arrangement.spacedBy(22.dp)) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Starts", color = TodoText, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(bottom = 4.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        GearWheel(24, from / 60, ::two, { from = it * 60 + from % 60 }, enabled = !allDay, width = w, description = "Start hour")
                        GearWheel(60, from % 60, ::two, { from = (from / 60) * 60 + it }, enabled = !allDay, width = w, description = "Start minute")
                    }
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Ends", color = TodoText, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(bottom = 4.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        GearWheel(24, to / 60, ::two, { setEnd(it * 60 + to % 60) }, enabled = !allDay, width = w, accent = Amber, description = "End hour")
                        GearWheel(60, to % 60, ::two, { setEnd((to / 60) * 60 + it) }, enabled = !allDay, width = w, accent = Amber, description = "End minute")
                    }
                }
            }
        }
    }
}

private fun nextHalfHour(): Int {
    val now = java.time.LocalTime.now().let { it.hour * 60 + it.minute }
    return ((now / 30 + 1) * 30).coerceAtMost(23 * 60 + 30)
}
