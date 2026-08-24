package com.example.dialsender;

import android.app.Activity;
import android.content.Intent;
import android.util.Log;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.widget.CheckBox;
import android.graphics.Rect;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RadioGroup;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import android.graphics.Typeface;
import android.graphics.BlurMaskFilter;
import android.graphics.drawable.GradientDrawable;
import android.widget.ArrayAdapter;
import android.widget.Spinner;

import com.chaquo.python.Python;
import com.chaquo.python.android.AndroidPlatform;
import com.google.android.material.button.MaterialButton;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.text.DateFormatSymbols;
import com.caverock.androidsvg.SVG;
import com.caverock.androidsvg.PreserveAspectRatio;

public class DialEditorActivity extends AppCompatActivity {

    private static final String TAG = "DialEditorActivity";
    private static final int PICK_IMAGE_CODE = 100;
    private static final int PICK_SVG_CODE = 101;
    private static final int PICK_ANIMATION_CODE = 102;
    private static final int PICK_FONT_CODE = 103;

    private FrameLayout previewContainer;
    private ImageView previewImage;
    private RecyclerView layerRecycler;
    private LinearLayout selectedLayerControls;
    private TextView txtSelectedLayer, txtLayerCount;
    private SeekBar seekScale, seekRotation;
    private Button btnUpload;
    private MaterialButton btnLockBg;

    // Alignment and Numeric position controls
    private EditText edtLayerPosX, edtLayerPosY;
    private Button btnCenterX, btnCenterY, btnCenterAll;
    private LinearLayout layoutAlignmentControls, layoutBgFitControls;
    private Button btnBgFit, btnBgFill, btnBgReset;
    private Button btnCopyStyle;
    private boolean isUpdatingPosText = false;

    private List<DialLayer> layers = new ArrayList<>();
    private int selectedLayerIndex = -1;
    private int pendingElementType = -1;
    private LayerAdapter layerAdapter;
    private String suggestedDialName;

    private int canvasWidth = 466;
    private int canvasHeight = 466;

    // Analog hands: default rotation-pivot tail in source-image pixels measured
    // from the bottom of the (trimmed) hand bitmap. The preview pivot and the
    // ctx byte DialCompiler writes are both derived from this value.
    private static final int HAND_TAIL_PX = 24;

    private static boolean isHandType(int type) {
        return type == DialCompiler.TYPE_ARM_HOUR || type == DialCompiler.TYPE_ARM_MIN
                || type == DialCompiler.TYPE_ARM_SEC;
    }

    /** Vertical rotation pivot of a hand layer, in source-image px from the bottom. */
    private static float handTail(DialLayer l) {
        return l.pivotTail > 0 ? l.pivotTail : HAND_TAIL_PX;
    }

    // Drag state
    private float dragStartX, dragStartY;
    private float layerStartX, layerStartY;
    private boolean isDragging = false;

    // Time group state
    private final List<TimeGroup> timeGroups = new ArrayList<>();
    private TimeGroup pendingGroupTarget      = null;
    private DialLayer pendingGroupSourceLayer = null;
    private DialLayer pendingStyleTarget      = null; // single placeholder awaiting a style
    private List<DialLayer> activeDragGroup             = new ArrayList<>();
    private List<float[]>   groupMemberStartPositions   = new ArrayList<>();

    // Element categories - no more "preview" option since it's auto-generated
    private static final int[][] ELEMENT_CATEGORIES = {
            { DialCompiler.TYPE_DIGITAL_HOUR, DialCompiler.TYPE_DIGITAL_MIN, DialCompiler.TYPE_SECONDS,
                    DialCompiler.TYPE_AMPM, DialCompiler.TYPE_WEEKDAY },
            { DialCompiler.TYPE_DAY, DialCompiler.TYPE_MONTH, DialCompiler.TYPE_YEAR },
            { DialCompiler.TYPE_ARM_HOUR, DialCompiler.TYPE_ARM_MIN, DialCompiler.TYPE_ARM_SEC },
            { DialCompiler.TYPE_STEPS, DialCompiler.TYPE_HEART, DialCompiler.TYPE_CALORIE, DialCompiler.TYPE_DISTANCE },
            { DialCompiler.TYPE_BATTERY, DialCompiler.TYPE_BATT_STRIP, DialCompiler.TYPE_CONNECT,
                    DialCompiler.TYPE_WEATHER, DialCompiler.TYPE_TEMP },
            { DialCompiler.TYPE_ANIM, DialCompiler.TYPE_BERRY, DialCompiler.TYPE_LABEL,
                    DialCompiler.TYPE_PROGRESS1, DialCompiler.TYPE_PROGRESS2 }
    };

    private String[] getCategoryNames() {
        return new String[] {
                getString(R.string.cat_time), getString(R.string.cat_date), getString(R.string.cat_analog),
                getString(R.string.cat_health), getString(R.string.cat_status), getString(R.string.cat_deco)
        };
    }

    protected void attachBaseContext(android.content.Context base) {
        super.attachBaseContext(LocaleHelper.wrap(base));
    }

    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if (!Python.isStarted()) {
            Python.start(new AndroidPlatform(this));
        }

        setContentView(R.layout.activity_dial_editor);

        previewContainer = findViewById(R.id.previewContainer);
        layerRecycler = findViewById(R.id.layerRecycler);
        selectedLayerControls = findViewById(R.id.selectedLayerControls);
        txtSelectedLayer = findViewById(R.id.txtSelectedLayer);
        txtLayerCount = findViewById(R.id.txtLayerCount);
        seekScale = findViewById(R.id.seekScale);
        seekRotation = findViewById(R.id.seekRotation);
        btnUpload = findViewById(R.id.btnUpload);
        btnLockBg = findViewById(R.id.btnLockBackground);
        btnLockBg.setOnClickListener(v -> {
            if (selectedLayerIndex >= 0 && selectedLayerIndex < layers.size()) {
                DialLayer l = layers.get(selectedLayerIndex);
                if (l.layerType == DialLayer.TYPE_BACKGROUND) {
                    l.locked = !l.locked;
                    refreshAll();
                }
            }
        });

        // Alignment & Position View Binding
        edtLayerPosX = findViewById(R.id.edtLayerPosX);
        edtLayerPosY = findViewById(R.id.edtLayerPosY);
        btnCenterX = findViewById(R.id.btnCenterX);
        btnCenterY = findViewById(R.id.btnCenterY);
        btnCenterAll = findViewById(R.id.btnCenterAll);
        layoutAlignmentControls = findViewById(R.id.layoutAlignmentControls);
        layoutBgFitControls = findViewById(R.id.layoutBgFitControls);
        btnBgFit = findViewById(R.id.btnBgFit);
        btnBgFill = findViewById(R.id.btnBgFill);
        btnBgReset = findViewById(R.id.btnBgReset);
        btnCopyStyle = findViewById(R.id.btnCopyStyle);

        btnCenterX.setOnClickListener(v -> centerSelectedLayer(true, false));
        btnCenterY.setOnClickListener(v -> centerSelectedLayer(false, true));
        btnCenterAll.setOnClickListener(v -> centerSelectedLayer(true, true));
        btnBgFit.setOnClickListener(v -> fitBackground(false));
        btnBgFill.setOnClickListener(v -> fitBackground(true));
        btnBgReset.setOnClickListener(v -> resetBackground());
        btnCopyStyle.setOnClickListener(v -> showCopyStyleDialog());

