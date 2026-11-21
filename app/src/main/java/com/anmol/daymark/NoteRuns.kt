package com.anmol.daymark

/**
 * The note editor types into one text field per *run*: a stretch of consecutive text blocks (paragraphs, headings,
 * list items, checklist items, quotes). The field holds only the words of each line, joined with line breaks; each
 * line's kind and marker stay on its [NoteBlock], so selection, Select all, copy and cut work across list items while
 * the stored markdown is exactly what [NoteBlocks.serialize] gives for the blocks. Pictures, tables, dividers and
 * files sit between runs as their own items.
 *
 * Everything here is plain logic on block lists, so it can be unit tested.
 */
internal object NoteRuns {
    /** The note split into editor items: each range is either a run of text blocks or one other block. */
    fun group(blocks: List<NoteBlock>): List<IntRange> {
        val out = mutableListOf<IntRange>()
        var i = 0
        while (i < blocks.size) {
            if (!blocks[i].kind.isText) { out += i..i; i++; continue }
            val start = i
            while (i < blocks.size && blocks[i].kind.isText) i++
            out += start until i
        }
        return out
    }

    /** Only the text runs, in order; a run's position in this list is its key in the editor. */
    fun runs(blocks: List<NoteBlock>): List<IntRange> = group(blocks).filter { blocks[it.first].kind.isText }

    fun text(lines: List<NoteBlock>): String = lines.joinToString("\n") { it.text }

    /** Where each line starts in the run's text. */
    fun lineStarts(lines: List<NoteBlock>): IntArray = startsOf(lines.map { it.text.length })

    private fun startsOf(lengths: List<Int>): IntArray {
        val starts = IntArray(lengths.size)
        for (i in 1 until lengths.size) starts[i] = starts[i - 1] + lengths[i - 1] + 1
        return starts
    }

    /** The line an offset falls in; the end of a line (just before its break) belongs to that line. */
    fun lineAt(starts: IntArray, offset: Int): Int {
        var line = 0
        while (line + 1 < starts.size && starts[line + 1] <= offset) line++
        return line
    }

    /** The run (index into [runs]) holding block [id], with that block's start offset in the run's text. */
    fun locate(blocks: List<NoteBlock>, id: Long): Pair<Int, Int>? {
        runs(blocks).forEachIndexed { ordinal, run ->
            val lines = blocks.subList(run.first, run.last + 1)
            val line = lines.indexOfFirst { it.id == id }
            if (line >= 0) return ordinal to lineStarts(lines)[line]
        }
        return null
    }

    /** The block a run's offset falls in. */
    fun blockAt(blocks: List<NoteBlock>, run: IntRange, offset: Int): NoteBlock {
        val lines = blocks.subList(run.first, run.last + 1)
        return lines[lineAt(lineStarts(lines), offset)]
    }

    /**
     * [blocks] after the run's text became [newText]. [exact] means the run's text is now exactly [newText], so the
     * field's own value (cursor, IME composition) can be kept; otherwise the cursor goes to [focus].
     */
    data class Result(val blocks: List<NoteBlock>, val focus: BlockFocus?, val exact: Boolean)

    /** Applies a change to run [run]'s text. Returns null when the text did not change. */
    fun edit(blocks: List<NoteBlock>, run: IntRange, newText: String, newCursor: Int): Result? {
        val lines = blocks.subList(run.first, run.last + 1)
        val old = text(lines)
        if (old == newText) return null
        val cursor = newCursor.coerceIn(0, newText.length)
        // The changed stretch: what is left once the common start and end are taken off. The common end may not reach
        // left of the new cursor, so Enter at the end of a line before an empty one splits that line, not the empty one.
        val maxSuffix = minOf(old.length, newText.length - cursor)
        var s = 0
        while (s < maxSuffix && old[old.length - 1 - s] == newText[newText.length - 1 - s]) s++
        val maxPrefix = minOf(old.length, newText.length) - s
        var p = 0
        while (p < maxPrefix && old[p] == newText[p]) p++
        val end = old.length - s
        val deleted = old.substring(p, end)
        val inserted = newText.substring(p, newText.length - s)
        val starts = lineStarts(lines)
        val a = lineAt(starts, p)
        val b = lineAt(starts, end)

        // Backspace at the start of a list item, heading or quote drops its marker first; the next backspace joins lines.
        if (deleted == "\n" && inserted.isEmpty() && lines[b].kind != BlockKind.PARAGRAPH) {
            val out = blocks.toMutableList()
            out[run.first + b] = lines[b].copy(kind = BlockKind.PARAGRAPH, prefix = "")
            return Result(out, BlockFocus(lines[b].id, 0), exact = false)
        }
        // Enter: split the line, carrying a list on (see NoteBlocks.enter). Over a selection, the selection goes first.
        if (inserted == "\n") {
            val base = if (deleted.isEmpty()) blocks else general(blocks, run, lines, old.removeRange(p, end), p, a, b).blocks
            val edit = NoteBlocks.enter(base, run.first + a, p - starts[a])
            return Result(edit.blocks, edit.focus, exact = false)
        }
        return general(blocks, run, lines, newText, cursor, a, b)
    }

