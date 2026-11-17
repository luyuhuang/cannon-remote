package com.cannonremote.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.cannonremote.app.data.AppPrefs
import com.cannonremote.app.data.VolumeKeyAction
import com.cannonremote.app.ui.theme.CanonRemoteTheme

class SettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 应用固定深色主题：强制状态栏/导航栏图标为浅色（白色）
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        setContent {
            CanonRemoteTheme {
                SettingsScreen(
                    app = application as CanonRemoteApp,
                    onBack = { finish() },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsScreen(app: CanonRemoteApp, onBack: () -> Unit) {
    var holdMsText by remember { mutableStateOf(app.prefs.shutterHoldMs.toString()) }
    var savedTick by remember { mutableIntStateOf(0) }

    val holdMs = holdMsText.toIntOrNull()
    val valid = holdMs != null && holdMs >= AppPrefs.MIN_SHUTTER_HOLD_MS && holdMs <= AppPrefs.MAX_SHUTTER_HOLD_MS

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("设置") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = Color.Transparent,
                ),
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("拍摄", style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.secondary)

                OutlinedTextField(
                    value = holdMsText,
                    onValueChange = { text ->
                        val filtered = text.filter { it.isDigit() }.take(5)
                        holdMsText = filtered
                        val value = filtered.toIntOrNull()
                        if (value != null && value >= AppPrefs.MIN_SHUTTER_HOLD_MS) {
                            app.prefs.shutterHoldMs = value
                            savedTick++
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("快门按下→松开间隔 (毫秒)") },
                    supportingText = {
                        Text(
                            if (valid || holdMsText.isBlank()) {
                                "“拍一张”时按下与松开快门指令的间隔。一般 100–300ms，默认 " +
                                    "${AppPrefs.DEFAULT_SHUTTER_HOLD_MS}ms"
                            } else {
                                "请输入 ${AppPrefs.MIN_SHUTTER_HOLD_MS}–${AppPrefs.MAX_SHUTTER_HOLD_MS} 之间的数字"
                            },
                            color = if (valid || holdMsText.isBlank()) MaterialTheme.colorScheme.onSurfaceVariant
                            else MaterialTheme.colorScheme.error,
                        )
                    },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    isError = !valid && holdMsText.isNotBlank(),
                )

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(100, 150, 200, 300).forEach { preset ->
                        AssistChip(
                            onClick = {
                                holdMsText = preset.toString()
                                app.prefs.shutterHoldMs = preset
                                savedTick++
                            },
                            label = { Text("${preset}ms") },
                        )
                    }
                }
            }

            Text(
                "说明：间隔值过小可能造成相机来不及响应，过大则影响连拍速度。" +
                    "如遇到漏拍，可适当增大该值。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // —— 音量键映射 ——
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("音量键", style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.secondary)

                VolumeKeySettingRow(
                    label = "音量+ 键",
                    selected = app.prefs.volumeUpAction,
                    onSelect = {
                        app.prefs.volumeUpAction = it
                        savedTick++
                    },
                )
                VolumeKeySettingRow(
                    label = "音量- 键",
                    selected = app.prefs.volumeDownAction,
                    onSelect = {
                        app.prefs.volumeDownAction = it
                        savedTick++
                    },
                )

                Text(
                    "在主界面按音量键即可触发对应功能，已设置时不再调节系统音量。" +
                        "延时/间隔拍摄使用主界面记住的参数；设为“间隔拍摄”时，" +
                        "按一下开始、再按一下停止。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            val showSaved = remember { mutableStateOf(false) }
            androidx.compose.runtime.LaunchedEffect(savedTick) {
                if (savedTick > 0) {
                    showSaved.value = true
                    kotlinx.coroutines.delay(1500)
                    showSaved.value = false
                }
            }
            if (showSaved.value) {
                Text(
                    "已保存 ✓",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF4ADE80),
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun VolumeKeySettingRow(
    label: String,
    selected: VolumeKeyAction,
    onSelect: (VolumeKeyAction) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, style = MaterialTheme.typography.bodyLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            VolumeKeyAction.entries.forEach { action ->
                FilterChip(
                    selected = selected == action,
                    onClick = { onSelect(action) },
                    label = { Text(action.label) },
                )
            }
        }
    }
}
