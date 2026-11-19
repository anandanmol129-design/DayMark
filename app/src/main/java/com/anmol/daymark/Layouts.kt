package com.anmol.daymark

import android.content.Context
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.PI
import kotlin.math.sin

/**
 * Each tab shows its things either as one long scrolling list with a header per section, or as
 * columns: one page per section to swipe between. The choice is remembered per tab.
 */
internal object Layouts {
    private const val PREFS = "daymark_settings"
    private const val PREFIX = "layout_columns_"

    const val TODAY = "today"
    const val TASKS = "todos"
    const val CALENDAR = "calendar"
    const val NOTES = "notes"

    /** What each tab showed before the switch existed, so nothing moves until it is used. */
    private val defaults = mapOf(TODAY to false, TASKS to true, CALENDAR to true, NOTES to true)
    private val chosen = mutableStateMapOf<String, Boolean>()

    fun load(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        defaults.keys.forEach { key -> if (prefs.contains(PREFIX + key)) chosen[key] = prefs.getBoolean(PREFIX + key, defaults.getValue(key)) }
    }

    /** True when [tab] shows one page per section. */
    fun columns(tab: String): Boolean = chosen[tab] ?: defaults[tab] ?: false

    fun set(context: Context, tab: String, columns: Boolean) {
        chosen[tab] = columns
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(PREFIX + tab, columns).apply()
    }
}

/** Each half of the List / Columns icon's move (turn, then split or pop); about a second in all. */
private const val TurnMs = 450
private const val ShapeMs = 550

/** Eases out past the end and settles back, so each move overshoots a little and rebounds. */
private val OvershootEasing = androidx.compose.animation.core.CubicBezierEasing(.34f, 1.7f, .64f, 1f)

/**
 * The List / Columns switch that sits left of Settings. In list mode it is an upright page with
 * three lines of text.
 * - To columns: the page turns a quarter clockwise, then splits along its lines into three
 *   separate rectangles.
 * - Back to list: the rectangles turn upright first, then they dissolve and a fresh page with its
 *   lines pops out from the middle.
 * Each move overshoots and rebounds.
 */
@Composable
internal fun LayoutToggle(tab: String) {
    val context = LocalContext.current
    val columns = Layouts.columns(tab)
    val turn = remember { androidx.compose.animation.core.Animatable(if (columns) 1f else 0f) }
    // Going to columns: 0 = page with lines, 1 = three rectangles.
    val split = remember { androidx.compose.animation.core.Animatable(if (columns) 1f else 0f) }
    // Coming back: 0 = only the rectangles, 1 = only the new page (it pops in as the rectangles dissolve).
    val pop = remember { androidx.compose.animation.core.Animatable(1f) }
    var returning by remember { androidx.compose.runtime.mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(columns) {
        if (columns) {
            if (turn.value == 1f && split.value == 1f) return@LaunchedEffect
            returning = false
            pop.snapTo(1f)
            turn.animateTo(1f, tween(TurnMs, easing = OvershootEasing))
            split.animateTo(1f, tween(ShapeMs, easing = OvershootEasing))
        } else {
            if (turn.value == 0f && split.value == 0f) return@LaunchedEffect
            returning = true
            pop.snapTo(0f)
            split.snapTo(1f)
            turn.animateTo(0f, tween(TurnMs, easing = OvershootEasing))
            pop.animateTo(1f, tween(ShapeMs, easing = OvershootEasing))
            split.snapTo(0f)
            returning = false
        }
    }
    val tint = TodoText
    Box(
        Modifier.size(42.dp).clip(CircleShape)
            .clickable { Layouts.set(context, tab, !columns) }
            .semantics { contentDescription = if (columns) "Show as one list" else "Show as columns" },
        contentAlignment = Alignment.Center
    ) {
        Canvas(Modifier.size(24.dp)) {
            val c = center
            val stroke = 1.7.dp.toPx()
            val w = 14.dp.toPx()
            val h = 18.dp.toPx()
            /** The page's frame and its three lines, mid-way to rectangles by [m] (0 lines, 1 rectangles), at [alpha] and [scale]. */
            fun page(m: Float, alpha: Float, scale: Float, frame: Boolean) {
                if (alpha <= 0f || scale <= 0f) return
                val mc = m.coerceIn(0f, 1f)
                fun lerp(a: Float, b: Float) = a + (b - a) * m
                if (frame && mc < 1f) drawRoundRect(
                    tint.copy(alpha = alpha * (1f - mc)), Offset(c.x - w * scale / 2, c.y - h * scale / 2), Size(w * scale, h * scale),
                    CornerRadius(2.8.dp.toPx() * scale), style = Stroke(stroke)
                )
                val length = lerp(7.5f, 17f).dp.toPx() * scale
                val thick = lerp(1.8f, 5.0f).dp.toPx() * scale
                val spacing = lerp(4.2f, 6.6f).dp.toPx() * scale
                for (i in -1..1) {
                    val y = c.y + i * spacing
                    val topLeft = Offset(c.x - length / 2, y - thick / 2)
                    val size = Size(length, thick)
                    if (mc < 1f) drawRoundRect(tint.copy(alpha = alpha * .6f * (1f - mc)), topLeft, size, CornerRadius(thick / 2))
                    if (mc > 0f) drawRoundRect(tint.copy(alpha = alpha * mc), topLeft, size, CornerRadius(1.6.dp.toPx() * scale), style = Stroke(stroke * mc.coerceAtLeast(.4f)))
                }
            }
            rotate(90f * turn.value, c) {
                if (returning) {
                    // The upright rectangles fade away while the new page grows out from the middle.
                    val p = pop.value
                    page(1f, (1f - p).coerceIn(0f, 1f), 1f, frame = false)
                    page(0f, p.coerceIn(0f, 1f), p, frame = true)
                } else page(split.value, 1f, 1f, frame = true)
            }
        }
    }
}

/** A section's quiet label, sitting above its card: the name in small caps and an optional count. */
@Composable
internal fun SectionLabel(title: String, count: String? = null, countColor: Color = TodoMuted, modifier: Modifier = Modifier) {
    Row(modifier.padding(start = 8.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title.uppercase(), color = TodoMuted, fontSize = 11.sp, fontWeight = FontWeight.Medium, letterSpacing = 1.6.sp)
        if (count != null) Text(count, color = countColor, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 8.dp))
    }
}

