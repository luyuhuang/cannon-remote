package com.cannonremote.app.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.util.Log
import android.os.Looper
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.ArrayDeque
import java.util.UUID

/** BLE 连接状态。 */
sealed interface ConnectionState {
    data object Disconnected : ConnectionState
    data class Connecting(val deviceName: String?) : ConnectionState
    data class Connected(val deviceName: String?) : ConnectionState
}

/**
 * 佳能相机 BLE 管理器：扫描、连接、配对、指令发送。
 *
 * 所有 GATT 操作都串行投递到主线程 Handler，写指令通过内部队列
 * 逐条发送（Android GATT 同一时间只允许一个未完成的写操作）。
 *
 * 配对说明：配对包（0x03+名字）使用“带响应写”——相机若要求加密，
 * 会返回 INSUFFICIENT_AUTHENTICATION/ENCRYPTION，此时自动发起系统绑定
 * （createBond），绑定完成后再重发配对包；只有相机真正应答后才算“已连接”。
 * 快门/对焦指令使用“无响应写”（参考 cbremote 实测）。
 */
class CanonBleManager(private val context: Context) {

    private val handler = Handler(Looper.getMainLooper())
    private val bluetoothManager =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager

    val adapter: BluetoothAdapter? get() = bluetoothManager.adapter

    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val state: StateFlow<ConnectionState> = _state

    /** 最近一次错误提示（供界面显示），null 表示无错误 */
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    /** 非错误的流程提示（如“请在系统弹窗中确认配对”） */
    private val _hint = MutableStateFlow<String?>(null)
    val hint: StateFlow<String?> = _hint

    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning

    private var gatt: BluetoothGatt? = null
    private var pendingDevice: BluetoothDevice? = null
    private var pendingDeviceName: String? = null
    private var userRequestedDisconnect = false
    private var retries = 0
    private var retryRunnable: Runnable? = null
    private var bondingRequested = false

    // —— 写队列 ——
    private class WriteOp(
        val payload: ByteArray,
        /** 该条写入的目标特征：配对包 → 00050002，按键指令 → 00050003 */
        val target: UUID,
        val markConnected: Boolean = false,
        /** true = 带响应写（配对包）；false = 无响应写（按键指令） */
        val secure: Boolean = false,
    )

    private val writeQueue = ArrayDeque<WriteOp>()
    private var currentOp: WriteOp? = null

    @Volatile
    private var scanListener: ((ScanResult) -> Unit)? = null

