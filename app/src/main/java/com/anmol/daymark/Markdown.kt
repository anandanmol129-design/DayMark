package com.anmol.daymark

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas as AndroidCanvas
import android.graphics.Paint
import android.graphics.Path as AndroidPath
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import java.io.File
import java.util.UUID

/** Notes keep their body as plain markdown text; images are embedded as `![](absolute path)`. */
sealed interface MdBlock {
    data class Heading(val level: Int, val text: String) : MdBlock
    data class Bullet(val text: String) : MdBlock
    data class Image(val path: String) : MdBlock
    data class File(val name: String, val path: String) : MdBlock
    data class Table(val rows: List<List<String>>) : MdBlock
    data class Paragraph(val text: String) : MdBlock
    data object Rule : MdBlock
}

private val ImageLine = Regex("""^!\[[^\]]*]\((.+)\)$""")
/** An attached file: `[📎 name](path)` on a line of its own. */
private val FileLine = Regex("""^\[📎 ?([^\]]*)]\((/[^)]+)\)$""")
private val RuleLine = Regex("""^(-{3,}|\*{3,}|_{3,})$""")
private val TableSeparator = Regex("""^\|?[\s:|-]+\|?$""")

object Markdown {
    fun parse(source: String): List<MdBlock> {
        val blocks = mutableListOf<MdBlock>()
        val lines = source.lines()
        var index = 0
        while (index < lines.size) {
            val line = lines[index].trim()
            index++
            when {
                line.isEmpty() -> Unit
                RuleLine.matches(line) -> blocks += MdBlock.Rule
                ImageLine.matches(line) -> blocks += MdBlock.Image(ImageLine.find(line)!!.groupValues[1])
                FileLine.matches(line) -> FileLine.find(line)!!.groupValues.let { blocks += MdBlock.File(it[1], it[2]) }
                line.startsWith("#") -> {
                    val level = line.takeWhile { it == '#' }.length.coerceIn(1, 3)
                    blocks += MdBlock.Heading(level, line.trimStart('#').trim())
                }
                line.startsWith("- ") || line.startsWith("* ") || line == "-" -> blocks += MdBlock.Bullet(line.drop(1).trim())
                line.startsWith("|") -> {
                    val rows = mutableListOf(line)
                    while (index < lines.size && lines[index].trim().startsWith("|")) rows += lines[index++].trim()
                    blocks += MdBlock.Table(tableRows(rows))
                }
                else -> blocks += MdBlock.Paragraph(line)
            }
        }
        return blocks
    }

    fun imagePaths(source: String): List<String> = source.lines().mapNotNull { ImageLine.find(it.trim())?.groupValues?.get(1) }

    /** Attached files as (name, path). */
    fun attachments(source: String): List<Pair<String, String>> = source.lines().mapNotNull { FileLine.find(it.trim())?.groupValues?.let { g -> g[1] to g[2] } }

    /** Every stored file a note body points to: its pictures and its attachments. */
    fun filePaths(source: String): List<String> = imagePaths(source) + attachments(source).map { it.second }

    /** The note body with every picture or attachment line pointing at one of [paths] taken out. */
    fun withoutFiles(source: String, paths: Set<String>): String = source.split('\n').filterNot { line ->
        val t = line.trim()
        (ImageLine.find(t)?.groupValues?.get(1) ?: FileLine.find(t)?.groupValues?.get(2))?.let { it in paths } == true
    }.joinToString("\n")


    /** Splits table source lines into cells, dropping the `| --- |` separator row (shared by the card and the editor). */
    private val CellBreak = Regex("<br\\s*/?>", RegexOption.IGNORE_CASE)

    fun tableRows(lines: List<String>): List<List<String>> =
        lines.map { it.trim() }.filterNot { TableSeparator.matches(it) && it.contains('-') }.map { row -> row.trim('|').split('|').map { it.trim().replace(CellBreak, "\n") } }

