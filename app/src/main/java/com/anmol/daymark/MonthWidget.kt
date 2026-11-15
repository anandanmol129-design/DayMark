package com.anmol.daymark

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.RemoteViews
import androidx.compose.ui.graphics.toArgb
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Which month a month widget shows and which day is selected. Reset to "this month, today" after midnight. */
internal data class MonthState(val month: YearMonth, val selected: Long) {
    fun save(context: Context, widgetId: Int, today: LocalDate) =
        WidgetActions.putString(context, widgetId, "month", "${month.atDay(1).toEpochDay()},$selected,${today.toEpochDay()}")

    companion object {
        fun today(today: LocalDate) = MonthState(YearMonth.from(today), today.toEpochDay())

        fun load(context: Context, widgetId: Int, today: LocalDate): MonthState {
            val parts = WidgetActions.string(context, widgetId, "month")?.split(',')?.mapNotNull { it.toLongOrNull() }
            if (parts == null || parts.size != 3 || parts[2] != today.toEpochDay()) return today(today)
            return MonthState(YearMonth.from(LocalDate.ofEpochDay(parts[0])), parts[1])
        }
    }
}

object MonthWidgets {
    fun refreshAll(context: Context) {
        MonthCompactWidgetProvider().refresh(context)
        MonthDetailedWidgetProvider().refresh(context)
    }

    const val ACTION = "com.anmol.daymark.widget.MONTH"
    const val EXTRA_OP = "op"
    const val EXTRA_DAY = "day"

    private val ROW = intArrayOf(R.id.grid_row_0, R.id.grid_row_1, R.id.grid_row_2, R.id.grid_row_3, R.id.grid_row_4, R.id.grid_row_5)
    private val CELL = intArrayOf(
        R.id.cell_0, R.id.cell_1, R.id.cell_2, R.id.cell_3, R.id.cell_4, R.id.cell_5, R.id.cell_6,
        R.id.cell_7, R.id.cell_8, R.id.cell_9, R.id.cell_10, R.id.cell_11, R.id.cell_12, R.id.cell_13,
        R.id.cell_14, R.id.cell_15, R.id.cell_16, R.id.cell_17, R.id.cell_18, R.id.cell_19, R.id.cell_20,
        R.id.cell_21, R.id.cell_22, R.id.cell_23, R.id.cell_24, R.id.cell_25, R.id.cell_26, R.id.cell_27,
        R.id.cell_28, R.id.cell_29, R.id.cell_30, R.id.cell_31, R.id.cell_32, R.id.cell_33, R.id.cell_34,
        R.id.cell_35, R.id.cell_36, R.id.cell_37, R.id.cell_38, R.id.cell_39, R.id.cell_40, R.id.cell_41
    )
    private val NUM = intArrayOf(
        R.id.cell_num_0, R.id.cell_num_1, R.id.cell_num_2, R.id.cell_num_3, R.id.cell_num_4, R.id.cell_num_5, R.id.cell_num_6,
        R.id.cell_num_7, R.id.cell_num_8, R.id.cell_num_9, R.id.cell_num_10, R.id.cell_num_11, R.id.cell_num_12, R.id.cell_num_13,
        R.id.cell_num_14, R.id.cell_num_15, R.id.cell_num_16, R.id.cell_num_17, R.id.cell_num_18, R.id.cell_num_19, R.id.cell_num_20,
        R.id.cell_num_21, R.id.cell_num_22, R.id.cell_num_23, R.id.cell_num_24, R.id.cell_num_25, R.id.cell_num_26, R.id.cell_num_27,
        R.id.cell_num_28, R.id.cell_num_29, R.id.cell_num_30, R.id.cell_num_31, R.id.cell_num_32, R.id.cell_num_33, R.id.cell_num_34,
        R.id.cell_num_35, R.id.cell_num_36, R.id.cell_num_37, R.id.cell_num_38, R.id.cell_num_39, R.id.cell_num_40, R.id.cell_num_41
    )
    private val DOT = intArrayOf(
        R.id.cell_dot_0, R.id.cell_dot_1, R.id.cell_dot_2, R.id.cell_dot_3, R.id.cell_dot_4, R.id.cell_dot_5, R.id.cell_dot_6,
        R.id.cell_dot_7, R.id.cell_dot_8, R.id.cell_dot_9, R.id.cell_dot_10, R.id.cell_dot_11, R.id.cell_dot_12, R.id.cell_dot_13,
        R.id.cell_dot_14, R.id.cell_dot_15, R.id.cell_dot_16, R.id.cell_dot_17, R.id.cell_dot_18, R.id.cell_dot_19, R.id.cell_dot_20,
        R.id.cell_dot_21, R.id.cell_dot_22, R.id.cell_dot_23, R.id.cell_dot_24, R.id.cell_dot_25, R.id.cell_dot_26, R.id.cell_dot_27,
        R.id.cell_dot_28, R.id.cell_dot_29, R.id.cell_dot_30, R.id.cell_dot_31, R.id.cell_dot_32, R.id.cell_dot_33, R.id.cell_dot_34,
        R.id.cell_dot_35, R.id.cell_dot_36, R.id.cell_dot_37, R.id.cell_dot_38, R.id.cell_dot_39, R.id.cell_dot_40, R.id.cell_dot_41
    )

