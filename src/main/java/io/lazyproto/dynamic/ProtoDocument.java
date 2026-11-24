package io.lazyproto.dynamic;

import com.google.protobuf.Message;
import io.lazyproto.runtime.AbstractLazyBuilder;
import io.lazyproto.runtime.AbstractLazyMessage;
import io.lazyproto.runtime.ImmutableWireBase;
import io.lazyproto.runtime.WireMergeEngine;
import io.lazyproto.runtime.WireOverlay;
import io.lazyproto.wire.Utf8Slice;
import io.lazyproto.wire.WireFormat;
import io.lazyproto.wire.WireReader;
import io.lazyproto.wire.WireWriter;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Schema-less lazy Protobuf document (Architecture v3: Immutable {@link DocBase} + External COW {@link DocOverlay}).
 *
 * <p>{@link ProtoDocument} itself is strictly immutable and exposes only read &amp; serialization methods.
 * All mutations occur through {@link ProtoDocument.Builder} into external COW overlays, and read-only path
 * navigation via {@link #at(int)} never mutates presence or allocates missing slots.
 */
public final class ProtoDocument extends AbstractLazyMessage<ProtoDocument, ProtoDocument.DocBase, ProtoDocument.DocOverlay> {

    private static final int DIRECT_FIELD_MAX = 64;
    private static final byte STATE_PATCHED_SCALAR = 1;
    private static final byte STATE_DELETED        = 2;
    private static final byte STATE_SUBDOC         = 3;

    public static final ProtoDocument EMPTY = new ProtoDocument(DocBase.EMPTY, null);

    private final Utf8Slice reusableSlice = new Utf8Slice();

    private ProtoDocument(DocBase base, DocOverlay overlay) {
        super(base, overlay);
    }

    public static ProtoDocument getDefaultInstance() {
        return EMPTY;
    }

    public static ProtoDocument wrapBytes(byte[] data) {
        return wrapBytes(data, 0, data.length);
    }

    public static ProtoDocument wrapBytes(byte[] data, int offset, int length) {
        if (length == 0) {
            return EMPTY;
        }
        DocBase b = new DocBase();
        b.wrapImmutable(data, offset, length);
        return new ProtoDocument(b, null);
    }

    public static Builder newBuilder() {
        DocBase b = new DocBase();
        b.markScannedEmpty();
        b.freeze();
        return new Builder(b, null);
    }

    @Override
    protected WireMergeEngine.Adapter<DocBase, DocOverlay> mergeAdapter() {
        return DocAdapter.INSTANCE;
    }

    // ==================== Read API (Fuses Immutable Base + External Overlay) ====================

    public boolean has(int fieldNumber) {
        return DocOps.has(base, overlay, fieldNumber);
    }

    public int getInt32(int fieldNumber) {
        return DocOps.getInt32(base, overlay, fieldNumber);
    }

    public long getInt64(int fieldNumber) {
        return DocOps.getInt64(base, overlay, fieldNumber);
    }

    public long getFixed64(int fieldNumber) {
        return DocOps.getFixed64(base, overlay, fieldNumber);
    }

    public Utf8Slice getSlice(int fieldNumber) {
        return DocOps.getSlice(base, overlay, fieldNumber, reusableSlice);
    }

    public String getString(int fieldNumber) {
        return DocOps.getString(base, overlay, fieldNumber);
    }

    /**
     * Purely read-only navigation to a nested sub-message. Missing paths return {@link #EMPTY}
     * without allocating or mutating parent presence. Appended length-delimited byte payloads are
     * preserved and wrapped lazily as read-only sub-documents.
     */
    public ProtoDocument at(int fieldNumber) {
        return DocOps.atReadOnly(base, overlay, fieldNumber);
    }

    @Override
    public Message getDefaultInstanceForType() {
        return EMPTY;
    }

    @Override
    public Builder newBuilderForType() {
        return newBuilder();
    }

    @Override
    public Builder toBuilder() {
        return new Builder(base, overlay != null ? overlay.copyMutable() : null);
    }

    // ==================== Immutable DocBase (WireTape) ====================

    public static final class DocBase extends ImmutableWireBase {
        public static final DocBase EMPTY = new DocBase(true);

        private final int[] directLastTapeIdx = new int[DIRECT_FIELD_MAX + 1];
        private DocBase[] cachedSubDocs;
        private int[] cachedSubDocFieldNums;
        private int cachedSubDocCount;

        public DocBase() {
            super(16);
            Arrays.fill(directLastTapeIdx, -1);
        }

        private DocBase(boolean emptyFrozen) {
            super(0);
            Arrays.fill(directLastTapeIdx, -1);
            markScannedEmpty();
            freeze();
        }

        @Override
        protected void onFreeze() {
            for (int i = 0; i < cachedSubDocCount; i++) {
                if (cachedSubDocs[i] != null) {
                    cachedSubDocs[i].freeze();
                }
            }
        }

        @Override
        protected void resetBaseSlots() {
            Arrays.fill(directLastTapeIdx, -1);
            for (int i = 0; i < cachedSubDocCount; i++) {
                DocBase sub = cachedSubDocs[i];
                if (sub != null) {
                    if (sub.isFrozen()) {
                        cachedSubDocs[i] = null;
                    } else {
                        sub.resetForReuse(EMPTY_BYTES, 0, 0);
                    }
                }
            }
            cachedSubDocCount = 0;
        }

        @Override
        protected void onScanField(int fieldNumber, int wireType, int tapeIdx) {
            // Schema-less mode: NEVER auto-shadow earlier entries during scan so unpacked repeated
            // fields and split sub-messages remain intact.
            if (fieldNumber > 0 && fieldNumber <= DIRECT_FIELD_MAX) {
                directLastTapeIdx[fieldNumber] = tapeIdx;
            }
        }

        public int findLastTapeIdx(int fieldNumber) {
            ensureScanned();
            if (fieldNumber > 0 && fieldNumber <= DIRECT_FIELD_MAX) {
                return directLastTapeIdx[fieldNumber];
            }
            for (int i = tapeCount - 1; i >= 0; i--) {
                if (tapeFieldNumber(i) == fieldNumber) {
                    return i;
                }
            }
            return -1;
        }

        public int findLastLenTapeIdx(int fieldNumber) {
            ensureScanned();
            for (int i = tapeCount - 1; i >= 0; i--) {
                if (tapeFieldNumber(i) == fieldNumber && tapeWireType(i) == WireFormat.WIRETYPE_LENGTH_DELIMITED) {
                    return i;
                }
            }
            return -1;
        }

        public int countLenOccurrences(int fieldNumber) {
            ensureScanned();
            int c = 0;
            for (int i = 0; i < tapeCount; i++) {
                if (tapeFieldNumber(i) == fieldNumber && tapeWireType(i) == WireFormat.WIRETYPE_LENGTH_DELIMITED) {
                    c++;
                }
            }
            return c;
        }

        public DocBase buildOrGetSubBase(int fieldNumber) {
            ensureScanned();
            for (int i = 0; i < cachedSubDocCount; i++) {
                if (cachedSubDocFieldNums[i] == fieldNumber && cachedSubDocs[i] != null) {
                    return cachedSubDocs[i];
                }
            }
            int segCount = countLenOccurrences(fieldNumber);
            if (segCount == 0) {
                return EMPTY;
            }
            DocBase sub = new DocBase();
            if (segCount == 1) {
                int idx = findLastLenTapeIdx(fieldNumber);
                sub.resetForReuse(buf, tapeValueOffset(idx), tapeValueLength(idx));
            } else {
                int[] segs = new int[segCount];
                int k = 0;
                for (int i = 0; i < tapeCount; i++) {
                    if (tapeFieldNumber(i) == fieldNumber && tapeWireType(i) == WireFormat.WIRETYPE_LENGTH_DELIMITED) {
                        segs[k++] = i;
                    }
                }
                sub.wrapMultiSegments(buf, tape, segs, segCount);
            }
            if (this.frozen) {
                sub.freeze();
            }
            if (cachedSubDocs == null) {
                cachedSubDocs = new DocBase[4];
                cachedSubDocFieldNums = new int[4];
            } else if (cachedSubDocCount >= cachedSubDocs.length) {
                cachedSubDocs = Arrays.copyOf(cachedSubDocs, cachedSubDocs.length << 1);
                cachedSubDocFieldNums = Arrays.copyOf(cachedSubDocFieldNums, cachedSubDocFieldNums.length << 1);
            }
            cachedSubDocFieldNums[cachedSubDocCount] = fieldNumber;
            cachedSubDocs[cachedSubDocCount++] = sub;
            return sub;
        }
    }

    // ==================== External COW DocOverlay ====================

    public static final class DocSlot {
        DocBase base;
        DocOverlay overlay;

        DocSlot(DocBase base, DocOverlay overlay) {
            this.base = base;
            this.overlay = overlay;
        }

        boolean isEffectivelyEmpty() {
            return (base == null || base.rawLength() == 0)
                    && (overlay == null || overlay.isEffectivelyEmpty());
        }

        void freeze() {
            if (base != null) base.freeze();
            if (overlay != null) overlay.freeze();
        }

        DocSlot shallowShare() {
            if (base != null) base.freeze();
            if (overlay != null) overlay.freeze();
            return new DocSlot(base, overlay);
        }
    }

    public static final class DocOverlay extends WireOverlay {
        private long bloomMask;
        private int[] fields = new int[4];
        private byte[] states = new byte[4];
        private byte[] wireTypes = new byte[4];
        private long[] primitives = new long[4];
        private byte[][] bytesVals = new byte[4][];
        private DocSlot[] subDocs = new DocSlot[4];
        private int count;

        @Override
        protected void onFreezeChildren() {
            for (int i = 0; i < count; i++) {
                if (states[i] == STATE_SUBDOC && subDocs[i] != null) {
                    subDocs[i].freeze();
                }
            }
        }

        public void reset() {
            this.bloomMask = 0L;
            Arrays.fill(bytesVals, 0, count, null);
            Arrays.fill(subDocs, 0, count, null);
            this.count = 0;
            resetMasksAndCache();
        }

        public DocOverlay copyMutable() {
            DocOverlay c = new DocOverlay();
            c.copyMasksFrom(this);
            c.bloomMask = this.bloomMask;
            c.count = this.count;
            if (this.count > c.fields.length) {
                int cap = this.fields.length;
                c.fields = Arrays.copyOf(this.fields, cap);
                c.states = Arrays.copyOf(this.states, cap);
                c.wireTypes = Arrays.copyOf(this.wireTypes, cap);
                c.primitives = Arrays.copyOf(this.primitives, cap);
                c.bytesVals = Arrays.copyOf(this.bytesVals, cap);
                c.subDocs = new DocSlot[cap];
            } else {
                System.arraycopy(this.fields, 0, c.fields, 0, this.count);
                System.arraycopy(this.states, 0, c.states, 0, this.count);
                System.arraycopy(this.wireTypes, 0, c.wireTypes, 0, this.count);
                System.arraycopy(this.primitives, 0, c.primitives, 0, this.count);
                System.arraycopy(this.bytesVals, 0, c.bytesVals, 0, this.count);
            }
            for (int i = 0; i < this.count; i++) {
                if (this.states[i] == STATE_SUBDOC && this.subDocs[i] != null) {
                    c.subDocs[i] = this.subDocs[i].shallowShare();
                }
            }
            return c;
        }

        int findEntry(int fieldNumber) {
            if (count == 0 || ((bloomMask >>> (fieldNumber & 63)) & 1L) == 0L) {
                return -1;
            }
            for (int i = 0; i < count; i++) {
                if (fields[i] == fieldNumber) {
                    return i;
                }
            }
            return -1;
        }

        int getOrAllocEntry(int fieldNumber) {
            int existing = findEntry(fieldNumber);
            if (existing >= 0) {
                return existing;
            }
            if (count >= fields.length) {
                int newCap = fields.length << 1;
                fields = Arrays.copyOf(fields, newCap);
                states = Arrays.copyOf(states, newCap);
                wireTypes = Arrays.copyOf(wireTypes, newCap);
                primitives = Arrays.copyOf(primitives, newCap);
                bytesVals = Arrays.copyOf(bytesVals, newCap);
                subDocs = Arrays.copyOf(subDocs, newCap);
            }
            int idx = count++;
            fields[idx] = fieldNumber;
            bloomMask |= (1L << (fieldNumber & 63));
            return idx;
        }

        boolean isEffectivelyEmpty() {
            if (count == 0) return true;
            for (int i = 0; i < count; i++) {
                byte st = states[i];
                if (st == STATE_SUBDOC) {
                    DocSlot slot = subDocs[i];
                    if (slot != null && slot.overlay != null && !slot.overlay.isEffectivelyEmpty()) {
                        return false;
                    }
                } else {
                    return false;
                }
            }
            return true;
        }

        void recomputeMasks(DocBase base) {
            long same = 0L;
            long var = 0L;
            long del = 0L;
            long app = 0L;
            for (int i = 0; i < count; i++) {
                int fn = fields[i];
                byte st = states[i];
                if (st == STATE_DELETED) {
                    if (base.findLastTapeIdx(fn) >= 0) {
                        del = 1L;
                        var = 1L;
                    }
                    continue;
                }
                if (st == STATE_SUBDOC) {
                    DocSlot slot = subDocs[i];
                    if (slot == null || slot.overlay == null || slot.overlay.isEffectivelyEmpty()) {
                        continue;
                    }
                    int lenCount = base.countLenOccurrences(fn);
                    if (lenCount == 1 && slot.base != null && !slot.base.isMultiSegment()
                            && slot.overlay.isSameSizeDirtyOnly(slot.base)) {
                        same = 1L;
                    } else if (lenCount >= 1) {
                        var = 1L;
                    } else {
                        if (base.findLastTapeIdx(fn) >= 0) {
                            del = 1L;
                            var = 1L;
                        }
                        app = 1L;
                    }
                    continue;
                }
                // STATE_PATCHED_SCALAR
                int lastIdx = base.findLastTapeIdx(fn);
                if (lastIdx >= 0 && base.tapeWireType(lastIdx) == wireTypes[i]) {
                    int occurrences = countAllOccurrences(base, fn);
                    int wt = wireTypes[i];
                    int newLen = switch (wt) {
                        case WireFormat.WIRETYPE_VARINT -> WireWriter.varint64Size(primitives[i]);
                        case WireFormat.WIRETYPE_FIXED64 -> 8;
                        case WireFormat.WIRETYPE_FIXED32 -> 4;
                        case WireFormat.WIRETYPE_LENGTH_DELIMITED -> bytesVals[i].length;
                        default -> -1;
                    };
                    if (occurrences == 1 && base.tapeValueLength(lastIdx) == newLen) {
                        same = 1L;
                    } else {
                        var = 1L;
                    }
                } else {
                    if (lastIdx >= 0) {
                        del = 1L;
                        var = 1L;
                    }
                    app = 1L;
                }
            }
            this.sameSizeMask = same;
            this.varSizeMask = var;
            this.deletedMask = del;
            this.appendedMask = app;
            this.cachedSerializedSize = -1;
        }

        private static int countAllOccurrences(DocBase base, int fn) {
            int c = 0;
            int n = base.tapeCount();
            for (int i = 0; i < n; i++) {
                if (base.tapeFieldNumber(i) == fn) {
                    c++;
                }
            }
            return c;
        }
    }

    // ==================== Shared Fused Read & Merge Logic ====================

    private static final class DocOps {
        static boolean has(DocBase base, DocOverlay ov, int fieldNumber) {
            if (ov != null) {
                int idx = ov.findEntry(fieldNumber);
                if (idx >= 0) {
                    byte st = ov.states[idx];
                    if (st == STATE_DELETED) return false;
                    if (st == STATE_PATCHED_SCALAR) return true;
                    if (st == STATE_SUBDOC) {
                        DocSlot slot = ov.subDocs[idx];
                        if (slot != null && (!slot.isEffectivelyEmpty() || base.countLenOccurrences(fieldNumber) > 0)) {
                            return true;
                        }
                        return base.findLastTapeIdx(fieldNumber) >= 0;
                    }
                }
            }
            return base.findLastTapeIdx(fieldNumber) >= 0;
        }

        static int getInt32(DocBase base, DocOverlay ov, int fieldNumber) {
            if (ov != null) {
                int idx = ov.findEntry(fieldNumber);
                if (idx >= 0) {
                    byte st = ov.states[idx];
                    if (st == STATE_DELETED) return 0;
                    if (st == STATE_PATCHED_SCALAR) {
                        return ov.wireTypes[idx] == WireFormat.WIRETYPE_VARINT ? (int) ov.primitives[idx] : 0;
                    }
                    if (st == STATE_SUBDOC) return 0;
                }
            }
            int tapeIdx = base.findLastTapeIdx(fieldNumber);
            if (tapeIdx < 0 || base.tapeWireType(tapeIdx) != WireFormat.WIRETYPE_VARINT) {
                return 0;
            }
            return WireReader.readVarint32At(base.rawBuffer(), base.tapeValueOffset(tapeIdx));
        }

        static long getInt64(DocBase base, DocOverlay ov, int fieldNumber) {
            if (ov != null) {
                int idx = ov.findEntry(fieldNumber);
                if (idx >= 0) {
                    byte st = ov.states[idx];
                    if (st == STATE_DELETED) return 0L;
                    if (st == STATE_PATCHED_SCALAR) {
                        return ov.wireTypes[idx] == WireFormat.WIRETYPE_VARINT ? ov.primitives[idx] : 0L;
                    }
                    if (st == STATE_SUBDOC) return 0L;
                }
            }
            int tapeIdx = base.findLastTapeIdx(fieldNumber);
            if (tapeIdx < 0 || base.tapeWireType(tapeIdx) != WireFormat.WIRETYPE_VARINT) {
                return 0L;
            }
            return WireReader.readVarint64At(base.rawBuffer(), base.tapeValueOffset(tapeIdx));
        }

        static long getFixed64(DocBase base, DocOverlay ov, int fieldNumber) {
            if (ov != null) {
                int idx = ov.findEntry(fieldNumber);
                if (idx >= 0) {
                    byte st = ov.states[idx];
                    if (st == STATE_DELETED) return 0L;
                    if (st == STATE_PATCHED_SCALAR) {
                        return ov.wireTypes[idx] == WireFormat.WIRETYPE_FIXED64 ? ov.primitives[idx] : 0L;
                    }
                    if (st == STATE_SUBDOC) return 0L;
                }
            }
            int tapeIdx = base.findLastTapeIdx(fieldNumber);
            if (tapeIdx < 0 || base.tapeWireType(tapeIdx) != WireFormat.WIRETYPE_FIXED64) {
                return 0L;
            }
            return WireReader.readFixed64At(base.rawBuffer(), base.tapeValueOffset(tapeIdx));
        }

        static Utf8Slice getSlice(DocBase base, DocOverlay ov, int fieldNumber, Utf8Slice slice) {
            if (ov != null) {
                int idx = ov.findEntry(fieldNumber);
                if (idx >= 0) {
                    byte st = ov.states[idx];
                    if (st == STATE_DELETED || st == STATE_SUBDOC) {
                        return slice.wrap(ImmutableWireBase.EMPTY_BYTES, 0, 0);
                    }
                    if (st == STATE_PATCHED_SCALAR) {
                        if (ov.wireTypes[idx] == WireFormat.WIRETYPE_LENGTH_DELIMITED && ov.bytesVals[idx] != null) {
                            byte[] b = ov.bytesVals[idx];
                            return slice.wrap(b, 0, b.length);
                        }
                        return slice.wrap(ImmutableWireBase.EMPTY_BYTES, 0, 0);
                    }
                }
            }
            int tapeIdx = base.findLastTapeIdx(fieldNumber);
            if (tapeIdx < 0 || base.tapeWireType(tapeIdx) != WireFormat.WIRETYPE_LENGTH_DELIMITED) {
                return slice.wrap(ImmutableWireBase.EMPTY_BYTES, 0, 0);
            }
            return slice.wrap(base.rawBuffer(), base.tapeValueOffset(tapeIdx), base.tapeValueLength(tapeIdx));
        }

        static String getString(DocBase base, DocOverlay ov, int fieldNumber) {
            if (ov != null) {
                int idx = ov.findEntry(fieldNumber);
                if (idx >= 0) {
                    byte st = ov.states[idx];
                    if (st == STATE_DELETED || st == STATE_SUBDOC) return "";
                    if (st == STATE_PATCHED_SCALAR) {
                        if (ov.wireTypes[idx] == WireFormat.WIRETYPE_LENGTH_DELIMITED && ov.bytesVals[idx] != null) {
                            byte[] b = ov.bytesVals[idx];
                            return WireReader.readStringAt(b, 0, b.length);
                        }
                        return "";
                    }
                }
            }
            int tapeIdx = base.findLastTapeIdx(fieldNumber);
            if (tapeIdx < 0 || base.tapeWireType(tapeIdx) != WireFormat.WIRETYPE_LENGTH_DELIMITED) {
                return "";
            }
            return WireReader.readStringAt(base.rawBuffer(), base.tapeValueOffset(tapeIdx), base.tapeValueLength(tapeIdx));
        }

        static ProtoDocument atReadOnly(DocBase base, DocOverlay ov, int fieldNumber) {
            if (ov != null) {
                int idx = ov.findEntry(fieldNumber);
                if (idx >= 0) {
                    byte st = ov.states[idx];
                    if (st == STATE_DELETED) {
                        return EMPTY;
                    }
                    if (st == STATE_PATCHED_SCALAR) {
                        if (ov.wireTypes[idx] == WireFormat.WIRETYPE_LENGTH_DELIMITED && ov.bytesVals[idx] != null) {
                            byte[] raw = ov.bytesVals[idx];
                            if (raw.length == 0) return EMPTY;
                            DocBase b = new DocBase();
                            b.wrapImmutable(raw, 0, raw.length);
                            return new ProtoDocument(b, null);
                        }
                        throw new IllegalArgumentException("Field " + fieldNumber + " is not length-delimited");
                    }
                    if (st == STATE_SUBDOC) {
                        DocSlot slot = ov.subDocs[idx];
                        if (slot == null || (slot.base == DocBase.EMPTY && slot.overlay == null)) {
                            return EMPTY;
                        }
                        DocBase b = slot.base != null ? slot.base : DocBase.EMPTY;
                        DocOverlay subOv = (slot.overlay != null && !slot.overlay.isEffectivelyEmpty())
                                ? slot.overlay.copyMutable() : null;
                        b.freeze();
                        if (subOv != null) subOv.freeze();
                        return new ProtoDocument(b, subOv);
                    }
                }
            }
            int lastIdx = base.findLastTapeIdx(fieldNumber);
            if (lastIdx >= 0 && base.tapeWireType(lastIdx) != WireFormat.WIRETYPE_LENGTH_DELIMITED
                    && base.countLenOccurrences(fieldNumber) == 0) {
                throw new IllegalArgumentException("Field " + fieldNumber + " is not length-delimited");
            }
            DocBase subBase = base.buildOrGetSubBase(fieldNumber);
            if (subBase == DocBase.EMPTY) {
                return EMPTY;
            }
            return new ProtoDocument(subBase, null);
        }
    }

    // ==================== WireMergeEngine Adapter ====================

    private static final class DocAdapter implements WireMergeEngine.Adapter<DocBase, DocOverlay> {
        static final DocAdapter INSTANCE = new DocAdapter();

        @Override
        public boolean isOverlayEmpty(DocBase base, DocOverlay ov) {
            return ov == null || ov.isEffectivelyEmpty();
        }

        @Override
        public boolean isSameSizeDirtyOnly(DocBase base, DocOverlay ov) {
            return !base.isMultiSegment() && ov.sameSizeMask != 0L
                    && (ov.varSizeMask | ov.deletedMask | ov.appendedMask) == 0L;
        }

        @Override
        public boolean hasSplitSubOverride(DocBase base, DocOverlay ov, int slot) {
            return false;
        }

        @Override
        public boolean isEntryDirtyOrDeleted(DocBase base, DocOverlay ov, int tapeIdx, int kind, int auxIndex, int fn, int wt) {
            int idx = ov.findEntry(fn);
            if (idx < 0) return false;
            byte st = ov.states[idx];
            if (st == STATE_SUBDOC) {
                DocSlot slot = ov.subDocs[idx];
                if (slot == null || slot.overlay == null || slot.overlay.isEffectivelyEmpty()) {
                    return false;
                }
                return true;
            }
            return true;
        }

        @Override
        public boolean isEntryDeleted(DocBase base, DocOverlay ov, int tapeIdx, int kind, int auxIndex, int fn, int wt) {
            int idx = ov.findEntry(fn);
            byte st = ov.states[idx];
            if (st == STATE_DELETED) return true;
            if (st == STATE_PATCHED_SCALAR) {
                if (wt != ov.wireTypes[idx]) return true;
                return tapeIdx != base.findLastTapeIdx(fn);
            }
            // STATE_SUBDOC: drop mismatched wire types or earlier LEN segments; emit merged at last LEN segment
            if (wt != WireFormat.WIRETYPE_LENGTH_DELIMITED) return true;
            return tapeIdx != base.findLastLenTapeIdx(fn);
        }

        @Override
        public void applyInPlacePatches(DocBase base, DocOverlay ov, byte[] target, int baseOffset) {
            for (int i = 0; i < ov.count; i++) {
                int fn = ov.fields[i];
                byte st = ov.states[i];
                if (st == STATE_SUBDOC) {
                    DocSlot slot = ov.subDocs[i];
                    if (slot != null && slot.overlay != null && !slot.overlay.isEffectivelyEmpty()) {
                        INSTANCE.applyInPlacePatches(slot.base, slot.overlay, target, baseOffset);
                    }
                    continue;
                }
                if (st == STATE_PATCHED_SCALAR) {
                    int tapeIdx = base.findLastTapeIdx(fn);
                    if (tapeIdx >= 0) {
                        int wt = ov.wireTypes[i];
                        int patchPos = base.tapeValueOffset(tapeIdx) - baseOffset;
                        if (wt == WireFormat.WIRETYPE_VARINT) {
                            WireWriter.patchVarint64At(target, patchPos, ov.primitives[i]);
                        } else if (wt == WireFormat.WIRETYPE_FIXED64) {
                            WireWriter.patchFixed64At(target, patchPos, ov.primitives[i]);
                        } else if (wt == WireFormat.WIRETYPE_FIXED32) {
                            WireWriter.patchFixed32At(target, patchPos, (int) ov.primitives[i]);
                        } else if (wt == WireFormat.WIRETYPE_LENGTH_DELIMITED) {
                            byte[] b = ov.bytesVals[i];
                            WireWriter.patchBytesAt(target, patchPos, b, 0, b.length);
                        }
                    }
                }
            }
        }

        @Override
        public int computeDirtyEntrySize(DocBase base, DocOverlay ov, int tapeIdx, int kind, int auxIndex, int fn, int wt) {
            int idx = ov.findEntry(fn);
            int tagLen = WireWriter.tagSize(fn);
            if (ov.states[idx] == STATE_SUBDOC) {
                DocSlot slot = ov.subDocs[idx];
                int childSize = WireMergeEngine.getSerializedSize(slot.base, slot.overlay, INSTANCE);
                return tagLen + WireWriter.varint32Size(childSize) + childSize;
            }
            byte owt = ov.wireTypes[idx];
            if (owt == WireFormat.WIRETYPE_VARINT) {
                return tagLen + WireWriter.varint64Size(ov.primitives[idx]);
            } else if (owt == WireFormat.WIRETYPE_FIXED64) {
                return tagLen + 8;
            } else if (owt == WireFormat.WIRETYPE_FIXED32) {
                return tagLen + 4;
            } else {
                byte[] b = ov.bytesVals[idx];
                return tagLen + WireWriter.varint32Size(b.length) + b.length;
            }
        }

        @Override
        public void writeDirtyEntry(DocBase base, DocOverlay ov, int tapeIdx, int kind, int auxIndex, int fn, int wt, WireWriter writer) {
            int idx = ov.findEntry(fn);
            if (ov.states[idx] == STATE_SUBDOC) {
                DocSlot slot = ov.subDocs[idx];
                int childSize = WireMergeEngine.getSerializedSize(slot.base, slot.overlay, INSTANCE);
                writer.writeTag(fn, WireFormat.WIRETYPE_LENGTH_DELIMITED);
                writer.writeVarint32(childSize);
                WireMergeEngine.writeSplicedTo(slot.base, slot.overlay, INSTANCE, writer);
                return;
            }
            byte owt = ov.wireTypes[idx];
            if (owt == WireFormat.WIRETYPE_VARINT) {
                writer.writeTag(fn, WireFormat.WIRETYPE_VARINT);
                writer.writeVarint64(ov.primitives[idx]);
            } else if (owt == WireFormat.WIRETYPE_FIXED64) {
                writer.writeTag(fn, WireFormat.WIRETYPE_FIXED64);
                writer.writeFixed64(ov.primitives[idx]);
            } else if (owt == WireFormat.WIRETYPE_FIXED32) {
                writer.writeTag(fn, WireFormat.WIRETYPE_FIXED32);
                writer.writeFixed32((int) ov.primitives[idx]);
            } else {
                byte[] b = ov.bytesVals[idx];
                writer.writeLengthDelimitedBytes(fn, b, 0, b.length);
            }
        }

        @Override
        public int computeAppendedSize(DocBase base, DocOverlay ov) {
            int size = 0;
            for (int i = 0; i < ov.count; i++) {
                int fn = ov.fields[i];
                byte st = ov.states[i];
                if (st == STATE_DELETED) continue;
                if (st == STATE_SUBDOC) {
                    if (base.countLenOccurrences(fn) == 0) {
                        DocSlot slot = ov.subDocs[i];
                        if (slot != null && !slot.isEffectivelyEmpty()) {
                            int cs = WireMergeEngine.getSerializedSize(slot.base, slot.overlay, INSTANCE);
                            size += WireWriter.tagSize(fn) + WireWriter.varint32Size(cs) + cs;
                        }
                    }
                    continue;
                }
                int lastIdx = base.findLastTapeIdx(fn);
                if (lastIdx < 0 || base.tapeWireType(lastIdx) != ov.wireTypes[i]) {
                    int tagLen = WireWriter.tagSize(fn);
                    byte owt = ov.wireTypes[i];
                    if (owt == WireFormat.WIRETYPE_VARINT) size += tagLen + WireWriter.varint64Size(ov.primitives[i]);
                    else if (owt == WireFormat.WIRETYPE_FIXED64) size += tagLen + 8;
                    else if (owt == WireFormat.WIRETYPE_FIXED32) size += tagLen + 4;
                    else if (ov.bytesVals[i] != null) {
                        byte[] b = ov.bytesVals[i];
                        size += tagLen + WireWriter.varint32Size(b.length) + b.length;
                    }
                }
            }
            return size;
        }

        @Override
        public void writeAppended(DocBase base, DocOverlay ov, WireWriter writer) {
            for (int i = 0; i < ov.count; i++) {
                int fn = ov.fields[i];
                byte st = ov.states[i];
                if (st == STATE_DELETED) continue;
                if (st == STATE_SUBDOC) {
                    if (base.countLenOccurrences(fn) == 0) {
                        DocSlot slot = ov.subDocs[i];
                        if (slot != null && !slot.isEffectivelyEmpty()) {
                            int cs = WireMergeEngine.getSerializedSize(slot.base, slot.overlay, INSTANCE);
                            writer.writeTag(fn, WireFormat.WIRETYPE_LENGTH_DELIMITED);
                            writer.writeVarint32(cs);
                            WireMergeEngine.writeSplicedTo(slot.base, slot.overlay, INSTANCE, writer);
                        }
                    }
                    continue;
                }
                int lastIdx = base.findLastTapeIdx(fn);
                if (lastIdx < 0 || base.tapeWireType(lastIdx) != ov.wireTypes[i]) {
                    byte owt = ov.wireTypes[i];
                    if (owt == WireFormat.WIRETYPE_VARINT) {
                        writer.writeTag(fn, WireFormat.WIRETYPE_VARINT);
                        writer.writeVarint64(ov.primitives[i]);
                    } else if (owt == WireFormat.WIRETYPE_FIXED64) {
                        writer.writeTag(fn, WireFormat.WIRETYPE_FIXED64);
                        writer.writeFixed64(ov.primitives[i]);
                    } else if (owt == WireFormat.WIRETYPE_FIXED32) {
                        writer.writeTag(fn, WireFormat.WIRETYPE_FIXED32);
                        writer.writeFixed32((int) ov.primitives[i]);
                    } else if (ov.bytesVals[i] != null) {
                        byte[] b = ov.bytesVals[i];
                        writer.writeLengthDelimitedBytes(fn, b, 0, b.length);
                    }
                }
            }
        }
    }

    // ==================== Unified Snapshot-Safe Builder & Lazy Sub-Cursor ====================

    public static final class Builder extends AbstractLazyBuilder<ProtoDocument, Builder, DocBase, DocOverlay> {
        private final Builder parentBuilder;
        private final int parentFieldNumber;
        private final Utf8Slice reusableSlice = new Utf8Slice();

        private Builder(DocBase initialBase, DocOverlay initialOverlay) {
            super(initialBase, initialOverlay);
            this.parentBuilder = null;
            this.parentFieldNumber = -1;
        }

        private Builder(Builder parentBuilder, int parentFieldNumber) {
            super(null, null);
            this.parentBuilder = parentBuilder;
            this.parentFieldNumber = parentFieldNumber;
        }

        @Override
        protected boolean isBoundToParent() {
            return parentBuilder != null;
        }

        @Override
        protected WireMergeEngine.Adapter<DocBase, DocOverlay> mergeAdapter() {
            return DocAdapter.INSTANCE;
        }

        @Override
        protected DocBase newEmptyBase() {
            return new DocBase();
        }

        @Override
        protected DocOverlay newEmptyOverlay() {
            return new DocOverlay();
        }

        @Override
        protected DocOverlay copyMutableOverlay(DocOverlay frozenOverlay) {
            return frozenOverlay.copyMutable();
        }

        @Override
        protected ProtoDocument wrapAsMessage(DocBase frozenBase, DocOverlay frozenOverlay) {
            if (frozenBase == DocBase.EMPTY && (frozenOverlay == null || frozenOverlay.isEffectivelyEmpty())) {
                return EMPTY;
            }
            return new ProtoDocument(frozenBase, frozenOverlay);
        }

        @Override
        protected ProtoDocument emptyDefaultMessage() {
            return EMPTY;
        }

        @Override
        protected void resetOverlayInPlace(DocOverlay ov) {
            ov.reset();
        }

        @Override
        protected DocBase resolveReadBase() {
            if (parentBuilder == null) {
                return this.base;
            }
            DocSlot slot = resolveReadSlotFromParent(false);
            return slot != null && slot.base != null ? slot.base : DocBase.EMPTY;
        }

        @Override
        protected DocOverlay resolveReadOverlay() {
            if (parentBuilder == null) {
                return this.overlay;
            }
            DocSlot slot = resolveReadSlotFromParent(false);
            return slot != null ? slot.overlay : null;
        }

        private DocSlot resolveReadSlotFromParent(boolean promoteScalarBytes) {
            DocBase pBase = parentBuilder.resolveReadBase();
            DocOverlay pOv = parentBuilder.resolveReadOverlay();
            if (pOv != null) {
                int idx = pOv.findEntry(parentFieldNumber);
                if (idx >= 0) {
                    byte st = pOv.states[idx];
                    if (st == STATE_DELETED) {
                        return null;
                    }
                    if (st == STATE_PATCHED_SCALAR) {
                        if (pOv.wireTypes[idx] == WireFormat.WIRETYPE_LENGTH_DELIMITED && pOv.bytesVals[idx] != null) {
                            byte[] raw = pOv.bytesVals[idx];
                            DocBase subBase = new DocBase();
                            subBase.wrapImmutable(raw, 0, raw.length);
                            if (promoteScalarBytes) {
                                DocOverlay subOv = new DocOverlay();
                                DocSlot slot = new DocSlot(subBase, subOv);
                                pOv.states[idx] = STATE_SUBDOC;
                                pOv.subDocs[idx] = slot;
                                pOv.bytesVals[idx] = null;
                                return slot;
                            }
                            return new DocSlot(subBase, null);
                        }
                        throw new IllegalArgumentException("Field " + parentFieldNumber + " is not length-delimited");
                    }
                    if (st == STATE_SUBDOC) {
                        return pOv.subDocs[idx];
                    }
                }
            }
            int lastIdx = pBase.findLastTapeIdx(parentFieldNumber);
            if (lastIdx >= 0 && pBase.tapeWireType(lastIdx) != WireFormat.WIRETYPE_LENGTH_DELIMITED
                    && pBase.countLenOccurrences(parentFieldNumber) == 0) {
                throw new IllegalArgumentException("Field " + parentFieldNumber + " is not length-delimited");
            }
            DocBase subBase = pBase.buildOrGetSubBase(parentFieldNumber);
            if (subBase == DocBase.EMPTY) {
                return null;
            }
            return new DocSlot(subBase, null);
        }

        private DocSlot resolveMutableSlotFromParent() {
            DocOverlay pOv = parentBuilder.ensureMutableOverlay();
            DocBase pBase = parentBuilder.resolveReadBase();
            int idx = pOv.findEntry(parentFieldNumber);
            DocSlot slot;
            if (idx >= 0 && pOv.states[idx] == STATE_SUBDOC && pOv.subDocs[idx] != null) {
                slot = pOv.subDocs[idx];
                if (slot.overlay == null) {
                    slot.overlay = new DocOverlay();
                } else if (slot.overlay.isFrozen()) {
                    slot.overlay = slot.overlay.copyMutable();
                }
            } else if (idx >= 0 && pOv.states[idx] == STATE_PATCHED_SCALAR
                    && pOv.wireTypes[idx] == WireFormat.WIRETYPE_LENGTH_DELIMITED && pOv.bytesVals[idx] != null) {
                byte[] raw = pOv.bytesVals[idx];
                DocBase subBase = new DocBase();
                subBase.wrapImmutable(raw, 0, raw.length);
                slot = new DocSlot(subBase, new DocOverlay());
                pOv.states[idx] = STATE_SUBDOC;
                pOv.subDocs[idx] = slot;
                pOv.bytesVals[idx] = null;
            } else {
                DocBase subBase = (idx >= 0 && pOv.states[idx] == STATE_DELETED)
                        ? DocBase.EMPTY
                        : pBase.buildOrGetSubBase(parentFieldNumber);
                subBase.freeze();
                slot = new DocSlot(subBase, new DocOverlay());
                int newIdx = pOv.getOrAllocEntry(parentFieldNumber);
                pOv.states[newIdx] = STATE_SUBDOC;
                pOv.wireTypes[newIdx] = WireFormat.WIRETYPE_LENGTH_DELIMITED;
                pOv.bytesVals[newIdx] = null;
                pOv.subDocs[newIdx] = slot;
            }
            return slot;
        }

        private void propagateMasksUpward() {
            Builder curr = this;
            while (curr != null) {
                if (curr.parentBuilder == null) {
                    if (curr.overlay != null) {
                        curr.overlay.recomputeMasks(curr.base);
                    }
                    break;
                }
                DocSlot slot = curr.resolveMutableSlotFromParent();
                slot.overlay.recomputeMasks(slot.base);
                curr = curr.parentBuilder;
            }
        }

        @Override
        protected DocOverlay ensureMutableOverlay() {
            if (parentBuilder == null) {
                return super.ensureMutableOverlay();
            }
            DocSlot slot = resolveMutableSlotFromParent();
            slot.overlay.invalidateSizeCache();
            return slot.overlay;
        }

        @Override
        protected Builder clearAllFields() {
            if (parentBuilder == null) {
                return super.clearAllFields();
            }
            DocOverlay pOv = parentBuilder.ensureMutableOverlay();
            int idx = pOv.getOrAllocEntry(parentFieldNumber);
            pOv.states[idx] = STATE_DELETED;
            pOv.bytesVals[idx] = null;
            pOv.subDocs[idx] = null;
            parentBuilder.propagateMasksUpward();
            return this;
        }

        @Override
        protected Builder replaceWithBytes(byte[] data, int offset, int length) {
            if (parentBuilder == null) {
                return super.replaceWithBytes(data, offset, length);
            }
            DocOverlay pOv = parentBuilder.ensureMutableOverlay();
            DocBase newBase = new DocBase();
            newBase.wrapImmutable(Arrays.copyOfRange(data, offset, offset + length), 0, length);
            DocSlot slot = new DocSlot(newBase, new DocOverlay());
            int idx = pOv.getOrAllocEntry(parentFieldNumber);
            pOv.states[idx] = STATE_SUBDOC;
            pOv.wireTypes[idx] = WireFormat.WIRETYPE_LENGTH_DELIMITED;
            pOv.bytesVals[idx] = null;
            pOv.subDocs[idx] = slot;
            parentBuilder.propagateMasksUpward();
            return this;
        }

        // -------------------- Read & Navigation Methods --------------------

        public boolean has(int fieldNumber) {
            return DocOps.has(resolveReadBase(), resolveReadOverlay(), fieldNumber);
        }

        public int getInt32(int fieldNumber) {
            return DocOps.getInt32(resolveReadBase(), resolveReadOverlay(), fieldNumber);
        }

        public long getInt64(int fieldNumber) {
            return DocOps.getInt64(resolveReadBase(), resolveReadOverlay(), fieldNumber);
        }

        public long getFixed64(int fieldNumber) {
            return DocOps.getFixed64(resolveReadBase(), resolveReadOverlay(), fieldNumber);
        }

        public Utf8Slice getSlice(int fieldNumber) {
            return DocOps.getSlice(resolveReadBase(), resolveReadOverlay(), fieldNumber, reusableSlice);
        }

        public String getString(int fieldNumber) {
            return DocOps.getString(resolveReadBase(), resolveReadOverlay(), fieldNumber);
        }

        /**
         * Returns an immutable read-only {@link ProtoDocument} snapshot of the nested field.
         */
        public ProtoDocument getSubDocument(int fieldNumber) {
            return DocOps.atReadOnly(resolveReadBase(), resolveReadOverlay(), fieldNumber);
        }

        /**
         * Returns a lazy-materializing bound {@link Builder} cursor for {@code fieldNumber}.
         * Navigating without writing allocates or registers zero state in the parent overlay.
         */
        public Builder at(int fieldNumber) {
            return new Builder(this, fieldNumber);
        }

        // -------------------- Mutation Methods (Overlay Only) --------------------

        public Builder setInt32(int fieldNumber, int value) {
            DocOverlay ov = ensureMutableOverlay();
            int idx = ov.getOrAllocEntry(fieldNumber);
            ov.states[idx] = STATE_PATCHED_SCALAR;
            ov.wireTypes[idx] = WireFormat.WIRETYPE_VARINT;
            ov.primitives[idx] = value;
            ov.bytesVals[idx] = null;
            ov.subDocs[idx] = null;
            propagateMasksUpward();
            return this;
        }

        public Builder setInt64(int fieldNumber, long value) {
            DocOverlay ov = ensureMutableOverlay();
            int idx = ov.getOrAllocEntry(fieldNumber);
            ov.states[idx] = STATE_PATCHED_SCALAR;
            ov.wireTypes[idx] = WireFormat.WIRETYPE_VARINT;
            ov.primitives[idx] = value;
            ov.bytesVals[idx] = null;
            ov.subDocs[idx] = null;
            propagateMasksUpward();
            return this;
        }

        public Builder setFixed64(int fieldNumber, long value) {
            DocOverlay ov = ensureMutableOverlay();
            int idx = ov.getOrAllocEntry(fieldNumber);
            ov.states[idx] = STATE_PATCHED_SCALAR;
            ov.wireTypes[idx] = WireFormat.WIRETYPE_FIXED64;
            ov.primitives[idx] = value;
            ov.bytesVals[idx] = null;
            ov.subDocs[idx] = null;
            propagateMasksUpward();
            return this;
        }

        public Builder setString(int fieldNumber, String value) {
            byte[] utf8 = value.getBytes(StandardCharsets.UTF_8);
            DocOverlay ov = ensureMutableOverlay();
            int idx = ov.getOrAllocEntry(fieldNumber);
            ov.states[idx] = STATE_PATCHED_SCALAR;
            ov.wireTypes[idx] = WireFormat.WIRETYPE_LENGTH_DELIMITED;
            ov.bytesVals[idx] = utf8;
            ov.subDocs[idx] = null;
            propagateMasksUpward();
            return this;
        }

        public Builder remove(int fieldNumber) {
            DocOverlay ov = ensureMutableOverlay();
            int idx = ov.getOrAllocEntry(fieldNumber);
            ov.states[idx] = STATE_DELETED;
            ov.bytesVals[idx] = null;
            ov.subDocs[idx] = null;
            propagateMasksUpward();
            return this;
        }

        @Override
        public Builder clone() {
            DocBase b = resolveReadBase();
            DocOverlay o = resolveReadOverlay();
            b.freeze();
            return new Builder(b, o != null ? o.copyMutable() : null);
        }
    }
}
