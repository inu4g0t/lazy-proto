package io.lazyproto.model;

import com.google.protobuf.ByteString;
import com.google.protobuf.Message;
import com.google.protobuf.UnsafeByteOperations;
import io.lazyproto.model.OrderInterfaces.LineItemOrBuilder;
import io.lazyproto.model.OrderInterfaces.OrderEnvelopeOrBuilder;
import io.lazyproto.model.OrderInterfaces.UserProfileOrBuilder;
import io.lazyproto.runtime.AbstractLazyBuilder;
import io.lazyproto.runtime.AbstractLazyMessage;
import io.lazyproto.runtime.LazyProtobufList;
import io.lazyproto.wire.Utf8Slice;
import io.lazyproto.wire.WireFormat;
import io.lazyproto.wire.WireReader;
import io.lazyproto.wire.WireWriter;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

/**
 * Lazy-Proto generated-style Java classes for {@code OrderEnvelope}, {@code UserProfile}, and {@code LineItem}.
 *
 * <p>Implements {@link OrderEnvelopeOrBuilder}, {@link UserProfileOrBuilder}, {@link LineItemOrBuilder},
 * {@link com.google.protobuf.Message}, and {@link com.google.protobuf.Message.Builder} (via snapshot-isolated
 * {@code Builder} inner classes with Ownership-Transfer + Copy-on-Post-Build-Write).
 * Also exposes {@code toInPlaceBuilder()} for explicit zero-allocation hot-path mutations.
 */
public final class LazyOrderProto {
    private LazyOrderProto() {}

    // =========================================================================
    // 1. LazyUserProfile (field #1 userId, #2 city, #3 vipLevel, #4 score, #5 email)
    // =========================================================================

    public static final class LazyUserProfile extends AbstractLazyMessage<LazyUserProfile>
            implements UserProfileOrBuilder {

        private static final int SLOT_USER_ID   = 0; // field #1 (int64, varint)
        private static final int SLOT_CITY      = 1; // field #2 (string, len)
        private static final int SLOT_VIP_LEVEL = 2; // field #3 (int32, varint)
        private static final int SLOT_SCORE     = 3; // field #4 (double, fixed64)
        private static final int SLOT_EMAIL     = 4; // field #5 (string, len)

        private static final long BIT_USER_ID   = 1L << SLOT_USER_ID;
        private static final long BIT_CITY      = 1L << SLOT_CITY;
        private static final long BIT_VIP_LEVEL = 1L << SLOT_VIP_LEVEL;
        private static final long BIT_SCORE     = 1L << SLOT_SCORE;
        private static final long BIT_EMAIL     = 1L << SLOT_EMAIL;

        private final int[] slotTapeIdx = new int[] { -1, -1, -1, -1, -1 };

        private long userId_;
        private String city_;
        private byte[] cityUtf8Dirty_;
        private int vipLevel_;
        private double score_;
        private String email_;
        private byte[] emailUtf8Dirty_;
        private final Utf8Slice citySlice_ = new Utf8Slice();
        private final Utf8Slice emailSlice_ = new Utf8Slice();

        public LazyUserProfile() {
            super(8);
        }

        public static LazyUserProfile parseFrom(byte[] data) {
            return new LazyUserProfile().wrap(data, 0, data.length);
        }

        public static Builder newBuilder() {
            LazyUserProfile m = new LazyUserProfile();
            m.markScannedEmpty();
            return new Builder(m);
        }

        @Override
        protected void resetSlots() {
            Arrays.fill(slotTapeIdx, -1);
            userId_ = 0L;
            city_ = null;
            cityUtf8Dirty_ = null;
            vipLevel_ = 0;
            score_ = 0.0;
            email_ = null;
            emailUtf8Dirty_ = null;
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
                    return; // Unknown field stays in FieldTape as CLEAN_RAW
            }
            int prev = slotTapeIdx[slot];
            if (prev >= 0) {
                setTapeFlags(prev, FLAG_SHADOWED);
            }
            slotTapeIdx[slot] = tapeIdx;
            presentMask |= (1L << slot);
        }

        @Override
        public long getUserId() {
            if ((decodedMask & BIT_USER_ID) == 0L) {
                ensureScanned();
                int idx = slotTapeIdx[SLOT_USER_ID];
                userId_ = idx >= 0 ? WireReader.readVarint64At(buf, tapeValueOffset(idx)) : 0L;
                decodedMask |= BIT_USER_ID;
            }
            return userId_;
        }

        public LazyUserProfile setUserId(long value) {
            ensureScanned();
            this.userId_ = value;
            this.decodedMask |= BIT_USER_ID;
            this.presentMask |= BIT_USER_ID;
            markScalarModified(SLOT_USER_ID, BIT_USER_ID, WireWriter.varint64Size(value));
            return this;
        }

        @Override
        public String getCity() {
            if ((decodedMask & BIT_CITY) == 0L) {
                ensureScanned();
                int idx = slotTapeIdx[SLOT_CITY];
                city_ = idx >= 0 ? WireReader.readStringAt(buf, tapeValueOffset(idx), tapeValueLength(idx)) : "";
                decodedMask |= BIT_CITY;
            }
            return city_;
        }

        public Utf8Slice getCitySlice() {
            ensureScanned();
            if (cityUtf8Dirty_ != null) {
                return citySlice_.wrap(cityUtf8Dirty_, 0, cityUtf8Dirty_.length);
            }
            int idx = slotTapeIdx[SLOT_CITY];
            if (idx >= 0) {
                return citySlice_.wrap(buf, tapeValueOffset(idx), tapeValueLength(idx));
            }
            return citySlice_.wrap(buf, 0, 0);
        }

        @Override
        public ByteString getCityBytes() {
            return getCitySlice().toByteStringZeroCopy();
        }

        public LazyUserProfile setCity(String value) {
            ensureScanned();
            this.city_ = value;
            this.cityUtf8Dirty_ = value.getBytes(StandardCharsets.UTF_8);
            this.decodedMask |= BIT_CITY;
            this.presentMask |= BIT_CITY;
            markScalarModified(SLOT_CITY, BIT_CITY, cityUtf8Dirty_.length);
            return this;
        }

        @Override
        public int getVipLevel() {
            if ((decodedMask & BIT_VIP_LEVEL) == 0L) {
                ensureScanned();
                int idx = slotTapeIdx[SLOT_VIP_LEVEL];
                vipLevel_ = idx >= 0 ? WireReader.readVarint32At(buf, tapeValueOffset(idx)) : 0;
                decodedMask |= BIT_VIP_LEVEL;
            }
            return vipLevel_;
        }

        public LazyUserProfile setVipLevel(int value) {
            ensureScanned();
            this.vipLevel_ = value;
            this.decodedMask |= BIT_VIP_LEVEL;
            this.presentMask |= BIT_VIP_LEVEL;
            markScalarModified(SLOT_VIP_LEVEL, BIT_VIP_LEVEL, WireWriter.varint32Size(value));
            return this;
        }

        @Override
        public double getScore() {
            if ((decodedMask & BIT_SCORE) == 0L) {
                ensureScanned();
                int idx = slotTapeIdx[SLOT_SCORE];
                score_ = idx >= 0 ? WireReader.readDoubleAt(buf, tapeValueOffset(idx)) : 0.0;
                decodedMask |= BIT_SCORE;
            }
            return score_;
        }

        public LazyUserProfile setScore(double value) {
            ensureScanned();
            this.score_ = value;
            this.decodedMask |= BIT_SCORE;
            this.presentMask |= BIT_SCORE;
            markScalarModified(SLOT_SCORE, BIT_SCORE, 8);
            return this;
        }

        @Override
        public String getEmail() {
            if ((decodedMask & BIT_EMAIL) == 0L) {
                ensureScanned();
                int idx = slotTapeIdx[SLOT_EMAIL];
                email_ = idx >= 0 ? WireReader.readStringAt(buf, tapeValueOffset(idx), tapeValueLength(idx)) : "";
                decodedMask |= BIT_EMAIL;
            }
            return email_;
        }

        public Utf8Slice getEmailSlice() {
            ensureScanned();
            if (emailUtf8Dirty_ != null) {
                return emailSlice_.wrap(emailUtf8Dirty_, 0, emailUtf8Dirty_.length);
            }
            int idx = slotTapeIdx[SLOT_EMAIL];
            if (idx >= 0) {
                return emailSlice_.wrap(buf, tapeValueOffset(idx), tapeValueLength(idx));
            }
            return emailSlice_.wrap(buf, 0, 0);
        }

        @Override
        public ByteString getEmailBytes() {
            return getEmailSlice().toByteStringZeroCopy();
        }

        public LazyUserProfile setEmail(String value) {
            ensureScanned();
            this.email_ = value;
            this.emailUtf8Dirty_ = value.getBytes(StandardCharsets.UTF_8);
            this.decodedMask |= BIT_EMAIL;
            this.presentMask |= BIT_EMAIL;
            markScalarModified(SLOT_EMAIL, BIT_EMAIL, emailUtf8Dirty_.length);
            return this;
        }

