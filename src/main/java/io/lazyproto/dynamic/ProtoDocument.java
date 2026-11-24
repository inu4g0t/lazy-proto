package io.lazyproto.dynamic;

import com.google.protobuf.Message;
import io.lazyproto.runtime.AbstractLazyBuilder;
import io.lazyproto.runtime.AbstractLazyMessage;
import io.lazyproto.wire.Utf8Slice;
import io.lazyproto.wire.WireFormat;
import io.lazyproto.wire.WireReader;
import io.lazyproto.wire.WireWriter;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Dynamic schema-less lazy Protobuf manipulator inspired by {@code json-iterator}'s {@code Any}.
 *
 * <p>Allows reading, mutating, inserting, and deleting arbitrary fields by field number path without compiling
 * a {@code .proto} schema, while preserving all unvisited fields (including unpacked repeated fields)
 * and reusing input bytes on serialization.
 */
public final class ProtoDocument extends AbstractLazyMessage<ProtoDocument> {
    private static final int DIRECT_FIELD_MAX = 64;

    // Fast O(1) direct lookup table for field numbers 1..64 -> last scanned tapeIdx
    private final int[] directTapeIdx = new int[DIRECT_FIELD_MAX + 1];

    // Modified values indexed by tapeIdx (lazily allocated only when mutated)
    private long[] mutatedPrimitives;
    private byte[][] mutatedBytes;
    private ProtoDocument[] childDocs;

    // Newly inserted (appended) fields that were absent in the original input tape
    private int[] appendedFields;
    private byte[] appendedWireTypes;
    private long[] appendedPrimitives;
    private byte[][] appendedBytes;
    private ProtoDocument[] appendedChildDocs;
    private int appendedCount;

    private final Utf8Slice reusableSlice = new Utf8Slice();

    public ProtoDocument() {
        super(16);
        Arrays.fill(directTapeIdx, -1);
    }

    public static ProtoDocument wrapBytes(byte[] data) {
        return new ProtoDocument().wrap(data, 0, data.length);
    }

    public static ProtoDocument wrapBytes(byte[] data, int offset, int length) {
        return new ProtoDocument().wrap(data, offset, length);
    }

    public static Builder newBuilder() {
        ProtoDocument doc = new ProtoDocument();
        doc.markScannedEmpty();
        return new Builder(doc);
    }

    @Override
    protected void resetSlots() {
        Arrays.fill(directTapeIdx, -1);
        if (mutatedBytes != null) {
            Arrays.fill(mutatedBytes, null);
        }
        if (childDocs != null) {
            Arrays.fill(childDocs, null);
        }
        if (appendedBytes != null) {
            Arrays.fill(appendedBytes, 0, appendedCount, null);
        }
        if (appendedChildDocs != null) {
            Arrays.fill(appendedChildDocs, 0, appendedCount, null);
        }
        appendedCount = 0;
    }

    @Override
    protected void onScanField(int fieldNumber, int wireType, int tapeIdx) {
        // Schema-less mode: NEVER auto-shadow earlier entries during scan, because an unknown field
        // might be an unpacked repeated scalar or a split sub-message!
        if (fieldNumber > 0 && fieldNumber <= DIRECT_FIELD_MAX) {
            directTapeIdx[fieldNumber] = tapeIdx;
        }
        presentMask |= (1L << (fieldNumber & 63));
    }

    private int findTapeIdx(int fieldNumber) {
        ensureScanned();
        if (fieldNumber > 0 && fieldNumber <= DIRECT_FIELD_MAX) {
            int idx = directTapeIdx[fieldNumber];
            if (idx >= 0 && tapeFlags(idx) != FLAG_DELETED && tapeFlags(idx) != FLAG_SHADOWED) {
                return idx;
            }
            return -1;
        }
        for (int i = tapeCount - 1; i >= 0; i--) {
            if (tapeFieldNumber(i) == fieldNumber && tapeFlags(i) != FLAG_DELETED && tapeFlags(i) != FLAG_SHADOWED) {
                return i;
            }
        }
        return -1;
    }

