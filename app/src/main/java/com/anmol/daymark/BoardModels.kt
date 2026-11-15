package com.anmol.daymark

import android.content.Context
import kotlinx.coroutines.asCoroutineDispatcher
import java.util.concurrent.Executors
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

enum class Workspace(val id: String, val title: String, val icon: ZenIcon) {
    TODOS("todos", "To-dos", ZenIcon.Inbox),
    CALENDAR("calendar", "Calendar", ZenIcon.Calendar),
    NOTES("notes", "Notes", ZenIcon.Note),
    TRACK("track", "Habits", ZenIcon.Habit);

    companion object {
        fun fromId(id: String?) = entries.firstOrNull { it.id == id } ?: TODOS
    }
}

enum class BoardView(val id: String, val label: String) {
    LIST("list", "List"), BOARD("board", "Board");
    companion object {
        // Older saves used one/two/four/all column layouts. Keep them on the board
        // when opening the new two-view switcher.
        fun fromId(id: String?) = if (id == LIST.id) LIST else BOARD
    }
}

enum class CardKind(val id: String) {
    TASK("task"), EVENT("event"), NOTE("note"), HABIT("habit"), TIMER("timer");
    companion object { fun fromId(id: String?) = entries.firstOrNull { it.id == id } ?: TASK }
}

data class BoardColumn(
    val id: String = UUID.randomUUID().toString(),
    val workspace: Workspace,
    val name: String,
    val pageId: String = ""
)

data class BoardPage(
    val id: String = UUID.randomUUID().toString(),
    val name: String
)

data class BoardCard(
    val id: String = UUID.randomUUID().toString(),
    val workspace: Workspace,
    val columnId: String,
    val kind: CardKind,
    val title: String,
    val details: String = "",
    val dueDay: Long? = null,
    val important: Boolean = false,
    val completed: Boolean = false,
    val completedDay: Long? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val activeSinceMillis: Long? = null,
    val trackedMillis: Long = 0,
    val appPackage: String = "",
    val appLabel: String = "",
    val deepLink: String = "",
    val calendarSourceId: String = "",
    val calendarColor: Long? = null,
    val dueMinute: Int? = null,
    val durationMinutes: Int = 0,
    val link: String = "",
    val imagePath: String = "",
    val previewDone: Boolean = false,
    val comment: String = "", // your own remark on a saved item, kept apart from what was saved
    /** Lives in the locked Private section of Notes and Saved; hidden everywhere else. */
    val isPrivate: Boolean = false,
    /** Repeat rule id (see [Repeat]); "" for none. */
    val repeat: String = "",
    /** First day of the repeat series, so monthly/yearly rules can return to e.g. the 31st after a short month. */
    val repeatAnchor: Long? = null,
    /** The open card a completed repeating task produced, so re-completing it never duplicates. */
    val spawnedId: String = "",
    /** Habit check-in days (epoch days, ascending, at most [CHECK_IN_LIMIT]). */
    val checkIns: List<Long> = emptyList(),
    /** Reminder delivery: "" none, [REMIND_NOTIFY] or [REMIND_ALARM]. Scheduled by [Reminders]. */
    val remind: String = "",
    /** Minutes before the due time the reminder goes off (0 = at the time). Habits remind at their daily time. */
    val remindLeadMinutes: Int = 0,
    /** Habit colour: an index into [HabitPalette], or -1 to pick one from the id. */
    val habitColor: Int = -1,
    /** A habit that happens at a set time (an event) rather than something to tick off (a task). */
    val habitEvent: Boolean = false
)

data class BoardSnapshot(
    val cards: List<BoardCard>,
    val columns: List<BoardColumn>,
    val views: Map<Workspace, BoardView>,
    val todoPages: List<BoardPage> = listOf(BoardPage(DEFAULT_TODO_PAGE_ID, "Inbox")),
    val notesPages: List<BoardPage> = listOf(BoardPage(DEFAULT_NOTES_PAGE_ID, "Inbox"))
) {
    fun columnsFor(workspace: Workspace) = columns.filter { it.workspace == workspace }
    fun cardsFor(workspace: Workspace) = cards.filter { it.workspace == workspace }
    fun columnsFor(page: BoardPage, workspace: Workspace = Workspace.TODOS) = columns.filter { it.workspace == workspace && it.pageId == page.id }
}

