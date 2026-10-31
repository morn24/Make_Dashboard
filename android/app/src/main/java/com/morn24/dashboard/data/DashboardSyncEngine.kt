package com.morn24.dashboard.data

import com.morn24.dashboard.domain.CalendarEvent
import com.morn24.dashboard.domain.Project
import com.morn24.dashboard.domain.Todo
import java.io.IOException
import java.time.Clock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

enum class WidgetConnectionState { NEEDS_SETUP, READY, REAUTHENTICATE }

data class FileSyncStatus(
    val file: DashboardFile,
    val lastSuccessAtMillis: Long?,
    val lastAttemptAtMillis: Long?,
    val error: String?,
    val notFoundAtMillis: Long?,
)

data class DashboardSnapshot(
    val connectionState: WidgetConnectionState,
    val webUrl: String,
    val todos: List<Todo> = emptyList(),
    val pendingTodoIds: Set<String> = emptySet(),
    val projects: List<Project> = emptyList(),
    val calendarEvents: List<CalendarEvent> = emptyList(),
    val calendarAvailable: Boolean = false,
    val lastUpdatedAtMillis: Long? = null,
    val lastCompleteSyncAtMillis: Long? = null,
    val lastSyncHadErrors: Boolean = false,
    val fileStatuses: List<FileSyncStatus> = emptyList(),
    val undo: CompletedTodoUndo? = null,
    val pendingUndoTodoId: String? = null,
    val appearance: WidgetAppearance = WidgetAppearance.DEFAULT,
    val errors: List<String> = emptyList(),
)