    private int findAppendedIdx(int fieldNumber) {
        for (int i = 0; i < appendedCount; i++) {
            if (appendedFields[i] == fieldNumber) {
                return i;
            }
        }
        return -1;
    }

    public boolean has(int fieldNumber) {
        if (findTapeIdx(fieldNumber) >= 0) {
            return true;
        }
        int appIdx = findAppendedIdx(fieldNumber);
        if (appIdx >= 0) {
            if (appendedWireTypes[appIdx] == WireFormat.WIRETYPE_LENGTH_DELIMITED && appendedChildDocs[appIdx] != null) {
                ProtoDocument child = appendedChildDocs[appIdx];
                return !child.isClean() || child.tapeCount() > 0 || child.appendedCount > 0;
            }
            return true;
        }
        return false;
    }

    public int getInt32(int fieldNumber) {
        int appIdx = findAppendedIdx(fieldNumber);
        if (appIdx >= 0) {
            return (int) appendedPrimitives[appIdx];
        }
        int idx = findTapeIdx(fieldNumber);
        if (idx < 0) return 0;
        int flags = tapeFlags(idx);
        if (flags == FLAG_DIRTY_SAME_SIZE || flags == FLAG_DIRTY_VAR_SIZE) {
            return (int) mutatedPrimitives[idx];
        }
        if (tapeWireType(idx) != WireFormat.WIRETYPE_VARINT) {
            return 0;
        }
        return WireReader.readVarint32At(buf, tapeValueOffset(idx));
    }

    public long getInt64(int fieldNumber) {
        int appIdx = findAppendedIdx(fieldNumber);
        if (appIdx >= 0) {
            return appendedPrimitives[appIdx];
        }
        int idx = findTapeIdx(fieldNumber);
        if (idx < 0) return 0L;
        int flags = tapeFlags(idx);
        if (flags == FLAG_DIRTY_SAME_SIZE || flags == FLAG_DIRTY_VAR_SIZE) {
            return mutatedPrimitives[idx];
        }
        if (tapeWireType(idx) != WireFormat.WIRETYPE_VARINT) {
            return 0L;
        }
        return WireReader.readVarint64At(buf, tapeValueOffset(idx));
    }

    public long getFixed64(int fieldNumber) {
        int appIdx = findAppendedIdx(fieldNumber);
        if (appIdx >= 0) {
            return appendedPrimitives[appIdx];
        }
        int idx = findTapeIdx(fieldNumber);
        if (idx < 0) return 0L;
        int flags = tapeFlags(idx);
        if (flags == FLAG_DIRTY_SAME_SIZE || flags == FLAG_DIRTY_VAR_SIZE) {
            return mutatedPrimitives[idx];
        }
        if (tapeWireType(idx) != WireFormat.WIRETYPE_FIXED64) {
            return 0L;
        }
        return WireReader.readFixed64At(buf, tapeValueOffset(idx));
    }

    public Utf8Slice getSlice(int fieldNumber) {
        int appIdx = findAppendedIdx(fieldNumber);
        if (appIdx >= 0) {
            byte[] b = appendedBytes[appIdx];
            return b != null ? reusableSlice.wrap(b, 0, b.length) : reusableSlice.wrap(buf, 0, 0);
        }
        int idx = findTapeIdx(fieldNumber);
        if (idx < 0) return reusableSlice.wrap(buf, 0, 0);
        int flags = tapeFlags(idx);
        if ((flags == FLAG_DIRTY_SAME_SIZE || flags == FLAG_DIRTY_VAR_SIZE)
                && mutatedBytes != null && idx < mutatedBytes.length && mutatedBytes[idx] != null) {
            byte[] b = mutatedBytes[idx];
            return reusableSlice.wrap(b, 0, b.length);
        }
        if (tapeWireType(idx) != WireFormat.WIRETYPE_LENGTH_DELIMITED) {
            return reusableSlice.wrap(buf, 0, 0);
        }
        return reusableSlice.wrap(buf, tapeValueOffset(idx), tapeValueLength(idx));
    }

