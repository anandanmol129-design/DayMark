package com.anmol.daymark

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

private val Late get() = Danger
private const val MAX_IMPORTANT = 5

/** Everything the Today screen shows, already sorted; each list is empty when its section should be hidden. */
internal data class TodayAgenda(
    val overdue: List<BoardCard>,
    /** Timed events and tasks in time order, open ones first only within the same minute (done tasks stay in place, struck through). */
    val timed: List<DayEntry>,
    /** Untimed tasks (open first) and all-day events. */
    val untimed: List<DayEntry>,
    /** Habits not checked in yet, then the done ones. */
    val habits: List<DayEntry>,
    val running: List<BoardCard>,
    val important: List<BoardCard>,
    /** The timed entry happening now, or else the next one still to come. */
    val focusId: String?,
    val tasksLeft: Int,
    val eventsLeft: Int,
    val habitsLeft: Int,
    /** Everything dated tomorrow (no habits), timed in time order then untimed. */
    val tomorrow: List<DayEntry> = emptyList(),
    /** The days after tomorrow up to the end of the week (or the next 7 days), each with its entries; empty days are left out. */
    val week: List<Pair<Long, List<DayEntry>>> = emptyList(),
    val weekLabel: String = "THIS WEEK"
) {
    /** Something to show after today's sections. */
    val hasUpcoming get() = tomorrow.isNotEmpty() || week.isNotEmpty()
    /** Nothing is waiting on you (a suggested next to-do with no date or priority doesn't count). */
    val clear get() = overdue.isEmpty() && tasksLeft == 0 && eventsLeft == 0 && habitsLeft == 0 && running.isEmpty() && important.isEmpty()
    /** Nothing at all to show, not even finished items. */
    val empty get() = clear && timed.isEmpty() && untimed.isEmpty() && habits.isEmpty()
}

/** Timed entries in time order, then untimed ones (open before done). Habits are left out: they are daily and would only repeat. */
private fun upcomingOn(cards: List<BoardCard>, day: Long): List<DayEntry> {
    val dated = entriesFor(cards, day).filter { it.card.kind != CardKind.HABIT }
    return dated.filter { it.minute != null }.sortedWith(compareBy<DayEntry> { it.minute }.thenBy { it.card.createdAt }) +
        dated.filter { it.minute == null }.sortedWith(compareBy<DayEntry> { it.done }.thenBy { it.card.createdAt })
}

/**
 * Days shown under "This week": after tomorrow up to the last day of the week, using the Calendar strip's convention
 * (weeks run Sunday to Saturday). When today or tomorrow is already that last day there is nothing left of the week,
 * so it becomes the 7 days after tomorrow instead ("Next 7 days").
 */
internal fun weekDays(today: Long): Pair<List<Long>, String> {
    val lastOfWeek = today + (6 - LocalDate.ofEpochDay(today).dayOfWeek.value % 7)
    return if (lastOfWeek <= today + 1) (today + 2..today + 8).toList() to "NEXT 7 DAYS" else (today + 2..lastOfWeek).toList() to "THIS WEEK"
}

private fun DayEntry.endMinute() = (minute ?: 0) + maxOf(card.durationMinutes, 1)

internal fun todayAgenda(snapshot: BoardSnapshot, today: Long, nowMinute: Int): TodayAgenda {
    val cards = snapshot.cards
    val overdue = cards.filter { it.kind == CardKind.TASK && !it.completed && it.dueDay != null && it.dueDay < today }
        .sortedWith(compareBy<BoardCard> { it.dueDay }.thenBy { it.dueMinute ?: -1 }.thenBy { it.createdAt })
    val entries = entriesFor(cards, today)
    val dated = entries.filter { it.card.kind != CardKind.HABIT }
    val timed = dated.filter { it.minute != null }.sortedWith(compareBy<DayEntry> { it.minute }.thenBy { it.card.createdAt })
    val untimed = dated.filter { it.minute == null }.sortedWith(compareBy<DayEntry> { it.done }.thenBy { it.card.createdAt })
    val habits = entries.filter { it.card.kind == CardKind.HABIT }.sortedWith(compareBy<DayEntry> { it.done }.thenBy { it.card.createdAt })
    val focus = timed.firstOrNull { !it.done && it.endMinute() > nowMinute }
    val openToday = dated.filter { it.card.kind == CardKind.TASK && !it.done }.size
    val eventsLeft = timed.count { it.card.kind == CardKind.EVENT && it.endMinute() > nowMinute } + untimed.count { it.card.kind == CardKind.EVENT }
    val (days, weekLabel) = weekDays(today)
    return TodayAgenda(
        overdue = overdue,
        timed = timed,
        untimed = untimed,
        habits = habits,
        running = cards.filter { it.kind == CardKind.TIMER && it.activeSinceMillis != null },
        important = cards.filter { it.kind == CardKind.TASK && !it.completed && it.important && it.dueDay == null }.take(MAX_IMPORTANT),
        focusId = focus?.card?.id,
        tasksLeft = overdue.size + openToday,
        eventsLeft = eventsLeft,
        habitsLeft = habits.count { !it.done },
        tomorrow = upcomingOn(cards, today + 1),
        week = days.map { it to upcomingOn(cards, it) }.filter { it.second.isNotEmpty() },
        weekLabel = weekLabel
    )
}

