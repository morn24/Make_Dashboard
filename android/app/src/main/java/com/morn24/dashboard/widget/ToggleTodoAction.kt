package com.morn24.dashboard.widget

import android.content.Context
import androidx.glance.GlanceId
import androidx.glance.action.ActionParameters
import androidx.glance.appwidget.action.ActionCallback
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.morn24.dashboard.data.DashboardStore
import com.morn24.dashboard.data.PendingTodoToggle
import com.morn24.dashboard.work.ToggleTodoWorker
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

class ToggleTodoAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val todoId = parameters[TodoToggleParameters.todoId] ?: return
        val tapDate = parameters[TodoToggleParameters.tappedDate] ?: return
        val target = parameters[TodoToggleParameters.targetDoneOn] ?: return
        val currentDate = LocalDate.now(ZoneId.systemDefault()).toString()
        if (tapDate != currentDate) {
            DashboardWidget().update(context, glanceId)
            return
        }
        if (target != CLEAR_DONE_TARGET && target != tapDate) return

        val store = DashboardStore.from(context)
        val generation = store.generation()
        val existing = store.pendingToggle(todoId)
        val action = existing ?: run {
            val proposed = PendingTodoToggle(
                operationId = UUID.randomUUID().toString(),
                todoId = todoId,
                targetDoneOn = if (target == CLEAR_DONE_TARGET) null else target,
                tappedDate = tapDate,
                expectedGeneration = generation,
                createdAtMillis = System.currentTimeMillis(),
            )
            if (store.enqueuePendingToggle(proposed)) proposed else store.pendingToggle(todoId)
        }

        if (action == null) {
            DashboardWidget().update(context, glanceId)
            return
        }

        // Render the persisted target from cache before asking WorkManager to start the PUT.
        runCatching { DashboardWidget().update(context, glanceId) }
        val request = OneTimeWorkRequestBuilder<ToggleTodoWorker>()
            .setInputData(
                workDataOf(
                    ToggleTodoWorker.KEY_TODO_ID to action.todoId,
                    ToggleTodoWorker.KEY_OPERATION_ID to action.operationId,
                ),
            )
            .build()
        try {
            WorkManager.getInstance(context).enqueueUniqueWork(
                ToggleTodoWorker.uniqueWorkName(action.operationId),
                ExistingWorkPolicy.KEEP,
                request,
            )
        } catch (_: Exception) {
            store.recordFileErrorIfGeneration(
                file = com.morn24.dashboard.data.DashboardFile.TODOS,
                safeMessage = "할 일 작업을 시작하지 못했어요. 체크를 원래 상태로 되돌렸어요.",
                expectedGeneration = action.expectedGeneration,
            )
            store.removePendingToggle(action.todoId, action.operationId)
            runCatching { DashboardWidget().update(context, glanceId) }
        }
    }
}
