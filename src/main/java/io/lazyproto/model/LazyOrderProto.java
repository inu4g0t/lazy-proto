package io.lazyproto.model;

import com.google.protobuf.ByteString;
import com.google.protobuf.Message;
import com.google.protobuf.UnsafeByteOperations;
import io.lazyproto.model.OrderInterfaces.LineItemOrBuilder;
import io.lazyproto.model.OrderInterfaces.OrderEnvelopeOrBuilder;
import io.lazyproto.model.OrderInterfaces.UserProfileOrBuilder;
import io.lazyproto.runtime.AbstractLazyBuilder;
import io.lazyproto.runtime.AbstractLazyMessage;
import io.lazyproto.runtime.ImmutableLazyList;
import io.lazyproto.runtime.ImmutableWireBase;
import io.lazyproto.runtime.WireMergeEngine;
import io.lazyproto.runtime.WireOverlay;
import io.lazyproto.wire.Utf8Slice;
import io.lazyproto.wire.WireFormat;
import io.lazyproto.wire.WireReader;
import io.lazyproto.wire.WireWriter;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

/**
 * Lazy-Proto generated-style Java classes for {@code OrderEnvelope}, {@code UserProfile}, and {@code LineItem}
 * using **Architecture v3 (Immutable WireBase + External COW Overlay + Merge-on-Read/Serialize)**.
 *
 * <p>{@link LazyOrderEnvelope}, {@link LazyUserProfile}, and {@link LazyLineItem} are strictly immutable
 * {@link Message} snapshots. All mutations occur exclusively through their {@code Builder}s into external
 * Copy-on-Write overlays, while unvisited fields and repeated elements remain untouched in the shared
 * read-only wire base.
 */
public final class LazyOrderProto {
    private LazyOrderProto() {}

    // =========================================================================
    // 1. LazyUserProfile (field #1 userId, #2 city, #3 vipLevel, #4 score, #5 email)
    // =========================================================================

    public static final class UserBase extends ImmutableWireBase {
        public static final UserBase EMPTY = new UserBase(true);

        private static final int SLOT_USER_ID   = 0;
        private static final int SLOT_CITY      = 1;
        private static final int SLOT_VIP_LEVEL = 2;
        private static final int SLOT_SCORE     = 3;
        private static final int SLOT_EMAIL     = 4;

        final int[] slotTapeIdx = new int[] { -1, -1, -1, -1, -1 };
        String cityCache;
        String emailCache;

        public UserBase() {
            super(8);
        }

        private UserBase(boolean emptyFrozen) {
            super(0);
            markScannedEmpty();
            freeze();
        }

        @Override
        protected void resetBaseSlots() {
            Arrays.fill(slotTapeIdx, -1);
            cityCache = null;
            emailCache = null;
        }

        @Override
        protected void onScanField(int fieldNumber, int wireType, int tapeIdx) {
            int slot;
            switch (fieldNumber) {
                case 1:
                    if (wireType != WireFormat.WIRETYPE_VARINT) return;
                    slot = SLOT_USER_ID;
                    break;
                case 2:
                    if (wireType != WireFormat.WIRETYPE_LENGTH_DELIMITED) return;
                    slot = SLOT_CITY;
                    break;
                case 3:
                    if (wireType != WireFormat.WIRETYPE_VARINT) return;
                    slot = SLOT_VIP_LEVEL;
                    break;
                case 4:
                    if (wireType != WireFormat.WIRETYPE_FIXED64) return;
                    slot = SLOT_SCORE;
                    break;
                case 5:
                    if (wireType != WireFormat.WIRETYPE_LENGTH_DELIMITED) return;
                    slot = SLOT_EMAIL;
                    break;
                default:
                    return;
            }
            int prev = slotTapeIdx[slot];
            if (prev >= 0) {
                setTapeKind(prev, KIND_SHADOWED);
                this.hasShadowedEntries = true;
            }
            slotTapeIdx[slot] = tapeIdx;
            setTapeKindAndAux(tapeIdx, KIND_SCALAR_SLOT, slot);
        }
    }

    public static final class UserOverlay extends WireOverlay {
        private static final int SLOT_USER_ID   = UserBase.SLOT_USER_ID;
        private static final int SLOT_CITY      = UserBase.SLOT_CITY;
        private static final int SLOT_VIP_LEVEL = UserBase.SLOT_VIP_LEVEL;
        private static final int SLOT_SCORE     = UserBase.SLOT_SCORE;
        private static final int SLOT_EMAIL     = UserBase.SLOT_EMAIL;

        private static final long BIT_USER_ID   = 1L << SLOT_USER_ID;
        private static final long BIT_CITY      = 1L << SLOT_CITY;
        private static final long BIT_VIP_LEVEL = 1L << SLOT_VIP_LEVEL;
        private static final long BIT_SCORE     = 1L << SLOT_SCORE;
        private static final long BIT_EMAIL     = 1L << SLOT_EMAIL;

        long writtenMask;
        long userId;
        String city;
        byte[] cityUtf8;
        int vipLevel;
        double score;
        String email;
        byte[] emailUtf8;

        public void reset() {
            assertUnfrozen();
            this.writtenMask = 0L;
            this.city = null;
            this.cityUtf8 = null;
            this.email = null;
            this.emailUtf8 = null;
            resetMasksAndCache();
        }

        public UserOverlay copyMutable() {
            UserOverlay c = new UserOverlay();
            c.copyMasksFrom(this);
            c.writtenMask = this.writtenMask;
            c.userId = this.userId;
            c.city = this.city;
            c.cityUtf8 = this.cityUtf8;
            c.vipLevel = this.vipLevel;
            c.score = this.score;
            c.email = this.email;
            c.emailUtf8 = this.emailUtf8;
            return c;
        }

        void markScalarWritten(UserBase base, int slot, long bit, int newEncodedLen) {
            this.writtenMask |= bit;
            base.ensureScanned();
            int idx = base.slotTapeIdx[slot];
            if (idx >= 0) {
                int oldLen = base.tapeValueLength(idx);
                if (oldLen == newEncodedLen) {
                    this.varSizeMask &= ~bit;
                    this.sameSizeMask |= bit;
                } else {
                    this.sameSizeMask &= ~bit;
                    this.varSizeMask |= bit;
                }
            } else {
                this.appendedMask |= bit;
                this.varSizeMask |= bit;
            }
            this.cachedSerializedSize = -1;
        }
    }

    private static final class UserOps implements WireMergeEngine.Adapter<UserBase, UserOverlay> {
        static final UserOps INSTANCE = new UserOps();

        static long getUserId(UserBase base, UserOverlay ov) {
            if (ov != null && (ov.writtenMask & UserOverlay.BIT_USER_ID) != 0L) return ov.userId;
            base.ensureScanned();
            int idx = base.slotTapeIdx[UserBase.SLOT_USER_ID];
            return idx >= 0 ? WireReader.readVarint64At(base.rawBuffer(), base.tapeValueOffset(idx)) : 0L;
        }

        static String getCity(UserBase base, UserOverlay ov) {
            if (ov != null && (ov.writtenMask & UserOverlay.BIT_CITY) != 0L) return ov.city;
            base.ensureScanned();
            String cached = base.cityCache;
            if (cached != null) return cached;
            int idx = base.slotTapeIdx[UserBase.SLOT_CITY];
            String val = idx >= 0
                    ? WireReader.readStringAt(base.rawBuffer(), base.tapeValueOffset(idx), base.tapeValueLength(idx))
                    : "";
            base.cityCache = val;
            return val;
        }

        static Utf8Slice getCitySlice(UserBase base, UserOverlay ov, Utf8Slice slice) {
            if (ov != null && (ov.writtenMask & UserOverlay.BIT_CITY) != 0L) {
                return slice.wrap(ov.cityUtf8, 0, ov.cityUtf8.length);
            }
            base.ensureScanned();
            int idx = base.slotTapeIdx[UserBase.SLOT_CITY];
            return idx >= 0
                    ? slice.wrap(base.rawBuffer(), base.tapeValueOffset(idx), base.tapeValueLength(idx))
                    : slice.wrap(ImmutableWireBase.EMPTY_BYTES, 0, 0);
        }

        static int getVipLevel(UserBase base, UserOverlay ov) {
            if (ov != null && (ov.writtenMask & UserOverlay.BIT_VIP_LEVEL) != 0L) return ov.vipLevel;
            base.ensureScanned();
            int idx = base.slotTapeIdx[UserBase.SLOT_VIP_LEVEL];
            return idx >= 0 ? WireReader.readVarint32At(base.rawBuffer(), base.tapeValueOffset(idx)) : 0;
        }

        static double getScore(UserBase base, UserOverlay ov) {
            if (ov != null && (ov.writtenMask & UserOverlay.BIT_SCORE) != 0L) return ov.score;
            base.ensureScanned();
            int idx = base.slotTapeIdx[UserBase.SLOT_SCORE];
            return idx >= 0 ? WireReader.readDoubleAt(base.rawBuffer(), base.tapeValueOffset(idx)) : 0.0;
        }

        static String getEmail(UserBase base, UserOverlay ov) {
            if (ov != null && (ov.writtenMask & UserOverlay.BIT_EMAIL) != 0L) return ov.email;
            base.ensureScanned();
            String cached = base.emailCache;
            if (cached != null) return cached;
            int idx = base.slotTapeIdx[UserBase.SLOT_EMAIL];
            String val = idx >= 0
                    ? WireReader.readStringAt(base.rawBuffer(), base.tapeValueOffset(idx), base.tapeValueLength(idx))
                    : "";
            base.emailCache = val;
            return val;
        }

        static Utf8Slice getEmailSlice(UserBase base, UserOverlay ov, Utf8Slice slice) {
            if (ov != null && (ov.writtenMask & UserOverlay.BIT_EMAIL) != 0L) {
                return slice.wrap(ov.emailUtf8, 0, ov.emailUtf8.length);
            }
            base.ensureScanned();
            int idx = base.slotTapeIdx[UserBase.SLOT_EMAIL];
            return idx >= 0
                    ? slice.wrap(base.rawBuffer(), base.tapeValueOffset(idx), base.tapeValueLength(idx))
                    : slice.wrap(ImmutableWireBase.EMPTY_BYTES, 0, 0);
        }

        @Override public boolean isOverlayEmpty(UserBase base, UserOverlay ov) { return ov == null || ov.isEmpty(); }
        @Override public boolean isSameSizeDirtyOnly(UserBase base, UserOverlay ov) { return ov.isSameSizeDirtyOnly(base); }
        @Override public boolean hasSplitSubOverride(UserBase base, UserOverlay ov, int slot) { return false; }

        @Override
        public boolean isEntryDirtyOrDeleted(UserBase base, UserOverlay ov, int tapeIdx, int kind, int aux, int fn, int wt) {
            return kind == ImmutableWireBase.KIND_SCALAR_SLOT
                    && ((ov.writtenMask >>> aux) & 1L) != 0L;
        }

        @Override
        public boolean isEntryDeleted(UserBase base, UserOverlay ov, int tapeIdx, int kind, int aux, int fn, int wt) {
            return false;
        }

        @Override
        public void applyInPlacePatches(UserBase base, UserOverlay ov, byte[] target, int baseOffset) {
            long mask = ov.sameSizeMask();
            while (mask != 0L) {
                int slot = Long.numberOfTrailingZeros(mask);
                int idx = base.slotTapeIdx[slot];
                if (idx >= 0) {
                    int patchPos = base.tapeValueOffset(idx) - baseOffset;
                    switch (slot) {
                        case UserBase.SLOT_USER_ID: WireWriter.patchVarint64At(target, patchPos, ov.userId); break;
                        case UserBase.SLOT_CITY: WireWriter.patchBytesAt(target, patchPos, ov.cityUtf8, 0, ov.cityUtf8.length); break;
                        case UserBase.SLOT_VIP_LEVEL: WireWriter.patchVarint32At(target, patchPos, ov.vipLevel); break;
                        case UserBase.SLOT_SCORE: WireWriter.patchFixed64At(target, patchPos, Double.doubleToRawLongBits(ov.score)); break;
                        case UserBase.SLOT_EMAIL: WireWriter.patchBytesAt(target, patchPos, ov.emailUtf8, 0, ov.emailUtf8.length); break;
                    }
                }
                mask &= (mask - 1);
            }
        }

        @Override
        public int computeDirtyEntrySize(UserBase base, UserOverlay ov, int tapeIdx, int kind, int aux, int fn, int wt) {
            return switch (fn) {
                case 1 -> 1 + WireWriter.varint64Size(ov.userId);
                case 2 -> 1 + WireWriter.varint32Size(ov.cityUtf8.length) + ov.cityUtf8.length;
                case 3 -> 1 + WireWriter.varint32Size(ov.vipLevel);
                case 4 -> 1 + 8;
                case 5 -> 1 + WireWriter.varint32Size(ov.emailUtf8.length) + ov.emailUtf8.length;
                default -> throw new IllegalStateException("Unexpected dirty field " + fn);
            };
        }

        @Override
        public void writeDirtyEntry(UserBase base, UserOverlay ov, int tapeIdx, int kind, int aux, int fn, int wt, WireWriter writer) {
            switch (fn) {
                case 1 -> { writer.writeTag(1, WireFormat.WIRETYPE_VARINT); writer.writeVarint64(ov.userId); }
                case 2 -> writer.writeLengthDelimitedBytes(2, ov.cityUtf8, 0, ov.cityUtf8.length);
                case 3 -> { writer.writeTag(3, WireFormat.WIRETYPE_VARINT); writer.writeVarint32(ov.vipLevel); }
                case 4 -> { writer.writeTag(4, WireFormat.WIRETYPE_FIXED64); writer.writeDouble(ov.score); }
                case 5 -> writer.writeLengthDelimitedBytes(5, ov.emailUtf8, 0, ov.emailUtf8.length);
            }
        }

        @Override
        public int computeAppendedSize(UserBase base, UserOverlay ov) {
            long app = ov.appendedMask();
            int size = 0;
            if ((app & UserOverlay.BIT_USER_ID) != 0L && ov.userId != 0L) size += 1 + WireWriter.varint64Size(ov.userId);
            if ((app & UserOverlay.BIT_CITY) != 0L && ov.cityUtf8 != null && ov.cityUtf8.length > 0) {
                size += 1 + WireWriter.varint32Size(ov.cityUtf8.length) + ov.cityUtf8.length;
            }
            if ((app & UserOverlay.BIT_VIP_LEVEL) != 0L && ov.vipLevel != 0) size += 1 + WireWriter.varint32Size(ov.vipLevel);
            if ((app & UserOverlay.BIT_SCORE) != 0L && Double.doubleToRawLongBits(ov.score) != 0L) size += 1 + 8;
            if ((app & UserOverlay.BIT_EMAIL) != 0L && ov.emailUtf8 != null && ov.emailUtf8.length > 0) {
                size += 1 + WireWriter.varint32Size(ov.emailUtf8.length) + ov.emailUtf8.length;
            }
            return size;
        }