private fun TodayAgenda.summary(): String {
    fun part(n: Int, one: String, many: String = one + "s") = if (n > 0) "$n ${if (n == 1) one else many}" else null
    val parts = listOfNotNull(part(tasksLeft, "task"), part(eventsLeft, "event"), part(habitsLeft, "habit"))
    return if (parts.isEmpty()) "Nothing left today" else parts.joinToString(" · ") + " left"
}

private fun lateLabel(dueDay: Long, today: Long): String {
    val days = today - dueDay
    return if (days <= 1) "Yesterday" else "$days days ago"
}

private fun formatElapsed(ms: Long): String {
    val s = ms.coerceAtLeast(0) / 1000
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}

private fun metaFor(card: BoardCard, extra: String? = null): String = listOfNotNull(
    extra,
    if (card.repeat.isNotBlank()) Repeat.fromId(card.repeat).label else null
).joinToString(" · ")

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun TodayScreen(
    snapshot: BoardSnapshot,
    timerTick: Long,
    clockKey: Int,
    onEdit: (BoardCard) -> Unit,
    onComplete: (BoardCard) -> Unit,
    onReopen: (BoardCard) -> Unit,
    onReschedule: (BoardCard) -> Unit,
    onHabitCheckIn: (BoardCard) -> Unit,
    onToggleTimer: (BoardCard) -> Unit,
    onDelete: (BoardCard) -> Unit
) {
    // Same idea as the Calendar screen's clock: tick on the minute so "now", overdue and the date follow real time (also past midnight and on resume).
    var tick by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(clockKey) {
        tick = System.currentTimeMillis()
        while (true) {
            delay(60_000 - System.currentTimeMillis() % 60_000 + 50)
            tick = System.currentTimeMillis()
        }
    }
    val now = remember(tick) { LocalDateTime.now() }
    val today = now.toLocalDate().toEpochDay()
    val nowMinute = now.hour * 60 + now.minute
    val agenda = remember(snapshot.cards, today, nowMinute) { todayAgenda(snapshot, today, nowMinute) }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        item(key = "header") {
            Column(Modifier.fillMaxWidth().padding(start = 22.dp, end = 16.dp, top = 14.dp, bottom = 14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(todayLabel(), color = TodoMuted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp, modifier = Modifier.weight(1f))
                    SettingsButton()
                }
                Text(
                    "Today", color = TodoText, fontSize = if (Look.light) 44.sp else 34.sp, fontWeight = if (Look.light) FontWeight.Black else FontWeight.Bold,
                    letterSpacing = if (Look.light) (-1).sp else 0.sp, modifier = Modifier.padding(top = 6.dp)
                )
                Text(agenda.summary(), color = TodoMuted, fontSize = 15.sp, modifier = Modifier.padding(top = 2.dp))
                val doable = remember(agenda) { (agenda.timed + agenda.untimed + agenda.habits).filter { it.card.kind == CardKind.TASK || it.card.kind == CardKind.HABIT } }
                DayProgress(doable.count { it.done }, doable.size, Modifier.fillMaxWidth().padding(top = 14.dp, end = 6.dp))
            }
        }
        if (agenda.empty && !agenda.hasUpcoming) {
            item(key = "empty") { AllClear(Modifier.fillParentMaxHeight(.55f)) }
            return@LazyColumn
        }
        if (agenda.clear) item(key = "clear") {
            Panel(Modifier.padding(horizontal = 14.dp).padding(bottom = 14.dp)) {
                Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Glyph(ZenIcon.Check, TodoBlue, 26.dp)
                    Column(Modifier.padding(start = 14.dp)) {
                        Text("All clear for today", color = TodoText, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                        Text("Tap + to add something", color = TodoMuted, fontSize = 13.sp)
                    }
                }
            }
        }
        if (agenda.overdue.isNotEmpty()) item(key = "overdue") {
            Section("OVERDUE", agenda.overdue.size.toString(), Late) {
                agenda.overdue.forEachIndexed { i, card ->
                    if (i > 0) RowDivider()
                    TodayRow(
                        title = card.title, meta = metaFor(card, listOfNotNull(lateLabel(card.dueDay!!, today), card.dueMinute?.let { formatClock(it).replace(" ", "") }).joinToString(" · ")), metaColor = Late,
                        onClick = { onEdit(card) }, onDelete = { onDelete(card) },
                        leading = { TaskCheck(card, onComplete, onReopen) },
                        badges = badgesFor(card).filter { it != ZenIcon.Repeat },
                        trailing = { Pill("Today", ZenIcon.ArrowRight) { onReschedule(card) } }
                    )
                }
            }
        }
        if (agenda.timed.isNotEmpty() || agenda.untimed.isNotEmpty()) item(key = "today") {
            val hasTimes = agenda.timed.isNotEmpty()
            val open = agenda.timed.count { !it.done } + agenda.untimed.count { !it.done }
            Section("TODAY", if (open > 0) open.toString() else null, TodoMuted) {
                (agenda.timed + agenda.untimed).forEachIndexed { i, entry ->
                    if (i > 0) RowDivider()
                    val card = entry.card
                    val isEvent = card.kind == CardKind.EVENT
                    val focused = entry.card.id == agenda.focusId
                    val past = entry.minute != null && !entry.done && entry.endMinute() <= nowMinute && isEvent
                    TodayRow(
                        title = card.title,
                        meta = metaFor(card, if (card.durationMinutes > 0 && entry.minute != null) "until ${formatClock((entry.minute + card.durationMinutes) % (24 * 60)).replace(" ", "")}" else if (isEvent && entry.minute == null) "All day" else null),
                        timeLabel = if (!hasTimes) null else entry.minute?.let { formatClock(it).replace(" ", "") } ?: "Anytime",
                        badge = if (focused) (if ((entry.minute ?: 0) <= nowMinute) "NOW" else "NEXT") else null,
                        highlight = focused, done = entry.done, dim = past, badges = badgesFor(card).filter { it != ZenIcon.Repeat },
                        onClick = { onEdit(card) }, onDelete = { onDelete(card) },
                        leading = { if (isEvent) Box(Modifier.size(26.dp), contentAlignment = Alignment.Center) { Glyph(ZenIcon.Calendar, CalendarBlue, 20.dp) } else TaskCheck(card, onComplete, onReopen) }
                    )
                }
            }
        }
        if (agenda.habits.isNotEmpty()) item(key = "habits") {
            Section("HABITS", agenda.habitsLeft.takeIf { it > 0 }?.toString(), TodoMuted) {
                agenda.habits.forEachIndexed { i, entry ->
                    if (i > 0) RowDivider()
                    val card = entry.card
                    val streak = currentStreak(card.checkIns, today)
                    TodayRow(
                        title = card.title,
                        meta = listOfNotNull(if (card.habitEvent) "Daily event" else "Daily", card.dueMinute?.let { formatClock(it).replace(" ", "") }).joinToString(" · "), badges = listOfNotNull(reminderIcon(card.remind)),
                        done = entry.done,
                        onClick = { onEdit(card) }, onDelete = { onDelete(card) },
                        leading = { CheckCircle(entry.done, if (entry.done) "Undo check-in" else "Check in", habitColor(card)) { onHabitCheckIn(card) } },
                        trailing = { if (streak > 0) MetaLine("$streak", habitColor(card), 14.sp, leading = ZenIcon.Flame, weight = FontWeight.SemiBold) }
                    )
                }
            }
        }
        if (agenda.running.isNotEmpty()) item(key = "running") {
            Section("RUNNING", null, TodoMuted) {
                agenda.running.forEachIndexed { i, card ->
                    if (i > 0) RowDivider()
                    val session = maxOf(timerTick, card.activeSinceMillis ?: 0L) - (card.activeSinceMillis ?: 0L)
                    TodayRow(
                        title = card.title, meta = formatElapsed(session), metaColor = Amber,
                        onClick = { onEdit(card) }, onDelete = { onDelete(card) },
                        leading = { Box(Modifier.size(26.dp), contentAlignment = Alignment.Center) { Glyph(ZenIcon.Clock, Amber, 22.dp) } },
                        trailing = { Pill("Stop", ZenIcon.Stop) { onToggleTimer(card) } }
                    )
                }
            }
        }
        if (agenda.important.isNotEmpty()) item(key = "important") {
            Section("IMPORTANT, NO DATE", null, TodoMuted) {
                agenda.important.forEachIndexed { i, card ->
                    if (i > 0) RowDivider()
                    TodayRow(
                        title = card.title, meta = metaFor(card),
                        onClick = { onEdit(card) }, onDelete = { onDelete(card) },
                        leading = { TaskCheck(card, onComplete, onReopen) },
                        trailing = { Glyph(ZenIcon.StarFilled, Amber, 16.dp) }
                    )
                }
            }
        }
        if (agenda.tomorrow.isNotEmpty()) {
            item(key = "divider-tomorrow") { DayDivider() }
            item(key = "tomorrow") {
                val hasTimes = agenda.tomorrow.any { it.minute != null }
                Section("TOMORROW", agenda.tomorrow.count { !it.done }.takeIf { it > 0 }?.toString(), TodoMuted) {
                    agenda.tomorrow.forEachIndexed { i, entry ->
                        if (i > 0) RowDivider()
                        UpcomingRow(entry, hasTimes, onEdit, onDelete, onComplete, onReopen)
                    }
                }
            }
        }
        if (agenda.week.isNotEmpty()) {
            item(key = "divider-week") { DayDivider() }
            item(key = "week") {
                Section(agenda.weekLabel, agenda.week.sumOf { (_, list) -> list.count { !it.done } }.takeIf { it > 0 }?.toString(), TodoMuted) {
                    agenda.week.forEachIndexed { i, (day, entries) ->
                        if (i > 0) RowDivider()
                        Text(
                            LocalDate.ofEpochDay(day).format(DateTimeFormatter.ofPattern("EEE d", Locale.getDefault())), color = TodoMuted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(start = 14.dp, top = 10.dp, bottom = 0.dp)
                        )
                        val hasTimes = entries.any { it.minute != null }
                        entries.forEach { entry -> UpcomingRow(entry, hasTimes, onEdit, onDelete, onComplete, onReopen) }
                    }
                }
            }
        }
    }
}

