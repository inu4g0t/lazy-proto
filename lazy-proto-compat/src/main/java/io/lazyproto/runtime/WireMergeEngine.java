package io.lazyproto.runtime;

import com.google.protobuf.ByteString;
import com.google.protobuf.CodedOutputStream;
import com.google.protobuf.UnsafeByteOperations;
import io.lazyproto.wire.WireWriter;

import java.io.IOException;
import java.io.OutputStream;
import java.util.Arrays;

/**
 * Shared Merge-on-Serialize Engine fusing an {@link ImmutableWireBase} and an optional
 * external {@link WireOverlay} across all 4 fast paths (FP0 Passthrough, FP1 Output Patch,
 * FP2 Contiguous Span Coalescing, and FP3 Hierarchical Sub-Tree Merge).
 */
public final class WireMergeEngine {
    private WireMergeEngine() {}

    public interface Adapter<B extends ImmutableWireBase, O extends WireOverlay> {
        boolean isOverlayEmpty(B base, O ov);
        boolean isSameSizeDirtyOnly(B base, O ov);
        boolean hasSplitSubOverride(B base, O ov, int slot);
        boolean isEntryDirtyOrDeleted(B base, O ov, int tapeIdx, int kind, int auxIndex, int fieldNumber, int wireType);
        boolean isEntryDeleted(B base, O ov, int tapeIdx, int kind, int auxIndex, int fieldNumber, int wireType);
        void applyInPlacePatches(B base, O ov, byte[] target, int baseOffset);
        int computeDirtyEntrySize(B base, O ov, int tapeIdx, int kind, int auxIndex, int fieldNumber, int wireType);
        void writeDirtyEntry(B base, O ov, int tapeIdx, int kind, int auxIndex, int fieldNumber, int wireType, WireWriter writer);
        int computeAppendedSize(B base, O ov);
        void writeAppended(B base, O ov, WireWriter writer);
    }

    public static <B extends ImmutableWireBase, O extends WireOverlay> int getSerializedSize(
            B base, O ov, Adapter<B, O> adapter) {
        if (!base.isMultiSegment() && (adapter.isOverlayEmpty(base, ov) || adapter.isSameSizeDirtyOnly(base, ov))) {
            return base.rawLength();
        }
        if (ov != null && ov.cachedSerializedSize >= 0) {
            return ov.cachedSerializedSize;
        }
        base.ensureScanned();
        int total = 0;
        int n = base.tapeCount();
        for (int i = 0; i < n; i++) {
            int kind = base.tapeKind(i);
            if (kind == ImmutableWireBase.KIND_SHADOWED) {
                continue;
            }
            int auxIndex = base.tapeAuxIndex(i);
            int fn = base.tapeFieldNumber(i);
            int wt = base.tapeWireType(i);
            if (kind == ImmutableWireBase.KIND_SPLIT_SUB_CONT) {
                if (ov == null || !adapter.hasSplitSubOverride(base, ov, auxIndex)) {
                    total += base.tapeRecordLength(i);
                }
            } else if (kind == ImmutableWireBase.KIND_SPLIT_SUB_TAIL) {
                if (ov == null || !adapter.hasSplitSubOverride(base, ov, auxIndex)) {
                    total += base.tapeRecordLength(i);
                } else if (!adapter.isEntryDeleted(base, ov, i, kind, auxIndex, fn, wt)) {
                    total += adapter.computeDirtyEntrySize(base, ov, i, kind, auxIndex, fn, wt);
                }
            } else if (ov != null && adapter.isEntryDirtyOrDeleted(base, ov, i, kind, auxIndex, fn, wt)) {
                if (!adapter.isEntryDeleted(base, ov, i, kind, auxIndex, fn, wt)) {
                    total += adapter.computeDirtyEntrySize(base, ov, i, kind, auxIndex, fn, wt);
                }
            } else {
                total += base.tapeRecordLength(i);
            }
        }
        if (ov != null && ov.appendedMask != 0L) {
            total += adapter.computeAppendedSize(base, ov);
        }
        if (ov != null) {
            ov.cachedSerializedSize = total;
        }
        return total;
    }

