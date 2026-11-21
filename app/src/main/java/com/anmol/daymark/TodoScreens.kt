package com.anmol.daymark

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.draw.drawBehind
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.zIndex
import kotlinx.coroutines.Job
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.foundation.BorderStroke
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.animation.core.Spring
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.ui.draw.rotate
import androidx.compose.runtime.key
import androidx.compose.ui.draw.scale
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import java.time.LocalDate

/** Folders whose Completed section the user has opened this session. */
private val DoneExpanded = mutableMapOf<String, Boolean>()

/** To-dos: one plain list per folder, with completed tasks tucked under a divider. */
@Composable
internal fun GroupedTaskListScreen(
    pages: List<BoardPage>,
    allColumns: List<BoardColumn>,
    cards: List<BoardCard>,
    selectedPageId: String,
    onSelectPage: (String) -> Unit,
    onNewPage: () -> Unit,
    onMovePage: (String, Int) -> Unit,
    onRenamePage: (BoardPage) -> Unit,
    onDeletePage: (BoardPage) -> Unit,
    onEditCard: (BoardCard) -> Unit,
    onComplete: (BoardCard) -> Unit,
    onReopen: (BoardCard) -> Unit,
    onMoveCard: (BoardCard, BoardColumn) -> Unit,
    onDelete: (BoardCard) -> Unit,
    onPlaceCards: (order: List<String>, movedId: String?, toColumn: BoardColumn?) -> Unit,
    onVoice: () -> Unit
) {
    val selectedIndex = pages.indexOfFirst { it.id == selectedPageId }.coerceAtLeast(0)
    val pagerState = rememberPagerState(initialPage = selectedIndex, pageCount = { pages.size.coerceAtLeast(1) })
    val scope = rememberCoroutineScope()
    val latestPages = rememberUpdatedState(pages)
    val chips = rememberLazyListState()

    val lastIds = remember { arrayOf(pages.map { it.id }) }
    LaunchedEffect(selectedPageId, pages) {
        val ids = pages.map { it.id }
        // Only the order changed (a chip was dragged): jump so the selected page stays showing, without sweeping through the others.
        val reordered = ids != lastIds[0] && ids.toSet() == lastIds[0].toSet()
        lastIds[0] = ids
        val target = ids.indexOf(selectedPageId)
        if (target >= 0 && target != pagerState.currentPage && Layouts.columns(Layouts.TASKS)) { if (reordered) pagerState.scrollToPage(target) else pagerState.animateScrollToPage(target, animationSpec = PageSpring) }
    }
    LaunchedEffect(pagerState) {
        androidx.compose.runtime.snapshotFlow { pagerState.currentPage }.drop(1).collect { index ->
            latestPages.value.getOrNull(index)?.let { onSelectPage(it.id) }
            runCatching { chips.animateScrollToItem(index) }
        }
    }
    val columnsMode = Layouts.columns(Layouts.TASKS)
    // List mode: every folder one under another, each as a card; the chip and title follow the folder at the top.
    val listState = rememberLazyListState()
    val listFolder by remember { androidx.compose.runtime.derivedStateOf { listState.firstVisibleItemIndex } }
    LaunchedEffect(columnsMode) {
        if (columnsMode) return@LaunchedEffect
        androidx.compose.runtime.snapshotFlow { listFolder }.drop(1).collect { index ->
            latestPages.value.getOrNull(index)?.let { onSelectPage(it.id) }
            runCatching { chips.animateScrollToItem(index) }
        }
    }
    val currentIndex = if (columnsMode) pagerState.currentPage else listFolder.coerceAtMost(pages.lastIndex.coerceAtLeast(0))
    val current = pages.getOrNull(currentIndex)

    // A held task floats over everything here (chips included), so the screen sits in a Box with the drag layer on top.
    val drag = remember { TaskDragState(scope) }
    Box(Modifier.fillMaxSize()) {
    // No folder-colour wash: the page takes the tab's own glow from the backdrop, like every other tab.
    Column(Modifier.fillMaxSize()) {
        // The title shares the row with the header icons, so the folders and tasks sit higher up.
        // Folders are renamed or deleted from their chip (long-press), so the title is plain text.
        Row(Modifier.fillMaxWidth().padding(start = 22.dp, end = 16.dp, top = 14.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            PageTitle(
                if (columnsMode) current?.name ?: "Inbox" else "Tasks",
                deletable = false,
                onDelete = {},
                size = 34.sp,
                modifier = Modifier.weight(1f)
            )
            LayoutToggle(Layouts.TASKS)
            SettingsButton()
        }
        // Folders as chips; swiping the list below moves between them too. Long-press: menu (release in place) or drag to reorder.
        val density = LocalDensity.current
        val spacingPx = with(density) { 8.dp.toPx() }
        val edgePx = with(density) { 56.dp.toPx() }
        val touchSlop = LocalViewConfiguration.current.touchSlop
        val haptic = LocalHapticFeedback.current
        var dragOrder by remember { mutableStateOf<List<BoardPage>?>(null) }
        var draggingId by remember { mutableStateOf<String?>(null) }
        var menuFor by remember { mutableStateOf<String?>(null) }
        var rowRootX by remember { mutableFloatStateOf(0f) }
        var rowWidth by remember { mutableFloatStateOf(0f) }
        var rowRootY by remember { mutableFloatStateOf(0f) }
        var rowHeight by remember { mutableFloatStateOf(0f) }
        var fingerX by remember { mutableFloatStateOf(0f) }
        // The dragged chip rides under the finger: its offset is (finger - grab point) minus where the row currently lays it out, measured every
        // frame, so it stays put when the list re-anchors after a swap or scrolls.
        var grabDx by remember { mutableFloatStateOf(0f) }
        var dragNatural by remember { mutableFloatStateOf(0f) }
        var lastSwap by remember { mutableLongStateOf(0L) }
        val chipBounds = remember { HashMap<String, Pair<Float, Float>>() } // page id -> (left in window, width)
        val selectedChipId = current?.id
        /** Moves the dragged chip past a neighbour once its centre has crossed that neighbour's centre. At most one swap per 120 ms so the layout can catch up. */
        fun settleSwaps() {
            val order = dragOrder ?: return
            val id = draggingId ?: return
            if (System.currentTimeMillis() - lastSwap < 120) return
            val wi = chipBounds[id]?.second ?: return
            val i = order.indexOfFirst { it.id == id }
            val center = rowRootX + fingerX - grabDx + wi / 2
            val wr = order.getOrNull(i + 1)?.let { chipBounds[it.id]?.second ?: wi }
            val wl = order.getOrNull(i - 1)?.let { chipBounds[it.id]?.second ?: wi }
            val to = when {
                wr != null && center > dragNatural + wi + spacingPx + wr / 2 -> i + 1
                wl != null && center < dragNatural - spacingPx - wl / 2 -> i - 1
                else -> return
            }
            dragOrder = movePage(order, i, to)
            lastSwap = System.currentTimeMillis()
        }
        // Dropping a held task: a folder chip under the finger moves it there, the trash deletes it.
        val bandPx = with(density) { 22.dp.toPx() }
        fun inChipBand(f: Offset) = f.y in (rowRootY - bandPx)..(rowRootY + rowHeight + bandPx)
        fun pageOf(card: BoardCard) = allColumns.firstOrNull { it.id == card.columnId }?.pageId
        drag.onHover = {
            val f = drag.finger
            val own = drag.card?.let(::pageOf)
            val page = if (!inChipBand(f)) null else chipBounds.entries
                .firstOrNull { (_, b) -> f.x >= b.first - spacingPx / 2 && f.x <= b.first + b.second + spacingPx / 2 }?.key?.takeIf { it != own }
            if (page != null && page != drag.hoverPage) haptic.performHapticFeedback(HapticFeedbackType.SegmentTick)
            drag.hoverPage = page
        }
        drag.onTargetEnter = { haptic.performHapticFeedback(HapticFeedbackType.SegmentTick) }
        drag.onTrash = { card -> haptic.performHapticFeedback(HapticFeedbackType.Confirm); onDelete(card) }
        drag.onDrop = { card ->
            val page = drag.hoverPage
            val target = page?.let { id -> allColumns.firstOrNull { it.pageId == id } }
            when {
                target != null -> {
                    haptic.performHapticFeedback(HapticFeedbackType.Confirm); onMoveCard(card, target)
                    chipBounds[page]?.let { (left, width) -> Offset(left + width / 2, rowRootY + rowHeight / 2) }
                }
                else -> null
            }
        }
        drag.onPlace = { order, toPage ->
            val moved = drag.card?.id.takeIf { toPage != null }
            onPlaceCards(order, moved, toPage?.let { id -> allColumns.firstOrNull { it.pageId == id } })
        }
        drag.onSlotChange = { haptic.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick) }
        drag.onLand = { haptic.performHapticFeedback(HapticFeedbackType.GestureEnd) }
        // While a task is held: the folders turn at the screen edges (not from up among the chips), the slot's list scrolls
        // near its ends, and chips near the ends of their row scroll into reach.
        DragEffects(
            drag, pager = if (columnsMode) pagerState else null, pageIds = pages.map { it.id },
            blockTurn = { inChipBand(it) },
            onFrame = {
                val f = drag.finger
                val x = f.x - rowRootX
                val dir = if (!inChipBand(f)) 0f else if (x < edgePx) -1f else if (x > rowWidth - edgePx) 1f else 0f
                if (dir != 0f) { chips.scrollBy(dir * 14f); drag.onHover() }
            }
        )
        LazyRow(
            state = chips, contentPadding = PaddingValues(horizontal = 18.dp), horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.onGloballyPositioned { val p = it.positionInRoot(); rowRootX = p.x; rowRootY = p.y; rowWidth = it.size.width.toFloat(); rowHeight = it.size.height.toFloat() }.pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    val rootX = rowRootX + down.position.x
                    val hit = chipBounds.entries.firstOrNull { (_, b) -> rootX >= b.first && rootX <= b.first + b.second }?.key ?: return@awaitEachGesture
                    // Not awaitLongPressOrCancellation: the chip's own click already consumed the press, which that helper treats as a cancel.
                    var cancelled = false
                    withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                        while (true) {
                            val c = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull { it.id == down.id }
                            if (c == null || !c.pressed || (c.position - down.position).getDistance() > touchSlop) { cancelled = true; break }
                        }
                    }
                    if (cancelled) return@awaitEachGesture
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    var total = 0f
                    var started = false
                    fingerX = down.position.x
                    var autoScroll: Job? = null
                    // From here the gesture is ours: consume everything so the row doesn't scroll and the chip doesn't also get a click.
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        val dx = change.positionChange().x
                        fingerX = change.position.x
                        change.consume()
                        if (!change.pressed) break
                        total += dx
                        if (!started && kotlin.math.abs(total) > touchSlop) {
                            started = true
                            grabDx = rootX - (chipBounds[hit]?.first ?: rootX); dragNatural = chipBounds[hit]?.first ?: rootX
                            draggingId = hit; dragOrder = latestPages.value; lastSwap = 0L
                            autoScroll = scope.launch {
                                while (true) {
                                    val dir = if (fingerX < edgePx) -1f else if (fingerX > rowWidth - edgePx) 1f else 0f
                                    if (dir != 0f) { chips.scrollBy(dir * 18f); settleSwaps() }
                                    delay(16)
                                }
                            }
                            settleSwaps()
                        } else if (started) settleSwaps()
                    }
                    autoScroll?.cancel()
                    if (started) {
                        val to = dragOrder?.indexOfFirst { it.id == hit } ?: -1
                        dragOrder = null; draggingId = null
                        if (to >= 0) onMovePage(hit, to)
                    } else menuFor = hit
                }
            }
        ) {
            itemsIndexed(dragOrder ?: pages, key = { _, page -> page.id }) { _, page ->
                val taskCount = remember(cards, allColumns, page.id) { val ids = allColumns.filter { it.pageId == page.id }.map { it.id }.toSet(); cards.count { it.columnId in ids } }
                val dragged = draggingId == page.id
                DisposableEffect(page.id) { onDispose { chipBounds.remove(page.id) } }
                FolderChip(
                    page.name, folderColor(page), selected = page.id == selectedChipId,
                    onRename = { onRenamePage(page) },
                    onDelete = if (page.id == DEFAULT_TODO_PAGE_ID) null else ({ onDeletePage(page) }),
                    taskCount = taskCount,
                    menuOpen = menuFor == page.id, onMenuDismiss = { menuFor = null },
                    lifted = dragged || drag.hoverPage == page.id, offset = { if (dragged) rowRootX + fingerX - grabDx - dragNatural else 0f },
                    modifier = (if (dragged) Modifier.zIndex(1f) else Modifier.animateItem()).onGloballyPositioned { chipBounds[page.id] = it.positionInRoot().x to it.size.width.toFloat(); if (draggingId == page.id) dragNatural = it.positionInRoot().x }
                ) {
                    val index = pages.indexOfFirst { it.id == page.id }.coerceAtLeast(0)
                    scope.launch { if (columnsMode) pagerState.animateScrollToPage(index, animationSpec = PageSpring) else listState.animateScrollToItem(index) }
                }
            }
            item(key = "new-folder") { FolderChip("+", Color.Transparent, selected = false, onClick = onNewPage) }
        }
        Spacer(Modifier.height(8.dp))
        val folderCards: @Composable (BoardPage, Boolean) -> Unit = { page, inList ->
            val listIds = allColumns.filter { it.pageId == page.id }.map { it.id }.toSet()
            TaskFolder(
                folderId = page.id,
                cards = cards.filter { it.columnId in listIds },
                inList = inList,
                drag = drag,
                onEdit = onEditCard,
                onComplete = onComplete,
                onReopen = onReopen
            )
        }
        androidx.compose.animation.Crossfade(columnsMode, Modifier.weight(1f).fillMaxWidth(), animationSpec = tween(260), label = "tasksLayout") { columnsShown ->
            if (columnsShown) HorizontalPager(
                state = pagerState,
                flingBehavior = rubberFling(pagerState),
                modifier = Modifier.fillMaxSize(),
                // A held task's own folder must stay composed while the pager turns away from it, or its drag would end.
                beyondViewportPageCount = if (drag.card != null) pages.size else 0,
                key = { index -> pages.getOrNull(index)?.id ?: "empty" }
            ) { index ->
                val page = pages.getOrNull(index) ?: return@HorizontalPager
                val pageList = rememberLazyListState()
                LazyColumn(Modifier.fillMaxSize().bounce(pagerState, index).dragScrollArea(drag, page.id, pageList), state = pageList, contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 4.dp, bottom = 24.dp)) {
                    item(key = page.id) { folderCards(page, false) }
                }
            } else LazyColumn(pages.fold(Modifier.fillMaxSize() as Modifier) { m, page -> m.dragScrollArea(drag, page.id, listState) }, state = listState, contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 4.dp, bottom = 24.dp)) {
                items(pages, key = { it.id }) { page ->
                    Column(Modifier.animateItem().padding(bottom = 18.dp)) {
                        val ids = allColumns.filter { it.pageId == page.id }.map { it.id }.toSet()
                        SectionLabel(page.name, cards.count { it.columnId in ids && !it.completed }.takeIf { it > 0 }?.toString())
                        folderCards(page, true)
                    }
                }
            }
        }
    }
    TaskDragLayer(drag) { card -> TaskLineContent(card, checked = card.completed, onCheck = null) }
    }
}

