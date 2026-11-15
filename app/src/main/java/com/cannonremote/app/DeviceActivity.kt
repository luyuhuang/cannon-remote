package com.cannonremote.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.BluetoothSearching
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cannonremote.app.ble.ConnectionState
import com.cannonremote.app.ble.isCanonDevice
import com.cannonremote.app.data.AppPrefs
import com.cannonremote.app.ui.BlePermissions
import com.cannonremote.app.ui.theme.CanonRemoteTheme
import kotlinx.coroutines.delay

/** 扫描列表条目 */
private data class ScannedDevice(
    val address: String,
    val name: String,
    val rssi: Int,
    val isCanon: Boolean,
)

class DeviceActivity : ComponentActivity() {

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
            if (granted.values.all { it }) {
                doScan = true
            }
        }

    private val enableBtLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { }

    // 扫描按钮点击后先请求权限，授权成功时置位触发扫描
    private var doScan by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 应用固定深色主题：强制状态栏/导航栏图标为浅色（白色）
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        setContent {
            CanonRemoteTheme {
                DeviceScreen(
                    app = application as CanonRemoteApp,
                    doScan = doScan,
                    onScanRequested = {
                        if (BlePermissions.granted(this)) {
                            doScan = true
                        } else {
                            permissionLauncher.launch(BlePermissions.required())
                        }
                    },
                    onStopScan = { doScan = false },
                    onRequestEnableBt = {
                        enableBtLauncher.launch(BlePermissions.requestEnableIntent())
                    },
                    onBack = { finish() },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DeviceScreen(
    app: CanonRemoteApp,
    doScan: Boolean,
    onScanRequested: () -> Unit,
    onStopScan: () -> Unit,
    onRequestEnableBt: () -> Unit,
    onBack: () -> Unit,
) {
    val ble = app.ble
    val connectionState by ble.state.collectAsStateWithLifecycle()
    val error by ble.error.collectAsStateWithLifecycle()
    val hint by ble.hint.collectAsStateWithLifecycle()
    val scanning by ble.scanning.collectAsStateWithLifecycle()

    val devices = remember { mutableStateListOf<ScannedDevice>() }
    var remembered by remember { mutableStateOf(app.prefs.rememberedDevice) }
    var pickedAddress by remember { mutableStateOf<String?>(null) }

    // 扫描开关由 Activity 权限回调驱动
    LaunchedEffect(doScan) {
        if (doScan && !scanning) {
            devices.clear()
            ble.startScan { result ->
                // 用广播数据中的名字（device.name 需要 BLUETOOTH_CONNECT 权限，扫描时可能还没授予）
                val name = result.scanRecord?.deviceName
                if (name.isNullOrBlank()) return@startScan // 只显示有名字的设备
                val item = ScannedDevice(
                    address = result.device.address,
                    name = name,
                    rssi = result.rssi,
                    isCanon = result.isCanonDevice(),
                )
                val existing = devices.indexOfFirst { it.address == item.address }
                if (existing >= 0) {
                    devices[existing] = item
                } else {
                    devices.add(item)
                }
            }
        } else if (!doScan && scanning) {
            ble.stopScan()
        }
    }

    // 扫描 15 秒自动停止
    LaunchedEffect(scanning) {
        if (scanning) {
            delay(15_000)
            if (ble.scanning.value) onStopScan()
        }
    }

    // 用户从列表选了设备：连接成功后自动返回主界面
    LaunchedEffect(connectionState) {
        if (pickedAddress != null && connectionState is ConnectionState.Connected) {
            onBack()
        }
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("连接相机") },
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
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // —— 当前设备卡片 ——
            RememberedCard(
                remembered = remembered,
                connectionState = connectionState,
                onReconnect = { app.connectRemembered() },
                onDisconnect = { ble.disconnect() },
                onForget = {
                    ble.disconnect()
                    app.prefs.rememberedDevice = null
                    remembered = null
                },
            )

            hint?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }

            error?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            // —— 扫描区 ——
            if (!BlePermissions.bluetoothEnabled(app)) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                ) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("蓝牙未开启", style = MaterialTheme.typography.titleSmall)
                        Button(onClick = onRequestEnableBt) { Text("打开蓝牙") }
                    }
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Button(
                    onClick = { if (scanning) onStopScan() else onScanRequested() },
                    colors = if (scanning) ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    ) else ButtonDefaults.buttonColors(),
                ) {
                    if (scanning) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("停止扫描")
                    } else {
                        Icon(Icons.AutoMirrored.Filled.BluetoothSearching, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("扫描设备")
                    }
                }
                Spacer(Modifier.width(12.dp))
                if (scanning) {
                    Text("正在搜索…", style = MaterialTheme.typography.bodyMedium)
                }
            }

            Text(
                "提示：若为首次配对，请先把相机蓝牙功能设为 Remote 并进入配对（Pairing）模式，" +
                    "再点击下方列表中的相机；已配对的相机可直接点击连接。带“佳能”标记的是广播中含佳能服务的设备。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // —— 设备列表 ——
            LazyColumn(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(devices, key = { it.address }) { device ->
                    DeviceRow(
                        device = device,
                        picked = device.address == pickedAddress,
                        onClick = {
                            pickedAddress = device.address
                            onStopScan()
                            app.prefs.rememberedDevice =
                                AppPrefs.RememberedDevice(device.address, device.name)
                            remembered = app.prefs.rememberedDevice
                            app.connectRemembered()
                        },
                    )
                }
            }

            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun RememberedCard(
    remembered: AppPrefs.RememberedDevice?,
    connectionState: ConnectionState,
    onReconnect: () -> Unit,
    onDisconnect: () -> Unit,
    onForget: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("当前设备", style = MaterialTheme.typography.titleSmall)

            if (remembered == null) {
                Text(
                    "未设置。扫描并点击列表中的相机即可配对并记住。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(
                                when (connectionState) {
                                    is ConnectionState.Connected -> Color(0xFF4ADE80)
                                    is ConnectionState.Connecting -> MaterialTheme.colorScheme.tertiary
                                    ConnectionState.Disconnected -> Color(0xFF55555C)
                                }
                            ),
                    )
                    Spacer(Modifier.width(10.dp))
                    Column {
                        Text(remembered.name, style = MaterialTheme.typography.bodyLarge)
                        Text(
                            remembered.address,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (remembered != null && connectionState is ConnectionState.Disconnected) {
                    Button(onClick = onReconnect) { Text("重新连接") }
                }
                if (connectionState !is ConnectionState.Disconnected) {
                    OutlinedButton(onClick = onDisconnect) { Text("断开连接") }
                }
                if (remembered != null) {
                    OutlinedButton(
                        onClick = onForget,
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.error,
                        ),
                    ) {
                        Icon(Icons.Filled.LinkOff, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("忘记设备")
                    }
                }
            }
        }
    }
}

@Composable
private fun DeviceRow(device: ScannedDevice, picked: Boolean, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = if (picked) MaterialTheme.colorScheme.surfaceVariant
            else MaterialTheme.colorScheme.surface,
        ),
        shape = RoundedCornerShape(12.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(device.name, style = MaterialTheme.typography.bodyLarge)
                    if (device.isCanon) {
                        Spacer(Modifier.width(8.dp))
                        AssistChip(
                            onClick = {},
                            label = { Text("佳能", style = MaterialTheme.typography.labelSmall) },
                        )
                    }
                }
                Text(
                    "${device.address} · ${device.rssi} dBm",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (picked) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            } else {
                Icon(
                    Icons.Filled.Bluetooth,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}