    /** Bold, italic, code, strikethrough and links inside one line of text; markers are the characters to hide or dim. */
    fun inlineSpans(text: String): List<InlineSpan> {
        val spans = mutableListOf<InlineSpan>()
        // Code and links are claimed first, so a "_" or "*" inside them is never read as emphasis.
        fun free(range: IntRange) = spans.none { (it.kind == InlineKind.CODE || it.kind == InlineKind.LINK) && it.whole.first <= range.last && range.first <= it.whole.last }
        InlineCode.findAll(text).forEach { spans += InlineSpan(InlineKind.CODE, it.range, listOf(it.range.first..it.range.first, it.range.last..it.range.last)) }
        InlineLink.findAll(text).filter { free(it.range) }.forEach { m ->
            val label = m.groups[1]!!.range
            spans += InlineSpan(InlineKind.LINK, m.range, listOf(m.range.first..m.range.first, (label.last + 1)..m.range.last))
        }
        BareUrl.findAll(text).filter { free(it.range) }.forEach { spans += InlineSpan(InlineKind.LINK, it.range, emptyList()) }
        listOf(InlineBold to InlineKind.BOLD, InlineStrike to InlineKind.STRIKE, InlineItalic to InlineKind.ITALIC).forEach { (regex, kind) ->
            regex.findAll(text).filter { free(it.range) }.forEach { m ->
                val inner = (m.groups[1] ?: m.groups[2])!!.range
                spans += InlineSpan(kind, m.range, listOf(m.range.first until inner.first, (inner.last + 1)..m.range.last))
            }
        }
        return spans
    }

    /** Read view of a line: inline markers hidden, the words styled. */
    fun styled(text: String, links: Boolean = false): AnnotatedString {
        val spans = inlineSpans(text)
        if (spans.isEmpty()) return AnnotatedString(text)
        val hidden = BooleanArray(text.length).also { flags -> spans.forEach { span -> span.markers.forEach { r -> r.forEach { if (it in flags.indices) flags[it] = true } } } }
        val map = IntArray(text.length + 1)
        val out = StringBuilder()
        text.forEachIndexed { i, c -> map[i] = out.length; if (!hidden[i]) out.append(c) }
        map[text.length] = out.length
        return buildAnnotatedString {
            append(out.toString())
            spans.forEach { span ->
                addStyle(inlineStyle(span.kind), map[span.whole.first], map[span.whole.last + 1])
                // Reader mode: links open when tapped.
                if (links && span.kind == InlineKind.LINK) {
                    val raw = text.substring(span.whole.first, span.whole.last + 1)
                    val url = InlineLink.matchEntire(raw)?.groupValues?.get(2) ?: raw
                    if (url.startsWith("http://", true) || url.startsWith("https://", true)) addLink(androidx.compose.ui.text.LinkAnnotation.Url(url), map[span.whole.first], map[span.whole.last + 1])
                }
            }
        }
    }

    /** Editor view of a line: every character kept (so the cursor maps 1:1), markers dimmed, the words styled. */
    fun styledForEditing(text: String, base: Color): AnnotatedString = buildAnnotatedString {
        append(text)
        inlineSpans(text).forEach { span ->
            addStyle(inlineStyle(span.kind), span.whole.first, span.whole.last + 1)
            span.markers.forEach { addStyle(SpanStyle(color = base.copy(alpha = .35f), fontWeight = FontWeight.Normal, fontStyle = FontStyle.Normal, textDecoration = TextDecoration.None, background = Color.Transparent), it.first, it.last + 1) }
        }
    }

    fun inlineStyle(kind: InlineKind) = when (kind) {
        InlineKind.BOLD -> SpanStyle(fontWeight = FontWeight.Bold)
        InlineKind.ITALIC -> SpanStyle(fontStyle = FontStyle.Italic)
        InlineKind.STRIKE -> SpanStyle(textDecoration = TextDecoration.LineThrough)
        InlineKind.CODE -> SpanStyle(fontFamily = FontFamily.Monospace, background = veil(.2f))
        InlineKind.LINK -> SpanStyle(color = TodoBlue, textDecoration = TextDecoration.Underline)
    }

