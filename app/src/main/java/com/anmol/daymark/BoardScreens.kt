package com.anmol.daymark

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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.runtime.key
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import kotlinx.coroutines.launch
import java.time.LocalDate

@Composable
internal fun BoardScreen(
    workspace: Workspace,
    onNewPage: () -> Unit,
    onDeletePage: (BoardPage) -> Unit,
    onMovePage: (String, Int) -> Unit,
    onRenamePage: (BoardPage) -> Unit,
    onEditCard: (BoardCard) -> Unit,
    snapshot: BoardSnapshot,
    timerTick: Long,
    selectedColumnId: String,
    onSelectColumn: (String) -> Unit,
    selectedTodoPageId: String,
    onSelectTodoPage: (String) -> Unit,
    selectedNotesPageId: String,
    onSelectNotesPage: (String) -> Unit,
    onSelectView: (BoardView) -> Unit,
    onAddColumn: () -> Unit,
    onMoveCard: (BoardCard, BoardColumn) -> Unit,
    onToggleImportant: (BoardCard) -> Unit,
    onComplete: (BoardCard) -> Unit,
    onReopen: (BoardCard) -> Unit,
    onHabitCheckIn: (BoardCard) -> Unit,
    onToggleTimer: (BoardCard) -> Unit,
    onLaunchApp: (BoardCard) -> Unit,
    onDelete: (BoardCard) -> Unit,
    onVoice: () -> Unit,
    onAddCard: (BoardColumn) -> Unit,
    onPickImages: () -> Unit,
    onImportCalendar: () -> Unit,
    calendarJump: Pair<Long, Int>? = null,
    privateOpen: Boolean = false,
    onTogglePrivate: (BoardCard) -> Unit = {},
    onLockPrivate: () -> Unit = {}
) {
    val columns = snapshot.columnsFor(workspace)
    if (workspace == Workspace.CALENDAR) {
        CalendarScreen(
            cards = snapshot.cards,
            onImport = onImportCalendar,
            onDelete = onDelete,
            onComplete = onComplete,
            onHabitCheckIn = onHabitCheckIn,
            jump = calendarJump
        )
        return
    }
    if (workspace == Workspace.TODOS) {
        GroupedTaskListScreen(
            pages = snapshot.todoPages,
            allColumns = columns,
            cards = snapshot.cardsFor(workspace),
            selectedPageId = selectedTodoPageId,
            onSelectPage = onSelectTodoPage,
            onNewPage = onNewPage,
            onMovePage = onMovePage,
            onRenamePage = onRenamePage,
            onDeletePage = onDeletePage,
            onEditCard = onEditCard,
            onComplete = onComplete,
            onReopen = onReopen,
            onToggleImportant = onToggleImportant,
            onMoveCard = onMoveCard,
            onDelete = onDelete,
            onVoice = onVoice
        )
        return
    }
    if (workspace == Workspace.NOTES) {
        NotesPagesScreen(
            pages = snapshot.notesPages,
            allColumns = columns,
            cards = snapshot.cardsFor(workspace),
            selectedPageId = selectedNotesPageId,
            onSelectPage = onSelectNotesPage,
            onNewPage = onNewPage,
            onDeletePage = onDeletePage,
            onEditCard = onEditCard,
            onPickImages = onPickImages,
            onAddCard = onAddCard,
            onMoveCard = onMoveCard,
            onToggleImportant = onToggleImportant,
            onDelete = onDelete,
            privateOpen = privateOpen,
            onTogglePrivate = onTogglePrivate,
            onLockPrivate = onLockPrivate
        )
        return
    }
    val view = snapshot.views[workspace] ?: BoardView.BOARD
    val selectedColumn = columns.firstOrNull { it.id == selectedColumnId }?.id ?: columns.firstOrNull()?.id.orEmpty()
    if (view == BoardView.LIST) {
        ColumnListScreen(
                workspace = workspace,
                snapshot = snapshot,
                timerTick = timerTick,
                onSwitchView = { onSelectView(BoardView.BOARD) },
                onAddColumn = onAddColumn,
                onAddCard = onAddCard,
                onMoveCard = onMoveCard,
                onToggleImportant = onToggleImportant,
                onComplete = onComplete,
                onHabitCheckIn = onHabitCheckIn,
                onToggleTimer = onToggleTimer,
                onLaunchApp = onLaunchApp,
                onDelete = onDelete
            )
        return
    }

    if (workspace == Workspace.TODOS) {
        TaskInboxScreen(
            columns = columns,
            cards = snapshot.cardsFor(workspace),
            selectedColumnId = selectedColumn,
            onSelectColumn = onSelectColumn,
            onAddColumn = onAddColumn,
            onAddCard = onAddCard,
            onComplete = onComplete,
            onToggleImportant = onToggleImportant,
            onMoveCard = onMoveCard,
            onDelete = onDelete,
            onVoice = onVoice,
            onSwitchView = { onSelectView(BoardView.LIST) }
        )
    } else {
        SingleColumnBoardScreen(
            workspace = workspace,
            snapshot = snapshot,
            timerTick = timerTick,
            columns = columns,
            selectedColumnId = selectedColumn,
            onSelectColumn = onSelectColumn,
            onAddColumn = onAddColumn,
            onMoveCard = onMoveCard,
            onToggleImportant = onToggleImportant,
            onComplete = onComplete,
            onHabitCheckIn = onHabitCheckIn,
            onToggleTimer = onToggleTimer,
            onLaunchApp = onLaunchApp,
            onDelete = onDelete,
            onAddCard = { column -> onAddCard(column) },
            onSwitchView = { onSelectView(BoardView.LIST) }
        )
    }
}

