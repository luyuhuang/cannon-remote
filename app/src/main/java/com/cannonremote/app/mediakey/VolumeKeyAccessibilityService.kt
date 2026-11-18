package com.cannonremote.app.mediakey

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.util.Log
import android.view.KeyEvent
import android.widget.Toast
import com.cannonremote.app.CanonRemoteApp
import com.cannonremote.app.ble.ConnectionState
import com.cannonremote.app.data.VolumeKeyAction

/**
 * 熄屏音量键遥控的无障碍服务（按键过滤）。
 *
 * MIUI 不把音量键分发给后台应用的 MediaSession（实测），改走系统输入管道最前端的
 * 无障碍按键过滤（flagRequestFilterKeyEvents）：锁屏/任意界面都能收到音量键的
 * 按下与抬起事件，且返回 true 可拦截（不调节音量）。
 *
 * 生命周期由系统管理（用户在系统设置中开启/关闭），是否生效由“熄屏音量键”设置
 * 开关与连接状态共同决定；未生效的按键一律放行，保持系统音量功能。
 */
class VolumeKeyAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.d(TAG, "无障碍服务已连接")
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        Log.d(TAG, "无障碍服务已解绑")
        return super.onUnbind(intent)
    }

    override fun onAccessibilityEvent(event: android.view.accessibility.AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    override fun onKeyEvent(event: KeyEvent?): Boolean {
        if (event == null) return false
        val app = application as? CanonRemoteApp ?: return false
        if (!app.prefs.screenOffVolumeKeys) return false

        val action = when (event.keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP -> app.prefs.volumeUpAction
            KeyEvent.KEYCODE_VOLUME_DOWN -> app.prefs.volumeDownAction
            else -> return false
        }
        if (action == VolumeKeyAction.NONE) return false
        // 未连接时放行：保持音量键原功能
        if (app.ble.state.value !is ConnectionState.Connected) return false

        val shooting = app.shooting
        return when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                if (event.repeatCount > 0) return true // 长按重复算一次
                Log.d(TAG, "onKeyEvent DOWN keyCode=${event.keyCode} action=$action")
                when (action) {
                    VolumeKeyAction.SHUTTER -> shooting.pressShutter()
                    VolumeKeyAction.FOCUS -> shooting.pressFocus()
                    VolumeKeyAction.DELAYED -> {
                        shooting.startDelayedShot(app.prefs.delaySeconds)
                        feedback("延时拍摄")
                    }
                    VolumeKeyAction.INTERVAL -> {
                        val running = shooting.intervalState.value.running
                        if (running) {
                            shooting.stopIntervalShooting()
                            feedback("间隔拍摄：停止")
                        } else {
                            shooting.startIntervalShooting(
                                app.prefs.intervalSeconds,
                                app.prefs.intervalShots,
                            )
                            feedback("间隔拍摄：开始")
                        }
                    }
                    VolumeKeyAction.NONE -> Unit
                }
                true
            }

            KeyEvent.ACTION_UP -> {
                Log.d(TAG, "onKeyEvent UP keyCode=${event.keyCode} action=$action")
                // 按住语义：按住期间持续“按下”，抬起发送“松开”
                when (action) {
                    VolumeKeyAction.SHUTTER -> shooting.releaseShutter()
                    VolumeKeyAction.FOCUS -> shooting.releaseFocus()
                    else -> Unit
                }
                true
            }

            else -> false
        }
    }

    private fun feedback(text: String) {
        runCatching {
            Toast.makeText(applicationContext, "音量键 → $text", Toast.LENGTH_SHORT).show()
        }
    }

    companion object {
        private const val TAG = "VolumeKeyA11y"

        /** 本无障碍服务是否已在系统设置中开启 */
        fun isEnabled(context: Context): Boolean {
            val enabled = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            ) ?: return false
            val cn = ComponentName(context, VolumeKeyAccessibilityService::class.java)
            val full = cn.flattenToString()
            val short = cn.flattenToShortString()
            return enabled.split(':')
                .any { it.equals(full, ignoreCase = true) || it.equals(short, ignoreCase = true) }
        }
    }
}