    public static <B extends ImmutableWireBase, O extends WireOverlay> void writeSplicedTo(
            B base, O ov, Adapter<B, O> adapter, WireWriter writer) {
        if (!base.isMultiSegment() && adapter.isOverlayEmpty(base, ov)) {
            writer.writeRawBytes(base.rawBuffer(), base.rawOffset(), base.rawLength());
            return;
        }
        if (ov != null && adapter.isSameSizeDirtyOnly(base, ov)) {
            int outStart = writer.position();
            writer.writeRawBytes(base.rawBuffer(), base.rawOffset(), base.rawLength());
            adapter.applyInPlacePatches(base, ov, writer.buffer(), base.rawOffset() - outStart);
            return;
        }

        base.ensureScanned();
        byte[] buf = base.rawBuffer();
        int n = base.tapeCount();
        int spanStart = -1;
        int spanEnd = -1;

        for (int i = 0; i < n; i++) {
            int kind = base.tapeKind(i);
            boolean skip = false;
            boolean dirty = false;
            int auxIndex = base.tapeAuxIndex(i);
            int fn = base.tapeFieldNumber(i);
            int wt = base.tapeWireType(i);

            if (kind == ImmutableWireBase.KIND_SHADOWED) {
                skip = true;
            } else if (kind == ImmutableWireBase.KIND_SPLIT_SUB_CONT) {
                if (ov != null && adapter.hasSplitSubOverride(base, ov, auxIndex)) {
                    skip = true;
                }
            } else if (kind == ImmutableWireBase.KIND_SPLIT_SUB_TAIL) {
                if (ov != null && adapter.hasSplitSubOverride(base, ov, auxIndex)) {
                    if (adapter.isEntryDeleted(base, ov, i, kind, auxIndex, fn, wt)) {
                        skip = true;
                    } else {
                        dirty = true;
                    }
                }
            } else if (ov != null && adapter.isEntryDirtyOrDeleted(base, ov, i, kind, auxIndex, fn, wt)) {
                if (adapter.isEntryDeleted(base, ov, i, kind, auxIndex, fn, wt)) {
                    skip = true;
                } else {
                    dirty = true;
                }
            }

            if (skip) {
                if (spanStart != -1) {
                    writer.writeRawBytes(buf, spanStart, spanEnd - spanStart);
                    spanStart = -1;
                }
            } else if (dirty) {
                if (spanStart != -1) {
                    writer.writeRawBytes(buf, spanStart, spanEnd - spanStart);
                    spanStart = -1;
                }
                adapter.writeDirtyEntry(base, ov, i, kind, auxIndex, fn, wt, writer);
            } else {
                int tagOff = base.tapeTagOffset(i);
                int recEnd = base.tapeRecordEnd(i);
                if (spanStart == -1) {
                    spanStart = tagOff;
                    spanEnd = recEnd;
                } else if (spanEnd == tagOff) {
                    spanEnd = recEnd;
                } else {
                    writer.writeRawBytes(buf, spanStart, spanEnd - spanStart);
                    spanStart = tagOff;
                    spanEnd = recEnd;
                }
            }
        }

        if (spanStart != -1) {
            writer.writeRawBytes(buf, spanStart, spanEnd - spanStart);
        }

        if (ov != null && ov.appendedMask != 0L) {
            adapter.writeAppended(base, ov, writer);
        }
    }

    public static <B extends ImmutableWireBase, O extends WireOverlay> byte[] toByteArray(
            B base, O ov, Adapter<B, O> adapter) {
        if (!base.isMultiSegment() && adapter.isOverlayEmpty(base, ov)) {
            byte[] out = new byte[base.rawLength()];
            System.arraycopy(base.rawBuffer(), base.rawOffset(), out, 0, out.length);
            return out;
        }
        if (ov != null && adapter.isSameSizeDirtyOnly(base, ov)) {
            byte[] out = new byte[base.rawLength()];
            System.arraycopy(base.rawBuffer(), base.rawOffset(), out, 0, out.length);
            adapter.applyInPlacePatches(base, ov, out, base.rawOffset());
            return out;
        }
        int size = getSerializedSize(base, ov, adapter);
        byte[] out = new byte[size];
        WireWriter writer = new WireWriter(out, 0);
        writeSplicedTo(base, ov, adapter, writer);
        return out;
    }