        @Override
        public void writeAppended(UserBase base, UserOverlay ov, WireWriter writer) {
            long app = ov.appendedMask();
            if ((app & UserOverlay.BIT_USER_ID) != 0L && ov.userId != 0L) {
                writer.writeTag(1, WireFormat.WIRETYPE_VARINT);
                writer.writeVarint64(ov.userId);
            }
            if ((app & UserOverlay.BIT_CITY) != 0L && ov.cityUtf8 != null && ov.cityUtf8.length > 0) {
                writer.writeLengthDelimitedBytes(2, ov.cityUtf8, 0, ov.cityUtf8.length);
            }
            if ((app & UserOverlay.BIT_VIP_LEVEL) != 0L && ov.vipLevel != 0) {
                writer.writeTag(3, WireFormat.WIRETYPE_VARINT);
                writer.writeVarint32(ov.vipLevel);
            }
            if ((app & UserOverlay.BIT_SCORE) != 0L && Double.doubleToRawLongBits(ov.score) != 0L) {
                writer.writeTag(4, WireFormat.WIRETYPE_FIXED64);
                writer.writeDouble(ov.score);
            }
            if ((app & UserOverlay.BIT_EMAIL) != 0L && ov.emailUtf8 != null && ov.emailUtf8.length > 0) {
                writer.writeLengthDelimitedBytes(5, ov.emailUtf8, 0, ov.emailUtf8.length);
            }
        }
    }

    public static final class LazyUserProfile
            extends AbstractLazyMessage<LazyUserProfile, UserBase, UserOverlay>
            implements UserProfileOrBuilder {

        public static final LazyUserProfile EMPTY = new LazyUserProfile(UserBase.EMPTY, null);

        private final Utf8Slice citySlice_ = new Utf8Slice();
        private final Utf8Slice emailSlice_ = new Utf8Slice();

        LazyUserProfile(UserBase base, UserOverlay overlay) {
            super(base, overlay);
        }

        public static LazyUserProfile getDefaultInstance() { return EMPTY; }

        public static LazyUserProfile parseFrom(byte[] data) {
            if (data == null || data.length == 0) return EMPTY;
            UserBase b = new UserBase();
            b.wrapImmutable(data, 0, data.length);
            return new LazyUserProfile(b, null);
        }

        public static Builder newBuilder() {
            UserBase b = new UserBase();
            b.markScannedEmpty();
            b.freeze();
            return new Builder(b, null);
        }

        private UserBase baseRef() { return base; }
        private UserOverlay overlayRef() { return overlay; }
        @Override protected WireMergeEngine.Adapter<UserBase, UserOverlay> mergeAdapter() { return UserOps.INSTANCE; }
        @Override public long getUserId() { return UserOps.getUserId(base, overlay); }
        @Override public String getCity() { return UserOps.getCity(base, overlay); }
        public Utf8Slice getCitySlice() { return UserOps.getCitySlice(base, overlay, citySlice_); }
        @Override public ByteString getCityBytes() { return getCitySlice().toByteStringZeroCopy(); }
        @Override public int getVipLevel() { return UserOps.getVipLevel(base, overlay); }
        @Override public double getScore() { return UserOps.getScore(base, overlay); }
        @Override public String getEmail() { return UserOps.getEmail(base, overlay); }
        public Utf8Slice getEmailSlice() { return UserOps.getEmailSlice(base, overlay, emailSlice_); }
        @Override public ByteString getEmailBytes() { return getEmailSlice().toByteStringZeroCopy(); }
        public Message getDefaultInstanceForType() { return EMPTY; }
        @Override public Builder newBuilderForType() { return newBuilder(); }
        @Override public Builder toBuilder() { return new Builder(base, overlay != null ? overlay.copyMutable() : null); }

        public static final class Builder
                extends AbstractLazyBuilder<LazyUserProfile, Builder, UserBase, UserOverlay>
                implements UserProfileOrBuilder {

            private final LazyOrderEnvelope.Builder parentBuilder;
            private final Utf8Slice citySlice_ = new Utf8Slice();
            private final Utf8Slice emailSlice_ = new Utf8Slice();

            private Builder(UserBase initialBase, UserOverlay initialOverlay) {
                super(initialBase, initialOverlay);
                this.parentBuilder = null;
            }

            Builder(LazyOrderEnvelope.Builder parentBuilder) {
                super(null, null);
                this.parentBuilder = parentBuilder;
            }

            @Override protected boolean isBoundToParent() { return parentBuilder != null; }
            private UserBase baseRef() { return base; }
        private UserOverlay overlayRef() { return overlay; }
        @Override protected WireMergeEngine.Adapter<UserBase, UserOverlay> mergeAdapter() { return UserOps.INSTANCE; }
            @Override protected UserBase newEmptyBase() { return new UserBase(); }
            @Override protected UserOverlay newEmptyOverlay() { return new UserOverlay(); }
            @Override protected UserOverlay copyMutableOverlay(UserOverlay src) { return src.copyMutable(); }
            @Override protected LazyUserProfile wrapAsMessage(UserBase b, UserOverlay o) {
                return (b == UserBase.EMPTY && (o == null || o.isEmpty())) ? EMPTY : new LazyUserProfile(b, o);
            }
            @Override protected LazyUserProfile emptyDefaultMessage() { return EMPTY; }
            @Override protected void resetOverlayInPlace(UserOverlay ov) { ov.reset(); }

            @Override protected UserBase resolveReadBase() {
                return parentBuilder != null ? parentBuilder.resolveUserReadBase() : this.base;
            }
            @Override protected UserOverlay resolveReadOverlay() {
                return parentBuilder != null ? parentBuilder.resolveUserReadOverlay() : this.overlay;
            }
            @Override protected UserOverlay ensureMutableOverlay() {
                return parentBuilder != null ? parentBuilder.ensureMutableUserOverlay() : super.ensureMutableOverlay();
            }

            private void afterWrite() {
                if (parentBuilder != null) {
                    parentBuilder.syncUserOverlayState();
                }
            }

            @Override protected Builder clearAllFields() {
                if (parentBuilder != null) {
                    parentBuilder.clearUser();
                    return this;
                }
                return super.clearAllFields();
            }

            @Override protected Builder replaceWithBytes(byte[] data, int offset, int length) {
                if (parentBuilder != null) {
                    byte[] copy = Arrays.copyOfRange(data, offset, offset + length);
                    parentBuilder.setUser(LazyUserProfile.parseFrom(copy));
                    return this;
                }
                return super.replaceWithBytes(data, offset, length);
            }

            @Override protected Builder replaceWithByteString(ByteString data) {
                if (parentBuilder != null) {
                    byte[] raw = data.toByteArray();
                    parentBuilder.setUser(LazyUserProfile.parseFrom(raw));
                    return this;
                }
                return super.replaceWithByteString(data);
            }

            @Override public long getUserId() { return UserOps.getUserId(resolveReadBase(), resolveReadOverlay()); }
            public Builder setUserId(long v) {
                UserOverlay ov = ensureMutableOverlay();
                UserBase b = resolveReadBase();
                ov.userId = v;
                ov.markScalarWritten(b, UserBase.SLOT_USER_ID, UserOverlay.BIT_USER_ID, WireWriter.varint64Size(v));
                afterWrite();
                return this;
            }

            @Override public String getCity() { return UserOps.getCity(resolveReadBase(), resolveReadOverlay()); }
            @Override public ByteString getCityBytes() {
                return UserOps.getCitySlice(resolveReadBase(), resolveReadOverlay(), citySlice_).toByteStringZeroCopy();
            }
            public Builder setCity(String v) {
                UserOverlay ov = ensureMutableOverlay();
                UserBase b = resolveReadBase();
                ov.city = v;
                ov.cityUtf8 = v.getBytes(StandardCharsets.UTF_8);
                ov.markScalarWritten(b, UserBase.SLOT_CITY, UserOverlay.BIT_CITY, ov.cityUtf8.length);
                afterWrite();
                return this;
            }

            @Override public int getVipLevel() { return UserOps.getVipLevel(resolveReadBase(), resolveReadOverlay()); }
            public Builder setVipLevel(int v) {
                UserOverlay ov = ensureMutableOverlay();
                UserBase b = resolveReadBase();
                ov.vipLevel = v;
                ov.markScalarWritten(b, UserBase.SLOT_VIP_LEVEL, UserOverlay.BIT_VIP_LEVEL, WireWriter.varint32Size(v));
                afterWrite();
                return this;
            }

            @Override public double getScore() { return UserOps.getScore(resolveReadBase(), resolveReadOverlay()); }
            public Builder setScore(double v) {
                UserOverlay ov = ensureMutableOverlay();
                UserBase b = resolveReadBase();
                ov.score = v;
                ov.markScalarWritten(b, UserBase.SLOT_SCORE, UserOverlay.BIT_SCORE, 8);
                afterWrite();
                return this;
            }

            @Override public String getEmail() { return UserOps.getEmail(resolveReadBase(), resolveReadOverlay()); }
            @Override public ByteString getEmailBytes() {
                return UserOps.getEmailSlice(resolveReadBase(), resolveReadOverlay(), emailSlice_).toByteStringZeroCopy();
            }
            public Builder setEmail(String v) {
                UserOverlay ov = ensureMutableOverlay();
                UserBase b = resolveReadBase();
                ov.email = v;
                ov.emailUtf8 = v.getBytes(StandardCharsets.UTF_8);
                ov.markScalarWritten(b, UserBase.SLOT_EMAIL, UserOverlay.BIT_EMAIL, ov.emailUtf8.length);
                afterWrite();
                return this;
            }

            @Override
            public Builder clone() {
                UserBase b = resolveReadBase();
                UserOverlay o = resolveReadOverlay();
                b.freeze();
                return new Builder(b, o != null ? o.copyMutable() : null);
            }
        }
    }

    // =========================================================================
    // 2. LazyLineItem (field #1 itemId, #2 sku, #3 quantity, #4 priceCents, #5 description)
    // =========================================================================

    public static final class LineItemBase extends ImmutableWireBase {
        public static final LineItemBase EMPTY = new LineItemBase(true);

        private static final int SLOT_ITEM_ID     = 0;
        private static final int SLOT_SKU         = 1;
        private static final int SLOT_QUANTITY    = 2;
        private static final int SLOT_PRICE_CENTS = 3;
        private static final int SLOT_DESCRIPTION = 4;

        final int[] slotTapeIdx = new int[] { -1, -1, -1, -1, -1 };
        String skuCache;
        String descCache;

        public LineItemBase() {
            super(8);
        }

        private LineItemBase(boolean emptyFrozen) {
            super(0);
            markScannedEmpty();
            freeze();
        }

        @Override
        protected void resetBaseSlots() {
            Arrays.fill(slotTapeIdx, -1);
            skuCache = null;
            descCache = null;
        }

        @Override
        protected void onScanField(int fieldNumber, int wireType, int tapeIdx) {
            int slot;
            switch (fieldNumber) {
                case 1:
                    if (wireType != WireFormat.WIRETYPE_VARINT) return;
                    slot = SLOT_ITEM_ID;
                    break;
                case 2:
                    if (wireType != WireFormat.WIRETYPE_LENGTH_DELIMITED) return;
                    slot = SLOT_SKU;
                    break;
                case 3:
                    if (wireType != WireFormat.WIRETYPE_VARINT) return;
                    slot = SLOT_QUANTITY;
                    break;
                case 4:
                    if (wireType != WireFormat.WIRETYPE_VARINT) return;
                    slot = SLOT_PRICE_CENTS;
                    break;
                case 5:
                    if (wireType != WireFormat.WIRETYPE_LENGTH_DELIMITED) return;
                    slot = SLOT_DESCRIPTION;
                    break;
                default:
                    return;
            }
            int prev = slotTapeIdx[slot];
            if (prev >= 0) {
                setTapeKind(prev, KIND_SHADOWED);
                this.hasShadowedEntries = true;
            }
            slotTapeIdx[slot] = tapeIdx;
            setTapeKindAndAux(tapeIdx, KIND_SCALAR_SLOT, slot);
        }
    }

    public static final class LineItemOverlay extends WireOverlay {
        private static final long BIT_ITEM_ID     = 1L << LineItemBase.SLOT_ITEM_ID;
        private static final long BIT_SKU         = 1L << LineItemBase.SLOT_SKU;
        private static final long BIT_QUANTITY    = 1L << LineItemBase.SLOT_QUANTITY;
        private static final long BIT_PRICE_CENTS = 1L << LineItemBase.SLOT_PRICE_CENTS;
        private static final long BIT_DESCRIPTION = 1L << LineItemBase.SLOT_DESCRIPTION;

        long writtenMask;
        long itemId;
        String sku;
        byte[] skuUtf8;
        int quantity;
        long priceCents;
        String description;
        byte[] descriptionUtf8;

        public void reset() {
            assertUnfrozen();
            this.writtenMask = 0L;
            this.sku = null;
            this.skuUtf8 = null;
            this.description = null;
            this.descriptionUtf8 = null;
            resetMasksAndCache();
        }

        public LineItemOverlay copyMutable() {
            LineItemOverlay c = new LineItemOverlay();
            c.copyMasksFrom(this);
            c.writtenMask = this.writtenMask;
            c.itemId = this.itemId;
            c.sku = this.sku;
            c.skuUtf8 = this.skuUtf8;
            c.quantity = this.quantity;
            c.priceCents = this.priceCents;
            c.description = this.description;
            c.descriptionUtf8 = this.descriptionUtf8;
            return c;
        }

        void markScalarWritten(LineItemBase base, int slot, long bit, int newEncodedLen) {
            this.writtenMask |= bit;
            base.ensureScanned();
            int idx = base.slotTapeIdx[slot];
            if (idx >= 0) {
                int oldLen = base.tapeValueLength(idx);
                if (oldLen == newEncodedLen) {
                    this.varSizeMask &= ~bit;
                    this.sameSizeMask |= bit;
                } else {
                    this.sameSizeMask &= ~bit;
                    this.varSizeMask |= bit;
                }
            } else {
                this.appendedMask |= bit;
                this.varSizeMask |= bit;
            }
            this.cachedSerializedSize = -1;
        }
    }

    private static final class LineItemOps implements WireMergeEngine.Adapter<LineItemBase, LineItemOverlay> {
        static final LineItemOps INSTANCE = new LineItemOps();

        static long getItemId(LineItemBase base, LineItemOverlay ov) {
            if (ov != null && (ov.writtenMask & LineItemOverlay.BIT_ITEM_ID) != 0L) return ov.itemId;
            base.ensureScanned();
            int idx = base.slotTapeIdx[LineItemBase.SLOT_ITEM_ID];
            return idx >= 0 ? WireReader.readVarint64At(base.rawBuffer(), base.tapeValueOffset(idx)) : 0L;
        }

        static String getSku(LineItemBase base, LineItemOverlay ov) {
            if (ov != null && (ov.writtenMask & LineItemOverlay.BIT_SKU) != 0L) return ov.sku;
            base.ensureScanned();
            String cached = base.skuCache;
            if (cached != null) return cached;
            int idx = base.slotTapeIdx[LineItemBase.SLOT_SKU];
            String val = idx >= 0
                    ? WireReader.readStringAt(base.rawBuffer(), base.tapeValueOffset(idx), base.tapeValueLength(idx))
                    : "";
            base.skuCache = val;
            return val;
        }

        static Utf8Slice getSkuSlice(LineItemBase base, LineItemOverlay ov, Utf8Slice slice) {
            if (ov != null && (ov.writtenMask & LineItemOverlay.BIT_SKU) != 0L) {
                return slice.wrap(ov.skuUtf8, 0, ov.skuUtf8.length);
            }
            base.ensureScanned();
            int idx = base.slotTapeIdx[LineItemBase.SLOT_SKU];
            return idx >= 0
                    ? slice.wrap(base.rawBuffer(), base.tapeValueOffset(idx), base.tapeValueLength(idx))
                    : slice.wrap(ImmutableWireBase.EMPTY_BYTES, 0, 0);
        }

