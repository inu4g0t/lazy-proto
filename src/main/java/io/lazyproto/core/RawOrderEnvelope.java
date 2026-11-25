package io.lazyproto.core;

import io.lazyproto.wire.Utf8Slice;
import io.lazyproto.wire.WireFormat;
import io.lazyproto.wire.WireReader;
import io.lazyproto.wire.WireWriter;

import java.util.Arrays;

/**
 * Java Lower-Level Input-Bytes Wrapper for {@code OrderEnvelope} (Zero {@code com.google.protobuf} dependencies).
 *
 * <p>Single-threaded, mutable, zero-allocation reusable wrapper:
 * <ul>
 *   <li>{@link #wrap(byte[], int, int)} binds raw wire bytes in O(1).</li>
 *   <li>Reads decode lazily on first access and cache into primitive/slice slots without marking dirty.</li>
 *   <li>Writes update the slot cache + inline {@link WireTape} flags (with {@link ScratchUtf8Arena} avoiding
 *       {@code byte[]} allocations on string edits) and automatically propagate child dirty state.</li>
 *   <li>{@link #writeToBuffer(byte[], int)} merges changes with untouched original bytes using FP0/FP1/FP2
 *       and 1-pass speculative length backpatching.</li>
 * </ul>
 */
public final class RawOrderEnvelope {

    public static final int SLOT_ORDER_ID   = 0;
    public static final int SLOT_BUYER_NAME = 1;
    public static final int SLOT_STATUS     = 2;
    public static final int SLOT_UPDATED_AT = 3;
    public static final int SLOT_USER       = 4;
    public static final int SLOT_ITEMS      = 5;
    public static final int SLOT_PAYLOAD    = 6;
    public static final int SLOT_AUTH_TOKEN = 7;
    public static final int SLOT_TRACE_ID   = 8;
    public static final int SLOT_TOTAL      = 9;

    public static final int BIT_ORDER_ID   = 1 << 0;
    public static final int BIT_BUYER_NAME = 1 << 1;
    public static final int BIT_STATUS     = 1 << 2;
    public static final int BIT_UPDATED_AT = 1 << 3;
    public static final int BIT_USER       = 1 << 4;
    public static final int BIT_ITEMS      = 1 << 5;
    public static final int BIT_PAYLOAD    = 1 << 6;
    public static final int BIT_AUTH_TOKEN = 1 << 7;
    public static final int BIT_TRACE_ID   = 1 << 8;
    public static final int BIT_TOTAL      = 1 << 9;

    private final WireTape tape = new WireTape(160);
    private final ScratchUtf8Arena arena = new ScratchUtf8Arena(256);
    private final int[] slotTapeIdx = new int[10];
    private int[] userSegTapeIdx = new int[4];
    private int userSegCount;
    private int[] itemTapeIdx = new int[128];
    private int itemCount;

    private boolean hasDeleted;
    private int presentMask;
    private int decodedMask;
    private int sameSizeDirtyMask;
    private int varSizeDirtyMask;
    private int appendedMask;

    private long orderId;
    private String buyerNameCache;
    private long buyerNameArenaHandle;
    private boolean buyerNameInArena;
    private final Utf8Slice buyerNameSlice = new Utf8Slice();

    private int status;
    private long updatedAtFixed64;

    private final RawUserProfile user = new RawUserProfile(this);
    private RawLineItem[] activeItems = new RawLineItem[128];
    private byte[] activeItemStates = new byte[128]; // 0=clean, 1=sameSize, 2=varSize
    private int sameSizeItemCount;
    private int varSizeItemCount;

    private RawLineItem[] appendedItems = new RawLineItem[16];
    private int appendedItemCount;

    private RawLineItem[] itemPool = new RawLineItem[128];
    private int poolUsed;

    private byte[] payloadExternal;
    private int payloadExtOff;
    private int payloadExtLen;
    private long payloadArenaHandle;
    private boolean payloadInArena;

    private String authTokenCache;
    private long authTokenArenaHandle;
    private boolean authTokenInArena;
    private final Utf8Slice authTokenSlice = new Utf8Slice();

    private String traceIdCache;
    private long traceIdArenaHandle;
    private boolean traceIdInArena;
    private final Utf8Slice traceIdSlice = new Utf8Slice();

    private long totalCents;

    private WireWriter cachedWriter;
    private final WireTape.ScanVisitor scanVisitor = this::onScanField;
    private final WireTape.DirtyEntryWriter dirtyEntryWriter = this::writeDirtyTapeEntry;

    public RawOrderEnvelope() {
        Arrays.fill(slotTapeIdx, -1);
    }

    public RawOrderEnvelope wrap(byte[] buf, int off, int len) {
        tape.reset(buf, off, len);
        resetState();
        return this;
    }

    public RawOrderEnvelope initScratch() {
        tape.resetEmpty();
        resetState();
        return this;
    }

    private void resetState() {
        arena.reset();
        Arrays.fill(slotTapeIdx, -1);
        userSegCount = 0;
        itemCount = 0;
        hasDeleted = false;
        presentMask = 0;
        decodedMask = 0;
        sameSizeDirtyMask = 0;
        varSizeDirtyMask = 0;
        appendedMask = 0;
        buyerNameCache = null;
        buyerNameInArena = false;
        authTokenCache = null;
        authTokenInArena = false;
        traceIdCache = null;
        traceIdInArena = false;
        payloadExternal = null;
        payloadInArena = false;
        if (sameSizeItemCount > 0 || varSizeItemCount > 0 || poolUsed > 0) {
            Arrays.fill(activeItems, 0, Math.min(activeItems.length, poolUsed + itemCount), null);
            Arrays.fill(activeItemStates, 0, Math.min(activeItemStates.length, itemCount), (byte) 0);
        }
        sameSizeItemCount = 0;
        varSizeItemCount = 0;
        appendedItemCount = 0;
        poolUsed = 0;
    }

    public boolean isScanned() { return tape.scanned; }

    public boolean isClean() {
        return !tape.multiSegment && (sameSizeDirtyMask | varSizeDirtyMask | appendedMask) == 0 && !hasDeleted;
    }

    public boolean isSameSizeDirtyOnly() {
        return !tape.multiSegment && (varSizeDirtyMask | appendedMask) == 0 && !hasDeleted && sameSizeDirtyMask != 0;
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
        if (fn == 6 && wt == WireFormat.WIRETYPE_LENGTH_DELIMITED) {
            if (itemCount == itemTapeIdx.length) {
                itemTapeIdx = Arrays.copyOf(itemTapeIdx, itemTapeIdx.length << 1);
            }
            itemTapeIdx[itemCount++] = tapeIdx;
            presentMask |= BIT_ITEMS;
            return;
        }
        if (fn == 5 && wt == WireFormat.WIRETYPE_LENGTH_DELIMITED) {
            if (userSegCount == userSegTapeIdx.length) {
                userSegTapeIdx = Arrays.copyOf(userSegTapeIdx, userSegTapeIdx.length << 1);
            }
            userSegTapeIdx[userSegCount++] = tapeIdx;
            slotTapeIdx[SLOT_USER] = tapeIdx;
            presentMask |= BIT_USER;
            return;
        }
        int slot = switch (fn) {
            case 1 -> wt == WireFormat.WIRETYPE_VARINT ? SLOT_ORDER_ID : -1;
            case 2 -> wt == WireFormat.WIRETYPE_LENGTH_DELIMITED ? SLOT_BUYER_NAME : -1;
            case 3 -> wt == WireFormat.WIRETYPE_VARINT ? SLOT_STATUS : -1;
            case 4 -> wt == WireFormat.WIRETYPE_FIXED64 ? SLOT_UPDATED_AT : -1;
            case 7 -> wt == WireFormat.WIRETYPE_LENGTH_DELIMITED ? SLOT_PAYLOAD : -1;
            case 8 -> wt == WireFormat.WIRETYPE_LENGTH_DELIMITED ? SLOT_AUTH_TOKEN : -1;
            case 9 -> wt == WireFormat.WIRETYPE_LENGTH_DELIMITED ? SLOT_TRACE_ID : -1;
            case 10 -> wt == WireFormat.WIRETYPE_VARINT ? SLOT_TOTAL : -1;
            default -> -1;
        };
        if (slot >= 0) {
            int prev = slotTapeIdx[slot];
            if (prev >= 0) {
                tape.setFlags(prev, WireTape.FLAG_SHADOWED);
            }
            slotTapeIdx[slot] = tapeIdx;
            presentMask |= (1 << slot);
        }
    }

