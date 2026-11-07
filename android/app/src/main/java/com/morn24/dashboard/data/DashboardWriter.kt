package com.morn24.dashboard.data

import java.io.IOException
import java.time.Clock
import java.time.LocalDate
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

sealed interface WriteOutcome {
    data object Saved : WriteOutcome

    /** The saved connection changed while the dialog was open; nothing was written to the cache. */
    data object StaleConfiguration : WriteOutcome

    /** Nothing was written (or it failed); the caller keeps the user input and may retry. */
    data class Failed(val message: String) : WriteOutcome
}

/**
 * Writes started from the add/edit dialog and the project next-task dialog. Every write takes the
 * shared mutex, re-reads the newest remote file, applies one pure mutation and PUTs with the sha it
 * just read (4 attempts on 409/422, never overwriting). On success the cache is replaced by the
 * written document so the widget can redraw immediately.
 */
class DashboardWriter(
    private val store: DashboardStore,
    private val repositoryFactory: DashboardRepositoryFactory,
    private val tokenCipher: TokenCipher,
    private val clock: Clock = Clock.systemDefaultZone(),
) {
    suspend fun addTodo(item: JsonObject): WriteOutcome {
        val title = (item["title"] as? kotlinx.serialization.json.JsonPrimitive)?.content.orEmpty()
        return write(DashboardFile.TODOS, "widget: 추가 - $title") { doc -> TodoMutations.addTodo(doc, item) }
    }

    suspend fun editTodo(todoId: String, title: String, patch: Map<String, JsonElement?>, label: String = "수정"): WriteOutcome =
        write(DashboardFile.TODOS, "widget: $label - $title") { doc -> TodoMutations.editTodo(doc, todoId, patch) }

    /** Deletes and leaves a one-shot undo record (shown in the widget undo row). */
    suspend fun deleteTodo(todoId: String): WriteOutcome {
        var undoSource: MutationResult? = null
        return write(
            DashboardFile.TODOS,
            "widget: 삭제 - $todoId",
            onMutated = { undoSource = it },
            undo = { generation ->
                val result = undoSource
                val before = result?.before
                if (result == null || before == null) null else CompletedTodoUndo(
                    recordId = UUID.randomUUID().toString(),
                    todoId = todoId,
                    title = (before["title"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: "할 일",
                    originalDoneOn = null,
                    completedDoneOn = "",
                    completedDate = LocalDate.now(clock).toString(),
                    generation = generation,
                    completedAtMillis = clock.millis(),
                    kind = UndoKind.DELETION,
                    deletedItemJson = Json.encodeToString(JsonObject.serializer(), before),
                    afterId = result.afterId,
                )
            },
        ) { doc -> TodoMutations.deleteTodo(doc, todoId) }
    }

    /** Checks or unchecks one project next-task (only `checked` of that project changes). */
    suspend fun setNextChecked(projectId: String, text: String, done: Boolean): WriteOutcome =
        write(DashboardFile.PROJECTS, "widget: $projectId 사용자 확인 ${if (done) "체크" else "해제"} - $text") { doc ->
            ProjectMutations.setNextChecked(doc, projectId, text, done, IsoTime.now(clock))
        }

    private suspend fun write(
        file: DashboardFile,
        message: String,
        onMutated: (MutationResult) -> Unit = {},
        undo: (Long) -> CompletedTodoUndo? = { null },
        change: (JsonObject) -> DocChange,
    ): WriteOutcome = DashboardSyncCoordinator.mutex.withLock {
        try {
            val settings = store.settings()
            val generation = store.generation()
            val encryptedToken = settings.encryptedToken
                ?: return@withLock WriteOutcome.Failed("연결 정보가 없어 저장하지 못했어요. 앱에서 다시 연결하세요.")
            val token = tokenCipher.decrypt(encryptedToken)
            val repository = repositoryFactory.create(settings.owner, settings.repository, settings.branch, token)
            val mutation = withContext(Dispatchers.IO) { repository.mutate(file, message, change) }
            onMutated(mutation)
            val rawJson = prettyJson.encodeToString(JsonElement.serializer(), mutation.document) + NEWLINE
            val atMillis = clock.millis()
            val saved = when (file) {
                DashboardFile.TODOS -> store.saveTodoMutationIfGeneration(
                    rawJson, mutation.sha, mutation.previousSha, atMillis, generation, undo(generation),
                )
                DashboardFile.PROJECTS -> store.saveProjectsMutationIfGeneration(rawJson, mutation.sha, atMillis, generation)
                else -> false
            }
            if (saved) WriteOutcome.Saved else WriteOutcome.StaleConfiguration
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            WriteOutcome.Failed(safeError(error))
        }
    }

    private fun safeError(error: Exception): String = when (error) {
        is GitHubApiException -> when (error.statusCode) {
            401 -> "GitHub 토큰을 확인하세요 (401). 입력은 그대로 두었어요."
            403 -> "GitHub 권한 또는 요청 한도를 확인하세요 (403). 입력은 그대로 두었어요."
            404 -> "저장소나 파일을 찾지 못했어요 (404). 입력은 그대로 두었어요."
            409, 422 -> "다른 변경과 충돌해 저장하지 못했어요. 입력은 그대로 두었어요. 다시 시도하세요."
            else -> "GitHub 저장에 실패했어요 (${error.statusCode}). 입력은 그대로 두었어요."
        }
        is MissingTodoException -> "할 일이 서버에서 사라졌어요. 새로고침해서 확인하세요."
        is MissingProjectException -> "프로젝트가 서버에서 사라졌어요. 새로고침해서 확인하세요."
        is MissingRemoteFileException -> "서버에서 데이터 파일을 찾지 못했어요. 입력은 그대로 두었어요."
        is InvalidRemoteJsonException -> "GitHub 데이터 형식을 읽지 못해 저장하지 않았어요."
        is IOException -> "네트워크에 연결하지 못했어요. 입력은 그대로 두었어요. 다시 시도하세요."
        else -> "저장하지 못했어요. 입력은 그대로 두었어요. 다시 시도하세요."
    }

    companion object {
        private const val NEWLINE = "\n"
        private val prettyJson = Json { prettyPrint = true; prettyPrintIndent = "  " }
    }
}
