package com.anmol.daymark

import androidx.compose.material3.Surface
import androidx.compose.ui.semantics.contentDescription

import androidx.compose.ui.semantics.semantics

import androidx.compose.ui.layout.layout
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.sp

/**
 * Full-height note editor. The note body is shown as blocks (paragraphs, headings, lists, checklists, quotes,
 * dividers, pictures, tables) that are each edited in place; the stored text stays the same markdown as before.
 * Closing (back or Done) saves; a note with no title and no body is simply dropped.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun NoteEditorSheet(
    columns: List<BoardColumn>,
    initialColumnId: String,
    editing: BoardCard?,
    onDismiss: () -> Unit,
    onSave: (title: String, details: String, columnId: String, cover: String, comment: String) -> Unit
) {
    val context = LocalContext.current
    var title by rememberSaveable(editing?.id) { mutableStateOf(editing?.title.orEmpty()) }
    // The editable body only; a YouTube transcript is kept apart in [transcript].
    var source by rememberSaveable(editing?.id) { mutableStateOf(Transcripts.withoutTranscript(editing?.details.orEmpty())) }
    var cover by rememberSaveable(editing?.id) { mutableStateOf(editing?.imagePath.orEmpty()) }
    var comment by rememberSaveable(editing?.id) { mutableStateOf(editing?.comment.orEmpty()) }
    // Saved items (and anything already carrying a remark) get a comment box under the saved content.
    val isSave = editing != null && (columns.firstOrNull { it.id == editing.columnId }?.pageId == DEFAULT_SAVED_PAGE_ID || editing.comment.isNotBlank())
    var columnId by rememberSaveable(editing?.id, initialColumnId) { mutableStateOf(initialColumnId.ifBlank { columns.firstOrNull()?.id.orEmpty() }) }
    // A YouTube transcript is kept apart from the editable body: it sits under its own collapsible heading at the end.
    // Not saveable: a long transcript is far too big for Android's saved state.
    var transcript by remember(editing?.id) { mutableStateOf(Transcripts.section(editing?.details.orEmpty())) }
    val blocks = remember(editing?.id) { mutableStateListOf<NoteBlock>().apply { addAll(NoteBlocks.withTrailingText(NoteBlocks.parse(Transcripts.withoutTranscript(source)))) } }
    fun fullText(list: List<NoteBlock>) = Transcripts.join(NoteBlocks.serialize(list), transcript)
    // Each run of text lines is one field (see NoteRuns); its value is kept by the run's position among the runs.
    val values = remember(editing?.id) { mutableStateMapOf<Int, TextFieldValue>() }
    var focusTarget by remember { mutableStateOf<BlockFocus?>(null) }
    var currentId by remember { mutableStateOf<Long?>(null) }
    var showDoodle by remember { mutableStateOf(false) }
    // A picture opened from the note: tapped to look at it, or held to draw on it in the doodle pad.
    var viewingImage by remember { mutableStateOf<String?>(null) }
    var redrawing by remember { mutableStateOf<NoteBlock?>(null) }
    var fetchingTranscript by remember { mutableStateOf(false) }
    var columnMenu by remember { mutableStateOf(false) }
    val titleFocus = remember { FocusRequester() }
    // Reader mode shows the note as a page with nothing to type into, so scrolling and tapping never pop the keyboard.
    // The choice is remembered: once on, existing notes open in it. New notes always open ready to write.
    var reading by rememberSaveable(editing?.id) { mutableStateOf(editing != null && NoteReader.enabled(context)) }
    var attaching by remember { mutableStateOf(false) }
    var sharing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    fun setReading(on: Boolean) {
        if (on) { focusManager.clearFocus(); keyboard?.hide(); currentId = null }
        reading = on
        NoteReader.set(context, on)
    }
    var look by remember { mutableStateOf(NoteReader.look(context)) }
    fun setLook(next: ReaderLook) { look = next; NoteReader.setLook(context, next) }

    fun valueOf(ordinal: Int, run: IntRange): TextFieldValue {
        val text = NoteRuns.text(blocks.subList(run.first, run.last + 1))
        return values[ordinal]?.takeIf { it.text == text } ?: TextFieldValue(text, TextRange(text.length))
    }
    // Undo and redo keep whole-note snapshots; a burst of typing (no pause over a second) is one step.
    val undoStack = remember(editing?.id) { mutableStateListOf<String>() }
    val redoStack = remember(editing?.id) { mutableStateListOf<String>() }
    var lastEditAt by remember { mutableStateOf(0L) }
    fun commit(next: List<NoteBlock>, record: Boolean = true) {
        val list = NoteBlocks.withTrailingText(next)
        val before = fullText(blocks)
        blocks.clear(); blocks.addAll(list)
        values.keys.retainAll(NoteRuns.runs(list).indices.toSet())
        source = NoteBlocks.serialize(list)
        if (record && fullText(list) != before) {
            val now = android.os.SystemClock.uptimeMillis()
            if (now - lastEditAt > 1000 || undoStack.isEmpty()) {
                undoStack.add(before)
                if (undoStack.size > 200) undoStack.removeAt(0)
            }
            lastEditAt = now
            redoStack.clear()
        }
    }
    /** Puts the cursor in block [target.id] at [target.cursor], focusing the run it is in. */
    fun focusOn(target: BlockFocus) {
        val (ordinal, start) = NoteRuns.locate(blocks, target.id) ?: return
        val run = NoteRuns.runs(blocks)[ordinal]
        val block = blocks.first { it.id == target.id }
        values[ordinal] = TextFieldValue(NoteRuns.text(blocks.subList(run.first, run.last + 1)), TextRange(start + target.cursor.coerceIn(0, block.text.length)))
        currentId = target.id
        focusTarget = target
    }
    fun apply(edit: BlockEdit) {
        commit(edit.blocks)
        edit.focus?.let(::focusOn)
    }
    fun indexOf(id: Long?) = blocks.indexOfFirst { it.id == id }.takeIf { it >= 0 }
    fun toggleCheck(block: NoteBlock) {
        val i = blocks.indexOfFirst { it.id == block.id }
        if (i >= 0) {
            blocks[i] = block.copy(prefix = if (block.checked) block.prefix.replace(Regex("""\[[xX]]"""), "[ ]") else block.prefix.replace("[ ]", "[x]"))
            commit(blocks.toList())
        }
    }
    fun restore(from: MutableList<String>, to: MutableList<String>) {
        val snapshot = from.removeLastOrNull() ?: return
        to.add(fullText(blocks))
        values.clear()
        transcript = Transcripts.section(snapshot)
        commit(NoteBlocks.parse(Transcripts.withoutTranscript(snapshot)), record = false)
        lastEditAt = 0L
        blocks.lastOrNull { it.kind.isText }?.let { focusOn(BlockFocus(it.id, it.text.length)) }
    }
    fun undo() = restore(undoStack, redoStack)
    fun redo() = restore(redoStack, undoStack)
    /** Puts in (or replaces) the transcript as one undoable step. */
    fun setTranscript(text: String) {
        undoStack.add(fullText(blocks)); redoStack.clear(); lastEditAt = 0L
        transcript = text
    }
    var refreshingTranscript by remember { mutableStateOf(false) }
    var transcriptOpen by remember { mutableStateOf(NoteReader.transcriptOpen(context)) }
    fun refreshTranscript(url: String) {
        refreshingTranscript = true
        scope.launch {
            val paragraphs = withContext(Dispatchers.IO) { Transcripts.fetch(url) }
            refreshingTranscript = false
            if (paragraphs == null) android.widget.Toast.makeText(context, "Couldn’t get the transcript again", android.widget.Toast.LENGTH_SHORT).show()
            else setTranscript(paragraphs.joinToString("\n\n"))
        }
    }
    fun heading(level: Int) {
        val index = indexOf(currentId) ?: return
        val runs = NoteRuns.runs(blocks)
        val ordinal = runs.indexOfFirst { index in it }
        if (ordinal < 0) return
        val selection = valueOf(ordinal, runs[ordinal]).selection
        commit(NoteRuns.heading(blocks, runs[ordinal], selection.min, selection.max, level))
    }
    fun insert(block: NoteBlock) = apply(NoteBlocks.insertAfter(blocks, indexOf(currentId), block))
    fun toggle(kind: BlockKind) {
        val index = indexOf(currentId) ?: return
        val runs = NoteRuns.runs(blocks)
        val ordinal = runs.indexOfFirst { index in it }
        if (ordinal < 0) return
        // Every line the selection touches changes together; the words stay as they are, so the selection does too.
        val selection = valueOf(ordinal, runs[ordinal]).selection
        commit(NoteRuns.toggle(blocks, runs[ordinal], selection.min, selection.max, kind))
    }
    fun finish() {
        val details = fullText(blocks).trim()
        val cleanTitle = title.trim()
        when {
            cleanTitle.isEmpty() && details.isEmpty() && cover.isEmpty() && comment.isBlank() -> onDismiss()
            editing != null && cleanTitle == editing.title.trim() && details == editing.details.trim() && columnId == editing.columnId && cover == editing.imagePath && comment.trim() == editing.comment.trim() -> onDismiss()
            else -> onSave(cleanTitle, details, columnId, cover, comment.trim())
        }
    }

    // A picked cover goes through the crop page first; it hands back a stored path just like a plain copy.
    var cropUri by rememberSaveable { mutableStateOf<android.net.Uri?>(null) }
    val pickCover = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) cropUri = uri
    }
    fun useCover(path: String) {
        // A cover picked and then swapped before saving is never stored, so drop it straight away.
        if (cover.isNotBlank() && cover != editing?.imagePath) runCatching { java.io.File(cover).delete() }
        cover = path
    }
    // Any kind of file: copied into the app on a background thread, then shown as a picture or a file card.
    fun attach(uri: android.net.Uri) {
        attaching = true
        scope.launch {
            val file = withContext(Dispatchers.IO) { Attachments.copy(context, uri) }
            attaching = false
            when {
                file == null -> android.widget.Toast.makeText(context, "Couldn’t add that file", android.widget.Toast.LENGTH_SHORT).show()
                Attachments.kindOf(file.name, file.mime) == FileKind.IMAGE -> insert(NoteBlock(NoteBlocks.newId(), BlockKind.IMAGE, file.path))
                else -> insert(NoteBlock(NoteBlocks.newId(), BlockKind.FILE, file.name, file.path))
            }
        }
    }
    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) attach(uri) }
    // Pictures and videos come through Android's own photo picker; a photo can also be taken there and then.
    val pickMedia = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> if (uri != null) attach(uri) }
    var cameraUri by rememberSaveable { mutableStateOf<android.net.Uri?>(null) }
    val takePhoto = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val uri = cameraUri
        cameraUri = null
        if (ok && uri != null) attach(uri)
    }
    fun openCamera() {
        val dir = java.io.File(context.cacheDir, "camera").apply { mkdirs() }
        val file = java.io.File(dir, "Photo ${java.text.SimpleDateFormat("yyyy-MM-dd HHmmss", java.util.Locale.US).format(java.util.Date())}.jpg")
        val uri = androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        cameraUri = uri
        runCatching { takePhoto.launch(uri) }.onFailure { cameraUri = null; android.widget.Toast.makeText(context, "No camera app found", android.widget.Toast.LENGTH_SHORT).show() }
    }
    fun chooseCover() = pickCover.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))

    // Remembered so typing elsewhere in the note doesn't recompose the transcript.
    val toggleTranscript = remember { { transcriptOpen = !transcriptOpen; NoteReader.setTranscriptOpen(context, transcriptOpen) } }
    val transcriptUrl = editing?.link?.takeIf { Transcripts.videoId(it) != null }
    val refreshAction: (() -> Unit)? = remember(transcriptUrl) { transcriptUrl?.let { url -> { refreshTranscript(url) } } }
    @Composable
    fun transcriptSection(look: ReaderLook) {
        if (transcript.isBlank()) return
        TranscriptSection(transcript, look, transcriptOpen, refreshingTranscript, onToggle = toggleTranscript, onRefresh = refreshAction)
    }

    LaunchedEffect(Unit) {
        // A new note starts in the title; an existing one opens without popping the keyboard over it.
        if (editing == null) runCatching { titleFocus.requestFocus() }
    }

    ModalBottomSheet(
        onDismissRequest = ::finish,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        sheetGesturesEnabled = false,
        containerColor = GlassSheet.copy(alpha = 1f),
        dragHandle = null,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
    ) {
        Column(Modifier.fillMaxSize().imePadding().navigationBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 14.dp, top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) {
                    Row(Modifier.clip(RoundedCornerShape(10.dp)).clickable { columnMenu = true }.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(columns.firstOrNull { it.id == columnId }?.name ?: "Folder", color = TodoMuted, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Glyph(ZenIcon.ChevronDown, TodoMuted, 14.dp, modifier = Modifier.padding(start = 6.dp))
                    }
                    DropdownMenu(expanded = columnMenu, onDismissRequest = { columnMenu = false }) {
                        columns.forEach { column -> DropdownMenuItem(text = { Text(column.name) }, onClick = { columnId = column.id; columnMenu = false }) }
                    }
                }
            }
            val noteScroll = rememberScrollState()
            Box(Modifier.weight(1f).fillMaxWidth()) {
            Column(Modifier.fillMaxSize().verticalScroll(noteScroll).padding(horizontal = 20.dp)) {
                // The cover runs edge to edge across the sheet, with change/remove floating on it.
                val header = rememberSavedBitmap(cover)
                if (cover.isNotEmpty()) Box(Modifier.bleed(20.dp).padding(top = 8.dp).fillMaxWidth().height(220.dp).background(TodoCard)) {
                    header?.let { Image(it, contentDescription = "Cover image", contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
                    if (!reading) Row(Modifier.align(Alignment.BottomEnd).padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CoverButton(ZenIcon.Picture, "Change cover", ::chooseCover)
                        CoverButton(ZenIcon.Close, "Remove cover") {
                            if (cover != editing?.imagePath) runCatching { java.io.File(cover).delete() }
                            cover = ""
                        }
                    }
                }
                if (reading) {
                    if (title.isNotBlank()) Text(look.spaced(title), color = TodoText, fontSize = 25.sp * look.scale, lineHeight = 32.sp * look.scale * look.lineSpacing, fontFamily = look.font.family, fontWeight = FontWeight.SemiBold, modifier = Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 10.dp))
                    else Spacer(Modifier.height(12.dp))
                } else BasicTextField(
                    value = title,
                    onValueChange = { typed ->
                        // Enter in the title moves on to the body instead of making a two-line title.
                        if ('\n' in typed) {
                            title = typed.replace("\n", "")
                            blocks.firstOrNull { it.kind.isText }?.let { focusOn(BlockFocus(it.id, 0)) }
                        } else title = typed
                    },
                    textStyle = TextStyle(color = TodoText, fontSize = 25.sp, lineHeight = 32.sp, fontWeight = FontWeight.SemiBold),
                    cursorBrush = SolidColor(TodoBlue),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    modifier = Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 10.dp).focusRequester(titleFocus).onFocusChanged { if (it.isFocused) currentId = null },
                    decorationBox = { field -> Box { if (title.isEmpty()) Text("Title", color = TodoMuted.copy(alpha = .6f), fontSize = 25.sp, lineHeight = 32.sp, fontWeight = FontWeight.SemiBold); field() } }
                )
                editing?.link?.takeIf { it.isNotBlank() }?.let { url ->
                    Row(
                        Modifier.padding(bottom = 12.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(TodoCard).clickable { openLink(context, url) }.padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        Glyph(ZenIcon.Link, TodoBlue, 16.dp, modifier = Modifier.padding(top = 2.dp, end = 10.dp))
                        Text(url, color = TodoBlue, fontSize = 14.sp, lineHeight = 20.sp, modifier = Modifier.weight(1f))
                        Glyph(ZenIcon.ArrowUpRight, TodoMuted, 16.dp, modifier = Modifier.padding(top = 2.dp, start = 8.dp))
                    }
                    // YouTube saves get the video's transcript when saved; this fetches it for older saves, or after a failed try.
                    if (Transcripts.videoId(url) != null && transcript.isBlank()) {
                        Text(
                            if (fetchingTranscript) "Getting transcript…" else "Get transcript", color = if (fetchingTranscript) TodoMuted else TodoBlue, fontSize = 14.sp,
                            modifier = Modifier.padding(bottom = 12.dp).clip(RoundedCornerShape(10.dp)).background(TodoCard)
                                .clickable(enabled = !fetchingTranscript) {
                                    fetchingTranscript = true
                                    scope.launch {
                                        val paragraphs = withContext(Dispatchers.IO) { Transcripts.fetch(url) }
                                        fetchingTranscript = false
                                        if (paragraphs == null) android.widget.Toast.makeText(context, "No transcript found for this video", android.widget.Toast.LENGTH_SHORT).show()
                                        else setTranscript(paragraphs.joinToString("\n\n"))
                                    }
                                }.padding(horizontal = 14.dp, vertical = 10.dp)
                        )
                    }
                }
                if (reading) SelectionContainer {
                    Column {
                        blocks.forEach { block -> key(block.id) { ReaderBlock(block, look, onViewImage = { viewingImage = it }, onEditImage = { redrawing = block }) { toggleCheck(block) } } }
                        if (isSave && comment.isNotBlank()) ReaderComment(comment, look)
                        transcriptSection(look)
                    }
                } else {
                    var ordinal = 0
                    NoteRuns.group(blocks).forEach { range ->
                        val block = blocks[range.first]
                        if (block.kind.isText) {
                            val run = ordinal++
                            key("run", run) {
                                NoteTextRun(
                                    lines = blocks.subList(range.first, range.last + 1).toList(),
                                    value = valueOf(run, range),
                                    hint = if (blocks.size == 1) "Start writing…" else null,
                                    focusTarget = focusTarget,
                                    onFocusHandled = { focusTarget = null },
                                    onFocused = { NoteRuns.runs(blocks).getOrNull(run)?.let { currentId = NoteRuns.blockAt(blocks, it, valueOf(run, it).selection.start).id } },
                                    onToggleCheck = ::toggleCheck,
                                    onBackspaceAtStart = {
                                        NoteRuns.runs(blocks).getOrNull(run)?.let { NoteBlocks.backspaceAtStart(blocks, it.first)?.let(::apply) }
                                    },
                                    onValueChange = { typed ->
                                        // Read the run afresh: several changes can arrive before the next recomposition.
                                        val now = NoteRuns.runs(blocks).getOrNull(run)
                                        val result = now?.let { NoteRuns.edit(blocks, it, typed.text, typed.selection.start) }
                                        when {
                                            now == null -> Unit
                                            result == null -> { values[run] = typed; currentId = NoteRuns.blockAt(blocks, now, typed.selection.start).id }
                                            else -> {
                                                commit(result.blocks)
                                                if (result.exact) {
                                                    values[run] = typed
                                                    currentId = result.focus?.id
                                                } else result.focus?.let(::focusOn)
                                            }
                                        }
                                    }
                                )
                            }
                        } else key(block.id) {
                            when (block.kind) {
                                BlockKind.FILE -> AttachmentChip(block.text, block.prefix, Modifier.padding(vertical = 6.dp), onRemove = { commit(blocks.filterNot { it.id == block.id }) })
                                BlockKind.IMAGE -> ImageBlock(block.text, onView = { viewingImage = block.text }, onEdit = { redrawing = block }) { commit(blocks.filterNot { it.id == block.id }) }
                                BlockKind.DIVIDER -> DividerBlock { commit(blocks.filterNot { it.id == block.id }) }
                                else -> TableBlock(
                                    block,
                                    onFocused = { currentId = block.id },
                                    onChange = { rows -> blocks.indexOfFirst { it.id == block.id }.takeIf { it >= 0 }?.let { i -> blocks[i] = block.copy(rows = rows, raw = null); commit(blocks.toList()) } },
                                    onDelete = { commit(blocks.filterNot { it.id == block.id }) }
                                )
                            }
                        }
                    }
                }
                if (reading) Spacer(Modifier.height(200.dp)) // room to scroll the last lines clear of the buttons
                else {
                    if (isSave) CommentBox(comment, onChange = { comment = it }, onFocused = { currentId = null })
                    SelectionContainer { transcriptSection(ReaderLook()) }
                    // The empty space under the note puts the cursor at the end of the last line.
                    Spacer(
                        Modifier.fillMaxWidth().height(220.dp).clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                            blocks.lastOrNull { it.kind.isText }?.let { focusOn(BlockFocus(it.id, it.text.length)) }
                        }
                    )
                }
            }
            NoteScrollbar(noteScroll, Modifier.align(Alignment.TopEnd))
            // Cover, reader mode and Done float at the bottom right, like search and voice on the main screens.
            // Hidden in reader mode so nothing floats over the text; the reader bar has the book button to leave it.
            if (!reading) Column(Modifier.align(Alignment.BottomEnd).padding(end = 16.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                EditorRoundButton(ZenIcon.Share, "Share note") { sharing = true }
                if (cover.isEmpty() && !reading) EditorRoundButton(ZenIcon.Picture, "Add cover", onClick = ::chooseCover)
                if (editing != null && !editing.isPrivate) PinNoteButton(editing.id)
                EditorRoundButton(ZenIcon.Book, if (reading) "Edit note" else "Reader mode", active = reading) { setReading(!reading) }
                EditorRoundButton(ZenIcon.Check, "Done", filled = true, onClick = ::finish)
            }
            }
            if (attaching) Text("Adding file…", color = TodoMuted, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp))
            if (reading) ReaderBar(look, ::setLook, onExit = { setReading(false) })
            else {
            val currentBlock = blocks.firstOrNull { it.id == currentId }
            val current = currentBlock?.kind
            val level = if (current == BlockKind.HEADING) currentBlock.level else 0
            NoteToolbar(
                listOf(
                    ToolSlot.Group(
                        ToolAction("Headings", text = "H"),
                        listOf(
                            ToolAction("Title", text = "H1", active = level == 1) { heading(1) },
                            ToolAction("Heading", text = "H2", active = level == 2) { heading(2) },
                            ToolAction("Small", text = "H3", active = level == 3) { heading(3) },
                            ToolAction("Text", text = "Aa") { heading(0) }
                        )
                    ),
                    ToolSlot.Group(
                        ToolAction("Lists", icon = ZenIcon.Lines),
                        listOf(
                            ToolAction("Bullets", icon = ZenIcon.Lines, active = current == BlockKind.BULLET) { toggle(BlockKind.BULLET) },
                            ToolAction("Checklist", icon = ZenIcon.Checkbox, active = current == BlockKind.CHECK) { toggle(BlockKind.CHECK) },
                            ToolAction("Numbered", text = "1.", active = current == BlockKind.NUMBERED) { toggle(BlockKind.NUMBERED) }
                        )
                    ),
                    ToolSlot.Single(ToolAction("Quote", icon = ZenIcon.Quote, active = current == BlockKind.QUOTE) { toggle(BlockKind.QUOTE) }),
                    ToolSlot.Single(ToolAction("Divider", icon = ZenIcon.Minus) { insert(NoteBlock(NoteBlocks.newId(), BlockKind.DIVIDER)) }),
                    ToolSlot.Single(ToolAction("Table", icon = ZenIcon.Table) { insert(NoteBlock(NoteBlocks.newId(), BlockKind.TABLE, rows = listOf(listOf("", ""), listOf("", "")))) }),
                    ToolSlot.Group(
                        ToolAction("Add", icon = ZenIcon.Paperclip, enabled = !attaching),
                        listOf(
                            ToolAction("Photo", icon = ZenIcon.Picture) { pickMedia.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)) },
                            ToolAction("Camera", icon = ZenIcon.Camera, onClick = ::openCamera),
                            ToolAction("File", icon = ZenIcon.File) { pickFile.launch(arrayOf("*/*")) },
                            ToolAction("Doodle", icon = ZenIcon.Pencil) { showDoodle = true }
                        )
                    ),
                    ToolSlot.Single(ToolAction("Undo", icon = ZenIcon.Undo, enabled = undoStack.isNotEmpty(), onClick = ::undo)),
                    ToolSlot.Single(ToolAction("Redo", icon = ZenIcon.Redo, enabled = redoStack.isNotEmpty(), onClick = ::redo))
                )
            )
            }
        }
    }
    cropUri?.let { uri ->
        ImageCropDialog(
            uri = uri,
            defaultAspect = CropAspect.Wide,
            onCancel = { cropUri = null },
            onResult = { path -> cropUri = null; useCover(path) }
        )
    }
    // Shares the note as it stands now, saved or not.
    if (sharing) NoteShareSheet(
        (editing ?: BoardCard(workspace = Workspace.NOTES, columnId = columnId, kind = CardKind.NOTE, title = ""))
            .copy(title = title.trim(), details = fullText(blocks).trim(), imagePath = cover, comment = comment.trim())
    ) { sharing = false }
    if (showDoodle) {
        DoodleDialog(
            onDismiss = { showDoodle = false },
            onSaved = { path -> insert(NoteBlock(NoteBlocks.newId(), BlockKind.IMAGE, path)); showDoodle = false }
        )
    }
    redrawing?.let { target ->
        DoodleDialog(
            editPath = target.text,
            onDismiss = { redrawing = null },
            onSaved = { path ->
                redrawing = null
                // The edit is saved as a new picture, so undo can still go back to the old one.
                blocks.indexOfFirst { it.id == target.id }.takeIf { it >= 0 }?.let { i -> blocks[i] = target.copy(text = path, raw = null); commit(blocks.toList()) }
            }
        )
    }
    viewingImage?.let { path -> ImageViewer(path) { viewingImage = null } }
}

