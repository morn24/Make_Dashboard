package com.morn24.dashboard.domain

import java.time.LocalDate
import java.time.ZoneId

/** Addresses that open today in a calendar app, mirroring web/app.js gcalUrl for the web fallback. */
object CalendarLinks {
    /** Opens the device calendar app on the start of [day] (ACTION_VIEW on this content address). */
    fun appUri(day: LocalDate, zone: ZoneId): String =
        "content://com.android.calendar/time/${day.atStartOfDay(zone).toInstant().toEpochMilli()}"

    /** Same web address as the web app uses when the Google Calendar app is missing. */
    fun webUrl(day: LocalDate): String =
        "https://calendar.google.com/calendar/r/day/${day.year}/${day.monthValue}/${day.dayOfMonth}"
}