/** The rounded glass card that holds a section's rows, used across the app. It eases to its new height as rows come and go. */
@Composable
internal fun SectionCard(modifier: Modifier = Modifier, color: Color = TodoCard, content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(22.dp)
    Surface(color = color, shape = shape, border = GlassStroke, modifier = modifier.fillMaxWidth().glassBlur(shape)) {
        Column(Modifier.animateContentSize(spring(stiffness = 500f)), content = content)
    }
}

/** A labelled section: its label, then its rows in a [SectionCard]. */
@Composable
internal fun Section(title: String, count: String?, countColor: Color = TodoMuted, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.rise().padding(horizontal = 14.dp).padding(bottom = 18.dp)) {
        SectionLabel(title, count, countColor)
        SectionCard(content = content)
    }
}

/** The hairline between two rows of a card. */
@Composable
internal fun RowDivider() = HorizontalDivider(Modifier.padding(start = 14.dp), color = veil(0.10f))

/** A gently moving squiggle, like the progress line of Android's media player; it separates open from completed. */
@Composable
internal fun WavyDivider(modifier: Modifier = Modifier, color: Color = veil(0.28f), amplitude: Dp = 2.6.dp, wavelength: Dp = 20.dp) {
    val phase by rememberInfiniteTransition(label = "wave").animateFloat(
        0f, 1f, infiniteRepeatable(tween(2600, easing = LinearEasing), RepeatMode.Restart), label = "wavePhase"
    )
    val path = remember { Path() }
    Canvas(modifier.fillMaxWidth().height(14.dp)) {
        val a = amplitude.toPx()
        val l = wavelength.toPx()
        val mid = size.height / 2
        path.reset()
        var x = 0f
        val step = 2f
        path.moveTo(0f, mid + a * sin(2 * PI * (-phase)).toFloat())
        while (x <= size.width) {
            x += step
            path.lineTo(x, mid + a * sin(2 * PI * (x / l - phase)).toFloat())
        }
        drawPath(path, color, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round))
    }
}

/** A plain chip for picking a section in column mode. */
@Composable
internal fun SectionChip(label: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val fill by animateColorAsState(if (selected) (if (Look.light) CalendarBlue else veil(0.20f)) else Color.Transparent, tween(220), label = "sectionChipFill")
    val text by animateColorAsState(if (selected) (if (Look.light) OnFill else TodoText) else TodoMuted, tween(220), label = "sectionChipText")
    Surface(
        color = fill, shape = RoundedCornerShape(22.dp),
        border = BorderStroke(1.dp, if (selected) Color.Transparent else veil(0.20f)),
        modifier = modifier.clip(RoundedCornerShape(22.dp)).clickable(onClick = onClick)
    ) {
        Text(label, color = text, fontSize = 15.sp, maxLines = 1, modifier = Modifier.padding(horizontal = 18.dp, vertical = 9.dp))
    }
}
