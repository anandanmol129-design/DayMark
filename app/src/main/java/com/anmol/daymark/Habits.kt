package com.anmol.daymark

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import android.app.TimePickerDialog
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.LocalDate
import kotlinx.coroutines.launch
import java.time.LocalTime
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Colours a habit can wear, in the app and on its widgets. Bright enough to read as "done" on the dark glass. */
internal val HabitPalette = listOf(
    0xFF7FC29B, // sage green
    0xFF6FA8DC, // blue
    0xFF5EC4C0, // teal
    0xFFA48BDB, // violet
    0xFFE48AB8, // pink
    0xFFE57E6E, // coral
    0xFFE9A35E, // orange
    0xFFE2C85C  // yellow
)
internal val HabitColorNames = listOf("Green", "Blue", "Teal", "Violet", "Pink", "Coral", "Orange", "Yellow")

internal fun habitColorIndex(card: BoardCard) = if (card.habitColor in HabitPalette.indices) card.habitColor else Math.floorMod(card.id.hashCode(), HabitPalette.size)
internal fun habitArgb(card: BoardCard): Int = HabitPalette[habitColorIndex(card)].toInt()
internal fun habitColor(card: BoardCard) = Color(habitArgb(card))

internal val HABIT_DURATIONS = listOf(0, 5, 10, 15, 20, 30, 45, 60, 90, 120)
internal fun durationLabel(minutes: Int) = when {
    minutes <= 0 -> "No duration"
    minutes % 60 == 0 -> "${minutes / 60} h"
    minutes > 60 -> "${minutes / 60} h ${minutes % 60} min"
    else -> "$minutes min"
}

/** "Event · 7:00 AM · 30 min" — what kind of habit it is and when. */
internal fun habitSummary(card: BoardCard): String = listOfNotNull(
    if (card.habitEvent) "Event" else "Task",
    card.dueMinute?.let(::formatClock) ?: if (card.habitEvent) "Any time" else null,
    card.durationMinutes.takeIf { it > 0 }?.let(::durationLabel)
).joinToString(" · ")

/** Check-ins on or after the first of [month], up to its end. */
internal fun checkInsIn(checkIns: Collection<Long>, from: Long, to: Long) = checkIns.count { it in from..to }

/**
 * The Habits tab: Focus (pomodoro timers and time tracking) on the first page, where it opens, and
 * habits a swipe away on the second.
 */
@Composable
internal fun HabitsScreen(
    habits: List<BoardCard>,
    onToggleDay: (BoardCard, Long) -> Unit,
    onEdit: (BoardCard) -> Unit,
    onDelete: (BoardCard) -> Unit,
    onAdd: () -> Unit,
    trackers: List<BoardCard>,
    page: Int,
    onPageChange: (Int) -> Unit,
    onStartFocus: (BoardCard, Int) -> Unit,
    onSetFocusMinutes: (BoardCard, Int) -> Unit,
    onStopFocus: () -> Unit,
    onDeleteTracker: (BoardCard) -> Unit
) {
    val pager = rememberPagerState(initialPage = page.coerceIn(0, 1)) { 2 }
    val scope = rememberCoroutineScope()
    LaunchedEffect(pager.currentPage) { onPageChange(pager.currentPage) }
    // A notification tap asks for the Focus page while the tab is already open.
    LaunchedEffect(page) { if (page != pager.currentPage && !pager.isScrollInProgress) pager.animateScrollToPage(page.coerceIn(0, 1), animationSpec = PageSpring) }
    Column(Modifier.fillMaxSize()) {
        HorizontalPager(pager, Modifier.weight(1f).fillMaxWidth(), flingBehavior = rubberFling(pager), pageSpacing = 16.dp, beyondViewportPageCount = 1) { index ->
            if (index == 0) FocusPage(trackers, onStartFocus, onSetFocusMinutes, onStopFocus, onDeleteTracker, Modifier.fillMaxSize().bounce(pager, index))
            else HabitsPage(habits, onToggleDay, onEdit, onDelete, onAdd, Modifier.fillMaxSize().bounce(pager, index))
        }
        Row(Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 8.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            (0..1).forEach { index -> PagerDot(pager.currentPage == index) { scope.launch { pager.animateScrollToPage(index, animationSpec = PageSpring) } } }
        }
    }
}

