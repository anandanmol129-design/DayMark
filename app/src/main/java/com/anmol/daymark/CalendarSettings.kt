package com.anmol.daymark

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Settings › Calendar: which phone calendars show in Daymark (grouped by account), the one calendar Daymark's own
 * events are copied to, and whether timed to-dos go along. Reads and writes [CalendarSync]'s settings.
 */
@Composable
internal fun CalendarSettingsScreen(snapshot: BoardSnapshot, snackbar: SnackbarHostState, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var canRead by remember { mutableStateOf(CalendarSync.canRead(context)) }
    var calendars by remember { mutableStateOf<List<DeviceCalendar>?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    var checked by remember { mutableStateOf(emptySet<Long>()) }
    var target by remember { mutableLongStateOf(CalendarSync.target(context)) }
    var todos by remember { mutableStateOf(CalendarSync.includeTodos(context)) }
    var pendingTarget by remember { mutableStateOf<Long?>(null) }

    fun message(text: String) = scope.launch { snackbar.currentSnackbarData?.dismiss(); snackbar.showSnackbar(text) }

    LaunchedEffect(canRead, reload) {
        if (!canRead) return@LaunchedEffect
        val list = withContext(Dispatchers.IO) { CalendarSync.calendars(context) }.orEmpty()
        calendars = list
        // First visit: keep what an earlier import did (every calendar) or start with none ticked.
        val ids = CalendarSync.checkedIds(context, snapshot) ?: list.map { it.id }.toSet()
        if (!CalendarSync.configured(context)) CalendarSync.setChecked(context, ids)
        checked = ids
        // The sync calendar vanished or lost write access: show it as off.
        if (target != CalendarSync.OFF && list.none { it.id == target && it.writable }) target = CalendarSync.OFF
    }

    val readPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        canRead = granted
        if (!granted) message("Calendar access is off, so Daymark can’t see your calendars")
    }
    val syncPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        val wanted = pendingTarget
        pendingTarget = null
        canRead = CalendarSync.canRead(context)
        if (CalendarSync.canRead(context) && CalendarSync.canWrite(context) && wanted != null) {
            target = wanted; CalendarSync.setTarget(context, wanted)
            message("Syncing Daymark events")
            reload++
        } else if (result.isNotEmpty()) message("Sync needs calendar access · it stays off")
    }

    fun toggleCalendar(id: Long) {
        checked = if (id in checked) checked - id else checked + id
        CalendarSync.setChecked(context, checked)
        CalendarSync.syncNow(context, manual = false, force = true)
    }
    fun chooseTarget(id: Long) {
        if (id == CalendarSync.OFF) {
            target = id; CalendarSync.setTarget(context, id)
            message("Sync off · Daymark’s copies are removed from that calendar")
        } else if (CalendarSync.canRead(context) && CalendarSync.canWrite(context)) {
            target = id; CalendarSync.setTarget(context, id)
            message("Syncing Daymark events")
        } else {
            pendingTarget = id
            syncPermission.launch(arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR))
        }
    }

    val blurred = LocalBackdrop.current
    Box(
        Modifier.fillMaxSize()
            .drawBehind {
                drawRect(TodoBackground)
                blurred?.let { drawBlurred(it) }
                drawRect(Color(0x590B1226))
            }
            .pointerInput(Unit) { detectTapGestures { } }
            .statusBarsPadding()
    ) {
        val list = calendars.orEmpty()
        val accounts = list.groupBy { it.accountName to it.accountType }.entries.sortedWith(compareBy({ it.key.second != "com.google" }, { it.key.first.lowercase() }))
        val writable = list.filter { it.writable }.sortedWith(compareBy({ !it.isGoogle }, { it.accountName.lowercase() }, { it.name.lowercase() }))
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 40.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item {
                Row(Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Glyph(ZenIcon.ChevronLeft, TodoText, 24.dp, modifier = Modifier.clip(CircleShape).clickable(onClick = onBack).padding(horizontal = 12.dp, vertical = 2.dp))
                    Text("Calendar", color = TodoText, fontSize = 26.sp, fontWeight = FontWeight.SemiBold)
                }
            }
            if (!canRead) {
                item {
                    SettingsSection("Access") {
                        SettingsRow(ZenIcon.Alert, "Allow calendar access", "Needed to show your phone’s calendars") { readPermission.launch(Manifest.permission.READ_CALENDAR) }
                    }
                    Footnote("Daymark reads and writes calendars through Android itself. Google calendars on this phone sync with Google Calendar on their own.")
                }
            } else {
                if (calendars != null && list.isEmpty()) item { Footnote("No calendars on this phone yet. Add an account in system settings, then come back.") }
                if (list.isNotEmpty()) item {
                    Text("SHOW IN DAYMARK", color = TodoMuted, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp, modifier = Modifier.padding(start = 6.dp))
                }
                items(accounts, key = { "${it.key.first}|${it.key.second}" }) { (account, cals) ->
                    SettingsSection(account.first.ifBlank { accountLabel(account.second) }) {
                        cals.sortedBy { it.name.lowercase() }.forEachIndexed { index, cal ->
                            if (index > 0) SettingsDivider()
                            CalendarChoiceRow(cal, subtitle = buildList {
                                add(accountLabel(cal.accountType))
                                if (!cal.writable) add("read-only")
                                if (!cal.visible) add("hidden on phone")
                            }.joinToString(" · "), selected = cal.id in checked, radio = false) { toggleCalendar(cal.id) }
                        }
                    }
                }
                item {
                    SettingsSection("Sync Daymark events to") {
                        OffRow(selected = target == CalendarSync.OFF) { chooseTarget(CalendarSync.OFF) }
                        writable.forEach { cal ->
                            SettingsDivider()
                            CalendarChoiceRow(cal, subtitle = cal.accountName.ifBlank { accountLabel(cal.accountType) }, selected = cal.id == target, radio = true) { chooseTarget(cal.id) }
                        }
                    }
                    Footnote("Events you make in Daymark are copied to this calendar and kept up to date; deleting one in Daymark deletes the copy. Daymark’s own reminders stay in Daymark, so you won’t get two alerts.")
                }
                if (target != CalendarSync.OFF) item {
                    SettingsSection("Also sync") {
                        CheckRow("Include to-dos with a time", "Timed to-dos appear in your calendar too; ticking one off keeps it there", todos) {
                            todos = !todos; CalendarSync.setIncludeTodos(context, todos)
                        }
                    }
                }
                item {
                    SettingsSection("Import") {
                        SettingsRow(ZenIcon.Download, "Sync now", "Daymark also checks whenever you open it") {
                            if (checked.isEmpty() && target == CalendarSync.OFF) message("Tick a calendar above first")
                            else CalendarSync.syncNow(context, manual = true) { message(it) }
                        }
                    }
                    Footnote("Edits to imported events go back to their calendar when it’s writable and the event doesn’t repeat; repeating events stay as they are on the phone. Deleting an imported event only hides it in Daymark. Unticking a calendar removes its events from Daymark.")
                }
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 12.dp))
    }
}

