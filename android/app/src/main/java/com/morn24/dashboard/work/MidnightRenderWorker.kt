package com.morn24.dashboard.work

import android.content.Context
import androidx.glance.appwidget.updateAll
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.morn24.dashboard.data.AndroidKeystoreAesKeyProvider
import com.morn24.dashboard.data.DashboardStore
import com.morn24.dashboard.data.DashboardSyncEngine
import com.morn24.dashboard.data.DefaultDashboardRepositoryFactory
import com.morn24.dashboard.data.TokenCipher
import com.morn24.dashboard.widget.DashboardWidget
import kotlinx.coroutines.CancellationException

class CalendarBoundaryWorker(
    appContext: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(appContext, workerParams) {
    override suspend fun doWork(): Result {
        return try {
            // Calendar boundaries are local render changes: read the cache, never fetch over the network.
            DashboardWidget().updateAll(applicationContext)
            val snapshot = DashboardSyncEngine(
                store = DashboardStore.from(applicationContext),
                repositoryFactory = DefaultDashboardRepositoryFactory(),
                tokenCipher = TokenCipher(AndroidKeystoreAesKeyProvider()),
            ).loadCached()
            if (DashboardWorkScheduler.hasWidget(applicationContext)) {
                DashboardWorkScheduler.scheduleNextCalendarBoundary(applicationContext, snapshot.calendarEvents)
            }
            Result.success()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            if (DashboardWorkScheduler.hasWidget(applicationContext)) {
                DashboardWorkScheduler.scheduleNextCalendarBoundary(applicationContext)
            }
            Result.retry()
        }
    }
}