/** A round icon button floating over the note: [filled] in the tab's colour (Done), [active] tinted while on (reader mode). */
@Composable
private fun EditorRoundButton(icon: ZenIcon, description: String, filled: Boolean = false, active: Boolean = false, onClick: () -> Unit) {
    Surface(
        color = if (filled) TodoBlue else if (active) GlassAccent else TodoNav, shape = CircleShape, border = GlassStroke, shadowElevation = 10.dp,
        modifier = Modifier.size(52.dp).clip(CircleShape).clickable(onClick = onClick).semantics { contentDescription = description }
    ) {
        Box(contentAlignment = Alignment.Center) { Glyph(icon, if (filled) OnAccent else TodoText, 22.dp) }
    }
}

/** How a text block's words look, the same while writing and while reading. */
internal fun noteTextStyle(block: NoteBlock): TextStyle {
    val (size, line) = when (block.kind) {
        BlockKind.HEADING -> when (block.level) { 1 -> 26.sp to 33.sp; 2 -> 22.sp to 29.sp; else -> 19.sp to 26.sp }
        else -> 17.sp to 25.sp
    }
    return TextStyle(
        color = when {
            block.checked -> TodoMuted.copy(alpha = .7f)
            block.kind == BlockKind.QUOTE -> TodoMuted
            else -> TodoText
        },
        fontSize = size, lineHeight = line,
        fontWeight = if (block.kind == BlockKind.HEADING) FontWeight.Bold else FontWeight.Normal,
        fontStyle = if (block.kind == BlockKind.QUOTE) FontStyle.Italic else FontStyle.Normal,
        textDecoration = if (block.checked) TextDecoration.LineThrough else null
    )
}

