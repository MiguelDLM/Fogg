package com.example.dialsender.ble;

import java.util.UUID;

/**
 * The GATT addresses of the watch protocol, in one place.
 *
 * These used to be copy-pasted string literals in both {@link BleManager} and
 * {@link WatchFilter}. Issue #8 was reported against "BleManager line 60" and
 * the second copy — the one the device picker ranks with — was invisible to
 * anyone reading that line. One definition means the address the app connects
 * to can never drift from the address it recognises.
 *
 * Fogg speaks the SMA protocol layer (0xAB framing, see
 * docs/SMA_BLE_PROTOCOL.md) carried over the Nordic UART Service. A watch that
 * exposes some other transport is running a different protocol, not just a
 * different address, so this is deliberately one profile and not a list of
 * candidates to probe.
 */
public final class BleUuids {

    /** Nordic UART Service — the only channel the watch protocol runs on. */
    public static final UUID SERVICE = UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e");

    /** Phone → watch. */
    public static final UUID WRITE_CHAR = UUID.fromString("6e400002-b5a3-f393-e0a9-e50e24dcca9e");

    /** Watch → phone, via notifications. */
    public static final UUID NOTIFY_CHAR = UUID.fromString("6e400003-b5a3-f393-e0a9-e50e24dcca9e");

    /** Client Characteristic Configuration Descriptor, 0x2902. */
    public static final UUID CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    /** Suffix every 16-bit SIG-based UUID expands to. */
    private static final String SIG_BASE_SUFFIX = "-0000-1000-8000-00805f9b34fb";

    private BleUuids() {
    }

    /**
     * Render a UUID the way nRF Connect does: {@code 0x8800} for the SIG-based
     * short forms, the full 128-bit value otherwise, with a friendly name when
     * we know one. Keeping the two representations identical is what lets a
     * dump pasted into an issue be compared against a dump from nRF Connect
     * without anyone having to expand base UUIDs by hand.
     */
    public static String describe(UUID uuid) {
        if (uuid == null)
            return "(null)";
        String s = uuid.toString().toLowerCase();
        String label = friendlyName(uuid);
        if (s.startsWith("0000") && s.endsWith(SIG_BASE_SUFFIX)) {
            String shortForm = "0x" + s.substring(4, 8).toUpperCase();
            return label != null ? shortForm + " (" + label + ")" : shortForm;
        }
        return label != null ? s + " (" + label + ")" : s;
    }

    private static String friendlyName(UUID uuid) {
        if (SERVICE.equals(uuid))
            return "Nordic UART Service";
        if (WRITE_CHAR.equals(uuid))
            return "NUS TX / write";
        if (NOTIFY_CHAR.equals(uuid))
            return "NUS RX / notify";
        if (CCCD.equals(uuid))
            return "CCCD";
        String s = uuid.toString().toLowerCase();
        if (!s.startsWith("0000") || !s.endsWith(SIG_BASE_SUFFIX))
            return null;
        switch (s.substring(4, 8)) {
            case "1800":
                return "Generic Access";
            case "1801":
                return "Generic Attribute";
            case "180a":
                return "Device Information";
            case "180f":
                return "Battery Service";
            case "1812":
                return "HID";
            case "fee7":
                return "Tencent";
            default:
                return null;
        }
    }

    /**
     * Decode the property bitmask of a characteristic into the flag names
     * nRF Connect prints. A transport is only usable by this app if some
     * characteristic is writable and another one notifies, so the properties
     * are the single most useful thing in a compatibility report.
     */
    public static String describeProperties(int properties) {
        StringBuilder sb = new StringBuilder();
        appendFlag(sb, properties, 0x02, "READ");
        appendFlag(sb, properties, 0x04, "WRITE_NO_RESPONSE");
        appendFlag(sb, properties, 0x08, "WRITE");
        appendFlag(sb, properties, 0x10, "NOTIFY");
        appendFlag(sb, properties, 0x20, "INDICATE");
        appendFlag(sb, properties, 0x01, "BROADCAST");
        appendFlag(sb, properties, 0x40, "SIGNED_WRITE");
        appendFlag(sb, properties, 0x80, "EXTENDED");
        return sb.length() == 0 ? "none" : sb.toString();
    }

    private static void appendFlag(StringBuilder sb, int properties, int mask, String name) {
        if ((properties & mask) == 0)
            return;
        if (sb.length() > 0)
            sb.append('|');
        sb.append(name);
    }
}
