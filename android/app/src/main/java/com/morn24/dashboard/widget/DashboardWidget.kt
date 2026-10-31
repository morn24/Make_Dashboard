@file:Suppress("RestrictedApi", "ResourceType")

package com.morn24.dashboard.widget

import android.content.Context
import android.content.Intent
import android.graphics.Color
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.toColorInt
import androidx.core.net.toUri
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.appwidget.lazy.items
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.LinearProgressIndicator
import androidx.glance.background
import androidx.glance.color.ColorProviders
import androidx.glance.color.DynamicThemeColorProviders
import androidx.glance.color.colorProviders
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.semantics.contentDescription
import androidx.glance.semantics.semantics
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.morn24.dashboard.data.AppearanceColorMode
import com.morn24.dashboard.data.DashboardSnapshot
import com.morn24.dashboard.data.DashboardStore
import com.morn24.dashboard.data.DashboardSyncEngine
import com.morn24.dashboard.data.DefaultDashboardRepositoryFactory
import com.morn24.dashboard.data.TokenCipher
import com.morn24.dashboard.data.WidgetAppearance
import com.morn24.dashboard.data.WidgetConnectionState
import com.morn24.dashboard.domain.CalendarOccurrence
import com.morn24.dashboard.domain.CalendarRules
import com.morn24.dashboard.domain.Project
import com.morn24.dashboard.domain.ProjectRules
import com.morn24.dashboard.domain.Todo
import com.morn24.dashboard.domain.TodoRules
import com.morn24.dashboard.ui.SettingsActivity
import com.morn24.dashboard.work.DashboardWorkScheduler
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

data class WidgetLayoutPlan(
    val small: Boolean,
    val medium: Boolean,
    val todoRows: Int,
    val eventRows: Int,
    val projectRows: Int,
)

object WidgetLayoutPlanner {
    fun plan(size: DpSize, fontScalePercent: Int): WidgetLayoutPlan {
        val small = size.width < 200.dp && size.height < 220.dp
        val medium = !small && size.height < 220.dp
        val largeText = fontScalePercent >= 130
        return when {
            small -> WidgetLayoutPlan(true, false, 1, 0, 0)
            medium -> WidgetLayoutPlan(false, true, 1, 0, if (largeText) 0 else 1)
            else -> WidgetLayoutPlan(false, false, if (largeText) 2 else 3, if (largeText) 3 else 5, if (largeText) 2 else 4)
        }
    }
}

class DashboardWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Responsive(
        setOf(
            DpSize(140.dp, 140.dp),
            DpSize(300.dp, 140.dp),
            DpSize(300.dp, 300.dp),
        ),
    )

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val store = DashboardStore.from(context)
        val initial = DashboardSyncEngine(
            store = store,
            repositoryFactory = DefaultDashboardRepositoryFactory(),
            tokenCipher = TokenCipher(com.morn24.dashboard.data.AndroidKeystoreAesKeyProvider()),
        ).loadCached()
        val widgetId = GlanceAppWidgetManager(context).getAppWidgetId(id)
        val snapshotFlow = store.observeWidgetSnapshot(initial, widgetId)

        provideContent {
            val snapshot by snapshotFlow.collectAsState(initial)
            val size = LocalSize.current
            val context = LocalContext.current
            val webAction = actionStartActivity(Intent(Intent.ACTION_VIEW, snapshot.webUrl.toUri()))
            val plan = WidgetLayoutPlanner.plan(size, snapshot.appearance.fontScalePercent)
            androidx.glance.GlanceTheme(colors = appearanceColors(snapshot.appearance)) {
                WidgetContent(snapshot, plan, webAction, widgetId)
            }
        }
    }
}

