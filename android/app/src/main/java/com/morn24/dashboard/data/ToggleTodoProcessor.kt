package com.morn24.dashboard.data

import java.io.IOException
import java.time.Clock
import java.time.LocalDate
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

sealed interface ToggleTodoResult {
    data object Saved : ToggleTodoResult
    data object NoPendingAction : ToggleTodoResult
    data object StaleConfiguration : ToggleTodoResult
    data class Failed(val message: String) : ToggleTodoResult
}

/** Applies one persisted target value, then updates only its cache and pending overlay. */
class ToggleTodoProcessor(
    private val store: DashboardStore,
    private val repositoryFactory: DashboardRepositoryFactory,
    private val tokenCipher: TokenCipher,
    private val clock: Clock = Clock.systemDefaultZone(),
) {
    suspend fun process(todoId: String, operationId: String): ToggleTodoResult =
        DashboardSyncCoordinator.mutex.withLock {
            val pending = store.pendingToggle(todoId)
            if (pending == null || pending.operationId != operationId) return@withLock ToggleTodoResult.NoPendingAction
            if (store.generation() != pending.expectedGeneration) {
                store.removePendingToggle(todoId, operationId)
                return@withLock ToggleTodoResult.StaleConfiguration
            }
            if (pending.tappedDate != LocalDate.now(clock).toString()) {
                store.removePendingToggle(todoId, operationId)
                return@withLock ToggleTodoResult.StaleConfiguration
            }

            try {
                val settings = store.settings()
                if (store.generation() != pending.expectedGeneration) {
                    store.removePendingToggle(todoId, operationId)
                    return@withLock ToggleTodoResult.StaleConfiguration
                }

                if (pending.undoRecordId != null) {
                    val currentUndo = store.completedUndo()
                    if (currentUndo?.recordId != pending.undoRecordId || currentUndo.generation != pending.expectedGeneration) {
                        store.removePendingToggle(todoId, operationId)
                        return@withLock ToggleTodoResult.StaleConfiguration
                    }
                }
                val encryptedToken = settings.encryptedToken ?: throw MissingSavedTokenException()
                val token = tokenCipher.decrypt(encryptedToken)
                val repository = repositoryFactory.create(settings.owner, settings.repository, settings.branch, token)
                if (store.generation() != pending.expectedGeneration) {
                    store.removePendingToggle(todoId, operationId)
                    return@withLock ToggleTodoResult.StaleConfiguration
                }

                val mutation = if (pending.requiresExpectedMatch) {
                    repository.updateTodoIfCurrent(todoId, pending.expectedDoneOn, pending.targetDoneOn)
                } else {
                    repository.updateTodo(todoId, pending.targetDoneOn)
                }
                val rawJson = prettyJson.encodeToString(mutation.document) + "\n"
                val atMillis = clock.millis()
                val undo = if (pending.undoRecordId == null && pending.targetDoneOn != null && mutation.changed) {
                    CompletedTodoUndo(
                        recordId = UUID.randomUUID().toString(),
                        todoId = pending.todoId,
                        title = pending.title,
                        originalDoneOn = mutation.originalDoneOn,
                        completedDoneOn = pending.targetDoneOn,
                        completedDate = pending.tappedDate,
                        generation = pending.expectedGeneration,
                        completedAtMillis = atMillis,
                    )
                } else null
                val saved = store.completePendingToggle(
                    pending = pending,
                    rawJson = rawJson,
                    newSha = mutation.sha,
                    previousSha = mutation.previousSha,
                    atMillis = atMillis,
                    undo = undo,
                )
                if (saved) ToggleTodoResult.Saved else ToggleTodoResult.StaleConfiguration
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (store.generation() != pending.expectedGeneration) {
                    store.removePendingToggle(todoId, operationId)
                    return@withLock ToggleTodoResult.StaleConfiguration
                }
                val message = safeError(error, pending.undoRecordId != null)
                store.recordPendingFailure(pending, message)
                ToggleTodoResult.Failed(message)
            }
        }

    private fun safeError(error: Exception, isUndo: Boolean): String = when (error) {
        is UndoConflictException -> "다른 기기에서 할 일이 바뀌어 취소하지 않았어요. 새로고침해서 확인하세요."
        is MissingSavedTokenException -> "연결 정보가 없어 체크를 저장하지 못했어요. 앱에서 다시 연결하세요."
        is GitHubApiException -> when (error.statusCode) {
            401 -> if (isUndo) "GitHub 토큰을 확인하세요 (401). 완료 상태는 유지했어요." else "GitHub 토큰을 확인하세요 (401). 체크를 원래 상태로 되돌렸어요."
            403 -> if (isUndo) "GitHub 권한 또는 요청 한도를 확인하세요 (403). 완료 상태는 유지했어요." else "GitHub 권한 또는 요청 한도를 확인하세요 (403). 체크를 원래 상태로 되돌렸어요."
            404 -> if (isUndo) "할 일을 찾지 못해 취소하지 않았어요 (404). 새로고침해서 확인하세요." else "저장소나 할 일을 찾지 못했어요 (404). 체크를 원래 상태로 되돌렸어요."
            409, 422 -> if (isUndo) "다른 변경과 충돌해 취소하지 않았어요. 새로고침해서 확인하세요." else "다른 변경과 충돌해 저장하지 못했어요. 체크를 원래 상태로 되돌렸어요."
            else -> if (isUndo) "GitHub 저장에 실패했어요 (${error.statusCode}). 완료 상태는 유지했어요." else "GitHub 저장에 실패했어요 (${error.statusCode}). 체크를 원래 상태로 되돌렸어요."
        }
        is MissingTodoException -> if (isUndo) "할 일이 서버에서 사라져 취소하지 않았어요. 새로고침해서 확인하세요." else "할 일이 서버에서 사라졌어요. 웹앱에서 확인하세요."
        is InvalidRemoteJsonException -> if (isUndo) "GitHub 데이터 형식을 읽지 못해 취소하지 않았어요." else "GitHub 데이터 형식을 읽지 못했어요. 체크를 원래 상태로 되돌렸어요."
        is IOException -> if (isUndo) "네트워크에 연결하지 못했어요. 완료 상태는 유지했어요." else "네트워크에 연결하지 못했어요. 체크를 원래 상태로 되돌렸어요."
        else -> if (isUndo) "취소를 저장하지 못했어요. 완료 상태는 유지했어요." else "체크를 저장하지 못했어요. 체크를 원래 상태로 되돌렸어요."
    }

    private class MissingSavedTokenException : IllegalStateException()

    companion object {
        private val prettyJson = Json { prettyPrint = true; prettyPrintIndent = "  " }
    }
}
