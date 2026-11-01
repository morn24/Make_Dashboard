@file:Suppress("RestrictedApi", "ResourceType")

package com.morn24.dashboard.widget

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
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
import androidx.glance.appwidget.cornerRadius
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
import com.morn24.dashboard.data.WidgetColorResolver
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
    val columns: Int,
    val small: Boolean,
    val medium: Boolean,
    val narrowTall: Boolean,
    val todoRows: Int,
    val eventRows: Int,
    val projectRows: Int,
)

object WidgetLayoutPlanner {
    fun plan(size: DpSize, fontScalePercent: Int): WidgetLayoutPlan {
        val small = size.width < 200.dp && size.height < 300.dp
        val medium = size.width >= 200.dp && size.height < 300.dp
        val narrowTall = size.width < 200.dp && size.height >= 300.dp
        val largeText = fontScalePercent >= 130
        return when {
            small -> WidgetLayoutPlan(columns = 1, small = true, medium = false, narrowTall = false, todoRows = 1, eventRows = 0, projectRows = 0)
            medium -> WidgetLayoutPlan(columns = 2, small = false, medium = true, narrowTall = false, todoRows = 1, eventRows = 1, projectRows = 1)
            narrowTall -> WidgetLayoutPlan(columns = 1, small = false, medium = false, narrowTall = true, todoRows = 2, eventRows = 2, projectRows = 2)
            else -> WidgetLayoutPlan(columns = 2, small = false, medium = false, narrowTall = false, todoRows = 1, eventRows = if (largeText) 1 else 4, projectRows = 1)
        }
    }
}

