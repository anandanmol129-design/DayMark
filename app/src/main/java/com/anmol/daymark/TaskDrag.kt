package com.anmol.daymark

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LocalPinnableContainer
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * A drop target beside the trash, for screens that offer more than delete (notes: share, and Private). [hotColor]
 * fills it while a card is over it; [onDrop] runs when a card is let go on it.
 */
@Stable
internal class DragBin(val icon: ZenIcon, val hotColor: () -> Color, val onDrop: (BoardCard) -> Unit) {
    var bounds = Rect.Zero
}

/**
 * A card (task, note or habit) pulled out of its list by a long-press, like a block slid out of a stack: it rides
 * under the finger while the list opens an outlined slot where it would go. It's dropped on a folder chip (move), a
 * bin under the list (trash; for notes also share and Private) or anywhere else (it's pulled into the slot, reordering the list).
 * Held at a screen edge it turns the folder pager that way; near the top or bottom of its list the list scrolls.
 * All positions are in root coordinates.
 */
@Stable
internal class TaskDragState(private val scope: CoroutineScope) {
    var card by mutableStateOf<BoardCard?>(null); private set
    var size by mutableStateOf(IntSize.Zero); private set
    var finger by mutableStateOf(Offset.Zero); private set
    /** The folder chip under the finger (never the card's own folder). */
    var hoverPage by mutableStateOf<String?>(null)
    var overTrash by mutableStateOf(false); private set
    /** The extra bin under the finger, if any. */
    var overExtra by mutableStateOf<DragBin?>(null); private set
    val overBin get() = overTrash || overExtra != null
    /** Bins shown left of the trash (set once, from a remembered list); none for tasks and habits. */
    var extraBins by mutableStateOf<List<DragBin>>(emptyList())
    /** Leans the block into the direction it's being pulled; eased back to upright every frame while held. */
    var tilt by mutableFloatStateOf(0f)
    var released by mutableStateOf(false); private set
    var trashBounds = Rect.Zero
    /** The full-screen box the drag layer sits in. */
    var screenBounds = Rect.Zero
    /** Waiting at a screen edge to turn the folders: which edge (-1, 0, 1) and how far along (0..1). */
    var edgeSide by mutableIntStateOf(0)
    var edgeDwell by mutableFloatStateOf(0f)
    /** Where the finger holds the block, from its top-left. */
    var grab = Offset.Zero; private set
    private var home = Offset.Zero
    val fly = Animatable(Offset.Zero, Offset.VectorConverter)
    val lift = Animatable(1f)
    val shrink = Animatable(1f)
    /** A held block taller than this is drawn scaled down to it, so a long note doesn't cover the screen. */
    var compactPx = Float.MAX_VALUE
    val compact get() = if (size.height <= 0) 1f else (compactPx / size.height).coerceIn(.45f, 1f)
    var binPadPx = 0f

    /** Each folder's open cards, top to bottom, as last shown; written by the folders themselves. */
    val folderOrders = mutableStateMapOf<String, List<String>>()
    /** Scrollable list of each folder, with its bounds; the one holding the slot scrolls near its ends. */
    val scrollAreas = HashMap<String, Pair<LazyListState, Rect>>()
    /** The held card's own folder, and the folder whose slot is open (a different one after paging at the screen edge). */
    var homePage: String? = null; private set
    var slotPage by mutableStateOf<String?>(null); private set
    /** True once the slot has left the home folder at least once, so slots no longer start pre-opened. */
    var switched = false; private set
    /** The other open cards in [slotPage], top to bottom (empty for one that isn't reordered, like a completed task). */
    val siblings: List<String> get() = if (originSlot < 0) emptyList() else folderOrders[slotPage]?.filter { it != card?.id }.orEmpty()
    /** The open slot among [siblings] (0 = above the first), or -1 while over a chip or a bin. */
    var slot by mutableIntStateOf(-1); private set
    var originSlot = -1; private set
    private var lastSlot = -1
    /** Live vertical centre of each open row, and the live top of the open slot; reported by the rows themselves. */
    val rowCenters = HashMap<String, Float>()
    var slotTop = 0f

