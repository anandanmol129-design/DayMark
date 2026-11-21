package com.anmol.daymark

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Notes pinned to the notification shade: one ongoing, quiet notification per note showing its title and text.
 * The set of pinned note ids lives in its own preferences; [sync] (called from [BoardStore.save], app start and boot)
 * keeps the notifications equal to the saved notes, and drops pins whose note was deleted or moved to Private.
 */
object PinnedNotes {
    private const val TAG = "DaymarkPins"
    private const val PREFS = "daymark_pinned"
    const val CHANNEL = "pinned_notes"
    internal const val ACTION_UNPIN = "com.anmol.daymark.pin.UNPIN"
    private const val EXTRA_ID = "card"
    /** Bumped on every pin change so an open editor's button follows (including Unpin from the shade). */
    val changes = MutableStateFlow(0)
    private val posted = HashMap<String, Int>()

    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    fun ids(c: Context): Set<String> = prefs(c).getStringSet("ids", emptySet()).orEmpty().toSet()
    fun isPinned(c: Context, id: String) = id in ids(c)
    private fun write(c: Context, ids: Set<String>) { prefs(c).edit().putStringSet("ids", ids).apply(); changes.value++ }
    private fun notificationId(id: String) = ("pin:$id").hashCode() and 0x7FFFFFFF or 0x20000

    /** Pins or unpins; the notification follows on the next [sync]. */
    fun set(c: Context, id: String, pinned: Boolean) {
        val app = c.applicationContext
        write(app, if (pinned) ids(app) + id else ids(app) - id)
        if (!pinned) cancel(app, id)
        BoardStore.enqueue { runCatching { sync(app, BoardStore(app).load(), force = true) } }
    }

    private fun cancel(c: Context, id: String) {
        synchronized(posted) { posted.remove(id) }
        c.getSystemService(NotificationManager::class.java).cancel(notificationId(id))
    }

    private fun ensureChannel(c: Context) {
        val nm = c.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Pinned notes", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Notes you pinned to your notifications"
                setSound(null, null); enableVibration(false)
            }
        )
    }

    /** A note as plain lines for a notification: no markdown symbols, checklist boxes and bullets as symbols. */
    internal fun plainText(source: String): String = source.lines().mapNotNull { line ->
        val block = NoteBlocks.classify(line)
        val text = Markdown.plain(line)
        if (text.isBlank()) return@mapNotNull null
        when (block.kind) {
            BlockKind.CHECK -> (if (block.checked) "☑ " else "☐ ") + text
            BlockKind.BULLET -> "• $text"
            BlockKind.NUMBERED -> block.prefix.trim() + " " + text
            BlockKind.QUOTE -> "“$text”"
            else -> text
        }
    }.joinToString("\n")

    /** Makes the notifications match [snapshot]. [force] re-posts even unchanged ones (app start, boot). */
    @Synchronized
    fun sync(context: Context, snapshot: BoardSnapshot, force: Boolean = false) {
        val c = context.applicationContext
        val pinned = ids(c)
        if (pinned.isEmpty() && !force) return
        val cards = snapshot.cards.associateBy { it.id }
        // Private notes never show content in notifications; a deleted note has nothing to show.
        val valid = pinned.filter { id -> cards[id]?.let { it.workspace == Workspace.NOTES && !it.isPrivate } == true }.toSet()
        if (valid != pinned) write(c, valid)
        (pinned - valid).forEach { cancel(c, it) }
        if (valid.isEmpty() || !Reminders.notificationsAllowed(c)) return
        ensureChannel(c)
        val nm = c.getSystemService(NotificationManager::class.java)
        valid.forEach { id ->
            val card = cards.getValue(id)
            val title = card.title.ifBlank { Markdown.firstLine(card.details) ?: "Note" }
            val body = plainText(card.details)
            val hash = (title + "\u0000" + body).hashCode()
            if (!force && synchronized(posted) { posted[id] } == hash) return@forEach
            val n = Notification.Builder(c, CHANNEL)
                .setSmallIcon(R.drawable.ic_notification_reminder)
                .setContentTitle(title)
                .setContentText(body.lineSequence().firstOrNull().orEmpty())
                .setStyle(Notification.BigTextStyle().bigText(body.ifBlank { title }))
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setCategory(Notification.CATEGORY_STATUS)
                .setContentIntent(PendingIntent.getActivity(
                    c, notificationId(id), WidgetActions.appIntent(c, WidgetActions.NOTE_OPEN, id),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                ))
                .addAction(Notification.Action.Builder(null, "Unpin", PendingIntent.getBroadcast(
                    c, notificationId(id),
                    Intent(c, PinnedNotesReceiver::class.java).setAction(ACTION_UNPIN).setData(Uri.parse("daymark://unpin/${Uri.encode(id)}")).putExtra(EXTRA_ID, id),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )).build())
                .build()
            runCatching { nm.notify(notificationId(id), n); synchronized(posted) { posted[id] = hash } }.onFailure { Log.w(TAG, "Couldn't post pin", it) }
        }
    }

    internal fun unpinFromIntent(c: Context, intent: Intent) {
        if (intent.action == ACTION_UNPIN) intent.getStringExtra(EXTRA_ID)?.let { set(c, it, false) }
    }
}

class PinnedNotesReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = PinnedNotes.unpinFromIntent(context.applicationContext, intent)
}

/** The editor's pin toggle (same look as its round buttons). Asks for notification permission the first time, as reminders do. */
@Composable
internal fun PinNoteButton(noteId: String) {
    val context = LocalContext.current
    val tick by PinnedNotes.changes.collectAsState()
    val pinned = remember(tick, noteId) { PinnedNotes.isPinned(context, noteId) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) PinnedNotes.set(context, noteId, true)
        else android.widget.Toast.makeText(context, "Notifications are off, so the note can’t be pinned. Allow them in Settings › Reminders.", android.widget.Toast.LENGTH_LONG).show()
    }
    Surface(
        color = if (pinned) GlassAccent else TodoNav, shape = CircleShape, border = GlassStroke, shadowElevation = 10.dp,
        modifier = Modifier.size(52.dp).clip(CircleShape).semantics { contentDescription = if (pinned) "Unpin from notifications" else "Pin to notifications" }.clickable {
            when {
                pinned -> PinnedNotes.set(context, noteId, false)
                Reminders.needsNotificationPermission(context) -> permission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                else -> {
                    if (!Reminders.notificationsAllowed(context)) android.widget.Toast.makeText(context, "Notifications are off for Daymark. Allow them in Settings › Reminders.", android.widget.Toast.LENGTH_LONG).show()
                    else PinnedNotes.set(context, noteId, true)
                }
            }
        }
    ) {
        Box(contentAlignment = Alignment.Center) { Glyph(ZenIcon.Pin, TodoText, 22.dp) }
    }
}
