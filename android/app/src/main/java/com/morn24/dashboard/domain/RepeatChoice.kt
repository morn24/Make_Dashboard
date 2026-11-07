package com.morn24.dashboard.domain

/** How often a todo repeats, as chosen in the add/edit dialog (same choices as the web todo menu). */
enum class RepeatKind { NONE, DAILY, WEEKDAYS, WEEKEND, CUSTOM }

/** The `repeat` / `days` fields a [RepeatKind] writes (web/app.js repeatFields). */
data class RepeatFields(val repeat: String, val days: List<Int>?)

object RepeatChoice {
    private val WEEKDAYS = listOf(1, 2, 3, 4, 5)
    private val WEEKEND = listOf(0, 6)

    fun fields(kind: RepeatKind, days: Collection<Int> = emptyList()): RepeatFields = when (kind) {
        RepeatKind.DAILY -> RepeatFields("daily", null)
        RepeatKind.WEEKDAYS -> RepeatFields("weekly", WEEKDAYS)
        RepeatKind.WEEKEND -> RepeatFields("weekly", WEEKEND)
        RepeatKind.CUSTOM -> if (days.isNotEmpty()) RepeatFields("weekly", days.sorted()) else RepeatFields("none", null)
        RepeatKind.NONE -> RepeatFields("none", null)
    }

    /** Which button is lit when an existing item is opened for editing. */
    fun kindOf(todo: Todo): RepeatKind = when {
        todo.repeat == "daily" -> RepeatKind.DAILY
        todo.repeat == "weekly" && todo.days.isNotEmpty() -> when (todo.days.sorted()) {
            WEEKDAYS -> RepeatKind.WEEKDAYS
            WEEKEND -> RepeatKind.WEEKEND
            else -> RepeatKind.CUSTOM
        }
        else -> RepeatKind.NONE
    }

    /**
     * Toggling one weekday button, starting from the days the current choice shows as lit.
     * All seven days mean daily; no days mean a one-time item.
     * Returns the new kind and the selected days (empty for daily).
     */
    fun toggleDay(kind: RepeatKind, days: Set<Int>, day: Int): Pair<RepeatKind, Set<Int>> {
        val base = selectedDays(kind, days)
        val next = if (day in base) base - day else base + day
        return when {
            next.size == 7 -> RepeatKind.DAILY to emptySet()
            next.isNotEmpty() -> RepeatKind.CUSTOM to next
            else -> RepeatKind.NONE to emptySet()
        }
    }

    /** Days shown as selected for a kind (so the weekday buttons light up for the chosen preset). */
    fun selectedDays(kind: RepeatKind, custom: Set<Int>): Set<Int> = when (kind) {
        RepeatKind.DAILY -> (0..6).toSet()
        RepeatKind.WEEKDAYS -> WEEKDAYS.toSet()
        RepeatKind.WEEKEND -> WEEKEND.toSet()
        RepeatKind.CUSTOM -> custom
        else -> emptySet()
    }
}
