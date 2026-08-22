package com.example.dialsender.fragments;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.text.InputType;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.core.content.FileProvider;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.chaquo.python.PyObject;
import com.chaquo.python.Python;
import com.chaquo.python.android.AndroidPlatform;
import com.example.dialsender.DialEditorActivity;

import com.example.dialsender.R;
import com.example.dialsender.ble.BleManager;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

public class DialsFragment extends Fragment {

    private RecyclerView dialsGrid;
    private List<DialEntry> dialEntries = new ArrayList<>();
    private DialAdapter dialAdapter;
    private androidx.activity.result.ActivityResultLauncher<Intent> filePickerLauncher;

    private static class DialEntry {
        String name;
        String path;
        String assetPath;
        long size;
        boolean isPreset;
        Bitmap thumb;

        DialEntry(String name, long size, boolean isPreset) {
            this.name = name;
            this.size = size;
            this.isPreset = isPreset;
        }
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        filePickerLauncher = registerForActivityResult(
                new androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (result.getResultCode() == Activity.RESULT_OK && result.getData() != null) {
                        try {
                            android.net.Uri uri = result.getData().getData();
                            if (uri != null) {
                                sendDialFromUri(uri);
                            }
                        } catch (Exception e) {
                            Toast.makeText(requireContext(), "Error: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                        }
                    }
                });
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
            @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_dials, container, false);

        dialsGrid = view.findViewById(R.id.dialsGrid);
        dialsGrid.setLayoutManager(new GridLayoutManager(requireContext(), 1));

        view.findViewById(R.id.btnCreateDial).setOnClickListener(v -> {
            Intent intent = new Intent(requireContext(), DialEditorActivity.class);
            startActivity(intent);
        });

        if (view.findViewById(R.id.btnPickFile) != null) {
            view.findViewById(R.id.btnPickFile).setOnClickListener(v -> {
                Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
                intent.setType("*/*");
                filePickerLauncher.launch(intent);
            });
        }

        if (!Python.isStarted()) {
            Python.start(new AndroidPlatform(requireContext()));
        }

        dialAdapter = new DialAdapter();
        dialsGrid.setAdapter(dialAdapter);
        loadDials();

        return view;
    }

    @Override
    public void onResume() {
        super.onResume();
        loadDials();
    }

    private void loadDials() {
        dialEntries.clear();

        try {
            String[] assetDials = requireContext().getAssets().list("dials");
            if (assetDials != null) {
                for (String name : assetDials) {
                    if (name.endsWith(".bin")) {
                        InputStream is = requireContext().getAssets().open("dials/" + name);
                        int size = is.available();
                        is.close();
                        DialEntry entry = new DialEntry(name, size, true);
                        entry.assetPath = "dials/" + name;
                        dialEntries.add(entry);
                    }
                }
            }
        } catch (Exception e) {
        }

        File userDialsDir = new File(requireActivity().getFilesDir(), "user_dials");
        if (!userDialsDir.exists())
            userDialsDir.mkdirs();
        if (userDialsDir.exists()) {
            File[] files = userDialsDir.listFiles();
            if (files != null) {
                for (File f : files) {
                    if (f.getName().endsWith(".bin")) {
                        DialEntry entry = new DialEntry(f.getName(), f.length(), false);
                        entry.path = f.getAbsolutePath();
                        dialEntries.add(entry);
                    }
                }
            }
        }

        dialAdapter.notifyDataSetChanged();
        extractThumbnailsAsync();
    }

    private void extractThumbnailsAsync() {
        List<DialEntry> entriesToProcess = new ArrayList<>(dialEntries);
        new Thread(() -> {
            Python py = Python.getInstance();
            PyObject module = py.getModule("comp_decomp");

            for (DialEntry entry : entriesToProcess) {
                try {
                    File thumbFile = new File(requireContext().getCacheDir(),
                            "preview_" + entry.name.replace(".bin", ".png"));

                    if (thumbFile.exists() && thumbFile.length() > 0) {
                        entry.thumb = BitmapFactory.decodeFile(thumbFile.getAbsolutePath());
                        continue;
                    }

                    File binFile;
                    if (entry.isPreset) {
                        binFile = new File(requireContext().getCacheDir(), "thumb_" + entry.name);
                        if (!binFile.exists()) {
                            InputStream is = requireContext().getAssets().open(entry.assetPath);
                            FileOutputStream fos = new FileOutputStream(binFile);
                            byte[] buf = new byte[4096];
                            int len;
                            while ((len = is.read(buf)) != -1)
                                fos.write(buf, 0, len);
                            fos.close();
                            is.close();
                        }
                    } else {
                        binFile = new File(entry.path);
                    }

                    PyObject result = module.callAttr("extract_preview_png",
                            binFile.getAbsolutePath(), thumbFile.getAbsolutePath());
                    String resultPath = result.toString();
                    if (!resultPath.isEmpty() && thumbFile.exists()) {
                        entry.thumb = BitmapFactory.decodeFile(thumbFile.getAbsolutePath());
                    }
                } catch (Exception e) {
                }
            }

            if (isAdded()) {
                requireActivity().runOnUiThread(() -> dialAdapter.notifyDataSetChanged());
            }
        }).start();
    }

