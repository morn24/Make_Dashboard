package com.morn24.dashboard.data

import kotlinx.coroutines.sync.Mutex

/** Serializes remote refreshes and todo mutations within the app process. */
object DashboardSyncCoordinator {
    val mutex = Mutex()
}
