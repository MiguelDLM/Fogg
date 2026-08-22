package com.example.dialsender.ble;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Calendar;
import java.util.List;

/**
 * The 28-byte ALARM (0x0210) item.
 *
 * The bit packing is the part worth pinning down: the original SDK writes
 * enabled with writeIntN(x, 1) and repeat with writeIntN(x, 7), MSB-first, so
 * both share byte 1. Getting that backwards silently produces alarms that fire
 * on the wrong days rather than an error.
 */
public class BleAlarmTest {

    private static BleAlarm sample() {
        BleAlarm a = new BleAlarm();
        a.id = 3;
        a.enabled = true;
        a.repeat = BleAlarm.WORKDAY;
        a.year = 2026;
        a.month = 8;
        a.day = 22;
        a.hour = 7;
        a.minute = 30;
        a.tag = "Work";
        return a;
    }

    @Test
    public void itemIsAlwaysTwentyEightBytes() {
        assertEquals(28, sample().encode().length);
        assertEquals(28, new BleAlarm().encode().length);
    }

    @Test
    public void enabledTakesTheTopBitAndRepeatTheRest() {
        BleAlarm a = sample();
        // 1 <<7 | 31 == 0x9F
        assertEquals((byte) 0x9F, a.encode()[1]);

        a.enabled = false;
        assertEquals((byte) 0x1F, a.encode()[1]);

        a.enabled = true;
        a.repeat = BleAlarm.ONCE;
        assertEquals((byte) 0x80, a.encode()[1]);

        a.repeat = BleAlarm.EVERYDAY;
        assertEquals((byte) 0xFF, a.encode()[1]);
    }

    @Test
    public void weekdayMaskIsMondayFirst() {
        assertEquals(1, BleAlarm.MONDAY);
        assertEquals(64, BleAlarm.SUNDAY);
        assertEquals(BleAlarm.MONDAY | BleAlarm.TUESDAY | BleAlarm.WEDNESDAY
                | BleAlarm.THURSDAY | BleAlarm.FRIDAY, BleAlarm.WORKDAY);
        assertEquals(BleAlarm.SATURDAY | BleAlarm.SUNDAY, BleAlarm.WEEKEND);
    }

    @Test
    public void fieldsLandInTheDocumentedOffsets() {
        byte[] w = sample().encode();
        assertEquals(3, w[0] & 0xFF);        // id
        assertEquals(26, w[2] & 0xFF);       // year - 2000
        assertEquals(8, w[3] & 0xFF);        // month
        assertEquals(22, w[4] & 0xFF);       // day
        assertEquals(7, w[5] & 0xFF);        // hour
        assertEquals(30, w[6] & 0xFF);       // minute
    }

    @Test
    public void tagIsZeroPaddedToTwentyOneBytes() {
        byte[] w = sample().encode();
        assertArrayEquals("Work".getBytes(StandardCharsets.UTF_8), Arrays.copyOfRange(w, 7, 11));
        for (int i = 11; i < 28; i++)
            assertEquals("byte " + i + " should be padding", 0, w[i]);
    }

    @Test
    public void roundTripsThroughDecode() {
        BleAlarm a = sample();
        BleAlarm b = BleAlarm.decode(a.encode(), 0);
        assertEquals(a.id, b.id);
        assertEquals(a.enabled, b.enabled);
        assertEquals(a.repeat, b.repeat);
        assertEquals(a.year, b.year);
        assertEquals(a.month, b.month);
        assertEquals(a.day, b.day);
        assertEquals(a.hour, b.hour);
        assertEquals(a.minute, b.minute);
        assertEquals(a.tag, b.tag);
    }

    @Test
    public void decodeStopsTheTagAtItsPadding() {
        byte[] w = sample().encode();
        // Junk after the padding must not leak into the tag.
        w[20] = 'X';
        assertEquals("Work", BleAlarm.decode(w, 0).tag);
    }

    @Test
    public void anOverlongTagIsCutOnAUtf8Boundary() {
        BleAlarm a = sample();
        // 11 x "ñ" is 22 bytes, one past the 21-byte field.
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 11; i++)
            sb.append('ñ');
        a.tag = sb.toString();

        BleAlarm b = BleAlarm.decode(a.encode(), 0);
        assertEquals(10, b.tag.length());
        assertEquals(-1, b.tag.indexOf('�'));
    }

    @Test
    public void listSplitsOnItemLengthWithNoHeader() {
        BleAlarm a = sample();
        BleAlarm b = new BleAlarm(9, false, BleAlarm.WEEKEND, 9, 5, "Gym");
        byte[] body = new byte[56];
        System.arraycopy(a.encode(), 0, body, 0, 28);
        System.arraycopy(b.encode(), 0, body, 28, 28);

        List<BleAlarm> list = BleAlarm.decodeList(body);
        assertEquals(2, list.size());
        assertEquals(3, list.get(0).id);
        assertEquals(9, list.get(1).id);
        assertFalse(list.get(1).enabled);
        assertEquals("Gym", list.get(1).tag);
    }

    @Test
    public void aTrailingPartialItemIsDropped() {
        assertEquals(1, BleAlarm.decodeList(new byte[40]).size());
        assertEquals(0, BleAlarm.decodeList(new byte[27]).size());
        assertEquals(0, BleAlarm.decodeList(null).size());
    }

    @Test
    public void recurringAlarmsAreNotGivenADate() {
        BleAlarm a = sample();          // WORKDAY
        a.year = 0;
        assertFalse(a.rollToFuture());
        assertEquals(0, a.year);
    }

    /**
     * The original app bumps day-of-month directly, which yields the 32nd at
     * the end of a month. Rolling through Calendar must land on a real date.
     */
    @Test
    public void aPastOneShotRollsToARealFutureDate() {
        BleAlarm a = new BleAlarm(1, true, BleAlarm.ONCE, 7, 30, "Once");
        a.year = 2020;
        a.month = 1;
        a.day = 1;
        assertTrue(a.rollToFuture());

        Calendar at = Calendar.getInstance();
        at.setLenient(false);           // throws if the date is not real
        at.set(a.year, a.month - 1, a.day, a.hour, a.minute, 0);
        at.set(Calendar.MILLISECOND, 0);
        assertTrue("rolled date must be in the future", at.after(Calendar.getInstance()));
        assertTrue(a.day >= 1 && a.day <= 31);
        assertTrue(a.month >= 1 && a.month <= 12);
    }

    @Test
    public void aFutureOneShotIsLeftAlone() {
        Calendar later = Calendar.getInstance();
        later.add(Calendar.DAY_OF_MONTH, 3);

        BleAlarm a = new BleAlarm(1, true, BleAlarm.ONCE,
                later.get(Calendar.HOUR_OF_DAY), later.get(Calendar.MINUTE), "Once");
        a.year = later.get(Calendar.YEAR);
        a.month = later.get(Calendar.MONTH) + 1;
        a.day = later.get(Calendar.DAY_OF_MONTH);

        assertFalse(a.rollToFuture());
        assertEquals(later.get(Calendar.DAY_OF_MONTH), a.day);
    }
}
