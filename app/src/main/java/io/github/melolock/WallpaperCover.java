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
    private static final String REV = "WCV-1";
    /** 本机实测的锁屏壁纸上传点（KeyguardAnimImageWallpaperRenderer#getBitmap, params=[]）。 */
    private static final String RENDERER_CLASS =
            "com.miui.miwallpaper.container.openGL.KeyguardAnimImageWallpaperRenderer";

    private static volatile Bitmap coverWallpaper;
    /** 原生壁纸位图尺寸（getBitmap 的原生返回值），合成封面时用它；未拿到前用本机已知值兜底。 */
    private static volatile int targetWidth = 1440, targetHeight = 3200;
    private static volatile boolean restartChannelArmed = false;
    private static final ExecutorService worker = Executors.newSingleThreadExecutor();

    @Override public void handleLoadPackage(XC_LoadPackage.LoadPackageParam param) {
        if (!Config.WALLPAPER_PACKAGE.equals(param.packageName)) return;
        Log.i(TAG, "WallpaperCover rev=" + REV + " loaded in " + param.packageName
                + " fingerprint=" + Build.FINGERPRINT);
        try { armRestartChannel(param.classLoader); }
        catch (Throwable error) { Log.w(TAG, "WCV restart channel unavailable (仅该功能放弃)", error); }
        try { hookRenderer(param.classLoader); }
        catch (Throwable error) { Log.w(TAG, "WCV renderer hook failed; 原生壁纸保留（失败关闭）", error); }
    }

    /** hook getBitmap：有缓存封面 → 返回缓存的壁纸尺寸位图；否则不动（原生壁纸）。 */
    private static void hookRenderer(ClassLoader loader) throws Throwable {
        Class<?> renderer = XposedHelpers.findClass(RENDERER_CLASS, loader);
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
        } catch (Throwable error) {
            coverWallpaper = null;
            Log.w(TAG, "WCV apply failed; native wallpaper restored (失败关闭)", error);
        }
    }
}
