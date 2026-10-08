package io.github.melolock;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.Build;
import android.util.Log;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;
import java.lang.reflect.Method;
import java.util.Arrays;

/**
 * 临时探针（AGENTS.md 约定：独立 Xposed 入口，只登记在 xposed_init；结论拿到后源码与清单一并删除）。
 *
 * 只回答一个问题：能否在 com.miui.miwallpaper 进程里，把「锁屏壁纸」上传 GPU 的 Bitmap 换成纯品红。
 * 成功判据：锁屏亮屏看到整屏品红。看到 → 换纹理路线成立；看不到 → 回头，不浪费集成成本。
 *
 * 依据（docs/RESEARCH-lockscreen-approaches.md）：
 * - 目标类与方法已在本机 dex 核实存在；
 * - lambda 方法名带 D8 后缀（lambda$onSurfaceCreated$0$com-miui-...），必须前缀匹配，不能精确名查找；
 * - 只动「类名含 Keyguard」的实例，桌面壁纸绝不动（否则用户回不到桌面）。
 *
 * 每个候选类独立 try/catch：一个类失败只放弃它自己，其余照常（失败关闭，绝不影响壁纸进程存活）。
 */
public final class WallpaperTexProbe implements IXposedHookLoadPackage {
    private static final String TAG = "MeloLock";
    /** 修订串：装机后先在日志里看到它，再让人复现（AGENTS.md：别假设装了就生效）。 */
    private static final String REV = "WTP-2";
    private static final int MAGENTA = 0xFFFF00FF;

    /** 候选上传点：opengl.ImageWallpaperRenderer（通用）与 container.openGL.KeyguardAnimImageWallpaperRenderer（keyguard 专用）。 */
    private static final String[] RENDERER_CLASSES = {
            "com.miui.miwallpaper.opengl.ImageWallpaperRenderer",
            "com.miui.miwallpaper.container.openGL.KeyguardAnimImageWallpaperRenderer",
            "com.miui.miwallpaper.container.openGL.KeyguardStreamAnimImageWallpaperRenderer",
    };

    @Override public void handleLoadPackage(XC_LoadPackage.LoadPackageParam param) {
        if (!"com.miui.miwallpaper".equals(param.packageName)) return;
        Log.i(TAG, "WallpaperTexProbe rev=" + REV + " loaded in " + param.packageName
                + " fingerprint=" + Build.FINGERPRINT);
        if (!Config.FINGERPRINT.equals(Build.FINGERPRINT)) {
            Log.i(TAG, "WTP fingerprint mismatch; probe disabled");
            return;
        }
        try {
            hookRestartChannel(param.classLoader);
        } catch (Throwable error) {
            // 无 root 重启通道装不上只影响「重启作用域按钮对壁纸进程失效」，hook 本体不受影响。
            Log.w(TAG, "WTP restart channel unavailable (仅该功能放弃)", error);
        }
        for (String className : RENDERER_CLASSES) {
            try {
                probeRenderer(param.classLoader, className);
            } catch (Throwable error) {
                // 只放弃这一个候选，其余继续。
                Log.w(TAG, "WTP probe " + className + " unavailable (仅该候选放弃)", error);
            }
        }
    }

    /**
     * 无 root 重启通道：与 SystemUI 侧的 restartReceiver 同构（[LockScreenOverlay] 122-128）。
     * 配置端「重启作用域」发显式广播到本包，收到后 kill 自己 —— 壁纸服务被系统自动重绑，
     * 重绑时 Vector 重新注入模块，新 hook 就装上了（等价 `am force-stop com.miui.miwallpaper`）。
     *
     * Context 怎么拿：本进程没有稳定的 View 可以 getContext()，改为 hook WallpaperService#onCreate，
     * thisObject 就是 Service（本身是 Context）。static flag 防止多个壁纸 engine 重复注册。
     */
    private static boolean restartChannelArmed = false;

