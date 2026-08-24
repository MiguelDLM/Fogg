package com.example.dialsender;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Bundle;
import android.view.View;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;

import com.github.mikephil.charting.charts.LineChart;
import com.github.mikephil.charting.components.XAxis;
import com.github.mikephil.charting.components.YAxis;
import com.github.mikephil.charting.data.Entry;
import com.github.mikephil.charting.data.LineData;
import com.github.mikephil.charting.data.LineDataSet;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.io.File;
import android.content.Intent;
import android.net.Uri;
import androidx.core.content.FileProvider;
import com.chaquo.python.PyObject;
import com.chaquo.python.Python;
import com.chaquo.python.android.AndroidPlatform;

public class SportDetailActivity extends AppCompatActivity {
    /** Active theme, resolved once so every builder below can read its tokens. */
    private com.example.dialsender.theme.ThemeManager.AppTheme theme;


    private static class LatLng {
        double latitude;
        double longitude;

        LatLng(double lat, double lon) {
            this.latitude = lat;
            this.longitude = lon;
        }
    }

    private WebView mapWebView;
    private LineChart chartHeartRate;
    private LineChart chartElevation;
    private LineChart chartSpeed;

    private TextView txtSportTitle;
    private TextView txtSessionDate;
    private TextView txtDuration;
    private TextView txtDistance;
    private TextView txtCalories;
    private TextView txtPace;
    private TextView txtAvgHr;
    private TextView txtElevation;

    private List<LatLng> currentPath;
    private boolean hasRoute, hasElevation, hasSpeed, hasHeartRate;
    private View sectionMap, sectionHeartRate, sectionElevation, sectionSpeed;
    private View chartsTitle, noDataNotice;
    private String currentSportName;

    protected void attachBaseContext(android.content.Context base) {
        super.attachBaseContext(LocaleHelper.wrap(base));
    }

