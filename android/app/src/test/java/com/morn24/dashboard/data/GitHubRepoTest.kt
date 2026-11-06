package com.morn24.dashboard.data

import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class GitHubRepoTest {
    private lateinit var server: MockWebServer
    private val testCredential = java.util.UUID.randomUUID().toString()

    @Before
    fun startServer() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun stopServer() {
        server.close()
    }

    @Test
    fun readsContentsWithBranchAndAuthorizationAndPreservesUnicodeAndUnknownFields() {
        val json = """{"version":1,"updatedAt":"2026-10-31T10:00:00+09:00","future":{"flag":true},"items":[{"id":"t1","title":"아침 ☕️"}]}"""
        server.enqueue(okResponse(contentResponse(json, "sha-a")))

        val result = repo().read(DashboardFile.TODOS) as RemoteRead.Found
        val request = server.takeRequest()

        assertEquals("sha-a", result.sha)
        assertEquals("아침 ☕️", result.document["items"]!!.jsonArray[0].jsonObject["title"]!!.jsonPrimitive.content)
        assertEquals(true, result.document["future"]!!.jsonObject["flag"]!!.jsonPrimitive.content.toBoolean())
        assertEquals("GET", request.method)
        assertEquals("/repos/morn24/dashboard-data/contents/data/todos.json?ref=main", request.url.encodedPath + "?" + request.url.encodedQuery)
        assertEquals("application/vnd.github+json", request.headers["Accept"])
        assertEquals("Bearer $testCredential", request.headers["Authorization"])
        assertEquals("2022-11-28", request.headers["X-GitHub-Api-Version"])
    }

    @Test
    fun returnsNotFoundAsDistinctFromAnEmptyFile() {
        server.enqueue(MockResponse.Builder().code(404).body("{}" ).build())
        assertEquals(RemoteRead.NotFound, repo().read(DashboardFile.CALENDAR))
    }

    @Test
    fun preservesHttpStatusForAuthorizationAndPermissionErrorsWithoutEchoingResponseBody() {
        listOf(401, 403).forEach { status ->
            server.enqueue(MockResponse.Builder().code(status).body("server response must not be surfaced").build())
            val error = runCatching { repo().read(DashboardFile.TODOS) }.exceptionOrNull() as GitHubApiException
            assertEquals(status, error.statusCode)
            assertFalse(error.message.orEmpty().contains("server response"))
        }
    }

    @Test
    fun malformedRemoteJsonFailsInsteadOfBecomingAnEmptyDocument() {
        server.enqueue(okResponse(contentResponse("not json", "sha-a")))
        assertTrue(runCatching { repo().read(DashboardFile.TODOS) }.exceptionOrNull() is InvalidRemoteJsonException)
    }

    @Test
    fun completionChangesOnlyDoneOnHistoryAndUpdatedAtAndWritesUtf8PrettyJsonWithSha() {
        val original = """{"version":1,"updatedAt":"old","future":{"keep":[1,"한글"]},"items":[{"id":"t1","title":"체크 ✅","repeat":"daily","doneOn":null,"custom":{"x":7}},{"id":"t2","doneOn":null}]}"""
        server.enqueue(okResponse(contentResponse(original, "sha-before")))
        server.enqueue(okResponse("""{"content":{"sha":"sha-after"}}"""))

        val result = repo().mutate(DashboardFile.TODOS, "widget: test") { TodoMutations.setCompletion(it, "t1", "2026-10-31", true) }
        val get = server.takeRequest()
        val put = server.takeRequest()
        val body = Json.parseToJsonElement(bodyText(put)).jsonObject
        val savedText = String(Base64.getDecoder().decode(body["content"]!!.jsonPrimitive.content), Charsets.UTF_8)
        val saved = Json.parseToJsonElement(savedText).jsonObject

        assertEquals("GET", get.method)
        assertEquals("PUT", put.method)
        assertEquals("sha-before", body["sha"]!!.jsonPrimitive.content)
        assertEquals("main", body["branch"]!!.jsonPrimitive.content)
        assertTrue(body["message"]!!.jsonPrimitive.content.contains("widget"))
        assertEquals("2026-10-31T12:34:56Z", saved["updatedAt"]!!.jsonPrimitive.content)
        assertEquals("한글", saved["future"]!!.jsonObject["keep"]!!.jsonArray[1].jsonPrimitive.content)
        val items = saved["items"]!!.jsonArray
        assertEquals("2026-10-31", items[0].jsonObject["doneOn"]!!.jsonPrimitive.content)
        assertEquals(7, items[0].jsonObject["custom"]!!.jsonObject["x"]!!.jsonPrimitive.content.toInt())
        assertEquals("t2", items[1].jsonObject["id"]!!.jsonPrimitive.content)
        assertTrue(savedText.endsWith("\n"))
        assertTrue(savedText.contains("\n  \"version\""))
        assertEquals("sha-after", result.sha)
        assertEquals("sha-before", result.previousSha)
        assertEquals("2026-10-31T12:34:56Z", result.updatedAt)
    }

    @Test
    fun retriesConflictByRereadingNewestShaAndReapplyingSameTarget() {
        val first = """{"version":1,"items":[{"id":"t1","doneOn":null}],"before":"old"}"""
        val second = """{"version":1,"items":[{"id":"t1","doneOn":null}],"before":"new","fromWeb":"preserved"}"""
        server.enqueue(okResponse(contentResponse(first, "sha-1")))
        server.enqueue(MockResponse.Builder().code(409).body("conflict").build())
        server.enqueue(okResponse(contentResponse(second, "sha-2")))
        server.enqueue(okResponse("""{"content":{"sha":"sha-3"}}"""))
        val delays = mutableListOf<Long>()

        val result = repo(sleeper = delays::add).mutate(DashboardFile.TODOS, "widget: test") { TodoMutations.setCompletion(it, "t1", "2026-10-31", true) }
        val requests = (1..4).map { server.takeRequest() }
        val retryBody = Json.parseToJsonElement(bodyText(requests[3])).jsonObject
        val savedText = String(Base64.getDecoder().decode(retryBody["content"]!!.jsonPrimitive.content), Charsets.UTF_8)
        val saved = Json.parseToJsonElement(savedText).jsonObject

        assertEquals(listOf("GET", "PUT", "GET", "PUT"), requests.map { it.method })
        assertEquals("sha-1", Json.parseToJsonElement(bodyText(requests[1])).jsonObject["sha"]!!.jsonPrimitive.content)
        assertEquals("sha-2", retryBody["sha"]!!.jsonPrimitive.content)
        assertEquals("preserved", saved["fromWeb"]!!.jsonPrimitive.content)
        assertEquals("2026-10-31", saved["items"]!!.jsonArray[0].jsonObject["doneOn"]!!.jsonPrimitive.content)
        assertEquals(listOf(600L), delays)
        assertEquals("sha-3", result.sha)
    }

    @Test
    fun retries422ConflictAndSucceedsWithTheFreshSha() {
        server.enqueue(okResponse(contentResponse("""{"items":[{"id":"t1","doneOn":"2026-10-30"}]}""", "sha-old")))
        server.enqueue(MockResponse.Builder().code(422).body("conflict").build())
        server.enqueue(okResponse(contentResponse("""{"items":[{"id":"t1","doneOn":"2026-10-30"}],"newField":true}""", "sha-new")))
        server.enqueue(okResponse("""{"content":{"sha":"sha-saved"}}"""))

        val result = repo().mutate(DashboardFile.TODOS, "widget: test") { TodoMutations.setCompletion(it, "t1", "2026-10-31", false) }
        val requests = (1..4).map { server.takeRequest() }
        val putBody = Json.parseToJsonElement(bodyText(requests[3])).jsonObject
        val savedText = String(Base64.getDecoder().decode(putBody["content"]!!.jsonPrimitive.content), Charsets.UTF_8)
        val saved = Json.parseToJsonElement(savedText).jsonObject

        assertEquals(listOf("GET", "PUT", "GET", "PUT"), requests.map { it.method })
        assertEquals("sha-new", putBody["sha"]!!.jsonPrimitive.content)
        assertEquals("sha-saved", result.sha)
        assertTrue(saved["items"]!!.jsonArray[0].jsonObject["doneOn"] is JsonNull)
    }

    @Test
    fun fourConflictsAreTheMaximumPutAttempts() {
        repeat(4) { index ->
            server.enqueue(okResponse(contentResponse("""{"items":[{"id":"t1","doneOn":null}]}""", "sha-$index")))
            server.enqueue(MockResponse.Builder().code(422).body("conflict").build())
        }
        val delays = mutableListOf<Long>()
        val error = runCatching { repo(sleeper = delays::add).mutate(DashboardFile.TODOS, "widget: test") { TodoMutations.setCompletion(it, "t1", "2026-10-31", true) } }.exceptionOrNull() as GitHubApiException

        assertEquals(422, error.statusCode)
        assertEquals(listOf(600L, 1_200L, 1_800L), delays)
        val requests = (1..8).map { server.takeRequest() }
        assertEquals(8, requests.size)
        assertEquals(listOf("GET", "PUT", "GET", "PUT", "GET", "PUT", "GET", "PUT"), requests.map { it.method })
    }

    @Test
    fun deletedTodoIsNotRecreated() {
        server.enqueue(okResponse(contentResponse("""{"items":[{"id":"other"}]}""", "sha")))
        assertTrue(runCatching { repo().mutate(DashboardFile.TODOS, "widget: test") { TodoMutations.setCompletion(it, "missing", "2026-10-31", true) } }.exceptionOrNull() is MissingTodoException)
        assertEquals("GET", server.takeRequest().method)
        assertEquals(null, server.takeRequest(10, java.util.concurrent.TimeUnit.MILLISECONDS))
    }

    @Test
    fun repeatedTargetIsIdempotentAndDoesNotWriteANewCommit() {
        val current = """{"version":1,"items":[{"id":"t1","doneOn":"2026-10-31"}]}"""
        server.enqueue(okResponse(contentResponse(current, "sha-current")))

        val result = repo().mutate(DashboardFile.TODOS, "widget: test") { TodoMutations.setCompletion(it, "t1", "2026-10-31", true) }

        assertEquals("sha-current", result.sha)
        assertFalse(result.changed)
        assertEquals("GET", server.takeRequest().method)
        assertEquals(null, server.takeRequest(10, java.util.concurrent.TimeUnit.MILLISECONDS))
    }

    @Test
    fun conditionalUndoRejectsAValueChangedByAnotherDeviceBeforeWriting() {
        val current = """{"version":1,"items":[{"id":"t1","doneOn":"2026-11-01"}]}"""
        server.enqueue(okResponse(contentResponse(current, "sha-current")))

        val error = runCatching { repo().mutate(DashboardFile.TODOS, "widget: undo") { TodoMutations.restoreCompletion(it, "t1", "2026-10-31", "2026-10-31", null, null, true) } }.exceptionOrNull()

        assertTrue(error is UndoConflictException)
        assertEquals("GET", server.takeRequest().method)
        assertEquals(null, server.takeRequest(10, java.util.concurrent.TimeUnit.MILLISECONDS))
    }

    @Test
    fun conditionalUndoRechecksExpectedValueAfterAShaConflict() {
        val first = """{"version":1,"items":[{"id":"t1","doneOn":"2026-10-31"}]}"""
        val changed = """{"version":1,"items":[{"id":"t1","doneOn":"2026-11-01"}]}"""
        server.enqueue(okResponse(contentResponse(first, "sha-1")))
        server.enqueue(MockResponse.Builder().code(409).body("conflict").build())
        server.enqueue(okResponse(contentResponse(changed, "sha-2")))

        val error = runCatching { repo(sleeper = {}).mutate(DashboardFile.TODOS, "widget: undo") { TodoMutations.restoreCompletion(it, "t1", "2026-10-31", "2026-10-31", null, null, true) } }.exceptionOrNull()

        assertTrue(error is UndoConflictException)
        assertEquals(listOf("GET", "PUT", "GET"), (1..3).map { server.takeRequest().method })
        assertEquals(null, server.takeRequest(10, java.util.concurrent.TimeUnit.MILLISECONDS))
    }

    private fun repo(sleeper: (Long) -> Unit = {}): GitHubRepo = GitHubRepo(
        owner = "morn24",
        repository = "dashboard-data",
        branch = "main",
        token = testCredential,
        client = OkHttpClient.Builder().build(),
        apiBaseUrl = server.url("/"),
        clock = Clock.fixed(Instant.parse("2026-10-31T12:34:56Z"), ZoneOffset.UTC),
        sleeper = sleeper,
    )

    private fun contentResponse(raw: String, sha: String): String {
        val content = Base64.getEncoder().encodeToString(raw.toByteArray(Charsets.UTF_8))
        return """{"sha":"$sha","content":"$content","encoding":"base64"}"""
    }

    private fun bodyText(request: mockwebserver3.RecordedRequest): String =
        String(request.body!!.toByteArray(), Charsets.UTF_8)

    private fun okResponse(body: String) = MockResponse.Builder().code(200).body(body).build()
}
