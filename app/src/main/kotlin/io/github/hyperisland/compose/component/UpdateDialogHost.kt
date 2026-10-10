package io.github.hyperisland.compose.component

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.hyperisland.R
import io.github.hyperisland.compose.service.AppUpdate
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog

internal sealed interface UpdateDialogState {
    data class Available(
        val currentVersion: String,
        val update: AppUpdate,
    ) : UpdateDialogState

    data object Failure : UpdateDialogState
}

@Composable
internal fun UpdateDialogHost(
    state: UpdateDialogState?,
    onDismiss: () -> Unit,
    /** (主下载直链, 备用直链)；备用可空（GitHub 源附带 blog 直链，下载失败自动回退用）。 */
    onDownload: (String, String?) -> Unit,
) {
    val available = state as? UpdateDialogState.Available
    val context = LocalContext.current
    // Android 13+ 下载进度通知需要 POST_NOTIFICATIONS 运行时授权（Manifest 已声明）。
    // 先问权限再开下载：**无论用户给不给都照旧下载**——通知只是进度展示，下载链路不依赖它
    // （应用内还有一条显式广播把百分比回传给关于页）。
    var pendingDownload by remember { mutableStateOf<Pair<String, String?>?>(null) }
    val requestNotification = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        val pending = pendingDownload
        pendingDownload = null
        if (pending != null) onDownload(pending.first, pending.second)
    }
    fun startDownload() {
        val update = available ?: return
        val url = update.update.apkUrl
        val fallback = update.update.fallbackApkUrl
        val needsPermission = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        if (needsPermission) {
            pendingDownload = url to fallback
            requestNotification.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            onDownload(url, fallback)
        }
    }
    WindowDialog(
        show = available != null,
        title = stringResource(R.string.new_version_found),
        onDismissRequest = onDismiss,
    ) {
        if (available != null) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.current_version, "v${available.currentVersion}"))
                Text(stringResource(R.string.latest_version, "v${available.update.version}"))
                if (available.update.changelog.isNotBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(1.dp)
                            .background(MiuixTheme.colorScheme.dividerLine),
                    )
                    ReleaseNotes(available.update.changelog)
                }
                DialogActions(onCancel = onDismiss, onConfirm = ::startDownload)
            }
        }
    }

    WindowDialog(
        show = state == UpdateDialogState.Failure,
        title = stringResource(R.string.update_check_failed),
        onDismissRequest = onDismiss,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(
                text = stringResource(R.string.update_check_failed_message),
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
            Button(
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColorsPrimary(),
            ) {
                Text(stringResource(R.string.confirm))
            }
        }
    }
}

@Composable
private fun ReleaseNotes(changelog: String) {
    val lines = changelog.trim().lines()
    LazyColumn(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 240.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        items(lines) { rawLine ->
            val line = rawLine.trim()
            when {
                line.isEmpty() -> Spacer(Modifier.height(3.dp))
                line.startsWith("#") -> Text(
                    text = markdownPlainText(line.trimStart('#').trim()),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MiuixTheme.colorScheme.onSurface,
                )
                line.startsWith("- ") || line.startsWith("* ") -> Text(
                    text = "• ${markdownPlainText(line.drop(2))}",
                    fontSize = 14.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
                else -> Text(
                    text = markdownPlainText(line),
                    fontSize = 14.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
        }
    }
}

@Composable
private fun DialogActions(
    onCancel: () -> Unit,
    onConfirm: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        TextButton(
            text = stringResource(R.string.cancel),
            onClick = onCancel,
            modifier = Modifier.weight(1f),
        )
        Button(
            onClick = onConfirm,
            modifier = Modifier.weight(1f),
            colors = ButtonDefaults.buttonColorsPrimary(),
        ) {
            Text(stringResource(R.string.download))
        }
    }
}

private fun markdownPlainText(source: String): String = source
    .replace(MARKDOWN_LINK) { match ->
        "${match.groupValues[1]} (${match.groupValues[2]})"
    }
    .replace("**", "")
    .replace("__", "")
    .replace("`", "")

private val MARKDOWN_LINK = Regex("""\[([^]]+)]\(([^)]+)\)""")
