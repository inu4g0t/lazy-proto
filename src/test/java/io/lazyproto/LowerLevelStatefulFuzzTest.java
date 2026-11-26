package io.lazyproto;

import com.google.protobuf.ByteString;
import com.google.protobuf.UnknownFieldSet;
import io.lazyproto.bench.proto.LineItem;
import io.lazyproto.bench.proto.OrderEnvelope;
import io.lazyproto.bench.proto.UserProfile;
import io.lazyproto.core.RawOrderEnvelope;
import io.lazyproto.core.RawWireView;
import io.lazyproto.wire.WireFormat;
import io.lazyproto.wire.WireWriter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Stateful differential model fuzzing for the Java Lower-Level core
 * ({@link RawOrderEnvelope} and {@link RawWireView}) against official {@code protoc}-generated
 * {@link OrderEnvelope}.
 */
public class LowerLevelStatefulFuzzTest {

    private static final String[] SAMPLE_STRINGS = {
            "", "A", "SH", "BJ", "NYC", "SKU-1", "Shanghai", "SanFrancisco-CA",
            "杭州-Hangzhou-UTF8", "x".repeat(140)
    };

    private static final long[] SAMPLE_VARINTS = {
            0L, 1L, 2L, 7L, 127L, 128L, 129L, 255L, 16383L, 16384L, 65535L,
            (1L << 21) + 3L, (1L << 35) + 9L, Long.MAX_VALUE
    };

    private static byte[] encodeLenField(int fn, byte[] payload) {
        int tag = (fn << 3) | WireFormat.WIRETYPE_LENGTH_DELIMITED;
        byte[] out = new byte[WireWriter.varint32Size(tag) + WireWriter.varint32Size(payload.length) + payload.length];
        WireWriter w = new WireWriter(out, 0);
        w.writeTag(fn, WireFormat.WIRETYPE_LENGTH_DELIMITED);
        w.writeVarint32(payload.length);
        w.writeRawBytes(payload, 0, payload.length);
        return out;
    }

    private static byte[] concat(byte[]... parts) {
        int total = 0;
        for (byte[] p : parts) total += p.length;
        byte[] out = new byte[total];
        int pos = 0;
        for (byte[] p : parts) {
            System.arraycopy(p, 0, out, pos, p.length);
            pos += p.length;
        }
        return out;
    }

    private static byte[] createRandomInitialWire(Random rng, boolean allowLargeItems) {
        int mode = rng.nextInt(10);
        if (mode == 0) {
            return new byte[0];
        }

        OrderEnvelope.Builder b = OrderEnvelope.newBuilder();
        if (rng.nextBoolean()) b.setOrderId(100L + rng.nextInt(50000));
        if (rng.nextBoolean()) b.setBuyerName(SAMPLE_STRINGS[1 + rng.nextInt(SAMPLE_STRINGS.length - 1)]);
        if (rng.nextBoolean()) b.setStatus(1 + rng.nextInt(120));
        if (rng.nextBoolean()) b.setUpdatedAtFixed64(0x1122334455667788L ^ rng.nextLong());
        if (rng.nextBoolean()) {
            b.setUser(UserProfile.newBuilder()
                    .setUserId(10L + rng.nextInt(5000))
                    .setCity(SAMPLE_STRINGS[1 + rng.nextInt(5)])
                    .setVipLevel(1 + rng.nextInt(9))
                    .setScore(12.5 + rng.nextInt(50))
                    .setEmail("u" + rng.nextInt(100) + "@test.io")
                    .build());
        }
        int itemCount = (allowLargeItems && mode == 9) ? (135 + rng.nextInt(25)) : rng.nextInt(6);
        for (int i = 0; i < itemCount; i++) {
            b.addItems(LineItem.newBuilder()
                    .setItemId(1000L + i)
                    .setSku("SKU-" + i)
                    .setQuantity(1 + rng.nextInt(100))
                    .setPriceCents(500L + rng.nextInt(9000))
                    .setDescription("Desc-" + i)
                    .build());
        }
        if (rng.nextBoolean()) {
            byte[] pl = new byte[1 + rng.nextInt(32)];
            rng.nextBytes(pl);
            b.setPayload(ByteString.copyFrom(pl));
        }
        if (rng.nextBoolean()) b.setAuthToken("tok-" + rng.nextInt(9999));
        if (rng.nextBoolean()) b.setTraceId("tr-" + rng.nextInt(9999));
        if (rng.nextBoolean()) b.setTotalCents(1000L + rng.nextInt(100000));

        if (rng.nextBoolean()) {
            b.setUnknownFields(UnknownFieldSet.newBuilder()
                    .addField(50, UnknownFieldSet.Field.newBuilder().addVarint(123456789L + rng.nextInt(1000)).build())
                    .addField(51, UnknownFieldSet.Field.newBuilder()
                            .addLengthDelimited(ByteString.copyFromUtf8("unk-" + rng.nextInt(100))).build())
                    .build());
        }

        byte[] base = b.build().toByteArray();
        // Inject split user submessage (two #5 segments) on mode == 1 or 2
        if (mode == 1 || mode == 2) {
            byte[] seg1 = UserProfile.newBuilder().setUserId(11L + rng.nextInt(100)).setCity("SH").build().toByteArray();
            byte[] seg2 = UserProfile.newBuilder().setVipLevel(1 + rng.nextInt(9)).setEmail("split@io").build().toByteArray();
            return concat(encodeLenField(5, seg1), base, encodeLenField(5, seg2));
        }
        return base;
    }

