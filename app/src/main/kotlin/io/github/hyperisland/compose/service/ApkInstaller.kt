package io.github.hyperisland.compose.service

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Environment
import android.util.Log
import android.widget.Toast

/**
 * 更新包「下载并安装」：交给系统 DownloadManager 下载（有前台通知、断点、不受我们的进程
 * 生命周期影响），下载完成的广播里直接拉起系统安装界面。
 *
 * 为什么不用自己写 OkHttp 下载：下载过程可能被系统杀掉我们的进程，而 DownloadManager
 * 是系统服务；通知栏进度条也不需要自己维护。
 * 前提是 APK 直链（见 [UpdateService]：GitHub 源取 assets 的 .apk，blog 源本来就是直链）。
 */
internal object ApkInstaller {
    private const val TAG = "MeloLock[App]"

    fun downloadAndInstall(context: Context, apkUrl: String, fileName: String) {
        if (apkUrl.isBlank()) {
            Toast.makeText(context, "没有可用的下载链接", Toast.LENGTH_SHORT).show()
            return
        }
        val safeName = if (fileName.endsWith(".apk", ignoreCase = true)) fileName else "$fileName.apk"
        val request = DownloadManager.Request(Uri.parse(apkUrl)).apply {
            setTitle("Hyper MeloLock 更新")
            setDescription(safeName)
            setMimeType("application/vnd.android.package-archive")
            setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            // 公共下载目录：Android 10+ 走 MediaStore/DownloadManager 不需要存储权限
            setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, safeName)
            setAllowedOverMetered(true)
            setAllowedOverRoaming(true)
        }
        val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as? DownloadManager
        if (manager == null) {
            Toast.makeText(context, "下载服务不可用", Toast.LENGTH_SHORT).show()
            return
        }
        // 同名文件已存在时 DownloadManager 会另起一份（文件名后加 -1），这里不额外处理：
        // 旧包留在下载目录对用户无害，覆盖安装同名不同签名时会失败但仍是明确提示。
        val downloadId = manager.enqueue(request)
        Log.i(TAG, "Update download enqueued id=$downloadId url=$apkUrl")
        Toast.makeText(context, "开始下载，完成后会自动打开安装界面", Toast.LENGTH_LONG).show()

        // 下载完成 → 拉起安装。注册在 ApplicationContext 上，用完立刻注销，不泄漏。
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(receiverContext: Context, intent: Intent) {
                val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
                if (id != downloadId) return
                try {
                    receiverContext.unregisterReceiver(this)
                } catch (ignored: IllegalArgumentException) { }
                val uri = manager.getUriForDownloadedFile(downloadId) ?: return
                val status = manager.query(DownloadManager.Query().setFilterById(downloadId))?.use { cursor ->
                    if (cursor.moveToFirst()) cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)) else -1
                }
                if (status != DownloadManager.STATUS_SUCCESSFUL) {
                    Log.w(TAG, "Update download not successful, status=$status")
                    return
                }
                val install = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "application/vnd.android.package-archive")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                try {
                    receiverContext.startActivity(install)
                } catch (error: Throwable) {
                    Log.w(TAG, "Cannot open installer: ${error::class.simpleName}: ${error.message}")
                }
            }
        }
        val filter = IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            context.applicationContext.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.applicationContext.registerReceiver(receiver, filter)
        }
    }
}
