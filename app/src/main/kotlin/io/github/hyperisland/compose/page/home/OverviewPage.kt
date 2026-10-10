package io.github.hyperisland.compose.page.home

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.hyperisland.R
import io.github.hyperisland.XposedPrefsSyncApp
import io.github.hyperisland.compose.data.FlutterPrefsRepository
import io.github.hyperisland.compose.service.HomeSystemInfo
import io.github.hyperisland.compose.service.SystemInfoProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.PressFeedbackType

internal data class ModuleState(
    val active: Boolean,
    val serviceConnected: Boolean = false,
    val framework: String = "",
    val frameworkVersion: String = "",
    val frameworkVersionCode: Int = 0,
    val apiVersion: Int = 0,
    val hasSystemUiScope: Boolean = false,
)

@Stable
internal class HomeOverviewState(private val prefs: FlutterPrefsRepository) {
    var status by mutableStateOf<ModuleState?>(null)
        private set
    var systemInfo by mutableStateOf<HomeSystemInfo?>(null)
        private set
    var enabledAppCount by mutableStateOf(prefs.enabledAppCount())
        private set
    var toastEnabledAppCount by mutableStateOf(prefs.toastEnabledAppCount())
        private set

    private val refreshMutex = Mutex()

    fun onPreferenceChanged(key: String) {
        if (key == "pref_generic_whitelist") enabledAppCount = prefs.enabledAppCount()
        if (key.startsWith("pref_app_config_")) {
            toastEnabledAppCount = prefs.toastEnabledAppCount()
        }
    }

    suspend fun refresh(context: Context) = refreshMutex.withLock {
        val refreshed = withContext(Dispatchers.IO) {
            val info = SystemInfoProvider.load(context)
            val connected = XposedPrefsSyncApp.awaitReady()
            if (!connected) return@withContext info to ModuleState(active = false)
            val app = context.applicationContext as XposedPrefsSyncApp
            val frameworkInfo = runCatching { app.getFrameworkInfo() }.getOrDefault(emptyMap())
            val apiVersion = (frameworkInfo["apiVersion"] as? Number)?.toInt() ?: 0
            val scopePackages = (frameworkInfo["scope"] as? List<*>)
                ?.filterIsInstance<String>()
                .orEmpty()
            val hasSystemUiScope = SYSTEM_UI_PACKAGE in scopePackages
            info to ModuleState(
                active = apiVersion >= MIN_SUPPORTED_API && hasSystemUiScope,
                serviceConnected = true,
                framework = frameworkInfo["frameworkName"]?.toString().orEmpty(),
                frameworkVersion = frameworkInfo["frameworkVersion"]?.toString().orEmpty(),
                frameworkVersionCode = (frameworkInfo["frameworkVersionCode"] as? Number)?.toInt() ?: 0,
                apiVersion = apiVersion,
                hasSystemUiScope = hasSystemUiScope,
            )
        }
        systemInfo = refreshed.first
        status = refreshed.second
    }
}

@Composable
internal fun rememberHomeOverviewState(prefs: FlutterPrefsRepository): HomeOverviewState {
    val state = remember(prefs) { HomeOverviewState(prefs) }
    DisposableEffect(prefs, state) {
        val removeListener = prefs.addChangeListener(state::onPreferenceChanged)
        onDispose(removeListener)
    }
    return state
}

private data class HomeStatusAlert(
    val title: String,
    val message: String,
    val warning: Boolean = false,
)

