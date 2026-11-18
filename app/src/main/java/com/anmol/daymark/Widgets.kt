package com.anmol.daymark

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Text sizes shared by every Daymark widget: body text, header text, and row height. */
enum class WidgetTextSize(val id: String, val label: String, val taskSp: Float, val headerSp: Float, val rowDp: Int) {
    SMALL("small", "Small", 13.5f, 16f, 32),
    MEDIUM("medium", "Medium", 15f, 18f, 36),
    LARGE("large", "Large", 17f, 20f, 40);

    companion object { fun fromId(id: String?) = entries.firstOrNull { it.id == id } ?: MEDIUM }
}

/** What a widget tap asks the app to do once it opens: [target] is a folder or note id. */
data class WidgetRequest(val action: String, val target: String = "", val day: Long? = null)

object WidgetActions {
    const val TASKS_OPEN = "com.anmol.daymark.widget.OPEN"
    const val TASKS_ADD = "com.anmol.daymark.widget.ADD"
    const val CALENDAR_OPEN = "com.anmol.daymark.widget.CALENDAR_OPEN"
    const val CALENDAR_ADD = "com.anmol.daymark.widget.CALENDAR_ADD"
    const val NOTE_OPEN = "com.anmol.daymark.widget.NOTE_OPEN"
    /** Opens one card (target = card id) on its edit sheet. */
    const val CARD_EDIT = "com.anmol.daymark.widget.CARD_EDIT"
    /** Opens the Habits tab (target = a habit id to edit, or ""). */
    const val HABITS_OPEN = "com.anmol.daymark.widget.HABITS_OPEN"
    private const val EXTRA_TARGET = "folder"
    private const val EXTRA_DAY = "day"
    private const val PREFS = "daymark_settings"
    private val appActions = setOf(TASKS_OPEN, TASKS_ADD, CALENDAR_OPEN, CALENDAR_ADD, NOTE_OPEN, CARD_EDIT, HABITS_OPEN)

    fun requestFrom(intent: Intent?): WidgetRequest? {
        val action = intent?.action?.takeIf { it in appActions } ?: return null
        return WidgetRequest(action, intent.getStringExtra(EXTRA_TARGET).orEmpty(), intent.getLongExtra(EXTRA_DAY, Long.MIN_VALUE).takeIf { it != Long.MIN_VALUE })
    }

    /**
     * A tap that opens the app; [slot] keeps each button of each widget its own PendingIntent.
     * List templates must be [mutable] so each row can fill in its own extras.
     */
    fun openApp(context: Context, widgetId: Int, slot: Int, action: String, target: String = "", day: Long? = null, mutable: Boolean = false): PendingIntent =
        PendingIntent.getActivity(
            context, widgetId * 10 + slot, appIntent(context, action, target, day),
            PendingIntent.FLAG_UPDATE_CURRENT or if (mutable) PendingIntent.FLAG_MUTABLE else PendingIntent.FLAG_IMMUTABLE
        )

    /** The intent [openApp] wraps; also used directly by activities that open the app (the quick task sheet). */
    fun appIntent(context: Context, action: String, target: String = "", day: Long? = null): Intent =
        Intent(context, MainActivity::class.java).setAction(action).putExtra(EXTRA_TARGET, target)
            .apply { if (day != null) putExtra(EXTRA_DAY, day) }
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)

    /** The per-row extras a list template needs to open the calendar on [day]. */
    fun dayFillIn(day: Long): Intent = Intent().putExtra(EXTRA_DAY, day)

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun string(context: Context, widgetId: Int, key: String): String? = prefs(context).getString("widget_${key}_$widgetId", null)

    fun putString(context: Context, widgetId: Int, key: String, value: String?) = prefs(context).edit().apply {
        if (value == null) remove("widget_${key}_$widgetId") else putString("widget_${key}_$widgetId", value)
    }.apply()

    fun sizeFor(context: Context, widgetId: Int) = WidgetTextSize.fromId(string(context, widgetId, "size"))

    fun setSize(context: Context, widgetId: Int, size: WidgetTextSize?) = putString(context, widgetId, "size", size?.id)

    /** Redraws every Daymark widget; called after each save. */
    fun refreshAll(context: Context) {
        TaskListWidgetProvider.refresh(context)
        MonthWidgets.refreshAll(context)
        EventsWidgets.refreshAll(context)
        NoteWidgetProvider.refresh(context)
        HabitWidgets.refreshAll(context)
        WidgetClock.schedule(context)
    }
}

/**
 * The settings card every widget uses: a live [preview] of fixed height (so nothing
 * shifts under a finger), text-size chips, optional [choices] below, then Done.
 */
@Composable
fun WidgetSettingsScreen(
    size: WidgetTextSize,
    onSize: (WidgetTextSize) -> Unit,
    onDismiss: () -> Unit,
    onDone: () -> Unit,
    preview: @Composable ColumnScope.() -> Unit,
    choices: (@Composable ColumnScope.() -> Unit)? = null
) {
    Box(Modifier.fillMaxSize().background(Color(0x99000000)).clickable(onClick = onDismiss), contentAlignment = Alignment.Center) {
        Column(
            Modifier.widthIn(max = 360.dp).fillMaxWidth().padding(20.dp).clip(RoundedCornerShape(26.dp))
                .background(SheetSolid).clickable(enabled = false) {}.padding(vertical = 18.dp)
        ) {
            Text("Widget settings", color = TodoText, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 22.dp))
            Column(
                Modifier.padding(horizontal = 18.dp, vertical = 14.dp).fillMaxWidth().height(172.dp)
                    .clip(RoundedCornerShape(18.dp)).background(SheetRaised).padding(vertical = 8.dp),
                content = preview
            )
            WidgetSettingsLabel("TEXT SIZE")
            Row(Modifier.padding(horizontal = 18.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                WidgetTextSize.entries.forEach { option ->
                    val selected = option == size
                    Box(
                        Modifier.weight(1f).clip(RoundedCornerShape(14.dp))
                            .background(if (selected) PaleGreen else Color.Transparent)
                            .border(1.dp, if (selected) TodoBlue else veil(0.20f), RoundedCornerShape(14.dp))
                            .clickable { onSize(option) }.padding(vertical = 10.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(option.label, color = if (selected) TodoText else TodoMuted, fontSize = 14.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
                    }
                }
            }
            choices?.invoke(this)
            Box(
                Modifier.padding(start = 18.dp, end = 18.dp, top = 12.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(TodoBlue)
                    .clickable(onClick = onDone).padding(vertical = 13.dp),
                contentAlignment = Alignment.Center
            ) {
                Text("Done", color = OnAccent, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
fun WidgetSettingsLabel(text: String) {
    Text(text, color = TodoMuted, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp, modifier = Modifier.padding(start = 22.dp, end = 22.dp, top = 12.dp, bottom = 4.dp))
}

/** One selectable row in a widget's settings list. */
@Composable
fun WidgetChoiceRow(label: String, selected: Boolean, hint: String? = null, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 22.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, color = if (selected) TodoBlue else TodoText, fontSize = 16.sp, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            if (hint != null) Text(hint, color = TodoMuted, fontSize = 12.sp, maxLines = 1)
        }
        if (selected) Glyph(ZenIcon.Check, TodoBlue, 16.dp)
    }
}
