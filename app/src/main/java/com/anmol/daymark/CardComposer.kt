package com.anmol.daymark

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.app.TimePickerDialog
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.TextRange
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.TextUnit
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale

data class CardDraft(
    val title: String,
    val details: String,
    val kind: CardKind,
    val columnId: String,
    val dueDay: Long?,
    val important: Boolean,
    val appChoice: AppChoice?,
    val deepLink: String,
    val dueMinute: Int? = null,
    val repeat: String = "",
    val remind: String = "",
    val remindLeadMinutes: Int = 0,
    val imagePath: String? = null, // a note's cover; null leaves it as it was
    val comment: String? = null,
    /** An event's length; null leaves it as it was (everything but calendar items). */
    val durationMinutes: Int? = null
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CardComposer(
    workspace: Workspace,
    columns: List<BoardColumn>,
    initialColumnId: String,
    selectedApp: AppChoice?,
    editing: BoardCard? = null,
    initialDay: Long? = null,
    onDismiss: () -> Unit,
    onPickApp: () -> Unit,
    onSave: (CardDraft) -> Unit,
    onVoice: () -> Unit
) {
    if (workspace == Workspace.NOTES) {
        // Notes get their own full-height editor; a title is optional there.
        NoteEditorSheet(columns, initialColumnId, editing, onDismiss) { title, details, columnId, cover, comment ->
            onSave(CardDraft(title, details, CardKind.NOTE, columnId, null, editing?.important ?: false, null, "", imagePath = cover, comment = comment))
        }
        return
    }
    val context = LocalContext.current
    var title by rememberSaveable(workspace.id, editing?.id) { mutableStateOf(editing?.title.orEmpty()) }
    var detailsValue by rememberSaveable(workspace.id, editing?.id, stateSaver = TextFieldValue.Saver) {
        mutableStateOf(editing?.details.orEmpty().let { TextFieldValue(it, TextRange(it.length)) })
    }
    val details = detailsValue.text
    var important by rememberSaveable(workspace.id, editing?.id) { mutableStateOf(editing?.important ?: false) }
    var dueDay by rememberSaveable(workspace.id, editing?.id) { mutableStateOf<Long?>(if (editing != null) editing.dueDay else if (workspace == Workspace.CALENDAR) initialDay ?: LocalDate.now().toEpochDay() else initialDay) }
    var kind by rememberSaveable(workspace.id) { mutableStateOf(if (workspace == Workspace.TRACK) CardKind.HABIT else kindFor(workspace)) }
    var columnId by rememberSaveable(workspace.id, initialColumnId, editing?.id) { mutableStateOf(initialColumnId.ifBlank { columns.firstOrNull()?.id.orEmpty() }) }
    var deepLink by rememberSaveable(workspace.id) { mutableStateOf("") }
    var dueMinute by rememberSaveable(workspace.id, editing?.id) { mutableStateOf<Int?>(editing?.dueMinute) }
    var repeat by rememberSaveable(workspace.id, editing?.id) { mutableStateOf(editing?.repeat.orEmpty()) }
    var repeatMenu by remember { mutableStateOf(false) }
    var showSchedule by remember { mutableStateOf(false) }
    // New dated items start with the default from Settings › Reminders; it only sticks if the item ends up with a date.
    var remind by rememberSaveable(workspace.id, editing?.id) { mutableStateOf(editing?.remind ?: if (workspace == Workspace.TODOS || workspace == Workspace.CALENDAR) Reminders.defaultType(context) else "") }
    var remindLead by rememberSaveable(workspace.id, editing?.id) { mutableStateOf(editing?.remindLeadMinutes ?: Reminders.defaultLead(context)) }
    var remindMenu by remember { mutableStateOf(false) }
    var notificationsBlocked by remember { mutableStateOf(false) }
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        notificationsBlocked = !granted
        if (!granted) android.widget.Toast.makeText(context, "Notifications are off, so reminders can’t show. Allow them in Settings › Reminders.", android.widget.Toast.LENGTH_LONG).show()
    }
    fun chooseRemind(type: String) {
        remind = type
        if (type.isBlank()) return
        if (Reminders.needsNotificationPermission(context)) notificationPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        else notificationsBlocked = !Reminders.notificationsAllowed(context)
        if (workspace == Workspace.TRACK) { if (dueMinute == null) dueMinute = DEFAULT_HABIT_REMINDER_MINUTE; return }
        // A time typed in the title ("call mum at 6pm") is what they want reminding about.
        if (dueDay != null && dueMinute == null) parseTimeFromTitle(title)?.let { dueMinute = it }
        if (dueDay == null) {
            // No date yet: suggest the next sensible slot (09:00 today, else the next full hour, else 09:00 tomorrow), then let them adjust.
            val now = LocalTime.now().let { it.hour * 60 + it.minute }
            val today = LocalDate.now().toEpochDay()
            val typed = dueMinute ?: parseTimeFromTitle(title)
            when {
                typed != null -> { dueMinute = typed; dueDay = today + if (typed <= now) 1 else 0 }
                now < DEFAULT_REMINDER_MINUTE -> { dueDay = today; dueMinute = DEFAULT_REMINDER_MINUTE }
                now < 23 * 60 -> { dueDay = today; dueMinute = (now / 60 + 1) * 60 }
                else -> { dueDay = today + 1; dueMinute = DEFAULT_REMINDER_MINUTE }
            }
            showSchedule = true
        }
    }
    var pickTime by remember { mutableStateOf(false) }
    var columnMenu by remember { mutableStateOf(false) }
    // Calendar items: how long they last, set with the start on the time gears.
    val isCalendar = workspace == Workspace.CALENDAR
    var duration by rememberSaveable(workspace.id, editing?.id) { mutableStateOf(editing?.durationMinutes?.takeIf { it > 0 } ?: 60) }
    var showTimes by remember { mutableStateOf(false) }
    // Editing an event from the phone's calendar: say whether the edit reaches Google Calendar, and offer to open it there.
    val reach by androidx.compose.runtime.produceState<CalendarSync.EditReach?>(null, editing?.id, isCalendar) {
        value = if (editing == null || !isCalendar || editing.kind != CardKind.EVENT) null
            else kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { CalendarSync.editReach(context, editing) }
    }
    var pendingSave by remember { mutableStateOf<(() -> Unit)?>(null) }
    val calendarPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        pendingSave?.invoke()
        pendingSave = null
    }

    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val canSave = title.isNotBlank() && columnId.isNotBlank()
    val save = {
        if (canSave) {
            var minute = dueMinute
            var day = dueDay
            val takesTime = workspace == Workspace.TODOS || workspace == Workspace.CALENDAR || (workspace == Workspace.TRACK && kind == CardKind.HABIT)
            val now = LocalTime.now().let { it.hour * 60 + it.minute }
            if (minute == null && takesTime) parseTimeFromTitle(title)?.let { found ->
                minute = found
                if (workspace != Workspace.TRACK && day == null) day = LocalDate.now().toEpochDay() + if (found <= now) 1 else 0
            }
            if (minute != null && day == null && workspace != Workspace.TRACK) day = LocalDate.now().toEpochDay()
            if (repeat.isNotBlank() && day == null) day = LocalDate.now().toEpochDay()
            val savedKind = kindFor(workspace, kind)
            // A reminder needs a date (or, for a habit, a daily time); without one it's dropped.
            val reminder = when {
                savedKind == CardKind.HABIT -> if (minute != null) remind else ""
                savedKind == CardKind.TASK || savedKind == CardKind.EVENT -> if (day != null) remind else ""
                else -> ""
            }
            val draft = CardDraft(title, details, savedKind, columnId, day, important, selectedApp, deepLink, minute, repeat, reminder, if (savedKind == CardKind.HABIT) 0 else remindLead,
                durationMinutes = if (isCalendar) (if (minute != null) duration else 0) else null)
            // Writing to the phone's calendar needs its permission first, or the edit would stay in Daymark only.
            if (reach == CalendarSync.EditReach.NEEDS_PERMISSION) {
                pendingSave = { onSave(draft) }
                calendarPermission.launch(arrayOf(android.Manifest.permission.READ_CALENDAR, android.Manifest.permission.WRITE_CALENDAR))
            } else onSave(draft)
        }
    }
    LaunchedEffect(Unit) {
        // An event opened from the calendar shows its times first; the keyboard comes up when the title is tapped.
        if (editing != null && isCalendar) return@LaunchedEffect
        runCatching { focus.requestFocus() }
        keyboard?.show()
    }

    // Quick-add sheet: type the title straight away, details and options underneath.
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = GlassSheet,
        dragHandle = null,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
    ) {
        Column(Modifier.fillMaxWidth().imePadding().navigationBarsPadding()) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(top = 22.dp, bottom = 12.dp)) {
                if (editing != null && isCalendar) EventSyncLine(editing, reach)
                SheetField(
                    value = title,
                    onValueChange = { title = it },
                    hint = titleHint(workspace, kind),
                    textSize = 22.sp,
                    modifier = Modifier.focusRequester(focus),
                    singleLine = true,
                    imeAction = ImeAction.Send,
                    onSend = { save() }
                )
                if (workspace == Workspace.CALENDAR || workspace == Workspace.TODOS) {
                    Spacer(Modifier.height(10.dp))
                    SheetValueField(
                        value = detailsValue,
                        onValueChange = { detailsValue = it },
                        hint = "Description",
                        textSize = 16.sp
                    )
                }
                if (workspace == Workspace.TRACK) {
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ChoicePill("Habit", kind == CardKind.HABIT) { kind = CardKind.HABIT; columnId = columns.firstOrNull()?.id.orEmpty() }
                        ChoicePill("Time tracker", kind == CardKind.TIMER) { kind = CardKind.TIMER; columnId = columns.getOrNull(1)?.id ?: columns.firstOrNull()?.id.orEmpty() }
                    }
                    if (kind == CardKind.TIMER) {
                        Spacer(Modifier.height(7.dp))
                        TextButton(onClick = onPickApp) { MetaLine(if (selectedApp == null) "Link an app to launch" else "${selectedApp.label} · Change", Green, 15.sp, leading = if (selectedApp == null) ZenIcon.Link else ZenIcon.ArrowUpRight) }
                        SheetField(value = deepLink, onValueChange = { deepLink = it }, hint = "Optional app link, e.g. hevy://…", textSize = 15.sp, singleLine = true)
                        Text("Starting a session launches the linked app. Add a deep link if the app supports opening a specific screen.", color = Muted, fontSize = 10.sp, lineHeight = 14.sp, modifier = Modifier.padding(top = 6.dp))
                    } else {
                        Text("Check in once a day. Your progress resets with each new day.", color = Muted, fontSize = 11.sp, modifier = Modifier.padding(top = 8.dp))
                        if (kind == CardKind.HABIT) {
                            Spacer(Modifier.height(10.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                SheetChip(dueMinute?.let { "Daily at ${formatClock(it)}" } ?: "Time", TodoText, ZenIcon.Clock) { pickTime = true }
                                Box {
                                    SheetChip(if (remind.isBlank()) "Remind" else "If not done", if (remind.isBlank()) TodoText else TodoBlue, reminderIcon(remind) ?: ZenIcon.Bell) { remindMenu = true }
                                    RemindMenu(remindMenu, remind, remindLead, showLeads = false, onDismiss = { remindMenu = false }, onType = { remindMenu = false; chooseRemind(it) }, onLead = {})
                                }
                            }
                            if (remind.isNotBlank() && notificationsBlocked) RemindWarning()
                            if (remind.isNotBlank()) ReminderSystemHints(remind)
                        }
                    }
                }
                if (workspace != Workspace.TRACK) {
                    Spacer(Modifier.height(14.dp))
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (workspace == Workspace.TODOS) {
                            val scheduled = listOfNotNull(dueDay?.let(::formatDue), dueMinute?.let(::formatClock)).joinToString(" · ")
                            SheetChip(if (scheduled.isEmpty()) "Schedule" else scheduled, if (scheduled.isEmpty()) TodoText else TodoBlue, ZenIcon.Calendar) { showSchedule = true }
                        }
                        if (isCalendar) {
                            SheetChip(dueDay?.let(::formatDue) ?: "Date", if (dueDay == null) TodoText else TodoBlue, ZenIcon.Calendar) { showSchedule = true }
                            val start = dueMinute
                            SheetChip(if (start == null) "All day" else "${formatClock(start)} – ${formatClock((start + duration) % (24 * 60))}", if (start == null) TodoText else TodoBlue, ZenIcon.Clock) { showTimes = true }
                        }
                        if ((workspace == Workspace.TODOS || workspace == Workspace.CALENDAR) && editing?.calendarSourceId.isNullOrBlank()) Box {
                            SheetChip(if (repeat.isBlank()) "Repeat" else Repeat.fromId(repeat).label, if (repeat.isBlank()) TodoText else TodoBlue, ZenIcon.Repeat) { repeatMenu = true }
                            DropdownMenu(expanded = repeatMenu, onDismissRequest = { repeatMenu = false }) {
                                Repeat.entries.forEach { rule ->
                                    DropdownMenuItem(text = { Text(if (rule == Repeat.NONE) "Don’t repeat" else rule.label) }, onClick = {
                                        repeat = rule.id
                                        if (rule != Repeat.NONE && dueDay == null) dueDay = LocalDate.now().toEpochDay()
                                        repeatMenu = false
                                    })
                                }
                            }
                        }
                        if (workspace == Workspace.TODOS || workspace == Workspace.CALENDAR) Box {
                            SheetChip(if (remind.isBlank()) "Remind" else leadLabel(remindLead), if (remind.isBlank()) TodoText else TodoBlue, reminderIcon(remind) ?: ZenIcon.Bell) { remindMenu = true }
                            RemindMenu(remindMenu, remind, remindLead, showLeads = true, onDismiss = { remindMenu = false }, onType = { remindMenu = false; chooseRemind(it) }, onLead = { remindLead = it; remindMenu = false })
                        }
                        if (workspace == Workspace.TODOS) SheetChip("Voice", TodoText, ZenIcon.Mic, onVoice)
                        SheetChip("Important", if (important) Amber else TodoText, if (important) ZenIcon.StarFilled else ZenIcon.Star) { important = !important }
                    }
                    if (isCalendar && dueMinute != null) Text("Lasts ${formatSpan(duration)}", color = TodoMuted, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
                    if (remind.isNotBlank() && (workspace == Workspace.TODOS || workspace == Workspace.CALENDAR)) {
                        if (notificationsBlocked) RemindWarning()
                        else if (dueDay != null && dueMinute == null && parseTimeFromTitle(title) == null) Text("No time set, so it reminds at ${formatClock(DEFAULT_REMINDER_MINUTE)}", color = TodoMuted, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
                        ReminderSystemHints(remind)
                    }
                }
            }
            HorizontalDivider(color = TodoBorder)
            Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) {
                    TextButton(onClick = { columnMenu = true }) {
                        Text(columns.firstOrNull { it.id == columnId }?.name ?: "Add a column", color = TodoText, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                        Glyph(ZenIcon.ChevronDown, TodoMuted, 14.dp, modifier = Modifier.padding(start = 6.dp))
                    }
                    DropdownMenu(expanded = columnMenu, onDismissRequest = { columnMenu = false }) {
                        columns.forEach { column -> DropdownMenuItem(text = { Text(column.name) }, onClick = { columnId = column.id; columnMenu = false }) }
                    }
                }
                Surface(
                    color = if (canSave) TodoBlue else TodoCardRaised,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.size(width = 56.dp, height = 44.dp).clickable(enabled = canSave) { save() }
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Glyph(ZenIcon.ArrowUp, if (canSave) OnAccent else TodoMuted, 18.dp)
                    }
                }
            }
        }
    }
    LaunchedEffect(pickTime) {
        if (pickTime) {
            val initial = dueMinute ?: LocalTime.now().let { it.hour * 60 + it.minute }
            TimePickerDialog(context, { _, hour, minute ->
                dueMinute = hour * 60 + minute
                if (workspace != Workspace.TRACK && dueDay == null) dueDay = LocalDate.now().toEpochDay()
                pickTime = false
            }, initial / 60, initial % 60, false)
                .apply { setOnCancelListener { pickTime = false } }.show()
        }
    }
    if (showSchedule) {
        SchedulePicker(
            initialDay = dueDay,
            initialMinute = dueMinute,
            showTime = !isCalendar,
            onDismiss = { showSchedule = false },
            onDone = { day, minute ->
                dueDay = day
                dueMinute = minute
                if (day == null) { repeat = ""; remind = "" }
                if (workspace == Workspace.CALENDAR && day != null) columnId = calendarColumnFor(columns, day, columnId)
                showSchedule = false
            }
        )
    }
    if (showTimes) {
        EventTimeSheet(dueMinute, duration, onDismiss = { showTimes = false }, onDone = { start, length ->
            dueMinute = start
            if (start != null) duration = length
            if (dueDay == null) dueDay = LocalDate.now().toEpochDay()
            showTimes = false
        })
    }
}

