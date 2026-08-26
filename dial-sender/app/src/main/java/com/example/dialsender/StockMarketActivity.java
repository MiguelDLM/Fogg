package com.example.dialsender;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.example.dialsender.ble.StockMarketManager;
import com.example.dialsender.theme.ThemeManager;

import java.util.List;
import java.util.Locale;

public class StockMarketActivity extends AppCompatActivity {

    private LinearLayout containerStocks;
    private TextView txtEmptyStocks;
    private List<StockMarketManager.StockItem> stockList;
    private ThemeManager.AppTheme theme;

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(LocaleHelper.wrap(base));
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        ThemeManager.applyTheme(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_stock_market);

        theme = ThemeManager.getTheme(this);

        findViewById(R.id.btnBack).setOnClickListener(v -> finish());
        findViewById(R.id.btnAddStock).setOnClickListener(v -> showAddStockDialog());
        findViewById(R.id.btnColorMode).setOnClickListener(v -> showColorModeDialog());
        findViewById(R.id.btnRefreshStocks).setOnClickListener(v -> refreshQuotes());

        containerStocks = findViewById(R.id.containerStocks);
        txtEmptyStocks = findViewById(R.id.txtEmptyStocks);

        loadStocks();
    }

    private final StockMarketManager.StockMarketChangeListener stockListener = () -> {
        runOnUiThread(this::loadStocks);
    };

    @Override
    protected void onResume() {
        super.onResume();
        StockMarketManager.addListener(stockListener);
        loadStocks();
    }

    @Override
    protected void onPause() {
        super.onPause();
        StockMarketManager.removeListener(stockListener);
    }

    private void loadStocks() {
        stockList = StockMarketManager.getSavedStocks(this);
        renderList();
    }

    private void renderList() {
        containerStocks.removeAllViews();

        if (stockList == null || stockList.isEmpty()) {
            txtEmptyStocks.setVisibility(View.VISIBLE);
            return;
        }
        txtEmptyStocks.setVisibility(View.GONE);

        float density = getResources().getDisplayMetrics().density;
        int padH = (int) (16 * density);
        int padV = (int) (14 * density);
        int colorMode = StockMarketManager.getColorMode(this);

        int colorUp = (colorMode == 0) ? Color.parseColor("#22C55E") : Color.parseColor("#EF4444");
        int colorDown = (colorMode == 0) ? Color.parseColor("#EF4444") : Color.parseColor("#22C55E");

        for (int i = 0; i < stockList.size(); i++) {
            final int index = i;
            final StockMarketManager.StockItem item = stockList.get(i);

            if (i > 0) {
                View div = new View(this);
                div.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1));
                div.setBackgroundColor(theme.divider);
                div.setPadding(padH, 0, 0, 0);
                containerStocks.addView(div);
            }

            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(padH, padV, padH, padV);

            // Left Col: Ticker + Company Name
            LinearLayout leftCol = new LinearLayout(this);
            leftCol.setOrientation(LinearLayout.VERTICAL);
            leftCol.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            TextView tvCode = new TextView(this);
            tvCode.setText(item.stockCode);
            tvCode.setTextColor(theme.textPrimary);
            tvCode.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
            tvCode.setTypeface(Typeface.DEFAULT_BOLD);
            leftCol.addView(tvCode);

            TextView tvName = new TextView(this);
            tvName.setText(item.companyName);
            tvName.setTextColor(theme.textMuted);
            tvName.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            leftCol.addView(tvName);

            row.addView(leftCol);

            // Price Col
            LinearLayout priceCol = new LinearLayout(this);
            priceCol.setOrientation(LinearLayout.VERTICAL);
            priceCol.setGravity(Gravity.END);
            priceCol.setPadding(0, 0, (int) (12 * density), 0);

            TextView tvPrice = new TextView(this);
            tvPrice.setText(String.format(Locale.US, "%.2f", item.sharePrice));
            tvPrice.setTextColor(theme.textPrimary);
            tvPrice.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
            tvPrice.setTypeface(Typeface.DEFAULT_BOLD);
            priceCol.addView(tvPrice);

            // Badge with percent change
            TextView tvPct = new TextView(this);
            boolean isPositive = item.netChangePoint >= 0;
            String sign = isPositive ? "+" : "";
            tvPct.setText(sign + String.format(Locale.US, "%.2f%%", item.netChangePercent));
            tvPct.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
            tvPct.setTypeface(Typeface.DEFAULT_BOLD);
            tvPct.setTextColor(Color.WHITE);
            tvPct.setPadding((int) (6 * density), (int) (2 * density), (int) (6 * density), (int) (2 * density));

            GradientDrawable badge = new GradientDrawable();
            badge.setShape(GradientDrawable.RECTANGLE);
            badge.setCornerRadius(4 * density);
            badge.setColor(isPositive ? colorUp : colorDown);
            tvPct.setBackground(badge);

            priceCol.addView(tvPct);
            row.addView(priceCol);

            // Delete Button
            ImageView btnDel = new ImageView(this);
            btnDel.setImageResource(R.drawable.ic_delete);
            btnDel.setColorFilter(theme.textMuted);
            btnDel.setPadding((int) (6 * density), (int) (6 * density), (int) (6 * density), (int) (6 * density));
            btnDel.setLayoutParams(new LinearLayout.LayoutParams((int) (32 * density), (int) (32 * density)));
            btnDel.setOnClickListener(v -> {
                stockList.remove(index);
                StockMarketManager.saveStocks(this, stockList);
                StockMarketManager.syncToWatch(this);
                renderList();
            });
            row.addView(btnDel);

            containerStocks.addView(row);
        }
    }

    private void showColorModeDialog() {
        String[] options = {
                getString(R.string.stock_color_green_up),
                getString(R.string.stock_color_red_up)
        };
        int current = StockMarketManager.getColorMode(this);
        new AlertDialog.Builder(this)
                .setTitle(R.string.stock_color_mode)
                .setSingleChoiceItems(options, current, (dialog, which) -> {
                    StockMarketManager.setColorMode(this, which);
                    StockMarketManager.syncToWatch(this);
                    renderList();
                    dialog.dismiss();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /**
     * Search any ticker instead of picking from a baked-in list.
     *
     * The old dialog offered eight hardcoded companies with hardcoded prices,
     * and its "custom" path asked the user to type the price and the change by
     * hand — numbers that were stale the moment they were entered. Now the
     * symbol comes from a live search and the price from a live quote.
     */
    private void showAddStockDialog() {
        if (stockList != null && stockList.size() >= StockMarketManager.MAX_STOCKS) {
            Toast.makeText(this, R.string.world_clock_max_reached, Toast.LENGTH_SHORT).show();
            return;
        }

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (20 * getResources().getDisplayMetrics().density);
        box.setPadding(pad, pad / 2, pad, pad / 2);

        final EditText edtQuery = new EditText(this);
        edtQuery.setHint(R.string.stock_search_hint);
        edtQuery.setSingleLine(true);
        edtQuery.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH);
        box.addView(edtQuery);

        final TextView status = new TextView(this);
        status.setTextColor(theme.textSecondary);
        status.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        status.setPadding(0, pad / 3, 0, pad / 3);
        status.setText(R.string.stock_search_prompt);
        box.addView(status);

        final LinearLayout results = new LinearLayout(this);
        results.setOrientation(LinearLayout.VERTICAL);
        box.addView(results);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(box);

        final AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.stock_add)
                .setView(scroll)
                .setNegativeButton(android.R.string.cancel, null)
                .create();

        final Runnable runSearch = () -> {
            String q = edtQuery.getText().toString().trim();
            if (q.isEmpty()) return;
            results.removeAllViews();
            status.setText(R.string.stock_searching);
            com.example.dialsender.ble.StockApi.search(q,
                    new com.example.dialsender.ble.StockApi.Callback<List<com.example.dialsender.ble.StockApi.Quote>>() {
                        @Override
                        public void onResult(List<com.example.dialsender.ble.StockApi.Quote> hits) {
                            if (isFinishing()) return;
                            results.removeAllViews();
                            if (hits.isEmpty()) {
                                status.setText(R.string.stock_no_results);
                                return;
                            }
                            status.setText(R.string.stock_pick_result);
                            for (com.example.dialsender.ble.StockApi.Quote hit : hits)
                                results.addView(searchResultRow(hit, dialog));
                        }

                        @Override
                        public void onError(String message) {
                            if (isFinishing()) return;
                            status.setText(getString(R.string.stock_search_failed, message));
                        }
                    });
        };

        edtQuery.setOnEditorActionListener((v, actionId, event) -> {
            runSearch.run();
            return true;
        });
        dialog.setButton(AlertDialog.BUTTON_POSITIVE, getString(R.string.stock_search_action),
                (d, w) -> { });
        dialog.show();
        // Wired after show() so searching does not dismiss the dialog.
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> runSearch.run());
    }

    private View searchResultRow(com.example.dialsender.ble.StockApi.Quote hit, AlertDialog parent) {
        int pad = (int) (12 * getResources().getDisplayMetrics().density);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(pad / 2, pad, pad / 2, pad);
        row.setClickable(true);

        TextView sym = new TextView(this);
        sym.setText(hit.symbol);
        sym.setTextColor(theme.textPrimary);
        sym.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        sym.setTypeface(null, Typeface.BOLD);
        row.addView(sym);

        TextView name = new TextView(this);
        name.setText(hit.exchange.isEmpty() ? hit.label() : hit.label() + " · " + hit.exchange);
        name.setTextColor(theme.textSecondary);
        name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        row.addView(name);

        row.setOnClickListener(v -> {
            parent.dismiss();
            addBySymbol(hit.symbol, hit.label());
        });
        return row;
    }

    /** Fetch the live quote, then store and push the row. */
    private void addBySymbol(String symbol, String name) {
        Toast.makeText(this, R.string.stock_fetching_quote, Toast.LENGTH_SHORT).show();
        com.example.dialsender.ble.StockApi.quote(symbol,
                new com.example.dialsender.ble.StockApi.Callback<com.example.dialsender.ble.StockApi.Quote>() {
                    @Override
                    public void onResult(com.example.dialsender.ble.StockApi.Quote q) {
                        if (isFinishing()) return;
                        if (stockList.size() >= StockMarketManager.MAX_STOCKS) {
                            Toast.makeText(StockMarketActivity.this,
                                    R.string.world_clock_max_reached, Toast.LENGTH_SHORT).show();
                            return;
                        }
                        stockList.add(new StockMarketManager.StockItem(
                                stockList.size() + 1,
                                q.symbol,
                                name == null || name.isEmpty() ? q.label() : name,
                                q.price, q.changePoint, q.changePercent, 0f));
                        StockMarketManager.saveStocks(StockMarketActivity.this, stockList);
                        StockMarketManager.syncToWatch(StockMarketActivity.this);
                        renderList();
                        Toast.makeText(StockMarketActivity.this,
                                R.string.stock_synced, Toast.LENGTH_SHORT).show();
                    }

                    @Override
                    public void onError(String message) {
                        if (isFinishing()) return;
                        Toast.makeText(StockMarketActivity.this,
                                getString(R.string.stock_quote_failed, message),
                                Toast.LENGTH_LONG).show();
                    }
                });
    }

    /** Re-quote every saved row and push the fresh numbers to the watch. */
    private void refreshQuotes() {
        if (stockList == null || stockList.isEmpty()) return;
        List<String> symbols = new java.util.ArrayList<>();
        for (StockMarketManager.StockItem it : stockList)
            symbols.add(it.stockCode);

        Toast.makeText(this, R.string.stock_refreshing, Toast.LENGTH_SHORT).show();
        com.example.dialsender.ble.StockApi.quoteAll(symbols,
                new com.example.dialsender.ble.StockApi.Callback<List<com.example.dialsender.ble.StockApi.Quote>>() {
                    @Override
                    public void onResult(List<com.example.dialsender.ble.StockApi.Quote> quotes) {
                        if (isFinishing()) return;
                        int updated = 0;
                        for (com.example.dialsender.ble.StockApi.Quote q : quotes) {
                            for (StockMarketManager.StockItem it : stockList) {
                                if (!it.stockCode.equalsIgnoreCase(q.symbol)) continue;
                                it.sharePrice = q.price;
                                it.netChangePoint = q.changePoint;
                                it.netChangePercent = q.changePercent;
                                updated++;
                            }
                        }
                        if (updated == 0) {
                            Toast.makeText(StockMarketActivity.this,
                                    R.string.stock_refresh_failed, Toast.LENGTH_LONG).show();
                            return;
                        }
                        StockMarketManager.saveStocks(StockMarketActivity.this, stockList);
                        StockMarketManager.syncToWatch(StockMarketActivity.this);
                        renderList();
                        Toast.makeText(StockMarketActivity.this,
                                R.string.stock_synced, Toast.LENGTH_SHORT).show();
                    }

                    @Override
                    public void onError(String message) {
                        if (isFinishing()) return;
                        Toast.makeText(StockMarketActivity.this,
                                getString(R.string.stock_quote_failed, message),
                                Toast.LENGTH_LONG).show();
                    }
                });
    }
}
