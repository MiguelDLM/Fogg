package com.example.dialsender;

import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.provider.Settings;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.NotificationManagerCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.switchmaterial.SwitchMaterial;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class NotificationSettingsActivity extends AppCompatActivity {

    private static final String PREF_NAME = "dial_sender_prefs";
    private static final String PREF_NOTIF_PACKAGES = "notif_enabled_packages";

    private SharedPreferences prefs;
    private Set<String> enabledPackages;
    private PackageManager pm;

    protected void attachBaseContext(android.content.Context base) {
        super.attachBaseContext(LocaleHelper.wrap(base));
    }

    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_notification_settings);
        if (getSupportActionBar() != null)
            getSupportActionBar().setTitle(R.string.notifications_title);

        pm = getPackageManager();
        prefs = getSharedPreferences(PREF_NAME, MODE_PRIVATE);
        String saved = prefs.getString(PREF_NOTIF_PACKAGES, "");
        enabledPackages = new HashSet<>(Arrays.asList(saved.split(",")));
        enabledPackages.remove(""); // clean up empty string from split

        // Permission banner
        LinearLayout banner = findViewById(R.id.bannerPermission);
        boolean hasPermission = isNotificationListenerEnabled();
        banner.setVisibility(hasPermission ? View.GONE : View.VISIBLE);
        findViewById(R.id.btnGrantPermission)
                .setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)));

        // Load ALL installed apps that are not pure system apps (no launcher icon)
        List<ApplicationInfo> allApps = pm.getInstalledApplications(PackageManager.GET_META_DATA);
        List<ApplicationInfo> userApps = new ArrayList<>();
        for (ApplicationInfo ai : allApps) {
            // Include: non-system apps OR system apps that have a launchable intent
            boolean isSystem = (ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
            if (!isSystem) {
                userApps.add(ai);
            } else {
                // Also include system apps that have a launcher (like Phone, Messages)
                Intent launchIntent = pm.getLaunchIntentForPackage(ai.packageName);
                if (launchIntent != null) {
                    userApps.add(ai);
                }
            }
        }
        // Sort alphabetically
        userApps.sort((a, b) -> {
            String nameA = pm.getApplicationLabel(a).toString();
            String nameB = pm.getApplicationLabel(b).toString();
            return nameA.compareToIgnoreCase(nameB);
        });

        RecyclerView rv = findViewById(R.id.rvApps);
        rv.setLayoutManager(new LinearLayoutManager(this));
        rv.setAdapter(new AppAdapter(userApps));
    }

    @Override
    protected void onResume() {
        super.onResume();
        boolean hasPermission = isNotificationListenerEnabled();
        findViewById(R.id.bannerPermission).setVisibility(hasPermission ? View.GONE : View.VISIBLE);
    }

    private boolean isNotificationListenerEnabled() {
        Set<String> enabled = NotificationManagerCompat.getEnabledListenerPackages(this);
        return enabled.contains(getPackageName());
    }

    private void saveEnabledPackages() {
        prefs.edit().putString(PREF_NOTIF_PACKAGES, String.join(",", enabledPackages)).apply();
    }

    class AppAdapter extends RecyclerView.Adapter<AppAdapter.VH> {
        private final List<ApplicationInfo> apps;

        AppAdapter(List<ApplicationInfo> apps) {
            this.apps = apps;
        }

        @Override
        public VH onCreateViewHolder(ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_app_notif, parent, false);
            return new VH(v);
        }

        @Override
        public void onBindViewHolder(VH h, int pos) {
            ApplicationInfo ai = apps.get(pos);
            h.tvName.setText(pm.getApplicationLabel(ai));
            h.imgIcon.setImageDrawable(pm.getApplicationIcon(ai));
            h.sw.setOnCheckedChangeListener(null);
            h.sw.setChecked(enabledPackages.contains(ai.packageName));
            h.sw.setOnCheckedChangeListener((btn, checked) -> {
                if (checked)
                    enabledPackages.add(ai.packageName);
                else
                    enabledPackages.remove(ai.packageName);
                saveEnabledPackages();
            });
        }

        @Override
        public int getItemCount() {
            return apps.size();
        }

        class VH extends RecyclerView.ViewHolder {
            ImageView imgIcon;
            TextView tvName;
            SwitchMaterial sw;

            VH(View v) {
                super(v);
                imgIcon = v.findViewById(R.id.imgAppIcon);
                tvName = v.findViewById(R.id.tvAppName);
                sw = v.findViewById(R.id.switchEnabled);
            }
        }
    }
}
