package com.morn24.dashboard.ui

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.net.toUri
import androidx.glance.appwidget.updateAll
import com.morn24.dashboard.data.AndroidKeystoreAesKeyProvider
import com.morn24.dashboard.data.AppearanceColorMode
import com.morn24.dashboard.data.DashboardFile
import com.morn24.dashboard.data.DashboardStore
import com.morn24.dashboard.data.DashboardSyncEngine
import com.morn24.dashboard.data.DefaultDashboardRepositoryFactory
import com.morn24.dashboard.data.FileSyncStatus
import com.morn24.dashboard.data.TokenCipher
import com.morn24.dashboard.data.WidgetAppearance
import com.morn24.dashboard.data.WidgetColorResolver
import com.morn24.dashboard.widget.DashboardWidget
import com.morn24.dashboard.widget.DashboardWidgetReceiver
import com.morn24.dashboard.work.DashboardWorkScheduler
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class SettingsActivity : ComponentActivity() {
    private lateinit var settingsViewModel: SettingsViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        window.navigationBarColor = android.graphics.Color.TRANSPARENT
        settingsViewModel = ViewModelProvider(this, SettingsViewModelFactory(this))[SettingsViewModel::class.java]
        setContent { SettingsRoot(settingsViewModel, intent.getIntExtra(EXTRA_WIDGET_ID, INVALID_WIDGET_ID).takeIf { it >= 0 }) }
    }

    override fun onResume() {
        super.onResume()
        if (::settingsViewModel.isInitialized) {
            settingsViewModel.load(widgetIds(this), intent.getIntExtra(EXTRA_WIDGET_ID, INVALID_WIDGET_ID).takeIf { it >= 0 })
        }
    }

    companion object {
        const val EXTRA_WIDGET_ID = "widget_id"
        private const val INVALID_WIDGET_ID = -1
    }
}

private class SettingsViewModelFactory(activity: ComponentActivity) : ViewModelProvider.Factory {
    private val store = DashboardStore.from(activity.applicationContext)
    private val repositoryFactory = DefaultDashboardRepositoryFactory()
    private val cipher = TokenCipher(AndroidKeystoreAesKeyProvider())

    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        SettingsViewModel(store, repositoryFactory, cipher) as T
}

