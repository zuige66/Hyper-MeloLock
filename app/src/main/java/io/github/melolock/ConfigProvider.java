package io.github.melolock;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import java.util.Map;
import java.util.Set;

/**
 * 配置读取（/state、/elements）对 SystemUI 只读；/runtime 是唯一可写的通道，
 * 方向为 SystemUI → App：HookEntry 把框架版本回报进来，App 端显示用。
 */
public final class ConfigProvider extends ContentProvider {
    /** /runtime 落在这个 prefs 文件里持久化（SystemUI 重启后 App 首次打开也能读到）。 */
    private static final String RUNTIME_PREFS = "runtime_state";

    @Override public boolean onCreate() { return true; }
    @Override public Cursor query(Uri uri, String[] projection, String selection,
                                  String[] selectionArgs, String sortOrder) {
        if (Config.ELEMENTS_URI.equals(uri)) return queryElements();
        if (Config.RUNTIME_URI.equals(uri)) return queryRuntime();
        requireState(uri);
        MatrixCursor cursor = new MatrixCursor(new String[] { "enabled", "corner_radius_dp", "overlay_style", "overlay_color", "overlay_alpha", "allowed_packages" }, 1);
        cursor.addRow(new Object[] { Config.enabled(getContext()) ? 1 : 0,
                Config.cornerRadiusDp(getContext()), Config.overlayStyle(getContext()),
                Config.overlayColor(getContext()), Config.overlayAlpha(getContext()),
                String.join("\n", Config.allowedPackages(getContext())) });
        cursor.setNotificationUri(getContext().getContentResolver(), Config.URI);
        return cursor;
    }

    /** 锁屏三元素配置：key/value 两列，新增参数不需要改这里的投影。 */
    private Cursor queryElements() {
        Set<String> keys = Config.elementKeys();
        MatrixCursor cursor = new MatrixCursor(new String[] { "key", "value" }, keys.size());
        for (String key : keys) cursor.addRow(new Object[] { key, Config.elementString(getContext(), key) });
        cursor.setNotificationUri(getContext().getContentResolver(), Config.URI);
        return cursor;
    }

    /** SystemUI 回报的运行时状态：key/value 两列，与 /elements 同形。 */
    private Cursor queryRuntime() {
        SharedPreferences prefs = getContext().getSharedPreferences(RUNTIME_PREFS, 0);
        Map<String, ?> all = prefs.getAll();
        MatrixCursor cursor = new MatrixCursor(new String[] { "key", "value" }, all.size());
        for (Map.Entry<String, ?> entry : all.entrySet()) {
            cursor.addRow(new Object[] { entry.getKey(), String.valueOf(entry.getValue()) });
        }
        return cursor;
    }

    @Override public String getType(Uri uri) {
        requireKnown(uri);
        return "vnd.android.cursor.item/vnd." + Config.AUTHORITY + ".state";
    }
    @Override public Uri insert(Uri uri, ContentValues values) {
        if (!Config.RUNTIME_URI.equals(uri)) throw new UnsupportedOperationException();
        if (values == null || values.isEmpty()) throw new IllegalArgumentException("Empty runtime values");
        SharedPreferences.Editor editor =
                getContext().getSharedPreferences(RUNTIME_PREFS, 0).edit();
        for (Map.Entry<String, Object> entry : values.valueSet()) {
            Object value = entry.getValue();
            if (value instanceof Boolean) editor.putBoolean(entry.getKey(), (Boolean) value);
            else if (value instanceof Integer) editor.putInt(entry.getKey(), (Integer) value);
            else if (value instanceof Long) editor.putLong(entry.getKey(), (Long) value);
            else editor.putString(entry.getKey(), String.valueOf(value));
        }
        editor.apply();
        getContext().getContentResolver().notifyChange(Config.RUNTIME_URI, null);
        return uri;
    }
    @Override public int delete(Uri uri, String selection, String[] args) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] args) {
        throw new UnsupportedOperationException();
    }
    private static void requireState(Uri uri) {
        if (!Config.URI.equals(uri)) throw new IllegalArgumentException("Unknown config URI");
    }
    private static void requireKnown(Uri uri) {
        if (!Config.URI.equals(uri) && !Config.ELEMENTS_URI.equals(uri)
                && !Config.RUNTIME_URI.equals(uri)) throw new IllegalArgumentException("Unknown config URI");
    }
}