    val holding get() = card != null && !released
    val reorderable get() = originSlot >= 0
    val topLeft get() = finger - grab + fly.value

    /** Works out [hoverPage] from [finger]; set by a screen with folder chips. */
    var onHover: () -> Unit = {}
    /** Acts on a release over a chip and returns the root point the block should fly into; null otherwise. */
    var onDrop: (BoardCard) -> Offset? = { null }
    var onTrash: (BoardCard) -> Unit = {}
    /** A folder's new top-to-bottom order when the block lands somewhere new; the page is set when it came from another folder. */
    var onPlace: (order: List<String>, movedToPage: String?) -> Unit = { _, _ -> }
    var onSlotChange: () -> Unit = {}
    var onTargetEnter: () -> Unit = {}
    var onLand: () -> Unit = {}

    fun pickUp(card: BoardCard, rowTopLeft: Offset, rowSize: IntSize, fingerInRow: Offset, folderId: String, folderOrder: List<String>?) {
        if (this.card != null) return // the last one is still settling
        home = rowTopLeft; grab = fingerInRow; finger = rowTopLeft + fingerInRow; size = rowSize
        tilt = 0f; released = false; hoverPage = null; overTrash = false; overExtra = null
        originSlot = folderOrder?.indexOf(card.id) ?: -1
        homePage = folderId; slotPage = folderId; switched = false
        if (folderOrder != null) folderOrders[folderId] = folderOrder
        slot = originSlot; lastSlot = originSlot; slotTop = rowTopLeft.y
        this.card = card
        scope.launch { lift.animateTo(1.05f, spring(dampingRatio = .55f, stiffness = 700f)) }
    }

    fun moveBy(delta: Offset) {
        if (!holding) return
        finger += delta
        tilt = (tilt + delta.x * .22f).coerceIn(-7f, 7f)
        val trash = trashBounds != Rect.Zero && trashBounds.inflate(binPadPx).contains(finger)
        val extra = if (trash) null else extraBins.firstOrNull { it.bounds != Rect.Zero && it.bounds.inflate(binPadPx).contains(finger) }
        if ((trash && !overTrash) || (extra != null && extra !== overExtra)) onTargetEnter()
        overTrash = trash; overExtra = extra
        onHover()
        updateSlot()
    }

    /** Paging at the screen edge carried the held card over to [page]: its slot opens there instead. */
    fun enterFolder(page: String) {
        if (!holding || !reorderable || page == slotPage) return
        slotPage = page; switched = true; slot = -1; lastSlot = 0
    }

    /**
     * Moves the slot past a neighbour once the block's centre crosses that neighbour's (live) centre. The slot sits
     * between rows d-1 and d, so it only moves on when the block leaves it: no flicker at the edges.
     */
    fun updateSlot() {
        if (!holding || !reorderable) return
        if (hoverPage != null || overBin) { slot = -1; return }
        var d = if (slot >= 0) slot else lastSlot
        // The block's drawn centre (a tall one is drawn smaller, about the point it's held by).
        val centre = finger.y + (size.height / 2f - grab.y) * compact
        for (step in 0..siblings.size) {
            val below = siblings.getOrNull(d)?.let { rowCenters[it] }
            val above = siblings.getOrNull(d - 1)?.let { rowCenters[it] }
            d = when {
                below != null && centre > below -> d + 1
                above != null && centre < above -> d - 1
                else -> break
            }
        }
        if (d != slot && slot >= 0) onSlotChange()
        slot = d; lastSlot = d
    }