@Composable
private fun SettingsRoot(viewModel: SettingsViewModel, requestedWidgetId: Int?) {
    val context = LocalContext.current
    val state by viewModel.state.collectAsState()
    val dark = androidx.compose.foundation.isSystemInDarkTheme()
    val colors = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && dark -> dynamicDarkColorScheme(context)
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> dynamicLightColorScheme(context)
        dark -> darkColorScheme()
        else -> lightColorScheme()
    }
    SideEffect {
        val activity = context as? ComponentActivity
        if (activity != null) {
            val controller = WindowInsetsControllerCompat(activity.window, activity.window.decorView)
            controller.isAppearanceLightStatusBars = !dark
            controller.isAppearanceLightNavigationBars = !dark
        }
    }
    var confirmDiscard by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(state.appearanceRevision) {
        if (state.appearanceRevision > 0) DashboardWidget().updateAll(context)
    }
    LaunchedEffect(state.connectionRevision) {
        if (state.connectionRevision > 0) {
            DashboardWorkScheduler.onSettingsSaved(context)
            val snapshot = withContext(Dispatchers.IO) {
                DashboardSyncEngine(
                    DashboardStore.from(context),
                    DefaultDashboardRepositoryFactory(),
                    TokenCipher(AndroidKeystoreAesKeyProvider()),
                ).loadCached()
            }
            viewModel.updateCachedSnapshot(snapshot)
        }
    }

    BackHandler(enabled = state.page != SettingsPage.HOME) {
        if (state.page == SettingsPage.APPEARANCE && state.appearanceDirty) confirmDiscard = true
        else viewModel.openHome()
    }
    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text("꾸미기 변경을 버릴까요?") },
            text = { Text("아직 적용하지 않은 미리보기 설정이 사라집니다.") },
            confirmButton = {
                Button(onClick = { confirmDiscard = false; viewModel.cancelAppearance() }) { Text("변경 버리기") }
            },
            dismissButton = { OutlinedButton(onClick = { confirmDiscard = false }) { Text("계속 편집") } },
        )
    }

    MaterialTheme(colorScheme = colors) {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            contentWindowInsets = WindowInsets.safeDrawing,
        ) { insets ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(insets)
                    .imePadding()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        text = when (state.page) {
                            SettingsPage.HOME -> "Dashboard"
                            SettingsPage.CONNECTION -> "연결 변경"
                            SettingsPage.APPEARANCE -> "위젯 꾸미기"
                            SettingsPage.STATUS -> "동기화 상태"
                            SettingsPage.HELP -> "도움말"
                        },
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                    )
                    if (state.page != SettingsPage.HOME) {
                        OutlinedButton(onClick = {
                            if (state.page == SettingsPage.APPEARANCE && state.appearanceDirty) confirmDiscard = true
                            else viewModel.openHome()
                        }) { Text("홈") }
                    }
                }

                when (state.page) {
                    SettingsPage.HOME -> HomePage(state, viewModel)
                    SettingsPage.CONNECTION -> ConnectionPage(state, viewModel)
                    SettingsPage.APPEARANCE -> AppearancePage(state, viewModel)
                    SettingsPage.STATUS -> StatusPage(state, viewModel)
                    SettingsPage.HELP -> HelpPage()
                }
                state.widgetMessage?.let { StatusCard(it, isError = false) }
                state.statusMessage?.let { StatusCard(it, state.isStatusError) }
            }
        }
    }
}

@Composable
private fun HomePage(state: SettingsUiState, viewModel: SettingsViewModel) {
    val context = LocalContext.current
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(if (state.isConnected) "GitHub 연결됨" else "GitHub 연결이 필요해요", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text("${state.owner}/${state.repository} · ${state.branch}", style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = viewModel::openStatus) { Text("동기화 상태") }
                OutlinedButton(onClick = viewModel::openConnection) { Text("연결 변경") }
            }
        }
    }

    Text("홈 화면 위젯", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
    if (state.widgetIds.isEmpty()) {
        OutlinedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("아직 추가한 위젯이 없어요. 원하는 크기를 홈 화면에 추가할 수 있습니다.")
                Button(onClick = { addWidget(context) }) { Text("위젯 추가") }
            }
        }
    } else {
        state.widgetIds.forEachIndexed { index, widgetId ->
            OutlinedCard(Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Dashboard 위젯 ${index + 1}", fontWeight = FontWeight.SemiBold)
                        Text("ID $widgetId", style = MaterialTheme.typography.bodySmall)
                    }
                    OutlinedButton(onClick = { viewModel.openAppearance(widgetId) }) { Text("꾸미기") }
                }
            }
        }
        Button(onClick = { addWidget(context) }, modifier = Modifier.fillMaxWidth()) { Text("위젯 추가") }
    }

    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("새 위젯 기본 모양", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text("기본값을 바꾸면 이후 추가하는 위젯에 적용됩니다. 기존 위젯은 각각 설정을 유지합니다.", style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = { viewModel.openAppearance(null) }) { Text("기본 모양 설정") }
        }
    }

    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Button(onClick = {
            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, state.webUrl.toUri())) }
                .onFailure { Toast.makeText(context, "웹앱을 열지 못했어요.", Toast.LENGTH_SHORT).show() }
        }, modifier = Modifier.weight(1f)) { Text("웹앱 열기") }
        OutlinedButton(onClick = viewModel::openHelp, modifier = Modifier.weight(1f)) { Text("도움말") }
    }
    if (state.widgetIds.isNotEmpty()) {
        OutlinedButton(onClick = { DashboardWorkScheduler.requestRefresh(context) }, modifier = Modifier.fillMaxWidth()) {
            Text("지금 새로고침")
        }
    }
}

