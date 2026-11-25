package io.lazyproto.runtime;

import com.google.protobuf.ByteString;
import com.google.protobuf.UnsafeByteOperations;
import io.lazyproto.wire.Utf8Slice;

/**
 * Bridge helpers isolating {@code com.google.protobuf.*} types to the Java intermediate compatibility layer.
 */
public final class ProtobufCompat {
    private ProtobufCompat() {}

    public static ByteString toByteString(Utf8Slice slice) {
        return UnsafeByteOperations.unsafeWrap(slice.array(), slice.offset(), slice.byteLength());
    }
}
