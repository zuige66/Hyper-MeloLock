package io.github.hyperisland.compose.page

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.hyperisland.BuildConfig
import io.github.hyperisland.R
import io.github.hyperisland.XposedPrefsSyncApp
import io.github.hyperisland.compose.component.CollapsingPage
import io.github.hyperisland.compose.component.LocalRootBottomBarPadding
import io.github.hyperisland.compose.component.PreferenceDropdown
import io.github.hyperisland.compose.component.PreferenceSlider
import io.github.hyperisland.compose.component.PreferenceSwitch
import io.github.hyperisland.compose.component.RestartScopeDialog
import io.github.hyperisland.compose.component.SectionTitle
import io.github.hyperisland.compose.component.SettingsAction
import io.github.hyperisland.compose.component.SettingsActionWithArrow
import io.github.hyperisland.compose.data.InstalledAppsRepository
import io.github.hyperisland.compose.page.home.OverviewAlertCard
import io.github.hyperisland.compose.page.home.OverviewInfoCard
import io.github.hyperisland.compose.page.home.OverviewStatusGrid
import io.github.hyperisland.compose.service.HomeSystemInfo
import io.github.hyperisland.compose.service.RestartScopeService
import io.github.hyperisland.compose.service.SystemInfoProvider
import io.github.melolock.Config
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.InputField
import top.yukonga.miuix.kmp.basic.SearchBar
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.basic.ArrowRight
import top.yukonga.miuix.kmp.icon.extended.Backup
import top.yukonga.miuix.kmp.icon.extended.Community
import top.yukonga.miuix.kmp.icon.extended.Info
import top.yukonga.miuix.kmp.icon.extended.Link
import top.yukonga.miuix.kmp.icon.extended.Refresh
import top.yukonga.miuix.kmp.icon.extended.Update
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical

/**
 * 本模块的四个根页面。
 *
 * 版式直接复用随本项目迁入的 HyperIsland（MIT）原版组件：首页用
 * [OverviewStatusGrid] / [OverviewInfoCard] / [OverviewAlertCard]，音乐应用页用
 * HyperIsland「应用」页的搜索栏加图标行样式，其余页面用 Miuix Preference 组件。
 * 这里只接本模块自己的数据源，不重写样式。
 */

// ── 首页 ─────────────────────────────────────────────────────────────────────

@Composable
internal fun LockHomePage(
    isActive: Boolean,
    onOpenMusicApps: () -> Unit,
    onOpenAppearance: () -> Unit,
) {
    val context = LocalContext.current
    var enabled by remember { mutableStateOf(Config.enabled(context)) }
    var enabledAppCount by remember { mutableIntStateOf(Config.enabledAppCount(context)) }
    var cornerRadiusDp by remember { mutableIntStateOf(Config.cornerRadiusDp(context)) }
    var supported by remember { mutableStateOf(Config.deviceSupported()) }
    var systemInfo by remember { mutableStateOf<HomeSystemInfo?>(null) }
    var framework by remember { mutableStateOf<FrameworkDetails?>(null) }
    var refreshToken by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    var showRestartDialog by remember { mutableStateOf(false) }

    LaunchedEffect(isActive, refreshToken) {
        if (!isActive && refreshToken == 0) return@LaunchedEffect
        enabled = Config.enabled(context)
        enabledAppCount = Config.enabledAppCount(context)
        cornerRadiusDp = Config.cornerRadiusDp(context)
        supported = Config.deviceSupported()
        val loaded = withContext(Dispatchers.IO) {
            val info = runCatching { SystemInfoProvider.load(context) }
                .onFailure { Log.w(APP_LOG_TAG, "System info unavailable", it) }
                .getOrNull()
            info to runCatching { loadFrameworkDetails(context) }.getOrNull()
        }
        systemInfo = loaded.first
        framework = loaded.second
    }

    val unknown = stringResource(R.string.unknown)
    val info = systemInfo
    // 系统属性读取可能被 ROM 拦掉，这里再兜一层 Build.*，避免整行退化成“未知”。
    val systemVersion = info?.systemVersion.orEmpty()
        .ifBlank { Build.VERSION.INCREMENTAL.orEmpty() }
        .ifBlank { unknown }
    val deviceModel = info?.deviceModel.orEmpty()
        .ifBlank { Build.MODEL.orEmpty() }
        .ifBlank { unknown }
    val frameworkText = framework?.let {
        stringResource(
            R.string.framework_details,
            it.name.ifBlank { unknown },
            it.version.ifBlank { unknown },
            it.versionCode,
            it.apiVersion,
        )
    } ?: unknown

    CollapsingPage(
        title = stringResource(R.string.app_name),
        actionIcon = MiuixIcons.Refresh,
        actionDescription = stringResource(R.string.restart_scope),
        // 重启作用域：先探测 root 再决定走哪条路。
        //  · 有 root → 弹出作用域列表，用 su 精确重启选中的进程；
        //  · 无 root → 走模块通道，广播给被 hook 的进程让它自杀重启（等价一次进程重启）。
        //    2026-10-08 起作用域含壁纸进程（com.miui.miwallpaper），两条都要发；
        //    壁纸侧接收器由 WallpaperTexProbe 在 WallpaperService#onCreate 时注册。
        //    本机 SukiSU 下 adb/app 侧拿不到 su，必须走这条，否则按钮点了没反应。
        onAction = {
            scope.launch {
                if (RestartScopeService.hasRoot()) {
                    showRestartDialog = true
                    return@launch
                }
                context.sendBroadcast(
                    Intent(Config.ACTION_RESTART_SYSTEMUI).setPackage(Config.SYSTEMUI_PACKAGE)
                )
                context.sendBroadcast(
                    Intent(Config.ACTION_RESTART_WALLPAPER).setPackage(Config.WALLPAPER_PACKAGE)
                )
                Toast.makeText(
                    context,
                    context.getString(R.string.restart_scope_requested),
                    Toast.LENGTH_LONG,
                ).show()
                refreshToken++
            }
        },
        horizontalContentPadding = 12.dp,
        topContentPadding = 12.dp,
        bottomContentPadding = 16.dp,
    ) {
        item {
            OverviewStatusGrid(
                active = enabled,
                versionText = stringResource(R.string.software_version, BuildConfig.VERSION_NAME),
                primaryTitle = "已开启应用",
                primaryValue = if (enabledAppCount < 0) "全部" else enabledAppCount.toString(),
                onPrimaryClick = onOpenMusicApps,
                secondaryTitle = "封面圆角",
                secondaryValue = "$cornerRadiusDp dp",
                onSecondaryClick = onOpenAppearance,
                // HyperIsland 的状态卡是“点击执行主动作”；本模块的主动作就是模块总开关。
                onStatusClick = {
                    if (Config.setEnabled(context, !enabled)) enabled = !enabled
                },
                statusClickableWhenInactive = true,
            )
        }
        if (!supported) {
            item {
                OverviewAlertCard(
                    title = "当前系统未适配",
                    message = "锁屏覆盖层只在已验证的 SystemUI 版本上启用；当前设备不在验证列表内，模块不会生效。",
                )
            }
        }
        item {
            OverviewInfoCard(
                rows = listOf(
                    stringResource(R.string.system_version) to systemVersion,
                    stringResource(R.string.app_version) to
                        "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                    stringResource(R.string.xposed_framework) to frameworkText,
                    stringResource(R.string.device_model) to deviceModel,
                ),
            )
        }
        item {
            Card {
                LinkAction(
                    title = stringResource(R.string.support_development),
                    summary = stringResource(R.string.support_development_summary),
                    url = DONATION_URL,
                )
                LinkAction(
                    title = stringResource(R.string.documentation),
                    summary = stringResource(R.string.documentation_summary),
                    url = DOCUMENTATION_URL,
                )
                LinkAction(
                    title = stringResource(R.string.related_resources),
                    summary = stringResource(R.string.related_resources_summary),
                    url = RESOURCES_URL,
                )
            }
        }
        item {
            SectionTitle("使用说明")
            Card {
                InfoText(
                    "请在 Vector 中仅勾选 SystemUI。更新 APK 后需重新确认模块总开关；" +
                        "点击上方状态卡可随时开关模块，关闭后立即恢复原生锁屏。" +
                        "右上角按钮用于重启作用域（SystemUI）。",
                )
            }
        }
    }
    RestartScopeDialog(
        show = showRestartDialog,
        onDismiss = { showRestartDialog = false },
    )
}

