package com.anmol.daymark

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt

/*
 * The bottom bar's open/close transitions. Every tab shares one clock and one size change:
 *  - one second in all: [NavPush] speeding up, [NavSpinning] at full speed, [NavStopping] easing to rest;
 *  - closed, an icon is the size of the 24dp line icons; open, it's 1.5 times that. Opening grows it
 *    over the push with a slight overshoot; closing shrinks it smoothly over the last half second.
 * Each icon is drawn on the same 24-unit grid as the line icons, with the stroke thickening a touch
 * as it grows.
 */

internal const val NavPush = .3f
internal const val NavSpinning = .5f
internal const val NavStopping = .2f
internal const val NavTotal = NavPush + NavSpinning + NavStopping

// Overshoots a little past the end, then settles.
private val NavOvershoot = CubicBezierEasing(.34f, 1.56f, .64f, 1f)
private val NavEase = androidx.compose.animation.core.FastOutSlowInEasing

private fun window(x: Float, from: Float, to: Float) = ((x - from) / (to - from)).coerceIn(0f, 1f)
private fun smooth(x: Float) = x * x * (3 - 2 * x)

/**
 * How far through the shared motion the transition is, 0 to 1, [t] seconds in: it speeds up over
 * the push, holds full speed, then eases to a stop (velocity falling off smoothly).
 */
internal fun navMotion(t: Float): Float {
    val top = 1f / (NavPush / 2 + NavSpinning + NavStopping / 3) // progress per second at full speed
    return when {
        t <= 0f -> 0f
        t <= NavPush -> top * t * t / (2 * NavPush)
        t <= NavPush + NavSpinning -> top * (NavPush / 2 + t - NavPush)
        else -> {
            val u = ((t - NavPush - NavSpinning) / NavStopping).coerceIn(0f, 1f)
            top * (NavPush / 2 + NavSpinning) + top * NavStopping / 3 * (1 - (1 - u) * (1 - u) * (1 - u))
        }
    }
}

/**
 * How grown the icon is, 0 (closed size) to 1 (open size), [t] seconds into opening. Opening
 * pushes out over [NavPush] with a slight overshoot; closing (time running backwards) shrinks on
 * its own curve over the last ~0.5s that eases to rest, since the overshoot played backwards snaps.
 */
internal fun navGrow(t: Float, closing: Boolean): Float =
    if (closing) smooth(window(t, 0f, NavPush * 1.6f)) else NavOvershoot.transform(window(t, 0f, NavPush))

/**
 * A bottom-bar icon with its own open/close transition, or null if [icon] has none (the Private
 * lock), in which case the caller draws the plain glyph.
 */
@Composable
internal fun NavTransitionIcon(icon: ZenIcon, open: Boolean, tint: Color, modifier: Modifier = Modifier): Boolean {
    when (icon) {
        ZenIcon.Sun -> NavCanvas(open, modifier) { t, closing -> drawSun(t, closing, tint) }
        ZenIcon.Inbox -> NavCanvas(open, modifier) { t, closing -> drawInbox(t, closing, tint) }
        ZenIcon.Note -> NavCanvas(open, modifier) { t, closing -> drawNote(t, closing, tint) }
        ZenIcon.Habit -> NavCanvas(open, modifier) { t, closing -> drawHabits(t, closing, tint) }
        ZenIcon.Calendar -> {
            val measurer = rememberTextMeasurer()
            // Today's date, read afresh each time the tab opens or closes.
            val day = remember(open) { java.time.LocalDate.now().dayOfMonth.toString() }
            val density = androidx.compose.ui.platform.LocalDensity.current
            val date = remember(day, density) { measurer.measure(day, TextStyle(fontSize = with(density) { DateTextPx.toSp() }, fontWeight = FontWeight.SemiBold)) }
            NavCanvas(open, modifier) { t, closing -> drawCalendar(t, closing, tint, date) }
        }
        else -> return false
    }
    return true
}

/**
 * Runs [open]'s transition on the shared one-second clock and hands [draw] the time in seconds
 * into opening. Time runs linearly in both directions (closing counts back down), so icons that
 * close by simply reversing retrace their opening exactly; [draw] applies its own easing.
 */
