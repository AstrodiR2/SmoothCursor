package com.smoothcursor;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Слой 2 из 4. Хранит картинки одного терминала и рисует их на Canvas.
 * Зависит только от Android (Canvas/Bitmap), про Xposed и Termux ничего не знает.
 *
 * Как картинка привязана к тексту:
 *   У каждой картинки есть "якорь": номер строки в общем счёте строк с начала сессии
 *   (anchor = сколько строк уже уехало в историю + номер строки курсора на экране).
 *   Когда экран прокручивается, счётчик растёт, и внешняя строка картинки
 *   (externalRow = anchor - scrolled) уменьшается вместе с текстом. externalRow = 0 это
 *   верхняя строка экрана, отрицательные значения это история (так же считает Termux).
 *
 * Слои: z >= 0 рисуется ПОВЕРХ текста, z < 0 рисуется ПОД текстом (как в kitty).
 *
 * Порт в форк Termux: класс переносится как есть. Нужно только вызывать draw() из
 * TerminalRenderer.render(): behind=true после заливки фона и до рисования текста,
 * behind=false после текста и до курсора.
 */
final class ImageStore {

    /** Картинка. Размеры известны сразу, пиксели (bitmap) могут появиться чуть позже: декодер работает в фоне. */
    static final class Img {
        final int width;
        final int height;
        volatile Bitmap bitmap;

        Img(int width, int height) {
            this.width = width;
            this.height = height;
        }
    }

    /** Одно размещение картинки на экране. */
    static final class Placement {
        final Img img;
        final Object buffer;   // экранный буфер (основной или альтернативный), где лежит картинка
        final long anchor;     // абсолютный номер строки (см. описание класса)
        final int col;         // левая колонка
        final int cols, rows;  // сколько ячеек занимает (для стирания и прокрутки)
        final float wCells, hCells; // точный размер в ячейках (для рисования)
        final int z;
        final int id;          // kitty image id, 0 если нет
        long seq;

        Placement(Img img, Object buffer, long anchor, int col, int cols, int rows,
                  float wCells, float hCells, int z, int id) {
            this.img = img;
            this.buffer = buffer;
            this.anchor = anchor;
            this.col = col;
            this.cols = cols;
            this.rows = rows;
            this.wCells = wCells;
            this.hCells = hCells;
            this.z = z;
            this.id = id;
        }
    }

    private static final int MAX_PLACEMENTS = 96;
    private static final long MAX_BYTES = 128L * 1024 * 1024;
    private static final int MAX_KITTY_IMAGES = 32;

    private final ArrayList<Placement> placements = new ArrayList<>();
    private long seqCounter = 0;
    private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final RectF dst = new RectF();

    /** Картинки kitty, переданные с i=<id> (a=t), чтобы потом показывать через a=p. Самые старые вытесняются. */
    final Map<Integer, Img> byId = new LinkedHashMap<Integer, Img>() {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Integer, Img> eldest) {
            return size() > MAX_KITTY_IMAGES;
        }
    };

    boolean isEmpty() {
        return placements.isEmpty();
    }

    void add(Placement p) {
        p.seq = ++seqCounter;
        int pos = placements.size();
        while (pos > 0 && placements.get(pos - 1).z > p.z) pos--; // сортировка по z, при равных новее выше
        placements.add(pos, p);
        while (placements.size() > 1 && (placements.size() > MAX_PLACEMENTS || totalBytes() > MAX_BYTES)) {
            evictOldest();
        }
    }

    private void evictOldest() {
        int idx = 0;
        for (int i = 1; i < placements.size(); i++) {
            if (placements.get(i).seq < placements.get(idx).seq) idx = i;
        }
        placements.remove(idx);
    }

    private long totalBytes() {
        IdentityHashMap<Bitmap, Boolean> seen = new IdentityHashMap<>();
        long sum = 0;
        for (Placement p : placements) {
            Bitmap b = p.img.bitmap;
            if (b != null && seen.put(b, Boolean.TRUE) == null) sum += b.getAllocationByteCount();
        }
        return sum;
    }

    void removeAll(Object buffer) {
        for (int i = placements.size() - 1; i >= 0; i--) {
            if (placements.get(i).buffer == buffer) placements.remove(i);
        }
    }

    void clearEverything() {
        placements.clear();
        byId.clear();
    }

    void removeById(int id) {
        if (id <= 0) return;
        for (int i = placements.size() - 1; i >= 0; i--) {
            if (placements.get(i).id == id) placements.remove(i);
        }
    }

    /** Программа стёрла прямоугольник ячеек (clear, ESC[2J, ESC[K...): убираем картинки, которых он касается. */
    void eraseRect(Object buffer, long scrolled, int sx, int sy, int w, int h) {
        for (int i = placements.size() - 1; i >= 0; i--) {
            Placement p = placements.get(i);
            if (p.buffer != buffer) continue;
            long ext = p.anchor - scrolled;
            if (ext < sy + h && ext + p.rows > sy && p.col < sx + w && p.col + p.cols > sx) {
                placements.remove(i);
            }
        }
    }

    /** ESC[3J: история очищена, картинки целиком в истории больше не нужны. */
    void eraseTranscript(Object buffer, long scrolled) {
        for (int i = placements.size() - 1; i >= 0; i--) {
            Placement p = placements.get(i);
            if (p.buffer == buffer && p.anchor - scrolled + p.rows <= 0) placements.remove(i);
        }
    }

    /** Картинки, уехавшие дальше, чем хранится история, удаляем (освобождаем память). */
    void prune(Object buffer, long scrolled, int transcriptRows) {
        for (int i = placements.size() - 1; i >= 0; i--) {
            Placement p = placements.get(i);
            if (p.buffer == buffer && p.anchor - scrolled + p.rows <= -transcriptRows) placements.remove(i);
        }
    }

    /**
     * Нарисовать картинки одного слоя.
     *
     * @param topRow     первая отображаемая строка (отрицательная при прокрутке в историю)
     * @param screenRows сколько строк на экране
     * @param cellW      ширина ячейки, px
     * @param cellH      высота строки, px
     * @param originY    y верхнего края строки 0 (у Termux это mFontLineSpacingAndAscent)
     * @param behind     true: слой под текстом (z &lt; 0), false: поверх текста (z &gt;= 0)
     */
    void draw(Canvas canvas, Object buffer, long scrolled, int topRow, int screenRows,
              float cellW, float cellH, float originY, boolean behind) {
        for (int i = 0; i < placements.size(); i++) {
            Placement p = placements.get(i);
            if (p.buffer != buffer || (p.z < 0) != behind) continue;
            Bitmap bm = p.img.bitmap;
            if (bm == null) continue; // ещё декодируется
            long ext = p.anchor - scrolled;
            if (ext + p.rows <= topRow || ext >= topRow + screenRows) continue;
            float left = p.col * cellW;
            float top = originY + (ext - topRow) * cellH;
            dst.set(left, top, left + p.wCells * cellW, top + p.hCells * cellH);
            canvas.drawBitmap(bm, null, dst, paint);
        }
    }
}
