package com.anmol.daymark

import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.core.content.IntentCompat
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.util.UUID

data class LinkPreview(val title: String?, val imagePath: String?)

/** Everything the Saved folder needs: reading shares, copying images, and fetching link previews. */
object Saves {
    private val UrlPattern = Regex("""https?://[^\s<>"']+""", RegexOption.IGNORE_CASE)
    private const val BrowserAgent = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36"

    fun findUrl(text: String): String? = UrlPattern.find(text)?.value?.trimEnd('.', ',', ')', ']', '!', '?', ';')

    fun domainOf(url: String): String =
        runCatching { URI(url).host.orEmpty().removePrefix("www.") }.getOrDefault("").ifBlank { url }

    fun imageDir(context: Context) = File(context.filesDir, "saves").apply { mkdirs() }

    fun copyImage(context: Context, uri: Uri): String? = runCatching {
        val target = File(imageDir(context), "${UUID.randomUUID()}.img")
        context.contentResolver.openInputStream(uri)?.use { input -> target.outputStream().use { input.copyTo(it) } } ?: return null
        target.absolutePath
    }.getOrNull()

    fun linkCard(columnId: String, url: String, title: String = "", imagePath: String = "") = BoardCard(
        workspace = Workspace.NOTES,
        columnId = columnId,
        kind = CardKind.NOTE,
        title = title.trim().ifBlank { domainOf(url) },
        link = url,
        imagePath = imagePath,
        previewDone = imagePath.isNotBlank()
    )

    fun imageCard(columnId: String, imagePath: String) = BoardCard(
        workspace = Workspace.NOTES,
        columnId = columnId,
        kind = CardKind.NOTE,
        title = "",
        imagePath = imagePath,
        previewDone = true
    )

    /** Turns a share intent into cards in the Saved folder. Returns how many were added. */
    fun saveShared(context: Context, intent: Intent): Int {
        val store = BoardStore(context)
        val snapshot = store.load()
        val column = snapshot.columns.firstOrNull { it.pageId == DEFAULT_SAVED_PAGE_ID } ?: return 0
        val streams = when (intent.action) {
            Intent.ACTION_SEND_MULTIPLE -> IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
            else -> listOfNotNull(IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java))
        }
        val text = intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
        val subject = (intent.getStringExtra(Intent.EXTRA_SUBJECT) ?: intent.getStringExtra(Intent.EXTRA_TITLE)).orEmpty()
        val url = findUrl(text)
        val cards = mutableListOf<BoardCard>()
        val images = streams.mapNotNull { copyImage(context, it) }
        when {
            url != null && images.size <= 1 -> cards += linkCard(column.id, url, subject.ifBlank { text.replace(url, "").trim().lineSequence().firstOrNull().orEmpty() }, images.firstOrNull().orEmpty())
            else -> {
                images.forEach { cards += imageCard(column.id, it) }
                if (url != null) cards += linkCard(column.id, url, subject)
                else if (text.isNotBlank()) {
                    // A short first line reads as a title; a long passage is kept whole as the note body.
                    val first = text.trim().lineSequence().first()
                    val titled = first.length <= 80 && text.trim().lines().size > 1
                    cards += BoardCard(
                        workspace = Workspace.NOTES, columnId = column.id, kind = CardKind.NOTE,
                        title = if (titled) first else "", details = if (titled) text.trim().lines().drop(1).joinToString("\n").trim() else text.trim(), previewDone = true
                    )
                }
            }
        }
        if (cards.isEmpty()) return 0
        store.save(snapshot.copy(cards = cards + snapshot.cards))
        return cards.size
    }

    /** Best-effort page title and preview image; returns null on any network problem. */
    fun fetchPreview(context: Context, url: String): LinkPreview? = runCatching {
        // YouTube serves apps a generic page, so ask its oEmbed endpoint for the real title and thumbnail.
        if (Transcripts.videoId(url) != null) youTubePreview(context, url)?.let { return@runCatching it }
        val connection = open(url)
        val type = connection.contentType.orEmpty()
        if (type.startsWith("image/")) {
            return@runCatching LinkPreview(null, saveImageBytes(context, readLimited(connection.inputStream, IMAGE_LIMIT)))
        }
        val html = String(readLimited(connection.inputStream, HTML_LIMIT), Charsets.UTF_8)
        val title = meta(html, "og:title") ?: meta(html, "twitter:title")
            ?: Regex("<title[^>]*>(.*?)</title>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)).find(html)?.groupValues?.get(1)?.let { decode(it).trim() }?.takeIf { it.isNotBlank() }
        val imageUrl = (meta(html, "og:image") ?: meta(html, "twitter:image"))?.let { runCatching { URL(URL(url), it).toString() }.getOrNull() }
        val imagePath = imageUrl?.let { runCatching { saveImageBytes(context, readLimited(open(it).inputStream, IMAGE_LIMIT)) }.getOrNull() }
        LinkPreview(title, imagePath)
    }.getOrNull()

    private fun youTubePreview(context: Context, url: String): LinkPreview? = runCatching {
        val id = Transcripts.videoId(url) ?: return null
        val json = org.json.JSONObject(String(readLimited(open("https://www.youtube.com/oembed?format=json&url=" + java.net.URLEncoder.encode("https://www.youtube.com/watch?v=$id", "UTF-8")).inputStream, HTML_LIMIT), Charsets.UTF_8))
        val title = json.optString("title").takeIf { it.isNotBlank() }
        val image = json.optString("thumbnail_url").takeIf { it.isNotBlank() }?.let { runCatching { saveImageBytes(context, readLimited(open(it).inputStream, IMAGE_LIMIT)) }.getOrNull() }
        if (title == null && image == null) null else LinkPreview(title, image)
    }.getOrNull()

    private const val HTML_LIMIT = 400_000
    private const val IMAGE_LIMIT = 6_000_000

    private fun open(url: String): HttpURLConnection = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = 6_000
        readTimeout = 6_000
        instanceFollowRedirects = true
        setRequestProperty("User-Agent", BrowserAgent)
        setRequestProperty("Accept-Language", "en")
    }

    private fun readLimited(stream: InputStream, limit: Int): ByteArray = stream.use {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        var total = 0
        while (total < limit) {
            val read = it.read(buffer)
            if (read < 0) break
            out.write(buffer, 0, read)
            total += read
        }
        out.toByteArray()
    }

    private fun saveImageBytes(context: Context, bytes: ByteArray): String? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        return File(imageDir(context), "${UUID.randomUUID()}.img").apply { writeBytes(bytes) }.absolutePath
    }

    private fun meta(html: String, key: String): String? {
        val keyPattern = Regex("""(?:property|name)\s*=\s*["']${Regex.escape(key)}["']""", RegexOption.IGNORE_CASE)
        val contentPattern = Regex("""content\s*=\s*(?:"([^"]*)"|'([^']*)')""", RegexOption.IGNORE_CASE)
        for (tag in Regex("""<meta\s[^>]*>""", RegexOption.IGNORE_CASE).findAll(html)) {
            if (!keyPattern.containsMatchIn(tag.value)) continue
            val match = contentPattern.find(tag.value) ?: continue
            val value = decode(match.groupValues[1].ifEmpty { match.groupValues[2] }).trim()
            if (value.isNotBlank()) return value
        }
        return null
    }

    private fun decode(text: String) = text
        .replace("&amp;", "&").replace("&quot;", "\"").replace("&#39;", "'").replace("&#x27;", "'")
        .replace("&lt;", "<").replace("&gt;", ">").replace("&nbsp;", " ")
}
