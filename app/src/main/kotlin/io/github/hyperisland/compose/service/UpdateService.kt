package io.github.hyperisland.compose.service

import android.util.Log
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

internal data class AppUpdate(
    val version: String,
    val releaseUrl: String,
    val changelog: String,
)

internal object UpdateService {
    private const val TAG = "MeloLock[App]"

    /**
     * 检查更新：先走 GitHub Releases API；失败（国内网络常见）自动回退 blog 的
     * latest.json（Hexo 静态源，按 versionCode 比较）。两个源都失败才抛错弹失败框。
     */
    suspend fun fetchIfNewer(
        currentVersion: String,
        currentVersionCode: Int = 0,
        api: String = LATEST_RELEASE_API,
        downloadUrl: String = MODULE_DOWNLOAD_URL,
    ): AppUpdate? = withContext(Dispatchers.IO) {
        try {
            fetchInternal(currentVersion, api, downloadUrl)
        } catch (error: Exception) {
            // 失败原因要落到日志（DNS 污染 / 连接超时 / TLS 重置 是三种不同的病，药方不同）
            Log.w(TAG, "GitHub update check failed: ${error::class.simpleName}: ${error.message}; trying blog fallback")
            try {
                fetchFromBlog(currentVersionCode)
            } catch (blogError: Exception) {
                Log.w(TAG, "Blog fallback failed too: ${blogError::class.simpleName}: ${blogError.message}")
                throw error   // 抛原始 GitHub 错误，失败弹窗语义不变
            }
        }
    }

    private suspend fun fetchInternal(
        currentVersion: String,
        api: String,
        downloadUrl: String,
    ): AppUpdate? = withContext(Dispatchers.IO) {
        val connection = (URL(api).openConnection() as HttpURLConnection).apply {
            connectTimeout = NETWORK_TIMEOUT_MILLIS
            readTimeout = NETWORK_TIMEOUT_MILLIS
            requestMethod = "GET"
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "HyperIsland/$currentVersion")
        }
        try {
            val responseCode = connection.responseCode
            if (responseCode != HttpURLConnection.HTTP_OK) {
                throw IOException("GitHub release request failed with HTTP $responseCode")
            }
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            val release = JSONObject(body)
            val remoteVersion = release.optString("tag_name").removePrefix("v")
            if (remoteVersion.isBlank() || !isNewer(remoteVersion, currentVersion)) {
                return@withContext null
            }
            AppUpdate(
                version = remoteVersion,
                releaseUrl = downloadUrl,
                changelog = release.optString("body"),
            )
        } finally {
            connection.disconnect()
        }
    }

    private fun isNewer(remote: String, current: String): Boolean {
        val remoteParts = versionParts(remote)
        val currentParts = versionParts(current)
        for (index in 0 until VERSION_PART_COUNT) {
            if (remoteParts[index] > currentParts[index]) return true
            if (remoteParts[index] < currentParts[index]) return false
        }
        return false
    }

    /** blog 回退源：Hexo 静态 JSON（versionName/versionCode/changelog/apkUrl），versionCode 整数比较。 */
    private suspend fun fetchFromBlog(currentVersionCode: Int): AppUpdate? = withContext(Dispatchers.IO) {
        val connection = (URL(BLOG_LATEST_JSON).openConnection() as HttpURLConnection).apply {
            connectTimeout = NETWORK_TIMEOUT_MILLIS
            readTimeout = NETWORK_TIMEOUT_MILLIS
            requestMethod = "GET"
        }
        try {
            val responseCode = connection.responseCode
            if (responseCode != HttpURLConnection.HTTP_OK) {
                throw IOException("Blog latest.json request failed with HTTP $responseCode")
            }
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            val manifest = JSONObject(body)
            val remoteCode = manifest.optInt("versionCode", 0)
            val remoteName = manifest.optString("versionName")
            val apkUrl = manifest.optString("apkUrl")
            if (remoteCode <= 0 || remoteName.isBlank() || apkUrl.isBlank()) {
                throw IOException("Blog latest.json is missing required fields")
            }
            if (currentVersionCode > 0 && remoteCode <= currentVersionCode) {
                return@withContext null   // 已是最新
            }
            Log.i(TAG, "Blog fallback hit: v$remoteName (code $remoteCode)")
            AppUpdate(version = remoteName, releaseUrl = apkUrl, changelog = manifest.optString("changelog"))
        } finally {
            connection.disconnect()
        }
    }

    private fun versionParts(version: String): List<Int> = version
        .removePrefix("v")
        .split('.')
        .take(VERSION_PART_COUNT)
        .map { part -> part.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }
        .let { parts -> List(VERSION_PART_COUNT) { index -> parts.getOrElse(index) { 0 } } }
}

private const val LATEST_RELEASE_API =
    "https://api.github.com/repos/1812z/HyperIsland/releases/latest"
private const val MODULE_DOWNLOAD_URL =
    "https://hyperisland.1812z.top/downloads.html#module-download"
/** blog 回退源：Hexo 静态 JSON（zuige66 的 blog，部署在 GitHub Pages + 自定义域名）。 */
private const val BLOG_LATEST_JSON =
    "https://blog.zuiges.com/downloads/melolock/latest.json"
private const val NETWORK_TIMEOUT_MILLIS = 10_000
private const val VERSION_PART_COUNT = 3