    /** One line as plain words: list/heading/quote markers and inline markers removed. */
    fun plain(line: String): String {
        val trimmed = line.trim()
        FileLine.find(trimmed)?.let { return "📎 " + it.groupValues[1] }
        if (ImageLine.matches(trimmed) || RuleLine.matches(trimmed) || (TableSeparator.matches(trimmed) && trimmed.contains('-'))) return ""
        if (trimmed.startsWith("|")) return tableRows(listOf(trimmed)).firstOrNull().orEmpty().filter { it.isNotBlank() }.joinToString(" · ") { it.replace("\n", " ") }
        val body = NoteBlocks.classify(line).let { if (it.kind == BlockKind.PARAGRAPH) trimmed else it.text.trim() }
        val hidden = inlineSpans(body).flatMap { span -> span.markers.flatMap { it.toList() } }.toSet()
        return body.filterIndexed { index, _ -> index !in hidden }.trim()
    }

    /** First line with words in it, as plain text, for notes saved without a title. */
    fun firstLine(source: String, max: Int = 60): String? =
        source.lineSequence().map(::plain).firstOrNull { it.isNotBlank() }?.let { if (it.length > max) it.take(max - 1).trimEnd() + "…" else it }

    /** The note as plain lines (no markdown symbols), cut to about [max] characters. */
    fun preview(source: String, max: Int = 200): String =
        source.lineSequence().map(::plain).filter { it.isNotBlank() }.joinToString("\n").let { if (it.length > max) it.take(max - 1).trimEnd() + "…" else it }
}

/** The title to show for a card: a note saved without one is named after its first line (never stored). */
val BoardCard.displayTitle: String
    get() = if (title.isNotBlank() || kind != CardKind.NOTE) title else Markdown.firstLine(details) ?: "Untitled note"

enum class InlineKind { BOLD, ITALIC, STRIKE, CODE, LINK }

/** [whole] covers the markers too; [markers] are the syntax characters ("**", "`", "[", "](url)"). */
data class InlineSpan(val kind: InlineKind, val whole: IntRange, val markers: List<IntRange>)

private val InlineCode = Regex("`[^`\n]+`")
private val InlineLink = Regex("""\[([^\]\n]+)]\(([^)\s]+)\)""")
private val InlineBold = Regex("""\*\*(?=\S)(.+?)(?<=\S)\*\*""")
private val InlineStrike = Regex("""~~(?=\S)(.+?)(?<=\S)~~""")
private val InlineItalic = Regex("""(?<![*\w])\*(?=[^\s*])(.+?)(?<=[^\s*])\*(?![*\w])|(?<![_\w])_(?=[^\s_])(.+?)(?<=[^\s_])_(?![_\w])""")
private val BareUrl = Regex("""https?://[^\s)]+""")

/** The kinds of block the note editor shows; the stored text stays plain markdown. */
enum class BlockKind { PARAGRAPH, HEADING, BULLET, NUMBERED, CHECK, QUOTE, DIVIDER, IMAGE, TABLE, FILE;
    val isText get() = this in setOf(PARAGRAPH, HEADING, BULLET, NUMBERED, CHECK, QUOTE)
    val isList get() = this in setOf(BULLET, NUMBERED, CHECK, QUOTE)
}

/**
 * One editor block. Text blocks serialise as [prefix] + [text] (the prefix is the exact marker that was read, e.g. "## " or "- [x] "),
 * so parse → serialise gives back the same characters. [raw] keeps an image/divider/table line verbatim until it is edited.
 */
data class NoteBlock(
    val id: Long,
    val kind: BlockKind,
    val text: String = "",
    val prefix: String = "",
    val rows: List<List<String>> = emptyList(),
    val raw: String? = null
) {
    val checked get() = kind == BlockKind.CHECK && prefix.contains("[x]", ignoreCase = true)
    val level get() = prefix.count { it == '#' }.coerceIn(1, 3)
}

/** Where the cursor should go after an edit: block id and offset in its text. */
data class BlockFocus(val id: Long, val cursor: Int)

data class BlockEdit(val blocks: List<NoteBlock>, val focus: BlockFocus?)

