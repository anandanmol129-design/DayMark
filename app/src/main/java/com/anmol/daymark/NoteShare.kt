package com.anmol.daymark

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.net.Uri
import android.text.Layout
import android.text.SpannableString
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.StrikethroughSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import android.text.style.UnderlineSpan
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Sharing a note: as text on the clipboard, as one tall picture of the whole note (split into a few if it's very
 * long), or as a zip of the note's markdown with every picture and file it holds.
 */
internal object NoteShare {
    private const val DIR = "share" // under the cache dir; listed in file_paths.xml

    /** Whether the note keeps any stored files (cover, pictures, attachments), so a zip is worth offering. */
    fun hasFiles(note: BoardCard): Boolean =
        (note.imagePath.isNotBlank() && File(note.imagePath).isFile) || Markdown.filePaths(note.details).any { File(it).isFile }

    /** The body's lines, minus an opening heading that only repeats the title. */
    fun bodyLines(note: BoardCard): List<String> {
        val lines = note.details.trim().lines()
        val first = lines.firstOrNull()?.trim().orEmpty()
        val repeatsTitle = first.startsWith("#") && note.title.isNotBlank() && first.trimStart('#').trim().equals(note.title.trim(), ignoreCase = true)
        return if (repeatsTitle) lines.drop(1).dropWhile { it.isBlank() } else lines
    }

    /** Title and text as tidy markdown: pictures (which are only paths on this phone) dropped, files named. */
    fun text(note: BoardCard): String {
        val out = mutableListOf<String>()
        if (note.title.isNotBlank()) { out += note.title.trim(); out += "" }
        if (note.link.isNotBlank() && !note.details.contains(note.link)) { out += note.link; out += "" }
        bodyLines(note).forEach { line ->
            val file = Markdown.attachments(line).firstOrNull()
            when {
                Markdown.imagePaths(line).isNotEmpty() -> Unit
                file != null -> out += "📎 " + file.first
                else -> out += line.trimEnd()
            }
        }
        if (note.comment.isNotBlank()) { out += ""; out += note.comment.trim() }
        // No runs of blank lines where pictures were taken out.
        return out.joinToString("\n").replace(Regex("\n{3,}"), "\n\n").trim()
    }

    fun copy(context: Context, note: BoardCard) {
        val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return
        clipboard.setPrimaryClip(ClipData.newPlainText(note.displayTitle, text(note)))
        // Android 13 and later confirm a copy themselves.
        if (android.os.Build.VERSION.SDK_INT < 33) android.widget.Toast.makeText(context, "Copied", android.widget.Toast.LENGTH_SHORT).show()
    }

    private fun freshDir(context: Context) = File(context.cacheDir, DIR).apply { deleteRecursively(); mkdirs() }

    private fun safeName(name: String, fallback: String) =
        name.replace(Regex("""[\\/:*?"<>|\n\r\t]"""), " ").replace(Regex("\\s+"), " ").trim().take(60).ifBlank { fallback }

