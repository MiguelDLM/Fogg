package com.example.dialsender.ble;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parser for QR codes displayed on smartwatch screens during initial setup or in
 * Settings -> Bind / QR Code.
 *
 * Supported formats:
 * 1. Plain MAC address: "AA:BB:CC:DD:EE:FF" or "AA-BB-CC-DD-EE-FF"
 * 2. Continuous 12-hex string: "AABBCCDDEEFF"
 * 3. Tagged string: "MAC:AABBCCDDEEFF", "SMA_AABBCCDDEEFF", "ID:AA:BB:CC:DD:EE:FF"
 * 4. URL / Deep link: "http://.../app?mac=AA:BB:CC:DD:EE:FF&name=Watch", "sma://bind?mac=..."
 * 5. JSON format: {"mac": "AA:BB:CC:DD:EE:FF", "name": "Smart Watch"}
 */
public class QrDeviceParser {

    private static final Pattern COLON_MAC_PATTERN =
            Pattern.compile("(?i)\\b([0-9A-F]{2}[:-][0-9A-F]{2}[:-][0-9A-F]{2}[:-][0-9A-F]{2}[:-][0-9A-F]{2}[:-][0-9A-F]{2})\\b");

    private static final Pattern CONTINUOUS_HEX_MAC_PATTERN =
            Pattern.compile("(?i)(?:mac|m|addr|id|sma)?[:=_\\s-]*([0-9A-F]{12})\\b");

    private static final Pattern STRICT_12HEX_PATTERN =
            Pattern.compile("(?i)^[0-9A-F]{12}$");

    private static final Pattern URL_PARAM_PATTERN =
            Pattern.compile("(?i)[?&](?:mac|m|address|addr|ble|bssid|id|devicemac)=([^&]+)");

    private static final Pattern URL_NAME_PARAM_PATTERN =
            Pattern.compile("(?i)[?&](?:name|n|device|devicename|model)=([^&]+)");

    private static final Pattern JSON_MAC_PATTERN =
            Pattern.compile("(?i)\"(?:mac|address|bleAddress|ble_mac|macAddress|id)\"\\s*:\\s*\"([^\"]+)\"");

    private static final Pattern JSON_NAME_PATTERN =
            Pattern.compile("(?i)\"(?:name|deviceName|bleName|device|model)\"\\s*:\\s*\"([^\"]+)\"");

    public static class DeviceInfo {
        @NonNull
        public final String macAddress;
        @Nullable
        public final String deviceName;
        @NonNull
        public final String rawContent;

        public DeviceInfo(@NonNull String macAddress, @Nullable String deviceName, @NonNull String rawContent) {
            this.macAddress = macAddress;
            this.deviceName = deviceName;
            this.rawContent = rawContent;
        }

        @NonNull
        @Override
        public String toString() {
            return "DeviceInfo{mac='" + macAddress + '\'' +
                    (deviceName != null ? ", name='" + deviceName + '\'' : "") +
                    '}';
        }
    }

    /**
     * Parses the QR code raw string and extracts device MAC address and optional name.
     *
     * @param rawContent Scanned QR code content.
     * @return Parsed DeviceInfo or null if no valid MAC address could be extracted.
     */
    @Nullable
    public static DeviceInfo parse(@Nullable String rawContent) {
        if (rawContent == null) {
            return null;
        }

        String trimmed = rawContent.trim();
        if (trimmed.isEmpty()) {
            return null;
        }

        String mac = null;
        String name = null;

        // 1. Check JSON format
        if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
            Matcher jm = JSON_MAC_PATTERN.matcher(trimmed);
            if (jm.find()) {
                mac = normalizeMac(jm.group(1));
            }
            Matcher jn = JSON_NAME_PATTERN.matcher(trimmed);
            if (jn.find()) {
                name = jn.group(1).trim();
            }
        }

        // 2. Check URL query parameters
        if (mac == null && (trimmed.startsWith("http://") || trimmed.startsWith("https://") || trimmed.contains("://") || trimmed.contains("?"))) {
            Matcher up = URL_PARAM_PATTERN.matcher(trimmed);
            if (up.find()) {
                mac = normalizeMac(up.group(1));
            }
            Matcher un = URL_NAME_PARAM_PATTERN.matcher(trimmed);
            if (un.find()) {
                name = un.group(1).trim();
            }
        }

        // 3. Check standard colon/dash formatted MAC regex anywhere in content
        if (mac == null) {
            Matcher matcher = COLON_MAC_PATTERN.matcher(trimmed);
            if (matcher.find()) {
                mac = normalizeMac(matcher.group(1));
            }
        }

        // 4. Check continuous 12-hex MAC pattern
        if (mac == null) {
            Matcher hexMatcher = CONTINUOUS_HEX_MAC_PATTERN.matcher(trimmed);
            if (hexMatcher.find()) {
                mac = normalizeMac(hexMatcher.group(1));
            }
        }

        // 5. Check strict 12-hex string
        if (mac == null && STRICT_12HEX_PATTERN.matcher(trimmed).matches()) {
            mac = normalizeMac(trimmed);
        }

        if (mac != null && isValidMac(mac)) {
            return new DeviceInfo(mac, name, rawContent);
        }

        return null;
    }

    /**
     * Normalizes any valid representation of a 6-byte MAC into "AA:BB:CC:DD:EE:FF".
     */
    @Nullable
    public static String normalizeMac(@Nullable String input) {
        if (input == null) return null;
        String clean = input.trim().replace("-", ":").toUpperCase(Locale.US);

        if (COLON_MAC_PATTERN.matcher(clean).matches()) {
            return clean.replace('-', ':');
        }

        // Remove all non-hex characters
        String hexOnly = input.replaceAll("[^0-9A-Fa-f]", "").toUpperCase(Locale.US);
        if (hexOnly.length() == 12) {
            return String.format(Locale.US, "%s:%s:%s:%s:%s:%s",
                    hexOnly.substring(0, 2),
                    hexOnly.substring(2, 4),
                    hexOnly.substring(4, 6),
                    hexOnly.substring(6, 8),
                    hexOnly.substring(8, 10),
                    hexOnly.substring(10, 12));
        }

        return null;
    }

    /**
     * Checks if the normalized MAC is valid and not a broadcast/null address.
     */
    public static boolean isValidMac(@Nullable String mac) {
        if (mac == null) return false;
        if (!COLON_MAC_PATTERN.matcher(mac).matches()) return false;
        if ("00:00:00:00:00:00".equals(mac)) return false;
        if ("FF:FF:FF:FF:FF:FF".equalsIgnoreCase(mac)) return false;
        return true;
    }
}
