package io.lazyproto;

import com.google.protobuf.ByteString;
import com.google.protobuf.CodedOutputStream;
import com.google.protobuf.UnknownFieldSet;
import io.lazyproto.bench.proto.OrderEnvelope;
import io.lazyproto.core.RawOrderEnvelope;
import io.lazyproto.model.LazyOrderProto.LazyOrderEnvelope;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.profile.GCProfiler;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;
import org.openjdk.jmh.runner.options.TimeValue;
import us.hebi.quickbuf.ProtoSink;
import us.hebi.quickbuf.ProtoSource;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

/**
 * JMH 1.37 Cross-Library Java Protobuf Benchmark ({@code -prof gc} for exact {@code ns/op} and {@code B/op}).
 *
 * <p>Compares 5 Java Protobuf implementations in symmetric reusable-object + preallocated-output-buffer mode
 * across all 10 scenarios (A1–A6 advantageous, B1–B4 adverse/crossover) plus A2 without unknown fields:
 * <ol>
 *   <li><b>Official PB</b>: {@code protobuf-java 3.25.5} ({@code protoc 3.25.5}, reusable {@code Builder} + {@code CodedOutputStream(outBuf)})</li>
 *   <li><b>QuickBuffers</b>: {@code us.hebi.quickbuf 1.4} ({@code clearQuick()} + reusable {@code ProtoSource}/{@code ProtoSink(outBuf)})</li>
 *   <li><b>LightProto</b>: {@code io.streamnative.lightproto 0.9.3} (reusable message + reusable heap Netty {@code ByteBuf} input/output)</li>
 *   <li><b>lazy-proto Compat</b>: {@link LazyOrderEnvelope.Builder} ({@code reuse()} + {@code writeToBuffer(outBuf, 0)})</li>
 *   <li><b>lazy-proto Raw</b>: {@link RawOrderEnvelope} (zero-dependency {@code lazy-proto-core}, {@code wrap()} + {@code writeToBuffer(outBuf, 0)})</li>
 * </ol>
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Thread)
@Fork(1)
@Warmup(iterations = 2, time = 400, timeUnit = TimeUnit.MILLISECONDS)
@Measurement(iterations = 3, time = 400, timeUnit = TimeUnit.MILLISECONDS)
public class JavaProtoJmhBenchmark {

    private static final String[] SKU_TABLE = {
            "SKU-000", "SKU-001", "SKU-002", "SKU-003", "SKU-004",
            "SKU-005", "SKU-006", "SKU-007", "SKU-008", "SKU-009"
    };

    private static final String[] MUT_SKU_TABLE = new String[20];
    static {
        for (int i = 0; i < MUT_SKU_TABLE.length; i++) {
            MUT_SKU_TABLE[i] = "NEW-LONG-SKU-CODE-" + i;
        }
    }

    // Fixtures
    private byte[] complexBytes;
    private byte[] complexNoUnknownBytes;
    private byte[] mediumBytes;
    private byte[] tinyBytes;
    private byte[] smallPayload;
    private byte[] newPayload;

    // Preallocated output buffer shared identically across all 5 libraries
    private byte[] outBuf;

    // Reusable instances per library
    private OrderEnvelope.Builder officialBuilder;
    private io.lazyproto.bench.quickbuf.OrderEnvelope quickBufMsg;
    private ProtoSource quickBufSource;
    private ProtoSink quickBufSink;
    private io.lazyproto.bench.lightproto.OrderEnvelope lightProtoMsg;
    private ByteBuf lpComplexIn;
    private ByteBuf lpComplexNoUnkIn;
    private ByteBuf lpMediumIn;
    private ByteBuf lpTinyIn;
    private ByteBuf lpOutBuf;
    private LazyOrderEnvelope.Builder lazyBuilder;
    private RawOrderEnvelope rawMsg;

    private int iterCounter;

    @Setup(Level.Trial)
    public void setup() throws IOException {
        complexBytes = LazyProtoCorrectnessTest.createComplexOrderBytes(100, 4096);
        complexNoUnknownBytes = OrderEnvelope.parseFrom(complexBytes)
                .toBuilder().setUnknownFields(UnknownFieldSet.getDefaultInstance()).build().toByteArray();
        mediumBytes = LazyProtoCorrectnessTest.createComplexOrderBytes(20, 256);
        tinyBytes = OrderEnvelope.newBuilder()
                .setOrderId(1001L)
                .setStatus(1)
                .setTotalCents(5000L)
                .build()
                .toByteArray();
        smallPayload = new byte[256];
        newPayload = new byte[128];
        for (int i = 0; i < smallPayload.length; i++) smallPayload[i] = (byte) (i ^ 0x5A);
        for (int i = 0; i < newPayload.length; i++) newPayload[i] = (byte) (i * 7 + 3);

        outBuf = new byte[complexBytes.length + 8192];

        officialBuilder = OrderEnvelope.newBuilder();
        quickBufMsg = io.lazyproto.bench.quickbuf.OrderEnvelope.newInstance();
        quickBufSource = ProtoSource.newArraySource();
        quickBufSink = ProtoSink.newArraySink();
        lightProtoMsg = new io.lazyproto.bench.lightproto.OrderEnvelope();
        lpComplexIn = Unpooled.wrappedBuffer(complexBytes);
        lpComplexNoUnkIn = Unpooled.wrappedBuffer(complexNoUnknownBytes);
        lpMediumIn = Unpooled.wrappedBuffer(mediumBytes);
        lpTinyIn = Unpooled.wrappedBuffer(tinyBytes);
        lpOutBuf = Unpooled.wrappedBuffer(outBuf);
        lazyBuilder = LazyOrderEnvelope.newBuilder();
        rawMsg = new RawOrderEnvelope();
    }

    private int writeOfficialToBuf(OrderEnvelope msg) throws IOException {
        int size = msg.getSerializedSize();
        CodedOutputStream cos = CodedOutputStream.newInstance(outBuf, 0, size);
        msg.writeTo(cos);
        cos.checkNoSpaceLeft();
        return consumeWritten(size);
    }

    private int writeQuickBufToBuf() throws IOException {
        quickBufSink.setOutput(outBuf);
        quickBufMsg.writeTo(quickBufSink);
        return consumeWritten(quickBufSink.getTotalBytesWritten());
    }

    private int writeLightProtoToBuf() {
        lpOutBuf.clear();
        return consumeWritten(lightProtoMsg.writeTo(lpOutBuf));
    }

    private int consumeWritten(int written) {
        return written + (outBuf[0] & 0xFF) + (outBuf[written - 1] & 0xFF);
    }

    // =========================================================================
    // A1: Partial Read (3 fields from 12.4 KB / 100-item message, no serialize)
    // =========================================================================

    @Benchmark
    public long a1_partialRead_officialPB() throws IOException {
        officialBuilder.clear().mergeFrom(complexBytes);
        return officialBuilder.getOrderId() + officialBuilder.getStatus()
                + officialBuilder.getUserOrBuilder().getVipLevel();
    }

    @Benchmark
    public long a1_partialRead_quickBuf() throws IOException {
        quickBufMsg.clearQuick();
        quickBufSource.setInput(complexBytes);
        quickBufMsg.mergeFrom(quickBufSource);
        return quickBufMsg.getOrderId() + quickBufMsg.getStatus() + quickBufMsg.getUser().getVipLevel();
    }

    @Benchmark
    public long a1_partialRead_lightProto() {
        lpComplexIn.setIndex(0, complexBytes.length);
        lightProtoMsg.parseFrom(lpComplexIn, complexBytes.length);
        return lightProtoMsg.getOrderId() + lightProtoMsg.getStatus() + lightProtoMsg.getUser().getVipLevel();
    }

    @Benchmark
    public long a1_partialRead_lazyCompat() {
        lazyBuilder.reuse(complexBytes, 0, complexBytes.length);
        return lazyBuilder.getOrderId() + lazyBuilder.getStatus()
                + lazyBuilder.getUserOrBuilder().getVipLevel();
    }

    @Benchmark
    public long a1_partialRead_lazyRaw() {
        rawMsg.wrap(complexBytes, 0, complexBytes.length);
        return rawMsg.getOrderId() + rawMsg.getStatus() + rawMsg.user().getVipLevel();
    }

    // =========================================================================
    // A2: Inspect 2 fields + Unmodified Passthrough (12.4 KB with unknowns)
    // =========================================================================

    @Benchmark
    public int a2_passthrough_officialPB() throws IOException {
        officialBuilder.clear().mergeFrom(complexBytes);
        return (officialBuilder.getOrderId() > 0 && officialBuilder.getStatus() == 1)
                ? writeOfficialToBuf(officialBuilder.build()) : 0;
    }

    @Benchmark
    public int a2_passthrough_quickBuf() throws IOException {
        quickBufMsg.clearQuick();
        quickBufSource.setInput(complexBytes);
        quickBufMsg.mergeFrom(quickBufSource);
        return (quickBufMsg.getOrderId() > 0 && quickBufMsg.getStatus() == 1)
                ? writeQuickBufToBuf() : 0;
    }

    @Benchmark
    public int a2_passthrough_lightProto() {
        lpComplexIn.setIndex(0, complexBytes.length);
        lightProtoMsg.parseFrom(lpComplexIn, complexBytes.length);
        return (lightProtoMsg.getOrderId() > 0 && lightProtoMsg.getStatus() == 1)
                ? writeLightProtoToBuf() : 0;
    }

    @Benchmark
    public int a2_passthrough_lazyCompat() {
        lazyBuilder.reuse(complexBytes, 0, complexBytes.length);
        return (lazyBuilder.getOrderId() > 0 && lazyBuilder.getStatus() == 1)
                ? consumeWritten(lazyBuilder.writeToBuffer(outBuf, 0)) : 0;
    }

    @Benchmark
    public int a2_passthrough_lazyRaw() {
        rawMsg.wrap(complexBytes, 0, complexBytes.length);
        return (rawMsg.getOrderId() > 0 && rawMsg.getStatus() == 1)
                ? consumeWritten(rawMsg.writeToBuffer(outBuf, 0)) : 0;
    }

    // =========================================================================
    // A2b: Passthrough WITHOUT unknown fields (lets LightProto hit _cachedSize)
    // =========================================================================

    @Benchmark
    public int a2b_passthroughNoUnk_lightProto() {
        lpComplexNoUnkIn.setIndex(0, complexNoUnknownBytes.length);
        lightProtoMsg.parseFrom(lpComplexNoUnkIn, complexNoUnknownBytes.length);
        return (lightProtoMsg.getOrderId() > 0 && lightProtoMsg.getStatus() == 1)
                ? writeLightProtoToBuf() : 0;
    }

    @Benchmark
    public int a2b_passthroughNoUnk_lazyRaw() {
        rawMsg.wrap(complexNoUnknownBytes, 0, complexNoUnknownBytes.length);
        return (rawMsg.getOrderId() > 0 && rawMsg.getStatus() == 1)
                ? consumeWritten(rawMsg.writeToBuffer(outBuf, 0)) : 0;
    }

    // =========================================================================
    // A3: Modify 3 Fixed / Same-Size Fields + Serialize (FP1)
    // =========================================================================

    @Benchmark
    public int a3_fixedMutate_officialPB() throws IOException {
        long ts = 1800000000000L + (iterCounter++ & 0xFF);
        officialBuilder.clear().mergeFrom(complexBytes).setStatus(2).setUpdatedAtFixed64(ts);
        officialBuilder.getUserBuilder().setScore(98.75);
        return writeOfficialToBuf(officialBuilder.build());
    }

    @Benchmark
    public int a3_fixedMutate_quickBuf() throws IOException {
        long ts = 1800000000000L + (iterCounter++ & 0xFF);
        quickBufMsg.clearQuick();
        quickBufSource.setInput(complexBytes);
        quickBufMsg.mergeFrom(quickBufSource).setStatus(2).setUpdatedAtFixed64(ts);
        quickBufMsg.getMutableUser().setScore(98.75);
        return writeQuickBufToBuf();
    }

    @Benchmark
    public int a3_fixedMutate_lightProto() {
        long ts = 1800000000000L + (iterCounter++ & 0xFF);
        lpComplexIn.setIndex(0, complexBytes.length);
        lightProtoMsg.parseFrom(lpComplexIn, complexBytes.length);
        lightProtoMsg.setStatus(2).setUpdatedAtFixed64(ts);
        lightProtoMsg.setUser().setScore(98.75);
        return writeLightProtoToBuf();
    }

    @Benchmark
    public int a3_fixedMutate_lazyCompat() {
        long ts = 1800000000000L + (iterCounter++ & 0xFF);
        lazyBuilder.reuse(complexBytes, 0, complexBytes.length).setStatus(2).setUpdatedAtFixed64(ts);
        lazyBuilder.getUserBuilder().setScore(98.75);
        return consumeWritten(lazyBuilder.writeToBuffer(outBuf, 0));
    }

    @Benchmark
    public int a3_fixedMutate_lazyRaw() {
        long ts = 1800000000000L + (iterCounter++ & 0xFF);
        rawMsg.wrap(complexBytes, 0, complexBytes.length).setStatus(2).setUpdatedAtFixed64(ts);
        rawMsg.user().setScore(98.75);
        return consumeWritten(rawMsg.writeToBuffer(outBuf, 0));
    }

    // =========================================================================
    // A4: Modify Var-Size String + Nested City + Serialize (FP2)
    // =========================================================================

    @Benchmark
    public int a4_varMutate_officialPB() throws IOException {
        officialBuilder.clear().mergeFrom(complexBytes)
                .setBuyerName("alexander_the_great_vip_buyer").setStatus(50000);
        officialBuilder.getUserBuilder().setCity("SHANGHAI_PUDONG");
        return writeOfficialToBuf(officialBuilder.build());
    }

    @Benchmark
    public int a4_varMutate_quickBuf() throws IOException {
        quickBufMsg.clearQuick();
        quickBufSource.setInput(complexBytes);
        quickBufMsg.mergeFrom(quickBufSource)
                .setBuyerName("alexander_the_great_vip_buyer").setStatus(50000);
        quickBufMsg.getMutableUser().setCity("SHANGHAI_PUDONG");
        return writeQuickBufToBuf();
    }

    @Benchmark
    public int a4_varMutate_lightProto() {
        lpComplexIn.setIndex(0, complexBytes.length);
        lightProtoMsg.parseFrom(lpComplexIn, complexBytes.length);
        lightProtoMsg.setBuyerName("alexander_the_great_vip_buyer").setStatus(50000);
        lightProtoMsg.setUser().setCity("SHANGHAI_PUDONG");
        return writeLightProtoToBuf();
    }

    @Benchmark
    public int a4_varMutate_lazyCompat() {
        lazyBuilder.reuse(complexBytes, 0, complexBytes.length)
                .setBuyerName("alexander_the_great_vip_buyer").setStatus(50000);
        lazyBuilder.getUserBuilder().setCity("SHANGHAI_PUDONG");
        return consumeWritten(lazyBuilder.writeToBuffer(outBuf, 0));
    }

    @Benchmark
    public int a4_varMutate_lazyRaw() {
        rawMsg.wrap(complexBytes, 0, complexBytes.length)
                .setBuyerName("alexander_the_great_vip_buyer").setStatus(50000);
        rawMsg.user().setCity("SHANGHAI_PUDONG");
        return consumeWritten(rawMsg.writeToBuffer(outBuf, 0));
    }

    // =========================================================================
    // A5: Mutate 1 of 100 Repeated LineItems + Serialize
    // =========================================================================

    @Benchmark
    public int a5_mutate1of100_officialPB() throws IOException {
        officialBuilder.clear().mergeFrom(complexBytes);
        officialBuilder.getItemsBuilder(42).setQuantity(999).setDescription("UPDATED_ITEM_42_DESC");
        return writeOfficialToBuf(officialBuilder.build());
    }

    @Benchmark
    public int a5_mutate1of100_quickBuf() throws IOException {
        quickBufMsg.clearQuick();
        quickBufSource.setInput(complexBytes);
        quickBufMsg.mergeFrom(quickBufSource);
        quickBufMsg.getMutableItems().get(42).setQuantity(999).setDescription("UPDATED_ITEM_42_DESC");
        return writeQuickBufToBuf();
    }

    @Benchmark
    public int a5_mutate1of100_lightProto() {
        lpComplexIn.setIndex(0, complexBytes.length);
        lightProtoMsg.parseFrom(lpComplexIn, complexBytes.length);
        lightProtoMsg.getItemAt(42).setQuantity(999).setDescription("UPDATED_ITEM_42_DESC");
        return writeLightProtoToBuf();
    }

    @Benchmark
    public int a5_mutate1of100_lazyCompat() {
        lazyBuilder.reuse(complexBytes, 0, complexBytes.length);
        lazyBuilder.getItemsBuilder(42).setQuantity(999).setDescription("UPDATED_ITEM_42_DESC");
        return consumeWritten(lazyBuilder.writeToBuffer(outBuf, 0));
    }

    @Benchmark
    public int a5_mutate1of100_lazyRaw() {
        rawMsg.wrap(complexBytes, 0, complexBytes.length);
        rawMsg.items(42).setQuantity(999).setDescription("UPDATED_ITEM_42_DESC");
        return consumeWritten(rawMsg.writeToBuffer(outBuf, 0));
    }

    // =========================================================================
    // A6: Gateway Redact 1 Sensitive Field + Forward
    // =========================================================================

    @Benchmark
    public int a6_redact_officialPB() throws IOException {
        officialBuilder.clear().mergeFrom(complexBytes).clearAuthToken();
        return writeOfficialToBuf(officialBuilder.build());
    }

    @Benchmark
    public int a6_redact_quickBuf() throws IOException {
        quickBufMsg.clearQuick();
        quickBufSource.setInput(complexBytes);
        quickBufMsg.mergeFrom(quickBufSource).clearAuthToken();
        return writeQuickBufToBuf();
    }

    @Benchmark
    public int a6_redact_lightProto() {
        lpComplexIn.setIndex(0, complexBytes.length);
        lightProtoMsg.parseFrom(lpComplexIn, complexBytes.length);
        lightProtoMsg.clearAuthToken();
        return writeLightProtoToBuf();
    }

    @Benchmark
    public int a6_redact_lazyCompat() {
        lazyBuilder.reuse(complexBytes, 0, complexBytes.length).clearAuthToken();
        return consumeWritten(lazyBuilder.writeToBuffer(outBuf, 0));
    }

    @Benchmark
    public int a6_redact_lazyRaw() {
        rawMsg.wrap(complexBytes, 0, complexBytes.length).clearAuthToken();
        return consumeWritten(rawMsg.writeToBuffer(outBuf, 0));
    }

    // =========================================================================
    // B1: Build from Scratch (10 items, 0% input reuse)
    // =========================================================================

    @Benchmark
    public int b1_buildScratch_officialPB() throws IOException {
        int iter = iterCounter++;
        officialBuilder.clear()
                .setOrderId(202611240001L + iter).setBuyerName("scratch_buyer").setStatus(1)
                .setUpdatedAtFixed64(1795497000123L).setPayload(ByteString.copyFrom(smallPayload))
                .setAuthToken("token-123456").setTraceId("trace-123456").setTotalCents(99000L);
        officialBuilder.getUserBuilder()
                .setUserId(88001122L).setCity("SHANGHAI").setVipLevel(3)
                .setScore(95.5).setEmail("alice@example.com");
        for (int i = 0; i < 10; i++) {
            officialBuilder.addItemsBuilder()
                    .setItemId(100L + i).setSku(SKU_TABLE[i]).setQuantity(2)
                    .setPriceCents(1500L).setDescription("Item desc");
        }
        return writeOfficialToBuf(officialBuilder.build());
    }

    @Benchmark
    public int b1_buildScratch_quickBuf() throws IOException {
        int iter = iterCounter++;
        quickBufMsg.clearQuick()
                .setOrderId(202611240001L + iter).setBuyerName("scratch_buyer").setStatus(1)
                .setUpdatedAtFixed64(1795497000123L)
                .setAuthToken("token-123456").setTraceId("trace-123456").setTotalCents(99000L);
        quickBufMsg.getMutablePayload().copyFrom(smallPayload);
        quickBufMsg.getMutableUser()
                .setUserId(88001122L).setCity("SHANGHAI").setVipLevel(3)
                .setScore(95.5).setEmail("alice@example.com");
        var items = quickBufMsg.getMutableItems();
        for (int i = 0; i < 10; i++) {
            items.next()
                    .setItemId(100L + i).setSku(SKU_TABLE[i]).setQuantity(2)
                    .setPriceCents(1500L).setDescription("Item desc");
        }
        return writeQuickBufToBuf();
    }

    @Benchmark
    public int b1_buildScratch_lightProto() {
        int iter = iterCounter++;
        lightProtoMsg.clear()
                .setOrderId(202611240001L + iter).setBuyerName("scratch_buyer").setStatus(1)
                .setUpdatedAtFixed64(1795497000123L).setPayload(smallPayload)
                .setAuthToken("token-123456").setTraceId("trace-123456").setTotalCents(99000L);
        lightProtoMsg.setUser()
                .setUserId(88001122L).setCity("SHANGHAI").setVipLevel(3)
                .setScore(95.5).setEmail("alice@example.com");
        for (int i = 0; i < 10; i++) {
            lightProtoMsg.addItem()
                    .setItemId(100L + i).setSku(SKU_TABLE[i]).setQuantity(2)
                    .setPriceCents(1500L).setDescription("Item desc");
        }
        return writeLightProtoToBuf();
    }

    @Benchmark
    public int b1_buildScratch_lazyCompat() {
        int iter = iterCounter++;
        lazyBuilder.clear()
                .setOrderId(202611240001L + iter).setBuyerName("scratch_buyer").setStatus(1)
                .setUpdatedAtFixed64(1795497000123L).setPayloadBytes(smallPayload)
                .setAuthToken("token-123456").setTraceId("trace-123456").setTotalCents(99000L);
        lazyBuilder.getUserBuilder()
                .setUserId(88001122L).setCity("SHANGHAI").setVipLevel(3)
                .setScore(95.5).setEmail("alice@example.com");
        for (int i = 0; i < 10; i++) {
            lazyBuilder.addItemsBuilder()
                    .setItemId(100L + i).setSku(SKU_TABLE[i]).setQuantity(2)
                    .setPriceCents(1500L).setDescription("Item desc");
        }
        return consumeWritten(lazyBuilder.writeToBuffer(outBuf, 0));
    }

    @Benchmark
    public int b1_buildScratch_lazyRaw() {
        int iter = iterCounter++;
        rawMsg.initScratch()
                .setOrderId(202611240001L + iter).setBuyerName("scratch_buyer").setStatus(1)
                .setUpdatedAtFixed64(1795497000123L).setPayloadBytes(smallPayload)
                .setAuthToken("token-123456").setTraceId("trace-123456").setTotalCents(99000L);
        rawMsg.user()
                .setUserId(88001122L).setCity("SHANGHAI").setVipLevel(3)
                .setScore(95.5).setEmail("alice@example.com");
        for (int i = 0; i < 10; i++) {
            rawMsg.addItem()
                    .setItemId(100L + i).setSku(SKU_TABLE[i]).setQuantity(2)
                    .setPriceCents(1500L).setDescription("Item desc");
        }
        return consumeWritten(rawMsg.writeToBuffer(outBuf, 0));
    }

    // =========================================================================
    // B2: 100% Full-Tree Read (All fields + all 100 LineItems, String decode)
    // =========================================================================

    @Benchmark
    public long b2_fullRead_officialPB() throws IOException {
        officialBuilder.clear().mergeFrom(complexBytes);
        return readOfficialBuilderFull(officialBuilder);
    }

    @Benchmark
    public long b2_fullRead_quickBuf() throws IOException {
        quickBufMsg.clearQuick();
        quickBufSource.setInput(complexBytes);
        quickBufMsg.mergeFrom(quickBufSource);
        return readQuickBufFull(quickBufMsg);
    }

    @Benchmark
    public long b2_fullRead_lightProto() {
        lpComplexIn.setIndex(0, complexBytes.length);
        lightProtoMsg.parseFrom(lpComplexIn, complexBytes.length);
        return readLightProtoFull(lightProtoMsg);
    }

    @Benchmark
    public long b2_fullRead_lazyCompat() {
        lazyBuilder.reuse(complexBytes, 0, complexBytes.length);
        return readLazyBuilderFull(lazyBuilder);
    }

    @Benchmark
    public long b2_fullRead_lazyRaw() {
        rawMsg.wrap(complexBytes, 0, complexBytes.length);
        return readRawFull(rawMsg);
    }

    // =========================================================================
    // B3: 100% Full-Tree Var-Size Mutation (20 items, 0% span reuse)
    // =========================================================================

    @Benchmark
    public int b3_fullMutate_officialPB() throws IOException {
        officialBuilder.clear().mergeFrom(mediumBytes);
        mutateOfficialBuilderFull(officialBuilder, newPayload);
        return writeOfficialToBuf(officialBuilder.build());
    }

    @Benchmark
    public int b3_fullMutate_quickBuf() throws IOException {
        quickBufMsg.clearQuick();
        quickBufSource.setInput(mediumBytes);
        quickBufMsg.mergeFrom(quickBufSource);
        mutateQuickBufFull(quickBufMsg, newPayload);
        return writeQuickBufToBuf();
    }

    @Benchmark
    public int b3_fullMutate_lightProto() {
        lpMediumIn.setIndex(0, mediumBytes.length);
        lightProtoMsg.parseFrom(lpMediumIn, mediumBytes.length);
        mutateLightProtoFull(lightProtoMsg, newPayload);
        return writeLightProtoToBuf();
    }

    @Benchmark
    public int b3_fullMutate_lazyCompat() {
        lazyBuilder.reuse(mediumBytes, 0, mediumBytes.length);
        mutateLazyBuilderFull(lazyBuilder, newPayload);
        return consumeWritten(lazyBuilder.writeToBuffer(outBuf, 0));
    }

    @Benchmark
    public int b3_fullMutate_lazyRaw() {
        rawMsg.wrap(mediumBytes, 0, mediumBytes.length);
        mutateRawFull(rawMsg, newPayload);
        return consumeWritten(rawMsg.writeToBuffer(outBuf, 0));
    }

    // =========================================================================
    // B4: Tiny 8-Byte Message (Read All 3 + Mutate All 3 Var-Size)
    // =========================================================================

    @Benchmark
    public int b4_tinyMutate_officialPB() throws IOException {
        officialBuilder.clear().mergeFrom(tinyBytes);
        long sum = officialBuilder.getOrderId() + officialBuilder.getStatus() + officialBuilder.getTotalCents();
        officialBuilder.setOrderId(sum + 100000000L).setStatus(30000).setTotalCents(999999999L);
        return writeOfficialToBuf(officialBuilder.build());
    }

    @Benchmark
    public int b4_tinyMutate_quickBuf() throws IOException {
        quickBufMsg.clearQuick();
        quickBufSource.setInput(tinyBytes);
        quickBufMsg.mergeFrom(quickBufSource);
        long sum = quickBufMsg.getOrderId() + quickBufMsg.getStatus() + quickBufMsg.getTotalCents();
        quickBufMsg.setOrderId(sum + 100000000L).setStatus(30000).setTotalCents(999999999L);
        return writeQuickBufToBuf();
    }

    @Benchmark
    public int b4_tinyMutate_lightProto() {
        lpTinyIn.setIndex(0, tinyBytes.length);
        lightProtoMsg.parseFrom(lpTinyIn, tinyBytes.length);
        long sum = lightProtoMsg.getOrderId() + lightProtoMsg.getStatus() + lightProtoMsg.getTotalCents();
        lightProtoMsg.setOrderId(sum + 100000000L).setStatus(30000).setTotalCents(999999999L);
        return writeLightProtoToBuf();
    }

    @Benchmark
    public int b4_tinyMutate_lazyCompat() {
        lazyBuilder.reuse(tinyBytes, 0, tinyBytes.length);
        long sum = lazyBuilder.getOrderId() + lazyBuilder.getStatus() + lazyBuilder.getTotalCents();
        lazyBuilder.setOrderId(sum + 100000000L).setStatus(30000).setTotalCents(999999999L);
        return consumeWritten(lazyBuilder.writeToBuffer(outBuf, 0));
    }

    @Benchmark
    public int b4_tinyMutate_lazyRaw() {
        rawMsg.wrap(tinyBytes, 0, tinyBytes.length);
        long sum = rawMsg.getOrderId() + rawMsg.getStatus() + rawMsg.getTotalCents();
        rawMsg.setOrderId(sum + 100000000L).setStatus(30000).setTotalCents(999999999L);
        return consumeWritten(rawMsg.writeToBuffer(outBuf, 0));
    }

    // =========================================================================
    // Shared helpers (package-private so CrossLibraryParityTest validates identical logic)
    // =========================================================================

    static long readOfficialBuilderFull(OrderEnvelope.Builder b) {
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

    static long readQuickBufFull(io.lazyproto.bench.quickbuf.OrderEnvelope msg) {
        long sum = msg.getOrderId() + msg.getBuyerName().length() + msg.getStatus()
                + msg.getUpdatedAtFixed64() + msg.getPayload().length()
                + msg.getAuthToken().length() + msg.getTraceId().length() + msg.getTotalCents();
        var u = msg.getUser();
        sum += u.getUserId() + u.getCity().length() + u.getVipLevel()
                + (long) u.getScore() + u.getEmail().length();
        var items = msg.getItems();
        int n = items.length();
        for (int i = 0; i < n; i++) {
            var item = items.get(i);
            sum += item.getItemId() + item.getSku().length() + item.getQuantity()
                    + item.getPriceCents() + item.getDescription().length();
        }
        return sum;
    }

    static long readLightProtoFull(io.lazyproto.bench.lightproto.OrderEnvelope msg) {
        long sum = msg.getOrderId() + msg.getBuyerName().length() + msg.getStatus()
                + msg.getUpdatedAtFixed64() + msg.getPayloadSize()
                + msg.getAuthToken().length() + msg.getTraceId().length() + msg.getTotalCents();
        var u = msg.getUser();
        sum += u.getUserId() + u.getCity().length() + u.getVipLevel()
                + (long) u.getScore() + u.getEmail().length();
        int n = msg.getItemsCount();
        for (int i = 0; i < n; i++) {
            var item = msg.getItemAt(i);
            sum += item.getItemId() + item.getSku().length() + item.getQuantity()
                    + item.getPriceCents() + item.getDescription().length();
        }
        return sum;
    }

    static long readLazyBuilderFull(LazyOrderEnvelope.Builder b) {
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

    static long readRawFull(RawOrderEnvelope msg) {
        long sum = msg.getOrderId() + msg.getBuyerName().length() + msg.getStatus()
                + msg.getUpdatedAtFixed64() + msg.getPayloadLength()
                + msg.getAuthToken().length() + msg.getTraceId().length() + msg.getTotalCents();
        var u = msg.user();
        sum += u.getUserId() + u.getCity().length() + u.getVipLevel()
                + (long) u.getScore() + u.getEmail().length();
        int n = msg.getItemsCount();
        for (int i = 0; i < n; i++) {
            var item = msg.items(i);
            sum += item.getItemId() + item.getSku().length() + item.getQuantity()
                    + item.getPriceCents() + item.getDescription().length();
        }
        return sum;
    }

    static void mutateOfficialBuilderFull(OrderEnvelope.Builder b, byte[] newPayload) {
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
                    .setItemId(9000000L + i).setSku(MUT_SKU_TABLE[i])
                    .setQuantity(50000).setPriceCents(10L).setDescription("Short");
        }
    }

    static void mutateQuickBufFull(io.lazyproto.bench.quickbuf.OrderEnvelope msg, byte[] newPayload) {
        msg.setOrderId(1L)
                .setBuyerName("mutated_full_tree_buyer_name_long")
                .setStatus(99999)
                .setUpdatedAtFixed64(42L)
                .setAuthToken("new_short_tok")
                .setTraceId("new_trace_id_full_mutation_long_string")
                .setTotalCents(5L);
        msg.getMutablePayload().copyFrom(newPayload);
        msg.getMutableUser()
                .setUserId(2L).setCity("GUANGZHOU_FULL_MUTATE")
                .setVipLevel(10000).setScore(12.34).setEmail("full_mutate_user@example.org");
        var items = msg.getMutableItems();
        int cnt = items.length();
        for (int i = 0; i < cnt; i++) {
            items.get(i)
                    .setItemId(9000000L + i).setSku(MUT_SKU_TABLE[i])
                    .setQuantity(50000).setPriceCents(10L).setDescription("Short");
        }
    }

    static void mutateLightProtoFull(io.lazyproto.bench.lightproto.OrderEnvelope msg, byte[] newPayload) {
        msg.setOrderId(1L)
                .setBuyerName("mutated_full_tree_buyer_name_long")
                .setStatus(99999)
                .setUpdatedAtFixed64(42L)
                .setPayload(newPayload)
                .setAuthToken("new_short_tok")
                .setTraceId("new_trace_id_full_mutation_long_string")
                .setTotalCents(5L);
        msg.setUser()
                .setUserId(2L).setCity("GUANGZHOU_FULL_MUTATE")
                .setVipLevel(10000).setScore(12.34).setEmail("full_mutate_user@example.org");
        int cnt = msg.getItemsCount();
        for (int i = 0; i < cnt; i++) {
            msg.getItemAt(i)
                    .setItemId(9000000L + i).setSku(MUT_SKU_TABLE[i])
                    .setQuantity(50000).setPriceCents(10L).setDescription("Short");
        }
    }

    static void mutateLazyBuilderFull(LazyOrderEnvelope.Builder b, byte[] newPayload) {
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
                    .setItemId(9000000L + i).setSku(MUT_SKU_TABLE[i])
                    .setQuantity(50000).setPriceCents(10L).setDescription("Short");
        }
    }

    static void mutateRawFull(RawOrderEnvelope raw, byte[] newPayload) {
        raw.setOrderId(1L)
                .setBuyerName("mutated_full_tree_buyer_name_long")
                .setStatus(99999)
                .setUpdatedAtFixed64(42L)
                .setPayloadBytes(newPayload)
                .setAuthToken("new_short_tok")
                .setTraceId("new_trace_id_full_mutation_long_string")
                .setTotalCents(5L);
        raw.user()
                .setUserId(2L).setCity("GUANGZHOU_FULL_MUTATE")
                .setVipLevel(10000).setScore(12.34).setEmail("full_mutate_user@example.org");
        int cnt = raw.getItemsCount();
        for (int i = 0; i < cnt; i++) {
            raw.items(i)
                    .setItemId(9000000L + i).setSku(MUT_SKU_TABLE[i])
                    .setQuantity(50000).setPriceCents(10L).setDescription("Short");
        }
    }

    public static void main(String[] args) throws Exception {
        String include = args.length > 0 ? args[0] : JavaProtoJmhBenchmark.class.getSimpleName();
        Options opt = new OptionsBuilder()
                .include(include)
                .addProfiler(GCProfiler.class)
                .forks(1)
                .warmupIterations(2)
                .warmupTime(TimeValue.milliseconds(400))
                .measurementIterations(3)
                .measurementTime(TimeValue.milliseconds(400))
                .build();
        new Runner(opt).run();
    }
}