@Composable
private fun ConnectionPage(state: SettingsUiState, viewModel: SettingsViewModel) {
    Text(
        "GitHub 비공개 저장소와 연결합니다. 토큰은 Android Keystore로 암호화해 이 기기에 저장하며, 화면을 나가면 입력값은 지웁니다.",
        style = MaterialTheme.typography.bodyMedium,
    )
    OutlinedTextField(
        value = state.owner,
        onValueChange = viewModel::updateOwner,
        label = { Text("저장소 소유자") },
        singleLine = true,
        enabled = !state.isConnecting,
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = state.repository,
        onValueChange = viewModel::updateRepository,
        label = { Text("저장소 이름") },
        singleLine = true,
        enabled = !state.isConnecting,
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = state.branch,
        onValueChange = viewModel::updateBranch,
        label = { Text("브랜치") },
        singleLine = true,
        enabled = !state.isConnecting,
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = state.tokenInput,
        onValueChange = viewModel::updateToken,
        label = { Text("GitHub 토큰") },
        placeholder = {
            if (state.hasSavedToken) Text("저장된 토큰을 유지하려면 비워 두세요")
            else Text("Contents 읽기/쓰기 권한이 있는 토큰")
        },
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { if (!state.isConnecting) viewModel.checkConnection() }),
        singleLine = true,
        enabled = !state.isConnecting,
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = state.webUrl,
        onValueChange = viewModel::updateWebUrl,
        label = { Text("웹앱 주소") },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
        singleLine = true,
        enabled = !state.isConnecting,
        modifier = Modifier.fillMaxWidth(),
    )
    Text("연결 확인은 data/todos.json 읽기만 검사합니다. 체크를 저장할 Contents 쓰기 권한까지 검사하지는 않습니다.", style = MaterialTheme.typography.bodySmall)
    Button(
        onClick = { viewModel.checkConnection() },
        enabled = !state.isConnecting,
        modifier = Modifier.fillMaxWidth(),
    ) {
        if (state.isConnecting) CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.height(20.dp))
        Spacer(Modifier.height(1.dp))
        Text(if (state.isConnecting) "  연결 확인 중…" else "읽기 확인 후 저장")
    }
    if (state.hasSavedToken) {
        OutlinedButton(onClick = viewModel::openHome, modifier = Modifier.fillMaxWidth()) { Text("취소") }
    }
}