@androidx.glance.GlanceComposable
@androidx.compose.runtime.Composable
private fun WidgetContent(
    snapshot: DashboardSnapshot,
    plan: WidgetLayoutPlan,
    webAction: androidx.glance.action.Action,
    widgetId: Int,
) {
    val context = LocalContext.current
    val today = LocalDate.now()
    val now = Instant.now()
    val zone = ZoneId.systemDefault()
    val appearance = snapshot.appearance.sanitized()
    val todayText = today.toString()
    val todoSummary = TodoRules.summary(snapshot.todos, todayText)
    val actionableTodos = TodoRules.displayOrder(snapshot.todos, todayText).filter {
        !TodoRules.isDone(it, todayText) || it.id in snapshot.pendingTodoIds
    }
    val shownTodos = actionableTodos.take(plan.todoRows)
    val hiddenTodos = (actionableTodos.size - shownTodos.size).coerceAtLeast(0)
    val activeProjects = ProjectRules.active(snapshot.projects)
    val projects = activeProjects.take(plan.projectRows)
    val allTodayEvents = CalendarRules.occurringToday(snapshot.calendarEvents, today, zone)
    val events = allTodayEvents.take(plan.eventRows)
    val hiddenEvents = (allTodayEvents.size - events.size).coerceAtLeast(0)
    val nextTimed = if (snapshot.calendarAvailable) CalendarRules.nextTimed(snapshot.calendarEvents, today, zone, now) else null
    val undo = snapshot.undo?.takeIf { it.completedDate == todayText }
    val pendingUndo = snapshot.pendingUndoTodoId

    val margin = if (plan.small) 8.dp else 10.dp
    LazyColumn(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(glanceColor(backgroundArgb(appearance)))
            .clickable(webAction)
            .padding(margin),
    ) {
        item(itemId = stableId("header")) {
            Header(snapshot, today, plan.small, appearance, widgetId)
        }
        item(itemId = stableId("sync-status")) {
            SyncStatus(snapshot, appearance)
        }
        if (snapshot.connectionState != WidgetConnectionState.READY) {
            item(itemId = stableId("connection")) { ConnectionRequired(snapshot, appearance) }
        } else {
            item(itemId = stableId("summary")) {
                Summary(snapshot, todoSummary.doneCount, todoSummary.visibleCount, activeProjects.size, appearance)
            }
            if (undo != null) {
                item(itemId = stableId("undo")) {
                    UndoRow(undo.title, pendingUndo == undo.todoId, undo.recordId, appearance)
                }
            }

            if (plan.small || plan.medium) {
                val compactLargeText = plan.medium && appearance.fontScalePercent >= 130
                if (!compactLargeText) {
                    item(itemId = stableId("next-event")) { NextEventSummary(snapshot, nextTimed, today, zone, appearance) }
                }
                if (plan.small) {
                    item(itemId = stableId("small-todo-label")) {
                        SectionLabel("오늘 할 일 ${todoSummary.doneCount}/${todoSummary.visibleCount}", appearance)
                    }
                    if (actionableTodos.isEmpty()) {
                        item(itemId = stableId("small-todo-empty")) {
                            Text(
                                if (todoSummary.visibleCount == 0) "등록된 할 일이 없어요" else "오늘 할 일을 모두 마쳤어요",
                                style = bodyStyle(appearance, 10),
                            )
                        }
                    } else {
                        items(shownTodos, itemId = { stableId("todo:${it.id}") }) { todo ->
                            TodoToggleRow(todo, todayText, snapshot.pendingTodoIds, appearance)
                        }
                    }
                    if (hiddenTodos > 0) item(itemId = stableId("small-todo-more")) {
                        MoreAction("할 일 +${hiddenTodos}개 · 전체 보기", webAction, appearance)
                    }
                }
                if (plan.medium) {
                    if (!compactLargeText) {
                        item(itemId = stableId("all-day")) {
                            val allDayCount = allTodayEvents.count(CalendarOccurrence::allDay)
                            if (allDayCount > 0) Text("종일 일정 ${allDayCount}개", style = bodyStyle(appearance, 11, variant = true))
                        }
                    }
                    if (plan.projectRows > 0) {
                        item(itemId = stableId("top-project")) {
                            if (activeProjects.isEmpty()) {
                                Text("진행 중 프로젝트 없음", style = bodyStyle(appearance, 11))
                            } else {
                                val project = activeProjects.first()
                                Text("${project.name} · 다음 · ${ProjectRules.nextTask(project) ?: "다음 과제 없음"}", maxLines = 2, style = bodyStyle(appearance, 11))
                            }
                        }
                    }
                    if (actionableTodos.isNotEmpty()) {
                        if (!compactLargeText) {
                            item(itemId = stableId("medium-todo-label")) { SectionLabel("할 일 ${shownTodos.size}/${actionableTodos.size}", appearance) }
                        }
                        items(shownTodos, itemId = { stableId("todo:${it.id}") }) { todo ->
                            TodoToggleRow(todo, todayText, snapshot.pendingTodoIds, appearance)
                        }
                    }
                    if (hiddenTodos > 0) item(itemId = stableId("medium-todo-more")) {
                        MoreAction("할 일 +${hiddenTodos}개 · 전체 보기", webAction, appearance)
                    }
                }
            } else {
                item(itemId = stableId("todo-label")) {
                    SectionLabel("오늘 할 일 ${shownTodos.size}/${actionableTodos.size}", appearance)
                }
                if (actionableTodos.isEmpty()) {
                    item(itemId = stableId("todo-empty")) {
                        val message = when {
                            todoSummary.visibleCount == 0 -> "등록된 할 일이 없어요 · 웹에서 추가"
                            todoSummary.doneCount == todoSummary.visibleCount -> "오늘 할 일을 모두 마쳤어요"
                            else -> "남은 할 일이 없어요"
                        }
                        Text(message, style = bodyStyle(appearance, 11))
                    }
                } else {
                    items(shownTodos, itemId = { stableId("todo:${it.id}") }) { todo ->
                        TodoToggleRow(todo, todayText, snapshot.pendingTodoIds, appearance)
                    }
                }
                if (hiddenTodos > 0) item(itemId = stableId("todo-more")) {
                    MoreAction("할 일 +${hiddenTodos}개 · 전체 보기", webAction, appearance)
                }

                item(itemId = stableId("calendar-label")) {
                    SectionLabel("오늘 일정 ${events.size}/${allTodayEvents.size}", appearance)
                }
                if (!snapshot.calendarAvailable) {
                    item(itemId = stableId("calendar-unavailable")) {
                        Text("캘린더 연결 안 됨", style = bodyStyle(appearance, 11, variant = true))
                    }
                } else if (events.isEmpty()) {
                    item(itemId = stableId("calendar-empty")) {
                        Text("오늘 일정 없음", style = bodyStyle(appearance, 11))
                    }
                } else {
                    items(events, itemId = { stableId("event:${it.event.id}") }) { occurrence ->
                        EventLine(occurrence, now, zone, today, appearance)
                    }
                }
                if (hiddenEvents > 0) item(itemId = stableId("event-more")) {
                    MoreAction("일정 +${hiddenEvents}개 · 전체 보기", webAction, appearance)
                }

                item(itemId = stableId("project-label")) { SectionLabel("진행 중 프로젝트 ${projects.size}/${activeProjects.size}", appearance) }
                if (activeProjects.isEmpty()) {
                    item(itemId = stableId("project-empty")) { Text("진행 중 프로젝트 없음", style = bodyStyle(appearance, 11)) }
                } else {
                    items(projects, itemId = { stableId("project:${it.id}") }) { project -> ProjectLine(project, appearance) }
                }
                val hiddenProjects = (activeProjects.size - projects.size).coerceAtLeast(0)
                if (hiddenProjects > 0) item(itemId = stableId("project-more")) {
                    MoreAction("프로젝트 +${hiddenProjects}개 · 전체 보기", webAction, appearance)
                }
                item(itemId = stableId("next-event-detail")) { NextEventSummary(snapshot, nextTimed, today, zone, appearance) }
            }

            item(itemId = stableId("actions")) {
                Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    MoreAction("전체 보기", webAction, appearance, modifier = GlanceModifier.defaultWeight())
                    if (!plan.small) {
                        MoreAction("설정", actionRunCallback<OpenWidgetSettingsAction>(), appearance, modifier = GlanceModifier.width(64.dp))
                    }
                }
            }
        }
    }
}

