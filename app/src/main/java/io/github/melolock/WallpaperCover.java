package io.github.melolock;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.Build;
import android.util.Log;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;
import java.lang.reflect.Method;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 把媒体封面变成锁屏壁纸（正式实现；2026-10-08 探针 WallpaperTexProbe 验证后替代之，探针已按
 * AGENTS.md 约定删除）。
 *
 * <p>动机：MIUI 时钟液态玻璃与通知卡模糊采样的是<b>壁纸窗口</b>，SystemUI 里叠 View 永远采样不到；
 * 且解锁时系统对壁纸窗口执行原生退场动画（updateKeyguardWallpaperState）——封面成为壁纸后，
 * 解锁「早掀盖露原生壁纸 / 晚掀盖挡桌面动效」的两头堵从结构上消失（见 docs/RESEARCH-lockscreen-approaches.md）。
 *
 * <p>分工：配置与封面全部由 SystemUI 侧（{@link WallpaperCoverPush}）决策并随广播下发，
 * 本进程不读任何模块配置（读不到，SELinux）。收到封面 → 缓存成壁纸尺寸的位图；收到清除或
 * 没有缓存 → getBitmap 原样返回（原生壁纸，fail closed）。
 *
 * <p>红线遵守：getBitmap 每次返回<b>同一引用</b>（引用去重，避免 GL 反复上传）；任何失败只放弃
 * 本次替换，绝不抛出影响壁纸进程。
 */
public final class WallpaperCover implements IXposedHookLoadPackage {
    private static final String TAG = "MeloLock";
    private static final String REV = "WCV-9";
    /** 本机实测的锁屏壁纸上传点（KeyguardAnimImageWallpaperRenderer#getBitmap, params=[]）。 */
    private static final String RENDERER_CLASS =
            "com.miui.miwallpaper.container.openGL.KeyguardAnimImageWallpaperRenderer";
    /**
     * 桌面壁纸渲染器（2026-10-16 补，rev WCV-5）：上滑进密码界面（bouncer）时系统会把桌面壁纸窗口显到
     * 锁屏壁纸后面，密码界面的毛玻璃采样的是<b>桌面壁纸</b> —— 只换锁屏渲染器的话密码界面
     * 背景就跳回桌面壁纸，不沉浸（用户反馈）。桌面渲染器与锁屏同族，同样有 getBitmap 上传点
     * （dex 核实：DesktopAnimImageWallpaperRenderer#getBitmap → WallpaperServiceController
     * #getDesktopBitmap）。
     */
    private static final String DESKTOP_RENDERER_CLASS =
            "com.miui.miwallpaper.container.openGL.DesktopAnimImageWallpaperRenderer";
    /**
     * 壁纸进程自己的锁屏状态单例：SystemUI 经 MiuiKeyguardWallpaperRemoteService 调
     * {@code WallpaperServiceController#setKeyguardLocked(boolean)} 推状态（dex 核实），
     * 所以壁纸进程<b>自己就知道锁没锁</b>，不需要 SystemUI 再开一条广播通道。
     */
    private static final String CONTROLLER_CLASS =
            "com.miui.miwallpaper.manager.WallpaperServiceController";

    private static volatile Bitmap coverWallpaper;
    /** 原生壁纸位图尺寸（getBitmap 的原生返回值），合成封面时用它；未拿到前用本机已知值兜底。 */
    private static volatile int targetWidth = 1440, targetHeight = 3200;
    private static volatile boolean restartChannelArmed = false;
    private static final ExecutorService worker = Executors.newSingleThreadExecutor();