/**
 * A folder chip. Long-press handling (menu or drag-to-reorder) lives on the row that holds the chips ([menuOpen] is driven from there);
 * [offset]/[lifted] show the chip riding under the finger while it is dragged. [onDelete] is null for the built-in Inbox.
 */
@Composable
private fun FolderChip(
    label: String, tint: Color, selected: Boolean, onRename: (() -> Unit)? = null, onDelete: (() -> Unit)? = null, taskCount: Int = 0,
    menuOpen: Boolean = false, onMenuDismiss: () -> Unit = {}, lifted: Boolean = false, offset: () -> Float = { 0f },
    modifier: Modifier = Modifier, onClick: () -> Unit
) {
    // Just the name: the selected folder's is brighter, with a line in the tab's colour drawn in under it.
    val text by animateColorAsState(if (selected) TodoText else TodoMuted, tween(220), label = "chipText")
    val underline by animateFloatAsState(if (selected) 1f else 0f, spring(dampingRatio = .7f, stiffness = Spring.StiffnessMediumLow), label = "chipLine")
    val lineColor = if (Look.light) TodoBlue else Look.accent.fill
    val lift by animateFloatAsState(if (lifted) 1.07f else 1f, tween(120), label = "chipLift")
    Box(modifier.graphicsLayer { translationX = offset(); scaleX = lift; scaleY = lift }) {
        Surface(
            color = if (lifted) veil(0.2f) else Color.Transparent, // a faint backing only while it's being dragged
            shape = RoundedCornerShape(22.dp),
            shadowElevation = if (lifted) 10.dp else 0.dp,
            modifier = Modifier.clip(RoundedCornerShape(22.dp)).clickable(onClick = onClick)
        ) {
            Text(
                label, color = text, fontSize = 15.sp, maxLines = 1,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                modifier = Modifier
                    .padding(horizontal = 12.dp, vertical = 9.dp)
                    .tabUnderline(underline, lineColor)
            )
        }
        DropdownMenu(expanded = menuOpen && onRename != null, onDismissRequest = onMenuDismiss) {
            DropdownMenuItem(text = { Text("Edit") }, onClick = { onMenuDismiss(); onRename?.invoke() })
            if (onDelete != null) DropdownMenuItem(
                text = { Text(if (taskCount > 0) "Delete · $taskCount task${if (taskCount == 1) "" else "s"}" else "Delete", color = Danger) },
                onClick = { onMenuDismiss(); onDelete() }
            )
        }
    }
}

