package com.anmol.daymark

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * One quiet family of thin line icons (24-unit grid, round caps), drawn in the style of
 * Lucide (ISC licence). Everything in the app uses these instead of emoji or text glyphs.
 */
enum class ZenIcon(vararg val paths: String, val filled: Boolean = false) {
    Sun("M12 8a4 4 0 1 0 0 8a4 4 0 1 0 0-8z", "M12 2v2", "M12 20v2", "M4.93 4.93l1.41 1.41", "M17.66 17.66l1.41 1.41", "M2 12h2", "M20 12h2", "M6.34 17.66l-1.41 1.41", "M19.07 4.93l-1.41 1.41"),
    Inbox("M22 12h-6l-2 3h-4l-2-3H2", "M5.45 5.11L2 12v6a2 2 0 0 0 2 2h16a2 2 0 0 0 2-2v-6l-3.45-6.89A2 2 0 0 0 16.76 4H7.24a2 2 0 0 0-1.79 1.11z"),
    Calendar("M8 2v4", "M16 2v4", "M5 4h14a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V6a2 2 0 0 1 2-2z", "M3 10h18"),
    Note("M15 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V7z", "M14 2v4a2 2 0 0 0 2 2h4", "M16 13H8", "M16 17H8", "M10 9H8"),
    Timer("M12 3a9 9 0 1 0 0 18a9 9 0 1 0 0-18z", "M12 7v5l3 2"),
    Plus("M5 12h14", "M12 5v14"),
    Minus("M5 12h14"),
    Settings("M20 7h-9", "M14 17H5", "M17 14a3 3 0 1 0 0 6a3 3 0 1 0 0-6z", "M7 4a3 3 0 1 0 0 6a3 3 0 1 0 0-6z"),
    Search("M11 3a8 8 0 1 0 0 16a8 8 0 1 0 0-16z", "M21 21l-4.3-4.3"),
    ChevronLeft("M15 18l-6-6 6-6"),
    ChevronRight("M9 18l6-6-6-6"),
    ChevronDown("M6 9l6 6 6-6"),
    ChevronUp("M18 15l-6-6-6 6"),
    Close("M18 6L6 18", "M6 6l12 12"),
    Check("M20 6L9 17l-5-5"),
    Bell("M10.27 21a2 2 0 0 0 3.46 0", "M3.26 15.33A1 1 0 0 0 4 17h16a1 1 0 0 0 .74-1.67C19.41 13.96 18 12.5 18 8A6 6 0 0 0 6 8c0 4.5-1.41 5.96-2.74 7.33"),
    BellOff("M10.27 21a2 2 0 0 0 3.46 0", "M17 17H4a1 1 0 0 1-.74-1.67C4.59 13.96 6 12.5 6 8a6 6 0 0 1 .26-1.74", "M8.67 2.67A6 6 0 0 1 18 8c0 2.5.4 4.1 1 5.2", "M2 2l20 20"),
    Alarm("M12 5a8 8 0 1 0 0 16a8 8 0 1 0 0-16z", "M12 9v4l2 2", "M5 3L2 6", "M22 6l-3-3", "M6.38 18.7L4 21", "M17.64 18.67L20 21"),
    Clock("M12 2a10 10 0 1 0 0 20a10 10 0 1 0 0-20z", "M12 6v6l4 2"),
    Repeat("M21 12a9 9 0 1 1-9-9c2.52 0 4.93 1 6.74 2.74L21 8", "M21 3v5h-5"),
    Star("M12 2l3.09 6.26L22 9.27l-5 4.87 1.18 6.88L12 17.77l-6.18 3.25L7 14.14 2 9.27l6.91-1.01L12 2z"),
    StarFilled("M12 2l3.09 6.26L22 9.27l-5 4.87 1.18 6.88L12 17.77l-6.18 3.25L7 14.14 2 9.27l6.91-1.01L12 2z", filled = true),
    Mic("M12 2a3 3 0 0 0-3 3v7a3 3 0 0 0 6 0V5a3 3 0 0 0-3-3z", "M19 10v2a7 7 0 0 1-14 0v-2", "M12 19v3"),
    Picture("M5 3h14a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2z", "M9 7a2 2 0 1 0 0 4a2 2 0 1 0 0-4z", "M21 15l-3.09-3.09a2 2 0 0 0-2.82 0L6 21"),
    Link("M10 13a5 5 0 0 0 7.54.54l3-3a5 5 0 0 0-7.07-7.07l-1.72 1.71", "M14 11a5 5 0 0 0-7.54-.54l-3 3a5 5 0 0 0 7.07 7.07l1.71-1.71"),
    Flame("M8.5 14.5A2.5 2.5 0 0 0 11 12c0-1.38-.5-2-1-3-1.07-2.14-.22-4.05 2-6 .5 2.5 2 4.9 4 6.5 2 1.6 3 3.5 3 5.5a7 7 0 1 1-14 0c0-1.15.43-2.29 1-3a2.5 2.5 0 0 0 2.5 2.5z"),
    Play("M7 4l13 8-13 8V4z"),
    Pause("M8 5v14", "M16 5v14"),
    Phone("M22 16.92v3a2 2 0 0 1-2.18 2 19.79 19.79 0 0 1-8.63-3.07 19.5 19.5 0 0 1-6-6 19.79 19.79 0 0 1-3.07-8.67A2 2 0 0 1 4.11 2h3a2 2 0 0 1 2 1.72 12.84 12.84 0 0 0 .7 2.81 2 2 0 0 1-.45 2.11L8.09 9.91a16 16 0 0 0 6 6l1.27-1.27a2 2 0 0 1 2.11-.45 12.84 12.84 0 0 0 2.81.7A2 2 0 0 1 22 16.92z"),
    Message("M7.9 20A9 9 0 1 0 4 16.1L2 22z"),
    Camera("M14.5 4h-5L7 7H4a2 2 0 0 0-2 2v9a2 2 0 0 0 2 2h16a2 2 0 0 0 2-2V9a2 2 0 0 0-2-2h-3l-2.5-3z", "M12 10a3 3 0 1 0 0 6a3 3 0 1 0 0-6z"),
    Maximize("M8 3H5a2 2 0 0 0-2 2v3", "M21 8V5a2 2 0 0 0-2-2h-3", "M3 16v3a2 2 0 0 0 2 2h3", "M16 21h3a2 2 0 0 0 2-2v-3"),
    Minimize("M8 3v3a2 2 0 0 1-2 2H3", "M21 8h-3a2 2 0 0 1-2-2V3", "M3 16h3a2 2 0 0 1 2 2v3", "M16 21v-3a2 2 0 0 1 2-2h3"),
    Stop("M8 6h8a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2H8a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2z"),
    More("M5 11.25a.75.75 0 1 0 0 1.5a.75.75 0 1 0 0-1.5z", "M12 11.25a.75.75 0 1 0 0 1.5a.75.75 0 1 0 0-1.5z", "M19 11.25a.75.75 0 1 0 0 1.5a.75.75 0 1 0 0-1.5z"),
    Lines("M8 6h13", "M8 12h13", "M8 18h13", "M3 6h.01", "M3 12h.01", "M3 18h.01"),
    Board("M6 5v11", "M12 5v6", "M18 5v14"),
    ArrowUp("M5 12l7-7 7 7", "M12 19V5"),
    ArrowRight("M5 12h14", "M12 5l7 7-7 7"),
    ArrowLeft("M19 12H5", "M12 19l-7-7 7-7"),
    ArrowUpRight("M7 7h10v10", "M7 17L17 7"),
    Bookmark("M19 21l-7-4-7 4V5a2 2 0 0 1 2-2h10a2 2 0 0 1 2 2v16z"),
    Pin("M12 17v5", "M9 10.76a2 2 0 0 1-1.11 1.79l-1.78.9A2 2 0 0 0 5 15.24V16a1 1 0 0 0 1 1h12a1 1 0 0 0 1-1v-.76a2 2 0 0 0-1.11-1.79l-1.78-.9A2 2 0 0 1 15 10.76V7a1 1 0 0 1 1-1 2 2 0 0 0 0-4H8a2 2 0 0 0 0 4 1 1 0 0 1 1 1z"),
    Quote("M17 6H3", "M21 12H8", "M21 18H8", "M3 12v6"),
    Bullet("M12 10.5a1.5 1.5 0 1 0 0 3a1.5 1.5 0 1 0 0-3z", filled = true),
    Checkbox("M5 3h14a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2z", "M9 12l2 2 4-4"),
    Table("M5 3h14a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2z", "M3 9h18", "M3 15h18", "M12 3v18"),
    Pencil("M17 3a2.85 2.83 0 1 1 4 4L7.5 20.5 2 22l1.5-5.5z"),
    Sync("M3 12a9 9 0 0 1 9-9 9.75 9.75 0 0 1 6.74 2.74L21 8", "M21 3v5h-5", "M21 12a9 9 0 0 1-9 9 9.75 9.75 0 0 1-6.74-2.74L3 16", "M8 16H3v5"),
    Upload("M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4", "M17 8l-5-5-5 5", "M12 3v12"),
    Download("M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4", "M7 10l5 5 5-5", "M12 15V3"),
    Share("M18 2a3 3 0 1 0 0 6a3 3 0 1 0 0-6z", "M6 9a3 3 0 1 0 0 6a3 3 0 1 0 0-6z", "M18 16a3 3 0 1 0 0 6a3 3 0 1 0 0-6z", "M8.59 13.51l6.83 3.98", "M15.41 6.51l-6.82 3.98"),
    Restore("M3 12a9 9 0 1 0 9-9 9.75 9.75 0 0 0-6.74 2.74L3 8", "M3 3v5h5"),
    CircleCheck("M12 2a10 10 0 1 0 0 20a10 10 0 1 0 0-20z", "M9 12l2 2 4-4"),
    Alert("M12 2a10 10 0 1 0 0 20a10 10 0 1 0 0-20z", "M12 8v4", "M12 16h.01"),
    Type("M4 7V4h16v3", "M9 20h6", "M12 4v16"),
    Comment("M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z"),
    Habit("M5 4h4a1 1 0 0 1 1 1v4a1 1 0 0 1-1 1H5a1 1 0 0 1-1-1V5a1 1 0 0 1 1-1z", "M15 4h4a1 1 0 0 1 1 1v4a1 1 0 0 1-1 1h-4a1 1 0 0 1-1-1V5a1 1 0 0 1 1-1z", "M5 14h4a1 1 0 0 1 1 1v4a1 1 0 0 1-1 1H5a1 1 0 0 1-1-1v-4a1 1 0 0 1 1-1z", "M14.5 17.5l2 2 3.5-4"),
    Lock("M5 11h14a2 2 0 0 1 2 2v7a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-7a2 2 0 0 1 2-2z", "M7 11V7a5 5 0 0 1 10 0v4"),
    Leaf("M11 20A7 7 0 0 1 9.8 6.1C15.5 5 17 4.48 19 2c1 2 2 4.18 2 8 0 5.5-4.78 10-10 10z", "M2 21c0-3 1.85-5.36 5.08-6C9.5 14.52 12 13 13 12"),
    Book("M2 3h6a4 4 0 0 1 4 4v14a3 3 0 0 0-3-3H2z", "M22 3h-6a4 4 0 0 0-4 4v14a3 3 0 0 1 3-3h7z"),
    Paperclip("M16 6l-8.414 8.586a2 2 0 0 0 2.829 2.829l8.414-8.586a4 4 0 1 0-5.657-5.657l-8.379 8.551a6 6 0 1 0 8.485 8.485l8.379-8.551"),
    File("M15 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V7z", "M14 2v4a2 2 0 0 0 2 2h4"),
    Music("M9 18V5l12-2v13", "M6 15a3 3 0 1 0 0 6a3 3 0 1 0 0-6z", "M18 13a3 3 0 1 0 0 6a3 3 0 1 0 0-6z"),
    Video("M16 13l5.223 3.482a.5.5 0 0 0 .777-.416V7.87a.5.5 0 0 0-.752-.432L16 10.5", "M4 6h10a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2z"),
    Folder("M20 20a2 2 0 0 0 2-2V8a2 2 0 0 0-2-2h-7.9a2 2 0 0 1-1.69-.9L9.6 3.9A2 2 0 0 0 7.93 3H4a2 2 0 0 0-2 2v13a2 2 0 0 0 2 2z"),
    Undo("M9 14L4 9l5-5", "M4 9h10.5a5.5 5.5 0 0 1 0 11H11"),
    Redo("M15 14l5-5-5-5", "M20 9H9.5a5.5 5.5 0 0 0 0 11H13"),
    Trash("M3 6h18", "M19 6v14c0 1-1 2-2 2H7c-1 0-2-1-2-2V6", "M8 6V4c0-1 1-2 2-2h4c1 0 2 1 2 2v2"),
    HardDrive("M22 12H2", "M5.45 5.11L2 12v6a2 2 0 0 0 2 2h16a2 2 0 0 0 2-2v-6l-3.45-6.89A2 2 0 0 0 16.76 4H7.24a2 2 0 0 0-1.79 1.11z", "M6 16h.01", "M10 16h.01");

