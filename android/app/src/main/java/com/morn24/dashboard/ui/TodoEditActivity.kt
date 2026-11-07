package com.morn24.dashboard.ui

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.glance.appwidget.updateAll
import com.morn24.dashboard.data.AndroidKeystoreAesKeyProvider
import com.morn24.dashboard.data.DashboardDisplayData
import com.morn24.dashboard.data.DashboardFile
import com.morn24.dashboard.data.DashboardStore
import com.morn24.dashboard.data.DashboardWriter
import com.morn24.dashboard.data.DefaultDashboardRepositoryFactory
import com.morn24.dashboard.data.IsoTime
import com.morn24.dashboard.data.TodoMutations
import com.morn24.dashboard.data.TokenCipher
import com.morn24.dashboard.data.WriteOutcome
import com.morn24.dashboard.domain.RepeatChoice
import com.morn24.dashboard.domain.RepeatKind
import com.morn24.dashboard.domain.Todo
import com.morn24.dashboard.domain.TodoInputParser
import com.morn24.dashboard.domain.TodoRules
import com.morn24.dashboard.widget.DashboardWidget
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/**
 * One transparent dialog for the widget "+" (add) and for tapping a todo title (edit). Input is
 * kept when a save fails so it can be retried; on success the cache is already updated and the
 * widget is redrawn right away.
 */
class TodoEditActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val todoId = intent.getStringExtra(EXTRA_TODO_ID)
        setContent { TodoEditRoot(todoId = todoId, onFinish = { finish() }) }
    }

    companion object {
        const val EXTRA_TODO_ID = "todoId"

        // Distinct action/data keep each widget PendingIntent separate (extras are not part of equality).
        fun addIntent(context: Context): Intent = Intent(context, TodoEditActivity::class.java)
            .setAction("com.morn24.dashboard.ADD_TODO")
            .setData(Uri.parse("dashboard://todo/add"))

        fun editIntent(context: Context, todoId: String): Intent = Intent(context, TodoEditActivity::class.java)
            .setAction("com.morn24.dashboard.EDIT_TODO")
            .setData(Uri.parse("dashboard://todo/edit/" + Uri.encode(todoId)))
            .putExtra(EXTRA_TODO_ID, todoId)
    }
}

internal fun dashboardColorScheme(context: Context, dark: Boolean): ColorScheme = when {
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && dark -> dynamicDarkColorScheme(context)
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> dynamicLightColorScheme(context)
    dark -> darkColorScheme()
    else -> lightColorScheme()
}

private sealed interface EditLoad {
    data object Loading : EditLoad
    data object Add : EditLoad
    data object Missing : EditLoad
    data class Ready(val todo: Todo, val linkedToGoogle: Boolean) : EditLoad
}

private val DAY_NAMES = listOf("일", "월", "화", "수", "목", "금", "토")

@Composable
private fun TodoEditRoot(todoId: String?, onFinish: () -> Unit) {
    val context = LocalContext.current
    val colors = dashboardColorScheme(context, isSystemInDarkTheme())
    var load by remember { mutableStateOf<EditLoad>(EditLoad.Loading) }
    LaunchedEffect(todoId) {
        load = if (todoId == null) EditLoad.Add else {
            val raw = DashboardStore.from(context).cached(DashboardFile.TODOS).rawJson
            val item = runCatching {
                (Json.parseToJsonElement(raw.orEmpty()) as JsonObject)["items"]
                    ?.let { it as JsonArray }
                    ?.map { it.jsonObject }
                    ?.firstOrNull { (it["id"] as? JsonPrimitive)?.content == todoId }
            }.getOrNull()
            if (item == null) EditLoad.Missing
            else EditLoad.Ready(DashboardDisplayData.todo(item), linkedToGoogle = item["gtask"] != null)
        }
    }
    MaterialTheme(colorScheme = colors) {
        when (val state = load) {
            EditLoad.Loading -> Unit
            EditLoad.Missing -> AlertDialog(
                onDismissRequest = onFinish,
                title = { Text("할 일을 찾지 못했어요") },
                text = { Text("위젯을 새로고침한 뒤 다시 눌러 주세요.") },
                confirmButton = { TextButton(onClick = onFinish) { Text("닫기") } },
            )
            EditLoad.Add -> TodoEditDialog(existing = null, linkedToGoogle = false, onFinish = onFinish)
            is EditLoad.Ready -> TodoEditDialog(existing = state.todo, linkedToGoogle = state.linkedToGoogle, onFinish = onFinish)
        }
    }
}