    fun drop(cancelled: Boolean = false) {
        val c = card ?: return
        if (released) return
        if (cancelled) { hoverPage = null; overTrash = false; overExtra = null; if (reorderable) { slotPage = homePage; slot = originSlot } }
        if (reorderable && slot < 0) slot = lastSlot
        released = true
        edgeSide = 0; edgeDwell = 0f
        val target = when {
            overTrash -> { onTrash(c); trashBounds.center }
            overExtra != null -> overExtra!!.let { bin -> bin.onDrop(c); bin.bounds.center }
            else -> onDrop(c)
        }
        scope.launch {
            val from = finger - grab
            if (target == null) {
                // Magnetic landing: the slot pulls the block in (slow, then quicker), it overshoots a touch, and
                // clicks into place with a small squash.
                val dest = Offset(home.x, if (reorderable) slotTop else home.y) - from
                val pull = dest - fly.value
                val len = pull.getDistance().coerceAtLeast(1f)
                val overshoot = pull / len * minOf(len * .08f, 18f)
                launch { animateTilt(0f) }
                launch { lift.animateTo(1f, tween(190, easing = MagnetPull)) }
                fly.animateTo(dest + overshoot, tween(190, easing = MagnetPull))
                onLand()
                launch { lift.animateTo(.97f, tween(70)); lift.animateTo(1f, spring(dampingRatio = .45f, stiffness = 900f)) }
                fly.animateTo(dest, spring(dampingRatio = .5f, stiffness = 1100f))
                val moved = slotPage != homePage
                if (reorderable && slot >= 0 && (moved || slot != originSlot)) {
                    onPlace(siblings.toMutableList().apply { add(slot.coerceIn(0, size), c.id) }, slotPage.takeIf { moved })
                }
            } else {
                // Sucked into the chip or the bin: it speeds up as it goes in.
                launch { shrink.animateTo(0f, tween(240, easing = MagnetPull)) }
                // It shrinks toward the finger, so that point is what lands on the target.
                fly.animateTo(target - finger, tween(240, easing = MagnetPull))
            }
            card = null; hoverPage = null; overTrash = false; overExtra = null; released = false; tilt = 0f
            slot = -1; originSlot = -1; lastSlot = -1; rowCenters.clear(); homePage = null; slotPage = null; switched = false
            fly.snapTo(Offset.Zero); lift.snapTo(1f); shrink.snapTo(1f)
        }
    }

    private suspend fun animateTilt(to: Float) {
        val a = Animatable(tilt)
        a.animateTo(to, tween(200)) { tilt = value }
    }
}

/**
 * Makes a row draggable: tap runs [onClick]; hold lifts it into [drag]. While lifted the row folds to no height (the
 * slot stands in for it) but stays composed and placed, pinned in its lazy list, so the drag keeps tracking.
 * [folderOrder] is its folder's top-to-bottom order, or null when it can't be reordered (it can still be dropped on a
 * chip or bin). [report] is false for a copy that isn't on screen (the habits' other month/year page).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun Modifier.dragRow(
    drag: TaskDragState, card: BoardCard, folderId: String, folderOrder: List<String>?,
    shape: Shape? = null, report: Boolean = true, onClick: () -> Unit
): Modifier {
    val haptics = LocalHapticFeedback.current
    val bounds = remember { arrayOf(Offset.Zero, Offset.Zero) } // top-left in root, size
    val lifted = drag.card?.id == card.id
    val pin = LocalPinnableContainer.current
    DisposableEffect(lifted, pin) { val handle = if (lifted) pin?.pin() else null; onDispose { handle?.release() } }
    DisposableEffect(card.id, report) { onDispose { if (report) drag.rowCenters.remove(card.id) } }
    val latestCard = rememberUpdatedState(card)
    val latestFolder = rememberUpdatedState(folderId)
    val latestOrder = rememberUpdatedState(folderOrder)
    return this
        .layout { m, c -> val p = m.measure(c); layout(p.width, if (lifted && drag.reorderable) 0 else p.height) { p.place(0, 0) } }
        .onGloballyPositioned {
            bounds[0] = it.positionInRoot(); bounds[1] = Offset(it.size.width.toFloat(), it.size.height.toFloat())
            if (report && !lifted && folderOrder != null) drag.rowCenters[card.id] = bounds[0].y + it.size.height / 2f
        }
        // The lifted block leaves an empty slot behind until it lands somewhere or slides back.
        .graphicsLayer { alpha = if (lifted) 0f else 1f }
        .then(if (shape != null) Modifier.clip(shape) else Modifier)
        // An empty long-click so letting go after a hold never also counts as a tap. It sits before the drag detector so the
        // detector sees the press first (the click consumes it, which the long-press wait would treat as a cancel).
        .combinedClickable(hapticFeedbackEnabled = false, onLongClick = {}, onClick = onClick)
        .pointerInput(card.id) {
            detectDragGesturesAfterLongPress(
                onDragStart = { at ->
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    drag.pickUp(latestCard.value, bounds[0], IntSize(bounds[1].x.toInt(), bounds[1].y.toInt()), at, latestFolder.value, latestOrder.value)
                },
                onDrag = { change, amount -> change.consume(); drag.moveBy(amount) },
                onDragEnd = { drag.drop() },
                onDragCancel = { drag.drop(cancelled = true) }
            )
        }
}

/** Registers a folder's scrolling list, so it scrolls when a held card nears its top or bottom. */
@Composable
internal fun Modifier.dragScrollArea(drag: TaskDragState, folderId: String, state: LazyListState): Modifier {
    DisposableEffect(folderId, state) { onDispose { if (drag.scrollAreas[folderId]?.first === state) drag.scrollAreas.remove(folderId) } }
    return onGloballyPositioned { drag.scrollAreas[folderId] = state to it.boundsInRoot() }
}