/** At the top of an event being edited: where the edit goes, and a way to open the event in the calendar app. */
@Composable
private fun EventSyncLine(event: BoardCard, reach: CalendarSync.EditReach?) {
    val context = LocalContext.current
    val linked = CalendarSync.phoneEvent(event) != null
    val (text, color) = when (reach) {
        CalendarSync.EditReach.SYNCS -> "Changes sync to Google Calendar" to TodoBlue
        CalendarSync.EditReach.NEEDS_PERMISSION -> "Saving asks for calendar access, to sync the change" to Amber
        CalendarSync.EditReach.REPEATING -> "Repeating event: changes stay in Daymark. Edit the series in Google Calendar" to Amber
        CalendarSync.EditReach.READ_ONLY -> "Read-only calendar: changes stay in Daymark" to Amber
        CalendarSync.EditReach.DAYMARK_ONLY -> "Only in Daymark. Choose a sync calendar in Settings › Calendar" to TodoMuted
        null -> "" to TodoMuted
    }
    Row(Modifier.fillMaxWidth().padding(bottom = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, color = color, fontSize = 12.sp, lineHeight = 16.sp, modifier = Modifier.weight(1f).padding(end = 8.dp))
        if (linked) SheetChip("Open in Calendar", TodoText, ZenIcon.ArrowUpRight) {
            if (!CalendarSync.openInCalendarApp(context, event)) android.widget.Toast.makeText(context, "No calendar app could open this event", android.widget.Toast.LENGTH_SHORT).show()
        }
    }
}

