package com.anmol.daymark

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.util.UUID

/** How a task or event repeats. Stored on [BoardCard.repeat] as [id]; "" means it doesn't. */
enum class Repeat(val id: String, val label: String) {
    NONE("", "Never"), DAILY("daily", "Daily"), WEEKDAYS("weekdays", "Weekdays"), WEEKLY("weekly", "Weekly"), MONTHLY("monthly", "Monthly"), YEARLY("yearly", "Yearly");

    companion object { fun fromId(id: String?) = entries.firstOrNull { it.id == id } ?: NONE }
}

private fun isWeekend(day: Long) = LocalDate.ofEpochDay(day).dayOfWeek.let { it == DayOfWeek.SATURDAY || it == DayOfWeek.SUNDAY }

private fun clampedDay(ym: YearMonth, dayOfMonth: Int) = ym.atDay(minOf(dayOfMonth, ym.lengthOfMonth())).toEpochDay()

/**
 * The first occurrence strictly after [fromDay] (epoch days). [anchorDay] is the day the series started on:
 * monthly and yearly rules clamp to short months (31st -> 30th/28th, 29 Feb -> 28 Feb) but go back to the
 * anchor's day when the month allows. Returns [fromDay] unchanged for [Repeat.NONE].
 */
fun nextOccurrence(rule: Repeat, fromDay: Long, anchorDay: Long = fromDay): Long {
    val from = LocalDate.ofEpochDay(fromDay)
    val anchor = LocalDate.ofEpochDay(anchorDay)
    return when (rule) {
        Repeat.NONE -> fromDay
        Repeat.DAILY -> fromDay + 1
        Repeat.WEEKDAYS -> generateSequence(fromDay + 1) { it + 1 }.first { !isWeekend(it) }
        Repeat.WEEKLY -> fromDay + 7
        Repeat.MONTHLY -> clampedDay(YearMonth.from(from).plusMonths(1), anchor.dayOfMonth)
        Repeat.YEARLY -> clampedDay(YearMonth.of(from.year + 1, anchor.month), anchor.dayOfMonth)
    }
}

/** Whether a series that starts on [startDay] (anchored at [anchorDay]) has an occurrence on [day]. */
fun occursOn(rule: Repeat, startDay: Long, anchorDay: Long, day: Long): Boolean {
    if (day < startDay) return false
    val date = LocalDate.ofEpochDay(day)
    val anchor = LocalDate.ofEpochDay(anchorDay)
    return when (rule) {
        Repeat.NONE -> false
        Repeat.DAILY -> true
        Repeat.WEEKDAYS -> !isWeekend(day)
        Repeat.WEEKLY -> (day - startDay) % 7 == 0L
        Repeat.MONTHLY -> date.dayOfMonth == minOf(anchor.dayOfMonth, YearMonth.from(date).lengthOfMonth())
        Repeat.YEARLY -> date.month == anchor.month && date.dayOfMonth == minOf(anchor.dayOfMonth, YearMonth.from(date).lengthOfMonth())
    }
}

/** True for in-app events that repeat (device-calendar imports are never expanded). */
internal fun BoardCard.repeatsAsEvent() = kind == CardKind.EVENT && calendarSourceId.isBlank() && dueDay != null && Repeat.fromId(repeat) != Repeat.NONE

internal fun BoardCard.occursOn(day: Long): Boolean =
    dueDay != null && if (repeatsAsEvent()) occursOn(Repeat.fromId(repeat), dueDay, repeatAnchor ?: dueDay, day) else dueDay == day


/**
 * Ticks a card off as of [today]. A repeating task also gets its next occurrence as a new open card
 * (same details, due after the current due day, rolled forward past today if it was long overdue). The
 * finished card keeps its rule for history and remembers the card it spawned in [BoardCard.spawnedId],
 * so completing it again after a reopen never creates a second copy.
 */
internal fun BoardSnapshot.completeCard(id: String, today: Long): BoardSnapshot {
    val card = cards.firstOrNull { it.id == id } ?: return this
    if (card.completed) return this
    val target = if (card.workspace == Workspace.TODOS) card.columnId
        else (columnsFor(card.workspace).let { list -> list.firstOrNull { it.name.equals("Done", true) } ?: list.lastOrNull() })?.id ?: card.columnId
    val rule = Repeat.fromId(card.repeat)
    val alreadySpawned = card.spawnedId.isNotBlank() && cards.any { it.id == card.spawnedId }
    val next = if (rule == Repeat.NONE || card.kind != CardKind.TASK || alreadySpawned) null else {
        val anchor = card.repeatAnchor ?: card.dueDay ?: today
        var due = nextOccurrence(rule, card.dueDay ?: today, anchor)
        while (due < today) due = nextOccurrence(rule, due, anchor)
        card.copy(id = UUID.randomUUID().toString(), completed = false, completedDay = null, spawnedId = "", dueDay = due, repeatAnchor = anchor, createdAt = System.currentTimeMillis())
    }
    val done = card.copy(completed = true, completedDay = today, columnId = target, spawnedId = next?.id ?: card.spawnedId)
    return copy(cards = cards.flatMap { if (it.id == id) listOfNotNull(done, next) else listOf(it) })
}

/** Un-ticks a card; the open occurrence it spawned (if still untouched by completion) is removed again. */
internal fun BoardSnapshot.reopenCard(id: String): BoardSnapshot {
    val card = cards.firstOrNull { it.id == id } ?: return this
    if (!card.completed) return this
    val spawned = cards.firstOrNull { card.spawnedId.isNotBlank() && it.id == card.spawnedId && !it.completed }
    return copy(cards = cards.filter { it.id != spawned?.id }.map {
        if (it.id == id) it.copy(completed = false, completedDay = null, spawnedId = if (spawned != null) "" else it.spawnedId) else it
    })
}
