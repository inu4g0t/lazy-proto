package io.lazyproto;

import com.google.protobuf.InvalidProtocolBufferException;
import io.lazyproto.bench.proto.LineItem;
import io.lazyproto.bench.proto.OrderEnvelope;
import io.lazyproto.bench.proto.UserProfile;
import io.lazyproto.core.RawOrderEnvelope;
import io.lazyproto.core.RawWireView;
import io.lazyproto.dynamic.ProtoDocument;
import io.lazyproto.model.LazyOrderProto.LazyOrderEnvelope;
import io.lazyproto.wire.Utf8Slice;
import io.lazyproto.wire.WireFormat;
import io.lazyproto.wire.WireWriter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Targeted regressions & Stateful Model-Checking Fuzzers for the Java Lower Level
 * ({@link RawOrderEnvelope}, {@link RawWireView}) plus thread-safety & boundary hardening
 * for the Java Compatibility Layer ({@link LazyOrderEnvelope}, {@link ProtoDocument}).
 */
public class LowerLevelFuzzAndRegressionTest {

    // =========================================================================
    // 1. Targeted P0 / P1 / P2 Regressions & Design Contracts
    // =========================================================================

    @Test
    @DisplayName("P0: RawWireView markScalarDirty must NOT take FP1 when repCount > 1 (no shadowed duplicate leak)")
    void testRawWireViewShadowedDuplicateNotLeakedOnSameSizeWrite() throws Exception {
        byte[] splitIn = new byte[] {0x2A, 0x02, 0x08, 0x01, 0x2A, 0x02, 0x18, 0x02};
        RawWireView wv = new RawWireView().wrap(splitIn, 0, splitIn.length);

        wv.setBytes(5, new byte[] {0x18, 0x09}, 0, 2);
        assertFalse(wv.isSameSizeDirtyOnly(), "Shadowing earlier segment must disable FP1");

        byte[] out = wv.toByteArray();
        assertArrayEquals(new byte[] {0x2A, 0x02, 0x18, 0x09}, out);
        OrderEnvelope dec = OrderEnvelope.parseFrom(out);
        assertEquals(0L, dec.getUser().getUserId(), "Shadowed segment 0 must not leak into output");
        assertEquals(9, dec.getUser().getVipLevel());
    }

    @Test
    @DisplayName("P0: Negative int32 encodes as 10-byte sign-extended varint matching protoc")
    void testNegativeInt32CanonicalEncoding() throws Exception {
        int[] negVals = {-1, -42, -128, Integer.MIN_VALUE};
        for (int v : negVals) {
            OrderEnvelope official = OrderEnvelope.newBuilder()
                    .setStatus(v)
                    .setUser(UserProfile.newBuilder().setVipLevel(v).build())
                    .addItems(LineItem.newBuilder().setQuantity(v).build())
                    .build();

            RawOrderEnvelope raw = new RawOrderEnvelope().initScratch();
            raw.setStatus(v);
            raw.user().setVipLevel(v);
            raw.addItem().setQuantity(v);
            byte[] rawOut = raw.toByteArray();
            assertEquals(official, OrderEnvelope.parseFrom(rawOut));

            raw.wrap(official.toByteArray(), 0, official.toByteArray().length);
            raw.setStatus(v == -1 ? -2 : -1);
            assertTrue(raw.isSameSizeDirtyOnly(), "Negative int32 -> negative int32 should hit FP1");
            OrderEnvelope patched = OrderEnvelope.parseFrom(raw.toByteArray());
            assertEquals(v == -1 ? -2 : -1, patched.getStatus());

            RawWireView wv = new RawWireView().wrap(new byte[0], 0, 0);
            wv.setInt32(3, v);
            assertEquals(11, wv.toByteArray().length, "1-byte tag + 10-byte negative int32 varint");
            assertEquals(v, OrderEnvelope.parseFrom(wv.toByteArray()).getStatus());
        }
    }

