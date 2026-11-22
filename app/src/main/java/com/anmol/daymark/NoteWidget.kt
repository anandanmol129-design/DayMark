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
        widgetIds.forEach { WidgetActions.putString(context, it, "note", null); WidgetLook.forget(context, it) }
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
            Markdown.parse(Transcripts.withoutTranscript(note.details)).forEach { block ->
                when (block) {
                    is MdBlock.Heading -> add(Line(block.text, bold = true))
                    is MdBlock.Bullet -> add(Line("•  ${block.text}"))
                    is MdBlock.Paragraph -> block.text.lines().forEach { add(Line(it)) }
                    is MdBlock.Table -> block.rows.forEach { add(Line(it.joinToString("  ·  "))) }
                    is MdBlock.Image -> add(Line("Image"))
                    is MdBlock.File -> add(Line("📎  ${block.name}"))
                    MdBlock.Rule -> add(Line("———"))
                }
            }
            if (note.link.isNotBlank()) add(Line(Saves.domainOf(note.link)))
        }

        fun update(context: Context, manager: AppWidgetManager, widgetId: Int) {
            val snapshot = BoardStore(context).load()
            WidgetLook.begin(context, widgetId, WidgetKind.NOTE)
            val note = noteFor(context, widgetId, snapshot)
            val size = WidgetActions.sizeFor(context, widgetId)
            val views = RemoteViews(context.packageName, WidgetLook.layout(context, R.layout.widget_note))
            views.setOnClickPendingIntent(
                R.id.note_menu,
                PendingIntent.getActivity(
                    context, widgetId * 10 + 6,
                    Intent(context, WidgetSetupActivity::class.java).putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            )
            views.setTextViewText(R.id.note_empty, if (WidgetActions.string(context, widgetId, "note") != null && note == null) "This note was deleted. Tap ⋮ to choose another." else "Tap ⋮ to choose a note")
            // Tapping anywhere on the note opens it for editing.
            if (note != null) views.setPendingIntentTemplate(R.id.note_lines, WidgetActions.openApp(context, widgetId, 7, WidgetActions.NOTE_OPEN, note.id, mutable = true))
            val items = RemoteViews.RemoteCollectionItems.Builder().setHasStableIds(true).setViewTypeCount(1)
            note?.let(::linesOf)?.take(80)?.forEachIndexed { index, line ->
                val row = RemoteViews(context.packageName, WidgetLook.layout(context, R.layout.widget_note_line))
                row.setTextViewText(R.id.note_line, if (!line.bold) line.text else SpannableString(line.text).apply { setSpan(StyleSpan(Typeface.BOLD), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) })
                row.setTextViewTextSize(R.id.note_line, TypedValue.COMPLEX_UNIT_SP, if (line.title) size.headerSp else size.taskSp)
                if (line.title) row.setTextColor(R.id.note_line, WidgetLook.accentInk)
                row.setOnClickFillInIntent(R.id.note_line, Intent())
                items.addItem(index.toLong(), row)
            }
            views.setRemoteAdapter(R.id.note_lines, items.build())
            views.setEmptyView(R.id.note_lines, R.id.note_empty)
            manager.updateAppWidget(widgetId, views)
        }
    }
}
