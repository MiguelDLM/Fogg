package com.example.dialsender;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.os.LocaleListCompat;

public class SettingsActivity extends AppCompatActivity {

    private static final String PREFS = "dial_studio_prefs";
    private static final String KEY_THEME = "theme_mode";
    private static final String KEY_LANG = "language";

    private TextView txtThemeValue, txtLangValue;
    private SharedPreferences prefs;

    protected void attachBaseContext(android.content.Context base) {
        super.attachBaseContext(LocaleHelper.wrap(base));
    }

    protected void onCreate(Bundle savedInstanceState) {
        applyTheme(false);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        txtThemeValue = findViewById(R.id.txtThemeValue);
        txtLangValue = findViewById(R.id.txtLangValue);

        com.example.dialsender.views.FoggToolbar.attach(this, R.string.settings);

        // Theme picker
        findViewById(R.id.rowTheme).setOnClickListener(v -> showThemePicker());

        // Language picker
        findViewById(R.id.rowLanguage).setOnClickListener(v -> showLanguagePicker());

        updateLabels();
    }

    private void updateLabels() {
        int mode = prefs.getInt(KEY_THEME, AppCompatDelegate.MODE_NIGHT_YES);
        switch (mode) {
            case AppCompatDelegate.MODE_NIGHT_YES:
                txtThemeValue.setText(R.string.theme_dark);
                break;
            case AppCompatDelegate.MODE_NIGHT_NO:
                txtThemeValue.setText(R.string.theme_light);
                break;
            default:
                txtThemeValue.setText(R.string.theme_system);
                break;
        }

        String lang = prefs.getString(KEY_LANG, "system");
        switch (lang) {
            case "en":
                txtLangValue.setText(R.string.lang_english);
                break;
            case "es":
                txtLangValue.setText(R.string.lang_spanish);
                break;
            default:
                txtLangValue.setText(R.string.lang_system);
                break;
        }
    }

    private void showThemePicker() {
        String[] options = {getString(R.string.theme_dark), getString(R.string.theme_light), getString(R.string.theme_system)};
        int[] modes = {AppCompatDelegate.MODE_NIGHT_YES, AppCompatDelegate.MODE_NIGHT_NO, AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM};

        int current = prefs.getInt(KEY_THEME, AppCompatDelegate.MODE_NIGHT_YES);
        int checked = 0;
        for (int i = 0; i < modes.length; i++) {
            if (modes[i] == current) { checked = i; break; }
        }

        new AlertDialog.Builder(this)
            .setTitle(R.string.theme)
            .setSingleChoiceItems(options, checked, (dialog, which) -> {
                prefs.edit().putInt(KEY_THEME, modes[which]).apply();
                AppCompatDelegate.setDefaultNightMode(modes[which]);
                dialog.dismiss();
                updateLabels();
            })
            .show();
    }

    private void showLanguagePicker() {
        String[] options = {getString(R.string.lang_system), getString(R.string.lang_english), getString(R.string.lang_spanish)};
        String[] codes = {"system", "en", "es"};

        String current = prefs.getString(KEY_LANG, "system");
        int checked = 0;
        for (int i = 0; i < codes.length; i++) {
            if (codes[i].equals(current)) { checked = i; break; }
        }

        new AlertDialog.Builder(this)
            .setTitle(R.string.language)
            .setSingleChoiceItems(options, checked, (dialog, which) -> {
                String code = codes[which];
                prefs.edit().putString(KEY_LANG, code).apply();

                LocaleListCompat locales;
                if (code.equals("system")) {
                    locales = LocaleListCompat.getEmptyLocaleList();
                } else {
                    locales = LocaleListCompat.forLanguageTags(code);
                }
                AppCompatDelegate.setApplicationLocales(locales);
                dialog.dismiss();
            })
            .show();
    }

    private void applyTheme(boolean recreateIfNeeded) {
        SharedPreferences p = getSharedPreferences(PREFS, MODE_PRIVATE);
        int mode = p.getInt(KEY_THEME, AppCompatDelegate.MODE_NIGHT_YES);
        AppCompatDelegate.setDefaultNightMode(mode);
    }

    /**
     * Static helper: call at the beginning of each activity's onCreate (before super).
     */
    public static void applyGlobalTheme(android.content.Context ctx) {
        SharedPreferences p = ctx.getSharedPreferences(PREFS, MODE_PRIVATE);
        int mode = p.getInt(KEY_THEME, AppCompatDelegate.MODE_NIGHT_YES);
        AppCompatDelegate.setDefaultNightMode(mode);
    }
}
