package com.smoothcursor;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;

import java.io.File;
import java.io.FileNotFoundException;

public class SettingsProvider extends ContentProvider {

    public static final String PREFS = "cfg";
    public static final String K_USE_THEME = "use_theme";
    public static final String K_COLOR = "color";
    public static final String K_OUTLINE = "outline";
    public static final String K_TRAIL = "trail";
    public static final String K_SMOOTH_SCROLL = "smooth_scroll";
    public static final String K_SCROLL_SPEED = "scroll_speed";

    // Отступ внутри окна терминала (dp)
    public static final String K_PAD_ON = "pad_on";
    public static final String K_PAD_X = "pad_x";
    public static final String K_PAD_Y = "pad_y";
    // Свечение текста
    public static final String K_GLOW_ON = "glow_on";
    public static final String K_GLOW_RADIUS = "glow_radius";
    public static final String K_GLOW_INTENSITY = "glow_intensity";

    // Плавное появление текста
    public static final String K_FADE_ON = "fade_on";
    public static final String K_FADE_MS = "fade_ms";

    // Фон: 0 выкл, 1 картинка, 2 видео, 3 градиент
    public static final String K_BG_MODE = "bg_mode";
    public static final String K_BG_DIM = "bg_dim";
    public static final String K_BG_C1 = "bg_c1";
    public static final String K_BG_C2 = "bg_c2";
    public static final String K_BG_VER = "bg_ver";
    // Заставка при запуске
    public static final String K_SPLASH_ON = "splash_on";
    public static final String K_SPLASH_SOUND = "splash_sound";

    public static final boolean D_USE_THEME = true;
    public static final int D_COLOR = 0xFFFFFFFF;
    public static final int D_OUTLINE = 0;
    public static final int D_TRAIL = 50;
    public static final boolean D_SMOOTH_SCROLL = true;
    public static final int D_SCROLL_SPEED = 50;

    public static final boolean D_PAD_ON = false;
    public static final int D_PAD_X = 8;
    public static final int D_PAD_Y = 8;
    public static final boolean D_GLOW_ON = false;
    public static final int D_GLOW_RADIUS = 6;
    public static final int D_GLOW_INTENSITY = 70;

    public static final boolean D_FADE_ON = true;
    public static final int D_FADE_MS = 160;

    public static final int D_BG_MODE = 0;
    public static final int D_BG_DIM = 35;
    public static final int D_BG_C1 = 0xFF141E30;
    public static final int D_BG_C2 = 0xFF243B55;
    public static final boolean D_SPLASH_ON = false;
    public static final boolean D_SPLASH_SOUND = false;

    // Файлы лежат в приватной папке модуля, Termux читает их через openFile()
    public static final String F_IMG = "bg_img.jpg";
    public static final String F_VID = "bg_vid.bin";
    public static final String F_SPL = "splash.bin";

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
        b.putBoolean(K_PAD_ON, sp.getBoolean(K_PAD_ON, D_PAD_ON));
        b.putInt(K_PAD_X, sp.getInt(K_PAD_X, D_PAD_X));
        b.putInt(K_PAD_Y, sp.getInt(K_PAD_Y, D_PAD_Y));
        b.putBoolean(K_GLOW_ON, sp.getBoolean(K_GLOW_ON, D_GLOW_ON));
        b.putInt(K_GLOW_RADIUS, sp.getInt(K_GLOW_RADIUS, D_GLOW_RADIUS));
        b.putInt(K_GLOW_INTENSITY, sp.getInt(K_GLOW_INTENSITY, D_GLOW_INTENSITY));
        b.putBoolean(K_FADE_ON, sp.getBoolean(K_FADE_ON, D_FADE_ON));
        b.putInt(K_FADE_MS, sp.getInt(K_FADE_MS, D_FADE_MS));
        b.putInt(K_BG_MODE, sp.getInt(K_BG_MODE, D_BG_MODE));
        b.putInt(K_BG_DIM, sp.getInt(K_BG_DIM, D_BG_DIM));
        b.putInt(K_BG_C1, sp.getInt(K_BG_C1, D_BG_C1));
        b.putInt(K_BG_C2, sp.getInt(K_BG_C2, D_BG_C2));
        b.putInt(K_BG_VER, sp.getInt(K_BG_VER, 0));
        b.putBoolean(K_SPLASH_ON, sp.getBoolean(K_SPLASH_ON, D_SPLASH_ON));
        b.putBoolean(K_SPLASH_SOUND, sp.getBoolean(K_SPLASH_SOUND, D_SPLASH_SOUND));
        return b;
    }

    // content://com.smoothcursor.settings/img | /vid | /splash, только чтение
    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (getContext() == null) throw new FileNotFoundException("no context");
        String seg = uri.getLastPathSegment();
        String name;
        if ("img".equals(seg)) name = F_IMG;
        else if ("vid".equals(seg)) name = F_VID;
        else if ("splash".equals(seg)) name = F_SPL;
        else throw new FileNotFoundException("unknown: " + seg);
        File f = new File(getContext().getFilesDir(), name);
        if (!f.exists()) throw new FileNotFoundException("no file: " + name);
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY);
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
