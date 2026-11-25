package io.lazyproto;

import com.google.protobuf.ByteString;
import com.google.protobuf.CodedOutputStream;
import com.sun.management.ThreadMXBean;
import io.lazyproto.bench.proto.LineItem;
import io.lazyproto.bench.proto.OrderEnvelope;
import io.lazyproto.bench.proto.UserProfile;
import io.lazyproto.model.LazyOrderProto.LazyLineItem;
import io.lazyproto.model.LazyOrderProto.LazyOrderEnvelope;
import io.lazyproto.model.LazyOrderProto.LazyUserProfile;
import io.lazyproto.model.StandardOrderProto;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Controlled Benchmark Suite comparing:
 * <ol>
 *   <li><b>Official {@code protoc 3.25.5} Generated PB</b> ({@link io.lazyproto.bench.proto.OrderEnvelope})</li>
 *   <li><b>Hand-Written Eager Baseline</b> ({@link io.lazyproto.model.StandardOrderProto.OrderEnvelope})</li>
 *   <li><b>Lazy-Proto Architecture v3</b> ({@link LazyOrderEnvelope})</li>
 * </ol>
 *
 * <p>Separates allocation &amp; lifecycle tracks strictly to eliminate asymmetric output-buffer or builder-reuse bias:
 * <ul>
 *   <li><b>Track I (Immutable + Fresh {@code toByteArray()})</b>: {@code parseFrom -> toBuilder -> build -> toByteArray()}</li>
 *   <li><b>Track II (Preallocated Output Buffer Reuse)</b>: {@code writeTo(CodedOutputStream(outBuf))} vs {@code writeToBuffer(outBuf, 0)},
 *       testing both immutable {@code parseFrom} and reusable {@code Builder} modes.</li>
 * </ul>
 */
public class LazyProtoBenchmarkSuite {

    private static final ThreadMXBean THREAD_MX =
            (ThreadMXBean) ManagementFactory.getThreadMXBean();

    @FunctionalInterface
    public interface BenchAction {
        long run(int iteration) throws Exception;
    }

    public record Metric(double nsPerOp, long bytesPerOp) {}

    public record TrackIRow(
            String category,
            String name,
            Metric officialPb,
            Metric handEager,
            Metric lazyImm
    ) {}

    public record TrackIIRow(
            String category,
            String name,
            Metric officialImmOutBuf,
            Metric officialReuseBuilderOutBuf,
            Metric handEagerOutBuf,
            Metric lazyImmOutBuf,
            Metric lazyReuseBuilderOutBuf
    ) {}

    private static volatile long blackhole = 0;

    private static Metric measure(int warmupIters, int measureIters, BenchAction action) throws Exception {
        long sink = 0;
        for (int i = 0; i < warmupIters; i++) {
            sink ^= action.run(i);
        }
        blackhole ^= sink;

        long tid = Thread.currentThread().threadId();
        long allocBefore = THREAD_MX.getThreadAllocatedBytes(tid);
        long t0 = System.nanoTime();
        for (int i = 0; i < measureIters; i++) {
            sink ^= action.run(i);
        }
        long elapsedNs = System.nanoTime() - t0;
        long allocAfter = THREAD_MX.getThreadAllocatedBytes(tid);
        blackhole ^= sink;

        double nsPerOp = (double) elapsedNs / measureIters;
        long bytesPerOp = Math.max(0L, (allocAfter - allocBefore) / measureIters);
        return new Metric(nsPerOp, bytesPerOp);
    }

    private static int writeOfficialToBuf(OrderEnvelope msg, byte[] outBuf) throws IOException {
        int size = msg.getSerializedSize();
        CodedOutputStream cos = CodedOutputStream.newInstance(outBuf, 0, size);
        msg.writeTo(cos);
        cos.checkNoSpaceLeft();
        return size;
    }

    private static int writeHandEagerToBuf(StandardOrderProto.OrderEnvelope msg, byte[] outBuf) throws IOException {
        int size = msg.getSerializedSize();
        CodedOutputStream cos = CodedOutputStream.newInstance(outBuf, 0, size);
        msg.writeTo(cos);
        cos.checkNoSpaceLeft();
        return size;
    }

