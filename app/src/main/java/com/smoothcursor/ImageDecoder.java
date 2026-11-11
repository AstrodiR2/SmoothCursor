package com.smoothcursor;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import java.io.ByteArrayOutputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.Inflater;

/**
 * Превращает байты в ImageStore.Img. Только Android, без Xposed и Termux.
 *
 * Сжатые форматы (PNG, JPEG, WebP, GIF, BMP) декодируются в фоновом потоке, чтобы не вешать
 * терминал: размеры читаются сразу (inJustDecodeBounds), а пиксели появляются позже.
 * Сырые RGB/RGBA (kitty f=24/f=32) переводятся в Bitmap сразу, это быстро.
 */
final class ImageDecoder {

    /** Больше этой стороны картинку при декодировании уменьшаем (экономим память). */
    static final int MAX_SIDE = 2048;
    private static final long MAX_RAW_PIXELS = 16L * 1024 * 1024;
    private static final int MAX_INFLATED = 128 * 1024 * 1024;

    private static final ExecutorService POOL = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "sc-img-decode");
        t.setDaemon(true);
        return t;
    });

    private ImageDecoder() {
    }

    /** @return null, если это не картинка. onReady вызывается из фонового потока, когда пиксели готовы. */
    static ImageStore.Img fromEncoded(final byte[] data, final Runnable onReady) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(data, 0, data.length, bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;

        final ImageStore.Img img = new ImageStore.Img(bounds.outWidth, bounds.outHeight);
        int sample = 1;
        while (bounds.outWidth / sample > MAX_SIDE || bounds.outHeight / sample > MAX_SIDE) sample *= 2;
        final int sampleSize = sample;
        POOL.execute(() -> {
            try {
                BitmapFactory.Options o = new BitmapFactory.Options();
                o.inSampleSize = sampleSize;
                Bitmap bm = BitmapFactory.decodeByteArray(data, 0, data.length, o);
                if (bm != null) {
                    img.bitmap = bm;
                    if (onReady != null) onReady.run();
                }
            } catch (Throwable ignored) {
            }
        });
        return img;
    }

    /** Сырые пиксели: channels = 3 (RGB) или 4 (RGBA, не премультиплицированный). */
    static ImageStore.Img fromRaw(byte[] px, int w, int h, int channels) {
        if (w <= 0 || h <= 0 || (long) w * h > MAX_RAW_PIXELS) return null;
        if (px.length < (long) w * h * channels) return null;
        int[] argb = new int[w * h];
        int p = 0;
        for (int i = 0; i < argb.length; i++) {
            int r = px[p++] & 255;
            int g = px[p++] & 255;
            int b = px[p++] & 255;
            int a = channels == 4 ? px[p++] & 255 : 255;
            argb[i] = (a << 24) | (r << 16) | (g << 8) | b;
        }
        Bitmap bm = Bitmap.createBitmap(argb, w, h, Bitmap.Config.ARGB_8888);
        ImageStore.Img img = new ImageStore.Img(w, h);
        img.bitmap = bm;
        return img;
    }

    /** zlib (kitty o=z). null при ошибке или слишком большом результате. */
    static byte[] inflate(byte[] data) {
        Inflater inf = new Inflater();
        try {
            inf.setInput(data);
            ByteArrayOutputStream out = new ByteArrayOutputStream(data.length * 4);
            byte[] buf = new byte[65536];
            while (!inf.finished()) {
                int n = inf.inflate(buf);
                if (n == 0 && (inf.needsInput() || inf.needsDictionary())) break;
                out.write(buf, 0, n);
                if (out.size() > MAX_INFLATED) return null;
            }
            return out.toByteArray();
        } catch (Throwable t) {
            return null;
        } finally {
            inf.end();
        }
    }
}
