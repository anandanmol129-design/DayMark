package com.anmol.daymark

import android.content.Context
import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.speech.RecognizerIntent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.net.Uri
import androidx.activity.result.PickVisualMediaRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.ui.draw.scale
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.util.Locale

@Composable
internal fun DaymarkApp(
    store: BoardStore,
    resumeTick: Int,
    widgetRequest: WidgetRequest? = null,
    onWidgetRequestHandled: () -> Unit = {}
) {
    var snapshot by remember { mutableStateOf(store.load()) }
    val syncContext = LocalContext.current
    LaunchedEffect(resumeTick) {
        if (resumeTick > 0) {
            snapshot = withContext(BoardStore.io) { store.load() }
            // After the reload, so the sync's changes land on the fresh cards.
            CalendarSync.syncNow(syncContext, manual = false)
        }
    }
    // A reminder's Done/Snooze can change the store while the app is open; pick that up.
    val reminderEdits by Reminders.externalEdits.collectAsState()
    LaunchedEffect(reminderEdits) { if (reminderEdits > 0) snapshot = withContext(BoardStore.io) { store.load() } }
    var workspaceId by rememberSaveable { mutableStateOf(Workspace.TODOS.id) }
    // Today is a destination outside the Workspace enum (it has no columns, pages or saved views). It's where a normal launch lands;
    // a launch that targets a workspace (widget tap) starts on that workspace instead.
    var todayActive by rememberSaveable { mutableStateOf(widgetRequest == null) }
    var showComposer by rememberSaveable { mutableStateOf(false) }
    var showColumnDialog by rememberSaveable { mutableStateOf(false) }
    var showPageDialog by rememberSaveable { mutableStateOf(false) }
    var showFolderDialog by rememberSaveable { mutableStateOf(false) }
    var showAppPicker by rememberSaveable { mutableStateOf(false) }
    var draftColumnId by rememberSaveable { mutableStateOf("") }
    var editingCardId by rememberSaveable { mutableStateOf<String?>(null) }
    var pageToDelete by remember { mutableStateOf<BoardPage?>(null) }
    var pageToRename by remember { mutableStateOf<BoardPage?>(null) }
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var showCalendarSettings by rememberSaveable { mutableStateOf(false) }
    var showStorage by rememberSaveable { mutableStateOf(false) }
    var showSearch by rememberSaveable { mutableStateOf(false) }
    var viewingSave by remember { mutableStateOf<BoardCard?>(null) }
    var calendarJump by remember { mutableStateOf<Pair<Long, Int>?>(null) }
    var composerDay by remember { mutableStateOf<Long?>(null) }
    var pendingRestore by remember { mutableStateOf<Uri?>(null) }
    var backupBusy by remember { mutableStateOf(false) }
    var lastBackup by remember { mutableStateOf(0L) }
    var selectedColumnByWorkspace by remember { mutableStateOf(emptyMap<String, String>()) }
    var selectedTodoPageId by rememberSaveable { mutableStateOf(DEFAULT_TODO_PAGE_ID) }
    var selectedNotesPageId by rememberSaveable { mutableStateOf(DEFAULT_NOTES_PAGE_ID) }
    var trackPage by rememberSaveable { mutableStateOf(0) } // 0 is Focus (the tab opens on it), 1 is Habits
    var pendingAppChoice by remember { mutableStateOf<AppChoice?>(null) }
    var snackbarMessage by remember { mutableStateOf("") }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val workspace = Workspace.fromId(workspaceId)
    val columns = snapshot.columnsFor(workspace)
    val view = snapshot.views[workspace] ?: BoardView.BOARD
    val privateOpen by PrivateVault.unlocked.collectAsState()
    // Notes shows either its everyday items or, while unlocked, only the private ones.
    val boardSnapshot = remember(snapshot, privateOpen) { snapshot.copy(cards = snapshot.cards.filter { it.workspace != Workspace.NOTES || it.isPrivate == privateOpen }) }
    // Leaving Notes locks Private again.
    FollowAccent(if (todayActive) null else workspace, privateOpen)
    LaunchedEffect(todayActive, workspace) { if (todayActive || workspace != Workspace.NOTES) PrivateVault.lock() }

    fun update(next: BoardSnapshot) {
        snapshot = next
        store.saveInBackground(next) { WidgetActions.refreshAll(context) }
    }

    fun showMessage(message: String) {
        scope.launch {
            // A newer message replaces the current one (which ends a delete's undo window) instead of queueing behind it.
            snackbar.currentSnackbarData?.dismiss()
            snackbar.showSnackbar(message)
        }
    }

    /**
     * Removes cards (and, for a page, its columns and the page itself) and offers Undo. Image files
     * are only deleted once the snackbar is gone without Undo, so an undone card still has its picture.
     */
    fun deleteWithUndo(message: String, cardIds: Set<String>, columnIds: Set<String> = emptySet(), pageIds: Set<String> = emptySet(), onUndo: () -> Unit = {}) {
        val (next, removed) = snapshot.remove(cardIds, columnIds, pageIds)
        update(next)
        scope.launch {
            snackbar.currentSnackbarData?.dismiss()
            var undone = false
            try {
                if (snackbar.showSnackbar(message, actionLabel = "Undo", duration = SnackbarDuration.Short) == SnackbarResult.ActionPerformed) {
                    undone = true
                    update(snapshot.restore(removed))
                    onUndo()
                }
            } finally {
                // Dismissed, timed out, replaced, or the screen went away: the window is over.
                if (!undone) withContext(NonCancellable + Dispatchers.IO) { deleteFiles(removed.files) }
            }
        }
    }

    /** Long-press Delete on a to-do folder chip: no confirm (Undo brings back the folder, its list and its tasks). Inbox stays. */
    fun deleteTodoFolder(page: BoardPage) {
        val index = snapshot.todoPages.indexOfFirst { it.id == page.id }
        if (index < 0 || page.id == DEFAULT_TODO_PAGE_ID) return
        val wasSelected = selectedTodoPageId == page.id
        val neighbour = (snapshot.todoPages.getOrNull(index - 1) ?: snapshot.todoPages.first { it.id != page.id }).id
        val doomed = snapshot.columns.filter { it.pageId == page.id }.map { it.id }.toSet()
        deleteWithUndo("Folder deleted", snapshot.cards.filter { it.columnId in doomed }.map { it.id }.toSet(), doomed, setOf(page.id)) { if (wasSelected) selectedTodoPageId = page.id }
        if (wasSelected) selectedTodoPageId = neighbour
    }

    fun togglePrivate() {
        if (privateOpen) { PrivateVault.lock(); showMessage("Private locked"); return }
        PrivateVault.unlock(context) { ok, error ->
            if (ok) { todayActive = false; workspaceId = Workspace.NOTES.id; showSearch = false; showSettings = false }
            else error?.let(::showMessage)
        }
    }

    fun setPrivate(card: BoardCard) {
        val making = !card.isPrivate
        if (making && !PrivateVault.available(context)) { showMessage("Set a screen lock on your phone to use Private"); return }
        update(snapshot.copy(cards = snapshot.cards.map { if (it.id == card.id) it.copy(isPrivate = making) else it }))
        showMessage(if (making) "Moved to Private · hold Notes to open" else "Moved out of Private")
    }

    /** Habits in the order they were started; time trackers stay hidden. */
    val habits = remember(snapshot.cards) { snapshot.cards.filter { it.kind == CardKind.HABIT }.sortedBy { it.createdAt } }
    fun toggleHabitDay(card: BoardCard, day: Long) = update(snapshot.toggleCheckIn(card.id, day, LocalDate.now().toEpochDay()))
    /** A blank habit in the Habits column, wearing the first colour no habit has yet. */
    fun newHabit(): BoardCard {
        val column = snapshot.columnsFor(Workspace.TRACK).let { cols -> cols.firstOrNull { it.name.equals("Habits", true) } ?: cols.firstOrNull() }
        val used = habits.map(::habitColorIndex).toSet()
        val color = HabitPalette.indices.firstOrNull { it !in used } ?: (habits.size % HabitPalette.size)
        return BoardCard(workspace = Workspace.TRACK, columnId = column?.id ?: "habits", kind = CardKind.HABIT, title = "", habitColor = color)
    }
    fun saveHabit(card: BoardCard) {
        var next = snapshot
        // Very old saves may lack the Habits column; add it so the habit has a home.
        if (next.columns.none { it.id == card.columnId }) next = next.copy(columns = next.columns + BoardColumn(id = card.columnId, workspace = Workspace.TRACK, name = "Habits"))
        val existing = next.cards.any { it.id == card.id }
        update(next.copy(cards = if (existing) next.cards.map { if (it.id == card.id) card else it } else next.cards + card))
        showComposer = false
        editingCardId = null
        showMessage(if (existing) "Habit updated" else "Habit added")
    }

    // Focus (Focus.kt): the things you time, the running session, and what happens when it ends.
    val trackers = remember(snapshot.cards) { snapshot.cards.filter { it.kind == CardKind.TIMER }.sortedBy { it.createdAt } }
    remember { FocusClock.load(context) }
    // A deep session that outlived the app (an update, or the process being killed) gets its cover back.
    LaunchedEffect(Unit) { FocusClock.run?.let { DeepFocus.sync(context, it); FocusClock.refreshNotification(context) } }
    val focusNotificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    fun newTracker(): BoardCard {
        val column = snapshot.columnsFor(Workspace.TRACK).let { cols -> cols.firstOrNull { it.name.equals("Timers", true) } ?: cols.lastOrNull() }
        val used = trackers.map(::habitColorIndex).toSet()
        val color = HabitPalette.indices.firstOrNull { it !in used } ?: (trackers.size % HabitPalette.size)
        return BoardCard(workspace = Workspace.TRACK, columnId = column?.id ?: "timers", kind = CardKind.TIMER, title = "", habitColor = color, durationMinutes = FOCUS_DEFAULT_MINUTES)
    }
    fun saveTracker(card: BoardCard) {
        var next = snapshot
        if (next.columns.none { it.id == card.columnId }) next = next.copy(columns = next.columns + BoardColumn(id = card.columnId, workspace = Workspace.TRACK, name = "Timers"))
        val existing = next.cards.any { it.id == card.id }
        update(next.copy(cards = if (existing) next.cards.map { if (it.id == card.id) card else it } else next.cards + card))
        FocusUi.editor = null
        showMessage(if (existing) "Timer updated" else "Timer added")
    }
    fun deleteTracker(card: BoardCard) {
        FocusUi.editor = null
        if (FocusClock.run?.trackerId == card.id) FocusClock.finish(context, completed = false)
        deleteWithUndo("Timer deleted", setOf(card.id))
    }
    fun startFocus(card: BoardCard, minutes: Int) {
        if (FocusClock.run != null) { showMessage("A session is already running"); return }
        if (Reminders.needsNotificationPermission(context)) focusNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        val deep = card.id in FocusClock.deepTrackers
        if (deep && !DeepFocus.canOverlay(context)) { FocusUi.deepSetup = card.id; return }
        FocusClock.start(context, card, minutes, deep)
        FocusUi.fullScreen = true
    }
    /** Ends the running session: records it on its tracker and opens its notes. */
    fun finishFocus(completed: Boolean) {
        val (run, session) = FocusClock.finish(context, completed) ?: return
        if (session == null) { showMessage(if (completed) "Break’s over" else "Break skipped"); return }
        if (snapshot.cards.none { it.id == run.trackerId }) return
        if (session.focusedMillis < 60_000 && session.note.isBlank()) { showMessage("Under a minute, so not saved"); return }
        update(snapshot.withSession(run.trackerId, session))
        FocusUi.justEnded = true
        FocusUi.sheet = run.trackerId to session.id
        FocusUi.fullScreen = false
    }
    val focusHaptics = androidx.compose.ui.platform.LocalHapticFeedback.current
    val focusRun = FocusClock.run
    LaunchedEffect(focusRun) {
        while (focusRun != null) {
            androidx.compose.runtime.withFrameMillis { }
            val now = System.currentTimeMillis()
            FocusClock.now = now
            if (!focusRun.paused && focusRun.remaining(now) <= 0L) {
                focusHaptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                finishFocus(completed = true)
                break
            }
            delay(100)
        }
    }

    // Two-way calendar sync (CalendarSync.kt): apply the card changes a sync works out to what's on screen.
    val calendarPatches by CalendarSync.patchTick.collectAsState()
    LaunchedEffect(calendarPatches) { CalendarSync.drainPatches(context) { patch -> patch.apply(snapshot).let { if (it !== snapshot) update(it) } } }
    fun importSyncedCalendarEvents() {
        if (CalendarSync.hasChoice(context, snapshot)) CalendarSync.syncNow(context, manual = true, onMessage = ::showMessage)
        else showCalendarSettings = true
    }

    val calendarPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) importSyncedCalendarEvents() else showMessage("Allow calendar access to import events")
    }
    fun requestCalendarImport() {
        if (context.checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED) importSyncedCalendarEvents()
        else calendarPermissionLauncher.launch(Manifest.permission.READ_CALENDAR)
    }

    fun addSpokenTask(spoken: String) {
        val list = snapshot.columnsFor(snapshot.todoPages.firstOrNull { it.id == selectedTodoPageId } ?: snapshot.todoPages.first()).first()
        val card = BoardCard(workspace = Workspace.TODOS, columnId = list.id, kind = CardKind.TASK, title = spoken)
        update(snapshot.copy(cards = listOf(card) + snapshot.cards))
        workspaceId = Workspace.TODOS.id
        showMessage("Added to your to-do inbox")
    }

    // Listening happens in the app (the voice button shows the sound level); the system popup is
    // only a fallback for phones without a speech service the app can use directly.
    val voice = rememberVoiceCapture(onText = { addSpokenTask(it) }, onProblem = { showMessage(it) })
    val voiceLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val spoken = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.trim()
        if (!spoken.isNullOrBlank()) addSpokenTask(spoken)
    }
    val micPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) voice.start() else showMessage("Allow the microphone to add tasks by voice")
    }

    val tabHaptics = androidx.compose.ui.platform.LocalHapticFeedback.current
    /** Shows a tab: Today (null) or one of the workspaces, in bottom-bar order. */
    fun selectTab(tab: Workspace?) {
        // Coming to the Habits tab from another tab always lands on Focus.
        if (tab == Workspace.TRACK && (todayActive || workspaceId != tab.id)) trackPage = 0
        if (tab == null) todayActive = true else { todayActive = false; workspaceId = tab.id }
    }

    fun startVoiceCapture() {
        if (voice.listening) { voice.stop(); return } // a second tap finishes
        if (voice.available) {
            if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) voice.start()
            else micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
            putExtra(RecognizerIntent.EXTRA_PROMPT, "What do you need to remember?")
        }
        try {
            voiceLauncher.launch(intent)
        } catch (_: ActivityNotFoundException) {
            showMessage("Voice input isn’t available here. Type your task instead.")
        }
    }

    // Keep elapsed-time labels current while a tracker is running in the foreground.
    val activeTimer = snapshot.cards.firstOrNull { it.kind == CardKind.TIMER && it.activeSinceMillis != null }
    var timerTick by remember { mutableStateOf(0L) }
    LaunchedEffect(activeTimer?.id) {
        while (activeTimer != null) {
            timerTick = System.currentTimeMillis()
            delay(1_000)
        }
    }

    // Fetch a title and preview image for saved links, one at a time.
    val pendingPreview = snapshot.cards.firstOrNull { it.link.isNotBlank() && !it.previewDone }
    LaunchedEffect(pendingPreview?.id) {
        val card = pendingPreview ?: return@LaunchedEffect
        val preview = withContext(Dispatchers.IO) { Saves.fetchPreview(context, card.link) }
        update(snapshot.copy(cards = snapshot.cards.map {
            if (it.id != card.id) it else it.copy(
                previewDone = true,
                title = if (it.title == Saves.domainOf(it.link)) preview?.title ?: it.title else it.title,
                imagePath = preview?.imagePath ?: it.imagePath
            )
        }))
    }
    fun addSavedImages(paths: List<String>) {
        val column = snapshot.columns.firstOrNull { it.pageId == DEFAULT_SAVED_PAGE_ID }
        if (column != null && paths.isNotEmpty()) {
            val added = paths.map { Saves.imageCard(column.id, it).copy(isPrivate = privateOpen) }
            update(snapshot.copy(cards = added + snapshot.cards))
            showMessage("Saved ${added.size} image${if (added.size == 1) "" else "s"}")
        }
    }
    // One picked image gets the crop page first; several are saved as they are.
    var cropSaveUri by rememberSaveable { mutableStateOf<Uri?>(null) }
    val pickImages = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(10)) { uris ->
        if (uris.size == 1) cropSaveUri = uris[0]
        else addSavedImages(uris.mapNotNull { Saves.copyImage(context, it) })
    }

    LaunchedEffect(Unit) { lastBackup = Backup.lastBackupMillis(context) }
    val exportBackup = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        backupBusy = true
        val current = snapshot
        scope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { Backup.export(context, current, uri) } }
            backupBusy = false
            result.onSuccess { summary ->
                lastBackup = Backup.lastBackupMillis(context)
                showMessage("Backed up ${summary.cards} item${if (summary.cards == 1) "" else "s"} and ${summary.images} image${if (summary.images == 1) "" else "s"}")
            }.onFailure { showMessage(it.message ?: "Backup failed") }
        }
    }
    // Writes the backup to the app's cache and opens the Android share sheet, so it can go to Drive, email, a chat or anywhere else.
    fun shareBackup() {
        backupBusy = true
        val current = snapshot
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val dir = java.io.File(context.cacheDir, "backups").apply { deleteRecursively(); mkdirs() }
                    val file = java.io.File(dir, Backup.fileName())
                    Backup.export(context, current, Uri.fromFile(file))
                    file
                }
            }
            backupBusy = false
            result.onSuccess { file ->
                lastBackup = Backup.lastBackupMillis(context)
                val uri = androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.files", file)
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "application/zip"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    putExtra(Intent.EXTRA_TITLE, file.name)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                runCatching { context.startActivity(Intent.createChooser(send, "Share backup").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                    .onFailure { showMessage("No app can take the backup") }
            }.onFailure { showMessage(it.message ?: "Backup failed") }
        }
    }
    val pickBackup = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) pendingRestore = uri }

    fun restoreBackup(uri: Uri) {
        backupBusy = true
        scope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { Backup.read(context, uri) } }
            backupBusy = false
            result.onSuccess { restored ->
                // Apply on the main thread through update() so nothing in memory overwrites it later.
                update(restored)
                selectedTodoPageId = restored.todoPages.first().id
                selectedNotesPageId = restored.notesPages.first().id
                editingCardId = null
                withContext(Dispatchers.IO) { Backup.deleteUnusedImages(context, restored) }
                showMessage("Restored ${restored.cards.size} item${if (restored.cards.size == 1) "" else "s"}")
            }.onFailure { showMessage(it.message ?: "Couldn’t restore that file") }
        }
    }

    BackHandler(enabled = showSettings && !showCalendarSettings && !showStorage) { showSettings = false }
    BackHandler(enabled = showStorage) { showStorage = false }
    // Catch up on an automatic backup the background job hasn't got round to yet.
    LaunchedEffect(Unit) {
        AutoBackup.schedule(context)
        if (AutoBackup.isDue(context)) {
            val latest = withContext(BoardStore.io) { store.load() }
            withContext(Dispatchers.IO) { runCatching { AutoBackup.runNow(context, latest) } }
            lastBackup = Backup.lastBackupMillis(context)
        }
    }
    BackHandler(enabled = showCalendarSettings) { showCalendarSettings = false }
    BackHandler(enabled = showSearch) { showSearch = false }

    // A tapped search result: go to its tab and page, then do what tapping it there does.
    fun openSearchHit(hit: SearchHit) {
        val card = hit.card
        val column = snapshot.columns.firstOrNull { it.id == card.columnId }
        showSearch = false
        todayActive = false
        workspaceId = card.workspace.id
        when (card.workspace) {
            Workspace.TODOS -> column?.let { selectedTodoPageId = it.pageId }
            Workspace.NOTES -> column?.let { selectedNotesPageId = it.pageId }
            Workspace.CALENDAR -> card.dueDay?.let { calendarJump = it to ((calendarJump?.second ?: 0) + 1) }
            Workspace.TRACK -> Unit
        }
        when {
            hit.section == SearchSection.SAVED && card.link.isNotBlank() ->
                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(card.link)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            hit.section == SearchSection.SAVED && card.imagePath.isNotBlank() -> viewingSave = card
            else -> { editingCardId = card.id; draftColumnId = card.columnId; showComposer = true }
        }
    }

    // Taps on the home-screen widgets: open a folder, day or note, or start something new there.
    LaunchedEffect(widgetRequest) {
        val request = widgetRequest ?: return@LaunchedEffect
        val latest = withContext(BoardStore.io) { store.load() }
        snapshot = latest
        showSettings = false
        editingCardId = null
        todayActive = false
        when (request.action) {
            WidgetActions.TASKS_OPEN, WidgetActions.TASKS_ADD -> {
                val page = latest.todoPages.firstOrNull { it.id == request.target } ?: latest.todoPages.first()
                workspaceId = Workspace.TODOS.id
                selectedTodoPageId = page.id
                if (request.action == WidgetActions.TASKS_ADD) { draftColumnId = latest.columnsFor(page).first().id; showComposer = true }
            }
            WidgetActions.CALENDAR_OPEN, WidgetActions.CALENDAR_ADD -> {
                val day = request.day ?: LocalDate.now().toEpochDay()
                workspaceId = Workspace.CALENDAR.id
                calendarJump = day to ((calendarJump?.second ?: 0) + 1)
                if (request.action == WidgetActions.CALENDAR_ADD) {
                    val calendarColumns = latest.columnsFor(Workspace.CALENDAR)
                    draftColumnId = calendarColumnFor(calendarColumns, day, calendarColumns.firstOrNull()?.id.orEmpty())
                    composerDay = day
                    showComposer = true
                }
            }
            WidgetActions.CARD_EDIT -> {
                // The compact task widget's quick sheet: open the task's own edit sheet.
                val card = latest.cards.firstOrNull { it.id == request.target }
                if (card != null) {
                    workspaceId = card.workspace.id
                    latest.columns.firstOrNull { it.id == card.columnId }?.let { if (card.workspace == Workspace.TODOS) selectedTodoPageId = it.pageId }
                    editingCardId = card.id; draftColumnId = card.columnId; showComposer = true
                }
            }
            WidgetActions.HABITS_OPEN -> {
                workspaceId = Workspace.TRACK.id
                trackPage = 1
                latest.cards.firstOrNull { it.id == request.target && it.kind == CardKind.HABIT }?.let { editingCardId = it.id; draftColumnId = it.columnId; showComposer = true }
            }
            WidgetActions.FOCUS_OPEN -> {
                workspaceId = Workspace.TRACK.id
                trackPage = 0
            }
            WidgetActions.NOTE_OPEN -> {
                val note = latest.cards.firstOrNull { it.id == request.target }
                workspaceId = Workspace.NOTES.id
                if (note != null) {
                    latest.columns.firstOrNull { it.id == note.columnId }?.let { selectedNotesPageId = it.pageId }
                    editingCardId = note.id; draftColumnId = note.columnId; showComposer = true
                }
            }
        }
        onWidgetRequestHandled()
    }

    val pickerApps = remember(showAppPicker) { if (showAppPicker) installedApps(context) else emptyList() }
    val appChoices = pickerApps

    // Opening the app: the page fades up into place and the bar rises from below.
    val appear = remember { Animatable(0f) }
    LaunchedEffect(Unit) { appear.animateTo(1f, tween(560, easing = FastOutSlowInEasing)) }
    CompositionLocalProvider(
        LocalOpenSettings provides { showSettings = true }, LocalOpenSearch provides { showSearch = true },
        LocalHabitDayToggle provides ::toggleHabitDay,
        LocalEditHabit provides { card -> editingCardId = card.id; draftColumnId = card.columnId; showComposer = true }
    ) {
    Scaffold(
        modifier = Modifier.graphicsLayer {
            alpha = appear.value
            val scale = .95f + .05f * appear.value
            scaleX = scale
            scaleY = scale
        },
        containerColor = Color.Transparent,
        contentWindowInsets = WindowInsets.statusBars,
        snackbarHost = { SnackbarHost(snackbar) },
        // Lines up over the + at the end of the bottom bar (both sit about 44dp in from the edge).
        floatingActionButton = { Box(Modifier.graphicsLayer { alpha = appear.value }.padding(end = 2.dp)) {
            androidx.compose.foundation.layout.Column(verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp)) {
                FloatingSearchButton()
                FloatingVoiceButton(voice.listening, voice.level, ::startVoiceCapture)
            }
        } },
        bottomBar = {
          Box(Modifier.graphicsLayer { translationY = (1f - appear.value) * 90.dp.toPx() }) {
            WorkspaceNavigation(selected = if (todayActive) null else workspace, darkTheme = true, calendarTheme = false, privateOpen = privateOpen, onLongPressNotes = ::togglePrivate, onSelect = ::selectTab, onCreate = {
                if (todayActive) {
                    // A new task due today, in the default to-do page's first list.
                    val page = snapshot.todoPages.firstOrNull { it.id == DEFAULT_TODO_PAGE_ID } ?: snapshot.todoPages.first()
                    draftColumnId = snapshot.columnsFor(page).first().id
                    composerDay = LocalDate.now().toEpochDay()
                    showComposer = true
                } else if (workspace == Workspace.TODOS) {
                    val page = snapshot.todoPages.firstOrNull { it.id == selectedTodoPageId } ?: snapshot.todoPages.first()
                    draftColumnId = snapshot.columnsFor(page).first().id
                    showComposer = true
                } else if (workspace == Workspace.NOTES) {
                    val page = snapshot.notesPages.firstOrNull { it.id == selectedNotesPageId } ?: snapshot.notesPages.first()
                    draftColumnId = snapshot.columnsFor(page, Workspace.NOTES).first().id
                    showComposer = true
                } else if (workspace == Workspace.TRACK && trackPage == 0) FocusUi.editor = ""
                else if (workspace == Workspace.TRACK) { editingCardId = null; showComposer = true }
                else { draftColumnId = columns.firstOrNull()?.id.orEmpty(); showComposer = true }
            })
          }
        }
    ) { insets ->
        Column(Modifier.fillMaxSize().padding(insets)) {
          // Switching tabs swings the screens round a carousel, toward the tab you picked.
          val tab = if (todayActive) null else workspace
          val lastTab = remember { intArrayOf(tab?.ordinal ?: -1) }
          val swingForward = remember(tab) { ((tab?.ordinal ?: -1) > lastTab[0]).also { lastTab[0] = tab?.ordinal ?: -1 } }
          // Read the tab when the swipe lands, not when a screen first set up its gesture handler.
          val currentTab = androidx.compose.runtime.rememberUpdatedState(tab)
          val swipeTab: (Boolean) -> Unit = remember {
              { forward ->
                  val order = listOf<Workspace?>(null, Workspace.TODOS, Workspace.CALENDAR, Workspace.NOTES, Workspace.TRACK)
                  val next = order.indexOf(currentTab.value) + if (forward) 1 else -1
                  if (next in order.indices) { tabHaptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.SegmentTick); selectTab(order[next]) }
              }
          }
          androidx.compose.runtime.CompositionLocalProvider(LocalTabSwipe provides swipeTab) {
          AnimatedContent(
            targetState = tab,
            modifier = Modifier.swipeBetweenTabs(swipeTab),
            transitionSpec = { EnterTransition.None togetherWith ExitTransition.None },
            label = "workspace"
          ) { shown ->
           Box(carousel(swingForward)) {
            if (shown == null) TodayScreen(
                snapshot = snapshot,
                timerTick = timerTick,
                clockKey = resumeTick,
                onEdit = { card -> editingCardId = card.id; draftColumnId = card.columnId; showComposer = true },
                onComplete = { card ->
                    val next = snapshot.completeCard(card.id, LocalDate.now().toEpochDay())
                    update(next)
                    val spawned = next.cards.firstOrNull { it.id !in snapshot.cards.map { c -> c.id } }
                    if (spawned != null) showMessage("Next: ${formatDue(spawned.dueDay)}")
                },
                onReopen = { card -> update(snapshot.reopenCard(card.id)) },
                onReschedule = { card ->
                    val today = LocalDate.now().toEpochDay()
                    update(snapshot.copy(cards = snapshot.cards.map { if (it.id == card.id) it.copy(dueDay = today, repeatAnchor = if (it.repeat.isBlank()) it.repeatAnchor else today) else it }))
                    showMessage("Moved to today")
                },
                onHabitCheckIn = { card -> val today = LocalDate.now().toEpochDay(); update(snapshot.toggleCheckIn(card.id, today, today)) },
                onToggleTimer = { card ->
                    val ended = System.currentTimeMillis()
                    val since = card.activeSinceMillis
                    if (since != null) {
                        update(snapshot.copy(cards = snapshot.cards.map { if (it.id == card.id) it.copy(activeSinceMillis = null, trackedMillis = it.trackedMillis + (ended - since)) else it }))
                        showMessage("Session saved · ${formatDuration(ended - since)}")
                    }
                },
                onDelete = { card -> deleteWithUndo(deletedMessage(card, snapshot), setOf(card.id)) }
            ) else if (shown == Workspace.TRACK) HabitsScreen(
                habits = habits,
                onToggleDay = ::toggleHabitDay,
                onEdit = { card -> editingCardId = card.id; draftColumnId = card.columnId; showComposer = true },
                onDelete = { card -> deleteWithUndo(deletedMessage(card, snapshot), setOf(card.id)) },
                onAdd = { editingCardId = null; showComposer = true },
                trackers = trackers,
                page = trackPage,
                onPageChange = { trackPage = it },
                onStartFocus = ::startFocus,
                onSetFocusMinutes = { card, minutes -> update(snapshot.copy(cards = snapshot.cards.map { if (it.id == card.id) it.copy(durationMinutes = minutes) else it })) },
                onStopFocus = { finishFocus(completed = false) },
                onDeleteTracker = ::deleteTracker
            ) else { val workspace = shown
            BoardScreen(
                workspace = workspace,
                snapshot = boardSnapshot,
                timerTick = timerTick,
                onSelectView = { view -> update(snapshot.copy(views = snapshot.views + (workspace to view))) },
                selectedColumnId = selectedColumnByWorkspace[workspace.id].orEmpty(),
                onSelectColumn = { columnId -> selectedColumnByWorkspace = selectedColumnByWorkspace + (workspace.id to columnId) },
                selectedTodoPageId = selectedTodoPageId,
                onSelectTodoPage = { selectedTodoPageId = it },
                selectedNotesPageId = selectedNotesPageId,
                onSelectNotesPage = { selectedNotesPageId = it },
                onAddColumn = { showColumnDialog = true },
                onNewPage = { if (workspace == Workspace.NOTES) showFolderDialog = true else showPageDialog = true },
                onMoveCard = { card, column ->
                    update(snapshot.copy(cards = snapshot.cards.map { if (it.id == card.id) it.copy(columnId = column.id) else it }))
                    if (card.workspace == Workspace.TODOS) showMessage("Moved to ${column.name}")
                },
                onToggleImportant = { card -> update(snapshot.copy(cards = snapshot.cards.map { if (it.id == card.id) it.copy(important = !it.important) else it })) },
                onComplete = { card ->
                    // To-dos stay in their list and just tick off (a repeating one also spawns its next date); other boards still move to Done.
                    val next = snapshot.completeCard(card.id, LocalDate.now().toEpochDay())
                    update(next)
                    val spawned = next.cards.firstOrNull { it.id !in snapshot.cards.map { c -> c.id } }
                    if (spawned != null) showMessage("Next: ${formatDue(spawned.dueDay)}")
                    else if (card.workspace != Workspace.TODOS) showMessage("Moved to Done")
                },
                onReopen = { card -> update(snapshot.reopenCard(card.id)) },
                onHabitCheckIn = { card -> val today = LocalDate.now().toEpochDay(); update(snapshot.toggleCheckIn(card.id, today, today)) },
                onToggleTimer = { card ->
                    if (card.activeSinceMillis == null) {
                        val started = System.currentTimeMillis()
                        update(snapshot.copy(cards = snapshot.cards.map { if (it.id == card.id) it.copy(activeSinceMillis = started) else if (it.kind == CardKind.TIMER && it.activeSinceMillis != null) it.copy(activeSinceMillis = null, trackedMillis = it.trackedMillis + (started - it.activeSinceMillis)) else it }))
                        launchTarget(context, card)?.let { showMessage(it) }
                    } else {
                        val ended = System.currentTimeMillis()
                        update(snapshot.copy(cards = snapshot.cards.map { if (it.id == card.id) it.copy(activeSinceMillis = null, trackedMillis = it.trackedMillis + (ended - card.activeSinceMillis)) else it }))
                        showMessage("Session saved · ${formatDuration(ended - card.activeSinceMillis)}")
                    }
                },
                onLaunchApp = { card -> launchTarget(context, card)?.let(::showMessage) },
                onDelete = { card -> deleteWithUndo(deletedMessage(card, snapshot), setOf(card.id)) },
                onPickImages = { pickImages.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                onVoice = ::startVoiceCapture,
                onAddCard = { column -> draftColumnId = column.id; showComposer = true },
                onDeletePage = { if (snapshot.todoPages.any { p -> p.id == it.id }) deleteTodoFolder(it) else pageToDelete = it },
                onRenamePage = { pageToRename = it },
                onMovePage = { id, to -> update(snapshot.copy(todoPages = movePage(snapshot.todoPages, snapshot.todoPages.indexOfFirst { it.id == id }, to))) },
                onEditCard = { card -> editingCardId = card.id; draftColumnId = card.columnId; showComposer = true },
                onImportCalendar = ::requestCalendarImport,
                calendarJump = calendarJump,
                privateOpen = privateOpen,
                onTogglePrivate = ::setPrivate,
                onLockPrivate = { PrivateVault.lock() }
            )
            }
           }
          }
          }
        }
    }

    }

    // Settings slides up over the current tab and back down when closed.
    AnimatedVisibility(
        visible = showSettings,
        enter = fadeIn(tween(220)) + slideInVertically(tween(340, easing = FastOutSlowInEasing)) { it / 6 },
        exit = fadeOut(tween(180)) + slideOutVertically(tween(260, easing = FastOutSlowInEasing)) { it / 6 }
    ) {
        SettingsScreen(
            snapshot = snapshot,
            lastBackup = lastBackup,
            busy = backupBusy,
            snackbar = snackbar,
            onBack = { showSettings = false },
            onBackup = { exportBackup.launch(Backup.fileName()) },
            onShareBackup = ::shareBackup,
            onRestore = { pickBackup.launch(arrayOf("application/zip", "application/octet-stream", "application/x-zip-compressed")) },
            onImportCalendar = { showCalendarSettings = true },
            onOpenStorage = { showStorage = true }
        )
    }

    // Settings › Calendar: which calendars to show and where Daymark's events sync to.
    AnimatedVisibility(
        visible = showCalendarSettings,
        enter = fadeIn(tween(220)) + slideInHorizontally(tween(320, easing = FastOutSlowInEasing)) { it / 6 },
        exit = fadeOut(tween(180)) + slideOutHorizontally(tween(260, easing = FastOutSlowInEasing)) { it / 6 }
    ) {
        CalendarSettingsScreen(snapshot = snapshot, snackbar = snackbar, onBack = { showCalendarSettings = false })
    }

    // Settings › Storage: the biggest pictures and files, to pick and delete.
    AnimatedVisibility(
        visible = showStorage,
        enter = fadeIn(tween(220)) + slideInHorizontally(tween(320, easing = FastOutSlowInEasing)) { it / 6 },
        exit = fadeOut(tween(180)) + slideOutHorizontally(tween(260, easing = FastOutSlowInEasing)) { it / 6 }
    ) {
        StorageScreen(
            snapshot = snapshot,
            snackbar = snackbar,
            onBack = { showStorage = false },
            onRemoveFromNotes = { paths ->
                update(snapshot.copy(cards = snapshot.cards.map { card ->
                    val details = Markdown.withoutFiles(card.details, paths)
                    if (card.imagePath !in paths && details == card.details) card
                    else card.copy(imagePath = if (card.imagePath in paths) "" else card.imagePath, details = details)
                }))
            }
        )
    }

    // Search covers the tab like Settings does, and closes when a result is picked.
    AnimatedVisibility(
        visible = showSearch,
        enter = fadeIn(tween(200)),
        exit = fadeOut(tween(160))
    ) {
        SearchScreen(snapshot = snapshot, onClose = { showSearch = false }, onOpen = ::openSearchHit)
    }

    FocusFullScreen(onStop = { finishFocus(completed = false) })
    FocusUi.deepSetup?.let { id -> DeepFocusSetup(id) { FocusUi.deepSetup = null } }
    FocusUi.editor?.let { id ->
        val editing = trackers.firstOrNull { it.id == id }
        val starter = remember(id) { newTracker() }
        TrackerEditor(editing, starter, onDismiss = { FocusUi.editor = null }, onSave = ::saveTracker, onDelete = ::deleteTracker)
    }
    FocusUi.sheet?.let { (trackerId, sessionId) ->
        val tracker = trackers.firstOrNull { it.id == trackerId }
        val session = tracker?.sessions?.firstOrNull { it.id == sessionId }
        if (tracker == null || session == null) { LaunchedEffect(Unit) { FocusUi.sheet = null }; return@let }
        SessionSheet(
            tracker, session, FocusUi.justEnded,
            onSaveNote = { note -> update(snapshot.editSession(trackerId, sessionId, note)) },
            onDelete = { FocusUi.sheet = null; update(snapshot.withoutSession(trackerId, sessionId)); showMessage("Session deleted") },
            onBreak = { FocusUi.sheet = null; FocusClock.startBreak(context, null) },
            onClose = { FocusUi.sheet = null }
        )
    }

    viewingSave?.let { SavedImageViewer(it) { viewingSave = null } }
    cropSaveUri?.let { uri ->
        ImageCropDialog(uri, CropAspect.Free, onCancel = { cropSaveUri = null }, onResult = { path -> cropSaveUri = null; addSavedImages(listOf(path)) })
    }

    pendingRestore?.let { uri ->
        AlertDialog(
            onDismissRequest = { pendingRestore = null },
            containerColor = GlassDialog,
            title = { DialogBlur(); Text("Restore this backup?", color = TodoText) },
            text = { Text("Everything currently in Daymark will be replaced with the backup’s tasks, notes, events and images.", color = TodoMuted) },
            confirmButton = {
                Button(onClick = { pendingRestore = null; restoreBackup(uri) }, colors = ButtonDefaults.buttonColors(containerColor = Danger)) { Text("Replace") }
            },
            dismissButton = { TextButton(onClick = { pendingRestore = null }) { Text("Cancel", color = TodoMuted) } }
        )
    }

    if (showPageDialog) {
        NewPageDialog(
            onDismiss = { showPageDialog = false },
            onCreate = { name ->
                val page = BoardPage(name = name, color = newFolderColor(snapshot.todoPages))
                val list = BoardColumn(workspace = Workspace.TODOS, name = name, pageId = page.id)
                update(snapshot.copy(todoPages = snapshot.todoPages + page, columns = snapshot.columns + list))
                selectedTodoPageId = page.id
                showPageDialog = false
            }
        )
    }

    if (showFolderDialog) {
        NewFolderDialog(
            onDismiss = { showFolderDialog = false },
            onCreate = { name ->
                val page = BoardPage(name = name, color = newFolderColor(snapshot.notesPages))
                val folder = BoardColumn(workspace = Workspace.NOTES, name = name, pageId = page.id)
                update(snapshot.copy(notesPages = snapshot.notesPages + page, columns = snapshot.columns + folder))
                selectedNotesPageId = page.id
                showFolderDialog = false
            }
        )
    }

    pageToRename?.let { page ->
        RenamePageDialog(
            current = page.name,
            currentColor = folderColorIndex(page),
            onDismiss = { pageToRename = null },
            onRename = { name, color ->
                update(snapshot.copy(
                    todoPages = snapshot.todoPages.map { if (it.id == page.id) it.copy(name = name, color = color) else it },
                    notesPages = snapshot.notesPages.map { if (it.id == page.id) it.copy(name = name, color = color) else it },
                    columns = snapshot.columns.map { if (it.pageId == page.id) it.copy(name = name) else it }
                ))
                pageToRename = null
            }
        )
    }

    pageToDelete?.let { page ->
        AlertDialog(
            onDismissRequest = { pageToDelete = null },
            containerColor = GlassDialog,
            title = { DialogBlur(); Text("Delete “${page.name}”?", color = TodoText) },
            text = { Text("This page and everything on it will be removed.", color = TodoMuted) },
            confirmButton = {
                Button(onClick = {
                    val isTodo = snapshot.todoPages.any { it.id == page.id }
                    val doomed = snapshot.columns.filter { it.pageId == page.id }.map { it.id }.toSet()
                    val doomedCards = snapshot.cards.filter { it.columnId in doomed }.map { it.id }.toSet()
                    deleteWithUndo(if (isTodo) "Page deleted" else "Folder deleted", doomedCards, doomed, setOf(page.id)) {
                        if (isTodo) selectedTodoPageId = page.id else selectedNotesPageId = page.id
                    }
                    if (isTodo) selectedTodoPageId = snapshot.todoPages.first().id else selectedNotesPageId = snapshot.notesPages.first().id
                    pageToDelete = null
                }, colors = ButtonDefaults.buttonColors(containerColor = Danger)) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { pageToDelete = null }) { Text("Cancel", color = TodoMuted) } }
        )
    }

    val editingHabit = snapshot.cards.firstOrNull { it.id == editingCardId }?.takeIf { it.kind == CardKind.HABIT }
    if (showComposer && (editingHabit != null || (editingCardId == null && !todayActive && workspace == Workspace.TRACK))) {
        HabitEditor(
            editing = editingHabit,
            onDismiss = { showComposer = false; editingCardId = null },
            onSave = ::saveHabit,
            onDelete = { card -> showComposer = false; editingCardId = null; deleteWithUndo(deletedMessage(card, snapshot), setOf(card.id)) },
            newCard = ::newHabit
        )
    } else if (showComposer) {
        val editing = snapshot.cards.firstOrNull { it.id == editingCardId }
        // From Today a new item is a to-do; an item being edited always opens as what it is.
        val workspace = editing?.workspace ?: if (todayActive) Workspace.TODOS else workspace
        CardComposer(
            workspace = workspace,
            columns = snapshot.columnsFor(workspace),
            initialColumnId = draftColumnId,
            selectedApp = pendingAppChoice,
            editing = editing,
            initialDay = composerDay,
            onDismiss = { showComposer = false; pendingAppChoice = null; editingCardId = null; composerDay = null },
            onPickApp = { showAppPicker = true },
            onSave = { draft ->
              composerDay = null
              if (editing != null) {
                val kept = Markdown.filePaths(draft.details).toSet()
                Markdown.filePaths(editing.details).filter { it !in kept }.forEach { runCatching { java.io.File(it).delete() } }
                // A replaced or removed cover image is deleted with it.
                if (draft.imagePath != null && editing.imagePath.isNotBlank() && draft.imagePath != editing.imagePath) runCatching { java.io.File(editing.imagePath).delete() }
                update(snapshot.copy(cards = snapshot.cards.map {
                    if (it.id == editing.id) it.copy(title = draft.title.trim(), details = draft.details.trim(), columnId = draft.columnId, important = draft.important, dueDay = draft.dueDay, dueMinute = draft.dueMinute,
                        repeat = draft.repeat, repeatAnchor = if (draft.repeat.isBlank()) null else if (draft.dueDay != it.dueDay || draft.repeat != it.repeat) draft.dueDay else it.repeatAnchor,
                        remind = draft.remind, remindLeadMinutes = draft.remindLeadMinutes, imagePath = draft.imagePath ?: it.imagePath, comment = draft.comment ?: it.comment) else it
                }))
                showComposer = false
                editingCardId = null
                showMessage(if (editing.workspace == Workspace.NOTES) "Note updated" else "Task updated")
              } else {
                // A note made while Private is open stays private.
                val saved = cardFromDraft(workspace, draft, snapshot).let { if (workspace == Workspace.NOTES && privateOpen) it.copy(isPrivate = true) else it }
                update(snapshot.copy(cards = listOf(saved) + snapshot.cards))
                showComposer = false
                pendingAppChoice = null
                showMessage(when (workspace) {
                    Workspace.TODOS -> "Task added"
                    Workspace.CALENDAR -> "Added to your calendar board"
                    Workspace.NOTES -> "Note saved"
                    Workspace.TRACK -> if (draft.kind == CardKind.HABIT) "Habit added" else "Tracker added"
                })
              }
            },
            onVoice = ::startVoiceCapture
        )
    }

    if (showColumnDialog) {
        NewColumnDialog(
            workspace = workspace,
            onDismiss = { showColumnDialog = false },
            onCreate = { name ->
                val column = BoardColumn(workspace = workspace, name = name)
                update(snapshot.copy(columns = snapshot.columns + column))
                selectedColumnByWorkspace = selectedColumnByWorkspace + (workspace.id to column.id)
                showColumnDialog = false
            }
        )
    }

    if (showAppPicker) {
        AppPickerDialog(
            apps = appChoices,
            onDismiss = { showAppPicker = false },
            onSelect = { pendingAppChoice = it; showAppPicker = false }
        )
    }
}