    // ==================== Field Getters ====================

    public long getOrderId() {
        if ((decodedMask & BIT_ORDER_ID) == 0) {
            ensureScanned();
            int idx = slotTapeIdx[SLOT_ORDER_ID];
            orderId = idx >= 0 ? WireReader.readVarint64At(tape.buf, tape.valueOffset(idx)) : 0L;
            decodedMask |= BIT_ORDER_ID;
        }
        return orderId;
    }

    public String getBuyerName() {
        if ((decodedMask & BIT_BUYER_NAME) == 0) {
            ensureScanned();
            int idx = slotTapeIdx[SLOT_BUYER_NAME];
            buyerNameCache = idx >= 0
                    ? WireReader.readStringAt(tape.buf, tape.valueOffset(idx), tape.valueLength(idx))
                    : "";
            buyerNameInArena = false;
            decodedMask |= BIT_BUYER_NAME;
        }
        if (buyerNameCache == null && buyerNameInArena) {
            buyerNameCache = WireReader.readStringAt(arena.buffer(),
                    ScratchUtf8Arena.unpackOffset(buyerNameArenaHandle),
                    ScratchUtf8Arena.unpackLength(buyerNameArenaHandle));
        }
        return buyerNameCache;
    }

    public Utf8Slice getBuyerNameSlice() {
        if (buyerNameInArena) {
            return buyerNameSlice.wrap(arena.buffer(),
                    ScratchUtf8Arena.unpackOffset(buyerNameArenaHandle),
                    ScratchUtf8Arena.unpackLength(buyerNameArenaHandle));
        }
        ensureScanned();
        int idx = slotTapeIdx[SLOT_BUYER_NAME];
        return idx >= 0
                ? buyerNameSlice.wrap(tape.buf, tape.valueOffset(idx), tape.valueLength(idx))
                : buyerNameSlice.wrap(WireTape.EMPTY_BYTES, 0, 0);
    }

    public int getStatus() {
        if ((decodedMask & BIT_STATUS) == 0) {
            ensureScanned();
            int idx = slotTapeIdx[SLOT_STATUS];
            status = idx >= 0 ? WireReader.readVarint32At(tape.buf, tape.valueOffset(idx)) : 0;
            decodedMask |= BIT_STATUS;
        }
        return status;
    }

    public long getUpdatedAtFixed64() {
        if ((decodedMask & BIT_UPDATED_AT) == 0) {
            ensureScanned();
            int idx = slotTapeIdx[SLOT_UPDATED_AT];
            updatedAtFixed64 = idx >= 0 ? WireReader.readFixed64At(tape.buf, tape.valueOffset(idx)) : 0L;
            decodedMask |= BIT_UPDATED_AT;
        }
        return updatedAtFixed64;
    }

    public RawUserProfile user() {
        if ((decodedMask & BIT_USER) == 0) {
            ensureScanned();
            if (userSegCount > 1) {
                user.wrapMultiSegments(tape, userSegTapeIdx, userSegCount);
            } else if (userSegCount == 1) {
                int idx = userSegTapeIdx[0];
                user.wrap(tape.buf, tape.valueOffset(idx), tape.valueLength(idx));
            } else {
                user.initScratch();
            }
            decodedMask |= BIT_USER;
        }
        return user;
    }

    public int getItemsCount() {
        ensureScanned();
        return itemCount + appendedItemCount;
    }

    public RawLineItem items(int index) {
        ensureScanned();
        if (index >= itemCount) {
            int appIdx = index - itemCount;
            if (appIdx < 0 || appIdx >= appendedItemCount) {
                throw new IndexOutOfBoundsException("Index " + index);
            }
            return appendedItems[appIdx];
        }
        if (index >= activeItems.length) {
            int newCap = Math.max(index + 1, activeItems.length << 1);
            activeItems = Arrays.copyOf(activeItems, newCap);
            activeItemStates = Arrays.copyOf(activeItemStates, newCap);
        }
        RawLineItem item = activeItems[index];
        if (item == null) {
            int tIdx = itemTapeIdx[index];
            item = borrowItem(index);
            item.wrap(tape.buf, tape.valueOffset(tIdx), tape.valueLength(tIdx));
            activeItems[index] = item;
        }
        return item;
    }

    public void readItemEager(int index, RawLineItem outItem) {
        int tIdx = itemTapeIdx[index];
        outItem.decodeAllEager(tape.buf, tape.valueOffset(tIdx), tape.valueLength(tIdx));
    }

    public int getPayloadLength() {
        if (payloadInArena) return ScratchUtf8Arena.unpackLength(payloadArenaHandle);
        if (payloadExternal != null) return payloadExtLen;
        ensureScanned();
        int idx = slotTapeIdx[SLOT_PAYLOAD];
        return idx >= 0 ? tape.valueLength(idx) : 0;
    }

    public String getAuthToken() {
        if ((presentMask & BIT_AUTH_TOKEN) == 0 && (decodedMask & BIT_AUTH_TOKEN) != 0) return "";
        if ((decodedMask & BIT_AUTH_TOKEN) == 0) {
            ensureScanned();
            int idx = slotTapeIdx[SLOT_AUTH_TOKEN];
            authTokenCache = idx >= 0
                    ? WireReader.readStringAt(tape.buf, tape.valueOffset(idx), tape.valueLength(idx))
                    : "";
            authTokenInArena = false;
            decodedMask |= BIT_AUTH_TOKEN;
        }
        if (authTokenCache == null && authTokenInArena) {
            authTokenCache = WireReader.readStringAt(arena.buffer(),
                    ScratchUtf8Arena.unpackOffset(authTokenArenaHandle),
                    ScratchUtf8Arena.unpackLength(authTokenArenaHandle));
        }
        return authTokenCache;
    }

    public String getTraceId() {
        if ((decodedMask & BIT_TRACE_ID) == 0) {
            ensureScanned();
            int idx = slotTapeIdx[SLOT_TRACE_ID];
            traceIdCache = idx >= 0
                    ? WireReader.readStringAt(tape.buf, tape.valueOffset(idx), tape.valueLength(idx))
                    : "";
            traceIdInArena = false;
            decodedMask |= BIT_TRACE_ID;
        }
        if (traceIdCache == null && traceIdInArena) {
            traceIdCache = WireReader.readStringAt(arena.buffer(),
                    ScratchUtf8Arena.unpackOffset(traceIdArenaHandle),
                    ScratchUtf8Arena.unpackLength(traceIdArenaHandle));
        }
        return traceIdCache;
    }

