package com.example.dialsender.ble;

import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Symbol search and live quotes, so the stock rows carry real numbers instead
 * of the typed-in ones the add dialog used to ask for.
 *
 * Yahoo's public finance endpoints need no key:
 *   search  /v1/finance/search?q=…   → symbol, name, exchange
 *   quote   /v8/finance/chart/SYM    → meta.regularMarketPrice + previousClose
 *
 * They are undocumented and do rate-limit, so a quote falls back to Stooq's CSV
 * (a different operator entirely) before giving up. Both are read-only and take
 * nothing but the ticker.
 */
public final class StockApi {

    private static final String UA =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120 Mobile Safari/537.36";
    private static final int CONNECT_TIMEOUT_MS = 8000;
    private static final int READ_TIMEOUT_MS = 8000;

    private static final ExecutorService IO = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private StockApi() {
    }

    /** One search hit, or a quote once the price fields are filled in. */
    public static class Quote {
        public String symbol = "";
        public String name = "";
        public String exchange = "";
        public float price;
        public float changePoint;
        public float changePercent;

        public String label() {
            return name == null || name.isEmpty() ? symbol : name;
        }
    }

    public interface Callback<T> {
        void onResult(T value);

        void onError(String message);
    }

    // ===== Search =====

    public static void search(String query, Callback<List<Quote>> cb) {
        final String q = query == null ? "" : query.trim();
        if (q.isEmpty()) {
            MAIN.post(() -> cb.onResult(new ArrayList<>()));
            return;
        }
        IO.execute(() -> {
            try {
                String url = "https://query1.finance.yahoo.com/v1/finance/search?q="
                        + URLEncoder.encode(q, "UTF-8") + "&quotesCount=15&newsCount=0&lang=en-US";
                JSONObject root = new JSONObject(get(url));
                JSONArray quotes = root.optJSONArray("quotes");
                List<Quote> out = new ArrayList<>();
                if (quotes != null) {
                    for (int i = 0; i < quotes.length(); i++) {
                        JSONObject o = quotes.optJSONObject(i);
                        if (o == null) continue;
                        String symbol = o.optString("symbol", "");
                        if (symbol.isEmpty()) continue;
                        // Indices and currencies come back too; the watch row is
                        // a ticker with a price, so keep the tradable ones.
                        String type = o.optString("quoteType", "");
                        if (!type.isEmpty() && !"EQUITY".equals(type) && !"ETF".equals(type)
                                && !"INDEX".equals(type) && !"CRYPTOCURRENCY".equals(type))
                            continue;
                        Quote qt = new Quote();
                        qt.symbol = symbol;
                        qt.name = o.optString("shortname", o.optString("longname", symbol));
                        qt.exchange = o.optString("exchDisp", o.optString("exchange", ""));
                        out.add(qt);
                    }
                }
                MAIN.post(() -> cb.onResult(out));
            } catch (Exception e) {
                String msg = String.valueOf(e.getMessage());
                MAIN.post(() -> cb.onError(msg));
            }
        });
    }

    // ===== Quotes =====

    public static void quote(String symbol, Callback<Quote> cb) {
        final String sym = symbol == null ? "" : symbol.trim().toUpperCase(java.util.Locale.US);
        if (sym.isEmpty()) {
            MAIN.post(() -> cb.onError("empty symbol"));
            return;
        }
        IO.execute(() -> {
            Quote q = fetchYahoo(sym);
            if (q == null)
                q = fetchStooq(sym);
            final Quote result = q;
            if (result == null)
                MAIN.post(() -> cb.onError("no quote for " + sym));
            else
                MAIN.post(() -> cb.onResult(result));
        });
    }

    /**
     * Refresh a whole list in one background pass. The callback fires once, on
     * the main thread, with the quotes that came back — a symbol that fails is
     * left out rather than zeroed, so a rate-limit does not wipe a row.
     */
    public static void quoteAll(List<String> symbols, Callback<List<Quote>> cb) {
        final List<String> syms = new ArrayList<>(symbols);
        IO.execute(() -> {
            List<Quote> out = new ArrayList<>();
            for (String s : syms) {
                Quote q = fetchYahoo(s);
                if (q == null)
                    q = fetchStooq(s);
                if (q != null)
                    out.add(q);
            }
            MAIN.post(() -> cb.onResult(out));
        });
    }

    private static Quote fetchYahoo(String symbol) {
        try {
            String url = "https://query1.finance.yahoo.com/v8/finance/chart/"
                    + URLEncoder.encode(symbol, "UTF-8") + "?interval=1d&range=5d";
            JSONObject meta = new JSONObject(get(url))
                    .getJSONObject("chart")
                    .getJSONArray("result")
                    .getJSONObject(0)
                    .getJSONObject("meta");

            double price = meta.optDouble("regularMarketPrice", Double.NaN);
            double prev = meta.optDouble("previousClose",
                    meta.optDouble("chartPreviousClose", Double.NaN));
            if (Double.isNaN(price))
                return null;

            Quote q = new Quote();
            q.symbol = meta.optString("symbol", symbol);
            q.name = meta.optString("shortName", q.symbol);
            q.exchange = meta.optString("fullExchangeName", "");
            q.price = (float) price;
            if (!Double.isNaN(prev) && prev > 0) {
                q.changePoint = (float) (price - prev);
                q.changePercent = (float) ((price - prev) / prev * 100.0);
            }
            return q;
        } catch (Exception e) {
            return null;
        }
    }

    /** CSV fallback: symbol,date,time,open,high,low,close,volume,name. */
    private static Quote fetchStooq(String symbol) {
        for (String suffix : new String[] { ".us", "" }) {
            try {
                String url = "https://stooq.com/q/l/?s="
                        + URLEncoder.encode(symbol.toLowerCase(java.util.Locale.US) + suffix, "UTF-8")
                        + "&f=sd2t2ohlcvn&h&e=csv";
                String[] lines = get(url).split("\n");
                if (lines.length < 2) continue;
                String[] c = lines[1].split(",");
                if (c.length < 7) continue;
                float open = parse(c[3]);
                float close = parse(c[6]);
                if (close <= 0) continue;

                Quote q = new Quote();
                q.symbol = symbol;
                q.name = c.length > 8 ? c[8] : symbol;
                q.price = close;
                if (open > 0) {
                    q.changePoint = close - open;
                    q.changePercent = (close - open) / open * 100f;
                }
                return q;
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    private static float parse(String s) {
        try {
            return Float.parseFloat(s.trim());
        } catch (Exception e) {
            return 0f;
        }
    }

    private static String get(String url) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        try {
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            conn.setRequestProperty("User-Agent", UA);
            conn.setRequestProperty("Accept", "application/json,text/csv,*/*");
            if (conn.getResponseCode() != HttpURLConnection.HTTP_OK)
                throw new IllegalStateException("HTTP " + conn.getResponseCode());
            StringBuilder sb = new StringBuilder();
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null)
                    sb.append(line).append('\n');
            }
            return sb.toString();
        } finally {
            conn.disconnect();
        }
    }
}