        static int getQuantity(LineItemBase base, LineItemOverlay ov) {
            if (ov != null && (ov.writtenMask & LineItemOverlay.BIT_QUANTITY) != 0L) return ov.quantity;
            base.ensureScanned();
            int idx = base.slotTapeIdx[LineItemBase.SLOT_QUANTITY];
            return idx >= 0 ? WireReader.readVarint32At(base.rawBuffer(), base.tapeValueOffset(idx)) : 0;
        }

        static long getPriceCents(LineItemBase base, LineItemOverlay ov) {
            if (ov != null && (ov.writtenMask & LineItemOverlay.BIT_PRICE_CENTS) != 0L) return ov.priceCents;
            base.ensureScanned();
            int idx = base.slotTapeIdx[LineItemBase.SLOT_PRICE_CENTS];
            return idx >= 0 ? WireReader.readVarint64At(base.rawBuffer(), base.tapeValueOffset(idx)) : 0L;
        }

        static String getDescription(LineItemBase base, LineItemOverlay ov) {
            if (ov != null && (ov.writtenMask & LineItemOverlay.BIT_DESCRIPTION) != 0L) return ov.description;
            base.ensureScanned();
            String cached = base.descCache;
            if (cached != null) return cached;
            int idx = base.slotTapeIdx[LineItemBase.SLOT_DESCRIPTION];
            String val = idx >= 0
                    ? WireReader.readStringAt(base.rawBuffer(), base.tapeValueOffset(idx), base.tapeValueLength(idx))
                    : "";
            base.descCache = val;
            return val;
        }

        static Utf8Slice getDescriptionSlice(LineItemBase base, LineItemOverlay ov, Utf8Slice slice) {
            if (ov != null && (ov.writtenMask & LineItemOverlay.BIT_DESCRIPTION) != 0L) {
                return slice.wrap(ov.descriptionUtf8, 0, ov.descriptionUtf8.length);
            }
            base.ensureScanned();
            int idx = base.slotTapeIdx[LineItemBase.SLOT_DESCRIPTION];
            return idx >= 0
                    ? slice.wrap(base.rawBuffer(), base.tapeValueOffset(idx), base.tapeValueLength(idx))
                    : slice.wrap(ImmutableWireBase.EMPTY_BYTES, 0, 0);
        }

        @Override public boolean isOverlayEmpty(LineItemBase base, LineItemOverlay ov) { return ov == null || ov.isEmpty(); }
        @Override public boolean isSameSizeDirtyOnly(LineItemBase base, LineItemOverlay ov) { return ov.isSameSizeDirtyOnly(base); }
        @Override public boolean hasSplitSubOverride(LineItemBase base, LineItemOverlay ov, int slot) { return false; }

        @Override
        public boolean isEntryDirtyOrDeleted(LineItemBase base, LineItemOverlay ov, int tapeIdx, int kind, int aux, int fn, int wt) {
            return kind == ImmutableWireBase.KIND_SCALAR_SLOT && ((ov.writtenMask >>> aux) & 1L) != 0L;
        }

        @Override
        public boolean isEntryDeleted(LineItemBase base, LineItemOverlay ov, int tapeIdx, int kind, int aux, int fn, int wt) {
            return false;
        }

        @Override
        public void applyInPlacePatches(LineItemBase base, LineItemOverlay ov, byte[] target, int baseOffset) {
            long mask = ov.sameSizeMask();
            while (mask != 0L) {
                int slot = Long.numberOfTrailingZeros(mask);
                int idx = base.slotTapeIdx[slot];
                if (idx >= 0) {
                    int patchPos = base.tapeValueOffset(idx) - baseOffset;
                    switch (slot) {
                        case LineItemBase.SLOT_ITEM_ID: WireWriter.patchVarint64At(target, patchPos, ov.itemId); break;
                        case LineItemBase.SLOT_SKU: WireWriter.patchBytesAt(target, patchPos, ov.skuUtf8, 0, ov.skuUtf8.length); break;
                        case LineItemBase.SLOT_QUANTITY: WireWriter.patchVarint32At(target, patchPos, ov.quantity); break;
                        case LineItemBase.SLOT_PRICE_CENTS: WireWriter.patchVarint64At(target, patchPos, ov.priceCents); break;
                        case LineItemBase.SLOT_DESCRIPTION: WireWriter.patchBytesAt(target, patchPos, ov.descriptionUtf8, 0, ov.descriptionUtf8.length); break;
                    }
                }
                mask &= (mask - 1);
            }
        }

        @Override
        public int computeDirtyEntrySize(LineItemBase base, LineItemOverlay ov, int tapeIdx, int kind, int aux, int fn, int wt) {
            return switch (fn) {
                case 1 -> 1 + WireWriter.varint64Size(ov.itemId);
                case 2 -> 1 + WireWriter.varint32Size(ov.skuUtf8.length) + ov.skuUtf8.length;
                case 3 -> 1 + WireWriter.varint32Size(ov.quantity);
                case 4 -> 1 + WireWriter.varint64Size(ov.priceCents);
                case 5 -> 1 + WireWriter.varint32Size(ov.descriptionUtf8.length) + ov.descriptionUtf8.length;
                default -> throw new IllegalStateException("Unexpected dirty field " + fn);
            };
        }

        @Override
        public void writeDirtyEntry(LineItemBase base, LineItemOverlay ov, int tapeIdx, int kind, int aux, int fn, int wt, WireWriter writer) {
            switch (fn) {
                case 1 -> { writer.writeTag(1, WireFormat.WIRETYPE_VARINT); writer.writeVarint64(ov.itemId); }
                case 2 -> writer.writeLengthDelimitedBytes(2, ov.skuUtf8, 0, ov.skuUtf8.length);
                case 3 -> { writer.writeTag(3, WireFormat.WIRETYPE_VARINT); writer.writeVarint32(ov.quantity); }
                case 4 -> { writer.writeTag(4, WireFormat.WIRETYPE_VARINT); writer.writeVarint64(ov.priceCents); }
                case 5 -> writer.writeLengthDelimitedBytes(5, ov.descriptionUtf8, 0, ov.descriptionUtf8.length);
            }
        }

        @Override
        public int computeAppendedSize(LineItemBase base, LineItemOverlay ov) {
            long app = ov.appendedMask();
            int size = 0;
            if ((app & LineItemOverlay.BIT_ITEM_ID) != 0L && ov.itemId != 0L) size += 1 + WireWriter.varint64Size(ov.itemId);
            if ((app & LineItemOverlay.BIT_SKU) != 0L && ov.skuUtf8 != null && ov.skuUtf8.length > 0) {
                size += 1 + WireWriter.varint32Size(ov.skuUtf8.length) + ov.skuUtf8.length;
            }
            if ((app & LineItemOverlay.BIT_QUANTITY) != 0L && ov.quantity != 0) size += 1 + WireWriter.varint32Size(ov.quantity);
            if ((app & LineItemOverlay.BIT_PRICE_CENTS) != 0L && ov.priceCents != 0L) size += 1 + WireWriter.varint64Size(ov.priceCents);
            if ((app & LineItemOverlay.BIT_DESCRIPTION) != 0L && ov.descriptionUtf8 != null && ov.descriptionUtf8.length > 0) {
                size += 1 + WireWriter.varint32Size(ov.descriptionUtf8.length) + ov.descriptionUtf8.length;
            }
            return size;
        }

        @Override
        public void writeAppended(LineItemBase base, LineItemOverlay ov, WireWriter writer) {
            long app = ov.appendedMask();
            if ((app & LineItemOverlay.BIT_ITEM_ID) != 0L && ov.itemId != 0L) {
                writer.writeTag(1, WireFormat.WIRETYPE_VARINT);
                writer.writeVarint64(ov.itemId);
            }
            if ((app & LineItemOverlay.BIT_SKU) != 0L && ov.skuUtf8 != null && ov.skuUtf8.length > 0) {
                writer.writeLengthDelimitedBytes(2, ov.skuUtf8, 0, ov.skuUtf8.length);
            }
            if ((app & LineItemOverlay.BIT_QUANTITY) != 0L && ov.quantity != 0) {
                writer.writeTag(3, WireFormat.WIRETYPE_VARINT);
                writer.writeVarint32(ov.quantity);
            }
            if ((app & LineItemOverlay.BIT_PRICE_CENTS) != 0L && ov.priceCents != 0L) {
                writer.writeTag(4, WireFormat.WIRETYPE_VARINT);
                writer.writeVarint64(ov.priceCents);
            }
            if ((app & LineItemOverlay.BIT_DESCRIPTION) != 0L && ov.descriptionUtf8 != null && ov.descriptionUtf8.length > 0) {
                writer.writeLengthDelimitedBytes(5, ov.descriptionUtf8, 0, ov.descriptionUtf8.length);
            }
        }
    }

    public static final class LazyLineItem
            extends AbstractLazyMessage<LazyLineItem, LineItemBase, LineItemOverlay>
            implements LineItemOrBuilder {

        public static final LazyLineItem EMPTY = new LazyLineItem(LineItemBase.EMPTY, null);

        private final Utf8Slice skuSlice_ = new Utf8Slice();
        private final Utf8Slice descriptionSlice_ = new Utf8Slice();

        LazyLineItem(LineItemBase base, LineItemOverlay overlay) {
            super(base, overlay);
        }

        public static LazyLineItem getDefaultInstance() { return EMPTY; }

        public static LazyLineItem parseFrom(byte[] data) {
            if (data == null || data.length == 0) return EMPTY;
            LineItemBase b = new LineItemBase();
            b.wrapImmutable(data, 0, data.length);
            return new LazyLineItem(b, null);
        }

        public static Builder newBuilder() {
            LineItemBase b = new LineItemBase();
            b.markScannedEmpty();
            b.freeze();
            return new Builder(b, null);
        }

        private LineItemBase baseRef() { return base; }
        private LineItemOverlay overlayRef() { return overlay; }
        @Override protected WireMergeEngine.Adapter<LineItemBase, LineItemOverlay> mergeAdapter() { return LineItemOps.INSTANCE; }
        @Override public long getItemId() { return LineItemOps.getItemId(base, overlay); }
        @Override public String getSku() { return LineItemOps.getSku(base, overlay); }
        public Utf8Slice getSkuSlice() { return LineItemOps.getSkuSlice(base, overlay, skuSlice_); }
        @Override public ByteString getSkuBytes() { return getSkuSlice().toByteStringZeroCopy(); }
        @Override public int getQuantity() { return LineItemOps.getQuantity(base, overlay); }
        @Override public long getPriceCents() { return LineItemOps.getPriceCents(base, overlay); }
        @Override public String getDescription() { return LineItemOps.getDescription(base, overlay); }
        public Utf8Slice getDescriptionSlice() { return LineItemOps.getDescriptionSlice(base, overlay, descriptionSlice_); }
        @Override public ByteString getDescriptionBytes() { return getDescriptionSlice().toByteStringZeroCopy(); }
        public Message getDefaultInstanceForType() { return EMPTY; }
        @Override public Builder newBuilderForType() { return newBuilder(); }
        @Override public Builder toBuilder() { return new Builder(base, overlay != null ? overlay.copyMutable() : null); }

        public static final class Builder
                extends AbstractLazyBuilder<LazyLineItem, Builder, LineItemBase, LineItemOverlay>
                implements LineItemOrBuilder {

            private final LazyOrderEnvelope.Builder parentBuilder;
            private final int itemIndex;
            private Utf8Slice skuSlice_;
            private Utf8Slice descriptionSlice_;

            private Builder(LineItemBase initialBase, LineItemOverlay initialOverlay) {
                super(initialBase, initialOverlay);
                this.parentBuilder = null;
                this.itemIndex = -1;
            }

            Builder(LazyOrderEnvelope.Builder parentBuilder, int itemIndex) {
                super(null, null);
                this.parentBuilder = parentBuilder;
                this.itemIndex = itemIndex;
            }

            @Override protected boolean isBoundToParent() { return parentBuilder != null; }
            private LineItemBase baseRef() { return base; }
        private LineItemOverlay overlayRef() { return overlay; }
        @Override protected WireMergeEngine.Adapter<LineItemBase, LineItemOverlay> mergeAdapter() { return LineItemOps.INSTANCE; }
            @Override protected LineItemBase newEmptyBase() { return new LineItemBase(); }
            @Override protected LineItemOverlay newEmptyOverlay() { return new LineItemOverlay(); }
            @Override protected LineItemOverlay copyMutableOverlay(LineItemOverlay src) { return src.copyMutable(); }
            @Override protected LazyLineItem wrapAsMessage(LineItemBase b, LineItemOverlay o) {
                return (b == LineItemBase.EMPTY && (o == null || o.isEmpty())) ? EMPTY : new LazyLineItem(b, o);
            }
            @Override protected LazyLineItem emptyDefaultMessage() { return EMPTY; }
            @Override protected void resetOverlayInPlace(LineItemOverlay ov) { ov.reset(); }

            @Override protected LineItemBase resolveReadBase() {
                return parentBuilder != null ? parentBuilder.resolveItemReadBase(itemIndex) : this.base;
            }
            @Override protected LineItemOverlay resolveReadOverlay() {
                return parentBuilder != null ? parentBuilder.resolveItemReadOverlay(itemIndex) : this.overlay;
            }
            @Override protected LineItemOverlay ensureMutableOverlay() {
                return parentBuilder != null ? parentBuilder.ensureMutableItemOverlay(itemIndex) : super.ensureMutableOverlay();
            }

            private void afterWrite() {
                if (parentBuilder != null) {
                    parentBuilder.syncItemOverlayState(itemIndex);
                }
            }

            @Override protected Builder clearAllFields() {
                if (parentBuilder != null) {
                    parentBuilder.setItems(itemIndex, LazyLineItem.EMPTY);
                    return this;
                }
                return super.clearAllFields();
            }

            @Override protected Builder replaceWithBytes(byte[] data, int offset, int length) {
                if (parentBuilder != null) {
                    byte[] copy = Arrays.copyOfRange(data, offset, offset + length);
                    parentBuilder.setItems(itemIndex, LazyLineItem.parseFrom(copy));
                    return this;
                }
                return super.replaceWithBytes(data, offset, length);
            }

            @Override protected Builder replaceWithByteString(ByteString data) {
                if (parentBuilder != null) {
                    parentBuilder.setItems(itemIndex, LazyLineItem.parseFrom(data.toByteArray()));
                    return this;
                }
                return super.replaceWithByteString(data);
            }

            @Override public long getItemId() { return LineItemOps.getItemId(resolveReadBase(), resolveReadOverlay()); }
            public Builder setItemId(long v) {
                LineItemOverlay ov = ensureMutableOverlay();
                LineItemBase b = resolveReadBase();
                ov.itemId = v;
                ov.markScalarWritten(b, LineItemBase.SLOT_ITEM_ID, LineItemOverlay.BIT_ITEM_ID, WireWriter.varint64Size(v));
                afterWrite();
                return this;
            }

            @Override public String getSku() { return LineItemOps.getSku(resolveReadBase(), resolveReadOverlay()); }
            @Override public ByteString getSkuBytes() {
                if (skuSlice_ == null) skuSlice_ = new Utf8Slice();
                return LineItemOps.getSkuSlice(resolveReadBase(), resolveReadOverlay(), skuSlice_).toByteStringZeroCopy();
            }
            public Builder setSku(String v) {
                LineItemOverlay ov = ensureMutableOverlay();
                LineItemBase b = resolveReadBase();
                ov.sku = v;
                ov.skuUtf8 = v.getBytes(StandardCharsets.UTF_8);
                ov.markScalarWritten(b, LineItemBase.SLOT_SKU, LineItemOverlay.BIT_SKU, ov.skuUtf8.length);
                afterWrite();
                return this;
            }

            @Override public int getQuantity() { return LineItemOps.getQuantity(resolveReadBase(), resolveReadOverlay()); }
            public Builder setQuantity(int v) {
                LineItemOverlay ov = ensureMutableOverlay();
                LineItemBase b = resolveReadBase();
                ov.quantity = v;
                ov.markScalarWritten(b, LineItemBase.SLOT_QUANTITY, LineItemOverlay.BIT_QUANTITY, WireWriter.varint32Size(v));
                afterWrite();
                return this;
            }

            @Override public long getPriceCents() { return LineItemOps.getPriceCents(resolveReadBase(), resolveReadOverlay()); }
            public Builder setPriceCents(long v) {
                LineItemOverlay ov = ensureMutableOverlay();
                LineItemBase b = resolveReadBase();
                ov.priceCents = v;
                ov.markScalarWritten(b, LineItemBase.SLOT_PRICE_CENTS, LineItemOverlay.BIT_PRICE_CENTS, WireWriter.varint64Size(v));
                afterWrite();
                return this;
            }

            @Override public String getDescription() { return LineItemOps.getDescription(resolveReadBase(), resolveReadOverlay()); }
            @Override public ByteString getDescriptionBytes() {
                if (descriptionSlice_ == null) descriptionSlice_ = new Utf8Slice();
                return LineItemOps.getDescriptionSlice(resolveReadBase(), resolveReadOverlay(), descriptionSlice_).toByteStringZeroCopy();
            }
            public Builder setDescription(String v) {
                LineItemOverlay ov = ensureMutableOverlay();
                LineItemBase b = resolveReadBase();
                ov.description = v;
                ov.descriptionUtf8 = v.getBytes(StandardCharsets.UTF_8);
                ov.markScalarWritten(b, LineItemBase.SLOT_DESCRIPTION, LineItemOverlay.BIT_DESCRIPTION, ov.descriptionUtf8.length);
                afterWrite();
                return this;
            }

            @Override
            public Builder clone() {
                LineItemBase b = resolveReadBase();
                LineItemOverlay o = resolveReadOverlay();
                b.freeze();
                return new Builder(b, o != null ? o.copyMutable() : null);
            }
        }
    }

