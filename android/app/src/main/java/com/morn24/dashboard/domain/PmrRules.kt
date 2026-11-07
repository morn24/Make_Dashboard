package com.morn24.dashboard.domain

import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime

data class PmrChip(val text: String, val warning: Boolean)

/**
 * The one small chip the widget header shows for PMR-Wiki. Nothing is shown when everything is fine,
 * when the file does not exist, or when there is no data. Order: failed jobs, pending decisions,
 * then "PMR 끊김" when the PC has not synced for more than a day.
 */
object PmrRules {
    val STALE_AFTER: Duration = Duration.ofDays(1)

    fun chip(pmr: PmrSummary?, now: Instant): PmrChip? {
        if (pmr == null) return null
        return when {
            pmr.failedJobs > 0 -> PmrChip("PMR 실패 ${pmr.failedJobs}", warning = true)
            pmr.decisionsNeed > 0 -> PmrChip("PMR 결정 ${pmr.decisionsNeed}", warning = false)
            isStale(pmr, now) -> PmrChip("PMR 끊김", warning = true)
            else -> null
        }
    }

    private fun isStale(pmr: PmrSummary, now: Instant): Boolean {
        val generated = pmr.generatedAt?.let { runCatching { OffsetDateTime.parse(it).toInstant() }.getOrNull() }
            ?: return false
        return Duration.between(generated, now) > STALE_AFTER
    }

    /** Chip tap target: the web app opened on its PMR tab (`#pmr`). */
    fun pmrUrl(webUrl: String): String = webUrl.substringBefore('#') + "#pmr"
}