    public static <B extends ImmutableWireBase, O extends WireOverlay> int writeToBuffer(
            B base, O ov, Adapter<B, O> adapter, byte[] target, int targetOffset) {
        if (!base.isMultiSegment() && adapter.isOverlayEmpty(base, ov)) {
            int len = base.rawLength();
            System.arraycopy(base.rawBuffer(), base.rawOffset(), target, targetOffset, len);
            return len;
        }
        if (ov != null && adapter.isSameSizeDirtyOnly(base, ov)) {
            int len = base.rawLength();
            System.arraycopy(base.rawBuffer(), base.rawOffset(), target, targetOffset, len);
            adapter.applyInPlacePatches(base, ov, target, base.rawOffset() - targetOffset);
            return len;
        }
        WireWriter writer = new WireWriter(target, targetOffset);
        writeSplicedTo(base, ov, adapter, writer);
        return writer.position() - targetOffset;
    }

    public static <B extends ImmutableWireBase, O extends WireOverlay> ByteString toByteString(
            B base, O ov, Adapter<B, O> adapter) {
        if (!base.isMultiSegment() && adapter.isOverlayEmpty(base, ov)) {
            return UnsafeByteOperations.unsafeWrap(base.rawBuffer(), base.rawOffset(), base.rawLength());
        }
        return UnsafeByteOperations.unsafeWrap(toByteArray(base, ov, adapter));
    }

    public static <B extends ImmutableWireBase, O extends WireOverlay> void writeTo(
            B base, O ov, Adapter<B, O> adapter, CodedOutputStream output) throws IOException {
        if (!base.isMultiSegment() && adapter.isOverlayEmpty(base, ov)) {
            output.writeRawBytes(base.rawBuffer(), base.rawOffset(), base.rawLength());
        } else {
            byte[] bytes = toByteArray(base, ov, adapter);
            output.writeRawBytes(bytes, 0, bytes.length);
        }
    }

    public static <B extends ImmutableWireBase, O extends WireOverlay> void writeTo(
            B base, O ov, Adapter<B, O> adapter, OutputStream output) throws IOException {
        if (!base.isMultiSegment() && adapter.isOverlayEmpty(base, ov)) {
            output.write(base.rawBuffer(), base.rawOffset(), base.rawLength());
        } else {
            output.write(toByteArray(base, ov, adapter));
        }
    }

    public static <B extends ImmutableWireBase, O extends WireOverlay> void writeDelimitedTo(
            B base, O ov, Adapter<B, O> adapter, OutputStream output) throws IOException {
        int size = getSerializedSize(base, ov, adapter);
        CodedOutputStream codedOut = CodedOutputStream.newInstance(
                output, CodedOutputStream.computeUInt32SizeNoTag(size) + 64);
        codedOut.writeUInt32NoTag(size);
        codedOut.flush();
        writeTo(base, ov, adapter, output);
    }

    public static <B extends ImmutableWireBase, O extends WireOverlay> boolean rawBytesEquals(
            B b1, O o1, Adapter<B, O> a1, B b2, O o2, Adapter<B, O> a2) {
        if (!b1.isMultiSegment() && !b2.isMultiSegment()
                && a1.isOverlayEmpty(b1, o1) && a2.isOverlayEmpty(b2, o2)) {
            if (b1.rawLength() != b2.rawLength()) return false;
            return Arrays.equals(
                    b1.rawBuffer(), b1.rawOffset(), b1.rawOffset() + b1.rawLength(),
                    b2.rawBuffer(), b2.rawOffset(), b2.rawOffset() + b2.rawLength());
        }
        return Arrays.equals(toByteArray(b1, o1, a1), toByteArray(b2, o2, a2));
    }
}