class DashboardWidget : GlanceAppWidget() {
    // Exact gives Samsung and other launchers' actual resized dimensions to LocalSize.
    // Fixed responsive buckets can collapse a wide/tall widget back to the minimum bucket.
    override val sizeMode = SizeMode.Exact

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
    val undo = snapshot.undo?.takeIf { it.completedDate == todayText }
    val pendingUndo = snapshot.pendingUndoTodoId
    val eventRowLimit = if (undo != null && plan.columns > 1) 1 else plan.eventRows
    val events = allTodayEvents.take(eventRowLimit)
    val hiddenEvents = (allTodayEvents.size - events.size).coerceAtLeast(0)
    val nextTimed = if (snapshot.calendarAvailable) CalendarRules.nextTimed(snapshot.calendarEvents, today, zone, now) else null
    val denseEventPreview = !plan.small && !plan.medium && undo == null && eventRowLimit >= 4
    val rootModifier = GlanceModifier.fillMaxSize()
        .background(glanceColor(backgroundArgb(appearance)))
        .clickable(webAction)
    when {
        plan.small || plan.narrowTall -> LazyColumn(modifier = rootModifier.padding(if (plan.small) 8.dp else 10.dp)) {
            item(itemId = stableId("header")) { Header(snapshot, today, plan.small, appearance, widgetId) }
            item(itemId = stableId("sync-status")) { SyncStatus(snapshot, appearance) }
            if (snapshot.connectionState != WidgetConnectionState.READY) {
                item(itemId = stableId("connection")) { ConnectionRequired(snapshot, appearance) }
            } else {
                item(itemId = stableId("summary")) {
                    Summary(snapshot, todoSummary.doneCount, todoSummary.visibleCount, activeProjects.size, appearance)
                }
                if (undo != null) item(itemId = stableId("undo")) {
                    UndoRow(undo.title, pendingUndo == undo.todoId, undo.recordId, appearance)
                }
                if (plan.small) {
                    item(itemId = stableId("next-event")) { NextEventSummary(snapshot, nextTimed, today, zone, appearance) }
                    item(itemId = stableId("small-todo-panel")) {
                        TodoPanel(
                            todos = shownTodos,
                            total = actionableTodos.size,
                            hidden = hiddenTodos,
                            today = todayText,
                            pendingTodoIds = snapshot.pendingTodoIds,
                            appearance = appearance,
                            webAction = webAction,
                            compact = true,
                        )
                    }
                    item(itemId = stableId("small-all")) { MoreAction("전체 보기", webAction, appearance) }
                } else {
                    item(itemId = stableId("tall-todo-panel")) {
                        TodoPanel(shownTodos, actionableTodos.size, hiddenTodos, todayText, snapshot.pendingTodoIds, appearance, webAction)
                    }
                    item(itemId = stableId("tall-calendar-panel")) {
                        SchedulePanel(events, allTodayEvents.size, hiddenEvents, snapshot.calendarAvailable, now, zone, today, appearance, webAction)
                    }
                    item(itemId = stableId("tall-project-panel")) {
                        ProjectPanel(projects, activeProjects.size, appearance, webAction)
                    }
                    item(itemId = stableId("tall-next-event")) { NextEventSummary(snapshot, nextTimed, today, zone, appearance) }
                    item(itemId = stableId("tall-actions")) {
                        Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            MoreAction("전체 보기", webAction, appearance, modifier = GlanceModifier.defaultWeight())
                            MoreAction("설정", actionRunCallback<OpenWidgetSettingsAction>(), appearance, modifier = GlanceModifier.width(64.dp))
                        }
                    }
                }
            }
        }
        plan.medium -> Column(modifier = rootModifier.padding(horizontal = 4.dp, vertical = 2.dp)) {
            CompactHeader(snapshot, today, todoSummary.doneCount, todoSummary.visibleCount, activeProjects.size, undo, pendingUndo, appearance)
            if (hasCompactStatus(snapshot)) SyncStatus(snapshot, appearance, compact = true)
            if (snapshot.connectionState != WidgetConnectionState.READY) {
                ConnectionRequired(snapshot, appearance)
            } else {
                Row(modifier = GlanceModifier.fillMaxWidth().defaultWeight(), verticalAlignment = Alignment.Top) {
                    TodoPanel(
                        todos = shownTodos,
                        total = actionableTodos.size,
                        hidden = hiddenTodos,
                        today = todayText,
                        pendingTodoIds = snapshot.pendingTodoIds,
                        appearance = appearance,
                        webAction = webAction,
                        compact = true,
                        modifier = GlanceModifier.defaultWeight(),
                    )
                    Spacer(GlanceModifier.width(5.dp))
                    Column(modifier = GlanceModifier.defaultWeight()) {
                        SchedulePanel(
                            events = events,
                            total = allTodayEvents.size,
                            hidden = hiddenEvents,
                            available = snapshot.calendarAvailable,
                            now = now,
                            zone = zone,
                            today = today,
                            appearance = appearance,
                            webAction = webAction,
                            compact = true,
                            modifier = GlanceModifier.fillMaxWidth().defaultWeight(),
                        )
                        Spacer(GlanceModifier.height(4.dp))
                        ProjectPanel(
                            projects = activeProjects.take(plan.projectRows),
                            total = activeProjects.size,
                            appearance = appearance,
                            webAction = webAction,
                            compact = true,
                            modifier = GlanceModifier.fillMaxWidth().defaultWeight(),
                        )
                    }
                }
            }
        }
        else -> Column(modifier = rootModifier.padding(8.dp)) {
            Header(snapshot, today, small = false, appearance = appearance, widgetId = widgetId)
            if (snapshot.connectionState != WidgetConnectionState.READY) {
                ConnectionRequired(snapshot, appearance)
            } else {
                if (hasCompactStatus(snapshot)) SyncStatus(snapshot, appearance, compact = true)
                Summary(snapshot, todoSummary.doneCount, todoSummary.visibleCount, activeProjects.size, appearance)
                if (undo != null) UndoRow(undo.title, pendingUndo == undo.todoId, undo.recordId, appearance)
                Row(modifier = GlanceModifier.fillMaxWidth().defaultWeight(), verticalAlignment = Alignment.Top) {
                    Column(modifier = GlanceModifier.defaultWeight()) {
                        TodoPanel(
                            todos = shownTodos,
                            total = actionableTodos.size,
                            hidden = hiddenTodos,
                            today = todayText,
                            pendingTodoIds = snapshot.pendingTodoIds,
                            appearance = appearance,
                            webAction = webAction,
                            compact = denseEventPreview,
                            modifier = if (denseEventPreview) GlanceModifier.fillMaxWidth().height(78.dp)
                                else GlanceModifier.fillMaxWidth().defaultWeight(),
                        )
                        Spacer(GlanceModifier.height(6.dp))
                        SchedulePanel(
                            events = events,
                            total = allTodayEvents.size,
                            hidden = hiddenEvents,
                            available = snapshot.calendarAvailable,
                            now = now,
                            zone = zone,
                            today = today,
                            appearance = appearance,
                            webAction = webAction,
                            compact = denseEventPreview,
                            modifier = GlanceModifier.fillMaxWidth().defaultWeight(),
                        )
                    }
                    Spacer(GlanceModifier.width(8.dp))
                    ProjectPanel(
                        projects = projects,
                        total = activeProjects.size,
                        appearance = appearance,
                        webAction = webAction,
                        modifier = GlanceModifier.defaultWeight(),
                    )
                }
            }
        }
    }
}