@androidx.glance.GlanceComposable
@androidx.compose.runtime.Composable
private fun Header(snapshot: DashboardSnapshot, today: LocalDate, small: Boolean, appearance: WidgetAppearance, widgetId: Int) {
    Row(modifier = GlanceModifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = GlanceModifier.defaultWeight()) {
            Text(
                today.format(DateTimeFormatter.ofPattern("M월 d일 (E)", Locale.KOREAN)),
                maxLines = 1,
                style = TextStyle(
                    fontSize = (if (small) 14 else 15).scaledSp(appearance),
                    fontWeight = FontWeight.Bold,
                    color = primaryTextColor(appearance),
                ),
            )
            Text(syncLabel(snapshot), maxLines = 1, style = bodyStyle(appearance, 9, variant = true))
        }
        Box(
            modifier = GlanceModifier.size(48.dp)
                .clickable(actionRunCallback<RefreshWidgetAction>())
                .semantics { contentDescription = "새로고침, 데이터를 다시 불러옵니다" },
            contentAlignment = Alignment.Center,
        ) {
            Text("↻", style = TextStyle(fontSize = 22.scaledSp(appearance), fontWeight = FontWeight.Bold, color = primaryTextColor(appearance)))
        }
        if (!small && snapshot.connectionState == WidgetConnectionState.READY) {
            Box(
                modifier = GlanceModifier.size(48.dp)
                    .clickable(actionRunCallback<OpenWidgetSettingsAction>())
                    .semantics { contentDescription = "이 위젯 꾸미기 설정 열기" },
                contentAlignment = Alignment.Center,
            ) {
                Text("⚙", style = TextStyle(fontSize = 18.scaledSp(appearance), color = primaryTextColor(appearance)))
            }
        }
    }
}