    public String getString(int fieldNumber) {
        int appIdx = findAppendedIdx(fieldNumber);
        if (appIdx >= 0) {
            byte[] b = appendedBytes[appIdx];
            return b != null ? WireReader.readStringAt(b, 0, b.length) : "";
        }
        int idx = findTapeIdx(fieldNumber);
        if (idx < 0) return "";
        int flags = tapeFlags(idx);
        if ((flags == FLAG_DIRTY_SAME_SIZE || flags == FLAG_DIRTY_VAR_SIZE)
                && mutatedBytes != null && idx < mutatedBytes.length && mutatedBytes[idx] != null) {
            byte[] b = mutatedBytes[idx];
            return WireReader.readStringAt(b, 0, b.length);
        }
        if (tapeWireType(idx) != WireFormat.WIRETYPE_LENGTH_DELIMITED) {
            return "";
        }
        return WireReader.readStringAt(buf, tapeValueOffset(idx), tapeValueLength(idx));
    }

    /**
     * Navigates lazily to a nested sub-message by field number (like {@code jsoniter.Any.get(fieldNum)}).
     * Automatically merges split sub-message segments if the field appears multiple times.
     */
    public ProtoDocument at(int fieldNumber) {
        int appIdx = findAppendedIdx(fieldNumber);
        if (appIdx >= 0) {
            if (appendedChildDocs[appIdx] == null) {
                ProtoDocument child = new ProtoDocument();
                child.markScannedEmpty();
                child.attachToParent(this, -1, 1L << (fieldNumber & 63));
                appendedChildDocs[appIdx] = child;
            }
            return appendedChildDocs[appIdx];
        }
        int lastIdx = findTapeIdx(fieldNumber);
        if (lastIdx < 0) {
            // Missing field insertion: create an empty child attached via appended entry if mutated
            int newAppIdx = appendEntrySlot(fieldNumber, (byte) WireFormat.WIRETYPE_LENGTH_DELIMITED);
            ProtoDocument child = new ProtoDocument();
            child.markScannedEmpty();
            child.attachToParent(this, -1, 1L << (fieldNumber & 63));
            appendedChildDocs[newAppIdx] = child;
            return child;
        }
        if (tapeWireType(lastIdx) != WireFormat.WIRETYPE_LENGTH_DELIMITED) {
            throw new IllegalArgumentException("Field " + fieldNumber + " is not length-delimited");
        }
        ensureMutationArrays();
        if (childDocs[lastIdx] == null) {
            // Link any earlier LENGTH_DELIMITED occurrences of fieldNumber for split sub-message merge
            int headIdx = -1;
            int prevSeg = -1;
            int segCount = 0;
            for (int i = 0; i < tapeCount; i++) {
                if (tapeFieldNumber(i) == fieldNumber
                        && tapeWireType(i) == WireFormat.WIRETYPE_LENGTH_DELIMITED
                        && tapeFlags(i) != FLAG_DELETED
                        && tapeFlags(i) != FLAG_SHADOWED) {
                    setTapeNextIdx(i, NO_NEXT_IDX);
                    if (headIdx < 0) {
                        headIdx = i;
                    } else {
                        setTapeNextIdx(prevSeg, i);
                    }
                    prevSeg = i;
                    segCount++;
                }
            }
            ProtoDocument child = new ProtoDocument();
            if (segCount > 1) {
                child.wrapMultiSegments(this, headIdx, 1L << (fieldNumber & 63));
                childDocs[headIdx] = child;
                childDocs[lastIdx] = child;
            } else {
                child.wrap(buf, tapeValueOffset(lastIdx), tapeValueLength(lastIdx));
                child.attachToParent(this, lastIdx, 1L << (fieldNumber & 63));
                childDocs[lastIdx] = child;
            }
        }
        return childDocs[lastIdx];
    }

