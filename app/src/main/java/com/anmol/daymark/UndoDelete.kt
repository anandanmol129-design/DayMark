package com.anmol.daymark

import java.io.File

/**
 * Everything a delete took out of a snapshot, with the list positions it had, so an
 * undo can put it back exactly where it was. Image files stay on disk until the undo
 * window is over; [files] says which ones belong to the removed cards.
 */
internal data class Removed(
    val cards: List<IndexedValue<BoardCard>> = emptyList(),
    val columns: List<IndexedValue<BoardColumn>> = emptyList(),
    val todoPages: List<IndexedValue<BoardPage>> = emptyList(),
    val notesPages: List<IndexedValue<BoardPage>> = emptyList()
) {
    /** Image files owned by the removed cards: a save's picture and pictures embedded in note text. */
    val files: List<String>
        get() = cards.flatMap { (_, card) -> listOf(card.imagePath) + Markdown.filePaths(card.details) }.filter { it.isNotBlank() }.distinct()
}

/** Takes the given cards, columns and pages out, remembering where each one sat. */
internal fun BoardSnapshot.remove(cardIds: Set<String> = emptySet(), columnIds: Set<String> = emptySet(), pageIds: Set<String> = emptySet()): Pair<BoardSnapshot, Removed> {
    val removed = Removed(
        cards = cards.withIndex().filter { it.value.id in cardIds }.toList(),
        columns = columns.withIndex().filter { it.value.id in columnIds }.toList(),
        todoPages = todoPages.withIndex().filter { it.value.id in pageIds }.toList(),
        notesPages = notesPages.withIndex().filter { it.value.id in pageIds }.toList()
    )
    val next = copy(
        cards = cards.filterNot { it.id in cardIds },
        columns = columns.filterNot { it.id in columnIds },
        todoPages = todoPages.filterNot { it.id in pageIds },
        notesPages = notesPages.filterNot { it.id in pageIds }
    )
    return next to removed
}

/** Puts [removed] back at its old positions; anything changed or added since is kept. */
internal fun BoardSnapshot.restore(removed: Removed): BoardSnapshot = copy(
    cards = cards.reinsert(removed.cards) { it.id },
    columns = columns.reinsert(removed.columns) { it.id },
    todoPages = todoPages.reinsert(removed.todoPages) { it.id },
    notesPages = notesPages.reinsert(removed.notesPages) { it.id }
)

/** Inserts each item at its old index (clamped to the current size), skipping ones already present. */
private fun <T> List<T>.reinsert(items: List<IndexedValue<T>>, id: (T) -> String): List<T> {
    val present = map(id).toSet()
    val result = toMutableList()
    items.filter { id(it.value) !in present }.sortedBy { it.index }.forEach { result.add(it.index.coerceIn(0, result.size), it.value) }
    return result
}

internal fun deleteFiles(paths: List<String>) = paths.forEach { runCatching { File(it).delete() } }

/** The snackbar text for a deleted card, e.g. "Task deleted". */
internal fun deletedMessage(card: BoardCard, snapshot: BoardSnapshot): String {
    val inSaved = snapshot.columns.any { it.id == card.columnId && it.pageId == DEFAULT_SAVED_PAGE_ID }
    return when {
        inSaved || card.link.isNotBlank() -> "Saved item deleted"
        card.kind == CardKind.HABIT -> "Habit deleted"
        card.kind == CardKind.TIMER -> "Timer deleted"
        card.kind == CardKind.EVENT || card.workspace == Workspace.CALENDAR -> "Event deleted"
        card.kind == CardKind.NOTE -> "Note deleted"
        else -> "Task deleted"
    }
}
