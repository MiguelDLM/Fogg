package com.example.dialsender.fragments;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.RadioGroup;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import com.example.dialsender.DeveloperToolsActivity;
import com.example.dialsender.LocaleHelper;
import com.example.dialsender.NotificationSettingsActivity;
import com.example.dialsender.R;
import com.example.dialsender.ble.BleManager;
import android.widget.RadioButton;

public class SettingsFragment extends Fragment {

    public static final String ACTION_GAUGE_STYLE_CHANGED = "com.example.dialsender.GAUGE_STYLE_CHANGED";
    private static final String PREF_NAME = "dial_sender_prefs";

    private SharedPreferences prefs;
    private boolean initializing = false;

    private ImageView imgProfile;
    private TextView txtProfileName;

    // Image picker that returns a persistable URI for the profile photo.
    private final ActivityResultLauncher<String[]> pickPhoto =
            registerForActivityResult(new ActivityResultContracts.OpenDocument(), uri -> {
                if (uri == null)
                    return;
                try {
                    requireContext().getContentResolver().takePersistableUriPermission(
                            uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
                } catch (Exception ignored) {
                }
                prefs.edit().putString("profile_photo_uri", uri.toString()).apply();
                loadProfilePhoto();
            });

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_settings, container, false);
        prefs = requireContext().getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);

        // Profile header — photo, editable name, bound device
        imgProfile = view.findViewById(R.id.imgProfile);
        txtProfileName = view.findViewById(R.id.txtProfileName);
        TextView profileDevice = view.findViewById(R.id.txtProfileDevice);
        if (profileDevice != null) {
            String addr = com.example.dialsender.ble.BleManager.getInstance(requireContext())
                    .getLastDeviceAddress();
            profileDevice.setText(addr != null ? getString(R.string.device_bound_fmt, addr) : getString(R.string.settings_no_device));
        }
        if (txtProfileName != null)
            txtProfileName.setText(prefs.getString("profile_name", getString(R.string.profile_default_name)));
        loadProfilePhoto();

        View profileCard = view.findViewById(R.id.profileCard);
        if (profileCard != null)
            profileCard.setOnClickListener(v -> editProfile());

        // Version footer — 6 consecutive taps unlock developer tools (Android-style)
        TextView txtVersion = view.findViewById(R.id.txtVersion);
        if (txtVersion != null) {
            txtVersion.setText(getString(R.string.settings_version));
            txtVersion.setOnClickListener(new View.OnClickListener() {
                int taps = 0;
                long last = 0;
                @Override
                public void onClick(View v) {
                    long now = System.currentTimeMillis();
                    taps = (now - last < 2000) ? taps + 1 : 1;
                    last = now;
                    if (taps >= 6) {
                        taps = 0;
                        startActivity(new Intent(requireContext(), DeveloperToolsActivity.class));
                    } else if (taps >= 3) {
                        Toast.makeText(requireContext(),
                                getString(R.string.dev_tools_unlock_countdown, (6 - taps)),
                                Toast.LENGTH_SHORT).show();
                    }
                }
            });
        }

        // Import data from STF / CoFit
        View rowImport = view.findViewById(R.id.rowImportData);
        if (rowImport != null)
            rowImport.setOnClickListener(v ->
                    startActivity(new Intent(requireContext(), com.example.dialsender.ImportDataActivity.class)));

        // Export health data as CSV
        View rowExport = view.findViewById(R.id.rowExportData);
        if (rowExport != null)
            rowExport.setOnClickListener(v ->
                    startActivity(new Intent(requireContext(), com.example.dialsender.ExportDataActivity.class)));

        // Language selector
        RadioGroup radioLang = view.findViewById(R.id.radioLanguage);
        String currentLang = LocaleHelper.getSavedLanguage(requireContext());
        initializing = true;
        if ("en".equals(currentLang)) radioLang.check(R.id.radioLangEn);
        else                          radioLang.check(R.id.radioLangEs);
        initializing = false;
        radioLang.setOnCheckedChangeListener((group, checkedId) -> {
            if (initializing) return;
            String lang = (checkedId == R.id.radioLangEn) ? "en" : "es";
            LocaleHelper.saveLanguage(requireContext(), lang);
            requireActivity().recreate();
        });

        // Gauge style radio group
        RadioGroup radioGauge = view.findViewById(R.id.radioGaugeStyle);
        String currentStyle = prefs.getString("gauge_style", "B");
        initializing = true;
        if ("B".equals(currentStyle))      radioGauge.check(R.id.radioGaugeB);
        else if ("C".equals(currentStyle)) radioGauge.check(R.id.radioGaugeC);
        else                               radioGauge.check(R.id.radioGaugeA);
        initializing = false;

        radioGauge.setOnCheckedChangeListener((group, checkedId) -> {
            if (initializing) return;
            String style;
            if (checkedId == R.id.radioGaugeB)      style = "B";
            else if (checkedId == R.id.radioGaugeC) style = "C";
            else                                     style = "A";
            prefs.edit().putString("gauge_style", style).apply();
            LocalBroadcastManager.getInstance(requireContext())
                    .sendBroadcast(new Intent(ACTION_GAUGE_STYLE_CHANGED));
        });

        // Distance unit spinner
        Spinner spinnerDist = view.findViewById(R.id.spinnerDistance);
        ArrayAdapter<String> distAdapter = new ArrayAdapter<>(requireContext(),
                android.R.layout.simple_spinner_dropdown_item, new String[]{"km", getString(R.string.unit_miles)});
        spinnerDist.setAdapter(distAdapter);
        spinnerDist.setSelection("mi".equals(prefs.getString("unit_distance", "km")) ? 1 : 0);
        spinnerDist.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            boolean first = true;
            @Override
            public void onItemSelected(AdapterView<?> parent, View v, int position, long id) {
                if (first) { first = false; return; }
                String unit = position == 1 ? "mi" : "km";
                prefs.edit().putString("unit_distance", unit).apply();
                TextView lbl = getView() != null ? getView().findViewById(R.id.lblDistanceUnit) : null;
                if (lbl != null) lbl.setText("  " + unit);
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });

        // Weight unit spinner
        Spinner spinnerWeight = view.findViewById(R.id.spinnerWeight);
        ArrayAdapter<String> weightAdapter = new ArrayAdapter<>(requireContext(),
                android.R.layout.simple_spinner_dropdown_item, new String[]{"kg", getString(R.string.unit_pounds)});
        spinnerWeight.setAdapter(weightAdapter);
        spinnerWeight.setSelection("lb".equals(prefs.getString("unit_weight", "kg")) ? 1 : 0);
        spinnerWeight.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            boolean first = true;
            @Override
            public void onItemSelected(AdapterView<?> parent, View v, int position, long id) {
                if (first) { first = false; return; }
                prefs.edit().putString("unit_weight", position == 1 ? "lb" : "kg").apply();
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });

        // Goal fields (save on focus lost)
        // Only the step goal has a verified write encoding (STEP_GOAL 0x0207,
        // int32 BE). The original app reads the calorie/distance/sleep goals
        // from the watch but never writes them, so those three stay phone-side
        // until their payloads can be confirmed rather than guessed.
        setupGoalField(view, R.id.etGoalSteps,    "goal_steps",     10000, true);
        setupGoalField(view, R.id.etGoalSleep,    "goal_sleep_min", 480, false);
        setupGoalField(view, R.id.etGoalCalories, "goal_calories",  500, false);
        setupGoalField(view, R.id.etGoalDistance, "goal_distance",  5, false);

        // Distance unit label initial state
        TextView lblDist = view.findViewById(R.id.lblDistanceUnit);
        lblDist.setText("  " + prefs.getString("unit_distance", "km"));

        return view;
    }

    /** Load the saved profile photo into the avatar (or keep the default icon). */
    private void loadProfilePhoto() {
        if (imgProfile == null)
            return;
        String uriStr = prefs.getString("profile_photo_uri", null);
        if (uriStr == null) {
            imgProfile.setImageResource(R.drawable.ic_nav_me);
            imgProfile.setColorFilter(getResources().getColor(R.color.accent_primary));
            imgProfile.setPadding(dp(14), dp(14), dp(14), dp(14));
            return;
        }
        try {
            android.graphics.Bitmap bmp = android.graphics.BitmapFactory.decodeStream(
                    requireContext().getContentResolver().openInputStream(Uri.parse(uriStr)));
            if (bmp != null) {
                imgProfile.clearColorFilter();
                imgProfile.setPadding(0, 0, 0, 0);
                imgProfile.setImageBitmap(circularBitmap(bmp));
                return;
            }
        } catch (Exception ignored) {
        }
        imgProfile.setImageResource(R.drawable.ic_nav_me);
        imgProfile.setColorFilter(getResources().getColor(R.color.accent_primary));
    }

    /** Crop a bitmap into a circle so the avatar reads as a profile photo. */
    private android.graphics.Bitmap circularBitmap(android.graphics.Bitmap src) {
        int size = Math.min(src.getWidth(), src.getHeight());
        android.graphics.Bitmap sq = android.graphics.Bitmap.createBitmap(src,
                (src.getWidth() - size) / 2, (src.getHeight() - size) / 2, size, size);
        android.graphics.Bitmap out = android.graphics.Bitmap.createBitmap(size, size,
                android.graphics.Bitmap.Config.ARGB_8888);
        android.graphics.Canvas c = new android.graphics.Canvas(out);
        android.graphics.Paint p = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        c.drawCircle(size / 2f, size / 2f, size / 2f, p);
        p.setXfermode(new android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.SRC_IN));
        c.drawBitmap(sq, 0, 0, p);
        return out;
    }

    /** Dialog to change the profile name and pick a photo. */
    private void editProfile() {
        android.widget.LinearLayout box = new android.widget.LinearLayout(requireContext());
        box.setOrientation(android.widget.LinearLayout.VERTICAL);
        int pad = dp(20);
        box.setPadding(pad, pad, pad, 0);

        final EditText etName = new EditText(requireContext());
        etName.setHint(getString(R.string.profile_name_hint));
        etName.setText(prefs.getString("profile_name", ""));
        box.addView(etName);

        // Sex, age, height and weight are what the watch needs to compute
        // calories and stride-based distance on-device (USER_PROFILE 0x0206).
        final RadioGroup rgSex = new RadioGroup(requireContext());
        rgSex.setOrientation(RadioGroup.HORIZONTAL);
        RadioButton rbMale = new RadioButton(requireContext());
        rbMale.setId(View.generateViewId());
        rbMale.setText(getString(R.string.profile_male));
        RadioButton rbFemale = new RadioButton(requireContext());
        rbFemale.setId(View.generateViewId());
        rbFemale.setText(getString(R.string.profile_female));
        rgSex.addView(rbMale);
        rgSex.addView(rbFemale);
        boolean isFemale = prefs.getInt("profile_gender", BleManager.GENDER_MALE) == BleManager.GENDER_FEMALE;
        rgSex.check(isFemale ? rbFemale.getId() : rbMale.getId());
        box.addView(labelled(getString(R.string.profile_sex)));
        box.addView(rgSex);

        final EditText etAge = numberField(getString(R.string.profile_age),
                String.valueOf(prefs.getInt("profile_age", 30)), box);
        final EditText etHeight = numberField(getString(R.string.profile_height_cm),
                trimFloat(prefs.getFloat("profile_height_cm", 170f)), box);
        final EditText etWeight = numberField(getString(R.string.profile_weight_kg),
                trimFloat(prefs.getFloat("profile_weight_kg", 70f)), box);

        new androidx.appcompat.app.AlertDialog.Builder(requireContext())
                .setTitle(getString(R.string.profile_title))
                .setView(box)
                .setNeutralButton(getString(R.string.profile_change_photo), (d, w) ->
                        pickPhoto.launch(new String[] { "image/*" }))
                .setPositiveButton(getString(R.string.action_save), (d, w) -> {
                    String name = etName.getText().toString().trim();
                    String fallback = getString(R.string.profile_default_name);
                    SharedPreferences.Editor e = prefs.edit();
                    e.putString("profile_name", name.isEmpty() ? fallback : name);
                    e.putInt("profile_gender", rgSex.getCheckedRadioButtonId() == rbFemale.getId()
                            ? BleManager.GENDER_FEMALE : BleManager.GENDER_MALE);
                    // An unparseable or blank field keeps the stored value
                    // rather than resetting the user to a default.
                    e.putInt("profile_age", parseInt(etAge, prefs.getInt("profile_age", 30)));
                    e.putFloat("profile_height_cm",
                            parseFloat(etHeight, prefs.getFloat("profile_height_cm", 170f)));
                    e.putFloat("profile_weight_kg",
                            parseFloat(etWeight, prefs.getFloat("profile_weight_kg", 70f)));
                    e.apply();
                    if (txtProfileName != null)
                        txtProfileName.setText(name.isEmpty() ? fallback : name);
                    pushProfileToWatch();
                })
                .setNegativeButton(getString(R.string.action_cancel), null)
                .show();
    }

    private TextView labelled(String text) {
        TextView tv = new TextView(requireContext());
        tv.setText(text);
        tv.setPadding(0, dp(12), 0, 0);
        return tv;
    }

    /**
     * Labelled numeric field. The label is a separate view rather than the
     * hint: these fields always start populated, and a hint on a non-empty
     * EditText is invisible, leaving three unlabelled numbers.
     */
    private EditText numberField(String label, String value, ViewGroup parent) {
        parent.addView(labelled(label));
        EditText et = new EditText(requireContext());
        et.setText(value);
        et.setInputType(android.text.InputType.TYPE_CLASS_NUMBER
                | android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL);
        parent.addView(et);
        return et;
    }

    private static int parseInt(EditText et, int fallback) {
        try {
            return Integer.parseInt(et.getText().toString().trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static float parseFloat(EditText et, float fallback) {
        try {
            return Float.parseFloat(et.getText().toString().trim().replace(',', '.'));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /** 170.0 -> "170", 70.5 -> "70.5". */
    private static String trimFloat(float v) {
        return v == Math.rint(v) ? String.valueOf((int) v) : String.valueOf(v);
    }

    private int dp(int v) {
        return (int) android.util.TypedValue.applyDimension(
                android.util.TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }

    private void setupGoalField(View root, int editTextId, String prefKey, int defaultVal,
                                boolean pushToWatch) {
        EditText et = root.findViewById(editTextId);
        et.setText(String.valueOf(prefs.getInt(prefKey, defaultVal)));
        et.setOnFocusChangeListener((v, hasFocus) -> {
            if (!hasFocus) {
                try {
                    int val = Integer.parseInt(et.getText().toString().trim());
                    boolean changed = val != prefs.getInt(prefKey, defaultVal);
                    prefs.edit().putInt(prefKey, val).apply();
                    if (changed && pushToWatch)
                        pushProfileToWatch();
                } catch (NumberFormatException ignored) {
                    et.setText(String.valueOf(prefs.getInt(prefKey, defaultVal)));
                }
            }
        });
    }

    /**
     * Send profile and step goal to the watch. Does nothing when no session is
     * up — the values are in prefs and go out on the next connect.
     */
    private void pushProfileToWatch() {
        BleManager.getInstance(requireContext().getApplicationContext())
                .syncUserProfileAndGoals();
    }
}
