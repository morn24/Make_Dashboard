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
import com.morn24.dashboard.data.CompletedTodoUndo
import com.morn24.dashboard.work.ToggleTodoWorker
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

class ToggleTodoAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val todoId = parameters[TodoToggleParameters.todoId] ?: return
        val tapDate = parameters[TodoToggleParameters.tappedDate] ?: return
        val target = parameters[TodoToggleParameters.targetDoneOn] ?: return
        val title = parameters[TodoToggleParameters.title] ?: "할 일"
        val currentDate = LocalDate.now(ZoneId.systemDefault()).toString()
        if (tapDate != currentDate) {
            DashboardWidget().update(context, glanceId)
            return
        }
        if (target != CLEAR_DONE_TARGET && target != tapDate) return

        val store = DashboardStore.from(context)
        val generation = store.generation()
        val action = PendingTodoToggle(
            operationId = UUID.randomUUID().toString(),
            todoId = todoId,
            targetDoneOn = if (target == CLEAR_DONE_TARGET) null else target,
            tappedDate = tapDate,
            expectedGeneration = generation,
            createdAtMillis = System.currentTimeMillis(),
            title = title,
        )
        if (!store.enqueuePendingToggle(action)) {
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
            store.recordPendingFailure(action, "할 일 작업을 시작하지 못했어요. 체크를 원래 상태로 되돌렸어요.")
            runCatching { DashboardWidget().update(context, glanceId) }
        }
    }
}

class UndoTodoAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val recordId = parameters[KEY_RECORD_ID] ?: return
        val store = DashboardStore.from(context)
        val undo = store.completedUndo()
        val today = LocalDate.now(ZoneId.systemDefault()).toString()
        val generation = store.generation()
        if (undo == null || undo.recordId != recordId || undo.completedDate != today || undo.generation != generation) {
            runCatching { DashboardWidget().update(context, glanceId) }
            return
        }
        val pending = PendingTodoToggle(
            operationId = UUID.randomUUID().toString(),
            todoId = undo.todoId,
            targetDoneOn = undo.originalDoneOn,
            tappedDate = today,
            expectedGeneration = generation,
            createdAtMillis = System.currentTimeMillis(),
            title = undo.title,
            undoRecordId = undo.recordId,
            requiresExpectedMatch = true,
            expectedDoneOn = undo.completedDoneOn,
        )
        if (!store.enqueuePendingToggle(pending)) {
            runCatching { DashboardWidget().update(context, glanceId) }
            return
        }
        runCatching { DashboardWidget().update(context, glanceId) }
        try {
            WorkManager.getInstance(context).enqueueUniqueWork(
                ToggleTodoWorker.uniqueWorkName(pending.operationId),
                ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<ToggleTodoWorker>()
                    .setInputData(workDataOf(
                        ToggleTodoWorker.KEY_TODO_ID to pending.todoId,
                        ToggleTodoWorker.KEY_OPERATION_ID to pending.operationId,
                    ))
                    .build(),
            )
        } catch (_: Exception) {
            store.recordPendingFailure(pending, "실행 취소 작업을 시작하지 못했어요. 완료 상태는 유지했어요.")
            runCatching { DashboardWidget().update(context, glanceId) }
        }
    }

    companion object {
        val KEY_RECORD_ID = ActionParameters.Key<String>("undoRecordId")
    }
}
