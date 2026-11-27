package io.lazyproto;

import com.google.protobuf.CodedOutputStream;
import io.lazyproto.bench.open.proto.MediaContent;
import io.lazyproto.bench.open.proto.OtelExportTraceRequest;
import io.lazyproto.bench.open.proto.PulsarMessageMetadata;
import io.lazyproto.bench.proto.OrderEnvelope;
import io.lazyproto.core.RawOrderEnvelope;
import io.lazyproto.core.RawWireView;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import us.hebi.quickbuf.ProtoSink;
import us.hebi.quickbuf.ProtoSource;

import java.io.IOException;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Verifies exact semantic parity across Official PB 3.25.5, QuickBuffers 1.4, LightProto 0.9.3,
 * and {@code lazy-proto} ({@link RawWireView} &amp; {@link RawOrderEnvelope}) for all open benchmark suites:
 * O1 ({@code jvm-serializers} {@code MediaContent}), O2 (Apache Pulsar {@code MessageMetadata}),
 * O3 (OpenTelemetry {@code ExportTraceServiceRequest}), and O4 (Selectivity Crossover Sweep).
 */
public class OpenBenchmarksParityTest {

    private static int writeOfficial(com.google.protobuf.MessageLite msg, byte[] outBuf) throws IOException {
        int size = msg.getSerializedSize();
        CodedOutputStream cos = CodedOutputStream.newInstance(outBuf, 0, size);
        msg.writeTo(cos);
        cos.checkNoSpaceLeft();
        return size;
    }

