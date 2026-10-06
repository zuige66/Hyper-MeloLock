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
                Log.i(TAG, "USER_PRESENT " + state());
                // 解锁完成。正常路径是 pre-draw 守卫在解锁动画一开始就 suspend() 淡出；
                // 这里只兜底补一次，仍然保留实例，不再销毁场景。
                if (!suspended && foreground != null) suspend("user-present");
            } else if (Intent.ACTION_SCREEN_ON.equals(intent.getAction())) {
                Log.i(TAG, "SCREEN_ON " + state());
                if (suspended && lockscreenCycle && keyguardLocked()) resume();
                else if (shown != null && foreground != null && !expanded) showMusic();
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
    private int createAttempts;
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
    private ViewGroup notifications, windowRoot;
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
                    if (!expanded) {
                        hide(notifications);
                    } else {
                        show(notifications);
                    }
                    foreground.bringToFront();
                    if (notificationButton != null) notificationButton.bringToFront();
                }
            }
            return true;
        };
        media = new MediaSource(context, snapshot -> {
            try { render(snapshot); } catch (Throwable error) { Log.e(TAG, "Overlay render failed", error); restore("render-error"); }
        });
        switchObserver = new ContentObserver(main) { @Override public void onChange(boolean ignored) { updateSwitch(); } };
    }

    void start() {
        if (!compatible()) { Log.w(TAG, "SystemUI or plugin version differs; native lockscreen kept"); return; }
        context.getContentResolver().registerContentObserver(Config.URI, false, switchObserver);
        IntentFilter events = new IntentFilter();
        events.addAction(Intent.ACTION_SCREEN_OFF); events.addAction(Intent.ACTION_SCREEN_ON); events.addAction(Intent.ACTION_USER_PRESENT);
        context.registerReceiver(screenReceiver, events, Context.RECEIVER_NOT_EXPORTED);
        observing = true;
        Log.i(TAG, "Keyguard root compatible; observing module switch");
        updateSwitch();
    }

    void destroy() {
        Log.i(TAG, "Overlay destroy " + state());
        if (observing) { context.getContentResolver().unregisterContentObserver(switchObserver); context.unregisterReceiver(screenReceiver); observing = false; }
        media.close(); restore("destroy");
    }

    private void updateSwitch() {
        boolean enabled = Config.enabled(context);
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
        if (!Config.enabled(context)) { skip("disabled"); restore("render-disabled"); return; }
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
        int blurDp = Config.overlayStyle(context) == 2 ? 0 : (Config.overlayStyle(context) == 1 ? 18 : 28);
        if (blurDp > 0) baseBlur.setRenderEffect(RenderEffect.createBlurEffect(dp(blurDp), dp(blurDp), Shader.TileMode.CLAMP));
        background.addView(baseBlur, new FrameLayout.LayoutParams(-1, -1));
        View baseScrim = new View(context);
        int color = Config.overlayColor(context);
        baseScrim.setBackground(new ColorDrawable((color & 0x00FFFFFF) | (Config.overlayAlpha(context) << 24)));
        background.addView(baseScrim, new FrameLayout.LayoutParams(-1, -1));
        root.addView(background, 0, new ViewGroup.LayoutParams(-1, -1));

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
        expanded = false; hideNativeWallpaperLayers(root); hideNativeClockLayers(root); hide(notifications);
        foreground.animate().cancel(); cover.animate().cancel(); playerCard.animate().cancel();
        cover.setVisibility(View.VISIBLE); playerCard.setVisibility(View.VISIBLE);
        foreground.setVisibility(View.VISIBLE); foreground.bringToFront(); notificationButton.bringToFront(); notificationButton.setText("展开通知");
        // hideNativeClockLayers() must never retain an old hidden state on the
        // module clock after a page transition or a SystemUI pre-draw pass.
        immersiveClock.setVisibility(View.VISIBLE); immersiveClock.setAlpha(1f);
        if (animateIn) {
            foreground.setAlpha(0f);
            cover.setAlpha(0f); cover.setTranslationY(-dp(24));
            playerCard.setAlpha(0f); playerCard.setTranslationY(-dp(36));
            foreground.animate().alpha(1f).setDuration(180).start();
            cover.animate().alpha(1f).translationY(0f).setStartDelay(20).setDuration(220).start();
            playerCard.animate().alpha(1f).translationY(0f).setStartDelay(40).setDuration(240).start();
        } else {
            foreground.setAlpha(1f); cover.setAlpha(1f); cover.setTranslationY(0f); playerCard.setAlpha(1f); playerCard.setTranslationY(0f);
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
        foreground.setVisibility(View.VISIBLE); foreground.setAlpha(1f); cover.setAlpha(1f); cover.setTranslationY(0f); playerCard.setAlpha(1f); playerCard.setTranslationY(0f);
        playerCard.animate().translationY(-dp(52)).alpha(0f).setDuration(220).withEndAction(() -> {
            if (expanded && playerCard != null) playerCard.setVisibility(View.GONE);
        }).start();
        cover.animate().translationY(-dp(28)).alpha(0f).setDuration(180).withEndAction(() -> {
            if (expanded && cover != null) cover.setVisibility(View.GONE);
        }).start();
        notificationButton.bringToFront(); notificationButton.setText("返回播放器");
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
        main.removeCallbacks(progressTicker); main.removeCallbacks(finishSuspend); cancelArtworkFallback(); unregisterGuard(); shown = null; expanded = false; playerSceneVisible = false; restoreChangedViews();
        if (foreground != null && foreground.getParent() == root) root.removeView(foreground);
        if (background != null && background.getParent() == root) root.removeView(background);
        if (notificationButton != null && notificationButton.getParent() == foreground) foreground.removeView(notificationButton);
        background = null; foreground = null; content = null; immersiveClock = null; playerCard = null; notificationButton = null; clock = null; secondaryClock = null; nativeBackgroundLayer = null; nativeForegroundLayer = null; notifications = null; windowRoot = null;
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
        Log.i(TAG, "suspend reason=" + reason + " " + state());
        foreground.animate().cancel();
        if (cover != null) cover.animate().cancel();
        if (playerCard != null) playerCard.animate().cancel();
        // 淡出只是视觉效果，真正的隐藏交给 finishSuspend 兜底：
        // 解锁时窗口正在切换，ViewPropertyAnimator 的回调可能根本不推进，
        // 那样前景组件就会一直留在桌面上（实测过）。
        foreground.animate().alpha(0f).setDuration(120).start();
        main.postDelayed(finishSuspend, 150);
    }

    /** suspend 收尾：隐藏场景并把原生层交还系统。用 Handler 而非动画回调，避免解锁时残留。 */
    private final Runnable finishSuspend = () -> {
        if (!suspended || foreground == null) return;   // 中途 resume()/restore() 过
        foreground.animate().cancel();
        foreground.setAlpha(1f);
        foreground.setVisibility(View.GONE);
        if (background != null) background.setVisibility(View.GONE);
        if (notificationButton != null) notificationButton.setVisibility(View.GONE);
        // 原生壁纸/时钟交还系统：桌面期间我们完全不碰这些层。
        restoreChangedViews();
        Log.i(TAG, "suspend done; scene kept for reuse");
    };

    /** 锁屏重新出现：复用 suspend 保留的场景，跳过 create() 重建。 */
    private void resume() {
        if (!suspended || foreground == null) return;
        suspended = false;
        main.removeCallbacks(finishSuspend);
        Log.i(TAG, "resume reused scene " + state());
        foreground.animate().cancel();
        foreground.setAlpha(1f);
        foreground.setVisibility(View.VISIBLE);
        if (background != null) background.setVisibility(View.VISIBLE);
        if (notificationButton != null) notificationButton.setVisibility(View.VISIBLE);
        showMusic();
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
