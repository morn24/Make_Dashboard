package com.morn24.dashboard.work

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.glance.appwidget.updateAll
import com.morn24.dashboard.data.AndroidKeystoreAesKeyProvider
import com.morn24.dashboard.data.DashboardStore
import com.morn24.dashboard.data.DashboardSyncEngine
import com.morn24.dashboard.data.DefaultDashboardRepositoryFactory
import com.morn24.dashboard.data.TokenCipher
import com.morn24.dashboard.widget.DashboardWidget
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class DashboardSystemReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in ACTIONS) return
        if (!DashboardWorkScheduler.hasWidget(context)) return
        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val snapshot = DashboardSyncEngine(
                    store = DashboardStore.from(context),
                    repositoryFactory = DefaultDashboardRepositoryFactory(),
                    tokenCipher = TokenCipher(AndroidKeystoreAesKeyProvider()),
                ).loadCached()
                DashboardWorkScheduler.onSystemTimeChanged(context, snapshot.calendarEvents)
                DashboardWidget().updateAll(context)
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        private val ACTIONS = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_DATE_CHANGED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
        )
    }
}
