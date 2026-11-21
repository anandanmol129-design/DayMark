package com.anmol.daymark

import android.content.ClipData
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
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
import androidx.compose.ui.platform.AndroidClipboard
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.ParagraphStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextIndent
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

/** How far a line's words start from the left: its indent, plus room for its bullet, number, box or quote bar. */
private fun lineInset(block: NoteBlock): Dp {
    val indent = block.prefix.takeWhile { it == ' ' || it == '\t' }.length * 6
    return (indent + when (block.kind) {
        BlockKind.BULLET, BlockKind.NUMBERED, BlockKind.CHECK -> 26
        BlockKind.QUOTE -> 15
        else -> 0
    }).dp
}

private fun lineLead(block: NoteBlock): Dp = (block.prefix.takeWhile { it == ' ' || it == '\t' }.length * 6).dp

/**
 * Styles a run's text line by line: each line is its own paragraph with the block's size and weight, indented to clear its
 * marker (wrapped lines stay under the words), plus the inline bold/italic/code/link styling. Every character is kept, so
 * offsets map one to one.
 */
private class RunLook(private val looks: List<NoteBlock>, private val density: Density) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val parts = text.text.split('\n')
        val styled = buildAnnotatedString {
            parts.forEachIndexed { i, part ->
                val block = looks.getOrNull(i) ?: NoteBlock(0, BlockKind.PARAGRAPH)
                val style = noteTextStyle(block)
                val inset = with(density) { lineInset(block).toSp() }
                // Line spacing goes under each line and is trimmed from the paragraph's last line, which is the empty one
                // after the break, so that line stays flat. The run's very last line has no such empty line after it.
                val spacing = LineHeightStyle(LineHeightStyle.Alignment.Top, if (i < parts.lastIndex) LineHeightStyle.Trim.LastLineBottom else LineHeightStyle.Trim.None)
                withStyle(ParagraphStyle(lineHeight = style.lineHeight, lineHeightStyle = spacing, textIndent = TextIndent(inset, inset))) {
                    withStyle(SpanStyle(color = style.color, fontSize = style.fontSize, fontWeight = style.fontWeight, fontStyle = style.fontStyle, textDecoration = style.textDecoration)) {
                        append(Markdown.styledForEditing(part, style.color))
                        // The break ends this line's paragraph. Android lays out an empty line after it; that line takes
                        // the field's base size, which is tiny (see RunBase), so it adds next to no height.
                        // The last line ends in a zero-width space instead, so an empty last line still has its full height.
                        append(if (i < parts.lastIndex) "\n" else "\u200B")
                    }
                }
            }
        }
        return TransformedText(styled, ZeroWidthEnd(text.length))
    }
    override fun equals(other: Any?) = other is RunLook && other.looks == looks && other.density == density
    override fun hashCode() = looks.hashCode() * 31 + density.hashCode()
}

/** The words map one to one; the extra zero-width space at the end maps back to the end of the text. */
private class ZeroWidthEnd(private val length: Int) : OffsetMapping {
    override fun originalToTransformed(offset: Int) = offset
    override fun transformedToOriginal(offset: Int) = offset.coerceAtMost(length)
}

/**
 * The clipboard as a run's field sees it: when what the field copies (or cuts) is the selection last seen in this run,
 * the copy carries the lines' markdown markers, so a copied list pastes back as a list.
 */
private class MarkdownClipboard(private val base: Clipboard) : AndroidClipboard {
    // Text fields read the platform clipboard directly (to see whether Paste applies), so this must be an AndroidClipboard.
    override val clipboardManager: android.content.ClipboardManager get() = base.nativeClipboard
    var plain: String? = null
    var markdown: String? = null
    override suspend fun getClipEntry(): ClipEntry? = base.getClipEntry()
    override suspend fun setClipEntry(clipEntry: ClipEntry?) {
        val copied = clipEntry?.clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString()
        val md = markdown
        base.setClipEntry(if (copied != null && md != null && copied == plain && md != copied) ClipEntry(ClipData.newPlainText("note", md)) else clipEntry)
    }
}

/**
 * One text field for a run of text blocks ([lines]); its [value] holds the lines' words joined with line breaks. Bullets,
 * numbers, checkboxes and quote bars are drawn beside the lines, so selection runs freely from item to item.
 */