/** Moves the item at [from] to index [to] (clamped); returns the same list when nothing moves. */
internal fun <T> movePage(pages: List<T>, from: Int, to: Int): List<T> {
    if (from !in pages.indices) return pages
    val target = to.coerceIn(0, pages.lastIndex)
    if (target == from) return pages
    return pages.toMutableList().apply { add(target, removeAt(from)) }
}

/**
 * A folder's tasks in one card: open ones first, then a wavy line and the Completed row, which opens
 * to show the finished ones inside the same card. In the pager an empty folder gets a friendly
 * message instead of a card; in the list ([inList]) it gets a small empty card.
 */
@Composable
private fun TaskFolder(
    folderId: String,
    cards: List<BoardCard>,
    inList: Boolean,
    drag: TaskDragState,
    onEdit: (BoardCard) -> Unit,
    onComplete: (BoardCard) -> Unit,
    onReopen: (BoardCard) -> Unit
) {
    val open = remember(cards) {
        // Tasks placed by hand keep their order; ones never placed (new or just moved here) sit above them, sorted as before.
        cards.filterNot { it.completed }.sortedWith(
            compareBy<BoardCard> { it.listOrder != null }.thenBy { it.listOrder ?: 0 }
                .thenBy { !it.important }.thenBy { it.dueDay ?: Long.MAX_VALUE }.thenByDescending { it.createdAt }
        )
    }
    val done = remember(cards) { cards.filter { it.completed }.sortedWith(compareByDescending<BoardCard> { it.completedDay ?: 0 }.thenByDescending { it.createdAt }) }
    // Collapsed until the user taps the header; the choice is remembered per folder for the session (also across tab switches).
    var showDone by remember(folderId) { mutableStateOf(DoneExpanded[folderId] == true) }
    val chevron by animateFloatAsState(if (showDone) 0f else -90f, tween(220), label = "doneChevron")

    // The held task's slot opens in whichever folder it's over (its own, or one it was carried to at the screen edge).
    val order = open.map { it.id }
    SideEffect { if (drag.folderOrders[folderId] != order) drag.folderOrders[folderId] = order }
    val slotsHere = drag.card != null && drag.reorderable && drag.slotPage == folderId
    if (cards.isEmpty() && !slotsHere) {
        if (inList) SectionCard {
            Text("No tasks yet", color = TodoMuted, fontSize = 15.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp))
        } else Column(Modifier.fillMaxWidth().padding(top = 70.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("No tasks yet", color = TodoText, fontSize = 17.sp, fontWeight = FontWeight.Medium)
            Text("Tap + below to add one", color = TodoMuted, fontSize = 14.sp, modifier = Modifier.padding(top = 4.dp))
        }
        return
    }
    SectionCard(Modifier.rise()) {
        // While one of these is held, its row folds away; the outlined slot opens where it would land.
        val held = drag.card?.takeIf { h -> drag.reorderable && open.any { it.id == h.id } }
        val firstShown = open.firstOrNull { it.id != held?.id }?.id
        open.forEach { card ->
            key(card.id) {
                if (slotsHere && card.id != held?.id) {
                    val index = drag.siblings.indexOf(card.id)
                    DropSlot(drag, index, startOpen = !drag.switched && index == drag.originSlot)
                }
                if (card.id != firstShown && card.id != held?.id) RowDivider()
                TaskLine(card, drag, folderId, onEdit, onComplete, onReopen, folderOrder = order)
            }
        }
        if (slotsHere) key("slot-end") { DropSlot(drag, drag.siblings.size, startOpen = !drag.switched && drag.originSlot == drag.siblings.size) }
        if (done.isNotEmpty()) {
            if (open.isNotEmpty()) WavyDivider(Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
            Row(
                Modifier.fillMaxWidth().clickable { showDone = !showDone; DoneExpanded[folderId] = showDone }.padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Completed", color = TodoMuted, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                Text("  ${done.size}", color = TodoMuted.copy(alpha = .7f), fontSize = 15.sp, modifier = Modifier.weight(1f))
                Glyph(ZenIcon.ChevronDown, TodoMuted, 14.dp, modifier = Modifier.rotate(chevron))
            }
            if (showDone) done.forEach { card ->
                key(card.id) {
                    RowDivider()
                    TaskLine(card, drag, folderId, onEdit, onComplete, onReopen)
                }
            }
        }
    }
}

/**
 * One task: a round check, the title, and a quiet line of details underneath. Tap to edit; hold to lift it out
 * ([drag]) and drop it on another folder's chip or the trash.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TaskLine(
    card: BoardCard,
    drag: TaskDragState,
    folderId: String,
    onEdit: (BoardCard) -> Unit,
    onComplete: (BoardCard) -> Unit,
    onReopen: (BoardCard) -> Unit,
    folderOrder: List<String>? = null
) {
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    // Tick immediately, then let the task glide down to Completed a beat later.
    var ticking by remember(card.id, card.completed) { mutableStateOf(false) }
    // Completed tasks can be moved or deleted by dragging, but stay in finishing order.
    Box(
        Modifier.fillMaxWidth().dragRow(drag, card, folderId, folderOrder.takeIf { !card.completed }) { onEdit(card) }
    ) {
        TaskLineContent(card, checked = card.completed || ticking, onCheck = {
            if (card.completed) onReopen(card)
            else if (!ticking) {
                ticking = true
                haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                scope.launch { delay(260); onComplete(card) }
            }
        })
    }
}

/** The look of a task row, shared by the row in its folder and the copy lifted under the finger ([onCheck] null: not tappable). */
@Composable
internal fun TaskLineContent(card: BoardCard, checked: Boolean, onCheck: (() -> Unit)?) {
    val ring by animateColorAsState(if (checked) CalendarBlue else TodoMuted.copy(alpha = .6f), tween(180), label = "checkRing")
    val fill by animateColorAsState(if (checked) CalendarBlue else Color.Transparent, tween(180), label = "checkFill")
    val tick by animateFloatAsState(if (checked) 1f else 0f, spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium), label = "tick")
    val today = LocalDate.now().toEpochDay()
    val due = listOfNotNull(card.dueDay?.let(::formatDue), card.dueMinute?.let(::formatClock)).joinToString(" · ")
    val overdue = !card.completed && card.dueDay != null && card.dueDay < today
    val note = card.details.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }
    Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 11.dp), verticalAlignment = Alignment.Top) {
        val shape = checkShape(checked)
        Box(
            Modifier.padding(top = 1.dp).size(26.dp).clip(shape).background(fill).border(1.5.dp, ring, shape)
                .then(if (onCheck != null) Modifier.clickable(onClick = onCheck) else Modifier)
                .semantics { contentDescription = if (checked) "Mark not done" else "Mark done" },
            contentAlignment = Alignment.Center
        ) {
            Glyph(ZenIcon.Check, OnFill, 15.dp, modifier = Modifier.scale(tick).alpha(tick))
        }
        Column(Modifier.weight(1f).padding(start = 16.dp)) {
            Text(
                card.title,
                color = if (checked) TodoMuted else TodoText,
                fontSize = 17.sp,
                lineHeight = 22.sp,
                textDecoration = if (checked) TextDecoration.LineThrough else null,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )
            if (note != null || due.isNotEmpty() || card.important) {
                Row(Modifier.padding(top = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (card.important && !checked) Glyph(ZenIcon.StarFilled, Amber, 12.dp, modifier = Modifier.padding(end = 6.dp))
                    if (due.isNotEmpty()) MetaLine(due, if (overdue) Danger else TodoMuted, 13.sp, Modifier.padding(end = if (note != null) 12.dp else 0.dp), leading = ZenIcon.Calendar, badges = badgesFor(card))
                    if (note != null) MetaLine(note, TodoMuted, 13.sp, leading = ZenIcon.Lines)
                }
            }
        }
    }
}

