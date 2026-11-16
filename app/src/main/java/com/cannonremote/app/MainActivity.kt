package com.cannonremote.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.BluetoothDisabled
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.ViewColumn
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cannonremote.app.ble.ConnectionState
import com.cannonremote.app.data.AppPrefs
import com.cannonremote.app.shooting.DelayedState
import com.cannonremote.app.shooting.IntervalState
import com.cannonremote.app.ui.BlePermissions
import com.cannonremote.app.ui.theme.CanonRemoteTheme

class MainActivity : ComponentActivity() {

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
            if (granted.values.all { it }) {
                // 首次启动且已记住相机：授权后自动连接
                (application as CanonRemoteApp).connectRemembered()
            }
        }

    private val enableBtLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            // 用户打开蓝牙后无需特殊处理，可在连接界面继续操作
        }

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            // 用户拒绝通知权限时前台服务仍会运行，只是不显示进度通知，无需特殊处理
        }

    /** 开始延时/间隔拍摄前，确保通知权限已请求（Android 13+；拒绝只影响通知显示，不影响拍摄）。 */
    private fun maybeRequestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 应用固定深色主题：强制状态栏/导航栏图标为浅色（白色），
        // 否则系统浅色模式下图标是黑的，在深色背景上看不见
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        setContent {
            CanonRemoteTheme {
                MainScreen(
                    app = application as CanonRemoteApp,
                    onRequestPermissions = {
                        permissionLauncher.launch(BlePermissions.required())
                    },
                    onRequestEnableBt = {
                        enableBtLauncher.launch(BlePermissions.requestEnableIntent())
                    },
                    onBeforeStartShooting = { maybeRequestNotificationPermission() },
                    openDevices = { startActivity(Intent(this, DeviceActivity::class.java)) },
                    openSettings = { startActivity(Intent(this, SettingsActivity::class.java)) },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    app: CanonRemoteApp,
    onRequestPermissions: () -> Unit,
    onRequestEnableBt: () -> Unit,
    onBeforeStartShooting: () -> Unit,
    openDevices: () -> Unit,
    openSettings: () -> Unit,
) {
    val ble = app.ble
    val shooting = app.shooting

    val connectionState by ble.state.collectAsStateWithLifecycle()
    val error by ble.error.collectAsStateWithLifecycle()
    val hint by ble.hint.collectAsStateWithLifecycle()
    val delayed by shooting.delayedState.collectAsStateWithLifecycle()
    val interval by shooting.intervalState.collectAsStateWithLifecycle()

    val autoConnectAttempted = remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (autoConnectAttempted.value) return@LaunchedEffect
        autoConnectAttempted.value = true
        if (app.prefs.rememberedDevice != null && ble.state.value is ConnectionState.Disconnected) {
            if (BlePermissions.granted(app)) {
                if (BlePermissions.bluetoothEnabled(app)) {
                    app.connectRemembered()
                } else {
                    onRequestEnableBt()
                }
            } else {
                onRequestPermissions()
            }
        }
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("佳能遥控") },
                navigationIcon = {
                    IconButton(onClick = openDevices) {
                        Icon(
                            Icons.Filled.PhotoCamera,
                            contentDescription = "连接相机",
                            tint = MaterialTheme.colorScheme.secondary,
                        )
                    }
                },
                actions = {
                    IconButton(onClick = openSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = "设置")
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
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            ConnectionCard(connectionState, error, hint, onClick = openDevices)
            ControlsRow(connectionState, shooting)
            DelayedCard(delayed, connectionState, shooting, onBeforeStartShooting, app.prefs)
            IntervalCard(interval, connectionState, shooting, onBeforeStartShooting, app.prefs)
            HintCard()
            Spacer(Modifier.height(12.dp))
        }
    }
}

// ———————————————————— 使用提示 ————————————————————

@Composable
private fun HintCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        ),
    ) {
        Text(
            "使用说明\n" +
                "① 相机菜单：选择“连接至无线遥控器” → “添加要连接的设备”，即可进入配对模式\n" +
                "② 应用内点击“扫描设备”，选择列表中的佳能相机即可配对连接\n" +
                "③ 连接失败请先唤醒相机（半按快门或点亮屏幕）再重试\n" +
                "④ 快门与 AF 支持按住：按住期间发送“按下”，松开发送“松开”",
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            lineHeight = 20.sp,
        )
    }
}

