package com.morn24.dashboard.widget

import android.content.Intent
import android.net.Uri
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceTheme
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.LocalSize
import androidx.glance.LocalContext
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.color.DynamicThemeColorProviders
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.text.Text
import androidx.glance.text.FontWeight
import androidx.glance.text.TextStyle
import com.morn24.dashboard.data.AndroidKeystoreAesKeyProvider
import com.morn24.dashboard.data.DashboardSnapshot
import com.morn24.dashboard.data.DashboardStore
import com.morn24.dashboard.data.DashboardSyncEngine
import com.morn24.dashboard.data.DefaultDashboardRepositoryFactory
import com.morn24.dashboard.data.TokenCipher
import com.morn24.dashboard.data.WidgetConnectionState
import com.morn24.dashboard.domain.CalendarOccurrence
import com.morn24.dashboard.domain.CalendarRules
import com.morn24.dashboard.domain.Project
import com.morn24.dashboard.domain.ProjectRules
import com.morn24.dashboard.domain.Todo
import com.morn24.dashboard.domain.TodoRules
import com.morn24.dashboard.ui.SettingsActivity
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.Dispatchers

class DashboardWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Responsive(
        setOf(
            DpSize(140.dp, 140.dp),
            DpSize(300.dp, 140.dp),
            DpSize(300.dp, 300.dp),
        ),
    )

    override suspend fun provideGlance(context: android.content.Context, id: GlanceId) {
        val store = DashboardStore.from(context)
        val snapshot = DashboardSyncEngine(
            store = store,
            repositoryFactory = DefaultDashboardRepositoryFactory(),
            tokenCipher = TokenCipher(AndroidKeystoreAesKeyProvider()),
        ).loadCached()

        provideContent {
            GlanceTheme(colors = DynamicThemeColorProviders) {
                val size = LocalSize.current
                val context = LocalContext.current
                val settingsAction = actionStartActivity(Intent(context, SettingsActivity::class.java))
                val webAction = actionStartActivity(
                    Intent(Intent.ACTION_VIEW, Uri.parse(snapshot.webUrl)),
                )
                val small = size.width < 200.dp && size.height < 220.dp
                val medium = size.height < 220.dp
                val action = if (snapshot.connectionState == WidgetConnectionState.READY) webAction else settingsAction

                Column(
                    modifier = GlanceModifier
                        .fillMaxSize()
                        .background(GlanceTheme.colors.surface)
                        .clickable(action)
                        .padding(if (small) 10.dp else 12.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    if (snapshot.connectionState != WidgetConnectionState.READY) {
                        ConnectionRequired(snapshot)
                    } else {
                        when {
                            small -> SmallDashboard(snapshot)
                            medium -> MediumDashboard(snapshot)
                            else -> LargeDashboard(snapshot)
                        }
                    }
                }
            }
        }
    }
}

private val nowClock: Clock get() = Clock.systemDefaultZone()

@androidx.glance.GlanceComposable
@androidx.compose.runtime.Composable
private fun ConnectionRequired(snapshot: DashboardSnapshot) {
    val headline = if (snapshot.connectionState == WidgetConnectionState.REAUTHENTICATE) {
        "다시 연결해 주세요"
    } else {
        "앱을 열어 연결을 설정하세요"
    }
    Text(headline, style = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Bold, color = GlanceTheme.colors.onSurface))
    Spacer(GlanceModifier.height(8.dp))
    Text("탭하여 GitHub 저장소를 연결하세요.", style = TextStyle(fontSize = 12.sp, color = GlanceTheme.colors.onSurfaceVariant))
    Spacer(GlanceModifier.height(8.dp))
    Text("Dashboard 설정 열기  ›", style = TextStyle(fontSize = 12.sp, color = GlanceTheme.colors.primary))
}

