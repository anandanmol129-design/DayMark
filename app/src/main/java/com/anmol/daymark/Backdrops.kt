package com.anmol.daymark

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.random.Random

/** The animated scenes painted behind the glass panels. */
enum class Backdrop(val id: String, val label: String) {
    ZEN("zen", "Zen"),
    AURORA("aurora", "Aurora"),
    LAVA("lava", "Lava lamp"),
    OCEAN("ocean", "Ocean"),
    STARS("stars", "Starfield"),
    SUNSET("sunset", "Sunset"),
    STILL("still", "Still");

    companion object {
        private const val PREFS = "daymark_settings"
        fun fromId(id: String?) = entries.firstOrNull { it.id == id } ?: ZEN
        fun load(context: Context): Backdrop {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            // The calm redesign starts everyone on Zen once; any later choice in Settings sticks.
            if (!prefs.getBoolean("zen_default_applied", false)) prefs.edit().putBoolean("zen_default_applied", true).putString("backdrop", ZEN.id).apply()
            return fromId(prefs.getString("backdrop", null))
        }
        fun save(context: Context, backdrop: Backdrop) =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("backdrop", backdrop.id).apply()
    }
}

/**
 * Scene time in seconds; read it only while drawing so frames skip recomposition.
 * Time stands still while [paused] is true, so the scene resumes without a jump.
 */
@Composable
internal fun rememberBackdropClock(running: Boolean = true, paused: () -> Boolean = { false }): State<Float> = produceState(0f, running) {
    if (!running) return@produceState
    var last = -1L
    while (true) {
        withFrameNanos { now ->
            if (last >= 0 && !paused()) value += ((now - last) / 1_000_000_000f).coerceAtMost(.1f)
            last = now
        }
        // The scenes drift slowly, so ~30 fps looks identical and halves the drawing work.
        delay(26)
    }
}

private const val TAU = (2 * PI).toFloat()

/** A slow wave between -1 and 1 that repeats every [period] seconds. */
private fun wave(t: Float, period: Float, phase: Float = 0f) = sin(t / period * TAU + phase)

internal fun DrawScope.drawBackdrop(backdrop: Backdrop, t: Float) {
    when (backdrop) {
        Backdrop.ZEN -> zen(t)
        Backdrop.AURORA -> aurora(t)
        Backdrop.STILL -> aurora(0f)
        Backdrop.LAVA -> lava(t)
        Backdrop.OCEAN -> ocean(t)
        Backdrop.STARS -> stars(t)
        Backdrop.SUNSET -> sunset(t)
    }
    // Tone everything down so text stays readable on top.
    drawRect(Color(0x66100F0D))
}

/** A soft pool of light that fades to nothing at its edge. */
private fun DrawScope.glow(cx: Float, cy: Float, r: Float, color: Color) =
    drawCircle(Brush.radialGradient(listOf(color, color.copy(alpha = color.alpha * .45f), Color.Transparent), Offset(cx, cy), r), r, Offset(cx, cy))

/** Warm ink with a few faint pools of sand, sage and stone light drifting very slowly, like dusk through paper. */
private fun DrawScope.zen(t: Float) {
    val w = size.width
    val h = size.height
    drawRect(Brush.verticalGradient(listOf(Color(0xFF1A1916), Color(0xFF151513), Color(0xFF121311))))
    fun drift(x: Float, y: Float, r: Float, period: Float, phase: Float, color: Color) = glow(
        w * x + w * .06f * wave(t, period, phase),
        h * y + h * .04f * wave(t, period * 1.4f, phase + 1f),
        w * r * (1f + .05f * wave(t, period * .8f, phase)),
        color
    )
    drift(.18f, .14f, .85f, 70f, 0f, Color(0x40B79E78))   // sand
    drift(.92f, .48f, .80f, 84f, 2.1f, Color(0x388FAE9C)) // sage
    drift(.20f, .88f, .90f, 96f, 4.2f, Color(0x2E8C8A9A)) // stone
    drift(.75f, .05f, .45f, 60f, 1.3f, Color(0x1FE3CFAE)) // a little warm light near the top
}

private fun DrawScope.orb(cx: Float, cy: Float, r: Float, a: Color, b: Color) =
    drawCircle(Brush.linearGradient(listOf(a, b), Offset(cx - r, cy - r), Offset(cx + r, cy + r)), r, Offset(cx, cy))

