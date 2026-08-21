package com.example.dialsender.ble;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.*;

public class BleDeviceInfoTest {

    /** Builder mirroring the wire order in 11-DEVICE-INFO-CAPABILITIES.md. */
    private static class Payload {
        private final ByteArrayOutputStream out = new ByteArrayOutputStream();

        Payload u8(int v) {
            out.write(v & 0xFF);
            return this;
        }

        Payload u16(int v) {
            return u8(v >> 8).u8(v);
        }

        Payload i32(int v) {
            return u16(v >>> 16).u16(v);
        }

        /** NUL-terminated string. */
        Payload str(String s) {
            byte[] b = s.getBytes(StandardCharsets.UTF_8);
            out.write(b, 0, b.length);
            return u8(0);
        }

        /** NUL-terminated run of big-endian BleKey values. */
        Payload keys(int... keys) {
            for (int k : keys)
                u16(k);
            return u8(0);
        }

        Payload flags(int count, int value) {
            for (int i = 0; i < count; i++)
                u8(value);
            return this;
        }

        byte[] build() {
            return out.toByteArray();
        }
    }

    /** Header through mSleepAlgorithmType, with the given key list. */
    private Payload header(int... keys) {
        return new Payload()
                .i32(0x11223344)
                .keys(keys)
                .str("KRONOS")        // mBleName
                .str("aa:bb:cc")      // mBleAddress
                .str("HK89")          // mPlatform
                .str("PROTO1")        // mPrototype
                .str("FW1.2")         // mFirmwareFlag
                .u8(2)                // mAGpsType
                .u16(512)             // mIOBufferSize
                .u8(3)                // mWatchFaceType
                .str("dd:ee:ff")      // mClassicAddress
                .u8(0)                // mHideDigitalPower
                .u8(1)                // mShowAntiLostSwitch
                .u8(4);               // mSleepAlgorithmType
    }

    private static final int FLAGS_A_COUNT = 37;
    private static final int FLAGS_B_COUNT = 61;

    @Test
    public void parsesHeaderFields() {
        BleDeviceInfo info = BleDeviceInfo.parse(header(0x0502, 0x0503).build());

        assertEquals(0x11223344, info.id);
        assertEquals("KRONOS", info.bleName);
        assertEquals("AA:BB:CC", info.bleAddress);   // upper-cased by the parser
        assertEquals("HK89", info.platform);
        assertEquals("PROTO1", info.prototype);
        assertEquals("FW1.2", info.firmwareFlag);
        assertEquals("DD:EE:FF", info.classicAddress);
        assertEquals(2, info.agpsType);
        assertEquals(512, info.ioBufferSize);
        assertEquals(3, info.watchFaceType);
        assertEquals(4, info.sleepAlgorithmType);
    }

    @Test
    public void parsesDataKeys() {
        BleDeviceInfo info = BleDeviceInfo.parse(header(0x0502, 0x0503, 0x0523).build());

        assertTrue(info.hasDataKeys());
        assertEquals(3, info.dataKeys.size());
        assertTrue(info.supportsKey(0x0502));
        assertTrue(info.supportsKey(0x0523));
        assertFalse(info.supportsKey(0x0509));
        assertEquals("0x0502,0x0503,0x0523", info.dataKeysHex());
    }

    @Test
    public void emptyKeyListMeansUnreported() {
        BleDeviceInfo info = BleDeviceInfo.parse(header().build());
        assertFalse(info.hasDataKeys());
        assertTrue(info.dataKeys.isEmpty());
    }

    /**
     * mBleDefaultName sits between the two flag blocks and must be consumed
     * even when empty — skipping it shifts every FLAGS_B value.
     */
    @Test
    public void defaultNameIsAlwaysConsumed() {
        byte[] payload = header(0x0502)
                .flags(FLAGS_A_COUNT, 1)
                .str("")                       // empty mBleDefaultName
                .flags(FLAGS_B_COUNT, 1)
                .build();

        BleDeviceInfo info = BleDeviceInfo.parse(payload);

        assertEquals(FLAGS_A_COUNT + FLAGS_B_COUNT, info.flagsRead);
        assertTrue(info.supports("MusicTransfer"));   // first flag after the name
        assertTrue(info.supports("Power2"));          // last flag on the wire
        assertEquals("KRONOS", info.bleName);         // empty default leaves the name alone
    }