    val vector: ImageVector by lazy {
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
            paths.forEach { d ->
                addPath(
                    pathData = PathParser().parsePathString(d).toNodes(),
                    fill = if (filled) SolidColor(Color.White) else null,
                    stroke = SolidColor(Color.White),
                    strokeLineWidth = 1.7f,
                    strokeLineCap = StrokeCap.Round,
                    strokeLineJoin = StrokeJoin.Round
                )
            }
        }.build()
    }
}

/** Draws [icon] tinted [tint]; decorative unless the caller adds semantics. */
@Composable
internal fun Glyph(icon: ZenIcon, tint: Color, size: Dp = 20.dp, modifier: Modifier = Modifier) {
    Image(icon.vector, contentDescription = null, colorFilter = ColorFilter.tint(tint), modifier = modifier.size(size))
}

/** The quiet icons that trail a card's date line: repeat and reminder. */
internal fun badgesFor(card: BoardCard): List<ZenIcon> = listOfNotNull(
    if (card.repeat.isNotBlank()) ZenIcon.Repeat else null,
    reminderBadge(card)
)

/** A single line of secondary text with an optional leading icon and small trailing icons. */
@Composable
internal fun MetaLine(
    text: String, color: Color, fontSize: androidx.compose.ui.unit.TextUnit,
    modifier: Modifier = Modifier, leading: ZenIcon? = null, badges: List<ZenIcon> = emptyList(),
    weight: androidx.compose.ui.text.font.FontWeight? = null,
    unbounded: Boolean = false // true inside a horizontally scrolling row, where the text can't share width by weight
) {
    val iconSize = (fontSize.value * .95f).dp
    androidx.compose.foundation.layout.Row(modifier, verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        if (leading != null) {
            Glyph(leading, color, iconSize)
            androidx.compose.foundation.layout.Spacer(Modifier.size(5.dp))
        }
        if (text.isNotEmpty()) androidx.compose.material3.Text(
            text, color = color, fontSize = fontSize, fontWeight = weight, maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = if (unbounded) Modifier else Modifier.weight(1f, fill = false)
        )
        badges.forEachIndexed { i, icon ->
            if (i > 0 || text.isNotEmpty() || leading != null) androidx.compose.foundation.layout.Spacer(Modifier.size(5.dp))
            Glyph(icon, color.copy(alpha = color.alpha * .85f), iconSize * .9f)
        }
    }
}
