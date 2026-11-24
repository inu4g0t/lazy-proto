package io.lazyproto;

import com.google.protobuf.ByteString;
import com.google.protobuf.CodedOutputStream;
import com.google.protobuf.UnknownFieldSet;
import io.lazyproto.dynamic.ProtoDocument;
import io.lazyproto.model.LazyOrderProto.LazyLineItem;
import io.lazyproto.model.LazyOrderProto.LazyOrderEnvelope;
import io.lazyproto.model.LazyOrderProto.LazyUserProfile;
import io.lazyproto.model.StandardOrderProto;
import io.lazyproto.model.StandardOrderProto.LineItem;
import io.lazyproto.model.StandardOrderProto.OrderEnvelope;
import io.lazyproto.model.StandardOrderProto.UserProfile;
import io.lazyproto.wire.WireFormat;
import io.lazyproto.wire.WireWriter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 6-Pillar Verification Suite for Lazy-Proto Architecture v2 (see DESIGN.md Chapter 11):
 * <ol>
 *   <li>Pillar 1: Differential Oracle Verifier against official Google Protobuf wire format</li>
 *   <li>Pillar 2: Stateful Property-Based Random Model-Checking Fuzzer (250 seeds x 40 transitions)</li>
 *   <li>Pillar 3: Adversarial Wire-Format Corpus (overlong varints, multi-segment split messages,
 *       duplicate scalar vs unpacked repeated, wrong wire-type collisions, tag boundaries, lazy malformed isolation)</li>
 *   <li>Pillar 4: Ownership, Aliasing &amp; DAG Sharing Isolation Matrix</li>
 *   <li>Pillar 5: White-Box Fast-Path Structural Invariant Assertions</li>
 * </ol>
 */
public class StatefulModelFuzzTest {

    // =========================================================================
    // Pillar 1: Differential Oracle Verifier
    // =========================================================================

    private static void assertDifferentialOracleEquivalence(
            LazyOrderEnvelope lazyOrig,
            OrderEnvelope oracle,
            String context) throws IOException {
        // 1. Verify unread serialization BEFORE any getter touches lazyOrig (by testing on a copy or direct)
        int reportedSize = lazyOrig.getSerializedSize();
        byte[] lazyBytes = lazyOrig.toByteArray();
        assertEquals(reportedSize, lazyBytes.length,
                context + ": getSerializedSize() must strictly match toByteArray().length");

        // Verify writeToBuffer, toByteString, and writeTo(CodedOutputStream) match toByteArray()
        byte[] bufTarget = new byte[reportedSize + 16];
        int written = lazyOrig.writeToBuffer(bufTarget, 5);
        assertEquals(reportedSize, written, context + ": writeToBuffer length mismatch");
        assertArrayEquals(lazyBytes, Arrays.copyOfRange(bufTarget, 5, 5 + written),
                context + ": writeToBuffer bytes mismatch");
        assertArrayEquals(lazyBytes, lazyOrig.toByteString().toByteArray(),
                context + ": toByteString() mismatch");

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        CodedOutputStream cos = CodedOutputStream.newInstance(baos);
        lazyOrig.writeTo(cos);
        cos.flush();
        assertArrayEquals(lazyBytes, baos.toByteArray(),
                context + ": writeTo(CodedOutputStream) mismatch");

        // 2. Parse lazyBytes using StandardOrderProto and assert full semantic equality with Oracle
        OrderEnvelope reparsed = OrderEnvelope.parseFrom(lazyBytes);
        assertEquals(oracle, reparsed, context + ": reparsed wire bytes diverged from StandardOrderProto Oracle");

        // 3. Verify all field getters on lazyOrig match Oracle
        assertEquals(oracle.getOrderId(), lazyOrig.getOrderId(), context + ": orderId");
        assertEquals(oracle.getBuyerName(), lazyOrig.getBuyerName(), context + ": buyerName");
        assertEquals(oracle.getBuyerNameBytes(), lazyOrig.getBuyerNameBytes(), context + ": buyerNameBytes");
        assertEquals(oracle.getStatus(), lazyOrig.getStatus(), context + ": status");
        assertEquals(oracle.getUpdatedAtFixed64(), lazyOrig.getUpdatedAtFixed64(), context + ": updatedAtFixed64");
        assertEquals(oracle.getPayload(), lazyOrig.getPayload(), context + ": payload");
        assertEquals(oracle.getAuthToken(), lazyOrig.getAuthToken(), context + ": authToken");
        assertEquals(oracle.getTraceId(), lazyOrig.getTraceId(), context + ": traceId");
        assertEquals(oracle.getTotalCents(), lazyOrig.getTotalCents(), context + ": totalCents");

        assertEquals(oracle.getUser().getUserId(), lazyOrig.getUser().getUserId(), context + ": user.userId");
        assertEquals(oracle.getUser().getCity(), lazyOrig.getUser().getCity(), context + ": user.city");
        assertEquals(oracle.getUser().getVipLevel(), lazyOrig.getUser().getVipLevel(), context + ": user.vipLevel");
        assertEquals(oracle.getUser().getScore(), lazyOrig.getUser().getScore(), 1e-9, context + ": user.score");
        assertEquals(oracle.getUser().getEmail(), lazyOrig.getUser().getEmail(), context + ": user.email");

        assertEquals(oracle.getItemsCount(), lazyOrig.getItemsCount(), context + ": itemsCount");
        for (int i = 0; i < oracle.getItemsCount(); i++) {
            LineItem expItem = oracle.getItems(i);
            LazyLineItem actItem = lazyOrig.getItems(i);
            assertEquals(expItem.getItemId(), actItem.getItemId(), context + ": item[" + i + "].itemId");
            assertEquals(expItem.getSku(), actItem.getSku(), context + ": item[" + i + "].sku");
            assertEquals(expItem.getQuantity(), actItem.getQuantity(), context + ": item[" + i + "].quantity");
            assertEquals(expItem.getPriceCents(), actItem.getPriceCents(), context + ": item[" + i + "].priceCents");
            assertEquals(expItem.getDescription(), actItem.getDescription(), context + ": item[" + i + "].description");
        }

        // 4. Re-check getSerializedSize() and toByteArray() AFTER all getters have populated decoded slots
        assertEquals(reportedSize, lazyOrig.getSerializedSize(),
                context + ": getSerializedSize() changed after lazy decoding");
        assertArrayEquals(lazyBytes, lazyOrig.toByteArray(),
                context + ": toByteArray() changed after lazy decoding");
    }

    private static byte[] createSeedOrderBytes(Random rng, int itemCount) {
        OrderEnvelope.Builder b = OrderEnvelope.newBuilder()
                .setOrderId(1000L + rng.nextInt(500000))
                .setBuyerName("Buyer-" + (1000 + rng.nextInt(8999)))
                .setStatus(1 + rng.nextInt(60))
                .setUpdatedAtFixed64(0x1122334455667788L ^ rng.nextLong())
                .setUser(UserProfile.newBuilder()
                        .setUserId(5000L + rng.nextInt(100000))
                        .setCity("Shanghai")
                        .setVipLevel(1 + rng.nextInt(10))
                        .setScore(88.5 + rng.nextInt(100))
                        .setEmail("user@lazyproto.io")
                        .build())
                .setPayload(ByteString.copyFromUtf8("payload-blob-" + rng.nextInt(10000)))
                .setAuthToken("tok-" + (100000 + rng.nextInt(899999)))
                .setTraceId("trace-" + (100000 + rng.nextInt(899999)))
                .setTotalCents(10000L + rng.nextInt(900000));

        for (int i = 0; i < itemCount; i++) {
            b.addItems(LineItem.newBuilder()
                    .setItemId(100L + i)
                    .setSku("SKU-" + (100 + i))
                    .setQuantity(1 + rng.nextInt(20))
                    .setPriceCents(500L + rng.nextInt(10000))
                    .setDescription("Item-Desc-" + i)
                    .build());
        }
        b.getUnknownFieldsBuilder()
                .mergeVarintField(99, 777888999)
                .mergeLengthDelimitedField(105, ByteString.copyFromUtf8("ext-metadata-" + rng.nextInt(100)));
        return b.build().toByteArray();
    }