@Composable
internal fun NoteTextRun(
    lines: List<NoteBlock>,
    value: TextFieldValue,
    hint: String?,
    focusTarget: BlockFocus?,
    onFocusHandled: () -> Unit,
    onFocused: () -> Unit,
    onToggleCheck: (NoteBlock) -> Unit,
    onBackspaceAtStart: () -> Unit,
    onValueChange: (TextFieldValue) -> Unit
) {
    val requester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(focusTarget) {
        if (focusTarget != null && lines.any { it.id == focusTarget.id }) {
            runCatching { requester.requestFocus() }
            onFocusHandled()
            // When the line that had focus was just merged away, the keyboard would otherwise drop.
            keyboard?.show()
            withFrameNanos { }
            keyboard?.show()
        }
    }
    val density = LocalDensity.current
    val looks = lines.map { it.copy(id = 0, text = "") }
    val transformation = remember(looks, density) { RunLook(looks, density) }
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    val clipboard = LocalClipboard.current
    val copy = remember(clipboard) { MarkdownClipboard(clipboard) }
    SideEffect {
        val selection = value.selection
        if (!selection.collapsed && value.text == NoteRuns.text(lines)) {
            copy.plain = value.text.substring(selection.min, selection.max)
            copy.markdown = NoteRuns.markdown(lines, selection.min, selection.max)
        }
    }
    // Real sizes come from each line's span; the base size only sets the empty line Android adds after each break.
    val base = noteTextStyle(NoteBlock(0, BlockKind.PARAGRAPH)).copy(fontSize = 1.sp, lineHeight = androidx.compose.ui.unit.TextUnit.Unspecified)
    CompositionLocalProvider(LocalClipboard provides copy) {
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            textStyle = base,
            cursorBrush = SolidColor(TodoBlue),
            visualTransformation = transformation,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            onTextLayout = { layout = it },
            modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp).focusRequester(requester)
                .onFocusChanged { if (it.isFocused) onFocused() }
                .onPreviewKeyEvent { event ->
                    if (event.type == KeyEventType.KeyDown && event.key == Key.Backspace && value.selection.collapsed && value.selection.start == 0) { onBackspaceAtStart(); true } else false
                },
            decorationBox = { field ->
                Box {
                    field()
                    layout?.let { RunMarkers(lines, it, hint, onToggleCheck) }
                }
            }
        )
    }
}

/** Bullets, numbers, checkboxes, quote bars and empty-line hints, placed against the laid-out lines. */
@Composable
private fun RunMarkers(lines: List<NoteBlock>, layout: TextLayoutResult, hint: String?, onToggleCheck: (NoteBlock) -> Unit) {
    val length = layout.layoutInput.text.length
    val starts = NoteRuns.lineStarts(lines)
    val density = LocalDensity.current
    lines.forEachIndexed { i, block ->
        val start = starts[i]
        if (start > length) return@forEachIndexed
        val top = layout.getLineTop(layout.getLineForOffset(start))
        val lead = lineLead(block)
        val at = Modifier.offset { IntOffset(lead.roundToPx(), top.roundToInt()) }
        val style = noteTextStyle(block)
        when (block.kind) {
            BlockKind.BULLET -> Glyph(ZenIcon.Bullet, TodoBlue, 20.dp, modifier = at.width(26.dp))
            BlockKind.NUMBERED -> Text(block.prefix.trim(), color = TodoBlue, fontSize = 16.sp, lineHeight = style.lineHeight, maxLines = 1, modifier = at.width(26.dp))
            BlockKind.CHECK -> Box(at.width(26.dp).height(25.dp).clip(RoundedCornerShape(6.dp)).clickable { onToggleCheck(block) }, contentAlignment = Alignment.CenterStart) {
                Box(
                    Modifier.size(18.dp).clip(RoundedCornerShape(5.dp)).background(if (block.checked) TodoBlue else Color.Transparent)
                        .border(1.5.dp, if (block.checked) TodoBlue else TodoMuted, RoundedCornerShape(5.dp)),
                    contentAlignment = Alignment.Center
                ) { if (block.checked) Glyph(ZenIcon.Check, OnAccent, 12.dp) }
            }
            BlockKind.QUOTE -> {
                val bottom = layout.getLineBottom(layout.getLineForOffset((start + block.text.length).coerceAtMost(length)))
                val height = with(density) { (bottom - top).toDp() } - 6.dp
                Box(Modifier.offset { IntOffset(lead.roundToPx(), top.roundToInt() + 3.dp.roundToPx()) }.width(3.dp).height(height.coerceAtLeast(4.dp)).clip(RoundedCornerShape(2.dp)).background(TodoBlue.copy(alpha = .7f)))
            }
            else -> Unit
        }
        val placeholder = when {
            block.text.isNotEmpty() -> null
            block.kind == BlockKind.HEADING -> "Heading"
            lines.size == 1 -> hint
            else -> null
        }
        if (placeholder != null) Text(
            placeholder, style = style.copy(color = TodoMuted.copy(alpha = .55f), textDecoration = null),
            modifier = Modifier.offset { IntOffset(lineInset(block).roundToPx(), top.roundToInt()) }
        )
    }
}
