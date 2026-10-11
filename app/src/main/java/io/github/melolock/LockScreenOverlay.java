package io.github.melolock;

import android.animation.ArgbEvaluator;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.app.KeyguardManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.database.ContentObserver;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.RenderEffect;
import android.graphics.Shader;
import android.graphics.Typeface;
import io.github.hyperisland.R;
import androidx.palette.graphics.Palette;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.TransitionDrawable;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Gravity;
import android.view.MotionEvent;
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
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Exact-build OS3 adapter. The player card is module-owned because OS3 owns its native header. */
final class LockScreenOverlay {
    private static final String TAG = "MeloLock";
    private static final String CLOCK = "com.android.keyguard.clock.KeyguardClockContainer";
    private static final String NOTIFICATIONS = "com.android.systemui.statusbar.notification.stack.NotificationStackScrollLayout";
    /**
     * SCREEN_OFF 之后的静默期：这段时间内 pre-draw 守卫不许 `resume()`。
     *
     * 真机实测 16 次 SCREEN_OFF 里有 **12 次**紧跟一次白跑的 `resume reused scene`（间隔 30~1112ms）。
     * 根因是这条分支里读到的 `interactive()` 那一刻**仍然返回 true**——模块自己在 SCREEN_OFF 行里
     * 打出来的就是 `interactive=true`，所以拿 `interactive()` 当判据根本拦不住它。于是每次息屏都要
     * 白跑一整趟 `showMusic()`（含底部快捷栏全树扫描 + 一行超长日志），全落在「屏幕正在黑」的窗口里。
     * 亮屏本身由 `SCREEN_ON` 广播负责 resume，不需要守卫在这段窗口里抢着做。
     */
    private static final long RESUME_AFTER_SCREEN_OFF_MS = 500;
    /**
     * 解锁时封面的淡出曲线：**压住系统动画的开头，再跟着动画做交叉溶解**。
     *
     * **2026-10-08 定稿（hold/fade 历经 130/240 → 240/130 → 280/90 → 700/90 → 720/140 → 760/160 → 200/460）**。
     *
     * 一次真实解锁的完整时间线（以系统 `keyguardGoingAway` 为 0 点，全部来自真机日志）：
     * ```
     *   +47ms   wms.showSurfaceRobustly …ImageWallpaper     ← 桌面壁纸窗口被拉起
     *  +127ms   wms.showSurfaceRobustly …Launcher           ← 桌面窗口上屏（图标已画好）
     *  +148ms   KeyguardService Starts IRemoteAnimationRunner ← 系统解锁动画**开始**
     *  +685ms   updateKeyguardWallpaperStateAnim onAnimationFinished ← 动画**结束**
     *  +742ms   WallpaperWindowToken{…true} isVisible=false / {…false} isVisible=true ← 换壁纸落地
     * ```
     *
     * **前几版为什么一直闪**：① 一直在猜「桌面窗口何时上屏」，而该对齐的是**系统动画有多长**；
     * ② 更致命的是封面挂在锁屏根**里面**，系统会把整棵锁屏根置 `INVISIBLE + alpha 0`，
     * 我们连绘制都不参与 —— 参数怎么调都无效（见 `liftCoverToWindowRoot`）。
     *
     * **这一版为什么改成早淡、慢淡**：上面两条修掉之后，「压到 760ms 再一次性让开」暴露了新的观感问题 ——
     * 桌面入场动效是 +148~685ms，我们压到 760ms 才让开，等于**把整段桌面动效都挡在盖子后面**，
     * 用户看到的是「音乐界面定住不动将近 0.7 秒，然后桌面已经站好了」＝「观感有点卡」。
     *
     * 所以改成 **200ms 起、460ms 线性淡出、约 660ms 淡完**：淡出窗口（200~660ms）
     * 基本**覆盖系统动画的后 2/3**，用户能一路看到桌面渐显的过程；同时前 200ms 仍是不透明的，
     * 避开动画开头「桌面还没完全合成」的那一段。
     *
     * 代价与调参方向（这是两个可以左右手的旋钮）：
     * - **早淡 / 快淡** → 越看得到桌面动效，但中间态可能混进壁纸（交叉溶解下最多约 25% 混合，是软的、不闪）；
     * - **晚淡 / 慢淡** → 越不可能露出壁纸，但越像「定住一下才进桌面」。
     * 淡出用 `LinearInterpolator`：默认的加减速曲线会在中段掉得比桌面渐显更快，反而更容易露出中间态。
     */
    private static final long UNLOCK_COVER_HOLD_MS = 200;
    private static final long UNLOCK_COVER_FADE_MS = 460;
    /** 快捷栏容器按 id 查找的最大次数（见 applyShortcutRowVisibility）：找不到就彻底放弃，不每帧扫树。 */
    private static final int SHORTCUT_LOOKUP_LIMIT = 5;
    /**
     * 快捷栏被隐藏时，「展开通知 / 返回播放器」入口的兜底纵向位置（占前景高度的比例）。
     * 真机 1080×2400：快捷带中心 y=2255、底部提示文案中心 y=2257 → 约屏高的 94%。
     */
    private static final float ENTRY_FALLBACK_CENTER_RATIO = 0.94f;
    /**
     * SCREEN_OFF 之后等面板**黑透**再"预显"场景的延时（避开熄屏动画）。
     *
     * SCREEN_OFF 广播送达时面板还在跑熄屏动画，那会儿改可见性会跟动画抢 —— 这正是之前被迫给
     * 守卫加 `interactive()` 闸的原因。面板黑透之后再动，既没人看得见，也不会撞上动画。
     */
    private static final long SCREEN_OFF_PRESHOW_DELAY_MS = 220;