/** One line of a note: its list marker, checkbox or quote bar, then [content]. */
@Composable
private fun BlockLine(block: NoteBlock, style: TextStyle, onToggleCheck: () -> Unit, content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit) {
    val indent = block.prefix.takeWhile { it == ' ' || it == '\t' }.length
    Row(
        Modifier.fillMaxWidth().height(IntrinsicSize.Min).padding(start = (indent * 6).dp, top = if (block.kind == BlockKind.HEADING) 8.dp else 1.dp, bottom = 1.dp),
        verticalAlignment = Alignment.Top
    ) {
        val marker = Modifier.width(26.dp)
        // With extra line spacing the words sit in the middle of a taller line, so the marker moves down with them.
        val centred = style.lineHeightStyle != null && style.lineHeightStyle != androidx.compose.ui.text.style.LineHeightStyle.Default
        val lineHeight = with(LocalDensity.current) { style.lineHeight.toDp() }
        when (block.kind) {
            BlockKind.BULLET -> if (centred) Box(marker.height(lineHeight).padding(top = 2.dp), contentAlignment = Alignment.CenterStart) { Glyph(ZenIcon.Bullet, TodoBlue, 20.dp) }
                else Glyph(ZenIcon.Bullet, TodoBlue, 20.dp, modifier = marker)
            BlockKind.NUMBERED -> Text(block.prefix.trim(), color = TodoBlue, fontSize = 16.sp, lineHeight = style.lineHeight, maxLines = 1, style = TextStyle(lineHeightStyle = style.lineHeightStyle), modifier = marker)
            BlockKind.CHECK -> Box(marker.height(if (centred) lineHeight else 25.dp).clip(RoundedCornerShape(6.dp)).clickable(onClick = onToggleCheck), contentAlignment = Alignment.CenterStart) {
                Box(
                    Modifier.size(18.dp).clip(RoundedCornerShape(5.dp)).background(if (block.checked) TodoBlue else Color.Transparent)
                        .border(1.5.dp, if (block.checked) TodoBlue else TodoMuted, RoundedCornerShape(5.dp)),
                    contentAlignment = Alignment.Center
                ) { if (block.checked) Glyph(ZenIcon.Check, OnAccent, 12.dp) }
            }
            BlockKind.QUOTE -> Box(Modifier.padding(end = 12.dp, top = 3.dp, bottom = 3.dp).width(3.dp).fillMaxHeight().clip(RoundedCornerShape(2.dp)).background(TodoBlue.copy(alpha = .7f)))
            else -> Unit
        }
        content()
    }
}

