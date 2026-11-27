package io.lazyproto.core;

import io.lazyproto.wire.Utf8Slice;
import io.lazyproto.wire.WireFormat;
import io.lazyproto.wire.WireReader;
import io.lazyproto.wire.WireWriter;

import java.util.Arrays;

/**
 * Schema-less Java Lower-Level Input-Bytes Wrapper (Zero {@code com.google.protobuf} dependencies).
 *
 * <p>Uses a single mutually-exclusive {@link FieldState} state machine per field slot so
 * scalar overrides, singular sub-message edits, repeated-element patches, and deletions
 * cannot leave conflicting flags or leak dirty counters across transitions.
 */
public final class RawWireView {
    private static final int DIRECT_MAX = 32;

    private static final byte KIND_NONE = 0;
    private static final byte KIND_VARINT = 1;
    private static final byte KIND_FIXED32 = 2;
    private static final byte KIND_FIXED64 = 3;
    private static final byte KIND_BYTES = 4;
    private static final byte KIND_SUBVIEW = 5;

    private static final byte DIRTY_NONE = 0;
    private static final byte DIRTY_SAME_SIZE = 1;
    private static final byte DIRTY_VAR_SIZE = 2;
    private static final byte DIRTY_APPENDED = 3;

    private enum FieldState {
        RAW_CLEAN,
        SCALAR_OVERRIDE,
        SUBVIEW_OVERRIDE,
        REPEATED_PATCHED,
        DELETED
    }

    private static final class FieldSlot {
        int fieldNumber;
        int lastTapeIdx = -1;
        boolean present;
        boolean decoded;
        FieldState state = FieldState.RAW_CLEAN;
        byte dirtyMode = DIRTY_NONE;
        byte kind = KIND_NONE;

        long numVal;
        boolean bytesInArena;
        long arenaHandle;
        byte[] extBytes;
        int extOff;
        int extLen;
        String stringCache;

        RawWireView subView;
        int subViewEpoch = -1;

        int[] repTapeIdx;
        int repCount;
        RawWireView[] repSubViews;
        int[] repSubEpochs;
        int repDirtyCount;

        void addRepTape(int tapeIdx) {
            if (repTapeIdx == null) repTapeIdx = new int[4];
            else if (repCount == repTapeIdx.length) repTapeIdx = Arrays.copyOf(repTapeIdx, repTapeIdx.length << 1);
            repTapeIdx[repCount++] = tapeIdx;
        }
    }

    private final WireTape tape = new WireTape(16);
    private final ScratchUtf8Arena arena;
    private final boolean isRoot;
    private final int[] directSlot = new int[DIRECT_MAX + 1];
    private FieldSlot[] slots = new FieldSlot[16];
    private int slotCount;
    private int wrapEpoch;

    private boolean hasDeleted;
    private boolean replacedFromWrap;
    private int sameSizeDirtyCount;
    private int varSizeDirtyCount;
    private int appendedCount;

    private RawWireView parent;
    private int parentFieldNumber;
    private int parentRepIdx = -1;
    private final Utf8Slice reusableSlice = new Utf8Slice();
    private WireWriter cachedWriter;
    private final WireTape.ScanVisitor scanVisitor = this::onScanField;
    private final WireTape.DirtyEntryWriter dirtyWriter = this::writeDirtyEntry;

    public RawWireView() {
        this.arena = new ScratchUtf8Arena(128);
        this.isRoot = true;
        Arrays.fill(directSlot, -1);
    }

    private RawWireView(ScratchUtf8Arena sharedArena) {
        this.arena = sharedArena;
        this.isRoot = false;
        Arrays.fill(directSlot, -1);
    }

    public RawWireView wrap(byte[] buf, int off, int len) {
        tape.reset(buf, off, len);
        resetLocal();
        if (isRoot) {
            arena.reset();
        } else if (parent != null) {
            this.replacedFromWrap = true;
            notifyParent();
        }
        return this;
    }

    private void resetLocal() {
        wrapEpoch++;
        Arrays.fill(directSlot, -1);
        for (int i = 0; i < slotCount; i++) {
            FieldSlot s = slots[i];
            s.lastTapeIdx = -1;
            s.present = false;
            s.decoded = false;
            s.state = FieldState.RAW_CLEAN;
            s.dirtyMode = DIRTY_NONE;
            s.kind = KIND_NONE;
            s.bytesInArena = false;
            s.extBytes = null;
            s.stringCache = null;
            if (s.subView != null) {
                s.subView.parent = null;
            }
            s.subViewEpoch = -1;
            if (s.repSubViews != null && s.repCount > 0) {
                for (int r = 0; r < s.repCount; r++) {
                    if (s.repSubViews[r] != null) {
                        s.repSubViews[r].parent = null;
                    }
                }
                if (s.repSubEpochs != null) {
                    Arrays.fill(s.repSubEpochs, 0, Math.min(s.repCount, s.repSubEpochs.length), -1);
                }
            }
            s.repCount = 0;
            s.repDirtyCount = 0;
        }
        slotCount = 0;
        hasDeleted = false;
        replacedFromWrap = false;
        sameSizeDirtyCount = 0;
        varSizeDirtyCount = 0;
        appendedCount = 0;
    }