@androidx.glance.GlanceComposable
@androidx.compose.runtime.Composable
private fun SyncStatus(snapshot: DashboardSnapshot, appearance: WidgetAppearance) {
    val missing = snapshot.fileStatuses.filter { it.notFoundAtMillis != null }
    val stale = snapshot.fileStatuses.filter { it.error != null }
    when {
        snapshot.syncInProgress -> Text(
            "동기화 중 · 저장된 내용 표시",
            maxLines = 1,
            style = bodyStyle(appearance, 10, variant = true),
        )
        snapshot.errors.isNotEmpty() || snapshot.lastSyncHadErrors || stale.isNotEmpty() -> Text(
            "⚠ 일부 갱신 실패 · 이전 내용 표시 · 앱에서 상세 확인",
            maxLines = 2,
            style = bodyStyle(appearance, 10, error = true),
        )
        missing.any { it.file != com.morn24.dashboard.data.DashboardFile.CALENDAR } -> Text(
            "일부 데이터 파일이 없어요 · 앱에서 상태 확인",
            maxLines = 1,
            style = bodyStyle(appearance, 10, variant = true),
        )
        snapshot.lastCompleteSyncAtMillis == null && snapshot.todos.isEmpty() && snapshot.projects.isEmpty() -> Text(
            "아직 동기화한 데이터가 없어요 · 새로고침",
            maxLines = 1,
            style = bodyStyle(appearance, 10, variant = true),
        )
        else -> Unit
    }
}

@androidx.glance.GlanceComposable
@androidx.compose.runtime.Composable
private fun Summary(snapshot: DashboardSnapshot, done: Int, total: Int, activeProjects: Int, appearance: WidgetAppearance) {
    Column(modifier = GlanceModifier.fillMaxWidth()) {
        Text(
            "할 일 $done/$total · 진행 중 프로젝트 ${activeProjects}개",
            maxLines = 2,
            style = bodyStyle(appearance, 12, bold = true),
        )
        LinearProgressIndicator(
            progress = if (total == 0) 0f else done.toFloat() / total,
            modifier = GlanceModifier.fillMaxWidth().height(6.dp)
                .semantics { contentDescription = "오늘 할 일 ${done}개 완료, 전체 ${total}개" },
            color = appearanceAccent(appearance),
        )
    }
}