    private const val INK = 0xFFF1F1F2.toInt()
    private const val INK_ON_ACCENT = 0xFF161B2E.toInt()
    private const val OUTSIDE = 0xFF5F667D.toInt()

    /** The grid shared by both sizes. Weeks start on Sunday, like the app's calendar week strip. */
    private fun grid(context: Context, views: RemoteViews, cards: List<BoardCard>, state: MonthState, today: Long, selectable: Boolean, widgetId: Int) {
        val first = state.month.atDay(1)
        val lead = first.dayOfWeek.value % 7
        val start = first.toEpochDay() - lead
        val rows = (lead + state.month.lengthOfMonth() + 6) / 7
        for (r in 0 until 6) views.setViewVisibility(ROW[r], if (r < rows) View.VISIBLE else View.GONE)
        for (i in 0 until rows * 7) {
            val day = start + i
            val date = LocalDate.ofEpochDay(day)
            val isToday = day == today
            val inMonth = YearMonth.from(date) == state.month
            views.setTextViewText(NUM[i], date.dayOfMonth.toString())
            views.setTextColor(NUM[i], if (isToday) INK_ON_ACCENT else if (inMonth) INK else OUTSIDE)
            views.setInt(
                NUM[i], "setBackgroundResource",
                if (isToday) R.drawable.widget_day_today else if (selectable && day == state.selected) R.drawable.widget_day_selected else 0
            )
            val marker = CalendarWidgetData.agenda(cards, day).firstOrNull()
            views.setViewVisibility(DOT[i], if (marker != null) View.VISIBLE else View.INVISIBLE)
            if (marker != null) views.setInt(DOT[i], "setColorFilter", if (inMonth) entryColor(marker.card).toArgb() else OUTSIDE)
            if (selectable) views.setOnClickPendingIntent(CELL[i], broadcast(context, widgetId, i, "day", day))
        }
    }

    internal fun broadcast(context: Context, widgetId: Int, slot: Int, op: String, day: Long = 0L): PendingIntent = PendingIntent.getBroadcast(
        context, widgetId * 100 + slot,
        Intent(context, MonthDetailedWidgetProvider::class.java).setAction(ACTION)
            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId).putExtra(EXTRA_OP, op).putExtra(EXTRA_DAY, day),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    fun renderCompact(context: Context, widgetId: Int, snapshot: BoardSnapshot): RemoteViews {
        val todayDate = LocalDate.now()
        val state = MonthState.today(todayDate)
        val views = RemoteViews(context.packageName, R.layout.widget_month_compact)
        views.setTextViewText(R.id.month_title, state.month.format(DateTimeFormatter.ofPattern("MMMM yyyy", Locale.getDefault())))
        grid(context, views, snapshot.cards, state, todayDate.toEpochDay(), false, widgetId)
        // Anywhere on the widget opens the calendar.
        views.setOnClickPendingIntent(android.R.id.background, WidgetActions.openApp(context, widgetId, 0, WidgetActions.CALENDAR_OPEN, day = todayDate.toEpochDay()))
        return views
    }

