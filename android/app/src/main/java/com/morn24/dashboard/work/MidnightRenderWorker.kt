package com.morn24.dashboard.work

import android.content.Context
import androidx.glance.appwidget.updateAll
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.morn24.dashboard.widget.DashboardWidget
import kotlinx.coroutines.CancellationException

class MidnightRenderWorker(
    appContext: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(appContext, workerParams) {
    override suspend fun doWork(): Result {
        return try {
            // DashboardWidget renders from the raw cache, re-evaluating daily todos for today's date.
            DashboardWidget().updateAll(applicationContext)
            Result.success()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            Result.retry()
        } finally {
            if (DashboardWorkScheduler.hasWidget(applicationContext)) {
                DashboardWorkScheduler.scheduleNextMidnight(applicationContext)
                DashboardWorkScheduler.requestRefresh(applicationContext)
            }
        }
    }
}