/** The hairline between today and what comes next. */
@Composable
private fun DayDivider() = HorizontalDivider(Modifier.padding(horizontal = 36.dp, vertical = 14.dp), thickness = 0.5.dp, color = veil(0.20f))

/** A quieter, more compact row for tomorrow and later in the week: same tap, checkbox and long-press behaviour as today's. */
@Composable
private fun UpcomingRow(entry: DayEntry, hasTimes: Boolean, onEdit: (BoardCard) -> Unit, onDelete: (BoardCard) -> Unit, onComplete: (BoardCard) -> Unit, onReopen: (BoardCard) -> Unit) {
    val card = entry.card
    val isEvent = card.kind == CardKind.EVENT
    TodayRow(
        title = card.title,
        meta = metaFor(card, if (isEvent && entry.minute == null) "All day" else null),
        timeLabel = if (!hasTimes) null else entry.minute?.let { formatClock(it).replace(" ", "") } ?: "Anytime",
        done = entry.done, compact = true,
        onClick = { onEdit(card) }, onDelete = { onDelete(card) },
        leading = { if (isEvent) Box(Modifier.size(26.dp), contentAlignment = Alignment.Center) { Glyph(ZenIcon.Calendar, CalendarBlue, 18.dp) } else TaskCheck(card, onComplete, onReopen) }
    )
}

