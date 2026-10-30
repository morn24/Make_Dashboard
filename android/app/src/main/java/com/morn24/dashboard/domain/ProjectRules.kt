package com.morn24.dashboard.domain

object ProjectRules {
    private val statusOrder = listOf("active", "paused", "done")

    /** Stable status grouping; projects retain their stored array order per status. */
    fun displayOrder(projects: List<Project>): List<Project> =
        statusOrder.flatMap { status -> projects.filter { normalizedStatus(it.status) == status } } +
            projects.filter { normalizedStatus(it.status) !in statusOrder }

    fun active(projects: List<Project>): List<Project> =
        projects.filter { normalizedStatus(it.status) == "active" }

    fun nextTask(project: Project): String? {
        val checkedTexts = project.checked.asSequence().map { it.text }.toHashSet()
        return project.next.firstOrNull { it.isNotBlank() && it !in checkedTexts }
    }

    private fun normalizedStatus(status: String): String = status.ifBlank { "active" }
}
