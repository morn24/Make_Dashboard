package com.morn24.dashboard.data

import kotlinx.serialization.json.JsonObject

interface DashboardRepository {
    fun read(file: DashboardFile): RemoteRead

    /**
     * Read-modify-write one file with sha conflict retries. [change] sees the newest document each
     * attempt and returns the whole new document (or none when no write is needed).
     */
    fun mutate(file: DashboardFile, message: String, change: (JsonObject) -> DocChange): MutationResult
}

fun interface DashboardRepositoryFactory {
    fun create(owner: String, repository: String, branch: String, token: String): DashboardRepository
}

class DefaultDashboardRepositoryFactory : DashboardRepositoryFactory {
    override fun create(owner: String, repository: String, branch: String, token: String): DashboardRepository =
        GitHubRepo(owner, repository, branch, token, apiBaseUrl = ApiEndpoint.baseUrl())
}
