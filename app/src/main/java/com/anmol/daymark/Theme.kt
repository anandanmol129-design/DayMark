package com.anmol.daymark

import androidx.compose.foundation.BorderStroke
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

// Zen palette: warm ink, paper-white text, one calm sage accent and a sand highlight.
// The old names stay so every screen picks the palette up without renaming.
internal val TodoBackground = Color(0xFF141413)
internal val TodoCard = Color(0x17FFFFFF)
internal val TodoCardRaised = Color(0x24FFFFFF)
internal val TodoBorder = Color(0x1FFFFFFF)
internal val TodoText = Color(0xFFEDEAE3)
internal val TodoMuted = Color(0xFFA39F96)
internal val TodoBlue = Color(0xFFB4CDBF)      // sage accent for icons, links and selected text
internal val TodoNav = Color(0xA6181816)
internal val Ink = TodoText
internal val Muted = TodoMuted
internal val Green = TodoBlue
internal val PaleGreen = Color(0x3393B3A3)
internal val Amber = Color(0xFFD9BC8C)          // sand, for importance and streaks
internal val Blue = TodoBlue
internal val Danger = Color(0xFFDE8E80)         // soft terracotta for overdue and delete
internal val SheetSolid = Color(0xFF1C1C1A)    // opaque bottom sheets
internal val SheetRaised = Color(0xFF262624)
internal val OnAccent = Color(0xFF14201A)       // text and ticks drawn on a filled accent

// Frosted glass: translucent warm fills with a faint hairline that fades out.
internal val GlassSheet = Color(0xF21B1B19)
internal val GlassDialog = Color(0xC21E1E1C)
internal val GlassAccent = Color(0x4D9DBBAB)
internal val GlassStroke = BorderStroke(
    0.75.dp,
    Brush.linearGradient(listOf(Color(0x47FFFFFF), Color(0x0AFFFFFF), Color(0x1FFFFFFF)))
)

// Filled accent (checked boxes, selected days, the send button): deeper sage so white reads on it.
internal val CalendarBlue = Color(0xFF7FA595)
