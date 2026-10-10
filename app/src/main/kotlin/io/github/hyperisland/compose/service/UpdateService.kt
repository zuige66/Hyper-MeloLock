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
     * 检查更新：先走 GitHub Releases API（4s 超时，连不上不等——HyperDuo 实测 api.github.com
     * 在国内通常 1.5s 内响应，超时基本等于不通）；失败自动回退 blog 的 latest.json（Hexo 静态源，
     * 按 versionCode 比较）。两个源都失败才抛错弹失败框。
     *
     * **主源必须是本模块仓库**：曾错指上游 1812z/HyperIsland（迁移遗留），GitHub 通了比对的也是
     * 别人家的 release，每次检查白等（2026-10-10 借鉴 HyperDuo UpdateController 时发现并修正）。
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
            // APK 直链：优先 api.github.com 资产端点（HyperDuo 实测 github.com 的
            // browser_download_url 在国内间歇性握手失败，资产端点 10/10 通；
            // 下载时带 Accept: application/octet-stream，见 UpdateDownloadService），
            // browser 直链作第二层，blog 静态源第三层（下载失败时服务内懒取，见其 tier3）。
            var assetId = 0L
            var browserUrl = ""
            val assets = release.optJSONArray("assets")
            if (assets != null) {
                for (index in 0 until assets.length()) {
                    val asset = assets.optJSONObject(index) ?: continue
                    val assetUrl = asset.optString("browser_download_url").orEmpty()
                    if (!assetUrl.endsWith(".apk", ignoreCase = true)) continue
                    assetId = asset.optLong("id")
                    browserUrl = assetUrl
                    break
                }
            }
            val primaryUrl = if (assetId > 0L) ASSET_API + assetId else browserUrl
            Log.i(TAG, "GitHub release v$remoteVersion apk=" + (primaryUrl.ifBlank { "(none)" }))
            AppUpdate(
                version = remoteVersion,
                releaseUrl = downloadUrl,
                changelog = release.optString("body"),
                apkUrl = primaryUrl.ifBlank { downloadUrl },
                // 第二层：GitHub 浏览器直链（与主源同源不同端点；blog 第三层在下载服务内懒取）。
                fallbackApkUrl = browserUrl.ifBlank { null },
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

    /** blog 源的 APK 直链（第三层兜底，由 UpdateDownloadService 在主备两层都失败时懒取）。 */
    internal fun fetchBlogApkUrl(): String? {
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
    "https://api.github.com/repos/zuige66/Hyper-MeloLock/releases/latest"
/** GitHub 资产下载端点前缀：拼 assetId 得直链，下载时须带 Accept: application/octet-stream。 */
private const val ASSET_API =
    "https://api.github.com/repos/zuige66/Hyper-MeloLock/releases/assets/"
private const val MODULE_DOWNLOAD_URL =
    "https://hyperisland.1812z.top/downloads.html#module-download"
/** blog 回退源：Hexo 静态 JSON（zuige66 的 blog，部署在 GitHub Pages + 自定义域名）。 */
internal const val BLOG_LATEST_JSON =
    "https://blog.zuiges.com/downloads/melolock/latest.json"
/** GitHub 检查超时：4s（zuige 定标——api.github.com 通的话 1.5s 内，4s 不通就是不通，立刻走 blog）。 */
private const val NETWORK_TIMEOUT_MILLIS = 4_000
private const val BLOG_FALLBACK_TIMEOUT_MILLIS = 5_000
private const val VERSION_PART_COUNT = 3
