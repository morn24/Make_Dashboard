package com.morn24.dashboard.data

interface DashboardRepository {
    fun read(file: DashboardFile): RemoteRead
    fun updateTodo(todoId: String, targetDoneOn: String?): MutationResult
}

fun interface DashboardRepositoryFactory {
    fun create(owner: String, repository: String, branch: String, token: String): DashboardRepository
}

class DefaultDashboardRepositoryFactory : DashboardRepositoryFactory {
    override fun create(owner: String, repository: String, branch: String, token: String): DashboardRepository =
        GitHubRepo(owner, repository, branch, token)
}
