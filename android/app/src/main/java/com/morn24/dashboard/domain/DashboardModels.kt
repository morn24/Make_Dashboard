package com.morn24.dashboard.domain

data class Todo(
    val id: String,
    val title: String,
    val repeat: String,
    val doneOn: String?,
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
