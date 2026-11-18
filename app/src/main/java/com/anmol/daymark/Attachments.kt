package com.anmol.daymark

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import java.util.UUID

/** What sort of file something is, for its icon, its label and the storage filters. */
enum class FileKind(val label: String, val icon: ZenIcon) {
    IMAGE("Pictures", ZenIcon.Picture),
    DOCUMENT("Documents", ZenIcon.File),
    AUDIO("Audio", ZenIcon.Music),
    VIDEO("Video", ZenIcon.Video),
    OTHER("Other", ZenIcon.Paperclip)
}

/** A file kept with a note: [name] is what the user sees, [path] is the copy in the app's storage. */
data class Attachment(val name: String, val path: String, val mime: String)

/**
 * Files of any type added to notes. They are copied into the same folder as note pictures,
 * keeping their extension so other apps can open them, and are written into the note as
 * `[📎 name](path)` on a line of their own.
 */
object Attachments {
    private val DocumentExtensions = setOf("pdf", "doc", "docx", "odt", "rtf", "txt", "md", "xls", "xlsx", "ods", "csv", "ppt", "pptx", "odp", "epub", "html", "json", "xml")

    fun mimeOf(name: String): String =
        MimeTypeMap.getSingleton().getMimeTypeFromExtension(extensionOf(name)) ?: "application/octet-stream"

    fun extensionOf(name: String): String = name.substringAfterLast('.', "").lowercase(Locale.ROOT)

    fun kindOf(name: String, mime: String = mimeOf(name)): FileKind = when {
        mime.startsWith("image/") -> FileKind.IMAGE
        mime.startsWith("audio/") -> FileKind.AUDIO
        mime.startsWith("video/") -> FileKind.VIDEO
        mime.startsWith("text/") || mime == "application/pdf" || extensionOf(name) in DocumentExtensions -> FileKind.DOCUMENT
        else -> FileKind.OTHER
    }

    /** Copies a picked file into app storage. Runs on a background thread; returns null if it can't be read. */
    fun copy(context: Context, uri: Uri): Attachment? = runCatching {
        val resolver = context.contentResolver
        val mime = resolver.getType(uri) ?: "application/octet-stream"
        val shown = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }?.takeIf { it.isNotBlank() } ?: uri.lastPathSegment?.substringAfterLast('/') ?: "File"
        // Brackets would break the markdown link the note stores, so they become parentheses.
        val name = shown.replace('[', '(').replace(']', ')').replace("\n", " ").trim()
        val ext = extensionOf(name).ifBlank { MimeTypeMap.getSingleton().getExtensionFromMimeType(mime).orEmpty() }
            .filter { it.isLetterOrDigit() }.take(10)
        val target = File(Saves.imageDir(context), UUID.randomUUID().toString() + if (ext.isBlank()) ".bin" else ".$ext")
        resolver.openInputStream(uri)?.use { input -> target.outputStream().use { input.copyTo(it) } } ?: return null
        Attachment(name, target.absolutePath, mime)
    }.getOrNull()

    /** The markdown line a note stores for an attachment. */
    fun line(attachment: Attachment) = "[📎 ${attachment.name}](${attachment.path})"

    /** Opens a stored file in whichever app handles its type. */
    fun open(context: Context, path: String, name: String) {
        val file = File(path)
        if (!file.isFile) return
        // Stored files have random names; hand the other app a link carrying the real name so it shows "Trip plan.pdf".
        // A hard link costs no space; if the phone won't make one, a copy is used instead.
        val shared = runCatching {
            val dir = File(context.cacheDir, "open").apply { mkdirs() }
            val link = File(dir, name.replace('/', '_').trim().ifBlank { file.name })
            link.delete()
            runCatching { android.system.Os.link(file.absolutePath, link.absolutePath) }.onFailure { file.copyTo(link, overwrite = true) }
            link.takeIf { it.isFile }
        }.getOrNull() ?: file
        val uri = androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.files", shared)
        val view = Intent(Intent.ACTION_VIEW).setDataAndType(uri, mimeOf(name).takeIf { it != "application/octet-stream" } ?: mimeOf(path))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(Intent.createChooser(view, name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }
}

/** A file in a note: an icon for its type, its name and size. Tap to open; [onRemove] adds a remove button. */
@Composable
internal fun AttachmentChip(name: String, path: String, modifier: Modifier = Modifier, compact: Boolean = false, onRemove: (() -> Unit)? = null, clickable: Boolean = true) {
    val context = LocalContext.current
    val size by produceState(-1L, path) { value = withContext(Dispatchers.IO) { File(path).takeIf { it.isFile }?.length() ?: -1L } }
    val kind = Attachments.kindOf(name)
    val missing = size == -1L
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(if (compact) 10.dp else 14.dp)).background(TodoCard)
            .border(1.dp, TodoBorder, RoundedCornerShape(if (compact) 10.dp else 14.dp))
            .then(if (clickable) Modifier.clickable(enabled = !missing) { Attachments.open(context, path, name) } else Modifier)
            .padding(horizontal = if (compact) 10.dp else 12.dp, vertical = if (compact) 7.dp else 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier.size(if (compact) 26.dp else 36.dp).clip(RoundedCornerShape(if (compact) 7.dp else 10.dp)).background(TodoBlue.copy(alpha = .14f)),
            contentAlignment = Alignment.Center
        ) { Glyph(kind.icon, TodoBlue, if (compact) 15.dp else 19.dp) }
        Column(Modifier.weight(1f).padding(start = if (compact) 9.dp else 12.dp)) {
            Text(name, color = TodoText, fontSize = if (compact) 13.sp else 15.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val ext = Attachments.extensionOf(name).uppercase(Locale.ROOT)
            Text(
                when {
                    missing -> "File missing"
                    ext.isBlank() -> formatBytes(size)
                    else -> "$ext · ${formatBytes(size)}"
                },
                color = TodoMuted, fontSize = if (compact) 11.sp else 12.sp, maxLines = 1
            )
        }
        if (onRemove != null) Box(
            Modifier.padding(start = 8.dp).size(30.dp).clip(CircleShape).clickable(onClick = onRemove).semantics { contentDescription = "Remove file" },
            contentAlignment = Alignment.Center
        ) { Glyph(ZenIcon.Close, TodoMuted, 17.dp) }
    }
}
