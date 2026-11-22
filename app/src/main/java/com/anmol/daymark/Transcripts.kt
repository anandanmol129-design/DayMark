package com.anmol.daymark

import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Fetches the captions YouTube already has for a video (uploaded or automatic) and turns them into plain
 * paragraphs with no timestamps, for saving under a "Transcript" heading in the note.
 */
object Transcripts {
    const val HEADING = "## Transcript"
    private const val BrowserAgent = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36"
    private val IdPatterns = listOf(
        Regex("""(?:youtube\.com|youtube-nocookie\.com)/(?:watch\?(?:.*&)?v=|shorts/|live/|embed/|v/)([A-Za-z0-9_-]{11})""", RegexOption.IGNORE_CASE),
        Regex("""youtu\.be/([A-Za-z0-9_-]{11})""", RegexOption.IGNORE_CASE)
    )

    fun videoId(url: String): String? = IdPatterns.firstNotNullOfOrNull { it.find(url)?.groupValues?.get(1) }

    /** Where the transcript heading line starts, or -1. A plain search, since transcripts can be very long. */
    private fun headingAt(details: String): Int {
        var from = 0
        while (true) {
            val at = details.indexOf(HEADING, from)
            if (at < 0) return -1
            val lineStart = at == 0 || details[at - 1] == '\n'
            val end = at + HEADING.length
            val lineEnd = end == details.length || details[end] == '\n' || details[end] == '\r'
            if (lineStart && lineEnd) return at
            from = at + 1
        }
    }

    fun hasTranscript(details: String) = headingAt(details) >= 0

    /** The note body without its transcript section, for short previews. */
    fun withoutTranscript(details: String): String {
        val at = headingAt(details)
        return if (at < 0) details else details.substring(0, at).trimEnd()
    }

    /** Adds the transcript as the last section of [details]. */
    fun append(details: String, paragraphs: List<String>): String = join(details, paragraphs.joinToString("\n\n"))

    /** A note body with [section] (the transcript text, without its heading) after it; a blank section adds nothing. */
    fun join(body: String, section: String): String =
        if (section.isBlank()) body else listOf(body.trimEnd(), HEADING, section.trim()).filter { it.isNotBlank() }.joinToString("\n\n")

    /** The transcript text under the heading, or "" when the note has none. */
    fun section(details: String): String {
        val at = headingAt(details)
        return if (at < 0) "" else details.substring(at + HEADING.length).trim()
    }

    /** Paragraphs of the video's transcript, or null if it has none or the network fails. */
    fun fetch(url: String): List<String>? = runCatching {
        val id = videoId(url) ?: return null
        val tracks = tracksFromPlayer(id) ?: return null
        val track = pick(tracks) ?: return null
        val base = track.baseUrl.replace(Regex("&fmt=[^&]*"), "")
        // The srv3 format times every word, so pauses can stand in for punctuation the automatic captions leave out.
        val timed = runCatching { String(get("$base&fmt=srv3"), Charsets.UTF_8) }.getOrNull()
        timed?.let(::timedWords)?.takeIf { it.size > 5 }?.let { words -> return format(words).takeIf { it.isNotEmpty() } }
        val xml = String(get(base), Charsets.UTF_8)
        paragraphs(snippets(xml)).takeIf { it.isNotEmpty() }
    }.getOrNull()

    internal class Word(val text: String, val ms: Long)

    /** Every word in srv3 captions with the time it is spoken; lines without word timings share their line's time. */
    internal fun timedWords(xml: String): List<Word> {
        val words = mutableListOf<Word>()
        Regex("""<p\b([^>]*)>(.*?)</p>""", RegexOption.DOT_MATCHES_ALL).findAll(xml).forEach { p ->
            val start = attr(p.groupValues[1], "t") ?: return@forEach
            val body = p.groupValues[2]
            val spans = Regex("""<s\b([^>]*)>(.*?)</s>""", RegexOption.DOT_MATCHES_ALL).findAll(body).toList()
            if (spans.isEmpty()) clean(body).split(' ').filter { it.isNotBlank() }.forEach { words += Word(it, start) }
            else spans.forEach { span ->
                val at = start + (attr(span.groupValues[1], "t") ?: 0L)
                clean(span.groupValues[2]).split(' ').filter { it.isNotBlank() }.forEach { words += Word(it, at) }
            }
        }
        return words.filterNot { Tag.matches(it.text) }
    }

