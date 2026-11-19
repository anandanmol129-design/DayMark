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
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
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
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
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
    var source by rememberSaveable(editing?.id) { mutableStateOf(editing?.details.orEmpty()) }
    var cover by rememberSaveable(editing?.id) { mutableStateOf(editing?.imagePath.orEmpty()) }
    var comment by rememberSaveable(editing?.id) { mutableStateOf(editing?.comment.orEmpty()) }
    // Saved items (and anything already carrying a remark) get a comment box under the saved content.
    val isSave = editing != null && (columns.firstOrNull { it.id == editing.columnId }?.pageId == DEFAULT_SAVED_PAGE_ID || editing.comment.isNotBlank())
    var columnId by rememberSaveable(editing?.id, initialColumnId) { mutableStateOf(initialColumnId.ifBlank { columns.firstOrNull()?.id.orEmpty() }) }
    val blocks = remember(editing?.id) { mutableStateListOf<NoteBlock>().apply { addAll(NoteBlocks.withTrailingText(NoteBlocks.parse(source))) } }
    val values = remember(editing?.id) { mutableStateMapOf<Long, TextFieldValue>() }
    var focusTarget by remember { mutableStateOf<BlockFocus?>(null) }
    var currentId by remember { mutableStateOf<Long?>(null) }
    var showDoodle by remember { mutableStateOf(false) }
    var columnMenu by remember { mutableStateOf(false) }
    val titleFocus = remember { FocusRequester() }
    // Reader mode shows the note as a page with nothing to type into, so scrolling and tapping never pop the keyboard.
    // The choice is remembered: once on, existing notes open in it. New notes always open ready to write.
    var reading by rememberSaveable(editing?.id) { mutableStateOf(editing != null && NoteReader.enabled(context)) }
    var attaching by remember { mutableStateOf(false) }
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

    fun valueOf(block: NoteBlock) = values[block.id]?.takeIf { it.text == block.text } ?: TextFieldValue(block.text, TextRange(block.text.length))
    fun commit(next: List<NoteBlock>) {
        val list = NoteBlocks.withTrailingText(next)
        blocks.clear(); blocks.addAll(list)
        values.keys.retainAll(list.map { it.id }.toSet())
        source = NoteBlocks.serialize(list)
    }
    fun apply(edit: BlockEdit) {
        edit.focus?.let { focus -> edit.blocks.firstOrNull { it.id == focus.id }?.let { values[focus.id] = TextFieldValue(it.text, TextRange(focus.cursor.coerceIn(0, it.text.length))) } }
        commit(edit.blocks)
        focusTarget = edit.focus
    }
    fun indexOf(id: Long?) = blocks.indexOfFirst { it.id == id }.takeIf { it >= 0 }
    fun toggleCheck(block: NoteBlock) {
        val i = blocks.indexOfFirst { it.id == block.id }
        if (i >= 0) {
            blocks[i] = block.copy(prefix = if (block.checked) block.prefix.replace(Regex("""\[[xX]]"""), "[ ]") else block.prefix.replace("[ ]", "[x]"))
            commit(blocks.toList())
        }
    }
    fun insert(block: NoteBlock) = apply(NoteBlocks.insertAfter(blocks, indexOf(currentId), block))
    fun toggle(kind: BlockKind) {
        val index = indexOf(currentId) ?: return
        blocks[index] = NoteBlocks.toggle(blocks[index], kind)
        commit(blocks.toList())
    }
    fun finish() {
        val details = NoteBlocks.serialize(blocks).trim()
        val cleanTitle = title.trim()
        when {
            cleanTitle.isEmpty() && details.isEmpty() && cover.isEmpty() && comment.isBlank() -> onDismiss()
            editing != null && cleanTitle == editing.title.trim() && details == editing.details.trim() && columnId == editing.columnId && cover == editing.imagePath && comment.trim() == editing.comment.trim() -> onDismiss()
            else -> onSave(cleanTitle, details, columnId, cover, comment.trim())
        }
    }

    // A picked image goes through the crop page first; it hands back a stored path just like a plain copy.
    var cropUri by rememberSaveable { mutableStateOf<android.net.Uri?>(null) }
    var cropForCover by rememberSaveable { mutableStateOf(false) }
    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) { cropForCover = false; cropUri = uri }
    }

    val pickCover = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) { cropForCover = true; cropUri = uri }
    }
    fun usePicked(path: String) {
        if (cropForCover) {
            // A cover picked and then swapped before saving is never stored, so drop it straight away.
            if (cover.isNotBlank() && cover != editing?.imagePath) runCatching { java.io.File(cover).delete() }
            cover = path
        } else insert(NoteBlock(NoteBlocks.newId(), BlockKind.IMAGE, path))
    }
    // Any kind of file: copied into the app on a background thread, then shown as a picture or a file card.
    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
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
    fun chooseCover() = pickCover.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))

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
            Box(Modifier.weight(1f).fillMaxWidth()) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
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
                    if (title.isNotBlank()) Text(title, color = TodoText, fontSize = 25.sp * look.scale, lineHeight = 32.sp * look.scale, fontFamily = look.font.family, fontWeight = FontWeight.SemiBold, modifier = Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 10.dp))
                    else Spacer(Modifier.height(12.dp))
                } else BasicTextField(
                    value = title,
                    onValueChange = { typed ->
                        // Enter in the title moves on to the body instead of making a two-line title.
                        if ('\n' in typed) {
                            title = typed.replace("\n", "")
                            blocks.firstOrNull { it.kind.isText }?.let { focusTarget = BlockFocus(it.id, 0) }
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
                }
                if (reading) SelectionContainer {
                    Column {
                        blocks.forEach { block -> key(block.id) { ReaderBlock(block, look) { toggleCheck(block) } } }
                        if (isSave && comment.isNotBlank()) ReaderComment(comment, look)
                    }
                } else blocks.forEachIndexed { index, block ->
                    key(block.id) {
                        when (block.kind) {
                            BlockKind.FILE -> AttachmentChip(block.text, block.prefix, Modifier.padding(vertical = 6.dp), onRemove = { commit(blocks.filterNot { it.id == block.id }) })
                            BlockKind.IMAGE -> ImageBlock(block.text) { commit(blocks.filterNot { it.id == block.id }) }
                            BlockKind.DIVIDER -> DividerBlock { commit(blocks.filterNot { it.id == block.id }) }
                            BlockKind.TABLE -> TableBlock(
                                block,
                                onFocused = { currentId = block.id },
                                onChange = { rows -> blocks.indexOfFirst { it.id == block.id }.takeIf { it >= 0 }?.let { i -> blocks[i] = block.copy(rows = rows, raw = null); commit(blocks.toList()) } },
                                onDelete = { commit(blocks.filterNot { it.id == block.id }) }
                            )
                            else -> TextBlock(
                                block = block,
                                value = valueOf(block),
                                hint = when {
                                    block.kind == BlockKind.HEADING -> "Heading"
                                    blocks.size == 1 -> "Start writing…"
                                    else -> null
                                },
                                focusTarget = focusTarget,
                                onFocusHandled = { focusTarget = null },
                                onFocused = { currentId = block.id },
                                onToggleCheck = { toggleCheck(block) },
                                onBackspaceAtStart = { NoteBlocks.backspaceAtStart(blocks, blocks.indexOfFirst { it.id == block.id })?.let(::apply) },
                                onValueChange = { typed ->
                                    val i = blocks.indexOfFirst { it.id == block.id }
                                    val newlines = typed.text.count { it == '\n' }
                                    when {
                                        i < 0 -> Unit
                                        newlines == 1 && typed.text.length == valueOf(block).text.length + 1 -> {
                                            // Enter: split here, carrying a list on to the next line.
                                            val at = typed.text.indexOf('\n')
                                            val joined = blocks.toMutableList().also { it[i] = block.copy(text = typed.text.removeRange(at, at + 1)) }
                                            apply(NoteBlocks.enter(joined, i, at))
                                        }
                                        newlines > 0 -> apply(NoteBlocks.paste(blocks, i, typed.text, typed.selection.start))
                                        else -> {
                                            val updated = NoteBlocks.withText(block, typed.text)
                                            val converted = NoteBlocks.shortcut(updated)
                                            if (converted.kind != updated.kind) {
                                                val cursor = (typed.selection.start - (typed.text.length - converted.text.length)).coerceIn(0, converted.text.length)
                                                values[block.id] = TextFieldValue(converted.text, TextRange(cursor))
                                                blocks[i] = converted
                                            } else {
                                                values[block.id] = typed
                                                blocks[i] = updated
                                            }
                                            source = NoteBlocks.serialize(blocks)
                                        }
                                    }
                                }
                            )
                        }
                    }
                }
                if (reading) Spacer(Modifier.height(200.dp)) // room to scroll the last lines clear of the buttons
                else {
                    if (isSave) CommentBox(comment, onChange = { comment = it }, onFocused = { currentId = null })
                    // The empty space under the note puts the cursor at the end of the last line.
                    Spacer(
                        Modifier.fillMaxWidth().height(220.dp).clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                            blocks.lastOrNull { it.kind.isText }?.let { focusTarget = BlockFocus(it.id, it.text.length) }
                        }
                    )
                }
            }
            // Cover, reader mode and Done float at the bottom right, like search and voice on the main screens.
            Column(Modifier.align(Alignment.BottomEnd).padding(end = 16.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (cover.isEmpty() && !reading) EditorRoundButton(ZenIcon.Picture, "Add cover", onClick = ::chooseCover)
                EditorRoundButton(ZenIcon.Book, if (reading) "Edit note" else "Reader mode", active = reading) { setReading(!reading) }
                EditorRoundButton(ZenIcon.Check, "Done", filled = true, onClick = ::finish)
            }
            }
            if (attaching) Text("Adding file…", color = TodoMuted, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp))
            if (reading) ReaderBar(look, ::setLook)
            else {
            val current = blocks.firstOrNull { it.id == currentId }?.kind
            NoteToolbar(
                actions = listOf(
                    "H" to { toggle(BlockKind.HEADING) },
                    "•" to { toggle(BlockKind.BULLET) },
                    "☐" to { toggle(BlockKind.CHECK) },
                    "1." to { toggle(BlockKind.NUMBERED) },
                    "❝" to { toggle(BlockKind.QUOTE) },
                    "🖼" to { pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                    "―" to { insert(NoteBlock(NoteBlocks.newId(), BlockKind.DIVIDER)) },
                    "▦" to { insert(NoteBlock(NoteBlocks.newId(), BlockKind.TABLE, rows = listOf(listOf("", ""), listOf("", "")))) },
                    "✎" to { showDoodle = true },
                    "📎" to { if (!attaching) pickFile.launch(arrayOf("*/*")) }
                ),
                active = setOfNotNull(when (current) {
                    BlockKind.HEADING -> "H"; BlockKind.BULLET -> "•"; BlockKind.CHECK -> "☐"; BlockKind.NUMBERED -> "1."; BlockKind.QUOTE -> "❝"; else -> null
                })
            )
            }
        }
    }
    cropUri?.let { uri ->
        ImageCropDialog(
            uri = uri,
            defaultAspect = if (cropForCover) CropAspect.Wide else CropAspect.Free,
            onCancel = { cropUri = null },
            onResult = { path -> cropUri = null; usePicked(path) }
        )
    }
    if (showDoodle) {
        DoodleDialog(
            onDismiss = { showDoodle = false },
            onSaved = { path -> insert(NoteBlock(NoteBlocks.newId(), BlockKind.IMAGE, path)); showDoodle = false }
        )
    }
}