@androidx.glance.GlanceComposable
@androidx.compose.runtime.Composable
private fun SmallDashboard(snapshot: DashboardSnapshot) {
    val now = Instant.now(nowClock)
    val today = LocalDate.now(nowClock)
    val zone = nowClock.zone
    Header(snapshot, today, small = true)
    ErrorLine(snapshot)
    val summary = TodoRules.summary(snapshot.todos, today.toString())
    Text(
        "할 일 ${summary.doneCount}/${summary.visibleCount}",
        style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Bold, color = GlanceTheme.colors.onSurface),
    )
    ProgressText(summary.progress)
    Spacer(GlanceModifier.height(4.dp))
    if (snapshot.calendarAvailable) {
        val next = CalendarRules.nextTimed(snapshot.calendarEvents, today, zone, now)
        Text("다음 일정", style = labelStyle())
        Text(
            next?.let { "${timeLabel(it, zone, today)} ${it.event.title}" } ?: "남은 일정 없음",
            maxLines = 2,
            style = TextStyle(fontSize = 12.sp, color = GlanceTheme.colors.onSurface),
        )
    }
    Spacer(GlanceModifier.height(4.dp))
    Text("진행 중 프로젝트 ${ProjectRules.active(snapshot.projects).size}개", style = labelStyle())
}

@androidx.glance.GlanceComposable
@androidx.compose.runtime.Composable
private fun MediumDashboard(snapshot: DashboardSnapshot) {
    val now = Instant.now(nowClock)
    val today = LocalDate.now(nowClock)
    val zone = nowClock.zone
    val summary = TodoRules.summary(snapshot.todos, today.toString())
    val pendingTodos = TodoRules.displayOrder(snapshot.todos, today.toString()).filterNot { TodoRules.isDone(it, today.toString()) }.take(3)
    val activeProjects = ProjectRules.active(snapshot.projects)
    Header(snapshot, today, small = false)
    ErrorLine(snapshot)
    Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            "할 일 ${summary.doneCount}/${summary.visibleCount}  ${progressPercent(summary.progress)}%",
            modifier = GlanceModifier.defaultWeight(),
            style = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Bold, color = GlanceTheme.colors.onSurface),
        )
        Text("진행 중 ${activeProjects.size}개", style = labelStyle())
    }
    ProgressText(summary.progress)
    Spacer(GlanceModifier.height(4.dp))
    Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Column(modifier = GlanceModifier.defaultWeight()) {
            Text("남은 일정", style = labelStyle())
            if (snapshot.calendarAvailable) {
                val remaining = CalendarRules.remainingToday(snapshot.calendarEvents, today, zone, now).take(2)
                if (remaining.isEmpty()) Text("없음", style = smallTextStyle())
                remaining.forEach { occurrence ->
                    Text(
                        "${timeLabel(occurrence, zone, today)} ${occurrence.event.title}",
                        maxLines = 1,
                        style = smallTextStyle(),
                    )
                }
            }
        }
        Column(modifier = GlanceModifier.defaultWeight()) {
            Text("미완료 할 일", style = labelStyle())
            if (pendingTodos.isEmpty()) Text("없음", style = smallTextStyle())
            pendingTodos.forEach { todo -> TodoToggleRow(todo, today.toString(), snapshot.pendingTodoIds) }
        }
    }
    activeProjects.firstOrNull()?.let { project ->
        Spacer(GlanceModifier.height(4.dp))
        Text(
            "${project.name} · ${project.progress}% · ${ProjectRules.nextTask(project) ?: "다음 과제 없음"}",
            maxLines = 1,
            style = TextStyle(fontSize = 10.sp, color = GlanceTheme.colors.onSurfaceVariant),
        )
    }
}