/** Reads each GitHub file independently and falls back to the last valid raw JSON cache. */
class DashboardSyncEngine(
    private val store: DashboardStore,
    private val repositoryFactory: DashboardRepositoryFactory,
    private val tokenCipher: TokenCipher,
    private val clock: Clock = Clock.systemDefaultZone(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    /** Fast, network-free render path for Glance actions and offline launches. */
    suspend fun loadCached(): DashboardSnapshot = withContext(ioDispatcher) {
        val settings = store.settings()
        val generation = store.generation()
        val encryptedToken = settings.encryptedToken
            ?: return@withContext DashboardSnapshot(WidgetConnectionState.NEEDS_SETUP, settings.webUrl)
        try {
            tokenCipher.decrypt(encryptedToken)
        } catch (_: Exception) {
            return@withContext DashboardSnapshot(WidgetConnectionState.REAUTHENTICATE, settings.webUrl)
        }
        cachedSnapshot(settings, generation, emptyList())
    }

    suspend fun load(): DashboardSnapshot = withContext(ioDispatcher) {
        val settings = store.settings()
        val generation = store.generation()
        val encryptedToken = settings.encryptedToken
            ?: return@withContext DashboardSnapshot(WidgetConnectionState.NEEDS_SETUP, settings.webUrl)
        val token = try {
            tokenCipher.decrypt(encryptedToken)
        } catch (_: Exception) {
            return@withContext DashboardSnapshot(WidgetConnectionState.REAUTHENTICATE, settings.webUrl)
        }
        val repository = try {
            repositoryFactory.create(settings.owner, settings.repository, settings.branch, token)
        } catch (_: Exception) {
            return@withContext cachedSnapshot(settings, generation, listOf("저장소 연결을 확인하지 못했어요."))
        }

        val errors = mutableListOf<String>()
        val todos = loadFile(
            DashboardFile.TODOS,
            repository,
            generation,
            emptyValue = emptyList(),
            parse = DashboardDisplayData::todos,
            errors = errors,
        )
        val projects = loadFile(
            DashboardFile.PROJECTS,
            repository,
            generation,
            emptyValue = emptyList(),
            parse = DashboardDisplayData::projects,
            errors = errors,
        )
        val calendar = loadFile(
            DashboardFile.CALENDAR,
            repository,
            generation,
            emptyValue = emptyList(),
            parse = DashboardDisplayData::events,
            errors = errors,
        )

        if (store.generation() != generation) {
            return@withContext cachedSnapshot(store.settings(), store.generation(), listOf("설정이 바뀌었어요. 새로고침해 주세요."))
        }
        store.recordSyncResultIfGeneration(clock.millis(), errors.isNotEmpty(), generation)
        val timestamps = DashboardFile.entries.mapNotNull { store.cached(it).lastSuccessAtMillis }
        val (displayTodos, pendingTodoIds) = overlayPendingTodos(todos.value, generation)
        val fileStatuses = DashboardFile.entries.map { file -> store.cached(file).toStatus(file) }
        DashboardSnapshot(
            connectionState = WidgetConnectionState.READY,
            webUrl = settings.webUrl,
            todos = displayTodos,
            pendingTodoIds = pendingTodoIds,
            projects = projects.value,
            calendarEvents = calendar.value,
            calendarAvailable = calendar.available,
            lastUpdatedAtMillis = timestamps.maxOrNull(),
            lastCompleteSyncAtMillis = store.lastCompleteSyncAtMillis(),
            lastSyncHadErrors = store.lastSyncHadErrors(),
            fileStatuses = fileStatuses,
            undo = store.completedUndo()?.takeIf { it.generation == generation },
            errors = errors.distinct(),
        )
    }

    private suspend fun <T> loadFile(
        file: DashboardFile,
        repository: DashboardRepository,
        generation: Long,
        emptyValue: T,
        parse: (JsonObject) -> T,
        errors: MutableList<String>,
    ): FileDisplay<T> {
        try {
            when (val result = repository.read(file)) {
                is RemoteRead.Found -> {
                    val parsed = parse(result.document)
                    if (file == DashboardFile.TODOS && store.shouldIgnoreStaleTodoSha(result.sha, clock.millis())) {
                        val cachedJson = store.cached(file).rawJson
                        if (cachedJson != null) {
                            val cachedValue = runCatching {
                                parse(Json.parseToJsonElement(cachedJson) as JsonObject)
                            }.getOrNull()
                            if (cachedValue != null) return FileDisplay(cachedValue, available = true)
                        }
                    }
                    store.saveSuccessfulFileIfGeneration(file, result.rawJson, result.sha, clock.millis(), generation)
                    return FileDisplay(parsed, available = true)
                }
                RemoteRead.NotFound -> {
                    store.recordFileNotFoundIfGeneration(file, clock.millis(), generation)
                    return FileDisplay(emptyValue, available = false)
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            val safeMessage = safeError(error)
            if (!store.recordFileErrorIfGeneration(file, safeMessage, generation)) {
                return FileDisplay(emptyValue, available = false)
            }
            errors += safeMessage
            val cached = store.cached(file)
            if (cached.rawJson != null) {
                val fallback = runCatching { parse(Json.parseToJsonElement(cached.rawJson) as JsonObject) }.getOrNull()
                if (fallback != null) return FileDisplay(fallback, available = true)
            }
            return FileDisplay(emptyValue, available = false)
        }
    }

    private suspend fun cachedSnapshot(
        settings: DashboardSettings,
        generation: Long,
        initialErrors: List<String>,
    ): DashboardSnapshot {
        val errors = initialErrors.toMutableList()
        suspend fun <T> cached(file: DashboardFile, emptyValue: T, parse: (JsonObject) -> T): Pair<T, Boolean> {
            val cache = store.cached(file)
            cache.error?.let { errors += it }
            if (cache.notFoundAtMillis != null) return emptyValue to false
            if (cache.rawJson == null) return emptyValue to false
            val value = runCatching { parse(Json.parseToJsonElement(cache.rawJson) as JsonObject) }.getOrNull()
            if (value == null) {
                errors += "저장된 ${file.path} 데이터를 읽지 못했어요."
                return emptyValue to false
            }
            return value to true
        }

        if (store.generation() != generation) {
            errors += "설정이 바뀌었어요. 새로고침해 주세요."
        }
        val todoCache = cached(DashboardFile.TODOS, emptyList<Todo>(), DashboardDisplayData::todos)
        val projectCache = cached(DashboardFile.PROJECTS, emptyList<Project>(), DashboardDisplayData::projects)
        val calendarCache = cached(DashboardFile.CALENDAR, emptyList<CalendarEvent>(), DashboardDisplayData::events)
        val timestamps = DashboardFile.entries.mapNotNull { store.cached(it).lastSuccessAtMillis }
        val (displayTodos, pendingTodoIds) = overlayPendingTodos(todoCache.first, generation)
        val fileStatuses = DashboardFile.entries.map { file -> store.cached(file).toStatus(file) }
        return DashboardSnapshot(
            connectionState = WidgetConnectionState.READY,
            webUrl = settings.webUrl,
            todos = displayTodos,
            pendingTodoIds = pendingTodoIds,
            projects = projectCache.first,
            calendarEvents = calendarCache.first,
            calendarAvailable = calendarCache.second,
            lastUpdatedAtMillis = timestamps.maxOrNull(),
            lastCompleteSyncAtMillis = store.lastCompleteSyncAtMillis(),
            lastSyncHadErrors = store.lastSyncHadErrors(),
            fileStatuses = fileStatuses,
            undo = store.completedUndo()?.takeIf { it.generation == generation },
            errors = errors.distinct(),
        )
    }

    private suspend fun overlayPendingTodos(todos: List<Todo>, generation: Long): Pair<List<Todo>, Set<String>> {
        val pending = store.pendingToggles().filter { it.expectedGeneration == generation }.associateBy(PendingTodoToggle::todoId)
        if (pending.isEmpty()) return todos to emptySet()
        return todos.map { todo ->
            pending[todo.id]?.let { todo.copy(doneOn = it.targetDoneOn) } ?: todo
        } to pending.keys
    }

    private fun safeError(error: Exception): String = when (error) {
        is GitHubApiException -> when (error.statusCode) {
            401 -> "GitHub 토큰을 확인하세요 (401)."
            403 -> "GitHub 권한 또는 요청 한도를 확인하세요 (403)."
            else -> "GitHub 요청에 실패했어요 (${error.statusCode})."
        }
        is InvalidRemoteJsonException, is IllegalArgumentException -> "GitHub 데이터 형식이 올바르지 않아요. 이전 데이터를 표시합니다."
        is IOException -> "네트워크 연결 실패. 이전 데이터를 표시합니다."
        else -> "데이터를 갱신하지 못했어요. 이전 데이터를 표시합니다."
    }

    private data class FileDisplay<T>(val value: T, val available: Boolean)
}

private fun CachedJsonFile.toStatus(file: DashboardFile) = FileSyncStatus(
    file = file,
    lastSuccessAtMillis = lastSuccessAtMillis,
    lastAttemptAtMillis = lastAttemptAtMillis,
    error = error,
    notFoundAtMillis = notFoundAtMillis,
)