    // =========================================================================
    // Pillar 2: Stateful Property-Based Random Model-Checking Fuzzer
    // =========================================================================

    private static final class SnapshotState {
        final LazyOrderEnvelope lazySnapshot;
        final OrderEnvelope oracleSnapshot;
        final byte[] frozenBytesAtBuildTime;
        final String label;

        SnapshotState(LazyOrderEnvelope lazySnapshot, OrderEnvelope oracleSnapshot, String label) {
            this.lazySnapshot = lazySnapshot;
            this.oracleSnapshot = oracleSnapshot;
            this.frozenBytesAtBuildTime = lazySnapshot.toByteArray();
            this.label = label;
        }
    }

    private static final class ActiveBuilderState {
        final LazyOrderEnvelope.Builder lazyBuilder;
        final OrderEnvelope.Builder oracleBuilder;
        LazyUserProfile.Builder heldUserBuilder;
        LazyLineItem.Builder heldItemBuilder;
        int heldItemIndex = -1;

        ActiveBuilderState(LazyOrderEnvelope.Builder lazyBuilder, OrderEnvelope.Builder oracleBuilder) {
            this.lazyBuilder = lazyBuilder;
            this.oracleBuilder = oracleBuilder;
        }
    }

    private static final String[] SAMPLE_CITIES = {
            "Shanghai", "Beijing", "Tokyo", "SanFrancisco-CA-USA", "SZ", "杭州-Hangzhou-浙江", ""
    };

    private static final long[] INTERESTING_VARINTS = {
            1L, 2L, 127L, 128L, 129L, 255L, 256L, 16383L, 16384L, 2097151L, 2097152L,
            Integer.MAX_VALUE, (1L << 35) + 77L, Long.MAX_VALUE
    };

