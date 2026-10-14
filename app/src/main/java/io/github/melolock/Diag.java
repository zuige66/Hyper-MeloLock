package io.github.melolock;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 远程诊断回传（2026-10-14）：把 SystemUI 侧的关键事件与状态经 {@link Config#RUNTIME_URI}
 * 写回 App，配置端「调试 → 诊断报告」展开即可看、一键复制。
 *
 * 为什么需要它：模块的全部日志都打在 SystemUI 进程（tag {@code MeloLock}），没有 root 的
 * 设备上第三方 logcat 工具读不到系统进程，adb 也不是每个用户都有。远程适配时「沉浸式不
 * 显示」看不到证据，只能猜——尤其 {@code compatible()} 版本门禁失败只打一行 warning 就
 * 退回原生锁屏，用户侧毫无感知。
 *
 * 设计约束（都有论证，别改）：
 *  - **insert 会跨进程拉起 App 进程**（Provider 在 App 侧），绝不能占 SystemUI 主线程：
 *    主线程只做 2 秒节流调度，落盘在独立工作线程。
 *  - **内存始终缓冲，开关只 gate 跨进程回写**（2026-10-14 修订，远程适配踩过）：早退版
 *    把 enable 绑在覆盖层创建上，导致「沉浸式没开 / hook 失败 / 模块总开关关着」三种最常见
 *    的远程适配场景下一条事件都不留——而那正是最需要证据的场景。现在 log/put 无条件进内存
 *    （事件是锁屏生命周期级别，不是每帧，成本可忽略），enabled 只决定要不要 flush 给 App。
 *    用户事后打开开关，开机至今的全部历史一次性放出来。
 *  - **环形缓冲**：事件封顶 {@value #MAX_EVENTS} 条、超出丢最旧——诊断报告只要最近现场，
 *    无限增长会撑大 App 的 prefs 文件。
 *  - **flush 失败只打一次 warning**：Provider 查不到时（App 被卸载/禁用）不能刷屏。
 */
final class Diag {
    /** 事件流水的 key（换行分隔，旧 → 新）。App 侧诊断报告读取用，必须与 UI 侧一致。 */
    public static final String KEY_EVENTS = "diag_events";
    /** 最近一次回写的时间戳（毫秒 epoch），App 端显示「更新于 …」。 */
    public static final String KEY_UPDATED_AT = "diag_updated_at";

    private static final String TAG = "MeloLock";
    private static final int MAX_EVENTS = 80;
    private static final long FLUSH_DELAY_MS = 2000;

    private static final ArrayDeque<String> events = new ArrayDeque<>();
    private static final Map<String, String> snapshot = new LinkedHashMap<>();
    private static final SimpleDateFormat CLOCK = new SimpleDateFormat("HH:mm:ss.SSS", Locale.ROOT);
    private static final Handler main = new Handler(Looper.getMainLooper());
    private static final ExecutorService writer = Executors.newSingleThreadExecutor();
    private static final Runnable flushTask = Diag::flush;

    private static volatile Context context;
    private static volatile boolean enabled;
    private static volatile boolean dirty;
    private static volatile boolean deviceInfoPut;
    private static volatile boolean writeFailedLogged;

    private Diag() { }

    /**
     * 开关。调用点：{@code HookEntry.attach()}（开机）、{@code create()}（配置重读）与
     * ACTION_DIAG_REFRESH 广播（App 端「开始/停止记录」与「调试日志」开关）。
     * 打开时把开机至今缓冲的全部历史一次性 flush 给 App——用户往往是出问题后才开开关。
     */
    static void enable(boolean on, Context systemContext) {
        context = systemContext;
        enabled = on;
        if (on) {
            putDeviceInfo();
            log("diag enabled (debug_log on)");
            dirty = true;   // 全量历史立即回写，不等 2 秒节流
            flush();
        }
    }

    /** 记一条事件（自动带时间戳）。无条件进环形缓冲；enabled 只决定是否回写。 */
    static void log(String message) {
        String line = CLOCK.format(new Date()) + " " + message;
        synchronized (events) {
            if (events.size() >= MAX_EVENTS) events.pollFirst();
            events.add(line);
        }
        scheduleFlush();
    }

    /** 写一个状态快照键（如 systemui_version=16.03.251211.r）。无条件进 map，随下次 flush 回写。 */
    static void put(String key, String value) {
        synchronized (snapshot) { snapshot.put(key, value); }
        scheduleFlush();
    }

    /**
     * 清空事件缓冲（App 端「清除报告」按钮经广播触发）。快照键保留（设备信息、版本号是
     * 环境事实，清了下次 flush 也会写回来）；清完立即回写一份空事件流，让 App 端报告同步
     * 变空，不等下一轮 2 秒节流。未 enable 时也清内存——用户可能先攒了现场再决定不开。
     */
    static void clear() {
        synchronized (events) { events.clear(); }
        if (!enabled) return;
        dirty = true;
        flush();
    }

    private static void putDeviceInfo() {
        if (deviceInfoPut) return;
        deviceInfoPut = true;
        put("device_model", String.valueOf(Build.MODEL));
        put("device_android", Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")");
        put("device_fingerprint", String.valueOf(Build.FINGERPRINT));
    }

    private static void scheduleFlush() {
        if (!enabled) return;   // 缓冲照攒，但没开回写就不占主线程
        dirty = true;
        main.removeCallbacks(flushTask);
        main.postDelayed(flushTask, FLUSH_DELAY_MS);
    }

    private static void flush() {
        if (!enabled || !dirty) return;
        dirty = false;
        ContentResolver resolver = context != null ? context.getContentResolver() : null;
        if (resolver == null) return;
        final ContentValues values = new ContentValues();
        synchronized (events) { values.put(KEY_EVENTS, String.join("\n", events)); }
        values.put(KEY_UPDATED_AT, String.valueOf(System.currentTimeMillis()));
        synchronized (snapshot) {
            for (Map.Entry<String, String> entry : snapshot.entrySet()) {
                values.put(entry.getKey(), entry.getValue());
            }
        }
        writer.execute(() -> {
            try {
                resolver.insert(Config.RUNTIME_URI, values);
            } catch (Throwable error) {
                // 常见原因：App 被卸载/停用，Provider 不在。只提醒一次，刷屏会反过来拖慢 SystemUI。
                if (!writeFailedLogged) {
                    writeFailedLogged = true;
                    Log.w(TAG, "Diag flush failed (logged once): " + error);
                }
            }
        });
    }
}