internal fun calendarColumnFor(columns: List<BoardColumn>, day: Long, current: String): String {
    val offset = day - LocalDate.now().toEpochDay()
    val name = when {
        offset < 0 -> "Past"
        offset == 0L -> "Today"
        offset <= 7 -> "This week"
        else -> "Later"
    }
    return columns.firstOrNull { it.name.equals(name, true) }?.id ?: current
}

/** Date and time in one sheet: quick picks, a month grid, then an optional time. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SchedulePicker(initialDay: Long?, initialMinute: Int?, showTime: Boolean = true, onDismiss: () -> Unit, onDone: (Long?, Int?) -> Unit) {
    val context = LocalContext.current
    val today = LocalDate.now()
    var day by remember { mutableStateOf(initialDay) }
    var minute by remember { mutableStateOf(initialMinute) }
    var month by remember { mutableStateOf(YearMonth.from(LocalDate.ofEpochDay(initialDay ?: today.toEpochDay()))) }
    var pickTime by remember { mutableStateOf(false) }
    val firstCell = month.atDay(1).minusDays((month.atDay(1).dayOfWeek.value % 7).toLong())
    // Next Monday, unless that is just tomorrow, in which case it would duplicate the option above.
    val nextMonday = today.with(java.time.temporal.TemporalAdjusters.next(java.time.DayOfWeek.MONDAY)).let { if (it == today.plusDays(1)) it.plusWeeks(1) else it }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = GlassSheet.copy(alpha = 1f),
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
    ) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().verticalScroll(rememberScrollState())) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Schedule", color = TodoMuted, fontSize = 17.sp, modifier = Modifier.weight(1f))
                SheetChip("Clear", TodoText, ZenIcon.Close) { onDone(null, null) }
                Spacer(Modifier.width(8.dp))
                Surface(color = TodoBlue, shape = RoundedCornerShape(12.dp), modifier = Modifier.clip(RoundedCornerShape(12.dp)).clickable {
                    onDone(day ?: if (minute != null) today.toEpochDay() else null, minute)
                }) {
                    Text("Done", color = OnAccent, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp))
                }
            }
            listOf(
                Triple("Today", today, today.format(DateTimeFormatter.ofPattern("EEE", Locale.getDefault()))),
                Triple("Tomorrow", today.plusDays(1), today.plusDays(1).format(DateTimeFormatter.ofPattern("EEE", Locale.getDefault()))),
                Triple("Next week", nextMonday, nextMonday.format(DateTimeFormatter.ofPattern("EEE d MMM", Locale.getDefault())))
            ).forEach { (label, date, hint) ->
                Row(
                    Modifier.fillMaxWidth().clickable { day = date.toEpochDay(); month = YearMonth.from(date) }.padding(horizontal = 20.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Glyph(ZenIcon.Calendar, if (day == date.toEpochDay()) TodoBlue else TodoMuted, 20.dp, modifier = Modifier.padding(end = 20.dp))
                    Text(label, color = TodoText, fontSize = 18.sp, modifier = Modifier.weight(1f))
                    Text(hint, color = TodoMuted, fontSize = 14.sp)
                }
            }
            HorizontalDivider(color = TodoBorder)
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(month.format(DateTimeFormatter.ofPattern("MMMM yyyy", Locale.getDefault())), color = TodoText, fontSize = 18.sp, modifier = Modifier.weight(1f))
                listOf(ZenIcon.ChevronLeft to -1L, ZenIcon.ChevronRight to 1L).forEach { (arrow, step) ->
                    Surface(color = TodoCardRaised, shape = CircleShape, modifier = Modifier.padding(start = 10.dp).size(42.dp).clip(CircleShape).clickable { month = month.plusMonths(step) }) {
                        Box(contentAlignment = Alignment.Center) { Glyph(arrow, TodoText, 18.dp) }
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
                listOf("S", "M", "T", "W", "T", "F", "S").forEach {
                    Text(it, color = TodoMuted, fontSize = 15.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center, modifier = Modifier.weight(1f).padding(vertical = 6.dp))
                }
            }
            (0 until 6).forEach { week ->
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
                    (0 until 7).forEach { weekday ->
                        val date = firstCell.plusDays((week * 7 + weekday).toLong())
                        val selected = date.toEpochDay() == day
                        val isToday = date == today
                        Box(Modifier.weight(1f).height(46.dp).clickable { day = date.toEpochDay() }, contentAlignment = Alignment.Center) {
                            Box(
                                Modifier.size(40.dp).clip(CircleShape)
                                    .background(if (selected) CalendarBlue else Color.Transparent)
                                    .then(if (isToday && !selected) Modifier.border(2.dp, TodoText, CircleShape) else Modifier),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    date.dayOfMonth.toString(),
                                    color = when { selected -> OnFill; YearMonth.from(date) == month -> TodoText; else -> TodoMuted.copy(alpha = .55f) },
                                    fontSize = 16.sp
                                )
                            }
                        }
                    }
                }
            }
            if (showTime) {
            HorizontalDivider(color = TodoBorder, modifier = Modifier.padding(top = 8.dp))
            Row(Modifier.fillMaxWidth().clickable { pickTime = true }.padding(horizontal = 20.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Glyph(ZenIcon.Clock, if (minute != null) TodoBlue else TodoMuted, 20.dp, modifier = Modifier.padding(end = 20.dp))
                Text(minute?.let(::formatClock) ?: "Time", color = TodoText, fontSize = 18.sp, modifier = Modifier.weight(1f))
                if (minute != null) Glyph(ZenIcon.Close, TodoMuted, 24.dp, modifier = Modifier.clickable { minute = null }.padding(horizontal = 8.dp))
            }
            }
            Spacer(Modifier.height(12.dp))
        }
    }
    LaunchedEffect(pickTime) {
        if (pickTime) {
            val initial = minute ?: LocalTime.now().let { it.hour * 60 + it.minute }
            TimePickerDialog(context, { _, hour, min ->
                minute = hour * 60 + min
                pickTime = false
            }, initial / 60, initial % 60, false)
                .apply { setOnCancelListener { pickTime = false } }.show()
        }
    }
}

@Composable
private fun SheetValueField(value: TextFieldValue, onValueChange: (TextFieldValue) -> Unit, hint: String, textSize: TextUnit, modifier: Modifier = Modifier) {
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        textStyle = TextStyle(color = TodoText, fontSize = textSize),
        cursorBrush = SolidColor(TodoBlue),
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
        modifier = modifier.fillMaxWidth(),
        decorationBox = { field ->
            Box {
                if (value.text.isEmpty()) Text(hint, color = TodoMuted, fontSize = textSize)
                field()
            }
        }
    )
}

@Composable
internal fun SheetField(
    value: String,
    onValueChange: (String) -> Unit,
    hint: String,
    textSize: TextUnit,
    modifier: Modifier = Modifier,
    singleLine: Boolean = true,
    imeAction: ImeAction = ImeAction.Default,
    onSend: () -> Unit = {}
) {
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = singleLine,
        textStyle = TextStyle(color = TodoText, fontSize = textSize),
        cursorBrush = SolidColor(TodoBlue),
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = imeAction),
        keyboardActions = KeyboardActions(onSend = { onSend() }),
        modifier = modifier.fillMaxWidth(),
        decorationBox = { field ->
            Box {
                if (value.isEmpty()) Text(hint, color = TodoMuted, fontSize = textSize)
                field()
            }
        }
    )
}

/** Off / Notification / Alarm, then (for dated items) how long before. */
@Composable
internal fun RemindMenu(expanded: Boolean, remind: String, lead: Int, showLeads: Boolean, onDismiss: () -> Unit, onType: (String) -> Unit, onLead: (Int) -> Unit) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        listOf("" to "Off", REMIND_NOTIFY to "Notification", REMIND_ALARM to "Alarm").forEach { (type, label) ->
            DropdownMenuItem(text = { Text(label) }, leadingIcon = { Glyph(reminderIcon(type) ?: ZenIcon.BellOff, TodoMuted, 18.dp) }, trailingIcon = if (type == remind) ({ Glyph(ZenIcon.Check, TodoBlue, 16.dp) }) else null, onClick = { onType(type) })
        }
        if (showLeads && remind.isNotBlank()) {
            HorizontalDivider(color = TodoBorder)
            REMINDER_LEADS.forEach { minutes -> DropdownMenuItem(text = { Text(leadLabel(minutes)) }, trailingIcon = if (minutes == lead) ({ Glyph(ZenIcon.Check, TodoBlue, 16.dp) }) else null, onClick = { onLead(minutes) }) }
        }
    }
}