    private static void assertEnvelopeEquivalent(OrderEnvelope expected, OrderEnvelope actual, String ctx) {
        assertEquals(expected.getOrderId(), actual.getOrderId(), ctx + ": order_id");
        assertEquals(expected.getBuyerName(), actual.getBuyerName(), ctx + ": buyer_name");
        assertEquals(expected.getStatus(), actual.getStatus(), ctx + ": status");
        assertEquals(expected.getUpdatedAtFixed64(), actual.getUpdatedAtFixed64(), ctx + ": updated_at_fixed64");
        assertEquals(expected.hasUser(), actual.hasUser(), ctx + ": has_user");
        if (expected.hasUser()) {
            assertEquals(expected.getUser().getUserId(), actual.getUser().getUserId(), ctx + ": user.user_id");
            assertEquals(expected.getUser().getCity(), actual.getUser().getCity(), ctx + ": user.city");
            assertEquals(expected.getUser().getVipLevel(), actual.getUser().getVipLevel(), ctx + ": user.vip_level");
            assertEquals(Double.doubleToRawLongBits(expected.getUser().getScore()),
                    Double.doubleToRawLongBits(actual.getUser().getScore()), ctx + ": user.score");
            assertEquals(expected.getUser().getEmail(), actual.getUser().getEmail(), ctx + ": user.email");
        }
        assertEquals(expected.getItemsCount(), actual.getItemsCount(), ctx + ": items_count");
        for (int i = 0; i < expected.getItemsCount(); i++) {
            LineItem e = expected.getItems(i);
            LineItem a = actual.getItems(i);
            assertEquals(e.getItemId(), a.getItemId(), ctx + ": item[" + i + "].item_id");
            assertEquals(e.getSku(), a.getSku(), ctx + ": item[" + i + "].sku");
            assertEquals(e.getQuantity(), a.getQuantity(), ctx + ": item[" + i + "].quantity");
            assertEquals(e.getPriceCents(), a.getPriceCents(), ctx + ": item[" + i + "].price_cents");
            assertEquals(e.getDescription(), a.getDescription(), ctx + ": item[" + i + "].description");
        }
        assertEquals(expected.getPayload(), actual.getPayload(), ctx + ": payload");
        assertEquals(expected.getAuthToken(), actual.getAuthToken(), ctx + ": auth_token");
        assertEquals(expected.getTraceId(), actual.getTraceId(), ctx + ": trace_id");
        assertEquals(expected.getTotalCents(), actual.getTotalCents(), ctx + ": total_cents");
        assertEquals(expected.getUnknownFields(), actual.getUnknownFields(), ctx + ": unknown_fields");
    }