private val HeadingPrefix = Regex("""^(\s*#+ ?)(.*)$""")
private val CheckPrefix = Regex("""^(\s*[-*] \[[ xX]](?: |$))(.*)$""")
private val BulletPrefix = Regex("""^(\s*(?:[-*] |-$))(.*)$""")
private val NumberPrefix = Regex("""^(\s*)(\d{1,9})([.)] )(.*)$""")
private val QuotePrefix = Regex("""^(\s*> ?)(.*)$""")

object NoteBlocks {
    private val ids = java.util.concurrent.atomic.AtomicLong(1)
    fun newId() = ids.getAndIncrement()

    /** Classifies one line the way [Markdown.parse] reads it (plus numbered, checklist and quote lines). */
    fun classify(line: String): NoteBlock {
        val trimmed = line.trim()
        fun text(kind: BlockKind, m: MatchResult) = NoteBlock(newId(), kind, m.groupValues.last(), m.groupValues[1])
        return when {
            trimmed.isEmpty() -> NoteBlock(newId(), BlockKind.PARAGRAPH, line)
            RuleLine.matches(trimmed) -> NoteBlock(newId(), BlockKind.DIVIDER, raw = line)
            ImageLine.matches(trimmed) -> NoteBlock(newId(), BlockKind.IMAGE, ImageLine.find(trimmed)!!.groupValues[1], raw = line)
            // An attachment keeps its file name in [NoteBlock.text] and its stored path in [NoteBlock.prefix].
            FileLine.matches(trimmed) -> FileLine.find(trimmed)!!.groupValues.let { g -> NoteBlock(newId(), BlockKind.FILE, g[1], g[2], raw = line) }
            HeadingPrefix.matches(line) -> text(BlockKind.HEADING, HeadingPrefix.find(line)!!)
            CheckPrefix.matches(line) -> text(BlockKind.CHECK, CheckPrefix.find(line)!!)
            BulletPrefix.matches(line) -> text(BlockKind.BULLET, BulletPrefix.find(line)!!)
            NumberPrefix.matches(line) -> NumberPrefix.find(line)!!.groupValues.let { g -> NoteBlock(newId(), BlockKind.NUMBERED, g[4], g[1] + g[2] + g[3]) }
            QuotePrefix.matches(line) -> text(BlockKind.QUOTE, QuotePrefix.find(line)!!)
            else -> NoteBlock(newId(), BlockKind.PARAGRAPH, line)
        }
    }

    /** Markdown → blocks. Every line becomes exactly one block (runs of `|` lines become one table), so nothing is dropped. */
    fun parse(source: String): List<NoteBlock> {
        val lines = source.split('\n')
        val blocks = mutableListOf<NoteBlock>()
        var index = 0
        while (index < lines.size) {
            val line = lines[index]
            if (line.trim().startsWith("|")) {
                val run = mutableListOf(line)
                index++
                while (index < lines.size && lines[index].trim().startsWith("|")) run += lines[index++]
                blocks += NoteBlock(newId(), BlockKind.TABLE, rows = Markdown.tableRows(run), raw = run.joinToString("\n"))
            } else {
                blocks += classify(line)
                index++
            }
        }
        return blocks
    }

    fun serialize(blocks: List<NoteBlock>): String = blocks.joinToString("\n") { block ->
        when (block.kind) {
            BlockKind.DIVIDER -> block.raw ?: "---"
            BlockKind.IMAGE -> block.raw ?: "![](${block.text})"
            BlockKind.FILE -> block.raw ?: "[📎 ${block.text}](${block.prefix})"
            BlockKind.TABLE -> block.raw ?: block.rows.let { rows ->
                val width = rows.maxOfOrNull { it.size }?.coerceAtLeast(1) ?: 1
                fun line(cells: List<String>) = "| " + (0 until width).joinToString(" | ") { cells.getOrNull(it).orEmpty().replace("|", "/").replace("\n", "<br>") } + " |"
                (listOf(line(rows.firstOrNull().orEmpty()), "| " + List(width) { "---" }.joinToString(" | ") + " |") + rows.drop(1).map(::line)).joinToString("\n")
            }
            BlockKind.PARAGRAPH -> block.text
            else -> block.prefix + block.text
        }
    }

