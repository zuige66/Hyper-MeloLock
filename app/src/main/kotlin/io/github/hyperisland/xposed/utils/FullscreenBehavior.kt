package io.github.hyperisland.xposed.utils

import android.content.Context
import io.github.hyperisland.xposed.ConfigManager

object FullscreenBehavior {

    const val MODE_OFF = "off"
    const val MODE_FALLBACK = "fallback"
    const val MODE_EXPAND = "expand"

    private const val PREF_KEY = "pref_fullscreen_behavior"
    private const val PREF_LANDSCAPE_KEY = "pref_landscape_behavior"

    fun mode(): String {
        return when (ConfigManager.getString(PREF_KEY, MODE_OFF).trim().lowercase()) {
            MODE_FALLBACK -> MODE_FALLBACK
            MODE_EXPAND -> MODE_EXPAND
            else -> MODE_OFF
        }
    }

    fun landscapeMode(): String {
        return when (ConfigManager.getString(PREF_LANDSCAPE_KEY, MODE_OFF).trim().lowercase()) {
            MODE_FALLBACK -> MODE_FALLBACK
            MODE_EXPAND -> MODE_EXPAND
            else -> MODE_OFF
        }
    }

    fun isFullscreenLike(context: Context): Boolean {
        return SceneBehavior.readEnvironment(context).isFullscreenLike
    }
}
