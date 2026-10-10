package io.github.melolock;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.Looper;
import android.net.Uri;
import android.util.Log;
import java.io.InputStream;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** MediaSession-only data and transport layer; no player package names or ROM classes. */
final class MediaSource {
    private static final String TAG = "MeloLock";
    /**
     * 细节日志开关（debug_log，LockScreenOverlay.create() 按配置写这里）：
     * 关＝不打每轮快照的周期性日志（Session/Media ready/Keeping previous frame），
     * 开＝排查问题时全量输出。生命周期与错误级日志不受它门控。
     */
    static volatile boolean verboseLog;
    interface Listener { void onMedia(Snapshot snapshot); }
    static final class Snapshot {
        final MediaController controller;
        final Bitmap art;
        final String title;
        final String artist;
        final long actions;
        final long positionMs;
        final long positionUpdateTimeMs;
        final long durationMs;
        final float speed;
        /** 会话当前是否在播放。暂停时仍会交出最后一帧（playing=false），锁屏不会被原生界面顶替。 */
        final boolean playing;
        Snapshot(MediaController controller, Bitmap art, String title, String artist, long actions,
                 long positionMs, long positionUpdateTimeMs, long durationMs, float speed, boolean playing) {
            this.controller = controller; this.art = art; this.title = title;
            this.artist = artist; this.actions = actions; this.positionMs = positionMs;
            this.positionUpdateTimeMs = positionUpdateTimeMs; this.durationMs = durationMs;
            this.speed = speed; this.playing = playing;
        }
    }
    private final MediaSessionManager manager;
    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService artworkWorker = Executors.newSingleThreadExecutor();
    private final Listener listener;
    private MediaController current;
    private Snapshot lastReady;
    private String pendingArtworkUri;
    private MediaController pendingArtworkController;
    private boolean started;
    private int generation;
    /**
     * 失去有效会话后的宽限期。切歌时 App 会先把 metadata 里的封面 bitmap 摘掉、甚至把会话
     * 短暂置为不可用（实测 300ms 左右才补齐），这一瞬间「没会话」是过渡态而不是真的停了。
     * 立刻撤回就露原生锁屏，所以先挺住上一帧，超时确认没有会话才回退。
     */
    private static final long SESSION_GRACE_MS = 1500;
    private boolean graceScheduled;
    // 用方法引用而不是 lambda 体：`listener` 在构造函数里赋值，直接在字段初始化器的 lambda 体里
    // 引用它会报「可能尚未初始化变量」。
    private final Runnable sessionLost = this::announceLost;
    private void announceLost() {
        graceScheduled = false;
        lastReady = null;
        pendingArtworkUri = null;
        pendingArtworkController = null;
        Log.i(TAG, "No session for " + SESSION_GRACE_MS + "ms; overlay falls back");
        listener.onMedia(null);
    }
    private final MediaController.Callback controllerCallback = new MediaController.Callback() {
        @Override public void onMetadataChanged(MediaMetadata metadata) { refresh(); }
        @Override public void onPlaybackStateChanged(PlaybackState state) { refresh(); }
        @Override public void onSessionDestroyed() { refresh(); }
    };
    private final MediaSessionManager.OnActiveSessionsChangedListener sessionsListener = sessions -> refresh();