    @Test
    @DisplayName("Execute Fair Multi-Track Benchmark Suite (Official protoc 3.25.5 vs Hand-Written Eager vs Lazy-Proto v3)")
    void runFullBenchmarkSuite() throws Exception {
        final byte[] complexBytes = LazyProtoCorrectnessTest.createComplexOrderBytes(100, 4096);
        final byte[] mediumBytes = LazyProtoCorrectnessTest.createComplexOrderBytes(20, 256);
        final byte[] tinyBytes = OrderEnvelope.newBuilder()
                .setOrderId(1001L)
                .setStatus(1)
                .setTotalCents(5000L)
                .build()
                .toByteArray();

        // Sanity-verify exact binary parity between Official protoc, Hand-Eager, and Lazy before timing
        OrderEnvelope offParsed = OrderEnvelope.parseFrom(complexBytes);
        StandardOrderProto.OrderEnvelope handParsed = StandardOrderProto.OrderEnvelope.parseFrom(complexBytes);
        LazyOrderEnvelope lazyParsed = LazyOrderEnvelope.parseFrom(complexBytes);
        assertEquals(offParsed.getOrderId(), lazyParsed.getOrderId());
        assertEquals(offParsed.getItemsCount(), lazyParsed.getItemsCount());
        assertArrayEquals(complexBytes, offParsed.toByteArray());
        assertArrayEquals(complexBytes, handParsed.toByteArray());
        assertArrayEquals(complexBytes, lazyParsed.toByteArray());

        final LazyOrderEnvelope.Builder reusableLazyBuilder = LazyOrderEnvelope.newBuilder();
        final OrderEnvelope.Builder reusableOfficialBuilder = OrderEnvelope.newBuilder();
        final byte[] reusableOutBuf = new byte[complexBytes.length + 4096];

        final int WARMUP = 6_000;
        final int ITERS = 15_000;
        final int WARMUP_H = WARMUP / 2;
        final int ITERS_H = ITERS / 2;

        List<TrackIRow> trackI = new ArrayList<>();
        List<TrackIIRow> trackII = new ArrayList<>();

        // =====================================================================
        // A1: Partial Read (3 fields from 12.4 KB / 100-item message, no serialize)
        // =====================================================================
        {
            Metric mOff = measure(WARMUP, ITERS, i -> {
                OrderEnvelope msg = OrderEnvelope.parseFrom(complexBytes);
                return msg.getOrderId() + msg.getStatus() + msg.getUser().getVipLevel();
            });
            Metric mHand = measure(WARMUP, ITERS, i -> {
                StandardOrderProto.OrderEnvelope msg = StandardOrderProto.OrderEnvelope.parseFrom(complexBytes);
                return msg.getOrderId() + msg.getStatus() + msg.getUser().getVipLevel();
            });
            Metric mLazyImm = measure(WARMUP, ITERS, i -> {
                LazyOrderEnvelope msg = LazyOrderEnvelope.parseFrom(complexBytes);
                return msg.getOrderId() + msg.getStatus() + msg.getUser().getVipLevel();
            });
            trackI.add(new TrackIRow("Advantageous (A1)", "Partial Read (3 fields, 12.4KB)", mOff, mHand, mLazyImm));

            Metric mOffReuseB = measure(WARMUP, ITERS, i -> {
                reusableOfficialBuilder.clear().mergeFrom(complexBytes);
                return reusableOfficialBuilder.getOrderId() + reusableOfficialBuilder.getStatus()
                        + reusableOfficialBuilder.getUserOrBuilder().getVipLevel();
            });
            Metric mLazyReuseB = measure(WARMUP, ITERS, i -> {
                reusableLazyBuilder.reuse(complexBytes, 0, complexBytes.length);
                return reusableLazyBuilder.getOrderId() + reusableLazyBuilder.getStatus()
                        + reusableLazyBuilder.getUserOrBuilder().getVipLevel();
            });
            trackII.add(new TrackIIRow("Advantageous (A1)", "Partial Read (3 fields, no outBuf)",
                    mOff, mOffReuseB, mHand, mLazyImm, mLazyReuseB));
        }

        // =====================================================================
        // A2: Inspect 2 fields + Unmodified Passthrough (Fast-Path 0)
        // =====================================================================
        {
            Metric mOff = measure(WARMUP, ITERS, i -> {
                OrderEnvelope msg = OrderEnvelope.parseFrom(complexBytes);
                return (msg.getOrderId() > 0 && msg.getStatus() == 1) ? msg.toByteArray().length : 0;
            });
            Metric mHand = measure(WARMUP, ITERS, i -> {
                StandardOrderProto.OrderEnvelope msg = StandardOrderProto.OrderEnvelope.parseFrom(complexBytes);
                return (msg.getOrderId() > 0 && msg.getStatus() == 1) ? msg.toByteArray().length : 0;
            });
            Metric mLazyImm = measure(WARMUP, ITERS, i -> {
                LazyOrderEnvelope msg = LazyOrderEnvelope.parseFrom(complexBytes);
                return (msg.getOrderId() > 0 && msg.getStatus() == 1) ? msg.toByteArray().length : 0;
            });
            trackI.add(new TrackIRow("Advantageous (A2)", "Inspect 2 + Passthrough (FP0)", mOff, mHand, mLazyImm));

            Metric mOffOut = measure(WARMUP, ITERS, i -> {
                OrderEnvelope msg = OrderEnvelope.parseFrom(complexBytes);
                return (msg.getOrderId() > 0 && msg.getStatus() == 1) ? writeOfficialToBuf(msg, reusableOutBuf) : 0;
            });
            Metric mOffReuseBOut = measure(WARMUP, ITERS, i -> {
                reusableOfficialBuilder.clear().mergeFrom(complexBytes);
                return (reusableOfficialBuilder.getOrderId() > 0 && reusableOfficialBuilder.getStatus() == 1)
                        ? writeOfficialToBuf(reusableOfficialBuilder.build(), reusableOutBuf) : 0;
            });
            Metric mHandOut = measure(WARMUP, ITERS, i -> {
                StandardOrderProto.OrderEnvelope msg = StandardOrderProto.OrderEnvelope.parseFrom(complexBytes);
                return (msg.getOrderId() > 0 && msg.getStatus() == 1) ? writeHandEagerToBuf(msg, reusableOutBuf) : 0;
            });
            Metric mLazyImmOut = measure(WARMUP, ITERS, i -> {
                LazyOrderEnvelope msg = LazyOrderEnvelope.parseFrom(complexBytes);
                return (msg.getOrderId() > 0 && msg.getStatus() == 1) ? msg.writeToBuffer(reusableOutBuf, 0) : 0;
            });
            Metric mLazyReuseBOut = measure(WARMUP, ITERS, i -> {
                reusableLazyBuilder.reuse(complexBytes, 0, complexBytes.length);
                return (reusableLazyBuilder.getOrderId() > 0 && reusableLazyBuilder.getStatus() == 1)
                        ? reusableLazyBuilder.writeToBuffer(reusableOutBuf, 0) : 0;
            });
            trackII.add(new TrackIIRow("Advantageous (A2)", "Inspect 2 + Passthrough (FP0)",
                    mOffOut, mOffReuseBOut, mHandOut, mLazyImmOut, mLazyReuseBOut));
        }

        // =====================================================================
        // A3: Modify 3 Fixed / Same-Size Fields (Fast-Path 1)
        // =====================================================================
        {
            Metric mOff = measure(WARMUP, ITERS, iter -> {
                OrderEnvelope msg = OrderEnvelope.parseFrom(complexBytes);
                OrderEnvelope.Builder b = msg.toBuilder()
                        .setStatus(2)
                        .setUpdatedAtFixed64(1800000000000L + (iter & 0xFF));
                b.getUserBuilder().setScore(98.75);
                return b.build().toByteArray().length;
            });
            Metric mHand = measure(WARMUP, ITERS, iter -> {
                StandardOrderProto.OrderEnvelope msg = StandardOrderProto.OrderEnvelope.parseFrom(complexBytes);
                StandardOrderProto.OrderEnvelope.Builder b = msg.toBuilder()
                        .setStatus(2)
                        .setUpdatedAtFixed64(1800000000000L + (iter & 0xFF));
                b.getUserBuilder().setScore(98.75);
                return b.build().toByteArray().length;
            });
            Metric mLazyImm = measure(WARMUP, ITERS, iter -> {
                LazyOrderEnvelope msg = LazyOrderEnvelope.parseFrom(complexBytes);
                LazyOrderEnvelope.Builder b = msg.toBuilder()
                        .setStatus(2)
                        .setUpdatedAtFixed64(1800000000000L + (iter & 0xFF));
                b.getUserBuilder().setScore(98.75);
                return b.build().toByteArray().length;
            });
            trackI.add(new TrackIRow("Advantageous (A3)", "Modify 3 Fixed/Same-Size (FP1)", mOff, mHand, mLazyImm));

            Metric mOffOut = measure(WARMUP, ITERS, iter -> {
                OrderEnvelope msg = OrderEnvelope.parseFrom(complexBytes);
                OrderEnvelope.Builder b = msg.toBuilder()
                        .setStatus(2)
                        .setUpdatedAtFixed64(1800000000000L + (iter & 0xFF));
                b.getUserBuilder().setScore(98.75);
                return writeOfficialToBuf(b.build(), reusableOutBuf);
            });
            Metric mOffReuseBOut = measure(WARMUP, ITERS, iter -> {
                reusableOfficialBuilder.clear().mergeFrom(complexBytes)
                        .setStatus(2)
                        .setUpdatedAtFixed64(1800000000000L + (iter & 0xFF));
                reusableOfficialBuilder.getUserBuilder().setScore(98.75);
                return writeOfficialToBuf(reusableOfficialBuilder.build(), reusableOutBuf);
            });
            Metric mHandOut = measure(WARMUP, ITERS, iter -> {
                StandardOrderProto.OrderEnvelope msg = StandardOrderProto.OrderEnvelope.parseFrom(complexBytes);
                StandardOrderProto.OrderEnvelope.Builder b = msg.toBuilder()
                        .setStatus(2)
                        .setUpdatedAtFixed64(1800000000000L + (iter & 0xFF));
                b.getUserBuilder().setScore(98.75);
                return writeHandEagerToBuf(b.build(), reusableOutBuf);
            });
            Metric mLazyImmOut = measure(WARMUP, ITERS, iter -> {
                LazyOrderEnvelope msg = LazyOrderEnvelope.parseFrom(complexBytes);
                LazyOrderEnvelope.Builder b = msg.toBuilder()
                        .setStatus(2)
                        .setUpdatedAtFixed64(1800000000000L + (iter & 0xFF));
                b.getUserBuilder().setScore(98.75);
                return b.build().writeToBuffer(reusableOutBuf, 0);
            });
            Metric mLazyReuseBOut = measure(WARMUP, ITERS, iter -> {
                reusableLazyBuilder.reuse(complexBytes, 0, complexBytes.length)
                        .setStatus(2)
                        .setUpdatedAtFixed64(1800000000000L + (iter & 0xFF));
                reusableLazyBuilder.getUserBuilder().setScore(98.75);
                return reusableLazyBuilder.writeToBuffer(reusableOutBuf, 0);
            });
            trackII.add(new TrackIIRow("Advantageous (A3)", "Modify 3 Fixed/Same-Size (FP1)",
                    mOffOut, mOffReuseBOut, mHandOut, mLazyImmOut, mLazyReuseBOut));
        }

        // =====================================================================
        // A4: Modify Variable-Size String & Nested City + Splice (Fast-Path 2)
        // =====================================================================
        {
            Metric mOff = measure(WARMUP, ITERS, iter -> {
                OrderEnvelope msg = OrderEnvelope.parseFrom(complexBytes);
                OrderEnvelope.Builder b = msg.toBuilder()
                        .setBuyerName("alexander_the_great_vip_buyer")
                        .setStatus(50000);
                b.getUserBuilder().setCity("SHANGHAI_PUDONG");
                return b.build().toByteArray().length;
            });
            Metric mHand = measure(WARMUP, ITERS, iter -> {
                StandardOrderProto.OrderEnvelope msg = StandardOrderProto.OrderEnvelope.parseFrom(complexBytes);
                StandardOrderProto.OrderEnvelope.Builder b = msg.toBuilder()
                        .setBuyerName("alexander_the_great_vip_buyer")
                        .setStatus(50000);
                b.getUserBuilder().setCity("SHANGHAI_PUDONG");
                return b.build().toByteArray().length;
            });
            Metric mLazyImm = measure(WARMUP, ITERS, iter -> {
                LazyOrderEnvelope msg = LazyOrderEnvelope.parseFrom(complexBytes);
                LazyOrderEnvelope.Builder b = msg.toBuilder()
                        .setBuyerName("alexander_the_great_vip_buyer")
                        .setStatus(50000);
                b.getUserBuilder().setCity("SHANGHAI_PUDONG");
                return b.build().toByteArray().length;
            });
            trackI.add(new TrackIRow("Advantageous (A4)", "Var-Size String + City (FP2)", mOff, mHand, mLazyImm));

            Metric mOffOut = measure(WARMUP, ITERS, iter -> {
                OrderEnvelope msg = OrderEnvelope.parseFrom(complexBytes);
                OrderEnvelope.Builder b = msg.toBuilder()
                        .setBuyerName("alexander_the_great_vip_buyer")
                        .setStatus(50000);
                b.getUserBuilder().setCity("SHANGHAI_PUDONG");
                return writeOfficialToBuf(b.build(), reusableOutBuf);
            });
            Metric mOffReuseBOut = measure(WARMUP, ITERS, iter -> {
                reusableOfficialBuilder.clear().mergeFrom(complexBytes)
                        .setBuyerName("alexander_the_great_vip_buyer")
                        .setStatus(50000);
                reusableOfficialBuilder.getUserBuilder().setCity("SHANGHAI_PUDONG");
                return writeOfficialToBuf(reusableOfficialBuilder.build(), reusableOutBuf);
            });
            Metric mHandOut = measure(WARMUP, ITERS, iter -> {
                StandardOrderProto.OrderEnvelope msg = StandardOrderProto.OrderEnvelope.parseFrom(complexBytes);
                StandardOrderProto.OrderEnvelope.Builder b = msg.toBuilder()
                        .setBuyerName("alexander_the_great_vip_buyer")
                        .setStatus(50000);
                b.getUserBuilder().setCity("SHANGHAI_PUDONG");
                return writeHandEagerToBuf(b.build(), reusableOutBuf);
            });
            Metric mLazyImmOut = measure(WARMUP, ITERS, iter -> {
                LazyOrderEnvelope msg = LazyOrderEnvelope.parseFrom(complexBytes);
                LazyOrderEnvelope.Builder b = msg.toBuilder()
                        .setBuyerName("alexander_the_great_vip_buyer")
                        .setStatus(50000);
                b.getUserBuilder().setCity("SHANGHAI_PUDONG");
                return b.build().writeToBuffer(reusableOutBuf, 0);
            });
            Metric mLazyReuseBOut = measure(WARMUP, ITERS, iter -> {
                reusableLazyBuilder.reuse(complexBytes, 0, complexBytes.length)
                        .setBuyerName("alexander_the_great_vip_buyer")
                        .setStatus(50000);
                reusableLazyBuilder.getUserBuilder().setCity("SHANGHAI_PUDONG");
                return reusableLazyBuilder.writeToBuffer(reusableOutBuf, 0);
            });
            trackII.add(new TrackIIRow("Advantageous (A4)", "Var-Size String + City (FP2)",
                    mOffOut, mOffReuseBOut, mHandOut, mLazyImmOut, mLazyReuseBOut));
        }

        // =====================================================================
        // A5: Mutate 1 of 100 Repeated LineItems
        // =====================================================================
        {
            Metric mOff = measure(WARMUP, ITERS, iter -> {
                OrderEnvelope msg = OrderEnvelope.parseFrom(complexBytes);
                OrderEnvelope.Builder b = msg.toBuilder();
                b.getItemsBuilder(42).setQuantity(999).setDescription("UPDATED_ITEM_42_DESC");
                return b.build().toByteArray().length;
            });
            Metric mHand = measure(WARMUP, ITERS, iter -> {
                StandardOrderProto.OrderEnvelope msg = StandardOrderProto.OrderEnvelope.parseFrom(complexBytes);
                StandardOrderProto.LineItem item42 = msg.getItems(42).toBuilder()
                        .setQuantity(999).setDescription("UPDATED_ITEM_42_DESC").build();
                return msg.toBuilder().setItems(42, item42).build().toByteArray().length;
            });
            Metric mLazyImm = measure(WARMUP, ITERS, iter -> {
                LazyOrderEnvelope msg = LazyOrderEnvelope.parseFrom(complexBytes);
                LazyOrderEnvelope.Builder b = msg.toBuilder();
                b.getItemsBuilder(42).setQuantity(999).setDescription("UPDATED_ITEM_42_DESC");
                return b.build().toByteArray().length;
            });
            trackI.add(new TrackIRow("Advantageous (A5)", "Mutate 1 of 100 Repeated Items", mOff, mHand, mLazyImm));

            Metric mOffOut = measure(WARMUP, ITERS, iter -> {
                OrderEnvelope msg = OrderEnvelope.parseFrom(complexBytes);
                OrderEnvelope.Builder b = msg.toBuilder();
                b.getItemsBuilder(42).setQuantity(999).setDescription("UPDATED_ITEM_42_DESC");
                return writeOfficialToBuf(b.build(), reusableOutBuf);
            });
            Metric mOffReuseBOut = measure(WARMUP, ITERS, iter -> {
                reusableOfficialBuilder.clear().mergeFrom(complexBytes);
                reusableOfficialBuilder.getItemsBuilder(42).setQuantity(999).setDescription("UPDATED_ITEM_42_DESC");
                return writeOfficialToBuf(reusableOfficialBuilder.build(), reusableOutBuf);
            });
            Metric mHandOut = measure(WARMUP, ITERS, iter -> {
                StandardOrderProto.OrderEnvelope msg = StandardOrderProto.OrderEnvelope.parseFrom(complexBytes);
                StandardOrderProto.LineItem item42 = msg.getItems(42).toBuilder()
                        .setQuantity(999).setDescription("UPDATED_ITEM_42_DESC").build();
                return writeHandEagerToBuf(msg.toBuilder().setItems(42, item42).build(), reusableOutBuf);
            });
            Metric mLazyImmOut = measure(WARMUP, ITERS, iter -> {
                LazyOrderEnvelope msg = LazyOrderEnvelope.parseFrom(complexBytes);
                LazyOrderEnvelope.Builder b = msg.toBuilder();
                b.getItemsBuilder(42).setQuantity(999).setDescription("UPDATED_ITEM_42_DESC");
                return b.build().writeToBuffer(reusableOutBuf, 0);
            });
            Metric mLazyReuseBOut = measure(WARMUP, ITERS, iter -> {
                reusableLazyBuilder.reuse(complexBytes, 0, complexBytes.length);
                reusableLazyBuilder.getItemsBuilder(42).setQuantity(999).setDescription("UPDATED_ITEM_42_DESC");
                return reusableLazyBuilder.writeToBuffer(reusableOutBuf, 0);
            });
            trackII.add(new TrackIIRow("Advantageous (A5)", "Mutate 1 of 100 Repeated Items",
                    mOffOut, mOffReuseBOut, mHandOut, mLazyImmOut, mLazyReuseBOut));
        }

        // =====================================================================
        // A6: Gateway Redact 1 Sensitive Field + Forward
        // =====================================================================
        {
            Metric mOff = measure(WARMUP, ITERS, iter ->
                    OrderEnvelope.parseFrom(complexBytes).toBuilder().clearAuthToken().build().toByteArray().length);
            Metric mHand = measure(WARMUP, ITERS, iter ->
                    StandardOrderProto.OrderEnvelope.parseFrom(complexBytes).toBuilder().clearAuthToken().build().toByteArray().length);
            Metric mLazyImm = measure(WARMUP, ITERS, iter ->
                    LazyOrderEnvelope.parseFrom(complexBytes).toBuilder().clearAuthToken().build().toByteArray().length);
            trackI.add(new TrackIRow("Advantageous (A6)", "Redact 1 Field + Forward", mOff, mHand, mLazyImm));

            Metric mOffOut = measure(WARMUP, ITERS, iter ->
                    writeOfficialToBuf(OrderEnvelope.parseFrom(complexBytes).toBuilder().clearAuthToken().build(), reusableOutBuf));
            Metric mOffReuseBOut = measure(WARMUP, ITERS, iter -> {
                reusableOfficialBuilder.clear().mergeFrom(complexBytes).clearAuthToken();
                return writeOfficialToBuf(reusableOfficialBuilder.build(), reusableOutBuf);
            });
            Metric mHandOut = measure(WARMUP, ITERS, iter ->
                    writeHandEagerToBuf(StandardOrderProto.OrderEnvelope.parseFrom(complexBytes).toBuilder().clearAuthToken().build(), reusableOutBuf));
            Metric mLazyImmOut = measure(WARMUP, ITERS, iter ->
                    LazyOrderEnvelope.parseFrom(complexBytes).toBuilder().clearAuthToken().build().writeToBuffer(reusableOutBuf, 0));
            Metric mLazyReuseBOut = measure(WARMUP, ITERS, iter -> {
                reusableLazyBuilder.reuse(complexBytes, 0, complexBytes.length).clearAuthToken();
                return reusableLazyBuilder.writeToBuffer(reusableOutBuf, 0);
            });
            trackII.add(new TrackIIRow("Advantageous (A6)", "Redact 1 Field + Forward",
                    mOffOut, mOffReuseBOut, mHandOut, mLazyImmOut, mLazyReuseBOut));
        }

        // =====================================================================
        // B1: Build from Scratch (10 items, 0% input reuse)
        // =====================================================================
        final byte[] smallPayload = new byte[256];
        {
            Metric mOff = measure(WARMUP, ITERS, iter -> buildOfficialScratch(iter, smallPayload).toByteArray().length);
            Metric mHand = measure(WARMUP, ITERS, iter -> buildHandScratch(iter, smallPayload).toByteArray().length);
            Metric mLazyImm = measure(WARMUP, ITERS, iter -> buildLazyScratch(iter, smallPayload).toByteArray().length);
            trackI.add(new TrackIRow("Non-Advantageous (B1)", "Build Scratch (10 items)", mOff, mHand, mLazyImm));

            Metric mOffOut = measure(WARMUP, ITERS, iter -> writeOfficialToBuf(buildOfficialScratch(iter, smallPayload), reusableOutBuf));
            Metric mHandOut = measure(WARMUP, ITERS, iter -> writeHandEagerToBuf(buildHandScratch(iter, smallPayload), reusableOutBuf));
            Metric mLazyImmOut = measure(WARMUP, ITERS, iter -> buildLazyScratch(iter, smallPayload).writeToBuffer(reusableOutBuf, 0));
            trackII.add(new TrackIIRow("Non-Advantageous (B1)", "Build Scratch (10 items)",
                    mOffOut, mOffOut, mHandOut, mLazyImmOut, mLazyImmOut));
        }

        // =====================================================================
        // B2: 100% Full-Tree Read (All fields + all 100 LineItems)
        // =====================================================================
        {
            Metric mOff = measure(WARMUP_H, ITERS_H, iter -> readOfficialFull(OrderEnvelope.parseFrom(complexBytes)));
            Metric mHand = measure(WARMUP_H, ITERS_H, iter -> readHandFull(StandardOrderProto.OrderEnvelope.parseFrom(complexBytes)));
            Metric mLazyImm = measure(WARMUP_H, ITERS_H, iter -> readLazyFull(LazyOrderEnvelope.parseFrom(complexBytes)));
            trackI.add(new TrackIRow("Non-Advantageous (B2)", "100% Full-Tree Read (100 Items)", mOff, mHand, mLazyImm));

            Metric mOffReuseB = measure(WARMUP_H, ITERS_H, iter -> {
                reusableOfficialBuilder.clear().mergeFrom(complexBytes);
                return readOfficialBuilderFull(reusableOfficialBuilder);
            });
            Metric mLazyReuseB = measure(WARMUP_H, ITERS_H, iter -> {
                reusableLazyBuilder.reuse(complexBytes, 0, complexBytes.length);
                return readLazyBuilderFull(reusableLazyBuilder);
            });
            trackII.add(new TrackIIRow("Non-Advantageous (B2)", "100% Full-Tree Read (no outBuf)",
                    mOff, mOffReuseB, mHand, mLazyImm, mLazyReuseB));
        }

        // =====================================================================
        // B3: 100% Full-Tree Variable-Size Mutation (20 items, 0% span reuse)
        // =====================================================================
        final byte[] newPayload = new byte[128];
        {
            Metric mOff = measure(WARMUP_H, ITERS_H, iter -> mutateOfficialFull(mediumBytes, newPayload).toByteArray().length);
            Metric mHand = measure(WARMUP_H, ITERS_H, iter -> mutateHandFull(mediumBytes, newPayload).toByteArray().length);
            Metric mLazyImm = measure(WARMUP_H, ITERS_H, iter -> mutateLazyImmFull(mediumBytes, newPayload).toByteArray().length);
            trackI.add(new TrackIRow("Non-Advantageous (B3)", "100% Full-Tree Var-Size Mutate", mOff, mHand, mLazyImm));

            Metric mOffOut = measure(WARMUP_H, ITERS_H, iter -> writeOfficialToBuf(mutateOfficialFull(mediumBytes, newPayload), reusableOutBuf));
            Metric mOffReuseBOut = measure(WARMUP_H, ITERS_H, iter -> {
                reusableOfficialBuilder.clear().mergeFrom(mediumBytes);
                mutateOfficialBuilderFull(reusableOfficialBuilder, newPayload);
                return writeOfficialToBuf(reusableOfficialBuilder.build(), reusableOutBuf);
            });
            Metric mHandOut = measure(WARMUP_H, ITERS_H, iter -> writeHandEagerToBuf(mutateHandFull(mediumBytes, newPayload), reusableOutBuf));
            Metric mLazyImmOut = measure(WARMUP_H, ITERS_H, iter -> mutateLazyImmFull(mediumBytes, newPayload).writeToBuffer(reusableOutBuf, 0));
            Metric mLazyReuseBOut = measure(WARMUP_H, ITERS_H, iter -> {
                reusableLazyBuilder.reuse(mediumBytes, 0, mediumBytes.length);
                mutateLazyBuilderFull(reusableLazyBuilder, newPayload);
                return reusableLazyBuilder.writeToBuffer(reusableOutBuf, 0);
            });
            trackII.add(new TrackIIRow("Non-Advantageous (B3)", "100% Full-Tree Var-Size Mutate",
                    mOffOut, mOffReuseBOut, mHandOut, mLazyImmOut, mLazyReuseBOut));
        }

        // =====================================================================
        // B4: Tiny 11-Byte Message (Read All 3 + Mutate All 3 Var-Size)
        // =====================================================================
        {
            Metric mOff = measure(WARMUP, ITERS, iter -> {
                OrderEnvelope msg = OrderEnvelope.parseFrom(tinyBytes);
                long sum = msg.getOrderId() + msg.getStatus() + msg.getTotalCents();
                return msg.toBuilder().setOrderId(sum + 100000000L).setStatus(30000).setTotalCents(999999999L)
                        .build().toByteArray().length;
            });
            Metric mHand = measure(WARMUP, ITERS, iter -> {
                StandardOrderProto.OrderEnvelope msg = StandardOrderProto.OrderEnvelope.parseFrom(tinyBytes);
                long sum = msg.getOrderId() + msg.getStatus() + msg.getTotalCents();
                return msg.toBuilder().setOrderId(sum + 100000000L).setStatus(30000).setTotalCents(999999999L)
                        .build().toByteArray().length;
            });
            Metric mLazyImm = measure(WARMUP, ITERS, iter -> {
                LazyOrderEnvelope msg = LazyOrderEnvelope.parseFrom(tinyBytes);
                long sum = msg.getOrderId() + msg.getStatus() + msg.getTotalCents();
                return msg.toBuilder().setOrderId(sum + 100000000L).setStatus(30000).setTotalCents(999999999L)
                        .build().toByteArray().length;
            });
            trackI.add(new TrackIRow("Non-Advantageous (B4)", "Tiny 11-Byte Msg (Read+Mutate)", mOff, mHand, mLazyImm));

            Metric mOffOut = measure(WARMUP, ITERS, iter -> {
                OrderEnvelope msg = OrderEnvelope.parseFrom(tinyBytes);
                long sum = msg.getOrderId() + msg.getStatus() + msg.getTotalCents();
                return writeOfficialToBuf(msg.toBuilder().setOrderId(sum + 100000000L).setStatus(30000)
                        .setTotalCents(999999999L).build(), reusableOutBuf);
            });
            Metric mOffReuseBOut = measure(WARMUP, ITERS, iter -> {
                reusableOfficialBuilder.clear().mergeFrom(tinyBytes);
                long sum = reusableOfficialBuilder.getOrderId() + reusableOfficialBuilder.getStatus()
                        + reusableOfficialBuilder.getTotalCents();
                reusableOfficialBuilder.setOrderId(sum + 100000000L).setStatus(30000).setTotalCents(999999999L);
                return writeOfficialToBuf(reusableOfficialBuilder.build(), reusableOutBuf);
            });
            Metric mHandOut = measure(WARMUP, ITERS, iter -> {
                StandardOrderProto.OrderEnvelope msg = StandardOrderProto.OrderEnvelope.parseFrom(tinyBytes);
                long sum = msg.getOrderId() + msg.getStatus() + msg.getTotalCents();
                return writeHandEagerToBuf(msg.toBuilder().setOrderId(sum + 100000000L).setStatus(30000)
                        .setTotalCents(999999999L).build(), reusableOutBuf);
            });
            Metric mLazyImmOut = measure(WARMUP, ITERS, iter -> {
                LazyOrderEnvelope msg = LazyOrderEnvelope.parseFrom(tinyBytes);
                long sum = msg.getOrderId() + msg.getStatus() + msg.getTotalCents();
                return msg.toBuilder().setOrderId(sum + 100000000L).setStatus(30000)
                        .setTotalCents(999999999L).build().writeToBuffer(reusableOutBuf, 0);
            });
            Metric mLazyReuseBOut = measure(WARMUP, ITERS, iter -> {
                reusableLazyBuilder.reuse(tinyBytes, 0, tinyBytes.length);
                long sum = reusableLazyBuilder.getOrderId() + reusableLazyBuilder.getStatus() + reusableLazyBuilder.getTotalCents();
                reusableLazyBuilder.setOrderId(sum + 100000000L).setStatus(30000).setTotalCents(999999999L);
                return reusableLazyBuilder.writeToBuffer(reusableOutBuf, 0);
            });
            trackII.add(new TrackIIRow("Non-Advantageous (B4)", "Tiny 11-Byte Msg (Read+Mutate)",
                    mOffOut, mOffReuseBOut, mHandOut, mLazyImmOut, mLazyReuseBOut));
        }

        // =====================================================================
        // Print Formatted Reports
        // =====================================================================
        String javaVer = System.getProperty("java.version");
        System.out.println("\n==========================================================================================================================================");
        System.out.printf("  JAVA TRACK I (Same Immutable Lifecycle + Fresh toByteArray()): Official protoc 3.25.5 vs Hand-Written Eager vs Lazy-Proto v3 (JDK %s)%n", javaVer);
        System.out.println("==========================================================================================================================================");
        System.out.printf("%-21s | %-30s | %10s | %10s | %10s | %9s | %9s | %9s | %9s | %9s%n",
                "Category", "Workload Scenario", "OffPB(ns)", "HandEgr(ns)", "LazyImm(ns)",
                "vsOffPB", "vsHandEgr", "OffPB(B)", "HandE(B)", "Lazy(B)");
        System.out.println("------------------------------------------------------------------------------------------------------------------------------------------");
        for (TrackIRow r : trackI) {
            System.out.printf("%-21s | %-30s | %10.1f | %10.1f | %10.1f | %8.2fx | %8.2fx | %9d | %9d | %9d%n",
                    r.category(), r.name(),
                    r.officialPb().nsPerOp(), r.handEager().nsPerOp(), r.lazyImm().nsPerOp(),
                    r.officialPb().nsPerOp() / r.lazyImm().nsPerOp(),
                    r.handEager().nsPerOp() / r.lazyImm().nsPerOp(),
                    r.officialPb().bytesPerOp(), r.handEager().bytesPerOp(), r.lazyImm().bytesPerOp());
        }

        System.out.println("\n==========================================================================================================================================================");
        System.out.println("  JAVA TRACK II (Preallocated Output Buffer Reuse + Immutable vs Reusable Builder): Official protoc 3.25.5 vs Hand-Eager vs Lazy-Proto v3");
        System.out.println("==========================================================================================================================================================");
        System.out.printf("%-21s | %-30s | %9s | %9s | %9s | %9s | %9s | %8s | %8s | %8s | %8s | %8s%n",
                "Category", "Workload Scenario", "OffImm", "OffRseB", "HandEgr", "LazyImm", "LazyRseB",
                "ImmSpd", "RseSpd", "OffR(B)", "LzIm(B)", "LzRs(B)");
        System.out.println("----------------------------------------------------------------------------------------------------------------------------------------------------------");
        for (TrackIIRow r : trackII) {
            System.out.printf("%-21s | %-30s | %9.1f | %9.1f | %9.1f | %9.1f | %9.1f | %7.2fx | %7.2fx | %8d | %8d | %8d%n",
                    r.category(), r.name(),
                    r.officialImmOutBuf().nsPerOp(), r.officialReuseBuilderOutBuf().nsPerOp(),
                    r.handEagerOutBuf().nsPerOp(),
                    r.lazyImmOutBuf().nsPerOp(), r.lazyReuseBuilderOutBuf().nsPerOp(),
                    r.officialImmOutBuf().nsPerOp() / r.lazyImmOutBuf().nsPerOp(),
                    r.officialReuseBuilderOutBuf().nsPerOp() / r.lazyReuseBuilderOutBuf().nsPerOp(),
                    r.officialReuseBuilderOutBuf().bytesPerOp(),
                    r.lazyImmOutBuf().bytesPerOp(),
                    r.lazyReuseBuilderOutBuf().bytesPerOp());
        }
        System.out.println("==========================================================================================================================================================\n");

        // Sanity assertions against real Official protoc 3.25.5 PB under same Track I / Track II rules
        assertTrue(trackI.get(0).officialPb().nsPerOp() / trackI.get(0).lazyImm().nsPerOp() > 3.0, "A1 Partial Read vs Official PB > 3x");
        assertTrue(trackI.get(1).officialPb().nsPerOp() / trackI.get(1).lazyImm().nsPerOp() > 5.0, "A2 Passthrough (Track I) vs Official PB > 5x");
        assertTrue(trackII.get(2).officialImmOutBuf().nsPerOp() / trackII.get(2).lazyImmOutBuf().nsPerOp() > 5.0, "A3 FP1 Imm vs Official PB > 5x");
        assertTrue(trackII.get(2).officialReuseBuilderOutBuf().nsPerOp() / trackII.get(2).lazyReuseBuilderOutBuf().nsPerOp() > 8.0, "A3 FP1 Reuse vs Official PB > 8x");
    }

