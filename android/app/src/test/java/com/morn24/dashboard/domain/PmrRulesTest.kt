package com.morn24.dashboard.domain

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PmrRulesTest {
    private val now = Instant.parse("2026-11-07T03:00:00Z")
    private val fresh = "2026-11-07T10:00:00+09:00" // 1 hour before now

    @Test
    fun nothingShowsWithoutDataOrWhenEverythingIsFine() {
        assertNull(PmrRules.chip(null, now))
        assertNull(PmrRules.chip(PmrSummary(generatedAt = fresh), now))
        assertNull(PmrRules.chip(PmrSummary(), now)) // no generatedAt: cannot judge staleness
    }

    @Test
    fun pendingDecisionsAreShownWithTheirCount() {
        val chip = PmrRules.chip(PmrSummary(generatedAt = fresh, decisionsNeed = 2), now)!!
        assertEquals("PMR 결정 2", chip.text)
        assertFalse(chip.warning)
    }

    @Test
    fun failedJobsComeFirstAndUseTheWarningStyle() {
        val chip = PmrRules.chip(PmrSummary(generatedAt = fresh, decisionsNeed = 2, failedJobs = 1), now)!!
        assertEquals("PMR 실패 1", chip.text)
        assertTrue(chip.warning)
    }

    @Test
    fun aSyncOlderThanADayShowsDisconnectedUnlessSomethingMoreUrgentExists() {
        val old = "2026-11-05T10:00:00+09:00"
        assertEquals("PMR 끊김", PmrRules.chip(PmrSummary(generatedAt = old), now)!!.text)
        assertEquals("PMR 결정 1", PmrRules.chip(PmrSummary(generatedAt = old, decisionsNeed = 1), now)!!.text)
        // Exactly one day is still fine.
        assertNull(PmrRules.chip(PmrSummary(generatedAt = "2026-11-06T12:00:00+09:00"), now))
    }

    @Test
    fun chipOpensThePmrTabOfTheWebApp() {
        assertEquals("https://morn24.github.io/Make_Dashboard/#pmr", PmrRules.pmrUrl("https://morn24.github.io/Make_Dashboard/"))
        assertEquals("https://example.com/app/#pmr", PmrRules.pmrUrl("https://example.com/app/#other"))
    }

    @Test
    fun calendarLinksMatchTheWebGcalUrlAndTheAppContentAddress() {
        val day = LocalDate.of(2026, 11, 7)
        assertEquals("https://calendar.google.com/calendar/r/day/2026/11/7", CalendarLinks.webUrl(day))
        val seoul = ZoneId.of("Asia/Seoul")
        val millis = day.atStartOfDay(seoul).toInstant().toEpochMilli()
        assertEquals("content://com.android.calendar/time/$millis", CalendarLinks.appUri(day, seoul))
    }
}