    // =========================================================================
    // 3. LazyOrderEnvelope (Top-level nested message)
    // =========================================================================

    public static final class OrderBase extends ImmutableWireBase {
        public static final OrderBase EMPTY = new OrderBase(true);

        private static final int SLOT_ORDER_ID   = 0;
        private static final int SLOT_BUYER_NAME = 1;
        private static final int SLOT_STATUS     = 2;
        private static final int SLOT_UPDATED_AT = 3;
        private static final int SLOT_USER       = 4;
        private static final int SLOT_ITEMS      = 5;
        private static final int SLOT_PAYLOAD    = 6;
        private static final int SLOT_AUTH_TOKEN = 7;
        private static final int SLOT_TRACE_ID   = 8;
        private static final int SLOT_TOTAL      = 9;

        private static final int[] EMPTY_INTS = new int[0];

        final int[] slotTapeIdx = new int[] { -1, -1, -1, -1, -1, -1, -1, -1, -1, -1 };

        int[] userSegTapeIdx;
        int userSegCount;
        UserBase cachedUserBase;
        boolean userBaseBound;

        int[] itemsTapeIdx = EMPTY_INTS;
        int itemsCount;
        LineItemBase[] cachedItemBases;
        boolean[] itemBaseBound;

        String buyerNameCache;
        String authTokenCache;
        String traceIdCache;

        public OrderBase() {
            super(128);
        }

        private OrderBase(boolean emptyFrozen) {
            super(0);
            markScannedEmpty();
            freeze();
        }

        @Override
        protected void onFreeze() {
            if (cachedUserBase != null) {
                cachedUserBase.freeze();
            }
            if (cachedItemBases != null) {
                for (int i = 0; i < itemsCount && i < cachedItemBases.length; i++) {
                    if (cachedItemBases[i] != null) {
                        cachedItemBases[i].freeze();
                    }
                }
            }
        }

        @Override
        protected void resetBaseSlots() {
            Arrays.fill(slotTapeIdx, -1);
            userSegCount = 0;
            userBaseBound = false;
            if (cachedUserBase != null) {
                if (cachedUserBase.isFrozen()) {
                    cachedUserBase = null;
                } else {
                    cachedUserBase.resetForReuse(EMPTY_BYTES, 0, 0);
                }
            }
            for (int i = 0; i < itemsCount && cachedItemBases != null && i < cachedItemBases.length; i++) {
                LineItemBase ib = cachedItemBases[i];
                if (itemBaseBound != null && i < itemBaseBound.length) {
                    itemBaseBound[i] = false;
                }
                if (ib != null) {
                    if (ib.isFrozen()) {
                        cachedItemBases[i] = null;
                    } else {
                        ib.resetForReuse(EMPTY_BYTES, 0, 0);
                    }
                }
            }
            itemsCount = 0;
            buyerNameCache = null;
            authTokenCache = null;
            traceIdCache = null;
        }

        @Override
        protected void onScanField(int fieldNumber, int wireType, int tapeIdx) {
            int slot;
            switch (fieldNumber) {
                case 1:
                    if (wireType != WireFormat.WIRETYPE_VARINT) return;
                    slot = SLOT_ORDER_ID;
                    break;
                case 2:
                    if (wireType != WireFormat.WIRETYPE_LENGTH_DELIMITED) return;
                    slot = SLOT_BUYER_NAME;
                    break;
                case 3:
                    if (wireType != WireFormat.WIRETYPE_VARINT) return;
                    slot = SLOT_STATUS;
                    break;
                case 4:
                    if (wireType != WireFormat.WIRETYPE_FIXED64) return;
                    slot = SLOT_UPDATED_AT;
                    break;
                case 5: {
                    if (wireType != WireFormat.WIRETYPE_LENGTH_DELIMITED) return;
                    if (userSegTapeIdx == null) {
                        userSegTapeIdx = new int[4];
                    } else if (userSegCount >= userSegTapeIdx.length) {
                        userSegTapeIdx = Arrays.copyOf(userSegTapeIdx, userSegTapeIdx.length << 1);
                    }
                    int prevTail = slotTapeIdx[SLOT_USER];
                    if (prevTail >= 0) {
                        setTapeKindAndAux(prevTail, KIND_SPLIT_SUB_CONT, SLOT_USER);
                    }
                    userSegTapeIdx[userSegCount++] = tapeIdx;
                    slotTapeIdx[SLOT_USER] = tapeIdx;
                    setTapeKindAndAux(tapeIdx, KIND_SPLIT_SUB_TAIL, SLOT_USER);
                    return;
                }
                case 6: {
                    if (wireType != WireFormat.WIRETYPE_LENGTH_DELIMITED) return;
                    int ord = itemsCount;
                    if (itemsTapeIdx.length == 0) {
                        itemsTapeIdx = new int[16];
                    } else if (ord >= itemsTapeIdx.length) {
                        itemsTapeIdx = Arrays.copyOf(itemsTapeIdx, itemsTapeIdx.length << 1);
                    }
                    itemsTapeIdx[ord] = tapeIdx;
                    itemsCount = ord + 1;
                    setTapeKindAndAux(tapeIdx, KIND_REPEATED_ITEM, ord);
                    return;
                }
                case 7:
                    if (wireType != WireFormat.WIRETYPE_LENGTH_DELIMITED) return;
                    slot = SLOT_PAYLOAD;
                    break;
                case 8:
                    if (wireType != WireFormat.WIRETYPE_LENGTH_DELIMITED) return;
                    slot = SLOT_AUTH_TOKEN;
                    break;
                case 9:
                    if (wireType != WireFormat.WIRETYPE_LENGTH_DELIMITED) return;
                    slot = SLOT_TRACE_ID;
                    break;
                case 10:
                    if (wireType != WireFormat.WIRETYPE_VARINT) return;
                    slot = SLOT_TOTAL;
                    break;
                default:
                    return;
            }
            int prev = slotTapeIdx[slot];
            if (prev >= 0) {
                setTapeKind(prev, KIND_SHADOWED);
                this.hasShadowedEntries = true;
            }
            slotTapeIdx[slot] = tapeIdx;
            setTapeKindAndAux(tapeIdx, KIND_SCALAR_SLOT, slot);
        }

        public boolean isUserMultiSegment() {
            ensureScanned();
            return userSegCount > 1;
        }

        public UserBase getOrCreateUserBase() {
            ensureScanned();
            if (userSegCount == 0) {
                return UserBase.EMPTY;
            }
            if (cachedUserBase == null) {
                cachedUserBase = new UserBase();
                userBaseBound = false;
            }
            if (!userBaseBound) {
                if (userSegCount == 1) {
                    int idx = userSegTapeIdx[0];
                    cachedUserBase.resetForReuse(buf, tapeValueOffset(idx), tapeValueLength(idx));
                } else {
                    cachedUserBase.wrapMultiSegments(buf, tape, userSegTapeIdx, userSegCount);
                }
                if (this.frozen) {
                    cachedUserBase.freeze();
                }
                userBaseBound = true;
            }
            return cachedUserBase;
        }

        public LineItemBase getOrCreateItemBase(int index) {
            ensureScanned();
            if (index < 0 || index >= itemsCount) {
                throw new IndexOutOfBoundsException("Index " + index + " out of bounds for length " + itemsCount);
            }
            if (cachedItemBases == null || index >= cachedItemBases.length) {
                int newLen = Math.max(itemsCount, cachedItemBases == null ? 8 : cachedItemBases.length << 1);
                cachedItemBases = cachedItemBases == null
                        ? new LineItemBase[newLen]
                        : Arrays.copyOf(cachedItemBases, newLen);
                itemBaseBound = itemBaseBound == null
                        ? new boolean[newLen]
                        : Arrays.copyOf(itemBaseBound, newLen);
            }
            LineItemBase ib = cachedItemBases[index];
            if (ib == null) {
                ib = new LineItemBase();
                cachedItemBases[index] = ib;
                itemBaseBound[index] = false;
            }
            if (!itemBaseBound[index]) {
                int idx = itemsTapeIdx[index];
                ib.resetForReuse(buf, tapeValueOffset(idx), tapeValueLength(idx));
                if (this.frozen) {
                    ib.freeze();
                }
                itemBaseBound[index] = true;
            }
            return ib;
        }

        public int ensureScannedReturnItemsCount() {
            ensureScanned();
            return itemsCount;
        }

        public int materializedItemBasesCount() {
            if (cachedItemBases == null) return 0;
            int c = 0;
            for (int i = 0; i < itemsCount && i < cachedItemBases.length; i++) {
                if (cachedItemBases[i] != null) c++;
            }
            return c;
        }
    }

    static final class ItemSlot {
        boolean replaced;
        LineItemBase base;       // when replaced == false
        LineItemOverlay patch;   // when replaced == false
        LazyLineItem replacedMsg; // when replaced == true (immutable!)

        static ItemSlot forPatch(LineItemBase base, LineItemOverlay patch) {
            ItemSlot s = new ItemSlot();
            s.replaced = false;
            s.base = base;
            s.patch = patch;
            return s;
        }

        static ItemSlot forReplaced(LazyLineItem msg) {
            ItemSlot s = new ItemSlot();
            s.replaced = true;
            s.replacedMsg = msg;
            return s;
        }

        ItemSlot shallowShare() {
            if (replaced) {
                return forReplaced(replacedMsg);
            }
            if (base != null) base.freeze();
            if (patch != null) patch.freeze();
            return forPatch(base, patch);
        }

        void freeze() {
            if (!replaced) {
                if (base != null) base.freeze();
                if (patch != null) patch.freeze();
            }
        }

        boolean isSameSizeDirtyOnly(OrderBase parentBase, int index) {
            if (replaced || patch == null) return false;
            return index >= 0 && index < parentBase.itemsCount
                    && ImmutableWireBase.isSubSliceOf(base, parentBase, parentBase.itemsTapeIdx[index])
                    && patch.isSameSizeDirtyOnly(base);
        }

        int getSerializedSize() {
            if (replaced) return replacedMsg.getSerializedSize();
            return WireMergeEngine.getSerializedSize(base, patch, LineItemOps.INSTANCE);
        }

        void writeSplicedTo(WireWriter writer) {
            if (replaced) {
                replacedMsg.writeSplicedTo(writer);
            } else {
                WireMergeEngine.writeSplicedTo(base, patch, LineItemOps.INSTANCE, writer);
            }
        }

        LazyLineItem toImmutableMessage() {
            if (replaced) return replacedMsg;
            if (base != null) base.freeze();
            if (patch != null) patch.freeze();
            return new LazyLineItem(base, patch);
        }
    }

    static final class ItemsOverlay {
        boolean cleared;
        boolean frozen;

        // Sparse modifications to active base indices [0 .. baseCount)
        int singleDirtyIdx = -1;
        ItemSlot singleDirtySlot;
        ItemSlot[] denseDirtySlots;
        long[] dirtyBitset;
        int dirtyBaseCount;
        int sameSizeDirtyCount;

        // Newly appended items
        ItemSlot[] appendedSlots;
        int appendedCount;

        void reset() {
            this.cleared = false;
            if (singleDirtySlot != null && !singleDirtySlot.replaced && singleDirtySlot.patch != null
                    && !singleDirtySlot.patch.isFrozen() && singleDirtySlot.base != null && !singleDirtySlot.base.isFrozen()) {
                singleDirtySlot.patch.reset();
            } else {
                singleDirtySlot = null;
            }
            this.singleDirtyIdx = -1;
            if (denseDirtySlots != null) {
                Arrays.fill(denseDirtySlots, null);
            }
            if (dirtyBitset != null) {
                Arrays.fill(dirtyBitset, 0L);
            }
            this.dirtyBaseCount = 0;
            this.sameSizeDirtyCount = 0;
            if (appendedSlots != null) {
                Arrays.fill(appendedSlots, 0, appendedCount, null);
            }
            this.appendedCount = 0;
        }

        void freeze() {
            if (!frozen) {
                this.frozen = true;
                if (singleDirtySlot != null) singleDirtySlot.freeze();
                if (denseDirtySlots != null) {
                    for (ItemSlot s : denseDirtySlots) {
                        if (s != null) s.freeze();
                    }
                }
                for (int i = 0; i < appendedCount; i++) {
                    if (appendedSlots[i] != null) appendedSlots[i].freeze();
                }
            }
        }

        ItemsOverlay copyMutable() {
            ItemsOverlay c = new ItemsOverlay();
            c.cleared = this.cleared;
            c.dirtyBaseCount = this.dirtyBaseCount;
            c.sameSizeDirtyCount = this.sameSizeDirtyCount;
            c.singleDirtyIdx = this.singleDirtyIdx;
            c.singleDirtySlot = this.singleDirtySlot != null ? this.singleDirtySlot.shallowShare() : null;
            if (this.denseDirtySlots != null) {
                c.denseDirtySlots = new ItemSlot[this.denseDirtySlots.length];
                for (int i = 0; i < this.denseDirtySlots.length; i++) {
                    if (this.denseDirtySlots[i] != null) {
                        c.denseDirtySlots[i] = this.denseDirtySlots[i].shallowShare();
                    }
                }
            }
            if (this.dirtyBitset != null) {
                c.dirtyBitset = Arrays.copyOf(this.dirtyBitset, this.dirtyBitset.length);
            }
            c.appendedCount = this.appendedCount;
            if (this.appendedCount > 0) {
                c.appendedSlots = new ItemSlot[this.appendedSlots.length];
                for (int i = 0; i < this.appendedCount; i++) {
                    if (this.appendedSlots[i] != null) {
                        c.appendedSlots[i] = this.appendedSlots[i].shallowShare();
                    }
                }
            }
            return c;
        }

