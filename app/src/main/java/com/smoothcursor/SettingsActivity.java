package com.smoothcursor;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.media.ExifInterface;
import android.net.Uri;
import android.os.Bundle;
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

    private static final int BG = 0xFF0E0E12;
    private static final int CARD = 0xFF1A1A20;
    private static final int CARD_STROKE = 0xFF2A2A33;
    private static final int FIELD = 0xFF24242C;
    private static final int TEXT = 0xFFF2F2F5;
    private static final int SUB = 0xFF9A9AA6;
    private static final int ACCENT = 0xFF8C9EFF;
    private static final int ACCENT_ON = 0xFF0E0E12;
    private static final int DANGER = 0xFFFF6B7A;
    private static final int OFF = 0xFF4A4A55;

    private SharedPreferences sp;
    private Switch swTheme;
    private LinearLayout colorBox;
    private GradientDrawable swatchBg;
    private EditText hex;
    private SeekBar sbH, sbS, sbV, sbOutline, sbTrail, sbScroll;
    private TextView tvOutline, tvTrail, tvScroll;
    private Switch swScroll;
    private Switch swPad, swGlow, swFade;
    private SeekBar sbPadX, sbPadY, sbGlowR, sbGlowI, sbFade;
    private TextView tvPadX, tvPadY, tvGlowR, tvGlowI, tvFade;

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

    // ---------------------------------------------------------------- UI-хелперы

    // Кнопка, которая сама тускнеет при setEnabled(false)
    private static class PillButton extends Button {
        PillButton(Context c) {
            super(c);
        }

        @Override
        public void setEnabled(boolean e) {
            super.setEnabled(e);
            setAlpha(e ? 1f : 0.4f);
        }
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private String dpS(int v) {
        return getString(R.string.fmt_dp, v);
    }

    private String msS(int v) {
        return getString(R.string.fmt_ms, v);
    }

    private String pctS(int v) {
        return getString(R.string.fmt_pct, v);
    }

    private ColorStateList enabledTint(int on, int off) {
        return new ColorStateList(
                new int[][]{new int[]{-android.R.attr.state_enabled}, new int[]{}},
                new int[]{off, on});
    }

    private ColorStateList checkedTint(int on, int off) {
        return new ColorStateList(
                new int[][]{new int[]{android.R.attr.state_checked}, new int[]{}},
                new int[]{on, off});
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
        c.setPadding(dp(18), dp(16), dp(18), dp(18));
        GradientDrawable gd = new GradientDrawable();
        gd.setColor(CARD);
        gd.setCornerRadius(dp(24));
        gd.setStroke(dp(1), CARD_STROKE);
        c.setBackground(gd);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(14);
        parent.addView(c, lp);
        return c;
    }

    // Заголовок карточки без переключателя
    private void head(LinearLayout card, int titleRes, int descRes) {
        card.addView(text(getString(titleRes), 16, TEXT, true));
        if (descRes != 0) desc(card, descRes);
    }

    private void desc(LinearLayout card, int descRes) {
        TextView d = text(getString(descRes), 12.5f, SUB, false);
        d.setLineSpacing(0, 1.1f);
        d.setPadding(0, dp(4), 0, 0);
        card.addView(d);
    }

    private Switch sw(LinearLayout card, int titleRes) {
        Switch s = new Switch(this);
        s.setText(titleRes);
        s.setTextColor(TEXT);
        s.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        s.setTypeface(null, Typeface.BOLD);
        s.setMinHeight(dp(40));
        s.setThumbTintList(checkedTint(ACCENT, 0xFFB4B4BE));
        s.setTrackTintList(checkedTint((ACCENT & 0x00FFFFFF) | 0x66000000, OFF));
        card.addView(s);
        return s;
    }

    // Строка «подпись слева — значение справа»
    private TextView row(LinearLayout card, int labelRes, boolean big) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(0, big ? 0 : dp(14), 0, 0);
        TextView l = text(getString(labelRes), big ? 16 : 14, big ? TEXT : SUB, big);
        r.addView(l, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        TextView v = text("", 14, ACCENT, true);
        r.addView(v);
        card.addView(r);
        return v;
    }

    private SeekBar seek(LinearLayout card, int max) {
        SeekBar sb = new SeekBar(this);
        sb.setMax(max);
        sb.setProgressTintList(enabledTint(ACCENT, OFF));
        sb.setThumbTintList(enabledTint(ACCENT, OFF));
        sb.setProgressBackgroundTintList(ColorStateList.valueOf(OFF));
        sb.setPadding(dp(14), dp(6), dp(14), dp(6));
        card.addView(sb, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        return sb;
    }

    private SeekBar labeledSeek(LinearLayout parent, int nameRes, int max) {
        TextView tv = text(getString(nameRes), 14, SUB, false);
        tv.setPadding(0, dp(14), 0, 0);
        parent.addView(tv);
        return seek(parent, max);
    }

    private EditText hexField(String hint) {
        EditText e = new EditText(this);
        e.setSingleLine(true);
        e.setTextColor(TEXT);
        e.setHintTextColor(SUB);
        e.setHint(hint);
        e.setTypeface(Typeface.MONOSPACE);
        e.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        e.setPadding(dp(14), dp(10), dp(14), dp(10));
        GradientDrawable gd = new GradientDrawable();
        gd.setColor(FIELD);
        gd.setCornerRadius(dp(14));
        e.setBackground(gd);
        e.setFilters(new InputFilter[]{new InputFilter.LengthFilter(7)});
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(10);
        e.setLayoutParams(lp);
        return e;
    }

    // style: 0 — залитая, 1 — тональная, 2 — опасная
    private Button btn(int textRes, int style) {
        PillButton b = new PillButton(this);
        b.setText(textRes);
        b.setAllCaps(false);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        b.setTypeface(null, Typeface.BOLD);
        b.setMinHeight(dp(46));
        b.setMinimumHeight(dp(46));
        b.setStateListAnimator(null);
        GradientDrawable gd = new GradientDrawable();
        gd.setCornerRadius(dp(23));
        if (style == 0) {
            gd.setColor(ACCENT);
            b.setTextColor(ACCENT_ON);
        } else if (style == 1) {
            gd.setColor((ACCENT & 0x00FFFFFF) | 0x26000000);
            b.setTextColor(ACCENT);
        } else {
            gd.setColor((DANGER & 0x00FFFFFF) | 0x1F000000);
            gd.setStroke(dp(1), (DANGER & 0x00FFFFFF) | 0x66000000);
            b.setTextColor(DANGER);
        }
        b.setBackground(new RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), gd, null));
        return b;
    }

    private void btnRow(LinearLayout parent, Button a, Button c) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setPadding(0, dp(12), 0, 0);
        LinearLayout.LayoutParams la = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        la.rightMargin = dp(5);
        LinearLayout.LayoutParams lc = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        lc.leftMargin = dp(5);
        r.addView(a, la);
        r.addView(c, lc);
        parent.addView(r);
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

    // ---------------------------------------------------------------- onCreate

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        sp = getSharedPreferences(SettingsProvider.PREFS, MODE_PRIVATE);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(BG);
        scroll.setFillViewport(true);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(44), dp(18), dp(32));
        scroll.addView(root);

        root.addView(text(getString(R.string.app_name), 32, TEXT, true));
        root.addView(text(getString(R.string.subtitle), 14, SUB, false));
        View bar = new View(this);
        GradientDrawable barBg = new GradientDrawable();
        barBg.setColor(ACCENT);
        barBg.setCornerRadius(dp(2));
        bar.setBackground(barBg);
        LinearLayout.LayoutParams barLp = new LinearLayout.LayoutParams(dp(40), dp(4));
        barLp.topMargin = dp(14);
        root.addView(bar, barLp);

        // ---- Цвет
        LinearLayout c1 = card(root);
        swTheme = sw(c1, R.string.theme_color);

        colorBox = new LinearLayout(this);
        colorBox.setOrientation(LinearLayout.VERTICAL);
        c1.addView(colorBox);

        View swatch = new View(this);
        swatchBg = new GradientDrawable();
        swatchBg.setCornerRadius(dp(14));
        swatchBg.setStroke(dp(1), CARD_STROKE);
        swatch.setBackground(swatchBg);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(48));
        slp.topMargin = dp(16);
        colorBox.addView(swatch, slp);

        hex = hexField("#RRGGBB");
        colorBox.addView(hex);

        sbH = labeledSeek(colorBox, R.string.hue, 360);
        sbS = labeledSeek(colorBox, R.string.saturation, 100);
        sbV = labeledSeek(colorBox, R.string.brightness, 100);

        // ---- Контур
        LinearLayout c2 = card(root);
        tvOutline = row(c2, R.string.outline, true);
        sbOutline = seek(c2, 100);

        // ---- Шлейф
        LinearLayout cT = card(root);
        tvTrail = row(cT, R.string.trail, true);
        desc(cT, R.string.trail_desc);
        sbTrail = seek(cT, 100);

        // ---- Скроллинг
        LinearLayout cS = card(root);
        swScroll = sw(cS, R.string.scroll);
        desc(cS, R.string.scroll_desc);
        tvScroll = row(cS, R.string.speed, false);
        sbScroll = seek(cS, 100);

        // ---- Отступ внутри терминала
        LinearLayout cP = card(root);
        swPad = sw(cP, R.string.pad);
        desc(cP, R.string.pad_desc);
        tvPadX = row(cP, R.string.pad_x, false);
        sbPadX = seek(cP, 48);
        tvPadY = row(cP, R.string.pad_y, false);
        sbPadY = seek(cP, 48);

        // ---- Свечение текста
        LinearLayout cG = card(root);
        swGlow = sw(cG, R.string.glow);
        desc(cG, R.string.glow_desc);
        tvGlowR = row(cG, R.string.glow_size, false);
        sbGlowR = seek(cG, 30);
        tvGlowI = row(cG, R.string.glow_strength, false);
        sbGlowI = seek(cG, 100);

        // ---- Плавное появление текста
        LinearLayout cF = card(root);
        swFade = sw(cF, R.string.fade);
        desc(cF, R.string.fade_desc);
        tvFade = row(cF, R.string.duration, false);
        sbFade = seek(cF, 450);

        // ---- Фон Termux
        LinearLayout cB = card(root);
        head(cB, R.string.bg_title, R.string.bg_desc);
        rgBg = new RadioGroup(this);
        rgBg.setOrientation(RadioGroup.VERTICAL);
        rgBg.setPadding(0, dp(8), 0, 0);
        int[] modeNames = {R.string.mode_off, R.string.mode_img, R.string.mode_vid, R.string.mode_grad};
        for (int i = 0; i < 4; i++) {
            RadioButton rb = new RadioButton(this);
            rb.setId(View.generateViewId());
            rb.setTag(Integer.valueOf(i));
            rb.setText(modeNames[i]);
            rb.setTextColor(TEXT);
            rb.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
            rb.setMinHeight(dp(42));
            rb.setButtonTintList(checkedTint(ACCENT, SUB));
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
        btnBgPick = btn(R.string.pick_img, 0);
        btnBgClear = btn(R.string.remove_file, 1);
        btnRow(fileBox, btnBgPick, btnBgClear);

        gradBox = new LinearLayout(this);
        gradBox.setOrientation(LinearLayout.VERTICAL);
        cB.addView(gradBox);
        TextView gh = text(getString(R.string.grad_hint), 14, SUB, false);
        gh.setPadding(0, dp(8), 0, 0);
        gradBox.addView(gh);
        hexC1 = hexField("#141E30");
        hexC2 = hexField("#243B55");
        gradBox.addView(hexC1);
        gradBox.addView(hexC2);

        tvDim = row(cB, R.string.dim, false);
        sbDim = seek(cB, 100);

        // ---- Заставка
        LinearLayout cSp = card(root);
        swSplash = sw(cSp, R.string.splash);
        desc(cSp, R.string.splash_desc);
        swSplashSound = sw(cSp, R.string.splash_sound);
        swSplashSound.setPadding(0, dp(12), 0, 0);
        tvSplashFile = text("", 14, SUB, false);
        tvSplashFile.setPadding(0, dp(8), 0, 0);
        cSp.addView(tvSplashFile);
        btnSplashPick = btn(R.string.pick_vid, 0);
        btnSplashClear = btn(R.string.remove_video, 1);
        btnRow(cSp, btnSplashPick, btnSplashClear);

        // ---- Сброс
        Button reset = btn(R.string.reset, 2);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        rlp.topMargin = dp(20);
        root.addView(reset, rlp);

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
                tvOutline.setText(pctS(p));
                sp.edit().putInt(SettingsProvider.K_OUTLINE, p).apply();
            }
        });
        sbTrail.setOnSeekBarChangeListener(new Seek() {
            void on(int p) {
                tvTrail.setText(pctS(p));
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
                tvScroll.setText(pctS(p));
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
                tvPadX.setText(dpS(p));
                sp.edit().putInt(SettingsProvider.K_PAD_X, p).apply();
            }
        });
        sbPadY.setOnSeekBarChangeListener(new Seek() {
            void on(int p) {
                tvPadY.setText(dpS(p));
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
                tvGlowR.setText(dpS(p));
                sp.edit().putInt(SettingsProvider.K_GLOW_RADIUS, p).apply();
            }
        });
        sbGlowI.setOnSeekBarChangeListener(new Seek() {
            void on(int p) {
                tvGlowI.setText(pctS(p));
                sp.edit().putInt(SettingsProvider.K_GLOW_INTENSITY, p).apply();
            }
        });

        swFade.setOnCheckedChangeListener((v, checked) -> {
            if (updating) return;
            sp.edit().putBoolean(SettingsProvider.K_FADE_ON, checked).apply();
            sbFade.setEnabled(checked);
        });
        sbFade.setOnSeekBarChangeListener(new Seek() {
            void on(int p) {
                tvFade.setText(msS(p + 50));
                sp.edit().putInt(SettingsProvider.K_FADE_MS, p + 50).apply();
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
                tvDim.setText(pctS(p));
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

        reset.setOnClickListener(v -> new AlertDialog.Builder(this, AlertDialog.THEME_DEVICE_DEFAULT_DARK)
                .setTitle(R.string.reset_title)
                .setMessage(R.string.reset_msg)
                .setPositiveButton(R.string.reset_ok, (d, w) -> {
                    sp.edit().clear().apply();
                    deleteMedia(SettingsProvider.F_IMG);
                    deleteMedia(SettingsProvider.F_VID);
                    deleteMedia(SettingsProvider.F_SPL);
                    load();
                    Toast.makeText(this, R.string.toast_reset, Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton(R.string.cancel, null)
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
        tvScroll.setText(pctS(speed));

        boolean padOn = sp.getBoolean(SettingsProvider.K_PAD_ON, SettingsProvider.D_PAD_ON);
        int padX = sp.getInt(SettingsProvider.K_PAD_X, SettingsProvider.D_PAD_X);
        int padY = sp.getInt(SettingsProvider.K_PAD_Y, SettingsProvider.D_PAD_Y);
        swPad.setChecked(padOn);
        sbPadX.setProgress(padX);
        sbPadY.setProgress(padY);
        sbPadX.setEnabled(padOn);
        sbPadY.setEnabled(padOn);
        tvPadX.setText(dpS(padX));
        tvPadY.setText(dpS(padY));

        boolean glowOn = sp.getBoolean(SettingsProvider.K_GLOW_ON, SettingsProvider.D_GLOW_ON);
        int glowR = sp.getInt(SettingsProvider.K_GLOW_RADIUS, SettingsProvider.D_GLOW_RADIUS);
        int glowI = sp.getInt(SettingsProvider.K_GLOW_INTENSITY, SettingsProvider.D_GLOW_INTENSITY);
        swGlow.setChecked(glowOn);
        sbGlowR.setProgress(glowR);
        sbGlowI.setProgress(glowI);
        sbGlowR.setEnabled(glowOn);
        sbGlowI.setEnabled(glowOn);
        tvGlowR.setText(dpS(glowR));
        tvGlowI.setText(pctS(glowI));

        boolean fadeOn = sp.getBoolean(SettingsProvider.K_FADE_ON, SettingsProvider.D_FADE_ON);
        int fadeMs = sp.getInt(SettingsProvider.K_FADE_MS, SettingsProvider.D_FADE_MS);
        swFade.setChecked(fadeOn);
        sbFade.setProgress(Math.max(0, fadeMs - 50));
        sbFade.setEnabled(fadeOn);
        tvFade.setText(msS(fadeMs));

        swTheme.setChecked(useTheme);
        Color.colorToHSV(color, hsv);
        sbH.setProgress(Math.round(hsv[0]));
        sbS.setProgress(Math.round(hsv[1] * 100));
        sbV.setProgress(Math.round(hsv[2] * 100));
        int c = Color.HSVToColor(hsv);
        swatchBg.setColor(c);
        hex.setText(String.format("#%06X", c & 0xFFFFFF));
        sbOutline.setProgress(outline);
        tvOutline.setText(pctS(outline));
        sbTrail.setProgress(trail);
        tvTrail.setText(pctS(trail));
        applyEnabled(useTheme);

        int bgMode = sp.getInt(SettingsProvider.K_BG_MODE, SettingsProvider.D_BG_MODE);
        if (bgMode < 0 || bgMode > 3) bgMode = 0;
        rgBg.check(rbMode[bgMode].getId());
        hexC1.setText(String.format("#%06X", sp.getInt(SettingsProvider.K_BG_C1, SettingsProvider.D_BG_C1) & 0xFFFFFF));
        hexC2.setText(String.format("#%06X", sp.getInt(SettingsProvider.K_BG_C2, SettingsProvider.D_BG_C2) & 0xFFFFFF));
        int dim = sp.getInt(SettingsProvider.K_BG_DIM, SettingsProvider.D_BG_DIM);
        sbDim.setProgress(dim);
        tvDim.setText(pctS(dim));
        swSplash.setChecked(sp.getBoolean(SettingsProvider.K_SPLASH_ON, SettingsProvider.D_SPLASH_ON));
        swSplashSound.setChecked(sp.getBoolean(SettingsProvider.K_SPLASH_SOUND, SettingsProvider.D_SPLASH_SOUND));
        updating = false;
        refreshBgUi();
    }

    // ---------------------------------------------------------------- фон и заставка

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
            tvBgFile.setText(has ? (mode == 1 ? R.string.bg_img_picked : R.string.bg_vid_picked) : R.string.bg_none);
            btnBgPick.setText(mode == 1 ? R.string.pick_img : R.string.pick_vid);
            btnBgClear.setEnabled(has);
        }
        boolean hasSplash = mediaFile(SettingsProvider.F_SPL).exists();
        tvSplashFile.setText(hasSplash ? R.string.splash_picked : R.string.splash_none);
        btnSplashClear.setEnabled(hasSplash);
    }

    private void pick(int req, String mime) {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType(mime);
        try {
            startActivityForResult(i, req);
        } catch (Throwable t) {
            Toast.makeText(this, R.string.toast_nopicker, Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onActivityResult(final int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (res != RESULT_OK || data == null || data.getData() == null) return;
        final Uri uri = data.getData();
        Toast.makeText(this, R.string.toast_copying, Toast.LENGTH_SHORT).show();
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
                    Toast.makeText(this, R.string.toast_done, Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(this, getString(R.string.toast_error, ferr), Toast.LENGTH_LONG).show();
                }
                refreshBgUi();
            });
        }).start();
    }

    private void copyTo(Uri uri, String name) throws IOException {
        File tmp = mediaFile(name + ".tmp");
        try (InputStream in = getContentResolver().openInputStream(uri);
             FileOutputStream out = new FileOutputStream(tmp)) {
            if (in == null) throw new IOException(getString(R.string.err_open));
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
            if (in == null) throw new IOException(getString(R.string.err_open));
            BitmapFactory.decodeStream(in, null, o);
        }
        int sample = 1;
        while (Math.max(o.outWidth, o.outHeight) / sample > 2400) sample *= 2;
        BitmapFactory.Options o2 = new BitmapFactory.Options();
        o2.inSampleSize = sample;
        Bitmap bm;
        try (InputStream in = getContentResolver().openInputStream(uri)) {
            if (in == null) throw new IOException(getString(R.string.err_open));
            bm = BitmapFactory.decodeStream(in, null, o2);
        }
        if (bm == null) throw new IOException(getString(R.string.err_not_image));
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
            if (!tmp.renameTo(dst)) throw new IOException(getString(R.string.err_save));
        }
    }
}
