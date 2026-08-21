package com.example.dialsender.ble;

/**
 * BleKey values used by this app, taken from the decompiled CO-FIT SDK
 * (com.szabh.smable3.BleKey, 242 constants). The full map lives in
 * docs/protocols/02-COMMAND-PROTOCOL.md §6.
 *
 * Only the keys the app actually speaks are listed here; adding one is not the
 * same as supporting it. The earlier version of this enum was transcribed from
 * a protocol doc whose key map was invented from 0x0211 onward — in particular
 * it called 0x0215 IDENTITY when that value is ANTI_LOST, which is what
 * DeviceFragment has been sending as anti-lost all along.
 */
public enum BleKey {
    // 0x01 UPDATE
    OTA(0x0101),
    XMODEM(0x0102),

    // 0x02 SET
    TIME(0x0201),
    TIME_ZONE(0x0202),
    POWER(0x0203),
    FIRMWARE_VERSION(0x0204),
    BLE_ADDRESS(0x0205),
    USER_PROFILE(0x0206),
    STEP_GOAL(0x0207),
    BACK_LIGHT(0x0208),
    SEDENTARINESS(0x0209),
    NO_DISTURB_RANGE(0x020A),
    GESTURE_WAKE(0x020C),
    HOUR_SYSTEM(0x020E),
    FIND_PHONE(0x0213), // watch-initiated
    ANTI_LOST(0x0215),
    AGPS_PREREQUISITE(0x0220),
    WATCHFACE_ID(0x0227),
    DEVICE_INFO(0x023E),

    // 0x03 CONNECT
    IDENTITY(0x0301),
    SESSION(0x0302), // CREATE + FF FF FF FF opens the session

    // 0x04 PUSH
    NOTIFICATION(0x0401),
    MUSIC_CONTROL(0x0402),
    WEATHER_REALTIME(0x0404),
    WEATHER_FORECAST(0x0405),
    WEATHER_REALTIME2(0x040C),

    // 0x05 DATA
    ACTIVITY_REALTIME(0x0501),
    ACTIVITY(0x0502),
    HEART_RATE(0x0503),
    BLOOD_PRESSURE(0x0504),
    SLEEP(0x0505),
    WORKOUT(0x0506),
    LOCATION(0x0507),
    TEMPERATURE(0x0508),
    BLOOD_OXYGEN(0x0509),
    HRV(0x050A),
    PRESSURE(0x050D), // stress
    WORKOUT2(0x050E),
    WORKOUT3(0x0523), // with GPS polyline
    DATA_ALL(0x05FF),

    // 0x06 CONTROL
    CAMERA(0x0601),

    // 0x07 IO
    WATCH_FACE(0x0701),
    AGPS_FILE(0x0702),

    NONE(0xFFFF);

    private final int mKey; // e.g. 0x0503

    BleKey(int mKey) {
        this.mKey = mKey;
    }

    public int getMKey() {
        return mKey;
    }

    public int getCommand() {
        return mKey >>> 8; // e.g. 5
    }

    public int getKey() {
        return mKey & 0xFF; // e.g. 3
    }

    public static BleKey fromRaw(int command, int key) {
        int target = (command << 8) | key;
        for (BleKey b : values()) {
            if (b.mKey == target)
                return b;
        }
        return NONE;
    }
}