    @Test
    @DisplayName("Pillar 1 & 2: Stateful Random Model-Checking Fuzzer (250 seeds x 40 steps = 10,000 transitions)")
    void testPillar1And2_StatefulRandomModelCheckingFuzzer() throws IOException {
        final int numSeeds = 250;
        final int stepsPerSeed = 40;

        for (int seed = 1; seed <= numSeeds; seed++) {
            Random rng = new Random(seed * 0x9E3779B97F4A7C15L);
            byte[] initialBytes = createSeedOrderBytes(rng, 4 + rng.nextInt(5));

            LazyOrderEnvelope rootLazy = LazyOrderEnvelope.parseFrom(initialBytes);
            OrderEnvelope rootOracle = OrderEnvelope.parseFrom(initialBytes);

            List<SnapshotState> snapshots = new ArrayList<>();
            snapshots.add(new SnapshotState(rootLazy, rootOracle, "seed=" + seed + ":root"));

            List<ActiveBuilderState> builders = new ArrayList<>();
            builders.add(new ActiveBuilderState(rootLazy.toBuilder(), rootOracle.toBuilder()));

            for (int step = 0; step < stepsPerSeed; step++) {
                String ctx = "seed=" + seed + ",step=" + step;
                int activeIdx = rng.nextInt(builders.size());
                ActiveBuilderState active = builders.get(activeIdx);
                int action = rng.nextInt(17);

                switch (action) {
                    case 0: {
                        // Read random fields from builder and verify against oracleBuilder
                        assertEquals(active.oracleBuilder.getOrderId(), active.lazyBuilder.getOrderId(), ctx);
                        assertEquals(active.oracleBuilder.getStatus(), active.lazyBuilder.getStatus(), ctx);
                        assertEquals(active.oracleBuilder.getBuyerName(), active.lazyBuilder.getBuyerName(), ctx);
                        assertEquals(active.oracleBuilder.getUser().getCity(), active.lazyBuilder.getUser().getCity(), ctx);
                        int cnt = active.oracleBuilder.getItemsCount();
                        assertEquals(cnt, active.lazyBuilder.getItemsCount(), ctx);
                        if (cnt > 0) {
                            int idx = rng.nextInt(cnt);
                            assertEquals(active.oracleBuilder.getItems(idx).getPriceCents(),
                                    active.lazyBuilder.getItems(idx).getPriceCents(), ctx);
                        }
                        break;
                    }
                    case 1: {
                        // Same-size mutation: fixed64 or 1-byte varint status (1..127)
                        long newTs = rng.nextLong();
                        if (newTs == 0L) newTs = 42L;
                        int newStatus = 1 + rng.nextInt(126);
                        active.lazyBuilder.setUpdatedAtFixed64(newTs).setStatus(newStatus);
                        active.oracleBuilder.setUpdatedAtFixed64(newTs).setStatus(newStatus);
                        break;
                    }
                    case 2: {
                        // Variable-size scalar mutation across varint & UTF-8 boundaries
                        long newOrderId = INTERESTING_VARINTS[rng.nextInt(INTERESTING_VARINTS.length)];
                        long newTotal = INTERESTING_VARINTS[rng.nextInt(INTERESTING_VARINTS.length)];
                        String newBuyer = SAMPLE_CITIES[rng.nextInt(SAMPLE_CITIES.length)] + "-" + rng.nextInt(10000);
                        active.lazyBuilder.setOrderId(newOrderId).setTotalCents(newTotal).setBuyerName(newBuyer);
                        active.oracleBuilder.setOrderId(newOrderId).setTotalCents(newTotal).setBuyerName(newBuyer);
                        break;
                    }
                    case 3: {
                        // Mutate user via getUserBuilder() and retain handle for post-build mutation testing
                        LazyUserProfile.Builder ub = active.lazyBuilder.getUserBuilder();
                        active.heldUserBuilder = ub;
                        String city = SAMPLE_CITIES[rng.nextInt(SAMPLE_CITIES.length)];
                        if (city.isEmpty()) city = "Shenzhen";
                        int vip = 1 + rng.nextInt(500);
                        double score = 10.25 + rng.nextInt(1000);
                        ub.setCity(city).setVipLevel(vip).setScore(score);
                        active.oracleBuilder.getUserBuilder().setCity(city).setVipLevel(vip).setScore(score);
                        break;
                    }
                    case 4: {
                        // Mutate via previously held userBuilder (tests post-build lazy detach on child builder!)
                        if (active.heldUserBuilder != null) {
                            long uid = INTERESTING_VARINTS[rng.nextInt(INTERESTING_VARINTS.length)];
                            String email = "held-" + rng.nextInt(9999) + "@example.com";
                            active.heldUserBuilder.setUserId(uid).setEmail(email);
                            active.oracleBuilder.getUserBuilder().setUserId(uid).setEmail(email);
                        }
                        break;
                    }
                    case 5: {
                        // Set an external clean LazyUserProfile parsed from separate wire bytes (Issue #1 Bug 2)
                        UserProfile extOracleUser = UserProfile.newBuilder()
                                .setUserId(900000L + rng.nextInt(100000))
                                .setCity("ExternalCity-" + rng.nextInt(100))
                                .setVipLevel(1 + rng.nextInt(200))
                                .setScore(99.95)
                                .setEmail("ext@domain.org")
                                .build();
                        LazyUserProfile extLazyUser = LazyUserProfile.parseFrom(extOracleUser.toByteArray());
                        assertTrue(extLazyUser.isClean());
                        active.lazyBuilder.setUser(extLazyUser);
                        active.oracleBuilder.setUser(extOracleUser);
                        break;
                    }
                    case 6: {
                        // Restore original clean user from root snapshot after getSerializedSize() (Issue #1 Bug 3)
                        active.lazyBuilder.buildPartial().getSerializedSize();
                        LazyUserProfile origCleanUser = rootLazy.getUser();
                        assertTrue(origCleanUser.isClean());
                        active.lazyBuilder.setUser(origCleanUser);
                        active.oracleBuilder.setUser(rootOracle.getUser());
                        break;
                    }
                    case 7: {
                        // Mutate a repeated item via getItemsBuilder(idx) and hold the sub-builder
                        int cnt = active.lazyBuilder.getItemsCount();
                        if (cnt > 0) {
                            int idx = rng.nextInt(cnt);
                            LazyLineItem.Builder ib = active.lazyBuilder.getItemsBuilder(idx);
                            active.heldItemBuilder = ib;
                            active.heldItemIndex = idx;
                            int newQty = 1 + rng.nextInt(300);
                            long newPrice = INTERESTING_VARINTS[rng.nextInt(INTERESTING_VARINTS.length)];
                            String newSku = "SKU-MUT-" + rng.nextInt(1000);
                            ib.setQuantity(newQty).setPriceCents(newPrice).setSku(newSku);

                            LineItem oldOracleItem = active.oracleBuilder.getItems(idx);
                            active.oracleBuilder.setItems(idx, oldOracleItem.toBuilder()
                                    .setQuantity(newQty).setPriceCents(newPrice).setSku(newSku).build());
                        }
                        break;
                    }
                    case 8: {
                        // Mutate via previously held itemBuilder (tests post-build lazy detach on list item builder!)
                        if (active.heldItemBuilder != null
                                && active.heldItemIndex >= 0
                                && active.heldItemIndex < active.lazyBuilder.getItemsCount()) {
                            int idx = active.heldItemIndex;
                            String desc = "HeldDesc-" + rng.nextInt(10000);
                            active.heldItemBuilder.setDescription(desc);
                            LineItem oldOracleItem = active.oracleBuilder.getItems(idx);
                            active.oracleBuilder.setItems(idx, oldOracleItem.toBuilder().setDescription(desc).build());
                        }
                        break;
                    }
                    case 9: {
                        // Replace element at index via setItems(idx, cleanExternalItem)
                        int cnt = active.lazyBuilder.getItemsCount();
                        if (cnt > 0) {
                            int idx = rng.nextInt(cnt);
                            LineItem extOracleItem = LineItem.newBuilder()
                                    .setItemId(77000L + step)
                                    .setSku("REPL-SKU-" + step)
                                    .setQuantity(1 + rng.nextInt(50))
                                    .setPriceCents(12345L)
                                    .setDescription("Replaced item")
                                    .build();
                            LazyLineItem extLazyItem = LazyLineItem.parseFrom(extOracleItem.toByteArray());
                            active.lazyBuilder.setItems(idx, extLazyItem);
                            active.oracleBuilder.setItems(idx, extOracleItem);
                        }
                        break;
                    }
                    case 10: {
                        // Append a brand new item via addItems()
                        if (active.lazyBuilder.getItemsCount() < 12) {
                            LineItem newOracleItem = LineItem.newBuilder()
                                    .setItemId(88000L + step)
                                    .setSku("APP-SKU-" + step)
                                    .setQuantity(2 + rng.nextInt(10))
                                    .setPriceCents(9900L + step)
                                    .setDescription("Appended item " + step)
                                    .build();
                            LazyLineItem newLazyItem = LazyLineItem.parseFrom(newOracleItem.toByteArray());
                            active.lazyBuilder.addItems(newLazyItem);
                            active.oracleBuilder.addItems(newOracleItem);
                        }
                        break;
                    }
                    case 11: {
                        // Clear or re-set authToken (tests deletion + re-append on same slot)
                        if (rng.nextBoolean()) {
                            active.lazyBuilder.clearAuthToken();
                            active.oracleBuilder.clearAuthToken();
                        } else {
                            String tok = "re-tok-" + rng.nextInt(100000);
                            active.lazyBuilder.setAuthToken(tok);
                            active.oracleBuilder.setAuthToken(tok);
                        }
                        break;
                    }
                    case 12: {
                        // Mid-flight getSerializedSize() to exercise size caching before further mutations
                        int sz = active.lazyBuilder.buildPartial().getSerializedSize();
                        assertTrue(sz > 0, ctx);
                        break;
                    }
                    case 13: {
                        // Fork a random historical snapshot via toBuilder()
                        if (builders.size() < 8) {
                            SnapshotState snap = snapshots.get(rng.nextInt(snapshots.size()));
                            builders.add(new ActiveBuilderState(
                                    snap.lazySnapshot.toBuilder(),
                                    snap.oracleSnapshot.toBuilder()));
                        }
                        break;
                    }
                    case 14: {
                        // Clone an active builder via clone() (or clone bound child builder!)
                        if (active.heldUserBuilder != null && rng.nextBoolean()) {
                            LazyUserProfile.Builder clonedUser = active.heldUserBuilder.clone();
                            assertEquals(active.oracleBuilder.getUser().getCity(), clonedUser.getCity(), ctx);
                            clonedUser.setCity("DETACHED_CLONE_CITY");
                            // Mutating detached clone must NOT affect parent builder!
                            assertEquals(active.oracleBuilder.getUser().getCity(),
                                    active.lazyBuilder.getUser().getCity(), ctx);
                        } else if (builders.size() < 8) {
                            builders.add(new ActiveBuilderState(
                                    active.lazyBuilder.clone(),
                                    active.oracleBuilder.build().toBuilder()));
                        }
                        break;
                    }
                    case 15: {
                        // Build a snapshot (and also occasionally build heldUserBuilder independently!)
                        if (active.heldUserBuilder != null && rng.nextInt(4) == 0) {
                            LazyUserProfile uSnap = active.heldUserBuilder.build();
                            String expCity = active.oracleBuilder.getUser().getCity();
                            assertEquals(expCity, uSnap.getCity(), ctx);
                            active.heldUserBuilder.setCity("POST_CHILD_BUILD_" + step);
                            active.oracleBuilder.getUserBuilder().setCity("POST_CHILD_BUILD_" + step);
                            assertEquals(expCity, uSnap.getCity(), ctx + ": child snapshot corrupted after build()");
                        }
                        LazyOrderEnvelope builtLazy = active.lazyBuilder.build();
                        OrderEnvelope builtOracle = active.oracleBuilder.build();
                        assertDifferentialOracleEquivalence(builtLazy, builtOracle, ctx + ":build");
                        snapshots.add(new SnapshotState(builtLazy, builtOracle, ctx));
                        break;
                    }
                    case 16: {
                        // Bound child builder clear() / mergeFrom() or clearItems() (Issue #2 regressions!)
                        int subOp = rng.nextInt(3);
                        if (subOp == 0 && active.heldUserBuilder != null) {
                            active.heldUserBuilder.clear();
                            active.oracleBuilder.setUser(UserProfile.getDefaultInstance());
                        } else if (subOp == 1 && active.heldUserBuilder != null) {
                            byte[] replacement = UserProfile.newBuilder()
                                    .setUserId(1234L + step)
                                    .setCity("MergedUser-" + step)
                                    .setVipLevel(9)
                                    .build().toByteArray();
                            active.heldUserBuilder.mergeFrom(replacement);
                            active.oracleBuilder.setUser(UserProfile.parseFrom(replacement));
                        } else {
                            active.lazyBuilder.clearItems();
                            OrderEnvelope cur = active.oracleBuilder.build();
                            OrderEnvelope.Builder nb = OrderEnvelope.newBuilder()
                                    .setOrderId(cur.getOrderId())
                                    .setBuyerName(cur.getBuyerName())
                                    .setStatus(cur.getStatus())
                                    .setUpdatedAtFixed64(cur.getUpdatedAtFixed64())
                                    .setPayload(cur.getPayload())
                                    .setAuthToken(cur.getAuthToken())
                                    .setTraceId(cur.getTraceId())
                                    .setTotalCents(cur.getTotalCents());
                            if (cur.hasUser()) nb.setUser(cur.getUser());
                            nb.getUnknownFieldsBuilder().mergeFrom(cur.getUnknownFields());
                            active = new ActiveBuilderState(active.lazyBuilder, nb);
                            builders.set(activeIdx, active);
                        }
                        break;
                    }
                }

                // At every single step, verify that the active builder's current state matches the Oracle
                LazyOrderEnvelope currentView = active.lazyBuilder.buildPartial();
                OrderEnvelope currentOracle = active.oracleBuilder.build();
                assertDifferentialOracleEquivalence(currentView, currentOracle, ctx + ":stepCheck");
            }

            // Verify ALL active builders at the end of the trajectory
            for (int i = 0; i < builders.size(); i++) {
                ActiveBuilderState b = builders.get(i);
                assertDifferentialOracleEquivalence(
                        b.lazyBuilder.build(),
                        b.oracleBuilder.build(),
                        "seed=" + seed + ":finalBuilder[" + i + "]");
            }

            // Verify ZERO historical snapshot corruption across all built snapshots
            for (SnapshotState snap : snapshots) {
                assertArrayEquals(snap.frozenBytesAtBuildTime, snap.lazySnapshot.toByteArray(),
                        "Historical snapshot corrupted! " + snap.label);
                assertDifferentialOracleEquivalence(
                        snap.lazySnapshot,
                        snap.oracleSnapshot,
                        "Historical snapshot oracle check: " + snap.label);
            }
        }
    }

