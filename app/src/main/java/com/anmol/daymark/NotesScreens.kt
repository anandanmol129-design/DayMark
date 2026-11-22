package com.anmol.daymark

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.ui.draw.clip
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.foundation.background
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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.runtime.key
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

@Composable
internal fun NotesPagesScreen(
    pages: List<BoardPage>,
    allColumns: List<BoardColumn>,
    cards: List<BoardCard>,
    selectedPageId: String,
    onSelectPage: (String) -> Unit,
    onNewPage: () -> Unit,
    onDeletePage: (BoardPage) -> Unit,
    onRenamePage: (BoardPage) -> Unit = {},
    onEditCard: (BoardCard) -> Unit,
    onPickImages: () -> Unit,
    onAddCard: (BoardColumn) -> Unit,
    onMoveCard: (BoardCard, BoardColumn) -> Unit,
    onToggleImportant: (BoardCard) -> Unit,
    onDelete: (BoardCard) -> Unit,
    privateOpen: Boolean = false,
    onTogglePrivate: (BoardCard) -> Unit = {},
    onLockPrivate: () -> Unit = {},
    onPlaceCards: (List<String>, String?, BoardColumn?) -> Unit = { _, _, _ -> }
) {
    val selectedIndex = pages.indexOfFirst { it.id == selectedPageId }.coerceAtLeast(0)
    val pagerState = rememberPagerState(initialPage = selectedIndex, pageCount = { pages.size.coerceAtLeast(1) })
    val scope = rememberCoroutineScope()
    val latestPages = rememberUpdatedState(pages)

    LaunchedEffect(selectedPageId, pages) {
        val target = pages.indexOfFirst { it.id == selectedPageId }
        if (target >= 0 && target != pagerState.currentPage) {
            delay(80)
            pagerState.scrollToPage(target)
        }
    }
    LaunchedEffect(pagerState) {
        androidx.compose.runtime.snapshotFlow { pagerState.currentPage }.drop(1).collect { pageIndex ->
            latestPages.value.getOrNull(pageIndex)?.let { onSelectPage(it.id) }
        }
    }

    // Hold a note to lift it: drop it between notes to reorder, at a screen edge to carry it to the next folder,
    // or on the share, lock (Private, or back out of it) or trash bins under the list.
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    val drag = remember { TaskDragState(scope) }
    var sharing by remember { androidx.compose.runtime.mutableStateOf<BoardCard?>(null) }
    val togglePrivate = rememberUpdatedState(onTogglePrivate)
    drag.extraBins = remember {
        val confirm = { haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.Confirm) }
        listOf(
            // The sheet opens once the note has flown into the bin.
            DragBin(ZenIcon.Share, { CalendarBlue }) { note -> confirm(); scope.launch { delay(230); sharing = note } },
            DragBin(ZenIcon.Lock, { Amber }) { note -> confirm(); togglePrivate.value(note) }
        )
    }
    drag.onTargetEnter = { haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.SegmentTick) }
    drag.onTrash = { note -> haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.Confirm); onDelete(note) }
    drag.onSlotChange = { haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.SegmentFrequentTick) }
    drag.onLand = { haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.GestureEnd) }
    drag.onPlace = { order, toPage ->
        val moved = drag.card?.id.takeIf { toPage != null }
        onPlaceCards(order, moved, toPage?.let { id -> allColumns.firstOrNull { it.pageId == id } })
    }
    val columnsMode = Layouts.columns(Layouts.NOTES)
    // In the stacked list, a held note's slot follows the folder section under the finger.
    val sectionTops = remember { HashMap<String, Float>() }
    val savedColumns = remember(allColumns) { allColumns.filter { it.pageId == DEFAULT_SAVED_PAGE_ID }.map { it.id }.toSet() }
    DragEffects(
        drag, pager = if (columnsMode) pagerState else null, pageIds = pages.map { it.id },
        canTurnTo = { it != DEFAULT_SAVED_PAGE_ID }, // saves are links and pictures, not a place for notes
        // A save only goes to a bin, and the left column of saves sits in the edge zone, so held saves never turn the page.
        blockTurn = { drag.card?.columnId in savedColumns },
        onFrame = {
            if (!columnsMode) pages.filter { it.id != DEFAULT_SAVED_PAGE_ID && (sectionTops[it.id] ?: Float.MAX_VALUE) <= drag.finger.y }
                .maxByOrNull { sectionTops[it.id] ?: 0f }?.let { drag.enterFolder(it.id) }
        }
    )
    androidx.compose.foundation.layout.Box(Modifier.fillMaxSize()) {
    // No folder-colour wash: the page takes the tab's own glow from the backdrop, like every other tab.
    Column(Modifier.fillMaxSize()) {
        androidx.compose.animation.AnimatedVisibility(privateOpen) { PrivateBanner(onLockPrivate) }
        val pageFor: @Composable (BoardPage?, Modifier) -> Unit = { page, modifier ->
            NotesFolderPage(page, pages, allColumns, cards, modifier, onNewPage, onDeletePage, onRenamePage, onEditCard, onPickImages, onAddCard, onDelete, privateOpen, onTogglePrivate, drag)
        }
        androidx.compose.animation.Crossfade(columnsMode, Modifier.weight(1f).fillMaxWidth(), animationSpec = androidx.compose.animation.core.tween(260), label = "notesLayout") { columnsShown ->
            if (columnsShown) Column(Modifier.fillMaxSize()) {
                HorizontalPager(
                    state = pagerState,
                    flingBehavior = rubberFling(pagerState),
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    pageSpacing = 16.dp,
                    // A held note's own folder must stay composed while the pager turns away from it, or its drag would end.
                    beyondViewportPageCount = if (drag.card != null) pages.size else 0,
                    key = { index -> pages.getOrNull(index)?.id ?: "empty" }
                ) { pageIndex -> pageFor(pages.getOrNull(pageIndex), Modifier.fillMaxSize().bounce(pagerState, pageIndex)) }
                Row(Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 8.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                    pages.forEachIndexed { index, _ ->
                        PagerDot(pagerState.currentPage == index) { scope.launch { pagerState.animateScrollToPage(index, animationSpec = PageSpring) } }
                    }
                }
            } else NotesListLayout(pages, allColumns, cards, selectedPageId, onSelectPage, onNewPage, onEditCard, privateOpen, drag, sectionTops) { saved, modifier -> pageFor(saved, modifier) }
        }
    }
    // A held save is drawn as its tile, a held note as its card.
    TaskDragLayer(drag, NoteShape) { note -> if (note.columnId in savedColumns) SaveTileContent(note) else NoteTileContent(note) }
    }
    sharing?.let { note -> NoteShareSheet(note) { sharing = null } }
}