const val DEFAULT_TODO_PAGE_ID = "todo-inbox-page"
const val DEFAULT_NOTES_PAGE_ID = "notes-inbox-page"
const val DEFAULT_SAVED_PAGE_ID = "notes-saved-page"

private fun defaultColumns(): List<BoardColumn> = listOf(
    BoardColumn(workspace = Workspace.TODOS, name = "Inbox", pageId = DEFAULT_TODO_PAGE_ID),
    BoardColumn(workspace = Workspace.CALENDAR, name = "Today"),
    BoardColumn(workspace = Workspace.CALENDAR, name = "This week"),
    BoardColumn(workspace = Workspace.CALENDAR, name = "Later"),
    BoardColumn(workspace = Workspace.CALENDAR, name = "Past"),
    BoardColumn(workspace = Workspace.NOTES, name = "Inbox", pageId = DEFAULT_NOTES_PAGE_ID),
    BoardColumn(workspace = Workspace.TRACK, name = "Habits"),
    BoardColumn(workspace = Workspace.TRACK, name = "Timers")
)

class BoardStore(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences("daymark_boards", Context.MODE_PRIVATE)

    fun load(): BoardSnapshot = withSavedFolder(read())

    // Notes always ends with a "Saved" folder for shared links and images.
    private fun withSavedFolder(snapshot: BoardSnapshot): BoardSnapshot {
        if (snapshot.notesPages.any { it.id == DEFAULT_SAVED_PAGE_ID }) return snapshot
        val page = BoardPage(DEFAULT_SAVED_PAGE_ID, "Saved")
        return snapshot.copy(
            notesPages = snapshot.notesPages + page,
            columns = snapshot.columns + BoardColumn(workspace = Workspace.NOTES, name = "Saved", pageId = page.id)
        )
    }

    private fun read(): BoardSnapshot = runCatching {
        val raw = prefs.getString("snapshot", null) ?: return migrateOldTasks()
        parse(raw)
    }.getOrElse { BoardSnapshot(emptyList(), defaultColumns(), Workspace.entries.associateWith { BoardView.BOARD }) }

    /** Parses a stored snapshot; throws on malformed input so restores can refuse bad files. */
    fun parse(raw: String): BoardSnapshot {
        val root = JSONObject(raw)
        val storedColumns = root.optJSONArray("columns")?.toColumns().orEmpty().ifEmpty { defaultColumns() }
        val cards = root.optJSONArray("cards")?.toCards().orEmpty()
        val storedPages = root.optJSONArray("todoPages")?.toPages().orEmpty()
        val storedNotesPages = root.optJSONArray("notesPages")?.toPages().orEmpty()
        val (todoColumns, todoPages, normalizedCards) = normalizeTodoPages(storedColumns, storedPages, cards)
        val (columns, notesPages) = normalizeNotesPages(todoColumns, storedNotesPages)
        val viewsJson = root.optJSONObject("views") ?: JSONObject()
        val views = Workspace.entries.associateWith { BoardView.fromId(viewsJson.optString(it.id, "board")) }
        return withSavedFolder(BoardSnapshot(normalizedCards, columns, views + (Workspace.TODOS to BoardView.LIST) + (Workspace.NOTES to BoardView.LIST), todoPages, notesPages))
    }

    /** Every write goes through here, so pending reminder alarms always follow the saved data. */
    fun save(snapshot: BoardSnapshot) {
        prefs.edit().putString("snapshot", encode(snapshot)).apply()
        runCatching { Reminders.sync(appContext, snapshot) }
    }

    /** Encodes and writes off the main thread, in order, so taps never wait on JSON or disk. */
    fun saveInBackground(snapshot: BoardSnapshot, after: () -> Unit = {}) {
        writer.execute { save(snapshot); after() }
    }

    companion object {
        private val writer = Executors.newSingleThreadExecutor()
        /** Runs after any queued saves, so reads always see the latest write. */
        val io = writer.asCoroutineDispatcher()
        /** Queues a read-modify-write behind pending saves (used by the widget). */
        fun enqueue(block: () -> Unit) = writer.execute(block)
    }

    fun encode(snapshot: BoardSnapshot): String = JSONObject().apply {
        put("columns", JSONArray().apply { snapshot.columns.forEach { put(it.toJson()) } })
        put("cards", JSONArray().apply { snapshot.cards.forEach { put(it.toJson()) } })
        put("views", JSONObject().apply { snapshot.views.forEach { (workspace, view) -> put(workspace.id, view.id) } })
        put("todoPages", JSONArray().apply { snapshot.todoPages.forEach { put(it.toJson()) } })
        put("notesPages", JSONArray().apply { snapshot.notesPages.forEach { put(it.toJson()) } })
    }.toString()

    private fun migrateOldTasks(): BoardSnapshot {
        val columns = defaultColumns()
        val todoColumn = columns.first { it.workspace == Workspace.TODOS }
        val doneColumn = columns.filter { it.workspace == Workspace.TODOS }.last()
        val cards = runCatching {
            val old = appContext.getSharedPreferences("daymark_tasks", Context.MODE_PRIVATE)
            val array = JSONArray(old.getString("items", "[]"))
            (0 until array.length()).map { i ->
                val task = array.getJSONObject(i)
                val done = task.optBoolean("done")
                BoardCard(
                    id = task.optString("id").ifBlank { UUID.randomUUID().toString() },
                    workspace = Workspace.TODOS,
                    columnId = if (done) doneColumn.id else todoColumn.id,
                    kind = CardKind.TASK,
                    title = task.optString("title"),
                    dueDay = if (task.isNull("dueDay")) null else task.optLong("dueDay"),
                    important = task.optBoolean("important"),
                    completed = done,
                    createdAt = task.optLong("createdAt", System.currentTimeMillis())
                )
            }
        }.getOrDefault(emptyList())
        return BoardSnapshot(cards, columns, Workspace.entries.associateWith { BoardView.BOARD } + (Workspace.TODOS to BoardView.LIST) + (Workspace.NOTES to BoardView.LIST)).also(::save)
    }
}