    MediaSource(Context context, Listener listener) {
        this.context = context;
        this.manager = context.getSystemService(MediaSessionManager.class);
        this.listener = listener;
    }
    void start() {
        if (started) { refresh(); return; }
        if (manager == null) { listener.onMedia(null); return; }
        try {
            manager.addOnActiveSessionsChangedListener(sessionsListener, null, main);
            started = true;
            refresh();
        } catch (RuntimeException error) { listener.onMedia(null); }
    }
    void stop() {
        generation++;
        if (started && manager != null) manager.removeOnActiveSessionsChangedListener(sessionsListener);
        started = false;
        if (current != null) current.unregisterCallback(controllerCallback);
        current = null;
        lastReady = null;
        pendingArtworkUri = null;
        pendingArtworkController = null;
        cancelGrace();
    }
    void close() { stop(); artworkWorker.shutdownNow(); }
    private void refresh() {
        final int request = ++generation;
        // 诊断用：把「从查询会话到真正出图」的耗时打出来，用于判断亮屏后的一秒延迟
        // 是花在异步取封面（URI 解码）还是别处。
        final long refreshStart = android.os.SystemClock.elapsedRealtime();
        MediaController selected = null;
        Snapshot snapshot = null;
        String artworkUri = null;
        MediaController pausedSession = null;
        List<MediaController> sessions = null;
        try {
            sessions = manager.getActiveSessions(null);
            for (MediaController candidate : sessions) {
                if (!Config.packageAllowed(context, candidate.getPackageName())) continue;
                PlaybackState state = candidate.getPlaybackState();
                MediaMetadata metadata = candidate.getMetadata();
                if (state == null || metadata == null) continue;
                if (state.getState() != PlaybackState.STATE_PLAYING) {
                    // 会话还在、只是暂停或缓冲：记住它，暂停时覆盖层继续交出最后一帧，
                    // 锁屏不会被原生界面顶替；只有会话真的消失才回退。
                    // 不能依赖 lastReady / current 判定——切歌失败会把 lastReady 清空，
                    // 那时暂停就识别不出来，整个场景会被当成「无会话」销毁掉。
                    if (pausedSession == null) pausedSession = candidate;
                    continue;
                }
                Bitmap art = metadata.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
                if (art == null) art = metadata.getBitmap(MediaMetadata.METADATA_KEY_ART);
                if (art == null) {
                    artworkUri = metadata.getString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI);
                    if (artworkUri == null) artworkUri = metadata.getString(MediaMetadata.METADATA_KEY_ART_URI);
                    if (artworkUri == null) continue;
                } else if (art.isRecycled() || art.getWidth() < 16 || art.getHeight() < 16) continue;
                selected = candidate;
                if (art != null) snapshot = new Snapshot(candidate, art,
                        text(metadata.getText(MediaMetadata.METADATA_KEY_TITLE)),
                        text(metadata.getText(MediaMetadata.METADATA_KEY_ARTIST)), state.getActions(),
                        state.getPosition(), state.getLastPositionUpdateTime(),
                        metadata.getLong(MediaMetadata.METADATA_KEY_DURATION), state.getPlaybackSpeed(), true);
                break;
            }
        } catch (RuntimeException error) { snapshot = null; selected = null; }
        // 暂停时也要继续跟踪会话：否则会注销回调，用户点「播放」后锁屏不会更新。
        MediaController tracked = selected != null ? selected : pausedSession;
        if (tracked == null && lastReady != null && lastReady.controller != null && sessions != null) {
            // 切歌空窗期会话暂时「不合格」（metadata / playbackState 为空），但 App 还在。
            // 若就此把 current 置空会注销回调，之后新封面再到达就没人唤醒我们了——真机现象是
            // 「切歌瞬间掉回原生锁屏、要靠下一次会话列表变化才回来」。按包名重新挂上去继续监听。
            String previousPackage = lastReady.controller.getPackageName();
            for (MediaController candidate : sessions) {
                if (previousPackage.equals(candidate.getPackageName()) && Config.packageAllowed(context, previousPackage)) {
                    tracked = candidate;
                    Log.i(TAG, "Empty gap detected; keep tracking " + previousPackage + " for the next track");
                    break;
                }
            }
        }
        if (current != tracked) {
            if (current != null) current.unregisterCallback(controllerCallback);
            current = tracked;
            if (current != null) current.registerCallback(controllerCallback, main);
        }
        if (snapshot != null) {
            lastReady = snapshot;
            pendingArtworkUri = null;
            pendingArtworkController = null;
            cancelGrace();
            if (verboseLog) Log.i(TAG, "Media ready from bitmap in " + elapsed(refreshStart) + "ms title=" + snapshot.title);
            listener.onMedia(snapshot);
        } else if (selected != null && artworkUri != null) {
            final MediaController controller = selected;
            final String uri = artworkUri;
            // 切歌时某些播放器会重建 MediaSession，不能再用 controller 是否相同判断缓存。
            // 在新 URI 真正解码成功前，上一首完整画面继续显示，避免短暂退回原生锁屏。
            boolean alreadyDecoding = uri.equals(pendingArtworkUri) && controller == pendingArtworkController;
            if (lastReady != null) {
                if (verboseLog) Log.i(TAG, "Keeping previous frame while artwork decode is pending in " + elapsed(refreshStart) + "ms");
                listener.onMedia(lastReady);
            } else {
                Log.i(TAG, "No cached frame in " + elapsed(refreshStart) + "ms; artwork decode required");
                listener.onMedia(null);
            }
            if (alreadyDecoding) return;
            pendingArtworkUri = uri;
            pendingArtworkController = controller;
            main.postDelayed(() -> {
                if (uri.equals(pendingArtworkUri) && pendingArtworkController == controller && current == controller) {
                    pendingArtworkUri = null;
                    pendingArtworkController = null;
                    // 解不出来只是拿不到封面，不等于播放停了（很多 App 给的 URI 在 SystemUI 里
                    // 读不到：content 权限或私有文件）。这里保留上一帧，让会话/下一首来决定。
                    Log.w(TAG, "Artwork decode timed out; keeping previous frame");
                    if (lastReady != null) listener.onMedia(lastReady);
                }
            }, 5000);
            artworkWorker.execute(() -> {
                Bitmap art = loadArt(uri);
                main.post(() -> {
                    if (current != controller || !uri.equals(pendingArtworkUri) || pendingArtworkController != controller) return;
                    if (art == null) {
                        pendingArtworkUri = null;
                        pendingArtworkController = null;
                        // 同上：封面解失败不撤层。以前这里会清空 lastReady 并下发 null，
                        // 后续 refresh 全部落到「无缓存」→ 最后被当成无会话把场景销毁。
                        Log.w(TAG, "Artwork decode failed in " + elapsed(refreshStart) + "ms; keeping previous frame");
                        if (lastReady != null) listener.onMedia(lastReady); else listener.onMedia(null);
                        return;
                    }
                    PlaybackState state = controller.getPlaybackState();
                    MediaMetadata metadata = controller.getMetadata();
                    if (state == null || state.getState() != PlaybackState.STATE_PLAYING || metadata == null) return;
                    lastReady = new Snapshot(controller, art,
                            text(metadata.getText(MediaMetadata.METADATA_KEY_TITLE)),
                            text(metadata.getText(MediaMetadata.METADATA_KEY_ARTIST)), state.getActions(),
                            state.getPosition(), state.getLastPositionUpdateTime(),
                            metadata.getLong(MediaMetadata.METADATA_KEY_DURATION), state.getPlaybackSpeed(), true);
                    pendingArtworkUri = null;
                    pendingArtworkController = null;
                    if (verboseLog) Log.i(TAG, "Media ready from URI decode in " + elapsed(refreshStart) + "ms title=" + lastReady.title);
                    listener.onMedia(lastReady);
                });
            });
        } else if (pausedSession != null && lastReady != null) {
            // 暂停：用会话的最新播放状态刷新最后一帧（speed 归零、进度条停住），
            // 覆盖层继续显示、不撤层。这样「暂停后锁屏」不会再被原生界面顶替。
            PlaybackState pausedState = pausedSession.getPlaybackState();
            if (pausedState != null) {
                lastReady = new Snapshot(lastReady.controller, lastReady.art, lastReady.title, lastReady.artist,
                        pausedState.getActions(), pausedState.getPosition(), pausedState.getLastPositionUpdateTime(),
                        lastReady.durationMs, pausedState.getPlaybackSpeed(),
                        pausedState.getState() == PlaybackState.STATE_PLAYING);
            }
            if (verboseLog) Log.i(TAG, "Session paused in " + elapsed(refreshStart) + "ms; keeping last frame");
            cancelGrace();
            listener.onMedia(lastReady);
        } else if (lastReady != null) {
            // 切歌/重连的空窗期：会话这一瞬间不合格，但上一帧还在。立刻下发 null 会让
            // `render()` 走 `restore("render-no-session")` 撤层，用户看到的就是「切歌进原生
            // 锁屏」。给一段宽限期继续显示上一帧，到期仍无会话才真的回退。
            listener.onMedia(lastReady);
            if (!graceScheduled) {
                graceScheduled = true;
                main.postDelayed(sessionLost, SESSION_GRACE_MS);
            }
            if (verboseLog) Log.i(TAG, "Session unavailable in " + elapsed(refreshStart) + "ms; keeping last frame up to "
                    + SESSION_GRACE_MS + "ms title=" + lastReady.title);
        } else {
            lastReady = null;
            pendingArtworkUri = null;
            pendingArtworkController = null;
            Log.i(TAG, "No playing session in " + elapsed(refreshStart) + "ms; overlay falls back");
            listener.onMedia(null);
        }
    }
    private void cancelGrace() {
        if (!graceScheduled) return;
        graceScheduled = false;
        main.removeCallbacks(sessionLost);
    }
    private static long elapsed(long start) { return android.os.SystemClock.elapsedRealtime() - start; }
    private Bitmap loadArt(String uriText) {
        Uri uri = Uri.parse(uriText);
        String target = (uri.getScheme() == null ? "?" : uri.getScheme()) + "://" + uri.getAuthority();
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            try (InputStream stream = context.getContentResolver().openInputStream(uri)) {
                if (stream == null) { Log.w(TAG, "Artwork stream null: " + target); return null; }
                BitmapFactory.decodeStream(stream, null, bounds);
            }
            if (bounds.outWidth < 16 || bounds.outHeight < 16) {
                Log.w(TAG, "Artwork too small: " + target + " " + bounds.outWidth + "x" + bounds.outHeight);
                return null;
            }
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = 1;
            int largest = Math.max(bounds.outWidth, bounds.outHeight);
            while (largest / options.inSampleSize > 1024) options.inSampleSize *= 2;
            try (InputStream stream = context.getContentResolver().openInputStream(uri)) {
                Bitmap decoded = BitmapFactory.decodeStream(stream, null, options);
                if (decoded == null) Log.w(TAG, "Artwork decode returned null: " + target);
                return decoded;
            }
        } catch (Exception error) {
            // 打印原因：多数情况是 content:// 的读取权限不属于 SystemUI，或 URI 指向 App 私有文件。
            Log.w(TAG, "Artwork open failed: " + target + " " + error);
            return null;
        }
    }
    private static String text(CharSequence value) { return value == null ? "" : value.toString(); }
}