private val NoteShape = androidx.compose.foundation.shape.RoundedCornerShape(22.dp)

/** Notes placed by hand keep their order; ones never placed (new, or just moved in) sit above them, newest first. */
private fun notesInOrder(notes: List<BoardCard>) =
    notes.sortedWith(compareBy<BoardCard> { it.listOrder != null }.thenBy { it.listOrder ?: 0 }.thenByDescending { it.createdAt })

/**
 * A folder's notes for a lazy list: each note (with the drop slot above it while one is held over this folder) and
 * the slot after the last. The held note's own row folds away while it's carried.
 */
private fun androidx.compose.foundation.lazy.LazyListScope.noteRows(
    folderId: String, notes: List<BoardCard>, drag: TaskDragState, onEditCard: (BoardCard) -> Unit
) {
    val order = notes.map { it.id }
    items(notes, key = { it.id }) { note ->
        Column(Modifier.animateItem()) {
            if (drag.card != null && drag.reorderable && drag.slotPage == folderId && note.id != drag.card?.id) {
                val index = drag.siblings.indexOf(note.id)
                DropSlot(drag, index, startOpen = !drag.switched && index == drag.originSlot)
            }
            NoteTile(note, Modifier.dragRow(drag, note, folderId, order, NoteShape) { onEditCard(note) })
        }
    }
    item(key = "slot-end-$folderId") {
        if (drag.card != null && drag.reorderable && drag.slotPage == folderId) DropSlot(drag, drag.siblings.size, startOpen = !drag.switched && drag.originSlot == drag.siblings.size)
    }
}