/**
 * What runs every frame while a card is held: turning the folder [pager] when the finger waits near a screen edge
 * ([pageIds] names its pages; [canTurnTo] can rule some out; [blockTurn] pauses it, e.g. up among the chips),
 * scrolling the slot's list near its ends, easing the block upright, and re-checking the slot as rows glide.
 */
@Composable
internal fun DragEffects(
    drag: TaskDragState,
    pager: PagerState? = null,
    pageIds: List<String> = emptyList(),
    canTurnTo: (String) -> Boolean = { true },
    blockTurn: (Offset) -> Boolean = { false },
    onFrame: suspend () -> Unit = {}
) {
    val density = LocalDensity.current
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    // About 2 cm in from each side (160 dp to the inch), so the thumb needn't reach the edge (curved screens included).
    val turnEdgePx = with(density) { 126.dp.toPx() }
    val scrollEdgePx = with(density) { 80.dp.toPx() }
    drag.compactPx = with(density) { 200.dp.toPx() }
    drag.binPadPx = with(density) { 14.dp.toPx() }
    val ids = rememberUpdatedState(pageIds)
    val canTurn = rememberUpdatedState(canTurnTo)
    val block = rememberUpdatedState(blockTurn)
    val frame = rememberUpdatedState(onFrame)
    LaunchedEffect(drag.holding) {
        drag.edgeDwell = 0f; drag.edgeSide = 0
        if (!drag.holding) return@LaunchedEffect
        var lastTurn = 0L
        var lastTick = System.currentTimeMillis()
        while (true) {
            // Timed by the clock, not by loop passes, so a slow frame doesn't stretch the waits.
            val now = System.currentTimeMillis(); val dt = (now - lastTick).coerceIn(0L, 100L).toFloat(); lastTick = now
            val f = drag.finger
            val screen = drag.screenBounds
            val side = when {
                pager == null || block.value(f) || drag.overBin -> 0
                f.x < screen.left + turnEdgePx -> -1
                f.x > screen.right - turnEdgePx -> 1
                else -> 0
            }
            val target = (pager?.currentPage ?: 0) + side
            val targetId = ids.value.getOrNull(target)
            if (side != 0 && pager != null && targetId != null && canTurn.value(targetId) && !pager.isScrollInProgress && now - lastTurn > 650) {
                drag.edgeSide = side
                drag.edgeDwell = (drag.edgeDwell + dt / 450f).coerceAtMost(1f)
                if (drag.edgeDwell >= 1f) {
                    drag.edgeDwell = 0f; lastTurn = now
                    haptic.performHapticFeedback(HapticFeedbackType.SegmentTick)
                    drag.enterFolder(targetId)
                    scope.launch { pager.animateScrollToPage(target, animationSpec = PageSpring) }
                }
            } else { drag.edgeDwell = (drag.edgeDwell - dt / 200f).coerceAtLeast(0f); if (drag.edgeDwell == 0f) drag.edgeSide = side }
            // Near the top or bottom of the slot's list, the list scrolls, faster the closer the finger gets.
            drag.scrollAreas[drag.slotPage]?.let { (state, area) ->
                val push = when {
                    f.y < area.top + scrollEdgePx -> -((area.top + scrollEdgePx - f.y) / scrollEdgePx).coerceIn(0f, 1f)
                    f.y > area.bottom - scrollEdgePx && !drag.overBin -> ((f.y - (area.bottom - scrollEdgePx)) / scrollEdgePx).coerceIn(0f, 1f)
                    else -> 0f
                }
                if (push != 0f) state.scrollBy(push * dt * .9f)
            }
            frame.value()
            drag.tilt *= .86f
            drag.updateSlot() // rows glide as the slot moves and the list scrolls, so re-check against where they are now
            delay(16)
        }
    }
}

