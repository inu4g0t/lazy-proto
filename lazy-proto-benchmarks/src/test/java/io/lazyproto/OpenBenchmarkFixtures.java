package io.lazyproto;

import com.google.protobuf.ByteString;
import io.lazyproto.bench.open.proto.*;

/**
 * Canonical fixture builders for open-ecosystem Protobuf benchmark suites:
 * <ol>
 *   <li><b>O1 — {@code jvm-serializers} {@code MediaContent}</b> (~250 B)</li>
 *   <li><b>O2 — Apache Pulsar {@code MessageMetadata}</b> (~550 B, 10 KeyValue properties)</li>
 *   <li><b>O3 — OpenTelemetry {@code ExportTraceServiceRequest}</b> (~17 KB, 4-level nesting, 50 spans × 6 attributes)</li>
 * </ol>
 */
public final class OpenBenchmarkFixtures {

    private OpenBenchmarkFixtures() {}

    /** Classic jvm-serializers MediaContent fixture (~250 bytes). */
    public static byte[] createMediaContentBytes() {
        return MediaContent.newBuilder()
                .setMedia(Media.newBuilder()
                        .setUri("http://javaone.com/keynote.mpg")
                        .setTitle("Javaone Keynote")
                        .setWidth(640)
                        .setHeight(480)
                        .setFormat("video/mpg4")
                        .setDuration(18000000L)
                        .setSize(58982400L)
                        .setBitrate(262144)
                        .addPerson("Bill Gates")
                        .addPerson("Steve Jobs")
                        .setPlayer(Media.Player.JAVA)
                        .setCopyright("None")
                        .build())
                .addImage(Image.newBuilder()
                        .setUri("http://javaone.com/keynote_large.jpg")
                        .setTitle("Javaone Keynote")
                        .setWidth(1024)
                        .setHeight(768)
                        .setSize(Image.Size.LARGE)
                        .build())
                .addImage(Image.newBuilder()
                        .setUri("http://javaone.com/keynote_small.jpg")
                        .setTitle("Javaone Keynote")
                        .setWidth(320)
                        .setHeight(240)
                        .setSize(Image.Size.SMALL)
                        .build())
                .build()
                .toByteArray();
    }

    /** Apache Pulsar MessageMetadata fixture (~550 bytes, 10 KeyValue properties). */
    public static byte[] createPulsarMetadataBytes() {
        PulsarMessageMetadata.Builder b = PulsarMessageMetadata.newBuilder()
                .setProducerName("persistent://tenant-a/ns-orders/orders-topic-partition-3-producer-07")
                .setSequenceId(98765432101L)
                .setPublishTime(1795497000123L)
                .setPartitionKey("order-shard-key-88421")
                .setCompression(1)
                .setUncompressedSize(4096)
                .setNumMessagesInBatch(16)
                .setEventTime(1795497000100L)
                .setSchemaVersion(ByteString.copyFrom(new byte[]{0, 0, 0, 0, 0, 0, 0, 7}))
                .setDeliverAtTime(1795497005000L);
        String[][] props = {
                {"traceparent", "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01"},
                {"content-type", "application/x-protobuf"},
                {"source-region", "ap-east-1"},
                {"tenant-id", "acme-corp-prod"},
                {"schema-name", "OrderEnvelopeV3"},
                {"priority", "high"},
                {"retry-count", "0"},
                {"client-sdk", "pulsar-java-3.3.0"},
                {"auth-subject", "svc-checkout-gateway"},
                {"idempotency-key", "idem-9f8e7d6c5b4a3210"}
        };
        for (String[] kv : props) {
            b.addProperties(KeyValue.newBuilder().setKey(kv[0]).setValue(kv[1]).build());
        }
        return b.build().toByteArray();
    }

    /** OpenTelemetry ExportTraceServiceRequest (1 ResourceSpans -> 2 ScopeSpans -> 50 Spans x 6 attrs, ~17 KB). */
    public static byte[] createOtelTraceRequestBytes() {
        OtelResource.Builder res = OtelResource.newBuilder()
                .setDroppedAttributesCount(0)
                .addAttributes(KeyValue.newBuilder().setKey("service.name").setValue("checkout-api-gateway"))
                .addAttributes(KeyValue.newBuilder().setKey("deployment.environment").setValue("staging-us-west"))
                .addAttributes(KeyValue.newBuilder().setKey("service.version").setValue("2.14.3"))
                .addAttributes(KeyValue.newBuilder().setKey("host.name").setValue("k8s-node-17.internal"))
                .addAttributes(KeyValue.newBuilder().setKey("telemetry.sdk.language").setValue("java"));

        OtelResourceSpans.Builder rs = OtelResourceSpans.newBuilder()
                .setResource(res)
                .setSchemaUrl("https://opentelemetry.io/schemas/1.24.0");

        byte[] traceId = new byte[16];
        byte[] spanId = new byte[8];
        byte[] parentId = new byte[8];
        for (int i = 0; i < 16; i++) traceId[i] = (byte) (0xA0 + i);
        for (int i = 0; i < 8; i++) parentId[i] = (byte) (0x10 + i);

        for (int s = 0; s < 2; s++) {
            OtelScopeSpans.Builder ss = OtelScopeSpans.newBuilder()
                    .setScope(OtelScope.newBuilder()
                            .setName(s == 0 ? "io.opentelemetry.http.server" : "io.opentelemetry.jdbc")
                            .setVersion("1.38.0"))
                    .setSchemaUrl("https://opentelemetry.io/schemas/1.24.0");

            for (int i = 0; i < 25; i++) {
                int idx = s * 25 + i;
                for (int b = 0; b < 8; b++) spanId[b] = (byte) (idx + b * 17);
                OtelSpan.Builder sp = OtelSpan.newBuilder()
                        .setTraceId(ByteString.copyFrom(traceId))
                        .setSpanId(ByteString.copyFrom(spanId))
                        .setParentSpanId(ByteString.copyFrom(parentId))
                        .setName(s == 0 ? "POST /api/v2/orders/checkout" : "SELECT * FROM orders WHERE id = ?")
                        .setKind(s == 0 ? 2 : 3)
                        .setStartTimeUnixNano(1795497000000000000L + idx * 1_000_000L)
                        .setEndTimeUnixNano(1795497000000500000L + idx * 1_000_000L)
                        .setStatusCode(1)
                        .addAttributes(KeyValue.newBuilder().setKey("http.method").setValue("POST"))
                        .addAttributes(KeyValue.newBuilder().setKey("http.status_code").setValue("200"))
                        .addAttributes(KeyValue.newBuilder().setKey("net.peer.ip").setValue("10.24.88." + (10 + idx)))
                        .addAttributes(KeyValue.newBuilder().setKey("user.email").setValue("customer" + idx + "@example.com"))
                        .addAttributes(KeyValue.newBuilder().setKey("db.system").setValue("postgresql"))
                        .addAttributes(KeyValue.newBuilder().setKey("rpc.service").setValue("OrderService"));
                ss.addSpans(sp);
            }
            rs.addScopeSpans(ss);
        }

        return OtelExportTraceRequest.newBuilder().addResourceSpans(rs).build().toByteArray();
    }
}
