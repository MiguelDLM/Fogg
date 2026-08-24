package com.example.dialsender;

import android.os.Bundle;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.TimePicker;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.dialsender.ble.BleAlarm;
import com.example.dialsender.ble.BleManager;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.android.material.switchmaterial.SwitchMaterial;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;

/**
 * The watch's alarm list (ALARM 0x0210).
 *
 * The watch is the source of truth: every edit goes out over BLE and the list
 * is redrawn from what comes back, so a write the watch rejects (its slots are
 * full, say) does not leave a phantom row on the phone. The cached copy is only
 * there to fill the screen before the first reply arrives.
 */
public class AlarmsActivity extends AppCompatActivity implements BleManager.AlarmListener {

    private BleManager ble;
    private final List<BleAlarm> alarms = new ArrayList<>();
    private AlarmAdapter adapter;
    private RecyclerView list;
    private View empty;
    private TextView hint;

    @Override
    protected void attachBaseContext(android.content.Context base) {
        super.attachBaseContext(LocaleHelper.wrap(base));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        com.example.dialsender.theme.ThemeManager.applyTheme(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_alarms);
        com.example.dialsender.views.FoggToolbar.attach(this, R.string.alarms_title);

        ble = BleManager.getInstance(this);

        list = findViewById(R.id.rvAlarms);
        empty = findViewById(R.id.tvAlarmsEmpty);
        hint = findViewById(R.id.tvAlarmsHint);
        hint.setText(getString(R.string.alarms_hint, BleManager.MAX_ALARMS));

        adapter = new AlarmAdapter();
        list.setLayoutManager(new LinearLayoutManager(this));
        list.setAdapter(adapter);

        FloatingActionButton fab = findViewById(R.id.fabAddAlarm);
        fab.setOnClickListener(v -> {
            if (!requireWatch())
                return;
            if (alarms.size() >= BleManager.MAX_ALARMS) {
                toast(getString(R.string.alarms_full, BleManager.MAX_ALARMS));
                return;
            }
            edit(null);
        });

        alarms.addAll(ble.getCachedAlarms());
        render();
    }

    @Override
    protected void onStart() {
        super.onStart();
        ble.setAlarmListener(this);
        // Re-read on every entry: the alarms can also be changed on the watch.
        ble.readAlarms();
    }

    @Override
    protected void onStop() {
        super.onStop();
        ble.setAlarmListener(null);
    }

    @Override
    public void onAlarmsChanged(List<BleAlarm> fromWatch) {
        alarms.clear();
        alarms.addAll(fromWatch);
        render();
    }

    private void render() {
        adapter.notifyDataSetChanged();
        boolean none = alarms.isEmpty();
        empty.setVisibility(none ? View.VISIBLE : View.GONE);
        list.setVisibility(none ? View.GONE : View.VISIBLE);
    }

    private boolean requireWatch() {
        if (ble.isSessionReady())
            return true;
        toast(getString(R.string.alarms_not_connected));
        return false;
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    // ---- edit dialog ----

    /** @param existing null to create a new alarm */
    private void edit(final BleAlarm existing) {
        View v = LayoutInflater.from(this).inflate(R.layout.dialog_alarm_edit, null);
        final TimePicker picker = v.findViewById(R.id.timePickerAlarm);
        final EditText label = v.findViewById(R.id.etAlarmLabel);
        final LinearLayout days = v.findViewById(R.id.rowAlarmDays);

        picker.setIs24HourView(android.text.format.DateFormat.is24HourFormat(this));

        int hour, minute, repeat;
        if (existing != null) {
            hour = existing.hour;
            minute = existing.minute;
            repeat = existing.repeat;
            label.setText(existing.tag);
        } else {
            Calendar now = Calendar.getInstance();
            hour = now.get(Calendar.HOUR_OF_DAY);
            minute = now.get(Calendar.MINUTE);
            repeat = BleAlarm.ONCE;
        }
        setTime(picker, hour, minute);

        final boolean[] picked = new boolean[7];
        final int[] masks = { BleAlarm.MONDAY, BleAlarm.TUESDAY, BleAlarm.WEDNESDAY,
                BleAlarm.THURSDAY, BleAlarm.FRIDAY, BleAlarm.SATURDAY, BleAlarm.SUNDAY };
        String[] names = getResources().getStringArray(R.array.weekday_initials);
        for (int i = 0; i < 7; i++) {
            picked[i] = (repeat & masks[i]) != 0;
            final int index = i;
            final TextView chip = new TextView(this);
            chip.setText(names[i]);
            chip.setGravity(android.view.Gravity.CENTER);
            chip.setPadding(0, 20, 0, 20);
            LinearLayout.LayoutParams lp =
                    new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            chip.setLayoutParams(lp);
            paintChip(chip, picked[index]);
            chip.setOnClickListener(c -> {
                picked[index] = !picked[index];
                paintChip(chip, picked[index]);
            });
            days.addView(chip);
        }

        AlertDialog.Builder b = new AlertDialog.Builder(this)
                .setTitle(existing == null ? R.string.alarm_add : R.string.alarm_edit)
                .setView(v)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.save, (d, w) -> {
                    if (!requireWatch())
                        return;
                    BleAlarm a = existing != null ? existing : new BleAlarm();
                    a.hour = getHour(picker);
                    a.minute = getMinute(picker);
                    a.enabled = true;
                    a.repeat = 0;
                    for (int i = 0; i < 7; i++)
                        if (picked[i])
                            a.repeat |= masks[i];
                    a.tag = label.getText().toString().trim();

                    if (existing == null)
                        ble.createAlarm(a);
                    else
                        ble.updateAlarm(a);
                });

        if (existing != null) {
            b.setNeutralButton(R.string.delete, (d, w) -> {
                if (requireWatch())
                    ble.deleteAlarm(existing.id);
            });
        }
        b.show();
    }

