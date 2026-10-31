package com.morn24.dashboard.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DashboardSyncEngineTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private lateinit var datastoreScope: CoroutineScope
    private lateinit var store: DashboardStore
    private lateinit var cipher: TokenCipher

    @Before
    fun createStore() {
        datastoreScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        store = DashboardStore(PreferenceDataStoreFactory.create(scope = datastoreScope) {
            File(temporaryFolder.root, "dashboard.preferences_pb")
        })
        cipher = TokenCipher(FixedKeyProvider(SecretKeySpec(ByteArray(32) { (it + 5).toByte() }, "AES")))
    }

    @After
    fun closeScope() {
        datastoreScope.cancel()
    }

    @Test
    fun successfulFilesBecomeTypedDataAndCacheTheUnmodifiedJson() = runBlocking {
        val rawTodos = """{"version":1,"items":[{"id":"t1","title":"한글 할 일 🧭","repeat":"daily","doneOn":null,"future":{"keep":[1,2]}}],"futureRoot":true}"""
        val factory = repositoryFactory(
            DashboardFile.TODOS to found(rawTodos, "todo-sha"),
            DashboardFile.PROJECTS to found("""{"version":1,"projects":[{"id":"p1","name":"대시보드","status":"active","progress":45,"next":["첫 과제"],"checked":[]}]}""", "project-sha"),
            DashboardFile.CALENDAR to found("""{"version":1,"events":[{"id":"e1","title":"회의","allDay":false,"start":"2026-10-31T10:00:00+09:00","end":"2026-10-31T11:00:00+09:00"}]}""", "calendar-sha"),
        )
        connectStore()

        val snapshot = engine(factory).load()

        assertEquals(WidgetConnectionState.READY, snapshot.connectionState)
        assertEquals("한글 할 일 🧭", snapshot.todos.single().title)
        assertEquals(45, snapshot.projects.single().progress)
        assertEquals("회의", snapshot.calendarEvents.single().title)
        assertTrue(snapshot.calendarAvailable)
        assertEquals(7_777L, snapshot.lastUpdatedAtMillis)
        assertEquals(rawTodos, store.cached(DashboardFile.TODOS).rawJson)
        assertEquals("todo-sha", store.cached(DashboardFile.TODOS).sha)
        assertTrue(snapshot.errors.isEmpty())
    }

    @Test
    fun readFailureShowsCachedDataAndDoesNotReplaceRawJsonOrSuccessTime() = runBlocking {
        val oldJson = """{"version":1,"items":[{"id":"old","title":"캐시 할 일","repeat":"none","doneOn":null}],"futureRoot":{"preserve":true}}"""
        store.saveSuccessfulFile(DashboardFile.TODOS, oldJson, "old-sha", 1_234L)
        val factory = repositoryFactory(
            DashboardFile.TODOS to { throw java.io.IOException("offline") },
            DashboardFile.PROJECTS to { RemoteRead.NotFound },
            DashboardFile.CALENDAR to { RemoteRead.NotFound },
        )
        connectStore()

        val snapshot = engine(factory).load()

        assertEquals("캐시 할 일", snapshot.todos.single().title)
        assertEquals(oldJson, store.cached(DashboardFile.TODOS).rawJson)
        assertEquals("old-sha", store.cached(DashboardFile.TODOS).sha)
        assertEquals(1_234L, store.cached(DashboardFile.TODOS).lastSuccessAtMillis)
        assertTrue(store.cached(DashboardFile.TODOS).error.orEmpty().contains("네트워크"))
        assertTrue(snapshot.errors.any { it.contains("이전 데이터를 표시합니다") })
    }

    @Test
    fun missingFilesProduceEmptyTodosAndProjectsAndHideCalendar() = runBlocking {
        connectStore()
        val snapshot = engine(repositoryFactory()).load()

        assertTrue(snapshot.todos.isEmpty())
        assertTrue(snapshot.projects.isEmpty())
        assertTrue(snapshot.calendarEvents.isEmpty())
        assertFalse(snapshot.calendarAvailable)
        assertEquals(2, snapshot.errors.size)
        assertTrue(snapshot.errors.any { it.contains(DashboardFile.TODOS.path) })
        assertTrue(snapshot.errors.any { it.contains(DashboardFile.PROJECTS.path) })
        assertNull(snapshot.lastCompleteSyncAtMillis)
        val cachedSnapshot = engine(repositoryFactory()).loadCached()
        assertTrue(cachedSnapshot.errors.any { it.contains(DashboardFile.TODOS.path) })
        assertNull(cachedSnapshot.lastCompleteSyncAtMillis)
    }

    @Test
    fun invalidRemoteSchemaKeepsPreviousCacheAndRecordsSafeError() = runBlocking {
        val oldJson = """{"version":1,"items":[{"id":"old","title":"이전 값","repeat":"daily","doneOn":null}]}"""
        store.saveSuccessfulFile(DashboardFile.TODOS, oldJson, "old-sha", 2_000L)
        val factory = repositoryFactory(
            DashboardFile.TODOS to found("""{"version":99,"items":[]}""", "bad-sha"),
            DashboardFile.PROJECTS to { RemoteRead.NotFound },
            DashboardFile.CALENDAR to { RemoteRead.NotFound },
        )
        connectStore()

        val snapshot = engine(factory).load()

        assertEquals("이전 값", snapshot.todos.single().title)
        assertEquals(oldJson, store.cached(DashboardFile.TODOS).rawJson)
        assertEquals("old-sha", store.cached(DashboardFile.TODOS).sha)
        assertEquals(2_000L, store.cached(DashboardFile.TODOS).lastSuccessAtMillis)
        assertTrue(snapshot.errors.any { it.contains("데이터 형식") })
    }

    @Test
    fun missingCredentialRequestsSetupAndLostKeystoreKeyRequestsReconnect() = runBlocking {
        val unconfigured = engine(repositoryFactory()).load()
        assertEquals(WidgetConnectionState.NEEDS_SETUP, unconfigured.connectionState)

        connectStore()
        val otherCipher = TokenCipher(FixedKeyProvider(SecretKeySpec(ByteArray(32) { (it + 8).toByte() }, "AES")))
        val lostKeySnapshot = DashboardSyncEngine(
            store = store,
            repositoryFactory = repositoryFactory(),
            tokenCipher = otherCipher,
            clock = fixedClock(),
            ioDispatcher = Dispatchers.Unconfined,
        ).load()

        assertEquals(WidgetConnectionState.REAUTHENTICATE, lostKeySnapshot.connectionState)
    }

    @Test
    fun cachedRenderOverlaysPendingTargetWithoutNetworkAccess() = runBlocking {
        connectStore()
        val generation = store.generation()
        val rawTodos = """{"version":1,"items":[{"id":"todo-1","title":"캐시 항목","repeat":"daily","doneOn":null}]}"""
        store.saveSuccessfulFile(DashboardFile.TODOS, rawTodos, "sha", 50L)
        store.enqueuePendingToggle(
            PendingTodoToggle(
                operationId = "operation-1",
                todoId = "todo-1",
                targetDoneOn = "2026-10-31",
                tappedDate = "2026-10-31",
                expectedGeneration = generation,
                createdAtMillis = 60L,
            ),
        )

        val snapshot = engine(repositoryFactory()).loadCached()

        assertEquals("2026-10-31", snapshot.todos.single().doneOn)
        assertEquals(setOf("todo-1"), snapshot.pendingTodoIds)
        assertEquals("sha", store.cached(DashboardFile.TODOS).sha)
    }

    @Test
    fun refreshIgnoresThePreviousTodosShaDuringTheMutationGuardWindow() = runBlocking {
        connectStore()
        val generation = store.generation()
        val updatedJson = """{"version":1,"items":[{"id":"todo-1","title":"새 완료 상태","repeat":"daily","doneOn":"2026-10-31"}]}"""
        val staleJson = """{"version":1,"items":[{"id":"todo-1","title":"오래된 서버 응답","repeat":"daily","doneOn":null}]}"""
        store.saveTodoMutationIfGeneration(updatedJson, "new-sha", "old-sha", 1_000L, generation)
        val factory = repositoryFactory(
            DashboardFile.TODOS to found(staleJson, "old-sha"),
            DashboardFile.PROJECTS to { RemoteRead.NotFound },
            DashboardFile.CALENDAR to { RemoteRead.NotFound },
        )

        val snapshot = engine(factory).load()

        assertEquals("새 완료 상태", snapshot.todos.single().title)
        assertEquals("new-sha", store.cached(DashboardFile.TODOS).sha)
        assertEquals(updatedJson, store.cached(DashboardFile.TODOS).rawJson)
    }

    private suspend fun connectStore() {
        store.saveSettings(DashboardSettings(encryptedToken = cipher.encrypt("test-only-token")))
    }

    private fun engine(factory: DashboardRepositoryFactory) = DashboardSyncEngine(
        store = store,
        repositoryFactory = factory,
        tokenCipher = cipher,
        clock = fixedClock(),
        ioDispatcher = Dispatchers.Unconfined,
    )

    private fun fixedClock(): Clock = Clock.fixed(Instant.ofEpochMilli(7_777L), ZoneId.of("Asia/Seoul"))

    private fun found(rawJson: String, sha: String): () -> RemoteRead = {
        RemoteRead.Found(Json.parseToJsonElement(rawJson).jsonObject, sha, rawJson)
    }

    private fun repositoryFactory(
        vararg reads: Pair<DashboardFile, () -> RemoteRead>,
    ): DashboardRepositoryFactory {
        val readers = reads.toMap()
        return DashboardRepositoryFactory { _, _, _, _ ->
            object : DashboardRepository {
                override fun read(file: DashboardFile): RemoteRead = readers[file]?.invoke() ?: RemoteRead.NotFound
                override fun updateTodo(todoId: String, targetDoneOn: String?): MutationResult = error("unused")
            }
        }
    }

    private class FixedKeyProvider(private val key: SecretKey) : AesKeyProvider {
        override fun getOrCreate(): SecretKey = key
    }
}