    /** New text for a text block; a bare "-" marker gains its space once words follow it. */
    fun withText(block: NoteBlock, text: String): NoteBlock =
        if ((block.kind == BlockKind.BULLET || block.kind == BlockKind.CHECK) && text.isNotEmpty() && !block.prefix.endsWith(" ")) block.copy(text = text, prefix = block.prefix + " ")
        else block.copy(text = text)

    /** The marker for the line after [block] when Enter is pressed in it: same kind of list, next number, unticked box. */
    fun continuePrefix(block: NoteBlock): String = when (block.kind) {
        BlockKind.NUMBERED -> NumberPrefix.find(block.prefix + "x")?.groupValues?.let { g -> g[1] + ((g[2].toLongOrNull() ?: 0) + 1) + g[3] } ?: block.prefix
        BlockKind.CHECK -> block.prefix.replace(Regex("""\[[xX]]"""), "[ ]").let { if (it.endsWith(" ")) it else "$it " }
        else -> if (block.prefix.endsWith(" ")) block.prefix else block.prefix + " "
    }

    /**
     * Typing a marker turns the line into that block once the marker's space is typed: "- ", "1. ", "# ", "> ", "- [ ] ",
     * and "[ ] " at the start of a bullet makes it a checklist item.
     */
    fun shortcut(block: NoteBlock): NoteBlock {
        if (!block.kind.isText) return block
        val read = classify(if (block.kind == BlockKind.PARAGRAPH) block.text else block.prefix + block.text)
        return if (read.kind != block.kind && read.kind.isText && read.kind != BlockKind.PARAGRAPH && read.prefix.endsWith(" ")) block.copy(kind = read.kind, prefix = read.prefix, text = read.text) else block
    }

    /** Enter at [cursor] in block [index]: split it, continuing a list; Enter on an empty list item ends the list instead. */
    fun enter(blocks: List<NoteBlock>, index: Int, cursor: Int): BlockEdit {
        val block = blocks[index]
        val before = block.text.substring(0, cursor.coerceIn(0, block.text.length))
        val after = block.text.substring(cursor.coerceIn(0, block.text.length))
        val out = blocks.toMutableList()
        if (block.kind.isList && before.isEmpty() && after.isEmpty()) {
            out[index] = block.copy(kind = BlockKind.PARAGRAPH, prefix = "")
            return BlockEdit(out, BlockFocus(block.id, 0))
        }
        if (block.kind == BlockKind.PARAGRAPH && after.isEmpty() && RuleLine.matches(before.trim())) {
            val next = NoteBlock(newId(), BlockKind.PARAGRAPH)
            out[index] = NoteBlock(block.id, BlockKind.DIVIDER, raw = before)
            out.add(index + 1, next)
            return BlockEdit(out, BlockFocus(next.id, 0))
        }
        val nextKind = if (block.kind.isList) block.kind else BlockKind.PARAGRAPH
        val nextPrefix = if (block.kind.isList) continuePrefix(block) else ""
        if (before.isEmpty() && after.isNotEmpty()) {
            // Enter at the start: open an empty line above and keep the cursor where it was.
            out.add(index, NoteBlock(newId(), nextKind, "", nextPrefix))
            return BlockEdit(out, BlockFocus(block.id, 0))
        }
        val next = NoteBlock(newId(), nextKind, after, nextPrefix)
        out[index] = block.copy(text = before)
        out.add(index + 1, next)
        return BlockEdit(out, BlockFocus(next.id, 0))
    }

    /** Backspace at the very start of block [index]: drop its marker, else join it to the line above (or remove a picture/divider above). */
    fun backspaceAtStart(blocks: List<NoteBlock>, index: Int): BlockEdit? {
        val block = blocks[index]
        val out = blocks.toMutableList()
        if (block.kind != BlockKind.PARAGRAPH) {
            out[index] = block.copy(kind = BlockKind.PARAGRAPH, prefix = "")
            return BlockEdit(out, BlockFocus(block.id, 0))
        }
        val previous = blocks.getOrNull(index - 1) ?: return null
        return when {
            previous.kind.isText -> {
                out[index - 1] = previous.copy(text = previous.text + block.text)
                out.removeAt(index)
                BlockEdit(out, BlockFocus(previous.id, previous.text.length))
            }
            previous.kind == BlockKind.IMAGE || previous.kind == BlockKind.DIVIDER -> {
                out.removeAt(index - 1)
                BlockEdit(out, BlockFocus(block.id, 0))
            }
            block.text.isEmpty() && index < blocks.lastIndex -> { out.removeAt(index); BlockEdit(out, null) }
            else -> null
        }
    }

