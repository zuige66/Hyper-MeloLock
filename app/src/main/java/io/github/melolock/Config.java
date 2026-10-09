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
     * 旧版两档挑出来的色大多相同，用户感知不到差别）。
     */
    public static final String SWATCH_PICK = "swatch_pick";

    /**
     * 切歌柔和过渡总开关（外观页「全局」分组，默认开）：
     * 1＝换歌时封面交叉过渡、文字淡出淡入、跟随配色渐变；
     * 0＝全部瞬时切换（旧版行为）。进 elementSignature，改开关触发整场重建。
     */
    public static final String SONG_FADE = "song_fade";

    /** 字符串值元素：签名正文。走同一条 /elements 通道（value 列本来就是字符串形式），但不进整数解析。 */
    public static final String DATE_SIGNATURE = "date_signature";

    /** 宽/高为 0 表示“跟随默认”，由覆盖层按屏幕计算。 */
    private static final Map<String, Integer> ELEMENT_DEFAULTS = new LinkedHashMap<>();
    /**
     * 元素默认值＝真机（gauguinpro）调定的一套抄回（2026-10-08 第二次同步，v0.3.1）：
     * 时钟 80/900/跟随封面、封面缩放 125%、卡片 369x180/底色跟随封面、
     * 日期 22/520/跟随封面/**鲜艳**取色/距顶 50、主色来源**占比优先**、入口背景跟随封面。
     * 签名例外：默认**关闭且内容空白**（用户指定），不随真机。
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
        ELEMENT_DEFAULTS.put(ENTRY_COLOR, 0xFFFFFFFF);
        ELEMENT_DEFAULTS.put(ENTRY_BG, 0);
        ELEMENT_DEFAULTS.put(CARD_BG_PICK, 1);
        ELEMENT_DEFAULTS.put(CLOCK_PICK, 1);
        ELEMENT_DEFAULTS.put(DATE_PICK, 1);
        ELEMENT_DEFAULTS.put(SIGN_PICK, 1);
        ELEMENT_DEFAULTS.put(ENTRY_COLOR_PICK, 1);
        ELEMENT_DEFAULTS.put(ENTRY_BG_PICK, 1);
        ELEMENT_DEFAULTS.put(SWATCH_PICK, 1);
        ELEMENT_DEFAULTS.put(SONG_FADE, 1);
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
        if (PACKAGE.equals(context.getPackageName())) {
            return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY, false);
        }
        try (Cursor cursor = context.getContentResolver().query(URI, null, null, null, null)) {
            return cursor != null && cursor.moveToFirst() && cursor.getInt(0) == 1;
        } catch (RuntimeException error) { return null; }
    }
    public static boolean setEnabled(Context context, boolean enabled) {
        try {
            if (!context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                    .putBoolean(KEY, enabled).commit()) return false;
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
