package com.smoothcursor;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Typeface;
import android.os.Bundle;

import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * Эффекты поверх терминала Termux (рисуются в том же кадре, что и курсор):
 *  1) анимация появления набранных символов (5 видов);
 *  2) частицы от курсора (5 видов).
 * Всё вызывается из хука render() в главном потоке, поэтому массивы без синхронизации.
 */
final class Fx {

    // Виды появления символа
    static final int FADE = 0, SLIDE = 1, POP = 2, GLOW = 3, WIPE = 4;
    // Виды частиц
    static final int P_SPARK = 0, P_SMOKE = 1, P_CONFETTI = 2, P_STARS = 3, P_BUBBLES = 4;

    private static final int COLOR_INDEX_FOREGROUND = 256;
    private static final int COLOR_INDEX_BACKGROUND = 257;

    // Настройки (обновляются из фонового потока через setConfig)
    static volatile boolean fadeOn = false;
    static volatile int fadeStyle = FADE;
    static volatile int fadeDur = 120;
    static volatile boolean partOn = false;
    static volatile int partStyle = P_SPARK;
    static volatile int partAmount = 50;

    private static int errCount = 0;
    private static boolean broken = false;

    private Fx() {
    }

    private static void err(Throwable t) {
        errCount++;
        if (errCount <= 3) XposedBridge.log("SmoothCursor: fx error: " + t);
        if (errCount > 20) broken = true; // не мучаем Termux, если что-то в его версии устроено иначе
    }