    /** 锁屏状态镜像：setKeyguardLocked 钩子维护，getBitmap 热路径零反射。 */
    private static volatile boolean keyguardLockedNow = false;
    private static volatile boolean lockSignalSeen = false;
    /** 兜底直查用（仅 setKeyguardLocked 钩子没挂上时）：单例与 Method 各缓存一次。 */
    private static volatile ClassLoader wpClassLoader;
    private static volatile Object lockController;
    private static volatile java.lang.reflect.Method isKeyguardLockedMethod;
    private static volatile boolean fallbackBroken = false;
    /** 日志去重：只在「桌面壁纸交给谁」翻转时打一行，绝不逐帧刷（红线 3）。 */
    private static volatile String lastDesktopServed = "";
    /**
     * WCV-7：捕获的两个渲染器实例（每引擎一个、与进程同寿，强引用安全）。真机证据：bouncer
     * 显示期间 getBitmap <b>零调用</b>——桌面引擎纹理是初始化时上传的静态内容，光换数据源
     * 够不着它；必须用 public 的 {@code AnimImageWallpaperRenderer#refresh()} 强制重查+重传。
     */
    private static volatile Object keyguardRendererInstance;
    private static volatile Object desktopRendererInstance;
    private static volatile boolean refreshBroken = false;

    @Override public void handleLoadPackage(XC_LoadPackage.LoadPackageParam param) {
        if (!Config.WALLPAPER_PACKAGE.equals(param.packageName)) return;
        wpClassLoader = param.classLoader;
        Log.i(TAG, "WallpaperCover rev=" + REV + " loaded in " + param.packageName
                + " fingerprint=" + Build.FINGERPRINT);
        try { armRestartChannel(param.classLoader); }
        catch (Throwable error) { Log.w(TAG, "WCV restart channel unavailable (仅该功能放弃)", error); }
        try { hookRenderer(param.classLoader); }
        catch (Throwable error) { Log.w(TAG, "WCV renderer hook failed; 原生壁纸保留（失败关闭）", error); }
        try { hookDesktopRenderer(param.classLoader); }
        catch (Throwable error) { Log.w(TAG, "WCV desktop hook failed; 密码界面背景维持原生（失败关闭）", error); }
        try { hookControllerBitmaps(param.classLoader); }
        catch (Throwable error) { Log.w(TAG, "WCV controller bitmap hook failed; 维持渲染器层替换", error); }
        try { hookLockSignal(param.classLoader); }
        catch (Throwable error) { Log.w(TAG, "WCV lock signal hook failed; 退回 isKeyguardLocked 直查", error); }
    }

    /**
     * 向 SystemUI 索要当前封面（2026-10-12）。壁纸进程活着时缓存一直在，只有重启丢缓存才需要
     * 重发，所以由本进程启动时自己要，而不是让 SystemUI 每次亮屏重推（那会每次重建位图、
     * 重传 GL 纹理，重传间隙闪一帧原生壁纸＝「亮屏慢一拍」）。
     * 带两次重试：本进程往往比 SystemUI 的接收器先起来，首次广播可能落空。
     */
    private static void requestCover(Context context) {
        worker.execute(() -> {
            for (int attempt = 0; attempt < 3; attempt++) {
                try {
                    Intent intent = new Intent(Config.ACTION_COVER_REQUEST)
                            .setPackage(Config.SYSTEMUI_PACKAGE);
                    context.sendBroadcast(intent);
                    Log.i(TAG, "WCV cover requested from SystemUI (attempt " + (attempt + 1) + ")");
                } catch (Throwable error) {
                    Log.w(TAG, "WCV cover request failed", error);
                }
                try { Thread.sleep(attempt == 0 ? 1500 : 3000); } catch (InterruptedException ignored) { }
            }
        });
    }

