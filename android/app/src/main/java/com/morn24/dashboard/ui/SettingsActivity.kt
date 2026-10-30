package com.morn24.dashboard.ui

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.morn24.dashboard.data.AndroidKeystoreAesKeyProvider
import com.morn24.dashboard.data.DashboardStore
import com.morn24.dashboard.data.DefaultDashboardRepositoryFactory
import com.morn24.dashboard.data.TokenCipher
import com.morn24.dashboard.work.DashboardWorkScheduler

class SettingsActivity : ComponentActivity() {
    private lateinit var viewModel: SettingsViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        viewModel = SettingsViewModel(
            store = DashboardStore.from(applicationContext),
            repositoryFactory = DefaultDashboardRepositoryFactory(),
            tokenCipher = TokenCipher(AndroidKeystoreAesKeyProvider()),
            scope = lifecycleScope,
        )
        setContent { SettingsScreen(viewModel) }
    }
}

@Composable
private fun SettingsScreen(viewModel: SettingsViewModel) {
    val context = LocalContext.current
    val state by viewModel.state.collectAsState()
    val dark = isSystemInDarkTheme()
    val colors = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && dark -> dynamicDarkColorScheme(context)
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> dynamicLightColorScheme(context)
        dark -> darkColorScheme()
        else -> lightColorScheme()
    }

    LaunchedEffect(viewModel) { viewModel.load() }
    LaunchedEffect(state.isConnected) {
        if (state.isConnected) DashboardWorkScheduler.onSettingsSaved(context)
    }

    MaterialTheme(colorScheme = colors) {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .imePadding()
                    .padding(horizontal = 20.dp, vertical = 24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("Dashboard 연결", style = MaterialTheme.typography.headlineSmall)
                Text(
                    "GitHub 비공개 저장소의 대시보드 데이터를 읽고 할 일 체크를 저장합니다.",
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
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    singleLine = true,
                    enabled = !state.isConnecting,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = state.webUrl,
                    onValueChange = viewModel::updateWebUrl,
                    label = { Text("웹앱 주소") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    singleLine = true,
                    enabled = !state.isConnecting,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text("저장된 토큰은 이 기기 안에서 암호화해 저장합니다.", style = MaterialTheme.typography.bodySmall)

                Button(
                    onClick = { viewModel.checkConnection() },
                    enabled = !state.isConnecting,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (state.isConnecting) {
                        CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.height(18.dp))
                        Spacer(Modifier.height(1.dp))
                        Text("  연결 확인 중…")
                    } else {
                        Text("연결 확인 후 저장")
                    }
                }
                state.statusMessage?.let { message ->
                    Text(
                        text = message,
                        color = if (state.isStatusError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                Button(
                    onClick = {
                        runCatching {
                            context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("배터리 최적화 설정 열기")
                }
                Text("홈 화면을 길게 누르기 → 위젯 → Dashboard를 선택해 추가하세요.", style = MaterialTheme.typography.bodyMedium)
                Text(
                    "절전 중에는 Android가 백그라운드 갱신과 자정 갱신을 늦출 수 있습니다. 정확한 실행 시각은 보장되지 않습니다.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}
