package com.anmol.daymark

import kotlinx.coroutines.launch
import androidx.compose.ui.graphics.graphicsLayer
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
        Surface(color = TodoNav, shape = island, border = GlassStroke, shadowElevation = if (Look.light) 4.dp else 10.dp, modifier = Modifier.weight(1f).glassBlur(island)) {
            BoxWithConstraints(Modifier.fillMaxWidth().padding(5.dp)) {
                val slot = maxWidth / tabs.size
                // One soft pill glides between tabs on a spring, like iOS segmented controls.
                val pillX by animateDpAsState(slot * index, spring(dampingRatio = .78f, stiffness = Spring.StiffnessMediumLow), label = "navPill")
                Box(Modifier.offset(x = pillX).width(slot).height(54.dp).clip(RoundedCornerShape(25.dp)).background(if (Look.light) Look.accent.container else veil(0.12f)))
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
        val fab = if (Look.light) RoundedCornerShape(22.dp) else CircleShape
        Surface(
            color = CalendarBlue, shape = fab, shadowElevation = if (Look.light) 4.dp else 10.dp,
            modifier = Modifier.size(60.dp).clip(fab).clickable(onClick = onCreate).semantics { contentDescription = "Create" }
        ) {
            Box(contentAlignment = Alignment.Center) { Glyph(ZenIcon.Plus, OnFill, 26.dp) }
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun NavigationItem(workspace: Workspace?, selected: Boolean, modifier: Modifier, private: Boolean = false, onLongClick: (() -> Unit)? = null, onClick: () -> Unit) {
    val label = if (private) "Private" else when (workspace) { null -> "Today"; Workspace.TODOS -> "Inbox"; else -> workspace.title }
    val tint by animateColorAsState(if (private) Amber else if (selected) (if (Look.light) TodoBlue else TodoText) else TodoMuted.copy(alpha = .8f), tween(240), label = "navTint")
    val lift by animateFloatAsState(if (selected) 1f else .94f, spring(dampingRatio = .5f, stiffness = Spring.StiffnessMedium), label = "navLift")
    val icon = if (private) ZenIcon.Lock else workspace?.icon ?: ZenIcon.Sun
    // Each tap plays a little animation that suits the icon (see tapMotion).
    val tap = remember { androidx.compose.animation.core.Animatable(1f) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    fun play() {
        scope.launch {
            tap.snapTo(0f)
            tap.animateTo(1f, tween(if (icon == ZenIcon.Calendar) 560 else 520, easing = androidx.compose.animation.core.LinearEasing))
        }
    }
    Column(
        modifier.height(54.dp).clip(RoundedCornerShape(25.dp))
            .combinedClickable(onClick = { play(); onClick() }, onLongClick = onLongClick?.let { long -> { play(); long() } })
            .semantics { contentDescription = label },
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center
    ) {
        androidx.compose.animation.Crossfade(icon, label = "navIcon") { shown ->
            Glyph(shown, tint, 22.dp, modifier = Modifier.scale(lift).graphicsLayer { tapMotion(shown, tap.value) })
        }
        Spacer(Modifier.height(3.dp))
        Text(label, color = tint, fontSize = 10.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal, letterSpacing = .2.sp, maxLines = 1)
    }
}

private const val NavTau = (2 * Math.PI).toFloat()
// Overshoots a little past the end, then settles.
private val Overshoot = androidx.compose.animation.core.CubicBezierEasing(.34f, 1.56f, .64f, 1f)

/**
 * The tap animation for a bottom-bar icon, at [t] from 0 to 1: the sun turns, the inbox tray
 * bounces as if something dropped in, the calendar page flips over, the note wiggles, the habit
 * grid pops and the lock shakes.
 */
private fun androidx.compose.ui.graphics.GraphicsLayerScope.tapMotion(icon: ZenIcon, t: Float) {
    if (t >= 1f) return
    val fade = 1f - t
    when (icon) {
        ZenIcon.Sun -> rotationZ = 90f * Overshoot.transform(t) // the rays repeat every 45°, so it ends looking unturned
        ZenIcon.Inbox -> translationY = 6.dp.toPx() * kotlin.math.abs(kotlin.math.sin(NavTau * t)) * fade
        ZenIcon.Calendar -> { rotationX = 360f * androidx.compose.animation.core.FastOutSlowInEasing.transform(t); cameraDistance = 8f * density }
        ZenIcon.Note -> rotationZ = 14f * kotlin.math.sin(NavTau * 1.5f * t) * fade
        ZenIcon.Lock -> translationX = 3.dp.toPx() * kotlin.math.sin(NavTau * 3f * t) * fade
        else -> { val pop = 1f + .28f * kotlin.math.sin(Math.PI.toFloat() * t) * fade; scaleX = pop; scaleY = pop; rotationZ = -8f * kotlin.math.sin(NavTau * t) * fade }
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
            name, color = TodoText, fontSize = size, fontWeight = if (size > 30.sp) (if (Look.light) FontWeight.Black else FontWeight.Bold) else if (Look.light) FontWeight.Bold else FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.clip(RoundedCornerShape(10.dp)).clickable(enabled = deletable || onEdit != null) { menu = true }
        )
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            if (onEdit != null) DropdownMenuItem(text = { Text("Edit") }, onClick = { menu = false; onEdit() })
            if (deletable) DropdownMenuItem(text = { Text("Delete page", color = Danger) }, onClick = { menu = false; onDelete() })
        }
    }
}

/** Search, floating just above the + button where a thumb can reach it; opens the same search as the header button. */
@Composable
internal fun FloatingSearchButton() {
    Surface(
        color = TodoNav, shape = CircleShape, border = GlassStroke, shadowElevation = 10.dp,
        modifier = Modifier.size(52.dp).glassBlur(CircleShape).clip(CircleShape)
            .clickable(onClick = LocalOpenSearch.current).semantics { contentDescription = "Search" }
    ) {
        Box(contentAlignment = Alignment.Center) { Glyph(ZenIcon.Search, TodoText, 22.dp) }
    }
}
