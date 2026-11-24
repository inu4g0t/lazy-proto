package io.lazyproto.runtime;

import com.google.protobuf.ByteOutput;
import com.google.protobuf.ByteString;
import com.google.protobuf.UnsafeByteOperations;
import io.lazyproto.wire.WireFormat;
import io.lazyproto.wire.WireReader;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Arrays;

/**
 * Immutable wire-slice base with lazily populated, read-only 128-bit {@code FieldTape}.
 *
 * <p>Architecture v3 core invariant: once scanned (or frozen), an {@code ImmutableWireBase} is
 * 100% read-only, contains zero mutation flags or parent back-pointers, and can be shared safely
 * across arbitrary built {@link AbstractLazyMessage} snapshots, forked builders, and threads.
 *
 * <p>128-bit TapeEntry layout (2 {@code long} words per wire record):
 * <ul>
 *   <li>{@code tape[i * 2]}: {@code [63..32] tagOffset (uint32) | [31..0] recordEnd (uint32)}</li>
 *   <li>{@code tape[i * 2 + 1]}: {@code [63..35] fieldNumber (29-bit) | [34..32] wireType (3-bit)
 *       | [31..28] kind (4-bit) | [27..24] headerLen (4-bit) | [23..0] auxIndex (24-bit)}</li>
 * </ul>
 */
public abstract class ImmutableWireBase {

    // Immutable Wire Record Kinds (4-bit)
    public static final int KIND_UNKNOWN        = 0;
    public static final int KIND_SHADOWED       = 1;
    public static final int KIND_SCALAR_SLOT    = 2;
    public static final int KIND_SPLIT_SUB_CONT = 3;
    public static final int KIND_SPLIT_SUB_TAIL = 4;
    public static final int KIND_REPEATED_ITEM  = 5;

    public static final int NO_AUX_IDX = 0xFFFFFF;
    public static final byte[] EMPTY_BYTES = new byte[0];
    protected static final long[] EMPTY_TAPE = new long[0];

    protected byte[] buf = EMPTY_BYTES;
    protected int rawOffset;
    protected int rawLength;

    private final int initialTapeWords;
    protected long[] tape = EMPTY_TAPE;
    protected int tapeCount;

    protected volatile boolean scanned;
    protected boolean multiSegment;
    protected boolean hasShadowedEntries;
    protected volatile boolean frozen;

    protected ImmutableWireBase(int initialTapeEntries) {
        this.initialTapeWords = Math.max(8, initialTapeEntries << 1);
    }

    public final void freeze() {
        if (!frozen) {
            this.frozen = true;
            onFreeze();
        }
    }

    protected void onFreeze() {}

    public final boolean isFrozen() {
        return frozen;
    }

    public final boolean isScanned() {
        return scanned;
    }

    public final boolean isMultiSegment() {
        return multiSegment;
    }

    public final boolean hasShadowedEntries() {
        return hasShadowedEntries;
    }

    public final byte[] rawBuffer() {
        return buf;
    }

    public final int rawOffset() {
        return rawOffset;
    }

    public final int rawLength() {
        return rawLength;
    }

    public final void markScannedEmpty() {
        this.scanned = true;
    }

    public final void wrapImmutable(byte[] buffer, int offset, int length) {
        if (frozen) {
            throw new IllegalStateException("Cannot re-wrap a frozen ImmutableWireBase");
        }
        resetForReuse(buffer, offset, length);
        this.frozen = true;
    }

    public final void wrapMultiSegments(byte[] buffer, long[] parentTape, int[] segTapeIndices, int segCount) {
        if (frozen) {
            throw new IllegalStateException("Cannot re-wrap a frozen ImmutableWireBase");
        }
        resetBaseState();
        this.buf = buffer;
        this.multiSegment = true;
        int totalLen = 0;
        for (int i = 0; i < segCount; i++) {
            int segIdx = segTapeIndices[i];
            int headerLen = (int) ((parentTape[(segIdx << 1) + 1] >>> 24) & 0xFL);
            int valOff = (int) (parentTape[segIdx << 1] >>> 32) + headerLen;
            int recEnd = (int) parentTape[segIdx << 1];
            totalLen += (recEnd - valOff);
            scanRange(buffer, valOff, recEnd);
        }
        int firstSeg = segTapeIndices[0];
        int firstHeader = (int) ((parentTape[(firstSeg << 1) + 1] >>> 24) & 0xFL);
        this.rawOffset = (int) (parentTape[firstSeg << 1] >>> 32) + firstHeader;
        this.rawLength = totalLen;
        this.scanned = true;
        this.frozen = true;
    }

