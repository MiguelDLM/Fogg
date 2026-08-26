package com.example.dialsender.ble;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.TimeZone;
import java.util.concurrent.CopyOnWriteArrayList;

public class WorldClockManager {

    private static final String PREF_NAME = "dial_sender_prefs";
    private static final String KEY_WORLD_CLOCKS = "world_clocks_json";
    private static final String TAG = "WorldClockManager";
    public static final int MAX_CLOCKS = 8;
    /** One world clock on the wire: flags+id, offset, 2 reserved, 62 bytes of UTF-16LE name. */
    public static final int ITEM_LENGTH = 68;
    public static final int QUARTER_HOUR_MS = 15 * 60 * 1000;

    public interface WorldClockChangeListener {
        void onWorldClocksChanged();
    }

    private static final List<WorldClockChangeListener> listeners = new CopyOnWriteArrayList<>();

    public static void addListener(WorldClockChangeListener listener) {
        if (listener != null && !listeners.contains(listener)) {
            listeners.add(listener);
        }
    }

    public static void removeListener(WorldClockChangeListener listener) {
        if (listener != null) {
            listeners.remove(listener);
        }
    }

    private static void notifyListeners() {
        new Handler(Looper.getMainLooper()).post(() -> {
            for (WorldClockChangeListener l : listeners) {
                try {
                    l.onWorldClocksChanged();
                } catch (Exception e) {
                    Log.e("WorldClockManager", "Error in listener", e);
                }
            }
        });
    }

    public static class WorldClockItem {
        public int id;
        public String cityName;
        public String timeZoneId;
        public boolean isLocal;

        public WorldClockItem(int id, String cityName, String timeZoneId, boolean isLocal) {
            this.id = id;
            this.cityName = cityName;
            this.timeZoneId = timeZoneId;
            this.isLocal = isLocal;
        }

        public int getQuarterHourOffset() {
            TimeZone tz = (timeZoneId != null && !timeZoneId.isEmpty())
                    ? TimeZone.getTimeZone(timeZoneId)
                    : TimeZone.getDefault();
            return tz.getOffset(System.currentTimeMillis()) / QUARTER_HOUR_MS;
        }
    }

    private static final String KEY_WORLD_CLOCKS_INITIALIZED = "world_clocks_initialized";

