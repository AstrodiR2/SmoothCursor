package com.smoothcursor;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;

public class SettingsProvider extends ContentProvider {

    public static final String PREFS = "cfg";
    public static final String K_USE_THEME = "use_theme";
    public static final String K_COLOR = "color";
    public static final String K_OUTLINE = "outline";
    public static final String K_TRAIL = "trail";
    public static final String K_SMOOTH_SCROLL = "smooth_scroll";
    public static final String K_SCROLL_SPEED = "scroll_speed";

    public static final boolean D_USE_THEME = true;
    public static final int D_COLOR = 0xFFFFFFFF;
    public static final int D_OUTLINE = 0;
    public static final int D_TRAIL = 50;
    public static final boolean D_SMOOTH_SCROLL = true;
    public static final int D_SCROLL_SPEED = 50;

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        if (!"get".equals(method) || getContext() == null) return null;
        SharedPreferences sp = getContext().getSharedPreferences(PREFS, 0);
        Bundle b = new Bundle();
        b.putBoolean(K_USE_THEME, sp.getBoolean(K_USE_THEME, D_USE_THEME));
        b.putInt(K_COLOR, sp.getInt(K_COLOR, D_COLOR));
        b.putInt(K_OUTLINE, sp.getInt(K_OUTLINE, D_OUTLINE));
        b.putInt(K_TRAIL, sp.getInt(K_TRAIL, D_TRAIL));
        b.putBoolean(K_SMOOTH_SCROLL, sp.getBoolean(K_SMOOTH_SCROLL, D_SMOOTH_SCROLL));
        b.putInt(K_SCROLL_SPEED, sp.getInt(K_SCROLL_SPEED, D_SCROLL_SPEED));
        return b;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        return null;
    }

    @Override
    public String getType(Uri uri) {
        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }
}
