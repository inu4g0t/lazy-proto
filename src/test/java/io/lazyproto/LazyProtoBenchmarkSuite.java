package io.lazyproto;

import com.google.protobuf.ByteString;
import com.sun.management.ThreadMXBean;
import io.lazyproto.dynamic.ProtoDocument;
import io.lazyproto.model.LazyOrderProto.LazyLineItem;
import io.lazyproto.model.LazyOrderProto.LazyOrderEnvelope;
import io.lazyproto.model.LazyOrderProto.LazyUserProfile;
import io.lazyproto.model.StandardOrderProto.LineItem;
import io.lazyproto.model.StandardOrderProto.OrderEnvelope;
import io.lazyproto.model.StandardOrderProto.UserProfile;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Comprehensive Performance & Heap Allocation Benchmark Suite comparing:
 * - Official Google Protobuf Eager Model ({@link OrderEnvelope})
 * - Lazy-Proto Generated Class ({@link LazyOrderEnvelope})
 *
 * <p>Evaluates BOTH:
 * 1. Advantageous Workloads (A1-A6: Partial Read, Passthrough, In-Place Patch, Span Splicing, List Item Modify, Redaction)
 * 2. Non-Advantageous / Adverse Workloads (B1-B4: From-Scratch Build, 100% Full-Tree Read, 100% Full-Tree Variable Mutation, Tiny Message)
 */
public class LazyProtoBenchmarkSuite {

    private static final ThreadMXBean THREAD_MX =
            (ThreadMXBean) ManagementFactory.getThreadMXBean();

    public record BenchResult(
            String category,
            String name,
            double stdNsPerOp,
            long stdBytesPerOp,
            double lazyNsPerOp,
            long lazyBytesPerOp,
            double speedup,
            double allocReductionPercent
    ) {}

    @FunctionalInterface
    public interface BenchAction {
        long run(int iteration) throws Exception;
    }

    private static long blackhole = 0;

    private static double[] measure(int warmupIters, int measureIters, BenchAction action) throws Exception {
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
        return new double[] { nsPerOp, bytesPerOp };
    }

    private static BenchResult runComparison(
            String category,
            String name,
            int warmupIters,
            int measureIters,
            BenchAction stdAction,
            BenchAction lazyAction) throws Exception {

        double[] std = measure(warmupIters, measureIters, stdAction);
        double[] lazy = measure(warmupIters, measureIters, lazyAction);

        double speedup = std[0] / lazy[0];
        double allocReduction = std[1] > 0 ? (1.0 - (lazy[1] / std[1])) * 100.0 : 0.0;
        return new BenchResult(
                category,
                name,
                std[0],
                (long) std[1],
                lazy[0],
                (long) lazy[1],
                speedup,
                allocReduction
        );
    }

