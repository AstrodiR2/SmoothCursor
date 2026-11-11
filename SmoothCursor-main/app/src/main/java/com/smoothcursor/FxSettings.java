package com.smoothcursor;

import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.Editable;
import android.text.InputFilter;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;

import java.io.File;
import java.util.List;

/**
 * Карточки настроек для новых функций. Каждый контроль сам читает и пишет SharedPreferences,
 * а при загрузке и сбросе перечитывается через список refresh.
 */
final class FxSettings {

    private static final int CARD = 0xFF1E1E1E;
    private static final int TEXT = 0xFFFFFFFF;
    private static final int SUB = 0xFF9AA0A6;

    private final SettingsActivity act;
    private final SharedPreferences sp;
    private final List<Runnable> refresh;
    private boolean busy = false;

    FxSettings(SettingsActivity act, SharedPreferences sp, List<Runnable> refresh) {
        this.act = act;
        this.sp = sp;
        this.refresh = refresh;
    }

    void build(LinearLayout root) {
        cursorModes(root);
        blink(root);
        shape(root);
        particles(root);
        fade(root);
        saver(root);
    }

    // ------------------------------------------------------------ карточки

    private void cursorModes(LinearLayout root) {
        LinearLayout c = card(root, "Курсор: цвета по режимам",
                "Цвет плавно меняется, когда ты печатаешь, жмёшь Enter, выделяешь текст или открываешь vim/nano");
        sw(c, "Включить цвета по режимам", Cfg.K_CM_ON, Cfg.D_CM_ON);
        hex(c, "Обычный", Cfg.K_CM_NORMAL, Cfg.D_CM_NORMAL);
        hex(c, "Ввод текста", Cfg.K_CM_TYPING, Cfg.D_CM_TYPING);
        hex(c, "Enter (короткая вспышка)", Cfg.K_CM_ENTER, Cfg.D_CM_ENTER);
        hex(c, "Выделение текста", Cfg.K_CM_SELECT, Cfg.D_CM_SELECT);
        hex(c, "Полноэкранное приложение (vim, nano)", Cfg.K_CM_APP, Cfg.D_CM_APP);
    }

    private void blink(LinearLayout root) {
        LinearLayout c = card(root, "Курсор: плавное мигание",
                "Курсор горит ровно, пока ты печатаешь, а потом плавно пульсирует");
        sw(c, "Плавное мигание", Cfg.K_BL_ON, Cfg.D_BL_ON);
        seek(c, "Период", Cfg.K_BL_PERIOD, 400, 2400, Cfg.D_BL_PERIOD, " мс");
        seek(c, "Минимальная яркость", Cfg.K_BL_MIN, 0, 80, Cfg.D_BL_MIN, "%");
        seek(c, "Горит ровно после ввода", Cfg.K_BL_HOLD, 0, 2000, Cfg.D_BL_HOLD, " мс");
    }

    private void shape(LinearLayout root) {
        LinearLayout c = card(root, "Курсор: углы и свечение", null);
        seek(c, "Скругление углов", Cfg.K_CR_ROUND, 0, 100, Cfg.D_CR_ROUND, "%");
        seek(c, "Сила свечения (0 = выкл)", Cfg.K_GL_STR, 0, 100, Cfg.D_GL_STR, "%");
        seek(c, "Радиус свечения", Cfg.K_GL_RADIUS, 0, 100, Cfg.D_GL_RADIUS, "%");
        sw(c, "Пульсация свечения", Cfg.K_GL_PULSE, Cfg.D_GL_PULSE);
        sw(c, "Свой цвет свечения", Cfg.K_GL_CUSTOM, Cfg.D_GL_CUSTOM);
        hex(c, "Цвет свечения", Cfg.K_GL_COLOR, Cfg.D_GL_COLOR);
    }

