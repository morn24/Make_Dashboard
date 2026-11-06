package com.morn24.dashboard.data

import com.morn24.dashboard.domain.Todo
import com.morn24.dashboard.domain.TodoRules
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class MissingProjectException(projectId: String) : java.io.IOException("Project no longer exists: $projectId")

/**
 * Pure document edits for `todos.json`. Each function copies the document, changes only what its
 * operation owns, and keeps every other key (gtask, gsync, future fields...) exactly as read.
 * The rules match web/app.js (toggleTodo, addTodo, updateTodo, deleteTodo).
 */
object TodoMutations {
    /** Complete or un-complete a todo for [today] (same maths as the web toggleTodo). */
    fun setCompletion(doc: JsonObject, todoId: String, today: String, makeDone: Boolean): DocChange {
        val (items, index) = locate(doc, todoId)
        val original = items[index].jsonObjectOrFail()
        val updated = original.withCompletion(TodoRules.applyCompletion(todoOf(original), today, makeDone))
        if (updated == original) return DocChange(null, before = original)
        return DocChange(replaceItem(doc, items, index, updated), before = original)
    }

    /**
     * Undo of a completion. Refuses (UndoConflictException) when the item `doneOn` is no longer the
     * value the widget wrote, i.e. another device changed it since. Restores the exact `doneOn` and
     * `history` captured before the completion when available.
     */
    fun restoreCompletion(
        doc: JsonObject,
        todoId: String,
        today: String,
        expectedDoneOn: String?,
        originalDoneOn: String?,
        originalHistory: List<String>?,
        historyCaptured: Boolean,
    ): DocChange {
        val (items, index) = locate(doc, todoId)
        val original = items[index].jsonObjectOrFail()
        val currentDoneOn = original.doneOnOrFail()
        if (currentDoneOn != expectedDoneOn) throw UndoConflictException()
        val updated = if (historyCaptured) {
            JsonObject(LinkedHashMap(original).apply {
                put("doneOn", originalDoneOn?.let(::JsonPrimitive) ?: JsonNull)
                if (originalHistory == null) remove("history") else put("history", originalHistory.toJsonArray())
            })
        } else {
            // Undo record written by an older app version: cancel the completion with the shared rules.
            original.withCompletion(TodoRules.applyCompletion(todoOf(original), today, false))
        }
        if (updated == original) return DocChange(null, before = original)
        return DocChange(replaceItem(doc, items, index, updated), before = original)
    }

    /** Append a new item at the end of the list (new todos always go last). */
    fun addTodo(doc: JsonObject, item: JsonObject): DocChange {
        val items = itemsOf(doc)
        val id = (item["id"] as? JsonPrimitive)?.content
        if (id != null && items.any { idOf(it) == id }) return DocChange(null)
        return DocChange(JsonObject(LinkedHashMap(doc).apply { put("items", JsonArray(items + item)) }))
    }

    /**
     * Apply a patch like the web updateTodo: a null value deletes the key, anything else sets it.
     * Keys that are not in the patch (gtask, gsync, history, doneOn...) are never touched.
     */
    fun editTodo(doc: JsonObject, todoId: String, patch: Map<String, JsonElement?>): DocChange {
        val (items, index) = locate(doc, todoId)
        val original = items[index].jsonObjectOrFail()
        val updated = JsonObject(LinkedHashMap(original).apply {
            for ((key, value) in patch) if (value == null) remove(key) else put(key, value)
        })
        if (updated == original) return DocChange(null, before = original)
        return DocChange(replaceItem(doc, items, index, updated), before = original)
    }

    /** Remove an item and remember which item followed it (undo puts it back in front of that one). */
    fun deleteTodo(doc: JsonObject, todoId: String): DocChange {
        val (items, index) = locate(doc, todoId)
        val removed = items[index].jsonObjectOrFail()
        val afterId = items.getOrNull(index + 1)?.let(::idOf)
        val rest = items.filterIndexed { i, _ -> i != index }
        return DocChange(
            JsonObject(LinkedHashMap(doc).apply { put("items", JsonArray(rest)) }),
            before = removed,
            afterId = afterId,
        )
    }

    /** Undo of [deleteTodo]. Does nothing if an item with that id exists again. */
    fun restoreDeleted(doc: JsonObject, item: JsonObject, afterId: String?): DocChange {
        val items = itemsOf(doc)
        val id = (item["id"] as? JsonPrimitive)?.content
        if (items.any { idOf(it) == id }) return DocChange(null)
        val to = afterId?.let { after -> items.indexOfFirst { idOf(it) == after } } ?: -1
        val list = items.toMutableList()
        if (to < 0) list.add(item) else list.add(to, item)
        return DocChange(JsonObject(LinkedHashMap(doc).apply { put("items", JsonArray(list)) }))
    }

