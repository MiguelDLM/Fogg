package com.example.dialsender.ble;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public class StockMarketManager {

    private static final String PREF_NAME = "dial_sender_prefs";
    private static final String KEY_STOCKS = "stocks_json";
    public static final String KEY_STOCK_COLOR_MODE = "stock_color_mode"; // 0=GreenUp, 1=RedUp
    public static final int MAX_STOCKS = 8;

    public interface StockMarketChangeListener {
        void onStocksChanged();
    }

    private static final List<StockMarketChangeListener> listeners = new CopyOnWriteArrayList<>();

    public static void addListener(StockMarketChangeListener listener) {
        if (listener != null && !listeners.contains(listener)) {
            listeners.add(listener);
        }
    }

    public static void removeListener(StockMarketChangeListener listener) {
        if (listener != null) {
            listeners.remove(listener);
        }
    }

    private static void notifyListeners() {
        for (StockMarketChangeListener l : listeners) {
            try {
                l.onStocksChanged();
            } catch (Exception ignored) {}
        }
    }

    public static class StockItem {
        public int id;
        public String stockCode;
        public String companyName;
        public float sharePrice;
        public float netChangePoint;
        public float netChangePercent;
        public float marketCap;

        public StockItem(int id, String stockCode, String companyName, float sharePrice,
                         float netChangePoint, float netChangePercent, float marketCap) {
            this.id = id;
            this.stockCode = stockCode;
            this.companyName = companyName;
            this.sharePrice = sharePrice;
            this.netChangePoint = netChangePoint;
            this.netChangePercent = netChangePercent;
            this.marketCap = marketCap;
        }
    }

    public static int getColorMode(Context context) {
        return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).getInt(KEY_STOCK_COLOR_MODE, 0);
    }

    public static void setColorMode(Context context, int mode) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).edit().putInt(KEY_STOCK_COLOR_MODE, mode).apply();
    }

    private static final String KEY_STOCKS_INITIALIZED = "stocks_initialized";

    public static List<StockItem> getSavedStocks(Context context) {
        SharedPreferences sp = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        List<StockItem> list = new ArrayList<>();
        if (!sp.getBoolean(KEY_STOCKS_INITIALIZED, false)) {
            sp.edit().putBoolean(KEY_STOCKS_INITIALIZED, true).apply();
            // Default stock presets on fresh install only
            list.add(new StockItem(1, "AAPL", "Apple Inc.", 228.50f, 2.45f, 1.08f, 3500.0f));
            list.add(new StockItem(2, "GOOGL", "Alphabet Inc.", 165.20f, -1.15f, -0.69f, 2050.0f));
            list.add(new StockItem(3, "MSFT", "Microsoft Corp.", 415.80f, 3.80f, 0.92f, 3100.0f));
            saveStocks(context, list);
            return list;
        }
        String jsonStr = sp.getString(KEY_STOCKS, "[]");
        try {
            JSONArray arr = new JSONArray(jsonStr);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject obj = arr.getJSONObject(i);
                list.add(new StockItem(
                        obj.optInt("id", i + 1),
                        obj.optString("stockCode", ""),
                        obj.optString("companyName", ""),
                        (float) obj.optDouble("sharePrice", 0.0),
                        (float) obj.optDouble("netChangePoint", 0.0),
                        (float) obj.optDouble("netChangePercent", 0.0),
                        (float) obj.optDouble("marketCap", 0.0)
                ));
            }
        } catch (Exception ignored) {}
        return list;
    }

    public static void saveStocks(Context context, List<StockItem> list) {
        SharedPreferences sp = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        JSONArray arr = new JSONArray();
        for (int i = 0; i < list.size(); i++) {
            StockItem item = list.get(i);
            item.id = i + 1; // keep IDs consistent 1..N
            JSONObject obj = new JSONObject();
            try {
                obj.put("id", item.id);
                obj.put("stockCode", item.stockCode);
                obj.put("companyName", item.companyName);
                obj.put("sharePrice", item.sharePrice);
                obj.put("netChangePoint", item.netChangePoint);
                obj.put("netChangePercent", item.netChangePercent);
                obj.put("marketCap", item.marketCap);
                arr.put(obj);
            } catch (Exception ignored) {}
        }
        sp.edit().putString(KEY_STOCKS, arr.toString()).apply();
        notifyListeners();
    }

    /**
     * Called when the watch notifies that a stock has been deleted from the watch UI.
     */
    public static void deleteStockById(Context context, int id) {
        List<StockItem> list = getSavedStocks(context);
        if (id == 0xFF || id == 255) {
            list.clear();
            saveStocks(context, list);
            return;
        }

        boolean removed = false;
        Iterator<StockItem> it = list.iterator();
        while (it.hasNext()) {
            StockItem item = it.next();
            if (item.id == id) {
                it.remove();
                removed = true;
                break;
            }
        }
        if (!removed && id >= 1 && id <= list.size()) {
            list.remove(id - 1);
            removed = true;
        }

        if (removed) {
            saveStocks(context, list);
        }
    }

    public static void syncToWatch(Context context) {
        BleManager ble = BleManager.getInstance(context);
        if (!ble.isSessionReady()) return;

        // Reset existing on watch
        ble.resetStocks();

        int colorMode = getColorMode(context);
        List<StockItem> stocks = getSavedStocks(context);
        for (int i = 0; i < stocks.size(); i++) {
            StockItem item = stocks.get(i);
            ble.sendStock(i + 1, colorMode, item.stockCode, item.sharePrice,
                    item.netChangePoint, item.netChangePercent, item.marketCap);
        }
    }
}
