package io.lazyproto.core;

import io.lazyproto.wire.WireFormat;
import io.lazyproto.wire.WireReader;
import io.lazyproto.wire.WireWriter;

import java.util.Arrays;

/**
 * Pure single-threaded 128-bit {@code FieldTape} &amp; SWAR scanner/splicer for the Java lower-level core.
 *
 * <p>Zero {@code com.google.protobuf} dependencies, zero {@code volatile}/{@code synchronized} locks,
 * zero external {@code WireOverlay} object indirection: mutation flags live directly inside {@code word1}.
 */
public final class WireTape {
    public static final int FLAG_CLEAN_RAW       = 0;
    public static final int FLAG_DIRTY_SAME_SIZE = 1;
    public static final int FLAG_DIRTY_VAR_SIZE  = 2;
    public static final int FLAG_DELETED         = 3;
    public static final int FLAG_SHADOWED        = 4;
    public static final int FLAG_SPLIT_CONT      = 5;
    public static final int FLAG_SPLIT_TAIL      = 6;

    public static final int NO_AUX_IDX = 0xFFFFFF;
    public static final byte[] EMPTY_BYTES = new byte[0];
    private static final long[] EMPTY_TAPE = new long[0];

    public interface ScanVisitor {
        void onField(int tapeIdx, int fieldNumber, int wireType);
    }

    /**
     * Low-level raw record callback shared by {@link WireTape} and {@code ImmutableWireBase}
     * so both lower-level and compatibility layers use a single bounds-checked SWAR scanner.
     */
    public interface RawRecordSink {
        void onRecord(int fieldNumber, int wireType, int tagOffset, int recordEnd, int headerLen);
    }

    public interface DirtyEntryWriter {
        void writeDirty(int tapeIdx, int fieldNumber, int wireType, int auxIndex, WireWriter writer);
    }

    public static long packWord0(int tagOffset, int recordEnd) {
        return ((long) tagOffset << 32) | (recordEnd & 0xFFFFFFFFL);
    }

    public static long packWord1(int fieldNumber, int wireType, int flagsOrKind, int headerLen, int auxIndex) {
        return ((long) fieldNumber << 35)
                | ((long) (wireType & 0x7) << 32)
                | ((long) (flagsOrKind & 0x7) << 29)
                | ((long) (headerLen & 0x1F) << 24)
                | (auxIndex & 0xFFFFFFL);
    }

    public static void scanWireRange(byte[] b, int cursor, int limit, RawRecordSink sink) {
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
                    if (8 > limit - cursor) throw new IllegalArgumentException("Truncated FIXED64 field");
                    cursor += 8;
                    recordEnd = cursor;
                    break;
                case WireFormat.WIRETYPE_LENGTH_DELIMITED:
                    if (cursor >= limit) {
                        throw new IllegalArgumentException("Truncated LENGTH_DELIMITED header at " + cursor);
                    }
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
                    if (len < 0 || len > limit - cursor) {
                        throw new IllegalArgumentException("Invalid LENGTH_DELIMITED len=" + len + " at " + cursor);
                    }
                    valOffset = cursor;
                    cursor += len;
                    recordEnd = cursor;
                    break;
                case WireFormat.WIRETYPE_FIXED32:
                    valOffset = cursor;
                    if (4 > limit - cursor) throw new IllegalArgumentException("Truncated FIXED32 field");
                    cursor += 4;
                    recordEnd = cursor;
                    break;
                default:
                    throw new IllegalArgumentException("Unsupported wireType " + wireType + " at offset " + tagOffset);
            }