    public boolean isScanned() { return tape.scanned; }

    public boolean isClean() {
        return !replacedFromWrap && isSelfClean();
    }

    private boolean isSelfClean() {
        return !tape.multiSegment
                && sameSizeDirtyCount == 0 && varSizeDirtyCount == 0 && appendedCount == 0 && !hasDeleted;
    }

    public boolean isSameSizeDirtyOnly() {
        return !replacedFromWrap && isSelfSameSizeDirtyOnly();
    }

    private boolean isSelfSameSizeDirtyOnly() {
        return !tape.multiSegment
                && varSizeDirtyCount == 0 && appendedCount == 0 && !hasDeleted && sameSizeDirtyCount > 0;
    }

    private void ensureScanned() {
        if (!tape.scanned) {
            tape.scanned = true;
            if (tape.rawLength > 0) {
                tape.scanRange(tape.buf, tape.rawOffset, tape.rawOffset + tape.rawLength, scanVisitor);
            }
        }
    }

    private void onScanField(int tapeIdx, int fn, int wt) {
        FieldSlot fc = findOrCreateSlot(fn);
        // Zero-assumption scan: keep duplicate tags CLEAN_RAW so repeated scalars remain intact.
        fc.lastTapeIdx = tapeIdx;
        fc.present = true;
        fc.addRepTape(tapeIdx);
    }

    private int findSlotIdx(int fn) {
        if (fn <= DIRECT_MAX && fn >= 0) return directSlot[fn];
        for (int i = 0; i < slotCount; i++) {
            if (slots[i].fieldNumber == fn) return i;
        }
        return -1;
    }

    private FieldSlot findOrCreateSlot(int fn) {
        int idx = findSlotIdx(fn);
        if (idx >= 0) return slots[idx];
        idx = slotCount++;
        if (idx >= slots.length) slots = Arrays.copyOf(slots, slots.length << 1);
        FieldSlot fc = slots[idx];
        if (fc == null) {
            fc = new FieldSlot();
            slots[idx] = fc;
        }
        fc.fieldNumber = fn;
        if (fn <= DIRECT_MAX && fn >= 0) directSlot[fn] = idx;
        return fc;
    }

    private FieldSlot resolveForRead(int fn) {
        ensureScanned();
        int idx = findSlotIdx(fn);
        if (idx < 0) return null;
        FieldSlot fc = slots[idx];
        return (fc.present && fc.state != FieldState.DELETED) ? fc : null;
    }

    // ==================== Read API ====================

    public boolean has(int fn) { return resolveForRead(fn) != null; }

    public long getInt64(int fn) {
        FieldSlot fc = resolveForRead(fn);
        if (fc == null) return 0L;
        if (fc.decoded && fc.kind == KIND_VARINT) return fc.numVal;
        if (fc.state == FieldState.SCALAR_OVERRIDE || fc.state == FieldState.SUBVIEW_OVERRIDE) return 0L;
        if (fc.lastTapeIdx < 0 || tape.wireType(fc.lastTapeIdx) != WireFormat.WIRETYPE_VARINT) return 0L;
        fc.numVal = WireReader.readVarint64At(tape.buf, tape.valueOffset(fc.lastTapeIdx));
        fc.kind = KIND_VARINT;
        fc.decoded = true;
        return fc.numVal;
    }

    public int getInt32(int fn) { return (int) getInt64(fn); }

    public int getFixed32(int fn) {
        FieldSlot fc = resolveForRead(fn);
        if (fc == null) return 0;
        if (fc.decoded && fc.kind == KIND_FIXED32) return (int) fc.numVal;
        if (fc.state == FieldState.SCALAR_OVERRIDE || fc.state == FieldState.SUBVIEW_OVERRIDE) return 0;
        if (fc.lastTapeIdx < 0 || tape.wireType(fc.lastTapeIdx) != WireFormat.WIRETYPE_FIXED32) return 0;
        fc.numVal = WireReader.readFixed32At(tape.buf, tape.valueOffset(fc.lastTapeIdx)) & 0xFFFFFFFFL;
        fc.kind = KIND_FIXED32;
        fc.decoded = true;
        return (int) fc.numVal;
    }

