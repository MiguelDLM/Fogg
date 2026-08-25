package com.example.dialsender;

import android.Manifest;
import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.media.AudioAttributes;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.Camera;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ExperimentalGetImage;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;

import com.example.dialsender.ble.BleManager;
import com.example.dialsender.ble.QrDeviceParser;
import com.example.dialsender.theme.ThemeManager;
import com.example.dialsender.views.QrScannerOverlayView;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.mlkit.vision.barcode.BarcodeScanner;
import com.google.mlkit.vision.barcode.BarcodeScannerOptions;
import com.google.mlkit.vision.barcode.BarcodeScanning;
import com.google.mlkit.vision.barcode.common.Barcode;
import com.google.mlkit.vision.common.InputImage;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Fullscreen QR code scanner activity for quickly binding smartwatches by scanning
 * the QR code on the watch face / Settings screen.
 */
public class QrDeviceScannerActivity extends AppCompatActivity {

    public static final String EXTRA_MAC_ADDRESS = "extra_mac_address";
    public static final String EXTRA_DEVICE_NAME = "extra_device_name";
    private static final int REQ_CAMERA_PERMISSION = 101;

    private PreviewView previewView;
    private QrScannerOverlayView overlayView;
    private ImageView btnTorch;
    private TextView txtHint;

    private Camera camera;
    private BarcodeScanner barcodeScanner;
    private ExecutorService cameraExecutor;
    private final AtomicBoolean isProcessing = new AtomicBoolean(false);
    private boolean isTorchOn = false;
    private long lastInvalidToastTime = 0;

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(LocaleHelper.wrap(base));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        ThemeManager.applyTheme(this);
        super.onCreate(savedInstanceState);

