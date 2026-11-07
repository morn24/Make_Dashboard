package com.morn24.dashboard.data

import com.morn24.dashboard.domain.CalendarEvent
import com.morn24.dashboard.domain.Project
import com.morn24.dashboard.domain.PmrSummary
import com.morn24.dashboard.domain.ProjectCheck
import com.morn24.dashboard.domain.Todo
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

/** Strictly maps the shared JSON contract while leaving the original JsonObject untouched. */
object DashboardDisplayData {
    fun todos(document: JsonObject): List<Todo> {
        requireSupportedVersion(document)
        return document.requiredArray("items").map { element ->
            todo(element as? JsonObject ?: error("todos.items entry is not an object"))
        }
    }

    /** One todos.json item. Unknown `repeat` values behave like "none", as in web/rules.js. */
    fun todo(item: JsonObject): Todo {
        val days = when (val raw = item["days"]) {
            is JsonArray -> raw.mapNotNull { (it as? JsonPrimitive)?.takeUnless(JsonPrimitive::isString)?.intOrNull }
            else -> emptyList()
        }
        val history = (item["history"] as? JsonArray)?.mapNotNull {
            (it as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content
        }
        return Todo(
            id = item.requiredString("id"),
            title = item.requiredString("title"),
            repeat = item.requiredString("repeat"),
            doneOn = item.optionalString("doneOn"),
            days = days,
            date = item.lenientString("date"),
            time = item.lenientString("time"),
            history = history,
        )
    }

    /** `data/pmr-wiki.json`: only what the widget chip needs. Missing fields mean zero. */
    fun pmr(document: JsonObject): PmrSummary {
        val need = ((document["decisions"] as? JsonObject)?.get("need") as? JsonPrimitive)?.intOrNull ?: 0
        val failed = (document["jobs"] as? JsonArray).orEmpty().count { job ->
            ((job as? JsonObject)?.get("state") as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content == "error"
        }
        return PmrSummary(
            generatedAt = document.lenientString("generatedAt"),
            decisionsNeed = need.coerceAtLeast(0),
            failedJobs = failed,
        )
    }

    fun projects(document: JsonObject): List<Project> {
        requireSupportedVersion(document)
        return document.requiredArray("projects").map { element ->
            val item = element as? JsonObject ?: error("projects entry is not an object")
            val checks = when (val raw = item["checked"]) {
                null, JsonNull -> emptyList()
                is JsonArray -> raw.map { check ->
                    val value = check as? JsonObject ?: error("projects.checked entry is not an object")
                    ProjectCheck(text = value.requiredString("text"), at = value.optionalString("at"))
                }
                else -> error("projects.checked is not an array")
            }
            val progress = (item["progress"] as? JsonPrimitive)?.intOrNull
                ?: error("projects.progress is missing or invalid")
            require(progress in 0..100) { "Project progress is outside 0..100" }
            Project(
                id = item.requiredString("id"),
                name = item.requiredString("name"),
                status = item.optionalString("status") ?: "active",
                progress = progress,
                current = item.optionalString("current"),
                next = item.optionalStringArray("next"),
                checked = checks,
                summary = item.optionalString("summary"),
                updatedAt = item.optionalString("updatedAt"),
            )
        }
    }

    fun events(document: JsonObject): List<CalendarEvent> {
        requireSupportedVersion(document)
        return document.requiredArray("events").map { element ->
            val item = element as? JsonObject ?: error("events entry is not an object")
            val allDay = (item["allDay"] as? JsonPrimitive)?.booleanOrNull
                ?: error("events.allDay is missing or invalid")
            CalendarEvent(
                id = item.requiredString("id"),
                title = item.requiredString("title"),
                calendar = item.optionalString("calendar"),
                color = item.optionalString("color"),
                allDay = allDay,
                start = item.requiredString("start"),
                end = item.requiredString("end"),
                location = item.optionalString("location"),
            )
        }
    }

    private fun requireSupportedVersion(document: JsonObject) {
        val version = (document["version"] as? JsonPrimitive)?.intOrNull
            ?: error("document version is missing or invalid")
        require(version == 1) { "Unsupported document version" }
    }

    private fun JsonObject.requiredArray(key: String): JsonArray =
        this[key] as? JsonArray ?: error("$key is missing or is not an array")

    private fun JsonObject.requiredString(key: String): String =
        (this[key] as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content
            ?: error("$key is missing or is not a string")

    private fun JsonObject.optionalString(key: String): String? = when (val value = this[key]) {
        null, JsonNull -> null
        is JsonPrimitive -> value.takeIf(JsonPrimitive::isString)?.content
            ?: error("$key is not a string")
        else -> error("$key is not a string")
    }

    /** A string value, or null when absent / null / not a string (never throws). */
    private fun JsonObject.lenientString(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content

    private fun JsonObject.optionalStringArray(key: String): List<String> = when (val value = this[key]) {
        null, JsonNull -> emptyList()
        is JsonArray -> value.map { element ->
            (element as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content
                ?: error("$key entry is not a string")
        }
        else -> error("$key is not an array")
    }
}
