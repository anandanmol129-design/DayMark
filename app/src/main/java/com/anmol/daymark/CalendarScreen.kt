package com.anmol.daymark

import android.app.DatePickerDialog as PlatformDatePickerDialog
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.runtime.key
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale

internal data class DayEntry(val card: BoardCard, val minute: Int?, val done: Boolean)

private sealed interface TimelineRow {
    data class Item(val entry: DayEntry) : TimelineRow
    data class Gap(val from: Int, val to: Int) : TimelineRow
    data object Now : TimelineRow
}

// Muted earth tones: sage, mist, stone violet, clay, sand and moss.
internal val EntryPalette = listOf(0xFF7FA595, 0xFF7E9BB0, 0xFF9289A8, 0xFFC08A72, 0xFFBFA477, 0xFF8FA27A)

// Colours from the phone's calendars are softened toward warm grey so they sit in the same calm palette.
internal fun entryColor(card: BoardCard): Color =
    if (card.kind == CardKind.HABIT) habitColor(card) else card.calendarColor?.let { androidx.compose.ui.graphics.lerp(Color(it.toInt()), Color(0xFF8A8780), .45f) } ?: Color(EntryPalette[Math.floorMod(card.id.hashCode(), EntryPalette.size)])

/** Everything that lands on [day]: dated tasks and events (repeating events on each occurrence), plus habits, which repeat daily. */
internal fun entriesFor(cards: List<BoardCard>, day: Long): List<DayEntry> = cards.mapNotNull { card ->
    when {
        card.kind == CardKind.HABIT -> {
            val created = java.time.Instant.ofEpochMilli(card.createdAt).atZone(java.time.ZoneId.systemDefault()).toLocalDate().toEpochDay()
            if (day >= created || card.checkedOn(day)) DayEntry(card, card.dueMinute, card.checkedOn(day)) else null
        }
        card.kind == CardKind.TIMER || card.kind == CardKind.NOTE -> null
        card.occursOn(day) -> DayEntry(card, card.dueMinute, card.completed && !card.repeatsAsEvent())
        else -> null
    }
}

private fun buildTimeline(timed: List<DayEntry>, nowMinute: Int?): List<TimelineRow> {
    if (timed.isEmpty()) return emptyList()
    val rows = mutableListOf<TimelineRow>()
    var nowPlaced = nowMinute == null
    fun gap(from: Int, to: Int) {
        if (!nowPlaced && nowMinute != null && nowMinute in from..to) {
            nowPlaced = true
            if (nowMinute - from >= 15) rows += TimelineRow.Gap(from, nowMinute)
            rows += TimelineRow.Now
            if (to - nowMinute >= 15) rows += TimelineRow.Gap(nowMinute, to)
        } else if (to - from >= 15) rows += TimelineRow.Gap(from, to)
    }
    var cursor = 0
    timed.forEach { entry ->
        val start = entry.minute ?: return@forEach
        if (start > cursor) gap(cursor, start)
        rows += TimelineRow.Item(entry)
        cursor = maxOf(cursor, start + maxOf(entry.card.durationMinutes, 1))
    }
    if (cursor < 24 * 60) gap(cursor, 24 * 60)
    if (!nowPlaced) rows += TimelineRow.Now
    return rows
}