    /** Pasted text with line breaks: the first line stays in the block, the rest is read as markdown. */
    fun paste(blocks: List<NoteBlock>, index: Int, text: String, cursorInFirst: Int): BlockEdit {
        val lines = text.split('\n')
        val block = blocks[index]
        val after = parse(lines.drop(1).joinToString("\n"))
        val out = blocks.toMutableList()
        out[index] = block.copy(text = lines.first())
        out.addAll(index + 1, after)
        val last = after.lastOrNull()
        return BlockEdit(out, if (last != null && last.kind.isText) BlockFocus(last.id, last.text.length) else BlockFocus(block.id, cursorInFirst))
    }

    /** Inserts [block] after block [index] (or at the end), keeping a text line after it to carry on typing. */
    fun insertAfter(blocks: List<NoteBlock>, index: Int?, block: NoteBlock): BlockEdit {
        val out = blocks.toMutableList()
        val current = index?.let { blocks.getOrNull(it) }
        // An empty line where the cursor is gets replaced rather than left behind.
        val at = when {
            current == null -> out.size
            current.kind == BlockKind.PARAGRAPH && current.text.isEmpty() -> { out.removeAt(index); index }
            else -> index + 1
        }
        out.add(at, block)
        val next = out.getOrNull(at + 1)
        return if (next != null && next.kind.isText) BlockEdit(out, BlockFocus(next.id, 0))
        else NoteBlock(newId(), BlockKind.PARAGRAPH).let { para -> out.add(at + 1, para); BlockEdit(out, BlockFocus(para.id, 0)) }
    }

    /** Turns a text block into [kind] (or back into a paragraph if it already is one). */
    fun toggle(block: NoteBlock, kind: BlockKind): NoteBlock = when {
        !block.kind.isText -> block
        block.kind == kind -> block.copy(kind = BlockKind.PARAGRAPH, prefix = "")
        else -> block.copy(kind = kind, prefix = when (kind) {
            BlockKind.HEADING -> "# "; BlockKind.BULLET -> "- "; BlockKind.NUMBERED -> "1. "; BlockKind.CHECK -> "- [ ] "; BlockKind.QUOTE -> "> "; else -> ""
        })
    }

    /** Always leave a text line at the end so there is somewhere to type after a picture, table or divider. */
    fun withTrailingText(blocks: List<NoteBlock>): List<NoteBlock> =
        if (blocks.lastOrNull()?.kind?.isText == true) blocks else blocks + NoteBlock(newId(), BlockKind.PARAGRAPH)
}

