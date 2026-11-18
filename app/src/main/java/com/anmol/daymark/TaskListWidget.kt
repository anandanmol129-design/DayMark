package com.anmol.daymark

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.text.SpannableString
import android.text.Spanned
import android.text.style.StrikethroughSpan
import java.time.LocalDate
import java.util.concurrent.ConcurrentHashMap
import android.widget.RemoteViews
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Tasks widget: the open tasks of one folder. Detailed: tick-to-complete rows under a header with open, settings and
 * quick-add buttons. Compact: see [TaskCompactWidget].
 */
class TaskListWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, widgetIds: IntArray) {
        widgetIds.forEach { update(context, manager, it) }
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_COMPLETE) return super.onReceive(context, intent)
        val taskId = intent.getStringExtra(EXTRA_TASK) ?: return
        if (!ticking.add(taskId)) return
        val app = context.applicationContext
        val pending = goAsync()
        // Show the tick straight away, then complete it so it slides off the list.
        BoardStore.enqueue { refresh(app) }
        Handler(Looper.getMainLooper()).postDelayed({
            BoardStore.enqueue {
                try {
                    val store = BoardStore(app)
                    val snapshot = store.load()
                    val today = LocalDate.now().toEpochDay()
                    store.save(snapshot.completeCard(taskId, today))
                    ticking.remove(taskId)
                    WidgetActions.refreshAll(app)
                } finally {
                    pending.finish()
                }
            }
        }, 650)
    }

    // Resizing to a very short widget re-fits the header and rows (see [update]).
    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, widgetId: Int, newOptions: Bundle) {
        update(context, manager, widgetId)
    }

    override fun onDeleted(context: Context, widgetIds: IntArray) {
        widgetIds.forEach { setFolder(context, it, null); WidgetLook.forget(context, it) }
    }

    companion object {
        const val ACTION_COMPLETE = "com.anmol.daymark.widget.COMPLETE"
        const val EXTRA_TASK = "task"
        private const val PREFS = "daymark_settings"
        /** Tasks ticked on the widget that are showing their check before they disappear. */
        private val ticking = ConcurrentHashMap.newKeySet<String>()

        fun folderFor(context: Context, widgetId: Int): String? =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("widget_folder_$widgetId", null)

        fun setFolder(context: Context, widgetId: Int, folderId: String?) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().apply {
                if (folderId == null) remove("widget_folder_$widgetId") else putString("widget_folder_$widgetId", folderId)
            }.apply()
        }

        /** Open tasks of one folder in the app's order: priorities first, then by due date, newest first. */
        fun openTasksOf(snapshot: BoardSnapshot, page: BoardPage): List<BoardCard> {
            val listIds = snapshot.columns.filter { it.workspace == Workspace.TODOS && it.pageId == page.id }.map { it.id }.toSet()
            return snapshot.cards.filter { it.columnId in listIds && !it.completed }
                .sortedWith(compareBy<BoardCard> { !it.important }.thenBy { it.dueDay ?: Long.MAX_VALUE }.thenByDescending { it.createdAt })
        }

        /** Divider, folder name with its open-task count, divider. Not tappable. */
        fun sectionHeader(context: Context, name: String, count: Int, nameSp: Float, countSp: Float): RemoteViews =
            RemoteViews(context.packageName, WidgetLook.layout(context, R.layout.widget_section_head)).apply {
                setTextViewText(R.id.section_name, name)
                setTextViewTextSize(R.id.section_name, TypedValue.COMPLEX_UNIT_SP, nameSp)
                setTextViewText(R.id.section_count, count.toString())
                setTextViewTextSize(R.id.section_count, TypedValue.COMPLEX_UNIT_SP, countSp)
            }

        fun refresh(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            manager.getAppWidgetIds(ComponentName(context, TaskListWidgetProvider::class.java)).forEach { update(context, manager, it) }
        }

        fun update(context: Context, manager: AppWidgetManager, widgetId: Int) {
            val snapshot = BoardStore(context).load()
            if (WidgetLook.begin(context, widgetId, WidgetKind.TASKS).view == WidgetView.COMPACT) {
                manager.updateAppWidget(widgetId, TaskCompactWidget.render(context, widgetId, snapshot))
                return
            }
            val page = snapshot.todoPages.firstOrNull { it.id == folderFor(context, widgetId) } ?: snapshot.todoPages.first()
            val tasks = openTasksOf(snapshot, page)
            // The Inbox widget also lists every other folder that has open tasks, each under its own heading.
            val sections = if (page.id == DEFAULT_TODO_PAGE_ID) {
                snapshot.todoPages.filter { it.id != page.id }.map { it to openTasksOf(snapshot, it) }.filter { it.second.isNotEmpty() }
            } else emptyList()

            val size = WidgetActions.sizeFor(context, widgetId)
            val density = context.resources.displayMetrics.density
            val views = RemoteViews(context.packageName, WidgetLook.layout(context, R.layout.widget_tasks))
            // On a very short widget (about one launcher cell) the full-size header would eat the list, so shrink it.
            // At the normal size (two cells and up) nothing changes.
            val heightDp = manager.getAppWidgetOptions(widgetId).getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 0)
            val tight = heightDp in 1..99
            views.setTextViewText(R.id.widget_folder_name, page.name)
            views.setTextViewTextSize(R.id.widget_folder_name, TypedValue.COMPLEX_UNIT_SP, if (tight) minOf(size.headerSp, 15f) else size.headerSp)
            if (tight) {
                views.setViewPadding(R.id.widget_header, (10 * density).toInt(), (2 * density).toInt(), (10 * density).toInt(), (2 * density).toInt())
                views.setViewLayoutHeight(R.id.widget_open, 34f, TypedValue.COMPLEX_UNIT_DIP)
                views.setViewLayoutHeight(R.id.widget_folder, 34f, TypedValue.COMPLEX_UNIT_DIP)
                views.setViewLayoutWidth(R.id.widget_add, 30f, TypedValue.COMPLEX_UNIT_DIP)
                views.setViewLayoutHeight(R.id.widget_add, 30f, TypedValue.COMPLEX_UNIT_DIP)
            }
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
            // Only the checkbox on each row does anything: it completes the task without opening the app.
            views.setPendingIntentTemplate(
                R.id.widget_tasks,
                PendingIntent.getBroadcast(
                    context, widgetId * 10 + 3,
                    Intent(context, TaskListWidgetProvider::class.java).setAction(ACTION_COMPLETE),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
                )
            )
            val items = RemoteViews.RemoteCollectionItems.Builder().setHasStableIds(true).setViewTypeCount(2)
            val all = tasks.map { null to it } + sections.flatMap { (folder, list) -> list.mapIndexed { i, t -> (if (i == 0) folder to list.size else null) to t } }
            // A section header goes in before the first task of each other folder.
            all.forEach { (header, task) ->
                if (header != null) items.addItem(("section:" + header.first.id).hashCode().toLong(), sectionHeader(context, header.first.name, header.second, maxOf(12f, size.taskSp - 2f), 12f))
                val done = task.id in ticking
                val row = RemoteViews(context.packageName, WidgetLook.layout(context, R.layout.widget_task_item))
                row.setTextViewText(
                    R.id.widget_task_title,
                    if (!done) task.title else SpannableString(task.title).apply { setSpan(StrikethroughSpan(), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
                )
                row.setTextColor(R.id.widget_task_title, if (done) WidgetLook.pick(0xFF8A90A6.toInt(), 0xFF75685F.toInt()) else WidgetLook.pick(0xFFF1F1F2.toInt(), 0xFF221A16.toInt()))
                row.setTextViewTextSize(R.id.widget_task_title, TypedValue.COMPLEX_UNIT_SP, size.taskSp)
                // Keep the circle about 20dp and shrink the row around it.
                val rowDp = if (tight) minOf(size.rowDp, 32) else size.rowDp
                row.setViewLayoutHeight(R.id.widget_task_check, rowDp.toFloat(), TypedValue.COMPLEX_UNIT_DIP)
                val vertical = ((rowDp - 20) / 2f * density).toInt()
                row.setViewPadding(R.id.widget_task_check, (10 * density).toInt(), vertical, (12 * density).toInt(), vertical)
                row.setImageViewResource(R.id.widget_task_check, WidgetLook.drawable(if (done) R.drawable.ic_widget_check_on else R.drawable.ic_widget_check_off))
                if (!done) row.setOnClickFillInIntent(R.id.widget_task_check, Intent().putExtra(EXTRA_TASK, task.id))
                items.addItem(task.id.hashCode().toLong(), row)
            }
            views.setRemoteAdapter(R.id.widget_tasks, items.build())
            views.setEmptyView(R.id.widget_tasks, R.id.widget_empty)
            manager.updateAppWidget(widgetId, views)
        }
    }
}
