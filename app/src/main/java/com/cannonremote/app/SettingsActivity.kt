package com.cannonremote.app

import android.content.Intent
import android.os.Bundle
import android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.cannonremote.app.data.AppPrefs
import com.cannonremote.app.data.VolumeKeyAction
import com.cannonremote.app.mediakey.VolumeKeyAccessibilityService
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
private fun SettingsScreen(
    app: CanonRemoteApp,
    onBack: () -> Unit,
) {
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

                // —— 熄屏音量键开关 ——
                var screenOffKeys by remember { mutableStateOf(app.prefs.screenOffVolumeKeys) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("熄屏时音量键仍可用", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "需开启下方系统无障碍服务，相机已连接后生效。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = screenOffKeys,
                        onCheckedChange = { checked ->
                            screenOffKeys = checked
                            app.prefs.screenOffVolumeKeys = checked
                            savedTick++
                        },
                    )
                }

                // —— 无障碍按键服务状态（音量键拦截的必经之路） ——
                val context = LocalContext.current
                val a11yEnabled = remember { mutableStateOf(false) }
                val lifecycleOwner = LocalLifecycleOwner.current
                DisposableEffect(lifecycleOwner) {
                    val observer = LifecycleEventObserver { _, event ->
                        if (event == Lifecycle.Event.ON_RESUME) {
                            a11yEnabled.value =
                                VolumeKeyAccessibilityService.isEnabled(context)
                        }
                    }
                    lifecycleOwner.lifecycle.addObserver(observer)
                    onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            if (a11yEnabled.value) "系统无障碍服务：已开启 ✓"
                            else "系统无障碍服务：未开启",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (a11yEnabled.value) Color(0xFF4ADE80)
                            else MaterialTheme.colorScheme.error,
                        )
                        if (!a11yEnabled.value) {
                            Text(
                                "拦截音量键必须开启该服务，" +
                                    "开启后音量键才会在应用外的界面生效",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    if (!a11yEnabled.value) {
                        OutlinedButton(onClick = {
                            runCatching {
                                context.startActivity(Intent(ACTION_ACCESSIBILITY_SETTINGS))
                            }
                        }) {
                            Text("去开启")
                        }
                    }
                }
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VolumeKeySettingRow(
    label: String,
    selected: VolumeKeyAction,
    onSelect: (VolumeKeyAction) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
    ) {
        OutlinedTextField(
            value = selected.label,
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                .fillMaxWidth(),
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            VolumeKeyAction.entries.forEach { action ->
                DropdownMenuItem(
                    text = { Text(action.label) },
                    onClick = {
                        onSelect(action)
                        expanded = false
                    },
                )
            }
        }
    }
}