// ———————————————————— 连接状态卡片 ————————————————————

@Composable
private fun ConnectionCard(state: ConnectionState, error: String?, hint: String?, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            when (state) {
                is ConnectionState.Connected -> {
                    Icon(
                        Icons.Filled.Bluetooth,
                        contentDescription = null,
                        tint = Color(0xFF4ADE80),
                        modifier = Modifier.size(22.dp),
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(
                        "已连接 · ${state.deviceName ?: "相机"}",
                        style = MaterialTheme.typography.bodyLarge,
                        color = Color(0xFF4ADE80),
                    )
                }

                is ConnectionState.Connecting -> {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(
                        "正在连接 ${state.deviceName ?: "相机"}…",
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }

                ConnectionState.Disconnected -> {
                    Icon(
                        Icons.Filled.BluetoothDisabled,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(22.dp),
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(
                        "未连接 · 点击左上角相机图标连接",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        if (hint != null) {
            Text(
                hint!!,
                modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 12.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.tertiary,
            )
        }
        if (error != null) {
            Text(
                error!!,
                modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 12.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

// ———————————————————— 遥控按键区 ————————————————————

@Composable
private fun ControlsRow(state: ConnectionState, shooting: com.cannonremote.app.shooting.ShootingController) {
    val connected = state is ConnectionState.Connected
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 对焦按钮（按住对焦，松开取消）
        HoldableCircleButton(
            size = 116.dp,
            enabled = connected,
            onDown = { shooting.pressFocus() },
            onUp = { shooting.releaseFocus() },
        ) {
            Text(
                "AF",
                fontSize = 26.sp,
                color = if (connected) MaterialTheme.colorScheme.secondary else Color(0xFF55555C),
            )
        }
        // 快门按钮（按住拍摄，松开结束曝光）：快门光圈图标，避免误读为“录像”的实心圆
        HoldableCircleButton(
            size = 188.dp,
            enabled = connected,
            onDown = { shooting.pressShutter() },
            onUp = { shooting.releaseShutter() },
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_shutter),
                contentDescription = "快门",
                modifier = Modifier.size(112.dp),
                tint = if (connected) MaterialTheme.colorScheme.primary else Color(0xFF55555C),
            )
        }
    }
}

/**
 * 可长按的圆形按钮：按下触发 onDown，抬起/取消触发 onUp。
 * 对应遥控器按键的“按下/松开”两个指令。
 */
@Composable
fun HoldableCircleButton(
    size: Dp,
    enabled: Boolean,
    onDown: () -> Unit,
    onUp: () -> Unit,
    content: @Composable () -> Unit,
) {
    var pressed by remember { mutableStateOf(false) }
    val haptics = LocalHapticFeedback.current
    val scale by androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (pressed) 0.94f else 1f,
        label = "buttonScale",
    )
    Box(
        modifier = Modifier
            .size(size)
            .scale(scale)
            .clip(CircleShape)
            .background(
                when {
                    !enabled -> MaterialTheme.colorScheme.surface
                    pressed -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
                    else -> MaterialTheme.colorScheme.surfaceVariant
                }
            )
            .border(
                width = 3.dp,
                color = if (enabled) MaterialTheme.colorScheme.primary else Color(0xFF3A3A42),
                shape = CircleShape,
            )
            .then(
                if (enabled) {
                    Modifier.pointerInput(Unit) {
                        awaitEachGesture {
                            awaitFirstDown(requireUnconsumed = false)
                            pressed = true
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            onDown()
                            // 抬起或手势取消（移出按钮）都发送“松开指令”
                            waitForUpOrCancellation()
                            pressed = false
                            onUp()
                        }
                    }
                } else {
                    Modifier
                }
            ),
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}

// ———————————————————— 延时单拍 ————————————————————

@Composable
private fun DelayedCard(
    state: DelayedState,
    connectionState: ConnectionState,
    shooting: com.cannonremote.app.shooting.ShootingController,
    onBeforeStart: () -> Unit,
    prefs: AppPrefs,
) {
    val connected = connectionState is ConnectionState.Connected
    var secondsText by remember { mutableStateOf(prefs.delaySeconds.toString()) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.Timer,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text("延时拍摄", style = MaterialTheme.typography.titleMedium)
            }

            if (state.running) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        "${state.remainingSeconds}",
                        fontSize = 64.sp,
                        color = MaterialTheme.colorScheme.tertiary,
                        textAlign = TextAlign.Center,
                    )
                    Text("秒后拍摄一张", style = MaterialTheme.typography.bodyMedium)
                    OutlinedButton(onClick = { shooting.cancelDelayedShot() }) {
                        Text("取消")
                    }
                }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = secondsText,
                        onValueChange = { raw ->
                            val filtered = raw.filter { c -> c.isDigit() }.take(4)
                            secondsText = filtered
                            // 有效值即记忆，下次打开沿用
                            filtered.toIntOrNull()?.takeIf { it > 0 }?.let { prefs.delaySeconds = it }
                        },
                        modifier = Modifier.width(120.dp),
                        label = { Text("延时(秒)") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        enabled = true,
                    )
                    Spacer(Modifier.width(16.dp))
                    Button(
                        onClick = {
                            val seconds = secondsText.toIntOrNull() ?: return@Button
                            onBeforeStart()
                            shooting.startDelayedShot(seconds)
                        },
                        enabled = connected && (secondsText.toIntOrNull() ?: 0) > 0,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.tertiary,
                            contentColor = Color(0xFF101828),
                        ),
                    ) {
                        Text("开始")
                    }
                }
                if (!connected) {
                    Text(
                        "连接相机后可用",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

// ———————————————————— 间隔拍摄 ————————————————————

@Composable
private fun IntervalCard(
    state: IntervalState,
    connectionState: ConnectionState,
    shooting: com.cannonremote.app.shooting.ShootingController,
    onBeforeStart: () -> Unit,
    prefs: AppPrefs,
) {
    val connected = connectionState is ConnectionState.Connected
    var periodText by remember { mutableStateOf(prefs.intervalSeconds.toString()) }
    var shotsText by remember { mutableStateOf(prefs.intervalShots.toString()) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.ViewColumn,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text("间隔拍摄", style = MaterialTheme.typography.titleMedium)
            }

            if (state.running) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            "第 ${state.shotsDone} / ${state.shotsTotal} 张",
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Text(
                            "下一张 ${state.secondsToNext}s",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.tertiary,
                        )
                    }
                    LinearProgressIndicator(
                        progress = {
                            if (state.shotsTotal > 0) state.shotsDone.toFloat() / state.shotsTotal else 0f
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedButton(
                        onClick = { shooting.stopIntervalShooting() },
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    ) {
                        Text("停止")
                    }
                }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = periodText,
                        onValueChange = { raw ->
                            val filtered = raw.filter { c -> c.isDigit() }.take(4)
                            periodText = filtered
                            filtered.toIntOrNull()?.takeIf { it > 0 }?.let { prefs.intervalSeconds = it }
                        },
                        modifier = Modifier.weight(1f),
                        label = { Text("间隔(秒)") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    )
                    Spacer(Modifier.width(12.dp))
                    OutlinedTextField(
                        value = shotsText,
                        onValueChange = { raw ->
                            val filtered = raw.filter { c -> c.isDigit() }.take(4)
                            shotsText = filtered
                            filtered.toIntOrNull()?.takeIf { it > 0 }?.let { prefs.intervalShots = it }
                        },
                        modifier = Modifier.weight(1f),
                        label = { Text("张数") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    )
                }
                Button(
                    onClick = {
                        val period = periodText.toIntOrNull() ?: return@Button
                        val shots = shotsText.toIntOrNull() ?: return@Button
                        onBeforeStart()
                        shooting.startIntervalShooting(period, shots)
                    },
                    enabled = connected &&
                        (periodText.toIntOrNull() ?: 0) > 0 &&
                        (shotsText.toIntOrNull() ?: 0) > 0,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("开始间隔拍摄")
                }
                if (!connected) {
                    Text(
                        "连接相机后可用",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

