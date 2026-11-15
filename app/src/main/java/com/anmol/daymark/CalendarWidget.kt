package com.anmol.daymark

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.text.SpannableString
import android.text.Spanned
import android.text.format.DateFormat
import android.text.style.StrikethroughSpan
import android.util.Log
import android.widget.RemoteViews
import androidx.compose.ui.graphics.toArgb
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Base for the widgets drawn from one [BoardSnapshot]: [render] builds the views for one widget id, and
 * the base class takes care of updating either some ids (system update) or all of its ids (data changed).
 */
abstract class DaymarkWidget : AppWidgetProvider() {
    abstract fun render(context: Context, widgetId: Int, snapshot: BoardSnapshot): RemoteViews

    fun update(context: Context, widgetIds: IntArray) {
        if (widgetIds.isEmpty()) return
        val manager = AppWidgetManager.getInstance(context)
        val snapshot = BoardStore(context).load()
        widgetIds.forEach { id ->
            runCatching { manager.updateAppWidget(id, render(context, id, snapshot)) }
                .onFailure { Log.e("DaymarkWidget", "Could not draw ${javaClass.simpleName} $id", it) }
        }
    }

    fun refresh(context: Context) {
        update(context, AppWidgetManager.getInstance(context).getAppWidgetIds(ComponentName(context, javaClass)))
    }

    override fun onUpdate(context: Context, manager: AppWidgetManager, widgetIds: IntArray) {
        update(context, widgetIds)
        WidgetClock.schedule(context)
    }

    override fun onEnabled(context: Context) = WidgetClock.schedule(context)
}

/** What the calendar widgets share: the day's entries, labels and times. Week convention: Sunday first, as in the app. */
internal object CalendarWidgetData {
    /** The day's entries without habits: all-day first, then by start time. */
    fun agenda(cards: List<BoardCard>, day: Long): List<DayEntry> =
        entriesFor(cards, day).filter { it.card.kind != CardKind.HABIT }
            .sortedWith(compareBy<DayEntry> { it.minute != null }.thenBy { it.minute ?: 0 })

    fun clock(context: Context, minute: Int): String {
        val pattern = if (DateFormat.is24HourFormat(context)) "HH:mm" else "h:mm a"
        return LocalTime.of((minute / 60) % 24, minute % 60).format(DateTimeFormatter.ofPattern(pattern, Locale.getDefault()))
    }

    fun timeLabel(context: Context, entry: DayEntry) = entry.minute?.let { clock(context, it) } ?: "All day"

    /** "Today", "Tomorrow", then "Mon 16". */
    fun dayHeading(day: Long, today: Long): String = when (day - today) {
        0L -> "Today"
        1L -> "Tomorrow"
        else -> LocalDate.ofEpochDay(day).format(DateTimeFormatter.ofPattern("EEE d", Locale.getDefault()))
    }