    /**
     * 熄屏"暗期"里把场景的可见性**提前摆好**（只摆可见性，不跑动画、不启进度 ticker）。
     *
     * 动机：`suspend()` 之后场景是 GONE，而 `resume()` 只能等 `SCREEN_ON` 广播（那时面板已经亮了）
     * 或亮屏后第一帧 pre-draw —— 中间露出的就是原生锁屏。用户反馈的「息屏快速亮屏又出现原生」
     * 正是这一段。在面板黑透之后先把可见性摆回去，亮屏**第一帧**就是我们的界面。
     *
     * 只摆可见性、不调 `resume()`：`resume()` 会走 `showMusic()`，而它会把进度 ticker 启动起来
     * —— 熄屏期间每 500ms 唤醒一次，纯耗电。入场动画与 ticker 照样留给亮屏时的 `SCREEN_ON` 分支。
     */
    private final Runnable preShowForWake = this::runPreShowForWake;
    private final ViewGroup root;
    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final MediaSource media;
    private final ContentObserver switchObserver;
    /**
     * 无 root 的重启通道：配置端发现 `su` 不可用时，会发一条显式广播过来，
     * 由已经跑在 SystemUI 进程里的模块自己 kill 自己完成一次作用域重启。
     * 与 `am crash com.android.systemui` 效果等价，但不需要 root 也不需要 adb。
     */
    private final BroadcastReceiver restartReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context ignored, Intent intent) {
            if (!Config.ACTION_RESTART_SYSTEMUI.equals(intent.getAction())) return;
            Log.i(TAG, "Restart requested via broadcast; killing SystemUI for a clean reload");
            android.os.Process.killProcess(android.os.Process.myPid());
        }
    };
    /**
     * 配置变更后的防抖重建：与 {@link #resume()} 的指纹比对走同一条路径——
     * 三元素变了撤掉重建（下一帧 render，避开 pre-draw 内改视图树），
     * 背景三态/圆角变了 applyBackdrop() 就地更新。
     *
     * 为什么必须在这里重建：熄屏唤醒走 applyPreShow()「只摆可见性」，**不经过 resume() 的
     * 指纹比对**——suspended 场景直接复活，旧外观又端了出来。这是「改了配置必须关开模块
     * 才生效」的根因；observer 收到通知后立即撤场，锁屏重现时 create() 读到的就是新配置。
     */
    private final Runnable applyPendingConfig = new Runnable() {
        @Override public void run() {
            updateSwitch();
            // 诊断：确认「配置端写盘 → SystemUI 收到 → 与当前场景不一致」链路通不通。
            // 只在配置变更时打一次（防抖合并后），不会刷屏。
            Log.i(TAG, "Config notify: fg=" + (foreground != null) + " suspended=" + suspended
                    + " hasElementSig=" + (elementSignature != null) + " hasBackdropSig=" + (backdropSignature != null));
            if (foreground == null || elementSignature == null || backdropSignature == null) return;
            boolean elementsChanged = !elementSignature.equals(currentElementSignature());
            boolean backdropChanged = !backdropSignature.equals(currentBackdropSignature());
            if (!elementsChanged && !backdropChanged) return;
            Log.i(TAG, "Config changed while scene alive; applying now (elements=" + elementsChanged
                    + " backdrop=" + backdropChanged + ")");
            if (elementsChanged) {
                MediaSource.Snapshot keep = shown;
                restore("config-changed");
                if (keep != null) {
                    // 下一帧再重建：此刻可能在视图回调里，直接 addView 会与 layout 互相打断。
                    main.post(() -> {
                        if (foreground != null || suspended || !lockscreenCycle || !keyguardLocked()) return;
                        render(keep);
                    });
                }
            } else {
                applyBackdrop();
            }
        }
    };
    private final BroadcastReceiver screenReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context ignored, Intent intent) {
            if (Intent.ACTION_SCREEN_OFF.equals(intent.getAction())) {
                lockscreenCycle = true;
                screenOffAtMs = android.os.SystemClock.elapsedRealtime();
                // Keep the already-rendered scene in SystemUI memory.  Rebuilding it
                // only after SCREEN_ON is what caused the one-second stock-screen flash.
                if (debugLog) Log.i(TAG, "SCREEN_OFF kept=" + state());
                main.removeCallbacks(progressTicker);
                // 暗期预显主触发已改到「系统唤醒信号」在面板还黑着时同步摆可见性（见 onSystemWakingUp）；
                // 这里只留兜底：万一唤醒信号没来（极少见），仍按原延时摆回，亮屏第一帧还是我们的。
                // 注意：不要再 removeCallbacks(preShowForWake) —— 快速开屏时 SCREEN_OFF 晚于唤醒信号到达，
                // 那次 remove 会把提前排好的预显撤销掉，反而制造闪原生壁纸（实测 43.666 信号被 43.731 撤掉）。
                main.postDelayed(preShowForWake, SCREEN_OFF_PRESHOW_DELAY_MS);
                // 主动让媒体层回调一次：此时还没有场景的话，render() 会在屏幕看不见时先建好。
                if (Config.enabled(context)) media.start();
            } else if (Intent.ACTION_USER_PRESENT.equals(intent.getAction())) {
                // USER_PRESENT is the reliable boundary between keyguard and the
                // unlocked notification shade.  On this ROM isKeyguardLocked()
                // can still briefly report true while the home shade is opening.
                lockscreenCycle = false;
                Log.i(TAG, "USER_PRESENT " + state()
                        + (suspended && suspendStartedAtMs > 0 ? " (+" + (android.os.SystemClock.elapsedRealtime() - suspendStartedAtMs) + "ms after suspend)" : ""));
                // 解锁完成。正常路径是 pre-draw 守卫在解锁动画一开始就 suspend() 淡出；
                // 这里只兜底补一次，仍然保留实例，不再销毁场景。
                if (!suspended && foreground != null) suspend("user-present");
            } else if (Intent.ACTION_SCREEN_ON.equals(intent.getAction())) {
                long offMs = screenOffAtMs > 0 ? android.os.SystemClock.elapsedRealtime() - screenOffAtMs : -1;
                // offForMs 能和用户的按键节奏对账：正常点一次是几百毫秒；「快按两下没亮起来」
                // 的那次，这里会看到屏幕其实是被正常点亮的，而真正丢失的是第一次按键本身。
                Log.i(TAG, "SCREEN_ON offFor=" + offMs + "ms " + state());
                logWallpaperLikeViews();
                screenOffAtMs = 0;
                if (suspended && lockscreenCycle && keyguardLocked()) resume();
                else if (suspended) {
                    // 暗期预显可能已经跑过，但这次亮屏看到的是桌面（keyguard 没锁）→ 把场景收回去，
                    // 否则它会一直盖在桌面上，直到下一次媒体回调才被 render() 撤掉。
                    restore("wake-unlocked");
                } else if (shown != null && foreground != null && !expanded) showMusic();
                updateSwitch();
            }
        }
    };
    private final IdentityHashMap<View, ViewState> changedViews = new IdentityHashMap<>();
    /** 内置圆体数字字体的缓存：roundness → Typeface，整个 SystemUI 进程只解一次。 */
    private static final Map<Integer, Typeface> roundedTypefaces = new HashMap<>();
    private final ViewTreeObserver.OnPreDrawListener keyguardGuard;
    private boolean observing;
    private Boolean lastEnabled;
    private boolean missingViewsLogged;
    /** 诊断用：解锁撤层只记一次，避免 pre-draw 每帧刷屏。 */
    private boolean unlockRestoreLogged;
    /** 诊断用：render 早退原因去重，只在原因变化时打日志。 */
    private String lastSkipReason;
    /** 诊断用：本次 suspend 的起点时间戳。 */
    private long suspendStartedAtMs;
    /** 最近一次 `keyguardGoingAway` 的时刻，用来在摘层时打「解锁后多久才撤」，和用户观感对账。 */
    private long unlockSignalledAtMs;
    /** 最近一次 SCREEN_OFF 的时刻，用来在亮屏时打出「熄屏了多久」，和用户操作对账。 */
    private long screenOffAtMs;
    private int createAttempts;
    /**
     * 建场景那一瞬的外观配置指纹，**分成两份**：
     * `elementSignature` 是三元素参数（尺寸/间距/字号…），变了要整场重建；
     * `backdropSignature` 是背景样式/遮罩/圆角，变了只需 [applyBackdrop] 就地更新。
     *
     * 场景实例现在跨锁屏周期复用（suspend / resume），`create()` 一辈子只跑一次，
     * 而它又是唯一读取外观配置的地方 —— 于是「外观改了没反应」成了必然结果。
     * 复用之前先比一次指纹，把「灭屏再亮屏一次生效」这条约定重新兑现。
     */
    private String elementSignature, backdropSignature;
    /** findBottomCornerIcon 的遍历预算：SystemUI 的窗口树很深，不容许每次 resume 全扫一遍。 */
    private int scanBudget;
    /** 「展开通知」入口是否已经和系统底部快捷栏对齐过（对齐成功后不再重复扫树）。 */
    private boolean entryAligned;
    /** 对齐尝试次数：只在头几次输出诊断清单，免得每次 resume 都刷一行。 */
    private int entryAlignAttempts;
    /**
     * 解锁后场景是否处于「已淡出但保留」状态。
     *
     * true 时 pre-draw 守卫完全不介入（不隐藏原生层、不 bringToFront、不撤销），
     * 媒体回调也不接管界面，只等锁屏重新出现时 resume() 直接复用同一批视图。
     */
    private boolean suspended;
    /**
     * 本次锁屏周期里是否已经收到过系统的解锁信号（`KeyguardViewMediator#keyguardGoingAway`）。
     *
     * 收到它就意味着「锁屏退场动画真正开始了」，比 pre-draw 守卫早 277~491ms（实测）。
     * 在 create()/resume() 之后复位；没有信号时一切回退到旧行为。
     */
    private boolean unlockSignalled;
    /** True only from screen-off until the user has completed an unlock. */
    private boolean lockscreenCycle = true;
    private boolean expanded;
    private View clock, secondaryClock, nativeBackgroundLayer, nativeForegroundLayer;
    /**
     * 左侧通知栏的宿主视图（`NotificationPanelView#notification_panel`）。
     *
     * 真机视图树显示：`legacy_window_root` 的子节点顺序是
     * `notification_panel` → … → `control_center_container` → `keyguard_root_view`（我们的层挂这里）。
     * 画在后面的盖在前面，所以自绘层**盖住左侧通知栏**（重叠），却在右侧控制中心**之下**（所以右侧下拉正常）。
     *
     * 信号用它的可见性：收起时 INVISIBLE(4)，下拉展开时 VISIBLE(0)——由系统自己维护，
     * 不会像通知栈那样被亮屏/锁屏重排误改（那条错路踩过，见 README）。
     */
    private View leftShadePanel;
    /** 左侧下拉手势拦截是否已安装（只在通知面板的触摸入口上装一次）。 */
    private boolean shadeBlockInstalled;
    /** 诊断用：每次成功吃掉一次下拉手势打一行。 */
    private int shadeBlockedCount;
    private ViewGroup notifications, windowRoot;
    /** 展开通知时测到的共享位移，返回播放器时复用它，保证两个方向对称。 */
    private int lastSwapOffset;
    private FrameLayout background;
    private FrameLayout foreground;
    private LinearLayout content;
    private LinearLayout playerCard;
    private ImageView baseBlur, cover, cardArt;
    /** 顶部日期行（公历+周几+农历）与自定义签名行；两行共存、各自独立开关，均在时钟上方。 */
    private TextView dateLine, signatureLine;
    /** 播放器卡片可回填的配色件：底色/小封面底 + 卡内三枚非控制图标。 */
    private GradientDrawable cardBackgroundDrawable, artBackgroundDrawable;
    /** 非控制图标三枚：矢量模式是 ImageView，旧字符模式是 TextView，统一按 View 持有（配色联动时按实际类型分发）。 */
    private View iconWave, iconHeart, iconQueue;
    /** 卡片底色配置（create 时读一次）：0＝跟随封面，其余为手选 ARGB。 */
    private int cardBgConfig;
    /** 「跟随封面」缓存（主线程读写）：按曲目 key（title|artist|尺寸）缓存取色结果——
     * 该设备的媒体源每 2 秒交一个新 Bitmap 实例，按引用缓存会每帧重跑 Palette。 */
    private String autoMediaKey;
    /** 取色结果：专辑主色原始 RGB（0＝未取到/取色失败）。各「跟随封面」档按各自取色风格从这里推导。 */
    private int autoSwatch;
    /** 缓存该结果时的主色来源档位；换档位必须重取（见 maybeExtractCardPalette 的缓存 key 注释）。 */
    private int autoSwatchPick;   // 主色来源档位快照（0=vibrant 1=dominant 2=family），与 swatchPickMode 配套做缓存命中
    // ── 切歌动效：封面交叉淡化 + 文字换字淡入淡出 + 跟随配色渐变 ──
    // 三者都是「封面/文字真的变了」才跑一次的短过渡，不碰「每 2 秒快照引用去重」的性能红线。
    /** 封面交叉淡化时长：一次性 TransitionDrawable，成本远低于周期性模糊层重绘。 */
    private static final long ART_CROSSFADE_MS = 260;
    /** 文字换字：旧字淡出 / 新字淡入时长。 */
    private static final long TEXT_FADE_OUT_MS = 130, TEXT_FADE_IN_MS = 190;
    /** 文字换字兜底：ViewPropertyAnimator 的回调不可独靠（红线经验），postDelayed 强制落终态。 */
    private static final long TEXT_SWAP_FALLBACK_MS = 500;
    /** 「跟随封面」配色主色渐变时长。 */
    private static final long SWATCH_FADE_MS = 320;
    /** 弹跳类动效（播放/暂停切换、封面弹入）共用：过冲插值器，落位时轻微回弹一下。 */
    private static final android.view.animation.OvershootInterpolator POP_INTERPOLATOR =
            new android.view.animation.OvershootInterpolator(2.2f);
    /** 配色跟随的展示值：渐变期间从旧主色向 autoSwatch 过渡，各跟随档按它推导；静止时恒等于 autoSwatch。 */
    private int displayedSwatch;
    /** 正在跑的主色渐变；新渐变重起 / 整场销毁时取消。 */
    private ValueAnimator swatchAnimator;
    /** 取色专用单线程（Palette 数百 ms，绝不占主线程）；daemon 防泄漏。 */
    private final java.util.concurrent.ExecutorService paletteExecutor =
            java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "MeloLockPalette"); t.setDaemon(true); return t;
            });
    /** 「展开通知」入口胶囊背景（可回填配色）。 */
    private GradientDrawable entryBackgroundDrawable;
    /** 各文字/入口颜色是否处于「跟随封面」档（create 时读定；配置变化走整场重建）。 */
    private boolean clockColorFollow, dateColorFollow, signColorFollow, entryColorFollow, entryBgFollow;
    /** 各「跟随封面」档的取色风格（create 时读定）：false＝低饱和磨砂（M3E），true＝鲜艳原色直出。 */
    private boolean cardBgPick, clockPick, datePick, signPick, entryColorPick, entryBgPick;
    /** 主色来源（全局，create 时读定）：false＝最鲜艳优先（鲜艳桶优先），true＝占比最高直取。 */
    private int swatchPickMode;   // 0=vibrant 1=dominant 2=family（色族占比）
    /** 切歌柔和过渡总开关（song_fade，默认开）：关＝封面/文字/配色全部瞬时切换（旧版行为）。 */
    private boolean songFadeEnabled;
    /** 组件动效开关（外观页「动效」分组，create 时读定）：按钮反馈 / 进度条平滑 / 封面弹入。 */
    private boolean fxButtonFeedback, fxSmoothProgress, fxCoverPop, fxPlayerCardPop;
    /** 播放器卡片弹入动画窗口标记：弹入进行中，showMusic() 的幂等 scale 复位必须让路（否则 0.90 起点同帧被打平）。 */
    private boolean cardPopRunning;
    /** 播放键图标风格（player_vector_icons，默认开）：开＝矢量圆润图标，关＝旧版字符播放键。create 时读定。 */
    private boolean fxVectorIcons;
    /** 调试日志开关（debug_log，默认关）：开＝周期性细节日志照打，关＝门控（见 MediaSource.verboseLog）。 */
    private boolean debugLog;
    /**
     * 隐藏锁屏底部快捷栏（手电筒 / 相机，hide_shortcuts，默认关）。
     * 开＝整排容器置 GONE（图标与触摸响应一起没），入口按钮改走几何兜底对齐。
     */
    private boolean hideShortcuts;
    /** 快捷栏容器缓存（见 applyShortcutRowVisibility）：避免每个 pre-draw 周期都全树按 id 找。 */
    private View cachedShortcutRow;
    private int shortcutRowLookups;
    /** 调试用：锁屏状态下永不息屏（stay_awake，默认关）。 */
    private boolean stayAwake;
    /**
     * 通知卡跟随封面（notify_card_tint，默认开）。**事件驱动，零周期扫描**：
     * 通知页整页底色来自通知栈后面的 `scrim_notifications`（深色主题=黑、浅色=白，
     * 卡片本身近透明、色是 scrim 透出来的），所以主攻 scrim 一次染色；
     * media_bg / 通知卡背景只做一次浅扫挂 SRC_ATOP 滤镜——ImageView 的滤镜挂在
     * 视图属性上，系统每 2 秒重设位图也不会丢，无需重扫。
     */
    private boolean notifyCardTint;
    private View notificationsScrim;
    private android.graphics.drawable.Drawable savedScrimBackground;
    private boolean tintApplied;
    /** 进度条平滑推进的进行中动画；新采样先取消再起新的，防两只动画打架。 */
    private ObjectAnimator progressAnimator;
    /** 上一次铺的播放/暂停图标；变了才弹跳（每 2 秒快照去重，与封面同思路）。 */
    private String shownPlayGlyph;
    /** 播放/暂停图标的**挂起**变化：600ms 内没翻回才真正提交（见 applySnapshot 的注释）。 */
    private String pendingGlyph;
    /** 播放/暂停图标的延迟提交：换字 + 弹跳（幅度与按压反馈一致 0.85→1.0）。 */
    private final Runnable glyphCommit = new Runnable() {
        @Override public void run() {
            if (playPause == null || pendingGlyph == null || pendingGlyph.equals(shownPlayGlyph)) return;
            shownPlayGlyph = pendingGlyph;
            // 旧字符模式是 TextView（pendingGlyph 是 "play"/"pause" 语义值，映射回「▶」「Ⅱ」）；
            // 矢量模式是 ImageView，不能 setImageResource（宿主解析不了模块 id，见 icon() 注释）。
            if (playPause instanceof TextView) {
                ((TextView) playPause).setText("pause".equals(pendingGlyph) ? "Ⅱ" : "▶");
            } else {
                Drawable glyphDrawable = moduleDrawable("pause".equals(pendingGlyph) ? R.drawable.ic_media_pause_block : R.drawable.ic_play_rounded);
                if (glyphDrawable != null) ((ImageView) playPause).setImageDrawable(glyphDrawable);
            }
            if (fxButtonFeedback) {
                Log.i(TAG, "Fx: play glyph -> " + pendingGlyph);
                playPause.animate().cancel();
                playPause.setScaleX(0.85f); playPause.setScaleY(0.85f);
                playPause.animate().scaleX(1f).scaleY(1f).setDuration(160).start();
                // 兜底：万一被别的路径 cancel，缩放不能永远停在 0.85。
                playPause.postDelayed(() -> {
                    if (playPause.getScaleX() != 1f) { playPause.animate().cancel(); playPause.setScaleX(1f); playPause.setScaleY(1f); }
                }, 400);
            }
        }
    };
    /** 按压反馈通路诊断：整个场景只打第一次按压的日志。 */
    private boolean pressFeedbackProbed;
    /** 签名正文（create 时读一次）；进 elementSignature，改签名会触发整场重建。 */
    private String signatureText = "";
    /** 日期行文本对应的天（epoch day）；跨天时重算。 */
    private int dateTextDay = -1;
    /** 背景三层：模糊封面 / 纯色底（style==2 才可见）/ 遮罩。提为字段以便 resume() 就地更新。 */
    private View solidFill, baseScrim;
    private TextView title, artist, elapsed, duration;
    /** 传输控制三键：player_vector_icons 开＝矢量圆润 ImageView，关＝旧版字符 TextView；统一按 View 持有。 */
    private View previous, playPause, next;
    private ProgressBar progress;
    private TextClock immersiveClock;
    private Button notificationButton;
    private MediaSource.Snapshot shown;
    /** 最近一次铺进三个 ImageView 的封面；比对用，避免每 2 秒重复触发全屏模糊层重绘。 */
    private android.graphics.Bitmap shownArtwork;
    /**
     * 最近一次铺的封面**内容指纹**。
     *
     * 为什么不能只按 Bitmap 引用去重（2026-10-09 真机查出来的）：有的播放器每次回调都
     * 重新解码出**新的 Bitmap 实例**（同一张专辑图），引用比对永远不等 —— 于是交叉淡化、
     * 封面弹入、后台取色每一帧全跑一遍：用户看到的「封面弹入没生效」（其实是一直在弹，
     * 没有「切歌那一刻」）以及全屏模糊层被反复重设都来源于此。改为抽样像素做内容指纹：
     * 同一张图重新解码，指纹相同。
     */
    private String shownArtSignature;
    private ViewTreeObserver guardObserver;
    private boolean playerSceneVisible;
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
        // 新的场景实例＝新的锁屏视图树：快捷栏缓存必须清掉，否则会一直对着上一轮的旧 View 改可见性。
        cachedShortcutRow = null;
        shortcutRowLookups = 0;
        keyguardGuard = () -> {
            if (suspended) {
                // 解锁后场景已淡出并置 GONE，守卫必须完全放手：继续隐藏原生层会破坏，
                // 继续 bringToFront 会把不可见场景提到桌面之上。锁屏重新出现才 resume。
                //
                // 唯一例外是**原生锁屏时钟层**：我们仍持有场景实例（没 restore），而系统在
                // 「桌面下拉通知栏」时会把锁屏时钟重新显示出来 —— 用户看到的就是「原屏保的
                // 时间」漏到桌面上。所以时钟层继续按住；**壁纸层绝不能碰**（解锁动画要靠它，
                // 隐藏会露黑底，这条踩过）。
                hideNativeClockLayers(root);
                // `interactive()` 这条不能少：熄屏动画期间窗口还在出帧，此时此刻 resume 等于
                // 在「屏幕正要黑」的窗口里把两层重新置可见 + bringToFront（整棵窗口根 relayout）。
                // 真机日志里出现过 SCREEN_OFF 后 49ms 就 `resume reused scene interactive=false`，
                // 而用户反馈「亮屏时快按两下开机键，息屏后没再亮起来」——第二次按键正好落在这个
                // 窗口里（系统那侧根本没有 SCREEN_ON 广播，不是模块吞键）。亮屏秒显靠的是
                // SCREEN_ON 广播与亮屏后第一帧 pre-draw，两者都在屏幕亮起之后，所以这里直接跳过。
                // 除了 interactive()，还要过一道**时间闸**：SCREEN_OFF 刚发生的那一小段里
                // interactive() 仍然返回 true（见 RESUME_AFTER_SCREEN_OFF_MS 注释），光靠它拦不住。
                if (lockscreenCycle && keyguardLocked() && interactive()
                        && android.os.SystemClock.elapsedRealtime() - screenOffAtMs > RESUME_AFTER_SCREEN_OFF_MS) resume();
                return true;
            }
            if (foreground != null) {
                if (!lockscreenCycle || !keyguardLocked()) {
                    if (!unlockRestoreLogged) {
                        unlockRestoreLogged = true;
                        Log.i(TAG, "Pre-draw: keyguard unlocked while overlay alive " + state());
                    }
                    suspend("predraw-keyguard-unlocked");
                } else {
                    hideNativeWallpaperLayers(root);
                    hideNativeClockLayers(root);
                    applyShortcutRowVisibility();
                    // 通知栈的显隐完全由 expanded 决定：展开时它自己在淡入（由 show() 保证可见），
                    // 收起时立即隐藏——不再做淡出（淡出层盖在时钟上，会让时间看起来闪一下）。
                    if (expanded) show(notifications); else hide(notifications);
                    ensureOnTop(foreground);
                    ensureOnTop(notificationButton);
                }
            }
            return true;
        };
        media = new MediaSource(context, snapshot -> {
            try { render(snapshot); } catch (Throwable error) { Log.e(TAG, "Overlay render failed", error); restore("render-error"); }
        });
        switchObserver = new ContentObserver(main) {
            @Override public void onChange(boolean ignored) {
                // App 端每次写盘都会 notifyChange；一次应用内操作（重置一组、签名落盘）可能连写
                // 多个键，120ms 防抖合并成一次重建。改完即生效，不再依赖灭屏亮屏或关开模块。
                main.removeCallbacks(applyPendingConfig);
                main.postDelayed(applyPendingConfig, 120);
            }
        };
    }

    void start() {
        if (!compatible()) { Log.w(TAG, "SystemUI or plugin version differs; native lockscreen kept"); return; }
        context.getContentResolver().registerContentObserver(Config.URI, false, switchObserver);
        IntentFilter events = new IntentFilter();
        events.addAction(Intent.ACTION_SCREEN_OFF); events.addAction(Intent.ACTION_SCREEN_ON); events.addAction(Intent.ACTION_USER_PRESENT);
        context.registerReceiver(screenReceiver, events, Context.RECEIVER_NOT_EXPORTED);
        // 显式广播（setPackage 到 SystemUI）才能穿过 RECEIVER_NOT_EXPORTED 送达这里。
        context.registerReceiver(restartReceiver, new IntentFilter(Config.ACTION_RESTART_SYSTEMUI),
                Context.RECEIVER_EXPORTED);
        observing = true;
        Log.i(TAG, "Keyguard root compatible; observing module switch");
        updateSwitch();
    }

    void destroy() {
        Log.i(TAG, "Overlay destroy " + state());
        if (observing) {
            context.getContentResolver().unregisterContentObserver(switchObserver);
            context.unregisterReceiver(screenReceiver);
            try { context.unregisterReceiver(restartReceiver); } catch (Throwable ignored) { }
            observing = false;
        }
        media.close(); restore("destroy");
    }

    private void updateSwitch() {
        Boolean read = Config.enabledOrNull(context);
        if (read == null) {
            // Provider 偶发查不到 ≠ 用户关了模块。旧代码把两者混为一谈，导致「改完设置
            // 锁屏上东西全没了 / 改完没反应」，且时好时坏（见 Config.enabledOrNull 注释）。
            Log.w(TAG, "Config read failed; keeping previous switch state=" + lastEnabled);
            return;
        }
        boolean enabled = read;
        if (lastEnabled == null || lastEnabled != enabled) Log.i(TAG, "Module enabled=" + enabled + " " + state());
        lastEnabled = enabled;
        if (enabled) media.start(); else { Log.i(TAG, "Module switched off; restoring " + state()); media.stop(); restore("switch-off"); }
    }

    private boolean compatible() {
        try {
            PackageManager packages = context.getPackageManager();
            return "16.03.251211.r".equals(packages.getPackageInfo("com.android.systemui", 0).versionName)
                    && "17.1.4.26.0".equals(packages.getPackageInfo("miui.systemui.plugin", 0).versionName);
        } catch (PackageManager.NameNotFoundException error) { return false; }
    }

    private void render(MediaSource.Snapshot snapshot) {
        Boolean enabledRead = Config.enabledOrNull(context);
        // 读不到（Provider 偶发失败）时什么都不做，保持现有画面；不能像旧代码那样当成
        // 「模块已关闭」把场景撤掉——那正是「配配置改完锁屏上没东西 / 时好时坏」的来源。
        if (enabledRead == null) { skip("config-unreadable"); return; }
        if (!enabledRead) { skip("disabled"); restore("render-disabled"); return; }
        if (!root.isAttachedToWindow()) { skip("root-detached"); restore("render-root-detached"); return; }
        if (!lockscreenCycle) {
            // 下拉桌面通知栏时系统仍可能把 keyguard root 保持 attached。这个周期
            // 标记优先于 KeyguardManager，确保自绘时间/背景绝不渗到桌面通知栏。
            if (snapshot != null && foreground == null) preCreate(snapshot);
            else if (foreground != null && !suspended) suspend("desktop-notification-shade");
            skip("unlocked-cycle");
            return;
        }
        if (suspended) {
            // 解锁后场景已淡出保留：媒体还在播就什么都不做，等锁屏出现时复用同一批视图；
            // 只有媒体真的不可用才销毁，免得锁屏重新出现时呈现已经过期的封面和曲目。
            if (snapshot == null) { Log.i(TAG, "Suspended scene dropped: media unavailable"); restore("suspended-media-gone"); }
            else skip("suspended-scene-kept");
            return;
        }
        PowerManager power = context.getSystemService(PowerManager.class);
        KeyguardManager keyguard = context.getSystemService(KeyguardManager.class);
        if (power == null) { skip("no-power"); restore("render-no-power"); return; }
        if (keyguard == null || !keyguard.isKeyguardLocked()) {
            // 桌面上不接管界面，但趁媒体回调把场景提前建好（不可见）：
            // 一播放歌曲场景就绪，之后锁屏/熄屏/亮屏都不用再 create()。
            if (snapshot != null && foreground == null) {
                preCreate(snapshot);
                skip("scene prebuilt on desktop");
                return;
            }
            skip("keyguard-unlocked");
            restore("render-keyguard-unlocked");
            return;
        }
        // A metadata callback may arrive while the display is off.  Preserve the
        // last valid scene until wake-up instead of exposing the stock wallpaper.
        if (!power.isInteractive()) {
            // 屏幕已灭、用户看不见，正好把场景提前建好：「桌面播歌 → 熄屏 → 亮屏」
            // 这条路径上从来没有场景（解锁期间不接管界面），亮屏得现 create()，
            // 实测 126~145ms，正是「先见原生锁屏」的来源。
            if (snapshot != null && foreground == null) preCreate(snapshot);
            skip("display-off");
            return;
        }
        if (snapshot == null) {
            // 锁屏上**只要有最后一帧就绝不撤层**。
            //
            // 旧写法只在 isStillPlaying() 时才保留、否则走 restore("render-no-session")。真机实测
            // （18:13:12~14）切歌期间媒体会话消失、超过 SESSION_GRACE_MS(1500ms) 后走的就是那条 else，
            // 把**整个场景**撤掉 —— 原生锁屏连原生壁纸一起原样露出，还得等下一次媒体回调重新 create()
            // （那一次等到 18:13:35 才建，人已经回到桌面上了）。这正是用户反馈的
            // 「解锁后有时候会看到原生壁纸」的来源。
            //
            // 取舍（已与用户确认）：音乐 App 被彻底关掉时，锁屏会一直留着最后一帧封面，而不是退回
            // 原生锁屏 —— 与既有的「暂停不撤层」是同一套哲学。注意能走到这一行就说明 keyguard
            // 此刻是锁着的（桌面/解锁路径在上面已经 restore），所以不会把场景漏到桌面上。
            if (foreground != null && shown != null) { skip("last-frame-kept"); return; }
            skip("no-session"); restore("render-no-session");
            return;
        }
        lastSkipReason = null;
        if (foreground == null && !create()) return;
        registerGuard();
        applySnapshot(snapshot);
        if (!expanded) showMusic();
    }

    /** 把一帧数据铺到已建好的视图上；不涉及可见性，供正常渲染与熄屏预建共用。 */
    private void applySnapshot(MediaSource.Snapshot snapshot) {
        shown = snapshot;
        // 只有封面**真的换了**才重新 setImageBitmap：媒体层每 2 秒交一次快照（带进度），
        // 而给 ImageView 重设同一张 bitmap 也会触发重绘 —— 全屏模糊层每 2 秒被强制重渲染一次，
        // 这本身就是周期性卡顿。文本与进度照旧每次更新（很便宜）。
        // 内容指纹比对（不是引用比对）：见 [shownArtSignature] 的注释。
        String artKey = artSignature(snapshot.art);
        if (!artKey.equals(shownArtSignature)) {
            Bitmap previousArtwork = shownArtwork;
            shownArtSignature = artKey;
            shownArtwork = snapshot.art;
            if (songFadeEnabled) {
                // 引用去重逻辑原样保留：只有封面真的换了才动这三个视图（性能红线）。
                crossfadeArtwork(baseBlur, previousArtwork, snapshot.art);
                crossfadeArtwork(cover, previousArtwork, snapshot.art);
                crossfadeArtwork(cardArt, previousArtwork, snapshot.art);
            } else {
                baseBlur.setImageBitmap(snapshot.art); cover.setImageBitmap(snapshot.art); cardArt.setImageBitmap(snapshot.art);
            }
            // 大封面弹入（cover_pop）：**独立于切歌淡出**——它只是缩放动效，跟封面做不做
            // 交叉过渡没有关系（zuige 指出不该耦合）。90% 过冲回弹到 100%。
            if (fxCoverPop && cover != null) {
                Log.i(TAG, "Fx: cover pop");
                cover.animate().cancel();
                cover.setScaleX(0.90f); cover.setScaleY(0.90f);
                cover.animate().scaleX(1f).scaleY(1f).setDuration(340)
                        .setInterpolator(POP_INTERPOLATOR).start();
                // 兜底同上：缩放绝不能停在 0.9。
                cover.postDelayed(() -> {
                    if (cover.getScaleX() != 1f) { cover.animate().cancel(); cover.setScaleX(1f); cover.setScaleY(1f); }
                }, 420);
            }
            // 播放器卡片弹入（player_card_pop，zuige：把专辑的动效搞一份到播放器上）：与大封面
            // 同款 90%→100% 过冲回弹、同拍触发（同挂「封面真换了」分支，首现与切歌都会弹）、
            // 独立开关。注意 cardPopRunning：showMusic() 的幂等 scale 复位同帧就跟在后面，
            // 不让路的话 0.90 起点立刻被打平（cover 没这问题是因为那行复位不动 cover 的 scale）。
            if (fxPlayerCardPop && playerCard != null) {
                Log.i(TAG, "Fx: card pop");
                cardPopRunning = true;
                playerCard.animate().cancel();
                playerCard.setScaleX(0.90f); playerCard.setScaleY(0.90f);
                playerCard.animate().scaleX(1f).scaleY(1f).setDuration(340)
                        .setInterpolator(POP_INTERPOLATOR).start();
                playerCard.postDelayed(() -> {
                    cardPopRunning = false;
                    if (playerCard.getScaleX() != 1f) { playerCard.animate().cancel(); playerCard.setScaleX(1f); playerCard.setScaleY(1f); }
                }, 420);
            }
        }
        // 「跟随封面」档取色：每次快照都过一遍（缓存命中只是一行字符串比较，很便宜）——
        // 挂在封面变化分支里的话，换「主色来源」档位后要等下一首歌才会重取，用户会以为没生效。
        maybeExtractCardPalette(snapshot.art, emptyAs(snapshot.title, "") + "|" + emptyAs(snapshot.artist, "")
                + "|" + snapshot.art.getWidth() + "x" + snapshot.art.getHeight());
        if (songFadeEnabled) {
            applyTextWithFade(title, emptyAs(snapshot.title, "未知曲目"));
            applyTextWithFade(artist, emptyAs(snapshot.artist, "未知艺术家"));
        } else {
            title.setText(emptyAs(snapshot.title, "未知曲目")); artist.setText(emptyAs(snapshot.artist, "未知艺术家"));
        }
        // 播放/暂停图标：变化**先挂起 600ms 再提交**（glyphCommit）。切歌瞬间播放器会经历
        // playing → 缓冲/暂停 → playing，图标闪一下又变回去非常碍眼（zuige 反馈）；
        // 600ms 内翻回当前显示状态的挂起直接撤销，屏幕上毫无痕迹。稳定 600ms 的才算真状态变化。
        // 值是 "play"/"pause" 语义标记（矢量图标切换），只做等值比较不直接上屏。
        String playGlyph = snapshot.playing ? "pause" : "play";
        if (!playGlyph.equals(shownPlayGlyph) && playPause != null) {
            pendingGlyph = playGlyph;
            main.removeCallbacks(glyphCommit);
            main.postDelayed(glyphCommit, 600);
        } else if (pendingGlyph != null && playPause != null) {
            pendingGlyph = null;                  // 翻回了正在显示的状态：撤销挂起的翻转
            main.removeCallbacks(glyphCommit);
        }
        updateProgress();
        refreshDateLine(false);   // 跨天时日期行最多 2 秒后自动翻页；同天只是一次整数比较
    }

    /**
     * 封面内容指纹：抽样 5 个位置（四角 + 中心）的像素 + 尺寸。
     * 同一张专辑图被重新解码出多个 Bitmap 实例时指纹不变——引用比对此刻会误判为「换了封面」。
     * 成本只有 5 次 getPixel（微秒级），远低于「误判一次」触发的全屏模糊层重绘 + Palette。
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

    /**
     * 切歌封面过渡：旧封面保持不透明，新封面从 0 淡入盖上（TransitionDrawable 默认模式；
     * 不新增视图层级、不破坏 clipToOutline 圆角，baseBlur 的 RenderEffect 模糊照常作用）。
     * 首次铺图（无旧图）直接落位——场景初建/熄屏预建有自己的入场节奏，不叠加动画。
     *
     * 过渡结束后必须换回单张 setImageBitmap：两层尺寸不同时，CENTER_CROP 的矩阵按
     * TransitionDrawable 整体计算，单层可能裁错；换回单张保证终态与旧版逐像素一致。
     * settle 以「当前 drawable 仍是自己那份 TransitionDrawable」为判据（红线经验：
     * 定时落点用 postDelayed 兜底），新过渡接管后旧 settle 自动失效。
     */
    private void crossfadeArtwork(ImageView view, Bitmap oldArt, Bitmap newArt) {
        if (view == null) return;
        if (oldArt == null || oldArt.isRecycled() || newArt == null) {
            view.setImageBitmap(newArt);
            return;
        }
        BitmapDrawable from = new BitmapDrawable(context.getResources(), oldArt);
        BitmapDrawable to = new BitmapDrawable(context.getResources(), newArt);
        TransitionDrawable transition = new TransitionDrawable(new Drawable[] { from, to });
        // **绝不能开 crossFadeEnabled(true)**（2026-10-09 真机踩坑）：那会让两层同时半透明，
        // 中段约 25% 透到视图背后——baseBlur 背后是原生壁纸层，壁纸偏浅就是用户看到的
        // 「切歌闪一张白、像一直在淡出」。默认模式（false）旧层保持不透明、新层淡入盖上，
        // 合成结果恒不透明，视觉上同样是平滑换封面。
        view.setImageDrawable(transition);
        transition.startTransition((int) ART_CROSSFADE_MS);
        view.postDelayed(() -> {
            if (view.getDrawable() == transition) view.setImageBitmap(newArt);
        }, ART_CROSSFADE_MS + 60);
    }

    /**
     * 切歌文字柔和换字：旧字淡出 → 换字 → 新字淡入；同字（每 2 秒的进度快照）直接跳过，
     * 不产生任何重绘。首帧铺字直接落位（场景入场有自己的节奏）。
     * view.tag 存「当前期望字样」：快速连续切歌时后一次调用接管，旧回调按 tag 失配自动作废。
     * 红线经验：ViewPropertyAnimator 的回调不可独靠，postDelayed 兜底强制落到终态，
     * 防文字停在半透明或被吞。
     */
    private void applyTextWithFade(TextView view, String newText) {
        if (view == null) return;
        if (newText.contentEquals(view.getText())) return;
        view.setTag(newText);
        if (view.length() == 0) { view.setText(newText); return; }
        view.animate().cancel();
        view.animate().alpha(0f).setDuration(TEXT_FADE_OUT_MS).withEndAction(() -> {
            if (!newText.equals(view.getTag())) return;   // 已被更新的换字接管
            view.setText(newText);
            view.animate().alpha(1f).setDuration(TEXT_FADE_IN_MS).start();
        }).start();
        view.postDelayed(() -> {
            if (newText.equals(view.getTag())) { view.setText(newText); view.setAlpha(1f); }
        }, TEXT_SWAP_FALLBACK_MS);
    }

    /** 停掉配色渐变并把展示值落位到当前主色（整场销毁 / 缓存命中同步回填时用）。 */
    private void cancelSwatchAnimation() {
        if (swatchAnimator != null) { swatchAnimator.cancel(); swatchAnimator = null; }
        displayedSwatch = autoSwatch;
    }

    /**
     * 「跟随封面」配色渐变：切歌时旧主色 → 新主色（ArgbEvaluator，约 320ms），
     * 每帧用展示值重推全套跟随色，卡片底色与各文字色不再「啪」一下跳变。
     * 首次取到色 / 回落到无彩兜底（0）时直接落位不动画。
     * 每帧成本只有约 10 次 setTextColor/setColor，与全屏模糊层重绘差两个量级；
     * 新取色到达时旧动画被取消重起新的（paletteExecutor 单线程 + main.post 天然串行）。
     */
    private void animateSwatchTo(int target) {
        autoSwatch = target;
        if (!songFadeEnabled) { cancelSwatchAnimation(); applyFollowColors(); return; }
        if (swatchAnimator != null) swatchAnimator.cancel();
        if (target == 0 || displayedSwatch == 0 || displayedSwatch == target) {
            displayedSwatch = target;
            applyFollowColors();
            return;
        }
        ValueAnimator animator = ValueAnimator.ofObject(new ArgbEvaluator(), displayedSwatch, target);
        swatchAnimator = animator;
        animator.setDuration(SWATCH_FADE_MS);
        animator.addUpdateListener(animation -> {
            Object value = animation.getAnimatedValue();
            if (value instanceof Integer) {
                displayedSwatch = (Integer) value;
                applyFollowColors();
            }
        });
        animator.start();
    }

    /**
     * 屏幕已灭时预建场景：建好立刻置 GONE 并标记 suspended，
     * 亮屏时由 pre-draw 守卫的 resume() 直接恢复，跳过 create()。
     */
    private void preCreate(MediaSource.Snapshot snapshot) {
        if (!create()) return;
        registerGuard();
        applySnapshot(snapshot);
        foreground.setVisibility(View.GONE);
        if (background != null) background.setVisibility(View.GONE);
        if (notificationButton != null) notificationButton.setVisibility(View.GONE);
        playerSceneVisible = true;   // 数据已就绪，亮屏不需要入场动画
        suspended = true;            // 复用 suspend 语义，等 resume() 恢复
        Log.i(TAG, "Scene pre-created while display off; will resume on wake");
    }

    private boolean create() {
        Log.i(TAG, "create() attempt " + (++createAttempts) + " " + state());
        Map<String, Integer> elements = Config.elementValues(context);
        ElementGeometry geometry = measureElements(elements);
        clock = find(root, CLOCK);
        View top = root.getRootView();
        windowRoot = top instanceof ViewGroup ? (ViewGroup) top : null;
        View stack = find(top, NOTIFICATIONS);
        notifications = stack instanceof ViewGroup ? (ViewGroup) stack : null;
        // 左侧通知栏宿主：它的可见性不是可靠信号，只用它来装手势拦截。
        leftShadePanel = findById(windowRoot, "notification_panel");
        notificationsScrim = findByIdInAnyPackage(windowRoot, "scrim_notifications");
        installShadeBlock();
        secondaryClock = findById(root, "miui_keyguard_foreground_clock_container");
        nativeBackgroundLayer = findById(root, "keyguard_background_layer");
        nativeForegroundLayer = findById(root, "keyguard_foreground_layer");
        if (clock == null || notifications == null || windowRoot == null) {
            if (!missingViewsLogged) Log.w(TAG, "Native views missing: clock=" + (clock != null) + " notifications=" + (notifications != null));
            missingViewsLogged = true;
            return false;
        }
        Log.i(TAG, "Native layers: clock=" + (clock != null) + " secondary=" + (secondaryClock != null)
                + " background=" + (nativeBackgroundLayer != null)
                + " foreground=" + (nativeForegroundLayer != null));
        // This layer is below SystemUI's shortcut row but above the stock wallpaper.
        // It fills the bottom area that the interaction scene deliberately leaves free.
        background = new FrameLayout(context);
        baseBlur = new ImageView(context);
        baseBlur.setScaleType(ImageView.ScaleType.CENTER_CROP);
        background.addView(baseBlur, new FrameLayout.LayoutParams(-1, -1));
        solidFill = new View(context);
        background.addView(solidFill, new FrameLayout.LayoutParams(-1, -1));
        baseScrim = new View(context);
        background.addView(baseScrim, new FrameLayout.LayoutParams(-1, -1));
        applyBackdrop();
        foreground = new FrameLayout(context);
        FrameLayout.LayoutParams sceneParams = new FrameLayout.LayoutParams(-1, -1, Gravity.TOP);
        // 挂在锁屏根视图而不是窗口根：解锁时系统的退场动画作用在锁屏根上，
        // 挂窗口根的前景不会跟着走，会「壁纸已出、组件还在」地残留到桌面（实测）。
        // 通知栈仍在窗口根，展开通知时它自然盖在锁屏根之上。
        root.addView(foreground, sceneParams);
        // 调试 stay_awake：前景层可见期间屏幕不熄（只影响我们自己这层，不碰系统视图状态）。
        foreground.setKeepScreenOn(stayAwake);

        // 全屏封面（模糊背景）的 z 序是本项目最容易翻车的地方，两条约束都是从真机踩出来的：
        //   ① 必须在**原生锁屏壁纸** `keyguard_background_layer` 之上 —— 否则原生壁纸一重显就盖住我们
        //      （旧版挂窗口根最底层就是这么翻车的：用户看到「只有一张原生壁纸」）。
        //   ② 必须在**底部快捷栏**容器之下 —— 把封面插到锁屏根最顶层之后，手电筒/相机图标整片消失
        //      （还能点进去，但图标看不见）：图标也在锁屏根里，被这层不透明封面压住了。
        // 所以锚点选「原生壁纸层」，紧贴它后面插，不碰它上面的任何原生控件。
        attachBackgroundLayer();
        // 一次创建只打三行：z 序是这块最容易出错的地方，光看 idx 数字没用，得看清每一支的归属。
        // ① 锁屏根的直接子视图（从底到顶）；② 原生壁纸层的祖先链；③ 快捷栏容器的祖先链。
        Log.i(TAG, "keyguardRoot children (bottom→top): " + rootChildCensus());
        Log.i(TAG, "wallpaperLayer chain: " + ancestorChain(nativeBackgroundLayer));
        Log.i(TAG, "shortcutContainer chain: " + ancestorChain(findByIdInAnyPackage(root, "keyguard_shortcut_container")));

        content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL); content.setGravity(Gravity.CENTER_HORIZONTAL);
        // 顶部三段（签名行/日期行/时钟）沿「距上一个元素」语义链：第一行距内容区顶，
        // 后面的行距上一行。两行都关时保持老布局（时钟距顶 = CLOCK_SPACING）。
        // 顺序＝日期行在上、签名行在日期下方（用户指定）。
        cardBgConfig = elem(elements, Config.CARD_BG);
        clockColorFollow = elem(elements, Config.CLOCK_COLOR) == 0;
        dateColorFollow = elem(elements, Config.DATE_COLOR) == 0;
        signColorFollow = elem(elements, Config.SIGN_COLOR) == 0;
        entryColorFollow = elem(elements, Config.ENTRY_COLOR) == 0;
        entryBgFollow = elem(elements, Config.ENTRY_BG) == 0;
        cardBgPick = elem(elements, Config.CARD_BG_PICK) != 0;
        clockPick = elem(elements, Config.CLOCK_PICK) != 0;
        datePick = elem(elements, Config.DATE_PICK) != 0;
        signPick = elem(elements, Config.SIGN_PICK) != 0;
        entryColorPick = elem(elements, Config.ENTRY_COLOR_PICK) != 0;
        entryBgPick = elem(elements, Config.ENTRY_BG_PICK) != 0;
        swatchPickMode = Math.max(0, Math.min(2, elem(elements, Config.SWATCH_PICK)));
        debugLog = elem(elements, Config.DEBUG_LOG) != 0;
        MediaSource.verboseLog = debugLog;
        songFadeEnabled = elem(elements, Config.SONG_FADE) != 0;
        fxButtonFeedback = elem(elements, Config.BUTTON_FEEDBACK) != 0;
        fxSmoothProgress = elem(elements, Config.SMOOTH_PROGRESS) != 0;
        fxCoverPop = elem(elements, Config.COVER_POP) != 0;
        fxPlayerCardPop = elem(elements, Config.PLAYER_CARD_POP) != 0;
        fxVectorIcons = elem(elements, Config.PLAYER_VECTOR_ICONS) != 0;
        hideShortcuts = elem(elements, Config.HIDE_SHORTCUTS) != 0;
        notifyCardTint = elem(elements, Config.NOTIFY_CARD_TINT) != 0;
        stayAwake = elem(elements, Config.STAY_AWAKE) != 0;
        // keepScreenOn 挂在我们自己的前景层上（场景显示期间屏幕不熄）。
        // 之前挂过 SystemUI 的锁屏根：那是系统视图，HyperOS 的 AOD/超级壁纸盯着它的
        // 窗口状态，挂上去之后真机出现「亮屏慢 + 滑动时原生壁纸闪烁」。
        if (stayAwake) Log.i(TAG, "Debug stay-awake ON (via overlay foreground)");
        Log.i(TAG, "Fx: button=" + fxButtonFeedback + " smooth=" + fxSmoothProgress
                + " pop=" + fxCoverPop + " cardPop=" + fxPlayerCardPop + " songFade=" + songFadeEnabled
                + " hideShortcuts=" + hideShortcuts + " notifyTint=" + notifyCardTint);
        signatureText = Config.elementText(context, Config.DATE_SIGNATURE);
        boolean signOn = elem(elements, Config.SIGN_ENABLED) != 0 && !signatureText.isEmpty();
        boolean dateOn = elem(elements, Config.DATE_ENABLED) != 0;
        int leadSpacing = dateOn ? elem(elements, Config.DATE_SPACING)
                : signOn ? elem(elements, Config.SIGN_SPACING)
                : elem(elements, Config.CLOCK_SPACING);
        FrameLayout.LayoutParams contentParams = new FrameLayout.LayoutParams(-1, -2, Gravity.TOP); contentParams.topMargin = dp(leadSpacing);
        foreground.addView(content, contentParams);
        if (dateOn) {
            dateLine = new TextView(context); dateLine.setGravity(Gravity.CENTER);
            dateLine.setTextSize(elem(elements, Config.DATE_SIZE));
            dateLine.setTextColor(elemOrFollow(elements, Config.DATE_COLOR, false, datePick));
            applyTextWeight(dateLine, elem(elements, Config.DATE_WEIGHT));
            content.addView(dateLine, new LinearLayout.LayoutParams(-1, -2));
            refreshDateLine(true);
        }
        if (signOn) {
            signatureLine = new TextView(context); signatureLine.setGravity(Gravity.CENTER);
            signatureLine.setTextSize(elem(elements, Config.SIGN_SIZE));
            signatureLine.setTextColor(elemOrFollow(elements, Config.SIGN_COLOR, false, signPick));
            signatureLine.setText(signatureText);
            applyTextWeight(signatureLine, elem(elements, Config.SIGN_WEIGHT));
            LinearLayout.LayoutParams signParams = new LinearLayout.LayoutParams(-1, -2);
            if (dateOn) signParams.topMargin = dp(elem(elements, Config.SIGN_SPACING));
            content.addView(signatureLine, signParams);
        }
        // 描边加粗：圆体字体 wght 轴有上限（700/800），更粗只能 FILL_AND_STROKE 外圈补粗。
        // 值在 create 时读定（配置变化走整场重建），每次绘制前重设 paint，防止被系统重置。
        final float clockStrokePx = dp(clamp(elem(elements, Config.CLOCK_STROKE), 0, 16));
        immersiveClock = new TextClock(context) {
            @Override protected void onDraw(Canvas canvas) {
                android.graphics.Paint paint = getPaint();
                if (clockStrokePx > 0) {
                    paint.setStyle(android.graphics.Paint.Style.FILL_AND_STROKE);
                    paint.setStrokeWidth(clockStrokePx);
                } else {
                    paint.setStyle(android.graphics.Paint.Style.FILL);
                }
                super.onDraw(canvas);
            }
        };
        immersiveClock.setFormat12Hour("h:mm"); immersiveClock.setFormat24Hour("HH:mm"); immersiveClock.setGravity(Gravity.CENTER);
        immersiveClock.setTextSize(elem(elements, Config.CLOCK_SIZE));
        immersiveClock.setTextColor(elemOrFollow(elements, Config.CLOCK_COLOR, true, clockPick));
        applyClockTypeface(immersiveClock, elem(elements, Config.CLOCK_ROUNDNESS), elem(elements, Config.CLOCK_WEIGHT));
        LinearLayout.LayoutParams clockParams = new LinearLayout.LayoutParams(geometry.clockWidth, geometry.clockHeight);
        if (signOn || dateOn) clockParams.topMargin = dp(elem(elements, Config.CLOCK_SPACING));
        content.addView(immersiveClock, clockParams);
        cover = new ImageView(context); cover.setScaleType(ImageView.ScaleType.CENTER_CROP); cover.setClipToOutline(true);
        GradientDrawable coverShape = new GradientDrawable(); coverShape.setColor(Color.WHITE); coverShape.setCornerRadius(dp(Config.cornerRadiusDp(context))); cover.setBackground(coverShape);
        LinearLayout.LayoutParams artParams = new LinearLayout.LayoutParams(geometry.coverWidth, geometry.coverHeight);
        artParams.topMargin = dp(elem(elements, Config.COVER_SPACING));
        content.addView(cover, artParams);
        playerCard = buildPlayerCard(elements);
        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(geometry.cardWidth, geometry.cardHeight);
        cardParams.topMargin = dp(elem(elements, Config.CARD_SPACING));
        content.addView(playerCard, cardParams);
        notificationButton = new Button(context); notificationButton.setText("展开通知");
        notificationButton.setTextColor(elemOrFollow(elements, Config.ENTRY_COLOR, true, entryColorPick));
        // 自绘胶囊替换系统默认背景：配色可配（含跟随封面）。默认半透明黑近似系统观感。
        entryBackgroundDrawable = new GradientDrawable();
        int entryBg = elem(elements, Config.ENTRY_BG);
        entryBackgroundDrawable.setColor(entryBg == 0 ? followEntryBg() : entryBg);
        entryBackgroundDrawable.setCornerRadius(dp(24));
        notificationButton.setBackground(entryBackgroundDrawable);
        notificationButton.setAllCaps(false);
        notificationButton.setOnClickListener(v -> { if (expanded) showMusic(); else showNotifications(); });
        // 放在我们自己的 FrameLayout 内，避免 HyperOS 动画期间根容器忽略 gravity
        // 而把入口落到左上角；底部位置低于充电文案。
        FrameLayout.LayoutParams entry = new FrameLayout.LayoutParams(-2, dp(48), Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL); entry.bottomMargin = dp(10);
        foreground.addView(notificationButton, entry);
        // 诊断用：一眼看出本次创建实际用了哪套参数，避免把“未重启 SystemUI”误判成代码问题。
        Log.i(TAG, "Elements: clock=" + elem(elements, Config.CLOCK_SIZE) + "sp w" + elem(elements, Config.CLOCK_WEIGHT)
                + " round" + elem(elements, Config.CLOCK_ROUNDNESS) + " color" + elem(elements, Config.CLOCK_COLOR)
                + " | cover=" + geometry.coverWidth + "x" + geometry.coverHeight + "px r"
                + Config.cornerRadiusDp(context) + " p" + elem(elements, Config.COVER_SCALE)
                + " | card=" + geometry.cardWidth + "x" + geometry.cardHeight + "px r"
                + elem(elements, Config.CARD_RADIUS) + " p" + elem(elements, Config.CARD_SCALE)
                + " | spacing=" + elem(elements, Config.CLOCK_SPACING) + "/"
                + elem(elements, Config.COVER_SPACING) + "/" + elem(elements, Config.CARD_SPACING)
                + " | date=" + (elem(elements, Config.DATE_ENABLED) != 0) + "/" + elem(elements, Config.DATE_SIZE)
                + "sp | sign=" + (elem(elements, Config.SIGN_ENABLED) != 0) + " len=" + signatureText.length()
                + " | pick vivid: card" + cardBgPick + " clock" + clockPick + " date" + datePick
                + " sign" + signPick + " entry" + entryColorPick + "/" + entryBgPick
                + " | swatch=" + (swatchPickMode == 2 ? "family" : swatchPickMode == 1 ? "dominant" : "vibrant"));
        Log.i(TAG, "Custom media card overlay created in " + createAttempts + " attempt(s)");
        createAttempts = 0;
        unlockRestoreLogged = false;
        entryAligned = false; entryAlignAttempts = 0;
        backdropSignature = currentBackdropSignature();   // applyBackdrop() 已写过一次，这里兜底
        elementSignature = currentElementSignature();
        // 底部快捷栏要等一次布局才量得到坐标，排到下一帧再对齐；拿不到就沿用 dp(10)。
        foreground.post(this::alignEntryWithShortcutRow);
        return true;
    }

    private static int elem(Map<String, Integer> elements, String key) {
        Integer value = elements.get(key);
        return value == null ? Config.elementDefault(key) : value;
    }

    /**
     * 就地更新背景三层（模糊封面 / 纯色底 / 遮罩），**不动视图树**。
     *
     * create() 与 resume() 共用：背景类设置（样式 / 遮罩颜色 / 遮罩强度）改了就调这里，
     * 只有三元素（尺寸、字号、间距…）才值得整场重建。三档样式必须一眼看得出换了东西：
     *   0 深色玻璃 —— 封面强模糊 28dp + 遮罩按「遮罩强度」原样铺
     *   1 浅色玻璃 —— 封面轻模糊 14dp + 遮罩乘 0.55，封面透出来更亮
     *   2 纯色沉浸 —— 不铺封面，整块背景就是「遮罩颜色」那一个纯色值
     * （早先三档只差 28dp 与 18dp 的模糊半径，肉眼等于没差，用户反馈「调了不生效」就出在这。）
     */
    private void applyBackdrop() {
        if (background == null || baseBlur == null) return;
        int style = Config.overlayStyle(context);
        int color = Config.overlayColor(context);
        int alpha = Config.overlayAlpha(context);
        int blurDp = style == 2 ? 0 : (style == 1 ? 14 : 28);
        baseBlur.setRenderEffect(blurDp > 0
                ? RenderEffect.createBlurEffect(dp(blurDp), dp(blurDp), Shader.TileMode.CLAMP) : null);
        if (solidFill != null) {
            solidFill.setBackground(new ColorDrawable(color | 0xFF000000));
            solidFill.setVisibility(style == 2 ? View.VISIBLE : View.GONE);
        }
        int scrimAlpha = style == 1 ? Math.round(alpha * 0.55f) : alpha;
        if (baseScrim != null)
            baseScrim.setBackground(new ColorDrawable((color & 0x00FFFFFF) | (clamp(scrimAlpha, 0, 255) << 24)));
        backdropSignature = currentBackdropSignature();
        Log.i(TAG, "Backdrop: style=" + style + " blur=" + blurDp + "dp color=" + Integer.toHexString(color)
                + " alpha=" + alpha + " scrim=" + scrimAlpha);
    }

    private static int clamp(int value, int min, int max) { return Math.max(min, Math.min(max, value)); }

    private static final class ElementGeometry {
        int clockWidth, clockHeight, coverWidth, coverHeight, cardWidth, cardHeight;
    }

    /**
     * 计算三元素的最终像素尺寸。
     *
     * 封面/播放器：宽、高为 0 时取各自的自动基准（封面＝屏宽 72% 钳 360dp、播放器＝屏宽-24 钳 160、
     * 高 178dp），非 0 时为绝对 dp；缩放百分比是**基准的倍率**（最终 = 基准 × 缩放%）。
     * 任何异常取值都只会退回默认值，不会让覆盖层失败。
     */
    private ElementGeometry measureElements(Map<String, Integer> elements) {
        DisplayMetrics metrics = context.getResources().getDisplayMetrics();
        int screenWidthDp = Math.max(1, Math.round(metrics.widthPixels / metrics.density));
        ElementGeometry geometry = new ElementGeometry();

        // A clock is text, not a scalable panel. A fixed height clips large glyphs
        // and custom fonts, so it always measures itself; top spacing controls its
        // position in the scene.
        geometry.clockWidth = -1;  // MATCH_PARENT
        geometry.clockHeight = -2; // WRAP_CONTENT

        int defaultArtDp = Math.min((int) (screenWidthDp * .72f), 360);
        int coverScale = clamp(elem(elements, Config.COVER_SCALE), 10, 300);
        int coverBaseW = elem(elements, Config.COVER_WIDTH) > 0
                ? clamp(elem(elements, Config.COVER_WIDTH), 40, 4096) : defaultArtDp;
        int coverBaseH = elem(elements, Config.COVER_HEIGHT) > 0
                ? clamp(elem(elements, Config.COVER_HEIGHT), 40, 4096) : defaultArtDp;
        geometry.coverWidth = Math.max(dp(40), dp(coverBaseW) * coverScale / 100);
        geometry.coverHeight = Math.max(dp(40), dp(coverBaseH) * coverScale / 100);

        int cardBaseW = elem(elements, Config.CARD_WIDTH) > 0
                ? clamp(elem(elements, Config.CARD_WIDTH), 40, 4096) : Math.max(160, screenWidthDp - 24);
        int cardBaseH = elem(elements, Config.CARD_HEIGHT) > 0
                ? clamp(elem(elements, Config.CARD_HEIGHT), 40, 4096) : 178;
        int cardScale = clamp(elem(elements, Config.CARD_SCALE), 10, 300);
        geometry.cardWidth = Math.max(dp(60), dp(cardBaseW) * cardScale / 100);
        geometry.cardHeight = Math.max(dp(60), dp(cardBaseH) * cardScale / 100);
        return geometry;
    }

    /**
     * 圆润度 0 用系统字体；1 / 2 用内置的开源圆体数字字体（OFL）。
     *
     * 粗细（wght）通过可变字体轴设置，失败则退回系统字体，绝不因为字体问题撤掉沉浸页。
     */
    private void applyClockTypeface(TextView view, int roundness, int weight) {
        int wght = clamp(weight, 100, 1000);
        try {
            Typeface rounded = roundTypeface(roundness);
            view.setTypeface(rounded != null
                    ? rounded
                    : Typeface.create(Typeface.SANS_SERIF, wght, false));
            view.getPaint().setFontVariationSettings("'wght' " + wght);
        } catch (Throwable error) {
            Log.w(TAG, "Clock typeface not applied: " + error);
        }
    }

    /** 文本行（日期/签名）粗细：系统字体的可变粗细，不走只含数字的圆体字体。 */
    private void applyTextWeight(TextView view, int weight) {
        int wght = clamp(weight, 100, 1000);
        try {
            view.setTypeface(Typeface.create(Typeface.SANS_SERIF, wght, false));
            view.getPaint().setFontVariationSettings("'wght' " + wght);
        } catch (Throwable error) {
            Log.w(TAG, "Text weight not applied: " + error);
        }
    }

    /**
     * 日期行刷新：按「天」缓存，只在跨天时重算文本（applySnapshot 的 2 秒节拍里只是
     * 一次整数比较，不产生渲染成本）。跨 0 点最多 2 秒后自动翻到新的一天。
     */
    private void refreshDateLine(boolean force) {
        if (dateLine == null) return;
        int dayKey = (int) (System.currentTimeMillis() / 86400000L);
        if (!force && dayKey == dateTextDay) return;
        dateTextDay = dayKey;
        try {
            dateLine.setText(buildDateText());
        } catch (Throwable error) {
            // 农历换算出任何意外都不能影响主场景：退化为纯公历
            Log.w(TAG, "Date line build failed: " + error);
            java.util.Calendar g = java.util.Calendar.getInstance();
            dateLine.setText((g.get(java.util.Calendar.MONTH) + 1) + "月" + g.get(java.util.Calendar.DAY_OF_MONTH) + "日");
        }
    }

    /** 顶部日期行文本：「6月28日周六 · 乙巳年六月初四」（格式对齐系统原生锁屏）。 */
    private static String buildDateText() {
        java.util.Calendar g = java.util.Calendar.getInstance();
        String solar = (g.get(java.util.Calendar.MONTH) + 1) + "月" + g.get(java.util.Calendar.DAY_OF_MONTH) + "日"
                + "周" + "日一二三四五六".charAt(g.get(java.util.Calendar.DAY_OF_WEEK) - 1);
        // ICU ChineseCalendar 是 Android 自带农历实现；干支年 = 60 甲子循环序号（epoch 2637 BC＝甲子）。
        android.icu.util.ChineseCalendar cc = new android.icu.util.ChineseCalendar();
        int cycle = Math.floorMod(cc.get(android.icu.util.ChineseCalendar.EXTENDED_YEAR), 60);
        char stem = "甲乙丙丁戊己庚辛壬癸".charAt((cycle + 59) % 60 % 10);
        char branch = "子丑寅卯辰巳午未申酉戌亥".charAt((cycle + 59) % 60 % 12);
        int month = cc.get(android.icu.util.ChineseCalendar.MONTH);
        boolean leap = cc.get(android.icu.util.ChineseCalendar.IS_LEAP_MONTH) != 0;
        int day = cc.get(android.icu.util.ChineseCalendar.DAY_OF_MONTH);
        String lunar = (leap ? "闰" : "") + "正二三四五六七八九十冬腊".charAt(month) + "月"
                + LUNAR_DAY_NAMES[day];
        return solar + " · " + stem + "" + branch + "年" + lunar;
    }

    private static final String[] LUNAR_DAY_NAMES = {
            "", "初一", "初二", "初三", "初四", "初五", "初六", "初七", "初八", "初九", "初十",
            "十一", "十二", "十三", "十四", "十五", "十六", "十七", "十八", "十九", "二十",
            "廿一", "廿二", "廿三", "廿四", "廿五", "廿六", "廿七", "廿八", "廿九", "三十",
    };

    /** 从本模块 APK 的 assets 解出圆体字体并缓存；SystemUI 读不到模块资源时返回 null。 */
    private Typeface roundTypeface(int roundness) {
        if (roundness <= 0) return null;
        Typeface cached = roundedTypefaces.get(roundness);
        if (cached != null) return cached;
        try {
            File target = new File(context.getCacheDir(), "hmsc_clock_round_" + roundness + ".ttf");
            if (target.length() == 0) {
                ApplicationInfo info = context.getPackageManager().getApplicationInfo(Config.PACKAGE, 0);
                try (ZipFile zip = new ZipFile(info.sourceDir)) {
                    ZipEntry entry = zip.getEntry("assets/fonts/clock_round_" + roundness + ".ttf");
                    if (entry == null) return null;
                    try (InputStream in = zip.getInputStream(entry);
                         OutputStream out = new FileOutputStream(target)) {
                        byte[] buffer = new byte[8192];
                        int read;
                        while ((read = in.read(buffer)) > 0) out.write(buffer, 0, read);
                    }
                }
            }
            cached = Typeface.createFromFile(target);
            roundedTypefaces.put(roundness, cached);
            Log.i(TAG, "Rounded clock font loaded: level=" + roundness);
            return cached;
        } catch (Throwable error) {
            Log.w(TAG, "Rounded clock font unavailable (" + roundness + "): " + error);
            return null;
        }
    }

    private LinearLayout buildPlayerCard(final Map<String, Integer> elements) {
        LinearLayout card = new LinearLayout(context); card.setOrientation(LinearLayout.VERTICAL); card.setPadding(dp(16), dp(14), dp(16), dp(10));
        // 底色可配（默认＝历史硬编码的近黑；0＝跟随封面，先按黑建、取色回来回填）。
        cardBackgroundDrawable = new GradientDrawable(); cardBackgroundDrawable.setColor(0xF2181818); cardBackgroundDrawable.setCornerRadius(dp(elem(elements, Config.CARD_RADIUS))); card.setBackground(cardBackgroundDrawable);
        LinearLayout header = new LinearLayout(context); header.setGravity(Gravity.CENTER_VERTICAL); card.addView(header, new LinearLayout.LayoutParams(-1, dp(64)));
        cardArt = new ImageView(context); cardArt.setScaleType(ImageView.ScaleType.CENTER_CROP); cardArt.setClipToOutline(true);
        artBackgroundDrawable = new GradientDrawable(); artBackgroundDrawable.setCornerRadius(dp(12)); artBackgroundDrawable.setColor(0xFF404040); cardArt.setBackground(artBackgroundDrawable);
        // 点击小封面 → 跳当前音乐 App（见 launchMusicApp：先收锁屏再启动）。
        cardArt.setOnClickListener(v -> launchMusicApp());
        attachPressFeedback(cardArt);
        cardArt.setContentDescription("打开音乐应用");
        header.addView(cardArt, new LinearLayout.LayoutParams(dp(64), dp(64)));
        LinearLayout labels = new LinearLayout(context); labels.setOrientation(LinearLayout.VERTICAL); labels.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(0, -1, 1f); labelParams.leftMargin = dp(14); header.addView(labels, labelParams);
        title = label(Color.WHITE, 22, true); artist = label(0xFF9E9EA3, 15, false);
        labels.addView(title, new LinearLayout.LayoutParams(-1, dp(34))); labels.addView(artist, new LinearLayout.LayoutParams(-1, dp(24)));
        LinearLayout controls = new LinearLayout(context); controls.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams controlsParams = new LinearLayout.LayoutParams(-1, dp(54)); controlsParams.topMargin = dp(4); card.addView(controls, controlsParams);
        if (fxVectorIcons) {
            iconWave = icon(R.drawable.ic_wave_rounded, 26, Color.WHITE); header.addView(iconWave, new LinearLayout.LayoutParams(dp(38), -1));
            iconHeart = icon(R.drawable.ic_heart_rounded, 26, Color.WHITE); controls.addView(iconHeart, controlParams());
            previous = icon(R.drawable.ic_media_previous_double, 28, Color.WHITE); previous.setOnClickListener(v -> transport(1)); attachPressFeedback(previous); controls.addView(previous, controlParams());
            playPause = icon(R.drawable.ic_media_pause_block, 32, Color.WHITE); playPause.setOnClickListener(v -> transport(2)); attachPressFeedback(playPause); controls.addView(playPause, controlParams());
            next = icon(R.drawable.ic_media_next_double, 28, Color.WHITE); next.setOnClickListener(v -> transport(3)); attachPressFeedback(next); controls.addView(next, controlParams());
            iconQueue = icon(R.drawable.ic_queue_rounded, 26, Color.WHITE); controls.addView(iconQueue, controlParams());
            Log.i(TAG, "Player icons: vector-rounded");
        } else {
            // 旧版字符播放键（player_vector_icons=0 的回退）：TextView + Unicode 字形，形状随字体。
            iconWave = iconGlyph("⌁", 30, Color.WHITE); header.addView(iconWave, new LinearLayout.LayoutParams(dp(38), -1));
            iconHeart = iconGlyph("\u2661", 28, Color.WHITE); controls.addView(iconHeart, controlParams());
            previous = iconGlyph("\u25c0", 29, Color.WHITE); previous.setOnClickListener(v -> transport(1)); attachPressFeedback(previous); controls.addView(previous, controlParams());
            playPause = iconGlyph("\u2161", 34, Color.WHITE); playPause.setOnClickListener(v -> transport(2)); attachPressFeedback(playPause); controls.addView(playPause, controlParams());
            next = iconGlyph("\u25b6", 29, Color.WHITE); next.setOnClickListener(v -> transport(3)); attachPressFeedback(next); controls.addView(next, controlParams());
            iconQueue = iconGlyph("\u25a3", 27, Color.WHITE); controls.addView(iconQueue, controlParams());
            Log.i(TAG, "Player icons: glyph-legacy");
        }
        LinearLayout timeline = new LinearLayout(context); timeline.setGravity(Gravity.CENTER_VERTICAL); card.addView(timeline, new LinearLayout.LayoutParams(-1, dp(28)));
        elapsed = label(0xFF9E9EA3, 14, false); elapsed.setGravity(Gravity.CENTER); timeline.addView(elapsed, new LinearLayout.LayoutParams(dp(48), -1));
        progress = new ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal); progress.setMax(1000);
        progress.setProgressTintList(ColorStateList.valueOf(0xFFD7D7DA)); progress.setProgressBackgroundTintList(ColorStateList.valueOf(0xFF444449));
        timeline.addView(progress, new LinearLayout.LayoutParams(0, dp(6), 1f));
        duration = label(0xFF9E9EA3, 14, false); duration.setGravity(Gravity.CENTER); timeline.addView(duration, new LinearLayout.LayoutParams(dp(48), -1));
        applyCardColors(cardBgConfig == 0 ? followCardBg() : cardBgConfig);
        return card;
    }

    /**
     * 把整套卡片配色（底色 + 文字 + 图标 + 进度条）按底色亮度联动铺上去。
     * 手选档位在 build 时调一次；「跟随封面」档在取色完成后由主线程再调（换歌时动态更新）。
     */
    private void applyCardColors(int cardBg) {
        if (cardBackgroundDrawable == null || title == null) return;
        boolean lightCard = isLightColor(cardBg);
        int titleColor = lightCard ? 0xFF1C1C1E : Color.WHITE;
        int subColor = lightCard ? 0xFF6C6C70 : 0xFF9E9EA3;
        cardBackgroundDrawable.setColor(cardBg);
        artBackgroundDrawable.setColor(lightCard ? 0xFF9E9EA3 : 0xFF404040);
        title.setTextColor(titleColor); artist.setTextColor(subColor);
        elapsed.setTextColor(subColor); duration.setTextColor(subColor);
        applyIconColor(iconWave, titleColor); applyIconColor(iconHeart, titleColor); applyIconColor(iconQueue, titleColor);
        applyIconColor(previous, titleColor); applyIconColor(playPause, titleColor); applyIconColor(next, titleColor);
        progress.setProgressTintList(ColorStateList.valueOf(lightCard ? 0xFF3C3C40 : 0xFFD7D7DA));
        progress.setProgressBackgroundTintList(ColorStateList.valueOf(lightCard ? 0xFF9E9EA3 : 0xFF444449));
    }

    /** 「跟随封面」文字色的当前值：按该项取色风格从主色推导；未取到色时先用白/灰兜底（回填时更新）。 */
    private int elemOrFollow(Map<String, Integer> elements, String key, boolean asMain, boolean vivid) {
        int value = elem(elements, key);
        if (value != 0) return value;
        return followText(vivid, asMain);
    }

    /** 「跟随封面」文字色：vivid＝鲜艳档（保留饱和度抬亮度），否则磨砂档（现行压饱和规则）。读展示值（渐变期间随动画移动）。 */
    private int followText(boolean vivid, boolean asMain) {
        if (displayedSwatch == 0) return asMain ? Color.WHITE : 0xFF9E9EA3;
        if (vivid) return vividText(displayedSwatch, asMain);
        int[] texts = textColorsFromSwatch(displayedSwatch);
        return asMain ? texts[0] : texts[1];
    }

    /** 「跟随封面」播放器底色：鲜艳档主色直出，磨砂档低饱和容器。读展示值。 */
    private int followCardBg() {
        if (displayedSwatch == 0) return 0xF2181818;
        return cardBgPick ? vividContainer(displayedSwatch) : containerFromSwatch(displayedSwatch);
    }

    /** 「跟随封面」入口胶囊背景：与播放器底色同规则但独立取色风格。读展示值。 */
    private int followEntryBg() {
        if (displayedSwatch == 0) return 0x66101010;
        return entryBgPick ? vividContainer(displayedSwatch) : containerFromSwatch(displayedSwatch);
    }

    /** 取色回填时统一刷新所有「跟随封面」档（卡片底 + 文字 + 入口）的配色（主线程调用）。 */
    private void applyFollowColors() {
        if (autoSwatch == 0) return;
        if (cardBgConfig == 0 && cardBackgroundDrawable != null) applyCardColors(followCardBg());
        if (clockColorFollow && immersiveClock != null) immersiveClock.setTextColor(followText(clockPick, true));
        if (dateColorFollow && dateLine != null) dateLine.setTextColor(followText(datePick, false));
        if (signColorFollow && signatureLine != null) signatureLine.setTextColor(followText(signPick, false));
        if (entryColorFollow && notificationButton != null) notificationButton.setTextColor(followText(entryColorPick, true));
        if (entryBgFollow && entryBackgroundDrawable != null) entryBackgroundDrawable.setColor(followEntryBg());
    }

    /** 是否任一处处于「跟随封面」档：全部手选时取色管线完全不跑。 */
    private boolean anyFollow() {
        return cardBgConfig == 0 || clockColorFollow || dateColorFollow
                || signColorFollow || entryColorFollow || entryBgFollow;
    }

    /** 「跟随封面」模式：曲目变了才在后台取色（Palette 要几百 ms，绝不占主线程）；结果按曲目 key 缓存。 */
    private void maybeExtractCardPalette(final Bitmap art, final String mediaKey) {
        if (!anyFollow() || art == null || art.isRecycled()) return;
        // **缓存 key 必须带上档位**：只比 mediaKey 的话，用户在「主色来源」换档后不换歌就永远
        // 复用旧档位算出来的色 —— 真机表现就是「明明选了占比优先，颜色还是鲜艳档那个」
        // （2026-10-09 zuige 反馈的根因）。
        if (mediaKey != null && mediaKey.equals(autoMediaKey) && swatchPickMode == autoSwatchPick) {
            // 同曲同档位：只做「新视图补齐配色」的兜底，且不在渐变中途打断。
            if (autoSwatch != 0 && (swatchAnimator == null || !swatchAnimator.isRunning())) {
                displayedSwatch = autoSwatch;
                applyFollowColors();
            }
            return;
        }
        paletteExecutor.execute(() -> {
            int swatchRgb = 0;
            String candidates = "";
            try {
                Palette palette = Palette.from(art).maximumColorCount(24).resizeBitmapSize(112).generate();
                Palette.Swatch swatch = swatchPickMode == 2 ? pickFamilySwatch(palette)
                        : swatchPickMode == 1 ? pickDominantSwatch(palette) : pickSwatch(palette);
                if (swatch != null) swatchRgb = swatch.getRgb();
                // 各档位的候选色一起打出来：换档时用户在同张封面上 A/B 对比才有据可依。
                Palette.Swatch dominant = pickDominantSwatch(palette);
                Palette.Swatch vivid = pickSwatch(palette);
                Palette.Swatch family = pickFamilySwatch(palette);
                candidates = (swatchPickMode == 2 ? "family" : swatchPickMode == 1 ? "dominant" : "vivid")
                        + " dominant=" + Integer.toHexString(dominant == null ? 0 : dominant.getRgb())
                        + " vivid=" + Integer.toHexString(vivid == null ? 0 : vivid.getRgb())
                        + " family=" + Integer.toHexString(family == null ? 0 : family.getRgb());
            } catch (Throwable error) {
                Log.w(TAG, "Card palette extract failed", error);   // swatchRgb=0 → 各档回兜底色（失败关闭）
            }
            final int rgb = swatchRgb;
            final String picks = candidates;
            main.post(() -> {
                autoMediaKey = mediaKey;
                autoSwatchPick = swatchPickMode;
                animateSwatchTo(rgb);   // 渐变到新主色，动画每帧内刷新全套「跟随封面」档
                if (debugLog) Log.i(TAG, "Card palette applied key=" + mediaKey + " swatch=" + Integer.toHexString(rgb)
                        + " pick[" + picks + "]");
            });
        });
    }

    /** 取色优先级：鲜艳 → 深鲜艳 → 浅鲜艳 → 柔和 → 深柔和 → 占比最高，尽量拿到「像专辑」的那个色。 */
    private static Palette.Swatch pickSwatch(Palette palette) {
        if (palette == null) return null;
        Palette.Swatch s = palette.getVibrantSwatch();
        if (s == null) s = palette.getDarkVibrantSwatch();
        if (s == null) s = palette.getLightVibrantSwatch();
        if (s == null) s = palette.getMutedSwatch();
        if (s == null) s = palette.getDarkMutedSwatch();
        if (s == null) s = palette.getDominantSwatch();
        return s;
    }

    /**
     * 「占比优先」主色：**直接取 population 最大的色块**（真·占比优先）。
     * 旧版在 population 前 5 里按鲜艳度 S×V 挑——大多数封面挑出来的色与「最鲜艳优先」
     * 相同，两档感知不到差别（2026-10-09 zuige 反馈「默认占比优先，实际是鲜艳」后改为直取）。
     * 代价：占比最高的色块可能是封面底色的低饱和色，各「跟随封面」档对无彩主色已有兜底
     * （回白/灰文字、深色容器），不会不可读。
     */
    private static Palette.Swatch pickDominantSwatch(Palette palette) {
        if (palette == null) return null;
        java.util.List<Palette.Swatch> swatches = palette.getSwatches();
        if (swatches == null || swatches.isEmpty()) return null;
        Palette.Swatch best = null;
        for (Palette.Swatch s : swatches) {
            if (best == null || s.getPopulation() > best.getPopulation()) best = s;
        }
        return best;
    }

    /**
     * 色族占比（swatch_pick=2）：把 Palette 量化桶按色相近似归并成"色族"再比人口——
     * 修复纯 dominant 桶比拼的两个翻车场景（2026-10-10 zuige 四张样张定标）：
     * ① 照片类封面主色被明暗拆散成多个桶，而阴影/深色区聚成一个大桶抢赢（绿草封面选出近黑）；
     * ② 灰色调封面上的小红标等小面积鲜艳色在「最鲜艳优先」下偷家。
     * 规则：无彩色（S&lt;0.15 或 V&lt;0.12）不参赛；有彩色按色相环形距离 ≤30° 归族、族分＝人口和；
     * 彩族总人口 &lt; 总量 15% 视为封面本就是无彩色主导，回退全量最大桶（保证灰调封面不出红）；
     * 族代表＝族内人口最大的桶（真实取自封面，不做加权平均防止浑色）。
     */
    private static Palette.Swatch pickFamilySwatch(Palette palette) {
        if (palette == null) return null;
        java.util.List<Palette.Swatch> swatches = palette.getSwatches();
        if (swatches == null || swatches.isEmpty()) return null;
        long total = 0;
        for (Palette.Swatch s : swatches) total += s.getPopulation();
        java.util.ArrayList<java.util.List<Palette.Swatch>> families = new java.util.ArrayList<>();
        java.util.ArrayList<Float> familyHues = new java.util.ArrayList<>();
        long chromaticPopulation = 0;
        float[] hsv = new float[3];
        for (Palette.Swatch s : swatches) {
            Color.colorToHSV(s.getRgb(), hsv);
            if (hsv[1] < 0.15f || hsv[2] < 0.12f) continue;   // 无彩色：不进彩族
            chromaticPopulation += s.getPopulation();
            int home = -1;
            for (int i = 0; i < families.size(); i++) {
                float dh = Math.abs(hsv[0] - familyHues.get(i));
                if (dh > 180f) dh = 360f - dh;
                if (dh <= 30f) { home = i; break; }
            }
            if (home < 0) { families.add(new java.util.ArrayList<>()); familyHues.add(hsv[0]); home = families.size() - 1; }
            families.get(home).add(s);
        }
        Palette.Swatch fallback = pickDominantSwatch(palette);
        if (families.isEmpty() || chromaticPopulation * 100L < total * 15L) return fallback;
        Palette.Swatch best = fallback;
        long bestScore = -1;
        for (java.util.List<Palette.Swatch> family : families) {
            long score = 0;
            Palette.Swatch head = null;
            for (Palette.Swatch s : family) {
                score += s.getPopulation();
                if (head == null || s.getPopulation() > head.getPopulation()) head = s;
            }
            if (score > bestScore) { bestScore = score; best = head; }
        }
        return best;
    }

    /**
     * 专辑主色 → 文字色对 [主, 辅]（磨砂档）：**真·跟随专辑色**（保留色相），不是黑白灰切换。
     * 主文字（时间/入口）= 主色提亮到可读区间（V 0.80~0.92）；辅助（日期/签名）= 降饱和弱化版。
     * 近乎无彩的封面回白/灰（此时没有任何彩可跟）。
     */
    private static int[] textColorsFromSwatch(int swatchRgb) {
        if (swatchRgb == 0) return new int[] { Color.WHITE, 0xFF9E9EA3 };
        float[] hsv = new float[3];
        Color.colorToHSV(swatchRgb, hsv);
        if (hsv[1] < 0.10f) return new int[] { Color.WHITE, 0xFF9E9EA3 };
        float v = hsv[2] < 0.55f ? 0.80f : Math.min(hsv[2] + 0.10f, 0.92f);
        float[] mainHsv = { hsv[0], Math.min(hsv[1] + 0.05f, 0.85f), v };
        float[] subHsv = { hsv[0], hsv[1] * 0.45f, Math.min(v + 0.02f, 0.90f) };
        return new int[] { Color.HSVToColor(0xFF, mainHsv), Color.HSVToColor(0xE6, subHsv) };
    }

    /**
     * 主色 → 低饱和磨砂容器色（磨砂档，保留色相）：主色偏亮做浅容器（V 0.82 / S≤0.25，配深字），
     * 偏暗做深容器（V 0.24 / S≤0.42，配白字）；近乎无彩的封面回历史黑。alpha 与手选档一致 0xF2。
     */
    private static int containerFromSwatch(int swatchRgb) {
        if (swatchRgb == 0) return 0xF2181818;
        float[] hsv = new float[3];
        Color.colorToHSV(swatchRgb, hsv);
        if (hsv[1] < 0.12f) return 0xF2181818;
        if (hsv[2] > 0.6f) { hsv[1] = Math.min(hsv[1] * 0.45f, 0.25f); hsv[2] = 0.82f; }
        else { hsv[1] = Math.min(hsv[1] * 0.8f, 0.42f); hsv[2] = 0.24f; }
        return Color.HSVToColor(0xF2, hsv);
    }

    /** 「鲜艳」档容器：专辑主色原色直出，仅统一 0xF2 不透明（与手选档一致）。 */
    private static int vividContainer(int swatchRgb) {
        return (swatchRgb & 0x00FFFFFF) | 0xF2000000;
    }

    /**
     * 「鲜艳」档文字：保留主色饱和度（略增强），只把亮度抬进可读区间 0.72~0.92，
     * 深色封面上的暗主色也看得清；辅助文字 alpha 0xE6 弱化。无彩封面回白/灰。
     */
    private static int vividText(int swatchRgb, boolean asMain) {
        float[] hsv = new float[3];
        Color.colorToHSV(swatchRgb, hsv);
        if (hsv[1] < 0.10f) return asMain ? Color.WHITE : 0xFF9E9EA3;
        float v = Math.max(Math.min(hsv[2] + 0.08f, 0.92f), 0.72f);
        return Color.HSVToColor(asMain ? 0xFF : 0xE6,
                new float[] { hsv[0], Math.min(hsv[1] * 1.05f, 1f), v });
    }

    /** sRGB 亮度 + alpha 折算：底色叠在壁纸上之后是否偏亮（决定卡片内文字用深还是浅）。 */
    private static boolean isLightColor(int color) {
        float alpha = Color.alpha(color) / 255f;
        if (alpha < 0.5f) return false;
        float lum = (0.299f * Color.red(color) + 0.587f * Color.green(color) + 0.114f * Color.blue(color)) / 255f;
        return lum * alpha > 0.5f;
    }

    /**
     * 点击卡片小封面：跳当前媒体会话所属的音乐 App；没有会话或包名不可启动就静默忽略。
     *
     * **为什么不能直接 `startActivity`**（2026-10-09 真机实测）：启动本身会被批准
     * （`BAL_ALLOW_NON_APP_VISIBLE_WINDOW`，START_TASK_TO_FRONT），但**锁屏还亮着**，
     * 系统不允许目标 Activity 变为可见 → 整个转场被 `transition.abort()` 回滚，
     * 屏幕上毫无反应。日志证据：`START ... result code=3` 紧跟 `handleStartResult transition.abort()`。
     *
     * 收锁屏的两条路都已真机验证过：
     * - ❌ `KeyguardManager.KeyguardLock`：Android 16 的 WMS 对系统 uid 直接抛
     *   `UnsupportedOperationException: Only apps can use the KeyguardLock API`（权限给了也不行）。
     * - ✅ 调 SystemUI 自己的 `KeyguardViewMediator`（实例由 HookEntry 构造器 hook 捕获）。
     *   dex 核实过的两个落点：无参 `exitKeyguardAndFinishSurfaceBehindRemoteAnimation()`
     *   （HyperOS 自加，语义就是「退出锁屏让后面的 surface 接管」）和
     *   `dismiss(IKeyguardDismissCallback, CharSequence)`。反射按名字找、逐个试、异常兜底。
     *
     * 只在**非安全锁**（`isKeyguardSecure()==false`）时启用：有 PIN/图案的设备跳过收锁屏
     * （绝不能绕过验证），退回直接启动。收锁屏失败同样退回直接启动——失败关闭。
     */
    private void launchMusicApp() {
        try {
            MediaSource.Snapshot snapshot = shown;
            String pkg = snapshot == null || snapshot.controller == null ? null : snapshot.controller.getPackageName();
            if (pkg == null) { Log.i(TAG, "Card tap: no media session, skip launch"); return; }
            Intent intent = context.getPackageManager().getLaunchIntentForPackage(pkg);
            if (intent == null) { Log.i(TAG, "Card tap: no launch intent for " + pkg); return; }
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            android.app.KeyguardManager keyguard = context.getSystemService(android.app.KeyguardManager.class);
            boolean locked = keyguard != null && keyguard.isKeyguardLocked();
            boolean secure = keyguard != null && keyguard.isKeyguardSecure();
            Log.i(TAG, "Card tap: launch " + pkg + " locked=" + locked + " secure=" + secure);
            if (locked && !secure && dismissKeyguardInternal(pkg)) {
                // 锁屏已在退场。**这里不能太快**（2026-10-09 实测 150ms 会 OOM 崩 SystemUI）：
                // dismiss → 解锁动画 → app 启动 → MIUI 转场快照全部压进同一窗口，
                // SystemUI 的 256MB 堆瞬时堆积 ~180MB 直接 OOM（堆平时只有 ~75MB）。
                // 正常的「解锁→再开 app」是两个动作，GC 有喘息时间；拆开时序对齐这个节奏。
                main.postDelayed(() -> {
                    try {
                        android.app.KeyguardManager km = context.getSystemService(android.app.KeyguardManager.class);
                        boolean stillLocked = km != null && km.isKeyguardLocked();
                        Log.i(TAG, "Card tap: deferred launch " + pkg + " stillLocked=" + stillLocked);
                        if (stillLocked) return;   // 没真解锁就别硬启动（会再被 abort），失败关闭
                        context.startActivity(intent);
                        Log.i(TAG, "Card tap: keyguard dismissed, app launched " + pkg);
                    } catch (Throwable error) { Log.w(TAG, "Card tap deferred start failed", error); }
                }, 800);
                return;
            }
            context.startActivity(intent);
        } catch (Throwable error) {
            Log.w(TAG, "Card tap launch failed", error);
        }
    }

    /**
     * 调 `KeyguardViewMediator` 收掉锁屏。非安全锁才允许调（安全锁绝不能绕过验证）。
     *
     * 落点与字节码依据（2026-10-09 dex 反汇编核实）：
     * - **`dismiss(IKeyguardDismissCallback, CharSequence)`（首选）**：往 Handler 发 DISMISS 消息，
     *   分支里 `mShowing==true` 时会走 `StatusBarKeyguardViewManager.mActivityStarter` 的
     *   原生 dismiss 路径（就是系统点通知那套）→ 非安全锁直接 keyguardGoingAway、跑原生解锁动画。
     *   callback 不能赌 null 安全（handleMessage 在 SystemUI 主线程，NPE = SystemUI 崩溃），
     *   用 `java.lang.reflect.Proxy` 在运行时造一个空实现（AIDL 接口是普通接口，可代理）。
     * - `exitKeyguardAndFinishSurfaceBehindRemoteAnimation()`（备选）：真机实测条件不满足时
     *   **内部静默 skip**（`surfaceAnimationRunning=false`），调用成功不代表生效，仅作兜底。
     *
     * @return true = 有落点被调用；false = 实例没捕获到 / 方法都失败 / 是安全锁
     */
    private boolean dismissKeyguardInternal(String pkg) {
        Object mediator = HookEntry.keyguardMediator;
        if (mediator == null) { Log.i(TAG, "Card tap: no mediator instance, plain start"); return false; }
        ClassLoader loader = mediator.getClass().getClassLoader();
        Object callback = null;
        try {
            Class<?> iface = Class.forName("com.android.internal.policy.IKeyguardDismissCallback", true, loader);
            callback = java.lang.reflect.Proxy.newProxyInstance(loader, new Class<?>[] {iface},
                    (proxy, method, args) -> {
                        String n = method.getName();
                        if (n.equals("onDismissSucceeded") || n.equals("onDismissError")) {
                            Log.i(TAG, "Card tap dismiss callback: " + n);
                            return null;
                        }
                        if (n.equals("asBinder")) return proxy;
                        // **绝不能再转发回 proxy**（2026-10-09 血案）：系统注册回调时会打日志
                        // `"Adding callback: " + callback` → 调 toString() → 旧代码兜底分支
                        // `method.invoke(proxy, args)` 又调回自己 → 无限递归（实测栈深 14460 层、
                        // 堆瞬间 +180MB → OOM）。Object 三个方法必须直接返回，其余一律 no-op。
                        if (n.equals("toString")) return "MeloLockDismissCallback";
                        if (n.equals("hashCode")) return System.identityHashCode(proxy);
                        if (n.equals("equals")) return proxy == (args == null || args.length == 0 ? null : args[0]);
                        return null;
                    });
        } catch (Throwable error) {
            Log.w(TAG, "Card tap: dismiss callback proxy unavailable", error);
        }
        for (String name : new String[] {"dismiss", "exitKeyguardAndFinishSurfaceBehindRemoteAnimation"}) {
            try {
                java.lang.reflect.Method target = null;
                for (java.lang.reflect.Method method : mediator.getClass().getDeclaredMethods()) {
                    if (name.equals(method.getName())) { target = method; break; }
                }
                if (target == null) { Log.i(TAG, "Card tap: mediator has no " + name); continue; }
                target.setAccessible(true);
                Class<?>[] params = target.getParameterTypes();
                if ("dismiss".equals(name) && params.length == 2
                        && params[0].getName().contains("IKeyguardDismissCallback")) {
                    if (callback == null) { Log.i(TAG, "Card tap: dismiss skipped (no callback)"); continue; }
                    target.invoke(mediator, callback, "io.github.melolock");
                } else if (params.length == 0) {
                    target.invoke(mediator);
                } else {
                    Log.i(TAG, "Card tap: skip " + name + " (unexpected signature " + params.length + " args)");
                    continue;
                }
                Log.i(TAG, "Card tap: keyguard exit via mediator#" + name);
                return true;
            } catch (Throwable error) {
                Log.w(TAG, "Card tap: mediator#" + name + " failed", error);
            }
        }
        return false;
    }

    private void showMusic() {
        boolean animateIn = !playerSceneVisible;
        boolean returning = expanded;   // 从通知页返回：走共享元素的反向动画
        expanded = false; hideNativeWallpaperLayers(root); hideNativeClockLayers(root); applyShortcutRowVisibility();
        // 取消动画**只在真的发生页面切换时做**：showMusic() 是每次快照（约 2 秒一次）都被
        // render() 调一遍的，无条件 cancel 会把刚启动的组件动效（封面弹入）在画出第一帧前
        // 就掐掉，而且缩放会永远卡在动画起点 0.9 —— 真机表现就是「封面弹入没生效」。
        if (returning || animateIn) { foreground.animate().cancel(); cover.animate().cancel(); playerCard.animate().cancel(); }
        cover.setVisibility(View.VISIBLE); playerCard.setVisibility(View.VISIBLE);
        foreground.setVisibility(View.VISIBLE); ensureOnTop(foreground); ensureOnTop(notificationButton); notificationButton.setText("展开通知");
        // hideNativeClockLayers() must never retain an old hidden state on the
        // module clock after a page transition or a SystemUI pre-draw pass.
        immersiveClock.setVisibility(View.VISIBLE); immersiveClock.setAlpha(1f);
        // 返回时复用展开时那一次的位移：现场测量两个方向量出来的值不同（真机日志 -605px vs -228px），
        // 卡片会「去一趟、从别处回来」，看起来就是跳。
        // 顺便再试一次和系统快捷栏对齐：场景经常是在「桌面/灭屏」时先建好的，那一刻
        // keyguard 底部什么都没有，只有真的站到锁屏上才量得到手电筒/相机那一排。
        alignEntryWithShortcutRow();
        int shared = returning ? (lastSwapOffset != 0 ? lastSwapOffset : measureSwapOffset()) : 0;
        if (returning) {
            // 通知**直接收起**，不再淡出：淡出层正好盖在时钟区域上，那一层任何合成抖动
            // 看起来都是「时间闪一下」。播放器的滑入本身已经承接了「同一个控件」的观感。
            Log.i(TAG, "Page swap: back to player, shared offset=" + shared + "px");
            applyNotificationTint(false);
            endNotificationsLayer();
            if (notifications != null) {
                notifications.animate().cancel();
                notifications.setAlpha(1f); notifications.setTranslationY(0f);
                hide(notifications);
            }
        } else {
            hide(notifications);
        }
        if (animateIn) {
            if (returning) {
                // 关键：**不要再淡入 foreground**。前景（含时钟）在通知页一直是显示的，
                // 旧代码在这里把它从 alpha 0 淡入 180ms，等于让时钟先消失再回来 —— 用户
                // 反馈的「切回播放器时间闪一下」就是这一下。只让封面和卡片滑进来就够。
                foreground.setAlpha(1f);
                cover.setAlpha(0f); cover.setTranslationY(shared * 0.35f);
                playerCard.setAlpha(0f); playerCard.setTranslationY(shared);
                playerCard.setScaleX(0.94f); playerCard.setScaleY(0.94f);
            } else {
                foreground.setAlpha(0f);
                cover.setAlpha(0f); cover.setTranslationY(-dp(24));
                playerCard.setAlpha(0f); playerCard.setTranslationY(-dp(36));
                playerCard.setScaleX(1f); playerCard.setScaleY(1f);
                foreground.animate().alpha(1f).setDuration(180).start();
            }
            cover.animate().alpha(1f).translationY(0f).setStartDelay(20).setDuration(220)
                    .setInterpolator(fastOutSlowIn()).start();
            playerCard.animate().alpha(1f).translationY(0f).scaleX(1f).scaleY(1f).setStartDelay(40).setDuration(240)
                    .setInterpolator(fastOutSlowIn()).start();
        } else {
            foreground.setAlpha(1f); cover.setAlpha(1f); cover.setTranslationY(0f);
            playerCard.setAlpha(1f); playerCard.setTranslationY(0f);
            // 卡片弹入进行中绝不能把 scale 拉回 1：这个 else 分支与 applySnapshot 的弹入同帧
            // 执行（render → applySnapshot → showMusic），无条件复位会把 0.90 弹入起点立刻打平。
            // 弹入自带 420ms 兜底复位，这里只做「没在弹」时的幂等复位。
            if (!cardPopRunning) { playerCard.setScaleX(1f); playerCard.setScaleY(1f); }
        }
        ensureCoverVisible();
        playerSceneVisible = true;
        main.removeCallbacks(progressTicker); main.post(progressTicker);
    }

    /** 兜底：场景该显示时，背景层不该是 GONE 或半透明（解锁淡出期间由 unlockSignalled 拦住）。 */
    private void ensureCoverVisible() {
        if (background == null || unlockSignalled || suspended) return;
        if (background.getVisibility() == View.VISIBLE && background.getAlpha() >= 1f) return;
        Log.i(TAG, "Cover was not visible while the scene shows; restoring it");
        background.animate().cancel();
        background.setAlpha(1f);
        background.setVisibility(View.VISIBLE);
    }

    /**
     * {@link #preShowForWake} 的实现体。
     *
     * 写成方法而不是 lambda：lambda 放在字段初始化器里时，用**简单名**引用后面声明的字段是
     * 非法的（javac「非法前向引用」）—— 这条坑本项目已经踩过一次，统一改成方法引用 `this::xxx`。
     */
    private void runPreShowForWake() {
        applyPreShow();
    }

    /**
     * 同步把场景可见性摆回（不跑动画、不启 ticker），供「系统唤醒信号」在面板还黑着时调用。
     *
     * 关键：唤醒时 MIUI 会把原生锁屏壁纸（keyguard_background_layer / wallpaper_des / AOD 超级壁纸）
     * 重新置回可见，必须在这里**立刻再按一次**——否则我们的封面还没盖上去、原生壁纸先露出来，
     * 用户看到的就是「只有壁纸」。SCREEN_ON 之后 showMusic 也会再按，这里是提前兜一道。
     */
    private void applyPreShow() {
        if (!suspended || foreground == null || !lockscreenCycle || !keyguardLocked()) return;
        Log.i(TAG, "Pre-showing scene while display is dark so the first lit frame is ours");
        main.removeCallbacks(finishSuspend); main.removeCallbacks(finishCoverFade);
        main.removeCallbacks(hideSceneAfterUnlock);
        main.removeCallbacks(restoreCoverIfStillLocked);
        suspended = false;
        unlockSignalled = false;
        foreground.animate().cancel();
        foreground.setAlpha(1f);
        foreground.setVisibility(View.VISIBLE);
        if (background != null) {
            background.animate().cancel();
            background.setAlpha(1f);
            background.setVisibility(View.VISIBLE);
        }
        if (notificationButton != null) notificationButton.setVisibility(View.VISIBLE);
        ensureBackgroundOrder();
        if (root != null) { hideNativeWallpaperLayers(root); hideNativeClockLayers(root); applyShortcutRowVisibility(); }
    }

    /**
     * 系统侧「正在唤醒」信号（`KeyguardViewMediator#onStartedWakingUp` / `#handleNotifyWakingUp`）。
     *
     * 为什么需要它：暗期预显原来只由 `SCREEN_OFF + 220ms` 触发。用户**快速连按两下电源键**时
     * 时序会反过来 —— 真机实测 `handleNotifyWakingUp`(08.925) 比 `onScreenTurnedOn`(09.165) 早 **240ms**，
     * 而我们的 `SCREEN_OFF` 广播反而晚到 53ms、预显比亮屏晚 50ms，于是前几帧就是原生锁屏。
     * 拿系统自己的唤醒信号当触发点，比赌一个固定延时稳。
     *
     * 幂等：真正该不该做由 [runPreShowForWake] 自己的守卫决定（`suspended && lockscreenCycle && keyguardLocked()`），
     * 所以这里可以放心地提前 post、并顺带把原来那个延迟任务取消掉。
     */
    void onSystemWakingUp() {
        if (foreground == null || !keyguardLocked()) return;
        // 注意：这里**不能**再用 lockscreenCycle 当守卫——快速连按两下电源键时，唤醒信号比 SCREEN_OFF
        // 广播先到（实测 43.666 vs 43.731），而 lockscreenCycle 还挂着上一轮 USER_PRESENT 留下的 false，
        // 把最早一次预显机会拦掉了；等补跑时面板已经亮了（实测 panel on 43.846 < 预显 43.889），
        // 头几帧就是原生壁纸。亮屏那一刻 keyguardLocked() 才是权威判据；keyguard 锁着，周期标志就地扶正。
        lockscreenCycle = true;
        // 唤醒信号在「面板还黑着」时就到了（比 onScreenTurnedOn 早 ~250ms），且位于 MIUI 唤醒序列最前端——
        // 此时主线程还没被唤醒重活占满。若还按 main.post 排队，runnable 会被后面 ~250ms 的系统唤醒工作
        // 饿死，等到亮屏后才跑。所以**主线程上就同步把可见性摆好**，亮屏第一帧就是我们的界面；
        // 个别 binder 回调不在主线程，才退回 post。
        main.removeCallbacks(preShowForWake);
        if (Looper.myLooper() == main.getLooper()) {
            try { applyPreShow(); }
            catch (Throwable error) { Log.w(TAG, "Pre-show during wake failed; native may flash", error); }
        } else {
            main.post(preShowForWake);
        }
        Log.i(TAG, "Wake signal pulled the pre-show forward " + state());
    }

    private void showNotifications() {
        expanded = true; playerSceneVisible = false; main.removeCallbacks(progressTicker);
        // The album backdrop and the module clock remain visible. Only the native
        // notification stack is revealed; restoring all views would show wallpaper.
        show(notifications); hideNativeWallpaperLayers(root); hideNativeClockLayers(root); applyShortcutRowVisibility(); applyNotificationTint(true);
        immersiveClock.setVisibility(View.VISIBLE); immersiveClock.setAlpha(1f);
        foreground.animate().cancel(); cover.animate().cancel(); playerCard.animate().cancel();
        foreground.setVisibility(View.VISIBLE); foreground.setAlpha(1f); cover.setAlpha(1f); cover.setTranslationY(0f);
        playerCard.setAlpha(1f); playerCard.setTranslationY(0f); playerCard.setScaleX(1f); playerCard.setScaleY(1f);
        int shared = measureSwapOffset();
        lastSwapOffset = shared;   // 返回时复用同一个值，两个方向才对称
        Log.i(TAG, "Page swap: to notifications, shared offset=" + shared + "px");
        // 播放器带着轻微缩小滑向「列表里那张媒体卡」的位置并淡出；通知随后沿同一方向淡入。
        // 两段运动方向一致 → 读起来是同一个控件换了地方，而不是两张卡片各管各的出现/消失。
        // 用 INVISIBLE 而不是 GONE：GONE 会把它们从布局里摘掉，content 这棵竖直 LinearLayout
        // 要重新 measure/layout 一整棵子树（封面是大图 ImageView、卡片带 ProgressBar），
        // 切回播放器再 VISIBLE 又来一次——两次全树 layout 正好落在切换动画的首尾，
        // 表现就是「回到播放器时时间/画面要卡一下」。alpha 已经是 0，视觉上没有区别。
        playerCard.animate().alpha(0f).translationY(shared).scaleX(0.94f).scaleY(0.94f).setDuration(220)
                .setInterpolator(fastOutSlowIn())
                .withEndAction(() -> { if (expanded && playerCard != null) playerCard.setVisibility(View.INVISIBLE); }).start();
        cover.animate().alpha(0f).translationY(shared * 0.35f).setDuration(180).setInterpolator(fastOutSlowIn())
                .withEndAction(() -> { if (expanded && cover != null) cover.setVisibility(View.INVISIBLE); }).start();
        if (notifications != null) {
            // 关键：不能像以前那样 show() 之后就完事——那样通知是瞬间满不透明出现，
            // 而播放器还在 220ms 的淡出里，两者并列就是用户看到的「错位」。
            beginNotificationsLayer();
            notifications.animate().cancel();
            notifications.setAlpha(0f); notifications.setTranslationY(shared * 0.18f);
            notifications.animate().alpha(1f).translationY(0f).setStartDelay(70).setDuration(210)
                    .setInterpolator(fastOutSlowIn()).start();
            // 动画跑完就拆掉硬件层（Handler 兜底，不挂动画回调）；重复调用是幂等的。
            main.postDelayed(this::endNotificationsLayer, 320);
        }
        ensureOnTop(notificationButton); notificationButton.setText("返回播放器");
    }

    /**
     * 「两个播放器」之间的共享位移：通知列表里第一张足够高的卡片（通常是原生媒体通知卡）
     * 相对自绘播放器卡片的屏幕纵向偏移。两页切换时让卡片沿这个位移移动 + 缩放，
     * 视觉上就是同一个控件在换位置。
     *
     * 上限取 `dp(96)`：实测这个偏移很容易量到 600px 以上（通知列表在锁屏上部，播放器卡在下面），
     * 照原值让卡片飞过去会**穿过时钟区域**再回来，看起来就是「时间闪一下」。位移只负责给眼睛
     * 一个「同一个控件」的线索，剩下的交给淡出。
     */
    private int measureSwapOffset() {
        int fallback = -dp(52);
        if (notifications == null || playerCard == null) return fallback;
        View destination = notifications;
        for (int i = 0; i < notifications.getChildCount(); i++) {
            View child = notifications.getChildAt(i);
            if (child != null && child.getVisibility() == View.VISIBLE && child.getHeight() > dp(56)) { destination = child; break; }
        }
        int[] to = new int[2], from = new int[2];
        destination.getLocationOnScreen(to);
        playerCard.getLocationOnScreen(from);
        int delta = to[1] - from[1];
        if (delta == 0) return fallback;   // 尚未完成布局时测量值不可信
        int limit = dp(96);
        return Math.max(-limit, Math.min(limit, delta));
    }
    /**
     * 只在真的不在最上层时才调整 z 序。
     *
     * `bringToFront()` 会触发 requestLayout —— pre-draw 守卫每帧都调用它，动画期间就等于每帧
     * 重排一次窗口根的子节点，这是切页掉帧的实打实来源之一。已经在最上时直接跳过。
     */
    private void ensureOnTop(View view) {
        if (view == null || !(view.getParent() instanceof ViewGroup)) return;
        ViewGroup parent = (ViewGroup) view.getParent();
        int last = parent.getChildCount() - 1;
        if (last < 0 || parent.getChildAt(last) == view) return;
        view.bringToFront();
    }
    private static android.view.animation.Interpolator fastOutSlowIn() {
        return new android.view.animation.PathInterpolator(0.4f, 0f, 0.2f, 1f);
    }
    /**
     * 切页动画期间给通知栈开硬件层。
     *
     * 通知栈是一棵很深的树（每条通知都是大图），做 alpha/位移动画时不开层就要**每帧重绘整棵子树**，
     * 主线程被拖住 → 锁屏上所有东西（最明显的是时间）都会连带卡一下。开层后只合成一次。
     * 展开结束或撤层时必须 `endNotificationsLayer()` 拆掉，否则 GPU 层会残留。
     */
    private void beginNotificationsLayer() {
        if (notifications != null) notifications.setLayerType(View.LAYER_TYPE_HARDWARE, null);
    }
    private void endNotificationsLayer() {
        if (notifications != null) notifications.setLayerType(View.LAYER_TYPE_NONE, null);
    }

    private void transport(int action) {
        if (shown == null) return;
        try {
            if (action == 1) shown.controller.getTransportControls().skipToPrevious();
            // 中间键是播放/暂停切换：暂停时场景会保留在锁屏上，所以这里必须能恢复播放。
            else if (action == 2) {
                if (shown.playing) shown.controller.getTransportControls().pause();
                else shown.controller.getTransportControls().play();
            }
            else shown.controller.getTransportControls().skipToNext();
        } catch (RuntimeException error) { Log.w(TAG, "Media transport action failed", error); }
    }

    private void updateProgress() {
        if (shown == null || progress == null) return;
        long total = shown.durationMs, position = shown.positionMs;
        if (shown.speed > 0f && shown.positionUpdateTimeMs > 0) position += (long) ((android.os.SystemClock.elapsedRealtime() - shown.positionUpdateTimeMs) * shown.speed);
        if (total > 0) { applyProgress((int) Math.min(1000, Math.max(0, position * 1000 / total))); elapsed.setText(time(position)); duration.setText(time(total)); }
        else { applyProgress(0); elapsed.setText("--:--"); duration.setText("--:--"); }
    }

    /**
     * 进度条落位：平滑档（smooth_progress）用属性动画从当前值滑到目标值（450ms 线性，
     * 与 500ms 采样节奏衔接，看起来是连续推进而不是每秒跳格）。
     * 跳变超过 15%（切歌回零 / 用户拖动 / 场景重建首帧）直接落位，避免指针扫过整个条。
     * 新动画先取消旧的；暂停时目标值不动，等于空操作。
     */
    private void applyProgress(int percent) {
        int current = progress.getProgress();
        if (!fxSmoothProgress || Math.abs(percent - current) > 150) {
            if (progressAnimator != null) { progressAnimator.cancel(); progressAnimator = null; }
            progress.setProgress(percent);
            return;
        }
        if (percent == current) return;
        if (progressAnimator != null) progressAnimator.cancel();
        progressAnimator = ObjectAnimator.ofInt(progress, "progress", current, percent);
        progressAnimator.setDuration(450);
        progressAnimator.setInterpolator(null);   // null＝线性：与采样节奏等速推进
        progressAnimator.start();
    }

    /**
     * 控制按钮按压反馈：按下缩到 0.85、抬起/取消回弹（不消费事件，click 照常触发）。
     * 只对真正有动作的按钮挂（⏮ ▶⏸ ⏭ + 点卡片小封面跳 App），无动作的装饰图标不挂。
     * 下潜只给 50ms：快速点按也有清晰的「按下」观感。首次按压打一行日志确认通路
     * （只打一次，触摸路径绝不每次都打——诊断探针拖慢主线程是本项目踩过的坑）。
     */
    private void attachPressFeedback(View view) {
        if (!fxButtonFeedback || view == null) return;
        view.setOnTouchListener((v, event) -> {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    if (!pressFeedbackProbed) {
                        pressFeedbackProbed = true;
                        Log.i(TAG, "Fx: press feedback reached (first press)");
                    }
                    v.animate().cancel();
                    v.animate().scaleX(0.85f).scaleY(0.85f).setDuration(50).start();
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    v.animate().cancel();
                    v.animate().scaleX(1f).scaleY(1f).setDuration(160).start();
                    break;
            }
            return false;
        });
    }

    /** 撤层。reason 只用于诊断日志，用来定位「上滑露壁纸 / 亮屏先见原生锁屏」由哪条路径触发。 */
    private void restore(String reason) {
        if (foreground != null || background != null || shown != null) Log.i(TAG, "restore reason=" + reason + " " + state());
        applyNotificationTint(false);   // 染过就要复原：原生通知栈要带着原始 scrim 回锁屏
        // stay_awake 挂在前景层上，前景层随场景一起销毁，无需额外清理。
        main.removeCallbacks(progressTicker); main.removeCallbacks(finishSuspend);
        cancelSwatchAnimation();   // 整场销毁：配色渐变停掉，别继续在孤儿视图上刷颜色
        if (progressAnimator != null) { progressAnimator.cancel(); progressAnimator = null; }   // 进度平滑动画同理
        // 页面切换动画可能只跑到一半就被撤层：复位通知栈的动画属性，
        // 否则下次 show() 出来的是一张全透明的通知列表。
        if (notifications != null) { notifications.animate().cancel(); endNotificationsLayer(); notifications.setAlpha(1f); notifications.setTranslationY(0f); }
        unregisterGuard(); shown = null; shownArtwork = null; shownArtSignature = null; shownPlayGlyph = null;
        pendingGlyph = null; main.removeCallbacks(glyphCommit);
        expanded = false; playerSceneVisible = false; restoreChangedViews();
        if (foreground != null && foreground.getParent() == root) root.removeView(foreground);
        // background 挂在窗口根（见 create()），这里按实际父容器移除。
        if (background != null && background.getParent() instanceof ViewGroup) ((ViewGroup) background.getParent()).removeView(background);
        if (notificationButton != null && notificationButton.getParent() == foreground) foreground.removeView(notificationButton);
        background = null; foreground = null; content = null; immersiveClock = null; playerCard = null; notificationButton = null; clock = null; secondaryClock = null; nativeBackgroundLayer = null; nativeForegroundLayer = null; notifications = null; windowRoot = null; leftShadePanel = null;
        solidFill = null; baseScrim = null; elementSignature = null; backdropSignature = null;
        // unlockSignalled 必须在这里清掉：本周期可能刚收到 keyguardGoingAway 就撤层了（例如用户
        // 立刻又关机/切配置）。不清的话，下一个场景复用时 finishCoverFade 会把新的背景层又摘掉。
        unlockRestoreLogged = false; lastSkipReason = null; suspended = false; unlockSignalled = false;
    }

    /**
     * 系统侧解锁信号（`KeyguardViewMediator#keyguardGoingAway`）：**锁屏退场动画真正开始的时刻**。
     *
     * 由 `HookEntry` 注入的 hook 调用，可能在非主线程，所以这里只置标志、再抛回主线程。
     * 没有这个信号时（hook 不可用 / 类名对不上）整条链路什么都不做，行为与旧版一致 —— 失败关闭。
     */
    void onUnlockStarting() {
        // `interactive()` 这道闸不能省：真机实测 `notifyKeyguardGoingAway` **会跟着息屏/Doze 一起发**
        // （2026-10-07 抓到一次：信号 41.988 之后 3ms 就是 `render skipped: display-off`，而屏幕
        // 42.565 才 SCREEN_OFF、43.209 又 SCREEN_ON —— 锁屏还在，盖子却已经被我们撤掉了）。
        // 屏幕灭着时的"退场"跟我们无关，绝不能因此撤层。
        if (suspended || unlockSignalled || !interactive()) return;
        unlockSignalled = true;
        main.post(this::startUnlockCoverFade);
    }

    /**
     * 让封面一直压住，等系统解锁退场动画跑完（+685ms）与换壁纸落地（+742ms）之后，再淡出交接。
     *
     * 旧版是等 pre-draw 守卫发现解锁（实测比 `keyguardGoingAway` 晚 **277~491ms**）才硬隐藏，
     * 于是「前景层随 keyguard 根先消失 → 屏幕上只剩一块纯色 → 再过两三百毫秒才进桌面」，
     * 就是用户说的「看到纯色壁纸然后才进入桌面」「解锁没有原生快」。
     * 中间几版又想靠「桌面窗口何时上屏（+111~205ms）」来决定掀盖时机，仍然闪 ——
     * 因为真正该对齐的是**系统动画的结束时刻**，完整时间线见 [UNLOCK_COVER_HOLD_MS] 的注释。
     */
    private void startUnlockCoverFade() {
        if (background == null || foreground == null) return;
        unlockSignalledAtMs = android.os.SystemClock.elapsedRealtime();
        // **第一件事就是把封面挪出锁屏根**（详见 liftCoverToWindowRoot）。
        // 不挪的话后面所有时序都是空谈：系统会在这趟解锁里把整棵锁屏根置 INVISIBLE + alpha 0，
        // 挂在他里面的层连绘制都不参与 —— 这就是 hold 130 → 280 → 760 怎么调都没用的真正原因。
        // 此刻（keyguardGoingAway 刚到，系统动画 +148ms 才开始）挪，屏幕上看到的仍是同一张不透明封面。
        liftCoverToWindowRoot();
        Log.i(TAG, "unlock: " + nativeCensus());
        // 「提前画好、直接覆盖」的完整含义：**全程压住不透明**，把系统过渡期拉起来的壁纸窗口
        // （独立窗口，keyguardGoingAway +47ms 被 showSurfaceRobustly 拉起）与锁屏壁纸收尾动画
        // 都死死盖住；等系统解锁动画结束（+685ms）、换壁纸落地（+742ms）之后才淡出。
        // 断不能在系统动画还在跑的时候自己淡——那就是用户看到的「解锁闪一帧清晰原生壁纸」。
        Log.i(TAG, "Unlock signalled (keyguardGoingAway); cover stays opaque for " + UNLOCK_COVER_HOLD_MS
                + "ms (till system unlock anim + wallpaper swap finish), then fades " + UNLOCK_COVER_FADE_MS + "ms");
        main.removeCallbacks(finishCoverFade);
        main.postDelayed(finishCoverFade, UNLOCK_COVER_HOLD_MS);
        // 误触发兜底：notifyKeyguardGoingAway 不只在真解锁时发，回头核对一次 ——
        // 只要 suspend() 没被叫到（守卫确认过 keyguard 真的不锁了），就说明这次不是解锁。
        // 时点必须排在淡出之后，否则它会在淡出中途把不透明封面又贴回去。
        main.removeCallbacks(restoreCoverIfStillLocked);
        main.postDelayed(restoreCoverIfStillLocked, UNLOCK_COVER_HOLD_MS + UNLOCK_COVER_FADE_MS + 300);
    }

    /**
     * 摘层收尾：绝不只留一层「alpha=0 但仍 VISIBLE」的全透明视图挡在桌面上（会挡触摸）。
     *
     * 声明顺序有讲究：它必须排在 [finishCoverFade] **之前**——字段初始化器里用简单名引用
     * 后声明的字段会被 javac 判为「非法前向引用」（本项目已踩过两次的坑）。
     */
    private final Runnable hideSceneAfterUnlock = () -> {
        if (!unlockSignalled || foreground == null) return;
        if (background != null) {
            background.animate().cancel();
            background.setAlpha(1f);
            background.setVisibility(View.GONE);
        }
        foreground.animate().cancel();
        foreground.setAlpha(1f);
        foreground.setVisibility(View.GONE);
        if (notificationButton != null) notificationButton.setVisibility(View.GONE);
        long since = unlockSignalledAtMs > 0 ? android.os.SystemClock.elapsedRealtime() - unlockSignalledAtMs : -1;
        Log.i(TAG, "unlock cover removed at t=+" + since + "ms after goingAway; " + nativeCensus());
    };

    /**
     * 淡出交接：此刻系统解锁动画已结束（+685ms）、换壁纸已落地（+742ms），渐显出来的是合成好的桌面，
     * 淡出交接：淡出窗口（+200~660ms）覆盖系统解锁动画（+148~685ms）的后 2/3，
     * 用户能一路看到桌面渐显的过程，而不是「盖子定住半天、让开时桌面已经站好」。
     * 前 200ms 保持不透明，避开动画开头桌面还没完全合成的那一段。
     * 前景与背景一起淡（旧版只淡背景，前景单独在 suspend() 里淡，两个节拍对不齐）。
     */
    private final Runnable finishCoverFade = () -> {
        if (background == null || !unlockSignalled) return;
        // 三个采样点（信号 +0ms / 淡出起点 / 摘层）记录锁屏根的可见性，
        // 用来确认「系统到底什么时候把锁屏根置 INVISIBLE」。
        Log.i(TAG, "unlock fade starts; " + nativeCensus());
        // 线性淡出：ViewPropertyAnimator 默认是加减速曲线，中段掉得比桌面渐显更快，
        // 反而更容易在中间态露出壁纸。交叉溶解就该用线性（理由见 UNLOCK_COVER_FADE_MS 的注释）。
        android.view.animation.LinearInterpolator linear = new android.view.animation.LinearInterpolator();
        background.animate().cancel();
        background.animate().alpha(0f).setDuration(UNLOCK_COVER_FADE_MS).setInterpolator(linear).start();
        if (foreground != null) {
            foreground.animate().cancel();
            foreground.animate().alpha(0f).setDuration(UNLOCK_COVER_FADE_MS).setInterpolator(linear).start();
        }
        // 隐藏不能只挂 ViewPropertyAnimator 的回调：解锁期间窗口正在切换，动画回调可能根本不推进。
        // 用 Handler 兜底摘层（项目既定规矩），并打一行带原生侧普查的自证日志，便于下一轮对账。
        main.removeCallbacks(hideSceneAfterUnlock);
        main.postDelayed(hideSceneAfterUnlock, UNLOCK_COVER_FADE_MS + 60);
    };


    /** 误触发兜底：淡出之后若解锁并没有真的发生（`suspend()` 没被叫到），把盖子放回去。 */
    private final Runnable restoreCoverIfStillLocked = () -> {
        if (foreground == null || suspended || !unlockSignalled) return;
        Log.w(TAG, "Unlock signal did not lead to an unlock; restoring cover");
        unlockSignalled = false;
        if (background == null) return;
        background.animate().cancel();
        background.setAlpha(1f);
        background.setVisibility(View.VISIBLE);
    };

    /**
     * 解锁时不再硬撤场景。
     *
     * 硬撤（旧行为）有两个后果：① `restore()` 会 `restoreChangedViews()` 把原生壁纸层恢复
     * VISIBLE，而系统解锁动画还要跑上百毫秒（实测 `USER_PRESENT` 在 80~190ms 后才到），
     * 空档期就露出壁纸；② 场景被销毁，下次亮屏必须重建（实测 126~145ms），观感是
     * 「先看到原生锁屏再变成音乐版」。这里改为淡出到 GONE 并**保留实例**。
     */
    private void suspend(String reason) {
        if (suspended || foreground == null) return;
        suspended = true;
        main.removeCallbacks(progressTicker);
        // 解锁信号那条链路的回调在这里**分两种**处理：
        // ① 有解锁信号：`finishCoverFade → hideSceneAfterUnlock` 是主角 —— 它知道要压到系统解锁动画
        //    跑完（UNLOCK_COVER_HOLD_MS）才淡出。**绝不能 cancel**（旧版就是在这儿掐掉它，导致 540ms
        //    就硬撤层、正好落在系统动画 +148~685ms 中间，露出壁纸窗口）。`finishSuspend` 退化成兜底，
        //    延时按「剩余保持期」算，保证它永远落在淡出之后。
        // ② 没有解锁信号（守卫发现 keyguard 不锁了，但不是走解锁信号那条路）：照旧 600ms 后硬隐藏。
        // 另外：**不要**在这里 cancel `hideSceneAfterUnlock` —— 它负责把淡完的层真正 GONE 掉，
        // 取消它会在桌面上留一层「alpha=0 但仍 VISIBLE」的全屏透明层挡触摸（旧代码明确忌讳这点）。
        long unlockElapsed = unlockSignalled && unlockSignalledAtMs > 0
                ? android.os.SystemClock.elapsedRealtime() - unlockSignalledAtMs : -1;
        main.removeCallbacks(finishSuspend);
        if (unlockSignalled) {
            long remain = UNLOCK_COVER_HOLD_MS + UNLOCK_COVER_FADE_MS + 150 - unlockElapsed;
            main.postDelayed(finishSuspend, Math.max(300, remain));
        } else {
            main.removeCallbacks(finishCoverFade);
            main.postDelayed(finishSuspend, 600);
        }
        main.removeCallbacks(restoreCoverIfStillLocked);
        suspendStartedAtMs = android.os.SystemClock.elapsedRealtime();
        Log.i(TAG, "suspend reason=" + reason + " " + state());
        foreground.animate().cancel();
        if (cover != null) cover.animate().cancel();
        if (playerCard != null) playerCard.animate().cancel();
        // 2026-10-07（解锁闪原生壁纸收尾版）：交接期间**完全不动可见性**。
        // 旧版在这里 foreground 淡出 120ms + background 立即 GONE —— 比系统的退场编排快了一截，
        // 等于自己提前掀盖子，露出系统正在过渡展示的壁纸窗口（com.miui.miwallpaper…ImageWallpaper，
        // 独立窗口，keyguardGoingAway 后 ~47ms 被 `wms.showSurfaceRobustly` 主动拉起；我们不掀它就不露）。
        // 掀盖时机现在完全交给上面那条链路（finishCoverFade 按 UNLOCK_COVER_HOLD_MS 压到系统动画跑完），
        // 这里一步都不做；`finishSuspend` 只是「链路没跑起来」的兜底。resume()/applyPreShow() 都会取消它。
    }

    /** suspend 收尾：隐藏场景并把原生层交还系统。用 Handler 而非动画回调，避免解锁时残留。 */
    private final Runnable finishSuspend = () -> {
        if (!suspended || foreground == null) return;   // 中途 resume()/restore() 过
        // 解锁链路（finishCoverFade → hideSceneAfterUnlock）已经收拾干净了就别再动一次：
        // 它会顺手把 alpha 复位成 1，虽然马上又 GONE（无害），但日志会看不出到底谁收的尾。
        if (unlockSignalled && foreground.getVisibility() == View.GONE
                && (background == null || background.getVisibility() == View.GONE)) {
            Log.i(TAG, "finishSuspend skipped; unlock cover chain already cleaned up");
            return;
        }
        foreground.animate().cancel();
        foreground.setAlpha(1f);
        foreground.setVisibility(View.GONE);
        if (background != null) {
            background.animate().cancel();
            background.setAlpha(1f);
            background.setVisibility(View.GONE);
        }
        if (notificationButton != null) notificationButton.setVisibility(View.GONE);
        // **故意不在这里交还原生层**（2026-10-07 修）：系统的解锁退场动画在 `keyguardGoingAway`
        // 之后还要跑 ~350ms（实测 `updateKeyguardWallpaperStateAnim onAnimationFinished` 比原来的交还
        // 时刻晚 3~62ms），而交还动作 `restoreChangedViews()` 会把原生壁纸层置回 VISIBLE ——
        // 正好和系统「正在把它藏起来」的动画打架，用户看到的就是「解锁时闪一下原生锁屏」。
        // 那些层都在锁屏根里，而锁屏根此时已被系统置 GONE/INVISIBLE，继续按住它们没有副作用；
        // 真正的交还交给 `restore()`（模块关闭 / 场景销毁）——那时才需要把原生层原样还给系统。
        Log.i(TAG, "native layers stay hidden (hand-back deferred to restore); t=+"
                + (android.os.SystemClock.elapsedRealtime() - suspendStartedAtMs) + "ms " + nativeCensus());
        Log.i(TAG, "suspend done; scene kept for reuse");
    };

    /**
     * 把全屏封面插进锁屏根：**紧贴原生锁屏壁纸层之上、底部快捷栏之下**（z 序两条约束见 create()）。
     *
     * 为什么不做成窗口根的子视图：那样虽然能躲开锁屏根的退场动画，却会盖住底部快捷栏图标
     * （图标在锁屏根里），而且窗口根的兄弟顺序在不同 ROM 上不可控。待在锁屏根里、
     * 只压住壁纸层，是最稳的位置；解锁时的「掀盖时机」改由时间线控制（见 [UNLOCK_COVER_HOLD_MS]）。
     */
    private void attachBackgroundLayer() {
        if (background == null || root == null) return;
        if (background.getParent() instanceof ViewGroup && background.getParent() != root)
            ((ViewGroup) background.getParent()).removeView(background);
        int index = Math.max(0, Math.min(backgroundIndexIn(root), root.getChildCount()));
        root.addView(background, index, new ViewGroup.LayoutParams(-1, -1));
        Log.i(TAG, "background host=keyguardRoot idx=" + root.indexOfChild(background) + "/" + (root.getChildCount() - 1)
                + " wallpaperIdx=" + (nativeBackgroundLayer != null ? root.indexOfChild(nativeBackgroundLayer) : -1)
                + " clockIdx=" + (clock != null ? root.indexOfChild(clock) : -1));
    }

    /**
     * 解锁一开始就把不透明封面**从锁屏根挪到窗口根、紧贴锁屏根之上**。
     *
     * 这是「解锁闪原生壁纸」真正的结构原因：真机日志里 `native[keyguardRoot=I0.00]` 证明系统在这趟
     * 解锁中会把**整棵锁屏根**置 INVISIBLE + alpha 0（`HyperOSKeyguardRootView`）。我们的层挂在它
     * 里面，父控件一被隐藏，子视图连绘制都不参与 —— 把自己设成 `VISIBLE / alpha 1` 也救不回来。
     * 这正是 hold 从 130 一路调到 760、参数怎么调都无效的原因：**不是时间不对，是我们压根没被画出来**。
     *
     * 挪到窗口根之后就不受锁屏根可见性的影响，掀盖时机才真正由我们掌握。
     * 时机选在 `keyguardGoingAway` 这一刻（系统动画 +148ms 才开始）：屏幕内容不变（还是同一张
     * 不透明封面），且 remove + add 在同一个主线程消息里完成，不存在「两边都没有」的那一帧。
     * 解锁期间底部快捷栏图标本来就在随锁屏根一起退场，被压住没有副作用；
     * 重新锁屏时 `ensureBackgroundOrder()` 会把它挂回锁屏根、回到「壁纸之上、快捷栏之下」。
     */
    private void liftCoverToWindowRoot() {
        if (background == null || windowRoot == null || root == null) return;
        if (background.getParent() == windowRoot) return;
        int keyguardIdx = windowRoot.indexOfChild(root);
        int index = keyguardIdx >= 0 ? keyguardIdx + 1 : windowRoot.getChildCount();
        if (background.getParent() instanceof ViewGroup) ((ViewGroup) background.getParent()).removeView(background);
        windowRoot.addView(background, Math.max(0, Math.min(index, windowRoot.getChildCount())), new ViewGroup.LayoutParams(-1, -1));
        Log.i(TAG, "cover lifted to windowRoot idx=" + windowRoot.indexOfChild(background) + "/" + (windowRoot.getChildCount() - 1)
                + " keyguardIdx=" + keyguardIdx + " " + nativeCensus());
    }

    /** 锁屏根直接子视图清单：`下标:类名#id`，从底到顶。 */
    private String rootChildCensus() {
        if (root == null) return "no root";
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < root.getChildCount(); i++) {
            View child = root.getChildAt(i);
            if (i > 0) out.append(" | ");
            out.append(i).append(':').append(child.getClass().getSimpleName());
            try {
                String id = child.getResources().getResourceEntryName(child.getId());
                if (id != null && !id.isEmpty()) out.append('#').append(id);
            } catch (Throwable ignored) { /* 没 id 就只打类名 */ }
            if (child == background) out.append("<<BACKGROUND");
        }
        return out.toString();
    }

    /** 祖先链，从下到上：`KeyguardPanelView#keyguard_panel_view[0] > FrameLayout#keyguard_background_layer[2]`。 */
    private String ancestorChain(View view) {
        if (view == null) return "none";
        StringBuilder out = new StringBuilder();
        View node = view;
        while (node != null) {
            String id = "";
            try { id = node.getResources().getResourceEntryName(node.getId()); } catch (Throwable ignored) { }
            int index = node.getParent() instanceof ViewGroup ? ((ViewGroup) node.getParent()).indexOfChild(node) : -1;
            if (out.length() > 0) out.insert(0, " > ");
            out.insert(0, node.getClass().getSimpleName() + (id == null || id.isEmpty() ? "" : "#" + id) + "[" + index + "]");
            node = node.getParent() instanceof View ? (View) node.getParent() : null;
        }
        return out.toString();
    }

    /** 目标下标：原生锁屏壁纸层之后一位；锚点找不到时退到第 1 位（壁纸层一般在第 0 位）。 */
    private int backgroundIndexIn(ViewGroup host) {
        if (nativeBackgroundLayer != null && nativeBackgroundLayer.getParent() == host) {
            int idx = host.indexOfChild(nativeBackgroundLayer);
            if (idx >= 0) return idx + 1;
        }
        return 1;
    }

    /**
     * z 序自校正：锁屏根是系统自己的视图，位置可能被重挂/重排；一旦我们的封面被挤到原生壁纸层
     * **下面**，原生壁纸就会盖住我们；被挤到顶层又会压住快捷栏图标。
     *
     * 另外它还负责**把解锁时挪走的封面挂回锁屏根** —— 上一轮解锁已经把它 lift 到窗口根了，
     * 重新锁屏时必须回到「壁纸之上、快捷栏之下」，否则快捷栏图标会被压住。
     * **只在位置确实不对时才动视图树**，不要每次亮屏都重排。
     */
    private void ensureBackgroundOrder() {
        if (background == null || root == null) return;
        if (background.getParent() == root && root.indexOfChild(background) == backgroundIndexIn(root)) return;
        attachBackgroundLayer();
        Log.i(TAG, "background z-order re-asserted idx=" + root.indexOfChild(background)
                + "/" + (root.getChildCount() - 1) + " " + state());
    }

    /**
     * 诊断：解锁交接那一刻**原生侧**长什么样。
     *
     * 排查「解锁闪一下原生锁屏」时最关键的一问是：闪的到底是**只有壁纸层**还是**整个原生锁屏**
     * （时钟/图标都在）—— 只有壁纸＝交还时机问题；连时钟都出来＝另有路径在放原生内容。
     * 用可见性 + alpha 打出来，比让用户回忆「看清没看清」可靠得多。
     */
    private String nativeCensus() {
        return "native[keyguardRoot=" + layerState(root) + " wallpaper=" + layerState(nativeBackgroundLayer)
                + " nativeFg=" + layerState(nativeForegroundLayer) + " clock=" + layerState(clock)
                + " secondaryClock=" + layerState(secondaryClock) + "]";
    }

    /**
     * 曾经有个 `unlockWatch` 探针（解锁期间每 30ms 采样锁屏根、每次解锁打 50 行日志），
     * **已删除**：它给出的结论早已拿到（系统把整个 keyguard 根直接置 INVISIBLE + alpha 0、
     * 全程无位移，所以「交给系统带走」这条路不成立），而真机实测解锁期间 30ms 的采样间隔
     * 反复飙到 87~157ms（12 次）——logcat 写入是同步的，这个诊断本身就在制造用户看到的卡顿。
     * 定位现象靠 `suspend reason=` 与 `native layers handed back at t=+Nms` 两行就够。
     */
    private static String fmt(float value) { return String.format(java.util.Locale.US, "%.2f", value); }
    private static String visibilityName(View view) {
        switch (view.getVisibility()) {
            case View.VISIBLE: return "VISIBLE";
            case View.INVISIBLE: return "INVISIBLE";
            default: return "GONE";
        }
    }

    /** 锁屏重新出现：复用 suspend 保留的场景，跳过 create() 重建。 */
    private void resume() {
        if (!suspended || foreground == null) return;
        suspended = false;
        main.removeCallbacks(finishSuspend); main.removeCallbacks(finishCoverFade);
        main.removeCallbacks(hideSceneAfterUnlock);
        main.removeCallbacks(restoreCoverIfStillLocked);
        unlockSignalled = false;   // 新一轮锁屏周期：等下一次 keyguardGoingAway 再启动淡出
        Log.i(TAG, "resume reused scene " + state());
        // 复用之前先验指纹：这一批视图是照着上次的配置量出来的，直接恢复就是把旧外观又端出来。
        // 场景跨周期复用之后 create() 不再重跑，配置读不到第二次，这是「外观改了没反应」的根因。
        // 分两级处理，**顺序不能反**：
        //   ① 背景（样式/遮罩/圆角）变了 → 就地更新这三层，不动视图树；
        //   ② 三元素（字号/间距/圆角…）变了 → 才撤掉重建。
        // 早先只有一个指纹、改了就整场重建，结果那条路径在 restore() 之后**没走到 showMusic()**，
        // 而隐藏原生壁纸层/时钟层的动作正在 showMusic() 里 —— 于是重建那一百来毫秒里
        // 原生壁纸是可见的，用户看到「息屏后快速解锁闪一下原生壁纸」。
        MediaSource.Snapshot keep = shown;
        if (elementSignature != null && !elementSignature.equals(currentElementSignature())) {
            Log.i(TAG, "Elements changed while scene kept; rebuilding with current values");
            restore("elements-changed");
            if (keep != null) {
                // 排队到下一帧重建：此刻正站在 pre-draw 回调里，直接 addView 改视图树会让
                // 当前这一帧的绘制与紧接着的 layout 互相打断。
                main.post(() -> {
                    if (foreground != null || suspended || !lockscreenCycle || !keyguardLocked()) return;
                    render(keep);
                });
            }
            return;
        }
        if (backdropSignature != null && !backdropSignature.equals(currentBackdropSignature())) applyBackdrop();
        foreground.animate().cancel();
        foreground.setAlpha(1f);
        foreground.setVisibility(View.VISIBLE);
        if (background != null) {
            // suspend() 会把背景淡到 0，复用时必须复位，否则锁屏重现时背景是透明的。
            background.animate().cancel();
            background.setAlpha(1f);
            background.setVisibility(View.VISIBLE);
        }
        if (notificationButton != null) notificationButton.setVisibility(View.VISIBLE);
        ensureBackgroundOrder();
        main.post(this::alignEntryWithShortcutRow);
        showMusic();
    }

    /**
     * 三元素参数指纹（尺寸 / 间距 / 字号 / 圆角 / 是否锁比例 …）。
     *
     * SystemUI 侧一次 `Config.elementValues()` 查询即可，调用点只有 create() 收尾与 resume()。
     */
    private String currentElementSignature() {
        // 取值必须走 elementValues() 的那张表：只有它跨进程查 Provider 拿到配置端的真实值。
        // Config.elementInt()/elementString() 在 SystemUI 进程读的是**本进程**的 SharedPreferences
        // （那份永远是空的，于是恒等于默认值）——早先签名用它取值，指纹因此恒定不变，
        // 比对形同虚设，改外观不重建，只能靠关开模块撤场才有新配置。
        Map<String, Integer> values = Config.elementValues(context);
        StringBuilder text = new StringBuilder(192);
        for (String key : new java.util.TreeSet<>(values.keySet()))
            text.append(key).append('=').append(values.get(key)).append(';');
        // 签名正文是字符串值元素，不在整数表里，单独读一次（跨进程）；改签名也要触发重建
        text.append("sig=").append(Config.elementText(context, Config.DATE_SIGNATURE)).append(';');
        return text.toString();
    }

    /** 背景指纹：背景样式 + 遮罩颜色 + 遮罩强度 + 封面圆角（`Config.cornerRadiusDp` 每次查询一次）。 */
    private String currentBackdropSignature() {
        return Config.overlayStyle(context) + "|" + Config.overlayColor(context) + "|"
                + Config.overlayAlpha(context) + "|" + Config.cornerRadiusDp(context);
    }

    /**
     * 「展开通知 / 返回播放器」入口与系统底部快捷栏（左手的电筒、右手的相机）对齐高度。
     *
     * 这两个图标是 MIUI 自己的 keyguard 布局，**资源 id 换版本就改名**（网上流传的那些名字
     * 在 16.03 上对不上），所以不写死 id，改成按几何特征认：可见、图标量级（高 32~72dp、
     * 宽 ≤ 96dp）、落在窗口底部 22% 的带子里、并且横向贴边（中心点在左右 22% 之外）。
     * 「底部两角 + 图标尺寸 + 贴边」三条一起，很难撞到别的控件。
     * 从 windowRoot 开始扫：不确定快捷栏挂在 keyguard 根的哪一层。
     * 拿不到锚点时保留默认的 dp(10)，绝不因为对齐失败让入口跑出屏幕。
     */
    private void alignEntryWithShortcutRow() {
        // 已经对齐过就别再扫树了；几何不变，重复测量只是白花钱。
        // 最多试 5 次：showMusic() 每次媒体回调都会走到（播放时约每秒一次），
        // 拿不到锚点时不能让它一直滚窗口树。
        if (entryAligned || entryAlignAttempts >= 5) return;
        if (notificationButton == null || foreground == null || windowRoot == null) return;
        // 只有真正站在锁屏上才量得准：桌面上、或屏幕还黑着的时候，keyguard 底部那一排
        // 快捷图标压根没显示（真机 log 里那次「band 里一个控件都没有」就是这么来的）。
        if (!keyguardLocked() || !interactive()) return;
        entryAlignAttempts++;
        View anchor = findShortcutAnchor();
        int height = foreground.getHeight();
        if (anchor == null && hideShortcuts && height > 0) {
            // 快捷栏被我们 GONE 了，量不到锚点是预期内的。按屏高比例把入口放回原快捷带的位置，
            // 否则入口会停在默认 dp(10)（贴屏幕底边，压住底部提示文案/手势条）。
            fallbackAlignEntryToBand(height);
            return;
        }
        if (anchor == null || height <= 0) {
            // 头三次尝试会附带一次「底部带子里到底有什么」的清单，方便换机型/换版本时改判据。
            if (entryAlignAttempts <= 3) {
                Log.i(TAG, "Shortcut anchor not found (attempt " + entryAlignAttempts + "); entry keeps dp(10). " + bottomBandCensus());
            }
            return;
        }
        FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) notificationButton.getLayoutParams();
        if (params == null) return;
        int[] anchorPos = new int[2], hostPos = new int[2];
        anchor.getLocationOnScreen(anchorPos);
        foreground.getLocationOnScreen(hostPos);
        int anchorCenterY = anchorPos[1] + anchor.getHeight() / 2 - hostPos[1];
        int margin = height - anchorCenterY - params.height / 2;
        if (margin < 0 || margin > dp(160)) {
            Log.i(TAG, "Shortcut anchor rejected: centerY=" + anchorCenterY + " margin=" + margin + "px");
            return;
        }
        entryAligned = true;
        if (margin == params.bottomMargin) return;
        params.bottomMargin = margin;
        notificationButton.setLayoutParams(params);
        Log.i(TAG, "Entry aligned to shortcut row: anchor=" + anchor.getHeight() + "px centerY=" + anchorCenterY + " margin=" + margin + "px");
    }

    /**
     * 快捷栏被隐藏时的入口兜底对齐：真机 1080×2400 上快捷带中心 y≈2255（底部提示文案中心 2257），
     * 约为屏高 94%，按前景高度折算即可。
     */
    private void fallbackAlignEntryToBand(int height) {
        FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) notificationButton.getLayoutParams();
        if (params == null) return;
        int anchorCenterY = Math.round(height * ENTRY_FALLBACK_CENTER_RATIO);
        int margin = height - anchorCenterY - params.height / 2;
        if (margin < 0 || margin > dp(160)) {
            Log.i(TAG, "Fallback entry band rejected: centerY=" + anchorCenterY + " margin=" + margin + "px");
            return;
        }
        entryAligned = true;
        if (margin == params.bottomMargin) return;
        params.bottomMargin = margin;
        notificationButton.setLayoutParams(params);
        Log.i(TAG, "Entry aligned to fallback band (shortcuts hidden): centerY=" + anchorCenterY + " margin=" + margin + "px");
    }

    /**
     * 诊断用：把窗口底部一带的可见控件列出来（类名 / id / 屏幕矩形），最多 30 条。
     *
     * 底部快捷栏（手电筒 / 相机）的 id 在 MIUI 各版本里都不一样，只能先看清楚真机上到底
     * 长什么样，再决定锚点怎么认。只在找不到锚点、且每个场景一次时输出。
     */
    private String bottomBandCensus() {
        if (windowRoot == null) return "no windowRoot";
        int[] hostPos = new int[2];
        windowRoot.getLocationOnScreen(hostPos);
        StringBuilder out = new StringBuilder("host=[" + windowRoot.getWidth() + "x" + windowRoot.getHeight()
                + " top=" + hostPos[1] + "] bottom band:");
        collectBottomViews(windowRoot, hostPos[1] + (int) (windowRoot.getHeight() * 0.70f), out, 0);
        return out.toString();
    }

    private void collectBottomViews(ViewGroup group, int bandTop, StringBuilder out, int depth) {
        if (group == null || depth > 12 || out.length() > 1400) return;
        for (int i = 0; i < group.getChildCount() && out.length() < 1400; i++) {
            View child = group.getChildAt(i);
            if (child == null || child.getVisibility() != View.VISIBLE || child.getAlpha() < 0.5f) continue;
            if (child == foreground || child == background) continue;
            int[] pos = new int[2];
            child.getLocationOnScreen(pos);
            if (pos[1] + child.getHeight() / 2 >= bandTop && child.getHeight() > 0) {
                out.append(' ').append(describe(child, pos));
                if (out.length() > 1400) return;
            }
            if (child instanceof ViewGroup) collectBottomViews((ViewGroup) child, bandTop, out, depth + 1);
        }
    }

    private String describe(View view, int[] pos) {
        String id = "";
        try { id = view.getResources().getResourceEntryName(view.getId()); } catch (Throwable ignored) { }
        int[] self = new int[2];
        view.getLocationOnScreen(self);
        return (id.isEmpty() ? view.getClass().getSimpleName() : id)
                + "[" + view.getWidth() + "x" + view.getHeight() + " @" + pos[0] + "," + pos[1] + "]";
    }

    private View findShortcutAnchor() {
        // 真机视图树（`adb` 抓取 hierarchy 确认，OS3 16.03）：keyguard 根下面挂着
        // keyguard_shortcut_container，里面是 shortcut_view_left_layout（手电筒）和
        // shortcut_view_right_layout（相机），三者都是 289×289 的一方，顶边 y=2111。
        // 按 id 找最稳；资源可能属于 SystemUI 本体也可能属于 miui.systemui.plugin，逐个试。
        String[] names = { "shortcut_view_left_layout", "shortcut_view_left", "keyguard_shortcut_container", "keyguard_shortcut_layout" };
        View container = windowRoot != null ? windowRoot : root;
        for (String name : names) {
            View view = findByIdInAnyPackage(container, name);
            if (view != null && view.getVisibility() == View.VISIBLE && view.getWidth() > 0) return view;
        }
        return findBottomCornerIcon();   // 换版本、id 改名时的几何兜底
    }

    private View findByIdInAnyPackage(View container, String name) {
        if (container == null) return null;
        // 空串表示「在本 Resources 里找」，省得猜包名；最后一个候选必查，别担心重复。
        for (String pkg : new String[] { "com.android.systemui", "miui.systemui.plugin", "com.miui.aod", "" }) {
            try {
                int id = context.getResources().getIdentifier(name, "id", pkg);
                if (id == 0) continue;
                View found = container.findViewById(id);
                if (found != null) return found;
            } catch (Throwable ignored) { /* 包名不存在就换下一个 */ }
        }
        return null;
    }

    private View findBottomCornerIcon() {
        int hostHeight = windowRoot.getHeight(), hostWidth = windowRoot.getWidth();
        if (hostHeight <= 0 || hostWidth <= 0) return null;
        int[] hostPos = new int[2];
        windowRoot.getLocationOnScreen(hostPos);
        scanBudget = 900;
        return scanForCornerIcon(windowRoot, hostPos[1] + (int) (hostHeight * 0.78f), hostWidth, 0);
    }

    private View scanForCornerIcon(ViewGroup group, int bandTop, int hostWidth, int depth) {
        if (group == null || depth > 12 || scanBudget <= 0) return null;
        for (int i = 0; i < group.getChildCount() && scanBudget > 0; i++) {
            View child = group.getChildAt(i);
            scanBudget--;
            if (child == null || child.getVisibility() != View.VISIBLE || child.getAlpha() < 0.5f) continue;
            if (child == foreground || child == background) continue;   // 别把自己当成锚点
            if (isCornerIcon(child, bandTop, hostWidth)) return child;
            if (child instanceof ViewGroup) {
                View hit = scanForCornerIcon((ViewGroup) child, bandTop, hostWidth, depth + 1);
                if (hit != null) return hit;
            }
        }
        return null;
    }

    private boolean isCornerIcon(View view, int bandTop, int hostWidth) {
        int width = view.getWidth(), height = view.getHeight();
        // 尺寸上限按真机量到的 289×289px（≈105dp）放宽：MIUI 把整个触控热区做成了贴边的方块。
        if (width <= 0 || height <= 0 || width > dp(120) || height < dp(32) || height > dp(120)) return false;
        int[] pos = new int[2];
        view.getLocationOnScreen(pos);
        if (pos[1] + height / 2 < bandTop) return false;
        float centerX = pos[0] + width / 2f;
        return centerX < hostWidth * 0.22f || centerX > hostWidth * 0.78f;
    }

    /** 诊断用：一行描述覆盖层与系统状态，配合 restore/skip 日志定位闪屏路径。 */
    private String state() {
        return "fg=" + (foreground != null) + " bg=" + (background != null) + " shown=" + (shown != null)
                + " expanded=" + expanded + " scene=" + playerSceneVisible + " suspended=" + suspended
                + " attached=" + root.isAttachedToWindow() + " interactive=" + interactive()
                + " keyguard=" + keyguardLocked()
                // 可见性必须一起记：`bg=true` 只说明对象还在，真正决定"露不露原生"的是它是不是
                // GONE / 半透明。排查「亮屏露原生」这类问题全靠这两个字段。
                + " cover=" + layerState(background) + " front=" + layerState(foreground);
    }

    /** 诊断用：`V1.00` = 可见且不透明，`G0.00` = 已移除，`-` = 对象不存在。 */
    private static String layerState(View view) {
        if (view == null) return "-";
        return visibilityName(view).charAt(0) + fmt(view.getAlpha());
    }

    private boolean keyguardLocked() {
        KeyguardManager keyguard = context.getSystemService(KeyguardManager.class);
        return keyguard != null && keyguard.isKeyguardLocked();
    }

    private boolean interactive() {
        PowerManager power = context.getSystemService(PowerManager.class);
        return power != null && power.isInteractive();
    }

    /** render 早退原因去重：只在原因变化时打一行，避免媒体回调反复刷同一状态。 */
    private void skip(String reason) {
        if (reason.equals(lastSkipReason)) return;
        lastSkipReason = reason;
        if (debugLog) Log.i(TAG, "render skipped: " + reason + " " + state());
    }

    private void registerGuard() {
        ViewTreeObserver current = windowRoot.getViewTreeObserver(); if (guardObserver == current) return; unregisterGuard();
        if (current.isAlive()) { current.addOnPreDrawListener(keyguardGuard); guardObserver = current; }
    }
    private void unregisterGuard() { if (guardObserver != null && guardObserver.isAlive()) guardObserver.removeOnPreDrawListener(keyguardGuard); guardObserver = null; }

    /**
     * 沉浸场景是否仍占着锁屏（决定要不要吃掉左下拉手势）。
     *
     * **不能看 `playerSceneVisible`**：展开通知时它会被置 false，但场景（模糊背景 + 时钟）依然
     * 在锁屏上，这时左下拉照样会拉出原生通知面板、和已展开的通知叠在一起——真机踩过：
     * 展开通知后再左下拉，原生锁屏时钟和我们的时钟就重叠出现了。
     */
    private boolean sceneShowing() {
        return !suspended && foreground != null
                && foreground.getVisibility() == View.VISIBLE && lockscreenCycle;
    }

    /**
     * 在通知面板的触摸入口装一个拦截：沉浸场景显示期间，左半屏按下的手势直接吃掉。
     *
     * 为什么堵手势：左边通知栏展开的「状态信号」在真机上被证伪两次（通知栈可见性会被亮屏改、
     * `notification_panel` 可见性表示「锁屏在显示」而非「拉下来了」）。我们的自绘层挂在
     * `keyguard_root_view`（窗口根的最后一个子节点）里，即压在通知面板之上，所以面板只会拿到
     * 落到我们层上的触摸——在这里把左半屏的 DOWN 吃掉，通知栏就不会展开。
     * 右侧控制中心在另一个容器（`control_center_container`），不受影响。
     */
    private void installShadeBlock() {
        if (shadeBlockInstalled || leftShadePanel == null) return;
        shadeBlockInstalled = true;
        try {
            Class<?> panelClass = leftShadePanel.getClass();
            for (String name : new String[]{"dispatchTouchEvent", "onInterceptTouchEvent", "onTouchEvent"}) {
                final java.lang.reflect.Method method = findTouchMethod(panelClass, name);
                if (method == null) continue;
                final String hookName = name;
                try {
                    de.robv.android.xposed.XposedBridge.hookMethod(method, new de.robv.android.xposed.XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam hook) {
                            // 关键：面板类没有覆写 dispatchTouchEvent，getMethod 拿到的是框架 View 的实现，
                            // 钩上之后全 SystemUI 的 View 都会经过这里。不加 thisObject 判定就会把左半屏的
                            // 所有触摸一起吃掉——踩过：播放器按钮与「展开通知」全部失灵。
                            if (hook.thisObject != leftShadePanel) return;
                            if (!(hook.args[0] instanceof android.view.MotionEvent)) return;
                            android.view.MotionEvent event = (android.view.MotionEvent) hook.args[0];
                            if (event.getActionMasked() != android.view.MotionEvent.ACTION_DOWN) return;
                            if (!sceneShowing() || !shadeBlockEnabled()) return;
                            // 只挡左半屏：右上角是控制中心，不该受影响。
                            if (event.getX() > leftShadePanel.getWidth() / 2f) return;
                            hook.setResult(Boolean.FALSE);   // 当作没处理 → 通知栏不展开
                            Log.i(TAG, "Left-shade gesture consumed via " + hookName + " x=" + Math.round(event.getX())
                                    + " (#" + (++shadeBlockedCount) + ")");
                        }
                    });
                } catch (Throwable error) {
                    Log.w(TAG, "Shade block hook skipped: " + name, error);
                }
            }
            Log.i(TAG, "Left-shade touch block armed on " + panelClass.getName());
        } catch (Throwable error) {
            Log.w(TAG, "Left-shade touch block unavailable", error);
        }
    }

    /** 优先取面板类自己声明的方法；没有才退回继承来的（那种情况必须靠 thisObject 判定兜住）。 */
    private static java.lang.reflect.Method findTouchMethod(Class<?> type, String name) {
        try { return type.getDeclaredMethod(name, android.view.MotionEvent.class); }
        catch (NoSuchMethodException ignored) { }
        try { return type.getMethod(name, android.view.MotionEvent.class); }
        catch (NoSuchMethodException ignored) { return null; }
    }

    /** 每次手势现读配置：开关改了立刻生效，不用重建场景。 */
    private boolean shadeBlockEnabled() {
        try {
            Integer value = Config.elementValues(context).get(Config.BLOCK_LEFT_SHADE);
            return value == null || value != 0;
        } catch (Throwable error) { return false; }
    }

    private void hide(View view) {
        if (view == null) return;
        if (!changedViews.containsKey(view)) changedViews.put(view, new ViewState(view));
        view.setVisibility(View.INVISIBLE); view.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
    }
    /** Makes a temporarily hidden SystemUI view visible while retaining its original state for restore(). */
    private void show(View view) {
        if (view == null) return;
        if (!changedViews.containsKey(view)) changedViews.put(view, new ViewState(view));
        view.setVisibility(View.VISIBLE);
        view.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_AUTO);
    }
    private void restoreChangedViews() {
        for (Map.Entry<View, ViewState> entry : changedViews.entrySet()) { entry.getKey().setVisibility(entry.getValue().visibility); entry.getKey().setImportantForAccessibility(entry.getValue().accessibility); }
        changedViews.clear();
    }
    /** Hides every stock keyguard clock layer; ROM themes may add one after create(). */
    private void hideNativeClockLayers(View view) {
        // foreground owns TextClock too. Do not recursively hide our own scene.
        if (view == null || view == foreground || view == background) return;
        String className = view.getClass().getName().toLowerCase(java.util.Locale.ROOT);
        String idName = resourceName(view);
        if (className.contains("clock") || idName.contains("clock")) hide(view);
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) hideNativeClockLayers(group.getChildAt(i));
        }
    }
    /**
     * Keeps any stock wallpaper/background container hidden while media owns the keyguard.
     *
     * **2026-10-07 扩充**：原来只按 `wallpaper` / `keyguard_background` 命名匹配，于是
     * **AOD（息屏显示）自己那层壁纸/超级壁纸视图没被按住** —— 快速连按两下电源键时，
     * 亮屏前那几帧显示的就是它（时钟层已经被我们按住，所以用户看到的是「只有壁纸」）。
     * 现在把 id / 类名里带 `aod`、`doze`、`superwallpaper` 的也一并按住，并在**首次**按住时打一行日志，
     * 便于确认到底盖住了哪些视图（幂等：已 hide 过的不再重复）。
     */
    /**
     * 按 `hide_shortcuts` 显隐锁屏底部快捷栏（手电筒 / 相机那一排）。
     *
     * 为什么必须每次锁屏都重复施加：系统回到锁屏时会重建/重显这一排，我们 hide 过的
     * 视图下次是新实例；反过来关掉开关时也要**显式恢复 VISIBLE**——藏过一次就别指望
     * 系统自己把它放回来。
     *
     * 成本：全树按 id 查找不便宜，所以结果缓存进 `cachedShortcutRow`，最多找
     * `SHORTCUT_LOOKUP_LIMIT` 次（找不到就放弃，宁可不隐藏也不每帧扫树）。
     * 换 ROM / 换版本时 id 改名会表现为「开关无效」，日志里能看到查找次数。
     */
    private void applyShortcutRowVisibility() {
        if (!hideShortcuts && cachedShortcutRow == null && shortcutRowLookups >= SHORTCUT_LOOKUP_LIMIT) return;
        if (root == null && windowRoot == null) return;
        if (cachedShortcutRow == null && shortcutRowLookups < SHORTCUT_LOOKUP_LIMIT) {
            shortcutRowLookups++;
            View container = windowRoot != null ? windowRoot : root;
            // 容器优先（一次 GONE 整排）；容器 id 改名时退而求其次逐个按钮。
            String[] names = { "keyguard_shortcut_container", "keyguard_shortcut_layout",
                    "shortcut_view_left_layout", "shortcut_view_right_layout" };
            for (String name : names) {
                View view = findByIdInAnyPackage(container, name);
                if (view != null) { cachedShortcutRow = view; break; }
            }
            if (cachedShortcutRow == null && shortcutRowLookups == 1) {
                Log.i(TAG, "Shortcut row not found (lookup " + shortcutRowLookups + "); hide_shortcuts inactive");
            }
        }
        if (cachedShortcutRow == null) return;
        int target = hideShortcuts ? View.GONE : View.VISIBLE;
        if (cachedShortcutRow.getVisibility() != target) {
            cachedShortcutRow.setVisibility(target);
            Log.i(TAG, "Shortcut row " + (hideShortcuts ? "hidden" : "restored")
                    + " id=" + resourceName(cachedShortcutRow));
        }
    }

    /**
     * 通知页染色（事件驱动）：展开时一次施加、收起/恢复时一次复原，绝不做周期扫描。
     * scrim 用 setBackgroundColor 直改（ScrimView 是全屏矩形，没有圆角要保）；
     * 通知卡背景走 mutate + SRC_ATOP（圆角形状保留）。媒体卡的 ImageView 滤镜常驻。
     */
    private void applyNotificationTint(boolean on) {
        if (notifications == null) return;
        if (on && !notifyCardTint) return;
        if (on == tintApplied) return;
        tintApplied = on;
        int base = displayedSwatch != 0 ? containerFromSwatch(displayedSwatch) : 0xFF181818;
        int tint = (base & 0x00FFFFFF) | 0xE6000000;   // 90% 不透明
        PorterDuffColorFilter filter = on ? new PorterDuffColorFilter(tint, PorterDuff.Mode.SRC_ATOP) : null;
        if (notificationsScrim != null) {
            if (on) {
                if (savedScrimBackground == null) savedScrimBackground = notificationsScrim.getBackground();
                notificationsScrim.setBackgroundColor(tint);
            } else if (savedScrimBackground != null) {
                notificationsScrim.setBackground(savedScrimBackground);
                savedScrimBackground = null;
            }
        }
        int views = applyTintShallow(notifications, filter, 0);
        Log.i(TAG, "Notification tint " + (on ? "on" : "off")
                + " scrim=" + (notificationsScrim != null) + " views=" + views
                + " swatch=0x" + Integer.toHexString(displayedSwatch));
    }

    /** 浅扫（深度 ≤6）：只摸每张卡的直接背景视图，成本约等于一层子视图遍历。 */
    private int applyTintShallow(ViewGroup group, PorterDuffColorFilter filter, int depth) {
        if (group == null || depth > 6) return 0;
        int count = 0;
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            if (child == null) continue;
            String id = resourceName(child);   // 全小写：真机普查确认实际 id 是 backgroundnormal/dimmed
            boolean isMediaBg = id.equals("media_bg");
            boolean isCardBg = id.equals("backgroundnormal") || id.equals("backgrounddimmed");
            if (isMediaBg && child instanceof ImageView) {
                ((ImageView) child).setColorFilter(filter);
                count++;
            } else if (isCardBg) {
                Drawable bg = child.getBackground();
                if (bg != null) bg.mutate().setColorFilter(filter);
                count++;
            }
            if (child instanceof ViewGroup) count += applyTintShallow((ViewGroup) child, filter, depth + 1);
        }
        return count;
    }

    private void hideNativeWallpaperLayers(View view) {
        if (view == null || view == background) return;
        String idName = resourceName(view);
        String className = view.getClass().getName().toLowerCase(java.util.Locale.ROOT);
        boolean wallpaperish = view == nativeBackgroundLayer || view == nativeForegroundLayer
                || idName.contains("wallpaper") || idName.contains("keyguard_background")
                || idName.contains("aod") || idName.contains("doze") || idName.contains("superwallpaper")
                || className.contains("aod") || className.contains("superwallpaper");
        if (wallpaperish) {
            boolean first = !changedViews.containsKey(view);
            hide(view);
            if (first) Log.i(TAG, "Hiding native background layer: id=" + (idName.isEmpty() ? "-" : idName)
                    + " class=" + view.getClass().getSimpleName());
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) hideNativeWallpaperLayers(group.getChildAt(i));
        }
    }
    /**
     * 定向普查（诊断）：把所有「看起来是壁纸」的原生视图的可见性列出来。
     *
     * 起因：快速连按两下电源键时用户看到「只有壁纸在」，而普查行显示我们盯着的四层
     * （keyguardRoot / keyguard_background_layer / keyguard_foreground_layer / clock）里
     * 壁纸层是被按住的（`I1.00`）—— 说明显示壁纸的**是另一个视图**（AOD / 息屏显示 / 超级壁纸）。
     * 这里把 id 或类名带 wallpaper / aod / doze / super 的视图都扫出来（**含窗口根**，
     * 因为 AOD 那套视图不一定挂在锁屏根里），并连同我们自己在内的可见性一起打出来。
     * 只在亮屏那一刻打一次、最多 10 行，成本可以忽略。
     */
    private void logWallpaperLikeViews() {
        StringBuilder out = new StringBuilder("wallpaper-ish views (visible only):");
        int[] budget = { 10 };
        collectWallpaperLike(windowRoot, out, budget, 0);
        Log.i(TAG, out.toString());
    }

    private void collectWallpaperLike(View view, StringBuilder out, int[] budget, int depth) {
        if (view == null || budget[0] <= 0 || depth > 12) return;
        String idName = resourceName(view);
        String className = view.getClass().getName().toLowerCase(java.util.Locale.ROOT);
        boolean wallpaperish = idName.contains("wallpaper") || idName.contains("aod") || idName.contains("doze")
                || className.contains("aod") || className.contains("superwallpaper") || className.contains("wallpaper");
        if (wallpaperish && view.getVisibility() == View.VISIBLE) {
            budget[0]--;
            out.append(" [id=").append(idName.isEmpty() ? "-" : idName)
                    .append(" ").append(view.getClass().getSimpleName())
                    .append(" ").append(layerState(view)).append("]");
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++)
                collectWallpaperLike(group.getChildAt(i), out, budget, depth + 1);
        }
    }

    private String resourceName(View view) {        int id = view.getId();
        if (id == View.NO_ID) return "";
        try { return context.getResources().getResourceEntryName(id).toLowerCase(java.util.Locale.ROOT); }
        catch (RuntimeException ignored) { return ""; }
    }
    /** Reveals one stock view without forgetting its original state for final restore. */
    private void reveal(View view) {
        if (view == null) return;
        ViewState state = changedViews.get(view);
        if (state == null) return;
        view.setVisibility(state.visibility);
        view.setImportantForAccessibility(state.accessibility);
    }
    private TextView label(int color, int sizeSp, boolean bold) {
        TextView text = new TextView(context); text.setTextColor(color); text.setTextSize(sizeSp); text.setSingleLine(true); text.setEllipsize(TextUtils.TruncateAt.END);
        if (bold) text.setTypeface(android.graphics.Typeface.DEFAULT_BOLD); return text;
    }
    /**
     * 矢量图标版：Material Symbols Rounded 的 drawable，CENTER 不缩放居中（外层仍是 weight 格子，
     * 与旧字符按钮同布局语义）；颜色走 ColorFilter，与「跟随封面」配色联动兼容。
     * 注意**不能** setImageResource：宿主 SystemUI 的 Resources 不认识模块的编译期 id
     * （0x7f08xxxx 会撞上宿主自己的资源，NotFoundException 后静默画空——按钮在、能点、看不见），
     * 必须先从模块包上下文拿 Resources，显式 getDrawable 成对象再 setImageDrawable。
     */
    private ImageView icon(int drawableRes, int sizeDp, int color) {
        ImageView icon = new ImageView(context);
        icon.setImageDrawable(moduleDrawable(drawableRes));
        icon.setScaleType(ImageView.ScaleType.CENTER);
        icon.setColorFilter(color);
        icon.setClickable(true);
        return icon;
    }
    /** 模块自己的 Resources（懒建，宿主进程里唯一能正确解析模块资源 id 的入口）；拿不到返回 null。 */
    private android.content.res.Resources moduleResources;
    private Drawable moduleDrawable(int resId) {
        if (moduleResources == null) {
            try {
                moduleResources = context.createPackageContext("io.github.melolock", Context.CONTEXT_IGNORE_SECURITY).getResources();
            } catch (Exception e) {
                Log.w(TAG, "module package context failed: " + e);
                return null;
            }
        }
        try {
            return moduleResources.getDrawable(resId);
        } catch (Exception e) {
            Log.w(TAG, "module drawable " + resId + " failed: " + e);
            return null;
        }
    }
    /** 旧字符播放键工厂（player_vector_icons=0 回退）：TextView + Unicode 字形。 */
    private TextView iconGlyph(String value, int sizeSp, int color) {
        TextView icon = new TextView(context); icon.setText(value); icon.setTextColor(color); icon.setTextSize(sizeSp); icon.setGravity(Gravity.CENTER); icon.setClickable(true); return icon;
    }
    /** 图标配色联动按实际类型分发：TextView 走 setTextColor，ImageView 走 ColorFilter。 */
    private void applyIconColor(View view, int color) {
        if (view instanceof TextView) ((TextView) view).setTextColor(color);
        else if (view instanceof ImageView) ((ImageView) view).setColorFilter(color);
    }
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