// ── 音乐应用 ─────────────────────────────────────────────────────────────────

@Composable
internal fun LockMusicAppsPage() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val repository = remember(context) { InstalledAppsRepository(context.applicationContext) }
    var apps by remember { mutableStateOf(repository.cachedApps()) }
    var loading by remember { mutableStateOf(apps.isEmpty()) }
    var selection by remember { mutableStateOf(loadMusicSelection(context)) }
    // 默认不显示系统应用：列表里绝大多数是系统组件，默认打开只会让用户难找。
    var showSystemApps by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var searchExpanded by remember { mutableStateOf(false) }

    fun load() {
        scope.launch {
            loading = apps.isEmpty()
            try {
                val loaded = withContext(Dispatchers.IO) {
                    runCatching { repository.load(forceRefresh = true) }.getOrNull()
                }
                if (loaded != null) apps = loaded
            } finally {
                loading = false
            }
        }
    }

    // HyperOS 上枚举全部应用需要 MIUI 的应用列表权限，和 HyperIsland 的应用页同样处理。
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        load()
    }
    LaunchedEffect(Unit) {
        // 缺权限时一定要弹授权框（之前带了「列表为空」的额外条件，容易一直问不到），
        // 拿到结果后由上面的回调再刷新列表。
        if (repository.needsAppListPermission()) {
            permissionLauncher.launch(APP_LIST_PERMISSION)
        } else {
            load()
        }
    }

    fun toggle(packageName: String, checked: Boolean) {
        val current = selection
        val next = when {
            // 未设置时表示“允许全部”，勾选不改变这一状态。
            current.unrestricted && checked -> return
            // 从不限制状态取消勾选某个应用时，其余应用落成显式白名单。
            current.unrestricted -> apps.map { it.packageName }.toSet() - packageName
            checked -> current.packages + packageName
            else -> current.packages - packageName
        }
        if (Config.setAllowedPackages(context, next)) {
            selection = MusicSelection(unrestricted = false, packages = next)
        }
    }

    val filtered = remember(apps, query, showSystemApps) {
        val normalized = query.trim().lowercase()
        apps.filter { app ->
            (showSystemApps || !app.isSystem) &&
                (normalized.isEmpty() ||
                    app.appName.lowercase().contains(normalized) ||
                    app.packageName.lowercase().contains(normalized))
        }
    }

    /** 全选开关的作用范围＝当前列表（含搜索/系统应用过滤），避免把看不见的应用一起勾上。 */
    fun selectAllVisible(checked: Boolean) {
        val visible = filtered.map { it.packageName }.toSet()
        val next = if (checked) selection.packages + visible else selection.packages - visible
        if (Config.setAllowedPackages(context, next)) {
            selection = MusicSelection(unrestricted = false, packages = next)
        }
    }

    val allVisibleSelected = filtered.isNotEmpty() &&
        filtered.all { selection.isSelected(it.packageName) }

    CollapsingPage(title = "音乐应用") {
        item {
            SearchBar(
                inputField = {
                    InputField(
                        query = query,
                        onQueryChange = { query = it },
                        onSearch = {},
                        expanded = searchExpanded,
                        onExpandedChange = { searchExpanded = it },
                        label = "搜索应用名称或包名",
                    )
                },
                onExpandedChange = { searchExpanded = it },
                expanded = searchExpanded,
            ) {}
        }
        item {
            Card {
                PreferenceSwitch(
                    title = "全选",
                    summary = "勾选当前列表里的全部应用；再点一次全部取消",
                    icon = null,
                    checked = allVisibleSelected,
                    onCheckedChange = { selectAllVisible(it) },
                )
                PreferenceSwitch(
                    title = "显示系统应用",
                    summary = "关闭后只列出你自己安装的应用",
                    icon = null,
                    checked = showSystemApps,
                    onCheckedChange = { showSystemApps = it },
                )
            }
        }
        if (filtered.isEmpty()) {
            item {
                Box(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 64.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = when {
                            loading -> "正在读取应用列表…"
                            query.isNotEmpty() -> "没有匹配的应用"
                            else -> "没有读取到已安装的应用。授权后重新进入此页。"
                        },
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
            }
        } else {
            items(filtered, key = { it.packageName }) { app ->
                MediaAppRow(
                    label = app.appName,
                    packageName = app.packageName,
                    repository = repository,
                    selected = selection.isSelected(app.packageName),
                    onSelectedChange = { toggle(app.packageName, it) },
                )
            }
        }
        item {
            SectionTitle("说明")
            Card {
                InfoText(
                    "这里列出全部已安装应用。默认一个都不勾选，必须显式勾选播放器，" +
                        "锁屏才会接管它的播放信息；全部取消后锁屏不再接管任何播放器。" +
                        "上方的「全选」只作用于当前列表（受搜索与「显示系统应用」过滤影响）。",
                )
            }
        }
    }
}