    private void particles(LinearLayout root) {
        LinearLayout c = card(root, "Курсор: частицы",
                "Как в Neovide: след из частиц при движении курсора и всплеск на Enter");
        sw(c, "Включить частицы", Cfg.K_PT_ON, Cfg.D_PT_ON);
        choice(c, "Стиль", Cfg.K_PT_STYLE, new String[]{"Пыль", "Искры", "Кольцо"}, Cfg.D_PT_STYLE);
        sw(c, "След при движении и вводе", Cfg.K_PT_MOVE, Cfg.D_PT_MOVE);
        sw(c, "Всплеск на Enter", Cfg.K_PT_ENTER, Cfg.D_PT_ENTER);
        seek(c, "Количество", Cfg.K_PT_COUNT, 1, 20, Cfg.D_PT_COUNT, "");
        seek(c, "Размер", Cfg.K_PT_SIZE, 1, 12, Cfg.D_PT_SIZE, "");
        seek(c, "Время жизни", Cfg.K_PT_LIFE, 200, 2000, Cfg.D_PT_LIFE, " мс");
        seek(c, "Разлёт", Cfg.K_PT_SPREAD, 0, 100, Cfg.D_PT_SPREAD, "%");
        seek(c, "Скорость", Cfg.K_PT_SPEED, 0, 100, Cfg.D_PT_SPEED, "%");
        choice(c, "Цвет частиц", Cfg.K_PT_CMODE, new String[]{"Как у курсора", "Свой", "Радуга"}, Cfg.D_PT_CMODE);
        hex(c, "Свой цвет", Cfg.K_PT_COLOR, Cfg.D_PT_COLOR);
    }

    private void fade(LinearLayout root) {
        LinearLayout c = card(root, "Появление ввода (fade-in)",
                "Новые символы появляются не сразу, а с анимацией");
        sw(c, "Включить", Cfg.K_FI_ON, Cfg.D_FI_ON);
        choice(c, "Вид появления", Cfg.K_FI_STYLE, new String[]{
                "Проявление", "Снизу вверх", "Сверху вниз", "Слева направо", "Из масштаба", "Случайный для каждой буквы"
        }, Cfg.D_FI_STYLE);
        seek(c, "Длительность", Cfg.K_FI_MS, 60, 600, Cfg.D_FI_MS, " мс");
        sw(c, "Только то, что печатаю я (не вывод команд)", Cfg.K_FI_INPUT, Cfg.D_FI_INPUT);
        sw(c, "Не в vim, nano и других полноэкранных приложениях", Cfg.K_FI_NOAPP, Cfg.D_FI_NOAPP);
    }