    public final void resetForReuse(byte[] buffer, int offset, int length) {
        if (frozen) {
            throw new IllegalStateException("Cannot resetForReuse a frozen ImmutableWireBase");
        }
        resetBaseState();
        this.buf = buffer != null ? buffer : EMPTY_BYTES;
        this.rawOffset = offset;
        this.rawLength = length;
    }

    public final void resetForReuse(ByteString byteString) {
        if (frozen) {
            throw new IllegalStateException("Cannot resetForReuse a frozen ImmutableWireBase");
        }
        final byte[][] holder = new byte[1][];
        final int[] range = new int[2];
        try {
            UnsafeByteOperations.unsafeWriteTo(byteString, new ByteOutput() {
                @Override
                public void write(byte value) {
                    throw new UnsupportedOperationException();
                }

                @Override
                public void write(byte[] value, int offset, int len) {
                    if (holder[0] == null) {
                        holder[0] = value;
                        range[0] = offset;
                        range[1] = len;
                    } else {
                        byte[] flat = byteString.toByteArray();
                        holder[0] = flat;
                        range[0] = 0;
                        range[1] = flat.length;
                    }
                }

                @Override
                public void writeLazy(byte[] value, int offset, int len) {
                    write(value, offset, len);
                }

                @Override
                public void write(ByteBuffer value) {
                    byte[] flat = byteString.toByteArray();
                    holder[0] = flat;
                    range[0] = 0;
                    range[1] = flat.length;
                }

                @Override
                public void writeLazy(ByteBuffer value) {
                    write(value);
                }
            });
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        if (holder[0] == null) {
            resetForReuse(EMPTY_BYTES, 0, 0);
        } else {
            resetForReuse(holder[0], range[0], range[1]);
        }
    }

    private void resetBaseState() {
        this.buf = EMPTY_BYTES;
        this.rawOffset = 0;
        this.rawLength = 0;
        this.tapeCount = 0;
        this.scanned = false;
        this.multiSegment = false;
        this.hasShadowedEntries = false;
        resetBaseSlots();
    }

    protected abstract void resetBaseSlots();

    // ==================== Tier 1: Thread-Safe Lazy Shallow Scanner ====================

    public final void ensureScanned() {
        if (!scanned) {
            if (frozen) {
                synchronized (this) {
                    if (!scanned) {
                        if (rawLength > 0) {
                            scanRange(this.buf, this.rawOffset, this.rawOffset + this.rawLength);
                        }
                        this.scanned = true;
                    }
                }
            } else {
                if (rawLength > 0) {
                    scanRange(this.buf, this.rawOffset, this.rawOffset + this.rawLength);
                }
                this.scanned = true;
            }
        }
    }

    protected final void scanRange(byte[] b, int cursor, int limit) {
        while (cursor < limit) {
            final int tagOffset = cursor;
            final int tag;
            final byte firstByte = b[cursor];
            if (firstByte >= 0) {
                tag = firstByte;
                cursor++;
            } else {
                long tagAndCursor = WireReader.readVarint32WithCursor(b, cursor, limit);
                tag = (int) tagAndCursor;
                cursor = (int) (tagAndCursor >>> 32);
            }

            final int fieldNumber = tag >>> 3;
            final int wireType = tag & 7;
            if (fieldNumber == 0) {
                throw new IllegalArgumentException("Invalid protobuf tag with fieldNumber 0 at offset " + tagOffset);
            }

            final int valOffset;
            final int recordEnd;

            switch (wireType) {
                case WireFormat.WIRETYPE_VARINT:
                    valOffset = cursor;
                    cursor = WireReader.skipVarintSWAR(b, cursor, limit);
                    recordEnd = cursor;
                    break;
                case WireFormat.WIRETYPE_FIXED64:
                    valOffset = cursor;
                    cursor += 8;
                    if (cursor > limit) throw new IllegalArgumentException("Truncated FIXED64 field");
                    recordEnd = cursor;
                    break;
                case WireFormat.WIRETYPE_LENGTH_DELIMITED:
                    final int len;
                    final byte lenByte = b[cursor];
                    if (lenByte >= 0) {
                        len = lenByte;
                        cursor++;
                    } else {
                        long lenAndCursor = WireReader.readVarint32WithCursor(b, cursor, limit);
                        len = (int) lenAndCursor;
                        cursor = (int) (lenAndCursor >>> 32);
                    }
                    if (len < 0 || cursor + len > limit) {
                        throw new IllegalArgumentException("Invalid LENGTH_DELIMITED len=" + len + " at " + cursor);
                    }
                    valOffset = cursor;
                    cursor += len;
                    recordEnd = cursor;
                    break;
                case WireFormat.WIRETYPE_FIXED32:
                    valOffset = cursor;
                    cursor += 4;
                    if (cursor > limit) throw new IllegalArgumentException("Truncated FIXED32 field");
                    recordEnd = cursor;
                    break;
                default:
                    throw new IllegalArgumentException("Unsupported wireType " + wireType + " at offset " + tagOffset);
            }

            final int headerLen = valOffset - tagOffset;
            final int tapeIdx = appendTapeEntry(fieldNumber, wireType, tagOffset, recordEnd, headerLen);
            onScanField(fieldNumber, wireType, tapeIdx);
        }
    }

    protected final int appendTapeEntry(int fieldNumber, int wireType, int tagOffset, int recordEnd, int headerLen) {
        int idx = this.tapeCount;
        int wordIdx = idx << 1;
        if (this.tape == EMPTY_TAPE) {
            this.tape = new long[this.initialTapeWords];
        } else if (wordIdx + 1 >= this.tape.length) {
            this.tape = Arrays.copyOf(this.tape, this.tape.length << 1);
        }
        long word0 = ((long) tagOffset << 32) | (recordEnd & 0xFFFFFFFFL);
        long word1 = ((long) fieldNumber << 35)
                | ((long) (wireType & 0x7) << 32)
                | ((long) KIND_UNKNOWN << 28)
                | ((long) (headerLen & 0xF) << 24)
                | NO_AUX_IDX;
        this.tape[wordIdx] = word0;
        this.tape[wordIdx + 1] = word1;
        this.tapeCount = idx + 1;
        return idx;
    }

    protected abstract void onScanField(int fieldNumber, int wireType, int tapeIdx);

    // ==================== FieldTape Bit-Unpacking Accessors ====================

    public final int tapeCount() { return tapeCount; }
    public final int tapeTagOffset(int tapeIdx) { return (int) (tape[tapeIdx << 1] >>> 32); }
    public final int tapeRecordEnd(int tapeIdx) { return (int) tape[tapeIdx << 1]; }
    public final int tapeRecordLength(int tapeIdx) { long w0 = tape[tapeIdx << 1]; return (int) w0 - (int) (w0 >>> 32); }
    public final int tapeHeaderLength(int tapeIdx) { return (int) ((tape[(tapeIdx << 1) + 1] >>> 24) & 0xFL); }
    public final int tapeValueOffset(int tapeIdx) {
        long w0 = tape[tapeIdx << 1];
        long w1 = tape[(tapeIdx << 1) + 1];
        return (int) (w0 >>> 32) + (int) ((w1 >>> 24) & 0xFL);
    }
    public final int tapeValueLength(int tapeIdx) {
        long w0 = tape[tapeIdx << 1];
        long w1 = tape[(tapeIdx << 1) + 1];
        return (int) w0 - ((int) (w0 >>> 32) + (int) ((w1 >>> 24) & 0xFL));
    }
    public final int tapeFieldNumber(int tapeIdx) { return (int) (tape[(tapeIdx << 1) + 1] >>> 35); }
    public final int tapeWireType(int tapeIdx) { return (int) ((tape[(tapeIdx << 1) + 1] >>> 32) & 0x7L); }
    public final int tapeKind(int tapeIdx) { return (int) ((tape[(tapeIdx << 1) + 1] >>> 28) & 0xFL); }
    public final int tapeAuxIndex(int tapeIdx) { return (int) (tape[(tapeIdx << 1) + 1] & 0xFFFFFFL); }

    protected final void setTapeKindAndAux(int tapeIdx, int kind, int auxIndex) {
        int w1Idx = (tapeIdx << 1) + 1;
        tape[w1Idx] = (tape[w1Idx] & ~((0xFL << 28) | 0xFFFFFFL))
                | ((long) (kind & 0xF) << 28)
                | (auxIndex & 0xFFFFFFL);
    }

    protected final void setTapeKind(int tapeIdx, int kind) {
        int w1Idx = (tapeIdx << 1) + 1;
        tape[w1Idx] = (tape[w1Idx] & ~(0xFL << 28)) | ((long) (kind & 0xF) << 28);
    }
}
