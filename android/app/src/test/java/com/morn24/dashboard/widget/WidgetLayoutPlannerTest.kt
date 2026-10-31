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
        val standard = WidgetLayoutPlanner.plan(DpSize(140.dp, 140.dp), 100)
        val largeText = WidgetLayoutPlanner.plan(DpSize(140.dp, 140.dp), 150)

        assertTrue(standard.small)
        assertEquals(1, standard.todoRows)
        assertEquals(1, largeText.todoRows)
    }

    @Test
    fun wideShortWidgetKeepsOneActionableTodoAtEveryFontSize() {
        val standard = WidgetLayoutPlanner.plan(DpSize(300.dp, 140.dp), 100)
        val largeText = WidgetLayoutPlanner.plan(DpSize(300.dp, 140.dp), 140)

        assertTrue(standard.medium)
        assertEquals(1, standard.todoRows)
        assertEquals(1, largeText.todoRows)
        assertEquals(0, largeText.projectRows)
    }

    @Test
    fun tallWidgetUsesScrollForMoreSectionsAndReducesDensityForLargeText() {
        val standard = WidgetLayoutPlanner.plan(DpSize(300.dp, 300.dp), 100)
        val largeText = WidgetLayoutPlanner.plan(DpSize(300.dp, 300.dp), 140)

        assertEquals(3, standard.todoRows)
        assertEquals(5, standard.eventRows)
        assertEquals(4, standard.projectRows)
        assertEquals(2, largeText.todoRows)
        assertEquals(3, largeText.eventRows)
        assertEquals(2, largeText.projectRows)
    }
}