    @Test
    @DisplayName("Execute Advantageous (A1-A6) vs Non-Advantageous (B1-B4) Benchmark Suite")
    void runFullBenchmarkSuite() throws Exception {
        // 1. Complex realistic fixture: 100 LineItems + 4 KB payload (~12.4 KB total wire size)
        final byte[] complexBytes = LazyProtoCorrectnessTest.createComplexOrderBytes(100, 4096);

        // 2. Tiny message fixture: only 3 scalar fields (order_id=1, status=3, total_cents=10), 11 bytes total
        final byte[] tinyBytes = OrderEnvelope.newBuilder()
                .setOrderId(1001L)
                .setStatus(1)
                .setTotalCents(5000L)
                .build()
                .toByteArray();

        // Reusable thread-local instance for LazyOrderEnvelope & output buffer
        final LazyOrderEnvelope reusableLazy = new LazyOrderEnvelope();
        final byte[] reusableOutBuf = new byte[complexBytes.length + 4096];

        List<BenchResult> results = new ArrayList<>();

        final int WARMUP = 8_000;
        final int ITERS  = 20_000;

        // =====================================================================
        // PART I: ADVANTAGEOUS WORKLOADS (A1 - A6)
        // =====================================================================

        // A1: Partial Read Routing (Read 3 fields out of ~12.4 KB / 100-item message)
        results.add(runComparison(
                "Advantageous (A1)",
                "Partial Read (3 fields from 12.4KB msg)",
                WARMUP, ITERS,
                iter -> {
                    OrderEnvelope msg = OrderEnvelope.parseFrom(complexBytes);
                    return msg.getOrderId() + msg.getStatus() + msg.getUser().getVipLevel();
                },
                iter -> {
                    reusableLazy.wrap(complexBytes, 0, complexBytes.length);
                    return reusableLazy.getOrderId() + reusableLazy.getStatus() + reusableLazy.getUser().getVipLevel();
                }
        ));

        // A2: Read-Few + Conditional Zero-Copy Passthrough (Fast-Path 0)
        results.add(runComparison(
                "Advantageous (A2)",
                "Inspect 2 fields + Unmodified Passthrough (Fast-Path 0)",
                WARMUP, ITERS,
                iter -> {
                    OrderEnvelope msg = OrderEnvelope.parseFrom(complexBytes);
                    if (msg.getOrderId() > 0 && msg.getStatus() == 1) {
                        byte[] out = msg.toByteArray();
                        return out.length;
                    }
                    return 0;
                },
                iter -> {
                    reusableLazy.wrap(complexBytes, 0, complexBytes.length);
                    if (reusableLazy.getOrderId() > 0 && reusableLazy.getStatus() == 1) {
                        return reusableLazy.writeToBuffer(reusableOutBuf, 0);
                    }
                    return 0;
                }
        ));

        // A3: Read-Few + In-Place Same-Size Patching (Fast-Path 1: status 1->2, fixed64 timestamp, nested double score)
        results.add(runComparison(
                "Advantageous (A3)",
                "Modify 3 Same-Size/Fixed Fields + Serialize (Fast-Path 1)",
                WARMUP, ITERS,
                iter -> {
                    OrderEnvelope msg = OrderEnvelope.parseFrom(complexBytes);
                    OrderEnvelope updated = msg.toBuilder()
                            .setStatus(2)
                            .setUpdatedAtFixed64(1800000000000L + (iter & 0xFF))
                            .setUser(msg.getUser().toBuilder().setScore(98.75).build())
                            .build();
                    return updated.toByteArray().length;
                },
                iter -> {
                    reusableLazy.wrap(complexBytes, 0, complexBytes.length);
                    reusableLazy.toBuilder()
                            .setStatus(2)
                            .setUpdatedAtFixed64(1800000000000L + (iter & 0xFF));
                    reusableLazy.getUser().setScore(98.75);
                    return reusableLazy.writeToBuffer(reusableOutBuf, 0);
                }
        ));

        // A4: Read-Few + Variable-Size Partial Mutation (Fast-Path 2: Span Splicing)
        results.add(runComparison(
                "Advantageous (A4)",
                "Modify Variable-Len String & Nested City + Splice (Fast-Path 2)",
                WARMUP, ITERS,
                iter -> {
                    OrderEnvelope msg = OrderEnvelope.parseFrom(complexBytes);
                    OrderEnvelope updated = msg.toBuilder()
                            .setBuyerName("alexander_the_great_vip_buyer")
                            .setStatus(50000)
                            .setUser(msg.getUser().toBuilder().setCity("SHANGHAI_PUDONG").build())
                            .build();
                    return updated.toByteArray().length;
                },
                iter -> {
                    reusableLazy.wrap(complexBytes, 0, complexBytes.length);
                    reusableLazy.setBuyerName("alexander_the_great_vip_buyer")
                                .setStatus(50000);
                    reusableLazy.getUser().setCity("SHANGHAI_PUDONG");
                    return reusableLazy.writeToBuffer(reusableOutBuf, 0);
                }
        ));

        // A5: Mutate 1 Element out of 100 in Repeated Sub-Message List + Serialize
        results.add(runComparison(
                "Advantageous (A5)",
                "Mutate 1 of 100 Repeated LineItems + Serialize",
                WARMUP, ITERS,
                iter -> {
                    OrderEnvelope msg = OrderEnvelope.parseFrom(complexBytes);
                    LineItem item42 = msg.getItems(42).toBuilder()
                            .setQuantity(999)
                            .setDescription("UPDATED_ITEM_42_DESC")
                            .build();
                    OrderEnvelope updated = msg.toBuilder()
                            .setItems(42, item42)
                            .build();
                    return updated.toByteArray().length;
                },
                iter -> {
                    reusableLazy.wrap(complexBytes, 0, complexBytes.length);
                    reusableLazy.getItems(42)
                            .setQuantity(999)
                            .setDescription("UPDATED_ITEM_42_DESC");
                    return reusableLazy.writeToBuffer(reusableOutBuf, 0);
                }
        ));

        // A6: Gateway Field Redaction (Delete auth_token field #8 & forward remaining spans)
        results.add(runComparison(
                "Advantageous (A6)",
                "Gateway Redact 1 Sensitive Field + Forward 12.4KB Msg",
                WARMUP, ITERS,
                iter -> {
                    OrderEnvelope msg = OrderEnvelope.parseFrom(complexBytes);
                    OrderEnvelope redacted = msg.toBuilder().clearAuthToken().build();
                    return redacted.toByteArray().length;
                },
                iter -> {
                    reusableLazy.wrap(complexBytes, 0, complexBytes.length);
                    reusableLazy.clearAuthToken();
                    return reusableLazy.writeToBuffer(reusableOutBuf, 0);
                }
        ));

        // =====================================================================
        // PART II: NON-ADVANTAGEOUS / ADVERSE WORKLOADS (B1 - B4)
        // =====================================================================

        // B1: Build Brand-New Message from Scratch (0% Input Bytes to Reuse)
        final byte[] smallPayload = new byte[256];
        results.add(runComparison(
                "Non-Advantageous (B1)",
                "Build from Scratch (10 items, 0% input reuse) + Serialize",
                WARMUP, ITERS,
                iter -> {
                    OrderEnvelope.Builder b = OrderEnvelope.newBuilder()
                            .setOrderId(202611240001L + iter)
                            .setBuyerName("scratch_buyer")
                            .setStatus(1)
                            .setUpdatedAtFixed64(1795497000123L)
                            .setUser(UserProfile.newBuilder()
                                    .setUserId(88001122L)
                                    .setCity("SHANGHAI")
                                    .setVipLevel(3)
                                    .setScore(95.5)
                                    .setEmail("alice@example.com")
                                    .build())
                            .setPayload(ByteString.copyFrom(smallPayload))
                            .setAuthToken("token-123456")
                            .setTraceId("trace-123456")
                            .setTotalCents(99000L);
                    for (int i = 0; i < 10; i++) {
                        b.addItems(LineItem.newBuilder()
                                .setItemId(100L + i)
                                .setSku("SKU-00" + i)
                                .setQuantity(2)
                                .setPriceCents(1500L)
                                .setDescription("Item desc")
                                .build());
                    }
                    return b.build().toByteArray().length;
                },
                iter -> {
                    LazyOrderEnvelope b = LazyOrderEnvelope.newBuilder()
                            .setOrderId(202611240001L + iter)
                            .setBuyerName("scratch_buyer")
                            .setStatus(1)
                            .setUpdatedAtFixed64(1795497000123L)
                            .setUser(LazyUserProfile.newBuilder()
                                    .setUserId(88001122L)
                                    .setCity("SHANGHAI")
                                    .setVipLevel(3)
                                    .setScore(95.5)
                                    .setEmail("alice@example.com"))
                            .setPayloadBytes(smallPayload)
                            .setAuthToken("token-123456")
                            .setTraceId("trace-123456")
                            .setTotalCents(99000L);
                    for (int i = 0; i < 10; i++) {
                        b.addItems(LazyLineItem.newBuilder()
                                .setItemId(100L + i)
                                .setSku("SKU-00" + i)
                                .setQuantity(2)
                                .setPriceCents(1500L)
                                .setDescription("Item desc"));
                    }
                    return b.writeToBuffer(reusableOutBuf, 0);
                }
        ));

        // B2: 100% Full-Tree Deserialization (Read every scalar, every string, and all 100 LineItems)
        results.add(runComparison(
                "Non-Advantageous (B2)",
                "100% Full-Tree Read (All fields + all 100 LineItems)",
                WARMUP / 2, ITERS / 2,
                iter -> {
                    OrderEnvelope msg = OrderEnvelope.parseFrom(complexBytes);
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
                },
                iter -> {
                    reusableLazy.wrap(complexBytes, 0, complexBytes.length);
                    long sum = reusableLazy.getOrderId() + reusableLazy.getBuyerName().length() + reusableLazy.getStatus()
                            + reusableLazy.getUpdatedAtFixed64() + reusableLazy.getPayload().size()
                            + reusableLazy.getAuthToken().length() + reusableLazy.getTraceId().length() + reusableLazy.getTotalCents();
                    LazyUserProfile u = reusableLazy.getUser();
                    sum += u.getUserId() + u.getCity().length() + u.getVipLevel()
                            + (long) u.getScore() + u.getEmail().length();
                    int n = reusableLazy.getItemsCount();
                    for (int i = 0; i < n; i++) {
                        LazyLineItem item = reusableLazy.getItems(i);
                        sum += item.getItemId() + item.getSku().length() + item.getQuantity()
                                + item.getPriceCents() + item.getDescription().length();
                    }
                    return sum;
                }
        ));

        // B3: 100% Full-Tree Variable-Size Mutation (Every single field & all 20 items mutated to different length)
        final byte[] mediumBytes = LazyProtoCorrectnessTest.createComplexOrderBytes(20, 256);
        final byte[] newPayload = new byte[128];
        results.add(runComparison(
                "Non-Advantageous (B3)",
                "100% Full-Tree Variable-Size Mutation (0% Span Reuse)",
                WARMUP / 2, ITERS / 2,
                iter -> {
                    OrderEnvelope msg = OrderEnvelope.parseFrom(mediumBytes);
                    OrderEnvelope.Builder b = msg.toBuilder()
                            .setOrderId(1L)
                            .setBuyerName("mutated_full_tree_buyer_name_long")
                            .setStatus(99999)
                            .setUpdatedAtFixed64(42L)
                            .setPayload(ByteString.copyFrom(newPayload))
                            .setAuthToken("new_short_tok")
                            .setTraceId("new_trace_id_full_mutation_long_string")
                            .setTotalCents(5L);
                    b.getUserBuilder()
                            .setUserId(2L)
                            .setCity("GUANGZHOU_FULL_MUTATE")
                            .setVipLevel(10000)
                            .setScore(12.34)
                            .setEmail("full_mutate_user@example.org");
                    for (int i = 0; i < b.getItemsCount(); i++) {
                        LineItem oldItem = b.getItems(i);
                        b.setItems(i, oldItem.toBuilder()
                                .setItemId(9000000L + i)
                                .setSku("NEW-LONG-SKU-CODE-" + i)
                                .setQuantity(50000)
                                .setPriceCents(10L)
                                .setDescription("Short")
                                .build());
                    }
                    return b.build().toByteArray().length;
                },
                iter -> {
                    reusableLazy.wrap(mediumBytes, 0, mediumBytes.length);
                    reusableLazy.setOrderId(1L)
                            .setBuyerName("mutated_full_tree_buyer_name_long")
                            .setStatus(99999)
                            .setUpdatedAtFixed64(42L)
                            .setPayloadBytes(newPayload)
                            .setAuthToken("new_short_tok")
                            .setTraceId("new_trace_id_full_mutation_long_string")
                            .setTotalCents(5L);
                    reusableLazy.getUser()
                            .setUserId(2L)
                            .setCity("GUANGZHOU_FULL_MUTATE")
                            .setVipLevel(10000)
                            .setScore(12.34)
                            .setEmail("full_mutate_user@example.org");
                    for (int i = 0; i < reusableLazy.getItemsCount(); i++) {
                        reusableLazy.getItems(i)
                                .setItemId(9000000L + i)
                                .setSku("NEW-LONG-SKU-CODE-" + i)
                                .setQuantity(50000)
                                .setPriceCents(10L)
                                .setDescription("Short");
                    }
                    return reusableLazy.writeToBuffer(reusableOutBuf, 0);
                }
        ));

        // B4: Tiny 11-Byte Message (3 scalar fields, all 3 read & mutated to variable sizes)
        results.add(runComparison(
                "Non-Advantageous (B4)",
                "Tiny 11-Byte Message (Read All 3 + Mutate All 3 Var-Size)",
                WARMUP, ITERS,
                iter -> {
                    OrderEnvelope msg = OrderEnvelope.parseFrom(tinyBytes);
                    long sum = msg.getOrderId() + msg.getStatus() + msg.getTotalCents();
                    OrderEnvelope updated = msg.toBuilder()
                            .setOrderId(sum + 100000000L)
                            .setStatus(30000)
                            .setTotalCents(999999999L)
                            .build();
                    return updated.toByteArray().length;
                },
                iter -> {
                    reusableLazy.wrap(tinyBytes, 0, tinyBytes.length);
                    long sum = reusableLazy.getOrderId() + reusableLazy.getStatus() + reusableLazy.getTotalCents();
                    reusableLazy.setOrderId(sum + 100000000L)
                            .setStatus(30000)
                            .setTotalCents(999999999L);
                    return reusableLazy.writeToBuffer(reusableOutBuf, 0);
                }
        ));

        // Print formatted benchmark table
        System.out.println("\n=============================================================================================================================");
        System.out.println("  LAZY-PROTO vs STANDARD GOOGLE PROTOBUF (JAVA 21) — ADVANTAGEOUS (A1-A6) & NON-ADVANTAGEOUS (B1-B4) BENCHMARK REPORT");
        System.out.println("=============================================================================================================================");
        System.out.printf("%-23s | %-55s | %10s | %10s | %8s | %10s | %10s%n",
                "Category", "Workload Scenario", "Std(ns/op)", "Lazy(ns/op)", "Speedup", "Std(B/op)", "Lazy(B/op)");
        System.out.println("-----------------------------------------------------------------------------------------------------------------------------");
        for (BenchResult r : results) {
            System.out.printf("%-23s | %-55s | %10.1f | %10.1f | %7.2fx | %10d | %10d%n",
                    r.category(),
                    r.name(),
                    r.stdNsPerOp(),
                    r.lazyNsPerOp(),
                    r.speedup(),
                    r.stdBytesPerOp(),
                    r.lazyBytesPerOp());
        }
        System.out.println("=============================================================================================================================\n");

        // Sanity assertions: advantageous workloads should be significantly faster than standard eager protobuf
        assertTrue(results.get(0).speedup() > 3.0, "A1 Partial Read should be >3x faster");
        assertTrue(results.get(1).speedup() > 5.0, "A2 Passthrough should be >5x faster");
        assertTrue(results.get(2).speedup() > 5.0, "A3 In-Place Patch should be >5x faster");
    }
}