/** A block in reader mode: the same look as the editor, but nothing to type into. Checkboxes and links still work. */
@Composable
private fun ReaderBlock(block: NoteBlock, look: ReaderLook, onViewImage: (String) -> Unit, onEditImage: () -> Unit, onToggleCheck: () -> Unit) {
    when (block.kind) {
        BlockKind.IMAGE -> {
            val bitmap = rememberSavedBitmap(block.text)
            if (bitmap != null) Image(
                bitmap, null, contentScale = ContentScale.Fit, alignment = Alignment.CenterStart,
                modifier = Modifier.padding(vertical = 8.dp).heightIn(max = 420.dp).clip(RoundedCornerShape(12.dp))
                    .combinedClickable(onClick = { onViewImage(block.text) }, onLongClick = onEditImage)
            )
        }
        BlockKind.DIVIDER -> HorizontalDivider(color = TodoMuted.copy(alpha = .45f), thickness = 1.5.dp, modifier = Modifier.padding(vertical = 12.dp))
        BlockKind.FILE -> AttachmentChip(block.text, block.prefix, Modifier.padding(vertical = 6.dp))
        BlockKind.TABLE -> {
            val width = block.rows.maxOfOrNull { it.size }?.coerceAtLeast(1) ?: 1
            Column(Modifier.padding(vertical = 8.dp).fillMaxWidth().clip(RoundedCornerShape(10.dp)).border(1.dp, TodoBorder, RoundedCornerShape(10.dp))) {
                block.rows.forEachIndexed { r, row ->
                    if (r > 0) HorizontalDivider(color = TodoBorder)
                    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).then(if (r == 0) Modifier.background(TodoCardRaised) else Modifier)) {
                        (0 until width).forEach { c ->
                            if (c > 0) VerticalDivider(color = TodoBorder)
                            Text(
                                look.spaced(Markdown.styled(row.getOrNull(c).orEmpty(), links = true)), color = if (r == 0) TodoText else TodoMuted, fontSize = 15.sp * look.scale,
                                lineHeight = 21.sp * look.scale * look.lineSpacing, fontFamily = look.font.family,
                                fontWeight = if (r == 0) FontWeight.SemiBold else FontWeight.Normal,
                                modifier = Modifier.weight(1f).padding(horizontal = 10.dp, vertical = 9.dp)
                            )
                        }
                    }
                }
            }
        }
        else -> {
            val style = noteTextStyle(block).let { it.copy(fontSize = it.fontSize * look.scale, lineHeight = it.lineHeight * look.scale * look.lineSpacing, fontFamily = look.font.family, lineHeightStyle = look.lineStyle) }
            // A blank line between paragraphs becomes the chosen paragraph gap.
            if (block.kind == BlockKind.PARAGRAPH && block.text.isBlank()) Spacer(Modifier.height(look.paragraphGap))
            else BlockLine(block, style, onToggleCheck) {
                Text(look.spaced(Markdown.styled(block.text, links = true)), style = style, modifier = Modifier.weight(1f))
            }
        }
    }
}

