package com.morn24.dashboard.domain

import java.time.Duration
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Test

class MidnightRulesTest {
    @Test
    fun schedulesJustAfterTheNextDeviceLocalMidnight() {
        val now = ZonedDateTime.parse("2026-10-31T23:59:00+09:00[Asia/Seoul]")

        assertEquals(61_000L, MidnightRules.delayUntilNextLocalMidnight(now))
        assertEquals(ZonedDateTime.parse("2026-11-01T00:00+09:00[Asia/Seoul]"), MidnightRules.nextLocalMidnight(now))
    }

    @Test
    fun nextDayDelayUsesTheLocalCalendarAcrossDaylightSavingChanges() {
        val now = ZonedDateTime.parse("2026-03-08T00:00-08:00[America/Los_Angeles]")
        val nextMidnight = MidnightRules.nextLocalMidnight(now)

        assertEquals(ZonedDateTime.parse("2026-03-09T00:00-07:00[America/Los_Angeles]"), nextMidnight)
        assertEquals(Duration.ofHours(23).toMillis() + 1_000L, MidnightRules.delayUntilNextLocalMidnight(now))
    }
}