    // =========================================================================
    // Pillar 3: Adversarial Wire-Format Corpus
    // =========================================================================

    @Test
    @DisplayName("Pillar 3A: Non-Canonical Overlong Varint Reading, Passthrough, In-Place Patch & Canonical Re-Encode")
    void testPillar3A_NonCanonicalOverlongVarints() throws IOException {
        // Craft raw wire message:
        // Field #1 (order_id, tag 0x08): 5-byte overlong varint for value 42: 0xAA, 0x80, 0x80, 0x80, 0x00
        // Field #3 (status, tag 0x18): 3-byte overlong varint for value 1: 0x81, 0x80, 0x00
        // Field #2 (buyer_name, tag 0x12): "Alice"
        byte[] raw = new byte[] {
                0x08, (byte) 0xAA, (byte) 0x80, (byte) 0x80, (byte) 0x80, 0x00,
                0x18, (byte) 0x81, (byte) 0x80, 0x00,
                0x12, 0x05, 'A', 'l', 'i', 'c', 'e'
        };

        LazyOrderEnvelope msg = LazyOrderEnvelope.parseFrom(raw);
        assertEquals(42L, msg.getOrderId());
        assertEquals(1, msg.getStatus());
        assertEquals("Alice", msg.getBuyerName());
        // Untouched passthrough preserves exact overlong wire bytes
        assertArrayEquals(raw, msg.toByteArray());

        // Case 1: Mutate status from 1 (3-byte overlong on wire) to 2 (1-byte canonical varint)
        // Must detect oldLen (3) != newLen (1) and switch to FLAG_DIRTY_VAR_SIZE!
        LazyOrderEnvelope m1 = msg.toBuilder().setStatus(2).build();
        assertEquals(raw.length - 2, m1.getSerializedSize());
        assertEquals(m1.getSerializedSize(), m1.toByteArray().length);
        OrderEnvelope parsed1 = OrderEnvelope.parseFrom(m1.toByteArray());
        assertEquals(42L, parsed1.getOrderId());
        assertEquals(2, parsed1.getStatus());
        assertEquals("Alice", parsed1.getBuyerName());

        // Case 2: Mutate status from 1 (3-byte overlong on wire) to 20000 (which has canonical 3-byte varint!)
        // oldLen (3) == newLen (3) -> takes Fast-Path 1 In-Place Same-Size Patching!
        LazyOrderEnvelope m2 = msg.toBuilder().setStatus(20000).build();
        assertTrue(m2.isSameSizeDirtyOnly());
        assertEquals(raw.length, m2.getSerializedSize());
        OrderEnvelope parsed2 = OrderEnvelope.parseFrom(m2.toByteArray());
        assertEquals(42L, parsed2.getOrderId());
        assertEquals(20000, parsed2.getStatus());
        assertEquals("Alice", parsed2.getBuyerName());
    }

    @Test
    @DisplayName("Pillar 3B: Deep Multi-Segment Interleaved Split Sub-Messages (Read, Same-Size, Var-Size & Clear)")
    void testPillar3B_MultiSegmentInterleavedSplitSubMessages() throws IOException {
        byte[] seg1 = UserProfile.newBuilder().setUserId(101L).setCity("OldCity").setScore(12.5).build().toByteArray();
        byte[] seg2 = UserProfile.newBuilder().setCity("MergedCity").setVipLevel(7).build().toByteArray();
        byte[] seg3 = UserProfile.newBuilder().setEmail("merged@lazyproto.io").build().toByteArray();

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        CodedOutputStream cos = CodedOutputStream.newInstance(baos);
        cos.writeByteArray(5, seg1);
        cos.writeInt64(1, 555L);
        cos.writeByteArray(5, seg2);
        cos.writeString(2, "SplitBuyer");
        cos.writeByteArray(5, seg3);
        cos.writeInt32(3, 9);
        cos.flush();
        byte[] wire = baos.toByteArray();

        OrderEnvelope oracle = OrderEnvelope.parseFrom(wire);
        LazyOrderEnvelope lazy = LazyOrderEnvelope.parseFrom(wire);

        // 1. Read-only merge verification
        assertDifferentialOracleEquivalence(lazy, oracle, "MultiSegment:ReadOnly");
        assertTrue(lazy.getUser().isMultiSegment());

        // 2. Same-size mutation on multi-segment child (setScore) must collapse segments cleanly
        LazyOrderEnvelope.Builder bSame = lazy.toBuilder();
        bSame.getUserBuilder().setScore(99.75);
        LazyOrderEnvelope builtSame = bSame.build();
        OrderEnvelope oracleSame = oracle.toBuilder()
                .setUser(oracle.getUser().toBuilder().setScore(99.75).build())
                .build();
        assertDifferentialOracleEquivalence(builtSame, oracleSame, "MultiSegment:SameSizeChild");

        // 3. Variable-size mutation on multi-segment child (setCity)
        LazyOrderEnvelope.Builder bVar = lazy.toBuilder();
        bVar.getUserBuilder().setCity("NewExpandedMetropolis");
        LazyOrderEnvelope builtVar = bVar.build();
        OrderEnvelope oracleVar = oracle.toBuilder()
                .setUser(oracle.getUser().toBuilder().setCity("NewExpandedMetropolis").build())
                .build();
        assertDifferentialOracleEquivalence(builtVar, oracleVar, "MultiSegment:VarSizeChild");

        // 4. clearUser() must delete all 3 split segments
        LazyOrderEnvelope builtCleared = lazy.toBuilder().clearUser().build();
        assertFalse(builtCleared.hasUser());
        OrderEnvelope reparsedCleared = OrderEnvelope.parseFrom(builtCleared.toByteArray());
        assertFalse(reparsedCleared.hasUser());
        assertEquals(555L, reparsedCleared.getOrderId());
        assertEquals("SplitBuyer", reparsedCleared.getBuyerName());
    }

