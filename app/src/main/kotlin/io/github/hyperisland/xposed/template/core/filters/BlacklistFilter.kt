package io.github.hyperisland.xposed.template.core.filters

import android.content.Context
import io.github.hyperisland.xposed.template.core.models.NotifData
import io.github.hyperisland.xposed.utils.SceneBehavior

/**
 * 兼容旧黑名单入口，并委托给统一场景规则。
 */
object BlacklistFilter {

    fun applyTo(context: Context, data: NotifData): NotifData? {
        val decision = SceneBehavior.resolve(
            context = context,
            surface = SceneBehavior.Surface.GENERIC_NOTIFICATION,
            sourcePackage = data.pkg,
            channelId = data.channelId,
        )
        if (decision.shouldSuppress) return null
        return data.copy(
            firstFloat = decision.applyToTriOpt(data.firstFloat),
            enableFloatMode = decision.applyToTriOpt(data.enableFloatMode),
        )
    }
}
