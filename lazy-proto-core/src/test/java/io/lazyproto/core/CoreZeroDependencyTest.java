package io.lazyproto.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies at runtime that {@code lazy-proto-core} operates with ZERO {@code com.google.protobuf}
 * classes on the classpath.
 */
public class CoreZeroDependencyTest {

    @Test
    @DisplayName("lazy-proto-core runs without com.google.protobuf on classpath")
    void testZeroProtobufDependencyAndRoundTrip() {
        assertThrows(ClassNotFoundException.class, () -> Class.forName("com.google.protobuf.Message"),
                "lazy-proto-core must NOT pull com.google.protobuf onto the classpath");

        RawOrderEnvelope env = new RawOrderEnvelope().initScratch();
        env.setOrderId(1001L).setBuyerName("alice").setStatus(-1).setTotalCents(5000L);
        env.user().setUserId(42L).setCity("SH").setVipLevel(3);
        env.addItem().setItemId(7L).setSku("SKU-1").setQuantity(2);
        byte[] wire = env.toByteArray();

        RawOrderEnvelope parsed = new RawOrderEnvelope().wrap(wire, 0, wire.length);
        assertFalse(parsed.isScanned());
        assertEquals(1001L, parsed.getOrderId());
        assertEquals("alice", parsed.getBuyerName());
        assertEquals(-1, parsed.getStatus());
        assertEquals("SH", parsed.user().getCity());
        assertEquals(1, parsed.getItemsCount());
        assertEquals("SKU-1", parsed.items(0).getSku());

        RawWireView view = new RawWireView().wrap(wire, 0, wire.length);
        assertEquals(1001L, view.getInt64(1));
        assertEquals(-1, view.getInt32(3));
        assertEquals("SH", view.sub(5).getString(2));
        view.setInt32(3, -2);
        assertTrue(view.isSameSizeDirtyOnly());
        byte[] patched = view.toByteArray();
        assertEquals(wire.length, patched.length);
        assertEquals(-2, new RawWireView().wrap(patched, 0, patched.length).getInt32(3));
    }
}