@Composable
private fun SingleColumnBoardScreen(
    workspace: Workspace,
    snapshot: BoardSnapshot,
    timerTick: Long,
    columns: List<BoardColumn>,
    selectedColumnId: String,
    onSelectColumn: (String) -> Unit,
    onAddColumn: () -> Unit,
    onMoveCard: (BoardCard, BoardColumn) -> Unit,
    onToggleImportant: (BoardCard) -> Unit,
    onComplete: (BoardCard) -> Unit,
    onHabitCheckIn: (BoardCard) -> Unit,
    onToggleTimer: (BoardCard) -> Unit,
    onLaunchApp: (BoardCard) -> Unit,
    onDelete: (BoardCard) -> Unit,
    onAddCard: (BoardColumn) -> Unit,
    onSwitchView: () -> Unit
) {
    if (columns.isEmpty()) return
    val initialPage = columns.indexOfFirst { it.id == selectedColumnId }.coerceAtLeast(0)
    val pagerState = rememberPagerState(initialPage = initialPage, pageCount = { columns.size })
    val scope = rememberCoroutineScope()
    LaunchedEffect(selectedColumnId, columns.size) {
        val page = columns.indexOfFirst { it.id == selectedColumnId }
        if (page >= 0 && page != pagerState.currentPage) pagerState.animateScrollToPage(page)
    }
    LaunchedEffect(pagerState, columns.size) {
        androidx.compose.runtime.snapshotFlow { pagerState.currentPage }.collect { page ->
            columns.getOrNull(page)?.let { onSelectColumn(it.id) }
        }
    }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("DAYMARK · ${todayLabel()}", color = Green, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.4.sp)
                Spacer(Modifier.height(5.dp))
                Text(workspace.title, color = Ink, fontSize = 30.sp, lineHeight = 35.sp, fontWeight = FontWeight.SemiBold)
            }
            ViewToggle(BoardView.BOARD, onSwitchView)
            SettingsButton()
        }
        Text(workspaceSubtitle(workspace, snapshot.cardsFor(workspace)), color = Muted, fontSize = 13.sp, modifier = Modifier.padding(start = 18.dp, top = 5.dp, bottom = 3.dp))
        Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp), verticalAlignment = Alignment.CenterVertically) {
            DropdownChoice(label = columns.getOrNull(pagerState.currentPage)?.name ?: columns.first().name, choices = columns.map { it.name }) { name ->
                columns.firstOrNull { it.name == name }?.let { target -> scope.launch { pagerState.animateScrollToPage(columns.indexOf(target)) } }
            }
            TextButton(onClick = onAddColumn, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)) { Text("Add column", color = Green, fontSize = 12.sp) }
        }
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 14.dp),
            pageSpacing = 12.dp,
            key = { page -> columns[page].id }
        ) { page ->
            val column = columns[page]
            Box(Modifier.fillMaxSize().padding(horizontal = 4.dp)) {
                BoardColumnCard(column, snapshot, timerTick, onMoveCard, onToggleImportant, onComplete, onHabitCheckIn, onToggleTimer, onLaunchApp, onDelete, { onAddCard(column) })
            }
        }
        ColumnPageIndicator(
            columns = columns,
            currentPage = pagerState.currentPage,
            darkTheme = true,
            onSelectPage = { page -> scope.launch { pagerState.animateScrollToPage(page) } },
            onAddColumn = onAddColumn
        )
    }
}

