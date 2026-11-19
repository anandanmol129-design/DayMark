package com.anmol.daymark

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

enum class SearchSection(val title: String) { TODOS("To-dos"), CALENDAR("Calendar"), NOTES("Notes"), SAVED("Saved"), TRACK("Habits") }

/** One search result: the card, the section it is listed under, and a one-line secondary text. */
data class SearchHit(val card: BoardCard, val section: SearchSection, val title: String, val secondary: String)

/** Pure matching: case-insensitive substring on title and details (and link for saves), grouped in section order. */
internal fun searchBoard(snapshot: BoardSnapshot, query: String): List<SearchHit> {
    val q = query.trim()
    if (q.isEmpty()) return emptyList()
    val columnById = snapshot.columns.associateBy { it.id }
    val pageNames = (snapshot.todoPages + snapshot.notesPages).associate { it.id to it.name }
    fun place(card: BoardCard) = columnById[card.columnId]?.let { pageNames[it.pageId] ?: it.name }.orEmpty()
    return snapshot.cards.mapNotNull { card ->
        // Embedded picture lines are file paths, not text the user wrote.
        if (card.kind == CardKind.TIMER) return@mapNotNull null // time trackers are hidden for now
        if (card.isPrivate) return@mapNotNull null // private notes never show outside their locked section
        val details = card.details.lines().filterNot { it.trim().startsWith("![") }
        val matchedLine = details.firstOrNull { it.contains(q, true) }
        val inTitle = card.title.contains(q, true)
        if (!inTitle && matchedLine == null && !card.link.contains(q, true) && !card.comment.contains(q, true)) return@mapNotNull null
        val saved = card.workspace == Workspace.NOTES && columnById[card.columnId]?.pageId == DEFAULT_SAVED_PAGE_ID
        val section = when {
            saved -> SearchSection.SAVED
            card.workspace == Workspace.TODOS -> SearchSection.TODOS
            card.workspace == Workspace.CALENDAR -> SearchSection.CALENDAR
            card.workspace == Workspace.NOTES -> SearchSection.NOTES
            else -> SearchSection.TRACK
        }
        val due = listOfNotNull(card.dueDay?.let(::formatDue), card.dueMinute?.let(::formatClock)).joinToString(" · ")
        val context = when (section) {
            SearchSection.TODOS -> listOf(place(card), due)
            SearchSection.CALENDAR -> listOf(due)
            SearchSection.NOTES -> listOf(place(card))
            SearchSection.SAVED -> listOf(if (card.link.isNotBlank()) Saves.domainOf(card.link) else "Saved")
            SearchSection.TRACK -> listOf(habitSummary(card))
        }.filter { it.isNotBlank() }.joinToString(" · ")
        // When only the details matched, show the matching line so it is clear why this result came up.
        val secondary = if (!inTitle && matchedLine != null) Markdown.plain(matchedLine).ifBlank { matchedLine.trim() } else context
        SearchHit(card, section, card.title.ifBlank { if (saved) card.link.takeIf { it.isNotBlank() }?.let(Saves::domainOf) ?: "Image" else card.displayTitle.ifBlank { "Untitled" } }, secondary)
    }.groupBy { it.section }.toSortedMap().flatMap { (section, hits) ->
        when (section) {
            SearchSection.TODOS -> hits.sortedWith(compareBy<SearchHit> { it.card.completed }.thenBy { !it.card.important }.thenBy { it.card.dueDay ?: Long.MAX_VALUE }.thenByDescending { it.card.createdAt })
            SearchSection.CALENDAR -> hits.sortedWith(compareBy<SearchHit> { it.card.dueDay ?: Long.MAX_VALUE }.thenBy { it.card.dueMinute ?: -1 })
            SearchSection.TRACK -> hits.sortedWith(compareBy<SearchHit> { it.card.kind.ordinal }.thenBy { it.title.lowercase() })
            else -> hits.sortedByDescending { it.card.createdAt }
        }
    }
}

