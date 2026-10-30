package com.morn24.dashboard.ui

import com.morn24.dashboard.data.DashboardFile
import com.morn24.dashboard.data.DashboardRepositoryFactory
import com.morn24.dashboard.data.DashboardSettings
import com.morn24.dashboard.data.DashboardStore
import com.morn24.dashboard.data.GitHubApiException
import com.morn24.dashboard.data.InvalidRemoteJsonException
import com.morn24.dashboard.data.RemoteRead
import com.morn24.dashboard.data.TokenCipher
import java.io.IOException
import java.net.URI
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class SettingsUiState(
    val owner: String = DashboardSettings.DEFAULT_OWNER,
    val repository: String = DashboardSettings.DEFAULT_REPOSITORY,
    val branch: String = DashboardSettings.DEFAULT_BRANCH,
    val tokenInput: String = "",
    val webUrl: String = DashboardSettings.DEFAULT_WEB_URL,
    val hasSavedToken: Boolean = false,
    val isConnecting: Boolean = false,
    val isConnected: Boolean = false,
    val statusMessage: String? = null,
    val isStatusError: Boolean = false,
    val formRevision: Long = 0L,
)

class SettingsViewModel(
    private val store: DashboardStore,
    private val repositoryFactory: DashboardRepositoryFactory,
    private val tokenCipher: TokenCipher,
    private val scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val mutableState = MutableStateFlow(SettingsUiState())
    val state: StateFlow<SettingsUiState> = mutableState.asStateFlow()
    private var savedEncryptedToken: com.morn24.dashboard.data.EncryptedToken? = null
    private var hasLoaded = false

    fun load(): Job = scope.launch {
        if (hasLoaded) return@launch
        hasLoaded = true
        runCatching { store.settings() }
            .onSuccess { settings ->
                savedEncryptedToken = settings.encryptedToken
                mutableState.update {
                    it.copy(
                        owner = settings.owner,
                        repository = settings.repository,
                        branch = settings.branch,
                        webUrl = settings.webUrl,
                        hasSavedToken = settings.encryptedToken != null,
                    )
                }
            }
            .onFailure { showError("설정을 읽지 못했어요. 앱을 다시 열어 확인하세요.") }
    }

    fun updateOwner(value: String) = updateForm { copy(owner = value) }
    fun updateRepository(value: String) = updateForm { copy(repository = value) }
    fun updateBranch(value: String) = updateForm { copy(branch = value) }
    fun updateWebUrl(value: String) = updateForm { copy(webUrl = value) }
    fun updateToken(value: String) = updateForm { copy(tokenInput = value) }

    fun checkConnection(): Job = scope.launch {
        val submitted = mutableState.value
        if (submitted.isConnecting) return@launch
        validationError(submitted)?.let { message ->
            showError(message)
            return@launch
        }

        val token = if (submitted.tokenInput.isNotBlank()) {
            submitted.tokenInput.trim()
        } else {
            val encrypted = savedEncryptedToken
            if (encrypted == null) {
                showError("저장소 토큰을 입력하세요.")
                return@launch
            }
            try {
                tokenCipher.decrypt(encrypted)
            } catch (_: Exception) {
                mutableState.update {
                    it.copy(
                        hasSavedToken = false,
                        isConnected = false,
                        statusMessage = "저장된 연결 정보를 복구할 수 없어요. 새 토큰을 입력하세요.",
                        isStatusError = true,
                    )
                }
                return@launch
            }
        }

        mutableState.update { it.copy(isConnecting = true, statusMessage = "GitHub 연결을 확인하고 있어요…", isStatusError = false) }
        try {
            val readResult = withContext(ioDispatcher) {
                repositoryFactory.create(
                    submitted.owner.trim(),
                    submitted.repository.trim(),
                    submitted.branch.trim(),
                    token,
                ).read(DashboardFile.TODOS)
            }

            if (mutableState.value.formRevision != submitted.formRevision) {
                showError("입력값이 확인 중 바뀌었어요. 새 값으로 다시 연결 확인을 눌러주세요.")
                return@launch
            }
            if (readResult !is RemoteRead.Found) {
                showError("저장소, 브랜치 또는 data/todos.json을 찾을 수 없어요 (404). 이름과 경로를 확인하세요.")
                return@launch
            }

            val encryptedToken = tokenCipher.encrypt(token)
            store.saveSettings(
                DashboardSettings(
                    owner = submitted.owner.trim(),
                    repository = submitted.repository.trim(),
                    branch = submitted.branch.trim(),
                    webUrl = submitted.webUrl.trim(),
                    encryptedToken = encryptedToken,
                ),
            )
            if (mutableState.value.formRevision != submitted.formRevision) {
                showError("입력값이 확인 중 바뀌었어요. 새 값으로 다시 연결 확인을 눌러주세요.")
                return@launch
            }
            savedEncryptedToken = encryptedToken
            mutableState.update {
                it.copy(
                    tokenInput = "",
                    hasSavedToken = true,
                    isConnecting = false,
                    isConnected = true,
                    statusMessage = "GitHub 저장소에 연결됐어요.",
                    isStatusError = false,
                )
            }
        } catch (error: GitHubApiException) {
            showError(httpErrorMessage(error.statusCode))
        } catch (_: InvalidRemoteJsonException) {
            showError("data/todos.json 응답을 읽을 수 없어요. JSON 형식을 확인하세요.")
        } catch (_: IOException) {
            showError("네트워크에 연결하지 못했어요. 연결 상태를 확인하세요.")
        } catch (_: Exception) {
            showError("연결 또는 설정 저장에 실패했어요. 입력값과 저장 공간을 확인하세요.")
        } finally {
            mutableState.update { if (it.isConnecting) it.copy(isConnecting = false) else it }
        }
    }

    private fun updateForm(transform: SettingsUiState.() -> SettingsUiState) {
        mutableState.update { current ->
            transform(current).copy(
                formRevision = current.formRevision + 1,
                isConnected = false,
                statusMessage = null,
                isStatusError = false,
            )
        }
    }

    private fun validationError(form: SettingsUiState): String? {
        if (form.owner.isBlank() || form.repository.isBlank() || form.branch.isBlank()) {
            return "저장소 소유자, 저장소 이름, 브랜치를 입력하세요."
        }
        val uri = runCatching { URI(form.webUrl.trim()) }.getOrNull()
        if (uri == null || !uri.scheme.equals("https", ignoreCase = true) || uri.host.isNullOrBlank() ||
            uri.userInfo != null || uri.fragment != null
        ) {
            return "웹앱 주소는 사용자 정보나 조각이 없는 HTTPS 주소여야 합니다."
        }
        return null
    }

    private fun showError(message: String) {
        mutableState.update { it.copy(isConnecting = false, isConnected = false, statusMessage = message, isStatusError = true) }
    }

    private fun httpErrorMessage(status: Int): String = when (status) {
        401 -> "토큰이 올바르지 않아요 (401). 토큰을 다시 확인하세요."
        403 -> "저장소 읽기 권한이 없거나 API 요청 한도를 넘었어요 (403). Contents 읽기 권한을 확인하세요."
        404 -> "저장소, 브랜치 또는 data/todos.json을 찾을 수 없어요 (404). 이름과 경로를 확인하세요."
        else -> "GitHub 연결에 실패했어요 ($status). 잠시 뒤 다시 시도하세요."
    }
}