@Composable
fun MarkdownText(source: String, modifier: Modifier = Modifier, maxBlocks: Int = Int.MAX_VALUE, reading: Boolean = false, skipHeading: String? = null) {
    val blocks = remember(source, skipHeading) {
        Markdown.parse(source).let { all ->
            // A note whose body opens with its own title shouldn't show the title twice.
            val first = all.firstOrNull()
            if (skipHeading != null && first is MdBlock.Heading && first.text.trim().equals(skipHeading.trim(), ignoreCase = true)) all.drop(1) else all
        }
    }
    // Reading mode (the Notes list) uses bigger, brighter text so a note reads like a page, not a caption.
    val size = if (reading) 15.sp else 13.sp
    val lineH = if (reading) 22.sp else 18.sp
    val prose = if (reading) TodoText.copy(alpha = .86f) else TodoMuted
    Column(modifier, verticalArrangement = Arrangement.spacedBy(if (reading) 7.dp else 5.dp)) {
        blocks.take(maxBlocks).forEach { block ->
            when (block) {
                is MdBlock.Heading -> Text(
                    Markdown.styled(block.text), color = TodoText, fontWeight = FontWeight.SemiBold,
                    fontSize = if (reading) when (block.level) { 1 -> 20.sp; 2 -> 17.sp; else -> 15.sp } else when (block.level) { 1 -> 19.sp; 2 -> 16.sp; else -> 14.sp },
                    modifier = Modifier.padding(top = 2.dp)
                )
                is MdBlock.Bullet -> {
                    // "- [ ] x" lines are checklist items; the editor reads them the same way.
                    val check = NoteBlocks.classify("- " + block.text).takeIf { it.kind == BlockKind.CHECK }
                    Row {
                        if (check != null) Glyph(if (check.checked) ZenIcon.Checkbox else ZenIcon.Stop, TodoBlue, (size.value + 1).dp, modifier = Modifier.padding(top = if (reading) 3.dp else 2.dp, end = 6.dp))
                        else Glyph(ZenIcon.Bullet, TodoBlue, size.value.dp, modifier = Modifier.padding(top = if (reading) 4.dp else 2.dp, end = 5.dp))
                        Text(
                            Markdown.styled(check?.text ?: block.text), color = if (check?.checked == true) TodoMuted else prose, fontSize = size, lineHeight = lineH,
                            textDecoration = if (check?.checked == true) androidx.compose.ui.text.style.TextDecoration.LineThrough else null
                        )
                    }
                }
                MdBlock.Rule -> HorizontalDivider(color = TodoBorder, modifier = Modifier.padding(vertical = 4.dp))
                is MdBlock.File -> AttachmentChip(block.name, block.path, compact = true, clickable = false)
                is MdBlock.Image -> {
                    val bitmap = rememberSavedBitmap(block.path)
                    if (bitmap != null) {
                        Image(bitmap, null, contentScale = ContentScale.Fit, alignment = Alignment.CenterStart, modifier = Modifier.heightIn(max = 240.dp).clip(RoundedCornerShape(10.dp)))
                    }
                }
                is MdBlock.Table -> Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).border(1.dp, TodoBorder, RoundedCornerShape(8.dp))) {
                    block.rows.forEachIndexed { rowIndex, row ->
                        if (rowIndex > 0) HorizontalDivider(color = TodoBorder)
                        Row(Modifier.fillMaxWidth().then(if (rowIndex == 0) Modifier.background(TodoCardRaised) else Modifier)) {
                            row.forEach { cell ->
                                Text(
                                    Markdown.styled(cell), color = if (rowIndex == 0) TodoText else prose, fontSize = if (reading) 13.sp else 12.sp,
                                    fontWeight = if (rowIndex == 0) FontWeight.SemiBold else FontWeight.Normal,
                                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f).padding(horizontal = 8.dp, vertical = 6.dp)
                                )
                            }
                        }
                    }
                }
                is MdBlock.Paragraph -> {
                    val kindLine = NoteBlocks.classify(block.text)
                    when (kindLine.kind) {
                        BlockKind.NUMBERED -> Row {
                            Text(kindLine.prefix.trim(), color = TodoBlue, fontSize = size, lineHeight = lineH, modifier = Modifier.width(if (reading) 26.dp else 22.dp))
                            Text(Markdown.styled(kindLine.text), color = prose, fontSize = size, lineHeight = lineH)
                        }
                        BlockKind.QUOTE -> Row(Modifier.height(androidx.compose.foundation.layout.IntrinsicSize.Min)) {
                            Box(Modifier.width(3.dp).fillMaxHeight().clip(RoundedCornerShape(2.dp)).background(TodoBlue.copy(alpha = .6f)))
                            Text(Markdown.styled(kindLine.text), color = TodoMuted, fontSize = size, lineHeight = lineH, fontStyle = androidx.compose.ui.text.font.FontStyle.Italic, modifier = Modifier.padding(start = 8.dp))
                        }
                        else -> Text(Markdown.styled(block.text), color = prose, fontSize = size, lineHeight = lineH)
                    }
                }
            }
        }
    }
}

private class DoodleStroke(val color: Color, val points: List<Offset>)

