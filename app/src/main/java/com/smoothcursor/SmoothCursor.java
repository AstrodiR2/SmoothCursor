package com.smoothcursor;

import android.app.Activity;
import android.graphics.BlurMaskFilter;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.net.Uri;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.View;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

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
    private static volatile boolean cfgGlow = false;
    private static volatile int cfgGlowRadius = 6;
    private static volatile int cfgGlowIntensity = 70;
    private static volatile float glowRadiusPx = 6f;
    private static volatile int glowAlpha = 178;
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
    private static final String EXTRA_SAVED = "sc_saved";

    private static final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private static final Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private static final Path path = new Path();

    // Свечение текста: shadow layer на mTextPaint рендерера, цвет берётся из стиля ячейки
    private static final Paint glowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private static float glowMaskRadius = -1f;
    private static boolean glowApplied = false;
    private static boolean glowBroken = false;
    // Плавное появление текста
    private static volatile boolean cfgFade = true;
    private static volatile int cfgFadeMs = 160;
    private static boolean fadeBroken = false;
    private static boolean fadeActive = false;
    private static long fadeUntil = 0L;
    private static long fNow = 0L;
    private static int fRowsN = 0, fColsN = 0, fLs = 1, fLsa = 0;
    private static float fwPx = 1f;
    private static char[][] fChars, fCur;
    private static int[] fHash, fCurHash;
    private static long[][] fBorn, fBornTmp;
    private static Method mExt, mAlloc, mSpace;
    private static Field fText;

    private static final float[] GLOW_PASSES = {1.0f, 0.6f, 0.35f, 0.2f};
    private static final Paint glowPaint2 = new Paint(Paint.ANTI_ALIAS_FLAG);
    private static float glowMaskRadius2 = -1f;
    private static Field textPaintField;
    private static Method tsFore, tsBack, tsEffect;
    private static int idxBackground = 257;
    private static int fxInverse = 16;
    private static int fxInvisible = 32;

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
                    TermImages.onViewDraw((View) p.thisObject);
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
                XposedBridge.log("SmoothCursor v5: TerminalRenderer NOT FOUND");
                return;
            }

            // Картинки в терминале (iTerm2 / kitty), см. TermImages.java
            try {
                TermImages.install(cl, emuCls);
            } catch (Throwable t) {
                XposedBridge.log("SmoothCursor: images install failed: " + t);
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
                        TermImages.frameBegin(p.thisObject, p.args[0], (Integer) p.args[2]);
                    } catch (Throwable t) {
                        XposedBridge.log("SmoothCursor: images frame error: " + t);
                    }
                    try {
                        fadeFrame(p);
                    } catch (Throwable t) {
                        fadeBroken = true;
                        XposedBridge.log("SmoothCursor: fade frame error: " + t);
                    }
                    try {
                        Overlay.bgBefore(p);
                    } catch (Throwable t) {
                        XposedBridge.log("SmoothCursor: bg error: " + t);
                    }
                }

                @Override
                protected void afterHookedMethod(MethodHookParam p) {
                    inRender = false;
                    try {
                        Overlay.bgAfter(p);
                    } catch (Throwable ignored) {
                    }
                    if (fadeActive) {
                        View fv = viewRef.get();
                        if (fv != null) fv.postInvalidateOnAnimation();
                    }
                    try {
                        TermImages.frameEnd(p.args);
                    } catch (Throwable t) {
                        XposedBridge.log("SmoothCursor: images draw error: " + t);
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

            try {
                initGlow(cl, rendCls);
            } catch (Throwable t) {
                glowBroken = true;
                XposedBridge.log("SmoothCursor: glow init failed: " + t);
            }

            XposedBridge.log("SmoothCursor v5: hooks installed, renderer=" + rendCls.getName());
        } catch (Throwable t) {
            XposedBridge.log("SmoothCursor v5: init failed: " + t);
        }
    }

    private static void initGlow(ClassLoader cl, Class<?> rendCls) {
        if (XposedBridge.hookAllMethods(rendCls, "drawTextRun", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam p) {
                TermImages.beforeTextRun(p.args);
                try {
                    if (fadeBefore(p)) return;
                } catch (Throwable t) {
                    fadeBroken = true;
                    XposedBridge.log("SmoothCursor: fade disabled, error: " + t);
                }
                glowBefore(p.thisObject, p.method, p.args);
            }
        }).isEmpty()) {
            glowBroken = true;
            fadeBroken = true;
            XposedBridge.log("SmoothCursor: drawTextRun NOT FOUND, glow/fade off");
        }
        Class<?> ts = XposedHelpers.findClass("com.termux.terminal.TextStyle", cl);
        tsFore = XposedHelpers.findMethodExact(ts, "decodeForeColor", long.class);
        tsBack = XposedHelpers.findMethodExact(ts, "decodeBackColor", long.class);
        tsEffect = XposedHelpers.findMethodExact(ts, "decodeEffect", long.class);
        idxBackground = XposedHelpers.getStaticIntField(ts, "COLOR_INDEX_BACKGROUND");
        try {
            fxInverse = XposedHelpers.getStaticIntField(ts, "CHARACTER_ATTRIBUTE_INVERSE");
            fxInvisible = XposedHelpers.getStaticIntField(ts, "CHARACTER_ATTRIBUTE_INVISIBLE");
        } catch (Throwable ignored) {
        }
        try {
            textPaintField = XposedHelpers.findField(rendCls, "mTextPaint");
        } catch (Throwable ignored) {
            for (Field f : rendCls.getDeclaredFields()) {
                if (Paint.class.isAssignableFrom(f.getType())) {
                    f.setAccessible(true);
                    textPaintField = f;
                    break;
                }
            }
        }
        if (textPaintField == null) throw new IllegalStateException("text paint not found");
        glowPaint.setStyle(Paint.Style.FILL);
    }

    // Перед отрисовкой каждого куска текста ставим тень цветом самого текста.
    // Ячейки с цветным фоном и инверсией пропускаем, чтобы вокруг подсветки vim не было ореола.
    private static void glowBefore(Object self, java.lang.reflect.Member method, Object[] args) {
        if (glowBroken) return;
        try {
            Paint paint = (Paint) textPaintField.get(self);
            if (paint == null) return;
            if (!cfgGlow) {
                if (glowApplied) {
                    paint.clearShadowLayer();
                    glowApplied = false;
                }
                return;
            }
            long style = 0L;
            boolean hasStyle = false;
            boolean reverse = false;
            int[] palette = null;
            for (Object a : args) {
                if (a instanceof Long) {
                    style = (Long) a;
                    hasStyle = true;
                } else if (a instanceof Boolean) {
                    if ((Boolean) a) reverse = true;
                } else if (a instanceof int[] && palette == null) {
                    palette = (int[]) a;
                }
            }
            if (!hasStyle || palette == null) return;

            boolean skip = reverse;
            int fore = 0;
            if (!skip) {
                int effect = (Integer) tsEffect.invoke(null, style);
                int back = (Integer) tsBack.invoke(null, style);
                fore = (Integer) tsFore.invoke(null, style);
                skip = (effect & (fxInverse | fxInvisible)) != 0 || back != idxBackground;
            }
            if (skip) {
                if (glowApplied) {
                    paint.clearShadowLayer();
                    glowApplied = false;
                }
                return;
            }
            int color;
            if ((fore & 0xFF000000) == 0xFF000000) color = fore;
            else if (fore >= 0 && fore < palette.length) color = palette[fore];
            else return;
            int shadowColor = (glowAlpha << 24) | (color & 0x00FFFFFF);
            // Одна тень от тонких глифов почти невидима (пик ~10%). Рисуем текст несколькими
            // проходами с убывающим радиусом: тени накладываются и дают плотное свечение.
            boolean hasCursor = args.length > 9 && args[9] instanceof Integer && (Integer) args[9] != 0;
            if (!hasCursor && method != null) {
                for (float f : GLOW_PASSES) {
                    paint.setShadowLayer(Math.max(1f, glowRadiusPx * f), 0f, 0f, shadowColor);
                    try {
                        XposedBridge.invokeOriginalMethod(method, self, args);
                    } catch (Throwable ignored) {
                    }
                }
            }
            paint.setShadowLayer(Math.max(1f, glowRadiusPx * 0.12f), 0f, 0f, shadowColor);
            glowApplied = true;
        } catch (Throwable t) {
            glowBroken = true;
            XposedBridge.log("SmoothCursor: glow disabled, error: " + t);
        }
    }


    // Раз за кадр: сравниваем экран с прошлым кадром (с учётом сдвига при скролле)
    // и запоминаем время появления каждого нового символа
    private static void fadeFrame(XC_MethodHook.MethodHookParam p) throws Throwable {
        fadeActive = false;
        if (!cfgFade || fadeBroken) return;
        Object rend = p.thisObject;
        Object emu = p.args[0];
        int topRow = (Integer) p.args[2];
        int rows = XposedHelpers.getIntField(emu, "mRows");
        int cols = XposedHelpers.getIntField(emu, "mColumns");
        fwPx = XposedHelpers.getFloatField(rend, "mFontWidth");
        fLs = Math.max(1, XposedHelpers.getIntField(rend, "mFontLineSpacing"));
        fLsa = XposedHelpers.getIntField(rend, "mFontLineSpacingAndAscent");
        Object scr = XposedHelpers.callMethod(emu, "getScreen");
        if (mExt == null) {
            mExt = XposedHelpers.findMethodExact(scr.getClass(), "externalToInternalRow", int.class);
            mAlloc = XposedHelpers.findMethodExact(scr.getClass(), "allocateFullLineIfNecessary", int.class);
        }
        long now = System.nanoTime();
        fNow = now;
        boolean reset = fBorn == null || rows != fRowsN || cols != fColsN;
        if (reset) {
            fChars = new char[rows][cols];
            fCur = new char[rows][cols];
            fHash = new int[rows];
            fCurHash = new int[rows];
            fBorn = new long[rows][cols];
            fBornTmp = new long[rows][cols];
        }
        boolean changed = reset;
        for (int r = 0; r < rows; r++) {
            Object line = mAlloc.invoke(scr, mExt.invoke(scr, topRow + r));
            if (mSpace == null) {
                mSpace = XposedHelpers.findMethodExact(line.getClass(), "getSpaceUsed");
                fText = XposedHelpers.findField(line.getClass(), "mText");
            }
            char[] t = (char[]) fText.get(line);
            int used = (Integer) mSpace.invoke(line);
            int n = Math.min(Math.min(used, t.length), cols);
            char[] c = fCur[r];
            int h = 0;
            for (int i = 0; i < cols; i++) {
                char ch = i < n ? t[i] : ' ';
                c[i] = ch;
                h = 31 * h + ch;
            }
            fCurHash[r] = h;
            if (reset) {
                System.arraycopy(c, 0, fChars[r], 0, cols);
                fHash[r] = h;
            } else if (h != fHash[r]) {
                changed = true;
            }
        }
        if (reset) {
            fRowsRowsSet(rows, cols);
            return;
        }
        if (changed) {
            int blank = 0;
            for (int i = 0; i < cols; i++) blank = 31 * blank + ' ';
            int bestK = 0, bestScore = -1;
            for (int d = 0; d < rows; d++) {
                for (int sg = 0; sg < (d == 0 ? 1 : 2); sg++) {
                    int k = sg == 0 ? d : -d;
                    int score = 0;
                    for (int r = 0; r < rows; r++) {
                        int s = r + k;
                        if (s < 0 || s >= rows) continue;
                        if (fCurHash[r] == fHash[s] && fCurHash[r] != blank) score++;
                    }
                    if (score > bestScore) {
                        bestScore = score;
                        bestK = k;
                    }
                }
            }
            int fresh = 0;
            for (int r = 0; r < rows; r++) {
                int s = r + bestK;
                boolean ok = s >= 0 && s < rows;
                char[] c = fCur[r];
                for (int i = 0; i < cols; i++) {
                    char ch = c[i];
                    char prev = ok ? fChars[s][i] : ' ';
                    if (ok && prev == ch) {
                        fBornTmp[r][i] = fBorn[s][i];
                    } else if (ch == ' ' || prev != ' ') {
                        // пробел или замена символа на месте (прогресс-бары, спиннеры): без анимации
                        fBornTmp[r][i] = 0L;
                    } else {
                        fBornTmp[r][i] = now;
                        fresh++;
                    }
                }
            }
            // clear, открытие vim и т.п.: меняется пол-экрана сразу, не анимируем
            if (fresh > rows * cols / 3) {
                for (int r = 0; r < rows; r++) {
                    for (int i = 0; i < cols; i++) {
                        if (fBornTmp[r][i] == now) fBornTmp[r][i] = 0L;
                    }
                }
                fresh = 0;
            }
            if (fresh > 0) fadeUntil = now + cfgFadeMs * 1000000L;
            long[][] tb = fBorn;
            fBorn = fBornTmp;
            fBornTmp = tb;
            char[][] tc = fChars;
            fChars = fCur;
            fCur = tc;
            int[] th = fHash;
            fHash = fCurHash;
            fCurHash = th;
        }
        fadeActive = now < fadeUntil;
    }

    private static void fRowsRowsSet(int rows, int cols) {
        fRowsN = rows;
        fColsN = cols;
        fadeUntil = 0L;
    }

    private static int fadeLevel(long born, long now, long fadeNs) {
        if (born == 0L) return 16;
        float t = (now - born) / (float) fadeNs;
        if (t >= 1f) return 16;
        if (t < 0f) t = 0f;
        float s = t * t * (3f - 2f * t);
        return (int) (s * 16f);
    }

    // Рисуем только что появившиеся символы через слой с прозрачностью.
    // true = сами всё нарисовали, оригинальный drawTextRun не нужен
    private static boolean fadeBefore(XC_MethodHook.MethodHookParam p) throws Throwable {
        if (!cfgFade || fadeBroken || !fadeActive || fBorn == null) return false;
        Object[] a = p.args;
        if (a.length < 13 || !(a[0] instanceof Canvas)) return false;
        int startCol = (Integer) a[4];
        int runCols = (Integer) a[5];
        int startIdx = (Integer) a[6];
        int runChars = (Integer) a[7];
        float mes = (Float) a[8];
        int cursor = (Integer) a[9];
        if (cursor != 0 || runCols != runChars || runCols <= 0) return false;
        float fw = fwPx;
        if (Math.abs(mes / fw - runCols) > 0.01f) return false;
        float y = (Float) a[3];
        int row = Math.round((y - fLsa) / fLs) - 1;
        if (row < 0 || row >= fRowsN || startCol < 0 || startCol + runCols > fColsN) return false;
        long fadeNs = Math.max(1, cfgFadeMs) * 1000000L;
        long now = fNow;
        long[] br = fBorn[row];
        boolean any = false;
        for (int i = 0; i < runCols; i++) {
            long b = br[startCol + i];
            if (b != 0L && now - b < fadeNs) {
                any = true;
                break;
            }
        }
        if (!any) return false;
        Canvas canvas = (Canvas) a[0];
        int i = 0;
        while (i < runCols) {
            int lvl = fadeLevel(br[startCol + i], now, fadeNs);
            int j = i + 1;
            while (j < runCols && fadeLevel(br[startCol + j], now, fadeNs) == lvl) j++;
            if (lvl > 0) {
                Object[] b = a.clone();
                int cols = j - i;
                b[4] = startCol + i;
                b[5] = cols;
                b[6] = startIdx + i;
                b[7] = cols;
                b[8] = cols * fw;
                int save = -1;
                if (lvl < 16) {
                    float pad = glowRadiusPx * 2f + fw;
                    save = canvas.saveLayerAlpha((startCol + i) * fw - pad, y - fLs - pad,
                            (startCol + j) * fw + pad, y + pad, lvl * 255 / 16);
                }
                try {
                    glowBefore(p.thisObject, p.method, b);
                    XposedBridge.invokeOriginalMethod(p.method, p.thisObject, b);
                } finally {
                    if (save >= 0) canvas.restoreToCount(save);
                }
            }
            i = j;
        }
        p.setResult(null);
        return true;
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
                    boolean gOn = b.getBoolean("glow_on", false);
                    int gR = b.getInt("glow_radius", 6);
                    int gI = b.getInt("glow_intensity", 70);
                    TermImages.setConfig(b.getBoolean("img_on", true), b.getBoolean("img_behind", false));
                    cfgFade = b.getBoolean("fade_on", true);
                    cfgFadeMs = Math.max(50, b.getInt("fade_ms", 160));
                    boolean changed = ut != cfgUseTheme || c != cfgColor || o != cfgOutline || tr != cfgTrail
                            || ss != cfgSmoothScroll || sv != cfgScrollSpeed
                            || gOn != cfgGlow || gR != cfgGlowRadius || gI != cfgGlowIntensity;
                    cfgGlow = gOn;
                    cfgGlowRadius = gR;
                    cfgGlowIntensity = gI;
                    float dens = v.getResources().getDisplayMetrics().density;
                    glowRadiusPx = Math.max(1, gR) * dens;
                    glowAlpha = Math.max(0, Math.min(255, gI * 255 / 100));
                    cfgSmoothScroll = ss;
                    cfgScrollSpeed = sv;
                    cfgUseTheme = ut;
                    cfgColor = c;
                    cfgOutline = o;
                    cfgTrail = tr;
                    Overlay.onConfig(v, b);
                    if (changed) v.postInvalidate();
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
        Canvas canvas = (Canvas) p.args[1];
        int topRow = (Integer) p.args[2];
        long now = System.nanoTime();

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
            if (d != 0) {
                float lim = cellH;
                scrollOff = Math.max(-lim, Math.min(lim, scrollOff + d * (float) cellH));
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
        if (cfgSmoothScroll && topRow != cursorTopRow) inited = false;
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

        if (!inited) {
            for (int i = 0; i < 4; i++) { cx[i] = tx[i]; cy[i] = ty[i]; }
            inited = true;
            lastCol = col; lastRow = row; lastStyle = style;
        } else if (col != lastCol || row != lastRow || style != lastStyle) {
            retarget(tx, ty, cellW, cellH);
            lastCol = col; lastRow = row; lastStyle = style;
            dtMs = wasMoving ? Math.min((now - lastNs) / 1_000_000f, 50f) : 0f;
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

        wasMoving = moving;

        if (nativeVisible) {
            path.reset();
            for (int i = 0; i < 4; i++) {
                float px = cx[i] * cellW;
                float py = yOff + (cy[i] + Y_SHIFT_CELLS) * cellH;
                if (i == 0) path.moveTo(px, py); else path.lineTo(px, py);
            }
            path.close();

            int base = (cfgUseTheme ? cursorColor(emu) : cfgColor) | 0xFF000000;

            if (cfgGlow) {
                float gr = glowRadiusPx;
                if (gr != glowMaskRadius) {
                    glowPaint.setMaskFilter(new BlurMaskFilter(Math.max(1f, gr), BlurMaskFilter.Blur.NORMAL));
                    glowMaskRadius = gr;
                }
                glowPaint.setColor(base);
                glowPaint.setAlpha(glowAlpha);
                canvas.drawPath(path, glowPaint);
                float gr2 = Math.max(1f, gr * 0.4f);
                if (gr2 != glowMaskRadius2) {
                    glowPaint2.setMaskFilter(new BlurMaskFilter(gr2, BlurMaskFilter.Blur.NORMAL));
                    glowMaskRadius2 = gr2;
                }
                glowPaint2.setColor(base);
                glowPaint2.setAlpha(glowAlpha);
                canvas.drawPath(path, glowPaint2);
            }

            fillPaint.setColor(base);
            fillPaint.setAlpha(CURSOR_ALPHA);
            canvas.drawPath(path, fillPaint);

            int o = cfgOutline;
            if (o > 0) {
                strokePaint.setColor(base);
                strokePaint.setAlpha(255);
                strokePaint.setStrokeWidth(Math.max(1f, cellH * 0.12f * o / 100f));
                canvas.drawPath(path, strokePaint);
            }
        }

        if (moving) {
            View v = viewRef.get();
            if (v != null) v.postInvalidateOnAnimation();
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