    private static OrderEnvelope buildOfficialScratch(int iter, byte[] payload) {
        OrderEnvelope.Builder b = OrderEnvelope.newBuilder()
                .setOrderId(202611240001L + iter)
                .setBuyerName("scratch_buyer")
                .setStatus(1)
                .setUpdatedAtFixed64(1795497000123L)
                .setUser(UserProfile.newBuilder()
                        .setUserId(88001122L).setCity("SHANGHAI").setVipLevel(3)
                        .setScore(95.5).setEmail("alice@example.com").build())
                .setPayload(ByteString.copyFrom(payload))
                .setAuthToken("token-123456")
                .setTraceId("trace-123456")
                .setTotalCents(99000L);
        for (int i = 0; i < 10; i++) {
            b.addItems(LineItem.newBuilder()
                    .setItemId(100L + i).setSku("SKU-00" + i).setQuantity(2)
                    .setPriceCents(1500L).setDescription("Item desc").build());
        }
        return b.build();
    }

    private static StandardOrderProto.OrderEnvelope buildHandScratch(int iter, byte[] payload) {
        StandardOrderProto.OrderEnvelope.Builder b = StandardOrderProto.OrderEnvelope.newBuilder()
                .setOrderId(202611240001L + iter)
                .setBuyerName("scratch_buyer")
                .setStatus(1)
                .setUpdatedAtFixed64(1795497000123L)
                .setUser(StandardOrderProto.UserProfile.newBuilder()
                        .setUserId(88001122L).setCity("SHANGHAI").setVipLevel(3)
                        .setScore(95.5).setEmail("alice@example.com").build())
                .setPayload(ByteString.copyFrom(payload))
                .setAuthToken("token-123456")
                .setTraceId("trace-123456")
                .setTotalCents(99000L);
        for (int i = 0; i < 10; i++) {
            b.addItems(StandardOrderProto.LineItem.newBuilder()
                    .setItemId(100L + i).setSku("SKU-00" + i).setQuantity(2)
                    .setPriceCents(1500L).setDescription("Item desc").build());
        }
        return b.build();
    }

