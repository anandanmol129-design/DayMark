package com.anmol.daymark

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * One settings page for every widget: the view (Compact/Detailed or Month/Year), Light or Dark, the accent,
 * text size where it applies, and what it shows (folder, habit or note), over a live preview in that style.
 * Opens when a widget is placed, from the launcher's widget settings, and from the widget's own header button.
 */
class WidgetSetupActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val widgetId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        // Backing out of first-time setup still keeps the widget, with the defaults.
        setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId))
        val kind = WidgetKind.of(this, widgetId)
        if (widgetId == AppWidgetManager.INVALID_APPWIDGET_ID || kind == null) { finish(); return }
        val snapshot = BoardStore(this).load()

        // The page itself follows the app's theme, in the widget's tab colour.
        Look.load(this)
        Look.accent = when (kind) { WidgetKind.TASKS -> Accents.TODOS; WidgetKind.MONTH, WidgetKind.EVENTS -> Accents.CALENDAR; WidgetKind.HABIT -> Accents.TRACK; WidgetKind.NOTE -> Accents.NOTES }
        setContent {
            WidgetSetupScreen(kind, widgetId, snapshot, onDismiss = ::finish) { finish() }
        }
    }
}

@Composable
private fun WidgetSetupScreen(kind: WidgetKind, widgetId: Int, snapshot: BoardSnapshot, onDismiss: () -> Unit, onSaved: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var style by remember { mutableStateOf(WidgetLook.style(context, widgetId, kind)) }
    var size by remember { mutableStateOf(WidgetActions.sizeFor(context, widgetId)) }
    val pages = snapshot.todoPages
    val notes = remember { NoteWidgetProvider.pinnable(snapshot) }
    val habits = remember { HabitWidgets.habits(snapshot) }
    var folderId by remember { mutableStateOf(TaskListWidgetProvider.folderFor(context, widgetId)?.takeIf { id -> pages.any { it.id == id } } ?: pages.first().id) }
    var noteId by remember { mutableStateOf(NoteWidgetProvider.noteFor(context, widgetId, snapshot)?.id ?: notes.firstOrNull()?.id) }
    var habitId by remember { mutableStateOf(HabitWidgets.habitFor(context, widgetId, snapshot)?.id ?: habits.firstOrNull()?.id) }

    fun save() {
        WidgetLook.save(context, widgetId, style)
        if (kind.usesTextSize) WidgetActions.setSize(context, widgetId, size)
        val manager = AppWidgetManager.getInstance(context)
        when (kind) {
            WidgetKind.TASKS -> { TaskListWidgetProvider.setFolder(context, widgetId, folderId); TaskListWidgetProvider.update(context, manager, widgetId) }
            WidgetKind.NOTE -> { WidgetActions.putString(context, widgetId, "note", noteId); NoteWidgetProvider.update(context, manager, widgetId) }
            WidgetKind.HABIT -> { WidgetActions.putString(context, widgetId, "habit", habitId); HabitMonthWidgetProvider().update(context, intArrayOf(widgetId)) }
            WidgetKind.MONTH -> MonthDetailedWidgetProvider().update(context, intArrayOf(widgetId))
            WidgetKind.EVENTS -> EventsDetailedWidgetProvider().update(context, intArrayOf(widgetId))
        }
        onSaved()
    }

    Box(Modifier.fillMaxSize().background(Color(0x99000000)).clickable(onClick = onDismiss), contentAlignment = Alignment.Center) {
        Column(
            Modifier.widthIn(max = 380.dp).fillMaxWidth().padding(18.dp).clip(RoundedCornerShape(26.dp))
                .background(SheetSolid).clickable(enabled = false) {}.padding(vertical = 18.dp)
        ) {
            Text("${kind.title} widget", color = TodoText, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 22.dp))
            WidgetPreview(
                kind, style, size, snapshot,
                folder = pages.firstOrNull { it.id == folderId }, note = notes.firstOrNull { it.id == noteId }, habit = habits.firstOrNull { it.id == habitId },
                modifier = Modifier.padding(horizontal = 18.dp, vertical = 14.dp)
            )
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                if (kind.views.isNotEmpty()) {
                    WidgetSettingsLabel("VIEW")
                    Pills(kind.views.map { it to it.label }, style.view) { style = style.copy(view = it) }
                }
                WidgetSettingsLabel("THEME")
                Pills(listOf(true to "Light", false to "Dark"), style.light) { style = style.copy(light = it) }
                if (kind.usesAccent) {
                    WidgetSettingsLabel("ACCENT")
                    AccentSwatches(style) { style = style.copy(accent = it) }
                }
                if (kind.usesTextSize) {
                    WidgetSettingsLabel("TEXT SIZE")
                    Pills(WidgetTextSize.entries.map { it to it.label }, size) { size = it }
                }
                when (kind) {
                    WidgetKind.TASKS -> {
                        WidgetSettingsLabel("FOLDER")
                        pages.forEach { page -> WidgetChoiceRow(page.name, page.id == folderId) { folderId = page.id } }
                    }
                    WidgetKind.NOTE -> {
                        WidgetSettingsLabel("NOTE")
                        if (notes.isEmpty()) Hint("No notes yet. Write one in the Notes tab.")
                        val folders = remember { snapshot.columns.associate { column -> column.id to (snapshot.notesPages.firstOrNull { it.id == column.pageId }?.name ?: column.name) } }
                        notes.forEach { note -> WidgetChoiceRow(note.displayTitle, note.id == noteId, hint = folders[note.columnId]) { noteId = note.id } }
                    }
                    WidgetKind.HABIT -> {
                        WidgetSettingsLabel("HABIT")
                        if (habits.isEmpty()) Hint("No habits yet. Add one in the Habits tab and it will show here.")
                        habits.forEach { habit -> HabitChoiceRow(habit, habit.id == habitId) { habitId = habit.id } }
                    }
                    else -> {}
                }
            }
            Box(
                Modifier.padding(start = 18.dp, end = 18.dp, top = 14.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(CalendarBlue)
                    .clickable(onClick = ::save).padding(vertical = 13.dp),
                contentAlignment = Alignment.Center
            ) { Text("Done", color = OnFill, fontSize = 16.sp, fontWeight = FontWeight.SemiBold) }
        }
    }
}

