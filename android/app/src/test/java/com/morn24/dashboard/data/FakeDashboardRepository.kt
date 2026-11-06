package com.morn24.dashboard.data

import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/**
 * In-memory stand-in for the data repository: applies the same mutation functions and sha
 * bookkeeping as GitHubRepo without any network. [beforeWrite] lets a test simulate another
 * device changing the file between the read and the write (the next write then gets a 409).
 */
class FakeDashboardRepository(initial: Map<DashboardFile, String> = emptyMap()) : DashboardRepository {
    private val raw = initial.toMutableMap()
    private val shas = initial.keys.associateWith { "sha-0" }.toMutableMap()
    private var counter = 0
    val writes = mutableListOf<Pair<DashboardFile, String>>()
    var failWith: Exception? = null
    var beforeWrite: (() -> Unit)? = null
    private val clock = Clock.fixed(Instant.parse("2026-11-07T01:02:03Z"), ZoneOffset.UTC)

    fun text(file: DashboardFile): String = raw.getValue(file)
    fun document(file: DashboardFile): JsonObject = Json.parseToJsonElement(text(file)).jsonObject
    fun setText(file: DashboardFile, text: String) {
        raw[file] = text
        shas[file] = "sha-${++counter}"
    }

    override fun read(file: DashboardFile): RemoteRead {
        val text = raw[file] ?: return RemoteRead.NotFound
        return RemoteRead.Found(Json.parseToJsonElement(text).jsonObject, shas.getValue(file), text)
    }

    override fun mutate(file: DashboardFile, message: String, change: (JsonObject) -> DocChange): MutationResult {
        failWith?.let { throw it }
        repeat(4) { attempt ->
            val remote = read(file) as? RemoteRead.Found ?: throw MissingRemoteFileException(file.path)
            val result = change(remote.document)
            val updated = result.document
            if (updated == null) {
                return MutationResult(remote.document, remote.sha, remote.sha, "", changed = false, before = result.before, afterId = result.afterId)
            }
            val sliced = JsonObject(LinkedHashMap(updated).apply { put("updatedAt", JsonPrimitive(IsoTime.now(clock))) })
            // A write whose sha is stale is rejected like GitHub does (409) and retried against the new file.
            val hook = beforeWrite
            beforeWrite = null
            hook?.invoke()
            if (shas.getValue(file) != remote.sha) {
                if (attempt == 3) throw GitHubApiException(409)
                return@repeat
            }
            val text = Json { prettyPrint = true; prettyPrintIndent = "  " }.encodeToString(JsonObject.serializer(), sliced) + "\n"
            raw[file] = text
            val newSha = "sha-${++counter}"
            shas[file] = newSha
            writes += file to message
            return MutationResult(sliced, newSha, remote.sha, IsoTime.now(clock), changed = true, before = result.before, afterId = result.afterId)
        }
        throw GitHubApiException(409)
    }
}