    public long getFixed64(int fn) {
        FieldSlot fc = resolveForRead(fn);
        if (fc == null) return 0L;
        if (fc.decoded && fc.kind == KIND_FIXED64) return fc.numVal;
        if (fc.state == FieldState.SCALAR_OVERRIDE || fc.state == FieldState.SUBVIEW_OVERRIDE) return 0L;
        if (fc.lastTapeIdx < 0 || tape.wireType(fc.lastTapeIdx) != WireFormat.WIRETYPE_FIXED64) return 0L;
        fc.numVal = WireReader.readFixed64At(tape.buf, tape.valueOffset(fc.lastTapeIdx));
        fc.kind = KIND_FIXED64;
        fc.decoded = true;
        return fc.numVal;
    }

    public double getDouble(int fn) {
        return Double.longBitsToDouble(getFixed64(fn));
    }

    public Utf8Slice getSlice(int fn) {
        FieldSlot fc = resolveForRead(fn);
        if (fc == null) return reusableSlice.wrap(WireTape.EMPTY_BYTES, 0, 0);
        if (fc.state == FieldState.SUBVIEW_OVERRIDE && fc.subView != null) {
            byte[] merged = fc.subView.toByteArray();
            long h = arena.storeBytes(merged, 0, merged.length);
            return reusableSlice.wrap(arena.buffer(), ScratchUtf8Arena.unpackOffset(h), ScratchUtf8Arena.unpackLength(h));
        }
        if (fc.state == FieldState.SCALAR_OVERRIDE) {
            if (fc.kind != KIND_BYTES) return reusableSlice.wrap(WireTape.EMPTY_BYTES, 0, 0);
            if (fc.bytesInArena) {
                return reusableSlice.wrap(arena.buffer(),
                        ScratchUtf8Arena.unpackOffset(fc.arenaHandle),
                        ScratchUtf8Arena.unpackLength(fc.arenaHandle));
            }
            return reusableSlice.wrap(fc.extBytes != null ? fc.extBytes : WireTape.EMPTY_BYTES, fc.extOff, fc.extLen);
        }
        if (fc.lastTapeIdx < 0 || tape.wireType(fc.lastTapeIdx) != WireFormat.WIRETYPE_LENGTH_DELIMITED) {
            return reusableSlice.wrap(WireTape.EMPTY_BYTES, 0, 0);
        }
        return reusableSlice.wrap(tape.buf, tape.valueOffset(fc.lastTapeIdx), tape.valueLength(fc.lastTapeIdx));
    }

    public String getString(int fn) {
        FieldSlot fc = resolveForRead(fn);
        if (fc == null) return "";
        if (fc.state == FieldState.SUBVIEW_OVERRIDE && fc.subView != null) {
            byte[] merged = fc.subView.toByteArray();
            return WireReader.readStringAt(merged, 0, merged.length);
        }
        if (fc.state == FieldState.SCALAR_OVERRIDE) {
            if (fc.kind != KIND_BYTES) return "";
            if (fc.stringCache == null) {
                if (fc.bytesInArena) {
                    fc.stringCache = WireReader.readStringAt(arena.buffer(),
                            ScratchUtf8Arena.unpackOffset(fc.arenaHandle),
                            ScratchUtf8Arena.unpackLength(fc.arenaHandle));
                } else {
                    fc.stringCache = WireReader.readStringAt(fc.extBytes, fc.extOff, fc.extLen);
                }
            }
            return fc.stringCache;
        }
        if (fc.decoded && fc.kind == KIND_BYTES && fc.stringCache != null) return fc.stringCache;
        if (fc.lastTapeIdx < 0 || tape.wireType(fc.lastTapeIdx) != WireFormat.WIRETYPE_LENGTH_DELIMITED) return "";
        String val = WireReader.readStringAt(tape.buf, tape.valueOffset(fc.lastTapeIdx), tape.valueLength(fc.lastTapeIdx));
        fc.stringCache = val;
        if (fc.kind == KIND_NONE) {
            fc.kind = KIND_BYTES;
            fc.decoded = true;
        }
        return val;
    }