@Composable
private fun Hint(text: String) = Text(text, color = TodoMuted, fontSize = 14.sp, modifier = Modifier.padding(horizontal = 22.dp, vertical = 8.dp))

/** A row of pill options with the chosen one filled, like the app's Settings. */
@Composable
private fun <T> Pills(options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit) {
    Row(Modifier.padding(horizontal = 18.dp, vertical = 4.dp).fillMaxWidth().clip(RoundedCornerShape(50)).background(TodoCardRaised).padding(3.dp)) {
        options.forEach { (value, label) ->
            val active = value == selected
            Box(
                Modifier.weight(1f).clip(RoundedCornerShape(50)).background(if (active) CalendarBlue else Color.Transparent)
                    .clickable { onSelect(value) }.padding(vertical = 8.dp),
                contentAlignment = Alignment.Center
            ) { Text(label, color = if (active) OnFill else TodoMuted, fontSize = 14.sp, fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium) }
        }
    }
}

/** The five accent colours, each shown as it will look on the chosen theme. */
@Composable
private fun AccentSwatches(style: WidgetStyle, onSelect: (WidgetAccent) -> Unit) {
    Row(Modifier.padding(horizontal = 22.dp, vertical = 6.dp).fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        WidgetAccent.entries.forEach { accent ->
            val shown = style.copy(accent = accent)
            val selected = accent == style.accent
            Box(
                Modifier.size(40.dp).clip(CircleShape).border(2.dp, if (selected) TodoText else Color.Transparent, CircleShape).padding(4.dp)
                    .clip(CircleShape).background(shown.accentFill).clickable { onSelect(accent) }
                    .semantics { contentDescription = accent.label },
                contentAlignment = Alignment.Center
            ) { if (selected) Glyph(ZenIcon.Check, shown.onFill, 15.dp) }
        }
    }
}

@Composable
private fun HabitChoiceRow(habit: BoardCard, selected: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 22.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(habitColor(habit)))
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(habit.title, color = if (selected) TodoBlue else TodoText, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("${currentStreak(habit.checkIns, LocalDate.now().toEpochDay())} day streak · ${habitSummary(habit)}", color = TodoMuted, fontSize = 12.sp, maxLines = 1)
        }
        if (selected) Glyph(ZenIcon.Check, TodoBlue, 16.dp)
    }
}

/** A small live mock of the widget, drawn in [style] (not the app's theme), so choices show as you make them. */
@Composable
private fun WidgetPreview(
    kind: WidgetKind, style: WidgetStyle, size: WidgetTextSize, snapshot: BoardSnapshot,
    folder: BoardPage?, note: BoardCard?, habit: BoardCard?, modifier: Modifier = Modifier
) {
    val shape = RoundedCornerShape(20.dp)
    Column(
        modifier.fillMaxWidth().height(196.dp).clip(shape).background(style.background).border(BorderStroke(1.dp, style.outline), shape)
    ) {
        when (kind) {
            WidgetKind.TASKS -> TasksPreview(style, size, snapshot, folder)
            WidgetKind.MONTH -> MonthPreview(style, snapshot)
            WidgetKind.EVENTS -> EventsPreview(style, snapshot)
            WidgetKind.HABIT -> HabitPreview(style, habit)
            WidgetKind.NOTE -> NotePreview(style, size, note)
        }
    }
}