    public long getTotalCents() {
        if ((decodedMask & BIT_TOTAL) == 0) {
            ensureScanned();
            int idx = slotTapeIdx[SLOT_TOTAL];
            totalCents = idx >= 0 ? WireReader.readVarint64At(tape.buf, tape.valueOffset(idx)) : 0L;
            decodedMask |= BIT_TOTAL;
        }
        return totalCents;
    }

    // ==================== Field Setters (Zero-Alloc Arena + Tape Flag Update) ====================

    public RawOrderEnvelope setOrderId(long v) {
        ensureScanned();
        this.orderId = v;
        this.decodedMask |= BIT_ORDER_ID;
        this.presentMask |= BIT_ORDER_ID;
        markModified(SLOT_ORDER_ID, BIT_ORDER_ID, WireFormat.WIRETYPE_VARINT, WireWriter.varint64Size(v));
        return this;
    }

    public RawOrderEnvelope setBuyerName(String v) {
        ensureScanned();
        this.buyerNameCache = v;
        this.buyerNameArenaHandle = arena.storeString(v);
        this.buyerNameInArena = true;
        this.decodedMask |= BIT_BUYER_NAME;
        this.presentMask |= BIT_BUYER_NAME;
        markModified(SLOT_BUYER_NAME, BIT_BUYER_NAME, WireFormat.WIRETYPE_LENGTH_DELIMITED,
                ScratchUtf8Arena.unpackLength(buyerNameArenaHandle));
        return this;
    }

    public RawOrderEnvelope setStatus(int v) {
        ensureScanned();
        this.status = v;
        this.decodedMask |= BIT_STATUS;
        this.presentMask |= BIT_STATUS;
        markModified(SLOT_STATUS, BIT_STATUS, WireFormat.WIRETYPE_VARINT, WireWriter.varint32Size(v));
        return this;
    }

    public RawOrderEnvelope setUpdatedAtFixed64(long v) {
        ensureScanned();
        this.updatedAtFixed64 = v;
        this.decodedMask |= BIT_UPDATED_AT;
        this.presentMask |= BIT_UPDATED_AT;
        markModified(SLOT_UPDATED_AT, BIT_UPDATED_AT, WireFormat.WIRETYPE_FIXED64, 8);
        return this;
    }

    public RawOrderEnvelope setPayloadBytes(byte[] bytes) {
        return setPayloadBytes(bytes, 0, bytes.length);
    }

    public RawOrderEnvelope setPayloadBytes(byte[] bytes, int off, int len) {
        ensureScanned();
        this.payloadExternal = bytes;
        this.payloadExtOff = off;
        this.payloadExtLen = len;
        this.payloadInArena = false;
        this.decodedMask |= BIT_PAYLOAD;
        this.presentMask |= BIT_PAYLOAD;
        markModified(SLOT_PAYLOAD, BIT_PAYLOAD, WireFormat.WIRETYPE_LENGTH_DELIMITED, len);
        return this;
    }

    public RawOrderEnvelope setAuthToken(String v) {
        ensureScanned();
        this.authTokenCache = v;
        this.authTokenArenaHandle = arena.storeString(v);
        this.authTokenInArena = true;
        this.decodedMask |= BIT_AUTH_TOKEN;
        this.presentMask |= BIT_AUTH_TOKEN;
        markModified(SLOT_AUTH_TOKEN, BIT_AUTH_TOKEN, WireFormat.WIRETYPE_LENGTH_DELIMITED,
                ScratchUtf8Arena.unpackLength(authTokenArenaHandle));
        return this;
    }

    public RawOrderEnvelope clearAuthToken() {
        ensureScanned();
        this.authTokenCache = "";
        this.authTokenInArena = false;
        this.decodedMask |= BIT_AUTH_TOKEN;
        this.presentMask &= ~BIT_AUTH_TOKEN;
        this.appendedMask &= ~BIT_AUTH_TOKEN;
        this.sameSizeDirtyMask &= ~BIT_AUTH_TOKEN;
        int idx = slotTapeIdx[SLOT_AUTH_TOKEN];
        if (idx >= 0) {
            tape.setFlags(idx, WireTape.FLAG_DELETED);
            this.hasDeleted = true;
            this.varSizeDirtyMask |= BIT_AUTH_TOKEN;
        } else {
            this.varSizeDirtyMask &= ~BIT_AUTH_TOKEN;
        }
        return this;
    }

    public RawOrderEnvelope setTraceId(String v) {
        ensureScanned();
        this.traceIdCache = v;
        this.traceIdArenaHandle = arena.storeString(v);
        this.traceIdInArena = true;
        this.decodedMask |= BIT_TRACE_ID;
        this.presentMask |= BIT_TRACE_ID;
        markModified(SLOT_TRACE_ID, BIT_TRACE_ID, WireFormat.WIRETYPE_LENGTH_DELIMITED,
                ScratchUtf8Arena.unpackLength(traceIdArenaHandle));
        return this;
    }

    public RawOrderEnvelope setTotalCents(long v) {
        ensureScanned();
        this.totalCents = v;
        this.decodedMask |= BIT_TOTAL;
        this.presentMask |= BIT_TOTAL;
        markModified(SLOT_TOTAL, BIT_TOTAL, WireFormat.WIRETYPE_VARINT, WireWriter.varint64Size(v));
        return this;
    }

    public RawLineItem addItem() {
        ensureScanned();
        int logicalIdx = itemCount + appendedItemCount;
        RawLineItem item = borrowItem(logicalIdx);
        item.initScratch();
        if (appendedItemCount == appendedItems.length) {
            appendedItems = Arrays.copyOf(appendedItems, appendedItems.length << 1);
        }
        appendedItems[appendedItemCount++] = item;
        presentMask |= BIT_ITEMS;
        appendedMask |= BIT_ITEMS;
        varSizeDirtyMask |= BIT_ITEMS;
        return item;
    }

    private RawLineItem borrowItem(int logicalIdx) {
        int p = poolUsed++;
        if (p >= itemPool.length) {
            itemPool = Arrays.copyOf(itemPool, itemPool.length << 1);
        }
        RawLineItem item = itemPool[p];
        if (item == null) {
            item = new RawLineItem(this, arena);
            itemPool[p] = item;
        }
        item.parentItemIdx = logicalIdx;
        return item;
    }

    private void markModified(int slot, int bit, int expectedWt, int newValLen) {
        int idx = slotTapeIdx[slot];
        if (idx >= 0) {
            if (tape.wireType(idx) == expectedWt && tape.valueLength(idx) == newValLen) {
                tape.setFlags(idx, WireTape.FLAG_DIRTY_SAME_SIZE);
                sameSizeDirtyMask |= bit;
                varSizeDirtyMask &= ~bit;
            } else {
                tape.setFlags(idx, WireTape.FLAG_DIRTY_VAR_SIZE);
                sameSizeDirtyMask &= ~bit;
                varSizeDirtyMask |= bit;
            }
            return;
        }
        appendedMask |= bit;
        sameSizeDirtyMask &= ~bit;
        varSizeDirtyMask |= bit;
    }

