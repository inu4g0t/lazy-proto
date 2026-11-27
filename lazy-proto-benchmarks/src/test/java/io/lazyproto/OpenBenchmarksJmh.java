package io.lazyproto;

import com.google.protobuf.CodedOutputStream;
import com.google.protobuf.MessageLite;
import com.google.protobuf.UnknownFieldSet;
import io.lazyproto.bench.open.proto.MediaContent;
import io.lazyproto.bench.open.proto.OtelExportTraceRequest;
import io.lazyproto.bench.open.proto.PulsarMessageMetadata;
import io.lazyproto.bench.proto.OrderEnvelope;
import io.lazyproto.core.RawOrderEnvelope;
import io.lazyproto.core.RawWireView;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.profile.GCProfiler;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;
import org.openjdk.jmh.runner.options.TimeValue;
import us.hebi.quickbuf.ProtoMessage;
import us.hebi.quickbuf.ProtoSink;
import us.hebi.quickbuf.ProtoSource;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

/**
 * Open-Ecosystem Java Protobuf JMH Benchmark Suite ({@code -prof gc}):
 * <ul>
 *   <li><b>O1 — {@code jvm-serializers} {@code MediaContent}</b> (~250 B): partial read, full deserialize, mutate title + serialize.</li>
 *   <li><b>O2 — Apache Pulsar {@code MessageMetadata}</b> (~550 B, 10 props): broker routing, broker stamp + forward, full property read.</li>
 *   <li><b>O3 — OpenTelemetry {@code ExportTraceServiceRequest}</b> (~17 KB, 4-level deep, 50 spans × 6 attrs): collector route + forward,
 *       enrich resource attr + forward, redact 1 PII span attr + forward, full ingest traversal.</li>
 *   <li><b>O4 — Mutation Selectivity Crossover Sweep</b> (12.4 KB, 100 items, no unknown fields): mutate {@code 1% / 10% / 25% / 50% / 100%}
 *       of repeated items + serialize, pinpointing the exact crossover point between {@code lazy-proto} and LightProto / QuickBuffers.</li>
 * </ul>
 *
 * <p>Note: Suites O1–O3 compare <b>schema-less generic {@link RawWireView}</b> (zero codegen) directly against
 * code-generated Official PB 3.25.5, QuickBuffers 1.4, and LightProto 0.9.3! Suite O4 uses typed {@link RawOrderEnvelope}.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Thread)
@Fork(1)
@Warmup(iterations = 2, time = 350, timeUnit = TimeUnit.MILLISECONDS)
@Measurement(iterations = 3, time = 350, timeUnit = TimeUnit.MILLISECONDS)
public class OpenBenchmarksJmh {

    private byte[] mediaBytes;
    private byte[] pulsarBytes;
    private byte[] otelBytes;
    private byte[] orderBytesNoUnk;
    private byte[] outBuf;

    // Shared QuickBuffers & LightProto IO buffers
    private ProtoSource qbSrc;
    private ProtoSink qbSink;
    private ByteBuf lpOut;

    // O1: MediaContent
    private MediaContent.Builder offMedia;
    private io.lazyproto.bench.open.quickbuf.MediaContent qbMedia;
    private io.lazyproto.bench.open.lightproto.MediaContent lpMedia;
    private ByteBuf lpMediaIn;
    private RawWireView rawMedia;

    // O2: Pulsar MessageMetadata
    private PulsarMessageMetadata.Builder offPulsar;
    private io.lazyproto.bench.open.quickbuf.PulsarMessageMetadata qbPulsar;
    private io.lazyproto.bench.open.lightproto.PulsarMessageMetadata lpPulsar;
    private ByteBuf lpPulsarIn;
    private RawWireView rawPulsar;

    // O3: OpenTelemetry ExportTraceServiceRequest
    private OtelExportTraceRequest.Builder offOtel;
    private io.lazyproto.bench.open.quickbuf.OtelExportTraceRequest qbOtel;
    private io.lazyproto.bench.open.lightproto.OtelExportTraceRequest lpOtel;
    private ByteBuf lpOtelIn;
    private RawWireView rawOtel;

    // O4: OrderEnvelope Selectivity Sweep
    private OrderEnvelope.Builder offOrder;
    private io.lazyproto.bench.quickbuf.OrderEnvelope qbOrder;
    private io.lazyproto.bench.lightproto.OrderEnvelope lpOrder;
    private ByteBuf lpOrderIn;
    private RawOrderEnvelope rawOrder;

    @Setup(Level.Trial)
    public void setup() throws IOException {
        mediaBytes = OpenBenchmarkFixtures.createMediaContentBytes();
        pulsarBytes = OpenBenchmarkFixtures.createPulsarMetadataBytes();
        otelBytes = OpenBenchmarkFixtures.createOtelTraceRequestBytes();
        orderBytesNoUnk = OrderEnvelope.parseFrom(LazyProtoCorrectnessTest.createComplexOrderBytes(100, 4096))
                .toBuilder().setUnknownFields(UnknownFieldSet.getDefaultInstance()).build().toByteArray();
        outBuf = new byte[Math.max(otelBytes.length, orderBytesNoUnk.length) + 8192];

        qbSrc = ProtoSource.newArraySource();
        qbSink = ProtoSink.newArraySink();
        lpOut = Unpooled.wrappedBuffer(outBuf);

        offMedia = MediaContent.newBuilder();
        qbMedia = io.lazyproto.bench.open.quickbuf.MediaContent.newInstance();
        lpMedia = new io.lazyproto.bench.open.lightproto.MediaContent();
        lpMediaIn = Unpooled.wrappedBuffer(mediaBytes);
        rawMedia = new RawWireView();

        offPulsar = PulsarMessageMetadata.newBuilder();
        qbPulsar = io.lazyproto.bench.open.quickbuf.PulsarMessageMetadata.newInstance();
        lpPulsar = new io.lazyproto.bench.open.lightproto.PulsarMessageMetadata();
        lpPulsarIn = Unpooled.wrappedBuffer(pulsarBytes);
        rawPulsar = new RawWireView();

        offOtel = OtelExportTraceRequest.newBuilder();
        qbOtel = io.lazyproto.bench.open.quickbuf.OtelExportTraceRequest.newInstance();
        lpOtel = new io.lazyproto.bench.open.lightproto.OtelExportTraceRequest();
        lpOtelIn = Unpooled.wrappedBuffer(otelBytes);
        rawOtel = new RawWireView();

        offOrder = OrderEnvelope.newBuilder();
        qbOrder = io.lazyproto.bench.quickbuf.OrderEnvelope.newInstance();
        lpOrder = new io.lazyproto.bench.lightproto.OrderEnvelope();
        lpOrderIn = Unpooled.wrappedBuffer(orderBytesNoUnk);
        rawOrder = new RawOrderEnvelope();
    }

    private int consumeWritten(int written) {
        return written + (outBuf[0] & 0xFF) + (outBuf[written - 1] & 0xFF);
    }

    private int writeOfficial(MessageLite msg) throws IOException {
        int size = msg.getSerializedSize();
        CodedOutputStream cos = CodedOutputStream.newInstance(outBuf, 0, size);
        msg.writeTo(cos);
        cos.checkNoSpaceLeft();
        return consumeWritten(size);
    }

    private int writeQuickBuf(ProtoMessage<?> msg) throws IOException {
        qbSink.setOutput(outBuf);
        msg.writeTo(qbSink);
        return consumeWritten(qbSink.getTotalBytesWritten());
    }

    // =========================================================================
    // O1: jvm-serializers MediaContent (~250 B)
    // =========================================================================

    @Benchmark
    public long o1a_mediaPartial_officialPB() throws IOException {
        offMedia.clear().mergeFrom(mediaBytes);
        return offMedia.getMediaOrBuilder().getTitle().length()
                + offMedia.getMediaOrBuilder().getDuration() + offMedia.getImageCount();
    }

    @Benchmark
    public long o1a_mediaPartial_quickBuf() throws IOException {
        qbMedia.clearQuick();
        qbSrc.setInput(mediaBytes);
        qbMedia.mergeFrom(qbSrc);
        return qbMedia.getMedia().getTitle().length()
                + qbMedia.getMedia().getDuration() + qbMedia.getImage().length();
    }

    @Benchmark
    public long o1a_mediaPartial_lightProto() {
        lpMediaIn.setIndex(0, mediaBytes.length);
        lpMedia.parseFrom(lpMediaIn, mediaBytes.length);
        return lpMedia.getMedia().getTitle().length()
                + lpMedia.getMedia().getDuration() + lpMedia.getImagesCount();
    }

    @Benchmark
    public long o1a_mediaPartial_lazyRawView() {
        rawMedia.wrap(mediaBytes, 0, mediaBytes.length);
        RawWireView m = rawMedia.sub(1);
        return m.getString(2).length() + m.getInt64(6) + rawMedia.repeatedCount(2);
    }

    @Benchmark
    public long o1b_mediaFull_officialPB() throws IOException {
        offMedia.clear().mergeFrom(mediaBytes);
        return readOfficialMediaFull(offMedia);
    }

    @Benchmark
    public long o1b_mediaFull_quickBuf() throws IOException {
        qbMedia.clearQuick();
        qbSrc.setInput(mediaBytes);
        qbMedia.mergeFrom(qbSrc);
        return readQuickBufMediaFull(qbMedia);
    }

    @Benchmark
    public long o1b_mediaFull_lightProto() {
        lpMediaIn.setIndex(0, mediaBytes.length);
        lpMedia.parseFrom(lpMediaIn, mediaBytes.length);
        return readLightProtoMediaFull(lpMedia);
    }

    @Benchmark
    public long o1b_mediaFull_lazyRawView() {
        rawMedia.wrap(mediaBytes, 0, mediaBytes.length);
        return readRawMediaFull(rawMedia);
    }

    @Benchmark
    public int o1c_mediaMutate_officialPB() throws IOException {
        offMedia.clear().mergeFrom(mediaBytes);
        offMedia.getMediaBuilder().setTitle("Javaone Keynote (HD Remastered)");
        return writeOfficial(offMedia.build());
    }

    @Benchmark
    public int o1c_mediaMutate_quickBuf() throws IOException {
        qbMedia.clearQuick();
        qbSrc.setInput(mediaBytes);
        qbMedia.mergeFrom(qbSrc);
        qbMedia.getMutableMedia().setTitle("Javaone Keynote (HD Remastered)");
        return writeQuickBuf(qbMedia);
    }

    @Benchmark
    public int o1c_mediaMutate_lightProto() {
        lpMediaIn.setIndex(0, mediaBytes.length);
        lpMedia.parseFrom(lpMediaIn, mediaBytes.length);
        lpMedia.setMedia().setTitle("Javaone Keynote (HD Remastered)");
        lpOut.clear();
        return consumeWritten(lpMedia.writeTo(lpOut));
    }

    @Benchmark
    public int o1c_mediaMutate_lazyRawView() {
        rawMedia.wrap(mediaBytes, 0, mediaBytes.length);
        rawMedia.sub(1).setString(2, "Javaone Keynote (HD Remastered)");
        return consumeWritten(rawMedia.writeToBuffer(outBuf, 0));
    }

    // =========================================================================
    // O2: Apache Pulsar MessageMetadata (~550 B, 10 KeyValue properties)
    // =========================================================================

    @Benchmark
    public long o2a_pulsarRoute_officialPB() throws IOException {
        offPulsar.clear().mergeFrom(pulsarBytes);
        return offPulsar.getPartitionKey().length() + offPulsar.getPublishTime() + offPulsar.getSequenceId();
    }

    @Benchmark
    public long o2a_pulsarRoute_quickBuf() throws IOException {
        qbPulsar.clearQuick();
        qbSrc.setInput(pulsarBytes);
        qbPulsar.mergeFrom(qbSrc);
        return qbPulsar.getPartitionKey().length() + qbPulsar.getPublishTime() + qbPulsar.getSequenceId();
    }

    @Benchmark
    public long o2a_pulsarRoute_lightProto() {
        lpPulsarIn.setIndex(0, pulsarBytes.length);
        lpPulsar.parseFrom(lpPulsarIn, pulsarBytes.length);
        return lpPulsar.getPartitionKey().length() + lpPulsar.getPublishTime() + lpPulsar.getSequenceId();
    }

    @Benchmark
    public long o2a_pulsarRoute_lazyRawView() {
        rawPulsar.wrap(pulsarBytes, 0, pulsarBytes.length);
        return rawPulsar.getString(6).length() + rawPulsar.getInt64(3) + rawPulsar.getInt64(2);
    }

    @Benchmark
    public int o2b_pulsarStamp_officialPB() throws IOException {
        offPulsar.clear().mergeFrom(pulsarBytes).setReplicatedFrom("us-west-2").setDeliverAtTime(1795497009999L);
        return writeOfficial(offPulsar.build());
    }

    @Benchmark
    public int o2b_pulsarStamp_quickBuf() throws IOException {
        qbPulsar.clearQuick();
        qbSrc.setInput(pulsarBytes);
        qbPulsar.mergeFrom(qbSrc).setReplicatedFrom("us-west-2").setDeliverAtTime(1795497009999L);
        return writeQuickBuf(qbPulsar);
    }

    @Benchmark
    public int o2b_pulsarStamp_lightProto() {
        lpPulsarIn.setIndex(0, pulsarBytes.length);
        lpPulsar.parseFrom(lpPulsarIn, pulsarBytes.length);
        lpPulsar.setReplicatedFrom("us-west-2").setDeliverAtTime(1795497009999L);
        lpOut.clear();
        return consumeWritten(lpPulsar.writeTo(lpOut));
    }

    @Benchmark
    public int o2b_pulsarStamp_lazyRawView() {
        rawPulsar.wrap(pulsarBytes, 0, pulsarBytes.length).setString(5, "us-west-2").setInt64(19, 1795497009999L);
        return consumeWritten(rawPulsar.writeToBuffer(outBuf, 0));
    }

    @Benchmark
    public long o2c_pulsarFull_officialPB() throws IOException {
        offPulsar.clear().mergeFrom(pulsarBytes);
        return readOfficialPulsarFull(offPulsar);
    }

    @Benchmark
    public long o2c_pulsarFull_quickBuf() throws IOException {
        qbPulsar.clearQuick();
        qbSrc.setInput(pulsarBytes);
        qbPulsar.mergeFrom(qbSrc);
        return readQuickBufPulsarFull(qbPulsar);
    }

    @Benchmark
    public long o2c_pulsarFull_lightProto() {
        lpPulsarIn.setIndex(0, pulsarBytes.length);
        lpPulsar.parseFrom(lpPulsarIn, pulsarBytes.length);
        return readLightProtoPulsarFull(lpPulsar);
    }

    @Benchmark
    public long o2c_pulsarFull_lazyRawView() {
        rawPulsar.wrap(pulsarBytes, 0, pulsarBytes.length);
        return readRawPulsarFull(rawPulsar);
    }

    // =========================================================================
    // O3: OpenTelemetry ExportTraceServiceRequest (~17 KB, 4-level nesting)
    // =========================================================================

    @Benchmark
    public int o3a_otelRoute_officialPB() throws IOException {
        offOtel.clear().mergeFrom(otelBytes);
        int len = offOtel.getResourceSpansOrBuilder(0).getResourceOrBuilder().getAttributesOrBuilder(0).getValue().length();
        return len > 0 ? writeOfficial(offOtel.build()) : 0;
    }

    @Benchmark
    public int o3a_otelRoute_quickBuf() throws IOException {
        qbOtel.clearQuick();
        qbSrc.setInput(otelBytes);
        qbOtel.mergeFrom(qbSrc);
        int len = qbOtel.getResourceSpans().get(0).getResource().getAttributes().get(0).getValue().length();
        return len > 0 ? writeQuickBuf(qbOtel) : 0;
    }

    @Benchmark
    public int o3a_otelRoute_lightProto() {
        lpOtelIn.setIndex(0, otelBytes.length);
        lpOtel.parseFrom(lpOtelIn, otelBytes.length);
        int len = lpOtel.getResourceSpanAt(0).getResource().getAttributeAt(0).getValue().length();
        if (len > 0) {
            lpOut.clear();
            return consumeWritten(lpOtel.writeTo(lpOut));
        }
        return 0;
    }

    @Benchmark
    public int o3a_otelRoute_lazyRawView() {
        rawOtel.wrap(otelBytes, 0, otelBytes.length);
        int len = rawOtel.repeatedSub(1, 0).sub(1).repeatedSub(1, 0).getString(2).length();
        return len > 0 ? consumeWritten(rawOtel.writeToBuffer(outBuf, 0)) : 0;
    }

    @Benchmark
    public int o3b_otelEnrichRes_officialPB() throws IOException {
        offOtel.clear().mergeFrom(otelBytes);
        offOtel.getResourceSpansBuilder(0).setSchemaUrl("https://opentelemetry.io/schemas/1.26.0")
                .getResourceBuilder().getAttributesBuilder(1).setValue("prod-us-east-1");
        return writeOfficial(offOtel.build());
    }

    @Benchmark
    public int o3b_otelEnrichRes_quickBuf() throws IOException {
        qbOtel.clearQuick();
        qbSrc.setInput(otelBytes);
        qbOtel.mergeFrom(qbSrc);
        var rs = qbOtel.getMutableResourceSpans().get(0);
        rs.setSchemaUrl("https://opentelemetry.io/schemas/1.26.0");
        rs.getMutableResource().getMutableAttributes().get(1).setValue("prod-us-east-1");
        return writeQuickBuf(qbOtel);
    }

    @Benchmark
    public int o3b_otelEnrichRes_lightProto() {
        lpOtelIn.setIndex(0, otelBytes.length);
        lpOtel.parseFrom(lpOtelIn, otelBytes.length);
        var rs = lpOtel.getResourceSpanAt(0);
        rs.setSchemaUrl("https://opentelemetry.io/schemas/1.26.0");
        rs.getResource().getAttributeAt(1).setValue("prod-us-east-1");
        lpOut.clear();
        return consumeWritten(lpOtel.writeTo(lpOut));
    }

    @Benchmark
    public int o3b_otelEnrichRes_lazyRawView() {
        rawOtel.wrap(otelBytes, 0, otelBytes.length);
        RawWireView rs = rawOtel.repeatedSub(1, 0);
        rs.setString(3, "https://opentelemetry.io/schemas/1.26.0");
        rs.sub(1).repeatedSub(1, 1).setString(2, "prod-us-east-1");
        return consumeWritten(rawOtel.writeToBuffer(outBuf, 0));
    }

    @Benchmark
    public int o3c_otelScrubPii_officialPB() throws IOException {
        offOtel.clear().mergeFrom(otelBytes);
        offOtel.getResourceSpansBuilder(0).getScopeSpansBuilder(1)
                .getSpansBuilder(12).getAttributesBuilder(3).setValue("[REDACTED]");
        return writeOfficial(offOtel.build());
    }

    @Benchmark
    public int o3c_otelScrubPii_quickBuf() throws IOException {
        qbOtel.clearQuick();
        qbSrc.setInput(otelBytes);
        qbOtel.mergeFrom(qbSrc);
        qbOtel.getMutableResourceSpans().get(0).getMutableScopeSpans().get(1)
                .getMutableSpans().get(12).getMutableAttributes().get(3).setValue("[REDACTED]");
        return writeQuickBuf(qbOtel);
    }

    @Benchmark
    public int o3c_otelScrubPii_lightProto() {
        lpOtelIn.setIndex(0, otelBytes.length);
        lpOtel.parseFrom(lpOtelIn, otelBytes.length);
        lpOtel.getResourceSpanAt(0).getScopeSpanAt(1).getSpanAt(12).getAttributeAt(3).setValue("[REDACTED]");
        lpOut.clear();
        return consumeWritten(lpOtel.writeTo(lpOut));
    }

    @Benchmark
    public int o3c_otelScrubPii_lazyRawView() {
        rawOtel.wrap(otelBytes, 0, otelBytes.length);
        rawOtel.repeatedSub(1, 0).repeatedSub(2, 1).repeatedSub(2, 12).repeatedSub(9, 3).setString(2, "[REDACTED]");
        return consumeWritten(rawOtel.writeToBuffer(outBuf, 0));
    }

    @Benchmark
    public long o3d_otelFullIngest_officialPB() throws IOException {
        offOtel.clear().mergeFrom(otelBytes);
        return readOfficialOtelFull(offOtel);
    }

    @Benchmark
    public long o3d_otelFullIngest_quickBuf() throws IOException {
        qbOtel.clearQuick();
        qbSrc.setInput(otelBytes);
        qbOtel.mergeFrom(qbSrc);
        return readQuickBufOtelFull(qbOtel);
    }

    @Benchmark
    public long o3d_otelFullIngest_lightProto() {
        lpOtelIn.setIndex(0, otelBytes.length);
        lpOtel.parseFrom(lpOtelIn, otelBytes.length);
        return readLightProtoOtelFull(lpOtel);
    }

    @Benchmark
    public long o3d_otelFullIngest_lazyRawView() {
        rawOtel.wrap(otelBytes, 0, otelBytes.length);
        return readRawOtelFull(rawOtel);
    }

    // =========================================================================
    // O4: Selectivity Crossover Sweep (mutate K of 100 items + serialize, 12.4 KB)
    // =========================================================================

    @State(Scope.Thread)
    public static class SweepParam {
        @Param({"1", "10", "25", "50", "100"})
        public int mutatedItems;
    }

    @Benchmark
    public int o4_sweepMutate_officialPB(SweepParam p) throws IOException {
        offOrder.clear().mergeFrom(orderBytesNoUnk);
        int k = p.mutatedItems;
        for (int i = 0; i < k; i++) {
            offOrder.getItemsBuilder(i).setQuantity(999).setDescription("UPDATED_ITEM_DESC");
        }
        return writeOfficial(offOrder.build());
    }

    @Benchmark
    public int o4_sweepMutate_quickBuf(SweepParam p) throws IOException {
        qbOrder.clearQuick();
        qbSrc.setInput(orderBytesNoUnk);
        qbOrder.mergeFrom(qbSrc);
        var items = qbOrder.getMutableItems();
        int k = p.mutatedItems;
        for (int i = 0; i < k; i++) {
            items.get(i).setQuantity(999).setDescription("UPDATED_ITEM_DESC");
        }
        return writeQuickBuf(qbOrder);
    }

    @Benchmark
    public int o4_sweepMutate_lightProto(SweepParam p) {
        lpOrderIn.setIndex(0, orderBytesNoUnk.length);
        lpOrder.parseFrom(lpOrderIn, orderBytesNoUnk.length);
        int k = p.mutatedItems;
        for (int i = 0; i < k; i++) {
            lpOrder.getItemAt(i).setQuantity(999).setDescription("UPDATED_ITEM_DESC");
        }
        lpOut.clear();
        return consumeWritten(lpOrder.writeTo(lpOut));
    }

    @Benchmark
    public int o4_sweepMutate_lazyRaw(SweepParam p) {
        rawOrder.wrap(orderBytesNoUnk, 0, orderBytesNoUnk.length);
        int k = p.mutatedItems;
        for (int i = 0; i < k; i++) {
            rawOrder.items(i).setQuantity(999).setDescription("UPDATED_ITEM_DESC");
        }
        return consumeWritten(rawOrder.writeToBuffer(outBuf, 0));
    }

    // =========================================================================
    // Full-read helpers (shared with OpenBenchmarksParityTest)
    // =========================================================================

    static long readOfficialMediaFull(MediaContent.Builder mc) {
        var m = mc.getMediaOrBuilder();
        long sum = m.getUri().length() + m.getTitle().length() + m.getWidth() + m.getHeight()
                + m.getFormat().length() + m.getDuration() + m.getSize() + m.getBitrate()
                + m.getPlayerValue() + m.getCopyright().length();
        for (int i = 0; i < m.getPersonCount(); i++) sum += m.getPerson(i).length();
        for (int i = 0; i < mc.getImageCount(); i++) {
            var img = mc.getImageOrBuilder(i);
            sum += img.getUri().length() + img.getTitle().length() + img.getWidth() + img.getHeight() + img.getSizeValue();
        }
        return sum;
    }

    static long readQuickBufMediaFull(io.lazyproto.bench.open.quickbuf.MediaContent mc) {
        var m = mc.getMedia();
        long sum = m.getUri().length() + m.getTitle().length() + m.getWidth() + m.getHeight()
                + m.getFormat().length() + m.getDuration() + m.getSize() + m.getBitrate()
                + m.getPlayer().getNumber() + m.getCopyright().length();
        var persons = m.getPerson();
        for (int i = 0; i < persons.length(); i++) sum += persons.get(i).length();
        var imgs = mc.getImage();
        for (int i = 0; i < imgs.length(); i++) {
            var img = imgs.get(i);
            sum += img.getUri().length() + img.getTitle().length() + img.getWidth() + img.getHeight() + img.getSize().getNumber();
        }
        return sum;
    }

    static long readLightProtoMediaFull(io.lazyproto.bench.open.lightproto.MediaContent mc) {
        var m = mc.getMedia();
        long sum = m.getUri().length() + m.getTitle().length() + m.getWidth() + m.getHeight()
                + m.getFormat().length() + m.getDuration() + m.getSize() + m.getBitrate()
                + m.getPlayer().getValue() + m.getCopyright().length();
        for (int i = 0; i < m.getPersonsCount(); i++) sum += m.getPersonAt(i).length();
        for (int i = 0; i < mc.getImagesCount(); i++) {
            var img = mc.getImageAt(i);
            sum += img.getUri().length() + img.getTitle().length() + img.getWidth() + img.getHeight() + img.getSize().getValue();
        }
        return sum;
    }

    static long readRawMediaFull(RawWireView mc) {
        RawWireView m = mc.sub(1);
        long sum = m.getString(1).length() + m.getString(2).length() + m.getInt32(3) + m.getInt32(4)
                + m.getString(5).length() + m.getInt64(6) + m.getInt64(7) + m.getInt32(8)
                + m.getInt32(10) + m.getString(11).length();
        int pn = m.repeatedCount(9);
        for (int i = 0; i < pn; i++) sum += m.getRepeatedString(9, i).length();
        int in = mc.repeatedCount(2);
        for (int i = 0; i < in; i++) {
            RawWireView img = mc.repeatedSub(2, i);
            sum += img.getString(1).length() + img.getString(2).length() + img.getInt32(3) + img.getInt32(4) + img.getInt32(5);
        }
        return sum;
    }

    static long readOfficialPulsarFull(PulsarMessageMetadata.Builder p) {
        long sum = p.getProducerName().length() + p.getSequenceId() + p.getPublishTime()
                + p.getReplicatedFrom().length() + p.getPartitionKey().length()
                + p.getCompression() + p.getUncompressedSize() + p.getNumMessagesInBatch()
                + p.getEventTime() + p.getSchemaVersion().size() + p.getDeliverAtTime();
        for (int i = 0; i < p.getPropertiesCount(); i++) {
            var kv = p.getPropertiesOrBuilder(i);
            sum += kv.getKey().length() + kv.getValue().length();
        }
        return sum;
    }

    static long readQuickBufPulsarFull(io.lazyproto.bench.open.quickbuf.PulsarMessageMetadata p) {
        long sum = p.getProducerName().length() + p.getSequenceId() + p.getPublishTime()
                + p.getReplicatedFrom().length() + p.getPartitionKey().length()
                + p.getCompression() + p.getUncompressedSize() + p.getNumMessagesInBatch()
                + p.getEventTime() + p.getSchemaVersion().length() + p.getDeliverAtTime();
        var props = p.getProperties();
        for (int i = 0; i < props.length(); i++) {
            var kv = props.get(i);
            sum += kv.getKey().length() + kv.getValue().length();
        }
        return sum;
    }

    static long readLightProtoPulsarFull(io.lazyproto.bench.open.lightproto.PulsarMessageMetadata p) {
        long sum = p.getProducerName().length() + p.getSequenceId() + p.getPublishTime()
                + p.getReplicatedFrom().length() + p.getPartitionKey().length()
                + p.getCompression() + p.getUncompressedSize() + p.getNumMessagesInBatch()
                + p.getEventTime() + p.getSchemaVersionSize() + p.getDeliverAtTime();
        for (int i = 0; i < p.getPropertiesCount(); i++) {
            var kv = p.getPropertyAt(i);
            sum += kv.getKey().length() + kv.getValue().length();
        }
        return sum;
    }

    static long readRawPulsarFull(RawWireView p) {
        long sum = p.getString(1).length() + p.getInt64(2) + p.getInt64(3)
                + p.getString(5).length() + p.getString(6).length()
                + p.getInt32(9) + p.getInt32(10) + p.getInt32(11)
                + p.getInt64(12) + p.getSlice(15).length() + p.getInt64(19);
        int n = p.repeatedCount(4);
        for (int i = 0; i < n; i++) {
            RawWireView kv = p.repeatedSub(4, i);
            sum += kv.getString(1).length() + kv.getString(2).length();
        }
        return sum;
    }

    static long readOfficialOtelFull(OtelExportTraceRequest.Builder req) {
        long sum = 0;
        for (int r = 0; r < req.getResourceSpansCount(); r++) {
            var rs = req.getResourceSpansOrBuilder(r);
            sum += rs.getSchemaUrl().length();
            var res = rs.getResourceOrBuilder();
            for (int a = 0; a < res.getAttributesCount(); a++) {
                var kv = res.getAttributesOrBuilder(a);
                sum += kv.getKey().length() + kv.getValue().length();
            }
            for (int s = 0; s < rs.getScopeSpansCount(); s++) {
                var ss = rs.getScopeSpansOrBuilder(s);
                sum += ss.getScopeOrBuilder().getName().length() + ss.getScopeOrBuilder().getVersion().length();
                for (int i = 0; i < ss.getSpansCount(); i++) {
                    var sp = ss.getSpansOrBuilder(i);
                    sum += sp.getTraceId().size() + sp.getSpanId().size() + sp.getParentSpanId().size()
                            + sp.getName().length() + sp.getKind()
                            + sp.getStartTimeUnixNano() + sp.getEndTimeUnixNano() + sp.getStatusCode();
                    for (int a = 0; a < sp.getAttributesCount(); a++) {
                        var kv = sp.getAttributesOrBuilder(a);
                        sum += kv.getKey().length() + kv.getValue().length();
                    }
                }
            }
        }
        return sum;
    }

    static long readQuickBufOtelFull(io.lazyproto.bench.open.quickbuf.OtelExportTraceRequest req) {
        long sum = 0;
        var rsList = req.getResourceSpans();
        for (int r = 0; r < rsList.length(); r++) {
            var rs = rsList.get(r);
            sum += rs.getSchemaUrl().length();
            var resAttrs = rs.getResource().getAttributes();
            for (int a = 0; a < resAttrs.length(); a++) {
                var kv = resAttrs.get(a);
                sum += kv.getKey().length() + kv.getValue().length();
            }
            var ssList = rs.getScopeSpans();
            for (int s = 0; s < ssList.length(); s++) {
                var ss = ssList.get(s);
                sum += ss.getScope().getName().length() + ss.getScope().getVersion().length();
                var spans = ss.getSpans();
                for (int i = 0; i < spans.length(); i++) {
                    var sp = spans.get(i);
                    sum += sp.getTraceId().length() + sp.getSpanId().length() + sp.getParentSpanId().length()
                            + sp.getName().length() + sp.getKind()
                            + sp.getStartTimeUnixNano() + sp.getEndTimeUnixNano() + sp.getStatusCode();
                    var attrs = sp.getAttributes();
                    for (int a = 0; a < attrs.length(); a++) {
                        var kv = attrs.get(a);
                        sum += kv.getKey().length() + kv.getValue().length();
                    }
                }
            }
        }
        return sum;
    }

    static long readLightProtoOtelFull(io.lazyproto.bench.open.lightproto.OtelExportTraceRequest req) {
        long sum = 0;
        for (int r = 0; r < req.getResourceSpansCount(); r++) {
            var rs = req.getResourceSpanAt(r);
            sum += rs.getSchemaUrl().length();
            var res = rs.getResource();
            for (int a = 0; a < res.getAttributesCount(); a++) {
                var kv = res.getAttributeAt(a);
                sum += kv.getKey().length() + kv.getValue().length();
            }
            for (int s = 0; s < rs.getScopeSpansCount(); s++) {
                var ss = rs.getScopeSpanAt(s);
                sum += ss.getScope().getName().length() + ss.getScope().getVersion().length();
                for (int i = 0; i < ss.getSpansCount(); i++) {
                    var sp = ss.getSpanAt(i);
                    sum += sp.getTraceIdSize() + sp.getSpanIdSize() + sp.getParentSpanIdSize()
                            + sp.getName().length() + sp.getKind()
                            + sp.getStartTimeUnixNano() + sp.getEndTimeUnixNano() + sp.getStatusCode();
                    for (int a = 0; a < sp.getAttributesCount(); a++) {
                        var kv = sp.getAttributeAt(a);
                        sum += kv.getKey().length() + kv.getValue().length();
                    }
                }
            }
        }
        return sum;
    }

    static long readRawOtelFull(RawWireView req) {
        long sum = 0;
        int rn = req.repeatedCount(1);
        for (int r = 0; r < rn; r++) {
            RawWireView rs = req.repeatedSub(1, r);
            sum += rs.getString(3).length();
            RawWireView res = rs.sub(1);
            int ran = res.repeatedCount(1);
            for (int a = 0; a < ran; a++) {
                RawWireView kv = res.repeatedSub(1, a);
                sum += kv.getString(1).length() + kv.getString(2).length();
            }
            int ssn = rs.repeatedCount(2);
            for (int s = 0; s < ssn; s++) {
                RawWireView ss = rs.repeatedSub(2, s);
                RawWireView sc = ss.sub(1);
                sum += sc.getString(1).length() + sc.getString(2).length();
                int spn = ss.repeatedCount(2);
                for (int i = 0; i < spn; i++) {
                    RawWireView sp = ss.repeatedSub(2, i);
                    sum += sp.getSlice(1).length() + sp.getSlice(2).length() + sp.getSlice(4).length()
                            + sp.getString(5).length() + sp.getInt32(6)
                            + sp.getFixed64(7) + sp.getFixed64(8) + sp.getInt32(15);
                    int an = sp.repeatedCount(9);
                    for (int a = 0; a < an; a++) {
                        RawWireView kv = sp.repeatedSub(9, a);
                        sum += kv.getString(1).length() + kv.getString(2).length();
                    }
                }
            }
        }
        return sum;
    }

    public static void main(String[] args) throws Exception {
        String include = args.length > 0 ? args[0] : OpenBenchmarksJmh.class.getSimpleName();
        Options opt = new OptionsBuilder()
                .include(include)
                .addProfiler(GCProfiler.class)
                .forks(1)
                .warmupIterations(2)
                .warmupTime(TimeValue.milliseconds(350))
                .measurementIterations(3)
                .measurementTime(TimeValue.milliseconds(350))
                .build();
        new Runner(opt).run();
    }
}