        boolean isBaseIndexModified(int idx) {
            if (dirtyBaseCount == 0) return false;
            if (dirtyBaseCount == 1) return idx == singleDirtyIdx;
            int word = idx >>> 6;
            return dirtyBitset != null && word < dirtyBitset.length && ((dirtyBitset[word] >>> idx) & 1L) != 0L;
        }

        ItemSlot getBaseSlot(int idx) {
            if (dirtyBaseCount == 0) return null;
            if (dirtyBaseCount == 1) return idx == singleDirtyIdx ? singleDirtySlot : null;
            return (denseDirtySlots != null && idx < denseDirtySlots.length) ? denseDirtySlots[idx] : null;
        }

        void putBaseSlot(int idx, OrderBase parentBase, ItemSlot slot) {
            int baseCount = parentBase.itemsCount;
            if (dirtyBaseCount == 0) {
                singleDirtyIdx = idx;
                singleDirtySlot = slot;
                dirtyBaseCount = 1;
                recomputeSameSizeCount(parentBase);
                return;
            }
            if (dirtyBaseCount == 1 && singleDirtyIdx == idx) {
                singleDirtySlot = slot;
                recomputeSameSizeCount(parentBase);
                return;
            }
            if (denseDirtySlots == null || denseDirtySlots.length < baseCount) {
                denseDirtySlots = denseDirtySlots == null
                        ? new ItemSlot[baseCount]
                        : Arrays.copyOf(denseDirtySlots, baseCount);
                int words = (baseCount + 63) >>> 6;
                dirtyBitset = dirtyBitset == null
                        ? new long[words]
                        : Arrays.copyOf(dirtyBitset, words);
            }
            if (dirtyBaseCount == 1 && singleDirtyIdx >= 0) {
                denseDirtySlots[singleDirtyIdx] = singleDirtySlot;
                dirtyBitset[singleDirtyIdx >>> 6] |= (1L << singleDirtyIdx);
            }
            if (denseDirtySlots[idx] == null) {
                dirtyBaseCount++;
            }
            denseDirtySlots[idx] = slot;
            dirtyBitset[idx >>> 6] |= (1L << idx);
            recomputeSameSizeCount(parentBase);
        }

        void recomputeSameSizeCount(OrderBase parentBase) {
            int baseCount = parentBase.itemsCount;
            int ss = 0;
            if (dirtyBaseCount == 1 && singleDirtySlot != null) {
                if (singleDirtySlot.isSameSizeDirtyOnly(parentBase, singleDirtyIdx)) ss = 1;
            } else if (denseDirtySlots != null) {
                for (int i = 0; i < baseCount && i < denseDirtySlots.length; i++) {
                    if (denseDirtySlots[i] != null && denseDirtySlots[i].isSameSizeDirtyOnly(parentBase, i)) {
                        ss++;
                    }
                }
            }
            this.sameSizeDirtyCount = ss;
        }

        void addAppended(LazyLineItem item) {
            if (appendedSlots == null) {
                appendedSlots = new ItemSlot[4];
            } else if (appendedCount >= appendedSlots.length) {
                appendedSlots = Arrays.copyOf(appendedSlots, appendedSlots.length << 1);
            }
            appendedSlots[appendedCount++] = ItemSlot.forReplaced(item);
        }
    }

    public static final class OrderOverlay extends WireOverlay {
        private static final int SLOT_ORDER_ID   = OrderBase.SLOT_ORDER_ID;
        private static final int SLOT_BUYER_NAME = OrderBase.SLOT_BUYER_NAME;
        private static final int SLOT_STATUS     = OrderBase.SLOT_STATUS;
        private static final int SLOT_UPDATED_AT = OrderBase.SLOT_UPDATED_AT;
        private static final int SLOT_USER       = OrderBase.SLOT_USER;
        private static final int SLOT_ITEMS      = OrderBase.SLOT_ITEMS;
        private static final int SLOT_PAYLOAD    = OrderBase.SLOT_PAYLOAD;
        private static final int SLOT_AUTH_TOKEN = OrderBase.SLOT_AUTH_TOKEN;
        private static final int SLOT_TRACE_ID   = OrderBase.SLOT_TRACE_ID;
        private static final int SLOT_TOTAL      = OrderBase.SLOT_TOTAL;

        private static final long BIT_ORDER_ID   = 1L << SLOT_ORDER_ID;
        private static final long BIT_BUYER_NAME = 1L << SLOT_BUYER_NAME;
        private static final long BIT_STATUS     = 1L << SLOT_STATUS;
        private static final long BIT_UPDATED_AT = 1L << SLOT_UPDATED_AT;
        private static final long BIT_USER       = 1L << SLOT_USER;
        private static final long BIT_ITEMS      = 1L << SLOT_ITEMS;
        private static final long BIT_PAYLOAD    = 1L << SLOT_PAYLOAD;
        private static final long BIT_AUTH_TOKEN = 1L << SLOT_AUTH_TOKEN;
        private static final long BIT_TRACE_ID   = 1L << SLOT_TRACE_ID;
        private static final long BIT_TOTAL      = 1L << SLOT_TOTAL;

        private static final byte USER_INHERIT_BASE = 0;
        private static final byte USER_DELETED      = 1;
        private static final byte USER_PATCHED      = 2;
        private static final byte USER_REPLACED     = 3;

        long writtenMask;
        long orderId;
        String buyerName;
        byte[] buyerNameUtf8;
        int status;
        long updatedAtFixed64;
        ByteString payload;
        byte[] payloadBytes;
        String authToken;
        byte[] authTokenUtf8;
        String traceId;
        byte[] traceIdUtf8;
        long totalCents;

        byte userMode = USER_INHERIT_BASE;
        UserBase userPatchBase;
        UserOverlay userPatch;
        LazyUserProfile userReplaced;

        ItemsOverlay itemsOverlay;

        @Override
        protected void onFreezeChildren() {
            if (userPatchBase != null) userPatchBase.freeze();
            if (userPatch != null) userPatch.freeze();
            if (itemsOverlay != null) itemsOverlay.freeze();
        }

        public void reset() {
            assertUnfrozen();
            this.writtenMask = 0L;
            this.buyerName = null;
            this.buyerNameUtf8 = null;
            this.payload = null;
            this.payloadBytes = null;
            this.authToken = null;
            this.authTokenUtf8 = null;
            this.traceId = null;
            this.traceIdUtf8 = null;

            this.userMode = USER_INHERIT_BASE;
            this.userPatchBase = null;
            if (this.userPatch != null) {
                if (this.userPatch.isFrozen()) {
                    this.userPatch = null;
                } else {
                    this.userPatch.reset();
                }
            }
            this.userReplaced = null;

            if (this.itemsOverlay != null) {
                if (this.itemsOverlay.frozen) {
                    this.itemsOverlay = null;
                } else {
                    this.itemsOverlay.reset();
                }
            }
            resetMasksAndCache();
        }

        public OrderOverlay copyMutable() {
            OrderOverlay c = new OrderOverlay();
            c.copyMasksFrom(this);
            c.writtenMask = this.writtenMask;
            c.orderId = this.orderId;
            c.buyerName = this.buyerName;
            c.buyerNameUtf8 = this.buyerNameUtf8;
            c.status = this.status;
            c.updatedAtFixed64 = this.updatedAtFixed64;
            c.payload = this.payload;
            c.payloadBytes = this.payloadBytes;
            c.authToken = this.authToken;
            c.authTokenUtf8 = this.authTokenUtf8;
            c.traceId = this.traceId;
            c.traceIdUtf8 = this.traceIdUtf8;
            c.totalCents = this.totalCents;

            c.userMode = this.userMode;
            if (this.userMode == USER_PATCHED) {
                if (this.userPatchBase != null) this.userPatchBase.freeze();
                if (this.userPatch != null) this.userPatch.freeze();
                c.userPatchBase = this.userPatchBase;
                c.userPatch = this.userPatch;
            } else if (this.userMode == USER_REPLACED) {
                c.userReplaced = this.userReplaced;
            }

            if (this.itemsOverlay != null) {
                this.itemsOverlay.freeze();
                c.itemsOverlay = this.itemsOverlay.copyMutable();
            }
            return c;
        }

        void markScalarWritten(OrderBase base, int slot, long bit, int newEncodedLen) {
            this.writtenMask |= bit;
            this.deletedMask &= ~bit;
            base.ensureScanned();
            int idx = base.slotTapeIdx[slot];
            if (idx >= 0) {
                int oldLen = base.tapeValueLength(idx);
                if (oldLen == newEncodedLen) {
                    this.varSizeMask &= ~bit;
                    this.sameSizeMask |= bit;
                } else {
                    this.sameSizeMask &= ~bit;
                    this.varSizeMask |= bit;
                }
            } else {
                this.appendedMask |= bit;
                this.varSizeMask |= bit;
            }
            this.cachedSerializedSize = -1;
        }
    }

    private static final class OrderOps implements WireMergeEngine.Adapter<OrderBase, OrderOverlay> {
        static final OrderOps INSTANCE = new OrderOps();

        static long getOrderId(OrderBase base, OrderOverlay ov) {
            if (ov != null && (ov.writtenMask & OrderOverlay.BIT_ORDER_ID) != 0L) return ov.orderId;
            base.ensureScanned();
            int idx = base.slotTapeIdx[OrderBase.SLOT_ORDER_ID];
            return idx >= 0 ? WireReader.readVarint64At(base.rawBuffer(), base.tapeValueOffset(idx)) : 0L;
        }

        static String getBuyerName(OrderBase base, OrderOverlay ov) {
            if (ov != null && (ov.writtenMask & OrderOverlay.BIT_BUYER_NAME) != 0L) return ov.buyerName;
            base.ensureScanned();
            String cached = base.buyerNameCache;
            if (cached != null) return cached;
            int idx = base.slotTapeIdx[OrderBase.SLOT_BUYER_NAME];
            String val = idx >= 0
                    ? WireReader.readStringAt(base.rawBuffer(), base.tapeValueOffset(idx), base.tapeValueLength(idx))
                    : "";
            base.buyerNameCache = val;
            return val;
        }

        static Utf8Slice getBuyerNameSlice(OrderBase base, OrderOverlay ov, Utf8Slice slice) {
            if (ov != null && (ov.writtenMask & OrderOverlay.BIT_BUYER_NAME) != 0L) {
                return slice.wrap(ov.buyerNameUtf8, 0, ov.buyerNameUtf8.length);
            }
            base.ensureScanned();
            int idx = base.slotTapeIdx[OrderBase.SLOT_BUYER_NAME];
            return idx >= 0
                    ? slice.wrap(base.rawBuffer(), base.tapeValueOffset(idx), base.tapeValueLength(idx))
                    : slice.wrap(ImmutableWireBase.EMPTY_BYTES, 0, 0);
        }

        static int getStatus(OrderBase base, OrderOverlay ov) {
            if (ov != null && (ov.writtenMask & OrderOverlay.BIT_STATUS) != 0L) return ov.status;
            base.ensureScanned();
            int idx = base.slotTapeIdx[OrderBase.SLOT_STATUS];
            return idx >= 0 ? WireReader.readVarint32At(base.rawBuffer(), base.tapeValueOffset(idx)) : 0;
        }

        static long getUpdatedAtFixed64(OrderBase base, OrderOverlay ov) {
            if (ov != null && (ov.writtenMask & OrderOverlay.BIT_UPDATED_AT) != 0L) return ov.updatedAtFixed64;
            base.ensureScanned();
            int idx = base.slotTapeIdx[OrderBase.SLOT_UPDATED_AT];
            return idx >= 0 ? WireReader.readFixed64At(base.rawBuffer(), base.tapeValueOffset(idx)) : 0L;
        }

        static boolean hasUser(OrderBase base, OrderOverlay ov) {
            if (ov != null) {
                if (ov.userMode == OrderOverlay.USER_DELETED) return false;
                if (ov.userMode == OrderOverlay.USER_PATCHED || ov.userMode == OrderOverlay.USER_REPLACED) return true;
            }
            base.ensureScanned();
            return base.userSegCount > 0;
        }

        static LazyUserProfile getUserImmutable(OrderBase base, OrderOverlay ov) {
            if (ov != null) {
                if (ov.userMode == OrderOverlay.USER_DELETED) return LazyUserProfile.EMPTY;
                if (ov.userMode == OrderOverlay.USER_REPLACED) return ov.userReplaced;
                if (ov.userMode == OrderOverlay.USER_PATCHED) {
                    UserBase ub = ov.userPatchBase != null ? ov.userPatchBase : base.getOrCreateUserBase();
                    ub.freeze();
                    if (ov.userPatch != null) ov.userPatch.freeze();
                    return new LazyUserProfile(ub, ov.userPatch);
                }
            }
            UserBase ub = base.getOrCreateUserBase();
            if (ub == UserBase.EMPTY) return LazyUserProfile.EMPTY;
            ub.freeze();
            return new LazyUserProfile(ub, null);
        }

        static int getItemsCount(OrderBase base, OrderOverlay ov) {
            return getItemsCountFrom(base, ov != null ? ov.itemsOverlay : null);
        }

        static int getItemsCountFrom(OrderBase base, ItemsOverlay io) {
            if (io != null) {
                int baseActive = io.cleared ? 0 : base.ensureScannedReturnItemsCount();
                return baseActive + io.appendedCount;
            }
            base.ensureScanned();
            return base.itemsCount;
        }

        static LazyLineItem getItemImmutable(OrderBase base, OrderOverlay ov, int index) {
            return getItemFrom(base, ov != null ? ov.itemsOverlay : null, index);
        }

        static LazyLineItem getItemFrom(OrderBase base, ItemsOverlay io, int index) {
            int baseActive = (io != null && io.cleared)
                    ? 0
                    : base.ensureScannedReturnItemsCount();
            int appCount = io != null ? io.appendedCount : 0;
            int total = baseActive + appCount;
            if (index < 0 || index >= total) {
                throw new IndexOutOfBoundsException("Index " + index + " out of bounds for length " + total);
            }
            if (index >= baseActive) {
                return io.appendedSlots[index - baseActive].toImmutableMessage();
            }
            if (io != null) {
                ItemSlot slot = io.getBaseSlot(index);
                if (slot != null) {
                    return slot.toImmutableMessage();
                }
            }
            LineItemBase ib = base.getOrCreateItemBase(index);
            ib.freeze();
            return new LazyLineItem(ib, null);
        }

        static ByteString getPayload(OrderBase base, OrderOverlay ov) {
            if (ov != null && (ov.writtenMask & OrderOverlay.BIT_PAYLOAD) != 0L) return ov.payload;
            base.ensureScanned();
            int idx = base.slotTapeIdx[OrderBase.SLOT_PAYLOAD];
            return idx >= 0
                    ? UnsafeByteOperations.unsafeWrap(base.rawBuffer(), base.tapeValueOffset(idx), base.tapeValueLength(idx))
                    : ByteString.EMPTY;
        }

