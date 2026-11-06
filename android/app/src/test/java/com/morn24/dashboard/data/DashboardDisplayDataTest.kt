package com.morn24.dashboard.data

import com.morn24.dashboard.domain.TodoRules
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DashboardDisplayDataTest {
    private fun obj(text: String) = Json.parseToJsonElement(text).jsonObject

    @Test
    fun weeklyTodosWithDaysDateTimeAndHistoryAreReadInsteadOfFailingTheWholeFile() {
        val todos = DashboardDisplayData.todos(
            obj(
                """{"version":1,"items":[
                {"id":"w","title":"영어","repeat":"weekly","days":[1,3,5],"time":"09:30","doneOn":null,"history":["2026-11-02"],"gtask":"x"},
                {"id":"o","title":"보고서","repeat":"none","date":"2026-11-10","doneOn":null},
                {"id":"d","title":"운동","repeat":"daily","doneOn":"2026-11-07"}]}""",
            ),
        )
        assertEquals(3, todos.size)
        assertEquals(listOf(1, 3, 5), todos[0].days)
        assertEquals("09:30", todos[0].time)
        assertEquals(listOf("2026-11-02"), todos[0].history)
        assertEquals("2026-11-10", todos[1].date)
        assertNull(todos[2].history)
    }

    @Test
    fun aWeeklyItemIsHiddenOnItsRestDaysAndVisibleWhenDoneOnARestDay() {
        val weekly = DashboardDisplayData.todos(
            obj("""{"version":1,"items":[{"id":"w","title":"영어","repeat":"weekly","days":[1,3,5],"doneOn":null}]}"""),
        ).single()
        // 2026-11-07 is a Saturday: a rest day.
        assertFalse(TodoRules.isVisible(weekly, "2026-11-07"))
        assertTrue(TodoRules.isVisible(weekly, "2026-11-06")) // Friday
        assertTrue(TodoRules.isVisible(weekly.copy(doneOn = "2026-11-07"), "2026-11-07"))
        // Doing it once does not mean done forever: it is done only on the day it was done.
        assertFalse(TodoRules.isDone(weekly.copy(doneOn = "2026-11-06"), "2026-11-09"))
        assertTrue(TodoRules.isDone(weekly.copy(doneOn = "2026-11-06"), "2026-11-06"))
    }

    @Test
    fun aFutureDatedOneTimeItemIsUpcomingNotListed() {
        val item = DashboardDisplayData.todos(
            obj("""{"version":1,"items":[{"id":"o","title":"병원","repeat":"none","date":"2026-11-09","doneOn":null}]}"""),
        ).single()
        assertFalse(TodoRules.isVisible(item, "2026-11-07"))
        assertTrue(TodoRules.isUpcoming(item, "2026-11-07"))
        assertEquals(2, TodoRules.lateDays(item.copy(date = "2026-11-05"), "2026-11-07"))
        assertNull(TodoRules.lateDays(item, "2026-11-07"))
    }

    @Test
    fun pmrSummaryCountsDecisionsAndErrorJobsAndToleratesMissingFields() {
        val full = DashboardDisplayData.pmr(
            obj(
                """{"version":1,"generatedAt":"2026-11-07T08:00:00+09:00",
                "jobs":[{"id":"a","state":"ok"},{"id":"b","state":"error"},{"id":"c","state":"warn"},{"id":"d","state":"error"}],
                "decisions":{"need":2,"auto":3,"items":[]}}""",
            ),
        )
        assertEquals(2, full.decisionsNeed)
        assertEquals(2, full.failedJobs)
        assertEquals("2026-11-07T08:00:00+09:00", full.generatedAt)

        val bare = DashboardDisplayData.pmr(obj("""{"version":1}"""))
        assertEquals(0, bare.decisionsNeed)
        assertEquals(0, bare.failedJobs)
        assertNull(bare.generatedAt)

        val odd = DashboardDisplayData.pmr(obj("""{"jobs":"x","decisions":[1],"generatedAt":5}"""))
        assertEquals(0, odd.decisionsNeed)
        assertEquals(0, odd.failedJobs)
    }
}
