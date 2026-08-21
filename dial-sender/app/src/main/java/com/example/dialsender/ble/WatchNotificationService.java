package com.example.dialsender.ble;

import android.app.Notification;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

import androidx.core.app.NotificationCompat;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

public class WatchNotificationService extends NotificationListenerService {

    /** Follow the language chosen in the app, not the system one. */
    @Override
    protected void attachBaseContext(android.content.Context base) {
        super.attachBaseContext(com.example.dialsender.LocaleHelper.wrap(base));
    }


    private static final String PREF_NAME = "dial_sender_prefs";
    private static final String PREF_NOTIF_PACKAGES = "notif_enabled_packages";
    private static final int CATEGORY_CALL = 1;
    private static final int CATEGORY_MESSAGE = 127; // 0x7F = generic message category in watch firmware

    private SharedPreferences prefs;
    private Set<String> enabledPackages = new HashSet<>();
    private SharedPreferences.OnSharedPreferenceChangeListener prefListener;

    @Override
    public void onListenerConnected() {
        super.onListenerConnected();
        prefs = getSharedPreferences(PREF_NAME, MODE_PRIVATE);
        reloadPackages();
        prefListener = (p, key) -> {
            if (PREF_NOTIF_PACKAGES.equals(key)) reloadPackages();
        };
        prefs.registerOnSharedPreferenceChangeListener(prefListener);
    }

    @Override
    public void onListenerDisconnected() {
        super.onListenerDisconnected();
        if (prefs != null && prefListener != null) {
            prefs.unregisterOnSharedPreferenceChangeListener(prefListener);
        }
    }

    private void reloadPackages() {
        String saved = prefs.getString(PREF_NOTIF_PACKAGES, "");
        enabledPackages = new HashSet<>(Arrays.asList(saved.split(",")));
        enabledPackages.remove("");
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        if (sbn == null) return;

        BleManager ble = BleManager.getInstance(getApplicationContext());
        if (!ble.isSessionReady()) return;

        String pkg = sbn.getPackageName();

        // Skip our own notifications
        if (pkg.equals(getPackageName())) return;

        // Check whitelist
        if (!enabledPackages.contains(pkg)) return;

        // Skip ongoing/non-clearable notifications (service, media, navigation)
        if (!sbn.isClearable()) return;

        // Skip group summary notifications (no real content)
        if ((sbn.getNotification().flags & Notification.FLAG_GROUP_SUMMARY) != 0) return;

        Notification notif = sbn.getNotification();
        Bundle extras = notif.extras;

        String title = extras.getString(NotificationCompat.EXTRA_TITLE, "");
        CharSequence bigText = extras.getCharSequence(NotificationCompat.EXTRA_BIG_TEXT);
        String content = bigText != null ? bigText.toString()
                : extras.getString(NotificationCompat.EXTRA_TEXT, "");

        int category = Notification.CATEGORY_CALL.equals(notif.category) ? CATEGORY_CALL : CATEGORY_MESSAGE;

        ble.sendNotification(category, title, content, pkg);
    }
}