    private static LazyOrderEnvelope buildLazyScratch(int iter, byte[] payload) {
        LazyOrderEnvelope.Builder b = LazyOrderEnvelope.newBuilder()
                .setOrderId(202611240001L + iter)
                .setBuyerName("scratch_buyer")
                .setStatus(1)
                .setUpdatedAtFixed64(1795497000123L)
                .setUser(LazyUserProfile.newBuilder()
                        .setUserId(88001122L).setCity("SHANGHAI").setVipLevel(3)
                        .setScore(95.5).setEmail("alice@example.com").build())
                .setPayloadBytes(payload)
                .setAuthToken("token-123456")
                .setTraceId("trace-123456")
                .setTotalCents(99000L);
        for (int i = 0; i < 10; i++) {
            b.addItems(LazyLineItem.newBuilder()
                    .setItemId(100L + i).setSku("SKU-00" + i).setQuantity(2)
                    .setPriceCents(1500L).setDescription("Item desc").build());
        }
        return b.build();
    }

    private static long readOfficialFull(OrderEnvelope msg) {
        long sum = msg.getOrderId() + msg.getBuyerName().length() + msg.getStatus()
                + msg.getUpdatedAtFixed64() + msg.getPayload().size()
                + msg.getAuthToken().length() + msg.getTraceId().length() + msg.getTotalCents();
        UserProfile u = msg.getUser();
        sum += u.getUserId() + u.getCity().length() + u.getVipLevel()
                + (long) u.getScore() + u.getEmail().length();
        int n = msg.getItemsCount();
        for (int i = 0; i < n; i++) {
            LineItem item = msg.getItems(i);
            sum += item.getItemId() + item.getSku().length() + item.getQuantity()
                    + item.getPriceCents() + item.getDescription().length();
        }
        return sum;
    }

