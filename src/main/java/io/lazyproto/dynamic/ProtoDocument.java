package io.lazyproto.dynamic;

import com.google.protobuf.Message;
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
 * <p>Allows reading, mutating, and deleting arbitrary fields by field number path without compiling
 * a {@code .proto} schema, while preserving all unvisited fields and reusing input bytes on serialization.
 */
public final class ProtoDocument extends AbstractLazyMessage<ProtoDocument> {
    private static final int DIRECT_FIELD_MAX = 64;

    // Fast O(1) direct lookup table for field numbers 1..64 -> tapeIdx
    private final int[] directTapeIdx = new int[DIRECT_FIELD_MAX + 1];

    // Modified values indexed by tapeIdx (lazily allocated only when mutated)
    private long[] mutatedPrimitives;
    private byte[][] mutatedBytes;
    private ProtoDocument[] childDocs;
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

    @Override
    protected void resetSlots() {
        if (presentMask != 0L) {
            long mask = presentMask;
            while (mask != 0L) {
                int bit = Long.numberOfTrailingZeros(mask);
                if (bit <= DIRECT_FIELD_MAX) {
                    directTapeIdx[bit] = -1;
                }
                mask &= (mask - 1);
            }
        }
        if (childDocs != null) {
            Arrays.fill(childDocs, null);
        }
    }

    @Override
    protected void onScanField(int fieldNumber, int wireType, int tapeIdx) {
        if (fieldNumber <= DIRECT_FIELD_MAX) {
            int prevIdx = directTapeIdx[fieldNumber];
            if (prevIdx >= 0 && wireType != WireFormat.WIRETYPE_LENGTH_DELIMITED) {
                // Protobuf Last-Write-Wins for duplicate non-repeated scalar fields
                setTapeFlags(prevIdx, FLAG_SHADOWED);
            }
            directTapeIdx[fieldNumber] = tapeIdx;
            presentMask |= (1L << (fieldNumber & 63));
        }
    }

    private int findTapeIdx(int fieldNumber) {
        ensureScanned();
        if (fieldNumber > 0 && fieldNumber <= DIRECT_FIELD_MAX) {
            return directTapeIdx[fieldNumber];
        }
        for (int i = tapeCount - 1; i >= 0; i--) {
            if (tapeFieldNumber(i) == fieldNumber && tapeFlags(i) != FLAG_DELETED && tapeFlags(i) != FLAG_SHADOWED) {
                return i;
            }
        }
        return -1;
    }

    public boolean has(int fieldNumber) {
        return findTapeIdx(fieldNumber) >= 0;
    }

    public int getInt32(int fieldNumber) {
        int idx = findTapeIdx(fieldNumber);
        if (idx < 0) return 0;
        if (tapeFlags(idx) == FLAG_DIRTY_SAME_SIZE || tapeFlags(idx) == FLAG_DIRTY_VAR_SIZE) {
            return (int) mutatedPrimitives[idx];
        }
        return WireReader.readVarint32At(buf, tapeValueOffset(idx));
    }

    public long getInt64(int fieldNumber) {
        int idx = findTapeIdx(fieldNumber);
        if (idx < 0) return 0L;
        if (tapeFlags(idx) == FLAG_DIRTY_SAME_SIZE || tapeFlags(idx) == FLAG_DIRTY_VAR_SIZE) {
            return mutatedPrimitives[idx];
        }
        return WireReader.readVarint64At(buf, tapeValueOffset(idx));
    }

    public long getFixed64(int fieldNumber) {
        int idx = findTapeIdx(fieldNumber);
        if (idx < 0) return 0L;
        if (tapeFlags(idx) == FLAG_DIRTY_SAME_SIZE || tapeFlags(idx) == FLAG_DIRTY_VAR_SIZE) {
            return mutatedPrimitives[idx];
        }
        return WireReader.readFixed64At(buf, tapeValueOffset(idx));
    }

    public Utf8Slice getSlice(int fieldNumber) {
        int idx = findTapeIdx(fieldNumber);
        if (idx < 0) return reusableSlice.wrap(buf, 0, 0);
        if (mutatedBytes != null && idx < mutatedBytes.length && mutatedBytes[idx] != null) {
            byte[] b = mutatedBytes[idx];
            return reusableSlice.wrap(b, 0, b.length);
        }
        return reusableSlice.wrap(buf, tapeValueOffset(idx), tapeValueLength(idx));
    }