    private fun uriOf(context: Context, file: File): Uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)

    private fun send(context: Context, files: List<File>, mime: String, title: String) {
        if (files.isEmpty()) return
        val uris = files.map { uriOf(context, it) }
        val intent = if (uris.size == 1) Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris[0])
            else Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
        intent.type = mime
        intent.putExtra(Intent.EXTRA_TITLE, title)
        intent.clipData = ClipData.newUri(context.contentResolver, title, uris[0]).apply { uris.drop(1).forEach { addItem(ClipData.Item(it)) } }
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        runCatching { context.startActivity(Intent.createChooser(intent, title).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    /** Renders the note and opens the share sheet with the picture(s). */
    suspend fun shareImage(context: Context, note: BoardCard): Boolean {
        val palette = SharePalette.current()
        val files = withContext(Dispatchers.Default) {
            runCatching {
                val dir = freshDir(context)
                val name = safeName(note.displayTitle, "Note")
                val pages = NoteImage(note, palette).render()
                pages.mapIndexed { i, bitmap ->
                    val file = File(dir, if (pages.size == 1) "$name.png" else "$name ${i + 1}.png")
                    file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    bitmap.recycle()
                    file
                }
            }.getOrNull()
        } ?: return false
        send(context, files, "image/png", note.displayTitle)
        return true
    }

    /** Zips the note as markdown with its files beside it, and opens the share sheet with the zip. */
    suspend fun shareZip(context: Context, note: BoardCard): Boolean {
        val zip = withContext(Dispatchers.IO) { runCatching { buildZip(context, note) }.getOrNull() } ?: return false
        send(context, listOf(zip), "application/zip", note.displayTitle)
        return true
    }

    private fun imageExtension(path: String): String {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        return when (bounds.outMimeType) {
            "image/png" -> "png"; "image/webp" -> "webp"; "image/gif" -> "gif"; "image/heif", "image/heic" -> "heic"
            else -> Attachments.extensionOf(path).takeIf { it.isNotBlank() && it != "img" } ?: "jpg"
        }
    }

    private fun buildZip(context: Context, note: BoardCard): File {
        val name = safeName(note.displayTitle, "Note")
        val used = HashSet<String>()
        val files = LinkedHashMap<String, File>() // name in the zip -> stored file
        fun add(path: String, wanted: String): String? {
            val file = File(path).takeIf { it.isFile } ?: return null
            val base = wanted.substringBeforeLast('.').ifBlank { "file" }
            val ext = wanted.substringAfterLast('.', "").let { if (it.isBlank()) "" else ".$it" }
            var entry = "files/$base$ext"
            var n = 2
            while (!used.add(entry.lowercase(Locale.ROOT))) entry = "files/$base (${n++})$ext"
            files[entry] = file
            return entry
        }
        var pictures = 0
        val md = mutableListOf<String>()
        if (note.title.isNotBlank()) { md += "# " + note.title.trim(); md += "" }
        if (note.imagePath.isNotBlank()) add(note.imagePath, "cover.${imageExtension(note.imagePath)}")?.let { md += "![Cover](${it.replace(" ", "%20")})"; md += "" }
        if (note.link.isNotBlank() && !note.details.contains(note.link)) { md += note.link; md += "" }
        bodyLines(note).forEach { line ->
            val image = Markdown.imagePaths(line).firstOrNull()
            val file = Markdown.attachments(line).firstOrNull()
            when {
                image != null -> add(image, "picture ${++pictures}.${imageExtension(image)}")?.let { md += "![](${it.replace(" ", "%20")})" }
                file != null -> add(file.second, safeName(file.first, "file"))?.let { md += "[📎 ${file.first}](${it.replace(" ", "%20")})" }
                else -> md += line.trimEnd()
            }
        }
        if (note.comment.isNotBlank()) { md += ""; md += "> " + note.comment.trim().replace("\n", "\n> ") }
        val zip = File(freshDir(context), "$name.zip")
        ZipOutputStream(zip.outputStream().buffered()).use { out ->
            out.putNextEntry(ZipEntry("$name.md"))
            out.write((md.joinToString("\n").trim() + "\n").toByteArray())
            out.closeEntry()
            files.forEach { (entry, file) ->
                out.putNextEntry(ZipEntry(entry))
                file.inputStream().use { it.copyTo(out) }
                out.closeEntry()
            }
        }
        return zip
    }
}

/** The app's colours for the current look, as plain ARGB ints for drawing off the main thread. */
internal data class SharePalette(
    val page: Int, val raised: Int, val border: Int, val text: Int, val prose: Int, val muted: Int, val accent: Int, val accentWash: Int, val codeWash: Int
) {
    companion object {
        fun current() = SharePalette(
            page = SheetSolid.toArgb(), raised = SheetRaised.toArgb(), border = TodoBorder.copy(alpha = TodoBorder.alpha * 2.2f).toArgb(),
            text = TodoText.toArgb(), prose = TodoText.copy(alpha = .88f).compositeOver(SheetSolid).toArgb(), muted = TodoMuted.toArgb(),
            accent = TodoBlue.toArgb(), accentWash = TodoBlue.copy(alpha = .16f).compositeOver(SheetSolid).toArgb(), codeWash = veil(.14f).compositeOver(SheetSolid).toArgb()
        )
    }
}

/**
 * Draws a whole note as a tall picture, phone-width (1080 px) with type sized like the reading view on a phone, so it
 * reads like a long screenshot. Very long notes are cut between blocks into several pictures.
 */
private class NoteImage(private val note: BoardCard, private val p: SharePalette) {
    private val width = 1080
    private val pad = 76f
    private val inner = (width - pad * 2).toInt()
    private val maxPage = 10_000
    private val gap = 26f

    private val regular = Typeface.create(Typeface.DEFAULT, 400, false)
    private val semibold = Typeface.create(Typeface.DEFAULT, 600, false)
    private val bodyPx = 44f

    /** A laid-out piece of the note: its height and how to draw it at a given top. */
    private class Piece(val height: Float, val spaceBefore: Float, val draw: Canvas.(top: Float) -> Unit)

    private fun paint(size: Float, color: Int, face: Typeface = regular) = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = size; this.color = color; typeface = face }

    private fun layout(text: CharSequence, paint: TextPaint, w: Int, spacing: Float = 1.42f, maxLines: Int = Int.MAX_VALUE): StaticLayout =
        StaticLayout.Builder.obtain(text, 0, text.length, paint, w.coerceAtLeast(1))
            .setLineSpacing(0f, spacing).setIncludePad(false).setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setMaxLines(maxLines).setEllipsize(if (maxLines == Int.MAX_VALUE) null else TextUtils.TruncateAt.END)
            .setBreakStrategy(Layout.BREAK_STRATEGY_HIGH_QUALITY).setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NORMAL)
            .build()

    /** Inline markdown (bold, italic, code, links…) as Android spans, styled the way the app shows them. */
    private fun styled(text: String): CharSequence {
        val annotated = Markdown.styled(text)
        if (annotated.spanStyles.isEmpty()) return annotated.text
        val out = SpannableString(annotated.text)
        annotated.spanStyles.forEach { range ->
            val s = range.item
            fun span(what: Any) = out.setSpan(what, range.start, range.end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            if (s.fontWeight != null && s.fontWeight!! >= FontWeight.SemiBold) span(StyleSpan(Typeface.BOLD))
            if (s.fontStyle == FontStyle.Italic) span(StyleSpan(Typeface.ITALIC))
            s.textDecoration?.let { d ->
                if (d.contains(TextDecoration.LineThrough)) span(StrikethroughSpan())
                if (d.contains(TextDecoration.Underline)) span(UnderlineSpan())
            }
            if (s.fontFamily == FontFamily.Monospace) { span(TypefaceSpan("monospace")); span(BackgroundColorSpan(p.codeWash)) }
            if (s.color != androidx.compose.ui.graphics.Color.Unspecified) span(ForegroundColorSpan(p.accent))
        }
        return out
    }

    private fun Canvas.icon(icon: ZenIcon, x: Float, y: Float, size: Float, color: Int, stroke: Float = 1.9f) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color; style = if (icon.filled) Paint.Style.FILL_AND_STROKE else Paint.Style.STROKE
            strokeWidth = stroke; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
        }
        save(); translate(x, y); scale(size / 24f, size / 24f)
        icon.paths.forEach { d -> runCatching { androidx.core.graphics.PathParser.createPathFromPathData(d) }.getOrNull()?.let { drawPath(it, paint) } }
        restore()
    }

    private fun textPiece(layout: StaticLayout, x: Float = pad, before: Float = gap, marker: (Canvas.(Float) -> Unit)? = null) =
        Piece(layout.height.toFloat(), before) { top -> marker?.invoke(this, top); save(); translate(x, top); layout.draw(this); restore() }

    /** Decodes a stored picture no wider than [maxW], or null if it can't be read. */
    private fun decode(path: String, maxW: Int): Bitmap? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= maxW) sample *= 2
        BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
    }.getOrNull()

    private fun Canvas.rounded(bitmap: Bitmap, dst: RectF, radius: Float, crop: Boolean) {
        val src = if (crop) {
            // Centre-crop to the destination's shape.
            val want = dst.width() / dst.height()
            val have = bitmap.width.toFloat() / bitmap.height
            if (have > want) { val w = bitmap.height * want; android.graphics.Rect(((bitmap.width - w) / 2).toInt(), 0, ((bitmap.width + w) / 2).toInt(), bitmap.height) }
            else { val h = bitmap.width / want; android.graphics.Rect(0, ((bitmap.height - h) / 2).toInt(), bitmap.width, ((bitmap.height + h) / 2).toInt()) }
        } else null
        save()
        if (radius > 0f) clipPath(Path().apply { addRoundRect(dst, radius, radius, Path.Direction.CW) })
        drawBitmap(bitmap, src, dst, Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
        restore()
    }

    private fun pieces(): List<Piece> {
        val out = mutableListOf<Piece>()
        val body = paint(bodyPx, p.prose)

        // Cover edge to edge, like the editor.
        if (note.imagePath.isNotBlank()) decode(note.imagePath, width)?.let { cover ->
            val h = (width * .56f)
            out += Piece(h, 0f) { top -> rounded(cover, RectF(0f, top, width.toFloat(), top + h), 0f, crop = true) }
        }
        if (note.title.isNotBlank()) out += textPiece(layout(note.title.trim(), paint(70f, p.text, semibold), inner, 1.22f), before = if (out.isEmpty()) pad else 56f)
        val date = java.time.Instant.ofEpochMilli(note.createdAt).atZone(java.time.ZoneId.systemDefault()).toLocalDate()
            .format(java.time.format.DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.getDefault()))
        out += textPiece(layout(date, paint(34f, p.muted), inner), before = if (out.isEmpty()) pad else 14f)
        if (note.link.isNotBlank()) out += textPiece(layout(note.link, paint(38f, p.accent), inner, maxLines = 3))

        var first = true
        Markdown.parse(NoteShare.bodyLines(note).joinToString("\n")).forEach { block ->
            val before = if (first) 44f else gap
            first = false
            when (block) {
                is MdBlock.Heading -> {
                    val size = when (block.level) { 1 -> 58f; 2 -> 51f; else -> 46f }
                    out += textPiece(layout(styled(block.text), paint(size, p.text, semibold), inner, 1.25f), before = before + 14f)
                }
                is MdBlock.Bullet -> {
                    val check = NoteBlocks.classify("- " + block.text).takeIf { it.kind == BlockKind.CHECK }
                    val indent = 62f
                    val words = paint(bodyPx, if (check?.checked == true) p.muted else p.prose)
                    val text = styled(check?.text ?: block.text).let { t ->
                        if (check?.checked == true) SpannableString(t).apply { setSpan(StrikethroughSpan(), 0, length, 0) } else t
                    }
                    val l = layout(text, words, inner - indent.toInt())
                    val firstLine = l.getLineBottom(0).toFloat()
                    out += textPiece(l, pad + indent, before) { top ->
                        val s = if (check != null) 46f else 40f
                        icon(if (check == null) ZenIcon.Bullet else if (check.checked) ZenIcon.Checkbox else ZenIcon.Stop, pad - 2f, top + (firstLine - s) / 2f, s, p.accent, if (check != null) 2.1f else 1.9f)
                    }
                }
                is MdBlock.Paragraph -> {
                    val line = NoteBlocks.classify(block.text)
                    when (line.kind) {
                        BlockKind.NUMBERED -> {
                            val indent = 70f
                            val l = layout(styled(line.text), body, inner - indent.toInt())
                            val number = layout(line.prefix.trim(), paint(bodyPx, p.accent), indent.toInt())
                            out += textPiece(l, pad + indent, before) { top -> save(); translate(pad, top); number.draw(this); restore() }
                        }
                        BlockKind.QUOTE -> {
                            val l = layout(styled(line.text), paint(bodyPx, p.muted, Typeface.create(regular, Typeface.ITALIC)), inner - 34)
                            val bar = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = p.accent; alpha = 150 }
                            out += textPiece(l, pad + 34f, before) { top -> drawRoundRect(RectF(pad, top, pad + 7f, top + l.height), 4f, 4f, bar) }
                        }
                        else -> out += textPiece(layout(styled(block.text), body, inner), before = before)
                    }
                }
                MdBlock.Rule -> out += Piece(4f, before + 10f) { top -> drawRect(pad, top, width - pad, top + 2.5f, Paint().apply { color = p.border }) }
                is MdBlock.Image -> decode(block.path, inner)?.let { bitmap ->
                    // Full width, but never taller than about two screens' worth of a phone picture.
                    var w = inner.toFloat(); var h = w * bitmap.height / bitmap.width
                    val maxH = 1500f
                    if (h > maxH) { w = w * maxH / h; h = maxH }
                    out += Piece(h, before + 6f) { top -> rounded(bitmap, RectF(pad, top, pad + w, top + h), 26f, crop = false) }
                }
                is MdBlock.File -> {
                    val kind = Attachments.kindOf(block.name)
                    val size = File(block.path).takeIf { it.isFile }?.length()
                    val ext = Attachments.extensionOf(block.name).uppercase(Locale.ROOT)
                    val name = layout(block.name, paint(40f, p.text, semibold), inner - 170, 1.2f, maxLines = 1)
                    val meta = layout(if (size == null) "File missing" else listOf(ext, formatBytes(size)).filter { it.isNotBlank() }.joinToString(" · "), paint(32f, p.muted), inner - 170, maxLines = 1)
                    val h = 132f
                    out += Piece(h, before) { top ->
                        val box = RectF(pad, top, width - pad, top + h)
                        drawRoundRect(box, 34f, 34f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = p.raised })
                        drawRoundRect(box, 34f, 34f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = p.border; style = Paint.Style.STROKE; strokeWidth = 2.5f })
                        val tile = RectF(pad + 26f, top + 22f, pad + 114f, top + 110f)
                        drawRoundRect(tile, 26f, 26f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = p.accentWash })
                        icon(kind.icon, tile.left + 22f, tile.top + 22f, 44f, p.accent, 1.8f)
                        val textTop = top + (h - name.height - meta.height - 6f) / 2f
                        save(); translate(pad + 140f, textTop); name.draw(this); translate(0f, name.height + 6f); meta.draw(this); restore()
                    }
                }
                is MdBlock.Table -> {
                    val cols = block.rows.maxOfOrNull { it.size }?.coerceAtLeast(1) ?: 1
                    val cellW = inner / cols
                    val cellPad = 22f
                    val rows = block.rows.mapIndexed { r, row ->
                        (0 until cols).map { c ->
                            layout(styled(row.getOrNull(c).orEmpty()), paint(38f, if (r == 0) p.text else p.prose, if (r == 0) semibold else regular), (cellW - cellPad * 2).toInt(), 1.3f)
                        }
                    }
                    val heights = rows.map { cells -> (cells.maxOfOrNull { it.height } ?: 0) + cellPad * 2 }
                    out += Piece(heights.sum(), before + 6f) { top ->
                        val box = RectF(pad, top, pad + cellW * cols, top + heights.sum())
                        val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = p.border; style = Paint.Style.STROKE; strokeWidth = 2.5f }
                        save(); clipPath(Path().apply { addRoundRect(box, 22f, 22f, Path.Direction.CW) })
                        drawRect(pad, top, box.right, top + heights[0], Paint().apply { color = p.raised })
                        var y = top
                        rows.forEachIndexed { r, cells ->
                            if (r > 0) drawLine(pad, y, box.right, y, line)
                            cells.forEachIndexed { c, cell -> save(); translate(pad + c * cellW + cellPad, y + cellPad); cell.draw(this); restore() }
                            y += heights[r]
                        }
                        (1 until cols).forEach { c -> drawLine(pad + c * cellW, top, pad + c * cellW, box.bottom, line) }
                        restore()
                        drawRoundRect(box, 22f, 22f, line)
                    }
                }
            }
        }
        if (note.comment.isNotBlank()) {
            val l = layout(styled(note.comment.trim()), paint(bodyPx, p.text), inner - 68)
            out += Piece(l.height + 68f, 40f) { top ->
                drawRoundRect(RectF(pad, top, width - pad, top + l.height + 68f), 30f, 30f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = p.accentWash })
                save(); translate(pad + 34f, top + 34f); l.draw(this); restore()
            }
        }
        return out
    }

    /** One bitmap per page; most notes fit on one. */
    fun render(): List<Bitmap> {
        val all = pieces()
        val pages = mutableListOf<MutableList<Piece>>(mutableListOf())
        var used = 0f
        all.forEach { piece ->
            val need = piece.spaceBefore + piece.height
            if (pages.last().isNotEmpty() && used + need + pad > maxPage) { pages += mutableListOf<Piece>(); used = 0f }
            used += if (pages.last().isEmpty() && pages.size > 1) pad + piece.height else need
            pages.last() += piece
        }
        return pages.mapIndexed { index, list ->
            // A continued page starts with the page margin instead of the gap the piece had after the one before it.
            val starts = list.mapIndexed { i, piece -> if (i == 0 && index > 0) pad else piece.spaceBefore }
            val height = (starts.zip(list).sumOf { (s, piece) -> (s + piece.height).toDouble() } + pad + 10).toInt().coerceIn(1, maxPage + 2000)
            Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { bitmap ->
                val canvas = Canvas(bitmap)
                canvas.drawColor(p.page)
                var y = 0f
                list.forEachIndexed { i, piece -> y += starts[i]; piece.draw(canvas, y); y += piece.height }
            }
        }
    }
}

