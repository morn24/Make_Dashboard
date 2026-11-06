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
import kotlinx.serialization.json.contentOrNull
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

/** What a mutation wants to write: a new document, or null when nothing needs to change. */
class DocChange(
    val document: JsonObject?,
    /** The item as it was before the change (toggle/delete), used to build one-shot undo records. */
    val before: JsonObject? = null,
    /** For deletions: the id of the item that followed the deleted one, so undo restores its place. */
    val afterId: String? = null,
)

data class MutationResult(
    val document: JsonObject,
    val sha: String,
    val previousSha: String,
    val updatedAt: String,
    val changed: Boolean = true,
    val before: JsonObject? = null,
    val afterId: String? = null,
)

class GitHubApiException(val statusCode: Int) : IOException("GitHub API request failed with HTTP $statusCode")
class InvalidRemoteJsonException(cause: Throwable) : IOException("GitHub returned invalid JSON", cause)
class MissingRemoteFileException(path: String) : IOException("GitHub file is missing: $path")
class MissingTodoException(todoId: String) : IOException("Todo no longer exists: $todoId")
class UndoConflictException : IOException("Todo completion changed after the action")

class GitHubRepo(
    private val owner: String,
    private val repository: String,
    private val branch: String,
    private val token: String,
    private val client: OkHttpClient = defaultClient(),
    private val apiBaseUrl: HttpUrl = "https://api.github.com/".toHttpUrl(),
    private val clock: Clock = Clock.systemDefaultZone(),
    private val sleeper: (Long) -> Unit = Thread::sleep,
) : DashboardRepository {
    override fun read(file: DashboardFile): RemoteRead = readPath(file.path)

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

    /**
     * Read the newest file, apply [change] to it, then PUT with the sha just read. A 409/422 means
     * somebody else wrote in between: read again and re-apply (never overwrite), up to four times.
     * [change] may throw (e.g. [MissingTodoException]) to abort without writing.
     */
    override fun mutate(file: DashboardFile, message: String, change: (JsonObject) -> DocChange): MutationResult {
        repeat(MAX_PUT_ATTEMPTS) { attempt ->
            val remote = read(file)
            if (remote !is RemoteRead.Found) throw MissingRemoteFileException(file.path)
            val result = change(remote.document)
            val updated = result.document
            if (updated == null) {
                val updatedAt = (remote.document["updatedAt"] as? JsonPrimitive)?.contentOrNull.orEmpty()
                return MutationResult(
                    remote.document, remote.sha, remote.sha, updatedAt,
                    changed = false, before = result.before, afterId = result.afterId,
                )
            }

            val updatedAt = IsoTime.now(clock)
            val updatedDocument = JsonObject(LinkedHashMap(updated).apply { put("updatedAt", JsonPrimitive(updatedAt)) })
            try {
                val newSha = put(file.path, updatedDocument, remote.sha, message)
                return MutationResult(
                    updatedDocument, newSha, remote.sha, updatedAt,
                    changed = true, before = result.before, afterId = result.afterId,
                )
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