// ── 外观 ─────────────────────────────────────────────────────────────────────

@Composable
internal fun LockAppearancePage() {
    val context = LocalContext.current
    val screenWidthDp = LocalConfiguration.current.screenWidthDp
    val defaultArtDp = minOf((screenWidthDp * 0.72f).toInt(), 360)
    val defaultCardWidthDp = maxOf(160, screenWidthDp - 24)

    // 一次读出全部元素配置，写回时同步更新本地快照以触发重组。
    var settings by remember { mutableStateOf(Config.elementValues(context)) }
    var radius by remember { mutableStateOf(Config.cornerRadiusDp(context).toFloat()) }
    var style by remember { mutableStateOf(Config.overlayStyle(context)) }
    var colorIndex by remember { mutableStateOf(PALETTE.indexOf(Config.overlayColor(context)).coerceAtLeast(0)) }
    var alpha by remember { mutableStateOf(Config.overlayAlpha(context).toFloat()) }

    fun value(key: String) = settings[key] ?: Config.elementDefault(key)
    fun update(key: String, newValue: Int) {
        Config.setElementInt(context, key, newValue)
        settings = settings + (key to newValue)
    }

    /** 从「锁定比例」切到「长宽」时，用当前默认尺寸兜底填充，避免出现 0 值。 */
    fun seedSize(widthKey: String, heightKey: String, fallbackWidth: Int, fallbackHeight: Int) {
        if (value(widthKey) <= 0) update(widthKey, fallbackWidth)
        if (value(heightKey) <= 0) update(heightKey, fallbackHeight)
    }

    CollapsingPage(title = "外观") {
        item {
            SectionTitle("日期")
            Card {
                PreferenceSwitch(
                    title = "显示日期",
                    summary = "时钟上方的「公历 + 周几 + 农历」，如「6月28日周六 · 乙巳年六月初四」",
                    icon = null,
                    checked = value(Config.DATE_ENABLED) != 0,
                    onCheckedChange = { update(Config.DATE_ENABLED, if (it) 1 else 0) },
                )
                if (value(Config.DATE_ENABLED) != 0) {
                    DpSlider("字号", value(Config.DATE_SIZE), 12..48, onCommit = { update(Config.DATE_SIZE, it) })
                    DpSlider("粗细", value(Config.DATE_WEIGHT), 100..900, unit = "", step = 10, onCommit = { update(Config.DATE_WEIGHT, it) })
                    PreferenceDropdown(
                        title = "颜色",
                        summary = "日期文字颜色，可选跟随封面",
                        icon = null,
                        items = TEXT_COLOR_LABELS,
                        selectedIndex = TEXT_COLOR_VALUES.indexOf(value(Config.DATE_COLOR)).coerceAtLeast(0),
                        onSelectedIndexChange = { update(Config.DATE_COLOR, TEXT_COLOR_VALUES[it]) },
                    )
                    DpSlider("距顶部", value(Config.DATE_SPACING), 0..160, onCommit = { update(Config.DATE_SPACING, it) })
                }
            }
        }
        item {
            SectionTitle("签名")
            Card {
                PreferenceSwitch(
                    title = "显示签名",
                    summary = "日期行下方的自定义文字；关闭或内容为空时不占位",
                    icon = null,
                    checked = value(Config.SIGN_ENABLED) != 0,
                    onCheckedChange = { update(Config.SIGN_ENABLED, if (it) 1 else 0) },
                )
                if (value(Config.SIGN_ENABLED) != 0) {
                    SignatureInputField()
                    DpSlider("字号", value(Config.SIGN_SIZE), 10..40, onCommit = { update(Config.SIGN_SIZE, it) })
                    DpSlider("粗细", value(Config.SIGN_WEIGHT), 100..900, unit = "", step = 10, onCommit = { update(Config.SIGN_WEIGHT, it) })
                    PreferenceDropdown(
                        title = "颜色",
                        summary = "签名文字颜色，可选跟随封面",
                        icon = null,
                        items = TEXT_COLOR_LABELS,
                        selectedIndex = TEXT_COLOR_VALUES.indexOf(value(Config.SIGN_COLOR)).coerceAtLeast(0),
                        onSelectedIndexChange = { update(Config.SIGN_COLOR, TEXT_COLOR_VALUES[it]) },
                    )
                    DpSlider("距顶部", value(Config.SIGN_SPACING), 0..160, onCommit = { update(Config.SIGN_SPACING, it) })
                }
            }
        }
        item {
            SectionTitle("时间")
            Card {
                DpSlider("字号", value(Config.CLOCK_SIZE), 20..120, onCommit = { update(Config.CLOCK_SIZE, it) })
                DpSlider("粗细", value(Config.CLOCK_WEIGHT), 100..900, unit = "", step = 10, onCommit = { update(Config.CLOCK_WEIGHT, it) })
                DpSlider("描边加粗", value(Config.CLOCK_STROKE), 0..8, onCommit = { update(Config.CLOCK_STROKE, it) })
                PreferenceDropdown(
                    title = "字体圆润",
                    summary = "内置开源圆体数字字体，只影响 0-9 与冒号",
                    icon = null,
                    items = ROUNDNESS_LABELS,
                    selectedIndex = value(Config.CLOCK_ROUNDNESS).coerceIn(0, ROUNDNESS_LABELS.lastIndex),
                    onSelectedIndexChange = { update(Config.CLOCK_ROUNDNESS, it) },
                )
                PreferenceDropdown(
                    title = "颜色",
                    summary = "时间文字颜色，可选跟随封面",
                    icon = null,
                    items = TEXT_COLOR_LABELS,
                    selectedIndex = TEXT_COLOR_VALUES.indexOf(value(Config.CLOCK_COLOR)).coerceAtLeast(0),
                    onSelectedIndexChange = { update(Config.CLOCK_COLOR, TEXT_COLOR_VALUES[it]) },
                )
                DpSlider("距顶部", value(Config.CLOCK_SPACING), 0..160, onCommit = { update(Config.CLOCK_SPACING, it) })
            }
        }
        item {
            SectionTitle("专辑封面")
            Card {
                PreferenceSwitch(
                    title = "锁定比例",
                    summary = "关闭后可分别调整宽度和高度，封面按居中裁切填充",
                    icon = null,
                    checked = value(Config.COVER_LOCKED) != 0,
                    onCheckedChange = { locked ->
                        update(Config.COVER_LOCKED, if (locked) 1 else 0)
                        if (!locked) seedSize(Config.COVER_WIDTH, Config.COVER_HEIGHT, defaultArtDp, defaultArtDp)
                    },
                )
                if (value(Config.COVER_LOCKED) != 0) {
                    DpSlider("缩放", value(Config.COVER_SCALE), 10..300, unit = "%", onCommit = { update(Config.COVER_SCALE, it) })
                } else {
                    DpSlider("宽度", value(Config.COVER_WIDTH).coerceAtLeast(40), 40..600, onCommit = { update(Config.COVER_WIDTH, it) })
                    DpSlider("高度", value(Config.COVER_HEIGHT).coerceAtLeast(40), 40..600, onCommit = { update(Config.COVER_HEIGHT, it) })
                }
                PreferenceSlider(
                    title = "圆角",
                    summary = "锁屏专辑封面的圆角半径",
                    icon = null,
                    value = radius,
                    valueText = "${radius.toInt()} dp",
                    valueRange = 0f..48f,
                    steps = 47,
                    allowManualInput = false,
                    onValueChange = { radius = it },
                    onValueChangeFinished = { Config.setCornerRadiusDp(context, radius.toInt()) },
                )
                DpSlider("间距", value(Config.COVER_SPACING), 0..160, onCommit = { update(Config.COVER_SPACING, it) })
            }
        }
        item {
            SectionTitle("播放器")
            Card {
                PreferenceSwitch(
                    title = "锁定比例",
                    summary = "关闭后可分别调整卡片宽度和高度",
                    icon = null,
                    checked = value(Config.CARD_LOCKED) != 0,
                    onCheckedChange = { locked ->
                        update(Config.CARD_LOCKED, if (locked) 1 else 0)
                        if (!locked) seedSize(Config.CARD_WIDTH, Config.CARD_HEIGHT, defaultCardWidthDp, 178)
                    },
                )
                if (value(Config.CARD_LOCKED) != 0) {
                    DpSlider("缩放", value(Config.CARD_SCALE), 10..300, unit = "%", onCommit = { update(Config.CARD_SCALE, it) })
                } else {
                    DpSlider("宽度", value(Config.CARD_WIDTH).coerceAtLeast(160), 160..600, onCommit = { update(Config.CARD_WIDTH, it) })
                    DpSlider("高度", value(Config.CARD_HEIGHT).coerceAtLeast(60), 60..600, onCommit = { update(Config.CARD_HEIGHT, it) })
                }
                DpSlider("圆角", value(Config.CARD_RADIUS), 0..48, onCommit = { update(Config.CARD_RADIUS, it) })
                DpSlider("间距", value(Config.CARD_SPACING), 0..160, onCommit = { update(Config.CARD_SPACING, it) })
                PreferenceDropdown(
                    title = "底色",
                    summary = "播放器卡片的背景色；文字颜色按底色亮度自动适配",
                    icon = null,
                    items = CARD_BG_LABELS,
                    selectedIndex = CARD_BG_VALUES.indexOf(value(Config.CARD_BG)).coerceAtLeast(0),
                    onSelectedIndexChange = { update(Config.CARD_BG, CARD_BG_VALUES[it]) },
                )
                PreferenceSwitch(
                    title = "锁屏禁止左下拉",
                    summary = "沉浸场景显示时吃掉左侧下拉手势，不再和通知栏抢层级。注意：左半屏起始的上滑解锁也会失效（右半屏、指纹、电源键正常）",
                    icon = null,
                    checked = value(Config.BLOCK_LEFT_SHADE) != 0,
                    onCheckedChange = { update(Config.BLOCK_LEFT_SHADE, if (it) 1 else 0) },
                )
            }
        }
        item {
            SectionTitle("通知入口")
            Card {
                PreferenceDropdown(
                    title = "文字颜色",
                    summary = "底部「展开通知」按钮的文字颜色，可选跟随封面",
                    icon = null,
                    items = TEXT_COLOR_LABELS,
                    selectedIndex = TEXT_COLOR_VALUES.indexOf(value(Config.ENTRY_COLOR)).coerceAtLeast(0),
                    onSelectedIndexChange = { update(Config.ENTRY_COLOR, TEXT_COLOR_VALUES[it]) },
                )
                PreferenceDropdown(
                    title = "胶囊背景",
                    summary = "按钮胶囊的背景色，可选跟随封面",
                    icon = null,
                    items = CARD_BG_LABELS,
                    selectedIndex = CARD_BG_VALUES.indexOf(value(Config.ENTRY_BG)).coerceAtLeast(0),
                    onSelectedIndexChange = { update(Config.ENTRY_BG, CARD_BG_VALUES[it]) },
                )
            }
        }
        item {
            SectionTitle("背景")
            Card {
                PreferenceDropdown(
                    title = "背景样式",
                    summary = "深色玻璃＝强模糊封面 + 遮罩原样；浅色玻璃＝轻模糊 + 遮罩更淡；纯色沉浸＝不用封面，整块底色",
                    icon = null,
                    items = listOf("深色玻璃", "浅色玻璃", "纯色沉浸"),
                    selectedIndex = style,
                    onSelectedIndexChange = { newValue -> style = newValue; Config.setOverlayStyle(context, newValue) },
                )
                PreferenceDropdown(
                    title = "遮罩颜色",
                    summary = "覆盖在模糊封面上的背景色",
                    icon = null,
                    items = listOf("深蓝灰", "靛蓝", "紫色", "深绿", "纯黑"),
                    selectedIndex = colorIndex,
                    onSelectedIndexChange = { newValue -> colorIndex = newValue; Config.setOverlayColor(context, PALETTE[newValue]) },
                )
                PreferenceSlider(
                    title = "遮罩强度",
                    summary = "数值越高，背景越不透明",
                    icon = null,
                    value = alpha,
                    valueText = alpha.toInt().toString(),
                    valueRange = 0f..255f,
                    steps = 254,
                    allowManualInput = false,
                    onValueChange = { alpha = it },
                    onValueChangeFinished = { Config.setOverlayAlpha(context, alpha.toInt()) },
                )
            }
        }
        item {
            SectionTitle("说明")
            Card {
                InfoText(
                    "以上尺寸、圆角、间距、字体和背景样式都由锁屏覆盖层在创建场景时读取，" +
                        "改完需要灭屏再亮屏一次才会生效（模块会自己重建，不用重启 SystemUI）。",
                )
            }
        }
    }
}

