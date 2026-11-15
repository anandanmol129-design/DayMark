package com.anmol.daymark

import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

internal fun formatBytes(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${bytes / 1024} KB"
    else -> String.format(Locale.getDefault(), "%.1f MB", bytes / (1024.0 * 1024.0))
}

internal fun workspaceSubtitle(workspace: Workspace, cards: List<BoardCard>): String = when (workspace) {
    Workspace.TODOS -> "${cards.count { !it.completed }} open · ${cards.count { it.important && !it.completed }} priorities"
    Workspace.CALENDAR -> "Your plans, grouped into a flexible board"
    Workspace.NOTES -> "Ideas, references, and everything in between"
    Workspace.TRACK -> "Small things, every day"
}

internal fun todayLabel() = LocalDate.now().format(DateTimeFormatter.ofPattern("EEEE · d MMM", Locale.getDefault())).uppercase(Locale.getDefault())

private val TimeInTitle = Regex("""\b(?:at\s+)?(?:(\d{1,2})(?::([0-5]\d))?\s*([ap]m)|([01]?\d|2[0-3]):([0-5]\d))\b""", RegexOption.IGNORE_CASE)

/** Picks a clock time such as "6pm", "6:30 am" or "18:30" out of free text, as minutes after midnight. */
internal fun parseTimeFromTitle(text: String): Int? {
    val match = TimeInTitle.find(text) ?: return null
    val (hour12, minute12, meridiem, hour24, minute24) = match.destructured
    if (meridiem.isNotEmpty()) {
        val hour = hour12.toInt()
        if (hour !in 1..12) return null
        return (hour % 12 + if (meridiem.equals("pm", true)) 12 else 0) * 60 + (minute12.ifEmpty { "0" }).toInt()
    }
    return hour24.toInt() * 60 + minute24.toInt()
}

internal fun formatClock(minute: Int): String =
    LocalTime.of((minute / 60) % 24, minute % 60).format(DateTimeFormatter.ofPattern("hh:mm a", Locale.getDefault()))

internal fun formatGap(minutes: Int): String = if (minutes >= 60) "${minutes / 60}h ${minutes % 60}min" else "${minutes}min"

internal fun formatDue(day: Long?): String {
    if (day == null) return "No date"
    val date = LocalDate.ofEpochDay(day)
    val today = LocalDate.now()
    return when (date) {
        today -> "Today"
        today.plusDays(1) -> "Tomorrow"
        today.minusDays(1) -> "Yesterday"
        else -> date.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))
    }
}

internal fun formatDuration(milliseconds: Long): String {
    val minutes = (milliseconds.coerceAtLeast(0) / 60_000)
    val hours = minutes / 60
    return if (hours > 0) "${hours}h ${minutes % 60}m" else "${minutes}m"
}
