package com.cannonremote.app.shooting

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.cannonremote.app.ble.CanonBleManager
import com.cannonremote.app.ble.CanonProtocol
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** 延时单拍状态 */
data class DelayedState(
    val running: Boolean = false,
    val remainingSeconds: Int = 0,
)

/** 间隔拍摄状态 */
data class IntervalState(
    val running: Boolean = false,
    val shotsDone: Int = 0,
    val shotsTotal: Int = 0,
    val secondsToNext: Int = 0,
)

/**
 * 拍摄调度：
 * - 手动对焦/快门：按下发送“按下指令”，松开发送“松开指令”
 * - 拍一张：按下快门 → 等待 holdMs → 松开快门
 * - 延时单拍：N 秒倒计时后拍一张
 * - 间隔拍摄：立即拍第一张，之后每 N 秒拍一张，最多 M 张
 */
class ShootingController(
    context: Context,
    private val ble: CanonBleManager,
    private val holdMs: () -> Int,
) {
    private val appContext = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private val powerManager =
        context.getSystemService(Context.POWER_SERVICE) as PowerManager

    private val _delayed = MutableStateFlow(DelayedState())
    val delayedState: StateFlow<DelayedState> = _delayed

    private val _interval = MutableStateFlow(IntervalState())
    val intervalState: StateFlow<IntervalState> = _interval

    private var wakeLock: PowerManager.WakeLock? = null

    // —— 手动按键 ——

    fun pressShutter() {
        ble.writeCommand(CanonProtocol.SHUTTER_PRESS)
    }

    fun releaseShutter() {
        ble.writeCommand(CanonProtocol.SHUTTER_RELEASE)
    }

    fun pressFocus() {
        ble.writeCommand(CanonProtocol.FOCUS_PRESS)
    }

    fun releaseFocus() {
        ble.writeCommand(CanonProtocol.FOCUS_RELEASE)
    }

    /** 拍一张：按下快门 → holdMs → 松开快门 */
    fun takeShot() {
        ble.writeCommand(CanonProtocol.SHUTTER_PRESS)
        handler.postDelayed({ ble.writeCommand(CanonProtocol.SHUTTER_RELEASE) }, holdMs().toLong())
    }

    // —— 延时单拍 ——

    private val delayedTicker = Runnable { runDelayedTick() }
    private var delayedDeadlineElapsed = 0L

    fun startDelayedShot(seconds: Int) {
        if (seconds <= 0) return
        stopIntervalShooting()
        handler.removeCallbacks(delayedTicker)
        delayedDeadlineElapsed = SystemClock.elapsedRealtime() + seconds * 1000L
        _delayed.value = DelayedState(running = true, remainingSeconds = seconds)
        ensureForegroundService()
        handler.postDelayed(delayedTicker, TICK_MS)
    }

    fun cancelDelayedShot() {
        handler.removeCallbacks(delayedTicker)
        _delayed.value = DelayedState()
    }

    private fun runDelayedTick() {
        val st = _delayed.value
        if (!st.running) return
        val remainingMs = delayedDeadlineElapsed - SystemClock.elapsedRealtime()
        if (remainingMs <= 0) {
            _delayed.value = DelayedState()
            takeShot()
            return
        }
        _delayed.value = DelayedState(running = true, remainingSeconds = remainingMsToSeconds(remainingMs))
        handler.postDelayed(delayedTicker, TICK_MS)
    }

    // —— 间隔拍摄 ——

    private val intervalTicker = Runnable { runIntervalTick() }
    private var intervalPeriodSec = 0
    private var nextShotAtElapsed = 0L

    fun startIntervalShooting(periodSeconds: Int, shots: Int) {
        if (periodSeconds <= 0 || shots <= 0) return
        cancelDelayedShot()
        handler.removeCallbacks(intervalTicker)
        intervalPeriodSec = periodSeconds
        acquireWakeLock()
        _interval.value = IntervalState(
            running = true,
            shotsDone = 0,
            shotsTotal = shots,
            secondsToNext = 0,
        )
        // 第一张立即拍
        nextShotAtElapsed = SystemClock.elapsedRealtime()
        ensureForegroundService()
        handler.postDelayed(intervalTicker, TICK_MS)
    }

    fun stopIntervalShooting() {
        handler.removeCallbacks(intervalTicker)
        releaseWakeLock()
        if (_interval.value.running) {
            _interval.value = _interval.value.copy(running = false, secondsToNext = 0)
        }
    }

    private fun runIntervalTick() {
        val st = _interval.value
        if (!st.running) return
        val now = SystemClock.elapsedRealtime()
        if (now >= nextShotAtElapsed) {
            takeShot()
            val done = st.shotsDone + 1
            if (done >= st.shotsTotal) {
                _interval.value = IntervalState(
                    running = false,
                    shotsDone = done,
                    shotsTotal = st.shotsTotal,
                    secondsToNext = 0,
                )
                releaseWakeLock()
                return
            }
            nextShotAtElapsed += intervalPeriodSec * 1000L
            _interval.value = st.copy(
                shotsDone = done,
                secondsToNext = intervalPeriodSec,
            )
        } else {
            _interval.value = st.copy(
                secondsToNext = remainingMsToSeconds(nextShotAtElapsed - now),
            )
        }
        handler.postDelayed(intervalTicker, TICK_MS)
    }

    // —— 工具 ——

    /** 拍摄开始时拉起前台服务：锁屏/后台不被系统冻结，并显示常驻进度通知。 */
    private fun ensureForegroundService() {
        runCatching {
            ContextCompat.startForegroundService(
                appContext,
                Intent(appContext, IntervalShootingService::class.java),
            )
        }
    }

    private fun remainingMsToSeconds(ms: Long): Int = ((ms + 999) / 1000).toInt()

    companion object {
        /** 状态刷新/倒计时 tick 间隔 */
        private const val TICK_MS = 200L
    }

    private fun acquireWakeLock() {
        if (wakeLock == null) {
            wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "canonremote:interval_shooting",
            ).apply { setReferenceCounted(false) }
        }
        // 上限 4 小时，防止异常情况下永久耗电
        wakeLock?.acquire(4 * 60 * 60 * 1000L)
    }

    private fun releaseWakeLock() {
        runCatching { wakeLock?.release() }
    }
}
