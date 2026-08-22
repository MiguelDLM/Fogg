package com.example.dialsender.ble;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.nio.charset.StandardCharsets;

/**
 * MUSIC_CONTROL (0x0402) body: [entity u8][attr u8][content UTF-8].
 *
 * The absence of a NUL terminator is the load-bearing detail — the original
 * SDK's BleMusicControl.getMLengthToWrite() is contentBytes + 2, leaving no
 * room for one, so the frame length is what delimits the string.
 */
public class MusicControlPayloadTest {

    private static final int MAX = 128;

    @Test
    public void writesEntityAttrThenRawUtf8() {
        byte[] p = BleManager.musicControlPayload(2, 2, "Hey");
        assertArrayEquals(new byte[] { 2, 2, 'H', 'e', 'y' }, p);
    }

    @Test
    public void doesNotTerminateTheString() {
        byte[] p = BleManager.musicControlPayload(0, 1, "1,1.0,,42");
        assertEquals(2 + 9, p.length);
        assertEquals('2', p[p.length - 1]);
    }

    @Test
    public void nullContentBecomesAnEmptyBody() {
        assertArrayEquals(new byte[] { 0, 2, }, BleManager.musicControlPayload(0, 2, null));
    }

    @Test
    public void multiByteCharactersSurviveIntact() {
        byte[] p = BleManager.musicControlPayload(2, 2, "Añoranza");
        byte[] expected = "Añoranza".getBytes(StandardCharsets.UTF_8);
        assertEquals(2 + expected.length, p.length);
        for (int i = 0; i < expected.length; i++)
            assertEquals(expected[i], p[i + 2]);
    }

    @Test
    public void longTitleIsCappedAtTheContentLimit() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 500; i++)
            sb.append('x');
        byte[] p = BleManager.musicControlPayload(2, 2, sb.toString());
        assertEquals(2 + MAX, p.length);
    }

    /**
     * A cut landing mid-sequence must move back to the previous lead byte, or
     * the watch would render a replacement glyph at the end of every long
     * title that happens to be non-ASCII.
     */
    @Test
    public void truncationNeverSplitsAUtf8Sequence() {
        // 'é' is two bytes, so 64 of them are exactly 128 bytes; adding one
        // more forces a cut at a boundary that must not fall inside a pair.
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 70; i++)
            sb.append('é');
        byte[] p = BleManager.musicControlPayload(2, 2, sb.toString());

        byte[] body = new byte[p.length - 2];
        System.arraycopy(p, 2, body, 0, body.length);
        assertEquals(MAX, body.length);
        assertEquals(64, new String(body, StandardCharsets.UTF_8).length());
    }

    @Test
    public void truncationBacksOffAnOddBoundary() {
        // "aé" repeated: byte 128 lands inside an 'é', so the cut drops it.
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 70; i++)
            sb.append("aé");
        byte[] raw = sb.toString().getBytes(StandardCharsets.UTF_8);
        byte[] cut = BleManager.truncateUtf8(raw, MAX);
        assertEquals(MAX - 1, cut.length);
        // Decoding is lossless: no replacement character at the tail.
        String decoded = new String(cut, StandardCharsets.UTF_8);
        assertEquals(-1, decoded.indexOf('�'));
    }

    @Test
    public void contentExactlyAtTheLimitIsNotTouched() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < MAX; i++)
            sb.append('x');
        byte[] p = BleManager.musicControlPayload(2, 2, sb.toString());
        assertEquals(2 + MAX, p.length);
    }
}