/** Keeps every character (so the cursor lands where you tap) and only styles bold/italic/code/links, dimming their markers. */
private class InlineMarkdown(private val base: Color) : VisualTransformation {
    override fun filter(text: AnnotatedString) = TransformedText(Markdown.styledForEditing(text.text, base), OffsetMapping.Identity)
    override fun equals(other: Any?) = other is InlineMarkdown && other.base == base
    override fun hashCode() = base.hashCode()
}

@Composable
private fun TextBlock(
    block: NoteBlock,
    value: TextFieldValue,
    hint: String?,
    focusTarget: BlockFocus?,
    onFocusHandled: () -> Unit,
    onFocused: () -> Unit,
    onToggleCheck: () -> Unit,
    onBackspaceAtStart: () -> Unit,
    onValueChange: (TextFieldValue) -> Unit
) {
    val requester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(focusTarget) {
        if (focusTarget?.id == block.id) {
            runCatching { requester.requestFocus() }
            onFocusHandled()
            // When the line that had focus was just merged away, the keyboard would otherwise drop.
            keyboard?.show()
            withFrameNanos { }
            keyboard?.show()
        }
    }
    val style = noteTextStyle(block)
    val transformation = remember(style.color) { InlineMarkdown(style.color) }
    BlockLine(block, style, onToggleCheck) {
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            textStyle = style,
            cursorBrush = SolidColor(TodoBlue),
            visualTransformation = transformation,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            modifier = Modifier.weight(1f).focusRequester(requester)
                .onFocusChanged { if (it.isFocused) onFocused() }
                .onPreviewKeyEvent { event ->
                    if (event.type == KeyEventType.KeyDown && event.key == Key.Backspace && value.selection.collapsed && value.selection.start == 0) { onBackspaceAtStart(); true } else false
                },
            decorationBox = { field -> Box { if (value.text.isEmpty() && hint != null) Text(hint, style = style.copy(color = TodoMuted.copy(alpha = .55f), textDecoration = null)); field() } }
        )
    }
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
private fun noteTextStyle(block: NoteBlock): TextStyle {
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
        when (block.kind) {
            BlockKind.BULLET -> Glyph(ZenIcon.Bullet, TodoBlue, 20.dp, modifier = marker)
            BlockKind.NUMBERED -> Text(block.prefix.trim(), color = TodoBlue, fontSize = 16.sp, lineHeight = style.lineHeight, maxLines = 1, modifier = marker)
            BlockKind.CHECK -> Box(marker.height(25.dp).clip(RoundedCornerShape(6.dp)).clickable(onClick = onToggleCheck), contentAlignment = Alignment.CenterStart) {
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
private fun ReaderBlock(block: NoteBlock, look: ReaderLook, onToggleCheck: () -> Unit) {
    when (block.kind) {
        BlockKind.IMAGE -> {
            val bitmap = rememberSavedBitmap(block.text)
            if (bitmap != null) Image(
                bitmap, null, contentScale = ContentScale.Fit, alignment = Alignment.CenterStart,
                modifier = Modifier.padding(vertical = 8.dp).heightIn(max = 420.dp).clip(RoundedCornerShape(12.dp))
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
                                Markdown.styled(row.getOrNull(c).orEmpty(), links = true), color = if (r == 0) TodoText else TodoMuted, fontSize = 15.sp * look.scale,
                                lineHeight = 21.sp * look.scale, fontFamily = look.font.family,
                                fontWeight = if (r == 0) FontWeight.SemiBold else FontWeight.Normal,
                                modifier = Modifier.weight(1f).padding(horizontal = 10.dp, vertical = 9.dp)
                            )
                        }
                    }
                }
            }
        }
        else -> {
            val style = noteTextStyle(block).let { it.copy(fontSize = it.fontSize * look.scale, lineHeight = it.lineHeight * look.scale, fontFamily = look.font.family) }
            BlockLine(block, style, onToggleCheck) {
                Text(Markdown.styled(block.text, links = true), style = style, modifier = Modifier.weight(1f))
            }
        }
    }
}

