package com.example.dialsender.ble;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

/**
 * One alarm as the watch stores it (ALARM 0x0210).
 *
 * The wire item is exactly 28 bytes and is never length-prefixed, so a READ
 * reply is simply {@code n * 28} bytes:
 *
 * <pre>
 *   0        id            u8
 *   1 bit 7  enabled       1 bit
 *   1 bit 6..0 repeat      7 bits, weekday mask
 *   2        year - 2000   u8
 *   3        month  1..12  u8
 *   4        day    1..31  u8
 *   5        hour   0..23  u8
 *   6        minute 0..59  u8
 *   7..27    tag           21 bytes, UTF-8, zero padded
 * </pre>
 *
 * Bits are packed MSB-first, matching {@code BleWritable.writeIntN} in the
 * original SDK: {@code enabled} takes the top bit of byte 1 and {@code repeat}
 * the remaining seven.
 *
 * Protocol: docs/protocols/13-ALARM.md
 */
public class BleAlarm {

    public static final int ITEM_LENGTH = 28;
    public static final int TAG_LENGTH = 21;

    // Weekday mask. Monday is the low bit — the original SDK's BleRepeat
    // orders WEEKDAYS as Mon..Sun, NOT the Calendar.SUNDAY-first convention.
    public static final int ONCE = 0;
    public static final int MONDAY = 1;
    public static final int TUESDAY = 2;
    public static final int WEDNESDAY = 4;
    public static final int THURSDAY = 8;
    public static final int FRIDAY = 16;
    public static final int SATURDAY = 32;
    public static final int SUNDAY = 64;
    public static final int WORKDAY = 31;   // Mon..Fri
    public static final int WEEKEND = 96;   // Sat + Sun
    public static final int EVERYDAY = 127;

    public int id;
    public boolean enabled = true;
    public int repeat = ONCE;
    public int year;
    public int month;
    public int day;
    public int hour;
    public int minute;
    public String tag = "";

    public BleAlarm() {
    }

    public BleAlarm(int id, boolean enabled, int repeat, int hour, int minute, String tag) {
        this.id = id;
        this.enabled = enabled;
        this.repeat = repeat;
        this.hour = hour;
        this.minute = minute;
        this.tag = tag == null ? "" : tag;
    }

    // ---- wire format ----

    public byte[] encode() {
        byte[] out = new byte[ITEM_LENGTH];
        out[0] = (byte) id;
        out[1] = (byte) (((enabled ? 1 : 0) << 7) | (repeat & 0x7F));
        out[2] = (byte) (year >= 2000 ? year - 2000 : 0);
        out[3] = (byte) month;
        out[4] = (byte) day;
        out[5] = (byte) hour;
        out[6] = (byte) minute;

        byte[] text = tag == null ? new byte[0] : tag.getBytes(StandardCharsets.UTF_8);
        int n = Math.min(text.length, TAG_LENGTH);
        // A cut mid-sequence would leave a broken glyph on the watch; back off
        // to the last lead byte. The rest of the field stays zero-padded.
        while (n > 0 && n < text.length && (text[n] & 0xC0) == 0x80)
            n--;
        System.arraycopy(text, 0, out, 7, n);
        return out;
    }

    public static BleAlarm decode(byte[] item, int offset) {
        BleAlarm a = new BleAlarm();
        a.id = item[offset] & 0xFF;
        int packed = item[offset + 1] & 0xFF;
        a.enabled = (packed & 0x80) != 0;
        a.repeat = packed & 0x7F;
        a.year = 2000 + (item[offset + 2] & 0xFF);
        a.month = item[offset + 3] & 0xFF;
        a.day = item[offset + 4] & 0xFF;
        a.hour = item[offset + 5] & 0xFF;
        a.minute = item[offset + 6] & 0xFF;

        // The tag is zero-padded, not NUL-terminated in the C sense: stop at
        // the first zero and ignore whatever follows it.
        int end = offset + 7;
        int limit = offset + 7 + TAG_LENGTH;
        while (end < limit && item[end] != 0)
            end++;
        a.tag = new String(item, offset + 7, end - (offset + 7), StandardCharsets.UTF_8);
        return a;
    }

    /**
     * Split a READ reply into items.
     *
     * There is no count field: the body is a whole number of 28-byte items. A
     * trailing partial item is dropped rather than guessed at.
     */
    public static List<BleAlarm> decodeList(byte[] body) {
        List<BleAlarm> out = new ArrayList<>();
        if (body == null)
            return out;
        for (int off = 0; off + ITEM_LENGTH <= body.length; off += ITEM_LENGTH)
            out.add(decode(body, off));
        return out;
    }

    // ---- helpers ----

    public boolean isRecurring() {
        return (repeat & 0x7F) != 0;
    }

    /**
     * Point a one-shot alarm at the next time it can still fire.
     *
     * Recurring alarms carry no date, so they are left alone. The original app
     * does this too, but by incrementing the day-of-month directly — which
     * produces the 32nd of a month at the end of one. Rolling through Calendar
     * avoids that.
     *
     * @return true if the date was moved
     */
    public boolean rollToFuture() {
        if (isRecurring())
            return false;

        Calendar now = Calendar.getInstance();
        Calendar at = Calendar.getInstance();
        if (year >= 2000 && month >= 1 && day >= 1) {
            at.set(year, month - 1, day, hour, minute, 0);
            at.set(Calendar.MILLISECOND, 0);
            if (at.after(now))
                return false;
        }

        at = Calendar.getInstance();
        at.set(Calendar.HOUR_OF_DAY, hour);
        at.set(Calendar.MINUTE, minute);
        at.set(Calendar.SECOND, 0);
        at.set(Calendar.MILLISECOND, 0);
        if (!at.after(now))
            at.add(Calendar.DAY_OF_MONTH, 1);

        year = at.get(Calendar.YEAR);
        month = at.get(Calendar.MONTH) + 1;
        day = at.get(Calendar.DAY_OF_MONTH);
        return true;
    }

    @Override
    public String toString() {
        return "BleAlarm{id=" + id + " enabled=" + enabled + " repeat=" + repeat
                + " " + year + "-" + month + "-" + day
                + " " + hour + ":" + minute + " tag='" + tag + "'}";
    }
}