@Composable
private fun ColumnScope.TasksPreview(style: WidgetStyle, size: WidgetTextSize, snapshot: BoardSnapshot, folder: BoardPage?) {
    val tasks = remember(folder) { folder?.let { TaskListWidgetProvider.openTasksOf(snapshot, it) }.orEmpty().take(5) }
    val titles = tasks.map { it.title }.ifEmpty { listOf("Buy milk", "Call the bank", "Plan the week") }
    val name = folder?.name ?: "Inbox"
    if (style.view == WidgetView.COMPACT) {
        Row(Modifier.fillMaxWidth().background(style.header).padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(18.dp).clip(RoundedCornerShape(5.dp)).background(style.accentFill), contentAlignment = Alignment.Center) { Glyph(ZenIcon.Check, style.onFill, 12.dp) }
            Text(name, color = style.accentInk, fontSize = 15.sp, modifier = Modifier.padding(start = 10.dp))
            Glyph(ZenIcon.ChevronDown, style.accentInk, 14.dp, modifier = Modifier.padding(start = 4.dp))
            Spacer(Modifier.weight(1f))
            Box(Modifier.size(24.dp).clip(CircleShape).background(style.accentFill), contentAlignment = Alignment.Center) { Glyph(ZenIcon.Plus, style.onFill, 15.dp) }
        }
        val today = LocalDate.now().toEpochDay()
        titles.forEachIndexed { i, title ->
            Row(Modifier.padding(horizontal = 14.dp).height((size.rowDp - 8).dp), verticalAlignment = Alignment.CenterVertically) {
                Text(title, color = style.ink, fontSize = (size.taskSp - 1).sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Text(TaskCompactWidget.dueLabel(tasks.getOrNull(i)?.dueDay, today), color = style.muted, fontSize = 12.sp)
            }
        }
    } else {
        Row(Modifier.fillMaxWidth().background(style.header).padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(22.dp).clip(RoundedCornerShape(6.dp)).background(style.accentFill), contentAlignment = Alignment.Center) { Glyph(ZenIcon.Check, style.onFill, 14.dp) }
            Text(name, color = style.accentInk, fontSize = size.headerSp.sp, modifier = Modifier.padding(start = 10.dp))
            Glyph(ZenIcon.ChevronDown, style.accentInk, 16.dp, modifier = Modifier.padding(start = 4.dp))
            Spacer(Modifier.weight(1f))
            Box(Modifier.size(30.dp).clip(CircleShape).background(style.accentFill), contentAlignment = Alignment.Center) { Glyph(ZenIcon.Plus, style.onFill, 18.dp) }
        }
        titles.forEach { title ->
            Row(Modifier.padding(horizontal = 14.dp).height(size.rowDp.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(18.dp).border(1.6.dp, style.muted, CircleShape))
                Text(title, color = style.ink, fontSize = size.taskSp.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 12.dp))
            }
        }
    }
}

