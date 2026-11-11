package com.smoothcursor;

import android.app.Activity;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.SurfaceTexture;
import android.graphics.Typeface;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.FrameLayout;
import android.widget.ImageView;

import java.lang.ref.WeakReference;
import java.util.Random;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * Скринсейвер при простое, внутри процесса Termux.
 * Ничего не делает и ничего не рисует, пока Termux свёрнут: таймер и анимации живут только
 * между onResume и onPause, видео освобождается при сворачивании.
 */
final class Saver {

    private static final Uri URI_IMG = Uri.parse("content://com.smoothcursor.settings/ssimg");
    private static final Uri URI_VID = Uri.parse("content://com.smoothcursor.settings/ssvid");
    private static final String TERMUX_ACT = "com.termux.app.TermuxActivity";

    private static final Handler H = new Handler(Looper.getMainLooper());
    private static final Runnable TICK = Saver::check;

    private static WeakReference<Activity> actRef = new WeakReference<>(null);
    private static volatile long lastActiveMs = SystemClock.uptimeMillis();
    private static volatile long lastOutputMs = 0;
    private static boolean resumed = false;
    private static SaverView view = null;

    private Saver() {
    }

    private static boolean isTermux(Object o) {
        return o != null && TERMUX_ACT.equals(o.getClass().getName());
    }