@Composable
internal fun TaskInboxScreen(
    columns: List<BoardColumn>,
    cards: List<BoardCard>,
    selectedColumnId: String,
    onSelectColumn: (String) -> Unit,
    onAddColumn: () -> Unit,
    onAddCard: (BoardColumn) -> Unit,
    onComplete: (BoardCard) -> Unit,
    onToggleImportant: (BoardCard) -> Unit,
    onMoveCard: (BoardCard, BoardColumn) -> Unit,
    onDelete: (BoardCard) -> Unit,
    onVoice: () -> Unit,
    onSwitchView: () -> Unit
) {
    if (columns.isEmpty()) return

    val initialPage = columns.indexOfFirst { it.id == selectedColumnId }.coerceAtLeast(0)
    val pagerState = rememberPagerState(initialPage = initialPage, pageCount = { columns.size })
    val scope = rememberCoroutineScope()
    LaunchedEffect(selectedColumnId, columns.size) {
        val page = columns.indexOfFirst { it.id == selectedColumnId }
        if (page >= 0 && page != pagerState.currentPage) pagerState.animateScrollToPage(page, animationSpec = PageSpring)
    }
    LaunchedEffect(pagerState, columns.size) {
        androidx.compose.runtime.snapshotFlow { pagerState.currentPage }.collect { page ->
            columns.getOrNull(page)?.let { onSelectColumn(it.id) }
        }
    }

    Column(Modifier.fillMaxSize().background(Color.Transparent)) {
        Row(
            Modifier.fillMaxWidth().padding(start = 22.dp, end = 16.dp, top = 18.dp, bottom = 3.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Inbox", color = TodoText, fontSize = 26.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
            ViewToggle(BoardView.BOARD, onSwitchView)
            SettingsButton()
        }
        val openCount = cards.count { !it.completed }
        val priorityCount = cards.count { it.important && !it.completed }
        Text(
            "$openCount open · $priorityCount priorities",
            color = TodoMuted,
            fontSize = 13.sp,
            modifier = Modifier.padding(start = 22.dp, bottom = 5.dp)
        )

        HorizontalPager(
            state = pagerState,
            flingBehavior = rubberFling(pagerState),
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 18.dp),
            pageSpacing = 14.dp,
            key = { page -> columns[page].id }
        ) { page ->
            val column = columns[page]
            Box(Modifier.fillMaxSize().bounce(pagerState, page)) { TaskColumnPage(
                column = column,
                cards = cards.filter { it.columnId == column.id },
                columns = columns,
                onAddTask = { onAddCard(column) },
                onComplete = onComplete,
                onToggleImportant = onToggleImportant,
                onMoveCard = onMoveCard,
                onDelete = onDelete
            ) }
        }

        ColumnPageIndicator(
            columns = columns,
            currentPage = pagerState.currentPage,
            darkTheme = true,
            onSelectPage = { page -> scope.launch { pagerState.animateScrollToPage(page, animationSpec = PageSpring) } },
            onAddColumn = onAddColumn
        )
    }
}

