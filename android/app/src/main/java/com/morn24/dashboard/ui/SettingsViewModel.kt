package com.morn24.dashboard.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.morn24.dashboard.data.AppearanceColorMode
import com.morn24.dashboard.data.DashboardFile
import com.morn24.dashboard.data.DashboardRepositoryFactory
import com.morn24.dashboard.data.DashboardSettings
import com.morn24.dashboard.data.DashboardSnapshot
import com.morn24.dashboard.data.DashboardStore
import com.morn24.dashboard.data.DashboardSyncEngine
import com.morn24.dashboard.data.GitHubApiException
import com.morn24.dashboard.data.InvalidRemoteJsonException
import com.morn24.dashboard.data.RemoteRead
import com.morn24.dashboard.data.TokenCipher
import com.morn24.dashboard.data.WidgetAppearance
import java.io.IOException
import java.net.URI
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class SettingsPage { HOME, CONNECTION, APPEARANCE, STATUS, HELP }

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
    val page: SettingsPage = SettingsPage.HOME,
    val widgetIds: List<Int> = emptyList(),
    val selectedWidgetId: Int? = null,
    val appearance: WidgetAppearance = WidgetAppearance.DEFAULT,
    val draftAppearance: WidgetAppearance = WidgetAppearance.DEFAULT,
    val appearanceDirty: Boolean = false,
    val snapshot: DashboardSnapshot? = null,
    val widgetMessage: String? = null,
    val appearanceRevision: Long = 0L,
    val connectionRevision: Long = 0L,
)