@Composable
internal fun RemindWarning() {
    Text("Notifications are blocked, so this reminder can’t show. Allow them in Settings › Reminders.", color = Amber, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
}

@Composable
internal fun SheetChip(label: String, color: Color, icon: ZenIcon, onClick: () -> Unit) {
    Surface(color = veil(0.06f), shape = RoundedCornerShape(14.dp), border = GlassStroke, modifier = Modifier.clip(RoundedCornerShape(14.dp)).clickable(onClick = onClick)) {
        MetaLine(label, color, 14.sp, Modifier.padding(horizontal = 13.dp, vertical = 10.dp), leading = icon, unbounded = true)
    }
}

@Composable
internal fun ChoicePill(label: String, selected: Boolean, onClick: () -> Unit) {
    Surface(color = if (selected) PaleGreen else TodoCard, shape = RoundedCornerShape(12.dp), border = GlassStroke, modifier = Modifier.glassBlur(RoundedCornerShape(12.dp)).clickable(onClick = onClick)) {
        Text(label, color = if (selected) Green else Muted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 13.dp, vertical = 9.dp))
    }
}

private fun kindFor(workspace: Workspace, chosen: CardKind = CardKind.TASK) = when (workspace) {
    Workspace.TODOS -> CardKind.TASK
    Workspace.CALENDAR -> CardKind.EVENT
    Workspace.NOTES -> CardKind.NOTE
    Workspace.TRACK -> chosen
}

private fun composerTitle(workspace: Workspace, kind: CardKind) = when (workspace) {
    Workspace.TODOS -> "New task"
    Workspace.CALENDAR -> "New calendar item"
    Workspace.NOTES -> "New note"
    Workspace.TRACK -> if (kind == CardKind.HABIT) "New habit" else "New time tracker"
}

private fun titleHint(workspace: Workspace, kind: CardKind) = when (workspace) {
    Workspace.TODOS -> "What do you need to do?"
    Workspace.CALENDAR -> "What’s happening?"
    Workspace.NOTES -> "Give this note a title"
    Workspace.TRACK -> if (kind == CardKind.HABIT) "e.g. Read for 20 minutes" else "e.g. Workout"
}