@Composable
private fun NavCanvas(open: Boolean, modifier: Modifier, draw: DrawScope.(t: Float, closing: Boolean) -> Unit) {
    val progress = remember { Animatable(if (open) 1f else 0f) }
    LaunchedEffect(open) {
        val target = if (open) 1f else 0f
        progress.animateTo(target, tween((NavTotal * 1000 * kotlin.math.abs(target - progress.value)).toInt(), easing = LinearEasing))
    }
    // Room for the fully grown icon, 1.5 times the 24dp line icons.
    Canvas(modifier.size(38.dp)) { draw(progress.value * NavTotal, !open) }
}

/**
 * Draws [block] on the 24-unit icon grid, scaled from the 24dp icon size up to 1.5 times it as
 * [grow] goes from 0 to 1, with the stroke (in grid units) thickening a little as it grows.
 */
private inline fun DrawScope.inGrid(grow: Float, block: DrawScope.(stroke: Stroke) -> Unit) {
    val unit = (1f + .5f * grow).dp.toPx()
    val width = (1.7f + .4f * grow).dp.toPx() / unit
    val c = center
    withTransform({ translate(c.x - 12f * unit, c.y - 12f * unit); scale(unit, unit, pivot = Offset.Zero) }) {
        block(Stroke(width, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

private fun DrawScope.line(tint: Color, stroke: Stroke, x1: Float, y1: Float, x2: Float, y2: Float, alpha: Float = 1f) {
    if (alpha <= 0f) return
    drawLine(tint, Offset(x1, y1), Offset(x2, y2), stroke.width, StrokeCap.Round, alpha = alpha.coerceAtMost(1f))
}

private fun polyline(points: List<Offset>, close: Boolean = false) = Path().apply {
    points.forEachIndexed { i, p -> if (i == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y) }
    if (close) close()
}

/** Adds a quadratic curve from [from] through control [ctrl] to [to], sampled as points. */
private fun MutableList<Offset>.quad(from: Offset, ctrl: Offset, to: Offset, steps: Int = 6) {
    for (k in 1..steps) {
        val s = k / steps.toFloat()
        val a = 1 - s
        add(Offset(a * a * from.x + 2 * a * s * ctrl.x + s * s * to.x, a * a * from.y + 2 * a * s * ctrl.y + s * s * to.y))
    }
}

// ---------------------------------------------------------------------------------------------
// Today: the sun

private const val SunTurns = 720f // whole multiples of 45° so the rays end where they started

/**
 * Today's sun. Closed, it's a small disc ringed by eight short curved arcs. Opening grows the disc
 * to just past where the arcs were, pushing them outward, while they spin out one by one into
 * straight rays and the sun picks up speed, spins, then comes to rest. Closing plays it in reverse,
 * the rays curling back as they spin and squeezing the disc small again.
 */
private fun DrawScope.drawSun(t: Float, closing: Boolean, tint: Color) {
    val dp = 1.dp.toPx()
    val c = center
    val grow = navGrow(t, closing)
    val disc = 2.6f + (7.4f - 2.6f) * grow // ends just outside the closed ring of arcs (6.8dp)
    val stroke = Stroke((1.7f + .4f * grow) * dp, cap = StrokeCap.Round, join = StrokeJoin.Round)
    drawCircle(tint, disc * dp, c, style = stroke)
    val spin = SunTurns * navMotion(t)
    val rays = 8
    val stagger = .06f
    val each = NavTotal - stagger * (rays - 1) // the last one straightens as the spin stops
    val arcSpan = Math.toRadians(30.0).toFloat()
    val steps = 10
    for (i in 0 until rays) {
        val q = NavEase.transform(((t - i * stagger) / each).coerceIn(0f, 1f))
        val base = Math.toRadians((spin + i * 45f - 90f).toDouble()).toFloat() // the first ray at the top, then clockwise
        val path = Path()
        for (k in 0..steps) {
            val s = k / steps.toFloat()
            // Blend each point from the curved arc to the straight ray in polar terms, so the arc swings outward as it straightens.
            val arc = disc + 4.2f
            val ray = disc + 2.6f + (17f - disc - 2.6f) * s
            val r = arc + (ray - arc) * q
            val a = base + (s - .5f) * arcSpan * (1f - q)
            val x = c.x + r * dp * cos(a)
            val y = c.y + r * dp * sin(a)
            if (k == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, tint, style = stroke)
    }
}

// ---------------------------------------------------------------------------------------------
// Inbox: pages drop in and weigh the tray down

private val InboxTray = listOf(
    "M22 12h-6l-2 3h-4l-2-3H2",
    "M5.45 5.11L2 12v6a2 2 0 0 0 2 2h16a2 2 0 0 0 2-2v-6l-3.45-6.89A2 2 0 0 0 16.76 4H7.24a2 2 0 0 0-1.79 1.11z"
).map { PathParser().parsePathString(it).toPath() }

// The pages that sit in the open tray, bottom one first: their resting height and half-width.
private val PageRest = floatArrayOf(9.6f, 6.8f)
private val PageHalf = floatArrayOf(3.8f, 3.2f)

/**
 * Inbox. Opening drops two pages in from above one after another, each landing with a thud
 * that dips the tray before it springs back; open, the pages sit in the tray. Closing lets the
 * pages fall out through the bottom while the tray, lightened, lifts up and settles back.
 */
private fun DrawScope.drawInbox(t: Float, closing: Boolean, tint: Color) {
    val grow = navGrow(t, closing)
    val pages = PageRest.size
    val y = FloatArray(pages)
    val alpha = FloatArray(pages)
    val inTray = BooleanArray(pages)
    var tray = 0f // how far the tray is pushed down, in grid units
    if (!closing) {
        for (i in 0 until pages) {
            val start = .06f + .18f * i
            val land = start + .26f
            val u = window(t, start, land)
            y[i] = -5f + (PageRest[i] + 5f) * u * u // falling under gravity
            alpha[i] = if (t <= start) 0f else (u * 3f).coerceAtMost(1f)
            if (t >= land) {
                inTray[i] = true
                val since = t - land
                tray += 1.4f * exp(-since / .09f) * sin(since * PI.toFloat() / .11f) // the landing's dip and rebound
            }
        }
    } else {
        val c = NavTotal - t // seconds into closing
        tray = -1.3f * sin(PI.toFloat() * smooth(window(c, .06f, .62f))) // lifts as the weight leaves
        for (i in 0 until pages) {
            val start = .08f * i
            val u = window(c, start, start + .3f)
            y[i] = PageRest[i] + 9f * u * u
            alpha[i] = 1f - window(y[i], PageRest[i] + 2f, PageRest[i] + 8f) // gone as it drops through
            inTray[i] = u == 0f
        }
    }
    inGrid(grow) { stroke ->
        withTransform({ translate(0f, tray) }) { InboxTray.forEach { drawPath(it, tint, style = stroke) } }
        for (i in 0 until pages) {
            val py = y[i] + if (inTray[i]) tray else 0f
            line(tint, stroke, 12f - PageHalf[i], py, 12f + PageHalf[i], py, alpha[i])
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Calendar: a desk calendar turns to face you as its page flips over

private const val DateTextPx = 40f // measured large, drawn scaled down so it stays crisp
private const val CalendarYaw = 38f // degrees turned away when closed
private const val CalHalfWidth = 9f
private const val CalHalfHeight = 7f
private const val CalDepth = 5f // how far the tent's feet stand out from the ridge

/**
 * Calendar. Closed, it's a tent-shaped desk calendar seen from the side, rings on its ridge and a
 * page on its face. Opening turns it to face you on the shared motion while the page flips up and
 * over the rings to the back, revealing today's date; the rings, seen edge-on, become the two
 * ticks above it. Closing turns it back (the motion reversed, so it ends on its gentle start).
 */
private fun DrawScope.drawCalendar(t: Float, closing: Boolean, tint: Color, date: TextLayoutResult) {
    val grow = navGrow(t, closing)
    val m = navMotion(t)
    val yaw = Math.toRadians((CalendarYaw * (1f - m)).toDouble()).toFloat()
    val cy = cos(yaw)
    val sy = sin(yaw)
    val depthCue = 1f - m // perspective fades out as it comes to face you, leaving a flat icon
    // 3D (x right, y down, z toward the viewer) to the grid, turned by yaw with a little perspective.
    fun project(x: Float, y: Float, z: Float): Offset {
        val px = x * cy + z * sy
        val pz = -x * sy + z * cy
        val k = 1f + (45f / (45f + pz) - 1f) * depthCue
        return Offset(px * k, y * k)
    }
    val h = CalHalfHeight
    val w = CalHalfWidth
    val d = CalDepth
    val ringR = 2.2f
    // Centre the drawing on its own bounds (it shifts a little as perspective comes and goes).
    val bounds = listOf(project(-w, -h, 0f), project(w, -h, 0f), project(w, h, d), project(-w, h, d), project(-w, h, -d), project(-4.5f, -h - ringR, 0f), project(4.5f, -h - ringR, 0f))
    val shift = Offset(12f - (bounds.minOf { it.x } + bounds.maxOf { it.x }) / 2, 12f - (bounds.minOf { it.y } + bounds.maxOf { it.y }) / 2)
    fun p(x: Float, y: Float, z: Float) = project(x, y, z) + shift

    // The page flips over the ridge from the front face to the back one, fading once it's behind.
    val face = Math.atan2(d.toDouble(), (2 * h).toDouble()).toFloat()
    val flip = smooth(window(m, .12f, .78f))
    val psi = face + flip * (2 * PI.toFloat() - 2 * face)
    val panel = sqrt(4 * h * h + d * d)
    fun sheet(x: Float, along: Float) = p(x, -h + along * cos(psi), along * sin(psi))
    val sheetAlpha = 1f - window(Math.toDegrees(psi.toDouble()).toFloat(), 200f, 290f)

    inGrid(grow) { stroke ->
        // The face, and the far leg of the tent's side.
        drawPath(polyline(listOf(p(-w, -h, 0f), p(w, -h, 0f), p(w, h, d), p(-w, h, d)), close = true), tint, style = stroke)
        drawPath(polyline(listOf(p(-w, -h, 0f), p(-w, h, -d))), tint, style = stroke, alpha = (1f - window(m, .7f, 1f)))
        // Rings through the ridge: half loops from the side, ticks when seen edge-on.
        for (rx in floatArrayOf(-4.5f, 4.5f)) {
            val pts = (0..12).map { k ->
                val a = -.35f + (PI.toFloat() + .7f) * k / 12f
                p(rx, -h - ringR * sin(a), ringR * cos(a))
            }
            drawPath(polyline(pts), tint, style = stroke)
        }
        if (sheetAlpha > 0f) {
            val outline = listOf(sheet(-w + 3f, panel * .24f), sheet(w - 3f, panel * .24f), sheet(w - 3f, panel * .8f), sheet(-w + 3f, panel * .8f))
            drawPath(polyline(outline, close = true), tint, style = stroke, alpha = sheetAlpha)
        }
        val dateAlpha = window(m, .72f, 1f)
        if (dateAlpha > 0f) {
            val at = p(0f, 1.2f, d * (8.2f / 14f))
            val k = 10f / DateTextPx // about 10 grid units tall
            withTransform({ scale(k, k, pivot = at) }) {
                drawText(date, tint, Offset(at.x - date.size.width / 2f, at.y - date.size.height / 2f), alpha = dateAlpha)
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Notes: the page flips over its diagonal and fills

/**
 * Notes. Closed, a page with its top-right corner folded down, a short line, a wavy line and a
 * longer line. Opening flips the page over its diagonal (edge-on halfway, so it's briefly just a
 * slash) as the corner unfolds and the page squares up; once it lands, three lines write
 * themselves in. Closing doesn't flip back: the top line shrinks and the corner folds down again.
 */
private fun DrawScope.drawNote(t: Float, closing: Boolean, tint: Color) {
    val grow = navGrow(t, closing)
    var flip = 0f // 0 to 1 is 0° to 180° about the diagonal
    val fold: Float // how far the top-right corner is folded, 0 to 1
    val square: Float // the page's shape: 0 is the tall page, 1 square
    var oldLines = 1f // the closed icon's lines, carried on the page as it flips
    var writeIn = -1f // after the flip, how far the new lines have written in (negative: not drawn)
    var topLine = 1f // the top line's length: 0 short, 1 long
    if (!closing) {
        val m = navMotion(t)
        flip = window(m, 0f, .7f)
        fold = 1f - window(m, 0f, .45f)
        square = window(m, .1f, .6f)
        oldLines = 1f - window(m, 0f, .12f)
        topLine = 0f
        if (m >= .7f) writeIn = window(m, .7f, 1f)
    } else {
        val c = NavTotal - t
        topLine = 1f - smooth(window(c, .05f, .55f))
        fold = smooth(window(c, .25f, .85f))
        square = 1f - smooth(window(c, .1f, .7f))
    }
    val left = 4f - square
    val right = 20f + square
    val top = 2f + square
    val bottom = 22f - square
    val r = 2f
    val f = 5f * fold

    // The outline, with the top-right corner cut off by the fold (rounding back as it unfolds).
    val outline = mutableListOf(Offset(left + r, top))
    val cut = maxOf(f, r)
    outline += Offset(right - cut, top)
    val sharp = window(f, r, r + 1f)
    val ctrl = Offset(right + (right - cut / 2 - right) * sharp, top + (top + cut / 2 - top) * sharp)
    outline.quad(Offset(right - cut, top), ctrl, Offset(right, top + cut))
    outline += Offset(right, bottom - r)
    outline.quad(Offset(right, bottom - r), Offset(right, bottom), Offset(right - r, bottom))
    outline += Offset(left + r, bottom)
    outline.quad(Offset(left + r, bottom), Offset(left, bottom), Offset(left, bottom - r))
    outline += Offset(left, top + r)
    outline.quad(Offset(left, top + r), Offset(left, top), Offset(left + r, top))
    // The folded-down flap.
    val flap = mutableListOf<Offset>()
    if (f > .2f) {
        val bend = minOf(2f, f / 2)
        flap += Offset(right - f, top)
        flap += Offset(right - f, top + f - bend)
        flap.quad(Offset(right - f, top + f - bend), Offset(right - f, top + f), Offset(right - f + bend, top + f))
        flap += Offset(right, top + f)
    }

    // Turning about the top-left to bottom-right diagonal: the part across the diagonal shrinks by cos.
    val turn = cos(PI.toFloat() * flip)
    fun turned(o: Offset): Offset {
        val rx = o.x - 12f
        val ry = o.y - 12f
        val along = (rx + ry) / 2 // projection onto (1,1)/√2, times 1/√2
        return Offset(12f + along + (rx - along) * turn, 12f + along + (ry - along) * turn)
    }

    inGrid(grow) { stroke ->
        drawPath(polyline(outline.map(::turned), close = true), tint, style = stroke)
        if (flap.isNotEmpty()) drawPath(polyline(flap.map(::turned)), tint, style = stroke, alpha = window(f, .2f, 1.5f))
        fun lines(alpha: Float, long: Float, reveal: (Int) -> Float, map: (Offset) -> Offset) {
            val topEnd = 10f + 6f * long
            val shapes = listOf(
                listOf(Offset(8f, 8.5f), Offset(topEnd, 8.5f)),
                (0..16).map { k -> val x = 8f + 8f * k / 16f; Offset(x, 12.8f + 1.1f * sin((x - 8f) / 8f * 3f * PI.toFloat())) },
                listOf(Offset(8f, 17f), Offset(16f, 17f))
            )
            shapes.forEachIndexed { i, pts ->
                val shown = reveal(i)
                if (shown <= 0f) return@forEachIndexed
                // Write the line in from the left up to [shown] of its length.
                val drawn = if (shown >= 1f) pts else {
                    val n = (pts.size - 1) * shown
                    val whole = n.toInt()
                    pts.take(whole + 1) + listOfNotNull(if (whole < pts.size - 1) { val a = pts[whole]; val b = pts[whole + 1]; val s = n - whole; Offset(a.x + (b.x - a.x) * s, a.y + (b.y - a.y) * s) } else null)
                }
                drawPath(polyline(drawn.map(map)), tint, style = stroke, alpha = alpha)
            }
        }
        if (writeIn >= 0f) {
            lines(1f, 1f, { i -> window(writeIn, i * .25f, i * .25f + .5f) }) { it }
        } else if (oldLines > 0f) {
            lines(oldLines, topLine, { 1f }, ::turned)
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Habits: boxes get crossed off; closing sinks them and floats up empty ones

// Top-left corners of the four cells (6 units square); the last one holds the check when closed.
private val HabitCells = listOf(Offset(4f, 4f), Offset(14f, 4f), Offset(4f, 14f), Offset(14f, 14f))

private fun DrawScope.habitBox(cell: Offset, scale: Float, tint: Color, stroke: Stroke, alpha: Float = 1f) {
    val c = cell + Offset(3f, 3f)
    val half = 3f * scale
    drawRoundRect(tint, Offset(c.x - half, c.y - half), androidx.compose.ui.geometry.Size(2 * half, 2 * half), androidx.compose.ui.geometry.CornerRadius(scale), style = stroke, alpha = alpha)
}

private fun DrawScope.habitCross(cell: Offset, scale: Float, drawn: Float, tint: Color, stroke: Stroke) {
    if (drawn <= 0f) return
    val c = cell + Offset(3f, 3f)
    val k = 3f * scale // corner to corner, so it reads at this size
    val first = window(drawn, 0f, .5f)
    val second = window(drawn, .5f, 1f)
    val pen = Stroke(stroke.width * .65f) // finer than the box, or the cross fills such a small box
    line(tint, pen, c.x - k, c.y - k, c.x - k + 2 * k * first, c.y - k + 2 * k * first)
    if (second > 0f) line(tint, pen, c.x + k, c.y - k, c.x + k - 2 * k * second, c.y - k + 2 * k * second)
}

private fun DrawScope.habitCheck(tint: Color, stroke: Stroke, alpha: Float = 1f, scale: Float = 1f) {
    if (alpha <= 0f) return
    val c = Offset(17f, 17f)
    val pts = listOf(Offset(14.5f, 17.5f), Offset(16.5f, 19.5f), Offset(20f, 15.5f)).map { c + (it - c) * scale }
    drawPath(polyline(pts), tint, style = stroke, alpha = alpha)
}

/**
 * Habits. Closed, three empty boxes and a check. Opening ticks the boxes off one by one with a
 * cross, each shrinking slightly as it's ticked; the check becomes a fourth box and is crossed
 * last. Closing sinks the crossed boxes one by one below a waterline at their base while empty
 * boxes (and the check) bob up out of it.
 */
private fun DrawScope.drawHabits(t: Float, closing: Boolean, tint: Color) {
    val grow = navGrow(t, closing)
    inGrid(grow) { stroke ->
        if (!closing) {
            val m = navMotion(t)
            HabitCells.forEachIndexed { i, cell ->
                val start = .08f + .2f * i
                val tick = window(m, start, start + .3f)
                val scale = 1f - .1f * NavEase.transform(tick)
                if (i == 3) {
                    // The check gives way to a box before it's crossed like the others.
                    val swap = window(m, .42f, .66f)
                    habitCheck(tint, stroke, alpha = 1f - swap, scale = 1f - .4f * swap)
                    if (swap > 0f) habitBox(cell, scale * (.6f + .4f * NavEase.transform(swap)), tint, stroke, alpha = swap)
                } else habitBox(cell, scale, tint, stroke)
                habitCross(cell, scale, tick, tint, stroke)
            }
        } else {
            val c = NavTotal - t
            HabitCells.forEachIndexed { i, cell ->
                val start = .05f + .13f * i
                val sink = window(c, start, start + .25f)
                val rise = window(c, start + .22f, start + .5f)
                val waterline = cell.y + 7f
                clipRect(-100f, -100f, 100f, waterline) {
                    if (sink < 1f) {
                        val dy = 8f * sink * sink // slipping under, speeding up
                        withTransform({ translate(0f, dy) }) {
                            habitBox(cell, .9f, tint, stroke)
                            habitCross(cell, .9f, 1f, tint, stroke)
                        }
                    }
                    if (rise > 0f) {
                        val dy = 8f * (1f - NavEase.transform(rise)) // bobbing up and settling
                        withTransform({ translate(0f, dy) }) {
                            if (i == 3) habitCheck(tint, stroke) else habitBox(cell, 1f, tint, stroke)
                        }
                    }
                }
            }
        }
    }
}