    fun renderDetailed(context: Context, widgetId: Int, snapshot: BoardSnapshot): RemoteViews {
        val todayDate = LocalDate.now()
        val today = todayDate.toEpochDay()
        val state = MonthState.load(context, widgetId, todayDate)
        val views = RemoteViews(context.packageName, R.layout.widget_month_detailed)
        views.setTextViewText(R.id.month_title, state.month.format(DateTimeFormatter.ofPattern("MMMM yyyy", Locale.getDefault())))
        grid(context, views, snapshot.cards, state, today, true, widgetId)

        views.setOnClickPendingIntent(R.id.month_prev, broadcast(context, widgetId, 50, "prev"))
        views.setOnClickPendingIntent(R.id.month_next, broadcast(context, widgetId, 51, "next"))
        views.setOnClickPendingIntent(R.id.month_today, broadcast(context, widgetId, 52, "today"))
        views.setOnClickPendingIntent(R.id.month_title, WidgetActions.openApp(context, widgetId, 0, WidgetActions.CALENDAR_OPEN, day = state.selected))
        views.setOnClickPendingIntent(R.id.month_add, WidgetActions.openApp(context, widgetId, 1, WidgetActions.CALENDAR_ADD, day = state.selected))

        // The selected day: label plus its first three entries.
        val entries = CalendarWidgetData.agenda(snapshot.cards, state.selected)
        val label = LocalDate.ofEpochDay(state.selected).format(DateTimeFormatter.ofPattern("EEE, d MMM", Locale.getDefault()))
        val more = entries.size - 3
        views.setTextViewText(
            R.id.month_day_label,
            (if (state.selected == today) "Today, $label" else label) + if (more > 0) "  ·  +$more more" else ""
        )
        val rowIds = intArrayOf(R.id.agenda_row_0, R.id.agenda_row_1, R.id.agenda_row_2)
        val dotIds = intArrayOf(R.id.agenda_dot_0, R.id.agenda_dot_1, R.id.agenda_dot_2)
        val timeIds = intArrayOf(R.id.agenda_time_0, R.id.agenda_time_1, R.id.agenda_time_2)
        val titleIds = intArrayOf(R.id.agenda_title_0, R.id.agenda_title_1, R.id.agenda_title_2)
        for (k in 0 until 3) {
            val entry = entries.getOrNull(k)
            when {
                entry != null -> {
                    views.setViewVisibility(rowIds[k], View.VISIBLE)
                    views.setViewVisibility(dotIds[k], View.VISIBLE)
                    views.setInt(dotIds[k], "setColorFilter", entryColor(entry.card).toArgb())
                    views.setTextViewText(timeIds[k], CalendarWidgetData.timeLabel(context, entry))
                    views.setTextViewText(titleIds[k], CalendarWidgetData.title(entry))
                }
                k == 0 -> {
                    views.setViewVisibility(rowIds[k], View.VISIBLE)
                    views.setViewVisibility(dotIds[k], View.INVISIBLE)
                    views.setTextViewText(timeIds[k], "")
                    views.setTextViewText(titleIds[k], "Nothing planned")
                }
                else -> views.setViewVisibility(rowIds[k], View.GONE)
            }
            views.setOnClickPendingIntent(rowIds[k], WidgetActions.openApp(context, widgetId, 2 + k, WidgetActions.CALENDAR_OPEN, day = state.selected))
        }
        return views
    }
}

/** Compact month: month name, weekday initials and a day grid with a dot under days that have plans. Tap opens the calendar. */
class MonthCompactWidgetProvider : DaymarkWidget() {
    override fun render(context: Context, widgetId: Int, snapshot: BoardSnapshot) = MonthWidgets.renderCompact(context, widgetId, snapshot)
}

/** Detailed month: month arrows, tap-to-select days, and the selected day's first entries below the grid. */
class MonthDetailedWidgetProvider : DaymarkWidget() {
    override fun render(context: Context, widgetId: Int, snapshot: BoardSnapshot) = MonthWidgets.renderDetailed(context, widgetId, snapshot)

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != MonthWidgets.ACTION) return super.onReceive(context, intent)
        val widgetId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        if (widgetId == AppWidgetManager.INVALID_APPWIDGET_ID) return
        val today = LocalDate.now()
        val current = MonthState.load(context, widgetId, today)
        fun shifted(by: Long): MonthState {
            val month = current.month.plusMonths(by)
            return if (month == YearMonth.from(today)) MonthState.today(today) else MonthState(month, month.atDay(1).toEpochDay())
        }
        val next = when (intent.getStringExtra(MonthWidgets.EXTRA_OP)) {
            "prev" -> shifted(-1)
            "next" -> shifted(1)
            "today" -> MonthState.today(today)
            "day" -> intent.getLongExtra(MonthWidgets.EXTRA_DAY, current.selected).let { MonthState(YearMonth.from(LocalDate.ofEpochDay(it)), it) }
            else -> current
        }
        next.save(context, widgetId, today)
        update(context, intArrayOf(widgetId))
    }

    override fun onDeleted(context: Context, widgetIds: IntArray) {
        widgetIds.forEach { WidgetActions.putString(context, it, "month", null) }
    }
}
