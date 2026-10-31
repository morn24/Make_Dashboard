package com.morn24.dashboard.domain

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CalendarRulesTest {
    private val today = LocalDate.parse("2026-10-31")
    private val zone = ZoneId.of("Asia/Seoul")

    @Test
    fun allDayUsesAnExclusiveEndDateAndIncludesMultiDayEvents() {
        val events = listOf(
            allDay("ends-today", "2026-10-30", "2026-10-31"),
            allDay("starts-today", "2026-10-31", "2026-11-01"),
            allDay("multi-day", "2026-10-29", "2026-11-02"),
            allDay("starts-tomorrow", "2026-11-01", "2026-11-02"),
        )

        assertEquals(listOf("starts-today", "multi-day"), ids(CalendarRules.occurringToday(events, today, zone)))
    }

    @Test
    fun timedEventsIncludeOvernightOverlapAndExcludeDayBoundaryTouches() {
        val events = listOf(
            timed("overnight", "2026-10-30T23:30:00+09:00", "2026-10-31T00:30:00+09:00"),
            timed("ends-at-midnight", "2026-10-30T23:00:00+09:00", "2026-10-31T00:00:00+09:00"),
            timed("starts-tomorrow", "2026-11-01T00:00:00+09:00", "2026-11-01T01:00:00+09:00"),
            timed("during-day", "2026-10-31T13:00:00+09:00", "2026-10-31T14:00:00+09:00"),
        )

        assertEquals(listOf("overnight", "during-day"), ids(CalendarRules.occurringToday(events, today, zone)))
    }

    @Test
    fun allDayEventsSortBeforeTimedAndTimedEventsSortByInstantStably() {
        val events = listOf(
            timed("later", "2026-10-31T15:00:00+09:00", "2026-10-31T15:30:00+09:00"),
            allDay("all-day-1", "2026-10-31", "2026-11-01"),
            timed("early-first", "2026-10-31T09:00:00+09:00", "2026-10-31T10:00:00+09:00"),
            timed("early-second", "2026-10-31T09:00:00+09:00", "2026-10-31T09:30:00+09:00"),
        )

        assertEquals(
            listOf("all-day-1", "early-first", "early-second", "later"),
            ids(CalendarRules.occurringToday(events, today, zone)),
        )
    }

    @Test
    fun nextTimedIncludesAnOngoingEventAndSkipsEndedAndAllDayEvents() {
        val events = listOf(
            allDay("all-day", "2026-10-31", "2026-11-01"),
            timed("ended", "2026-10-31T08:00:00+09:00", "2026-10-31T09:00:00+09:00"),
            timed("ongoing", "2026-10-31T09:30:00+09:00", "2026-10-31T10:30:00+09:00"),
            timed("upcoming", "2026-10-31T11:00:00+09:00", "2026-10-31T12:00:00+09:00"),
        )
        val now = Instant.parse("2026-10-31T01:00:00Z") // 10:00 in Seoul
        val next = CalendarRules.nextTimed(events, today, zone, now)

        assertEquals("ongoing", next?.event?.id)
        assertTrue(CalendarRules.isInProgress(next!!, now))
        assertFalse(CalendarRules.hasEnded(next, now))
    }

    @Test
    fun remainingListKeepsAllDayEventsAndDropsEndedTimedEvents() {
        val events = listOf(
            allDay("all-day", "2026-10-31", "2026-11-01"),
            timed("ended", "2026-10-31T08:00:00+09:00", "2026-10-31T09:00:00+09:00"),
            timed("upcoming", "2026-10-31T10:00:00+09:00", "2026-10-31T11:00:00+09:00"),
        )

        assertEquals(
            listOf("all-day", "upcoming"),
            ids(CalendarRules.remainingToday(events, today, zone, Instant.parse("2026-10-31T00:30:00Z"))),
        )
    }

    @Test
    fun daylightSavingDayUsesNextLocalMidnightInsteadOfTwentyFourHours() {
        val dstDay = LocalDate.parse("2026-03-08")
        val newYork = ZoneId.of("America/New_York")
        val start = dstDay.atStartOfDay(newYork).toInstant()
        val end = dstDay.plusDays(1).atStartOfDay(newYork).toInstant()
        val event = timed("dst-overnight", "2026-03-08T23:30:00-04:00", "2026-03-09T00:30:00-04:00")

        assertEquals(Duration.ofHours(23), Duration.between(start, end))
        assertEquals(listOf("dst-overnight"), ids(CalendarRules.occurringToday(listOf(event), dstDay, newYork)))
    }

    @Test
    fun nextTimedIsNullWhenOnlyTomorrowHasAnEvent() {
        val tomorrow = timed("tomorrow", "2026-11-01T08:00:00+09:00", "2026-11-01T09:00:00+09:00")
        assertNull(CalendarRules.nextTimed(listOf(tomorrow), today, zone, Instant.parse("2026-10-31T00:00:00Z")))
    }

    @Test
    fun nextRenderBoundaryUsesStrictlyFutureEventStartAndEndThenLocalMidnight() {
        val events = listOf(
            timed("finished", "2026-10-31T08:00:00+09:00", "2026-10-31T09:00:00+09:00"),
            timed("next", "2026-10-31T10:00:00+09:00", "2026-10-31T11:00:00+09:00"),
            allDay("all-day", "2026-10-31", "2026-11-01"),
        )
        val beforeStart = ZonedDateTime.parse("2026-10-31T09:00:00+09:00[Asia/Seoul]")
        val atStart = ZonedDateTime.parse("2026-10-31T10:00:00+09:00[Asia/Seoul]")
        val afterEvents = ZonedDateTime.parse("2026-10-31T23:00:00+09:00[Asia/Seoul]")

        assertEquals(Instant.parse("2026-10-31T01:00:00Z"), CalendarBoundaryRules.nextRenderBoundary(events, beforeStart))
        assertEquals(Instant.parse("2026-10-31T02:00:00Z"), CalendarBoundaryRules.nextRenderBoundary(events, atStart))
        assertEquals(Instant.parse("2026-10-31T15:00:00Z"), CalendarBoundaryRules.nextRenderBoundary(events, afterEvents))
    }

    @Test
    fun nextBoundaryUsesRealLocalMidnightAcrossDaylightSavingChange() {
        val newYork = ZoneId.of("America/New_York")
        val now = ZonedDateTime.parse("2026-03-08T12:00:00-04:00[America/New_York]")

        assertEquals(
            LocalDate.parse("2026-03-09").atStartOfDay(newYork).toInstant(),
            CalendarBoundaryRules.nextRenderBoundary(emptyList(), now),
        )
    }

    private fun ids(items: List<CalendarOccurrence>) = items.map { it.event.id }

    private fun allDay(id: String, start: String, end: String) =
        CalendarEvent(id, id, allDay = true, start = start, end = end)

    private fun timed(id: String, start: String, end: String) =
        CalendarEvent(id, id, allDay = false, start = start, end = end)
}