    /** Builds a fresh todos.json item like the web addTodo (same key order, date/time only if set). */
    fun newItem(
        id: String,
        title: String,
        repeat: String,
        days: List<Int>?,
        date: String?,
        time: String?,
        createdAt: String,
    ): JsonObject = buildJsonObject {
        put("id", id)
        put("title", title)
        put("repeat", repeat)
        if (repeat == "weekly" && !days.isNullOrEmpty()) put("days", JsonArray(days.sorted().map(::JsonPrimitive)))
        put("doneOn", JsonNull)
        put("createdAt", createdAt)
        if (repeat == "none" && !date.isNullOrEmpty()) put("date", date)
        if (!time.isNullOrEmpty()) put("time", time)
    }

    // ---- helpers ----

    private fun itemsOf(doc: JsonObject): JsonArray =
        doc["items"] as? JsonArray ?: throw InvalidRemoteJsonException(IllegalStateException("todos.items is not an array"))

    private fun idOf(element: JsonElement): String? = ((element as? JsonObject)?.get("id") as? JsonPrimitive)?.content

    private fun locate(doc: JsonObject, todoId: String): Pair<JsonArray, Int> {
        val items = itemsOf(doc)
        val index = items.indexOfFirst { idOf(it) == todoId }
        if (index < 0) throw MissingTodoException(todoId)
        return items to index
    }

    private fun replaceItem(doc: JsonObject, items: JsonArray, index: Int, item: JsonObject): JsonObject =
        JsonObject(LinkedHashMap(doc).apply {
            put("items", JsonArray(items.mapIndexed { i, old -> if (i == index) item else old }))
        })

    private fun JsonElement.jsonObjectOrFail(): JsonObject =
        this as? JsonObject ?: throw InvalidRemoteJsonException(IllegalStateException("todos.items entry is not an object"))

    private fun JsonObject.doneOnOrFail(): String? = when (val value = this["doneOn"]) {
        null, JsonNull -> null
        is JsonPrimitive -> value.content.takeIf { value.isString }
            ?: throw InvalidRemoteJsonException(IllegalArgumentException("todos.doneOn is not a string or null"))
        else -> throw InvalidRemoteJsonException(IllegalArgumentException("todos.doneOn is not a string or null"))
    }

    /** Lenient view of an item for the shared rules (a missing title or repeat must not block a write). */
    private fun todoOf(item: JsonObject): Todo = Todo(
        id = (item["id"] as? JsonPrimitive)?.content.orEmpty(),
        title = (item["title"] as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content.orEmpty(),
        repeat = (item["repeat"] as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content ?: "none",
        doneOn = item.doneOnOrFail(),
        days = (item["days"] as? JsonArray)?.mapNotNull {
            (it as? JsonPrimitive)?.takeUnless(JsonPrimitive::isString)?.content?.toIntOrNull()
        }.orEmpty(),
        date = (item["date"] as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content,
        time = (item["time"] as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content,
        history = (item["history"] as? JsonArray)?.mapNotNull {
            (it as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content
        },
    )

    /** Copy with only `doneOn` and, for repeating items, `history` taken from [result]. */
    private fun JsonObject.withCompletion(result: Todo): JsonObject =
        JsonObject(LinkedHashMap(this).apply {
            put("doneOn", result.doneOn?.let(::JsonPrimitive) ?: JsonNull)
            val history = result.history
            if (TodoRules.isRepeating(result) && history != null) put("history", history.toJsonArray())
        })

    private fun List<String>.toJsonArray(): JsonArray = JsonArray(map(::JsonPrimitive))
}

/** Pure document edits for `projects.json`. */
object ProjectMutations {
    /**
     * Same rule as web toggleNext: only `checked` changes (a `{text, at}` entry is added or
     * removed); the key is deleted when the list becomes empty. `next` and other projects stay as is.
     */
    fun setNextChecked(doc: JsonObject, projectId: String, text: String, done: Boolean, nowIso: String): DocChange {
        val projects = doc["projects"] as? JsonArray
            ?: throw InvalidRemoteJsonException(IllegalStateException("projects.projects is not an array"))
        val index = projects.indexOfFirst { ((it as? JsonObject)?.get("id") as? JsonPrimitive)?.content == projectId }
        if (index < 0) throw MissingProjectException(projectId)
        val project = projects[index] as? JsonObject
            ?: throw InvalidRemoteJsonException(IllegalStateException("projects entry is not an object"))
        val rest = (project["checked"] as? JsonArray).orEmpty().filter { entry ->
            entry is JsonObject && (entry["text"] as? JsonPrimitive)?.content != text
        }
        val checked = if (done) rest + buildJsonObject { put("text", text); put("at", nowIso) } else rest
        val updated = JsonObject(LinkedHashMap(project).apply {
            if (checked.isEmpty()) remove("checked") else put("checked", JsonArray(checked))
        })
        if (updated == project) return DocChange(null, before = project)
        return DocChange(
            JsonObject(LinkedHashMap(doc).apply {
                put("projects", JsonArray(projects.mapIndexed { i, old -> if (i == index) updated else old }))
            }),
            before = project,
        )
    }
}