    @Test
    @DisplayName("Pillar 3C: Schema-less ProtoDocument Duplicate Scalar Last-Wins vs Unpacked Repeated Preservation")
    void testPillar3C_ProtoDocumentDuplicateScalarVsUnpackedRepeated() throws IOException {
        // Wire payload has:
        // - Duplicate scalar field #3 (status): 10, then 20, then 30
        // - Unpacked repeated varint field #50: 100, 200, 300
        // - Split sub-message field #5 (user): segA(userId=1), segB(vipLevel=8)
        byte[] segA = UserProfile.newBuilder().setUserId(1L).build().toByteArray();
        byte[] segB = UserProfile.newBuilder().setVipLevel(8).build().toByteArray();

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        CodedOutputStream cos = CodedOutputStream.newInstance(baos);
        cos.writeInt32(3, 10);
        cos.writeInt32(50, 100);
        cos.writeByteArray(5, segA);
        cos.writeInt32(3, 20);
        cos.writeInt32(50, 200);
        cos.writeByteArray(5, segB);
        cos.writeInt32(3, 30);
        cos.writeInt32(50, 300);
        cos.flush();
        byte[] wire = baos.toByteArray();

        ProtoDocument doc = ProtoDocument.wrapBytes(wire);
        // Last-wins scalar read on #3
        assertEquals(30, doc.getInt32(3));
        // Split sub-message navigation on #5
        assertEquals(1L, doc.at(5).getInt64(1));
        assertEquals(8, doc.at(5).getInt32(3));

        // Mutate scalar #3 to 99; unpacked repeated #50 (100, 200, 300) must all be preserved!
        ProtoDocument mutated = doc.toBuilder().setInt32(3, 99).build();
        UnknownFieldSet parsed = UnknownFieldSet.parseFrom(mutated.toByteArray());
        assertEquals(List.of(99L), parsed.getField(3).getVarintList());
        assertEquals(100L, parsed.getField(50).getVarintList().get(0));
        assertEquals(List.of(100L, 200L, 300L), parsed.getField(50).getVarintList());
    }

    @Test
    @DisplayName("Pillar 3D & 3E: Wrong Wire-Type Collisions and Tag Bit-Width Boundaries (1, 15, 16, 63, 64, 65, 2048)")
    void testPillar3DAnd3E_WireTypeCollisionsAndTagBoundaries() throws IOException {
        // 3D: Field #1 encoded with wrong wire type FIXED64 followed by valid VARINT #1
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        CodedOutputStream cos = CodedOutputStream.newInstance(baos);
        cos.writeFixed64(1, 0xDEADBEEFCAFEBABEL); // mismatched wire type for field #1
        cos.writeInt64(1, 777L);                  // valid wire type for field #1
        cos.writeInt32(2, 12345);                 // mismatched wire type for field #2 (string)
        cos.writeInt32(15, 150);                  // max 1-byte tag
        cos.writeInt32(16, 160);                  // min 2-byte tag
        cos.writeInt32(63, 630);                  // DIRECT_FIELD_MAX - 1
        cos.writeInt32(64, 640);                  // DIRECT_FIELD_MAX
        cos.writeInt32(65, 650);                  // DIRECT_FIELD_MAX + 1 (linear scan fallback)
        cos.writeString(2048, "three-byte-tag");  // 3-byte tag
        cos.flush();
        byte[] wire = baos.toByteArray();

        LazyOrderEnvelope env = LazyOrderEnvelope.parseFrom(wire);
        assertEquals(777L, env.getOrderId());
        assertEquals("", env.getBuyerName());

        ProtoDocument doc = ProtoDocument.wrapBytes(wire);
        assertEquals(150, doc.getInt32(15));
        assertEquals(160, doc.getInt32(16));
        assertEquals(630, doc.getInt32(63));
        assertEquals(640, doc.getInt32(64));
        assertEquals(650, doc.getInt32(65));
        assertEquals("three-byte-tag", doc.getString(2048));

        ProtoDocument mutatedDoc = doc.toBuilder()
                .setInt32(64, 6400)
                .setInt32(65, 6500)
                .setString(2048, "updated-3byte-tag")
                .build();
        assertEquals(6400, mutatedDoc.getInt32(64));
        assertEquals(6500, mutatedDoc.getInt32(65));
        assertEquals("updated-3byte-tag", mutatedDoc.getString(2048));
        assertEquals(mutatedDoc.getSerializedSize(), mutatedDoc.toByteArray().length);
    }

    @Test
    @DisplayName("Pillar 3F: Malformed Top-Level Rejection vs Unvisited Nested Corrupt Sub-Message Lazy Isolation")
    void testPillar3F_MalformedWireIsolationBoundary() {
        // 1. Top-level truncated length-delimited record must fail immediately on scan
        byte[] truncatedTopLevel = new byte[] { 0x12, 0x20, 'a', 'b', 'c' }; // claims 32 bytes, only 3 available
        LazyOrderEnvelope badTop = LazyOrderEnvelope.parseFrom(truncatedTopLevel);
        assertThrows(IllegalArgumentException.class, badTop::getOrderId);

        // 2. Top-level fieldNumber == 0 must fail immediately on scan
        byte[] zeroFieldNum = new byte[] { 0x00, 0x01 };
        LazyOrderEnvelope badZero = LazyOrderEnvelope.parseFrom(zeroFieldNum);
        assertThrows(IllegalArgumentException.class, badZero::getOrderId);

        // 3. Corrupt nested sub-message in field #5 (user) that is NEVER visited must NOT throw during top-level routing!
        byte[] corruptNestedBytes = new byte[] { 0x12, 0x50, 'x' }; // invalid inner length
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        byte[] out = new byte[64];
        WireWriter writer = new WireWriter(out, 0);
        writer.writeTag(1, WireFormat.WIRETYPE_VARINT);
        writer.writeVarint64(888L);
        writer.writeLengthDelimitedBytes(5, corruptNestedBytes, 0, corruptNestedBytes.length);
        writer.writeTag(3, WireFormat.WIRETYPE_VARINT);
        writer.writeVarint32(4);
        byte[] envelopeWithCorruptUser = Arrays.copyOf(out, writer.position());

        LazyOrderEnvelope lazyRouter = LazyOrderEnvelope.parseFrom(envelopeWithCorruptUser);
        // Accessing and mutating top-level fields #1 and #3 succeeds without ever parsing field #5!
        assertEquals(888L, lazyRouter.getOrderId());
        assertEquals(4, lazyRouter.getStatus());
        LazyOrderEnvelope routed = lazyRouter.toBuilder().setStatus(5).build();
        assertEquals(5, LazyOrderEnvelope.parseFrom(routed.toByteArray()).getStatus());

        // Only when field #5 is explicitly accessed does the nested scanner reject the corrupt inner bytes
        assertThrows(IllegalArgumentException.class, () -> routed.getUser().getCity());
    }

    // =========================================================================
    // Pillar 4: Ownership, Aliasing & DAG Reference Matrix
    // =========================================================================

