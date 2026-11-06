package com.morn24.dashboard.domain

import com.morn24.dashboard.data.DashboardDisplayData
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Cross-language check: every expected value in rule-vectors.json was computed by web/rules.js
 * (node scripts/gen_rule_vectors.js). Kotlin must give the same answer for every vector.
 */
class RuleVectorsTest {
    private val vectors: JsonObject = RuleVectorsTest::class.java.getResourceAsStream("/rule-vectors.json")!!
        .bufferedReader(Charsets.UTF_8).use { Json.parseToJsonElement(it.readText()).jsonObject }

    private fun section(name: String): JsonArray = vectors[name]!!.jsonArray
    private fun todo(item: JsonElement): Todo = DashboardDisplayData.todo(item.jsonObject)
    private fun strings(element: JsonElement?): List<String>? = when (element) {
        null, JsonNull -> null
        else -> element.jsonArray.map { it.jsonPrimitive.content }
    }
    private fun nullableString(element: JsonElement?): String? = (element as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content

    @Test
    fun vectorFileIsLargeEnoughToCoverTheRequiredCases() {
        assertTrue(section("rules").size >= 150)
        assertTrue(section("toggles").size >= 300)
        assertTrue(section("parse").size >= 100)
        assertEquals(TodoRules.HISTORY_DAYS, vectors["historyDays"]!!.jsonPrimitive.int)
    }

    @Test
    fun ruleFunctionsMatchRulesJs() {
        for (case in section("rules")) {
            val item = todo(case.jsonObject["item"]!!)
            val today = case.jsonObject["today"]!!.jsonPrimitive.content
            val expect = case.jsonObject["expect"]!!.jsonObject
            val label = "${case.jsonObject["item"]} today=$today"
            assertEquals("isRepeating $label", expect["isRepeating"]!!.jsonPrimitive.boolean, TodoRules.isRepeating(item))
            assertEquals("scheduledOn $label", expect["scheduledOn"]!!.jsonPrimitive.boolean, TodoRules.scheduledOn(item, today))
            assertEquals("isDone $label", expect["isDone"]!!.jsonPrimitive.boolean, TodoRules.isDone(item, today))
            assertEquals("isVisible $label", expect["isVisible"]!!.jsonPrimitive.boolean, TodoRules.isVisible(item, today))
            assertEquals("isUpcoming $label", expect["isUpcoming"]!!.jsonPrimitive.boolean, TodoRules.isUpcoming(item, today))
            assertEquals("isStale $label", expect["isStale"]!!.jsonPrimitive.boolean, TodoRules.isStale(item, today))
            assertEquals("streak $label", expect["streak"]!!.jsonPrimitive.int, TodoRules.streak(item, today))
            assertEquals("repeatLabel $label", expect["repeatLabel"]!!.jsonPrimitive.content, TodoRules.repeatLabel(item))
        }
    }

    @Test
    fun completionAndCancellationMatchTheWebToggle() {
        for (case in section("toggles")) {
            val item = todo(case.jsonObject["item"]!!)
            val today = case.jsonObject["today"]!!.jsonPrimitive.content
            val makeDone = case.jsonObject["makeDone"]!!.jsonPrimitive.boolean
            val expect = case.jsonObject["expect"]!!.jsonObject
            val after = TodoRules.applyCompletion(item, today, makeDone)
            val label = "${case.jsonObject["item"]} today=$today makeDone=$makeDone"
            assertEquals("doneOn $label", nullableString(expect["doneOn"]), after.doneOn)
            // A one-time item never gets a history; the web only writes it for repeating items.
            val expectedHistory = if (TodoRules.isRepeating(item)) strings(expect["history"]) else item.history
            assertEquals("history $label", expectedHistory, after.history)
        }
    }

    @Test
    fun completingThenCancellingMatchesTheWebRoundTrip() {
        for (case in section("roundTrips")) {
            val item = todo(case.jsonObject["item"]!!)
            val today = case.jsonObject["today"]!!.jsonPrimitive.content
            val expect = case.jsonObject["expect"]!!.jsonObject
            val done = TodoRules.applyCompletion(item, today, true)
            val undone = TodoRules.applyCompletion(done, today, false)
            val label = "${case.jsonObject["item"]} today=$today"
            assertEquals("doneOn $label", nullableString(expect["doneOn"]), undone.doneOn)
            val expectedHistory = if (TodoRules.isRepeating(item)) strings(expect["history"]) else item.history
            assertEquals("history $label", expectedHistory, undone.history)
        }
    }

    @Test
    fun dayLabelsMatchRulesJs() {
        for (case in section("fmtDays")) {
            val day = case.jsonObject["day"]!!.jsonPrimitive.content
            val today = case.jsonObject["today"]!!.jsonPrimitive.content
            assertEquals("$day / $today", case.jsonObject["expect"]!!.jsonPrimitive.content, TodoRules.fmtDay(day, today))
        }
    }

    @Test
    fun inputSentencesMatchParseTodoInput() {
        for (case in section("parse")) {
            val text = case.jsonObject["text"]!!.jsonPrimitive.content
            val n = case.jsonObject["now"]!!.jsonArray.map { it.jsonPrimitive.int }
            val now = java.time.LocalDateTime.of(n[0], n[1], n[2], n[3], n[4])
            val expect = case.jsonObject["expect"]!!.jsonObject
            val parsed = TodoInputParser.parse(text, now)
            val label = "'$text' now=$n"
            assertEquals("title $label", expect["title"]!!.jsonPrimitive.content, parsed.title)
            assertEquals("date $label", nullableString(expect["date"]), parsed.date)
            assertEquals("time $label", nullableString(expect["time"]), parsed.time)
        }
    }
}
