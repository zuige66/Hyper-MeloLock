package io.github.melolock;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

public final class Config {
    static final String PACKAGE = "io.github.melolock";
    static final String AUTHORITY = PACKAGE + ".config";
    static final Uri URI = Uri.parse("content://" + AUTHORITY + "/state");
    static final Uri ELEMENTS_URI = Uri.parse("content://" + AUTHORITY + "/elements");
    /**
     * 运行时状态（SystemUI → App 单向回报）。App 进程拿不到框架信息：Vector 不实现
     * libxposed 的服务绑定，`XposedServiceHelper` 永远超时。HookEntry 在 SystemUI 进程里
     * 读 `XposedBridge.getXposedVersion()` 后 insert 到这里（Provider 在 App 进程执行，
     * 落 SharedPreferences 持久化），App 端「Xposed 框架」行在服务绑定失败时 fallback 读它。
     */
    public static final Uri RUNTIME_URI = Uri.parse("content://" + AUTHORITY + "/runtime");
    private static final String PREFS = "module_state";
    private static final String KEY = "enabled";
    private static final String CORNER_RADIUS = "corner_radius_dp";
    private static final String ALLOWED_PACKAGES = "allowed_packages";
    private static final String OVERLAY_STYLE = "overlay_style";
    private static final String OVERLAY_COLOR = "overlay_color";
    private static final String OVERLAY_ALPHA = "overlay_alpha";
    private static final String NONE = "__NONE__";
    static final String FINGERPRINT = "Redmi/gauguinpro/gauguinpro:16/BP2A.250605.031.A3/OS3.0.303.0.WNKCNXM:user/release-keys";

    /**
     * 已验证基准指纹（2026-10-09 起机型门禁已撤、任何设备都尝试 hook，多机型测试中）。
     * 只剩一个用途：首页区分「完整验证过」与「未验证」设备，提示测试者反馈。
     */
    public static boolean deviceVerified() {
        return FINGERPRINT.equals(android.os.Build.FINGERPRINT);
    }

    /**
     * 无 root 时重启 SystemUI 的通道：配置端发这条广播，注入在 SystemUI 里的模块自己
     * kill 自己（等价一次 SystemUI 重启，和 `am crash` 效果一样）。
     *
     * 为什么需要它：Xposed 模块的 hook 只在进程启动时装载，改完配置/作用域必须重启
     * 目标进程。常规做法是 `su -c killall com.android.systemui`，但 SukiSU 这类环境
     * 下 `su` 对应用可能完全不可用（本机实测 `su: inaccessible or not found`），
     * 重启作用域这个功能就变成摆设。模块既然已经跑在 SystemUI 里，就不需要 root。
     */
    public static final String ACTION_RESTART_SYSTEMUI = "io.github.melolock.action.RESTART_SYSTEMUI";
    /**
     * 清空诊断报告（2026-10-14）：App 端「清除报告」按钮发显式广播，SystemUI 侧
     * Diag 收到后清空内存事件缓冲并回写一份空报告。无害操作（只影响诊断数据），
     * 走 RECEIVER_EXPORTED 的同一接收器。
     */
    public static final String ACTION_DIAG_CLEAR = "io.github.melolock.action.DIAG_CLEAR";
    /**
     * 「开始/停止记录」按钮（2026-10-14）：App 端切 debug_log 后发本广播，SystemUI 侧
     * 立即按最新配置重读并 Diag.enable——否则要等下一次 create()（灭屏亮屏）才生效，
     * 「点了开始没反应」会被当成模块坏了。
     */
    public static final String ACTION_DIAG_REFRESH = "io.github.melolock.action.DIAG_REFRESH";
    /** 广播的目标包：显式指定才能送达 SystemUI 进程里注册的接收器。 */
    public static final String SYSTEMUI_PACKAGE = "com.android.systemui";

    /**
     * 无 root 时重启壁纸进程（com.miui.miwallpaper）的通道，机制与 [ACTION_RESTART_SYSTEMUI]
     * 相同：注入在该进程里的 hook 代码注册接收器，收到后 kill 自己，系统自动重绑壁纸服务。
     * 2026-10-08 起模块扩展了壁纸进程作用域（换纹理探针 → 未来的封面壁纸化），
     * 「重启作用域」必须能同时重启它，否则壁纸侧的新 hook 永远装不上。
     */
    public static final String ACTION_RESTART_WALLPAPER = "io.github.melolock.action.RESTART_WALLPAPER";
    /** 壁纸进程包名：本机锁屏壁纸（含 GL 纹理上传点）由它绘制，独立于 SystemUI。 */
    public static final String WALLPAPER_PACKAGE = "com.miui.miwallpaper";