    @Test
    @DisplayName("O1 MediaContent, O2 Pulsar MessageMetadata, O3 OTel Trace, O4 Selectivity Sweep parity across 4 libraries")
    void verifyOpenBenchmarksParity() throws Exception {
        byte[] mediaBytes = OpenBenchmarkFixtures.createMediaContentBytes();
        byte[] pulsarBytes = OpenBenchmarkFixtures.createPulsarMetadataBytes();
        byte[] otelBytes = OpenBenchmarkFixtures.createOtelTraceRequestBytes();
        byte[] orderBytes = OrderEnvelope.parseFrom(LazyProtoCorrectnessTest.createComplexOrderBytes(100, 4096))
                .toBuilder().setUnknownFields(com.google.protobuf.UnknownFieldSet.getDefaultInstance())
                .build().toByteArray();

        byte[] outBuf = new byte[Math.max(otelBytes.length, orderBytes.length) + 8192];
        ByteBuf lpOut = Unpooled.wrappedBuffer(outBuf);
        ProtoSource qbSrc = ProtoSource.newArraySource();
        ProtoSink qbSink = ProtoSink.newArraySink();

        // Reusable objects
        MediaContent.Builder offMedia = MediaContent.newBuilder();
        io.lazyproto.bench.open.quickbuf.MediaContent qbMedia = io.lazyproto.bench.open.quickbuf.MediaContent.newInstance();
        io.lazyproto.bench.open.lightproto.MediaContent lpMedia = new io.lazyproto.bench.open.lightproto.MediaContent();
        ByteBuf lpMediaIn = Unpooled.wrappedBuffer(mediaBytes);
        RawWireView rawMedia = new RawWireView();

        PulsarMessageMetadata.Builder offPulsar = PulsarMessageMetadata.newBuilder();
        io.lazyproto.bench.open.quickbuf.PulsarMessageMetadata qbPulsar = io.lazyproto.bench.open.quickbuf.PulsarMessageMetadata.newInstance();
        io.lazyproto.bench.open.lightproto.PulsarMessageMetadata lpPulsar = new io.lazyproto.bench.open.lightproto.PulsarMessageMetadata();
        ByteBuf lpPulsarIn = Unpooled.wrappedBuffer(pulsarBytes);
        RawWireView rawPulsar = new RawWireView();

        OtelExportTraceRequest.Builder offOtel = OtelExportTraceRequest.newBuilder();
        io.lazyproto.bench.open.quickbuf.OtelExportTraceRequest qbOtel = io.lazyproto.bench.open.quickbuf.OtelExportTraceRequest.newInstance();
        io.lazyproto.bench.open.lightproto.OtelExportTraceRequest lpOtel = new io.lazyproto.bench.open.lightproto.OtelExportTraceRequest();
        ByteBuf lpOtelIn = Unpooled.wrappedBuffer(otelBytes);
        RawWireView rawOtel = new RawWireView();

        OrderEnvelope.Builder offOrder = OrderEnvelope.newBuilder();
        io.lazyproto.bench.quickbuf.OrderEnvelope qbOrder = io.lazyproto.bench.quickbuf.OrderEnvelope.newInstance();
        io.lazyproto.bench.lightproto.OrderEnvelope lpOrder = new io.lazyproto.bench.lightproto.OrderEnvelope();
        ByteBuf lpOrderIn = Unpooled.wrappedBuffer(orderBytes);
        RawOrderEnvelope rawOrder = new RawOrderEnvelope();

        for (int round = 0; round < 3; round++) {
            // --- O1a/O1b: MediaContent partial & full read ---
            offMedia.clear().mergeFrom(mediaBytes);
            long offO1a = offMedia.getMediaOrBuilder().getTitle().length()
                    + offMedia.getMediaOrBuilder().getDuration() + offMedia.getImageCount();
            long offO1b = OpenBenchmarksJmh.readOfficialMediaFull(offMedia);

            qbMedia.clearQuick();
            qbSrc.setInput(mediaBytes);
            qbMedia.mergeFrom(qbSrc);
            long qbO1a = qbMedia.getMedia().getTitle().length()
                    + qbMedia.getMedia().getDuration() + qbMedia.getImage().length();
            long qbO1b = OpenBenchmarksJmh.readQuickBufMediaFull(qbMedia);

            lpMediaIn.setIndex(0, mediaBytes.length);
            lpMedia.parseFrom(lpMediaIn, mediaBytes.length);
            long lpO1a = lpMedia.getMedia().getTitle().length()
                    + lpMedia.getMedia().getDuration() + lpMedia.getImagesCount();
            long lpO1b = OpenBenchmarksJmh.readLightProtoMediaFull(lpMedia);

            rawMedia.wrap(mediaBytes, 0, mediaBytes.length);
            long rawO1a = rawMedia.sub(1).getString(2).length()
                    + rawMedia.sub(1).getInt64(6) + rawMedia.repeatedCount(2);
            long rawO1b = OpenBenchmarksJmh.readRawMediaFull(rawMedia);

            assertEquals(offO1a, qbO1a, "O1a QuickBuf");
            assertEquals(offO1a, lpO1a, "O1a LightProto");
            assertEquals(offO1a, rawO1a, "O1a LazyRaw");
            assertEquals(offO1b, qbO1b, "O1b QuickBuf");
            assertEquals(offO1b, lpO1b, "O1b LightProto");
            assertEquals(offO1b, rawO1b, "O1b LazyRaw");

            // --- O1c: MediaContent mutate title + serialize ---
            offMedia.clear().mergeFrom(mediaBytes);
            offMedia.getMediaBuilder().setTitle("Javaone Keynote (HD Remastered)");
            MediaContent expectedO1c = MediaContent.parseFrom(Arrays.copyOf(outBuf, writeOfficial(offMedia.build(), outBuf)));

            qbMedia.clearQuick();
            qbSrc.setInput(mediaBytes);
            qbMedia.mergeFrom(qbSrc);
            qbMedia.getMutableMedia().setTitle("Javaone Keynote (HD Remastered)");
            qbSink.setOutput(outBuf);
            qbMedia.writeTo(qbSink);
            assertEquals(expectedO1c, MediaContent.parseFrom(Arrays.copyOf(outBuf, qbSink.getTotalBytesWritten())), "O1c QuickBuf");

            lpMediaIn.setIndex(0, mediaBytes.length);
            lpMedia.parseFrom(lpMediaIn, mediaBytes.length);
            lpMedia.setMedia().setTitle("Javaone Keynote (HD Remastered)");
            lpOut.clear();
            assertEquals(expectedO1c, MediaContent.parseFrom(Arrays.copyOf(outBuf, lpMedia.writeTo(lpOut))), "O1c LightProto");

            rawMedia.wrap(mediaBytes, 0, mediaBytes.length);
            rawMedia.sub(1).setString(2, "Javaone Keynote (HD Remastered)");
            assertEquals(expectedO1c, MediaContent.parseFrom(Arrays.copyOf(outBuf, rawMedia.writeToBuffer(outBuf, 0))), "O1c LazyRaw");

            // --- O2a/O2c: Pulsar MessageMetadata route & full read ---
            offPulsar.clear().mergeFrom(pulsarBytes);
            long offO2a = offPulsar.getPartitionKey().length() + offPulsar.getPublishTime() + offPulsar.getSequenceId();
            long offO2c = OpenBenchmarksJmh.readOfficialPulsarFull(offPulsar);

            qbPulsar.clearQuick();
            qbSrc.setInput(pulsarBytes);
            qbPulsar.mergeFrom(qbSrc);
            long qbO2a = qbPulsar.getPartitionKey().length() + qbPulsar.getPublishTime() + qbPulsar.getSequenceId();
            long qbO2c = OpenBenchmarksJmh.readQuickBufPulsarFull(qbPulsar);

            lpPulsarIn.setIndex(0, pulsarBytes.length);
            lpPulsar.parseFrom(lpPulsarIn, pulsarBytes.length);
            long lpO2a = lpPulsar.getPartitionKey().length() + lpPulsar.getPublishTime() + lpPulsar.getSequenceId();
            long lpO2c = OpenBenchmarksJmh.readLightProtoPulsarFull(lpPulsar);

            rawPulsar.wrap(pulsarBytes, 0, pulsarBytes.length);
            long rawO2a = rawPulsar.getString(6).length() + rawPulsar.getInt64(3) + rawPulsar.getInt64(2);
            long rawO2c = OpenBenchmarksJmh.readRawPulsarFull(rawPulsar);

            assertEquals(offO2a, qbO2a, "O2a QuickBuf");
            assertEquals(offO2a, lpO2a, "O2a LightProto");
            assertEquals(offO2a, rawO2a, "O2a LazyRaw");
            assertEquals(offO2c, qbO2c, "O2c QuickBuf");
            assertEquals(offO2c, lpO2c, "O2c LightProto");
            assertEquals(offO2c, rawO2c, "O2c LazyRaw");

            // --- O2b: Pulsar Broker Stamp & Forward ---
            offPulsar.clear().mergeFrom(pulsarBytes).setReplicatedFrom("us-west-2").setDeliverAtTime(1795497009999L);
            PulsarMessageMetadata expectedO2b = PulsarMessageMetadata.parseFrom(
                    Arrays.copyOf(outBuf, writeOfficial(offPulsar.build(), outBuf)));

            qbPulsar.clearQuick();
            qbSrc.setInput(pulsarBytes);
            qbPulsar.mergeFrom(qbSrc).setReplicatedFrom("us-west-2").setDeliverAtTime(1795497009999L);
            qbSink.setOutput(outBuf);
            qbPulsar.writeTo(qbSink);
            assertEquals(expectedO2b, PulsarMessageMetadata.parseFrom(Arrays.copyOf(outBuf, qbSink.getTotalBytesWritten())), "O2b QuickBuf");

            lpPulsarIn.setIndex(0, pulsarBytes.length);
            lpPulsar.parseFrom(lpPulsarIn, pulsarBytes.length);
            lpPulsar.setReplicatedFrom("us-west-2").setDeliverAtTime(1795497009999L);
            lpOut.clear();
            assertEquals(expectedO2b, PulsarMessageMetadata.parseFrom(Arrays.copyOf(outBuf, lpPulsar.writeTo(lpOut))), "O2b LightProto");

            rawPulsar.wrap(pulsarBytes, 0, pulsarBytes.length).setString(5, "us-west-2").setInt64(19, 1795497009999L);
            assertEquals(expectedO2b, PulsarMessageMetadata.parseFrom(Arrays.copyOf(outBuf, rawPulsar.writeToBuffer(outBuf, 0))), "O2b LazyRaw");

            // --- O3a: OTel Collector Route + Forward ---
            offOtel.clear().mergeFrom(otelBytes);
            OtelExportTraceRequest expectedO3a = OtelExportTraceRequest.parseFrom(
                    Arrays.copyOf(outBuf, writeOfficial(offOtel.build(), outBuf)));

            rawOtel.wrap(otelBytes, 0, otelBytes.length);
            assertEquals("checkout-api-gateway", rawOtel.repeatedSub(1, 0).sub(1).repeatedSub(1, 0).getString(2));
            assertEquals(expectedO3a, OtelExportTraceRequest.parseFrom(Arrays.copyOf(outBuf, rawOtel.writeToBuffer(outBuf, 0))), "O3a LazyRaw");

            // --- O3b: OTel Enrich Resource Attr + Schema URL ---
            offOtel.clear().mergeFrom(otelBytes);
            offOtel.getResourceSpansBuilder(0).setSchemaUrl("https://opentelemetry.io/schemas/1.26.0")
                    .getResourceBuilder().getAttributesBuilder(1).setValue("prod-us-east-1");
            OtelExportTraceRequest expectedO3b = OtelExportTraceRequest.parseFrom(
                    Arrays.copyOf(outBuf, writeOfficial(offOtel.build(), outBuf)));

            qbOtel.clearQuick();
            qbSrc.setInput(otelBytes);
            qbOtel.mergeFrom(qbSrc);
            var qbRs = qbOtel.getMutableResourceSpans().get(0);
            qbRs.setSchemaUrl("https://opentelemetry.io/schemas/1.26.0");
            qbRs.getMutableResource().getMutableAttributes().get(1).setValue("prod-us-east-1");
            qbSink.setOutput(outBuf);
            qbOtel.writeTo(qbSink);
            assertEquals(expectedO3b, OtelExportTraceRequest.parseFrom(Arrays.copyOf(outBuf, qbSink.getTotalBytesWritten())), "O3b QuickBuf");

            lpOtelIn.setIndex(0, otelBytes.length);
            lpOtel.parseFrom(lpOtelIn, otelBytes.length);
            var lpRs = lpOtel.getResourceSpanAt(0);
            lpRs.setSchemaUrl("https://opentelemetry.io/schemas/1.26.0");
            lpRs.getResource().getAttributeAt(1).setValue("prod-us-east-1");
            lpOut.clear();
            assertEquals(expectedO3b, OtelExportTraceRequest.parseFrom(Arrays.copyOf(outBuf, lpOtel.writeTo(lpOut))), "O3b LightProto");

            rawOtel.wrap(otelBytes, 0, otelBytes.length);
            RawWireView rawRs = rawOtel.repeatedSub(1, 0);
            rawRs.setString(3, "https://opentelemetry.io/schemas/1.26.0");
            rawRs.sub(1).repeatedSub(1, 1).setString(2, "prod-us-east-1");
            assertEquals(expectedO3b, OtelExportTraceRequest.parseFrom(Arrays.copyOf(outBuf, rawOtel.writeToBuffer(outBuf, 0))), "O3b LazyRaw");

            // --- O3c: OTel Scrub 1 PII Span Attr in 4-Level Nested Batch ---
            offOtel.clear().mergeFrom(otelBytes);
            offOtel.getResourceSpansBuilder(0).getScopeSpansBuilder(1)
                    .getSpansBuilder(12).getAttributesBuilder(3).setValue("[REDACTED]");
            OtelExportTraceRequest expectedO3c = OtelExportTraceRequest.parseFrom(
                    Arrays.copyOf(outBuf, writeOfficial(offOtel.build(), outBuf)));

            qbOtel.clearQuick();
            qbSrc.setInput(otelBytes);
            qbOtel.mergeFrom(qbSrc);
            qbOtel.getMutableResourceSpans().get(0).getMutableScopeSpans().get(1)
                    .getMutableSpans().get(12).getMutableAttributes().get(3).setValue("[REDACTED]");
            qbSink.setOutput(outBuf);
            qbOtel.writeTo(qbSink);
            assertEquals(expectedO3c, OtelExportTraceRequest.parseFrom(Arrays.copyOf(outBuf, qbSink.getTotalBytesWritten())), "O3c QuickBuf");

            lpOtelIn.setIndex(0, otelBytes.length);
            lpOtel.parseFrom(lpOtelIn, otelBytes.length);
            lpOtel.getResourceSpanAt(0).getScopeSpanAt(1).getSpanAt(12).getAttributeAt(3).setValue("[REDACTED]");
            lpOut.clear();
            assertEquals(expectedO3c, OtelExportTraceRequest.parseFrom(Arrays.copyOf(outBuf, lpOtel.writeTo(lpOut))), "O3c LightProto");

            rawOtel.wrap(otelBytes, 0, otelBytes.length);
            rawOtel.repeatedSub(1, 0).repeatedSub(2, 1).repeatedSub(2, 12).repeatedSub(9, 3).setString(2, "[REDACTED]");
            assertEquals(expectedO3c, OtelExportTraceRequest.parseFrom(Arrays.copyOf(outBuf, rawOtel.writeToBuffer(outBuf, 0))), "O3c LazyRaw");

            // --- O3d: OTel Full Traverse All 50 Spans x 6 Attrs ---
            offOtel.clear().mergeFrom(otelBytes);
            long offO3d = OpenBenchmarksJmh.readOfficialOtelFull(offOtel);
            qbOtel.clearQuick();
            qbSrc.setInput(otelBytes);
            qbOtel.mergeFrom(qbSrc);
            long qbO3d = OpenBenchmarksJmh.readQuickBufOtelFull(qbOtel);
            lpOtelIn.setIndex(0, otelBytes.length);
            lpOtel.parseFrom(lpOtelIn, otelBytes.length);
            long lpO3d = OpenBenchmarksJmh.readLightProtoOtelFull(lpOtel);
            rawOtel.wrap(otelBytes, 0, otelBytes.length);
            long rawO3d = OpenBenchmarksJmh.readRawOtelFull(rawOtel);

            assertEquals(offO3d, qbO3d, "O3d QuickBuf");
            assertEquals(offO3d, lpO3d, "O3d LightProto");
            assertEquals(offO3d, rawO3d, "O3d LazyRaw");

            // --- O4: Selectivity Crossover Sweep (25% = 25 items) ---
            int k = 25;
            offOrder.clear().mergeFrom(orderBytes);
            for (int i = 0; i < k; i++) offOrder.getItemsBuilder(i).setQuantity(999).setDescription("UPDATED_ITEM_DESC");
            OrderEnvelope expectedO4 = OrderEnvelope.parseFrom(Arrays.copyOf(outBuf, writeOfficial(offOrder.build(), outBuf)));

            qbOrder.clearQuick();
            qbSrc.setInput(orderBytes);
            qbOrder.mergeFrom(qbSrc);
            for (int i = 0; i < k; i++) qbOrder.getMutableItems().get(i).setQuantity(999).setDescription("UPDATED_ITEM_DESC");
            qbSink.setOutput(outBuf);
            qbOrder.writeTo(qbSink);
            assertEquals(expectedO4, OrderEnvelope.parseFrom(Arrays.copyOf(outBuf, qbSink.getTotalBytesWritten())), "O4 QuickBuf");

            lpOrderIn.setIndex(0, orderBytes.length);
            lpOrder.parseFrom(lpOrderIn, orderBytes.length);
            for (int i = 0; i < k; i++) lpOrder.getItemAt(i).setQuantity(999).setDescription("UPDATED_ITEM_DESC");
            lpOut.clear();
            assertEquals(expectedO4, OrderEnvelope.parseFrom(Arrays.copyOf(outBuf, lpOrder.writeTo(lpOut))), "O4 LightProto");

            rawOrder.wrap(orderBytes, 0, orderBytes.length);
            for (int i = 0; i < k; i++) rawOrder.items(i).setQuantity(999).setDescription("UPDATED_ITEM_DESC");
            assertEquals(expectedO4, OrderEnvelope.parseFrom(Arrays.copyOf(outBuf, rawOrder.writeToBuffer(outBuf, 0))), "O4 LazyRaw");
        }
    }
}
