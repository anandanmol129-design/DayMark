package com.anmol.daymark

import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import kotlinx.coroutines.flow.drop
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
import kotlinx.coroutines.launch
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

/** What the calendar page is showing, so the + button can add an event on that day. */
internal object CalendarUi {
    var shownDay by mutableStateOf<Long?>(null)
}

/** Weeks either side of this one that the week strip can be swiped to. */
private const val WEEK_PAGES = 4000
private const val WEEK_HOME = WEEK_PAGES / 2

/** The Sunday that starts [day]'s week. */
private fun weekStartOf(day: Long): Long = day - (LocalDate.ofEpochDay(day).dayOfWeek.value % 7)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CalendarScreen(
    cards: List<BoardCard>,
    onImport: () -> Unit,
    onDelete: (BoardCard) -> Unit,
    onComplete: (BoardCard) -> Unit,
    onHabitCheckIn: (BoardCard) -> Unit,
    onEdit: (BoardCard) -> Unit = {},
    jump: Pair<Long, Int>? = null
) {
    var selectedDay by rememberSaveable { mutableStateOf(jump?.first ?: LocalDate.now().toEpochDay()) }
    var pickDate by remember { mutableStateOf(false) }
    var nowTick by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000)
            nowTick = System.currentTimeMillis()
        }
    }
    SideEffect { CalendarUi.shownDay = selectedDay }
    val today = LocalDate.now().toEpochDay()
    val selectedDate = LocalDate.ofEpochDay(selectedDay)
    val haptics = LocalHapticFeedback.current

    val columnsMode = Layouts.columns(Layouts.CALENDAR)
    // List mode: one long scroll of days, each day's things in a card. The strip above follows the day at the top.
    val agendaDays = remember(cards, today) { agendaDays(cards, today) }
    val agendaState = androidx.compose.foundation.lazy.rememberLazyListState(initialFirstVisibleItemIndex = agendaIndex(agendaDays, selectedDay))
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    /** Picks [day]; in list mode also scrolls to it (or the next day with something on it). */
    fun go(day: Long) {
        selectedDay = day
        if (Layouts.columns(Layouts.CALENDAR)) return
        scope.launch { agendaState.animateScrollToItem(agendaIndex(agendaDays, day)) }
    }
    LaunchedEffect(columnsMode, agendaDays) {
        if (columnsMode) return@LaunchedEffect
        agendaState.scrollToItem(agendaIndex(agendaDays, selectedDay))
        androidx.compose.runtime.snapshotFlow { agendaState.firstVisibleItemIndex }.collect { i -> agendaDays.getOrNull(i)?.let { selectedDay = it } }
    }
    LaunchedEffect(jump) { jump?.let { go(it.first) } }

    // The week strip is a row of pages, one week each: swipe it to change week. Sideways swipes anywhere else on
    // the page fall through to the tabs (see swipeBetweenTabs), so the two never fight over a drag.
    val homeWeek = remember { weekStartOf(today) }
    fun pageOf(day: Long) = (WEEK_HOME + Math.floorDiv(weekStartOf(day) - homeWeek, 7L).toInt()).coerceIn(0, WEEK_PAGES - 1)
    val weeks = rememberPagerState(initialPage = pageOf(selectedDay)) { WEEK_PAGES }
    val latestDay = androidx.compose.runtime.rememberUpdatedState(selectedDay)
    LaunchedEffect(selectedDay) {
        val page = pageOf(selectedDay)
        if (weeks.currentPage != page && !weeks.isScrollInProgress) weeks.animateScrollToPage(page)
    }
    LaunchedEffect(weeks) {
        androidx.compose.runtime.snapshotFlow { weeks.settledPage }.drop(1).collect { page ->
            val day = latestDay.value
            if (page == pageOf(day)) return@collect
            // Same weekday in the week swiped to.
            haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
            go(homeWeek + (page - WEEK_HOME) * 7L + (day - weekStartOf(day)))
        }
    }
    val shownWeek = homeWeek + (weeks.currentPage - WEEK_HOME) * 7L

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp).padding(top = 18.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            HeaderIcon(ZenIcon.Calendar, "Go to date") { pickDate = true }
            Spacer(Modifier.weight(1f))
            Glyph(ZenIcon.ChevronLeft, TodoMuted, 20.dp, modifier = Modifier.clip(CircleShape).clickable { go(selectedDay - 7) }.padding(horizontal = 8.dp, vertical = 10.dp))
            Text(LocalDate.ofEpochDay(shownWeek + 3).format(DateTimeFormatter.ofPattern("MMM yyyy", Locale.getDefault())), color = TodoText, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.clip(RoundedCornerShape(10.dp)).clickable { go(today) }.padding(horizontal = 4.dp, vertical = 6.dp))
            Glyph(ZenIcon.ChevronRight, TodoMuted, 20.dp, modifier = Modifier.clip(CircleShape).clickable { go(selectedDay + 7) }.padding(horizontal = 8.dp, vertical = 10.dp))
            Spacer(Modifier.weight(1f))
            SyncButton(onImport)
            LayoutToggle(Layouts.CALENDAR)
            SettingsButton()
        }
        HorizontalPager(
            state = weeks,
            modifier = Modifier.fillMaxWidth(),
            beyondViewportPageCount = 1,
            key = { it }
        ) { page ->
            val weekStart = LocalDate.ofEpochDay(homeWeek + (page - WEEK_HOME) * 7L)
            Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                (0 until 7).forEach { offset ->
                    val date = weekStart.plusDays(offset.toLong())
                    val day = date.toEpochDay()
                    DayChip(date, day == selectedDay, day == today, remember(cards, day) { entriesFor(cards, day).filter { it.card.kind != CardKind.HABIT }.map { entryColor(it.card) } }, Modifier.weight(1f)) { go(day) }
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        androidx.compose.animation.Crossfade(columnsMode, Modifier.weight(1f).fillMaxWidth(), animationSpec = tween(260), label = "calendarLayout") { columnsShown ->
        if (!columnsShown) CalendarAgenda(cards, agendaDays, today, agendaState, onDelete, onComplete, onHabitCheckIn, onEdit)
        // The day's timeline slides in from the side of the day picked.
        else AnimatedContent(
            targetState = selectedDay,
            modifier = Modifier.fillMaxSize(),
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
                    Text("Tap + to add an event with a start and end", color = TodoMuted, fontSize = 15.sp)
                }
            } else {
                LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(start = 12.dp, end = 14.dp, top = 6.dp, bottom = 24.dp)) {
                    if (allDay.isNotEmpty()) {
                        item(key = "all-day") {
                            Column(Modifier.padding(start = 4.dp, bottom = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("All day", color = TodoMuted, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                                allDay.forEach { TimelineCard(it, day == today, onDelete, onComplete, onHabitCheckIn, onEdit) }
                            }
                        }
                    }
                    items(rows.size) { index ->
                        when (val row = rows[index]) {
                            is TimelineRow.Item -> TimelineSlot(Rail.Wave, entryColor(row.entry.card)) {
                                val start = row.entry.minute!!
                                val span = row.entry.card.durationMinutes
                                Column {
                                    Text(if (span > 0) "${formatClock(start)} – ${formatClock((start + span) % (24 * 60))}" else formatClock(start), color = TodoMuted, fontSize = 12.sp, fontWeight = FontWeight.Medium, maxLines = 1, modifier = Modifier.padding(start = 4.dp, top = 8.dp))
                                    TimelineCard(row.entry, day == today, onDelete, onComplete, onHabitCheckIn, onEdit)
                                }
                            }
                            is TimelineRow.Gap -> TimelineSlot(Rail.Dots, TodoMuted.copy(alpha = .5f)) {
                                Box(Modifier.fillMaxWidth().height(if (row.to - row.from >= 120) 72.dp else 52.dp).padding(vertical = 6.dp).clip(RoundedCornerShape(14.dp)).background(TodoCard.copy(alpha = .08f)), contentAlignment = Alignment.Center) {
                                    Text("${formatGap(row.to - row.from)} ${if (row.to == 24 * 60) "free" else "between tasks"}", color = TodoMuted.copy(alpha = .75f), fontSize = 14.sp)
                                }
                            }
                            TimelineRow.Now -> Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.width(14.dp), contentAlignment = Alignment.Center) { Box(Modifier.size(8.dp).clip(CircleShape).background(Amber)) }
                                Text(formatClock(LocalTime.now().let { it.hour * 60 + it.minute }), color = Amber, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 8.dp))
                                Box(Modifier.weight(1f).height(2.dp).background(Amber))
                            }
                        }
                    }
                }
            }
            }
        }
        }
    }
    if (pickDate) GearDateSheet(selectedDate, onChange = { if (it.toEpochDay() != latestDay.value) go(it.toEpochDay()) }, onDismiss = { pickDate = false })
}