    /**
     * 封面下发通道：SystemUI 侧把媒体封面压成 JPEG 放进 extra 发给壁纸进程，
     * 壁纸侧 hook `KeyguardAnimImageWallpaperRenderer#getBitmap` 时把封面铺到壁纸尺寸。
     * 为什么不走文件：SystemUI（system 域）与 miwallpaper（wallpaper 域）互相读写对方
     * data 目录会被 SELinux 拦，JPEG bytes 直接走 Binder 最省事且无权限坑（实测单帧 ~100KB）。
     * extra 为空＝清除封面回退原生壁纸（fail closed）。
     */
    public static final String ACTION_WALLPAPER_COVER = "io.github.melolock.action.WALLPAPER_COVER";
    /**
     * 反向索要通道（2026-10-12）：壁纸进程**自己**在启动时向 SystemUI 要一次封面。
     *
     * 为什么需要它：原先靠 `ACTION_SCREEN_ON` 每次亮屏重推，壁纸进程每次都要重建 1440x3200
     * 位图并重传 GL 纹理，重传间隙会闪一帧原生壁纸——用户感知就是「亮屏慢一拍」。
     * 亮屏本身不需要重推（壁纸进程还活着、缓存还在），**只有壁纸进程重启丢缓存才需要**。
     * 改成进程自己来要，亮屏零重推。
     */
    public static final String ACTION_COVER_REQUEST = "io.github.melolock.action.COVER_REQUEST";
    /** 封面 JPEG 字节数组的 extra 键；缺省该 extra 表示清除。 */
    public static final String EXTRA_COVER_JPEG = "art";

    // ── 锁屏三元素（时间 / 专辑封面 / 播放器）的可编辑参数 ──────────────────────
    // 键名同时用作 SharedPreferences 键与 Provider 的 key 列，值统一按字符串存取，
    // 这样新增参数不需要再改 Provider 的列投影。

    public static final String CLOCK_SIZE = "clock_text_size_dp";
    public static final String CLOCK_SPACING = "clock_spacing_dp";
    public static final String CLOCK_WEIGHT = "clock_weight";
    public static final String CLOCK_COLOR = "clock_color";
    public static final String CLOCK_ROUNDNESS = "clock_roundness";
    /**
     * 时钟描边加粗（dp，0＝关）。圆体可变字体的 wght 轴有上限（中等圆 300~700、很圆 400~800，
     * 滑杆拉到 900 会被字体钳制），「更粗」只能靠 FILL_AND_STROKE 在字形外圈补一圈。
     * 0 就是字体拉满的原样，往大持续变粗。
     */
    public static final String CLOCK_STROKE = "clock_stroke_dp";
    public static final String COVER_SCALE = "cover_scale_percent";
    public static final String COVER_WIDTH = "cover_width_dp";
    public static final String COVER_HEIGHT = "cover_height_dp";
    public static final String COVER_SPACING = "cover_spacing_dp";
    public static final String CARD_SCALE = "card_scale_percent";
    public static final String CARD_WIDTH = "card_width_dp";
    public static final String CARD_HEIGHT = "card_height_dp";
    public static final String CARD_RADIUS = "card_radius_dp";
    public static final String CARD_SPACING = "card_spacing_dp";
    /**
     * 播放器卡片底色（ARGB int）。默认＝历史硬编码的近黑 0xF2181818，保持老配置外观不变。
     * 覆盖层按底色亮度自动联动卡片内文字 / 进度条配色（浅底配深字）。
     */
    public static final String CARD_BG = "card_bg";
    /**
     * 沉浸场景显示期间是否吃掉左侧下拉手势（左边通知栏）。
     *
     * 不是几何参数，借用 /elements 的 key/value 通道（SystemUI 侧一次查询就能读到）。
     * 两个「通知栏是否展开」的信号在真机上都被证伪，所以改成直接从源头堵住手势。
     */
    public static final String BLOCK_LEFT_SHADE = "block_left_shade";