    @Test
    public void nonEmptyDefaultNameOverridesAndPreservesCustom() {
        byte[] payload = header(0x0502)
                .flags(FLAGS_A_COUNT, 0)
                .str("SMA-B7")
                .flags(FLAGS_B_COUNT, 0)
                .build();

        BleDeviceInfo info = BleDeviceInfo.parse(payload);

        assertEquals("SMA-B7", info.bleName);
        assertEquals("KRONOS", info.bleCustomName);
        assertEquals(FLAGS_A_COUNT + FLAGS_B_COUNT, info.flagsRead);
    }

    /** An OEM name embedded in mFirmwareFlag after "<>" wins over mBleName. */
    @Test
    public void firmwareFlagNameOverride() {
        byte[] payload = new Payload()
                .i32(1)
                .keys(0x0502)
                .str("KRONOS")
                .str("aa")
                .str("HK89")
                .str("P")
                .str("FW1.2<>Thunder")   // separator + marketing name
                .u8(0).u16(480).u8(0)
                .str("cc")
                .u8(0).u8(0).u8(0)
                .flags(FLAGS_A_COUNT, 0)
                .str("")
                .build();

        BleDeviceInfo info = BleDeviceInfo.parse(payload);

        assertEquals("Thunder", info.bleName);
        assertEquals("KRONOS", info.bleCustomName);
    }

    /** The byte holds tens of contacts. */
    @Test
    public void contactSizeIsScaledByTen() {
        Payload p = header(0x0502);
        // ContactSize is index 33 within FLAGS_A.
        for (int i = 0; i < FLAGS_A_COUNT; i++)
            p.u8(i == 33 ? 10 : 0);
        BleDeviceInfo info = BleDeviceInfo.parse(p.str("").build());

        assertEquals(100, info.flag("ContactSize"));
    }

    /**
     * Older firmware truncates the payload. Reads past the end must report
     * "unsupported", never throw.
     */
    @Test
    public void truncatedPayloadDegradesGracefully() {
        byte[] payload = header(0x0502).flags(5, 1).build();

        BleDeviceInfo info = BleDeviceInfo.parse(payload);

        assertEquals(5, info.flagsRead);
        assertTrue(info.supports("DateFormatSet"));   // present
        assertFalse(info.supports("Power2"));         // never arrived
        assertEquals(0, info.flag("Power2"));
        assertEquals(512, info.ioBufferSize);         // header still intact
    }

    @Test
    public void emptyAndNullPayloadsAreSafe() {
        BleDeviceInfo empty = BleDeviceInfo.parse(new byte[0]);
        assertEquals(0, empty.flagsRead);
        assertFalse(empty.hasDataKeys());
        assertEquals(0, empty.ioBufferSize);

        BleDeviceInfo nul = BleDeviceInfo.parse(null);
        assertEquals(0, nul.flagsRead);
        assertFalse(nul.hasDataKeys());
    }

    @Test
    public void unknownFlagReadsAsZero() {
        BleDeviceInfo info = BleDeviceInfo.parse(header(0x0502).build());
        assertEquals(0, info.flag("NoSuchCapability"));
        assertFalse(info.supports("NoSuchCapability"));
    }

    // ---- V2 (identity) variant ----

