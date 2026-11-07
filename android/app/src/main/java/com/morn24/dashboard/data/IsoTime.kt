package com.morn24.dashboard.data

import java.time.Clock
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/** `updatedAt` / `createdAt` style timestamps: ISO 8601 with offset, whole seconds (docs/data-format.md). */
object IsoTime {
    fun now(clock: Clock = Clock.systemDefaultZone()): String =
        OffsetDateTime.now(clock).truncatedTo(ChronoUnit.SECONDS).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
}
