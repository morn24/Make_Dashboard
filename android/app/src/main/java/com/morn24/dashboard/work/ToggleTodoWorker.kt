package com.morn24.dashboard.work

import android.content.Context
import androidx.glance.appwidget.updateAll
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.morn24.dashboard.data.AndroidKeystoreAesKeyProvider
import com.morn24.dashboard.data.DashboardStore
import com.morn24.dashboard.data.DefaultDashboardRepositoryFactory
import com.morn24.dashboard.data.TokenCipher
import com.morn24.dashboard.data.ToggleTodoProcessor
import com.morn24.dashboard.widget.DashboardWidget
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ToggleTodoWorker(
    appContext: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(appContext, workerParams) {
    override suspend fun doWork(): Result {
        val todoId = inputData.getString(KEY_TODO_ID) ?: return Result.success()
        val operationId = inputData.getString(KEY_OPERATION_ID) ?: return Result.success()
        return try {
            withContext(Dispatchers.IO) {
                ToggleTodoProcessor(
                    store = DashboardStore.from(applicationContext),
                    repositoryFactory = DefaultDashboardRepositoryFactory(),
                    tokenCipher = TokenCipher(AndroidKeystoreAesKeyProvider()),
                ).process(todoId, operationId)
            }
            Result.success()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            val store = DashboardStore.from(applicationContext)
            val cleanup = runCatching {
                store.pendingToggle(todoId)?.takeIf { it.operationId == operationId }?.let { pending ->
                    store.recordPendingFailure(
                        pending,
                        if (pending.undoRecordId == null) {
                            "할 일 작업을 완료하지 못했어요. 체크를 원래 상태로 되돌렸어요."
                        } else {
                            "실행 취소를 저장하지 못했어요. 완료 상태는 유지했어요."
                        },
                    )
                }
            }
            if (cleanup.isSuccess) Result.success() else Result.retry()
        } finally {
            runCatching { DashboardWidget().updateAll(applicationContext) }
        }
    }

    companion object {
        const val KEY_TODO_ID = "todo_id"
        const val KEY_OPERATION_ID = "operation_id"
        fun uniqueWorkName(operationId: String): String = "todo-toggle-$operationId"
    }
}
