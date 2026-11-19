package com.anmol.daymark

import android.os.SystemClock
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.draw.scale
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import android.view.WindowManager
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// The blurred copy of the backdrop; glass panels paint the slice behind themselves.
internal val LocalBackdrop = staticCompositionLocalOf<GraphicsLayer?> { null }

@Composable
internal fun GlassBackdrop(content: @Composable () -> Unit) {
    val light = Look.light
    val sharp = rememberGraphicsLayer()
    val blurred = rememberGraphicsLayer().apply {
        renderEffect = BlurEffect(48f / BlurDownscale, 48f / BlurDownscale, TileMode.Clamp)
    }
    // While a finger is down (and shortly after), hold the scene still so scrolls and
    // transitions get the whole frame budget instead of sharing it with the background.
    val lastTouch = remember { longArrayOf(0L) }
    Box(Modifier.fillMaxSize().pointerInput(Unit) {
        awaitPointerEventScope {
            while (true) {
                awaitPointerEvent(PointerEventPass.Initial)
                lastTouch[0] = SystemClock.uptimeMillis()
            }
        }
    }) {
        // The tab colour lighting the edges fades across when the tab changes (only the backdrop redraws).
        val glow by androidx.compose.animation.animateColorAsState(Look.accent.fill, androidx.compose.animation.core.tween(450), label = "edgeGlow")
        if (light) {
            // Material surfaces sit on a calm cream, lit by the tab's colour from beyond the top and bottom edges.
            Canvas(Modifier.fillMaxSize()) {
                drawRect(TodoBackground)
                drawEdgeGlow(glow, EdgeGlowStrength)
            }
        } else {
            // Record the scene once per frame; glass panels reuse the blurred copy without redrawing.
            Box(
                Modifier.fillMaxSize().drawWithContent {
                    sharp.record { this@drawWithContent.drawContent() }
                    // Blur a small copy: it looks the same once blurred and costs far less every frame.
                    blurred.record(IntSize((size.width / BlurDownscale).toInt().coerceAtLeast(1), (size.height / BlurDownscale).toInt().coerceAtLeast(1))) {
                        scale(1f / BlurDownscale, 1f / BlurDownscale, pivot = Offset.Zero) { drawLayer(sharp) }
                    }
                    drawLayer(sharp)
                }
            ) {
                val clock = rememberBackdropClock(paused = { SystemClock.uptimeMillis() - lastTouch[0] < 1500 })
                Canvas(Modifier.fillMaxSize()) { drawBackdrop(clock.value); drawEdgeGlow(glow, EdgeGlowStrength) }
            }
        }
        // The content stays in one place whichever look is showing, so flipping the theme keeps its state.
        CompositionLocalProvider(LocalBackdrop provides if (light) null else blurred) { content() }
    }
}

private const val BlurDownscale = 4f

/** Draws the downscaled blurred backdrop back at full size. */
internal fun DrawScope.drawBlurred(layer: GraphicsLayer) = scale(BlurDownscale, BlurDownscale, pivot = Offset.Zero) { drawLayer(layer) }

/** Real frosted glass: blurs the backdrop behind this node, then lets its tint draw on top. */
@Composable
internal fun Modifier.glassBlur(shape: Shape): Modifier {
    val backdrop = LocalBackdrop.current ?: return this
    var origin by remember { mutableStateOf(Offset.Zero) }
    return this
        .onGloballyPositioned { origin = it.positionInRoot() }
        .clip(shape)
        .drawBehind { translate(-origin.x, -origin.y) { drawBlurred(backdrop) } }
}

/** Blur whatever sits behind a dialog window (Android 12+). */
@Composable
internal fun DialogBlur() {
    val window = (LocalView.current.parent as? DialogWindowProvider)?.window
    SideEffect {
        window?.apply {
            addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
            attributes = attributes.apply { blurBehindRadius = 60 }
        }
    }
}

@Composable
internal fun GlassPill(icon: ZenIcon, label: String, onClick: () -> Unit) {
    Surface(color = TodoCard, shape = CircleShape, border = GlassStroke, modifier = Modifier.glassBlur(CircleShape).clip(CircleShape).clickable(onClick = onClick)) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Glyph(icon, TodoBlue, 16.dp)
            Spacer(Modifier.width(7.dp))
            Text(label, color = TodoText, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}
