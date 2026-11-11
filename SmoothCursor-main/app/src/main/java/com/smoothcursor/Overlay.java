package com.smoothcursor;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.SurfaceTexture;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.FrameLayout;
import android.widget.ImageView;

import java.lang.ref.WeakReference;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * Работает внутри процесса Termux: свой фон под терминалом и видео-заставка при запуске.
 *
 * Фон: за TerminalView вставляется слой (картинка / видео / градиент), сам терминал
 * рисуется в аппаратном слое с прозрачным фоном по умолчанию, поэтому слой просвечивает
 * под текстом. Цветные фоны ячеек (vim, mc и т.д.) остаются как были.
 */
final class Overlay {

    private static final Uri CFG_URI = Uri.parse("content://com.smoothcursor.settings");
    private static final Uri URI_IMG = Uri.parse("content://com.smoothcursor.settings/img");
    private static final Uri URI_VID = Uri.parse("content://com.smoothcursor.settings/vid");
    private static final Uri URI_SPL = Uri.parse("content://com.smoothcursor.settings/splash");

    private static final String X_ARR = "sc_bg_arr";
    private static final String X_OLD = "sc_bg_old";
    private static final String X_ORIG_M = "sc_orig_margins";

    private static volatile boolean bgActive = false;
    private static int idxBg = 257; // TextStyle.COLOR_INDEX_BACKGROUND
    private static String lastMediaKey = null;
    private static int lastDim = -1;
    private static WeakReference<BgLayer> layerRef = new WeakReference<>(null);
    private static boolean splashShown = false;

    private Overlay() {
    }

    static void init(ClassLoader cl) {
        try {
            Class<?> ts = XposedHelpers.findClassIfExists("com.termux.terminal.TextStyle", cl);
            if (ts != null) idxBg = XposedHelpers.getStaticIntField(ts, "COLOR_INDEX_BACKGROUND");
        } catch (Throwable t) {
            XposedBridge.log("SmoothCursor: TextStyle index fallback: " + t);
        }
    }

    // ---------------------------------------------------------------- фон

    // Перед render(): делаем фон по умолчанию прозрачным (только на время кадра)
    static void bgBefore(XC_MethodHook.MethodHookParam p) {
        if (!bgActive) return;
        Object emu = p.args[0];
        Object rv = XposedHelpers.callMethod(emu, "isReverseVideo");
        if (rv instanceof Boolean && (Boolean) rv) return;
        Object colors = XposedHelpers.getObjectField(emu, "mColors");
        int[] cur = (int[]) XposedHelpers.getObjectField(colors, "mCurrentColors");
        int old = cur[idxBg];
        cur[idxBg] = old & 0x00FFFFFF;
        p.setObjectExtra(X_ARR, cur);
        p.setObjectExtra(X_OLD, Integer.valueOf(old));
    }

    // После render(): возвращаем цвет на место
    static void bgAfter(XC_MethodHook.MethodHookParam p) {
        Object arr = p.getObjectExtra(X_ARR);
        Object old = p.getObjectExtra(X_OLD);
        if (arr instanceof int[] && old instanceof Integer) {
            ((int[]) arr)[idxBg] = (Integer) old;
        }
    }

    // Вызывается из фонового потока, когда пришли свежие настройки
    static void onConfig(final View v, Bundle b) {
        final boolean padOn = b.getBoolean("pad_on", false);
        final int padX = b.getInt("pad_x", 8);
        final int padY = b.getInt("pad_y", 8);
        v.post(() -> {
            try {
                applyPadding(v, padOn, padX, padY);
            } catch (Throwable t) {
                XposedBridge.log("SmoothCursor: padding error: " + t);
            }
        });
        final int mode = b.getInt("bg_mode", 0);
        final int dim = b.getInt("bg_dim", 35);
        final int c1 = b.getInt("bg_c1", 0xFF141E30);
        final int c2 = b.getInt("bg_c2", 0xFF243B55);
        final int ver = b.getInt("bg_ver", 0);
        final String mediaKey = mode + ":" + c1 + ":" + c2 + ":" + ver;
        final boolean mediaChanged = !mediaKey.equals(lastMediaKey);
        final boolean dimChanged = dim != lastDim;
        lastMediaKey = mediaKey;
        lastDim = dim;
        v.post(() -> {
            try {
                BgLayer l = layerRef.get();
                boolean attached = l != null && l.getParent() == v.getParent();
                if (mode == 0) {
                    if (bgActive || mediaChanged) apply(v, 0, dim, c1, c2, true);
                    return;
                }
                if (!attached || mediaChanged || dimChanged) {
                    apply(v, mode, dim, c1, c2, mediaChanged || !attached);
                }
            } catch (Throwable t) {
                XposedBridge.log("SmoothCursor: bg apply error: " + t);
            }
        });
    }

