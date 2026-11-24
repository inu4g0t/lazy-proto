package io.lazyproto.runtime;

import com.google.protobuf.ByteString;
import com.google.protobuf.CodedInputStream;
import com.google.protobuf.Descriptors;
import com.google.protobuf.ExtensionRegistryLite;
import com.google.protobuf.Message;
import com.google.protobuf.MessageLite;
import com.google.protobuf.UnknownFieldSet;
import io.lazyproto.wire.WireWriter;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Unified snapshot-safe {@link Message.Builder} base class for Architecture v3
 * (Immutable Base + External COW Overlay).
 *
 * <p>All write operations (`set*`, `clear()`, `mergeFrom()`) mutate only external overlays.
 * Calling {@link #build()} freezes the base and active overlays in O(1) without deep copying;
 * subsequent writes to the same builder (or any bound child cursor) copy-on-write only the frozen
 * overlay nodes. Supports explicit thread-local zero-allocation reuse via {@link #reuse(byte[], int, int)}
 * when snapshots are not retained.
 */
public abstract class AbstractLazyBuilder<
        M extends AbstractLazyMessage<M, Base, Ov>,
        B extends AbstractLazyBuilder<M, B, Base, Ov>,
        Base extends ImmutableWireBase,
        Ov extends WireOverlay> implements Message.Builder {

    protected Base base;
    protected Ov overlay;
    private WireWriter cachedWriter;

    protected AbstractLazyBuilder(Base initialBase, Ov initialOverlay) {
        this.base = initialBase;
        this.overlay = initialOverlay;
    }

    protected boolean isBoundToParent() {
        return false;
    }

    protected abstract WireMergeEngine.Adapter<Base, Ov> mergeAdapter();

    protected abstract Base newEmptyBase();

    protected abstract Ov newEmptyOverlay();

    protected abstract Ov copyMutableOverlay(Ov frozenOverlay);

    protected abstract M wrapAsMessage(Base frozenBase, Ov frozenOverlay);

    protected abstract M emptyDefaultMessage();

    /**
     * Resolves current read-only Base for this builder (or through parent if bound).
     */
    protected Base resolveReadBase() {
        return this.base;
    }

    /**
     * Resolves current read-only Overlay for this builder (may be {@code null} if clean).
     */
    protected Ov resolveReadOverlay() {
        return this.overlay;
    }

    /**
     * Ensures this builder owns an unfrozen Overlay ready for mutation (copying if frozen).
     */
    protected Ov ensureMutableOverlay() {
        if (this.overlay == null) {
            this.overlay = newEmptyOverlay();
        } else if (this.overlay.isFrozen()) {
            this.overlay = copyMutableOverlay(this.overlay);
        }
        this.overlay.invalidateSizeCache();
        return this.overlay;
    }

    @SuppressWarnings("unchecked")
    protected final B self() {
        return (B) this;
    }

    /**
     * Thread-local zero-allocation entry point: resets this builder's internal base &amp; overlay
     * when neither has been frozen into a built {@link AbstractLazyMessage} snapshot. If previously
     * frozen, allocates a fresh base/overlay so existing snapshots remain 100% immutable.
     */
    public B reuse(byte[] data) {
        return reuse(data, 0, data.length);
    }

    public B reuse(byte[] data, int offset, int length) {
        if (isBoundToParent()) {
            return replaceWithBytes(data, offset, length);
        }
        if (this.base == null || this.base.isFrozen()) {
            this.base = newEmptyBase();
        }
        this.base.resetForReuse(data, offset, length);
        if (this.overlay != null) {
            if (this.overlay.isFrozen()) {
                this.overlay = null;
            } else {
                resetOverlayInPlace(this.overlay);
            }
        }
        onAfterReuse();
        return self();
    }

    protected void resetOverlayInPlace(Ov ov) {}

    protected void onAfterReuse() {}

    protected B replaceWithBytes(byte[] data, int offset, int length) {
        if (this.base == null || this.base.isFrozen()) {
            this.base = newEmptyBase();
        }
        this.base.resetForReuse(data, offset, length);
        if (this.overlay != null) {
            this.overlay = null;
        }
        return self();
    }

    protected B replaceWithByteString(ByteString data) {
        if (this.base == null || this.base.isFrozen()) {
            this.base = newEmptyBase();
        }
        this.base.resetForReuse(data);
        if (this.overlay != null) {
            this.overlay = null;
        }
        return self();
    }

    protected B clearAllFields() {
        if (this.base == null || this.base.isFrozen()) {
            this.base = newEmptyBase();
        } else {
            this.base.resetForReuse(ImmutableWireBase.EMPTY_BYTES, 0, 0);
        }
        this.base.markScannedEmpty();
        if (this.overlay != null) {
            this.overlay = null;
        }
        return self();
    }

    @Override
    public M build() {
        Base b = resolveReadBase();
        Ov o = resolveReadOverlay();
        b.freeze();
        if (o != null) {
            o.freeze();
        }
        return wrapAsMessage(b, o);
    }

    @Override
    public M buildPartial() {
        return build();
    }

    @Override
    public B clear() {
        return clearAllFields();
    }

    @Override
    public M getDefaultInstanceForType() {
        return emptyDefaultMessage();
    }

    // ==================== Direct Serialization (Zero Snapshot Allocation for Reuse Loops) ====================

    public int getSerializedSize() {
        return WireMergeEngine.getSerializedSize(resolveReadBase(), resolveReadOverlay(), mergeAdapter());
    }

    public byte[] toByteArray() {
        return WireMergeEngine.toByteArray(resolveReadBase(), resolveReadOverlay(), mergeAdapter());
    }

    public int writeToBuffer(byte[] target, int targetOffset) {
        Base b = resolveReadBase();
        Ov o = resolveReadOverlay();
        WireMergeEngine.Adapter<Base, Ov> adapter = mergeAdapter();
        if (!b.isMultiSegment() && adapter.isOverlayEmpty(b, o)) {
            int len = b.rawLength();
            System.arraycopy(b.rawBuffer(), b.rawOffset(), target, targetOffset, len);
            return len;
        }
        if (o != null && adapter.isSameSizeDirtyOnly(b, o)) {
            int len = b.rawLength();
            System.arraycopy(b.rawBuffer(), b.rawOffset(), target, targetOffset, len);
            adapter.applyInPlacePatches(b, o, target, b.rawOffset() - targetOffset);
            return len;
        }
        if (cachedWriter == null) {
            cachedWriter = new WireWriter(target, targetOffset);
        } else {
            cachedWriter.reset(target, targetOffset);
        }
        WireMergeEngine.writeSplicedTo(b, o, adapter, cachedWriter);
        return cachedWriter.position() - targetOffset;
    }

    public boolean isScanned() {
        return resolveReadBase().isScanned();
    }

    public boolean isClean() {
        return mergeAdapter().isOverlayEmpty(resolveReadBase(), resolveReadOverlay());
    }

    public boolean isSameSizeDirtyOnly() {
        Ov o = resolveReadOverlay();
        return o != null && mergeAdapter().isSameSizeDirtyOnly(resolveReadBase(), o);
    }

    @Override
    public abstract B clone();

    @Override
    public boolean isInitialized() {
        return true;
    }

    @Override
    public B mergeFrom(byte[] data) {
        return mergeFrom(data, 0, data.length);
    }

    private boolean isEffectivelyEmptyForMerge() {
        Base b = resolveReadBase();
        if (b.isMultiSegment() || b.rawLength() > 0) {
            return false;
        }
        Ov o = resolveReadOverlay();
        return o == null || mergeAdapter().isOverlayEmpty(b, o);
    }

    private B concatAndReplace(byte[] incoming, int off, int len) {
        byte[] current = toByteArray();
        if (current.length == 0) {
            return replaceWithBytes(incoming, off, len);
        }
        byte[] merged = new byte[current.length + len];
        System.arraycopy(current, 0, merged, 0, current.length);
        System.arraycopy(incoming, off, merged, current.length, len);
        return replaceWithBytes(merged, 0, merged.length);
    }

    @Override
    public B mergeFrom(byte[] data, int off, int len) {
        Objects.checkFromIndexSize(off, len, data.length);
        if (len == 0) {
            return self();
        }
        if (isEffectivelyEmptyForMerge()) {
            return replaceWithBytes(data, off, len);
        }
        return concatAndReplace(data, off, len);
    }

    @Override
    public B mergeFrom(ByteString data) {
        if (data.isEmpty()) {
            return self();
        }
        if (isEffectivelyEmptyForMerge()) {
            return replaceWithByteString(data);
        }
        byte[] incoming = data.toByteArray();
        return concatAndReplace(incoming, 0, incoming.length);
    }

    @Override
    public B mergeFrom(InputStream input) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        byte[] tmp = new byte[4096];
        int n;
        while ((n = input.read(tmp)) != -1) {
            baos.write(tmp, 0, n);
        }
        return mergeFrom(baos.toByteArray());
    }

    @Override
    public B mergeFrom(CodedInputStream input) throws IOException {
        return mergeFrom(input, ExtensionRegistryLite.getEmptyRegistry());
    }

    @Override
    public B mergeFrom(CodedInputStream input, ExtensionRegistryLite extensionRegistry) throws IOException {
        int limit = input.getBytesUntilLimit();
        if (limit >= 0) {
            return mergeFrom(input.readRawBytes(limit));
        }
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        while (!input.isAtEnd()) {
            baos.write(input.readRawByte());
        }
        return mergeFrom(baos.toByteArray());
    }

    @Override
    public B mergeFrom(Message other) {
        return mergeFromMessageLite(other);
    }

    @Override
    public B mergeFrom(MessageLite other) {
        return mergeFromMessageLite(other);
    }

    private B mergeFromMessageLite(MessageLite other) {
        if (other == this.getDefaultInstanceForType()) {
            return self();
        }
        if (other instanceof AbstractLazyMessage<?, ?, ?> lazyOther && lazyOther.isClean() && !lazyOther.isMultiSegment()) {
            return mergeFrom(lazyOther.rawBuffer(), lazyOther.rawOffset(), lazyOther.rawLength());
        }
        byte[] bytes = other.toByteArray();
        return mergeFrom(bytes, 0, bytes.length);
    }

    @Override
    public B mergeFrom(byte[] data, ExtensionRegistryLite extensionRegistry) {
        return mergeFrom(data);
    }

    @Override
    public B mergeFrom(byte[] data, int off, int len, ExtensionRegistryLite extensionRegistry) {
        return mergeFrom(data, off, len);
    }

    @Override
    public B mergeFrom(ByteString data, ExtensionRegistryLite extensionRegistry) {
        return mergeFrom(data);
    }

    @Override
    public B mergeFrom(InputStream input, ExtensionRegistryLite extensionRegistry) throws IOException {
        return mergeFrom(input);
    }

    @Override
    public boolean mergeDelimitedFrom(InputStream input) throws IOException {
        int firstByte = input.read();
        if (firstByte == -1) return false;
        int size = CodedInputStream.readRawVarint32(firstByte, input);
        byte[] data = input.readNBytes(size);
        mergeFrom(data);
        return true;
    }

    @Override
    public boolean mergeDelimitedFrom(InputStream input, ExtensionRegistryLite extensionRegistry) throws IOException {
        return mergeDelimitedFrom(input);
    }

    // ==================== Intentionally Unsupported Reflection Methods ====================

    @Override
    public final Descriptors.Descriptor getDescriptorForType() {
        throw new UnsupportedOperationException("Reflection Descriptor is disabled in lazy-proto.");
    }

    @Override
    public final Map<Descriptors.FieldDescriptor, Object> getAllFields() {
        throw new UnsupportedOperationException("Reflection is disabled in lazy-proto.");
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
        throw new UnsupportedOperationException("UnknownFieldSet is disabled in lazy-proto.");
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
}