private val DoodleColors = listOf(Color(0xFFEDEAE3), Color(0xFFB4CDBF), Color(0xFFD9BC8C), Color(0xFFDE8E80), Color(0xFF9DB7D0))
private val DoodlePaper = Color(0xFF1C1C1A)

/** Freehand drawing pad; "Done" saves it as a PNG and hands back its path. */
@Composable
fun DoodleDialog(onDismiss: () -> Unit, onSaved: (String) -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val strokes = remember { mutableStateListOf<DoodleStroke>() }
    var current by remember { mutableStateOf<List<Offset>>(emptyList()) }
    var color by remember { mutableStateOf(DoodleColors[0]) }
    var size by remember { mutableStateOf(IntSize.Zero) }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxSize().background(DoodlePaper).navigationBarsPadding().padding(top = 28.dp)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Cancel", color = TodoMuted, fontSize = 16.sp, modifier = Modifier.clickable(onClick = onDismiss).padding(8.dp))
                Spacer(Modifier.weight(1f))
                Text("Undo", color = TodoText, fontSize = 16.sp, modifier = Modifier.clickable { if (strokes.isNotEmpty()) strokes.removeAt(strokes.lastIndex) }.padding(8.dp))
                Text("Clear", color = TodoText, fontSize = 16.sp, modifier = Modifier.clickable { strokes.clear() }.padding(8.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    "Done", color = OnAccent, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.clip(RoundedCornerShape(12.dp)).background(TodoBlue)
                        .clickable(enabled = strokes.isNotEmpty()) { saveDoodle(context, strokes, size)?.let(onSaved) }
                        .padding(horizontal = 16.dp, vertical = 9.dp)
                )
            }
            Canvas(
                Modifier.weight(1f).fillMaxWidth().onSizeChanged { size = it }
                    .pointerInput(color) {
                        detectDragGestures(
                            onDragStart = { current = listOf(it) },
                            onDrag = { change, _ -> change.consume(); current = current + change.position },
                            onDragEnd = { if (current.isNotEmpty()) strokes += DoodleStroke(color, current); current = emptyList() },
                            onDragCancel = { current = emptyList() }
                        )
                    }
            ) {
                val width = 5.dp.toPx()
                strokes.forEach { drawStroke(it.color, it.points, width) }
                if (current.isNotEmpty()) drawStroke(color, current, width)
            }
            Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.Center) {
                DoodleColors.forEach { swatch ->
                    Box(
                        Modifier.padding(horizontal = 9.dp).size(34.dp).clip(CircleShape).background(swatch)
                            .then(if (swatch == color) Modifier.border(3.dp, Color.White.copy(alpha = .9f), CircleShape) else Modifier)
                            .clickable { color = swatch }
                    )
                }
            }
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawStroke(color: Color, points: List<Offset>, width: Float) {
    if (points.size == 1) {
        drawCircle(color, width / 2f, points[0])
        return
    }
    val path = Path().apply {
        moveTo(points[0].x, points[0].y)
        points.drop(1).forEach { lineTo(it.x, it.y) }
    }
    drawPath(path, color, style = Stroke(width, cap = StrokeCap.Round, join = StrokeJoin.Round))
}

private fun saveDoodle(context: Context, strokes: List<DoodleStroke>, size: IntSize): String? = runCatching {
    val bitmap = Bitmap.createBitmap(size.width.coerceAtLeast(1), size.height.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
    val canvas = AndroidCanvas(bitmap)
    canvas.drawColor(DoodlePaper.toArgb())
    val width = 5f * context.resources.displayMetrics.density
    strokes.forEach { stroke ->
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = stroke.color.toArgb(); strokeWidth = width; style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
        }
        if (stroke.points.size == 1) {
            paint.style = Paint.Style.FILL
            canvas.drawCircle(stroke.points[0].x, stroke.points[0].y, width / 2f, paint)
        } else {
            val path = AndroidPath().apply {
                moveTo(stroke.points[0].x, stroke.points[0].y)
                stroke.points.drop(1).forEach { lineTo(it.x, it.y) }
            }
            canvas.drawPath(path, paint)
        }
    }
    val file = File(Saves.imageDir(context), "${UUID.randomUUID()}.img")
    file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    file.absolutePath
}.getOrNull()
