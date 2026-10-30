package com.morn24.dashboard.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProjectRulesTest {
    @Test
    fun statusGroupsAreOrderedAndStable() {
        val projects = listOf(
            project("paused-1", "paused"),
            project("done-1", "done"),
            project("active-1", "active"),
            project("active-2", "active"),
            project("paused-2", "paused"),
        )

        assertEquals(
            listOf("active-1", "active-2", "paused-1", "paused-2", "done-1"),
            ProjectRules.displayOrder(projects).map { it.id },
        )
        assertEquals(listOf("active-1", "active-2"), ProjectRules.active(projects).map { it.id })
    }

    @Test
    fun nextTaskSkipsCheckedTextWithoutChangingStoredOrder() {
        val project = project("p", next = listOf("first", "second", "third"), checked = listOf(ProjectCheck("first")))

        assertEquals("second", ProjectRules.nextTask(project))
        assertEquals(listOf("first", "second", "third"), project.next)
    }

    @Test
    fun allNextTasksCheckedHasNoOpenNextTask() {
        val project = project("p", next = listOf("first", "second"), checked = listOf(ProjectCheck("first"), ProjectCheck("second")))
        assertNull(ProjectRules.nextTask(project))
    }

    private fun project(
        id: String,
        status: String = "active",
        next: List<String> = emptyList(),
        checked: List<ProjectCheck> = emptyList(),
    ) = Project(id = id, name = id, status = status, next = next, checked = checked)
}