    @Test
    @DisplayName("Lower-Level Fuzz 1: RawOrderEnvelope stateful multi-round differential fuzzing vs official protoc")
    void testRawOrderEnvelopeStatefulFuzz() throws Exception {
        RawOrderEnvelope env = new RawOrderEnvelope();
        RawOrderEnvelope.RawLineItem eagerTmp = new RawOrderEnvelope.RawLineItem();

        final int numSeeds = 120;
        final int roundsPerSeed = 4;
        final int stepsPerRound = 25;

        for (int seed = 1; seed <= numSeeds; seed++) {
            Random rng = new Random(seed * 0x517CC1B727220A95L);

            for (int round = 0; round < roundsPerSeed; round++) {
                boolean scratchInit = (round == 3 && rng.nextInt(4) == 0);
                byte[] initWire;
                OrderEnvelope.Builder oracle;
                int rawItemCount;
                if (scratchInit) {
                    initWire = new byte[0];
                    env.initScratch();
                    oracle = OrderEnvelope.newBuilder();
                    rawItemCount = 0;
                } else {
                    initWire = createRandomInitialWire(rng, true);
                    env.wrap(initWire, 0, initWire.length);
                    oracle = OrderEnvelope.parseFrom(initWire).toBuilder();
                    rawItemCount = oracle.getItemsCount();
                }

                // Optionally exercise readItemEager immediately after wrap() before any getter
                if (!scratchInit && rawItemCount > 0 && rng.nextBoolean()) {
                    int idx = rng.nextInt(rawItemCount);
                    env.readItemEager(idx, eagerTmp);
                    LineItem exp = oracle.getItems(idx);
                    assertEquals(exp.getItemId(), eagerTmp.itemId, "seed=" + seed + ",r=" + round + ": eager itemId");
                    assertEquals(exp.getQuantity(), eagerTmp.quantity, "seed=" + seed + ",r=" + round + ": eager qty");
                    assertEquals(exp.getPriceCents(), eagerTmp.priceCents, "seed=" + seed + ",r=" + round + ": eager price");
                    assertEquals(exp.getSku(), new String(initWire, eagerTmp.skuOff, eagerTmp.skuLen, StandardCharsets.UTF_8));
                    assertEquals(exp.getDescription(), new String(initWire, eagerTmp.descOff, eagerTmp.descLen, StandardCharsets.UTF_8));
                }

                boolean doIntermediateGets = rng.nextBoolean();

                for (int step = 0; step < stepsPerRound; step++) {
                    String ctx = "RawOrderEnvelope seed=" + seed + ",round=" + round + ",step=" + step;
                    int op = rng.nextInt(16);
                    switch (op) {
                        case 0 -> {
                            long v = SAMPLE_VARINTS[rng.nextInt(SAMPLE_VARINTS.length)];
                            env.setOrderId(v);
                            oracle.setOrderId(v);
                        }
                        case 1 -> {
                            String s = SAMPLE_STRINGS[rng.nextInt(SAMPLE_STRINGS.length)];
                            env.setBuyerName(s);
                            oracle.setBuyerName(s);
                        }
                        case 2 -> {
                            int st = rng.nextBoolean() ? (1 + rng.nextInt(126)) : (128 + rng.nextInt(50000));
                            env.setStatus(st);
                            oracle.setStatus(st);
                        }
                        case 3 -> {
                            long f = rng.nextLong();
                            env.setUpdatedAtFixed64(f);
                            oracle.setUpdatedAtFixed64(f);
                        }
                        case 4 -> {
                            byte[] pl = new byte[rng.nextInt(40)];
                            rng.nextBytes(pl);
                            env.setPayloadBytes(pl, 0, pl.length);
                            oracle.setPayload(ByteString.copyFrom(pl));
                        }
                        case 5 -> {
                            String tok = SAMPLE_STRINGS[rng.nextInt(SAMPLE_STRINGS.length)];
                            env.setAuthToken(tok);
                            oracle.setAuthToken(tok);
                        }
                        case 6 -> {
                            env.clearAuthToken();
                            oracle.clearAuthToken();
                        }
                        case 7 -> {
                            String tr = SAMPLE_STRINGS[rng.nextInt(SAMPLE_STRINGS.length)];
                            env.setTraceId(tr);
                            oracle.setTraceId(tr);
                        }
                        case 8 -> {
                            long tot = SAMPLE_VARINTS[rng.nextInt(SAMPLE_VARINTS.length)];
                            env.setTotalCents(tot);
                            oracle.setTotalCents(tot);
                        }
                        case 9 -> {
                            RawOrderEnvelope.RawUserProfile u = env.user();
                            UserProfile.Builder ou = oracle.getUserBuilder();
                            int uop = rng.nextInt(5);
                            switch (uop) {
                                case 0 -> { long v = SAMPLE_VARINTS[rng.nextInt(SAMPLE_VARINTS.length)]; u.setUserId(v); ou.setUserId(v); }
                                case 1 -> { String c = SAMPLE_STRINGS[rng.nextInt(SAMPLE_STRINGS.length)]; u.setCity(c); ou.setCity(c); }
                                case 2 -> { int vip = 1 + rng.nextInt(500); u.setVipLevel(vip); ou.setVipLevel(vip); }
                                case 3 -> { double sc = rng.nextInt(1000) * 0.25; u.setScore(sc); ou.setScore(sc); }
                                case 4 -> { String em = SAMPLE_STRINGS[rng.nextInt(SAMPLE_STRINGS.length)]; u.setEmail(em); ou.setEmail(em); }
                            }
                        }
                        case 10, 11 -> {
                            int cnt = oracle.getItemsCount();
                            if (cnt > 0) {
                                int idx = rng.nextInt(cnt);
                                RawOrderEnvelope.RawLineItem it = env.items(idx);
                                LineItem.Builder oit = oracle.getItemsBuilder(idx);
                                int iop = rng.nextInt(5);
                                switch (iop) {
                                    case 0 -> { long v = SAMPLE_VARINTS[rng.nextInt(SAMPLE_VARINTS.length)]; it.setItemId(v); oit.setItemId(v); }
                                    case 1 -> { String s = SAMPLE_STRINGS[rng.nextInt(SAMPLE_STRINGS.length)]; it.setSku(s); oit.setSku(s); }
                                    case 2 -> { int q = 1 + rng.nextInt(500); it.setQuantity(q); oit.setQuantity(q); }
                                    case 3 -> { long p = SAMPLE_VARINTS[rng.nextInt(SAMPLE_VARINTS.length)]; it.setPriceCents(p); oit.setPriceCents(p); }
                                    case 4 -> { String d = SAMPLE_STRINGS[rng.nextInt(SAMPLE_STRINGS.length)]; it.setDescription(d); oit.setDescription(d); }
                                }
                            }
                        }
                        case 12 -> {
                            if (oracle.getItemsCount() < 170) {
                                RawOrderEnvelope.RawLineItem it = env.addItem();
                                LineItem.Builder oit = oracle.addItemsBuilder();
                                long id = 5000L + step;
                                String sku = "APP-" + rng.nextInt(1000);
                                int qty = 1 + rng.nextInt(50);
                                it.setItemId(id).setSku(sku).setQuantity(qty);
                                oit.setItemId(id).setSku(sku).setQuantity(qty);
                            }
                        }
                        case 13 -> {
                            if (doIntermediateGets) {
                                assertEquals(oracle.getOrderId(), env.getOrderId(), ctx);
                                assertEquals(oracle.getBuyerName(), env.getBuyerName(), ctx);
                                assertEquals(oracle.getBuyerName(), env.getBuyerNameSlice().toString(), ctx);
                                assertEquals(oracle.getStatus(), env.getStatus(), ctx);
                                assertEquals(oracle.getUpdatedAtFixed64(), env.getUpdatedAtFixed64(), ctx);
                                assertEquals(oracle.getPayload().size(), env.getPayloadLength(), ctx);
                                assertEquals(oracle.getAuthToken(), env.getAuthToken(), ctx);
                                assertEquals(oracle.getTraceId(), env.getTraceId(), ctx);
                                assertEquals(oracle.getTotalCents(), env.getTotalCents(), ctx);
                                assertEquals(oracle.getItemsCount(), env.getItemsCount(), ctx);
                                if (oracle.hasUser()) {
                                    assertEquals(oracle.getUser().getCity(), env.user().getCity(), ctx);
                                    assertEquals(oracle.getUser().getVipLevel(), env.user().getVipLevel(), ctx);
                                }
                            }
                        }
                        case 14 -> {
                            // Mid-round serialization & exact maxSerializedSize() bound check
                            verifyRawOrderEnvelope(env, oracle.build(), ctx);
                        }
                        case 15 -> {
                            // Mutate buyer/status/user same-size style
                            int st = 1 + rng.nextInt(126);
                            env.setStatus(st);
                            oracle.setStatus(st);
                        }
                    }
                }

                String endCtx = "RawOrderEnvelope seed=" + seed + ",round=" + round + ",end";
                verifyRawOrderEnvelope(env, oracle.build(), endCtx);
            }
        }
    }

