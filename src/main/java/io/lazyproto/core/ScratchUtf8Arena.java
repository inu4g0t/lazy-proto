package io.lazyproto.core;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Reusable contiguous UTF-8 byte arena for Java lower-level wrappers.
 *
 * <p>Avoids {@code String.getBytes(UTF_8)} per-call heap allocations on ASCII strings by encoding
 * directly into a reusable {@code byte[]} buffer, storing {@code (offset, length)} packed in a {@code long}
 * so arena growth never invalidates previously stored slices.
 */
public final class ScratchUtf8Arena {
    private byte[] buf;
    private int used;

    public ScratchUtf8Arena(int initialCapacity) {
        this.buf = new byte[Math.max(64, initialCapacity)];
    }

    public void reset() {
        this.used = 0;
    }

    public byte[] buffer() {
        return buf;
    }

    /**
     * Encodes {@code s} into the arena and returns {@code ((long) offset << 32) | (len & 0xFFFFFFFFL)}.
     */
    public long storeString(String s) {
        int strLen = s.length();
        if (strLen == 0) {
            return 0L;
        }
        int start = this.used;
        ensureCapacity(start + strLen);
        for (int i = 0; i < strLen; i++) {
            char c = s.charAt(i);
            if (c < 0x80) {
                buf[start + i] = (byte) c;
            } else {
                byte[] utf8 = s.getBytes(StandardCharsets.UTF_8);
                return storeBytes(utf8, 0, utf8.length);
            }
        }
        this.used = start + strLen;
        return ((long) start << 32) | (strLen & 0xFFFFFFFFL);
    }

    public long storeBytes(byte[] src, int srcOff, int len) {
        if (len == 0) {
            return 0L;
        }
        int start = this.used;
        ensureCapacity(start + len);
        System.arraycopy(src, srcOff, buf, start, len);
        this.used = start + len;
        return ((long) start << 32) | (len & 0xFFFFFFFFL);
    }

    public static int unpackOffset(long handle) {
        return (int) (handle >>> 32);
    }

    public static int unpackLength(long handle) {
        return (int) handle;
    }

    private void ensureCapacity(int needed) {
        if (needed > buf.length) {
            int newCap = Math.max(needed, buf.length << 1);
            buf = Arrays.copyOf(buf, newCap);
        }
    }
}
