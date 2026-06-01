package com.cycglass.monitor;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Round-trips a synthetic 87-byte {@code COMM_GET_VALUES} response through
 * VESC framing + the bundled {@code cyc_uart.json} layout and asserts every
 * field the app cares about decodes to the expected physical value.
 *
 * <p>Source of truth: the {@code data/cyc_uart.json} file in the project
 * root. If this test ever fails after a layout change, the change to the
 * JSON is almost certainly the cause.
 */
public class CycLayoutTest {

    private CycLayout layout;

    @Before
    public void setUp() throws IOException {
        String json = new String(java.nio.file.Files.readAllBytes(
                java.nio.file.Paths.get("src", "main", "assets", "cyc_uart.json")),
                StandardCharsets.UTF_8);
        layout = CycLayout.fromJsonString(json);
    }

    @Test
    public void layoutParsesAllFields() {
        assertTrue("Layout should have at least 20 fields", layout.fields().size() >= 20);
        assertEquals(86, layout.totalBytes());
    }

    @Test
    public void decodesSyntheticTelemetry() throws IOException {
        byte[] body = VescFramingTest.makeSyntheticTelemetry();
        // The 87-byte VESC response is: 0x04 (command) + 86-byte body.
        byte[] payload = new byte[body.length + 1];
        payload[0] = 0x04;
        System.arraycopy(body, 0, payload, 1, body.length);

        // Frame the payload and extract it back, to prove the full chain.
        byte[] frame = VescFraming.encodeShort(payload);
        byte[] extracted = VescFraming.extractPayload(frame);
        assertNotNull(extracted);
        assertEquals(87, extracted.length);

        // Strip the VESC command byte, then decode the body with the layout.
        byte[] telemetry = new byte[extracted.length - 1];
        System.arraycopy(extracted, 1, telemetry, 0, telemetry.length);

        assertEquals(25.0, layout.field("temp_fet_filtered").decode(telemetry), 1e-9);
        assertEquals(30.0, layout.field("temp_motor_filtered").decode(telemetry), 1e-9);
        assertEquals(48.0, layout.field("Input_V").decode(telemetry), 1e-9);
        assertEquals(5.00, layout.field("reset_avg_motor_current").decode(telemetry), 1e-9);
        assertEquals(12.34, layout.field("Speed").decode(telemetry), 1e-9);
        assertEquals(84.0, layout.field("Human Power").decode(telemetry), 1e-9);
        assertEquals(3.0, layout.field("Assist Level").decode(telemetry), 1e-9);

        // Derived motor power: 48 V * 5 A = 240 W.
        assertEquals(240.0,
                layout.field("Input_V").decode(telemetry)
                        * layout.field("reset_avg_motor_current").decode(telemetry),
                1e-9);
    }

    @Test
    public void outOfRangeReturnsNaN() {
        byte[] tiny = new byte[10];
        // temp_fet_filtered is at offset 0 with length 2; we only provide 10
        // bytes, so the field is in range. Use a length-zero buffer to make
        // every field out of range.
        byte[] empty = new byte[0];
        assertTrue(Double.isNaN(layout.field("Assist Level").decode(empty)));
    }

    @Test
    public void fieldsAreBigEndian() throws IOException {
        // Regression test for the CYC wire format: every multi-byte field in
        // the COMM_GET_VALUES response is big-endian (network order). The
        // cygnus-bike reference decoder (scripts/cyc_telemetry.py) confirmed
        // this against the live CYC Ride Control app. Reading little-endian
        // produces physically impossible values (e.g. controller temp of
        // -982 °F when the wire bytes decode to a sensible ambient value
        // under BE). This test pins the byte order so it cannot drift back.
        //
        // The wire bytes below are the canonical "26.5 °C controller temp,
        // 23.5 °C motor temp" capture from the cygnus-bike memory note of
        // 2026-05-28. The first two bytes are 0x01 0x09 (265 big-endian →
        // 26.5 °C), the next two are 0x00 0xEB (235 big-endian → 23.5 °C).
        byte[] body = new byte[] {
                0x01, 0x09,   // temp_fet_filtered = 265 → 26.5 °C
                0x00, (byte) 0xEB,  // temp_motor_filtered = 235 → 23.5 °C
                0, 0, 0, 0, 0, 0, 0, 0,
                0, 0, 0, 0, 0, 0, 0, 0,
                0, 0, 0, 0, 0, 0, 0, 0,
                0, 0, 0, 0, 0, 0, 0, 0,
                0, 0, 0, 0, 0, 0, 0, 0,
                0, 0, 0, 0, 0, 0, 0, 0,
                0, 0, 0, 0, 0, 0, 0, 0,
                0, 0, 0, 0, 0, 0, 0, 0,
                0, 0, 0, 0, 0, 0, 0, 0,
                0, 0, 0, 0, 0, 0, 0, 0,
                0, 0
        };
        assertEquals(26.5, layout.field("temp_fet_filtered").decode(body), 1e-9);
        assertEquals(23.5, layout.field("temp_motor_filtered").decode(body), 1e-9);
        // Cross-check the ASCII: under big-endian these wire bytes give a
        // reasonable ambient/motor temp; under little-endian they would give
        // (0x0901 = 2305 → 230.5 °C → 446.9 °F) and (0xEB00 = -5376 →
        // -537.6 °C → -935.7 °F) — both physically impossible, exactly the
        // symptom the user reported in the wild.
    }

    @Test
    public void layoutJsonRoundTripsFromInputStream() throws IOException {
        String json = new String(java.nio.file.Files.readAllBytes(
                java.nio.file.Paths.get("src", "main", "assets", "cyc_uart.json")),
                StandardCharsets.UTF_8);
        CycLayout fromStream;
        try (InputStream in = new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8))) {
            fromStream = CycLayout.fromJson(in);
        }
        assertEquals(layout.fields().size(), fromStream.fields().size());
        assertEquals(layout.totalBytes(), fromStream.totalBytes());
        assertEquals(layout.field("Assist Level").offset,
                fromStream.field("Assist Level").offset);
    }
}
