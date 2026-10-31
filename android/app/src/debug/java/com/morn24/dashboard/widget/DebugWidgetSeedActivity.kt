package com.morn24.dashboard.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.glance.appwidget.updateAll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import com.morn24.dashboard.data.AndroidKeystoreAesKeyProvider
import com.morn24.dashboard.data.DashboardFile
import com.morn24.dashboard.data.DashboardSettings
import com.morn24.dashboard.data.DashboardStore
import com.morn24.dashboard.data.TokenCipher
import java.time.Instant
import java.time.LocalDate
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Debug-only anonymous fixtures for checking a real Glance widget on an emulator. */
class DebugWidgetSeedActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                var message by remember { mutableStateOf("샘플 데이터는 이 가상 기기에만 저장됩니다.") }
                Column(
                    modifier = Modifier.fillMaxSize().padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Text("위젯 렌더 검증", style = MaterialTheme.typography.headlineSmall)
                    Text(message)
                    Button(
                        onClick = {
                            lifecycleScope.launch {
                                message = "샘플 데이터 저장 중…"
                                try {
                                    seedAnonymousData()
                                    val manager = AppWidgetManager.getInstance(this@DebugWidgetSeedActivity)
                                    val component = ComponentName(this@DebugWidgetSeedActivity, DashboardWidgetReceiver::class.java)
                                    message = if (manager.isRequestPinAppWidgetSupported && manager.requestPinAppWidget(component, null, null)) {
                                        "샘플을 저장했어요. 런처의 추가 확인을 마치면 위젯이 표시됩니다."
                                    } else {
                                        "샘플을 저장했어요. 홈 화면에서 Dashboard 위젯을 추가하세요."
                                    }
                                } catch (_: Exception) {
                                    message = "샘플을 저장하지 못했어요. 다시 시도하세요."
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("샘플 저장 후 위젯 추가") }
                    Button(
                        onClick = {
                            lifecycleScope.launch {
                                message = try {
                                    DashboardWidget().updateAll(this@DebugWidgetSeedActivity)
                                    "기존 위젯의 Glance 세션을 다시 요청했어요."
                                } catch (_: Exception) {
                                    "위젯 갱신을 요청하지 못했어요."
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("기존 위젯 다시 렌더링") }
                }
            }
        }
    }

    private suspend fun seedAnonymousData() = withContext(Dispatchers.IO) {
        val now = Instant.now()
        val today = LocalDate.now().toString()
        val dateTime = DateTimeFormatter.ISO_OFFSET_DATE_TIME
        val start = ZonedDateTime.now().plusMinutes(20)
        val later = ZonedDateTime.now().plusHours(2)
        val store = DashboardStore.from(this@DebugWidgetSeedActivity)
        val cipher = TokenCipher(AndroidKeystoreAesKeyProvider())
        store.saveSettings(store.settings().copy(encryptedToken = cipher.encrypt("debug-only-invalid-token")))
        store.saveSuccessfulFile(
            DashboardFile.TODOS,
            """{"version":1,"updatedAt":"$now","items":[{"id":"demo-1","title":"주간 계획 정리","repeat":"daily","doneOn":null},{"id":"demo-2","title":"자료 검토","repeat":"daily","doneOn":"$today"},{"id":"demo-3","title":"화면 흐름 점검","repeat":"daily","doneOn":null},{"id":"demo-4","title":"회의 안건 준비","repeat":"daily","doneOn":null},{"id":"demo-5","title":"검증 결과 기록","repeat":"daily","doneOn":null}]}""",
            "demo-todos",
            now.toEpochMilli(),
        )
        store.saveSuccessfulFile(
            DashboardFile.PROJECTS,
            """{"version":1,"projects":[{"id":"demo-dashboard","name":"Dashboard 위젯","status":"active","progress":72,"current":"반응형 표시와 설정을 점검합니다.","next":["큰 글씨로 레이아웃 확인","갤럭시에서 실제 추가"],"summary":"익명 에뮬레이터 예시 데이터"}]}""",
            "demo-projects",
            now.toEpochMilli(),
        )
        store.saveSuccessfulFile(
            DashboardFile.CALENDAR,
            """{"version":1,"timeZone":"${start.zone.id}","events":[{"id":"demo-meeting","title":"팀 회의","calendar":"예시","allDay":false,"start":"${start.format(dateTime)}","end":"${start.plusMinutes(40).format(dateTime)}","location":"회의실"},{"id":"demo-review","title":"디자인 검토","calendar":"예시","allDay":false,"start":"${later.format(dateTime)}","end":"${later.plusMinutes(30).format(dateTime)}"}]}""",
            "demo-calendar",
            now.toEpochMilli(),
        )
        DashboardWidget().updateAll(this@DebugWidgetSeedActivity)
    }
}