    @Test
    @DisplayName("P1: WireTape & ImmutableWireBase reject truncated LEN and Integer.MAX_VALUE overflow with IllegalArgumentException")
    void testScannerBoundsAndOverflowGuards() {
        byte[] truncTagOnly = new byte[] {0x12};
        assertThrows(IllegalArgumentException.class, () -> {
            RawWireView wv = new RawWireView().wrap(truncTagOnly, 0, truncTagOnly.length);
            wv.getString(2);
        });
        assertThrows(IllegalArgumentException.class, () -> LazyOrderEnvelope.parseFrom(truncTagOnly).getBuyerName());

        byte[] outer = new byte[] {0x2A, 0x01, 0x12, 0x00, 0x08, 0x01};
        assertThrows(IllegalArgumentException.class, () -> {
            RawOrderEnvelope env = new RawOrderEnvelope().wrap(outer, 0, outer.length);
            env.user().getCity();
        });
        assertThrows(IllegalArgumentException.class, () -> LazyOrderEnvelope.parseFrom(outer).getUser().getCity());

        byte[] overflowLen = new byte[] {
                0x12, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, 0x07, 'a', 'b'
        };
        assertThrows(IllegalArgumentException.class, () -> {
            RawWireView wv = new RawWireView().wrap(overflowLen, 0, overflowLen.length);
            wv.getString(2);
        });
        assertThrows(IllegalArgumentException.class, () -> LazyOrderEnvelope.parseFrom(overflowLen).getBuyerName());

        byte[] badItem = new byte[] {0x32, 0x03, 0x12, 0x10, 'A'};
        RawOrderEnvelope env = new RawOrderEnvelope().wrap(badItem, 0, badItem.length);
        assertEquals(1, env.getItemsCount());
        RawOrderEnvelope.RawLineItem scratch = new RawOrderEnvelope.RawLineItem();
        assertThrows(IllegalArgumentException.class, () -> env.readItemEager(0, scratch));
    }

    @Test
    @DisplayName("P1: RawWireView dirty-counter reclaim & detached child isolation across setBytes, erase, and wrap")
    void testRawWireViewCounterReclaimAndDetachedChildIsolation() throws Exception {
        byte[] singleRep = new byte[] {0x32, 0x02, 0x18, 0x01};
        RawWireView wv = new RawWireView().wrap(singleRep, 0, singleRep.length);
        RawWireView staleRepChild = wv.repeatedSub(6, 0);
        staleRepChild.setInt32(3, 50000);
        assertFalse(wv.isSameSizeDirtyOnly());

        wv.setBytes(6, new byte[] {0x18, 0x07}, 0, 2);
        assertTrue(wv.isSameSizeDirtyOnly(), "Old repeatedSub var-size dirty count must be reclaimed");
        assertArrayEquals(new byte[] {0x32, 0x02, 0x18, 0x07}, wv.toByteArray());

        staleRepChild.setInt32(3, 99999);
        assertTrue(wv.isSameSizeDirtyOnly(), "Detached repeated child must not notify parent");
        assertArrayEquals(new byte[] {0x32, 0x02, 0x18, 0x07}, wv.toByteArray());

        byte[] userIn = new byte[] {0x2A, 0x02, 0x08, 0x01};
        wv.wrap(userIn, 0, userIn.length);
        RawWireView staleSub = wv.sub(5);
        staleSub.setInt64(1, 2);
        assertEquals(2L, OrderEnvelope.parseFrom(wv.toByteArray()).getUser().getUserId());

        wv.erase(5);
        assertFalse(wv.has(5));
        assertEquals(0, wv.toByteArray().length);

        staleSub.setInt64(1, 777);
        assertFalse(wv.has(5));
        assertEquals(0, wv.toByteArray().length);

        wv.wrap(userIn, 0, userIn.length);
        RawWireView oldChild = wv.sub(5);
        assertEquals(1L, oldChild.getInt64(1));

        byte[] newIn = new byte[] {0x2A, 0x02, 0x08, 0x05};
        wv.wrap(newIn, 0, newIn.length);
        oldChild.setInt64(1, 9999);
        assertTrue(wv.isClean(), "Stale child from prior wrap() must be detached");
        assertEquals(5L, OrderEnvelope.parseFrom(wv.toByteArray()).getUser().getUserId());
    }

