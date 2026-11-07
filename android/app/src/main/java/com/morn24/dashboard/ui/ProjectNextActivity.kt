package com.morn24.dashboard.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.glance.appwidget.updateAll
import com.morn24.dashboard.data.AndroidKeystoreAesKeyProvider
import com.morn24.dashboard.data.DashboardDisplayData
import com.morn24.dashboard.data.DashboardFile
import com.morn24.dashboard.data.DashboardStore
import com.morn24.dashboard.data.DashboardWriter
import com.morn24.dashboard.data.DefaultDashboardRepositoryFactory
import com.morn24.dashboard.data.TokenCipher
import com.morn24.dashboard.data.WriteOutcome
import com.morn24.dashboard.domain.Project
import com.morn24.dashboard.domain.ProjectRules
import com.morn24.dashboard.widget.DashboardWidget
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/** Lists one project next tasks with round check marks (the web app "사용자 확인" check). */
class ProjectNextActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val projectId = intent.getStringExtra(EXTRA_PROJECT_ID)
        setContent { ProjectNextRoot(projectId, onFinish = { finish() }) }
    }

    companion object {
        const val EXTRA_PROJECT_ID = "projectId"

        fun intent(context: Context, projectId: String): Intent = Intent(context, ProjectNextActivity::class.java)
            .setAction("com.morn24.dashboard.PROJECT_NEXT")
            .setData(Uri.parse("dashboard://project/next/" + Uri.encode(projectId)))
            .putExtra(EXTRA_PROJECT_ID, projectId)
    }
}

/** Unchecked next tasks first, then checked ones (including checks whose task left `next`). */
internal fun nextTaskRows(project: Project): List<Pair<String, Boolean>> {
    val checkedTexts = project.checked.map { it.text }.toSet()
    val open = project.next.filter { it.isNotBlank() && it !in checkedTexts }.map { it to false }
    val done = (project.next.filter { it in checkedTexts } + project.checked.map { it.text }.filter { it !in project.next })
        .distinct().map { it to true }
    return open + done
}

@Composable
private fun ProjectNextRoot(projectId: String?, onFinish: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember { DashboardStore.from(context) }
    val writer = remember {
        DashboardWriter(store, DefaultDashboardRepositoryFactory(), TokenCipher(AndroidKeystoreAesKeyProvider()))
    }
    var project by remember { mutableStateOf<Project?>(null) }
    var loaded by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    suspend fun reload() {
        project = runCatching {
            val raw = store.cached(DashboardFile.PROJECTS).rawJson.orEmpty()
            DashboardDisplayData.projects(Json.parseToJsonElement(raw) as JsonObject).firstOrNull { it.id == projectId }
        }.getOrNull()
        loaded = true
    }
    LaunchedEffect(projectId) { reload() }

    MaterialTheme(colorScheme = dashboardColorScheme(context, isSystemInDarkTheme())) {
        if (!loaded) return@MaterialTheme
        val current = project
        AlertDialog(
            onDismissRequest = { if (!busy) onFinish() },
            title = { Text(current?.name ?: "프로젝트를 찾지 못했어요") },
            text = {
                Column(
                    modifier = Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    if (current == null) {
                        Text("위젯을 새로고침한 뒤 다시 눌러 주세요.")
                    } else {
                        val rows = nextTaskRows(current)
                        if (rows.isEmpty()) Text("다음 과제가 없어요")
                        rows.forEach { (task, checked) ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth()
                                    .clickable(enabled = !busy) {
                                        busy = true
                                        error = null
                                        scope.launch {
                                            when (val outcome = writer.setNextChecked(current.id, task, !checked)) {
                                                WriteOutcome.Saved -> {
                                                    reload()
                                                    runCatching { DashboardWidget().updateAll(context) }
                                                }
                                                WriteOutcome.StaleConfiguration -> error = "연결 설정이 바뀌어 저장하지 않았어요."
                                                is WriteOutcome.Failed -> error = outcome.message
                                            }
                                            busy = false
                                        }
                                    }
                                    .padding(vertical = 10.dp)
                                    .semantics { contentDescription = "$task, ${if (checked) "확인함, 누르면 해제" else "확인 전, 누르면 확인 완료"}" },
                            ) {
                                Text(if (checked) "●" else "○", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                                Text(
                                    task,
                                    modifier = Modifier.padding(start = 12.dp),
                                    style = MaterialTheme.typography.bodyMedium,
                                    textDecoration = if (checked) TextDecoration.LineThrough else null,
                                    color = if (checked) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                                )
                            }
                        }
                        if (busy) Text("저장 중…", style = MaterialTheme.typography.bodySmall)
                        error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                    }
                }
            },
            confirmButton = { TextButton(onClick = onFinish, enabled = !busy) { Text("닫기") } },
        )
    }
}