@Composable
private fun TodoEditDialog(existing: Todo?, linkedToGoogle: Boolean, onFinish: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val today = remember { LocalDate.now().toString() }
    val isAdd = existing == null

    var text by remember { mutableStateOf(existing?.title.orEmpty()) }
    var kind by remember { mutableStateOf(existing?.let(RepeatChoice::kindOf) ?: RepeatKind.NONE) }
    var customDays by remember { mutableStateOf(if (existing != null && kind == RepeatKind.CUSTOM) existing.days.toSet() else emptySet()) }
    var date by remember { mutableStateOf(existing?.date) }
    var time by remember { mutableStateOf(existing?.time) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    val writer = remember {
        DashboardWriter(
            store = DashboardStore.from(context),
            repositoryFactory = DefaultDashboardRepositoryFactory(),
            tokenCipher = TokenCipher(AndroidKeystoreAesKeyProvider()),
        )
    }
    val lit = RepeatChoice.selectedDays(kind, customDays)
    val parsed = if (isAdd && text.isNotBlank()) TodoInputParser.parse(text, LocalDateTime.now()) else null

    fun finishSaved(toast: String) {
        scope.launch {
            runCatching { DashboardWidget().updateAll(context) }
            Toast.makeText(context, toast, Toast.LENGTH_SHORT).show()
            onFinish()
        }
    }

    fun handle(outcome: WriteOutcome, toast: String) {
        saving = false
        when (outcome) {
            WriteOutcome.Saved -> finishSaved(toast)
            WriteOutcome.StaleConfiguration -> error = "연결 설정이 바뀌어 저장하지 않았어요. 닫고 다시 시도하세요."
            is WriteOutcome.Failed -> error = outcome.message
        }
    }

    fun save() {
        if (saving) return
        val fields = RepeatChoice.fields(kind, customDays)
        if (isAdd) {
            val input = text.trim()
            if (input.isEmpty()) return
            val p = TodoInputParser.parse(input, LocalDateTime.now())
            val itemDate = if (fields.repeat == "none") (p.date ?: today) else null
            val item = TodoMutations.newItem(
                id = newTodoId(),
                title = p.title,
                repeat = fields.repeat,
                days = fields.days,
                date = itemDate,
                time = p.time,
                createdAt = IsoTime.now(),
            )
            saving = true
            error = null
            val upcoming = itemDate != null && itemDate > today
            scope.launch {
                handle(
                    writer.addTodo(item),
                    if (upcoming) "${TodoRules.fmtDay(itemDate.orEmpty(), today)}${p.time?.let { " $it" }.orEmpty()} 예정으로 추가했어요" else "추가했어요",
                )
            }
        } else {
            val title = text.trim()
            if (title.isEmpty()) return
            val patch: Map<String, JsonElement?> = mapOf(
                "title" to JsonPrimitive(title),
                "repeat" to JsonPrimitive(fields.repeat),
                "days" to fields.days?.let { days -> JsonArray(days.map { JsonPrimitive(it) }) },
                "date" to (if (fields.repeat == "none") date?.let { JsonPrimitive(it) } else null),
                "time" to time?.let { JsonPrimitive(it) },
            )
            saving = true
            error = null
            scope.launch { handle(writer.editTodo(existing!!.id, existing.title, patch), "저장했어요") }
        }
    }

    AlertDialog(
        onDismissRequest = { if (!saving) onFinish() },
        title = { Text(if (isAdd) "할 일 추가" else "할 일 수정") },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it; error = null },
                    label = { Text(if (isAdd) "예: 내일 오후 3시 병원" else "할 일") },
                    singleLine = true,
                    enabled = !saving,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (parsed != null && (parsed.date != null || parsed.time != null)) {
                    Text(
                        "📅 ${parsed.date?.let { TodoRules.fmtDay(it, today) } ?: "오늘"}${parsed.time?.let { " $it · 10분 전 알림" }.orEmpty()} · \"${parsed.title}\"",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }

                Text("반복", style = MaterialTheme.typography.labelMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(RepeatKind.NONE to "한 번", RepeatKind.DAILY to "매일", RepeatKind.WEEKDAYS to "평일", RepeatKind.WEEKEND to "주말").forEach { (value, label) ->
                        FilterChip(
                            selected = kind == value,
                            onClick = { kind = value; customDays = emptySet() },
                            label = { Text(label) },
                            enabled = !saving,
                        )
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
                    DAY_NAMES.forEachIndexed { day, name ->
                        DayToggle(name, selected = day in lit, enabled = !saving) {
                            val (nextKind, nextDays) = RepeatChoice.toggleDay(kind, customDays, day)
                            kind = nextKind
                            customDays = nextDays
                        }
                    }
                }

                if (!isAdd) {
                    if (kind == RepeatKind.NONE) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            OutlinedButton(
                                onClick = { pickDate(context, date ?: today) { date = it } },
                                enabled = !saving,
                            ) { Text("날짜 · ${date?.let { TodoRules.fmtDay(it, today) } ?: "없음"}") }
                            if (date != null) TextButton(onClick = { date = null }, enabled = !saving) { Text("지움") }
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        OutlinedButton(
                            onClick = { pickTime(context, time) { time = it } },
                            enabled = !saving,
                        ) { Text("시간 · ${time ?: "없음"}") }
                        if (time != null) TextButton(onClick = { time = null }, enabled = !saving) { Text("지움") }
                    }
                    if (linkedToGoogle) {
                        Text("🔗 Google 캘린더 할 일과 연동돼 있어요 (수정·삭제가 함께 반영돼요)", style = MaterialTheme.typography.bodySmall)
                    } else if (TodoRules.isRepeating(existing!!)) {
                        Text("반복 할 일은 Google 캘린더에 올리지 않아요", style = MaterialTheme.typography.bodySmall)
                    }
                    if (!TodoRules.isRepeating(existing!!) && !TodoRules.isDone(existing, today)) {
                        OutlinedButton(
                            onClick = {
                                if (saving) return@OutlinedButton
                                saving = true
                                error = null
                                val tomorrow = TodoRules.addDays(today, 1)
                                scope.launch {
                                    handle(
                                        writer.editTodo(existing.id, existing.title, mapOf("date" to JsonPrimitive(tomorrow)), "내일($tomorrow)로 미룸"),
                                        "내일로 미뤘어요",
                                    )
                                }
                            },
                            enabled = !saving,
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("내일로 미루기") }
                    }
                    TextButton(
                        onClick = {
                            if (saving) return@TextButton
                            saving = true
                            error = null
                            scope.launch { handle(writer.deleteTodo(existing!!.id), "삭제했어요 · 위젯에서 되돌릴 수 있어요") }
                        },
                        enabled = !saving,
                    ) { Text("삭제 (위젯에서 되돌리기)", color = MaterialTheme.colorScheme.error) }
                }
                if (saving) Text("저장 중…", style = MaterialTheme.typography.bodySmall)
                error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = ::save, enabled = !saving && text.isNotBlank()) {
                Text(if (error != null) "다시 시도" else if (isAdd) "추가" else "저장")
            }
        },
        dismissButton = { TextButton(onClick = onFinish, enabled = !saving) { Text("취소") } },
    )
}