@Composable
private fun Panel(modifier: Modifier = Modifier, color: Color = TodoCard, content: @Composable () -> Unit) {
    val shape = RoundedCornerShape(22.dp)
    Surface(color = color, shape = shape, border = GlassStroke, modifier = modifier.fillMaxWidth().glassBlur(shape), content = content)
}

@Composable
private fun Section(title: String, count: String?, countColor: Color, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.rise().padding(horizontal = 14.dp).padding(bottom = 18.dp)) {
        Row(Modifier.padding(start = 8.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(title, color = TodoMuted, fontSize = 11.sp, fontWeight = FontWeight.Medium, letterSpacing = 1.6.sp)
            if (count != null) Text(count, color = countColor, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 8.dp))
        }
        Panel { Column(content = content) }
    }
}

@Composable
private fun RowDivider() = HorizontalDivider(Modifier.padding(start = 14.dp), color = veil(0.10f))

@Composable
private fun Pill(label: String, icon: ZenIcon? = null, onClick: () -> Unit) {
    Box(
        Modifier.padding(start = 8.dp).clip(RoundedCornerShape(12.dp)).background(veil(0.08f)).border(0.75.dp, veil(0.18f), RoundedCornerShape(12.dp))
            .clickable(onClick = onClick).padding(horizontal = 11.dp, vertical = 7.dp)
    ) { MetaLine(label, TodoText, 13.sp, leading = icon, weight = FontWeight.SemiBold) }
}

