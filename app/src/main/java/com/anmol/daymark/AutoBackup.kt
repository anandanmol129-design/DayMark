package com.anmol.daymark

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Backs up to a folder the user picks (on the phone, an SD card, or a cloud folder such as Drive that
 * shows up in the file picker), daily or weekly. Each run writes a dated zip and keeps the newest [KEEP].
 * Only files named `daymark-auto-…` are ever pruned, so nothing else in the folder is touched.
 */
object AutoBackup {
    private const val PREFS = "daymark_settings"
    private const val KEY_FOLDER = "auto_backup_folder"
    private const val KEY_EVERY = "auto_backup_every_days"
    private const val KEY_LAST = "auto_backup_last"
    private const val KEY_ERROR = "auto_backup_error"
    private const val PREFIX = "daymark-auto-"
    private const val JOB_ID = 4_2001
    const val KEEP = 5

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun folder(context: Context): Uri? = prefs(context).getString(KEY_FOLDER, null)?.let(Uri::parse)
    /** 0 = off, 1 = daily, 7 = weekly. */
    fun everyDays(context: Context): Int = prefs(context).getInt(KEY_EVERY, 0)
    fun lastMillis(context: Context): Long = prefs(context).getLong(KEY_LAST, 0L)
    fun lastError(context: Context): String? = prefs(context).getString(KEY_ERROR, null)

    /** The picked folder's name as the file picker showed it, or null if it's gone or access was lost. */
    fun folderName(context: Context): String? {
        val tree = folder(context) ?: return null
        return runCatching {
            val doc = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
            context.contentResolver.query(doc, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        }.getOrNull()
    }

    fun setFolder(context: Context, tree: Uri) {
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        val old = folder(context)
        context.contentResolver.takePersistableUriPermission(tree, flags)
        if (old != null && old != tree) runCatching { context.contentResolver.releasePersistableUriPermission(old, flags) }
        prefs(context).edit().putString(KEY_FOLDER, tree.toString()).remove(KEY_ERROR).apply()
        // Picking a folder with backups off turns on daily backups, which is what someone picking a folder wants.
        if (everyDays(context) == 0) prefs(context).edit().putInt(KEY_EVERY, 1).apply()
        schedule(context)
    }

    fun setEveryDays(context: Context, days: Int) {
        prefs(context).edit().putInt(KEY_EVERY, days).apply()
        schedule(context)
    }

    /** Keeps the background job in step with the settings. Safe to call at any time. */
    fun schedule(context: Context) {
        val scheduler = context.getSystemService(JobScheduler::class.java) ?: return
        if (everyDays(context) == 0 || folder(context) == null) { scheduler.cancel(JOB_ID); return }
        if (scheduler.getPendingJob(JOB_ID) != null) return
        // The job wakes about twice a day and only backs up when one is due, so a missed run is caught up soon after.
        val job = JobInfo.Builder(JOB_ID, ComponentName(context, AutoBackupJob::class.java))
            .setPeriodic(TimeUnit.HOURS.toMillis(12), TimeUnit.HOURS.toMillis(3))
            .setRequiresBatteryNotLow(true)
            .setPersisted(true)
            .build()
        runCatching { scheduler.schedule(job) }
    }

    fun isDue(context: Context): Boolean {
        val days = everyDays(context)
        if (days == 0 || folder(context) == null) return false
        // An hour of slack so a daily backup doesn't slip a day each time the job runs a little early.
        return System.currentTimeMillis() - lastMillis(context) >= TimeUnit.DAYS.toMillis(days.toLong()) - TimeUnit.HOURS.toMillis(1)
    }

    /** Backs up if one is due. Runs on the calling (background) thread. */
    fun runIfDue(context: Context) {
        if (isDue(context)) runNow(context)
    }

    /** Writes a backup into the chosen folder now. Returns the file name, or throws with a message to show. */
    @Synchronized
    fun runNow(context: Context, snapshot: BoardSnapshot = BoardStore(context).load()): String {
        val tree = folder(context) ?: error("Choose a folder first")
        try {
            val resolver = context.contentResolver
            val rootId = DocumentsContract.getTreeDocumentId(tree)
            val parent = DocumentsContract.buildDocumentUriUsingTree(tree, rootId)
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, rootId)
            val existing = mutableListOf<Pair<String, String>>() // name to document id
            resolver.query(children, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { c ->
                while (c.moveToNext()) {
                    val name = c.getString(1) ?: continue
                    if (name.startsWith(PREFIX) && name.endsWith(".zip")) existing += name to c.getString(0)
                }
            } ?: error("Can’t open the backup folder. Choose it again.")
            val name = PREFIX + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd")) + ".zip"
            // A second run on the same day replaces that day's file instead of making "(1)" copies.
            val target = existing.firstOrNull { it.first == name }?.let { DocumentsContract.buildDocumentUriUsingTree(tree, it.second) }
                ?: DocumentsContract.createDocument(resolver, parent, "application/zip", name)
                ?: error("Couldn’t create a file in the backup folder")
            Backup.export(context, snapshot, target)
            // Dated names sort in date order, so the oldest are at the end.
            (existing.filter { it.first != name } + (name to "")).sortedByDescending { it.first }.drop(KEEP).forEach { (_, id) ->
                if (id.isNotEmpty()) runCatching { DocumentsContract.deleteDocument(resolver, DocumentsContract.buildDocumentUriUsingTree(tree, id)) }
            }
            prefs(context).edit().putLong(KEY_LAST, System.currentTimeMillis()).remove(KEY_ERROR).apply()
            return name
        } catch (e: SecurityException) {
            val message = "Daymark lost access to the backup folder. Choose it again."
            prefs(context).edit().putString(KEY_ERROR, message).apply()
            error(message)
        } catch (e: Exception) {
            val message = e.message ?: "Automatic backup failed"
            prefs(context).edit().putString(KEY_ERROR, message).apply()
            throw e
        }
    }
}

/** Wakes up now and then and writes an automatic backup when one is due. */
class AutoBackupJob : JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        thread(name = "daymark-auto-backup") {
            runCatching { AutoBackup.runIfDue(applicationContext) }
            jobFinished(params, false)
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean = true
}
