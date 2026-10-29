package com.example.dialsender.ble;

import org.junit.Test;

import java.util.UUID;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class BleUuidsTest {

    @Test
    public void acceptsBothNordicUartServiceVariants() {
        assertTrue(BleUuids.isWatchService(BleUuids.SERVICE));
        assertTrue(BleUuids.isWatchService(BleUuids.SERVICE_ALT));
        assertFalse(BleUuids.isWatchService(UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dccaaa")));
    }

    @Test
    public void acceptsBothNordicUartCharacteristicVariants() {
        assertTrue(BleUuids.isWatchWriteCharacteristic(BleUuids.WRITE_CHAR));
        assertTrue(BleUuids.isWatchWriteCharacteristic(BleUuids.WRITE_CHAR_ALT));
        assertTrue(BleUuids.isWatchNotifyCharacteristic(BleUuids.NOTIFY_CHAR));
        assertTrue(BleUuids.isWatchNotifyCharacteristic(BleUuids.NOTIFY_CHAR_ALT));
    }
}
