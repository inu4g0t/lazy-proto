package io.lazyproto.model;

import com.google.protobuf.ByteString;
import com.google.protobuf.CodedInputStream;
import com.google.protobuf.CodedOutputStream;
import com.google.protobuf.Descriptors;
import com.google.protobuf.ExtensionRegistryLite;
import com.google.protobuf.Message;
import com.google.protobuf.Parser;
import com.google.protobuf.UnknownFieldSet;
import io.lazyproto.model.OrderInterfaces.LineItemOrBuilder;
import io.lazyproto.model.OrderInterfaces.OrderEnvelopeOrBuilder;
import io.lazyproto.model.OrderInterfaces.UserProfileOrBuilder;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Standard Google Protobuf (Eager Deserialization + Immutable Message/Builder + Two-Pass Full Serialization +
 * UnknownFieldSet + Full equals/hashCode) implementation of OrderEnvelope, UserProfile, and LineItem.
 *
 * <p>Follows the exact wire parsing, object allocation, and serialization lifecycle produced by standard {@code protoc}.
 */
public final class StandardOrderProto {
    private StandardOrderProto() {}

    // ==================== 1. UserProfile ====================

    public static final class UserProfile extends AbstractStandardMessage implements UserProfileOrBuilder {
        private final long userId;
        private final String city;
        private final int vipLevel;
        private final double score;
        private final String email;
        private final UnknownFieldSet unknownFields;
        private int memoizedSize = -1;

        private UserProfile(Builder b) {
            this.userId = b.userId;
            this.city = b.city;
            this.vipLevel = b.vipLevel;
            this.score = b.score;
            this.email = b.email;
            this.unknownFields = b.unknownFields.build();
        }

        public static UserProfile getDefaultInstance() {
            return DEFAULT_INSTANCE;
        }

        private static final UserProfile DEFAULT_INSTANCE = new Builder().build();

        public static Builder newBuilder() {
            return new Builder();
        }

        public static UserProfile parseFrom(byte[] data) throws IOException {
            return newBuilder().mergeFrom(CodedInputStream.newInstance(data)).build();
        }

        @Override public long getUserId() { return userId; }
        @Override public String getCity() { return city; }
        @Override public ByteString getCityBytes() { return ByteString.copyFromUtf8(city); }
        @Override public int getVipLevel() { return vipLevel; }
        @Override public double getScore() { return score; }
        @Override public String getEmail() { return email; }
        @Override public ByteString getEmailBytes() { return ByteString.copyFromUtf8(email); }
        @Override public UnknownFieldSet getUnknownFields() { return unknownFields; }

        @Override
        public int getSerializedSize() {
            int size = memoizedSize;
            if (size != -1) return size;
            size = 0;
            if (userId != 0L) size += CodedOutputStream.computeInt64Size(1, userId);
            if (!city.isEmpty()) size += CodedOutputStream.computeStringSize(2, city);
            if (vipLevel != 0) size += CodedOutputStream.computeInt32Size(3, vipLevel);
            if (Double.doubleToRawLongBits(score) != 0L) size += CodedOutputStream.computeDoubleSize(4, score);
            if (!email.isEmpty()) size += CodedOutputStream.computeStringSize(5, email);
            size += unknownFields.getSerializedSize();
            memoizedSize = size;
            return size;
        }

        @Override
        public void writeTo(CodedOutputStream out) throws IOException {
            if (userId != 0L) out.writeInt64(1, userId);
            if (!city.isEmpty()) out.writeString(2, city);
            if (vipLevel != 0) out.writeInt32(3, vipLevel);
            if (Double.doubleToRawLongBits(score) != 0L) out.writeDouble(4, score);
            if (!email.isEmpty()) out.writeString(5, email);
            unknownFields.writeTo(out);
        }

        @Override
        public Builder toBuilder() {
            return new Builder().mergeFrom(this);
        }

