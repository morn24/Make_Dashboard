package com.morn24.dashboard.data

import java.io.IOException
import java.time.Clock
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

            try {
                val settings = store.settings()
                if (store.generation() != pending.expectedGeneration) {
                    store.removePendingToggle(todoId, operationId)
                    return@withLock ToggleTodoResult.StaleConfiguration
                }
                val encryptedToken = settings.encryptedToken ?: throw MissingSavedTokenException()
                val token = tokenCipher.decrypt(encryptedToken)
                val repository = repositoryFactory.create(settings.owner, settings.repository, settings.branch, token)
                if (store.generation() != pending.expectedGeneration) {
                    store.removePendingToggle(todoId, operationId)
                    return@withLock ToggleTodoResult.StaleConfiguration
                }

                val mutation = repository.updateTodo(todoId, pending.targetDoneOn)
                val rawJson = prettyJson.encodeToString(mutation.document) + "\n"
                val saved = store.saveSuccessfulFileIfGeneration(
                    DashboardFile.TODOS,
                    rawJson,
                    mutation.sha,
                    clock.millis(),
                    pending.expectedGeneration,
                )
                store.removePendingToggle(todoId, operationId)
                if (saved) ToggleTodoResult.Saved else ToggleTodoResult.StaleConfiguration
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (store.generation() != pending.expectedGeneration) {
                    store.removePendingToggle(todoId, operationId)
                    return@withLock ToggleTodoResult.StaleConfiguration
                }
                val message = safeError(error)
                store.recordFileErrorIfGeneration(DashboardFile.TODOS, message, pending.expectedGeneration)
                store.removePendingToggle(todoId, operationId)
                ToggleTodoResult.Failed(message)
            }
        }

    private fun safeError(error: Exception): String = when (error) {
        is MissingSavedTokenException -> "연결 정보가 없어 체크를 저장하지 못했어요. 앱에서 다시 연결하세요."
        is GitHubApiException -> when (error.statusCode) {
            401 -> "GitHub 토큰을 확인하세요 (401). 체크를 원래 상태로 되돌렸어요."
            403 -> "GitHub 권한 또는 요청 한도를 확인하세요 (403). 체크를 원래 상태로 되돌렸어요."
            404 -> "저장소나 할 일을 찾지 못했어요 (404). 체크를 원래 상태로 되돌렸어요."
            409, 422 -> "다른 변경과 충돌해 저장하지 못했어요. 체크를 원래 상태로 되돌렸어요."
            else -> "GitHub 저장에 실패했어요 (${error.statusCode}). 체크를 원래 상태로 되돌렸어요."
        }
        is MissingTodoException -> "할 일이 서버에서 사라졌어요. 웹앱에서 확인하세요."
        is InvalidRemoteJsonException -> "GitHub 데이터 형식을 읽지 못했어요. 체크를 원래 상태로 되돌렸어요."
        is IOException -> "네트워크에 연결하지 못했어요. 체크를 원래 상태로 되돌렸어요."
        else -> "체크를 저장하지 못했어요. 체크를 원래 상태로 되돌렸어요."
    }

    private class MissingSavedTokenException : IllegalStateException()

    companion object {
        private val prettyJson = Json { prettyPrint = true; prettyPrintIndent = "  " }
    }
}