    /**
     * Real DEVICE_INFO reply from a Kronos Thunder (JL / AM05), frame body from
     * offset 9. This watch answers 0x023E with the BleDeviceInfo2 shape, which
     * shares no layout with the capability block.
     */
    private static final String KRONOS_V2 =
            "37 33 3A 30 38 3A 31 31 3A 41 35 3A 41 45 3A 36 31 00" +   // "73:08:11:A5:AE:61"
            " 00" +                                                      // empty classic address
            " 00 00 06" +                                                // firmware 0.0.6
            " 00 00 01" +                                                // UI 0.0.1
            " 00 00 00" +                                                // language 0.0.0
            " 00" +                                                      // language code
            " 4B 72 6F 6E 6F 73 20 54 68 75 6E 64 65 72 00" +            // "Kronos Thunder"
            " 4A 4C 00" +                                                // "JL"
            " 41 4D 30 35 00" +                                          // "AM05"
            " 47 36 5F 4E 45 57 5F 4B 72 6F 6E 6F 73 5F 54 68 75 6E 64 65 72 00" + // firmware flag
            " 4B 72 6F 6E 6F 73 5F 54 68 75 6E 64 65 72 5F 56 30 30 36 00";        // full version

    private static byte[] hex(String s) {
        String[] parts = s.trim().split("\\s+");
        byte[] out = new byte[parts.length];
        for (int i = 0; i < parts.length; i++)
            out[i] = (byte) Integer.parseInt(parts[i], 16);
        return out;
    }

    @Test
    public void parsesRealKronosThunderReply() {
        BleDeviceInfo info = BleDeviceInfo.parse(hex(KRONOS_V2));

        assertEquals(BleDeviceInfo.Variant.V2_IDENTITY, info.variant);
        assertEquals("73:08:11:A5:AE:61", info.bleAddress);
        assertEquals("", info.classicAddress);
        assertEquals("0.0.6", info.firmwareVersion);
        assertEquals("0.0.1", info.uiVersion);
        assertEquals("0.0.0", info.languageVersion);
        assertEquals(0, info.languageCode);
        assertEquals("Kronos Thunder", info.bleName);
        assertEquals("JL", info.platform);
        assertEquals("AM05", info.prototype);
        assertEquals("G6_NEW_Kronos_Thunder", info.firmwareFlag);
        assertEquals("Kronos_Thunder_V006", info.fullVersion);
    }

    /**
     * V2 carries no key list, so health sync must fall back to the built-in
     * list rather than reading garbage keys out of the MAC string — which is
     * exactly what the V1-only parser did against real hardware.
     */
    @Test
    public void v2ReportsNoCapabilities() {
        BleDeviceInfo info = BleDeviceInfo.parse(hex(KRONOS_V2));

        assertFalse(info.hasDataKeys());
        assertTrue(info.dataKeys.isEmpty());
        assertEquals("", info.dataKeysHex());
        assertEquals(0, info.flagsRead);
        assertFalse(info.supports("MusicTransfer"));
        assertEquals(0, info.ioBufferSize);
    }

    /** A V1 block must not be mistaken for V2 by the MAC sniffer. */
    @Test
    public void v1IsNotSniffedAsV2() {
        BleDeviceInfo info = BleDeviceInfo.parse(header(0x0502).build());
        assertEquals(BleDeviceInfo.Variant.V1_CAPABILITY, info.variant);
        assertTrue(info.hasDataKeys());
    }

    /** A 17-char leading string that is not a MAC stays on the V1 path. */
    @Test
    public void nearMissStringIsNotV2() {
        byte[] payload = new Payload().str("not-a-mac-address").flags(4, 1).build();
        BleDeviceInfo info = BleDeviceInfo.parse(payload);
        assertEquals(BleDeviceInfo.Variant.V1_CAPABILITY, info.variant);
    }

    @Test
    public void truncatedV2DegradesGracefully() {
        byte[] full = hex(KRONOS_V2);
        byte[] cut = new byte[30];
        System.arraycopy(full, 0, cut, 0, cut.length);

        BleDeviceInfo info = BleDeviceInfo.parse(cut);

        assertEquals(BleDeviceInfo.Variant.V2_IDENTITY, info.variant);
        assertEquals("73:08:11:A5:AE:61", info.bleAddress);
        assertEquals("0.0.6", info.firmwareVersion);
        assertEquals("", info.fullVersion);   // never arrived
    }
}