    fun title(entry: DayEntry): CharSequence {
        val text = entry.card.title.ifBlank { "Untitled" }
        return if (!entry.done) text else SpannableString(text).apply { setSpan(StrikethroughSpan(), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
    }

    /** A single event row, shared by the compact and detailed lists. */
    fun eventRow(context: Context, layout: Int, entry: DayEntry, time: String, day: Long): RemoteViews {
        val row = RemoteViews(context.packageName, layout)
        row.setTextViewText(R.id.ev_row_title, title(entry))
        row.setTextViewText(R.id.ev_time, time)
        row.setInt(R.id.ev_dot, "setColorFilter", entryColor(entry.card).toArgb())
        row.setOnClickFillInIntent(R.id.ev_row, WidgetActions.dayFillIn(day))
        return row
    }

    fun stableId(day: Long, key: String) = day * 31 + key.hashCode()
}

object EventsWidgets {
    fun refreshAll(context: Context) {
        EventsCompactWidgetProvider().refresh(context)
        EventsDetailedWidgetProvider().refresh(context)
    }

    internal fun header(context: Context, widgetId: Int, title: String, today: Long, layout: Int = R.layout.widget_events): RemoteViews {
        val views = RemoteViews(context.packageName, layout)
        views.setTextViewText(R.id.ev_title, title)
        views.setOnClickPendingIntent(R.id.ev_open, WidgetActions.openApp(context, widgetId, 0, WidgetActions.CALENDAR_OPEN, day = today))
        views.setOnClickPendingIntent(R.id.ev_title, WidgetActions.openApp(context, widgetId, 0, WidgetActions.CALENDAR_OPEN, day = today))
        views.setOnClickPendingIntent(R.id.ev_add, WidgetActions.openApp(context, widgetId, 1, WidgetActions.CALENDAR_ADD, day = today))
        // Each row opens the calendar on its own day (filled in per row).
        views.setPendingIntentTemplate(R.id.ev_list, WidgetActions.openApp(context, widgetId, 2, WidgetActions.CALENDAR_OPEN, day = today, mutable = true))
        return views
    }
}

/** Compact events: today's date and the next few upcoming events or timed tasks, one line each. */
class EventsCompactWidgetProvider : DaymarkWidget() {
    override fun render(context: Context, widgetId: Int, snapshot: BoardSnapshot): RemoteViews {
        val todayDate = LocalDate.now()
        val today = todayDate.toEpochDay()
        val nowMinute = LocalTime.now().let { it.hour * 60 + it.minute }
        val views = EventsWidgets.header(context, widgetId, todayDate.format(DateTimeFormatter.ofPattern("EEE, d MMM", Locale.getDefault())), today)
        views.setTextViewText(R.id.ev_empty, "Nothing coming up")

        val upcoming = mutableListOf<Pair<Long, DayEntry>>()
        for (offset in 0L..7L) {
            val day = today + offset
            CalendarWidgetData.agenda(snapshot.cards, day).forEach { entry ->
                val relevant = !entry.done && (entry.minute != null || entry.card.kind == CardKind.EVENT)
                // Today: keep all-day items and anything that has not finished yet.
                val notOver = offset > 0 || entry.minute == null || entry.minute + entry.card.durationMinutes >= nowMinute
                if (relevant && notOver) upcoming += day to entry
            }
            if (upcoming.size >= 8) break
        }

        val items = RemoteViews.RemoteCollectionItems.Builder().setHasStableIds(true).setViewTypeCount(1)
        upcoming.take(8).forEach { (day, entry) ->
            val time = if (day == today) CalendarWidgetData.timeLabel(context, entry) else {
                val label = CalendarWidgetData.dayHeading(day, today).let { if (it == "Tomorrow") "Tmrw" else it.substringBefore(' ') }
                entry.minute?.let { "$label ${CalendarWidgetData.clock(context, it)}" } ?: label
            }
            items.addItem(CalendarWidgetData.stableId(day, entry.card.id), CalendarWidgetData.eventRow(context, R.layout.widget_ev_row_compact, entry, time, day))
        }
        views.setRemoteAdapter(R.id.ev_list, items.build())
        views.setEmptyView(R.id.ev_list, R.id.ev_empty)
        return views
    }
}

/** Detailed events: a scrolling agenda for the next seven days, grouped by day. */
class EventsDetailedWidgetProvider : DaymarkWidget() {
    override fun render(context: Context, widgetId: Int, snapshot: BoardSnapshot): RemoteViews {
        val today = LocalDate.now().toEpochDay()
        val views = EventsWidgets.header(context, widgetId, "Next 7 days", today)
        views.setTextViewText(R.id.ev_empty, "Nothing planned this week")

        val items = RemoteViews.RemoteCollectionItems.Builder().setHasStableIds(true).setViewTypeCount(2)
        for (day in today..today + 6) {
            val entries = CalendarWidgetData.agenda(snapshot.cards, day).filter { !it.done }
            if (entries.isEmpty()) continue
            val head = RemoteViews(context.packageName, R.layout.widget_ev_head)
            head.setTextViewText(R.id.ev_head, CalendarWidgetData.dayHeading(day, today))
            head.setOnClickFillInIntent(R.id.ev_head_row, WidgetActions.dayFillIn(day))
            items.addItem(CalendarWidgetData.stableId(day, "head"), head)
            entries.take(30).forEach { entry ->
                items.addItem(
                    CalendarWidgetData.stableId(day, entry.card.id),
                    CalendarWidgetData.eventRow(context, R.layout.widget_ev_row_detailed, entry, CalendarWidgetData.timeLabel(context, entry), day)
                )
            }
        }
        views.setRemoteAdapter(R.id.ev_list, items.build())
        views.setEmptyView(R.id.ev_list, R.id.ev_empty)
        return views
    }
}