@androidx.glance.GlanceComposable
@androidx.compose.runtime.Composable
private fun ConnectionRequired(snapshot: DashboardSnapshot, appearance: WidgetAppearance) {
    Column {
        val headline = if (snapshot.connectionState == WidgetConnectionState.REAUTHENTICATE) "다시 연결해 주세요" else "앱에서 연결을 설정하세요"
        Text(headline, style = bodyStyle(appearance, 15, bold = true))
        Spacer(GlanceModifier.height(4.dp))
        Text("탭하여 Dashboard 설정을 엽니다.", style = bodyStyle(appearance, 12, variant = true))
    }
}

@androidx.glance.GlanceComposable
@androidx.compose.runtime.Composable
private fun NextEventSummary(
    snapshot: DashboardSnapshot,
    next: CalendarOccurrence?,
    today: LocalDate,
    zone: ZoneId,
    appearance: WidgetAppearance,
) {
    Column(modifier = GlanceModifier.fillMaxWidth()) {
        Text("다음 시간 일정", style = bodyStyle(appearance, 10, bold = true, variant = true))
        val label = when {
            !snapshot.calendarAvailable -> "캘린더 연결 안 됨"
            next == null -> "남은 시간 일정 없음"
            else -> "${timeLabel(next, zone, today)} ${next.event.title}"
        }
        Text(label, maxLines = 2, style = bodyStyle(appearance, 12, bold = next?.let { CalendarRules.isInProgress(it, Instant.now()) } == true))
    }
}

@androidx.glance.GlanceComposable
@androidx.compose.runtime.Composable
private fun SectionLabel(label: String, appearance: WidgetAppearance) {
    Text(label, maxLines = 1, style = bodyStyle(appearance, 11, bold = true, variant = true))
}

@androidx.glance.GlanceComposable
@androidx.compose.runtime.Composable
private fun TodoToggleRow(todo: Todo, today: String, pendingTodoIds: Set<String>, appearance: WidgetAppearance) {
    val isDone = TodoRules.isDone(todo, today)
    val isPending = todo.id in pendingTodoIds
    val targetDoneOn = if (isDone) CLEAR_DONE_TARGET else today
    val action = actionRunCallback<ToggleTodoAction>(
        parameters = actionParametersOf(
            TodoToggleParameters.todoId.to(todo.id),
            TodoToggleParameters.targetDoneOn.to(targetDoneOn),
            TodoToggleParameters.tappedDate.to(today),
            TodoToggleParameters.title.to(todo.title),
        ),
    )
    val description = when {
        isPending -> "${todo.title}, 저장 중"
        isDone -> "${todo.title}, 완료됨"
        else -> "${todo.title}, 미완료, 완료로 표시"
    }
    val rowModifier = GlanceModifier.fillMaxWidth().height(48.dp)
        .thenIf(!isPending) { clickable(action) }
        .semantics { contentDescription = description }
        .padding(horizontal = 3.dp)
    Row(modifier = rowModifier, verticalAlignment = Alignment.CenterVertically) {
        Text(
            when {
                isPending -> "…"
                isDone -> "☑"
                else -> "□"
            },
            style = bodyStyle(appearance, 16, bold = true, accent = isDone),
        )
        Text(
            todo.title,
            modifier = GlanceModifier.defaultWeight().padding(start = 8.dp),
            maxLines = 2,
            style = bodyStyle(appearance, 11, variant = isPending),
        )
    }
}