@Composable
private fun homeStatusAlert(status: ModuleState?, info: HomeSystemInfo?): HomeStatusAlert? {
    if (status == null || info == null) return null
    return when {
        !status.serviceConnected -> HomeStatusAlert(
            title = stringResource(R.string.lsposed_service_unavailable),
            message = stringResource(R.string.lsposed_service_unavailable_summary),
        )
        status.apiVersion < MIN_SUPPORTED_API -> HomeStatusAlert(
            title = stringResource(R.string.lsposed_version_unsupported),
            message = stringResource(R.string.update_lsposed),
        )
        !status.hasSystemUiScope -> HomeStatusAlert(
            title = stringResource(R.string.systemui_scope_missing),
            message = stringResource(R.string.enable_systemui_scope),
        )
        info.focusProtocolVersion != REQUIRED_FOCUS_PROTOCOL -> HomeStatusAlert(
            title = stringResource(R.string.system_not_supported),
            message = stringResource(
                R.string.system_not_supported_summary,
                info.focusProtocolVersion,
                REQUIRED_FOCUS_PROTOCOL,
            ),
        )
        info.androidSdkVersion == ANDROID_15_SDK -> HomeStatusAlert(
            title = stringResource(R.string.android_15_limited),
            message = stringResource(R.string.android_15_limited_summary),
            warning = true,
        )
        else -> null
    }
}

@Composable
internal fun OverviewAlertCard(title: String, message: String, warning: Boolean = false) {
    val isLight = MiuixTheme.colorScheme.background.luminance() > 0.5f
    val backgroundColor = when {
        !warning -> MiuixTheme.colorScheme.errorContainer
        isLight -> WarningBackgroundLight
        else -> WarningBackgroundDark
    }
    val contentColor = when {
        !warning -> MiuixTheme.colorScheme.onErrorContainer
        isLight -> WarningContentLight
        else -> WarningContentDark
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.defaultColors(
            color = backgroundColor,
            contentColor = contentColor,
        ),
        showIndication = false,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = title,
                color = contentColor,
                style = MiuixTheme.textStyles.headline1,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = message,
                color = contentColor.copy(alpha = 0.82f),
                style = MiuixTheme.textStyles.body2,
            )
        }
    }
}

/**
 * HyperIsland 原版首页状态区：左侧方形激活卡 + 右侧两张数据卡。
 *
 * 从 HyperIsland 的私有实现提升为可复用组件，仅把标题与数值参数化，
 * 视觉与交互代码保持不变，供本模块首页直接复用。
 */
@Composable
internal fun OverviewStatusGrid(
    active: Boolean,
    versionText: String,
    primaryTitle: String,
    primaryValue: String,
    onPrimaryClick: () -> Unit,
    secondaryTitle: String,
    secondaryValue: String,
    onSecondaryClick: () -> Unit,
    modifier: Modifier = Modifier,
    onStatusClick: (() -> Unit)? = null,
    onStatusLongPress: (() -> Unit)? = null,
    statusClickableWhenInactive: Boolean = false,
) {
    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        if (maxWidth >= 600.dp) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OverviewStatusCard(
                    active = active,
                    versionText = versionText,
                    modifier = Modifier.weight(1f).height(112.dp),
                    onClick = onStatusClick,
                    onLongPress = onStatusLongPress,
                    clickableWhenInactive = statusClickableWhenInactive,
                )
                OverviewStatCard(
                    title = primaryTitle,
                    value = primaryValue,
                    modifier = Modifier.weight(1f).height(112.dp),
                    onClick = onPrimaryClick,
                )
                OverviewStatCard(
                    title = secondaryTitle,
                    value = secondaryValue,
                    modifier = Modifier.weight(1f).height(112.dp),
                    onClick = onSecondaryClick,
                )
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OverviewStatusCard(
                    active = active,
                    versionText = versionText,
                    modifier = Modifier.weight(1f).aspectRatio(1f),
                    onClick = onStatusClick,
                    onLongPress = onStatusLongPress,
                    clickableWhenInactive = statusClickableWhenInactive,
                )
                Column(
                    modifier = Modifier.weight(1f).aspectRatio(1f),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    OverviewStatCard(
                        title = primaryTitle,
                        value = primaryValue,
                        modifier = Modifier.weight(1f),
                        onClick = onPrimaryClick,
                    )
                    OverviewStatCard(
                        title = secondaryTitle,
                        value = secondaryValue,
                        modifier = Modifier.weight(1f),
                        onClick = onSecondaryClick,
                    )
                }
            }
        }
    }
}

