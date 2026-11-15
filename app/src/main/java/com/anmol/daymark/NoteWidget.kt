package com.anmol.daymark

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.text.SpannableString
import android.text.Spanned
import android.text.style.StyleSpan
import android.util.TypedValue
import android.widget.RemoteViews
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Home-screen sticky note: one note's text. Tap it to edit; ⋮ picks another note or text size. */
class NoteWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, widgetIds: IntArray) {
        widgetIds.forEach { update(context, manager, it) }
    }

    override fun onDeleted(context: Context, widgetIds: IntArray) {
        widgetIds.forEach { WidgetActions.putString(context, it, "note", null); WidgetActions.setSize(context, it, null) }
    }

    /** A line of the note as the widget shows it. */
    data class Line(val text: String, val bold: Boolean = false, val title: Boolean = false)

    companion object {
        fun refresh(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            manager.getAppWidgetIds(ComponentName(context, NoteWidgetProvider::class.java)).forEach { update(context, manager, it) }
        }

        /** Notes worth pinning: anything with words in it, newest first. */
        fun pinnable(snapshot: BoardSnapshot) = snapshot.cards
            .filter { it.workspace == Workspace.NOTES && !it.isPrivate && (it.title.isNotBlank() || it.details.isNotBlank()) }
            .sortedByDescending { it.createdAt }

        fun noteFor(context: Context, widgetId: Int, snapshot: BoardSnapshot): BoardCard? =
            WidgetActions.string(context, widgetId, "note")?.let { id -> snapshot.cards.firstOrNull { it.id == id && !it.isPrivate } }

        /** Markdown flattened to plain lines; images and tables become short stand-ins. */
        fun linesOf(note: BoardCard): List<Line> = buildList {
            if (note.title.isNotBlank()) add(Line(note.title, bold = true, title = true))
            Markdown.parse(note.details).forEach { block ->
                when (block) {
                    is MdBlock.Heading -> add(Line(block.text, bold = true))
                    is MdBlock.Bullet -> add(Line("•  ${block.text}"))
                    is MdBlock.Paragraph -> block.text.lines().forEach { add(Line(it)) }
                    is MdBlock.Table -> block.rows.forEach { add(Line(it.joinToString("  ·  "))) }
                    is MdBlock.Image -> add(Line("Image"))
                    MdBlock.Rule -> add(Line("———"))
                }
            }
            if (note.link.isNotBlank()) add(Line(Saves.domainOf(note.link)))
        }

        fun update(context: Context, manager: AppWidgetManager, widgetId: Int) {
            val snapshot = BoardStore(context).load()
            val note = noteFor(context, widgetId, snapshot)
            val size = WidgetActions.sizeFor(context, widgetId)
            val views = RemoteViews(context.packageName, R.layout.widget_note)
            views.setOnClickPendingIntent(
                R.id.note_menu,
                PendingIntent.getActivity(
                    context, widgetId * 10 + 6,
                    Intent(context, NoteWidgetSettingsActivity::class.java).putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            )
            views.setTextViewText(R.id.note_empty, if (WidgetActions.string(context, widgetId, "note") != null && note == null) "This note was deleted. Tap ⋮ to choose another." else "Tap ⋮ to choose a note")
            // Tapping anywhere on the note opens it for editing.
            if (note != null) views.setPendingIntentTemplate(R.id.note_lines, WidgetActions.openApp(context, widgetId, 7, WidgetActions.NOTE_OPEN, note.id, mutable = true))
            val items = RemoteViews.RemoteCollectionItems.Builder().setHasStableIds(true).setViewTypeCount(1)
            note?.let(::linesOf)?.take(80)?.forEachIndexed { index, line ->
                val row = RemoteViews(context.packageName, R.layout.widget_note_line)
                row.setTextViewText(R.id.note_line, if (!line.bold) line.text else SpannableString(line.text).apply { setSpan(StyleSpan(Typeface.BOLD), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) })
                row.setTextViewTextSize(R.id.note_line, TypedValue.COMPLEX_UNIT_SP, if (line.title) size.headerSp else size.taskSp)
                row.setOnClickFillInIntent(R.id.note_line, Intent())
                items.addItem(index.toLong(), row)
            }
            views.setRemoteAdapter(R.id.note_lines, items.build())
            views.setEmptyView(R.id.note_lines, R.id.note_empty)
            manager.updateAppWidget(widgetId, views)
        }
    }
}

/** Note widget setup: which note to pin and how big the text is. Runs on placement and from ⋮. */
class NoteWidgetSettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val widgetId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId))
        if (widgetId == AppWidgetManager.INVALID_APPWIDGET_ID) { finish(); return }
        val snapshot = BoardStore(this).load()
        val notes = NoteWidgetProvider.pinnable(snapshot)
        val folders = snapshot.columns.associate { column -> column.id to (snapshot.notesPages.firstOrNull { it.id == column.pageId }?.name ?: column.name) }

        setContent {
            var noteId by remember { mutableStateOf(NoteWidgetProvider.noteFor(this, widgetId, snapshot)?.id ?: notes.firstOrNull()?.id) }
            var size by remember { mutableStateOf(WidgetActions.sizeFor(this, widgetId)) }
            val lines = remember(noteId) { notes.firstOrNull { it.id == noteId }?.let(NoteWidgetProvider::linesOf).orEmpty().take(6) }
            WidgetSettingsScreen(
                size = size,
                onSize = { size = it },
                onDismiss = ::finish,
                onDone = {
                    WidgetActions.putString(this, widgetId, "note", noteId)
                    WidgetActions.setSize(this, widgetId, size)
                    NoteWidgetProvider.update(this, AppWidgetManager.getInstance(this), widgetId)
                    finish()
                },
                preview = {
                    if (lines.isEmpty()) Text("No notes yet. Write one in the Notes tab.", color = TodoMuted, fontSize = 14.sp, modifier = Modifier.padding(16.dp))
                    lines.forEach { line ->
                        Text(
                            line.text, color = TodoText, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            fontSize = (if (line.title) size.headerSp else size.taskSp).sp,
                            fontWeight = if (line.bold) FontWeight.Bold else FontWeight.Normal,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 1.dp)
                        )
                    }
                },
                choices = if (notes.isEmpty()) null else ({
                    WidgetSettingsLabel("NOTE")
                    LazyColumn(Modifier.heightIn(max = 230.dp)) {
                        items(notes, key = { it.id }) { note ->
                            val label = note.displayTitle
                            WidgetChoiceRow(label, note.id == noteId, hint = folders[note.columnId]) { noteId = note.id }
                        }
                    }
                })
            )
        }
    }
}