        private void markScalarModified(int slot, long bit, int newEncodedValueLen) {
            int idx = slotTapeIdx[slot];
            if (idx >= 0) {
                int oldLen = tapeValueLength(idx);
                if (oldLen == newEncodedValueLen && tapeFlags(idx) != FLAG_DIRTY_VAR_SIZE) {
                    setTapeFlags(idx, FLAG_DIRTY_SAME_SIZE);
                    this.sameSizeDirtyMask |= bit;
                    invalidateSizeCacheUpward();
                    bubbleSameSizeDirtyToParent();
                    return;
                }
                setTapeFlags(idx, FLAG_DIRTY_VAR_SIZE);
                this.varSizeDirtyMask |= bit;
            } else {
                this.appendedMask |= bit;
                this.varSizeDirtyMask |= bit;
            }
            bubbleVarSizeDirtyToParent();
        }

        @Override
        public void applyInPlacePatches(byte[] target, int baseOffset) {
            long mask = sameSizeDirtyMask;
            while (mask != 0L) {
                int slot = Long.numberOfTrailingZeros(mask);
                int idx = slotTapeIdx[slot];
                if (idx >= 0 && tapeFlags(idx) == FLAG_DIRTY_SAME_SIZE) {
                    int patchPos = tapeValueOffset(idx) - baseOffset;
                    switch (slot) {
                        case SLOT_USER_ID:
                            WireWriter.patchVarint64At(target, patchPos, userId_);
                            break;
                        case SLOT_CITY:
                            WireWriter.patchBytesAt(target, patchPos, cityUtf8Dirty_, 0, cityUtf8Dirty_.length);
                            break;
                        case SLOT_VIP_LEVEL:
                            WireWriter.patchVarint32At(target, patchPos, vipLevel_);
                            break;
                        case SLOT_SCORE:
                            WireWriter.patchFixed64At(target, patchPos, Double.doubleToRawLongBits(score_));
                            break;
                        case SLOT_EMAIL:
                            WireWriter.patchBytesAt(target, patchPos, emailUtf8Dirty_, 0, emailUtf8Dirty_.length);
                            break;
                    }
                }
                mask &= (mask - 1);
            }
        }

        @Override
        protected int computeDirtyEntrySize(int tapeIdx, int fieldNumber, int wireType) {
            switch (fieldNumber) {
                case 1: return 1 + WireWriter.varint64Size(userId_);
                case 2: return 1 + WireWriter.varint32Size(cityUtf8Dirty_.length) + cityUtf8Dirty_.length;
                case 3: return 1 + WireWriter.varint32Size(vipLevel_);
                case 4: return 1 + 8;
                case 5: return 1 + WireWriter.varint32Size(emailUtf8Dirty_.length) + emailUtf8Dirty_.length;
                default: throw new IllegalStateException("Unexpected dirty field " + fieldNumber);
            }
        }

        @Override
        protected void writeDirtyEntry(int tapeIdx, int fieldNumber, int wireType, WireWriter writer) {
            switch (fieldNumber) {
                case 1:
                    writer.writeTag(1, WireFormat.WIRETYPE_VARINT);
                    writer.writeVarint64(userId_);
                    break;
                case 2:
                    writer.writeLengthDelimitedBytes(2, cityUtf8Dirty_, 0, cityUtf8Dirty_.length);
                    break;
                case 3:
                    writer.writeTag(3, WireFormat.WIRETYPE_VARINT);
                    writer.writeVarint32(vipLevel_);
                    break;
                case 4:
                    writer.writeTag(4, WireFormat.WIRETYPE_FIXED64);
                    writer.writeDouble(score_);
                    break;
                case 5:
                    writer.writeLengthDelimitedBytes(5, emailUtf8Dirty_, 0, emailUtf8Dirty_.length);
                    break;
            }
        }

        @Override
        protected int computeAppendedFieldsSize() {
            int size = 0;
            if ((appendedMask & BIT_USER_ID) != 0L && userId_ != 0L) size += 1 + WireWriter.varint64Size(userId_);
            if ((appendedMask & BIT_CITY) != 0L && cityUtf8Dirty_ != null && cityUtf8Dirty_.length > 0) {
                size += 1 + WireWriter.varint32Size(cityUtf8Dirty_.length) + cityUtf8Dirty_.length;
            }
            if ((appendedMask & BIT_VIP_LEVEL) != 0L && vipLevel_ != 0) size += 1 + WireWriter.varint32Size(vipLevel_);
            if ((appendedMask & BIT_SCORE) != 0L && Double.doubleToRawLongBits(score_) != 0L) size += 1 + 8;
            if ((appendedMask & BIT_EMAIL) != 0L && emailUtf8Dirty_ != null && emailUtf8Dirty_.length > 0) {
                size += 1 + WireWriter.varint32Size(emailUtf8Dirty_.length) + emailUtf8Dirty_.length;
            }
            return size;
        }

        @Override
        protected void writeAppendedFields(WireWriter writer) {
            if ((appendedMask & BIT_USER_ID) != 0L && userId_ != 0L) {
                writer.writeTag(1, WireFormat.WIRETYPE_VARINT);
                writer.writeVarint64(userId_);
            }
            if ((appendedMask & BIT_CITY) != 0L && cityUtf8Dirty_ != null && cityUtf8Dirty_.length > 0) {
                writer.writeLengthDelimitedBytes(2, cityUtf8Dirty_, 0, cityUtf8Dirty_.length);
            }
            if ((appendedMask & BIT_VIP_LEVEL) != 0L && vipLevel_ != 0) {
                writer.writeTag(3, WireFormat.WIRETYPE_VARINT);
                writer.writeVarint32(vipLevel_);
            }
            if ((appendedMask & BIT_SCORE) != 0L && Double.doubleToRawLongBits(score_) != 0L) {
                writer.writeTag(4, WireFormat.WIRETYPE_FIXED64);
                writer.writeDouble(score_);
            }
            if ((appendedMask & BIT_EMAIL) != 0L && emailUtf8Dirty_ != null && emailUtf8Dirty_.length > 0) {
                writer.writeLengthDelimitedBytes(5, emailUtf8Dirty_, 0, emailUtf8Dirty_.length);
            }
        }

        @Override
        public LazyUserProfile copy() {
            LazyUserProfile c = new LazyUserProfile();
            c.copyFrom(this);
            return c;
        }

        public LazyUserProfile copyFrom(LazyUserProfile src) {
            copyBaseFrom(src);
            System.arraycopy(src.slotTapeIdx, 0, this.slotTapeIdx, 0, slotTapeIdx.length);
            this.userId_ = src.userId_;
            this.city_ = src.city_;
            this.cityUtf8Dirty_ = src.cityUtf8Dirty_;
            this.vipLevel_ = src.vipLevel_;
            this.score_ = src.score_;
            this.email_ = src.email_;
            this.emailUtf8Dirty_ = src.emailUtf8Dirty_;
            return this;
        }

        @Override public Message getDefaultInstanceForType() { return new LazyUserProfile(); }
        @Override public Builder newBuilderForType() { return newBuilder(); }
        @Override public Builder toBuilder() { return new Builder(this.copy()); }