    private void ensureMutationArrays() {
        int cap = Math.max(tapeCount, 16);
        if (mutatedPrimitives == null || mutatedPrimitives.length < cap) {
            mutatedPrimitives = mutatedPrimitives == null ? new long[cap] : Arrays.copyOf(mutatedPrimitives, cap);
            mutatedBytes = mutatedBytes == null ? new byte[cap][] : Arrays.copyOf(mutatedBytes, cap);
            childDocs = childDocs == null ? new ProtoDocument[cap] : Arrays.copyOf(childDocs, cap);
        }
    }

    private int appendEntrySlot(int fieldNumber, byte wireType) {
        int existing = findAppendedIdx(fieldNumber);
        if (existing >= 0) {
            appendedWireTypes[existing] = wireType;
            return existing;
        }
        if (appendedFields == null) {
            appendedFields = new int[4];
            appendedWireTypes = new byte[4];
            appendedPrimitives = new long[4];
            appendedBytes = new byte[4][];
            appendedChildDocs = new ProtoDocument[4];
        } else if (appendedCount >= appendedFields.length) {
            int newCap = appendedFields.length << 1;
            appendedFields = Arrays.copyOf(appendedFields, newCap);
            appendedWireTypes = Arrays.copyOf(appendedWireTypes, newCap);
            appendedPrimitives = Arrays.copyOf(appendedPrimitives, newCap);
            appendedBytes = Arrays.copyOf(appendedBytes, newCap);
            appendedChildDocs = Arrays.copyOf(appendedChildDocs, newCap);
        }
        int idx = appendedCount++;
        appendedFields[idx] = fieldNumber;
        appendedWireTypes[idx] = wireType;
        return idx;
    }

    private void shadowEarlierOccurrences(int fieldNumber, int activeIdx) {
        for (int i = 0; i < activeIdx; i++) {
            if (tapeFieldNumber(i) == fieldNumber && tapeFlags(i) != FLAG_DELETED) {
                setTapeFlags(i, FLAG_SHADOWED);
            }
        }
    }

    public ProtoDocument setInt32(int fieldNumber, int value) {
        int idx = findTapeIdx(fieldNumber);
        long bit = 1L << (fieldNumber & 63);
        if (idx < 0 || tapeWireType(idx) != WireFormat.WIRETYPE_VARINT) {
            if (idx >= 0) {
                remove(fieldNumber);
            }
            int appIdx = appendEntrySlot(fieldNumber, (byte) WireFormat.WIRETYPE_VARINT);
            appendedPrimitives[appIdx] = value;
            appendedBytes[appIdx] = null;
            appendedChildDocs[appIdx] = null;
            appendedMask |= bit;
            varSizeDirtyMask |= bit;
            bubbleVarSizeDirtyToParent();
            return this;
        }
        shadowEarlierOccurrences(fieldNumber, idx);
        ensureMutationArrays();
        mutatedPrimitives[idx] = value;
        int oldLen = tapeValueLength(idx);
        int newLen = WireWriter.varint32Size(value);
        if (oldLen == newLen && tapeFlags(idx) != FLAG_DIRTY_VAR_SIZE) {
            setTapeFlags(idx, FLAG_DIRTY_SAME_SIZE);
            sameSizeDirtyMask |= bit;
            invalidateSizeCacheUpward();
            bubbleSameSizeDirtyToParent();
        } else {
            setTapeFlags(idx, FLAG_DIRTY_VAR_SIZE);
            varSizeDirtyMask |= bit;
            bubbleVarSizeDirtyToParent();
        }
        return this;
    }