class SettingsViewModel(
    private val store: DashboardStore,
    private val repositoryFactory: DashboardRepositoryFactory,
    private val tokenCipher: TokenCipher,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {
    private val mutableState = MutableStateFlow(SettingsUiState())
    val state: StateFlow<SettingsUiState> = mutableState.asStateFlow()
    private var savedEncryptedToken: com.morn24.dashboard.data.EncryptedToken? = null
    private var hasLoaded = false
    private var statusObservation: Job? = null

    fun load(widgetIds: List<Int>, requestedWidgetId: Int?): Job = viewModelScope.launch(ioDispatcher) {
        if (hasLoaded) {
            mutableState.update { it.copy(widgetIds = widgetIds, selectedWidgetId = requestedWidgetId?.takeIf(widgetIds::contains)) }
            return@launch
        }
        hasLoaded = true
        try {
            val settings = store.settings()
            savedEncryptedToken = settings.encryptedToken
            val selectedWidget = requestedWidgetId?.takeIf(widgetIds::contains)
            val cachedSnapshot = DashboardSyncEngine(store, repositoryFactory, tokenCipher, ioDispatcher = ioDispatcher).loadCached()
            mutableState.update {
                it.copy(
                    owner = settings.owner,
                    repository = settings.repository,
                    branch = settings.branch,
                    webUrl = settings.webUrl,
                    hasSavedToken = settings.encryptedToken != null,
                    isConnected = settings.encryptedToken != null,
                    widgetIds = widgetIds,
                    selectedWidgetId = selectedWidget,
                    page = if (settings.encryptedToken == null) SettingsPage.CONNECTION else SettingsPage.HOME,
                    snapshot = cachedSnapshot,
                    widgetMessage = when {
                        requestedWidgetId != null && selectedWidget == null -> "선택한 위젯이 이미 삭제됐어요. 목록에서 다른 위젯을 선택하세요."
                        widgetIds.isEmpty() -> "홈 화면에 위젯이 아직 없어요. 아래에서 추가할 수 있습니다."
                        else -> null
                    },
                )
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            mutableState.update { it.copy(statusMessage = "설정을 읽지 못했어요. 다시 열어 확인하세요.", isStatusError = true) }
        }
    }

    fun updateOwner(value: String) = updateForm { copy(owner = value) }
    fun updateRepository(value: String) = updateForm { copy(repository = value) }
    fun updateBranch(value: String) = updateForm { copy(branch = value) }
    fun updateWebUrl(value: String) = updateForm { copy(webUrl = value) }
    fun updateToken(value: String) = updateForm { copy(tokenInput = value) }

    fun openHome() { stopStatusObservation(); mutableState.update { it.copy(page = SettingsPage.HOME) } }
    fun openConnection() {
        stopStatusObservation()
        mutableState.update { it.copy(page = SettingsPage.CONNECTION, statusMessage = null, isStatusError = false) }
    }
    fun openStatus() {
        mutableState.update { it.copy(page = SettingsPage.STATUS) }
        stopStatusObservation()
        statusObservation = viewModelScope.launch(ioDispatcher) {
            try {
                val initial = DashboardSyncEngine(store, repositoryFactory, tokenCipher, ioDispatcher = ioDispatcher).loadCached()
                store.observeWidgetSnapshot(initial, mutableState.value.selectedWidgetId ?: -1).collect(::updateCachedSnapshot)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutableState.update { it.copy(statusMessage = "동기화 상태를 새로 읽지 못했어요.", isStatusError = true) }
            }
        }
    }
    fun openHelp() { stopStatusObservation(); mutableState.update { it.copy(page = SettingsPage.HELP) } }

    fun openAppearance(widgetId: Int?) = viewModelScope.launch(ioDispatcher) {
        stopStatusObservation()
        if (widgetId != null && widgetId !in mutableState.value.widgetIds) {
            mutableState.update { it.copy(widgetMessage = "선택한 위젯이 사라졌어요. 목록을 새로고침했습니다.", selectedWidgetId = null) }
            return@launch
        }
        val appearance = store.appearance(widgetId)
        mutableState.update {
            it.copy(
                page = SettingsPage.APPEARANCE,
                selectedWidgetId = widgetId,
                appearance = appearance,
                draftAppearance = appearance,
                appearanceDirty = false,
                statusMessage = null,
                isStatusError = false,
            )
        }
    }

    fun selectAppearanceWidget(widgetId: Int?) = openAppearance(widgetId)

    fun updateDraft(transform: WidgetAppearance.() -> WidgetAppearance) {
        mutableState.update { current ->
            val next = transform(current.draftAppearance).sanitized()
            current.copy(draftAppearance = next, appearanceDirty = next != current.appearance, statusMessage = null, isStatusError = false)
        }
    }

    fun updateDraftBackgroundHex(value: String) {
        mutableState.update {
            val next = it.draftAppearance.copy(backgroundHex = value)
            it.copy(draftAppearance = next, appearanceDirty = next != it.appearance, statusMessage = null, isStatusError = false)
        }
    }

    fun updateDraftAccentHex(value: String) {
        mutableState.update {
            val next = it.draftAppearance.copy(accentHex = value)
            it.copy(draftAppearance = next, appearanceDirty = next != it.appearance, statusMessage = null, isStatusError = false)
        }
    }

    fun restoreAppearanceDefaults() = updateDraft { WidgetAppearance.DEFAULT }

    fun cancelAppearance() {
        mutableState.update { it.copy(page = SettingsPage.HOME, draftAppearance = it.appearance, appearanceDirty = false, statusMessage = null) }
    }

    fun applyAppearance(toAllWidgets: Boolean): Job = viewModelScope.launch(ioDispatcher) {
        val current = mutableState.value
        val draft = current.draftAppearance
        if (!HEX_COLOR.matches(draft.backgroundHex) || !HEX_COLOR.matches(draft.accentHex)) {
            mutableState.update { it.copy(statusMessage = "사용자 색상은 #RRGGBB 형식으로 입력하세요.", isStatusError = true) }
            return@launch
        }
        try {
            if (toAllWidgets) {
                store.saveAppearance(draft, widgetId = null)
                current.widgetIds.forEach { id -> store.saveAppearance(draft, widgetId = id) }
            } else {
                store.saveAppearance(draft, widgetId = current.selectedWidgetId)
            }
            mutableState.update {
                it.copy(
                    page = SettingsPage.HOME,
                    appearance = draft.sanitized(),
                    draftAppearance = draft.sanitized(),
                    appearanceDirty = false,
                    appearanceRevision = it.appearanceRevision + 1,
                    statusMessage = if (toAllWidgets) "모든 위젯에 적용했어요." else "위젯 모양을 적용했어요.",
                    isStatusError = false,
                )
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            mutableState.update { it.copy(statusMessage = "꾸미기 설정을 저장하지 못했어요.", isStatusError = true) }
        }
    }

    fun checkConnection(): Job = viewModelScope.launch(ioDispatcher) {
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

        mutableState.update { it.copy(isConnecting = true, statusMessage = "GitHub의 할 일 파일을 읽어 연결을 확인하고 있어요…", isStatusError = false) }
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
                    page = SettingsPage.HOME,
                    connectionRevision = it.connectionRevision + 1,
                    statusMessage = "할 일 파일 읽기를 확인했어요. 쓰기 권한은 첫 체크 저장 때 확인됩니다.",
                    isStatusError = false,
                )
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
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

    fun updateCachedSnapshot(snapshot: DashboardSnapshot) {
        mutableState.update { it.copy(snapshot = snapshot) }
    }

    private fun stopStatusObservation() {
        statusObservation?.cancel()
        statusObservation = null
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

    companion object {
        private val HEX_COLOR = Regex("^#[0-9A-Fa-f]{6}$")
    }
}