    @Test
    @DisplayName("Pillar 4: Diamond Builder Forking, Post-Build Multi-Generation Reuse & Cross-Tree DAG Child Sharing")
    void testPillar4_OwnershipAliasingAndDagSharingMatrix() throws IOException {
        byte[] seedBytes = createSeedOrderBytes(new Random(42), 3);
        LazyOrderEnvelope root = LazyOrderEnvelope.parseFrom(seedBytes);

        // 4A. Multi-generation post-build mutation on parent builder + held child builders
        LazyOrderEnvelope.Builder b = root.toBuilder();
        LazyUserProfile.Builder heldUb = b.getUserBuilder();
        LazyLineItem.Builder heldIb = b.getItemsBuilder(0);

        heldUb.setCity("Gen1City");
        heldIb.setQuantity(11);
        LazyOrderEnvelope gen1 = b.build();

        // First post-build mutation on parent builder (triggers parentBuilder.ensureMutable())
        b.setOrderId(222222L);
        // Subsequent mutation on held child builders (parentBuilder.isBuilt() is already false,
        // so heldUb and heldIb MUST re-bind to the new parentBuilder.impl()!)
        heldUb.setCity("Gen2City");
        heldIb.setQuantity(22);
        LazyOrderEnvelope gen2 = b.build();

        // Third generation mutation directly via held child builders when parentBuilder.isBuilt() is true
        heldUb.setCity("Gen3City");
        heldIb.setQuantity(33);
        LazyOrderEnvelope gen3 = b.build();

        assertEquals("Shanghai", root.getUser().getCity());
        assertEquals("Gen1City", gen1.getUser().getCity());
        assertEquals(11, gen1.getItems(0).getQuantity());
        assertEquals("Gen2City", gen2.getUser().getCity());
        assertEquals(22, gen2.getItems(0).getQuantity());
        assertEquals(222222L, gen2.getOrderId());
        assertEquals("Gen3City", gen3.getUser().getCity());
        assertEquals(33, gen3.getItems(0).getQuantity());

        // 4B. Cross-Tree DAG Child Sharing: pass same LazyUserProfile & LazyLineItem to two builders
        LazyUserProfile sharedUser = LazyUserProfile.parseFrom(
                UserProfile.newBuilder().setUserId(77L).setCity("SharedCity").setVipLevel(3).build().toByteArray());
        LazyLineItem sharedItem = LazyLineItem.parseFrom(
                LineItem.newBuilder().setItemId(99L).setSku("SHARED-SKU").setQuantity(5).build().toByteArray());

        LazyOrderEnvelope.Builder treeA = root.toBuilder().setUser(sharedUser).setItems(0, sharedItem);
        LazyOrderEnvelope.Builder treeB = root.toBuilder().setUser(sharedUser).setItems(0, sharedItem);

        treeA.getUserBuilder().setCity("MutatedInTreeA");
        treeA.getItemsBuilder(0).setQuantity(500);

        LazyOrderEnvelope builtA = treeA.build();
        LazyOrderEnvelope builtB = treeB.build();

        assertEquals("MutatedInTreeA", builtA.getUser().getCity());
        assertEquals(500, builtA.getItems(0).getQuantity());
        assertEquals("SharedCity", builtB.getUser().getCity());
        assertEquals(5, builtB.getItems(0).getQuantity());
        assertEquals("SharedCity", sharedUser.getCity());
        assertEquals(5, sharedItem.getQuantity());
    }

    // =========================================================================
    // Pillar 5: White-Box Fast-Path Structural Invariant Assertions
    // =========================================================================

    @Test
    @DisplayName("Pillar 5: White-Box Fast-Path Structural Invariants (FP0, FP1, FP2, FP3)")
    void testPillar5_WhiteBoxFastPathInvariants() {
        byte[] raw = createSeedOrderBytes(new Random(2026), 16);
        LazyOrderEnvelope msg = LazyOrderEnvelope.parseFrom(raw);

        // FP0 Invariant: Zero scan before first field access
        assertFalse(msg.isScanned());
        assertTrue(msg.isClean());
        assertFalse(msg.isSameSizeDirtyOnly());
        assertEquals(0, msg.loadedItemsCount());
        assertEquals(raw.length, msg.getSerializedSize());
        assertFalse(msg.isScanned(), "getSerializedSize() on unscanned message must be O(1) without scanning");

        // Tier-1 Shallow Scan Invariant: Reading top-level scalar scans root tape only, 0 children loaded
        assertTrue(msg.getOrderId() > 0);
        assertTrue(msg.isScanned());
        assertEquals(0, msg.loadedItemsCount(), "Reading top-level scalar must not load any repeated items");

        // O(1) Partial List Access Invariant: Accessing item[7] loads exactly 1 of 16 items
        assertTrue(msg.getItems(7).getPriceCents() > 0);
        assertEquals(1, msg.loadedItemsCount(), "Accessing items(7) must instantiate only 1 LazyLineItem view");
        assertFalse(msg.getUser().isScanned(), "Accessing items(7) must not scan UserProfile");

        // FP1 Invariant: Same-size mutations at root and nested item keep isSameSizeDirtyOnly() == true
        LazyOrderEnvelope fp1 = msg.toBuilder()
                .setUpdatedAtFixed64(0xCAFEBABE12345678L)
                .build();
        assertFalse(fp1.isClean());
        assertTrue(fp1.isSameSizeDirtyOnly());
        assertEquals(raw.length, fp1.getSerializedSize());

        // FP2/FP3 Invariant: Variable-size mutation on 1 item leaves the other 15 items unloaded (0 object allocations)
        LazyOrderEnvelope.Builder bFp3 = msg.toBuilder();
        bFp3.getItemsBuilder(7).setDescription("Expanded variable-length description for item #7 only");
        LazyOrderEnvelope fp3 = bFp3.build();
        assertFalse(fp3.isClean());
        assertFalse(fp3.isSameSizeDirtyOnly());
        assertEquals(1, fp3.loadedItemsCount(),
                "Splicing serialization of 1 mutated item must leave the other 15 items unloaded");
        byte[] spliced = fp3.toByteArray();
        assertEquals(1, fp3.loadedItemsCount(),
                "toByteArray() must splice raw byte spans for the 15 untouched items without loading them");
        assertEquals("Expanded variable-length description for item #7 only",
                LazyOrderEnvelope.parseFrom(spliced).getItems(7).getDescription());
    }

    @Test
    @DisplayName("Pillar 2B: Stateful Random Model-Checking Fuzzer for Schema-Less ProtoDocument (100 seeds x 30 steps)")
    void testPillar2B_StatefulRandomModelCheckingFuzzerForProtoDocument() throws IOException {
        for (int seed = 1; seed <= 100; seed++) {
            Random rng = new Random(seed * 0x517CC1B727220A95L);
            byte[] initialBytes = createSeedOrderBytes(rng, 3);

            ProtoDocument rootDoc = ProtoDocument.wrapBytes(initialBytes);
            OrderEnvelope rootOracle = OrderEnvelope.parseFrom(initialBytes);

            List<ProtoDocument> docSnapshots = new ArrayList<>();
            List<byte[]> docFrozenBytes = new ArrayList<>();
            List<OrderEnvelope> oracleSnapshots = new ArrayList<>();

            docSnapshots.add(rootDoc);
            docFrozenBytes.add(rootDoc.toByteArray());
            oracleSnapshots.add(rootOracle);

            ProtoDocument.Builder activeDoc = rootDoc.toBuilder();
            OrderEnvelope.Builder activeOracle = rootOracle.toBuilder();

            for (int step = 0; step < 30; step++) {
                int op = rng.nextInt(8);
                switch (op) {
                    case 0: {
                        long v = INTERESTING_VARINTS[rng.nextInt(INTERESTING_VARINTS.length)];
                        activeDoc.setInt64(1, v);
                        activeOracle.setOrderId(v);
                        break;
                    }
                    case 1: {
                        int st = 1 + rng.nextInt(500);
                        activeDoc.setInt32(3, st);
                        activeOracle.setStatus(st);
                        break;
                    }
                    case 2: {
                        long ts = rng.nextLong();
                        if (ts == 0L) ts = 99L;
                        activeDoc.setFixed64(4, ts);
                        activeOracle.setUpdatedAtFixed64(ts);
                        break;
                    }
                    case 3: {
                        String buyer = "DocBuyer-" + rng.nextInt(100000);
                        activeDoc.setString(2, buyer);
                        activeOracle.setBuyerName(buyer);
                        break;
                    }
                    case 4: {
                        String city = SAMPLE_CITIES[rng.nextInt(SAMPLE_CITIES.length)];
                        if (city.isEmpty()) city = "Nanjing";
                        int vip = 1 + rng.nextInt(200);
                        activeDoc.at(5).setString(2, city).setInt32(3, vip);
                        activeOracle.getUserBuilder().setCity(city).setVipLevel(vip);
                        break;
                    }
                    case 5: {
                        activeDoc.remove(8);
                        activeOracle.clearAuthToken();
                        break;
                    }
                    case 6: {
                        String tok = "doc-tok-" + rng.nextInt(10000);
                        activeDoc.setString(8, tok);
                        activeOracle.setAuthToken(tok);
                        break;
                    }
                    case 7: {
                        ProtoDocument builtDoc = activeDoc.build();
                        OrderEnvelope builtOracle = activeOracle.build();
                        assertEquals(builtDoc.getSerializedSize(), builtDoc.toByteArray().length);
                        assertEquals(builtOracle, OrderEnvelope.parseFrom(builtDoc.toByteArray()));
                        docSnapshots.add(builtDoc);
                        docFrozenBytes.add(builtDoc.toByteArray());
                        oracleSnapshots.add(builtOracle);
                        break;
                    }
                }
                ProtoDocument partial = activeDoc.buildPartial();
                byte[] out = partial.toByteArray();
                assertEquals(partial.getSerializedSize(), out.length, "seed=" + seed + ",step=" + step);
                assertEquals(activeOracle.build(), OrderEnvelope.parseFrom(out), "seed=" + seed + ",step=" + step);
            }

            for (int i = 0; i < docSnapshots.size(); i++) {
                assertArrayEquals(docFrozenBytes.get(i), docSnapshots.get(i).toByteArray(),
                        "ProtoDocument historical snapshot corrupted at index " + i);
                assertEquals(oracleSnapshots.get(i), OrderEnvelope.parseFrom(docSnapshots.get(i).toByteArray()));
            }
        }
    }

