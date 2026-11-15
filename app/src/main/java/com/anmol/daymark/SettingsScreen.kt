package com.anmol.daymark

import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.runtime.produceState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.foundation.BorderStroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.animation.core.Spring
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.ui.draw.scale
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
internal fun SettingsScreen(
    snapshot: BoardSnapshot,
    lastBackup: Long,
    busy: Boolean,
    snackbar: SnackbarHostState,
    onBack: () -> Unit,
    onBackup: () -> Unit,
    onRestore: () -> Unit,
    onImportCalendar: () -> Unit,
    backdrop: Backdrop,
    onBackdrop: (Backdrop) -> Unit
) {
    val context = LocalContext.current
    val version = remember { runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull().orEmpty() }
    val imageBytes by produceState(0L, snapshot.cards.size) {
        value = withContext(Dispatchers.IO) { Saves.imageDir(context).listFiles()?.sumOf { it.length() } ?: 0L }
    }
    val tasks = snapshot.cards.count { it.workspace == Workspace.TODOS }
    val events = snapshot.cards.count { it.workspace == Workspace.CALENDAR }
    val notes = snapshot.cards.count { it.workspace == Workspace.NOTES }
    val tracked = snapshot.cards.count { it.workspace == Workspace.TRACK }
    val backedUp = if (lastBackup == 0L) "Never backed up" else "Last backup " + java.time.Instant.ofEpochMilli(lastBackup)
        .atZone(java.time.ZoneId.systemDefault())
        .format(DateTimeFormatter.ofPattern("d MMM yyyy, h:mm a", Locale.getDefault()))

    val blurred = LocalBackdrop.current
    Box(
        Modifier.fillMaxSize()
            // Paint the blurred background so nothing from the tab underneath shows through.
            .drawBehind {
                drawRect(TodoBackground)
                blurred?.let { drawBlurred(it) }
                drawRect(Color(0x590B1226))
            }
            // Swallow taps so they don't reach the tab underneath.
            .pointerInput(Unit) { detectTapGestures { } }
            .statusBarsPadding()
    ) {
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 40.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item {
                Row(Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Glyph(ZenIcon.ChevronLeft, TodoText, 24.dp, modifier = Modifier.clip(CircleShape).clickable(onClick = onBack).padding(horizontal = 12.dp, vertical = 2.dp))
                    Text("Settings", color = TodoText, fontSize = 26.sp, fontWeight = FontWeight.SemiBold)
                }
            }
            item {
                Text("BACKGROUND", color = TodoMuted, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp, modifier = Modifier.padding(start = 6.dp, bottom = 6.dp))
                BackdropPicker(selected = backdrop, onSelect = onBackdrop)
            }
            item {
                SettingsSection("Backup") {
                    SettingsRow(ZenIcon.Upload, "Back up now", backedUp, enabled = !busy, onClick = onBackup)
                    SettingsDivider()
                    SettingsRow(ZenIcon.Restore, "Restore from backup", "Replaces everything with a backup file", enabled = !busy, onClick = onRestore)
                }
                Text(
                    if (busy) "Working…" else "Backups are a single .zip with all your pages, tasks, notes and images. Keep one somewhere safe, like Drive or Downloads.",
                    color = TodoMuted, fontSize = 12.sp, modifier = Modifier.padding(start = 6.dp, end = 6.dp, top = 8.dp)
                )
            }
            item {
                SettingsSection("Calendar") {
                    SettingsRow(ZenIcon.Download, "Import device calendar", "Pull events from calendars on this phone", onClick = onImportCalendar)
                }
            }
            item { RemindersSettings(snackbar) }
            item {
                SettingsSection("Your data") {
                    SettingsStat("To-dos", "$tasks across ${snapshot.todoPages.size} page${if (snapshot.todoPages.size == 1) "" else "s"}")
                    SettingsStat("Notes & saves", "$notes in ${snapshot.notesPages.size} folder${if (snapshot.notesPages.size == 1) "" else "s"}")
                    SettingsStat("Events", events.toString())
                    SettingsStat("Habits & timers", tracked.toString())
                    SettingsStat("Images", formatBytes(imageBytes))
                }
            }
            item {
                SettingsSection("About") {
                    SettingsStat("Version", version.ifBlank { "—" })
                    SettingsStat("Storage", "On this device only")
                }
            }
        }
        // The app's snackbar sits under this page, so show messages here too.
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 12.dp))
    }
}

/** Live mini previews of each background; tap one to switch. */
@Composable
private fun BackdropPicker(selected: Backdrop, onSelect: (Backdrop) -> Unit) {
    val clock = rememberBackdropClock()
    LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(horizontal = 2.dp)) {
        items(Backdrop.entries) { scene ->
            val active = scene == selected
            val ring by animateColorAsState(if (active) CalendarBlue else Color(0x33FFFFFF), tween(220), label = "backdropRing")
            val lift by animateFloatAsState(if (active) 1f else .94f, spring(dampingRatio = Spring.DampingRatioMediumBouncy), label = "backdropLift")
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.scale(lift)) {
                Box(
                    Modifier.size(width = 84.dp, height = 140.dp)
                        .clip(RoundedCornerShape(18.dp))
                        .border(BorderStroke(if (active) 2.5.dp else 1.dp, ring), RoundedCornerShape(18.dp))
                        .clickable { onSelect(scene) }
                ) {
                    Canvas(Modifier.fillMaxSize()) { drawBackdrop(scene, if (scene == Backdrop.STILL) 0f else clock.value) }
                    if (active) Glyph(ZenIcon.Check, Color.White, 14.dp, modifier = Modifier.align(Alignment.TopEnd).padding(8.dp).clip(CircleShape).background(CalendarBlue).padding(horizontal = 6.dp, vertical = 1.dp))
                }
                Text(scene.label, color = if (active) TodoText else TodoMuted, fontSize = 12.sp, fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal, modifier = Modifier.padding(top = 6.dp))
            }
        }
    }
}

@Composable
internal fun SettingsSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column {
        Text(title.uppercase(), color = TodoMuted, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp, modifier = Modifier.padding(start = 6.dp, bottom = 6.dp))
        Surface(color = TodoCard, shape = RoundedCornerShape(20.dp), border = GlassStroke, modifier = Modifier.fillMaxWidth().glassBlur(RoundedCornerShape(20.dp))) {
            Column(Modifier.padding(vertical = 4.dp), content = content)
        }
    }
}

@Composable
internal fun SettingsRow(icon: ZenIcon, title: String, subtitle: String, enabled: Boolean = true, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp).graphicsLayer { alpha = if (enabled) 1f else .5f },
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.width(36.dp)) { Glyph(icon, TodoBlue, 20.dp) }
        Column(Modifier.weight(1f)) {
            Text(title, color = TodoText, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            Text(subtitle, color = TodoMuted, fontSize = 12.sp)
        }
        Glyph(ZenIcon.ChevronRight, TodoMuted, 17.dp)
    }
}

@Composable
private fun SettingsStat(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = TodoText, fontSize = 14.sp, modifier = Modifier.weight(1f))
        Text(value, color = TodoMuted, fontSize = 14.sp)
    }
}

@Composable
internal fun SettingsDivider() {
    Box(Modifier.fillMaxWidth().padding(start = 50.dp).height(1.dp).background(Color(0x1FFFFFFF)))
}