        ThemeManager.AppTheme theme = ThemeManager.getTheme(this);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        // 1. Camera preview
        previewView = new PreviewView(this);
        previewView.setId(View.generateViewId());
        root.addView(previewView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        // 2. HUD Viewfinder Overlay
        overlayView = new QrScannerOverlayView(this);
        root.addView(overlayView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        // 3. Top Controls Bar (Back + Title + Torch)
        LinearLayout topBar = new LinearLayout(this);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        topBar.setPadding(dp(16), dp(40), dp(16), dp(16));

        ImageView btnBack = new ImageView(this);
        btnBack.setImageResource(R.drawable.ic_back);
        btnBack.setColorFilter(Color.WHITE);
        btnBack.setPadding(dp(8), dp(8), dp(8), dp(8));
        btnBack.setOnClickListener(v -> finish());
        topBar.addView(btnBack);

        TextView title = new TextView(this);
        title.setText(R.string.qr_scan_title);
        title.setTextColor(Color.WHITE);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        title.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        title.setPadding(dp(12), 0, dp(12), 0);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        topBar.addView(title, titleLp);

        btnTorch = new ImageView(this);
        btnTorch.setImageResource(R.drawable.ic_flashlight);
        btnTorch.setColorFilter(Color.WHITE);
        btnTorch.setPadding(dp(8), dp(8), dp(8), dp(8));
        btnTorch.setOnClickListener(v -> toggleTorch());
        topBar.addView(btnTorch);

        FrameLayout.LayoutParams topBarLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        topBarLp.gravity = Gravity.TOP;
        root.addView(topBar, topBarLp);

        // 4. Bottom Hint Label
        txtHint = new TextView(this);
        txtHint.setText(R.string.qr_scan_hint);
        txtHint.setTextColor(Color.parseColor("#E0E0E0"));
        txtHint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        txtHint.setGravity(Gravity.CENTER);
        txtHint.setPadding(dp(32), dp(12), dp(32), dp(12));
        txtHint.setBackgroundResource(R.drawable.bg_fogg_chip);

        FrameLayout.LayoutParams hintLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        hintLp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        hintLp.bottomMargin = dp(64);
        root.addView(txtHint, hintLp);

        setContentView(root);

        // Setup ML Kit QR Scanner
        BarcodeScannerOptions options = new BarcodeScannerOptions.Builder()
                .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
                .build();
        barcodeScanner = BarcodeScanning.getClient(options);
        cameraExecutor = Executors.newSingleThreadExecutor();

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.CAMERA}, REQ_CAMERA_PERMISSION);
        } else {
            startCamera();
        }
    }

    private void toggleTorch() {
        if (camera != null && camera.getCameraInfo().hasFlashUnit()) {
            isTorchOn = !isTorchOn;
            camera.getCameraControl().enableTorch(isTorchOn);
            ThemeManager.AppTheme theme = ThemeManager.getTheme(this);
            int activeColor = theme != null ? theme.accentPrimary : Color.CYAN;
            btnTorch.setColorFilter(isTorchOn ? activeColor : Color.WHITE);
            Toast.makeText(this, isTorchOn ? R.string.qr_torch_on : R.string.qr_torch_off, Toast.LENGTH_SHORT).show();
        }
    }

    private void startCamera() {
        ListenableFuture<ProcessCameraProvider> cameraProviderFuture = ProcessCameraProvider.getInstance(this);
        cameraProviderFuture.addListener(() -> {
            try {
                ProcessCameraProvider cameraProvider = cameraProviderFuture.get();

                Preview preview = new Preview.Builder().build();
                preview.setSurfaceProvider(previewView.getSurfaceProvider());

                ImageAnalysis imageAnalysis = new ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build();

                imageAnalysis.setAnalyzer(cameraExecutor, this::analyzeImage);

                CameraSelector cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA;
                cameraProvider.unbindAll();
                camera = cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageAnalysis);

            } catch (Exception e) {
                Toast.makeText(this, getString(R.string.camera_error, e.getMessage()), Toast.LENGTH_LONG).show();
                finish();
            }
        }, ContextCompat.getMainExecutor(this));
    }

    @SuppressLint("UnsafeOptInUsageError")
    @ExperimentalGetImage
    private void analyzeImage(@NonNull ImageProxy imageProxy) {
        if (isProcessing.get()) {
            imageProxy.close();
            return;
        }

        android.media.Image mediaImage = imageProxy.getImage();
        if (mediaImage != null) {
            InputImage image = InputImage.fromMediaImage(mediaImage, imageProxy.getImageInfo().getRotationDegrees());
            barcodeScanner.process(image)
                    .addOnSuccessListener(barcodes -> {
                        if (!isProcessing.get() && barcodes != null && !barcodes.isEmpty()) {
                            for (Barcode barcode : barcodes) {
                                String rawValue = barcode.getRawValue();
                                if (rawValue != null) {
                                    handleScannedQr(rawValue);
                                    if (isProcessing.get()) break;
                                }
                            }
                        }
                    })
                    .addOnFailureListener(e -> {
                        // ignore frame analysis failures
                    })
                    .addOnCompleteListener(task -> imageProxy.close());
        } else {
            imageProxy.close();
        }
    }

    private void handleScannedQr(@NonNull String rawContent) {
        QrDeviceParser.DeviceInfo info = QrDeviceParser.parse(rawContent);
        if (info != null) {
            if (isProcessing.compareAndSet(false, true)) {
                triggerHapticFeedback();

                runOnUiThread(() -> {
                    String displayName = info.deviceName != null ? info.deviceName : info.macAddress;
                    Toast.makeText(this, getString(R.string.qr_connecting_to, displayName), Toast.LENGTH_SHORT).show();

                    // Connect via BleManager
                    connectToScannedDevice(info.macAddress);

                    Intent data = new Intent();
                    data.putExtra(EXTRA_MAC_ADDRESS, info.macAddress);
                    if (info.deviceName != null) {
                        data.putExtra(EXTRA_DEVICE_NAME, info.deviceName);
                    }
                    setResult(RESULT_OK, data);
                    finish();
                });
            }
        } else {
            long now = System.currentTimeMillis();
            if (now - lastInvalidToastTime > 3000) {
                lastInvalidToastTime = now;
                runOnUiThread(() -> Toast.makeText(this, R.string.qr_invalid_code, Toast.LENGTH_SHORT).show());
            }
        }
    }

    private void connectToScannedDevice(@NonNull String macAddress) {
        BluetoothManager bm = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        if (bm != null) {
            BluetoothAdapter adapter = bm.getAdapter();
            if (adapter != null && adapter.isEnabled()) {
                try {
                    BluetoothDevice device = adapter.getRemoteDevice(macAddress);
                    BleManager.getInstance(this).connect(device);
                } catch (Exception e) {
                    Toast.makeText(this, "Error: " + e.getMessage(), Toast.LENGTH_LONG).show();
                }
            }
        }
    }

    private void triggerHapticFeedback() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                VibratorManager vm = (VibratorManager) getSystemService(Context.VIBRATOR_MANAGER_SERVICE);
                if (vm != null) {
                    vm.getDefaultVibrator().vibrate(VibrationEffect.createOneShot(80, VibrationEffect.DEFAULT_AMPLITUDE));
                }
            } else {
                Vibrator v = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
                if (v != null) {
                    v.vibrate(80);
                }
            }
        } catch (Exception ignored) {
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_CAMERA_PERMISSION && grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startCamera();
        } else {
            Toast.makeText(this, R.string.camera_perm_denied, Toast.LENGTH_SHORT).show();
            finish();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (cameraExecutor != null) {
            cameraExecutor.shutdown();
        }
        if (barcodeScanner != null) {
            barcodeScanner.close();
        }
    }

    private int dp(float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }
}