    public ProtoDocument setInt64(int fieldNumber, long value) {
        int idx = findTapeIdx(fieldNumber);
        long bit = 1L << (fieldNumber & 63);
        if (idx < 0 || tapeWireType(idx) != WireFormat.WIRETYPE_VARINT) {
            if (idx >= 0) {
                remove(fieldNumber);
            }
            int appIdx = appendEntrySlot(fieldNumber, (byte) WireFormat.WIRETYPE_VARINT);
            appendedPrimitives[appIdx] = value;
            appendedBytes[appIdx] = null;
            appendedChildDocs[appIdx] = null;
            appendedMask |= bit;
            varSizeDirtyMask |= bit;
            bubbleVarSizeDirtyToParent();
            return this;
        }
        shadowEarlierOccurrences(fieldNumber, idx);
        ensureMutationArrays();
        mutatedPrimitives[idx] = value;
        int oldLen = tapeValueLength(idx);
        int newLen = WireWriter.varint64Size(value);
        if (oldLen == newLen && tapeFlags(idx) != FLAG_DIRTY_VAR_SIZE) {
            setTapeFlags(idx, FLAG_DIRTY_SAME_SIZE);
            sameSizeDirtyMask |= bit;
            invalidateSizeCacheUpward();
            bubbleSameSizeDirtyToParent();
        } else {
            setTapeFlags(idx, FLAG_DIRTY_VAR_SIZE);
            varSizeDirtyMask |= bit;
            bubbleVarSizeDirtyToParent();
        }
        return this;
    }

    public ProtoDocument setFixed64(int fieldNumber, long value) {
        int idx = findTapeIdx(fieldNumber);
        long bit = 1L << (fieldNumber & 63);
        if (idx < 0 || tapeWireType(idx) != WireFormat.WIRETYPE_FIXED64) {
            if (idx >= 0) {
                remove(fieldNumber);
            }
            int appIdx = appendEntrySlot(fieldNumber, (byte) WireFormat.WIRETYPE_FIXED64);
            appendedPrimitives[appIdx] = value;
            appendedBytes[appIdx] = null;
            appendedChildDocs[appIdx] = null;
            appendedMask |= bit;
            varSizeDirtyMask |= bit;
            bubbleVarSizeDirtyToParent();
            return this;
        }
        shadowEarlierOccurrences(fieldNumber, idx);
        ensureMutationArrays();
        mutatedPrimitives[idx] = value;
        setTapeFlags(idx, FLAG_DIRTY_SAME_SIZE);
        sameSizeDirtyMask |= bit;
        invalidateSizeCacheUpward();
        bubbleSameSizeDirtyToParent();
        return this;
    }

    public ProtoDocument setString(int fieldNumber, String value) {
        byte[] utf8 = value.getBytes(StandardCharsets.UTF_8);
        int idx = findTapeIdx(fieldNumber);
        long bit = 1L << (fieldNumber & 63);
        if (idx < 0 || tapeWireType(idx) != WireFormat.WIRETYPE_LENGTH_DELIMITED) {
            if (idx >= 0) {
                remove(fieldNumber);
            }
            int appIdx = appendEntrySlot(fieldNumber, (byte) WireFormat.WIRETYPE_LENGTH_DELIMITED);
            appendedBytes[appIdx] = utf8;
            appendedChildDocs[appIdx] = null;
            appendedMask |= bit;
            varSizeDirtyMask |= bit;
            bubbleVarSizeDirtyToParent();
            return this;
        }
        shadowEarlierOccurrences(fieldNumber, idx);
        ensureMutationArrays();
        mutatedBytes[idx] = utf8;
        childDocs[idx] = null;
        int oldLen = tapeValueLength(idx);
        if (oldLen == utf8.length && tapeFlags(idx) != FLAG_DIRTY_VAR_SIZE) {
            setTapeFlags(idx, FLAG_DIRTY_SAME_SIZE);
            sameSizeDirtyMask |= bit;
            invalidateSizeCacheUpward();
            bubbleSameSizeDirtyToParent();
        } else {
            setTapeFlags(idx, FLAG_DIRTY_VAR_SIZE);
            varSizeDirtyMask |= bit;
            bubbleVarSizeDirtyToParent();
        }
        return this;
    }

