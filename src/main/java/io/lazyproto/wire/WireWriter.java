package io.lazyproto.wire;

import java.nio.charset.StandardCharsets;

/**
 * High-performance Protobuf Wire Writer supporting raw span copying, in-place binary patching,
 * and branchless varint size calculation.
 */
public final class WireWriter {
    private byte[] out;
    private int pos;

    public WireWriter(byte[] out, int startPos) {
        this.out = out;
        this.pos = startPos;
    }

    public WireWriter reset(byte[] out, int startPos) {
        this.out = out;
        this.pos = startPos;
        return this;
    }

    public byte[] buffer() {
        return out;
    }

    public int position() {
        return pos;
    }

    /**
     * Copies a contiguous raw byte span (e.g. merged CLEAN_RAW fields from input buffer) in one bulk memcpy.
     */
    public void writeRawBytes(byte[] src, int srcOffset, int length) {
        System.arraycopy(src, srcOffset, out, pos, length);
        pos += length;
    }

    public void writeTag(int fieldNumber, int wireType) {
        writeVarint32(WireFormat.makeTag(fieldNumber, wireType));
    }

    public void writeVarint32(int value) {
        if (value >= 0) {
            while ((value & ~0x7F) != 0) {
                out[pos++] = (byte) ((value & 0x7F) | 0x80);
                value >>>= 7;
            }
            out[pos++] = (byte) value;
        } else {
            // Negative int32 is sign-extended to 10-byte varint64 in Protobuf wire format
            writeVarint64(value);
        }
    }

    public void writeVarint64(long value) {
        while ((value & ~0x7FL) != 0L) {
            out[pos++] = (byte) (((int) value & 0x7F) | 0x80);
            value >>>= 7;
        }
        out[pos++] = (byte) value;
    }

    public void writeZigZag32(int value) {
        writeVarint32((value << 1) ^ (value >> 31));
    }

    public void writeZigZag64(long value) {
        writeVarint64((value << 1) ^ (value >> 63));
    }

    public void writeFixed32(int value) {
        WireReader.INT_LE.set(out, pos, value);
        pos += 4;
    }

    public void writeFixed64(long value) {
        WireReader.LONG_LE.set(out, pos, value);
        pos += 8;
    }

    public void writeFloat(float value) {
        writeFixed32(Float.floatToRawIntBits(value));
    }

    public void writeDouble(double value) {
        writeFixed64(Double.doubleToRawLongBits(value));
    }

    public void writeBool(boolean value) {
        out[pos++] = (byte) (value ? 1 : 0);
    }

    public void writeLengthDelimitedBytes(int fieldNumber, byte[] bytes, int offset, int length) {
        writeTag(fieldNumber, WireFormat.WIRETYPE_LENGTH_DELIMITED);
        writeVarint32(length);
        writeRawBytes(bytes, offset, length);
    }

    public void writeStringField(int fieldNumber, String value) {
        byte[] utf8 = value.getBytes(StandardCharsets.UTF_8);
        writeLengthDelimitedBytes(fieldNumber, utf8, 0, utf8.length);
    }

    // ==================== In-Place Patching Helpers (Fast-Path 1) ====================

    public static void patchVarint32At(byte[] target, int pos, int value) {
        if (value >= 0) {
            while ((value & ~0x7F) != 0) {
                target[pos++] = (byte) ((value & 0x7F) | 0x80);
                value >>>= 7;
            }
            target[pos] = (byte) value;
        } else {
            patchVarint64At(target, pos, value);
        }
    }

    public static void patchVarint64At(byte[] target, int pos, long value) {
        while ((value & ~0x7FL) != 0L) {
            target[pos++] = (byte) (((int) value & 0x7F) | 0x80);
            value >>>= 7;
        }
        target[pos] = (byte) value;
    }

    public static void patchFixed32At(byte[] target, int pos, int value) {
        WireReader.INT_LE.set(target, pos, value);
    }

    public static void patchFixed64At(byte[] target, int pos, long value) {
        WireReader.LONG_LE.set(target, pos, value);
    }

    public static void patchBytesAt(byte[] target, int pos, byte[] src, int srcOff, int len) {
        System.arraycopy(src, srcOff, target, pos, len);
    }

    // ==================== Branchless Size Calculations ====================

    public static int tagSize(int fieldNumber) {
        return varint32Size(fieldNumber << 3);
    }

    public static int varint32Size(int value) {
        if (value < 0) return 10;
        // Exact branchless floor(log2(value | 1) / 7) + 1 for all 0..31: (log2 * 9 + 73) >>> 6
        int log2 = 31 - Integer.numberOfLeadingZeros(value | 1);
        return (log2 * 9 + 73) >>> 6;
    }

    public static int varint64Size(long value) {
        // Exact branchless floor(log2(value | 1) / 7) + 1 for all 0..63: (log2 * 9 + 73) >>> 6
        int log2 = 63 - Long.numberOfLeadingZeros(value | 1L);
        return (log2 * 9 + 73) >>> 6;
    }
}