@Composable
internal fun ColumnListScreen(
    workspace: Workspace,
    snapshot: BoardSnapshot,
    timerTick: Long,
    onSwitchView: () -> Unit,
    onAddColumn: () -> Unit,
    onAddCard: (BoardColumn) -> Unit,
    onMoveCard: (BoardCard, BoardColumn) -> Unit,
    onToggleImportant: (BoardCard) -> Unit,
    onComplete: (BoardCard) -> Unit,
    onHabitCheckIn: (BoardCard) -> Unit,
    onToggleTimer: (BoardCard) -> Unit,
    onLaunchApp: (BoardCard) -> Unit,
    onDelete: (BoardCard) -> Unit
) {
    val columns = snapshot.columnsFor(workspace)
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 16.dp, bottom = 18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("DAYMARK · ${todayLabel()}", color = Green, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.4.sp)
                    Spacer(Modifier.height(5.dp))
                    Text(workspace.title, color = Ink, fontSize = 30.sp, lineHeight = 35.sp, fontWeight = FontWeight.SemiBold)
                }
                ViewToggle(BoardView.LIST, onSwitchView)
                SettingsButton()
            }
            Spacer(Modifier.height(5.dp))
            Text(workspaceSubtitle(workspace, snapshot.cardsFor(workspace)), color = Muted, fontSize = 13.sp)
            TextButton(onClick = onAddColumn, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)) { Text("Add column", color = Green, fontSize = 12.sp) }
        }
        items(columns, key = { it.id }) { column ->
            BoardColumnCard(column, snapshot, timerTick, onMoveCard, onToggleImportant, onComplete, onHabitCheckIn, onToggleTimer, onLaunchApp, onDelete, { onAddCard(column) })
        }
    }
}


/** A note as a small page: header image if any, a clear title, the date, then the body in reading type. Hold to drag it. */
@Composable
private fun NoteTile(note: BoardCard, modifier: Modifier) {
    androidx.compose.material3.Surface(color = TodoCard, shape = NoteShape, border = GlassStroke, modifier = modifier.glassBlur(NoteShape).fillMaxWidth()) {
        NoteTileContent(note)
    }
}

