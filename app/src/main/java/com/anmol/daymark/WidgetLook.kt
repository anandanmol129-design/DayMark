package com.anmol.daymark

import android.content.Context
import androidx.compose.ui.graphics.Color

/** A widget's accent colour. The order must match accentNames in app/build.gradle.kts. */
enum class WidgetAccent(val id: String, val label: String, val lightFill: Long, val lightInk: Long, val lightOnFill: Long, val lightContainer: Long, val dark: Long, val darkContainer: Long) {
    CORAL("coral", "Coral", 0xFFFF5A5F, 0xFFD7263D, 0xFFFFFFFF, 0xFFFFE2E0, 0xFFFF8A80, 0xFF3A1F2A),
    BLUE("blue", "Blue", 0xFF3D5AFE, 0xFF2F4AE8, 0xFFFFFFFF, 0xFFE1E6FF, 0xFF9DB8FF, 0xFF1E2440),
    SUNFLOWER("sunflower", "Sunflower", 0xFFFFC23C, 0xFF9A6200, 0xFF2B1D00, 0xFFFFF0C4, 0xFFFFD36B, 0xFF35301F),
    MINT("mint", "Mint", 0xFF1DB97A, 0xFF0B8A56, 0xFFFFFFFF, 0xFFD3F5E5, 0xFF6FE0B0, 0xFF17352C),
    GRAPE("grape", "Grape", 0xFF7C4DFF, 0xFF6236E0, 0xFFFFFFFF, 0xFFEAE2FF, 0xFFB49CFF, 0xFF2A2346);

    companion object { fun fromId(id: String?) = entries.firstOrNull { it.id == id } }
}

/** One way a widget can lay itself out. */
enum class WidgetView(val id: String, val label: String) {
    COMPACT("compact", "Compact"), DETAILED("detailed", "Detailed"), MONTH("month", "Month"), YEAR("year", "Year");

    companion object { fun fromId(id: String?) = entries.firstOrNull { it.id == id } }
}

/** The five widgets in the picker: the views each offers (the first placed ones were the detailed or month view) and its tab's colour. */
enum class WidgetKind(val title: String, val views: List<WidgetView>, val defaultView: WidgetView?, val defaultAccent: WidgetAccent, val usesTextSize: Boolean, val usesAccent: Boolean = true) {
    TASKS("Tasks", listOf(WidgetView.COMPACT, WidgetView.DETAILED), WidgetView.DETAILED, WidgetAccent.CORAL, true),
    MONTH("Calendar month", listOf(WidgetView.COMPACT, WidgetView.DETAILED), WidgetView.DETAILED, WidgetAccent.BLUE, false),
    EVENTS("Events", listOf(WidgetView.COMPACT, WidgetView.DETAILED), WidgetView.DETAILED, WidgetAccent.BLUE, false),
    HABIT("Habit", listOf(WidgetView.MONTH, WidgetView.YEAR), WidgetView.MONTH, WidgetAccent.MINT, false, usesAccent = false), // habits draw in their own colour
    NOTE("Note", emptyList(), null, WidgetAccent.SUNFLOWER, true);

    companion object {
        /** Which widget a placed widget id is, from its provider. */
        fun of(context: Context, widgetId: Int): WidgetKind? = when (android.appwidget.AppWidgetManager.getInstance(context).getAppWidgetInfo(widgetId)?.provider?.className) {
            TaskListWidgetProvider::class.java.name -> TASKS
            MonthDetailedWidgetProvider::class.java.name -> MONTH
            EventsDetailedWidgetProvider::class.java.name -> EVENTS
            HabitMonthWidgetProvider::class.java.name -> HABIT
            NoteWidgetProvider::class.java.name -> NOTE
            else -> null
        }
    }
}

/** How one placed widget looks: its theme, accent and view, chosen on its settings page. */
internal data class WidgetStyle(val light: Boolean, val accent: WidgetAccent, val view: WidgetView?) {
    /** The colours a Compose preview of the widget uses. */
    val background get() = Color(if (light) 0xFFFFF8F1 else 0xFF161B2E)
    val header get() = Color(if (light) accent.lightContainer else accent.darkContainer)
    val ink get() = Color(if (light) 0xFF221A16 else 0xFFF1F1F2)
    val muted get() = Color(if (light) 0xFF75685F else 0xFF8A90A6)
    val accentInk get() = Color(if (light) accent.lightInk else accent.dark)
    val accentFill get() = Color(if (light) accent.lightFill else accent.dark)
    val onFill get() = Color(if (light) accent.lightOnFill else 0xFF161B2E)
    val outline get() = Color(if (light) 0x262A1F1A else 0x33FFFFFF)
}

/**
 * Per-widget styles. Rendering a widget starts with [begin], which makes that widget's style current
 * for the thread, so the helpers below ([layout], [drawable], [pick]) need no style passed around.
 * The styled layouts and drawables are generated at build time (generateWidgetStyleRes, WidgetRes).
 */
internal object WidgetLook {
    private val current = ThreadLocal<WidgetStyle>()

    /** The saved style, or the defaults: the app's theme, the widget's tab colour (blue on dark, as widgets always were). */
    fun style(context: Context, widgetId: Int, kind: WidgetKind): WidgetStyle {
        val light = WidgetActions.string(context, widgetId, "theme")?.let { it == "light" }
            ?: context.getSharedPreferences("daymark_settings", Context.MODE_PRIVATE).getBoolean("light_mode", false)
        val accent = WidgetAccent.fromId(WidgetActions.string(context, widgetId, "accent")) ?: if (light) kind.defaultAccent else WidgetAccent.BLUE
        val view = WidgetView.fromId(WidgetActions.string(context, widgetId, "view"))?.takeIf { it in kind.views } ?: kind.defaultView
        return WidgetStyle(light, accent, view)
    }

    fun save(context: Context, widgetId: Int, style: WidgetStyle) {
        WidgetActions.putString(context, widgetId, "theme", if (style.light) "light" else "dark")
        WidgetActions.putString(context, widgetId, "accent", style.accent.id)
        WidgetActions.putString(context, widgetId, "view", style.view?.id)
    }

    fun forget(context: Context, widgetId: Int) = listOf("theme", "accent", "view", "size").forEach { WidgetActions.putString(context, widgetId, it, null) }

    /** Makes [widgetId]'s style current for drawing it, and returns it. */
    fun begin(context: Context, widgetId: Int, kind: WidgetKind): WidgetStyle = style(context, widgetId, kind).also { current.set(it) }

    private val now get() = current.get() ?: WidgetStyle(false, WidgetAccent.BLUE, null)
    val light: Boolean get() = now.light

    /** The layout to inflate for the original (dark) [dark] layout in the current style. */
    @Suppress("UNUSED_PARAMETER")
    fun layout(context: Context, dark: Int): Int = WidgetRes.layout(dark, now.light, now.accent.ordinal)

    /** The drawable for the original (dark) [dark] drawable in the current style. */
    fun drawable(dark: Int): Int = WidgetRes.drawable(dark, now.light, now.accent.ordinal)

    /** Picks the dark or light value. */
    fun <T> pick(dark: T, light: T): T = if (now.light) light else dark

    /** Accent-coloured text, e.g. a note's title. */
    val accentInk: Int get() = (if (now.light) now.accent.lightInk else now.accent.dark).toInt()

    /** Text drawn on the accent fill (today's date in the month grid). */
    val onAccent: Int get() = (if (now.light) now.accent.lightOnFill else 0xFF161B2E).toInt()
}