    /** hook getBitmap：有缓存封面 → 返回缓存的壁纸尺寸位图；否则不动（原生壁纸）。 */
    private static void hookRenderer(ClassLoader loader) throws Throwable {
        Class<?> renderer = XposedHelpers.findClass(RENDERER_CLASS, loader);
        XposedBridge.hookAllConstructors(renderer, new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam hook) {
                keyguardRendererInstance = hook.thisObject;
                Log.i(TAG, "WCV keyguard renderer instance captured");
            }
        });
        XposedHelpers.findAndHookMethod(renderer, "getBitmap", new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam hook) {
                if (!(hook.getResult() instanceof Bitmap)) return;
                Bitmap nativeBitmap = (Bitmap) hook.getResult();
                targetWidth = nativeBitmap.getWidth();
                targetHeight = nativeBitmap.getHeight();
                Bitmap cover = coverWallpaper;
                if (cover == null || cover.isRecycled()) return;
                hook.setResult(cover);
            }
        });
        Log.i(TAG, "WCV renderer hook installed: " + RENDERER_CLASS + "#getBitmap");
    }

    /**
     * 密码界面（bouncer）背景补丁（2026-10-16）：上滑弹密码时系统把桌面壁纸窗口显到后面，
     * bouncer 的毛玻璃采样桌面壁纸 —— 桌面渲染器的同一上传点在<b>锁屏期间</b>也返回封面，
     * 解锁立即交还原生桌面壁纸（桌面绝不能被封面钉住，失败关闭）。
     */
    private static void hookDesktopRenderer(ClassLoader loader) throws Throwable {
        Class<?> renderer = XposedHelpers.findClass(DESKTOP_RENDERER_CLASS, loader);
        XposedBridge.hookAllConstructors(renderer, new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam hook) {
                desktopRendererInstance = hook.thisObject;
                Log.i(TAG, "WCV desktop renderer instance captured");
            }
        });
        XposedHelpers.findAndHookMethod(renderer, "getBitmap", new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam hook) {
                if (!(hook.getResult() instanceof Bitmap)) return;
                Bitmap nativeBitmap = (Bitmap) hook.getResult();
                targetWidth = nativeBitmap.getWidth();
                targetHeight = nativeBitmap.getHeight();
                Bitmap cover = coverWallpaper;
                if (!keyguardLocked() || cover == null || cover.isRecycled()) {
                    noteDesktopServed(false);
                    return;
                }
                noteDesktopServed(true);
                hook.setResult(cover); // 与锁屏渲染器同一引用，GL 侧引用去重不重复上传
            }
        });
        Log.i(TAG, "WCV desktop renderer hook installed: " + DESKTOP_RENDERER_CLASS + "#getBitmap");
    }

    /**
     * 锁屏状态主通道：镜像 SystemUI 推来的 {@code setKeyguardLocked}（签名 dex 核实为
     * {@code (Z)V}），热路径只读布尔量。没挂上（ROM 改名/未注入）时
     * {@link #keyguardLockedFallback()} 直查同一单例兜底。
     */
    private static void hookLockSignal(ClassLoader loader) throws Throwable {
        Class<?> controller = XposedHelpers.findClass(CONTROLLER_CLASS, loader);
        XposedHelpers.findAndHookMethod(controller, "setKeyguardLocked", boolean.class, new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam hook) {
                keyguardLockedNow = Boolean.TRUE.equals(hook.args[0]);
                lockSignalSeen = true;
                Log.i(TAG, "WCV keyguard locked=" + keyguardLockedNow);
                // WCV-7：锁态翻转时强制渲染器重查 getBitmap（桌面引擎纹理是静态的，
                // 不刷新就永远是初始化时的原生壁纸 —— bouncer 背景治不好的根因）。
                refreshRenderers("locked=" + keyguardLockedNow);
            }
        });
        Log.i(TAG, "WCV lock signal hook installed: " + CONTROLLER_CLASS + "#setKeyguardLocked");
    }

    /**
     * 对捕获的渲染器实例调 {@code AnimImageWallpaperRenderer#refresh()}（public，dex 核实：
     * {@code ()V} 与 {@code (Z)V} 两个重载），强制重走 getBitmap → 纹理重传。锁屏∧有封面
     * → 桌面纹理变封面（bouncer 背景生效）；解锁/无封面 → 交还原生（失败关闭）。仅锁态
     * 翻转与封面变更时调用，绝不进每帧路径。
     */
    private static void refreshRenderers(String why) {
        if (refreshBroken) return;
        int done = 0, failed = 0;
        Object[] targets = {keyguardRendererInstance, desktopRendererInstance};
        for (Object renderer : targets) {
            if (renderer == null) continue;
            try {
                // refresh() 在 AnimImageWallpaperRenderer 上是 PROTECTED（dex 0x0004），
                // getMethods() 拿不到 —— 必须沿父类链 getDeclaredMethods + setAccessible。
                java.lang.reflect.Method refresh = null;
                for (Class<?> type = renderer.getClass(); type != null && refresh == null;
                        type = type.getSuperclass()) {
                    for (java.lang.reflect.Method m : type.getDeclaredMethods()) {
                        if (!"refresh".equals(m.getName())) continue;
                        Class<?>[] p = m.getParameterTypes();
                        if (p.length == 0) { refresh = m; break; }
                        if (p.length == 1 && p[0] == boolean.class) refresh = m;
                    }
                }
                if (refresh == null) continue;
                refresh.setAccessible(true);
                if (refresh.getParameterTypes().length == 0) refresh.invoke(renderer);
                else refresh.invoke(renderer, !keyguardLocked()); // (Z) 语义未知，解锁交还原生时强制刷新
                done++;
            } catch (Throwable error) {
                failed++;
                Log.w(TAG, "WCV renderer refresh failed on " + renderer.getClass().getSimpleName(), error);
            }
        }
        Log.i(TAG, "WCV renderers refreshed (" + why + ") done=" + done + " failed=" + failed);
    }

    private static boolean keyguardLocked() {
        if (lockSignalSeen) return keyguardLockedNow;
        return keyguardLockedFallback();
    }

    /** 兜底：直查壁纸进程自己的单例（instance/Method 各缓存一次，不逐次反射查找）。读不到＝未锁（失败关闭）。 */
    private static boolean keyguardLockedFallback() {
        if (fallbackBroken) return false;
        try {
            Object controller = lockController;
            if (controller == null) {
                Class<?> type = XposedHelpers.findClass(CONTROLLER_CLASS, wpClassLoader);
                controller = XposedHelpers.callStaticMethod(type, "getInstance");
                lockController = controller;
            }
            java.lang.reflect.Method method = isKeyguardLockedMethod;
            if (method == null) {
                method = controller.getClass().getMethod("isKeyguardLocked");
                method.setAccessible(true);
                isKeyguardLockedMethod = method;
            }
            return Boolean.TRUE.equals(method.invoke(controller));
        } catch (Throwable error) {
            fallbackBroken = true;
            Log.w(TAG, "WCV isKeyguardLocked fallback broken; desktop 替换停用（失败关闭）", error);
            return false;
        }
    }

    private static void noteDesktopServed(boolean cover) {
        String state = cover ? "cover" : "native";
        if (state.equals(lastDesktopServed)) return;
        lastDesktopServed = state;
        Log.i(TAG, "WCV desktop wallpaper -> " + state);
    }

    /**
     * 数据源层补丁（2026-10-16 深夜，WCV-6）：WCV-5 上机后真机日志证明 bouncer 期间桌面渲染器
     * 的 {@code getBitmap} <b>一次都没被调</b>（壁纸进程仅开机时一条 {@code -> native}）——
     * 密码界面的背景走的是另一条取图路径（dex 里 {@code WallpaperServiceController#
     * getKeyguardBitmap} 有两个调用点，渲染器层只钩住其一）。改在<b>数据源</b>兜底：锁屏期间
     * 控制器的 {@code getKeyguardBitmap()}/{@code getDesktopBitmap()} 一律返回封面，不管上层
     * 哪个渲染器/哪条路径来取。解锁交还原生（失败关闭）；日志按 keyguard/desktop 各自翻转去重。
     */
    private static void hookControllerBitmaps(ClassLoader loader) throws Throwable {
        Class<?> controller = XposedHelpers.findClass(CONTROLLER_CLASS, loader);
        XC_MethodHook serveCover = new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam hook) {
                if (!(hook.getResult() instanceof Bitmap)) return;
                Bitmap cover = coverWallpaper;
                if (!keyguardLocked() || cover == null || cover.isRecycled()) {
                    noteServed(hook.method.getName(), false);
                    return;
                }
                noteServed(hook.method.getName(), true);
                hook.setResult(cover);
            }
        };
        XposedHelpers.findAndHookMethod(controller, "getKeyguardBitmap", serveCover);
        XposedHelpers.findAndHookMethod(controller, "getDesktopBitmap", serveCover);
        Log.i(TAG, "WCV controller bitmap hook installed: "
                + CONTROLLER_CLASS + "#getKeyguardBitmap/#getDesktopBitmap");
    }

    private static void noteServed(String source, boolean cover) {
        String state = cover ? "cover" : "native";
        String last;
        String key = "served:" + source;
        synchronized (WallpaperCover.class) {
            last = lastServedBySource.get(key);
            if (state.equals(last)) return;
            lastServedBySource.put(key, state);
        }
        Log.i(TAG, "WCV " + source + " -> " + state);
    }
    private static final java.util.HashMap<String, String> lastServedBySource = new java.util.HashMap<>();

    /**
     * 广播入口：封面 JPEG（{@link Config#EXTRA_COVER_JPEG}）或清除（无该 extra）。
     * 解码与合成在单线程后台做，主线程只换引用。
     */
    private static void armRestartChannel(ClassLoader loader) throws Throwable {
        Method onCreate = null;
        for (Class<?> type = XposedHelpers.findClass("android.service.wallpaper.WallpaperService", loader);
                type != null && onCreate == null; type = type.getSuperclass()) {
            try { onCreate = type.getDeclaredMethod("onCreate"); } catch (NoSuchMethodException ignored) { }
        }
        if (onCreate == null) throw new NoSuchMethodException("WallpaperService.onCreate not found");
        XposedBridge.hookMethod(onCreate, new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam hook) {
                if (restartChannelArmed || !(hook.thisObject instanceof Context)) return;
                Context context = (Context) hook.thisObject;
                try {
                    IntentFilter filter = new IntentFilter();
                    filter.addAction(Config.ACTION_WALLPAPER_COVER);
                    filter.addAction(Config.ACTION_RESTART_WALLPAPER);
                    requestCover(context);   // 进程刚起（可能丢过缓存）→ 主动向 SystemUI 要一次
                    context.registerReceiver(new BroadcastReceiver() {
                        @Override public void onReceive(Context c, Intent intent) {
                            String action = intent == null ? null : intent.getAction();
                            if (Config.ACTION_RESTART_WALLPAPER.equals(action)) {
                                Log.i(TAG, "WCV restart requested via broadcast");
                                android.os.Process.killProcess(android.os.Process.myPid());
                            } else if (Config.ACTION_WALLPAPER_COVER.equals(action)) {
                                byte[] jpeg = intent.getByteArrayExtra(Config.EXTRA_COVER_JPEG);
                                worker.execute(() -> applyCover(jpeg));
                            }
                        }
                    }, filter, Context.RECEIVER_EXPORTED);
                    restartChannelArmed = true;
                    Log.i(TAG, "WCV broadcast channel armed");
                } catch (Throwable error) {
                    Log.w(TAG, "WCV register receiver failed", error);
                }
            }
        });
    }

    /** 后台：JPEG 解码 → 铺到目标尺寸（cover-crop 居中）。失败清缓存回退原生壁纸。 */
    private static void applyCover(byte[] jpeg) {
        try {
            if (jpeg == null || jpeg.length == 0) {
                coverWallpaper = null;
                Log.i(TAG, "WCV cover cleared; native wallpaper restored");
                refreshRenderers("cover-cleared");
                return;
            }
            Bitmap art = android.graphics.BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length);
            if (art == null) { Log.w(TAG, "WCV decode failed"); return; }
            int width = art.getWidth(), height = art.getHeight();
            if (width <= 0 || height <= 0) return;
            int tW = targetWidth, tH = targetHeight;
            Bitmap out = Bitmap.createBitmap(tW, tH, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(out);
            canvas.drawColor(0xFF000000);
            float scale = Math.max(tW / (float) width, tH / (float) height);
            float drawW = width * scale, drawH = height * scale;
            Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
            canvas.drawBitmap(art, null,
                    new RectF((tW - drawW) / 2f, (tH - drawH) / 2f,
                            (tW + drawW) / 2f, (tH + drawH) / 2f), paint);
            art.recycle();
            coverWallpaper = out;
            Log.i(TAG, "WCV cover applied " + tW + "x" + tH + " (art " + width + "x" + height + ")");
            // WCV-7：封面在锁屏期间才到达时（先锁屏后放歌），渲染器纹理还停在原生 —— 强制刷新。
            if (keyguardLocked()) refreshRenderers("cover-applied-while-locked");
        } catch (Throwable error) {
            coverWallpaper = null;
            Log.w(TAG, "WCV apply failed; native wallpaper restored (失败关闭)", error);
        }
    }
}
