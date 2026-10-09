package io.github.hyperisland.compose.page.settings

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.hyperisland.R
import io.github.hyperisland.compose.component.DetailPage
import io.github.hyperisland.compose.component.SectionTitle
import io.github.hyperisland.compose.component.SettingsAction
import io.github.hyperisland.compose.component.rememberBrowserLauncher
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Info
import top.yukonga.miuix.kmp.icon.extended.Link

/** 开发者主页：GitHub 个人页。 */
private const val DEVELOPER_GITHUB_URL = "https://github.com/zuige66"
/** zuige 的博客（2026-10-09 由 zuige 指定）。 */
private const val DEVELOPER_BLOG_URL = "https://blog.zuiges.com"

/**
 * 「开发者」detail 页：从「关于」页的开发者卡片进入（2026-10-09 之前是整卡直达 GitHub）。
 * 两项外链都走 [rememberBrowserLauncher] 确认弹窗，与全应用外链行为一致。
 */
@Composable
internal fun DeveloperPage(onBack: () -> Unit) {
    val openLink = rememberBrowserLauncher()
    DetailPage(
        title = stringResource(R.string.about_developer),
        onBack = onBack,
    ) {
        item {
            SectionTitle(stringResource(R.string.about_developer))
            Card(modifier = Modifier.fillMaxWidth()) {
                SettingsAction(
                    title = "GitHub",
                    summary = "@zuige66",
                    icon = MiuixIcons.Info,
                    endIcon = MiuixIcons.Link,
                    endIconSize = 26.dp,
                ) {
                    openLink(DEVELOPER_GITHUB_URL)
                }
                SettingsAction(
                    title = "Blog",
                    summary = "blog.zuiges.com",
                    icon = MiuixIcons.Info,
                    endIcon = MiuixIcons.Link,
                    endIconSize = 26.dp,
                ) {
                    openLink(DEVELOPER_BLOG_URL)
                }
            }
        }
    }
}
