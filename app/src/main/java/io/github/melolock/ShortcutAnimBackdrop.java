package io.github.melolock;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.ImageView;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * 手电筒 / 相机转场：把 MIUI 动画窗里那层全屏遮罩的**背景**换成我们的封面。
 *
 * **要解决的问题**：点快捷方式时 `MiuiShortcutController.onShortcutPluginCallbackWrap()` 在
 * "onFullscreenAnimationStart" 分支调 `KeyguardPanelViewController.hideWindowViewByOccludedAnim()`，
 * 后者只有一句 `Folme.useAt(notificationShadeWindowView).state().to(0f, hideEase)` —— 把整个
 * NotificationShade 窗口（type=2040）alpha 动到 0。我们的背景层（挂它 index 0）与前景层（挂锁屏根）
 * 都在那扇窗里，于是一起消失，**露出最底层的系统壁纸**；退出方向（shade 的 alpha 淡回来）同样露。
 *
 * **为什么是「改别人的背景」而不是「插自己的视图」**：本文件的前身（`ShortcutAnimBackdrop` 注入版）
 * 往动画窗里插过一层自己的视图，结果那层每帧都要重画全屏内容，实测把转场那 1 秒拖到 20fps（见
 * docs/DEVELOPMENT.md）。现在改成**不新增视图**：MIUI 的 `ShortcutOccludedAnimView` 里本来就有
 * 一层 1080×2400 的 `View bg=ColorDrawable`（每帧都在画），我们只把它的 background 换掉 ——
 * 绘制成本与原实现相同（都是「一张图铺满」），但内容变成我们的封面，于是壁纸被彻底盖住。
 *
 * 真机实测到的结构（OS3 16.03）：
 * ```
 * Window{miui_keyguard_shortcut  type=2017}          ← 按标题认
 *  └─ FrameLayout 1080x2400                          ← 窗口内容视图
 *      └─ ShortcutOccludedAnimView 1080x2400         ← MIUI 插件类，按类名认
 *          ├─ View 1080x2400 bg=ColorDrawable        ← **我们改它的 background**
 *          └─ ScaleShortcutImageView 289x289         ← 图标放大（不动）
 * ```
 *
 * **天然的好处**：显隐完全不用我们管 —— 那层是 MIUI 的，它自己显示/隐藏/移除窗口，我们的背景跟着走。
 * 于是「盖住 app」「忘记撤层」这类前身踩过的坑在结构上不存在；能被 MIUI 覆盖掉 background 时，
 * 我们在下一次转场会重设一遍。
 *
 * 失败关闭：认不出窗口/找不到那层/取不到封面 → 什么都不做，行为与原生一致（最多回到原来的「露壁纸」）。
 */
public final class ShortcutAnimBackdrop implements IXposedHookLoadPackage {
    private static final String TAG = "MeloLock";
    private static final String PRE = "[scrim] ";
    private static final String REV = "2026-10-07T2145-scrim2";
    private static final String WINDOW_TAG = "keyguard_shortcut";
    private static final String ANIM_VIEW = "ShortcutOccludedAnimView";
    private static final String CONTROLLER = "com.android.keyguard.shortcut.MiuiShortcutController";
    /** 需要「我们的背景在场」的时刻：进入（shade 淡到 0）与退出（shade 淡回来）。 */
    private static final String[] APPLY_ACTIONS = { "onFullscreenAnimationStart", "onUnoccludedAnimationStart" };
    /** MIUI 铺尺寸/加子视图比回调晚一点点，补一次延后重试。 */
    private static final long RETRY_MS = 150;

    private static boolean armed;
    private static ViewGroup windowContent;
    private static View scrim;
    private static String appliedKey;

    @Override public void handleLoadPackage(XC_LoadPackage.LoadPackageParam param) {
        if (!"com.android.systemui".equals(param.packageName)) return;
        // 机型门禁已撤（2026-10-09 多机型测试）：失败关闭兜底。
        if (armed) return;
        armed = true;
        Log.i(TAG, PRE + "rev=" + REV);
        hookWindowAdd(param.classLoader);
        hookShortcutCallback(param.classLoader);
    }

