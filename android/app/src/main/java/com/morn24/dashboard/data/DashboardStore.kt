package com.morn24.dashboard.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.morn24.dashboard.domain.CalendarEvent
import com.morn24.dashboard.domain.Project
import com.morn24.dashboard.domain.Todo
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

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
            else if (changed) mutable.remove(UNDO_KEY)
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
            lastAttemptAtMillis = preferences[cacheAttemptKey(file)],
            notFoundAtMillis = preferences[cacheNotFoundKey(file)],
        )
    }

    /** Collects all widget-visible values from the same atomic preferences emission. */
    fun observeWidgetSnapshot(initial: DashboardSnapshot, widgetId: Int): Flow<DashboardSnapshot> =
        dataStore.data.map { preferences ->
            val hasToken = preferences[SettingsKeys.tokenIv] != null && preferences[SettingsKeys.tokenCiphertext] != null
            val generation = preferences[SettingsKeys.generation] ?: 0L
            val connectionState = when {
                !hasToken -> WidgetConnectionState.NEEDS_SETUP
                initial.connectionState == WidgetConnectionState.REAUTHENTICATE -> WidgetConnectionState.REAUTHENTICATE
                else -> WidgetConnectionState.READY
            }
            val statuses = DashboardFile.entries.map { file ->
                FileSyncStatus(
                    file = file,
                    lastSuccessAtMillis = preferences[cacheSuccessKey(file)],
                    lastAttemptAtMillis = preferences[cacheAttemptKey(file)],
                    error = preferences[cacheErrorKey(file)],
                    notFoundAtMillis = preferences[cacheNotFoundKey(file)],
                )
            }
            val parseErrors = mutableListOf<String>()
            fun <T> parse(file: DashboardFile, decoder: (JsonObject) -> T): T {
                val status = statuses.first { it.file == file }
                if (status.notFoundAtMillis != null) return emptyValue(file)
                val raw = preferences[cacheRawKey(file)] ?: return emptyValue(file)
                return runCatching { decoder(Json.parseToJsonElement(raw) as JsonObject) }.getOrElse {
                    parseErrors += "저장된 ${file.path} 데이터를 읽지 못했어요."
                    emptyValue(file)
                }
            }
            @Suppress("UNCHECKED_CAST")
            val todos = parse(DashboardFile.TODOS, DashboardDisplayData::todos) as List<Todo>
            @Suppress("UNCHECKED_CAST")
            val projects = parse(DashboardFile.PROJECTS, DashboardDisplayData::projects) as List<Project>
            @Suppress("UNCHECKED_CAST")
            val events = parse(DashboardFile.CALENDAR, DashboardDisplayData::events) as List<CalendarEvent>
            val pending = preferences.asMap().entries.asSequence()
                .filter { it.key.name.startsWith(PENDING_PREFIX) }
                .mapNotNull { it.value as? String }
                .mapNotNull { raw -> runCatching { pendingJson.decodeFromString<PendingTodoToggle>(raw) }.getOrNull() }
                .filter { it.expectedGeneration == generation }
                .associateBy(PendingTodoToggle::todoId)
            val overlaidTodos = todos.map { todo -> pending[todo.id]?.let { todo.copy(doneOn = it.targetDoneOn) } ?: todo }
            val appearance = preferences[widgetAppearanceKey(widgetId)]?.let(::decodeAppearance)
                ?: preferences[DEFAULT_APPEARANCE_KEY]?.let(::decodeAppearance)
                ?: WidgetAppearance.DEFAULT
            val undo = preferences[UNDO_KEY]?.let { raw -> runCatching { pendingJson.decodeFromString<CompletedTodoUndo>(raw) }.getOrNull() }
                ?.takeIf { it.generation == generation }
            val timestamps = statuses.mapNotNull(FileSyncStatus::lastSuccessAtMillis)
            val fileErrors = statuses.mapNotNull(FileSyncStatus::error)
            val missingRequiredFiles = statuses.filter {
                it.notFoundAtMillis != null && it.file != DashboardFile.CALENDAR
            }.map { "${it.file.path} 파일을 찾지 못했어요." }
            DashboardSnapshot(
                connectionState = connectionState,
                webUrl = preferences[SettingsKeys.webUrl] ?: DashboardSettings.DEFAULT_WEB_URL,
                todos = overlaidTodos,
                pendingTodoIds = pending.keys,
                projects = projects,
                calendarEvents = events,
                calendarAvailable = statuses.first { it.file == DashboardFile.CALENDAR }.notFoundAtMillis == null &&
                    preferences[cacheRawKey(DashboardFile.CALENDAR)] != null,
                lastUpdatedAtMillis = timestamps.maxOrNull(),
                lastCompleteSyncAtMillis = preferences[LAST_COMPLETE_SYNC_KEY],
                lastSyncHadErrors = preferences[LAST_SYNC_ERROR_KEY] ?: false,
                syncInProgress = preferences[SYNC_IN_PROGRESS_KEY] ?: false,
                fileStatuses = statuses,
                undo = undo,
                pendingUndoTodoId = pending.values.firstOrNull { it.undoRecordId != null }?.todoId,
                appearance = appearance,
                errors = (fileErrors + missingRequiredFiles + parseErrors + initial.errors.filterNot { it in fileErrors }).distinct(),
            )
        }

    suspend fun appearance(widgetId: Int? = null): WidgetAppearance {
        val preferences = dataStore.data.first()
        return (widgetId?.let { preferences[widgetAppearanceKey(it)] }
            ?: preferences[DEFAULT_APPEARANCE_KEY])?.let(::decodeAppearance) ?: WidgetAppearance.DEFAULT
    }

    suspend fun saveAppearance(appearance: WidgetAppearance, widgetId: Int? = null) {
        val encoded = pendingJson.encodeToString(appearance.sanitized())
        dataStore.edit { preferences ->
            if (widgetId == null) preferences[DEFAULT_APPEARANCE_KEY] = encoded
            else preferences[widgetAppearanceKey(widgetId)] = encoded
        }
    }

    suspend fun removeWidgetAppearance(widgetId: Int) {
        dataStore.edit { it.remove(widgetAppearanceKey(widgetId)) }
    }

    suspend fun completedUndo(): CompletedTodoUndo? = dataStore.data.first()[UNDO_KEY]?.let { raw ->
        runCatching { pendingJson.decodeFromString<CompletedTodoUndo>(raw) }.getOrNull()
    }

    suspend fun lastCompleteSyncAtMillis(): Long? = dataStore.data.first()[LAST_COMPLETE_SYNC_KEY]
    suspend fun lastSyncHadErrors(): Boolean = dataStore.data.first()[LAST_SYNC_ERROR_KEY] ?: false
    suspend fun syncInProgress(): Boolean = dataStore.data.first()[SYNC_IN_PROGRESS_KEY] ?: false

    suspend fun recordSyncStartedIfGeneration(atMillis: Long, expectedGeneration: Long) {
        dataStore.edit { preferences ->
            if ((preferences[SettingsKeys.generation] ?: 0L) != expectedGeneration) return@edit
            preferences[SYNC_IN_PROGRESS_KEY] = true
            preferences[SYNC_STARTED_KEY] = atMillis
        }
    }

    suspend fun recordSyncResultIfGeneration(atMillis: Long, hadErrors: Boolean, expectedGeneration: Long) {
        dataStore.edit { preferences ->
            if ((preferences[SettingsKeys.generation] ?: 0L) != expectedGeneration) return@edit
            preferences[LAST_SYNC_ERROR_KEY] = hadErrors
            preferences[SYNC_IN_PROGRESS_KEY] = false
            if (!hadErrors) preferences[LAST_COMPLETE_SYNC_KEY] = atMillis
        }
    }

    suspend fun recordFileNotFoundIfGeneration(file: DashboardFile, atMillis: Long, expectedGeneration: Long): Boolean {
        var recorded = false
        dataStore.edit { preferences ->
            if ((preferences[SettingsKeys.generation] ?: 0L) != expectedGeneration) return@edit
            preferences[cacheAttemptKey(file)] = atMillis
            preferences[cacheNotFoundKey(file)] = atMillis
            preferences.remove(cacheErrorKey(file))
            recorded = true
        }
        return recorded
    }

    suspend fun saveSuccessfulFile(file: DashboardFile, rawJson: String, sha: String?, atMillis: Long) {
        dataStore.edit { preferences ->
            preferences[cacheRawKey(file)] = rawJson
            sha?.let { preferences[cacheShaKey(file)] = it } ?: preferences.remove(cacheShaKey(file))
            preferences[cacheSuccessKey(file)] = atMillis
            preferences[cacheAttemptKey(file)] = atMillis
            preferences.remove(cacheErrorKey(file))
            preferences.remove(cacheNotFoundKey(file))
            if (file == DashboardFile.TODOS) clearTodoStaleShaGuard(preferences)
        }
    }

    /** Saves a successful mutation and remembers the previous sha for a short stale-read window. */
    suspend fun saveTodoMutationIfGeneration(
        rawJson: String,
        newSha: String,
        previousSha: String,
        atMillis: Long,
        expectedGeneration: Long,
    ): Boolean {
        var saved = false
        dataStore.edit { preferences ->
            if ((preferences[SettingsKeys.generation] ?: 0L) != expectedGeneration) return@edit
            preferences[cacheRawKey(DashboardFile.TODOS)] = rawJson
            preferences[cacheShaKey(DashboardFile.TODOS)] = newSha
            preferences[cacheSuccessKey(DashboardFile.TODOS)] = atMillis
            preferences[cacheAttemptKey(DashboardFile.TODOS)] = atMillis
            preferences.remove(cacheErrorKey(DashboardFile.TODOS))
            preferences.remove(cacheNotFoundKey(DashboardFile.TODOS))
            if (previousSha != newSha) {
                preferences[SettingsKeys.todoRejectedSha] = previousSha
                preferences[SettingsKeys.todoRejectedShaUntil] = atMillis + STALE_TODO_SHA_GUARD_MILLIS
            } else {
                clearTodoStaleShaGuard(preferences)
            }
            saved = true
        }
        return saved
    }

    suspend fun shouldIgnoreStaleTodoSha(sha: String, nowMillis: Long): Boolean {
        val preferences = dataStore.data.first()
        return preferences[SettingsKeys.todoRejectedSha] == sha &&
            (preferences[SettingsKeys.todoRejectedShaUntil] ?: Long.MIN_VALUE) > nowMillis
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
            preferences[cacheAttemptKey(file)] = atMillis
            preferences.remove(cacheErrorKey(file))
            preferences.remove(cacheNotFoundKey(file))
            if (file == DashboardFile.TODOS) clearTodoStaleShaGuard(preferences)
            saved = true
        }
        return saved
    }

    suspend fun recordFileError(file: DashboardFile, safeMessage: String) {
        dataStore.edit {
            it[cacheErrorKey(file)] = safeMessage
            it[cacheAttemptKey(file)] = System.currentTimeMillis()
            it.remove(cacheNotFoundKey(file))
        }
    }

    suspend fun recordFileErrorIfGeneration(file: DashboardFile, safeMessage: String, expectedGeneration: Long): Boolean {
        var recorded = false
        dataStore.edit { preferences ->
            if ((preferences[SettingsKeys.generation] ?: 0L) != expectedGeneration) return@edit
            preferences[cacheErrorKey(file)] = safeMessage
            preferences[cacheAttemptKey(file)] = System.currentTimeMillis()
            preferences.remove(cacheNotFoundKey(file))
            recorded = true
        }
        return recorded
    }

    suspend fun pendingToggles(): List<PendingTodoToggle> = dataStore.data.first().asMap().entries
        .asSequence()
        .filter { it.key.name.startsWith(PENDING_PREFIX) }
        .mapNotNull { it.value as? String }
        .mapNotNull { raw -> runCatching { pendingJson.decodeFromString<PendingTodoToggle>(raw) }.getOrNull() }
        .sortedBy(PendingTodoToggle::createdAtMillis)
        .toList()

    suspend fun pendingToggle(todoId: String): PendingTodoToggle? {
        val raw = dataStore.data.first()[pendingToggleKey(todoId)] ?: return null
        return runCatching { pendingJson.decodeFromString<PendingTodoToggle>(raw) }.getOrNull()
    }

    /** Atomically rejects duplicate todo actions and actions from an obsolete settings form. */
    suspend fun enqueuePendingToggle(toggle: PendingTodoToggle): Boolean {
        var accepted = false
        dataStore.edit { preferences ->
            if ((preferences[SettingsKeys.generation] ?: 0L) != toggle.expectedGeneration) return@edit
            val key = pendingToggleKey(toggle.todoId)
            if (preferences[key] != null) return@edit
            if (toggle.undoRecordId != null) {
                val undo = preferences[UNDO_KEY]?.let { raw -> runCatching { pendingJson.decodeFromString<CompletedTodoUndo>(raw) }.getOrNull() }
                if (undo?.recordId != toggle.undoRecordId || undo.generation != toggle.expectedGeneration || undo.completedDate != toggle.tappedDate) return@edit
            }
            preferences[key] = pendingJson.encodeToString(toggle)
            accepted = true
        }
        return accepted
    }

    suspend fun removePendingToggle(todoId: String, operationId: String): Boolean {
        var removed = false
        dataStore.edit { preferences ->
            val key = pendingToggleKey(todoId)
            val current = preferences[key]?.let { raw ->
                runCatching { pendingJson.decodeFromString<PendingTodoToggle>(raw) }.getOrNull()
            }
            if (current?.operationId == operationId) {
                preferences.remove(key)
                removed = true
            }
        }
        return removed
    }

    /** Commits the remote result, pending overlay, and the one-shot undo in one DataStore write. */
    suspend fun completePendingToggle(
        pending: PendingTodoToggle,
        rawJson: String,
        newSha: String,
        previousSha: String,
        atMillis: Long,
        undo: CompletedTodoUndo?,
    ): Boolean {
        var saved = false
        dataStore.edit { preferences ->
            if ((preferences[SettingsKeys.generation] ?: 0L) != pending.expectedGeneration) return@edit
            val key = pendingToggleKey(pending.todoId)
            val current = preferences[key]?.let { raw -> runCatching { pendingJson.decodeFromString<PendingTodoToggle>(raw) }.getOrNull() }
            if (current?.operationId != pending.operationId) return@edit
            preferences[cacheRawKey(DashboardFile.TODOS)] = rawJson
            preferences[cacheShaKey(DashboardFile.TODOS)] = newSha
            preferences[cacheSuccessKey(DashboardFile.TODOS)] = atMillis
            preferences[cacheAttemptKey(DashboardFile.TODOS)] = atMillis
            preferences.remove(cacheErrorKey(DashboardFile.TODOS))
            preferences.remove(cacheNotFoundKey(DashboardFile.TODOS))
            if (previousSha != newSha) {
                preferences[SettingsKeys.todoRejectedSha] = previousSha
                preferences[SettingsKeys.todoRejectedShaUntil] = atMillis + STALE_TODO_SHA_GUARD_MILLIS
            } else clearTodoStaleShaGuard(preferences)
            preferences.remove(key)
            if (pending.undoRecordId != null) {
                val existingUndo = preferences[UNDO_KEY]?.let { raw -> runCatching { pendingJson.decodeFromString<CompletedTodoUndo>(raw) }.getOrNull() }
                if (existingUndo?.recordId == pending.undoRecordId) preferences.remove(UNDO_KEY)
            } else if (undo != null) {
                preferences[UNDO_KEY] = pendingJson.encodeToString(undo)
            }
            saved = true
        }
        return saved
    }

    suspend fun recordPendingFailure(pending: PendingTodoToggle, safeMessage: String): Boolean {
        var removed = false
        dataStore.edit { preferences ->
            if ((preferences[SettingsKeys.generation] ?: 0L) != pending.expectedGeneration) return@edit
            val key = pendingToggleKey(pending.todoId)
            val current = preferences[key]?.let { raw -> runCatching { pendingJson.decodeFromString<PendingTodoToggle>(raw) }.getOrNull() }
            if (current?.operationId != pending.operationId) return@edit
            preferences[cacheErrorKey(DashboardFile.TODOS)] = safeMessage
            preferences[cacheAttemptKey(DashboardFile.TODOS)] = System.currentTimeMillis()
            preferences.remove(key)
            removed = true
        }
        return removed
    }

    private fun clearCachedFiles(preferences: MutablePreferences) {
        DashboardFile.entries.forEach { file ->
            preferences.remove(cacheRawKey(file))
            preferences.remove(cacheShaKey(file))
            preferences.remove(cacheSuccessKey(file))
            preferences.remove(cacheErrorKey(file))
            preferences.remove(cacheAttemptKey(file))
            preferences.remove(cacheNotFoundKey(file))
        }
        clearTodoStaleShaGuard(preferences)
        preferences.remove(LAST_COMPLETE_SYNC_KEY)
        preferences.remove(LAST_SYNC_ERROR_KEY)
        preferences.remove(SYNC_IN_PROGRESS_KEY)
        preferences.remove(SYNC_STARTED_KEY)
        clearPendingAndUndo(preferences)
    }

    private fun clearPendingAndUndo(preferences: MutablePreferences) {
        preferences.asMap().keys.filter { it.name.startsWith(PENDING_PREFIX) }.forEach { preferences.remove(it) }
        preferences.remove(UNDO_KEY)
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> emptyValue(file: DashboardFile): T = when (file) {
        DashboardFile.TODOS, DashboardFile.CALENDAR, DashboardFile.PROJECTS -> emptyList<Any>() as T
    }

    private fun decodeAppearance(raw: String): WidgetAppearance = runCatching {
        pendingJson.decodeFromString<WidgetAppearance>(raw).sanitized()
    }.getOrDefault(WidgetAppearance.DEFAULT)

    private object SettingsKeys {
        val owner = stringPreferencesKey("settings_owner")
        val repository = stringPreferencesKey("settings_repository")
        val branch = stringPreferencesKey("settings_branch")
        val webUrl = stringPreferencesKey("settings_web_url")
        val tokenIv = stringPreferencesKey("settings_token_iv")
        val tokenCiphertext = stringPreferencesKey("settings_token_ciphertext")
        val generation = longPreferencesKey("settings_generation")
        val todoRejectedSha = stringPreferencesKey("cache_todos_rejected_sha")
        val todoRejectedShaUntil = longPreferencesKey("cache_todos_rejected_sha_until")
    }

    companion object {
        private const val CACHE_PREFIX = "cache_"
        private const val PENDING_PREFIX = "pending_toggle_"
        private const val STALE_TODO_SHA_GUARD_MILLIS = 90_000L
        private val DEFAULT_APPEARANCE_KEY = stringPreferencesKey("widget_appearance_default")
        private val LAST_COMPLETE_SYNC_KEY = longPreferencesKey("sync_last_complete")
        private val LAST_SYNC_ERROR_KEY = booleanPreferencesKey("sync_last_had_errors")
        private val SYNC_IN_PROGRESS_KEY = booleanPreferencesKey("sync_in_progress")
        private val SYNC_STARTED_KEY = longPreferencesKey("sync_started")
        private val UNDO_KEY = stringPreferencesKey("todo_completed_undo")
        private val pendingJson = Json { ignoreUnknownKeys = true }

        private fun cacheRawKey(file: DashboardFile) = stringPreferencesKey("${CACHE_PREFIX}${file.key}_json")
        private fun cacheShaKey(file: DashboardFile) = stringPreferencesKey("${CACHE_PREFIX}${file.key}_sha")
        private fun cacheSuccessKey(file: DashboardFile) = longPreferencesKey("${CACHE_PREFIX}${file.key}_success")
        private fun cacheErrorKey(file: DashboardFile) = stringPreferencesKey("${CACHE_PREFIX}${file.key}_error")
        private fun cacheAttemptKey(file: DashboardFile) = longPreferencesKey("${CACHE_PREFIX}${file.key}_attempt")
        private fun cacheNotFoundKey(file: DashboardFile) = longPreferencesKey("${CACHE_PREFIX}${file.key}_not_found")
        private fun pendingToggleKey(todoId: String) = stringPreferencesKey("$PENDING_PREFIX$todoId")
        private fun widgetAppearanceKey(widgetId: Int) = stringPreferencesKey("widget_appearance_$widgetId")

        private fun clearTodoStaleShaGuard(preferences: MutablePreferences) {
            preferences.remove(SettingsKeys.todoRejectedSha)
            preferences.remove(SettingsKeys.todoRejectedShaUntil)
        }

        fun from(context: Context): DashboardStore = DashboardStore(context.applicationContext.dashboardPreferencesDataStore)
    }
}
