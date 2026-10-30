package com.morn24.dashboard.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first

private val Context.dashboardPreferencesDataStore: DataStore<Preferences> by preferencesDataStore(name = "dashboard")

class DashboardStore(private val dataStore: DataStore<Preferences>) {
    suspend fun settings(): DashboardSettings {
        val preferences = dataStore.data.first()
        val iv = preferences[SettingsKeys.tokenIv]
        val ciphertext = preferences[SettingsKeys.tokenCiphertext]
        val token = if (iv != null && ciphertext != null) EncryptedToken(iv, ciphertext) else null
        return DashboardSettings(
            owner = preferences[SettingsKeys.owner] ?: DashboardSettings.DEFAULT_OWNER,
            repository = preferences[SettingsKeys.repository] ?: DashboardSettings.DEFAULT_REPOSITORY,
            branch = preferences[SettingsKeys.branch] ?: DashboardSettings.DEFAULT_BRANCH,
            webUrl = preferences[SettingsKeys.webUrl] ?: DashboardSettings.DEFAULT_WEB_URL,
            encryptedToken = token,
        )
    }

    suspend fun generation(): Long = dataStore.data.first()[SettingsKeys.generation] ?: 0L

    /**
     * Increment generation for every saved setting change. A repository/branch switch starts
     * with a fresh cache and clears pending toggle keys added by the worker layer.
     */
    suspend fun saveSettings(settings: DashboardSettings): Long {
        var newGeneration = 0L
        dataStore.edit { mutable ->
            val oldIdentity = listOf(
                mutable[SettingsKeys.owner] ?: DashboardSettings.DEFAULT_OWNER,
                mutable[SettingsKeys.repository] ?: DashboardSettings.DEFAULT_REPOSITORY,
                mutable[SettingsKeys.branch] ?: DashboardSettings.DEFAULT_BRANCH,
            ).joinToString("/")
            val newIdentity = listOf(settings.owner, settings.repository, settings.branch).joinToString("/")
            val changed = oldIdentity != newIdentity ||
                mutable[SettingsKeys.webUrl] != settings.webUrl ||
                mutable[SettingsKeys.tokenIv] != settings.encryptedToken?.ivBase64 ||
                mutable[SettingsKeys.tokenCiphertext] != settings.encryptedToken?.ciphertextBase64

            if (oldIdentity != newIdentity) clearCachedFiles(mutable)
            mutable[SettingsKeys.owner] = settings.owner
            mutable[SettingsKeys.repository] = settings.repository
            mutable[SettingsKeys.branch] = settings.branch
            mutable[SettingsKeys.webUrl] = settings.webUrl
            settings.encryptedToken?.let {
                mutable[SettingsKeys.tokenIv] = it.ivBase64
                mutable[SettingsKeys.tokenCiphertext] = it.ciphertextBase64
            } ?: run {
                mutable.remove(SettingsKeys.tokenIv)
                mutable.remove(SettingsKeys.tokenCiphertext)
            }
            val currentGeneration = mutable[SettingsKeys.generation] ?: 0L
            newGeneration = if (changed) currentGeneration + 1 else currentGeneration
            mutable[SettingsKeys.generation] = newGeneration
        }
        return newGeneration
    }

    suspend fun cached(file: DashboardFile): CachedJsonFile {
        val preferences = dataStore.data.first()
        return CachedJsonFile(
            rawJson = preferences[cacheRawKey(file)],
            sha = preferences[cacheShaKey(file)],
            lastSuccessAtMillis = preferences[cacheSuccessKey(file)],
            error = preferences[cacheErrorKey(file)],
        )
    }

    suspend fun saveSuccessfulFile(file: DashboardFile, rawJson: String, sha: String?, atMillis: Long) {
        dataStore.edit { preferences ->
            preferences[cacheRawKey(file)] = rawJson
            sha?.let { preferences[cacheShaKey(file)] = it } ?: preferences.remove(cacheShaKey(file))
            preferences[cacheSuccessKey(file)] = atMillis
            preferences.remove(cacheErrorKey(file))
        }
    }

    /** Stores a response only if it still belongs to the current saved connection. */
    suspend fun saveSuccessfulFileIfGeneration(
        file: DashboardFile,
        rawJson: String,
        sha: String?,
        atMillis: Long,
        expectedGeneration: Long,
    ): Boolean {
        var saved = false
        dataStore.edit { preferences ->
            if ((preferences[SettingsKeys.generation] ?: 0L) != expectedGeneration) return@edit
            preferences[cacheRawKey(file)] = rawJson
            sha?.let { preferences[cacheShaKey(file)] = it } ?: preferences.remove(cacheShaKey(file))
            preferences[cacheSuccessKey(file)] = atMillis
            preferences.remove(cacheErrorKey(file))
            saved = true
        }
        return saved
    }

    suspend fun recordFileError(file: DashboardFile, safeMessage: String) {
        dataStore.edit { it[cacheErrorKey(file)] = safeMessage }
    }

    suspend fun recordFileErrorIfGeneration(file: DashboardFile, safeMessage: String, expectedGeneration: Long): Boolean {
        var recorded = false
        dataStore.edit { preferences ->
            if ((preferences[SettingsKeys.generation] ?: 0L) != expectedGeneration) return@edit
            preferences[cacheErrorKey(file)] = safeMessage
            recorded = true
        }
        return recorded
    }

    private fun clearCachedFiles(preferences: MutablePreferences) {
        DashboardFile.entries.forEach { file ->
            preferences.remove(cacheRawKey(file))
            preferences.remove(cacheShaKey(file))
            preferences.remove(cacheSuccessKey(file))
            preferences.remove(cacheErrorKey(file))
        }
        // Pending-action preferences use this prefix; remove them when a saved connection changes.
        preferences.asMap().keys.filter { it.name.startsWith(PENDING_PREFIX) }.forEach { preferences.remove(it) }
    }

    private object SettingsKeys {
        val owner = stringPreferencesKey("settings_owner")
        val repository = stringPreferencesKey("settings_repository")
        val branch = stringPreferencesKey("settings_branch")
        val webUrl = stringPreferencesKey("settings_web_url")
        val tokenIv = stringPreferencesKey("settings_token_iv")
        val tokenCiphertext = stringPreferencesKey("settings_token_ciphertext")
        val generation = longPreferencesKey("settings_generation")
    }

    companion object {
        private const val CACHE_PREFIX = "cache_"
        private const val PENDING_PREFIX = "pending_toggle_"

        private fun cacheRawKey(file: DashboardFile) = stringPreferencesKey("${CACHE_PREFIX}${file.key}_json")
        private fun cacheShaKey(file: DashboardFile) = stringPreferencesKey("${CACHE_PREFIX}${file.key}_sha")
        private fun cacheSuccessKey(file: DashboardFile) = longPreferencesKey("${CACHE_PREFIX}${file.key}_success")
        private fun cacheErrorKey(file: DashboardFile) = stringPreferencesKey("${CACHE_PREFIX}${file.key}_error")

        fun from(context: Context): DashboardStore = DashboardStore(context.applicationContext.dashboardPreferencesDataStore)
    }
}
