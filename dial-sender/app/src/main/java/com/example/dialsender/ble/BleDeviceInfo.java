package com.example.dialsender.ble;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * DEVICE_INFO (0x023E) — what the watch says about itself.
 *
 * Two incompatible payload shapes answer this one key, and which one you get
 * depends on the firmware. The SDK models them as separate entities
 * (BleDeviceInfo and BleDeviceInfo2) and its dispatcher — the part that picks
 * between them — is not present in the decompiled sources, so this class sniffs
 * the wire instead:
 *
 *   V2 (identity)   opens with the BLE MAC as an ASCII string. Carries names,
 *                   platform, prototype and firmware/UI/language versions, but
 *                   no key list and no capability flags. This is what the
 *                   Kronos Thunder (JL / AM05) replies with.
 *   V1 (capability) opens with an int32 id followed by the supported-BleKey
 *                   list, then ~100 capability flags.
 *
 * Fields are positional in both — no tags, no lengths. Firmware that predates a
 * field simply truncates the payload, so every read past the end yields 0/""
 * rather than throwing; an absent flag means "unsupported", the safe reading.
 *
 * Layout: docs/protocols/11-DEVICE-INFO-CAPABILITIES.md
 */
public class BleDeviceInfo {

    /** Capability flags, in wire order, read between the header and the name block. */
    private static final String[] FLAGS_A = {
            "DateFormatSet", "ReadDeviceInfo", "TemperatureUnitSet", "DrinkWaterSet",
            "ChangeClassicBluetoothState", "AppSport", "BloodOxyGenSet", "WashSet",
            "RequestRealtimeWeather", "HID", "IBeaconSet", "WatchFaceId",
            "NewTransportMode", "JLTransport", "FindWatch", "WorldClock",
            "Stock", "SMSQuickReply", "NoDisturbSet", "SetWatchPassword",
            "RealTimeMeasurement", "PowerSaveMode", "LoveTap", "Newsfeed",
            "MedicationReminder", "Qrcode", "Weather2", "Alipay",
            "StandbySet", "2DAcceleration", "TuyaKey", "MedicationAlarm",
            "ReadPackageStatus", "ContactSize", "Voice", "Navigation",
            "HrWarnSet",
    };

    /** Capability flags following mBleDefaultName. */
    private static final String[] FLAGS_B = {
            "MusicTransfer", "NoDisturbSet2", "SOSSet", "ReadLanguages",
            "GirlCareReminder", "AppPushSwitch", "ReceiptCodeSize", "GameTimeReminder",
            "MyCardCodeSize", "DeviceSportData", "EbookTransfer", "DoubleScreen",
            "CustomLogo", "PressureTimingMeasurement", "TimerStandbySet", "SOSSet2",
            "FallSet", "WalkAndBike", "ConnectReminder", "SDCardInfo",
            "IncomingCallRing", "NotificationLightScreenSet", "BloodPressureCalibration", "OTAFile",
            "GPSFirmwareFile", "GoMoreSet", "RingVibrationSet", "Network",
            "ContactSort", "QrcodeSize", "QrcodeContentSize", "StringQrcode",
            "WatchFaceIndex", "SosContact", "GirlCareMonthly", "WearWay",
            "GestureWake2", "NavImage", "VoiceMaxLength", "AudioBooks",
            "StudyCards", "AppStore", "SHSYAlgorithm", "QiblaSet",
            "MeasurementBloodGlucose", "GameControls", "BatteryUsage", "AITranslation",
            "SimultaneousTranslation", "TouchSet", "IMEISet", "Quran",
            "SyncAGPSInBackground", "RestoreFactory", "RecordNote", "SleepScore",
            "Watchface2", "AICoach", "CrossAppTranslation", "RelaxReminder",
            "Power2",
    };

    /** The app renames the device when mFirmwareFlag embeds "<name>" after this token. */
    private static final String RAW_NAME_SEPARATOR = "<>";

    /** Which payload shape the watch answered with. */
    public enum Variant { V1_CAPABILITY, V2_IDENTITY }

    public Variant variant = Variant.V1_CAPABILITY;

    public int id;
    /** BleKey values (0xCCKK) this watch supports. Always empty on V2. */
    public List<Integer> dataKeys = Collections.emptyList();
    public String bleName = "";
    public String bleCustomName = "";
    public String bleDefaultName = "";
    public String bleAddress = "";
    public String platform = "";
    public String prototype = "";
    public String firmwareFlag = "";
    public String classicAddress = "";
    public int agpsType;
    /** Stream chunk size for 0x07xx transfers. 0 when the watch did not report one. */
    public int ioBufferSize;
    public int watchFaceType;
    public int hideDigitalPower;
    public int showAntiLostSwitch;
    public int sleepAlgorithmType;
    /** How many of the trailing capability flags the payload actually carried. V1 only. */
    public int flagsRead;

