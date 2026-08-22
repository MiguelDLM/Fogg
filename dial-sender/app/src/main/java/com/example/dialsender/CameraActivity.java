package com.example.dialsender;

import android.Manifest;
import android.content.ContentValues;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Bundle;
import android.provider.MediaStore;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageCapture;
import androidx.camera.core.ImageCaptureException;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;

import com.example.dialsender.ble.BleManager;
import com.google.common.util.concurrent.ListenableFuture;

import java.text.SimpleDateFormat;
import java.util.Locale;

/**
 * In-app camera. The watch acts as a remote shutter: while this screen is open
 * the watch is told (CAMERA_ENTER) and a press on the watch fires
 * {@link BleManager.CameraListener#onShutter()} → we take a photo. There is also
 * an on-screen shutter button.
 */
public class CameraActivity extends AppCompatActivity {

    private ImageCapture imageCapture;
    private BleManager ble;
    private TextView hint;

    protected void attachBaseContext(android.content.Context base) {
        super.attachBaseContext(LocaleHelper.wrap(base));
    }

    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        PreviewView previewView = new PreviewView(this);
        previewView.setId(View.generateViewId());
        root.addView(previewView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        hint = new TextView(this);
        hint.setText(R.string.camera_hint);
        hint.setTextColor(Color.WHITE);
        hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        hint.setPadding(0, dp(40), 0, 0);
        FrameLayout.LayoutParams hp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        hp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        root.addView(hint, hp);

        TextView shutter = new TextView(this);
        shutter.setText("◉");
        shutter.setTextColor(Color.WHITE);
        shutter.setTextSize(TypedValue.COMPLEX_UNIT_SP, 64);
        shutter.setOnClickListener(v -> takePhoto());
        FrameLayout.LayoutParams sp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        sp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        sp.bottomMargin = dp(40);
        root.addView(shutter, sp);

        setContentView(root);

        ble = BleManager.getInstance(this);

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[] { Manifest.permission.CAMERA }, 10);
        } else {
            startCamera(previewView);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
            @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == 10 && grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            recreate();
        } else {
            Toast.makeText(this, R.string.camera_perm_denied, Toast.LENGTH_SHORT).show();
            finish();
        }
    }

    private void startCamera(PreviewView previewView) {
        ListenableFuture<ProcessCameraProvider> future = ProcessCameraProvider.getInstance(this);
        future.addListener(() -> {
            try {
                ProcessCameraProvider provider = future.get();
                Preview preview = new Preview.Builder().build();
                preview.setSurfaceProvider(previewView.getSurfaceProvider());
                imageCapture = new ImageCapture.Builder().build();
                provider.unbindAll();
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageCapture);
            } catch (Exception e) {
                Toast.makeText(this, getString(R.string.camera_error, e.getMessage()), Toast.LENGTH_LONG).show();
                finish();
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void takePhoto() {
        if (imageCapture == null)
            return;
        String name = "WATCH_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new java.util.Date());
        ContentValues cv = new ContentValues();
        cv.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
        cv.put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg");
        cv.put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/Fogg");
        ImageCapture.OutputFileOptions opts = new ImageCapture.OutputFileOptions.Builder(
                getContentResolver(), MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv).build();
        imageCapture.takePicture(opts, ContextCompat.getMainExecutor(this),
                new ImageCapture.OnImageSavedCallback() {
                    @Override
                    public void onImageSaved(@NonNull ImageCapture.OutputFileResults r) {
                        Toast.makeText(CameraActivity.this, R.string.camera_photo_saved, Toast.LENGTH_SHORT).show();
                    }

                    @Override
                    public void onError(@NonNull ImageCaptureException e) {
                        Toast.makeText(CameraActivity.this, getString(R.string.camera_save_error, e.getMessage()),
                                Toast.LENGTH_SHORT).show();
                    }
                });
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (ble.isSessionReady()) {
            ble.setCameraListener(this::takePhoto);
            ble.sendCamera(BleManager.CAMERA_ENTER);
        } else if (hint != null) {
            hint.setText(R.string.camera_hint_disconnected);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (ble.isSessionReady())
            ble.sendCamera(BleManager.CAMERA_EXIT);
        ble.setCameraListener(null);
    }

    private int dp(int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }
}