/** Slow to start, quick at the end, like something drawn in by a magnet. */
private val MagnetPull = CubicBezierEasing(.55f, 0f, .85f, .45f)

/**
 * The open space a held task would drop into: a little taller than the task, with a dashed outline. It opens and
 * closes smoothly as the slot moves; [startOpen] is the task's own old place, which starts at the task's height so
 * nothing jumps when it's lifted. On release it closes to exactly the task's height for the landing.
 */
@Composable
internal fun DropSlot(drag: TaskDragState, index: Int, startOpen: Boolean) {
    val density = LocalDensity.current
    val rowPx = drag.size.height.toFloat()
    val fullPx = rowPx + with(density) { 14.dp.toPx() }
    val active = drag.slot == index
    val height = remember { Animatable(if (startOpen) rowPx else 0f) }
    LaunchedEffect(active, drag.released) {
        height.animateTo(if (!active) 0f else if (drag.released) rowPx else fullPx, spring(dampingRatio = .82f, stiffness = 520f))
    }
    val outline = veil(.32f)
    val wash = veil(.05f)
    Box(
        Modifier.fillMaxWidth()
            .layout { m, c -> val p = m.measure(c); layout(p.width, height.value.roundToInt().coerceAtLeast(0)) { p.place(0, 0) } }
            .onGloballyPositioned { if (active) drag.slotTop = it.positionInRoot().y }
            .drawBehind {
                val shown = (height.value / fullPx).coerceIn(0f, 1f)
                if (shown <= .02f) return@drawBehind
                val inset = 8.dp.toPx()
                val r = CornerRadius(14.dp.toPx())
                val box = Size(size.width - inset * 2, height.value - inset * 2)
                if (box.height <= 0f) return@drawBehind
                drawRoundRect(wash.copy(alpha = wash.alpha * shown), Offset(inset, inset), box, r)
                drawRoundRect(
                    outline.copy(alpha = outline.alpha * shown), Offset(inset, inset), box, r,
                    style = Stroke(1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(8.dp.toPx(), 6.dp.toPx())))
                )
            }
    )
}

/**
 * Draws the lifted card (via [ghost]) and, while one is held, the bins just above the bottom bar and the edge glow.
 * Goes last in a full-screen Box.
 */
