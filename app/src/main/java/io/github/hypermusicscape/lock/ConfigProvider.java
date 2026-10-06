package io.github.hypermusicscape.lock;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import java.util.Set;

/** Exposes read-only display preferences; only this app can change them. */
public final class ConfigProvider extends ContentProvider {
    @Override public boolean onCreate() { return true; }
    @Override public Cursor query(Uri uri, String[] projection, String selection,
                                  String[] selectionArgs, String sortOrder) {
        if (Config.ELEMENTS_URI.equals(uri)) return queryElements();
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

    @Override public String getType(Uri uri) {
        requireKnown(uri);
        return "vnd.android.cursor.item/vnd." + Config.AUTHORITY + ".state";
    }
    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri uri, String selection, String[] args) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] args) {
        throw new UnsupportedOperationException();
    }
    private static void requireState(Uri uri) {
        if (!Config.URI.equals(uri)) throw new IllegalArgumentException("Unknown config URI");
    }
    private static void requireKnown(Uri uri) {
        if (!Config.URI.equals(uri) && !Config.ELEMENTS_URI.equals(uri)) throw new IllegalArgumentException("Unknown config URI");
    }
}
