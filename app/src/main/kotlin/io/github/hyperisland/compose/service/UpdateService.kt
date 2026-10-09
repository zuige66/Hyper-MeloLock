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
    /** APK 直链（用于「下载并安装」）；GitHub 源取 assets 里的 .apk，blog 源直接用 apkUrl。 */
    val apkUrl: String = releaseUrl,
    /**
     * 备用下载直链（GitHub 检查成功时带上 blog 的 apkUrl）。GitHub 的 release 直链在
     * 国内基本下不动（K60 Pro 用户实测「下载失败」），下载服务主源失败后自动换它重试。
     */
    val fallbackApkUrl: String? = null,
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
                fetchFromBlog(currentVersionCode, currentVersion)
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
            // APK 直链：优先取 assets 里的 .apk（下载安装要用直链，releases 页是 HTML 不能下）
            var apkUrl = ""
            val assets = release.optJSONArray("assets")
            if (assets != null) {
                for (index in 0 until assets.length()) {
                    val assetUrl = assets.optJSONObject(index)?.optString("browser_download_url").orEmpty()
                    if (assetUrl.endsWith(".apk", ignoreCase = true)) { apkUrl = assetUrl; break }
                    if (apkUrl.isEmpty()) apkUrl = assetUrl
                }
            }
            Log.i(TAG, "GitHub release v$remoteVersion apk=" + (apkUrl.ifBlank { "(none)" }))
            AppUpdate(
                version = remoteVersion,
                releaseUrl = downloadUrl,
                changelog = release.optString("body"),
                apkUrl = apkUrl.ifBlank { downloadUrl },
                // blog 直链在国内可达，作为下载阶段的备用源；拉不到不阻塞检查更新本身。
                fallbackApkUrl = runCatching { fetchBlogApkUrl() }.getOrNull(),
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

    /**
     * blog 回退源：Hexo 静态 JSON（versionName/versionCode/changelog/apkUrl）。
     * **两个维度都要判**：versionCode 整数比较 + versionName 三段比较，任一更新即算新版——
     * GitHub 源只比较 versionName，若这里只比 code，两条源会给出相反结论
     * （真机实测：GitHub 403 回退 blog，本地 code 已等于线上但 name 更低，直接被判「已是最新」）。
     */
    private suspend fun fetchFromBlog(currentVersionCode: Int, currentVersion: String): AppUpdate? = withContext(Dispatchers.IO) {
        val manifest = fetchBlogManifest()
        val remoteCode = manifest.optInt("versionCode", 0)
        val remoteName = manifest.optString("versionName")
        val apkUrl = manifest.optString("apkUrl")
        if (remoteCode <= 0 || remoteName.isBlank() || apkUrl.isBlank()) {
            throw IOException("Blog latest.json is missing required fields")
        }
        val codeNewer = currentVersionCode > 0 && remoteCode > currentVersionCode
        val nameNewer = isNewer(remoteName, currentVersion)
        if (!codeNewer && !nameNewer) {
            Log.i(TAG, "Blog latest is v$remoteName (code $remoteCode); already up to date")
            return@withContext null   // 已是最新
        }
        Log.i(TAG, "Blog fallback hit: v$remoteName (code $remoteCode)")
        AppUpdate(
            version = remoteName,
            releaseUrl = apkUrl,
            changelog = manifest.optString("changelog"),
            apkUrl = apkUrl,
        )
    }

    /** 拉 blog 的 latest.json；超时比主流程短（它是备用源，不值得久等）。 */
    private fun fetchBlogManifest(): JSONObject {
        val connection = (URL(BLOG_LATEST_JSON).openConnection() as HttpURLConnection).apply {
            connectTimeout = BLOG_FALLBACK_TIMEOUT_MILLIS
            readTimeout = BLOG_FALLBACK_TIMEOUT_MILLIS
            requestMethod = "GET"
        }
        try {
            val responseCode = connection.responseCode
            if (responseCode != HttpURLConnection.HTTP_OK) {
                throw IOException("Blog latest.json request failed with HTTP $responseCode")
            }
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            return JSONObject(body)
        } finally {
            connection.disconnect()
        }
    }

    /** blog 源的 APK 直链（给 GitHub 源当下载备用；拉不到返回 null，由调用方忽略）。 */
    private fun fetchBlogApkUrl(): String? {
        val apkUrl = fetchBlogManifest().optString("apkUrl")
        return apkUrl.ifBlank { null }
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
private const val BLOG_FALLBACK_TIMEOUT_MILLIS = 5_000
private const val VERSION_PART_COUNT = 3
