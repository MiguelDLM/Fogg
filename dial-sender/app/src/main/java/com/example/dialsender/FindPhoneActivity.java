package com.example.dialsender;

import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.example.dialsender.ble.BleManager;

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
        super.onCreate(savedInstanceState);

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
        root.setBackgroundColor(0xFF0E1116);
        root.setPadding(dp(32), dp(32), dp(32), dp(32));

        TextView icon = new TextView(this);
        icon.setText("📱");
        icon.setTextSize(TypedValue.COMPLEX_UNIT_SP, 72);
        icon.setGravity(Gravity.CENTER);
        root.addView(icon);

        TextView title = new TextView(this);
        title.setText(R.string.findphone_title);
        title.setTextColor(Color.WHITE);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        title.setGravity(Gravity.CENTER);
        title.setPadding(0, dp(24), 0, dp(48));
        root.addView(title);

        Button stop = new Button(this);
        stop.setText(R.string.findphone_stop);
        stop.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        stop.setAllCaps(false);
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
