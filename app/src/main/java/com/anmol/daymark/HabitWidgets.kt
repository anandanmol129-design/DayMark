package com.anmol.daymark

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Bundle
import android.view.View
import android.widget.RemoteViews
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
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
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/**
 * Habit widgets: one habit's month (a calendar of squares) or past year (a GitHub-style grid of weeks),
 * its streak, and a button that checks today in. The grid is drawn into a bitmap sized to the widget.
 */
object HabitWidgets {
    const val ACTION_CHECK = "com.anmol.daymark.widget.HABIT_CHECK"
    private const val EXTRA_HABIT = "habit"
    private val INK get() = WidgetLook.pick(0xFFF1F1F2.toInt(), 0xFF221A16.toInt())
    private val MUTED get() = WidgetLook.pick(0xFFA9AEC0.toInt(), 0xFF8A7C72.toInt())
    private val EMPTY get() = WidgetLook.pick(0x1FFFFFFF, 0x1A2A1F1A)
    private val FUTURE get() = WidgetLook.pick(0x0AFFFFFF, 0x0A2A1F1A)
    private const val ON_COLOR = 0xFF14201A.toInt()

    fun refreshAll(context: Context) {
        HabitMonthWidgetProvider().refresh(context)
    }

    fun habits(snapshot: BoardSnapshot) = snapshot.cards.filter { it.kind == CardKind.HABIT }.sortedBy { it.createdAt }

    /** The habit a widget shows: the one chosen in its settings, else the first habit. */
    fun habitFor(context: Context, widgetId: Int, snapshot: BoardSnapshot): BoardCard? {
        val all = habits(snapshot)
        val chosen = WidgetActions.string(context, widgetId, "habit")
        return all.firstOrNull { it.id == chosen } ?: if (chosen == null) all.firstOrNull() else null
    }

