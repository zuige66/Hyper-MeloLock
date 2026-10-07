package io.github.melolock;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.RenderEffect;
import android.graphics.Shader;
import android.graphics.drawable.ColorDrawable;
import android.os.Build;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * 方案 B：手电筒 / 相机转场期间，用自绘背景顶掉露出的系统壁纸。
 *
 * **根因**：点快捷方式时 `MiuiShortcutController.onShortcutPluginCallbackWrap()` 在
 * "onFullscreenAnimationStart" 分支调 `KeyguardPanelViewController.hideWindowViewByOccludedAnim()`，
 * 后者只有一句 `Folme.useAt(notificationShadeWindowView).state().to(0f, hideEase)` —— 把整个
 * NotificationShade 窗口（type=2040，`NotificationShadeWindowView`）alpha 动到 0。我们的背景层
 * （挂它 index 0）与前景层（挂锁屏根）都在那扇窗里，于是一起消失，露出最底层的系统壁纸 surface。
 *
 * **做法**：不拦那次淡出（拦掉会有盖住相机的风险，还会丢掉图标放大动画），改成往 MIUI 自己的
 * 快捷方式动画窗口 `miui_keyguard_shortcut`（type=2017）里插一层自绘背景。
 *
 * **关键约束（真机踩过）**：那扇动画窗**在 app 窗口之上**，所以我们这层只要还可见，就会盖住
 * 相机/手电筒的界面（表现为「一片深蓝纯色，点中间还能开关手电筒」）。因此它**默认 GONE**，
 * 只在 shade 开始淡出（`onFullscreenAnimationStart`）时显示，收到动画结束类回调就撤掉；
 * 另外挂一个 6s 兜底定时器，任何回调缺失都不会让它长期压着 app。
 *
 * 真机实测到的目标窗口结构（`[probe]` 日志，OS3 16.03）：
 * ```
 * Window{miui_keyguard_shortcut  type=2017(TYPE_STATUS_BAR_SUB_PANEL)  flags=0xd010718(HW加速/半透明)}
 *  └─ FrameLayout           1080x2400   ← 窗口内容视图（addView 的入参）
 *      └─ ShortcutOccludedAnimView 1080x2400   ← MIUI 的动画视图（插件类）
 *          ├─ View 1080x2400 bg=ColorDrawable  ← 全屏遮罩
 *          └─ ScaleShortcutImageView 289x289   ← 与快捷栏图标同尺寸，放大到全屏
 * ```
 *
 * 失败关闭：钩不上窗口或钩不上回调 → 这层永远不显示，行为与原生一致。
 */
public final class ShortcutAnimBackdrop implements IXposedHookLoadPackage {
    private static final String TAG = "MeloLock";
    private static final String PRE = "[backdrop] ";
    /** 窗口标题里认这个片段（真机 `VRI[miui_keyguard_shortcut]` 已证实）。 */
    private static final String WINDOW_TAG = "keyguard_shortcut";
    private static final String CONTROLLER = "com.android.keyguard.shortcut.MiuiShortcutController";
    /** 开始显示：就是 ROM 把 shade 淡到 0 的那一刻，与根因一一对应。 */
    private static final String SHOW_ACTION = "onFullscreenAnimationStart";
    /** 这些回调意味着「app 已经上来了 / 转场结束」：必须撤掉我们这层，否则压着 app。 */
    private static final Set<String> HIDE_ACTIONS = new HashSet<>(Arrays.asList(
            "onOccludedAnimationEnd", "onUnoccludedAnimationStart", "onUnoccludedAnimationEnd",
            "onBackAnimationEnd", "onDismissAnimationEnd"));
    /**
     * 兜底：显示之后最多留这么久。
     *
     * 取 6s 是实测出来的：相机**冷启动**时 `onOccludedAnimationEnd` 会拖到点击后 **5.34s**
     * （19:26:07.368 点，12.705 才收到），而主路径本来就该由那个回调收工。这个定时器只是
     * 「回调一次都没来」时的网，所以必须比任何现实中的入场动画都长 —— 之前写 2.5s，
     * 真机上是**靠主线程被冷启动堵住**（定时器实际 12.544 才执行）才没提前把壁纸放出来。
     */
    private static final long HOLD_TIMEOUT_MS = 6000;

    private static boolean armed;
    /** 当前注入的那一层。一个进程只有一扇锁屏，同一时刻只需保留最后一个。 */
    private static BackdropLayer live;

    @Override public void handleLoadPackage(XC_LoadPackage.LoadPackageParam param) {
        if (!"com.android.systemui".equals(param.packageName)) return;
        if (!Config.FINGERPRINT.equals(Build.FINGERPRINT)) return;
        if (armed) return;
        armed = true;
        hookWindowAdd(param.classLoader);
        hookShortcutCallback(param.classLoader);
    }