@androidx.glance.GlanceComposable
@androidx.compose.runtime.Composable
private fun UndoRow(title: String, pending: Boolean, recordId: String, appearance: WidgetAppearance) {
    Row(modifier = GlanceModifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(if (pending) "취소 저장 중 · $title" else "최근 완료 · $title", modifier = GlanceModifier.defaultWeight(), maxLines = 2, style = bodyStyle(appearance, 10))
        if (!pending) {
            Box(
                modifier = GlanceModifier.width(84.dp).height(48.dp)
                    .clickable(actionRunCallback<UndoTodoAction>(parameters = actionParametersOf(UndoTodoAction.KEY_RECORD_ID.to(recordId))))
                    .semantics { contentDescription = "$title 완료 실행 취소" },
                contentAlignment = Alignment.Center,
            ) {
                Text("실행 취소", style = bodyStyle(appearance, 10, bold = true, accent = true))
            }
        }
    }
}

@androidx.glance.GlanceComposable
@androidx.compose.runtime.Composable
private fun MoreAction(
    text: String,
    action: androidx.glance.action.Action,
    appearance: WidgetAppearance,
    modifier: GlanceModifier = GlanceModifier.fillMaxWidth(),
) {
    Box(
        modifier = modifier.height(48.dp).clickable(action)
            .semantics { contentDescription = text },
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(text, style = bodyStyle(appearance, 10, bold = true, accent = true))
    }
}

@androidx.glance.GlanceComposable
@androidx.compose.runtime.Composable
private fun EventLine(occurrence: CalendarOccurrence, now: Instant, zone: ZoneId, today: LocalDate, appearance: WidgetAppearance) {
    val ended = CalendarRules.hasEnded(occurrence, now)
    val inProgress = CalendarRules.isInProgress(occurrence, now)
    Text(
        "${timeLabel(occurrence, zone, today)} ${occurrence.event.title}",
        maxLines = 2,
        modifier = GlanceModifier.fillMaxWidth().padding(vertical = 3.dp)
            .semantics { contentDescription = "${occurrence.event.title}, ${if (inProgress) "진행 중" else if (ended) "종료" else "예정"}" },
        style = bodyStyle(appearance, 10, bold = inProgress, variant = ended),
    )
}

@androidx.glance.GlanceComposable
@androidx.compose.runtime.Composable
private fun ProjectLine(project: Project, appearance: WidgetAppearance) {
    Column(modifier = GlanceModifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(project.name, maxLines = 1, style = bodyStyle(appearance, 11, bold = true))
        Text("진행률 ${project.progress}%", style = bodyStyle(appearance, 9, variant = true))
        LinearProgressIndicator(
            progress = project.progress / 100f,
            modifier = GlanceModifier.fillMaxWidth().height(4.dp)
                .semantics { contentDescription = "${project.name} 진행률 ${project.progress}%" },
            color = appearanceAccent(appearance),
        )
        Text("다음 · ${ProjectRules.nextTask(project) ?: "다음 과제 없음"}", maxLines = 2, style = bodyStyle(appearance, 10, variant = true))
    }
}

class RefreshWidgetAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        runCatching { DashboardWidget().update(context, glanceId) }
        DashboardWorkScheduler.requestRefresh(context)
    }
}

class OpenWidgetSettingsAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val widgetId = runCatching { GlanceAppWidgetManager(context).getAppWidgetId(glanceId) }.getOrNull() ?: return
        context.startActivity(
            Intent(context, SettingsActivity::class.java)
                .putExtra(SettingsActivity.EXTRA_WIDGET_ID, widgetId)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}

internal object TodoToggleParameters {
    val todoId = ActionParameters.Key<String>("todoId")
    val targetDoneOn = ActionParameters.Key<String>("targetDoneOn")
    val tappedDate = ActionParameters.Key<String>("tappedDate")
    val title = ActionParameters.Key<String>("todoTitle")
}

internal const val CLEAR_DONE_TARGET = "__clear__"