@androidx.glance.GlanceComposable
@androidx.compose.runtime.Composable
private fun CompactHeader(
    snapshot: DashboardSnapshot,
    today: LocalDate,
    done: Int,
    total: Int,
    activeProjects: Int,
    undo: com.morn24.dashboard.data.CompletedTodoUndo?,
    pendingUndo: String?,
    appearance: WidgetAppearance,
) {
    Row(modifier = GlanceModifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = GlanceModifier.defaultWeight()) {
            Text(
                today.format(DateTimeFormatter.ofPattern("M월 d일 (E)", Locale.KOREAN)),
                maxLines = 1,
                style = TextStyle(fontSize = 13.scaledSp(appearance), fontWeight = FontWeight.Bold, color = primaryTextColor(appearance)),
            )
            Text(
                "할 일 $done/$total · 프로젝트 ${activeProjects}개 · ${syncLabel(snapshot)}",
                maxLines = 1,
                style = bodyStyle(appearance, 8, variant = true),
            )
        }
        if (undo != null) UndoButton(undo.title, pendingUndo == undo.todoId, undo.recordId, appearance)
        Box(
            modifier = GlanceModifier.size(48.dp).clickable(actionRunCallback<RefreshWidgetAction>())
                .semantics { contentDescription = "새로고침, 데이터를 다시 불러옵니다" },
            contentAlignment = Alignment.Center,
        ) {
            Text("↻", style = TextStyle(fontSize = 21.scaledSp(appearance), fontWeight = FontWeight.Bold, color = primaryTextColor(appearance)))
        }
        if (snapshot.connectionState == WidgetConnectionState.READY) {
            Box(
                modifier = GlanceModifier.size(48.dp).clickable(actionRunCallback<OpenWidgetSettingsAction>())
                    .semantics { contentDescription = "이 위젯 꾸미기 설정 열기" },
                contentAlignment = Alignment.Center,
            ) {
                Text("⚙", style = TextStyle(fontSize = 17.scaledSp(appearance), color = primaryTextColor(appearance)))
            }
        }
    }
}

@androidx.glance.GlanceComposable
@androidx.compose.runtime.Composable
private fun UndoButton(title: String, pending: Boolean, recordId: String, appearance: WidgetAppearance) {
    val modifier = GlanceModifier.size(48.dp)
        .thenIf(!pending) {
            clickable(actionRunCallback<UndoTodoAction>(parameters = actionParametersOf(UndoTodoAction.KEY_RECORD_ID.to(recordId))))
        }
        .semantics { contentDescription = if (pending) "완료 취소를 저장 중입니다" else "$title 완료 실행 취소" }
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Text(if (pending) "…" else "↶", style = TextStyle(fontSize = 21.scaledSp(appearance), color = appearanceAccent(appearance)))
    }
}

