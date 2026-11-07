package com.morn24.dashboard.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import androidx.glance.appwidget.updateAll
import com.morn24.dashboard.data.AndroidKeystoreAesKeyProvider
import com.morn24.dashboard.data.CompletedTodoUndo
import com.morn24.dashboard.data.DashboardFile
import com.morn24.dashboard.data.DashboardSettings
import com.morn24.dashboard.data.DashboardStore
import com.morn24.dashboard.data.TokenCipher
import com.morn24.dashboard.data.WidgetAppearance
import com.morn24.dashboard.data.AppearanceColorMode
import com.morn24.dashboard.data.PendingTodoToggle
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Anonymous debug fixtures for rendering real Glance widgets on an Android debug device. */
class DebugWidgetSeedActivity : ComponentActivity() {
    private val palettes = listOf(
        "mint-blue-lavender" to "민트",
        "sky-amber-purple" to "스카이",
        "lavender-mint-peach" to "라벤더",
        "amber-sky-mauve" to "앰버",
        "coral-red-sage-cream" to "코랄 레드",
        "apricot-orange-blue-mint" to "살구 오렌지",
        "butter-yellow-sky-plum" to "버터 옐로",
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                var message by remember { mutableStateOf("익명 fixture 데이터만 이 디버깅 기기에 저장합니다.") }
                Column(
                    modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text("위젯 렌더 검증", style = MaterialTheme.typography.headlineSmall)
                    Text(message)
                    Button(
                        onClick = {
                            lifecycleScope.launch {
                                message = "익명 fixture 저장 중…"
                                message = runCatching {
                                    seedAnonymousData(
                                        appearance = appearance(paletteId = palettes.first().first),
                                        withUndo = false,
                                        withError = false,
                                    )
                                }.getOrElse { "fixture를 저장하지 못했어요: ${it.message ?: it.javaClass.simpleName}" }
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("할 일·일정·프로젝트 20개 fixture 저장") }

                    Button(
                        onClick = {
                            lifecycleScope.launch {
                                message = "가짜 서버에서 읽는 중…"
                                message = runCatching { connectToFakeServerAndSync() }
                                    .getOrElse { "가짜 서버 연결에 실패했어요: ${it.message ?: it.javaClass.simpleName}" }
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("가짜 GitHub 서버에서 읽기 (files/debug-api-base.txt 필요)") }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        listOf("2×2" to (150 to 150), "4×2" to (330 to 150), "4×4" to (330 to 420)).forEach { (label, size) ->
                            Button(onClick = { message = setWidgetSizes(size.first, size.second) }, modifier = Modifier.weight(1f)) { Text(label) }
                        }
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        listOf("글씨 100%" to 100, "글씨 150%" to 150).forEach { (label, scale) ->
                            Button(
                                onClick = { lifecycleScope.launch { message = applyAppearance(appearance("mint-blue-lavender", fontScale = scale)) } },
                                modifier = Modifier.weight(1f),
                            ) { Text(label) }
                        }
                    }

                    Text("같은 어두운 배경 · 강조색 조합")
                    palettes.chunked(2).forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                            row.forEach { (id, label) ->
                                Button(
                                    onClick = {
                                        lifecycleScope.launch {
                                            message = applyAppearance(appearance(paletteId = id))
                                        }
                                    },
                                    modifier = Modifier.weight(1f),
                                ) { Text(label) }
                            }
                        }
                    }

                    Button(
                        onClick = {
                            lifecycleScope.launch {
                                message = seedAnonymousData(
                                    appearance = appearance("amber-sky-mauve", fontScale = 150, opacity = 50),
                                    withUndo = true,
                                    withError = true,
                                )
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("큰 글씨 · 부분 실패 · 실행 취소") }

                    Button(
                        onClick = {
                            lifecycleScope.launch {
                                message = runCatching {
                                    DashboardWidget().updateAll(this@DebugWidgetSeedActivity)
                                    "기존 위젯의 Glance 렌더를 다시 요청했어요."
                                }.getOrElse { "위젯 갱신 요청에 실패했어요." }
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("기존 위젯 다시 렌더링") }
                }
            }
        }
    }

    /** Gives every widget one size (dp) so each layout can be captured. */
    private fun setWidgetSizes(width: Int, height: Int): String {
        val manager = AppWidgetManager.getInstance(this)
        val ids = widgetIds()
        ids.forEach { id ->
            val options = android.os.Bundle().apply {
                putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, width)
                putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, width)
                putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, height)
                putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, height)
            }
            manager.updateAppWidgetOptions(id, options)
        }
        lifecycleScope.launch { DashboardWidget().updateAll(this@DebugWidgetSeedActivity) }
        return "위젯 ${ids.size}개를 ${width}x${height}dp로 지정했어요"
    }

    /** Saves an anonymous dummy connection and reads every file from the debug endpoint override. */
    private suspend fun connectToFakeServerAndSync(): String = withContext(Dispatchers.IO) {
        val base = java.io.File(filesDir, "debug-api-base.txt")
        check(base.exists()) { "debug-api-base.txt가 없어 실제 주소로 나갈 수 있어요. 중단합니다." }
        val store = DashboardStore.from(this@DebugWidgetSeedActivity)
        val cipher = TokenCipher(AndroidKeystoreAesKeyProvider())
        store.saveSettings(DashboardSettings(encryptedToken = cipher.encrypt("debug-invalid-${System.currentTimeMillis()}")))
        val snapshot = com.morn24.dashboard.data.DashboardSyncEngine(
            store = store,
            repositoryFactory = com.morn24.dashboard.data.DefaultDashboardRepositoryFactory(),
            tokenCipher = cipher,
        ).load()
        val manager = AppWidgetManager.getInstance(this@DebugWidgetSeedActivity)
        val component = ComponentName(this@DebugWidgetSeedActivity, DashboardWidgetReceiver::class.java)
        if (widgetIds().size < 3 && manager.isRequestPinAppWidgetSupported) manager.requestPinAppWidget(component, null, null)
        DashboardWidget().updateAll(this@DebugWidgetSeedActivity)
        "서버 ${base.readText().trim()} · 할 일 ${snapshot.todos.size} · 프로젝트 ${snapshot.projects.size} · 일정 ${snapshot.calendarEvents.size} · PMR ${snapshot.pmr?.let { "결정 ${it.decisionsNeed}/실패 ${it.failedJobs}" } ?: "없음"} · 오류 ${snapshot.errors.size}"
    }

    private fun appearance(paletteId: String, fontScale: Int = 100, opacity: Int = 100) = WidgetAppearance(
        colorMode = AppearanceColorMode.DARK,
        accentPaletteId = paletteId,
        fontScalePercent = fontScale,
        backgroundOpacityPercent = opacity,
        contrastAssist = true,
    )

    private suspend fun applyAppearance(appearance: WidgetAppearance): String = withContext(Dispatchers.IO) {
        val store = DashboardStore.from(this@DebugWidgetSeedActivity)
        store.saveAppearance(appearance)
        widgetIds().forEach { store.saveAppearance(appearance, it) }
        DashboardWidget().updateAll(this@DebugWidgetSeedActivity)
        "${WidgetAppearancePaletteName(appearance.accentPaletteId)} 조합으로 다시 그렸어요."
    }

    private suspend fun seedAnonymousData(
        appearance: WidgetAppearance,
        withUndo: Boolean,
        withError: Boolean,
    ): String = withContext(Dispatchers.IO) {
        var stage = "fixture 구성"
        try {
        val store = DashboardStore.from(this@DebugWidgetSeedActivity)
        val now = Instant.now()
        val nowMillis = now.toEpochMilli()
        val today = LocalDate.now()
        val day = today.toString()
        val zone = ZoneId.systemDefault()
        val formatter = DateTimeFormatter.ISO_OFFSET_DATE_TIME
        val firstEventStart = ZonedDateTime.of(today, java.time.LocalTime.of(9, 30), zone)
        val colors = listOf("#E53935", "#1E88E5", "#43A047", "#4986E7")
        stage = "20개 일정 fixture 생성"
        val events = (1..20).joinToString(",") { number ->
            val index = number - 1
            val suffix = number.toString().padStart(2, '0')
            val color = colors[index % colors.size]
            val start = firstEventStart.plusMinutes(index * 45L)
            val end = start.plusMinutes(30)
            val colorField = "\"color\":\"$color\","
            """{"id":"fixture-event-$suffix","title":"일정 EVT-$suffix","calendar":"익명 fixture",$colorField"allDay":false,"start":"${start.format(formatter)}","end":"${end.format(formatter)}"}"""
        }
        stage = "20개 할 일 fixture 생성"
        val todoItems = (1..20).joinToString(",") { number ->
            val suffix = number.toString().padStart(2, '0')
            """{"id":"fixture-todo-$suffix","title":"할 일 TODO-$suffix","repeat":"daily","doneOn":null}"""
        }
        val todosJson = """{"version":1,"updatedAt":"$now","items":[$todoItems]}"""
        stage = "20개 프로젝트 fixture 생성"
        val projectItems = (1..20).joinToString(",") { number ->
            val suffix = number.toString().padStart(2, '0')
            val progress = (number * 7) % 101
            """{"id":"fixture-project-$suffix","name":"프로젝트 PRJ-$suffix","status":"active","progress":$progress,"current":"익명 목록 fixture","next":["fixture step $suffix"]}"""
        }
        val projectsJson = """{"version":1,"updatedAt":"$now","projects":[$projectItems]}"""
        stage = "공유 calendar.json fixture 생성"
        val calendarJson = """{"version":1,"updatedAt":"$now","timeZone":"${zone.id}","rangeStart":"$day","rangeEnd":"${today.plusDays(7)}","events":[$events]}"""

        stage = "익명 연결 설정 저장"
        val generation = store.saveSettings(
            DashboardSettings(
                encryptedToken = TokenCipher(AndroidKeystoreAesKeyProvider()).encrypt("debug-invalid-${System.currentTimeMillis()}"),
            ),
        )
        stage = "표시 모양 저장"
        store.saveAppearance(appearance)
        widgetIds().forEach { store.saveAppearance(appearance, it) }
        stage = "할 일 fixture 저장"
        store.saveSuccessfulFile(DashboardFile.TODOS, todosJson, "fixture-todos", nowMillis)
        stage = "프로젝트 fixture 저장"
        store.saveSuccessfulFile(DashboardFile.PROJECTS, projectsJson, "fixture-projects", nowMillis)
        stage = "일정 fixture 저장"
        store.saveSuccessfulFile(DashboardFile.CALENDAR, calendarJson, "fixture-calendar", nowMillis)

        if (withUndo) {
            val pending = PendingTodoToggle(
                operationId = "fixture-undo-op",
                todoId = "fixture-todo-01",
                targetDoneOn = day,
                tappedDate = day,
                expectedGeneration = generation,
                createdAtMillis = nowMillis,
                title = "할 일 TODO-01",
            )
            check(store.enqueuePendingToggle(pending))
            val completedTodos = todosJson.replace(
                "\"id\":\"fixture-todo-01\",\"title\":\"할 일 TODO-01\",\"repeat\":\"daily\",\"doneOn\":null",
                "\"id\":\"fixture-todo-01\",\"title\":\"할 일 TODO-01\",\"repeat\":\"daily\",\"doneOn\":\"$day\"",
            )
            val undo = CompletedTodoUndo(
                recordId = "fixture-undo",
                todoId = "fixture-todo-01",
                title = "할 일 TODO-01",
                originalDoneOn = null,
                completedDoneOn = day,
                completedDate = day,
                generation = generation,
                completedAtMillis = nowMillis,
            )
            check(store.completePendingToggle(pending, completedTodos, "fixture-todos-undo", "fixture-todos", nowMillis, undo))
        }
        if (withError) {
            store.recordFileErrorIfGeneration(DashboardFile.CALENDAR, "익명 검증 fixture 오류", generation)
            store.recordSyncResultIfGeneration(nowMillis, hadErrors = true, expectedGeneration = generation)
        }

        stage = "런처 위젯 고정 요청"
        val manager = AppWidgetManager.getInstance(this@DebugWidgetSeedActivity)
        val component = ComponentName(this@DebugWidgetSeedActivity, DashboardWidgetReceiver::class.java)
        val added = manager.isRequestPinAppWidgetSupported && manager.requestPinAppWidget(component, null, null)
        stage = "기존 위젯 다시 그리기"
        DashboardWidget().updateAll(this@DebugWidgetSeedActivity)
        if (added) "익명 fixture를 저장했어요. 런처에서 위젯 추가 확인을 마치면 표시됩니다." else "익명 fixture를 저장하고 기존 Glance 위젯을 갱신했어요. 홈 화면에서 Dashboard를 추가하세요."
        } catch (error: Exception) {
            val origin = error.stackTrace.firstOrNull()
            val location = origin?.let { " · ${it.className.substringAfterLast('.')}#${it.methodName}:${it.lineNumber}" }.orEmpty()
            throw IllegalStateException("$stage · ${error.javaClass.simpleName}$location", error)
        }
    }

    private fun widgetIds(): List<Int> = runCatching {
        AppWidgetManager.getInstance(this).getAppWidgetIds(ComponentName(this, DashboardWidgetReceiver::class.java)).toList()
    }.getOrDefault(emptyList())
}

private fun WidgetAppearancePaletteName(id: String): String = when (id) {
    "sky-amber-purple" -> "스카이"
    "lavender-mint-peach" -> "라벤더"
    "amber-sky-mauve" -> "앰버"
        "coral-red-sage-cream" -> "코랄 레드"
        "apricot-orange-blue-mint" -> "살구 오렌지"
        "butter-yellow-sky-plum" -> "버터 옐로"
    else -> "민트"
}
