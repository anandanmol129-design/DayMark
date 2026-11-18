package com.anmol.daymark

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.RemoteViews
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The Tasks widget's compact view: a slim header (page name with settings, quick add) over plain one-line rows.
 * Tapping a row opens [QuickTaskActivity] over the home screen instead of launching the app.
 */
object TaskCompactWidget {
    fun render(context: Context, widgetId: Int, snapshot: BoardSnapshot): RemoteViews {
        val page = snapshot.todoPages.firstOrNull { it.id == TaskListWidgetProvider.folderFor(context, widgetId) } ?: snapshot.todoPages.first()
        val columns = snapshot.columnsFor(page)
        val firstColumn = columns.firstOrNull()
        val today = LocalDate.now().toEpochDay()
        // Open tasks only, per column, in the app's order: priorities first, then due date, newest first.
        val groups = columns.filterNot { it.name.equals("Done", ignoreCase = true) }.map { column ->
            column to snapshot.cards.filter { it.columnId == column.id && !it.completed }
                .sortedWith(compareBy<BoardCard> { !it.important }.thenBy { it.dueDay ?: Long.MAX_VALUE }.thenByDescending { it.createdAt })
        }.filter { it.second.isNotEmpty() }
        // Headings only help once tasks are spread over more than the first column.
        val showHeadings = groups.any { it.first.id != firstColumn?.id }

        val views = RemoteViews(context.packageName, WidgetLook.layout(context, R.layout.widget_tasks_compact))
        views.setTextViewText(R.id.widget_folder_name, page.name)
        views.setOnClickPendingIntent(R.id.widget_open, WidgetActions.openApp(context, widgetId, 0, WidgetActions.TASKS_OPEN, page.id))
        views.setOnClickPendingIntent(R.id.widget_add, WidgetActions.openApp(context, widgetId, 1, WidgetActions.TASKS_ADD, page.id))
        views.setOnClickPendingIntent(
            R.id.widget_folder,
            PendingIntent.getActivity(
                context, widgetId * 10 + 2,
                Intent(context, WidgetSetupActivity::class.java).putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        )
        // Rows fill in their task id; the template is the only mutable PendingIntent here.
        views.setPendingIntentTemplate(
            R.id.widget_tasks,
            PendingIntent.getActivity(
                context, widgetId * 10 + 3,
                Intent(context, QuickTaskActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            )
        )

        // The Inbox widget also lists every other folder that has open tasks, each under its own heading.
        val sections = if (page.id == DEFAULT_TODO_PAGE_ID) {
            snapshot.todoPages.filter { it.id != page.id }.map { folder ->
                folder to snapshot.columnsFor(folder).filterNot { it.name.equals("Done", ignoreCase = true) }.flatMap { column ->
                    snapshot.cards.filter { it.columnId == column.id && !it.completed }
                        .sortedWith(compareBy<BoardCard> { !it.important }.thenBy { it.dueDay ?: Long.MAX_VALUE }.thenByDescending { it.createdAt })
                }
            }.filter { it.second.isNotEmpty() }
        } else emptyList()

        val items = RemoteViews.RemoteCollectionItems.Builder().setHasStableIds(true).setViewTypeCount(3)
        groups.forEach { (column, tasks) ->
            if (showHeadings) {
                val head = RemoteViews(context.packageName, WidgetLook.layout(context, R.layout.widget_tc_head))
                head.setTextViewText(R.id.tc_head, "${column.name} ${tasks.size}")
                items.addItem(column.id.hashCode().toLong() * 31, head)
            }
            addTasks(context, items, tasks, today)
        }
        sections.forEach { (folder, tasks) ->
            items.addItem(("section:" + folder.id).hashCode().toLong(), TaskListWidgetProvider.sectionHeader(context, folder.name, tasks.size, 12f, 10f))
            addTasks(context, items, tasks, today)
        }
        views.setRemoteAdapter(R.id.widget_tasks, items.build())
        views.setEmptyView(R.id.widget_tasks, R.id.widget_empty)
        return views
    }

    private fun addTasks(context: Context, items: RemoteViews.RemoteCollectionItems.Builder, tasks: List<BoardCard>, today: Long) {
        run {
            tasks.forEach { task ->
                val row = RemoteViews(context.packageName, WidgetLook.layout(context, R.layout.widget_tc_row))
                row.setTextViewText(R.id.tc_title, task.title.ifBlank { "Untitled" })
                row.setViewVisibility(R.id.tc_mark, if (task.important) View.VISIBLE else View.INVISIBLE)
                row.setInt(R.id.tc_mark, "setColorFilter", WidgetLook.pick(0xFFFFB74D.toInt(), 0xFFE07B00.toInt()))
                row.setTextViewText(R.id.tc_due, dueLabel(task.dueDay, today))
                row.setTextColor(R.id.tc_due, if (task.dueDay != null && task.dueDay < today) WidgetLook.pick(0xFFFF7A7A.toInt(), 0xFFE5303A.toInt()) else WidgetLook.pick(0xFF8A90A6.toInt(), 0xFF75685F.toInt()))
                row.setOnClickFillInIntent(R.id.tc_row, Intent().putExtra(QuickTaskActivity.EXTRA_TASK, task.id))
                items.addItem(task.id.hashCode().toLong(), row)
            }
        }
    }

    /** Short due text for a row: "Today", "Tomorrow", a weekday this week, otherwise "14 Nov". */
    fun dueLabel(day: Long?, today: Long): String {
        if (day == null) return ""
        val date = LocalDate.ofEpochDay(day)
        return when {
            day == today -> "Today"
            day == today + 1 -> "Tomorrow"
            day == today - 1 -> "Yesterday"
            day in today + 2..today + 6 -> date.format(DateTimeFormatter.ofPattern("EEE", Locale.getDefault()))
            else -> date.format(DateTimeFormatter.ofPattern("d MMM", Locale.getDefault()))
        }
    }
}
