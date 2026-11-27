package io.lazyproto;

import com.google.protobuf.ByteString;
import com.google.protobuf.CodedOutputStream;
import com.google.protobuf.UnknownFieldSet;
import io.lazyproto.bench.proto.OrderEnvelope;
import io.lazyproto.core.RawOrderEnvelope;
import io.lazyproto.model.LazyOrderProto.LazyOrderEnvelope;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import us.hebi.quickbuf.ProtoSink;
import us.hebi.quickbuf.ProtoSource;

import java.io.IOException;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies exact wire & semantic parity across all 5 Java Protobuf implementations
 * benchmarked in {@link JavaProtoJmhBenchmark}:
 * <ol>
 *   <li>Official {@code protobuf-java 3.25.5} ({@code protoc 3.25.5})</li>
 *   <li>QuickBuffers {@code 1.4} ({@code us.hebi.quickbuf}, default codegen)</li>
 *   <li>LightProto {@code 0.9.3} ({@code io.streamnative.lightproto} + Netty {@code ByteBuf})</li>
 *   <li>{@code lazy-proto} Compat ({@link LazyOrderEnvelope})</li>
 *   <li>{@code lazy-proto} Lower Level ({@link RawOrderEnvelope})</li>
 * </ol>
 *
 * <p>Also explicitly verifies and documents unknown-field preservation behavior:
 * Official PB, {@code LazyOrderEnvelope}, and {@code RawOrderEnvelope} preserve unknown fields (#50/#51),
 * whereas QuickBuffers (default) and LightProto skip unknown fields on decode.
 */
public class CrossLibraryParityTest {

    private static final String[] SKU_TABLE = {
            "SKU-000", "SKU-001", "SKU-002", "SKU-003", "SKU-004",
            "SKU-005", "SKU-006", "SKU-007", "SKU-008", "SKU-009"
    };

    private static OrderEnvelope stripUnknowns(OrderEnvelope msg) {
        return msg.toBuilder().setUnknownFields(UnknownFieldSet.getDefaultInstance()).build();
    }

    private static int writeOfficial(OrderEnvelope msg, byte[] outBuf) throws IOException {
        int size = msg.getSerializedSize();
        CodedOutputStream cos = CodedOutputStream.newInstance(outBuf, 0, size);
        msg.writeTo(cos);
        cos.checkNoSpaceLeft();
        return size;
    }

    private static int writeQuickBuf(io.lazyproto.bench.quickbuf.OrderEnvelope msg, ProtoSink sink, byte[] outBuf)
            throws IOException {
        sink.setOutput(outBuf);
        msg.writeTo(sink);
        return sink.getTotalBytesWritten();
    }

    private static int writeLightProto(io.lazyproto.bench.lightproto.OrderEnvelope msg, ByteBuf outBuf) {
        outBuf.clear();
        return msg.writeTo(outBuf);
    }

    private static OrderEnvelope decodeSlice(byte[] buf, int len) throws IOException {
        return OrderEnvelope.parseFrom(Arrays.copyOf(buf, len));
    }

    @Test
    @DisplayName("All 5 Java Protobuf libraries agree on A1-A6 & B1-B4 across repeated reuse iterations")
    void verifyFullCrossLibraryParityAndReuseIsolation() throws Exception {
        byte[] complexBytes = LazyProtoCorrectnessTest.createComplexOrderBytes(100, 4096);
        byte[] mediumBytes = LazyProtoCorrectnessTest.createComplexOrderBytes(20, 256);
        byte[] tinyBytes = OrderEnvelope.newBuilder()
                .setOrderId(1001L).setStatus(1).setTotalCents(5000L).build().toByteArray();
        byte[] smallPayload = new byte[256];
        byte[] newPayload = new byte[128];
        for (int i = 0; i < smallPayload.length; i++) smallPayload[i] = (byte) (i ^ 0x5A);
        for (int i = 0; i < newPayload.length; i++) newPayload[i] = (byte) (i * 7 + 3);

        // Reusable handles matching JMH benchmark state
        OrderEnvelope.Builder offB = OrderEnvelope.newBuilder();
        io.lazyproto.bench.quickbuf.OrderEnvelope qbMsg = io.lazyproto.bench.quickbuf.OrderEnvelope.newInstance();
        ProtoSource qbSrc = ProtoSource.newArraySource();
        ProtoSink qbSink = ProtoSink.newArraySink();
        io.lazyproto.bench.lightproto.OrderEnvelope lpMsg = new io.lazyproto.bench.lightproto.OrderEnvelope();
        ByteBuf lpComplexIn = Unpooled.wrappedBuffer(complexBytes);
        ByteBuf lpMediumIn = Unpooled.wrappedBuffer(mediumBytes);
        ByteBuf lpTinyIn = Unpooled.wrappedBuffer(tinyBytes);
        LazyOrderEnvelope.Builder lazyB = LazyOrderEnvelope.newBuilder();
        RawOrderEnvelope raw = new RawOrderEnvelope();

        byte[] outBuf = new byte[complexBytes.length + 8192];
        ByteBuf lpOut = Unpooled.wrappedBuffer(outBuf);

        // Run 3 rounds to guarantee reusable state has zero cross-iteration contamination
        for (int round = 0; round < 3; round++) {
            // --- A1: Partial Read ---
            offB.clear().mergeFrom(complexBytes);
            long offA1 = offB.getOrderId() + offB.getStatus() + offB.getUserOrBuilder().getVipLevel();

            qbMsg.clearQuick();
            qbSrc.setInput(complexBytes);
            qbMsg.mergeFrom(qbSrc);
            long qbA1 = qbMsg.getOrderId() + qbMsg.getStatus() + qbMsg.getUser().getVipLevel();

            lpComplexIn.setIndex(0, complexBytes.length);
            lpMsg.parseFrom(lpComplexIn, complexBytes.length);
            long lpA1 = lpMsg.getOrderId() + lpMsg.getStatus() + lpMsg.getUser().getVipLevel();

            lazyB.reuse(complexBytes, 0, complexBytes.length);
            long lazyA1 = lazyB.getOrderId() + lazyB.getStatus() + lazyB.getUserOrBuilder().getVipLevel();

            raw.wrap(complexBytes, 0, complexBytes.length);
            long rawA1 = raw.getOrderId() + raw.getStatus() + raw.user().getVipLevel();

            assertEquals(offA1, qbA1, "A1 QuickBuf mismatch");
            assertEquals(offA1, lpA1, "A1 LightProto mismatch");
            assertEquals(offA1, lazyA1, "A1 LazyCompat mismatch");
            assertEquals(offA1, rawA1, "A1 LazyRaw mismatch");

            // --- A2: Passthrough + Unknown Field Verification ---
            offB.clear().mergeFrom(complexBytes);
            OrderEnvelope offA2 = decodeSlice(outBuf, writeOfficial(offB.build(), outBuf));

            qbMsg.clearQuick();
            qbSrc.setInput(complexBytes);
            qbMsg.mergeFrom(qbSrc);
            OrderEnvelope qbA2 = decodeSlice(outBuf, writeQuickBuf(qbMsg, qbSink, outBuf));

            lpComplexIn.setIndex(0, complexBytes.length);
            lpMsg.parseFrom(lpComplexIn, complexBytes.length);
            OrderEnvelope lpA2 = decodeSlice(outBuf, writeLightProto(lpMsg, lpOut));

            lazyB.reuse(complexBytes, 0, complexBytes.length);
            OrderEnvelope lazyA2 = decodeSlice(outBuf, lazyB.writeToBuffer(outBuf, 0));

            raw.wrap(complexBytes, 0, complexBytes.length);
            OrderEnvelope rawA2 = decodeSlice(outBuf, raw.writeToBuffer(outBuf, 0));

            // Full equality including unknown fields #50/#51 for Official, LazyCompat, LazyRaw
            assertTrue(offA2.getUnknownFields().hasField(50), "Fixture must have unknown field #50");
            assertTrue(offA2.getUnknownFields().hasField(51), "Fixture must have unknown field #51");
            assertEquals(offA2, lazyA2, "A2 LazyCompat must preserve all fields + unknowns");
            assertEquals(offA2, rawA2, "A2 LazyRaw must preserve all fields + unknowns");
            // QuickBuffers (default) and LightProto skip unknown fields; all known fields must match identically
            assertFalse(qbA2.getUnknownFields().hasField(50), "Default QuickBuffers drops unknown fields");
            assertFalse(lpA2.getUnknownFields().hasField(50), "LightProto drops unknown fields");
            assertEquals(stripUnknowns(offA2), qbA2, "A2 QuickBuf known fields mismatch");
            assertEquals(stripUnknowns(offA2), lpA2, "A2 LightProto known fields mismatch");

            // --- A3: Modify 3 Fixed/Same-Size Fields ---
            long ts = 1800000000000L + round;
            offB.clear().mergeFrom(complexBytes).setStatus(2).setUpdatedAtFixed64(ts);
            offB.getUserBuilder().setScore(98.75);
            OrderEnvelope offA3 = decodeSlice(outBuf, writeOfficial(offB.build(), outBuf));

            qbMsg.clearQuick();
            qbSrc.setInput(complexBytes);
            qbMsg.mergeFrom(qbSrc).setStatus(2).setUpdatedAtFixed64(ts);
            qbMsg.getMutableUser().setScore(98.75);
            OrderEnvelope qbA3 = decodeSlice(outBuf, writeQuickBuf(qbMsg, qbSink, outBuf));

            lpComplexIn.setIndex(0, complexBytes.length);
            lpMsg.parseFrom(lpComplexIn, complexBytes.length);
            lpMsg.setStatus(2).setUpdatedAtFixed64(ts);
            lpMsg.setUser().setScore(98.75);
            OrderEnvelope lpA3 = decodeSlice(outBuf, writeLightProto(lpMsg, lpOut));

            lazyB.reuse(complexBytes, 0, complexBytes.length).setStatus(2).setUpdatedAtFixed64(ts);
            lazyB.getUserBuilder().setScore(98.75);
            OrderEnvelope lazyA3 = decodeSlice(outBuf, lazyB.writeToBuffer(outBuf, 0));

            raw.wrap(complexBytes, 0, complexBytes.length).setStatus(2).setUpdatedAtFixed64(ts);
            raw.user().setScore(98.75);
            OrderEnvelope rawA3 = decodeSlice(outBuf, raw.writeToBuffer(outBuf, 0));

            assertEquals(offA3, lazyA3, "A3 LazyCompat mismatch");
            assertEquals(offA3, rawA3, "A3 LazyRaw mismatch");
            assertEquals(stripUnknowns(offA3), qbA3, "A3 QuickBuf mismatch");
            assertEquals(stripUnknowns(offA3), lpA3, "A3 LightProto mismatch");

            // --- A4: Modify Var-Size String + Nested City ---
            offB.clear().mergeFrom(complexBytes)
                    .setBuyerName("alexander_the_great_vip_buyer").setStatus(50000);
            offB.getUserBuilder().setCity("SHANGHAI_PUDONG");
            OrderEnvelope offA4 = decodeSlice(outBuf, writeOfficial(offB.build(), outBuf));

            qbMsg.clearQuick();
            qbSrc.setInput(complexBytes);
            qbMsg.mergeFrom(qbSrc).setBuyerName("alexander_the_great_vip_buyer").setStatus(50000);
            qbMsg.getMutableUser().setCity("SHANGHAI_PUDONG");
            OrderEnvelope qbA4 = decodeSlice(outBuf, writeQuickBuf(qbMsg, qbSink, outBuf));

            lpComplexIn.setIndex(0, complexBytes.length);
            lpMsg.parseFrom(lpComplexIn, complexBytes.length);
            lpMsg.setBuyerName("alexander_the_great_vip_buyer").setStatus(50000);
            lpMsg.setUser().setCity("SHANGHAI_PUDONG");
            OrderEnvelope lpA4 = decodeSlice(outBuf, writeLightProto(lpMsg, lpOut));

            lazyB.reuse(complexBytes, 0, complexBytes.length)
                    .setBuyerName("alexander_the_great_vip_buyer").setStatus(50000);
            lazyB.getUserBuilder().setCity("SHANGHAI_PUDONG");
            OrderEnvelope lazyA4 = decodeSlice(outBuf, lazyB.writeToBuffer(outBuf, 0));

            raw.wrap(complexBytes, 0, complexBytes.length)
                    .setBuyerName("alexander_the_great_vip_buyer").setStatus(50000);
            raw.user().setCity("SHANGHAI_PUDONG");
            OrderEnvelope rawA4 = decodeSlice(outBuf, raw.writeToBuffer(outBuf, 0));

            assertEquals(offA4, lazyA4, "A4 LazyCompat mismatch");
            assertEquals(offA4, rawA4, "A4 LazyRaw mismatch");
            assertEquals(stripUnknowns(offA4), qbA4, "A4 QuickBuf mismatch");
            assertEquals(stripUnknowns(offA4), lpA4, "A4 LightProto mismatch");

            // --- A5: Mutate 1 of 100 Repeated LineItems ---
            offB.clear().mergeFrom(complexBytes);
            offB.getItemsBuilder(42).setQuantity(999).setDescription("UPDATED_ITEM_42_DESC");
            OrderEnvelope offA5 = decodeSlice(outBuf, writeOfficial(offB.build(), outBuf));

            qbMsg.clearQuick();
            qbSrc.setInput(complexBytes);
            qbMsg.mergeFrom(qbSrc);
            qbMsg.getMutableItems().get(42).setQuantity(999).setDescription("UPDATED_ITEM_42_DESC");
            OrderEnvelope qbA5 = decodeSlice(outBuf, writeQuickBuf(qbMsg, qbSink, outBuf));

            lpComplexIn.setIndex(0, complexBytes.length);
            lpMsg.parseFrom(lpComplexIn, complexBytes.length);
            lpMsg.getItemAt(42).setQuantity(999).setDescription("UPDATED_ITEM_42_DESC");
            OrderEnvelope lpA5 = decodeSlice(outBuf, writeLightProto(lpMsg, lpOut));

            lazyB.reuse(complexBytes, 0, complexBytes.length);
            lazyB.getItemsBuilder(42).setQuantity(999).setDescription("UPDATED_ITEM_42_DESC");
            OrderEnvelope lazyA5 = decodeSlice(outBuf, lazyB.writeToBuffer(outBuf, 0));

            raw.wrap(complexBytes, 0, complexBytes.length);
            raw.items(42).setQuantity(999).setDescription("UPDATED_ITEM_42_DESC");
            OrderEnvelope rawA5 = decodeSlice(outBuf, raw.writeToBuffer(outBuf, 0));

            assertEquals(offA5, lazyA5, "A5 LazyCompat mismatch");
            assertEquals(offA5, rawA5, "A5 LazyRaw mismatch");
            assertEquals(stripUnknowns(offA5), qbA5, "A5 QuickBuf mismatch");
            assertEquals(stripUnknowns(offA5), lpA5, "A5 LightProto mismatch");

            // --- A6: Redact auth_token ---
            offB.clear().mergeFrom(complexBytes).clearAuthToken();
            OrderEnvelope offA6 = decodeSlice(outBuf, writeOfficial(offB.build(), outBuf));

            qbMsg.clearQuick();
            qbSrc.setInput(complexBytes);
            qbMsg.mergeFrom(qbSrc).clearAuthToken();
            OrderEnvelope qbA6 = decodeSlice(outBuf, writeQuickBuf(qbMsg, qbSink, outBuf));

            lpComplexIn.setIndex(0, complexBytes.length);
            lpMsg.parseFrom(lpComplexIn, complexBytes.length);
            lpMsg.clearAuthToken();
            OrderEnvelope lpA6 = decodeSlice(outBuf, writeLightProto(lpMsg, lpOut));

            lazyB.reuse(complexBytes, 0, complexBytes.length).clearAuthToken();
            OrderEnvelope lazyA6 = decodeSlice(outBuf, lazyB.writeToBuffer(outBuf, 0));

            raw.wrap(complexBytes, 0, complexBytes.length).clearAuthToken();
            OrderEnvelope rawA6 = decodeSlice(outBuf, raw.writeToBuffer(outBuf, 0));

            assertEquals(offA6, lazyA6, "A6 LazyCompat mismatch");
            assertEquals(offA6, rawA6, "A6 LazyRaw mismatch");
            assertEquals(stripUnknowns(offA6), qbA6, "A6 QuickBuf mismatch");
            assertEquals(stripUnknowns(offA6), lpA6, "A6 LightProto mismatch");

            // --- B1: Build from Scratch (10 items) ---
            offB.clear().setOrderId(202611240001L + round).setBuyerName("scratch_buyer").setStatus(1)
                    .setUpdatedAtFixed64(1795497000123L).setPayload(ByteString.copyFrom(smallPayload))
                    .setAuthToken("token-123456").setTraceId("trace-123456").setTotalCents(99000L);
            offB.getUserBuilder().setUserId(88001122L).setCity("SHANGHAI").setVipLevel(3)
                    .setScore(95.5).setEmail("alice@example.com");
            for (int i = 0; i < 10; i++) {
                offB.addItemsBuilder().setItemId(100L + i).setSku(SKU_TABLE[i]).setQuantity(2)
                        .setPriceCents(1500L).setDescription("Item desc");
            }
            OrderEnvelope offB1 = decodeSlice(outBuf, writeOfficial(offB.build(), outBuf));

            qbMsg.clearQuick().setOrderId(202611240001L + round).setBuyerName("scratch_buyer").setStatus(1)
                    .setUpdatedAtFixed64(1795497000123L).setAuthToken("token-123456")
                    .setTraceId("trace-123456").setTotalCents(99000L);
            qbMsg.getMutablePayload().copyFrom(smallPayload);
            qbMsg.getMutableUser().setUserId(88001122L).setCity("SHANGHAI").setVipLevel(3)
                    .setScore(95.5).setEmail("alice@example.com");
            var qbItems = qbMsg.getMutableItems();
            for (int i = 0; i < 10; i++) {
                qbItems.next().setItemId(100L + i).setSku(SKU_TABLE[i]).setQuantity(2)
                        .setPriceCents(1500L).setDescription("Item desc");
            }
            OrderEnvelope qbB1 = decodeSlice(outBuf, writeQuickBuf(qbMsg, qbSink, outBuf));

            lpMsg.clear().setOrderId(202611240001L + round).setBuyerName("scratch_buyer").setStatus(1)
                    .setUpdatedAtFixed64(1795497000123L).setPayload(smallPayload)
                    .setAuthToken("token-123456").setTraceId("trace-123456").setTotalCents(99000L);
            lpMsg.setUser().setUserId(88001122L).setCity("SHANGHAI").setVipLevel(3)
                    .setScore(95.5).setEmail("alice@example.com");
            for (int i = 0; i < 10; i++) {
                lpMsg.addItem().setItemId(100L + i).setSku(SKU_TABLE[i]).setQuantity(2)
                        .setPriceCents(1500L).setDescription("Item desc");
            }
            OrderEnvelope lpB1 = decodeSlice(outBuf, writeLightProto(lpMsg, lpOut));

            lazyB.clear().setOrderId(202611240001L + round).setBuyerName("scratch_buyer").setStatus(1)
                    .setUpdatedAtFixed64(1795497000123L).setPayloadBytes(smallPayload)
                    .setAuthToken("token-123456").setTraceId("trace-123456").setTotalCents(99000L);
            lazyB.getUserBuilder().setUserId(88001122L).setCity("SHANGHAI").setVipLevel(3)
                    .setScore(95.5).setEmail("alice@example.com");
            for (int i = 0; i < 10; i++) {
                lazyB.addItemsBuilder().setItemId(100L + i).setSku(SKU_TABLE[i]).setQuantity(2)
                        .setPriceCents(1500L).setDescription("Item desc");
            }
            OrderEnvelope lazyB1 = decodeSlice(outBuf, lazyB.writeToBuffer(outBuf, 0));

            raw.initScratch().setOrderId(202611240001L + round).setBuyerName("scratch_buyer").setStatus(1)
                    .setUpdatedAtFixed64(1795497000123L).setPayloadBytes(smallPayload)
                    .setAuthToken("token-123456").setTraceId("trace-123456").setTotalCents(99000L);
            raw.user().setUserId(88001122L).setCity("SHANGHAI").setVipLevel(3)
                    .setScore(95.5).setEmail("alice@example.com");
            for (int i = 0; i < 10; i++) {
                raw.addItem().setItemId(100L + i).setSku(SKU_TABLE[i]).setQuantity(2)
                        .setPriceCents(1500L).setDescription("Item desc");
            }
            OrderEnvelope rawB1 = decodeSlice(outBuf, raw.writeToBuffer(outBuf, 0));

            assertEquals(offB1, qbB1, "B1 QuickBuf mismatch");
            assertEquals(offB1, lpB1, "B1 LightProto mismatch");
            assertEquals(offB1, lazyB1, "B1 LazyCompat mismatch");
            assertEquals(offB1, rawB1, "B1 LazyRaw mismatch");

            // --- B2: 100% Full-Tree Read ---
            offB.clear().mergeFrom(complexBytes);
            long offB2 = JavaProtoJmhBenchmark.readOfficialBuilderFull(offB);

            qbMsg.clearQuick();
            qbSrc.setInput(complexBytes);
            qbMsg.mergeFrom(qbSrc);
            long qbB2 = JavaProtoJmhBenchmark.readQuickBufFull(qbMsg);

            lpComplexIn.setIndex(0, complexBytes.length);
            lpMsg.parseFrom(lpComplexIn, complexBytes.length);
            long lpB2 = JavaProtoJmhBenchmark.readLightProtoFull(lpMsg);

            lazyB.reuse(complexBytes, 0, complexBytes.length);
            long lazyB2 = JavaProtoJmhBenchmark.readLazyBuilderFull(lazyB);

            raw.wrap(complexBytes, 0, complexBytes.length);
            long rawB2 = JavaProtoJmhBenchmark.readRawFull(raw);

            assertEquals(offB2, qbB2, "B2 QuickBuf checksum mismatch");
            assertEquals(offB2, lpB2, "B2 LightProto checksum mismatch");
            assertEquals(offB2, lazyB2, "B2 LazyCompat checksum mismatch");
            assertEquals(offB2, rawB2, "B2 LazyRaw checksum mismatch");

            // --- B3: 100% Full-Tree Var-Size Mutation (20 items) ---
            offB.clear().mergeFrom(mediumBytes);
            JavaProtoJmhBenchmark.mutateOfficialBuilderFull(offB, newPayload);
            OrderEnvelope offB3 = decodeSlice(outBuf, writeOfficial(offB.build(), outBuf));

            qbMsg.clearQuick();
            qbSrc.setInput(mediumBytes);
            qbMsg.mergeFrom(qbSrc);
            JavaProtoJmhBenchmark.mutateQuickBufFull(qbMsg, newPayload);
            OrderEnvelope qbB3 = decodeSlice(outBuf, writeQuickBuf(qbMsg, qbSink, outBuf));

            lpMediumIn.setIndex(0, mediumBytes.length);
            lpMsg.parseFrom(lpMediumIn, mediumBytes.length);
            JavaProtoJmhBenchmark.mutateLightProtoFull(lpMsg, newPayload);
            OrderEnvelope lpB3 = decodeSlice(outBuf, writeLightProto(lpMsg, lpOut));

            lazyB.reuse(mediumBytes, 0, mediumBytes.length);
            JavaProtoJmhBenchmark.mutateLazyBuilderFull(lazyB, newPayload);
            OrderEnvelope lazyB3 = decodeSlice(outBuf, lazyB.writeToBuffer(outBuf, 0));

            raw.wrap(mediumBytes, 0, mediumBytes.length);
            JavaProtoJmhBenchmark.mutateRawFull(raw, newPayload);
            OrderEnvelope rawB3 = decodeSlice(outBuf, raw.writeToBuffer(outBuf, 0));

            assertEquals(offB3, lazyB3, "B3 LazyCompat mismatch");
            assertEquals(offB3, rawB3, "B3 LazyRaw mismatch");
            assertEquals(stripUnknowns(offB3), qbB3, "B3 QuickBuf mismatch");
            assertEquals(stripUnknowns(offB3), lpB3, "B3 LightProto mismatch");

            // --- B4: Tiny Message Read + Mutate ---
            offB.clear().mergeFrom(tinyBytes);
            long offSum = offB.getOrderId() + offB.getStatus() + offB.getTotalCents();
            offB.setOrderId(offSum + 100000000L).setStatus(30000).setTotalCents(999999999L);
            OrderEnvelope offB4 = decodeSlice(outBuf, writeOfficial(offB.build(), outBuf));

            qbMsg.clearQuick();
            qbSrc.setInput(tinyBytes);
            qbMsg.mergeFrom(qbSrc);
            long qbSum = qbMsg.getOrderId() + qbMsg.getStatus() + qbMsg.getTotalCents();
            qbMsg.setOrderId(qbSum + 100000000L).setStatus(30000).setTotalCents(999999999L);
            OrderEnvelope qbB4 = decodeSlice(outBuf, writeQuickBuf(qbMsg, qbSink, outBuf));

            lpTinyIn.setIndex(0, tinyBytes.length);
            lpMsg.parseFrom(lpTinyIn, tinyBytes.length);
            long lpSum = lpMsg.getOrderId() + lpMsg.getStatus() + lpMsg.getTotalCents();
            lpMsg.setOrderId(lpSum + 100000000L).setStatus(30000).setTotalCents(999999999L);
            OrderEnvelope lpB4 = decodeSlice(outBuf, writeLightProto(lpMsg, lpOut));

            lazyB.reuse(tinyBytes, 0, tinyBytes.length);
            long lazySum = lazyB.getOrderId() + lazyB.getStatus() + lazyB.getTotalCents();
            lazyB.setOrderId(lazySum + 100000000L).setStatus(30000).setTotalCents(999999999L);
            OrderEnvelope lazyB4 = decodeSlice(outBuf, lazyB.writeToBuffer(outBuf, 0));

            raw.wrap(tinyBytes, 0, tinyBytes.length);
            long rawSum = raw.getOrderId() + raw.getStatus() + raw.getTotalCents();
            raw.setOrderId(rawSum + 100000000L).setStatus(30000).setTotalCents(999999999L);
            OrderEnvelope rawB4 = decodeSlice(outBuf, raw.writeToBuffer(outBuf, 0));

            assertEquals(offB4, qbB4, "B4 QuickBuf mismatch");
            assertEquals(offB4, lpB4, "B4 LightProto mismatch");
            assertEquals(offB4, lazyB4, "B4 LazyCompat mismatch");
            assertEquals(offB4, rawB4, "B4 LazyRaw mismatch");
        }
    }
}