        @Override
        public Builder newBuilderForType() {
            return new Builder();
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof UserProfile that)) return false;
            return userId == that.userId
                    && vipLevel == that.vipLevel
                    && Double.doubleToLongBits(score) == Double.doubleToLongBits(that.score)
                    && Objects.equals(city, that.city)
                    && Objects.equals(email, that.email)
                    && Objects.equals(unknownFields, that.unknownFields);
        }

        @Override
        public int hashCode() {
            return Objects.hash(userId, city, vipLevel, score, email, unknownFields);
        }

        public static final class Builder extends AbstractStandardBuilder<Builder> implements UserProfileOrBuilder {
            private long userId;
            private String city = "";
            private int vipLevel;
            private double score;
            private String email = "";
            private UnknownFieldSet.Builder unknownFields = UnknownFieldSet.newBuilder();

            @Override public long getUserId() { return userId; }
            public Builder setUserId(long v) { this.userId = v; return this; }
            @Override public String getCity() { return city; }
            @Override public ByteString getCityBytes() { return ByteString.copyFromUtf8(city); }
            public Builder setCity(String v) { this.city = Objects.requireNonNull(v); return this; }
            @Override public int getVipLevel() { return vipLevel; }
            public Builder setVipLevel(int v) { this.vipLevel = v; return this; }
            @Override public double getScore() { return score; }
            public Builder setScore(double v) { this.score = v; return this; }
            @Override public String getEmail() { return email; }
            @Override public ByteString getEmailBytes() { return ByteString.copyFromUtf8(email); }
            public Builder setEmail(String v) { this.email = Objects.requireNonNull(v); return this; }

            public Builder mergeFrom(UserProfile other) {
                if (other.userId != 0L) this.userId = other.userId;
                if (!other.city.isEmpty()) this.city = other.city;
                if (other.vipLevel != 0) this.vipLevel = other.vipLevel;
                if (Double.doubleToRawLongBits(other.score) != 0L) this.score = other.score;
                if (!other.email.isEmpty()) this.email = other.email;
                this.unknownFields.mergeFrom(other.unknownFields);
                return this;
            }

            @Override
            public Builder mergeFrom(CodedInputStream input, ExtensionRegistryLite reg) throws IOException {
                while (true) {
                    int tag = input.readTag();
                    switch (tag) {
                        case 0: return this;
                        case 8: this.userId = input.readInt64(); break;
                        case 18: this.city = input.readStringRequireUtf8(); break;
                        case 24: this.vipLevel = input.readInt32(); break;
                        case 33: this.score = input.readDouble(); break;
                        case 42: this.email = input.readStringRequireUtf8(); break;
                        default:
                            if (!unknownFields.mergeFieldFrom(tag, input)) return this;
                            break;
                    }
                }
            }

            @Override
            public UserProfile build() {
                return new UserProfile(this);
            }
        }
    }

    // ==================== 2. LineItem ====================

    public static final class LineItem extends AbstractStandardMessage implements LineItemOrBuilder {
        private final long itemId;
        private final String sku;
        private final int quantity;
        private final long priceCents;
        private final String description;
        private final UnknownFieldSet unknownFields;
        private int memoizedSize = -1;

        private LineItem(Builder b) {
            this.itemId = b.itemId;
            this.sku = b.sku;
            this.quantity = b.quantity;
            this.priceCents = b.priceCents;
            this.description = b.description;
            this.unknownFields = b.unknownFields.build();
        }

        public static Builder newBuilder() {
            return new Builder();
        }

        public static LineItem parseFrom(byte[] data) throws IOException {
            return newBuilder().mergeFrom(CodedInputStream.newInstance(data)).build();
        }

        @Override public long getItemId() { return itemId; }
        @Override public String getSku() { return sku; }
        @Override public ByteString getSkuBytes() { return ByteString.copyFromUtf8(sku); }
        @Override public int getQuantity() { return quantity; }
        @Override public long getPriceCents() { return priceCents; }
        @Override public String getDescription() { return description; }
        @Override public ByteString getDescriptionBytes() { return ByteString.copyFromUtf8(description); }
        @Override public UnknownFieldSet getUnknownFields() { return unknownFields; }

        @Override
        public int getSerializedSize() {
            int size = memoizedSize;
            if (size != -1) return size;
            size = 0;
            if (itemId != 0L) size += CodedOutputStream.computeInt64Size(1, itemId);
            if (!sku.isEmpty()) size += CodedOutputStream.computeStringSize(2, sku);
            if (quantity != 0) size += CodedOutputStream.computeInt32Size(3, quantity);
            if (priceCents != 0L) size += CodedOutputStream.computeInt64Size(4, priceCents);
            if (!description.isEmpty()) size += CodedOutputStream.computeStringSize(5, description);
            size += unknownFields.getSerializedSize();
            memoizedSize = size;
            return size;
        }

        @Override
        public void writeTo(CodedOutputStream out) throws IOException {
            if (itemId != 0L) out.writeInt64(1, itemId);
            if (!sku.isEmpty()) out.writeString(2, sku);
            if (quantity != 0) out.writeInt32(3, quantity);
            if (priceCents != 0L) out.writeInt64(4, priceCents);
            if (!description.isEmpty()) out.writeString(5, description);
            unknownFields.writeTo(out);
        }

        @Override public Builder toBuilder() { return new Builder().mergeFrom(this); }
        @Override public Builder newBuilderForType() { return new Builder(); }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof LineItem that)) return false;
            return itemId == that.itemId
                    && quantity == that.quantity
                    && priceCents == that.priceCents
                    && Objects.equals(sku, that.sku)
                    && Objects.equals(description, that.description)
                    && Objects.equals(unknownFields, that.unknownFields);
        }

        @Override
        public int hashCode() {
            return Objects.hash(itemId, sku, quantity, priceCents, description, unknownFields);
        }

        public static final class Builder extends AbstractStandardBuilder<Builder> implements LineItemOrBuilder {
            private long itemId;
            private String sku = "";
            private int quantity;
            private long priceCents;
            private String description = "";
            private UnknownFieldSet.Builder unknownFields = UnknownFieldSet.newBuilder();

            @Override public long getItemId() { return itemId; }
            public Builder setItemId(long v) { this.itemId = v; return this; }
            @Override public String getSku() { return sku; }
            @Override public ByteString getSkuBytes() { return ByteString.copyFromUtf8(sku); }
            public Builder setSku(String v) { this.sku = Objects.requireNonNull(v); return this; }
            @Override public int getQuantity() { return quantity; }
            public Builder setQuantity(int v) { this.quantity = v; return this; }
            @Override public long getPriceCents() { return priceCents; }
            public Builder setPriceCents(long v) { this.priceCents = v; return this; }
            @Override public String getDescription() { return description; }
            @Override public ByteString getDescriptionBytes() { return ByteString.copyFromUtf8(description); }
            public Builder setDescription(String v) { this.description = Objects.requireNonNull(v); return this; }

            public Builder mergeFrom(LineItem other) {
                if (other.itemId != 0L) this.itemId = other.itemId;
                if (!other.sku.isEmpty()) this.sku = other.sku;
                if (other.quantity != 0) this.quantity = other.quantity;
                if (other.priceCents != 0L) this.priceCents = other.priceCents;
                if (!other.description.isEmpty()) this.description = other.description;
                this.unknownFields.mergeFrom(other.unknownFields);
                return this;
            }

            @Override
            public Builder mergeFrom(CodedInputStream input, ExtensionRegistryLite reg) throws IOException {
                while (true) {
                    int tag = input.readTag();
                    switch (tag) {
                        case 0: return this;
                        case 8: this.itemId = input.readInt64(); break;
                        case 18: this.sku = input.readStringRequireUtf8(); break;
                        case 24: this.quantity = input.readInt32(); break;
                        case 32: this.priceCents = input.readInt64(); break;
                        case 42: this.description = input.readStringRequireUtf8(); break;
                        default:
                            if (!unknownFields.mergeFieldFrom(tag, input)) return this;
                            break;
                    }
                }
            }

            @Override
            public LineItem build() {
                return new LineItem(this);
            }
        }
    }

    // ==================== 3. OrderEnvelope ====================

    public static final class OrderEnvelope extends AbstractStandardMessage implements OrderEnvelopeOrBuilder {
        private final long orderId;
        private final String buyerName;
        private final int status;
        private final long updatedAtFixed64;
        private final UserProfile user;
        private final List<LineItem> items;
        private final ByteString payload;
        private final String authToken;
        private final String traceId;
        private final long totalCents;
        private final UnknownFieldSet unknownFields;
        private int memoizedSize = -1;

        private OrderEnvelope(Builder b) {
            this.orderId = b.orderId;
            this.buyerName = b.buyerName;
            this.status = b.status;
            this.updatedAtFixed64 = b.updatedAtFixed64;
            this.user = b.userBuilder != null ? b.userBuilder.build() : b.user;
            this.items = Collections.unmodifiableList(new ArrayList<>(b.items));
            this.payload = b.payload;
            this.authToken = b.authToken;
            this.traceId = b.traceId;
            this.totalCents = b.totalCents;
            this.unknownFields = b.unknownFields.build();
        }

        public static Builder newBuilder() {
            return new Builder();
        }

        public static OrderEnvelope parseFrom(byte[] data) throws IOException {
            return newBuilder().mergeFrom(CodedInputStream.newInstance(data)).build();
        }

        @Override public long getOrderId() { return orderId; }
        @Override public String getBuyerName() { return buyerName; }
        @Override public ByteString getBuyerNameBytes() { return ByteString.copyFromUtf8(buyerName); }
        @Override public int getStatus() { return status; }
        @Override public long getUpdatedAtFixed64() { return updatedAtFixed64; }
        @Override public boolean hasUser() { return user != null; }
        public UserProfile getUser() { return user != null ? user : UserProfile.getDefaultInstance(); }
        @Override public UserProfileOrBuilder getUserOrBuilder() { return getUser(); }
        public List<LineItem> getItemsList() { return items; }
        @Override public List<? extends LineItemOrBuilder> getItemsOrBuilderList() { return items; }
        @Override public int getItemsCount() { return items.size(); }
        public LineItem getItems(int index) { return items.get(index); }
        @Override public LineItemOrBuilder getItemsOrBuilder(int index) { return items.get(index); }
        @Override public ByteString getPayload() { return payload; }
        @Override public String getAuthToken() { return authToken; }
        @Override public ByteString getAuthTokenBytes() { return ByteString.copyFromUtf8(authToken); }
        @Override public String getTraceId() { return traceId; }
        @Override public ByteString getTraceIdBytes() { return ByteString.copyFromUtf8(traceId); }
        @Override public long getTotalCents() { return totalCents; }
        @Override public UnknownFieldSet getUnknownFields() { return unknownFields; }

        @Override
        public int getSerializedSize() {
            int size = memoizedSize;
            if (size != -1) return size;
            size = 0;
            if (orderId != 0L) size += CodedOutputStream.computeInt64Size(1, orderId);
            if (!buyerName.isEmpty()) size += CodedOutputStream.computeStringSize(2, buyerName);
            if (status != 0) size += CodedOutputStream.computeInt32Size(3, status);
            if (updatedAtFixed64 != 0L) size += CodedOutputStream.computeFixed64Size(4, updatedAtFixed64);
            if (user != null) size += CodedOutputStream.computeMessageSize(5, user);
            for (int i = 0; i < items.size(); i++) {
                size += CodedOutputStream.computeMessageSize(6, items.get(i));
            }
            if (!payload.isEmpty()) size += CodedOutputStream.computeBytesSize(7, payload);
            if (!authToken.isEmpty()) size += CodedOutputStream.computeStringSize(8, authToken);
            if (!traceId.isEmpty()) size += CodedOutputStream.computeStringSize(9, traceId);
            if (totalCents != 0L) size += CodedOutputStream.computeInt64Size(10, totalCents);
            size += unknownFields.getSerializedSize();
            memoizedSize = size;
            return size;
        }

        @Override
        public void writeTo(CodedOutputStream out) throws IOException {
            if (orderId != 0L) out.writeInt64(1, orderId);
            if (!buyerName.isEmpty()) out.writeString(2, buyerName);
            if (status != 0) out.writeInt32(3, status);
            if (updatedAtFixed64 != 0L) out.writeFixed64(4, updatedAtFixed64);
            if (user != null) out.writeMessage(5, user);
            for (int i = 0; i < items.size(); i++) {
                out.writeMessage(6, items.get(i));
            }
            if (!payload.isEmpty()) out.writeBytes(7, payload);
            if (!authToken.isEmpty()) out.writeString(8, authToken);
            if (!traceId.isEmpty()) out.writeString(9, traceId);
            if (totalCents != 0L) out.writeInt64(10, totalCents);
            unknownFields.writeTo(out);
        }

        @Override public Builder toBuilder() { return new Builder().mergeFrom(this); }
        @Override public Builder newBuilderForType() { return new Builder(); }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof OrderEnvelope that)) return false;
            return orderId == that.orderId
                    && status == that.status
                    && updatedAtFixed64 == that.updatedAtFixed64
                    && totalCents == that.totalCents
                    && Objects.equals(buyerName, that.buyerName)
                    && Objects.equals(getUser(), that.getUser())
                    && Objects.equals(items, that.items)
                    && Objects.equals(payload, that.payload)
                    && Objects.equals(authToken, that.authToken)
                    && Objects.equals(traceId, that.traceId)
                    && Objects.equals(unknownFields, that.unknownFields);
        }

        @Override
        public int hashCode() {
            return Objects.hash(orderId, buyerName, status, updatedAtFixed64, getUser(), items, payload, authToken, traceId, totalCents, unknownFields);
        }

        public static final class Builder extends AbstractStandardBuilder<Builder> implements OrderEnvelopeOrBuilder {
            private long orderId;
            private String buyerName = "";
            private int status;
            private long updatedAtFixed64;
            private UserProfile user;
            private UserProfile.Builder userBuilder;
            private List<LineItem> items = new ArrayList<>();
            private ByteString payload = ByteString.EMPTY;
            private String authToken = "";
            private String traceId = "";
            private long totalCents;
            private UnknownFieldSet.Builder unknownFields = UnknownFieldSet.newBuilder();

            @Override public long getOrderId() { return orderId; }
            public Builder setOrderId(long v) { this.orderId = v; return this; }
            @Override public String getBuyerName() { return buyerName; }
            @Override public ByteString getBuyerNameBytes() { return ByteString.copyFromUtf8(buyerName); }
            public Builder setBuyerName(String v) { this.buyerName = Objects.requireNonNull(v); return this; }
            @Override public int getStatus() { return status; }
            public Builder setStatus(int v) { this.status = v; return this; }
            @Override public long getUpdatedAtFixed64() { return updatedAtFixed64; }
            public Builder setUpdatedAtFixed64(long v) { this.updatedAtFixed64 = v; return this; }
            @Override public boolean hasUser() { return user != null || userBuilder != null; }
            public UserProfile getUser() {
                if (userBuilder != null) return userBuilder.build();
                return user != null ? user : UserProfile.getDefaultInstance();
            }
            @Override public UserProfileOrBuilder getUserOrBuilder() {
                if (userBuilder != null) return userBuilder;
                return getUser();
            }
            public Builder setUser(UserProfile v) {
                this.user = Objects.requireNonNull(v);
                this.userBuilder = null;
                return this;
            }
            public UserProfile.Builder getUserBuilder() {
                if (userBuilder == null) {
                    userBuilder = user != null ? user.toBuilder() : UserProfile.newBuilder();
                    user = null;
                }
                return userBuilder;
            }
            public List<LineItem> getItemsList() { return Collections.unmodifiableList(items); }
            @Override public List<? extends LineItemOrBuilder> getItemsOrBuilderList() { return getItemsList(); }
            @Override public int getItemsCount() { return items.size(); }
            public LineItem getItems(int index) { return items.get(index); }
            @Override public LineItemOrBuilder getItemsOrBuilder(int index) { return items.get(index); }
            public Builder setItems(int index, LineItem item) { items.set(index, Objects.requireNonNull(item)); return this; }
            public Builder addItems(LineItem item) { items.add(Objects.requireNonNull(item)); return this; }
            @Override public ByteString getPayload() { return payload; }
            public Builder setPayload(ByteString v) { this.payload = Objects.requireNonNull(v); return this; }
            @Override public String getAuthToken() { return authToken; }
            @Override public ByteString getAuthTokenBytes() { return ByteString.copyFromUtf8(authToken); }
            public Builder setAuthToken(String v) { this.authToken = Objects.requireNonNull(v); return this; }
            public Builder clearAuthToken() { this.authToken = ""; return this; }
            @Override public String getTraceId() { return traceId; }
            @Override public ByteString getTraceIdBytes() { return ByteString.copyFromUtf8(traceId); }
            public Builder setTraceId(String v) { this.traceId = Objects.requireNonNull(v); return this; }
            @Override public long getTotalCents() { return totalCents; }
            public Builder setTotalCents(long v) { this.totalCents = v; return this; }
            public UnknownFieldSet.Builder getUnknownFieldsBuilder() { return unknownFields; }

            public Builder mergeFrom(OrderEnvelope other) {
                if (other.orderId != 0L) this.orderId = other.orderId;
                if (!other.buyerName.isEmpty()) this.buyerName = other.buyerName;
                if (other.status != 0) this.status = other.status;
                if (other.updatedAtFixed64 != 0L) this.updatedAtFixed64 = other.updatedAtFixed64;
                if (other.user != null) {
                    if (this.user != null) {
                        this.user = this.user.toBuilder().mergeFrom(other.user).build();
                    } else {
                        this.user = other.user;
                    }
                }
                if (!other.items.isEmpty()) {
                    this.items.addAll(other.items);
                }
                if (!other.payload.isEmpty()) this.payload = other.payload;
                if (!other.authToken.isEmpty()) this.authToken = other.authToken;
                if (!other.traceId.isEmpty()) this.traceId = other.traceId;
                if (other.totalCents != 0L) this.totalCents = other.totalCents;
                this.unknownFields.mergeFrom(other.unknownFields);
                return this;
            }

            @Override
            public Builder mergeFrom(CodedInputStream input, ExtensionRegistryLite reg) throws IOException {
                while (true) {
                    int tag = input.readTag();
                    switch (tag) {
                        case 0: return this;
                        case 8: this.orderId = input.readInt64(); break;
                        case 18: this.buyerName = input.readStringRequireUtf8(); break;
                        case 24: this.status = input.readInt32(); break;
                        case 33: this.updatedAtFixed64 = input.readFixed64(); break;
                        case 42: {
                            UserProfile.Builder sub = user != null ? user.toBuilder() : UserProfile.newBuilder();
                            input.readMessage(sub, reg);
                            user = sub.build();
                            break;
                        }
                        case 50: {
                            LineItem.Builder item = LineItem.newBuilder();
                            input.readMessage(item, reg);
                            items.add(item.build());
                            break;
                        }
                        case 58: this.payload = input.readBytes(); break;
                        case 66: this.authToken = input.readStringRequireUtf8(); break;
                        case 74: this.traceId = input.readStringRequireUtf8(); break;
                        case 80: this.totalCents = input.readInt64(); break;
                        default:
                            if (!unknownFields.mergeFieldFrom(tag, input)) return this;
                            break;
                    }
                }
            }

            @Override
            public OrderEnvelope build() {
                return new OrderEnvelope(this);
            }
        }
    }

    // ==================== Base Boilerplate for Standard Eager Message/Builder ====================

    public abstract static class AbstractStandardMessage implements Message {
        @Override
        public byte[] toByteArray() {
            byte[] out = new byte[getSerializedSize()];
            CodedOutputStream coded = CodedOutputStream.newInstance(out);
            try {
                writeTo(coded);
                coded.checkNoSpaceLeft();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
            return out;
        }

        @Override
        public ByteString toByteString() {
            return ByteString.copyFrom(toByteArray());
        }

        @Override
        public void writeTo(OutputStream output) throws IOException {
            output.write(toByteArray());
        }

        @Override
        public void writeDelimitedTo(OutputStream output) throws IOException {
            byte[] b = toByteArray();
            CodedOutputStream coded = CodedOutputStream.newInstance(output);
            coded.writeUInt32NoTag(b.length);
            coded.flush();
            output.write(b);
        }

        @Override public boolean isInitialized() { return true; }
        @Override public Message getDefaultInstanceForType() { return this; }
        @Override public Parser<? extends Message> getParserForType() { throw new UnsupportedOperationException(); }
        @Override public Descriptors.Descriptor getDescriptorForType() { throw new UnsupportedOperationException(); }
        @Override public Map<Descriptors.FieldDescriptor, Object> getAllFields() { throw new UnsupportedOperationException(); }
        @Override public boolean hasOneof(Descriptors.OneofDescriptor oneof) { return false; }
        @Override public Descriptors.FieldDescriptor getOneofFieldDescriptor(Descriptors.OneofDescriptor oneof) { return null; }
        @Override public boolean hasField(Descriptors.FieldDescriptor field) { throw new UnsupportedOperationException(); }
        @Override public Object getField(Descriptors.FieldDescriptor field) { throw new UnsupportedOperationException(); }
        @Override public int getRepeatedFieldCount(Descriptors.FieldDescriptor field) { throw new UnsupportedOperationException(); }
        @Override public Object getRepeatedField(Descriptors.FieldDescriptor field, int index) { throw new UnsupportedOperationException(); }
        @Override public List<String> findInitializationErrors() { return Collections.emptyList(); }
        @Override public String getInitializationErrorString() { return ""; }
    }

    public abstract static class AbstractStandardBuilder<B extends AbstractStandardBuilder<B>> implements Message.Builder {
        @Override public B clear() { return (B) this; }
        @Override public Message buildPartial() { return build(); }
        @Override public B clone() { throw new UnsupportedOperationException(); }
        @Override public abstract B mergeFrom(CodedInputStream input, ExtensionRegistryLite reg) throws IOException;
        @Override public B mergeFrom(CodedInputStream input) throws IOException { return mergeFrom(input, ExtensionRegistryLite.getEmptyRegistry()); }
        @Override public B mergeFrom(Message other) { throw new UnsupportedOperationException(); }
        @Override public B mergeFrom(com.google.protobuf.MessageLite other) { throw new UnsupportedOperationException(); }
        @Override public B mergeFrom(ByteString data) {
            try { return mergeFrom(data.newCodedInput()); } catch (IOException e) { throw new RuntimeException(e); }
        }
        @Override public B mergeFrom(ByteString data, ExtensionRegistryLite reg) { return mergeFrom(data); }
        @Override public B mergeFrom(byte[] data) { return mergeFrom(data, 0, data.length); }
        @Override public B mergeFrom(byte[] data, int off, int len) {
            try { return mergeFrom(CodedInputStream.newInstance(data, off, len)); } catch (IOException e) { throw new RuntimeException(e); }
        }
        @Override public B mergeFrom(byte[] data, ExtensionRegistryLite reg) { return mergeFrom(data); }
        @Override public B mergeFrom(byte[] data, int off, int len, ExtensionRegistryLite reg) { return mergeFrom(data, off, len); }
        @Override public B mergeFrom(InputStream input) throws IOException { return mergeFrom(CodedInputStream.newInstance(input)); }
        @Override public B mergeFrom(InputStream input, ExtensionRegistryLite reg) throws IOException { return mergeFrom(input); }
        @Override public boolean mergeDelimitedFrom(InputStream input) throws IOException {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            int b = input.read();
            if (b == -1) return false;
            int len = CodedInputStream.readRawVarint32(b, input);
            mergeFrom(input.readNBytes(len));
            return true;
        }
        @Override public boolean mergeDelimitedFrom(InputStream input, ExtensionRegistryLite reg) throws IOException { return mergeDelimitedFrom(input); }
        @Override public boolean isInitialized() { return true; }
        @Override public Message getDefaultInstanceForType() { return build(); }
        @Override public Descriptors.Descriptor getDescriptorForType() { throw new UnsupportedOperationException(); }
        @Override public Map<Descriptors.FieldDescriptor, Object> getAllFields() { throw new UnsupportedOperationException(); }
        @Override public boolean hasOneof(Descriptors.OneofDescriptor oneof) { return false; }
        @Override public Descriptors.FieldDescriptor getOneofFieldDescriptor(Descriptors.OneofDescriptor oneof) { return null; }
        @Override public boolean hasField(Descriptors.FieldDescriptor field) { throw new UnsupportedOperationException(); }
        @Override public Object getField(Descriptors.FieldDescriptor field) { throw new UnsupportedOperationException(); }
        @Override public int getRepeatedFieldCount(Descriptors.FieldDescriptor field) { throw new UnsupportedOperationException(); }
        @Override public Object getRepeatedField(Descriptors.FieldDescriptor field, int index) { throw new UnsupportedOperationException(); }
        @Override public UnknownFieldSet getUnknownFields() { return UnknownFieldSet.getDefaultInstance(); }
        @Override public List<String> findInitializationErrors() { return Collections.emptyList(); }
        @Override public String getInitializationErrorString() { return ""; }
        @Override public Message.Builder newBuilderForField(Descriptors.FieldDescriptor field) { throw new UnsupportedOperationException(); }
        @Override public Message.Builder getFieldBuilder(Descriptors.FieldDescriptor field) { throw new UnsupportedOperationException(); }
        @Override public Message.Builder getRepeatedFieldBuilder(Descriptors.FieldDescriptor field, int index) { throw new UnsupportedOperationException(); }
        @Override public Message.Builder setField(Descriptors.FieldDescriptor field, Object value) { throw new UnsupportedOperationException(); }
        @Override public Message.Builder clearField(Descriptors.FieldDescriptor field) { throw new UnsupportedOperationException(); }
        @Override public Message.Builder clearOneof(Descriptors.OneofDescriptor oneof) { throw new UnsupportedOperationException(); }
        @Override public Message.Builder setRepeatedField(Descriptors.FieldDescriptor field, int index, Object value) { throw new UnsupportedOperationException(); }
        @Override public Message.Builder addRepeatedField(Descriptors.FieldDescriptor field, Object value) { throw new UnsupportedOperationException(); }
        @Override public Message.Builder setUnknownFields(UnknownFieldSet unknownFields) { throw new UnsupportedOperationException(); }
        @Override public Message.Builder mergeUnknownFields(UnknownFieldSet unknownFields) { throw new UnsupportedOperationException(); }
    }
}