    // 顶部日期行 / 自定义签名行（两行共存，各自独立开关）。日期行内容＝「公历+周几 · 农历」，
    // 由覆盖层用 ICU ChineseCalendar 换算。不提供「字体圆润」：圆体字体只含数字，对汉字无效。
    public static final String DATE_ENABLED = "date_enabled";
    public static final String DATE_SIZE = "date_text_size_sp";
    public static final String DATE_WEIGHT = "date_weight";
    public static final String DATE_COLOR = "date_color";
    public static final String DATE_SPACING = "date_spacing_dp";
    public static final String SIGN_ENABLED = "signature_enabled";
    public static final String SIGN_SIZE = "sign_text_size_sp";
    public static final String SIGN_WEIGHT = "sign_weight";
    public static final String SIGN_COLOR = "sign_color";
    public static final String SIGN_SPACING = "sign_spacing_dp";

    /** 底部「展开通知」入口胶囊：文字色与背景色（0＝跟随封面）。默认近似系统胶囊观感。 */
    public static final String ENTRY_COLOR = "entry_color";
    public static final String ENTRY_BG = "entry_bg";

    /**
     * 各「跟随封面」档的取色风格：0＝低饱和磨砂（M3E，主色压饱和做容器/弱化做文字），
     * 1＝鲜艳（主色原色直出，只调亮度保证可读）。每个可调颜色的项独立一份。
     * 仅在对应颜色选「跟随封面」时生效；进 elementSignature，改动触发整场重建。
     */
    public static final String CARD_BG_PICK = "card_bg_pick";
    public static final String CLOCK_PICK = "clock_pick";
    public static final String DATE_PICK = "date_pick";
    public static final String SIGN_PICK = "sign_pick";
    public static final String ENTRY_COLOR_PICK = "entry_color_pick";
    public static final String ENTRY_BG_PICK = "entry_bg_pick";

    /**
     * 主色来源（全局一项，所有跟随档共用同一主色保证色相统一）：
     * 0＝最鲜艳优先（Palette 鲜艳桶 vibrant→darkVibrant→lightVibrant→muted→…，现状），
     * 1＝占比优先（直接取 population 最大的色块；2026-10-09 起不再在 top5 里挑鲜艳——
     * 旧版两档挑出来的色大多相同，用户感知不到差别），
     * 2＝色族占比（近似色归并成族再比人口：有彩色按色相 ≤30° 归族、无彩色不参赛，
     * 彩族总人口 ≥15% 才有资格赢、族代表取族内最大桶；专治照片类封面「绿草被拆桶、
     * 阴影黑桶抢赢」「小红标偷家」，2026-10-10 zuige 四张样张定标）。
     */
    public static final String SWATCH_PICK = "swatch_pick";

    /**
     * 切歌柔和过渡总开关（外观页「全局」分组，默认开）：
     * 1＝换歌时封面交叉过渡、文字淡出淡入、跟随配色渐变；
     * 0＝全部瞬时切换（旧版行为）。进 elementSignature，改开关触发整场重建。
     */
    public static final String SONG_FADE = "song_fade";

    /**
     * 组件动效开关（外观页「动效」分组，均默认开）：
     * button_feedback＝控制按钮按压缩放回弹 + 播放/暂停切换弹跳；
     * smooth_progress＝进度条在两次采样之间平滑推进（跳变 >15% 视为切歌/拖动，直接落位）；
     * cover_pop＝换歌时大封面缩放弹入（需 song_fade 同时开启，弹入叠加在交叉过渡上）；
     * player_card_pop＝换歌时播放器卡片与大封面同款缩放弹入（独立开关）。
     */
    public static final String BUTTON_FEEDBACK = "button_feedback";
    public static final String SMOOTH_PROGRESS = "smooth_progress";
    public static final String COVER_POP = "cover_pop";
    public static final String PLAYER_CARD_POP = "player_card_pop";

    /**
     * 播放键图标风格（外观页「播放器」分组，默认开）：
     * player_vector_icons＝1 用矢量圆润图标（双三角/实心块），0 回退旧版字符播放键「◀ Ⅱ ▶ ⌁ ♡ ▣」。
     */
    public static final String PLAYER_VECTOR_ICONS = "player_vector_icons";

    /**
     * 跑马灯滚动速度（外观页「播放器」分组，默认 45）：
     * 单位 dp/s，仅当歌名或歌手文本溢出卡片宽度时启用跑马灯。
     * 取值 20~120，值越大滚得越快。
     */
    public static final String MARQUEE_SPEED = "marquee_speed";