    // Отступ делаем через margin у TerminalView: view становится меньше, Termux сам пересчитывает
    // число колонок и строк, а фон (картинка/видео/цвет окна) остаётся на всю площадь.
    private static void applyPadding(View term, boolean on, int xDp, int yDp) {
        ViewGroup.LayoutParams lp0 = term.getLayoutParams();
        if (!(lp0 instanceof ViewGroup.MarginLayoutParams)) return;
        ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams) lp0;
        int[] orig = (int[]) XposedHelpers.getAdditionalInstanceField(term, X_ORIG_M);
        if (orig == null) {
            orig = new int[]{lp.leftMargin, lp.topMargin, lp.rightMargin, lp.bottomMargin};
            XposedHelpers.setAdditionalInstanceField(term, X_ORIG_M, orig);
        }
        float d = term.getResources().getDisplayMetrics().density;
        int px = on ? Math.round(xDp * d) : 0;
        int py = on ? Math.round(yDp * d) : 0;
        int l = orig[0] + px, t = orig[1] + py, r = orig[2] + px, bt = orig[3] + py;
        if (lp.leftMargin == l && lp.topMargin == t && lp.rightMargin == r && lp.bottomMargin == bt) return;
        lp.setMargins(l, t, r, bt);
        term.setLayoutParams(lp);
    }

    private static void apply(View term, int mode, int dim, int c1, int c2, boolean reload) {
        ViewParent pp = term.getParent();
        BgLayer l = layerRef.get();

        if (mode == 0) {
            bgActive = false;
            if (l != null) {
                l.clear();
                if (l.getParent() instanceof ViewGroup) ((ViewGroup) l.getParent()).removeView(l);
                layerRef = new WeakReference<>(null);
            }
            term.setLayerType(View.LAYER_TYPE_NONE, null);
            term.invalidate();
            return;
        }

        if (!(pp instanceof ViewGroup)) return;
        ViewGroup parent = (ViewGroup) pp;

        if (l == null || l.getParent() != parent) {
            if (l != null) {
                l.clear();
                if (l.getParent() instanceof ViewGroup) ((ViewGroup) l.getParent()).removeView(l);
            }
            l = new BgLayer(term.getContext());
            parent.addView(l, 0, new ViewGroup.LayoutParams(-1, -1));
            layerRef = new WeakReference<>(l);
            reload = true;
        }

        term.setBackground(null);
        if (term.getLayerType() != View.LAYER_TYPE_HARDWARE) {
            term.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        }
        l.setDim(dim);
        if (reload) l.configure(mode, c1, c2);
        bgActive = true;
        term.invalidate();
    }

    // Матрица для TextureView: cover = заполнить с обрезкой, иначе вписать с чёрными полями
    static Matrix fitMatrix(int w, int h, int vw, int vh, boolean cover) {
        Matrix m = new Matrix();
        if (w <= 0 || h <= 0 || vw <= 0 || vh <= 0) return m;
        float wa = (float) w / h;
        float va = (float) vw / vh;
        float sx = 1f, sy = 1f;
        if (cover) {
            if (wa > va) sy = wa / va; else sx = va / wa;
        } else {
            if (wa > va) sx = va / wa; else sy = wa / va;
        }
        m.setScale(sx, sy, w / 2f, h / 2f);
        return m;
    }

    static final class BgLayer extends FrameLayout implements TextureView.SurfaceTextureListener {
        private final ImageView img;
        private final View dimView;
        private TextureView tv;
        private MediaPlayer mp;
        private Surface surface;
        private int vw, vh;
        private boolean prepared, paused;
        private int token = 0;

        BgLayer(Context c) {
            super(c);
            img = new ImageView(c);
            img.setScaleType(ImageView.ScaleType.CENTER_CROP);
            img.setVisibility(View.GONE);
            addView(img, new LayoutParams(-1, -1));
            dimView = new View(c);
            addView(dimView, new LayoutParams(-1, -1));
        }

        void setDim(int dim) {
            int d = Math.max(0, Math.min(100, dim));
            dimView.setBackgroundColor(Color.argb(d * 255 / 100, 0, 0, 0));
        }

        void configure(int mode, int c1, int c2) {
            clear();
            if (mode == 1) {
                loadImage(token);
            } else if (mode == 2) {
                tv = new TextureView(getContext());
                tv.setSurfaceTextureListener(this);
                addView(tv, 0, new LayoutParams(-1, -1));
            } else if (mode == 3) {
                setBackground(new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                        new int[]{c1 | 0xFF000000, c2 | 0xFF000000}));
            }
        }

        void clear() {
            token++;
            releaseVideo();
            img.setImageDrawable(null);
            img.setVisibility(View.GONE);
            setBackground(null);
        }

        private void loadImage(final int myToken) {
            final Context ctx = getContext();
            new Thread(() -> {
                Bitmap bm = null;
                try (ParcelFileDescriptor pfd = ctx.getContentResolver().openFileDescriptor(URI_IMG, "r")) {
                    if (pfd != null) bm = BitmapFactory.decodeFileDescriptor(pfd.getFileDescriptor());
                } catch (Throwable t) {
                    XposedBridge.log("SmoothCursor: bg image error: " + t);
                }
                final Bitmap fb = bm;
                post(() -> {
                    if (fb == null || myToken != token) return;
                    img.setImageBitmap(fb);
                    img.setAlpha(0f);
                    img.setVisibility(View.VISIBLE);
                    img.animate().alpha(1f).setDuration(300).start();
                });
            }).start();
        }

        private void openPlayer(SurfaceTexture st) {
            stopPlayer();
            ParcelFileDescriptor pfd = null;
            try {
                pfd = getContext().getContentResolver().openFileDescriptor(URI_VID, "r");
                if (pfd == null) return;
                MediaPlayer m = new MediaPlayer();
                mp = m;
                m.setDataSource(pfd.getFileDescriptor());
                surface = new Surface(st);
                m.setSurface(surface);
                m.setLooping(true);
                m.setVolume(0f, 0f);
                m.setOnVideoSizeChangedListener((x, w, h) -> {
                    vw = w;
                    vh = h;
                    fit();
                });
                m.setOnPreparedListener(x -> {
                    prepared = true;
                    if (!paused) x.start();
                });
                m.setOnErrorListener((x, what, extra) -> {
                    XposedBridge.log("SmoothCursor: bg video error " + what + "/" + extra);
                    return true;
                });
                m.prepareAsync();
            } catch (Throwable t) {
                XposedBridge.log("SmoothCursor: bg video open error: " + t);
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

        private void fit() {
            TextureView t = tv;
            if (t == null) return;
            t.setTransform(fitMatrix(t.getWidth(), t.getHeight(), vw, vh, true));
        }

        private void stopPlayer() {
            prepared = false;
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
            stopPlayer();
            if (tv != null) {
                tv.setSurfaceTextureListener(null);
                removeView(tv);
                tv = null;
            }
            vw = 0;
            vh = 0;
        }

        @Override
        public void onSurfaceTextureAvailable(SurfaceTexture st, int w, int h) {
            openPlayer(st);
        }

        @Override
        public void onSurfaceTextureSizeChanged(SurfaceTexture st, int w, int h) {
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

        // Termux свёрнут: ставим видео на паузу, чтобы не жрать батарею
        @Override
        protected void onWindowVisibilityChanged(int visibility) {
            super.onWindowVisibilityChanged(visibility);
            paused = visibility != View.VISIBLE;
            MediaPlayer m = mp;
            if (m == null) return;
            try {
                if (paused) {
                    if (m.isPlaying()) m.pause();
                } else if (prepared) {
                    m.start();
                }
            } catch (Throwable ignored) {
            }
        }

        @Override
        protected void onDetachedFromWindow() {
            super.onDetachedFromWindow();
            releaseVideo();
        }
    }

    // ------------------------------------------------------------ заставка

    // Один раз за запуск процесса Termux. Новые сессии и пересоздание activity не повторяют.
    static void maybeSplash(final Activity act) {
        if (splashShown) return;
        splashShown = true;
        Bundle b = act.getContentResolver().call(CFG_URI, "get", null, null);
        if (b == null || !b.getBoolean("splash_on", false)) return;
        ParcelFileDescriptor pfd;
        try {
            pfd = act.getContentResolver().openFileDescriptor(URI_SPL, "r");
        } catch (Throwable t) {
            XposedBridge.log("SmoothCursor: splash file missing: " + t);
            return;
        }
        if (pfd == null) return;
        new Splash(act, pfd, b.getBoolean("splash_sound", false)).start();
    }

    static final class Splash extends FrameLayout implements TextureView.SurfaceTextureListener {
        private final Activity act;
        private final ParcelFileDescriptor pfd;
        private final boolean sound;
        private final Handler h = new Handler(Looper.getMainLooper());
        private final Runnable watchdog = this::finish;
        private TextureView tv;
        private MediaPlayer mp;
        private AudioManager am;
        private boolean done, started, fading;
        private int vw, vh;

        Splash(Activity a, ParcelFileDescriptor p, boolean snd) {
            super(a);
            act = a;
            pfd = p;
            sound = snd;
            setBackgroundColor(0xFF000000);
            setClickable(true); // глотаем касания, терминал под заставкой не трогается
            setOnClickListener(v -> fade());
            tv = new TextureView(a);
            tv.setSurfaceTextureListener(this);
            addView(tv, new LayoutParams(-1, -1));
        }

        void start() {
            ViewGroup content = (ViewGroup) act.findViewById(android.R.id.content);
            if (content == null) {
                finish();
                return;
            }
            content.addView(this, new FrameLayout.LayoutParams(-1, -1));
            h.postDelayed(watchdog, 8000); // видео не завелось, не держим чёрный экран
        }

        private void begin(SurfaceTexture st) {
            try {
                MediaPlayer m = new MediaPlayer();
                mp = m;
                m.setDataSource(pfd.getFileDescriptor());
                try {
                    pfd.close();
                } catch (Throwable ignored) {
                }
                m.setSurface(new Surface(st));
                m.setLooping(false);
                if (sound) {
                    m.setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                            .build());
                    m.setVolume(1f, 1f);
                    am = (AudioManager) act.getSystemService(Context.AUDIO_SERVICE);
                    if (am != null) {
                        am.requestAudioFocus(null, AudioManager.STREAM_MUSIC,
                                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT);
                    }
                } else {
                    m.setVolume(0f, 0f);
                }
                m.setOnVideoSizeChangedListener((x, w, hh) -> {
                    vw = w;
                    vh = hh;
                    fit();
                });
                m.setOnPreparedListener(x -> {
                    if (done) return;
                    started = true;
                    h.removeCallbacks(watchdog);
                    x.start();
                });
                m.setOnCompletionListener(x -> fade());
                m.setOnErrorListener((x, what, extra) -> {
                    XposedBridge.log("SmoothCursor: splash error " + what + "/" + extra);
                    finish();
                    return true;
                });
                m.prepareAsync();
            } catch (Throwable t) {
                XposedBridge.log("SmoothCursor: splash start error: " + t);
                finish();
            }
        }

        private void fit() {
            TextureView t = tv;
            if (t == null) return;
            // Заставка вписывается целиком, без обрезки
            t.setTransform(fitMatrix(t.getWidth(), t.getHeight(), vw, vh, false));
        }

        // Плавное затухание картинки и звука, потом заставка убирается
        private void fade() {
            if (done || fading) return;
            fading = true;
            if (sound) {
                ValueAnimator va = ValueAnimator.ofFloat(1f, 0f);
                va.setDuration(700);
                va.addUpdateListener(a -> {
                    float f = (Float) a.getAnimatedValue();
                    MediaPlayer m = mp;
                    if (m != null) {
                        try {
                            m.setVolume(f, f);
                        } catch (Throwable ignored) {
                        }
                    }
                });
                va.start();
            }
            animate().alpha(0f).setDuration(700).withEndAction(this::finish).start();
        }

        void finish() {
            if (done) return;
            done = true;
            h.removeCallbacks(watchdog);
            MediaPlayer m = mp;
            mp = null;
            if (m != null) {
                try {
                    m.release();
                } catch (Throwable ignored) {
                }
            }
            try {
                pfd.close();
            } catch (Throwable ignored) {
            }
            if (am != null) {
                try {
                    am.abandonAudioFocus(null);
                } catch (Throwable ignored) {
                }
            }
            ViewParent p = getParent();
            if (p instanceof ViewGroup) ((ViewGroup) p).removeView(this);
        }

        @Override
        public void onSurfaceTextureAvailable(SurfaceTexture st, int w, int hh) {
            begin(st);
        }

        @Override
        public void onSurfaceTextureSizeChanged(SurfaceTexture st, int w, int hh) {
            fit();
        }

        @Override
        public boolean onSurfaceTextureDestroyed(SurfaceTexture st) {
            return true;
        }

        @Override
        public void onSurfaceTextureUpdated(SurfaceTexture st) {
        }

        // Ушли из Termux посреди заставки: не доигрываем в фоне
        @Override
        protected void onWindowVisibilityChanged(int visibility) {
            super.onWindowVisibilityChanged(visibility);
            if (started && visibility != View.VISIBLE) finish();
        }
    }
}