        static String getAuthToken(OrderBase base, OrderOverlay ov) {
            if (ov != null && (ov.deletedMask() & OrderOverlay.BIT_AUTH_TOKEN) != 0L) return "";
            if (ov != null && (ov.writtenMask & OrderOverlay.BIT_AUTH_TOKEN) != 0L) return ov.authToken;
            base.ensureScanned();
            String cached = base.authTokenCache;
            if (cached != null) return cached;
            int idx = base.slotTapeIdx[OrderBase.SLOT_AUTH_TOKEN];
            String val = idx >= 0
                    ? WireReader.readStringAt(base.rawBuffer(), base.tapeValueOffset(idx), base.tapeValueLength(idx))
                    : "";
            base.authTokenCache = val;
            return val;
        }

        static Utf8Slice getAuthTokenSlice(OrderBase base, OrderOverlay ov, Utf8Slice slice) {
            if (ov != null && (ov.deletedMask() & OrderOverlay.BIT_AUTH_TOKEN) != 0L) {
                return slice.wrap(ImmutableWireBase.EMPTY_BYTES, 0, 0);
            }
            if (ov != null && (ov.writtenMask & OrderOverlay.BIT_AUTH_TOKEN) != 0L) {
                return slice.wrap(ov.authTokenUtf8, 0, ov.authTokenUtf8.length);
            }
            base.ensureScanned();
            int idx = base.slotTapeIdx[OrderBase.SLOT_AUTH_TOKEN];
            return idx >= 0
                    ? slice.wrap(base.rawBuffer(), base.tapeValueOffset(idx), base.tapeValueLength(idx))
                    : slice.wrap(ImmutableWireBase.EMPTY_BYTES, 0, 0);
        }

        static String getTraceId(OrderBase base, OrderOverlay ov) {
            if (ov != null && (ov.writtenMask & OrderOverlay.BIT_TRACE_ID) != 0L) return ov.traceId;
            base.ensureScanned();
            String cached = base.traceIdCache;
            if (cached != null) return cached;
            int idx = base.slotTapeIdx[OrderBase.SLOT_TRACE_ID];
            String val = idx >= 0
                    ? WireReader.readStringAt(base.rawBuffer(), base.tapeValueOffset(idx), base.tapeValueLength(idx))
                    : "";
            base.traceIdCache = val;
            return val;
        }

        static Utf8Slice getTraceIdSlice(OrderBase base, OrderOverlay ov, Utf8Slice slice) {
            if (ov != null && (ov.writtenMask & OrderOverlay.BIT_TRACE_ID) != 0L) {
                return slice.wrap(ov.traceIdUtf8, 0, ov.traceIdUtf8.length);
            }
            base.ensureScanned();
            int idx = base.slotTapeIdx[OrderBase.SLOT_TRACE_ID];
            return idx >= 0
                    ? slice.wrap(base.rawBuffer(), base.tapeValueOffset(idx), base.tapeValueLength(idx))
                    : slice.wrap(ImmutableWireBase.EMPTY_BYTES, 0, 0);
        }

        static long getTotalCents(OrderBase base, OrderOverlay ov) {
            if (ov != null && (ov.writtenMask & OrderOverlay.BIT_TOTAL) != 0L) return ov.totalCents;
            base.ensureScanned();
            int idx = base.slotTapeIdx[OrderBase.SLOT_TOTAL];
            return idx >= 0 ? WireReader.readVarint64At(base.rawBuffer(), base.tapeValueOffset(idx)) : 0L;
        }

        @Override
        public boolean isOverlayEmpty(OrderBase base, OrderOverlay ov) {
            return ov == null || ov.isEmpty();
        }

        @Override
        public boolean isSameSizeDirtyOnly(OrderBase base, OrderOverlay ov) {
            return ov.isSameSizeDirtyOnly(base);
        }

        @Override
        public boolean hasSplitSubOverride(OrderBase base, OrderOverlay ov, int slot) {
            return slot == OrderBase.SLOT_USER && ov.userMode != OrderOverlay.USER_INHERIT_BASE;
        }

        @Override
        public boolean isEntryDirtyOrDeleted(OrderBase base, OrderOverlay ov, int tapeIdx, int kind, int aux, int fn, int wt) {
            if (kind == ImmutableWireBase.KIND_SCALAR_SLOT) {
                return (((ov.writtenMask | ov.deletedMask()) >>> aux) & 1L) != 0L;
            }
            if (kind == ImmutableWireBase.KIND_REPEATED_ITEM) {
                ItemsOverlay io = ov.itemsOverlay;
                return io != null && (io.cleared || io.isBaseIndexModified(aux));
            }
            return false;
        }

        @Override
        public boolean isEntryDeleted(OrderBase base, OrderOverlay ov, int tapeIdx, int kind, int aux, int fn, int wt) {
            if (kind == ImmutableWireBase.KIND_SPLIT_SUB_TAIL && aux == OrderBase.SLOT_USER) {
                return ov.userMode == OrderOverlay.USER_DELETED;
            }
            if (kind == ImmutableWireBase.KIND_SCALAR_SLOT) {
                return ((ov.deletedMask() >>> aux) & 1L) != 0L;
            }
            if (kind == ImmutableWireBase.KIND_REPEATED_ITEM) {
                return ov.itemsOverlay != null && ov.itemsOverlay.cleared;
            }
            return false;
        }

        @Override
        public void applyInPlacePatches(OrderBase base, OrderOverlay ov, byte[] target, int baseOffset) {
            long mask = ov.sameSizeMask();
            while (mask != 0L) {
                int slot = Long.numberOfTrailingZeros(mask);
                if (slot == OrderBase.SLOT_ITEMS) {
                    ItemsOverlay io = ov.itemsOverlay;
                    if (io.dirtyBaseCount == 1 && io.singleDirtySlot != null
                            && io.singleDirtySlot.isSameSizeDirtyOnly(base, io.singleDirtyIdx)) {
                        LineItemOps.INSTANCE.applyInPlacePatches(io.singleDirtySlot.base, io.singleDirtySlot.patch, target, baseOffset);
                    } else if (io.denseDirtySlots != null) {
                        for (int i = 0; i < base.itemsCount && i < io.denseDirtySlots.length; i++) {
                            ItemSlot s = io.denseDirtySlots[i];
                            if (s != null && s.isSameSizeDirtyOnly(base, i)) {
                                LineItemOps.INSTANCE.applyInPlacePatches(s.base, s.patch, target, baseOffset);
                            }
                        }
                    }
                } else if (slot == OrderBase.SLOT_USER) {
                    UserOps.INSTANCE.applyInPlacePatches(ov.userPatchBase, ov.userPatch, target, baseOffset);
                } else {
                    int idx = base.slotTapeIdx[slot];
                    if (idx >= 0) {
                        int patchPos = base.tapeValueOffset(idx) - baseOffset;
                        switch (slot) {
                            case OrderBase.SLOT_ORDER_ID: WireWriter.patchVarint64At(target, patchPos, ov.orderId); break;
                            case OrderBase.SLOT_BUYER_NAME: WireWriter.patchBytesAt(target, patchPos, ov.buyerNameUtf8, 0, ov.buyerNameUtf8.length); break;
                            case OrderBase.SLOT_STATUS: WireWriter.patchVarint32At(target, patchPos, ov.status); break;
                            case OrderBase.SLOT_UPDATED_AT: WireWriter.patchFixed64At(target, patchPos, ov.updatedAtFixed64); break;
                            case OrderBase.SLOT_PAYLOAD: WireWriter.patchBytesAt(target, patchPos, ov.payloadBytes, 0, ov.payloadBytes.length); break;
                            case OrderBase.SLOT_AUTH_TOKEN: WireWriter.patchBytesAt(target, patchPos, ov.authTokenUtf8, 0, ov.authTokenUtf8.length); break;
                            case OrderBase.SLOT_TRACE_ID: WireWriter.patchBytesAt(target, patchPos, ov.traceIdUtf8, 0, ov.traceIdUtf8.length); break;
                            case OrderBase.SLOT_TOTAL: WireWriter.patchVarint64At(target, patchPos, ov.totalCents); break;
                        }
                    }
                }
                mask &= (mask - 1);
            }
        }

        private static int computeUserSize(OrderOverlay ov) {
            if (ov.userMode == OrderOverlay.USER_REPLACED) {
                return ov.userReplaced.getSerializedSize();
            }
            return WireMergeEngine.getSerializedSize(ov.userPatchBase, ov.userPatch, UserOps.INSTANCE);
        }

        private static void writeUserBytes(OrderOverlay ov, WireWriter writer) {
            int subSize = computeUserSize(ov);
            writer.writeTag(5, WireFormat.WIRETYPE_LENGTH_DELIMITED);
            writer.writeVarint32(subSize);
            if (ov.userMode == OrderOverlay.USER_REPLACED) {
                ov.userReplaced.writeSplicedTo(writer);
            } else {
                WireMergeEngine.writeSplicedTo(ov.userPatchBase, ov.userPatch, UserOps.INSTANCE, writer);
            }
        }

        @Override
        public int computeDirtyEntrySize(OrderBase base, OrderOverlay ov, int tapeIdx, int kind, int aux, int fn, int wt) {
            return switch (fn) {
                case 1 -> 1 + WireWriter.varint64Size(ov.orderId);
                case 2 -> 1 + WireWriter.varint32Size(ov.buyerNameUtf8.length) + ov.buyerNameUtf8.length;
                case 3 -> 1 + WireWriter.varint32Size(ov.status);
                case 4 -> 1 + 8;
                case 5 -> {
                    int subSize = computeUserSize(ov);
                    yield 1 + WireWriter.varint32Size(subSize) + subSize;
                }
                case 6 -> {
                    int subSize = ov.itemsOverlay.getBaseSlot(aux).getSerializedSize();
                    yield 1 + WireWriter.varint32Size(subSize) + subSize;
                }
                case 7 -> 1 + WireWriter.varint32Size(ov.payloadBytes.length) + ov.payloadBytes.length;
                case 8 -> 1 + WireWriter.varint32Size(ov.authTokenUtf8.length) + ov.authTokenUtf8.length;
                case 9 -> 1 + WireWriter.varint32Size(ov.traceIdUtf8.length) + ov.traceIdUtf8.length;
                case 10 -> 1 + WireWriter.varint64Size(ov.totalCents);
                default -> throw new IllegalStateException("Unexpected dirty field " + fn);
            };
        }

        @Override
        public void writeDirtyEntry(OrderBase base, OrderOverlay ov, int tapeIdx, int kind, int aux, int fn, int wt, WireWriter writer) {
            switch (fn) {
                case 1 -> { writer.writeTag(1, WireFormat.WIRETYPE_VARINT); writer.writeVarint64(ov.orderId); }
                case 2 -> writer.writeLengthDelimitedBytes(2, ov.buyerNameUtf8, 0, ov.buyerNameUtf8.length);
                case 3 -> { writer.writeTag(3, WireFormat.WIRETYPE_VARINT); writer.writeVarint32(ov.status); }
                case 4 -> { writer.writeTag(4, WireFormat.WIRETYPE_FIXED64); writer.writeFixed64(ov.updatedAtFixed64); }
                case 5 -> writeUserBytes(ov, writer);
                case 6 -> {
                    ItemSlot slot = ov.itemsOverlay.getBaseSlot(aux);
                    int subSize = slot.getSerializedSize();
                    writer.writeTag(6, WireFormat.WIRETYPE_LENGTH_DELIMITED);
                    writer.writeVarint32(subSize);
                    slot.writeSplicedTo(writer);
                }
                case 7 -> writer.writeLengthDelimitedBytes(7, ov.payloadBytes, 0, ov.payloadBytes.length);
                case 8 -> writer.writeLengthDelimitedBytes(8, ov.authTokenUtf8, 0, ov.authTokenUtf8.length);
                case 9 -> writer.writeLengthDelimitedBytes(9, ov.traceIdUtf8, 0, ov.traceIdUtf8.length);
                case 10 -> { writer.writeTag(10, WireFormat.WIRETYPE_VARINT); writer.writeVarint64(ov.totalCents); }
            }
        }

        @Override
        public int computeAppendedSize(OrderBase base, OrderOverlay ov) {
            long app = ov.appendedMask();
            int size = 0;
            if ((app & OrderOverlay.BIT_ORDER_ID) != 0L && ov.orderId != 0L) size += 1 + WireWriter.varint64Size(ov.orderId);
            if ((app & OrderOverlay.BIT_BUYER_NAME) != 0L && ov.buyerNameUtf8 != null && ov.buyerNameUtf8.length > 0) {
                size += 1 + WireWriter.varint32Size(ov.buyerNameUtf8.length) + ov.buyerNameUtf8.length;
            }
            if ((app & OrderOverlay.BIT_STATUS) != 0L && ov.status != 0) size += 1 + WireWriter.varint32Size(ov.status);
            if ((app & OrderOverlay.BIT_UPDATED_AT) != 0L && ov.updatedAtFixed64 != 0L) size += 1 + 8;
            if ((app & OrderOverlay.BIT_USER) != 0L
                    && (ov.userMode == OrderOverlay.USER_PATCHED || ov.userMode == OrderOverlay.USER_REPLACED)) {
                int subSize = computeUserSize(ov);
                size += 1 + WireWriter.varint32Size(subSize) + subSize;
            }
            if ((app & OrderOverlay.BIT_ITEMS) != 0L && ov.itemsOverlay != null) {
                for (int i = 0; i < ov.itemsOverlay.appendedCount; i++) {
                    int subSize = ov.itemsOverlay.appendedSlots[i].getSerializedSize();
                    size += 1 + WireWriter.varint32Size(subSize) + subSize;
                }
            }
            if ((app & OrderOverlay.BIT_PAYLOAD) != 0L && ov.payloadBytes != null && ov.payloadBytes.length > 0) {
                size += 1 + WireWriter.varint32Size(ov.payloadBytes.length) + ov.payloadBytes.length;
            }
            if ((app & OrderOverlay.BIT_AUTH_TOKEN) != 0L && ov.authTokenUtf8 != null && ov.authTokenUtf8.length > 0) {
                size += 1 + WireWriter.varint32Size(ov.authTokenUtf8.length) + ov.authTokenUtf8.length;
            }
            if ((app & OrderOverlay.BIT_TRACE_ID) != 0L && ov.traceIdUtf8 != null && ov.traceIdUtf8.length > 0) {
                size += 1 + WireWriter.varint32Size(ov.traceIdUtf8.length) + ov.traceIdUtf8.length;
            }
            if ((app & OrderOverlay.BIT_TOTAL) != 0L && ov.totalCents != 0L) size += 1 + WireWriter.varint64Size(ov.totalCents);
            return size;
        }

