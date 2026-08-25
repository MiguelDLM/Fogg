package com.example.dialsender.ble;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public class QrDeviceParserTest {

    @Test
    public void testPlainColonMac() {
        QrDeviceParser.DeviceInfo info = QrDeviceParser.parse("AA:BB:CC:DD:EE:FF");
        assertNotNull(info);
        assertEquals("AA:BB:CC:DD:EE:FF", info.macAddress);
    }

    @Test
    public void testPlainDashMac() {
        QrDeviceParser.DeviceInfo info = QrDeviceParser.parse("1a-2b-3c-4d-5e-6f");
        assertNotNull(info);
        assertEquals("1A:2B:3C:4D:5E:6F", info.macAddress);
    }

    @Test
    public void testContinuousHex() {
        QrDeviceParser.DeviceInfo info = QrDeviceParser.parse("A1B2C3D4E5F6");
        assertNotNull(info);
        assertEquals("A1:B2:C3:D4:E5:F6", info.macAddress);
    }

    @Test
    public void testUrlWithMacParam() {
        QrDeviceParser.DeviceInfo info = QrDeviceParser.parse("https://app.smawatch.com/download.html?mac=12:34:56:78:9A:BC&name=KronosWatch");
        assertNotNull(info);
        assertEquals("12:34:56:78:9A:BC", info.macAddress);
        assertEquals("KronosWatch", info.deviceName);
    }

    @Test
    public void testUrlWithHexMacParam() {
        QrDeviceParser.DeviceInfo info = QrDeviceParser.parse("http://c2-app.com/down?m=AABBCCDDEEFF");
        assertNotNull(info);
        assertEquals("AA:BB:CC:DD:EE:FF", info.macAddress);
    }

    @Test
    public void testJsonPayload() {
        String json = "{\"mac\":\"AA:BB:CC:11:22:33\",\"name\":\"Thunder\"}";
        QrDeviceParser.DeviceInfo info = QrDeviceParser.parse(json);
        assertNotNull(info);
        assertEquals("AA:BB:CC:11:22:33", info.macAddress);
        assertEquals("Thunder", info.deviceName);
    }

    @Test
    public void testTaggedString() {
        QrDeviceParser.DeviceInfo info = QrDeviceParser.parse("SMA_DEV_MAC:11:22:33:44:55:66");
        assertNotNull(info);
        assertEquals("11:22:33:44:55:66", info.macAddress);
    }

    @Test
    public void testInvalidPayload() {
        assertNull(QrDeviceParser.parse(""));
        assertNull(QrDeviceParser.parse("hello world"));
        assertNull(QrDeviceParser.parse("00:00:00:00:00:00"));
        assertNull(QrDeviceParser.parse("https://google.com"));
    }
}