    void onUserMutated() {
        presentMask |= BIT_USER;
        if (userSegCount > 0) {
            if (userSegCount == 1 && user.isSameSizeDirtyOnly()) {
                tape.setFlags(userSegTapeIdx[0], WireTape.FLAG_DIRTY_SAME_SIZE);
                sameSizeDirtyMask |= BIT_USER;
                varSizeDirtyMask &= ~BIT_USER;
            } else {
                for (int i = 0; i < userSegCount - 1; i++) {
                    tape.setFlags(userSegTapeIdx[i], WireTape.FLAG_SHADOWED);
                }
                tape.setFlags(userSegTapeIdx[userSegCount - 1], WireTape.FLAG_DIRTY_VAR_SIZE);
                sameSizeDirtyMask &= ~BIT_USER;
                varSizeDirtyMask |= BIT_USER;
            }
            appendedMask &= ~BIT_USER;
        } else {
            sameSizeDirtyMask &= ~BIT_USER;
            varSizeDirtyMask |= BIT_USER;
            appendedMask |= BIT_USER;
        }
    }

    void onItemMutated(int itemIdx) {
        if (itemIdx >= itemCount) return;
        RawLineItem item = activeItems[itemIdx];
        byte oldState = activeItemStates[itemIdx];
        byte newState = item.isClean() ? (byte) 0 : (item.isSameSizeDirtyOnly() ? (byte) 1 : (byte) 2);
        if (oldState == 1) sameSizeItemCount--;
        else if (oldState == 2) varSizeItemCount--;
        activeItemStates[itemIdx] = newState;

        int tIdx = itemTapeIdx[itemIdx];
        if (newState == 0) {
            tape.setFlags(tIdx, WireTape.FLAG_CLEAN_RAW);
        } else if (newState == 1) {
            tape.setFlags(tIdx, WireTape.FLAG_DIRTY_SAME_SIZE);
            sameSizeItemCount++;
        } else {
            tape.setFlags(tIdx, WireTape.FLAG_DIRTY_VAR_SIZE);
            varSizeItemCount++;
        }

        if (varSizeItemCount > 0) {
            sameSizeDirtyMask &= ~BIT_ITEMS;
            varSizeDirtyMask |= BIT_ITEMS;
        } else if (sameSizeItemCount > 0) {
            sameSizeDirtyMask |= BIT_ITEMS;
            if (appendedItemCount == 0) varSizeDirtyMask &= ~BIT_ITEMS;
        } else {
            sameSizeDirtyMask &= ~BIT_ITEMS;
            if (appendedItemCount == 0) varSizeDirtyMask &= ~BIT_ITEMS;
        }
    }

    // ==================== 1-Pass Input-Reusing Serialization ====================

    public int writeToBuffer(byte[] outBuf, int outOffset) {
        if (isClean()) {
            int len = tape.rawLength;
            System.arraycopy(tape.buf, tape.rawOffset, outBuf, outOffset, len);
            return len;
        }
        if (isSameSizeDirtyOnly()) {
            int len = tape.rawLength;
            System.arraycopy(tape.buf, tape.rawOffset, outBuf, outOffset, len);
            applyInPlacePatches(outBuf, tape.rawOffset - outOffset);
            return len;
        }
        WireWriter w = cachedWriter;
        if (w == null) {
            w = new WireWriter(outBuf, outOffset);
            cachedWriter = w;
        } else {
            w.reset(outBuf, outOffset);
        }
        writeSplicedTo(w);
        return w.position() - outOffset;
    }

    public byte[] toByteArray() {
        byte[] tmp = new byte[Math.max(256, tape.rawLength + 4096)];
        int len = writeToBuffer(tmp, 0);
        return Arrays.copyOf(tmp, len);
    }

    private void applyInPlacePatches(byte[] target, int baseOffset) {
        int mask = sameSizeDirtyMask;
        while (mask != 0) {
            int slot = Integer.numberOfTrailingZeros(mask);
            if (slot == SLOT_USER) {
                user.applyInPlacePatches(target, baseOffset);
            } else if (slot == SLOT_ITEMS) {
                for (int i = 0; i < itemCount; i++) {
                    if (activeItemStates[i] == 1 && activeItems[i] != null) {
                        activeItems[i].applyInPlacePatches(target, baseOffset);
                    }
                }
            } else {
                int idx = slotTapeIdx[slot];
                if (idx >= 0 && tape.flags(idx) == WireTape.FLAG_DIRTY_SAME_SIZE) {
                    int patchPos = tape.valueOffset(idx) - baseOffset;
                    switch (slot) {
                        case SLOT_ORDER_ID -> WireWriter.patchVarint64At(target, patchPos, orderId);
                        case SLOT_BUYER_NAME -> WireWriter.patchBytesAt(target, patchPos, arena.buffer(),
                                ScratchUtf8Arena.unpackOffset(buyerNameArenaHandle),
                                ScratchUtf8Arena.unpackLength(buyerNameArenaHandle));
                        case SLOT_STATUS -> WireWriter.patchVarint32At(target, patchPos, status);
                        case SLOT_UPDATED_AT -> WireWriter.patchFixed64At(target, patchPos, updatedAtFixed64);
                        case SLOT_PAYLOAD -> WireWriter.patchBytesAt(target, patchPos, payloadExternal, payloadExtOff, payloadExtLen);
                        case SLOT_AUTH_TOKEN -> WireWriter.patchBytesAt(target, patchPos, arena.buffer(),
                                ScratchUtf8Arena.unpackOffset(authTokenArenaHandle),
                                ScratchUtf8Arena.unpackLength(authTokenArenaHandle));
                        case SLOT_TRACE_ID -> WireWriter.patchBytesAt(target, patchPos, arena.buffer(),
                                ScratchUtf8Arena.unpackOffset(traceIdArenaHandle),
                                ScratchUtf8Arena.unpackLength(traceIdArenaHandle));
                        case SLOT_TOTAL -> WireWriter.patchVarint64At(target, patchPos, totalCents);
                    }
                }
            }
            mask &= (mask - 1);
        }
    }

    private void writeSplicedTo(WireWriter w) {
        if (tape.tapeCount > 0) {
            tape.spliceTo(w, dirtyEntryWriter);
        }
        if (appendedMask != 0) {
            writeAppended(w);
        }
    }

    private void writeDirtyTapeEntry(int tapeIdx, int fn, int wt, int aux, WireWriter writer) {
        switch (fn) {
            case 1 -> { writer.writeTag(1, WireFormat.WIRETYPE_VARINT); writer.writeVarint64(orderId); }
            case 2 -> writer.writeLengthDelimitedBytes(2, arena.buffer(),
                    ScratchUtf8Arena.unpackOffset(buyerNameArenaHandle),
                    ScratchUtf8Arena.unpackLength(buyerNameArenaHandle));
            case 3 -> { writer.writeTag(3, WireFormat.WIRETYPE_VARINT); writer.writeVarint32(status); }
            case 4 -> { writer.writeTag(4, WireFormat.WIRETYPE_FIXED64); writer.writeFixed64(updatedAtFixed64); }
            case 5 -> {
                int lenSlot = writer.beginSubmessageOneByteTag(0x2A);
                user.writeSplicedTo(writer);
                writer.endSubmessage(lenSlot);
            }
            case 6 -> {
                int itemIdx = Arrays.binarySearch(itemTapeIdx, 0, itemCount, tapeIdx);
                int lenSlot = writer.beginSubmessageOneByteTag(0x32);
                activeItems[itemIdx].writeSplicedTo(writer);
                writer.endSubmessage(lenSlot);
            }
            case 7 -> writer.writeLengthDelimitedBytes(7, payloadExternal, payloadExtOff, payloadExtLen);
            case 8 -> writer.writeLengthDelimitedBytes(8, arena.buffer(),
                    ScratchUtf8Arena.unpackOffset(authTokenArenaHandle),
                    ScratchUtf8Arena.unpackLength(authTokenArenaHandle));
            case 9 -> writer.writeLengthDelimitedBytes(9, arena.buffer(),
                    ScratchUtf8Arena.unpackOffset(traceIdArenaHandle),
                    ScratchUtf8Arena.unpackLength(traceIdArenaHandle));
            case 10 -> { writer.writeTag(10, WireFormat.WIRETYPE_VARINT); writer.writeVarint64(totalCents); }
        }
    }