private fun DrawScope.aurora(t: Float) {
    val w = size.width
    val h = size.height
    drawRect(Brush.verticalGradient(listOf(Color(0xFF0B1226), Color(0xFF181033), Color(0xFF081C2C))))
    // Each orb drifts on its own slow loop so the panels shimmer as they pass.
    fun drift(x: Float, y: Float, r: Float, period: Float, phase: Float, a: Color, b: Color) = orb(
        w * x + w * .08f * wave(t, period, phase),
        h * y + h * .05f * wave(t, period * 1.3f, phase + 1f),
        w * r * (1f + .06f * wave(t, period * .7f, phase)),
        a, b
    )
    drift(.12f, .10f, .34f, 26f, 0f, Color(0xFF4C7DFF), Color(0xFF7A4CFF))
    drift(.92f, .30f, .26f, 31f, 1.7f, Color(0xFFB55CFF), Color(0xFFFF6FA8))
    drift(.30f, .52f, .20f, 23f, 3.1f, Color(0xFF28C9B5), Color(0xFF3F8CFF))
    drift(.85f, .66f, .30f, 35f, 4.4f, Color(0xFF6A5CFF), Color(0xFF2BD0C8))
    drift(.10f, .90f, .30f, 29f, 2.2f, Color(0xFF1FB59B), Color(0xFF2C6BFF))
    drift(.70f, .95f, .16f, 21f, 5.3f, Color(0xFFFFA45C), Color(0xFFFF5C8A))
}

private fun DrawScope.lava(t: Float) {
    val w = size.width
    val h = size.height
    drawRect(Brush.verticalGradient(listOf(Color(0xFF1A0B24), Color(0xFF2B0D1C), Color(0xFF12061A))))
    val blobs = listOf(
        Triple(.20f, .30f, Color(0xFFFF6A3D) to Color(0xFFFF2E7E)),
        Triple(.75f, .24f, Color(0xFFFFB347) to Color(0xFFFF5E3A)),
        Triple(.45f, .22f, Color(0xFFE040FB) to Color(0xFFFF4081)),
        Triple(.88f, .18f, Color(0xFFFF8A65) to Color(0xFFD81B60)),
        Triple(.10f, .20f, Color(0xFFFFD54F) to Color(0xFFFF7043)),
        Triple(.60f, .28f, Color(0xFFAB47BC) to Color(0xFFFF6E40))
    )
    blobs.forEachIndexed { i, (x, r, colors) ->
        val period = 34f + i * 7f
        // Rise and sink like wax in a lamp, swaying a little side to side.
        val rise = (wave(t, period, i * 1.9f) + 1f) / 2f
        val cy = h * (1.05f - rise * 1.1f)
        val cx = w * x + w * .07f * wave(t, period * .6f, i.toFloat())
        val radius = w * r * (1f + .12f * wave(t, period * .4f, i * 2.3f))
        orb(cx, cy, radius, colors.first, colors.second)
    }
}

private fun DrawScope.ocean(t: Float) {
    val w = size.width
    val h = size.height
    drawRect(Brush.verticalGradient(listOf(Color(0xFF06142E), Color(0xFF0A2A4A), Color(0xFF04303A))))
    // A pale moon that slowly bobs above the swell.
    orb(w * .74f, h * (.16f + .015f * wave(t, 18f)), w * .17f, Color(0xFFE8F1FF), Color(0xFF8FB8FF))
    val layers = listOf(
        Color(0xFF1E5BB8) to Color(0xFF173E8C),
        Color(0xFF1689A8) to Color(0xFF0F5C7A),
        Color(0xFF19B3A5) to Color(0xFF0E7A78),
        Color(0xFF3CD6C0) to Color(0xFF138A86)
    )
    layers.forEachIndexed { i, (top, bottom) ->
        val base = h * (.42f + i * .14f)
        val amp = h * (.035f - i * .004f)
        val k = TAU / (w * (1.3f - i * .18f))
        val shift = t / (14f - i * 2.5f) * TAU * (if (i % 2 == 0) 1 else -1)
        val path = Path().apply {
            moveTo(0f, h)
            var x = 0f
            val step = max(4f, w / 60f)
            while (x <= w + step) {
                lineTo(x, base + amp * sin(x * k + shift + i) + amp * .4f * cos(x * k * 2.1f - shift * .7f))
                x += step
            }
            lineTo(w, h)
            close()
        }
        drawPath(path, Brush.verticalGradient(listOf(top, bottom), base - amp, h))
    }
}

