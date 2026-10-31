package com.morn24.dashboard.data

interface DashboardRepository {
    fun read(file: DashboardFile): RemoteRead
    fun updateTodo(todoId: String, targetDoneOn: String?): MutationResult

    /** Conditional mutation used by undo so a value changed by another device is never overwritten. */
    fun updateTodoIfCurrent(todoId: String, expectedDoneOn: String?, targetDoneOn: String?): MutationResult =
        updateTodo(todoId, targetDoneOn)
}

fun interface DashboardRepositoryFactory {
    fun create(owner: String, repository: String, branch: String, token: String): DashboardRepository
}

class DefaultDashboardRepositoryFactory : DashboardRepositoryFactory {
    override fun create(owner: String, repository: String, branch: String, token: String): DashboardRepository =
        GitHubRepo(owner, repository, branch, token)
}