    public ProtoDocument remove(int fieldNumber) {
        ensureScanned();
        boolean changed = false;
        for (int i = 0; i < tapeCount; i++) {
            if (tapeFieldNumber(i) == fieldNumber && tapeFlags(i) != FLAG_DELETED) {
                setTapeFlags(i, FLAG_DELETED);
                hasDeletedEntries = true;
                changed = true;
            }
        }
        if (fieldNumber > 0 && fieldNumber <= DIRECT_FIELD_MAX) {
            directTapeIdx[fieldNumber] = -1;
        }
        int appIdx = findAppendedIdx(fieldNumber);
        if (appIdx >= 0) {
            int last = appendedCount - 1;
            if (appIdx != last) {
                appendedFields[appIdx] = appendedFields[last];
                appendedWireTypes[appIdx] = appendedWireTypes[last];
                appendedPrimitives[appIdx] = appendedPrimitives[last];
                appendedBytes[appIdx] = appendedBytes[last];
                appendedChildDocs[appIdx] = appendedChildDocs[last];
            }
            appendedBytes[last] = null;
            appendedChildDocs[last] = null;
            appendedCount--;
            if (appendedCount == 0) {
                appendedMask = 0L;
            }
            changed = true;
        }
        if (changed) {
            varSizeDirtyMask |= (1L << (fieldNumber & 63));
            bubbleVarSizeDirtyToParent();
        }
        return this;
    }

    @Override
    protected void applyInPlacePatches(byte[] target, int baseOffset) {
        for (int i = 0; i < tapeCount; i++) {
            if (tapeFlags(i) == FLAG_DIRTY_SAME_SIZE) {
                int wt = tapeWireType(i);
                int patchPos = tapeValueOffset(i) - baseOffset;
                if (wt == WireFormat.WIRETYPE_VARINT) {
                    WireWriter.patchVarint64At(target, patchPos, mutatedPrimitives[i]);
                } else if (wt == WireFormat.WIRETYPE_FIXED64) {
                    WireWriter.patchFixed64At(target, patchPos, mutatedPrimitives[i]);
                } else if (wt == WireFormat.WIRETYPE_FIXED32) {
                    WireWriter.patchFixed32At(target, patchPos, (int) mutatedPrimitives[i]);
                } else if (wt == WireFormat.WIRETYPE_LENGTH_DELIMITED) {
                    if (childDocs != null && childDocs[i] != null) {
                        childDocs[i].applyInPlacePatches(target, baseOffset);
                    } else if (mutatedBytes != null && mutatedBytes[i] != null) {
                        byte[] b = mutatedBytes[i];
                        WireWriter.patchBytesAt(target, patchPos, b, 0, b.length);
                    }
                }
            }
        }
    }

    @Override
    protected int computeDirtyEntrySize(int tapeIdx, int fieldNumber, int wireType) {
        int tagLen = WireWriter.tagSize(fieldNumber);
        if (wireType == WireFormat.WIRETYPE_VARINT) {
            return tagLen + WireWriter.varint64Size(mutatedPrimitives[tapeIdx]);
        } else if (wireType == WireFormat.WIRETYPE_FIXED64) {
            return tagLen + 8;
        } else if (wireType == WireFormat.WIRETYPE_FIXED32) {
            return tagLen + 4;
        } else if (wireType == WireFormat.WIRETYPE_LENGTH_DELIMITED) {
            if (childDocs != null && childDocs[tapeIdx] != null) {
                int childSize = childDocs[tapeIdx].getSerializedSize();
                return tagLen + WireWriter.varint32Size(childSize) + childSize;
            }
            byte[] b = mutatedBytes[tapeIdx];
            return tagLen + WireWriter.varint32Size(b.length) + b.length;
        }
        throw new IllegalStateException("Unsupported wireType " + wireType);
    }

    @Override
    protected void writeDirtyEntry(int tapeIdx, int fieldNumber, int wireType, WireWriter writer) {
        if (wireType == WireFormat.WIRETYPE_VARINT) {
            writer.writeTag(fieldNumber, WireFormat.WIRETYPE_VARINT);
            writer.writeVarint64(mutatedPrimitives[tapeIdx]);
        } else if (wireType == WireFormat.WIRETYPE_FIXED64) {
            writer.writeTag(fieldNumber, WireFormat.WIRETYPE_FIXED64);
            writer.writeFixed64(mutatedPrimitives[tapeIdx]);
        } else if (wireType == WireFormat.WIRETYPE_FIXED32) {
            writer.writeTag(fieldNumber, WireFormat.WIRETYPE_FIXED32);
            writer.writeFixed32((int) mutatedPrimitives[tapeIdx]);
        } else if (wireType == WireFormat.WIRETYPE_LENGTH_DELIMITED) {
            if (childDocs != null && childDocs[tapeIdx] != null) {
                ProtoDocument child = childDocs[tapeIdx];
                int childSize = child.getSerializedSize();
                writer.writeTag(fieldNumber, WireFormat.WIRETYPE_LENGTH_DELIMITED);
                writer.writeVarint32(childSize);
                child.writeSplicedTo(writer);
            } else {
                byte[] b = mutatedBytes[tapeIdx];
                writer.writeLengthDelimitedBytes(fieldNumber, b, 0, b.length);
            }
        }
    }