            sink.onRecord(fieldNumber, wireType, tagOffset, recordEnd, valOffset - tagOffset);
        }
    }

    public byte[] buf = EMPTY_BYTES;
    public int rawOffset;
    public int rawLength;

    private final int initialWords;
    public long[] tape = EMPTY_TAPE;
    public int tapeCount;
    public boolean scanned;
    public boolean multiSegment;
    private ScanVisitor activeVisitor;
    private final RawRecordSink rawSink = this::onRawRecord;

    public WireTape(int initialEntries) {
        this.initialWords = Math.max(8, initialEntries << 1);
    }

    public void reset(byte[] buffer, int offset, int length) {
        this.buf = buffer != null ? buffer : EMPTY_BYTES;
        this.rawOffset = offset;
        this.rawLength = length;
        this.tapeCount = 0;
        this.scanned = false;
        this.multiSegment = false;
    }

    public void resetEmpty() {
        this.buf = EMPTY_BYTES;
        this.rawOffset = 0;
        this.rawLength = 0;
        this.tapeCount = 0;
        this.scanned = true;
        this.multiSegment = false;
    }

    public void scanRange(byte[] b, int cursor, int limit, ScanVisitor visitor) {
        this.activeVisitor = visitor;
        try {
            scanWireRange(b, cursor, limit, rawSink);
        } finally {
            this.activeVisitor = null;
        }
    }

    private void onRawRecord(int fieldNumber, int wireType, int tagOffset, int recordEnd, int headerLen) {
        int tapeIdx = appendEntry(fieldNumber, wireType, tagOffset, recordEnd, headerLen);
        activeVisitor.onField(tapeIdx, fieldNumber, wireType);
    }

    public int appendEntry(int fieldNumber, int wireType, int tagOffset, int recordEnd, int headerLen) {
        int idx = this.tapeCount;
        int wordIdx = idx << 1;
        if (this.tape == EMPTY_TAPE) {
            this.tape = new long[this.initialWords];
        } else if (wordIdx + 1 >= this.tape.length) {
            this.tape = Arrays.copyOf(this.tape, this.tape.length << 1);
        }
        this.tape[wordIdx] = packWord0(tagOffset, recordEnd);
        this.tape[wordIdx + 1] = packWord1(fieldNumber, wireType, FLAG_CLEAN_RAW, headerLen, NO_AUX_IDX);
        this.tapeCount = idx + 1;
        return idx;
    }

    public void spliceTo(WireWriter writer, DirtyEntryWriter dirtyWriter) {
        byte[] b = this.buf;
        int n = this.tapeCount;
        int spanStart = -1;
        int spanEnd = -1;

        for (int i = 0; i < n; i++) {
            long w1 = tape[(i << 1) + 1];
            int flags = (int) ((w1 >>> 29) & 0x7L);
            if (flags == FLAG_CLEAN_RAW || flags == FLAG_SPLIT_CONT || flags == FLAG_SPLIT_TAIL) {
                long w0 = tape[i << 1];
                int tagOff = (int) (w0 >>> 32);
                int recEnd = (int) w0;
                if (spanStart == -1) {
                    spanStart = tagOff;
                    spanEnd = recEnd;
                } else if (spanEnd == tagOff) {
                    spanEnd = recEnd;
                } else {
                    writer.writeRawBytes(b, spanStart, spanEnd - spanStart);
                    spanStart = tagOff;
                    spanEnd = recEnd;
                }
            } else if (flags == FLAG_DELETED || flags == FLAG_SHADOWED) {
                if (spanStart != -1) {
                    writer.writeRawBytes(b, spanStart, spanEnd - spanStart);
                    spanStart = -1;
                }
            } else {
                if (spanStart != -1) {
                    writer.writeRawBytes(b, spanStart, spanEnd - spanStart);
                    spanStart = -1;
                }
                int fn = (int) (w1 >>> 35);
                int wt = (int) ((w1 >>> 32) & 0x7L);
                int aux = (int) (w1 & 0xFFFFFFL);
                dirtyWriter.writeDirty(i, fn, wt, aux, writer);
            }
        }
        if (spanStart != -1) {
            writer.writeRawBytes(b, spanStart, spanEnd - spanStart);
        }
    }

    public int tagOffset(int idx) { return (int) (tape[idx << 1] >>> 32); }
    public int recordEnd(int idx) { return (int) tape[idx << 1]; }
    public int valueOffset(int idx) {
        long w0 = tape[idx << 1];
        long w1 = tape[(idx << 1) + 1];
        return (int) (w0 >>> 32) + (int) ((w1 >>> 24) & 0x1FL);
    }
    public int valueLength(int idx) {
        long w0 = tape[idx << 1];
        long w1 = tape[(idx << 1) + 1];
        return (int) w0 - ((int) (w0 >>> 32) + (int) ((w1 >>> 24) & 0x1FL));
    }
    public int flags(int idx) { return (int) ((tape[(idx << 1) + 1] >>> 29) & 0x7L); }
    public int fieldNumber(int idx) { return (int) (tape[(idx << 1) + 1] >>> 35); }
    public int wireType(int idx) { return (int) ((tape[(idx << 1) + 1] >>> 32) & 0x7L); }
    public int auxIndex(int idx) { return (int) (tape[(idx << 1) + 1] & 0xFFFFFFL); }

    public void setFlags(int idx, int flags) {
        int w1Idx = (idx << 1) + 1;
        tape[w1Idx] = (tape[w1Idx] & ~(0x7L << 29)) | ((long) (flags & 0x7) << 29);
    }

    public void setFlagsAndAux(int idx, int flags, int aux) {
        int w1Idx = (idx << 1) + 1;
        tape[w1Idx] = (tape[w1Idx] & ~((0x7L << 29) | 0xFFFFFFL))
                | ((long) (flags & 0x7) << 29)
                | (aux & 0xFFFFFFL);
    }
}