/** A saved item's own comment, shown read-only under the content in reader mode. */
@Composable
private fun ReaderComment(text: String, look: ReaderLook) {
    Row(Modifier.padding(top = 18.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(TodoCard).height(IntrinsicSize.Min)) {
        Box(Modifier.width(3.dp).fillMaxHeight().background(Amber.copy(alpha = .7f)))
        Text(look.spaced(text), color = TodoText, fontSize = 15.sp * look.scale, lineHeight = 22.sp * look.scale * look.lineSpacing, fontFamily = look.font.family, modifier = Modifier.weight(1f).padding(horizontal = 14.dp, vertical = 12.dp))
    }
}

/**
 * Typefaces reader mode offers: Android's own three, plus four made for reading on screens, bundled in res/font
 * (open fonts under the SIL OFL, cut down to Latin text in regular and bold; licences in assets/licenses).
 */
internal enum class ReaderFont(val label: String, val family: FontFamily) {
    SANS("Sans", FontFamily.SansSerif),
    ATKINSON("Atkinson", bundled(R.font.atkinson_regular, R.font.atkinson_bold)),
    SERIF("Serif", FontFamily.Serif),
    LITERATA("Literata", bundled(R.font.literata_regular, R.font.literata_bold)),
    LORA("Lora", bundled(R.font.lora_regular, R.font.lora_bold)),
    MERRIWEATHER("Merriweather", bundled(R.font.merriweather_regular, R.font.merriweather_bold)),
    MONO("Mono", FontFamily.Monospace);
    /** Android's own fonts sit well on the default line; the bundled ones are centred on it, like extra line spacing. */
    val system get() = this == SANS || this == SERIF || this == MONO
}

private fun bundled(regular: Int, bold: Int) = FontFamily(
    androidx.compose.ui.text.font.Font(regular, FontWeight.Normal),
    androidx.compose.ui.text.font.Font(bold, FontWeight.Bold)
)

/** How reader mode sets text: a typeface, a size step (see [NoteReader.Sizes]) and steps of word, line and paragraph spacing. */
internal data class ReaderLook(
    val font: ReaderFont = ReaderFont.SANS,
    val size: Int = NoteReader.DEFAULT_SIZE,
    val words: Int = 1,
    val lines: Int = 1,
    val paragraphs: Int = 1
) {
    val scale get() = NoteReader.Sizes[size.coerceIn(NoteReader.Sizes.indices)]
    /** Extra space after each space character, in ems. */
    val wordSpacing get() = NoteReader.WordSpacing[words.coerceIn(NoteReader.WordSpacing.indices)]
    /** Multiplies every line height. */
    val lineSpacing get() = NoteReader.LineSpacing[lines.coerceIn(NoteReader.LineSpacing.indices)]
    /** The gap a blank line between paragraphs becomes. */
    val paragraphGap get() = NoteReader.ParagraphGap[paragraphs.coerceIn(NoteReader.ParagraphGap.indices)].dp

    /** [text] with its spaces widened (or narrowed) by [wordSpacing]. */
    fun spaced(text: AnnotatedString): AnnotatedString {
        if (wordSpacing == 0f || ' ' !in text.text) return text
        return AnnotatedString.Builder(text).apply {
            text.text.forEachIndexed { i, ch -> if (ch == ' ') addStyle(androidx.compose.ui.text.SpanStyle(letterSpacing = wordSpacing.em), i, i + 1) }
        }.toAnnotatedString()
    }
    fun spaced(text: String) = spaced(AnnotatedString(text))
    /** With extra line spacing, every line (the first and last too) gets its share, so lists open up evenly. */
    val lineStyle get() = if (lines == 1 && font.system) androidx.compose.ui.text.style.LineHeightStyle.Default
        else androidx.compose.ui.text.style.LineHeightStyle(androidx.compose.ui.text.style.LineHeightStyle.Alignment.Center, androidx.compose.ui.text.style.LineHeightStyle.Trim.None)
}

/** Remembers whether notes open in reader mode, and the reading font and size. */
internal object NoteReader {
    private const val PREFS = "daymark_settings"
    private const val KEY = "note_reader"
    private const val KEY_FONT = "note_reader_font"
    private const val KEY_SIZE = "note_reader_size"
    val Sizes = listOf(.85f, .92f, 1f, 1.1f, 1.2f, 1.32f, 1.45f)
    const val DEFAULT_SIZE = 2
    val WordSpacing = listOf(-.06f, 0f, .16f, .32f)
    val LineSpacing = listOf(.9f, 1f, 1.2f, 1.42f)
    val ParagraphGap = listOf(10, 24, 38, 56)
    val WordLabels = listOf("Tight", "Normal", "Wide", "Wider")
    val LineLabels = listOf("Tight", "Normal", "Relaxed", "Loose")
    val ParagraphLabels = listOf("Small", "Normal", "Large", "Larger")
    private fun prefs(context: android.content.Context) = context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
    fun enabled(context: android.content.Context) = prefs(context).getBoolean(KEY, false)
    fun set(context: android.content.Context, on: Boolean) = prefs(context).edit().putBoolean(KEY, on).apply()
    fun look(context: android.content.Context) = ReaderLook(
        ReaderFont.entries.firstOrNull { it.name == prefs(context).getString(KEY_FONT, null) } ?: ReaderFont.SANS,
        prefs(context).getInt(KEY_SIZE, DEFAULT_SIZE).coerceIn(Sizes.indices),
        prefs(context).getInt("note_reader_words", 1).coerceIn(WordSpacing.indices),
        prefs(context).getInt("note_reader_lines", 1).coerceIn(LineSpacing.indices),
        prefs(context).getInt("note_reader_paragraphs", 1).coerceIn(ParagraphGap.indices)
    )
    fun transcriptOpen(context: android.content.Context) = prefs(context).getBoolean("transcript_open", true)
    fun setTranscriptOpen(context: android.content.Context, open: Boolean) = prefs(context).edit().putBoolean("transcript_open", open).apply()
    fun setLook(context: android.content.Context, look: ReaderLook) = prefs(context).edit().putString(KEY_FONT, look.font.name).putInt(KEY_SIZE, look.size)
        .putInt("note_reader_words", look.words).putInt("note_reader_lines", look.lines).putInt("note_reader_paragraphs", look.paragraphs).apply()
}

/**
 * The bar at the bottom of reader mode. Font, Spacing and Size open their choices in pop-ups above the bar (like the
 * grouped buttons in the writing toolbar) and stay open while you try settings. The book button leaves reader mode;
 * Share, Pin and Done are hidden while reading so nothing floats over the text (going back saves as Done does).
 */
@Composable
private fun ReaderBar(look: ReaderLook, onChange: (ReaderLook) -> Unit, onExit: () -> Unit) {
    var open by remember { mutableStateOf<Int?>(null) }
    Column(Modifier.fillMaxWidth()) {
        HorizontalDivider(color = TodoBorder)
        Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            @Composable
            fun RowScope.item(label: String, description: String, active: Boolean = false, filled: Boolean = false, onClick: () -> Unit, face: @Composable () -> Unit, popup: (@Composable () -> Unit)? = null) =
                Box(Modifier.weight(1f)) {
                    Column(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(if (active) GlassAccent else Color.Transparent)
                            .clickable(onClick = onClick).semantics { contentDescription = description }.padding(vertical = 6.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Box(
                            Modifier.size(32.dp).clip(CircleShape).background(if (filled) TodoBlue else Color.Transparent),
                            contentAlignment = Alignment.Center
                        ) { face() }
                        Text(label, color = TodoMuted, fontSize = 11.sp, maxLines = 1, modifier = Modifier.padding(top = 2.dp))
                    }
                    if (popup != null) ToolPopup(onClose = { open = null }, content = popup)
                }
            fun toggle(index: Int) { open = if (open == index) null else index }

            item("Font", "Font: ${look.font.label}", active = open == 0, onClick = { toggle(0) },
                face = { Text("Aa", fontFamily = look.font.family, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = TodoText) },
                popup = if (open == 0) ({
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        ReaderFont.entries.chunked(4).forEach { row ->
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                row.forEach { font -> ReaderChoice("Aa", font.label, font == look.font, font.family, wide = true) { onChange(look.copy(font = font)) } }
                            }
                        }
                    }
                }) else null)
            item("Spacing", "Spacing", active = open == 1, onClick = { toggle(1) },
                face = { Glyph(ZenIcon.Lines, TodoText, 20.dp) },
                popup = if (open == 1) ({
                    Column(Modifier.padding(4.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        SpacingRow("Words", NoteReader.WordLabels, look.words) { onChange(look.copy(words = it)) }
                        SpacingRow("Lines", NoteReader.LineLabels, look.lines) { onChange(look.copy(lines = it)) }
                        SpacingRow("Paragraphs", NoteReader.ParagraphLabels, look.paragraphs) { onChange(look.copy(paragraphs = it)) }
                    }
                }) else null)
            item("${(look.scale * 100).toInt()}%", "Text size", active = open == 2, onClick = { toggle(2) },
                face = { Text("A", fontSize = 19.sp, fontWeight = FontWeight.SemiBold, color = TodoText) },
                popup = if (open == 2) ({
                    Row(Modifier.padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                        @Composable
                        fun sizeButton(size: androidx.compose.ui.unit.TextUnit, description: String, enabled: Boolean, step: Int) = Box(
                            Modifier.size(52.dp).clip(CircleShape).background(TodoCardRaised)
                                .clickable(enabled = enabled) { onChange(look.copy(size = look.size + step)) }.semantics { contentDescription = description },
                            contentAlignment = Alignment.Center
                        ) { Text("A", fontSize = size, fontWeight = FontWeight.SemiBold, color = if (enabled) TodoText else TodoMuted.copy(alpha = .35f)) }
                        sizeButton(14.sp, "Smaller text", look.size > 0, -1)
                        Text(
                            "${(look.scale * 100).toInt()}%", color = TodoText, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.width(64.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                        sizeButton(21.sp, "Bigger text", look.size < NoteReader.Sizes.lastIndex, 1)
                    }
                }) else null)
            VerticalDivider(color = TodoBorder, modifier = Modifier.height(36.dp).padding(horizontal = 2.dp))
            item("Reader", "Leave reader mode", active = true, onClick = onExit, face = { Glyph(ZenIcon.Book, TodoText, 20.dp) })
        }
    }
}

/** One choice in a reader pop-up: a big sample over a small label, highlighted when it is the current one. */
@Composable
private fun ReaderChoice(sample: String, label: String, active: Boolean, family: FontFamily? = null, wide: Boolean = false, onClick: () -> Unit) {
    Column(
        Modifier.width(if (wide) 80.dp else 64.dp).clip(RoundedCornerShape(14.dp)).background(if (active) GlassAccent else Color.Transparent)
            .clickable(onClick = onClick).semantics { contentDescription = label }.padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        val tint = if (active) TodoText else TodoMuted
        Box(Modifier.height(30.dp), contentAlignment = Alignment.Center) {
            Text(sample, color = tint, fontSize = 20.sp, fontFamily = family, fontWeight = FontWeight.SemiBold, maxLines = 1)
        }
        Text(label, color = tint, fontSize = 11.sp, maxLines = 1, modifier = Modifier.padding(top = 3.dp))
    }
}

/** A labelled row of spacing steps, e.g. Lines: Tight · Normal · Relaxed · Loose. */
@Composable
private fun SpacingRow(title: String, labels: List<String>, selected: Int, onPick: (Int) -> Unit) {
    Column {
        Text(title.uppercase(), color = TodoMuted, fontSize = 11.sp, fontWeight = FontWeight.Medium, letterSpacing = 1.2.sp, modifier = Modifier.padding(start = 6.dp, bottom = 4.dp))
        Row(Modifier.clip(RoundedCornerShape(50)).background(TodoCardRaised).padding(3.dp)) {
            labels.forEachIndexed { i, label ->
                val active = i == selected
                Text(
                    label, fontSize = 13.sp, color = if (active) OnAccent else TodoMuted, fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    modifier = Modifier.width(72.dp).clip(RoundedCornerShape(50)).background(if (active) TodoBlue else Color.Transparent)
                        .clickable { onPick(i) }.semantics { contentDescription = "$title $label" }.padding(vertical = 8.dp)
                )
            }
        }
    }
}

@Composable
private fun ImageBlock(path: String, onView: () -> Unit, onEdit: () -> Unit, onRemove: () -> Unit) {
    val bitmap = rememberSavedBitmap(path)
    val haptics = androidx.compose.ui.platform.LocalHapticFeedback.current
    Box(Modifier.padding(vertical = 8.dp)) {
        if (bitmap != null) Image(
            bitmap, null, contentScale = ContentScale.Fit, alignment = Alignment.CenterStart,
            modifier = Modifier.heightIn(max = 320.dp).clip(RoundedCornerShape(12.dp)).border(1.dp, TodoBorder, RoundedCornerShape(12.dp))
                .combinedClickable(onClick = onView, onLongClick = { haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress); onEdit() })
        )
        else Box(Modifier.fillMaxWidth().height(90.dp).clip(RoundedCornerShape(12.dp)).background(TodoCardRaised), contentAlignment = Alignment.Center) { Glyph(ZenIcon.Picture, TodoMuted, 26.dp) }
        RemoveButton(Modifier.align(Alignment.TopEnd).padding(6.dp), onRemove)
    }
}

@Composable
private fun DividerBlock(onRemove: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        HorizontalDivider(color = TodoMuted.copy(alpha = .45f), thickness = 1.5.dp, modifier = Modifier.weight(1f))
        Glyph(ZenIcon.Close, TodoMuted.copy(alpha = .7f), 18.dp, modifier = Modifier.clip(CircleShape).clickable(onClick = onRemove).padding(horizontal = 10.dp, vertical = 2.dp))
    }
}

