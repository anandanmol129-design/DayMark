package com.anmol.daymark

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.runtime.produceState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.layout.navigationBarsPadding
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
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.ui.graphics.graphicsLayer
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
    onShareBackup: () -> Unit,
    onRestore: () -> Unit,
    onImportCalendar: () -> Unit,
    onOpenStorage: () -> Unit
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
                drawRect(PageTint)
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
                SettingsSection("Appearance") {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.width(36.dp)) { Glyph(ZenIcon.Sun, TodoBlue, 20.dp) }
                        Text("Theme", color = TodoText, fontSize = 15.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                        PillChoice(listOf(true to "Light", false to "Dark"), Look.light) { Look.setLight(context, it) }
                    }
                }
            }
            item {
                SettingsSection("Backup") {
                    SettingsRow(ZenIcon.Upload, "Back up now", backedUp, enabled = !busy, onClick = onBackup)
                    SettingsDivider()
                    SettingsRow(ZenIcon.Share, "Share backup", "Send a copy to Drive, email or any app", enabled = !busy, onClick = onShareBackup)
                    SettingsDivider()
                    SettingsRow(ZenIcon.Restore, "Restore from backup", "Replaces everything with a backup file", enabled = !busy, onClick = onRestore)
                }
                Text(
                    if (busy) "Working…" else "Backups are a single .zip with all your pages, tasks, notes, pictures and files. Keep one somewhere safe, like Drive or Downloads.",
                    color = TodoMuted, fontSize = 12.sp, modifier = Modifier.padding(start = 6.dp, end = 6.dp, top = 8.dp)
                )
            }
            item { AutoBackupSettings(snapshot, snackbar) }
            item {
                SettingsSection("Storage") {
                    SettingsRow(ZenIcon.HardDrive, "Manage storage", "${formatBytes(imageBytes)} of pictures and files · free up space", onClick = onOpenStorage)
                }
            }
            item {
                SettingsSection("Calendar") {
                    SettingsRow(ZenIcon.Calendar, "Calendars & sync", "Choose calendars to show, and two-way sync", onClick = onImportCalendar)
                }
            }
            item { RemindersSettings(snackbar) }
            item {
                SettingsSection("Your data") {
                    SettingsStat("To-dos", "$tasks across ${snapshot.todoPages.size} page${if (snapshot.todoPages.size == 1) "" else "s"}")
                    SettingsStat("Notes & saves", "$notes in ${snapshot.notesPages.size} folder${if (snapshot.notesPages.size == 1) "" else "s"}")
                    SettingsStat("Events", events.toString())
                    SettingsStat("Habits & timers", tracked.toString())
                    SettingsStat("Pictures & files", formatBytes(imageBytes))
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

/** A row of pill options with the chosen one filled, like "Off · Daily · Weekly". */
@Composable
private fun <T> PillChoice(options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit) {
    Row(Modifier.clip(RoundedCornerShape(50)).background(TodoCardRaised).padding(3.dp)) {
        options.forEach { (value, label) ->
            val active = value == selected
            Text(
                label, color = if (active) OnAccent else TodoMuted, fontSize = 13.sp, fontWeight = FontWeight.Medium,
                modifier = Modifier.clip(RoundedCornerShape(50)).background(if (active) TodoBlue else Color.Transparent)
                    .clickable { onSelect(value) }
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            )
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
    Box(Modifier.fillMaxWidth().padding(start = 50.dp).height(1.dp).background(veil(0.12f)))
}

/** Settings › Automatic backup: a folder to write to, how often, and how the last run went. */
@Composable
private fun AutoBackupSettings(snapshot: BoardSnapshot, snackbar: SnackbarHostState) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var refresh by remember { mutableIntStateOf(0) }
    var every by remember { mutableIntStateOf(AutoBackup.everyDays(context)) }
    var running by remember { mutableStateOf(false) }
    val folderName by produceState<String?>(null, refresh) { value = withContext(Dispatchers.IO) { AutoBackup.folderName(context) } }
    val hasFolder = remember(refresh) { AutoBackup.folder(context) != null }
    val last = remember(refresh) { AutoBackup.lastMillis(context) }
    val error = remember(refresh) { AutoBackup.lastError(context) }

    fun message(text: String) = scope.launch { snackbar.currentSnackbarData?.dismiss(); snackbar.showSnackbar(text) }
    fun backUpNow() {
        running = true
        val current = snapshot
        scope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { AutoBackup.runNow(context, current) } }
            running = false
            refresh++
            result.onSuccess { message("Saved $it") }.onFailure { message(it.message ?: "Backup failed") }
        }
    }
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching { AutoBackup.setFolder(context, uri) }
            .onSuccess { every = AutoBackup.everyDays(context); refresh++; backUpNow() }
            .onFailure { message("Daymark can’t write to that folder. Try another one.") }
    }

    SettingsSection("Automatic backup") {
        SettingsRow(
            ZenIcon.Folder, if (hasFolder) "Backup folder" else "Choose a backup folder",
            when {
                !hasFolder -> "On your phone, SD card or a cloud folder"
                folderName != null -> folderName!!
                else -> "Folder not available · choose it again"
            },
            onClick = { pickFolder.launch(null) }
        )
        SettingsDivider()
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(36.dp)) { Glyph(ZenIcon.Repeat, TodoBlue, 20.dp) }
            Text("How often", color = TodoText, fontSize = 15.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
            PillChoice(listOf(0 to "Off", 1 to "Daily", 7 to "Weekly"), every) { days ->
                if (days != 0 && !hasFolder) { pickFolder.launch(null); return@PillChoice }
                every = days
                AutoBackup.setEveryDays(context, days)
                refresh++
            }
        }
        if (hasFolder) {
            SettingsDivider()
            SettingsRow(ZenIcon.Upload, "Back up to folder now", if (running) "Working…" else "Writes today’s backup there", enabled = !running, onClick = ::backUpNow)
        }
    }
    val status = when {
        error != null -> error
        every == 0 && hasFolder -> "Automatic backup is off. Choose Daily or Weekly to turn it on."
        every == 0 || !hasFolder -> "Pick a folder and Daymark backs up on its own, keeping the latest ${AutoBackup.KEEP} backups there and removing older ones."
        last == 0L -> "No automatic backup yet. Keeps the latest ${AutoBackup.KEEP}."
        else -> "Last automatic backup " + java.time.Instant.ofEpochMilli(last).atZone(java.time.ZoneId.systemDefault())
            .format(DateTimeFormatter.ofPattern("d MMM, h:mm a", Locale.getDefault())) + " · keeps the latest ${AutoBackup.KEEP}"
    }
    Text(status, color = if (error != null) Danger else TodoMuted, fontSize = 12.sp, modifier = Modifier.padding(start = 6.dp, end = 6.dp, top = 8.dp))
}