    private void paintChip(TextView chip, boolean on) {
        com.example.dialsender.theme.ThemeManager.AppTheme theme =
                com.example.dialsender.theme.ThemeManager.getTheme(this);
        chip.setTextColor(on ? theme.accentPrimary : theme.textSecondary);
        chip.setAlpha(on ? 1f : 0.6f);
    }

    @SuppressWarnings("deprecation")
    private static void setTime(TimePicker p, int hour, int minute) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            p.setHour(hour);
            p.setMinute(minute);
        } else {
            p.setCurrentHour(hour);
            p.setCurrentMinute(minute);
        }
    }

    @SuppressWarnings("deprecation")
    private static int getHour(TimePicker p) {
        return android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M
                ? p.getHour() : p.getCurrentHour();
    }

    @SuppressWarnings("deprecation")
    private static int getMinute(TimePicker p) {
        return android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M
                ? p.getMinute() : p.getCurrentMinute();
    }

    /** "Mon, Wed, Fri", or the date for a one-shot. */
    private String describeRepeat(BleAlarm a) {
        if (a.isRecurring()) {
            if ((a.repeat & 0x7F) == BleAlarm.EVERYDAY)
                return getString(R.string.repeat_everyday);
            if ((a.repeat & 0x7F) == BleAlarm.WORKDAY)
                return getString(R.string.repeat_workdays);
            if ((a.repeat & 0x7F) == BleAlarm.WEEKEND)
                return getString(R.string.repeat_weekend);

            String[] names = getResources().getStringArray(R.array.weekday_short);
            int[] masks = { BleAlarm.MONDAY, BleAlarm.TUESDAY, BleAlarm.WEDNESDAY,
                    BleAlarm.THURSDAY, BleAlarm.FRIDAY, BleAlarm.SATURDAY, BleAlarm.SUNDAY };
            List<String> parts = new ArrayList<>();
            for (int i = 0; i < 7; i++)
                if ((a.repeat & masks[i]) != 0)
                    parts.add(names[i]);
            return TextUtils.join(", ", parts);
        }
        if (a.year >= 2000 && a.month >= 1 && a.day >= 1)
            return String.format(Locale.getDefault(), "%02d/%02d/%04d", a.day, a.month, a.year);
        return getString(R.string.repeat_once);
    }

    // ---- adapter ----

    private class AlarmAdapter extends RecyclerView.Adapter<AlarmAdapter.Holder> {

        class Holder extends RecyclerView.ViewHolder {
            final TextView time;
            final TextView repeat;
            final SwitchMaterial toggle;

            Holder(View v) {
                super(v);
                time = v.findViewById(R.id.tvAlarmTime);
                repeat = v.findViewById(R.id.tvAlarmRepeat);
                toggle = v.findViewById(R.id.switchAlarm);
            }
        }

        @NonNull
        @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new Holder(LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_alarm, parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull Holder h, int position) {
            BleAlarm a = alarms.get(position);
            h.time.setText(String.format(Locale.getDefault(), "%02d:%02d", a.hour, a.minute));
            String sub = describeRepeat(a);
            if (!a.tag.isEmpty())
                sub = a.tag + " · " + sub;
            h.repeat.setText(sub);
            h.itemView.setAlpha(a.enabled ? 1f : 0.5f);

            // Rebinding must not fire the listener, or scrolling would push
            // spurious updates to the watch.
            h.toggle.setOnCheckedChangeListener(null);
            h.toggle.setChecked(a.enabled);
            h.toggle.setOnCheckedChangeListener((btn, checked) -> {
                if (!requireWatch()) {
                    btn.setChecked(!checked);
                    return;
                }
                a.enabled = checked;
                h.itemView.setAlpha(checked ? 1f : 0.5f);
                ble.updateAlarm(a);
            });

            h.itemView.setOnClickListener(v -> edit(a));
        }

        @Override
        public int getItemCount() {
            return alarms.size();
        }
    }
}