        public static final class Builder extends AbstractLazyBuilder<LazyUserProfile, Builder>
                implements UserProfileOrBuilder {

            private final LazyOrderEnvelope.Builder parentBuilder;

            private Builder(LazyUserProfile initialImpl) {
                super(initialImpl);
                this.parentBuilder = null;
            }

            Builder(LazyUserProfile initialImpl, LazyOrderEnvelope.Builder parentBuilder) {
                super(initialImpl);
                this.parentBuilder = parentBuilder;
            }

            private void ensureBuilderMutable() {
                if (parentBuilder != null) {
                    if (parentBuilder.isBuilt()) {
                        parentBuilder.ensureMutable();
                        this.impl = parentBuilder.impl().getUserBuilder();
                    }
                } else {
                    ensureMutable();
                }
            }

            @Override public long getUserId() { return impl.getUserId(); }
            public Builder setUserId(long v) { ensureBuilderMutable(); impl.setUserId(v); return this; }
            @Override public String getCity() { return impl.getCity(); }
            @Override public ByteString getCityBytes() { return impl.getCityBytes(); }
            public Builder setCity(String v) { ensureBuilderMutable(); impl.setCity(v); return this; }
            @Override public int getVipLevel() { return impl.getVipLevel(); }
            public Builder setVipLevel(int v) { ensureBuilderMutable(); impl.setVipLevel(v); return this; }
            @Override public double getScore() { return impl.getScore(); }
            public Builder setScore(double v) { ensureBuilderMutable(); impl.setScore(v); return this; }
            @Override public String getEmail() { return impl.getEmail(); }
            @Override public ByteString getEmailBytes() { return impl.getEmailBytes(); }
            public Builder setEmail(String v) { ensureBuilderMutable(); impl.setEmail(v); return this; }

            @Override
            public Builder clone() {
                return new Builder(impl.copy());
            }
        }
    }

    // =========================================================================
    // 2. LazyLineItem (field #1 itemId, #2 sku, #3 quantity, #4 priceCents, #5 description)
    // =========================================================================

    public static final class LazyLineItem extends AbstractLazyMessage<LazyLineItem>
            implements LineItemOrBuilder {

        private static final int SLOT_ITEM_ID     = 0; // field #1 (int64)
        private static final int SLOT_SKU         = 1; // field #2 (string)
        private static final int SLOT_QUANTITY    = 2; // field #3 (int32)
        private static final int SLOT_PRICE_CENTS = 3; // field #4 (int64)
        private static final int SLOT_DESCRIPTION = 4; // field #5 (string)

        private static final long BIT_ITEM_ID     = 1L << SLOT_ITEM_ID;
        private static final long BIT_SKU         = 1L << SLOT_SKU;
        private static final long BIT_QUANTITY    = 1L << SLOT_QUANTITY;
        private static final long BIT_PRICE_CENTS = 1L << SLOT_PRICE_CENTS;
        private static final long BIT_DESCRIPTION = 1L << SLOT_DESCRIPTION;

        private final int[] slotTapeIdx = new int[] { -1, -1, -1, -1, -1 };

        private long itemId_;
        private String sku_;
        private byte[] skuUtf8Dirty_;
        private int quantity_;
        private long priceCents_;
        private String description_;
        private byte[] descriptionUtf8Dirty_;
        private final Utf8Slice skuSlice_ = new Utf8Slice();
        private final Utf8Slice descriptionSlice_ = new Utf8Slice();

        public LazyLineItem() {
            super(8);
        }

        public static LazyLineItem parseFrom(byte[] data) {
            return new LazyLineItem().wrap(data, 0, data.length);
        }

        public static Builder newBuilder() {
            LazyLineItem m = new LazyLineItem();
            m.markScannedEmpty();
            return new Builder(m);
        }

        @Override
        protected void resetSlots() {
            Arrays.fill(slotTapeIdx, -1);
            itemId_ = 0L;
            sku_ = null;
            skuUtf8Dirty_ = null;
            quantity_ = 0;
            priceCents_ = 0L;
            description_ = null;
            descriptionUtf8Dirty_ = null;
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
                setTapeFlags(prev, FLAG_SHADOWED);
            }
            slotTapeIdx[slot] = tapeIdx;
            presentMask |= (1L << slot);
        }

        @Override
        public long getItemId() {
            if ((decodedMask & BIT_ITEM_ID) == 0L) {
                ensureScanned();
                int idx = slotTapeIdx[SLOT_ITEM_ID];
                itemId_ = idx >= 0 ? WireReader.readVarint64At(buf, tapeValueOffset(idx)) : 0L;
                decodedMask |= BIT_ITEM_ID;
            }
            return itemId_;
        }

        public LazyLineItem setItemId(long value) {
            ensureScanned();
            this.itemId_ = value;
            this.decodedMask |= BIT_ITEM_ID;
            this.presentMask |= BIT_ITEM_ID;
            markScalarModified(SLOT_ITEM_ID, BIT_ITEM_ID, WireWriter.varint64Size(value));
            return this;
        }

        @Override
        public String getSku() {
            if ((decodedMask & BIT_SKU) == 0L) {
                ensureScanned();
                int idx = slotTapeIdx[SLOT_SKU];
                sku_ = idx >= 0 ? WireReader.readStringAt(buf, tapeValueOffset(idx), tapeValueLength(idx)) : "";
                decodedMask |= BIT_SKU;
            }
            return sku_;
        }

        public Utf8Slice getSkuSlice() {
            ensureScanned();
            if (skuUtf8Dirty_ != null) return skuSlice_.wrap(skuUtf8Dirty_, 0, skuUtf8Dirty_.length);
            int idx = slotTapeIdx[SLOT_SKU];
            return idx >= 0 ? skuSlice_.wrap(buf, tapeValueOffset(idx), tapeValueLength(idx)) : skuSlice_.wrap(buf, 0, 0);
        }

        @Override
        public ByteString getSkuBytes() {
            return getSkuSlice().toByteStringZeroCopy();
        }

        public LazyLineItem setSku(String value) {
            ensureScanned();
            this.sku_ = value;
            this.skuUtf8Dirty_ = value.getBytes(StandardCharsets.UTF_8);
            this.decodedMask |= BIT_SKU;
            this.presentMask |= BIT_SKU;
            markScalarModified(SLOT_SKU, BIT_SKU, skuUtf8Dirty_.length);
            return this;
        }

        @Override
        public int getQuantity() {
            if ((decodedMask & BIT_QUANTITY) == 0L) {
                ensureScanned();
                int idx = slotTapeIdx[SLOT_QUANTITY];
                quantity_ = idx >= 0 ? WireReader.readVarint32At(buf, tapeValueOffset(idx)) : 0;
                decodedMask |= BIT_QUANTITY;
            }
            return quantity_;
        }

        public LazyLineItem setQuantity(int value) {
            ensureScanned();
            this.quantity_ = value;
            this.decodedMask |= BIT_QUANTITY;
            this.presentMask |= BIT_QUANTITY;
            markScalarModified(SLOT_QUANTITY, BIT_QUANTITY, WireWriter.varint32Size(value));
            return this;
        }

        @Override
        public long getPriceCents() {
            if ((decodedMask & BIT_PRICE_CENTS) == 0L) {
                ensureScanned();
                int idx = slotTapeIdx[SLOT_PRICE_CENTS];
                priceCents_ = idx >= 0 ? WireReader.readVarint64At(buf, tapeValueOffset(idx)) : 0L;
                decodedMask |= BIT_PRICE_CENTS;
            }
            return priceCents_;
        }

        public LazyLineItem setPriceCents(long value) {
            ensureScanned();
            this.priceCents_ = value;
            this.decodedMask |= BIT_PRICE_CENTS;
            this.presentMask |= BIT_PRICE_CENTS;
            markScalarModified(SLOT_PRICE_CENTS, BIT_PRICE_CENTS, WireWriter.varint64Size(value));
            return this;
        }

        @Override
        public String getDescription() {
            if ((decodedMask & BIT_DESCRIPTION) == 0L) {
                ensureScanned();
                int idx = slotTapeIdx[SLOT_DESCRIPTION];
                description_ = idx >= 0 ? WireReader.readStringAt(buf, tapeValueOffset(idx), tapeValueLength(idx)) : "";
                decodedMask |= BIT_DESCRIPTION;
            }
            return description_;
        }

        public Utf8Slice getDescriptionSlice() {
            ensureScanned();
            if (descriptionUtf8Dirty_ != null) return descriptionSlice_.wrap(descriptionUtf8Dirty_, 0, descriptionUtf8Dirty_.length);
            int idx = slotTapeIdx[SLOT_DESCRIPTION];
            return idx >= 0 ? descriptionSlice_.wrap(buf, tapeValueOffset(idx), tapeValueLength(idx)) : descriptionSlice_.wrap(buf, 0, 0);
        }

        @Override
        public ByteString getDescriptionBytes() {
            return getDescriptionSlice().toByteStringZeroCopy();
        }

        public LazyLineItem setDescription(String value) {
            ensureScanned();
            this.description_ = value;
            this.descriptionUtf8Dirty_ = value.getBytes(StandardCharsets.UTF_8);
            this.decodedMask |= BIT_DESCRIPTION;
            this.presentMask |= BIT_DESCRIPTION;
            markScalarModified(SLOT_DESCRIPTION, BIT_DESCRIPTION, descriptionUtf8Dirty_.length);
            return this;
        }

        private void markScalarModified(int slot, long bit, int newEncodedValueLen) {
            int idx = slotTapeIdx[slot];
            if (idx >= 0) {
                int oldLen = tapeValueLength(idx);
                if (oldLen == newEncodedValueLen && tapeFlags(idx) != FLAG_DIRTY_VAR_SIZE) {
                    setTapeFlags(idx, FLAG_DIRTY_SAME_SIZE);
                    this.sameSizeDirtyMask |= bit;
                    invalidateSizeCacheUpward();
                    bubbleSameSizeDirtyToParent();
                    return;
                }
                setTapeFlags(idx, FLAG_DIRTY_VAR_SIZE);
                this.varSizeDirtyMask |= bit;
            } else {
                this.appendedMask |= bit;
                this.varSizeDirtyMask |= bit;
            }
            bubbleVarSizeDirtyToParent();
        }

        @Override
        public void applyInPlacePatches(byte[] target, int baseOffset) {
            long mask = sameSizeDirtyMask;
            while (mask != 0L) {
                int slot = Long.numberOfTrailingZeros(mask);
                int idx = slotTapeIdx[slot];
                if (idx >= 0 && tapeFlags(idx) == FLAG_DIRTY_SAME_SIZE) {
                    int patchPos = tapeValueOffset(idx) - baseOffset;
                    switch (slot) {
                        case SLOT_ITEM_ID:
                            WireWriter.patchVarint64At(target, patchPos, itemId_);
                            break;
                        case SLOT_SKU:
                            WireWriter.patchBytesAt(target, patchPos, skuUtf8Dirty_, 0, skuUtf8Dirty_.length);
                            break;
                        case SLOT_QUANTITY:
                            WireWriter.patchVarint32At(target, patchPos, quantity_);
                            break;
                        case SLOT_PRICE_CENTS:
                            WireWriter.patchVarint64At(target, patchPos, priceCents_);
                            break;
                        case SLOT_DESCRIPTION:
                            WireWriter.patchBytesAt(target, patchPos, descriptionUtf8Dirty_, 0, descriptionUtf8Dirty_.length);
                            break;
                    }
                }
                mask &= (mask - 1);
            }
        }

        @Override
        protected int computeDirtyEntrySize(int tapeIdx, int fieldNumber, int wireType) {
            switch (fieldNumber) {
                case 1: return 1 + WireWriter.varint64Size(itemId_);
                case 2: return 1 + WireWriter.varint32Size(skuUtf8Dirty_.length) + skuUtf8Dirty_.length;
                case 3: return 1 + WireWriter.varint32Size(quantity_);
                case 4: return 1 + WireWriter.varint64Size(priceCents_);
                case 5: return 1 + WireWriter.varint32Size(descriptionUtf8Dirty_.length) + descriptionUtf8Dirty_.length;
                default: throw new IllegalStateException("Unexpected dirty field " + fieldNumber);
            }
        }

        @Override
        protected void writeDirtyEntry(int tapeIdx, int fieldNumber, int wireType, WireWriter writer) {
            switch (fieldNumber) {
                case 1:
                    writer.writeTag(1, WireFormat.WIRETYPE_VARINT);
                    writer.writeVarint64(itemId_);
                    break;
                case 2:
                    writer.writeLengthDelimitedBytes(2, skuUtf8Dirty_, 0, skuUtf8Dirty_.length);
                    break;
                case 3:
                    writer.writeTag(3, WireFormat.WIRETYPE_VARINT);
                    writer.writeVarint32(quantity_);
                    break;
                case 4:
                    writer.writeTag(4, WireFormat.WIRETYPE_VARINT);
                    writer.writeVarint64(priceCents_);
                    break;
                case 5:
                    writer.writeLengthDelimitedBytes(5, descriptionUtf8Dirty_, 0, descriptionUtf8Dirty_.length);
                    break;
            }
        }

        @Override
        protected int computeAppendedFieldsSize() {
            int size = 0;
            if ((appendedMask & BIT_ITEM_ID) != 0L && itemId_ != 0L) size += 1 + WireWriter.varint64Size(itemId_);
            if ((appendedMask & BIT_SKU) != 0L && skuUtf8Dirty_ != null && skuUtf8Dirty_.length > 0) {
                size += 1 + WireWriter.varint32Size(skuUtf8Dirty_.length) + skuUtf8Dirty_.length;
            }
            if ((appendedMask & BIT_QUANTITY) != 0L && quantity_ != 0) size += 1 + WireWriter.varint32Size(quantity_);
            if ((appendedMask & BIT_PRICE_CENTS) != 0L && priceCents_ != 0L) size += 1 + WireWriter.varint64Size(priceCents_);
            if ((appendedMask & BIT_DESCRIPTION) != 0L && descriptionUtf8Dirty_ != null && descriptionUtf8Dirty_.length > 0) {
                size += 1 + WireWriter.varint32Size(descriptionUtf8Dirty_.length) + descriptionUtf8Dirty_.length;
            }
            return size;
        }

        @Override
        protected void writeAppendedFields(WireWriter writer) {
            if ((appendedMask & BIT_ITEM_ID) != 0L && itemId_ != 0L) {
                writer.writeTag(1, WireFormat.WIRETYPE_VARINT);
                writer.writeVarint64(itemId_);
            }
            if ((appendedMask & BIT_SKU) != 0L && skuUtf8Dirty_ != null && skuUtf8Dirty_.length > 0) {
                writer.writeLengthDelimitedBytes(2, skuUtf8Dirty_, 0, skuUtf8Dirty_.length);
            }
            if ((appendedMask & BIT_QUANTITY) != 0L && quantity_ != 0) {
                writer.writeTag(3, WireFormat.WIRETYPE_VARINT);
                writer.writeVarint32(quantity_);
            }
            if ((appendedMask & BIT_PRICE_CENTS) != 0L && priceCents_ != 0L) {
                writer.writeTag(4, WireFormat.WIRETYPE_VARINT);
                writer.writeVarint64(priceCents_);
            }
            if ((appendedMask & BIT_DESCRIPTION) != 0L && descriptionUtf8Dirty_ != null && descriptionUtf8Dirty_.length > 0) {
                writer.writeLengthDelimitedBytes(5, descriptionUtf8Dirty_, 0, descriptionUtf8Dirty_.length);
            }
        }

        @Override
        public LazyLineItem copy() {
            LazyLineItem c = new LazyLineItem();
            c.copyFrom(this);
            return c;
        }

        public LazyLineItem copyFrom(LazyLineItem src) {
            copyBaseFrom(src);
            System.arraycopy(src.slotTapeIdx, 0, this.slotTapeIdx, 0, slotTapeIdx.length);
            this.itemId_ = src.itemId_;
            this.sku_ = src.sku_;
            this.skuUtf8Dirty_ = src.skuUtf8Dirty_;
            this.quantity_ = src.quantity_;
            this.priceCents_ = src.priceCents_;
            this.description_ = src.description_;
            this.descriptionUtf8Dirty_ = src.descriptionUtf8Dirty_;
            return this;
        }

        @Override public Message getDefaultInstanceForType() { return new LazyLineItem(); }
        @Override public Builder newBuilderForType() { return newBuilder(); }
        @Override public Builder toBuilder() { return new Builder(this.copy()); }

        public static final class Builder extends AbstractLazyBuilder<LazyLineItem, Builder>
                implements LineItemOrBuilder {

            private Builder(LazyLineItem initialImpl) {
                super(initialImpl);
            }

            @Override public long getItemId() { return impl.getItemId(); }
            public Builder setItemId(long v) { ensureMutable(); impl.setItemId(v); return this; }
            @Override public String getSku() { return impl.getSku(); }
            @Override public ByteString getSkuBytes() { return impl.getSkuBytes(); }
            public Builder setSku(String v) { ensureMutable(); impl.setSku(v); return this; }
            @Override public int getQuantity() { return impl.getQuantity(); }
            public Builder setQuantity(int v) { ensureMutable(); impl.setQuantity(v); return this; }
            @Override public long getPriceCents() { return impl.getPriceCents(); }
            public Builder setPriceCents(long v) { ensureMutable(); impl.setPriceCents(v); return this; }
            @Override public String getDescription() { return impl.getDescription(); }
            @Override public ByteString getDescriptionBytes() { return impl.getDescriptionBytes(); }
            public Builder setDescription(String v) { ensureMutable(); impl.setDescription(v); return this; }

            @Override
            public Builder clone() {
                return new Builder(impl.copy());
            }
        }
    }

    // =========================================================================
    // 3. LazyOrderEnvelope (Top-level complex nested message)
    // =========================================================================

    public static final class LazyOrderEnvelope extends AbstractLazyMessage<LazyOrderEnvelope>
            implements OrderEnvelopeOrBuilder {

        private static final int SLOT_ORDER_ID   = 0; // field #1 (int64)
        private static final int SLOT_BUYER_NAME = 1; // field #2 (string)
        private static final int SLOT_STATUS     = 2; // field #3 (int32)
        private static final int SLOT_UPDATED_AT = 3; // field #4 (fixed64)
        private static final int SLOT_USER       = 4; // field #5 (UserProfile message)
        private static final int SLOT_ITEMS      = 5; // field #6 (repeated LineItem)
        private static final int SLOT_PAYLOAD    = 6; // field #7 (bytes)
        private static final int SLOT_AUTH_TOKEN = 7; // field #8 (string)
        private static final int SLOT_TRACE_ID   = 8; // field #9 (string)
        private static final int SLOT_TOTAL      = 9; // field #10 (int64)

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

        private final int[] slotTapeIdx = new int[] { -1, -1, -1, -1, -1, -1, -1, -1, -1, -1 };
        private int userTailTapeIdx = -1;

        private long orderId_;
        private String buyerName_;
        private byte[] buyerNameUtf8Dirty_;
        private int status_;
        private long updatedAtFixed64_;
        private LazyUserProfile userView_;
        private final LazyProtobufList<LazyLineItem> itemsList_ =
                new LazyProtobufList<>(this, BIT_ITEMS, LazyLineItem::new);
        private ByteString payload_;
        private byte[] payloadDirty_;
        private String authToken_;
        private byte[] authTokenUtf8Dirty_;
        private String traceId_;
        private byte[] traceIdUtf8Dirty_;
        private long totalCents_;

        private final Utf8Slice buyerNameSlice_ = new Utf8Slice();
        private final Utf8Slice authTokenSlice_ = new Utf8Slice();
        private final Utf8Slice traceIdSlice_   = new Utf8Slice();

        public LazyOrderEnvelope() {
            super(64);
        }

        public static LazyOrderEnvelope parseFrom(byte[] data) {
            return new LazyOrderEnvelope().wrap(data, 0, data.length);
        }

        public static LazyOrderEnvelope parseFrom(ByteString data) {
            return new LazyOrderEnvelope().wrap(data);
        }

        public static Builder newBuilder() {
            LazyOrderEnvelope m = new LazyOrderEnvelope();
            m.markScannedEmpty();
            return new Builder(m);
        }

        @Override
        protected void resetSlots() {
            Arrays.fill(slotTapeIdx, -1);
            userTailTapeIdx = -1;
            orderId_ = 0L;
            buyerName_ = null;
            buyerNameUtf8Dirty_ = null;
            status_ = 0;
            updatedAtFixed64_ = 0L;
            if (userView_ != null) {
                userView_.reset();
            }
            itemsList_.reset();
            payload_ = null;
            payloadDirty_ = null;
            authToken_ = null;
            authTokenUtf8Dirty_ = null;
            traceId_ = null;
            traceIdUtf8Dirty_ = null;
            totalCents_ = 0L;
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
                case 5:
                    if (wireType != WireFormat.WIRETYPE_LENGTH_DELIMITED) return;
                    // Split Sub-Message Merge Semantics: link segments instead of shadowing!
                    if (slotTapeIdx[SLOT_USER] < 0) {
                        slotTapeIdx[SLOT_USER] = tapeIdx;
                        userTailTapeIdx = tapeIdx;
                    } else {
                        setTapeNextIdx(userTailTapeIdx, tapeIdx);
                        userTailTapeIdx = tapeIdx;
                    }
                    presentMask |= BIT_USER;
                    return;
                case 6:
                    if (wireType != WireFormat.WIRETYPE_LENGTH_DELIMITED) return;
                    itemsList_.linkScannedEntry(tapeIdx);
                    presentMask |= BIT_ITEMS;
                    return;
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
                    // Any unknown field or mismatched wire type stays in FieldTape as CLEAN_RAW
                    return;
            }
            int prev = slotTapeIdx[slot];
            if (prev >= 0) {
                setTapeFlags(prev, FLAG_SHADOWED);
            }
            slotTapeIdx[slot] = tapeIdx;
            presentMask |= (1L << slot);
        }

        // -------------------- Field #1: order_id --------------------

        @Override
        public long getOrderId() {
            if ((decodedMask & BIT_ORDER_ID) == 0L) {
                ensureScanned();
                int idx = slotTapeIdx[SLOT_ORDER_ID];
                orderId_ = idx >= 0 ? WireReader.readVarint64At(buf, tapeValueOffset(idx)) : 0L;
                decodedMask |= BIT_ORDER_ID;
            }
            return orderId_;
        }

        public LazyOrderEnvelope setOrderId(long value) {
            ensureScanned();
            this.orderId_ = value;
            this.decodedMask |= BIT_ORDER_ID;
            this.presentMask |= BIT_ORDER_ID;
            markScalarModified(SLOT_ORDER_ID, BIT_ORDER_ID, WireWriter.varint64Size(value));
            return this;
        }

        // -------------------- Field #2: buyer_name --------------------

        @Override
        public String getBuyerName() {
            if ((decodedMask & BIT_BUYER_NAME) == 0L) {
                ensureScanned();
                int idx = slotTapeIdx[SLOT_BUYER_NAME];
                buyerName_ = idx >= 0 ? WireReader.readStringAt(buf, tapeValueOffset(idx), tapeValueLength(idx)) : "";
                decodedMask |= BIT_BUYER_NAME;
            }
            return buyerName_;
        }

        public Utf8Slice getBuyerNameSlice() {
            ensureScanned();
            if (buyerNameUtf8Dirty_ != null) {
                return buyerNameSlice_.wrap(buyerNameUtf8Dirty_, 0, buyerNameUtf8Dirty_.length);
            }
            int idx = slotTapeIdx[SLOT_BUYER_NAME];
            return idx >= 0
                    ? buyerNameSlice_.wrap(buf, tapeValueOffset(idx), tapeValueLength(idx))
                    : buyerNameSlice_.wrap(buf, 0, 0);
        }

        @Override
        public ByteString getBuyerNameBytes() {
            return getBuyerNameSlice().toByteStringZeroCopy();
        }

        public LazyOrderEnvelope setBuyerName(String value) {
            ensureScanned();
            this.buyerName_ = value;
            this.buyerNameUtf8Dirty_ = value.getBytes(StandardCharsets.UTF_8);
            this.decodedMask |= BIT_BUYER_NAME;
            this.presentMask |= BIT_BUYER_NAME;
            markScalarModified(SLOT_BUYER_NAME, BIT_BUYER_NAME, buyerNameUtf8Dirty_.length);
            return this;
        }

        // -------------------- Field #3: status --------------------

        @Override
        public int getStatus() {
            if ((decodedMask & BIT_STATUS) == 0L) {
                ensureScanned();
                int idx = slotTapeIdx[SLOT_STATUS];
                status_ = idx >= 0 ? WireReader.readVarint32At(buf, tapeValueOffset(idx)) : 0;
                decodedMask |= BIT_STATUS;
            }
            return status_;
        }

        public LazyOrderEnvelope setStatus(int value) {
            ensureScanned();
            this.status_ = value;
            this.decodedMask |= BIT_STATUS;
            this.presentMask |= BIT_STATUS;
            markScalarModified(SLOT_STATUS, BIT_STATUS, WireWriter.varint32Size(value));
            return this;
        }

        // -------------------- Field #4: updated_at_fixed64 --------------------

        @Override
        public long getUpdatedAtFixed64() {
            if ((decodedMask & BIT_UPDATED_AT) == 0L) {
                ensureScanned();
                int idx = slotTapeIdx[SLOT_UPDATED_AT];
                updatedAtFixed64_ = idx >= 0 ? WireReader.readFixed64At(buf, tapeValueOffset(idx)) : 0L;
                decodedMask |= BIT_UPDATED_AT;
            }
            return updatedAtFixed64_;
        }

        public LazyOrderEnvelope setUpdatedAtFixed64(long value) {
            ensureScanned();
            this.updatedAtFixed64_ = value;
            this.decodedMask |= BIT_UPDATED_AT;
            this.presentMask |= BIT_UPDATED_AT;
            markScalarModified(SLOT_UPDATED_AT, BIT_UPDATED_AT, 8);
            return this;
        }

        // -------------------- Field #5: user (LazyUserProfile) --------------------

        @Override
        public boolean hasUser() {
            ensureScanned();
            return (presentMask & BIT_USER) != 0L;
        }

        public LazyUserProfile getUser() {
            if ((decodedMask & BIT_USER) == 0L) {
                ensureScanned();
                if (userView_ == null) {
                    userView_ = new LazyUserProfile();
                }
                int idx = slotTapeIdx[SLOT_USER];
                if (idx >= 0) {
                    if (tapeNextIdx(idx) != NO_NEXT_IDX) {
                        userView_.wrapMultiSegments(this, idx, BIT_USER);
                    } else {
                        userView_.wrap(buf, tapeValueOffset(idx), tapeValueLength(idx));
                        userView_.attachToParent(this, idx, BIT_USER);
                    }
                } else {
                    userView_.reset();
                    userView_.markScannedEmpty();
                    userView_.attachToParent(this, -1, BIT_USER);
                }
                decodedMask |= BIT_USER;
            }
            return userView_;
        }

        public LazyUserProfile getUserBuilder() {
            LazyUserProfile u = getUser();
            if (slotTapeIdx[SLOT_USER] < 0) {
                presentMask |= BIT_USER;
                appendedMask |= BIT_USER;
                varSizeDirtyMask |= BIT_USER;
                bubbleVarSizeDirtyToParent();
            }
            return u;
        }

        @Override
        public UserProfileOrBuilder getUserOrBuilder() {
            return getUser();
        }

        public LazyOrderEnvelope setUser(LazyUserProfile user) {
            ensureScanned();
            if (user == this.userView_) {
                this.presentMask |= BIT_USER;
                invalidateSizeCacheUpward();
                return this;
            }
            this.userView_ = user;
            this.decodedMask |= BIT_USER;
            this.presentMask |= BIT_USER;
            int idx = slotTapeIdx[SLOT_USER];
            if (idx >= 0) {
                user.attachToParent(this, idx, BIT_USER);
                // Check if restoring the original single-segment slice from this.buf
                if (tapeNextIdx(idx) == NO_NEXT_IDX
                        && !user.isMultiSegment()
                        && user.rawBuffer() == this.buf
                        && user.rawOffset() == tapeValueOffset(idx)
                        && user.rawLength() == tapeValueLength(idx)) {
                    if (user.isClean()) {
                        restoreCleanChild(idx, BIT_USER);
                        return this;
                    }
                    if (user.isSameSizeDirtyOnly()) {
                        setTapeFlags(idx, FLAG_DIRTY_SAME_SIZE);
                        this.varSizeDirtyMask &= ~BIT_USER;
                        this.appendedMask &= ~BIT_USER;
                        this.sameSizeDirtyMask |= BIT_USER;
                        invalidateSizeCacheUpward();
                        bubbleSameSizeDirtyToParent();
                        return this;
                    }
                }
                if (tapeNextIdx(idx) != NO_NEXT_IDX) {
                    int curr = idx;
                    while (curr != NO_NEXT_IDX) {
                        int next = tapeNextIdx(curr);
                        setTapeFlags(curr, next == NO_NEXT_IDX ? FLAG_DIRTY_VAR_SIZE : FLAG_SHADOWED);
                        curr = next;
                    }
                } else {
                    setTapeFlags(idx, FLAG_DIRTY_VAR_SIZE);
                }
            } else {
                user.attachToParent(this, -1, BIT_USER);
                this.appendedMask |= BIT_USER;
            }
            this.varSizeDirtyMask |= BIT_USER;
            bubbleVarSizeDirtyToParent();
            return this;
        }

        // -------------------- Field #6: repeated LineItem items --------------------

        public List<LazyLineItem> getItemsList() {
            ensureScanned();
            return itemsList_;
        }

        @Override
        public List<? extends LineItemOrBuilder> getItemsOrBuilderList() {
            ensureScanned();
            return itemsList_;
        }

        @Override
        public int getItemsCount() {
            return itemsList_.size();
        }

        public LazyLineItem getItems(int index) {
            return itemsList_.get(index);
        }

        @Override
        public LineItemOrBuilder getItemsOrBuilder(int index) {
            return itemsList_.get(index);
        }

        public LazyOrderEnvelope addItems(LazyLineItem item) {
            ensureScanned();
            itemsList_.addElement(item);
            presentMask |= BIT_ITEMS;
            appendedMask |= BIT_ITEMS;
            varSizeDirtyMask |= BIT_ITEMS;
            bubbleVarSizeDirtyToParent();
            return this;
        }

        // -------------------- Field #7: payload (bytes) --------------------

        @Override
        public ByteString getPayload() {
            if ((decodedMask & BIT_PAYLOAD) == 0L) {
                ensureScanned();
                int idx = slotTapeIdx[SLOT_PAYLOAD];
                if (idx >= 0) {
                    payload_ = UnsafeByteOperations.unsafeWrap(buf, tapeValueOffset(idx), tapeValueLength(idx));
                } else {
                    payload_ = ByteString.EMPTY;
                }
                decodedMask |= BIT_PAYLOAD;
            }
            return payload_;
        }

        public LazyOrderEnvelope setPayload(ByteString value) {
            ensureScanned();
            this.payload_ = value;
            this.payloadDirty_ = value.toByteArray();
            this.decodedMask |= BIT_PAYLOAD;
            this.presentMask |= BIT_PAYLOAD;
            markScalarModified(SLOT_PAYLOAD, BIT_PAYLOAD, payloadDirty_.length);
            return this;
        }

        public LazyOrderEnvelope setPayloadBytes(byte[] rawBytes) {
            ensureScanned();
            this.payload_ = UnsafeByteOperations.unsafeWrap(rawBytes);
            this.payloadDirty_ = rawBytes;
            this.decodedMask |= BIT_PAYLOAD;
            this.presentMask |= BIT_PAYLOAD;
            markScalarModified(SLOT_PAYLOAD, BIT_PAYLOAD, rawBytes.length);
            return this;
        }

        // -------------------- Field #8: auth_token (string) --------------------

        @Override
        public String getAuthToken() {
            if ((decodedMask & BIT_AUTH_TOKEN) == 0L) {
                ensureScanned();
                int idx = slotTapeIdx[SLOT_AUTH_TOKEN];
                authToken_ = idx >= 0 ? WireReader.readStringAt(buf, tapeValueOffset(idx), tapeValueLength(idx)) : "";
                decodedMask |= BIT_AUTH_TOKEN;
            }
            return authToken_;
        }

        public Utf8Slice getAuthTokenSlice() {
            ensureScanned();
            if (authTokenUtf8Dirty_ != null) return authTokenSlice_.wrap(authTokenUtf8Dirty_, 0, authTokenUtf8Dirty_.length);
            int idx = slotTapeIdx[SLOT_AUTH_TOKEN];
            return idx >= 0
                    ? authTokenSlice_.wrap(buf, tapeValueOffset(idx), tapeValueLength(idx))
                    : authTokenSlice_.wrap(buf, 0, 0);
        }

        @Override
        public ByteString getAuthTokenBytes() {
            return getAuthTokenSlice().toByteStringZeroCopy();
        }

        public LazyOrderEnvelope setAuthToken(String value) {
            ensureScanned();
            this.authToken_ = value;
            this.authTokenUtf8Dirty_ = value.getBytes(StandardCharsets.UTF_8);
            this.decodedMask |= BIT_AUTH_TOKEN;
            this.presentMask |= BIT_AUTH_TOKEN;
            markScalarModified(SLOT_AUTH_TOKEN, BIT_AUTH_TOKEN, authTokenUtf8Dirty_.length);
            return this;
        }

        public LazyOrderEnvelope clearAuthToken() {
            ensureScanned();
            this.authToken_ = "";
            this.authTokenUtf8Dirty_ = null;
            this.decodedMask |= BIT_AUTH_TOKEN;
            this.presentMask &= ~BIT_AUTH_TOKEN;
            boolean hadAppended = (this.appendedMask & BIT_AUTH_TOKEN) != 0L;
            this.appendedMask &= ~BIT_AUTH_TOKEN;
            int idx = slotTapeIdx[SLOT_AUTH_TOKEN];
            if (idx >= 0) {
                setTapeFlags(idx, FLAG_DELETED);
                slotTapeIdx[SLOT_AUTH_TOKEN] = -1;
                this.hasDeletedEntries = true;
                this.sameSizeDirtyMask &= ~BIT_AUTH_TOKEN;
                this.varSizeDirtyMask |= BIT_AUTH_TOKEN;
                bubbleVarSizeDirtyToParent();
            } else if (hadAppended) {
                this.varSizeDirtyMask &= ~BIT_AUTH_TOKEN;
                invalidateSizeCacheUpward();
                bubbleVarSizeDirtyToParent();
            }
            return this;
        }

        // -------------------- Field #9: trace_id (string) --------------------

        @Override
        public String getTraceId() {
            if ((decodedMask & BIT_TRACE_ID) == 0L) {
                ensureScanned();
                int idx = slotTapeIdx[SLOT_TRACE_ID];
                traceId_ = idx >= 0 ? WireReader.readStringAt(buf, tapeValueOffset(idx), tapeValueLength(idx)) : "";
                decodedMask |= BIT_TRACE_ID;
            }
            return traceId_;
        }

        public Utf8Slice getTraceIdSlice() {
            ensureScanned();
            if (traceIdUtf8Dirty_ != null) return traceIdSlice_.wrap(traceIdUtf8Dirty_, 0, traceIdUtf8Dirty_.length);
            int idx = slotTapeIdx[SLOT_TRACE_ID];
            return idx >= 0
                    ? traceIdSlice_.wrap(buf, tapeValueOffset(idx), tapeValueLength(idx))
                    : traceIdSlice_.wrap(buf, 0, 0);
        }

        @Override
        public ByteString getTraceIdBytes() {
            return getTraceIdSlice().toByteStringZeroCopy();
        }

        public LazyOrderEnvelope setTraceId(String value) {
            ensureScanned();
            this.traceId_ = value;
            this.traceIdUtf8Dirty_ = value.getBytes(StandardCharsets.UTF_8);
            this.decodedMask |= BIT_TRACE_ID;
            this.presentMask |= BIT_TRACE_ID;
            markScalarModified(SLOT_TRACE_ID, BIT_TRACE_ID, traceIdUtf8Dirty_.length);
            return this;
        }

        // -------------------- Field #10: total_cents (int64) --------------------

        @Override
        public long getTotalCents() {
            if ((decodedMask & BIT_TOTAL) == 0L) {
                ensureScanned();
                int idx = slotTapeIdx[SLOT_TOTAL];
                totalCents_ = idx >= 0 ? WireReader.readVarint64At(buf, tapeValueOffset(idx)) : 0L;
                decodedMask |= BIT_TOTAL;
            }
            return totalCents_;
        }

        public LazyOrderEnvelope setTotalCents(long value) {
            ensureScanned();
            this.totalCents_ = value;
            this.decodedMask |= BIT_TOTAL;
            this.presentMask |= BIT_TOTAL;
            markScalarModified(SLOT_TOTAL, BIT_TOTAL, WireWriter.varint64Size(value));
            return this;
        }

        private void markScalarModified(int slot, long bit, int newEncodedValueLen) {
            int idx = slotTapeIdx[slot];
            if (idx >= 0) {
                int oldLen = tapeValueLength(idx);
                if (oldLen == newEncodedValueLen && tapeFlags(idx) != FLAG_DIRTY_VAR_SIZE) {
                    setTapeFlags(idx, FLAG_DIRTY_SAME_SIZE);
                    this.sameSizeDirtyMask |= bit;
                    invalidateSizeCacheUpward();
                    bubbleSameSizeDirtyToParent();
                    return;
                }
                setTapeFlags(idx, FLAG_DIRTY_VAR_SIZE);
                this.varSizeDirtyMask |= bit;
            } else {
                this.appendedMask |= bit;
                this.varSizeDirtyMask |= bit;
            }
            bubbleVarSizeDirtyToParent();
        }

        // -------------------- Fast-Path 1 & 2/3 Serialization Hooks --------------------

        @Override
        public void applyInPlacePatches(byte[] target, int baseOffset) {
            long mask = sameSizeDirtyMask;
            while (mask != 0L) {
                int slot = Long.numberOfTrailingZeros(mask);
                if (slot == SLOT_ITEMS) {
                    itemsList_.applyInPlacePatches(target, baseOffset);
                } else {
                    int idx = slotTapeIdx[slot];
                    if (idx >= 0 && tapeFlags(idx) == FLAG_DIRTY_SAME_SIZE) {
                        int patchPos = tapeValueOffset(idx) - baseOffset;
                        switch (slot) {
                            case SLOT_ORDER_ID:
                                WireWriter.patchVarint64At(target, patchPos, orderId_);
                                break;
                            case SLOT_BUYER_NAME:
                                WireWriter.patchBytesAt(target, patchPos, buyerNameUtf8Dirty_, 0, buyerNameUtf8Dirty_.length);
                                break;
                            case SLOT_STATUS:
                                WireWriter.patchVarint32At(target, patchPos, status_);
                                break;
                            case SLOT_UPDATED_AT:
                                WireWriter.patchFixed64At(target, patchPos, updatedAtFixed64_);
                                break;
                            case SLOT_USER:
                                userView_.applyInPlacePatches(target, baseOffset);
                                break;
                            case SLOT_PAYLOAD:
                                WireWriter.patchBytesAt(target, patchPos, payloadDirty_, 0, payloadDirty_.length);
                                break;
                            case SLOT_AUTH_TOKEN:
                                WireWriter.patchBytesAt(target, patchPos, authTokenUtf8Dirty_, 0, authTokenUtf8Dirty_.length);
                                break;
                            case SLOT_TRACE_ID:
                                WireWriter.patchBytesAt(target, patchPos, traceIdUtf8Dirty_, 0, traceIdUtf8Dirty_.length);
                                break;
                            case SLOT_TOTAL:
                                WireWriter.patchVarint64At(target, patchPos, totalCents_);
                                break;
                        }
                    }
                }
                mask &= (mask - 1);
            }
        }

        @Override
        protected int computeDirtyEntrySize(int tapeIdx, int fieldNumber, int wireType) {
            switch (fieldNumber) {
                case 1: return 1 + WireWriter.varint64Size(orderId_);
                case 2: return 1 + WireWriter.varint32Size(buyerNameUtf8Dirty_.length) + buyerNameUtf8Dirty_.length;
                case 3: return 1 + WireWriter.varint32Size(status_);
                case 4: return 1 + 8;
                case 5: {
                    int subSize = userView_.getSerializedSize();
                    return 1 + WireWriter.varint32Size(subSize) + subSize;
                }
                case 6: {
                    LazyLineItem item = itemsList_.getLoadedElementByTapeIdx(tapeIdx);
                    int subSize = item.getSerializedSize();
                    return 1 + WireWriter.varint32Size(subSize) + subSize;
                }
                case 7: return 1 + WireWriter.varint32Size(payloadDirty_.length) + payloadDirty_.length;
                case 8: return 1 + WireWriter.varint32Size(authTokenUtf8Dirty_.length) + authTokenUtf8Dirty_.length;
                case 9: return 1 + WireWriter.varint32Size(traceIdUtf8Dirty_.length) + traceIdUtf8Dirty_.length;
                case 10: return 1 + WireWriter.varint64Size(totalCents_);
                default: throw new IllegalStateException("Unexpected dirty fieldNumber " + fieldNumber);
            }
        }

        @Override
        protected void writeDirtyEntry(int tapeIdx, int fieldNumber, int wireType, WireWriter writer) {
            switch (fieldNumber) {
                case 1:
                    writer.writeTag(1, WireFormat.WIRETYPE_VARINT);
                    writer.writeVarint64(orderId_);
                    break;
                case 2:
                    writer.writeLengthDelimitedBytes(2, buyerNameUtf8Dirty_, 0, buyerNameUtf8Dirty_.length);
                    break;
                case 3:
                    writer.writeTag(3, WireFormat.WIRETYPE_VARINT);
                    writer.writeVarint32(status_);
                    break;
                case 4:
                    writer.writeTag(4, WireFormat.WIRETYPE_FIXED64);
                    writer.writeFixed64(updatedAtFixed64_);
                    break;
                case 5: {
                    int subSize = userView_.getSerializedSize();
                    writer.writeTag(5, WireFormat.WIRETYPE_LENGTH_DELIMITED);
                    writer.writeVarint32(subSize);
                    userView_.writeSplicedTo(writer);
                    break;
                }
                case 6: {
                    LazyLineItem item = itemsList_.getLoadedElementByTapeIdx(tapeIdx);
                    int subSize = item.getSerializedSize();
                    writer.writeTag(6, WireFormat.WIRETYPE_LENGTH_DELIMITED);
                    writer.writeVarint32(subSize);
                    item.writeSplicedTo(writer);
                    break;
                }
                case 7:
                    writer.writeLengthDelimitedBytes(7, payloadDirty_, 0, payloadDirty_.length);
                    break;
                case 8:
                    writer.writeLengthDelimitedBytes(8, authTokenUtf8Dirty_, 0, authTokenUtf8Dirty_.length);
                    break;
                case 9:
                    writer.writeLengthDelimitedBytes(9, traceIdUtf8Dirty_, 0, traceIdUtf8Dirty_.length);
                    break;
                case 10:
                    writer.writeTag(10, WireFormat.WIRETYPE_VARINT);
                    writer.writeVarint64(totalCents_);
                    break;
            }
        }

        @Override
        protected int computeAppendedFieldsSize() {
            int size = 0;
            if ((appendedMask & BIT_ORDER_ID) != 0L && orderId_ != 0L) size += 1 + WireWriter.varint64Size(orderId_);
            if ((appendedMask & BIT_BUYER_NAME) != 0L && buyerNameUtf8Dirty_ != null && buyerNameUtf8Dirty_.length > 0) {
                size += 1 + WireWriter.varint32Size(buyerNameUtf8Dirty_.length) + buyerNameUtf8Dirty_.length;
            }
            if ((appendedMask & BIT_STATUS) != 0L && status_ != 0) size += 1 + WireWriter.varint32Size(status_);
            if ((appendedMask & BIT_UPDATED_AT) != 0L && updatedAtFixed64_ != 0L) size += 1 + 8;
            if ((appendedMask & BIT_USER) != 0L && userView_ != null) {
                int subSize = userView_.getSerializedSize();
                size += 1 + WireWriter.varint32Size(subSize) + subSize;
            }
            if ((appendedMask & BIT_ITEMS) != 0L) {
                for (int i = 0; i < itemsList_.appendedCount(); i++) {
                    int subSize = itemsList_.getAppended(i).getSerializedSize();
                    size += 1 + WireWriter.varint32Size(subSize) + subSize;
                }
            }
            if ((appendedMask & BIT_PAYLOAD) != 0L && payloadDirty_ != null && payloadDirty_.length > 0) {
                size += 1 + WireWriter.varint32Size(payloadDirty_.length) + payloadDirty_.length;
            }
            if ((appendedMask & BIT_AUTH_TOKEN) != 0L && authTokenUtf8Dirty_ != null && authTokenUtf8Dirty_.length > 0) {
                size += 1 + WireWriter.varint32Size(authTokenUtf8Dirty_.length) + authTokenUtf8Dirty_.length;
            }
            if ((appendedMask & BIT_TRACE_ID) != 0L && traceIdUtf8Dirty_ != null && traceIdUtf8Dirty_.length > 0) {
                size += 1 + WireWriter.varint32Size(traceIdUtf8Dirty_.length) + traceIdUtf8Dirty_.length;
            }
            if ((appendedMask & BIT_TOTAL) != 0L && totalCents_ != 0L) size += 1 + WireWriter.varint64Size(totalCents_);
            return size;
        }

        @Override
        protected void writeAppendedFields(WireWriter writer) {
            if ((appendedMask & BIT_ORDER_ID) != 0L && orderId_ != 0L) {
                writer.writeTag(1, WireFormat.WIRETYPE_VARINT);
                writer.writeVarint64(orderId_);
            }
            if ((appendedMask & BIT_BUYER_NAME) != 0L && buyerNameUtf8Dirty_ != null && buyerNameUtf8Dirty_.length > 0) {
                writer.writeLengthDelimitedBytes(2, buyerNameUtf8Dirty_, 0, buyerNameUtf8Dirty_.length);
            }
            if ((appendedMask & BIT_STATUS) != 0L && status_ != 0) {
                writer.writeTag(3, WireFormat.WIRETYPE_VARINT);
                writer.writeVarint32(status_);
            }
            if ((appendedMask & BIT_UPDATED_AT) != 0L && updatedAtFixed64_ != 0L) {
                writer.writeTag(4, WireFormat.WIRETYPE_FIXED64);
                writer.writeFixed64(updatedAtFixed64_);
            }
            if ((appendedMask & BIT_USER) != 0L && userView_ != null) {
                int subSize = userView_.getSerializedSize();
                writer.writeTag(5, WireFormat.WIRETYPE_LENGTH_DELIMITED);
                writer.writeVarint32(subSize);
                userView_.writeSplicedTo(writer);
            }
            if ((appendedMask & BIT_ITEMS) != 0L) {
                for (int i = 0; i < itemsList_.appendedCount(); i++) {
                    LazyLineItem item = itemsList_.getAppended(i);
                    int subSize = item.getSerializedSize();
                    writer.writeTag(6, WireFormat.WIRETYPE_LENGTH_DELIMITED);
                    writer.writeVarint32(subSize);
                    item.writeSplicedTo(writer);
                }
            }
            if ((appendedMask & BIT_PAYLOAD) != 0L && payloadDirty_ != null && payloadDirty_.length > 0) {
                writer.writeLengthDelimitedBytes(7, payloadDirty_, 0, payloadDirty_.length);
            }
            if ((appendedMask & BIT_AUTH_TOKEN) != 0L && authTokenUtf8Dirty_ != null && authTokenUtf8Dirty_.length > 0) {
                writer.writeLengthDelimitedBytes(8, authTokenUtf8Dirty_, 0, authTokenUtf8Dirty_.length);
            }
            if ((appendedMask & BIT_TRACE_ID) != 0L && traceIdUtf8Dirty_ != null && traceIdUtf8Dirty_.length > 0) {
                writer.writeLengthDelimitedBytes(9, traceIdUtf8Dirty_, 0, traceIdUtf8Dirty_.length);
            }
            if ((appendedMask & BIT_TOTAL) != 0L && totalCents_ != 0L) {
                writer.writeTag(10, WireFormat.WIRETYPE_VARINT);
                writer.writeVarint64(totalCents_);
            }
        }

        @Override
        public LazyOrderEnvelope copy() {
            LazyOrderEnvelope c = new LazyOrderEnvelope();
            c.copyFrom(this);
            return c;
        }

        public LazyOrderEnvelope copyFrom(LazyOrderEnvelope src) {
            copyBaseFrom(src);
            System.arraycopy(src.slotTapeIdx, 0, this.slotTapeIdx, 0, slotTapeIdx.length);
            this.userTailTapeIdx = src.userTailTapeIdx;
            this.orderId_ = src.orderId_;
            this.buyerName_ = src.buyerName_;
            this.buyerNameUtf8Dirty_ = src.buyerNameUtf8Dirty_;
            this.status_ = src.status_;
            this.updatedAtFixed64_ = src.updatedAtFixed64_;

            boolean copyUserView = (src.decodedMask & BIT_USER) != 0L && src.userView_ != null
                    && (!src.userView_.isClean()
                        || (src.varSizeDirtyMask & BIT_USER) != 0L
                        || (src.sameSizeDirtyMask & BIT_USER) != 0L
                        || (src.appendedMask & BIT_USER) != 0L);
            if (copyUserView) {
                if (this.userView_ == null) this.userView_ = new LazyUserProfile();
                this.userView_.copyFrom(src.userView_);
                this.userView_.attachToParent(this, this.slotTapeIdx[SLOT_USER], BIT_USER);
            } else {
                this.decodedMask &= ~BIT_USER;
            }
            this.itemsList_.reset();
            this.itemsList_.copyFrom(src.itemsList_, LazyLineItem::copy);
            this.payload_ = src.payload_;
            this.payloadDirty_ = src.payloadDirty_;
            this.authToken_ = src.authToken_;
            this.authTokenUtf8Dirty_ = src.authTokenUtf8Dirty_;
            this.traceId_ = src.traceId_;
            this.traceIdUtf8Dirty_ = src.traceIdUtf8Dirty_;
            this.totalCents_ = src.totalCents_;
            return this;
        }

        @Override public Message getDefaultInstanceForType() { return new LazyOrderEnvelope(); }
        @Override public Builder newBuilderForType() { return newBuilder(); }
        @Override public Builder toBuilder() { return new Builder(this.copy()); }

        public static final class Builder extends AbstractLazyBuilder<LazyOrderEnvelope, Builder>
                implements OrderEnvelopeOrBuilder {

            private Builder(LazyOrderEnvelope initialImpl) {
                super(initialImpl);
            }

            @Override public long getOrderId() { return impl.getOrderId(); }
            public Builder setOrderId(long v) { ensureMutable(); impl.setOrderId(v); return this; }

            @Override public String getBuyerName() { return impl.getBuyerName(); }
            @Override public ByteString getBuyerNameBytes() { return impl.getBuyerNameBytes(); }
            public Builder setBuyerName(String v) { ensureMutable(); impl.setBuyerName(v); return this; }

            @Override public int getStatus() { return impl.getStatus(); }
            public Builder setStatus(int v) { ensureMutable(); impl.setStatus(v); return this; }

            @Override public long getUpdatedAtFixed64() { return impl.getUpdatedAtFixed64(); }
            public Builder setUpdatedAtFixed64(long v) { ensureMutable(); impl.setUpdatedAtFixed64(v); return this; }

            @Override public boolean hasUser() { return impl.hasUser(); }
            public LazyUserProfile getUser() { return impl.getUser(); }
            @Override public UserProfileOrBuilder getUserOrBuilder() { return impl.getUserOrBuilder(); }

            public LazyUserProfile.Builder getUserBuilder() {
                ensureMutable();
                return new LazyUserProfile.Builder(impl.getUserBuilder(), this);
            }

            public Builder setUser(LazyUserProfile v) {
                ensureMutable();
                impl.setUser(v);
                return this;
            }

            public Builder setUser(LazyUserProfile.Builder b) {
                return setUser(b.build());
            }

            public List<LazyLineItem> getItemsList() { return impl.getItemsList(); }
            @Override public List<? extends LineItemOrBuilder> getItemsOrBuilderList() { return impl.getItemsOrBuilderList(); }
            @Override public int getItemsCount() { return impl.getItemsCount(); }
            public LazyLineItem getItems(int index) { return impl.getItems(index); }
            @Override public LineItemOrBuilder getItemsOrBuilder(int index) { return impl.getItemsOrBuilder(index); }

            public LazyLineItem getItemsBuilder(int index) {
                ensureMutable();
                return impl.getItems(index);
            }

            public Builder addItems(LazyLineItem item) {
                ensureMutable();
                impl.addItems(item);
                return this;
            }

            public Builder addItems(LazyLineItem.Builder itemBuilder) {
                return addItems(itemBuilder.build());
            }

            @Override public ByteString getPayload() { return impl.getPayload(); }
            public Builder setPayload(ByteString v) { ensureMutable(); impl.setPayload(v); return this; }
            public Builder setPayloadBytes(byte[] v) { ensureMutable(); impl.setPayloadBytes(v); return this; }

            @Override public String getAuthToken() { return impl.getAuthToken(); }
            @Override public ByteString getAuthTokenBytes() { return impl.getAuthTokenBytes(); }
            public Builder setAuthToken(String v) { ensureMutable(); impl.setAuthToken(v); return this; }
            public Builder clearAuthToken() { ensureMutable(); impl.clearAuthToken(); return this; }

            @Override public String getTraceId() { return impl.getTraceId(); }
            @Override public ByteString getTraceIdBytes() { return impl.getTraceIdBytes(); }
            public Builder setTraceId(String v) { ensureMutable(); impl.setTraceId(v); return this; }

            @Override public long getTotalCents() { return impl.getTotalCents(); }
            public Builder setTotalCents(long v) { ensureMutable(); impl.setTotalCents(v); return this; }

            @Override
            public Builder clone() {
                return new Builder(impl.copy());
            }
        }
    }
}
