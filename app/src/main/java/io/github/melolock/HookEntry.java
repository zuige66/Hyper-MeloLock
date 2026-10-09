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
    /**
     * 捕获到的 `KeyguardViewMediator` 实例（点击小封面跳 App 时收锁屏用）。
     *
     * 2026-10-09 实测：从 SystemUI 直接 `startActivity` 启动音乐 App 会被系统以
     * `transition.abort()` 静默回滚（锁屏不允许后面的 Activity 变可见）；而公开的
     * `KeyguardManager.KeyguardLock` 在 Android 16 上对系统 uid 直接抛
     * `UnsupportedOperationException: Only apps can use the KeyguardLock API`。
     * 唯一出路是调 SystemUI 自己的 `KeyguardViewMediator`（dex 核实有无参的
     * `exitKeyguardAndFinishSurfaceBehindRemoteAnimation()` 与
     * `dismiss(IKeyguardDismissCallback, CharSequence)`）。实例由构造器 hook 捕获。
     */
    public static volatile Object keyguardMediator;
    @Override public void handleLoadPackage(XC_LoadPackage.LoadPackageParam param) {
        if (!"com.android.systemui".equals(param.packageName) ||
                !Config.FINGERPRINT.equals(Build.FINGERPRINT)) return;
        Log.i(TAG, "HookEntry rev=HE2 (mediator capture + card-tap launch)");
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
                            // 封面壁纸化（2026-10-08）：SystemUI 侧把媒体封面下发给壁纸进程。
                            // 独立 try：它失败只损失「封面成为壁纸」，覆盖层不受影响。
                            try { WallpaperCoverPush.install(attached.getContext()); }
                            catch (Throwable error) { Log.w(TAG, "WallpaperCoverPush install failed", error); }
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
            // 唤醒信号同理：装不上只是「快速息屏亮屏」退回固定延时预显。
            try { hookWakeSignal(param.classLoader); }
            catch (Throwable error) { Log.w(TAG, "Wake signal hook install failed", error); }
            // 捕获锁屏中介实例：装不上只是「点小封面跳 App」退回直接启动（失败关闭）。
            try { hookMediatorCapture(param.classLoader); }
            catch (Throwable error) { Log.w(TAG, "Mediator capture hook install failed", error); }
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

    /**
     * 「屏幕正在被唤醒」的系统侧信号。
     *
     * 真机实测（2026-10-07，快速连按两下电源键那次）：
     * ```
     * 08.905 onFinishedGoingToSleep
     * 08.925 KeyguardViewMediator: handleNotifyWakingUp        ← 系统已经开始唤醒（比亮屏早 240ms）
     * 08.978 我们的 SCREEN_OFF 广播才到（晚 53ms）
     * 09.165 onScreenTurnedOn                                  ← 面板亮
     * 09.215 我们的预显才跑（晚 50ms）→ 前几帧是原生锁屏
     * ```
     * 所以预显不能只靠 `SCREEN_OFF + 220ms`，要把触发点换成系统自己的唤醒回调。
     *
     * **重要经验：日志文案 ≠ 方法名**。第一版按 `onStartedWakingUp` / `handleNotifyWakingUp`
     * 精确匹配，四个类全部落空（真机上那两个只是 `Log` 的文案）。真正的落点是用 dex 解析挖出来的
     * （逐个 dex 搜「引用了该字符串的方法」）：见 `WAKE_SIGNAL_CLASSES` 注释。
     * 现在改成**按名字模糊匹配**（小写含 `wakingup`）+ 覆盖匿名内部类，并打印实际挂上了哪些方法名。
     */
    private static void hookWakeSignal(ClassLoader loader) {
        XC_MethodHook callback = new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam hook) {
                Log.i(TAG, "Wake signal from system: " + hook.method.getDeclaringClass().getSimpleName()
                        + "#" + hook.method.getName());
                notifySystemWakingUp();
            }
        };
        int armed = 0;
        for (String name : WAKE_SIGNAL_CLASSES) {
            try {
                Class<?> type = XposedHelpers.findClass(name, loader);
                java.util.Set<java.lang.reflect.Method> targets = wakeCandidates(type);
                if (targets.isEmpty()) {
                    Log.w(TAG, "Wake signal hook: none on " + name + " (methods=" + declaredMethodCount(type)
                            + ", wake*=" + namesContaining(type, "wak") + ")");
                    continue;
                }
                StringBuilder armedNames = new StringBuilder();
                for (java.lang.reflect.Method method : targets) {
                    XposedBridge.hookMethod(method, callback);
                    armedNames.append(method.getName()).append(' ');
                    armed++;
                }
                Log.i(TAG, "Wake signal hook armed on " + name + " -> " + armedNames.toString().trim());
            } catch (Throwable error) {
                Log.w(TAG, "Wake signal hook: " + name + " unavailable (" + error + ")");
            }
        }
        if (armed == 0) Log.w(TAG, "Wake signal hook unavailable; pre-show keeps the SCREEN_OFF+delay timing");
    }

    /**
     * 唤醒信号的候选宿主。前三个是**dex 解析核实过的真实落点**（日志文案与实际方法名不同）：
     *   - `KeyguardPanelViewController$wakeObserver$1#onStartedWakingUp` —— 面板的唤醒观察者（实测早亮屏 224ms）
     *   - `KeyguardViewMediator#-$$Nest$mhandleNotifyStartedWakingUp` —— 就是打 `handleNotifyWakingUp` 那行日志的方法
     *   - `KeyguardService$3#onStartedWakingUp` —— 最早的一个（实测早 562ms）
     * 后面两个是 AOSP 常见宿主，顺手一起试，挂上多一个也无害（下游幂等）。
     */
    private static final String[] WAKE_SIGNAL_CLASSES = {
            "com.android.keyguard.panel.KeyguardPanelViewController$wakeObserver$1",
            "com.android.systemui.keyguard.KeyguardViewMediator",
            "com.android.systemui.keyguard.KeyguardService$3",
            "com.android.systemui.keyguard.KeyguardUpdateMonitor",
            "com.android.keyguard.KeyguardUpdateMonitor",
            "com.android.systemui.statusbar.policy.KeyguardStateControllerImpl",
    };

    /** 收集一个类（含父类、接口 —— 唤醒回调常是接口的 default 方法）里名字含 `wakingup` 的方法。 */
    private static java.util.Set<java.lang.reflect.Method> wakeCandidates(Class<?> type) {
        java.util.Set<java.lang.reflect.Method> out = new java.util.HashSet<>();
        for (Class<?> level = type; level != null && level != Object.class; level = level.getSuperclass()) {
            collectWake(level, out);
            for (Class<?> itf : level.getInterfaces()) collectWake(itf, out);
        }
        return out;
    }

    private static void collectWake(Class<?> type, java.util.Set<java.lang.reflect.Method> out) {
        for (java.lang.reflect.Method method : type.getDeclaredMethods())
            if (matchesWakeSignal(method)) out.add(method);
    }

    private static boolean matchesWakeSignal(java.lang.reflect.Method method) {
        return method.getName().toLowerCase(java.util.Locale.ROOT).contains("wakingup");
    }

    /** 诊断用：列出声明方法里名字含某片段的方法名（第一版就是靠它发现「精确匹配全落空」的）。 */
    private static String namesContaining(Class<?> type, String needle) {
        StringBuilder out = new StringBuilder();
        for (Class<?> level = type; level != null && level != Object.class; level = level.getSuperclass())
            for (java.lang.reflect.Method method : level.getDeclaredMethods())
                if (method.getName().toLowerCase(java.util.Locale.ROOT).contains(needle))
                    out.append(method.getName()).append(' ');
        return out.length() == 0 ? "(none)" : out.toString().trim();
    }

    /**
     * 捕获 `KeyguardViewMediator` 实例。构造器 hook 最稳：中介随 SystemUI 启动创建，
     * 拿到的是系统正在用的那个实例。所有构造器重载都挂（参数个数不猜）。
     */
    private static void hookMediatorCapture(ClassLoader loader) {
        Class<?> type = XposedHelpers.findClass(
                "com.android.systemui.keyguard.KeyguardViewMediator", loader);
        XposedBridge.hookAllConstructors(type, new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam hook) {
                keyguardMediator = hook.thisObject;
                Log.i(TAG, "KeyguardViewMediator instance captured");
            }
        });
    }

    private static void notifySystemWakingUp() {
        for (LockScreenOverlay overlay : overlays.values())
            if (overlay != null) overlay.onSystemWakingUp();
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