        @Override
        public void writeAppended(OrderBase base, OrderOverlay ov, WireWriter writer) {
            long app = ov.appendedMask();
            if ((app & OrderOverlay.BIT_ORDER_ID) != 0L && ov.orderId != 0L) {
                writer.writeTag(1, WireFormat.WIRETYPE_VARINT);
                writer.writeVarint64(ov.orderId);
            }
            if ((app & OrderOverlay.BIT_BUYER_NAME) != 0L && ov.buyerNameUtf8 != null && ov.buyerNameUtf8.length > 0) {
                writer.writeLengthDelimitedBytes(2, ov.buyerNameUtf8, 0, ov.buyerNameUtf8.length);
            }
            if ((app & OrderOverlay.BIT_STATUS) != 0L && ov.status != 0) {
                writer.writeTag(3, WireFormat.WIRETYPE_VARINT);
                writer.writeVarint32(ov.status);
            }
            if ((app & OrderOverlay.BIT_UPDATED_AT) != 0L && ov.updatedAtFixed64 != 0L) {
                writer.writeTag(4, WireFormat.WIRETYPE_FIXED64);
                writer.writeFixed64(ov.updatedAtFixed64);
            }
            if ((app & OrderOverlay.BIT_USER) != 0L
                    && (ov.userMode == OrderOverlay.USER_PATCHED || ov.userMode == OrderOverlay.USER_REPLACED)) {
                writeUserBytes(ov, writer);
            }
            if ((app & OrderOverlay.BIT_ITEMS) != 0L && ov.itemsOverlay != null) {
                for (int i = 0; i < ov.itemsOverlay.appendedCount; i++) {
                    ItemSlot slot = ov.itemsOverlay.appendedSlots[i];
                    int subSize = slot.getSerializedSize();
                    writer.writeTag(6, WireFormat.WIRETYPE_LENGTH_DELIMITED);
                    writer.writeVarint32(subSize);
                    slot.writeSplicedTo(writer);
                }
            }
            if ((app & OrderOverlay.BIT_PAYLOAD) != 0L && ov.payloadBytes != null && ov.payloadBytes.length > 0) {
                writer.writeLengthDelimitedBytes(7, ov.payloadBytes, 0, ov.payloadBytes.length);
            }
            if ((app & OrderOverlay.BIT_AUTH_TOKEN) != 0L && ov.authTokenUtf8 != null && ov.authTokenUtf8.length > 0) {
                writer.writeLengthDelimitedBytes(8, ov.authTokenUtf8, 0, ov.authTokenUtf8.length);
            }
            if ((app & OrderOverlay.BIT_TRACE_ID) != 0L && ov.traceIdUtf8 != null && ov.traceIdUtf8.length > 0) {
                writer.writeLengthDelimitedBytes(9, ov.traceIdUtf8, 0, ov.traceIdUtf8.length);
            }
            if ((app & OrderOverlay.BIT_TOTAL) != 0L && ov.totalCents != 0L) {
                writer.writeTag(10, WireFormat.WIRETYPE_VARINT);
                writer.writeVarint64(ov.totalCents);
            }
        }
    }

    // Small helper on OrderBase to scan & return itemsCount in one expression
    static {
        // Ensure constants align cleanly
    }

