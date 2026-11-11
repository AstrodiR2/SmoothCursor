package com.smoothcursor;

/**
 * Слой 1 из 4. Чистая Java, никаких зависимостей от Android, Xposed и Termux.
 *
 * Задача: смотреть на сырой поток байт, который программа шлёт терминалу, вырезать из него
 * последовательности с картинками и отдавать всё остальное эмулятору без изменений.
 *
 * Почему на уровне байт, а не через парсер эмулятора: TerminalEmulator Termux обрезает OSC до
 * 8192 символов (MAX_OSC_STRING_LENGTH), а картинка в base64 весит сотни килобайт. APC-последовательности
 * (kitty) эмулятор вообще не понимает. Поэтому перехватываем поток ДО эмулятора.
 *
 * Какие последовательности ловим:
 *   iTerm2:  ESC ] 1337 ; File = <аргументы> : <base64>  (BEL | ESC \)
 *   kitty:   ESC _ G <ключи> ; <base64>                  ESC \
 *
 * Порт в форк Termux: класс можно вставить как есть и вызвать из TerminalEmulator.append()
 * (в начале метода), передав ему buffer и length.
 */
final class ImageStreamFilter {

    static final int KIND_ITERM = 1;
    static final int KIND_KITTY = 2;

    interface Sink {
        /** Обычные байты терминала: передать эмулятору как есть, строго по порядку. */
        void passThrough(byte[] buf, int off, int len);

        /** Целая последовательность с картинкой (без вводной части и терминатора). Буфер потом переиспользуется. */
        void sequence(int kind, byte[] payload, int len);
    }

    private static final int ESC = 0x1B;
    private static final int BEL = 0x07;
    private static final int MAX_PAYLOAD = 24 * 1024 * 1024;

    // ESC ] 1337 ; File =
    private static final byte[] INTRO_ITERM = {ESC, ']', '1', '3', '3', '7', ';', 'F', 'i', 'l', 'e', '='};
    // ESC _ G
    private static final byte[] INTRO_KITTY = {ESC, '_', 'G'};

    private boolean capturing;
    private int kind;
    private boolean escSeen;
    private boolean overflow;
    private byte[] payload = new byte[8192];
    private int payloadLen;

    // Хвост чанка, который может оказаться началом нашей последовательности (например одиночный ESC)
    private final byte[] held = new byte[16];
    private int heldLen;

    private int matchedKind;

    /** Есть ли байты, удержанные до следующего чанка. Если да, их надо сбросить по таймеру (см. takeHeld). */
    boolean hasHeld() {
        return heldLen > 0;
    }

    /** Забрать удержанные байты (чтобы одиночный ESC не завис навсегда). */
    byte[] takeHeld() {
        byte[] r = new byte[heldLen];
        System.arraycopy(held, 0, r, 0, heldLen);
        heldLen = 0;
        return r;
    }

    /** Бросить всё накопленное (сброс терминала, закрытие сессии). */
    void reset() {
        capturing = false;
        escSeen = false;
        overflow = false;
        payloadLen = 0;
        heldLen = 0;
    }