/** Each habit with its streak, today's check and a month or year of coloured squares. */
@Composable
private fun HabitsPage(
    habits: List<BoardCard>,
    onToggleDay: (BoardCard, Long) -> Unit,
    onEdit: (BoardCard) -> Unit,
    onDelete: (BoardCard) -> Unit,
    onAdd: () -> Unit,
    modifier: Modifier
) {
    val today = LocalDate.now().toEpochDay()
    // Month and year are two pages side by side: swipe between them, or tap the toggle.
    var savedPage by rememberSaveable { mutableIntStateOf(0) }
    val pager = rememberPagerState(initialPage = savedPage) { 2 }
    val scope = rememberCoroutineScope()
    LaunchedEffect(pager.currentPage) { savedPage = pager.currentPage }
    val done = habits.count { it.checkedOn(today) }
    Column(modifier) {
        Column(Modifier.fillMaxWidth().padding(start = 22.dp, end = 16.dp, top = 14.dp, bottom = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(todayLabel(), color = TodoMuted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp, modifier = Modifier.weight(1f))
                SettingsButton()
            }
            Text("Habits", color = TodoText, fontSize = 34.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 6.dp))
            Row(Modifier.fillMaxWidth().padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    when { habits.isEmpty() -> "Small things, every day"; done == habits.size -> "All done today"; else -> "$done of ${habits.size} done today" },
                    color = TodoMuted, fontSize = 15.sp, modifier = Modifier.weight(1f)
                )
                if (habits.isNotEmpty()) Row {
                    Segment("Month", pager.targetPage == 0) { scope.launch { pager.animateScrollToPage(0, animationSpec = PageSpring) } }
                    Segment("Year", pager.targetPage == 1) { scope.launch { pager.animateScrollToPage(1, animationSpec = PageSpring) } }
                }
            }
        }
        if (habits.isEmpty()) {
            Column(Modifier.fillMaxWidth().padding(top = 80.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Glyph(ZenIcon.Habit, TodoMuted, 40.dp)
                Text("No habits yet", color = TodoText, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 14.dp))
                Text("Tap + to start one", color = TodoMuted, fontSize = 14.sp, modifier = Modifier.padding(top = 4.dp))
                Spacer(Modifier.height(16.dp))
                GlassPill(ZenIcon.Plus, "New habit", onAdd)
            }
        } else HorizontalPager(pager, Modifier.fillMaxSize(), flingBehavior = rubberFling(pager), beyondViewportPageCount = 1, key = { it }) { page ->
            val yearView = page == 1
            LazyColumn(Modifier.fillMaxSize().bounce(pager, page), contentPadding = PaddingValues(top = 4.dp, bottom = 24.dp)) {
                items(habits, key = { it.id }) { habit ->
                    Box(Modifier.animateItem().padding(horizontal = 14.dp, vertical = 6.dp)) {
                        HabitCard(habit, today, yearView, onToggleDay = { onToggleDay(habit, it) }, onEdit = { onEdit(habit) }, onDelete = { onDelete(habit) })
                    }
                }
            }
        }
    }
}

