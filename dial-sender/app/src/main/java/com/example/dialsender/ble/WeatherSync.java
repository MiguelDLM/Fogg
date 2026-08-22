package com.example.dialsender.ble;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.Address;
import android.location.Geocoder;
import android.location.Location;
import android.location.LocationManager;
import android.util.Log;

import androidx.core.content.ContextCompat;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Fetches current weather + a short forecast from Open-Meteo (free, no API key)
 * for the phone's last known location and pushes it to the watch via
 * {@link BleManager#sendWeather}.
 *
 * Runs entirely on a background thread. If location is unavailable it falls back
 * to a default coordinate so the feature still demonstrates end-to-end.
 *
 * NOTE: the watch-side weather byte layout used by {@link BleManager#sendWeather}
 * is not yet verified against a real capture (see the warning there). The
 * condition-code mapping below converts WMO weather codes to a small icon set;
 * adjust both once the firmware's expected codes are confirmed.
 */
public final class WeatherSync {
    private static final String TAG = "WeatherSync";

    // Fallback location (Mérida, MX) used only when no fix is available.
    private static final double FALLBACK_LAT = 20.97;
    private static final double FALLBACK_LON = -89.62;

    private WeatherSync() {
    }

    /**
     * Fetch + push weather on a background thread. Safe to call any time.
     * The forecast is always fetched and cached for the app UI; it is pushed
     * to the watch only when a BLE session is ready.
     */
    public static void syncIfPossible(Context context, BleManager ble) {
        final Context appCtx = context.getApplicationContext();
        new Thread(() -> {
            double[] loc = currentLocation(appCtx);
            // Retry a few times: right after launch the network/DNS may not be
            // ready yet (observed transient "Unable to resolve host").
            for (int attempt = 1; attempt <= 4; attempt++) {
                try {
                    List<BleManager.WeatherDay> days = fetch(loc[0], loc[1]);
                    if (!days.isEmpty()) {
                        String city = resolveCity(appCtx, loc[0], loc[1]);
                        if (ble != null && ble.isSessionReady())
                            ble.sendWeather(days, city);
                        // Cache today's weather + a short forecast so the UI can
                        // show a chip and a full detail screen.
                        BleManager.WeatherDay t = days.get(0);
                        StringBuilder fc = new StringBuilder();
                        for (int i = 0; i < days.size(); i++) {
                            BleManager.WeatherDay d = days.get(i);
                            if (i > 0)
                                fc.append(";");
                            // code|hi|lo|pop
                            fc.append(d.conditionCode).append("|").append(d.tempHigh)
                                    .append("|").append(d.tempLow).append("|").append(d.precipitation);
                        }
                        appCtx.getSharedPreferences("dial_sender_prefs", Context.MODE_PRIVATE).edit()
                                .putInt("weather_temp", t.tempCurrent)
                                .putInt("weather_code", t.conditionCode)
                                .putInt("weather_hi", t.tempHigh)
                                .putInt("weather_lo", t.tempLow)
                                .putInt("weather_humidity", t.humidity)
                                .putInt("weather_wind", t.windSpeed)
                                .putInt("weather_uv", t.uvIndex)
                                .putInt("weather_pop", t.precipitation)
                                .putString("weather_city", city)
                                .putString("weather_forecast", fc.toString())
                                .putFloat("weather_lat", (float) loc[0])
                                .putFloat("weather_lon", (float) loc[1])
                                .putLong("weather_time", System.currentTimeMillis())
                                .apply();
                        Log.i(TAG, "weather pushed (" + days.size() + " days)");
                        return;
                    }
                } catch (Exception e) {
                    Log.w(TAG, "weather attempt " + attempt + " failed: " + e.getMessage());
                }
                try { Thread.sleep(5000L); } catch (InterruptedException ie) { return; }
            }
        }, "weather-sync").start();
    }

    private static boolean hasLocationPermission(Context ctx) {
        return ContextCompat.checkSelfPermission(ctx,
                android.Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                || ContextCompat.checkSelfPermission(ctx,
                        android.Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    /**
     * Resolves the location to use for the forecast, most reliable first:
     * 1. a fresh single-shot GPS/network fix (so we never stay stuck on a stale
     *    or IP/VPN-derived position),
     * 2. the most recent last-known fix across providers,
     * 3. the coordinates of the last successful weather fetch,
     * 4. a hardcoded fallback.
     */
    private static double[] currentLocation(Context ctx) {
        try {
            if (hasLocationPermission(ctx)) {
                Location fresh = requestFreshFix(ctx);
                if (fresh != null) {
                    Log.i(TAG, "using fresh " + fresh.getProvider() + " fix");
                    return new double[] { fresh.getLatitude(), fresh.getLongitude() };
                }
                LocationManager lm = (LocationManager) ctx.getSystemService(Context.LOCATION_SERVICE);
                if (lm != null) {
                    // No fresh fix — fall back to the most RECENT last-known fix.
                    // (A VPN can make IP-derived network fixes wrong, but a newer
                    // real fix always beats an older one.)
                    Location best = null;
                    for (String provider : new String[] {
                            LocationManager.GPS_PROVIDER,
                            LocationManager.NETWORK_PROVIDER,
                            LocationManager.PASSIVE_PROVIDER }) {
                        Location l;
                        try {
                            l = lm.getLastKnownLocation(provider);
                        } catch (SecurityException | IllegalArgumentException ex) {
                            continue;
                        }
                        if (l == null) continue;
                        if (best == null || l.getTime() > best.getTime())
                            best = l;
                    }
                    if (best != null)
                        return new double[] { best.getLatitude(), best.getLongitude() };
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "location lookup failed: " + e.getMessage());
        }
        // Coordinates of the last successful fetch, if any
        android.content.SharedPreferences prefs =
                ctx.getSharedPreferences("dial_sender_prefs", Context.MODE_PRIVATE);
        float lat = prefs.getFloat("weather_lat", Float.NaN);
        float lon = prefs.getFloat("weather_lon", Float.NaN);
        if (!Float.isNaN(lat) && !Float.isNaN(lon))
            return new double[] { lat, lon };
        return new double[] { FALLBACK_LAT, FALLBACK_LON };
    }

    /** Requests one fresh location fix (GPS preferred, network fallback), max ~12 s. */
    @SuppressLint("MissingPermission")
    private static Location requestFreshFix(Context ctx) {
        LocationManager lm = (LocationManager) ctx.getSystemService(Context.LOCATION_SERVICE);
        if (lm == null)
            return null;
        String provider = null;
        try {
            if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER))
                provider = LocationManager.GPS_PROVIDER;
            else if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER))
                provider = LocationManager.NETWORK_PROVIDER;
        } catch (Exception ignored) {
        }
        if (provider == null)
            return null;

        final Location[] holder = new Location[1];
        final java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
        android.os.HandlerThread ht = new android.os.HandlerThread("weather-loc");
        ht.start();
        android.location.LocationListener listener = new android.location.LocationListener() {
            @Override public void onLocationChanged(Location location) {
                holder[0] = location;
                latch.countDown();
            }
            @Override public void onStatusChanged(String p, int status, android.os.Bundle extras) { }
            @Override public void onProviderEnabled(String p) { }
            @Override public void onProviderDisabled(String p) { latch.countDown(); }
        };
        try {
            lm.requestSingleUpdate(provider, listener, ht.getLooper());
            latch.await(12, java.util.concurrent.TimeUnit.SECONDS);
        } catch (Exception e) {
            Log.w(TAG, "fresh fix failed: " + e.getMessage());
        } finally {
            try { lm.removeUpdates(listener); } catch (Exception ignored) { }
            ht.quitSafely();
        }
        return holder[0];
    }

    private static List<BleManager.WeatherDay> fetch(double lat, double lon) throws Exception {
        String url = "https://api.open-meteo.com/v1/forecast"
                + "?latitude=" + lat + "&longitude=" + lon
                + "&current=temperature_2m,weather_code,relative_humidity_2m,wind_speed_10m"
                + "&daily=weather_code,temperature_2m_max,temperature_2m_min,"
                + "uv_index_max,precipitation_probability_max,wind_speed_10m_max"
                + "&forecast_days=4&timezone=auto";

        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(8000);
        conn.setReadTimeout(8000);
        conn.setRequestProperty("User-Agent", "dial-sender/1.0");
        StringBuilder sb = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(conn.getInputStream()))) {
            String line;
            while ((line = r.readLine()) != null)
                sb.append(line);
        } finally {
            conn.disconnect();
        }

        JSONObject root = new JSONObject(sb.toString());
        JSONObject current = root.optJSONObject("current");
        int curTemp = current != null ? (int) Math.round(current.optDouble("temperature_2m", 0)) : 0;
        int curCode = current != null ? current.optInt("weather_code", 0) : 0;
        int curHum = current != null ? (int) Math.round(current.optDouble("relative_humidity_2m", 0)) : 0;
        int curWind = current != null ? (int) Math.round(current.optDouble("wind_speed_10m", 0)) : 0;

        JSONObject daily = root.getJSONObject("daily");
        JSONArray codes = daily.getJSONArray("weather_code");
        JSONArray maxA = daily.getJSONArray("temperature_2m_max");
        JSONArray minA = daily.getJSONArray("temperature_2m_min");
        JSONArray uvA = daily.optJSONArray("uv_index_max");
        JSONArray popA = daily.optJSONArray("precipitation_probability_max");
        JSONArray windA = daily.optJSONArray("wind_speed_10m_max");

        List<BleManager.WeatherDay> out = new ArrayList<>();
        int n = codes.length();
        for (int i = 0; i < n; i++) {
            int hi = (int) Math.round(maxA.optDouble(i, 0));
            int lo = (int) Math.round(minA.optDouble(i, 0));
            int cur = (i == 0) ? curTemp : (hi + lo) / 2;
            int code = (i == 0) ? wmoToCode(curCode) : wmoToCode(codes.optInt(i, 0));
            int wind = (i == 0) ? curWind : (windA != null ? (int) Math.round(windA.optDouble(i, 0)) : 0);
            int hum = (i == 0) ? curHum : 0;
            int uv = uvA != null ? (int) Math.round(uvA.optDouble(i, 0)) : 0;
            int pop = popA != null ? (int) Math.round(popA.optDouble(i, 0)) : 0;
            // visibility isn't provided here; leave 0.
            out.add(new BleManager.WeatherDay(code, cur, lo, hi, wind, hum, 0, uv, pop));
        }
        return out;
    }

    private static String resolveCity(Context ctx, double lat, double lon) {
        // First try Android Geocoder
        String androidCity = cityFromGeocoder(ctx, lat, lon);
        // If geocoder returned something useful (not empty, not a generic "Centro"), use it
        if (!androidCity.isEmpty() && !isGenericPlaceName(androidCity)) {
            return androidCity;
        }
        // Fallback: Open-Meteo reverse geocoding (nominatim-based, free)
        String nominatimCity = cityFromNominatim(lat, lon);
        if (!nominatimCity.isEmpty()) {
            return nominatimCity;
        }
        // Last resort: use the Android geocoder result even if generic
        return androidCity;
    }

    private static boolean isGenericPlaceName(String name) {
        String lower = name.toLowerCase(Locale.ROOT).trim();
        return lower.equals("centro") || lower.equals("center") || lower.equals("downtown")
                || lower.equals("central") || lower.equals("centre") || lower.length() <= 3;
    }

    private static String cityFromGeocoder(Context ctx, double lat, double lon) {
        try {
            Geocoder g = new Geocoder(ctx, Locale.getDefault());
            List<Address> addrs = g.getFromLocation(lat, lon, 1);
            if (addrs != null && !addrs.isEmpty()) {
                Address a = addrs.get(0);
                // Prefer the most specific non-generic names
                if (a.getLocality() != null && !isGenericPlaceName(a.getLocality()))
                    return a.getLocality();
                if (a.getSubAdminArea() != null && !isGenericPlaceName(a.getSubAdminArea()))
                    return a.getSubAdminArea();
                if (a.getAdminArea() != null)
                    return a.getAdminArea();
                if (a.getLocality() != null)
                    return a.getLocality();
            }
        } catch (Exception ignored) {
        }
        return "";
    }

    private static String cityFromNominatim(double lat, double lon) {
        try {
            String urlStr = "https://nominatim.openstreetmap.org/reverse?lat=" + lat
                    + "&lon=" + lon + "&format=json&zoom=10&accept-language=es";
            java.net.HttpURLConnection conn = (java.net.HttpURLConnection)
                    new java.net.URL(urlStr).openConnection();
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);
            conn.setRequestProperty("User-Agent", "Fogg/1.0");
            if (conn.getResponseCode() == java.net.HttpURLConnection.HTTP_OK) {
                BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = br.readLine()) != null) sb.append(line);
                br.close();
                org.json.JSONObject obj = new org.json.JSONObject(sb.toString());
                if (obj.has("address")) {
                    org.json.JSONObject addr = obj.getJSONObject("address");
                    // Try city, town, municipality, village in order
                    for (String key : new String[]{"city", "town", "municipality", "village", "county"}) {
                        if (addr.has(key) && !addr.getString(key).isEmpty())
                            return addr.getString(key);
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return "";
    }

    /**
     * Maps a WMO weather code (Open-Meteo) to the watch's BleWeather code.
     * Watch codes (from the original app): 1=sunny 2=cloudy 3=overcast 4=rainy
     * 5=thunder 6=thundershower 7=high-wind 8=snowy 9=foggy 10=sandstorm 11=haze,
     * 0=other.
     */
    private static int wmoToCode(int wmo) {
        switch (wmo) {
            case 0:
                return BleManager.WEATHER_SUNNY; // clear sky
            case 1:
            case 2:
                return BleManager.WEATHER_CLOUDY; // mainly clear / partly cloudy
            case 3:
                return BleManager.WEATHER_OVERCAST;
            case 45:
            case 48:
                return BleManager.WEATHER_FOGGY;
            case 95:
                return BleManager.WEATHER_THUNDER;
            case 96:
            case 99:
                return BleManager.WEATHER_THUNDERSHOWER;
            default:
                break;
        }
        if ((wmo >= 51 && wmo <= 67) || (wmo >= 80 && wmo <= 82))
            return BleManager.WEATHER_RAINY;
        if ((wmo >= 71 && wmo <= 77) || (wmo >= 85 && wmo <= 86))
            return BleManager.WEATHER_SNOWY;
        return BleManager.WEATHER_OTHER;
    }
}