@Composable
internal fun CalendarScreen(
    cards: List<BoardCard>,
    onImport: () -> Unit,
    onDelete: (BoardCard) -> Unit,
    onComplete: (BoardCard) -> Unit,
    onHabitCheckIn: (BoardCard) -> Unit,
    jump: Pair<Long, Int>? = null
) {
    val context = LocalContext.current
    var selectedDay by rememberSaveable { mutableStateOf(jump?.first ?: LocalDate.now().toEpochDay()) }
    // A day picked on the calendar widget (the counter lets the same day be requested twice).
    LaunchedEffect(jump) { jump?.let { selectedDay = it.first } }
    var pickDate by remember { mutableStateOf(false) }
    var nowTick by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000)
            nowTick = System.currentTimeMillis()
        }
    }
    val today = LocalDate.now().toEpochDay()
    val selectedDate = LocalDate.ofEpochDay(selectedDay)
    val weekStart = selectedDate.minusDays((selectedDate.dayOfWeek.value % 7).toLong())

    var drag by remember { mutableStateOf(0f) }
    // Swipe anywhere on the page to move a day back or forward.
    Column(
        Modifier.fillMaxSize().pointerInput(Unit) {
            detectHorizontalDragGestures(
                onDragStart = { drag = 0f },
                onDragEnd = { if (drag > 80f) selectedDay -= 1 else if (drag < -80f) selectedDay += 1 },
                onHorizontalDrag = { _, amount -> drag += amount }
            )
        }
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp).padding(top = 18.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Glyph(ZenIcon.Calendar, TodoText, 24.dp, modifier = Modifier.clip(CircleShape).clickable { pickDate = true }.padding(8.dp))
            Spacer(Modifier.weight(1f))
            Glyph(ZenIcon.ChevronLeft, TodoMuted, 20.dp, modifier = Modifier.clickable { selectedDay -= 1 }.padding(horizontal = 8.dp))
            Text(selectedDate.format(DateTimeFormatter.ofPattern("MMM yyyy", Locale.getDefault())), color = TodoText, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.clickable { selectedDay = today })
            Glyph(ZenIcon.ChevronRight, TodoMuted, 20.dp, modifier = Modifier.clickable { selectedDay += 1 }.padding(horizontal = 8.dp))
            Spacer(Modifier.weight(1f))
            Text("Import", color = CalendarBlue, fontSize = 14.sp, modifier = Modifier.clip(RoundedCornerShape(12.dp)).clickable(onClick = onImport).padding(horizontal = 8.dp, vertical = 10.dp))
            SettingsButton()
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            (0 until 7).forEach { offset ->
                val date = weekStart.plusDays(offset.toLong())
                val day = date.toEpochDay()
                val selected = day == selectedDay
                val dots = entriesFor(cards, day).take(5)
                val chip by animateColorAsState(if (selected) GlassAccent else TodoCard, tween(220), label = "dayChip")
                val ring by animateColorAsState(if (selected) CalendarBlue else Color(0x33FFFFFF), tween(220), label = "dayRing")
                Surface(
                    color = chip,
                    shape = RoundedCornerShape(16.dp),
                    border = BorderStroke(if (selected) 2.dp else 1.dp, ring),
                    modifier = Modifier.weight(1f).clip(RoundedCornerShape(16.dp)).clickable { selectedDay = day }
                ) {
                    Column(Modifier.padding(vertical = 9.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(date.format(DateTimeFormatter.ofPattern("EEE", Locale.getDefault())), color = if (selected) CalendarBlue else TodoText, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                        Text(date.dayOfMonth.toString(), color = if (selected) CalendarBlue else TodoText, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                        Row(Modifier.height(8.dp).padding(top = 3.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                            dots.forEach { Box(Modifier.size(5.dp).clip(CircleShape).background(entryColor(it.card))) }
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        // The day's timeline slides in from the side you swiped toward.
        AnimatedContent(
            targetState = selectedDay,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            transitionSpec = {
                val forward = targetState > initialState
                (slideInHorizontally(tween(300, easing = FastOutSlowInEasing)) { w -> if (forward) w / 4 else -w / 4 } + fadeIn(tween(240)))
                    .togetherWith(slideOutHorizontally(tween(300, easing = FastOutSlowInEasing)) { w -> if (forward) -w / 4 else w / 4 } + fadeOut(tween(160)))
            },
            label = "day"
        ) { day ->
            val entries = remember(cards, day) { entriesFor(cards, day) }
            val allDay = entries.filter { it.minute == null }.sortedBy { it.card.createdAt }
            val timed = entries.filter { it.minute != null }.sortedWith(compareBy<DayEntry> { it.minute }.thenBy { it.card.createdAt })
            val nowMinute = if (day == today) LocalTime.now().let { it.hour * 60 + it.minute } else null
            val rows = remember(timed, nowMinute, nowTick / 60_000) { buildTimeline(timed, nowMinute) }
            Column(Modifier.fillMaxSize()) {
            if (entries.isEmpty()) {
                Column(Modifier.weight(1f).fillMaxWidth().padding(bottom = 45.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    Glyph(ZenIcon.Calendar, CalendarBlue, 40.dp)
                    Spacer(Modifier.height(20.dp))
                    Text(if (day == today) "You have a free day" else "Nothing planned", color = TodoText, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(7.dp))
                    Text("Add a task with a time and it shows up here", color = TodoMuted, fontSize = 15.sp)
                }
            } else {
                LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(start = 12.dp, end = 14.dp, top = 6.dp, bottom = 24.dp)) {
                    if (allDay.isNotEmpty()) {
                        item(key = "all-day") {
                            Column(Modifier.padding(start = 4.dp, bottom = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("All day", color = TodoMuted, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                                allDay.forEach { TimelineCard(it, day == today, onDelete, onComplete, onHabitCheckIn) }
                            }
                        }
                    }
                    items(rows.size) { index ->
                        when (val row = rows[index]) {
                            is TimelineRow.Item -> TimelineSlot(formatClock(row.entry.minute!!), entryColor(row.entry.card)) {
                                TimelineCard(row.entry, day == today, onDelete, onComplete, onHabitCheckIn)
                            }
                            is TimelineRow.Gap -> TimelineSlot(formatClock(row.from % (24 * 60)), TodoMuted.copy(alpha = .5f)) {
                                Box(Modifier.fillMaxWidth().height(if (row.to - row.from >= 120) 84.dp else 60.dp).padding(vertical = 4.dp).clip(RoundedCornerShape(14.dp)).background(TodoCard.copy(alpha = .08f)), contentAlignment = Alignment.Center) {
                                    Text("${formatGap(row.to - row.from)} ${if (row.to == 24 * 60) "free" else "between tasks"}", color = TodoMuted.copy(alpha = .75f), fontSize = 15.sp)
                                }
                            }
                            TimelineRow.Now -> Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(formatClock(LocalTime.now().let { it.hour * 60 + it.minute }), color = Amber, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.width(64.dp))
                                Box(Modifier.weight(1f).height(2.dp).background(Amber))
                            }
                        }
                    }
                }
            }
            }
        }
    }
    LaunchedEffect(pickDate) {
        if (pickDate) {
            PlatformDatePickerDialog(context, { _, year, month, day ->
                selectedDay = LocalDate.of(year, month + 1, day).toEpochDay()
                pickDate = false
            }, selectedDate.year, selectedDate.monthValue - 1, selectedDate.dayOfMonth)
                .apply { setOnCancelListener { pickDate = false } }.show()
        }
    }
}

/** A time label on the left, a dotted rail in the middle, content on the right. */
@Composable
private fun TimelineSlot(label: String, railColor: Color, content: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
        Text(label.replace(" ", ""), color = TodoMuted, fontSize = 12.sp, maxLines = 1, modifier = Modifier.width(64.dp).padding(top = 14.dp))
        Canvas(Modifier.width(14.dp).fillMaxHeight()) {
            var y = 3.dp.toPx()
            while (y < size.height) {
                drawCircle(railColor, 2.dp.toPx(), Offset(size.width / 2, y))
                y += 9.dp.toPx()
            }
        }
        Spacer(Modifier.width(8.dp))
        Box(Modifier.weight(1f)) { content() }
    }
}

@Composable
private fun TimelineCard(
    entry: DayEntry,
    isToday: Boolean,
    onDelete: (BoardCard) -> Unit,
    onComplete: (BoardCard) -> Unit,
    onHabitCheckIn: (BoardCard) -> Unit
) {
    val card = entry.card
    val color = entryColor(card)
    val glyph = when { card.kind == CardKind.HABIT -> ZenIcon.Timer; card.workspace == Workspace.CALENDAR -> ZenIcon.Calendar; else -> ZenIcon.Checkbox }
    val isEvent = card.workspace == Workspace.CALENDAR
    val interactive = if (card.kind == CardKind.HABIT) isToday else !entry.done
    val timeLine = entry.minute?.let { start ->
        if (card.durationMinutes > 0) "${formatClock(start)} - ${formatClock((start + card.durationMinutes) % (24 * 60))} (${formatGap(card.durationMinutes)})" else formatClock(start)
    }
    val shape = RoundedCornerShape(18.dp)
    Surface(color = color.copy(alpha = .22f), shape = shape, border = GlassStroke, modifier = Modifier.glassBlur(shape).fillMaxWidth().padding(vertical = 4.dp)) {
        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(48.dp).clip(RoundedCornerShape(14.dp)).background(color), contentAlignment = Alignment.Center) {
                Glyph(glyph, Color.White, 22.dp)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(card.title, color = if (entry.done) TodoMuted else TodoText, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, textDecoration = if (entry.done) TextDecoration.LineThrough else null, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (timeLine != null) MetaLine(timeLine, TodoMuted, 13.sp, Modifier.padding(top = 2.dp), badges = badgesFor(card))
                else if (card.repeat.isNotBlank()) MetaLine(Repeat.fromId(card.repeat).label, TodoMuted, 13.sp, Modifier.padding(top = 2.dp), leading = ZenIcon.Repeat)
                else if (card.kind == CardKind.HABIT) Text("Daily habit", color = TodoMuted, fontSize = 13.sp, modifier = Modifier.padding(top = 2.dp))
            }
            if (isEvent) {
                Glyph(ZenIcon.Close, TodoMuted, 22.dp, modifier = Modifier.clickable { onDelete(card) }.padding(start = 8.dp, end = 6.dp))
            } else {
                Box(
                    Modifier.size(34.dp).clip(RoundedCornerShape(10.dp)).background(if (entry.done) color else Color.Transparent)
                        .border(2.dp, color.copy(alpha = if (interactive || entry.done) 1f else .4f), RoundedCornerShape(10.dp))
                        .clickable(enabled = interactive) { if (card.kind == CardKind.HABIT) onHabitCheckIn(card) else onComplete(card) },
                    contentAlignment = Alignment.Center
                ) { if (entry.done) Glyph(ZenIcon.Check, Color.White, 18.dp) }
            }
        }
    }
}