    private void saver(LinearLayout root) {
        LinearLayout c = card(root, "Скринсейвер при простое",
                "Включается, когда ты не трогаешь Termux. В фоне и при свёрнутом Termux ничего не рисуется. "
                        + "Тап или ввод с клавиатуры его закрывает");
        choice(c, "Режим", Cfg.K_SS_MODE, new String[]{
                "Выкл", "Матричный дождь", "Частицы", "Фото", "Видео"
        }, Cfg.D_SS_MODE);
        seekNames(c, "Включать через", Cfg.K_SS_IDLE, new String[]{
                "10 секунд", "30 секунд", "1 минуту", "2 минуты", "5 минут", "10 минут"
        }, Cfg.D_SS_IDLE);
        seek(c, "Плавное появление и исчезновение", Cfg.K_SS_FADE, 0, 2000, Cfg.D_SS_FADE, " мс");
        sw(c, "Не включать, пока в терминале идёт вывод", Cfg.K_SS_NOOUT, Cfg.D_SS_NOOUT);

        c.addView(sub("Дождь и частицы"));
        hex(c, "Цвет", Cfg.K_SS_COLOR, Cfg.D_SS_COLOR);
        seek(c, "Скорость", Cfg.K_SS_SPEED, 0, 100, Cfg.D_SS_SPEED, "%");
        seek(c, "Размер", Cfg.K_SS_SIZE, 8, 40, Cfg.D_SS_SIZE, "");
        seek(c, "Плотность дождя", Cfg.K_SS_DENSITY, 20, 100, Cfg.D_SS_DENSITY, "%");
        choice(c, "Символы дождя", Cfg.K_SS_CHARS, new String[]{"Катакана и цифры", "Двоичные", "Шестнадцатеричные"},
                Cfg.D_SS_CHARS);
        seek(c, "Количество частиц", Cfg.K_SS_COUNT, 20, 200, Cfg.D_SS_COUNT, "");
        sw(c, "Линии между близкими частицами", Cfg.K_SS_LINKS, Cfg.D_SS_LINKS);

        c.addView(sub("Фото и видео"));
        seek(c, "Затемнение", Cfg.K_SS_DIM, 0, 100, Cfg.D_SS_DIM, "%");
        final TextView st = text("", 14, SUB, false);
        st.setPadding(0, dp(8), 0, 0);
        c.addView(st);
        Button pickImg = new Button(act);
        pickImg.setText("Выбрать фото");
        pickImg.setOnClickListener(v -> act.pick(SettingsActivity.REQ_SS_IMG, "image/*"));
        c.addView(pickImg);
        Button pickVid = new Button(act);
        pickVid.setText("Выбрать видео");
        pickVid.setOnClickListener(v -> act.pick(SettingsActivity.REQ_SS_VID, "video/*"));
        c.addView(pickVid);
        final Button clear = new Button(act);
        clear.setText("Убрать фото и видео");
        clear.setOnClickListener(v -> {
            act.deleteMedia(SettingsProvider.F_SSIMG);
            act.deleteMedia(SettingsProvider.F_SSVID);
            runRefresh();
        });
        c.addView(clear);
        refresh.add(() -> {
            File fi = act.mediaFile(SettingsProvider.F_SSIMG);
            File fv = act.mediaFile(SettingsProvider.F_SSVID);
            String t = "Фото: " + (fi.exists() ? "выбрано" : "нет") + ", видео: " + (fv.exists() ? "выбрано" : "нет");
            st.setText(t);
            clear.setEnabled(fi.exists() || fv.exists());
        });

        sw(c, "Плавное зацикливание видео", Cfg.K_SS_VFADE, Cfg.D_SS_VFADE);
        c.addView(text("В конце видео картинка плавно гаснет и появляется снова с самого начала", 12, SUB, false));
        seek(c, "Длительность затухания и появления", Cfg.K_SS_VFADE_MS, 200, 3000, Cfg.D_SS_VFADE_MS, " мс");
        seek(c, "Пауза на чёрном между повторами", Cfg.K_SS_VPAUSE, 0, 2000, Cfg.D_SS_VPAUSE, " мс");
    }

    private void runRefresh() {
        for (Runnable r : refresh) r.run();
    }

    // ------------------------------------------------------------ строительные блоки

    private int dp(int v) {
        return Math.round(v * act.getResources().getDisplayMetrics().density);
    }