    @Test
    @DisplayName("P1: Immutable LazyOrderEnvelope & ProtoDocument slice/bytes reads are non-aliasing and thread-safe")
    void testImmutableSliceAndBytesThreadSafety() throws Exception {
        byte[] complex = LazyProtoCorrectnessTest.createComplexOrderBytes(20, 256);
        LazyOrderEnvelope msg = LazyOrderEnvelope.parseFrom(complex);
        ProtoDocument doc = ProtoDocument.wrapBytes(complex);

        Utf8Slice s2 = doc.getSlice(2);
        Utf8Slice s9 = doc.getSlice(9);
        assertEquals("alice_wonderland", s2.toString());
        assertEquals("trace-00112233445566778899aabbccddeeff", s9.toString());
        assertEquals("alice_wonderland", s2.toString(), "Subsequent getSlice must not clobber earlier slice");

        int threads = 8;
        int iters = 2_000;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Callable<Void>> tasks = new ArrayList<>();
            for (int t = 0; t < threads; t++) {
                final int tid = t;
                tasks.add(() -> {
                    Utf8Slice localReusable = new Utf8Slice();
                    for (int i = 0; i < iters; i++) {
                        assertEquals("alice_wonderland", msg.getBuyerNameBytes().toStringUtf8());
                        assertEquals("alice_wonderland", msg.getBuyerNameSlice().toString());
                        assertEquals("alice_wonderland", msg.getBuyerNameSlice(localReusable).toString());
                        assertEquals("trace-00112233445566778899aabbccddeeff", msg.getTraceIdBytes().toStringUtf8());
                        assertEquals("SH", msg.getUser().getCityBytes().toStringUtf8());
                        assertEquals("alice@example.com", msg.getUser().getEmailSlice().toString());
                        int idx = (tid + i) % 20;
                        assertEquals(String.format("SKU-PRO-%04d", idx), msg.getItems(idx).getSkuBytes().toStringUtf8());

                        Utf8Slice d2 = doc.getSlice(2);
                        Utf8Slice d9 = doc.getSlice(9);
                        assertEquals("alice_wonderland", d2.toString());
                        assertEquals("trace-00112233445566778899aabbccddeeff", d9.toString());
                        assertEquals("alice_wonderland", doc.getSlice(2, localReusable).toString());
                    }
                    return null;
                });
            }
            for (Future<Void> f : pool.invokeAll(tasks)) {
                f.get();
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("P2: AbstractLazyBuilder.mergeDelimitedFrom rejects negative size and truncated EOF")
    void testMergeDelimitedFromRejectsInvalidStreams() {
        byte[] truncated = new byte[] {20, 0x08, 0x01, 0x18};
        assertThrows(InvalidProtocolBufferException.class, () ->
                LazyOrderEnvelope.newBuilder().mergeDelimitedFrom(new ByteArrayInputStream(truncated)));

        byte[] negSize = new byte[] {
                (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF,
                (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, 0x01
        };
        assertThrows(InvalidProtocolBufferException.class, () ->
                LazyOrderEnvelope.newBuilder().mergeDelimitedFrom(new ByteArrayInputStream(negSize)));
    }

    @Test
    @DisplayName("Design Contract: validateDeep() catches unvisited corrupt nested sub-messages & FIXED32/DOUBLE FP1/FP2 parity")
    void testValidateDeepAndFixed32DoubleParity() throws Exception {
        byte[] valid = LazyProtoCorrectnessTest.createComplexOrderBytes(10, 64);
        assertDoesNotThrow(() -> LazyOrderEnvelope.parseFrom(valid).validateDeep());
        assertDoesNotThrow(() -> new RawOrderEnvelope().wrap(valid, 0, valid.length).validateDeep());

        byte[] corruptNestedUser = new byte[] {
                0x08, 0x01,
                0x2A, 0x03, 0x12, 0x10, 'A'
        };
        LazyOrderEnvelope compatBad = LazyOrderEnvelope.parseFrom(corruptNestedUser);
        assertEquals(1L, compatBad.getOrderId());
        assertArrayEquals(corruptNestedUser, compatBad.toByteArray());
        assertThrows(IllegalArgumentException.class, compatBad::validateDeep);

        RawOrderEnvelope rawBad = new RawOrderEnvelope().wrap(corruptNestedUser, 0, corruptNestedUser.length);
        assertEquals(1L, rawBad.getOrderId());
        assertArrayEquals(corruptNestedUser, rawBad.toByteArray());
        assertThrows(IllegalArgumentException.class, rawBad::validateDeep);

        byte[] corruptNestedItem = new byte[] {
                0x08, 0x02,
                0x32, 0x03, 0x21, 0x01, 0x02
        };
        assertThrows(IllegalArgumentException.class, () -> LazyOrderEnvelope.parseFrom(corruptNestedItem).validateDeep());
        assertThrows(IllegalArgumentException.class, () -> new RawOrderEnvelope().wrap(corruptNestedItem, 0, corruptNestedItem.length).validateDeep());

        byte[] wire = new byte[64];
        WireWriter ww = new WireWriter(wire, 0);
        ww.writeTag(11, WireFormat.WIRETYPE_FIXED32);
        ww.writeFixed32(0x12345678);
        ww.writeTag(12, WireFormat.WIRETYPE_FIXED64);
        ww.writeDouble(3.14159);
        byte[] fixedWire = Arrays.copyOf(wire, ww.position());

        RawWireView rw = new RawWireView().wrap(fixedWire, 0, fixedWire.length);
        assertEquals(0x12345678, rw.getFixed32(11));
        assertEquals(3.14159, rw.getDouble(12), 1e-12);
        rw.setFixed32(11, 0xCAFEBABE).setDouble(12, -42.5);
        assertTrue(rw.isSameSizeDirtyOnly(), "FIXED32 & DOUBLE edits should hit FP1");
        byte[] rwOut = rw.toByteArray();
        assertEquals(fixedWire.length, rwOut.length);
        RawWireView rwCheck = new RawWireView().wrap(rwOut, 0, rwOut.length);
        assertEquals(0xCAFEBABE, rwCheck.getFixed32(11));
        assertEquals(-42.5, rwCheck.getDouble(12), 1e-12);

        rw.setFixed32(20, 0x01020304).setDouble(21, 99.125);
        RawWireView rwAppCheck = new RawWireView().wrap(rw.toByteArray(), 0, rw.toByteArray().length);
        assertEquals(0x01020304, rwAppCheck.getFixed32(20));
        assertEquals(99.125, rwAppCheck.getDouble(21), 1e-12);

        ProtoDocument doc = ProtoDocument.wrapBytes(fixedWire);
        assertEquals(0x12345678, doc.getFixed32(11));
        assertEquals(3.14159, doc.getDouble(12), 1e-12);
        ProtoDocument patchedDoc = doc.toBuilder().setFixed32(11, 0xDEADBEEF).setDouble(12, 2.71828).build();
        assertTrue(patchedDoc.isSameSizeDirtyOnly());
        ProtoDocument docCheck = ProtoDocument.wrapBytes(patchedDoc.toByteArray());
        assertEquals(0xDEADBEEF, docCheck.getFixed32(11));
        assertEquals(2.71828, docCheck.getDouble(12), 1e-12);
    }

    // =========================================================================
    // 2. Stateful Model-Checking Fuzzer: RawOrderEnvelope
    // =========================================================================

    @Test
    @DisplayName("Fuzzer 1: RawOrderEnvelope Stateful Model-Checking (200 seeds x 35 steps vs Official protoc)")
    void testRawOrderEnvelopeStatefulFuzzer() throws Exception {
        byte[] complex = LazyProtoCorrectnessTest.createComplexOrderBytes(140, 256);
        byte[] medium = LazyProtoCorrectnessTest.createComplexOrderBytes(15, 64);
        byte[] tiny = OrderEnvelope.newBuilder().setOrderId(7L).setStatus(1).setTotalCents(99L).build().toByteArray();
        byte[] splitUser = buildSplitUserBytes();

        byte[][] corpus = new byte[][] {complex, medium, tiny, splitUser, new byte[0]};

        RawOrderEnvelope raw = new RawOrderEnvelope();
        byte[] reusableOut = new byte[65536];
        RawOrderEnvelope.RawLineItem eagerScratch = new RawOrderEnvelope.RawLineItem();

        for (int seed = 0; seed < 200; seed++) {
            Random rng = new Random(0xC0FFEE00L + seed);
            byte[] initial = corpus[rng.nextInt(corpus.length)];
            OrderEnvelope.Builder oracle;
            if (rng.nextInt(5) == 0) {
                raw.initScratch();
                oracle = OrderEnvelope.newBuilder();
            } else {
                raw.wrap(initial, 0, initial.length);
                oracle = OrderEnvelope.parseFrom(initial).toBuilder();
            }

            for (int step = 0; step < 35; step++) {
                int action = rng.nextInt(16);
                switch (action) {
                    case 0 -> {
                        assertEquals(oracle.getOrderId(), raw.getOrderId());
                        assertEquals(oracle.getBuyerName(), raw.getBuyerName());
                        assertEquals(oracle.getStatus(), raw.getStatus());
                        assertEquals(oracle.getUpdatedAtFixed64(), raw.getUpdatedAtFixed64());
                        assertEquals(oracle.getAuthToken(), raw.getAuthToken());
                        assertEquals(oracle.getTraceId(), raw.getTraceId());
                        assertEquals(oracle.getTotalCents(), raw.getTotalCents());
                        assertEquals(oracle.getItemsCount(), raw.getItemsCount());
                        if (oracle.hasUser()) {
                            assertEquals(oracle.getUser().getUserId(), raw.user().getUserId());
                            assertEquals(oracle.getUser().getCity(), raw.user().getCity());
                            assertEquals(oracle.getUser().getVipLevel(), raw.user().getVipLevel());
                        }
                    }
                    case 1 -> { long v = pickVarint64(rng); raw.setOrderId(v); oracle.setOrderId(v); }
                    case 2 -> { String s = pickString(rng, "buyer"); raw.setBuyerName(s); oracle.setBuyerName(s); }
                    case 3 -> { int v = pickInt32(rng); raw.setStatus(v); oracle.setStatus(v); }
                    case 4 -> { long v = rng.nextLong(); raw.setUpdatedAtFixed64(v); oracle.setUpdatedAtFixed64(v); }
                    case 5 -> {
                        int sub = rng.nextInt(5);
                        switch (sub) {
                            case 0 -> { long v = pickVarint64(rng); raw.user().setUserId(v); oracle.getUserBuilder().setUserId(v); }
                            case 1 -> { String s = pickString(rng, "CT"); raw.user().setCity(s); oracle.getUserBuilder().setCity(s); }
                            case 2 -> { int v = pickInt32(rng); raw.user().setVipLevel(v); oracle.getUserBuilder().setVipLevel(v); }
                            case 3 -> { double d = rng.nextInt(1000) / 4.0; raw.user().setScore(d); oracle.getUserBuilder().setScore(d); }
                            case 4 -> { String s = pickString(rng, "mail"); raw.user().setEmail(s); oracle.getUserBuilder().setEmail(s); }
                        }
                    }
                    case 6, 7 -> {
                        int cnt = oracle.getItemsCount();
                        if (cnt > 0) {
                            int idx = rng.nextInt(cnt);
                            int sub = rng.nextInt(5);
                            switch (sub) {
                                case 0 -> { long v = pickVarint64(rng); raw.items(idx).setItemId(v); oracle.getItemsBuilder(idx).setItemId(v); }
                                case 1 -> { String s = pickString(rng, "SKU"); raw.items(idx).setSku(s); oracle.getItemsBuilder(idx).setSku(s); }
                                case 2 -> { int v = pickInt32(rng); raw.items(idx).setQuantity(v); oracle.getItemsBuilder(idx).setQuantity(v); }
                                case 3 -> { long v = pickVarint64(rng); raw.items(idx).setPriceCents(v); oracle.getItemsBuilder(idx).setPriceCents(v); }
                                case 4 -> { String s = pickString(rng, "desc"); raw.items(idx).setDescription(s); oracle.getItemsBuilder(idx).setDescription(s); }
                            }
                        }
                    }
                    case 8 -> {
                        if (oracle.getItemsCount() < 160) {
                            long id = 50000L + step;
                            String sku = pickString(rng, "NEW");
                            int qty = pickInt32(rng);
                            raw.addItem().setItemId(id).setSku(sku).setQuantity(qty);
                            oracle.addItems(LineItem.newBuilder().setItemId(id).setSku(sku).setQuantity(qty).build());
                        }
                    }
                    case 9 -> {
                        int len = rng.nextInt(64);
                        byte[] p = new byte[len];
                        rng.nextBytes(p);
                        raw.setPayloadBytes(p);
                        oracle.setPayload(com.google.protobuf.ByteString.copyFrom(p));
                    }
                    case 10 -> { raw.clearAuthToken(); oracle.clearAuthToken(); }
                    case 11 -> { String s = pickString(rng, "tok"); raw.setAuthToken(s); oracle.setAuthToken(s); }
                    case 12 -> {
                        String t = pickString(rng, "tr");
                        long c = pickVarint64(rng);
                        raw.setTraceId(t).setTotalCents(c);
                        oracle.setTraceId(t).setTotalCents(c);
                    }
                    case 13 -> {
                        if (raw.isClean() && oracle.getItemsCount() > 0) {
                            int idx = rng.nextInt(oracle.getItemsCount());
                            raw.readItemEager(idx, eagerScratch);
                            assertEquals(oracle.getItems(idx).getItemId(), eagerScratch.itemId);
                            assertEquals(oracle.getItems(idx).getQuantity(), eagerScratch.quantity);
                        }
                    }
                    case 14 -> {
                        byte[] nextIn = corpus[rng.nextInt(corpus.length)];
                        raw.wrap(nextIn, 0, nextIn.length);
                        oracle = OrderEnvelope.parseFrom(nextIn).toBuilder();
                    }
                    case 15 -> verifyRawOrderParity(raw, oracle.build(), reusableOut, seed, step);
                }
            }
            verifyRawOrderParity(raw, oracle.build(), reusableOut, seed, 999);
        }
    }

    private static void verifyRawOrderParity(
            RawOrderEnvelope raw, OrderEnvelope expected, byte[] reusableOut, int seed, int step) throws Exception {
        int maxSz = raw.maxSerializedSize();
        byte[] arr = raw.toByteArray();
        assertTrue(arr.length <= maxSz, "seed=" + seed + " step=" + step + " actual=" + arr.length + " > max=" + maxSz);
        int written = raw.writeToBuffer(reusableOut, 0);
        assertEquals(arr.length, written);
        assertArrayEquals(arr, Arrays.copyOf(reusableOut, written));
        assertEquals(expected, OrderEnvelope.parseFrom(arr), "RawOrderEnvelope mismatch at seed=" + seed + " step=" + step);
    }

    // =========================================================================
    // 3. Stateful Model-Checking Fuzzer: RawWireView (FieldState State Machine)
    // =========================================================================

    @Test
    @DisplayName("Fuzzer 2: RawWireView Stateful Model-Checking (200 seeds x 30 steps vs Official protoc)")
    void testRawWireViewStatefulFuzzer() throws Exception {
        byte[] complex = LazyProtoCorrectnessTest.createComplexOrderBytes(12, 64);
        byte[] splitUser = buildSplitUserBytes();
        byte[] repItems = OrderEnvelope.newBuilder()
                .setOrderId(100L)
                .setStatus(1)
                .addItems(LineItem.newBuilder().setItemId(1L).setSku("A1").setQuantity(2).build())
                .addItems(LineItem.newBuilder().setItemId(2L).setSku("B2").setQuantity(3).build())
                .build().toByteArray();
        byte[][] corpus = new byte[][] {complex, splitUser, repItems, new byte[0]};

        RawWireView view = new RawWireView();
        byte[] outBuf = new byte[65536];

        for (int seed = 0; seed < 200; seed++) {
            Random rng = new Random(0xBEEF0000L + seed);
            byte[] initial = corpus[rng.nextInt(corpus.length)];
            view.wrap(initial, 0, initial.length);
            OrderEnvelope.Builder oracle = OrderEnvelope.parseFrom(initial).toBuilder();
            List<RawWireView> detachedChildren = new ArrayList<>();

            for (int step = 0; step < 30; step++) {
                int action = rng.nextInt(14);
                switch (action) {
                    case 0 -> {
                        assertEquals(oracle.getOrderId(), view.getInt64(1));
                        assertEquals(oracle.getBuyerName(), view.getString(2));
                        assertEquals(oracle.getStatus(), view.getInt32(3));
                        assertEquals(oracle.getUpdatedAtFixed64(), view.getFixed64(4));
                        assertEquals(oracle.getAuthToken(), view.getString(8));
                    }
                    case 1 -> { long v = pickVarint64(rng); view.setInt64(1, v); oracle.setOrderId(v); }
                    case 2 -> { String s = pickString(rng, "B"); view.setString(2, s); oracle.setBuyerName(s); }
                    case 3 -> { int v = pickInt32(rng); view.setInt32(3, v); oracle.setStatus(v); }
                    case 4 -> { long v = rng.nextLong(); view.setFixed64(4, v); oracle.setUpdatedAtFixed64(v); }
                    case 5 -> {
                        int sub = rng.nextInt(3);
                        if (sub == 0) {
                            long uid = pickVarint64(rng);
                            view.sub(5).setInt64(1, uid);
                            oracle.getUserBuilder().setUserId(uid);
                        } else if (sub == 1) {
                            String city = pickString(rng, "SH");
                            view.sub(5).setString(2, city);
                            oracle.getUserBuilder().setCity(city);
                        } else {
                            int vip = pickInt32(rng);
                            view.sub(5).setInt32(3, vip);
                            oracle.getUserBuilder().setVipLevel(vip);
                        }
                    }
                    case 6 -> {
                        UserProfile newUser = UserProfile.newBuilder()
                                .setUserId(1000L + rng.nextInt(500))
                                .setCity(pickString(rng, "W"))
                                .setVipLevel(rng.nextInt(10))
                                .build();
                        byte[] ub = newUser.toByteArray();
                        view.sub(5).wrap(ub, 0, ub.length);
                        oracle.setUser(newUser);
                    }
                    case 7 -> {
                        detachedChildren.add(view.sub(5));
                        UserProfile newUser = UserProfile.newBuilder()
                                .setUserId(rng.nextBoolean() ? 0L : 77L)
                                .setVipLevel(rng.nextInt(20))
                                .build();
                        byte[] ub = newUser.toByteArray();
                        view.setBytes(5, ub, 0, ub.length);
                        oracle.setUser(newUser);
                    }
                    case 8 -> {
                        if (rng.nextBoolean()) {
                            detachedChildren.add(view.sub(5));
                            view.erase(5);
                            oracle.clearUser();
                        } else {
                            view.erase(8);
                            oracle.clearAuthToken();
                        }
                    }
                    case 9 -> {
                        int repCnt = view.repeatedCount(6);
                        if (repCnt > 0 && repCnt == oracle.getItemsCount()) {
                            int idx = rng.nextInt(repCnt);
                            if (rng.nextBoolean()) {
                                int q = pickInt32(rng);
                                view.repeatedSub(6, idx).setInt32(3, q);
                                oracle.getItemsBuilder(idx).setQuantity(q);
                            } else {
                                String sku = pickString(rng, "S");
                                view.repeatedSub(6, idx).setString(2, sku);
                                oracle.getItemsBuilder(idx).setSku(sku);
                            }
                        }
                    }
                    case 10 -> {
                        if (view.repeatedCount(6) > 0) {
                            detachedChildren.add(view.repeatedSub(6, 0));
                        }
                        LineItem single = LineItem.newBuilder()
                                .setItemId(999L)
                                .setQuantity(rng.nextInt(500))
                                .setSku(pickString(rng, "ONE"))
                                .build();
                        byte[] ib = single.toByteArray();
                        view.setBytes(6, ib, 0, ib.length);
                        oracle.clearItems().addItems(single);
                    }
                    case 11 -> {
                        if (!detachedChildren.isEmpty()) {
                            RawWireView stale = detachedChildren.get(rng.nextInt(detachedChildren.size()));
                            stale.setInt32(3, 123456);
                        }
                    }
                    case 12 -> {
                        int highFn = rng.nextBoolean() ? 65537 : 536870911;
                        view.setInt64(highFn, 42L + step);
                        assertEquals(42L + step, view.getInt64(highFn));
                        byte[] withHigh = view.toByteArray();
                        RawWireView checkHigh = new RawWireView().wrap(withHigh, 0, withHigh.length);
                        assertEquals(42L + step, checkHigh.getInt64(highFn));
                        assertEquals(oracle.getOrderId(), checkHigh.getInt64(1));
                        view.erase(highFn);
                    }
                    case 13 -> verifyRawWireViewParity(view, oracle.build(), outBuf, seed, step);
                }
            }
            verifyRawWireViewParity(view, oracle.build(), outBuf, seed, 999);
        }
    }

    private static void verifyRawWireViewParity(
            RawWireView view, OrderEnvelope expected, byte[] outBuf, int seed, int step) throws Exception {
        int maxSz = view.maxSerializedSize();
        byte[] arr = view.toByteArray();
        assertTrue(arr.length <= maxSz, "RawWireView seed=" + seed + " step=" + step + " actual=" + arr.length + " > max=" + maxSz);
        int written = view.writeToBuffer(outBuf, 0);
        assertEquals(arr.length, written);
        assertArrayEquals(arr, Arrays.copyOf(outBuf, written));

        OrderEnvelope actual = OrderEnvelope.parseFrom(arr);
        assertEquals(expected.getOrderId(), actual.getOrderId());
        assertEquals(expected.getBuyerName(), actual.getBuyerName());
        assertEquals(expected.getStatus(), actual.getStatus());
        assertEquals(expected.getUpdatedAtFixed64(), actual.getUpdatedAtFixed64());
        assertEquals(expected.hasUser(), actual.hasUser());
        assertEquals(expected.getUser(), actual.getUser());
        assertEquals(expected.getItemsList(), actual.getItemsList());
        assertEquals(expected.getAuthToken(), actual.getAuthToken());
        assertEquals(expected.getTraceId(), actual.getTraceId());
        assertEquals(expected.getTotalCents(), actual.getTotalCents());
    }

    private static byte[] buildSplitUserBytes() {
        byte[] seg1 = UserProfile.newBuilder().setUserId(11L).setCity("SH").build().toByteArray();
        byte[] seg2 = UserProfile.newBuilder().setVipLevel(5).setEmail("a@b.c").build().toByteArray();
        byte[] buf = new byte[128];
        WireWriter w = new WireWriter(buf, 0);
        w.writeTag(1, WireFormat.WIRETYPE_VARINT);
        w.writeVarint64(777L);
        w.writeLengthDelimitedBytes(5, seg1, 0, seg1.length);
        w.writeTag(3, WireFormat.WIRETYPE_VARINT);
        w.writeVarint32(2);
        w.writeLengthDelimitedBytes(5, seg2, 0, seg2.length);
        return Arrays.copyOf(buf, w.position());
    }

    private static long pickVarint64(Random rng) {
        return switch (rng.nextInt(6)) {
            case 0 -> 0L;
            case 1 -> 1L + rng.nextInt(126);
            case 2 -> 128L + rng.nextInt(16000);
            case 3 -> 1_000_000_000L + rng.nextInt(1000);
            case 4 -> -1L;
            default -> Long.MAX_VALUE;
        };
    }

    private static int pickInt32(Random rng) {
        return switch (rng.nextInt(6)) {
            case 0 -> 0;
            case 1 -> 1 + rng.nextInt(126);
            case 2 -> 128 + rng.nextInt(50000);
            case 3 -> -1;
            case 4 -> -1000 - rng.nextInt(5000);
            default -> Integer.MIN_VALUE;
        };
    }

    private static String pickString(Random rng, String prefix) {
        return switch (rng.nextInt(6)) {
            case 0 -> "";
            case 1 -> "AB";
            case 2 -> prefix + "_" + rng.nextInt(100);
            case 3 -> prefix + "_long_string_" + "x".repeat(40 + rng.nextInt(120));
            case 4 -> "中文UTF8_" + rng.nextInt(10);
            default -> "alice_wonderland";
        };
    }
}