    protected void onCreate(Bundle savedInstanceState) {
        com.example.dialsender.theme.ThemeManager.applyTheme(this);
        theme = com.example.dialsender.theme.ThemeManager.getTheme(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_sport_detail);

        // Bind Views
        ImageButton btnBack = findViewById(R.id.btnBack);
        btnBack.setOnClickListener(v -> finish());

        sectionMap = findViewById(R.id.sectionMap);
        sectionHeartRate = findViewById(R.id.sectionHeartRate);
        sectionElevation = findViewById(R.id.sectionElevation);
        sectionSpeed = findViewById(R.id.sectionSpeed);
        chartsTitle = findViewById(R.id.txtChartsTitle);
        noDataNotice = findViewById(R.id.txtNoSensorData);

        ImageButton btnDelete = findViewById(R.id.btnDeleteSession);
        btnDelete.setOnClickListener(v -> confirmDelete());

        ImageButton btnShare = findViewById(R.id.btnShare);
        btnShare.setOnClickListener(v -> {
            if (currentPath == null || currentPath.isEmpty()) {
                Toast.makeText(this, getString(R.string.sport_no_route), Toast.LENGTH_SHORT).show();
                return;
            }
            
            Toast.makeText(this, getString(R.string.sport_generating_anim), Toast.LENGTH_SHORT).show();
            
            new Thread(() -> {
                try {
                    if (!Python.isStarted()) {
                        Python.start(new AndroidPlatform(this));
                    }
                    
                    StringBuilder coordsStr = new StringBuilder();
                    for (LatLng p : currentPath) {
                        if (coordsStr.length() > 0) {
                            coordsStr.append(";");
                        }
                        coordsStr.append(p.latitude).append(",").append(p.longitude);
                    }
                    
                    File cacheDir = getCacheDir();
                    File gifFile = new File(cacheDir, "ruta_" + System.currentTimeMillis() + ".gif");
                    String outputPath = gifFile.getAbsolutePath();
                    
                    Python py = Python.getInstance();
                    PyObject pyModule = py.getModule("generate_route_gif");
                    pyModule.callAttr("generate_gif", 
                        coordsStr.toString(), 
                        outputPath, 
                        currentSportName != null ? currentSportName : getString(R.string.sport_other), 
                        txtDuration.getText().toString(), 
                        txtDistance.getText().toString(), 
                        txtCalories.getText().toString()
                    );
                    
                    runOnUiThread(() -> {
                        if (gifFile.exists()) {
                            shareGifFile(gifFile);
                        } else {
                            Toast.makeText(this, getString(R.string.sport_anim_error), Toast.LENGTH_SHORT).show();
                        }
                    });
                } catch (Exception e) {
                    e.printStackTrace();
                    runOnUiThread(() -> {
                        Toast.makeText(this, getString(R.string.sport_anim_gen_error, e.getMessage()), Toast.LENGTH_LONG).show();
                    });
                }
            }).start();
        });

        txtSportTitle = findViewById(R.id.txtSportTitle);
        txtSessionDate = findViewById(R.id.txtSessionDate);
        txtDuration = findViewById(R.id.txtDuration);
        txtDistance = findViewById(R.id.txtDistance);
        txtCalories = findViewById(R.id.txtCalories);
        txtPace = findViewById(R.id.txtPace);
        txtAvgHr = findViewById(R.id.txtAvgHr);
        txtElevation = findViewById(R.id.txtElevation);

        mapWebView = findViewById(R.id.mapWebView);
        chartHeartRate = findViewById(R.id.chartHeartRate);
        chartElevation = findViewById(R.id.chartElevation);
        chartSpeed = findViewById(R.id.chartSpeed);

        // Configure WebView
        WebSettings webSettings = mapWebView.getSettings();
        webSettings.setJavaScriptEnabled(true);
        mapWebView.setWebViewClient(new WebViewClient());

        // Parse Intent Extra
        String sessionRecord = getIntent().getStringExtra("session_record");
        if (sessionRecord == null || sessionRecord.isEmpty()) {
            Toast.makeText(this, getString(R.string.sport_session_not_found), Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        loadSession(sessionRecord);
    }

    private void loadSession(String record) {
        // Format: start|sportName|durSec|kcal
        String[] parts = record.split("\\|");
        if (parts.length < 4) {
            Toast.makeText(this, getString(R.string.sport_record_corrupt), Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        long startTime = Long.parseLong(parts[0]);
        String sportName = parts[1];
        int durSec = Integer.parseInt(parts[2]);
        int kcal = Integer.parseInt(parts[3]);
        long endTime = startTime + durSec;

        txtSportTitle.setText(sportName);

        SimpleDateFormat sdf = new SimpleDateFormat("d MMMM yyyy, HH:mm", Locale.getDefault());
        txtSessionDate.setText(sdf.format(new Date(startTime * 1000L)));

        // Try to find matching health_workout synced from the watch
        SharedPreferences prefs = getSharedPreferences("dial_sender_prefs", MODE_PRIVATE);
        String workoutPref = prefs.getString("health_workout", "");
        String matchingWorkout = null;
        if (!workoutPref.isEmpty()) {
            for (String w : workoutPref.split(",")) {
                if (w.startsWith(startTime + ":")) {
                    matchingWorkout = w;
                    break;
                }
            }
        }

        int steps = 0;
        double distanceKm = 0.0;
        int watchCalories = kcal;
        int avgHr = 0;
        int maxHr = 0;
        int altGain = 0;

        if (matchingWorkout != null) {
            String[] wp = matchingWorkout.split(":");
            if (wp.length >= 14) {
                // start(0) end(1) duration(2) altitude(3) airPressure(4) spm(5) mode(6) step(7) distance(8) calorie(9) speed(10) pace(11) avgBpm(12) maxBpm(13)
                try {
                    durSec = Integer.parseInt(wp[2]);
                    altGain = Math.abs(Integer.parseInt(wp[3])); // using altitude diff or peak
                    steps = Integer.parseInt(wp[7]);
                    distanceKm = Double.parseDouble(wp[8]) / 1000.0;
                    watchCalories = Integer.parseInt(wp[9]);
                    avgHr = Integer.parseInt(wp[12]);
                    maxHr = Integer.parseInt(wp[13]);
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }

        // Standard duration formatting
        int h = durSec / 3600;
        int m = (durSec % 3600) / 60;
        int s = durSec % 60;
        txtDuration.setText(String.format(Locale.US, "%02d:%02d:%02d", h, m, s));

        // Fetch location data from prefs
        List<LatLng> path = new ArrayList<>();
        List<Entry> elevEntries = new ArrayList<>();
        List<Entry> speedEntries = new ArrayList<>();
        
        String locationPref = prefs.getString("health_location", "");
        if (!locationPref.isEmpty()) {
            for (String loc : locationPref.split(",")) {
                String[] lp = loc.split(":");
                if (lp.length >= 5) {
                    try {
                        long t = Long.parseLong(lp[0]);
                        if (t >= startTime && t <= endTime) {
                            int alt = Integer.parseInt(lp[2]);
                            double lon = Double.parseDouble(lp[3]);
                            double lat = Double.parseDouble(lp[4]);
                            path.add(new LatLng(lat, lon));
                            
                            float relMin = (t - startTime) / 60.0f;
                            elevEntries.add(new Entry(relMin, alt));
                        }
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }
            }
        }

        // Fetch HR data from prefs
        List<Entry> hrEntries = new ArrayList<>();
        String hrPref = prefs.getString("health_heart_rate", "");
        int totalHrSum = 0;
        int hrCount = 0;
        if (!hrPref.isEmpty()) {
            for (String hrRec : hrPref.split(",")) {
                String[] hp = hrRec.split(":");
                if (hp.length >= 2) {
                    try {
                        long t = Long.parseLong(hp[0]);
                        if (t >= startTime && t <= endTime) {
                            int bpm = Integer.parseInt(hp[1]);
                            if (bpm > 0) {
                                float relMin = (t - startTime) / 60.0f;
                                hrEntries.add(new Entry(relMin, bpm));
                                totalHrSum += bpm;
                                hrCount++;
                                if (bpm > maxHr) maxHr = bpm;
                            }
                        }
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }
            }
        }

        if (hrCount > 0 && avgHr == 0) {
            avgHr = totalHrSum / hrCount;
        }

        // Sessions recorded by the phone carry their own GPS trace.
        if (path.isEmpty()) {
            List<WorkoutTrack.Point> recorded = WorkoutTrack.load(this, startTime);
            for (WorkoutTrack.Point pt : recorded) {
                path.add(new LatLng(pt.lat, pt.lon));
                if (!Double.isNaN(pt.altitude))
                    elevEntries.add(new Entry(pt.elapsedSec / 60.0f, (float) pt.altitude));
            }
            if (!recorded.isEmpty()) {
                if (distanceKm == 0.0)
                    distanceKm = WorkoutTrack.distanceMetres(recorded) / 1000.0;
                double gain = WorkoutTrack.elevationGainMetres(recorded);
                if (altGain == 0 && gain >= 0)
                    altGain = (int) Math.round(gain);
                hasElevation = gain >= 0;
                speedEntries.addAll(speedFromTrack(recorded));
            }
        } else {
            hasElevation = !elevEntries.isEmpty();
            speedEntries.addAll(speedFromWatchTrack(locationPref, startTime, endTime));
        }

        hasRoute = path.size() >= 2;
        hasSpeed = !speedEntries.isEmpty();
        hasHeartRate = !hrEntries.isEmpty();

        // Set UI values. Anything we do not actually have reads "--" rather than
        // a plausible-looking invention.
        txtDistance.setText(distanceKm > 0
                ? String.format(Locale.US, "%.2f km", distanceKm)
                : "-- km");
        txtCalories.setText(watchCalories + " kcal");
        txtElevation.setText(hasElevation ? altGain + " m" : "-- m");

        // Format Pace
        if (distanceKm > 0) {
            double paceDecimal = (durSec / 60.0) / distanceKm;
            int paceMin = (int) paceDecimal;
            int paceSec = (int) ((paceDecimal - paceMin) * 60);
            txtPace.setText(String.format(Locale.US, "%02d'%02d\"", paceMin, paceSec));
        } else {
            txtPace.setText("--'--");
        }

        String bpmUnit = " " + getString(R.string.unit_bpm);
        if (avgHr > 0) {
            txtAvgHr.setText(avgHr + bpmUnit);
        } else {
            txtAvgHr.setText("--" + bpmUnit);
        }

        this.currentPath = path;
        this.currentSportName = sportName;

        // Sections with no data are hidden outright.
        showSection(sectionMap, hasRoute);
        showSection(sectionHeartRate, hasHeartRate);
        showSection(sectionElevation, hasElevation && elevEntries.size() >= 2);
        showSection(sectionSpeed, hasSpeed);
        boolean anyChart = hasHeartRate || (hasElevation && elevEntries.size() >= 2) || hasSpeed;
        showSection(chartsTitle, anyChart);
        showSection(noDataNotice, !hasRoute && !anyChart);

        if (hasRoute)
            loadMap(path);
        if (hasHeartRate)
            setupChart(chartHeartRate, hrEntries, "#EF4444");
        if (hasElevation && elevEntries.size() >= 2)
            setupChart(chartElevation, elevEntries, "#F59E0B");
        if (hasSpeed)
            setupChart(chartSpeed, speedEntries, "#22D3EE");
    }

    private static void showSection(View view, boolean visible) {
        if (view != null)
            view.setVisibility(visible ? View.VISIBLE : View.GONE);
    }

    /** Speed between consecutive fixes — measured, not modelled. */
    private static List<Entry> speedFromTrack(List<WorkoutTrack.Point> points) {
        List<Entry> out = new ArrayList<>();
        for (int i = 1; i < points.size(); i++) {
            WorkoutTrack.Point a = points.get(i - 1);
            WorkoutTrack.Point b = points.get(i);
            int dt = b.elapsedSec - a.elapsedSec;
            if (dt <= 0)
                continue;
            float[] r = new float[1];
            android.location.Location.distanceBetween(a.lat, a.lon, b.lat, b.lon, r);
            out.add(new Entry(b.elapsedSec / 60.0f, (float) (r[0] / dt * 3.6)));
        }
        return out;
    }

    /** Same, for a route the watch recorded (health_location). */
    private static List<Entry> speedFromWatchTrack(String locationPref, long startTime,
            long endTime) {
        List<Entry> out = new ArrayList<>();
        double prevLat = 0, prevLon = 0;
        long prevT = 0;
        boolean first = true;
        for (String loc : locationPref.split(",")) {
            String[] lp = loc.split(":");
            if (lp.length < 5)
                continue;
            try {
                long t = Long.parseLong(lp[0]);
                if (t < startTime || t > endTime)
                    continue;
                double lon = Double.parseDouble(lp[3]);
                double lat = Double.parseDouble(lp[4]);
                if (!first && t > prevT) {
                    float[] r = new float[1];
                    android.location.Location.distanceBetween(prevLat, prevLon, lat, lon, r);
                    out.add(new Entry((t - startTime) / 60.0f,
                            (float) (r[0] / (double) (t - prevT) * 3.6)));
                }
                prevLat = lat;
                prevLon = lon;
                prevT = t;
                first = false;
            } catch (Exception ignored) {
            }
        }
        return out;
    }

    private void loadMap(List<LatLng> path) {
        StringBuilder latLonArray = new StringBuilder();
        for (LatLng p : path) {
            if (latLonArray.length() > 0) {
                latLonArray.append(",");
            }
            latLonArray.append("[").append(p.latitude).append(",").append(p.longitude).append("]");
        }

        String htmlContent = "<!DOCTYPE html>\n" +
                "<html>\n" +
                "<head>\n" +
                "    <meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no\" />\n" +
                "    <link rel=\"stylesheet\" href=\"https://unpkg.com/leaflet@1.7.1/dist/leaflet.css\" />\n" +
                "    <script src=\"https://unpkg.com/leaflet@1.7.1/dist/leaflet.js\"></script>\n" +
                "    <style>\n" +
                "        html, body, #map {\n" +
                "            height: 100%;\n" +
                "            width: 100%;\n" +
                "            margin: 0;\n" +
                "            padding: 0;\n" +
                "            background: #1A2027;\n" +
                "        }\n" +
                "        #controls {\n" +
                "            position: absolute;\n" +
                "            bottom: 12px;\n" +
                "            left: 12px;\n" +
                "            right: 12px;\n" +
                "            background: rgba(26, 32, 39, 0.85);\n" +
                "            border-radius: 10px;\n" +
                "            padding: 8px 12px;\n" +
                "            display: flex;\n" +
                "            align-items: center;\n" +
                "            gap: 10px;\n" +
                "            z-index: 1000;\n" +
                "            box-shadow: 0 4px 6px rgba(0,0,0,0.3);\n" +
                "            border: 1px solid rgba(255,255,255,0.08);\n" +
                "            font-family: -apple-system, BlinkMacSystemFont, \"Segoe UI\", Roboto, Helvetica, Arial, sans-serif;\n" +
                "        }\n" +
                "        .btn {\n" +
                "            background: #22D3EE;\n" +
                "            border: none;\n" +
                "            color: #06121A;\n" +
                "            height: 30px;\n" +
                "            border-radius: 6px;\n" +
                "            cursor: pointer;\n" +
                "            font-weight: bold;\n" +
                "            font-size: 12px;\n" +
                "            outline: none;\n" +
                "            display: flex;\n" +
                "            align-items: center;\n" +
                "            justify-content: center;\n" +
                "        }\n" +
                "        #playBtn {\n" +
                "            width: 30px;\n" +
                "            border-radius: 50%;\n" +
                "            font-size: 14px;\n" +
                "        }\n" +
                "        #speedBtn {\n" +
                "            padding: 0 8px;\n" +
                "            background: rgba(255,255,255,0.12);\n" +
                "            color: #FFFFFF;\n" +
                "        }\n" +
                "        #mapTypeBtn {\n" +
                "            padding: 0 8px;\n" +
                "            background: #4A5568;\n" +
                "            color: #FFFFFF;\n" +
                "        }\n" +
                "        #slider {\n" +
                "            flex-grow: 1;\n" +
                "            -webkit-appearance: none;\n" +
                "            background: rgba(255, 255, 255, 0.2);\n" +
                "            height: 4px;\n" +
                "            border-radius: 2px;\n" +
                "            outline: none;\n" +
                "            margin: 0;\n" +
                "        }\n" +
                "        #slider::-webkit-slider-thumb {\n" +
                "            -webkit-appearance: none;\n" +
                "            appearance: none;\n" +
                "            width: 12px;\n" +
                "            height: 12px;\n" +
                "            border-radius: 50%;\n" +
                "            background: #22D3EE;\n" +
                "            cursor: pointer;\n" +
                "        }\n" +
                "    </style>\n" +
                "</head>\n" +
                "<body>\n" +
                "    <div id=\"map\"></div>\n" +
                "    <div id=\"controls\">\n" +
                "        <button id=\"playBtn\" class=\"btn\">▶</button>\n" +
                "        <input type=\"range\" id=\"slider\" min=\"0\" value=\"0\">\n" +
                "        <button id=\"speedBtn\" class=\"btn\">1x</button>\n" +
                "        <button id=\"mapTypeBtn\" class=\"btn\">City</button>\n" +
                "    </div>\n" +
                "    <script>\n" +
                "        var map = L.map('map', {\n" +
                "            zoomControl: false,\n" +
                "            attributionControl: false\n" +
                "        });\n" +
                "        \n" +
                "        var darkLayer = L.tileLayer('https://{s}.basemaps.cartocdn.com/dark_all/{z}/{x}/{y}{r}.png', {\n" +
                "            maxZoom: 18\n" +
                "        });\n" +
                "        var satLayer = L.tileLayer('https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}', {\n" +
                "            maxZoom: 18\n" +
                "        });\n" +
                "        var topoLayer = L.tileLayer('https://{s}.tile.opentopomap.org/{z}/{x}/{y}.png', {\n" +
                "            maxZoom: 18,\n" +
                "            maxNativeZoom: 17\n" +
                "        });\n" +
                "\n" +
                "        darkLayer.addTo(map);\n" +
                "        var layers = [darkLayer, satLayer, topoLayer];\n" +
                "        var layerNames = ['City', 'Satélite', 'Topo'];\n" +
                "        var currentLayerIdx = 0;\n" +
                "\n" +
                "        var pathData = [" + latLonArray.toString() + "];\n" +
                "        \n" +
                "        if (pathData.length > 0) {\n" +
                "            var fullPolyline = L.polyline(pathData, {\n" +
                "                color: '#22D3EE',\n" +
                "                weight: 4,\n" +
                "                opacity: 0.25\n" +
                "            }).addTo(map);\n" +
                "\n" +
                "            var activePolyline = L.polyline([], {\n" +
                "                color: '#22D3EE',\n" +
                "                weight: 5,\n" +
                "                opacity: 0.95\n" +
                "            }).addTo(map);\n" +
                "            \n" +
                "            L.circle(pathData[0], { radius: 10, color: '#10B981', fillColor: '#10B981', fillOpacity: 1 }).addTo(map);\n" +
                "            L.circle(pathData[pathData.length - 1], { radius: 10, color: '#EF4444', fillColor: '#EF4444', fillOpacity: 1 }).addTo(map);\n" +
                "\n" +
                "            var runnerMarker = L.circleMarker(pathData[0], {\n" +
                "                radius: 7,\n" +
                "                color: '#FFFFFF',\n" +
                "                weight: 2,\n" +
                "                fillColor: '#00D8FF',\n" +
                "                fillOpacity: 1\n" +
                "            }).addTo(map);\n" +
                "\n" +
                "            var runnerShadow = L.circleMarker(pathData[0], {\n" +
                "                radius: 12,\n" +
                "                color: '#00D8FF',\n" +
                "                weight: 0,\n" +
                "                fillColor: '#00D8FF',\n" +
                "                fillOpacity: 0.3\n" +
                "            }).addTo(map);\n" +
                "\n" +
                "            map.fitBounds(fullPolyline.getBounds(), { padding: [30, 45] });\n" +
                "\n" +
                "            var currentIndex = 0;\n" +
                "            var isPlaying = false;\n" +
                "            var timer = null;\n" +
                "            var speedMultiplier = 1;\n" +
                "            var baseDelay = 120;\n" +
                "\n" +
                "            function updatePosition(index) {\n" +
                "                if (index < 0) index = 0;\n" +
                "                if (index >= pathData.length) index = pathData.length - 1;\n" +
                "                currentIndex = index;\n" +
                "                \n" +
                "                var pos = pathData[currentIndex];\n" +
                "                runnerMarker.setLatLng(pos);\n" +
                "                runnerShadow.setLatLng(pos);\n" +
                "                \n" +
                "                activePolyline.setLatLngs(pathData.slice(0, currentIndex + 1));\n" +
                "                document.getElementById('slider').value = currentIndex;\n" +
                "            }\n" +
                "\n" +
                "            function play() {\n" +
                "                isPlaying = true;\n" +
                "                document.getElementById('playBtn').innerHTML = '❚❚';\n" +
                "                tick();\n" +
                "            }\n" +
                "\n" +
                "            function pause() {\n" +
                "                isPlaying = false;\n" +
                "                document.getElementById('playBtn').innerHTML = '▶';\n" +
                "                clearTimeout(timer);\n" +
                "            }\n" +
                "\n" +
                "            function tick() {\n" +
                "                if (!isPlaying) return;\n" +
                "                if (currentIndex >= pathData.length - 1) {\n" +
                "                    updatePosition(0);\n" +
                "                } else {\n" +
                "                    updatePosition(currentIndex + 1);\n" +
                "                }\n" +
                "                timer = setTimeout(tick, baseDelay / speedMultiplier);\n" +
                "            }\n" +
                "\n" +
                "            document.getElementById('playBtn').addEventListener('click', function() {\n" +
                "                if (isPlaying) {\n" +
                "                    pause();\n" +
                "                } else {\n" +
                "                    play();\n" +
                "                }\n" +
                "            });\n" +
                "\n" +
                "            document.getElementById('speedBtn').addEventListener('click', function() {\n" +
                "                if (speedMultiplier === 1) speedMultiplier = 2;\n" +
                "                else if (speedMultiplier === 2) speedMultiplier = 5;\n" +
                "                else if (speedMultiplier === 5) speedMultiplier = 10;\n" +
                "                else speedMultiplier = 1;\n" +
                "                document.getElementById('speedBtn').innerText = speedMultiplier + 'x';\n" +
                "            });\n" +
                "\n" +
                "            document.getElementById('mapTypeBtn').addEventListener('click', function() {\n" +
                "                map.removeLayer(layers[currentLayerIdx]);\n" +
                "                currentLayerIdx = (currentLayerIdx + 1) % layers.length;\n" +
                "                layers[currentLayerIdx].addTo(map);\n" +
                "                document.getElementById('mapTypeBtn').innerText = layerNames[currentLayerIdx];\n" +
                "            });\n" +
                "\n" +
                "            var slider = document.getElementById('slider');\n" +
                "            slider.max = pathData.length - 1;\n" +
                "            slider.addEventListener('input', function() {\n" +
                "                pause();\n" +
                "                updatePosition(parseInt(slider.value));\n" +
                "            });\n" +
                "\n" +
                "            setTimeout(play, 800);\n" +
                "        } else {\n" +
                // Unreachable now that the map only loads with a real route,
                // but a hardcoded city here is how the fake data started.
                "            map.setView([0, 0], 2);\n" +
                "            document.getElementById('controls').style.display = 'none';\n" +
                "        }\n" +
                "    </script>\n" +
                "</body>\n" +
                "</html>";

        mapWebView.loadDataWithBaseURL("https://app", htmlContent, "text/html", "UTF-8", null);
    }

    private void setupChart(LineChart chart, List<Entry> entries, String colorHex) {
        chart.getDescription().setEnabled(false);
        chart.getLegend().setEnabled(false);
        chart.setDrawGridBackground(false);
        chart.setTouchEnabled(true);
        chart.setDragEnabled(true);
        chart.setScaleEnabled(true);
        chart.setPinchZoom(true);

        XAxis xAxis = chart.getXAxis();
        xAxis.setPosition(XAxis.XAxisPosition.BOTTOM);
        xAxis.setTextColor(Color.parseColor("#9AA4B2"));
        xAxis.setDrawGridLines(true);
        xAxis.setGridColor(Color.parseColor("#1CFFFFFF")); // 10% white
        xAxis.setAxisLineColor(Color.TRANSPARENT);

        YAxis leftAxis = chart.getAxisLeft();
        leftAxis.setTextColor(Color.parseColor("#9AA4B2"));
        leftAxis.setDrawGridLines(true);
        leftAxis.setGridColor(Color.parseColor("#1CFFFFFF")); // 10% white
        leftAxis.setAxisLineColor(Color.TRANSPARENT);

        YAxis rightAxis = chart.getAxisRight();
        rightAxis.setEnabled(false);

        LineDataSet dataSet = new LineDataSet(entries, "");
        dataSet.setColor(Color.parseColor(colorHex));
        dataSet.setLineWidth(2f);
        dataSet.setDrawCircles(false);
        dataSet.setDrawValues(false);
        dataSet.setMode(LineDataSet.Mode.CUBIC_BEZIER);
        
        // Setup gradient fill
        dataSet.setDrawFilled(true);
        dataSet.setFillColor(Color.parseColor(colorHex));
        dataSet.setFillAlpha(35);

        LineData lineData = new LineData(dataSet);
        chart.setData(lineData);
        chart.invalidate();
    }

    private void shareGifFile(File file) {
        try {
            Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".provider", file);
            Intent intent = new Intent(Intent.ACTION_SEND);
            intent.setType("image/gif");
            intent.putExtra(Intent.EXTRA_STREAM, uri);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(intent, getString(R.string.sport_share_video)));
        } catch (Exception e) {
            e.printStackTrace();
            Toast.makeText(this, getString(R.string.share_error, e.getMessage()), Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * Deletes the workout being shown. Same mechanism as the history's
     * selection mode: remove any phone-side copy and suppress the start
     * timestamp so the watch does not re-add it on the next sync.
     */
    private void confirmDelete() {
        final String record = getIntent().getStringExtra("session_record");
        if (record == null || record.isEmpty()) {
            Toast.makeText(this, getString(R.string.sport_session_not_found), Toast.LENGTH_SHORT).show();
            return;
        }
        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle(getString(R.string.sport_delete_title))
                .setMessage(getResources().getQuantityString(R.plurals.sport_delete_msg, 1, 1))
                .setPositiveButton(getString(R.string.delete), (d, w) -> {
                    deleteSession(record.split("\\|")[0].trim());
                    Toast.makeText(this, getString(R.string.sport_workout_deleted),
                            Toast.LENGTH_SHORT).show();
                    finish();
                })
                .setNegativeButton(getString(R.string.cancel), null)
                .show();
    }

    private void deleteSession(String start) {
        android.content.SharedPreferences prefs =
                getSharedPreferences("dial_sender_prefs", MODE_PRIVATE);

        StringBuilder kept = new StringBuilder();
        for (String s : prefs.getString("sport_sessions", "").split(",")) {
            if (s.trim().isEmpty())
                continue;
            if (s.split("\\|")[0].trim().equals(start))
                continue;
            if (kept.length() > 0)
                kept.append(",");
            kept.append(s);
        }

        boolean fromWatch = false;
        for (String w : prefs.getString("health_workout", "").split(",")) {
            String[] f = w.split(":");
            if (f.length >= 10 && f[0].trim().equals(start)) {
                fromWatch = true;
                break;
            }
        }

        StringBuilder hidden = new StringBuilder();
        boolean present = !fromWatch; // only watch workouts need suppressing
        for (String s : prefs.getString("sport_hidden_starts", "").split(",")) {
            String t = s.trim();
            if (t.isEmpty())
                continue;
            if (t.equals(start))
                present = true;
            if (hidden.length() > 0)
                hidden.append(",");
            hidden.append(t);
        }
        if (!present) {
            if (hidden.length() > 0)
                hidden.append(",");
            hidden.append(start);
        }

        prefs.edit()
                .putString("sport_sessions", kept.toString())
                .putString("sport_hidden_starts", hidden.toString())
                .apply();
        try {
            WorkoutTrack.delete(this, Long.parseLong(start));
        } catch (Exception ignored) {
        }
    }
}