private fun accountLabel(type: String) = when (type) {
    "com.google" -> "Google"
    CalendarContractLocal -> "On this phone"
    "com.android.exchange", "com.google.android.gm.exchange" -> "Exchange"
    "com.samsung.android.exchange" -> "Exchange"
    else -> type.substringAfterLast('.').replaceFirstChar { it.uppercase() }.ifBlank { "Calendar" }
}

private const val CalendarContractLocal = android.provider.CalendarContract.ACCOUNT_TYPE_LOCAL

@Composable
private fun Footnote(text: String) {
    Text(text, color = TodoMuted, fontSize = 12.sp, modifier = Modifier.padding(start = 6.dp, end = 6.dp, top = 8.dp))
}

@Composable
private fun CalendarChoiceRow(cal: DeviceCalendar, subtitle: String, selected: Boolean, radio: Boolean, onClick: () -> Unit) {
    val dot = if (cal.color == 0) TodoBlue else Color(cal.color.toLong() or 0xFF000000L)
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(36.dp)) { Box(Modifier.size(14.dp).clip(CircleShape).background(dot)) }
        Column(Modifier.weight(1f)) {
            Text(cal.name, color = TodoText, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, color = TodoMuted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (radio) RadioMark(selected) else CheckMark(selected)
    }
}

@Composable
private fun OffRow(selected: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(36.dp)) { Glyph(ZenIcon.Close, TodoBlue, 18.dp) }
        Column(Modifier.weight(1f)) {
            Text("Off", color = TodoText, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            Text("Daymark’s events stay in Daymark", color = TodoMuted, fontSize = 12.sp)
        }
        RadioMark(selected)
    }
}

@Composable
private fun CheckRow(title: String, subtitle: String, on: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(36.dp)) { Glyph(ZenIcon.Checkbox, TodoBlue, 20.dp) }
        Column(Modifier.weight(1f)) {
            Text(title, color = TodoText, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            Text(subtitle, color = TodoMuted, fontSize = 12.sp)
        }
        CheckMark(on)
    }
}

@Composable
private fun CheckMark(on: Boolean) {
    val shape = RoundedCornerShape(6.dp)
    Box(
        Modifier.size(22.dp).clip(shape).background(if (on) CalendarBlue else Color.Transparent).border(1.5.dp, if (on) CalendarBlue else TodoMuted, shape),
        contentAlignment = Alignment.Center
    ) { if (on) Glyph(ZenIcon.Check, Color.White, 14.dp) }
}

@Composable
private fun RadioMark(on: Boolean) {
    Box(Modifier.size(22.dp).clip(CircleShape).border(1.5.dp, if (on) CalendarBlue else TodoMuted, CircleShape), contentAlignment = Alignment.Center) {
        if (on) Box(Modifier.size(12.dp).clip(CircleShape).background(CalendarBlue))
    }
}