@Composable
internal fun ColumnPageIndicator(
    columns: List<BoardColumn>,
    currentPage: Int,
    darkTheme: Boolean,
    onSelectPage: (Int) -> Unit,
    onAddColumn: () -> Unit
) {
    val dotColor = if (darkTheme) TodoMuted else Muted
    Row(
        Modifier.fillMaxWidth().padding(top = 5.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        columns.forEachIndexed { index, column ->
            Box(
                Modifier.padding(horizontal = 3.dp).size(if (index == currentPage) 9.dp else 7.dp)
                    .clip(CircleShape)
                    .background(if (index == currentPage) (if (darkTheme) TodoText else Green) else dotColor)
                    .clickable { onSelectPage(index) }
            )
        }
        Glyph(ZenIcon.Plus, if (darkTheme) TodoBlue else Green, 16.dp, modifier = Modifier.clickable(onClick = onAddColumn).padding(start = 4.dp, end = 3.dp))
    }
}

@Composable
internal fun BoardColumnCard(
    column: BoardColumn,
    snapshot: BoardSnapshot,
    timerTick: Long,
    onMoveCard: (BoardCard, BoardColumn) -> Unit,
    onToggleImportant: (BoardCard) -> Unit,
    onComplete: (BoardCard) -> Unit,
    onHabitCheckIn: (BoardCard) -> Unit,
    onToggleTimer: (BoardCard) -> Unit,
    onLaunchApp: (BoardCard) -> Unit,
    onDelete: (BoardCard) -> Unit,
    onAddCard: () -> Unit
) {
    val cards = snapshot.cards.filter { it.columnId == column.id }.sortedWith(compareBy<BoardCard> { !it.important }.thenBy { it.dueDay ?: Long.MAX_VALUE }.thenByDescending { it.createdAt })
    Surface(color = TodoCard, shape = RoundedCornerShape(20.dp), border = GlassStroke, modifier = Modifier.glassBlur(RoundedCornerShape(20.dp)).fillMaxWidth()) {
        Column(Modifier.padding(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(column.name, color = Ink, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Text(cards.size.toString(), color = Muted, fontSize = 10.sp)
            }
            Spacer(Modifier.height(8.dp))
            if (cards.isEmpty()) {
                Text("Nothing here yet", color = Muted, fontSize = 11.sp, lineHeight = 15.sp, modifier = Modifier.padding(vertical = 8.dp))
                TextButton(onClick = onAddCard, contentPadding = PaddingValues(horizontal = 0.dp, vertical = 0.dp)) { Text("Add", color = Green, fontSize = 11.sp) }
            }
            cards.forEach { card ->
                BoardCardTile(
                    card = card,
                    columns = snapshot.columnsFor(card.workspace),
                    timerTick = timerTick,
                    onMove = { onMoveCard(card, it) },
                    onToggleImportant = { onToggleImportant(card) },
                    onComplete = { onComplete(card) },
                    onHabitCheckIn = { onHabitCheckIn(card) },
                    onToggleTimer = { onToggleTimer(card) },
                    onLaunchApp = { onLaunchApp(card) },
                    onDelete = { onDelete(card) }
                )
                Spacer(Modifier.height(7.dp))
            }
        }
    }
}

@Composable
internal fun BoardCardTile(
    card: BoardCard,
    columns: List<BoardColumn>,
    timerTick: Long,
    onMove: (BoardColumn) -> Unit,
    onToggleImportant: () -> Unit,
    onComplete: () -> Unit,
    onHabitCheckIn: () -> Unit,
    onToggleTimer: () -> Unit,
    onLaunchApp: () -> Unit,
    onDelete: () -> Unit,
    onClick: (() -> Unit)? = null
) {
    var menuExpanded by remember(card.id) { mutableStateOf(false) }
    val today = LocalDate.now().toEpochDay()
    val checkedToday = card.checkedOn(today)
    val streak = currentStreak(card.checkIns, today)
    var showHistory by remember(card.id) { mutableStateOf(false) }
    val toggleDay = LocalHabitDayToggle.current
    Surface(
        color = TodoCardRaised, shape = RoundedCornerShape(15.dp), border = GlassStroke,
        modifier = Modifier.glassBlur(RoundedCornerShape(15.dp)).fillMaxWidth().then(if (onClick != null) Modifier.clip(RoundedCornerShape(15.dp)).clickable(onClick = onClick) else Modifier)
    ) {
        Column(Modifier.padding(10.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    Text(card.displayTitle, color = Ink, fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium)
                    if (card.important) MetaLine("Priority", Amber, 10.sp, Modifier.padding(top = 4.dp), leading = ZenIcon.StarFilled, weight = FontWeight.SemiBold)
                }
                Box {
                    Glyph(ZenIcon.More, Muted, 18.dp, modifier = Modifier.clip(CircleShape).clickable { menuExpanded = true }.padding(start = 5.dp))
                    DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                        if (card.kind != CardKind.HABIT && card.kind != CardKind.TIMER) {
                            DropdownMenuItem(text = { Text(if (card.important) "Remove priority" else "Mark important") }, onClick = { onToggleImportant(); menuExpanded = false })
                        }
                        columns.filter { it.id != card.columnId }.forEach { column ->
                            DropdownMenuItem(text = { Text("Move to ${column.name}") }, onClick = { onMove(column); menuExpanded = false })
                        }
                        if (card.kind == CardKind.HABIT) LocalEditHabit.current?.let { edit ->
                            DropdownMenuItem(text = { Text("Edit time & reminder") }, onClick = { edit(card); menuExpanded = false })
                        }
                        DropdownMenuItem(text = { Text("Delete") }, onClick = { onDelete(); menuExpanded = false })
                    }
                }
            }
            if (card.details.isNotBlank()) {
                Spacer(Modifier.height(5.dp))
                if (card.kind == CardKind.NOTE) MarkdownText(card.details, maxBlocks = 10)
                else Text(card.details, color = Muted, fontSize = 11.sp, lineHeight = 15.sp, maxLines = 4, overflow = TextOverflow.Ellipsis)
            }
            if (card.dueDay != null) {
                Spacer(Modifier.height(5.dp))
                MetaLine(formatDue(card.dueDay), if (card.kind == CardKind.EVENT) Blue else Muted, 10.sp, leading = if (card.kind == CardKind.EVENT) ZenIcon.Calendar else null, badges = badgesFor(card))
            }
            if (card.kind == CardKind.TIMER) {
                Spacer(Modifier.height(7.dp))
                Text(if (card.activeSinceMillis != null) "Running · ${formatDuration(card.trackedMillis + (timerTick - card.activeSinceMillis))}" else "Tracked · ${formatDuration(card.trackedMillis)}", color = if (card.activeSinceMillis != null) Green else Muted, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                if (card.appLabel.isNotBlank()) MetaLine(card.appLabel, Muted, 10.sp, Modifier.padding(top = 3.dp), leading = ZenIcon.ArrowUpRight)
                TextButton(onClick = onToggleTimer, contentPadding = PaddingValues(horizontal = 0.dp, vertical = 0.dp)) {
                    MetaLine(if (card.activeSinceMillis == null) "Start session" else "Stop session", Green, 11.sp, leading = if (card.activeSinceMillis == null) ZenIcon.Play else ZenIcon.Stop)
                }
            } else if (card.kind == CardKind.HABIT) {
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = onHabitCheckIn, contentPadding = PaddingValues(horizontal = 0.dp, vertical = 0.dp)) {
                        MetaLine(if (checkedToday) "Done today" else "Check in today", if (checkedToday) Green else Muted, 11.sp, leading = if (checkedToday) ZenIcon.CircleCheck else ZenIcon.Timer)
                    }
                    Spacer(Modifier.weight(1f))
                    if (card.remind.isNotBlank() && card.dueMinute != null) MetaLine(formatClock(card.dueMinute) + "  ", Muted, 10.sp, leading = reminderIcon(card.remind))
                    if (streak > 0) MetaLine("$streak", Amber, 11.sp, leading = ZenIcon.Flame, weight = FontWeight.SemiBold)
                    Glyph(if (showHistory) ZenIcon.ChevronUp else ZenIcon.ChevronDown, Muted, 14.dp, modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { showHistory = !showHistory }.padding(horizontal = 8.dp, vertical = 2.dp))
                }
                if (showHistory) HabitHistory(card, today) { day -> toggleDay(card, day) }
            } else if (card.kind == CardKind.TASK && !card.completed) {
                Spacer(Modifier.height(3.dp))
                TextButton(onClick = onComplete, contentPadding = PaddingValues(horizontal = 0.dp, vertical = 0.dp)) { MetaLine("Complete", Green, 10.sp, leading = ZenIcon.CircleCheck) }
            } else if (card.kind == CardKind.NOTE) {
                Spacer(Modifier.height(2.dp))
                Text("NOTE", color = Blue, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
            }
        }
    }
}