    public RawWireView sub(int fn) {
        ensureScanned();
        FieldSlot fc = findOrCreateSlot(fn);
        if (fc.subView == null) fc.subView = new RawWireView(arena);
        if (fc.subViewEpoch != this.wrapEpoch) {
            RawWireView child = fc.subView;
            // Clear parent temporarily so internal bind-wrap does not mark parent dirty!
            child.parent = null;
            if (fc.present && fc.state != FieldState.DELETED) {
                if (fc.state == FieldState.SCALAR_OVERRIDE) {
                    if (fc.kind == KIND_BYTES) {
                        if (fc.bytesInArena) {
                            int off = ScratchUtf8Arena.unpackOffset(fc.arenaHandle);
                            int len = ScratchUtf8Arena.unpackLength(fc.arenaHandle);
                            child.wrap(Arrays.copyOfRange(arena.buffer(), off, off + len), 0, len);
                        } else {
                            child.wrap(fc.extBytes, fc.extOff, fc.extLen);
                        }
                    } else {
                        child.wrap(WireTape.EMPTY_BYTES, 0, 0);
                        child.tape.scanned = true;
                    }
                } else if (fc.repCount > 1) {
                    child.wrapMultiSegments(tape, fc.repTapeIdx, fc.repCount);
                } else if (fc.lastTapeIdx >= 0 && tape.wireType(fc.lastTapeIdx) == WireFormat.WIRETYPE_LENGTH_DELIMITED) {
                    child.wrap(tape.buf, tape.valueOffset(fc.lastTapeIdx), tape.valueLength(fc.lastTapeIdx));
                } else {
                    child.wrap(WireTape.EMPTY_BYTES, 0, 0);
                    child.tape.scanned = true;
                }
            } else {
                child.wrap(WireTape.EMPTY_BYTES, 0, 0);
                child.tape.scanned = true;
            }
            child.parent = this;
            child.parentFieldNumber = fn;
            child.parentRepIdx = -1;
            fc.subViewEpoch = this.wrapEpoch;
            if (fc.state != FieldState.SCALAR_OVERRIDE) {
                fc.kind = KIND_SUBVIEW;
                fc.decoded = true;
            }
        }
        return fc.subView;
    }

    public int repeatedCount(int fn) {
        ensureScanned();
        int idx = findSlotIdx(fn);
        if (idx < 0 || slots[idx].state == FieldState.DELETED) return 0;
        return slots[idx].repCount;
    }

    public String getRepeatedString(int fn, int repIndex) {
        ensureScanned();
        int idx = findSlotIdx(fn);
        if (idx < 0 || slots[idx].state == FieldState.DELETED || repIndex < 0 || repIndex >= slots[idx].repCount) {
            throw new IndexOutOfBoundsException("Index " + repIndex);
        }
        int tIdx = slots[idx].repTapeIdx[repIndex];
        if (tape.wireType(tIdx) != WireFormat.WIRETYPE_LENGTH_DELIMITED) return "";
        return WireReader.readStringAt(tape.buf, tape.valueOffset(tIdx), tape.valueLength(tIdx));
    }

    public RawWireView repeatedSub(int fn, int repIndex) {
        ensureScanned();
        int idx = findSlotIdx(fn);
        if (idx < 0 || slots[idx].state == FieldState.DELETED || repIndex < 0 || repIndex >= slots[idx].repCount) {
            throw new IndexOutOfBoundsException("Index " + repIndex);
        }
        FieldSlot fc = slots[idx];
        if (fc.repSubViews == null || fc.repSubViews.length < fc.repCount) {
            int oldEpochLen = fc.repSubEpochs == null ? 0 : fc.repSubEpochs.length;
            fc.repSubViews = fc.repSubViews == null ? new RawWireView[fc.repCount] : Arrays.copyOf(fc.repSubViews, fc.repCount);
            fc.repSubEpochs = fc.repSubEpochs == null ? new int[fc.repCount] : Arrays.copyOf(fc.repSubEpochs, fc.repCount);
            if (fc.repCount > oldEpochLen) {
                Arrays.fill(fc.repSubEpochs, oldEpochLen, fc.repCount, -1);
            }
        }
        RawWireView child = fc.repSubViews[repIndex];
        if (child == null) {
            child = new RawWireView(arena);
            fc.repSubViews[repIndex] = child;
            fc.repSubEpochs[repIndex] = -1;
        }
        if (fc.repSubEpochs[repIndex] != this.wrapEpoch) {
            int tIdx = fc.repTapeIdx[repIndex];
            child.parent = null; // Internal bind-wrap must not mark parent dirty
            if (tape.wireType(tIdx) == WireFormat.WIRETYPE_LENGTH_DELIMITED) {
                child.wrap(tape.buf, tape.valueOffset(tIdx), tape.valueLength(tIdx));
            } else {
                child.wrap(WireTape.EMPTY_BYTES, 0, 0);
                child.tape.scanned = true;
            }
            child.parent = this;
            child.parentFieldNumber = fn;
            child.parentRepIdx = repIndex;
            fc.repSubEpochs[repIndex] = this.wrapEpoch;
        }
        return child;
    }

    // ==================== Write API (Single Atomic State Machine) ====================

    public RawWireView setInt64(int fn, long val) {
        ensureScanned();
        FieldSlot fc = findOrCreateSlot(fn);
        enterScalarOverride(fc, KIND_VARINT, WireFormat.WIRETYPE_VARINT, WireWriter.varint64Size(val));
        fc.numVal = val;
        notifyParent();
        return this;
    }

