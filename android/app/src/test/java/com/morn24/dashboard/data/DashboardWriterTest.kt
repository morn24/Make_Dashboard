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
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DashboardWriterTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private lateinit var scope: CoroutineScope
    private lateinit var store: DashboardStore
    private lateinit var cipher: TokenCipher
    private var generation = 0L

    private val todos = """{"version":1,"updatedAt":"old","items":[{"id":"a","title":"A","repeat":"none","doneOn":null,"gtask":"g-a"},{"id":"b","title":"B","repeat":"none","doneOn":null},{"id":"c","title":"C","repeat":"daily","doneOn":null}],"keep":1}"""
    private val projects = """{"version":1,"updatedAt":"old","projects":[{"id":"p1","name":"P1","status":"active","progress":10,"next":["하나","둘"],"updatedAt":"x"},{"id":"p2","name":"P2","status":"active","progress":5,"next":["셋"],"checked":[{"text":"셋","at":"t"}]}]}"""

    @Before
    fun setUp() = runBlocking {
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        store = DashboardStore(PreferenceDataStoreFactory.create(scope = scope) { File(temporaryFolder.root, "d.preferences_pb") })
        cipher = TokenCipher(object : AesKeyProvider {
            private val key: SecretKey = SecretKeySpec(ByteArray(32) { (it + 3).toByte() }, "AES")
            override fun getOrCreate(): SecretKey = key
        })
        generation = store.saveSettings(DashboardSettings(encryptedToken = cipher.encrypt("writer-test-token")))
        store.saveSuccessfulFile(DashboardFile.TODOS, todos, "sha-0", 1_000L)
        store.saveSuccessfulFile(DashboardFile.PROJECTS, projects, "sha-0", 1_000L)
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    private fun writer(repo: FakeDashboardRepository) = DashboardWriter(
        store, DashboardRepositoryFactory { _, _, _, _ -> repo }, cipher,
        Clock.fixed(Instant.parse("2026-11-07T01:00:00Z"), ZoneId.of("Asia/Seoul")),
    )

    private fun repo() = FakeDashboardRepository(mapOf(DashboardFile.TODOS to todos, DashboardFile.PROJECTS to projects))

    private fun ids(raw: String?) =
        kotlinx.serialization.json.Json.parseToJsonElement(raw!!).jsonObject["items"]!!.jsonArray.map { it.jsonObject["id"]!!.jsonPrimitive.content }

    @Test
    fun addAppendsAtTheEndKeepsOtherKeysAndRefreshesTheCache() = runBlocking {
        val repo = repo()
        val item = TodoMutations.newItem("t_new", "병원", "none", null, "2026-11-08", "14:00", "2026-11-07T10:00:00+09:00")

        val outcome = writer(repo).addTodo(item)

        assertEquals(WriteOutcome.Saved, outcome)
        val remote = repo.document(DashboardFile.TODOS)
        assertEquals(listOf("a", "b", "c", "t_new"), remote["items"]!!.jsonArray.map { it.jsonObject["id"]!!.jsonPrimitive.content })
        assertEquals("g-a", remote["items"]!!.jsonArray[0].jsonObject["gtask"]!!.jsonPrimitive.content)
        assertEquals(JsonPrimitive(1), remote["keep"])
        assertTrue(repo.writes.single().second.startsWith("widget: 추가"))
        val cache = store.cached(DashboardFile.TODOS)
        assertEquals(repo.text(DashboardFile.TODOS), cache.rawJson)
        assertEquals(repo.document(DashboardFile.TODOS)["updatedAt"], remote["updatedAt"])
        assertTrue(remote["updatedAt"]!!.jsonPrimitive.content != "old")
        assertNull(store.completedUndo())
    }

    @Test
    fun editAndPostponeWriteOnlyTheirKeys() = runBlocking {
        val repo = repo()
        val w = writer(repo)
        assertEquals(WriteOutcome.Saved, w.editTodo("b", "B", mapOf("title" to JsonPrimitive("B2"), "time" to JsonPrimitive("08:30"))))
        assertEquals(WriteOutcome.Saved, w.editTodo("b", "B2", mapOf("date" to JsonPrimitive("2026-11-08")), "내일로 미룸"))
        val b = repo.document(DashboardFile.TODOS)["items"]!!.jsonArray[1].jsonObject
        assertEquals("B2", b["title"]!!.jsonPrimitive.content)
        assertEquals("08:30", b["time"]!!.jsonPrimitive.content)
        assertEquals("2026-11-08", b["date"]!!.jsonPrimitive.content)
        assertEquals(2, repo.writes.size)
    }

    @Test
    fun deleteLeavesAUndoRecordThatRestoresTheItemInPlace() = runBlocking {
        val repo = repo()
        assertEquals(WriteOutcome.Saved, writer(repo).deleteTodo("b"))
        assertEquals(listOf("a", "c"), ids(repo.text(DashboardFile.TODOS)))
        val undo = store.completedUndo()
        assertNotNull(undo)
        assertEquals(UndoKind.DELETION, undo!!.kind)
        assertEquals("B", undo.title)
        assertEquals("c", undo.afterId)
        assertEquals("2026-11-07", undo.completedDate)
        assertTrue(undo.deletedItemJson!!.contains("\"id\":\"b\"") || undo.deletedItemJson!!.contains("\"id\": \"b\""))
        assertEquals(listOf("a", "c"), ids(store.cached(DashboardFile.TODOS).rawJson))
    }

    @Test
    fun aFailedSaveKeepsTheCacheAndSaysSoInKorean() = runBlocking {
        val repo = repo().apply { failWith = java.io.IOException("offline") }
        val outcome = writer(repo).addTodo(TodoMutations.newItem("x", "x", "none", null, null, null, "now"))
        assertTrue(outcome is WriteOutcome.Failed)
        assertTrue((outcome as WriteOutcome.Failed).message.contains("입력은 그대로"))
        assertEquals(todos, store.cached(DashboardFile.TODOS).rawJson)
        assertEquals("sha-0", store.cached(DashboardFile.TODOS).sha)

        repo.failWith = GitHubApiException(401)
        assertTrue((writer(repo).addTodo(TodoMutations.newItem("x", "x", "none", null, null, null, "now")) as WriteOutcome.Failed).message.contains("401"))
    }

    @Test
    fun aShaConflictIsRetriedAgainstTheNewestFileAndNeverOverwrites() = runBlocking {
        val repo = repo()
        // Another device adds an item between our read and our write.
        repo.beforeWrite = {
            repo.setText(
                DashboardFile.TODOS,
                todos.replace("""{"id":"c",""", """{"id":"other-device","title":"X","repeat":"none","doneOn":null},{"id":"c","""),
            )
        }
        val outcome = writer(repo).addTodo(TodoMutations.newItem("t_new", "병원", "none", null, null, null, "now"))
        assertEquals(WriteOutcome.Saved, outcome)
        assertEquals(listOf("a", "b", "other-device", "c", "t_new"), ids(repo.text(DashboardFile.TODOS)))
    }

    @Test
    fun aConnectionChangedDuringTheWriteDoesNotTouchTheCache() = runBlocking {
        val repo = repo()
        repo.beforeWrite = {
            runBlocking { store.saveSettings(DashboardSettings(webUrl = "https://example.org/", encryptedToken = cipher.encrypt("third"))) }
        }
        val outcome = writer(repo).addTodo(TodoMutations.newItem("t", "t", "none", null, null, null, "now"))
        assertEquals(WriteOutcome.StaleConfiguration, outcome)
        assertEquals(todos, store.cached(DashboardFile.TODOS).rawJson)
    }

    @Test
    fun nextTaskCheckWritesOnlyCheckedOfThatProjectAndRefreshesTheProjectsCache() = runBlocking {
        val repo = repo()
        assertEquals(WriteOutcome.Saved, writer(repo).setNextChecked("p1", "하나", true))
        val doc = repo.document(DashboardFile.PROJECTS)
        val p1 = doc["projects"]!!.jsonArray[0].jsonObject
        assertEquals("하나", p1["checked"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content)
        assertEquals("2026-11-07T10:00:00+09:00", p1["checked"]!!.jsonArray[0].jsonObject["at"]!!.jsonPrimitive.content)
        assertEquals(2, p1["next"]!!.jsonArray.size)
        assertEquals("x", p1["updatedAt"]!!.jsonPrimitive.content)
        assertEquals(1, doc["projects"]!!.jsonArray[1].jsonObject["checked"]!!.jsonArray.size)
        assertEquals(repo.text(DashboardFile.PROJECTS), store.cached(DashboardFile.PROJECTS).rawJson)
        assertEquals(todos, store.cached(DashboardFile.TODOS).rawJson)

        assertEquals(WriteOutcome.Saved, writer(repo).setNextChecked("p1", "하나", false))
        assertFalse(repo.document(DashboardFile.PROJECTS)["projects"]!!.jsonArray[0].jsonObject.containsKey("checked"))
    }

    @Test
    fun nextTaskCheckSurvivesAShaConflictAndKeepsTheOtherDeviceChange() = runBlocking {
        val repo = repo()
        repo.beforeWrite = {
            repo.setText(DashboardFile.PROJECTS, projects.replace(""""progress":10""", """"progress":55"""))
        }
        assertEquals(WriteOutcome.Saved, writer(repo).setNextChecked("p1", "둘", true))
        val p1 = repo.document(DashboardFile.PROJECTS)["projects"]!!.jsonArray[0].jsonObject
        assertEquals(55, p1["progress"]!!.jsonPrimitive.content.toInt())
        assertEquals("둘", p1["checked"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content)
    }
}
