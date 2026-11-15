package com.anmol.daymark

/** Days of check-in history kept per habit. */
internal const val CHECK_IN_LIMIT = 400

/** Consecutive checked days ending today, or ending yesterday while today is still open (a streak isn't broken until the day is over). */
fun currentStreak(checkIns: Collection<Long>, today: Long): Int {
    val days = checkIns.toHashSet()
    var day = if (today in days) today else today - 1
    var count = 0
    while (day in days) { count++; day-- }
    return count
}

/** The longest run of consecutive checked days anywhere in the history. */
fun bestStreak(checkIns: Collection<Long>): Int {
    var best = 0
    var run = 0
    var previous = Long.MIN_VALUE
    checkIns.distinct().sorted().forEach { day ->
        run = if (day == previous + 1) run + 1 else 1
        previous = day
        if (run > best) best = run
    }
    return best
}

/** Check-ins within the 30 days ending today (inclusive). */
fun completionsInLast30(checkIns: Collection<Long>, today: Long): Int = checkIns.distinct().count { it in (today - 29)..today }

internal fun BoardCard.checkedOn(day: Long) = day in checkIns

/** Adds or removes a habit's check-in for [day] (never a future day), keeping at most [CHECK_IN_LIMIT] days and [BoardCard.completedDay] at the latest one. */
internal fun BoardSnapshot.toggleCheckIn(id: String, day: Long, today: Long): BoardSnapshot {
    if (day > today) return this
    return copy(cards = cards.map { card ->
        if (card.id != id) card else {
            val days = if (day in card.checkIns) card.checkIns - day else (card.checkIns + day).distinct().sorted().takeLast(CHECK_IN_LIMIT)
            card.copy(checkIns = days, completedDay = days.maxOrNull())
        }
    })
}
