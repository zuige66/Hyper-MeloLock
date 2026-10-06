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
        try {
            List<MediaController> sessions = manager.getActiveSessions(null);
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
        if (current != tracked) {
            if (current != null) current.unregisterCallback(controllerCallback);
            current = tracked;
            if (current != null) current.registerCallback(controllerCallback, main);
        }
        if (snapshot != null) {
            lastReady = snapshot;
            pendingArtworkUri = null;
            pendingArtworkController = null;
            Log.i(TAG, "Media ready from bitmap in " + elapsed(refreshStart) + "ms title=" + snapshot.title);
            listener.onMedia(snapshot);
        } else if (selected != null && artworkUri != null) {
            final MediaController controller = selected;
            final String uri = artworkUri;
            // 切歌时某些播放器会重建 MediaSession，不能再用 controller 是否相同判断缓存。
            // 在新 URI 真正解码成功前，上一首完整画面继续显示，避免短暂退回原生锁屏。
            boolean alreadyDecoding = uri.equals(pendingArtworkUri) && controller == pendingArtworkController;
            if (lastReady != null) {
                Log.i(TAG, "Keeping previous frame while artwork decode is pending in " + elapsed(refreshStart) + "ms");
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
                    lastReady = null;
                    pendingArtworkUri = null;
                    pendingArtworkController = null;
                    Log.w(TAG, "Artwork decode timed out; overlay may fall back");
                    listener.onMedia(null);
                }
            }, 5000);
            artworkWorker.execute(() -> {
                Bitmap art = loadArt(uri);
                main.post(() -> {
                    if (current != controller || !uri.equals(pendingArtworkUri) || pendingArtworkController != controller) return;
                    if (art == null) {
                        lastReady = null;
                        pendingArtworkUri = null;
                        pendingArtworkController = null;
                        Log.i(TAG, "Artwork decode failed in " + elapsed(refreshStart) + "ms; overlay will fall back");
                        listener.onMedia(null);
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
                    Log.i(TAG, "Media ready from URI decode in " + elapsed(refreshStart) + "ms title=" + lastReady.title);
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
            Log.i(TAG, "Session paused in " + elapsed(refreshStart) + "ms; keeping last frame");
            listener.onMedia(lastReady);
        } else {
            lastReady = null;
            pendingArtworkUri = null;
            pendingArtworkController = null;
            Log.i(TAG, "No playing session in " + elapsed(refreshStart) + "ms; overlay falls back");
            listener.onMedia(null);
        }
    }
    private static long elapsed(long start) { return android.os.SystemClock.elapsedRealtime() - start; }
    private Bitmap loadArt(String uriText) {
        try {
            Uri uri = Uri.parse(uriText);
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            try (InputStream stream = context.getContentResolver().openInputStream(uri)) {
                BitmapFactory.decodeStream(stream, null, bounds);
            }
            if (bounds.outWidth < 16 || bounds.outHeight < 16) return null;
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = 1;
            int largest = Math.max(bounds.outWidth, bounds.outHeight);
            while (largest / options.inSampleSize > 1024) options.inSampleSize *= 2;
            try (InputStream stream = context.getContentResolver().openInputStream(uri)) {
                return BitmapFactory.decodeStream(stream, null, options);
            }
        } catch (Exception error) { return null; }
    }
    private static String text(CharSequence value) { return value == null ? "" : value.toString(); }
}