    /** 进程里所有窗口都要过 WindowManagerImpl.addView —— 按标题认出快捷方式动画窗。 */
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
        } catch (Throwable ignored) { }
        try {
            XposedHelpers.findAndHookMethod("android.view.WindowManagerImpl", loader, "addView",
                    View.class, ViewGroup.LayoutParams.class, hook);
            Log.i(TAG, PRE + "armed via WindowManagerImpl.addView");
        } catch (Throwable error) {
            Log.w(TAG, PRE + "no window hook; shortcut scrim left native: " + error);
        }
    }

    private static void onWindowAdded(Object[] args) {
        if (args == null || args.length < 2) return;
        if (!(args[0] instanceof ViewGroup) || !(args[1] instanceof WindowManager.LayoutParams)) return;
        CharSequence title = ((WindowManager.LayoutParams) args[1]).getTitle();
        if (title == null || !title.toString().contains(WINDOW_TAG)) return;
        windowContent = (ViewGroup) args[0];
        scrim = null; appliedKey = null;
        Log.i(TAG, PRE + "shortcut window added; title=" + title);
        // MIUI 铺尺寸/加子视图比窗口加入晚一点（实测 anim view 在窗口 addView 时还没进去），多试几次。
        apply();
        ((ViewGroup) args[0]).post(() -> apply());
        ((ViewGroup) args[0]).postDelayed(ShortcutAnimBackdrop::apply, 80);
        ((ViewGroup) args[0]).postDelayed(ShortcutAnimBackdrop::apply, RETRY_MS);
    }

    /** ROM 自己的回调驱动重设：进入与退出各来一次（含一次延后重试）。 */
    private static void hookShortcutCallback(ClassLoader loader) {
        try {
            Class<?> controller = XposedHelpers.findClassIfExists(CONTROLLER, loader);
            if (controller == null) { Log.w(TAG, PRE + CONTROLLER + " not found"); return; }
            XposedHelpers.findAndHookMethod(controller, "onShortcutPluginCallbackWrap",
                    android.os.Bundle.class, String.class, new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam call) {
                            if (!(call.args[1] instanceof String)) return;
                            String action = (String) call.args[1];
                            for (String wanted : APPLY_ACTIONS) {
                                if (!wanted.equals(action)) continue;
                                View anchor = windowContent;
                                if (anchor == null) return;
                                anchor.post(ShortcutAnimBackdrop::apply);
                                anchor.postDelayed(ShortcutAnimBackdrop::apply, RETRY_MS);
                                return;
                            }
                        }
                    });
            Log.i(TAG, PRE + "armed: " + CONTROLLER + ".onShortcutPluginCallbackWrap");
        } catch (Throwable error) {
            Log.w(TAG, PRE + "callback hook failed: " + error);
        }
    }

    /** 找到 MIUI 那层全屏遮罩，把它的 background 换成我们的封面。 */
    private static void apply() {
        try {
            if (windowContent == null) return;
            View target = findScrim(windowContent);
            if (target == null) { Log.i(TAG, PRE + "scrim view not found yet"); return; }
            scrim = target;
            int width = scrim.getWidth() > 0 ? scrim.getWidth() : 1080;
            int height = scrim.getHeight() > 0 ? scrim.getHeight() : 2400;
            Drawable backdrop = build(scrim.getContext(), width, height);
            if (backdrop == null) return;
            String key = width + "x" + height + "|" + backdropKey(backdrop);
            if (key.equals(appliedKey) && scrim.getBackground() == backdrop) return;
            appliedKey = key;
            scrim.setBackground(backdrop);
            scrim.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            Log.i(TAG, PRE + "scrim background replaced: " + key);
        } catch (Throwable error) {
            Log.w(TAG, PRE + "apply failed; scrim left native: " + error);
        }
    }

    /**
     * 在动画窗里找 MIUI 的全屏遮罩：类是 `ShortcutOccludedAnimView` 的那棵子树里，
     * **不是 ImageView、且背景是纯色（或已经被我们换过）**的那个子 View
     * （图标是 `ScaleShortcutImageView`，289×289，不会被选中）。
     *
     * 必须认「已经被我们换过的那份 background」：否则换完之后就再也找不到它了，
     * 日志里会一直刷 `scrim view not found yet`，而且**换歌后背景不会更新**（真机踩过）。
     */
    private static View findScrim(View view) {
        if (!(view instanceof ViewGroup)) return null;
        ViewGroup group = (ViewGroup) view;
        if (view.getClass().getSimpleName().contains(ANIM_VIEW)) {
            for (int i = 0; i < group.getChildCount(); i++) {
                View child = group.getChildAt(i);
                if (child instanceof ImageView) continue;              // 图标
                Drawable background = child.getBackground();
                if (background instanceof ColorDrawable) return child;
                if (background != null && background == appliedDrawable) return child;
            }
            return null;
        }
        for (int i = 0; i < group.getChildCount(); i++) {
            View found = findScrim(group.getChildAt(i));
            if (found != null) return found;
        }
        return null;
    }

    /**
     * 造背景。风格与 `LockScreenOverlay.applyBackdrop()` 对齐：
     *   0 深色玻璃 —— 封面（廉价模糊）＋遮罩原样
     *   1 浅色玻璃 —— 封面（更轻）＋遮罩 ×0.55
     *   2 纯色沉浸 —— 不铺封面，直接就是遮罩色
     *
     * 封面＋遮罩**预合成到一张半分辨率位图**，交给 `BitmapDrawable` 拉伸显示：
     * 这样那层每帧的绘制成本与它原来的 `ColorDrawable` 一样（都是一张图铺满），不会重演前身「每帧重画全屏内容」的卡顿。
     */
    private static Drawable build(Context context, int width, int height) {
        int style = Config.overlayStyle(context);
        int color = Config.overlayColor(context);
        int alpha = Config.overlayAlpha(context);
        int scrimAlpha = Math.max(0, Math.min(255, style == 1 ? Math.round(alpha * 0.55f) : alpha));
        if (style == 2) { appliedArtwork = null; return new ColorDrawable(color | 0xFF000000); }
        Bitmap art = currentArtwork();
        if (art == null) { appliedArtwork = null; return new ColorDrawable(color | 0xFF000000); }
        if (art == appliedArtwork && appliedDrawable != null) return appliedDrawable;

        int outW = Math.max(64, width / 4), outH = Math.max(64, height / 4);   // 半分辨率的一半：内容是模糊的，够用且省内存
        Bitmap composed = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(composed);
        Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
        // 廉价模糊：把封面缩到 1/32，再按 CENTER_CROP 拉满整块（双线性 → 软掉的一片）
        int smallW = Math.max(4, art.getWidth() / 32), smallH = Math.max(4, art.getHeight() / 32);
        Bitmap tiny = Bitmap.createScaledBitmap(art, smallW, smallH, true);
        float ratio = Math.max((float) outW / smallW, (float) outH / smallH);
        int drawW = Math.round(smallW * ratio), drawH = Math.round(smallH * ratio);
        int left = (outW - drawW) / 2, top = (outH - drawH) / 2;
        canvas.drawBitmap(tiny, null, new Rect(left, top, left + drawW, top + drawH), paint);
        tiny.recycle();
        canvas.drawColor((color & 0x00FFFFFF) | (scrimAlpha << 24));
        BitmapDrawable drawable = new BitmapDrawable(context.getResources(), composed);
        drawable.setFilterBitmap(true);
        appliedArtwork = art;
        appliedDrawable = drawable;
        Log.i(TAG, PRE + "backdrop built: style=" + style + " " + outW + "x" + outH
                + " color=" + Integer.toHexString(color) + " scrim=" + scrimAlpha + " (cheap blur, no RenderEffect)");
        return drawable;
    }

    private static String backdropKey(Drawable drawable) {
        return drawable instanceof ColorDrawable ? "color" : "bitmap";
    }

    private static Bitmap appliedArtwork;
    private static Drawable appliedDrawable;

    /**
     * 拿封面：反射读锁屏覆盖层当前那一帧，**不钩自己人**。
     *
     * 坑：`XposedHelpers.findAndHookMethod("io.github.melolock.LockScreenOverlay", param.classLoader, …)`
     * 会抛 `ClassNotFoundException` —— legacy Xposed 下模块自己的类由模块自己的 ClassLoader 加载，
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
            Log.w(TAG, PRE + "artwork read failed; falling back to scrim colour: " + error);
        }
        return null;
    }
}
