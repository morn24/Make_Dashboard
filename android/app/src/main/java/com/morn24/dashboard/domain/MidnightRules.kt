package com.morn24.dashboard.domain

import java.time.Duration
import java.time.ZonedDateTime

object MidnightRules {
    fun nextLocalMidnight(now: ZonedDateTime): ZonedDateTime =
        now.toLocalDate().plusDays(1).atStartOfDay(now.zone)

    /** WorkManager runs inexactly; the small grace places its target just after local midnight. */
    fun delayUntilNextLocalMidnight(now: ZonedDateTime, graceMillis: Long = 1_000L): Long =
        (Duration.between(now, nextLocalMidnight(now)).toMillis() + graceMillis).coerceAtLeast(1L)
}