    public RawWireView setInt32(int fn, int val) { return setInt64(fn, val); }

    public RawWireView setFixed32(int fn, int val) {
        ensureScanned();
        FieldSlot fc = findOrCreateSlot(fn);
        enterScalarOverride(fc, KIND_FIXED32, WireFormat.WIRETYPE_FIXED32, 4);
        fc.numVal = val & 0xFFFFFFFFL;
        notifyParent();
        return this;
    }

    public RawWireView setFixed64(int fn, long val) {
        ensureScanned();
        FieldSlot fc = findOrCreateSlot(fn);
        enterScalarOverride(fc, KIND_FIXED64, WireFormat.WIRETYPE_FIXED64, 8);
        fc.numVal = val;
        notifyParent();
        return this;
    }

    public RawWireView setDouble(int fn, double val) {
        return setFixed64(fn, Double.doubleToRawLongBits(val));
    }

    public RawWireView setString(int fn, String val) {
        ensureScanned();
        FieldSlot fc = findOrCreateSlot(fn);
        long handle = arena.storeString(val);
        enterScalarOverride(fc, KIND_BYTES, WireFormat.WIRETYPE_LENGTH_DELIMITED, ScratchUtf8Arena.unpackLength(handle));
        fc.stringCache = val;
        fc.arenaHandle = handle;
        fc.bytesInArena = true;
        notifyParent();
        return this;
    }

    public RawWireView setBytes(int fn, byte[] bytes, int off, int len) {
        ensureScanned();
        FieldSlot fc = findOrCreateSlot(fn);
        enterScalarOverride(fc, KIND_BYTES, WireFormat.WIRETYPE_LENGTH_DELIMITED, len);
        fc.extBytes = bytes;
        fc.extOff = off;
        fc.extLen = len;
        fc.bytesInArena = false;
        notifyParent();
        return this;
    }

    public RawWireView erase(int fn) {
        ensureScanned();
        int idx = findSlotIdx(fn);
        if (idx < 0) return this;
        FieldSlot fc = slots[idx];
        leaveCurrentState(fc, false, false);
        fc.state = FieldState.DELETED;
        fc.present = false;
        fc.decoded = false;
        fc.kind = KIND_NONE;
        for (int i = 0; i < fc.repCount; i++) {
            tape.setFlags(fc.repTapeIdx[i], WireTape.FLAG_DELETED);
            hasDeleted = true;
        }
        notifyParent();
        return this;
    }

    /**
     * Atomically tears down {@code fc}'s previous state: reclaims any dirty counters
     * contributed to this view, resets base tape entry flags to {@code CLEAN_RAW},
     * and detaches invalidated child views so stale references cannot notify this parent.
     */
    private void leaveCurrentState(FieldSlot fc, boolean keepSubView, boolean keepRepViews) {
        switch (fc.state) {
            case SCALAR_OVERRIDE, SUBVIEW_OVERRIDE -> {
                if (fc.dirtyMode == DIRTY_SAME_SIZE) sameSizeDirtyCount--;
                else if (fc.dirtyMode == DIRTY_VAR_SIZE) varSizeDirtyCount--;
                else if (fc.dirtyMode == DIRTY_APPENDED) appendedCount--;
                fc.dirtyMode = DIRTY_NONE;
                for (int i = 0; i < fc.repCount; i++) {
                    tape.setFlags(fc.repTapeIdx[i], WireTape.FLAG_CLEAN_RAW);
                }
            }
            case REPEATED_PATCHED -> {
                for (int i = 0; i < fc.repCount; i++) {
                    int tIdx = fc.repTapeIdx[i];
                    int fl = tape.flags(tIdx);
                    if (fl == WireTape.FLAG_DIRTY_SAME_SIZE) sameSizeDirtyCount--;
                    else if (fl == WireTape.FLAG_DIRTY_VAR_SIZE) varSizeDirtyCount--;
                    tape.setFlags(tIdx, WireTape.FLAG_CLEAN_RAW);
                }
                fc.repDirtyCount = 0;
            }
            case DELETED -> {
                for (int i = 0; i < fc.repCount; i++) {
                    tape.setFlags(fc.repTapeIdx[i], WireTape.FLAG_CLEAN_RAW);
                }
            }
            case RAW_CLEAN -> {}
        }

        if (!keepSubView && fc.subView != null) {
            fc.subView.parent = null;
            fc.subView = null;
            fc.subViewEpoch = -1;
        }
        if (!keepRepViews) {
            if (fc.repSubViews != null && fc.repCount > 0) {
                for (int r = 0; r < fc.repCount; r++) {
                    if (fc.repSubViews[r] != null) {
                        fc.repSubViews[r].parent = null;
                        fc.repSubViews[r] = null;
                    }
                }
            }
            if (fc.repSubEpochs != null && fc.repCount > 0) {
                Arrays.fill(fc.repSubEpochs, 0, fc.repCount, -1);
            }
        }
        fc.bytesInArena = false;
        fc.extBytes = null;
        fc.stringCache = null;
        fc.state = FieldState.RAW_CLEAN;
    }