    // V2 only.
    public String firmwareVersion = "";
    public String uiVersion = "";
    public String languageVersion = "";
    public int languageCode;
    public String fullVersion = "";

    private final Map<String, Integer> flags = new LinkedHashMap<>();

    /**
     * Parse the DEVICE_INFO payload — the frame body from offset 9 onward.
     * Never throws: a short or malformed payload yields a partially filled
     * object, with {@link #flagsRead} telling you how far a V1 block got.
     */
    public static BleDeviceInfo parse(byte[] payload) {
        return looksLikeV2(payload) ? parseV2(payload) : parseV1(payload);
    }

    /**
     * V2 opens with the BLE MAC as ASCII ("73:08:11:A5:AE:61"); V1 opens with an
     * int32 id, whose four bytes forming that exact 17-char pattern is not a
     * case worth worrying about. Sniffing beats guessing from firmware version:
     * the SDK's own dispatcher is absent from the decompile.
     */
    private static boolean looksLikeV2(byte[] payload) {
        if (payload == null || payload.length < 18 || payload[17] != 0)
            return false;
        for (int i = 0; i < 17; i++) {
            int c = payload[i] & 0xFF;
            boolean sep = (i % 3) == 2;
            if (sep != (c == ':'))
                return false;
            if (!sep && !isHexDigit(c))
                return false;
        }
        return true;
    }

    private static boolean isHexDigit(int c) {
        return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
    }

    /** Identity block: addresses, names and version strings. No capabilities. */
    private static BleDeviceInfo parseV2(byte[] payload) {
        BleDeviceInfo info = new BleDeviceInfo();
        info.variant = Variant.V2_IDENTITY;
        Reader r = new Reader(payload);

        info.bleAddress = r.string().toUpperCase();
        info.classicAddress = r.string().toUpperCase();
        info.firmwareVersion = r.version();
        info.uiVersion = r.version();
        info.languageVersion = r.version();
        info.languageCode = r.uint8();
        info.bleName = r.string();
        info.platform = r.string();
        info.prototype = r.string();
        info.firmwareFlag = r.string();
        info.fullVersion = r.string();
        return info;
    }

    /** Capability block: supported-key list plus ~100 feature flags. */
    private static BleDeviceInfo parseV1(byte[] payload) {
        BleDeviceInfo info = new BleDeviceInfo();
        info.variant = Variant.V1_CAPABILITY;
        Reader r = new Reader(payload);

        info.id = r.int32();
        info.dataKeys = r.keyList();
        info.bleName = r.string();
        info.bleAddress = r.string().toUpperCase();
        info.platform = r.string();
        info.prototype = r.string();
        info.firmwareFlag = r.string();
        info.agpsType = r.uint8();
        info.ioBufferSize = r.uint16();
        info.watchFaceType = r.uint8();
        info.classicAddress = r.string().toUpperCase();
        info.hideDigitalPower = r.uint8();
        info.showAntiLostSwitch = r.uint8();
        info.sleepAlgorithmType = r.uint8();

        for (String name : FLAGS_A) {
            if (r.exhausted())
                return info;
            info.flags.put(name, r.uint8());
            info.flagsRead++;
        }
        // Wire quirk: the byte holds tens of contacts, not contacts.
        Integer contacts = info.flags.get("ContactSize");
        if (contacts != null)
            info.flags.put("ContactSize", contacts * 10);

        // An OEM marketing name can arrive inside mFirmwareFlag; whatever the
        // user had renamed the watch to is preserved as the custom name.
        int sep = info.firmwareFlag.indexOf(RAW_NAME_SEPARATOR);
        if (sep >= 0) {
            String candidate = info.firmwareFlag.substring(sep + RAW_NAME_SEPARATOR.length());
            if (!candidate.isEmpty() && !candidate.equals(info.bleName)) {
                info.bleCustomName = info.bleName;
                info.bleName = candidate;
            }
        }
        // Read unconditionally — treating it as optional shifts every flag in
        // FLAGS_B by the length of this string.
        info.bleDefaultName = r.string();
        if (!info.bleDefaultName.isEmpty()) {
            info.bleCustomName = info.bleName;
            info.bleName = info.bleDefaultName;
        }

        for (String name : FLAGS_B) {
            if (r.exhausted())
                return info;
            info.flags.put(name, r.uint8());
            info.flagsRead++;
        }
        return info;
    }

