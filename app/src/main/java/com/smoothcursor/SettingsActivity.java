package com.smoothcursor;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.ExifInterface;
import android.net.Uri;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputFilter;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;

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
    private Switch swPad, swGlow;
    private SeekBar sbPadX, sbPadY, sbGlowR, sbGlowI;
    private TextView tvPadX, tvPadY, tvGlowR, tvGlowI;

    private static final int REQ_IMG = 101;
    private static final int REQ_VID = 102;
    private static final int REQ_SPLASH = 103;
    private RadioGroup rgBg;
    private final RadioButton[] rbMode = new RadioButton[4];
    private Button btnBgPick, btnBgClear;
    private TextView tvBgFile, tvDim, tvSplashFile;
    private LinearLayout fileBox, gradBox;
    private EditText hexC1, hexC2;
    private SeekBar sbDim;
    private Switch swSplash, swSplashSound;
    private Button btnSplashPick, btnSplashClear;

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

        // ---- Отступ внутри терминала
        LinearLayout cP = card(root);
        swPad = new Switch(this);
        swPad.setText("Отступ от краёв экрана");
        swPad.setTextColor(TEXT);
        swPad.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        cP.addView(swPad);
        cP.addView(text("Текст не липнет к краям и скруглённым углам. Число колонок и строк пересчитается", 12, SUB, false));
        cP.addView(text("По бокам", 14, SUB, false));
        tvPadX = text("8 dp", 14, SUB, false);
        cP.addView(tvPadX);
        sbPadX = new SeekBar(this);
        sbPadX.setMax(48);
        cP.addView(sbPadX);
        cP.addView(text("Сверху и снизу", 14, SUB, false));
        tvPadY = text("8 dp", 14, SUB, false);
        cP.addView(tvPadY);
        sbPadY = new SeekBar(this);
        sbPadY.setMax(48);
        cP.addView(sbPadY);

        // ---- Свечение текста
        LinearLayout cG = card(root);
        swGlow = new Switch(this);
        swGlow.setText("Свечение текста (neon glow)");
        swGlow.setTextColor(TEXT);
        swGlow.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        cG.addView(swGlow);
        cG.addView(text("Ореол цветом самого текста, заодно светится курсор. Нужен Android 9+ и немного GPU", 12, SUB, false));
        cG.addView(text("Размер свечения", 14, SUB, false));
        tvGlowR = text("6 dp", 14, SUB, false);
        cG.addView(tvGlowR);
        sbGlowR = new SeekBar(this);
        sbGlowR.setMax(30);
        cG.addView(sbGlowR);
        cG.addView(text("Яркость свечения", 14, SUB, false));
        tvGlowI = text("70%", 14, SUB, false);
        cG.addView(tvGlowI);
        sbGlowI = new SeekBar(this);
        sbGlowI.setMax(100);
        cG.addView(sbGlowI);

        // ---- Фон Termux
        LinearLayout cB = card(root);
        cB.addView(text("Фон Termux", 16, TEXT, false));
        cB.addView(text("Картинка, видео или градиент под текстом терминала", 12, SUB, false));
        rgBg = new RadioGroup(this);
        rgBg.setOrientation(RadioGroup.VERTICAL);
        String[] modeNames = {"Выкл", "Картинка", "Видео", "Градиент"};
        for (int i = 0; i < 4; i++) {
            RadioButton rb = new RadioButton(this);
            rb.setId(View.generateViewId());
            rb.setTag(Integer.valueOf(i));
            rb.setText(modeNames[i]);
            rb.setTextColor(TEXT);
            rbMode[i] = rb;
            rgBg.addView(rb);
        }
        cB.addView(rgBg);

        fileBox = new LinearLayout(this);
        fileBox.setOrientation(LinearLayout.VERTICAL);
        cB.addView(fileBox);
        tvBgFile = text("", 14, SUB, false);
        tvBgFile.setPadding(0, dp(8), 0, 0);
        fileBox.addView(tvBgFile);
        btnBgPick = new Button(this);
        btnBgPick.setText("Выбрать файл");
        fileBox.addView(btnBgPick);
        btnBgClear = new Button(this);
        btnBgClear.setText("Убрать файл");
        fileBox.addView(btnBgClear);

        gradBox = new LinearLayout(this);
        gradBox.setOrientation(LinearLayout.VERTICAL);
        cB.addView(gradBox);
        gradBox.addView(text("Цвет сверху слева и снизу справа", 14, SUB, false));
        hexC1 = hexField("#141E30");
        hexC2 = hexField("#243B55");
        gradBox.addView(hexC1);
        gradBox.addView(hexC2);

        tvDim = text("35%", 14, SUB, false);
        tvDim.setPadding(0, dp(12), 0, 0);
        cB.addView(text("Затемнение фона", 14, SUB, false));
        cB.addView(tvDim);
        sbDim = new SeekBar(this);
        sbDim.setMax(100);
        cB.addView(sbDim);

        // ---- Заставка
        LinearLayout cSp = card(root);
        swSplash = new Switch(this);
        swSplash.setText("Заставка при запуске Termux");
        swSplash.setTextColor(TEXT);
        swSplash.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        cSp.addView(swSplash);
        cSp.addView(text("Видео играет один раз при старте Termux и плавно исчезает. "
                + "Новые сессии заставку не запускают. Тап по заставке пропускает её", 12, SUB, false));
        swSplashSound = new Switch(this);
        swSplashSound.setText("Со звуком");
        swSplashSound.setTextColor(TEXT);
        swSplashSound.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        swSplashSound.setPadding(0, dp(12), 0, 0);
        cSp.addView(swSplashSound);
        tvSplashFile = text("", 14, SUB, false);
        tvSplashFile.setPadding(0, dp(8), 0, 0);
        cSp.addView(tvSplashFile);
        btnSplashPick = new Button(this);
        btnSplashPick.setText("Выбрать видео");
        cSp.addView(btnSplashPick);
        btnSplashClear = new Button(this);
        btnSplashClear.setText("Убрать видео");
        cSp.addView(btnSplashClear);

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

        swPad.setOnCheckedChangeListener((v, checked) -> {
            if (updating) return;
            sp.edit().putBoolean(SettingsProvider.K_PAD_ON, checked).apply();
            sbPadX.setEnabled(checked);
            sbPadY.setEnabled(checked);
        });
        sbPadX.setOnSeekBarChangeListener(new Seek() {
            void on(int p) {
                tvPadX.setText(p + " dp");
                sp.edit().putInt(SettingsProvider.K_PAD_X, p).apply();
            }
        });
        sbPadY.setOnSeekBarChangeListener(new Seek() {
            void on(int p) {
                tvPadY.setText(p + " dp");
                sp.edit().putInt(SettingsProvider.K_PAD_Y, p).apply();
            }
        });
        swGlow.setOnCheckedChangeListener((v, checked) -> {
            if (updating) return;
            sp.edit().putBoolean(SettingsProvider.K_GLOW_ON, checked).apply();
            sbGlowR.setEnabled(checked);
            sbGlowI.setEnabled(checked);
        });
        sbGlowR.setOnSeekBarChangeListener(new Seek() {
            void on(int p) {
                tvGlowR.setText(p + " dp");
                sp.edit().putInt(SettingsProvider.K_GLOW_RADIUS, p).apply();
            }
        });
        sbGlowI.setOnSeekBarChangeListener(new Seek() {
            void on(int p) {
                tvGlowI.setText(p + "%");
                sp.edit().putInt(SettingsProvider.K_GLOW_INTENSITY, p).apply();
            }
        });

        rgBg.setOnCheckedChangeListener((g, id) -> {
            if (updating) return;
            View rb = g.findViewById(id);
            if (rb == null) return;
            int mode = (Integer) rb.getTag();
            sp.edit().putInt(SettingsProvider.K_BG_MODE, mode).apply();
            refreshBgUi();
        });
        btnBgPick.setOnClickListener(v -> {
            int mode = sp.getInt(SettingsProvider.K_BG_MODE, SettingsProvider.D_BG_MODE);
            if (mode == 1) pick(REQ_IMG, "image/*");
            else if (mode == 2) pick(REQ_VID, "video/*");
        });
        btnBgClear.setOnClickListener(v -> {
            int mode = sp.getInt(SettingsProvider.K_BG_MODE, SettingsProvider.D_BG_MODE);
            deleteMedia(mode == 1 ? SettingsProvider.F_IMG : SettingsProvider.F_VID);
            bumpBgVer();
            refreshBgUi();
        });
        watchHex(hexC1, SettingsProvider.K_BG_C1);
        watchHex(hexC2, SettingsProvider.K_BG_C2);
        sbDim.setOnSeekBarChangeListener(new Seek() {
            void on(int p) {
                tvDim.setText(p + "%");
                sp.edit().putInt(SettingsProvider.K_BG_DIM, p).apply();
            }
        });
        swSplash.setOnCheckedChangeListener((v, checked) -> {
            if (updating) return;
            sp.edit().putBoolean(SettingsProvider.K_SPLASH_ON, checked).apply();
        });
        swSplashSound.setOnCheckedChangeListener((v, checked) -> {
            if (updating) return;
            sp.edit().putBoolean(SettingsProvider.K_SPLASH_SOUND, checked).apply();
        });
        btnSplashPick.setOnClickListener(v -> pick(REQ_SPLASH, "video/*"));
        btnSplashClear.setOnClickListener(v -> {
            deleteMedia(SettingsProvider.F_SPL);
            refreshBgUi();
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
                    deleteMedia(SettingsProvider.F_IMG);
                    deleteMedia(SettingsProvider.F_VID);
                    deleteMedia(SettingsProvider.F_SPL);
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

        boolean padOn = sp.getBoolean(SettingsProvider.K_PAD_ON, SettingsProvider.D_PAD_ON);
        int padX = sp.getInt(SettingsProvider.K_PAD_X, SettingsProvider.D_PAD_X);
        int padY = sp.getInt(SettingsProvider.K_PAD_Y, SettingsProvider.D_PAD_Y);
        swPad.setChecked(padOn);
        sbPadX.setProgress(padX);
        sbPadY.setProgress(padY);
        sbPadX.setEnabled(padOn);
        sbPadY.setEnabled(padOn);
        tvPadX.setText(padX + " dp");
        tvPadY.setText(padY + " dp");

        boolean glowOn = sp.getBoolean(SettingsProvider.K_GLOW_ON, SettingsProvider.D_GLOW_ON);
        int glowR = sp.getInt(SettingsProvider.K_GLOW_RADIUS, SettingsProvider.D_GLOW_RADIUS);
        int glowI = sp.getInt(SettingsProvider.K_GLOW_INTENSITY, SettingsProvider.D_GLOW_INTENSITY);
        swGlow.setChecked(glowOn);
        sbGlowR.setProgress(glowR);
        sbGlowI.setProgress(glowI);
        sbGlowR.setEnabled(glowOn);
        sbGlowI.setEnabled(glowOn);
        tvGlowR.setText(glowR + " dp");
        tvGlowI.setText(glowI + "%");

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

        int bgMode = sp.getInt(SettingsProvider.K_BG_MODE, SettingsProvider.D_BG_MODE);
        if (bgMode < 0 || bgMode > 3) bgMode = 0;
        rgBg.check(rbMode[bgMode].getId());
        hexC1.setText(String.format("#%06X", sp.getInt(SettingsProvider.K_BG_C1, SettingsProvider.D_BG_C1) & 0xFFFFFF));
        hexC2.setText(String.format("#%06X", sp.getInt(SettingsProvider.K_BG_C2, SettingsProvider.D_BG_C2) & 0xFFFFFF));
        int dim = sp.getInt(SettingsProvider.K_BG_DIM, SettingsProvider.D_BG_DIM);
        sbDim.setProgress(dim);
        tvDim.setText(dim + "%");
        swSplash.setChecked(sp.getBoolean(SettingsProvider.K_SPLASH_ON, SettingsProvider.D_SPLASH_ON));
        swSplashSound.setChecked(sp.getBoolean(SettingsProvider.K_SPLASH_SOUND, SettingsProvider.D_SPLASH_SOUND));
        updating = false;
        refreshBgUi();
    }

    // ---------------------------------------------------------------- фон и заставка

    private EditText hexField(String hint) {
        EditText e = new EditText(this);
        e.setSingleLine(true);
        e.setTextColor(TEXT);
        e.setHintTextColor(SUB);
        e.setHint(hint);
        e.setFilters(new InputFilter[]{new InputFilter.LengthFilter(7)});
        return e;
    }

    private void watchHex(final EditText et, final String key) {
        et.addTextChangedListener(new TextWatcher() {
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
                    int col = Color.parseColor(t) | 0xFF000000;
                    sp.edit().putInt(key, col).apply();
                    bumpBgVer();
                } catch (IllegalArgumentException ignored) {
                }
            }
        });
    }

    private File mediaFile(String name) {
        return new File(getFilesDir(), name);
    }

    private void deleteMedia(String name) {
        mediaFile(name).delete();
    }

    private void bumpBgVer() {
        sp.edit().putInt(SettingsProvider.K_BG_VER, sp.getInt(SettingsProvider.K_BG_VER, 0) + 1).apply();
    }

    private void refreshBgUi() {
        int mode = sp.getInt(SettingsProvider.K_BG_MODE, SettingsProvider.D_BG_MODE);
        boolean media = mode == 1 || mode == 2;
        fileBox.setVisibility(media ? View.VISIBLE : View.GONE);
        gradBox.setVisibility(mode == 3 ? View.VISIBLE : View.GONE);
        sbDim.setEnabled(mode != 0);
        if (media) {
            boolean has = mediaFile(mode == 1 ? SettingsProvider.F_IMG : SettingsProvider.F_VID).exists();
            String what = mode == 1 ? "Картинка" : "Видео";
            tvBgFile.setText(has ? what + " выбрана" : "Файл не выбран");
            btnBgPick.setText(mode == 1 ? "Выбрать картинку" : "Выбрать видео");
            btnBgClear.setEnabled(has);
        }
        boolean hasSplash = mediaFile(SettingsProvider.F_SPL).exists();
        tvSplashFile.setText(hasSplash ? "Видео выбрано" : "Видео не выбрано");
        btnSplashClear.setEnabled(hasSplash);
    }

    private void pick(int req, String mime) {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType(mime);
        try {
            startActivityForResult(i, req);
        } catch (Throwable t) {
            Toast.makeText(this, "Нет приложения для выбора файла", Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onActivityResult(final int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (res != RESULT_OK || data == null || data.getData() == null) return;
        final Uri uri = data.getData();
        Toast.makeText(this, "Копирую файл…", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            String err = null;
            try {
                if (req == REQ_IMG) saveImage(uri);
                else if (req == REQ_VID) copyTo(uri, SettingsProvider.F_VID);
                else if (req == REQ_SPLASH) copyTo(uri, SettingsProvider.F_SPL);
            } catch (Throwable t) {
                err = String.valueOf(t.getMessage());
            }
            final String ferr = err;
            runOnUiThread(() -> {
                if (ferr == null) {
                    if (req != REQ_SPLASH) bumpBgVer();
                    Toast.makeText(this, "Готово", Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(this, "Ошибка: " + ferr, Toast.LENGTH_LONG).show();
                }
                refreshBgUi();
            });
        }).start();
    }

    private void copyTo(Uri uri, String name) throws IOException {
        File tmp = mediaFile(name + ".tmp");
        try (InputStream in = getContentResolver().openInputStream(uri);
             FileOutputStream out = new FileOutputStream(tmp)) {
            if (in == null) throw new IOException("не удалось открыть файл");
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        }
        replaceFile(tmp, mediaFile(name));
    }

    // Картинку уменьшаем до разумного размера и поворачиваем по EXIF, чтобы Termux не ловил OOM
    private void saveImage(Uri uri) throws IOException {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        try (InputStream in = getContentResolver().openInputStream(uri)) {
            if (in == null) throw new IOException("не удалось открыть файл");
            BitmapFactory.decodeStream(in, null, o);
        }
        int sample = 1;
        while (Math.max(o.outWidth, o.outHeight) / sample > 2400) sample *= 2;
        BitmapFactory.Options o2 = new BitmapFactory.Options();
        o2.inSampleSize = sample;
        Bitmap bm;
        try (InputStream in = getContentResolver().openInputStream(uri)) {
            if (in == null) throw new IOException("не удалось открыть файл");
            bm = BitmapFactory.decodeStream(in, null, o2);
        }
        if (bm == null) throw new IOException("не картинка");
        int rot = 0;
        try (InputStream in = getContentResolver().openInputStream(uri)) {
            if (in != null) {
                int ori = new ExifInterface(in).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1);
                if (ori == 6) rot = 90;
                else if (ori == 3) rot = 180;
                else if (ori == 8) rot = 270;
            }
        } catch (Throwable ignored) {
        }
        if (rot != 0) {
            Matrix m = new Matrix();
            m.postRotate(rot);
            Bitmap r = Bitmap.createBitmap(bm, 0, 0, bm.getWidth(), bm.getHeight(), m, true);
            bm.recycle();
            bm = r;
        }
        File tmp = mediaFile(SettingsProvider.F_IMG + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            bm.compress(Bitmap.CompressFormat.JPEG, 92, out);
        }
        bm.recycle();
        replaceFile(tmp, mediaFile(SettingsProvider.F_IMG));
    }

    private void replaceFile(File tmp, File dst) throws IOException {
        if (!tmp.renameTo(dst)) {
            dst.delete();
            if (!tmp.renameTo(dst)) throw new IOException("не удалось сохранить файл");
        }
    }
}
