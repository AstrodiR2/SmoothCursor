package com.smoothcursor;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.CornerPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Region;
import android.os.Build;
import android.view.KeyEvent;
import android.view.View;

import java.lang.reflect.Method;
import java.util.Random;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * Эффекты курсора и ввода внутри процесса Termux:
 * цвета по режимам, плавное мигание, скругление, свечение, частицы и fade-in вводимых символов.
 * Всё выполняется на UI-потоке (хуки onDraw/ввода), поэтому без синхронизации.
 */
final class Fx {

    private Fx() {
    }

    // ------------------------------------------------------------ ввод

    static volatile long lastInputNs = 0;
    static volatile long enterNs = 0;
    static volatile boolean enterPending = false;
    static volatile boolean inputSeen = false;
    static volatile boolean lastPrintable = false;
    static volatile long activityNs = 0;

    private static boolean fadeErrLogged = false;

    static void markActive() {
        activityNs = System.nanoTime();
    }

    private static void onInput(boolean enter, boolean printable) {
        long now = System.nanoTime();
        inputSeen = true;
        lastInputNs = now;
        lastPrintable = printable;
        activityNs = now;
        if (enter) {
            enterNs = now;
            enterPending = true;
        }
        Saver.poke();
    }

    /** Хуки ввода: время последней клавиши и Enter. Если имя метода другое, просто ничего не ловим. */
    static void install(ClassLoader cl) {
        Class<?> viewCls = XposedHelpers.findClassIfExists("com.termux.view.TerminalView", cl);
        if (viewCls == null) {
            XposedBridge.log("SmoothCursor: Fx.install: TerminalView not found");
            return;
        }
        XposedBridge.hookAllMethods(viewCls, "inputCodePoint", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam p) {
                try {
                    Object[] a = p.args;
                    int cp = -1;
                    if (a.length >= 4 && a[1] instanceof Integer) cp = (Integer) a[1];
                    else if (a.length >= 1 && a[0] instanceof Integer) cp = (Integer) a[0];
                    onInput(cp == '\r' || cp == '\n', cp >= 32);
                } catch (Throwable ignored) {
                }
            }
        });
        XposedBridge.hookAllMethods(viewCls, "onKeyDown", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam p) {
                try {
                    int kc = (Integer) p.args[0];
                    KeyEvent ev = (KeyEvent) p.args[1];
                    boolean enter = kc == KeyEvent.KEYCODE_ENTER || kc == KeyEvent.KEYCODE_NUMPAD_ENTER;
                    boolean printable = ev != null && ev.getUnicodeChar() >= 32 && !ev.isCtrlPressed();
                    onInput(enter, printable);
                } catch (Throwable ignored) {
                }
            }
        });
    }

    // ------------------------------------------------------------ fade-in ввода

    private static final int MAXF = 40;
    private static final int[] fRow = new int[MAXF];
    private static final int[] fCol = new int[MAXF];
    private static final long[] fT = new long[MAXF];
    private static final int[] fTmp = new int[MAXF];
    private static int nF = 0;
    private static int prevCol = -1, prevRow = -1;
    private static boolean prevInit = false;
    private static boolean inFrame = false;
    private static int curTopRow = 0;

    /** Перед render(): регистрируем клетки, которые только что появились слева от курсора. */
    static void beforeRender(Object emu, int topRow) {
        curTopRow = topRow;
        inFrame = true;
        long now = System.nanoTime();

        long durNs = Cfg.fiMs * 1_000_000L;
        int w = 0;
        for (int i = 0; i < nF; i++) {
            if (now - fT[i] < durNs) {
                fRow[w] = fRow[i];
                fCol[w] = fCol[i];
                fT[w] = fT[i];
                w++;
            }
        }
        nF = w;

        if (!Cfg.fiOn) {
            nF = 0;
            prevInit = false;
            return;
        }
        int col = (Integer) XposedHelpers.callMethod(emu, "getCursorCol");
        int row = (Integer) XposedHelpers.callMethod(emu, "getCursorRow");
        if (prevInit && row == prevRow && col > prevCol && col - prevCol <= 12 && fadeAllowed(emu, now)) {
            for (int c = prevCol; c < col && nF < MAXF; c++) {
                fRow[nF] = row;
                fCol[nF] = c;
                fT[nF] = now;
                nF++;
            }
        }
        prevRow = row;
        prevCol = col;
        prevInit = true;
    }

    static void afterRender() {
        inFrame = false;
    }

    private static boolean fadeAllowed(Object emu, long now) {
        if (Cfg.fiOnlyInput && inputSeen) {
            if (now - lastInputNs > 300_000_000L || !lastPrintable) return false;
        }
        if (Cfg.fiNoApp) {
            try {
                Object alt = XposedHelpers.callMethod(emu, "isAlternateBufferActive");
                if (alt instanceof Boolean && (Boolean) alt) return false;
            } catch (Throwable ignored) {
            }
        }
        return true;
    }

    private static Class<?> fadeCls = null;
    private static java.util.Set<XC_MethodHook.Unhook> fadeUnhooks = null;

    private static final XC_MethodHook FADE_HOOK = new XC_MethodHook() {
        @Override
        protected void beforeHookedMethod(MethodHookParam p) {
            try {
                fadeRun(p);
            } catch (Throwable t) {
                if (!fadeErrLogged) {
                    fadeErrLogged = true;
                    XposedBridge.log("SmoothCursor: fade error: " + t);
                }
            }
        }
    };

    /**
     * Запоминает класс рендерера. Сам хук на drawTextRun ставится только когда fade-in включён:
     * этот метод вызывается сотни раз за кадр, и постоянный хук на нём тормозит весь терминал,
     * из-за чего курсор рвётся.
     */
    static void hookFade(Class<?> rendCls) {
        fadeCls = rendCls;
    }

    /** Включает или выключает хук fade-in. Вызывать с UI-потока. */
    static void setFade(boolean on) {
        if (fadeCls == null) return;
        try {
            if (on && fadeUnhooks == null) {
                fadeUnhooks = XposedBridge.hookAllMethods(fadeCls, "drawTextRun", FADE_HOOK);
            } else if (!on && fadeUnhooks != null) {
                for (XC_MethodHook.Unhook u : fadeUnhooks) u.unhook();
                fadeUnhooks = null;
            }
        } catch (Throwable t) {
            XposedBridge.log("SmoothCursor: setFade failed: " + t);
        }
    }

    private static void clipOut(Canvas c, float l, float t, float r, float b) {
        if (Build.VERSION.SDK_INT >= 26) c.clipOutRect(l, t, r, b);
        else c.clipRect(l, t, r, b, Region.Op.DIFFERENCE);
    }

    private static void fadeRun(XC_MethodHook.MethodHookParam p) throws Throwable {
        if (!inFrame || nF == 0 || !Cfg.fiOn) return;

        Canvas canvas = null;
        float y = Float.NaN;
        int[] iv = new int[4];
        int ni = 0;
        for (Object o : p.args) {
            if (o instanceof Canvas) canvas = (Canvas) o;
            else if (o instanceof Float) {
                if (Float.isNaN(y)) y = (Float) o;
            } else if (o instanceof Integer) {
                if (ni < 4) iv[ni++] = (Integer) o;
            }
        }
        if (canvas == null || Float.isNaN(y) || ni < 2) return;
        int startCol = iv[0];
        int runCols = iv[1];

        Object rend = p.thisObject;
        float cellW = XposedHelpers.getFloatField(rend, "mFontWidth");
        int cellH = XposedHelpers.getIntField(rend, "mFontLineSpacing");
        int ascent;
        try {
            ascent = XposedHelpers.getIntField(rend, "mFontAscent");
        } catch (Throwable t) {
            ascent = -cellH;
        }
        float yOff = cellH + ascent;
        int absRow = curTopRow + Math.round((y - yOff) / cellH) - 1;

        int nc = 0;
        for (int i = 0; i < nF; i++) {
            if (fRow[i] == absRow && fCol[i] >= startCol && fCol[i] < startCol + runCols) fTmp[nc++] = i;
        }
        if (nc == 0) return;

        Method m = (Method) p.method;
        float top = y - cellH;
        float bottom = y;

        // 1) Всё, кроме появляющихся клеток, рисуем как обычно
        int sc = canvas.save();
        try {
            for (int k = 0; k < nc; k++) {
                float l = fCol[fTmp[k]] * cellW;
                clipOut(canvas, l, top, l + cellW, bottom);
            }
            XposedBridge.invokeOriginalMethod(m, p.thisObject, p.args);
        } finally {
            canvas.restoreToCount(sc);
        }

        // 2) Появляющиеся клетки: тот же прогон, обрезанный по клетке, с анимацией
        long now = System.nanoTime();
        float dur = Cfg.fiMs * 1_000_000f;
        for (int k = 0; k < nc; k++) {
            int i = fTmp[k];
            float t = Math.min(1f, Math.max(0f, (now - fT[i]) / dur));
            float e = 1f - (1f - t) * (1f - t) * (1f - t);
            int style = Cfg.fiStyle;
            if (style >= 5) style = (int) ((((fT[i] >> 8) ^ (fCol[i] * 31L)) & 0x7fffffffL) % 5);

            float l = fCol[i] * cellW;
            float r = l + cellW;
            float a = (style == 0 || style == 4) ? e : Math.min(1f, e * 1.6f);

            int s2 = canvas.save();
            try {
                canvas.clipRect(l, top, r, bottom);
                canvas.saveLayerAlpha(l, top, r, bottom, Math.round(255f * a));
                switch (style) {
                    case 1:
                        canvas.translate(0f, (1f - e) * cellH * 0.7f);
                        break;
                    case 2:
                        canvas.translate(0f, -(1f - e) * cellH * 0.7f);
                        break;
                    case 3:
                        canvas.translate(-(1f - e) * cellW * 0.9f, 0f);
                        break;
                    case 4: {
                        float s = 0.35f + 0.65f * e;
                        canvas.scale(s, s, (l + r) / 2f, (top + bottom) / 2f);
                        break;
                    }
                    default:
                        break;
                }
                XposedBridge.invokeOriginalMethod(m, p.thisObject, p.args);
            } finally {
                canvas.restoreToCount(s2);
            }
        }
        p.setResult(null);
    }

    // ------------------------------------------------------------ курсор

    private static final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private static final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private static final Paint glow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private static final Paint pPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private static final float[] hsv = new float[]{0f, 0.75f, 1f};
    private static final Random rnd = new Random();

    static {
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeJoin(Paint.Join.ROUND);
        glow.setStyle(Paint.Style.STROKE);
        glow.setStrokeJoin(Paint.Join.ROUND);
        pPaint.setStrokeCap(Paint.Cap.ROUND);
    }

    private static CornerPathEffect cpe;
    private static float cpeR = -1f;
    private static int curColor = 0xFFFFFFFF;
    private static boolean colorInit = false;
    private static long lastColorNs = 0;
    private static boolean needSlow = false;

    private static int lerpColor(int a, int b, float t) {
        int ar = (a >> 16) & 255, ag = (a >> 8) & 255, ab = a & 255;
        int br = (b >> 16) & 255, bg = (b >> 8) & 255, bb = b & 255;
        int r = Math.round(ar + (br - ar) * t);
        int g = Math.round(ag + (bg - ag) * t);
        int bl = Math.round(ab + (bb - ab) * t);
        return 0xFF000000 | (r << 16) | (g << 8) | bl;
    }

    private static boolean closeColor(int a, int b) {
        return Math.abs(((a >> 16) & 255) - ((b >> 16) & 255)) < 2
                && Math.abs(((a >> 8) & 255) - ((b >> 8) & 255)) < 2
                && Math.abs((a & 255) - (b & 255)) < 2;
    }

    private static int modeColor(Object emu, View v, long now) {
        try {
            if (v != null) {
                Object sel = XposedHelpers.callMethod(v, "isSelectingText");
                if (sel instanceof Boolean && (Boolean) sel) return Cfg.cmSelect;
            }
        } catch (Throwable ignored) {
        }
        if (now - enterNs < 300_000_000L) {
            needSlow = true;
            return Cfg.cmEnter;
        }
        try {
            Object alt = XposedHelpers.callMethod(emu, "isAlternateBufferActive");
            if (alt instanceof Boolean && (Boolean) alt) return Cfg.cmApp;
        } catch (Throwable ignored) {
        }
        if (now - lastInputNs < 700_000_000L) {
            needSlow = true;
            return Cfg.cmTyping;
        }
        return Cfg.cmNormal;
    }

    private static boolean shown(Object emu, boolean nativeVisible) {
        try {
            return XposedHelpers.getBooleanField(emu, "mShowCursor");
        } catch (Throwable t) {
            return nativeVisible;
        }
    }

    private static void setRound(float rad) {
        if (rad < 0.5f) {
            fill.setPathEffect(null);
            stroke.setPathEffect(null);
            glow.setPathEffect(null);
            cpeR = -1f;
            return;
        }
        if (Math.abs(rad - cpeR) > 0.25f || cpe == null) {
            cpe = new CornerPathEffect(rad);
            cpeR = rad;
        }
        fill.setPathEffect(cpe);
        stroke.setPathEffect(cpe);
        glow.setPathEffect(cpe);
    }

    /**
     * Рисует курсор (цвет по режиму, мигание, скругление, свечение, контур) и частицы.
     * Возвращает, нужен ли ещё кадр: 0 нет, 1 редкие кадры (мигание), 2 каждый кадр.
     */
    static int drawCursor(View v, Canvas canvas, Path path, Object emu, int themeColor,
                          float cellW, int cellH, float w, float h, boolean visible,
                          int outlinePct, int baseAlpha) {
        long now = System.nanoTime();
        int lvl = 0;
        needSlow = false;

        // Цвет: по режиму или как раньше, с плавным переходом
        int target = themeColor | 0xFF000000;
        if (Cfg.cmOn) target = modeColor(emu, v, now) | 0xFF000000;
        if (!colorInit) {
            curColor = target;
            colorInit = true;
        } else if (curColor != target) {
            float dt = Math.min((now - lastColorNs) / 1_000_000f, 50f);
            float a = 1f - (float) Math.exp(-dt / 70f);
            curColor = lerpColor(curColor, target, a);
            if (closeColor(curColor, target)) curColor = target;
            else lvl = 2;
        }
        lastColorNs = now;
        if (needSlow) lvl = Math.max(lvl, 1);

        // Плавное мигание: пока печатаешь или двигаешь курсор, он горит ровно
        float bf = 1f;
        if (Cfg.blOn) {
            long last = Math.max(activityNs, lastInputNs);
            long idleMs = (now - last) / 1_000_000L;
            if (idleMs > Cfg.blHold) {
                float t = (idleMs - Cfg.blHold) / (float) Math.max(200, Cfg.blPeriod);
                float s = 0.5f + 0.5f * (float) Math.cos(2.0 * Math.PI * t);
                float mn = Cfg.blMin / 100f;
                bf = mn + (1f - mn) * s;
            }
            lvl = Math.max(lvl, 1);
            visible = shown(emu, visible);
        }

        // Скругление: 100% = полукруг по короткой стороне
        setRound(Cfg.crRound / 100f * 0.5f * Math.min(w, h));

        if (visible) {
            int base = curColor;

            // Свечение: несколько толстых полупрозрачных обводок, внутри они накладываются и дают спад
            if (Cfg.glStr > 0 && Cfg.glRadius > 0) {
                float gr = cellH * 0.9f * Cfg.glRadius / 100f;
                float pulse = 1f;
                if (Cfg.glPulse) {
                    pulse = 0.72f + 0.28f * (float) Math.sin(now / 1e9 * 2.0 * Math.PI / 1.6);
                    lvl = Math.max(lvl, 1);
                }
                int layers = 7;
                int per = Math.max(1, Math.round((6f + Cfg.glStr * 0.30f) * bf * pulse));
                glow.setColor(Cfg.glCustom ? (Cfg.glColor | 0xFF000000) : base);
                for (int i = layers; i >= 1; i--) {
                    glow.setStrokeWidth(2f * gr * i / layers);
                    glow.setAlpha(Math.min(255, per));
                    canvas.drawPath(path, glow);
                }
            }

            fill.setColor(base);
            fill.setAlpha(Math.round(baseAlpha * bf));
            canvas.drawPath(path, fill);

            if (outlinePct > 0) {
                stroke.setColor(base);
                stroke.setAlpha(Math.round(255f * bf));
                stroke.setStrokeWidth(Math.max(1f, cellH * 0.12f * outlinePct / 100f));
                canvas.drawPath(path, stroke);
            }
        }

        float dens = v != null ? v.getResources().getDisplayMetrics().density : 3f;
        stepParticles(canvas, now, dens);
        if (nP > 0 || nF > 0) lvl = 2;
        return lvl;
    }

    // ------------------------------------------------------------ частицы

    private static final int MAXP = 256;
    private static final float[] ppx = new float[MAXP];
    private static final float[] ppy = new float[MAXP];
    private static final float[] pvx = new float[MAXP];
    private static final float[] pvy = new float[MAXP];
    private static final float[] pAge = new float[MAXP];
    private static final float[] pLife = new float[MAXP];
    private static final float[] pSize = new float[MAXP];
    private static final int[] pCol = new int[MAXP];
    private static final byte[] pKind = new byte[MAXP];
    private static int nP = 0;
    private static long lastPNs = 0;

    private static int pickColor() {
        switch (Cfg.ptCMode) {
            case 1:
                return Cfg.ptColor | 0xFF000000;
            case 2:
                hsv[0] = (float) ((System.nanoTime() / 6_000_000L) % 360L);
                return Color.HSVToColor(hsv);
            default:
                return curColor;
        }
    }

    // kind: 0 пыль, 1 искра, 2 кольцо
    private static void spawn(float x, float y, float dx, float dy, boolean radial, int style, float dens) {
        if (nP >= MAXP) return;
        int i = nP++;
        float spread = Cfg.ptSpread / 100f;
        float sp = (0.3f + 1.7f * Cfg.ptSpeed / 100f) * dens;
        float mag = (20f + 90f * rnd.nextFloat()) * sp * (0.3f + spread);
        float ang = rnd.nextFloat() * 6.2831853f;
        float vx, vy;
        if (style == 1) {
            if (radial || (dx == 0f && dy == 0f)) {
                vx = (float) Math.cos(ang) * mag * 1.4f;
                vy = (float) Math.sin(ang) * mag * 1.4f - 40f * dens;
            } else {
                vx = (-dx * 1.2f + (rnd.nextFloat() - 0.5f) * 2f * spread) * mag * 1.5f;
                vy = (-dy * 1.2f + (rnd.nextFloat() - 0.5f) * 2f * spread) * mag * 1.5f - 40f * dens;
            }
        } else {
            vx = (float) Math.cos(ang) * mag;
            vy = (float) Math.sin(ang) * mag;
        }
        ppx[i] = x;
        ppy[i] = y;
        pvx[i] = vx;
        pvy[i] = vy;
        pAge[i] = 0f;
        pLife[i] = Cfg.ptLife * (0.6f + 0.4f * rnd.nextFloat());
        pSize[i] = dens * Cfg.ptSize * 0.5f * (0.6f + 0.8f * rnd.nextFloat());
        pCol[i] = pickColor();
        pKind[i] = (byte) (style == 1 ? 1 : 0);
    }

    private static void addRing(float x, float y, float cellH, float maxR) {
        if (nP >= MAXP) return;
        int i = nP++;
        ppx[i] = x;
        ppy[i] = y;
        pvx[i] = 0f;
        pvy[i] = 0f;
        pAge[i] = 0f;
        pLife[i] = Cfg.ptLife;
        pSize[i] = maxR;
        pCol[i] = pickColor();
        pKind[i] = 2;
    }

    /** Курсор поехал из (ox,oy) в (nx,ny), координаты в пикселях холста. */
    static void onMove(View v, float ox, float oy, float nx, float ny, float cellW, float cellH) {
        if (!Cfg.ptOn) {
            enterPending = false;
            return;
        }
        float dens = v.getResources().getDisplayMetrics().density;
        long now = System.nanoTime();
        boolean enter = enterPending && (now - enterNs) < 400_000_000L;
        enterPending = false;
        int style = Cfg.ptStyle;

        if (enter && Cfg.ptEnter) {
            if (style == 2) {
                addRing(ox + cellW / 2f, oy + cellH / 2f, cellH, cellH * 2.2f);
            } else {
                int n = Cfg.ptCount * 4;
                for (int i = 0; i < n; i++) spawn(ox + cellW / 2f, oy + cellH / 2f, 0f, 0f, true, style, dens);
            }
            return;
        }
        if (!Cfg.ptMove) return;

        float dx = nx - ox, dy = ny - oy;
        float dist = (float) Math.sqrt(dx * dx + dy * dy);
        if (style == 2) {
            addRing(nx + cellW / 2f, ny + cellH / 2f, cellH, cellH * 1.3f);
            return;
        }
        float ux = dist > 0.001f ? dx / dist : 0f;
        float uy = dist > 0.001f ? dy / dist : 0f;
        int n = Math.round(Cfg.ptCount * (1f + Math.min(2f, dist / cellW / 8f)));
        for (int i = 0; i < n; i++) {
            float t = rnd.nextFloat();
            float x = ox + dx * t + (rnd.nextFloat() - 0.5f) * cellW * 0.6f + cellW / 2f;
            float y = oy + dy * t + (rnd.nextFloat() - 0.5f) * cellH * 0.6f + cellH / 2f;
            spawn(x, y, ux, uy, false, style, dens);
        }
    }

    private static void stepParticles(Canvas canvas, long now, float dens) {
        if (nP == 0) {
            lastPNs = now;
            return;
        }
        float dtMs = Math.min((now - lastPNs) / 1_000_000f, 50f);
        lastPNs = now;
        float dt = dtMs / 1000f;
        float drag = (float) Math.exp(-2.5f * dt);

        int w = 0;
        for (int i = 0; i < nP; i++) {
            pAge[i] += dtMs;
            if (pAge[i] >= pLife[i]) continue;
            if (pKind[i] != 2) {
                pvx[i] *= drag;
                pvy[i] *= drag;
                if (pKind[i] == 1) pvy[i] += 420f * dens * dt;
                ppx[i] += pvx[i] * dt;
                ppy[i] += pvy[i] * dt;
            }
            if (w != i) {
                ppx[w] = ppx[i];
                ppy[w] = ppy[i];
                pvx[w] = pvx[i];
                pvy[w] = pvy[i];
                pAge[w] = pAge[i];
                pLife[w] = pLife[i];
                pSize[w] = pSize[i];
                pCol[w] = pCol[i];
                pKind[w] = pKind[i];
            }
            w++;
        }
        nP = w;

        for (int i = 0; i < nP; i++) {
            float t = pAge[i] / pLife[i];
            float a = 1f - t;
            pPaint.setColor(pCol[i]);
            pPaint.setAlpha(Math.round(230f * a));
            byte k = pKind[i];
            if (k == 0) {
                pPaint.setStyle(Paint.Style.FILL);
                canvas.drawCircle(ppx[i], ppy[i], Math.max(0.5f, pSize[i] * (1f - 0.55f * t)), pPaint);
            } else if (k == 1) {
                pPaint.setStyle(Paint.Style.STROKE);
                pPaint.setStrokeWidth(Math.max(1f, pSize[i] * 0.7f));
                canvas.drawLine(ppx[i], ppy[i], ppx[i] - pvx[i] * 0.035f, ppy[i] - pvy[i] * 0.035f, pPaint);
            } else {
                float e = 1f - (1f - t) * (1f - t) * (1f - t);
                pPaint.setStyle(Paint.Style.STROKE);
                pPaint.setStrokeWidth(Math.max(1f, dens * Cfg.ptSize * 0.35f));
                canvas.drawCircle(ppx[i], ppy[i], Math.max(1f, pSize[i] * e), pPaint);
            }
        }
    }
}