    /**
     * Any other change: lines [a]..[b] are replaced by the matching lines of [newText]. The first keeps line [a]'s kind
     * (so deleting across items leaves the first item's kind, as in most editors); pasted lines after it are read as markdown.
     */
    private fun general(blocks: List<NoteBlock>, run: IntRange, lines: List<NoteBlock>, newText: String, cursor: Int, a: Int, b: Int): Result {
        val newLines = newText.split('\n')
        val after = lines.size - 1 - b
        val midCount = newLines.size - a - after
        val mid = newLines.subList(a, a + midCount)
        val first = NoteBlocks.shortcut(NoteBlocks.withText(lines[a], mid[0]))
        val rest = if (midCount > 1) NoteBlocks.parse(mid.drop(1).joinToString("\n")) else emptyList()
        val newRun = lines.take(a) + first + rest + lines.takeLast(after)
        val out = blocks.take(run.first) + newRun + blocks.drop(run.last + 1)
        if (newRun.all { it.kind.isText } && text(newRun) == newText) return Result(out, BlockFocus(blockIdAt(newRun, cursor), 0), exact = true)

        // Something was read differently (a typed "- " became a bullet, pasted lines became a list or table): put the
        // cursor where it was in the words, less any marker characters that were taken out.
        val nStarts = startsOf(newLines.map { it.length })
        val line = lineAt(nStarts, cursor)
        val offset = cursor - nStarts[line]
        fun stripped(raw: String, block: NoteBlock) = (offset - (raw.length - block.text.length)).coerceIn(0, block.text.length)
        val focus = when {
            line < a -> BlockFocus(lines[line].id, offset)
            line >= a + midCount -> BlockFocus(lines[b + 1 + (line - a - midCount)].id, offset)
            line == a -> BlockFocus(first.id, stripped(mid[0], first))
            rest.size == midCount - 1 && rest[line - a - 1].kind.isText -> rest[line - a - 1].let { BlockFocus(it.id, stripped(mid[line - a], it)) }
            else -> (rest.lastOrNull { it.kind.isText } ?: first).let { BlockFocus(it.id, it.text.length) }
        }
        return Result(out, focus, exact = false)
    }

    private fun blockIdAt(lines: List<NoteBlock>, offset: Int) = lines[lineAt(lineStarts(lines), offset)].id

    /**
     * The toolbar's list/heading/quote buttons on every line the selection touches: if they all already are [kind]
     * they become paragraphs, otherwise they all become [kind] (numbered lines are numbered 1, 2, 3…).
     */
    fun toggle(blocks: List<NoteBlock>, run: IntRange, selectionStart: Int, selectionEnd: Int, kind: BlockKind): List<NoteBlock> {
        val lines = blocks.subList(run.first, run.last + 1)
        val starts = lineStarts(lines)
        val a = lineAt(starts, minOf(selectionStart, selectionEnd))
        val b = lineAt(starts, maxOf(selectionStart, selectionEnd))
        val off = (a..b).all { lines[it].kind == kind }
        val out = blocks.toMutableList()
        (a..b).forEachIndexed { n, i ->
            val line = lines[i]
            out[run.first + i] = when {
                off -> NoteBlocks.toggle(line, kind)
                kind == BlockKind.NUMBERED && b > a -> line.copy(kind = kind, prefix = line.prefix.takeWhile { it == ' ' || it == '\t' } + "${n + 1}. ")
                line.kind == kind -> line
                else -> NoteBlocks.toggle(line, kind)
            }
        }
        return out
    }

    /**
     * What Copy/Cut put on the clipboard for a selection in a run: the selected words, and when the selection spans
     * lines, each whole line keeps its markdown marker ("- ", "1. ", "- [ ] "…) so pasting it rebuilds the list.
     */
    fun markdown(lines: List<NoteBlock>, selectionStart: Int, selectionEnd: Int): String {
        val text = text(lines)
        val start = minOf(selectionStart, selectionEnd).coerceIn(0, text.length)
        val end = maxOf(selectionStart, selectionEnd).coerceIn(0, text.length)
        val starts = lineStarts(lines)
        val a = lineAt(starts, start)
        val b = lineAt(starts, end)
        if (a == b) return text.substring(start, end)
        val out = mutableListOf<String>()
        out += if (start == starts[a]) NoteBlocks.serialize(listOf(lines[a])) else lines[a].text.substring(start - starts[a])
        for (i in a + 1 until b) out += NoteBlocks.serialize(listOf(lines[i]))
        out += if (end == starts[b]) "" else NoteBlocks.serialize(listOf(lines[b].copy(text = lines[b].text.substring(0, end - starts[b]))))
        return out.joinToString("\n")
    }
}

/** Sets every line the selection touches in [run] to heading [level] (1–3), or plain text for 0; already that level → plain text. */
internal fun NoteRuns.heading(blocks: List<NoteBlock>, run: IntRange, selectionStart: Int, selectionEnd: Int, level: Int): List<NoteBlock> {
    val lines = blocks.subList(run.first, run.last + 1)
    val starts = lineStarts(lines)
    val a = lineAt(starts, minOf(selectionStart, selectionEnd))
    val b = lineAt(starts, maxOf(selectionStart, selectionEnd))
    val off = level == 0 || (a..b).all { lines[it].kind == BlockKind.HEADING && lines[it].level == level }
    val out = blocks.toMutableList()
    for (i in a..b) {
        val line = lines[i]
        if (!line.kind.isText) continue
        out[run.first + i] = if (off) line.copy(kind = BlockKind.PARAGRAPH, prefix = "") else line.copy(kind = BlockKind.HEADING, prefix = "#".repeat(level) + " ")
    }
    return out
}