    /**
     * 跑马灯滚动前停顿（外观页「播放器」分组，默认 15）：
     * 单位秒，文本滚动到末尾后停留多久再跳回开头重新开始。
     * 取值 5~60。
     */
    public static final String MARQUEE_PAUSE = "marquee_pause";

    /**
     * 调试日志开关（「设置」页底部「调试」分组，默认关）：
     * 开＝SystemUI 侧输出周期性细节日志（每轮媒体快照的 Session/Media ready、逐帧 render 跳过、
     * 逐句歌词的取色结果等）；关＝只留生命周期/状态变化/错误级日志。
     * 平时关闭防止刷爆 logcat 缓冲（媒体通知日志几分钟就能把主缓冲轮转空的教训，2026-10-10）；
     * 排查问题时在 App 里一开，adb 抓到的就全是干货。
     */
    public static final String DEBUG_LOG = "debug_log";

    /**
     * 隐藏锁屏底部快捷栏（手电筒 / 相机那一排）。
     *
     * 2026-10-11 zuige 要求：沉浸场景要干净，图标和它们的触摸响应一起去掉。
     * 打开后我们的「展开通知 / 返回播放器」入口不能再按快捷栏实测坐标对齐（那排视图已经
     * GONE），改走几何兜底——见 `LockScreenOverlay.alignEntryWithShortcutRow()`。
     * 默认关：动的是系统自带的相机/手电筒入口，属于激进改动，交给用户选。
     */
    public static final String HIDE_SHORTCUTS = "hide_shortcuts";

    /**
     * 隐藏锁屏底部充电/提示文案（外观页「全局」分组，默认开＝隐藏）：
     * 目标是原生 keyguard_indication_text_bottom（「正在充电 XX%」/ 指纹提示那一行）。
     * 模块关闭或场景销毁时复原原生视图。
     */
    public static final String HIDE_CHARGING = "hide_charging";

    /**
     * 原生播放器模式（外观页「播放器」分组，默认关）：
     * 开＝沉浸锁屏不再显示自制播放器卡片，直接显示原生通知栈（原生媒体卡在其中，
     * 已支持染色）；关＝现状自制播放器。
     */
    public static final String NATIVE_PLAYER = "native_player";

    /**
     * 通知卡跟随封面（notify_card_tint，默认开）。
     *
     * 展开通知时原生媒体卡与通知卡是系统黑底/白底，和沉浸背景不搭。打开后按封面主色
     * 给它们染色（SRC_ATOP，保留系统圆角与内容），关掉恢复原生。2026-10-11 真机普查：
     * 媒体卡背景＝`media_bg`（ImageView），通知卡＝`backgroundNormal`/`backgroundDimmed`。
     */
    /** 调试用：锁屏状态下永不息屏（root 挂 keepScreenOn），方便盯着通知页反复调试。 */
    public static final String STAY_AWAKE = "stay_awake";
    /** 通知卡跟随封面（notify_card_tint，默认开）：通知页 scrim + 媒体卡按封面主色染色。 */
    public static final String NOTIFY_CARD_TINT = "notify_card_tint";
    /**
     * 通知页底色（notify_card_bg / notify_card_bg_pick，2026-10-11 新增，默认 0/1）：
     * 通知页染色不再强制与播放器卡同源——0＝全局取色（独立走 *_pick 三档取色风格），
     * 其余值与 CARD_BG 手选档同表（0xF2 不透明 ARGB）。总开关仍是 notify_card_tint。
     */
    public static final String NOTIFY_CARD_BG = "notify_card_bg";
    public static final String NOTIFY_CARD_BG_PICK = "notify_card_bg_pick";

    /** 字符串值元素：签名正文。走同一条 /elements 通道（value 列本来就是字符串形式），但不进整数解析。 */
    public static final String DATE_SIGNATURE = "date_signature";

