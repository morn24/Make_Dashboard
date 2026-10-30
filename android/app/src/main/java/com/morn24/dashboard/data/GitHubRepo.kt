package com.morn24.dashboard.data

import java.io.IOException
import java.time.Clock
import java.time.OffsetDateTime
import java.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

sealed interface RemoteRead {
    data class Found(val document: JsonObject, val sha: String, val rawJson: String) : RemoteRead
    data object NotFound : RemoteRead
}

data class MutationResult(
    val document: JsonObject,
    val sha: String,
    val previousSha: String,
    val updatedAt: String,
)

class GitHubApiException(val statusCode: Int) : IOException("GitHub API request failed with HTTP $statusCode")
class InvalidRemoteJsonException(cause: Throwable) : IOException("GitHub returned invalid JSON", cause)
class MissingRemoteFileException(path: String) : IOException("GitHub file is missing: $path")
class MissingTodoException(todoId: String) : IOException("Todo no longer exists: $todoId")

class GitHubRepo(
    private val owner: String,
    private val repository: String,
    private val branch: String,
    private val token: String,
    private val client: OkHttpClient = defaultClient(),
    private val apiBaseUrl: HttpUrl = "https://api.github.com/".toHttpUrl(),
    private val clock: Clock = Clock.systemDefaultZone(),
    private val sleeper: (Long) -> Unit = Thread::sleep,
) {
    fun read(file: DashboardFile): RemoteRead = readPath(file.path)

    fun readPath(path: String): RemoteRead {
        val request = Request.Builder()
            .url(contentsUrl(path).newBuilder().addQueryParameter("ref", branch).build())
            .header("Accept", ACCEPT)
            .header("Authorization", "Bearer $token")
            .header("X-GitHub-Api-Version", API_VERSION)
            .get()
            .build()

        client.newCall(request).execute().use { response ->
            if (response.code == 404) return RemoteRead.NotFound
            if (!response.isSuccessful) throw GitHubApiException(response.code)
            val body = response.body.string()
            return try {
                val wrapper = Json.parseToJsonElement(body).jsonObject
                val encodedContent = wrapper["content"]?.jsonPrimitive?.content
                    ?: throw IllegalArgumentException("Missing content")
                val sha = wrapper["sha"]?.jsonPrimitive?.content
                    ?: throw IllegalArgumentException("Missing sha")
                val rawJson = String(Base64.getMimeDecoder().decode(encodedContent), Charsets.UTF_8)
                val document = Json.parseToJsonElement(rawJson).jsonObject
                RemoteRead.Found(document, sha, rawJson)
            } catch (error: Exception) {
                throw InvalidRemoteJsonException(error)
            }
        }
    }

    fun updateTodo(todoId: String, targetDoneOn: String?): MutationResult {
        repeat(MAX_PUT_ATTEMPTS) { attempt ->
            val remote = read(DashboardFile.TODOS)
            if (remote !is RemoteRead.Found) throw MissingRemoteFileException(DashboardFile.TODOS.path)
            val items = remote.document["items"] as? JsonArray
                ?: throw InvalidRemoteJsonException(IllegalStateException("todos.items is not an array"))
            val todoIndex = items.indexOfFirst { item ->
                (item as? JsonObject)?.get("id")?.jsonPrimitive?.content == todoId
            }
            if (todoIndex < 0) throw MissingTodoException(todoId)

            val updatedAt = OffsetDateTime.now(clock).toString()
            val updatedItems = items.mapIndexed { index, item ->
                if (index != todoIndex) item
                else {
                    val original = item.jsonObject
                    JsonObject(original.toMutableMap().apply {
                        put("doneOn", targetDoneOn?.let(::JsonPrimitive) ?: JsonNull)
                    })
                }
            }
            val updatedDocument = JsonObject(remote.document.toMutableMap().apply {
                put("items", JsonArray(updatedItems))
                put("updatedAt", JsonPrimitive(updatedAt))
            })

            try {
                val newSha = put(DashboardFile.TODOS.path, updatedDocument, remote.sha, TODO_COMMIT_MESSAGE)
                return MutationResult(updatedDocument, newSha, remote.sha, updatedAt)
            } catch (error: GitHubApiException) {
                if (error.statusCode !in CONFLICT_STATUSES || attempt == MAX_PUT_ATTEMPTS - 1) throw error
                sleeper(RETRY_DELAY_MS * (attempt + 1))
            }
        }
        error("unreachable")
    }

    private fun put(path: String, document: JsonObject, sha: String, message: String): String {
        val text = json.encodeToString(kotlinx.serialization.json.JsonElement.serializer(), document) + "\n"
        val content = Base64.getEncoder().encodeToString(text.toByteArray(Charsets.UTF_8))
        val body = buildJsonObject {
            put("message", message)
            put("content", content)
            put("branch", branch)
            put("sha", sha)
        }
        val request = Request.Builder()
            .url(contentsUrl(path))
            .header("Accept", ACCEPT)
            .header("Authorization", "Bearer $token")
            .header("X-GitHub-Api-Version", API_VERSION)
            .put(json.encodeToString(kotlinx.serialization.json.JsonObject.serializer(), body).toRequestBody(JSON_MEDIA_TYPE))
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw GitHubApiException(response.code)
            val responseBody = response.body.string()
            return try {
                Json.parseToJsonElement(responseBody).jsonObject["content"]?.jsonObject?.get("sha")?.jsonPrimitive?.content
                    ?: throw IllegalArgumentException("Missing returned sha")
            } catch (error: Exception) {
                throw InvalidRemoteJsonException(error)
            }
        }
    }

    private fun contentsUrl(path: String): HttpUrl {
        val builder = apiBaseUrl.newBuilder()
            .addPathSegment("repos")
            .addPathSegment(owner)
            .addPathSegment(repository)
            .addPathSegment("contents")
        path.split('/').filter(String::isNotBlank).forEach(builder::addPathSegment)
        return builder.build()
    }

    companion object {
        private const val ACCEPT = "application/vnd.github+json"
        private const val API_VERSION = "2022-11-28"
        private const val TODO_COMMIT_MESSAGE = "android widget: update todo completion"
        private const val MAX_PUT_ATTEMPTS = 4
        private const val RETRY_DELAY_MS = 600L
        private val CONFLICT_STATUSES = setOf(409, 422)
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private val json = Json { prettyPrint = true; prettyPrintIndent = "  " }

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(java.time.Duration.ofSeconds(10))
            .readTimeout(java.time.Duration.ofSeconds(20))
            .callTimeout(java.time.Duration.ofSeconds(30))
            .build()
    }
}
