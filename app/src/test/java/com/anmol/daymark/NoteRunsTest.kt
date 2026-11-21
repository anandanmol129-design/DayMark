package com.anmol.daymark

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NoteRunsTest {
    private val typical = listOf(
        "# Groceries\n- milk\n- **eggs**\n  - free range\n\nSome *notes* here\n1. one\n2. two\n- [ ] call mum\n- [x] done\n> quoted",
        "Intro\n![](/data/img/a.img)\n- after picture\n---\n| a | b |\n| --- | --- |\n| 1 | 2 |\ntail",
        "-\n- \n* star item\n3) three\n\n\n",
        "[📎 file.pdf](/data/files/file.pdf)\nplain"
    )

    private fun runOf(blocks: List<NoteBlock>, n: Int = 0) = NoteRuns.runs(blocks)[n]
    private fun runText(blocks: List<NoteBlock>, n: Int = 0) = runOf(blocks, n).let { NoteRuns.text(blocks.subList(it.first, it.last + 1)) }
    private fun md(blocks: List<NoteBlock>) = NoteBlocks.serialize(blocks)

    @Test fun roundTripIsUnchanged() {
        typical.forEach { source ->
            val blocks = NoteBlocks.parse(source)
            assertEquals(source, md(blocks))
            // Feeding each run its own text back changes nothing.
            NoteRuns.runs(blocks).forEach { run -> assertNull(NoteRuns.edit(blocks, run, NoteRuns.text(blocks.subList(run.first, run.last + 1)), 0)) }
        }
    }

    @Test fun runsSplitAroundNonTextBlocks() {
        val blocks = NoteBlocks.parse(typical[1])
        val runs = NoteRuns.runs(blocks)
        assertEquals(3, runs.size) // Intro | - after picture | tail
        assertEquals("Intro", runText(blocks, 0))
        assertEquals("after picture", runText(blocks, 1))
        assertEquals("tail", runText(blocks, 2))
        val first = NoteBlocks.parse(typical[0])
        assertEquals(1, NoteRuns.runs(first).size)
        assertEquals("Groceries\nmilk\n**eggs**\nfree range\n\nSome *notes* here\none\ntwo\ncall mum\ndone\nquoted", runText(first))
    }

    @Test fun typingKeepsKindsAndIsExact() {
        val blocks = NoteBlocks.parse("- milk\n- eggs")
        val r = NoteRuns.edit(blocks, runOf(blocks), "milk\neggsx", 10)!!
        assertTrue(r.exact)
        assertEquals("- milk\n- eggsx", md(r.blocks))
    }

    @Test fun enterContinuesListAndEndsOnEmptyItem() {
        var blocks = NoteBlocks.parse("- milk\n1. one")
        var r = NoteRuns.edit(blocks, runOf(blocks), "milk\n\none", 5)!!
        assertEquals("- milk\n- \n1. one", md(r.blocks))
        blocks = r.blocks
        assertEquals(blocks[1].id, r.focus!!.id)
        // Enter again on the empty item ends the list.
        r = NoteRuns.edit(blocks, runOf(blocks), "milk\n\n\none", 6)!!
        assertEquals("- milk\n\n1. one", md(r.blocks))
        blocks = NoteBlocks.parse("1. one")
        r = NoteRuns.edit(blocks, runOf(blocks), "one\n", 4)!!
        assertEquals("1. one\n2. ", md(r.blocks))
    }

    @Test fun enterSplitsLineBeforeEmptyOne() {
        val blocks = NoteBlocks.parse("- a\n")
        val r = NoteRuns.edit(blocks, runOf(blocks), "a\n\n", 2)!!
        assertEquals("- a\n- \n", md(r.blocks))
    }

    @Test fun enterOnRuleMakesDivider() {
        val typed = NoteBlocks.parse("x\n").let { NoteRuns.edit(it, runOf(it), "x\n---", 5)!! }
        assertTrue(typed.exact) // "---" stays words until Enter
        val blocks = typed.blocks
        val r = NoteRuns.edit(blocks, runOf(blocks), "x\n---\n", 6)!!
        assertEquals("x\n---\n", md(r.blocks))
        assertEquals(BlockKind.DIVIDER, r.blocks[1].kind)
        assertEquals(2, NoteRuns.runs(r.blocks).size)
    }

    @Test fun backspaceAtItemStartDropsMarkerThenJoins() {
        var blocks = NoteBlocks.parse("- milk\n- eggs")
        var r = NoteRuns.edit(blocks, runOf(blocks), "milkeggs", 4)!!
        assertEquals("- milk\neggs", md(r.blocks))
        assertEquals(BlockFocus(blocks[1].id, 0), r.focus)
        blocks = r.blocks
        r = NoteRuns.edit(blocks, runOf(blocks), "milkeggs", 4)!!
        assertEquals("- milkeggs", md(r.blocks))
    }

    @Test fun deleteAcrossItemsKeepsFirstKind() {
        val blocks = NoteBlocks.parse("- milk\n- eggs\n- [ ] jam\ntail")
        // Select "lk\neggs\nj" and delete.
        val r = NoteRuns.edit(blocks, runOf(blocks), "miam\ntail", 2)!!
        assertEquals("- miam\ntail", md(r.blocks))
    }

    @Test fun selectAllDeleteLeavesOneEmptyLine() {
        val blocks = NoteBlocks.parse("# T\n- a\n- b")
        val r = NoteRuns.edit(blocks, runOf(blocks), "", 0)!!
        assertEquals(1, r.blocks.size)
        assertEquals("", r.blocks[0].text)
    }

    @Test fun pasteReadsLaterLinesAsMarkdown() {
        val blocks = NoteBlocks.parse("start")
        val r = NoteRuns.edit(blocks, runOf(blocks), "start\n- a\n- b", 13)!!
        assertEquals("start\n- a\n- b", md(r.blocks))
        assertEquals(BlockKind.BULLET, r.blocks[2].kind)
        assertEquals(BlockFocus(r.blocks[2].id, 1), r.focus)
    }

    @Test fun typedMarkerBecomesBullet() {
        val blocks = NoteBlocks.parse("x\n")
        val r = NoteRuns.edit(blocks, runOf(blocks), "x\n- ", 4)!!
        assertFalse(r.exact)
        assertEquals("x\n- ", md(r.blocks))
        assertEquals(BlockKind.BULLET, r.blocks[1].kind)
        assertEquals(BlockFocus(r.blocks[1].id, 0), r.focus)
    }

    @Test fun toggleAppliesToEverySelectedLine() {
        val blocks = NoteBlocks.parse("a\nb\nc")
        val on = NoteRuns.toggle(blocks, runOf(blocks), 0, 3, BlockKind.BULLET)
        assertEquals("- a\n- b\nc", md(on))
        val numbered = NoteRuns.toggle(blocks, runOf(blocks), 0, 5, BlockKind.NUMBERED)
        assertEquals("1. a\n2. b\n3. c", md(numbered))
        val off = NoteRuns.toggle(on, runOf(on), 0, 3, BlockKind.BULLET)
        assertEquals("a\nb\nc", md(off))
    }

    @Test fun copyKeepsMarkersAcrossLines() {
        val blocks = NoteBlocks.parse("- milk\n- [x] eggs\n1. jam")
        val lines = blocks.subList(0, blocks.size)
        assertEquals("ilk", NoteRuns.markdown(lines, 1, 4))
        assertEquals("- milk\n- [x] eggs\n1. jam", NoteRuns.markdown(lines, 0, NoteRuns.text(lines).length))
        assertEquals("lk\n- [x] eg", NoteRuns.markdown(lines, 2, 7))
        // A pasted copy rebuilds the list.
        val target = NoteBlocks.parse("")
        val pasted = NoteRuns.edit(target, runOf(target), "- milk\n- [x] eggs\n1. jam", 21)!!
        assertEquals("- milk\n- [x] eggs\n1. jam", md(pasted.blocks))
    }
}