    /** 宽/高为 0 表示“跟随默认”，由覆盖层按屏幕计算。 */
    private static final Map<String, Integer> ELEMENT_DEFAULTS = new LinkedHashMap<>();
    /**
     * 元素默认值＝真机（gauguinpro）调定的一套抄回（2026-10-12 第三次同步，v0.3.4）：
     * 时钟 80/900/跟随封面、封面缩放 125%、卡片 369x180/底色跟随封面（原色档）、
     * 日期 22/520/跟随封面/鲜艳取色/距顶 50、主色来源**色族占比**、
     * 入口胶囊背景跟随封面（**原色**档）、通知页染色开（低饱和档）、
     * 播放器卡片弹入默认**关**。
     * 签名例外：默认**关闭且内容空白**（用户指定），不随真机。
     * debug_log 例外：真机为 1 属调试残留，发布默认仍为 0（不随真机）。
     * entry_color / entry_color_pick 已废弃（入口文字固定算法黑白），默认值随真机同步为 0，
     * 键保留只为旧配置读取兼容。
     * 存量用户 SharedPreferences 已有值不受影响，默认值只对新装生效。
     */
    static {
        ELEMENT_DEFAULTS.put(CLOCK_SIZE, 80);
        ELEMENT_DEFAULTS.put(CLOCK_SPACING, 0);
        ELEMENT_DEFAULTS.put(CLOCK_WEIGHT, 900);
        ELEMENT_DEFAULTS.put(CLOCK_COLOR, 0);
        ELEMENT_DEFAULTS.put(CLOCK_ROUNDNESS, 0);
        ELEMENT_DEFAULTS.put(CLOCK_STROKE, 0);
        ELEMENT_DEFAULTS.put(COVER_SCALE, 125);
        ELEMENT_DEFAULTS.put(COVER_WIDTH, 0);
        ELEMENT_DEFAULTS.put(COVER_HEIGHT, 0);
        ELEMENT_DEFAULTS.put(COVER_SPACING, 10);
        ELEMENT_DEFAULTS.put(CARD_SCALE, 100);
        ELEMENT_DEFAULTS.put(CARD_WIDTH, 369);
        ELEMENT_DEFAULTS.put(CARD_HEIGHT, 180);
        ELEMENT_DEFAULTS.put(CARD_RADIUS, 28);
        ELEMENT_DEFAULTS.put(CARD_SPACING, 20);
        ELEMENT_DEFAULTS.put(CARD_BG, 0);
        ELEMENT_DEFAULTS.put(BLOCK_LEFT_SHADE, 1);
        ELEMENT_DEFAULTS.put(DATE_ENABLED, 1);
        ELEMENT_DEFAULTS.put(DATE_SIZE, 22);
        ELEMENT_DEFAULTS.put(DATE_WEIGHT, 520);
        ELEMENT_DEFAULTS.put(DATE_COLOR, 0);
        ELEMENT_DEFAULTS.put(DATE_SPACING, 50);
        ELEMENT_DEFAULTS.put(SIGN_ENABLED, 0);
        ELEMENT_DEFAULTS.put(SIGN_SIZE, 16);
        ELEMENT_DEFAULTS.put(SIGN_WEIGHT, 500);
        ELEMENT_DEFAULTS.put(SIGN_COLOR, 0);
        ELEMENT_DEFAULTS.put(SIGN_SPACING, 8);
        ELEMENT_DEFAULTS.put(ENTRY_COLOR, 0);            // 已废弃（入口文字固定算法黑白），键保留兼容旧配置
        ELEMENT_DEFAULTS.put(ENTRY_BG, 0);
        ELEMENT_DEFAULTS.put(CARD_BG_PICK, 1);
        ELEMENT_DEFAULTS.put(CLOCK_PICK, 1);
        ELEMENT_DEFAULTS.put(DATE_PICK, 1);
        ELEMENT_DEFAULTS.put(SIGN_PICK, 1);
        ELEMENT_DEFAULTS.put(ENTRY_COLOR_PICK, 0);       // 已废弃，同上
        ELEMENT_DEFAULTS.put(ENTRY_BG_PICK, 1);          // 原色（2026-10-12 定稿：与播放器卡一致，低饱和观感偏灰）
        ELEMENT_DEFAULTS.put(SWATCH_PICK, 2);            // 色族占比（2026-10-12 真机同步）
        ELEMENT_DEFAULTS.put(SONG_FADE, 1);
        ELEMENT_DEFAULTS.put(BUTTON_FEEDBACK, 1);
        ELEMENT_DEFAULTS.put(SMOOTH_PROGRESS, 1);
        ELEMENT_DEFAULTS.put(COVER_POP, 1);
        ELEMENT_DEFAULTS.put(PLAYER_CARD_POP, 0);        // 默认关（2026-10-12 真机同步）
        ELEMENT_DEFAULTS.put(PLAYER_VECTOR_ICONS, 1);
        ELEMENT_DEFAULTS.put(MARQUEE_SPEED, 45);          // dp/s，20~120
        ELEMENT_DEFAULTS.put(MARQUEE_PAUSE, 15);          // 秒，5~60
        ELEMENT_DEFAULTS.put(DEBUG_LOG, 0);
        ELEMENT_DEFAULTS.put(HIDE_SHORTCUTS, 0);
        ELEMENT_DEFAULTS.put(HIDE_CHARGING, 1);           // 默认隐藏（zuige 2026-10-14：底部充电文案默认不要）
        ELEMENT_DEFAULTS.put(NATIVE_PLAYER, 0);
        ELEMENT_DEFAULTS.put(STAY_AWAKE, 0);
        ELEMENT_DEFAULTS.put(NOTIFY_CARD_TINT, 1);
        ELEMENT_DEFAULTS.put(NOTIFY_CARD_BG, 0);
        ELEMENT_DEFAULTS.put(NOTIFY_CARD_BG_PICK, 1);
    }