@Composable
private fun ColumnScope.MonthPreview(style: WidgetStyle, snapshot: BoardSnapshot) {
    val today = LocalDate.now()
    val month = YearMonth.from(today)
    val detailed = style.view != WidgetView.COMPACT
    Row(Modifier.padding(start = 14.dp, end = 12.dp, top = 10.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(month.format(DateTimeFormatter.ofPattern("MMMM yyyy", Locale.getDefault())), color = style.accentInk, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        if (detailed) {
            Glyph(ZenIcon.ChevronLeft, style.ink, 15.dp)
            Spacer(Modifier.width(10.dp))
            Glyph(ZenIcon.ChevronRight, style.ink, 15.dp)
            Spacer(Modifier.width(10.dp))
            Box(Modifier.size(22.dp).clip(CircleShape).background(style.accentFill), contentAlignment = Alignment.Center) { Glyph(ZenIcon.Plus, style.onFill, 14.dp) }
        }
    }
    val lead = month.atDay(1).dayOfWeek.value % 7
    val rows = (lead + month.lengthOfMonth() + 6) / 7
    val cell = if (detailed) 17.dp else 21.dp
    Row(Modifier.padding(horizontal = 10.dp)) {
        listOf("S", "M", "T", "W", "T", "F", "S").forEach { Text(it, color = style.muted, fontSize = 10.sp, modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.Center) }
    }
    for (r in 0 until rows) {
        Row(Modifier.padding(horizontal = 10.dp).height(cell)) {
            for (c in 0 until 7) {
                val dayOfMonth = r * 7 + c - lead + 1
                Box(Modifier.weight(1f).fillMaxSize(), contentAlignment = Alignment.Center) {
                    if (dayOfMonth in 1..month.lengthOfMonth()) {
                        val date = month.atDay(dayOfMonth)
                        val isToday = date == today
                        Box(Modifier.size(cell - 3.dp).clip(CircleShape).background(if (isToday) style.accentFill else Color.Transparent), contentAlignment = Alignment.Center) {
                            Text("$dayOfMonth", color = if (isToday) style.onFill else style.ink, fontSize = 10.sp)
                        }
                    }
                }
            }
        }
    }
    if (detailed) {
        val first = remember { CalendarWidgetData.agenda(snapshot.cards, today.toEpochDay()).firstOrNull() }
        Text(
            "Today, " + today.format(DateTimeFormatter.ofPattern("EEE, d MMM", Locale.getDefault())) + "  ·  " + (first?.card?.title ?: "Nothing planned"),
            color = style.muted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp)
        )
    }
}

@Composable
private fun ColumnScope.EventsPreview(style: WidgetStyle, snapshot: BoardSnapshot) {
    val today = LocalDate.now().toEpochDay()
    val detailed = style.view != WidgetView.COMPACT
    val entries = remember { (today..today + 6).flatMap { day -> CalendarWidgetData.agenda(snapshot.cards, day).filter { !it.done }.map { day to it } }.take(5) }
    Row(Modifier.fillMaxWidth().background(style.header).padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(if (detailed) "Next 7 days" else LocalDate.now().format(DateTimeFormatter.ofPattern("EEE, d MMM", Locale.getDefault())), color = style.accentInk, fontSize = 16.sp, modifier = Modifier.weight(1f))
        Box(Modifier.size(26.dp).clip(CircleShape).background(style.accentFill), contentAlignment = Alignment.Center) { Glyph(ZenIcon.Plus, style.onFill, 16.dp) }
    }
    if (entries.isEmpty()) Text("Nothing coming up", color = style.muted, fontSize = 13.sp, modifier = Modifier.padding(14.dp))
    var lastDay = -1L
    entries.forEach { (day, entry) ->
        if (detailed && day != lastDay) {
            Text(CalendarWidgetData.dayHeading(day, today), color = style.accentInk, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 14.dp, top = 6.dp))
            lastDay = day
        }
        Row(Modifier.padding(horizontal = 14.dp, vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(7.dp).clip(CircleShape).background(entryColor(entry.card)))
            Text(entry.minute?.let(::formatClock) ?: "All day", color = style.muted, fontSize = 12.sp, modifier = Modifier.padding(start = 8.dp).width(64.dp))
            Text(entry.card.title, color = style.ink, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun ColumnScope.HabitPreview(style: WidgetStyle, habit: BoardCard?) {
    val color = habit?.let(::habitColor) ?: style.accentFill
    val days = remember(habit) { habit?.checkIns?.toHashSet().orEmpty() }
    val today = LocalDate.now().toEpochDay()
    Row(Modifier.padding(start = 14.dp, end = 14.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(9.dp).clip(CircleShape).background(color))
        Text(habit?.title ?: "Habit", color = style.ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 8.dp).weight(1f))
        Glyph(ZenIcon.Flame, color, 14.dp)
        Text(" ${currentStreak(days, today)}", color = style.ink, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
    val empty = style.ink.copy(alpha = .1f)
    val year = style.view == WidgetView.YEAR
    val cols = if (year) 30 else 7
    val rows = if (year) 7 else 5
    val square = if (year) 8.dp else 18.dp
    // Most recent day in the bottom-right, like the widget.
    Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(if (year) 2.dp else 4.dp)) {
        for (r in 0 until rows) {
            Row(horizontalArrangement = Arrangement.spacedBy(if (year) 2.dp else 4.dp)) {
                for (c in 0 until cols) {
                    val back = if (year) (cols - 1 - c) * 7L + (rows - 1 - r) else (rows - 1 - r) * 7L + (cols - 1 - c)
                    Box(Modifier.size(square).clip(RoundedCornerShape(square * .22f)).background(if ((today - back) in days) color else empty))
                }
            }
        }
    }
}

@Composable
private fun ColumnScope.NotePreview(style: WidgetStyle, size: WidgetTextSize, note: BoardCard?) {
    val lines = remember(note) { note?.let(NoteWidgetProvider::linesOf).orEmpty().take(7) }
    if (lines.isEmpty()) Text("No notes yet", color = style.muted, fontSize = 14.sp, modifier = Modifier.padding(16.dp))
    Column(Modifier.padding(vertical = 10.dp)) {
        lines.forEach { line ->
            Text(
                line.text, color = if (line.title) style.accentInk else style.ink, maxLines = 1, overflow = TextOverflow.Ellipsis,
                fontSize = (if (line.title) size.headerSp else size.taskSp).sp, fontWeight = if (line.bold) FontWeight.Bold else FontWeight.Normal,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 1.dp)
            )
        }
    }
}