@androidx.glance.GlanceComposable
@androidx.compose.runtime.Composable
private fun TodoPanel(
    todos: List<Todo>,
    total: Int,
    hidden: Int,
    today: String,
    pendingTodoIds: Set<String>,
    appearance: WidgetAppearance,
    webAction: androidx.glance.action.Action,
    compact: Boolean = false,
    modifier: GlanceModifier = GlanceModifier.fillMaxWidth(),
) {
    val colors = WidgetColorResolver.resolve(appearance)
    Column(
        modifier = sectionPanelModifier(modifier, SectionTone.TODO, appearance, webAction, "오늘 할 일 전체 보기"),
    ) {
        PanelRule(SectionTone.TODO, appearance)
        Spacer(GlanceModifier.height(if (compact) 1.dp else 3.dp))
        SectionLabel("할 일 ${todos.size}/$total${if (hidden > 0) " · +$hidden" else ""}", appearance, SectionTone.TODO)
        if (todos.isEmpty()) {
            Text("오늘 할 일이 없어요", maxLines = 2, style = bodyStyle(appearance, if (compact) 9 else 10))
        } else {
            todos.forEach { todo -> TodoToggleRow(todo, today, pendingTodoIds, appearance) }
        }
        if (hidden > 0) Text("+${hidden}개 더보기", maxLines = 1, style = bodyStyle(appearance, 9, accent = true, roleColor = colors.todo))
    }
}

@androidx.glance.GlanceComposable
@androidx.compose.runtime.Composable
private fun SchedulePanel(
    events: List<CalendarOccurrence>,
    total: Int,
    hidden: Int,
    available: Boolean,
    now: Instant,
    zone: ZoneId,
    today: LocalDate,
    appearance: WidgetAppearance,
    webAction: androidx.glance.action.Action,
    compact: Boolean = false,
    modifier: GlanceModifier = GlanceModifier.fillMaxWidth(),
) {
    val colors = WidgetColorResolver.resolve(appearance)
    Column(
        modifier = sectionPanelModifier(modifier, SectionTone.SCHEDULE, appearance, webAction, "오늘 일정 전체 보기"),
    ) {
        PanelRule(SectionTone.SCHEDULE, appearance)
        Spacer(GlanceModifier.height(1.dp))
        SectionLabel("오늘 일정 ${events.size}/$total${if (hidden > 0) " · +$hidden" else ""}", appearance, SectionTone.SCHEDULE)
        when {
            !available -> Text("캘린더 연결 안 됨", maxLines = 1, style = bodyStyle(appearance, 9, variant = true))
            events.isEmpty() -> Text("오늘 일정 없음", maxLines = 1, style = bodyStyle(appearance, 9))
            else -> events.forEach { EventLine(it, now, zone, today, appearance, compact = compact) }
        }
        if (hidden > 0) Text("+${hidden}개 더보기", maxLines = 1, style = bodyStyle(appearance, 9, accent = true, roleColor = colors.schedule))
    }
}

@androidx.glance.GlanceComposable
@androidx.compose.runtime.Composable
private fun ProjectPanel(
    projects: List<Project>,
    total: Int,
    appearance: WidgetAppearance,
    webAction: androidx.glance.action.Action,
    compact: Boolean = false,
    modifier: GlanceModifier = GlanceModifier.fillMaxWidth(),
) {
    val colors = WidgetColorResolver.resolve(appearance)
    val hidden = (total - projects.size).coerceAtLeast(0)
    Column(
        modifier = sectionPanelModifier(modifier, SectionTone.PROJECT, appearance, webAction, "진행 중 프로젝트 전체 보기"),
    ) {
        PanelRule(SectionTone.PROJECT, appearance)
        Spacer(GlanceModifier.height(if (compact) 1.dp else 3.dp))
        SectionLabel("프로젝트 ${projects.size}/$total${if (hidden > 0) " · +$hidden" else ""}", appearance, SectionTone.PROJECT)
        if (projects.isEmpty()) {
            Text("진행 중 프로젝트 없음", maxLines = 2, style = bodyStyle(appearance, 9))
        } else {
            projects.forEach { project -> ProjectLine(project, appearance, compact) }
        }
        if (hidden > 0) Text("+${hidden}개 더보기", maxLines = 1, style = bodyStyle(appearance, 9, accent = true, roleColor = colors.project))
    }
}

