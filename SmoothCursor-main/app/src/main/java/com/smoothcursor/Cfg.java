package com.smoothcursor;

import android.os.Bundle;

/**
 * Все новые настройки в одном месте: ключи, значения по умолчанию и разбор Bundle,
 * который приходит из SettingsProvider. Читается в процессе Termux.
 */
final class Cfg {

    private Cfg() {
    }

    // ---- Цвета курсора по режимам
    static final String K_CM_ON = "cm_on";
    static final String K_CM_NORMAL = "cm_normal";
    static final String K_CM_TYPING = "cm_typing";
    static final String K_CM_ENTER = "cm_enter";
    static final String K_CM_SELECT = "cm_select";
    static final String K_CM_APP = "cm_app";

    // ---- Плавное мигание
    static final String K_BL_ON = "bl_on";
    static final String K_BL_PERIOD = "bl_period";
    static final String K_BL_MIN = "bl_min";
    static final String K_BL_HOLD = "bl_hold";

    // ---- Форма и свечение
    static final String K_CR_ROUND = "cr_round";
    static final String K_GL_STR = "gl_str";
    static final String K_GL_RADIUS = "gl_radius";
    static final String K_GL_PULSE = "gl_pulse";
    static final String K_GL_CUSTOM = "gl_custom";
    static final String K_GL_COLOR = "gl_color";

    // ---- Частицы
    static final String K_PT_ON = "pt_on";
    static final String K_PT_STYLE = "pt_style";
    static final String K_PT_COUNT = "pt_count";
    static final String K_PT_SIZE = "pt_size";
    static final String K_PT_LIFE = "pt_life";
    static final String K_PT_SPREAD = "pt_spread";
    static final String K_PT_SPEED = "pt_speed";
    static final String K_PT_CMODE = "pt_cmode";
    static final String K_PT_COLOR = "pt_color";
    static final String K_PT_MOVE = "pt_move";
    static final String K_PT_ENTER = "pt_enter";

    // ---- Появление ввода (fade-in)
    static final String K_FI_ON = "fi_on";
    static final String K_FI_STYLE = "fi_style";
    static final String K_FI_MS = "fi_ms";
    static final String K_FI_INPUT = "fi_input";
    static final String K_FI_NOAPP = "fi_noapp";

    // ---- Скринсейвер
    static final String K_SS_MODE = "ss_mode";
    static final String K_SS_IDLE = "ss_idle";
    static final String K_SS_FADE = "ss_fade";
    static final String K_SS_DIM = "ss_dim";
    static final String K_SS_NOOUT = "ss_noout";
    static final String K_SS_COLOR = "ss_color";
    static final String K_SS_SPEED = "ss_speed";
    static final String K_SS_SIZE = "ss_size";
    static final String K_SS_DENSITY = "ss_density";
    static final String K_SS_CHARS = "ss_chars";
    static final String K_SS_COUNT = "ss_count";
    static final String K_SS_LINKS = "ss_links";
    static final String K_SS_VFADE = "ss_vfade";
    static final String K_SS_VFADE_MS = "ss_vfade_ms";
    static final String K_SS_VPAUSE = "ss_vpause";

    // ---- Значения по умолчанию (всё новое выключено, пока не включишь сам)
    static final boolean D_CM_ON = false;
    static final int D_CM_NORMAL = 0xFFCDD6F4;
    static final int D_CM_TYPING = 0xFFA6E3A1;
    static final int D_CM_ENTER = 0xFFF9E2AF;
    static final int D_CM_SELECT = 0xFFF5C2E7;
    static final int D_CM_APP = 0xFF89B4FA;

    static final boolean D_BL_ON = false;
    static final int D_BL_PERIOD = 1000;
    static final int D_BL_MIN = 0;
    static final int D_BL_HOLD = 500;

    static final int D_CR_ROUND = 0;
    static final int D_GL_STR = 0;
    static final int D_GL_RADIUS = 40;
    static final boolean D_GL_PULSE = false;
    static final boolean D_GL_CUSTOM = false;
    static final int D_GL_COLOR = 0xFF89B4FA;

