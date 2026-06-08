package com.cycglass.monitor;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import org.junit.Test;

import java.util.Random;

/**
 * Verifies VESC packet framing round-trips, CRC16 stability, and tolerance
 * to junk bytes between frames.
 *
 * <p>The CRC16 reference vector {@code COMM_GET_VALUES} =
 * {@code 02 01 04 40 84 03} is the same byte sequence observed on the
 * wire by the CYC X1 Pro Gen4 controller (validated against the
 * {@code cygnus-bike} project's {@code scripts/cyc_telemetry.py}).
 */
public class VescFramingTest {

    @Test
    public void getValuesRequestHasExpectedBytes() {
        byte[] request = VescFraming.encodeShort(new byte[] { 0x04 });
        assertArrayEquals(new byte[] {
                (byte) 0x02, 0x01, 0x04, 0x40, (byte) 0x84, 0x03
        }, request);
    }

    @Test
    public void encodeDecodeRoundTripPreservesPayload() {
        byte[] payload = makeSyntheticTelemetry();
        byte[] frame = VescFraming.encodeShort(payload);
        // Sentinel + length + payload + CRC + end
        assertEquals(0x02, frame[0] & 0xFF);
        assertEquals(payload.length, frame[1] & 0xFF);
        assertEquals(0x03, frame[frame.length - 1] & 0xFF);
        byte[] extracted = VescFraming.extractPayload(frame);
        assertNotNull(extracted);
        assertArrayEquals(payload, extracted);
    }

    @Test
    public void extractToleratesLeadingJunkAndPartialFrames() {
        byte[] payload = makeSyntheticTelemetry();
        byte[] frame = VescFraming.encodeShort(payload);
        // Prepend 7 junk bytes; the parser should still find the start sentinel.
        byte[] withJunk = new byte[frame.length + 7];
        new Random(0xC1C0FFEE).nextBytes(withJunk);
        System.arraycopy(frame, 0, withJunk, 7, frame.length);
        // Overwrite the leading junk with non-sentinel bytes so the parser
        // can't get lucky and find a phantom 0x02.
        for (int i = 0; i < 7; i++) withJunk[i] = (byte) (0x10 + i);
        assertArrayEquals(payload, VescFraming.extractPayload(withJunk));
        // A buffer holding only a partial frame (no end sentinel) returns null.
        byte[] partial = new byte[frame.length - 3];
        System.arraycopy(frame, 0, partial, 0, partial.length);
        assertNull(VescFraming.extractPayload(partial));
    }

    @Test
    public void extractRejectsCrcMismatch() {
        byte[] payload = makeSyntheticTelemetry();
        byte[] frame = VescFraming.encodeShort(payload);
        // Corrupt the payload; the CRC check should fail and extract should
        // return null because no later valid frame exists in the buffer.
        frame[5] ^= 0x01;
        assertNull(VescFraming.extractPayload(frame));
    }

    /**
     * Builds a synthetic 87-byte telemetry payload (VESC {@code COMM_GET_VALUES}
     * response body) with values that map cleanly onto the cyc_uart layout:
     * controller temp = 25.0 C, motor temp = 30.0 C, input V = 48.0 V,
     * motor current = 5.00 A, input current = 3.25 A, speed = 12.34,
     * human power = 84 W, assist = 3. All other fields are zero. The body is
     * the 86 bytes that follow the leading VESC command byte (0x04).
     * Multi-byte fields are written big-endian, matching the wire format the
     * CYC controller actually emits (and the cygnus-bike reference decoder).
     */
    static byte[] makeSyntheticTelemetry() {
        byte[] body = new byte[86];
        // temp_fet_filtered: int16 BE, scale 10, value 25.0 C → 250
        writeInt16BE(body, 0, 250);
        // temp_motor_filtered: int16 BE, scale 10, value 30.0 C → 300
        writeInt16BE(body, 2, 300);
        // Input_V: int16 BE, scale 10, value 48.0 V → 480
        writeInt16BE(body, 26, 480);
        // reset_avg_motor_current: int32 BE, scale 100, value 5.00 A → 500
        writeInt32BE(body, 4, 500);
        // reset_avg_input_current: int32 BE, scale 100, value 3.25 A → 325
        writeInt32BE(body, 8, 325);
        // Speed: int32 BE, scale 100, value 12.34 → 1234
        writeInt32BE(body, 80, 1234);
        // Human Power: int32 BE, scale 1, value 84 W → 84
        writeInt32BE(body, 76, 84);
        // Assist Level: int8, scale 1, value 3
        body[85] = 3;
        return body;
    }

    private static void writeInt16BE(byte[] buf, int offset, int value) {
        buf[offset] = (byte) ((value >> 8) & 0xFF);
        buf[offset + 1] = (byte) (value & 0xFF);
    }

    private static void writeInt32BE(byte[] buf, int offset, int value) {
        buf[offset] = (byte) ((value >> 24) & 0xFF);
        buf[offset + 1] = (byte) ((value >> 16) & 0xFF);
        buf[offset + 2] = (byte) ((value >> 8) & 0xFF);
        buf[offset + 3] = (byte) (value & 0xFF);
    }
}
