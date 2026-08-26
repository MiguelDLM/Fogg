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

    private static final String[][] POPULAR_STOCKS = {
            {"AAPL", "Apple Inc.", "228.50", "2.45", "1.08"},
            {"GOOGL", "Alphabet Inc.", "165.20", "-1.15", "-0.69"},
            {"MSFT", "Microsoft Corp.", "415.80", "3.80", "0.92"},
            {"NVDA", "NVIDIA Corp.", "124.30", "4.10", "3.41"},
            {"AMZN", "Amazon.com Inc.", "178.60", "-0.80", "-0.45"},
            {"TSLA", "Tesla Inc.", "215.40", "-3.20", "-1.46"},
            {"META", "Meta Platforms", "502.10", "6.30", "1.27"},
            {"BTC/USD", "Bitcoin USD", "64200.00", "1250.00", "1.98"}
    };

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

    private void showAddStockDialog() {
        if (stockList != null && stockList.size() >= StockMarketManager.MAX_STOCKS) {
            Toast.makeText(this, R.string.world_clock_max_reached, Toast.LENGTH_SHORT).show();
            return;
        }

        String[] popularNames = new String[POPULAR_STOCKS.length + 1];
        for (int i = 0; i < POPULAR_STOCKS.length; i++) {
            popularNames[i] = POPULAR_STOCKS[i][0] + " — " + POPULAR_STOCKS[i][1];
        }
        popularNames[POPULAR_STOCKS.length] = "+ " + getString(R.string.reminder_repeat_custom);

        new AlertDialog.Builder(this)
                .setTitle(R.string.stock_add)
                .setItems(popularNames, (dialog, which) -> {
                    if (which < POPULAR_STOCKS.length) {
                        String[] s = POPULAR_STOCKS[which];
                        int newId = stockList.size() + 1;
                        stockList.add(new StockMarketManager.StockItem(
                                newId, s[0], s[1],
                                Float.parseFloat(s[2]), Float.parseFloat(s[3]), Float.parseFloat(s[4]), 1000.0f
                        ));
                        StockMarketManager.saveStocks(this, stockList);
                        StockMarketManager.syncToWatch(this);
                        renderList();
                        Toast.makeText(this, R.string.stock_synced, Toast.LENGTH_SHORT).show();
                    } else {
                        showCustomStockDialog();
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void showCustomStockDialog() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (20 * getResources().getDisplayMetrics().density);
        layout.setPadding(pad, pad / 2, pad, pad);

        final EditText edtSymbol = new EditText(this);
        edtSymbol.setHint(R.string.stock_search_hint);
        layout.addView(edtSymbol);

        final EditText edtPrice = new EditText(this);
        edtPrice.setHint(R.string.stock_price);
        edtPrice.setInputType(android.text.InputType.TYPE_CLASS_NUMBER | android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL);
        layout.addView(edtPrice);

        final EditText edtChange = new EditText(this);
        edtChange.setHint(R.string.stock_change_percent);
        edtChange.setInputType(android.text.InputType.TYPE_CLASS_NUMBER | android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL | android.text.InputType.TYPE_NUMBER_FLAG_SIGNED);
        layout.addView(edtChange);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(layout);

        new AlertDialog.Builder(this)
                .setTitle(R.string.stock_add)
                .setView(scroll)
                .setPositiveButton(R.string.save, (d, w) -> {
                    String sym = edtSymbol.getText().toString().trim().toUpperCase(Locale.US);
                    if (sym.isEmpty()) sym = "STOCK";

                    float price = 100.0f;
                    float chgPct = 0.0f;
                    try {
                        price = Float.parseFloat(edtPrice.getText().toString().trim());
                        chgPct = Float.parseFloat(edtChange.getText().toString().trim());
                    } catch (Exception ignored) {}

                    float chgPt = (price * chgPct) / 100.0f;
                    int newId = stockList.size() + 1;
                    stockList.add(new StockMarketManager.StockItem(newId, sym, sym, price, chgPt, chgPct, 1000.0f));
                    StockMarketManager.saveStocks(this, stockList);
                    StockMarketManager.syncToWatch(this);
                    renderList();
                    Toast.makeText(this, R.string.stock_synced, Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }
}
