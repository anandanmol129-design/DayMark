package com.anmol.daymark

import android.content.Context
import android.net.Uri
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

data class BackupSummary(val cards: Int, val images: Int)

/**
 * Backups are a zip with `daymark.json` (format marker + snapshot) and an `images/` folder.
 * Image paths are stored relative to a placeholder so a backup restores on any device.
 */
object Backup {
    private const val MANIFEST = "daymark.json"
    private const val IMAGES = "images/"
    private const val IMAGE_REF = "daymark-img://"
    private const val FORMAT = 1
    private const val PREFS = "daymark_settings"

    fun fileName(): String = "daymark-backup-${java.time.LocalDate.now()}.zip"

    fun lastBackupMillis(context: Context): Long =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong("last_backup", 0L)

    fun export(context: Context, snapshot: BoardSnapshot, target: Uri): BackupSummary {
        val dir = Saves.imageDir(context)
        val portable = snapshot.copy(cards = snapshot.cards.map { it.withImagePaths(dir.absolutePath + "/", IMAGE_REF) })
        val images = referencedImages(snapshot).map(::File).filter { it.isFile && it.parentFile == dir }
        val manifest = JSONObject().apply {
            put("format", FORMAT)
            put("createdAt", System.currentTimeMillis())
            put("snapshot", JSONObject(BoardStore(context).encode(portable)))
        }
        val stream = context.contentResolver.openOutputStream(target, "wt") ?: error("Couldn’t open the backup file")
        ZipOutputStream(stream.buffered()).use { zip ->
            zip.putNextEntry(ZipEntry(MANIFEST))
            zip.write(manifest.toString().toByteArray())
            zip.closeEntry()
            images.forEach { file ->
                zip.putNextEntry(ZipEntry(IMAGES + file.name))
                file.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putLong("last_backup", System.currentTimeMillis()).apply()
        return BackupSummary(snapshot.cards.size, images.size)
    }

    /**
     * Reads a backup and copies its images into place. Nothing is written until the
     * manifest has been parsed, so a bad file leaves the current data untouched.
     */
    fun read(context: Context, source: Uri): BoardSnapshot {
        val staging = File(context.cacheDir, "restore").apply { deleteRecursively(); mkdirs() }
        try {
            var raw: String? = null
            val input = context.contentResolver.openInputStream(source) ?: error("Couldn’t open the backup file")
            ZipInputStream(input.buffered()).use { zip ->
                generateSequence { zip.nextEntry }.forEach { entry ->
                    when {
                        entry.isDirectory -> Unit
                        entry.name == MANIFEST -> raw = zip.readBytes().toString(Charsets.UTF_8)
                        // Only take the bare file name so entries can't escape the folder.
                        entry.name.startsWith(IMAGES) -> File(entry.name).name.takeIf { it.isNotBlank() }?.let { name ->
                            File(staging, name).outputStream().use { zip.copyTo(it) }
                        }
                    }
                }
            }
            val manifest = JSONObject(raw ?: error("This isn’t a Daymark backup"))
            if (manifest.optInt("format") != FORMAT) error("This backup was made by a newer version of Daymark")
            val dir = Saves.imageDir(context)
            val parsed = BoardStore(context).parse(manifest.getJSONObject("snapshot").toString())
            val restored = parsed.copy(cards = parsed.cards.map { it.withImagePaths(IMAGE_REF, dir.absolutePath + "/") })
            staging.listFiles()?.forEach { it.copyTo(File(dir, it.name), overwrite = true) }
            return restored
        } finally {
            staging.deleteRecursively()
        }
    }

    /** Removes saved images that the current snapshot no longer points to. */
    fun deleteUnusedImages(context: Context, snapshot: BoardSnapshot) {
        val used = referencedImages(snapshot).toSet()
        Saves.imageDir(context).listFiles()?.filter { it.absolutePath !in used }?.forEach { it.delete() }
    }

    private fun referencedImages(snapshot: BoardSnapshot): List<String> =
        snapshot.cards.flatMap { listOf(it.imagePath) + Markdown.imagePaths(it.details) }.filter { it.isNotBlank() }.distinct()

    private fun BoardCard.withImagePaths(from: String, to: String) = copy(
        imagePath = if (imagePath.startsWith(from)) to + imagePath.removePrefix(from) else imagePath,
        details = details.replace("](" + from, "](" + to)
    )
}
