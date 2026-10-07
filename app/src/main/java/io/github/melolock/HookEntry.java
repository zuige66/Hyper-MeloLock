package io.github.melolock;

import android.os.Build;
import android.util.Log;
import android.view.View;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;
import java.util.WeakHashMap;

/** The only ROM-specific hook. No code is imported from HyperMusicCover. */
public final class HookEntry implements IXposedHookLoadPackage {
    private static final String TAG = "MeloLock";
    private static final WeakHashMap<View, LockScreenOverlay> overlays = new WeakHashMap<>();
    @Override public void handleLoadPackage(XC_LoadPackage.LoadPackageParam param) {
        if (!"com.android.systemui".equals(param.packageName) ||
                !Config.FINGERPRINT.equals(Build.FINGERPRINT)) return;
        try {
            Class<?> root = XposedHelpers.findClass(
                    "com.android.keyguard.widget.HyperOSKeyguardRootView", param.classLoader);
            Log.i(TAG, "Root class resolved via loader=" + param.classLoader + " -> " + root.getClassLoader());
            XposedHelpers.findAndHookConstructor(root, android.content.Context.class,
                    android.util.AttributeSet.class, new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam hook) {
                            View view = (View) hook.thisObject;
                            view.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
                                @Override public void onViewAttachedToWindow(View attached) {
                                    Log.i(TAG, "Keyguard root attached; visibility=" + attached.getVisibility()
                                            + " shown=" + attached.isShown() + " window=" + attached.getWindowToken());
                                    attached.post(() -> attach(attached));
                                }
                                @Override public void onViewDetachedFromWindow(View detached) {
                                    LockScreenOverlay overlay = overlays.remove(detached);
                                    Log.i(TAG, "Keyguard root detached; overlay=" + (overlay != null));
                                    if (overlay != null) overlay.destroy();
                                }
                            });
                        }
                    });
            // 解锁信号单独 try：它装不上绝不能影响上面已经装好的主 hook。
            try { hookUnlockSignal(param.classLoader); }
            catch (Throwable error) { Log.w(TAG, "Unlock signal hook install failed", error); }
            XposedBridge.log(TAG + ": hook armed for exact OS3 build");
            Log.i(TAG, "SystemUI root constructor hook installed");
        } catch (Throwable error) {
            XposedBridge.log(TAG + ": incompatible hook; native lock screen retained (loader=" + param.classLoader + "): " + error);
            Log.e(TAG, "SystemUI hook unavailable; loader=" + param.classLoader, error);
        }
    }
    /**
     * 解锁信号的候选宿主类：HyperOS 各版本包名/职责划分不同，逐个试；都不中就失败关闭。
     *
     * 真机实测（2026-10-07）：`com.android.systemui.keyguard.KeyguardViewMediator` **存在**，
     * 但**没有** `keyguardGoingAway` 方法 —— 所以下面按**方法名**匹配、不猜参数签名。
     */
    private static final String[] UNLOCK_SIGNAL_CLASSES = {
            "com.android.systemui.keyguard.KeyguardViewMediator",
            "com.android.systemui.statusbar.policy.KeyguardStateControllerImpl",
            "com.android.systemui.statusbar.phone.NotificationShadeWindowControllerImpl",
            "com.android.systemui.shade.NotificationShadeWindowControllerImpl",
            "com.android.systemui.statusbar.phone.CentralSurfacesImpl",
            "com.android.systemui.keyguard.KeyguardStateControllerImpl",
            "com.android.keyguard.KeyguardViewMediator",
    };

    /**
     * 解锁开始的**系统侧信号**：`KeyguardViewMediator#keyguardGoingAway`。
     *
     * 为什么必须从系统拿：pre-draw 守卫要等窗口重绘，实测比 `keyguardGoingAway` 晚 **277~491ms**，
     * 而桌面窗口在 `keyguardGoingAway` 之后 **111~205ms** 就已经可见 —— 这段延迟正是用户反馈的
     * 「看到纯色壁纸然后才进桌面 / 解锁没有原生快」的来源。
     *
     * 该类在 SystemUI 进程内，**不需要扩 Xposed 作用域**。找不到就只打日志、不装，
     * 覆盖层退回旧的 pre-draw 时序（失败关闭，绝不影响解锁）。
     */
    private static void hookUnlockSignal(ClassLoader loader) {
        XC_MethodHook callback = new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam hook) {
                // setKeyguardGoingAway(boolean)：只有参数为 true 才是"开始退场"，false 是反向调用。
                if ("setKeyguardGoingAway".equals(hook.method.getName())
                        && !(hook.args != null && hook.args.length == 1 && Boolean.TRUE.equals(hook.args[0]))) return;
                Log.i(TAG, "Unlock signal from system: " + hook.method.getDeclaringClass().getSimpleName()
                        + "#" + hook.method.getName());
                notifyUnlockStarting();
            }
        };
        int armed = 0;
        for (String name : UNLOCK_SIGNAL_CLASSES) {
            try {
                Class<?> type = XposedHelpers.findClass(name, loader);
                int hits = 0;
                // 只认**语义确定**的两个名字，且不猜签名：
                //   keyguardGoingAway(...)         —— 退场开始（AOSP 在这里发广播 + 起动画）
                //   setKeyguardGoingAway(boolean)  —— 同一个时刻的窗口侧落点，参数为 true 才算
                // 故意不收 isKeyguardGoingAway / onKeyguardGoingAwayChanged 这类会被频繁轮询的成员，
                // 否则可能在锁屏静止时误触发淡出。
                for (Class<?> level = type; level != null && level != Object.class; level = level.getSuperclass()) {
                    for (java.lang.reflect.Method method : level.getDeclaredMethods()) {
                        if (!matchesUnlockSignal(method)) continue;
                        XposedBridge.hookMethod(method, callback);
                        hits++;
                    }
                }
                if (hits > 0) { armed += hits; Log.i(TAG, "Unlock signal hook armed on " + name + " (" + hits + " overload(s))"); }
                else Log.w(TAG, "Unlock signal hook: none on " + name + " (methods=" + declaredMethodCount(type)
                        + ", GoingAway*=" + goingAwayMethodNames(type) + ")");
            } catch (Throwable error) {
                Log.w(TAG, "Unlock signal hook: " + name + " unavailable (" + error + ")");
            }
        }
        if (armed == 0) Log.w(TAG, "Unlock signal hook unavailable; overlay keeps the pre-draw timing");
    }

    private static boolean matchesUnlockSignal(java.lang.reflect.Method method) {
        String name = method.getName();
        // 三个语义确定的落点。真机实测（OS3 16.03）：本机只有 `notifyKeyguardGoingAway` 存在 ——
        // `com.android.systemui.statusbar.policy.KeyguardStateControllerImpl` 里，
        // 而 AOSP 的 `KeyguardViewMediator#keyguardGoingAway` 本来就是调它去通知所有 Callback 的，
        // 所以挂在这里和挂在 keyguardGoingAway 是同一时刻。
        if ("keyguardGoingAway".equals(name) || "notifyKeyguardGoingAway".equals(name)) return true;
        return "setKeyguardGoingAway".equals(name) && method.getParameterTypes().length == 1
                && method.getParameterTypes()[0] == boolean.class;
    }

    /** 诊断用：类的声明方法总数；反射被拦时会明显异常。 */
    private static int declaredMethodCount(Class<?> type) {
        int total = 0;
        for (Class<?> level = type; level != null && level != Object.class; level = level.getSuperclass())
            total += level.getDeclaredMethods().length;
        return total;
    }

    /** 诊断用：列出所有名字里带 `GoingAway` 的声明方法，一眼看出真实的方法叫什么、在哪个类。 */
    private static String goingAwayMethodNames(Class<?> type) {
        StringBuilder text = new StringBuilder();
        for (Class<?> level = type; level != null && level != Object.class; level = level.getSuperclass()) {
            for (java.lang.reflect.Method method : level.getDeclaredMethods()) {
                if (!method.getName().toLowerCase(java.util.Locale.ROOT).contains("goingaway")) continue;
                if (text.length() > 0) text.append(',');
                text.append(method.getName());
            }
        }
        return text.length() == 0 ? "(none)" : text.toString();
    }

    /** 把解锁信号转给当前存活的覆盖层。全部调用点都在主线程（keyguardGoingAway / attach / detach）。 */
    private static void notifyUnlockStarting() {
        LockScreenOverlay[] live = overlays.values().toArray(new LockScreenOverlay[0]);
        for (LockScreenOverlay overlay : live) {
            try { overlay.onUnlockStarting(); } catch (Throwable error) { Log.w(TAG, "Unlock notify failed", error); }
        }
    }

    private static void attach(View root) {
        if (overlays.containsKey(root)) return;
        try {
            Log.i(TAG, "Keyguard root attached");
            LockScreenOverlay overlay = new LockScreenOverlay(root);
            overlays.put(root, overlay);
            overlay.start();
        } catch (Throwable error) {
            XposedBridge.log(TAG + ": overlay skipped: " + error);
            Log.e(TAG, "Overlay attach failed", error);
        }
    }
}