    /** 系统绑定状态监听：绑定成功后重发配对包 */
    private val bondReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            if (intent.action != BluetoothDevice.ACTION_BOND_STATE_CHANGED) return
            val device = IntentCompat.getParcelableExtra(
                intent, BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java,
            ) ?: return
            if (device.address != pendingDevice?.address) return
            when (device.bondState) {
                BluetoothDevice.BOND_BONDED -> handler.post {
                    bondingRequested = false
                    _hint.value = null
                    if (_state.value is ConnectionState.Connecting) {
                        enqueue(
                            WriteOp(
                                CanonProtocol.pairingPayload(),
                                CanonProtocol.CHARACTERISTIC_PAIRING,
                                markConnected = true,
                                secure = true,
                            )
                        )
                    }
                }

                BluetoothDevice.BOND_NONE -> handler.post {
                    bondingRequested = false
                    if (_state.value is ConnectionState.Connecting && !userRequestedDisconnect) {
                        fail("系统蓝牙配对未完成，无法连接相机")
                    }
                }
            }
        }
    }

    init {
        ContextCompat.registerReceiver(
            context,
            bondReceiver,
            IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED),
            ContextCompat.RECEIVER_EXPORTED,
        )
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            scanListener?.invoke(result)
        }

        override fun onScanFailed(errorCode: Int) {
            _scanning.value = false
            scanListener = null
            _error.value = "扫描失败 (errorCode=$errorCode)"
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            Log.d(TAG, "onConnectionStateChange status=$status newState=$newState")
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    gatt = g
                    if (!g.discoverServices()) {
                        fail("无法启动服务发现")
                    }
                }

                BluetoothProfile.STATE_DISCONNECTED -> {
                    closeGattInternal()
                    if (userRequestedDisconnect) {
                        _state.value = ConnectionState.Disconnected
                    } else if (retries < MAX_RETRIES) {
                        // 意外断开：自动重试几次（相机息屏后 BLE 会下线）
                        val device = pendingDevice ?: run {
                            _state.value = ConnectionState.Disconnected
                            return
                        }
                        retries++
                        _state.value = ConnectionState.Connecting(pendingDeviceName)
                        val r = Runnable { reconnect(device) }
                        retryRunnable = r
                        handler.postDelayed(r, RETRY_DELAY_MS)
                    } else {
                        _state.value = ConnectionState.Disconnected
                        _error.value = "连接失败或已断开。请唤醒相机（半按快门/点亮屏幕）后，在连接界面重试。"
                    }
                }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            Log.d(TAG, "onServicesDiscovered status=$status services=${g.services.map { it.uuid }}")
            g.findCharacteristic(CanonProtocol.CHARACTERISTIC_PAIRING)?.let {
                Log.d(TAG, "pairing char properties=0x${Integer.toHexString(it.properties)}")
            }
            g.findCharacteristic(CanonProtocol.CHARACTERISTIC_EVENT)?.let {
                Log.d(TAG, "event char properties=0x${Integer.toHexString(it.properties)}")
            }
            if (status != BluetoothGatt.GATT_SUCCESS) {
                fail("服务发现失败 (status=$status)")
                return
            }
            val event = g.findCharacteristic(CanonProtocol.CHARACTERISTIC_EVENT)
            val pairing = g.findCharacteristic(CanonProtocol.CHARACTERISTIC_PAIRING)
            if (event == null || pairing == null) {
                fail("未找到佳能遥控服务。请确认相机蓝牙功能已设为 Remote 模式。")
                return
            }
            // 参考 canoremote/cbremote：每次连接都发送一次配对包；
            // 相机在配对模式下完成首次配对，已配对时则静默接受。
            // 注意：配对包必须写入配对特征 00050002（而非事件特征 00050003）。
            enqueue(
                WriteOp(
                    CanonProtocol.pairingPayload(),
                    CanonProtocol.CHARACTERISTIC_PAIRING,
                    markConnected = true,
                    secure = true,
                )
            )
        }

        override fun onCharacteristicWrite(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            val op = currentOp
            currentOp = null
            Log.d(
                TAG,
                "onCharacteristicWrite char=${characteristic.uuid} status=$status " +
                    "len=${op?.payload?.size}",
            )
            if (status == BluetoothGatt.GATT_INSUFFICIENT_AUTHENTICATION ||
                status == BluetoothGatt.GATT_INSUFFICIENT_ENCRYPTION
            ) {
                // 相机要求加密：发起系统绑定，绑定完成后由 bondReceiver 重发配对包
                requestBond()
                return
            }
            if (status != BluetoothGatt.GATT_SUCCESS) {
                writeQueue.clear()
                if (op?.markConnected == true) {
                    fail("配对写入失败 (status=$status)")
                } else {
                    _error.value = "指令写入失败 (status=$status)"
                }
                return
            }
            if (op?.markConnected == true) {
                retries = 0
                _hint.value = null
                _state.value = ConnectionState.Connected(pendingDeviceName)
            }
            drainWriteQueue()
        }
    }

    // ———————————————————— 连接管理 ————————————————————

    /** 连接指定相机（连接成功后自动发送配对包）。 */
    fun connect(device: BluetoothDevice, displayName: String? = null) {
        handler.post {
            userRequestedDisconnect = false
            retries = 0
            bondingRequested = false
            closeGattInternal()
            _error.value = null
            pendingDevice = device
            pendingDeviceName = displayName ?: runCatching { device.name }.getOrNull()
            _state.value = ConnectionState.Connecting(pendingDeviceName)
            try {
                gatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
            } catch (e: SecurityException) {
                gatt = null
                pendingDevice = null
                _state.value = ConnectionState.Disconnected
                _error.value = "缺少蓝牙权限：${e.message}"
            }
        }
    }

    /** 用户主动断开（不自动重连）。 */
    fun disconnect() {
        handler.post {
            userRequestedDisconnect = true
            closeGattInternal()
            _hint.value = null
            _state.value = ConnectionState.Disconnected
        }
    }

    private fun reconnect(device: BluetoothDevice) {
        retryRunnable = null
        if (userRequestedDisconnect || _state.value is ConnectionState.Connected) return
        closeGattInternal()
        try {
            gatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        } catch (e: SecurityException) {
            _state.value = ConnectionState.Disconnected
            _error.value = "缺少蓝牙权限：${e.message}"
        }
    }

    private fun fail(message: String) {
        closeGattInternal()
        _hint.value = null
        _state.value = ConnectionState.Disconnected
        _error.value = message
    }

    private fun closeGattInternal() {
        retryRunnable?.let { handler.removeCallbacks(it) }
        retryRunnable = null
        writeQueue.clear()
        currentOp = null
        runCatching { gatt?.close() }
        gatt = null
    }

    // ———————————————————— 配对加密处理 ————————————————————

    private fun requestBond() {
        val device = pendingDevice
        if (device == null) {
            fail("相机要求加密连接，但设备信息缺失")
            return
        }
        val bonded = runCatching { device.bondState }
            .getOrDefault(BluetoothDevice.BOND_NONE)
        if (bonded == BluetoothDevice.BOND_BONDED) {
            // 已绑定仍报权限不足：直接重试一次配对写入
            enqueue(
                WriteOp(
                    CanonProtocol.pairingPayload(),
                    CanonProtocol.CHARACTERISTIC_PAIRING,
                    markConnected = true,
                    secure = true,
                )
            )
            return
        }
        if (bondingRequested) return
        bondingRequested = true
        _hint.value = "相机要求加密连接：请在手机弹出的“蓝牙配对请求”中确认"
        Log.d(TAG, "requestBond createBond device=${device.address}")
        runCatching { device.createBond() }.onFailure {
            bondingRequested = false
            fail("发起系统配对失败：${it.message}")
        }
    }

    // ———————————————————— 指令发送 ————————————————————

    /** 写入一条按键指令（模式位|按键位，1 字节）。未连接时忽略。 */
    fun writeCommand(command: Int) {
        handler.post {
            if (_state.value is ConnectionState.Connected) {
                enqueue(
                    WriteOp(
                        byteArrayOf(command.toByte()),
                        CanonProtocol.CHARACTERISTIC_EVENT,
                    )
                )
            }
        }
    }

    private fun enqueue(op: WriteOp) {
        writeQueue.addLast(op)
        drainWriteQueue()
    }

    private fun drainWriteQueue() {
        if (currentOp != null) return
        val g = gatt ?: run {
            writeQueue.clear()
            return
        }
        val op = writeQueue.pollFirst() ?: return
        val characteristic =
            g.findCharacteristic(op.target) ?: run {
                writeQueue.clear()
                fail("找不到目标特征 (${op.target})")
                return
            }
        currentOp = op
        val ok = try {
            // 配对包用带响应写（相机拒绝时能拿到错误并触发绑定）；
            // 按键指令用无响应写。若特征不支持对应属性则回退。
            val writeType =
                if (op.secure && (characteristic.properties and BluetoothGattCharacteristic.PROPERTY_WRITE) != 0) {
                    BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                } else {
                    BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                }
            @Suppress("DEPRECATION")
            characteristic.writeType = writeType
            @Suppress("DEPRECATION")
            characteristic.value = op.payload
            @Suppress("DEPRECATION")
            g.writeCharacteristic(characteristic)
        } catch (e: Exception) {
            false
        }
        if (!ok) {
            currentOp = null
            writeQueue.clear()
            fail("指令写入失败")
        }
    }

    // ———————————————————— 扫描 ————————————————————

    /** 开始 BLE 扫描，每发现一个设备回调一次。返回是否成功启动。 */
    fun startScan(onFound: (ScanResult) -> Unit): Boolean {
        if (_scanning.value) stopScan()
        val scanner = adapter?.bluetoothLeScanner ?: run {
            _error.value = "蓝牙不可用"
            return false
        }
        scanListener = onFound
        return try {
            scanner.startScan(scanCallback)
            _scanning.value = true
            true
        } catch (e: SecurityException) {
            scanListener = null
            _error.value = "缺少蓝牙权限：${e.message}"
            false
        }
    }

    fun stopScan() {
        scanListener = null
        runCatching { adapter?.bluetoothLeScanner?.stopScan(scanCallback) }
        _scanning.value = false
    }

    companion object {
        private const val TAG = "CanonBle"
        private const val MAX_RETRIES = 2
        private const val RETRY_DELAY_MS = 3000L
    }
}

/** 在已发现的服务中按 UUID 查找特征（跨服务查找，兼容不同固件的分组方式）。 */
fun BluetoothGatt.findCharacteristic(uuid: UUID): BluetoothGattCharacteristic? =
    services.asSequence()
        .flatMap { it.characteristics.asSequence() }
        .firstOrNull { it.uuid == uuid }

/** 扫描结果是否来自佳能相机（广播中包含佳能 GATT 服务 UUID）。 */
fun ScanResult.isCanonDevice(): Boolean =
    scanRecord?.serviceUuids?.any {
        it.uuid.toString().endsWith(CanonProtocol.CANON_UUID_SUFFIX)
    } == true