@Composable
private fun CheckCircle(checked: Boolean, description: String, fill: Color = CalendarBlue, onClick: () -> Unit) {
    val shape = checkShape(checked)
    Box(
        Modifier.size(26.dp).clip(shape).background(if (checked) fill else Color.Transparent)
            .border(1.5.dp, if (checked) fill else TodoMuted.copy(alpha = .6f), shape)
            .clickable(onClick = onClick).semantics { contentDescription = description },
        contentAlignment = Alignment.Center
    ) { if (checked) Glyph(ZenIcon.Check, if (fill == CalendarBlue) OnFill else Color.White, 15.dp) }
}

/** A task's checkbox: ticks at once, then completes a beat later (through the same handler as every other screen). */
@Composable
private fun TaskCheck(card: BoardCard, onComplete: (BoardCard) -> Unit, onReopen: (BoardCard) -> Unit) {
    val scope = rememberCoroutineScope()
    val haptics = androidx.compose.ui.platform.LocalHapticFeedback.current
    var ticking by remember(card.id, card.completed) { mutableStateOf(false) }
    CheckCircle(card.completed || ticking, if (card.completed) "Mark not done" else "Mark done") {
        if (card.completed) onReopen(card)
        else if (!ticking) { ticking = true; haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.Confirm); scope.launch { delay(260); onComplete(card) } }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TodayRow(
    title: String,
    meta: String,
    badges: List<ZenIcon> = emptyList(),
    onClick: () -> Unit,
    onDelete: () -> Unit,
    leading: @Composable () -> Unit,
    timeLabel: String? = null,
    badge: String? = null,
    metaColor: Color = TodoMuted,
    highlight: Boolean = false,
    done: Boolean = false,
    dim: Boolean = false,
    compact: Boolean = false,
    trailing: (@Composable () -> Unit)? = null
) {
    var menu by remember { mutableStateOf(false) }
    Box(if (compact) Modifier.alpha(.78f) else Modifier) {
        Row(
            Modifier.fillMaxWidth().background(if (highlight) GlassAccent else Color.Transparent)
                .combinedClickable(onClick = onClick, onLongClick = { menu = true })
                .padding(horizontal = 14.dp, vertical = if (compact) 7.dp else 11.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (timeLabel != null) Column(Modifier.width(66.dp)) {
                Text(timeLabel, color = if (timeLabel == "Anytime") TodoMuted.copy(alpha = .7f) else TodoText, fontSize = 12.sp, maxLines = 1)
                if (badge != null) Text(badge, color = Amber, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = .8.sp)
            }
            leading()
            Column(Modifier.weight(1f).padding(start = 14.dp)) {
                Text(
                    title, color = if (done || dim) TodoMuted else TodoText, fontSize = if (compact) 15.sp else 17.sp, lineHeight = if (compact) 20.sp else 22.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    textDecoration = if (done) TextDecoration.LineThrough else null
                )
                if (meta.isNotEmpty() || badges.isNotEmpty()) MetaLine(meta, metaColor, 13.sp, Modifier.padding(top = 2.dp), badges = badges)
            }
            trailing?.invoke()
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(text = { Text("Delete", color = Danger) }, onClick = { menu = false; onDelete() })
        }
    }
}

@Composable
private fun AllClear(modifier: Modifier) {
    Column(modifier.rise().fillMaxWidth().padding(horizontal = 32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Glyph(ZenIcon.Sun, TodoBlue, 40.dp, modifier = Modifier.breathe())
        Spacer(Modifier.height(18.dp))
        Text("All clear for today", color = TodoText, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(7.dp))
        Text("Tap + to add a task, event or habit", color = TodoMuted, fontSize = 15.sp)
    }
}
