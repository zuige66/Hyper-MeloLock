package io.github.melolock;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.os.Build;
import android.util.Log;
import java.io.ByteArrayOutputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * SystemUI 侧：把当前媒体封面下发给壁纸进程（{@link WallpaperCover}）。
 *
 * <p>职责边界：模块开关与封面取值都在本进程完成（壁纸进程读不到模块配置），
 * 壁纸侧只做「收到封面就画、收到清除就回退原生」。壁纸进程被杀重启会丢缓存，
 * 所以 {@link android.content.Intent#ACTION_SCREEN_ON} 时重发当前封面补上（fail closed：
 * 补发前壁纸就是原生壁纸，不存在残缺状态）。
 *
 * <p>发送去重：封面 Bitmap 引用没变就不发（红线：引用去重；JPEG 编码是重活）。
 * 编码与发送都在单线程后台，绝不占用 SystemUI 主线程。
 */
public final class WallpaperCoverPush {
    private static final String TAG = "MeloLock";
    private static final String REV = "WCV-1";
    /** 封面最长边：锁屏壁纸 cover-crop 后约 1440x3200，源图 1080 足够清晰且 JPEG 体积可控。 */
    private static final int MAX_EDGE = 1080;

    private static boolean installed = false;
    /**
     * 上次推送的「内容 key」。**不能按 Bitmap 引用去重**：MediaSource 每次回调都重新解码出
     * 新的 Bitmap 实例（2026-10-08 实测同一封面每 1~2 秒被重发一次，壁纸侧跟着每秒重合成 +
     * GL 重传全屏纹理，重传间隙原生壁纸一闪）。同曲目 + 同尺寸才视为同一封面。
     */
    private static volatile String lastSentKey;
    /** 最近一次 MediaSource 回调的快照，亮屏补发用（volatile：回调线程不定）。 */
    private static volatile MediaSource.Snapshot lastSnapshot;
    private static MediaSource media;
    private static Context appContext;
    private static final ExecutorService worker = Executors.newSingleThreadExecutor();

    /** 幂等安装：由 HookEntry 在 keyguard 根 attach 时调用（不触碰 LockScreenOverlay）。 */
    public static void install(Context context) {
        if (installed) return;
        installed = true;
        appContext = context.getApplicationContext() != null
                ? context.getApplicationContext() : context;
        Log.i(TAG, "WallpaperCoverPush rev=" + REV + " installed");
        if (!Config.FINGERPRINT.equals(Build.FINGERPRINT)) {
            Log.i(TAG, "WCV push fingerprint mismatch; disabled");
            return;
        }
        try {
            appContext.registerReceiver(new BroadcastReceiver() {
                @Override public void onReceive(Context c, Intent intent) {
                    if (!Intent.ACTION_SCREEN_ON.equals(intent.getAction())) return;
                    worker.execute(WallpaperCoverPush::resendCurrent);
                }
            }, new IntentFilter(Intent.ACTION_SCREEN_ON), Context.RECEIVER_NOT_EXPORTED);
        } catch (Throwable error) {
            Log.w(TAG, "WCV push screen-on resend unavailable", error);
        }
        try {
            media = new MediaSource(appContext, snapshot -> {
                lastSnapshot = snapshot;
                worker.execute(() -> {
                    try { push(snapshot); }
                    catch (Throwable error) { Log.w(TAG, "WCV push failed", error); }
                });
            });
            media.start();
        } catch (Throwable error) {
            Log.w(TAG, "WCV push media source failed; 壁纸封面不可用（失败关闭）", error);
        }
    }

    /** 亮屏补发：清 key 后无条件重发（壁纸进程可能已重启丢缓存，key 相同也不能跳过）。 */
    private static void resendCurrent() {
        try {
            MediaSource.Snapshot snapshot = lastSnapshot;
            lastSentKey = null;
            push(snapshot);
        } catch (Throwable error) {
            Log.w(TAG, "WCV resend failed", error);
        }
    }

    /** 封面内容 key（曲目 + 尺寸）没变就不发；快照为空＝通知壁纸侧清除。 */
    private static void push(MediaSource.Snapshot snapshot) {
        Bitmap art = snapshot == null ? null : snapshot.art;
        if (art == null || art.isRecycled()) {
            if (lastSentKey != null) {
                lastSentKey = null;
                send(null);
            }
            return;
        }
        String key = snapshot.title + "|" + snapshot.artist
                + "|" + art.getWidth() + "x" + art.getHeight();
        if (key.equals(lastSentKey)) return;
        Bitmap scaled = scaleDown(art);
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        if (!scaled.compress(Bitmap.CompressFormat.JPEG, 85, buffer)) {
            Log.w(TAG, "WCV encode failed");
            return;
        }
        byte[] jpeg = buffer.toByteArray();
        lastSentKey = key;
        send(jpeg);
        if (scaled != art) scaled.recycle();
        Log.i(TAG, "WCV cover pushed " + jpeg.length + " bytes key=" + key);
    }

    private static Bitmap scaleDown(Bitmap art) {
        int width = art.getWidth(), height = art.getHeight();
        int longest = Math.max(width, height);
        if (longest <= MAX_EDGE) return art;
        float scale = MAX_EDGE / (float) longest;
        return Bitmap.createScaledBitmap(art,
                Math.max(1, Math.round(width * scale)),
                Math.max(1, Math.round(height * scale)), true);
    }

    private static void send(byte[] jpeg) {
        try {
            Intent intent = new Intent(Config.ACTION_WALLPAPER_COVER)
                    .setPackage(Config.WALLPAPER_PACKAGE);
            if (jpeg != null) intent.putExtra(Config.EXTRA_COVER_JPEG, jpeg);
            appContext.sendBroadcast(intent);
        } catch (Throwable error) {
            Log.w(TAG, "WCV broadcast failed", error);
        }
    }
}
