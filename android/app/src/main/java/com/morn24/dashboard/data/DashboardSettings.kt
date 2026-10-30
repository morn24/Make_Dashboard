package com.morn24.dashboard.data

import kotlinx.serialization.Serializable

data class EncryptedToken(
    val ivBase64: String,
    val ciphertextBase64: String,
)

data class DashboardSettings(
    val owner: String = DEFAULT_OWNER,
    val repository: String = DEFAULT_REPOSITORY,
    val branch: String = DEFAULT_BRANCH,
    val webUrl: String = DEFAULT_WEB_URL,
    val encryptedToken: EncryptedToken? = null,
) {
    val repositoryIdentity: String get() = "$owner/$repository@$branch"

    companion object {
        const val DEFAULT_OWNER = "morn24"
        const val DEFAULT_REPOSITORY = "dashboard-data"
        const val DEFAULT_BRANCH = "main"
        const val DEFAULT_WEB_URL = "https://morn24.github.io/Make_Dashboard/"
    }
}

enum class DashboardFile(val path: String, val key: String) {
    TODOS("data/todos.json", "todos"),
    PROJECTS("data/projects.json", "projects"),
    CALENDAR("data/calendar.json", "calendar"),
}

data class CachedJsonFile(
    val rawJson: String?,
    val sha: String?,
    val lastSuccessAtMillis: Long?,
    val error: String?,
)

@Serializable
data class PendingTodoToggle(
    val operationId: String,
    val todoId: String,
    val targetDoneOn: String?,
    val tappedDate: String,
    val expectedGeneration: Long,
    val createdAtMillis: Long,
)