@Composable
private fun DayToggle(label: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val container = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
    val content = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
    Box(
        modifier = Modifier.size(38.dp).clip(CircleShape).background(container)
            .clickable(enabled = enabled, onClick = onClick)
            .semantics { contentDescription = "$label 요일 ${if (selected) "선택됨" else "선택 안 됨"}" },
        contentAlignment = Alignment.Center,
    ) { Text(label, color = content, style = MaterialTheme.typography.labelLarge) }
}

private fun newTodoId(): String =
    "t_" + System.currentTimeMillis().toString(36) + (1..4).map { "0123456789abcdefghijklmnopqrstuvwxyz".random() }.joinToString("")

private fun pickDate(context: Context, current: String, onPicked: (String) -> Unit) {
    val day = runCatching { LocalDate.parse(current) }.getOrDefault(LocalDate.now())
    DatePickerDialog(
        context,
        { _, y, m, d -> onPicked(LocalDate.of(y, m + 1, d).toString()) },
        day.year, day.monthValue - 1, day.dayOfMonth,
    ).show()
}

private fun pickTime(context: Context, current: String?, onPicked: (String) -> Unit) {
    val value = runCatching { LocalTime.parse(current) }.getOrDefault(LocalTime.of(9, 0))
    TimePickerDialog(
        context,
        { _, h, m -> onPicked(LocalTime.of(h, m).format(DateTimeFormatter.ofPattern("HH:mm"))) },
        value.hour, value.minute, true,
    ).show()
}
