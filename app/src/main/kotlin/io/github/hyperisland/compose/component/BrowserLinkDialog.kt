package io.github.hyperisland.compose.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog

/**
 * 外链统一确认：返回「请求打开 URL」的回调，点击先弹确认框，用户确认才交给默认浏览器。
 *
 * 每个调用点各自持有一个 launcher（内部就是一段待打开 URL + 一个 Miuix 对话框），互不干扰；
 * 首页 LinkAction 与开发者页的各条外链都走这里，行为一致。
 */
@Composable
internal fun rememberBrowserLauncher(): (String) -> Unit {
    val context = LocalContext.current
    var pendingUrl by remember { mutableStateOf<String?>(null) }
    pendingUrl?.let { url ->
        WindowDialog(
            show = true,
            title = "跳转浏览器",
            onDismissRequest = { pendingUrl = null },
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("即将在默认浏览器中打开以下链接：")
                Text(
                    text = url,
                    fontSize = MiuixTheme.textStyles.footnote1.fontSize,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    TextButton(
                        text = "取消",
                        onClick = { pendingUrl = null },
                        modifier = Modifier.weight(1f),
                    )
                    Button(
                        onClick = {
                            pendingUrl = null
                            context.openInBrowser(url)
                        },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColorsPrimary(),
                    ) {
                        Text("打开")
                    }
                }
            }
        }
    }
    return { url: String -> pendingUrl = url }
}

private fun android.content.Context.openInBrowser(url: String) {
    runCatching {
        startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url)))
    }
}
