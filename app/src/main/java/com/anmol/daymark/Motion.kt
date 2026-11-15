package com.anmol.daymark

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.foundation.LocalIndication
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.getValue
import androidx.compose.animation.core.animateFloat
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.invalidateDraw
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.min

/**
 * Apple-style touch feedback: whatever you press sinks in a little and springs back,
 * instead of a Material ripple. Small things sink a bit more than big cards, so a
 * whole list row barely moves while an icon button gives a clear "press".
 */
private object SquishIndication : IndicationNodeFactory {
    override fun create(interactionSource: InteractionSource): DelegatableNode = SquishNode(interactionSource)
    override fun equals(other: Any?) = other === this
    override fun hashCode() = 7
}

private class SquishNode(private val source: InteractionSource) : Modifier.Node(), DrawModifierNode {
    private val pressed = Animatable(0f)

    override fun onAttach() {
        coroutineScope.launch {
            var presses = 0
            source.interactions.collect { interaction ->
                when (interaction) {
                    is PressInteraction.Press -> presses++
                    is PressInteraction.Release, is PressInteraction.Cancel -> presses = max(0, presses - 1)
                }
                val down = presses > 0
                launch {
                    // Sink quickly, then settle back with a soft overshoot.
                    pressed.animateTo(
                        if (down) 1f else 0f,
                        if (down) spring(stiffness = Spring.StiffnessHigh) else spring(dampingRatio = .55f, stiffness = Spring.StiffnessMediumLow)
                    ) { invalidateDraw() }
                }
            }
        }
    }

    override fun ContentDrawScope.draw() {
        val p = pressed.value
        if (p == 0f) { drawContent(); return }
        val longest = max(size.width, size.height).coerceAtLeast(1f)
        // About 5dp of travel, capped at 8% for tiny targets.
        val depth = min(.08f, 5f * density / longest)
        scale(1f - depth * p) { this@draw.drawContent() }
    }
}

/** Installs the squish feedback for every clickable under [content] and turns ripples off. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ZenTouch(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalIndication provides SquishIndication, LocalRippleConfiguration provides null, content = content)
}

/** Shared motion curves so every screen moves the same way. */
internal object ZenMotion {
    val gentle = spring<Float>(dampingRatio = .82f, stiffness = Spring.StiffnessMediumLow)
    val bouncy = spring<Float>(dampingRatio = .6f, stiffness = Spring.StiffnessMedium)
}

/** Content drifts up a few dp and fades in when it first appears; [order] staggers siblings. */
@Composable
internal fun Modifier.rise(order: Int = 0): Modifier {
    val progress = androidx.compose.runtime.remember { Animatable(0f) }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(order.coerceAtMost(6) * 45L)
        progress.animateTo(1f, spring(dampingRatio = .9f, stiffness = Spring.StiffnessLow))
    }
    return graphicsLayer {
        alpha = progress.value
        translationY = (1f - progress.value) * 18.dp.toPx()
    }
}

/** A slow, barely-there swell, like breathing; for empty-state icons. */
@Composable
internal fun Modifier.breathe(): Modifier {
    val transition = androidx.compose.animation.core.rememberInfiniteTransition(label = "breathe")
    val s by transition.animateFloat(
        1f, 1.07f,
        androidx.compose.animation.core.infiniteRepeatable(androidx.compose.animation.core.tween<Float>(3200, easing = androidx.compose.animation.core.FastOutSlowInEasing), androidx.compose.animation.core.RepeatMode.Reverse),
        label = "breath"
    )
    return graphicsLayer { scaleX = s; scaleY = s }
}