// ── 开发者 ───────────────────────────────────────────────────────────────────

/**
 * 「开发者」页（第 4 个根页面）。
 *
 * 版式参考 HyperIsland 的 `AboutPage`：整屏是一个滚动列表，hero（图标 + 应用名 + 版本号）
 * 固定叠在顶部，**上滑时 hero 淡出并轻微缩小、动画渐变背景同时淡掉**，列表内容看起来是
 * 「盖上来」的。背景动画直接复用同包的 [AnimatedAboutBackground] / [rememberAboutAnimationTime]
 * / [animatedGradientColors]，hero 的滚动映射与上游同一套公式，不另写一份样式。
 *
 * 本模块还没有的东西（讨论 / 备份恢复 / 检查更新 / 引用 / 隐私政策）一律**灰度不可点**。
 */
@Composable
internal fun LockAboutPage(isActive: Boolean) {
    val context = LocalContext.current
    val listState = rememberLazyListState()
    val density = LocalDensity.current
    val screenHeight = LocalConfiguration.current.screenHeightDp.dp
    val heroHeight = screenHeight * ABOUT_HERO_HEIGHT_FRACTION
    val heroHeightPx = with(density) { heroHeight.toPx() }
    val backgroundFadeDistance = with(density) { 389.dp.toPx() }
    val logoFadeStart = heroHeightPx * 0.25f
    val logoFadeDistance = heroHeightPx * 0.35f

    val scrollOffset by remember(listState, heroHeightPx) {
        derivedStateOf {
            if (listState.firstVisibleItemIndex > 0) {
                heroHeightPx
            } else {
                listState.firstVisibleItemScrollOffset.toFloat()
            }
        }
    }
    // 背景先淡掉（跟着滚动距离线性走），hero 稍后才开始消失，两者错开才有「盖上来」的层次。
    val backgroundAlpha = (1f - scrollOffset / backgroundFadeDistance).coerceIn(0f, 1f)
    val logoProgress = ((scrollOffset - logoFadeStart) / logoFadeDistance).coerceIn(0f, 1f)
    val logoAlpha = 1f - logoProgress
    val logoScale = 1f - logoProgress * 0.1f

    val animationTime = rememberAboutAnimationTime(isActive)
    val gradientColors = animatedGradientColors(animationTime, isSystemInDarkTheme())

    Box(modifier = Modifier.fillMaxSize()) {
        AnimatedAboutBackground(
            animationTime = animationTime,
            colors = gradientColors,
            modifier = Modifier
                .fillMaxWidth()
                .height(heroHeight + 180.dp)
                .alpha(backgroundAlpha)
                .graphicsLayer {
                    // 必须开离屏合成：`AnimatedAboutBackground` 内部用 `BlendMode.DstIn` 做
                    // 竖直淡出蒙版，没有独立图层时它会拿整块 surface 当目标去混合，
                    // 结果是背景底边留下一条硬边、并且把相邻页面也染上颜色。
                    // 上游 AboutPage 也是同样的写法（compositingStrategy = Offscreen），别删。
                    compositingStrategy = CompositingStrategy.Offscreen
                    translationY = -listState.firstVisibleItemScrollOffset * 0.12f
                },
        )
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().overScrollVertical(),
            contentPadding = PaddingValues(
                start = 16.dp,
                top = 0.dp,
                end = 16.dp,
                bottom = 28.dp + LocalRootBottomBarPadding.current,
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Column(modifier = Modifier.fillMaxWidth()) {
                    // hero 是叠在上层的（不是列表项），这里留出等高的空位让它可见。
                    Spacer(Modifier.height(heroHeight + ABOUT_DEVELOPER_TOP_GAP))
                    SectionTitle(stringResource(R.string.about_developer))
                    DeveloperCard()
                }
            }
            item {
                SectionTitle(stringResource(R.string.about_discussion))
                Card(modifier = Modifier.fillMaxWidth()) {
                    // 还没有讨论群：先灰度占位。
                    SettingsAction(
                        title = stringResource(R.string.telegram),
                        icon = MiuixIcons.Community,
                        summary = PLACEHOLDER_TEXT,
                        endIcon = MiuixIcons.Link,
                        endIconSize = 26.dp,
                        enabled = false,
                        onClick = {},
                    )
                }
            }
            item {
                SectionTitle(stringResource(R.string.about_module))
                Card(modifier = Modifier.fillMaxWidth()) {
                    // 备份恢复与检查更新都还没做（且已移除 INTERNET 权限）：灰度。
                    SettingsActionWithArrow(
                        title = stringResource(R.string.backup_restore),
                        icon = MiuixIcons.Backup,
                        enabled = false,
                        onClick = {},
                    )
                    SettingsAction(
                        title = stringResource(R.string.check_update_action),
                        icon = MiuixIcons.Update,
                        summary = PLACEHOLDER_TEXT,
                        enabled = false,
                        onClick = {},
                    )
                }
            }
            item {
                SectionTitle(stringResource(R.string.about_project))
                Card(modifier = Modifier.fillMaxWidth()) {
                    SettingsAction(
                        title = stringResource(R.string.github),
                        icon = MiuixIcons.Info,
                        summary = "$DEVELOPER_HANDLE/Hyper-MeloLock",
                        endIcon = MiuixIcons.Link,
                        endIconSize = 26.dp,
                    ) {
                        context.openUrl(REPO_URL)
                    }
                    SettingsAction(
                        title = stringResource(R.string.changelog),
                        icon = MiuixIcons.Info,
                        summary = "GitHub Releases",
                        endIcon = MiuixIcons.Link,
                        endIconSize = 26.dp,
                    ) {
                        context.openUrl(RELEASES_URL)
                    }
                    // 引用清单与隐私政策还没有页面：灰度。
                    SettingsActionWithArrow(
                        title = stringResource(R.string.references),
                        icon = MiuixIcons.Info,
                        enabled = false,
                        onClick = {},
                    )
                    SettingsAction(
                        title = stringResource(R.string.privacy_consent_title),
                        icon = MiuixIcons.Info,
                        summary = PLACEHOLDER_TEXT,
                        endIcon = MiuixIcons.Link,
                        endIconSize = 26.dp,
                        enabled = false,
                        onClick = {},
                    )
                }
            }
            item {
                SectionTitle("开源说明")
                Card(modifier = Modifier.fillMaxWidth()) {
                    InfoText(
                        "本项目：AGPL-3.0\n" +
                            "界面来源：HyperIsland（MIT License），配置端 Compose/Miuix 组件直接复用。" +
                            "锁屏 Hook 与配置链路为本项目独立实现。",
                    )
                }
            }
        }
        // hero 画在列表之后（更上层），淡出过程中列表内容是「从下面盖上来」的观感。
        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .height(heroHeight)
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Column(
                modifier = Modifier
                    .offset(y = ABOUT_HERO_CONTENT_OFFSET)
                    .graphicsLayer {
                        alpha = logoAlpha
                        scaleX = logoScale
                        scaleY = logoScale
                    },
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Image(
                    painter = painterResource(R.drawable.ic_hmsc),
                    contentDescription = null,
                    modifier = Modifier.size(88.dp).clip(RoundedCornerShape(22.dp)),
                )
                Text(
                    text = stringResource(R.string.app_name),
                    modifier = Modifier.padding(top = 18.dp),
                    fontSize = 34.sp,
                    fontWeight = FontWeight.Bold,
                    color = MiuixTheme.colorScheme.onSurface,
                )
                Text(
                    text = "${BuildConfig.VERSION_NAME}(${BuildConfig.VERSION_CODE})",
                    modifier = Modifier.padding(top = 6.dp),
                    fontSize = 15.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
        }
    }
}