private fun syncLabel(snapshot: DashboardSnapshot): String = when {
    snapshot.syncInProgress -> "동기화 중…"
    snapshot.errors.isNotEmpty() || snapshot.lastSyncHadErrors -> "부분 실패 · ${snapshot.lastCompleteSyncAtMillis?.let(::formatTime) ?: "완료 기록 없음"}"
    snapshot.lastCompleteSyncAtMillis == null -> "갱신 기록 없음"
    else -> "확인 ${formatTime(snapshot.lastCompleteSyncAtMillis)}"
}

private fun formatTime(epochMillis: Long): String =
    Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("M/d HH:mm"))

private fun timeLabel(occurrence: CalendarOccurrence, zone: ZoneId, today: LocalDate): String {
    if (occurrence.allDay) return "종일"
    val start = occurrence.startInstant ?: return "시간"
    val local = start.atZone(zone)
    val prefix = if (local.toLocalDate() < today) "전날 " else ""
    return prefix + local.format(DateTimeFormatter.ofPattern("HH:mm"))
}

private fun Int.scaledSp(appearance: WidgetAppearance) = (this * appearance.fontScalePercent / 100f).sp

@androidx.glance.GlanceComposable
@androidx.compose.runtime.Composable
private fun bodyStyle(
    appearance: WidgetAppearance,
    size: Int,
    bold: Boolean = false,
    variant: Boolean = false,
    error: Boolean = false,
    accent: Boolean = false,
) = TextStyle(
    fontSize = size.scaledSp(appearance),
    fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
    color = when {
        error -> errorTextColor(appearance)
        accent -> appearanceAccent(appearance)
        variant && appearance.colorMode != AppearanceColorMode.AUTOMATIC -> glanceColor(contrastText(appearance).let { if (it == Color.WHITE) Color.LTGRAY else Color.DKGRAY })
        appearance.colorMode == AppearanceColorMode.AUTOMATIC -> if (variant) androidx.glance.GlanceTheme.colors.onSurfaceVariant else androidx.glance.GlanceTheme.colors.onSurface
        else -> glanceColor(contrastText(appearance))
    },
)

private fun appearanceColors(appearance: WidgetAppearance): ColorProviders {
    if (appearance.colorMode == AppearanceColorMode.AUTOMATIC) return DynamicThemeColorProviders
    val foreground = contrastText(appearance)
    val background = baseBackground(appearance)
    val accent = baseAccent(appearance)
    val alphaBackground = backgroundArgb(appearance)
    val secondary = if (foreground == Color.WHITE) Color.LTGRAY else Color.DKGRAY
    return colorProviders(
        primary = glanceColor(accent),
        onPrimary = glanceColor(foreground),
        primaryContainer = glanceColor(withAlpha(accent, 0x33)),
        onPrimaryContainer = glanceColor(foreground),
        secondary = glanceColor(accent),
        onSecondary = glanceColor(foreground),
        secondaryContainer = glanceColor(withAlpha(accent, 0x22)),
        onSecondaryContainer = glanceColor(foreground),
        tertiary = glanceColor(accent),
        onTertiary = glanceColor(foreground),
        tertiaryContainer = glanceColor(withAlpha(accent, 0x22)),
        onTertiaryContainer = glanceColor(foreground),
        error = glanceColor(Color.rgb(0xB3, 0x26, 0x1E)),
        errorContainer = glanceColor(Color.rgb(0xF9, 0xDE, 0xDC)),
        onError = glanceColor(Color.WHITE),
        onErrorContainer = glanceColor(Color.rgb(0x41, 0x1E, 0x1B)),
        background = glanceColor(alphaBackground),
        onBackground = glanceColor(foreground),
        surface = glanceColor(alphaBackground),
        onSurface = glanceColor(foreground),
        surfaceVariant = glanceColor(withAlpha(background, if (appearance.contrastAssist) 0xD9 else 0x88)),
        onSurfaceVariant = glanceColor(secondary),
        outline = glanceColor(withAlpha(foreground, 0x55)),
        inverseOnSurface = glanceColor(background),
        inverseSurface = glanceColor(foreground),
        inversePrimary = glanceColor(accent),
    )
}

