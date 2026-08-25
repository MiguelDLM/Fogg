package com.example.dialsender.ble;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothClass;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.le.ScanRecord;
import android.bluetooth.le.ScanResult;
import android.content.Context;
import android.os.ParcelUuid;
import android.util.Log;

import com.example.dialsender.R;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * Tells a compatible watch apart from every other Bluetooth device the phone
 * can see (headphones, speakers, car kits, other phones...).
 *
 * Deliberately built as an *exclusion* filter rather than a whitelist gate.
 * A whitelist is what the original CO-FIT/SMA app nominally uses
 * (ProductManager's product-name list, shipped here as
 * res/raw/devices_supported.csv), but its own scan predicate bypasses it —
 * ProjectManager.o0() is a hardcoded true in the shipped build — and the list
 * is demonstrably incomplete: it carries Kronos X1/X2/X4 and KRONOS PRIME but
 * not "Kronos Thunder", a watch this app talks to fine. Gating on it would
 * lock working hardware out.
 *
 * So the rule is: reject what provably cannot be a watch, then rank what is
 * left. Rejection uses the Bluetooth class of device — the Ray-Ban Meta
 * glasses that were hijacking this app's connection report 0x240418, i.e.
 * major class AUDIO_VIDEO, as does every pair of headphones — plus BR/EDR-only
 * devices, which cannot speak our GATT protocol at all.
 *
 * {@link #isKnownWatch} then marks the devices we positively recognise (Nordic
 * UART service 6e400001-…, or a name from the product list) so they sort to
 * the top of the picker.
 */
public final class WatchFilter {

    private static final String TAG = "WatchFilter";

    /** Service the whole watch protocol runs on — a definitive match. */
    public static final UUID SERVICE_UUID = BleUuids.SERVICE;

    /** Names shorter than this only ever match exactly ("86", "A6", "L1"...). */
    private static final int MIN_PREFIX_LENGTH = 4;

    private static volatile Set<String> exactNames;
    private static volatile List<String> prefixNames;

    private WatchFilter() {
    }

    // ========== Supported-name list ==========