@Composable
private fun RemoveButton(modifier: Modifier, onClick: () -> Unit) {
    Box(modifier.size(28.dp).clip(CircleShape).background(Color(0xCC1C1C1A)).border(1.dp, TodoBorder, CircleShape).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Glyph(ZenIcon.Close, Color(0xFFEDEAE3), 17.dp)
    }
}

/** A table as a grid of cells you type into, with row/column controls underneath. */
@Composable
private fun TableBlock(block: NoteBlock, onFocused: () -> Unit, onChange: (List<List<String>>) -> Unit, onDelete: () -> Unit) {
    val width = block.rows.maxOfOrNull { it.size }?.coerceAtLeast(1) ?: 1
    val rows = block.rows.ifEmpty { listOf(List(width) { "" }) }.map { row -> List(width) { row.getOrNull(it).orEmpty() } }
    Column(Modifier.padding(vertical = 8.dp)) {
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).border(1.dp, TodoBorder, RoundedCornerShape(10.dp))) {
            rows.forEachIndexed { r, row ->
                if (r > 0) HorizontalDivider(color = TodoBorder)
                Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).then(if (r == 0) Modifier.background(TodoCardRaised) else Modifier)) {
                    row.forEachIndexed { c, cell ->
                        if (c > 0) VerticalDivider(color = TodoBorder)
                        val style = TextStyle(color = if (r == 0) TodoText else TodoMuted, fontSize = 15.sp, fontWeight = if (r == 0) FontWeight.SemiBold else FontWeight.Normal)
                        BasicTextField(
                            value = cell,
                            onValueChange = { typed -> onChange(rows.mapIndexed { ri, cells -> if (ri != r) cells else cells.mapIndexed { ci, old -> if (ci == c) typed.replace("|", "/") else old } }) },
                            textStyle = style,
                            cursorBrush = SolidColor(TodoBlue),
                            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                            modifier = Modifier.weight(1f).padding(horizontal = 10.dp, vertical = 9.dp).onFocusChanged { if (it.isFocused) onFocused() },
                            decorationBox = { field -> Box { if (cell.isEmpty()) Text(if (r == 0) "Heading" else "", style = style.copy(color = TodoMuted.copy(alpha = .45f))); field() } }
                        )
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 2.dp), horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
            @Composable
            fun control(label: String, enabled: Boolean = true, action: () -> Unit) = Text(
                label, color = if (enabled) TodoBlue else TodoMuted.copy(alpha = .35f), fontSize = 13.sp,
                modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(enabled = enabled, onClick = action).padding(horizontal = 8.dp, vertical = 7.dp)
            )
            // Delete leads the row on the left, so it is never pushed off the edge or hidden under the floating buttons.
            Box(
                Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onDelete).semantics { contentDescription = "Delete table" }.padding(horizontal = 8.dp, vertical = 6.dp)
            ) { Glyph(ZenIcon.Trash, Danger, 18.dp) }
            control("+ Row") { onChange(rows + listOf(List(width) { "" })) }
            control("− Row", rows.size > 1) { onChange(rows.dropLast(1)) }
            control("+ Column") { onChange(rows.map { it + "" }) }
            control("− Column", width > 1) { onChange(rows.map { it.dropLast(1) }) }
        }
    }
}

