package com.morn24.dashboard.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
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
    fun todoMutationRejectsThePreviousShaForNinetySeconds() = runBlocking {
        val generation = store.saveSettings(DashboardSettings(encryptedToken = EncryptedToken("iv", "cipher")))
        assertTrue(
            store.saveTodoMutationIfGeneration(
                rawJson = "{\"items\":[]}",
                newSha = "new-sha",
                previousSha = "old-sha",
                atMillis = 1_000L,
                expectedGeneration = generation,
            ),
        )

        assertTrue(store.shouldIgnoreStaleTodoSha("old-sha", nowMillis = 90_999L))
        assertFalse(store.shouldIgnoreStaleTodoSha("old-sha", nowMillis = 91_000L))
        assertFalse(store.shouldIgnoreStaleTodoSha("new-sha", nowMillis = 1_001L))
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

    @Test
    fun widgetAppearanceIsScopedPerWidgetAndDoesNotClearConnectionOrCache() = runBlocking {
        val generation = store.saveSettings(DashboardSettings(encryptedToken = EncryptedToken("iv", "cipher")))
        store.saveSuccessfulFile(DashboardFile.TODOS, "{\"version\":1,\"items\":[]}", "sha", 10L)
        val custom = WidgetAppearance(
            colorMode = AppearanceColorMode.CUSTOM,
            backgroundHex = "#112233",
            accentHex = "#AABBCC",
            fontScalePercent = 130,
            backgroundOpacityPercent = 35,
        )

        store.saveAppearance(custom, widgetId = 101)
        store.saveAppearance(WidgetAppearance(fontScalePercent = 90), widgetId = 102)

        assertEquals(custom, store.appearance(101))
        assertEquals(90, store.appearance(102).fontScalePercent)
        assertEquals(WidgetAppearance.DEFAULT, store.appearance(103))
        assertEquals(generation, store.generation())
        assertEquals("sha", store.cached(DashboardFile.TODOS).sha)
        assertEquals("cipher", store.settings().encryptedToken!!.ciphertextBase64)
    }

    @Test
    fun widgetSnapshotFlowEmitsPendingTargetAndAppearanceFromOneDataStoreState() = runBlocking {
        val generation = store.saveSettings(DashboardSettings(encryptedToken = EncryptedToken("iv", "cipher")))
        store.saveSuccessfulFile(
            DashboardFile.TODOS,
            """{"version":1,"items":[{"id":"todo-1","title":"즉시 표시","repeat":"daily","doneOn":null}]}""",
            "sha",
            10L,
        )
        store.saveAppearance(WidgetAppearance(fontScalePercent = 120), widgetId = 7)
        store.enqueuePendingToggle(
            PendingTodoToggle("operation", "todo-1", "2026-11-01", "2026-11-01", generation, 20L, title = "즉시 표시"),
        )

        val snapshot = store.observeWidgetSnapshot(
            initial = DashboardSnapshot(WidgetConnectionState.READY, DashboardSettings.DEFAULT_WEB_URL),
            widgetId = 7,
        ).first()

        assertEquals(setOf("todo-1"), snapshot.pendingTodoIds)
        assertEquals("2026-11-01", snapshot.todos.single().doneOn)
        assertEquals(120, snapshot.appearance.fontScalePercent)
    }

    @Test
    fun missingFileAndCompleteSyncStatusAreStoredSeparatelyFromOtherFileSuccess() = runBlocking {
        val generation = store.saveSettings(DashboardSettings(encryptedToken = EncryptedToken("iv", "cipher")))
        store.saveSuccessfulFile(DashboardFile.TODOS, "{}", "sha", 100L)
        store.recordFileNotFoundIfGeneration(DashboardFile.CALENDAR, 110L, generation)
        store.recordSyncResultIfGeneration(120L, hadErrors = false, expectedGeneration = generation)

        assertEquals(110L, store.cached(DashboardFile.CALENDAR).notFoundAtMillis)
        assertEquals(120L, store.lastCompleteSyncAtMillis())
        assertFalse(store.lastSyncHadErrors())
        assertEquals(100L, store.cached(DashboardFile.TODOS).lastSuccessAtMillis)
    }

    private fun ByteArray.containsBytes(needle: ByteArray): Boolean =
        needle.isNotEmpty() && indices.any { start ->
            start + needle.size <= size && needle.indices.all { offset -> this[start + offset] == needle[offset] }
        }

    private class FixedKeyProvider(private val key: SecretKey) : AesKeyProvider {
        override fun getOrCreate(): SecretKey = key
    }
}