    private fun attr(attrs: String, name: String): Long? = Regex("""\b$name="(\d+)"""").find(attrs)?.groupValues?.get(1)?.toLongOrNull()

    private val Fillers = setOf("uh", "um", "uhh", "umm", "uhm", "erm", "hmm", "mm")
    private val QuestionStarts = listOf(
        "do you", "did you", "does it", "does that", "are you", "is it", "is that", "is there", "can you", "can i", "could you", "would you",
        "have you", "should i", "should we", "what do", "what does", "what did", "what is", "what's", "what are", "why do", "why did",
        "why is", "why would", "how do", "how does", "how did", "how is", "how are", "how can", "where do", "where is", "when did", "who is", "who's"
    )

    /** Words a spoken sentence almost never ends on, so a pause after one is a breath, not a full stop. */
    private val NoEnd = ("a an the and or but nor to of in on at by for with from into onto about as than then if when while because since so " +
        "my your his her its our their this these those i i'm i've i'd i'll you're we're they're he's she's it's that's there's what's " +
        "is are was were be been being am do does did have has had will would could should can may might must not no very just really " +
        "also too like kind sort lot bit going gonna wanna gotta get got some any every each more most much many few such own same " +
        "other another which who whom whose where how what why").split(' ').toSet()

    /** Words that carry on the sentence before them, so a sentence never starts with one. */
    private val NoStart = setOf("of", "to", "than", "for", "with", "from", "by", "at", "into", "onto", "per")

    /**
     * Builds sentences and paragraphs from timed words. Captions that already have punctuation keep it; automatic ones
     * get full stops at the speaker's pauses (the gap between words, less the time the word itself takes to say).
     */
    internal fun format(all: List<Word>): List<String> {
        val punctuated = all.count { it.text.last() in ".?!" } > all.size / 60
        val words = if (punctuated) all else all.filterNot { it.text.lowercase().trim(',', '.') in Fillers }
        if (words.isEmpty()) return emptyList()
        val n = words.size
        val pauses = LongArray(n) { i -> if (i + 1 < n) (words[i + 1].ms - words[i].ms - (90 + 55 * words[i].text.length)).coerceAtLeast(0) else Long.MAX_VALUE }
        fun canEnd(i: Int) = words[i].text.lowercase().trim(',') !in NoEnd && (i + 1 >= n || words[i + 1].text.lowercase() !in NoStart)

        val ends = BooleanArray(n)
        if (punctuated) words.indices.forEach { ends[it] = words[it].text.last() in ".?!" }
        else {
            words.indices.forEach { ends[it] = pauses[it] >= 300 && canEnd(it) }
            // A fragment of a word or three only stands alone after a long pause.
            var last = -1
            words.indices.forEach { i -> if (ends[i]) { if (i - last < 4 && pauses[i] < 900) ends[i] = false else last = i } }
            // Long runs with no clear pause are cut at their longest pause.
            fun split(a: Int, b: Int) {
                if (b - a + 1 <= 28) return
                val cut = (a + 5..b - 5).filter(::canEnd).ifEmpty { (a + 5..b - 5).toList() }.maxByOrNull { pauses[it] } ?: return
                ends[cut] = true
                split(a, cut); split(cut + 1, b)
            }
            var from = 0
            words.indices.forEach { i -> if (ends[i] || i == n - 1) { split(from, i); from = i + 1 } }
        }
        ends[n - 1] = true

        val paragraphs = mutableListOf<String>()
        val paragraph = StringBuilder()
        var paragraphWords = 0
        var sentence = mutableListOf<String>()
        words.forEachIndexed { i, word ->
            sentence += word.text
            if (!ends[i]) return@forEachIndexed
            val count = sentence.size
            val text = if (punctuated) sentence.joinToString(" ") else tidy(sentence)
            sentence = mutableListOf()
            if (paragraph.isNotEmpty()) paragraph.append(' ')
            paragraph.append(text)
            paragraphWords += count
            if (paragraphWords >= 70 || (paragraphWords >= 25 && pauses[i] >= 1_100) || i == n - 1) {
                paragraphs += paragraph.toString(); paragraph.clear(); paragraphWords = 0
            }
        }
        return paragraphs.map { it.trimStart('#', '>', '|', '-', '*', ' ').trim() }.filter { it.isNotBlank() }
    }

    /** One unpunctuated sentence: capital first letter, a capital "I", and a full stop or question mark. */
    private fun tidy(words: List<String>): String {
        val fixed = words.map { w ->
            val lower = w.lowercase()
            if (lower == "i" || lower.startsWith("i'")) "I" + w.drop(1) else w
        }.toMutableList()
        fixed[0] = fixed[0].replaceFirstChar { it.uppercaseChar() }
        val opening = fixed.take(2).joinToString(" ").lowercase()
        val mark = if (QuestionStarts.any { opening == it || opening.startsWith("$it ") || fixed[0].lowercase() == it }) "?" else "."
        val last = fixed.last().trimEnd(',', ';', ':')
        fixed[fixed.lastIndex] = if (last.last() in ".?!") last else last + mark
        return fixed.joinToString(" ")
    }

    private class Track(val baseUrl: String, val language: String, val automatic: Boolean)

    /** Asks YouTube's player API, as its Android app does, for the video's caption tracks. */
    private fun tracksFromPlayer(id: String): List<Track>? {
        val page = String(get("https://www.youtube.com/watch?v=$id&hl=en"), Charsets.UTF_8)
        val key = Regex(""""INNERTUBE_API_KEY":\s*"([a-zA-Z0-9_-]+)"""").find(page)?.groupValues?.get(1)
        val fromApi = key?.let { k ->
            runCatching {
                val body = JSONObject()
                    .put("context", JSONObject().put("client", JSONObject().put("clientName", "ANDROID").put("clientVersion", "20.10.38").put("hl", "en")))
                    .put("videoId", id)
                tracks(JSONObject(String(post("https://www.youtube.com/youtubei/v1/player?key=$k", body.toString()), Charsets.UTF_8)))
            }.getOrNull()
        }
        if (!fromApi.isNullOrEmpty()) return fromApi
        // Fall back to the player data embedded in the watch page.
        val start = page.indexOf("ytInitialPlayerResponse = {").takeIf { it >= 0 } ?: return fromApi
        val json = page.substring(page.indexOf('{', start)).let { it.substring(0, matchingBrace(it) + 1) }
        return tracks(JSONObject(json))
    }

    private fun tracks(player: JSONObject): List<Track> {
        val list = player.optJSONObject("captions")?.optJSONObject("playerCaptionsTracklistRenderer")?.optJSONArray("captionTracks") ?: return emptyList()
        return (0 until list.length()).mapNotNull { i ->
            val t = list.getJSONObject(i)
            val base = t.optString("baseUrl").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            Track(base, t.optString("languageCode"), t.optString("kind") == "asr")
        }
    }

    /** Uploaded captions beat automatic ones; English beats other languages; otherwise the first track. */
    private fun pick(tracks: List<Track>): Track? =
        tracks.firstOrNull { !it.automatic && it.language.startsWith("en") }
            ?: tracks.firstOrNull { it.automatic && it.language.startsWith("en") }
            ?: tracks.firstOrNull { !it.automatic } ?: tracks.firstOrNull()

    private fun matchingBrace(text: String): Int {
        var depth = 0
        var inString = false
        var escaped = false
        text.forEachIndexed { i, ch ->
            when {
                escaped -> escaped = false
                ch == '\\' && inString -> escaped = true
                ch == '"' -> inString = !inString
                inString -> Unit
                ch == '{' -> depth++
                ch == '}' -> if (--depth == 0) return i
            }
        }
        error("unterminated")
    }

    /** The caption lines, in order, from either of YouTube's timed-text formats. */
    internal fun snippets(xml: String): List<String> {
        val classic = Regex("""<text\b[^>]*>(.*?)</text>""", RegexOption.DOT_MATCHES_ALL).findAll(xml).map { it.groupValues[1] }.toList()
        val raw = classic.ifEmpty { Regex("""<p\b[^>]*>(.*?)</p>""", RegexOption.DOT_MATCHES_ALL).findAll(xml).map { it.groupValues[1] }.toList() }
        return raw.map { clean(it) }.filter { it.isNotBlank() && !Tag.matches(it) }
    }

    private val Tag = Regex("""^[\[(][^\])]{1,30}[\])]$""")

    private fun clean(text: String): String {
        val stripped = text.replace(Regex("<[^>]+>"), "")
        // Entities can be escaped twice (&amp;#39;), so decode until nothing changes.
        var out = stripped
        repeat(2) { out = decode(out) }
        return out.replace(Regex("\\s+"), " ").trim()
    }

    private fun decode(text: String) = Regex("&(#x[0-9a-fA-F]+|#[0-9]+|amp|lt|gt|quot|apos|nbsp);").replace(text) { m ->
        when (val e = m.groupValues[1]) {
            "amp" -> "&"; "lt" -> "<"; "gt" -> ">"; "quot" -> "\""; "apos" -> "'"; "nbsp" -> " "
            else -> runCatching { String(Character.toChars(if (e.startsWith("#x")) e.drop(2).toInt(16) else e.drop(1).toInt())) }.getOrDefault(m.value)
        }
    }

    /** Joins caption lines into paragraphs of a readable length, preferring to break after a sentence. */
    internal fun paragraphs(snippets: List<String>): List<String> {
        val out = mutableListOf<String>()
        val current = StringBuilder()
        var words = 0
        snippets.forEach { line ->
            if (current.isNotEmpty()) current.append(' ')
            current.append(line)
            words += line.split(' ').size
            val sentenceEnd = line.endsWith('.') || line.endsWith('?') || line.endsWith('!')
            if ((words >= 70 && sentenceEnd) || words >= 130) {
                out += current.toString(); current.clear(); words = 0
            }
        }
        if (current.isNotBlank()) out += current.toString()
        // Keep each paragraph from being read as markdown (a heading, list or table line).
        return out.map { it.trimStart('#', '>', '|', '-', '*', ' ').trim() }.filter { it.isNotBlank() }
    }

    private fun connect(url: String) = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = 8_000
        readTimeout = 10_000
        instanceFollowRedirects = true
        setRequestProperty("User-Agent", BrowserAgent)
        setRequestProperty("Accept-Language", "en")
        // Skips the cookie-consent page some regions get.
        setRequestProperty("Cookie", "CONSENT=YES+1; SOCS=CAI")
    }

    private fun get(url: String): ByteArray = connect(url).let { c -> c.inputStream.use { read(it) } }

    private fun post(url: String, json: String): ByteArray = connect(url).let { c ->
        c.requestMethod = "POST"
        c.doOutput = true
        c.setRequestProperty("Content-Type", "application/json")
        c.outputStream.use { it.write(json.toByteArray()) }
        c.inputStream.use { read(it) }
    }

    private fun read(stream: java.io.InputStream): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (out.size() < 4_000_000) {
            val n = stream.read(buffer)
            if (n < 0) break
            out.write(buffer, 0, n)
        }
        return out.toByteArray()
    }
}
