package io.github.melolock;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

public final class Config {
    static final String PACKAGE = "io.github.melolock";
    static final String AUTHORITY = PACKAGE + ".config";
    static final Uri URI = Uri.parse("content://" + AUTHORITY + "/state");
    static final Uri ELEMENTS_URI = Uri.parse("content://" + AUTHORITY + "/elements");
    private static final String PREFS = "module_state";
    private static final String KEY = "enabled";
    private static final String CORNER_RADIUS = "corner_radius_dp";
    private static final String ALLOWED_PACKAGES = "allowed_packages";
    private static final String OVERLAY_STYLE = "overlay_style";
    private static final String OVERLAY_COLOR = "overlay_color";
    private static final String OVERLAY_ALPHA = "overlay_alpha";
    private static final String NONE = "__NONE__";
    static final String FINGERPRINT = "Redmi/gauguinpro/gauguinpro:16/BP2A.250605.031.A3/OS3.0.303.0.WNKCNXM:user/release-keys";

    // ── 锁屏三元素（时间 / 专辑封面 / 播放器）的可编辑参数 ──────────────────────
    // 键名同时用作 SharedPreferences 键与 Provider 的 key 列，值统一按字符串存取，
    // 这样新增参数不需要再改 Provider 的列投影。

    public static final String CLOCK_SIZE = "clock_text_size_dp";
    public static final String CLOCK_SPACING = "clock_spacing_dp";
    public static final String CLOCK_WEIGHT = "clock_weight";
    public static final String CLOCK_COLOR = "clock_color";
    public static final String CLOCK_ROUNDNESS = "clock_roundness";
    public static final String COVER_SCALE = "cover_scale_percent";
    public static final String COVER_WIDTH = "cover_width_dp";
    public static final String COVER_HEIGHT = "cover_height_dp";
    public static final String COVER_LOCKED = "cover_aspect_locked";
    public static final String COVER_SPACING = "cover_spacing_dp";
    public static final String CARD_SCALE = "card_scale_percent";
    public static final String CARD_WIDTH = "card_width_dp";
    public static final String CARD_HEIGHT = "card_height_dp";
    public static final String CARD_LOCKED = "card_aspect_locked";
    public static final String CARD_RADIUS = "card_radius_dp";
    public static final String CARD_SPACING = "card_spacing_dp";
    /**
     * 沉浸场景显示期间是否吃掉左侧下拉手势（左边通知栏）。
     *
     * 不是几何参数，借用 /elements 的 key/value 通道（SystemUI 侧一次查询就能读到）。
     * 两个「通知栏是否展开」的信号在真机上都被证伪，所以改成直接从源头堵住手势。
     */
    public static final String BLOCK_LEFT_SHADE = "block_left_shade";

    /** 宽/高为 0 表示“跟随默认”，由覆盖层按屏幕计算。 */
    private static final Map<String, Integer> ELEMENT_DEFAULTS = new LinkedHashMap<>();
    static {
        ELEMENT_DEFAULTS.put(CLOCK_SIZE, 75);
        ELEMENT_DEFAULTS.put(CLOCK_SPACING, 52);
        ELEMENT_DEFAULTS.put(CLOCK_WEIGHT, 770);
        ELEMENT_DEFAULTS.put(CLOCK_COLOR, 0xFFFFFFFF);
        ELEMENT_DEFAULTS.put(CLOCK_ROUNDNESS, 0);
        ELEMENT_DEFAULTS.put(COVER_SCALE, 118);
        ELEMENT_DEFAULTS.put(COVER_WIDTH, 0);
        ELEMENT_DEFAULTS.put(COVER_HEIGHT, 0);
        ELEMENT_DEFAULTS.put(COVER_LOCKED, 1);
        ELEMENT_DEFAULTS.put(COVER_SPACING, 24);
        ELEMENT_DEFAULTS.put(CARD_SCALE, 100);
        ELEMENT_DEFAULTS.put(CARD_WIDTH, 0);
        ELEMENT_DEFAULTS.put(CARD_HEIGHT, 178);
        ELEMENT_DEFAULTS.put(CARD_LOCKED, 1);
        ELEMENT_DEFAULTS.put(CARD_RADIUS, 28);
        ELEMENT_DEFAULTS.put(CARD_SPACING, 20);
        ELEMENT_DEFAULTS.put(BLOCK_LEFT_SHADE, 1);
    }

    private Config() {}

    public static Set<String> elementKeys() { return Collections.unmodifiableSet(ELEMENT_DEFAULTS.keySet()); }

    public static int elementDefault(String key) {
        Integer value = ELEMENT_DEFAULTS.get(key);
        return value == null ? 0 : value;
    }

    /** 本地读取原始字符串；未设置时返回默认值，因此调用方永远拿得到可解析的值。 */
    public static String elementString(Context context, String key) {
        String raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(key, null);
        return raw == null ? String.valueOf(elementDefault(key)) : raw;
    }

    public static int elementInt(Context context, String key) {
        return parseElement(elementString(context, key), elementDefault(key));
    }

    public static boolean elementBool(Context context, String key) { return elementInt(context, key) != 0; }

    public static void setElementInt(Context context, String key, int value) {
        write(context, key, String.valueOf(value));
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
        if (PACKAGE.equals(context.getPackageName())) {
            return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY, false);
        }
        try (Cursor cursor = context.getContentResolver().query(URI, null, null, null, null)) {
            return cursor != null && cursor.moveToFirst() && cursor.getInt(0) == 1;
        } catch (RuntimeException error) { return false; }
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
     * @return 未设置（允许全部）时为 -1，已全部取消时为 0，其余为实际勾选数量。
     */
    public static int enabledAppCount(Context context) {
        if (allPackagesDisabled(context)) return 0;
        Set<String> packages = selectedPackages(context);
        return packages.isEmpty() ? -1 : packages.size();
    }

    /** 当前设备是否落在已验证的 SystemUI 构建指纹内，与 HookEntry 的加载门禁一致。 */
    public static boolean deviceSupported() {
        return FINGERPRINT.equals(android.os.Build.FINGERPRINT);
    }

    public static boolean packageAllowed(Context context, String packageName) {
        String raw = readString(context, ALLOWED_PACKAGES, "");
        return raw.isEmpty() || (!NONE.equals(raw) && allowedPackages(context).contains(packageName));
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
