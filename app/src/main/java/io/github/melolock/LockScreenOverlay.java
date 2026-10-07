package io.github.melolock;

import android.app.KeyguardManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.database.ContentObserver;
import android.graphics.Color;
import android.graphics.RenderEffect;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextClock;
import android.widget.TextView;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Exact-build OS3 adapter. The player card is module-owned because OS3 owns its native header. */
final class LockScreenOverlay {
    private static final String TAG = "MeloLock";
    private static final String CLOCK = "com.android.keyguard.clock.KeyguardClockContainer";
    private static final String NOTIFICATIONS = "com.android.systemui.statusbar.notification.stack.NotificationStackScrollLayout";
    private final ViewGroup root;
    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final MediaSource media;
    private final ContentObserver switchObserver;
    /**
     * 无 root 的重启通道：配置端发现 `su` 不可用时，会发一条显式广播过来，
     * 由已经跑在 SystemUI 进程里的模块自己 kill 自己完成一次作用域重启。
     * 与 `am crash com.android.systemui` 效果等价，但不需要 root 也不需要 adb。
     */
    private final BroadcastReceiver restartReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context ignored, Intent intent) {
            if (!Config.ACTION_RESTART_SYSTEMUI.equals(intent.getAction())) return;
            Log.i(TAG, "Restart requested via broadcast; killing SystemUI for a clean reload");
            android.os.Process.killProcess(android.os.Process.myPid());
        }
    };
    private final BroadcastReceiver screenReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context ignored, Intent intent) {
            if (Intent.ACTION_SCREEN_OFF.equals(intent.getAction())) {
                lockscreenCycle = true;
                // Keep the already-rendered scene in SystemUI memory.  Rebuilding it
                // only after SCREEN_ON is what caused the one-second stock-screen flash.
                Log.i(TAG, "SCREEN_OFF kept=" + state());
                main.removeCallbacks(progressTicker);
                // 主动让媒体层回调一次：此时还没有场景的话，render() 会在屏幕看不见时先建好。
                if (Config.enabled(context)) media.start();
            } else if (Intent.ACTION_USER_PRESENT.equals(intent.getAction())) {
                // USER_PRESENT is the reliable boundary between keyguard and the
                // unlocked notification shade.  On this ROM isKeyguardLocked()
                // can still briefly report true while the home shade is opening.
                lockscreenCycle = false;
                Log.i(TAG, "USER_PRESENT " + state()
                        + (suspended && suspendStartedAtMs > 0 ? " (+" + (android.os.SystemClock.elapsedRealtime() - suspendStartedAtMs) + "ms after suspend)" : ""));
                // 解锁完成。正常路径是 pre-draw 守卫在解锁动画一开始就 suspend() 淡出；
                // 这里只兜底补一次，仍然保留实例，不再销毁场景。
                if (!suspended && foreground != null) suspend("user-present");
            } else if (Intent.ACTION_SCREEN_ON.equals(intent.getAction())) {
                Log.i(TAG, "SCREEN_ON " + state());
                if (suspended && lockscreenCycle && keyguardLocked()) resume();
                else if (!suspended && shown != null && foreground != null && !expanded) showMusic();
                updateSwitch();
            }
        }
    };
    private final IdentityHashMap<View, ViewState> changedViews = new IdentityHashMap<>();
    /** 内置圆体数字字体的缓存：roundness → Typeface，整个 SystemUI 进程只解一次。 */
    private static final Map<Integer, Typeface> roundedTypefaces = new HashMap<>();
    private final ViewTreeObserver.OnPreDrawListener keyguardGuard;
    private boolean observing;
    private Boolean lastEnabled;
    private boolean missingViewsLogged;
    /** 诊断用：解锁撤层只记一次，避免 pre-draw 每帧刷屏。 */
    private boolean unlockRestoreLogged;
    /** 诊断用：render 早退原因去重，只在原因变化时打日志。 */
    private String lastSkipReason;
    /** 诊断用：本次 suspend 的起点时间戳，配合 unlockWatch 输出相对毫秒数。 */
    private long suspendStartedAtMs;
    private int createAttempts;
    /**
     * 建场景那一瞬的外观配置指纹（三元素参数 + 背景样式/颜色/强度 + 封面圆角）。
     *
     * 场景实例现在跨锁屏周期复用（suspend / resume），`create()` 一辈子只跑一次，
     * 而它又是唯一读取外观配置的地方 —— 于是「背景样式调了没反应」成了必然结果。
     * 复用之前先比一次指纹，不同就撤掉旧场景重建，把「灭屏再亮屏一次生效」这条约定重新兑现。
     */
    private String appearanceSignature;
    /** findBottomCornerIcon 的遍历预算：SystemUI 的窗口树很深，不容许每次 resume 全扫一遍。 */
    private int scanBudget;
    /** 「展开通知」入口是否已经和系统底部快捷栏对齐过（对齐成功后不再重复扫树）。 */
    private boolean entryAligned;
    /** 对齐尝试次数：只在头几次输出诊断清单，免得每次 resume 都刷一行。 */
    private int entryAlignAttempts;
    /**
     * 解锁后场景是否处于「已淡出但保留」状态。
     *
     * true 时 pre-draw 守卫完全不介入（不隐藏原生层、不 bringToFront、不撤销），
     * 媒体回调也不接管界面，只等锁屏重新出现时 resume() 直接复用同一批视图。
     */
    private boolean suspended;
    /** True only from screen-off until the user has completed an unlock. */
    private boolean lockscreenCycle = true;
    private boolean expanded;
    private View clock, secondaryClock, nativeBackgroundLayer, nativeForegroundLayer;
    /**
     * 左侧通知栏的宿主视图（`NotificationPanelView#notification_panel`）。
     *
     * 真机视图树显示：`legacy_window_root` 的子节点顺序是
     * `notification_panel` → … → `control_center_container` → `keyguard_root_view`（我们的层挂这里）。
     * 画在后面的盖在前面，所以自绘层**盖住左侧通知栏**（重叠），却在右侧控制中心**之下**（所以右侧下拉正常）。
     *
     * 信号用它的可见性：收起时 INVISIBLE(4)，下拉展开时 VISIBLE(0)——由系统自己维护，
     * 不会像通知栈那样被亮屏/锁屏重排误改（那条错路踩过，见 README）。
     */
    private View leftShadePanel;
    /** 左侧下拉手势拦截是否已安装（只在通知面板的触摸入口上装一次）。 */
    private boolean shadeBlockInstalled;
    /** 诊断用：每次成功吃掉一次下拉手势打一行。 */
    private int shadeBlockedCount;
    private ViewGroup notifications, windowRoot;
    /** 展开通知时测到的共享位移，返回播放器时复用它，保证两个方向对称。 */
    private int lastSwapOffset;
    private FrameLayout background;
    private FrameLayout foreground;
    private LinearLayout content;
    private LinearLayout playerCard;
    private ImageView baseBlur, cover, cardArt;
    private TextView title, artist, previous, playPause, next, elapsed, duration;
    private ProgressBar progress;
    private TextClock immersiveClock;
    private Button notificationButton;
    private MediaSource.Snapshot shown;
    private ViewTreeObserver guardObserver;
    private boolean playerSceneVisible;
    private boolean artworkFallbackPending;
    private final Runnable artworkFallback = () -> {
        artworkFallbackPending = false;
        if (foreground != null && shown != null) {
            Log.i(TAG, "Artwork update timed out; native lockscreen restored");
            restore("artwork-timeout");
        }
    };
    private final Runnable progressTicker = new Runnable() {
        @Override public void run() {
            updateProgress();
            if (foreground != null && !expanded && shown != null) main.postDelayed(this, 500);
        }
    };

    private static final class ViewState {
        final int visibility, accessibility;
        ViewState(View view) { visibility = view.getVisibility(); accessibility = view.getImportantForAccessibility(); }
    }

    LockScreenOverlay(View root) {
        this.root = (ViewGroup) root;
        context = root.getContext();
        keyguardGuard = () -> {
            if (suspended) {
                // 解锁后场景已淡出并置 GONE，守卫必须完全放手：继续隐藏原生层会破坏，
                // 继续 bringToFront 会把不可见场景提到桌面之上。锁屏重新出现才 resume。
                //
                // 唯一例外是**原生锁屏时钟层**：我们仍持有场景实例（没 restore），而系统在
                // 「桌面下拉通知栏」时会把锁屏时钟重新显示出来 —— 用户看到的就是「原屏保的
                // 时间」漏到桌面上。所以时钟层继续按住；**壁纸层绝不能碰**（解锁动画要靠它，
                // 隐藏会露黑底，这条踩过）。
                hideNativeClockLayers(root);
                if (lockscreenCycle && keyguardLocked()) resume();
                return true;
            }
            if (foreground != null) {
                if (!lockscreenCycle || !keyguardLocked()) {
                    if (!unlockRestoreLogged) {
                        unlockRestoreLogged = true;
                        Log.i(TAG, "Pre-draw: keyguard unlocked while overlay alive " + state());
                    }
                    suspend("predraw-keyguard-unlocked");
                } else {
                    hideNativeWallpaperLayers(root);
                    hideNativeClockLayers(root);
                    // 通知栈的显隐完全由 expanded 决定：展开时它自己在淡入（由 show() 保证可见），
                    // 收起时立即隐藏——不再做淡出（淡出层盖在时钟上，会让时间看起来闪一下）。
                    if (expanded) show(notifications); else hide(notifications);
                    ensureOnTop(foreground);
                    ensureOnTop(notificationButton);
                }
            }
            return true;
        };
        media = new MediaSource(context, snapshot -> {
            try { render(snapshot); } catch (Throwable error) { Log.e(TAG, "Overlay render failed", error); restore("render-error"); }
        });
        switchObserver = new ContentObserver(main) {
            @Override public void onChange(boolean ignored) {
                updateSwitch();
                // 诊断：确认「配置端写盘 → SystemUI 收到 → 与当前场景不一致」这条链路是通的。
                // 只在真的不一致时打一行，滑块拖动commit 几次也不会刷屏。
                if (foreground != null && appearanceSignature != null
                        && !appearanceSignature.equals(currentAppearanceSignature())) {
                    Log.i(TAG, "Appearance differs from current scene; will rebuild on next lock");
                }
            }
        };
    }

    void start() {
        if (!compatible()) { Log.w(TAG, "SystemUI or plugin version differs; native lockscreen kept"); return; }
        context.getContentResolver().registerContentObserver(Config.URI, false, switchObserver);
        IntentFilter events = new IntentFilter();
        events.addAction(Intent.ACTION_SCREEN_OFF); events.addAction(Intent.ACTION_SCREEN_ON); events.addAction(Intent.ACTION_USER_PRESENT);
        context.registerReceiver(screenReceiver, events, Context.RECEIVER_NOT_EXPORTED);
        // 显式广播（setPackage 到 SystemUI）才能穿过 RECEIVER_NOT_EXPORTED 送达这里。
        context.registerReceiver(restartReceiver, new IntentFilter(Config.ACTION_RESTART_SYSTEMUI),
                Context.RECEIVER_EXPORTED);
        observing = true;
        Log.i(TAG, "Keyguard root compatible; observing module switch");
        updateSwitch();
    }

    void destroy() {
        Log.i(TAG, "Overlay destroy " + state());
        if (observing) {
            context.getContentResolver().unregisterContentObserver(switchObserver);
            context.unregisterReceiver(screenReceiver);
            try { context.unregisterReceiver(restartReceiver); } catch (Throwable ignored) { }
            observing = false;
        }
        media.close(); restore("destroy");
    }

    private void updateSwitch() {
        Boolean read = Config.enabledOrNull(context);
        if (read == null) {
            // Provider 偶发查不到 ≠ 用户关了模块。旧代码把两者混为一谈，导致「改完设置
            // 锁屏上东西全没了 / 改完没反应」，且时好时坏（见 Config.enabledOrNull 注释）。
            Log.w(TAG, "Config read failed; keeping previous switch state=" + lastEnabled);
            return;
        }
        boolean enabled = read;
        if (lastEnabled == null || lastEnabled != enabled) Log.i(TAG, "Module enabled=" + enabled + " " + state());
        lastEnabled = enabled;
        if (enabled) media.start(); else { Log.i(TAG, "Module switched off; restoring " + state()); media.stop(); restore("switch-off"); }
    }

    private boolean compatible() {
        try {
            PackageManager packages = context.getPackageManager();
            return "16.03.251211.r".equals(packages.getPackageInfo("com.android.systemui", 0).versionName)
                    && "17.1.4.26.0".equals(packages.getPackageInfo("miui.systemui.plugin", 0).versionName);
        } catch (PackageManager.NameNotFoundException error) { return false; }
    }

    private void render(MediaSource.Snapshot snapshot) {
        Boolean enabledRead = Config.enabledOrNull(context);
        // 读不到（Provider 偶发失败）时什么都不做，保持现有画面；不能像旧代码那样当成
        // 「模块已关闭」把场景撤掉——那正是「配配置改完锁屏上没东西 / 时好时坏」的来源。
        if (enabledRead == null) { skip("config-unreadable"); return; }
        if (!enabledRead) { skip("disabled"); restore("render-disabled"); return; }
        if (!root.isAttachedToWindow()) { skip("root-detached"); restore("render-root-detached"); return; }
        if (!lockscreenCycle) {
            // 下拉桌面通知栏时系统仍可能把 keyguard root 保持 attached。这个周期
            // 标记优先于 KeyguardManager，确保自绘时间/背景绝不渗到桌面通知栏。
            if (snapshot != null && foreground == null) preCreate(snapshot);
            else if (foreground != null && !suspended) suspend("desktop-notification-shade");
            skip("unlocked-cycle");
            return;
        }
        if (suspended) {
            // 解锁后场景已淡出保留：媒体还在播就什么都不做，等锁屏出现时复用同一批视图；
            // 只有媒体真的不可用才销毁，免得锁屏重新出现时呈现已经过期的封面和曲目。
            if (snapshot == null) { Log.i(TAG, "Suspended scene dropped: media unavailable"); restore("suspended-media-gone"); }
            else skip("suspended-scene-kept");
            return;
        }
        PowerManager power = context.getSystemService(PowerManager.class);
        KeyguardManager keyguard = context.getSystemService(KeyguardManager.class);
        if (power == null) { skip("no-power"); restore("render-no-power"); return; }
        if (keyguard == null || !keyguard.isKeyguardLocked()) {
            // 桌面上不接管界面，但趁媒体回调把场景提前建好（不可见）：
            // 一播放歌曲场景就绪，之后锁屏/熄屏/亮屏都不用再 create()。
            if (snapshot != null && foreground == null) {
                preCreate(snapshot);
                skip("scene prebuilt on desktop");
                return;
            }
            skip("keyguard-unlocked");
            restore("render-keyguard-unlocked");
            return;
        }
        // A metadata callback may arrive while the display is off.  Preserve the
        // last valid scene until wake-up instead of exposing the stock wallpaper.
        if (!power.isInteractive()) {
            // 屏幕已灭、用户看不见，正好把场景提前建好：「桌面播歌 → 熄屏 → 亮屏」
            // 这条路径上从来没有场景（解锁期间不接管界面），亮屏得现 create()，
            // 实测 126~145ms，正是「先见原生锁屏」的来源。
            if (snapshot != null && foreground == null) preCreate(snapshot);
            skip("display-off");
            return;
        }
        if (snapshot == null) {
            if (foreground != null && shown != null && isStillPlaying()) { skip("awaiting-artwork"); deferArtworkFallback(); }
            else { skip("no-session"); restore("render-no-session"); }
            return;
        }
        lastSkipReason = null;
        cancelArtworkFallback();
        if (foreground == null && !create()) return;
        registerGuard();
        applySnapshot(snapshot);
        if (!expanded) showMusic();
    }

    /** 把一帧数据铺到已建好的视图上；不涉及可见性，供正常渲染与熄屏预建共用。 */
    private void applySnapshot(MediaSource.Snapshot snapshot) {
        shown = snapshot;
        baseBlur.setImageBitmap(snapshot.art); cover.setImageBitmap(snapshot.art); cardArt.setImageBitmap(snapshot.art);
        title.setText(emptyAs(snapshot.title, "未知曲目")); artist.setText(emptyAs(snapshot.artist, "未知艺术家"));
        playPause.setText(snapshot.playing ? "Ⅱ" : "▶");
        updateProgress();
    }

    /**
     * 屏幕已灭时预建场景：建好立刻置 GONE 并标记 suspended，
     * 亮屏时由 pre-draw 守卫的 resume() 直接恢复，跳过 create()。
     */
    private void preCreate(MediaSource.Snapshot snapshot) {
        if (!create()) return;
        registerGuard();
        applySnapshot(snapshot);
        foreground.setVisibility(View.GONE);
        if (background != null) background.setVisibility(View.GONE);
        if (notificationButton != null) notificationButton.setVisibility(View.GONE);
        playerSceneVisible = true;   // 数据已就绪，亮屏不需要入场动画
        suspended = true;            // 复用 suspend 语义，等 resume() 恢复
        Log.i(TAG, "Scene pre-created while display off; will resume on wake");
    }

    private boolean create() {
        Log.i(TAG, "create() attempt " + (++createAttempts) + " " + state());
        Map<String, Integer> elements = Config.elementValues(context);
        ElementGeometry geometry = measureElements(elements);
        clock = find(root, CLOCK);
        View top = root.getRootView();
        windowRoot = top instanceof ViewGroup ? (ViewGroup) top : null;
        View stack = find(top, NOTIFICATIONS);
        notifications = stack instanceof ViewGroup ? (ViewGroup) stack : null;
        // 左侧通知栏宿主：它的可见性不是可靠信号，只用它来装手势拦截。
        leftShadePanel = findById(windowRoot, "notification_panel");
        installShadeBlock();
        secondaryClock = findById(root, "miui_keyguard_foreground_clock_container");
        nativeBackgroundLayer = findById(root, "keyguard_background_layer");
        nativeForegroundLayer = findById(root, "keyguard_foreground_layer");
        if (clock == null || notifications == null || windowRoot == null) {
            if (!missingViewsLogged) Log.w(TAG, "Native views missing: clock=" + (clock != null) + " notifications=" + (notifications != null));
            missingViewsLogged = true;
            return false;
        }
        Log.i(TAG, "Native layers: clock=" + (clock != null) + " secondary=" + (secondaryClock != null)
                + " background=" + (nativeBackgroundLayer != null)
                + " foreground=" + (nativeForegroundLayer != null));
        // This layer is below SystemUI's shortcut row but above the stock wallpaper.
        // It fills the bottom area that the interaction scene deliberately leaves free.
        background = new FrameLayout(context);
        baseBlur = new ImageView(context);
        baseBlur.setScaleType(ImageView.ScaleType.CENTER_CROP);
        // 三档背景样式，**必须一眼看得出换了东西**：
        //   0 深色玻璃 —— 封面强模糊（28dp）+ 遮罩按「遮罩强度」原样铺
        //   1 浅色玻璃 —— 封面轻模糊（14dp）+ 遮罩乘 0.55，封面透出来更亮
        //   2 纯色沉浸 —— 不铺封面，整块背景就是「遮罩颜色」那一个纯色值
        // 之前三档只差 28dp/18dp 的模糊半径（约等于没差），用户反馈「调了不生效」就出在这。
        int style = Config.overlayStyle(context);
        int color = Config.overlayColor(context);
        int alpha = Config.overlayAlpha(context);
        int blurDp = style == 2 ? 0 : (style == 1 ? 14 : 28);
        if (blurDp > 0) baseBlur.setRenderEffect(RenderEffect.createBlurEffect(dp(blurDp), dp(blurDp), Shader.TileMode.CLAMP));
        background.addView(baseBlur, new FrameLayout.LayoutParams(-1, -1));
        View solidFill = new View(context);
        solidFill.setBackground(new ColorDrawable(color | 0xFF000000));
        solidFill.setVisibility(style == 2 ? View.VISIBLE : View.GONE);
        background.addView(solidFill, new FrameLayout.LayoutParams(-1, -1));
        View baseScrim = new View(context);
        int scrimAlpha = style == 1 ? Math.round(alpha * 0.55f) : alpha;
        baseScrim.setBackground(new ColorDrawable((color & 0x00FFFFFF) | (clamp(scrimAlpha, 0, 255) << 24)));
        background.addView(baseScrim, new FrameLayout.LayoutParams(-1, -1));
        Log.i(TAG, "Backdrop: style=" + style + " blur=" + blurDp + "dp color=" + Integer.toHexString(color)
                + " alpha=" + alpha + " scrim=" + scrimAlpha);
        // 挂窗口根而不是锁屏根：真机采样（Unlock watch）显示系统解锁时是把整个
        // HyperOSKeyguardRootView 直接置 INVISIBLE + alpha 0，且动作发生在 suspend()
        // 触发之前——挂在它内部的层没有过渡窗口，父控件 alpha 归零就一起消失，露出的是桌面壁纸。
        // 改挂窗口根最底层后，keyguard 被撤走时这一层留在原地，下面露出的就是我们的模糊封面，
        // 收尾仍由 suspend/finishSuspend 负责（GONE），不会残留到桌面。
        // 注：前景层必须留在 keyguard 根里跟着系统走，否则会「壁纸已出、组件还在」。
        windowRoot.addView(background, 0, new ViewGroup.LayoutParams(-1, -1));

        foreground = new FrameLayout(context);
        FrameLayout.LayoutParams sceneParams = new FrameLayout.LayoutParams(-1, -1, Gravity.TOP);
        // 挂在锁屏根视图而不是窗口根：解锁时系统的退场动画作用在锁屏根上，
        // 挂窗口根的前景不会跟着走，会「壁纸已出、组件还在」地残留到桌面（实测）。
        // 通知栈仍在窗口根，展开通知时它自然盖在锁屏根之上。
        root.addView(foreground, sceneParams);

        content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL); content.setGravity(Gravity.CENTER_HORIZONTAL);
        FrameLayout.LayoutParams contentParams = new FrameLayout.LayoutParams(-1, -2, Gravity.TOP); contentParams.topMargin = dp(elem(elements, Config.CLOCK_SPACING));
        foreground.addView(content, contentParams);
        immersiveClock = new TextClock(context);
        immersiveClock.setFormat12Hour("h:mm"); immersiveClock.setFormat24Hour("HH:mm"); immersiveClock.setGravity(Gravity.CENTER);
        immersiveClock.setTextSize(elem(elements, Config.CLOCK_SIZE));
        immersiveClock.setTextColor(elem(elements, Config.CLOCK_COLOR));
        applyClockTypeface(immersiveClock, elem(elements, Config.CLOCK_ROUNDNESS), elem(elements, Config.CLOCK_WEIGHT));
        content.addView(immersiveClock, new LinearLayout.LayoutParams(geometry.clockWidth, geometry.clockHeight));
        cover = new ImageView(context); cover.setScaleType(ImageView.ScaleType.CENTER_CROP); cover.setClipToOutline(true);
        GradientDrawable coverShape = new GradientDrawable(); coverShape.setColor(Color.WHITE); coverShape.setCornerRadius(dp(Config.cornerRadiusDp(context))); cover.setBackground(coverShape);
        LinearLayout.LayoutParams artParams = new LinearLayout.LayoutParams(geometry.coverWidth, geometry.coverHeight);
        artParams.topMargin = dp(elem(elements, Config.COVER_SPACING));
        content.addView(cover, artParams);
        playerCard = buildPlayerCard(elements);
        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(geometry.cardWidth, geometry.cardHeight);
        cardParams.topMargin = dp(elem(elements, Config.CARD_SPACING));
        content.addView(playerCard, cardParams);
        notificationButton = new Button(context); notificationButton.setText("展开通知");
        notificationButton.setOnClickListener(v -> { if (expanded) showMusic(); else showNotifications(); });
        // 放在我们自己的 FrameLayout 内，避免 HyperOS 动画期间根容器忽略 gravity
        // 而把入口落到左上角；底部位置低于充电文案。
        FrameLayout.LayoutParams entry = new FrameLayout.LayoutParams(-2, dp(48), Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL); entry.bottomMargin = dp(10);
        foreground.addView(notificationButton, entry);
        // 诊断用：一眼看出本次创建实际用了哪套参数，避免把“未重启 SystemUI”误判成代码问题。
        Log.i(TAG, "Elements: clock=" + elem(elements, Config.CLOCK_SIZE) + "sp w" + elem(elements, Config.CLOCK_WEIGHT)
                + " round" + elem(elements, Config.CLOCK_ROUNDNESS) + " color" + elem(elements, Config.CLOCK_COLOR)
                + " | cover=" + geometry.coverWidth + "x" + geometry.coverHeight + "px r"
                + Config.cornerRadiusDp(context) + " p" + elem(elements, Config.COVER_SCALE)
                + " | card=" + geometry.cardWidth + "x" + geometry.cardHeight + "px r"
                + elem(elements, Config.CARD_RADIUS) + " p" + elem(elements, Config.CARD_SCALE)
                + " | spacing=" + elem(elements, Config.CLOCK_SPACING) + "/"
                + elem(elements, Config.COVER_SPACING) + "/" + elem(elements, Config.CARD_SPACING));
        Log.i(TAG, "Custom media card overlay created in " + createAttempts + " attempt(s)");
        createAttempts = 0;
        unlockRestoreLogged = false;
        entryAligned = false; entryAlignAttempts = 0;
        appearanceSignature = currentAppearanceSignature();
        // 底部快捷栏要等一次布局才量得到坐标，排到下一帧再对齐；拿不到就沿用 dp(10)。
        foreground.post(this::alignEntryWithShortcutRow);
        return true;
    }

    private static int elem(Map<String, Integer> elements, String key) {
        Integer value = elements.get(key);
        return value == null ? Config.elementDefault(key) : value;
    }

    private static int clamp(int value, int min, int max) { return Math.max(min, Math.min(max, value)); }

    private static final class ElementGeometry {
        int clockWidth, clockHeight, coverWidth, coverHeight, cardWidth, cardHeight;
    }

    /**
     * 计算三元素的最终像素尺寸。
     *
     * 锁定比例时取默认尺寸乘缩放百分比；解锁时长宽取各自的 dp 值。
     * 任何异常取值都只会退回默认值，不会让覆盖层失败。
     */
    private ElementGeometry measureElements(Map<String, Integer> elements) {
        DisplayMetrics metrics = context.getResources().getDisplayMetrics();
        int screenWidthDp = Math.max(1, Math.round(metrics.widthPixels / metrics.density));
        ElementGeometry geometry = new ElementGeometry();

        // A clock is text, not a scalable panel. A fixed height clips large glyphs
        // and custom fonts, so it always measures itself; top spacing controls its
        // position in the scene.
        geometry.clockWidth = -1;  // MATCH_PARENT
        geometry.clockHeight = -2; // WRAP_CONTENT

        int defaultArt = Math.min((int) (metrics.widthPixels * .72f), dp(360));
        if (elem(elements, Config.COVER_LOCKED) != 0) {
            int size = Math.max(dp(40), defaultArt * clamp(elem(elements, Config.COVER_SCALE), 10, 300) / 100);
            geometry.coverWidth = size;
            geometry.coverHeight = size;
        } else {
            geometry.coverWidth = dp(clamp(elem(elements, Config.COVER_WIDTH), 40, 4096));
            geometry.coverHeight = dp(clamp(elem(elements, Config.COVER_HEIGHT), 40, 4096));
        }

        int baseCardWidthDp = Math.max(160, screenWidthDp - 24);
        if (elem(elements, Config.CARD_LOCKED) != 0) {
            int scale = clamp(elem(elements, Config.CARD_SCALE), 10, 300);
            geometry.cardWidth = dp(Math.max(160, baseCardWidthDp * scale / 100));
            geometry.cardHeight = dp(Math.max(60, 178 * scale / 100));
        } else {
            geometry.cardWidth = dp(clamp(elem(elements, Config.CARD_WIDTH), 160, 4096));
            geometry.cardHeight = dp(clamp(elem(elements, Config.CARD_HEIGHT), 60, 4096));
        }
        return geometry;
    }

    /**
     * 圆润度 0 用系统字体；1 / 2 用内置的开源圆体数字字体（OFL）。
     *
     * 粗细（wght）通过可变字体轴设置，失败则退回系统字体，绝不因为字体问题撤掉沉浸页。
     */
    private void applyClockTypeface(TextView view, int roundness, int weight) {
        int wght = clamp(weight, 100, 1000);
        try {
            Typeface rounded = roundTypeface(roundness);
            view.setTypeface(rounded != null
                    ? rounded
                    : Typeface.create(Typeface.SANS_SERIF, wght, false));
            view.getPaint().setFontVariationSettings("'wght' " + wght);
        } catch (Throwable error) {
            Log.w(TAG, "Clock typeface not applied: " + error);
        }
    }

    /** 从本模块 APK 的 assets 解出圆体字体并缓存；SystemUI 读不到模块资源时返回 null。 */
    private Typeface roundTypeface(int roundness) {
        if (roundness <= 0) return null;
        Typeface cached = roundedTypefaces.get(roundness);
        if (cached != null) return cached;
        try {
            File target = new File(context.getCacheDir(), "hmsc_clock_round_" + roundness + ".ttf");
            if (target.length() == 0) {
                ApplicationInfo info = context.getPackageManager().getApplicationInfo(Config.PACKAGE, 0);
                try (ZipFile zip = new ZipFile(info.sourceDir)) {
                    ZipEntry entry = zip.getEntry("assets/fonts/clock_round_" + roundness + ".ttf");
                    if (entry == null) return null;
                    try (InputStream in = zip.getInputStream(entry);
                         OutputStream out = new FileOutputStream(target)) {
                        byte[] buffer = new byte[8192];
                        int read;
                        while ((read = in.read(buffer)) > 0) out.write(buffer, 0, read);
                    }
                }
            }
            cached = Typeface.createFromFile(target);
            roundedTypefaces.put(roundness, cached);
            Log.i(TAG, "Rounded clock font loaded: level=" + roundness);
            return cached;
        } catch (Throwable error) {
            Log.w(TAG, "Rounded clock font unavailable (" + roundness + "): " + error);
            return null;
        }
    }

    private LinearLayout buildPlayerCard(final Map<String, Integer> elements) {
        LinearLayout card = new LinearLayout(context); card.setOrientation(LinearLayout.VERTICAL); card.setPadding(dp(16), dp(14), dp(16), dp(10));
        GradientDrawable cardBackground = new GradientDrawable(); cardBackground.setColor(0xF2181818); cardBackground.setCornerRadius(dp(elem(elements, Config.CARD_RADIUS))); card.setBackground(cardBackground);
        LinearLayout header = new LinearLayout(context); header.setGravity(Gravity.CENTER_VERTICAL); card.addView(header, new LinearLayout.LayoutParams(-1, dp(64)));
        cardArt = new ImageView(context); cardArt.setScaleType(ImageView.ScaleType.CENTER_CROP); cardArt.setClipToOutline(true);
        GradientDrawable artShape = new GradientDrawable(); artShape.setCornerRadius(dp(12)); artShape.setColor(0xFF404040); cardArt.setBackground(artShape);
        header.addView(cardArt, new LinearLayout.LayoutParams(dp(64), dp(64)));
        LinearLayout labels = new LinearLayout(context); labels.setOrientation(LinearLayout.VERTICAL); labels.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(0, -1, 1f); labelParams.leftMargin = dp(14); header.addView(labels, labelParams);
        title = label(Color.WHITE, 22, true); artist = label(0xFF9E9EA3, 15, false);
        labels.addView(title, new LinearLayout.LayoutParams(-1, dp(34))); labels.addView(artist, new LinearLayout.LayoutParams(-1, dp(24)));
        header.addView(icon("⌁", 30), new LinearLayout.LayoutParams(dp(38), -1));
        LinearLayout controls = new LinearLayout(context); controls.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams controlsParams = new LinearLayout.LayoutParams(-1, dp(54)); controlsParams.topMargin = dp(4); card.addView(controls, controlsParams);
        controls.addView(icon("♡", 28), controlParams());
        previous = icon("◀", 29); previous.setOnClickListener(v -> transport(1)); controls.addView(previous, controlParams());
        playPause = icon("Ⅱ", 34); playPause.setOnClickListener(v -> transport(2)); controls.addView(playPause, controlParams());
        next = icon("▶", 29); next.setOnClickListener(v -> transport(3)); controls.addView(next, controlParams());
        controls.addView(icon("▣", 27), controlParams());
        LinearLayout timeline = new LinearLayout(context); timeline.setGravity(Gravity.CENTER_VERTICAL); card.addView(timeline, new LinearLayout.LayoutParams(-1, dp(28)));
        elapsed = label(0xFF9E9EA3, 14, false); elapsed.setGravity(Gravity.CENTER); timeline.addView(elapsed, new LinearLayout.LayoutParams(dp(48), -1));
        progress = new ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal); progress.setMax(1000);
        progress.setProgressTintList(ColorStateList.valueOf(0xFFD7D7DA)); progress.setProgressBackgroundTintList(ColorStateList.valueOf(0xFF444449));
        timeline.addView(progress, new LinearLayout.LayoutParams(0, dp(6), 1f));
        duration = label(0xFF9E9EA3, 14, false); duration.setGravity(Gravity.CENTER); timeline.addView(duration, new LinearLayout.LayoutParams(dp(48), -1));
        return card;
    }

    private void showMusic() {
        boolean animateIn = !playerSceneVisible;
        boolean returning = expanded;   // 从通知页返回：走共享元素的反向动画
        expanded = false; hideNativeWallpaperLayers(root); hideNativeClockLayers(root);
        foreground.animate().cancel(); cover.animate().cancel(); playerCard.animate().cancel();
        cover.setVisibility(View.VISIBLE); playerCard.setVisibility(View.VISIBLE);
        foreground.setVisibility(View.VISIBLE); ensureOnTop(foreground); ensureOnTop(notificationButton); notificationButton.setText("展开通知");
        // hideNativeClockLayers() must never retain an old hidden state on the
        // module clock after a page transition or a SystemUI pre-draw pass.
        immersiveClock.setVisibility(View.VISIBLE); immersiveClock.setAlpha(1f);
        // 返回时复用展开时那一次的位移：现场测量两个方向量出来的值不同（真机日志 -605px vs -228px），
        // 卡片会「去一趟、从别处回来」，看起来就是跳。
        // 顺便再试一次和系统快捷栏对齐：场景经常是在「桌面/灭屏」时先建好的，那一刻
        // keyguard 底部什么都没有，只有真的站到锁屏上才量得到手电筒/相机那一排。
        alignEntryWithShortcutRow();
        int shared = returning ? (lastSwapOffset != 0 ? lastSwapOffset : measureSwapOffset()) : 0;
        if (returning) {
            // 通知**直接收起**，不再淡出：淡出层正好盖在时钟区域上，那一层任何合成抖动
            // 看起来都是「时间闪一下」。播放器的滑入本身已经承接了「同一个控件」的观感。
            Log.i(TAG, "Page swap: back to player, shared offset=" + shared + "px");
            endNotificationsLayer();
            if (notifications != null) {
                notifications.animate().cancel();
                notifications.setAlpha(1f); notifications.setTranslationY(0f);
                hide(notifications);
            }
        } else {
            hide(notifications);
        }
        if (animateIn) {
            if (returning) {
                // 关键：**不要再淡入 foreground**。前景（含时钟）在通知页一直是显示的，
                // 旧代码在这里把它从 alpha 0 淡入 180ms，等于让时钟先消失再回来 —— 用户
                // 反馈的「切回播放器时间闪一下」就是这一下。只让封面和卡片滑进来就够。
                foreground.setAlpha(1f);
                cover.setAlpha(0f); cover.setTranslationY(shared * 0.35f);
                playerCard.setAlpha(0f); playerCard.setTranslationY(shared);
                playerCard.setScaleX(0.94f); playerCard.setScaleY(0.94f);
            } else {
                foreground.setAlpha(0f);
                cover.setAlpha(0f); cover.setTranslationY(-dp(24));
                playerCard.setAlpha(0f); playerCard.setTranslationY(-dp(36));
                playerCard.setScaleX(1f); playerCard.setScaleY(1f);
                foreground.animate().alpha(1f).setDuration(180).start();
            }
            cover.animate().alpha(1f).translationY(0f).setStartDelay(20).setDuration(220)
                    .setInterpolator(fastOutSlowIn()).start();
            playerCard.animate().alpha(1f).translationY(0f).scaleX(1f).scaleY(1f).setStartDelay(40).setDuration(240)
                    .setInterpolator(fastOutSlowIn()).start();
        } else {
            foreground.setAlpha(1f); cover.setAlpha(1f); cover.setTranslationY(0f);
            playerCard.setAlpha(1f); playerCard.setTranslationY(0f); playerCard.setScaleX(1f); playerCard.setScaleY(1f);
        }
        playerSceneVisible = true;
        main.removeCallbacks(progressTicker); main.post(progressTicker);
    }

    private void showNotifications() {
        expanded = true; playerSceneVisible = false; main.removeCallbacks(progressTicker);
        // The album backdrop and the module clock remain visible. Only the native
        // notification stack is revealed; restoring all views would show wallpaper.
        show(notifications); hideNativeWallpaperLayers(root); hideNativeClockLayers(root);
        immersiveClock.setVisibility(View.VISIBLE); immersiveClock.setAlpha(1f);
        foreground.animate().cancel(); cover.animate().cancel(); playerCard.animate().cancel();
        foreground.setVisibility(View.VISIBLE); foreground.setAlpha(1f); cover.setAlpha(1f); cover.setTranslationY(0f);
        playerCard.setAlpha(1f); playerCard.setTranslationY(0f); playerCard.setScaleX(1f); playerCard.setScaleY(1f);
        int shared = measureSwapOffset();
        lastSwapOffset = shared;   // 返回时复用同一个值，两个方向才对称
        Log.i(TAG, "Page swap: to notifications, shared offset=" + shared + "px");
        // 播放器带着轻微缩小滑向「列表里那张媒体卡」的位置并淡出；通知随后沿同一方向淡入。
        // 两段运动方向一致 → 读起来是同一个控件换了地方，而不是两张卡片各管各的出现/消失。
        // 用 INVISIBLE 而不是 GONE：GONE 会把它们从布局里摘掉，content 这棵竖直 LinearLayout
        // 要重新 measure/layout 一整棵子树（封面是大图 ImageView、卡片带 ProgressBar），
        // 切回播放器再 VISIBLE 又来一次——两次全树 layout 正好落在切换动画的首尾，
        // 表现就是「回到播放器时时间/画面要卡一下」。alpha 已经是 0，视觉上没有区别。
        playerCard.animate().alpha(0f).translationY(shared).scaleX(0.94f).scaleY(0.94f).setDuration(220)
                .setInterpolator(fastOutSlowIn())
                .withEndAction(() -> { if (expanded && playerCard != null) playerCard.setVisibility(View.INVISIBLE); }).start();
        cover.animate().alpha(0f).translationY(shared * 0.35f).setDuration(180).setInterpolator(fastOutSlowIn())
                .withEndAction(() -> { if (expanded && cover != null) cover.setVisibility(View.INVISIBLE); }).start();
        if (notifications != null) {
            // 关键：不能像以前那样 show() 之后就完事——那样通知是瞬间满不透明出现，
            // 而播放器还在 220ms 的淡出里，两者并列就是用户看到的「错位」。
            beginNotificationsLayer();
            notifications.animate().cancel();
            notifications.setAlpha(0f); notifications.setTranslationY(shared * 0.18f);
            notifications.animate().alpha(1f).translationY(0f).setStartDelay(70).setDuration(210)
                    .setInterpolator(fastOutSlowIn()).start();
            // 动画跑完就拆掉硬件层（Handler 兜底，不挂动画回调）；重复调用是幂等的。
            main.postDelayed(this::endNotificationsLayer, 320);
        }
        ensureOnTop(notificationButton); notificationButton.setText("返回播放器");
    }

    /**
     * 「两个播放器」之间的共享位移：通知列表里第一张足够高的卡片（通常是原生媒体通知卡）
     * 相对自绘播放器卡片的屏幕纵向偏移。两页切换时让卡片沿这个位移移动 + 缩放，
     * 视觉上就是同一个控件在换位置。
     *
     * 上限取 `dp(96)`：实测这个偏移很容易量到 600px 以上（通知列表在锁屏上部，播放器卡在下面），
     * 照原值让卡片飞过去会**穿过时钟区域**再回来，看起来就是「时间闪一下」。位移只负责给眼睛
     * 一个「同一个控件」的线索，剩下的交给淡出。
     */
    private int measureSwapOffset() {
        int fallback = -dp(52);
        if (notifications == null || playerCard == null) return fallback;
        View destination = notifications;
        for (int i = 0; i < notifications.getChildCount(); i++) {
            View child = notifications.getChildAt(i);
            if (child != null && child.getVisibility() == View.VISIBLE && child.getHeight() > dp(56)) { destination = child; break; }
        }
        int[] to = new int[2], from = new int[2];
        destination.getLocationOnScreen(to);
        playerCard.getLocationOnScreen(from);
        int delta = to[1] - from[1];
        if (delta == 0) return fallback;   // 尚未完成布局时测量值不可信
        int limit = dp(96);
        return Math.max(-limit, Math.min(limit, delta));
    }
    /**
     * 只在真的不在最上层时才调整 z 序。
     *
     * `bringToFront()` 会触发 requestLayout —— pre-draw 守卫每帧都调用它，动画期间就等于每帧
     * 重排一次窗口根的子节点，这是切页掉帧的实打实来源之一。已经在最上时直接跳过。
     */
    private void ensureOnTop(View view) {
        if (view == null || !(view.getParent() instanceof ViewGroup)) return;
        ViewGroup parent = (ViewGroup) view.getParent();
        int last = parent.getChildCount() - 1;
        if (last < 0 || parent.getChildAt(last) == view) return;
        view.bringToFront();
    }
    private static android.view.animation.Interpolator fastOutSlowIn() {
        return new android.view.animation.PathInterpolator(0.4f, 0f, 0.2f, 1f);
    }
    /**
     * 切页动画期间给通知栈开硬件层。
     *
     * 通知栈是一棵很深的树（每条通知都是大图），做 alpha/位移动画时不开层就要**每帧重绘整棵子树**，
     * 主线程被拖住 → 锁屏上所有东西（最明显的是时间）都会连带卡一下。开层后只合成一次。
     * 展开结束或撤层时必须 `endNotificationsLayer()` 拆掉，否则 GPU 层会残留。
     */
    private void beginNotificationsLayer() {
        if (notifications != null) notifications.setLayerType(View.LAYER_TYPE_HARDWARE, null);
    }
    private void endNotificationsLayer() {
        if (notifications != null) notifications.setLayerType(View.LAYER_TYPE_NONE, null);
    }

    private void transport(int action) {
        if (shown == null) return;
        try {
            if (action == 1) shown.controller.getTransportControls().skipToPrevious();
            // 中间键是播放/暂停切换：暂停时场景会保留在锁屏上，所以这里必须能恢复播放。
            else if (action == 2) {
                if (shown.playing) shown.controller.getTransportControls().pause();
                else shown.controller.getTransportControls().play();
            }
            else shown.controller.getTransportControls().skipToNext();
        } catch (RuntimeException error) { Log.w(TAG, "Media transport action failed", error); }
    }

    private void updateProgress() {
        if (shown == null || progress == null) return;
        long total = shown.durationMs, position = shown.positionMs;
        if (shown.speed > 0f && shown.positionUpdateTimeMs > 0) position += (long) ((android.os.SystemClock.elapsedRealtime() - shown.positionUpdateTimeMs) * shown.speed);
        if (total > 0) { progress.setProgress((int) Math.min(1000, Math.max(0, position * 1000 / total))); elapsed.setText(time(position)); duration.setText(time(total)); }
        else { progress.setProgress(0); elapsed.setText("--:--"); duration.setText("--:--"); }
    }

    /** 撤层。reason 只用于诊断日志，用来定位「上滑露壁纸 / 亮屏先见原生锁屏」由哪条路径触发。 */
    private void restore(String reason) {
        if (foreground != null || background != null || shown != null) Log.i(TAG, "restore reason=" + reason + " " + state());
        main.removeCallbacks(progressTicker); main.removeCallbacks(finishSuspend);
        // 页面切换动画可能只跑到一半就被撤层：复位通知栈的动画属性，
        // 否则下次 show() 出来的是一张全透明的通知列表。
        if (notifications != null) { notifications.animate().cancel(); endNotificationsLayer(); notifications.setAlpha(1f); notifications.setTranslationY(0f); }
        cancelArtworkFallback(); unregisterGuard(); shown = null; expanded = false; playerSceneVisible = false; restoreChangedViews();
        if (foreground != null && foreground.getParent() == root) root.removeView(foreground);
        // background 挂在窗口根（见 create()），这里按实际父容器移除。
        if (background != null && background.getParent() instanceof ViewGroup) ((ViewGroup) background.getParent()).removeView(background);
        if (notificationButton != null && notificationButton.getParent() == foreground) foreground.removeView(notificationButton);
        background = null; foreground = null; content = null; immersiveClock = null; playerCard = null; notificationButton = null; clock = null; secondaryClock = null; nativeBackgroundLayer = null; nativeForegroundLayer = null; notifications = null; windowRoot = null; leftShadePanel = null;
        unlockRestoreLogged = false; lastSkipReason = null; suspended = false;
    }

    /**
     * 解锁时不再硬撤场景。
     *
     * 硬撤（旧行为）有两个后果：① `restore()` 会 `restoreChangedViews()` 把原生壁纸层恢复
     * VISIBLE，而系统解锁动画还要跑上百毫秒（实测 `USER_PRESENT` 在 80~190ms 后才到），
     * 空档期就露出壁纸；② 场景被销毁，下次亮屏必须重建（实测 126~145ms），观感是
     * 「先看到原生锁屏再变成音乐版」。这里改为淡出到 GONE 并**保留实例**。
     */
    private void suspend(String reason) {
        if (suspended || foreground == null) return;
        suspended = true;
        main.removeCallbacks(progressTicker);
        main.removeCallbacks(finishSuspend);
        main.removeCallbacks(unlockWatch);
        suspendStartedAtMs = android.os.SystemClock.elapsedRealtime();
        Log.i(TAG, "suspend reason=" + reason + " " + state());
        main.post(unlockWatch);   // 诊断：采样系统退场动画进度，只打日志不影响行为
        foreground.animate().cancel();
        if (cover != null) cover.animate().cancel();
        if (playerCard != null) playerCard.animate().cancel();
        // 淡出只是视觉效果，真正的隐藏交给 finishSuspend 兜底：
        // 解锁时窗口正在切换，ViewPropertyAnimator 的回调可能根本不推进，
        // 那样前景组件就会一直留在桌面上（实测过）。
        foreground.animate().alpha(0f).setDuration(120).start();
        // 背景层挂在窗口根，不随锁屏根被系统带走（刻意如此：上滑时要它盖住桌面壁纸）。
        // 但它原先要等 finishSuspend（+150ms）才「啪」地消失，而锁屏根在 +36ms 就已经
        // alpha=0 —— 中间那 100 多毫秒里用户看到的是一张**静止不动**的模糊封面，
        // 就是反馈的「解锁时沉浸式壁纸停顿一下」；系统退场动画时长不定，所以时有时无。
        // 现在让它跟前景同节奏淡出；硬隐藏仍由 finishSuspend 兜底，绝不留下残影。
        if (background != null) {
            background.animate().cancel();
            background.animate().alpha(0f).setDuration(120).start();
        }
        main.postDelayed(finishSuspend, 150);
    }

    /** suspend 收尾：隐藏场景并把原生层交还系统。用 Handler 而非动画回调，避免解锁时残留。 */
    private final Runnable finishSuspend = () -> {
        if (!suspended || foreground == null) return;   // 中途 resume()/restore() 过
        foreground.animate().cancel();
        foreground.setAlpha(1f);
        foreground.setVisibility(View.GONE);
        if (background != null) {
            background.animate().cancel();
            background.setAlpha(1f);
            background.setVisibility(View.GONE);
        }
        if (notificationButton != null) notificationButton.setVisibility(View.GONE);
        // 原生壁纸/时钟交还系统：桌面期间我们完全不碰这些层。
        long handedBackAtMs = android.os.SystemClock.elapsedRealtime() - suspendStartedAtMs;
        restoreChangedViews();
        Log.i(TAG, "native layers handed back at t=+" + handedBackAtMs + "ms");
        Log.i(TAG, "suspend done; scene kept for reuse");
    };

    /**
     * 诊断：解锁期间每 30ms 采样一次锁屏根视图，看清系统退场动画到底对整棵树做了什么
     * （是否位移、是否淡出、何时 GONE、何时 detach）。
     *
     * 用来回答两件事：① 我们的层挂在根视图里，能不能**完全交给系统带走**——如果系统确实
     * 在给整棵树做 alpha/位移动画，那我们就没必要自己淡出，自己淡出反而是「比系统早退场」
     * 从而露出原生壁纸的元凶；② 若交给系统，兜底超时该设多久（观察什么时候彻底静止）。
     *
     * **只打日志，不改任何行为**。
     */
    private final Runnable unlockWatch = new Runnable() {
        @Override public void run() {
            if (!suspended || foreground == null) return;   // resume()/restore() 过后自动停
            long t = android.os.SystemClock.elapsedRealtime() - suspendStartedAtMs;
            if (t > 1500) { Log.i(TAG, "Unlock watch: end after " + t + "ms"); return; }
            Log.i(TAG, "Unlock watch t=+" + t + "ms root=[" + visibilityName(root) + " attached=" + root.isAttachedToWindow()
                    + " alpha=" + fmt(root.getAlpha()) + " ty=" + fmt(root.getTranslationY()) + "]"
                    + " fg=[" + visibilityName(foreground) + " alpha=" + fmt(foreground.getAlpha()) + "]"
                    + " bg=[" + (background == null ? "null" : visibilityName(background)) + "]");
            main.postDelayed(this, 30);
        }
    };

    private static String fmt(float value) { return String.format(java.util.Locale.US, "%.2f", value); }
    private static String visibilityName(View view) {
        switch (view.getVisibility()) {
            case View.VISIBLE: return "VISIBLE";
            case View.INVISIBLE: return "INVISIBLE";
            default: return "GONE";
        }
    }

    /** 锁屏重新出现：复用 suspend 保留的场景，跳过 create() 重建。 */
    private void resume() {
        if (!suspended || foreground == null) return;
        suspended = false;
        main.removeCallbacks(finishSuspend);
        Log.i(TAG, "resume reused scene " + state());
        // 复用之前先验指纹：这一批视图是照着上次的配置量出来的，直接恢复就是把旧外观又端出来。
        // 场景跨周期复用之后 create() 不再重跑，配置读不到第二次，这是「背景样式/尺寸改了没反应」的根因；
        // 这里统一处理——外观动过就撤掉旧场景，用同一个快照当场重建（只多一次 create，约 100ms）。
        MediaSource.Snapshot keep = shown;
        if (appearanceSignature != null && appearanceSignature.equals(currentAppearanceSignature()) == false) {
            Log.i(TAG, "Appearance changed while scene kept; rebuilding with current values");
            restore("appearance-changed");
            if (keep != null) {
                // 排队到下一帧重建：此刻正站在 pre-draw 回调里，直接 addView 改视图树会让
                // 当前这一帧的绘制与紧接着的 layout 互相打断。
                main.post(() -> {
                    if (foreground != null || suspended || !lockscreenCycle || !keyguardLocked()) return;
                    render(keep);
                });
            }
            return;
        }
        foreground.animate().cancel();
        foreground.setAlpha(1f);
        foreground.setVisibility(View.VISIBLE);
        if (background != null) {
            // suspend() 会把背景淡到 0，复用时必须复位，否则锁屏重现时背景是透明的。
            background.animate().cancel();
            background.setAlpha(1f);
            background.setVisibility(View.VISIBLE);
        }
        if (notificationButton != null) notificationButton.setVisibility(View.VISIBLE);
        main.post(this::alignEntryWithShortcutRow);
        showMusic();
    }

    /**
     * 当前外观配置的指纹：三元素参数 + 背景样式 / 遮罩颜色 / 遮罩强度 + 封面圆角。
     *
     * SystemUI 进程里 `Config.elementValues()` 只有一次 ContentResolver 查询，
     * 但 `overlayStyle/Color/Alpha` 和 `cornerRadiusDp` 各自再查一次，所以只在这两处调用：
     * create() 收尾 与 resume() 比对，一次锁屏周期最多一次。
     */
    private String currentAppearanceSignature() {
        Map<String, Integer> values = Config.elementValues(context);
        StringBuilder text = new StringBuilder(256);
        text.append("style=").append(Config.overlayStyle(context))
                .append(";color=").append(Config.overlayColor(context))
                .append(";alpha=").append(Config.overlayAlpha(context))
                .append(";radius=").append(Config.cornerRadiusDp(context));
        for (String key : new java.util.TreeSet<>(values.keySet()))
            text.append(';').append(key).append('=').append(values.get(key));
        return text.toString();
    }

    /**
     * 「展开通知 / 返回播放器」入口与系统底部快捷栏（左手的电筒、右手的相机）对齐高度。
     *
     * 这两个图标是 MIUI 自己的 keyguard 布局，**资源 id 换版本就改名**（网上流传的那些名字
     * 在 16.03 上对不上），所以不写死 id，改成按几何特征认：可见、图标量级（高 32~72dp、
     * 宽 ≤ 96dp）、落在窗口底部 22% 的带子里、并且横向贴边（中心点在左右 22% 之外）。
     * 「底部两角 + 图标尺寸 + 贴边」三条一起，很难撞到别的控件。
     * 从 windowRoot 开始扫：不确定快捷栏挂在 keyguard 根的哪一层。
     * 拿不到锚点时保留默认的 dp(10)，绝不因为对齐失败让入口跑出屏幕。
     */
    private void alignEntryWithShortcutRow() {
        // 已经对齐过就别再扫树了；几何不变，重复测量只是白花钱。
        // 最多试 5 次：showMusic() 每次媒体回调都会走到（播放时约每秒一次），
        // 拿不到锚点时不能让它一直滚窗口树。
        if (entryAligned || entryAlignAttempts >= 5) return;
        if (notificationButton == null || foreground == null || windowRoot == null) return;
        // 只有真正站在锁屏上才量得准：桌面上、或屏幕还黑着的时候，keyguard 底部那一排
        // 快捷图标压根没显示（真机 log 里那次「band 里一个控件都没有」就是这么来的）。
        if (!keyguardLocked() || !interactive()) return;
        entryAlignAttempts++;
        View anchor = findShortcutAnchor();
        int height = foreground.getHeight();
        if (anchor == null || height <= 0) {
            // 头三次尝试会附带一次「底部带子里到底有什么」的清单，方便换机型/换版本时改判据。
            if (entryAlignAttempts <= 3) {
                Log.i(TAG, "Shortcut anchor not found (attempt " + entryAlignAttempts + "); entry keeps dp(10). " + bottomBandCensus());
            }
            return;
        }
        FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) notificationButton.getLayoutParams();
        if (params == null) return;
        int[] anchorPos = new int[2], hostPos = new int[2];
        anchor.getLocationOnScreen(anchorPos);
        foreground.getLocationOnScreen(hostPos);
        int anchorCenterY = anchorPos[1] + anchor.getHeight() / 2 - hostPos[1];
        int margin = height - anchorCenterY - params.height / 2;
        if (margin < 0 || margin > dp(160)) {
            Log.i(TAG, "Shortcut anchor rejected: centerY=" + anchorCenterY + " margin=" + margin + "px");
            return;
        }
        entryAligned = true;
        if (margin == params.bottomMargin) return;
        params.bottomMargin = margin;
        notificationButton.setLayoutParams(params);
        Log.i(TAG, "Entry aligned to shortcut row: anchor=" + anchor.getHeight() + "px centerY=" + anchorCenterY + " margin=" + margin + "px");
    }

    /**
     * 诊断用：把窗口底部一带的可见控件列出来（类名 / id / 屏幕矩形），最多 30 条。
     *
     * 底部快捷栏（手电筒 / 相机）的 id 在 MIUI 各版本里都不一样，只能先看清楚真机上到底
     * 长什么样，再决定锚点怎么认。只在找不到锚点、且每个场景一次时输出。
     */
    private String bottomBandCensus() {
        if (windowRoot == null) return "no windowRoot";
        int[] hostPos = new int[2];
        windowRoot.getLocationOnScreen(hostPos);
        StringBuilder out = new StringBuilder("host=[" + windowRoot.getWidth() + "x" + windowRoot.getHeight()
                + " top=" + hostPos[1] + "] bottom band:");
        collectBottomViews(windowRoot, hostPos[1] + (int) (windowRoot.getHeight() * 0.70f), out, 0);
        return out.toString();
    }

    private void collectBottomViews(ViewGroup group, int bandTop, StringBuilder out, int depth) {
        if (group == null || depth > 12 || out.length() > 1400) return;
        for (int i = 0; i < group.getChildCount() && out.length() < 1400; i++) {
            View child = group.getChildAt(i);
            if (child == null || child.getVisibility() != View.VISIBLE || child.getAlpha() < 0.5f) continue;
            if (child == foreground || child == background) continue;
            int[] pos = new int[2];
            child.getLocationOnScreen(pos);
            if (pos[1] + child.getHeight() / 2 >= bandTop && child.getHeight() > 0) {
                out.append(' ').append(describe(child, pos));
                if (out.length() > 1400) return;
            }
            if (child instanceof ViewGroup) collectBottomViews((ViewGroup) child, bandTop, out, depth + 1);
        }
    }

    private String describe(View view, int[] pos) {
        String id = "";
        try { id = view.getResources().getResourceEntryName(view.getId()); } catch (Throwable ignored) { }
        int[] self = new int[2];
        view.getLocationOnScreen(self);
        return (id.isEmpty() ? view.getClass().getSimpleName() : id)
                + "[" + view.getWidth() + "x" + view.getHeight() + " @" + pos[0] + "," + pos[1] + "]";
    }

    private View findShortcutAnchor() {
        // 真机视图树（`adb` 抓取 hierarchy 确认，OS3 16.03）：keyguard 根下面挂着
        // keyguard_shortcut_container，里面是 shortcut_view_left_layout（手电筒）和
        // shortcut_view_right_layout（相机），三者都是 289×289 的一方，顶边 y=2111。
        // 按 id 找最稳；资源可能属于 SystemUI 本体也可能属于 miui.systemui.plugin，逐个试。
        String[] names = { "shortcut_view_left_layout", "shortcut_view_left", "keyguard_shortcut_container", "keyguard_shortcut_layout" };
        View container = windowRoot != null ? windowRoot : root;
        for (String name : names) {
            View view = findByIdInAnyPackage(container, name);
            if (view != null && view.getVisibility() == View.VISIBLE && view.getWidth() > 0) return view;
        }
        return findBottomCornerIcon();   // 换版本、id 改名时的几何兜底
    }

    private View findByIdInAnyPackage(View container, String name) {
        if (container == null) return null;
        // 空串表示「在本 Resources 里找」，省得猜包名；最后一个候选必查，别担心重复。
        for (String pkg : new String[] { "com.android.systemui", "miui.systemui.plugin", "" }) {
            try {
                int id = context.getResources().getIdentifier(name, "id", pkg);
                if (id == 0) continue;
                View found = container.findViewById(id);
                if (found != null) return found;
            } catch (Throwable ignored) { /* 包名不存在就换下一个 */ }
        }
        return null;
    }

    private View findBottomCornerIcon() {
        int hostHeight = windowRoot.getHeight(), hostWidth = windowRoot.getWidth();
        if (hostHeight <= 0 || hostWidth <= 0) return null;
        int[] hostPos = new int[2];
        windowRoot.getLocationOnScreen(hostPos);
        scanBudget = 900;
        return scanForCornerIcon(windowRoot, hostPos[1] + (int) (hostHeight * 0.78f), hostWidth, 0);
    }

    private View scanForCornerIcon(ViewGroup group, int bandTop, int hostWidth, int depth) {
        if (group == null || depth > 12 || scanBudget <= 0) return null;
        for (int i = 0; i < group.getChildCount() && scanBudget > 0; i++) {
            View child = group.getChildAt(i);
            scanBudget--;
            if (child == null || child.getVisibility() != View.VISIBLE || child.getAlpha() < 0.5f) continue;
            if (child == foreground || child == background) continue;   // 别把自己当成锚点
            if (isCornerIcon(child, bandTop, hostWidth)) return child;
            if (child instanceof ViewGroup) {
                View hit = scanForCornerIcon((ViewGroup) child, bandTop, hostWidth, depth + 1);
                if (hit != null) return hit;
            }
        }
        return null;
    }

    private boolean isCornerIcon(View view, int bandTop, int hostWidth) {
        int width = view.getWidth(), height = view.getHeight();
        // 尺寸上限按真机量到的 289×289px（≈105dp）放宽：MIUI 把整个触控热区做成了贴边的方块。
        if (width <= 0 || height <= 0 || width > dp(120) || height < dp(32) || height > dp(120)) return false;
        int[] pos = new int[2];
        view.getLocationOnScreen(pos);
        if (pos[1] + height / 2 < bandTop) return false;
        float centerX = pos[0] + width / 2f;
        return centerX < hostWidth * 0.22f || centerX > hostWidth * 0.78f;
    }

    /** 诊断用：一行描述覆盖层与系统状态，配合 restore/skip 日志定位闪屏路径。 */
    private String state() {
        return "fg=" + (foreground != null) + " bg=" + (background != null) + " shown=" + (shown != null)
                + " expanded=" + expanded + " scene=" + playerSceneVisible + " suspended=" + suspended
                + " attached=" + root.isAttachedToWindow() + " interactive=" + interactive()
                + " keyguard=" + keyguardLocked();
    }

    private boolean keyguardLocked() {
        KeyguardManager keyguard = context.getSystemService(KeyguardManager.class);
        return keyguard != null && keyguard.isKeyguardLocked();
    }

    private boolean interactive() {
        PowerManager power = context.getSystemService(PowerManager.class);
        return power != null && power.isInteractive();
    }

    /** render 早退原因去重：只在原因变化时打一行，避免媒体回调反复刷同一状态。 */
    private void skip(String reason) {
        if (reason.equals(lastSkipReason)) return;
        lastSkipReason = reason;
        Log.i(TAG, "render skipped: " + reason + " " + state());
    }

    private void registerGuard() {
        ViewTreeObserver current = windowRoot.getViewTreeObserver(); if (guardObserver == current) return; unregisterGuard();
        if (current.isAlive()) { current.addOnPreDrawListener(keyguardGuard); guardObserver = current; }
    }
    private void unregisterGuard() { if (guardObserver != null && guardObserver.isAlive()) guardObserver.removeOnPreDrawListener(keyguardGuard); guardObserver = null; }

    /**
     * 沉浸场景是否仍占着锁屏（决定要不要吃掉左下拉手势）。
     *
     * **不能看 `playerSceneVisible`**：展开通知时它会被置 false，但场景（模糊背景 + 时钟）依然
     * 在锁屏上，这时左下拉照样会拉出原生通知面板、和已展开的通知叠在一起——真机踩过：
     * 展开通知后再左下拉，原生锁屏时钟和我们的时钟就重叠出现了。
     */
    private boolean sceneShowing() {
        return !suspended && foreground != null
                && foreground.getVisibility() == View.VISIBLE && lockscreenCycle;
    }

    /**
     * 在通知面板的触摸入口装一个拦截：沉浸场景显示期间，左半屏按下的手势直接吃掉。
     *
     * 为什么堵手势：左边通知栏展开的「状态信号」在真机上被证伪两次（通知栈可见性会被亮屏改、
     * `notification_panel` 可见性表示「锁屏在显示」而非「拉下来了」）。我们的自绘层挂在
     * `keyguard_root_view`（窗口根的最后一个子节点）里，即压在通知面板之上，所以面板只会拿到
     * 落到我们层上的触摸——在这里把左半屏的 DOWN 吃掉，通知栏就不会展开。
     * 右侧控制中心在另一个容器（`control_center_container`），不受影响。
     */
    private void installShadeBlock() {
        if (shadeBlockInstalled || leftShadePanel == null) return;
        shadeBlockInstalled = true;
        try {
            Class<?> panelClass = leftShadePanel.getClass();
            for (String name : new String[]{"dispatchTouchEvent", "onInterceptTouchEvent", "onTouchEvent"}) {
                final java.lang.reflect.Method method = findTouchMethod(panelClass, name);
                if (method == null) continue;
                final String hookName = name;
                try {
                    de.robv.android.xposed.XposedBridge.hookMethod(method, new de.robv.android.xposed.XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam hook) {
                            // 关键：面板类没有覆写 dispatchTouchEvent，getMethod 拿到的是框架 View 的实现，
                            // 钩上之后全 SystemUI 的 View 都会经过这里。不加 thisObject 判定就会把左半屏的
                            // 所有触摸一起吃掉——踩过：播放器按钮与「展开通知」全部失灵。
                            if (hook.thisObject != leftShadePanel) return;
                            if (!(hook.args[0] instanceof android.view.MotionEvent)) return;
                            android.view.MotionEvent event = (android.view.MotionEvent) hook.args[0];
                            if (event.getActionMasked() != android.view.MotionEvent.ACTION_DOWN) return;
                            if (!sceneShowing() || !shadeBlockEnabled()) return;
                            // 只挡左半屏：右上角是控制中心，不该受影响。
                            if (event.getX() > leftShadePanel.getWidth() / 2f) return;
                            hook.setResult(Boolean.FALSE);   // 当作没处理 → 通知栏不展开
                            Log.i(TAG, "Left-shade gesture consumed via " + hookName + " x=" + Math.round(event.getX())
                                    + " (#" + (++shadeBlockedCount) + ")");
                        }
                    });
                } catch (Throwable error) {
                    Log.w(TAG, "Shade block hook skipped: " + name, error);
                }
            }
            Log.i(TAG, "Left-shade touch block armed on " + panelClass.getName());
        } catch (Throwable error) {
            Log.w(TAG, "Left-shade touch block unavailable", error);
        }
    }

    /** 优先取面板类自己声明的方法；没有才退回继承来的（那种情况必须靠 thisObject 判定兜住）。 */
    private static java.lang.reflect.Method findTouchMethod(Class<?> type, String name) {
        try { return type.getDeclaredMethod(name, android.view.MotionEvent.class); }
        catch (NoSuchMethodException ignored) { }
        try { return type.getMethod(name, android.view.MotionEvent.class); }
        catch (NoSuchMethodException ignored) { return null; }
    }

    /** 每次手势现读配置：开关改了立刻生效，不用重建场景。 */
    private boolean shadeBlockEnabled() {
        try {
            Integer value = Config.elementValues(context).get(Config.BLOCK_LEFT_SHADE);
            return value == null || value != 0;
        } catch (Throwable error) { return false; }
    }

    private void hide(View view) {
        if (view == null) return;
        if (!changedViews.containsKey(view)) changedViews.put(view, new ViewState(view));
        view.setVisibility(View.INVISIBLE); view.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
    }
    /** Makes a temporarily hidden SystemUI view visible while retaining its original state for restore(). */
    private void show(View view) {
        if (view == null) return;
        if (!changedViews.containsKey(view)) changedViews.put(view, new ViewState(view));
        view.setVisibility(View.VISIBLE);
        view.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_AUTO);
    }
    private void restoreChangedViews() {
        for (Map.Entry<View, ViewState> entry : changedViews.entrySet()) { entry.getKey().setVisibility(entry.getValue().visibility); entry.getKey().setImportantForAccessibility(entry.getValue().accessibility); }
        changedViews.clear();
    }
    /** Hides every stock keyguard clock layer; ROM themes may add one after create(). */
    private void hideNativeClockLayers(View view) {
        // foreground owns TextClock too. Do not recursively hide our own scene.
        if (view == null || view == foreground || view == background) return;
        String className = view.getClass().getName().toLowerCase(java.util.Locale.ROOT);
        String idName = resourceName(view);
        if (className.contains("clock") || idName.contains("clock")) hide(view);
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) hideNativeClockLayers(group.getChildAt(i));
        }
    }
    /** Keeps any stock wallpaper/background container hidden while media owns the keyguard. */
    private void hideNativeWallpaperLayers(View view) {
        if (view == null || view == background) return;
        String idName = resourceName(view);
        if (view == nativeBackgroundLayer || view == nativeForegroundLayer
                || idName.contains("wallpaper") || idName.contains("keyguard_background")) hide(view);
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) hideNativeWallpaperLayers(group.getChildAt(i));
        }
    }
    private String resourceName(View view) {
        int id = view.getId();
        if (id == View.NO_ID) return "";
        try { return context.getResources().getResourceEntryName(id).toLowerCase(java.util.Locale.ROOT); }
        catch (RuntimeException ignored) { return ""; }
    }
    /** Reveals one stock view without forgetting its original state for final restore. */
    private void reveal(View view) {
        if (view == null) return;
        ViewState state = changedViews.get(view);
        if (state == null) return;
        view.setVisibility(state.visibility);
        view.setImportantForAccessibility(state.accessibility);
    }
    private boolean isStillPlaying() {
        try {
            PlaybackState state = shown.controller.getPlaybackState();
            return state != null && state.getState() == PlaybackState.STATE_PLAYING;
        } catch (RuntimeException error) { return false; }
    }
    private void deferArtworkFallback() {
        if (artworkFallbackPending) return;
        artworkFallbackPending = true;
        main.postDelayed(artworkFallback, 2500);
        Log.i(TAG, "Keeping previous artwork while the next track updates");
    }
    private void cancelArtworkFallback() {
        artworkFallbackPending = false;
        main.removeCallbacks(artworkFallback);
    }
    private TextView label(int color, int sizeSp, boolean bold) {
        TextView text = new TextView(context); text.setTextColor(color); text.setTextSize(sizeSp); text.setSingleLine(true); text.setEllipsize(TextUtils.TruncateAt.END);
        if (bold) text.setTypeface(android.graphics.Typeface.DEFAULT_BOLD); return text;
    }
    private TextView icon(String value, int sizeSp) { TextView icon = new TextView(context); icon.setText(value); icon.setTextColor(Color.WHITE); icon.setTextSize(sizeSp); icon.setGravity(Gravity.CENTER); icon.setClickable(true); return icon; }
    private LinearLayout.LayoutParams controlParams() { return new LinearLayout.LayoutParams(0, -1, 1f); }
    private static String emptyAs(String value, String fallback) { return value == null || value.isEmpty() ? fallback : value; }
    private static String time(long value) { long seconds = Math.max(0, value / 1000); return String.format(java.util.Locale.US, "%d:%02d", seconds / 60, seconds % 60); }
    private static View find(View view, String className) {
        if (view.getClass().getName().equals(className)) return view;
        if (view instanceof ViewGroup) { ViewGroup group = (ViewGroup) view; for (int i = 0; i < group.getChildCount(); i++) { View found = find(group.getChildAt(i), className); if (found != null) return found; } }
        return null;
    }
    private View findById(View parent, String name) { int id = context.getResources().getIdentifier(name, "id", "com.android.systemui"); return id == 0 ? null : parent.findViewById(id); }
    private int dp(float value) { return (int) (value * context.getResources().getDisplayMetrics().density + .5f); }
}