    static final boolean D_PT_ON = false;
    static final int D_PT_STYLE = 0;
    static final int D_PT_COUNT = 6;
    static final int D_PT_SIZE = 4;
    static final int D_PT_LIFE = 700;
    static final int D_PT_SPREAD = 50;
    static final int D_PT_SPEED = 50;
    static final int D_PT_CMODE = 0;
    static final int D_PT_COLOR = 0xFFFFC857;
    static final boolean D_PT_MOVE = true;
    static final boolean D_PT_ENTER = true;

    static final boolean D_FI_ON = false;
    static final int D_FI_STYLE = 0;
    static final int D_FI_MS = 220;
    static final boolean D_FI_INPUT = true;
    static final boolean D_FI_NOAPP = true;

    static final int D_SS_MODE = 0;
    static final int D_SS_IDLE = 2;
    static final int D_SS_FADE = 600;
    static final int D_SS_DIM = 30;
    static final boolean D_SS_NOOUT = true;
    static final int D_SS_COLOR = 0xFF00FF41;
    static final int D_SS_SPEED = 50;
    static final int D_SS_SIZE = 16;
    static final int D_SS_DENSITY = 70;
    static final int D_SS_CHARS = 0;
    static final int D_SS_COUNT = 80;
    static final boolean D_SS_LINKS = true;
    static final boolean D_SS_VFADE = true;
    static final int D_SS_VFADE_MS = 800;
    static final int D_SS_VPAUSE = 300;

    // Через сколько секунд простоя включается скринсейвер (индекс = ss_idle)
    static final int[] IDLE_SECS = {10, 30, 60, 120, 300, 600};

    // ---- Текущие значения в процессе Termux
    static volatile boolean cmOn = D_CM_ON;
    static volatile int cmNormal = D_CM_NORMAL, cmTyping = D_CM_TYPING, cmEnter = D_CM_ENTER,
            cmSelect = D_CM_SELECT, cmApp = D_CM_APP;

    static volatile boolean blOn = D_BL_ON;
    static volatile int blPeriod = D_BL_PERIOD, blMin = D_BL_MIN, blHold = D_BL_HOLD;

    static volatile int crRound = D_CR_ROUND, glStr = D_GL_STR, glRadius = D_GL_RADIUS, glColor = D_GL_COLOR;
    static volatile boolean glPulse = D_GL_PULSE, glCustom = D_GL_CUSTOM;

    static volatile boolean ptOn = D_PT_ON, ptMove = D_PT_MOVE, ptEnter = D_PT_ENTER;
    static volatile int ptStyle = D_PT_STYLE, ptCount = D_PT_COUNT, ptSize = D_PT_SIZE, ptLife = D_PT_LIFE,
            ptSpread = D_PT_SPREAD, ptSpeed = D_PT_SPEED, ptCMode = D_PT_CMODE, ptColor = D_PT_COLOR;

    static volatile boolean fiOn = D_FI_ON, fiOnlyInput = D_FI_INPUT, fiNoApp = D_FI_NOAPP;
    static volatile int fiStyle = D_FI_STYLE, fiMs = D_FI_MS;

    static volatile int ssMode = D_SS_MODE, ssIdle = D_SS_IDLE, ssFade = D_SS_FADE, ssDim = D_SS_DIM,
            ssColor = D_SS_COLOR, ssSpeed = D_SS_SPEED, ssSize = D_SS_SIZE, ssDensity = D_SS_DENSITY,
            ssChars = D_SS_CHARS, ssCount = D_SS_COUNT, ssVFadeMs = D_SS_VFADE_MS, ssVPause = D_SS_VPAUSE;
    static volatile boolean ssNoOut = D_SS_NOOUT, ssLinks = D_SS_LINKS, ssVFade = D_SS_VFADE;

    private static String lastSig = "";

    static long idleMs() {
        int i = Math.max(0, Math.min(IDLE_SECS.length - 1, ssIdle));
        return IDLE_SECS[i] * 1000L;
    }