@Composable
private fun DropdownChoice(label: String, choices: List<String>, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        Row(Modifier.clip(RoundedCornerShape(9.dp)).clickable { expanded = true }.padding(horizontal = 9.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(label, color = Green, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
            Glyph(ZenIcon.ChevronDown, Green, 12.dp, modifier = Modifier.padding(start = 4.dp))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            choices.forEach { choice -> DropdownMenuItem(text = { Text(choice) }, onClick = { onSelect(choice); expanded = false }) }
        }
    }
}

/** Handler for toggling a habit's check-in on any past day; provided by [DaymarkApp]. */
internal val LocalHabitDayToggle = androidx.compose.runtime.staticCompositionLocalOf<(BoardCard, Long) -> Unit> { { _, _ -> } }

/** Streak stats and a five-week grid (Sunday first, today outlined); tapping a day up to today toggles its check-in. */
@Composable
private fun HabitHistory(card: BoardCard, today: Long, onToggleDay: (Long) -> Unit) {
    val done = currentStreak(card.checkIns, today)
    val last30 = completionsInLast30(card.checkIns, today)
    val weekStart = today - (LocalDate.ofEpochDay(today).dayOfWeek.value % 7) - 28
    Column(Modifier.padding(top = 4.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            listOf("Current" to "$done", "Best" to "${bestStreak(card.checkIns)}", "30 days" to "${last30 * 100 / 30}%").forEach { (label, value) ->
                Column {
                    Text(value, color = Ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    Text(label, color = Muted, fontSize = 9.sp)
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        (0 until 5).forEach { week ->
            Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                (0 until 7).forEach { weekday ->
                    val day = weekStart + week * 7 + weekday
                    val future = day > today
                    val checked = card.checkedOn(day)
                    Box(
                        Modifier.size(22.dp).clip(CircleShape)
                            .background(if (checked) Green else Color.Transparent)
                            .border(if (day == today) 2.dp else 1.dp, if (day == today) Ink else Muted.copy(alpha = if (checked || future) 0f else .45f), CircleShape)
                            .clickable(enabled = !future) { onToggleDay(day) },
                        contentAlignment = Alignment.Center
                    ) { if (checked) Glyph(ZenIcon.Check, Color.White, 12.dp) }
                }
            }
        }
    }
}
