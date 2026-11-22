package com.anmol.daymark

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TranscriptsTest {
    private fun srv3(vararg lines: Pair<Long, String>) = buildString {
        append("<timedtext format=\"3\"><body>")
        lines.forEach { (t, words) ->
            append("<p t=\"$t\" d=\"2000\">")
            var offset = 0
            words.split(' ').forEachIndexed { i, w ->
                if (i == 0) append("<s>$w</s>") else append("<s t=\"$offset\"> $w</s>")
                offset += 300
            }
            append("</p>")
        }
        append("</body></timedtext>")
    }

    @Test fun pausesBecomeSentences() {
        val xml = srv3(0L to "so i went to the shop", 5_000L to "and um they were closed", 10_000L to "what do you think about that")
        val text = Transcripts.format(Transcripts.timedWords(xml)).joinToString(" ")
        assertEquals("So I went to the shop. And they were closed. What do you think about that?", text)
    }

    @Test fun noFullStopAfterFunctionWords() {
        val xml = srv3(0L to "i have been", 5_000L to "absorbing your information all day long")
        val text = Transcripts.format(Transcripts.timedWords(xml)).joinToString(" ")
        assertTrue(text, text.contains("been absorbing"))
    }

    @Test fun sectionRoundTrips() {
        val details = "My notes\n\n## Transcript\n\nFirst paragraph.\n\nSecond paragraph."
        assertEquals("First paragraph.\n\nSecond paragraph.", Transcripts.section(details))
        assertEquals("My notes", Transcripts.withoutTranscript(details))
        assertEquals(details, Transcripts.join(Transcripts.withoutTranscript(details), Transcripts.section(details)))
        assertEquals("## Transcript\n\nOnly.", Transcripts.join("", "Only."))
    }

    @Test fun realCaptionsIfPresent() {
        val file = java.io.File("/tmp/tx/naval.xml")
        if (!file.exists()) return
        val paragraphs = Transcripts.format(Transcripts.timedWords(file.readText()))
        java.io.File("/tmp/tx/naval.txt").writeText(paragraphs.joinToString("\n\n"))
        assertTrue(paragraphs.size > 50)
    }
}
