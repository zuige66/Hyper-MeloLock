package io.github.hyperisland.compose.page

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.hyperisland.BuildConfig
import io.github.hyperisland.R
import io.github.hyperisland.XposedPrefsSyncApp
import io.github.hyperisland.compose.component.CollapsingPage
import io.github.hyperisland.compose.component.PreferenceDropdown
import io.github.hyperisland.compose.component.PreferenceSlider
import io.github.hyperisland.compose.component.PreferenceSwitch
import io.github.hyperisland.compose.component.SectionTitle
import io.github.hyperisland.compose.component.SettingsAction
import io.github.hyperisland.compose.data.InstalledAppsRepository
import io.github.hyperisland.compose.page.home.OverviewAlertCard
import io.github.hyperisland.compose.page.home.OverviewInfoCard
import io.github.hyperisland.compose.page.home.OverviewStatusGrid
import io.github.hyperisland.compose.service.HomeSystemInfo
import io.github.hyperisland.compose.service.SystemInfoProvider
import io.github.melolock.Config
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.InputField
import top.yukonga.miuix.kmp.basic.SearchBar
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Info
import top.yukonga.miuix.kmp.icon.extended.Link
import top.yukonga.miuix.kmp.icon.extended.Refresh
import top.yukonga.miuix.kmp.theme.MiuixTheme

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
        actionDescription = "刷新状态",
        onAction = { refreshToken++ },
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
                        "点击上方状态卡可随时开关模块，关闭后立即恢复原生锁屏。",
                )
            }
        }
    }
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
                    "这里列出全部已安装应用，勾选允许进入锁屏的播放器即可。" +
                        "首次进入默认允许全部；全部取消后锁屏不再接管任何播放器。",
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
            SectionTitle("时间")
            Card {
                DpSlider("字号", value(Config.CLOCK_SIZE), 20..120, onCommit = { update(Config.CLOCK_SIZE, it) })
                DpSlider("粗细", value(Config.CLOCK_WEIGHT), 100..900, unit = "", step = 10, onCommit = { update(Config.CLOCK_WEIGHT, it) })
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
                    summary = "时间文字颜色",
                    icon = null,
                    items = CLOCK_COLOR_LABELS,
                    selectedIndex = CLOCK_COLORS.indexOf(value(Config.CLOCK_COLOR)).coerceAtLeast(0),
                    onSelectedIndexChange = { update(Config.CLOCK_COLOR, CLOCK_COLORS[it]) },
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
            SectionTitle("背景")
            Card {
                PreferenceDropdown(
                    title = "背景样式",
                    summary = "影响锁屏背景的模糊效果",
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
                    "以上尺寸、圆角、间距和字体设置都由锁屏覆盖层在创建时读取，" +
                        "改完需要灭屏再亮屏一次才会生效。",
                )
            }
        }
    }
}

// ── 开发者 ───────────────────────────────────────────────────────────────────

@Composable
internal fun LockAboutPage() {
    CollapsingPage(title = "开发者") {
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 22.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Image(
                        painter = painterResource(R.drawable.ic_hmsc),
                        contentDescription = null,
                        modifier = Modifier.size(88.dp).clip(RoundedCornerShape(22.dp)),
                    )
                    Text(
                        text = stringResource(R.string.app_name),
                        modifier = Modifier.padding(top = 14.dp),
                        fontSize = 19.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MiuixTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                        modifier = Modifier.padding(top = 2.dp),
                        fontSize = 13.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
            }
        }
        item {
            SectionTitle("开发者")
            Card {
                // 作者信息待补充：把下面的常量填上即可显示真实内容。
                SettingsAction(
                    title = "开发者",
                    summary = DEVELOPER_NAME.ifBlank { PLACEHOLDER_TEXT },
                    icon = MiuixIcons.Info,
                    enabled = false,
                    onClick = {},
                )
                SettingsAction(
                    title = "联系方式",
                    summary = DEVELOPER_CONTACT.ifBlank { PLACEHOLDER_TEXT },
                    icon = MiuixIcons.Info,
                    enabled = false,
                    onClick = {},
                )
            }
        }
        item {
            SectionTitle("项目")
            Card {
                LinkAction(title = "GitHub", summary = DEVELOPER_HANDLE, url = GITHUB_URL)
                LinkAction(title = "项目主页", summary = "源码与发布", url = PROJECT_URL)
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
            SectionTitle("开源说明")
            Card {
                InfoText(
                    "本项目：AGPL-3.0\n" +
                        "界面来源：HyperIsland（MIT License），配置端 Compose/Miuix 组件直接复用。" +
                        "锁屏 Hook 与配置链路为本项目独立实现。",
                )
            }
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
private fun loadMusicSelection(context: Context): MusicSelection =
    MusicSelection(unrestricted = false, packages = Config.selectedPackages(context))

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

// TODO(作者信息)：以下链接与署名待补充，留空时页面会显示“待填写”并置灰。
private const val DEVELOPER_NAME = "zuige"
private const val DEVELOPER_HANDLE = "zuige66"
private const val DEVELOPER_CONTACT = ""
private const val GITHUB_URL = "https://github.com/zuige66"
private const val PROJECT_URL = "https://github.com/zuige66"
private const val DONATION_URL = ""
private const val DOCUMENTATION_URL = ""
private const val RESOURCES_URL = ""
private const val PLACEHOLDER_TEXT = "待填写"

/** HyperOS 上枚举全部应用需要的 MIUI 权限，与 HyperIsland 应用页一致。 */
private const val APP_LIST_PERMISSION = "com.android.permission.GET_INSTALLED_APPS"

private const val APP_LOG_TAG = "MeloLock[App]"

private val PALETTE = intArrayOf(0xFF111827.toInt(), 0xFF253B80.toInt(), 0xFF5B2C83.toInt(), 0xFF14532D.toInt(), 0xFF000000.toInt())

/** 时间字体的圆润档位；0 用系统字体，1 / 2 用内置的开源圆体数字字体。 */
private val ROUNDNESS_LABELS = listOf("直角", "中等圆", "很圆")
private val CLOCK_COLOR_LABELS = listOf("白色", "黑色", "浅灰", "暖黄", "天蓝", "粉")
private val CLOCK_COLORS = intArrayOf(
    0xFFFFFFFF.toInt(),
    0xFF000000.toInt(),
    0xFFC7C7CC.toInt(),
    0xFFFFD479.toInt(),
    0xFF7EC8FF.toInt(),
    0xFFFFB4C8.toInt(),
)