    /**
     * @return false: в чанке нет ничего нашего, вызывающий просто пускает оригинальный append().
     *         true: чанк обработан здесь (всё нужное уже ушло в sink), оригинальный append() вызывать НЕ надо.
     */
    boolean process(byte[] in, int inLen, Sink sink) {
        if (!capturing && heldLen == 0 && !mayContainIntro(in, inLen)) return false;

        byte[] d = in;
        int n = inLen;
        if (heldLen > 0) {
            d = new byte[heldLen + inLen];
            System.arraycopy(held, 0, d, 0, heldLen);
            System.arraycopy(in, 0, d, heldLen, inLen);
            n = d.length;
            heldLen = 0;
        }

        int i = 0;
        int passStart = capturing ? -1 : 0; // начало ещё не отданного обычного текста
        while (i < n) {
            if (capturing) {
                byte c = d[i++];
                if (escSeen) {
                    escSeen = false;
                    if (c == '\\') { // ESC \ = конец последовательности
                        finish(sink);
                        passStart = i;
                        continue;
                    }
                    // ESC и что-то ещё внутри картинки: это уже не наша последовательность, обрываем.
                    // ESC мог остаться в прошлом чанке, поэтому отдаём его отдельно.
                    abort();
                    sink.passThrough(new byte[]{ESC}, 0, 1);
                    passStart = i - 1;
                    continue;
                }
                if (c == ESC) {
                    escSeen = true;
                    continue;
                }
                if (c == BEL && kind == KIND_ITERM) {
                    finish(sink);
                    passStart = i;
                    continue;
                }
                if (c == '\n' || c == '\r') continue; // base64 иногда переносят по строкам
                if (c >= 0 && c < 0x20) { // другой управляющий символ: мусор, обрываем
                    abort();
                    passStart = i - 1;
                    continue;
                }
                if (!overflow) {
                    if (payloadLen >= MAX_PAYLOAD) {
                        overflow = true; // слишком большая: дочитаем до конца, но не сохраняем
                    } else {
                        if (payloadLen == payload.length) {
                            byte[] bigger = new byte[Math.min(MAX_PAYLOAD, payload.length * 2)];
                            System.arraycopy(payload, 0, bigger, 0, payloadLen);
                            payload = bigger;
                        }
                        payload[payloadLen++] = c;
                    }
                }
                continue;
            }

            // Обычный режим: ищем ESC, за которым начинается наша вводная часть
            int j = indexOfEsc(d, i, n);
            if (j < 0) {
                i = n;
                break;
            }
            int m = matchIntro(d, j, n);
            if (m > 0) {
                if (j > passStart) sink.passThrough(d, passStart, j - passStart);
                capturing = true;
                kind = matchedKind;
                payloadLen = 0;
                escSeen = false;
                overflow = false;
                i = j + m;
                passStart = -1;
                continue;
            }
            if (m < 0) {
                // чанк оборвался посреди вводной части: придерживаем хвост до следующего чанка
                if (j > passStart) sink.passThrough(d, passStart, j - passStart);
                heldLen = n - j;
                System.arraycopy(d, j, held, 0, heldLen);
                return true;
            }
            i = j + 1;
        }
        if (!capturing && passStart >= 0 && passStart < n) {
            sink.passThrough(d, passStart, n - passStart);
        }
        return true;
    }

    private void finish(Sink sink) {
        capturing = false;
        if (!overflow) sink.sequence(kind, payload, payloadLen);
        payloadLen = 0;
        overflow = false;
    }

    private void abort() {
        capturing = false;
        payloadLen = 0;
        overflow = false;
    }

    private boolean mayContainIntro(byte[] d, int n) {
        int i = 0;
        while (true) {
            int j = indexOfEsc(d, i, n);
            if (j < 0) return false;
            if (matchIntro(d, j, n) != 0) return true;
            i = j + 1;
        }
    }

    private static int indexOfEsc(byte[] d, int from, int n) {
        for (int i = from; i < n; i++) if (d[i] == ESC) return i;
        return -1;
    }

    /** @return длина вводной части; 0 = не наша; -1 = совпало всё, что есть до конца чанка, нужно больше байт */
    private int matchIntro(byte[] d, int j, int n) {
        boolean partial = false;
        for (int k = 0; k < 2; k++) {
            byte[] intro = k == 0 ? INTRO_ITERM : INTRO_KITTY;
            int avail = Math.min(intro.length, n - j);
            boolean ok = true;
            for (int t = 0; t < avail; t++) {
                if (d[j + t] != intro[t]) {
                    ok = false;
                    break;
                }
            }
            if (!ok) continue;
            if (avail == intro.length) {
                matchedKind = k == 0 ? KIND_ITERM : KIND_KITTY;
                return intro.length;
            }
            partial = true;
        }
        return partial ? -1 : 0;
    }
}
