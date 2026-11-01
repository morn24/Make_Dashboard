package com.morn24.dashboard.widget

import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WidgetLayoutPlannerTest {
    @Test
    fun glanceListIdsStayOutsideTheReservedNegativeRange() {
        listOf("header", "sync-status", "todo:한글", "event:meeting", "project:dashboard").forEach { key ->
            assertTrue(stableId(key) >= 0L)
        }
    }

    @Test
    fun smallWidgetKeepsOneActionableTodoAtEveryFontSize() {
        val standard = WidgetLayoutPlanner.plan(DpSize(170.dp, 240.dp), 100)
        val largeText = WidgetLayoutPlanner.plan(DpSize(170.dp, 240.dp), 150)

        assertTrue(standard.small)
        assertEquals(1, standard.columns)
        assertEquals(1, standard.todoRows)
        assertEquals(1, largeText.todoRows)
    }

    @Test
    fun wideShortWidgetKeepsOneActionableTodoAtEveryFontSize() {
        val standard = WidgetLayoutPlanner.plan(DpSize(260.dp, 240.dp), 100)
        val largeText = WidgetLayoutPlanner.plan(DpSize(260.dp, 240.dp), 140)

        assertTrue(standard.medium)
        assertEquals(2, standard.columns)
        assertEquals(1, standard.todoRows)
        assertEquals(1, largeText.todoRows)
        assertEquals(1, largeText.projectRows)
    }

    @Test
    fun tallWidgetPlansTwoColumnsAndKeepsAllThreePanelsForLargeText() {
        val standard = WidgetLayoutPlanner.plan(DpSize(260.dp, 490.dp), 100)
        val largeText = WidgetLayoutPlanner.plan(DpSize(260.dp, 490.dp), 140)

        assertEquals(2, standard.columns)
        assertEquals(1, standard.todoRows)
        assertEquals(4, standard.eventRows)
        assertEquals(1, standard.projectRows)
        assertEquals(1, largeText.todoRows)
        assertEquals(1, largeText.eventRows)
        assertEquals(1, largeText.projectRows)
    }

    @Test
    fun tallNarrowWidgetKeepsTheSingleColumnFallback() {
        val plan = WidgetLayoutPlanner.plan(DpSize(170.dp, 490.dp), 100)

        assertTrue(plan.narrowTall)
        assertEquals(1, plan.columns)
        assertTrue(plan.eventRows > 0)
        assertTrue(plan.projectRows > 0)
    }
}
