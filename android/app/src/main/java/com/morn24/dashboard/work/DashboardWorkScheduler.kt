package com.morn24.dashboard.work

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit
import com.morn24.dashboard.domain.MidnightRules
import com.morn24.dashboard.widget.DashboardWidgetReceiver

object DashboardWorkScheduler {
    private const val PERIODIC_REFRESH = "dashboard-refresh-periodic"
    private const val IMMEDIATE_REFRESH = "dashboard-refresh-immediate"
    private const val MIDNIGHT_RENDER = "dashboard-midnight-render"

    fun onWidgetAdded(context: Context) {
        schedulePeriodicRefresh(context)
        scheduleNextMidnight(context)
        requestRefresh(context)
    }

    fun onSettingsSaved(context: Context) {
        if (hasWidget(context)) {
            schedulePeriodicRefresh(context)
            scheduleNextMidnight(context)
        }
        requestRefresh(context)
    }

    fun onSystemTimeChanged(context: Context) {
        if (!hasWidget(context)) return
        scheduleNextMidnight(context)
        requestRefresh(context)
    }

    fun onLastWidgetRemoved(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(PERIODIC_REFRESH)
        WorkManager.getInstance(context).cancelUniqueWork(IMMEDIATE_REFRESH)
        WorkManager.getInstance(context).cancelUniqueWork(MIDNIGHT_RENDER)
    }

    fun requestRefresh(context: Context) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        val request = OneTimeWorkRequestBuilder<RefreshDashboardWorker>()
            .setConstraints(constraints)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(IMMEDIATE_REFRESH, ExistingWorkPolicy.KEEP, request)
    }

    fun scheduleNextMidnight(context: Context) {
        if (!hasWidget(context)) return
        val now = ZonedDateTime.now()
        val delayMillis = MidnightRules.delayUntilNextLocalMidnight(now)
        val request = OneTimeWorkRequestBuilder<MidnightRenderWorker>()
            .setInitialDelay(delayMillis, TimeUnit.MILLISECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(MIDNIGHT_RENDER, ExistingWorkPolicy.REPLACE, request)
    }

    fun schedulePeriodicRefresh(context: Context) {
        if (!hasWidget(context)) return
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        val request = PeriodicWorkRequestBuilder<RefreshDashboardWorker>(30, TimeUnit.MINUTES)
            .setConstraints(constraints)
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            PERIODIC_REFRESH,
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
    }

    fun hasWidget(context: Context): Boolean {
        val component = ComponentName(context, DashboardWidgetReceiver::class.java)
        return AppWidgetManager.getInstance(context).getAppWidgetIds(component).isNotEmpty()
    }
}
