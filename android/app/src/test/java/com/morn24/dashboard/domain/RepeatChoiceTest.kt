package com.morn24.dashboard.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class RepeatChoiceTest {
    @Test
    fun presetsWriteTheSameFieldsAsTheWebApp() {
        assertEquals(RepeatFields("none", null), RepeatChoice.fields(RepeatKind.NONE))
        assertEquals(RepeatFields("daily", null), RepeatChoice.fields(RepeatKind.DAILY))
        assertEquals(RepeatFields("weekly", listOf(1, 2, 3, 4, 5)), RepeatChoice.fields(RepeatKind.WEEKDAYS))
        assertEquals(RepeatFields("weekly", listOf(0, 6)), RepeatChoice.fields(RepeatKind.WEEKEND))
        assertEquals(RepeatFields("weekly", listOf(1, 3, 5)), RepeatChoice.fields(RepeatKind.CUSTOM, setOf(5, 1, 3)))
        assertEquals(RepeatFields("none", null), RepeatChoice.fields(RepeatKind.CUSTOM, emptySet()))
    }

    @Test
    fun openingAnExistingItemLightsTheMatchingChoice() {
        fun todo(repeat: String, days: List<Int> = emptyList()) = Todo("a", "a", repeat, null, days = days)
        assertEquals(RepeatKind.NONE, RepeatChoice.kindOf(todo("none")))
        assertEquals(RepeatKind.DAILY, RepeatChoice.kindOf(todo("daily")))
        assertEquals(RepeatKind.WEEKDAYS, RepeatChoice.kindOf(todo("weekly", listOf(5, 4, 3, 2, 1))))
        assertEquals(RepeatKind.WEEKEND, RepeatChoice.kindOf(todo("weekly", listOf(6, 0))))
        assertEquals(RepeatKind.CUSTOM, RepeatChoice.kindOf(todo("weekly", listOf(1, 3))))
        assertEquals(RepeatKind.NONE, RepeatChoice.kindOf(todo("weekly", emptyList())))
    }

    @Test
    fun togglingDaysMovesBetweenChoices() {
        // Start from none: pick Monday, then Wednesday.
        var state = RepeatChoice.toggleDay(RepeatKind.NONE, emptySet(), 1)
        assertEquals(RepeatKind.CUSTOM to setOf(1), state)
        state = RepeatChoice.toggleDay(state.first, state.second, 3)
        assertEquals(RepeatKind.CUSTOM to setOf(1, 3), state)
        // Unpick everything: back to a one-time item.
        state = RepeatChoice.toggleDay(state.first, state.second, 1)
        state = RepeatChoice.toggleDay(state.first, state.second, 3)
        assertEquals(RepeatKind.NONE to emptySet<Int>(), state)
        // Taking one day away from the weekday preset keeps the other four.
        assertEquals(RepeatKind.CUSTOM to setOf(1, 2, 4, 5), RepeatChoice.toggleDay(RepeatKind.WEEKDAYS, emptySet(), 3))
        // All seven days is daily; one off daily leaves six.
        assertEquals(RepeatKind.DAILY to emptySet<Int>(), RepeatChoice.toggleDay(RepeatKind.CUSTOM, setOf(0, 1, 2, 3, 4, 5), 6))
        assertEquals(RepeatKind.CUSTOM to setOf(0, 1, 2, 3, 4, 5), RepeatChoice.toggleDay(RepeatKind.DAILY, emptySet(), 6))
    }
}
