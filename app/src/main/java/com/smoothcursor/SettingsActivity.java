package com.smoothcursor;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputFilter;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

public class SettingsActivity extends Activity {

    private static final int BG = 0xFF121212;
    private static final int CARD = 0xFF1E1E1E;
    private static final int TEXT = 0xFFFFFFFF;
    private static final int SUB = 0xFF9AA0A6;

    private SharedPreferences sp;
    private Switch swTheme;
    private LinearLayout colorBox;
    private GradientDrawable swatchBg;
    private EditText hex;
    private SeekBar sbH, sbS, sbV, sbOutline, sbTrail, sbScroll;
    private TextView tvOutline, tvTrail, tvScroll;
    private Switch swScroll;
    private boolean updating = false;
    private final float[] hsv = new float[3];

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private TextView text(String s, float size, int color, boolean bold) {
        TextView tv = new TextView(this);
        tv.setText(s);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, size);
        tv.setTextColor(color);
        if (bold) tv.setTypeface(null, Typeface.BOLD);
        return tv;
    }

    private LinearLayout card(LinearLayout parent) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(dp(16), dp(16), dp(16), dp(16));
        GradientDrawable gd = new GradientDrawable();
        gd.setColor(CARD);
        gd.setCornerRadius(dp(20));
        c.setBackground(gd);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(16);
        parent.addView(c, lp);
        return c;
    }

    private SeekBar labeledSeek(LinearLayout parent, String name, int max) {
        TextView tv = text(name, 14, SUB, false);
        tv.setPadding(0, dp(12), 0, 0);
        parent.addView(tv);
        SeekBar sb = new SeekBar(this);
        sb.setMax(max);
        parent.addView(sb, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        return sb;
    }

    private abstract static class Seek implements SeekBar.OnSeekBarChangeListener {
        abstract void on(int p);

        @Override
        public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
            if (fromUser) on(p);
        }

        @Override
        public void onStartTrackingTouch(SeekBar s) {
        }

        @Override
        public void onStopTrackingTouch(SeekBar s) {
        }
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        sp = getSharedPreferences(SettingsProvider.PREFS, MODE_PRIVATE);

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(BG);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(48), dp(20), dp(32));
        scroll.addView(root);

        root.addView(text("SmoothCursor", 30, TEXT, true));
        root.addView(text("Настройки курсора в Termux", 14, SUB, false));

        LinearLayout c1 = card(root);
        swTheme = new Switch(this);
        swTheme.setText("Цвет из темы Termux");
        swTheme.setTextColor(TEXT);
        swTheme.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        c1.addView(swTheme);

        colorBox = new LinearLayout(this);
        colorBox.setOrientation(LinearLayout.VERTICAL);
        c1.addView(colorBox);

        View swatch = new View(this);
        swatchBg = new GradientDrawable();
        swatchBg.setCornerRadius(dp(12));
        swatch.setBackground(swatchBg);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(44));
        slp.topMargin = dp(16);
        colorBox.addView(swatch, slp);

        hex = new EditText(this);
        hex.setSingleLine(true);
        hex.setTextColor(TEXT);
        hex.setHintTextColor(SUB);
        hex.setHint("#RRGGBB");
        hex.setFilters(new InputFilter[]{new InputFilter.LengthFilter(7)});
        colorBox.addView(hex);

        sbH = labeledSeek(colorBox, "Оттенок", 360);
        sbS = labeledSeek(colorBox, "Насыщенность", 100);
        sbV = labeledSeek(colorBox, "Яркость", 100);

        LinearLayout c2 = card(root);
        c2.addView(text("Интенсивность контура", 16, TEXT, false));
        tvOutline = text("0%", 14, SUB, false);
        c2.addView(tvOutline);
        sbOutline = new SeekBar(this);
        sbOutline.setMax(100);
        c2.addView(sbOutline);

        LinearLayout cT = card(root);
        cT.addView(text("Интенсивность шлейфа", 16, TEXT, false));
        cT.addView(text("Чем выше, тем длиннее и заметнее след, и тем плавнее перелёт", 12, SUB, false));
        tvTrail = text("50%", 14, SUB, false);
        cT.addView(tvTrail);
        sbTrail = new SeekBar(this);
        sbTrail.setMax(100);
        cT.addView(sbTrail);

        LinearLayout cS = card(root);
        swScroll = new Switch(this);
        swScroll.setText("Плавный скроллинг");
        swScroll.setTextColor(TEXT);
        swScroll.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        cS.addView(swScroll);
        cS.addView(text("Текст плавно ездит при прокрутке, а не перескакивает по строкам", 12, SUB, false));
        tvScroll = text("50%", 14, SUB, false);
        tvScroll.setPadding(0, dp(12), 0, 0);
        cS.addView(tvScroll);
        sbScroll = new SeekBar(this);
        sbScroll.setMax(100);
        cS.addView(sbScroll);

        LinearLayout c3 = card(root);
        Button reset = new Button(this);
        reset.setText("Сбросить все настройки");
        c3.addView(reset);

        setContentView(scroll);

        swTheme.setOnCheckedChangeListener((v, checked) -> {
            if (updating) return;
            sp.edit().putBoolean(SettingsProvider.K_USE_THEME, checked).apply();
            applyEnabled(checked);
        });

        sbH.setOnSeekBarChangeListener(new Seek() {
            void on(int p) {
                hsv[0] = p;
                colorChanged(true);
            }
        });
        sbS.setOnSeekBarChangeListener(new Seek() {
            void on(int p) {
                hsv[1] = p / 100f;
                colorChanged(true);
            }
        });
        sbV.setOnSeekBarChangeListener(new Seek() {
            void on(int p) {
                hsv[2] = p / 100f;
                colorChanged(true);
            }
        });
        sbOutline.setOnSeekBarChangeListener(new Seek() {
            void on(int p) {
                tvOutline.setText(p + "%");
                sp.edit().putInt(SettingsProvider.K_OUTLINE, p).apply();
            }
        });
        sbTrail.setOnSeekBarChangeListener(new Seek() {
            void on(int p) {
                tvTrail.setText(p + "%");
                sp.edit().putInt(SettingsProvider.K_TRAIL, p).apply();
            }
        });

        swScroll.setOnCheckedChangeListener((v, checked) -> {
            if (updating) return;
            sp.edit().putBoolean(SettingsProvider.K_SMOOTH_SCROLL, checked).apply();
            sbScroll.setEnabled(checked);
        });
        sbScroll.setOnSeekBarChangeListener(new Seek() {
            void on(int p) {
                tvScroll.setText(p + "%");
                sp.edit().putInt(SettingsProvider.K_SCROLL_SPEED, p).apply();
            }
        });

        hex.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b2, int c) {
            }

            @Override
            public void onTextChanged(CharSequence s, int a, int b2, int c) {
            }

            @Override
            public void afterTextChanged(Editable e) {
                if (updating) return;
                String t = e.toString().trim();
                if (t.length() == 6) t = "#" + t;
                if (t.length() != 7 || t.charAt(0) != '#') return;
                try {
                    int col = Color.parseColor(t);
                    Color.colorToHSV(col, hsv);
                    updating = true;
                    sbH.setProgress(Math.round(hsv[0]));
                    sbS.setProgress(Math.round(hsv[1] * 100));
                    sbV.setProgress(Math.round(hsv[2] * 100));
                    updating = false;
                    colorChanged(false);
                } catch (IllegalArgumentException ignored) {
                    updating = false;
                }
            }
        });

        reset.setOnClickListener(v -> new AlertDialog.Builder(this)
                .setTitle("Сбросить все настройки?")
                .setMessage("Все значения вернутся к настройкам по умолчанию.")
                .setPositiveButton("Сбросить", (d, w) -> {
                    sp.edit().clear().apply();
                    load();
                    Toast.makeText(this, "Настройки сброшены", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("Отмена", null)
                .show());

        load();
    }

    private void colorChanged(boolean writeHex) {
        int c = Color.HSVToColor(hsv);
        swatchBg.setColor(c);
        sp.edit().putInt(SettingsProvider.K_COLOR, c | 0xFF000000).apply();
        if (writeHex) {
            updating = true;
            hex.setText(String.format("#%06X", c & 0xFFFFFF));
            updating = false;
        }
    }

    private void applyEnabled(boolean useTheme) {
        boolean en = !useTheme;
        colorBox.setAlpha(en ? 1f : 0.4f);
        sbH.setEnabled(en);
        sbS.setEnabled(en);
        sbV.setEnabled(en);
        hex.setEnabled(en);
    }

    private void load() {
        updating = true;
        boolean useTheme = sp.getBoolean(SettingsProvider.K_USE_THEME, SettingsProvider.D_USE_THEME);
        int color = sp.getInt(SettingsProvider.K_COLOR, SettingsProvider.D_COLOR);
        int outline = sp.getInt(SettingsProvider.K_OUTLINE, SettingsProvider.D_OUTLINE);
        int trail = sp.getInt(SettingsProvider.K_TRAIL, SettingsProvider.D_TRAIL);

        boolean smooth = sp.getBoolean(SettingsProvider.K_SMOOTH_SCROLL, SettingsProvider.D_SMOOTH_SCROLL);
        int speed = sp.getInt(SettingsProvider.K_SCROLL_SPEED, SettingsProvider.D_SCROLL_SPEED);
        swScroll.setChecked(smooth);
        sbScroll.setProgress(speed);
        sbScroll.setEnabled(smooth);
        tvScroll.setText(speed + "%");

        swTheme.setChecked(useTheme);
        Color.colorToHSV(color, hsv);
        sbH.setProgress(Math.round(hsv[0]));
        sbS.setProgress(Math.round(hsv[1] * 100));
        sbV.setProgress(Math.round(hsv[2] * 100));
        int c = Color.HSVToColor(hsv);
        swatchBg.setColor(c);
        hex.setText(String.format("#%06X", c & 0xFFFFFF));
        sbOutline.setProgress(outline);
        tvOutline.setText(outline + "%");
        sbTrail.setProgress(trail);
        tvTrail.setText(trail + "%");
        applyEnabled(useTheme);
        updating = false;
    }
}
