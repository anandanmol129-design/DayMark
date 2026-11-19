package com.anmol.daymark

import android.content.Context
import androidx.compose.foundation.BorderStroke
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.dp

/**
 * One tab's colour in the light theme.
 * [fill] paints buttons, ticks and selections, [onFill] is text and icons drawn on it, [ink] is the
 * deeper shade used for icons and links on the cream background, and [container] is a soft wash.
 */
internal data class Accent(val fill: Color, val onFill: Color, val ink: Color, val container: Color)

/** "Sorbet": every tab gets its own bright colour, and the whole app takes it on. */
internal object Accents {
    val TODAY = Accent(Color(0xFFFF3D8B), Color.White, Color(0xFFD01A6A), Color(0xFFFFE0EC))
    val TODOS = Accent(Color(0xFFFF5A5F), Color.White, Color(0xFFD7263D), Color(0xFFFFE2E0))
    val CALENDAR = Accent(Color(0xFF3D5AFE), Color.White, Color(0xFF2F4AE8), Color(0xFFE1E6FF))
    val NOTES = Accent(Color(0xFFFFC23C), Color(0xFF2B1D00), Color(0xFF9A6200), Color(0xFFFFF0C4))
    val TRACK = Accent(Color(0xFF1DB97A), Color.White, Color(0xFF0B8A56), Color(0xFFD3F5E5))
    val PRIVATE = Accent(Color(0xFF7C4DFF), Color.White, Color(0xFF6236E0), Color(0xFFEAE2FF))

    fun of(workspace: Workspace?, private: Boolean) = when {
        private && workspace == Workspace.NOTES -> PRIVATE
        workspace == null -> TODAY
        workspace == Workspace.TODOS -> TODOS
        workspace == Workspace.CALENDAR -> CALENDAR
        workspace == Workspace.NOTES -> NOTES
        else -> TRACK
    }
}

/**
 * Which look is showing. Both are snapshot state, so everything drawn with the colours below
 * redraws by itself when the theme flips or the accent changes to the next tab's colour.
 */
internal object Look {
    private const val PREFS = "daymark_settings"
    private const val KEY = "light_mode"

    /** The experimental Material 3 Expressive light theme; the calm dark Zen look otherwise. */
    var light by mutableStateOf(false)
    var accent by mutableStateOf(Accents.TODAY)

    fun load(context: Context) {
        light = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY, false)
    }

    fun setLight(context: Context, on: Boolean) {
        light = on
        // Saved at once, so the widgets read the new theme as they redraw.
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY, on).commit()
        val app = context.applicationContext
        kotlin.concurrent.thread { WidgetActions.refreshAll(app) }
    }
}

private inline fun pick(dark: Color, light: () -> Color) = if (Look.light) light() else dark

/** A translucent wash of the text colour: white on the dark theme, ink on the light one. */
internal fun veil(alpha: Float) = if (Look.light) Color(0xFF2A1F1A).copy(alpha = alpha * .7f) else Color.White.copy(alpha = alpha)

// Dark: Zen palette (warm ink, paper-white text, one calm sage accent and a sand highlight).
// Light: Sorbet (white page and cards, near-black ink; the tab's colour only on buttons, icons and the page's edge glow).
// The old names stay so every screen picks the palette up without renaming.
internal val TodoBackground get() = pick(Color(0xFF141413)) { Color.White }
internal val TodoCard get() = pick(Color(0x17FFFFFF)) { Color.White }
internal val TodoCardRaised get() = pick(Color(0x24FFFFFF)) { Color(0xFFF1F1F1) } // neutral grey: tracks, table headers, placeholders
internal val TodoBorder get() = pick(Color(0x1FFFFFFF)) { Color(0x1A2A1F1A) }
internal val TodoText get() = pick(Color(0xFFEDEAE3)) { Color(0xFF221A16) }
internal val TodoMuted get() = pick(Color(0xFFA39F96)) { Color(0xFF75685F) }
internal val TodoBlue get() = pick(Color(0xFFB4CDBF)) { Look.accent.ink }      // accent for icons, links and selected text
internal val TodoNav get() = pick(Color(0xA6181816)) { Color(0xF7FFFFFF) }
internal val Ink get() = TodoText
internal val Muted get() = TodoMuted
internal val Green get() = TodoBlue
internal val PaleGreen get() = pick(Color(0x3393B3A3)) { Look.accent.container }
internal val Amber get() = pick(Color(0xFFD9BC8C)) { Color(0xFFE07B00) }        // importance and streaks
internal val Blue get() = TodoBlue
internal val Danger get() = pick(Color(0xFFDE8E80)) { Color(0xFFE5303A) }       // overdue and delete
internal val SheetSolid get() = pick(Color(0xFF1C1C1A)) { Color(0xFFFFFBF7) }   // opaque bottom sheets
internal val SheetRaised get() = pick(Color(0xFF262624)) { Color(0xFFF1F1F1) }
internal val OnAccent get() = pick(Color(0xFF14201A)) { Look.accent.onFill }    // text and ticks drawn on a filled accent
/** Ticks and icons drawn on [CalendarBlue] (white on the dark theme's deep sage). */
internal val OnFill get() = pick(Color.White) { Look.accent.onFill }

// Frosted glass on the dark theme; solid, softly tinted surfaces on the light one.
internal val GlassSheet get() = pick(Color(0xF21B1B19)) { Color(0xFFFFFBF7) }
internal val GlassDialog get() = pick(Color(0xC21E1E1C)) { Color(0xFFFFFBF7) }
internal val GlassAccent get() = pick(Color(0x4D9DBBAB)) { Look.accent.container }
private val DarkStroke = BorderStroke(0.75.dp, Brush.linearGradient(listOf(Color(0x47FFFFFF), Color(0x0AFFFFFF), Color(0x1FFFFFFF))))
private val LightStroke = BorderStroke(1.dp, Color(0x122A1F1A))
internal val GlassStroke get() = if (Look.light) LightStroke else DarkStroke

// Filled accent (checked boxes, selected days, the send button).
internal val CalendarBlue get() = pick(Color(0xFF7FA595)) { Look.accent.fill }

/** Laid over the blurred backdrop behind full-page screens (Settings, Search…) on the dark theme. */
internal val PageTint get() = pick(Color(0x590B1226)) { Color.Transparent }
