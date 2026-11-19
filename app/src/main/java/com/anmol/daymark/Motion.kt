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
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
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

/** How long a carousel swing between tabs takes. */
private const val CarouselMs = 420

/** How far each screen is turned when it sits one step off-centre on the carousel. */
private const val CarouselAngle = 40f

/**
 * Swings a tab's screen round a carousel when switching tabs: the old screen slides off to one
 * side, its far edge swinging toward you, while the new one comes round from the other side and faces you. Both move
 * at once, hinged on the edge they share. [forward] is true when moving right along the tab bar.
 */
@Composable
internal fun androidx.compose.animation.AnimatedVisibilityScope.carousel(forward: Boolean): Modifier {
    // -1 is one step to the left, 0 is facing you, 1 is one step to the right.
    val offset by transition.animateFloat(
        transitionSpec = { androidx.compose.animation.core.tween(CarouselMs, easing = androidx.compose.animation.core.FastOutSlowInEasing) },
        label = "carousel"
    ) { state ->
        when (state) {
            androidx.compose.animation.EnterExitState.PreEnter -> if (forward) 1f else -1f
            androidx.compose.animation.EnterExitState.Visible -> 0f
            androidx.compose.animation.EnterExitState.PostExit -> if (forward) -1f else 1f
        }
    }
    return Modifier.graphicsLayer {
        translationX = offset * size.width
        // Hinge on the edge that faces the centre, so the two screens stay joined as they swing.
        transformOrigin = androidx.compose.ui.graphics.TransformOrigin(if (offset > 0f) 0f else 1f, .5f)
        // The outer edge swings toward you, as if you stood inside the ring of screens.
        rotationY = -offset * CarouselAngle
        cameraDistance = 10f * density
        clip = false
    }
}

/**
 * Bounce between pages inside a tab: each page shrinks a little as it slides away from the
 * centre, and grows back as it settles. The rubber-band spring makes it overshoot and pop.
 */
internal fun Modifier.bounce(state: androidx.compose.foundation.pager.PagerState, page: Int): Modifier = graphicsLayer {
    val away = kotlin.math.abs((state.currentPage - page) + state.currentPageOffsetFraction).coerceAtMost(1f)
    val s = 1f - .14f * away
    scaleX = s
    scaleY = s
    alpha = 1f - .35f * away
}

/**
 * Moving between pages inside a tab: a spring that starts slow, speeds up, then overshoots a
 * little and settles back, like a rubber band.
 */
internal val PageSpring = spring<Float>(dampingRatio = .62f, stiffness = 260f)

/** Swipes settle onto the next page with the same rubber-band spring. */
@Composable
internal fun rubberFling(state: androidx.compose.foundation.pager.PagerState) =
    androidx.compose.foundation.pager.PagerDefaults.flingBehavior(state, snapAnimationSpec = PageSpring)

/**
 * Moves to the next (true) or previous (false) tab, for screens that handle sideways swipes
 * themselves (Calendar's days) and want to hand the swipe on once they reach their edge.
 */
internal val LocalTabSwipe = androidx.compose.runtime.staticCompositionLocalOf<(Boolean) -> Unit> { {} }

/** How far a sideways swipe has to carry on past a tab's own pages before it moves to the next tab. */
private val TabSwipeDistance = 72.dp

/**
 * Swiping sideways moves between tabs. Inside a tab with pages of its own (folders, Month/Year,
 * the List/Columns pages), the swipe moves through those first; carrying on past the first or last
 * page moves to the neighbouring tab. A tab with nothing to swipe sideways switches straight away.
 * [onSwipe] gets true for the next tab (swiping left), false for the previous one.
 */
@Composable
internal fun Modifier.swipeBetweenTabs(onSwipe: (forward: Boolean) -> Unit): Modifier {
    val threshold = with(androidx.compose.ui.platform.LocalDensity.current) { TabSwipeDistance.toPx() }
    val latest = androidx.compose.runtime.rememberUpdatedState(onSwipe)
    val pull = androidx.compose.runtime.remember { floatArrayOf(0f) }
    val connection = androidx.compose.runtime.remember {
        object : androidx.compose.ui.input.nestedscroll.NestedScrollConnection {
            // What a page row couldn't use because it was already at its first or last page.
            override fun onPostScroll(consumed: androidx.compose.ui.geometry.Offset, available: androidx.compose.ui.geometry.Offset, source: androidx.compose.ui.input.nestedscroll.NestedScrollSource): androidx.compose.ui.geometry.Offset {
                if (source == androidx.compose.ui.input.nestedscroll.NestedScrollSource.UserInput) pull[0] += available.x
                return androidx.compose.ui.geometry.Offset.Zero
            }
            // The finger lifted: switch if it pulled far enough past the edge.
            override suspend fun onPreFling(available: androidx.compose.ui.unit.Velocity): androidx.compose.ui.unit.Velocity {
                val p = pull[0]
                pull[0] = 0f
                if (kotlin.math.abs(p) > threshold) latest.value(p < 0)
                return androidx.compose.ui.unit.Velocity.Zero
            }
        }
    }
    return this
        .nestedScroll(connection)
        .pointerInput(Unit) {
            // Screens with nothing that scrolls sideways: a plain sideways drag (anything inside that scrolls takes the drag first).
            var total = 0f
            detectHorizontalDragGestures(
                onDragStart = { total = 0f },
                onDragEnd = { if (kotlin.math.abs(total) > threshold) latest.value(total < 0) }
            ) { change, amount -> total += amount; change.consume() }
        }
}