@Composable
private fun AppearancePage(state: SettingsUiState, viewModel: SettingsViewModel) {
    Text("위젯마다 다른 모양을 적용할 수 있습니다. 슬라이더와 색상은 저장 전 미리보기에만 반영됩니다.", style = MaterialTheme.typography.bodyMedium)
    Text("설정 대상", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(
            selected = state.selectedWidgetId == null,
            onClick = { if (!state.appearanceDirty) viewModel.selectAppearanceWidget(null) },
            label = { Text("새 위젯 기본값") },
            enabled = !state.appearanceDirty,
        )
        state.widgetIds.forEachIndexed { index, id ->
            FilterChip(
                selected = state.selectedWidgetId == id,
                onClick = { if (!state.appearanceDirty) viewModel.selectAppearanceWidget(id) },
                label = { Text("위젯 ${index + 1}") },
                enabled = !state.appearanceDirty,
            )
        }
    }

    Text("배경 모드", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
    val modes = listOf(
        AppearanceColorMode.AUTOMATIC to "자동",
        AppearanceColorMode.LIGHT to "밝음",
        AppearanceColorMode.DARK to "어두움",
        AppearanceColorMode.OCEAN to "바다",
        AppearanceColorMode.FOREST to "숲",
        AppearanceColorMode.CUSTOM to "사용자 지정",
    )
    modes.chunked(3).forEach { row ->
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            row.forEach { (mode, label) ->
                FilterChip(
                    selected = state.draftAppearance.colorMode == mode,
                    onClick = { viewModel.updateDraft { copy(colorMode = mode) } },
                    label = { Text(label) },
                )
            }
        }
    }
    Text("강조색 조합", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
    Text("배경 모드와 별도로 선택합니다. 어두운 배경은 그대로 유지됩니다.", style = MaterialTheme.typography.bodySmall)
    WidgetColorResolver.palettes.chunked(2).forEach { row ->
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            row.forEach { palette ->
                FilterChip(
                    selected = state.draftAppearance.accentPaletteId == palette.id,
                    onClick = { viewModel.updateDraft { copy(accentPaletteId = palette.id) } },
                    label = { Text(palette.name, maxLines = 1) },
                    modifier = Modifier.weight(1f),
                )
            }
            if (row.size == 1) Spacer(Modifier.weight(1f))
        }
    }
    if (state.draftAppearance.colorMode == AppearanceColorMode.CUSTOM) {
        OutlinedTextField(
            value = state.draftAppearance.backgroundHex,
            onValueChange = viewModel::updateDraftBackgroundHex,
            label = { Text("배경색 · #RRGGBB") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Next),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = state.draftAppearance.accentHex,
            onValueChange = viewModel::updateDraftAccentHex,
            label = { Text("강조색 · #RRGGBB") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Done),
            modifier = Modifier.fillMaxWidth(),
        )
    }

    Text("글씨 크기 ${state.draftAppearance.fontScalePercent}%", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
    Slider(
        value = state.draftAppearance.fontScalePercent.toFloat(),
        onValueChange = { value -> viewModel.updateDraft { copy(fontScalePercent = value.toInt()) } },
        valueRange = 80f..150f,
        steps = 6,
        modifier = Modifier.fillMaxWidth(),
    )
    Text("시스템 글꼴 크기에 이 배율을 더 적용합니다.", style = MaterialTheme.typography.bodySmall)

    Text("배경 불투명도 ${state.draftAppearance.backgroundOpacityPercent}%", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
    Slider(
        value = state.draftAppearance.backgroundOpacityPercent.toFloat(),
        onValueChange = { value -> viewModel.updateDraft { copy(backgroundOpacityPercent = value.toInt()) } },
        valueRange = 0f..100f,
        steps = 19,
        modifier = Modifier.fillMaxWidth(),
    )
    Text("0%는 완전 투명, 100%는 불투명입니다. 글자와 아이콘은 투명도를 낮추지 않습니다.", style = MaterialTheme.typography.bodySmall)

    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f)) {
            Text("대비 보조", fontWeight = FontWeight.SemiBold)
            Text("투명 배경에서 읽기 쉽도록 텍스트 표면 대비를 높입니다.", style = MaterialTheme.typography.bodySmall)
        }
        Switch(
            checked = state.draftAppearance.contrastAssist,
            onCheckedChange = { checked -> viewModel.updateDraft { copy(contrastAssist = checked) } },
        )
    }

    AppearancePreview(state.draftAppearance)

    OutlinedButton(onClick = viewModel::restoreAppearanceDefaults, modifier = Modifier.fillMaxWidth()) { Text("기본값으로 복원") }
    Button(onClick = { viewModel.applyAppearance(toAllWidgets = false) }, modifier = Modifier.fillMaxWidth()) {
        Text(if (state.selectedWidgetId == null) "기본값 저장" else "이 위젯에 적용")
    }
    OutlinedButton(onClick = { viewModel.applyAppearance(toAllWidgets = true) }, modifier = Modifier.fillMaxWidth()) {
        Text("모든 위젯에 적용")
    }
    OutlinedButton(onClick = viewModel::cancelAppearance, modifier = Modifier.fillMaxWidth()) { Text("취소") }
}