    @Override
    protected int computeAppendedFieldsSize() {
        int size = 0;
        for (int i = 0; i < appendedCount; i++) {
            int fn = appendedFields[i];
            int wt = appendedWireTypes[i];
            int tagLen = WireWriter.tagSize(fn);
            if (wt == WireFormat.WIRETYPE_VARINT) {
                size += tagLen + WireWriter.varint64Size(appendedPrimitives[i]);
            } else if (wt == WireFormat.WIRETYPE_FIXED64) {
                size += tagLen + 8;
            } else if (wt == WireFormat.WIRETYPE_FIXED32) {
                size += tagLen + 4;
            } else if (wt == WireFormat.WIRETYPE_LENGTH_DELIMITED) {
                if (appendedChildDocs[i] != null) {
                    ProtoDocument child = appendedChildDocs[i];
                    if (!child.isClean() || child.tapeCount() > 0 || child.appendedCount > 0) {
                        int cs = child.getSerializedSize();
                        size += tagLen + WireWriter.varint32Size(cs) + cs;
                    }
                } else if (appendedBytes[i] != null) {
                    byte[] b = appendedBytes[i];
                    size += tagLen + WireWriter.varint32Size(b.length) + b.length;
                }
            }
        }
        return size;
    }

    @Override
    protected void writeAppendedFields(WireWriter writer) {
        for (int i = 0; i < appendedCount; i++) {
            int fn = appendedFields[i];
            int wt = appendedWireTypes[i];
            if (wt == WireFormat.WIRETYPE_VARINT) {
                writer.writeTag(fn, WireFormat.WIRETYPE_VARINT);
                writer.writeVarint64(appendedPrimitives[i]);
            } else if (wt == WireFormat.WIRETYPE_FIXED64) {
                writer.writeTag(fn, WireFormat.WIRETYPE_FIXED64);
                writer.writeFixed64(appendedPrimitives[i]);
            } else if (wt == WireFormat.WIRETYPE_FIXED32) {
                writer.writeTag(fn, WireFormat.WIRETYPE_FIXED32);
                writer.writeFixed32((int) appendedPrimitives[i]);
            } else if (wt == WireFormat.WIRETYPE_LENGTH_DELIMITED) {
                if (appendedChildDocs[i] != null) {
                    ProtoDocument child = appendedChildDocs[i];
                    if (!child.isClean() || child.tapeCount() > 0 || child.appendedCount > 0) {
                        int cs = child.getSerializedSize();
                        writer.writeTag(fn, WireFormat.WIRETYPE_LENGTH_DELIMITED);
                        writer.writeVarint32(cs);
                        child.writeSplicedTo(writer);
                    }
                } else if (appendedBytes[i] != null) {
                    byte[] b = appendedBytes[i];
                    writer.writeLengthDelimitedBytes(fn, b, 0, b.length);
                }
            }
        }
    }

    @Override
    public ProtoDocument copy() {
        ProtoDocument c = new ProtoDocument();
        c.copyFrom(this);
        return c;
    }