    fun render(context: Context, widgetId: Int, snapshot: BoardSnapshot, year: Boolean): RemoteViews {
        val views = RemoteViews(context.packageName, WidgetLook.layout(context, R.layout.widget_habit))
        val habit = habitFor(context, widgetId, snapshot)
        views.setOnClickPendingIntent(
            R.id.habit_menu,
            PendingIntent.getActivity(
                context, widgetId * 10 + 8,
                Intent(context, WidgetSetupActivity::class.java).putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        )
        val today = LocalDate.now()
        val todayDay = today.toEpochDay()
        if (habit == null) {
            views.setTextViewText(R.id.habit_title, "Habits")
            views.setViewVisibility(R.id.habit_flame, View.GONE)
            views.setViewVisibility(R.id.habit_streak, View.GONE)
            views.setViewVisibility(R.id.habit_check, View.GONE)
            views.setViewVisibility(R.id.habit_grid, View.GONE)
            views.setViewVisibility(R.id.habit_empty, View.VISIBLE)
            views.setInt(R.id.habit_dot, "setColorFilter", MUTED)
            views.setTextViewText(R.id.habit_sub, "")
            views.setTextViewText(R.id.habit_empty, if (habits(snapshot).isEmpty()) "Add a habit in the Habits tab" else "This habit was deleted. Tap ⋮ to choose another.")
            views.setOnClickPendingIntent(R.id.habit_empty, WidgetActions.openApp(context, widgetId, 7, WidgetActions.HABITS_OPEN))
            return views
        }
        val color = habitArgb(habit)
        val days = habit.checkIns.toHashSet()
        val streak = currentStreak(days, todayDay)
        val checked = todayDay in days
        views.setViewVisibility(R.id.habit_grid, View.VISIBLE)
        views.setViewVisibility(R.id.habit_empty, View.GONE)
        views.setViewVisibility(R.id.habit_flame, View.VISIBLE)
        views.setViewVisibility(R.id.habit_streak, View.VISIBLE)
        views.setViewVisibility(R.id.habit_check, View.VISIBLE)
        views.setTextViewText(R.id.habit_title, habit.title)
        views.setInt(R.id.habit_dot, "setColorFilter", color)
        views.setInt(R.id.habit_flame, "setColorFilter", if (streak > 0) color else MUTED)
        views.setTextViewText(R.id.habit_streak, "$streak")
        views.setTextColor(R.id.habit_streak, if (streak > 0) color else MUTED)
        val sub = if (year) {
            "${checkInsIn(days, todayDay - 364, todayDay)} days in the past year · best ${bestStreak(habit.checkIns)}"
        } else {
            val month = YearMonth.from(today)
            "${month.month.getDisplayName(TextStyle.FULL, Locale.getDefault())} · ${checkInsIn(days, month.atDay(1).toEpochDay(), todayDay)} of ${today.dayOfMonth} days"
        }
        val density = context.resources.displayMetrics.density
        views.setImageViewBitmap(R.id.habit_check, checkBitmap(density, checked, color))
        views.setContentDescription(R.id.habit_check, if (checked) "Undo today's check-in" else "Check in today")
        views.setOnClickPendingIntent(R.id.habit_check, checkPending(context, widgetId, habit.id, year))

        // Size the grid to the widget: portrait uses the min width and the max height.
        val options = AppWidgetManager.getInstance(context).getAppWidgetOptions(widgetId)
        val widthDp = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH).takeIf { it > 0 } ?: if (year) 320 else 180
        val heightDp = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT).takeIf { it > 0 } ?: if (year) 140 else 180
        val gridW = (widthDp - 32).coerceAtLeast(80)
        val gridH = (heightDp - 96).coerceAtLeast(40)
        // Cap the bitmap so it stays well under the widget memory limit.
        val scale = minOf(density, 900f / gridW)
        val stats = listOf(
            "$streak" to "Streak",
            "${bestStreak(habit.checkIns)}" to "Best",
            "${checkInsIn(days, YearMonth.from(today).atDay(1).toEpochDay(), todayDay)}/${today.dayOfMonth}" to "This month"
        )
        val (bitmap, statsDrawn) = if (year) yearBitmap(days, todayDay, color, gridW, gridH, scale) to false else monthBitmap(days, today, color, gridW, gridH, scale, stats)
        // When the stats sit beside the grid, the subtitle only needs the month name.
        views.setTextViewText(R.id.habit_sub, if (statsDrawn) YearMonth.from(today).month.getDisplayName(TextStyle.FULL, Locale.getDefault()) else sub)
        views.setImageViewBitmap(R.id.habit_grid, bitmap)
        views.setOnClickPendingIntent(R.id.habit_grid, WidgetActions.openApp(context, widgetId, 7, WidgetActions.HABITS_OPEN))
        views.setOnClickPendingIntent(R.id.habit_title, WidgetActions.openApp(context, widgetId, 9, WidgetActions.HABITS_OPEN, habit.id))
        return views
    }

    private fun checkPending(context: Context, widgetId: Int, habitId: String, year: Boolean): PendingIntent = PendingIntent.getBroadcast(
        context, widgetId,
        Intent(context, HabitMonthWidgetProvider::class.java)
            .setAction(ACTION_CHECK).putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId).putExtra(EXTRA_HABIT, habitId),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    /** A check-in tap: checks today in (clearing its reminder), or takes today's check-in back. */
    internal fun handleCheck(provider: android.content.BroadcastReceiver, context: Context, intent: Intent) {
        val habitId = intent.getStringExtra(EXTRA_HABIT) ?: return
        val app = context.applicationContext
        with(Reminders) {
            provider.inBackground {
                val store = BoardStore(app)
                val snapshot = store.load()
                val today = LocalDate.now().toEpochDay()
                val habit = snapshot.cards.firstOrNull { it.id == habitId } ?: return@inBackground
                if (!habit.checkedOn(today)) done(app, habitId)
                else {
                    store.save(snapshot.toggleCheckIn(habitId, today, today))
                    WidgetActions.refreshAll(app)
                    externalEdits.value++
                }
            }
        }
    }

    private fun checkBitmap(density: Float, checked: Boolean, color: Int): Bitmap {
        val size = (30 * density).toInt()
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val r = size / 2f
        if (checked) {
            paint.color = color
            canvas.drawCircle(r, r, r, paint)
            paint.color = ON_COLOR
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 2.4f * density
            paint.strokeCap = Paint.Cap.ROUND
            paint.strokeJoin = Paint.Join.ROUND
            val s = size / 30f
            canvas.drawPath(Path().apply { moveTo(9 * s, 15.5f * s); lineTo(13.2f * s, 19.5f * s); lineTo(21 * s, 11 * s) }, paint)
        } else {
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 1.6f * density
            paint.color = WidgetLook.pick(0x99A9AEC0.toInt(), 0x998A7C72.toInt())
            canvas.drawCircle(r, r, r - paint.strokeWidth, paint)
        }
        return bitmap
    }

    private fun square(canvas: Canvas, paint: Paint, x: Float, y: Float, cell: Float, fill: Int, today: Boolean, scale: Float) {
        val rect = RectF(x, y, x + cell, y + cell)
        val radius = cell * .22f
        paint.style = Paint.Style.FILL
        paint.color = fill
        canvas.drawRoundRect(rect, radius, radius, paint)
        if (today) {
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 1.4f * scale
            paint.color = WidgetLook.pick(0xCCF1F1F2.toInt(), 0xCC221A16.toInt())
            val inset = paint.strokeWidth / 2
            canvas.drawRoundRect(RectF(rect.left + inset, rect.top + inset, rect.right - inset, rect.bottom - inset), radius, radius, paint)
        }
    }

    /**
     * This month as a calendar of squares, weeks starting Sunday, with weekday initials on top. The grid sits on the left; when the
     * widget is wide enough, [stats] fill the space on its right. Returns the bitmap and whether the stats were drawn.
     */
    private fun monthBitmap(days: Set<Long>, today: LocalDate, color: Int, wDp: Int, hDp: Int, scale: Float, stats: List<Pair<String, String>>): Pair<Bitmap, Boolean> {
        val w = (wDp * scale).toInt().coerceAtLeast(1)
        val h = (hDp * scale).toInt().coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val month = YearMonth.from(today)
        val first = month.atDay(1)
        val lead = first.dayOfWeek.value % 7
        val rows = (lead + month.lengthOfMonth() + 6) / 7
        val labelH = 12 * scale
        val gapRatio = .18f
        val byHeight = ((h - labelH) / (rows + (rows - 1) * gapRatio)).coerceAtMost(40 * scale)
        // Wide widgets put the stats on the right, like a calendar with a summary beside it.
        val panelW = 96 * scale
        val besideCell = minOf(byHeight, (w - panelW - 16 * scale) / (7 + 6 * gapRatio))
        val side = besideCell >= 22 * scale
        val cell = if (side) besideCell else minOf(w / (7 + 6 * gapRatio), byHeight)
        val gap = cell * gapRatio
        val gridW = cell * 7 + gap * 6
        val left = if (side) 0f else (w - gridW) / 2
        // Top-aligned under the subtitle; any spare height goes below.
        val top = labelH
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
        text.textSize = 9 * scale
        text.color = MUTED
        listOf("S", "M", "T", "W", "T", "F", "S").forEachIndexed { i, label -> canvas.drawText(label, left + i * (cell + gap) + cell / 2, top - 4 * scale, text) }
        val todayDay = today.toEpochDay()
        val numbers = cell >= 15 * scale
        text.textSize = (cell * .38f).coerceAtMost(12 * scale)
        for (i in 0 until month.lengthOfMonth()) {
            val slot = lead + i
            val day = first.toEpochDay() + i
            val x = left + (slot % 7) * (cell + gap)
            val y = top + (slot / 7) * (cell + gap)
            val done = day in days
            square(canvas, paint, x, y, cell, when { done -> color; day > todayDay -> FUTURE; else -> EMPTY }, day == todayDay, scale)
            if (numbers) {
                text.color = when { done -> ON_COLOR; day > todayDay -> WidgetLook.pick(0x55A9AEC0, 0x558A7C72); else -> MUTED }
                text.typeface = if (done) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                canvas.drawText("${i + 1}", x + cell / 2, y + cell / 2 - (text.descent() + text.ascent()) / 2, text)
            }
        }
        if (side) {
            // Stats stacked in the free space, spread over the grid's height.
            val panelLeft = gridW + ((w - gridW) - panelW) / 2 + 8 * scale
            val gridBottom = top + cell * rows + gap * (rows - 1)
            val value = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 19 * scale; typeface = Typeface.DEFAULT_BOLD }
            val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 11 * scale; this.color = MUTED }
            val blockH = 19 * scale + 15 * scale
            val spacing = ((gridBottom - (top - labelH)) - blockH * stats.size) / (stats.size + 1)
            stats.forEachIndexed { i, (v, l) ->
                val y = top - labelH + spacing * (i + 1) + blockH * i
                value.setColor(if (i == 0 && v != "0") color else INK)
                canvas.drawText(v, panelLeft, y + 19 * scale, value)
                canvas.drawText(l, panelLeft, y + blockH, label)
            }
        }
        return bitmap to side
    }

    /**
     * The past 53 weeks, a column per week with Sunday on top and the newest week last; month names above. A tall widget wraps the
     * weeks into two or three bands (oldest first) so the squares can grow and fill the space instead of sitting in a thin strip.
     */
    private fun yearBitmap(days: Set<Long>, today: Long, color: Int, wDp: Int, hDp: Int, scale: Float): Bitmap {
        val w = (wDp * scale).toInt().coerceAtLeast(1)
        val h = (hDp * scale).toInt().coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val weeks = 53
        val labelH = 12 * scale
        val bandGap = 6 * scale
        val gapRatio = .22f
        fun cellFor(bands: Int): Float {
            val perBand = (weeks + bands - 1) / bands
            return minOf(w / (perBand + (perBand - 1) * gapRatio), (h - bands * labelH - (bands - 1) * bandGap) / (bands * (7 + 6 * gapRatio)))
        }
        val bands = (1..3).maxBy { cellFor(it) }
        val perBand = (weeks + bands - 1) / bands
        val cell = cellFor(bands)
        val gap = cell * gapRatio
        val gridW = cell * perBand + gap * (perBand - 1)
        val bandH = labelH + cell * 7 + gap * 6
        val left = 0f // Lines up with the title and subtitle above.
        val firstTop = 0f
        val thisWeek = today - (LocalDate.ofEpochDay(today).dayOfWeek.value % 7)
        val start = thisWeek - (weeks - 1) * 7
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 9 * scale; this.color = MUTED }
        val monthFormat = DateTimeFormatter.ofPattern("MMM", Locale.getDefault())
        for (band in 0 until bands) {
            val top = firstTop + band * (bandH + bandGap) + labelH
            var lastLabelEnd = -1f
            for (column in 0 until perBand) {
                val week = band * perBand + column
                if (week >= weeks) break
                val weekStart = start + week * 7
                val x = left + column * (cell + gap)
                val date = LocalDate.ofEpochDay(weekStart)
                if ((date.dayOfMonth <= 7 || column == 0) && x > lastLabelEnd) {
                    val label = date.format(monthFormat)
                    if (x + text.measureText(label) <= w) { canvas.drawText(label, x, top - 4 * scale, text); lastLabelEnd = x + text.measureText(label) + 4 * scale }
                }
                for (d in 0 until 7) {
                    val day = weekStart + d
                    if (day > today) continue
                    square(canvas, paint, x, top + d * (cell + gap), cell, if (day in days) color else EMPTY, day == today, scale)
                }
            }
        }
        return bitmap
    }
}

/**
 * Habit widget: one habit's month as a calendar of coloured squares, or its past year as a grid of weeks, with
 * its streak and a check-in button. (The class keeps its old name so placed widgets keep working.)
 */
class HabitMonthWidgetProvider : DaymarkWidget() {
    override val kind = WidgetKind.HABIT
    override fun render(context: Context, widgetId: Int, snapshot: BoardSnapshot) =
        HabitWidgets.render(context, widgetId, snapshot, year = WidgetLook.style(context, widgetId, kind).view == WidgetView.YEAR)
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == HabitWidgets.ACTION_CHECK) HabitWidgets.handleCheck(this, context, intent) else super.onReceive(context, intent)
    }
    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, widgetId: Int, newOptions: Bundle) = update(context, intArrayOf(widgetId))
    override fun onDeleted(context: Context, widgetIds: IntArray) {
        super.onDeleted(context, widgetIds)
        widgetIds.forEach { WidgetActions.putString(context, it, "habit", null) }
    }
}