    /** 字符串值元素的默认值；{@link #elementKeys()} 会把这里面的键也导出到 /elements。 */
    private static final Map<String, String> TEXT_ELEMENT_DEFAULTS = new LinkedHashMap<>();
    static {
        TEXT_ELEMENT_DEFAULTS.put(DATE_SIGNATURE, "");
    }

    private Config() {}

    /** /elements 导出的全部键：整数元素 + 字符串元素（签名正文）。 */
    public static Set<String> elementKeys() {
        Set<String> keys = new LinkedHashSet<>(ELEMENT_DEFAULTS.keySet());
        keys.addAll(TEXT_ELEMENT_DEFAULTS.keySet());
        return Collections.unmodifiableSet(keys);
    }

    public static int elementDefault(String key) {
        Integer value = ELEMENT_DEFAULTS.get(key);
        return value == null ? 0 : value;
    }

    /** 本地读取原始字符串；未设置时返回默认值，因此调用方永远拿得到可解析的值。 */
    public static String elementString(Context context, String key) {
        String raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(key, null);
        if (raw != null) return raw;
        String textDefault = TEXT_ELEMENT_DEFAULTS.get(key);
        return textDefault != null ? textDefault : String.valueOf(elementDefault(key));
    }

    public static int elementInt(Context context, String key) {
        return parseElement(elementString(context, key), elementDefault(key));
    }

    public static boolean elementBool(Context context, String key) { return elementInt(context, key) != 0; }

    public static void setElementInt(Context context, String key, int value) {
        write(context, key, String.valueOf(value));
    }

    /** 写字符串值元素（签名正文）。写盘触发 notifyChange，覆盖层靠 elementSignature 里的签名段感知变化。 */
    public static void setElementText(Context context, String key, String value) {
        write(context, key, value == null ? "" : value);
    }

    /**
     * 读取字符串值元素（签名正文）。SystemUI 侧与 {@link #elementValues} 一样走 /elements
     * 查询；签名不在整数解析表里，需要单独取（create 时一次，代价可忽略）。
     */
    public static String elementText(Context context, String key) {
        if (PACKAGE.equals(context.getPackageName())) {
            return elementString(context, key);
        }
        try (Cursor cursor = context.getContentResolver().query(ELEMENTS_URI, null, null, null, null)) {
            if (cursor != null) {
                while (cursor.moveToNext()) {
                    if (key.equals(cursor.getString(0))) {
                        String raw = cursor.getString(1);
                        return raw != null ? raw : TEXT_ELEMENT_DEFAULTS.getOrDefault(key, "");
                    }
                }
            }
        } catch (RuntimeException error) { /* 读不到走默认值，失败关闭 */ }
        return TEXT_ELEMENT_DEFAULTS.getOrDefault(key, "");
    }

    public static int parseElement(String raw, int fallback) {
        if (raw == null) return fallback;
        try { return Integer.parseInt(raw.trim()); } catch (NumberFormatException error) { return fallback; }
    }

    /**
     * SystemUI 侧一次查询拿到全部元素配置（已解析为整数）。
     *
     * 读不到时返回空表，覆盖层用各自的默认值兜底，不会因此撤掉沉浸页。
     */
    public static Map<String, Integer> elementValues(Context context) {
        Map<String, String> raw = new HashMap<>();
        if (PACKAGE.equals(context.getPackageName())) {
            for (String key : ELEMENT_DEFAULTS.keySet()) raw.put(key, elementString(context, key));
        } else {
            Map<String, String> remote = new HashMap<>();
            try (Cursor cursor = context.getContentResolver().query(ELEMENTS_URI, null, null, null, null)) {
                if (cursor != null) {
                    while (cursor.moveToNext()) remote.put(cursor.getString(0), cursor.getString(1));
                }
            } catch (RuntimeException error) { /* 空表 → 全部走默认值 */ }
            raw.putAll(remote);
        }
        Map<String, Integer> values = new HashMap<>();
        for (String key : ELEMENT_DEFAULTS.keySet()) {
            values.put(key, parseElement(raw.get(key), elementDefault(key)));
        }
        return values;
    }