    public void copyFrom(ProtoDocument src) {
        copyBaseFrom(src);
        System.arraycopy(src.directTapeIdx, 0, this.directTapeIdx, 0, this.directTapeIdx.length);
        if (src.mutatedPrimitives != null) {
            this.mutatedPrimitives = Arrays.copyOf(src.mutatedPrimitives, src.mutatedPrimitives.length);
        } else {
            this.mutatedPrimitives = null;
        }
        if (src.mutatedBytes != null) {
            this.mutatedBytes = Arrays.copyOf(src.mutatedBytes, src.mutatedBytes.length);
        } else {
            this.mutatedBytes = null;
        }
        if (src.childDocs != null) {
            this.childDocs = new ProtoDocument[src.childDocs.length];
            for (int i = 0; i < src.tapeCount && i < src.childDocs.length; i++) {
                ProtoDocument srcChild = src.childDocs[i];
                if (srcChild != null) {
                    int flags = src.tapeFlags(i);
                    if (flags != FLAG_SHADOWED && flags != FLAG_DELETED
                            && (!srcChild.isClean() || flags == FLAG_DIRTY_SAME_SIZE || flags == FLAG_DIRTY_VAR_SIZE)) {
                        ProtoDocument cloned = srcChild.copy();
                        int fn = src.tapeFieldNumber(i);
                        cloned.attachToParent(this, i, 1L << (fn & 63));
                        this.childDocs[i] = cloned;
                    }
                }
            }
        } else {
            this.childDocs = null;
        }
        this.appendedCount = src.appendedCount;
        if (src.appendedCount > 0) {
            this.appendedFields = Arrays.copyOf(src.appendedFields, src.appendedFields.length);
            this.appendedWireTypes = Arrays.copyOf(src.appendedWireTypes, src.appendedWireTypes.length);
            this.appendedPrimitives = Arrays.copyOf(src.appendedPrimitives, src.appendedPrimitives.length);
            this.appendedBytes = Arrays.copyOf(src.appendedBytes, src.appendedBytes.length);
            this.appendedChildDocs = new ProtoDocument[src.appendedChildDocs.length];
            for (int i = 0; i < src.appendedCount; i++) {
                if (src.appendedChildDocs[i] != null) {
                    ProtoDocument cloned = src.appendedChildDocs[i].copy();
                    cloned.attachToParent(this, -1, 1L << (src.appendedFields[i] & 63));
                    this.appendedChildDocs[i] = cloned;
                }
            }
        }
    }

    @Override
    public Message getDefaultInstanceForType() {
        return new ProtoDocument();
    }

    @Override
    public Builder newBuilderForType() {
        return newBuilder();
    }

    @Override
    public Builder toBuilder() {
        return new Builder(this.copy());
    }

    public static final class Builder extends AbstractLazyBuilder<ProtoDocument, Builder> {
        private Builder(ProtoDocument initialImpl) {
            super(initialImpl);
        }

        public boolean has(int fieldNumber) { return impl.has(fieldNumber); }
        public int getInt32(int fieldNumber) { return impl.getInt32(fieldNumber); }
        public long getInt64(int fieldNumber) { return impl.getInt64(fieldNumber); }
        public long getFixed64(int fieldNumber) { return impl.getFixed64(fieldNumber); }
        public Utf8Slice getSlice(int fieldNumber) { return impl.getSlice(fieldNumber); }
        public String getString(int fieldNumber) { return impl.getString(fieldNumber); }

        public Builder setInt32(int fieldNumber, int value) {
            ensureMutable();
            impl.setInt32(fieldNumber, value);
            return this;
        }

        public Builder setInt64(int fieldNumber, long value) {
            ensureMutable();
            impl.setInt64(fieldNumber, value);
            return this;
        }

        public Builder setFixed64(int fieldNumber, long value) {
            ensureMutable();
            impl.setFixed64(fieldNumber, value);
            return this;
        }

        public Builder setString(int fieldNumber, String value) {
            ensureMutable();
            impl.setString(fieldNumber, value);
            return this;
        }

        public Builder remove(int fieldNumber) {
            ensureMutable();
            impl.remove(fieldNumber);
            return this;
        }

        public ProtoDocument at(int fieldNumber) {
            ensureMutable();
            return impl.at(fieldNumber);
        }

        @Override
        public Builder clone() {
            return new Builder(impl.copy());
        }
    }
}