@Composable
private fun CoverButton(icon: ZenIcon, description: String, onClick: () -> Unit) {
    Box(
        Modifier.size(38.dp).clip(CircleShape).background(Color(0x99141413)).border(0.75.dp, Color(0x40FFFFFF), CircleShape)
            .clickable(onClick = onClick).semantics { contentDescription = description },
        contentAlignment = Alignment.Center
    ) { Glyph(icon, Color(0xFFEDEAE3), 17.dp) }
}

/** Lets a child ignore [horizontal] padding on its parent so it can run edge to edge. */
private fun Modifier.bleed(horizontal: androidx.compose.ui.unit.Dp) = layout { measurable, constraints ->
    val extra = (horizontal * 2).roundToPx()
    val placeable = measurable.measure(constraints.copy(minWidth = constraints.maxWidth + extra, maxWidth = constraints.maxWidth + extra))
    layout(constraints.maxWidth, placeable.height) { placeable.place(-extra / 2, 0) }
}

/** Your own remark on a saved item, set apart from the saved content with a soft accent bar. */
@Composable
private fun CommentBox(value: String, onChange: (String) -> Unit, onFocused: () -> Unit) {
    Column(Modifier.padding(top = 18.dp).fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 8.dp)) {
            Glyph(ZenIcon.Comment, TodoMuted, 14.dp, modifier = Modifier.padding(end = 7.dp))
            Text("COMMENT", color = TodoMuted, fontSize = 11.sp, fontWeight = FontWeight.Medium, letterSpacing = 1.6.sp)
        }
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(TodoCard).height(androidx.compose.foundation.layout.IntrinsicSize.Min)) {
            Box(Modifier.width(3.dp).fillMaxHeight().background(Amber.copy(alpha = .7f)))
            BasicTextField(
                value = value,
                onValueChange = onChange,
                textStyle = TextStyle(color = TodoText, fontSize = 15.sp, lineHeight = 22.sp),
                cursorBrush = SolidColor(TodoBlue),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                modifier = Modifier.weight(1f).padding(horizontal = 14.dp, vertical = 12.dp).onFocusChanged { if (it.isFocused) onFocused() },
                decorationBox = { field -> Box { if (value.isEmpty()) Text("Add a comment or extra note…", color = TodoMuted.copy(alpha = .7f), fontSize = 15.sp, lineHeight = 22.sp); field() } }
            )
        }
    }
}

