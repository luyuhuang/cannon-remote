package com.cannonremote.app.shooting

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.cannonremote.app.CanonRemoteApp
import com.cannonremote.app.MainActivity
import com.cannonremote.app.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * 延时/间隔拍摄的前台服务：
 * - 保证锁屏、应用退到后台时拍摄调度不被系统冻结或杀死（配合 WakeLock 保持 CPU 运行）
 * - 常驻通知显示拍摄进度，并提供“停止拍摄”快捷操作
 */
class IntervalShootingService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val app: CanonRemoteApp get() = application as CanonRemoteApp

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(null, null),
            if (Build.VERSION.SDK_INT >= 29) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            } else {
                0
            },
        )
        scope.launch {
            combine(app.shooting.intervalState, app.shooting.delayedState) { i, d -> i to d }
                .collect { (interval, delayed) ->
                    when {
                        interval.running -> {
                            val next =
                                if (interval.secondsToNext > 0) " · 下一张 ${interval.secondsToNext}s" else ""
                            updateNotification(
                                buildNotification(
                                    "间隔拍摄 · 第 ${interval.shotsDone}/${interval.shotsTotal} 张$next",
                                    interval,
                                )
                            )
                        }

                        delayed.running -> {
                            updateNotification(
                                buildNotification("延时拍摄 · ${delayed.remainingSeconds} 秒后拍摄", null)
                            )
                        }

                        else -> stopSelf()
                    }
                }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    // ———————————————————— 通知 ————————————————————

    private fun buildNotification(text: String?, interval: IntervalState?): Notification {
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                // 复用已存在的 MainActivity（若不在栈顶则清掉其上层），
                // 避免点击通知后栈里出现两个实例、按返回键“退不出应用”的问题
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP
                )
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_shutter)
            .setContentTitle("佳能遥控 · 拍摄中")
            .setContentText(text ?: "正在准备拍摄…")
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
        if (interval != null && interval.shotsTotal > 0) {
            builder.setProgress(interval.shotsTotal, interval.shotsDone, false)
        } else {
            builder.setProgress(0, 0, true)
        }
        return builder.build()
    }

    private fun updateNotification(notification: Notification) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification)
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "拍摄进度",
            // 用 DEFAULT 保证各 ROM 都会渲染操作按钮；同时关闭声音保持静默。
            // 注意：系统不更新已存在渠道的重要性，故改设置时需更换渠道 ID。
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = "延时/间隔拍摄的常驻进度通知"
            setSound(null, null)
            enableVibration(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    companion object {
        private const val CHANNEL_ID = "shooting_progress_v2"
        private const val NOTIFICATION_ID = 1
    }
}
