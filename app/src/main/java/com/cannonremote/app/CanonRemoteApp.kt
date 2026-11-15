package com.cannonremote.app

import android.app.Application
import android.bluetooth.BluetoothDevice
import com.cannonremote.app.ble.CanonBleManager
import com.cannonremote.app.data.AppPrefs
import com.cannonremote.app.shooting.ShootingController

class CanonRemoteApp : Application() {

    lateinit var prefs: AppPrefs
        private set
    lateinit var ble: CanonBleManager
        private set
    lateinit var shooting: ShootingController
        private set

    override fun onCreate() {
        super.onCreate()
        prefs = AppPrefs(this)
        ble = CanonBleManager(this)
        shooting = ShootingController(this, ble) { prefs.shutterHoldMs }
    }

    /** 连接已记住的相机。返回是否发起了连接。 */
    fun connectRemembered(): Boolean {
        val remembered = prefs.rememberedDevice ?: return false
        val device: BluetoothDevice = try {
            ble.adapter?.getRemoteDevice(remembered.address) ?: return false
        } catch (_: Exception) {
            return false
        }
        ble.connect(device, remembered.name.ifBlank { null })
        return true
    }
}