@Composable
internal fun OverviewStatusCard(
    active: Boolean,
    versionText: String,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    onLongPress: (() -> Unit)? = null,
    /**
     * HyperIsland 的状态卡只在激活时可点（点击发测试通知）。本模块把这张卡当作模块总开关，
     * 必须未激活时也能点，否则关掉之后就再也开不回来。
     */
    clickableWhenInactive: Boolean = false,
) {
    val clickable = active || clickableWhenInactive
    val statusColor = if (active) ActiveColor else InactiveColor
    val statusBackground = if (active) ActiveBackground else InactiveBackground
    Card(
        modifier = modifier,
        colors = CardDefaults.defaultColors(color = statusBackground),
        pressFeedbackType = PressFeedbackType.Tilt,
        showIndication = clickable,
        onClick = onClick?.takeIf { clickable },
        onLongPress = onLongPress?.takeIf { active },
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier.fillMaxSize().offset(27.dp, 31.dp),
                contentAlignment = Alignment.BottomEnd,
            ) {
                Icon(
                    modifier = Modifier.size(110.dp),
                    painter = painterResource(R.drawable.ic_check_circle_outline),
                    contentDescription = null,
                    tint = statusColor.copy(alpha = 0.78f),
                )
            }
            Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                Text(
                    text = stringResource(
                        if (active) R.string.activated else R.string.not_activated,
                    ),
                    fontSize = 20.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color(0xFF101010),
                )
                Text(
                    text = versionText,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = if (active) Color(0xFF101010) else statusColor,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
    }
}

@Composable
internal fun OverviewStatCard(
    title: String,
    value: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        pressFeedbackType = PressFeedbackType.Tilt,
        onClick = onClick,
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(14.dp),
            horizontalAlignment = Alignment.Start,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = title,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
            Text(
                text = value,
                fontSize = 24.sp,
                fontWeight = FontWeight.SemiBold,
                color = MiuixTheme.colorScheme.onSurface,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

@Composable
internal fun OverviewInfoCard(
    rows: List<Pair<String, String>>,
    modifier: Modifier = Modifier,
) {
    Card(modifier) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            rows.forEachIndexed { index, row ->
                InfoText(
                    title = row.first,
                    content = row.second,
                    bottomPadding = if (index == rows.lastIndex) 0.dp else 24.dp,
                )
            }
        }
    }
}

@Composable
private fun InfoText(title: String, content: String, bottomPadding: androidx.compose.ui.unit.Dp = 24.dp) {
    Text(
        text = title,
        fontSize = MiuixTheme.textStyles.headline1.fontSize,
        fontWeight = FontWeight.Medium,
        color = MiuixTheme.colorScheme.onSurface,
    )
    Text(
        text = content,
        fontSize = MiuixTheme.textStyles.body2.fontSize,
        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        modifier = Modifier.padding(top = 2.dp, bottom = bottomPadding),
    )
}

private const val SYSTEM_UI_PACKAGE = "com.android.systemui"
private const val MIN_SUPPORTED_API = 101
private const val REQUIRED_FOCUS_PROTOCOL = 3
private const val ANDROID_15_SDK = 35
private val ActiveColor = Color(0xFF36D167)
private val ActiveBackground = Color(0xFFDFFAE4)
private val InactiveColor = Color(0xFFFF5A52)
private val InactiveBackground = Color(0xFFFFE5E3)
private val WarningBackgroundLight = Color(0xFFFFF3D6)
private val WarningContentLight = Color(0xFF704D00)
private val WarningBackgroundDark = Color(0xFF3A2D12)
private val WarningContentDark = Color(0xFFFFD978)
