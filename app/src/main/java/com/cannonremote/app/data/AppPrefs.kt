package com.cannonremote.app.data

import android.content.Context
import android.content.SharedPreferences

/** 音量键可映射的遥控功能 */
enum class VolumeKeyAction {
    NONE, SHUTTER, FOCUS, DELAYED, INTERVAL;

    /** 设置界面显示名 */
    val label: String get() = when (this) {
        NONE -> "无"
        SHUTTER -> "快门"
        FOCUS -> "对焦"
        DELAYED -> "延时拍摄"
        INTERVAL -> "间隔拍摄"
    }
}

/** 应用偏好：记住的相机设备、拍摄参数。 */
class AppPrefs(context: Context) {

    data class RememberedDevice(val address: String, val name: String)

    private val sp: SharedPreferences =
        context.getSharedPreferences("canon_remote_prefs", Context.MODE_PRIVATE)

    /** 记住的相机（配对成功后保存，下次启动自动连接）。 */
    var rememberedDevice: RememberedDevice?
        get() = sp.getString(KEY_DEVICE_ADDRESS, null)?.let {
            RememberedDevice(it, sp.getString(KEY_DEVICE_NAME, "").orEmpty())
        }
        set(value) {
            sp.edit().apply {
                if (value == null) {
                    remove(KEY_DEVICE_ADDRESS)
                    remove(KEY_DEVICE_NAME)
                } else {
                    putString(KEY_DEVICE_ADDRESS, value.address)
                    putString(KEY_DEVICE_NAME, value.name)
                }
            }.apply()
        }

    /**
     * “拍一张”时，按下快门指令与松开快门指令之间的间隔（毫秒）。
     * 一般 100–300ms，默认 150ms。
     */
    var shutterHoldMs: Int
        get() = sp.getInt(KEY_SHUTTER_HOLD_MS, DEFAULT_SHUTTER_HOLD_MS)
        set(value) {
            sp.edit()
                .putInt(KEY_SHUTTER_HOLD_MS, value.coerceIn(MIN_SHUTTER_HOLD_MS, MAX_SHUTTER_HOLD_MS))
                .apply()
        }

    /** 延时拍摄秒数（界面记忆） */
    var delaySeconds: Int
        get() = sp.getInt(KEY_DELAY_SECONDS, DEFAULT_DELAY_SECONDS)
        set(value) {
            sp.edit().putInt(KEY_DELAY_SECONDS, value.coerceIn(1, MAX_DELAY_SECONDS)).apply()
        }

    /** 间隔拍摄周期秒数（界面记忆） */
    var intervalSeconds: Int
        get() = sp.getInt(KEY_INTERVAL_SECONDS, DEFAULT_INTERVAL_SECONDS)
        set(value) {
            sp.edit().putInt(KEY_INTERVAL_SECONDS, value.coerceIn(1, MAX_INTERVAL_SECONDS)).apply()
        }

    /** 间隔拍摄张数（界面记忆） */
    var intervalShots: Int
        get() = sp.getInt(KEY_INTERVAL_SHOTS, DEFAULT_INTERVAL_SHOTS)
        set(value) {
            sp.edit().putInt(KEY_INTERVAL_SHOTS, value.coerceIn(1, MAX_INTERVAL_SHOTS)).apply()
        }

    /** 音量+ 键映射的功能（默认无） */
    var volumeUpAction: VolumeKeyAction
        get() = volumeAction(KEY_VOLUME_UP_ACTION)
        set(value) {
            sp.edit().putString(KEY_VOLUME_UP_ACTION, value.name).apply()
        }

    /** 音量- 键映射的功能（默认无） */
    var volumeDownAction: VolumeKeyAction
        get() = volumeAction(KEY_VOLUME_DOWN_ACTION)
        set(value) {
            sp.edit().putString(KEY_VOLUME_DOWN_ACTION, value.name).apply()
        }

    /**
     * 熄屏/应用在后台时音量键仍可用。
     * 由无障碍按键过滤服务（VolumeKeyAccessibilityService）在运行时读取生效。
     */
    var screenOffVolumeKeys: Boolean
        get() = sp.getBoolean(KEY_SCREEN_OFF_VOLUME_KEYS, false)
        set(value) {
            sp.edit().putBoolean(KEY_SCREEN_OFF_VOLUME_KEYS, value).apply()
        }

    private fun volumeAction(key: String): VolumeKeyAction =
        sp.getString(key, null)?.let { name ->
            runCatching { VolumeKeyAction.valueOf(name) }.getOrNull()
        } ?: VolumeKeyAction.NONE

    companion object {
        const val DEFAULT_SHUTTER_HOLD_MS = 150
        const val MIN_SHUTTER_HOLD_MS = 20
        const val MAX_SHUTTER_HOLD_MS = 2000

        const val DEFAULT_DELAY_SECONDS = 5
        const val MAX_DELAY_SECONDS = 3600

        const val DEFAULT_INTERVAL_SECONDS = 5
        const val MAX_INTERVAL_SECONDS = 3600

        const val DEFAULT_INTERVAL_SHOTS = 10
        const val MAX_INTERVAL_SHOTS = 9999

        private const val KEY_DEVICE_ADDRESS = "device_address"
        private const val KEY_DEVICE_NAME = "device_name"
        private const val KEY_SHUTTER_HOLD_MS = "shutter_hold_ms"
        private const val KEY_DELAY_SECONDS = "delay_seconds"
        private const val KEY_INTERVAL_SECONDS = "interval_seconds"
        private const val KEY_INTERVAL_SHOTS = "interval_shots"
        private const val KEY_VOLUME_UP_ACTION = "volume_up_action"
        private const val KEY_VOLUME_DOWN_ACTION = "volume_down_action"
        private const val KEY_SCREEN_OFF_VOLUME_KEYS = "screen_off_volume_keys"
    }
}