    private static long readOfficialBuilderFull(OrderEnvelope.Builder b) {
        long sum = b.getOrderId() + b.getBuyerName().length() + b.getStatus()
                + b.getUpdatedAtFixed64() + b.getPayload().size()
                + b.getAuthToken().length() + b.getTraceId().length() + b.getTotalCents();
        var u = b.getUserOrBuilder();
        sum += u.getUserId() + u.getCity().length() + u.getVipLevel()
                + (long) u.getScore() + u.getEmail().length();
        int n = b.getItemsCount();
        for (int i = 0; i < n; i++) {
            var item = b.getItemsOrBuilder(i);
            sum += item.getItemId() + item.getSku().length() + item.getQuantity()
                    + item.getPriceCents() + item.getDescription().length();
        }
        return sum;
    }

    private static long readHandFull(StandardOrderProto.OrderEnvelope msg) {
        long sum = msg.getOrderId() + msg.getBuyerName().length() + msg.getStatus()
                + msg.getUpdatedAtFixed64() + msg.getPayload().size()
                + msg.getAuthToken().length() + msg.getTraceId().length() + msg.getTotalCents();
        StandardOrderProto.UserProfile u = msg.getUser();
        sum += u.getUserId() + u.getCity().length() + u.getVipLevel()
                + (long) u.getScore() + u.getEmail().length();
        int n = msg.getItemsCount();
        for (int i = 0; i < n; i++) {
            StandardOrderProto.LineItem item = msg.getItems(i);
            sum += item.getItemId() + item.getSku().length() + item.getQuantity()
                    + item.getPriceCents() + item.getDescription().length();
        }
        return sum;
    }