private enum class SectionTone { TODO, SCHEDULE, PROJECT }

private fun sectionPanelModifier(
    modifier: GlanceModifier,
    tone: SectionTone,
    appearance: WidgetAppearance,
    webAction: androidx.glance.action.Action,
    description: String,
): GlanceModifier {
    val colors = WidgetColorResolver.resolve(appearance)
    val surface = when (tone) {
        SectionTone.TODO -> colors.todoSurface
        SectionTone.SCHEDULE -> colors.scheduleSurface
        SectionTone.PROJECT -> colors.projectSurface
    }
    val alpha = appearance.backgroundOpacityPercent * 255 / 100
    return modifier.background(glanceColor(WidgetColorResolver.withAlpha(surface, alpha)))
        .cornerRadius(9.dp)
        .clickable(webAction)
        .semantics { contentDescription = "$description, 웹앱 열기" }
        .padding(8.dp)
}

@androidx.glance.GlanceComposable
@androidx.compose.runtime.Composable
private fun PanelRule(tone: SectionTone, appearance: WidgetAppearance) {
    if (appearance.backgroundOpacityPercent == 0) {
        val colors = WidgetColorResolver.resolve(appearance)
        val roleColor = when (tone) {
            SectionTone.TODO -> colors.todo
            SectionTone.SCHEDULE -> colors.schedule
            SectionTone.PROJECT -> colors.project
        }
        Spacer(GlanceModifier.fillMaxWidth().height(1.dp).background(glanceColor(WidgetColorResolver.withAlpha(roleColor, 0x66))))
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
private fun SyncStatus(snapshot: DashboardSnapshot, appearance: WidgetAppearance, compact: Boolean = false) {
    val missing = snapshot.fileStatuses.filter { it.notFoundAtMillis != null }
    val stale = snapshot.fileStatuses.filter { it.error != null }
    when {
        snapshot.syncInProgress -> Text(
            "동기화 중 · 저장된 내용 표시",
            maxLines = 1,
            style = bodyStyle(appearance, 10, variant = true),
        )
        snapshot.errors.isNotEmpty() || snapshot.lastSyncHadErrors || stale.isNotEmpty() -> Text(
            if (compact) "⚠ 일부 갱신 실패 · 저장된 내용 표시" else "⚠ 일부 갱신 실패 · 이전 내용 표시 · 앱에서 상세 확인",
            maxLines = if (compact) 1 else 2,
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

private fun hasCompactStatus(snapshot: DashboardSnapshot): Boolean =
    snapshot.syncInProgress || snapshot.errors.isNotEmpty() || snapshot.lastSyncHadErrors ||
        snapshot.fileStatuses.any { it.error != null || (it.notFoundAtMillis != null && it.file != com.morn24.dashboard.data.DashboardFile.CALENDAR) }

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
        SectionLabel("다음 시간 일정", appearance, SectionTone.SCHEDULE)
        val label = when {
            !snapshot.calendarAvailable -> "캘린더 연결 안 됨"
            next == null -> "남은 시간 일정 없음"
            else -> "${timeLabel(next, zone, today)} ${next.event.title}"
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = GlanceModifier.fillMaxWidth().height(32.dp)) {
            if (next != null) {
                Box(
                    modifier = GlanceModifier.size(8.dp)
                        .background(glanceColor(WidgetColorResolver.eventColor(next.event.color)))
                        .cornerRadius(4.dp)
                        .semantics { contentDescription = "" },
                ) {}
                Spacer(GlanceModifier.width(6.dp))
            }
            Text(
                label,
                maxLines = 2,
                modifier = GlanceModifier.defaultWeight(),
                style = bodyStyle(appearance, 11, bold = next?.let { CalendarRules.isInProgress(it, Instant.now()) } == true),
            )
        }
    }
}

@androidx.glance.GlanceComposable
@androidx.compose.runtime.Composable
private fun SectionLabel(label: String, appearance: WidgetAppearance, tone: SectionTone? = null) {
    val colors = WidgetColorResolver.resolve(appearance)
    val role = when (tone) {
        SectionTone.TODO -> colors.todo
        SectionTone.SCHEDULE -> colors.schedule
        SectionTone.PROJECT -> colors.project
        null -> null
    }
    Text(label, maxLines = 1, style = bodyStyle(appearance, 10, bold = true, roleColor = role))
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
private fun EventLine(
    occurrence: CalendarOccurrence,
    now: Instant,
    zone: ZoneId,
    today: LocalDate,
    appearance: WidgetAppearance,
    compact: Boolean = false,
) {
    val ended = CalendarRules.hasEnded(occurrence, now)
    val inProgress = CalendarRules.isInProgress(occurrence, now)
    Row(
        modifier = GlanceModifier.fillMaxWidth().height(if (compact) 22.dp else 36.dp)
            .semantics { contentDescription = "${timeLabel(occurrence, zone, today)} ${occurrence.event.title}, ${if (inProgress) "진행 중" else if (ended) "종료" else "예정"}" },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = GlanceModifier.size(8.dp)
                .background(glanceColor(WidgetColorResolver.eventColor(occurrence.event.color)))
                .cornerRadius(4.dp),
        ) {}
        Spacer(GlanceModifier.width(6.dp))
        Text(
            "${timeLabel(occurrence, zone, today)} ${occurrence.event.title}",
            maxLines = if (compact) 1 else 2,
            modifier = GlanceModifier.defaultWeight(),
            style = bodyStyle(appearance, if (compact) 9 else 10, bold = inProgress, variant = ended),
        )
    }
}

@androidx.glance.GlanceComposable
@androidx.compose.runtime.Composable
private fun ProjectLine(project: Project, appearance: WidgetAppearance, compact: Boolean = false) {
    val colors = WidgetColorResolver.resolve(appearance)
    Column(modifier = GlanceModifier.fillMaxWidth().padding(vertical = if (compact) 1.dp else 3.dp)) {
        Text(project.name, maxLines = 1, style = bodyStyle(appearance, if (compact) 9 else 11, bold = true, roleColor = colors.project))
        if (!compact) Text("진행률 ${project.progress}%", style = bodyStyle(appearance, 9, variant = true))
        LinearProgressIndicator(
            progress = project.progress / 100f,
            modifier = GlanceModifier.fillMaxWidth().height(if (compact) 3.dp else 4.dp)
                .semantics { contentDescription = "${project.name} 진행률 ${project.progress}%" },
            color = glanceColor(colors.progress),
        )
        if (!compact) Text("다음 · ${ProjectRules.nextTask(project) ?: "다음 과제 없음"}", maxLines = 2, style = bodyStyle(appearance, 9, variant = true))
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
    roleColor: Int? = null,
) = TextStyle(
    fontSize = size.scaledSp(appearance),
    fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
    color = when {
        error -> errorTextColor(appearance)
        roleColor != null -> glanceColor(roleColor)
        accent -> appearanceAccent(appearance)
        variant && appearance.colorMode != AppearanceColorMode.AUTOMATIC -> glanceColor(WidgetColorResolver.resolve(appearance).secondaryText)
        appearance.colorMode == AppearanceColorMode.AUTOMATIC -> if (variant) androidx.glance.GlanceTheme.colors.onSurfaceVariant else androidx.glance.GlanceTheme.colors.onSurface
        else -> glanceColor(contrastText(appearance))
    },
)

private fun appearanceColors(appearance: WidgetAppearance): ColorProviders {
    if (appearance.colorMode == AppearanceColorMode.AUTOMATIC) return DynamicThemeColorProviders
    val colors = WidgetColorResolver.resolve(appearance)
    val foreground = contrastText(appearance)
    val background = baseBackground(appearance)
    val accent = colors.action
    val alphaBackground = backgroundArgb(appearance)
    return colorProviders(
        primary = glanceColor(accent),
        onPrimary = glanceColor(foreground),
        primaryContainer = glanceColor(withAlpha(accent, 0x33)),
        onPrimaryContainer = glanceColor(foreground),
        secondary = glanceColor(colors.schedule),
        onSecondary = glanceColor(foreground),
        secondaryContainer = glanceColor(withAlpha(colors.schedule, 0x22)),
        onSecondaryContainer = glanceColor(foreground),
        tertiary = glanceColor(colors.project),
        onTertiary = glanceColor(foreground),
        tertiaryContainer = glanceColor(withAlpha(colors.project, 0x22)),
        onTertiaryContainer = glanceColor(foreground),
        error = glanceColor(colors.error),
        errorContainer = glanceColor(WidgetColorResolver.mix(colors.error, background, 0.12)),
        onError = glanceColor(0xFFFFFFFF.toInt()),
        onErrorContainer = glanceColor(foreground),
        background = glanceColor(alphaBackground),
        onBackground = glanceColor(foreground),
        surface = glanceColor(alphaBackground),
        onSurface = glanceColor(foreground),
        surfaceVariant = glanceColor(withAlpha(colors.scheduleSurface, appearance.backgroundOpacityPercent * 255 / 100)),
        onSurfaceVariant = glanceColor(colors.secondaryText),
        outline = glanceColor(withAlpha(foreground, 0x55)),
        inverseOnSurface = glanceColor(background),
        inverseSurface = glanceColor(foreground),
        inversePrimary = glanceColor(accent),
    )
}

@androidx.glance.GlanceComposable
@androidx.compose.runtime.Composable
private fun appearanceAccent(appearance: WidgetAppearance): ColorProvider =
    glanceColor(WidgetColorResolver.resolve(appearance).action)

@androidx.glance.GlanceComposable
@androidx.compose.runtime.Composable
private fun primaryTextColor(appearance: WidgetAppearance): ColorProvider =
    if (appearance.colorMode == AppearanceColorMode.AUTOMATIC) androidx.glance.GlanceTheme.colors.onSurface else glanceColor(contrastText(appearance))

private fun errorTextColor(appearance: WidgetAppearance): ColorProvider =
    glanceColor(WidgetColorResolver.resolve(appearance).error)

private fun glanceColor(color: Int): ColorProvider = ColorProvider(androidx.compose.ui.graphics.Color(color))

private fun backgroundArgb(appearance: WidgetAppearance): Int =
    withAlpha(baseBackground(appearance), appearance.backgroundOpacityPercent * 255 / 100)

private fun baseBackground(appearance: WidgetAppearance): Int = WidgetColorResolver.resolve(appearance).background

private fun contrastText(appearance: WidgetAppearance): Int = WidgetColorResolver.resolve(appearance).foreground

private fun withAlpha(color: Int, alpha: Int): Int = WidgetColorResolver.withAlpha(color, alpha)

internal fun stableId(value: String): Long =
    value.fold(0xcbf29ce484222325UL.toLong()) { hash, char -> (hash xor char.code.toLong()) * 0x100000001b3L } and Long.MAX_VALUE

private fun GlanceModifier.thenIf(condition: Boolean, transform: GlanceModifier.() -> GlanceModifier): GlanceModifier =
    if (condition) transform() else this
