package com.smoothcursor;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.View;

import java.lang.ref.WeakReference;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class SmoothCursor implements IXposedHookLoadPackage {

    // ===== НАСТРОЙКИ (мс) =====
    // Время "догоняния" передней кромки курсора (меньше = быстрее)
    private static final float TAU_LEAD_MS = 25f;
    // Время "догоняния" задней кромки (больше = длиннее хвост как в Neovide)
    private static final float TAU_TRAIL_MS = 90f;
    // Прозрачность курсора 0..255 (чтобы был виден символ под ним)
    private static final int CURSOR_ALPHA = 200;
    // =========================

    private static final String PKG = "com.termux";
    private static final int COLOR_INDEX_CURSOR = 258;

    private static WeakReference<View> viewRef = new WeakReference<>(null);
    private static boolean inRender = false;
    private static boolean nativeVisible = true;

    // Текущие края курсора в клетках (экранные координаты)
    private static float curL, curT, curR, curB;
    private static boolean inited = false;
    private static long lastNs = 0;
    private static final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if (!PKG.equals(lpparam.packageName)) return;
        ClassLoader cl = lpparam.classLoader;

        try {
            // Запоминаем View, чтобы просить перерисовку
            Class<?> viewCls = XposedHelpers.findClass("com.termux.view.TerminalView", cl);
            XposedBridge.hookAllMethods(viewCls, "onDraw", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam p) {
                    viewRef = new WeakReference<>((View) p.thisObject);
                }
            });

            // Прячем родной курсор только во время render
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

            // Рисуем свой курсор поверх
            Class<?> rendCls = XposedHelpers.findClass("com.termux.terminal.TerminalRenderer", cl);
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
                        XposedBridge.log("SmoothCursor: " + t);
                    }
                }
            });

            XposedBridge.log("SmoothCursor: hooks installed");
        } catch (Throwable t) {
            XposedBridge.log("SmoothCursor: init failed: " + t);
        }
    }

    private static void drawCursor(XC_MethodHook.MethodHookParam p) {
        Object renderer = p.thisObject;
        Object emu = p.args[0];
        Canvas canvas = (Canvas) p.args[1];
        int topRow = (Integer) p.args[2];

        float cellW = XposedHelpers.getFloatField(renderer, "mFontWidth");
        int cellH = XposedHelpers.getIntField(renderer, "mFontLineSpacing");

        int col = (Integer) XposedHelpers.callMethod(emu, "getCursorCol");
        int row = (Integer) XposedHelpers.callMethod(emu, "getCursorRow") - topRow;

        int style = 0; // 0 блок, 1 подчёркивание, 2 палка
        try {
            style = (Integer) XposedHelpers.callMethod(emu, "getCursorStyle");
        } catch (Throwable ignored) {
        }

        // Целевые края в клетках
        float tL = col, tR = col + 1, tT = row, tB = row + 1;
        if (style == 1) tT = row + 0.85f;
        else if (style == 2) tR = col + 0.15f;

        long now = System.nanoTime();
        if (!inited) {
            curL = tL; curR = tR; curT = tT; curB = tB;
            inited = true;
        } else {
            float dtMs = Math.min((now - lastNs) / 1_000_000f, 50f);
            curL = chase(curL, tL, dtMs, tL < curL);
            curR = chase(curR, tR, dtMs, tR > curR);
            curT = chase(curT, tT, dtMs, tT < curT);
            curB = chase(curB, tB, dtMs, tB > curB);
        }
        lastNs = now;

        boolean moving = Math.abs(curL - tL) > 0.005f || Math.abs(curR - tR) > 0.005f
                || Math.abs(curT - tT) > 0.005f || Math.abs(curB - tB) > 0.005f;

        if (nativeVisible) {
            paint.setColor(cursorColor(emu));
            paint.setAlpha(CURSOR_ALPHA);
            canvas.drawRect(curL * cellW, curT * cellH, curR * cellW, curB * cellH, paint);
        }

        if (moving) {
            View v = viewRef.get();
            if (v != null) v.postInvalidateOnAnimation();
        }
    }

    // Передняя кромка (leading) двигается быстро, задняя медленнее
    private static float chase(float cur, float target, float dtMs, boolean leading) {
        float tau = leading ? TAU_LEAD_MS : TAU_TRAIL_MS;
        float a = 1f - (float) Math.exp(-dtMs / tau);
        float v = cur + (target - cur) * a;
        return Math.abs(target - v) < 0.005f ? target : v;
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