    private void writeAppended(WireWriter w) {
        if ((appendedMask & BIT_ORDER_ID) != 0 && orderId != 0L) {
            w.writeTag(1, WireFormat.WIRETYPE_VARINT);
            w.writeVarint64(orderId);
        }
        if ((appendedMask & BIT_BUYER_NAME) != 0 && buyerNameInArena) {
            int len = ScratchUtf8Arena.unpackLength(buyerNameArenaHandle);
            if (len > 0) w.writeLengthDelimitedBytes(2, arena.buffer(), ScratchUtf8Arena.unpackOffset(buyerNameArenaHandle), len);
        }
        if ((appendedMask & BIT_STATUS) != 0 && status != 0) {
            w.writeTag(3, WireFormat.WIRETYPE_VARINT);
            w.writeVarint32(status);
        }
        if ((appendedMask & BIT_UPDATED_AT) != 0 && updatedAtFixed64 != 0L) {
            w.writeTag(4, WireFormat.WIRETYPE_FIXED64);
            w.writeFixed64(updatedAtFixed64);
        }
        if ((appendedMask & BIT_USER) != 0) {
            int lenSlot = w.beginSubmessageOneByteTag(0x2A);
            user.writeSplicedTo(w);
            w.endSubmessage(lenSlot);
        }
        if ((appendedMask & BIT_ITEMS) != 0) {
            for (int i = 0; i < appendedItemCount; i++) {
                int lenSlot = w.beginSubmessageOneByteTag(0x32);
                appendedItems[i].writeSplicedTo(w);
                w.endSubmessage(lenSlot);
            }
        }
        if ((appendedMask & BIT_PAYLOAD) != 0 && payloadExternal != null && payloadExtLen > 0) {
            w.writeLengthDelimitedBytes(7, payloadExternal, payloadExtOff, payloadExtLen);
        }
        if ((appendedMask & BIT_AUTH_TOKEN) != 0 && authTokenInArena) {
            int len = ScratchUtf8Arena.unpackLength(authTokenArenaHandle);
            if (len > 0) w.writeLengthDelimitedBytes(8, arena.buffer(), ScratchUtf8Arena.unpackOffset(authTokenArenaHandle), len);
        }
        if ((appendedMask & BIT_TRACE_ID) != 0 && traceIdInArena) {
            int len = ScratchUtf8Arena.unpackLength(traceIdArenaHandle);
            if (len > 0) w.writeLengthDelimitedBytes(9, arena.buffer(), ScratchUtf8Arena.unpackOffset(traceIdArenaHandle), len);
        }
        if ((appendedMask & BIT_TOTAL) != 0 && totalCents != 0L) {
            w.writeTag(10, WireFormat.WIRETYPE_VARINT);
            w.writeVarint64(totalCents);
        }
    }

    // =========================================================================
    // Nested Lower-Level Views (UserProfile & LineItem)
    // =========================================================================

    public static final class RawUserProfile {
        private static final int SLOT_USER_ID = 0, SLOT_CITY = 1, SLOT_VIP = 2, SLOT_SCORE = 3, SLOT_EMAIL = 4;
        private static final int BIT_USER_ID = 1, BIT_CITY = 2, BIT_VIP = 4, BIT_SCORE = 8, BIT_EMAIL = 16;

        private final RawOrderEnvelope parent;
        private final WireTape tape = new WireTape(8);
        private final int[] slotTapeIdx = new int[5];
        private final WireTape.ScanVisitor scanVisitor = this::onScan;
        private final WireTape.DirtyEntryWriter dirtyWriter = (tapeIdx, fn, wt, aux, writer) -> writeField(fn, writer);
        private int decodedMask, sameSizeDirtyMask, varSizeDirtyMask, appendedMask;

        private long userId;
        private String cityCache;
        private long cityArenaHandle;
        private boolean cityInArena;
        private int vipLevel;
        private double score;
        private String emailCache;
        private long emailArenaHandle;
        private boolean emailInArena;

        RawUserProfile(RawOrderEnvelope parent) {
            this.parent = parent;
            Arrays.fill(slotTapeIdx, -1);
        }

        void wrap(byte[] buf, int off, int len) {
            tape.reset(buf, off, len);
            resetLocal();
        }

