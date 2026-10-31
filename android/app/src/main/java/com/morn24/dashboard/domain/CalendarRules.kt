package com.morn24.dashboard.domain

import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZonedDateTime

data class CalendarOccurrence(
    val event: CalendarEvent,
    val startInstant: Instant?,
    val endInstant: Instant?,
    val sourceIndex: Int,
) {
    val allDay: Boolean get() = event.allDay
}

object CalendarRules {
    /**
     * The device's local day defines "today". Time-zone strings on the calendar file are
     * descriptive; event Instants already carry their own offset and are compared in device time.
     */
    fun occurringToday(
        events: List<CalendarEvent>,
        today: LocalDate,
        deviceZone: ZoneId,
    ): List<CalendarOccurrence> {
        val dayStart = today.atStartOfDay(deviceZone).toInstant()
        val nextDayStart = today.plusDays(1).atStartOfDay(deviceZone).toInstant()

        return events.mapIndexedNotNull { index, event ->
            if (event.allDay) {
                val startDate = parseDate(event.start)
                val endDate = parseDate(event.end)
                if (startDate != null && endDate != null && startDate <= today && today < endDate) {
                    CalendarOccurrence(event, null, null, index)
                } else {
                    null
                }
            } else {
                val start = parseInstant(event.start)
                val end = parseInstant(event.end)
                if (start != null && end != null && start < end && start < nextDayStart && end > dayStart) {
                    CalendarOccurrence(event, start, end, index)
                } else {
                    null
                }
            }
        }.sortedWith(
            compareBy<CalendarOccurrence> { if (it.allDay) 0 else 1 }
                .thenBy { it.startInstant ?: Instant.MIN }
                .thenBy { it.sourceIndex },
        )
    }

    /** All-day events remain relevant for the whole date; ended timed events are omitted. */
    fun remainingToday(
        events: List<CalendarEvent>,
        today: LocalDate,
        deviceZone: ZoneId,
        now: Instant,
    ): List<CalendarOccurrence> = occurringToday(events, today, deviceZone).filter {
        it.allDay || it.endInstant?.isAfter(now) == true
    }

    /** The first not-ended timed event today, including one that has already started. */
    fun nextTimed(
        events: List<CalendarEvent>,
        today: LocalDate,
        deviceZone: ZoneId,
        now: Instant,
    ): CalendarOccurrence? = occurringToday(events, today, deviceZone).firstOrNull {
        !it.allDay && it.endInstant?.isAfter(now) == true
    }

    fun isInProgress(occurrence: CalendarOccurrence, now: Instant): Boolean =
        !occurrence.allDay && occurrence.startInstant?.let { it <= now } == true &&
            occurrence.endInstant?.isAfter(now) == true

    fun hasEnded(occurrence: CalendarOccurrence, now: Instant): Boolean =
        !occurrence.allDay && occurrence.endInstant?.let { !it.isAfter(now) } == true

    private fun parseDate(value: String): LocalDate? = runCatching { LocalDate.parse(value) }.getOrNull()

    private fun parseInstant(value: String): Instant? =
        runCatching { OffsetDateTime.parse(value).toInstant() }.getOrNull()
}

/** The next local render change for calendar labels, event state, or today's boundary. */
object CalendarBoundaryRules {
    fun nextRenderBoundary(events: List<CalendarEvent>, now: ZonedDateTime): Instant {
        val nowInstant = now.toInstant()
        val midnight = now.toLocalDate().plusDays(1).atStartOfDay(now.zone).toInstant()
        val eventBoundaries = events.asSequence()
            .filterNot(CalendarEvent::allDay)
            .flatMap { event -> sequenceOf(parseEventInstant(event.start), parseEventInstant(event.end)) }
            .filterNotNull()
            .filter { it.isAfter(nowInstant) && it.isBefore(midnight) }
        return (eventBoundaries + sequenceOf(midnight)).minOrNull() ?: midnight
    }

    private fun parseEventInstant(value: String): Instant? =
        runCatching { OffsetDateTime.parse(value).toInstant() }.getOrNull()
}