    public static List<WorldClockItem> getSavedClocks(Context context) {
        SharedPreferences sp = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        List<WorldClockItem> list = new ArrayList<>();
        if (!sp.getBoolean(KEY_WORLD_CLOCKS_INITIALIZED, false)) {
            sp.edit().putBoolean(KEY_WORLD_CLOCKS_INITIALIZED, true).apply();
            // Default presets on fresh install only
            list.add(new WorldClockItem(1, "London", "Europe/London", false));
            list.add(new WorldClockItem(2, "New York", "America/New_York", false));
            list.add(new WorldClockItem(3, "Tokyo", "Asia/Tokyo", false));
            saveClocks(context, list);
            return list;
        }
        String jsonStr = sp.getString(KEY_WORLD_CLOCKS, "[]");
        try {
            JSONArray arr = new JSONArray(jsonStr);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject obj = arr.getJSONObject(i);
                list.add(new WorldClockItem(
                        obj.optInt("id", i + 1),
                        obj.optString("cityName", ""),
                        obj.optString("timeZoneId", "UTC"),
                        obj.optBoolean("isLocal", false)
                ));
            }
        } catch (Exception ignored) {}
        return list;
    }

    public static int getNextAvailableId(List<WorldClockItem> list) {
        if (list == null) return 1;
        for (int candidate = 1; candidate <= MAX_CLOCKS; candidate++) {
            boolean used = false;
            for (WorldClockItem item : list) {
                if (item.id == candidate) {
                    used = true;
                    break;
                }
            }
            if (!used) return candidate;
        }
        return list.size() + 1;
    }

    public static void saveClocks(Context context, List<WorldClockItem> list) {
        SharedPreferences sp = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        JSONArray arr = new JSONArray();
        if (list != null) {
            for (WorldClockItem item : list) {
                JSONObject obj = new JSONObject();
                try {
                    obj.put("id", item.id);
                    obj.put("cityName", item.cityName);
                    obj.put("timeZoneId", item.timeZoneId);
                    obj.put("isLocal", item.isLocal);
                    arr.put(obj);
                } catch (Exception ignored) {}
            }
        }
        sp.edit().putString(KEY_WORLD_CLOCKS, arr.toString()).apply();
        notifyListeners();
    }

    /**
     * Called when the watch notifies that a world clock has been deleted from the watch UI.
     *
     * The id is the one the phone assigned when it pushed the clock, so an exact
     * match is the only safe rule: an earlier version fell back to treating the
     * id as a 1-based position, which silently removed the wrong city whenever
     * the ids had holes in them (delete "2" out of {1,3,4} and the phone dropped
     * the second row, id 3).
     */
    public static void deleteClockById(Context context, int id) {
        List<WorldClockItem> list = getSavedClocks(context);
        Log.d(TAG, "deleteClockById called with id=" + id + ", list size=" + list.size());
        if (id == 0xFF || id == 255) {
            list.clear();
            saveClocks(context, list);
            return;
        }

        boolean removed = false;
        Iterator<WorldClockItem> it = list.iterator();
        while (it.hasNext()) {
            WorldClockItem item = it.next();
            if (item.id == id) {
                it.remove();
                removed = true;
                Log.d(TAG, "Removed clock: " + item.cityName + " (id=" + id + ")");
                break;
            }
        }

        if (removed) {
            saveClocks(context, list);
        } else {
            Log.w(TAG, "Could not find clock with id=" + id + " to delete");
        }
    }

    /**
     * Decode one READ page: a run of 68-byte items, local clock included.
     * The caller decides what to do with them — the watch hands them over one
     * page at a time, so a single page is never the whole list.
     */
    public static List<WorldClockItem> parseItems(byte[] payload) {
        List<WorldClockItem> list = new ArrayList<>();
        if (payload == null || payload.length < ITEM_LENGTH) return list;
        int count = payload.length / ITEM_LENGTH;
        for (int i = 0; i < count; i++) {
            int offset = i * ITEM_LENGTH;
            int b0 = payload[offset] & 0xFF;
            boolean isLocal = ((b0 >> 7) & 0x01) == 1;
            int id = b0 & 0x7F;
            int offsetQuarterHours = payload[offset + 1];
            String cityName = new String(payload, offset + 4, ITEM_LENGTH - 6, StandardCharsets.UTF_16LE)
                    .replace("\0", "").trim();
            WorldClockItem item = new WorldClockItem(id, cityName, quarterHoursToGmtId(offsetQuarterHours), isLocal);
            list.add(item);
        }
        return list;
    }

    private static String quarterHoursToGmtId(int offsetQuarterHours) {
        int minutes = offsetQuarterHours * 15;
        String sign = minutes >= 0 ? "+" : "-";
        int absMin = Math.abs(minutes);
        return String.format(java.util.Locale.US, "GMT%s%02d:%02d", sign, absMin / 60, absMin % 60);
    }

    /**
     * Adopt the list the watch just reported (local clock already filtered out
     * by the caller's paging). The watch only knows a city by name and a raw
     * UTC offset, so the real Olson zone is carried over from the phone's copy
     * whenever the name still matches — otherwise the row would stop following
     * that city's DST.
     */
    public static void applyWatchList(Context context, List<WorldClockItem> watchItems) {
        List<WorldClockItem> existing = getSavedClocks(context);
        List<WorldClockItem> list = new ArrayList<>();
        if (watchItems != null) {
            for (WorldClockItem item : watchItems) {
                if (item.isLocal || item.cityName == null || item.cityName.isEmpty()) continue;
                String tzId = item.timeZoneId;
                for (WorldClockItem old : existing) {
                    if (old.cityName != null && old.cityName.equalsIgnoreCase(item.cityName)) {
                        tzId = old.timeZoneId;
                        break;
                    }
                }
                list.add(new WorldClockItem(item.id, item.cityName, tzId, false));
            }
        }
        Log.d(TAG, "applyWatchList: watch reports " + list.size() + " world clock(s), phone had " + existing.size());
        saveClocks(context, list);
    }

    public static void syncToWatch(Context context) {
        BleManager ble = BleManager.getInstance(context);
        if (!ble.isSessionReady()) return;

        // Reset existing on watch
        ble.resetWorldClocks();

        // 1. Sync Local Clock
        TimeZone localTz = TimeZone.getDefault();
        int localOffsetQ = localTz.getOffset(System.currentTimeMillis()) / QUARTER_HOUR_MS;
        String localName = localTz.getID().replace("_", " ");
        if (localName.contains("/")) {
            localName = localName.substring(localName.lastIndexOf('/') + 1);
        }
        ble.sendWorldClock(0, true, localOffsetQ, localName);

        // 2. Sync World Clocks
        List<WorldClockItem> clocks = getSavedClocks(context);
        for (WorldClockItem item : clocks) {
            ble.sendWorldClock(item.id, false, item.getQuarterHourOffset(), item.cityName);
        }
    }
}
