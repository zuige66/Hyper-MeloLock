package io.github.hyperisland.compose.service

import android.content.Context
import android.content.Intent
import android.widget.Toast

/**
 * 更新包「下载并安装」的入口：把下载交给 [UpdateDownloadService]（前台服务，保证下载期间
 * 进程存活），由服务在下完后直接拉起系统安装界面。
 *
 * 早期版本走系统 DownloadManager + 完成广播，而那个广播只能由**本进程**接收，下载 39MB
 * 期间进程常被 HyperOS 回收 → 「下载完了却不弹安装」（真机实测），已改为前台服务方案。
 */
internal object ApkInstaller {
    fun downloadAndInstall(context: Context, apkUrl: String, fallbackUrl: String?, fileName: String) {
        if (apkUrl.isBlank()) {
            Toast.makeText(context, "没有可用的下载链接", Toast.LENGTH_SHORT).show()
            return
        }
        val safeName = if (fileName.endsWith(".apk", ignoreCase = true)) fileName else "$fileName.apk"
        val intent = Intent(context, UpdateDownloadService::class.java).apply {
            putExtra(UpdateDownloadService.EXTRA_URL, apkUrl)
            putExtra(UpdateDownloadService.EXTRA_FALLBACK_URL, fallbackUrl.orEmpty())
            putExtra(UpdateDownloadService.EXTRA_NAME, safeName)
        }
        try {
            context.startForegroundService(intent)
            Toast.makeText(context, "开始下载，完成后会自动打开安装界面", Toast.LENGTH_LONG).show()
        } catch (error: Throwable) {
            Toast.makeText(context, "无法启动下载：${error.message}", Toast.LENGTH_SHORT).show()
        }
    }
}