private val StarField: List<FloatArray> = Random(7).let { r ->
    List(140) { floatArrayOf(r.nextFloat(), r.nextFloat(), .6f + r.nextFloat() * 1.8f, r.nextFloat() * TAU, 2f + r.nextFloat() * 5f) }
}

private fun DrawScope.stars(t: Float) {
    val w = size.width
    val h = size.height
    drawRect(Brush.verticalGradient(listOf(Color(0xFF03040E), Color(0xFF0B0820), Color(0xFF050B1C))))
    // Soft nebulae for the glass to pick up colour from.
    orb(w * (.25f + .06f * wave(t, 40f)), h * .30f, w * .38f, Color(0x887A4CFF), Color(0x55FF5CA8))
    orb(w * (.80f + .05f * wave(t, 47f, 2f)), h * .72f, w * .42f, Color(0x6628C9B5), Color(0x663F5CFF))
    val unit = size.minDimension / 400f
    StarField.forEach { (x, y, r, phase, speed) ->
        val twinkle = .35f + .65f * (sin(t * speed * .6f + phase) + 1f) / 2f
        val sy = ((y + t * .004f * r) % 1f) * h
        drawCircle(Color.White.copy(alpha = twinkle), r * unit, Offset(x * w, sy))
    }
    // Every 9 seconds a shooting star streaks across for about a second.
    val cycle = t % 9f
    if (cycle < 1.1f) {
        val p = cycle / 1.1f
        val n = (t / 9f).toInt()
        val sx = w * (.15f + (n * .37f % .6f))
        val sy = h * (.08f + (n * .23f % .3f))
        val head = Offset(sx + w * .5f * p, sy + h * .18f * p)
        val tail = Offset(head.x - w * .16f, head.y - h * .06f)
        drawLine(
            Brush.linearGradient(listOf(Color.Transparent, Color.White.copy(alpha = 1f - p)), tail, head),
            tail, head, strokeWidth = 2.5f * unit, cap = StrokeCap.Round
        )
    }
}

private fun DrawScope.sunset(t: Float) {
    val w = size.width
    val h = size.height
    // The sky slowly breathes between dusk and dawn tones.
    val mix = (wave(t, 40f) + 1f) / 2f
    fun blend(a: Long, b: Long) = androidx.compose.ui.graphics.lerp(Color(a), Color(b), mix)
    drawRect(Brush.verticalGradient(listOf(blend(0xFF1B1035, 0xFF0F1A3D), blend(0xFF5A1E5C, 0xFF6B2A6E), blend(0xFFB4435A, 0xFFD0603F), blend(0xFF2A0F2E, 0xFF1F0E26))))
    val sunY = h * (.55f + .05f * wave(t, 30f))
    orb(w * .5f, sunY, w * .26f, Color(0xFFFFC46B), Color(0xFFFF5E6C))
    orb(w * (.15f + .05f * wave(t, 37f)), h * .22f, w * .22f, Color(0xFF7A4CFF), Color(0xFFE05CFF))
    orb(w * (.88f + .04f * wave(t, 33f, 1f)), h * .35f, w * .18f, Color(0xFFFF7EB3), Color(0xFFFF9A5C))
    // Shimmering bands on the water below the sun.
    val water = h * .66f
    drawRect(Brush.verticalGradient(listOf(Color(0xFF2A1240), Color(0xFF0B0A1E)), water, h), Offset(0f, water))
    repeat(7) { i ->
        val y = water + (h - water) * (i + 1) / 9f
        val half = w * (.22f - i * .02f) * (1f + .25f * wave(t, 3f + i * .7f, i.toFloat()))
        drawLine(Color(0xFFFFB36B).copy(alpha = .55f - i * .06f), Offset(w * .5f - half, y), Offset(w * .5f + half, y), strokeWidth = h * .006f, cap = StrokeCap.Round)
    }
}