@Composable
private fun AppearancePreview(appearance: WidgetAppearance) {
    val colors = WidgetColorResolver.resolve(
        appearance,
        automaticBackground = MaterialTheme.colorScheme.surface.toArgb(),
        automaticAccent = MaterialTheme.colorScheme.primary.toArgb(),
    )
    val background = Color(colors.background)
    val foreground = Color(colors.foreground)
    val opacity = appearance.backgroundOpacityPercent / 100f
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = background.copy(alpha = opacity)),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Text("오늘 진행 2/5 · 다음 일정 10:30", color = foreground, fontSize = (12 * appearance.fontScalePercent / 100f).sp, fontWeight = FontWeight.SemiBold)
            LinearProgressIndicator(progress = { 0.4f }, color = Color(colors.progress), modifier = Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                PreviewSection(
                    title = "할 일",
                    body = "□ 기획안 정리",
                    background = Color(colors.todoSurface),
                    role = Color(colors.todo),
                    foreground = foreground,
                    opacity = opacity,
                    modifier = Modifier.weight(1f),
                )
                PreviewSection(
                    title = "오늘 일정",
                    body = "10:30 팀 회의",
                    background = Color(colors.scheduleSurface),
                    role = Color(colors.schedule),
                    foreground = foreground,
                    opacity = opacity,
                    showEventDot = true,
                    modifier = Modifier.weight(1f),
                )
                PreviewSection(
                    title = "프로젝트",
                    body = "72% 진행",
                    background = Color(colors.projectSurface),
                    role = Color(colors.project),
                    foreground = foreground,
                    opacity = opacity,
                    modifier = Modifier.weight(1f),
                )
            }
            Surface(
                color = Color(colors.action).copy(alpha = if (opacity == 0f) 0.16f else 0.20f),
                shape = RoundedCornerShape(8.dp),
            ) {
                Text(
                    "✓ 완료  ·  실행 취소",
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    color = Color(colors.action),
                    fontSize = (10 * appearance.fontScalePercent / 100f).sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

@Composable
private fun PreviewSection(
    title: String,
    body: String,
    background: Color,
    role: Color,
    foreground: Color,
    opacity: Float,
    modifier: Modifier = Modifier,
    showEventDot: Boolean = false,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(8.dp),
        color = background.copy(alpha = opacity),
        border = if (opacity == 0f) androidx.compose.foundation.BorderStroke(1.dp, role.copy(alpha = 0.45f)) else null,
    ) {
        Column(Modifier.padding(7.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, maxLines = 1, color = role, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (showEventDot) androidx.compose.foundation.layout.Box(
                    Modifier.size(7.dp).background(Color(0xFFE53935), CircleShape),
                )
                Text(body, maxLines = 2, color = foreground, fontSize = 9.sp)
            }
        }
    }
}

@Composable
private fun StatusPage(state: SettingsUiState, viewModel: SettingsViewModel) {
    val context = LocalContext.current
    val snapshot = state.snapshot
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("전체 동기화", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(
                when {
                    snapshot == null -> "상태를 아직 읽지 못했어요."
                    snapshot.syncInProgress -> "파일별 결과를 기다리는 중입니다. 이전 데이터는 그대로 사용할 수 있어요."
                    snapshot.lastSyncHadErrors -> "일부 파일을 갱신하지 못했어요. 이전 데이터가 남아 있을 수 있습니다."
                    snapshot.lastCompleteSyncAtMillis == null -> "완료된 전체 갱신 기록이 없어요."
                    else -> "전체 갱신 확인 ${formatStatusTime(snapshot.lastCompleteSyncAtMillis)}"
                },
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
    snapshot?.fileStatuses.orEmpty().forEach { FileStatusCard(it) }
    snapshot?.errors?.forEach { StatusCard(it, isError = true) }
    Button(onClick = { DashboardWorkScheduler.requestRefresh(context) }, modifier = Modifier.fillMaxWidth(), enabled = state.isConnected) {
        Text("다시 갱신")
    }
    OutlinedButton(onClick = viewModel::openHelp, modifier = Modifier.fillMaxWidth()) { Text("오류 해결 도움말") }
}

@Composable
private fun FileStatusCard(status: FileSyncStatus) {
    val title = when (status.file) {
        DashboardFile.TODOS -> "할 일 · ${status.file.path}"
        DashboardFile.PROJECTS -> "프로젝트 · ${status.file.path}"
        DashboardFile.CALENDAR -> "캘린더 · ${status.file.path}"
    }
    val message = when {
        status.error != null -> "마지막 시도 실패 · ${status.error}"
        status.notFoundAtMillis != null -> if (status.file == DashboardFile.CALENDAR) "404 · 캘린더가 연결되지 않았습니다." else "404 · 파일을 찾지 못했습니다."
        status.lastSuccessAtMillis != null -> "읽기 확인 ${formatStatusTime(status.lastSuccessAtMillis)}"
        else -> "아직 읽기 확인 기록이 없습니다."
    }
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(message, style = MaterialTheme.typography.bodySmall, color = if (status.error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
            status.lastSuccessAtMillis?.let { Text("마지막 성공 ${formatStatusTime(it)}", style = MaterialTheme.typography.bodySmall) }
            status.lastAttemptAtMillis?.let { Text("마지막 시도 ${formatStatusTime(it)}", style = MaterialTheme.typography.bodySmall) }
        }
    }
}

@Composable
private fun HelpPage() {
    Text("GitHub 연결", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
    Text("Fine-grained 토큰에서 dashboard-data 저장소 하나만 선택하고 Contents 읽기·쓰기 권한을 설정하세요. 토큰은 채팅이나 문서에 붙이지 말고 마스킹된 연결 입력란에만 넣습니다.")
    Text("연결 확인은 할 일 파일 읽기만 검사합니다. GitHub의 쓰기 권한은 첫 체크 저장 시 확인되며, 실패하면 위젯에 원복과 오류가 표시됩니다.", style = MaterialTheme.typography.bodySmall)
    Text("위젯 추가", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
    Text("위젯 추가 요청이 지원되지 않으면 홈 화면 빈 곳을 길게 누르고 위젯 → Dashboard를 열어 원하는 크기를 끌어 놓으세요.")
    Text("일정 갱신", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
    Text("30분 원격 갱신과 일정 시각의 로컬 화면 갱신은 Android 절전 정책에 따라 늦어질 수 있습니다. 정확한 분 단위 실행은 보장되지 않습니다.")
    val context = LocalContext.current
    OutlinedButton(
        onClick = {
            runCatching { context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
                .onFailure { Toast.makeText(context, "배터리 설정을 열 수 없어요.", Toast.LENGTH_SHORT).show() }
        },
        modifier = Modifier.fillMaxWidth(),
    ) { Text("배터리 최적화 설정 열기") }
}

@Composable
private fun StatusCard(message: String, isError: Boolean) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = if (isError) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Text(
            message,
            modifier = Modifier.padding(14.dp),
            color = if (isError) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSecondaryContainer,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

private fun addWidget(context: Context) {
    val manager = AppWidgetManager.getInstance(context)
    if (manager.isRequestPinAppWidgetSupported) {
        val accepted = manager.requestPinAppWidget(ComponentName(context, DashboardWidgetReceiver::class.java), null, null)
        if (!accepted) Toast.makeText(context, "런처가 위젯 추가 요청을 받지 않았어요. 홈 화면의 위젯 목록에서 추가하세요.", Toast.LENGTH_LONG).show()
    } else {
        Toast.makeText(context, "홈 화면 빈 곳을 길게 누른 뒤 위젯 → Dashboard를 선택하세요.", Toast.LENGTH_LONG).show()
    }
}

private fun widgetIds(context: Context): List<Int> = runCatching {
    AppWidgetManager.getInstance(context).getAppWidgetIds(ComponentName(context, DashboardWidgetReceiver::class.java)).toList()
}.getOrDefault(emptyList())

private fun formatStatusTime(epochMillis: Long): String =
    Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.KOREAN))