    private void enterScalarOverride(FieldSlot fc, byte kind, int expectedWt, int newValLen) {
        leaveCurrentState(fc, false, false);
        fc.state = FieldState.SCALAR_OVERRIDE;
        fc.kind = kind;
        fc.decoded = true;
        fc.present = true;
        if (fc.lastTapeIdx >= 0) {
            for (int i = 0; i < fc.repCount - 1; i++) {
                tape.setFlags(fc.repTapeIdx[i], WireTape.FLAG_SHADOWED);
            }
            if (fc.repCount == 1
                    && tape.wireType(fc.lastTapeIdx) == expectedWt
                    && tape.valueLength(fc.lastTapeIdx) == newValLen) {
                tape.setFlags(fc.lastTapeIdx, WireTape.FLAG_DIRTY_SAME_SIZE);
                fc.dirtyMode = DIRTY_SAME_SIZE;
                sameSizeDirtyCount++;
            } else {
                tape.setFlags(fc.lastTapeIdx, WireTape.FLAG_DIRTY_VAR_SIZE);
                fc.dirtyMode = DIRTY_VAR_SIZE;
                varSizeDirtyCount++;
            }
        } else {
            fc.dirtyMode = DIRTY_APPENDED;
            appendedCount++;
        }
    }

    private void wrapMultiSegments(WireTape parentTape, int[] segIndices, int segCount) {
        tape.resetEmpty();
        tape.buf = parentTape.buf;
        tape.multiSegment = true;
        resetLocal();
        int total = 0;
        for (int i = 0; i < segCount; i++) {
            int sIdx = segIndices[i];
            int vOff = parentTape.valueOffset(sIdx);
            int rEnd = parentTape.recordEnd(sIdx);
            total += (rEnd - vOff);
            tape.scanRange(parentTape.buf, vOff, rEnd, scanVisitor);
        }
        tape.rawLength = total;
    }

    private void notifyParent() {
        if (parent != null) parent.onChildDirty(parentFieldNumber, parentRepIdx, this);
    }

    private void onChildDirty(int fn, int repIdx, RawWireView child) {
        FieldSlot fc = findOrCreateSlot(fn);
        if (repIdx >= 0) {
            if (fc.state != FieldState.REPEATED_PATCHED) {
                leaveCurrentState(fc, false, true);
                fc.state = FieldState.REPEATED_PATCHED;
                fc.present = true;
            }
            int tIdx = fc.repTapeIdx[repIdx];
            int prev = tape.flags(tIdx);
            if (prev == WireTape.FLAG_DIRTY_SAME_SIZE) sameSizeDirtyCount--;
            else if (prev == WireTape.FLAG_DIRTY_VAR_SIZE) varSizeDirtyCount--;
            else fc.repDirtyCount++;
            if (child.isSameSizeDirtyOnly()) {
                tape.setFlags(tIdx, WireTape.FLAG_DIRTY_SAME_SIZE);
                sameSizeDirtyCount++;
            } else {
                tape.setFlags(tIdx, WireTape.FLAG_DIRTY_VAR_SIZE);
                varSizeDirtyCount++;
            }
        } else {
            leaveCurrentState(fc, true, false);
            fc.state = FieldState.SUBVIEW_OVERRIDE;
            fc.kind = KIND_SUBVIEW;
            fc.decoded = true;
            fc.present = true;
            if (fc.repCount > 0) {
                for (int i = 0; i < fc.repCount - 1; i++) {
                    tape.setFlags(fc.repTapeIdx[i], WireTape.FLAG_SHADOWED);
                }
                int tail = fc.repTapeIdx[fc.repCount - 1];
                if (fc.repCount == 1 && child.isSameSizeDirtyOnly()) {
                    tape.setFlags(tail, WireTape.FLAG_DIRTY_SAME_SIZE);
                    fc.dirtyMode = DIRTY_SAME_SIZE;
                    sameSizeDirtyCount++;
                } else {
                    tape.setFlags(tail, WireTape.FLAG_DIRTY_VAR_SIZE);
                    fc.dirtyMode = DIRTY_VAR_SIZE;
                    varSizeDirtyCount++;
                }
            } else {
                fc.dirtyMode = DIRTY_APPENDED;
                appendedCount++;
            }
        }
        notifyParent();
    }