    private static void hookRestartChannel(ClassLoader loader) throws Throwable {
        Class<?> service = XposedHelpers.findClass("android.service.wallpaper.WallpaperService", loader);
        // onCreate 声明在 WallpaperService 或其父类（Service）上，沿父类链找。
        Method onCreate = null;
        for (Class<?> type = service; type != null; type = type.getSuperclass()) {
            try {
                onCreate = type.getDeclaredMethod("onCreate");
                break;
            } catch (NoSuchMethodException ignored) { /* 继续向上 */ }
        }
        if (onCreate == null) throw new NoSuchMethodException("WallpaperService.onCreate not found");
        XposedBridge.hookMethod(onCreate, new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam hook) {
                if (restartChannelArmed) return;
                if (!(hook.thisObject instanceof android.content.Context)) return;
                android.content.Context context = (android.content.Context) hook.thisObject;
                try {
                    context.registerReceiver(new android.content.BroadcastReceiver() {
                        @Override public void onReceive(android.content.Context c, android.content.Intent intent) {
                            if (!Config.ACTION_RESTART_WALLPAPER.equals(intent.getAction())) return;
                            Log.i(TAG, "WTP restart requested via broadcast; killing wallpaper process");
                            android.os.Process.killProcess(android.os.Process.myPid());
                        }
                    }, new android.content.IntentFilter(Config.ACTION_RESTART_WALLPAPER),
                            android.content.Context.RECEIVER_EXPORTED);
                    restartChannelArmed = true;
                    Log.i(TAG, "WTP restart channel armed (action=" + Config.ACTION_RESTART_WALLPAPER + ")");
                } catch (Throwable error) {
                    Log.w(TAG, "WTP restart channel register failed", error);
                }
            }
        });
    }

    private static void probeRenderer(ClassLoader loader, String className) throws Throwable {
        Class<?> cls = XposedHelpers.findClass(className, loader);
        int hooked = 0;
        for (Method method : cls.getDeclaredMethods()) {
            boolean takesBitmap = false;
            for (Class<?> type : method.getParameterTypes()) {
                if (type == Bitmap.class) takesBitmap = true;
            }
            boolean uploadLambda = method.getName().startsWith("lambda$onSurfaceCreated$");
            boolean bitmapGetter = method.getName().equals("getBitmap");
            if (!(takesBitmap || bitmapGetter)) continue;
            try {
                XposedBridge.hookMethod(method, new Probe());
                hooked++;
                Log.i(TAG, "WTP hooked " + className + "#" + method.getName()
                        + " params=" + Arrays.toString(method.getParameterTypes()));
            } catch (Throwable error) {
                Log.w(TAG, "WTP hook " + className + "#" + method.getName() + " failed (仅该方法放弃)", error);
            }
        }
        if (hooked == 0) {
            Log.w(TAG, "WTP no hookable upload point in " + className
                    + " (该类此 ROM 无 Bitmap 参数方法/lambda —— 记录后换下一候选)");
        }
    }

    /** 探针钩子：日志全打（含非 keyguard 命中），替换只动类名含 Keyguard 的实例。 */
    private static final class Probe extends XC_MethodHook {
        @Override protected void beforeHookedMethod(MethodHookParam hook) {
            String self = selfName(hook);
            boolean keyguard = self.contains("Keyguard");
            Log.i(TAG, "WTP hit " + hook.method.getName() + " self=" + self + " keyguard=" + keyguard
                    + " args=" + describe(hook.args));
            if (!keyguard) return;
            for (int i = 0; i < hook.args.length; i++) {
                if (hook.args[i] instanceof Bitmap) {
                    Bitmap original = (Bitmap) hook.args[i];
                    hook.args[i] = magenta(original);
                    Log.i(TAG, "WTP replaced arg#" + i + " " + original.getWidth() + "x" + original.getHeight());
                }
            }
        }

        @Override protected void afterHookedMethod(MethodHookParam hook) {
            if (!(hook.getResult() instanceof Bitmap)) return;
            String self = selfName(hook);
            if (!self.contains("Keyguard")) return;
            Bitmap result = (Bitmap) hook.getResult();
            hook.setResult(magenta(result));
            Log.i(TAG, "WTP replaced result " + result.getWidth() + "x" + result.getHeight());
        }

        private static String selfName(MethodHookParam hook) {
            return hook.thisObject == null ? "(static)" : hook.thisObject.getClass().getName();
        }

        private static String describe(Object[] args) {
            StringBuilder text = new StringBuilder("[");
            for (int i = 0; i < args.length; i++) {
                if (i > 0) text.append(", ");
                text.append(args[i] == null ? "null" : args[i].getClass().getSimpleName());
            }
            return text.append(']').toString();
        }
    }

    /** 在原图的副本上画品红：尺寸与配置不变，避免 GL 矩阵源矩形错位（不 hook getTextureDimensions）。 */
    private static Bitmap magenta(Bitmap source) {
        try {
            Bitmap out = source.copy(source.getConfig(), true);
            new Canvas(out).drawColor(MAGENTA);
            return out;
        } catch (Throwable error) {
            Log.w(TAG, "WTP magenta copy failed; 交回原图", error);
            return source;
        }
    }
}