@Composable
private fun TaskColumnPage(
    column: BoardColumn,
    cards: List<BoardCard>,
    columns: List<BoardColumn>,
    onAddTask: () -> Unit,
    onComplete: (BoardCard) -> Unit,
    onToggleImportant: (BoardCard) -> Unit,
    onMoveCard: (BoardCard, BoardColumn) -> Unit,
    onDelete: (BoardCard) -> Unit
) {
    val sorted = cards.sortedWith(compareBy<BoardCard> { !it.important }.thenBy { it.dueDay ?: Long.MAX_VALUE }.thenByDescending { it.createdAt })
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp),
        contentPadding = PaddingValues(top = 17.dp, bottom = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item {
            Row(Modifier.fillMaxWidth().padding(horizontal = 2.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(column.name, color = TodoText, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.width(8.dp))
                Text(cards.size.toString(), color = TodoMuted, fontSize = 13.sp)
            }
        }
        items(sorted, key = { it.id }) { card ->
            TaskInboxRow(
                card = card,
                columns = columns,
                checked = card.completed || column.name.equals("Done", ignoreCase = true),
                onComplete = { if (!card.completed) onComplete(card) },
                onToggleImportant = { onToggleImportant(card) },
                onMove = { onMoveCard(card, it) },
                onDelete = { onDelete(card) }
            )
        }
        item {
            Row(
                Modifier.fillMaxWidth()
                    .border(GlassStroke, RoundedCornerShape(11.dp))
                    .clip(RoundedCornerShape(11.dp))
                    .clickable(onClick = onAddTask)
                    .padding(horizontal = 17.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Glyph(ZenIcon.Plus, TodoBlue, 22.dp)
                Spacer(Modifier.width(13.dp))
                Text("Add task", color = TodoText, fontSize = 15.sp)
            }
        }
    }
}

@Composable
private fun TaskInboxRow(
    card: BoardCard,
    columns: List<BoardColumn>,
    checked: Boolean,
    onComplete: () -> Unit,
    onToggleImportant: () -> Unit,
    onMove: (BoardColumn) -> Unit,
    onDelete: () -> Unit
) {
    var menuExpanded by remember(card.id) { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth()
            .border(GlassStroke, RoundedCornerShape(11.dp))
            .glassBlur(RoundedCornerShape(11.dp))
            .background(TodoCard)
            .padding(start = 16.dp, end = 8.dp, top = 9.dp, bottom = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier.size(23.dp)
                .border(1.5.dp, if (checked) TodoBlue else TodoMuted, CircleShape)
                .clip(CircleShape)
                .clickable(enabled = !checked, onClick = onComplete),
            contentAlignment = Alignment.Center
        ) {
            if (checked) Glyph(ZenIcon.Check, TodoBlue, 14.dp)
        }
        Text(
            card.title,
            color = if (checked) TodoMuted else TodoText,
            fontSize = 15.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(start = 13.dp, end = 6.dp)
        )
        Box {
            Glyph(ZenIcon.More, TodoMuted, 18.dp, modifier = Modifier.clickable { menuExpanded = true }.padding(horizontal = 7.dp, vertical = 3.dp))
            DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                DropdownMenuItem(text = { Text(if (card.important) "Remove priority" else "Mark important") }, onClick = { onToggleImportant(); menuExpanded = false })
                columns.filter { it.id != card.columnId }.forEach { target ->
                    DropdownMenuItem(text = { Text("Move to ${target.name}") }, onClick = { onMove(target); menuExpanded = false })
                }
                DropdownMenuItem(text = { Text("Delete") }, onClick = { onDelete(); menuExpanded = false })
            }
        }
    }
}