    private static void verifyRawOrderEnvelope(RawOrderEnvelope env, OrderEnvelope expected, String ctx) throws Exception {
        int maxSz = env.maxSerializedSize();
        byte[] exactBuf = new byte[maxSz];
        int written = env.writeToBuffer(exactBuf, 0);
        assertTrue(written <= maxSz, ctx + ": written=" + written + " > maxSerializedSize=" + maxSz);
        byte[] out = Arrays.copyOf(exactBuf, written);
        assertArrayEquals(out, env.toByteArray(), ctx + ": toByteArray != writeToBuffer");
        OrderEnvelope decoded = OrderEnvelope.parseFrom(out);
        assertEnvelopeEquivalent(expected, decoded, ctx);
    }

    @Test
    @DisplayName("Lower-Level Fuzz 2: RawWireView stateful multi-round differential fuzzing vs official protoc")
    void testRawWireViewStatefulFuzz() throws Exception {
        RawWireView wv = new RawWireView();

        final int numSeeds = 120;
        final int roundsPerSeed = 4;
        final int stepsPerRound = 28;

        for (int seed = 1; seed <= numSeeds; seed++) {
            Random rng = new Random(seed * 0x9E3779B97F4A7C15L);
            RawWireView staleSubFromPrev = null;
            RawWireView staleRepFromPrev = null;

            for (int round = 0; round < roundsPerSeed; round++) {
                byte[] initWire = createRandomInitialWire(rng, false);
                wv.wrap(initWire, 0, initWire.length);
                OrderEnvelope.Builder oracle = OrderEnvelope.parseFrom(initWire).toBuilder();

                // Mutate stale child references from previous round — must be completely detached!
                if (staleSubFromPrev != null) {
                    staleSubFromPrev.setInt64(1, 999999L);
                    staleSubFromPrev.setString(2, "STALE_CORRUPTION");
                }
                if (staleRepFromPrev != null) {
                    staleRepFromPrev.setInt32(3, 888888);
                }
                assertTrue(wv.isClean(), "seed=" + seed + ",round=" + round + ": stale child dirtied freshly wrapped parent");

                boolean field6ReplacedOrErased = false;
                boolean doIntermediateGets = rng.nextBoolean();
                RawWireView heldStaleSub = null;
                RawWireView heldStaleRep = null;

                for (int step = 0; step < stepsPerRound; step++) {
                    String ctx = "RawWireView seed=" + seed + ",round=" + round + ",step=" + step;
                    int op = rng.nextInt(18);
                    switch (op) {
                        case 0 -> {
                            long v = SAMPLE_VARINTS[rng.nextInt(SAMPLE_VARINTS.length)];
                            wv.setInt64(1, v);
                            oracle.setOrderId(v);
                        }
                        case 1 -> {
                            String s = SAMPLE_STRINGS[rng.nextInt(SAMPLE_STRINGS.length)];
                            wv.setString(2, s);
                            oracle.setBuyerName(s);
                        }
                        case 2 -> {
                            int st = rng.nextBoolean() ? (1 + rng.nextInt(126)) : (128 + rng.nextInt(50000));
                            wv.setInt32(3, st);
                            oracle.setStatus(st);
                        }
                        case 3 -> {
                            long f = rng.nextLong();
                            wv.setFixed64(4, f);
                            oracle.setUpdatedAtFixed64(f);
                        }
                        case 4 -> {
                            // Mutate user via sub(5)
                            RawWireView sub = wv.sub(5);
                            staleSubFromPrev = sub;
                            int uop = rng.nextInt(6);
                            switch (uop) {
                                case 0 -> { long v = SAMPLE_VARINTS[rng.nextInt(SAMPLE_VARINTS.length)]; sub.setInt64(1, v); oracle.getUserBuilder().setUserId(v); }
                                case 1 -> { String c = SAMPLE_STRINGS[rng.nextInt(SAMPLE_STRINGS.length)]; sub.setString(2, c); oracle.getUserBuilder().setCity(c); }
                                case 2 -> { int vip = rng.nextBoolean() ? (1 + rng.nextInt(126)) : (128 + rng.nextInt(500)); sub.setInt32(3, vip); oracle.getUserBuilder().setVipLevel(vip); }
                                case 3 -> { double sc = rng.nextInt(1000) * 0.5; sub.setFixed64(4, Double.doubleToRawLongBits(sc)); oracle.getUserBuilder().setScore(sc); }
                                case 4 -> { String em = SAMPLE_STRINGS[rng.nextInt(SAMPLE_STRINGS.length)]; sub.setString(5, em); oracle.getUserBuilder().setEmail(em); }
                                case 5 -> {
                                    if (oracle.hasUser()) {
                                        sub.erase(2);
                                        oracle.getUserBuilder().clearCity();
                                    }
                                }
                            }
                        }
                        case 5 -> {
                            // Replace user via sub(5).wrap(newUserBytes) with zero getters on child
                            UserProfile newU = UserProfile.newBuilder()
                                    .setUserId(1 + rng.nextInt(120))
                                    .setCity(rng.nextBoolean() ? "SH" : SAMPLE_STRINGS[rng.nextInt(SAMPLE_STRINGS.length)])
                                    .setVipLevel(1 + rng.nextInt(9))
                                    .build();
                            byte[] uBytes = newU.toByteArray();
                            RawWireView sub = wv.sub(5);
                            staleSubFromPrev = sub;
                            sub.wrap(uBytes, 0, uBytes.length);
                            oracle.setUser(newU);
                        }
                        case 6 -> {
                            // Replace user via setBytes(5, newUserBytes) — detaches prior sub(5)
                            heldStaleSub = wv.sub(5);
                            UserProfile newU = UserProfile.newBuilder()
                                    .setUserId(1 + rng.nextInt(120))
                                    .setCity(rng.nextBoolean() ? "" : "C" + rng.nextInt(99))
                                    .build();
                            byte[] uBytes = newU.toByteArray();
                            wv.setBytes(5, uBytes, 0, uBytes.length);
                            oracle.setUser(newU);
                        }
                        case 7, 8 -> {
                            // Mutate repeated item via repeatedSub(6, idx)
                            if (!field6ReplacedOrErased && oracle.getItemsCount() > 0) {
                                int cnt = wv.repeatedCount(6);
                                assertEquals(oracle.getItemsCount(), cnt, ctx + ": repeatedCount(6)");
                                int idx = rng.nextInt(cnt);
                                RawWireView rv = wv.repeatedSub(6, idx);
                                staleRepFromPrev = rv;
                                LineItem.Builder oit = oracle.getItemsBuilder(idx);
                                int iop = rng.nextInt(6);
                                switch (iop) {
                                    case 0 -> { long v = SAMPLE_VARINTS[rng.nextInt(SAMPLE_VARINTS.length)]; rv.setInt64(1, v); oit.setItemId(v); }
                                    case 1 -> { String s = SAMPLE_STRINGS[rng.nextInt(SAMPLE_STRINGS.length)]; rv.setString(2, s); oit.setSku(s); }
                                    case 2 -> { int q = rng.nextBoolean() ? (1 + rng.nextInt(126)) : (128 + rng.nextInt(500)); rv.setInt32(3, q); oit.setQuantity(q); }
                                    case 3 -> { long p = SAMPLE_VARINTS[rng.nextInt(SAMPLE_VARINTS.length)]; rv.setInt64(4, p); oit.setPriceCents(p); }
                                    case 4 -> { String d = SAMPLE_STRINGS[rng.nextInt(SAMPLE_STRINGS.length)]; rv.setString(5, d); oit.setDescription(d); }
                                    case 5 -> { rv.erase(5); oit.clearDescription(); }
                                }
                            }
                        }
                        case 9 -> {
                            // Replace repeated item via repeatedSub(6, idx).wrap(...)
                            if (!field6ReplacedOrErased && oracle.getItemsCount() > 0) {
                                int idx = rng.nextInt(oracle.getItemsCount());
                                LineItem newIt = LineItem.newBuilder()
                                        .setItemId(1 + rng.nextInt(100))
                                        .setQuantity(1 + rng.nextInt(120))
                                        .setSku("W-" + rng.nextInt(99))
                                        .build();
                                byte[] itBytes = newIt.toByteArray();
                                RawWireView rv = wv.repeatedSub(6, idx);
                                staleRepFromPrev = rv;
                                rv.wrap(itBytes, 0, itBytes.length);
                                oracle.setItems(idx, newIt);
                            }
                        }
                        case 10 -> {
                            // Replace entire field 6 via setBytes(6, singleItemBytes) — tests Bug 1 & Bug 2 & Bug 3!
                            if (!field6ReplacedOrErased && oracle.getItemsCount() > 0) {
                                heldStaleRep = wv.repeatedSub(6, 0);
                            }
                            LineItem singleIt = LineItem.newBuilder()
                                    .setQuantity(1 + rng.nextInt(126))
                                    .setSku(rng.nextBoolean() ? "" : "S" + rng.nextInt(10))
                                    .build();
                            byte[] itBytes = singleIt.toByteArray();
                            wv.setBytes(6, itBytes, 0, itBytes.length);
                            oracle.clearItems().addItems(singleIt);
                            field6ReplacedOrErased = true;
                        }
                        case 11 -> {
                            // Set payload / auth_token / trace_id / total_cents
                            int subOp = rng.nextInt(4);
                            switch (subOp) {
                                case 0 -> {
                                    byte[] pl = new byte[rng.nextInt(32)];
                                    rng.nextBytes(pl);
                                    wv.setBytes(7, pl, 0, pl.length);
                                    oracle.setPayload(ByteString.copyFrom(pl));
                                }
                                case 1 -> { String s = SAMPLE_STRINGS[rng.nextInt(SAMPLE_STRINGS.length)]; wv.setString(8, s); oracle.setAuthToken(s); }
                                case 2 -> { String s = SAMPLE_STRINGS[rng.nextInt(SAMPLE_STRINGS.length)]; wv.setString(9, s); oracle.setTraceId(s); }
                                case 3 -> { long v = SAMPLE_VARINTS[rng.nextInt(SAMPLE_VARINTS.length)]; wv.setInt64(10, v); oracle.setTotalCents(v); }
                            }
                        }
                        case 12 -> {
                            // Erase a random field (scalar, user #5, or items #6)
                            int target = 1 + rng.nextInt(10);
                            if (target == 5) heldStaleSub = wv.sub(5);
                            if (target == 6 && !field6ReplacedOrErased && oracle.getItemsCount() > 0) {
                                heldStaleRep = wv.repeatedSub(6, 0);
                            }
                            wv.erase(target);
                            switch (target) {
                                case 1 -> oracle.clearOrderId();
                                case 2 -> oracle.clearBuyerName();
                                case 3 -> oracle.clearStatus();
                                case 4 -> oracle.clearUpdatedAtFixed64();
                                case 5 -> oracle.clearUser();
                                case 6 -> { oracle.clearItems(); field6ReplacedOrErased = true; }
                                case 7 -> oracle.clearPayload();
                                case 8 -> oracle.clearAuthToken();
                                case 9 -> oracle.clearTraceId();
                                case 10 -> oracle.clearTotalCents();
                            }
                        }
                        case 13 -> {
                            // Mutate any stale detached child references — must be completely ignored by parent!
                            if (heldStaleSub != null) {
                                heldStaleSub.setInt64(1, 777777L);
                                heldStaleSub.setString(2, "DETACHED_SUB");
                            }
                            if (heldStaleRep != null) {
                                heldStaleRep.setInt32(3, 666666);
                            }
                        }
                        case 14 -> {
                            // Intermediate getters
                            if (doIntermediateGets) {
                                assertEquals(oracle.getOrderId(), wv.getInt64(1), ctx);
                                assertEquals(oracle.getBuyerName(), wv.getString(2), ctx);
                                assertEquals(oracle.getStatus(), wv.getInt32(3), ctx);
                                assertEquals(oracle.getUpdatedAtFixed64(), wv.getFixed64(4), ctx);
                                assertEquals(oracle.getAuthToken(), wv.getString(8), ctx);
                                assertEquals(oracle.getTraceId(), wv.getString(9), ctx);
                                assertEquals(oracle.getTotalCents(), wv.getInt64(10), ctx);
                                if (oracle.hasUser()) {
                                    assertEquals(oracle.getUser().getUserId(), wv.sub(5).getInt64(1), ctx + ": sub(5).userId");
                                    assertEquals(oracle.getUser().getCity(), wv.sub(5).getString(2), ctx + ": sub(5).city");
                                    if (wv.repeatedCount(5) <= 1) {
                                        var uSliceView = wv.getSlice(5);
                                        byte[] uSlice = Arrays.copyOfRange(
                                                uSliceView.array(), uSliceView.offset(),
                                                uSliceView.offset() + uSliceView.byteLength());
                                        UserProfile uDec = UserProfile.parseFrom(uSlice);
                                        assertEquals(oracle.getUser().getUserId(), uDec.getUserId(), ctx + ": getSlice(5).userId");
                                        assertEquals(oracle.getUser().getCity(), uDec.getCity(), ctx + ": getSlice(5).city");
                                    }
                                }
                            }
                        }
                        case 15, 16, 17 -> {
                            // Mid-round serialize & verify exact maxSerializedSize() bound
                            verifyRawWireView(wv, oracle.build(), ctx);
                        }
                    }
                }

                verifyRawWireView(wv, oracle.build(), "RawWireView seed=" + seed + ",round=" + round + ",end");
            }
        }
    }

    private static void verifyRawWireView(RawWireView wv, OrderEnvelope expected, String ctx) throws Exception {
        int maxSz = wv.maxSerializedSize();
        byte[] exactBuf = new byte[maxSz];
        int written = wv.writeToBuffer(exactBuf, 0);
        assertTrue(written <= maxSz, ctx + ": written=" + written + " > maxSerializedSize=" + maxSz);
        byte[] out = Arrays.copyOf(exactBuf, written);
        assertArrayEquals(out, wv.toByteArray(), ctx + ": toByteArray != writeToBuffer");
        OrderEnvelope decoded = OrderEnvelope.parseFrom(out);
        assertEnvelopeEquivalent(expected, decoded, ctx);
    }
}