    private static long readLazyFull(LazyOrderEnvelope msg) {
        long sum = msg.getOrderId() + msg.getBuyerName().length() + msg.getStatus()
                + msg.getUpdatedAtFixed64() + msg.getPayload().size()
                + msg.getAuthToken().length() + msg.getTraceId().length() + msg.getTotalCents();
        LazyUserProfile u = msg.getUser();
        sum += u.getUserId() + u.getCity().length() + u.getVipLevel()
                + (long) u.getScore() + u.getEmail().length();
        int n = msg.getItemsCount();
        for (int i = 0; i < n; i++) {
            LazyLineItem item = msg.getItems(i);
            sum += item.getItemId() + item.getSku().length() + item.getQuantity()
                    + item.getPriceCents() + item.getDescription().length();
        }
        return sum;
    }

    private static long readLazyBuilderFull(LazyOrderEnvelope.Builder b) {
        long sum = b.getOrderId() + b.getBuyerName().length() + b.getStatus()
                + b.getUpdatedAtFixed64() + b.getPayload().size()
                + b.getAuthToken().length() + b.getTraceId().length() + b.getTotalCents();
        var u = b.getUserOrBuilder();
        sum += u.getUserId() + u.getCity().length() + u.getVipLevel()
                + (long) u.getScore() + u.getEmail().length();
        int n = b.getItemsCount();
        for (int i = 0; i < n; i++) {
            var item = b.getItemsOrBuilder(i);
            sum += item.getItemId() + item.getSku().length() + item.getQuantity()
                    + item.getPriceCents() + item.getDescription().length();
        }
        return sum;
    }