@Composable
private fun NoteTileContent(note: BoardCard) {
    val header = rememberSavedBitmap(note.imagePath)
    Column {
        header?.let {
            Image(it, contentDescription = null, contentScale = androidx.compose.ui.layout.ContentScale.Crop, modifier = Modifier.fillMaxWidth().height(150.dp))
        }
        Column(Modifier.padding(start = 18.dp, end = 18.dp, top = 16.dp, bottom = 18.dp)) {
            if (note.title.isNotBlank()) Text(note.title, color = TodoText, fontSize = 19.sp, lineHeight = 25.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            Text(noteDate(note.createdAt), color = TodoMuted.copy(alpha = .8f), fontSize = 12.sp, letterSpacing = .3.sp, modifier = Modifier.padding(top = 3.dp))
            if (note.link.isNotBlank()) MetaLine(note.link, TodoBlue, 13.sp, Modifier.padding(top = 10.dp), leading = ZenIcon.Link)
            val body = remember(note.details) { Transcripts.withoutTranscript(note.details) }
            if (body.isNotBlank()) {
                Spacer(Modifier.height(12.dp))
                MarkdownText(body, maxBlocks = 12, reading = true, skipHeading = note.title)
            }
            if (note.comment.isNotBlank()) CommentPreview(note.comment, 6, Modifier.padding(top = 10.dp))
        }
    }
}

/** Sits above the folders while Private is open, with a quick way to lock it again. */
@Composable
private fun PrivateBanner(onLock: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(start = 22.dp, end = 16.dp, top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Glyph(ZenIcon.Lock, Amber, 15.dp)
        Spacer(Modifier.width(7.dp))
        Text("PRIVATE", color = Amber, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.4.sp, modifier = Modifier.weight(1f))
        GlassPill(ZenIcon.Lock, "Lock", onLock)
    }
}

private fun noteDate(millis: Long): String {
    val date = java.time.Instant.ofEpochMilli(millis).atZone(java.time.ZoneId.systemDefault()).toLocalDate()
    val today = java.time.LocalDate.now()
    return when {
        date == today -> "Today"
        date == today.minusDays(1) -> "Yesterday"
        date.year == today.year -> date.format(java.time.format.DateTimeFormatter.ofPattern("d MMMM", java.util.Locale.getDefault()))
        else -> date.format(java.time.format.DateTimeFormatter.ofPattern("d MMMM yyyy", java.util.Locale.getDefault()))
    }
}

/** One folder as a page: its title and buttons, a count, then its notes (or the Saved grid). */
@Composable
private fun NotesFolderPage(
    page: BoardPage?,
    pages: List<BoardPage>,
    allColumns: List<BoardColumn>,
    cards: List<BoardCard>,
    modifier: Modifier,
    onNewPage: () -> Unit,
    onDeletePage: (BoardPage) -> Unit,
    onRenamePage: (BoardPage) -> Unit,
    onEditCard: (BoardCard) -> Unit,
    onPickImages: () -> Unit,
    onAddCard: (BoardColumn) -> Unit,
    onDelete: (BoardCard) -> Unit,
    privateOpen: Boolean,
    onTogglePrivate: (BoardCard) -> Unit,
    drag: TaskDragState
) {
    val folder = page?.let { target -> allColumns.firstOrNull { it.pageId == target.id } }
    val isSaved = page?.id == DEFAULT_SAVED_PAGE_ID
    val folderNotes = cards.filter { it.columnId == folder?.id }.let { if (isSaved) it.sortedByDescending { n -> n.createdAt } else notesInOrder(it) }
    val folderId = page?.id.orEmpty()
    if (!isSaved && page != null) {
        val order = folderNotes.map { it.id }
        androidx.compose.runtime.SideEffect { if (drag.folderOrders[folderId] != order) drag.folderOrders[folderId] = order }
    }
    val slotsHere = !isSaved && drag.card != null && drag.reorderable && drag.slotPage == folderId

    Column(modifier) {
        Row(
            Modifier.fillMaxWidth().padding(start = 22.dp, end = 16.dp, top = 18.dp, bottom = 3.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            PageTitle(page?.name ?: "Inbox", deletable = page != null && pages.size > 1 && !isSaved && !privateOpen, onDelete = { page?.let(onDeletePage) }, onEdit = if (page != null && !privateOpen) ({ onRenamePage(page) }) else null, modifier = Modifier.weight(1f))
            HeaderIcon(ZenIcon.Folder, "New folder", onClick = onNewPage)
            LayoutToggle(Layouts.NOTES)
            SettingsButton()
        }
        Text(
            (if (privateOpen) "${folderNotes.size} private " else "${folderNotes.size} ") +
                if (isSaved) "save${if (folderNotes.size == 1) "" else "s"}" else "note${if (folderNotes.size == 1) "" else "s"}",
            color = TodoMuted,
            fontSize = 13.sp,
            modifier = Modifier.padding(start = 22.dp, bottom = 4.dp)
        )
        if (isSaved && folderNotes.isNotEmpty()) {
            SavedGrid(folderNotes, drag, folderId, onOpen = onEditCard, modifier = Modifier.weight(1f).fillMaxWidth())
        } else if (folderNotes.isEmpty() && !slotsHere) {
            Column(Modifier.weight(1f).fillMaxWidth().padding(bottom = 40.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Glyph(if (privateOpen) ZenIcon.Lock else if (isSaved) ZenIcon.Bookmark else ZenIcon.Note, TodoMuted.copy(alpha = .7f), 44.dp)
                Spacer(Modifier.height(12.dp))
                Text(if (privateOpen) (if (isSaved) "No private saves" else "No private notes here") else if (isSaved) "Nothing saved yet" else "No notes in this folder", color = TodoMuted, fontSize = 16.sp)
                if (privateOpen) Text("Hold a note or save and drop it on the lock", color = TodoMuted, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
                else if (isSaved) Text("Share a link or image to “Add to saves”", color = TodoMuted, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
                folder?.let { TextButton(onClick = { onAddCard(it) }) { Text("Add note", color = TodoBlue) } }
            }
        } else {
            val listState = androidx.compose.foundation.lazy.rememberLazyListState()
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth().dragScrollArea(drag, folderId, listState),
                state = listState,
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                noteRows(folderId, folderNotes, drag, onEditCard)
                folder?.let { currentFolder ->
                    item {
                        TextButton(onClick = { onAddCard(currentFolder) }, modifier = Modifier.fillMaxWidth()) {
                            Text("Add note", color = TodoBlue)
                        }
                    }
                }
            }
        }
    }
}

/**
 * Notes as one list: every folder's notes one under another, under the folder's name. Saved stays
 * on its own page to the side, a swipe away.
 */
@Composable
private fun NotesListLayout(
    pages: List<BoardPage>,
    allColumns: List<BoardColumn>,
    cards: List<BoardCard>,
    selectedPageId: String,
    onSelectPage: (String) -> Unit,
    onNewPage: () -> Unit,
    onEditCard: (BoardCard) -> Unit,
    privateOpen: Boolean,
    drag: TaskDragState,
    sectionTops: HashMap<String, Float>,
    savedPage: @Composable (BoardPage, Modifier) -> Unit
) {
    val saved = pages.firstOrNull { it.id == DEFAULT_SAVED_PAGE_ID }
    val folders = pages.filter { it.id != DEFAULT_SAVED_PAGE_ID }
    val pager = rememberPagerState(initialPage = if (saved != null && selectedPageId == saved.id) 1 else 0, pageCount = { if (saved != null) 2 else 1 })
    val scope = rememberCoroutineScope()
    val latestSelected = rememberUpdatedState(selectedPageId)
    LaunchedEffect(pager) {
        androidx.compose.runtime.snapshotFlow { pager.currentPage }.drop(1).collect { index ->
            // New notes go to the folder that was last picked, or the first folder once back from Saved.
            if (index == 1 && saved != null) onSelectPage(saved.id)
            else if (latestSelected.value == DEFAULT_SAVED_PAGE_ID) folders.firstOrNull()?.let { onSelectPage(it.id) }
        }
    }
    Column(Modifier.fillMaxSize()) {
        HorizontalPager(state = pager, flingBehavior = rubberFling(pager), modifier = Modifier.weight(1f).fillMaxWidth(), pageSpacing = 16.dp) { index ->
            if (index == 1 && saved != null) savedPage(saved, Modifier.fillMaxSize().bounce(pager, index))
            else Column(Modifier.fillMaxSize().bounce(pager, index)) {
                val notesByFolder = remember(cards, folders, allColumns) {
                    folders.map { page ->
                        val ids = allColumns.filter { it.pageId == page.id }.map { it.id }.toSet()
                        page to notesInOrder(cards.filter { it.columnId in ids })
                    }
                }
                notesByFolder.forEach { (page, notes) ->
                    val order = notes.map { it.id }
                    androidx.compose.runtime.SideEffect { if (drag.folderOrders[page.id] != order) drag.folderOrders[page.id] = order }
                }
                val listState = androidx.compose.foundation.lazy.rememberLazyListState()
                Row(Modifier.fillMaxWidth().padding(start = 22.dp, end = 16.dp, top = 18.dp, bottom = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                    PageTitle("Notes", deletable = false, onDelete = {}, modifier = Modifier.weight(1f))
                    HeaderIcon(ZenIcon.Folder, "New folder", onClick = onNewPage)
                    LayoutToggle(Layouts.NOTES)
                    SettingsButton()
                }
                val total = notesByFolder.sumOf { it.second.size }
                Text(
                    (if (privateOpen) "$total private " else "$total ") + "note${if (total == 1) "" else "s"}",
                    color = TodoMuted, fontSize = 13.sp, modifier = Modifier.padding(start = 22.dp, bottom = 4.dp)
                )
                LazyColumn(
                    modifier = notesByFolder.fold(Modifier.weight(1f).fillMaxWidth() as Modifier) { m, (page, _) -> m.dragScrollArea(drag, page.id, listState) },
                    state = listState,
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    notesByFolder.forEach { (page, notes) ->
                        item(key = "label-" + page.id) {
                            SectionLabel(
                                page.name, notes.size.takeIf { it > 0 }?.toString(),
                                modifier = Modifier.animateItem().padding(top = 8.dp)
                                    .onGloballyPositioned { sectionTops[page.id] = it.positionInRoot().y }
                            )
                        }
                        // An empty folder says so, unless a held note's slot is open in it.
                        if (notes.isEmpty() && !(drag.card != null && drag.reorderable && drag.slotPage == page.id)) item(key = "empty-" + page.id) {
                            Text("No notes yet", color = TodoMuted, fontSize = 14.sp, modifier = Modifier.animateItem().padding(start = 8.dp, bottom = 4.dp))
                        }
                        noteRows(page.id, notes, drag, onEditCard)
                    }
                }
            }
        }
        if (saved != null) Row(Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 8.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            (0..1).forEach { index -> PagerDot(pager.currentPage == index) { scope.launch { pager.animateScrollToPage(index, animationSpec = PageSpring) } } }
        }
    }
}
