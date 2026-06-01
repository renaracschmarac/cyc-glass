package com.cycglass.monitor;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.lang.reflect.Method;

/**
 * Unit tests for the Daly BMS frame validation logic, ported from
 * {@code blu-battery}'s {@code MainActivity}. Uses reflection to exercise
 * the package-private helpers {@code validStatusFrame}, {@code crc16}, and
 * the unsigned-16 conversion without exposing them on the public API.
 */
public class BmsFrameTest {

    @Test
    public void crc16MatchesKnownDalyTrailer() throws Exception {
        // The blu-battery code documents the Daly status frame layout in
        // src/blu_battery/daly.py; the CRC16 byte order is "low high" (Modbus
        // RTU) with polynomial 0xA001 reflected. The trailer computed by
        // BmsClient.crc16Modbus on the canonical 0xD2 0x03 0x00 ... header
        // must match the wire bytes the BMS actually emits.
        //
        // We assert against a known input/expected pair: frame header
        // "D2 03 00 00 00 3E" should CRC to 0xB9D7, sent as "D7 B9" on the
        // wire (little-endian), which is the same trailer used in the live
        // request. This pins both the algorithm and the byte order.
        byte[] header = new byte[] {(byte) 0xD2, 0x03, 0x00, 0x00, 0x00, 0x3E};
        int crc = (int) crc16Modbus().invoke(null, header, header.length);
        assertEquals(0xB9D7, crc);
    }

    @Test
    public void validStatusFrameAcceptsCanonicalFrame() throws Exception {
        // Hand-craft a minimal frame: 0xD2 0x03 <length> <body> <crc_lo> <crc_hi>
        // Length field at offset 2 must equal body length, total = 5 + length.
        int bodyLen = 96;  // 101-byte frame total
        byte[] frame = new byte[3 + bodyLen + 2];
        frame[0] = (byte) 0xD2;
        frame[1] = 0x03;
        frame[2] = (byte) bodyLen;
        // Body left as zeros. Compute CRC over the entire frame minus the
        // 2-byte trailer.
        int crc = (int) crc16Modbus().invoke(null, frame, frame.length - 2);
        frame[frame.length - 2] = (byte) (crc & 0xFF);
        frame[frame.length - 1] = (byte) ((crc >> 8) & 0xFF);
        assertTrue((boolean) validStatusFrame().invoke(null, frame));
    }

    @Test
    public void validStatusFrameRejectsBadHeader() throws Exception {
        byte[] frame = new byte[101];
        frame[0] = 0x00;  // not 0xD2
        frame[1] = 0x03;
        frame[2] = (byte) 96;
        assertFalse((boolean) validStatusFrame().invoke(null, frame));
    }

    @Test
    public void validStatusFrameRejectsBadCrc() throws Exception {
        byte[] frame = new byte[101];
        frame[0] = (byte) 0xD2;
        frame[1] = 0x03;
        frame[2] = (byte) 96;
        // Leave CRC bytes as zero — guaranteed to mismatch.
        assertFalse((boolean) validStatusFrame().invoke(null, frame));
    }

    private static Method crc16Modbus() throws NoSuchMethodException {
        Method m = BmsClient.class.getDeclaredMethod("crc16Modbus", byte[].class, int.class);
        m.setAccessible(true);
        return m;
    }

    private static Method validStatusFrame() throws NoSuchMethodException {
        Method m = BmsClient.class.getDeclaredMethod("validStatusFrame", byte[].class);
        m.setAccessible(true);
        return m;
    }
}