/** Opens search from any tab's header without threading a callback through every screen. */
internal val LocalOpenSearch = staticCompositionLocalOf<() -> Unit> { {} }

/** Full-screen search over the whole app; [onOpen] is told which result was tapped. */
@Composable
internal fun SearchScreen(snapshot: BoardSnapshot, onClose: () -> Unit, onOpen: (SearchHit) -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    val hits = remember(snapshot, query) { searchBoard(snapshot, query) }
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) {
        runCatching { focus.requestFocus() }
        keyboard?.show()
    }
    val blurred = LocalBackdrop.current
    Box(
        Modifier.fillMaxSize()
            .drawBehind {
                drawRect(TodoBackground)
                blurred?.let { drawBlurred(it) } ?: drawEdgeGlow(Look.accent.fill, EdgeGlowStrength) // the blurred backdrop already carries it
                drawRect(PageTint)
            }
            .pointerInput(Unit) { detectTapGestures { } }
            .statusBarsPadding()
            .imePadding()
    ) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 16.dp, top = 10.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Glyph(ZenIcon.ChevronLeft, TodoText, 24.dp, modifier = Modifier.clip(CircleShape).clickable(onClick = onClose).padding(horizontal = 12.dp, vertical = 2.dp).semantics { contentDescription = "Close search" })
                val shape = RoundedCornerShape(16.dp)
                Surface(color = TodoCard, shape = shape, border = GlassStroke, modifier = Modifier.weight(1f).glassBlur(shape)) {
                    Row(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Glyph(ZenIcon.Search, TodoBlue, 18.dp)
                        Spacer(Modifier.width(10.dp))
                        Box(Modifier.weight(1f)) {
                            if (query.isEmpty()) Text("Search everything", color = TodoMuted, fontSize = 17.sp)
                            BasicTextField(
                                value = query,
                                onValueChange = { query = it },
                                singleLine = true,
                                textStyle = TextStyle(color = TodoText, fontSize = 17.sp),
                                cursorBrush = SolidColor(TodoBlue),
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                                modifier = Modifier.fillMaxWidth().focusRequester(focus).semantics { contentDescription = "Search field" }
                            )
                        }
                        if (query.isNotEmpty()) Glyph(ZenIcon.Close, TodoMuted, 15.dp, modifier = Modifier.clip(CircleShape).clickable { query = "" }.padding(start = 8.dp).semantics { contentDescription = "Clear search" })
                    }
                }
            }
            when {
                query.isBlank() -> SearchNote("Search to-dos, events, notes, saves, habits and timers")
                hits.isEmpty() -> SearchNote("No matches")
                else -> LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    var previous: SearchSection? = null
                    hits.forEach { hit ->
                        if (hit.section != previous) {
                            previous = hit.section
                            item(key = "header-${hit.section.name}") {
                                Text(hit.section.title.uppercase(), color = TodoMuted, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp, modifier = Modifier.padding(start = 6.dp, top = 10.dp))
                            }
                        }
                        item(key = hit.card.id) { SearchRow(hit) { onOpen(hit) } }
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchNote(text: String) {
    Box(Modifier.fillMaxWidth().padding(top = 60.dp, start = 32.dp, end = 32.dp), contentAlignment = Alignment.TopCenter) {
        Text(text, color = TodoMuted, fontSize = 15.sp)
    }
}

@Composable
private fun SearchRow(hit: SearchHit, onClick: () -> Unit) {
    val dim = hit.card.completed && hit.section == SearchSection.TODOS
    val shape = RoundedCornerShape(16.dp)
    Surface(color = TodoCard, shape = shape, border = GlassStroke, modifier = Modifier.fillMaxWidth().glassBlur(shape).alpha(if (dim) .5f else 1f).clickable(onClick = onClick)) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 11.dp)) {
            Text(hit.title, color = TodoText, fontSize = 16.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis, textDecoration = if (dim) TextDecoration.LineThrough else null)
            if (hit.secondary.isNotBlank()) Text(hit.secondary, color = TodoMuted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
        }
    }
}
