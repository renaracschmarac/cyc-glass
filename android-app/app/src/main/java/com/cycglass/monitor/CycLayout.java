package com.cycglass.monitor;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Loads and indexes the CYC UART telemetry layout JSON (the same shape as
 * {@code data/cyc_uart.json} in the project root, also bundled as an Android
 * asset). The layout is the byte map used to decode the response to VESC
 * {@code COMM_GET_VALUES} ({@code 0x04}) sent over Nordic UART Service.
 *
 * <p>Layout entries describe a single field at a fixed offset, length, scale,
 * and signedness. The decoder uses these to extract individual telemetry
 * values from the 86-byte payload returned by the CYC X1 Pro Gen4 / X12
 * controller.
 *
 * <p>This class is pure: it has no Android dependencies beyond
 * {@link JSONObject} and {@link JSONArray}, and reads the JSON via
 * {@link InputStream} so it can be loaded from either the assets directory or
 * a test resource.
 */
public final class CycLayout {

    public enum ValueType {
        INT,
        UINT
    }

    public static final class Field {
        public final String key;
        public final int offset;
        public final int length;
        public final double scale;
        public final ValueType type;

        public Field(String key, int offset, int length, double scale, ValueType type) {
            this.key = key;
            this.offset = offset;
            this.length = length;
            this.scale = scale;
            this.type = type;
        }

        /**
         * Decodes this field from the given payload buffer, treating the buffer
         * as the byte stream that follows the VESC command byte.
         */
        public double decode(byte[] payload) {
            return decode(payload, 0);
        }

        /**
         * Decodes this field from the given payload buffer at the given
         * additional byte offset. The buffer is the telemetry payload that
         * follows the VESC command byte; no further adjustment is made.
         */
        public double decode(byte[] payload, int extraOffset) {
            int start = offset + extraOffset;
            if (start < 0 || start + length > payload.length) {
                return Double.NaN;
            }
            long raw = readRaw(payload, start, length, type);
            if (type == ValueType.UINT) {
                return raw / scale;
            }
            return signExtend(raw, length * 8) / scale;
        }
    }

    private final List<Field> fields;
    private final int totalBytes;

    public CycLayout(List<Field> fields) {
        this.fields = Collections.unmodifiableList(new ArrayList<>(fields));
        int max = 0;
        for (Field f : fields) {
            int end = f.offset + f.length;
            if (end > max) {
                max = end;
            }
        }
        this.totalBytes = max;
    }

    public List<Field> fields() {
        return fields;
    }

    public int totalBytes() {
        return totalBytes;
    }

    public Field field(String key) {
        for (Field f : fields) {
            if (f.key.equals(key)) {
                return f;
            }
        }
        return null;
    }

    /** Reads a JSON layout from an input stream. */
    public static CycLayout fromJson(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder();
        BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        String line;
        while ((line = reader.readLine()) != null) {
            sb.append(line);
        }
        return fromJsonString(sb.toString());
    }

    /** Parses a JSON layout from a string. */
    public static CycLayout fromJsonString(String json) throws IOException {
        try {
            JSONArray array = new JSONArray(json);
            List<Field> fields = new ArrayList<>(array.length());
            for (int i = 0; i < array.length(); i++) {
                JSONObject obj = array.getJSONObject(i);
                String key = obj.getString("key");
                int offset = obj.getInt("offset");
                int length = obj.getInt("length");
                double scale = obj.getDouble("scale");
                ValueType type = "uint".equals(obj.getString("type"))
                        ? ValueType.UINT
                        : ValueType.INT;
                fields.add(new Field(key, offset, length, scale, type));
            }
            return new CycLayout(fields);
        } catch (JSONException e) {
            throw new IOException("Failed to parse CYC layout JSON", e);
        }
    }

    private static long readRaw(byte[] payload, int start, int length, ValueType type) {
        long value = 0;
        // Little-endian read.
        for (int i = 0; i < length; i++) {
            value |= ((long) (payload[start + i] & 0xFF)) << (8 * i);
        }
        return value;
    }

    private static long signExtend(long value, int bits) {
        long mask = 1L << (bits - 1);
        return (value ^ mask) - mask;
    }
}