private fun BoardColumn.toJson() = JSONObject().apply {
    put("id", id); put("workspace", workspace.id); put("name", name); put("pageId", pageId)
}

private fun BoardPage.toJson() = JSONObject().apply { put("id", id); put("name", name) }

private fun BoardCard.toJson() = JSONObject().apply {
    put("id", id); put("workspace", workspace.id); put("columnId", columnId); put("kind", kind.id)
    put("title", title); put("details", details); put("dueDay", dueDay ?: JSONObject.NULL)
    put("important", important); put("completed", completed); put("completedDay", completedDay ?: JSONObject.NULL)
    put("createdAt", createdAt); put("activeSinceMillis", activeSinceMillis ?: JSONObject.NULL); put("trackedMillis", trackedMillis)
    put("appPackage", appPackage); put("appLabel", appLabel); put("deepLink", deepLink); put("calendarSourceId", calendarSourceId)
    put("calendarColor", calendarColor ?: JSONObject.NULL)
    put("dueMinute", dueMinute ?: JSONObject.NULL); put("durationMinutes", durationMinutes)
    put("link", link); put("imagePath", imagePath); put("previewDone", previewDone); put("comment", comment); put("private", isPrivate)
    put("repeat", repeat); put("repeatAnchor", repeatAnchor ?: JSONObject.NULL); put("spawnedId", spawnedId)
    put("checkIns", JSONArray().apply { checkIns.forEach { put(it) } })
    put("remind", remind); put("remindLeadMinutes", remindLeadMinutes)
    put("habitColor", habitColor); put("habitEvent", habitEvent)
}

private fun JSONArray.toColumns() = (0 until length()).map { i ->
    val item = getJSONObject(i)
    BoardColumn(item.optString("id").ifBlank { UUID.randomUUID().toString() }, Workspace.fromId(item.optString("workspace")), item.optString("name", "Column"), item.optString("pageId"))
}

private fun JSONArray.toPages() = (0 until length()).map { i ->
    val item = getJSONObject(i)
    BoardPage(item.optString("id").ifBlank { UUID.randomUUID().toString() }, item.optString("name", "Page"))
}

/**
 * Each to-do page is a single list; completion is a flag on the task. Older saves
 * split pages into To do / Doing / Done, so fold those into one list per page:
 * tasks from Done come back completed, everything else stays open.
 */
