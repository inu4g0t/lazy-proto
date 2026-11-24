package io.lazyproto.runtime;

import com.google.protobuf.ByteOutput;
import com.google.protobuf.ByteString;
import com.google.protobuf.CodedInputStream;
import com.google.protobuf.CodedOutputStream;
import com.google.protobuf.Descriptors;
import com.google.protobuf.ExtensionRegistryLite;
import com.google.protobuf.Message;
import com.google.protobuf.MessageLite;
import com.google.protobuf.Parser;
import com.google.protobuf.UnknownFieldSet;
import com.google.protobuf.UnsafeByteOperations;
import io.lazyproto.wire.WireFormat;
import io.lazyproto.wire.WireReader;
import io.lazyproto.wire.WireWriter;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Base class for all lazy-proto messages.
 *
 * <p>Implements both {@link com.google.protobuf.Message} and {@link com.google.protobuf.Message.Builder}
 * to support zero-allocation unified self-builder semantics, while throwing {@link UnsupportedOperationException}
 * on non-lazy methods such as {@link #equals(Object)}, {@link #hashCode()}, {@link #getAllFields()},
 * and reflection Descriptor operations.
 *
 * <p>128-bit FieldTape layout (2 {@code long} words per wire record):
 * <ul>
 *   <li>{@code tape[i * 2]}: {@code [63..32] tagOffset (uint32) | [31..0] recordEnd (uint32)}</li>
 *   <li>{@code tape[i * 2 + 1]}: {@code [63..35] fieldNumber (29-bit) | [34..32] wireType (3-bit)
 *       | [31..28] flags (4-bit) | [27..24] headerLen (4-bit) | [23..0] nextTapeIdx (24-bit)}</li>
 * </ul>
 */
public abstract class AbstractLazyMessage<M extends AbstractLazyMessage<M>>
        implements Message, Message.Builder {

    // Tape Entry State Flags (4-bit)
    public static final int FLAG_CLEAN_RAW       = 0;
    public static final int FLAG_DIRTY_SAME_SIZE = 1;
    public static final int FLAG_DIRTY_VAR_SIZE  = 2;
    public static final int FLAG_DELETED         = 3;
    public static final int FLAG_SHADOWED        = 4;

    public static final int NO_NEXT_IDX = 0xFFFFFF;
    private static final byte[] EMPTY_BYTES = new byte[0];
    private static final long[] EMPTY_TAPE = new long[0];

    // Underlying raw input buffer slice
    protected byte[] buf = EMPTY_BYTES;
    protected int rawOffset;
    protected int rawLength;

    // Flat 128-bit FieldTape (2 longs per entry), lazily allocated on first scan
    private final int initialTapeWords;
    protected long[] tape = EMPTY_TAPE;
    protected int tapeCount;
    protected boolean scanned;

    // 64-bit State Bitmasks
    protected long presentMask;
    protected long decodedMask;
    protected long sameSizeDirtyMask;
    protected long varSizeDirtyMask;
    protected long appendedMask;
    protected boolean hasDeletedEntries;
    protected int cachedSerializedSize = -1;

    // Parent linkage for upward dirty bubbling in nested sub-messages
    protected AbstractLazyMessage<?> parent;
    protected int parentTapeIdx = -1;
    protected long parentFieldBit;

    protected AbstractLazyMessage(int initialTapeEntries) {
        this.initialTapeWords = Math.max(8, initialTapeEntries << 1);
    }

    public void attachToParent(AbstractLazyMessage<?> parent, int parentTapeIdx, long parentFieldBit) {
        this.parent = parent;
        this.parentTapeIdx = parentTapeIdx;
        this.parentFieldBit = parentFieldBit;
    }

    @SuppressWarnings("unchecked")
    public M wrap(byte[] buffer, int offset, int length) {
        reset();
        this.buf = buffer != null ? buffer : EMPTY_BYTES;
        this.rawOffset = offset;
        this.rawLength = length;
        return (M) this;
    }

    public M wrap(byte[] buffer) {
        return wrap(buffer, 0, buffer.length);
    }

    /**
     * Zero-copy extraction from Google Protobuf {@link ByteString} using {@link UnsafeByteOperations}.
     */
    public M wrap(ByteString byteString) {
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
                        // RopeByteString fallback: flatten once
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
            return wrap(EMPTY_BYTES, 0, 0);
        }
        return wrap(holder[0], range[0], range[1]);
    }

    public void reset() {
        this.buf = EMPTY_BYTES;
        this.rawOffset = 0;
        this.rawLength = 0;
        this.tapeCount = 0;
        this.scanned = false;
        this.presentMask = 0L;
        this.decodedMask = 0L;
        this.sameSizeDirtyMask = 0L;
        this.varSizeDirtyMask = 0L;
        this.appendedMask = 0L;
        this.hasDeletedEntries = false;
        this.cachedSerializedSize = -1;
        resetSlots();
    }

    protected abstract void resetSlots();

    public final byte[] rawBuffer() {
        return buf;
    }

    public final int rawOffset() {
        return rawOffset;
    }

    public final int rawLength() {
        return rawLength;
    }

    public final boolean isScanned() {
        return scanned;
    }

    public final void markScannedEmpty() {
        this.scanned = true;
    }

    public final boolean isClean() {
        return (sameSizeDirtyMask | varSizeDirtyMask | appendedMask) == 0L && !hasDeletedEntries;
    }

    public final boolean isSameSizeDirtyOnly() {
        return (varSizeDirtyMask | appendedMask) == 0L && !hasDeletedEntries && sameSizeDirtyMask != 0L;
    }

    // ==================== Tier 1: Shallow Non-Recursive Wire Scanner ====================

    public final void ensureScanned() {
        if (!scanned) {
            scanCurrentLevel();
        }
    }

    protected void scanCurrentLevel() {
        this.scanned = true;
        if (rawLength == 0) {
            return;
        }
        final byte[] b = this.buf;
        int cursor = this.rawOffset;
        final int limit = cursor + this.rawLength;

        while (cursor < limit) {
            final int tagOffset = cursor;
            final int tag;
            final byte firstByte = b[cursor];
            if (firstByte >= 0) {
                // Fast path: 1-byte tag (field numbers 1..15)
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
                    // O(1) skip across nested sub-message, string, bytes, or packed repeated array!
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
                | ((long) FLAG_CLEAN_RAW << 28)
                | ((long) (headerLen & 0xF) << 24)
                | NO_NEXT_IDX;
        this.tape[wordIdx] = word0;
        this.tape[wordIdx + 1] = word1;
        this.tapeCount = idx + 1;
        return idx;
    }

    /**
     * Hook called during {@link #scanCurrentLevel()} for each wire entry.
     * Subclasses register the {@code tapeIdx} in their O(1) slot directory,
     * mark shadowed duplicate scalar entries, or link repeated fields.
     */
    protected abstract void onScanField(int fieldNumber, int wireType, int tapeIdx);

    // ==================== FieldTape Bit-Unpacking Accessors ====================

    public final int tapeCount() {
        return tapeCount;
    }

    public final int tapeTagOffset(int tapeIdx) {
        return (int) (tape[tapeIdx << 1] >>> 32);
    }

    public final int tapeRecordEnd(int tapeIdx) {
        return (int) tape[tapeIdx << 1];
    }

    public final int tapeRecordLength(int tapeIdx) {
        long w0 = tape[tapeIdx << 1];
        return (int) w0 - (int) (w0 >>> 32);
    }

    public final int tapeHeaderLength(int tapeIdx) {
        return (int) ((tape[(tapeIdx << 1) + 1] >>> 24) & 0xFL);
    }

    public final int tapeValueOffset(int tapeIdx) {
        long w0 = tape[tapeIdx << 1];
        long w1 = tape[(tapeIdx << 1) + 1];
        return (int) (w0 >>> 32) + (int) ((w1 >>> 24) & 0xFL);
    }

    public final int tapeValueLength(int tapeIdx) {
        long w0 = tape[tapeIdx << 1];
        long w1 = tape[(tapeIdx << 1) + 1];
        int tagOff = (int) (w0 >>> 32);
        int recEnd = (int) w0;
        int hdrLen = (int) ((w1 >>> 24) & 0xFL);
        return recEnd - (tagOff + hdrLen);
    }

    public final int tapeFieldNumber(int tapeIdx) {
        return (int) (tape[(tapeIdx << 1) + 1] >>> 35);
    }

    public final int tapeWireType(int tapeIdx) {
        return (int) ((tape[(tapeIdx << 1) + 1] >>> 32) & 0x7L);
    }

    public final int tapeFlags(int tapeIdx) {
        return (int) ((tape[(tapeIdx << 1) + 1] >>> 28) & 0xFL);
    }

    public final void setTapeFlags(int tapeIdx, int flags) {
        int w1Idx = (tapeIdx << 1) + 1;
        tape[w1Idx] = (tape[w1Idx] & ~(0xFL << 28)) | ((long) (flags & 0xF) << 28);
    }

    public final int tapeNextIdx(int tapeIdx) {
        return (int) (tape[(tapeIdx << 1) + 1] & 0xFFFFFFL);
    }

    public final void setTapeNextIdx(int tapeIdx, int nextIdx) {
        int w1Idx = (tapeIdx << 1) + 1;
        tape[w1Idx] = (tape[w1Idx] & ~0xFFFFFFL) | (nextIdx & 0xFFFFFFL);
    }

    // ==================== Upward Dirty Propagation ====================

    protected final void bubbleSameSizeDirtyToParent() {
        if (parent != null) {
            parent.onChildSameSizeDirty(parentTapeIdx, parentFieldBit);
        }
    }

    protected final void bubbleVarSizeDirtyToParent() {
        this.cachedSerializedSize = -1;
        if (parent != null) {
            parent.onChildVarSizeDirty(parentTapeIdx, parentFieldBit);
        }
    }

    protected final void onChildSameSizeDirty(int childTapeIdx, long fieldBit) {
        if (childTapeIdx >= 0 && tapeFlags(childTapeIdx) == FLAG_CLEAN_RAW) {
            setTapeFlags(childTapeIdx, FLAG_DIRTY_SAME_SIZE);
        }
        this.sameSizeDirtyMask |= fieldBit;
        bubbleSameSizeDirtyToParent();
    }

    protected final void onChildVarSizeDirty(int childTapeIdx, long fieldBit) {
        if (childTapeIdx >= 0) {
            setTapeFlags(childTapeIdx, FLAG_DIRTY_VAR_SIZE);
        }
        this.varSizeDirtyMask |= fieldBit;
        this.cachedSerializedSize = -1;
        bubbleVarSizeDirtyToParent();
    }

    // ==================== Tier 4: Input-Reusing Serialization Engine ====================

    @Override
    public int getSerializedSize() {
        // Fast-Path 0 & Fast-Path 1: No variable-size changes -> exact same byte length as raw input!
        if (!scanned || ((varSizeDirtyMask | appendedMask) == 0L && !hasDeletedEntries)) {
            return this.rawLength;
        }
        if (this.cachedSerializedSize >= 0) {
            return this.cachedSerializedSize;
        }
        int total = 0;
        for (int i = 0; i < tapeCount; i++) {
            int flags = tapeFlags(i);
            if (flags == FLAG_CLEAN_RAW || flags == FLAG_DIRTY_SAME_SIZE) {
                total += tapeRecordLength(i);
            } else if (flags == FLAG_DELETED || flags == FLAG_SHADOWED) {
                continue;
            } else {
                // FLAG_DIRTY_VAR_SIZE: compute size only for this dirty field
                total += computeDirtyEntrySize(i, tapeFieldNumber(i), tapeWireType(i));
            }
        }
        if (appendedMask != 0L) {
            total += computeAppendedFieldsSize();
        }
        this.cachedSerializedSize = total;
        return total;
    }

    @Override
    public byte[] toByteArray() {
        // Fast-Path 0: 100% unmodified message -> single System.arraycopy
        if (isClean()) {
            byte[] out = new byte[rawLength];
            System.arraycopy(buf, rawOffset, out, 0, rawLength);
            return out;
        }
        // Fast-Path 1: Only same-size/fixed-size fields modified -> single System.arraycopy + O(1) offset patch
        if (isSameSizeDirtyOnly()) {
            byte[] out = new byte[rawLength];
            System.arraycopy(buf, rawOffset, out, 0, rawLength);
            applyInPlacePatches(out, rawOffset);
            return out;
        }
        // Fast-Path 2 & 3: Contiguous Span Coalescing + Dirty Entry Encoding
        int size = getSerializedSize();
        byte[] out = new byte[size];
        WireWriter writer = new WireWriter(out, 0);
        writeSplicedTo(writer);
        return out;
    }

    /**
     * Writes directly into a target buffer or executes in-place patching when possible.
     */
    public int writeToBuffer(byte[] target, int targetOffset) {
        if (isClean()) {
            System.arraycopy(buf, rawOffset, target, targetOffset, rawLength);
            return rawLength;
        }
        if (isSameSizeDirtyOnly()) {
            System.arraycopy(buf, rawOffset, target, targetOffset, rawLength);
            applyInPlacePatches(target, rawOffset - targetOffset);
            return rawLength;
        }
        WireWriter writer = new WireWriter(target, targetOffset);
        writeSplicedTo(writer);
        return writer.position() - targetOffset;
    }

    public void writeSplicedTo(WireWriter writer) {
        if (isClean()) {
            writer.writeRawBytes(buf, rawOffset, rawLength);
            return;
        }
        if (isSameSizeDirtyOnly()) {
            int outStart = writer.position();
            writer.writeRawBytes(buf, rawOffset, rawLength);
            applyInPlacePatches(writer.buffer(), rawOffset - outStart);
            return;
        }

        // Contiguous Span Coalescing Loop
        int spanStart = -1;
        int spanEnd = -1;

        for (int i = 0; i < tapeCount; i++) {
            int flags = tapeFlags(i);
            if (flags == FLAG_CLEAN_RAW) {
                int tagOff = tapeTagOffset(i);
                int recEnd = tapeRecordEnd(i);
                if (spanStart == -1) {
                    spanStart = tagOff;
                    spanEnd = recEnd;
                } else if (spanEnd == tagOff) {
                    // Contiguous in physical input buffer! Coalesce into current span.
                    spanEnd = recEnd;
                } else {
                    writer.writeRawBytes(buf, spanStart, spanEnd - spanStart);
                    spanStart = tagOff;
                    spanEnd = recEnd;
                }
            } else if (flags == FLAG_DELETED || flags == FLAG_SHADOWED) {
                if (spanStart != -1) {
                    writer.writeRawBytes(buf, spanStart, spanEnd - spanStart);
                    spanStart = -1;
                }
            } else {
                // FLAG_DIRTY_SAME_SIZE or FLAG_DIRTY_VAR_SIZE
                if (spanStart != -1) {
                    writer.writeRawBytes(buf, spanStart, spanEnd - spanStart);
                    spanStart = -1;
                }
                writeDirtyEntry(i, tapeFieldNumber(i), tapeWireType(i), writer);
            }
        }

        if (spanStart != -1) {
            writer.writeRawBytes(buf, spanStart, spanEnd - spanStart);
        }

        if (appendedMask != 0L) {
            writeAppendedFields(writer);
        }
    }

    @Override
    public ByteString toByteString() {
        if (isClean()) {
            // Zero-copy ByteString wrapping the original input slice!
            return UnsafeByteOperations.unsafeWrap(buf, rawOffset, rawLength);
        }
        return UnsafeByteOperations.unsafeWrap(toByteArray());
    }

    @Override
    public void writeTo(CodedOutputStream output) throws IOException {
        byte[] bytes = isClean() ? null : toByteArray();
        if (bytes == null) {
            output.writeRawBytes(buf, rawOffset, rawLength);
        } else {
            output.writeRawBytes(bytes, 0, bytes.length);
        }
    }

    @Override
    public void writeTo(OutputStream output) throws IOException {
        if (isClean()) {
            output.write(buf, rawOffset, rawLength);
        } else {
            output.write(toByteArray());
        }
    }

    @Override
    public void writeDelimitedTo(OutputStream output) throws IOException {
        int size = getSerializedSize();
        CodedOutputStream codedOut = CodedOutputStream.newInstance(output, CodedOutputStream.computeUInt32SizeNoTag(size) + 64);
        codedOut.writeUInt32NoTag(size);
        codedOut.flush();
        writeTo(output);
    }

    /**
     * Explicit byte-level equality check on the underlying/serialized wire bytes,
     * replacing {@link #equals(Object)}.
     */
    public boolean rawBytesEquals(AbstractLazyMessage<?> other) {
        if (other == null) return false;
        if (this == other) return true;
        if (this.isClean() && other.isClean()) {
            if (this.rawLength != other.rawLength) return false;
            return Arrays.equals(
                    this.buf, this.rawOffset, this.rawOffset + this.rawLength,
                    other.buf, other.rawOffset, other.rawOffset + other.rawLength);
        }
        return Arrays.equals(this.toByteArray(), other.toByteArray());
    }

    protected abstract void applyInPlacePatches(byte[] target, int baseOffset);

    protected abstract int computeDirtyEntrySize(int tapeIdx, int fieldNumber, int wireType);

    protected abstract void writeDirtyEntry(int tapeIdx, int fieldNumber, int wireType, WireWriter writer);

    protected abstract int computeAppendedFieldsSize();

    protected abstract void writeAppendedFields(WireWriter writer);

    // ==================== Message & Builder Lifecycle Methods ====================

    @Override
    public boolean isInitialized() {
        return true;
    }

    @Override
    public Message.Builder clear() {
        reset();
        return this;
    }

    @Override
    public Message build() {
        return this;
    }

    @Override
    public Message buildPartial() {
        return this;
    }

    @Override
    public Message.Builder clone() {
        throw new UnsupportedOperationException("clone() is disabled in lazy-proto; use wrap() or toByteArray().");
    }

    @Override
    public Message.Builder mergeFrom(byte[] data) {
        return wrap(data, 0, data.length);
    }

    @Override
    public Message.Builder mergeFrom(byte[] data, int off, int len) {
        return wrap(data, off, len);
    }

    @Override
    public Message.Builder mergeFrom(ByteString data) {
        return wrap(data);
    }

    @Override
    public Message.Builder mergeFrom(InputStream input) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        byte[] tmp = new byte[4096];
        int n;
        while ((n = input.read(tmp)) != -1) {
            baos.write(tmp, 0, n);
        }
        return wrap(baos.toByteArray());
    }

    @Override
    public Message.Builder mergeFrom(CodedInputStream input) throws IOException {
        return mergeFrom(input, ExtensionRegistryLite.getEmptyRegistry());
    }

    @Override
    public Message.Builder mergeFrom(CodedInputStream input, ExtensionRegistryLite extensionRegistry) throws IOException {
        byte[] bytes = input.readRawBytes(input.getBytesUntilLimit());
        return wrap(bytes);
    }

    @Override
    public Message.Builder mergeFrom(Message other) {
        if (other instanceof AbstractLazyMessage<?> lazyOther && lazyOther.isClean()) {
            return wrap(lazyOther.buf, lazyOther.rawOffset, lazyOther.rawLength);
        }
        return wrap(other.toByteArray());
    }

    @Override
    public Message.Builder mergeFrom(MessageLite other) {
        if (other instanceof AbstractLazyMessage<?> lazyOther && lazyOther.isClean()) {
            return wrap(lazyOther.buf, lazyOther.rawOffset, lazyOther.rawLength);
        }
        return wrap(other.toByteArray());
    }

    @Override
    public Message.Builder mergeFrom(byte[] data, ExtensionRegistryLite extensionRegistry) {
        return wrap(data);
    }

    @Override
    public Message.Builder mergeFrom(byte[] data, int off, int len, ExtensionRegistryLite extensionRegistry) {
        return wrap(data, off, len);
    }

    @Override
    public Message.Builder mergeFrom(ByteString data, ExtensionRegistryLite extensionRegistry) {
        return wrap(data);
    }

    @Override
    public Message.Builder mergeFrom(InputStream input, ExtensionRegistryLite extensionRegistry) throws IOException {
        return mergeFrom(input);
    }

    @Override
    public boolean mergeDelimitedFrom(InputStream input) throws IOException {
        int firstByte = input.read();
        if (firstByte == -1) return false;
        int size = CodedInputStream.readRawVarint32(firstByte, input);
        byte[] data = input.readNBytes(size);
        wrap(data, 0, data.length);
        return true;
    }

    @Override
    public boolean mergeDelimitedFrom(InputStream input, ExtensionRegistryLite extensionRegistry) throws IOException {
        return mergeDelimitedFrom(input);
    }

    @Override
    public Parser<? extends Message> getParserForType() {
        throw new UnsupportedOperationException("getParserForType() is disabled in lazy-proto; use parseFrom/wrap.");
    }

    // ==================== Intentionally Unsupported Methods (Throw Direct Exception) ====================

    @Override
    public final boolean equals(Object obj) {
        throw new UnsupportedOperationException(
                "equals() is disabled in lazy-proto to prevent full-tree eager deserialization. " +
                "Use rawBytesEquals() or compare key fields directly.");
    }

    @Override
    public final int hashCode() {
        throw new UnsupportedOperationException(
                "hashCode() is disabled in lazy-proto. Do not use mutable lazy messages as hash keys.");
    }

    @Override
    public final Descriptors.Descriptor getDescriptorForType() {
        throw new UnsupportedOperationException("Reflection Descriptor is disabled in lazy-proto.");
    }

    @Override
    public final Map<Descriptors.FieldDescriptor, Object> getAllFields() {
        throw new UnsupportedOperationException("getAllFields() reflection is disabled in lazy-proto.");
    }

    @Override
    public final boolean hasOneof(Descriptors.OneofDescriptor oneof) {
        throw new UnsupportedOperationException("Reflection is disabled in lazy-proto.");
    }

    @Override
    public final Descriptors.FieldDescriptor getOneofFieldDescriptor(Descriptors.OneofDescriptor oneof) {
        throw new UnsupportedOperationException("Reflection is disabled in lazy-proto.");
    }

    @Override
    public final boolean hasField(Descriptors.FieldDescriptor field) {
        throw new UnsupportedOperationException("Reflection is disabled in lazy-proto.");
    }

    @Override
    public final Object getField(Descriptors.FieldDescriptor field) {
        throw new UnsupportedOperationException("Reflection is disabled in lazy-proto.");
    }

    @Override
    public final int getRepeatedFieldCount(Descriptors.FieldDescriptor field) {
        throw new UnsupportedOperationException("Reflection is disabled in lazy-proto.");
    }

    @Override
    public final Object getRepeatedField(Descriptors.FieldDescriptor field, int index) {
        throw new UnsupportedOperationException("Reflection is disabled in lazy-proto.");
    }

    @Override
    public final UnknownFieldSet getUnknownFields() {
        throw new UnsupportedOperationException(
                "UnknownFieldSet object creation is disabled; unknown fields are preserved automatically in FieldTape.");
    }

    @Override
    public final List<String> findInitializationErrors() {
        throw new UnsupportedOperationException("Reflection is disabled in lazy-proto.");
    }

    @Override
    public final String getInitializationErrorString() {
        throw new UnsupportedOperationException("Reflection is disabled in lazy-proto.");
    }

    @Override
    public final Message.Builder newBuilderForField(Descriptors.FieldDescriptor field) {
        throw new UnsupportedOperationException("Reflection is disabled in lazy-proto.");
    }

    @Override
    public final Message.Builder getFieldBuilder(Descriptors.FieldDescriptor field) {
        throw new UnsupportedOperationException("Reflection is disabled in lazy-proto.");
    }

    @Override
    public final Message.Builder getRepeatedFieldBuilder(Descriptors.FieldDescriptor field, int index) {
        throw new UnsupportedOperationException("Reflection is disabled in lazy-proto.");
    }

    @Override
    public final Message.Builder setField(Descriptors.FieldDescriptor field, Object value) {
        throw new UnsupportedOperationException("Reflection is disabled in lazy-proto.");
    }

    @Override
    public final Message.Builder clearField(Descriptors.FieldDescriptor field) {
        throw new UnsupportedOperationException("Reflection is disabled in lazy-proto.");
    }

    @Override
    public final Message.Builder clearOneof(Descriptors.OneofDescriptor oneof) {
        throw new UnsupportedOperationException("Reflection is disabled in lazy-proto.");
    }

    @Override
    public final Message.Builder setRepeatedField(Descriptors.FieldDescriptor field, int index, Object value) {
        throw new UnsupportedOperationException("Reflection is disabled in lazy-proto.");
    }

    @Override
    public final Message.Builder addRepeatedField(Descriptors.FieldDescriptor field, Object value) {
        throw new UnsupportedOperationException("Reflection is disabled in lazy-proto.");
    }

    @Override
    public final Message.Builder setUnknownFields(UnknownFieldSet unknownFields) {
        throw new UnsupportedOperationException("UnknownFieldSet is disabled in lazy-proto.");
    }

    @Override
    public final Message.Builder mergeUnknownFields(UnknownFieldSet unknownFields) {
        throw new UnsupportedOperationException("UnknownFieldSet is disabled in lazy-proto.");
    }

    @Override
    public String toString() {
        // Safeguard: Never trigger TextFormat full-tree deserialization on toString()
        return getClass().getSimpleName() + "[rawLen=" + rawLength
                + ", scanned=" + scanned
                + ", decoded=0x" + Long.toHexString(decodedMask)
                + ", sameSizeDirty=0x" + Long.toHexString(sameSizeDirtyMask)
                + ", varSizeDirty=0x" + Long.toHexString(varSizeDirtyMask) + "]";
    }
}
