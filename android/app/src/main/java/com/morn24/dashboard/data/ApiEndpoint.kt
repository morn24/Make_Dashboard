package com.morn24.dashboard.data

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl

/**
 * GitHub API base address. Release code always talks to api.github.com; the debug source set may
 * install [override] (see DebugApiEndpointProvider) so emulator checks can use a local fake server
 * instead of the real data repository.
 */
object ApiEndpoint {
    private val DEFAULT = "https://api.github.com/".toHttpUrl()

    @Volatile
    var override: (() -> HttpUrl?)? = null

    fun baseUrl(): HttpUrl = override?.invoke() ?: DEFAULT
}
