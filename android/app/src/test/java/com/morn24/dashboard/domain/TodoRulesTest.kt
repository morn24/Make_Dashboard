package com.morn24.dashboard.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TodoRulesTest {
    private val today = "2026-10-31"

    @Test
    fun dailyCompletionIsScopedToTheCurrentLocalDate() {
        val todo = todo("daily", repeat = "daily", doneOn = today)
        assertTrue(TodoRules.isDone(todo, today))
        assertFalse(TodoRules.isDone(todo, "2026-11-01"))
        assertTrue(TodoRules.isVisible(todo, "2026-11-01"))
    }

    @Test
    fun oneTimeItemsStayDoneAndEarlierCompletionsAreHidden() {
        val doneToday = todo("today", doneOn = today)
        val doneEarlier = todo("earlier", doneOn = "2026-10-30")
        val open = todo("open")

        assertTrue(TodoRules.isDone(doneToday, today))
        assertFalse(TodoRules.isVisible(doneEarlier, today))
        assertTrue(TodoRules.isVisible(open, today))
    }

    @Test
    fun incompleteAndCompleteGroupsKeepTheirArrayOrder() {
        val items = listOf(
            todo("done-first", doneOn = today),
            todo("open-first"),
            todo("open-second"),
            todo("done-second", doneOn = today),
            todo("old", doneOn = "2026-10-01"),
        )

        assertEquals(
            listOf("open-first", "open-second", "done-first", "done-second"),
            TodoRules.displayOrder(items, today).map { it.id },
        )
    }

    @Test
    fun emptyTodoListHasZeroProgress() {
        val summary = TodoRules.summary(emptyList(), today)
        assertEquals(0, summary.visibleCount)
        assertEquals(0, summary.doneCount)
        assertEquals(0f, summary.progress)
    }

    @Test
    fun progressUsesTheSameVisibleItemsAsTheDenominator() {
        val summary = TodoRules.summary(
            listOf(todo("daily", repeat = "daily", doneOn = today), todo("old", doneOn = "2026-10-01"), todo("open")),
            today,
        )
        assertEquals(2, summary.visibleCount)
        assertEquals(1, summary.doneCount)
        assertEquals(0.5f, summary.progress)
    }

    private fun todo(id: String, repeat: String = "none", doneOn: String? = null) =
        Todo(id = id, title = id, repeat = repeat, doneOn = doneOn)
}
