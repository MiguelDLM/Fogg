package com.example.dialsender;

import android.os.Build;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.example.dialsender.ble.BleManager;
import com.example.dialsender.theme.ThemeManager;

/**
 * Full-screen alert shown when the watch rings the phone (find-phone). Plays via
 * {@link BleManager}'s ringtone; this screen just shows a big "Detener" button
 * and appears over the lock screen.
 */
public class FindPhoneActivity extends AppCompatActivity {

    protected void attachBaseContext(android.content.Context base) {
        super.attachBaseContext(LocaleHelper.wrap(base));
    }

    protected void onCreate(Bundle savedInstanceState) {
        ThemeManager.applyTheme(this);
        super.onCreate(savedInstanceState);
        ThemeManager.AppTheme theme = ThemeManager.getTheme(this);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true);
            setTurnScreenOn(true);
        } else {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                    | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                    | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setBackgroundColor(theme.bgPrimary);
        root.setPadding(dp(32), dp(32), dp(32), dp(32));

        // Was a 📱 emoji, which rendered differently on every launcher font.
        ImageView icon = new ImageView(this);
        icon.setImageResource(R.drawable.ic_vibration);
        icon.setColorFilter(theme.accentPrimary);
        icon.setLayoutParams(new LinearLayout.LayoutParams(dp(88), dp(88)));
        root.addView(icon);

        TextView title = new TextView(this);
        title.setTextAppearance(theme.textScreenTitle);
        title.setText(R.string.findphone_title);
        title.setTextColor(theme.textPrimary);
        title.setGravity(Gravity.CENTER);
        title.setPadding(0, dp(24), 0, dp(48));
        root.addView(title);

        Button stop = new Button(this);
        stop.setText(R.string.findphone_stop);
        stop.setBackground(ThemeManager.createPrimaryButtonDrawable(theme));
        stop.setTextColor(theme.onAccent);
        stop.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        stop.setAllCaps(false);
        stop.setStateListAnimator(null);
        stop.setOnClickListener(v -> {
            BleManager.getInstance(this).stopFindPhoneAlert();
            finish();
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(220), dp(64));
        stop.setLayoutParams(lp);
        root.addView(stop);

        setContentView(root);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // If the alert was already stopped (e.g. via the notification), close.
        if (!BleManager.getInstance(this).isFindPhoneActive())
            finish();
    }

    private int dp(int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }
}
