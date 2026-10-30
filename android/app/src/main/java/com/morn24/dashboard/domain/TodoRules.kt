package com.morn24.dashboard.domain

data class TodoSummary(
    val visibleCount: Int,
    val doneCount: Int,
    val progress: Float,
)

object TodoRules {
    fun isDone(todo: Todo, today: String): Boolean =
        if (todo.repeat == "daily") todo.doneOn == today else todo.doneOn != null

    fun isVisible(todo: Todo, today: String): Boolean =
        todo.repeat == "daily" || todo.doneOn == null || todo.doneOn >= today

    /** Incomplete items first, preserving source array order within both groups. */
    fun displayOrder(todos: List<Todo>, today: String): List<Todo> {
        val visible = todos.filter { isVisible(it, today) }
        return visible.filterNot { isDone(it, today) } + visible.filter { isDone(it, today) }
    }

    fun summary(todos: List<Todo>, today: String): TodoSummary {
        val visible = todos.filter { isVisible(it, today) }
        val done = visible.count { isDone(it, today) }
        return TodoSummary(
            visibleCount = visible.size,
            doneCount = done,
            progress = if (visible.isEmpty()) 0f else done.toFloat() / visible.size,
        )
    }
}
