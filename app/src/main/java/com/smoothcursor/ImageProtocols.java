package com.smoothcursor;

import android.util.Base64;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * Слой 3 из 4. Понимает протоколы картинок и решает, что и куда положить.
 * Не знает ни про Xposed, ни про внутренности Termux: всё, что нужно от терминала,
 * приходит через интерфейс Host. В форке Termux Host реализуется прямыми вызовами эмулятора.
 *
 * Поддерживается:
 *   iTerm2 (OSC 1337 File=): inline, width, height (N, Npx, N%, auto), preserveAspectRatio,
 *     doNotMoveCursor, и своё расширение z=<число> (слой). Лишние ключи iTerm2 игнорирует,
 *     так что z=-1 ничего не ломает.
 *   kitty (APC G), подмножество:
 *     a = T (передать и показать), t (передать), p (показать по id), d (удалить: a/A/i/I), q (запрос)
 *     f = 100 (PNG и другие форматы), 24 (RGB), 32 (RGBA), вместе с s=ширина, v=высота
 *     t = d (данные в запросе), f (путь к файлу), t (временный файл)
 *     o = z (zlib), m = 1 (данные идут чанками), i = id, c/r = размер в ячейках,
 *     z = слой, C = 1 (не двигать курсор), q = 1|2 (тихий режим)
 *   Не поддерживается: обрезка x/y/w/h, смещения X/Y, юникод-плейсхолдеры U=1, shared memory (t=s), анимация.
 */
final class ImageProtocols {

    interface Host {
        int cursorCol();

        int cursorRow();

        int columns();

        int rows();

        float cellWidth();

        float cellHeight();

        /** Текущий экранный буфер (основной или альтернативный), любой объект, сравнивается по ==. */
        Object activeBuffer();

        /** Сколько строк уже уехало в историю у этого буфера. Растёт при каждой прокрутке экрана. */
        long scrolledLines(Object buffer);

        /** Слой по умолчанию, если программа не указала z: 0 поверх текста, -1 под текстом. */
        int defaultZ();

        /** Подать байты на вход эмулятора (как будто их прислала программа). Мимо фильтра картинок. */
        void feed(byte[] bytes);

        /** Ответить программе (запись в stdin процесса). */
        void reply(String text);

        void invalidate();
    }

    private final Host host;
    private final ImageStore store;

    // Kitty: данные приходят кусками (m=1), пока не придёт последний
    private Map<String, String> pendingCtl;
    private ByteArrayOutputStream pendingData;
    private static final int MAX_PENDING = 32 * 1024 * 1024;

    ImageProtocols(Host host, ImageStore store) {
        this.host = host;
        this.store = store;
    }

    void onSequence(int kind, byte[] p, int len) {
        if (kind == ImageStreamFilter.KIND_ITERM) onITerm(p, len);
        else if (kind == ImageStreamFilter.KIND_KITTY) onKitty(p, len);
    }

    // ================================================================ iTerm2

    private void onITerm(byte[] p, int len) {
        int colon = indexOf(p, (byte) ':', len);
        if (colon < 0) return;
        Map<String, String> args = parseList(new String(p, 0, colon, StandardCharsets.ISO_8859_1), ';');
        if ("0".equals(args.get("inline"))) return; // это загрузка файла, а не картинка в тексте

        byte[] data = decodeBase64(p, colon + 1, len - colon - 1);
        if (data == null) return;
        ImageStore.Img img = ImageDecoder.fromEncoded(data, host::invalidate);
        if (img == null) return;

        float cw = host.cellWidth(), ch = host.cellHeight();
        float wPx = dimension(args.get("width"), cw, host.columns() * cw);
        float hPx = dimension(args.get("height"), ch, host.rows() * ch);
        float iw = img.width, ih = img.height;
        if (wPx < 0 && hPx < 0) {
            wPx = iw;
            hPx = ih;
        } else if (wPx < 0) {
            wPx = hPx * iw / ih;
        } else if (hPx < 0) {
            hPx = wPx * ih / iw;
        } else if (!"0".equals(args.get("preserveAspectRatio"))) {
            float scale = Math.min(wPx / iw, hPx / ih); // вписываем в рамку без искажений
            wPx = iw * scale;
            hPx = ih * scale;
        }
        int z = parseInt(args.get("z"), host.defaultZ());
        boolean move = !"1".equals(args.get("doNotMoveCursor"));
        place(img, wPx / cw, hPx / ch, z, 0, move);
    }