    /** Читает свежие настройки. Возвращает true, если что-то изменилось с прошлого раза. */
    static boolean update(Bundle b) {
        cmOn = b.getBoolean(K_CM_ON, D_CM_ON);
        cmNormal = b.getInt(K_CM_NORMAL, D_CM_NORMAL);
        cmTyping = b.getInt(K_CM_TYPING, D_CM_TYPING);
        cmEnter = b.getInt(K_CM_ENTER, D_CM_ENTER);
        cmSelect = b.getInt(K_CM_SELECT, D_CM_SELECT);
        cmApp = b.getInt(K_CM_APP, D_CM_APP);

        blOn = b.getBoolean(K_BL_ON, D_BL_ON);
        blPeriod = b.getInt(K_BL_PERIOD, D_BL_PERIOD);
        blMin = b.getInt(K_BL_MIN, D_BL_MIN);
        blHold = b.getInt(K_BL_HOLD, D_BL_HOLD);

        crRound = b.getInt(K_CR_ROUND, D_CR_ROUND);
        glStr = b.getInt(K_GL_STR, D_GL_STR);
        glRadius = b.getInt(K_GL_RADIUS, D_GL_RADIUS);
        glPulse = b.getBoolean(K_GL_PULSE, D_GL_PULSE);
        glCustom = b.getBoolean(K_GL_CUSTOM, D_GL_CUSTOM);
        glColor = b.getInt(K_GL_COLOR, D_GL_COLOR);

        ptOn = b.getBoolean(K_PT_ON, D_PT_ON);
        ptStyle = b.getInt(K_PT_STYLE, D_PT_STYLE);
        ptCount = b.getInt(K_PT_COUNT, D_PT_COUNT);
        ptSize = b.getInt(K_PT_SIZE, D_PT_SIZE);
        ptLife = b.getInt(K_PT_LIFE, D_PT_LIFE);
        ptSpread = b.getInt(K_PT_SPREAD, D_PT_SPREAD);
        ptSpeed = b.getInt(K_PT_SPEED, D_PT_SPEED);
        ptCMode = b.getInt(K_PT_CMODE, D_PT_CMODE);
        ptColor = b.getInt(K_PT_COLOR, D_PT_COLOR);
        ptMove = b.getBoolean(K_PT_MOVE, D_PT_MOVE);
        ptEnter = b.getBoolean(K_PT_ENTER, D_PT_ENTER);

        fiOn = b.getBoolean(K_FI_ON, D_FI_ON);
        fiStyle = b.getInt(K_FI_STYLE, D_FI_STYLE);
        fiMs = Math.max(40, b.getInt(K_FI_MS, D_FI_MS));
        fiOnlyInput = b.getBoolean(K_FI_INPUT, D_FI_INPUT);
        fiNoApp = b.getBoolean(K_FI_NOAPP, D_FI_NOAPP);

        ssMode = b.getInt(K_SS_MODE, D_SS_MODE);
        ssIdle = b.getInt(K_SS_IDLE, D_SS_IDLE);
        ssFade = b.getInt(K_SS_FADE, D_SS_FADE);
        ssDim = b.getInt(K_SS_DIM, D_SS_DIM);
        ssNoOut = b.getBoolean(K_SS_NOOUT, D_SS_NOOUT);
        ssColor = b.getInt(K_SS_COLOR, D_SS_COLOR);
        ssSpeed = b.getInt(K_SS_SPEED, D_SS_SPEED);
        ssSize = Math.max(6, b.getInt(K_SS_SIZE, D_SS_SIZE));
        ssDensity = b.getInt(K_SS_DENSITY, D_SS_DENSITY);
        ssChars = b.getInt(K_SS_CHARS, D_SS_CHARS);
        ssCount = Math.max(5, b.getInt(K_SS_COUNT, D_SS_COUNT));
        ssLinks = b.getBoolean(K_SS_LINKS, D_SS_LINKS);
        ssVFade = b.getBoolean(K_SS_VFADE, D_SS_VFADE);
        ssVFadeMs = b.getInt(K_SS_VFADE_MS, D_SS_VFADE_MS);
        ssVPause = b.getInt(K_SS_VPAUSE, D_SS_VPAUSE);

        String sig = String.valueOf(b);
        boolean changed = !sig.equals(lastSig);
        lastSig = sig;
        return changed;
    }
}