private enum class ShareWay { IMAGE, ZIP }

/** The share choices for a note, as a short sheet of large rows: copy, picture, and zip when it holds files. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun NoteShareSheet(note: BoardCard, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf<ShareWay?>(null) }
    var failed by remember { mutableStateOf(false) }
    val withFiles by produceState(false, note) { value = withContext(Dispatchers.IO) { NoteShare.hasFiles(note) } }
    fun run(way: ShareWay) {
        if (busy != null) return
        busy = way; failed = false
        scope.launch {
            val ok = if (way == ShareWay.IMAGE) NoteShare.shareImage(context, note) else NoteShare.shareZip(context, note)
            busy = null
            if (ok) onDismiss() else failed = true
        }
    }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = GlassSheet.copy(alpha = 1f),
        dragHandle = null,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
    ) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(start = 18.dp, end = 18.dp, top = 22.dp, bottom = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("SHARE", color = TodoBlue, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.4.sp, modifier = Modifier.padding(start = 4.dp))
            Text(note.displayTitle, color = TodoText, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 4.dp, bottom = 6.dp))
            ShareRow(ZenIcon.Note, "Copy to clipboard", "Title and text, ready to paste", false) { NoteShare.copy(context, note); onDismiss() }
            ShareRow(ZenIcon.Picture, "Share as image", "The whole note as one long picture", busy == ShareWay.IMAGE) { run(ShareWay.IMAGE) }
            if (withFiles) ShareRow(ZenIcon.Paperclip, "Share as zip", "The note with all its pictures and files", busy == ShareWay.ZIP) { run(ShareWay.ZIP) }
            if (failed) Text("Couldn’t prepare that. Try again.", color = Danger, fontSize = 13.sp, modifier = Modifier.padding(start = 4.dp))
        }
    }
}

@Composable
private fun ShareRow(icon: ZenIcon, label: String, hint: String, busy: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(20.dp)
    Row(
        Modifier.fillMaxWidth().clip(shape).background(TodoCard).border(1.dp, TodoBorder, shape)
            .clickable(onClick = onClick).semantics { contentDescription = label }.padding(horizontal = 14.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(44.dp).clip(CircleShape).background(TodoBlue.copy(alpha = .14f)), contentAlignment = Alignment.Center) {
            if (busy) CircularProgressIndicator(color = TodoBlue, strokeWidth = 2.dp, modifier = Modifier.size(22.dp)) else Glyph(icon, TodoBlue, 21.dp)
        }
        Column(Modifier.weight(1f).padding(start = 14.dp)) {
            Text(label, color = TodoText, fontSize = 16.sp, fontWeight = FontWeight.Medium)
            Text(if (busy) "Preparing…" else hint, color = TodoMuted, fontSize = 13.sp, modifier = Modifier.padding(top = 1.dp))
        }
        Glyph(ZenIcon.ChevronRight, TodoMuted, 18.dp)
    }
}
