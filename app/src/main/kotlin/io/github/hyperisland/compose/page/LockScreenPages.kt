package io.github.hyperisland.compose.page

import android.content.BroadcastReceiver
import android.content.IntentFilter
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
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.clickable
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
import androidx.compose.runtime.saveable.rememberSaveable
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
import io.github.hyperisland.compose.component.UpdateDialogHost
import io.github.hyperisland.compose.component.UpdateDialogState
import io.github.hyperisland.compose.component.rememberBrowserLauncher
import io.github.hyperisland.compose.data.InstalledApp
import io.github.hyperisland.compose.data.InstalledAppsRepository
import io.github.hyperisland.compose.page.home.OverviewAlertCard
import io.github.hyperisland.compose.page.home.OverviewInfoCard
import io.github.hyperisland.compose.page.home.OverviewStatusGrid
import io.github.hyperisland.compose.service.ApkInstaller
import io.github.hyperisland.utils.SystemPropertyReader
import io.github.hyperisland.compose.service.UpdateDownloadService
import io.github.hyperisland.compose.service.UpdateService
import io.github.melolock.Config
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.roundToInt
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.InputField
import top.yukonga.miuix.kmp.basic.SearchBar
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.basic.ArrowRight
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
    var verified by remember { mutableStateOf(Config.deviceVerified()) }
    val scope = rememberCoroutineScope()
    var showRestartDialog by remember { mutableStateOf(false) }

    // 信息卡显示值**首帧直出**：全部从本地缓存（`INFO_CACHE_PREFS`）同步读。此前每次进 App
    // 都要现读——`android.os.SystemProperties` 反射在新系统上会被 hidden API 限制拦掉、退回
    // getprop 子进程（几百 ms～1s），框架信息的服务绑定更要等 1.5s 超时，表现为「Xposed 框架
    // 与设备型号每次都要等一会才出现」。页面可见时后台刷新一次（zuige：下一次进 App 检查
    // 有没有变化），值有变化才更新界面并回写缓存；首次安装无缓存时先显示「未知」。
    val infoCache = remember { context.getSharedPreferences(INFO_CACHE_PREFS, Context.MODE_PRIVATE) }
    var systemVersion by remember {
        mutableStateOf(infoCache.getString("system_version", "").orEmpty())
    }
    var deviceModel by remember {
        mutableStateOf(infoCache.getString("device_model", "").orEmpty())
    }
    var frameworkText by remember {
        mutableStateOf(infoCache.getString("framework_text", null))
    }

    LaunchedEffect(isActive) {
        if (!isActive) return@LaunchedEffect
        enabled = Config.enabled(context)
        enabledAppCount = Config.enabledAppCount(context)
        cornerRadiusDp = Config.cornerRadiusDp(context)
        verified = Config.deviceVerified()
        val loaded = withContext(Dispatchers.IO) {
            val version = SystemPropertyReader.get("ro.build.version.incremental")
                .ifBlank { Build.VERSION.INCREMENTAL.orEmpty() }
            val model = SystemPropertyReader.get("ro.product.marketname")
                .ifBlank { Build.MODEL.orEmpty() }
            val framework = runCatching { loadFrameworkDetails(context) }
                .onFailure { Log.w(APP_LOG_TAG, "Framework details unavailable", it) }
                .getOrNull()
            Triple(version, model, framework)
        }
        val unknownText = context.getString(R.string.unknown)
        val loadedFrameworkText = loaded.third?.summary ?: loaded.third?.let {
            context.getString(
                R.string.framework_details,
                it.name.ifBlank { unknownText },
                it.version.ifBlank { unknownText },
                it.versionCode,
                it.apiVersion,
            )
        } ?: unknownText
        var changed = false
        val editor = infoCache.edit()
        if (loaded.first != systemVersion) {
            systemVersion = loaded.first
            editor.putString("system_version", loaded.first)
            changed = true
        }
        if (loaded.second != deviceModel) {
            deviceModel = loaded.second
            editor.putString("device_model", loaded.second)
            changed = true
        }
        if (loadedFrameworkText != frameworkText) {
            frameworkText = loadedFrameworkText
            editor.putString("framework_text", loadedFrameworkText)
            changed = true
        }
        if (changed) editor.apply()
    }

    val unknown = stringResource(R.string.unknown)
    val systemVersionText = systemVersion.ifBlank { unknown }
    val deviceModelText = deviceModel.ifBlank { unknown }
    val frameworkTextShown = frameworkText.orEmpty().ifBlank { unknown }

    CollapsingPage(
        title = stringResource(R.string.app_name),
        actionIcon = MiuixIcons.Refresh,
        actionDescription = stringResource(R.string.restart_scope),
        // 重启作用域：与 HyperIsland 一致——**点进去先选作用域、再点重启**。
        // 不再按 root 探测结果决定是否弹窗：弹窗一律显示（默认全选），重启本身需要 root，
        // 拿不到 root 时由弹窗给出「请检查是否已给予本应用 ROOT 权限」，不做隐式广播回退。
        onAction = {
            showRestartDialog = true
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
        if (!verified) {
            // 机型门禁已撤（多机型测试中）：任何设备都尝试启用，只有验证过的基准机型不提示。
            item {
                OverviewAlertCard(
                    title = "当前机型未验证",
                    message = "模块已在这台设备上启用；该机型尚未完整验证，如遇锁屏显示异常请在 GitHub 反馈。",
                )
            }
        }
        item {
            OverviewInfoCard(
                rows = listOf(
                    stringResource(R.string.system_version) to systemVersionText,
                    stringResource(R.string.app_version) to
                        "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                    stringResource(R.string.xposed_framework) to frameworkTextShown,
                    stringResource(R.string.device_model) to deviceModelText,
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
            Text(
                text = "Vector 中仅勾选 SystemUI；右上角按钮可重启作用域；更新 APK 后需重新确认模块开关。",
                modifier = Modifier.padding(horizontal = 18.dp, vertical = 12.dp),
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                fontSize = MiuixTheme.textStyles.footnote1.fontSize,
            )
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

    val filtered = remember(apps, query, showSystemApps, selection) {
        val normalized = query.trim().lowercase()
        apps.filter { app ->
            (showSystemApps || !app.isSystem) &&
                (normalized.isEmpty() ||
                    app.appName.lowercase().contains(normalized) ||
                    app.packageName.lowercase().contains(normalized))
        }
            // 已勾选的排前面（组内按名称），勾完不用在长列表里翻。
            .sortedWith(
                compareByDescending<InstalledApp> {
                    selection.isSelected(it.packageName)
                }.thenBy { it.appName.lowercase() },
            )
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

    CollapsingPage(title = "应用") {
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
            Text(
                text = "默认不接管任何播放器，需在此显式勾选；「全选」只作用于当前列表。",
                modifier = Modifier.padding(horizontal = 18.dp, vertical = 12.dp),
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                fontSize = MiuixTheme.textStyles.footnote1.fontSize,
            )
        }
    }
}

// ── 外观 ─────────────────────────────────────────────────────────────────────

@Composable
internal fun LockAppearancePage() {
    val context = LocalContext.current
    val screenWidthDp = LocalConfiguration.current.screenWidthDp

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

    // 分组手风琴：默认全收起，点标题展开/收起；跨页面切换用 rememberSaveable 记住。
    var expandedSections by rememberSaveable { mutableStateOf(setOf<String>()) }
    fun toggleSection(key: String) {
        expandedSections = if (key in expandedSections) expandedSections - key else expandedSections + key
    }

    // 各分组的「恢复默认」键集（↺ 按钮）；背景组例外：三态存 SharedPreferences 非 elements。
    val sectionDefaultKeys: Map<String, List<String>> = mapOf(
        "pick" to listOf(Config.SWATCH_PICK),
        "date" to listOf(Config.DATE_ENABLED, Config.DATE_SIZE, Config.DATE_WEIGHT, Config.DATE_COLOR, Config.DATE_SPACING),
        "sign" to listOf(Config.SIGN_ENABLED, Config.SIGN_SIZE, Config.SIGN_WEIGHT, Config.SIGN_COLOR, Config.SIGN_SPACING),
        "clock" to listOf(Config.CLOCK_SIZE, Config.CLOCK_SPACING, Config.CLOCK_WEIGHT, Config.CLOCK_COLOR, Config.CLOCK_ROUNDNESS, Config.CLOCK_STROKE),
        "cover" to listOf(Config.COVER_SCALE, Config.COVER_WIDTH, Config.COVER_HEIGHT, Config.COVER_SPACING),
        "card" to listOf(Config.CARD_SCALE, Config.CARD_WIDTH, Config.CARD_HEIGHT, Config.CARD_RADIUS, Config.CARD_SPACING, Config.CARD_BG, Config.CARD_BG_PICK),
        "entry" to listOf(Config.ENTRY_COLOR, Config.ENTRY_COLOR_PICK, Config.ENTRY_BG, Config.ENTRY_BG_PICK),
    )
    fun resetSection(key: String) {
        sectionDefaultKeys[key]?.forEach { k ->
            val defaultValue = Config.elementDefault(k)
            Config.setElementInt(context, k, defaultValue)
            settings = settings + (k to defaultValue)
        }
        when (key) {
            "sign" -> Config.setElementText(context, Config.DATE_SIGNATURE, "")   // 签名默认空白
            "cover" -> { radius = 28f; Config.setCornerRadiusDp(context, 28) }    // 圆角全局默认 28
            "backdrop" -> {                                                       // 三态回默认：玻璃/深蓝灰/150
                style = 0; Config.setOverlayStyle(context, 0)
                colorIndex = 0; Config.setOverlayColor(context, PALETTE[0])
                alpha = 150f; Config.setOverlayAlpha(context, 150)
            }
        }
    }

    CollapsingPage(title = "外观") {
        item {
            CollapsibleSection("取色", "pick", expandedSections, ::toggleSection, { resetSection("pick") }) {
                PreferenceDropdown(
                    title = "主色来源",
                    summary = "跟随封面时挑选专辑主色的方式",
                    icon = null,
                    items = listOf("最鲜艳优先", "占比优先"),
                    selectedIndex = value(Config.SWATCH_PICK).coerceIn(0, 1),
                    onSelectedIndexChange = { update(Config.SWATCH_PICK, it) },
                )
            }
        }
        item {
            CollapsibleSection("日期", "date", expandedSections, ::toggleSection, { resetSection("date") }) {
                PreferenceSwitch(
                    title = "显示日期",
                    summary = "时钟上方显示公历、周几与农历",
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
                    PickStyleDropdown(Config.DATE_COLOR, Config.DATE_PICK, ::value, ::update)
                    DpSlider("上间距", value(Config.DATE_SPACING), 0..160, onCommit = { update(Config.DATE_SPACING, it) })
                }
            }
        }
        item {
            CollapsibleSection("签名", "sign", expandedSections, ::toggleSection, { resetSection("sign") }) {
                PreferenceSwitch(
                    title = "显示签名",
                    summary = "日期行下方的自定义文字",
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
                    PickStyleDropdown(Config.SIGN_COLOR, Config.SIGN_PICK, ::value, ::update)
                    DpSlider("上间距", value(Config.SIGN_SPACING), 0..160, onCommit = { update(Config.SIGN_SPACING, it) })
                }
            }
        }
        item {
            CollapsibleSection("时间", "clock", expandedSections, ::toggleSection, { resetSection("clock") }) {
                DpSlider("字号", value(Config.CLOCK_SIZE), 20..120, onCommit = { update(Config.CLOCK_SIZE, it) })
                DpSlider("粗细", value(Config.CLOCK_WEIGHT), 100..900, unit = "", step = 10, onCommit = { update(Config.CLOCK_WEIGHT, it) })
                DpSlider("描边加粗", value(Config.CLOCK_STROKE), 0..8, onCommit = { update(Config.CLOCK_STROKE, it) })
                PreferenceDropdown(
                    title = "字体圆润",
                    summary = "圆体数字字体，只影响数字与冒号",
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
                PickStyleDropdown(Config.CLOCK_COLOR, Config.CLOCK_PICK, ::value, ::update)
                DpSlider("上间距", value(Config.CLOCK_SPACING), 0..160, onCommit = { update(Config.CLOCK_SPACING, it) })
            }
        }
        item {
            CollapsibleSection("专辑封面", "cover", expandedSections, ::toggleSection, { resetSection("cover") }) {
                DpSlider("缩放", value(Config.COVER_SCALE), 10..300, unit = "%", onCommit = { update(Config.COVER_SCALE, it) })
                DpSlider("宽度 (0=自动)", value(Config.COVER_WIDTH), 0..600, onCommit = { update(Config.COVER_WIDTH, it) })
                DpSlider("高度 (0=自动)", value(Config.COVER_HEIGHT), 0..600, onCommit = { update(Config.COVER_HEIGHT, it) })
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
                DpSlider("上间距", value(Config.COVER_SPACING), 0..160, onCommit = { update(Config.COVER_SPACING, it) })
            }
        }
        item {
            CollapsibleSection("播放器", "card", expandedSections, ::toggleSection, { resetSection("card") }) {
                DpSlider("缩放", value(Config.CARD_SCALE), 10..300, unit = "%", onCommit = { update(Config.CARD_SCALE, it) })
                DpSlider("宽度 (0=自动)", value(Config.CARD_WIDTH), 0..600, onCommit = { update(Config.CARD_WIDTH, it) })
                DpSlider("高度 (0=自动)", value(Config.CARD_HEIGHT), 0..600, onCommit = { update(Config.CARD_HEIGHT, it) })
                DpSlider("圆角", value(Config.CARD_RADIUS), 0..48, onCommit = { update(Config.CARD_RADIUS, it) })
                DpSlider("上间距", value(Config.CARD_SPACING), 0..160, onCommit = { update(Config.CARD_SPACING, it) })
                PreferenceDropdown(
                    title = "底色",
                    summary = "卡片背景色，文字按亮度自动适配",
                    icon = null,
                    items = CARD_BG_LABELS,
                    selectedIndex = CARD_BG_VALUES.indexOf(value(Config.CARD_BG)).coerceAtLeast(0),
                    onSelectedIndexChange = { update(Config.CARD_BG, CARD_BG_VALUES[it]) },
                )
                PickStyleDropdown(Config.CARD_BG, Config.CARD_BG_PICK, ::value, ::update)
                PreferenceSwitch(
                    title = "锁屏禁止左下拉",
                    summary = "锁屏时禁用左侧下拉；左半屏上滑解锁随之失效",
                    icon = null,
                    checked = value(Config.BLOCK_LEFT_SHADE) != 0,
                    onCheckedChange = { update(Config.BLOCK_LEFT_SHADE, if (it) 1 else 0) },
                )
            }
        }
        item {
            CollapsibleSection("通知入口", "entry", expandedSections, ::toggleSection, { resetSection("entry") }) {
                PreferenceDropdown(
                    title = "文字颜色",
                    summary = "「展开通知」的文字颜色，可选跟随封面",
                    icon = null,
                    items = TEXT_COLOR_LABELS,
                    selectedIndex = TEXT_COLOR_VALUES.indexOf(value(Config.ENTRY_COLOR)).coerceAtLeast(0),
                    onSelectedIndexChange = { update(Config.ENTRY_COLOR, TEXT_COLOR_VALUES[it]) },
                )
                PickStyleDropdown(Config.ENTRY_COLOR, Config.ENTRY_COLOR_PICK, ::value, ::update)
                PreferenceDropdown(
                    title = "胶囊背景",
                    summary = "按钮胶囊的背景色，可选跟随封面",
                    icon = null,
                    items = CARD_BG_LABELS,
                    selectedIndex = CARD_BG_VALUES.indexOf(value(Config.ENTRY_BG)).coerceAtLeast(0),
                    onSelectedIndexChange = { update(Config.ENTRY_BG, CARD_BG_VALUES[it]) },
                )
                PickStyleDropdown(Config.ENTRY_BG, Config.ENTRY_BG_PICK, ::value, ::update)
            }
        }
        item {
            CollapsibleSection("背景", "backdrop", expandedSections, ::toggleSection, { resetSection("backdrop") }) {
                PreferenceDropdown(
                    title = "背景样式",
                    summary = "玻璃风格模糊封面，沉浸风格纯色底",
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
            Text(
                text = "外观参数在锁屏重建时生效：改完灭屏再亮屏一次即可（无需重启 SystemUI）。",
                modifier = Modifier.padding(horizontal = 18.dp, vertical = 12.dp),
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                fontSize = MiuixTheme.textStyles.footnote1.fontSize,
            )
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
 * 本模块还没有的东西（讨论 / 引用 / 隐私政策）一律**灰度不可点**；检查更新位于「关于模块」区块。
 */
@Composable
internal fun LockAboutPage(isActive: Boolean) {
    val context = LocalContext.current
    // 开发者页外链（GitHub / 更新日志）统一走确认弹窗，与首页 LinkAction 行为一致。
    val openBrowserLink = rememberBrowserLauncher()
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

    // 检查更新：请求本仓库 GitHub Releases；有新版弹更新对话框，无新版 Toast，失败弹失败对话框。
    var isCheckingUpdate by remember { mutableStateOf(false) }
    var updateDialogState by remember { mutableStateOf<UpdateDialogState?>(null) }
    /** APK 下载进度（0..100）；-1＝未在下载。由 UpdateDownloadService 的显式广播驱动。 */
    var downloadPercent by remember { mutableIntStateOf(-1) }
    DisposableEffect(Unit) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(receiverContext: Context, intent: Intent) {
                val percent = intent.getIntExtra(UpdateDownloadService.EXTRA_PROGRESS, -1)
                val done = intent.getBooleanExtra(UpdateDownloadService.EXTRA_DONE, false)
                downloadPercent = if (done) -1 else percent.coerceIn(0, 100)
            }
        }
        val filter = IntentFilter(UpdateDownloadService.ACTION_UPDATE_PROGRESS)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(receiver, filter)
        }
        onDispose { try { context.unregisterReceiver(receiver) } catch (_: Throwable) { } }
    }
    val scope = rememberCoroutineScope()
    fun requestUpdateCheck() {
        if (isCheckingUpdate) return
        isCheckingUpdate = true
        scope.launch {
            try {
                val update = UpdateService.fetchIfNewer(
                    BuildConfig.VERSION_NAME,
                    currentVersionCode = BuildConfig.VERSION_CODE,
                    api = UPDATE_CHECK_API,
                    downloadUrl = RELEASES_URL,
                )
                isCheckingUpdate = false
                updateDialogState = if (update != null) {
                    UpdateDialogState.Available(BuildConfig.VERSION_NAME, update)
                } else {
                    Toast.makeText(context, R.string.already_latest, Toast.LENGTH_SHORT).show()
                    null
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                isCheckingUpdate = false
                updateDialogState = UpdateDialogState.Failure
            }
        }
    }

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
                    DeveloperCard(openBrowserLink)
                }
            }
            item {
                SectionTitle(stringResource(R.string.about_discussion))
                Card(modifier = Modifier.fillMaxWidth()) {
                    // QQ 交流群（2026-10-09）：优先拉起 QQ 群资料卡（mqqapi，装了 QQ 直达），
                    // 没装 QQ 再回退官方加群短链（浏览器打开）；确认弹窗与全应用外链一致。
                    SettingsAction(
                        title = "QQ 交流群",
                        icon = MiuixIcons.Community,
                        summary = "群号 $QQ_GROUP_NUMBER",
                        endIcon = MiuixIcons.Link,
                        endIconSize = 26.dp,
                        onClick = {
                            runCatching {
                                context.startActivity(
                                    Intent(Intent.ACTION_VIEW, Uri.parse(QQ_GROUP_JUMP_URI)),
                                )
                            }.onFailure { openBrowserLink(QQ_GROUP_JOIN_URL) }
                        },
                    )
                }
            }
            item {
                SectionTitle(stringResource(R.string.about_module))
                Card(modifier = Modifier.fillMaxWidth()) {
                    // 检查更新：请求本仓库 GitHub Releases；有新版弹更新对话框，无新版 Toast，
                    // 失败弹失败对话框。位置＝「关于模块」（2026-10-09 从「关于项目」移入）。
                    SettingsAction(
                        title = stringResource(R.string.check_update_action),
                        icon = MiuixIcons.Update,
                        // 右端：下载中显示转圈 + 百分比（系统通知在 HyperOS 上常被折叠/不显示，
                        // 应用内进度才是用户真正看得到的），检查中显示纯转圈。
                        endContent = if (downloadPercent >= 0) {
                            {
                                Row(
                                    modifier = Modifier.padding(end = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    CircularProgressIndicator(size = 18.dp)
                                    Text(
                                        text = "下载中 $downloadPercent%",
                                        fontSize = MiuixTheme.textStyles.footnote1.fontSize,
                                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                    )
                                }
                            }
                        } else if (isCheckingUpdate) {
                            {
                                Box(
                                    modifier = Modifier.padding(end = 8.dp).size(26.dp),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    CircularProgressIndicator(size = 20.dp)
                                }
                            }
                        } else {
                            null
                        },
                        enabled = !isCheckingUpdate && downloadPercent < 0,
                        onClick = { requestUpdateCheck() },
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
                        openBrowserLink(REPO_URL)
                    }
                    SettingsAction(
                        title = stringResource(R.string.changelog),
                        icon = MiuixIcons.Info,
                        summary = "GitHub Releases",
                        endIcon = MiuixIcons.Link,
                        endIconSize = 26.dp,
                    ) {
                        openBrowserLink(RELEASES_URL)
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
                Text(
                    text = "本项目 AGPL-3.0；界面基于 HyperIsland（MIT）复用，锁屏 Hook 与配置链路独立实现。",
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 12.dp),
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    fontSize = MiuixTheme.textStyles.footnote1.fontSize,
                )
            }
        }
        UpdateDialogHost(
            state = updateDialogState,
            onDismiss = { updateDialogState = null },
            onDownload = { url, fallbackUrl ->
                val version = (updateDialogState as? UpdateDialogState.Available)?.update?.version
                updateDialogState = null
                // 下载交给前台服务（主源失败自动换 blog 备用源），下完自动拉起安装界面
                ApkInstaller.downloadAndInstall(
                    context,
                    url,
                    fallbackUrl,
                    "Hyper-MeloLock-v${version ?: "update"}.apk",
                )
            },
        )
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

/**
 * 开发者卡片（外观页同款折叠交互）：卡头＝头像 + 名称 + GitHub 号 + 箭头，整头可点、
 * 点击就地弹性展开/收起（animateContentSize + 箭头随状态旋转 90°），展开区是
 * GitHub / Blog 两项外链（走 [rememberBrowserLauncher] 确认弹窗）。
 */
@Composable
private fun DeveloperCard(openLink: (String) -> Unit) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val arrowRotation by animateFloatAsState(
        targetValue = if (expanded) 90f else 0f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMediumLow,
        ),
        label = "developerArrow",
    )
    Card(
        modifier = Modifier.fillMaxWidth().animateContentSize(
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioLowBouncy,
                stiffness = Spring.StiffnessMediumLow,
            ),
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(horizontal = 18.dp, vertical = 14.dp),
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
            Text(
                text = "▸",
                modifier = Modifier
                    .padding(start = 12.dp)
                    .graphicsLayer { rotationZ = arrowRotation },
                fontSize = 15.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }
        if (expanded) {
            Column(modifier = Modifier.padding(bottom = 8.dp)) {
                SettingsAction(
                    title = "GitHub",
                    summary = "$DEVELOPER_HANDLE/Hyper-MeloLock",
                    endIcon = MiuixIcons.Link,
                    endIconSize = 26.dp,
                ) {
                    openLink(DEVELOPER_GITHUB_URL)
                }
                SettingsAction(
                    title = "Blog",
                    summary = "blog.zuiges.com",
                    endIcon = MiuixIcons.Link,
                    endIconSize = 26.dp,
                ) {
                    openLink(DEVELOPER_BLOG_URL)
                }
            }
        }
    }
}

// ── 复用组件 ─────────────────────────────────────────────────────────────────

@Composable
private fun LinkAction(title: String, summary: String, url: String) {
    val context = LocalContext.current
    val openLink = rememberBrowserLauncher()
    val filled = url.isNotBlank()
    SettingsAction(
        title = title,
        summary = if (filled) summary else PLACEHOLDER_TEXT,
        endIcon = MiuixIcons.Link,
        endIconSize = 26.dp,
        enabled = filled,
        onClick = { if (filled) openLink(url) },
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
    /** 直接给出的整行文案（SystemUI 回报链路用）；非空时首页跳过 framework_details 格式串。 */
    val summary: String? = null,
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

/**
 * 框架信息两段式：先走 libxposed 服务绑定（LSPosed 管理器支持，Vector 不实现、必超时），
 * 绑不上就 fallback 读 SystemUI 回报（[loadReportedFramework]）。两路都空返回 null，
 * 首页显示「未知」。
 */
private fun loadFrameworkDetails(context: Context): FrameworkDetails? {
    loadXposedServiceFramework(context)?.let { return it }
    return loadReportedFramework(context)
}

private fun loadXposedServiceFramework(context: Context): FrameworkDetails? {
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

/**
 * SystemUI 侧回报（`HookEntry.reportFrameworkInfo` 经 /runtime 写入）：读 xposed_version
 * 与框架特征探测值。特征类名在 Vector（混淆后）上探不到，名字靠设备门禁定：模块按
 * `Config.FINGERPRINT` 精确匹配唯一 ROM，该 ROM 的框架由 SukiSU Ultra 的 Vector 提供
 * （2026-10-09 真机回报 `xposed_version=102`，LSPosed/SukiSU 特征类均未命中）。设备门禁
 * 放宽时要重新核实这里的名字映射。
 */
private fun loadReportedFramework(context: Context): FrameworkDetails? {
    val values = runCatching {
        context.contentResolver.query(Config.RUNTIME_URI, null, null, null, null)?.use { cursor ->
            val keyIndex = cursor.getColumnIndexOrThrow("key")
            val valueIndex = cursor.getColumnIndexOrThrow("value")
            buildMap {
                while (cursor.moveToNext()) put(cursor.getString(keyIndex), cursor.getString(valueIndex))
            }
        }
    }.getOrNull() ?: return null
    val apiVersion = values["xposed_version"]?.toIntOrNull() ?: return null
    if (apiVersion <= 0) return null
    return FrameworkDetails(
        name = "Vector",
        version = "",
        versionCode = 0,
        apiVersion = apiVersion,
        summary = "Vector（模块运行中，API v$apiVersion）",
    )
}

/** 首页信息卡的显示值缓存：首帧直出、后台刷新回写（见 `LockHomePage`）。 */
private const val INFO_CACHE_PREFS = "home_info_cache"

// 留空的链接（捐赠 / 使用教程 / 相关资源）在页面上显示「待填写」并置灰。
private const val DEVELOPER_NAME = "zuige"
private const val DEVELOPER_HANDLE = "zuige66"
/** 开发者卡片展开区的外链（2026-10-09 zuige 指定）。 */
private const val DEVELOPER_GITHUB_URL = "https://github.com/zuige66"
private const val DEVELOPER_BLOG_URL = "https://blog.zuiges.com"
/** QQ 交流群（2026-10-09 zuige 指定）：jump 直拉群资料卡，短链兜底（浏览器加群页）。 */
private const val QQ_GROUP_NUMBER = "1129363923"
private const val QQ_GROUP_JUMP_URI =
    "mqqapi://card/show_pslcard?src_type=internal&version=1&uin=$QQ_GROUP_NUMBER&card_type=group&source=qrcode"
private const val QQ_GROUP_JOIN_URL = "https://qm.qq.com/q/mEJT74MJa0"
private const val REPO_URL = "https://github.com/zuige66/Hyper-MeloLock"
private const val RELEASES_URL = "$REPO_URL/releases"
/** 「检查更新」请求的 GitHub Releases API（本仓库）。 */
private const val UPDATE_CHECK_API = "https://api.github.com/repos/zuige66/Hyper-MeloLock/releases/latest"
private const val DONATION_URL = ""
/** 使用教程：博客介绍文章（2026-10-09 由 zuige 指定）。 */
private const val DOCUMENTATION_URL = "https://blog.zuiges.com/2026/10/09/hyper-melolock-lockscreen-cover/"
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
 * 「取色风格」子选项（每个可调颜色的项各一份，键 *_pick）：仅当对应颜色处于「跟随封面」档时显示。
 * 0＝低饱和磨砂（M3E，主色压饱和做容器/弱化做文字，默认）；1＝鲜艳原色（主色直出，只调亮度保证可读）。
 * 值直接当selectedIndex用（与 PICK_STYLE_LABELS 顺序一致）。
 */
private val PICK_STYLE_LABELS = listOf("低饱和磨砂 (M3E)", "鲜艳原色")

/**
 * 外观页分组（M3E 折叠卡）：**每组一张完整卡片**——标题是卡片头（可点、涟漪反馈，
 * 箭头随展开状态弹性旋转 90°），内容在同一张卡内 animateContentSize 弹性展开/收起；
 * 收起时是一张矮卡，整页保持完整卡片列表的结构感。expandedKeys 由页面持有并 rememberSaveable。
 * 卡片头右侧的 ↺ 是「恢复本组默认」，点击只重置该组的键（不影响其他组）。
 */
@Composable
private fun CollapsibleSection(
    title: String,
    key: String,
    expandedKeys: Set<String>,
    onToggle: (String) -> Unit,
    onReset: () -> Unit,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    val expanded = key in expandedKeys
    val arrowRotation by animateFloatAsState(
        targetValue = if (expanded) 90f else 0f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMediumLow,
        ),
        label = "sectionArrow",
    )
    Card(
        modifier = Modifier.fillMaxWidth().animateContentSize(
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioLowBouncy,
                stiffness = Spring.StiffnessMediumLow,
            ),
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onToggle(key) }
                .padding(horizontal = 18.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = title,
                modifier = Modifier.weight(1f),
                fontSize = MiuixTheme.textStyles.body1.fontSize,
                color = MiuixTheme.colorScheme.onSurface,
            )
            Box(
                modifier = Modifier
                    .size(30.dp)
                    .clip(CircleShape)
                    .clickable { onReset() },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "↺",
                    fontSize = 17.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
            Text(
                text = "▸",
                modifier = Modifier
                    .padding(start = 12.dp)
                    .graphicsLayer { rotationZ = arrowRotation },
                fontSize = 15.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }
        if (expanded) {
            Column(modifier = Modifier.padding(bottom = 8.dp), content = content)
        }
    }
}

@Composable
private fun PickStyleDropdown(
    colorKey: String,
    pickKey: String,
    value: (String) -> Int,
    update: (String, Int) -> Unit,
) {
    if (value(colorKey) != 0) return   // 手选固定色时取色管线不跑，子选项无意义
    PreferenceDropdown(
        title = "取色风格",
        summary = "跟随封面时对主色的处理方式",
        icon = null,
        items = PICK_STYLE_LABELS,
        selectedIndex = value(pickKey).coerceIn(0, PICK_STYLE_LABELS.lastIndex),
        onSelectedIndexChange = { update(pickKey, it) },
    )
}

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
        modifier = Modifier.fillMaxWidth(),   // 与下方滑条同宽
        label = "签名内容",
        useLabelAsPlaceholder = true,
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { commit() }),
    )
}

/** 时间字体的圆润档位；0 用系统字体，1 / 2 用内置的开源圆体数字字体。 */
private val ROUNDNESS_LABELS = listOf("直角", "中等圆", "很圆")