@Composable
internal fun BoxScope.TaskDragLayer(drag: TaskDragState, ghostShape: Shape = RoundedCornerShape(18.dp), ghost: @Composable (BoardCard) -> Unit) {
    val density = LocalDensity.current
    val card = drag.card
    Box(Modifier.matchParentSize().onGloballyPositioned { drag.screenBounds = it.boundsInRoot() })
    // The wait at a screen edge before the folders turn, drawn as a glow filling in on that side.
    if (drag.edgeSide != 0) {
        val glow = Look.accent.fill
        Box(
            Modifier.align(if (drag.edgeSide < 0) Alignment.CenterStart else Alignment.CenterEnd).fillMaxHeight(.7f).width(44.dp)
                .graphicsLayer { alpha = drag.edgeDwell }
                .background(Brush.horizontalGradient(if (drag.edgeSide < 0) listOf(glow.copy(alpha = .45f), Color.Transparent) else listOf(Color.Transparent, glow.copy(alpha = .45f))))
        )
    }
    if (card != null) {
        // Over a chip or a bin the block draws in toward the finger, so the target it's about to land on stays visible.
        val aim by animateFloatAsState(if (drag.hoverPage != null || drag.overBin) .42f else 1f, spring(dampingRatio = .7f, stiffness = 500f), label = "dragAim")
        // A tall card is carried smaller, and grows back to size as it lands.
        val compact by animateFloatAsState(if (drag.released) 1f else drag.compact, spring(dampingRatio = .8f, stiffness = 500f), label = "dragCompact")
        // ...and steps aside from it: below the finger over a chip (they're at the top), above it over a bin (at the bottom).
        val aside by animateFloatAsState(if (drag.hoverPage != null) 1f else if (drag.overBin) -1f else 0f, spring(dampingRatio = .75f, stiffness = 450f), label = "dragAside")
        val gapPx = with(density) { 26.dp.toPx() }
        Surface(
            color = if (Look.light) Color(0xFAFFFFFF) else Color(0xF22C2A28),
            shape = ghostShape,
            border = GlassStroke,
            shadowElevation = 18.dp,
            modifier = Modifier.align(Alignment.TopStart)
                .width(with(density) { drag.size.width.toDp() })
                .graphicsLayer {
                    val at = drag.topLeft - drag.screenBounds.topLeft
                    translationX = at.x; translationY = at.y
                    val s = drag.lift.value * drag.shrink.value * aim * compact
                    // Over a bin, lift it until its bottom clears the bin's top.
                    val reach = if (aside >= 0f) drag.grab.y * s + gapPx
                        else (drag.finger.y + (drag.size.height - drag.grab.y) * s - (drag.trashBounds.top - gapPx / 2)).coerceAtLeast(0f)
                    translationY += aside * reach * drag.shrink.value
                    scaleX = s; scaleY = s
                    if (drag.size.width > 0 && drag.size.height > 0) transformOrigin = TransformOrigin(drag.grab.x / drag.size.width, drag.grab.y / drag.size.height)
                    rotationZ = drag.tilt
                    alpha = (drag.shrink.value * 1.6f).coerceAtMost(1f)
                }
        ) { ghost(card) }
    }
    // The bins sit above the held card, so even a tall one never hides them.
    AnimatedVisibility(
        visible = card != null && (!drag.released || drag.overBin),
        modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 16.dp),
        enter = fadeIn(tween(160)) + scaleIn(spring(dampingRatio = .6f, stiffness = 600f), initialScale = .5f) + slideInVertically(tween(220)) { it },
        exit = fadeOut(tween(160)) + scaleOut(tween(180), targetScale = .6f) + slideOutVertically(tween(200)) { it / 2 }
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(28.dp)) {
            drag.extraBins.forEach { bin -> DropBin(bin.icon, drag.overExtra === bin, bin.hotColor()) { bin.bounds = it } }
            DropBin(ZenIcon.Trash, drag.overTrash, Danger) { drag.trashBounds = it }
        }
    }
}

/** A round drop target under the list; it grows and fills with [hotColor] while the held card is over it. */
@Composable
private fun DropBin(icon: ZenIcon, hot: Boolean, hotColor: Color, onPlaced: (Rect) -> Unit) {
    val scale by animateFloatAsState(if (hot) 1.2f else 1f, spring(dampingRatio = .5f, stiffness = 600f), label = "binScale")
    val fill by animateColorAsState(if (hot) hotColor else TodoNav, tween(140), label = "binFill")
    val tint by animateColorAsState(if (hot) OnFill else TodoText, tween(140), label = "binIcon")
    Box(
        Modifier.onGloballyPositioned { onPlaced(it.boundsInRoot()) }
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .shadow(10.dp, CircleShape)
            .size(60.dp).clip(CircleShape).background(fill).border(GlassStroke, CircleShape),
        contentAlignment = Alignment.Center
    ) {
        if (icon == ZenIcon.Trash) {
            val lid by animateFloatAsState(if (hot) 1f else 0f, spring(dampingRatio = .45f, stiffness = 500f), label = "binLid")
            TrashGlyph(lid, tint, 24.dp)
        } else Glyph(icon, tint, 24.dp)
    }
}