    public static boolean enabled(Context context) {
        Boolean value = enabledOrNull(context);
        return value != null && value;
    }

    /**
     * 取开关状态，**读不到时返回 `null`**（与「读到关闭」区分开）。
     *
     * 为什么要单独一个方法：SystemUI 侧只能通过 ContentProvider 读，而 Provider 在配置端
     * 正在写盘/被系统整理时可能**偶发**查询失败。旧写法把异常一律当「关闭」，于是配置端
     * 每改一次外观（每次写盘都会 notifyChange），SystemUI 就有一小段概率把模块判成关闭、
     * 直接 `restore()` 撤掉场景——用户看到的是「改完设置锁屏上就什么都没有了」 /
     * 「改完没反应」，而且「有时有、有时没有」。所以：读到 false 照旧关闭（失败关闭原则不变），
     * 读失败则保持上一次的状态不动，并打日志。
     */
    public static Boolean enabledOrNull(Context context) {
        // **TTL 缓存（2026-10-12）**：这个方法在 SystemUI 的**主线程**上被每个媒体回调调一次
        // （两个 MediaSource 各一份 ≈ 每秒 1 次跨进程查询）。查询本身要打进配置端进程，而配置端
        // 进程随时可能正忙（前台 UI / GC）或被 MIUI SmartPower 冻结在 idle（每次唤醒要 3 秒）——
        // binder 同步等它，SystemUI 主线程就被拖住，主观表现正是「开屏卡一下」（12:55:28 实测：
        // 配置端 GC 后 100ms 用户按电源，SystemUI 同帧掉 40 帧）。缓存 1 秒不损语义：
        // 开关变化有 ContentObserver 推送（switchObserver.onChange 会先失效缓存再走 updateSwitch），
        // render() 里重读本来只是对「漏推送」的兜底。
        long now = android.os.SystemClock.elapsedRealtime();
        Boolean cached = cachedEnabled;
        if (cached != null && now - cachedEnabledAtMs < ENABLED_TTL_MS) return cached;
        Boolean fresh = readEnabled(context);
        cachedEnabled = fresh;
        cachedEnabledAtMs = now;
        return fresh;
    }
    /** 配置端进程随时可能忙/被冻结，跨进程读必须节制；见 {@link #enabledOrNull}。 */
    private static final long ENABLED_TTL_MS = 1000;
    private static volatile Boolean cachedEnabled;
    private static volatile long cachedEnabledAtMs;

