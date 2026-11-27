package io.lazyproto.wire;

/**
 * Zero-copy UTF-8 slice view over a raw byte array, implementing {@link CharSequence}
 * so callers can inspect or compare string fields without allocating {@link String}.
 */
public final class Utf8Slice implements CharSequence {
    private byte[] buf;
    private int offset;
    private int length;

    public Utf8Slice() {}

    public Utf8Slice(byte[] buf, int offset, int length) {
        this.buf = buf;
        this.offset = offset;
        this.length = length;
    }

    public Utf8Slice wrap(byte[] buf, int offset, int length) {
        this.buf = buf;
        this.offset = offset;
        this.length = length;
        return this;
    }

    public byte[] array() {
        return buf;
    }

    public int offset() {
        return offset;
    }

    public int byteLength() {
        return length;
    }

    @Override
    public int length() {
        return length;
    }

    @Override
    public char charAt(int index) {
        if (index < 0 || index >= length) {
            throw new IndexOutOfBoundsException(index);
        }
        return (char) (buf[offset + index] & 0xFF);
    }

    @Override
    public CharSequence subSequence(int start, int end) {
        if (start < 0 || end > length || start > end) {
            throw new IndexOutOfBoundsException();
        }
        return new Utf8Slice(buf, offset + start, end - start);
    }

    /**
     * Zero-allocation ASCII equality check against a Java String.
     */
    public boolean contentEqualsAscii(String ascii) {
        if (ascii == null || ascii.length() != length) {
            return false;
        }
        for (int i = 0; i < length; i++) {
            if ((buf[offset + i] & 0xFF) != ascii.charAt(i)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Zero-allocation byte-exact equality check against pre-encoded UTF-8 bytes.
     */
    public boolean contentEqualsUtf8(byte[] expectedUtf8) {
        if (expectedUtf8 == null || expectedUtf8.length != length) {
            return false;
        }
        return java.util.Arrays.equals(
                buf, offset, offset + length,
                expectedUtf8, 0, expectedUtf8.length);
    }

    @Override
    public String toString() {
        if (buf == null || length == 0) {
            return "";
        }
        return WireReader.readStringAt(buf, offset, length);
    }
}
