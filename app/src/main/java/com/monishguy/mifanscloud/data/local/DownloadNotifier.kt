package com.monishguy.mifanscloud.data.local

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.monishguy.mifanscloud.R
import java.util.concurrent.atomic.AtomicInteger

/**
 * 下载进度通知（相册 / 录音共用）：
 * [start] 发进度通知并返回 id，[update] 更新进度，[finish] 发完成/失败通知。
 * Android 13+ 需 POST_NOTIFICATIONS 运行时权限（未授权时静默跳过，不影响下载）。
 */
object DownloadNotifier {

    private const val CHANNEL_ID = "download_progress"
    private val nextId = AtomicInteger(1000)

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = context.getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "下载进度",
                    NotificationManager.IMPORTANCE_LOW,
                ).apply { description = "相册/录音下载进度通知" },
            )
        }
    }

    private fun canPost(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = context.checkSelfPermission("android.permission.POST_NOTIFICATIONS") ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
            if (!granted) return false
        }
        return NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    fun start(context: Context, title: String, text: String): Int? {
        ensureChannel(context)
        if (!canPost(context)) return null
        val id = nextId.incrementAndGet()
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(0, 0, true)
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(id, notification) }
        return id
    }

    fun update(context: Context, id: Int?, title: String, text: String, progress: Int, max: Int) {
        if (id == null || !canPost(context)) return
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(max, progress, false)
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(id, notification) }
    }

    fun finish(context: Context, id: Int?, title: String, text: String, success: Boolean) {
        if (id == null || !canPost(context)) return
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(false)
            .setAutoCancel(true)
            .setProgress(0, 0, false)
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(id, notification) }
    }
}
