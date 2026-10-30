package com.morn24.dashboard.ui

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.morn24.dashboard.data.AesKeyProvider
import com.morn24.dashboard.data.DashboardFile
import com.morn24.dashboard.data.DashboardRepository
import com.morn24.dashboard.data.DashboardRepositoryFactory
import com.morn24.dashboard.data.DashboardSettings
import com.morn24.dashboard.data.DashboardStore
import com.morn24.dashboard.data.EncryptedToken
import com.morn24.dashboard.data.GitHubApiException
import com.morn24.dashboard.data.MutationResult
import com.morn24.dashboard.data.RemoteRead
import com.morn24.dashboard.data.TokenCipher
import java.io.File
import java.util.UUID
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SettingsViewModelTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private lateinit var datastoreScope: CoroutineScope
    private lateinit var viewModelScope: CoroutineScope
    private lateinit var store: DashboardStore
    private lateinit var cipher: TokenCipher

    @Before
    fun createStore() {
        datastoreScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        viewModelScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val dataStore = PreferenceDataStoreFactory.create(scope = datastoreScope) {
            File(temporaryFolder.root, "dashboard.preferences_pb")
        }
        store = DashboardStore(dataStore)
        cipher = TokenCipher(FixedKeyProvider(key(1)))
    }

    @After
    fun closeScopes() {
        viewModelScope.cancel()
        datastoreScope.cancel()
    }

    @Test
    fun successfulConnectionSavesEncryptedTokenAndClearsInput() = runBlocking {
        val factory = FakeRepositoryFactory { RemoteRead.Found(JsonObject(emptyMap()), "sha", "{}") }
        val viewModel = viewModel(factory)
        val credential = UUID.randomUUID().toString()
        viewModel.load().join()
        viewModel.updateToken(credential)

        viewModel.checkConnection().join()

        val saved = store.settings()
        assertEquals(DashboardSettings.DEFAULT_OWNER, saved.owner)
        assertEquals(DashboardSettings.DEFAULT_REPOSITORY, saved.repository)
        assertEquals(DashboardSettings.DEFAULT_BRANCH, saved.branch)
        assertEquals(credential, cipher.decrypt(saved.encryptedToken!!))
        assertTrue(viewModel.state.value.hasSavedToken)
        assertTrue(viewModel.state.value.isConnected)
        assertEquals("", viewModel.state.value.tokenInput)
        assertTrue(viewModel.state.value.statusMessage.orEmpty().contains("연결됐어요"))
        assertEquals(1, factory.createCount)
    }

    @Test
    fun connectionErrorsDistinguishUnauthorizedForbiddenAndNotFoundInKorean() = runBlocking {
        listOf(
            401 to "401",
            403 to "403",
        ).forEach { (statusCode, expected) ->
            val factory = FakeRepositoryFactory { throw GitHubApiException(statusCode) }
            val viewModel = viewModel(factory)
            viewModel.load().join()
            viewModel.updateToken(UUID.randomUUID().toString())

            viewModel.checkConnection().join()

            assertTrue(viewModel.state.value.statusMessage.orEmpty().contains(expected))
            assertTrue(viewModel.state.value.isStatusError)
            assertFalse(viewModel.state.value.hasSavedToken)
        }

        val missingFactory = FakeRepositoryFactory { RemoteRead.NotFound }
        val missingViewModel = viewModel(missingFactory)
        missingViewModel.load().join()
        missingViewModel.updateToken(UUID.randomUUID().toString())
        missingViewModel.checkConnection().join()
        assertTrue(missingViewModel.state.value.statusMessage.orEmpty().contains("404"))
        assertTrue(missingViewModel.state.value.statusMessage.orEmpty().contains("data/todos.json"))
    }

    @Test
    fun invalidWebUrlStopsBeforeMakingNetworkRequest() = runBlocking {
        val factory = FakeRepositoryFactory { RemoteRead.Found(JsonObject(emptyMap()), "sha", "{}") }
        val viewModel = viewModel(factory)
        viewModel.load().join()
        viewModel.updateToken(UUID.randomUUID().toString())
        viewModel.updateWebUrl("http://example.com/")

        viewModel.checkConnection().join()

        assertEquals(0, factory.createCount)
        assertTrue(viewModel.state.value.statusMessage.orEmpty().contains("HTTPS"))
    }

    @Test
    fun responseForAnOlderFormRevisionIsDiscarded() = runBlocking {
        lateinit var viewModel: SettingsViewModel
        val factory = FakeRepositoryFactory {
            RemoteRead.Found(JsonObject(emptyMap()), "sha", "{}").also { viewModel.updateBranch("different-branch") }
        }
        viewModel = viewModel(factory)
        viewModel.load().join()
        viewModel.updateToken(UUID.randomUUID().toString())

        viewModel.checkConnection().join()

        assertFalse(viewModel.state.value.isConnected)
        assertTrue(viewModel.state.value.statusMessage.orEmpty().contains("바뀌었어요"))
        assertEquals(null, store.settings().encryptedToken)
    }

    @Test
    fun lostKeystoreKeyAsksForReconnectionWithoutCallingRepository() = runBlocking {
        val oldCipher = TokenCipher(FixedKeyProvider(key(8)))
        store.saveSettings(DashboardSettings(encryptedToken = oldCipher.encrypt("저장된 연결 예시")))
        val factory = FakeRepositoryFactory { RemoteRead.Found(JsonObject(emptyMap()), "sha", "{}") }
        val viewModel = viewModel(factory, TokenCipher(FixedKeyProvider(key(3))))
        viewModel.load().join()

        viewModel.checkConnection().join()

        assertEquals(0, factory.createCount)
        assertTrue(viewModel.state.value.statusMessage.orEmpty().contains("새 토큰을 입력하세요"))
        assertFalse(viewModel.state.value.hasSavedToken)
    }

    private fun viewModel(
        factory: FakeRepositoryFactory,
        tokenCipher: TokenCipher = cipher,
    ) = SettingsViewModel(store, factory, tokenCipher, viewModelScope, Dispatchers.Unconfined)

    private fun key(seed: Int): SecretKey = SecretKeySpec(ByteArray(32) { (it + seed).toByte() }, "AES")

    private class FixedKeyProvider(private val secretKey: SecretKey) : AesKeyProvider {
        override fun getOrCreate(): SecretKey = secretKey
    }

    private class FakeRepositoryFactory(
        private val readResult: () -> RemoteRead,
    ) : DashboardRepositoryFactory {
        var createCount = 0
            private set

        override fun create(owner: String, repository: String, branch: String, token: String): DashboardRepository {
            createCount += 1
            return object : DashboardRepository {
                override fun read(file: DashboardFile): RemoteRead = readResult()
                override fun updateTodo(todoId: String, targetDoneOn: String?): MutationResult = error("unused")
            }
        }
    }
}