    /** Raw value of a capability flag, or 0 when the firmware did not report it. */
    public int flag(String name) {
        Integer v = flags.get(name);
        return v == null ? 0 : v;
    }

    public boolean supports(String name) {
        return flag(name) != 0;
    }

    /** True when the watch listed this BleKey in mDataKeys. */
    public boolean supportsKey(int bleKey) {
        return dataKeys.contains(bleKey);
    }

    /**
     * Whether mDataKeys is usable for gating. Firmware that reports an empty
     * list is saying "I did not tell you", not "I support nothing" — callers
     * must fall back to their own key list in that case.
     */
    public boolean hasDataKeys() {
        return !dataKeys.isEmpty();
    }

    public Map<String, Integer> allFlags() {
        return Collections.unmodifiableMap(flags);
    }

    /** Supported keys as "0x0502,0x0503,…" for logs and prefs. */
    public String dataKeysHex() {
        StringBuilder sb = new StringBuilder();
        for (int k : dataKeys) {
            if (sb.length() > 0)
                sb.append(',');
            sb.append(String.format("0x%04X", k));
        }
        return sb.toString();
    }

    /** "Name=1;Other=0;…" for prefs and the developer tools dump. */
    public String flagsCompact() {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Integer> e : flags.entrySet()) {
            if (sb.length() > 0)
                sb.append(';');
            sb.append(e.getKey()).append('=').append(e.getValue());
        }
        return sb.toString();
    }

    @Override
    public String toString() {
        if (variant == Variant.V2_IDENTITY) {
            return "DeviceInfo2{name=" + bleName
                    + ", addr=" + bleAddress
                    + (classicAddress.isEmpty() ? "" : ", classic=" + classicAddress)
                    + ", platform=" + platform
                    + ", prototype=" + prototype
                    + ", fwFlag=" + firmwareFlag
                    + ", fullVersion=" + fullVersion
                    + ", fw=" + firmwareVersion
                    + ", ui=" + uiVersion
                    + ", lang=" + languageVersion + "/" + languageCode
                    + "}";
        }
        return "DeviceInfo{name=" + bleName
                + (bleCustomName.isEmpty() ? "" : " (custom=" + bleCustomName + ")")
                + ", addr=" + bleAddress
                + ", platform=" + platform
                + ", prototype=" + prototype
                + ", fwFlag=" + firmwareFlag
                + ", agpsType=" + agpsType
                + ", ioBufferSize=" + ioBufferSize
                + ", watchFaceType=" + watchFaceType
                + ", sleepAlgo=" + sleepAlgorithmType
                + ", dataKeys=" + dataKeys.size()
                + ", flags=" + flagsRead + "/" + (FLAGS_A.length + FLAGS_B.length)
                + "}";
    }

    /**
     * Big-endian positional reader. Reads past the end return 0 / "" and latch
     * {@link #exhausted()} so the caller can stop filling optional fields.
     */
    private static final class Reader {
        private final byte[] buf;
        private int pos;

        Reader(byte[] buf) {
            this.buf = buf == null ? new byte[0] : buf;
        }

        boolean exhausted() {
            return pos >= buf.length;
        }

        int uint8() {
            if (pos >= buf.length)
                return 0;
            return buf[pos++] & 0xFF;
        }

        int uint16() {
            return (uint8() << 8) | uint8();
        }

        int int32() {
            return (uint16() << 16) | uint16();
        }

        /** Bytes up to the next 0x00; the terminator is consumed. */
        byte[] bytesUntilNul() {
            int start = pos;
            while (pos < buf.length && buf[pos] != 0)
                pos++;
            byte[] out = new byte[pos - start];
            System.arraycopy(buf, start, out, 0, out.length);
            if (pos < buf.length)
                pos++; // consume the terminator
            return out;
        }

        String string() {
            return new String(bytesUntilNul(), StandardCharsets.UTF_8);
        }

        /** Three bytes rendered as "major.minor.patch". */
        String version() {
            return uint8() + "." + uint8() + "." + uint8();
        }

        /**
         * NUL-terminated run of big-endian uint16 BleKey values. No real key
         * contains a 0x00 byte (0x01xx–0x07xx high bytes, low bytes 0x01–0xFF),
         * so a lone 0x00 unambiguously ends the list.
         */
        List<Integer> keyList() {
            byte[] raw = bytesUntilNul();
            List<Integer> keys = new ArrayList<>(raw.length / 2);
            for (int i = 0; i + 1 < raw.length; i += 2)
                keys.add(((raw[i] & 0xFF) << 8) | (raw[i + 1] & 0xFF));
            return keys;
        }
    }
}