@androidx.glance.GlanceComposable
@androidx.compose.runtime.Composable
private fun LargeDashboard(snapshot: DashboardSnapshot) {
    val now = Instant.now(nowClock)
    val today = LocalDate.now(nowClock)
    val zone = nowClock.zone
    val todayText = today.toString()
    val summary = TodoRules.summary(snapshot.todos, todayText)
    val pendingTodos = TodoRules.displayOrder(snapshot.todos, todayText).filterNot { TodoRules.isDone(it, todayText) }.take(3)
    val projects = ProjectRules.active(snapshot.projects).take(4)

    Header(snapshot, today, small = false)
    ErrorLine(snapshot)
    Text(
        "할 일 ${summary.doneCount}/${summary.visibleCount}  ·  진행 중 프로젝트 ${ProjectRules.active(snapshot.projects).size}개",
        style = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Bold, color = GlanceTheme.colors.onSurface),
    )
    ProgressText(summary.progress)
    Spacer(GlanceModifier.height(6.dp))
    Row(modifier = GlanceModifier.fillMaxSize(), verticalAlignment = Alignment.Top) {
        Column(modifier = GlanceModifier.defaultWeight()) {
            Text("미완료 할 일", style = labelStyle())
            if (pendingTodos.isEmpty()) Text("없음", style = smallTextStyle())
            pendingTodos.forEach { todo -> TodoToggleRow(todo, todayText, snapshot.pendingTodoIds) }
            Spacer(GlanceModifier.height(8.dp))
            if (snapshot.calendarAvailable) {
                Text("오늘 일정", style = labelStyle())
                val events = CalendarRules.occurringToday(snapshot.calendarEvents, today, zone).take(5)
                if (events.isEmpty()) Text("오늘 일정 없음", style = smallTextStyle())
                events.forEach { occurrence -> EventLine(occurrence, now, zone, today) }
            }
        }
        Spacer(GlanceModifier.width(8.dp))
        Column(modifier = GlanceModifier.defaultWeight()) {
            Text("진행 중 프로젝트", style = labelStyle())
            if (projects.isEmpty()) Text("없음", style = smallTextStyle())
            projects.forEach { project -> ProjectLine(project) }
        }
    }
}

@androidx.glance.GlanceComposable
@androidx.compose.runtime.Composable
private fun Header(snapshot: DashboardSnapshot, today: LocalDate, small: Boolean) {
    Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = GlanceModifier.defaultWeight()) {
            Text(
                today.format(DateTimeFormatter.ofPattern("M월 d일 (E)", Locale.KOREAN)),
                maxLines = 1,
                style = TextStyle(fontSize = if (small) 14.sp else 16.sp, fontWeight = FontWeight.Bold, color = GlanceTheme.colors.onSurface),
            )
            Text("갱신 ${snapshot.lastUpdatedAtMillis?.let(::formatTime) ?: "기록 없음"}", maxLines = 1, style = smallTextStyle())
        }
        Text(
            "↻",
            modifier = GlanceModifier.clickable(actionRunCallback<RefreshWidgetAction>()).padding(6.dp),
            style = TextStyle(fontSize = 18.sp, color = GlanceTheme.colors.primary),
        )
    }
}

@androidx.glance.GlanceComposable
@androidx.compose.runtime.Composable
private fun ErrorLine(snapshot: DashboardSnapshot) {
    snapshot.errors.firstOrNull()?.let { message ->
        Text(
            "⚠ $message",
            maxLines = 1,
            style = TextStyle(fontSize = 9.sp, color = GlanceTheme.colors.error),
        )
    }
}

@androidx.glance.GlanceComposable
@androidx.compose.runtime.Composable
private fun ProgressText(progress: Float) {
    val segments = progressPercent(progress) / 10
    val bar = "■".repeat(segments) + "□".repeat(10 - segments)
    Text(
        "$bar  ${progressPercent(progress)}%",
        maxLines = 1,
        style = TextStyle(fontSize = 11.sp, color = GlanceTheme.colors.primary),
    )
}

@androidx.glance.GlanceComposable
@androidx.compose.runtime.Composable
private fun EventLine(occurrence: CalendarOccurrence, now: Instant, zone: ZoneId, today: LocalDate) {
    val ended = CalendarRules.hasEnded(occurrence, now)
    val inProgress = CalendarRules.isInProgress(occurrence, now)
    val color = when {
        inProgress -> GlanceTheme.colors.primary
        ended -> GlanceTheme.colors.onSurfaceVariant
        else -> GlanceTheme.colors.onSurface
    }
    Text(
        "${timeLabel(occurrence, zone, today)} ${occurrence.event.title}",
        maxLines = 1,
        style = TextStyle(fontSize = 10.sp, color = color, fontWeight = if (inProgress) FontWeight.Bold else FontWeight.Normal),
    )
}