        void wrapMultiSegments(WireTape parentTape, int[] segIndices, int segCount) {
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

        void initScratch() {
            tape.resetEmpty();
            resetLocal();
        }

        private void resetLocal() {
            Arrays.fill(slotTapeIdx, -1);
            decodedMask = 0;
            sameSizeDirtyMask = 0;
            varSizeDirtyMask = 0;
            appendedMask = 0;
            cityCache = null;
            cityInArena = false;
            emailCache = null;
            emailInArena = false;
        }

        boolean isClean() {
            return !tape.multiSegment && (sameSizeDirtyMask | varSizeDirtyMask | appendedMask) == 0;
        }

        boolean isSameSizeDirtyOnly() {
            return !tape.multiSegment && (varSizeDirtyMask | appendedMask) == 0 && sameSizeDirtyMask != 0;
        }

        private void ensureScanned() {
            if (!tape.scanned) {
                tape.scanned = true;
                if (tape.rawLength > 0) {
                    tape.scanRange(tape.buf, tape.rawOffset, tape.rawOffset + tape.rawLength, scanVisitor);
                }
            }
        }

        private void onScan(int idx, int fn, int wt) {
            int slot = switch (fn) {
                case 1 -> wt == WireFormat.WIRETYPE_VARINT ? SLOT_USER_ID : -1;
                case 2 -> wt == WireFormat.WIRETYPE_LENGTH_DELIMITED ? SLOT_CITY : -1;
                case 3 -> wt == WireFormat.WIRETYPE_VARINT ? SLOT_VIP : -1;
                case 4 -> wt == WireFormat.WIRETYPE_FIXED64 ? SLOT_SCORE : -1;
                case 5 -> wt == WireFormat.WIRETYPE_LENGTH_DELIMITED ? SLOT_EMAIL : -1;
                default -> -1;
            };
            if (slot >= 0) {
                int prev = slotTapeIdx[slot];
                if (prev >= 0) tape.setFlags(prev, WireTape.FLAG_SHADOWED);
                slotTapeIdx[slot] = idx;
            }
        }

        public long getUserId() {
            if ((decodedMask & BIT_USER_ID) == 0) {
                ensureScanned();
                int idx = slotTapeIdx[SLOT_USER_ID];
                userId = idx >= 0 ? WireReader.readVarint64At(tape.buf, tape.valueOffset(idx)) : 0L;
                decodedMask |= BIT_USER_ID;
            }
            return userId;
        }

        public String getCity() {
            if ((decodedMask & BIT_CITY) == 0) {
                ensureScanned();
                int idx = slotTapeIdx[SLOT_CITY];
                cityCache = idx >= 0 ? WireReader.readStringAt(tape.buf, tape.valueOffset(idx), tape.valueLength(idx)) : "";
                cityInArena = false;
                decodedMask |= BIT_CITY;
            }
            if (cityCache == null && cityInArena) {
                cityCache = WireReader.readStringAt(parent.arena.buffer(),
                        ScratchUtf8Arena.unpackOffset(cityArenaHandle), ScratchUtf8Arena.unpackLength(cityArenaHandle));
            }
            return cityCache;
        }

        public int getVipLevel() {
            if ((decodedMask & BIT_VIP) == 0) {
                ensureScanned();
                int idx = slotTapeIdx[SLOT_VIP];
                vipLevel = idx >= 0 ? WireReader.readVarint32At(tape.buf, tape.valueOffset(idx)) : 0;
                decodedMask |= BIT_VIP;
            }
            return vipLevel;
        }

        public double getScore() {
            if ((decodedMask & BIT_SCORE) == 0) {
                ensureScanned();
                int idx = slotTapeIdx[SLOT_SCORE];
                score = idx >= 0 ? WireReader.readDoubleAt(tape.buf, tape.valueOffset(idx)) : 0.0;
                decodedMask |= BIT_SCORE;
            }
            return score;
        }

        public String getEmail() {
            if ((decodedMask & BIT_EMAIL) == 0) {
                ensureScanned();
                int idx = slotTapeIdx[SLOT_EMAIL];
                emailCache = idx >= 0 ? WireReader.readStringAt(tape.buf, tape.valueOffset(idx), tape.valueLength(idx)) : "";
                emailInArena = false;
                decodedMask |= BIT_EMAIL;
            }
            if (emailCache == null && emailInArena) {
                emailCache = WireReader.readStringAt(parent.arena.buffer(),
                        ScratchUtf8Arena.unpackOffset(emailArenaHandle), ScratchUtf8Arena.unpackLength(emailArenaHandle));
            }
            return emailCache;
        }

        public RawUserProfile setUserId(long v) {
            ensureScanned();
            this.userId = v;
            this.decodedMask |= BIT_USER_ID;
            markModified(SLOT_USER_ID, BIT_USER_ID, WireFormat.WIRETYPE_VARINT, WireWriter.varint64Size(v));
            parent.onUserMutated();
            return this;
        }

        public RawUserProfile setCity(String v) {
            ensureScanned();
            this.cityCache = v;
            this.cityArenaHandle = parent.arena.storeString(v);
            this.cityInArena = true;
            this.decodedMask |= BIT_CITY;
            markModified(SLOT_CITY, BIT_CITY, WireFormat.WIRETYPE_LENGTH_DELIMITED,
                    ScratchUtf8Arena.unpackLength(cityArenaHandle));
            parent.onUserMutated();
            return this;
        }

        public RawUserProfile setVipLevel(int v) {
            ensureScanned();
            this.vipLevel = v;
            this.decodedMask |= BIT_VIP;
            markModified(SLOT_VIP, BIT_VIP, WireFormat.WIRETYPE_VARINT, WireWriter.varint32Size(v));
            parent.onUserMutated();
            return this;
        }

        public RawUserProfile setScore(double v) {
            ensureScanned();
            this.score = v;
            this.decodedMask |= BIT_SCORE;
            markModified(SLOT_SCORE, BIT_SCORE, WireFormat.WIRETYPE_FIXED64, 8);
            parent.onUserMutated();
            return this;
        }

        public RawUserProfile setEmail(String v) {
            ensureScanned();
            this.emailCache = v;
            this.emailArenaHandle = parent.arena.storeString(v);
            this.emailInArena = true;
            this.decodedMask |= BIT_EMAIL;
            markModified(SLOT_EMAIL, BIT_EMAIL, WireFormat.WIRETYPE_LENGTH_DELIMITED,
                    ScratchUtf8Arena.unpackLength(emailArenaHandle));
            parent.onUserMutated();
            return this;
        }

        private void markModified(int slot, int bit, int expectedWt, int newValLen) {
            int idx = slotTapeIdx[slot];
            if (idx >= 0) {
                if (tape.wireType(idx) == expectedWt && tape.valueLength(idx) == newValLen) {
                    tape.setFlags(idx, WireTape.FLAG_DIRTY_SAME_SIZE);
                    sameSizeDirtyMask |= bit;
                    varSizeDirtyMask &= ~bit;
                } else {
                    tape.setFlags(idx, WireTape.FLAG_DIRTY_VAR_SIZE);
                    sameSizeDirtyMask &= ~bit;
                    varSizeDirtyMask |= bit;
                }
                return;
            }
            appendedMask |= bit;
            sameSizeDirtyMask &= ~bit;
            varSizeDirtyMask |= bit;
        }

        void applyInPlacePatches(byte[] target, int baseOffset) {
            int mask = sameSizeDirtyMask;
            while (mask != 0) {
                int slot = Integer.numberOfTrailingZeros(mask);
                int idx = slotTapeIdx[slot];
                if (idx >= 0 && tape.flags(idx) == WireTape.FLAG_DIRTY_SAME_SIZE) {
                    int patchPos = tape.valueOffset(idx) - baseOffset;
                    switch (slot) {
                        case SLOT_USER_ID -> WireWriter.patchVarint64At(target, patchPos, userId);
                        case SLOT_CITY -> WireWriter.patchBytesAt(target, patchPos, parent.arena.buffer(),
                                ScratchUtf8Arena.unpackOffset(cityArenaHandle),
                                ScratchUtf8Arena.unpackLength(cityArenaHandle));
                        case SLOT_VIP -> WireWriter.patchVarint32At(target, patchPos, vipLevel);
                        case SLOT_SCORE -> WireWriter.patchFixed64At(target, patchPos, Double.doubleToRawLongBits(score));
                        case SLOT_EMAIL -> WireWriter.patchBytesAt(target, patchPos, parent.arena.buffer(),
                                ScratchUtf8Arena.unpackOffset(emailArenaHandle),
                                ScratchUtf8Arena.unpackLength(emailArenaHandle));
                    }
                }
                mask &= (mask - 1);
            }
        }

        void writeSplicedTo(WireWriter w) {
            if (isClean()) {
                w.writeRawBytes(tape.buf, tape.rawOffset, tape.rawLength);
                return;
            }
            if (isSameSizeDirtyOnly()) {
                int start = w.position();
                w.writeRawBytes(tape.buf, tape.rawOffset, tape.rawLength);
                applyInPlacePatches(w.buffer(), tape.rawOffset - start);
                return;
            }
            if (tape.tapeCount > 0) {
                tape.spliceTo(w, dirtyWriter);
            }
            int m = appendedMask;
            while (m != 0) {
                int slot = Integer.numberOfTrailingZeros(m);
                writeField(slot + 1, w);
                m &= (m - 1);
            }
        }

        private void writeField(int fn, WireWriter w) {
            switch (fn) {
                case 1 -> { if (userId != 0L || slotTapeIdx[0] >= 0) { w.writeTag(1, WireFormat.WIRETYPE_VARINT); w.writeVarint64(userId); } }
                case 2 -> {
                    int len = cityInArena ? ScratchUtf8Arena.unpackLength(cityArenaHandle) : 0;
                    if (len > 0 || slotTapeIdx[1] >= 0) w.writeLengthDelimitedBytes(2, parent.arena.buffer(), ScratchUtf8Arena.unpackOffset(cityArenaHandle), len);
                }
                case 3 -> { if (vipLevel != 0 || slotTapeIdx[2] >= 0) { w.writeTag(3, WireFormat.WIRETYPE_VARINT); w.writeVarint32(vipLevel); } }
                case 4 -> { if (score != 0.0 || slotTapeIdx[3] >= 0) { w.writeTag(4, WireFormat.WIRETYPE_FIXED64); w.writeDouble(score); } }
                case 5 -> {
                    int len = emailInArena ? ScratchUtf8Arena.unpackLength(emailArenaHandle) : 0;
                    if (len > 0 || slotTapeIdx[4] >= 0) w.writeLengthDelimitedBytes(5, parent.arena.buffer(), ScratchUtf8Arena.unpackOffset(emailArenaHandle), len);
                }
            }
        }
    }