    /** "5" = 5 ячеек, "100px", "50%" от окна, "auto". Возвращает пиксели или -1 (авто). */
    private static float dimension(String s, float cellPx, float totalPx) {
        if (s == null || s.isEmpty() || s.equals("auto")) return -1f;
        try {
            float v;
            if (s.endsWith("px")) v = Float.parseFloat(s.substring(0, s.length() - 2));
            else if (s.endsWith("%")) v = Float.parseFloat(s.substring(0, s.length() - 1)) / 100f * totalPx;
            else v = Float.parseFloat(s) * cellPx;
            return v > 0 ? v : -1f;
        } catch (NumberFormatException e) {
            return -1f;
        }
    }

    // ================================================================ kitty

    private void onKitty(byte[] p, int len) {
        int semi = indexOf(p, (byte) ';', len);
        int ctlEnd = semi < 0 ? len : semi;
        Map<String, String> ctl = parseList(new String(p, 0, ctlEnd, StandardCharsets.ISO_8859_1), ',');
        int dataOff = semi < 0 ? len : semi + 1;
        boolean more = "1".equals(ctl.get("m"));

        if (pendingCtl != null) { // продолжение предыдущего запроса
            if (pendingData.size() + (len - dataOff) > MAX_PENDING) {
                pendingCtl = null;
                pendingData = null;
                return;
            }
            pendingData.write(p, dataOff, len - dataOff);
            if (more) return;
            Map<String, String> first = pendingCtl;
            byte[] all = pendingData.toByteArray();
            pendingCtl = null;
            pendingData = null;
            runKitty(first, all, all.length);
            return;
        }
        if (more) {
            pendingCtl = ctl;
            pendingData = new ByteArrayOutputStream();
            pendingData.write(p, dataOff, len - dataOff);
            return;
        }
        // Одиночный запрос: base64 лежит в том же буфере, смещённый на dataOff
        byte[] b64 = new byte[len - dataOff];
        System.arraycopy(p, dataOff, b64, 0, b64.length);
        runKitty(ctl, b64, b64.length);
    }

    private void runKitty(Map<String, String> ctl, byte[] b64, int b64Len) {
        String a = ctl.get("a");
        char action = (a == null || a.isEmpty()) ? 't' : a.charAt(0);
        int id = parseInt(ctl.get("i"), 0);
        int quiet = parseInt(ctl.get("q"), 0);

        switch (action) {
            case 'q': // запрос "поддерживаешь?": отвечаем OK
                kittyReply(id, quiet, true, null);
                break;
            case 'd':
                kittyDelete(ctl, id);
                break;
            case 't':
            case 'T': {
                ImageStore.Img img = decodeKitty(ctl, b64, b64Len);
                if (img == null) {
                    kittyReply(id, quiet, false, "EINVAL:cannot decode image");
                    return;
                }
                if (id > 0) store.byId.put(id, img);
                if (action == 'T') placeKitty(img, ctl, id);
                kittyReply(id, quiet, true, null);
                break;
            }
            case 'p': {
                ImageStore.Img img = store.byId.get(id);
                if (img == null) {
                    kittyReply(id, quiet, false, "ENOENT:image not found");
                    return;
                }
                placeKitty(img, ctl, id);
                kittyReply(id, quiet, true, null);
                break;
            }
            default:
                break;
        }
    }

    private ImageStore.Img decodeKitty(Map<String, String> ctl, byte[] b64, int b64Len) {
        byte[] raw = decodeBase64(b64, 0, b64Len);
        if (raw == null) return null;
        String medium = ctl.get("t");
        if (medium != null && !medium.isEmpty() && medium.charAt(0) != 'd') {
            if (medium.charAt(0) != 'f' && medium.charAt(0) != 't') return null; // shared memory не поддерживаем
            raw = readFile(new String(raw, StandardCharsets.UTF_8), medium.charAt(0) == 't',
                    parseInt(ctl.get("O"), 0), parseInt(ctl.get("S"), 0));
            if (raw == null) return null;
        }
        if ("z".equals(ctl.get("o"))) {
            raw = ImageDecoder.inflate(raw);
            if (raw == null) return null;
        }
        int f = parseInt(ctl.get("f"), 32);
        if (f == 100) return ImageDecoder.fromEncoded(raw, host::invalidate);
        if (f == 24 || f == 32) {
            return ImageDecoder.fromRaw(raw, parseInt(ctl.get("s"), 0), parseInt(ctl.get("v"), 0), f == 24 ? 3 : 4);
        }
        return null;
    }

    private void placeKitty(ImageStore.Img img, Map<String, String> ctl, int id) {
        float cw = host.cellWidth(), ch = host.cellHeight();
        int c = parseInt(ctl.get("c"), 0);
        int r = parseInt(ctl.get("r"), 0);
        float w, h;
        if (c > 0 && r > 0) { // обе стороны заданы: растягиваем, как kitty
            w = c;
            h = r;
        } else if (c > 0) {
            w = c;
            h = c * cw * img.height / img.width / ch;
        } else if (r > 0) {
            h = r;
            w = r * ch * img.width / img.height / cw;
        } else { // родной размер
            w = img.width / cw;
            h = img.height / ch;
        }
        int z = ctl.containsKey("z") ? parseInt(ctl.get("z"), 0) : host.defaultZ();
        place(img, w, h, z, id, !"1".equals(ctl.get("C")));
    }