/** 开发者卡片：头像、名称、GitHub 号，整卡可点直达 GitHub。 */
@Composable
private fun DeveloperCard() {
    val context = LocalContext.current
    Card(
        modifier = Modifier.fillMaxWidth(),
        onClick = { context.openUrl(GITHUB_URL) },
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 作者头像：`res/drawable-nodpi/dev_avatar.jpg`（圆裁展示）。
            Image(
                painter = painterResource(R.drawable.dev_avatar),
                contentDescription = "开发者头像",
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(56.dp)
                    .clip(CircleShape),
            )
            Column(modifier = Modifier.padding(start = 14.dp)) {
                Text(
                    text = DEVELOPER_NAME,
                    fontSize = 19.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MiuixTheme.colorScheme.onSurface,
                )
                Text(
                    text = "@$DEVELOPER_HANDLE",
                    modifier = Modifier.padding(top = 1.dp),
                    fontSize = 14.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
            Spacer(Modifier.weight(1f))
            Icon(
                imageVector = MiuixIcons.Basic.ArrowRight,
                contentDescription = null,
                modifier = Modifier.size(width = 10.dp, height = 16.dp),
                tint = MiuixTheme.colorScheme.onSurfaceVariantActions,
            )
        }
    }
}

// ── 复用组件 ─────────────────────────────────────────────────────────────────

@Composable
private fun LinkAction(title: String, summary: String, url: String) {
    val context = LocalContext.current
    val filled = url.isNotBlank()
    SettingsAction(
        title = title,
        summary = if (filled) summary else PLACEHOLDER_TEXT,
        endIcon = MiuixIcons.Link,
        endIconSize = 26.dp,
        enabled = filled,
        onClick = { if (filled) context.openUrl(url) },
    )
}

@Composable
private fun DpSlider(
    title: String,
    value: Int,
    range: IntRange,
    unit: String = "dp",
    step: Int = 1,
    onCommit: (Int) -> Unit,
) {
    var current by remember(value) { mutableStateOf(value.toFloat()) }
    PreferenceSlider(
        title = title,
        summary = null,
        icon = null,
        value = current,
        valueText = if (unit.isEmpty()) current.toInt().toString() else "${current.toInt()} $unit",
        valueRange = range.first.toFloat()..range.last.toFloat(),
        steps = (range.last - range.first) / step - 1,
        allowManualInput = false,
        onValueChange = { raw -> current = ((raw / step).roundToInt() * step).toFloat() },
        onValueChangeFinished = { onCommit(current.toInt()) },
    )
}

@Composable
private fun MediaAppRow(
    label: String,
    packageName: String,
    repository: InstalledAppsRepository,
    selected: Boolean,
    onSelectedChange: (Boolean) -> Unit,
) {
    val icon by produceState<ImageBitmap?>(repository.cachedIcon(packageName), packageName) {
        if (value == null) {
            value = withContext(Dispatchers.IO) { repository.loadIcon(packageName) }
        }
    }
    Card(modifier = Modifier.fillMaxWidth()) {
        BasicComponent(
            startAction = {
                Box(
                    modifier = Modifier.padding(end = 14.dp).size(42.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    val currentIcon = icon
                    if (currentIcon != null) {
                        Image(
                            bitmap = currentIcon,
                            contentDescription = null,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            },
            endActions = { Switch(checked = selected, onCheckedChange = onSelectedChange) },
            insideMargin = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            onClick = { onSelectedChange(!selected) },
        ) {
            Text(
                text = label,
                fontSize = MiuixTheme.textStyles.headline1.fontSize,
                fontWeight = FontWeight.Medium,
                color = MiuixTheme.colorScheme.onBackground,
            )
            Text(
                text = packageName,
                fontSize = MiuixTheme.textStyles.footnote1.fontSize,
                fontWeight = FontWeight.Medium,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun InfoText(value: String) {
    Text(
        text = value,
        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        fontSize = MiuixTheme.textStyles.body2.fontSize,
    )
}

// ── 数据 ─────────────────────────────────────────────────────────────────────

private data class FrameworkDetails(
    val name: String,
    val version: String,
    val versionCode: Int,
    val apiVersion: Int,
)

/** 已勾选状态；[unrestricted] 为真表示尚未限制，允许全部播放器。 */
private data class MusicSelection(val unrestricted: Boolean, val packages: Set<String>) {
    fun isSelected(packageName: String): Boolean = unrestricted || packages.contains(packageName)
}

/**
 * 默认任何应用都不勾选。
 *
 * 旧逻辑是 `unrestricted = packages.isEmpty()`，也就是「还没设置过＝允许全部」，
 * 装好之后没做任何选择时任何 App 的 MediaSession 都能拉起覆盖层。现在与
 * [Config.packageAllowed] 对齐：默认空勾选＝不启用，用户必须显式勾选；
 * 「全部应用」仍作为一个可切换的选项留在界面上，但不再是默认值。
 */
private fun loadMusicSelection(context: Context): MusicSelection {
    val packages = Config.selectedPackages(context)
    // 排查「默认是不是全勾上了」时全靠这一行：0 就是真的一个都没勾。
    Log.i(APP_LOG_TAG, "Music apps: ${packages.size} selected by default")
    return MusicSelection(unrestricted = false, packages = packages)
}

/** 从 Vector/LSPosed 服务读取框架信息；legacy 模块拿不到服务时返回 null。 */
private fun loadFrameworkDetails(context: Context): FrameworkDetails? {
    if (!XposedPrefsSyncApp.awaitReady()) return null
    val app = context.applicationContext as? XposedPrefsSyncApp ?: return null
    val info = runCatching { app.getFrameworkInfo() }.getOrNull() ?: return null
    val apiVersion = (info["apiVersion"] as? Number)?.toInt() ?: 0
    val name = info["frameworkName"]?.toString().orEmpty()
    val version = info["frameworkVersion"]?.toString().orEmpty()
    if (name.isBlank() && version.isBlank() && apiVersion == 0) return null
    return FrameworkDetails(
        name = name,
        version = version,
        versionCode = (info["frameworkVersionCode"] as? Number)?.toInt() ?: 0,
        apiVersion = apiVersion,
    )
}

private fun Context.openUrl(url: String) {
    runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
}

// 留空的链接（捐赠 / 使用教程 / 相关资源）在页面上显示「待填写」并置灰。
private const val DEVELOPER_NAME = "zuige"
private const val DEVELOPER_HANDLE = "zuige66"
/** 开发者主页：开发者卡片整卡点击的去处。 */
private const val GITHUB_URL = "https://github.com/zuige66"
private const val REPO_URL = "https://github.com/zuige66/Hyper-MeloLock"
private const val RELEASES_URL = "$REPO_URL/releases"
private const val DONATION_URL = ""
private const val DOCUMENTATION_URL = ""
private const val RESOURCES_URL = ""
private const val PLACEHOLDER_TEXT = "待填写"

// 「开发者」页 hero 的尺寸参数，与上游 AboutPage 同一套比例。
private const val ABOUT_HERO_HEIGHT_FRACTION = 0.60f
private val ABOUT_DEVELOPER_TOP_GAP = 16.dp
private val ABOUT_HERO_CONTENT_OFFSET = 30.dp

/** HyperOS 上枚举全部应用需要的 MIUI 权限，与 HyperIsland 应用页一致。 */
private const val APP_LIST_PERMISSION = "com.android.permission.GET_INSTALLED_APPS"

private const val APP_LOG_TAG = "MeloLock[App]"

private val PALETTE = intArrayOf(0xFF111827.toInt(), 0xFF253B80.toInt(), 0xFF5B2C83.toInt(), 0xFF14532D.toInt(), 0xFF000000.toInt())

/**
 * 播放器卡片底色档位（M3 风格 tonal 配色）。值的顺序与标签一一对应。
 * 与覆盖层 `isLightColor` 的亮度联动判定相配：浅档必须足够亮（自动配深字），
 * 深档足够暗（自动配白字）。「深色」＝历史硬编码黑，老配置外观不变。
 * 「跟随封面」值 0＝覆盖层从专辑封面取主色调成低饱和容器色（异步，换歌时自动更新）。
 */
private val CARD_BG_VALUES = intArrayOf(
    0, //                跟随封面：动态取色
    0xF2181818.toInt(), // 深色：原硬编码近黑
    0xF21E2A3C.toInt(), // 墨蓝：M3 深色容器调
    0xF2C7C7CC.toInt(), // 浅色：中性浅灰
    0xF2B6C1D6.toInt(), // 蓝灰：系统浅色磨砂同款（半透明淡蓝紫灰）
    0xF2D8CEF4.toInt(), // 淡紫：M3 secondaryContainer 系
    0xF2F4CEDA.toInt(), // 淡粉：M3 tertiaryContainer 系
)
private val CARD_BG_LABELS = listOf("跟随封面", "深色", "墨蓝", "浅色", "蓝灰", "淡紫", "淡粉")

/**
 * 文字颜色统一档位（时间/日期/签名/通知入口共用，与播放器「配色统一」）：
 * 「跟随封面」值 0＝文字颜色按取色容器亮度联动（主文字深/白，辅助文字灰阶）；其余为固定色。
 */
private val TEXT_COLOR_VALUES = intArrayOf(
    0, //                跟随封面
    0xFFFFFFFF.toInt(), // 白
    0xFF000000.toInt(), // 黑
    0xFFC7C7CC.toInt(), // 浅灰
    0xFFFFD479.toInt(), // 暖黄
    0xFF7EC8FF.toInt(), // 天蓝
    0xFFFF9EAD.toInt(), // 粉
)
private val TEXT_COLOR_LABELS = listOf("跟随封面", "白", "黑", "浅灰", "暖黄", "天蓝", "粉")

/**
 * 签名内容输入框。**三重保存**：① 输入停顿 800ms 自动写盘（LaunchedEffect 防抖，text
 * 变化重启协程）；② 页面退出时兜底写盘（onDispose，兜住「输完直接返回」）；③ IME 确认。
 * 签名变化会触发锁屏场景整场重建，防抖保证停顿期间至多重建一次。
 */
@Composable
private fun SignatureInputField() {
    val context = LocalContext.current
    var text by remember { mutableStateOf(Config.elementString(context, Config.DATE_SIGNATURE)) }
    fun commit() {
        if (text != Config.elementString(context, Config.DATE_SIGNATURE)) {
            Config.setElementText(context, Config.DATE_SIGNATURE, text)
        }
    }
    LaunchedEffect(text) {
        if (text != Config.elementString(context, Config.DATE_SIGNATURE)) {
            kotlinx.coroutines.delay(800)
            commit()
        }
    }
    DisposableEffect(Unit) {
        onDispose { commit() }
    }
    TextField(
        value = text,
        onValueChange = { text = it },
        modifier = Modifier.fillMaxWidth(),
        label = "签名内容",
        useLabelAsPlaceholder = true,
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { commit() }),
    )
}

/** 时间字体的圆润档位；0 用系统字体，1 / 2 用内置的开源圆体数字字体。 */
private val ROUNDNESS_LABELS = listOf("直角", "中等圆", "很圆")