private fun cardFromDraft(workspace: Workspace, draft: CardDraft, snapshot: BoardSnapshot): BoardCard {
    val card = BoardCard(
        workspace = workspace,
        columnId = draft.columnId,
        kind = draft.kind,
        title = draft.title.trim(),
        details = draft.details.trim(),
        dueDay = draft.dueDay,
        dueMinute = draft.dueMinute,
        repeat = draft.repeat,
        repeatAnchor = if (draft.repeat.isBlank()) null else draft.dueDay,
        important = draft.important,
        remind = draft.remind,
        remindLeadMinutes = draft.remindLeadMinutes,
        imagePath = draft.imagePath.orEmpty(),
        comment = draft.comment.orEmpty(),
        appPackage = draft.appChoice?.packageName.orEmpty(),
        appLabel = draft.appChoice?.label.orEmpty(),
        deepLink = draft.deepLink.trim()
    )
    val savedColumnId = snapshot.columns.firstOrNull { it.pageId == DEFAULT_SAVED_PAGE_ID }?.id
    val saved = if (draft.columnId == savedColumnId) {
        Saves.findUrl("${card.title} ${card.details}")?.let { url ->
            card.copy(link = url, title = card.title.replace(url, "").trim().ifBlank { Saves.domainOf(url) })
        } ?: card.copy(previewDone = true)
    } else card
    return saved
}