@androidx.glance.GlanceComposable
@androidx.compose.runtime.Composable
private fun ProjectLine(project: Project) {
    Text(project.name, maxLines = 1, style = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Bold, color = GlanceTheme.colors.onSurface))
    Text(
        "${"■".repeat(project.progress / 20)}${"□".repeat(5 - project.progress / 20)} ${project.progress}%",
        maxLines = 1,
        style = TextStyle(fontSize = 9.sp, color = GlanceTheme.colors.primary),
    )
    Text(
        "다음 · ${ProjectRules.nextTask(project) ?: "다음 과제 없음"}",
        maxLines = 1,
        style = TextStyle(fontSize = 9.sp, color = GlanceTheme.colors.onSurfaceVariant),
    )
    Spacer(GlanceModifier.height(3.dp))
}

@androidx.glance.GlanceComposable
@androidx.compose.runtime.Composable
private fun TodoToggleRow(todo: Todo, today: String, pendingTodoIds: Set<String>) {
    val isDone = TodoRules.isDone(todo, today)
    val isPending = todo.id in pendingTodoIds
    val targetDoneOn = if (isDone) CLEAR_DONE_TARGET else today
    val action = actionRunCallback<ToggleTodoAction>(
        parameters = actionParametersOf(
            TodoToggleParameters.todoId.to(todo.id),
            TodoToggleParameters.targetDoneOn.to(targetDoneOn),
            TodoToggleParameters.tappedDate.to(today),
        ),
    )
    val modifier = if (isPending) {
        GlanceModifier.fillMaxWidth().padding(vertical = 1.dp)
    } else {
        GlanceModifier.fillMaxWidth().clickable(action).padding(vertical = 1.dp)
    }
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        Text(
            when {
                isPending -> "…"
                isDone -> "☑"
                else -> "□"
            },
            style = TextStyle(
                fontSize = 12.sp,
                color = if (isDone) GlanceTheme.colors.primary else GlanceTheme.colors.onSurfaceVariant,
            ),
        )
        Text(
            todo.title,
            modifier = GlanceModifier.defaultWeight().padding(start = 4.dp),
            maxLines = 1,
            style = TextStyle(fontSize = 10.sp, color = GlanceTheme.colors.onSurface),
        )
    }
}

@androidx.glance.GlanceComposable
@androidx.compose.runtime.Composable
private fun labelStyle() = TextStyle(fontSize = 10.sp, fontWeight = FontWeight.Bold, color = GlanceTheme.colors.onSurfaceVariant)

@androidx.glance.GlanceComposable
@androidx.compose.runtime.Composable
private fun smallTextStyle() = TextStyle(fontSize = 10.sp, color = GlanceTheme.colors.onSurface)

private fun progressPercent(value: Float): Int = (value.coerceIn(0f, 1f) * 100).toInt()

private fun formatTime(epochMillis: Long): String =
    Instant.ofEpochMilli(epochMillis).atZone(nowClock.zone).format(DateTimeFormatter.ofPattern("HH:mm"))

private fun timeLabel(occurrence: CalendarOccurrence, zone: ZoneId, today: LocalDate): String {
    if (occurrence.allDay) return "종일"
    val start = occurrence.startInstant ?: return "시간"
    val local = start.atZone(zone)
    val prefix = if (local.toLocalDate() < today) "전날 " else ""
    return prefix + local.format(DateTimeFormatter.ofPattern("HH:mm"))
}

class RefreshWidgetAction : ActionCallback {
    override suspend fun onAction(context: android.content.Context, glanceId: GlanceId, parameters: ActionParameters) {
        DashboardWidget().update(context, glanceId)
    }
}

internal object TodoToggleParameters {
    val todoId = ActionParameters.Key<String>("todoId")
    val targetDoneOn = ActionParameters.Key<String>("targetDoneOn")
    val tappedDate = ActionParameters.Key<String>("tappedDate")
}

internal const val CLEAR_DONE_TARGET = "__clear__"