/** Sync with the phone's calendars; the arrows turn once each time it's tapped. */
@Composable
private fun SyncButton(onSync: () -> Unit) {
    val turn = remember { androidx.compose.animation.core.Animatable(0f) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    Box(Modifier.graphicsLayer { rotationZ = turn.value }) {
        HeaderIcon(ZenIcon.Sync, "Sync calendar", CalendarBlue) {
            onSync()
            scope.launch { turn.snapTo(0f); turn.animateTo(360f, tween(700, easing = FastOutSlowInEasing)) }
        }
    }
}

/** A day in the week strip: weekday, date, and its events as a small polygon of dots under it. */
@Composable
private fun DayChip(date: LocalDate, selected: Boolean, isToday: Boolean, colors: List<Color>, modifier: Modifier, onClick: () -> Unit) {
    val chip by animateColorAsState(if (selected) GlassAccent else TodoCard, tween(220), label = "dayChip")
    val ring by animateColorAsState(if (selected) CalendarBlue else if (isToday) CalendarBlue.copy(alpha = .5f) else veil(0.20f), tween(220), label = "dayRing")
    Surface(
        color = chip,
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(if (selected) 2.dp else 1.dp, ring),
        modifier = modifier.clip(RoundedCornerShape(16.dp)).clickable(onClick = onClick)
    ) {
        Column(Modifier.padding(top = 8.dp, bottom = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(date.format(DateTimeFormatter.ofPattern("EEE", Locale.getDefault())), color = if (selected) CalendarBlue else TodoText, fontSize = 12.sp, fontWeight = FontWeight.Medium)
            Text(date.dayOfMonth.toString(), color = if (selected) CalendarBlue else TodoText, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            EventPolygon(colors, Modifier.padding(top = 3.dp).size(20.dp))
        }
    }
}

/**
 * A day's events as dots at the corners of a regular polygon, one dot per event in its colour: one is a dot, two a
 * pair, three a triangle, and so on up to an octagon; past eight they sit round a circle. Each edge blends from one
 * event's colour to the next.
 */
@Composable
internal fun EventPolygon(colors: List<Color>, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val n = colors.size
        if (n == 0) return@Canvas
        val center = Offset(size.width / 2, size.height / 2)
        if (n == 1) { drawCircle(colors[0], 2.6.dp.toPx(), center); return@Canvas }
        val shown = colors.take(24)
        val count = shown.size
        val radius = size.minDimension / 2 - 2.6.dp.toPx()
        val dot = when { count > 8 -> 1.5.dp.toPx(); count > 5 -> 2.dp.toPx(); else -> 2.3.dp.toPx() }
        // Two dots lie side by side; otherwise the first corner points up.
        val startAngle = if (count == 2) 0.0 else -Math.PI / 2
        val points = List(count) { i ->
            val a = startAngle + 2 * Math.PI * i / count
            Offset(center.x + radius * kotlin.math.cos(a).toFloat(), center.y + radius * kotlin.math.sin(a).toFloat())
        }
        if (count > 8) {
            // A circle: each event's colour along its own stretch of the ring.
            val sweep = 360f / count
            shown.forEachIndexed { i, c ->
                drawArc(c.copy(alpha = .75f), -90f + sweep * i, sweep, false, Offset(center.x - radius, center.y - radius), androidx.compose.ui.geometry.Size(radius * 2, radius * 2), style = Stroke(1.dp.toPx()))
            }
        } else {
            val edges = if (count == 2) 1 else count
            for (i in 0 until edges) {
                val a = points[i]
                val b = points[(i + 1) % count]
                drawLine(Brush.linearGradient(listOf(shown[i].copy(alpha = .8f), shown[(i + 1) % count].copy(alpha = .8f)), a, b), a, b, 1.1.dp.toPx(), cap = StrokeCap.Round)
            }
        }
        points.forEachIndexed { i, p -> drawCircle(shown[i], dot, p) }
    }
}

private enum class Rail { Wave, Dots }

/** A rail down the left, then the content: a wavy line in the item's colour beside an item, grey dots beside free time. */
@Composable
private fun TimelineSlot(rail: Rail, railColor: Color, content: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
        RailLine(rail, railColor, Modifier.width(14.dp).fillMaxHeight())
        Spacer(Modifier.width(10.dp))
        Box(Modifier.weight(1f)) { content() }
    }
}

/** The rail itself: a wavy line (an item) or a column of grey dots (free time), down the full height. */
@Composable
private fun RailLine(rail: Rail, railColor: Color, modifier: Modifier) {
        Canvas(modifier) {
            val x = size.width / 2
            when (rail) {
                Rail.Wave -> {
                    val amp = 3.dp.toPx()
                    val wave = 14.dp.toPx()
                    val top = 4.dp.toPx()
                    val bottom = size.height - 4.dp.toPx()
                    val path = Path().apply {
                        moveTo(x, top)
                        var y = top
                        val step = 1.5.dp.toPx()
                        while (y < bottom) {
                            y = (y + step).coerceAtMost(bottom)
                            lineTo(x + amp * kotlin.math.sin(2 * Math.PI * (y - top) / wave).toFloat(), y)
                        }
                    }
                    drawPath(path, railColor, style = Stroke(2.4.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
                }
                Rail.Dots -> {
                    var y = 3.dp.toPx()
                    while (y < size.height) {
                        drawCircle(railColor, 1.6.dp.toPx(), Offset(x, y))
                        y += 8.dp.toPx()
                    }
                }
            }
        }
}

@Composable
private fun TimelineCard(
    entry: DayEntry,
    isToday: Boolean,
    onDelete: (BoardCard) -> Unit,
    onComplete: (BoardCard) -> Unit,
    onHabitCheckIn: (BoardCard) -> Unit,
    onEdit: (BoardCard) -> Unit
) {
    val card = entry.card
    val color = entryColor(card)
    val glyph = when { card.kind == CardKind.HABIT -> ZenIcon.Timer; card.workspace == Workspace.CALENDAR -> ZenIcon.Calendar; else -> ZenIcon.Checkbox }
    val isEvent = card.workspace == Workspace.CALENDAR
    val interactive = if (card.kind == CardKind.HABIT) isToday else !entry.done
    // The times sit above the card; inside, under the name, how long it lasts.
    val span = if (entry.minute != null && card.durationMinutes > 0) formatSpan(card.durationMinutes) else null
    val shape = RoundedCornerShape(18.dp)
    Surface(color = color.copy(alpha = .22f), shape = shape, border = GlassStroke, modifier = Modifier.padding(vertical = 4.dp).glassBlur(shape).fillMaxWidth().clip(shape).clickable { onEdit(card) }) {
        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(48.dp).clip(RoundedCornerShape(14.dp)).background(color), contentAlignment = Alignment.Center) {
                Glyph(glyph, Color.White, 22.dp)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(card.title, color = if (entry.done) TodoMuted else TodoText, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, textDecoration = if (entry.done) TextDecoration.LineThrough else null, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (span != null) MetaLine(span, TodoMuted, 13.sp, Modifier.padding(top = 2.dp), leading = ZenIcon.Clock, badges = badgesFor(card))
                else if (card.repeat.isNotBlank()) MetaLine(Repeat.fromId(card.repeat).label, TodoMuted, 13.sp, Modifier.padding(top = 2.dp), leading = ZenIcon.Repeat)
                else if (card.kind == CardKind.HABIT) Text("Daily habit", color = TodoMuted, fontSize = 13.sp, modifier = Modifier.padding(top = 2.dp))
                else if (badgesFor(card).isNotEmpty()) MetaLine("", TodoMuted, 13.sp, Modifier.padding(top = 2.dp), badges = badgesFor(card))
            }
            if (isEvent) {
                TapTwiceTrash({ onDelete(card) }, size = 22.dp)
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

/**
 * The days list mode shows: today and the two weeks after it always (empty ones say so), and further
 * out, two months back to a year ahead, only the days with something on them.
 */
private fun agendaDays(cards: List<BoardCard>, today: Long): List<Long> =
    (today - 60..today + 365).filter { day -> day in today..today + 13 || agendaEntries(cards, day, today).isNotEmpty() }

/** Where [day] sits in the list: its own row, or the next day that has something on it. */
private fun agendaIndex(days: List<Long>, day: Long): Int = days.indexOfFirst { it >= day }.let { if (it < 0) days.lastIndex.coerceAtLeast(0) else it }

/** A day's things for the list; habits only on today, since they would repeat on every day. */
private fun agendaEntries(cards: List<BoardCard>, day: Long, today: Long): List<DayEntry> {
    val entries = entriesFor(cards, day).filter { day == today || it.card.kind != CardKind.HABIT }
    return entries.filter { it.minute == null }.sortedBy { it.card.createdAt } +
        entries.filter { it.minute != null }.sortedWith(compareBy<DayEntry> { it.minute }.thenBy { it.card.createdAt })
}

/** Calendar as one list: each day's name, then its things in a card, all-day ones first. */
@Composable
private fun CalendarAgenda(
    cards: List<BoardCard>,
    days: List<Long>,
    today: Long,
    state: androidx.compose.foundation.lazy.LazyListState,
    onDelete: (BoardCard) -> Unit,
    onComplete: (BoardCard) -> Unit,
    onHabitCheckIn: (BoardCard) -> Unit,
    onEdit: (BoardCard) -> Unit
) {
    LazyColumn(Modifier.fillMaxSize(), state = state, contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 4.dp, bottom = 24.dp)) {
        items(days, key = { it }) { day ->
            val entries = remember(cards, day, today) { agendaEntries(cards, day, today) }
            val date = LocalDate.ofEpochDay(day)
            val name = date.format(DateTimeFormatter.ofPattern("EEE d MMM", Locale.getDefault()))
            Column(Modifier.padding(bottom = 18.dp)) {
                SectionLabel(
                    when (day) { today -> "Today · $name"; today + 1 -> "Tomorrow · $name"; today - 1 -> "Yesterday · $name"; else -> name },
                    entries.count { !it.done }.takeIf { it > 0 }?.toString(), if (day == today) CalendarBlue else TodoMuted
                )
                SectionCard {
                    if (entries.isEmpty()) Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        RailLine(Rail.Dots, TodoMuted.copy(alpha = .5f), Modifier.width(12.dp).fillMaxHeight())
                        Text("Nothing planned", color = TodoMuted, fontSize = 15.sp, modifier = Modifier.padding(start = 12.dp))
                    }
                    entries.forEachIndexed { i, entry ->
                        key(entry.card.id) {
                            if (i > 0) RowDivider()
                            AgendaRow(entry, day == today, onDelete, onComplete, onHabitCheckIn, onEdit)
                        }
                    }
                }
            }
        }
        // Room after the last day, so even it can scroll up to the top (and the strip can follow it there).
        item(key = "end") { Spacer(Modifier.fillParentMaxHeight(.7f)) }
    }
}

/** One thing on a day in list mode: its time, a stripe of its colour, the title, and its check (or a trash for events). */
@Composable
private fun AgendaRow(entry: DayEntry, isToday: Boolean, onDelete: (BoardCard) -> Unit, onComplete: (BoardCard) -> Unit, onHabitCheckIn: (BoardCard) -> Unit, onEdit: (BoardCard) -> Unit) {
    val card = entry.card
    val color = entryColor(card)
    val isEvent = card.workspace == Workspace.CALENDAR
    val interactive = if (card.kind == CardKind.HABIT) isToday else !entry.done
    val meta = when {
        entry.minute != null && card.durationMinutes > 0 -> formatSpan(card.durationMinutes)
        card.repeat.isNotBlank() -> Repeat.fromId(card.repeat).label
        card.kind == CardKind.HABIT -> "Daily habit"
        else -> ""
    }
    // Start and end above the name, how long it lasts under it, and a wavy line in its colour down the side.
    val times = entry.minute?.let { start -> if (card.durationMinutes > 0) "${formatClock(start)} – ${formatClock((start + card.durationMinutes) % (24 * 60))}" else formatClock(start) } ?: "All day"
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).clickable { onEdit(card) }.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        RailLine(Rail.Wave, color, Modifier.width(12.dp).fillMaxHeight())
        Column(Modifier.weight(1f).padding(start = 12.dp, end = 8.dp)) {
            Text(times, color = TodoMuted, fontSize = 12.sp, fontWeight = FontWeight.Medium, maxLines = 1)
            Text(card.title, color = if (entry.done) TodoMuted else TodoText, fontSize = 16.sp, textDecoration = if (entry.done) TextDecoration.LineThrough else null, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (meta.isNotEmpty() || badgesFor(card).isNotEmpty()) MetaLine(meta, TodoMuted, 13.sp, Modifier.padding(top = 2.dp), badges = badgesFor(card))
        }
        if (isEvent) TapTwiceTrash({ onDelete(card) }, size = 20.dp)
        else Box(
            Modifier.size(28.dp).clip(RoundedCornerShape(9.dp)).background(if (entry.done) color else Color.Transparent)
                .border(2.dp, color.copy(alpha = if (interactive || entry.done) 1f else .4f), RoundedCornerShape(9.dp))
                .clickable(enabled = interactive) { if (card.kind == CardKind.HABIT) onHabitCheckIn(card) else onComplete(card) },
            contentAlignment = Alignment.Center
        ) { if (entry.done) Glyph(ZenIcon.Check, Color.White, 16.dp) }
    }
}