    // ==================== Serialization (FP0 / FP1 / FP2 / FP3) ====================

    public int maxSerializedSize() {
        if (isSelfClean() || isSelfSameSizeDirtyOnly()) {
            return tape.rawLength;
        }
        int sz = tape.rawLength;
        for (int i = 0; i < slotCount; i++) {
            FieldSlot fc = slots[i];
            int tagSz = WireWriter.tagSize(fc.fieldNumber);
            switch (fc.state) {
                case REPEATED_PATCHED -> {
                    if (fc.repSubViews != null) {
                        for (int r = 0; r < fc.repCount; r++) {
                            RawWireView rv = fc.repSubViews[r];
                            if (rv != null && (fc.repSubEpochs == null || fc.repSubEpochs[r] == wrapEpoch) && !rv.isClean()) {
                                int subMax = rv.maxSerializedSize();
                                sz += tagSz + WireWriter.varint32Size(subMax) + subMax;
                            }
                        }
                    }
                }
                case SUBVIEW_OVERRIDE -> {
                    if ((fc.dirtyMode == DIRTY_VAR_SIZE || fc.dirtyMode == DIRTY_APPENDED) && fc.subView != null) {
                        int subMax = fc.subView.maxSerializedSize();
                        sz += tagSz + WireWriter.varint32Size(subMax) + subMax;
                    }
                }
                case SCALAR_OVERRIDE -> {
                    if (fc.dirtyMode == DIRTY_VAR_SIZE || fc.dirtyMode == DIRTY_APPENDED) {
                        if (fc.kind == KIND_BYTES) {
                            int len = fc.bytesInArena ? ScratchUtf8Arena.unpackLength(fc.arenaHandle) : fc.extLen;
                            sz += tagSz + WireWriter.varint32Size(len) + len;
                        } else if (fc.kind == KIND_FIXED32) {
                            sz += tagSz + 4;
                        } else if (fc.kind == KIND_FIXED64) {
                            sz += tagSz + 8;
                        } else {
                            sz += tagSz + 10;
                        }
                    }
                }
                case RAW_CLEAN, DELETED -> {}
            }
        }
        return sz;
    }

    public int writeToBuffer(byte[] outBuf, int outOffset) {
        if (isSelfClean()) {
            int len = tape.rawLength;
            if (len > 0) System.arraycopy(tape.buf, tape.rawOffset, outBuf, outOffset, len);
            return len;
        }
        if (isSelfSameSizeDirtyOnly()) {
            int len = tape.rawLength;
            if (len > 0) System.arraycopy(tape.buf, tape.rawOffset, outBuf, outOffset, len);
            applyInPlacePatches(outBuf, tape.rawOffset - outOffset);
            return len;
        }
        WireWriter w = cachedWriter;
        if (w == null) { w = new WireWriter(outBuf, outOffset); cachedWriter = w; }
        else w.reset(outBuf, outOffset);
        writeSplicedTo(w);
        return w.position() - outOffset;
    }

    public byte[] toByteArray() {
        int cap = maxSerializedSize();
        byte[] tmp = new byte[cap];
        int len = writeToBuffer(tmp, 0);
        return len == cap ? tmp : Arrays.copyOf(tmp, len);
    }

    private void applyInPlacePatches(byte[] target, int baseOffset) {
        for (int i = 0; i < slotCount; i++) {
            FieldSlot fc = slots[i];
            switch (fc.state) {
                case SUBVIEW_OVERRIDE -> {
                    if (fc.dirtyMode == DIRTY_SAME_SIZE && fc.lastTapeIdx >= 0 && fc.subView != null) {
                        int pos = tape.valueOffset(fc.lastTapeIdx) - baseOffset;
                        fc.subView.applyInPlacePatches(target, tape.valueOffset(fc.lastTapeIdx) - pos);
                    }
                }
                case SCALAR_OVERRIDE -> {
                    if (fc.dirtyMode == DIRTY_SAME_SIZE && fc.lastTapeIdx >= 0) {
                        int pos = tape.valueOffset(fc.lastTapeIdx) - baseOffset;
                        switch (fc.kind) {
                            case KIND_VARINT -> WireWriter.patchVarint64At(target, pos, fc.numVal);
                            case KIND_FIXED32 -> WireWriter.patchFixed32At(target, pos, (int) fc.numVal);
                            case KIND_FIXED64 -> WireWriter.patchFixed64At(target, pos, fc.numVal);
                            case KIND_BYTES -> {
                                if (fc.bytesInArena) {
                                    WireWriter.patchBytesAt(target, pos, arena.buffer(),
                                            ScratchUtf8Arena.unpackOffset(fc.arenaHandle),
                                            ScratchUtf8Arena.unpackLength(fc.arenaHandle));
                                } else if (fc.extBytes != null) {
                                    WireWriter.patchBytesAt(target, pos, fc.extBytes, fc.extOff, fc.extLen);
                                }
                            }
                        }
                    }
                }
                case REPEATED_PATCHED -> {
                    if (fc.repSubViews != null) {
                        for (int r = 0; r < fc.repCount; r++) {
                            RawWireView child = fc.repSubViews[r];
                            if (child != null && (fc.repSubEpochs == null || fc.repSubEpochs[r] == wrapEpoch)
                                    && tape.flags(fc.repTapeIdx[r]) == WireTape.FLAG_DIRTY_SAME_SIZE) {
                                int pos = tape.valueOffset(fc.repTapeIdx[r]) - baseOffset;
                                child.applyInPlacePatches(target, tape.valueOffset(fc.repTapeIdx[r]) - pos);
                            }
                        }
                    }
                }
                case RAW_CLEAN, DELETED -> {}
            }
        }
    }

