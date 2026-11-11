package com.smoothcursor;

import android.app.Activity;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.net.Uri;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.View;

import java.lang.ref.WeakReference;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class SmoothCursor implements IXposedHookLoadPackage {

    private static final int CURSOR_ALPHA = 200;
    private static final float Y_SHIFT_CELLS = 0f;

    private static final String PKG = "com.termux";
    private static final int COLOR_INDEX_CURSOR = 258;
    private static final Uri CFG_URI = Uri.parse("content://com.smoothcursor.settings");

    // Настройки из приложения модуля (подтягиваются в фоне)
    private static volatile boolean cfgUseTheme = true;
    private static volatile int cfgColor = 0xFFFFFFFF;
    private static volatile int cfgOutline = 0;
    private static volatile int cfgTrail = 50;
    private static volatile boolean cfgSmoothScroll = true;
    private static volatile int cfgScrollSpeed = 50;
    private static volatile boolean fetching = false;
    private static long lastFetchMs = -100000;

    private static WeakReference<View> viewRef = new WeakReference<>(null);
    private static boolean inRender = false;
    private static boolean nativeVisible = true;
    private static boolean logged = false;

    private static final float[] cx = new float[4];
    private static final float[] cy = new float[4];
    private static final float[] tau = {55f, 55f, 55f, 55f};
    private static final float[] SX = {-1f, 1f, 1f, -1f};
    private static final float[] SY = {-1f, -1f, 1f, 1f};

    private static boolean inited = false;
    private static boolean wasMoving = false;
    private static int lastCol, lastRow, lastStyle;
    private static long lastNs = 0;

    // Плавный скролл: визуальное смещение в пикселях, затухает к нулю
    private static float scrollOff = 0f;
    private static int lastTopRow = 0;
    private static boolean scrollInited = false;
    private static long lastScrollNs = 0;
    private static int cursorTopRow = 0;
    // Сколько строк эмулятор прокрутил сам (Enter внизу экрана, вывод команд)
    private static int pendingScroll = 0;
    // Сдвиг контента в строках за этот кадр: курсор сдвигается вместе с ним, а не телепортируется
    private static int frameShift = 0;
    private static final String EXTRA_SAVED = "sc_saved";

    private static final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private static final Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private static final Path path = new Path();

    static {
        strokePaint.setStyle(Paint.Style.STROKE);
        strokePaint.setStrokeJoin(Paint.Join.ROUND);
    }

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if (!PKG.equals(lpparam.packageName)) return;
        ClassLoader cl = lpparam.classLoader;
        XposedBridge.log("SmoothCursor v5: loaded");

        // Свой фон и заставка (см. Overlay.java)
        Overlay.init(cl);

        // Эффекты курсора/ввода и скринсейвер (см. Fx.java, Saver.java)
        try {
            Fx.install(cl);
        } catch (Throwable t) {
            XposedBridge.log("SmoothCursor: Fx install failed: " + t);
        }
        try {
            Saver.install(cl);
        } catch (Throwable t) {
            XposedBridge.log("SmoothCursor: Saver install failed: " + t);
        }
        try {
            Class<?> actCls = XposedHelpers.findClassIfExists("com.termux.app.TermuxActivity", cl);
            if (actCls != null) {
                XposedBridge.hookAllMethods(actCls, "onCreate", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam p) {
                        try {
                            Overlay.maybeSplash((Activity) p.thisObject);
                        } catch (Throwable t) {
                            XposedBridge.log("SmoothCursor: splash error: " + t);
                        }
                    }
                });
            } else {
                XposedBridge.log("SmoothCursor: TermuxActivity NOT FOUND, splash off");
            }
        } catch (Throwable t) {
            XposedBridge.log("SmoothCursor: splash hook failed: " + t);
        }

        try {
            Class<?> viewCls = XposedHelpers.findClass("com.termux.view.TerminalView", cl);
            XposedBridge.hookAllMethods(viewCls, "onDraw", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam p) {
                    viewRef = new WeakReference<>((View) p.thisObject);
                }
            });

            Class<?> emuCls = XposedHelpers.findClass("com.termux.terminal.TerminalEmulator", cl);
            XposedBridge.hookAllMethods(emuCls, "shouldCursorBeVisible", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam p) {
                    if (inRender) {
                        Object r = p.getResult();
                        nativeVisible = !(r instanceof Boolean) || (Boolean) r;
                        p.setResult(false);
                    }
                }
            });

            // Эмулятор сам прокручивает экран, когда курсор внизу. Ловим, на сколько строк,
            // чтобы текст плыл, а не прыгал.
            XposedBridge.hookAllMethods(emuCls, "clearScrollCounter", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam p) {
                    try {
                        if (!cfgSmoothScroll) return;
                        Object alt = XposedHelpers.callMethod(p.thisObject, "isAlternateBufferActive");
                        if (alt instanceof Boolean && (Boolean) alt) return;
                        Object n = XposedHelpers.callMethod(p.thisObject, "getScrollCounter");
                        if (n instanceof Integer && (Integer) n > 0) {
                            pendingScroll = Math.min(1000, pendingScroll + (Integer) n);
                        }
                    } catch (Throwable ignored) {
                    }
                }
            });

            Class<?> rendCls = XposedHelpers.findClassIfExists("com.termux.view.TerminalRenderer", cl);
            if (rendCls == null) {
                rendCls = XposedHelpers.findClassIfExists("com.termux.terminal.TerminalRenderer", cl);
            }
            if (rendCls == null) {
                XposedBridge.log("SmoothCursor v5: TerminalRenderer NOT FOUND");
                return;
            }

            try {
                Fx.hookFade(rendCls);
            } catch (Throwable t) {
                XposedBridge.log("SmoothCursor: fade hook failed: " + t);
            }

            XposedBridge.hookAllMethods(rendCls, "render", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam p) {
                    inRender = true;
                    nativeVisible = true;
                    try {
                        applyScroll(p);
                    } catch (Throwable t) {
                        XposedBridge.log("SmoothCursor: scroll error: " + t);
                    }
                    try {
                        Overlay.bgBefore(p);
                    } catch (Throwable t) {
                        XposedBridge.log("SmoothCursor: bg error: " + t);
                    }
                    try {
                        Fx.beforeRender(p.args[0], (Integer) p.args[2]);
                    } catch (Throwable t) {
                        XposedBridge.log("SmoothCursor: fx error: " + t);
                    }
                }

                @Override
                protected void afterHookedMethod(MethodHookParam p) {
                    inRender = false;
                    Fx.afterRender();
                    try {
                        Overlay.bgAfter(p);
                    } catch (Throwable ignored) {
                    }
                    try {
                        drawCursor(p);
                    } catch (Throwable t) {
                        XposedBridge.log("SmoothCursor v5: draw error: " + t);
                    } finally {
                        if (p.getObjectExtra(EXTRA_SAVED) != null) {
                            try {
                                ((Canvas) p.args[1]).restore();
                            } catch (Throwable ignored) {
                            }
                        }
                    }
                }
            });

            XposedBridge.log("SmoothCursor v5: hooks installed, renderer=" + rendCls.getName());
        } catch (Throwable t) {
            XposedBridge.log("SmoothCursor v5: init failed: " + t);
        }
    }

    // Раз в ~1.5 c тихо спрашиваем настройки у приложения модуля
    private static void maybeFetch() {
        final View v = viewRef.get();
        if (v == null || fetching) return;
        long now = SystemClock.uptimeMillis();
        if (now - lastFetchMs < 1500) return;
        lastFetchMs = now;
        fetching = true;
        final Context ctx = v.getContext();
        new Thread(() -> {
            try {
                Bundle b = ctx.getContentResolver().call(CFG_URI, "get", null, null);
                if (b != null) {
                    boolean ut = b.getBoolean("use_theme", true);
                    int c = b.getInt("color", 0xFFFFFFFF);
                    int o = b.getInt("outline", 0);
                    int tr = b.getInt("trail", 50);
                    boolean ss = b.getBoolean("smooth_scroll", true);
                    int sv = b.getInt("scroll_speed", 50);
                    boolean changed = ut != cfgUseTheme || c != cfgColor || o != cfgOutline || tr != cfgTrail
                            || ss != cfgSmoothScroll || sv != cfgScrollSpeed;
                    cfgSmoothScroll = ss;
                    cfgScrollSpeed = sv;
                    cfgUseTheme = ut;
                    cfgColor = c;
                    cfgOutline = o;
                    cfgTrail = tr;
                    Overlay.onConfig(v, b);
                    boolean fxChanged = Cfg.update(b);
                    Saver.onCfg();
                    if (changed || fxChanged) v.postInvalidate();
                }
            } catch (Throwable ignored) {
            } finally {
                fetching = false;
            }
        }).start();
    }

    // Скролл Termux двигает topRow целыми строками. Здесь сдвигаем холст на недостающие
    // пиксели и плавно возвращаем в 0, так что текст не телепортируется, а ездит.
    private static void applyScroll(XC_MethodHook.MethodHookParam p) {
        frameShift = 0;
        Canvas canvas = (Canvas) p.args[1];
        int topRow = (Integer) p.args[2];
        long now = System.nanoTime();
        int pend = pendingScroll;
        pendingScroll = 0;

        if (!cfgSmoothScroll) {
            scrollOff = 0f;
            lastTopRow = topRow;
            lastScrollNs = now;
            scrollInited = true;
            return;
        }

        int cellH = XposedHelpers.getIntField(p.thisObject, "mFontLineSpacing");

        if (scrollInited) {
            float dtMs = Math.min((now - lastScrollNs) / 1_000_000f, 50f);
            float tau = 30f + 120f * cfgScrollSpeed / 100f;
            scrollOff *= (float) Math.exp(-dtMs / tau);
            int d = topRow - lastTopRow;
            // Эмулятор прокрутил сам (мы внизу): это тот же сдвиг, только topRow остался 0.
            // Большой вывод (cat файла) не анимируем, иначе вечное дрожание.
            if (topRow == 0 && lastTopRow == 0 && pend > 0 && pend <= 3) d += pend;
            if (d != 0) {
                float lim = cellH * 2f;
                scrollOff = Math.max(-lim, Math.min(lim, scrollOff + d * (float) cellH));
                if (Math.abs(d) <= 3) frameShift = d;
            }
        }
        scrollInited = true;
        lastTopRow = topRow;
        lastScrollNs = now;

        if (Math.abs(scrollOff) < 0.5f) {
            scrollOff = 0f;
            return;
        }

        canvas.save();
        canvas.translate(0f, scrollOff);
        p.setObjectExtra(EXTRA_SAVED, Boolean.TRUE);

        View v = viewRef.get();
        if (v != null) v.postInvalidateOnAnimation();
    }

    private static void drawCursor(XC_MethodHook.MethodHookParam p) {
        maybeFetch();

        Object renderer = p.thisObject;
        Object emu = p.args[0];
        Canvas canvas = (Canvas) p.args[1];
        int topRow = (Integer) p.args[2];

        float cellW = XposedHelpers.getFloatField(renderer, "mFontWidth");
        int cellH = XposedHelpers.getIntField(renderer, "mFontLineSpacing");

        int ascent;
        try {
            ascent = XposedHelpers.getIntField(renderer, "mFontAscent");
        } catch (Throwable t) {
            ascent = -cellH;
        }
        float yOff = cellH + ascent;

        if (!logged) {
            logged = true;
            XposedBridge.log("SmoothCursor v5: spacing=" + cellH + " ascent=" + ascent
                    + " yOff=" + yOff + " cellW=" + cellW);
        }

        // При скролле курсор едет вместе с текстом (через сдвиг холста), без своей анимации
        // Раньше тут был сброс (inited = false): курсор телепортировался при каждом сдвиге.
        // Теперь он едет вместе с контентом и дальше плавно догоняет цель.
        if (cfgSmoothScroll) {
            if (Math.abs(topRow - cursorTopRow) > 3) {
                inited = false;
            } else if (inited && frameShift != 0) {
                for (int i = 0; i < 4; i++) cy[i] -= frameShift;
                lastRow -= frameShift;
            }
        }
        cursorTopRow = topRow;

        int col = (Integer) XposedHelpers.callMethod(emu, "getCursorCol");
        int row = (Integer) XposedHelpers.callMethod(emu, "getCursorRow") - topRow;

        int style = 0;
        try {
            style = (Integer) XposedHelpers.callMethod(emu, "getCursorStyle");
        } catch (Throwable ignored) {
        }

        float tL = col, tR = col + 1, tT = row, tB = row + 1;
        if (style == 1) tT = row + 0.75f;
        else if (style == 2) tR = col + 0.25f;

        float[] tx = {tL, tR, tR, tL};
        float[] ty = {tT, tT, tB, tB};

        long now = System.nanoTime();
        float dtMs = 0f;

        boolean moved = false;
        float oldCx = 0f, oldCy = 0f;
        if (!inited) {
            for (int i = 0; i < 4; i++) { cx[i] = tx[i]; cy[i] = ty[i]; }
            inited = true;
            lastCol = col; lastRow = row; lastStyle = style;
        } else if (col != lastCol || row != lastRow || style != lastStyle) {
            oldCx = (cx[0] + cx[1] + cx[2] + cx[3]) / 4f;
            oldCy = (cy[0] + cy[1] + cy[2] + cy[3]) / 4f;
            moved = true;
            retarget(tx, ty, cellW, cellH);
            lastCol = col; lastRow = row; lastStyle = style;
            // На первом кадре движения даём небольшой шаг, а не 0, чтобы старт не был ступенькой
            dtMs = wasMoving ? Math.min((now - lastNs) / 1_000_000f, 48f) : 8f;
        } else {
            dtMs = Math.min((now - lastNs) / 1_000_000f, 48f);
        }
        lastNs = now;

        boolean moving = false;
        for (int i = 0; i < 4; i++) {
            float a = 1f - (float) Math.exp(-dtMs / tau[i]);
            cx[i] += (tx[i] - cx[i]) * a;
            cy[i] += (ty[i] - cy[i]) * a;
            if (Math.abs(tx[i] - cx[i]) < 0.005f) cx[i] = tx[i]; else moving = true;
            if (Math.abs(ty[i] - cy[i]) < 0.005f) cy[i] = ty[i]; else moving = true;
        }

        wasMoving = moving;

        View vv = viewRef.get();

        // Курсор сдвинулся: сбрасываем фазу мигания, пускаем частицы
        if (moved) {
            Fx.markActive();
            if (vv != null) {
                float nxc = (tx[0] + tx[1] + tx[2] + tx[3]) / 4f;
                float nyc = (ty[0] + ty[1] + ty[2] + ty[3]) / 4f;
                try {
                    Fx.onMove(vv, oldCx * cellW - cellW / 2f, yOff + oldCy * cellH - cellH / 2f,
                            nxc * cellW - cellW / 2f, yOff + nyc * cellH - cellH / 2f, cellW, cellH);
                } catch (Throwable t) {
                    XposedBridge.log("SmoothCursor: particles error: " + t);
                }
            }
        }

        path.reset();
        for (int i = 0; i < 4; i++) {
            float px = cx[i] * cellW;
            float py = yOff + (cy[i] + Y_SHIFT_CELLS) * cellH;
            if (i == 0) path.moveTo(px, py); else path.lineTo(px, py);
        }
        path.close();

        int base = (cfgUseTheme ? cursorColor(emu) : cfgColor) | 0xFF000000;
        float qw = (tR - tL) * cellW;
        float qh = (tB - tT) * cellH;

        // Цвет по режимам, мигание, скругление, свечение, контур и частицы живут в Fx
        int lvl = Fx.drawCursor(vv, canvas, path, emu, base, cellW, cellH, qw, qh,
                nativeVisible, cfgOutline, CURSOR_ALPHA);

        if (vv != null) {
            if (moving || lvl >= 2) vv.postInvalidateOnAnimation();
            else if (lvl == 1) vv.postInvalidateDelayed(33);
        }
    }

    // Передние углы получают короткое время, задние длинное. Ползунок шлейфа тянет оба.
    private static void retarget(float[] tx, float[] ty, float cellW, int cellH) {
        float ccx = 0, ccy = 0, tcx = 0, tcy = 0;
        for (int i = 0; i < 4; i++) {
            ccx += cx[i]; ccy += cy[i];
            tcx += tx[i]; tcy += ty[i];
        }
        float dx = (tcx - ccx) / 4f * cellW;
        float dy = (tcy - ccy) / 4f * cellH;
        float len = (float) Math.sqrt(dx * dx + dy * dy);

        float s = cfgTrail / 100f;
        float lead = 30f + 50f * s;
        float trail = lead * (1.3f + 2.2f * s);

        for (int i = 0; i < 4; i++) {
            float a = 0f;
            if (len > 1e-3f) a = ((SX[i] * dx + SY[i] * dy) / len) * 0.70710678f;
            float t = (1f - a) / 2f;
            tau[i] = lead + (trail - lead) * t;
        }
    }

    private static int cursorColor(Object emu) {
        try {
            Object colors = XposedHelpers.getObjectField(emu, "mColors");
            int[] cur = (int[]) XposedHelpers.getObjectField(colors, "mCurrentColors");
            return cur[COLOR_INDEX_CURSOR] | 0xFF000000;
        } catch (Throwable t) {
            return 0xFFFFFFFF;
        }
    }
}
