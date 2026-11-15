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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.runtime.key
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
    onLockPrivate: () -> Unit = {}
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

    val washColor by androidx.compose.animation.animateColorAsState(
        pages.getOrNull(pagerState.currentPage)?.let(::folderColor) ?: Color.Transparent, androidx.compose.animation.core.tween(350), label = "folderWash"
    )
    Column(Modifier.fillMaxSize().folderWashBehind(washColor, .17f)) {
        androidx.compose.animation.AnimatedVisibility(privateOpen) { PrivateBanner(onLockPrivate) }
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            pageSpacing = 16.dp,
            key = { index -> pages.getOrNull(index)?.id ?: "empty" }
        ) { pageIndex ->
            val page = pages.getOrNull(pageIndex)
            val folder = page?.let { target -> allColumns.firstOrNull { it.pageId == target.id } }
            val folderNotes = cards.filter { it.columnId == folder?.id }.sortedByDescending { it.createdAt }
            val isSaved = page?.id == DEFAULT_SAVED_PAGE_ID

            Column(Modifier.fillMaxSize()) {
                Row(
                    Modifier.fillMaxWidth().padding(start = 22.dp, end = 16.dp, top = 18.dp, bottom = 3.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    PageTitle(page?.name ?: "Inbox", deletable = page != null && pages.size > 1 && !isSaved && !privateOpen, onDelete = { page?.let(onDeletePage) }, onEdit = if (page != null && !privateOpen) ({ onRenamePage(page) }) else null, modifier = Modifier.weight(1f))
                    if (isSaved) {
                        GlassPill(ZenIcon.Picture, "Image", onPickImages)
                        Spacer(Modifier.width(8.dp))
                    }
                    NewButton(onNewPage)
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
                    SavedGrid(folderNotes, onOpen = onEditCard, onDelete = onDelete, onTogglePrivate = onTogglePrivate, modifier = Modifier.weight(1f).fillMaxWidth())
                } else if (folderNotes.isEmpty()) {
                    Column(Modifier.weight(1f).fillMaxWidth().padding(bottom = 40.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                        Glyph(if (privateOpen) ZenIcon.Lock else if (isSaved) ZenIcon.Bookmark else ZenIcon.Note, TodoMuted.copy(alpha = .7f), 44.dp)
                        Spacer(Modifier.height(12.dp))
                        Text(if (privateOpen) (if (isSaved) "No private saves" else "No private notes here") else if (isSaved) "Nothing saved yet" else "No notes in this folder", color = TodoMuted, fontSize = 16.sp)
                        if (privateOpen) Text("Long-press a note or save and choose “Move to Private”", color = TodoMuted, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
                        else if (isSaved) Text("Share a link or image to “Add to saves”", color = TodoMuted, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
                        folder?.let { TextButton(onClick = { onAddCard(it) }) { Text("Add note", color = TodoBlue) } }
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        items(folderNotes, key = { it.id }) { note ->
                          Box(Modifier.animateItem()) {
                            NoteTile(
                                note = note,
                                moveTargets = allColumns.filter { it.id != note.columnId },
                                onMove = { onMoveCard(note, it) },
                                onDelete = { onDelete(note) },
                                onOpen = { onEditCard(note) },
                                onTogglePrivate = { onTogglePrivate(note) }
                            )
                          }
                        }
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
        Row(Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 8.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            pages.forEachIndexed { index, page ->
                PagerDot(pagerState.currentPage == index) { scope.launch { pagerState.animateScrollToPage(index) } }
            }
        }
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


/** A note as a small page: header image if any, a clear title, the date, then the body in reading type. */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun NoteTile(note: BoardCard, moveTargets: List<BoardColumn>, onMove: (BoardColumn) -> Unit, onDelete: () -> Unit, onOpen: () -> Unit, onTogglePrivate: () -> Unit) {
    var menu by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    val shape = androidx.compose.foundation.shape.RoundedCornerShape(22.dp)
    val header = rememberSavedBitmap(note.imagePath)
    Box {
        androidx.compose.material3.Surface(
            color = TodoCard, shape = shape, border = GlassStroke,
            modifier = Modifier.glassBlur(shape).fillMaxWidth().clip(shape).combinedClickable(onClick = onOpen, onLongClick = { menu = true })
        ) {
            Column {
                header?.let {
                    Image(it, contentDescription = null, contentScale = androidx.compose.ui.layout.ContentScale.Crop, modifier = Modifier.fillMaxWidth().height(150.dp))
                }
                Column(Modifier.padding(start = 18.dp, end = 18.dp, top = 16.dp, bottom = 18.dp)) {
                    Row(verticalAlignment = Alignment.Top) {
                        Column(Modifier.weight(1f)) {
                            if (note.title.isNotBlank()) Text(note.title, color = TodoText, fontSize = 19.sp, lineHeight = 25.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                            Text(noteDate(note.createdAt), color = TodoMuted.copy(alpha = .8f), fontSize = 12.sp, letterSpacing = .3.sp, modifier = Modifier.padding(top = 3.dp))
                        }
                        Glyph(ZenIcon.More, TodoMuted, 20.dp, modifier = Modifier.clip(androidx.compose.foundation.shape.CircleShape).clickable { menu = true }.padding(start = 6.dp))
                    }
                    if (note.link.isNotBlank()) MetaLine(note.link, TodoBlue, 13.sp, Modifier.padding(top = 10.dp), leading = ZenIcon.Link)
                    if (note.details.isNotBlank()) {
                        Spacer(Modifier.height(12.dp))
                        MarkdownText(note.details, maxBlocks = 12, reading = true, skipHeading = note.title)
                    }
                    if (note.comment.isNotBlank()) CommentPreview(note.comment, 6, Modifier.padding(top = 10.dp))
                }
            }
        }
        androidx.compose.material3.DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            moveTargets.forEach { column -> androidx.compose.material3.DropdownMenuItem(text = { Text("Move to ${column.name}") }, onClick = { menu = false; onMove(column) }) }
            androidx.compose.material3.DropdownMenuItem(text = { Text(if (note.isPrivate) "Remove from Private" else "Move to Private") }, onClick = { menu = false; onTogglePrivate() })
            androidx.compose.material3.DropdownMenuItem(text = { Text("Delete", color = Danger) }, onClick = { menu = false; onDelete() })
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
