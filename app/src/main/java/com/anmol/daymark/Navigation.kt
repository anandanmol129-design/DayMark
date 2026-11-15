package com.anmol.daymark

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.ui.unit.TextUnit
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.animation.core.Spring
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.ui.draw.scale
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** [selected] null means the Today screen, which sits first in the bar but isn't a [Workspace]. */
@Composable
internal fun WorkspaceNavigation(selected: Workspace?, darkTheme: Boolean, calendarTheme: Boolean, onSelect: (Workspace?) -> Unit, onCreate: () -> Unit, privateOpen: Boolean = false, onLongPressNotes: () -> Unit = {}) {
    val island = RoundedCornerShape(30.dp)
    val haptics = LocalHapticFeedback.current
    // Five even tabs in the glass bar, with the create button as its own glass circle beside it.
    val tabs = listOf<Workspace?>(null, Workspace.TODOS, Workspace.CALENDAR, Workspace.NOTES, Workspace.TRACK)
    val index = tabs.indexOf(selected).coerceAtLeast(0)
    Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(start = 14.dp, end = 14.dp, top = 6.dp, bottom = 18.dp), verticalAlignment = Alignment.CenterVertically) {
        Surface(color = TodoNav, shape = island, border = GlassStroke, shadowElevation = 10.dp, modifier = Modifier.weight(1f).glassBlur(island)) {
            BoxWithConstraints(Modifier.fillMaxWidth().padding(5.dp)) {
                val slot = maxWidth / tabs.size
                // One soft pill glides between tabs on a spring, like iOS segmented controls.
                val pillX by animateDpAsState(slot * index, spring(dampingRatio = .78f, stiffness = Spring.StiffnessMediumLow), label = "navPill")
                Box(Modifier.offset(x = pillX).width(slot).height(54.dp).clip(RoundedCornerShape(25.dp)).background(Color(0x1FFFFFFF)))
                Row(Modifier.fillMaxWidth()) {
                    tabs.forEachIndexed { i, workspace ->
                        val notes = workspace == Workspace.NOTES
                        NavigationItem(
                            workspace, i == index, Modifier.weight(1f), private = notes && privateOpen,
                            // Holding Notes opens (or closes) the locked Private section.
                            onLongClick = if (notes) ({ haptics.performHapticFeedback(HapticFeedbackType.LongPress); onLongPressNotes() }) else null
                        ) {
                            if (i != index) haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                            onSelect(workspace)
                        }
                    }
                }
            }
        }
        Spacer(Modifier.width(10.dp))
        Surface(
            color = CalendarBlue, shape = CircleShape, shadowElevation = 10.dp,
            modifier = Modifier.size(60.dp).clip(CircleShape).clickable(onClick = onCreate).semantics { contentDescription = "Create" }
        ) {
            Box(contentAlignment = Alignment.Center) { Glyph(ZenIcon.Plus, Color.White, 26.dp) }
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun NavigationItem(workspace: Workspace?, selected: Boolean, modifier: Modifier, private: Boolean = false, onLongClick: (() -> Unit)? = null, onClick: () -> Unit) {
    val label = if (private) "Private" else when (workspace) { null -> "Today"; Workspace.TODOS -> "Inbox"; else -> workspace.title }
    val tint by animateColorAsState(if (private) Amber else if (selected) TodoText else TodoMuted.copy(alpha = .8f), tween(240), label = "navTint")
    val lift by animateFloatAsState(if (selected) 1f else .94f, spring(dampingRatio = .5f, stiffness = Spring.StiffnessMedium), label = "navLift")
    Column(
        modifier.height(54.dp).clip(RoundedCornerShape(25.dp)).combinedClickable(onClick = onClick, onLongClick = onLongClick).semantics { contentDescription = label },
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center
    ) {
        androidx.compose.animation.Crossfade(if (private) ZenIcon.Lock else workspace?.icon ?: ZenIcon.Sun, label = "navIcon") { icon -> Glyph(icon, tint, 22.dp, modifier = Modifier.scale(lift)) }
        Spacer(Modifier.height(3.dp))
        Text(label, color = tint, fontSize = 10.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal, letterSpacing = .2.sp, maxLines = 1)
    }
}

/** Opens Settings from any tab's header without threading a callback through every screen. */
internal val LocalOpenSettings = staticCompositionLocalOf<() -> Unit> { {} }

/** A round glass button holding one icon; used across headers. */
@Composable
internal fun IconCircle(icon: ZenIcon, description: String, tint: Color = TodoText, onClick: () -> Unit) {
    Surface(color = TodoCard, shape = CircleShape, border = GlassStroke, modifier = Modifier.size(42.dp).glassBlur(CircleShape).clip(CircleShape).clickable(onClick = onClick).semantics { contentDescription = description }) {
        Box(contentAlignment = Alignment.Center) { Glyph(icon, tint, 19.dp) }
    }
}

@Composable
internal fun SettingsButton() {
    SearchButton() // search sits just left of settings on every tab
    Spacer(Modifier.width(10.dp))
    IconCircle(ZenIcon.Settings, "Settings", onClick = LocalOpenSettings.current)
}

@Composable
internal fun NewButton(onClick: () -> Unit) = GlassPill(ZenIcon.Plus, "New", onClick)

@Composable
internal fun ViewToggle(view: BoardView, onToggle: () -> Unit) {
    val nextView = if (view == BoardView.LIST) BoardView.BOARD else BoardView.LIST
    GlassPill(if (nextView == BoardView.LIST) ZenIcon.Lines else ZenIcon.Board, nextView.label, onToggle)
}

@Composable
internal fun PagerDot(active: Boolean, onClick: () -> Unit) {
    val width by animateDpAsState(if (active) 20.dp else 7.dp, spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMediumLow), label = "dotWidth")
    val color by animateColorAsState(if (active) TodoText else TodoMuted.copy(alpha = .6f), tween(250), label = "dotColor")
    Box(Modifier.padding(horizontal = 3.dp).size(width = width, height = 7.dp).clip(CircleShape).background(color).clickable(onClick = onClick))
}

/** The page name; tapping it offers to delete the page (not for the last page or Saved). */
@Composable
internal fun PageTitle(name: String, deletable: Boolean, onDelete: () -> Unit, modifier: Modifier = Modifier, size: TextUnit = 26.sp, onEdit: (() -> Unit)? = null) {
    var menu by remember { mutableStateOf(false) }
    Box(modifier) {
        Text(
            name, color = TodoText, fontSize = size, fontWeight = if (size > 30.sp) FontWeight.Bold else FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.clip(RoundedCornerShape(10.dp)).clickable(enabled = deletable || onEdit != null) { menu = true }
        )
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            if (onEdit != null) DropdownMenuItem(text = { Text("Edit") }, onClick = { menu = false; onEdit() })
            if (deletable) DropdownMenuItem(text = { Text("Delete page", color = Danger) }, onClick = { menu = false; onDelete() })
        }
    }
}