    public String getString(int fieldNumber) {
        int idx = findTapeIdx(fieldNumber);
        if (idx < 0) return "";
        if (mutatedBytes != null && idx < mutatedBytes.length && mutatedBytes[idx] != null) {
            byte[] b = mutatedBytes[idx];
            return WireReader.readStringAt(b, 0, b.length);
        }
        return WireReader.readStringAt(buf, tapeValueOffset(idx), tapeValueLength(idx));
    }

    /**
     * Navigates lazily to a nested sub-message by field number (like {@code jsoniter.Any.get(fieldNum)}).
     */
    public ProtoDocument at(int fieldNumber) {
        int idx = findTapeIdx(fieldNumber);
        if (idx < 0) {
            throw new IllegalArgumentException("Field " + fieldNumber + " not found");
        }
        ensureMutationArrays();
        if (childDocs[idx] == null) {
            ProtoDocument child = new ProtoDocument();
            child.wrap(buf, tapeValueOffset(idx), tapeValueLength(idx));
            child.attachToParent(this, idx, 1L << (fieldNumber & 63));
            childDocs[idx] = child;
        }
        return childDocs[idx];
    }

    private void ensureMutationArrays() {
        int cap = Math.max(tapeCount, 16);
        if (mutatedPrimitives == null || mutatedPrimitives.length < cap) {
            mutatedPrimitives = mutatedPrimitives == null ? new long[cap] : Arrays.copyOf(mutatedPrimitives, cap);
            mutatedBytes = mutatedBytes == null ? new byte[cap][] : Arrays.copyOf(mutatedBytes, cap);
            childDocs = childDocs == null ? new ProtoDocument[cap] : Arrays.copyOf(childDocs, cap);
        }
    }

    public ProtoDocument setInt32(int fieldNumber, int value) {
        int idx = findTapeIdx(fieldNumber);
        if (idx < 0) {
            throw new IllegalArgumentException("Field " + fieldNumber + " not present in tape");
        }
        ensureMutationArrays();
        mutatedPrimitives[idx] = value;
        long bit = 1L << (fieldNumber & 63);
        int oldLen = tapeValueLength(idx);
        int newLen = WireWriter.varint32Size(value);
        if (oldLen == newLen && tapeFlags(idx) != FLAG_DIRTY_VAR_SIZE) {
            setTapeFlags(idx, FLAG_DIRTY_SAME_SIZE);
            sameSizeDirtyMask |= bit;
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
        if (idx < 0) {
            throw new IllegalArgumentException("Field " + fieldNumber + " not present in tape");
        }
        ensureMutationArrays();
        mutatedPrimitives[idx] = value;
        long bit = 1L << (fieldNumber & 63);
        setTapeFlags(idx, FLAG_DIRTY_SAME_SIZE);
        sameSizeDirtyMask |= bit;
        bubbleSameSizeDirtyToParent();
        return this;
    }

    public ProtoDocument setString(int fieldNumber, String value) {
        int idx = findTapeIdx(fieldNumber);
        if (idx < 0) {
            throw new IllegalArgumentException("Field " + fieldNumber + " not present in tape");
        }
        ensureMutationArrays();
        byte[] utf8 = value.getBytes(StandardCharsets.UTF_8);
        mutatedBytes[idx] = utf8;
        long bit = 1L << (fieldNumber & 63);
        int oldLen = tapeValueLength(idx);
        if (oldLen == utf8.length && tapeFlags(idx) != FLAG_DIRTY_VAR_SIZE) {
            setTapeFlags(idx, FLAG_DIRTY_SAME_SIZE);
            sameSizeDirtyMask |= bit;
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
        for (int i = 0; i < tapeCount; i++) {
            if (tapeFieldNumber(i) == fieldNumber) {
                setTapeFlags(i, FLAG_DELETED);
            }
        }
        if (fieldNumber <= DIRECT_FIELD_MAX) {
            directTapeIdx[fieldNumber] = -1;
        }
        hasDeletedEntries = true;
        varSizeDirtyMask |= (1L << (fieldNumber & 63));
        bubbleVarSizeDirtyToParent();
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
        return 0;
    }

    @Override
    protected void writeAppendedFields(WireWriter writer) {
    }

    @Override
    public Message getDefaultInstanceForType() {
        return new ProtoDocument();
    }

    @Override
    public Message.Builder newBuilderForType() {
        return new ProtoDocument();
    }

    @Override
    public Message.Builder toBuilder() {
        return this;
    }
}