@Composable
private fun Segment(label: String, selected: Boolean, onClick: () -> Unit) {
    // Plain labels like the task folders: the selected one brighter, with a line in the tab's colour under it.
    val text by animateColorAsState(if (selected) TodoText else TodoMuted, tween(200), label = "segment")
    val underline by androidx.compose.animation.core.animateFloatAsState(if (selected) 1f else 0f, spring(dampingRatio = .7f, stiffness = Spring.StiffnessMediumLow), label = "segmentLine")
    Text(
        label, color = text, fontSize = 15.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        modifier = Modifier.clip(RoundedCornerShape(10.dp)).clickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 8.dp)
            .tabUnderline(underline, if (Look.light) TodoBlue else Look.accent.fill)
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HabitCard(habit: BoardCard, today: Long, yearView: Boolean, onToggleDay: (Long) -> Unit, onEdit: () -> Unit, onDelete: () -> Unit) {
    val color = habitColor(habit)
    val days = remember(habit.checkIns) { habit.checkIns.toHashSet() }
    val streak = currentStreak(days, today)
    val best = remember(habit.checkIns) { bestStreak(habit.checkIns) }
    val checked = today in days
    val haptics = LocalHapticFeedback.current
    var menu by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(20.dp)
    Surface(color = TodoCard, shape = shape, border = GlassStroke, modifier = Modifier.glassBlur(shape).fillMaxWidth().clip(shape).combinedClickable(onClick = onEdit, onLongClick = { menu = true })) {
        Column(Modifier.padding(start = 16.dp, end = 14.dp, top = 14.dp, bottom = 14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(10.dp).clip(CircleShape).background(color))
                Column(Modifier.weight(1f).padding(start = 10.dp)) {
                    Text(habit.title, color = TodoText, fontSize = 17.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    MetaLine(habitSummary(habit), TodoMuted, 12.sp, Modifier.padding(top = 2.dp), badges = listOfNotNull(reminderIcon(habit.remind)))
                }
                StreakBadge(streak, color)
                Spacer(Modifier.width(10.dp))
                val fill by animateColorAsState(if (checked) color else Color.Transparent, tween(200), label = "habitCheck")
                Box(
                    Modifier.size(38.dp).clip(CircleShape).background(fill).border(1.5.dp, if (checked) color else TodoMuted.copy(alpha = .6f), CircleShape)
                        .clickable { haptics.performHapticFeedback(if (checked) HapticFeedbackType.SegmentTick else HapticFeedbackType.Confirm); onToggleDay(today) }
                        .semantics { contentDescription = if (checked) "Undo today's check-in" else "Check in today" },
                    contentAlignment = Alignment.Center
                ) { if (checked) Glyph(ZenIcon.Check, OnAccent, 19.dp) }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("Edit") }, onClick = { menu = false; onEdit() })
                    DropdownMenuItem(text = { Text("Delete", color = Danger) }, onClick = { menu = false; onDelete() })
                }
            }
            Spacer(Modifier.height(14.dp))
            if (yearView) YearGrid(days, today, color, onToggleDay) else MonthGrid(days, today, color, onToggleDay)
            Spacer(Modifier.height(12.dp))
            val month = YearMonth.from(LocalDate.ofEpochDay(today))
            val monthCount = checkInsIn(days, month.atDay(1).toEpochDay(), today)
            val yearCount = checkInsIn(days, today - 364, today)
            Row(Modifier.fillMaxWidth()) {
                Stat("$streak", "Streak", Modifier.weight(1f))
                Stat("$best", "Best", Modifier.weight(1f))
                if (yearView) Stat("$yearCount", "Past year", Modifier.weight(1f))
                else Stat("$monthCount/${LocalDate.ofEpochDay(today).dayOfMonth}", month.month.getDisplayName(java.time.format.TextStyle.FULL, Locale.getDefault()), Modifier.weight(1f))
            }
        }
    }
}

