package com.morn24.dashboard.work

import android.content.Context
import androidx.glance.appwidget.updateAll
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.morn24.dashboard.data.AndroidKeystoreAesKeyProvider
import com.morn24.dashboard.data.DashboardStore
import com.morn24.dashboard.data.DashboardSyncCoordinator
import com.morn24.dashboard.data.DashboardSyncEngine
import com.morn24.dashboard.data.DefaultDashboardRepositoryFactory
import com.morn24.dashboard.data.TokenCipher
import com.morn24.dashboard.widget.DashboardWidget
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.withLock

class RefreshDashboardWorker(
    appContext: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(appContext, workerParams) {
    override suspend fun doWork(): Result = try {
        withContext(Dispatchers.IO) {
            DashboardSyncCoordinator.mutex.withLock {
                DashboardSyncEngine(
                    store = DashboardStore.from(applicationContext),
                    repositoryFactory = DefaultDashboardRepositoryFactory(),
                    tokenCipher = TokenCipher(AndroidKeystoreAesKeyProvider()),
                ).load()
            }
        }
        DashboardWidget().updateAll(applicationContext)
        Result.success()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        runCatching { DashboardWidget().updateAll(applicationContext) }
        Result.retry()
    }
}
