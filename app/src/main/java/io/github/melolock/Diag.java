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
 *  - **默认零开销**：只有 {@code debug_log} 打开时才 enable；关闭时 log/put 直接返回，
 *    不攒事件、不调度、不写 Provider。
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
    private static final int EARLY_MAX = 8;
    private static final long FLUSH_DELAY_MS = 2000;

    private static final ArrayDeque<String> events = new ArrayDeque<>();
    /**
     * enable 之前的早退缓冲：用户往往是出问题后才打开 debug_log，开机阶段的失败
     * （hook unavailable、compatible fail 等）不能因此丢失。封顶 8 条。
     */
    private static final ArrayDeque<String> early = new ArrayDeque<>();
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
     * 开关。由 {@code LockScreenOverlay.start()} 与 {@code create()} 按 debug_log 配置调用——
     * start() 里的调用早于 {@code reportVersions()} 采集，这样开机阶段（版本采集之前）的
     * 失败也能进早退缓冲。
     */
    static void enable(boolean on, Context systemContext) {
        context = systemContext;
        enabled = on;
        if (on) {
            putDeviceInfo();
            log("diag enabled (debug_log on)");
        }
    }

    /** 记一条事件（自动带时间戳）。环形缓冲 + 2 秒节流回写。 */
    static void log(String message) {
        String line = CLOCK.format(new Date()) + " " + message;
        synchronized (events) {
            if (!enabled) {
                if (early.size() >= EARLY_MAX) early.pollFirst();
                early.add(line);
                return;
            }
            while (!early.isEmpty()) {
                if (events.size() >= MAX_EVENTS) events.pollFirst();
                events.add(early.poll());
            }
            if (events.size() >= MAX_EVENTS) events.pollFirst();
            events.add(line);
        }
        scheduleFlush();
    }

    /** 写一个状态快照键（如 compatible=pass / systemui_version=16.03.251211.r）。 */
    static void put(String key, String value) {
        if (!enabled) return;
        synchronized (snapshot) { snapshot.put(key, value); }
        scheduleFlush();
    }

    /**
     * 清空事件与早退缓冲（App 端「清除报告」按钮经广播触发）。快照键保留（设备信息、
     * 版本号是环境事实，清了下次 flush 也会写回来）；清完立即回写一份空事件流，
     * 让 App 端报告同步变空，不等下一轮 2 秒节流。
     */
    static void clear() {
        if (!enabled) return;   // 未开启时缓冲本来就是空、也没在回写
        synchronized (events) {
            events.clear();
            early.clear();
        }
        scheduleFlush();
    }

    private static void putDeviceInfo() {
        if (deviceInfoPut) return;
        deviceInfoPut = true;
        put("device_model", String.valueOf(Build.MODEL));
        put("device_android", Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")");
        put("device_fingerprint", String.valueOf(Build.FINGERPRINT));
    }

    private static void scheduleFlush() {
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