    private TextView text(String s, float size, int color, boolean bold) {
        TextView tv = new TextView(act);
        tv.setText(s);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, size);
        tv.setTextColor(color);
        if (bold) tv.setTypeface(null, Typeface.BOLD);
        return tv;
    }

    private TextView sub(String s) {
        TextView tv = text(s, 14, TEXT, true);
        tv.setPadding(0, dp(20), 0, dp(4));
        return tv;
    }

    private LinearLayout card(LinearLayout root, String title, String subtitle) {
        LinearLayout c = new LinearLayout(act);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(dp(16), dp(16), dp(16), dp(16));
        GradientDrawable gd = new GradientDrawable();
        gd.setColor(CARD);
        gd.setCornerRadius(dp(20));
        c.setBackground(gd);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(16);
        root.addView(c, lp);
        c.addView(text(title, 18, TEXT, true));
        if (subtitle != null) {
            TextView s = text(subtitle, 12, SUB, false);
            s.setPadding(0, dp(2), 0, dp(4));
            c.addView(s);
        }
        return c;
    }

    private void sw(LinearLayout parent, String label, final String key, final boolean def) {
        final Switch s = new Switch(act);
        s.setText(label);
        s.setTextColor(TEXT);
        s.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        s.setPadding(0, dp(12), 0, 0);
        parent.addView(s);
        s.setOnCheckedChangeListener((v, checked) -> {
            if (busy) return;
            sp.edit().putBoolean(key, checked).apply();
        });
        refresh.add(() -> {
            busy = true;
            s.setChecked(sp.getBoolean(key, def));
            busy = false;
        });
    }

    private void seek(LinearLayout parent, String label, String key, int min, int max, int def, String unit) {
        seekImpl(parent, label, key, min, max, def, unit, null);
    }

    private void seekNames(LinearLayout parent, String label, String key, String[] names, int def) {
        seekImpl(parent, label, key, 0, names.length - 1, def, "", names);
    }

    private void seekImpl(LinearLayout parent, final String label, final String key, final int min, final int max,
                          final int def, final String unit, final String[] names) {
        final TextView tv = text("", 14, SUB, false);
        tv.setPadding(0, dp(12), 0, 0);
        parent.addView(tv);
        final SeekBar sb = new SeekBar(act);
        sb.setMax(max - min);
        parent.addView(sb, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        final Runnable show = () -> {
            int v = min + sb.getProgress();
            String vs = names != null ? names[Math.max(0, Math.min(names.length - 1, v))] : (v + unit);
            tv.setText(label + ": " + vs);
        };
        sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                show.run();
                if (fromUser && !busy) sp.edit().putInt(key, min + p).apply();
            }

            @Override
            public void onStartTrackingTouch(SeekBar s) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar s) {
            }
        });
        refresh.add(() -> {
            busy = true;
            int v = Math.max(min, Math.min(max, sp.getInt(key, def)));
            sb.setProgress(v - min);
            show.run();
            busy = false;
        });
    }

    private void hex(LinearLayout parent, String label, final String key, final int def) {
        TextView tv = text(label, 14, SUB, false);
        tv.setPadding(0, dp(12), 0, 0);
        parent.addView(tv);

        LinearLayout row = new LinearLayout(act);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        parent.addView(row);

        final GradientDrawable swatch = new GradientDrawable();
        swatch.setCornerRadius(dp(8));
        View sv = new View(act);
        sv.setBackground(swatch);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(dp(36), dp(36));
        slp.rightMargin = dp(12);
        row.addView(sv, slp);

        final EditText et = new EditText(act);
        et.setSingleLine(true);
        et.setTextColor(TEXT);
        et.setHintTextColor(SUB);
        et.setHint("#RRGGBB");
        et.setFilters(new InputFilter[]{new InputFilter.LengthFilter(7)});
        row.addView(et, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        et.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void afterTextChanged(Editable e) {
                if (busy) return;
                String t = e.toString().trim();
                if (t.length() == 6) t = "#" + t;
                if (t.length() != 7 || t.charAt(0) != '#') return;
                try {
                    int col = Color.parseColor(t) | 0xFF000000;
                    swatch.setColor(col);
                    sp.edit().putInt(key, col).apply();
                } catch (IllegalArgumentException ignored) {
                }
            }
        });
        refresh.add(() -> {
            busy = true;
            int col = sp.getInt(key, def) | 0xFF000000;
            swatch.setColor(col);
            et.setText(String.format("#%06X", col & 0xFFFFFF));
            busy = false;
        });
    }

    private void choice(LinearLayout parent, String label, final String key, String[] names, final int def) {
        TextView tv = text(label, 14, SUB, false);
        tv.setPadding(0, dp(12), 0, 0);
        parent.addView(tv);

        final RadioGroup rg = new RadioGroup(act);
        rg.setOrientation(RadioGroup.VERTICAL);
        final RadioButton[] rbs = new RadioButton[names.length];
        for (int i = 0; i < names.length; i++) {
            RadioButton rb = new RadioButton(act);
            rb.setId(View.generateViewId());
            rb.setTag(Integer.valueOf(i));
            rb.setText(names[i]);
            rb.setTextColor(TEXT);
            rbs[i] = rb;
            rg.addView(rb);
        }
        parent.addView(rg);
        rg.setOnCheckedChangeListener((g, id) -> {
            if (busy) return;
            View rb = g.findViewById(id);
            if (rb == null) return;
            sp.edit().putInt(key, (Integer) rb.getTag()).apply();
        });
        refresh.add(() -> {
            busy = true;
            int v = Math.max(0, Math.min(rbs.length - 1, sp.getInt(key, def)));
            rg.check(rbs[v].getId());
            busy = false;
        });
    }
}