private fun normalizeTodoPages(
    storedColumns: List<BoardColumn>,
    storedPages: List<BoardPage>,
    cards: List<BoardCard>
): Triple<List<BoardColumn>, List<BoardPage>, List<BoardCard>> {
    val (columns, pages, legacyCards) = normalizeLegacyTodoPages(storedColumns, storedPages, cards)
    val otherColumns = columns.filterNot { it.workspace == Workspace.TODOS }
    val todoColumns = columns.filter { it.workspace == Workspace.TODOS }
    val keep = pages.associate { page ->
        // Prefer lists that were actually saved; the legacy pass may have just invented new ones.
        val saved = storedColumns.map { it.id }.toSet()
        val pageColumns = todoColumns.filter { it.pageId == page.id }.sortedBy { if (it.id in saved) 0 else 1 }
        val primary = pageColumns.firstOrNull { it.id in saved && it.name.equals("To do", true) } ?: pageColumns.firstOrNull()
        page.id to (primary?.copy(name = page.name) ?: BoardColumn(workspace = Workspace.TODOS, name = page.name, pageId = page.id))
    }
    val doneIds = todoColumns.filter { it.name.equals("Done", true) }.map { it.id }.toSet()
    val pageOf = todoColumns.associate { it.id to it.pageId }
    val merged = legacyCards.map { card ->
        val target = pageOf[card.columnId]?.let { keep[it] } ?: return@map card
        if (target.id == card.columnId && card.columnId !in doneIds) card
        else card.copy(columnId = target.id, completed = card.completed || card.columnId in doneIds)
    }
    return Triple(otherColumns + pages.mapNotNull { keep[it.id] }, pages, merged)
}

private fun normalizeLegacyTodoPages(
    storedColumns: List<BoardColumn>,
    storedPages: List<BoardPage>,
    cards: List<BoardCard>
): Triple<List<BoardColumn>, List<BoardPage>, List<BoardCard>> {
    val todoColumns = storedColumns.filter { it.workspace == Workspace.TODOS }
    val otherColumns = storedColumns.filterNot { it.workspace == Workspace.TODOS }
    val names = listOf("To do", "Doing", "Done")

    if (storedPages.isNotEmpty() && todoColumns.any { it.pageId.isNotBlank() }) {
        val pages = storedPages
        val normalized = todoColumns.map { column ->
            if (column.pageId.isNotBlank()) column else column.copy(pageId = pages.first().id)
        }
        val existingPageIds = pages.map { it.id }.toSet()
        val repaired = normalized.map { if (it.pageId in existingPageIds) it else it.copy(pageId = pages.first().id) }
        val withLists = pages.flatMap { page ->
            val pageColumns = repaired.filter { it.pageId == page.id }
            names.mapNotNull { name -> pageColumns.firstOrNull { it.name.equals(name, true) } ?: BoardColumn(workspace = Workspace.TODOS, name = name, pageId = page.id).takeIf { pageColumns.none { col -> col.name.equals(name, true) } } }
        }
        return Triple(otherColumns + withLists + repaired.filterNot { col -> names.any { it.equals(col.name, true) } }, pages, cards)
    }

    // Older versions treated every to-do column as its own page. Keep the three
    // built-in lists together and turn each custom column into a page with the
    // same title, placing its existing tasks in that page's To do list.
    val defaultPage = BoardPage(DEFAULT_TODO_PAGE_ID, "Inbox")
    val legacyDefaultColumns = names.mapNotNull { name -> todoColumns.firstOrNull { it.name.equals(name, true) } }
    val baseColumns = names.map { name ->
        legacyDefaultColumns.firstOrNull { it.name.equals(name, true) }
            ?.copy(name = name, pageId = defaultPage.id)
            ?: BoardColumn(workspace = Workspace.TODOS, name = name, pageId = defaultPage.id)
    }
    val customColumns = todoColumns.filterNot { col -> names.any { it.equals(col.name, true) } }
    val pages = mutableListOf(defaultPage)
    val pageColumns = mutableListOf<BoardColumn>().apply { addAll(baseColumns) }
    val cardMoves = mutableMapOf<String, String>()
    customColumns.forEach { oldColumn ->
        val page = BoardPage(name = oldColumn.name)
        pages += page
        val newColumns = names.map { name -> BoardColumn(workspace = Workspace.TODOS, name = name, pageId = page.id) }
        pageColumns += newColumns
        val destination = newColumns.first { it.name == if (cards.any { card -> card.columnId == oldColumn.id && card.completed }) "Done" else "To do" }
        cards.filter { it.columnId == oldColumn.id }.forEach { cardMoves[it.id] = destination.id }
    }
    val updatedCards = cards.map { card -> cardMoves[card.id]?.let { card.copy(columnId = it) } ?: card }
    return Triple(otherColumns + pageColumns, pages, updatedCards)
}

