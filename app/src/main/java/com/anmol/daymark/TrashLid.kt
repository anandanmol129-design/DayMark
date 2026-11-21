package com.anmol.daymark

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

private val lidPath = PathParser().parsePathString("M3 6h18M8 6V4c0-1 1-2 2-2h4c1 0 2 1 2 2v2").toPath()
private val canPath = PathParser().parsePathString("M19 6v14c0 1-1 2-2 2H7c-1 0-2-1-2-2V6M10 11v6M14 11v6").toPath()

/**
 * The trash glyph with a lid that can swing open: [open] 0 is shut, 1 is lifted and tipped back
 * on its left hinge.
 */
@Composable
internal fun TrashGlyph(open: Float, tint: Color, size: Dp, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size)) {
        val s = this.size.minDimension / 24f
        scale(s, s, pivot = Offset.Zero) {
            drawPath(canPath, tint, style = Stroke(1.7f, cap = StrokeCap.Round, join = StrokeJoin.Round))
            translate(top = -2.2f * open) {
                rotate(-28f * open, pivot = Offset(3f, 6f)) {
                    drawPath(lidPath, tint, style = Stroke(1.7f, cap = StrokeCap.Round, join = StrokeJoin.Round))
                }
            }
        }
    }
}

/**
 * A delete button that needs two taps: the first opens the lid (and turns it red), the second
 * deletes. Left alone, the lid shuts again after a few seconds.
 */
@Composable
internal fun TapTwiceTrash(onDelete: () -> Unit, modifier: Modifier = Modifier, size: Dp = 22.dp, tint: Color = TodoMuted) {
    val haptics = LocalHapticFeedback.current
    var armed by remember { mutableStateOf(false) }
    val open by animateFloatAsState(if (armed) 1f else 0f, spring(dampingRatio = .45f, stiffness = 500f), label = "lid")
    val color by animateColorAsState(if (armed) Danger else tint, tween(140), label = "lidTint")
    LaunchedEffect(armed) { if (armed) { delay(2600); armed = false } }
    Box(
        modifier.clip(CircleShape)
            .semantics { contentDescription = if (armed) "Tap again to delete" else "Delete" }
            .clickable {
                if (armed) { armed = false; haptics.performHapticFeedback(HapticFeedbackType.Confirm); onDelete() }
                else { armed = true; haptics.performHapticFeedback(HapticFeedbackType.SegmentTick) }
            }
            .padding(8.dp),
        contentAlignment = Alignment.Center
    ) { TrashGlyph(open, color, size) }
}