    static void install(ClassLoader cl) {
        XposedBridge.hookAllMethods(Activity.class, "onResume", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam p) {
                try {
                    if (isTermux(p.thisObject)) onResume((Activity) p.thisObject);
                } catch (Throwable t) {
                    XposedBridge.log("SmoothCursor: saver onResume: " + t);
                }
            }
        });
        XposedBridge.hookAllMethods(Activity.class, "onPause", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam p) {
                try {
                    if (isTermux(p.thisObject)) onPause();
                } catch (Throwable t) {
                    XposedBridge.log("SmoothCursor: saver onPause: " + t);
                }
            }
        });
        // Любое касание или клавиша, дошедшие до activity
        XposedBridge.hookAllMethods(Activity.class, "onUserInteraction", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam p) {
                try {
                    if (isTermux(p.thisObject)) poke();
                } catch (Throwable ignored) {
                }
            }
        });
        // Новый вывод в терминале (нужен для «не включать, пока идёт вывод»)
        Class<?> viewCls = XposedHelpers.findClassIfExists("com.termux.view.TerminalView", cl);
        if (viewCls != null) {
            XposedBridge.hookAllMethods(viewCls, "onScreenUpdated", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam p) {
                    lastOutputMs = SystemClock.uptimeMillis();
                }
            });
        }
    }

    private static void onResume(Activity a) {
        actRef = new WeakReference<>(a);
        resumed = true;
        lastActiveMs = SystemClock.uptimeMillis();
        check();
    }

    private static void onPause() {
        resumed = false;
        H.removeCallbacks(TICK);
        dismiss(false);
    }

    /** Пользователь что-то сделал: сбрасываем простой и прячем скринсейвер, если он показан. */
    static void poke() {
        lastActiveMs = SystemClock.uptimeMillis();
        if (view != null) dismiss(true);
    }

    /** Пришли свежие настройки (из фонового потока). */
    static void onCfg() {
        H.post(() -> {
            if (!resumed) return;
            if (view != null) {
                if (Cfg.ssMode == 0) dismiss(false);
            } else {
                check();
            }
        });
    }

    private static void check() {
        H.removeCallbacks(TICK);
        if (!resumed || view != null || Cfg.ssMode == 0) return;
        long now = SystemClock.uptimeMillis();
        long last = lastActiveMs;
        if (Cfg.ssNoOut) last = Math.max(last, lastOutputMs);
        long remain = last + Cfg.idleMs() - now;
        if (remain > 0) {
            H.postDelayed(TICK, remain + 30);
            return;
        }
        show();
    }

    private static void show() {
        Activity a = actRef.get();
        if (a == null) return;
        ViewGroup content = a.findViewById(android.R.id.content);
        if (content == null) return;
        try {
            SaverView sv = new SaverView(a, Cfg.ssMode);
            view = sv;
            content.addView(sv, new FrameLayout.LayoutParams(-1, -1));
            sv.fadeIn(Cfg.ssFade);
        } catch (Throwable t) {
            XposedBridge.log("SmoothCursor: saver show error: " + t);
            view = null;
        }
    }

    private static void dismiss(boolean animate) {
        SaverView sv = view;
        if (sv == null) return;
        view = null;
        lastActiveMs = SystemClock.uptimeMillis();
        if (animate && Cfg.ssFade > 0) sv.fadeOutAndRemove(Math.min(Cfg.ssFade, 500));
        else sv.removeNow();
        check();
    }

    // ------------------------------------------------------------ окно скринсейвера

    static final class SaverView extends FrameLayout implements TextureView.SurfaceTextureListener {
        private final int mode;
        private final Handler h = new Handler(Looper.getMainLooper());
        private ImageView img;
        private TextureView tv;
        private MediaPlayer mp;
        private Surface surface;
        private int vw, vh;
        private boolean prepared, restarting, fadingOut, gone;
        private int effFade = 800;
        private int token = 0;

        private final Runnable poll = new Runnable() {
            @Override
            public void run() {
                pollVideo();
            }
        };

        SaverView(Context c, int m) {
            super(c);
            mode = m;
            setBackgroundColor(0xFF000000);
            setClickable(true); // глотаем касания, терминал под ним не трогается
            setOnClickListener(v -> poke());
            setAlpha(0f);

            if (mode == 1) {
                addView(new MatrixView(c), new LayoutParams(-1, -1));
            } else if (mode == 2) {
                addView(new DustView(c), new LayoutParams(-1, -1));
            } else if (mode == 3) {
                img = new ImageView(c);
                img.setScaleType(ImageView.ScaleType.CENTER_CROP);
                addView(img, new LayoutParams(-1, -1));
                loadImage(token);
            } else if (mode == 4) {
                tv = new TextureView(c);
                tv.setSurfaceTextureListener(this);
                addView(tv, new LayoutParams(-1, -1));
            }
            if (mode == 3 || mode == 4) {
                View dim = new View(c);
                dim.setBackgroundColor(Color.argb(Math.max(0, Math.min(100, Cfg.ssDim)) * 255 / 100, 0, 0, 0));
                addView(dim, new LayoutParams(-1, -1));
            }
        }

        void fadeIn(int ms) {
            if (ms <= 0) setAlpha(1f);
            else animate().alpha(1f).setDuration(ms).start();
        }

        void fadeOutAndRemove(int ms) {
            animate().alpha(0f).setDuration(ms).withEndAction(this::removeNow).start();
        }

        void removeNow() {
            if (gone) return;
            gone = true;
            animate().cancel();
            token++;
            releaseVideo();
            ViewParent p = getParent();
            if (p instanceof ViewGroup) ((ViewGroup) p).removeView(this);
        }

        // ---- фото

        private void loadImage(final int myToken) {
            final Context ctx = getContext();
            new Thread(() -> {
                Bitmap bm = null;
                try (ParcelFileDescriptor pfd = ctx.getContentResolver().openFileDescriptor(URI_IMG, "r")) {
                    if (pfd != null) bm = BitmapFactory.decodeFileDescriptor(pfd.getFileDescriptor());
                } catch (Throwable t) {
                    XposedBridge.log("SmoothCursor: saver image error: " + t);
                }
                final Bitmap fb = bm;
                post(() -> {
                    if (fb == null || myToken != token || img == null) return;
                    img.setImageBitmap(fb);
                });
            }).start();
        }

        // ---- видео с плавным зацикливанием

        private void openPlayer(SurfaceTexture st) {
            stopPlayer();
            ParcelFileDescriptor pfd = null;
            try {
                pfd = getContext().getContentResolver().openFileDescriptor(URI_VID, "r");
                if (pfd == null) return;
                final boolean smooth = Cfg.ssVFade;
                MediaPlayer m = new MediaPlayer();
                mp = m;
                m.setDataSource(pfd.getFileDescriptor());
                surface = new Surface(st);
                m.setSurface(surface);
                m.setLooping(!smooth);
                m.setVolume(0f, 0f);
                m.setOnVideoSizeChangedListener((x, w, hh) -> {
                    vw = w;
                    vh = hh;
                    fit();
                });
                m.setOnPreparedListener(x -> {
                    prepared = true;
                    int dur = 0;
                    try {
                        dur = x.getDuration();
                    } catch (Throwable ignored) {
                    }
                    effFade = Cfg.ssVFadeMs;
                    if (dur > 0) effFade = Math.max(100, Math.min(effFade, dur / 3));
                    if (tv != null) {
                        tv.setAlpha(0f);
                        tv.animate().alpha(1f).setDuration(smooth ? effFade : 300).start();
                    }
                    x.start();
                    if (smooth) h.postDelayed(poll, 60);
                });
                m.setOnSeekCompleteListener(x -> {
                    if (!restarting) return;
                    restarting = false;
                    fadingOut = false;
                    try {
                        x.start();
                    } catch (Throwable ignored) {
                    }
                    if (tv != null) tv.animate().alpha(1f).setDuration(effFade).start();
                    h.postDelayed(poll, 60);
                });
                m.setOnCompletionListener(x -> {
                    if (!smooth || gone) return;
                    // Картинка уже должна быть погашена; держим чёрный экран и начинаем сначала
                    if (tv != null) tv.setAlpha(0f);
                    h.postDelayed(() -> {
                        MediaPlayer cur = mp;
                        if (cur == null || gone) return;
                        try {
                            restarting = true;
                            cur.seekTo(0);
                        } catch (Throwable t) {
                            restarting = false;
                        }
                    }, Math.max(0, Cfg.ssVPause));
                });
                m.setOnErrorListener((x, what, extra) -> {
                    XposedBridge.log("SmoothCursor: saver video error " + what + "/" + extra);
                    return true;
                });
                m.prepareAsync();
            } catch (Throwable t) {
                XposedBridge.log("SmoothCursor: saver video open error: " + t);
                stopPlayer();
            } finally {
                if (pfd != null) {
                    try {
                        pfd.close();
                    } catch (Throwable ignored) {
                    }
                }
            }
        }

        // Раз в 60 мс смотрим, не пора ли гасить картинку перед концом видео
        private void pollVideo() {
            MediaPlayer m = mp;
            if (m == null || gone || !prepared || restarting) return;
            try {
                if (m.isPlaying() && !fadingOut) {
                    int left = m.getDuration() - m.getCurrentPosition();
                    if (left <= effFade) {
                        fadingOut = true;
                        if (tv != null) tv.animate().alpha(0f).setDuration(Math.max(100, left)).start();
                    }
                }
            } catch (Throwable ignored) {
            }
            h.postDelayed(poll, 60);
        }

        private void fit() {
            TextureView t = tv;
            if (t == null) return;
            t.setTransform(Overlay.fitMatrix(t.getWidth(), t.getHeight(), vw, vh, true));
        }

        private void stopPlayer() {
            h.removeCallbacks(poll);
            prepared = false;
            restarting = false;
            fadingOut = false;
            MediaPlayer m = mp;
            mp = null;
            if (m != null) {
                try {
                    m.release();
                } catch (Throwable ignored) {
                }
            }
            Surface s = surface;
            surface = null;
            if (s != null) {
                try {
                    s.release();
                } catch (Throwable ignored) {
                }
            }
        }

        private void releaseVideo() {
            h.removeCallbacksAndMessages(null);
            stopPlayer();
            if (tv != null) {
                tv.setSurfaceTextureListener(null);
                tv = null;
            }
        }

        @Override
        public void onSurfaceTextureAvailable(SurfaceTexture st, int w, int hh) {
            openPlayer(st);
        }

        @Override
        public void onSurfaceTextureSizeChanged(SurfaceTexture st, int w, int hh) {
            fit();
        }

        @Override
        public boolean onSurfaceTextureDestroyed(SurfaceTexture st) {
            stopPlayer();
            return true;
        }

        @Override
        public void onSurfaceTextureUpdated(SurfaceTexture st) {
        }

        // Termux свёрнут или экран погас: не играем и не рисуем в фоне
        @Override
        protected void onWindowVisibilityChanged(int visibility) {
            super.onWindowVisibilityChanged(visibility);
            if (visibility != View.VISIBLE && Saver.view == this) Saver.dismiss(false);
        }

        @Override
        protected void onDetachedFromWindow() {
            super.onDetachedFromWindow();
            token++;
            releaseVideo();
        }
    }

    // ------------------------------------------------------------ матричный дождь

    static final class MatrixView extends View {
        private static final int GLYPHS = 64;

        private final Paint head = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint body = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Random r = new Random();
        private final float size;
        private final float colW;
        private final int base;
        private final int headColor;
        private final char[] set;
        private int cols;
        private float[] y, speed;
        private int[] len;
        private char[][] g;
        private long last = 0;

        MatrixView(Context c) {
            super(c);
            float dens = c.getResources().getDisplayMetrics().density;
            size = Math.max(6f, Cfg.ssSize) * dens;
            colW = size * 0.95f;
            base = Cfg.ssColor | 0xFF000000;
            int rr = (Color.red(base) + 255 * 3) / 4;
            int gg = (Color.green(base) + 255 * 3) / 4;
            int bb = (Color.blue(base) + 255 * 3) / 4;
            headColor = Color.rgb(rr, gg, bb);
            for (Paint p : new Paint[]{head, body}) {
                p.setTypeface(Typeface.MONOSPACE);
                p.setTextAlign(Paint.Align.CENTER);
                p.setTextSize(size);
            }
            set = buildSet(Cfg.ssChars);
        }

        private static char[] buildSet(int kind) {
            if (kind == 1) return "01".toCharArray();
            if (kind == 2) return "0123456789ABCDEF".toCharArray();
            StringBuilder sb = new StringBuilder();
            for (char ch = 0x30A1; ch <= 0x30F6; ch++) sb.append(ch);
            sb.append("0123456789");
            return sb.toString().toCharArray();
        }

        @Override
        protected void onSizeChanged(int w, int h, int ow, int oh) {
            super.onSizeChanged(w, h, ow, oh);
            cols = Math.max(1, (int) (w / colW));
            y = new float[cols];
            speed = new float[cols];
            len = new int[cols];
            g = new char[cols][GLYPHS];
            for (int i = 0; i < cols; i++) resetCol(i, true);
        }

        private void resetCol(int i, boolean initial) {
            float h = getHeight();
            len[i] = 8 + r.nextInt(18);
            speed[i] = (0.5f + r.nextFloat()) * (6f + 22f * Cfg.ssSpeed / 100f) * size;
            float dens = Math.max(0.05f, Math.min(1f, Cfg.ssDensity / 100f));
            float delay = r.nextFloat() * h * (1f - dens) * 3f;
            y[i] = -len[i] * size - delay;
            if (initial) y[i] += r.nextFloat() * h;
            for (int k = 0; k < GLYPHS; k++) g[i][k] = set[r.nextInt(set.length)];
        }

        @Override
        protected void onDraw(Canvas canvas) {
            if (y == null) return;
            long now = SystemClock.uptimeMillis();
            float dt = last == 0 ? 0.033f : Math.min((now - last) / 1000f, 0.1f);
            last = now;
            canvas.drawColor(0xFF000000);
            float hgt = getHeight();

            for (int i = 0; i < cols; i++) {
                y[i] += speed[i] * dt;
                if (y[i] - len[i] * size > hgt) {
                    resetCol(i, false);
                    continue;
                }
                if (r.nextInt(12) == 0) g[i][r.nextInt(GLYPHS)] = set[r.nextInt(set.length)];
                float x = i * colW + colW / 2f;
                int headRow = (int) Math.floor(y[i] / size);
                for (int k = 0; k < len[i]; k++) {
                    float yy = y[i] - k * size;
                    if (yy < -size || yy > hgt + size) continue;
                    int idx = (((headRow - k) % GLYPHS) + GLYPHS) % GLYPHS;
                    if (k == 0) {
                        head.setColor(headColor);
                        canvas.drawText(g[i], idx, 1, x, yy, head);
                    } else {
                        float f = 1f - k / (float) len[i];
                        body.setColor(base);
                        body.setAlpha((int) (255f * f * f));
                        canvas.drawText(g[i], idx, 1, x, yy, body);
                    }
                }
            }
            postInvalidateDelayed(33);
        }
    }

    // ------------------------------------------------------------ частицы

    static final class DustView extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Random r = new Random();
        private final float dens;
        private final int color;
        private int n;
        private float[] x, y, vx, vy, rad, ph;
        private long last = 0;

        DustView(Context c) {
            super(c);
            dens = c.getResources().getDisplayMetrics().density;
            color = Cfg.ssColor | 0xFF000000;
            p.setStrokeCap(Paint.Cap.ROUND);
        }

        @Override
        protected void onSizeChanged(int w, int h, int ow, int oh) {
            super.onSizeChanged(w, h, ow, oh);
            n = Math.max(5, Cfg.ssCount);
            x = new float[n];
            y = new float[n];
            vx = new float[n];
            vy = new float[n];
            rad = new float[n];
            ph = new float[n];
            float sp = (10f + 60f * Cfg.ssSpeed / 100f) * dens;
            for (int i = 0; i < n; i++) {
                x[i] = r.nextFloat() * w;
                y[i] = r.nextFloat() * h;
                vx[i] = (r.nextFloat() - 0.5f) * sp;
                vy[i] = (r.nextFloat() - 0.5f) * sp;
                rad[i] = dens * (0.8f + r.nextFloat() * 1.6f) * Cfg.ssSize / 16f;
                ph[i] = r.nextFloat() * 6.2831853f;
            }
        }

        @Override
        protected void onDraw(Canvas canvas) {
            if (x == null) return;
            long now = SystemClock.uptimeMillis();
            float dt = last == 0 ? 0.033f : Math.min((now - last) / 1000f, 0.1f);
            last = now;
            canvas.drawColor(0xFF000000);
            int w = getWidth(), h = getHeight();

            for (int i = 0; i < n; i++) {
                x[i] += vx[i] * dt;
                y[i] += vy[i] * dt;
                if (x[i] < 0) x[i] += w;
                else if (x[i] > w) x[i] -= w;
                if (y[i] < 0) y[i] += h;
                else if (y[i] > h) y[i] -= h;
                ph[i] += dt * 1.6f;
            }

            if (Cfg.ssLinks) {
                float link = Math.min(w, h) * 0.18f;
                float l2 = link * link;
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(Math.max(1f, dens * 0.6f));
                p.setColor(color);
                int m = Math.min(n, 140);
                for (int i = 0; i < m; i++) {
                    for (int j = i + 1; j < m; j++) {
                        float dx = x[i] - x[j], dy = y[i] - y[j];
                        float d2 = dx * dx + dy * dy;
                        if (d2 < l2) {
                            float f = 1f - (float) Math.sqrt(d2) / link;
                            p.setAlpha((int) (110f * f));
                            canvas.drawLine(x[i], y[i], x[j], y[j], p);
                        }
                    }
                }
            }

            p.setStyle(Paint.Style.FILL);
            for (int i = 0; i < n; i++) {
                float tw = 0.35f + 0.65f * (0.5f + 0.5f * (float) Math.sin(ph[i]));
                p.setColor(color);
                p.setAlpha((int) (255f * tw));
                canvas.drawCircle(x[i], y[i], rad[i], p);
            }
            postInvalidateDelayed(33);
        }
    }
}