/** A saved item's own comment, shown read-only under the content in reader mode. */
@Composable
private fun ReaderComment(text: String, look: ReaderLook) {
    Row(Modifier.padding(top = 18.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(TodoCard).height(IntrinsicSize.Min)) {
        Box(Modifier.width(3.dp).fillMaxHeight().background(Amber.copy(alpha = .7f)))
        Text(text, color = TodoText, fontSize = 15.sp * look.scale, lineHeight = 22.sp * look.scale, fontFamily = look.font.family, modifier = Modifier.weight(1f).padding(horizontal = 14.dp, vertical = 12.dp))
    }
}

/** Typefaces reader mode offers; all come with Android, so nothing is downloaded or bundled. */
internal enum class ReaderFont(val label: String, val family: FontFamily) {
    SANS("Sans", FontFamily.SansSerif), SERIF("Serif", FontFamily.Serif), MONO("Mono", FontFamily.Monospace)
}

/** How reader mode sets text: a typeface and a size step (see [NoteReader.Sizes]). */
internal data class ReaderLook(val font: ReaderFont = ReaderFont.SANS, val size: Int = NoteReader.DEFAULT_SIZE) {
    val scale get() = NoteReader.Sizes[size.coerceIn(NoteReader.Sizes.indices)]
}

/** Remembers whether notes open in reader mode, and the reading font and size. */
internal object NoteReader {
    private const val PREFS = "daymark_settings"
    private const val KEY = "note_reader"
    private const val KEY_FONT = "note_reader_font"
    private const val KEY_SIZE = "note_reader_size"
    val Sizes = listOf(.85f, .92f, 1f, 1.1f, 1.2f, 1.32f, 1.45f)
    const val DEFAULT_SIZE = 2
    private fun prefs(context: android.content.Context) = context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
    fun enabled(context: android.content.Context) = prefs(context).getBoolean(KEY, false)
    fun set(context: android.content.Context, on: Boolean) = prefs(context).edit().putBoolean(KEY, on).apply()
    fun look(context: android.content.Context) = ReaderLook(
        ReaderFont.entries.firstOrNull { it.name == prefs(context).getString(KEY_FONT, null) } ?: ReaderFont.SANS,
        prefs(context).getInt(KEY_SIZE, DEFAULT_SIZE).coerceIn(Sizes.indices)
    )
    fun setLook(context: android.content.Context, look: ReaderLook) = prefs(context).edit().putString(KEY_FONT, look.font.name).putInt(KEY_SIZE, look.size).apply()
}

/** The bar at the bottom of reader mode: pick a typeface, and make the text smaller or bigger. */
@Composable
private fun ReaderBar(look: ReaderLook, onChange: (ReaderLook) -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        HorizontalDivider(color = TodoBorder)
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.clip(RoundedCornerShape(50)).background(TodoCardRaised).padding(3.dp)) {
                ReaderFont.entries.forEach { font ->
                    val active = font == look.font
                    Text(
                        font.label, fontFamily = font.family, fontSize = 15.sp, color = if (active) OnAccent else TodoMuted,
                        fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                        modifier = Modifier.clip(RoundedCornerShape(50)).background(if (active) TodoBlue else Color.Transparent)
                            .clickable { onChange(look.copy(font = font)) }.semantics { contentDescription = "${font.label} font" }
                            .padding(horizontal = 14.dp, vertical = 8.dp)
                    )
                }
            }
            Spacer(Modifier.weight(1f))
            @Composable
            fun sizeButton(label: String, size: androidx.compose.ui.unit.TextUnit, description: String, enabled: Boolean, step: Int) = Box(
                Modifier.size(42.dp).clip(CircleShape).background(TodoCardRaised)
                    .clickable(enabled = enabled) { onChange(look.copy(size = look.size + step)) }.semantics { contentDescription = description },
                contentAlignment = Alignment.Center
            ) { Text(label, fontSize = size, fontWeight = FontWeight.SemiBold, color = if (enabled) TodoText else TodoMuted.copy(alpha = .35f)) }
            sizeButton("A", 13.sp, "Smaller text", look.size > 0, -1)
            Text(
                "${(look.scale * 100).toInt()}%", color = TodoMuted, fontSize = 13.sp,
                modifier = Modifier.width(52.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
            sizeButton("A", 19.sp, "Bigger text", look.size < NoteReader.Sizes.lastIndex, 1)
        }
    }
}

@Composable
private fun ImageBlock(path: String, onRemove: () -> Unit) {
    val bitmap = rememberSavedBitmap(path)
    Box(Modifier.padding(vertical = 8.dp)) {
        if (bitmap != null) Image(
            bitmap, null, contentScale = ContentScale.Fit, alignment = Alignment.CenterStart,
            modifier = Modifier.heightIn(max = 320.dp).clip(RoundedCornerShape(12.dp)).border(1.dp, TodoBorder, RoundedCornerShape(12.dp))
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
            control("Add row") { onChange(rows + listOf(List(width) { "" })) }
            control("Remove row", rows.size > 1) { onChange(rows.dropLast(1)) }
            control("Add column") { onChange(rows.map { it + "" }) }
            control("Remove column", width > 1) { onChange(rows.map { it.dropLast(1) }) }
            Spacer(Modifier.weight(1f))
            control("Delete", action = onDelete)
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
