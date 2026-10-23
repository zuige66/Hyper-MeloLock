package io.github.melolock;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.util.Log;
import java.io.ByteArrayOutputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * SystemUI 侧封面分发：把当前媒体封面<b>按统一规格</b>送到两个消费端——
 * ① 壁纸进程（{@link WallpaperCover}）：沉浸锁屏/解锁动画期间的壁纸纹理；
 * ② 密码界面（bouncer）：{@code keyguard_security_container} 及其壁纸子层
 * {@code keyguard_bouncer_bg} 的背景图。
 *
 * <p>职责边界：模块开关、封面取值、规格化（屏幕 cover-crop + 首页同款遮罩 +
 * <b>模糊烘焙</b>——bouncer 视图会被系统清 RenderEffect，壁纸 GL 侧没有该管线，
 * 两条路径都必须拿到「模糊已在位图里」的成品）都在本进程完成，消费端只做
 * 「收到就画、收到清除就回退原生」。壁纸进程被杀重启丢缓存时自己来要
 * （{@code Config.ACTION_COVER_REQUEST}，见 {@link #install}）。
 *
 * <p>发送去重：按封面<b>像素指纹</b>去重（见 {@link #lastSentKey} 的教训记录），
 * JPEG 编码与发送都在单线程后台，绝不占用 SystemUI 主线程。
 */
public final class WallpaperCoverPush {
    private static final String TAG = "MeloLock";
    private static final String REV = "WCV-25";
    /** 封面最长边：锁屏壁纸 cover-crop 后约 1440x3200，源图 1080 足够清晰且 JPEG 体积可控。 */
    private static final int MAX_EDGE = 1080;

    private static boolean installed = false;
    /**
     * 上次推送的「内容 key」＝封面<b>像素指纹</b>（{@link #artSignature}），不含曲目信息。
     * **不能按 Bitmap 引用去重**：MediaSource 每次回调都重新解码出新实例（引用恒变）。
     * **也不能带 title**（WCV-2 的 key 是 title|artist|尺寸，2026-10-10 实测翻车）：部分音乐 App
     * 把「当前滚动歌词行」塞进 title（《大城小爱》每 3~4 秒滚一行），key 跟着歌词变 → 去重失效 →
     * 同一封面每句歌词重推一次 → 壁纸进程重建 18MB 位图换引用 + GL 重传全屏纹理 →
     * 重传间隙原生壁纸一帧闪现（静止时被我们的层盖住看不见，上下滑露出壁纸层就全看见了）。
     * 封面像素没变就不发——同专辑换曲目也不重推（封面相同本来就不需要重画）。
     */
    private static volatile String lastSentKey;
    /** 最近一次 MediaSource 回调的快照，亮屏补发用（volatile：回调线程不定）。 */
    private static volatile MediaSource.Snapshot lastSnapshot;
    private static MediaSource media;
    private static Context appContext;
    private static final ExecutorService worker = Executors.newSingleThreadExecutor();

    // ── bouncer（密码界面）背景替换（2026-10-16，真机日志定因）────────────────────────
    // 密码界面的背景不走壁纸进程（其纹理已被刷成封面），也不走数据源——真身是 bouncer 容器
    // keyguard_security_container 的 background + 其直接子级 ImageView（keyguard_bouncer_bg，
    // 系统持有原生壁纸图）。三条腿：① 构造钩子（容器 inflate 即捕获，WCV-10 教训：背景是
    // inflate 时一次性设好的，显示时不再重设）；② 全窗口根 BFS 定位（容器在锁屏根子树之外）；
    // ③ 子级 ImageView 的 setImageBitmap/setImageDrawable 实例过滤钩——系统每次设原生壁纸图
    // 都换成封面。所有钩子**必须带 id/实例过滤**，否则等于给 SystemUI 全部 View 装钩（老教训）。
    private static volatile int securityContainerId = -1;
    private static volatile android.graphics.drawable.Drawable bouncerDrawable;
    private static volatile android.graphics.drawable.Drawable originalBouncerBackground;
    private static volatile java.lang.ref.WeakReference<android.view.View> securityContainerRef;
    private static volatile android.graphics.drawable.Drawable lastLoggedReplacement;
    /**
     * WCV-11：锁屏根视图（install 时由 HookEntry 传入）。真机证据：显示密码界面时系统
     * <b>不会重新设背景</b>（WCV-10 的钩子整轮零命中）——背景是 inflate 时一次性设好的，
     * 而那发生在钩子装好之前。所以要主动遍历锁屏树找到容器直接换背景，钩子只作为
     * 「系统真的重设了」的补拦。
     */
    private static volatile java.lang.ref.WeakReference<android.view.View> keyguardRootRef;
    /**
     * WCV-16：bouncer 壁纸的真身——容器直接子级 ImageView（资源名 {@code keyguard_bouncer_bg}，
     * 全屏壁纸图层）。系统显示 bouncer 时把原生壁纸图设进它；我们钩
     * setImageBitmap/setImageDrawable（实例过滤，同 NotificationPanelView 触摸钩
     * 的老模式）把图换成封面。捕获时记住它当时的 drawable 供还原。
     */
    private static volatile java.lang.ref.WeakReference<android.widget.ImageView> bouncerImageRef;
    private static volatile android.graphics.drawable.Drawable originalBouncerImage;
    private static volatile boolean bouncerImageHookLogged = false;
    /** setImageBitmap 拦截用的位图（与 bouncerDrawable 同源，模糊已烘焙进位图）。 */
    private static volatile android.graphics.Bitmap bouncerBitmap;

    /** 幂等安装：由 HookEntry 在 keyguard 根 attach 时调用（不触碰 LockScreenOverlay）。 */
    public static void install(Context context, android.view.View keyguardRoot) {
        if (installed) return;
        installed = true;
        appContext = context.getApplicationContext() != null
                ? context.getApplicationContext() : context;
        Log.i(TAG, "WallpaperCoverPush rev=" + REV + " installed");
        try {
            // 2026-10-12：取消「每次亮屏重推」——亮屏本身不需要重推（壁纸进程缓存还在），
            // 重推就要重建 1440x3200 位图 + 重传 GL 纹理，重传间隙闪一帧原生壁纸＝「亮屏慢一拍」。
            // 改为壁纸进程重启时自己来要（ACTION_COVER_REQUEST）。
            // 必须 EXPORTED：索要方是壁纸进程（独立 UID），NOT_EXPORTED 只允许同 UID/系统发送，
            // 跨进程根本收不到。风险可控——别人发这条只会让壁纸重画一次当前封面。
            appContext.registerReceiver(new BroadcastReceiver() {
                @Override public void onReceive(Context c, Intent intent) {
                    if (!Config.ACTION_COVER_REQUEST.equals(intent.getAction())) return;
                    Log.i(TAG, "WCV cover request received; snapshot="
                            + (lastSnapshot == null ? "none" : "cached"));
                    worker.execute(WallpaperCoverPush::resendCurrent);
                }
            }, new IntentFilter(Config.ACTION_COVER_REQUEST), Context.RECEIVER_EXPORTED);
        } catch (Throwable error) {
            Log.w(TAG, "WCV cover-request receiver unavailable", error);
        }
        try { hookBouncerBackground(); }
        catch (Throwable error) { Log.w(TAG, "WCV bouncer hook unavailable; 密码界面背景维持原生（失败关闭）", error); }
        if (keyguardRoot != null) {
            keyguardRootRef = new java.lang.ref.WeakReference<>(keyguardRoot);
            // 在主线程做一次定位+诊断（keyguard attach 回调本身就在主线程）
            try { locateBouncerContainer(); }
            catch (Throwable error) { Log.w(TAG, "WCV bouncer locate failed", error); }
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

    /**
     * bouncer 背景替换钩子：系统每次给 {@code keyguard_security_container} 设背景（原生模糊
     * 壁纸）时，若我们手上有封面就换成它。afterHook 里直接再调一次 setBackgroundDrawable 换
     * 成我们的 —— 重入的那次 args[0]==我们的 drawable，走 early return 放行原方法，天然无递归。
     * 没封面／模块关 → 不干预（失败关闭）。getIdentifier 解析不到 id（ROM 改名）→ 钩子装上
     * 也永不命中，等效无害。
     */
    private static void hookBouncerBackground() {
        securityContainerId = appContext.getResources().getIdentifier(
                "keyguard_security_container", "id", appContext.getPackageName());
        if (securityContainerId == -1) {
            Log.w(TAG, "WCV bouncer id not found; background replace disabled");
            return;
        }
        de.robv.android.xposed.XposedBridge.hookAllMethods(android.view.View.class,
                "setBackgroundDrawable", new de.robv.android.xposed.XC_MethodHook() {
                    @Override protected void afterHookedMethod(
                            de.robv.android.xposed.XC_MethodHook.MethodHookParam hook) {
                        if (!(hook.thisObject instanceof android.view.View)) return;
                        android.view.View view = (android.view.View) hook.thisObject;
                        if (view.getId() != securityContainerId) return;
                        securityContainerRef = new java.lang.ref.WeakReference<>(view);
                        android.graphics.drawable.Drawable incoming =
                                hook.args.length > 0 && hook.args[0] instanceof android.graphics.drawable.Drawable
                                        ? (android.graphics.drawable.Drawable) hook.args[0] : null;
                        android.graphics.drawable.Drawable ours = bouncerDrawable;
                        if (incoming == ours) return; // 我们自己的重入调用，放行
                        if (ours == null) return;     // 没封面：不干预，原生背景原样生效
                        if (incoming != null) originalBouncerBackground = incoming;
                        view.setBackgroundDrawable(ours);
                        if (lastLoggedReplacement != ours) {
                            lastLoggedReplacement = ours;
                            Log.i(TAG, "WCV bouncer background replaced with cover");
                        }
                    }
                });
        // 新版 Android 里 setBackground 与 setBackgroundDrawable 互为转发，实际入口可能任选其一 —— 两个都钩。
        de.robv.android.xposed.XposedBridge.hookAllMethods(android.view.View.class,
                "setBackground", new de.robv.android.xposed.XC_MethodHook() {
                    @Override protected void afterHookedMethod(
                            de.robv.android.xposed.XC_MethodHook.MethodHookParam hook) {
                        if (!(hook.thisObject instanceof android.view.View)) return;
                        android.view.View view = (android.view.View) hook.thisObject;
                        if (view.getId() != securityContainerId) return;
                        securityContainerRef = new java.lang.ref.WeakReference<>(view);
                        android.graphics.drawable.Drawable incoming =
                                hook.args.length > 0 && hook.args[0] instanceof android.graphics.drawable.Drawable
                                        ? (android.graphics.drawable.Drawable) hook.args[0] : null;
                        android.graphics.drawable.Drawable ours = bouncerDrawable;
                        if (incoming == ours) return;
                        if (ours == null) return;
                        if (incoming != null) originalBouncerBackground = incoming;
                        view.setBackgroundDrawable(ours);
                        if (lastLoggedReplacement != ours) {
                            lastLoggedReplacement = ours;
                            Log.i(TAG, "WCV bouncer background replaced with cover");
                        }
                    }
                });
        // WCV-12：bouncer 是懒加载（install 时遍历锁屏树找不到），inflate 时刻唯一可靠的
        // 捕获点是 View 构造函数（HookEntry 找 keyguard_root_view 同款模式）。构造完 XML 的
        // 背景 id 已就位，背景 drawable 也已设好 —— 捕获 + 记原背景 + 手上有货就当场换。
        de.robv.android.xposed.XposedHelpers.findAndHookConstructor(android.view.View.class,
                Context.class, android.util.AttributeSet.class, new de.robv.android.xposed.XC_MethodHook() {
                    @Override protected void afterHookedMethod(
                            de.robv.android.xposed.XC_MethodHook.MethodHookParam hook) {
                        android.view.View view = (android.view.View) hook.thisObject;
                        if (view.getId() != securityContainerId) return;
                        securityContainerRef = new java.lang.ref.WeakReference<>(view);
                        android.graphics.drawable.Drawable bg = view.getBackground();
                        Log.i(TAG, "WCV bouncer container inflated: " + view.getClass().getName()
                                + " bg=" + (bg == null ? "null" : bg.getClass().getName()));
                        if (bg != null && bg != bouncerDrawable) originalBouncerBackground = bg;
                        android.graphics.drawable.Drawable ours = bouncerDrawable;
                        if (ours != null) view.post(() -> {
                            try { view.setBackgroundDrawable(ours); }
                            catch (Throwable error) { Log.w(TAG, "WCV bouncer ctor apply failed", error); }
                        });
                    }
                });
        Log.i(TAG, "WCV bouncer background hook installed id=" + securityContainerId);
    }

    /** 定位密码容器（必要时重新遍历锁屏树）并把背景换成指定 drawable。只碰主线程。 */
    private static void applyBouncerDrawable(android.graphics.drawable.Drawable drawable) {
        android.view.View container = securityContainerRef != null ? securityContainerRef.get() : null;
        if (container == null) locateBouncerContainer();
        container = securityContainerRef != null ? securityContainerRef.get() : null;
        android.widget.ImageView image = bouncerImageRef != null ? bouncerImageRef.get() : null;
        if (container != null) container.setBackgroundDrawable(drawable); // 钩子对 args[0]==ours 早退放行
        if (image != null) image.setImageDrawable(drawable); // 钩子对 drawable==ours 放行
        if (lastLoggedReplacement != drawable) {
            lastLoggedReplacement = drawable;
            Log.i(TAG, "WCV bouncer bg applied to container=" + (container != null)
                    + " image=" + (image != null ? "yes" : "-"));
        }
    }

    /**
     * 从<b>整个窗口根</b>（{@code getRootView()}，不只是锁屏根——真机证明密码容器不在锁屏根
     * 子树里，WCV-12 的锁屏树遍历两次扑空）遍历找 {@code keyguard_security_container}。
     * BFS，一次性/低频调用，只碰主线程。找到即记录原背景（还原用）并打一行诊断。
     */
    private static void locateBouncerContainer() {
        if (securityContainerId == -1) return;
        android.view.View keyguardRoot = keyguardRootRef != null ? keyguardRootRef.get() : null;
        if (keyguardRoot == null) return;
        android.view.View root = keyguardRoot.getRootView();
        if (root == null) return;
        java.util.ArrayDeque<android.view.View> queue = new java.util.ArrayDeque<>();
        queue.add(root);
        while (!queue.isEmpty()) {
            android.view.View view = queue.poll();
            if (view.getId() == securityContainerId) {
                securityContainerRef = new java.lang.ref.WeakReference<>(view);
                android.graphics.drawable.Drawable bg = view.getBackground();
                Log.i(TAG, "WCV bouncer container found: " + view.getClass().getName()
                        + " bg=" + (bg == null ? "null" : bg.getClass().getName()));
                if (bg != null && bg != bouncerDrawable) originalBouncerBackground = bg;
                captureBouncerImage(view);
                if (bouncerDrawable != null) applyBouncerDrawable(bouncerDrawable);
                return;
            }
            if (view instanceof android.view.ViewGroup) {
                android.view.ViewGroup group = (android.view.ViewGroup) view;
                for (int i = 0; i < group.getChildCount(); i++) queue.add(group.getChildAt(i));
            }
        }
        Log.i(TAG, "WCV bouncer container not found in window tree");
    }

    /**
     * WCV-16：捕获容器直接子级里的第一个 ImageView（壁纸图载体 {@code keyguard_bouncer_bg}），
     * 记住原图供还原，然后装实例过滤钩子拦它后续的 setImageBitmap/setImageDrawable——
     * 系统每次设原生壁纸图都会被换成封面（没封面时放行原生，失败关闭）。
     */
    private static void captureBouncerImage(android.view.View container) {
        try {
            if (!(container instanceof android.view.ViewGroup)) return;
            android.view.ViewGroup group = (android.view.ViewGroup) container;
            for (int i = 0; i < group.getChildCount(); i++) {
                android.view.View child = group.getChildAt(i);
                if (!(child instanceof android.widget.ImageView)) continue;
                android.widget.ImageView image = (android.widget.ImageView) child;
                String name = "id#" + child.getId();
                try { name = appContext.getResources().getResourceEntryName(child.getId()); }
                catch (Throwable ignored) { }
                android.graphics.drawable.Drawable current = image.getDrawable();
                Log.i(TAG, "WCV bouncer image captured: " + child.getClass().getName()
                        + " " + name + " drawable="
                        + (current == null ? "null" : current.getClass().getSimpleName()));
                if (current != null && current != bouncerDrawable) originalBouncerImage = current;
                bouncerImageRef = new java.lang.ref.WeakReference<>(image);
                installBouncerImageHook();
                return;
            }
        } catch (Throwable error) {
            Log.w(TAG, "WCV bouncer image capture failed", error);
        }
    }

    /**
     * 实例过滤钩子：全进程 ImageView 的 setImageBitmap/setImageDrawable 都走这里，但只对
     * 捕获的那一个实例生效（== 比对，零成本放行别人）。系统设原生图 → 记住原图并把参数
     * 换成封面；我们自己的 set（drawable==bouncerDrawable）→ 放行，天然无递归。
     */
    private static void installBouncerImageHook() {
        final de.robv.android.xposed.XC_MethodHook imageHook =
                new de.robv.android.xposed.XC_MethodHook() {
                    @Override protected void beforeHookedMethod(
                            de.robv.android.xposed.XC_MethodHook.MethodHookParam hook) {
                        android.widget.ImageView target =
                                bouncerImageRef != null ? bouncerImageRef.get() : null;
                        if (target == null || hook.thisObject != target) return;
                        boolean bitmapCall = hook.args.length > 0
                                && hook.args[0] instanceof android.graphics.Bitmap;
                        Object incoming = hook.args.length > 0 ? hook.args[0] : null;
                        android.graphics.drawable.Drawable ours = bouncerDrawable;
                        if (ours == null) return; // 没封面：放行原生（失败关闭）
                        if (bitmapCall && incoming == bouncerBitmap) return; // 自己的调用
                        if (!bitmapCall && incoming == ours) return;
                        if (bitmapCall && originalBouncerImage == null) {
                            originalBouncerImage = new android.graphics.drawable.BitmapDrawable(
                                    appContext.getResources(),
                                    (android.graphics.Bitmap) incoming);
                        } else if (!bitmapCall && incoming instanceof android.graphics.drawable.Drawable
                                && originalBouncerImage == null) {
                            originalBouncerImage = (android.graphics.drawable.Drawable) incoming;
                        }
                        // setImageBitmap 的参数类型是 Bitmap，setImageDrawable 传 Drawable —— 按调用分支
                        hook.args[0] = bitmapCall ? bouncerBitmap : ours;
                        if (!bouncerImageHookLogged) {
                            bouncerImageHookLogged = true;
                            Log.i(TAG, "WCV bouncer image replaced with cover ("
                                    + hook.method.getName() + ")");
                        }
                    }
                };
        de.robv.android.xposed.XposedBridge.hookAllMethods(
                android.widget.ImageView.class, "setImageBitmap", imageHook);
        de.robv.android.xposed.XposedBridge.hookAllMethods(
                android.widget.ImageView.class, "setImageDrawable", imageHook);
        Log.i(TAG, "WCV bouncer image hooks armed");
    }

    /** 撤下 bouncer 封面背景：恢复系统原 drawable（此刻 ours==null，钩子不拦截）。 */
    private static void clearBouncer() {
        bouncerDrawable = null;
        bouncerBitmap = null;
        lastLoggedReplacement = null;
        android.view.View container = securityContainerRef != null ? securityContainerRef.get() : null;
        android.graphics.drawable.Drawable original = originalBouncerBackground;
        new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
            try {
                if (container != null && original != null) container.setBackgroundDrawable(original);
                android.widget.ImageView image =
                        bouncerImageRef != null ? bouncerImageRef.get() : null;
                if (image != null) {
                    if (originalBouncerImage != null) {
                        image.setImageDrawable(originalBouncerImage);
                    } else {
                        image.setImageDrawable(null); // 没存到原图：至少撤掉封面，露锁屏层
                    }
                }
            } catch (Throwable error) { Log.w(TAG, "WCV bouncer restore failed", error); }
        });
        originalBouncerBackground = null;
        originalBouncerImage = null;
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

    /** 模块总开关关闭时由 LockScreenOverlay 调：立即撤下壁纸封面，不等下一次媒体回调。 */
    public static void clearCover() {
        worker.execute(() -> {
            try {
                lastSentKey = null;
                lastSnapshot = null;
                send(null);
                Log.i(TAG, "WCV cover cleared (module off)");
            } catch (Throwable error) {
                Log.w(TAG, "WCV clear failed", error);
            }
        });
    }

    /**
     * 封面内容指纹（5 点采样 + 尺寸，与 {@link LockScreenOverlay#artSignature} 同构）：
     * 引用会变（重解码）、title 会变（歌词行），只有像素内容是壁纸真正关心的。
     */
    private static String artSignature(Bitmap art) {
        if (art == null || art.isRecycled()) return "";
        int w = art.getWidth(), h = art.getHeight();
        if (w <= 0 || h <= 0) return "";
        int lastX = w - 1, lastY = h - 1;
        int[] xs = { 0, lastX / 3, (lastX * 2) / 3, lastX, lastX / 2 };
        int[] ys = { 0, lastY / 3, (lastY * 2) / 3, lastY, lastY / 2 };
        StringBuilder text = new StringBuilder(64);
        for (int i = 0; i < xs.length; i++) {
            try { text.append(Integer.toHexString(art.getPixel(xs[i], ys[i]))).append(','); }
            catch (RuntimeException ignore) { text.append("x,"); }
        }
        return text.append(w).append('x').append(h).toString();
    }

    /** 封面内容没变就不发；快照为空＝通知壁纸侧清除。**模块总开关关闭时无条件清除并停推**。 */
    private static void push(MediaSource.Snapshot snapshot) {
        // 开关闸（2026-10-12 补）：此前这个类自带 MediaSource、装上就推，模块关了封面照样
        // 钉在锁屏壁纸上——失败关闭被绕过。读不到配置按「关」处理（宁可不推，不越权）。
        Boolean enabled = Config.enabledOrNull(appContext);
        if (enabled == null || !enabled) {
            if (lastSentKey != null) {
                lastSentKey = null;
                lastSnapshot = null;
                send(null);
                Log.i(TAG, "WCV module off; cover cleared");
            }
            clearBouncer();
            lastSnapshot = null;
            return;
        }
        Bitmap art = snapshot == null ? null : snapshot.art;
        if (art == null || art.isRecycled()) {
            if (lastSentKey != null) {
                lastSentKey = null;
                send(null);
            }
            return;
        }
        String key = artSignature(art);
        if (key.isEmpty() || key.startsWith("x,x,x,x,x,")) {
            // 采样全部失败（如 HARDWARE 位图）：退回曲目级 key —— 宁可歌词滚动时多推，
            // 也不能把签名钉死成常量导致切歌后封面永远不更新。
            key = snapshot.title + "|" + snapshot.artist + "|" + art.getWidth() + "x" + art.getHeight();
        }
        if (key.equals(lastSentKey)) return;
        Bitmap scaled = scaleDown(art);
        // 统一规格底图（屏幕尺寸 cover-crop + 首页同款 scrim；纯色档为不透明纯色），
        // bouncer 与壁纸推送共用——两条路径都没有 View RenderEffect 管线，模糊一律烘焙。
        int style = Config.overlayStyle(appContext);
        int color = Config.overlayColor(appContext);
        int alpha = Config.overlayAlpha(appContext);
        Bitmap base = buildStyledBase(scaled, style, color, alpha);
        armBouncer(base, style); // 自带 try/catch，失败不影响封面推送
        // 壁纸进程 GL 纹理没有 View RenderEffect 管线（解锁动画期间可见的就是它的输出），
        // 必须把首页同款模糊烘焙进位图再推，观感才与锁屏一致。
        Bitmap baked = base;
        if (style != 2) {
            float density = appContext.getResources().getDisplayMetrics().density;
            baked = bakeBlur(base, (style == 1 ? 14 : 28) * density);
        }
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        if (!baked.compress(Bitmap.CompressFormat.JPEG, 85, buffer)) {
            Log.w(TAG, "WCV encode failed");
            return;
        }
        byte[] jpeg = buffer.toByteArray();
        if (baked != base) baked.recycle();
        lastSentKey = key;
        send(jpeg);
        if (scaled != art) scaled.recycle();
        Log.i(TAG, "WCV cover pushed " + jpeg.length + " bytes key=" + key);
    }

    /**
     * WCV-19：统一规格底图——屏幕尺寸 cover-crop 封面 + 首页 {@code applyBackdrop()} 同款遮罩
     * （style 0 原样 / 1 ×0.55），纯色档（style 2）不铺封面铺不透明纯色。bouncer 与壁纸推送共用。
     */
    private static Bitmap buildStyledBase(Bitmap scaled, int style, int color, int alpha) {
        android.util.DisplayMetrics metrics = appContext.getResources().getDisplayMetrics();
        int targetW = Math.max(1, metrics.widthPixels);
        int targetH = Math.max(1, metrics.heightPixels);
        android.graphics.Bitmap out = android.graphics.Bitmap.createBitmap(targetW, targetH,
                android.graphics.Bitmap.Config.ARGB_8888);
        android.graphics.Canvas canvas = new android.graphics.Canvas(out);
        if (style == 2) { // 纯色沉浸：与首页 solidFill 同款
            canvas.drawColor(color | 0xFF000000);
            return out;
        }
        float scale = Math.max(targetW / (float) scaled.getWidth(),
                targetH / (float) scaled.getHeight());
        int drawW = Math.round(scaled.getWidth() * scale);
        int drawH = Math.round(scaled.getHeight() * scale);
        canvas.drawBitmap(scaled, null, new android.graphics.RectF(
                (targetW - drawW) / 2f, (targetH - drawH) / 2f,
                (targetW + drawW) / 2f, (targetH + drawH) / 2f),
                new android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG));
        int scrimAlpha = style == 1 ? Math.round(alpha * 0.55f) : alpha;
        scrimAlpha = Math.max(0, Math.min(255, scrimAlpha));
        canvas.drawColor((color & 0x00FFFFFF) | (scrimAlpha << 24));
        return out;
    }

    /**
     * bouncer 就位——底图直接作内容（屏幕比例，任何 scaleType 都铺满）。
     * 模糊<b>烘焙进位图</b>（与壁纸推送同一套管线），不用视图级 RenderEffect：
     * MIUI 在解锁瞬间会清掉该视图的模糊效果，视图级方案会把清晰底图裸露出来
     * （用户看到「解锁动画期间封面变清晰」，真身即此）；纯色档无效果。
     */
    private static void armBouncer(Bitmap base, int style) {
        try {
            Bitmap effective = base;
            if (style != 2) {
                float density = appContext.getResources().getDisplayMetrics().density;
                effective = bakeBlur(base, (style == 1 ? 14 : 28) * density);
            }
            bouncerBitmap = effective;
            final android.graphics.drawable.Drawable drawable =
                    new android.graphics.drawable.BitmapDrawable(appContext.getResources(), effective);
            bouncerDrawable = drawable;
            new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
                try { applyBouncerDrawable(drawable); }
                catch (Throwable error) { Log.w(TAG, "WCV bouncer apply failed", error); }
            });
        } catch (Throwable error) {
            Log.w(TAG, "WCV bouncer arm failed", error);
        }
    }

    /**
     * 把模糊<b>烘焙</b>进位图（壁纸进程 GL 纹理直接采位图，没有 View RenderEffect 管线——
     * 解锁动画期间用户看到的就是它，必须与首页同观感）。
     * WCV-24：三趟盒式模糊（近似高斯，观感与 View RenderEffect 同族）。历史教训：
     * ① HardwareRenderer+RenderNode（WCV-19~21）产物视觉无模糊，不可信；② BlurMaskFilter
     * （WCV-22/23）只模糊 alpha 通道，对不透明位图是 no-op——两轮都把清晰图当了模糊图。
     * 半分辨率处理（SystemUI 进程内存敏感，峰值减 4 倍）；失败退多级降采样兜底。
     */
    private static Bitmap bakeBlur(Bitmap src, float radiusPx) {
        if (radiusPx <= 0f) return src;
        try {
            int w = src.getWidth(), h = src.getHeight();
            int sw = Math.max(1, w / 2), sh = Math.max(1, h / 2);
            android.graphics.Bitmap small =
                    android.graphics.Bitmap.createScaledBitmap(src, sw, sh, true);
            int radius = Math.max(1, Math.round(radiusPx / 2f));
            int[] pix = new int[sw * sh];
            small.getPixels(pix, 0, sw, 0, 0, sw, sh);
            for (int pass = 0; pass < 3; pass++) {
                pix = boxBlur(pix, sw, sh, radius, true);
                pix = boxBlur(pix, sw, sh, radius, false);
            }
            small.setPixels(pix, 0, sw, 0, 0, sw, sh);
            android.graphics.Bitmap out =
                    android.graphics.Bitmap.createScaledBitmap(small, w, h, true);
            if (out != small) small.recycle();
            Log.i(TAG, "WCV bake via 3xbox r=" + Math.round(radiusPx));
            return out;
        } catch (Throwable error) {
            Log.w(TAG, "WCV box blur bake failed; 多级降采样兜底", error);
        }
        try {
            int w = Math.max(1, src.getWidth() / 16), h = Math.max(1, src.getHeight() / 16);
            android.graphics.Bitmap small =
                    android.graphics.Bitmap.createScaledBitmap(src, w, h, true);
            android.graphics.Bitmap mid =
                    android.graphics.Bitmap.createScaledBitmap(small, w * 4, h * 4, true);
            small.recycle();
            return android.graphics.Bitmap.createScaledBitmap(mid, src.getWidth(), src.getHeight(), true);
        } catch (Throwable error) {
            return src;
        }
    }

    /**
     * 单趟盒式模糊（滑动窗口，O(n)）。{@code horizontal} 决定沿行还是沿列扫；
     * 边界 clamp（窗口越界端重复边缘像素，等价 TileMode.CLAMP 的边缘语义）。
     */
    private static int[] boxBlur(int[] pix, int w, int h, int radius, boolean horizontal) {
        int[] out = new int[pix.length];
        int outer = horizontal ? h : w;
        int inner = horizontal ? w : h;
        int stride = horizontal ? 1 : w;
        int lineStep = horizontal ? w : 1;
        int div = radius * 2 + 1;
        for (int line = 0; line < outer; line++) {
            int base = line * lineStep;
            int rSum = 0, gSum = 0, bSum = 0;
            for (int i = -radius; i <= radius; i++) {
                int p = pix[base + Math.max(0, Math.min(inner - 1, i)) * stride];
                rSum += (p >> 16) & 0xFF;
                gSum += (p >> 8) & 0xFF;
                bSum += p & 0xFF;
            }
            for (int x = 0; x < inner; x++) {
                if (x > 0) {
                    int pa = pix[base + Math.min(inner - 1, x + radius) * stride];
                    int ps = pix[base + Math.max(0, x - radius - 1) * stride];
                    rSum += ((pa >> 16) & 0xFF) - ((ps >> 16) & 0xFF);
                    gSum += ((pa >> 8) & 0xFF) - ((ps >> 8) & 0xFF);
                    bSum += (pa & 0xFF) - (ps & 0xFF);
                }
                out[base + x * stride] = 0xFF000000
                        | ((rSum / div) << 16) | ((gSum / div) << 8) | (bSum / div);
            }
        }
        return out;
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
