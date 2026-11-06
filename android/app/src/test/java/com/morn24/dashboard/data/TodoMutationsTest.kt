package com.morn24.dashboard.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class TodoMutationsTest {
    private fun doc(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject
    private fun items(doc: JsonObject) = doc["items"]!!.jsonArray.map { it.jsonObject }
    private fun strings(element: kotlinx.serialization.json.JsonElement) = element.jsonArray.map { it.jsonPrimitive.content }

    private val unknownFields = """"gtask":"g-1","gsync":["제목","2026-11-07",null,false],"time":"15:00","createdAt":"2026-11-01T09:00:00+09:00","futureKey":{"a":[1,2]}"""

    @Test
    fun weeklyCompletionWritesHistoryAndKeepsEveryUnknownField() {
        val before = doc("""{"version":1,"future":9,"items":[{"id":"t1","title":"영어","repeat":"weekly","days":[1,3,5],"doneOn":"2026-11-04","history":["2026-11-02","2026-11-04"],$unknownFields}]}""")

        val change = TodoMutations.setCompletion(before, "t1", "2026-11-06", true)

        val item = items(change.document!!)[0]
        assertEquals("2026-11-06", item["doneOn"]!!.jsonPrimitive.content)
        assertEquals(listOf("2026-11-02", "2026-11-04", "2026-11-06"), strings(item["history"]!!))
        assertEquals("g-1", item["gtask"]!!.jsonPrimitive.content)
        assertEquals(before.jsonArray("items")[0].jsonObject["gsync"], item["gsync"])
        assertEquals("15:00", item["time"]!!.jsonPrimitive.content)
        assertEquals(before["items"]!!.jsonArray[0].jsonObject["futureKey"], item["futureKey"])
        assertEquals(JsonPrimitive(9), change.document!!["future"])
        assertEquals(listOf(1, 3, 5), item["days"]!!.jsonArray.map { it.jsonPrimitive.content.toInt() })
        // The original item is kept for the one-shot undo.
        assertEquals("2026-11-04", change.before!!["doneOn"]!!.jsonPrimitive.content)
    }

    @Test
    fun cancellingARepeatingItemRestoresThePreviousCompletionDayAndKeepsTheStreak() {
        val completed = doc("""{"version":1,"items":[{"id":"t1","title":"운동","repeat":"daily","doneOn":"2026-11-07","history":["2026-11-05","2026-11-06","2026-11-07"]}]}""")

        val change = TodoMutations.setCompletion(completed, "t1", "2026-11-07", false)

        val item = items(change.document!!)[0]
        assertEquals("2026-11-06", item["doneOn"]!!.jsonPrimitive.content)
        assertEquals(listOf("2026-11-05", "2026-11-06"), strings(item["history"]!!))
    }

    @Test
    fun historyIsTrimmedToNinetyDaysAndALegacyLastCompletionIsKept() {
        val old = (1..100).joinToString(",") { "\"" + java.time.LocalDate.parse("2026-11-07").minusDays(it.toLong()) + "\"" }
        val longHistory = doc("""{"version":1,"items":[{"id":"t1","title":"x","repeat":"daily","doneOn":"2026-11-06","history":[$old]}]}""")
        val trimmed = items(TodoMutations.setCompletion(longHistory, "t1", "2026-11-07", true).document!!)[0]
        assertEquals(91, strings(trimmed["history"]!!).size) // the 90 days before today plus today
        assertEquals("2026-11-07", strings(trimmed["history"]!!).last())

        val legacy = doc("""{"version":1,"items":[{"id":"t1","title":"x","repeat":"daily","doneOn":"2026-11-05"}]}""")
        val withLegacy = items(TodoMutations.setCompletion(legacy, "t1", "2026-11-07", true).document!!)[0]
        assertEquals(listOf("2026-11-05", "2026-11-07"), strings(withLegacy["history"]!!))
    }

    @Test
    fun oneTimeCompletionSetsAndClearsDoneOnWithoutTouchingHistoryOrDate() {
        val open = doc("""{"version":1,"items":[{"id":"t1","title":"보고서","repeat":"none","date":"2026-11-07","doneOn":null,$unknownFields}]}""")
        val done = TodoMutations.setCompletion(open, "t1", "2026-11-07", true).document!!
        assertEquals("2026-11-07", items(done)[0]["doneOn"]!!.jsonPrimitive.content)
        assertNull(items(done)[0]["history"])
        assertEquals("2026-11-07", items(done)[0]["date"]!!.jsonPrimitive.content)
        val undone = TodoMutations.setCompletion(done, "t1", "2026-11-07", false).document!!
        assertEquals(JsonNull, items(undone)[0]["doneOn"])
    }

    @Test
    fun alreadyInTheRequestedStateWritesNothing() {
        val done = doc("""{"version":1,"items":[{"id":"t1","title":"보고서","repeat":"none","doneOn":"2026-11-07"}]}""")
        assertNull(TodoMutations.setCompletion(done, "t1", "2026-11-07", true).document)
        val dailyDone = doc("""{"version":1,"items":[{"id":"t1","title":"x","repeat":"daily","doneOn":"2026-11-07","history":["2026-11-07"]}]}""")
        assertNull(TodoMutations.setCompletion(dailyDone, "t1", "2026-11-07", true).document)
    }

    @Test
    fun missingTodoIsNeverRecreated() {
        val d = doc("""{"version":1,"items":[{"id":"other","title":"x","repeat":"none","doneOn":null}]}""")
        assertThrows(MissingTodoException::class.java) { TodoMutations.setCompletion(d, "gone", "2026-11-07", true) }
    }

    @Test
    fun restoreCompletionPutsBackTheExactValuesAndRefusesAfterAnotherDeviceChange() {
        val completed = doc("""{"version":1,"items":[{"id":"t1","title":"x","repeat":"daily","doneOn":"2026-11-07","history":["2026-11-05","2026-11-07"]}]}""")
        val restored = TodoMutations.restoreCompletion(completed, "t1", "2026-11-07", "2026-11-07", "2026-11-05", listOf("2026-11-05"), true).document!!
        assertEquals("2026-11-05", items(restored)[0]["doneOn"]!!.jsonPrimitive.content)
        assertEquals(listOf("2026-11-05"), strings(items(restored)[0]["history"]!!))

        val noHistoryBefore = TodoMutations.restoreCompletion(completed, "t1", "2026-11-07", "2026-11-07", null, null, true).document!!
        assertEquals(JsonNull, items(noHistoryBefore)[0]["doneOn"])
        assertFalse(items(noHistoryBefore)[0].containsKey("history"))

        val changedElsewhere = doc("""{"version":1,"items":[{"id":"t1","title":"x","repeat":"daily","doneOn":"2026-11-08","history":["2026-11-08"]}]}""")
        assertThrows(UndoConflictException::class.java) {
            TodoMutations.restoreCompletion(changedElsewhere, "t1", "2026-11-07", "2026-11-07", null, null, true)
        }
    }

    @Test
    fun anUndoRecordFromTheOldAppCancelsWithTheSharedRules() {
        val completed = doc("""{"version":1,"items":[{"id":"t1","title":"x","repeat":"daily","doneOn":"2026-11-07","history":["2026-11-06","2026-11-07"]}]}""")
        val restored = TodoMutations.restoreCompletion(completed, "t1", "2026-11-07", "2026-11-07", null, null, historyCaptured = false).document!!
        assertEquals("2026-11-06", items(restored)[0]["doneOn"]!!.jsonPrimitive.content)
        assertEquals(listOf("2026-11-06"), strings(items(restored)[0]["history"]!!))
    }

    @Test
    fun newItemsGoLastWithTheWebFieldsAndKeepEverythingElse() {
        val before = doc("""{"version":1,"future":1,"items":[{"id":"a","title":"A","repeat":"none","doneOn":null,"gtask":"keep"}]}""")
        val item = TodoMutations.newItem("t_x", "병원", "none", null, "2026-11-08", "15:00", "2026-11-07T10:00:00+09:00")
        val after = TodoMutations.addTodo(before, item).document!!
        assertEquals(listOf("a", "t_x"), items(after).map { it["id"]!!.jsonPrimitive.content })
        assertEquals(listOf("id", "title", "repeat", "doneOn", "createdAt", "date", "time"), items(after)[1].keys.toList())
        assertEquals("keep", items(after)[0]["gtask"]!!.jsonPrimitive.content)
        assertEquals(JsonPrimitive(1), after["future"])
        val weekly = TodoMutations.newItem("t_y", "영어", "weekly", listOf(5, 1, 3), "2026-11-08", null, "now")
        assertEquals(listOf(1, 3, 5), weekly["days"]!!.jsonArray.map { it.jsonPrimitive.content.toInt() })
        assertNull(weekly["date"]) // dates belong to one-time items only
        assertNull(TodoMutations.addTodo(after, item).document) // same id is not added twice
    }

    @Test
    fun editChangesOnlyThePatchedKeysAndNullDeletesAKey() {
        val before = doc("""{"version":1,"items":[{"id":"a","title":"A","repeat":"none","date":"2026-11-07","doneOn":null,"history":["2026-11-01"],$unknownFields}]}""")
        val patch = mapOf<String, kotlinx.serialization.json.JsonElement?>(
            "title" to JsonPrimitive("B"), "repeat" to JsonPrimitive("weekly"),
            "days" to buildJsonArray { add(JsonPrimitive(1)); add(JsonPrimitive(3)) }, "date" to null, "time" to null,
        )
        val after = items(TodoMutations.editTodo(before, "a", patch).document!!)[0]
        assertEquals("B", after["title"]!!.jsonPrimitive.content)
        assertEquals("weekly", after["repeat"]!!.jsonPrimitive.content)
        assertNull(after["date"])
        assertNull(after["time"])
        assertEquals("g-1", after["gtask"]!!.jsonPrimitive.content)
        assertEquals(listOf("2026-11-01"), strings(after["history"]!!))
        assertEquals(JsonNull, after["doneOn"])
        // An identical patch does not write.
        assertNull(TodoMutations.editTodo(before, "a", mapOf("title" to JsonPrimitive("A"))).document)
    }

    @Test
    fun postponeIsADatePatchToTomorrow() {
        val before = doc("""{"version":1,"items":[{"id":"a","title":"A","repeat":"none","date":"2026-11-07","doneOn":null}]}""")
        val after = TodoMutations.editTodo(before, "a", mapOf("date" to JsonPrimitive("2026-11-08"))).document!!
        assertEquals("2026-11-08", items(after)[0]["date"]!!.jsonPrimitive.content)
    }

    @Test
    fun deleteRemembersThePlaceAndRestoreUsesIt() {
        val before = doc("""{"version":1,"items":[{"id":"a","title":"A","repeat":"none","doneOn":null},{"id":"b","title":"B","repeat":"none","doneOn":null,"gtask":"keep"},{"id":"c","title":"C","repeat":"none","doneOn":null}]}""")
        val deleted = TodoMutations.deleteTodo(before, "b")
        assertEquals(listOf("a", "c"), items(deleted.document!!).map { it["id"]!!.jsonPrimitive.content })
        assertEquals("c", deleted.afterId)

        val restored = TodoMutations.restoreDeleted(deleted.document!!, deleted.before!!, deleted.afterId).document!!
        assertEquals(listOf("a", "b", "c"), items(restored).map { it["id"]!!.jsonPrimitive.content })
        assertEquals("keep", items(restored)[1]["gtask"]!!.jsonPrimitive.content)
        // Restoring twice does nothing, and an unknown follower falls back to the end of the list.
        assertNull(TodoMutations.restoreDeleted(restored, deleted.before!!, "c").document)
        val tail = TodoMutations.restoreDeleted(deleted.document!!, deleted.before!!, "zzz").document!!
        assertEquals(listOf("a", "c", "b"), items(tail).map { it["id"]!!.jsonPrimitive.content })
        // The last item has no follower.
        assertNull(TodoMutations.deleteTodo(before, "c").afterId)
    }

    @Test
    fun projectCheckAddsAndRemovesOnlyTheCheckedEntryAndDropsTheEmptyKey() {
        val before = doc("""{"version":1,"projects":[{"id":"p1","name":"P1","status":"active","progress":10,"next":["하나","둘"],"futureKey":true,"updatedAt":"2026-11-01T00:00:00+09:00"},{"id":"p2","name":"P2","status":"active","progress":20,"next":["셋"],"checked":[{"text":"셋","at":"2026-11-02T00:00:00+09:00"}]}],"future":3}""")

        val checked = ProjectMutations.setNextChecked(before, "p1", "하나", true, "2026-11-07T10:00:00+09:00")
        val p1 = checked.document!!["projects"]!!.jsonArray[0].jsonObject
        assertEquals("하나", p1["checked"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content)
        assertEquals("2026-11-07T10:00:00+09:00", p1["checked"]!!.jsonArray[0].jsonObject["at"]!!.jsonPrimitive.content)
        // next, other fields, the project own updatedAt and the other project are untouched.
        assertEquals(before["projects"]!!.jsonArray[0].jsonObject["next"], p1["next"])
        assertEquals(JsonPrimitive(true), p1["futureKey"])
        assertEquals("2026-11-01T00:00:00+09:00", p1["updatedAt"]!!.jsonPrimitive.content)
        assertEquals(before["projects"]!!.jsonArray[1], checked.document!!["projects"]!!.jsonArray[1])
        assertEquals(JsonPrimitive(3), checked.document!!["future"])

        val unchecked = ProjectMutations.setNextChecked(checked.document!!, "p1", "하나", false, "now").document!!
        assertFalse(unchecked["projects"]!!.jsonArray[0].jsonObject.containsKey("checked"))

        val other = ProjectMutations.setNextChecked(before, "p2", "셋", false, "now").document!!
        assertFalse(other["projects"]!!.jsonArray[1].jsonObject.containsKey("checked"))

        assertTrue(ProjectMutations.setNextChecked(before, "p1", "하나", false, "now").document == null)
        assertThrows(MissingProjectException::class.java) { ProjectMutations.setNextChecked(before, "zz", "a", true, "now") }
    }

    private fun JsonObject.jsonArray(key: String): JsonArray = this[key]!!.jsonArray
}