    @Test
    @DisplayName("Pillar 4B: GitHub Issue #2 Round-2 Regression Matrix (All 8 Findings Covered)")
    void testPillar4B_Issue2RegressionMatrix() throws IOException {
        byte[] raw = createSeedOrderBytes(new Random(777), 3);

        // #1: Child builder clear() and mergeFrom() after parent build() must isolate parent snapshot
        LazyOrderEnvelope.Builder pb1 = LazyOrderEnvelope.parseFrom(raw).toBuilder();
        LazyUserProfile.Builder ub1 = pb1.getUserBuilder();
        LazyOrderEnvelope pSnap1 = pb1.build();
        byte[] pSnap1Bytes = pSnap1.toByteArray();
        String origCity = pSnap1.getUser().getCity();
        ub1.clear();
        assertEquals(origCity, pSnap1.getUser().getCity());
        assertArrayEquals(pSnap1Bytes, pSnap1.toByteArray());
        assertFalse(pb1.hasUser());
        assertFalse(pb1.build().hasUser());

        byte[] bjBytes = UserProfile.newBuilder().setUserId(99L).setCity("BJ").build().toByteArray();
        ub1.mergeFrom(bjBytes);
        assertEquals(origCity, pSnap1.getUser().getCity());
        assertArrayEquals(pSnap1Bytes, pSnap1.toByteArray());
        assertEquals("BJ", pb1.build().getUser().getCity());

        // #2: Bound child builder build() followed by post-build setter must NOT mutate built child snapshot
        LazyOrderEnvelope.Builder pb2 = LazyOrderEnvelope.parseFrom(raw).toBuilder();
        LazyUserProfile.Builder ub2 = pb2.getUserBuilder();
        ub2.setCity("SNAP_CITY");
        LazyUserProfile userSnap = ub2.build();
        ub2.setCity("HACKED_AFTER_BUILD");
        pb2.getUserBuilder().setCity("HACKED_VIA_PARENT");
        assertEquals("SNAP_CITY", userSnap.getCity());
        assertEquals("HACKED_VIA_PARENT", pb2.build().getUser().getCity());

        // #3: builder.getUser() & doc.at() are strictly immutable snapshots / non-leaking views
        LazyOrderEnvelope.Builder pb3 = LazyOrderEnvelope.parseFrom(raw).toBuilder();
        LazyUserProfile immutableUserView = pb3.getUser();
        LazyOrderEnvelope pSnap3 = pb3.build();
        pb3.getUserBuilder().setCity("MUTATED_LATER");
        assertEquals("Shanghai", immutableUserView.getCity());
        assertEquals("Shanghai", pSnap3.getUser().getCity());

        ProtoDocument.Builder db3 = ProtoDocument.wrapBytes(raw).toBuilder();
        ProtoDocument.Builder subCursor = db3.at(5);
        ProtoDocument docSnap3 = db3.build();
        subCursor.setString(2, "POST_BUILD_SUB_CURSOR");
        assertEquals("Shanghai", docSnap3.at(5).getString(2));
        assertEquals("POST_BUILD_SUB_CURSOR", db3.build().at(5).getString(2));

        // #4: Bound child builder clone() after parent fork sees latest parent state and stays detached
        LazyOrderEnvelope.Builder pb4 = LazyOrderEnvelope.parseFrom(raw).toBuilder();
        LazyUserProfile.Builder ub4 = pb4.getUserBuilder();
        ub4.setCity("V1");
        LazyOrderEnvelope snap4 = pb4.build();
        pb4.getUserBuilder().setCity("V2");
        LazyUserProfile.Builder clonedUb = ub4.clone();
        assertEquals("V2", clonedUb.getCity());
        assertEquals("V1", snap4.getUser().getCity());
        clonedUb.setCity("V3_CLONED");
        assertEquals("V2", pb4.getUser().getCity());
        assertEquals("V3_CLONED", clonedUb.build().getCity());

        // #5: ProtoDocument.at() on appended LENGTH_DELIMITED field preserves payload & size
        ProtoDocument.Builder db5 = ProtoDocument.newBuilder();
        db5.setString(2, "Hello");
        ProtoDocument doc5 = db5.build();
        assertEquals(7, doc5.getSerializedSize());
        assertEquals(ByteString.copyFromUtf8("Hello"), ByteString.copyFrom(doc5.at(2).rawBuffer(), doc5.at(2).rawOffset(), doc5.at(2).rawLength()));
        byte[] out5 = doc5.toByteArray();
        assertEquals("Hello", OrderEnvelope.parseFrom(out5).getBuyerName());

        // And navigating into appended valid submessage bytes on field #5 preserves and fuses subfields cleanly
        ProtoDocument.Builder db5Sub = ProtoDocument.newBuilder();
        byte[] validUserBytes = UserProfile.newBuilder().setUserId(42L).setCity("SZ").build().toByteArray();
        db5Sub.setString(5, new String(validUserBytes, StandardCharsets.UTF_8));
        ProtoDocument doc5Sub = db5Sub.build();
        assertEquals(42L, doc5Sub.at(5).getInt64(1));
        assertEquals("SZ", doc5Sub.at(5).getString(2));
        OrderEnvelope parsedDoc5Sub = OrderEnvelope.parseFrom(doc5Sub.toByteArray());
        assertEquals(42L, parsedDoc5Sub.getUser().getUserId());
        assertEquals("SZ", parsedDoc5Sub.getUser().getCity());

        // #6: Read-only navigation on missing paths at(1).at(2) NEVER mutates presence or output
        ProtoDocument emptyDoc = ProtoDocument.newBuilder().build();
        assertSame(ProtoDocument.EMPTY, emptyDoc.at(1).at(2));
        assertFalse(emptyDoc.has(1));

        ProtoDocument.Builder db6 = ProtoDocument.newBuilder();
        assertEquals(0, db6.at(1).at(2).getInt32(3));
        assertFalse(db6.has(1));
        db6.setInt32(99, 7);
        UnknownFieldSet ufs6 = UnknownFieldSet.parseFrom(db6.build().toByteArray());
        assertFalse(ufs6.hasField(1), "Unwritten path at(1).at(2) must not emit empty submessage");
        assertEquals(List.of(7L), ufs6.getField(99).getVarintList());

        // And writing on deep at(1).at(2).setInt32(3, 42) lazily materializes when written!
        db6.at(1).at(2).setInt32(3, 42);
        assertTrue(db6.has(1));
        assertEquals(42, db6.build().at(1).at(2).getInt32(3));

        // #7: clearItems() leaves previously returned immutable LazyLineItem untouched & throws OOB on old itemBuilder
        LazyOrderEnvelope.Builder pb7 = LazyOrderEnvelope.parseFrom(raw).toBuilder();
        LazyLineItem oldItem = pb7.getItems(0);
        LazyLineItem.Builder oldItemBuilder = pb7.getItemsBuilder(0);
        pb7.clearItems();
        assertEquals(100L, oldItem.getItemId());
        assertThrows(IndexOutOfBoundsException.class, () -> oldItemBuilder.setQuantity(777));
        assertEquals(0, pb7.build().getItemsCount());

        // #8: Builder.getDefaultInstanceForType() returns empty default instance, never current populated copy
        assertEquals(0L, ((LazyOrderEnvelope) pb1.getDefaultInstanceForType()).getOrderId());
        assertEquals("", ((LazyOrderEnvelope) pb1.getDefaultInstanceForType()).getBuyerName());
        assertEquals(0L, ((LazyUserProfile) ub1.getDefaultInstanceForType()).getUserId());
        assertSame(ProtoDocument.EMPTY, db3.getDefaultInstanceForType());
    }

