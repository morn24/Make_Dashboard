package com.morn24.dashboard.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DashboardStoreTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private lateinit var scope: CoroutineScope
    private lateinit var store: DashboardStore

    @Before
    fun createStore() {
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val backingStore = PreferenceDataStoreFactory.create(scope = scope) {
            File(temporaryFolder.root, "dashboard.preferences_pb")
        }
        store = DashboardStore(backingStore)
    }

    @After
    fun closeScope() {
        scope.cancel()
    }

    @Test
    fun defaultsMatchTheWebDashboardAndNoPlainTokenIsStored() = runBlocking {
        assertEquals("morn24", store.settings().owner)
        assertEquals("dashboard-data", store.settings().repository)
        assertEquals("main", store.settings().branch)
        assertEquals("https://morn24.github.io/Make_Dashboard/", store.settings().webUrl)

        val plainText = "저장소 연결 예시 🔐"
        val cipher = TokenCipher(FixedKeyProvider(SecretKeySpec(ByteArray(32) { (it + 3).toByte() }, "AES")))
        val encryptedToken = cipher.encrypt(plainText)
        val settings = DashboardSettings(encryptedToken = encryptedToken)
        store.saveSettings(settings)

        assertEquals(encryptedToken, store.settings().encryptedToken)
        val persisted = File(temporaryFolder.root, "dashboard.preferences_pb").readBytes()
        assertFalse(persisted.containsBytes(plainText.toByteArray(Charsets.UTF_8)))
        assertEquals(plainText, cipher.decrypt(store.settings().encryptedToken!!))
        assertEquals(1L, store.generation())
    }

    @Test
    fun saveFailureKeepsPreviousCacheAndSuccessClearsError() = runBlocking {
        store.saveSuccessfulFile(DashboardFile.TODOS, "{\"items\":[]}", "sha-1", 100L)
        store.recordFileError(DashboardFile.TODOS, "네트워크 연결 실패")

        val failed = store.cached(DashboardFile.TODOS)
        assertEquals("{\"items\":[]}", failed.rawJson)
        assertEquals("sha-1", failed.sha)
        assertEquals(100L, failed.lastSuccessAtMillis)
        assertEquals("네트워크 연결 실패", failed.error)

        store.saveSuccessfulFile(DashboardFile.TODOS, "{\"items\":[1]}", "sha-2", 200L)
        val successful = store.cached(DashboardFile.TODOS)
        assertEquals("{\"items\":[1]}", successful.rawJson)
        assertEquals("sha-2", successful.sha)
        assertEquals(200L, successful.lastSuccessAtMillis)
        assertNull(successful.error)
    }

    @Test
    fun conditionalCacheWritesRejectResponsesFromAnOlderSettingsGeneration() = runBlocking {
        val currentGeneration = store.saveSettings(DashboardSettings(repository = "new-repository"))
        assertEquals(1L, currentGeneration)

        assertFalse(
            store.saveSuccessfulFileIfGeneration(
                DashboardFile.TODOS,
                "{\"items\":[]}",
                "stale-sha",
                100L,
                expectedGeneration = 0L,
            ),
        )
        assertNull(store.cached(DashboardFile.TODOS).rawJson)
        assertFalse(store.recordFileErrorIfGeneration(DashboardFile.TODOS, "stale error", expectedGeneration = 0L))
        assertNull(store.cached(DashboardFile.TODOS).error)

        assertTrue(
            store.saveSuccessfulFileIfGeneration(
                DashboardFile.TODOS,
                "{\"items\":[]}",
                "current-sha",
                200L,
                expectedGeneration = currentGeneration,
            ),
        )
        assertEquals("current-sha", store.cached(DashboardFile.TODOS).sha)
    }

    @Test
    fun pendingTodoTogglesAreDurableDeduplicatedAndClearedOnConnectionChange() = runBlocking {
        val generation = store.saveSettings(
            DashboardSettings(encryptedToken = EncryptedToken("iv", "cipher")),
        )
        val action = PendingTodoToggle(
            operationId = "operation-1",
            todoId = "todo-1",
            targetDoneOn = "2026-10-31",
            tappedDate = "2026-10-31",
            expectedGeneration = generation,
            createdAtMillis = 12L,
        )

        assertTrue(store.enqueuePendingToggle(action))
        assertFalse(store.enqueuePendingToggle(action.copy(operationId = "operation-2")))
        assertEquals(listOf(action), store.pendingToggles())
        assertFalse(store.removePendingToggle("todo-1", "operation-2"))
        assertTrue(store.removePendingToggle("todo-1", "operation-1"))
        assertNull(store.pendingToggle("todo-1"))

        assertTrue(store.enqueuePendingToggle(action))
        store.saveSettings(store.settings().copy(repository = "changed-repository"))
        assertTrue(store.pendingToggles().isEmpty())
    }

    @Test
    fun repositoryChangeClearsAllRemoteCachesAndIncrementsGeneration() = runBlocking {
        store.saveSuccessfulFile(DashboardFile.PROJECTS, "{\"projects\":[]}", "sha", 100L)
        val first = store.settings().copy(encryptedToken = EncryptedToken("iv", "cipher"))
        assertEquals(1L, store.saveSettings(first))
        assertEquals(1L, store.saveSettings(first))

        val changed = first.copy(repository = "new-repository")
        assertEquals(2L, store.saveSettings(changed))
        val cache = store.cached(DashboardFile.PROJECTS)
        assertNull(cache.rawJson)
        assertNull(cache.sha)
        assertNull(cache.lastSuccessAtMillis)
    }

    private fun ByteArray.containsBytes(needle: ByteArray): Boolean =
        needle.isNotEmpty() && indices.any { start ->
            start + needle.size <= size && needle.indices.all { offset -> this[start + offset] == needle[offset] }
        }

    private class FixedKeyProvider(private val key: SecretKey) : AesKeyProvider {
        override fun getOrCreate(): SecretKey = key
    }
}
