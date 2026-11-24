package io.lazyproto.wire;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/**
 * High-performance SWAR (SIMD Within A Register) and VarHandle-accelerated Protobuf Wire Reader.
 */
public final class WireReader {
    public static final VarHandle LONG_LE =
            MethodHandles.byteArrayViewVarHandle(long[].class, ByteOrder.LITTLE_ENDIAN);
    public static final VarHandle INT_LE =
            MethodHandles.byteArrayViewVarHandle(int[].class, ByteOrder.LITTLE_ENDIAN);

    private static final long MSB_MASK_64 = 0x8080808080808080L;

    private WireReader() {}

    /**
     * Skips a varint starting at {@code pos} using 64-bit SWAR unaligned load + TZCNT instruction.
     * Returns the index of the first byte after the varint.
     */
    public static int skipVarintSWAR(byte[] buf, int pos, int limit) {
        // Fast path: 1-byte varint (most common for tags, bools, small enums/ints)
        if (pos < limit && buf[pos] >= 0) {
            return pos + 1;
        }
        // SWAR 64-bit register parallel scan when at least 8 bytes remain in buffer
        if (pos + 8 <= buf.length) {
            long word = (long) LONG_LE.get(buf, pos);
            long termMask = ~word & MSB_MASK_64;
            if (termMask != 0L) {
                int len = (Long.numberOfTrailingZeros(termMask) >>> 3) + 1;
                int next = pos + len;
                if (next <= limit) {
                    return next;
                }
            }
        }
        // Fallback for 9-10 byte varints or tail < 8 bytes near end of array
        return skipVarintSlow(buf, pos, limit);
    }

    private static int skipVarintSlow(byte[] buf, int pos, int limit) {
        int max = Math.min(limit, pos + 10);
        while (pos < max) {
            if (buf[pos++] >= 0) {
                return pos;
            }
        }
        throw new IllegalArgumentException("Malformed or truncated varint at offset " + pos);
    }

    /**
     * Reads a 32-bit varint at {@code pos} and returns the decoded value.
     * Use {@link #skipVarintSWAR} or {@link #readVarint32WithEnd} when advancing a cursor.
     */
    public static int readVarint32At(byte[] buf, int pos) {
        int b = buf[pos++];
        if (b >= 0) return b;
        int result = b & 0x7F;
        if ((b = buf[pos++]) >= 0) return result | (b << 7);
        result |= (b & 0x7F) << 7;
        if ((b = buf[pos++]) >= 0) return result | (b << 14);
        result |= (b & 0x7F) << 14;
        if ((b = buf[pos++]) >= 0) return result | (b << 21);
        result |= (b & 0x7F) << 21;
        b = buf[pos];
        result |= b << 28;
        return result;
    }

    /**
     * Reads a 32-bit varint (such as tag or length) and packs {@code (nextPos << 32) | (value & 0xFFFFFFFFL)}.
     */
    public static long readVarint32WithCursor(byte[] buf, int pos, int limit) {
        int b = buf[pos++];
        if (b >= 0) {
            return ((long) pos << 32) | (b & 0xFFFFFFFFL);
        }
        int result = b & 0x7F;
        if ((b = buf[pos++]) >= 0) {
            result |= b << 7;
            return ((long) pos << 32) | (result & 0xFFFFFFFFL);
        }
        result |= (b & 0x7F) << 7;
        if ((b = buf[pos++]) >= 0) {
            result |= b << 14;
            return ((long) pos << 32) | (result & 0xFFFFFFFFL);
        }
        result |= (b & 0x7F) << 14;
        if ((b = buf[pos++]) >= 0) {
            result |= b << 21;
            return ((long) pos << 32) | (result & 0xFFFFFFFFL);
        }
        result |= (b & 0x7F) << 21;
        b = buf[pos++];
        result |= b << 28;
        if (b < 0) {
            // Negative int32 wire representation uses 10 bytes (sign-extended to 64 bits)
            for (int i = 0; i < 5; i++) {
                if (pos >= limit) {
                    throw new IllegalArgumentException("Truncated varint32");
                }
                if (buf[pos++] >= 0) {
                    return ((long) pos << 32) | (result & 0xFFFFFFFFL);
                }
            }
            throw new IllegalArgumentException("Malformed varint32");
        }
        return ((long) pos << 32) | (result & 0xFFFFFFFFL);
    }

    /**
     * Reads a 64-bit varint at {@code pos}.
     */
    public static long readVarint64At(byte[] buf, int pos) {
        long b = buf[pos++];
        if (b >= 0) return b;
        long result = b & 0x7FL;
        for (int shift = 7; shift < 64; shift += 7) {
            b = buf[pos++];
            result |= (b & 0x7FL) << shift;
            if (b >= 0) {
                return result;
            }
        }
        throw new IllegalArgumentException("Malformed varint64");
    }

    public static int readZigZag32At(byte[] buf, int pos) {
        int n = readVarint32At(buf, pos);
        return (n >>> 1) ^ -(n & 1);
    }

    public static long readZigZag64At(byte[] buf, int pos) {
        long n = readVarint64At(buf, pos);
        return (n >>> 1) ^ -(n & 1L);
    }

    public static int readFixed32At(byte[] buf, int pos) {
        return (int) INT_LE.get(buf, pos);
    }

    public static long readFixed64At(byte[] buf, int pos) {
        return (long) LONG_LE.get(buf, pos);
    }

    public static float readFloatAt(byte[] buf, int pos) {
        return Float.intBitsToFloat((int) INT_LE.get(buf, pos));
    }

    public static double readDoubleAt(byte[] buf, int pos) {
        return Double.longBitsToDouble((long) LONG_LE.get(buf, pos));
    }

    public static boolean readBoolAt(byte[] buf, int pos) {
        return buf[pos] != 0;
    }

    @SuppressWarnings("deprecation")
    public static String readStringAt(byte[] buf, int pos, int len) {
        if (len == 0) return "";
        // SWAR fast-path check: if all bytes are 7-bit ASCII, use fast ISO-8859-1 / ASCII Latin1 construction
        if (isAsciiSWAR(buf, pos, len)) {
            return new String(buf, 0, pos, len);
        }
        return new String(buf, pos, len, StandardCharsets.UTF_8);
    }

    public static boolean isAsciiSWAR(byte[] buf, int pos, int len) {
        int i = 0;
        int limit8 = len - 7;
        for (; i < limit8; i += 8) {
            long word = (long) LONG_LE.get(buf, pos + i);
            if ((word & MSB_MASK_64) != 0L) {
                return false;
            }
        }
        for (; i < len; i++) {
            if (buf[pos + i] < 0) {
                return false;
            }
        }
        return true;
    }
}
