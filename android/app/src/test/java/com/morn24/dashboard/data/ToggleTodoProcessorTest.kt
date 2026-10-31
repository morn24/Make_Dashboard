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

class ToggleTodoProcessorTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private lateinit var datastoreScope: CoroutineScope
    private lateinit var store: DashboardStore
    private lateinit var cipher: TokenCipher
    private lateinit var settings: DashboardSettings
    private lateinit var rawTodos: String

    @Before
    fun createStore() {
        datastoreScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        store = DashboardStore(PreferenceDataStoreFactory.create(scope = datastoreScope) {
            File(temporaryFolder.root, "dashboard.preferences_pb")
        })
        cipher = TokenCipher(FixedKeyProvider(SecretKeySpec(ByteArray(32) { (it + 9).toByte() }, "AES")))
        rawTodos = """{"version":1,"updatedAt":"before","items":[{"id":"todo-1","title":"할 일","repeat":"daily","doneOn":null,"futureField":{"retain":true}}],"futureRoot":7}"""
    }

    @After
    fun closeScope() {
        datastoreScope.cancel()
    }

    @Test
    fun successWritesTheExplicitTargetAndPreservesUnknownJson() = runBlocking {
        val pending = preparePending(targetDoneOn = "2026-10-31")
        var receivedTarget: String? = null
        val updatedJson = """{"version":1,"updatedAt":"after","items":[{"id":"todo-1","title":"할 일","repeat":"daily","doneOn":"2026-10-31","futureField":{"retain":true}}],"futureRoot":7}"""
        val factory = factory { todoId, target ->
            assertEquals("todo-1", todoId)
            receivedTarget = target
            mutation(updatedJson)
        }

        val result = processor(factory).process(pending.todoId, pending.operationId)

        assertEquals(ToggleTodoResult.Saved, result)
        assertEquals("2026-10-31", receivedTarget)
        assertNull(store.pendingToggle(pending.todoId))
        val cache = store.cached(DashboardFile.TODOS)
        assertEquals("updated-sha", cache.sha)
        assertTrue(cache.rawJson.orEmpty().contains("futureField"))
        assertTrue(cache.rawJson.orEmpty().contains("futureRoot"))
        assertTrue(cache.rawJson.orEmpty().endsWith("\n"))
        assertEquals(3_000L, cache.lastSuccessAtMillis)
        assertNull(cache.error)
    }

    @Test
    fun failureRemovesOnlyItsOverlayAndStoresASafeKoreanError() = runBlocking {
        val pending = preparePending(targetDoneOn = "2026-10-31")
        val factory = factory { _, _ -> throw GitHubApiException(409) }

        val result = processor(factory).process(pending.todoId, pending.operationId)

        assertTrue(result is ToggleTodoResult.Failed)
        assertTrue((result as ToggleTodoResult.Failed).message.contains("충돌"))
        assertNull(store.pendingToggle(pending.todoId))
        assertEquals(rawTodos, store.cached(DashboardFile.TODOS).rawJson)
        assertEquals("before-sha", store.cached(DashboardFile.TODOS).sha)
        assertTrue(store.cached(DashboardFile.TODOS).error.orEmpty().contains("원래 상태"))
    }

    @Test
    fun staleSettingsGenerationDoesNotSendOrApplyTheMutation() = runBlocking {
        val pending = preparePending(targetDoneOn = "2026-10-31")
        store.saveSettings(settings.copy(webUrl = "https://example.com/"))
        var callCount = 0
        val factory = factory { _, _ ->
            callCount += 1
            mutation(rawTodos)
        }

        val result = processor(factory).process(pending.todoId, pending.operationId)

        assertEquals(ToggleTodoResult.StaleConfiguration, result)
        assertEquals(0, callCount)
        assertNull(store.pendingToggle(pending.todoId))
        assertNull(store.cached(DashboardFile.TODOS).error)
    }

    @Test
    fun deletedTodoReturnsFailureWithoutReplacingTheCachedDocument() = runBlocking {
        val pending = preparePending(targetDoneOn = "2026-10-31")
        val factory = factory { _, _ -> throw MissingTodoException("todo-1") }

        val result = processor(factory).process(pending.todoId, pending.operationId)

        assertTrue(result is ToggleTodoResult.Failed)
        assertTrue((result as ToggleTodoResult.Failed).message.contains("사라졌어요"))
        assertNull(store.pendingToggle(pending.todoId))
        assertEquals(rawTodos, store.cached(DashboardFile.TODOS).rawJson)
    }

    @Test
    fun successfulCompletionStoresTheOriginalValueForOneSafeUndo() = runBlocking {
        val pending = preparePending(targetDoneOn = "2026-10-31")
        val factory = factory { _, _ -> mutation(rawTodos).copy(originalDoneOn = null) }

        val result = processor(factory).process(pending.todoId, pending.operationId)

        assertEquals(ToggleTodoResult.Saved, result)
        val undo = store.completedUndo()!!
        assertEquals("todo-1", undo.todoId)
        assertEquals("할 일", undo.title)
        assertNull(undo.originalDoneOn)
        assertEquals("2026-10-31", undo.completedDoneOn)
        assertEquals(pending.expectedGeneration, undo.generation)
        assertNull(store.pendingToggle("todo-1"))
    }

    @Test
    fun undoUsesConditionalMutationAndKeepsRecordWhenAnotherDeviceChangedTodo() = runBlocking {
        val completion = preparePending(targetDoneOn = "2026-10-31")
        val finish = processor(factory { _, _ -> mutation(rawTodos).copy(originalDoneOn = null) })
            .process(completion.todoId, completion.operationId)
        assertEquals(ToggleTodoResult.Saved, finish)
        val undo = store.completedUndo()!!
        val pendingUndo = PendingTodoToggle(
            operationId = "undo-operation",
            todoId = undo.todoId,
            targetDoneOn = undo.originalDoneOn,
            tappedDate = undo.completedDate,
            expectedGeneration = undo.generation,
            createdAtMillis = 4_000L,
            title = undo.title,
            undoRecordId = undo.recordId,
            requiresExpectedMatch = true,
            expectedDoneOn = undo.completedDoneOn,
        )
        assertTrue(store.enqueuePendingToggle(pendingUndo))
        var conditionalCalls = 0
        val repositoryFactory = DashboardRepositoryFactory { _, _, _, _ ->
            object : DashboardRepository {
                override fun read(file: DashboardFile): RemoteRead = error("unused")
                override fun updateTodo(todoId: String, targetDoneOn: String?): MutationResult = error("must use conditional update")
                override fun updateTodoIfCurrent(todoId: String, expectedDoneOn: String?, targetDoneOn: String?): MutationResult {
                    conditionalCalls++
                    assertEquals(undo.completedDoneOn, expectedDoneOn)
                    assertEquals(undo.originalDoneOn, targetDoneOn)
                    throw UndoConflictException()
                }
            }
        }

        val result = processor(repositoryFactory).process(pendingUndo.todoId, pendingUndo.operationId)

        assertTrue(result is ToggleTodoResult.Failed)
        assertEquals(1, conditionalCalls)
        assertEquals(undo, store.completedUndo())
        assertNull(store.pendingToggle(undo.todoId))
        assertTrue(store.cached(DashboardFile.TODOS).error.orEmpty().contains("다른 기기"))
    }

    private suspend fun preparePending(targetDoneOn: String?): PendingTodoToggle {
        settings = DashboardSettings(encryptedToken = cipher.encrypt("processor-test-token"))
        val generation = store.saveSettings(settings)
        store.saveSuccessfulFile(DashboardFile.TODOS, rawTodos, "before-sha", 1_000L)
        return PendingTodoToggle(
            operationId = "operation-1",
            todoId = "todo-1",
            targetDoneOn = targetDoneOn,
            tappedDate = "1970-01-01",
            expectedGeneration = generation,
            createdAtMillis = 2_000L,
        ).also { assertTrue(store.enqueuePendingToggle(it)) }
    }

    private fun processor(factory: DashboardRepositoryFactory) = ToggleTodoProcessor(
        store = store,
        repositoryFactory = factory,
        tokenCipher = cipher,
        clock = Clock.fixed(Instant.ofEpochMilli(3_000L), ZoneId.of("Asia/Seoul")),
    )

    private fun factory(update: (String, String?) -> MutationResult) = DashboardRepositoryFactory { _, _, _, _ ->
        object : DashboardRepository {
            override fun read(file: DashboardFile): RemoteRead = error("unused")
            override fun updateTodo(todoId: String, targetDoneOn: String?): MutationResult = update(todoId, targetDoneOn)
        }
    }

    private fun mutation(json: String) = MutationResult(
        document = Json.parseToJsonElement(json).jsonObject,
        sha = "updated-sha",
        previousSha = "before-sha",
        updatedAt = "after",
    )

    private class FixedKeyProvider(private val key: SecretKey) : AesKeyProvider {
        override fun getOrCreate(): SecretKey = key
    }
}