    private void writeSplicedTo(WireWriter w) {
        if (isSelfClean()) {
            if (tape.rawLength > 0) {
                w.writeRawBytes(tape.buf, tape.rawOffset, tape.rawLength);
            }
            return;
        }
        if (isSelfSameSizeDirtyOnly()) {
            int start = w.position();
            if (tape.rawLength > 0) {
                w.writeRawBytes(tape.buf, tape.rawOffset, tape.rawLength);
                applyInPlacePatches(w.buffer(), tape.rawOffset - start);
            }
            return;
        }
        if (tape.tapeCount > 0) {
            tape.spliceTo(w, dirtyWriter);
        }
        if (appendedCount > 0) {
            for (int i = 0; i < slotCount; i++) {
                FieldSlot fc = slots[i];
                if (fc.dirtyMode == DIRTY_APPENDED && fc.present && fc.state != FieldState.DELETED) {
                    writeFieldValue(fc, w);
                }
            }
        }
    }

    private void writeDirtyEntry(int tapeIdx, int fn, int wt, int aux, WireWriter writer) {
        int sIdx = findSlotIdx(fn);
        if (sIdx < 0) return;
        FieldSlot fc = slots[sIdx];
        if (fc.state == FieldState.REPEATED_PATCHED && fc.repSubViews != null && wt == WireFormat.WIRETYPE_LENGTH_DELIMITED) {
            for (int r = 0; r < fc.repCount; r++) {
                if (fc.repTapeIdx[r] == tapeIdx && fc.repSubViews[r] != null
                        && (fc.repSubEpochs == null || fc.repSubEpochs[r] == wrapEpoch)) {
                    int lenSlot = writer.beginSubmessage(fn);
                    fc.repSubViews[r].writeSplicedTo(writer);
                    writer.endSubmessage(lenSlot);
                    return;
                }
            }
        }
        writeFieldValue(fc, writer);
    }

    private void writeFieldValue(FieldSlot fc, WireWriter w) {
        if (fc.state == FieldState.SUBVIEW_OVERRIDE && fc.subView != null) {
            int lenSlot = w.beginSubmessage(fc.fieldNumber);
            fc.subView.writeSplicedTo(w);
            w.endSubmessage(lenSlot);
            return;
        }
        switch (fc.kind) {
            case KIND_VARINT -> { w.writeTag(fc.fieldNumber, WireFormat.WIRETYPE_VARINT); w.writeVarint64(fc.numVal); }
            case KIND_FIXED32 -> { w.writeTag(fc.fieldNumber, WireFormat.WIRETYPE_FIXED32); w.writeFixed32((int) fc.numVal); }
            case KIND_FIXED64 -> { w.writeTag(fc.fieldNumber, WireFormat.WIRETYPE_FIXED64); w.writeFixed64(fc.numVal); }
            case KIND_BYTES -> {
                if (fc.bytesInArena) {
                    w.writeLengthDelimitedBytes(fc.fieldNumber, arena.buffer(),
                            ScratchUtf8Arena.unpackOffset(fc.arenaHandle),
                            ScratchUtf8Arena.unpackLength(fc.arenaHandle));
                } else {
                    w.writeLengthDelimitedBytes(fc.fieldNumber,
                            fc.extBytes != null ? fc.extBytes : WireTape.EMPTY_BYTES, fc.extOff, fc.extLen);
                }
            }
            case KIND_SUBVIEW -> {
                if (fc.subView != null) {
                    int lenSlot = w.beginSubmessage(fc.fieldNumber);
                    fc.subView.writeSplicedTo(w);
                    w.endSubmessage(lenSlot);
                }
            }
        }
    }
}