    /** {@link #enabledOrNull} 的无缓存本体。 */
    private static Boolean readEnabled(Context context) {
        if (PACKAGE.equals(context.getPackageName())) {
            return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY, false);
        }
        try (Cursor cursor = context.getContentResolver().query(URI, null, null, null, null)) {
            return cursor != null && cursor.moveToFirst() && cursor.getInt(0) == 1;
        } catch (RuntimeException error) { return null; }
    }

    /** 配置可能已变（ContentObserver 收到推送 / 配置端主动写盘）时调用：下下一次读走真查询。 */
    public static void invalidateEnabledCache() {
        cachedEnabled = null;
        cachedEnabledAtMs = 0;
    }
    public static boolean setEnabled(Context context, boolean enabled) {
        try {
            if (!context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                    .putBoolean(KEY, enabled).commit()) return false;
            invalidateEnabledCache();   // 同进程读也走缓存（App 端 UI），写完必须立即可见
            context.getContentResolver().notifyChange(URI, null);
            return true;
        } catch (RuntimeException error) { return false; }
    }
    public static int cornerRadiusDp(Context context) {
        if (PACKAGE.equals(context.getPackageName()))
            return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .getInt(CORNER_RADIUS, 28);
        try (Cursor cursor = context.getContentResolver().query(URI, null, null, null, null)) {
            return cursor != null && cursor.moveToFirst() ? cursor.getInt(1) : 28;
        } catch (RuntimeException error) { return 28; }
    }
    public static boolean setCornerRadiusDp(Context context, int radiusDp) {
        if (radiusDp < 0 || radiusDp > 48) return false;
        try {
            if (!context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                    .putInt(CORNER_RADIUS, radiusDp).commit()) return false;
            context.getContentResolver().notifyChange(URI, null);
            return true;
        } catch (RuntimeException error) { return false; }
    }

    public static Set<String> allowedPackages(Context context) {
        String raw = readString(context, ALLOWED_PACKAGES, "");
        if (raw.isEmpty()) return Collections.emptySet();
        return new HashSet<>(Arrays.asList(raw.split("\\n")));
    }

    /** 已勾选的播放器包名；未设置（表示不限制）时返回空集，已全部取消时也返回空集。 */
    public static Set<String> selectedPackages(Context context) {
        Set<String> packages = allowedPackages(context);
        packages.remove(NONE);
        return packages;
    }

    /** 是否已把所有播放器都取消勾选（与“从未设置”区分开）。 */
    public static boolean allPackagesDisabled(Context context) {
        return NONE.equals(readString(context, ALLOWED_PACKAGES, ""));
    }

    /**
     * 首页数据卡用的已开启应用数。
     *
     * @return 实际勾选数量；未选中任何播放器时为 0（此时不启用沉浸锁屏）。
     */
    public static int enabledAppCount(Context context) {
        return selectedPackages(context).size();
    }

    /**
     * 是否允许这个播放器驱动沉浸锁屏。
     *
     * **必须在「音乐应用」页显式勾选**：旧的语义是「未设置＝允许全部」，那样用户还
     * 没做任何选择时任何 App 的 MediaSession 都能拉起覆盖层。现在未设置（空值）与
     * 全部取消（哨兵 `NONE`）一律不放行，只有真正勾选过的包名才通过。
     */
    public static boolean packageAllowed(Context context, String packageName) {
        String raw = readString(context, ALLOWED_PACKAGES, "");
        if (raw.isEmpty() || NONE.equals(raw)) return false;
        return allowedPackages(context).contains(packageName);
    }

    public static boolean setAllowedPackages(Context context, Set<String> packages) {
        StringBuilder value = new StringBuilder();
        for (String packageName : packages) {
            if (value.length() > 0) value.append('\n');
            value.append(packageName);
        }
        return write(context, ALLOWED_PACKAGES, value.length() == 0 ? NONE : value.toString());
    }

    public static int overlayStyle(Context context) { return readInt(context, OVERLAY_STYLE, 0); }
    public static int overlayColor(Context context) { return readInt(context, OVERLAY_COLOR, 0xFF111827); }
    public static int overlayAlpha(Context context) { return readInt(context, OVERLAY_ALPHA, 150); }

    public static boolean setOverlayStyle(Context context, int value) { return write(context, OVERLAY_STYLE, value); }
    public static boolean setOverlayColor(Context context, int value) { return write(context, OVERLAY_COLOR, value); }
    public static boolean setOverlayAlpha(Context context, int value) { return value >= 0 && value <= 255 && write(context, OVERLAY_ALPHA, value); }

    private static String readString(Context context, String key, String fallback) {
        if (PACKAGE.equals(context.getPackageName()))
            return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(key, fallback);
        try (Cursor cursor = context.getContentResolver().query(URI, null, null, null, null)) {
            return cursor != null && cursor.moveToFirst() ? cursor.getString(5) : fallback;
        } catch (RuntimeException error) { return fallback; }
    }

    private static int readInt(Context context, String key, int fallback) {
        if (PACKAGE.equals(context.getPackageName()))
            return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(key, fallback);
        try (Cursor cursor = context.getContentResolver().query(URI, null, null, null, null)) {
            if (cursor == null || !cursor.moveToFirst()) return fallback;
            int index = OVERLAY_STYLE.equals(key) ? 2 : OVERLAY_COLOR.equals(key) ? 3 : 4;
            return cursor.getInt(index);
        } catch (RuntimeException error) { return fallback; }
    }

    private static boolean write(Context context, String key, Object value) {
        try {
            android.content.SharedPreferences.Editor editor = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit();
            if (value instanceof Integer) editor.putInt(key, (Integer) value);
            else editor.putString(key, String.valueOf(value));
            if (!editor.commit()) return false;
            context.getContentResolver().notifyChange(URI, null);
            return true;
        } catch (RuntimeException error) { return false; }
    }
}