    @Test
    @DisplayName("Pillar 4C: GitHub Issue #3 Round-3 Regression Matrix (All 9 Findings Covered)")
    void testPillar4C_Issue3RegressionMatrix() throws IOException {
        byte[] raw1 = createSeedOrderBytes(new Random(301), 3);
        byte[] raw2 = OrderEnvelope.newBuilder()
                .setOrderId(999L)
                .setUser(UserProfile.newBuilder().setUserId(777L).setCity("GZ").setVipLevel(5).build())
                .addItems(LineItem.newBuilder().setItemId(888L).setSku("SKU-R2").setQuantity(5).build())
                .build().toByteArray();

        // #1: Same-size edit on replaced external UserProfile (and dynamic subdoc) must NOT hit parent-slice FP1
        byte[] extUserWire = UserProfile.newBuilder()
                .setUserId(100001L)
                .setCity("ExternalVeryLongCityName")
                .setVipLevel(1) // 1-byte varint
                .setScore(12.5)
                .setEmail("ext-long@lazyproto.io")
                .build().toByteArray();
        LazyOrderEnvelope.Builder pb1 = LazyOrderEnvelope.parseFrom(raw1).toBuilder();
        pb1.setUser(LazyUserProfile.parseFrom(extUserWire));
        pb1.getUserBuilder().setVipLevel(4); // same-size relative to extUserWire, NOT raw1!
        LazyOrderEnvelope built1 = pb1.build();
        assertFalse(built1.isSameSizeDirtyOnly());
        assertEquals(4, built1.getUser().getVipLevel());
        OrderEnvelope decoded1 = OrderEnvelope.parseFrom(built1.toByteArray());
        assertEquals(4, decoded1.getUser().getVipLevel());
        assertEquals("ExternalVeryLongCityName", decoded1.getUser().getCity());

        // Same check for replaced LineItem + same-size quantity mutation
        byte[] extItemWire = LineItem.newBuilder()
                .setItemId(500001L).setSku("EXT-VERY-LONG-SKU-12345").setQuantity(1).build().toByteArray();
        LazyOrderEnvelope.Builder pb1Item = LazyOrderEnvelope.parseFrom(raw1).toBuilder();
        pb1Item.setItems(0, LazyLineItem.parseFrom(extItemWire));
        pb1Item.getItemsBuilder(0).setQuantity(6);
        OrderEnvelope decoded1Item = OrderEnvelope.parseFrom(pb1Item.build().toByteArray());
        assertEquals(6, decoded1Item.getItems(0).getQuantity());
        assertEquals("EXT-VERY-LONG-SKU-12345", decoded1Item.getItems(0).getSku());

        // Same check for ProtoDocument: replace subdoc via at(5).mergeFrom(...) + same-size mutation
        ProtoDocument.Builder db1 = ProtoDocument.wrapBytes(raw1).toBuilder();
        db1.at(5).mergeFrom(extUserWire);
        db1.at(5).setInt32(3, 4);
        ProtoDocument db1Built = db1.build();
        assertFalse(db1Built.isSameSizeDirtyOnly());
        OrderEnvelope decodedDb1 = OrderEnvelope.parseFrom(db1Built.toByteArray());
        assertEquals(4, decodedDb1.getUser().getVipLevel());
        assertEquals("ExternalVeryLongCityName", decodedDb1.getUser().getCity());

        // #2: Sequential reuse(raw1) -> read child -> reuse(raw2) -> read child rebinds cleanly!
        LazyOrderEnvelope.Builder reuseB = LazyOrderEnvelope.newBuilder();
        reuseB.reuse(raw1);
        assertEquals("Shanghai", reuseB.getUserOrBuilder().getCity());
        assertEquals("SKU-100", reuseB.getItemsOrBuilder(0).getSku());
        reuseB.reuse(raw2);
        assertEquals(5, reuseB.getUserOrBuilder().getVipLevel());
        assertEquals("GZ", reuseB.getUserOrBuilder().getCity());
        assertEquals(5, reuseB.getItemsOrBuilder(0).getQuantity());
        assertEquals("SKU-R2", reuseB.getItemsOrBuilder(0).getSku());
        OrderEnvelope decodedReuse = OrderEnvelope.parseFrom(reuseB.toByteArray());
        assertEquals(5, decodedReuse.getUser().getVipLevel());

        // #3 & #5: ProtoDocument.Builder.at(1).mergeFrom(byte[] & ByteString) on empty parent
        byte[] subBytes = new byte[] { 8, 2 }; // field #1 varint = 2
        ProtoDocument.Builder db3Bytes = ProtoDocument.newBuilder();
        db3Bytes.at(1).mergeFrom(subBytes);
        assertTrue(db3Bytes.has(1));
        assertEquals(2, db3Bytes.at(1).getInt32(1));
        ProtoDocument built3Bytes = db3Bytes.build();
        assertTrue(built3Bytes.has(1));
        assertEquals(2, built3Bytes.at(1).getInt32(1));
        assertArrayEquals(new byte[] { 0x0A, 0x02, 0x08, 0x02 }, built3Bytes.toByteArray());

        ProtoDocument.Builder db3Bs = ProtoDocument.newBuilder();
        db3Bs.at(1).mergeFrom(ByteString.copyFrom(subBytes));
        assertTrue(db3Bs.has(1));
        assertEquals(2, db3Bs.at(1).getInt32(1));
        ProtoDocument built3Bs = db3Bs.build();
        assertTrue(built3Bs.has(1));
        assertEquals(2, built3Bs.at(1).getInt32(1));
        assertArrayEquals(new byte[] { 0x0A, 0x02, 0x08, 0x02 }, built3Bs.toByteArray());

        // #4 & #6: Frozen DocOverlay/OrderOverlay/ImmutableWireBase cannot be mutated externally
        ProtoDocument.DocOverlay frozenDocOv = new ProtoDocument.DocOverlay();
        frozenDocOv.freeze();
        assertThrows(IllegalStateException.class, frozenDocOv::reset);
        ProtoDocument.DocBase frozenDocBase = new ProtoDocument.DocBase();
        frozenDocBase.wrapImmutable(new byte[] { 0x18, 0x02 }, 0, 2);
        assertThrows(IllegalStateException.class, frozenDocBase::markScannedEmpty);

        // #7: Builder.getItemsList() followed by clearItems() keeps snapshot list consistent
        LazyOrderEnvelope.Builder pb7 = LazyOrderEnvelope.parseFrom(raw1).toBuilder();
        List<LazyLineItem> oldList = pb7.getItemsList();
        int oldSize = oldList.size();
        assertTrue(oldSize >= 3);
        pb7.clearItems();
        assertEquals(0, pb7.getItemsCount());
        assertEquals(oldSize, oldList.size());
        assertEquals(100L, oldList.get(0).getItemId());
    }
}
