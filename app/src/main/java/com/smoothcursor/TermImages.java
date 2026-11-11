package com.smoothcursor;

import android.graphics.Canvas;
import android.os.Handler;
import android.os.Looper;
import android.view.View;

import java.lang.ref.WeakReference;
import java.lang.reflect.Member;
import java.util.Arrays;
import java.util.WeakHashMap;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * Слой 4 из 4. Единственный файл, который знает про внутренности Termux и Xposed.
 * Всё остальное (ImageStreamFilter, ImageStore, ImageDecoder, ImageProtocols) от них не зависит.
 *
 * Что здесь делается и чем это заменяется при переносе в форк Termux:
 *
 *  1. TerminalEmulator.append(byte[], int): пускаем байты через ImageStreamFilter.
 *     Форк: вызвать filter.process() в начале append().
 *  2. TerminalBuffer.scrollDownOneLine: считаем, сколько строк уехало в историю.
 *     Форк: счётчик long внутри TerminalBuffer, ++ при полноэкранной прокрутке.
 *  3. TerminalBuffer.blockSet / clearTranscript / TerminalEmulator.reset: стираем картинки вместе с текстом.
 *     Форк: вызвать store.eraseRect() / eraseTranscript() / clearEverything() в этих же методах.
 *  4. TerminalRenderer.render: рисуем слои (frameBegin, beforeTextRun, frameEnd вызываются из SmoothCursor.java).
 *     Форк: store.draw(..., behind=true) после заливки фона и до текста, behind=false после текста.
 *
 * Состояние на каждый терминал (сессию Termux) своё: Session. Картинки не смешиваются между вкладками.
 */
final class TermImages {

    private static volatile boolean enabled = true;
    private static volatile boolean broken = false;
    private static volatile int defaultZ = 0;

    // Размеры ячейки с последнего кадра: нужны в момент прихода картинки, чтобы перевести пиксели в ячейки
    private static volatile float lastCellW = 0f;
    private static volatile float lastCellH = 0f;

    // Ключи только слабые: сессия не должна держать эмулятор в памяти (Session хранит эмулятор через WeakReference)
    private static final WeakHashMap<Object, Session> sessions = new WeakHashMap<>();
    private static final WeakHashMap<Object, Session> bufferOwner = new WeakHashMap<>();
    private static final WeakHashMap<Object, long[]> scrolled = new WeakHashMap<>();
    private static WeakReference<View> viewRef = new WeakReference<>(null);

    private TermImages() {
    }

    // ---------------------------------------------------------------- настройки

    static void setConfig(boolean on, boolean behindByDefault) {
        enabled = on;
        defaultZ = behindByDefault ? -1 : 0;
    }

    static void onViewDraw(View v) {
        if (viewRef.get() != v) viewRef = new WeakReference<>(v);
    }

    private static void invalidate() {
        View v = viewRef.get();
        if (v != null) v.postInvalidate();
    }

    // ---------------------------------------------------------------- установка хуков