    private static void ensureLoaded(Context context) {
        if (exactNames != null)
            return;
        synchronized (WatchFilter.class) {
            if (exactNames != null)
                return;
            Set<String> exact = new HashSet<>();
            List<String> prefixes = new ArrayList<>();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(
                    context.getResources().openRawResource(R.raw.devices_supported),
                    StandardCharsets.UTF_8))) {
                String line;
                boolean first = true;
                while ((line = r.readLine()) != null) {
                    String name = line.trim();
                    if (first) {
                        first = false;
                        if (name.equalsIgnoreCase("device"))
                            continue; // CSV header
                    }
                    if (name.isEmpty())
                        continue;
                    String upper = name.toUpperCase(Locale.US);
                    exact.add(upper);
                    if (upper.length() >= MIN_PREFIX_LENGTH)
                        prefixes.add(upper);
                }
            } catch (Exception e) {
                Log.w(TAG, "Could not read devices_supported.csv: " + e.getMessage());
            }
            prefixNames = prefixes;
            exactNames = exact;
        }
    }

    /**
     * Known product name? Exact match, or the entry is a prefix of the
     * advertised name — watches routinely append a serial or MAC fragment
     * ("SMA-R5_A1B2"). Short entries are exact-only so that "86" or "L1" do
     * not swallow unrelated devices.
     */
    public static boolean isSupportedName(Context context, String name) {
        if (name == null || name.trim().isEmpty())
            return false;
        ensureLoaded(context);
        String upper = name.trim().toUpperCase(Locale.US);
        if (exactNames.contains(upper))
            return true;
        for (String prefix : prefixNames) {
            if (upper.startsWith(prefix))
                return true;
        }
        return false;
    }

    // ========== Hard exclusions ==========

    /**
     * Device classes that can never be a watch. This is what keeps a pair of
     * bonded headphones out of the picker even when they are the only device
     * with an active GATT link.
     */
    @SuppressLint("MissingPermission")
    public static boolean isExcludedByClass(BluetoothDevice device) {
        try {
            BluetoothClass btClass = device.getBluetoothClass();
            if (btClass == null)
                return false;
            switch (btClass.getMajorDeviceClass()) {
                case BluetoothClass.Device.Major.AUDIO_VIDEO:
                case BluetoothClass.Device.Major.PHONE:
                case BluetoothClass.Device.Major.COMPUTER:
                case BluetoothClass.Device.Major.IMAGING:
                case BluetoothClass.Device.Major.NETWORKING:
                    return true;
                default:
                    return false;
            }
        } catch (Exception e) {
            return false;
        }
    }

    /** BR/EDR-only devices cannot speak our GATT protocol. */
    @SuppressLint("MissingPermission")
    private static boolean isClassicOnly(BluetoothDevice device) {
        try {
            return device.getType() == BluetoothDevice.DEVICE_TYPE_CLASSIC;
        } catch (Exception e) {
            return false;
        }
    }

    @SuppressLint("MissingPermission")
    private static boolean advertisesWatchService(BluetoothDevice device) {
        try {
            ParcelUuid[] uuids = device.getUuids();
            if (uuids == null)
                return false;
            for (ParcelUuid u : uuids) {
                if (u != null && SERVICE_UUID.equals(u.getUuid()))
                    return true;
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    // ========== Public predicates ==========

    /**
     * Hard rejection: this device provably cannot be a watch. Used to keep
     * headphones, speakers, car kits, phones and computers out of the picker.
     */
    @SuppressLint("MissingPermission")
    public static boolean isExcluded(BluetoothDevice device) {
        return device == null || isExcludedByClass(device) || isClassicOnly(device);
    }

    /**
     * Positive recognition, used for ranking rather than gating: the device
     * either exposes the watch protocol's service or carries a name we know.
     */
    @SuppressLint("MissingPermission")
    public static boolean isKnownWatch(Context context, BluetoothDevice device) {
        if (device == null)
            return false;
        if (advertisesWatchService(device))
            return true;
        try {
            return isSupportedName(context, device.getName());
        } catch (Exception e) {
            return false;
        }
    }

    /** Scan-result variant: the advertisement may carry the service UUID. */
    @SuppressLint("MissingPermission")
    public static boolean isKnownWatch(Context context, ScanResult result) {
        if (result == null || result.getDevice() == null)
            return false;
        ScanRecord record = result.getScanRecord();
        if (record != null && record.getServiceUuids() != null) {
            for (ParcelUuid u : record.getServiceUuids()) {
                if (u != null && SERVICE_UUID.equals(u.getUuid()))
                    return true;
            }
        }
        String name = record != null ? record.getDeviceName() : null;
        if (name == null || name.trim().isEmpty()) {
            try {
                name = result.getDevice().getName();
            } catch (Exception ignored) {
            }
        }
        return isSupportedName(context, name);
    }

    /**
     * Whether a scan result is worth showing at all. Mirrors the original's
     * predicate: named devices with usable signal, minus the hard exclusions.
     */
    @SuppressLint("MissingPermission")
    public static boolean isCandidate(Context context, ScanResult result) {
        if (result == null || result.getDevice() == null)
            return false;
        if (result.getRssi() <= -100)
            return false;
        if (isExcluded(result.getDevice()))
            return false;
        if (isKnownWatch(context, result))
            return true;
        // Unknown but plausible: require a name, as the original does.
        ScanRecord record = result.getScanRecord();
        String name = record != null ? record.getDeviceName() : null;
        if (name == null || name.trim().isEmpty()) {
            try {
                name = result.getDevice().getName();
            } catch (Exception ignored) {
            }
        }
        return name != null && !name.trim().isEmpty();
    }

    /** Best available display name for a device, never null. */
    @SuppressLint("MissingPermission")
    public static String displayName(BluetoothDevice device, String fallback) {
        try {
            String name = device.getName();
            if (name != null && !name.trim().isEmpty())
                return name.trim();
        } catch (Exception ignored) {
        }
        return fallback;
    }

}
