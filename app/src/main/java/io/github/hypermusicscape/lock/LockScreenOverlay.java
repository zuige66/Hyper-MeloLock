package io.github.hypermusicscape.lock;

import android.app.KeyguardManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.database.ContentObserver;
import android.graphics.Color;
import android.graphics.RenderEffect;
import android.graphics.Shader;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.text.TextUtils;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextClock;
import android.widget.TextView;
import java.util.IdentityHashMap;
import java.util.Map;

/** Exact-build OS3 adapter. The player card is module-owned because OS3 owns its native header. */
final class LockScreenOverlay {
    private static final String TAG = "HyperMusicScapeLock";
    private static final String CLOCK = "com.android.keyguard.clock.KeyguardClockContainer";
    private static final String NOTIFICATIONS = "com.android.systemui.statusbar.notification.stack.NotificationStackScrollLayout";
    private final ViewGroup root;
    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final MediaSource media;
    private final ContentObserver switchObserver;
    private final BroadcastReceiver screenReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context ignored, Intent intent) {
            if (Intent.ACTION_SCREEN_OFF.equals(intent.getAction()) || Intent.ACTION_USER_PRESENT.equals(intent.getAction())) restore();
            else if (Intent.ACTION_SCREEN_ON.equals(intent.getAction())) updateSwitch();
        }
    };
    private final IdentityHashMap<View, ViewState> changedViews = new IdentityHashMap<>();
    private final ViewTreeObserver.OnPreDrawListener keyguardGuard;
    private boolean observing;
    private Boolean lastEnabled;
    private boolean missingViewsLogged;
    private boolean expanded;
    private View clock, secondaryClock, nativeBackgroundLayer, nativeForegroundLayer;
    private ViewGroup notifications, windowRoot;
    private FrameLayout foreground;
    private ImageView blur, cover, cardArt;
    private TextView title, artist, previous, playPause, next, elapsed, duration;
    private ProgressBar progress;
    private Button notificationButton;
    private MediaSource.Snapshot shown;
    private ViewTreeObserver guardObserver;
    private final Runnable progressTicker = new Runnable() {
        @Override public void run() {
            updateProgress();
            if (foreground != null && !expanded && shown != null) main.postDelayed(this, 500);
        }
    };

    private static final class ViewState {
        final int visibility, accessibility;
        ViewState(View view) { visibility = view.getVisibility(); accessibility = view.getImportantForAccessibility(); }
    }

    LockScreenOverlay(View root) {
        this.root = (ViewGroup) root;
        context = root.getContext();
        keyguardGuard = () -> {
            if (foreground != null) {
                KeyguardManager keyguard = context.getSystemService(KeyguardManager.class);
                if (keyguard == null || !keyguard.isKeyguardLocked()) restore();
                else if (!expanded) {
                    hide(clock); hide(secondaryClock); hide(nativeBackgroundLayer); hide(nativeForegroundLayer); hide(notifications);
                    foreground.bringToFront();
                    if (notificationButton != null) notificationButton.bringToFront();
                }
            }
            return true;
        };
        media = new MediaSource(context, snapshot -> {
            try { render(snapshot); } catch (Throwable error) { Log.e(TAG, "Overlay render failed", error); restore(); }
        });
        switchObserver = new ContentObserver(main) { @Override public void onChange(boolean ignored) { updateSwitch(); } };
    }

    void start() {
        if (!compatible()) { Log.w(TAG, "SystemUI or plugin version differs; native lockscreen kept"); return; }
        context.getContentResolver().registerContentObserver(Config.URI, false, switchObserver);
        IntentFilter events = new IntentFilter();
        events.addAction(Intent.ACTION_SCREEN_OFF); events.addAction(Intent.ACTION_SCREEN_ON); events.addAction(Intent.ACTION_USER_PRESENT);
        context.registerReceiver(screenReceiver, events, Context.RECEIVER_NOT_EXPORTED);
        observing = true;
        Log.i(TAG, "Keyguard root compatible; observing module switch");
        updateSwitch();
    }

    void destroy() {
        if (observing) { context.getContentResolver().unregisterContentObserver(switchObserver); context.unregisterReceiver(screenReceiver); observing = false; }
        media.close(); restore();
    }

    private void updateSwitch() {
        boolean enabled = Config.enabled(context);
        if (lastEnabled == null || lastEnabled != enabled) Log.i(TAG, "Module enabled=" + enabled);
        lastEnabled = enabled;
        if (enabled) media.start(); else { media.stop(); restore(); }
    }

    private boolean compatible() {
        try {
            PackageManager packages = context.getPackageManager();
            return "16.03.251211.r".equals(packages.getPackageInfo("com.android.systemui", 0).versionName)
                    && "17.1.4.26.0".equals(packages.getPackageInfo("miui.systemui.plugin", 0).versionName);
        } catch (PackageManager.NameNotFoundException error) { return false; }
    }

    private void render(MediaSource.Snapshot snapshot) {
        PowerManager power = context.getSystemService(PowerManager.class);
        KeyguardManager keyguard = context.getSystemService(KeyguardManager.class);
        if (!Config.enabled(context) || snapshot == null || !root.isAttachedToWindow() || power == null || !power.isInteractive()
                || keyguard == null || !keyguard.isKeyguardLocked()) { restore(); return; }
        if (foreground == null && !create()) return;
        registerGuard();
        shown = snapshot;
        blur.setImageBitmap(snapshot.art); cover.setImageBitmap(snapshot.art); cardArt.setImageBitmap(snapshot.art);
        title.setText(emptyAs(snapshot.title, "未知曲目")); artist.setText(emptyAs(snapshot.artist, "未知艺术家"));
        playPause.setText("Ⅱ");
        updateProgress();
        if (!expanded) showMusic();
    }

    private boolean create() {
        clock = find(root, CLOCK);
        View top = root.getRootView();
        windowRoot = top instanceof ViewGroup ? (ViewGroup) top : null;
        View stack = find(top, NOTIFICATIONS);
        notifications = stack instanceof ViewGroup ? (ViewGroup) stack : null;
        secondaryClock = findById(root, "miui_keyguard_foreground_clock_container");
        nativeBackgroundLayer = findById(root, "keyguard_background_layer");
        nativeForegroundLayer = findById(root, "keyguard_foreground_layer");
        if (clock == null || notifications == null || windowRoot == null) {
            if (!missingViewsLogged) Log.w(TAG, "Native views missing: clock=" + (clock != null) + " notifications=" + (notifications != null));
            missingViewsLogged = true;
            return false;
        }
        foreground = new FrameLayout(context);
        FrameLayout.LayoutParams sceneParams = new FrameLayout.LayoutParams(-1, -1, Gravity.TOP);
        sceneParams.bottomMargin = dp(88);
        windowRoot.addView(foreground, sceneParams);
        blur = new ImageView(context);
        blur.setScaleType(ImageView.ScaleType.CENTER_CROP);
        int blurDp = Config.overlayStyle(context) == 2 ? 0 : (Config.overlayStyle(context) == 1 ? 18 : 28);
        if (blurDp > 0) blur.setRenderEffect(RenderEffect.createBlurEffect(dp(blurDp), dp(blurDp), Shader.TileMode.CLAMP));
        foreground.addView(blur, new FrameLayout.LayoutParams(-1, -1));
        View scrim = new View(context);
        int color = Config.overlayColor(context);
        scrim.setBackground(new ColorDrawable((color & 0x00FFFFFF) | (Config.overlayAlpha(context) << 24)));
        foreground.addView(scrim, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL); content.setGravity(Gravity.CENTER_HORIZONTAL);
        FrameLayout.LayoutParams contentParams = new FrameLayout.LayoutParams(-1, -2, Gravity.TOP); contentParams.topMargin = dp(52);
        foreground.addView(content, contentParams);
        TextClock time = new TextClock(context);
        time.setFormat12Hour("h:mm"); time.setFormat24Hour("HH:mm"); time.setTextColor(Color.WHITE); time.setTextSize(52); time.setGravity(Gravity.CENTER);
        content.addView(time, new LinearLayout.LayoutParams(-1, -2));
        cover = new ImageView(context); cover.setScaleType(ImageView.ScaleType.CENTER_CROP); cover.setClipToOutline(true);
        GradientDrawable coverShape = new GradientDrawable(); coverShape.setColor(Color.WHITE); coverShape.setCornerRadius(dp(Config.cornerRadiusDp(context))); cover.setBackground(coverShape);
        int artSize = Math.min((int) (context.getResources().getDisplayMetrics().widthPixels * .72f), dp(360));
        LinearLayout.LayoutParams artParams = new LinearLayout.LayoutParams(artSize, artSize); artParams.topMargin = dp(24); artParams.bottomMargin = dp(20);
        content.addView(cover, artParams);
        LinearLayout card = buildPlayerCard();
        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(-1, dp(178)); cardParams.leftMargin = dp(12); cardParams.rightMargin = dp(12);
        content.addView(card, cardParams);
        notificationButton = new Button(context); notificationButton.setText("展开通知");
        notificationButton.setOnClickListener(v -> { if (expanded) showMusic(); else showNotifications(); });
        FrameLayout.LayoutParams entry = new FrameLayout.LayoutParams(-2, dp(48), Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL); entry.bottomMargin = dp(125);
        windowRoot.addView(notificationButton, entry);
        Log.i(TAG, "Custom media card overlay created");
        return true;
    }

    private LinearLayout buildPlayerCard() {
        LinearLayout card = new LinearLayout(context); card.setOrientation(LinearLayout.VERTICAL); card.setPadding(dp(16), dp(14), dp(16), dp(10));
        GradientDrawable cardBackground = new GradientDrawable(); cardBackground.setColor(0xF2181818); cardBackground.setCornerRadius(dp(28)); card.setBackground(cardBackground);
        LinearLayout header = new LinearLayout(context); header.setGravity(Gravity.CENTER_VERTICAL); card.addView(header, new LinearLayout.LayoutParams(-1, dp(64)));
        cardArt = new ImageView(context); cardArt.setScaleType(ImageView.ScaleType.CENTER_CROP); cardArt.setClipToOutline(true);
        GradientDrawable artShape = new GradientDrawable(); artShape.setCornerRadius(dp(12)); artShape.setColor(0xFF404040); cardArt.setBackground(artShape);
        header.addView(cardArt, new LinearLayout.LayoutParams(dp(64), dp(64)));
        LinearLayout labels = new LinearLayout(context); labels.setOrientation(LinearLayout.VERTICAL); labels.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(0, -1, 1f); labelParams.leftMargin = dp(14); header.addView(labels, labelParams);
        title = label(Color.WHITE, 22, true); artist = label(0xFF9E9EA3, 15, false);
        labels.addView(title, new LinearLayout.LayoutParams(-1, dp(34))); labels.addView(artist, new LinearLayout.LayoutParams(-1, dp(24)));
        header.addView(icon("⌁", 30), new LinearLayout.LayoutParams(dp(38), -1));
        LinearLayout controls = new LinearLayout(context); controls.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams controlsParams = new LinearLayout.LayoutParams(-1, dp(54)); controlsParams.topMargin = dp(4); card.addView(controls, controlsParams);
        controls.addView(icon("♡", 28), controlParams());
        previous = icon("◀", 29); previous.setOnClickListener(v -> transport(1)); controls.addView(previous, controlParams());
        playPause = icon("Ⅱ", 34); playPause.setOnClickListener(v -> transport(2)); controls.addView(playPause, controlParams());
        next = icon("▶", 29); next.setOnClickListener(v -> transport(3)); controls.addView(next, controlParams());
        controls.addView(icon("▣", 27), controlParams());
        LinearLayout timeline = new LinearLayout(context); timeline.setGravity(Gravity.CENTER_VERTICAL); card.addView(timeline, new LinearLayout.LayoutParams(-1, dp(28)));
        elapsed = label(0xFF9E9EA3, 14, false); elapsed.setGravity(Gravity.CENTER); timeline.addView(elapsed, new LinearLayout.LayoutParams(dp(48), -1));
        progress = new ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal); progress.setMax(1000);
        progress.setProgressTintList(ColorStateList.valueOf(0xFFD7D7DA)); progress.setProgressBackgroundTintList(ColorStateList.valueOf(0xFF444449));
        timeline.addView(progress, new LinearLayout.LayoutParams(0, dp(6), 1f));
        duration = label(0xFF9E9EA3, 14, false); duration.setGravity(Gravity.CENTER); timeline.addView(duration, new LinearLayout.LayoutParams(dp(48), -1));
        return card;
    }

    private void showMusic() {
        expanded = false; hide(clock); hide(secondaryClock); hide(nativeBackgroundLayer); hide(nativeForegroundLayer); hide(notifications);
        foreground.setVisibility(View.VISIBLE); foreground.bringToFront(); notificationButton.bringToFront(); notificationButton.setText("展开通知");
        main.removeCallbacks(progressTicker); main.post(progressTicker);
    }

    private void showNotifications() {
        expanded = true; main.removeCallbacks(progressTicker); restoreChangedViews(); foreground.setVisibility(View.GONE);
        notificationButton.bringToFront(); notificationButton.setText("返回播放器");
    }

    private void transport(int action) {
        if (shown == null) return;
        try {
            if (action == 1) shown.controller.getTransportControls().skipToPrevious();
            else if (action == 2) shown.controller.getTransportControls().pause();
            else shown.controller.getTransportControls().skipToNext();
        } catch (RuntimeException error) { Log.w(TAG, "Media transport action failed", error); }
    }

    private void updateProgress() {
        if (shown == null || progress == null) return;
        long total = shown.durationMs, position = shown.positionMs;
        if (shown.speed > 0f && shown.positionUpdateTimeMs > 0) position += (long) ((android.os.SystemClock.elapsedRealtime() - shown.positionUpdateTimeMs) * shown.speed);
        if (total > 0) { progress.setProgress((int) Math.min(1000, Math.max(0, position * 1000 / total))); elapsed.setText(time(position)); duration.setText(time(total)); }
        else { progress.setProgress(0); elapsed.setText("--:--"); duration.setText("--:--"); }
    }

    private void restore() {
        main.removeCallbacks(progressTicker); unregisterGuard(); shown = null; expanded = false; restoreChangedViews();
        if (foreground != null && foreground.getParent() == windowRoot) windowRoot.removeView(foreground);
        if (notificationButton != null && notificationButton.getParent() == windowRoot) windowRoot.removeView(notificationButton);
        foreground = null; notificationButton = null; clock = null; secondaryClock = null; nativeBackgroundLayer = null; nativeForegroundLayer = null; notifications = null; windowRoot = null;
    }

    private void registerGuard() {
        ViewTreeObserver current = windowRoot.getViewTreeObserver(); if (guardObserver == current) return; unregisterGuard();
        if (current.isAlive()) { current.addOnPreDrawListener(keyguardGuard); guardObserver = current; }
    }
    private void unregisterGuard() { if (guardObserver != null && guardObserver.isAlive()) guardObserver.removeOnPreDrawListener(keyguardGuard); guardObserver = null; }
    private void hide(View view) {
        if (view == null) return;
        if (!changedViews.containsKey(view)) changedViews.put(view, new ViewState(view));
        view.setVisibility(View.INVISIBLE); view.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
    }
    private void restoreChangedViews() {
        for (Map.Entry<View, ViewState> entry : changedViews.entrySet()) { entry.getKey().setVisibility(entry.getValue().visibility); entry.getKey().setImportantForAccessibility(entry.getValue().accessibility); }
        changedViews.clear();
    }
    private TextView label(int color, int sizeSp, boolean bold) {
        TextView text = new TextView(context); text.setTextColor(color); text.setTextSize(sizeSp); text.setSingleLine(true); text.setEllipsize(TextUtils.TruncateAt.END);
        if (bold) text.setTypeface(android.graphics.Typeface.DEFAULT_BOLD); return text;
    }
    private TextView icon(String value, int sizeSp) { TextView icon = new TextView(context); icon.setText(value); icon.setTextColor(Color.WHITE); icon.setTextSize(sizeSp); icon.setGravity(Gravity.CENTER); icon.setClickable(true); return icon; }
    private LinearLayout.LayoutParams controlParams() { return new LinearLayout.LayoutParams(0, -1, 1f); }
    private static String emptyAs(String value, String fallback) { return value == null || value.isEmpty() ? fallback : value; }
    private static String time(long value) { long seconds = Math.max(0, value / 1000); return String.format(java.util.Locale.US, "%d:%02d", seconds / 60, seconds % 60); }
    private static View find(View view, String className) {
        if (view.getClass().getName().equals(className)) return view;
        if (view instanceof ViewGroup) { ViewGroup group = (ViewGroup) view; for (int i = 0; i < group.getChildCount(); i++) { View found = find(group.getChildAt(i), className); if (found != null) return found; } }
        return null;
    }
    private View findById(View parent, String name) { int id = context.getResources().getIdentifier(name, "id", "com.android.systemui"); return id == 0 ? null : parent.findViewById(id); }
    private int dp(float value) { return (int) (value * context.getResources().getDisplayMetrics().density + .5f); }
}