    private static OrderEnvelope mutateOfficialFull(byte[] mediumBytes, byte[] newPayload) throws IOException {
        OrderEnvelope.Builder b = OrderEnvelope.parseFrom(mediumBytes).toBuilder();
        mutateOfficialBuilderFull(b, newPayload);
        return b.build();
    }

    private static void mutateOfficialBuilderFull(OrderEnvelope.Builder b, byte[] newPayload) {
        b.setOrderId(1L)
                .setBuyerName("mutated_full_tree_buyer_name_long")
                .setStatus(99999)
                .setUpdatedAtFixed64(42L)
                .setPayload(ByteString.copyFrom(newPayload))
                .setAuthToken("new_short_tok")
                .setTraceId("new_trace_id_full_mutation_long_string")
                .setTotalCents(5L);
        b.getUserBuilder()
                .setUserId(2L).setCity("GUANGZHOU_FULL_MUTATE")
                .setVipLevel(10000).setScore(12.34).setEmail("full_mutate_user@example.org");
        for (int i = 0; i < b.getItemsCount(); i++) {
            b.getItemsBuilder(i)
                    .setItemId(9000000L + i).setSku("NEW-LONG-SKU-CODE-" + i)
                    .setQuantity(50000).setPriceCents(10L).setDescription("Short");
        }
    }

