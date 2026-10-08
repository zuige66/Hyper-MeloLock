package io.github.hyperisland.compose.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.FileProvider
import io.github.hyperisland.R
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * 更新包下载服务（前台服务）。
 *
 * 为什么不交给系统 DownloadManager + 完成广播：那个广播只能由**我们自己的进程**接收，
 * 而 39MB 的下载期间 HyperOS 很可能把 App 进程回收掉（用户切走/锁屏），广播无人接收，
 * 于是「下载完了却没弹安装」——真机实测就是这个现象。前台服务在下载期间保证进程存活，
 * 下完同一进程里直接 startActivity 拉起安装界面，链路最短。
 */
internal class UpdateDownloadService : Service() {
    private val notificationManager by lazy { getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager }
    @Volatile private var cancelled = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val url = intent?.getStringExtra(EXTRA_URL)
        val fileName = intent?.getStringExtra(EXTRA_NAME) ?: "Hyper-MeloLock-update.apk"
        if (url.isNullOrBlank()) {
            stopSelf()
            return START_NOT_STICKY
        }
        ensureChannel()
        startForeground(NOTIFICATION_ID, progressNotification(0))
        Thread {
            val target = File(File(filesDir, "update").apply { mkdirs() }, fileName)
            try {
                download(url, target)
                if (cancelled) return@Thread
                notifyDone(target, launchInstall(target))
            } catch (error: Throwable) {
                Log.w(TAG, "Update download failed: ${error::class.simpleName}: ${error.message}")
                notifyFailed()
            } finally {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }.start()
        return START_NOT_STICKY
    }

    override fun onDestroy() { cancelled = true; super.onDestroy() }

    private fun download(url: String, target: File) {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = NETWORK_TIMEOUT_MILLIS
            readTimeout = NETWORK_TIMEOUT_MILLIS
            instanceFollowRedirects = true
        }
        try {
            val code = connection.responseCode
            if (code !in 200..299) throw java.io.IOException("HTTP $code")
            val total = connection.contentLength.coerceAtLeast(0)
            var downloaded = 0L
            var lastNotify = 0L
            connection.inputStream.use { input ->
                FileOutputStream(target).use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (!cancelled) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        output.write(buffer, 0, read)
                        downloaded += read
                        val now = System.currentTimeMillis()
                        if (now - lastNotify > 500) {
                            lastNotify = now
                            val percent = if (total > 0) (downloaded * 100 / total).toInt() else 0
                            notificationManager.notify(NOTIFICATION_ID, progressNotification(percent))
                        }
                    }
                }
            }
            if (cancelled) return
            Log.i(TAG, "Update downloaded ${target.length()} bytes -> ${target.name}")
        } finally {
            connection.disconnect()
        }
    }

    /** 拉起系统安装界面；没有「允许安装未知应用」权限时跳对应设置页（一次性引导）。 */
    private fun launchInstall(apk: File): Boolean {
        val uri: Uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", apk)
        val install = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, APK_MIME)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return try {
            startActivity(install)
            true
        } catch (error: Throwable) {
            Log.w(TAG, "Cannot open installer: ${error::class.simpleName}: ${error.message}")
            try {
                startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:$packageName"),
                    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            } catch (settingsError: Throwable) {
                Log.w(TAG, "Cannot open unknown-source settings: ${settingsError.message}")
            }
            false
        }
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        if (notificationManager.getNotificationChannel(CHANNEL_ID) != null) return
        notificationManager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "更新下载", NotificationManager.IMPORTANCE_LOW),
        )
    }

    private fun progressNotification(percent: Int) = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_launcher)
        .setContentTitle("正在下载 Hyper MeloLock 更新")
        .setContentText(if (percent > 0) "$percent%" else "准备中")
        .setProgress(100, percent, percent <= 0)
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .build()

    private fun notifyDone(apk: File, installed: Boolean) {
        notificationManager.notify(
            NOTIFICATION_ID,
            NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_launcher)
                .setContentTitle(if (installed) "下载完成，请在安装界面确认" else "下载完成，需要允许安装未知应用")
                .setContentText(apk.name)
                .setOngoing(false)
                .build(),
        )
    }

    private fun notifyFailed() {
        notificationManager.notify(
            NOTIFICATION_ID,
            NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_launcher)
                .setContentTitle("更新下载失败")
                .setContentText("请检查网络后重试，或用浏览器打开发布页手动下载")
                .setOngoing(false)
                .build(),
        )
    }

    companion object {
        private const val TAG = "MeloLock[App]"
        private const val CHANNEL_ID = "update_download"
        private const val NOTIFICATION_ID = 9101
        private const val APK_MIME = "application/vnd.android.package-archive"
        private const val NETWORK_TIMEOUT_MILLIS = 30_000
        const val EXTRA_URL = "extra_apk_url"
        const val EXTRA_NAME = "extra_apk_name"
    }
}