    // --- Transfer Methods ---
    private void sendDial(DialEntry entry) {
        try {
            File file = prepareFile(entry);
            java.io.FileInputStream fis = new java.io.FileInputStream(file);
            java.io.ByteArrayOutputStream byteBuffer = new java.io.ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int len;
            while ((len = fis.read(buffer)) != -1)
                byteBuffer.write(buffer, 0, len);
            fis.close();
            byte[] fileBytesToSend = byteBuffer.toByteArray();

            com.example.dialsender.ble.BleManager bleManager = com.example.dialsender.ble.BleManager
                    .getInstance(requireContext());

            android.widget.ProgressBar progressBar = new android.widget.ProgressBar(
                    requireContext(), null, android.R.attr.progressBarStyleHorizontal);
            progressBar.setMax(100);
            progressBar.setProgress(0);
            int dp16 = (int) (16 * getResources().getDisplayMetrics().density);
            progressBar.setPadding(dp16, dp16, dp16, 0);

            android.widget.TextView msgView = new android.widget.TextView(requireContext());
            msgView.setText(getString(R.string.waiting));
            msgView.setPadding(dp16, dp16 / 2, dp16, dp16);

            android.widget.LinearLayout layout = new android.widget.LinearLayout(requireContext());
            layout.setOrientation(android.widget.LinearLayout.VERTICAL);
            layout.addView(progressBar);
            layout.addView(msgView);

            final boolean[] done = {false};
            // Boxed so the cancel lambda below, declared before the listener
            // itself exists, can still unsubscribe it.
            final com.example.dialsender.ble.BleManager.BleStateListener[] xferListener =
                    new com.example.dialsender.ble.BleManager.BleStateListener[1];

            androidx.appcompat.app.AlertDialog transferDialog = new AlertDialog.Builder(requireContext())
                    .setTitle(getString(R.string.transfer))
                    .setView(layout)
                    .setNegativeButton(R.string.cancel, (d, w) -> {
                        done[0] = true;
                        bleManager.cancelTransfer();
                        if (xferListener[0] != null)
                            bleManager.removeListener(xferListener[0]);
                    })
                    .setCancelable(false)
                    .create();
            transferDialog.show();

            xferListener[0] = new com.example.dialsender.ble.BleManager.BleStateListener() {
                @Override
                public void onConnectionStateChange(boolean connected, boolean sessionReady) {
                }

                @Override
                public void onHealthDataReceived(String keyName, byte[] payload) {
                }

                @Override
                public void onHealthSyncComplete() {
                }

                @Override
                public void onTransferProgress(int percent, long bytesTransferred, long totalBytes) {
                    if (done[0]) return;
                    progressBar.setProgress(percent);
                    msgView.setText(bytesTransferred + " / " + totalBytes + " bytes");
                }

                @Override
                public void onTransferComplete() {
                    if (done[0]) return;
                    done[0] = true;
                    bleManager.removeListener(xferListener[0]);
                    transferDialog.dismiss();
                    if (isAdded()) {
                        Toast.makeText(requireContext(), R.string.dial_sent_ok, Toast.LENGTH_LONG).show();
                    }
                }

                @Override
                public void onTransferFailed(String reason) {
                    if (done[0]) return;
                    done[0] = true;
                    bleManager.removeListener(xferListener[0]);
                    transferDialog.dismiss();
                    if (isAdded()) {
                        Toast.makeText(requireContext(), getString(R.string.transfer_failed, reason), Toast.LENGTH_LONG).show();
                    }
                }

                @Override
                public void onLogUpdated() {
                }
            };
            bleManager.addListener(xferListener[0]);

            bleManager.startFileTransfer(fileBytesToSend);

        } catch (Exception e) {
            Toast.makeText(requireContext(), "Error: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private void sendDialFromUri(android.net.Uri uri) {
        try {
            java.io.InputStream is = requireContext().getContentResolver().openInputStream(uri);
            if (is == null)
                return;
            java.io.ByteArrayOutputStream byteBuffer = new java.io.ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int len;
            while ((len = is.read(buffer)) != -1)
                byteBuffer.write(buffer, 0, len);
            is.close();
            byte[] fileBytesToSend = byteBuffer.toByteArray();

            // ── Persist the dial so it appears in the library ──────────────
            String rawName = null;
            String uriPath = uri.getLastPathSegment();
            if (uriPath != null) {
                int slash = uriPath.lastIndexOf('/');
                rawName = slash >= 0 ? uriPath.substring(slash + 1) : uriPath;
            }
            if (rawName == null || rawName.isEmpty())
                rawName = "dial_" + System.currentTimeMillis() + ".bin";
            if (!rawName.toLowerCase().endsWith(".bin"))
                rawName = rawName + ".bin";
            rawName = rawName.replaceAll("[^a-zA-Z0-9_\\-.]", "_");
            File userDialsDir = new File(requireActivity().getFilesDir(), "user_dials");
            if (!userDialsDir.exists())
                userDialsDir.mkdirs();
            File savedFile = new File(userDialsDir, rawName);
            // If a file with the same name already exists, skip overwriting but still send.
            if (!savedFile.exists()) {
                try (java.io.FileOutputStream fos = new java.io.FileOutputStream(savedFile)) {
                    fos.write(fileBytesToSend);
                }
                loadDials(); // refresh the grid to show the newly saved dial
            }
            // ────────────────────────────────────────────────────────────────

            com.example.dialsender.ble.BleManager bleManager = com.example.dialsender.ble.BleManager
                    .getInstance(requireContext());

            android.widget.ProgressBar progressBar = new android.widget.ProgressBar(
                    requireContext(), null, android.R.attr.progressBarStyleHorizontal);
            progressBar.setMax(100);
            progressBar.setProgress(0);
            int dp16 = (int) (16 * getResources().getDisplayMetrics().density);
            progressBar.setPadding(dp16, dp16, dp16, 0);

            android.widget.TextView msgView = new android.widget.TextView(requireContext());
            msgView.setText(getString(R.string.waiting));
            msgView.setPadding(dp16, dp16 / 2, dp16, dp16);

            android.widget.LinearLayout layout = new android.widget.LinearLayout(requireContext());
            layout.setOrientation(android.widget.LinearLayout.VERTICAL);
            layout.addView(progressBar);
            layout.addView(msgView);

            final boolean[] done = {false};
            // Boxed so the cancel lambda below, declared before the listener
            // itself exists, can still unsubscribe it.
            final com.example.dialsender.ble.BleManager.BleStateListener[] xferListener =
                    new com.example.dialsender.ble.BleManager.BleStateListener[1];

            androidx.appcompat.app.AlertDialog transferDialog = new AlertDialog.Builder(requireContext())
                    .setTitle(getString(R.string.transfer))
                    .setView(layout)
                    .setNegativeButton(R.string.cancel, (d, w) -> {
                        done[0] = true;
                        bleManager.cancelTransfer();
                        if (xferListener[0] != null)
                            bleManager.removeListener(xferListener[0]);
                    })
                    .setCancelable(false)
                    .create();
            transferDialog.show();

            xferListener[0] = new com.example.dialsender.ble.BleManager.BleStateListener() {
                @Override
                public void onConnectionStateChange(boolean connected, boolean sessionReady) {
                }

                @Override
                public void onHealthDataReceived(String keyName, byte[] payload) {
                }

                @Override
                public void onHealthSyncComplete() {
                }

                @Override
                public void onTransferProgress(int percent, long bytesTransferred, long totalBytes) {
                    if (done[0]) return;
                    progressBar.setProgress(percent);
                    msgView.setText(bytesTransferred + " / " + totalBytes + " bytes");
                }

                @Override
                public void onTransferComplete() {
                    if (done[0]) return;
                    done[0] = true;
                    bleManager.removeListener(xferListener[0]);
                    transferDialog.dismiss();
                    if (isAdded()) {
                        Toast.makeText(requireContext(), R.string.dial_sent_ok, Toast.LENGTH_LONG).show();
                    }
                }

                @Override
                public void onTransferFailed(String reason) {
                    if (done[0]) return;
                    done[0] = true;
                    bleManager.removeListener(xferListener[0]);
                    transferDialog.dismiss();
                    if (isAdded()) {
                        Toast.makeText(requireContext(), getString(R.string.transfer_failed, reason), Toast.LENGTH_LONG).show();
                    }
                }

                @Override
                public void onLogUpdated() {
                }
            };
            bleManager.addListener(xferListener[0]);

            bleManager.startFileTransfer(fileBytesToSend);

        } catch (Exception e) {
            Toast.makeText(requireContext(), getString(R.string.dial_send_error, e.getMessage()), Toast.LENGTH_SHORT).show();
        }
    }

    private void editDial(DialEntry entry) {
        try {
            File file = prepareFile(entry);
            String safeName = entry.name.replace(".bin", "");
            File editDir = new File(requireContext().getCacheDir(), "edit_" + safeName);
            if (!editDir.exists())
                editDir.mkdirs();

            Python py = Python.getInstance();
            PyObject module = py.getModule("comp_decomp");
            PyObject result = module.callAttr("extract_dial", file.getAbsolutePath(), editDir.getAbsolutePath());

            if (result != null && result.toBoolean()) {
                Intent intent = new Intent(requireContext(), DialEditorActivity.class);
                intent.putExtra("edit_dial_folder", editDir.getAbsolutePath());
                intent.putExtra("edit_dial_name", safeName);
                startActivity(intent);
            } else {
                Toast.makeText(requireContext(), getString(R.string.dial_extract_error), Toast.LENGTH_SHORT).show();
            }
        } catch (Exception e) {
            Toast.makeText(requireContext(), "Edit error: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private void showDialContextMenu(DialEntry entry, int position) {
        List<String> opts = new ArrayList<>();
        opts.add(getString(R.string.send_arrow));
        opts.add(getString(R.string.edit));
        opts.add(getString(R.string.share));
        opts.add(getString(R.string.export));

        if (!entry.isPreset) {
            opts.add(getString(R.string.rename));
            opts.add(getString(R.string.delete));
        }

        new AlertDialog.Builder(requireContext())
                .setTitle(entry.name.replace(".bin", ""))
                .setItems(opts.toArray(new String[0]), (dialog, which) -> {
                    String choice = opts.get(which);
                    if (choice.equals(getString(R.string.send_arrow))) {
                        sendDial(entry);
                    } else if (choice.equals(getString(R.string.edit))) {
                        editDial(entry);
                    } else if (choice.equals(getString(R.string.share))) {
                        shareDial(entry);
                    } else if (choice.equals(getString(R.string.export))) {
                        exportDial(entry);
                    } else if (choice.equals(getString(R.string.rename))) {
                        showRenameDialog(entry, position);
                    } else if (choice.equals(getString(R.string.delete))) {
                        showDeleteConfirm(entry, position);
                    }
                })
                .show();
    }

    private void showRenameDialog(DialEntry entry, int position) {
        EditText input = new EditText(requireContext());
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        input.setText(entry.name.replace(".bin", ""));
        input.setPadding(48, 24, 48, 24);

        new AlertDialog.Builder(requireContext())
                .setTitle(R.string.rename_dial)
                .setView(input)
                .setPositiveButton(R.string.save, (dialog, which) -> {
                    String newName = input.getText().toString().trim();
                    if (newName.isEmpty())
                        return;
                    newName = newName.replaceAll("[^a-zA-Z0-9_\\-]", "_") + ".bin";

                    File oldFile = new File(entry.path);
                    File newFile = new File(oldFile.getParentFile(), newName);

                    if (newFile.exists()) {
                        Toast.makeText(requireContext(), getString(R.string.name_exists), Toast.LENGTH_SHORT).show();
                        return;
                    }

                    if (oldFile.renameTo(newFile)) {
                        File oldThumb = new File(requireContext().getCacheDir(),
                                "preview_" + entry.name.replace(".bin", ".png"));
                        File newThumb = new File(requireContext().getCacheDir(),
                                "preview_" + newName.replace(".bin", ".png"));
                        if (oldThumb.exists())
                            oldThumb.renameTo(newThumb);

                        entry.name = newName;
                        entry.path = newFile.getAbsolutePath();
                        dialAdapter.notifyItemChanged(position);
                        Toast.makeText(requireContext(), R.string.dial_renamed, Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void showDeleteConfirm(DialEntry entry, int position) {
        new AlertDialog.Builder(requireContext())
                .setTitle(R.string.delete)
                .setMessage(R.string.confirm_delete)
                .setPositiveButton(R.string.delete, (dialog, which) -> {
                    File file = new File(entry.path);
                    file.delete();
                    File thumbFile = new File(requireContext().getCacheDir(),
                            "preview_" + entry.name.replace(".bin", ".png"));
                    thumbFile.delete();

                    dialEntries.remove(position);
                    dialAdapter.notifyItemRemoved(position);
                    Toast.makeText(requireContext(), R.string.dial_deleted, Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void shareDial(DialEntry entry) {
        try {
            File file = prepareFile(entry);
            Uri uri = FileProvider.getUriForFile(requireContext(), requireContext().getPackageName() + ".provider",
                    file);

            Intent intent = new Intent(Intent.ACTION_SEND);
            intent.setType("application/octet-stream");
            intent.putExtra(Intent.EXTRA_STREAM, uri);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(intent, getString(R.string.share)));
        } catch (Exception e) {
            Toast.makeText(requireContext(), "Error: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private void exportDial(DialEntry entry) {
        try {
            File src = prepareFile(entry);
            File downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
            File destDir = new File(downloads, "Fogg");
            if (!destDir.exists())
                destDir.mkdirs();

            File dest = new File(destDir, entry.name);
            copyFile(src, dest);

            Toast.makeText(requireContext(), getString(R.string.dial_exported), Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(requireContext(), "Export error: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private File prepareFile(DialEntry entry) throws Exception {
        if (!entry.isPreset)
            return new File(entry.path);

        File cached = new File(requireContext().getCacheDir(), entry.name);
        if (!cached.exists()) {
            InputStream is = requireContext().getAssets().open(entry.assetPath);
            FileOutputStream fos = new FileOutputStream(cached);
            byte[] buf = new byte[4096];
            int len;
            while ((len = is.read(buf)) != -1)
                fos.write(buf, 0, len);
            fos.close();
            is.close();
        }
        return cached;
    }

    private void copyFile(File src, File dst) throws Exception {
        InputStream in = new java.io.FileInputStream(src);
        FileOutputStream out = new FileOutputStream(dst);
        byte[] buf = new byte[4096];
        int len;
        while ((len = in.read(buf)) != -1)
            out.write(buf, 0, len);
        in.close();
        out.close();
    }

    // --------- Adapter ---------

    private class DialAdapter extends RecyclerView.Adapter<DialAdapter.VH> {
        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_dial, parent, false);
            return new VH(v);
        }

        @Override
        public void onBindViewHolder(@NonNull VH holder, int position) {
            DialEntry entry = dialEntries.get(position);
            holder.txtName.setText(entry.name.replace(".bin", ""));
            String sizeStr = entry.size > 1024
                    ? String.format("%.1f KB", entry.size / 1024.0)
                    : entry.size + " bytes";
            holder.txtSize.setText(sizeStr);
            holder.txtSource.setText(entry.isPreset ? getString(R.string.preset) : getString(R.string.custom));
            holder.txtSource.setTextColor(getResources().getColor(
                    entry.isPreset ? R.color.accent_cyan : R.color.accent_secondary));

            if (entry.thumb != null) {
                holder.imgThumb.setImageBitmap(entry.thumb);
            } else {
                holder.imgThumb.setImageResource(android.R.color.darker_gray);
            }

            // Click -> Send, Long Click -> Context Menu
            holder.itemView.setOnClickListener(v -> sendDial(entry));
            holder.btnSend.setText(R.string.send_arrow);
            holder.btnSend.setOnClickListener(v -> sendDial(entry));
            holder.itemView.setOnLongClickListener(v -> {
                showDialContextMenu(entry, position);
                return true;
            });
        }

        @Override
        public int getItemCount() {
            return dialEntries.size();
        }

        class VH extends RecyclerView.ViewHolder {
            android.widget.ImageView imgThumb;
            TextView txtName, txtSize, txtSource, btnSend;

            VH(View v) {
                super(v);
                imgThumb = v.findViewById(R.id.imgDialThumb);
                txtName = v.findViewById(R.id.txtDialName);
                txtSize = v.findViewById(R.id.txtDialSize);
                txtSource = v.findViewById(R.id.txtDialSource);
                btnSend = v.findViewById(R.id.btnSendDial);
            }
        }
    }
}
