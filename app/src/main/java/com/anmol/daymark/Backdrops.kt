package com.anmol.daymark

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.sin

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

/** The Zen scene painted behind the glass panels, toned down so text stays readable on top. */
internal fun DrawScope.drawBackdrop(t: Float) {
    zen(t)
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