    public static final class RawLineItem {
        private static final int SLOT_ITEM_ID = 0, SLOT_SKU = 1, SLOT_QTY = 2, SLOT_PRICE = 3, SLOT_DESC = 4;
        private static final int BIT_ITEM_ID = 1, BIT_SKU = 2, BIT_QTY = 4, BIT_PRICE = 8, BIT_DESC = 16;

        private final RawOrderEnvelope parent;
        private final ScratchUtf8Arena arena;
        private final WireTape tape = new WireTape(8);
        private final int[] slotTapeIdx = new int[5];
        private final WireTape.ScanVisitor scanVisitor = this::onScanField;
        private final WireTape.DirtyEntryWriter dirtyWriter = (tapeIdx, fn, wt, aux, writer) -> writeField(fn, writer);
        int parentItemIdx;
        private int decodedMask, sameSizeDirtyMask, varSizeDirtyMask, appendedMask;

        public long itemId;
        public int skuOff, skuLen;
        private String skuCache;
        private boolean skuInArena;
        public int quantity;
        public long priceCents;
        public int descOff, descLen;
        private String descCache;
        private boolean descInArena;

        public RawLineItem() {
            this(null, null);
        }

        RawLineItem(RawOrderEnvelope parent, ScratchUtf8Arena arena) {
            this.parent = parent;
            this.arena = arena;
            Arrays.fill(slotTapeIdx, -1);
        }

        void wrap(byte[] buf, int off, int len) {
            tape.reset(buf, off, len);
            resetLocal();
        }

        void initScratch() {
            tape.resetEmpty();
            resetLocal();
        }

        private void resetLocal() {
            Arrays.fill(slotTapeIdx, -1);
            decodedMask = 0;
            sameSizeDirtyMask = 0;
            varSizeDirtyMask = 0;
            appendedMask = 0;
            skuCache = null;
            skuInArena = false;
            descCache = null;
            descInArena = false;
        }

        void decodeAllEager(byte[] b, int off, int len) {
            int cursor = off;
            int limit = off + len;
            while (cursor < limit) {
                int tag = b[cursor++];
                if (tag < 0) {
                    long tc = WireReader.readVarint32WithCursor(b, cursor - 1, limit);
                    tag = (int) tc;
                    cursor = (int) (tc >>> 32);
                }
                switch (tag) {
                    case 8 -> {
                        int next = WireReader.skipVarintSWAR(b, cursor, limit);
                        itemId = WireReader.readVarint64At(b, cursor);
                        cursor = next;
                    }
                    case 18 -> {
                        long lc = WireReader.readVarint32WithCursor(b, cursor, limit);
                        skuLen = (int) lc;
                        skuOff = (int) (lc >>> 32);
                        cursor = skuOff + skuLen;
                    }
                    case 24 -> {
                        int next = WireReader.skipVarintSWAR(b, cursor, limit);
                        quantity = WireReader.readVarint32At(b, cursor);
                        cursor = next;
                    }
                    case 32 -> {
                        int next = WireReader.skipVarintSWAR(b, cursor, limit);
                        priceCents = WireReader.readVarint64At(b, cursor);
                        cursor = next;
                    }
                    case 42 -> {
                        long lc = WireReader.readVarint32WithCursor(b, cursor, limit);
                        descLen = (int) lc;
                        descOff = (int) (lc >>> 32);
                        cursor = descOff + descLen;
                    }
                    default -> {
                        int wt = tag & 7;
                        if (wt == WireFormat.WIRETYPE_VARINT) cursor = WireReader.skipVarintSWAR(b, cursor, limit);
                        else if (wt == WireFormat.WIRETYPE_FIXED64) cursor += 8;
                        else if (wt == WireFormat.WIRETYPE_LENGTH_DELIMITED) {
                            long lc = WireReader.readVarint32WithCursor(b, cursor, limit);
                            cursor = (int) (lc >>> 32) + (int) lc;
                        } else if (wt == WireFormat.WIRETYPE_FIXED32) cursor += 4;
                    }
                }
            }
        }

        boolean isClean() {
            return (sameSizeDirtyMask | varSizeDirtyMask | appendedMask) == 0;
        }

        boolean isSameSizeDirtyOnly() {
            return (varSizeDirtyMask | appendedMask) == 0 && sameSizeDirtyMask != 0;
        }

        private void ensureScanned() {
            if (!tape.scanned) {
                tape.scanned = true;
                if (tape.rawLength > 0) {
                    tape.scanRange(tape.buf, tape.rawOffset, tape.rawOffset + tape.rawLength, scanVisitor);
                }
            }
        }

        private void onScanField(int idx, int fn, int wt) {
            int slot = switch (fn) {
                case 1 -> wt == WireFormat.WIRETYPE_VARINT ? SLOT_ITEM_ID : -1;
                case 2 -> wt == WireFormat.WIRETYPE_LENGTH_DELIMITED ? SLOT_SKU : -1;
                case 3 -> wt == WireFormat.WIRETYPE_VARINT ? SLOT_QTY : -1;
                case 4 -> wt == WireFormat.WIRETYPE_VARINT ? SLOT_PRICE : -1;
                case 5 -> wt == WireFormat.WIRETYPE_LENGTH_DELIMITED ? SLOT_DESC : -1;
                default -> -1;
            };
            if (slot >= 0) {
                int prev = slotTapeIdx[slot];
                if (prev >= 0) tape.setFlags(prev, WireTape.FLAG_SHADOWED);
                slotTapeIdx[slot] = idx;
            }
        }

        public long getItemId() {
            if ((decodedMask & BIT_ITEM_ID) == 0) {
                ensureScanned();
                int idx = slotTapeIdx[SLOT_ITEM_ID];
                itemId = idx >= 0 ? WireReader.readVarint64At(tape.buf, tape.valueOffset(idx)) : 0L;
                decodedMask |= BIT_ITEM_ID;
            }
            return itemId;
        }

        public String getSku() {
            if ((decodedMask & BIT_SKU) == 0) {
                ensureScanned();
                int idx = slotTapeIdx[SLOT_SKU];
                skuCache = idx >= 0 ? WireReader.readStringAt(tape.buf, tape.valueOffset(idx), tape.valueLength(idx)) : "";
                skuInArena = false;
                decodedMask |= BIT_SKU;
            }
            if (skuCache == null && skuInArena) {
                skuCache = WireReader.readStringAt(arena.buffer(), skuOff, skuLen);
            }
            return skuCache;
        }