        android.text.TextWatcher posWatcher = new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(android.text.Editable s) {
                if (isUpdatingPosText) return;
                if (selectedLayerIndex < 0 || selectedLayerIndex >= layers.size()) return;
                DialLayer l = layers.get(selectedLayerIndex);
                if (l.locked) return;
                try {
                    if (edtLayerPosX.hasFocus()) {
                        l.posX = Float.parseFloat(edtLayerPosX.getText().toString());
                        updatePreview();
                    } else if (edtLayerPosY.hasFocus()) {
                        l.posY = Float.parseFloat(edtLayerPosY.getText().toString());
                        updatePreview();
                    }
                } catch (Exception ignored) {}
            }
        };
        edtLayerPosX.addTextChangedListener(posWatcher);
        edtLayerPosY.addTextChangedListener(posWatcher);

        findViewById(R.id.btnBackEditor).setOnClickListener(v -> finish());
        findViewById(R.id.btnAddElement).setOnClickListener(v -> showAddElementDialog());

        findViewById(R.id.btnMoveUp).setOnClickListener(v -> {
            if (selectedLayerIndex > 0) {
                DialLayer l = layers.remove(selectedLayerIndex);
                selectedLayerIndex--;
                layers.add(selectedLayerIndex, l);
                refreshAll();
            }
        });
        findViewById(R.id.btnMoveDown).setOnClickListener(v -> {
            if (selectedLayerIndex < layers.size() - 1) {
                DialLayer l = layers.remove(selectedLayerIndex);
                selectedLayerIndex++;
                layers.add(selectedLayerIndex, l);
                refreshAll();
            }
        });

        seekScale.setOnSeekBarChangeListener(new SimpleSeekListener() {
            @Override
            public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                if (fromUser) {
                    if (selectedLayerIndex < 0 || selectedLayerIndex >= layers.size())
                        return;
                    DialLayer layer = layers.get(selectedLayerIndex);
                    if (layer.locked)
                        return;
                    layer.scale = progress / 100.0f;
                    updatePreview();
                }
            }
        });

        seekRotation.setOnSeekBarChangeListener(new SimpleSeekListener() {
            @Override
            public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                if (fromUser) {
                    if (selectedLayerIndex < 0 || selectedLayerIndex >= layers.size())
                        return;
                    DialLayer layer = layers.get(selectedLayerIndex);
                    if (layer.locked)
                        return;
                    layer.rotation = progress;
                    updatePreview();
                }
            }
        });

        btnUpload.setOnClickListener(v -> showNameDialogThenCompile());

        layerAdapter = new LayerAdapter();
        layerRecycler.setLayoutManager(new LinearLayoutManager(this));
        layerRecycler.setAdapter(layerAdapter);

        // Setup preview with touch drag support
        setupPreviewTouch();

        loadEditableDialPreview();
        refreshAll();
    }

    private void loadEditableDialPreview() {
        suggestedDialName = getIntent().getStringExtra("edit_dial_name");
        String editFolder = getIntent().getStringExtra("edit_dial_folder");
        String previewPath = getIntent().getStringExtra("edit_preview_path");

        if (editFolder != null) {
            File folder = new File(editFolder);
            File jsonFile = new File(folder, "dial_desc.json");
            if (jsonFile.exists()) {
                try {
                    String jsonStr = new String(java.nio.file.Files.readAllBytes(jsonFile.toPath()));
                    org.json.JSONObject desc = new org.json.JSONObject(jsonStr);
                    org.json.JSONArray blocks = desc.getJSONArray("blocks");

                    for (int i = 0; i < blocks.length(); i++) {
                        org.json.JSONObject b = blocks.getJSONObject(i);
                        String typeStr = b.getString("type");
                        int elementType = DialCompiler.getTypeFromString(typeStr);

                        if (elementType == DialCompiler.TYPE_PREVIEW) {
                            continue;
                        }

                        String fname = b.optString("fname", null);

                        if (fname == null || fname.isEmpty() || fname.equals("null")) {
                            continue;
                        }

                        File imgFile = new File(folder, fname);
                        if (!imgFile.exists())
                            continue;

                        BitmapFactory.Options opts = new BitmapFactory.Options();
                        opts.inPreferredConfig = b.optString("colsp", "RGB").equals("RGB") ? Bitmap.Config.RGB_565
                                : Bitmap.Config.ARGB_8888;
                        Bitmap fullBmp = BitmapFactory.decodeFile(imgFile.getAbsolutePath(), opts);

                        if (fullBmp == null)
                            continue;

                        int frms = b.optInt("frms", 1);
                        int frameH = fullBmp.getHeight() / frms;
                        Bitmap[] frames = new Bitmap[frms];

                        for (int f = 0; f < frms; f++) {
                            frames[f] = Bitmap.createBitmap(fullBmp, 0, f * frameH, fullBmp.getWidth(), frameH);
                        }

                        DialLayer layer = new DialLayer(
                                frms > 1 ? DialLayer.TYPE_ELEMENT
                                        : (elementType == DialCompiler.TYPE_BACKGROUND ? DialLayer.TYPE_BACKGROUND
                                                : DialLayer.TYPE_ARM),
                                frames[0], getBlockLabel(elementType), elementType);

                        layer.frames = frames;
                        layer.frameCount = frms;
                        layer.isSpriteSheet = frms > 1;
                        layer.posX = b.optInt("posx", 0);
                        layer.posY = b.optInt("posy", 0);
                        layer.scale = 1.0f;
                        if (isHandType(elementType)) {
                            // Preserve the original rotation pivot (ctx byte =
                            // vertical pivot from the image bottom) on re-edit
                            layer.pivotTail = b.optInt("ctx", 0);
                        }

                        layers.add(layer);
                    }
                    Toast.makeText(this, R.string.editor_loaded_elements, Toast.LENGTH_SHORT).show();
                    return;
                } catch (Exception e) {
                    e.printStackTrace();
                    Toast.makeText(this, R.string.editor_edit_parse_error, Toast.LENGTH_SHORT).show();
                }
            }
        }

        // Fallback to preview
        if (previewPath == null)
            return;
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inPreferredConfig = Bitmap.Config.RGB_565;
        Bitmap bmp = BitmapFactory.decodeFile(previewPath, opts);
        if (bmp != null) {
            addBackgroundLayer(ensureRgb565(bmp), DialCompiler.TYPE_BACKGROUND);
            Toast.makeText(this, R.string.editing_dial, Toast.LENGTH_SHORT).show();
        }
    }

    // ===================== TOUCH DRAG ON PREVIEW =====================

    private void setupPreviewTouch() {
        previewContainer.setOnTouchListener((v, event) -> {
            float viewW = v.getWidth();
            float viewH = v.getHeight();
            // Convert view touch coordinates to canvas coordinates
            float tx = event.getX() / viewW * canvasWidth;
            float ty = event.getY() / viewH * canvasHeight;

            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    // Find which layer was tapped
                    int hitIdx = findLayerAt(tx, ty);
                    if (hitIdx >= 0) {
                        selectedLayerIndex = hitIdx;
                        DialLayer tappedLayer = layers.get(hitIdx);
                        if (tappedLayer.pendingStyle) {
                            isDragging = false;
                            showSourcePickerForPendingLayer(tappedLayer);
                            refreshAll();
                            return true;
                        }
                        isDragging = true;
                        dragStartX = tx;
                        dragStartY = ty;
                        layerStartX = tappedLayer.posX;
                        layerStartY = tappedLayer.posY;
                        activeDragGroup.clear();
                        groupMemberStartPositions.clear();
                        if (tappedLayer.timeGroupId != null) {
                            TimeGroup grp = findGroup(tappedLayer.timeGroupId);
                            if (grp != null && grp.mode == TimeGroup.Mode.TOGETHER) {
                                for (DialLayer part : grp.parts) {
                                    activeDragGroup.add(part);
                                    groupMemberStartPositions.add(new float[]{part.posX, part.posY});
                                }
                            }
                        }
                        refreshAll();
                    }
                    return true;

                case MotionEvent.ACTION_MOVE:
                    if (isDragging && selectedLayerIndex >= 0 && selectedLayerIndex < layers.size()) {
                        float dx = tx - dragStartX;
                        float dy = ty - dragStartY;
                        if (!activeDragGroup.isEmpty()) {
                            for (int gi = 0; gi < activeDragGroup.size(); gi++) {
                                activeDragGroup.get(gi).posX = groupMemberStartPositions.get(gi)[0] + dx;
                                activeDragGroup.get(gi).posY = groupMemberStartPositions.get(gi)[1] + dy;
                            }
                        } else {
                            DialLayer l = layers.get(selectedLayerIndex);
                            l.posX = layerStartX + dx;
                            l.posY = layerStartY + dy;
                        }
                        updatePreview();
                    }
                    return true;

                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    isDragging = false;
                    activeDragGroup.clear();
                    groupMemberStartPositions.clear();
                    updateControls();
                    return true;
            }
            return false;
        });
    }

    private int findLayerAt(float x, float y) {
        for (int i = layers.size() - 1; i >= 0; i--) {
            DialLayer l = layers.get(i);
            if (l.locked) continue;
            android.graphics.RectF r = layerScreenRect(l);
            if (r != null && r.contains(x, y)) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Bounding box of a layer exactly as the preview draws it: hands are pinned
     * to the watch centre by their pivot, and digit groups span all rendered
     * digits (not just one frame). Keeping hit-testing and drawing in sync is
     * what makes taps select the layer the user actually touched.
     */
    private android.graphics.RectF layerScreenRect(DialLayer l) {
        if (l.isColonSeparator) {
            float[] cd = colonDimensions(l);
            return new android.graphics.RectF(l.posX, l.posY, l.posX + cd[0], l.posY + cd[1]);
        }
        if (l.pendingStyle) {
            return new android.graphics.RectF(l.posX, l.posY, l.posX + 40, l.posY + 60);
        }
        Bitmap bmp = getPreviewBitmap(l);
        if (bmp == null) return null;
        float w = bmp.getWidth() * l.scale;
        float h = bmp.getHeight() * l.scale;
        float x = l.posX, y = l.posY;
        if (isHandType(l.nativeElementType)) {
            x = canvasWidth / 2f - (bmp.getWidth() / 2f) * l.scale;
            y = canvasHeight / 2f - (bmp.getHeight() - handTail(l)) * l.scale;
        }
        return new android.graphics.RectF(x, y, x + w, y + h);
    }

    /**
     * Colon separator display size, derived from the styled digit sibling so the
     * colon always matches the digits next to it. {width, height}.
     */
    private float[] colonDimensions(DialLayer colon) {
        float h = 60, w = 16;
        TimeGroup g = colon.timeGroupId != null ? findGroup(colon.timeGroupId) : null;
        if (g != null) {
            DialLayer donor = g.styledSiblingOf(colon);
            if (donor != null && donor.frames != null && donor.frames.length > 0) {
                h = donor.frames[0].getHeight() * donor.scale;
                w = Math.max(8f, h * 0.28f);
            }
        }
        return new float[] { w, h };
    }

    /** Renders the ":" separator as a bitmap (two dots) sized by colonDimensions(). */
    private Bitmap colonBitmapFor(DialLayer colon) {
        float[] cd = colonDimensions(colon);
        int w = Math.max(4, Math.round(cd[0]));
        int h = Math.max(8, Math.round(cd[1]));
        Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(Color.WHITE);
        float r = Math.max(2f, Math.min(w * 0.28f, h * 0.09f));
        c.drawCircle(w / 2f, h * 0.36f, r, p);
        c.drawCircle(w / 2f, h * 0.64f, r, p);
        return bmp;
    }

    /**
     * Returns a localized label for a block type.
     */
    private String getBlockLabel(int type) {
        return getString(DialCompiler.blockTypeLabelRes(type));
    }

    // ===================== LAYER PANEL =====================

    private class LayerAdapter extends RecyclerView.Adapter<LayerAdapter.VH> {
        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_layer, parent, false);
            return new VH(v);
        }

        @Override
        public void onBindViewHolder(@NonNull VH holder, int position) {
            int layerIdx = layers.size() - 1 - position;
            DialLayer layer = layers.get(layerIdx);

            holder.txtName.setText(layer.name);
            holder.txtType.setText(DialCompiler.blockTypeToString(layer.nativeElementType));

            Bitmap thumb = (layer.frames != null && layer.frames.length > 0) ? layer.frames[0] : layer.icon;
            if (thumb != null) {
                holder.imgThumb.setImageBitmap(thumb);
            } else {
                holder.imgThumb.setImageBitmap(null);
            }

            if (layer.isSpriteSheet && layer.frameCount > 1) {
                holder.txtFrameBadge.setVisibility(View.VISIBLE);
                holder.txtFrameBadge.setText(layer.frameCount + "f");
            } else {
                holder.txtFrameBadge.setVisibility(View.GONE);
            }

            boolean isSelected = (layerIdx == selectedLayerIndex);
            holder.itemView.setAlpha(isSelected ? 1.0f : (layer.locked ? 0.5f : 0.7f));
            holder.itemView.setBackgroundColor(getResources().getColor(
                    isSelected ? R.color.bg_elevated : R.color.bg_card));

            // Lock toggle
            holder.btnLock.setText(layer.locked ? "🔒" : "🔓");
            holder.btnLock.setOnClickListener(v -> {
                layer.locked = !layer.locked;
                refreshAll();
            });

            holder.itemView.setOnClickListener(v -> {
                selectedLayerIndex = layerIdx;
                refreshAll();
            });

            holder.itemView.setOnLongClickListener(v -> {
                showLayerContextMenu(layer, layerIdx);
                return true;
            });

            holder.btnDelete.setOnClickListener(v -> {
                if (layer.pendingStyle) {
                    showPendingLayerMenu(layer);
                } else {
                    layers.remove(layerIdx);
                    if (selectedLayerIndex >= layers.size()) {
                        selectedLayerIndex = layers.size() - 1;
                    }
                    refreshAll();
                }
            });
        }

        @Override
        public int getItemCount() {
            return layers.size();
        }

        class VH extends RecyclerView.ViewHolder {
            ImageView imgThumb;
            TextView txtName, txtType, txtFrameBadge, btnDelete, btnLock;

            VH(View v) {
                super(v);
                imgThumb = v.findViewById(R.id.imgLayerThumb);
                txtName = v.findViewById(R.id.txtLayerName);
                txtType = v.findViewById(R.id.txtLayerType);
                txtFrameBadge = v.findViewById(R.id.txtFrameBadge);
                btnLock = v.findViewById(R.id.btnLockLayer);
                btnDelete = v.findViewById(R.id.btnDeleteLayer);
            }
        }
    }

    // ===================== ADD ELEMENT DIALOG =====================

    private void showAddElementDialog() {
        // A fresh add-element flow must not adopt into a stale placeholder
        pendingStyleTarget      = null;
        pendingGroupTarget      = null;
        pendingGroupSourceLayer = null;

        String[] catNames = getCategoryNames();
        // Top-level: Background, Animated background, Scale (hour ring), then categories
        String[] topLevel = new String[catNames.length + 3];
        topLevel[0] = getString(R.string.bg_image);
        topLevel[1] = getString(R.string.add_animation);
        topLevel[2] = getString(R.string.cat_scale);
        System.arraycopy(catNames, 0, topLevel, 3, catNames.length);

        new AlertDialog.Builder(this)
                .setTitle(R.string.add_layer)
                .setItems(topLevel, (dialog, which) -> {
                    if (which == 0) {
                        pendingElementType = DialCompiler.TYPE_BACKGROUND;
                        pickImageFromGallery();
                    } else if (which == 1) {
                        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
                        intent.setType("*/*");
                        String[] mimeTypes = { "image/gif", "video/mp4", "video/avi", "video/3gpp", "video/*" };
                        intent.putExtra(Intent.EXTRA_MIME_TYPES, mimeTypes);
                        startActivityForResult(intent, PICK_ANIMATION_CODE);
                    } else if (which == 2) {
                        showScalePicker(); // Watch face ring / scale
                    } else {
                        showCategoryPicker(which - 3);
                    }
                })
                .show();
    }

    private void showScalePicker() {
        // Load scale presets from time/pointer/scale/
        List<String> presetPaths = new ArrayList<>();
        String scaleBase = "dial_customize_2/time/pointer/scale";
        String[] scaleFiles = safeListAssets(scaleBase);
        for (String f : scaleFiles) {
            if (f.endsWith(".png")) {
                presetPaths.add(scaleBase + "/" + f);
            }
        }

        if (presetPaths.isEmpty()) {
            pendingElementType = DialCompiler.TYPE_BERRY;
            pickImageFromGallery();
            return;
        }

        // Gallery-style visual grid (same as element picker)
        View galleryView = LayoutInflater.from(this).inflate(R.layout.dialog_preset_gallery, null);
        TextView titleView = galleryView.findViewById(R.id.txtGalleryTitle);
        titleView.setText(getString(R.string.cat_scale));
        RecyclerView grid = galleryView.findViewById(R.id.gridPresets);
        grid.setLayoutManager(new GridLayoutManager(this, 3));

        List<Object[]> scaleItems = new ArrayList<>();
        scaleItems.add(new Object[] { getString(R.string.from_gallery), null, null });
        for (int i = 0; i < presetPaths.size(); i++) {
            String p = presetPaths.get(i);
            Bitmap thumb = null;
            try {
                InputStream is = getAssets().open(p);
                thumb = BitmapFactory.decodeStream(is);
                is.close();
            } catch (Exception ignored) {
            }
            scaleItems.add(new Object[] { getString(R.string.style_label, String.valueOf(i + 1)), p, thumb });
        }

        AlertDialog scaleDialog = new AlertDialog.Builder(this)
                .setView(galleryView)
                .setNegativeButton(R.string.cancel, null)
                .create();

        grid.setAdapter(new RecyclerView.Adapter<PresetVH>() {
            @NonNull
            @Override
            public PresetVH onCreateViewHolder(@NonNull ViewGroup parent, int vt) {
                View v = LayoutInflater.from(parent.getContext())
                        .inflate(R.layout.item_preset_thumb, parent, false);
                return new PresetVH(v);
            }

            @Override
            public void onBindViewHolder(@NonNull PresetVH h, int pos) {
                Object[] item = scaleItems.get(pos);
                h.txtName.setText((String) item[0]);
                Bitmap t = (Bitmap) item[2];
                if (t != null)
                    h.imgThumb.setImageBitmap(t);
                else if (pos == 0)
                    h.imgThumb.setImageResource(android.R.drawable.ic_menu_gallery);
                else
                    h.imgThumb.setImageBitmap(null);
                h.itemView.setOnClickListener(v -> {
                    scaleDialog.dismiss();
                    String path = (String) item[1];
                    if (path == null) {
                        pendingElementType = DialCompiler.TYPE_BERRY;
                        pickImageFromGallery();
                    } else {
                        loadScalePreset(path);
                    }
                });
            }

            @Override
            public int getItemCount() {
                return scaleItems.size();
            }
        });
        scaleDialog.show();
    }

    private void loadScalePreset(String assetPath) {
        try {
            InputStream is = getAssets().open(assetPath);
            Bitmap bmp = BitmapFactory.decodeStream(is);
            is.close();
            if (bmp == null) {
                Toast.makeText(this, R.string.no_preset_images, Toast.LENGTH_SHORT).show();
                return;
            }
            // Scale is typically a full-size decoration overlay
            String name = getString(R.string.blk_scale);
            DialLayer layer = new DialLayer(DialLayer.TYPE_ELEMENT, bmp, name, DialCompiler.TYPE_BERRY);
            layer.frames = new Bitmap[] { bmp };
            layer.frameCount = 1;
            layer.isSpriteSheet = false;
            layer.alpha = 1.0f;
            layer.posX = 0;
            layer.posY = 0;
            // Auto-scale to canvas
            layer.scale = (float) canvasWidth / bmp.getWidth();

            layers.add(layer);
            selectedLayerIndex = layers.size() - 1;
            refreshAll();

            Toast.makeText(this, "✓ " + name, Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(this, "Error: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private void showTimeFormatDialog() {
        final TimeGroup.Format[] selectedFormat = {TimeGroup.Format.HH_MM};
        final TimeGroup.Mode[]   selectedMode   = {TimeGroup.Mode.TOGETHER};

        View v = LayoutInflater.from(this).inflate(R.layout.dialog_time_format, null);
        RadioGroup rgFormat = v.findViewById(R.id.rgTimeFormat);
        RadioGroup rgMode   = v.findViewById(R.id.rgTimeMode);

        rgFormat.setOnCheckedChangeListener((g, id) ->
                selectedFormat[0] = (id == R.id.rbHHMMSS)
                        ? TimeGroup.Format.HH_MM_SS : TimeGroup.Format.HH_MM);
        rgMode.setOnCheckedChangeListener((g, id) ->
                selectedMode[0] = (id == R.id.rbParts)
                        ? TimeGroup.Mode.PARTS : TimeGroup.Mode.TOGETHER);

        new AlertDialog.Builder(this)
                .setTitle(R.string.add_time_group)
                .setView(v)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.cont, (d, w) ->
                        createTimeGroup(selectedFormat[0], selectedMode[0]))
                .show();
    }

    private void createTimeGroup(TimeGroup.Format format, TimeGroup.Mode mode) {
        String groupId = java.util.UUID.randomUUID().toString();
        boolean withSeconds = (format == TimeGroup.Format.HH_MM_SS);

        int digitW = 40;
        int digitH = 60;
        int colonW = 16;
        int cx = canvasWidth  / 2;
        int cy = canvasHeight / 2;

        int totalW = digitW * 2 + colonW + (withSeconds ? digitW + colonW : 0);
        int startX = cx - totalW / 2;
        int topY   = cy - digitH / 2;

        DialLayer hoursLayer = new DialLayer(DialLayer.TYPE_ELEMENT, null, getString(R.string.lbl_hours));
        hoursLayer.nativeElementType = DialCompiler.TYPE_DIGITAL_HOUR;
        hoursLayer.posX         = startX;
        hoursLayer.posY         = topY;
        hoursLayer.timeGroupId  = groupId;
        hoursLayer.pendingStyle = true;

        DialLayer colon1 = new DialLayer(DialLayer.TYPE_ELEMENT, null, ":");
        colon1.posX              = startX + digitW;
        colon1.posY              = topY;
        colon1.timeGroupId       = groupId;
        colon1.isColonSeparator  = true;

        DialLayer minutesLayer = new DialLayer(DialLayer.TYPE_ELEMENT, null, getString(R.string.lbl_minutes));
        minutesLayer.nativeElementType = DialCompiler.TYPE_DIGITAL_MIN;
        minutesLayer.posX         = startX + digitW + colonW;
        minutesLayer.posY         = topY;
        minutesLayer.timeGroupId  = groupId;
        minutesLayer.pendingStyle = true;

        List<DialLayer> parts = new ArrayList<>();
        parts.add(hoursLayer);
        parts.add(colon1);
        parts.add(minutesLayer);

        layers.add(hoursLayer);
        layers.add(colon1);
        layers.add(minutesLayer);

        if (withSeconds) {
            DialLayer colon2 = new DialLayer(DialLayer.TYPE_ELEMENT, null, ":");
            colon2.posX             = startX + digitW + colonW + digitW;
            colon2.posY             = topY;
            colon2.timeGroupId      = groupId;
            colon2.isColonSeparator = true;

            DialLayer secondsLayer = new DialLayer(DialLayer.TYPE_ELEMENT, null, getString(R.string.lbl_seconds));
            secondsLayer.nativeElementType = DialCompiler.TYPE_SECONDS;
            secondsLayer.posX         = startX + digitW + colonW + digitW + colonW;
            secondsLayer.posY         = topY;
            secondsLayer.timeGroupId  = groupId;
            secondsLayer.pendingStyle = true;

            parts.add(colon2);
            parts.add(secondsLayer);
            layers.add(colon2);
            layers.add(secondsLayer);
        }

        TimeGroup group = new TimeGroup(groupId, format, mode, parts);
        timeGroups.add(group);
        selectedLayerIndex = layers.size() - 1;
        refreshAll();

        if (mode == TimeGroup.Mode.TOGETHER) {
            showSourcePickerForGroup(hoursLayer, group);
        }
    }

    private void showSourcePickerForGroup(DialLayer hoursLayer, TimeGroup group) {
        pendingGroupTarget      = group;
        pendingGroupSourceLayer = hoursLayer;
        showSourcePicker(hoursLayer.nativeElementType);
    }

    private TimeGroup findGroup(String groupId) {
        for (TimeGroup g : timeGroups) {
            if (g.groupId.equals(groupId)) return g;
        }
        return null;
    }

    private void showLayerContextMenu(DialLayer layer, int layerIdx) {
        if (layer.pendingStyle) {
            showPendingLayerMenu(layer);
            return;
        }
        List<String> options = new ArrayList<>();
        if (layer.timeGroupId != null && !layer.isColonSeparator) {
            options.add(getString(R.string.ungroup));
        }
        options.add(getString(R.string.save_as_preset));
        new AlertDialog.Builder(DialEditorActivity.this)
                .setTitle(layer.name)
                .setItems(options.toArray(new String[0]), (d, w) -> {
                    String chosen = options.get(w);
                    if (getString(R.string.ungroup).equals(chosen)) {
                        ungroupTimeGroup(layer.timeGroupId);
                    } else {
                        saveLayerAsPreset(layer);
                    }
                })
                .show();
    }

    private void showPendingLayerMenu(DialLayer layer) {
        new AlertDialog.Builder(DialEditorActivity.this)
                .setTitle(layer.name)
                .setItems(new String[]{
                        getString(R.string.assign_style),
                        getString(R.string.delete_layer)
                }, (d, w) -> {
                    if (w == 0) showSourcePickerForPendingLayer(layer);
                    else        removePendingLayer(layer);
                })
                .show();
    }

    private void ungroupTimeGroup(String groupId) {
        TimeGroup group = findGroup(groupId);
        if (group == null) return;
        layers.removeIf(l -> l.isColonSeparator && groupId.equals(l.timeGroupId));
        for (DialLayer l : group.parts) {
            if (!l.isColonSeparator) {
                l.timeGroupId  = null;
                l.pendingStyle = false;
            }
        }
        timeGroups.remove(group);
        refreshAll();
    }

    private void removePendingLayer(DialLayer layer) {
        if (pendingStyleTarget == layer) pendingStyleTarget = null;
        String gid = layer.timeGroupId;
        if (gid != null) {
            TimeGroup group = findGroup(gid);
            if (group != null) {
                group.parts.remove(layer);
                group.parts.removeIf(l -> l.isColonSeparator);
                layers.removeIf(l -> l.isColonSeparator && gid.equals(l.timeGroupId));
                if (group.parts.isEmpty()) timeGroups.remove(group);
            }
        }
        layers.remove(layer);
        if (selectedLayerIndex >= layers.size()) selectedLayerIndex = layers.size() - 1;
        refreshAll();
    }

    private void showSourcePickerForPendingLayer(DialLayer layer) {
        pendingGroupTarget      = null;
        pendingGroupSourceLayer = null;

        if (layer.timeGroupId != null) {
            TimeGroup group = findGroup(layer.timeGroupId);
            if (group != null) {
                DialLayer donor = group.styledSiblingOf(layer);
                if (donor != null) {
                    showSourcePickerWithAutoApply(layer, donor, group);
                    return;
                }
            }
        }
        // The chosen style must fill this placeholder, not create a new layer
        pendingStyleTarget = layer;
        showSourcePicker(layer.nativeElementType);
    }

    private void showSourcePickerWithAutoApply(DialLayer target, DialLayer donor, TimeGroup group) {
        int elementType = target.nativeElementType;
        List<String> presetPaths = findPresetFolders(elementType);

        View galleryView = LayoutInflater.from(this).inflate(R.layout.dialog_preset_gallery, null);
        TextView titleView = galleryView.findViewById(R.id.txtGalleryTitle);
        titleView.setText(getString(R.string.select_style) + " — " + getBlockLabel(elementType));
        RecyclerView grid = galleryView.findViewById(R.id.gridPresets);
        grid.setLayoutManager(new GridLayoutManager(this, 3));

        List<Object[]> items = new ArrayList<>();
        items.add(new Object[]{ "🔁 " + getString(R.string.same_as) + " " + donor.name, "__COPY__", null });
        items.add(new Object[]{ getString(R.string.from_gallery), null, null });
        if (isDigitElementType(elementType)) {
            items.add(new Object[]{ getString(R.string.from_font), "__FONT__", null });
        }
        items.add(new Object[]{ getString(R.string.from_svg), "__SVG__", null });
        for (String p : presetPaths) {
            Bitmap thumb = loadPresetThumbnail(p, elementType);
            int lastSlash = p.lastIndexOf('/');
            String styleName = lastSlash >= 0 ? p.substring(lastSlash + 1) : p;
            items.add(new Object[]{ styleName, p, thumb });
        }

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setView(galleryView)
                .setNegativeButton(R.string.cancel, null)
                .create();

        grid.setAdapter(new RecyclerView.Adapter<PresetVH>() {
            @NonNull @Override
            public PresetVH onCreateViewHolder(@NonNull ViewGroup parent, int vt) {
                View v = LayoutInflater.from(parent.getContext())
                        .inflate(R.layout.item_preset_thumb, parent, false);
                return new PresetVH(v);
            }

            @Override
            public void onBindViewHolder(@NonNull PresetVH h, int pos) {
                Object[] item = items.get(pos);
                h.txtName.setText((String) item[0]);
                Bitmap thumb = (Bitmap) item[2];
                if ("__COPY__".equals(item[1]) && donor.frames != null && donor.frames.length > 0) {
                    h.imgThumb.setImageBitmap(donor.frames[0]);
                } else if (thumb != null) {
                    h.imgThumb.setImageBitmap(thumb);
                } else {
                    h.imgThumb.setImageBitmap(null);
                }
                h.itemView.setOnClickListener(v -> {
                    dialog.dismiss();
                    String path = (String) item[1];
                    if ("__COPY__".equals(path)) {
                        target.frames        = donor.frames != null ? donor.frames.clone() : null;
                        target.icon          = donor.icon;
                        target.scale         = donor.scale;
                        target.frameCount    = donor.frameCount;
                        target.isSpriteSheet = donor.isSpriteSheet;
                        target.pendingStyle  = false;
                        relayoutTimeGroup(group);
                        refreshAll();
                    } else if (path == null) {
                        pendingStyleTarget = target;
                        pendingElementType = elementType;
                        pickImageFromGallery();
                    } else if ("__FONT__".equals(path)) {
                        pendingStyleTarget = target;
                        showFontCreator(elementType);
                    } else if ("__SVG__".equals(path)) {
                        pendingStyleTarget = target;
                        pendingElementType = elementType;
                        pickSvgFromGallery();
                    } else {
                        pendingStyleTarget = target;
                        loadPreset(path, elementType);
                    }
                });
            }

            @Override public int getItemCount() { return items.size(); }
        });

        dialog.show();
    }

    private void showCategoryPicker(int categoryIndex) {
        if (categoryIndex == 0) {
            showTimeFormatDialog();
            return;
        }

        String[] catNames = getCategoryNames();
        int[] types = ELEMENT_CATEGORIES[categoryIndex];
        String[] labels = new String[types.length];
        for (int i = 0; i < types.length; i++) {
            int frames = DialCompiler.getDefaultFrameCount(types[i]);
            String frameLabel = frames > 1 ? " (" + frames + " frames)" : "";
            labels[i] = getBlockLabel(types[i]) + frameLabel;
        }

        new AlertDialog.Builder(this)
                .setTitle(catNames[categoryIndex])
                .setItems(labels, (dialog, which) -> {
                    int selectedType = types[which];
                    showSourcePicker(selectedType);
                })
                .show();
    }

    private void showSourcePicker(int elementType) {
        List<String> presetPaths = findPresetFolders(elementType);

        // Build gallery dialog with visual thumbnails
        View galleryView = LayoutInflater.from(this).inflate(R.layout.dialog_preset_gallery, null);
        TextView title = galleryView.findViewById(R.id.txtGalleryTitle);
        title.setText(getString(R.string.select_style) + " \u2014 " + getBlockLabel(elementType));
        RecyclerView grid = galleryView.findViewById(R.id.gridPresets);
        grid.setLayoutManager(new GridLayoutManager(this, 3));

        // Build items: gallery option + builders + font option + presets
        List<Object[]> items = new ArrayList<>();

        // Hand Builder option for analog hands
        if (isHandType(elementType)) {
            items.add(new Object[] { getString(R.string.from_hand_builder), "__HAND_BUILDER__", null });
        }

        // Battery Builder option for battery
        if (elementType == DialCompiler.TYPE_BATT_STRIP || elementType == DialCompiler.TYPE_BATTERY) {
            items.add(new Object[] { getString(R.string.from_battery_builder), "__BATTERY_BUILDER__", null });
        }

        // Weather Builder option for weather
        if (elementType == DialCompiler.TYPE_WEATHER) {
            items.add(new Object[] { getString(R.string.from_weather_builder), "__WEATHER_BUILDER__", null });
        }

        items.add(new Object[] { getString(R.string.from_gallery), null, null });

        // Font creator option for digit types
        boolean isDigitType = isDigitElementType(elementType);
        if (isDigitType) {
            items.add(new Object[] { getString(R.string.from_font), "__FONT__", null });
        }

        // SVG Option for all supported types
        items.add(new Object[] { getString(R.string.from_svg), "__SVG__", null });

        // Load thumbnails for presets
        for (String p : presetPaths) {
            Bitmap thumb = loadPresetThumbnail(p, elementType);
            int lastSlash = p.lastIndexOf('/');
            String styleName = lastSlash >= 0 ? p.substring(lastSlash + 1) : p;
            items.add(new Object[] { styleName, p, thumb });
        }

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setView(galleryView)
                .setNegativeButton(R.string.cancel, null)
                .create();

        grid.setAdapter(new RecyclerView.Adapter<PresetVH>() {
            @NonNull
            @Override
            public PresetVH onCreateViewHolder(@NonNull ViewGroup parent, int vt) {
                View v = LayoutInflater.from(parent.getContext())
                        .inflate(R.layout.item_preset_thumb, parent, false);
                return new PresetVH(v);
            }

            @Override
            public void onBindViewHolder(@NonNull PresetVH h, int pos) {
                Object[] item = items.get(pos);
                h.txtName.setText((String) item[0]);
                Bitmap thumb = (Bitmap) item[2];
                if (thumb != null) {
                    h.imgThumb.setImageBitmap(thumb);
                } else if ("__HAND_BUILDER__".equals(item[1]) || "__BATTERY_BUILDER__".equals(item[1])
                        || "__WEATHER_BUILDER__".equals(item[1]) || "__FONT__".equals(item[1])) {
                    h.imgThumb.setImageResource(android.R.drawable.ic_menu_edit);
                } else if (item[1] == null) {
                    h.imgThumb.setImageResource(android.R.drawable.ic_menu_gallery);
                } else {
                    h.imgThumb.setImageBitmap(null);
                }
                h.itemView.setOnClickListener(v -> {
                    dialog.dismiss();
                    String path = (String) item[1];
                    if (path == null) {
                        pendingElementType = elementType;
                        pickImageFromGallery();
                    } else if ("__HAND_BUILDER__".equals(path)) {
                        showHandSetBuilder();
                    } else if ("__BATTERY_BUILDER__".equals(path)) {
                        showBatteryGenerator();
                    } else if ("__WEATHER_BUILDER__".equals(path)) {
                        showWeatherGenerator();
                    } else if ("__FONT__".equals(path)) {
                        showFontCreator(elementType);
                    } else if ("__SVG__".equals(path)) {
                        pendingElementType = elementType;
                        pickSvgFromGallery();
                    } else if (path.endsWith(".svg")) {
                        // Open SVG Editor with asset
                        showSVGEditor(elementType, path);
                    } else {
                        loadPreset(path, elementType);
                    }
                });
            }

            @Override
            public int getItemCount() {
                return items.size();
            }
        });

        dialog.show();
    }

    private static class PresetVH extends RecyclerView.ViewHolder {
        ImageView imgThumb;
        TextView txtName;

        PresetVH(View v) {
            super(v);
            imgThumb = v.findViewById(R.id.imgPresetThumb);
            txtName = v.findViewById(R.id.txtPresetName);
        }
    }

    private boolean isDigitElementType(int type) {
        return type == DialCompiler.TYPE_DIGITAL_HOUR || type == DialCompiler.TYPE_DIGITAL_MIN ||
                type == DialCompiler.TYPE_SECONDS || type == DialCompiler.TYPE_HOUR_HI ||
                type == DialCompiler.TYPE_HOUR_LO || type == DialCompiler.TYPE_MIN_HI ||
                type == DialCompiler.TYPE_MIN_LO || type == DialCompiler.TYPE_DAY ||
                type == DialCompiler.TYPE_MONTH || type == DialCompiler.TYPE_YEAR ||
                type == DialCompiler.TYPE_STEPS || type == DialCompiler.TYPE_HEART ||
                type == DialCompiler.TYPE_CALORIE || type == DialCompiler.TYPE_DISTANCE ||
                type == DialCompiler.TYPE_BATTERY || type == DialCompiler.TYPE_TEMP ||
                type == DialCompiler.TYPE_BERRY || type == DialCompiler.TYPE_LABEL;
    }

    /**
     * Load a representative thumbnail from a preset folder/file.
     */
    private Bitmap loadPresetThumbnail(String assetPath, int elementType) {
        try {
            if (assetPath.endsWith(".png")) {
                InputStream is = getAssets().open(assetPath);
                Bitmap bmp = BitmapFactory.decodeStream(is);
                is.close();
                return bmp;
            } else if (assetPath.endsWith(".svg")) {
                // Render SVG thumbnail
                InputStream is = getAssets().open(assetPath);
                com.caverock.androidsvg.SVG svg = com.caverock.androidsvg.SVG.getFromInputStream(is);
                is.close();
                // Render to small thumb
                Bitmap bmp = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888);
                Canvas c = new Canvas(bmp);
                // Fit to 100x100
                float docW = svg.getDocumentWidth();
                float docH = svg.getDocumentHeight();
                if (docW <= 0)
                    docW = 100;
                if (docH <= 0)
                    docH = 100;
                float scale = Math.min(100f / docW, 100f / docH);
                c.scale(scale, scale);
                svg.renderToCanvas(c);
                return bmp;
            }
            String thumbFile;
            if (elementType == DialCompiler.TYPE_AMPM) {
                thumbFile = "am.png";
            } else if (elementType == DialCompiler.TYPE_WEEKDAY) {
                thumbFile = "2.png";
            } else if (elementType == DialCompiler.TYPE_DISTANCE) {
                // For distance, try to combine 1, 2, dot, 3 to show "12.3"
                try {
                    Bitmap d1 = BitmapFactory.decodeStream(getAssets().open(assetPath + "/1.png"));
                    Bitmap d2 = BitmapFactory.decodeStream(getAssets().open(assetPath + "/2.png"));
                    Bitmap dot = BitmapFactory.decodeStream(getAssets().open(assetPath + "/10.png"));
                    Bitmap d3 = BitmapFactory.decodeStream(getAssets().open(assetPath + "/3.png"));
                    if (d1 != null && d2 != null && dot != null && d3 != null) {
                        int tw = d1.getWidth() + d2.getWidth() + dot.getWidth() + d3.getWidth();
                        int th = Math.max(d1.getHeight(), Math.max(d2.getHeight(), Math.max(dot.getHeight(), d3.getHeight())));
                        Bitmap combined = Bitmap.createBitmap(tw, th, Bitmap.Config.ARGB_8888);
                        Canvas c = new Canvas(combined);
                        c.drawBitmap(d1, 0, 0, null);
                        c.drawBitmap(d2, d1.getWidth(), 0, null);
                        c.drawBitmap(dot, d1.getWidth() + d2.getWidth(), 0, null);
                        c.drawBitmap(d3, d1.getWidth() + d2.getWidth() + dot.getWidth(), 0, null);
                        return combined;
                    }
                } catch (Exception ignored) {
                }
                thumbFile = "5.png";
            } else {
                thumbFile = "5.png";
            }
            try {
                InputStream is = getAssets().open(assetPath + "/" + thumbFile);
                Bitmap bmp = BitmapFactory.decodeStream(is);
                is.close();
                return bmp;
            } catch (Exception e) {
                InputStream is = getAssets().open(assetPath + "/0.png");
                Bitmap bmp = BitmapFactory.decodeStream(is);
                is.close();
                return bmp;
            }
        } catch (Exception e) {
            return null;
        }
    }

    // ===================== FONT-BASED DIGIT CREATOR =====================

    private void showFontCreator(int elementType) {
        View view = LayoutInflater.from(this).inflate(R.layout.dialog_font_creator, null);
        ImageView imgPreview = view.findViewById(R.id.imgFontPreview);
        Spinner spinnerFont = view.findViewById(R.id.spinnerFont);
        SeekBar seekSize = view.findViewById(R.id.seekDigitSize);
        SeekBar seekGlow = view.findViewById(R.id.seekGlow);
        SeekBar seekBorder = view.findViewById(R.id.seekBorder);
        SeekBar seekSpacing = view.findViewById(R.id.seekSpacing);
        Spinner spinnerLang = view.findViewById(R.id.spinnerLang);
        View containerLang = view.findViewById(R.id.containerLang);
        EditText edtCustomText = view.findViewById(R.id.edtCustom);
        View containerCustomText = view.findViewById(R.id.containerCustom);
        View lblCustom = view.findViewById(R.id.lblCustom);

        // Setup Language Spinner
        ArrayAdapter<String> langAdapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item,
                new String[] { getString(R.string.lang_en), getString(R.string.lang_es) });
        spinnerLang.setAdapter(langAdapter);

        if (elementType == DialCompiler.TYPE_WEEKDAY || elementType == DialCompiler.TYPE_MONTH) {
            containerLang.setVisibility(View.VISIBLE);
        } else {
            containerLang.setVisibility(View.GONE);
        }

        if (elementType == DialCompiler.TYPE_BERRY || elementType == DialCompiler.TYPE_LABEL) {
            containerCustomText.setVisibility(View.VISIBLE);
            lblCustom.setVisibility(View.VISIBLE);
        } else {
            containerCustomText.setVisibility(View.GONE);
            lblCustom.setVisibility(View.GONE);
        }

        seekSpacing.setMax(100);
        seekSpacing.setProgress(50); // Center = 0 spacing
        LinearLayout colorPalette = view.findViewById(R.id.colorPalette);
        LinearLayout borderColorPalette = view.findViewById(R.id.borderColorPalette);

        // Font options: system fonts + any bundled .ttf in assets/fonts/
        List<String> fontNames = new ArrayList<>();
        List<Typeface> fontFaces = new ArrayList<>();
        fontNames.add("Sans Serif");
        fontFaces.add(Typeface.SANS_SERIF);
        fontNames.add("Serif");
        fontFaces.add(Typeface.SERIF);
        fontNames.add("Monospace");
        fontFaces.add(Typeface.MONOSPACE);
        fontNames.add("Sans Bold");
        fontFaces.add(Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD));
        fontNames.add("Serif Bold");
        fontFaces.add(Typeface.create(Typeface.SERIF, Typeface.BOLD));

        // Try to load bundled .ttf fonts from assets/fonts/
        String[] bundledFonts = safeListAssets("fonts");
        for (String f : bundledFonts) {
            if (f.endsWith(".ttf") || f.endsWith(".otf")) {
                try {
                    Typeface tf = Typeface.createFromAsset(getAssets(), "fonts/" + f);
                    fontNames.add(f.replace(".ttf", "").replace(".otf", ""));
                    fontFaces.add(tf);
                } catch (Exception e) {
                    /* skip */ }
            }
        }

        // Load custom fonts from internal storage
        File internalFontsDir = new File(getFilesDir(), "fonts");
        if (internalFontsDir.exists() && internalFontsDir.isDirectory()) {
            File[] files = internalFontsDir.listFiles();
            if (files != null) {
                for (File f : files) {
                    if (f.getName().endsWith(".ttf") || f.getName().endsWith(".otf")) {
                        try {
                            Typeface tf = Typeface.createFromFile(f);
                            fontNames.add("[C] " + f.getName().replace(".ttf", "").replace(".otf", ""));
                            fontFaces.add(tf);
                        } catch (Exception e) {
                            /* skip */ }
                    }
                }
            }
        }

        ArrayAdapter<String> fontAdapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item, fontNames);
        spinnerFont.setAdapter(fontAdapter);

        Button btnLoadFont = view.findViewById(R.id.btnLoadFont);
        if (btnLoadFont != null) {
            btnLoadFont.setOnClickListener(v -> {
                Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
                intent.setType("*/*");
                startActivityForResult(intent, PICK_FONT_CODE);
                // The dialog will be dismissed and reopened after font is loaded
            });
        }

        // Color palette
        final int[] colors = {
                Color.WHITE, Color.BLACK, Color.parseColor("#FF4444"), Color.parseColor("#44FF44"),
                Color.parseColor("#4488FF"), Color.parseColor("#FFDD44"),
                Color.parseColor("#FF44FF"), Color.parseColor("#44FFFF"),
                Color.parseColor("#FF8844"), Color.parseColor("#88FF44")
        };
        final int[] selectedColor = { Color.WHITE };
        final int[] selectedBorderColor = { Color.BLACK };

        // Helper to setup a palette
        View.OnClickListener colorClickListener = v -> {
            int clr = (int) v.getTag();
            selectedColor[0] = clr;
            // Update UI
            for (int i = 0; i < colorPalette.getChildCount(); i++) {
                View child = colorPalette.getChildAt(i);
                GradientDrawable bg = (GradientDrawable) child.getBackground();
                bg.setStroke(3, ((int) child.getTag() == clr) ? Color.parseColor("#58A6FF") : Color.TRANSPARENT);
            }
            updatePreviewAction(imgPreview, spinnerFont, fontFaces, seekSize, selectedColor[0],
                    selectedBorderColor[0], seekGlow, seekBorder, seekSpacing, spinnerLang, edtCustomText, elementType);
        };

        for (int clr : colors) {
            View swatch = new View(this);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1);
            lp.setMargins(2, 2, 2, 2);
            swatch.setLayoutParams(lp);
            swatch.setTag(clr);
            GradientDrawable gd = new GradientDrawable();
            gd.setColor(clr);
            gd.setCornerRadius(6);
            if (clr == selectedColor[0])
                gd.setStroke(3, Color.parseColor("#58A6FF"));
            swatch.setBackground(gd);
            swatch.setOnClickListener(v -> {
                selectedColor[0] = clr;
                for (int i = 0; i < colorPalette.getChildCount(); i++) {
                    View child = colorPalette.getChildAt(i);
                    GradientDrawable bg = (GradientDrawable) child.getBackground();
                    bg.setStroke(3, ((int) child.getTag() == clr) ? Color.parseColor("#58A6FF") : Color.TRANSPARENT);
                }
                updatePreviewAction(imgPreview, spinnerFont, fontFaces, seekSize, selectedColor[0],
                        selectedBorderColor[0], seekGlow, seekBorder, seekSpacing, spinnerLang, edtCustomText,
                        elementType);
            });
            colorPalette.addView(swatch);
        }

        // Border Color Palette
        for (int clr : colors) {
            View swatch = new View(this);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1);
            lp.setMargins(2, 2, 2, 2);
            swatch.setLayoutParams(lp);
            swatch.setTag(clr);
            GradientDrawable gd = new GradientDrawable();
            gd.setColor(clr);
            gd.setCornerRadius(6);
            if (clr == selectedBorderColor[0])
                gd.setStroke(3, Color.parseColor("#58A6FF"));
            swatch.setBackground(gd);
            swatch.setOnClickListener(v -> {
                selectedBorderColor[0] = clr;
                for (int i = 0; i < borderColorPalette.getChildCount(); i++) {
                    View child = borderColorPalette.getChildAt(i);
                    GradientDrawable bg = (GradientDrawable) child.getBackground();
                    bg.setStroke(3, ((int) child.getTag() == clr) ? Color.parseColor("#58A6FF") : Color.TRANSPARENT);
                }
                updatePreviewAction(imgPreview, spinnerFont, fontFaces, seekSize, selectedColor[0],
                        selectedBorderColor[0], seekGlow, seekBorder, seekSpacing, spinnerLang, edtCustomText,
                        elementType);
            });
            borderColorPalette.addView(swatch);
        }

        // Update preview on any change
        SimpleSeekListener seekListener = new SimpleSeekListener() {
            @Override
            public void onProgressChanged(SeekBar sb, int val, boolean user) {
                updatePreviewAction(imgPreview, spinnerFont, fontFaces, seekSize, selectedColor[0],
                        selectedBorderColor[0], seekGlow, seekBorder, seekSpacing, spinnerLang, edtCustomText,
                        elementType);
            }
        };

        seekSize.setOnSeekBarChangeListener(seekListener);
        seekGlow.setOnSeekBarChangeListener(seekListener);
        seekBorder.setOnSeekBarChangeListener(seekListener);
        seekSpacing.setOnSeekBarChangeListener(seekListener);

        spinnerFont.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(android.widget.AdapterView<?> p, View v, int pos, long id) {
                updatePreviewAction(imgPreview, spinnerFont, fontFaces, seekSize, selectedColor[0],
                        selectedBorderColor[0], seekGlow, seekBorder, seekSpacing, spinnerLang, edtCustomText,
                        elementType);
            }

            @Override
            public void onNothingSelected(android.widget.AdapterView<?> p) {
            }
        });

        // Update when custom text changes
        edtCustomText.addTextChangedListener(new android.text.TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                updatePreviewAction(imgPreview, spinnerFont, fontFaces, seekSize, selectedColor[0],
                        selectedBorderColor[0], seekGlow, seekBorder, seekSpacing, spinnerLang, edtCustomText,
                        elementType);
            }

            @Override
            public void afterTextChanged(android.text.Editable s) {
            }
        });

        // Update when language changes
        spinnerLang.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                updatePreviewAction(imgPreview, spinnerFont, fontFaces, seekSize, selectedColor[0],
                        selectedBorderColor[0], seekGlow, seekBorder, seekSpacing, spinnerLang, edtCustomText,
                        elementType);
            }

            @Override
            public void onNothingSelected(android.widget.AdapterView<?> parent) {
            }
        });

        // Initial preview
        updatePreviewAction(imgPreview, spinnerFont, fontFaces, seekSize, selectedColor[0],
                selectedBorderColor[0], seekGlow, seekBorder, seekSpacing, spinnerLang, edtCustomText, elementType);

        new AlertDialog.Builder(this)
                .setTitle(R.string.font_creator_title)
                .setView(view)
                .setPositiveButton(R.string.create_digits, (dialog, which) -> {
                    Typeface tf = fontFaces.get(spinnerFont.getSelectedItemPosition());
                    int size = seekSize.getProgress();
                    int color = selectedColor[0];
                    int borderColor = selectedBorderColor[0];
                    int glow = seekGlow.getProgress();
                    int border = seekBorder.getProgress();
                    int spacing = seekSpacing.getProgress() - 50; // Allow negative spacing
                    String lang = (spinnerLang.getSelectedItemPosition() == 1) ? "es" : "en";
                    String custom = edtCustomText.getText().toString();

                    Bitmap[] frames = renderDigitBitmaps(elementType, tf, size, color, borderColor, glow, border,
                            spacing, lang, custom);
                    if (frames != null && frames.length > 0) {
                        String name = getBlockLabel(elementType);
                        DialLayer layer = new DialLayer(DialLayer.TYPE_ELEMENT, frames[0], name, elementType);
                        layer.frames = frames;
                        layer.frameCount = frames.length;
                        layer.isSpriteSheet = frames.length > 1;
                        layer.alpha = 1.0f;
                        layer.posX = canvasWidth / 4f;
                        layer.posY = canvasHeight / 4f;
                        layers.add(layer);
                        selectedLayerIndex = layers.size() - 1;
                        applyGroupPropagation(layer);
                        adoptStyleIntoPending(layer);
                        refreshAll();
                        Toast.makeText(this, "\u2713 " + name + " \u2014 " + frames.length + " frames",
                                Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void updatePreviewAction(ImageView imgPreview, Spinner spinnerFont, List<Typeface> fontFaces,
            SeekBar seekSize, int color, int borderColor, SeekBar seekGlow,
            SeekBar seekBorder, SeekBar seekSpacing, Spinner spinnerLang, EditText edtCustom, int elementType) {

        if (spinnerFont.getSelectedItemPosition() < 0 || spinnerFont.getSelectedItemPosition() >= fontFaces.size())
            return;

        Typeface tf = fontFaces.get(spinnerFont.getSelectedItemPosition());
        int size = seekSize.getProgress();
        int glow = seekGlow.getProgress();
        int border = seekBorder.getProgress();
        int spacing = seekSpacing.getProgress() - 50;
        String lang = (spinnerLang != null && spinnerLang.getSelectedItemPosition() == 1) ? "es" : "en";
        String custom = (edtCustom != null) ? edtCustom.getText().toString() : "";

        Bitmap[] frames = renderDigitBitmaps(elementType, tf, size, color, borderColor, glow, border, spacing, lang,
                custom);

        if (frames != null && frames.length > 0) {
            // For preview, maybe combinine hi/lo if digit? Or just show first frame?
            // Since it's a font preview, let's show the first generated glyph or a sample.
            // If Weekday/Month, frames[0] is MON/JAN.
            imgPreview.setImageBitmap(frames[0]);
            // If simple number, frames[0] is '0'.
            // To be fancier for numbers, we could combine '1' and '2'.
            if (frames.length >= 10 && elementType != DialCompiler.TYPE_WEEKDAY
                    && elementType != DialCompiler.TYPE_MONTH && elementType != DialCompiler.TYPE_BERRY) {
                if (elementType == DialCompiler.TYPE_DISTANCE && frames.length > 10) {
                    // Show "12.3" — 2 integer digits, decimal point, 1 decimal digit
                    Bitmap[] glyphBmps = { frames[1], frames[2], frames[10], frames[3] };
                    int totalW = 0, maxH = 0;
                    for (Bitmap b : glyphBmps) { totalW += b.getWidth(); maxH = Math.max(maxH, b.getHeight()); }
                    Bitmap combined = Bitmap.createBitmap(totalW, maxH, Bitmap.Config.ARGB_8888);
                    Canvas c = new Canvas(combined);
                    int x = 0;
                    for (Bitmap b : glyphBmps) { c.drawBitmap(b, x, 0, null); x += b.getWidth(); }
                    imgPreview.setImageBitmap(combined);
                } else if (frames.length > 2) {
                    // Combine '1' and '2' → "12"
                    Bitmap one = frames[1];
                    Bitmap two = frames[2];
                    Bitmap combined = Bitmap.createBitmap(one.getWidth() + two.getWidth() + 5,
                            Math.max(one.getHeight(), two.getHeight()), Bitmap.Config.ARGB_8888);
                    Canvas c = new Canvas(combined);
                    c.drawBitmap(one, 0, 0, null);
                    c.drawBitmap(two, one.getWidth() + 5, 0, null);
                    imgPreview.setImageBitmap(combined);
                }
            }
        }
    }

    private void updateFontPreview(ImageView target, Typeface tf, int size,
            int color, int borderColor, int glowRadius, int borderWidth, int spacing) {
        if (size < 16)
            size = 16;
        String sampleText = "12"; // Show fewer digits to fit preview better
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setTypeface(tf);
        paint.setTextSize(size);
        // We only use paint for metrics here, individual drawing below

        Paint.FontMetrics fm = paint.getFontMetrics();
        int textH = (int) (fm.descent - fm.ascent + 0.5f);
        int padding = Math.max(glowRadius, borderWidth) + 4;

        // Render "1" and "2" separately with spacing
        float digit1W = paint.measureText("1");
        float digit2W = paint.measureText("2");
        int frameW1 = (int) digit1W + padding * 2 + spacing;
        int frameW2 = (int) digit2W + padding * 2 + spacing;

        int bmpW = frameW1 + frameW2;
        int bmpH = textH + padding * 2;

        Bitmap bmp = Bitmap.createBitmap(Math.max(1, bmpW), Math.max(1, bmpH), Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);

        // Draw digit 1
        float y = padding - fm.ascent;
        drawDigitOnCanvas(c, "1", padding + spacing / 2f, y, paint, color, borderColor, glowRadius, borderWidth);
        // Draw digit 2
        drawDigitOnCanvas(c, "2", frameW1 + padding + spacing / 2f, y, paint, color, borderColor, glowRadius,
                borderWidth);

        target.setImageBitmap(bmp);
    }

    private void drawDigitOnCanvas(Canvas c, String text, float x, float y, Paint basePaint,
            int color, int borderColor, int glowRadius, int borderWidth) {
        if (glowRadius > 0) {
            Paint glowPaint = new Paint(basePaint);
            glowPaint.setColor(color);
            glowPaint.setMaskFilter(new BlurMaskFilter(glowRadius, BlurMaskFilter.Blur.NORMAL));
            glowPaint.setAlpha(180);
            c.drawText(text, x, y, glowPaint);
        }
        if (borderWidth > 0) {
            Paint strokePaint = new Paint(basePaint);
            strokePaint.setStyle(Paint.Style.STROKE);
            strokePaint.setStrokeWidth(borderWidth);
            strokePaint.setColor(borderColor);
            c.drawText(text, x, y, strokePaint);
        }
        basePaint.setColor(color);
        c.drawText(text, x, y, basePaint);
    }

    /**
     * Render individual digit bitmaps from a font with effects.
     */
    private Bitmap[] renderDigitBitmaps(int elementType, Typeface tf, int size,
            int color, int borderColor, int glowRadius, int borderWidth, int spacing, String language,
            String customText) {
        if (size < 16)
            size = 16;
        int expectedFrames = DialCompiler.getDefaultFrameCount(elementType);
        String[] glyphs;

        if (elementType == DialCompiler.TYPE_AMPM) {
            glyphs = new String[] { "AM", "PM" };
        } else if (elementType == DialCompiler.TYPE_WEEKDAY) {
            if ("es".equals(language)) {
                glyphs = new String[] { "LUN", "MAR", "MIE", "JUE", "VIE", "SAB", "DOM" };
            } else {
                glyphs = new String[] { "MON", "TUE", "WED", "THU", "FRI", "SAT", "SUN" };
            }
        } else if (elementType == DialCompiler.TYPE_MONTH) {
            // 1-12 (Jan-Dec)
            if ("es".equals(language)) {
                glyphs = new String[] { "ENE", "FEB", "MAR", "ABR", "MAY", "JUN", "JUL", "AGO", "SEP", "OCT", "NOV",
                        "DIC" };
            } else {
                glyphs = new String[] { "JAN", "FEB", "MAR", "APR", "MAY", "JUN", "JUL", "AUG", "SEP", "OCT", "NOV",
                        "DEC" };
            }
        } else if (elementType == DialCompiler.TYPE_BERRY || elementType == DialCompiler.TYPE_LABEL) {
            // Decoration / Label: Use custom text if provided, else default
            if (customText != null && !customText.isEmpty()) {
                glyphs = new String[] { customText };
            } else {
                glyphs = new String[] { ":" }; // Default decoration
            }
        } else if (elementType == DialCompiler.TYPE_DISTANCE) {
            // Numeric digits 0-9 + '.' at index 10
            glyphs = new String[11];
            for (int i = 0; i < 10; i++) {
                glyphs[i] = String.valueOf(i);
            }
            glyphs[10] = ".";
        } else {
            // Numeric digits 0-9
            glyphs = new String[expectedFrames];
            for (int i = 0; i < expectedFrames; i++) {
                glyphs[i] = String.valueOf(i);
            }
        }

        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setTypeface(tf);
        paint.setTextSize(size);
        paint.setColor(color);

        // Find max dimensions across all glyphs (exclude "." from maxW so it gets its own narrower width)
        Paint.FontMetrics fm = paint.getFontMetrics();
        int textH = (int) (fm.descent - fm.ascent + 0.5f);
        int maxW = 0;
        for (String g : glyphs) {
            if (!".".equals(g)) {
                maxW = Math.max(maxW, (int) (paint.measureText(g) + 0.5f));
            }
        }
        int padding = Math.max(glowRadius, borderWidth) + 2;
        int frameW = maxW + padding * 2 + spacing;
        int frameH = textH + padding * 2;

        Bitmap[] frames = new Bitmap[glyphs.length];
        for (int i = 0; i < glyphs.length; i++) {
            boolean isDot = ".".equals(glyphs[i]);
            // Dot gets its own narrow frame: measured width + padding (no extra spacing)
            int thisFrameW = isDot
                    ? (int) (paint.measureText(".") + 0.5f) + padding * 2
                    : frameW;
            Bitmap bmp = Bitmap.createBitmap(Math.max(1, thisFrameW), Math.max(1, frameH), Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(bmp);
            float textW = paint.measureText(glyphs[i]);
            float x = (thisFrameW - textW) / 2f;
            float y = padding - fm.ascent;

            if (glowRadius > 0) {
                Paint gp = new Paint(paint);
                gp.setMaskFilter(new BlurMaskFilter(glowRadius, BlurMaskFilter.Blur.NORMAL));
                gp.setAlpha(180);
                c.drawText(glyphs[i], x, y, gp);
            }
            if (borderWidth > 0) {
                Paint sp = new Paint(paint);
                sp.setStyle(Paint.Style.STROKE);
                sp.setStrokeWidth(borderWidth);
                sp.setColor(borderColor);
                c.drawText(glyphs[i], x, y, sp);
            }
            c.drawText(glyphs[i], x, y, paint);
            frames[i] = bmp;
        }
        return frames;
    }

    // ===================== ASSET PRESET LOADING =====================

    private List<String> findPresetFolders(int elementType) {
        List<String> found = new ArrayList<>();
        List<String> allBases = new ArrayList<>();
        try {
            String[] assets = getAssets().list("");
            if (assets != null) {
                for (String a : assets) {
                    if (a.startsWith("dial_customize_")) {
                        allBases.add(a);
                    }
                }
            }
        } catch (Exception e) {
        }
        if (allBases.isEmpty())
            allBases.add("dial_customize_2");

        try {
            for (String base : allBases) {
                switch (elementType) {
                    // === ANALOG HANDS (pointer/pointer/hour|minute|second) ===
                    case DialCompiler.TYPE_ARM_HOUR: {
                        String pointerDir = base + "/time/pointer/pointer/hour";
                        String[] files = safeListAssets(pointerDir);
                        for (String f : files) {
                            if (f.endsWith(".png")) {
                                found.add(pointerDir + "/" + f);
                            }
                        }
                        break;
                    }
                    case DialCompiler.TYPE_ARM_MIN: {
                        String pointerDir = base + "/time/pointer/pointer/minute";
                        String[] files = safeListAssets(pointerDir);
                        for (String f : files) {
                            if (f.endsWith(".png")) {
                                found.add(pointerDir + "/" + f);
                            }
                        }
                        break;
                    }
                    case DialCompiler.TYPE_ARM_SEC: {
                        String pointerDir = base + "/time/pointer/pointer/second";
                        String[] files = safeListAssets(pointerDir);
                        for (String f : files) {
                            if (f.endsWith(".png")) {
                                found.add(pointerDir + "/" + f);
                            }
                        }
                        break;
                    }

                    // === DIGITAL DIGITS (time/digital/X/hour_minute) ===
                    case DialCompiler.TYPE_BATTERY: {
                        // Look in assets/battery/*.svg
                        String batDir = "battery";
                        String[] files = safeListAssets(batDir);
                        for (String f : files) {
                            if (f.endsWith(".svg")) {
                                found.add(batDir + "/" + f);
                            }
                        }
                        // Also look in standard value folder
                        String valueBase = base + "/value";
                        String[] styles = safeListAssets(valueBase);
                        for (String style : styles) {
                            String path = valueBase + "/" + style;
                            if (hasAssetFile(path + "/0.png")) {
                                found.add(path);
                            }
                        }
                        break;
                    }
                    case DialCompiler.TYPE_DIGITAL_HOUR:
                    case DialCompiler.TYPE_DIGITAL_MIN:
                    case DialCompiler.TYPE_SECONDS:
                    case DialCompiler.TYPE_HOUR_HI:
                    case DialCompiler.TYPE_HOUR_LO:
                    case DialCompiler.TYPE_MIN_HI:
                    case DialCompiler.TYPE_MIN_LO: {
                        String digitalBase = base + "/time/digital";
                        String[] styles = safeListAssets(digitalBase);
                        for (String style : styles) {
                            String path = digitalBase + "/" + style + "/hour_minute";
                            if (hasAssetFile(path + "/0.png")) {
                                found.add(path);
                            }
                        }
                        break;
                    }
                    case DialCompiler.TYPE_WEEKDAY: {
                        String digitalBase = base + "/time/digital";
                        String[] styles = safeListAssets(digitalBase);
                        for (String style : styles) {
                            String path = digitalBase + "/" + style + "/week";
                            if (hasAssetFile(path + "/0.png")) {
                                found.add(path);
                            }
                        }
                        break;
                    }
                    case DialCompiler.TYPE_AMPM: {
                        String digitalBase = base + "/time/digital";
                        String[] styles = safeListAssets(digitalBase);
                        for (String style : styles) {
                            String path = digitalBase + "/" + style + "/am_pm";
                            if (hasAssetFile(path + "/am.png")) {
                                found.add(path);
                            }
                        }
                        break;
                    }

                    // === DATE DIGITS ===
                    case DialCompiler.TYPE_DAY:
                    case DialCompiler.TYPE_MONTH:
                    case DialCompiler.TYPE_YEAR: {
                        String digitalBase = base + "/time/digital";
                        String[] styles = safeListAssets(digitalBase);
                        for (String style : styles) {
                            String path = digitalBase + "/" + style + "/date";
                            if (hasAssetFile(path + "/0.png")) {
                                found.add(path);
                            }
                        }
                        break;
                    }

                    // === HEALTH/VALUE DIGITS ===
                    case DialCompiler.TYPE_STEPS:
                    case DialCompiler.TYPE_HEART:
                    case DialCompiler.TYPE_CALORIE:
                    case DialCompiler.TYPE_DISTANCE:
                    case DialCompiler.TYPE_TEMP: {
                        String valueBase = base + "/value";
                        String[] styles = safeListAssets(valueBase);
                        for (String style : styles) {
                            String path = valueBase + "/" + style;
                            if (hasAssetFile(path + "/0.png")) {
                                found.add(path);
                            }
                        }
                        break;
                    }
                }
            }
        } catch (Exception e) {
            // Directory doesn't exist
        }
        return found;
    }

    private InputStream openAssetOrFile(String path) throws java.io.IOException {
        if (path.startsWith("FILE:")) {
            return new java.io.FileInputStream(path.substring(5));
        }
        return getAssets().open(path);
    }

    private void saveLayerAsPreset(DialLayer layer) {
        if (layer == null || layer.frames == null || layer.frames.length == 0)
            return;
        try {
            java.io.File presetsDir = new java.io.File(getFilesDir(), "presets");
            String customName = "dial_customize_user_" + System.currentTimeMillis();
            java.io.File newPresetDir = new java.io.File(presetsDir, customName);
            newPresetDir.mkdirs();

            String subPath = "";
            int type = layer.nativeElementType;
            if (type == DialCompiler.TYPE_ARM_HOUR)
                subPath = "time/pointer/pointer/hour";
            else if (type == DialCompiler.TYPE_ARM_MIN)
                subPath = "time/pointer/pointer/minute";
            else if (type == DialCompiler.TYPE_ARM_SEC)
                subPath = "time/pointer/pointer/second";
            else if (type == DialCompiler.TYPE_HOUR_LO || type == DialCompiler.TYPE_HOUR_HI
                    || type == DialCompiler.TYPE_MIN_LO || type == DialCompiler.TYPE_MIN_HI
                    || type == DialCompiler.TYPE_DIGITAL_HOUR || type == DialCompiler.TYPE_DIGITAL_MIN)
                subPath = "time/digital/1/hour_minute";
            else if (type == DialCompiler.TYPE_SECONDS)
                subPath = "time/digital/1/second";
            else if (type == DialCompiler.TYPE_DAY)
                subPath = "time/digital/1/day";
            else if (type == DialCompiler.TYPE_MONTH)
                subPath = "time/digital/1/month";
            else if (type == DialCompiler.TYPE_YEAR)
                subPath = "time/digital/1/year";
            else if (type == DialCompiler.TYPE_WEEKDAY)
                subPath = "time/digital/1/weekday";
            else if (type == DialCompiler.TYPE_AMPM)
                subPath = "time/digital/1/ampm";
            else if (type == DialCompiler.TYPE_STEPS)
                subPath = "control/step";
            else if (type == DialCompiler.TYPE_HEART)
                subPath = "control/heart_rate";
            else if (type == DialCompiler.TYPE_CALORIE)
                subPath = "control/calories";
            else if (type == DialCompiler.TYPE_DISTANCE)
                subPath = "control/distance";
            else if (type == DialCompiler.TYPE_BATTERY)
                subPath = "control/battery";
            else if (type == DialCompiler.TYPE_BATT_STRIP)
                subPath = "control/battery_strip";
            else {
                Toast.makeText(this, R.string.editor_preset_unsupported, Toast.LENGTH_SHORT).show();
                return;
            }

            java.io.File targetDir = new java.io.File(newPresetDir, subPath);
            targetDir.mkdirs();

            if (type == DialCompiler.TYPE_AMPM && layer.frames.length >= 2) {
                layer.frames[0].compress(Bitmap.CompressFormat.PNG, 100,
                        new java.io.FileOutputStream(new java.io.File(targetDir, "am.png")));
                layer.frames[1].compress(Bitmap.CompressFormat.PNG, 100,
                        new java.io.FileOutputStream(new java.io.File(targetDir, "pm.png")));
            } else {
                for (int i = 0; i < layer.frames.length; i++) {
                    String fname = (i + ".png");
                    if (layer.frames.length == 1 && (type == DialCompiler.TYPE_ARM_HOUR
                            || type == DialCompiler.TYPE_ARM_MIN || type == DialCompiler.TYPE_ARM_SEC))
                        fname = "0.png";
                    layer.frames[i].compress(Bitmap.CompressFormat.PNG, 100,
                            new java.io.FileOutputStream(new java.io.File(targetDir, fname)));
                }
            }
            Toast.makeText(this, R.string.editor_preset_saved, Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(this, getString(R.string.error_fmt, e.getMessage()), Toast.LENGTH_SHORT).show();
        }
    }

    private String[] safeListAssets(String path) {
        if (path.startsWith("FILE:")) {
            java.io.File d = new java.io.File(path.substring(5));
            if (d.exists() && d.isDirectory()) {
                String[] list = d.list();
                return list != null ? list : new String[0];
            }
            return new String[0];
        }
        try {
            String[] list = getAssets().list(path);
            return list != null ? list : new String[0];
        } catch (Exception e) {
            return new String[0];
        }
    }

    private boolean hasAssetFile(String path) {
        if (path.startsWith("FILE:")) {
            return new java.io.File(path.substring(5)).exists();
        }
        try {
            InputStream is = openAssetOrFile(path);
            is.close();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private void loadPreset(String assetPath, int elementType) {
        try {
            // Check if this is a single file (pointer hand) or a folder (digit set)
            boolean isSingleFile = assetPath.endsWith(".png");

            if (isSingleFile) {
                // Single image file (analog hand)
                InputStream is = getAssets().open(assetPath);
                Bitmap bmp = BitmapFactory.decodeStream(is);
                is.close();
                if (bmp == null) {
                    Toast.makeText(this, R.string.no_preset_images, Toast.LENGTH_SHORT).show();
                    return;
                }
                
                boolean isHand = (elementType == DialCompiler.TYPE_ARM_HOUR ||
                                  elementType == DialCompiler.TYPE_ARM_MIN ||
                                  elementType == DialCompiler.TYPE_ARM_SEC);
                if (isHand) {
                    bmp = trimBitmap(bmp);
                }

                String name = getBlockLabel(elementType);
                DialLayer layer = new DialLayer(DialLayer.TYPE_ARM, bmp, name, elementType);
                layer.frames = new Bitmap[] { bmp };
                layer.frameCount = 1;
                layer.isSpriteSheet = false;
                layer.alpha = 1.0f;
                // Center analog hands
                layer.posX = (canvasWidth - bmp.getWidth()) / 2f;
                layer.posY = (canvasHeight - bmp.getHeight()) / 2f;

                layers.add(layer);
                selectedLayerIndex = layers.size() - 1;
                applyGroupPropagation(layer);
                adoptStyleIntoPending(layer);
                refreshAll();
                Toast.makeText(this, "✓ " + name, Toast.LENGTH_SHORT).show();
                return;
            }

            // Folder with numbered images (digit set)
            int expectedFrames = DialCompiler.getDefaultFrameCount(elementType);
            List<Bitmap> loadedFrames = new ArrayList<>();

            if (elementType == DialCompiler.TYPE_AMPM) {
                for (String name : new String[] { "am.png", "pm.png" }) {
                    try {
                        InputStream is = openAssetOrFile(assetPath + "/" + name);
                        Bitmap bmp = BitmapFactory.decodeStream(is);
                        is.close();
                        if (bmp != null)
                            loadedFrames.add(bmp);
                    } catch (Exception e) {
                        /* skip */ }
                }
            } else {
                for (int i = 0; i < expectedFrames; i++) {
                    try {
                        InputStream is = openAssetOrFile(assetPath + "/" + i + ".png");
                        Bitmap bmp = BitmapFactory.decodeStream(is);
                        is.close();
                        if (bmp != null)
                            loadedFrames.add(bmp);
                    } catch (Exception e) {
                        /* skip */ }
                }
            }

            if (loadedFrames.isEmpty()) {
                Toast.makeText(this, R.string.no_preset_images, Toast.LENGTH_SHORT).show();
                return;
            }

            boolean isHand = (elementType == DialCompiler.TYPE_ARM_HOUR ||
                              elementType == DialCompiler.TYPE_ARM_MIN ||
                              elementType == DialCompiler.TYPE_ARM_SEC);

            Bitmap[] frames = loadedFrames.toArray(new Bitmap[0]);
            if (isHand) {
                for (int i = 0; i < frames.length; i++) {
                    frames[i] = trimBitmap(frames[i]);
                }
            }
            
            String name = getBlockLabel(elementType);
            DialLayer layer = new DialLayer(DialLayer.TYPE_ELEMENT, frames[0], name, elementType);
            layer.frames = frames;
            layer.frameCount = frames.length;
            layer.isSpriteSheet = frames.length > 1;
            layer.alpha = 1.0f;
            layer.posX = canvasWidth / 4f;
            layer.posY = canvasHeight / 4f;

            layers.add(layer);
            selectedLayerIndex = layers.size() - 1;
            applyGroupPropagation(layer);
            adoptStyleIntoPending(layer);
            refreshAll();

            Toast.makeText(this, "✓ " + name + " — " + frames.length + " frames", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(this, "Error: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private void applyGroupPropagation(DialLayer justLoaded) {
        if (pendingGroupTarget == null || pendingGroupSourceLayer == null) return;
        TimeGroup group = pendingGroupTarget;
        pendingGroupSourceLayer.frames        = justLoaded.frames != null ? justLoaded.frames.clone() : null;
        pendingGroupSourceLayer.icon          = justLoaded.icon;
        pendingGroupSourceLayer.scale         = justLoaded.scale;
        pendingGroupSourceLayer.frameCount    = justLoaded.frameCount;
        pendingGroupSourceLayer.isSpriteSheet = justLoaded.isSpriteSheet;
        pendingGroupSourceLayer.pendingStyle  = false;
        layers.remove(justLoaded);
        for (DialLayer sibling : group.parts) {
            if (sibling == pendingGroupSourceLayer || sibling.isColonSeparator) continue;
            sibling.frames        = pendingGroupSourceLayer.frames != null ? pendingGroupSourceLayer.frames.clone() : null;
            sibling.icon          = pendingGroupSourceLayer.icon;
            sibling.scale         = pendingGroupSourceLayer.scale;
            sibling.frameCount    = pendingGroupSourceLayer.frameCount;
            sibling.isSpriteSheet = pendingGroupSourceLayer.isSpriteSheet;
            sibling.pendingStyle  = false;
        }
        selectedLayerIndex = layers.indexOf(pendingGroupSourceLayer);
        pendingGroupTarget      = null;
        pendingGroupSourceLayer = null;
        relayoutTimeGroup(group);
    }

    /**
     * If a single pending placeholder was waiting for this style, move the
     * freshly created layer's bitmaps into it instead of leaving both a styled
     * duplicate and an orphaned placeholder on the canvas.
     */
    private boolean adoptStyleIntoPending(DialLayer created) {
        if (pendingStyleTarget == null || created == pendingStyleTarget) return false;
        DialLayer target = pendingStyleTarget;
        pendingStyleTarget = null;
        if (!layers.contains(target)) return false;
        target.frames        = created.frames;
        target.icon          = created.icon;
        target.scale         = created.scale;
        target.frameCount    = created.frameCount;
        target.isSpriteSheet = created.isSpriteSheet;
        target.pendingStyle  = false;
        layers.remove(created);
        selectedLayerIndex = layers.indexOf(target);
        if (target.timeGroupId != null) {
            relayoutTimeGroup(findGroup(target.timeGroupId));
        }
        return true;
    }

    /**
     * Re-spaces the parts of a time group using their actual rendered widths.
     * The placeholder layout assumes 40px-wide elements; real digit styles are
     * usually wider, so without this pass hours/colon/minutes overlap both in
     * the preview and on the watch. The group's visual centre is preserved.
     */
    private void relayoutTimeGroup(TimeGroup group) {
        if (group == null || group.parts.isEmpty()) return;
        float minX = Float.MAX_VALUE, maxX = -Float.MAX_VALUE;
        float centerY = 0;
        List<float[]> sizes = new ArrayList<>();
        for (DialLayer part : group.parts) {
            android.graphics.RectF r = layerScreenRect(part);
            float w = r != null ? r.width() : 40;
            float h = r != null ? r.height() : 60;
            sizes.add(new float[] { w, h });
            minX = Math.min(minX, part.posX);
            maxX = Math.max(maxX, part.posX + w);
            centerY += part.posY + h / 2f;
        }
        centerY /= group.parts.size();
        float groupCenterX = (minX + maxX) / 2f;
        float totalW = 0;
        for (float[] s : sizes) totalW += s[0];
        float x = groupCenterX - totalW / 2f;
        for (int i = 0; i < group.parts.size(); i++) {
            DialLayer part = group.parts.get(i);
            part.posX = x;
            part.posY = centerY - sizes.get(i)[1] / 2f;
            x += sizes.get(i)[0];
        }
    }

    // ===================== GALLERY PICK =====================

    private void pickImageFromGallery() {
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.setType("image/*");
        startActivityForResult(intent, PICK_IMAGE_CODE);
    }

    private void pickSvgFromGallery() {
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.setType("image/svg+xml");
        startActivityForResult(intent, PICK_SVG_CODE);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == PICK_IMAGE_CODE && resultCode == RESULT_OK && data != null && data.getData() != null) {
            Uri imageUri = data.getData();
            try {
                // Step 1: read dimensions only (no memory allocation)
                BitmapFactory.Options boundsOpts = new BitmapFactory.Options();
                boundsOpts.inJustDecodeBounds = true;
                InputStream is0 = getContentResolver().openInputStream(imageUri);
                BitmapFactory.decodeStream(is0, null, boundsOpts);
                is0.close();

                // Step 2: calculate inSampleSize so the decoded bitmap fits within 1024px
                // (still much larger than the 466px canvas — the final scale happens later)
                int sampleSize = 1;
                int w = boundsOpts.outWidth, h = boundsOpts.outHeight;
                while (w / (sampleSize * 2) >= 466 && h / (sampleSize * 2) >= 466) {
                    sampleSize *= 2;
                }

                // Step 3: decode at reduced resolution as ARGB_8888
                BitmapFactory.Options opts = new BitmapFactory.Options();
                opts.inSampleSize = sampleSize;
                opts.inPreferredConfig = Bitmap.Config.ARGB_8888;
                InputStream is = getContentResolver().openInputStream(imageUri);
                Bitmap bmp = BitmapFactory.decodeStream(is, null, opts);
                is.close();

                if (bmp == null) {
                    Toast.makeText(this, R.string.editor_image_load_error, Toast.LENGTH_SHORT).show();
                    return;
                }

                if (pendingElementType > 0 && pendingElementType != DialCompiler.TYPE_BACKGROUND) {
                    // Element art picked from the gallery: slice it as a vertical
                    // sprite sheet (multi-frame types) or keep it as one image —
                    // never turn it into a fullscreen background.
                    addSpriteSheetLayer(bmp, pendingElementType);
                } else {
                    addBackgroundLayer(bmp, DialCompiler.TYPE_BACKGROUND);
                }
                pendingElementType = -1;
            } catch (Exception e) {
                Toast.makeText(this, "Error: " + e.getClass().getSimpleName() + ": " + e.getMessage(),
                        Toast.LENGTH_LONG).show();
            }
        } else if (requestCode == PICK_FONT_CODE && resultCode == RESULT_OK && data != null && data.getData() != null) {
            Uri fontUri = data.getData();
            try {
                InputStream is = getContentResolver().openInputStream(fontUri);
                File fontsDir = new File(getFilesDir(), "fonts");
                if (!fontsDir.exists())
                    fontsDir.mkdirs();

                String fileName = "custom_" + System.currentTimeMillis() + ".ttf";
                File targetFile = new File(fontsDir, fileName);
                FileOutputStream fos = new FileOutputStream(targetFile);
                byte[] buffer = new byte[4096];
                int len;
                while ((len = is.read(buffer)) != -1)
                    fos.write(buffer, 0, len);
                fos.close();
                is.close();

                Toast.makeText(this, R.string.font_loaded, Toast.LENGTH_SHORT).show();
                // Font list will refresh when user clicks "Add Layer" again
            } catch (Exception e) {
                Toast.makeText(this, getString(R.string.font_load_error, e.getMessage()), Toast.LENGTH_SHORT).show();
            }
        } else if (requestCode == PICK_SVG_CODE && resultCode == RESULT_OK && data != null && data.getData() != null) {
            showSVGEditor(data.getData(), pendingElementType);
            pendingElementType = -1;
        } else if (requestCode == PICK_ANIMATION_CODE && resultCode == RESULT_OK && data != null
                && data.getData() != null) {
            Uri animUri = data.getData();
            String mime = getContentResolver().getType(animUri);
            boolean isGif = mime != null && mime.equals("image/gif");

            // For GIF: decode Movie once — reused for duration display AND frame extraction
            @SuppressWarnings("deprecation")
            final android.graphics.Movie gifMovie;
            if (isGif) {
                try {
                    InputStream is = getContentResolver().openInputStream(animUri);
                    byte[] gifData = readBytes(is);
                    is.close();
                    gifMovie = android.graphics.Movie.decodeByteArray(gifData, 0, gifData.length);
                } catch (Exception e) {
                    Toast.makeText(this, R.string.gif_read_error, Toast.LENGTH_SHORT).show();
                    return;
                }
            } else {
                gifMovie = null;
            }

            // Compute duration
            long durationMs;
            if (isGif) {
                durationMs = gifMovie != null ? gifMovie.duration() : 0;
            } else {
                android.media.MediaMetadataRetriever r = new android.media.MediaMetadataRetriever();
                try {
                    r.setDataSource(this, animUri);
                    String s = r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION);
                    durationMs = s != null ? Long.parseLong(s) : 0;
                } catch (Exception e) {
                    durationMs = 0;
                } finally {
                    try {
                        r.release();
                    } catch (Exception ignored) {
                    }
                }
            }
            final long finalDuration = durationMs;

            // Show dialog to pick interval
            View dialogView = getLayoutInflater().inflate(R.layout.dialog_frame_picker, null);
            RadioGroup rg = dialogView.findViewById(R.id.rgInterval);
            TextView tvInfo = dialogView.findViewById(R.id.tvFramePickerInfo);
            TextView tvCount = dialogView.findViewById(R.id.tvFrameCount);

            rg.setOnCheckedChangeListener((g, id) -> {
                int ms = id == R.id.rb100ms ? 100 : id == R.id.rb500ms ? 500 : 200;
                long est = finalDuration > 0 ? Math.min(15, finalDuration / ms) : 1;
                tvCount.setText(getString(R.string.frames_estimate, (int) est, 15));
            });
            tvInfo.setText(getString(R.string.anim_duration_fmt, (int) finalDuration));
            long estDefault = finalDuration > 0 ? Math.min(15, finalDuration / 200) : 1;
            tvCount.setText(getString(R.string.frames_estimate, (int) estDefault, 15));

            new AlertDialog.Builder(this)
                    .setView(dialogView)
                    .setPositiveButton(R.string.action_import, (dlg, w) -> {
                        int checkedId = rg.getCheckedRadioButtonId();
                        int ms = checkedId == R.id.rb100ms ? 100 : checkedId == R.id.rb500ms ? 500 : 200;
                        EditText etInterval = dialogView.findViewById(R.id.etManualInterval);
                        EditText etLimit = dialogView.findViewById(R.id.etMaxFrames);

                        int finalMaxFrames = 15;
                        try {
                            String s = etLimit.getText().toString().trim();
                            if (!s.isEmpty())
                                finalMaxFrames = Integer.parseInt(s);
                        } catch (Exception ignored) {
                        }

                        // Hard limit to 20 frames for stability
                        if (finalMaxFrames > 20) finalMaxFrames = 20;

                        int finalMs = ms;
                        try {
                            String s = etInterval.getText().toString().trim();
                            if (!s.isEmpty())
                                finalMs = Integer.parseInt(s);
                        } catch (Exception ignored) {
                        }

                        final int extractionMs = finalMs;
                        final int extractionLimit = finalMaxFrames;

                        android.app.ProgressDialog progress = new android.app.ProgressDialog(this);
                        progress.setMessage(getString(R.string.extracting_frames));
                        progress.setCancelable(false);
                        progress.show();
                        new Thread(() -> {
                            Bitmap[] frames = isGif
                                    ? extractGifFrames(gifMovie, extractionMs, extractionLimit)
                                    : extractVideoFrames(animUri, extractionMs, extractionLimit);
                            runOnUiThread(() -> {
                                progress.dismiss();
                                if (frames == null || frames.length == 0) {
                                    Toast.makeText(this, R.string.frames_extract_error, Toast.LENGTH_SHORT).show();
                                    return;
                                }
                                DialLayer layer = new DialLayer(DialLayer.TYPE_ELEMENT, frames[0],
                                        getBlockLabel(DialCompiler.TYPE_ANIM), DialCompiler.TYPE_ANIM);
                                layer.frames = frames;
                                layer.frameCount = frames.length;
                                layer.isSpriteSheet = true;
                                layer.animIntervalMs = extractionMs;
                                // DO NOT scale to cover for animations — keep original size or fit within
                                // screen
                                float scale = 1.0f;
                                if (frames[0].getWidth() > canvasWidth || frames[0].getHeight() > canvasHeight) {
                                    scale = Math.min((float) canvasWidth / frames[0].getWidth(),
                                            (float) canvasHeight / frames[0].getHeight());
                                }
                                layer.scale = scale;
                                layer.posX = (canvasWidth - frames[0].getWidth() * scale) / 2f;
                                layer.posY = (canvasHeight - frames[0].getHeight() * scale) / 2f;
                                layer.locked = false;
                                layers.add(0, layer);
                                selectedLayerIndex = 0;
                                Toast.makeText(this, getString(R.string.frames_imported, frames.length),
                                        Toast.LENGTH_SHORT).show();
                                refreshAll();
                            });
                        }).start();
                    })
                    .setNegativeButton(R.string.cancel, null)
                    .show();
        }
    }

    private void addBackgroundLayer(Bitmap bmp) {
        addBackgroundLayer(bmp, DialCompiler.TYPE_BACKGROUND);
    }

    private void addBackgroundLayer(Bitmap bmp, int elementType) {
        // Ensure no other background exists? Or just add on top?
        // Usually only 1 background. Remove existing if any?
        // For now, just add.

        if (elementType == DialCompiler.TYPE_BACKGROUND) {
            // Ensure ARGB_8888 for firmware compatibility (but don't stretch — preserve
            // aspect ratio)
            if (bmp.getConfig() != Bitmap.Config.ARGB_8888) {
                Bitmap argb = Bitmap.createBitmap(bmp.getWidth(), bmp.getHeight(), Bitmap.Config.ARGB_8888);
                Canvas c = new Canvas(argb);
                c.drawBitmap(bmp, 0, 0, null);
                bmp = argb;
            }
        }

        float scaleToCover = Math.max(
                (float) canvasWidth / bmp.getWidth(),
                (float) canvasHeight / bmp.getHeight());

        DialLayer layer = new DialLayer(DialLayer.TYPE_BACKGROUND, bmp,
                getBlockLabel(DialCompiler.TYPE_BACKGROUND), DialCompiler.TYPE_BACKGROUND);
        layer.frames = new Bitmap[] { bmp };
        layer.scale = scaleToCover;
        // Center on canvas
        layer.posX = (canvasWidth - bmp.getWidth() * scaleToCover) / 2f;
        layer.posY = (canvasHeight - bmp.getHeight() * scaleToCover) / 2f;
        layer.locked = false;
        layers.add(0, layer);
        selectedLayerIndex = 0;
        refreshAll();
    }

    @SuppressWarnings("deprecation")
    private Bitmap[] extractGifFrames(android.graphics.Movie movie, int intervalMs, int maxFrames) {
        try {
            if (movie == null)
                return null;

            int duration = movie.duration();
            List<Bitmap> frames = new ArrayList<>();

            if (duration == 0) {
                // Single-frame GIF: render at t=0
                Bitmap bmp = Bitmap.createBitmap(movie.width(), movie.height(), Bitmap.Config.ARGB_8888);
                Canvas c = new Canvas(bmp);
                c.drawColor(Color.TRANSPARENT, android.graphics.PorterDuff.Mode.CLEAR);
                movie.setTime(0);
                movie.draw(c, 0, 0);
                return new Bitmap[] { bmp };
            }

            for (int t = 0; t < duration && frames.size() < maxFrames; t += intervalMs) {
                Bitmap bmp = Bitmap.createBitmap(movie.width(), movie.height(), Bitmap.Config.ARGB_8888);
                Canvas c = new Canvas(bmp);
                c.drawColor(Color.TRANSPARENT, android.graphics.PorterDuff.Mode.CLEAR);
                // movie.setTime() mutates the Movie object; caller must ensure no concurrent
                // access
                movie.setTime(t);
                movie.draw(c, 0, 0);
                frames.add(bmp);
            }
            return frames.isEmpty() ? null : frames.toArray(new Bitmap[0]);
        } catch (Exception e) {
            Log.e(TAG, "GIF decode failed", e);
            return null;
        }
    }

    private byte[] readBytes(InputStream is) throws Exception {
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = is.read(buf)) != -1)
            bos.write(buf, 0, n);
        return bos.toByteArray();
    }

    private Bitmap[] extractVideoFrames(Uri uri, int intervalMs, int maxFrames) {
        android.media.MediaMetadataRetriever retriever = new android.media.MediaMetadataRetriever();
        try {
            retriever.setDataSource(this, uri);
            String durationStr = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION);
            long durationMs = durationStr != null ? Long.parseLong(durationStr) : 0;
            if (durationMs == 0)
                return null;

            List<Bitmap> frames = new ArrayList<>();
            for (long t = 0; t < durationMs && frames.size() < maxFrames; t += intervalMs) {
                Bitmap bmp = retriever.getFrameAtTime(t * 1000L, android.media.MediaMetadataRetriever.OPTION_CLOSEST);
                if (bmp != null)
                    frames.add(bmp);
            }
            return frames.toArray(new Bitmap[0]);
        } catch (Exception e) {
            Log.e(TAG, "Video decode failed", e);
            return null;
        } finally {
            try {
                retriever.release();
            } catch (Exception ignored) {
            }
        }
    }

    private void addSpriteSheetLayer(Bitmap compositeBmp, int elementType) {
        int expectedFrames = DialCompiler.getDefaultFrameCount(elementType);

        Bitmap[] frames;
        if (expectedFrames > 1 && compositeBmp.getHeight() > compositeBmp.getWidth()) {
            // Vertical sprite sheet
            int frameWidth = compositeBmp.getWidth();
            int frameHeight = compositeBmp.getHeight() / expectedFrames;
            frames = new Bitmap[expectedFrames];
            for (int i = 0; i < expectedFrames; i++) {
                int y = i * frameHeight;
                if (y + frameHeight > compositeBmp.getHeight()) {
                    frames[i] = Bitmap.createBitmap(frameWidth, frameHeight, Bitmap.Config.ARGB_8888);
                } else {
                    frames[i] = Bitmap.createBitmap(compositeBmp, 0, y, frameWidth, frameHeight);
                }
            }
        } else {
            frames = new Bitmap[] { compositeBmp };
        }

        String name = getBlockLabel(elementType);
        DialLayer layer = new DialLayer(DialLayer.TYPE_ELEMENT, frames[0], name, elementType);
        layer.frames = frames;
        layer.frameCount = frames.length;
        layer.isSpriteSheet = frames.length > 1;
        layer.compositeImage = compositeBmp;
        layer.alpha = 1.0f;
        layer.posX = canvasWidth / 4f;
        layer.posY = canvasHeight / 4f;

        layers.add(layer);
        selectedLayerIndex = layers.size() - 1;
        applyGroupPropagation(layer);
        adoptStyleIntoPending(layer);
        refreshAll();
    }

    // ===================== PREVIEW + CONTROLS =====================

    private void refreshAll() {
        updatePreview();
        layerAdapter.notifyDataSetChanged();
        updateControls();
    }

    /**
     * Returns a representative frame index for preview display.
     * Instead of always showing digit "0", shows a realistic-looking time like
     * "2:56".
     */
    private int getPreviewFrameIndex(int elementType, int frameCount) {
        if (frameCount <= 1)
            return 0;
        int idx;
        switch (elementType) {
            case DialCompiler.TYPE_DIGITAL_HOUR:
            case DialCompiler.TYPE_HOUR_HI:
                idx = 2;
                break; // "2" → 2:56
            case DialCompiler.TYPE_HOUR_LO:
                idx = 1;
                break; // "1" → 12:56
            case DialCompiler.TYPE_DIGITAL_MIN:
            case DialCompiler.TYPE_MIN_HI:
                idx = 5;
                break; // "5" → X:56
            case DialCompiler.TYPE_SECONDS:
            case DialCompiler.TYPE_MIN_LO:
                idx = 6;
                break; // "6"
            case DialCompiler.TYPE_WEEKDAY:
                idx = 2;
                break; // Wednesday
            case DialCompiler.TYPE_DAY:
                idx = 5;
                break; // 15th
            case DialCompiler.TYPE_MONTH:
                idx = 1;
                break; // February
            case DialCompiler.TYPE_YEAR:
                idx = 6;
                break; // "6" → 2026
            case DialCompiler.TYPE_STEPS:
                idx = 7;
                break;
            case DialCompiler.TYPE_HEART:
                idx = 8;
                break;
            case DialCompiler.TYPE_CALORIE:
                idx = 3;
                break;
            case DialCompiler.TYPE_DISTANCE:
                idx = 4;
                break;
            case DialCompiler.TYPE_BATTERY:
            case DialCompiler.TYPE_BATT_STRIP:
                idx = 3;
                break; // mid-level
            case DialCompiler.TYPE_TEMP:
                idx = 2;
                break;
            case DialCompiler.TYPE_WEATHER:
                idx = 0;
                break;
            case DialCompiler.TYPE_CONNECT:
                idx = 0;
                break;
            default:
                idx = 0;
                break;
        }
        return Math.min(idx, frameCount - 1);
    }

    /**
     * For multi-digit types, creates a combined two-digit bitmap.
     * For other types, returns a single representative frame.
     */
    private Bitmap getPreviewBitmap(DialLayer layer) {
        if (layer.frames == null || layer.frames.length < 10) {
            // Not a digit sprite-sheet, return single frame
            int fi = 0;
            if (layer.frames != null && layer.frames.length > 0) {
                fi = getPreviewFrameIndex(layer.nativeElementType, layer.frames.length);
                fi = Math.min(fi, layer.frames.length - 1);
            }
            return (layer.frames != null && layer.frames.length > 0) ? layer.frames[fi] : layer.icon;
        }
        // Multi-digit combinations
        int[] indices;
        switch (layer.nativeElementType) {
            case DialCompiler.TYPE_DIGITAL_HOUR:
                indices = new int[] { 1, 2 }; // "12"
                break;
            case DialCompiler.TYPE_DIGITAL_MIN:
                indices = new int[] { 5, 6 }; // "56"
                break;
            case DialCompiler.TYPE_SECONDS:
                indices = new int[] { 3, 4 }; // "34"
                break;
            case DialCompiler.TYPE_DAY:
                indices = new int[] { 1, 5 }; // "15"
                break;
            case DialCompiler.TYPE_YEAR:
                indices = new int[] { 2, 6 }; // "26"
                break;
            case DialCompiler.TYPE_DISTANCE:
                // Show "12.3" — Frame 10 is usually the dot
                if (layer.frames.length > 10) {
                    indices = new int[] { 1, 2, 10, 3 };
                } else {
                    indices = new int[] { 4 };
                }
                break;
            default:
                int fi = getPreviewFrameIndex(layer.nativeElementType, layer.frames.length);
                return layer.frames[Math.min(fi, layer.frames.length - 1)];
        }

        // The watch advances exactly one frame-width per digit with no extra
        // spacing — render the preview the same way so on-screen positions
        // match the device pixel-for-pixel.
        int slotW = layer.frames[0].getWidth();
        int maxH = 0;
        for (int idx : indices) {
            maxH = Math.max(maxH, layer.frames[Math.min(idx, layer.frames.length - 1)].getHeight());
        }
        Bitmap combined = Bitmap.createBitmap(Math.max(1, slotW * indices.length), Math.max(1, maxH),
                Bitmap.Config.ARGB_8888);
        Canvas cc = new Canvas(combined);
        for (int i = 0; i < indices.length; i++) {
            Bitmap b = layer.frames[Math.min(indices[i], layer.frames.length - 1)];
            cc.drawBitmap(b, i * slotW, (maxH - b.getHeight()) / 2f, null);
        }
        return combined;
    }

    private void updatePreview() {
        Bitmap preview = renderComposite(true);

        // Use a single ImageView managed by the container
        if (previewImage == null) {
            previewImage = new ImageView(this);
            previewImage.setScaleType(ImageView.ScaleType.FIT_CENTER);
            previewContainer.addView(previewImage,
                    new FrameLayout.LayoutParams(
                            FrameLayout.LayoutParams.MATCH_PARENT,
                            FrameLayout.LayoutParams.MATCH_PARENT));
        }
        previewImage.setImageBitmap(preview);

        txtLayerCount.setText(String.valueOf(layers.size()));
    }

    /**
     * Renders all layers onto a canvas-sized bitmap. With editorDecorations the
     * result includes selection borders and pending-style placeholders (editor
     * preview); without them it is the clean composite used as the dial's
     * compiled preview image, so both always stay in sync.
     */
    private Bitmap renderComposite(boolean editorDecorations) {
        Bitmap preview = Bitmap.createBitmap(canvasWidth, canvasHeight, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(preview);
        canvas.drawColor(Color.BLACK);

        for (int i = 0; i < layers.size(); i++) {
            DialLayer layer = layers.get(i);

            if (layer.isColonSeparator) {
                Bitmap colonBmp = colonBitmapFor(layer);
                canvas.drawBitmap(colonBmp, layer.posX, layer.posY, null);
                if (editorDecorations && i == selectedLayerIndex) {
                    Paint border = new Paint();
                    border.setStyle(Paint.Style.STROKE);
                    border.setColor(Color.parseColor("#58A6FF"));
                    border.setStrokeWidth(2);
                    canvas.drawRect(layer.posX, layer.posY,
                            layer.posX + colonBmp.getWidth(), layer.posY + colonBmp.getHeight(), border);
                }
                continue;
            }

            if (layer.pendingStyle) {
                if (!editorDecorations)
                    continue;
                float px = layer.posX, py = layer.posY;
                int pw = 40, ph = 60;
                Paint bg = new Paint();
                bg.setColor(0x33FF8C00);
                canvas.drawRect(px, py, px + pw, py + ph, bg);
                Paint border = new Paint();
                border.setStyle(Paint.Style.STROKE);
                border.setColor(0xFFFF8C00);
                border.setStrokeWidth(2);
                canvas.drawRect(px, py, px + pw, py + ph, border);
                Paint txt = new Paint(Paint.ANTI_ALIAS_FLAG);
                txt.setColor(0xFFFF8C00);
                txt.setTextSize(18f);
                txt.setTextAlign(Paint.Align.CENTER);
                String label = layer.name != null ? layer.name.substring(0, Math.min(2, layer.name.length())) : "??";
                canvas.drawText(label, px + pw / 2f, py + ph / 2f, txt);
                txt.setTextSize(12f);
                canvas.drawText("tap", px + pw / 2f, py + ph - 6f, txt);
                if (i == selectedLayerIndex) {
                    border.setColor(Color.parseColor("#58A6FF"));
                    canvas.drawRect(px, py, px + pw, py + ph, border);
                }
                continue;
            }

            Bitmap drawBmp = getPreviewBitmap(layer);
            if (drawBmp == null)
                continue;

            Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
            paint.setAlpha((int) (layer.alpha * 255));

            boolean isHand = isHandType(layer.nativeElementType);

            Matrix m = new Matrix();
            float scaledW = drawBmp.getWidth() * layer.scale;
            float scaledH = drawBmp.getHeight() * layer.scale;
            m.postScale(layer.scale, layer.scale);

            float drawX = layer.posX;
            float drawY = layer.posY;

            if (isHand) {
                // Hands are pinned to the watch centre by their rotation pivot:
                // horizontally the image middle, vertically handTail() px from the
                // bottom — the same pivot DialCompiler writes as ctx/cty.
                float pivotX = (drawBmp.getWidth() / 2f) * layer.scale;
                float pivotY = (drawBmp.getHeight() - handTail(layer)) * layer.scale;

                drawX = (canvasWidth / 2f) - pivotX;
                drawY = (canvasHeight / 2f) - pivotY;

                float mockRotation = layer.rotation;
                if (mockRotation == 0) {
                    if (layer.nativeElementType == DialCompiler.TYPE_ARM_HOUR) mockRotation = 90; // 3h
                    else if (layer.nativeElementType == DialCompiler.TYPE_ARM_MIN) mockRotation = 0; // 0m
                    else if (layer.nativeElementType == DialCompiler.TYPE_ARM_SEC) mockRotation = 270; // 45s
                }

                // postRotate runs after postScale, so the pivot is in scaled coords
                m.postRotate(mockRotation, pivotX, pivotY);
            } else if (layer.rotation != 0) {
                m.postRotate(layer.rotation, scaledW / 2, scaledH / 2);
            }

            m.postTranslate(drawX, drawY);
            canvas.drawBitmap(drawBmp, m, paint);

            if (editorDecorations && i == selectedLayerIndex) {
                Paint border = new Paint();
                border.setStyle(Paint.Style.STROKE);
                border.setColor(Color.parseColor("#58A6FF"));
                border.setStrokeWidth(2);
                canvas.drawRect(drawX, drawY, drawX + scaledW, drawY + scaledH, border);
            }
        }
        return preview;
    }

    private void updateControls() {
        if (selectedLayerIndex >= 0 && selectedLayerIndex < layers.size()) {
            selectedLayerControls.setVisibility(View.VISIBLE);
            DialLayer layer = layers.get(selectedLayerIndex);
            String info = layer.name;
            if (layer.isSpriteSheet)
                info += " • " + layer.frameCount + " frames";
            txtSelectedLayer.setText(info);
            seekScale.setProgress((int) (layer.scale * 100));
            seekRotation.setProgress((int) layer.rotation);

            isUpdatingPosText = true;
            if (edtLayerPosX != null && !edtLayerPosX.hasFocus()) {
                edtLayerPosX.setText(String.valueOf(Math.round(layer.posX)));
            }
            if (edtLayerPosY != null && !edtLayerPosY.hasFocus()) {
                edtLayerPosY.setText(String.valueOf(Math.round(layer.posY)));
            }
            isUpdatingPosText = false;

            boolean isBg = layer.layerType == DialLayer.TYPE_BACKGROUND;
            if (btnLockBg != null) {
                btnLockBg.setVisibility(isBg ? View.VISIBLE : View.GONE);
                if (isBg) {
                    btnLockBg.setText(layer.locked ? R.string.unlock_bg : R.string.lock_bg);
                }
            }
            if (layoutBgFitControls != null) {
                layoutBgFitControls.setVisibility(isBg ? View.VISIBLE : View.GONE);
            }
            if (layoutAlignmentControls != null) {
                layoutAlignmentControls.setVisibility(isBg ? View.GONE : View.VISIBLE);
            }
            if (btnCopyStyle != null) {
                btnCopyStyle.setVisibility(hasOtherStyledLayers(layer) ? View.VISIBLE : View.GONE);
            }
        } else {
            selectedLayerControls.setVisibility(View.GONE);
            if (btnLockBg != null) btnLockBg.setVisibility(View.GONE);
        }
    }

    private void centerSelectedLayer(boolean cx, boolean cy) {
        if (selectedLayerIndex < 0 || selectedLayerIndex >= layers.size()) return;
        DialLayer l = layers.get(selectedLayerIndex);
        if (l.locked) return;
        Bitmap bmp = getPreviewBitmap(l);
        if (bmp == null) return;
        float w = bmp.getWidth() * l.scale;
        float h = bmp.getHeight() * l.scale;
        if (cx) {
            l.posX = (canvasWidth - w) / 2.0f;
        }
        if (cy) {
            l.posY = (canvasHeight - h) / 2.0f;
        }
        refreshAll();
    }

    private void fitBackground(boolean fill) {
        if (selectedLayerIndex < 0 || selectedLayerIndex >= layers.size()) return;
        DialLayer l = layers.get(selectedLayerIndex);
        if (l.layerType != DialLayer.TYPE_BACKGROUND || l.locked) return;
        Bitmap bmp = l.icon;
        if (bmp == null) return;
        float scaleX = (float) canvasWidth / bmp.getWidth();
        float scaleY = (float) canvasHeight / bmp.getHeight();
        l.scale = fill ? Math.max(scaleX, scaleY) : Math.min(scaleX, scaleY);
        l.posX = (canvasWidth - bmp.getWidth() * l.scale) / 2.0f;
        l.posY = (canvasHeight - bmp.getHeight() * l.scale) / 2.0f;
        l.rotation = 0;
        refreshAll();
    }

    private void resetBackground() {
        if (selectedLayerIndex < 0 || selectedLayerIndex >= layers.size()) return;
        DialLayer l = layers.get(selectedLayerIndex);
        if (l.layerType != DialLayer.TYPE_BACKGROUND || l.locked) return;
        l.scale = 1.0f;
        l.posX = 0;
        l.posY = 0;
        l.rotation = 0;
        refreshAll();
    }

    private boolean hasOtherStyledLayers(DialLayer currentLayer) {
        for (DialLayer l : layers) {
            if (l != currentLayer && (l.fontPath != null || l.fontSize > 0 || l.fontColor != 0)) {
                return true;
            }
        }
        return false;
    }

    private void showCopyStyleDialog() {
        if (selectedLayerIndex < 0 || selectedLayerIndex >= layers.size()) return;
        DialLayer targetLayer = layers.get(selectedLayerIndex);
        List<DialLayer> candidates = new ArrayList<>();
        List<String> labels = new ArrayList<>();
        for (DialLayer l : layers) {
            if (l != targetLayer) {
                candidates.add(l);
                labels.add(l.name);
            }
        }
        if (candidates.isEmpty()) {
            Toast.makeText(this, "No other layers to copy from", Toast.LENGTH_SHORT).show();
            return;
        }

        new AlertDialog.Builder(this)
                .setTitle(R.string.copy_style_from)
                .setItems(labels.toArray(new String[0]), (dialog, which) -> {
                    DialLayer donor = candidates.get(which);
                    targetLayer.fontPath = donor.fontPath;
                    targetLayer.fontSize = donor.fontSize;
                    targetLayer.fontColor = donor.fontColor;
                    targetLayer.fontBold = donor.fontBold;
                    targetLayer.fontItalic = donor.fontItalic;
                    targetLayer.fontStrokeColor = donor.fontStrokeColor;
                    targetLayer.fontStrokeWidth = donor.fontStrokeWidth;
                    targetLayer.fontShadowColor = donor.fontShadowColor;
                    targetLayer.fontShadowRadius = donor.fontShadowRadius;
                    targetLayer.scale = donor.scale;
                    refreshAll();
                    Toast.makeText(this, "Style copied from " + donor.name, Toast.LENGTH_SHORT).show();
                })
                .show();
    }

    // ===================== HAND SET BUILDER =====================

    private void showHandSetBuilder() {
        View dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_hand_generator, null);
        ImageView imgDialLivePreview = dialogView.findViewById(R.id.imgDialLivePreview);
        TextView txtDims = dialogView.findViewById(R.id.txtHandDimensionsInfo);
        RadioGroup rgHandTabs = dialogView.findViewById(R.id.rgHandTabs);
        Spinner spinnerStyle = dialogView.findViewById(R.id.spinnerHandStyle);
        CheckBox chkProportional = dialogView.findViewById(R.id.chkProportional);
        EditText edtWidth = dialogView.findViewById(R.id.edtHandWidth);
        EditText edtTipLen = dialogView.findViewById(R.id.edtHandTipLen);
        EditText edtTailLen = dialogView.findViewById(R.id.edtHandTailLen);
        EditText edtHexColor = dialogView.findViewById(R.id.edtHexColor);
        CheckBox chkOutline = dialogView.findViewById(R.id.chkOutline);
        CheckBox chkShadow = dialogView.findViewById(R.id.chkShadow);
        CheckBox chkRounded = dialogView.findViewById(R.id.chkRounded);
        Button btnApply = dialogView.findViewById(R.id.btnApplyHandSet);

        final HandGenerator.HandConfig[] configs = new HandGenerator.HandConfig[]{
                HandGenerator.HandConfig.getDefault("hour"),
                HandGenerator.HandConfig.getDefault("minute"),
                HandGenerator.HandConfig.getDefault("second")
        };
        final int[] currentTab = {0};

        final String[] styles = {"sword", "arrow", "baton", "needle", "club", "diamond", "leaf", "lollipop"};
        final String[] styleNames = {"Sword (Espada)", "Arrow (Flecha)", "Baton (Bastón)", "Needle (Aguja)", "Club", "Diamond (Diamante)", "Leaf (Hoja)", "Lollipop"};
        ArrayAdapter<String> styleAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, styleNames);
        spinnerStyle.setAdapter(styleAdapter);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setView(dialogView)
                .setNegativeButton(R.string.cancel, null)
                .create();

        Runnable updateDialPreview = () -> {
            Bitmap preview = HandGenerator.renderDialPreview(configs[0], configs[1], configs[2], 280);
            imgDialLivePreview.setImageBitmap(preview);
            txtDims.setText(String.format(Locale.US, "H: %d×%d (ctx=%d)\nM: %d×%d (ctx=%d)\nS: %d×%d (ctx=%d)",
                    configs[0].width + 10, configs[0].handLength + configs[0].tailLength + 10, configs[0].tailLength + 5,
                    configs[1].width + 10, configs[1].handLength + configs[1].tailLength + 10, configs[1].tailLength + 5,
                    configs[2].width + 10, configs[2].handLength + configs[2].tailLength + 10, configs[2].tailLength + 5));
        };

        final boolean[] isSettingFields = {false};
        Runnable populateFields = () -> {
            isSettingFields[0] = true;
            HandGenerator.HandConfig cfg = configs[currentTab[0]];
            for (int i = 0; i < styles.length; i++) {
                if (styles[i].equalsIgnoreCase(cfg.tipStyle)) {
                    spinnerStyle.setSelection(i);
                    break;
                }
            }
            edtWidth.setText(String.valueOf(cfg.width));
            edtTipLen.setText(String.valueOf(cfg.handLength));
            edtTailLen.setText(String.valueOf(cfg.tailLength));
            edtHexColor.setText(String.format("#%06X", (0xFFFFFF & cfg.color)));
            chkOutline.setChecked(cfg.outline);
            chkShadow.setChecked(cfg.shadow);
            chkRounded.setChecked(cfg.rounded);
            isSettingFields[0] = false;
            updateDialPreview.run();
        };

        rgHandTabs.setOnCheckedChangeListener((group, checkedId) -> {
            if (checkedId == R.id.rbHourHand) currentTab[0] = 0;
            else if (checkedId == R.id.rbMinuteHand) currentTab[0] = 1;
            else if (checkedId == R.id.rbSecondHand) currentTab[0] = 2;
            populateFields.run();
        });

        spinnerStyle.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(android.widget.AdapterView<?> parent, View v, int position, long id) {
                if (isSettingFields[0]) return;
                configs[currentTab[0]].tipStyle = styles[position];
                updateDialPreview.run();
            }
            @Override
            public void onNothingSelected(android.widget.AdapterView<?> parent) {}
        });

        android.text.TextWatcher dimWatcher = new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(android.text.Editable s) {
                if (isSettingFields[0]) return;
                try {
                    HandGenerator.HandConfig cfg = configs[currentTab[0]];
                    if (edtWidth.hasFocus()) {
                        int oldW = cfg.width;
                        int newW = Integer.parseInt(edtWidth.getText().toString());
                        cfg.width = Math.max(1, newW);
                        if (chkProportional.isChecked() && oldW > 0) {
                            float factor = (float) newW / oldW;
                            cfg.handLength = Math.round(cfg.handLength * factor);
                            cfg.tailLength = Math.round(cfg.tailLength * factor);
                            isSettingFields[0] = true;
                            edtTipLen.setText(String.valueOf(cfg.handLength));
                            edtTailLen.setText(String.valueOf(cfg.tailLength));
                            isSettingFields[0] = false;
                        }
                    } else if (edtTipLen.hasFocus()) {
                        cfg.handLength = Math.max(10, Integer.parseInt(edtTipLen.getText().toString()));
                    } else if (edtTailLen.hasFocus()) {
                        cfg.tailLength = Math.max(0, Integer.parseInt(edtTailLen.getText().toString()));
                    }
                    updateDialPreview.run();
                } catch (Exception ignored) {}
            }
        };
        edtWidth.addTextChangedListener(dimWatcher);
        edtTipLen.addTextChangedListener(dimWatcher);
        edtTailLen.addTextChangedListener(dimWatcher);

        int[] swatchIds = {R.id.viewColorWhite, R.id.viewColorRed, R.id.viewColorYellow, R.id.viewColorCyan, R.id.viewColorOrange};
        int[] swatchColors = {Color.WHITE, Color.parseColor("#FF4444"), Color.parseColor("#FFCC00"), Color.parseColor("#22D3EE"), Color.parseColor("#F97316")};
        for (int i = 0; i < swatchIds.length; i++) {
            int clr = swatchColors[i];
            View sw = dialogView.findViewById(swatchIds[i]);
            if (sw != null) {
                sw.setOnClickListener(v -> {
                    configs[currentTab[0]].color = clr;
                    edtHexColor.setText(String.format("#%06X", (0xFFFFFF & clr)));
                    updateDialPreview.run();
                });
            }
        }

        edtHexColor.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(android.text.Editable s) {
                if (isSettingFields[0]) return;
                try {
                    String hex = edtHexColor.getText().toString().trim();
                    if (!hex.startsWith("#")) hex = "#" + hex;
                    configs[currentTab[0]].color = Color.parseColor(hex);
                    updateDialPreview.run();
                } catch (Exception ignored) {}
            }
        });

        chkOutline.setOnCheckedChangeListener((btn, isChecked) -> {
            if (isSettingFields[0]) return;
            configs[currentTab[0]].outline = isChecked;
            updateDialPreview.run();
        });
        chkShadow.setOnCheckedChangeListener((btn, isChecked) -> {
            if (isSettingFields[0]) return;
            configs[currentTab[0]].shadow = isChecked;
            updateDialPreview.run();
        });
        chkRounded.setOnCheckedChangeListener((btn, isChecked) -> {
            if (isSettingFields[0]) return;
            configs[currentTab[0]].rounded = isChecked;
            updateDialPreview.run();
        });

        populateFields.run();

        btnApply.setOnClickListener(v -> {
            dialog.dismiss();
            applyHandSet(configs[0], configs[1], configs[2]);
        });

        dialog.show();
    }

    private void applyHandSet(HandGenerator.HandConfig hourCfg, HandGenerator.HandConfig minCfg, HandGenerator.HandConfig secCfg) {
        for (int i = layers.size() - 1; i >= 0; i--) {
            int t = layers.get(i).nativeElementType;
            if (isHandType(t)) {
                layers.remove(i);
            }
        }

        int hW = hourCfg.width + 10;
        int hH = hourCfg.handLength + hourCfg.tailLength + 10;
        int hCtx = hourCfg.tailLength + 5;
        Bitmap hourBmp = HandGenerator.generateHandBitmap(hourCfg, hW, hH);
        DialLayer hourLayer = new DialLayer(DialLayer.TYPE_ARM, hourBmp, getString(R.string.hand_hour), DialCompiler.TYPE_ARM_HOUR);
        hourLayer.pivotTail = hCtx;
        hourLayer.handConfig = hourCfg;
        layers.add(hourLayer);

        int mW = minCfg.width + 10;
        int mH = minCfg.handLength + minCfg.tailLength + 10;
        int mCtx = minCfg.tailLength + 5;
        Bitmap minBmp = HandGenerator.generateHandBitmap(minCfg, mW, mH);
        DialLayer minLayer = new DialLayer(DialLayer.TYPE_ARM, minBmp, getString(R.string.hand_minute), DialCompiler.TYPE_ARM_MIN);
        minLayer.pivotTail = mCtx;
        minLayer.handConfig = minCfg;
        layers.add(minLayer);

        int sW = secCfg.width + 10;
        int sH = secCfg.handLength + secCfg.tailLength + 10;
        int sCtx = secCfg.tailLength + 5;
        Bitmap secBmp = HandGenerator.generateHandBitmap(secCfg, sW, sH);
        DialLayer secLayer = new DialLayer(DialLayer.TYPE_ARM, secBmp, getString(R.string.hand_second), DialCompiler.TYPE_ARM_SEC);
        secLayer.pivotTail = sCtx;
        secLayer.handConfig = secCfg;
        layers.add(secLayer);

        refreshAll();
        Toast.makeText(this, R.string.hand_apply_set, Toast.LENGTH_SHORT).show();
    }

    // ===================== BATTERY GENERATOR =====================

    private void showBatteryGenerator() {
        View dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_battery_generator, null);
        Spinner spinnerStyle = dialogView.findViewById(R.id.spinnerBatteryStyle);
        RadioGroup rgColorMode = dialogView.findViewById(R.id.rgBatteryColorMode);
        EditText edtW = dialogView.findViewById(R.id.edtBatteryWidth);
        EditText edtH = dialogView.findViewById(R.id.edtBatteryHeight);
        CheckBox chkLightning = dialogView.findViewById(R.id.chkShowLightning);
        Button btnApply = dialogView.findViewById(R.id.btnApplyBattery);

        ImageView[] previews = new ImageView[]{
                dialogView.findViewById(R.id.imgBatteryPreview0),
                dialogView.findViewById(R.id.imgBatteryPreview1),
                dialogView.findViewById(R.id.imgBatteryPreview2),
                dialogView.findViewById(R.id.imgBatteryPreview3),
                dialogView.findViewById(R.id.imgBatteryPreview4),
                dialogView.findViewById(R.id.imgBatteryPreview5)
        };

        BatteryGenerator.BatteryConfig cfg = new BatteryGenerator.BatteryConfig();
        String[] styles = {"horizontal_capsule", "vertical_capsule", "circular_ring", "pill_dots"};
        String[] styleNames = {"Horizontal Capsule", "Vertical Capsule", "Circular Ring", "Pill Dots"};
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, styleNames);
        spinnerStyle.setAdapter(adapter);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setView(dialogView)
                .setNegativeButton(R.string.cancel, null)
                .create();

        Runnable updatePreviews = () -> {
            for (int i = 0; i < cfg.frameCount; i++) {
                Bitmap frame = BatteryGenerator.generateSingleFrame(cfg, i, cfg.frameCount);
                previews[i].setImageBitmap(frame);
            }
        };

        spinnerStyle.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(android.widget.AdapterView<?> parent, View v, int position, long id) {
                cfg.presetStyle = styles[position];
                updatePreviews.run();
            }
            @Override public void onNothingSelected(android.widget.AdapterView<?> parent) {}
        });

        rgColorMode.setOnCheckedChangeListener((group, checkedId) -> {
            cfg.colorMode = (checkedId == R.id.rbColorDynamic) ? "dynamic" : "solid";
            updatePreviews.run();
        });

        chkLightning.setOnCheckedChangeListener((btn, isChecked) -> {
            cfg.showLightning = isChecked;
            updatePreviews.run();
        });

        android.text.TextWatcher dimWatcher = new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(android.text.Editable s) {
                try {
                    cfg.frameWidth = Math.max(16, Integer.parseInt(edtW.getText().toString()));
                    cfg.frameHeight = Math.max(16, Integer.parseInt(edtH.getText().toString()));
                    updatePreviews.run();
                } catch (Exception ignored) {}
            }
        };
        edtW.addTextChangedListener(dimWatcher);
        edtH.addTextChangedListener(dimWatcher);

        updatePreviews.run();

        btnApply.setOnClickListener(v -> {
            dialog.dismiss();
            Bitmap fullSheet = BatteryGenerator.generateVerticalSpriteSheet(cfg);
            Bitmap[] frames = new Bitmap[cfg.frameCount];
            int fh = cfg.frameHeight;
            for (int i = 0; i < cfg.frameCount; i++) {
                frames[i] = Bitmap.createBitmap(fullSheet, 0, i * fh, cfg.frameWidth, fh);
            }
            DialLayer layer = new DialLayer(DialLayer.TYPE_ELEMENT, frames[frames.length - 1], getBlockLabel(DialCompiler.TYPE_BATT_STRIP), DialCompiler.TYPE_BATT_STRIP);
            layer.frames = frames;
            layer.frameCount = cfg.frameCount;
            layer.isSpriteSheet = true;
            layer.compositeImage = fullSheet;
            layer.batteryConfig = cfg;
            layers.add(layer);
            refreshAll();
            Toast.makeText(this, R.string.battery_apply, Toast.LENGTH_SHORT).show();
        });

        dialog.show();
    }

    // ===================== WEATHER GENERATOR =====================

    private void showWeatherGenerator() {
        View dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_weather_generator, null);
        EditText edtW = dialogView.findViewById(R.id.edtWeatherWidth);
        EditText edtH = dialogView.findViewById(R.id.edtWeatherHeight);
        Button btnApply = dialogView.findViewById(R.id.btnApplyWeather);

        ImageView[] previews = new ImageView[12];
        int[] iconIds = {
                R.id.imgWeather0, R.id.imgWeather1, R.id.imgWeather2, R.id.imgWeather3,
                R.id.imgWeather4, R.id.imgWeather5, R.id.imgWeather6, R.id.imgWeather7,
                R.id.imgWeather8, R.id.imgWeather9, R.id.imgWeather10, R.id.imgWeather11
        };
        for (int i = 0; i < 12; i++) {
            previews[i] = dialogView.findViewById(iconIds[i]);
        }

        WeatherGenerator.WeatherConfig cfg = new WeatherGenerator.WeatherConfig();

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setView(dialogView)
                .setNegativeButton(R.string.cancel, null)
                .create();

        Runnable updatePreviews = () -> {
            for (int i = 0; i < cfg.frameCount; i++) {
                Bitmap frame = WeatherGenerator.generateSingleFrame(cfg, i);
                previews[i].setImageBitmap(frame);
            }
        };

        android.text.TextWatcher dimWatcher = new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(android.text.Editable s) {
                try {
                    cfg.frameWidth = Math.max(16, Integer.parseInt(edtW.getText().toString()));
                    cfg.frameHeight = Math.max(16, Integer.parseInt(edtH.getText().toString()));
                    updatePreviews.run();
                } catch (Exception ignored) {}
            }
        };
        edtW.addTextChangedListener(dimWatcher);
        edtH.addTextChangedListener(dimWatcher);

        updatePreviews.run();

        btnApply.setOnClickListener(v -> {
            dialog.dismiss();
            Bitmap fullSheet = WeatherGenerator.generateVerticalSpriteSheet(cfg);
            Bitmap[] frames = new Bitmap[cfg.frameCount];
            int fh = cfg.frameHeight;
            for (int i = 0; i < cfg.frameCount; i++) {
                frames[i] = Bitmap.createBitmap(fullSheet, 0, i * fh, cfg.frameWidth, fh);
            }
            DialLayer layer = new DialLayer(DialLayer.TYPE_ELEMENT, frames[1], getBlockLabel(DialCompiler.TYPE_WEATHER), DialCompiler.TYPE_WEATHER);
            layer.frames = frames;
            layer.frameCount = cfg.frameCount;
            layer.isSpriteSheet = true;
            layer.compositeImage = fullSheet;
            layer.weatherConfig = cfg;
            layers.add(layer);
            refreshAll();
            Toast.makeText(this, R.string.weather_apply, Toast.LENGTH_SHORT).show();
        });

        dialog.show();
    }

    // ===================== COMPILE WITH NAME =====================

    private void showNameDialogThenCompile() {
        if (layers.isEmpty()) {
            Toast.makeText(this, R.string.add_one_layer, Toast.LENGTH_SHORT).show();
            return;
        }

        EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        input.setHint(R.string.dial_name_hint);
        if (suggestedDialName != null && !suggestedDialName.isEmpty()) {
            input.setText(suggestedDialName);
        }
        input.setPadding(48, 24, 48, 24);

        new AlertDialog.Builder(this)
                .setTitle(R.string.enter_dial_name)
                .setView(input)
                .setPositiveButton(R.string.save, (dialog, which) -> {
                    String name = input.getText().toString().trim();
                    if (name.isEmpty())
                        name = "dial";
                    // Sanitize: replace spaces with _, remove non-alphanumeric except _-
                    name = name.replaceAll("[^a-zA-Z0-9_\\-]", "_");

                    // Check for duplicates
                    File userDialsDir = new File(getFilesDir(), "user_dials");
                    if (!userDialsDir.exists())
                        userDialsDir.mkdirs();
                    File targetFile = new File(userDialsDir, name + ".bin");
                    boolean isSameNameAsEditing = suggestedDialName != null && suggestedDialName.equals(name);
                    if (targetFile.exists() && !isSameNameAsEditing) {
                        Toast.makeText(this, R.string.name_exists, Toast.LENGTH_LONG).show();
                        return;
                    }

                    compileAndFinish(name);
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void compileAndFinish(String dialName) {
        btnUpload.setEnabled(false);
        btnUpload.setText(R.string.compiling);

        new Thread(() -> {
            try {
                DialCompiler compiler = new DialCompiler(canvasWidth, canvasHeight);

                // Auto-generate preview from current canvas state using the exact
                // same renderer as the editor preview (hands centred + rotated)
                Bitmap previewBmp = renderComposite(false);

                // RESIZE PREVIEW TO 280x280 (Required by chipset)
                // normalizeForWatch quantizes to RGB565 + flattens alpha
                Bitmap normalizedPreview = DialCompiler.normalizeForWatch(previewBmp, 280, 280);

                DialCompiler.DialBlock previewBlock = new DialCompiler.DialBlock();
                previewBlock.type = DialCompiler.TYPE_PREVIEW;
                previewBlock.width = 280;
                previewBlock.height = 280;
                previewBlock.x = 0;
                previewBlock.y = 0;
                previewBlock.frames = 1;
                previewBlock.hasAlpha = false; // Preview is always RGB
                previewBlock.images = new Bitmap[] { normalizedPreview };
                compiler.addBlock(previewBlock);

                for (DialLayer layer : layers) {
                    if (layer.pendingStyle) continue;
                    if (layer.isColonSeparator) {
                        // Compile the ":" separator as a static decoration so the
                        // watch shows the same time layout as the editor preview.
                        Bitmap colonBmp = colonBitmapFor(layer);
                        DialCompiler.DialBlock colonBlock = new DialCompiler.DialBlock();
                        colonBlock.type = DialCompiler.TYPE_BERRY;
                        colonBlock.images = new Bitmap[] { colonBmp };
                        colonBlock.frames = 1;
                        colonBlock.width = colonBmp.getWidth();
                        colonBlock.height = colonBmp.getHeight();
                        colonBlock.x = Math.round(layer.posX);
                        colonBlock.y = Math.round(layer.posY);
                        colonBlock.hasAlpha = true;
                        compiler.addBlock(colonBlock);
                        continue;
                    }
                    DialCompiler.DialBlock block = new DialCompiler.DialBlock();
                    block.type = layer.nativeElementType;

                    // All non-hand blocks anchor by their TOP-LEFT corner at
                    // (posx, posy) — same convention the desktop designer uses.
                    block.x = (int) layer.posX;
                    block.y = (int) layer.posY;

                    boolean isHand = isHandType(block.type);

                    if (block.type == DialCompiler.TYPE_BACKGROUND) {
                        int frameCount = 1;
                        Bitmap[] renderedFrames = new Bitmap[frameCount];
                        Bitmap bgBitmap = Bitmap.createBitmap(canvasWidth, canvasHeight, Bitmap.Config.ARGB_8888);
                        Canvas bgCanvas = new Canvas(bgBitmap);
                        bgCanvas.drawColor(Color.BLACK);
                        Bitmap layerBmp = (layer.frames != null && layer.frames.length > 0) ? layer.frames[0] : layer.icon;
                        if (layerBmp != null) {
                            Matrix m = new Matrix();
                            m.postScale(layer.scale, layer.scale);
                            if (layer.rotation != 0) {
                                float sw = layerBmp.getWidth() * layer.scale;
                                float sh = layerBmp.getHeight() * layer.scale;
                                m.postRotate(layer.rotation, sw / 2, sh / 2);
                            }
                            m.postTranslate(layer.posX, layer.posY);
                            bgCanvas.drawBitmap(layerBmp, m, null);
                        }
                        renderedFrames[0] = DialCompiler.normalizeForWatch(bgBitmap, canvasWidth, canvasHeight);
                        block.images = renderedFrames;
                        block.width = canvasWidth;
                        block.height = canvasHeight;
                        block.frames = 1;
                        block.x = 0;
                        block.y = 0;
                    } else if (block.type == DialCompiler.TYPE_ANIM) {
                        int frameCount = (layer.frames != null && layer.frames.length > 0) ? layer.frames.length : 1;
                        Bitmap[] renderedFrames = new Bitmap[frameCount];
                        Bitmap firstFrame = (layer.frames != null && layer.frames.length > 0) ? layer.frames[0] : layer.icon;
                        if (firstFrame == null) continue;
                        int animW = Math.round(firstFrame.getWidth() * layer.scale);
                        int animH = Math.round(firstFrame.getHeight() * layer.scale);

                        for (int fi = 0; fi < frameCount; fi++) {
                            Bitmap frameBmp = (layer.frames != null && layer.frames.length > fi) ? layer.frames[fi] : firstFrame;
                            Bitmap scaledFrame = Bitmap.createScaledBitmap(frameBmp, animW, animH, true);
                            renderedFrames[fi] = DialCompiler.normalizeForWatch(scaledFrame, animW, animH);
                        }
                        block.images = renderedFrames;
                        block.width = animW;
                        block.height = animH;
                        block.frames = frameCount;
                        block.x = Math.round(layer.posX);
                        block.y = Math.round(layer.posY);
                        block.animIntervalMs = layer.animIntervalMs;
                    } else if (layer.frames != null && layer.frames.length > 0) {
                        int scaledW = Math.max(1, (int) (layer.frames[0].getWidth() * layer.scale));
                        int scaledH = Math.max(1, (int) (layer.frames[0].getHeight() * layer.scale));
                        
                        // Apply scaling to hands too! (Previously ignored)
                        if (Math.abs(layer.scale - 1.0f) > 0.01f || isHand) {
                            Bitmap[] scaledFrames = new Bitmap[layer.frames.length];
                            for (int fi = 0; fi < layer.frames.length; fi++) {
                                scaledFrames[fi] = Bitmap.createScaledBitmap(
                                        layer.frames[fi], scaledW, scaledH, true);
                            }
                            block.images = scaledFrames;
                        } else {
                            block.images = layer.frames;
                        }
                        block.width = scaledW;
                        block.height = scaledH;
                        block.frames = layer.frames.length;
                    } else if (layer.icon != null) {
                        int scaledW = Math.max(1, (int) (layer.icon.getWidth() * layer.scale));
                        int scaledH = Math.max(1, (int) (layer.icon.getHeight() * layer.scale));
                        if (Math.abs(layer.scale - 1.0f) > 0.01f) {
                            block.images = new Bitmap[] { Bitmap.createScaledBitmap(
                                    layer.icon, scaledW, scaledH, true) };
                        } else {
                            block.images = new Bitmap[] { layer.icon };
                        }
                        block.frames = 1;
                        block.width = scaledW;
                        block.height = scaledH;
                    }

                    block.hasAlpha = (block.type != DialCompiler.TYPE_BACKGROUND &&
                            block.type != DialCompiler.TYPE_ANIM &&
                            block.type != DialCompiler.TYPE_PREVIEW);

                    // Analog hands: RAW compression, pivot placed at the canvas
                    // centre. pivotTail is in final (scaled) image pixels because
                    // the frames were rescaled above.
                    if (isHand) {
                        block.compress = 0;
                        block.x = canvasWidth / 2;
                        block.y = canvasHeight / 2;
                        block.pivotTail = Math.max(1, Math.round(handTail(layer) * layer.scale));
                    }

                    compiler.addBlock(block);
                }

                String filename = dialName + ".bin";

                File cacheDir = getCacheDir();
                File outFile = compiler.compile(cacheDir, filename);

                if (outFile != null && outFile.exists()) {
                    File userDialsDir = new File(getFilesDir(), "user_dials");
                    if (!userDialsDir.exists())
                        userDialsDir.mkdirs();
                    File savedFile = new File(userDialsDir, filename);
                    copyFile(outFile, savedFile);

                    String size = outFile.length() > 1024
                            ? String.format("%.1f KB", outFile.length() / 1024.0)
                            : outFile.length() + " bytes";

                    runOnUiThread(() -> {
                        btnUpload.setEnabled(true);
                        btnUpload.setText(R.string.compile);
                        // Show success dialog with Send option
                        new AlertDialog.Builder(this)
                                .setTitle("✅ " + filename)
                                .setMessage(size)
                                .setPositiveButton(R.string.send_dial, (d, w) -> {
                                    try {
                                        java.io.FileInputStream fis = new java.io.FileInputStream(savedFile);
                                        java.io.ByteArrayOutputStream byteBuffer = new java.io.ByteArrayOutputStream();
                                        byte[] buffer = new byte[4096];
                                        int len;
                                        while ((len = fis.read(buffer)) != -1)
                                            byteBuffer.write(buffer, 0, len);
                                        fis.close();
                                        byte[] fileBytesToSend = byteBuffer.toByteArray();

                                        com.example.dialsender.ble.BleManager bleManager = com.example.dialsender.ble.BleManager
                                                .getInstance(DialEditorActivity.this);

                                        android.widget.LinearLayout layout = new android.widget.LinearLayout(
                                                DialEditorActivity.this);
                                        layout.setOrientation(android.widget.LinearLayout.VERTICAL);
                                        layout.setPadding(50, 40, 50, 0);

                                        android.widget.ProgressBar progressBar = new android.widget.ProgressBar(
                                                DialEditorActivity.this, null,
                                                android.R.attr.progressBarStyleHorizontal);
                                        progressBar.setIndeterminate(false);
                                        progressBar.setMax(100);
                                        progressBar.setProgress(0);
                                        layout.addView(progressBar);

                                        android.widget.TextView progressText = new android.widget.TextView(
                                                DialEditorActivity.this);
                                        progressText.setText(getString(R.string.waiting));
                                        progressText.setPadding(0, 20, 0, 0);
                                        layout.addView(progressText);

                                        final com.example.dialsender.ble.BleManager.BleStateListener[] xferListener =
                                                new com.example.dialsender.ble.BleManager.BleStateListener[1];

                                        androidx.appcompat.app.AlertDialog progressDialog = new AlertDialog.Builder(
                                                DialEditorActivity.this)
                                                .setTitle(getString(R.string.transfer))
                                                .setView(layout)
                                                .setNegativeButton(R.string.cancel, (d2, w2) -> {
                                                    bleManager.cancelTransfer();
                                                    if (xferListener[0] != null)
                                                        bleManager.removeListener(xferListener[0]);
                                                })
                                                .setCancelable(false)
                                                .create();

                                        progressDialog.show();

                                        xferListener[0] =
                                                new com.example.dialsender.ble.BleManager.BleStateListener() {
                                                    @Override
                                                    public void onConnectionStateChange(boolean connected,
                                                            boolean sessionReady) {
                                                    }

                                                    @Override
                                                    public void onHealthDataReceived(String keyName, byte[] payload) {
                                                    }

                                                    @Override
                                                    public void onHealthSyncComplete() {
                                                    }

                                                    @Override
                                                    public void onTransferProgress(int percent, long bytesTransferred,
                                                            long totalBytes) {
                                                        progressBar.setProgress(percent);
                                                        progressText.setText(
                                                                bytesTransferred + " / " + totalBytes + " bytes");
                                                    }

                                                    @Override
                                                    public void onTransferComplete() {
                                                        progressDialog.dismiss();
                                                        Toast.makeText(DialEditorActivity.this, R.string.dial_sent_ok,
                                                                Toast.LENGTH_LONG).show();
                                                        bleManager.removeListener(xferListener[0]);
                                                    }

                                                    @Override
                                                    public void onTransferFailed(String reason) {
                                                        progressDialog.dismiss();
                                                        Toast.makeText(DialEditorActivity.this,
                                                                getString(R.string.transfer_failed, reason),
                                                                Toast.LENGTH_LONG).show();
                                                        bleManager.removeListener(xferListener[0]);
                                                    }

                                                    @Override
                                                    public void onLogUpdated() {
                                                    }
                                                };
                                        bleManager.addListener(xferListener[0]);

                                        bleManager.startFileTransfer(fileBytesToSend);
                                    } catch (Exception e) {
                                        Toast.makeText(DialEditorActivity.this, "Error: " + e.getMessage(),
                                                Toast.LENGTH_SHORT).show();
                                    }
                                })
                                .setNegativeButton(R.string.close, null)
                                .show();
                    });
                } else {
                    runOnUiThread(() -> {
                        btnUpload.setEnabled(true);
                        btnUpload.setText(R.string.compile);
                        Toast.makeText(this, R.string.compile_failed, Toast.LENGTH_SHORT).show();
                    });
                }
            } catch (Exception e) {
                e.printStackTrace();
                runOnUiThread(() -> {
                    btnUpload.setEnabled(true);
                    btnUpload.setText(R.string.compile);
                    Toast.makeText(this, "Error: " + e.getMessage(), Toast.LENGTH_LONG).show();
                });
            }
        }).start();
    }

    private Bitmap ensureRgb565(Bitmap source) {
        if (source.getConfig() == Bitmap.Config.RGB_565 && !source.hasAlpha())
            return source;
        Bitmap rgb565Bmp = Bitmap.createBitmap(source.getWidth(), source.getHeight(), Bitmap.Config.RGB_565);
        Canvas canvas = new Canvas(rgb565Bmp);
        canvas.drawColor(android.graphics.Color.BLACK);
        canvas.drawBitmap(source, 0, 0, null);
        return rgb565Bmp;
    }

    // ===================== SVG EDITOR =====================

    private void showSVGEditor(Uri svgUri, int elementType) {
        AlertDialog dialog;
        View view = LayoutInflater.from(this).inflate(R.layout.dialog_svg_editor, null);
        ImageView imgPreview = view.findViewById(R.id.imgSvgPreview);
        LinearLayout colorPalette = view.findViewById(R.id.svgColorPalette);
        LinearLayout containerBattery = view.findViewById(R.id.containerBatteryOpts);
        SeekBar seekLevels = view.findViewById(R.id.seekLevels);
        TextView txtLevels = view.findViewById(R.id.txtLevelsVal);
        android.widget.CheckBox chkClip = view.findViewById(R.id.chkClipVertical);

        boolean isBattery = (elementType == DialCompiler.TYPE_BATTERY ||
                elementType == DialCompiler.TYPE_BATT_STRIP ||
                elementType == DialCompiler.TYPE_PROGRESS1 ||
                elementType == DialCompiler.TYPE_PROGRESS2);

        if (isBattery) {
            containerBattery.setVisibility(View.VISIBLE);
            int defFrames = DialCompiler.getDefaultFrameCount(elementType);
            seekLevels.setProgress(defFrames);
            txtLevels.setText(getString(R.string.levels_count, defFrames));
        } else {
            containerBattery.setVisibility(View.GONE);
        }

        seekLevels.setOnSeekBarChangeListener(new SimpleSeekListener() {
            @Override
            public void onProgressChanged(SeekBar sb, int val, boolean user) {
                txtLevels.setText(getString(R.string.levels_count, val));
            }
        });

        // Color Palette
        final int[] colors = {
                Color.WHITE, Color.BLACK, Color.parseColor("#FF4444"), Color.parseColor("#44FF44"),
                Color.parseColor("#4488FF"), Color.parseColor("#FFDD44"),
                Color.parseColor("#FF44FF"), Color.parseColor("#44FFFF"),
                Color.parseColor("#FF8844"), Color.parseColor("#88FF44"),
                Color.TRANSPARENT // Original color
        };
        final int[] selectedColor = { Color.WHITE }; // Default tint white

        for (int clr : colors) {
            View swatch = new View(this);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1);
            lp.setMargins(2, 2, 2, 2);
            swatch.setLayoutParams(lp);
            swatch.setTag(clr);
            GradientDrawable gd = new GradientDrawable();
            if (clr == Color.TRANSPARENT) {
                gd.setColor(Color.LTGRAY); // visual placeholder for "None"
            } else {
                gd.setColor(clr);
            }
            gd.setCornerRadius(6);
            if (clr == selectedColor[0])
                gd.setStroke(3, Color.parseColor("#58A6FF"));
            swatch.setBackground(gd);
            swatch.setOnClickListener(v -> {
                selectedColor[0] = clr;
                for (int i = 0; i < colorPalette.getChildCount(); i++) {
                    View child = colorPalette.getChildAt(i);
                    GradientDrawable bg = (GradientDrawable) child.getBackground();
                    bg.setStroke(3, ((int) child.getTag() == clr) ? Color.parseColor("#58A6FF") : Color.TRANSPARENT);
                }
                // Update preview
                updateSVGPreview(imgPreview, svgUri, selectedColor[0]);
            });
            colorPalette.addView(swatch);
        }

        // Initial Preview
        updateSVGPreview(imgPreview, svgUri, selectedColor[0]);

        new AlertDialog.Builder(this)
                .setTitle(R.string.svg_generator)
                .setView(view)
                .setPositiveButton(R.string.save, (d, w) -> {
                    generateSVGLayer(svgUri, elementType, selectedColor[0],
                            isBattery ? seekLevels.getProgress() : 1,
                            isBattery && chkClip.isChecked());
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void updateSVGPreview(ImageView target, Uri uri, int tintColor) {
        try {
            InputStream is = getContentResolver().openInputStream(uri);
            SVG svg = SVG.getFromInputStream(is);
            is.close();

            if (tintColor != Color.TRANSPARENT) {
                // Apply tint to document locally for preview?
                // AndroidSVG doesn't easily support dynamic tinting of the whole doc structure
                // without CSS
                // Easier: Rasterize then tint Bitmap
            }

            // Render to a decent preview size (e.g. 200x200)
            // But preserve aspect ratio
            float docW = svg.getDocumentWidth();
            float docH = svg.getDocumentHeight();
            if (docW <= 0 || docH <= 0) {
                // Try viewBox
                if (svg.getDocumentViewBox() != null) {
                    docW = svg.getDocumentViewBox().width();
                    docH = svg.getDocumentViewBox().height();
                } else {
                    docW = 100;
                    docH = 100;
                }
            }

            float scale = Math.min(200f / docW, 200f / docH);
            int w = (int) (docW * scale);
            int h = (int) (docH * scale);

            Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(bmp);

            if (tintColor != Color.TRANSPARENT) {
                // Not fully supported by AndroidSVG directly, use Paint/PorterDuff on the
                // bitmap later?
                // Actually, let's just render standard, then apply Tint filter if needed?
                // Or better: render standard, then apply ColorFilter to the ImageView?
                // But we need to generate correct bitmaps later.

                // For valid generation, we need to rasterize with tint.
                // Since AndroidSVG is read-only mostly, good trick: use CSS?
                // Or: Render to bitmap, then draw that bitmap with ColorFilter to a new bitmap.
            }

            svg.setDocumentWidth(w);
            svg.setDocumentHeight(h);
            svg.renderToCanvas(c);

            if (tintColor != Color.TRANSPARENT) {
                Bitmap tinted = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
                Canvas tc = new Canvas(tinted);
                Paint p = new Paint();
                p.setColorFilter(
                        new android.graphics.PorterDuffColorFilter(tintColor, android.graphics.PorterDuff.Mode.SRC_IN));
                tc.drawBitmap(bmp, 0, 0, p);
                bmp.recycle();
                target.setImageBitmap(tinted);
            } else {
                target.setImageBitmap(bmp);
            }

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void showSVGEditor(int elementType, String assetPath) {
        View view = LayoutInflater.from(this).inflate(R.layout.dialog_svg_editor, null);
        ImageView imgPreview = view.findViewById(R.id.imgSvgPreview);
        SeekBar seekLevels = view.findViewById(R.id.seekLevels);
        TextView txtLevels = view.findViewById(R.id.txtLevelsVal);
        CheckBox switchClip = view.findViewById(R.id.chkClipVertical);
        LinearLayout colorPalette = view.findViewById(R.id.svgColorPalette);
        // Button btnPickColor = view.findViewById(R.id.btnPickColor); // Removed as not
        // in XML
        // Assuming user wants to pick tint from palette or wheel.
        // XML had colorPalette.

        final int[] tintColor = { Color.WHITE }; // Default tint
        final int[] levels = { 5 }; // Default levels for battery
        final boolean[] clip = { true };

        // Helper to update preview
        Runnable updatePreview = () -> {
            try {
                InputStream is = getAssets().open(assetPath);
                com.caverock.androidsvg.SVG svg = com.caverock.androidsvg.SVG.getFromInputStream(is);
                is.close();
                // We need to simulate clipping/tinting for preview
                // It's complex to preview clipping exactly without generating bitmaps
                // Let's just show the tinted SVG for now
                // Render to a decent preview size (e.g. 200x200)
                float docW = svg.getDocumentWidth();
                float docH = svg.getDocumentHeight();
                if (docW <= 0)
                    docW = 100;
                if (docH <= 0)
                    docH = 100;

                float scale = Math.min(200f / docW, 200f / docH);
                int w = (int) (docW * scale);
                int h = (int) (docH * scale);

                Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
                Canvas c = new Canvas(bmp);
                c.scale(scale, scale);
                svg.renderToCanvas(c);

                // apply tint
                Bitmap tinted = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
                Canvas tc = new Canvas(tinted);
                Paint p = new Paint();
                p.setColorFilter(new android.graphics.PorterDuffColorFilter(tintColor[0],
                        android.graphics.PorterDuff.Mode.SRC_IN));
                tc.drawBitmap(bmp, 0, 0, p);
                bmp.recycle();
                imgPreview.setImageBitmap(tinted);
            } catch (Exception e) {
                e.printStackTrace();
            }
        };

        // ... Setup UI listeners (simplified for brevity, copy from existing
        // showSVGEditor or refactor to share)
        // Actually, showSVGEditor(Uri) is not fully visible in snippet, but I should
        // use the same logic overloads.
        // I will implement a shared helper ShowSVGEditor(Uri uri, String assetPath, int
        // elementType)
        // But for now, let's just duplicate the essentials for asset path to be safe
        // and quick.

        // Color Palette
        int[] colors = { Color.WHITE, Color.BLACK, Color.RED, Color.GREEN, Color.BLUE, Color.CYAN, Color.MAGENTA,
                Color.YELLOW };
        for (int c : colors) {
            View swatch = new View(this);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, 100, 1);
            lp.setMargins(4, 4, 4, 4);
            swatch.setLayoutParams(lp);
            swatch.setBackgroundColor(c);
            swatch.setOnClickListener(v -> {
                tintColor[0] = c;
                updatePreview.run();
            });
            colorPalette.addView(swatch);
        }

        seekLevels.setOnSeekBarChangeListener(new SimpleSeekListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                levels[0] = Math.max(1, progress);
                txtLevels.setText(getString(R.string.levels_count, levels[0]));
            }
        });

        if (elementType == DialCompiler.TYPE_BATTERY) {
            seekLevels.setVisibility(View.VISIBLE);
            txtLevels.setVisibility(View.VISIBLE);
            switchClip.setVisibility(View.VISIBLE);
        } else {
            seekLevels.setVisibility(View.GONE);
            txtLevels.setVisibility(View.GONE);
            switchClip.setVisibility(View.GONE);
        }

        updatePreview.run();

        new AlertDialog.Builder(this)
                .setTitle(R.string.svg_generator)
                .setView(view)
                .setPositiveButton(R.string.action_add, (dialog, which) -> {
                    // Generate Layer
                    generateSVGLayerFromAsset(assetPath, elementType, tintColor[0], levels[0], switchClip.isChecked());
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void generateSVGLayerFromAsset(String assetPath, int elementType, int tintColor, int levels,
            boolean clipVertical) {
        try {
            InputStream is = getAssets().open(assetPath);
            com.caverock.androidsvg.SVG svg = com.caverock.androidsvg.SVG.getFromInputStream(is);
            is.close();

            // Common logic with generateSVGLayer(Uri).
            // Reuse logic?
            // Yes, extract core logic.
            // For now, implement inline to avoid disturbing existing method too much.

            float docW = svg.getDocumentWidth();
            float docH = svg.getDocumentHeight();
            if (docW <= 0)
                docW = 100;
            if (docH <= 0)
                docH = 100;

            // Render to Bitmap
            // Scale to fit screen logic? Or fixed size?
            // Let's assume reasonable icon size relative to canvas (e.g. 1/4 width?)
            // Or keep original density?
            // Assets might be small.
            // Let's render at 100x100 or 200x200 if no size.
            // Better: Render at a size suitable for the watch face (e.g. 50-100px).
            int w = (int) docW;
            int h = (int) docH;
            if (w < 50) {
                w *= 4;
                h *= 4;
            } // Scale up small icons

            Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(bmp);
            c.scale((float) w / docW, (float) h / docH);
            svg.renderToCanvas(c);

            // Tint
            Bitmap tinted = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            Canvas tc = new Canvas(tinted);
            Paint p = new Paint();
            p.setColorFilter(
                    new android.graphics.PorterDuffColorFilter(tintColor, android.graphics.PorterDuff.Mode.SRC_IN));
            tc.drawBitmap(bmp, 0, 0, p);
            bmp.recycle();
            bmp = tinted;

            if (elementType == DialCompiler.TYPE_BATTERY) {
                // Clip for levels
                int frameH = h / levels;
                if (clipVertical) {
                    // We need 'levels' frames.
                    // If 'clipVertical' is true, we assume the SVG is a full battery and we crop
                    // it?
                    // Or we assume the SVG is one state and we duplicate it?
                    // The user said "Clip Vertical".
                    // Usually implies the battery icon is full, and we mask it.
                    // ... (Implementation detail: create frames by cropping the full icon)
                    // Actually, if we just want a static icon that changes color or shape, we need
                    // multiple frames.
                    // If the SVG is just one static icon, we can't magically make it empty unless
                    // we mask it.
                    // Let's assume the user wants to generate a "strip" of images by cropping the
                    // single SVG?
                    // Simple approach: Generate 'levels' frames.
                    // Frame 0: Empty (or 1 segment). Frame N: Full.
                    // If clipVertical:
                    // Loop i from 0 to levels-1:
                    // Crop rect: (0, H - (i+1)*frameH/levels, W, H) -> draw bottom part?
                    // Or draw full bitmap masked?
                    Bitmap[] frames = new Bitmap[levels];
                    for (int i = 0; i < levels; i++) {
                        Bitmap frame = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
                        Canvas fc = new Canvas(frame);
                        // Draw only a portion
                        // Percentage height
                        int visibleH = (int) ((float) (i + 1) / levels * h);
                        Rect src = new Rect(0, h - visibleH, w, h); // Bottom up
                        Rect dst = new Rect(0, h - visibleH, w, h);
                        fc.drawBitmap(bmp, src, dst, null);
                        frames[i] = frame;
                    }

                    DialLayer layer = new DialLayer(DialLayer.TYPE_ELEMENT, frames[0], "Battery (SVG)", elementType);
                    layer.frames = frames;
                    layer.frameCount = levels;
                    layers.add(layer);
                    refreshAll();
                } else {
                    // Just use as static icon?
                    DialLayer layer = new DialLayer(DialLayer.TYPE_ELEMENT, bmp, "Icon (SVG)", elementType);
                    layer.frames = new Bitmap[] { bmp };
                    layers.add(layer);
                    refreshAll();
                }
            } else {
                DialLayer layer = new DialLayer(DialLayer.TYPE_ELEMENT, bmp, "Icon (SVG)", elementType);
                layer.frames = new Bitmap[] { bmp };
                layers.add(layer);
                refreshAll();
            }
            Toast.makeText(this, R.string.svg_layer_added, Toast.LENGTH_SHORT).show();

        } catch (Exception e) {
            e.printStackTrace();
            Toast.makeText(this, "Error: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private void generateSVGLayer(Uri uri, int elementType, int tintColor, int levels, boolean clipVertical) {
        try {
            InputStream is = getContentResolver().openInputStream(uri);
            SVG svg = SVG.getFromInputStream(is);
            is.close();

            // Determine target size
            // Default to some reasonable size if not battery?
            // Or use original size?
            float docW = svg.getDocumentWidth();
            float docH = svg.getDocumentHeight();
            if (docW <= 0 || docH <= 0) {
                if (svg.getDocumentViewBox() != null) {
                    docW = svg.getDocumentViewBox().width();
                    docH = svg.getDocumentViewBox().height();
                } else {
                    docW = 50;
                    docH = 50;
                }
            }

            // If it's too big, scale down? Or keep original?
            // Let's limit max dimension to 466 (screen size) to avoid huge heap usage
            float maxDim = Math.max(docW, docH);
            if (maxDim > 466) {
                float s = 466 / maxDim;
                docW *= s;
                docH *= s;
            }

            int w = (int) Math.ceil(docW);
            int h = (int) Math.ceil(docH);

            Bitmap baseBmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(baseBmp);
            svg.setDocumentWidth(w);
            svg.setDocumentHeight(h);
            svg.renderToCanvas(c);

            // Apply tint
            if (tintColor != Color.TRANSPARENT) {
                Bitmap tinted = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
                Canvas tc = new Canvas(tinted);
                Paint p = new Paint();
                p.setColorFilter(
                        new android.graphics.PorterDuffColorFilter(tintColor, android.graphics.PorterDuff.Mode.SRC_IN));
                tc.drawBitmap(baseBmp, 0, 0, p);
                baseBmp.recycle();
                baseBmp = tinted;
            }

            Bitmap[] frames;

            if (levels > 1 && clipVertical) {
                // Generate clipped frames
                frames = new Bitmap[levels];
                // Frame 0..N-1.
                // Usually Frame 0 is lowest (empty?) or 1/N?
                // Let's assume linear fill from bottom.
                // Frame i (0-based) represents state i/(N-1) ?
                // Battery 6 levels: 0, 20, 40, 60, 80, 100?
                // If N=6.

                for (int i = 0; i < levels; i++) {
                    Bitmap frame = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
                    Canvas fc = new Canvas(frame);

                    // Ratio of fill
                    // If levels=5. i=0 -> 20%? or 0%?
                    // Typically index 0 is "empty" or "lowest".
                    // Let's say we split height into 'levels' chunks.
                    // But we want "accumulative" fill?
                    // Let's do: fillHeight = (i + 1) * (h / levels);

                    float fillRatio = (float) (i + 1) / levels;
                    // For battery, often index 0 is empty/low.
                    // If N=5. i=0->20%, i=4->100%.

                    int clipTop = (int) (h * (1 - fillRatio));

                    fc.save();
                    fc.clipRect(0, clipTop, w, h); // Keep bottom part
                    fc.drawBitmap(baseBmp, 0, 0, null);
                    fc.restore();

                    // If we want the "empty" part to be dim?
                    // User said "substitute logic... use fonts".
                    // For battery, usually 100% lit is full. 0% is empty.
                    // Currently we just clip. The "empty" part is transparent.
                    // This works if there is a background or if the user supplies a "dim" layer
                    // behind it.

                    frames[i] = frame;
                }
                baseBmp.recycle();

            } else if (levels > 1) {
                // Just repeat the same image?
                // Or maybe user wants identical frames for state-based logic?
                frames = new Bitmap[levels];
                for (int i = 0; i < levels; i++) {
                    frames[i] = baseBmp.copy(Bitmap.Config.ARGB_8888, false);
                }
                baseBmp.recycle();
            } else {
                frames = new Bitmap[] { baseBmp };
            }

            // Add layer
            String name = getBlockLabel(elementType);
            DialLayer layer = new DialLayer(DialLayer.TYPE_ELEMENT, frames[0], name, elementType);
            layer.frames = frames;
            layer.frameCount = frames.length;
            layer.isSpriteSheet = frames.length > 1;
            layer.alpha = 1.0f;
            layer.posX = canvasWidth / 2f - w / 2f;
            layer.posY = canvasHeight / 2f - h / 2f;
            layers.add(layer);
            selectedLayerIndex = layers.size() - 1;
            applyGroupPropagation(layer);
            adoptStyleIntoPending(layer);
            refreshAll();

            Toast.makeText(this, "✓ " + name, Toast.LENGTH_SHORT).show();

        } catch (Exception e) {
            e.printStackTrace();
            Toast.makeText(this, "SVG Error: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private void copyFile(File src, File dst) throws IOException {
        java.io.FileInputStream fis = new java.io.FileInputStream(src);
        FileOutputStream fos = new FileOutputStream(dst);
        byte[] buf = new byte[4096];
        int len;
        while ((len = fis.read(buf)) > 0)
            fos.write(buf, 0, len);
        fos.close();
        fis.close();
    }

    private Bitmap trimBitmap(Bitmap bmp) {
        int imgW = bmp.getWidth();
        int imgH = bmp.getHeight();
        int top = imgH, left = imgW, right = 0, bottom = 0;

        for (int y = 0; y < imgH; y++) {
            for (int x = 0; x < imgW; x++) {
                int color = bmp.getPixel(x, y);
                if ((color >> 24) != 0) { // If pixel is NOT transparent
                    if (x < left) left = x;
                    if (x > right) right = x;
                    if (y < top) top = y;
                    if (y > bottom) bottom = y;
                }
            }
        }
        
        // Check if image is completely transparent
        if (left > right || top > bottom) return bmp;

        return Bitmap.createBitmap(bmp, left, top, (right - left) + 1, (bottom - top) + 1);
    }

    private static abstract class SimpleSeekListener implements SeekBar.OnSeekBarChangeListener {
        @Override
        public void onStartTrackingTouch(SeekBar sb) {
        }

        @Override
        public void onStopTrackingTouch(SeekBar sb) {
        }
    }
}
