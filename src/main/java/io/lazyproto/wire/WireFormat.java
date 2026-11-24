package io.lazyproto.wire;

/**
 * Low-level Protocol Buffers wire format constants and tag utilities.
 */
public final class WireFormat {
    public static final int WIRETYPE_VARINT           = 0;
    public static final int WIRETYPE_FIXED64          = 1;
    public static final int WIRETYPE_LENGTH_DELIMITED = 2;
    public static final int WIRETYPE_START_GROUP      = 3;
    public static final int WIRETYPE_END_GROUP        = 4;
    public static final int WIRETYPE_FIXED32          = 5;

    public static final int TAG_TYPE_BITS = 3;
    public static final int TAG_TYPE_MASK = (1 << TAG_TYPE_BITS) - 1;

    private WireFormat() {}

    public static int getTagWireType(int tag) {
        return tag & TAG_TYPE_MASK;
    }

    public static int getTagFieldNumber(int tag) {
        return tag >>> TAG_TYPE_BITS;
    }

    public static int makeTag(int fieldNumber, int wireType) {
        return (fieldNumber << TAG_TYPE_BITS) | wireType;
    }
}