    static void install(ClassLoader cl, Class<?> emuCls) {
        final Class<?> bufCls = XposedHelpers.findClassIfExists("com.termux.terminal.TerminalBuffer", cl);
        if (bufCls == null) {
            XposedBridge.log("SmoothCursor images: TerminalBuffer NOT FOUND, images off");
            return;
        }

        // 1. Входной поток байт
        XposedBridge.hookAllMethods(emuCls, "append", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam p) {
                if (!enabled || broken) return;
                if (p.args.length != 2 || !(p.args[0] instanceof byte[]) || !(p.args[1] instanceof Integer)) return;
                Session s = null;
                try {
                    s = session(p.thisObject, p.method);
                    s.delivered = false;
                    if (s.filter.process((byte[]) p.args[0], (Integer) p.args[1], s)) {
                        p.setResult(null); // чанк обработан нами
                        s.scheduleFlush();
                    }
                } catch (Throwable t) {
                    broken = true;
                    XposedBridge.log("SmoothCursor images: disabled, append error: " + t);
                    // Если часть байт уже ушла эмулятору, второй раз их отдавать нельзя
                    if (s != null && s.delivered) p.setResult(null);
                }
            }
        });

        // 2. Счётчик прокрутки: только полноэкранная прокрутка уводит строки в историю
        XposedBridge.hookAllMethods(bufCls, "scrollDownOneLine", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam p) {
                try {
                    if (p.args.length < 2) return;
                    int top = (Integer) p.args[0];
                    int bottom = (Integer) p.args[1];
                    if (top == 0 && bottom == XposedHelpers.getIntField(p.thisObject, "mScreenRows")) {
                        counter(p.thisObject)[0]++;
                    }
                } catch (Throwable ignored) {
                }
            }
        });

        // 3. Стирание: картинки исчезают вместе с текстом под ними
        XposedBridge.hookAllMethods(bufCls, "blockSet", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam p) {
                try {
                    if (p.args.length < 4) return;
                    Session s = ownerOf(p.thisObject);
                    if (s == null || s.store.isEmpty()) return;
                    s.store.eraseRect(p.thisObject, counter(p.thisObject)[0],
                            (Integer) p.args[0], (Integer) p.args[1], (Integer) p.args[2], (Integer) p.args[3]);
                } catch (Throwable ignored) {
                }
            }
        });
        XposedBridge.hookAllMethods(bufCls, "clearTranscript", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam p) {
                try {
                    Session s = ownerOf(p.thisObject);
                    if (s != null) s.store.eraseTranscript(p.thisObject, counter(p.thisObject)[0]);
                } catch (Throwable ignored) {
                }
            }
        });
        XposedBridge.hookAllMethods(emuCls, "reset", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam p) {
                try {
                    Session s;
                    synchronized (sessions) {
                        s = sessions.get(p.thisObject);
                    }
                    if (s != null) {
                        s.store.clearEverything();
                        s.filter.reset();
                    }
                } catch (Throwable ignored) {
                }
            }
        });
        XposedBridge.log("SmoothCursor images: hooks installed");
    }

    // ---------------------------------------------------------------- рисование (зовётся из SmoothCursor.java)

    private static ImageStore fStore;
    private static Object fBuf;
    private static long fScrolled;
    private static int fTop, fRows;
    private static float fCw, fCh, fOriginY;
    private static boolean fBehindPending;

    /** Начало кадра: render() до отрисовки. */
    static void frameBegin(Object renderer, Object emu, int topRow) {
        fStore = null;
        fBehindPending = false;
        if (!enabled || broken) return;
        fCw = XposedHelpers.getFloatField(renderer, "mFontWidth");
        fCh = XposedHelpers.getIntField(renderer, "mFontLineSpacing");
        lastCellW = fCw;
        lastCellH = fCh;
        Session s;
        synchronized (sessions) {
            s = sessions.get(emu);
        }
        if (s == null || s.store.isEmpty()) return;
        fOriginY = XposedHelpers.getIntField(renderer, "mFontLineSpacingAndAscent");
        fBuf = XposedHelpers.callMethod(emu, "getScreen");
        fScrolled = counter(fBuf)[0];
        fTop = topRow;
        fRows = XposedHelpers.getIntField(emu, "mRows");
        s.store.prune(fBuf, fScrolled, transcriptRows(fBuf));
        if (s.store.isEmpty()) return;
        fStore = s.store;
        fBehindPending = true;
    }

    /** Первый drawTextRun кадра: фон уже залит, текста ещё нет. Самое место для слоя "за текстом". */
    static void beforeTextRun(Object[] args) {
        if (!fBehindPending) return;
        fBehindPending = false;
        if (fStore != null && args.length > 0 && args[0] instanceof Canvas) {
            fStore.draw((Canvas) args[0], fBuf, fScrolled, fTop, fRows, fCw, fCh, fOriginY, true);
        }
    }

    /** Конец render(): слой "перед текстом" (и "за текстом", если drawTextRun не вызывался). Курсор рисуется после. */
    static void frameEnd(Object[] renderArgs) {
        ImageStore st = fStore;
        if (st == null) return;
        fStore = null;
        Canvas canvas = (Canvas) renderArgs[1];
        if (fBehindPending) {
            fBehindPending = false;
            st.draw(canvas, fBuf, fScrolled, fTop, fRows, fCw, fCh, fOriginY, true);
        }
        st.draw(canvas, fBuf, fScrolled, fTop, fRows, fCw, fCh, fOriginY, false);
    }

    // ---------------------------------------------------------------- служебное

    private static int transcriptRows(Object buf) {
        try {
            return (Integer) XposedHelpers.callMethod(buf, "getActiveTranscriptRows");
        } catch (Throwable t) {
            try {
                return XposedHelpers.getIntField(buf, "mActiveTranscriptRows");
            } catch (Throwable t2) {
                return Integer.MAX_VALUE / 4; // не знаем: ничего не удаляем
            }
        }
    }

    private static long[] counter(Object buf) {
        synchronized (scrolled) {
            long[] c = scrolled.get(buf);
            if (c == null) {
                c = new long[1];
                scrolled.put(buf, c);
            }
            return c;
        }
    }

    private static Session ownerOf(Object buf) {
        synchronized (sessions) {
            return bufferOwner.get(buf);
        }
    }

    private static Session session(Object emu, Member append) {
        synchronized (sessions) {
            Session s = sessions.get(emu);
            if (s == null) {
                s = new Session(emu, append);
                sessions.put(emu, s);
                for (String f : new String[]{"mMainBuffer", "mAltBuffer"}) {
                    try {
                        Object b = XposedHelpers.getObjectField(emu, f);
                        if (b != null) bufferOwner.put(b, s);
                    } catch (Throwable ignored) {
                    }
                }
                try {
                    Object b = XposedHelpers.callMethod(emu, "getScreen");
                    if (b != null) bufferOwner.put(b, s);
                } catch (Throwable ignored) {
                }
            }
            return s;
        }
    }

    /** Один терминал (сессия Termux): свой фильтр потока, своё хранилище картинок. */
    private static final class Session implements ImageStreamFilter.Sink, ImageProtocols.Host {
        final WeakReference<Object> emuRef;
        final Member append;
        final ImageStreamFilter filter = new ImageStreamFilter();
        final ImageStore store = new ImageStore();
        final ImageProtocols protocols = new ImageProtocols(this, store);
        boolean delivered;
        private Handler handler;
        private Runnable flushTask;

        Session(Object emu, Member append) {
            this.emuRef = new WeakReference<>(emu);
            this.append = append;
        }

        // ---- ImageStreamFilter.Sink

        @Override
        public void passThrough(byte[] buf, int off, int len) {
            invokeAppend(Arrays.copyOfRange(buf, off, off + len));
        }

        @Override
        public void sequence(int kind, byte[] payload, int len) {
            try {
                protocols.onSequence(kind, payload, len);
            } catch (Throwable t) {
                XposedBridge.log("SmoothCursor images: sequence error: " + t);
            }
        }

        private void invokeAppend(byte[] bytes) {
            Object emu = emuRef.get();
            if (emu == null || bytes.length == 0) return;
            delivered = true;
            try {
                XposedBridge.invokeOriginalMethod(append, emu, new Object[]{bytes, bytes.length});
            } catch (Throwable t) {
                XposedBridge.log("SmoothCursor images: feed error: " + t);
            }
        }

        // Одиночный ESC в конце чанка мы придерживаем (вдруг дальше пойдёт картинка).
        // Если продолжения нет, через 80 мс отдаём его эмулятору.
        void scheduleFlush() {
            if (!filter.hasHeld()) return;
            if (handler == null) handler = new Handler(Looper.getMainLooper());
            if (flushTask == null) {
                flushTask = () -> {
                    if (filter.hasHeld()) invokeAppend(filter.takeHeld());
                };
            }
            handler.removeCallbacks(flushTask);
            handler.postDelayed(flushTask, 80);
        }

        // ---- ImageProtocols.Host

        @Override
        public int cursorCol() {
            return (Integer) XposedHelpers.callMethod(emuRef.get(), "getCursorCol");
        }

        @Override
        public int cursorRow() {
            return (Integer) XposedHelpers.callMethod(emuRef.get(), "getCursorRow");
        }

        @Override
        public int columns() {
            return XposedHelpers.getIntField(emuRef.get(), "mColumns");
        }

        @Override
        public int rows() {
            return XposedHelpers.getIntField(emuRef.get(), "mRows");
        }

        @Override
        public float cellWidth() {
            float w = lastCellW;
            return w > 1f ? w : 10f;
        }

        @Override
        public float cellHeight() {
            float h = lastCellH;
            return h > 1f ? h : 20f;
        }

        @Override
        public Object activeBuffer() {
            return XposedHelpers.callMethod(emuRef.get(), "getScreen");
        }

        @Override
        public long scrolledLines(Object buffer) {
            return counter(buffer)[0];
        }

        @Override
        public int defaultZ() {
            return defaultZ;
        }

        @Override
        public void feed(byte[] bytes) {
            invokeAppend(bytes);
        }

        @Override
        public void reply(String text) {
            try {
                Object emu = emuRef.get();
                if (emu == null) return;
                Object session = XposedHelpers.getObjectField(emu, "mSession");
                byte[] b = text.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                XposedHelpers.callMethod(session, "write", b, 0, b.length);
            } catch (Throwable t) {
                XposedBridge.log("SmoothCursor images: reply error: " + t);
            }
        }

        @Override
        public void invalidate() {
            TermImages.invalidate();
        }
    }
}