@Composable
internal fun StreakBadge(streak: Int, color: Color) {
    Row(
        Modifier.clip(RoundedCornerShape(12.dp)).background(if (streak > 0) color.copy(alpha = .16f) else veil(0.06f)).padding(horizontal = 9.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Glyph(ZenIcon.Flame, if (streak > 0) color else TodoMuted, 14.dp)
        Spacer(Modifier.width(4.dp))
        Text("$streak", color = if (streak > 0) color else TodoMuted, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun Stat(value: String, label: String, modifier: Modifier) {
    Column(modifier) {
        Text(value, color = TodoText, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        Text(label, color = TodoMuted, fontSize = 11.sp)
    }
}

private val EmptySquare get() = veil(0.08f)

/** One square: filled with the habit's colour when done, outlined for today, faint for days still ahead. */
@Composable
private fun DaySquare(size: Dp, done: Boolean, isToday: Boolean, future: Boolean, color: Color, radius: Dp, label: String? = null, onClick: () -> Unit) {
    Box(
        Modifier.size(size).clip(RoundedCornerShape(radius))
            .background(when { done -> color; future -> veil(0.03f); else -> EmptySquare })
            .then(if (isToday) Modifier.border(1.5.dp, if (done) TodoText.copy(alpha = .8f) else TodoText.copy(alpha = .55f), RoundedCornerShape(radius)) else Modifier)
            .clickable(enabled = !future, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        if (label != null) Text(label, color = when { done -> OnAccent; future -> TodoMuted.copy(alpha = .35f); else -> TodoMuted }, fontSize = 11.sp, fontWeight = if (done) FontWeight.SemiBold else FontWeight.Normal)
    }
}

/** This month as a calendar: a square per day, weeks starting Sunday like the rest of the app. */
@Composable
private fun MonthGrid(days: Set<Long>, today: Long, color: Color, onToggleDay: (Long) -> Unit) {
    val month = YearMonth.from(LocalDate.ofEpochDay(today))
    val first = month.atDay(1)
    val lead = first.dayOfWeek.value % 7
    val rows = (lead + month.lengthOfMonth() + 6) / 7
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val gap = 5.dp
        val cell = ((maxWidth - gap * 6) / 7).coerceAtMost(40.dp)
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(gap), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                listOf("S", "M", "T", "W", "T", "F", "S").forEach { Text(it, color = TodoMuted, fontSize = 10.sp, textAlign = TextAlign.Center, modifier = Modifier.width(cell)) }
            }
            (0 until rows).forEach { r ->
                Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                    (0 until 7).forEach { c ->
                        val index = r * 7 + c - lead
                        if (index < 0 || index >= month.lengthOfMonth()) Spacer(Modifier.size(cell))
                        else {
                            val day = first.toEpochDay() + index
                            DaySquare(cell, day in days, day == today, day > today, color, 8.dp, "${index + 1}") { onToggleDay(day) }
                        }
                    }
                }
            }
        }
    }
}

/** The past year, GitHub style: a column per week (Sunday at the top), newest on the right; scrolls back through the months. */
@Composable
private fun YearGrid(days: Set<Long>, today: Long, color: Color, onToggleDay: (Long) -> Unit) {
    val weeks = 53
    val thisWeekStart = today - (LocalDate.ofEpochDay(today).dayOfWeek.value % 7)
    val start = thisWeekStart - (weeks - 1) * 7
    val scroll = rememberScrollState()
    LaunchedEffect(Unit) { scroll.scrollTo(scroll.maxValue) }
    LaunchedEffect(scroll.maxValue) { scroll.scrollTo(scroll.maxValue) }
    val cell = 13.dp
    val gap = 3.dp
    Row(Modifier.fillMaxWidth().horizontalScroll(scroll)) {
        (0 until weeks).forEach { w ->
            val weekStart = start + w * 7
            Column(verticalArrangement = Arrangement.spacedBy(gap), modifier = Modifier.padding(end = gap)) {
                // A month's short name above the first week that starts in it.
                val date = LocalDate.ofEpochDay(weekStart)
                val label = if (date.dayOfMonth <= 7) date.format(DateTimeFormatter.ofPattern("MMM", Locale.getDefault())) else ""
                Box(Modifier.width(cell).height(14.dp)) {
                    if (label.isNotEmpty()) Text(label, color = TodoMuted, fontSize = 9.sp, maxLines = 1, softWrap = false, modifier = Modifier.wrapContentWidth(Alignment.Start, unbounded = true))
                }
                (0 until 7).forEach { d ->
                    val day = weekStart + d
                    DaySquare(cell, day in days, day == today, day > today, color, 3.dp) { onToggleDay(day) }
                }
            }
        }
    }
}

/** Add or edit a habit: name, task or event, optional start time and duration, reminder, and colour. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun HabitEditor(editing: BoardCard?, onDismiss: () -> Unit, onSave: (BoardCard) -> Unit, onDelete: (BoardCard) -> Unit, newCard: () -> BoardCard) {
    val context = LocalContext.current
    var title by rememberSaveable(editing?.id) { mutableStateOf(editing?.title.orEmpty()) }
    var asEvent by rememberSaveable(editing?.id) { mutableStateOf(editing?.habitEvent ?: false) }
    var minute by rememberSaveable(editing?.id) { mutableStateOf(editing?.dueMinute) }
    var duration by rememberSaveable(editing?.id) { mutableStateOf(editing?.durationMinutes ?: 0) }
    var remind by rememberSaveable(editing?.id) { mutableStateOf(editing?.remind.orEmpty()) }
    // A new habit gets the next colour no other habit is wearing yet.
    val starter = remember { newCard() }
    var colorIndex by rememberSaveable(editing?.id) { mutableStateOf(editing?.let(::habitColorIndex) ?: habitColorIndex(starter)) }
    var pickTime by remember { mutableStateOf(false) }
    var remindMenu by remember { mutableStateOf(false) }
    var durationMenu by remember { mutableStateOf(false) }
    var notificationsBlocked by remember { mutableStateOf(false) }
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> notificationsBlocked = !granted }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { if (editing == null) runCatching { focus.requestFocus() } }

    fun chooseRemind(type: String) {
        remind = type
        if (type.isBlank()) return
        if (Reminders.needsNotificationPermission(context)) notificationPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        else notificationsBlocked = !Reminders.notificationsAllowed(context)
        // A reminder needs a time of day to go off at.
        if (minute == null) minute = DEFAULT_HABIT_REMINDER_MINUTE
    }
    val canSave = title.isNotBlank()
    fun save() {
        if (!canSave) return
        val base = editing ?: starter
        onSave(base.copy(
            title = title.trim(), habitEvent = asEvent, dueMinute = minute, durationMinutes = if (minute == null && !asEvent) 0 else duration,
            remind = if (minute != null) remind else "", remindLeadMinutes = 0, habitColor = colorIndex
        ))
    }
    val accent = Color(HabitPalette[colorIndex].toInt())

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = GlassSheet,
        dragHandle = null,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
    ) {
        Column(Modifier.fillMaxWidth().imePadding().navigationBarsPadding()) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(top = 22.dp, bottom = 14.dp)) {
                Text(if (editing == null) "NEW HABIT" else "EDIT HABIT", color = TodoMuted, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp)
                Spacer(Modifier.height(10.dp))
                SheetField(value = title, onValueChange = { title = it }, hint = "e.g. Read for 20 minutes", textSize = 22.sp, modifier = Modifier.focusRequester(focus), imeAction = ImeAction.Send, onSend = ::save)

                Spacer(Modifier.height(18.dp))
                Row(Modifier.clip(RoundedCornerShape(12.dp)).background(TodoCard).padding(3.dp)) {
                    Segment("Task", !asEvent) { asEvent = false }
                    Segment("Event", asEvent) { asEvent = true }
                }
                Text(
                    if (asEvent) "Happens at a set time each day, and shows on your calendar then." else "Something to tick off each day, whenever you get to it.",
                    color = TodoMuted, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp)
                )

                Spacer(Modifier.height(14.dp))
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SheetChip(minute?.let { (if (asEvent) "Starts " else "By ") + formatClock(it) } ?: "Start time", if (minute != null) TodoBlue else TodoText, ZenIcon.Clock) { pickTime = true }
                    if (minute != null) SheetChip("", TodoMuted, ZenIcon.Close) { minute = null; remind = "" }
                    if (minute != null || asEvent) Box {
                        SheetChip(if (duration > 0) durationLabel(duration) else "Duration", if (duration > 0) TodoBlue else TodoText, ZenIcon.Timer) { durationMenu = true }
                        DropdownMenu(expanded = durationMenu, onDismissRequest = { durationMenu = false }) {
                            HABIT_DURATIONS.forEach { m ->
                                DropdownMenuItem(text = { Text(durationLabel(m)) }, trailingIcon = if (m == duration) ({ Glyph(ZenIcon.Check, TodoBlue, 16.dp) }) else null, onClick = { duration = m; durationMenu = false })
                            }
                        }
                    }
                    Box {
                        SheetChip(if (remind.isBlank()) "Remind" else remindTypeLabel(remind), if (remind.isBlank()) TodoText else TodoBlue, reminderIcon(remind) ?: ZenIcon.Bell) { remindMenu = true }
                        RemindMenu(remindMenu, remind, 0, showLeads = false, onDismiss = { remindMenu = false }, onType = { remindMenu = false; chooseRemind(it) }, onLead = {})
                    }
                }
                if (remind.isNotBlank()) {
                    Text("${remindTypeLabel(remind)} at ${formatClock(minute ?: DEFAULT_HABIT_REMINDER_MINUTE)} on days you haven’t checked in yet", color = TodoMuted, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
                    if (notificationsBlocked) RemindWarning()
                    ReminderSystemHints(remind)
                }

                Spacer(Modifier.height(18.dp))
                Text("COLOUR", color = TodoMuted, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp)
                Spacer(Modifier.height(10.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    HabitPalette.forEachIndexed { i, argb ->
                        val selected = i == colorIndex
                        Box(
                            Modifier.size(34.dp).clip(CircleShape).border(2.dp, if (selected) TodoText else Color.Transparent, CircleShape).padding(4.dp)
                                .clip(CircleShape).background(Color(argb.toInt())).clickable { colorIndex = i }
                                .semantics { contentDescription = HabitColorNames[i] },
                            contentAlignment = Alignment.Center
                        ) { if (selected) Glyph(ZenIcon.Check, OnAccent, 14.dp) }
                    }
                }
            }
            HorizontalDivider(color = TodoBorder)
            Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                if (editing != null) Text("Delete", color = Danger, fontSize = 15.sp, modifier = Modifier.clip(RoundedCornerShape(12.dp)).clickable { onDelete(editing) }.padding(horizontal = 10.dp, vertical = 10.dp))
                Spacer(Modifier.weight(1f))
                Surface(
                    color = if (canSave) accent else TodoCardRaised, shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.clip(RoundedCornerShape(14.dp)).clickable(enabled = canSave) { save() }
                ) {
                    Text(if (editing == null) "Add habit" else "Save", color = if (canSave) OnAccent else TodoMuted, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp))
                }
            }
        }
    }
    LaunchedEffect(pickTime) {
        if (pickTime) {
            val initial = minute ?: LocalTime.now().let { it.hour * 60 + it.minute }
            TimePickerDialog(context, { _, hour, m -> minute = hour * 60 + m; pickTime = false }, initial / 60, initial % 60, false)
                .apply { setOnCancelListener { pickTime = false } }.show()
        }
    }
}
