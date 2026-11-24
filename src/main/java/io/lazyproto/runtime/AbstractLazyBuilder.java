package io.lazyproto.runtime;

import com.google.protobuf.ByteString;
import com.google.protobuf.CodedInputStream;
import com.google.protobuf.Descriptors;
import com.google.protobuf.ExtensionRegistryLite;
import com.google.protobuf.Message;
import com.google.protobuf.MessageLite;
import com.google.protobuf.UnknownFieldSet;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;

/**
 * Snapshot-safe Builder base class using Ownership Transfer + Copy-on-Post-Build-Write.
 *
 * <p>Calling {@link #build()} marks the builder as built and returns the underlying {@link AbstractLazyMessage}
 * directly with <b>zero extra copy</b>. Only if the caller continues to mutate the same builder <i>after</i>
 * {@link #build()} does {@link #ensureMutable()} shallow-fork the backing message so previously built snapshots
 * remain 100% isolated and immutable.
 */
public abstract class AbstractLazyBuilder<M extends AbstractLazyMessage<M>, B extends AbstractLazyBuilder<M, B>>
        implements Message.Builder {

    protected M impl;
    protected boolean built;

    protected AbstractLazyBuilder(M initialImpl) {
        this.impl = initialImpl;
        this.built = false;
    }

    public final boolean isBuilt() {
        return built;
    }

    public final M impl() {
        return impl;
    }

    public final void ensureMutable() {
        if (built) {
            this.impl = this.impl.copy();
            this.built = false;
            onForkedAfterBuild();
        }
    }

    protected void onForkedAfterBuild() {}

    protected void onBuild() {}

    @Override
    public M build() {
        this.built = true;
        onBuild();
        return impl;
    }

    @Override
    public M buildPartial() {
        return build();
    }

    @SuppressWarnings("unchecked")
    protected final B self() {
        return (B) this;
    }

    @Override
    public B clear() {
        ensureMutable();
        impl.reset();
        impl.markScannedEmpty();
        return self();
    }

    @Override
    public abstract B clone();

    @Override
    public boolean isInitialized() {
        return true;
    }

    @Override
    public M getDefaultInstanceForType() {
        return impl.copy();
    }

    @Override
    public B mergeFrom(byte[] data) {
        ensureMutable();
        impl.wrap(data, 0, data.length);
        return self();
    }

    @Override
    public B mergeFrom(byte[] data, int off, int len) {
        ensureMutable();
        impl.wrap(data, off, len);
        return self();
    }

    @Override
    public B mergeFrom(ByteString data) {
        ensureMutable();
        impl.wrap(data);
        return self();
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
        byte[] bytes = input.readRawBytes(input.getBytesUntilLimit());
        return mergeFrom(bytes);
    }

    @Override
    @SuppressWarnings("unchecked")
    public B mergeFrom(Message other) {
        ensureMutable();
        if (other instanceof AbstractLazyMessage<?> lazyOther && !lazyOther.isMultiSegment() && lazyOther.isClean()) {
            impl.wrap(lazyOther.rawBuffer(), lazyOther.rawOffset(), lazyOther.rawLength());
        } else {
            impl.wrap(other.toByteArray());
        }
        return self();
    }

    @Override
    public B mergeFrom(MessageLite other) {
        if (other instanceof AbstractLazyMessage<?> lazyOther && !lazyOther.isMultiSegment() && lazyOther.isClean()) {
            ensureMutable();
            impl.wrap(lazyOther.rawBuffer(), lazyOther.rawOffset(), lazyOther.rawLength());
            return self();
        }
        return mergeFrom(other.toByteArray());
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