    // Возвращает true, если что-то изменилось (нужно перерисовать)
    static boolean setConfig(Bundle b) {
        boolean fo = b.getBoolean("fade_on", false);
        int fs = clamp(b.getInt("fade_style", FADE), 0, 4);
        int fd = clamp(b.getInt("fade_dur", 120), 50, 300);
        boolean po = b.getBoolean("part_on", false);
        int ps = clamp(b.getInt("part_style", P_SPARK), 0, 4);
        int pa = clamp(b.getInt("part_amount", 50), 0, 100);
        boolean changed = fo != fadeOn || fs != fadeStyle || fd != fadeDur
                || po != partOn || ps != partStyle || pa != partAmount;
        fadeOn = fo;
        fadeStyle = fs;
        fadeDur = fd;
        partOn = po;
        partStyle = ps;
        partAmount = pa;
        return changed;
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private static float rand(float a, float b) {
        return a + (float) Math.random() * (b - a);
    }

    private static int mix(int c1, int c2, float t) {
        int r = (int) (Color.red(c1) + (Color.red(c2) - Color.red(c1)) * t);
        int g = (int) (Color.green(c1) + (Color.green(c2) - Color.green(c1)) * t);
        int b = (int) (Color.blue(c1) + (Color.blue(c2) - Color.blue(c1)) * t);
        return Color.rgb(r, g, b);
    }

    // ===================================================== появление символов

    private static final int MAXC = 24;
    private static final int[] fRow = new int[MAXC];
    private static final int[] fCol = new int[MAXC];
    private static final long[] fT0 = new long[MAXC];
    private static final String[] fCh = new String[MAXC];
    private static final int[] fFg = new int[MAXC];
    private static int fN = 0;

    private static boolean havePrev = false;
    private static int tRow, tCol;
    private static String tUnder = "";

    private static Object cachedRend = null;
    private static Paint srcPaint = null;

    private static final Paint cover = new Paint();
    private static final Paint glyph = new Paint(Paint.ANTI_ALIAS_FLAG);
    private static final PorterDuffXfermode DST_OUT = new PorterDuffXfermode(PorterDuff.Mode.DST_OUT);

    // Что напечатано в одной ячейке (пустая строка для пробела)
    private static String cell(Object emu, int row, int col) {
        Object screen = XposedHelpers.callMethod(emu, "getScreen");
        Object s = XposedHelpers.callMethod(screen, "getSelectedText", col, row, col, row);
        return s == null ? "" : (String) s;
    }

    // Цвет символа из стиля ячейки; при любой неудаче цвет текста по умолчанию
    private static int fgOf(Object emu, int row, int col) {
        int[] palette = null;
        try {
            Object colors = XposedHelpers.getObjectField(emu, "mColors");
            palette = (int[]) XposedHelpers.getObjectField(colors, "mCurrentColors");
            Object screen = XposedHelpers.callMethod(emu, "getScreen");
            Object line = XposedHelpers.callMethod(screen, "allocateFullLineIfNecessary",
                    XposedHelpers.callMethod(screen, "externalToInternalRow", row));
            long style = (Long) XposedHelpers.callMethod(line, "getStyle", col);
            Class<?> ts = XposedHelpers.findClass("com.termux.terminal.TextStyle",
                    emu.getClass().getClassLoader());
            int f = (Integer) XposedHelpers.callStaticMethod(ts, "decodeForeColor", style);
            if ((f & 0xFF000000) == 0xFF000000) return f;
            if (f >= 0 && f < palette.length) return palette[f] | 0xFF000000;
        } catch (Throwable ignored) {
        }
        if (palette != null && palette.length > COLOR_INDEX_FOREGROUND) {
            return palette[COLOR_INDEX_FOREGROUND] | 0xFF000000;
        }
        return 0xFFFFFFFF;
    }

    // Вызывается на каждом кадре: замечаем, что курсор сдвинулся вправо и в ячейке появился новый символ
    static void track(Object emu, int extRow, int col, long now) {
        if (!fadeOn || broken) {
            havePrev = false;
            return;
        }
        try {
            // Новая строка (Enter): ячейки привязаны к строке буфера, а она могла уехать вверх
            if (havePrev && extRow != tRow) fN = 0;
            if (havePrev && extRow == tRow && col > tCol && col - tCol <= 6) {
                // Стрелка вправо по тексту ничего не меняет в ячейке, набор меняет
                if (!cell(emu, extRow, tCol).equals(tUnder)) {
                    for (int c = tCol; c < col; c++) {
                        String s = cell(emu, extRow, c);
                        if (s.length() > 0 && !" ".equals(s)) {
                            addCell(extRow, c, s, fgOf(emu, extRow, c), now);
                        }
                    }
                }
            }
            tRow = extRow;
            tCol = col;
            tUnder = cell(emu, extRow, col);
            havePrev = true;
        } catch (Throwable t) {
            havePrev = false;
            err(t);
        }
    }

    private static void addCell(int row, int col, String ch, int fg, long now) {
        for (int i = 0; i < fN; i++) {
            if (fRow[i] == row && fCol[i] == col) {
                fT0[i] = now;
                fCh[i] = ch;
                fFg[i] = fg;
                return;
            }
        }
        if (fN == MAXC) removeCell(0);
        fRow[fN] = row;
        fCol[fN] = col;
        fT0[fN] = now;
        fCh[fN] = ch;
        fFg[fN] = fg;
        fN++;
    }

    private static void removeCell(int i) {
        for (int k = i; k < fN - 1; k++) {
            fRow[k] = fRow[k + 1];
            fCol[k] = fCol[k + 1];
            fT0[k] = fT0[k + 1];
            fCh[k] = fCh[k + 1];
            fFg[k] = fFg[k + 1];
        }
        fN--;
        fCh[fN] = null;
    }

    private static float outCubic(float t) {
        float u = 1f - t;
        return 1f - u * u * u;
    }

    private static float backOut(float t) {
        float u = t - 1f;
        return 1f + 2.70158f * u * u * u + 1.70158f * u * u;
    }

    // Стереть ячейку целиком или частично. Если у Termux свой фон (картинка/видео) слой прозрачный,
    // и мы вычитаем пиксели (DST_OUT). Иначе закрашиваем цветом фона терминала.
    private static void erase(Canvas c, float l, float t, float r, float b, int alpha,
                              boolean bgMode, int bgColor) {
        if (alpha <= 0) return;
        cover.setXfermode(bgMode ? DST_OUT : null);
        cover.setColor(bgMode ? 0xFF000000 : (bgColor | 0xFF000000));
        cover.setAlpha(Math.min(255, alpha));
        c.drawRect(l, t, r, b, cover);
        cover.setXfermode(null);
    }

    // Рисуется ПОД курсором. Возвращает true, пока есть что анимировать.
    static boolean drawCells(Canvas c, Object renderer, Object emu, int topRow, float cellW,
                             int cellH, float yOff, int base, long now) {
        if (!fadeOn || broken) {
            fN = 0;
            return false;
        }
        if (fN == 0) return false;
        try {
            Object rv = XposedHelpers.callMethod(emu, "isReverseVideo");
            if (rv instanceof Boolean && (Boolean) rv) {
                fN = 0;
                return false;
            }
            Object colors = XposedHelpers.getObjectField(emu, "mColors");
            int[] palette = (int[]) XposedHelpers.getObjectField(colors, "mCurrentColors");
            int bgColor = palette[COLOR_INDEX_BACKGROUND];
            boolean bgMode = Overlay.isBgActive();

            if (renderer != cachedRend) {
                cachedRend = renderer;
                srcPaint = null;
                try {
                    srcPaint = (Paint) XposedHelpers.getObjectField(renderer, "mTextPaint");
                } catch (Throwable ignored) {
                }
            }
            if (srcPaint != null) {
                glyph.setTypeface(srcPaint.getTypeface());
                glyph.setTextSize(srcPaint.getTextSize());
            } else {
                glyph.setTypeface(Typeface.MONOSPACE);
                glyph.setTextSize(cellH * 0.8f);
            }
            glyph.setFakeBoldText(false);
            glyph.setTextSkewX(0f);
            glyph.setTextScaleX(1f);
            glyph.clearShadowLayer();

            float dur = Math.max(30f, fadeDur);
            int style = fadeStyle;
            boolean alive = false;

            for (int i = fN - 1; i >= 0; i--) {
                float t = (now - fT0[i]) / 1_000_000f / dur;
                if (t >= 1f) {
                    removeCell(i);
                    continue;
                }
                if (t < 0f) t = 0f;
                alive = true;

                int row = fRow[i] - topRow;
                float l = fCol[i] * cellW;
                float r = l + cellW;
                float top = yOff + row * cellH;
                float bot = top + cellH;
                float baseline = (row + 1) * cellH; // верх ячейки минус ascent
                String s = fCh[i];
                int fg = fFg[i];
                float e = outCubic(t);

                switch (style) {
                    case SLIDE: {
                        c.save();
                        c.clipRect(l, top, r, bot);
                        erase(c, l, top, r, bot, 255, bgMode, bgColor);
                        glyph.setColor(fg);
                        glyph.setAlpha((int) (255 * Math.min(1f, e * 1.4f)));
                        c.drawText(s, 0, s.length(), l, baseline + (1f - e) * cellH * 0.75f, glyph);
                        c.restore();
                        break;
                    }
                    case POP: {
                        erase(c, l, top, r, bot, 255, bgMode, bgColor);
                        float sc = 0.35f + 0.65f * backOut(t);
                        c.save();
                        c.scale(sc, sc, l + cellW / 2f, top + cellH / 2f);
                        glyph.setColor(fg);
                        glyph.setAlpha((int) (255 * Math.min(1f, t * 2.5f)));
                        c.drawText(s, 0, s.length(), l, baseline, glyph);
                        c.restore();
                        break;
                    }
                    case GLOW: {
                        // Поверх обычного символа: яркая копия с ореолом, которая гаснет
                        float a = 1f - e;
                        glyph.setColor(base);
                        glyph.setAlpha((int) (255 * a));
                        glyph.setShadowLayer(Math.max(0.5f, cellH * 0.5f * a), 0f, 0f, base);
                        c.drawText(s, 0, s.length(), l, baseline, glyph);
                        glyph.clearShadowLayer();
                        break;
                    }
                    case WIPE: {
                        erase(c, l + e * cellW, top, r, bot, 255, bgMode, bgColor);
                        break;
                    }
                    default: { // FADE
                        erase(c, l, top, r, bot, (int) (255 * (1f - e)), bgMode, bgColor);
                        break;
                    }
                }
            }
            return alive;
        } catch (Throwable t) {
            err(t);
            fN = 0;
            return false;
        }
    }

    // ============================================================== частицы

    private static final int MAXP = 128;
    private static final float[] px = new float[MAXP];
    private static final float[] py = new float[MAXP];
    private static final float[] pvx = new float[MAXP];
    private static final float[] pvy = new float[MAXP];
    private static final float[] pLife = new float[MAXP]; // осталось, мс
    private static final float[] pMax = new float[MAXP];
    private static final float[] pSize = new float[MAXP];
    private static final float[] pRot = new float[MAXP];
    private static final float[] pVr = new float[MAXP];   // скорость вращения или роста
    private static final int[] pCol = new int[MAXP];
    private static final int[] pKind = new int[MAXP];
    private static int pn = 0;
    private static long lastPNs = 0;

    private static final float[] hsv = new float[3];
    private static final Paint pFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private static final Paint pStroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private static final Path starPath = new Path();

    static {
        pStroke.setStyle(Paint.Style.STROKE);
        pStroke.setStrokeCap(Paint.Cap.ROUND);
    }

    // x, y: центр курсора в пикселях; dx, dy: куда он сдвинулся; u: высота строки
    static void spawn(float x, float y, float dx, float dy, int color, float u, long gapMs) {
        if (!partOn || broken) return;
        try {
            int n = 1 + Math.round(partAmount / 100f * 3f);
            if (gapMs < 60) n = Math.max(1, n / 2); // автоповтор: не засыпаем экран
            float len = (float) Math.sqrt(dx * dx + dy * dy);
            float back = len > 1e-3f ? (float) Math.atan2(-dy, -dx) : 0f;
            int kind = partStyle;

            for (int k = 0; k < n; k++) {
                int i = pn < MAXP ? pn++ : (int) (Math.random() * MAXP);
                pKind[i] = kind;
                px[i] = x;
                py[i] = y;
                pRot[i] = 0f;
                pVr[i] = 0f;
                float ang, sp;
                switch (kind) {
                    case P_SMOKE:
                        px[i] += rand(-0.15f, 0.15f) * u;
                        pvx[i] = u * rand(-0.4f, 0.4f);
                        pvy[i] = -u * rand(0.6f, 1.4f);
                        pLife[i] = rand(700f, 1100f);
                        pSize[i] = u * rand(0.12f, 0.2f);
                        pVr[i] = u * rand(0.4f, 0.8f); // рост радиуса, px/с
                        pCol[i] = mix(color, 0xFF8A8A8A, 0.6f);
                        break;
                    case P_CONFETTI:
                        ang = -1.5708f + rand(-1.2f, 1.2f);
                        sp = u * rand(3f, 7f);
                        pvx[i] = (float) Math.cos(ang) * sp;
                        pvy[i] = (float) Math.sin(ang) * sp;
                        pLife[i] = rand(650f, 1100f);
                        pSize[i] = u * rand(0.1f, 0.2f);
                        pRot[i] = rand(0f, 6.2832f);
                        pVr[i] = rand(-12f, 12f);
                        hsv[0] = rand(0f, 360f);
                        hsv[1] = 0.75f;
                        hsv[2] = 1f;
                        pCol[i] = Color.HSVToColor(hsv);
                        break;
                    case P_STARS:
                        ang = rand(0f, 6.2832f);
                        sp = u * rand(0.4f, 1.6f);
                        pvx[i] = (float) Math.cos(ang) * sp;
                        pvy[i] = (float) Math.sin(ang) * sp;
                        pLife[i] = rand(450f, 800f);
                        pSize[i] = u * rand(0.18f, 0.4f);
                        pRot[i] = rand(0f, 1.5708f);
                        pVr[i] = rand(-2f, 2f);
                        pCol[i] = mix(color, 0xFFFFE082, 0.5f);
                        break;
                    case P_BUBBLES:
                        pvx[i] = u * rand(-0.3f, 0.3f);
                        pvy[i] = -u * rand(0.9f, 1.8f);
                        pLife[i] = rand(600f, 1000f);
                        pSize[i] = u * rand(0.08f, 0.14f);
                        pVr[i] = u * rand(0.3f, 0.7f);
                        pCol[i] = color;
                        break;
                    default: // искры: летят назад от направления движения курсора
                        ang = len > 1e-3f ? back + rand(-0.9f, 0.9f) : rand(0f, 6.2832f);
                        sp = u * rand(2f, 6f);
                        pvx[i] = (float) Math.cos(ang) * sp;
                        pvy[i] = (float) Math.sin(ang) * sp;
                        pLife[i] = rand(260f, 520f);
                        pSize[i] = Math.max(1.5f, u * rand(0.05f, 0.1f));
                        pCol[i] = mix(color, 0xFFFFFFFF, rand(0.2f, 0.8f));
                        break;
                }
                pMax[i] = pLife[i];
            }
        } catch (Throwable t) {
            err(t);
        }
    }

    private static void removeParticle(int i) {
        int last = --pn;
        if (i == last) return;
        px[i] = px[last];
        py[i] = py[last];
        pvx[i] = pvx[last];
        pvy[i] = pvy[last];
        pLife[i] = pLife[last];
        pMax[i] = pMax[last];
        pSize[i] = pSize[last];
        pRot[i] = pRot[last];
        pVr[i] = pVr[last];
        pCol[i] = pCol[last];
        pKind[i] = pKind[last];
    }

    private static void star(float cx, float cy, float r, float rot) {
        starPath.reset();
        for (int i = 0; i < 8; i++) {
            float a = rot + i * 0.7853982f;
            float rr = (i & 1) == 0 ? r : r * 0.28f;
            float x = cx + (float) Math.cos(a) * rr;
            float y = cy + (float) Math.sin(a) * rr;
            if (i == 0) starPath.moveTo(x, y); else starPath.lineTo(x, y);
        }
        starPath.close();
    }

    // Рисуется ПОВЕРХ курсора. Возвращает true, пока есть живые частицы.
    static boolean drawParticles(Canvas c, long now, float u) {
        if (!partOn || broken) {
            pn = 0;
            return false;
        }
        if (pn == 0) {
            lastPNs = now;
            return false;
        }
        try {
            float dt = Math.min((now - lastPNs) / 1_000_000f, 50f);
            lastPNs = now;
            float dts = dt / 1000f;

            for (int i = pn - 1; i >= 0; i--) {
                pLife[i] -= dt;
                if (pLife[i] <= 0f) {
                    removeParticle(i);
                    continue;
                }
                int k = pKind[i];
                if (k == P_CONFETTI) pvy[i] += u * 14f * dts;
                else if (k == P_SPARK) pvy[i] += u * 5f * dts;
                if (k == P_SMOKE || k == P_BUBBLES) pSize[i] += pVr[i] * dts;
                else if (k == P_CONFETTI || k == P_STARS) pRot[i] += pVr[i] * dts;
                px[i] += pvx[i] * dts;
                py[i] += pvy[i] * dts;

                float a = pLife[i] / pMax[i]; // 1 -> 0
                switch (k) {
                    case P_SMOKE:
                        pFill.setColor(pCol[i]);
                        pFill.setAlpha((int) (255 * 0.35f * a));
                        c.drawCircle(px[i], py[i], pSize[i], pFill);
                        break;
                    case P_CONFETTI: {
                        pFill.setColor(pCol[i]);
                        pFill.setAlpha((int) (255 * Math.min(1f, a * 2f)));
                        c.save();
                        c.translate(px[i], py[i]);
                        c.rotate(pRot[i] * 57.29578f);
                        c.drawRect(-pSize[i], -pSize[i] * 0.5f, pSize[i], pSize[i] * 0.5f, pFill);
                        c.restore();
                        break;
                    }
                    case P_STARS: {
                        float tw = (float) Math.sin(3.14159f * (1f - a)); // вспыхивает и гаснет
                        star(px[i], py[i], pSize[i] * (0.4f + 0.6f * tw), pRot[i]);
                        pFill.setColor(pCol[i]);
                        pFill.setAlpha((int) (255 * Math.max(0f, tw)));
                        c.drawPath(starPath, pFill);
                        break;
                    }
                    case P_BUBBLES:
                        pStroke.setColor(pCol[i]);
                        pStroke.setAlpha((int) (255 * 0.7f * a));
                        pStroke.setStrokeWidth(Math.max(1f, u * 0.04f));
                        c.drawCircle(px[i], py[i], pSize[i], pStroke);
                        break;
                    default: // искры: короткая чёрточка по направлению полёта
                        pStroke.setColor(pCol[i]);
                        pStroke.setAlpha((int) (255 * a));
                        pStroke.setStrokeWidth(pSize[i]);
                        c.drawLine(px[i], py[i], px[i] - pvx[i] * 0.03f, py[i] - pvy[i] * 0.03f, pStroke);
                        break;
                }
            }
        } catch (Throwable t) {
            err(t);
            pn = 0;
            return false;
        }
        return pn > 0;
    }
}
