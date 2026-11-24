package io.lazyproto.runtime;

import com.google.protobuf.ByteString;
import com.google.protobuf.CodedOutputStream;
import com.google.protobuf.Descriptors;
import com.google.protobuf.Message;
import com.google.protobuf.Parser;
import com.google.protobuf.UnknownFieldSet;
import io.lazyproto.wire.WireWriter;

import java.io.IOException;
import java.io.OutputStream;
import java.util.List;
import java.util.Map;

/**
 * Strictly immutable base class for all lazy-proto {@link Message} snapshots (Architecture v3).
 *
 * <p>Every instance pairs a frozen {@link ImmutableWireBase} (read-only raw input slice + lazy
 * FieldTape) with an optional frozen external {@link WireOverlay}. Exposes zero public mutators;
 * all edits happen via {@link #toBuilder()} into external COW overlays.
 */
public abstract class AbstractLazyMessage<
        M extends AbstractLazyMessage<M, B, O>,
        B extends ImmutableWireBase,
        O extends WireOverlay> implements Message {

    protected final B base;
    protected final O overlay;

    protected AbstractLazyMessage(B base, O overlay) {
        base.freeze();
        if (overlay != null) {
            overlay.freeze();
        }
        this.base = base;
        this.overlay = overlay;
    }

    protected abstract WireMergeEngine.Adapter<B, O> mergeAdapter();

    protected final B base() {
        return base;
    }

    protected final O overlay() {
        return overlay;
    }

    public final byte[] rawBuffer() {
        return base.rawBuffer();
    }

    public final int rawOffset() {
        return base.rawOffset();
    }

    public final int rawLength() {
        return base.rawLength();
    }

    public final boolean isScanned() {
        return base.isScanned();
    }

    public final boolean isMultiSegment() {
        return base.isMultiSegment();
    }

    public final boolean isClean() {
        return mergeAdapter().isOverlayEmpty(base, overlay);
    }

    public final boolean isSameSizeDirtyOnly() {
        return overlay != null && mergeAdapter().isSameSizeDirtyOnly(base, overlay);
    }

    @Override
    public int getSerializedSize() {
        return WireMergeEngine.getSerializedSize(base, overlay, mergeAdapter());
    }

    @Override
    public byte[] toByteArray() {
        return WireMergeEngine.toByteArray(base, overlay, mergeAdapter());
    }

    public int writeToBuffer(byte[] target, int targetOffset) {
        return WireMergeEngine.writeToBuffer(base, overlay, mergeAdapter(), target, targetOffset);
    }

    public void writeSplicedTo(WireWriter writer) {
        WireMergeEngine.writeSplicedTo(base, overlay, mergeAdapter(), writer);
    }

    @Override
    public ByteString toByteString() {
        return WireMergeEngine.toByteString(base, overlay, mergeAdapter());
    }

    @Override
    public void writeTo(CodedOutputStream output) throws IOException {
        WireMergeEngine.writeTo(base, overlay, mergeAdapter(), output);
    }

    @Override
    public void writeTo(OutputStream output) throws IOException {
        WireMergeEngine.writeTo(base, overlay, mergeAdapter(), output);
    }

    @Override
    public void writeDelimitedTo(OutputStream output) throws IOException {
        WireMergeEngine.writeDelimitedTo(base, overlay, mergeAdapter(), output);
    }

    public boolean rawBytesEquals(AbstractLazyMessage<?, B, O> other) {
        if (other == null) return false;
        if (this == other) return true;
        return WireMergeEngine.rawBytesEquals(
                this.base, this.overlay, mergeAdapter(),
                other.base, other.overlay, other.mergeAdapter());
    }

    @Override
    public boolean isInitialized() {
        return true;
    }

    @Override
    public Parser<? extends Message> getParserForType() {
        throw new UnsupportedOperationException("getParserForType() is disabled in lazy-proto; use parseFrom.");
    }

    // ==================== Intentionally Unsupported Methods ====================

    @Override
    public final boolean equals(Object obj) {
        throw new UnsupportedOperationException(
                "equals() is disabled in lazy-proto to prevent full-tree eager deserialization. " +
                "Use rawBytesEquals() or compare key fields directly.");
    }

    @Override
    public final int hashCode() {
        throw new UnsupportedOperationException(
                "hashCode() is disabled in lazy-proto. Do not use lazy messages as hash keys.");
    }

    @Override
    public final Descriptors.Descriptor getDescriptorForType() {
        throw new UnsupportedOperationException("Reflection Descriptor is disabled in lazy-proto.");
    }

    @Override
    public final Map<Descriptors.FieldDescriptor, Object> getAllFields() {
        throw new UnsupportedOperationException("getAllFields() reflection is disabled in lazy-proto.");
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
        throw new UnsupportedOperationException(
                "UnknownFieldSet object creation is disabled; unknown fields are preserved automatically.");
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
    public String toString() {
        return getClass().getSimpleName() + "[rawLen=" + base.rawLength()
                + ", scanned=" + base.isScanned()
                + ", clean=" + isClean() + "]";
    }
}
