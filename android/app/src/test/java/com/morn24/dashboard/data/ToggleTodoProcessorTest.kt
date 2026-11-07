package com.morn24.dashboard.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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

    // The processor clock is fixed at the epoch, so "today" for these tests is 1970-01-01.
    private val today = "1970-01-01"

    @Before
    fun createStore() {
        datastoreScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        store = DashboardStore(PreferenceDataStoreFactory.create(scope = datastoreScope) {
            File(temporaryFolder.root, "dashboard.preferences_pb")
        })
        cipher = TokenCipher(FixedKeyProvider(SecretKeySpec(ByteArray(32) { (it + 9).toByte() }, "AES")))
        rawTodos = """{"version":1,"updatedAt":"before","items":[{"id":"todo-1","title":"할 일","repeat":"daily","doneOn":null,"futureField":{"retain":true},"gtask":"g-1","gsync":["할 일","",null,false]}],"futureRoot":7}"""
    }

    @After
    fun closeScope() {
        datastoreScope.cancel()
    }

    @Test
    fun completionAppliesTheWebRulesAndPreservesUnknownJson() = runBlocking {
        val pending = preparePending(makeDone = true)
        val repo = FakeDashboardRepository(mapOf(DashboardFile.TODOS to rawTodos))

        val result = processor(repo).process(pending.todoId, pending.operationId)

        assertEquals(ToggleTodoResult.Saved, result)
        assertNull(store.pendingToggle(pending.todoId))
        val item = repo.document(DashboardFile.TODOS)["items"]!!.jsonArray[0].jsonObject
        assertEquals(today, item["doneOn"]!!.jsonPrimitive.content)
        assertEquals(listOf(today), item["history"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals("g-1", item["gtask"]!!.jsonPrimitive.content)
        assertTrue(item["gsync"] is JsonArray)
        assertEquals(true, item["futureField"]!!.jsonObject["retain"]!!.jsonPrimitive.content.toBoolean())
        assertEquals("7", repo.document(DashboardFile.TODOS)["futureRoot"]!!.jsonPrimitive.content)
        val cache = store.cached(DashboardFile.TODOS)
        assertEquals(repo.text(DashboardFile.TODOS), cache.rawJson)
        assertTrue(cache.rawJson.orEmpty().endsWith("\n"))
        assertEquals(3_000L, cache.lastSuccessAtMillis)
        assertNull(cache.error)
        assertTrue(repo.document(DashboardFile.TODOS)["updatedAt"]!!.jsonPrimitive.content != "before")
    }

    @Test
    fun failureRemovesOnlyItsOverlayAndStoresASafeKoreanError() = runBlocking {
        val pending = preparePending(makeDone = true)
        val repo = FakeDashboardRepository(mapOf(DashboardFile.TODOS to rawTodos)).apply { failWith = GitHubApiException(409) }

        val result = processor(repo).process(pending.todoId, pending.operationId)

        assertTrue(result is ToggleTodoResult.Failed)
        assertTrue((result as ToggleTodoResult.Failed).message.contains("충돌"))
        assertNull(store.pendingToggle(pending.todoId))
        assertEquals(rawTodos, store.cached(DashboardFile.TODOS).rawJson)
        assertEquals("before-sha", store.cached(DashboardFile.TODOS).sha)
        assertTrue(store.cached(DashboardFile.TODOS).error.orEmpty().contains("원래 상태"))
    }

    @Test
    fun staleSettingsGenerationDoesNotSendOrApplyTheMutation() = runBlocking {
        val pending = preparePending(makeDone = true)
        store.saveSettings(settings.copy(webUrl = "https://example.com/"))
        val repo = FakeDashboardRepository(mapOf(DashboardFile.TODOS to rawTodos))

        val result = processor(repo).process(pending.todoId, pending.operationId)

        assertEquals(ToggleTodoResult.StaleConfiguration, result)
        assertTrue(repo.writes.isEmpty())
        assertNull(store.pendingToggle(pending.todoId))
        assertNull(store.cached(DashboardFile.TODOS).error)
    }

    @Test
    fun deletedTodoReturnsFailureWithoutReplacingTheCachedDocument() = runBlocking {
        val pending = preparePending(makeDone = true)
        val repo = FakeDashboardRepository(mapOf(DashboardFile.TODOS to """{"version":1,"items":[{"id":"other","title":"x","repeat":"none","doneOn":null}]}"""))

        val result = processor(repo).process(pending.todoId, pending.operationId)

        assertTrue(result is ToggleTodoResult.Failed)
        assertTrue((result as ToggleTodoResult.Failed).message.contains("사라졌어요"))
        assertNull(store.pendingToggle(pending.todoId))
        assertEquals(rawTodos, store.cached(DashboardFile.TODOS).rawJson)
    }

    @Test
    fun completionStoresTheValuesBeforeItForAnExactUndo() = runBlocking {
        val pending = preparePending(makeDone = true)
        val repo = FakeDashboardRepository(mapOf(DashboardFile.TODOS to rawTodos))

        assertEquals(ToggleTodoResult.Saved, processor(repo).process(pending.todoId, pending.operationId))

        val undo = store.completedUndo()!!
        assertEquals("todo-1", undo.todoId)
        assertEquals("할 일", undo.title)
        assertNull(undo.originalDoneOn)
        assertNull(undo.originalHistory)
        assertTrue(undo.historyCaptured)
        assertEquals(today, undo.completedDoneOn)
        assertEquals(pending.expectedGeneration, undo.generation)
        assertEquals(UndoKind.COMPLETION, undo.kind)
        assertNull(store.pendingToggle("todo-1"))
    }

    @Test
    fun undoRestoresDoneOnAndHistoryExactlyAsTheyWereBeforeTheCompletion() = runBlocking {
        val yesterday = "1969-12-31"
        val raw = """{"version":1,"items":[{"id":"todo-1","title":"운동","repeat":"weekly","days":[0,1,2,3,4,5,6],"doneOn":"$yesterday","history":["1969-12-30","$yesterday"],"gtask":"g"}]}"""
        val pending = preparePending(makeDone = true, raw = raw)
        val repo = FakeDashboardRepository(mapOf(DashboardFile.TODOS to raw))
        assertEquals(ToggleTodoResult.Saved, processor(repo).process(pending.todoId, pending.operationId))
        val completed = repo.document(DashboardFile.TODOS)["items"]!!.jsonArray[0].jsonObject
        assertEquals(listOf("1969-12-30", yesterday, today), completed["history"]!!.jsonArray.map { it.jsonPrimitive.content })

        val undo = store.completedUndo()!!
        val pendingUndo = undoPending(undo, operationId = "undo-op")
        assertTrue(store.enqueuePendingToggle(pendingUndo))
        val result = processor(repo).process(pendingUndo.todoId, pendingUndo.operationId)

        assertEquals(ToggleTodoResult.Saved, result)
        val restored = repo.document(DashboardFile.TODOS)["items"]!!.jsonArray[0].jsonObject
        assertEquals(yesterday, restored["doneOn"]!!.jsonPrimitive.content)
        assertEquals(listOf("1969-12-30", yesterday), restored["history"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals("g", restored["gtask"]!!.jsonPrimitive.content)
        assertNull(store.completedUndo())
    }

    @Test
    fun undoKeepsTheRecordWhenAnotherDeviceChangedTheTodoAfterTheCompletion() = runBlocking {
        val completion = preparePending(makeDone = true)
        val repo = FakeDashboardRepository(mapOf(DashboardFile.TODOS to rawTodos))
        assertEquals(ToggleTodoResult.Saved, processor(repo).process(completion.todoId, completion.operationId))
        val undo = store.completedUndo()!!
        // Another device cancels the completion and re-completes it on another day.
        repo.setText(
            DashboardFile.TODOS,
            """{"version":1,"items":[{"id":"todo-1","title":"할 일","repeat":"daily","doneOn":"1970-01-02","history":["1970-01-02"]}]}""",
        )
        val pendingUndo = undoPending(undo, operationId = "undo-operation")
        assertTrue(store.enqueuePendingToggle(pendingUndo))

        val result = processor(repo).process(pendingUndo.todoId, pendingUndo.operationId)

        assertTrue(result is ToggleTodoResult.Failed)
        assertEquals(undo, store.completedUndo())
        assertNull(store.pendingToggle(undo.todoId))
        assertTrue(store.cached(DashboardFile.TODOS).error.orEmpty().contains("다른 기기"))
        assertEquals(1, repo.writes.size)
    }

    @Test
    fun deletionUndoPutsTheItemBackBeforeTheItemThatFollowedIt() = runBlocking {
        settings = DashboardSettings(encryptedToken = cipher.encrypt("processor-test-token"))
        val generation = store.saveSettings(settings)
        val raw = """{"version":1,"items":[{"id":"a","title":"A","repeat":"none","doneOn":null},{"id":"c","title":"C","repeat":"none","doneOn":null}]}"""
        store.saveSuccessfulFile(DashboardFile.TODOS, raw, "before-sha", 1_000L)
        val deleted = """{"id":"b","title":"B","repeat":"none","doneOn":null,"gtask":"keep-me"}"""
        val undo = CompletedTodoUndo(
            recordId = "del-1", todoId = "b", title = "B", originalDoneOn = null, completedDoneOn = "",
            completedDate = today, generation = generation, completedAtMillis = 1L,
            kind = UndoKind.DELETION, deletedItemJson = deleted, afterId = "c",
        )
        assertTrue(store.saveTodoMutationIfGeneration(raw, "before-sha", "before-sha", 1_000L, generation, undo))
        val pendingUndo = undoPending(undo, operationId = "restore-op")
        assertTrue(store.enqueuePendingToggle(pendingUndo))
        val repo = FakeDashboardRepository(mapOf(DashboardFile.TODOS to raw))

        val result = processor(repo).process("b", "restore-op")

        assertEquals(ToggleTodoResult.Saved, result)
        val items = repo.document(DashboardFile.TODOS)["items"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("a", "b", "c"), items.map { it["id"]!!.jsonPrimitive.content })
        assertEquals("keep-me", items[1]["gtask"]!!.jsonPrimitive.content)
        assertNull(store.completedUndo())
    }

    @Test
    fun aPendingRecordLeftByTheOldAppStillDecodesAndIsAppliedAsACompletion() = runBlocking {
        settings = DashboardSettings(encryptedToken = cipher.encrypt("processor-test-token"))
        val generation = store.saveSettings(settings)
        store.saveSuccessfulFile(DashboardFile.TODOS, rawTodos, "before-sha", 1_000L)
        // v1.5 records had only a target date, no makeDone.
        val legacy = PendingTodoToggle(
            operationId = "legacy", todoId = "todo-1", targetDoneOn = today, tappedDate = today,
            expectedGeneration = generation, createdAtMillis = 2_000L,
        )
        val decoded = Json { ignoreUnknownKeys = true }.decodeFromString<PendingTodoToggle>(
            """{"operationId":"legacy","todoId":"todo-1","targetDoneOn":"$today","tappedDate":"$today","expectedGeneration":$generation,"createdAtMillis":2000}""",
        )
        assertEquals(legacy.copy(makeDone = null), decoded)
        assertTrue(decoded.isCompletion)
        assertTrue(store.enqueuePendingToggle(decoded))
        val repo = FakeDashboardRepository(mapOf(DashboardFile.TODOS to rawTodos))

        assertEquals(ToggleTodoResult.Saved, processor(repo).process("todo-1", "legacy"))

        assertEquals(today, repo.document(DashboardFile.TODOS)["items"]!!.jsonArray[0].jsonObject["doneOn"]!!.jsonPrimitive.content)
    }

    @Test
    fun overlayShowsTheSameStateAsTheRemoteWriteWillProduce() = runBlocking {
        val pending = preparePending(makeDone = true)
        val snapshot = DashboardSyncEngine(store, DashboardRepositoryFactory { _, _, _, _ -> error("offline") }, cipher, ioDispatcher = Dispatchers.IO).loadCached()
        val overlaid = snapshot.todos.single()
        val repo = FakeDashboardRepository(mapOf(DashboardFile.TODOS to rawTodos))
        processor(repo).process(pending.todoId, pending.operationId)
        val written = DashboardDisplayData.todos(repo.document(DashboardFile.TODOS)).single()

        assertEquals(written.doneOn, overlaid.doneOn)
        assertEquals(written.history, overlaid.history)
    }

    @Test
    fun tenWidgetTapsAreVisibleBeforeThreeAndAHalfSecondRemoteResponses() = runBlocking {
        val realToday = LocalDate.now(ZoneId.systemDefault()).toString()
        settings = DashboardSettings(encryptedToken = cipher.encrypt("slow-server-test-token"))
        val generation = store.saveSettings(settings)
        val items = (1..10).joinToString(",") { index ->
            """{"id":"todo-$index","title":"지연 검증 $index","repeat":"daily","doneOn":null}"""
        }
        store.saveSuccessfulFile(DashboardFile.TODOS, """{"version":1,"items":[$items]}""", "slow-sha", 1_000L)
        val remoteStarted = CountDownLatch(1)
        val slowFactory = DashboardRepositoryFactory { _, _, _, _ ->
            object : DashboardRepository {
                override fun read(file: DashboardFile): RemoteRead = error("unused")
                override fun mutate(file: DashboardFile, message: String, change: (JsonObject) -> DocChange): MutationResult {
                    remoteStarted.countDown()
                    Thread.sleep(3_500L)
                    throw java.io.IOException("simulated delayed server")
                }
            }
        }

        val tapStartedAt = System.nanoTime()
        val pendingActions = (1..10).map { index ->
            val pending = PendingTodoToggle(
                operationId = "slow-operation-$index",
                todoId = "todo-$index",
                targetDoneOn = realToday,
                tappedDate = realToday,
                expectedGeneration = generation,
                createdAtMillis = 2_000L + index,
                title = "지연 검증 $index",
                makeDone = true,
            )
            assertTrue(store.enqueuePendingToggle(pending))
            pending
        }
        val slowRequest = async(Dispatchers.IO) {
            val pending = pendingActions.first()
            ToggleTodoProcessor(store, slowFactory, cipher).process(pending.todoId, pending.operationId)
        }

        val optimistic = DashboardSyncEngine(
            store = store,
            repositoryFactory = slowFactory,
            tokenCipher = cipher,
            ioDispatcher = Dispatchers.IO,
        ).loadCached()
        val responseMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - tapStartedAt)
        assertTrue("optimistic screen response was ${responseMillis}ms", responseMillis < 3_000L)
        assertEquals(10, optimistic.pendingTodoIds.size)
        assertTrue(optimistic.todos.all { it.doneOn == realToday })
        assertTrue("a delayed request should be in flight", remoteStarted.await(1, TimeUnit.SECONDS))

        assertTrue(slowRequest.await() is ToggleTodoResult.Failed)
        assertEquals(9, store.pendingToggles().size)
    }

    private suspend fun preparePending(makeDone: Boolean, raw: String = rawTodos): PendingTodoToggle {
        settings = DashboardSettings(encryptedToken = cipher.encrypt("processor-test-token"))
        val generation = store.saveSettings(settings)
        rawTodos = raw
        store.saveSuccessfulFile(DashboardFile.TODOS, raw, "before-sha", 1_000L)
        return PendingTodoToggle(
            operationId = "operation-1",
            todoId = "todo-1",
            targetDoneOn = if (makeDone) today else null,
            tappedDate = today,
            expectedGeneration = generation,
            createdAtMillis = 2_000L,
            makeDone = makeDone,
        ).also { assertTrue(store.enqueuePendingToggle(it)) }
    }

    private fun undoPending(undo: CompletedTodoUndo, operationId: String) = PendingTodoToggle(
        operationId = operationId,
        todoId = undo.todoId,
        targetDoneOn = undo.originalDoneOn,
        tappedDate = undo.completedDate,
        expectedGeneration = undo.generation,
        createdAtMillis = 4_000L,
        title = undo.title,
        undoRecordId = undo.recordId,
        makeDone = false,
    )

    private fun processor(repo: FakeDashboardRepository) = ToggleTodoProcessor(
        store = store,
        repositoryFactory = DashboardRepositoryFactory { _, _, _, _ -> repo },
        tokenCipher = cipher,
        clock = Clock.fixed(Instant.ofEpochMilli(3_000L), ZoneId.of("Asia/Seoul")),
    )

    private class FixedKeyProvider(private val key: SecretKey) : AesKeyProvider {
        override fun getOrCreate(): SecretKey = key
    }
}
