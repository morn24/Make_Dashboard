package com.morn24.dashboard.widget

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import com.morn24.dashboard.data.ApiEndpoint
import java.io.File
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Debug builds only. When `files/debug-api-base.txt` exists (written over adb with run-as), GitHub
 * traffic goes to that address instead of api.github.com, so emulator checks can use the local
 * fake server (scripts/fake_github_server.py) and never touch the real data repository.
 * The file can only be created by someone with adb access to this debuggable app.
 */
class DebugApiEndpointProvider : ContentProvider() {
    override fun onCreate(): Boolean {
        val dir = context?.filesDir ?: return true
        ApiEndpoint.override = {
            runCatching { File(dir, "debug-api-base.txt").readText().trim().toHttpUrlOrNull() }.getOrNull()
        }
        return true
    }

    override fun query(uri: Uri, projection: Array<String>?, selection: String?, selectionArgs: Array<String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<String>?): Int = 0
}