    /** 进程里所有窗口都要过 WindowManagerImpl.addView —— 按标题认出快捷方式动画窗口。 */
    private static void hookWindowAdd(ClassLoader loader) {
        XC_MethodHook hook = new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam call) {
                try { onWindowAdded(call.args); }
                catch (Throwable error) { Log.w(TAG, PRE + "window hook failed: " + error); }
            }
        };
        try {
            XposedHelpers.findAndHookMethod("android.view.WindowManagerGlobal", loader, "addView",
                    View.class, ViewGroup.LayoutParams.class, android.view.Display.class, android.view.Window.class, hook);
            Log.i(TAG, PRE + "armed via WindowManagerGlobal.addView");
            return;
        } catch (Throwable error) {
            Log.i(TAG, PRE + "WindowManagerGlobal.addView unavailable (" + error + "); trying WindowManagerImpl");
        }
        try {
            XposedHelpers.findAndHookMethod("android.view.WindowManagerImpl", loader, "addView",
                    View.class, ViewGroup.LayoutParams.class, hook);
            Log.i(TAG, PRE + "armed via WindowManagerImpl.addView");
        } catch (Throwable error) {
            Log.w(TAG, PRE + "no window hook; shortcut backdrop disabled: " + error);
        }
    }

    /** 认窗口 → 往窗口内容视图的最底层插一层背景（默认不显示）。 */
    private static void onWindowAdded(Object[] args) {
        if (args == null || args.length < 2) return;
        if (!(args[0] instanceof ViewGroup) || !(args[1] instanceof WindowManager.LayoutParams)) return;
        WindowManager.LayoutParams params = (WindowManager.LayoutParams) args[1];
        CharSequence title = params.getTitle();
        if (title == null || !title.toString().contains(WINDOW_TAG)) return;
        ViewGroup content = (ViewGroup) args[0];
        BackdropLayer existing = findBackdrop(content);
        if (existing != null) { live = existing; return; }
        BackdropLayer layer = new BackdropLayer(content.getContext());
        content.addView(layer, 0, new FrameLayout.LayoutParams(-1, -1));
        live = layer;
        Log.i(TAG, PRE + "injected into " + content.getClass().getName()
                + " title=" + title + " type=" + params.type + " kids=" + content.getChildCount());
    }

    private static BackdropLayer findBackdrop(ViewGroup group) {
        for (int i = 0; i < group.getChildCount(); i++) {
            if (group.getChildAt(i) instanceof BackdropLayer) return (BackdropLayer) group.getChildAt(i);
        }
        return null;
    }

    /**
     * 用 ROM 自己的快捷方式回调驱动显隐 —— 和根因同源，时机天然对齐。
     *
     * 钩子装不上就永远不显示（失败关闭），不会出现「长期盖着 app」这种更糟的形态。
     */
    private static void hookShortcutCallback(ClassLoader loader) {
        try {
            Class<?> controller = XposedHelpers.findClassIfExists(CONTROLLER, loader);
            if (controller == null) {
                Log.w(TAG, PRE + CONTROLLER + " not found; backdrop stays hidden");
                return;
            }
            XposedHelpers.findAndHookMethod(controller, "onShortcutPluginCallbackWrap",
                    android.os.Bundle.class, String.class, new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam call) {
                            if (!(call.args[1] instanceof String)) return;
                            String action = (String) call.args[1];
                            BackdropLayer layer = live;
                            if (layer == null) return;
                            if (SHOW_ACTION.equals(action)) layer.show();
                            else if (HIDE_ACTIONS.contains(action)) layer.hide(action);
                        }
                    });
            Log.i(TAG, PRE + "armed: " + CONTROLLER + ".onShortcutPluginCallbackWrap");
        } catch (Throwable error) {
            Log.w(TAG, PRE + "callback hook failed; backdrop stays hidden: " + error);
        }
    }

    /**
     * 拿封面：反射读锁屏覆盖层当前那一帧，**不钩自己人**。
     *
     * 踩过的坑：`XposedHelpers.findAndHookMethod("io.github.melolock.LockScreenOverlay", param.classLoader, …)`
     * 会抛 `ClassNotFoundException` —— legacy Xposed 下模块自己的类由**模块自己的 ClassLoader** 加载，
     * 不在 SystemUI 的 `param.classLoader` 里。直接引用模块类（同一个 loader）+ 反射读字段即可。
     */
    private static Bitmap currentArtwork() {
        try {
            Object overlays = XposedHelpers.getStaticObjectField(HookEntry.class, "overlays");
            if (!(overlays instanceof java.util.Map)) return null;
            for (Object value : ((java.util.Map<?, ?>) overlays).values()) {
                if (!(value instanceof LockScreenOverlay)) continue;
                Object art = XposedHelpers.getObjectField(value, "shownArtwork");
                if (art instanceof Bitmap) return (Bitmap) art;
                Object shown = XposedHelpers.getObjectField(value, "shown");
                if (shown instanceof MediaSource.Snapshot) return ((MediaSource.Snapshot) shown).art;
            }
        } catch (Throwable error) {
            Log.w(TAG, PRE + "artwork read failed; falling back to solid colour: " + error);
        }
        return null;
    }

    /**
     * 注入的那一层：模糊封面 / 纯色底 / 遮罩，三层参数与 `LockScreenOverlay.applyBackdrop()` 完全一致
     * （0 深色玻璃 blur 28dp、1 浅色玻璃 blur 14dp 且遮罩 ×0.55、2 纯色沉浸不铺封面）。
     */
    static final class BackdropLayer extends FrameLayout {
        private final ImageView cover;
        private final View solid;
        private final View scrim;
        private final Runnable holdTimeout;
        private Bitmap appliedArtwork;

        BackdropLayer(Context context) {
            super(context);
            setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            cover = new ImageView(context);
            cover.setScaleType(ImageView.ScaleType.CENTER_CROP);
            addView(cover, new FrameLayout.LayoutParams(-1, -1));
            solid = new View(context);
            addView(solid, new FrameLayout.LayoutParams(-1, -1));
            scrim = new View(context);
            addView(scrim, new FrameLayout.LayoutParams(-1, -1));
            holdTimeout = this::onHoldTimeout;
            refresh();
            setVisibility(View.GONE);
        }

        /** 转场开始（shade 正在淡出）：铺参数并显形。 */
        void show() {
            post(() -> {
                refresh();
                setVisibility(View.VISIBLE);
                removeCallbacks(holdTimeout);
                postDelayed(holdTimeout, HOLD_TIMEOUT_MS);
                Log.i(TAG, PRE + "shown (hold<=" + HOLD_TIMEOUT_MS + "ms)");
            });
        }

        /** 转场结束 / app 已上来：立刻撤掉，绝不能压着 app。 */
        void hide(String reason) {
            post(() -> {
                removeCallbacks(holdTimeout);
                if (getVisibility() != View.GONE) {
                    setVisibility(View.GONE);
                    Log.i(TAG, PRE + "hidden by " + reason);
                }
            });
        }

        private void onHoldTimeout() {
            setVisibility(View.GONE);
            Log.i(TAG, PRE + "hidden by hold timeout");
        }

        @Override protected void onAttachedToWindow() {
            super.onAttachedToWindow();
            refresh();
        }

        /** MIUI 会复用同一个动画窗口：重新可见时把参数再铺一遍（可能换了歌/改了外观）。 */
        @Override public void onWindowVisibilityChanged(int visibility) {
            super.onWindowVisibilityChanged(visibility);
            if (visibility == View.VISIBLE) refresh();
        }

        /** 只更新三层参数，不动自身可见性（可见性由回调驱动）。 */
        void refresh() {
            Context context = getContext();
            int style = Config.overlayStyle(context);
            int color = Config.overlayColor(context);
            int alpha = Config.overlayAlpha(context);
            int blurDp = style == 2 ? 0 : (style == 1 ? 14 : 28);
            Bitmap art = style == 2 ? null : currentArtwork();
            boolean useCover = style != 2 && art != null;
            cover.setVisibility(useCover ? View.VISIBLE : View.GONE);
            // 同一张 bitmap 重设也会触发重绘，按引用去重。
            if (useCover && art != appliedArtwork) {
                appliedArtwork = art;
                cover.setImageBitmap(art);
            } else if (!useCover && appliedArtwork != null) {
                appliedArtwork = null;
                cover.setImageDrawable(null);
            }
            cover.setRenderEffect(blurDp > 0
                    ? RenderEffect.createBlurEffect(dp(blurDp), dp(blurDp), Shader.TileMode.CLAMP) : null);
            solid.setBackground(new ColorDrawable(color | 0xFF000000));
            solid.setVisibility(style == 2 ? View.VISIBLE : View.GONE);
            int scrimAlpha = Math.max(0, Math.min(255, style == 1 ? Math.round(alpha * 0.55f) : alpha));
            scrim.setBackground(new ColorDrawable((color & 0x00FFFFFF) | (scrimAlpha << 24)));
            Log.i(TAG, PRE + "backdrop params: style=" + style + " blur=" + blurDp + "dp color="
                    + Integer.toHexString(color) + " scrim=" + scrimAlpha + " cover=" + (useCover ? "yes" : "no"));
        }

        private int dp(int value) { return (int) (value * getResources().getDisplayMetrics().density + .5f); }
    }
}
