package io.github.hyperisland.compose.data

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject

/**
 * 直接读写旧版 shared_preferences 存储格式，保证升级后配置无损。
 *
 * MeloLock 精简版：上游 HyperIsland 的灵岛/渠道/Toast/AI 配置等大段读写逻辑
 * 随死代码一并删除，只保留锁屏配置页仍在使用的基础读写与统计方法。
 */
class FlutterPrefsRepository(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences(
        PREFS_NAME,
        Context.MODE_PRIVATE,
    )

    fun getBoolean(key: String, default: Boolean = false): Boolean =
        runCatching { prefs.getBoolean(storageKey(key), default) }.getOrDefault(default)

    fun putBoolean(key: String, value: Boolean) {
        prefs.edit().putBoolean(storageKey(key), value).apply()
    }

    fun getString(key: String, default: String = ""): String =
        runCatching { prefs.getString(storageKey(key), default) ?: default }.getOrDefault(default)

    fun putString(key: String, value: String) {
        prefs.edit().putString(storageKey(key), value).apply()
    }

    fun getLong(key: String, default: Long = 0L): Long =
        runCatching { prefs.getLong(storageKey(key), default) }.getOrDefault(default)

    fun putLong(key: String, value: Long) {
        prefs.edit().putLong(storageKey(key), value).apply()
    }

    fun getDouble(key: String, default: Double): Double = runCatching {
        val raw = prefs.getString(storageKey(key), null) ?: return@runCatching default
        raw.removePrefix(FLUTTER_DOUBLE_PREFIX).toDoubleOrNull() ?: default
    }.getOrElse {
        runCatching { prefs.getFloat(storageKey(key), default.toFloat()).toDouble() }.getOrDefault(default)
    }

    fun putDouble(key: String, value: Double) {
        prefs.edit().putString(storageKey(key), FLUTTER_DOUBLE_PREFIX + value).apply()
    }

    fun remove(key: String) {
        prefs.edit().remove(storageKey(key)).apply()
    }

    fun addChangeListener(listener: (String) -> Unit): () -> Unit {
        val delegate = SharedPreferences.OnSharedPreferenceChangeListener { _, storageKey ->
            if (storageKey?.startsWith(FLUTTER_PREFIX) == true) {
                listener(storageKey.removePrefix(FLUTTER_PREFIX))
            }
        }
        prefs.registerOnSharedPreferenceChangeListener(delegate)
        return { prefs.unregisterOnSharedPreferenceChangeListener(delegate) }
    }

    fun enabledAppCount(): Int = getString("pref_generic_whitelist")
        .split(',')
        .count { it.trim().isNotEmpty() }

    fun toastEnabledAppCount(): Int = prefs.all.count { (key, value) ->
        if (!key.startsWith("${FLUTTER_PREFIX}pref_app_config_") || value !is String) {
            return@count false
        }
        runCatching {
            JSONObject(value).optJSONObject("toast")?.optBoolean("forward", false) == true
        }.getOrDefault(false)
    }

    private fun storageKey(key: String): String =
        if (key.startsWith(FLUTTER_PREFIX)) key else "$FLUTTER_PREFIX$key"

    private companion object {
        const val PREFS_NAME = "FlutterSharedPreferences"
        const val FLUTTER_PREFIX = "flutter."
        const val FLUTTER_DOUBLE_PREFIX = "VGhpcyBpcyB0aGUgcHJlZml4IGZvciBEb3VibGUu"
    }
}