    public static final class LazyOrderEnvelope
            extends AbstractLazyMessage<LazyOrderEnvelope, OrderBase, OrderOverlay>
            implements OrderEnvelopeOrBuilder {

        public static final LazyOrderEnvelope EMPTY = new LazyOrderEnvelope(OrderBase.EMPTY, null);

        private final Utf8Slice buyerNameSlice_ = new Utf8Slice();
        private final Utf8Slice authTokenSlice_ = new Utf8Slice();
        private final Utf8Slice traceIdSlice_ = new Utf8Slice();

        LazyOrderEnvelope(OrderBase base, OrderOverlay overlay) {
            super(base, overlay);
        }

        public static LazyOrderEnvelope getDefaultInstance() { return EMPTY; }

        public static LazyOrderEnvelope parseFrom(byte[] data) {
            if (data == null || data.length == 0) return EMPTY;
            OrderBase b = new OrderBase();
            b.wrapImmutable(data, 0, data.length);
            return new LazyOrderEnvelope(b, null);
        }

        public static LazyOrderEnvelope parseFrom(ByteString data) {
            if (data == null || data.isEmpty()) return EMPTY;
            OrderBase b = new OrderBase();
            b.resetForReuse(data);
            b.freeze();
            return new LazyOrderEnvelope(b, null);
        }

        public static Builder newBuilder() {
            OrderBase b = new OrderBase();
            b.markScannedEmpty();
            b.freeze();
            return new Builder(b, null);
        }

        @Override protected WireMergeEngine.Adapter<OrderBase, OrderOverlay> mergeAdapter() { return OrderOps.INSTANCE; }

        public int loadedItemsCount() {
            return base.materializedItemBasesCount();
        }

        @Override public long getOrderId() { return OrderOps.getOrderId(base, overlay); }
        @Override public String getBuyerName() { return OrderOps.getBuyerName(base, overlay); }
        public Utf8Slice getBuyerNameSlice() { return OrderOps.getBuyerNameSlice(base, overlay, buyerNameSlice_); }
        @Override public ByteString getBuyerNameBytes() { return getBuyerNameSlice().toByteStringZeroCopy(); }
        @Override public int getStatus() { return OrderOps.getStatus(base, overlay); }
        @Override public long getUpdatedAtFixed64() { return OrderOps.getUpdatedAtFixed64(base, overlay); }
        @Override public boolean hasUser() { return OrderOps.hasUser(base, overlay); }
        public LazyUserProfile getUser() { return OrderOps.getUserImmutable(base, overlay); }
        @Override public UserProfileOrBuilder getUserOrBuilder() { return getUser(); }

        public List<LazyLineItem> getItemsList() {
            int sz = getItemsCount();
            return new ImmutableLazyList<>(sz, this::getItems);
        }

        @Override public List<? extends LineItemOrBuilder> getItemsOrBuilderList() { return getItemsList(); }
        @Override public int getItemsCount() { return OrderOps.getItemsCount(base, overlay); }
        public LazyLineItem getItems(int index) { return OrderOps.getItemImmutable(base, overlay, index); }
        @Override public LineItemOrBuilder getItemsOrBuilder(int index) { return getItems(index); }

        @Override public ByteString getPayload() { return OrderOps.getPayload(base, overlay); }
        @Override public String getAuthToken() { return OrderOps.getAuthToken(base, overlay); }
        public Utf8Slice getAuthTokenSlice() { return OrderOps.getAuthTokenSlice(base, overlay, authTokenSlice_); }
        @Override public ByteString getAuthTokenBytes() { return getAuthTokenSlice().toByteStringZeroCopy(); }
        @Override public String getTraceId() { return OrderOps.getTraceId(base, overlay); }
        public Utf8Slice getTraceIdSlice() { return OrderOps.getTraceIdSlice(base, overlay, traceIdSlice_); }
        @Override public ByteString getTraceIdBytes() { return getTraceIdSlice().toByteStringZeroCopy(); }
        @Override public long getTotalCents() { return OrderOps.getTotalCents(base, overlay); }

        public Message getDefaultInstanceForType() { return EMPTY; }
        @Override public Builder newBuilderForType() { return newBuilder(); }
        @Override public Builder toBuilder() { return new Builder(base, overlay != null ? overlay.copyMutable() : null); }

        public static final class Builder
                extends AbstractLazyBuilder<LazyOrderEnvelope, Builder, OrderBase, OrderOverlay>
                implements OrderEnvelopeOrBuilder {

            private final LazyUserProfile.Builder cachedUserBuilder = new LazyUserProfile.Builder(this);
            private final Utf8Slice buyerNameSlice_ = new Utf8Slice();
            private final Utf8Slice authTokenSlice_ = new Utf8Slice();
            private final Utf8Slice traceIdSlice_ = new Utf8Slice();

            private Builder(OrderBase initialBase, OrderOverlay initialOverlay) {
                super(initialBase, initialOverlay);
            }

            @Override protected WireMergeEngine.Adapter<OrderBase, OrderOverlay> mergeAdapter() { return OrderOps.INSTANCE; }
            @Override protected OrderBase newEmptyBase() { return new OrderBase(); }
            @Override protected OrderOverlay newEmptyOverlay() { return new OrderOverlay(); }
            @Override protected OrderOverlay copyMutableOverlay(OrderOverlay src) { return src.copyMutable(); }
            @Override protected LazyOrderEnvelope wrapAsMessage(OrderBase b, OrderOverlay o) {
                return (b == OrderBase.EMPTY && (o == null || o.isEmpty())) ? EMPTY : new LazyOrderEnvelope(b, o);
            }
            @Override protected LazyOrderEnvelope emptyDefaultMessage() { return EMPTY; }
            @Override protected void resetOverlayInPlace(OrderOverlay ov) { ov.reset(); }

            // -------------------- Sub-Builder Coordination Hooks --------------------

            UserBase resolveUserReadBase() {
                if (overlay != null) {
                    if (overlay.userMode == OrderOverlay.USER_DELETED) return UserBase.EMPTY;
                    if (overlay.userMode == OrderOverlay.USER_REPLACED) return overlay.userReplaced.baseRef();
                    if (overlay.userMode == OrderOverlay.USER_PATCHED && overlay.userPatchBase != null) {
                        return overlay.userPatchBase;
                    }
                }
                return base.getOrCreateUserBase();
            }

            UserOverlay resolveUserReadOverlay() {
                if (overlay != null) {
                    if (overlay.userMode == OrderOverlay.USER_DELETED) return null;
                    if (overlay.userMode == OrderOverlay.USER_REPLACED) return overlay.userReplaced.overlayRef();
                    if (overlay.userMode == OrderOverlay.USER_PATCHED) return overlay.userPatch;
                }
                return null;
            }

            UserOverlay ensureMutableUserOverlay() {
                OrderOverlay ov = ensureMutableOverlay();
                if (ov.userMode == OrderOverlay.USER_REPLACED) {
                    LazyUserProfile prev = ov.userReplaced;
                    ov.userPatchBase = prev.baseRef();
                    ov.userPatchBase.freeze();
                    ov.userPatch = prev.overlayRef() != null ? prev.overlayRef().copyMutable() : new UserOverlay();
                    ov.userReplaced = null;
                    ov.userMode = OrderOverlay.USER_PATCHED;
                } else {
                    if (ov.userMode == OrderOverlay.USER_DELETED) {
                        ov.userPatchBase = UserBase.EMPTY;
                        ov.userPatch = ov.userPatch != null && !ov.userPatch.isFrozen() ? ov.userPatch : new UserOverlay();
                        ov.userPatch.reset();
                    } else {
                        if (ov.userPatchBase == null) {
                            ov.userPatchBase = base.getOrCreateUserBase();
                        }
                        if (ov.userPatch == null) {
                            ov.userPatch = new UserOverlay();
                        } else if (ov.userPatch.isFrozen()) {
                            ov.userPatch = ov.userPatch.copyMutable();
                        }
                    }
                    ov.userMode = OrderOverlay.USER_PATCHED;
                }
                ov.userPatch.invalidateSizeCache();
                return ov.userPatch;
            }

            void syncUserOverlayState() {
                OrderOverlay ov = this.overlay;
                if (ov == null) return;
                ov.invalidateSizeCache();
                ov.deletedMask &= ~OrderOverlay.BIT_USER;
                base.ensureScanned();
                boolean presentInBase = base.userSegCount > 0;
                if (ov.userMode == OrderOverlay.USER_PATCHED) {
                    ov.writtenMask |= OrderOverlay.BIT_USER;
                    if (presentInBase) {
                        if (!base.isUserMultiSegment()
                                && ImmutableWireBase.isSubSliceOf(ov.userPatchBase, base, base.slotTapeIdx[OrderBase.SLOT_USER])
                                && ov.userPatch != null
                                && ov.userPatch.isSameSizeDirtyOnly(ov.userPatchBase)) {
                            ov.sameSizeMask |= OrderOverlay.BIT_USER;
                            ov.varSizeMask &= ~OrderOverlay.BIT_USER;
                        } else {
                            ov.sameSizeMask &= ~OrderOverlay.BIT_USER;
                            ov.varSizeMask |= OrderOverlay.BIT_USER;
                        }
                        ov.appendedMask &= ~OrderOverlay.BIT_USER;
                    } else {
                        ov.sameSizeMask &= ~OrderOverlay.BIT_USER;
                        ov.varSizeMask |= OrderOverlay.BIT_USER;
                        ov.appendedMask |= OrderOverlay.BIT_USER;
                    }
                }
            }

            private ItemsOverlay ensureMutableItemsOverlay() {
                OrderOverlay ov = ensureMutableOverlay();
                if (ov.itemsOverlay == null) {
                    ov.itemsOverlay = new ItemsOverlay();
                } else if (ov.itemsOverlay.frozen) {
                    ov.itemsOverlay = ov.itemsOverlay.copyMutable();
                }
                return ov.itemsOverlay;
            }

            LineItemBase resolveItemReadBase(int index) {
                int baseActive = (overlay != null && overlay.itemsOverlay != null && overlay.itemsOverlay.cleared)
                        ? 0 : base.ensureScannedReturnItemsCount();
                int appCount = (overlay != null && overlay.itemsOverlay != null) ? overlay.itemsOverlay.appendedCount : 0;
                int total = baseActive + appCount;
                if (index < 0 || index >= total) {
                    throw new IndexOutOfBoundsException("Index " + index + " out of bounds for length " + total);
                }
                if (index >= baseActive) {
                    ItemSlot s = overlay.itemsOverlay.appendedSlots[index - baseActive];
                    return s.replaced ? s.replacedMsg.baseRef() : s.base;
                }
                if (overlay != null && overlay.itemsOverlay != null) {
                    ItemSlot s = overlay.itemsOverlay.getBaseSlot(index);
                    if (s != null) {
                        return s.replaced ? s.replacedMsg.baseRef() : s.base;
                    }
                }
                return base.getOrCreateItemBase(index);
            }

            LineItemOverlay resolveItemReadOverlay(int index) {
                int baseActive = (overlay != null && overlay.itemsOverlay != null && overlay.itemsOverlay.cleared)
                        ? 0 : base.ensureScannedReturnItemsCount();
                int appCount = (overlay != null && overlay.itemsOverlay != null) ? overlay.itemsOverlay.appendedCount : 0;
                int total = baseActive + appCount;
                if (index < 0 || index >= total) {
                    throw new IndexOutOfBoundsException("Index " + index + " out of bounds for length " + total);
                }
                if (index >= baseActive) {
                    ItemSlot s = overlay.itemsOverlay.appendedSlots[index - baseActive];
                    return s.replaced ? s.replacedMsg.overlayRef() : s.patch;
                }
                if (overlay != null && overlay.itemsOverlay != null) {
                    ItemSlot s = overlay.itemsOverlay.getBaseSlot(index);
                    if (s != null) {
                        return s.replaced ? s.replacedMsg.overlayRef() : s.patch;
                    }
                }
                return null;
            }

            LineItemOverlay ensureMutableItemOverlay(int index) {
                ItemsOverlay io = ensureMutableItemsOverlay();
                int baseActive = io.cleared ? 0 : base.ensureScannedReturnItemsCount();
                int total = baseActive + io.appendedCount;
                if (index < 0 || index >= total) {
                    throw new IndexOutOfBoundsException("Index " + index + " out of bounds for length " + total);
                }
                if (index >= baseActive) {
                    int appIdx = index - baseActive;
                    ItemSlot s = io.appendedSlots[appIdx];
                    if (s.replaced) {
                        LineItemBase sb = s.replacedMsg.baseRef();
                        sb.freeze();
                        LineItemOverlay patch = s.replacedMsg.overlayRef() != null
                                ? s.replacedMsg.overlayRef().copyMutable() : new LineItemOverlay();
                        s = ItemSlot.forPatch(sb, patch);
                        io.appendedSlots[appIdx] = s;
                    } else if (s.patch == null) {
                        s.patch = new LineItemOverlay();
                    } else if (s.patch.isFrozen()) {
                        s.patch = s.patch.copyMutable();
                    }
                    s.patch.invalidateSizeCache();
                    return s.patch;
                }
                ItemSlot s = io.getBaseSlot(index);
                if (s == null) {
                    LineItemBase ib = base.getOrCreateItemBase(index);
                    LineItemOverlay patch = new LineItemOverlay();
                    s = ItemSlot.forPatch(ib, patch);
                    io.putBaseSlot(index, base, s);
                } else if (s.replaced) {
                    LineItemBase sb = s.replacedMsg.baseRef();
                    sb.freeze();
                    LineItemOverlay patch = s.replacedMsg.overlayRef() != null
                            ? s.replacedMsg.overlayRef().copyMutable() : new LineItemOverlay();
                    s = ItemSlot.forPatch(sb, patch);
                    io.putBaseSlot(index, base, s);
                } else if (s.patch == null) {
                    s.patch = new LineItemOverlay();
                } else if (s.patch.isFrozen()) {
                    s.patch = s.patch.copyMutable();
                }
                s.patch.invalidateSizeCache();
                return s.patch;
            }

            void syncItemOverlayState(int index) {
                OrderOverlay ov = this.overlay;
                if (ov == null || ov.itemsOverlay == null) return;
                ov.invalidateSizeCache();
                ItemsOverlay io = ov.itemsOverlay;
                int baseActive = io.cleared ? 0 : base.ensureScannedReturnItemsCount();
                if (index >= baseActive) {
                    ov.writtenMask |= OrderOverlay.BIT_ITEMS;
                    ov.varSizeMask |= OrderOverlay.BIT_ITEMS;
                    ov.appendedMask |= OrderOverlay.BIT_ITEMS;
                    return;
                }
                io.recomputeSameSizeCount(base);
                recomputeParentItemsMasks(ov, io);
            }

            private void recomputeParentItemsMasks(OrderOverlay ov, ItemsOverlay io) {
                if (io.cleared) {
                    if (base.itemsCount > 0) {
                        ov.writtenMask |= OrderOverlay.BIT_ITEMS;
                        ov.deletedMask |= OrderOverlay.BIT_ITEMS;
                        ov.varSizeMask |= OrderOverlay.BIT_ITEMS;
                    } else {
                        ov.deletedMask &= ~OrderOverlay.BIT_ITEMS;
                        if (io.appendedCount == 0) {
                            ov.writtenMask &= ~OrderOverlay.BIT_ITEMS;
                            ov.varSizeMask &= ~OrderOverlay.BIT_ITEMS;
                        }
                    }
                    ov.sameSizeMask &= ~OrderOverlay.BIT_ITEMS;
                } else {
                    ov.deletedMask &= ~OrderOverlay.BIT_ITEMS;
                    if (io.dirtyBaseCount > 0) {
                        ov.writtenMask |= OrderOverlay.BIT_ITEMS;
                        if (io.sameSizeDirtyCount == io.dirtyBaseCount) {
                            ov.sameSizeMask |= OrderOverlay.BIT_ITEMS;
                            if (io.appendedCount > 0) ov.varSizeMask |= OrderOverlay.BIT_ITEMS;
                            else ov.varSizeMask &= ~OrderOverlay.BIT_ITEMS;
                        } else {
                            ov.sameSizeMask &= ~OrderOverlay.BIT_ITEMS;
                            ov.varSizeMask |= OrderOverlay.BIT_ITEMS;
                        }
                    } else {
                        ov.sameSizeMask &= ~OrderOverlay.BIT_ITEMS;
                        if (io.appendedCount > 0) {
                            ov.writtenMask |= OrderOverlay.BIT_ITEMS;
                            ov.varSizeMask |= OrderOverlay.BIT_ITEMS;
                        } else {
                            ov.writtenMask &= ~OrderOverlay.BIT_ITEMS;
                            ov.varSizeMask &= ~OrderOverlay.BIT_ITEMS;
                        }
                    }
                }
                if (io.appendedCount > 0) {
                    ov.appendedMask |= OrderOverlay.BIT_ITEMS;
                    ov.varSizeMask |= OrderOverlay.BIT_ITEMS;
                } else {
                    ov.appendedMask &= ~OrderOverlay.BIT_ITEMS;
                }
                ov.invalidateSizeCache();
            }

            // -------------------- Field Getters & Setters --------------------

            @Override public long getOrderId() { return OrderOps.getOrderId(base, overlay); }
            public Builder setOrderId(long v) {
                OrderOverlay ov = ensureMutableOverlay();
                ov.orderId = v;
                ov.markScalarWritten(base, OrderBase.SLOT_ORDER_ID, OrderOverlay.BIT_ORDER_ID, WireWriter.varint64Size(v));
                return this;
            }

            @Override public String getBuyerName() { return OrderOps.getBuyerName(base, overlay); }
            @Override public ByteString getBuyerNameBytes() {
                return OrderOps.getBuyerNameSlice(base, overlay, buyerNameSlice_).toByteStringZeroCopy();
            }
            public Builder setBuyerName(String v) {
                OrderOverlay ov = ensureMutableOverlay();
                ov.buyerName = v;
                ov.buyerNameUtf8 = v.getBytes(StandardCharsets.UTF_8);
                ov.markScalarWritten(base, OrderBase.SLOT_BUYER_NAME, OrderOverlay.BIT_BUYER_NAME, ov.buyerNameUtf8.length);
                return this;
            }

            @Override public int getStatus() { return OrderOps.getStatus(base, overlay); }
            public Builder setStatus(int v) {
                OrderOverlay ov = ensureMutableOverlay();
                ov.status = v;
                ov.markScalarWritten(base, OrderBase.SLOT_STATUS, OrderOverlay.BIT_STATUS, WireWriter.varint32Size(v));
                return this;
            }

            @Override public long getUpdatedAtFixed64() { return OrderOps.getUpdatedAtFixed64(base, overlay); }
            public Builder setUpdatedAtFixed64(long v) {
                OrderOverlay ov = ensureMutableOverlay();
                ov.updatedAtFixed64 = v;
                ov.markScalarWritten(base, OrderBase.SLOT_UPDATED_AT, OrderOverlay.BIT_UPDATED_AT, 8);
                return this;
            }

            @Override public boolean hasUser() { return OrderOps.hasUser(base, overlay); }
            public LazyUserProfile getUser() { return OrderOps.getUserImmutable(base, overlay); }
            @Override public UserProfileOrBuilder getUserOrBuilder() { return cachedUserBuilder; }

            public LazyUserProfile.Builder getUserBuilder() {
                return cachedUserBuilder;
            }

            public Builder setUser(LazyUserProfile user) {
                OrderOverlay ov = ensureMutableOverlay();
                base.ensureScanned();
                int idx = base.slotTapeIdx[OrderBase.SLOT_USER];
                // Check if restoring exact original clean single-segment slice from base
                if (base.userSegCount == 1
                        && user.isClean()
                        && !user.isMultiSegment()
                        && user.rawBuffer() == base.rawBuffer()
                        && user.rawOffset() == base.tapeValueOffset(idx)
                        && user.rawLength() == base.tapeValueLength(idx)) {
                    ov.userMode = OrderOverlay.USER_INHERIT_BASE;
                    ov.userPatchBase = null;
                    ov.userPatch = null;
                    ov.userReplaced = null;
                    ov.writtenMask &= ~OrderOverlay.BIT_USER;
                    ov.sameSizeMask &= ~OrderOverlay.BIT_USER;
                    ov.varSizeMask &= ~OrderOverlay.BIT_USER;
                    ov.deletedMask &= ~OrderOverlay.BIT_USER;
                    ov.appendedMask &= ~OrderOverlay.BIT_USER;
                    ov.invalidateSizeCache();
                    return this;
                }
                ov.userMode = OrderOverlay.USER_REPLACED;
                ov.userReplaced = user;
                ov.userPatchBase = null;
                ov.userPatch = null;
                ov.writtenMask |= OrderOverlay.BIT_USER;
                ov.sameSizeMask &= ~OrderOverlay.BIT_USER;
                ov.deletedMask &= ~OrderOverlay.BIT_USER;
                ov.varSizeMask |= OrderOverlay.BIT_USER;
                if (base.userSegCount == 0) {
                    ov.appendedMask |= OrderOverlay.BIT_USER;
                } else {
                    ov.appendedMask &= ~OrderOverlay.BIT_USER;
                }
                ov.invalidateSizeCache();
                return this;
            }

            public Builder setUser(LazyUserProfile.Builder b) {
                return setUser(b.build());
            }

            public Builder clearUser() {
                OrderOverlay ov = ensureMutableOverlay();
                base.ensureScanned();
                ov.userMode = OrderOverlay.USER_DELETED;
                ov.userPatchBase = null;
                ov.userPatch = null;
                ov.userReplaced = null;
                ov.writtenMask &= ~OrderOverlay.BIT_USER;
                ov.sameSizeMask &= ~OrderOverlay.BIT_USER;
                ov.appendedMask &= ~OrderOverlay.BIT_USER;
                if (base.userSegCount > 0) {
                    ov.deletedMask |= OrderOverlay.BIT_USER;
                    ov.varSizeMask |= OrderOverlay.BIT_USER;
                } else {
                    ov.deletedMask &= ~OrderOverlay.BIT_USER;
                    ov.varSizeMask &= ~OrderOverlay.BIT_USER;
                }
                ov.invalidateSizeCache();
                return this;
            }

            public List<LazyLineItem> getItemsList() {
                base.freeze();
                ItemsOverlay snapIo = (overlay != null && overlay.itemsOverlay != null) ? overlay.itemsOverlay : null;
                if (snapIo != null) {
                    snapIo.freeze();
                }
                final OrderBase snapBase = base;
                int sz = OrderOps.getItemsCountFrom(snapBase, snapIo);
                return new ImmutableLazyList<>(sz, idx -> OrderOps.getItemFrom(snapBase, snapIo, idx));
            }
            @Override public List<? extends LineItemOrBuilder> getItemsOrBuilderList() { return getItemsList(); }
            @Override public int getItemsCount() { return OrderOps.getItemsCount(base, overlay); }
            public LazyLineItem getItems(int index) { return OrderOps.getItemImmutable(base, overlay, index); }
            @Override public LineItemOrBuilder getItemsOrBuilder(int index) { return new LazyLineItem.Builder(this, index); }

            public LazyLineItem.Builder getItemsBuilder(int index) {
                return new LazyLineItem.Builder(this, index);
            }

            public Builder setItems(int index, LazyLineItem item) {
                ItemsOverlay io = ensureMutableItemsOverlay();
                int baseActive = io.cleared ? 0 : base.ensureScannedReturnItemsCount();
                int total = baseActive + io.appendedCount;
                if (index < 0 || index >= total) {
                    throw new IndexOutOfBoundsException("Index " + index + " out of bounds for length " + total);
                }
                if (index >= baseActive) {
                    io.appendedSlots[index - baseActive] = ItemSlot.forReplaced(item);
                } else {
                    io.putBaseSlot(index, base, ItemSlot.forReplaced(item));
                }
                recomputeParentItemsMasks(this.overlay, io);
                return this;
            }

            public Builder setItems(int index, LazyLineItem.Builder itemBuilder) {
                return setItems(index, itemBuilder.build());
            }

            public Builder addItems(LazyLineItem item) {
                ItemsOverlay io = ensureMutableItemsOverlay();
                io.addAppended(item);
                recomputeParentItemsMasks(this.overlay, io);
                return this;
            }

            public Builder addItems(LazyLineItem.Builder itemBuilder) {
                return addItems(itemBuilder.build());
            }

            public Builder clearItems() {
                ItemsOverlay io = ensureMutableItemsOverlay();
                base.ensureScanned();
                io.reset();
                io.cleared = true;
                recomputeParentItemsMasks(this.overlay, io);
                return this;
            }

            @Override public ByteString getPayload() { return OrderOps.getPayload(base, overlay); }
            public Builder setPayload(ByteString v) {
                OrderOverlay ov = ensureMutableOverlay();
                ov.payload = v;
                ov.payloadBytes = v.toByteArray();
                ov.markScalarWritten(base, OrderBase.SLOT_PAYLOAD, OrderOverlay.BIT_PAYLOAD, ov.payloadBytes.length);
                return this;
            }
            public Builder setPayloadBytes(byte[] rawBytes) {
                OrderOverlay ov = ensureMutableOverlay();
                ov.payload = UnsafeByteOperations.unsafeWrap(rawBytes);
                ov.payloadBytes = rawBytes;
                ov.markScalarWritten(base, OrderBase.SLOT_PAYLOAD, OrderOverlay.BIT_PAYLOAD, rawBytes.length);
                return this;
            }

            @Override public String getAuthToken() { return OrderOps.getAuthToken(base, overlay); }
            @Override public ByteString getAuthTokenBytes() {
                return OrderOps.getAuthTokenSlice(base, overlay, authTokenSlice_).toByteStringZeroCopy();
            }
            public Builder setAuthToken(String v) {
                OrderOverlay ov = ensureMutableOverlay();
                ov.authToken = v;
                ov.authTokenUtf8 = v.getBytes(StandardCharsets.UTF_8);
                ov.markScalarWritten(base, OrderBase.SLOT_AUTH_TOKEN, OrderOverlay.BIT_AUTH_TOKEN, ov.authTokenUtf8.length);
                return this;
            }
            public Builder clearAuthToken() {
                OrderOverlay ov = ensureMutableOverlay();
                base.ensureScanned();
                ov.authToken = "";
                ov.authTokenUtf8 = null;
                ov.writtenMask &= ~OrderOverlay.BIT_AUTH_TOKEN;
                ov.sameSizeMask &= ~OrderOverlay.BIT_AUTH_TOKEN;
                ov.appendedMask &= ~OrderOverlay.BIT_AUTH_TOKEN;
                if (base.slotTapeIdx[OrderBase.SLOT_AUTH_TOKEN] >= 0) {
                    ov.deletedMask |= OrderOverlay.BIT_AUTH_TOKEN;
                    ov.varSizeMask |= OrderOverlay.BIT_AUTH_TOKEN;
                } else {
                    ov.deletedMask &= ~OrderOverlay.BIT_AUTH_TOKEN;
                    ov.varSizeMask &= ~OrderOverlay.BIT_AUTH_TOKEN;
                }
                ov.invalidateSizeCache();
                return this;
            }

            @Override public String getTraceId() { return OrderOps.getTraceId(base, overlay); }
            @Override public ByteString getTraceIdBytes() {
                return OrderOps.getTraceIdSlice(base, overlay, traceIdSlice_).toByteStringZeroCopy();
            }
            public Builder setTraceId(String v) {
                OrderOverlay ov = ensureMutableOverlay();
                ov.traceId = v;
                ov.traceIdUtf8 = v.getBytes(StandardCharsets.UTF_8);
                ov.markScalarWritten(base, OrderBase.SLOT_TRACE_ID, OrderOverlay.BIT_TRACE_ID, ov.traceIdUtf8.length);
                return this;
            }

            @Override public long getTotalCents() { return OrderOps.getTotalCents(base, overlay); }
            public Builder setTotalCents(long v) {
                OrderOverlay ov = ensureMutableOverlay();
                ov.totalCents = v;
                ov.markScalarWritten(base, OrderBase.SLOT_TOTAL, OrderOverlay.BIT_TOTAL, WireWriter.varint64Size(v));
                return this;
            }

            @Override
            public Builder clone() {
                base.freeze();
                return new Builder(base, overlay != null ? overlay.copyMutable() : null);
            }
        }
    }

    // Add package-private helper on OrderBase (avoid static init workaround)
    // Helper method placed directly via compile-time check:
    private static int ensureScannedItems(OrderBase base) {
        base.ensureScanned();
        return base.itemsCount;
    }
}
