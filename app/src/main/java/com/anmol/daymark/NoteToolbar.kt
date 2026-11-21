package com.anmol.daymark

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties

/** One button in the note toolbar: an [icon], or short [text] when there is no icon (H1, Text…). */
internal data class ToolAction(
    val label: String,
    val icon: ZenIcon? = null,
    val text: String? = null,
    val active: Boolean = false,
    val enabled: Boolean = true,
    val onClick: () -> Unit = {}
)

/** A toolbar slot: a single action, or a group whose choices pop out above it when tapped. */
internal sealed interface ToolSlot {
    data class Single(val action: ToolAction) : ToolSlot
    /** [face] is what the slot shows; it follows the active choice when one is on (so a bullet list shows the bullet icon). */
    data class Group(val face: ToolAction, val choices: List<ToolAction>) : ToolSlot
}

/** Formatting row that sits directly above the keyboard while writing a note. */
@Composable
internal fun NoteToolbar(slots: List<ToolSlot>) {
    var open by remember { mutableStateOf<Int?>(null) }
    Column(Modifier.fillMaxWidth()) {
        HorizontalDivider(color = TodoBorder)
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            slots.forEachIndexed { i, slot ->
                Box(Modifier.weight(1f)) {
                    when (slot) {
                        is ToolSlot.Single -> ToolButton(slot.action, slot.action.active, onClick = slot.action.onClick)
                        is ToolSlot.Group -> {
                            val chosen = slot.choices.firstOrNull { it.active }
                            val face = chosen?.let { slot.face.copy(icon = it.icon, text = it.text) } ?: slot.face
                            ToolButton(face, active = chosen != null || open == i, more = true) { open = if (open == i) null else i }
                            if (open == i) ToolChoices(slot.choices) { open = null }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ToolButton(action: ToolAction, active: Boolean, more: Boolean = false, onClick: () -> Unit) {
    val tint = when { !action.enabled -> TodoMuted.copy(alpha = .35f); active -> TodoText; else -> TodoMuted }
    Box(
        Modifier.fillMaxWidth().height(42.dp).padding(horizontal = 1.dp).clip(RoundedCornerShape(12.dp))
            .background(if (active) GlassAccent else androidx.compose.ui.graphics.Color.Transparent)
            .clickable(enabled = action.enabled, onClick = onClick)
            .semantics { contentDescription = action.label },
        contentAlignment = Alignment.Center
    ) {
        if (action.icon != null) Glyph(action.icon, tint, 20.dp)
        else Text(action.text ?: action.label, color = tint, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
        // A tiny caret in the corner says "more choices in here".
        if (more) Glyph(ZenIcon.ChevronUp, tint.copy(alpha = tint.alpha * .7f), 9.dp, Modifier.align(Alignment.TopEnd).padding(top = 5.dp, end = 7.dp))
    }
}

/** The choices of a group, springing up out of its button in a floating pill. */
@Composable
private fun ToolChoices(choices: List<ToolAction>, onClose: () -> Unit) {
    val haptics = LocalHapticFeedback.current
    // The popup window is padded by [pad] on every side so the pill's shadow isn't cut off at its edges.
    val pad = with(LocalDensity.current) { 16.dp.roundToPx() }
    val gap = pad / 2
    val grow = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
        grow.animateTo(1f, spring(dampingRatio = .55f, stiffness = 520f))
    }
    var anchorX by remember { mutableStateOf(.5f) }
    Popup(
        popupPositionProvider = remember {
            object : PopupPositionProvider {
                override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset {
                    val x = (anchorBounds.center.x - popupContentSize.width / 2).coerceIn(gap - pad, (windowSize.width - popupContentSize.width - gap + pad).coerceAtLeast(gap - pad))
                    anchorX = ((anchorBounds.center.x - x - pad).toFloat() / (popupContentSize.width - 2 * pad).coerceAtLeast(1)).coerceIn(0f, 1f)
                    return IntOffset(x, anchorBounds.top - popupContentSize.height + pad - gap)
                }
            }
        },
        onDismissRequest = onClose,
        properties = PopupProperties(focusable = false, dismissOnClickOutside = true)
    ) {
        val shape = RoundedCornerShape(20.dp)
        Row(
            Modifier
                .padding(16.dp)
                .graphicsLayer {
                    val v = grow.value
                    scaleX = .55f + .45f * v; scaleY = .55f + .45f * v; alpha = v.coerceIn(0f, 1f)
                    translationY = (1f - v) * 24.dp.toPx()
                    transformOrigin = TransformOrigin(anchorX, 1f)
                }
                .shadow(14.dp, shape).clip(shape).background(SheetSolid).border(GlassStroke, shape)
                .padding(6.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            choices.forEach { choice ->
                Column(
                    Modifier.width(66.dp).clip(RoundedCornerShape(14.dp))
                        .background(if (choice.active) GlassAccent else androidx.compose.ui.graphics.Color.Transparent)
                        .clickable(enabled = choice.enabled) { onClose(); choice.onClick() }
                        .padding(vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    val tint = if (choice.active) TodoText else TodoMuted
                    Box(Modifier.size(26.dp), contentAlignment = Alignment.Center) {
                        if (choice.icon != null) Glyph(choice.icon, tint, 22.dp)
                        else Text(choice.text ?: choice.label, color = tint, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                    }
                    Text(choice.label, color = tint, fontSize = 11.sp, maxLines = 1, modifier = Modifier.padding(top = 3.dp))
                }
            }
        }
    }
}