        public int getQuantity() {
            if ((decodedMask & BIT_QTY) == 0) {
                ensureScanned();
                int idx = slotTapeIdx[SLOT_QTY];
                quantity = idx >= 0 ? WireReader.readVarint32At(tape.buf, tape.valueOffset(idx)) : 0;
                decodedMask |= BIT_QTY;
            }
            return quantity;
        }

        public long getPriceCents() {
            if ((decodedMask & BIT_PRICE) == 0) {
                ensureScanned();
                int idx = slotTapeIdx[SLOT_PRICE];
                priceCents = idx >= 0 ? WireReader.readVarint64At(tape.buf, tape.valueOffset(idx)) : 0L;
                decodedMask |= BIT_PRICE;
            }
            return priceCents;
        }

        public String getDescription() {
            if ((decodedMask & BIT_DESC) == 0) {
                ensureScanned();
                int idx = slotTapeIdx[SLOT_DESC];
                descCache = idx >= 0 ? WireReader.readStringAt(tape.buf, tape.valueOffset(idx), tape.valueLength(idx)) : "";
                descInArena = false;
                decodedMask |= BIT_DESC;
            }
            if (descCache == null && descInArena) {
                descCache = WireReader.readStringAt(arena.buffer(), descOff, descLen);
            }
            return descCache;
        }

        public RawLineItem setItemId(long v) {
            ensureScanned();
            this.itemId = v;
            this.decodedMask |= BIT_ITEM_ID;
            markModified(SLOT_ITEM_ID, BIT_ITEM_ID, WireFormat.WIRETYPE_VARINT, WireWriter.varint64Size(v));
            if (parent != null) parent.onItemMutated(parentItemIdx);
            return this;
        }

        public RawLineItem setSku(String v) {
            ensureScanned();
            this.skuCache = v;
            long h = arena.storeString(v);
            this.skuOff = ScratchUtf8Arena.unpackOffset(h);
            this.skuLen = ScratchUtf8Arena.unpackLength(h);
            this.skuInArena = true;
            this.decodedMask |= BIT_SKU;
            markModified(SLOT_SKU, BIT_SKU, WireFormat.WIRETYPE_LENGTH_DELIMITED, skuLen);
            if (parent != null) parent.onItemMutated(parentItemIdx);
            return this;
        }

        public RawLineItem setQuantity(int v) {
            ensureScanned();
            this.quantity = v;
            this.decodedMask |= BIT_QTY;
            markModified(SLOT_QTY, BIT_QTY, WireFormat.WIRETYPE_VARINT, WireWriter.varint32Size(v));
            if (parent != null) parent.onItemMutated(parentItemIdx);
            return this;
        }

        public RawLineItem setPriceCents(long v) {
            ensureScanned();
            this.priceCents = v;
            this.decodedMask |= BIT_PRICE;
            markModified(SLOT_PRICE, BIT_PRICE, WireFormat.WIRETYPE_VARINT, WireWriter.varint64Size(v));
            if (parent != null) parent.onItemMutated(parentItemIdx);
            return this;
        }

        public RawLineItem setDescription(String v) {
            ensureScanned();
            this.descCache = v;
            long h = arena.storeString(v);
            this.descOff = ScratchUtf8Arena.unpackOffset(h);
            this.descLen = ScratchUtf8Arena.unpackLength(h);
            this.descInArena = true;
            this.decodedMask |= BIT_DESC;
            markModified(SLOT_DESC, BIT_DESC, WireFormat.WIRETYPE_LENGTH_DELIMITED, descLen);
            if (parent != null) parent.onItemMutated(parentItemIdx);
            return this;
        }

        private void markModified(int slot, int bit, int expectedWt, int newValLen) {
            int idx = slotTapeIdx[slot];
            if (idx >= 0) {
                if (tape.wireType(idx) == expectedWt && tape.valueLength(idx) == newValLen) {
                    tape.setFlags(idx, WireTape.FLAG_DIRTY_SAME_SIZE);
                    sameSizeDirtyMask |= bit;
                    varSizeDirtyMask &= ~bit;
                } else {
                    tape.setFlags(idx, WireTape.FLAG_DIRTY_VAR_SIZE);
                    sameSizeDirtyMask &= ~bit;
                    varSizeDirtyMask |= bit;
                }
                return;
            }
            appendedMask |= bit;
            sameSizeDirtyMask &= ~bit;
            varSizeDirtyMask |= bit;
        }

        void applyInPlacePatches(byte[] target, int baseOffset) {
            int mask = sameSizeDirtyMask;
            while (mask != 0) {
                int slot = Integer.numberOfTrailingZeros(mask);
                int idx = slotTapeIdx[slot];
                if (idx >= 0 && tape.flags(idx) == WireTape.FLAG_DIRTY_SAME_SIZE) {
                    int patchPos = tape.valueOffset(idx) - baseOffset;
                    switch (slot) {
                        case SLOT_ITEM_ID -> WireWriter.patchVarint64At(target, patchPos, itemId);
                        case SLOT_SKU -> WireWriter.patchBytesAt(target, patchPos, arena.buffer(), skuOff, skuLen);
                        case SLOT_QTY -> WireWriter.patchVarint32At(target, patchPos, quantity);
                        case SLOT_PRICE -> WireWriter.patchVarint64At(target, patchPos, priceCents);
                        case SLOT_DESC -> WireWriter.patchBytesAt(target, patchPos, arena.buffer(), descOff, descLen);
                    }
                }
                mask &= (mask - 1);
            }
        }

        void writeSplicedTo(WireWriter w) {
            if (isClean()) {
                w.writeRawBytes(tape.buf, tape.rawOffset, tape.rawLength);
                return;
            }
            if (isSameSizeDirtyOnly()) {
                int start = w.position();
                w.writeRawBytes(tape.buf, tape.rawOffset, tape.rawLength);
                applyInPlacePatches(w.buffer(), tape.rawOffset - start);
                return;
            }
            if (tape.tapeCount > 0) {
                tape.spliceTo(w, dirtyWriter);
            }
            int m = appendedMask;
            while (m != 0) {
                int slot = Integer.numberOfTrailingZeros(m);
                writeField(slot + 1, w);
                m &= (m - 1);
            }
        }

        private void writeField(int fn, WireWriter w) {
            switch (fn) {
                case 1 -> { if (itemId != 0L || slotTapeIdx[0] >= 0) { w.writeTag(1, WireFormat.WIRETYPE_VARINT); w.writeVarint64(itemId); } }
                case 2 -> { if ((skuInArena && skuLen > 0) || slotTapeIdx[1] >= 0) w.writeLengthDelimitedBytes(2, arena.buffer(), skuOff, skuLen); }
                case 3 -> { if (quantity != 0 || slotTapeIdx[2] >= 0) { w.writeTag(3, WireFormat.WIRETYPE_VARINT); w.writeVarint32(quantity); } }
                case 4 -> { if (priceCents != 0L || slotTapeIdx[3] >= 0) { w.writeTag(4, WireFormat.WIRETYPE_VARINT); w.writeVarint64(priceCents); } }
                case 5 -> { if ((descInArena && descLen > 0) || slotTapeIdx[4] >= 0) w.writeLengthDelimitedBytes(5, arena.buffer(), descOff, descLen); }
            }
        }
    }
}