/**
 * A YouTube transcript under its own heading. The chevron folds it away (the choice is remembered for the next note);
 * the refresh button fetches it again, for example after an update that formats transcripts better.
 */
@Composable
private fun TranscriptSection(text: String, look: ReaderLook, open: Boolean, refreshing: Boolean, onToggle: () -> Unit, onRefresh: (() -> Unit)?) {
    val paragraphs = remember(text) { text.split(Regex("\\n\\s*\\n")).map { it.replace('\n', ' ').trim() }.filter { it.isNotBlank() } }
    val words = remember(text) { text.split(Regex("\\s+")).count { it.isNotBlank() } }
    val turn by androidx.compose.animation.core.animateFloatAsState(if (open) 0f else -90f, label = "transcript chevron")
    Column(Modifier.padding(top = 18.dp).fillMaxWidth()) {
        androidx.compose.foundation.layout.Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(onClick = onToggle)
                .semantics { contentDescription = if (open) "Collapse transcript" else "Expand transcript" }.padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Glyph(ZenIcon.ChevronDown, TodoText, 20.dp, modifier = Modifier.graphicsLayer { rotationZ = turn })
            Text("Transcript", color = TodoText, fontSize = 22.sp * look.scale, fontWeight = FontWeight.SemiBold, fontFamily = look.font.family, modifier = Modifier.padding(start = 8.dp))
            Text(if (refreshing) "Updating…" else "%,d words".format(words), color = TodoMuted, fontSize = 13.sp, modifier = Modifier.padding(start = 10.dp, top = 3.dp).weight(1f))
            if (onRefresh != null) Box(
                Modifier.padding(end = 64.dp).size(36.dp).clip(CircleShape).clickable(enabled = !refreshing, onClick = onRefresh).semantics { contentDescription = "Get transcript again" },
                contentAlignment = Alignment.Center
            ) { Glyph(ZenIcon.Repeat, if (refreshing) TodoMuted.copy(alpha = .4f) else TodoMuted, 17.dp) }
        }
        if (open) {
            // Only the stretch near the screen is laid out and drawn; the rest stands in as empty space of the same
            // height, so a transcript of tens of thousands of words scrolls as lightly as a short note.
            val chunks = remember(paragraphs) { paragraphs.chunked(5) }
            val heights = remember(text, look) { ChunkSizes() }
            Column(Modifier.padding(top = 6.dp)) {
                chunks.forEachIndexed { i, chunk -> key(i) { TranscriptChunk(i, chunk, look, heights) } }
            }
        }
    }
}

/** A scroll bar along the right edge of a long note; its thumb can be dragged to move through the note quickly. */
@Composable
private fun NoteScrollbar(state: androidx.compose.foundation.ScrollState, modifier: Modifier) {
    // Only long notes (more than about two screens) get a scroll bar.
    if (state.maxValue == Int.MAX_VALUE || state.viewportSize <= 0 || state.maxValue < state.viewportSize) return
    var dragging by remember { mutableStateOf(false) }
    val strength by androidx.compose.animation.core.animateFloatAsState(if (dragging) 1f else if (state.isScrollInProgress) .8f else .4f, label = "scrollbar")
    val density = LocalDensity.current
    androidx.compose.foundation.layout.BoxWithConstraints(modifier.fillMaxHeight().width(30.dp)) {
        val track = constraints.maxHeight.toFloat()
        val viewport = state.viewportSize.toFloat()
        val thumb = maxOf(track * viewport / (viewport + state.maxValue), with(density) { 56.dp.toPx() }).coerceAtMost(track)
        val travel = (track - thumb).coerceAtLeast(1f)
        val top = travel * state.value / state.maxValue
        Box(
            Modifier.offset { androidx.compose.ui.unit.IntOffset(0, top.toInt()) }.fillMaxWidth().height(with(density) { thumb.toDp() })
                .pointerInput(state) {
                    detectVerticalDragGestures(
                        onDragStart = { dragging = true }, onDragEnd = { dragging = false }, onDragCancel = { dragging = false }
                    ) { change, dy ->
                        change.consume()
                        state.dispatchRawDelta(dy * state.maxValue / travel)
                    }
                }
                .semantics { contentDescription = "Scroll bar" }
        ) {
            Box(
                Modifier.align(Alignment.CenterEnd).padding(end = 3.dp).width(if (dragging) 8.dp else 5.dp).fillMaxHeight()
                    .clip(RoundedCornerShape(50)).background(TodoMuted.copy(alpha = .75f * strength))
            )
        }
    }
}

/** A few transcript paragraphs, drawn only while they are within a couple of screens of what is showing. */
@Composable
private fun TranscriptChunk(index: Int, paragraphs: List<String>, look: ReaderLook, sizes: ChunkSizes) {
    val density = LocalDensity.current
    val screen = with(density) { androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp.dp.toPx() }
    var near by remember { mutableStateOf(index < 3) }
    Box(
        Modifier.fillMaxWidth().onGloballyPositioned { c ->
            val top = c.positionInWindow().y
            val bottom = top + c.size.height
            // A wider band to stay drawn than to start, so chunks at the edge don't flicker in and out.
            val keep = if (near) bottom > -3 * screen && top < 4 * screen else bottom > -1.5f * screen && top < 2.5f * screen
            if (near) sizes.measured(index, c.size.height, paragraphs.sumOf { it.length })
            if (keep != near) near = keep
        }
    ) {
        if (near) Column {
            paragraphs.forEach { paragraph ->
                Text(
                    look.spaced(paragraph), color = TodoText, fontSize = 16.sp * look.scale, lineHeight = 25.sp * look.scale * look.lineSpacing,
                    fontFamily = look.font.family, style = TextStyle(lineHeightStyle = look.lineStyle), modifier = Modifier.padding(bottom = look.paragraphGap - 10.dp)
                )
            }
        } else {
            // Not drawn: keep its measured height, or guess from its length using what the drawn chunks measured.
            val guess = sizes.heights[index] ?: run {
                val perChar = sizes.perChar ?: with(density) { (25.sp * look.scale * look.lineSpacing).toPx() } / 40f
                (paragraphs.sumOf { it.length } * perChar).toInt()
            }
            Spacer(Modifier.fillMaxWidth().height(with(density) { guess.toDp() }))
        }
    }
}

/** Heights of transcript chunks once drawn, and from those, how tall a character of text comes out on average. */
private class ChunkSizes {
    val heights = HashMap<Int, Int>()
    private var px = 0L
    private var chars = 0L
    val perChar: Float? get() = if (chars > 0) px.toFloat() / chars else null
    fun measured(index: Int, height: Int, length: Int) {
        val old = heights.put(index, height)
        if (old == null) { px += height; chars += length } else px += height - old
    }
}
