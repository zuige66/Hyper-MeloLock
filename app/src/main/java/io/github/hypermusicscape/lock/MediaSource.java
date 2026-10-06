package io.github.hypermusicscape.lock;

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
import java.io.InputStream;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** MediaSession-only data and transport layer; no player package names or ROM classes. */
final class MediaSource {
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
        Snapshot(MediaController controller, Bitmap art, String title, String artist, long actions,
                 long positionMs, long positionUpdateTimeMs, long durationMs, float speed) {
            this.controller = controller; this.art = art; this.title = title;
            this.artist = artist; this.actions = actions; this.positionMs = positionMs;
            this.positionUpdateTimeMs = positionUpdateTimeMs; this.durationMs = durationMs;
            this.speed = speed;
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
    }
    void close() { stop(); artworkWorker.shutdownNow(); }
    private void refresh() {
        final int request = ++generation;
        MediaController selected = null;
        Snapshot snapshot = null;
        String artworkUri = null;
        try {
            List<MediaController> sessions = manager.getActiveSessions(null);
            for (MediaController candidate : sessions) {
                if (!Config.packageAllowed(context, candidate.getPackageName())) continue;
                PlaybackState state = candidate.getPlaybackState();
                MediaMetadata metadata = candidate.getMetadata();
                if (state == null || state.getState() != PlaybackState.STATE_PLAYING || metadata == null) continue;
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
                        metadata.getLong(MediaMetadata.METADATA_KEY_DURATION), state.getPlaybackSpeed());
                break;
            }
        } catch (RuntimeException error) { snapshot = null; selected = null; }
        if (current != selected) {
            if (current != null) current.unregisterCallback(controllerCallback);
            current = selected;
            if (current != null) current.registerCallback(controllerCallback, main);
        }
        if (snapshot != null) {
            lastReady = snapshot;
            pendingArtworkUri = null;
            listener.onMedia(snapshot);
        } else if (selected != null && artworkUri != null) {
            final MediaController controller = selected;
            final String uri = artworkUri;
            if (lastReady != null && lastReady.controller == controller) {
                if (!uri.equals(pendingArtworkUri)) {
                    pendingArtworkUri = uri;
                    main.postDelayed(() -> {
                        if (uri.equals(pendingArtworkUri) && current == controller) {
                            lastReady = null;
                            pendingArtworkUri = null;
                            listener.onMedia(null);
                        }
                    }, 2000);
                }
                listener.onMedia(lastReady);
            } else {
                lastReady = null;
                listener.onMedia(null);
            }
            artworkWorker.execute(() -> {
                Bitmap art = loadArt(uri);
                main.post(() -> {
                    if (request != generation || current != controller) return;
                    if (art == null) {
                        lastReady = null;
                        pendingArtworkUri = null;
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
                            metadata.getLong(MediaMetadata.METADATA_KEY_DURATION), state.getPlaybackSpeed());
                    pendingArtworkUri = null;
                    listener.onMedia(lastReady);
                });
            });
        } else {
            lastReady = null;
            pendingArtworkUri = null;
            listener.onMedia(null);
        }
    }
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