private fun normalizeNotesPages(
    columns: List<BoardColumn>,
    storedPages: List<BoardPage>
): Pair<List<BoardColumn>, List<BoardPage>> {
    val noteColumns = columns.filter { it.workspace == Workspace.NOTES }
    val otherColumns = columns.filterNot { it.workspace == Workspace.NOTES }
    if (storedPages.isNotEmpty()) {
        val pages = storedPages
        val validIds = pages.map { it.id }.toSet()
        val assigned = noteColumns.mapIndexed { index, column ->
            val pageId = column.pageId.takeIf { it in validIds } ?: pages.getOrNull(index)?.id ?: pages.first().id
            column.copy(pageId = pageId)
        }
        val missingFolders = pages.filter { page -> assigned.none { it.pageId == page.id } }
            .map { page -> BoardColumn(workspace = Workspace.NOTES, name = page.name, pageId = page.id) }
        return otherColumns + assigned + missingFolders to pages
    }

    // Prior versions treated the Inbox, Ideas, and Reference columns as folders.
    // Keep their IDs and cards, and use each existing column as its own page.
    val legacyFolders = noteColumns.ifEmpty {
        listOf(BoardColumn(workspace = Workspace.NOTES, name = "Inbox", pageId = DEFAULT_NOTES_PAGE_ID))
    }
    val pages = legacyFolders.mapIndexed { index, column ->
        val id = if (index == 0 && column.name.equals("Inbox", true)) DEFAULT_NOTES_PAGE_ID else column.id
        BoardPage(id = id, name = column.name)
    }
    val folders = legacyFolders.mapIndexed { index, column -> column.copy(pageId = pages[index].id) }
    return otherColumns + folders to pages
}

private fun JSONArray.toCards() = (0 until length()).map { i ->
    val item = getJSONObject(i)
    BoardCard(
        id = item.optString("id").ifBlank { UUID.randomUUID().toString() },
        workspace = Workspace.fromId(item.optString("workspace")),
        columnId = item.optString("columnId"),
        kind = CardKind.fromId(item.optString("kind")),
        title = item.optString("title"),
        details = item.optString("details"),
        dueDay = if (item.isNull("dueDay")) null else item.optLong("dueDay"),
        important = item.optBoolean("important"),
        completed = item.optBoolean("completed"),
        completedDay = if (item.isNull("completedDay")) null else item.optLong("completedDay"),
        createdAt = item.optLong("createdAt", System.currentTimeMillis()),
        activeSinceMillis = if (item.isNull("activeSinceMillis")) null else item.optLong("activeSinceMillis"),
        trackedMillis = item.optLong("trackedMillis"),
        appPackage = item.optString("appPackage"),
        appLabel = item.optString("appLabel"),
        deepLink = item.optString("deepLink"),
        calendarSourceId = item.optString("calendarSourceId"),
        calendarColor = if (item.isNull("calendarColor")) null else item.optLong("calendarColor"),
        dueMinute = if (item.isNull("dueMinute")) null else item.optInt("dueMinute"),
        durationMinutes = item.optInt("durationMinutes"),
        link = item.optString("link"),
        imagePath = item.optString("imagePath"),
        previewDone = item.optBoolean("previewDone"),
        comment = item.optString("comment"),
        isPrivate = item.optBoolean("private"),
        repeat = item.optString("repeat"),
        repeatAnchor = if (item.isNull("repeatAnchor")) null else item.optLong("repeatAnchor"),
        spawnedId = item.optString("spawnedId"),
        checkIns = item.optJSONArray("checkIns")?.let { a -> (0 until a.length()).map { a.getLong(it) }.distinct().sorted().takeLast(CHECK_IN_LIMIT) }
            // Saves from before history existed: the one remembered check-in becomes the history.
            ?: listOfNotNull(if (item.isNull("completedDay")) null else item.optLong("completedDay")).filter { CardKind.fromId(item.optString("kind")) == CardKind.HABIT },
        remind = item.optString("remind").takeIf { it == REMIND_NOTIFY || it == REMIND_ALARM }.orEmpty(),
        remindLeadMinutes = item.optInt("remindLeadMinutes").coerceIn(0, 7 * 24 * 60),
        habitColor = item.optInt("habitColor", -1).coerceIn(-1, HabitPalette.size - 1),
        habitEvent = item.optBoolean("habitEvent")
    )
}
