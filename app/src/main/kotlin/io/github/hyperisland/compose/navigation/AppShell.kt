package io.github.hyperisland.compose.navigation

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.layout.fillMaxWidth
import io.github.hyperisland.R
import io.github.hyperisland.compose.component.BarBackdropContent
import io.github.hyperisland.compose.component.BarBlurHost
import io.github.hyperisland.compose.component.BlurredBar
import io.github.hyperisland.compose.component.LocalRootBottomBarPadding
import io.github.hyperisland.compose.component.LiquidGlassNavigationBar
import io.github.hyperisland.compose.component.LiquidGlassNavigationItem
import io.github.hyperisland.compose.component.barBlurBackground
import io.github.hyperisland.compose.data.FlutterPrefsRepository
import io.github.hyperisland.compose.data.rememberBooleanPreference
import io.github.hyperisland.compose.page.LockAboutPage
import io.github.hyperisland.compose.page.LockAppearancePage
import io.github.hyperisland.compose.page.LockHomePage
import io.github.hyperisland.compose.page.LockMusicAppsPage
import io.github.hyperisland.compose.theme.PREF_BLUR_BARS
import io.github.hyperisland.compose.theme.PREF_FLOATING_NAVIGATION_BAR
import io.github.hyperisland.compose.theme.PREF_LIQUID_GLASS_NAVIGATION_BAR
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.FloatingNavigationBar
import top.yukonga.miuix.kmp.basic.FloatingNavigationBarItem
import top.yukonga.miuix.kmp.basic.FloatingToolbarDefaults
import top.yukonga.miuix.kmp.basic.NavigationBar
import top.yukonga.miuix.kmp.basic.NavigationBarItem
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SnackbarHost
import top.yukonga.miuix.kmp.basic.SnackbarHostState
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.GridView
import top.yukonga.miuix.kmp.icon.extended.Home
import top.yukonga.miuix.kmp.icon.extended.Info
import top.yukonga.miuix.kmp.icon.extended.Settings

private data class RootDestination(@StringRes val title: Int, val icon: ImageVector)

/**
 * MeloLock 精简版外壳：上游 HyperIsland 的四个根 tab + 一堆 detail 路由里，
 * 本模块只保留四个 Lock 页面 tab，全部 detail 导航层（渠道/Toast/AI 配置/
 * 扩展钩子页/主题页等）的入口状态没有任何赋值点，属于死路径，已随死代码一并删除。
 */
@Composable
internal fun HyperIslandApp(prefs: FlutterPrefsRepository) {
    val destinations = remember {
        listOf(
            RootDestination(R.string.nav_home, MiuixIcons.Home),
            RootDestination(R.string.nav_apps, MiuixIcons.GridView),
            RootDestination(R.string.nav_settings, MiuixIcons.Settings),
            RootDestination(R.string.about, MiuixIcons.Info),
        )
    }
    val pagerState = rememberPagerState(pageCount = { destinations.size })
    val scope = rememberCoroutineScope()
    val rootSnackbarState = remember { SnackbarHostState() }
    val floatingNavigationBar = rememberBooleanPreference(prefs, PREF_FLOATING_NAVIGATION_BAR, false)
    val liquidGlassNavigationBar = rememberBooleanPreference(
        prefs,
        PREF_LIQUID_GLASS_NAVIGATION_BAR,
        false,
    )
    val blurBars = rememberBooleanPreference(prefs, PREF_BLUR_BARS, false)
    var bottomBarHeightPx by remember { mutableIntStateOf(0) }
    val density = LocalDensity.current

    @Composable
    fun RootBottomBar(enabled: Boolean = true) {
        if (floatingNavigationBar.value) {
            if (liquidGlassNavigationBar.value) {
                LiquidGlassNavigationBar(
                    selectedTabIndex = { pagerState.currentPage },
                    onTabSelected = { index ->
                        if (enabled && pagerState.currentPage != index) {
                            scope.launch { pagerState.animateScrollToPage(index) }
                        }
                    },
                    items = destinations.map { destination ->
                        LiquidGlassNavigationItem(
                            icon = destination.icon,
                            label = stringResource(destination.title),
                        )
                    },
                )
            } else {
                FloatingNavigationBar(
                    modifier = Modifier.barBlurBackground(
                        RoundedCornerShape(FloatingToolbarDefaults.CornerRadius),
                    ),
                    color = Color.Transparent,
                ) {
                    destinations.forEachIndexed { index, destination ->
                        FloatingNavigationBarItem(
                            selected = pagerState.currentPage == index,
                            onClick = {
                                if (enabled) scope.launch { pagerState.animateScrollToPage(index) }
                            },
                            icon = destination.icon,
                            label = stringResource(destination.title),
                        )
                    }
                }
            }
        } else {
            BlurredBar {
                NavigationBar(color = Color.Transparent) {
                    destinations.forEachIndexed { index, destination ->
                        NavigationBarItem(
                            selected = pagerState.currentPage == index,
                            onClick = {
                                if (enabled) scope.launch { pagerState.animateScrollToPage(index) }
                            },
                            icon = destination.icon,
                            label = stringResource(destination.title),
                        )
                    }
                }
            }
        }
    }

    BarBlurHost(
        enabled = blurBars.value,
        captureForEffects = false,
    ) {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            snackbarHost = { SnackbarHost(rootSnackbarState) },
            bottomBar = {
                // Reserve space without composing a transparent, clickable second navigation bar.
                Spacer(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(with(density) { bottomBarHeightPx.toDp() }),
                )
            },
        ) { padding ->
            Box(modifier = Modifier.fillMaxSize()) {
                BarBackdropContent(modifier = Modifier.fillMaxSize()) {
                    CompositionLocalProvider(
                        LocalRootBottomBarPadding provides padding.calculateBottomPadding(),
                    ) {
                        HorizontalPager(
                            state = pagerState,
                            modifier = Modifier.fillMaxSize(),
                            beyondViewportPageCount = 1,
                        ) { page ->
                            when (page) {
                                0 -> LockHomePage(
                                    isActive = pagerState.currentPage == 0,
                                    onOpenMusicApps = {
                                        scope.launch { pagerState.animateScrollToPage(1) }
                                    },
                                    onOpenAppearance = {
                                        scope.launch { pagerState.animateScrollToPage(2) }
                                    },
                                )
                                1 -> LockMusicAppsPage()
                                2 -> LockAppearancePage()
                                // hero 的渐变背景是逐帧动画，只在停留此页时跑，否则白白耗电。
                                else -> LockAboutPage(isActive = pagerState.currentPage == 3)
                            }
                        }
                    }
                }

                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .onSizeChanged { bottomBarHeightPx = it.height },
                ) {
                    RootBottomBar()
                }
            }
        }
    }
}
