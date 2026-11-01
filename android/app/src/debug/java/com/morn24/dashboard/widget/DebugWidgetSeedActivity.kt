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
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                var message by remember { mutableStateOf("익명 fixture 데이터만 이 디버깅 기기에 저장합니다.") }
                Column(
                    modifier = Modifier.fillMaxSize().padding(20.dp),
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
                                }.getOrElse { "fixture를 저장하지 못했어요: ${it.javaClass.simpleName}" }
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("익명 일정 색상 fixture 저장") }

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
        val store = DashboardStore.from(this@DebugWidgetSeedActivity)
        val now = Instant.now()
        val nowMillis = now.toEpochMilli()
        val today = LocalDate.now()
        val day = today.toString()
        val zone = ZoneId.systemDefault()
        val formatter = DateTimeFormatter.ISO_OFFSET_DATE_TIME
        val firstEventStart = ZonedDateTime.of(today, java.time.LocalTime.of(9, 30), zone)
        val colors = listOf("#E53935", "#1E88E5", "#43A047", "#4986E7")
        val titles = listOf("예시 빨강 일정", "예시 파랑 일정", "예시 초록 일정", "캘린더 기본색 일정")
        val events = titles.indices.joinToString(",") { index ->
            val start = firstEventStart.plusMinutes(index * 45L)
            val end = start.plusMinutes(30)
            val colorField = "\"color\":\"${colors[index]}\","
            """{"id":"fixture-event-$index","title":"${titles[index]}","calendar":"익명 예시",$colorField"allDay":false,"start":"${start.format(formatter)}","end":"${end.format(formatter)}"}"""
        }
        val todosJson = """{"version":1,"updatedAt":"$now","items":[{"id":"fixture-todo-1","title":"주간 계획 정리","repeat":"daily","doneOn":null},{"id":"fixture-todo-2","title":"자료 검토","repeat":"daily","doneOn":"$day"},{"id":"fixture-todo-3","title":"화면 흐름 점검","repeat":"daily","doneOn":null},{"id":"fixture-todo-4","title":"검증 결과 기록","repeat":"daily","doneOn":null}]}"""
        val projectsJson = """{"version":1,"updatedAt":"$now","projects":[{"id":"fixture-project","name":"예시 프로젝트","status":"active","progress":72,"current":"익명 렌더 fixture","next":["큰 글씨로 보기","홈 화면에서 확인"],"summary":"화면 검증용 예시 데이터"},{"id":"fixture-project-2","name":"두 번째 예시","status":"active","progress":38,"current":"색상 역할 확인","next":["다음 단계 확인"]}]}"""
        val calendarJson = """{"version":1,"updatedAt":"$now","timeZone":"${zone.id}","rangeStart":"$day","rangeEnd":"${today.plusDays(7)}","events":[$events]}"""

        val generation = store.saveSettings(
            DashboardSettings(
                encryptedToken = TokenCipher(AndroidKeystoreAesKeyProvider()).encrypt("debug-invalid-${System.currentTimeMillis()}"),
            ),
        )
        store.saveAppearance(appearance)
        widgetIds().forEach { store.saveAppearance(appearance, it) }
        store.saveSuccessfulFile(DashboardFile.TODOS, todosJson, "fixture-todos", nowMillis)
        store.saveSuccessfulFile(DashboardFile.PROJECTS, projectsJson, "fixture-projects", nowMillis)
        store.saveSuccessfulFile(DashboardFile.CALENDAR, calendarJson, "fixture-calendar", nowMillis)

        if (withUndo) {
            val pending = PendingTodoToggle(
                operationId = "fixture-undo-op",
                todoId = "fixture-todo-1",
                targetDoneOn = day,
                tappedDate = day,
                expectedGeneration = generation,
                createdAtMillis = nowMillis,
                title = "주간 계획 정리",
            )
            check(store.enqueuePendingToggle(pending))
            val completedTodos = todosJson.replace("\"id\":\"fixture-todo-1\",\"title\":\"주간 계획 정리\",\"repeat\":\"daily\",\"doneOn\":null", "\"id\":\"fixture-todo-1\",\"title\":\"주간 계획 정리\",\"repeat\":\"daily\",\"doneOn\":\"$day\"")
            val undo = CompletedTodoUndo(
                recordId = "fixture-undo",
                todoId = "fixture-todo-1",
                title = "주간 계획 정리",
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

        val manager = AppWidgetManager.getInstance(this@DebugWidgetSeedActivity)
        val component = ComponentName(this@DebugWidgetSeedActivity, DashboardWidgetReceiver::class.java)
        val added = manager.isRequestPinAppWidgetSupported && manager.requestPinAppWidget(component, null, null)
        DashboardWidget().updateAll(this@DebugWidgetSeedActivity)
        if (added) "익명 fixture를 저장했어요. 런처에서 위젯 추가 확인을 마치면 표시됩니다." else "익명 fixture를 저장하고 기존 Glance 위젯을 갱신했어요. 홈 화면에서 Dashboard를 추가하세요."
    }

    private fun widgetIds(): List<Int> = runCatching {
        AppWidgetManager.getInstance(this).getAppWidgetIds(ComponentName(this, DashboardWidgetReceiver::class.java)).toList()
    }.getOrDefault(emptyList())
}

private fun WidgetAppearancePaletteName(id: String): String = when (id) {
    "sky-amber-purple" -> "스카이"
    "lavender-mint-peach" -> "라벤더"
    "amber-sky-mauve" -> "앰버"
    else -> "민트"
}
