package com.smoothcursor;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;

import java.lang.ref.WeakReference;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class SmoothCursor implements IXposedHookLoadPackage {

    // Время догоняния для углов (мс): передние быстро, задние медленно
    private static final float TAU_LEAD_MS = 25f;
    private static final float TAU_TRAIL_MS = 90f;
    // Прозрачность курсора 0..255
    private static final int CURSOR_ALPHA = 200;
    // Ручная подгонка по вертикали в долях клетки (плюс вниз, минус вверх)
    private static final float Y_SHIFT_CELLS = 0f;

    private static final String PKG = "com.termux";
    private static final int COLOR_INDEX_CURSOR = 258;

    private static WeakReference<View> viewRef = new WeakReference<>(null);
    private static boolean inRender = false;
    private static boolean nativeVisible = true;
    private static boolean logged = false;

    // Четыре угла курсора в клетках: 0 левый верх, 1 правый верх, 2 правый низ, 3 левый низ
    private static final float[] cx = new float[4];
    private static final float[] cy = new float[4];
    private static final float[] tau = {TAU_LEAD_MS, TAU_LEAD_MS, TAU_LEAD_MS, TAU_LEAD_MS};
    private static final float[] SX = {-1f, 1f, 1f, -1f};
    private static final float[] SY = {-1f, -1f, 1f, 1f};

    private static boolean inited = false;
    private static int lastCol, lastRow, lastStyle;
    private static long lastNs = 0;

    private static final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private static final Path path = new Path();

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if (!PKG.equals(lpparam.packageName)) return;
        ClassLoader cl = lpparam.classLoader;
        XposedBridge.log("SmoothCursor v3: loaded");

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

            Class<?> rendCls = XposedHelpers.findClassIfExists("com.termux.view.TerminalRenderer", cl);
            if (rendCls == null) {
                rendCls = XposedHelpers.findClassIfExists("com.termux.terminal.TerminalRenderer", cl);
            }
            if (rendCls == null) {
                XposedBridge.log("SmoothCursor v3: TerminalRenderer NOT FOUND");
                return;
            }

            XposedBridge.hookAllMethods(rendCls, "render", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam p) {
                    inRender = true;
                    nativeVisible = true;
                }

                @Override
                protected void afterHookedMethod(MethodHookParam p) {
                    inRender = false;
                    try {
                        drawCursor(p);
                    } catch (Throwable t) {
                        XposedBridge.log("SmoothCursor v3: draw error: " + t);
                    }
                }
            });

            XposedBridge.log("SmoothCursor v3: hooks installed, renderer=" + rendCls.getName());
        } catch (Throwable t) {
            XposedBridge.log("SmoothCursor v3: init failed: " + t);
        }
    }

    private static void drawCursor(XC_MethodHook.MethodHookParam p) {
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
        // Верх нулевой строки так же, как считает сам Termux
        float yOff = cellH + ascent;

        if (!logged) {
            logged = true;
            XposedBridge.log("SmoothCursor v3: spacing=" + cellH + " ascent=" + ascent
                    + " yOff=" + yOff + " cellW=" + cellW);
        }

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

        if (!inited) {
            for (int i = 0; i < 4; i++) { cx[i] = tx[i]; cy[i] = ty[i]; }
            inited = true;
            lastCol = col; lastRow = row; lastStyle = style;
        } else if (col != lastCol || row != lastRow || style != lastStyle) {
            retarget(tx, ty, cellW, cellH);
            lastCol = col; lastRow = row; lastStyle = style;
            dtMs = 0f;
        } else {
            dtMs = Math.min((now - lastNs) / 1_000_000f, 50f);
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

        if (nativeVisible) {
            path.reset();
            for (int i = 0; i < 4; i++) {
                float px = cx[i] * cellW;
                float py = yOff + (cy[i] + Y_SHIFT_CELLS) * cellH;
                if (i == 0) path.moveTo(px, py); else path.lineTo(px, py);
            }
            path.close();
            paint.setColor(cursorColor(emu));
            paint.setAlpha(CURSOR_ALPHA);
            canvas.drawPath(path, paint);
        }

        if (moving) {
            View v = viewRef.get();
            if (v != null) v.postInvalidateOnAnimation();
        }
    }

    // Новая цель: углы по направлению движения получают короткое время, задние длинное
    private static void retarget(float[] tx, float[] ty, float cellW, int cellH) {
        float ccx = 0, ccy = 0, tcx = 0, tcy = 0;
        for (int i = 0; i < 4; i++) {
            ccx += cx[i]; ccy += cy[i];
            tcx += tx[i]; tcy += ty[i];
        }
        float dx = (tcx - ccx) / 4f * cellW;
        float dy = (tcy - ccy) / 4f * cellH;
        float len = (float) Math.sqrt(dx * dx + dy * dy);

        for (int i = 0; i < 4; i++) {
            float a = 0f;
            if (len > 1e-3f) a = ((SX[i] * dx + SY[i] * dy) / len) * 0.70710678f;
            float t = (1f - a) / 2f;
            tau[i] = TAU_LEAD_MS + (TAU_TRAIL_MS - TAU_LEAD_MS) * t;
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