    private void kittyDelete(Map<String, String> ctl, int id) {
        String d = ctl.get("d");
        char c = (d == null || d.isEmpty()) ? 'a' : d.charAt(0);
        switch (c) {
            case 'a':
            case 'A':
                store.removeAll(host.activeBuffer());
                if (c == 'A') store.byId.clear();
                break;
            case 'i':
            case 'I':
                store.removeById(id);
                if (c == 'I') store.byId.remove(id);
                break;
            default:
                break;
        }
        host.invalidate();
    }

    /** kitty отвечает только если указан i; q=1 глушит OK, q=2 глушит ещё и ошибки. */
    private void kittyReply(int id, int quiet, boolean ok, String error) {
        if (id <= 0) return;
        if (ok ? quiet >= 1 : quiet >= 2) return;
        host.reply("\u001b_Gi=" + id + ";" + (ok ? "OK" : error) + "\u001b\\");
    }

    // ================================================================ общее

    /**
     * Положить картинку в позицию курсора и сдвинуть курсор так, как это делают kitty и iTerm2:
     * вниз на (rows - 1) строк и вправо на ширину картинки.
     */
    private void place(ImageStore.Img img, float wCells, float hCells, int z, int id, boolean moveCursor) {
        float maxW = host.columns();
        if (wCells > maxW) { // шире терминала: ужимаем с сохранением пропорций
            hCells *= maxW / wCells;
            wCells = maxW;
        }
        wCells = Math.max(0.1f, wCells);
        hCells = Math.max(0.1f, hCells);
        int cols = (int) Math.max(1, Math.ceil(wCells - 0.001f));
        int rows = (int) Math.max(1, Math.ceil(hCells - 0.001f));

        int col = host.cursorCol();
        int row = host.cursorRow();
        Object buf = host.activeBuffer();
        long anchor = host.scrolledLines(buf) + row;
        store.add(new ImageStore.Placement(img, buf, anchor, col, cols, rows, wCells, hCells, z, id));

        if (moveCursor) {
            StringBuilder sb = new StringBuilder();
            for (int i = 1; i < rows; i++) sb.append('\n'); // LF сам прокрутит экран, если места мало
            int target = col + cols;
            if (target >= host.columns()) sb.append('\r');
            else sb.append("\u001b[").append(target + 1).append('G');
            host.feed(sb.toString().getBytes(StandardCharsets.US_ASCII));
        }
        host.invalidate();
    }

    private static byte[] decodeBase64(byte[] b, int off, int len) {
        try {
            return Base64.decode(b, off, len, Base64.DEFAULT);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static byte[] readFile(String path, boolean deleteAfter, int offset, int size) {
        File f = new File(path);
        try (FileInputStream in = new FileInputStream(f)) {
            long total = f.length();
            if (total > 128L * 1024 * 1024) return null;
            long start = Math.max(0, offset);
            long n = size > 0 ? Math.min(size, total - start) : total - start;
            if (n <= 0) return null;
            byte[] out = new byte[(int) n];
            long skipped = 0;
            while (skipped < start) {
                long s = in.skip(start - skipped);
                if (s <= 0) return null;
                skipped += s;
            }
            int got = 0;
            while (got < out.length) {
                int r = in.read(out, got, out.length - got);
                if (r < 0) return null;
                got += r;
            }
            return out;
        } catch (IOException e) {
            return null;
        } finally {
            // По протоколу kitty временный файл (t=t) удаляет терминал, но только если он создан именно для этого
            if (deleteAfter && f.getName().contains("tty-graphics-protocol")) {
                //noinspection ResultOfMethodCallIgnored
                f.delete();
            }
        }
    }

    /** "k=v;k=v" или "k=v,k=v" в карту. Ключи без значения игнорируются. */
    private static Map<String, String> parseList(String s, char sep) {
        Map<String, String> m = new HashMap<>();
        int i = 0;
        while (i < s.length()) {
            int e = s.indexOf(sep, i);
            if (e < 0) e = s.length();
            String part = s.substring(i, e);
            int eq = part.indexOf('=');
            if (eq > 0) m.put(part.substring(0, eq), part.substring(eq + 1));
            i = e + 1;
        }
        return m;
    }

    private static int parseInt(String s, int def) {
        if (s == null) return def;
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static int indexOf(byte[] b, byte x, int len) {
        for (int i = 0; i < len; i++) if (b[i] == x) return i;
        return -1;
    }
}
