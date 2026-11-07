package com.morn24.dashboard.domain

/**
 * One item of `todos.json`. Optional fields mirror docs/data-format.md.
 * `history == null` means the key is absent (legacy item); an empty list is a present key.
 */
data class Todo(
    val id: String,
    val title: String,
    val repeat: String,
    val doneOn: String?,
    val days: List<Int> = emptyList(),
    val date: String? = null,
    val time: String? = null,
    val history: List<String>? = null,
)

data class ProjectCheck(
    val text: String,
    val at: String? = null,
)

data class Project(
    val id: String,
    val name: String,
    val status: String = "active",
    val progress: Int = 0,
    val current: String? = null,
    val next: List<String> = emptyList(),
    val checked: List<ProjectCheck> = emptyList(),
    val summary: String? = null,
    val updatedAt: String? = null,
)

data class CalendarEvent(
    val id: String,
    val title: String,
    val calendar: String? = null,
    val color: String? = null,
    val allDay: Boolean,
    val start: String,
    val end: String,
    val location: String? = null,
)

/** Read-only summary of `data/pmr-wiki.json` used for the widget chip. */
data class PmrSummary(
    val generatedAt: String? = null,
    val decisionsNeed: Int = 0,
    val failedJobs: Int = 0,
)