@androidx.glance.GlanceComposable
@androidx.compose.runtime.Composable
private fun appearanceAccent(appearance: WidgetAppearance): ColorProvider =
    if (appearance.colorMode == AppearanceColorMode.AUTOMATIC) androidx.glance.GlanceTheme.colors.primary else glanceColor(baseAccent(appearance))

@androidx.glance.GlanceComposable
@androidx.compose.runtime.Composable
private fun primaryTextColor(appearance: WidgetAppearance): ColorProvider =
    if (appearance.colorMode == AppearanceColorMode.AUTOMATIC) androidx.glance.GlanceTheme.colors.onSurface else glanceColor(contrastText(appearance))

private fun errorTextColor(appearance: WidgetAppearance): ColorProvider =
    if (contrastText(appearance) == Color.WHITE) glanceColor(Color.rgb(0xFF, 0xB4, 0xAB)) else glanceColor(Color.rgb(0xB3, 0x26, 0x1E))

private fun glanceColor(color: Int): ColorProvider = ColorProvider(androidx.compose.ui.graphics.Color(color))

private fun backgroundArgb(appearance: WidgetAppearance): Int =
    withAlpha(baseBackground(appearance), (appearance.backgroundOpacityPercent * 255 / 100))

private fun baseBackground(appearance: WidgetAppearance): Int = when (appearance.colorMode) {
    AppearanceColorMode.AUTOMATIC -> Color.WHITE
    AppearanceColorMode.LIGHT -> Color.rgb(0xFF, 0xF8, 0xF2)
    AppearanceColorMode.DARK -> Color.rgb(0x17, 0x1A, 0x17)
    AppearanceColorMode.OCEAN -> Color.rgb(0xE9, 0xF3, 0xFC)
    AppearanceColorMode.FOREST -> Color.rgb(0xED, 0xF4, 0xE9)
    AppearanceColorMode.CUSTOM -> runCatching { appearance.backgroundHex.toColorInt() }.getOrDefault(Color.WHITE)
}

private fun baseAccent(appearance: WidgetAppearance): Int = when (appearance.colorMode) {
    AppearanceColorMode.AUTOMATIC -> Color.rgb(0x00, 0x6C, 0x4C)
    AppearanceColorMode.LIGHT -> Color.rgb(0x00, 0x6C, 0x4C)
    AppearanceColorMode.DARK -> Color.rgb(0x91, 0xD5, 0xB3)
    AppearanceColorMode.OCEAN -> Color.rgb(0x20, 0x56, 0xA0)
    AppearanceColorMode.FOREST -> Color.rgb(0x35, 0x62, 0x3B)
    AppearanceColorMode.CUSTOM -> runCatching { appearance.accentHex.toColorInt() }.getOrDefault(Color.rgb(0x00, 0x6C, 0x4C))
}

private fun contrastText(appearance: WidgetAppearance): Int {
    val color = baseBackground(appearance)
    val r = Color.red(color) / 255.0
    val g = Color.green(color) / 255.0
    val b = Color.blue(color) / 255.0
    fun channel(value: Double) = if (value <= 0.04045) value / 12.92 else Math.pow((value + 0.055) / 1.055, 2.4)
    val luminance = 0.2126 * channel(r) + 0.7152 * channel(g) + 0.0722 * channel(b)
    return if (luminance > 0.45) Color.rgb(0x19, 0x1B, 0x19) else Color.WHITE
}

private fun withAlpha(color: Int, alpha: Int): Int = (color and 0x00FFFFFF) or (alpha.coerceIn(0, 255) shl 24)

internal fun stableId(value: String): Long =
    value.fold(0xcbf29ce484222325UL.toLong()) { hash, char -> (hash xor char.code.toLong()) * 0x100000001b3L } and Long.MAX_VALUE

private fun GlanceModifier.thenIf(condition: Boolean, transform: GlanceModifier.() -> GlanceModifier): GlanceModifier =
    if (condition) transform() else this