    private static StandardOrderProto.OrderEnvelope mutateHandFull(byte[] mediumBytes, byte[] newPayload) throws IOException {
        StandardOrderProto.OrderEnvelope.Builder b = StandardOrderProto.OrderEnvelope.parseFrom(mediumBytes).toBuilder()
                .setOrderId(1L)
                .setBuyerName("mutated_full_tree_buyer_name_long")
                .setStatus(99999)
                .setUpdatedAtFixed64(42L)
                .setPayload(ByteString.copyFrom(newPayload))
                .setAuthToken("new_short_tok")
                .setTraceId("new_trace_id_full_mutation_long_string")
                .setTotalCents(5L);
        b.getUserBuilder()
                .setUserId(2L).setCity("GUANGZHOU_FULL_MUTATE")
                .setVipLevel(10000).setScore(12.34).setEmail("full_mutate_user@example.org");
        for (int i = 0; i < b.getItemsCount(); i++) {
            StandardOrderProto.LineItem oldItem = b.getItems(i);
            b.setItems(i, oldItem.toBuilder()
                    .setItemId(9000000L + i).setSku("NEW-LONG-SKU-CODE-" + i)
                    .setQuantity(50000).setPriceCents(10L).setDescription("Short").build());
        }
        return b.build();
    }

    private static LazyOrderEnvelope mutateLazyImmFull(byte[] mediumBytes, byte[] newPayload) {
        LazyOrderEnvelope.Builder b = LazyOrderEnvelope.parseFrom(mediumBytes).toBuilder();
        mutateLazyBuilderFull(b, newPayload);
        return b.build();
    }

    private static void mutateLazyBuilderFull(LazyOrderEnvelope.Builder b, byte[] newPayload) {
        b.setOrderId(1L)
                .setBuyerName("mutated_full_tree_buyer_name_long")
                .setStatus(99999)
                .setUpdatedAtFixed64(42L)
                .setPayloadBytes(newPayload)
                .setAuthToken("new_short_tok")
                .setTraceId("new_trace_id_full_mutation_long_string")
                .setTotalCents(5L);
        b.getUserBuilder()
                .setUserId(2L).setCity("GUANGZHOU_FULL_MUTATE")
                .setVipLevel(10000).setScore(12.34).setEmail("full_mutate_user@example.org");
        int cnt = b.getItemsCount();
        for (int i = 0; i < cnt; i++) {
            b.getItemsBuilder(i)
                    .setItemId(9000000L + i).setSku("NEW-LONG-SKU-CODE-" + i)
                    .setQuantity(50000).setPriceCents(10L).setDescription("Short");
        }
    }
}
