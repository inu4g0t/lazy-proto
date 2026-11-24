package io.lazyproto;

import com.google.protobuf.ByteString;
import com.google.protobuf.CodedOutputStream;
import com.google.protobuf.Message;
import com.google.protobuf.UnknownFieldSet;
import io.lazyproto.dynamic.ProtoDocument;
import io.lazyproto.model.LazyOrderProto.LazyLineItem;
import io.lazyproto.model.LazyOrderProto.LazyOrderEnvelope;
import io.lazyproto.model.LazyOrderProto.LazyUserProfile;
import io.lazyproto.model.OrderInterfaces.OrderEnvelopeOrBuilder;
import io.lazyproto.model.StandardOrderProto.LineItem;
import io.lazyproto.model.StandardOrderProto.OrderEnvelope;
import io.lazyproto.model.StandardOrderProto.UserProfile;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive correctness test suite covering:
 * 1. Advantageous workloads (Partial read, Fast-Path 0 Passthrough, Fast-Path 1 In-Place Patching,
 *    Fast-Path 2/3 Contiguous Span Splicing, Single element mutation in 100-item repeated list,
 *    Field redaction, Unknown field preservation, Duplicate tag shadowing, Dynamic ProtoDocument).
 * 2. Non-advantageous / Adverse workloads (From-scratch construction with 0 input reuse,
 *    100% full-tree read, 100% full-tree variable-length mutation, Tiny 3-field message full mutation).
 * 3. Java Protobuf interface compatibility & UnsupportedOperationException contract.
 */
public class LazyProtoCorrectnessTest {

    /**
     * Builds a realistic complex OrderEnvelope (~12 KB) with 100 LineItems, 4 KB binary payload,
     * nested UserProfile, and injected unknown fields (#50 varint, #51 string).
     */
    public static byte[] createComplexOrderBytes(int itemCount, int payloadSize) {
        byte[] payloadBytes = new byte[payloadSize];
        for (int i = 0; i < payloadSize; i++) {
            payloadBytes[i] = (byte) ((i * 31 + 7) & 0xFF);
        }

        UnknownFieldSet unknowns = UnknownFieldSet.newBuilder()
                .addField(50, UnknownFieldSet.Field.newBuilder().addVarint(9876543210L).build())
                .addField(51, UnknownFieldSet.Field.newBuilder()
                        .addLengthDelimited(ByteString.copyFromUtf8("unknown-extension-metadata-v2"))
                        .build())
                .build();

        OrderEnvelope.Builder b = OrderEnvelope.newBuilder()
                .setOrderId(202611240001L)
                .setBuyerName("alice_wonderland")
                .setStatus(1)
                .setUpdatedAtFixed64(1795497000123L)
                .setUser(UserProfile.newBuilder()
                        .setUserId(88001122L)
                        .setCity("SH")
                        .setVipLevel(3)
                        .setScore(95.5)
                        .setEmail("alice@example.com")
                        .build())
                .setPayload(ByteString.copyFrom(payloadBytes))
                .setAuthToken("secret-jwt-token-xyz-987654321")
                .setTraceId("trace-00112233445566778899aabbccddeeff")
                .setTotalCents(1250000L);

        b.getUnknownFieldsBuilder().mergeFrom(unknowns);

        for (int i = 0; i < itemCount; i++) {
            b.addItems(LineItem.newBuilder()
                    .setItemId(10000L + i)
                    .setSku("SKU-PRO-" + String.format("%04d", i))
                    .setQuantity((i % 5) + 1)
                    .setPriceCents(19900L + i * 100L)
                    .setDescription("High performance component item #" + i + " with extra details")
                    .build());
        }
        return b.build().toByteArray();
    }

    // =========================================================================
    // Category A: Advantageous Workloads Correctness Verification
    // =========================================================================

    @Test
    @DisplayName("A1: Tier-0 O(1) Wrap & Tier-1 Lazy Partial Read (unvisited sub-messages & list items stay unparsed)")
    void testA1_LazyPartialRead() throws IOException {
        byte[] raw = createComplexOrderBytes(100, 4096);
        OrderEnvelope expected = OrderEnvelope.parseFrom(raw);

        LazyOrderEnvelope lazy = LazyOrderEnvelope.parseFrom(raw);
        // Tier 0: Immediately after parseFrom(), zero scanning has occurred!
        assertFalse(lazy.isScanned(), "Tier-0 wrap must not scan bytes before first getter call");
        assertTrue(lazy.isClean());

        // Tier 1: Access 2 top-level scalar fields
        assertEquals(expected.getOrderId(), lazy.getOrderId());
        assertEquals(expected.getStatus(), lazy.getStatus());
        assertTrue(lazy.isScanned(), "Top-level must be scanned after first getter");

        // Zero-copy slice comparison without creating java.lang.String
        assertTrue(lazy.getBuyerNameSlice().contentEqualsAscii("alice_wonderland"));

        // Access nested UserProfile: wrapping UserProfile must NOT scan UserProfile until a UserProfile getter is called
        LazyUserProfile lazyUser = lazy.getUser();
        assertFalse(lazyUser.isScanned(), "Nested sub-message must remain unscanned until its own field is accessed");
        assertEquals(expected.getUser().getVipLevel(), lazyUser.getVipLevel());
        assertTrue(lazyUser.isScanned(), "Nested sub-message is scanned on first field access");

        // Access only item #3 out of 100 items
        assertEquals(100, lazy.getItemsCount());
        LazyLineItem item3 = lazy.getItems(3);
        assertEquals(expected.getItems(3).getItemId(), item3.getItemId());
        assertEquals(expected.getItems(3).getSku(), item3.getSku());
    }

    @Test
    @DisplayName("A2: Fast-Path 0 Passthrough Serialization (read fields + unknown fields preserved verbatim)")
    void testA2_FastPath0_CleanPassthrough() throws IOException {
        byte[] raw = createComplexOrderBytes(50, 2048);

        LazyOrderEnvelope lazy = LazyOrderEnvelope.parseFrom(raw);
        // Read several fields including String and nested sub-message
        assertEquals(202611240001L, lazy.getOrderId());
        assertEquals("alice_wonderland", lazy.getBuyerName());
        assertEquals("SH", lazy.getUser().getCity());
        assertEquals("SKU-PRO-0010", lazy.getItems(10).getSku());

        // Even though fields were read and Strings were materialized, nothing was modified!
        assertTrue(lazy.isClean(), "Reading fields must not mark message dirty");
        assertEquals(raw.length, lazy.getSerializedSize());

        byte[] out = lazy.toByteArray();
        assertArrayEquals(raw, out, "Fast-Path 0 must produce 100% byte-identical output including unknown fields");

        // Verify zero-copy ByteString output
        ByteString bs = lazy.toByteString();
        assertEquals(raw.length, bs.size());
    }

    @Test
    @DisplayName("A3: Fast-Path 1 Deep In-Place Binary Patching (same-size varint, fixed64, double, same-length string & list item)")
    void testA3_FastPath1_InPlaceSameSizePatching() throws IOException {
        byte[] raw = createComplexOrderBytes(50, 2048);

        // Use standard Java Protobuf idiom: order.toBuilder().setXxx().build()
        LazyOrderEnvelope lazy = LazyOrderEnvelope.parseFrom(raw);
        LazyOrderEnvelope updated = lazy.toBuilder()
                .setStatus(2)                                // 1 -> 2 (both 1-byte varint!)
                .setUpdatedAtFixed64(1800000000999L)         // fixed64 (always 8 bytes!)
                .build();

        // Also mutate nested sub-message fields with same byte size
        updated.getUser()
                .setCity("BJ")                               // "SH" -> "BJ" (both 2 UTF-8 bytes!)
                .setScore(99.875)                            // double (always 8 bytes!)
                .setVipLevel(5);                             // 3 -> 5 (both 1-byte varint!)

        // Also mutate 1 element in repeated list with same varint byte size
        updated.getItems(7).setQuantity(9);                  // 3 -> 9 (both 1-byte varint!)

        assertTrue(updated.isSameSizeDirtyOnly(), "All mutations have identical wire byte length -> must hit Fast-Path 1");
        assertEquals(raw.length, updated.getSerializedSize(), "Fast-Path 1 serialized size must equal original rawLength in O(1)");

        byte[] patchedBytes = updated.toByteArray();
        assertEquals(raw.length, patchedBytes.length);

        // Parse with standard official Google Protobuf parser and verify every mutated & untouched field + unknown fields!
        OrderEnvelope decoded = OrderEnvelope.parseFrom(patchedBytes);
        assertEquals(202611240001L, decoded.getOrderId());
        assertEquals("alice_wonderland", decoded.getBuyerName());
        assertEquals(2, decoded.getStatus());
        assertEquals(1800000000999L, decoded.getUpdatedAtFixed64());
        assertEquals("BJ", decoded.getUser().getCity());
        assertEquals(99.875, decoded.getUser().getScore(), 1e-9);
        assertEquals(5, decoded.getUser().getVipLevel());
        assertEquals("alice@example.com", decoded.getUser().getEmail());
        assertEquals(50, decoded.getItemsCount());
        assertEquals(9, decoded.getItems(7).getQuantity());
        assertEquals("SKU-PRO-0007", decoded.getItems(7).getSku());
        // Unknown fields #50 and #51 must still be intact!
        assertTrue(decoded.getUnknownFields().hasField(50));
        assertTrue(decoded.getUnknownFields().hasField(51));
    }

    @Test
    @DisplayName("A4: Fast-Path 2 & 3 Contiguous Span Splicing (variable-size string, nested sub-message & single list element mutation)")
    void testA4_FastPath2And3_VariableSizeSpanSplicing() throws IOException {
        byte[] raw = createComplexOrderBytes(100, 4096);
        OrderEnvelope original = OrderEnvelope.parseFrom(raw);

        LazyOrderEnvelope lazy = LazyOrderEnvelope.parseFrom(raw);
        // 1. Variable-size top-level string change (16 bytes -> 35 bytes)
        lazy.setBuyerName("alexander_the_great_vip_customer_01");
        // 2. Variable-size top-level varint change (1 byte -> 3 bytes)
        lazy.setStatus(50000);
        // 3. Variable-size nested UserProfile string change (2 bytes -> 24 bytes)
        lazy.getUser().setCity("SHANGHAI_PUDONG_NEW_AREA");
        // 4. Variable-size mutation on item #42 out of 100 items
        lazy.getItems(42).setDescription("UPDATED_SHORT_DESC");
        lazy.getItems(42).setQuantity(9999);

        assertFalse(lazy.isClean());
        assertFalse(lazy.isSameSizeDirtyOnly());

        int expectedSize = lazy.getSerializedSize();
        byte[] splicedBytes = lazy.toByteArray();
        assertEquals(expectedSize, splicedBytes.length);

        // Verify with StandardOrderProto parser
        OrderEnvelope decoded = OrderEnvelope.parseFrom(splicedBytes);
        assertEquals("alexander_the_great_vip_customer_01", decoded.getBuyerName());
        assertEquals(50000, decoded.getStatus());
        assertEquals("SHANGHAI_PUDONG_NEW_AREA", decoded.getUser().getCity());
        assertEquals(original.getUser().getEmail(), decoded.getUser().getEmail());
        assertEquals(original.getUser().getUserId(), decoded.getUser().getUserId());

        // Check all 100 items
        assertEquals(100, decoded.getItemsCount());
        for (int i = 0; i < 100; i++) {
            if (i == 42) {
                assertEquals("UPDATED_SHORT_DESC", decoded.getItems(i).getDescription());
                assertEquals(9999, decoded.getItems(i).getQuantity());
                assertEquals(original.getItems(i).getSku(), decoded.getItems(i).getSku());
            } else {
                assertEquals(original.getItems(i), decoded.getItems(i), "Untouched item #" + i + " must be preserved verbatim");
            }
        }
        // Payload and unknown fields must be preserved verbatim
        assertEquals(original.getPayload(), decoded.getPayload());
        assertEquals(original.getUnknownFields(), decoded.getUnknownFields());
    }

    @Test
    @DisplayName("A5: Gateway Field Redaction (clearAuthToken excises sensitive field while splicing remaining spans)")
    void testA5_FieldRedactionAndUnknownPassthrough() throws IOException {
        byte[] raw = createComplexOrderBytes(20, 1024);

        LazyOrderEnvelope lazy = LazyOrderEnvelope.parseFrom(raw);
        assertEquals("secret-jwt-token-xyz-987654321", lazy.getAuthToken());

        // Redact auth_token
        lazy.clearAuthToken();
        assertEquals("", lazy.getAuthToken());

        byte[] redactedBytes = lazy.toByteArray();
        assertTrue(redactedBytes.length < raw.length);

        OrderEnvelope decoded = OrderEnvelope.parseFrom(redactedBytes);
        assertEquals("", decoded.getAuthToken(), "Redacted auth_token must be absent in output");
        assertEquals(202611240001L, decoded.getOrderId());
        assertEquals("trace-00112233445566778899aabbccddeeff", decoded.getTraceId());
        assertEquals(20, decoded.getItemsCount());
        assertTrue(decoded.getUnknownFields().hasField(50));
    }

    @Test
    @DisplayName("A6: Duplicate Non-Repeated Scalar Tag Shadowing (Last-Write-Wins & Clean Excision on Mutation)")
    void testA6_DuplicateTagShadowing() throws IOException {
        // Construct a non-canonical protobuf stream where field #3 (status) appears twice: first status=10, later status=200
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        CodedOutputStream cos = CodedOutputStream.newInstance(baos);
        cos.writeInt64(1, 777L);
        cos.writeInt32(3, 10);       // First occurrence of field #3 (should be shadowed!)
        cos.writeString(2, "dup_test");
        cos.writeInt32(3, 200);      // Second occurrence of field #3 (Last-Write-Wins!)
        cos.flush();
        byte[] nonCanonicalBytes = baos.toByteArray();

        LazyOrderEnvelope lazy = LazyOrderEnvelope.parseFrom(nonCanonicalBytes);
        // 1. Reading must return the last value (200)
        assertEquals(200, lazy.getStatus());

        // 2. Modifying status to 300 must replace the active occurrence AND drop the shadowed occurrence!
        lazy.setStatus(300);
        byte[] out = lazy.toByteArray();

        OrderEnvelope std = OrderEnvelope.parseFrom(out);
        assertEquals(777L, std.getOrderId());
        assertEquals("dup_test", std.getBuyerName());
        assertEquals(300, std.getStatus());
    }

    @Test
    @DisplayName("A7: Dynamic Schema-less ProtoDocument API (jsoniter-style path navigation, in-place patch & span splicing)")
    void testA7_DynamicProtoDocument() throws IOException {
        byte[] raw = createComplexOrderBytes(10, 512);

        ProtoDocument doc = ProtoDocument.wrapBytes(raw);
        assertEquals(202611240001L, doc.getInt64(1));
        assertTrue(doc.getSlice(2).contentEqualsAscii("alice_wonderland"));
        assertEquals("SH", doc.at(5).getString(2));

        // Mutate top-level status (field #3) and nested user city (field #5 -> #2) and remove auth_token (field #8)
        doc.setInt32(3, 99);
        doc.at(5).setString(2, "SHENZHEN");
        doc.remove(8);

        byte[] out = doc.toByteArray();
        OrderEnvelope decoded = OrderEnvelope.parseFrom(out);
        assertEquals(99, decoded.getStatus());
        assertEquals("SHENZHEN", decoded.getUser().getCity());
        assertEquals("", decoded.getAuthToken());
        assertEquals(10, decoded.getItemsCount());
    }

    // =========================================================================
    // Category B: Non-Advantageous / Adverse Workloads Correctness Verification
    // =========================================================================

    @Test
    @DisplayName("B1 (Adverse): From-Scratch Construction with Zero Input Buffer to Reuse")
    void testB1_FromScratchConstruction() throws IOException {
        LazyOrderEnvelope scratch = LazyOrderEnvelope.newBuilder()
                .setOrderId(999888777L)
                .setBuyerName("scratch_builder_user")
                .setStatus(4)
                .setUpdatedAtFixed64(1234567890123L)
                .setUser(LazyUserProfile.newBuilder()
                        .setUserId(555L)
                        .setCity("HANGZHOU")
                        .setVipLevel(2)
                        .setScore(88.25)
                        .setEmail("scratch@example.com"))
                .setPayloadBytes(new byte[] { 1, 2, 3, 4, 5, 6, 7, 8 })
                .setAuthToken("token-from-scratch")
                .setTraceId("trace-scratch-01")
                .setTotalCents(88800L);

        for (int i = 0; i < 25; i++) {
            scratch.addItems(LazyLineItem.newBuilder()
                    .setItemId(i + 1L)
                    .setSku("SCRATCH-SKU-" + i)
                    .setQuantity(i + 2)
                    .setPriceCents(500L * (i + 1))
                    .setDescription("Built from scratch item " + i));
        }

        byte[] out = scratch.toByteArray();
        OrderEnvelope decoded = OrderEnvelope.parseFrom(out);
        assertEquals(999888777L, decoded.getOrderId());
        assertEquals("scratch_builder_user", decoded.getBuyerName());
        assertEquals(4, decoded.getStatus());
        assertEquals(1234567890123L, decoded.getUpdatedAtFixed64());
        assertEquals("HANGZHOU", decoded.getUser().getCity());
        assertEquals(88.25, decoded.getUser().getScore(), 1e-9);
        assertEquals(25, decoded.getItemsCount());
        assertEquals("SCRATCH-SKU-24", decoded.getItems(24).getSku());
        assertEquals(88800L, decoded.getTotalCents());
    }

    @Test
    @DisplayName("B2 (Adverse): 100% Full-Tree Deserialization (Reading Every Field & All 100 List Elements)")
    void testB2_FullTreeReadAllFields() throws IOException {
        byte[] raw = createComplexOrderBytes(100, 4096);
        OrderEnvelope std = OrderEnvelope.parseFrom(raw);
        LazyOrderEnvelope lazy = LazyOrderEnvelope.parseFrom(raw);

        assertEquals(std.getOrderId(), lazy.getOrderId());
        assertEquals(std.getBuyerName(), lazy.getBuyerName());
        assertEquals(std.getStatus(), lazy.getStatus());
        assertEquals(std.getUpdatedAtFixed64(), lazy.getUpdatedAtFixed64());
        assertEquals(std.getUser().getUserId(), lazy.getUser().getUserId());
        assertEquals(std.getUser().getCity(), lazy.getUser().getCity());
        assertEquals(std.getUser().getVipLevel(), lazy.getUser().getVipLevel());
        assertEquals(std.getUser().getScore(), lazy.getUser().getScore(), 1e-9);
        assertEquals(std.getUser().getEmail(), lazy.getUser().getEmail());
        assertEquals(std.getPayload(), lazy.getPayload());
        assertEquals(std.getAuthToken(), lazy.getAuthToken());
        assertEquals(std.getTraceId(), lazy.getTraceId());
        assertEquals(std.getTotalCents(), lazy.getTotalCents());

        assertEquals(std.getItemsCount(), lazy.getItemsCount());
        for (int i = 0; i < std.getItemsCount(); i++) {
            assertEquals(std.getItems(i).getItemId(), lazy.getItems(i).getItemId());
            assertEquals(std.getItems(i).getSku(), lazy.getItems(i).getSku());
            assertEquals(std.getItems(i).getQuantity(), lazy.getItems(i).getQuantity());
            assertEquals(std.getItems(i).getPriceCents(), lazy.getItems(i).getPriceCents());
            assertEquals(std.getItems(i).getDescription(), lazy.getItems(i).getDescription());
        }
    }

    @Test
    @DisplayName("B3 (Adverse): 100% Full-Tree Variable-Size Mutation (Every Single Field & List Element Modified)")
    void testB3_FullTreeVariableSizeMutation() throws IOException {
        byte[] raw = createComplexOrderBytes(30, 1024);
        LazyOrderEnvelope lazy = LazyOrderEnvelope.parseFrom(raw);

        lazy.setOrderId(1L)
            .setBuyerName("completely_new_buyer_name_with_different_length")
            .setStatus(999999)
            .setUpdatedAtFixed64(42L)
            .setPayloadBytes(new byte[] { 9, 8, 7 })
            .setAuthToken("short_tok")
            .setTraceId("new_trace_id_with_extra_padding_0000000000")
            .setTotalCents(10L);

        lazy.getUser()
            .setUserId(2L)
            .setCity("GUANGZHOU_TIANHE")
            .setVipLevel(10000)
            .setScore(12.34)
            .setEmail("new_long_email_address_for_full_mutation@domain.org");

        for (int i = 0; i < lazy.getItemsCount(); i++) {
            LazyLineItem item = lazy.getItems(i);
            item.setItemId(900000000L + i)
                .setSku("MUTATED-LONG-SKU-IDENTIFIER-" + i)
                .setQuantity(100000 + i)
                .setPriceCents(i + 1L)
                .setDescription("Short-" + i);
        }

        byte[] out = lazy.toByteArray();
        OrderEnvelope decoded = OrderEnvelope.parseFrom(out);
        assertEquals(1L, decoded.getOrderId());
        assertEquals("completely_new_buyer_name_with_different_length", decoded.getBuyerName());
        assertEquals(999999, decoded.getStatus());
        assertEquals("GUANGZHOU_TIANHE", decoded.getUser().getCity());
        assertEquals(30, decoded.getItemsCount());
        for (int i = 0; i < 30; i++) {
            assertEquals(900000000L + i, decoded.getItems(i).getItemId());
            assertEquals("MUTATED-LONG-SKU-IDENTIFIER-" + i, decoded.getItems(i).getSku());
            assertEquals(100000 + i, decoded.getItems(i).getQuantity());
            assertEquals(i + 1L, decoded.getItems(i).getPriceCents());
            assertEquals("Short-" + i, decoded.getItems(i).getDescription());
        }
    }

    // =========================================================================
    // Category C: Java Protobuf Interface Compatibility & Exception Contract
    // =========================================================================

    @Test
    @DisplayName("C1: Polymorphic Interface Compatibility & UnsupportedOperationException Enforcement")
    void testC1_JavaInterfaceCompatibilityAndUnsupportedExceptions() {
        byte[] raw = createComplexOrderBytes(5, 64);
        LazyOrderEnvelope lazy = LazyOrderEnvelope.parseFrom(raw);

        // 1. Polymorphic assignment to standard Google Protobuf interfaces
        OrderEnvelopeOrBuilder orBuilder = lazy;
        Message msg = lazy;
        Message.Builder builder = lazy.toBuilder();

        assertSame(lazy, builder, "Unified self-builder must return 'this' with 0 allocation");
        assertSame(lazy, builder.build(), "build() must return 'this' with 0 allocation");
        assertEquals(202611240001L, orBuilder.getOrderId());
        assertTrue(msg.isInitialized());

        // 2. Safeguarded toString() must NOT trigger scanning or deserialization
        LazyOrderEnvelope unscanned = LazyOrderEnvelope.parseFrom(raw);
        String summary = unscanned.toString();
        assertFalse(unscanned.isScanned(), "toString() must never trigger lazy deserialization");
        assertTrue(summary.contains("LazyOrderEnvelope[rawLen="));

        // 3. Intentionally unsupported methods must throw UnsupportedOperationException
        assertThrows(UnsupportedOperationException.class, () -> lazy.equals(unscanned));
        assertThrows(UnsupportedOperationException.class, lazy::hashCode);
        assertThrows(UnsupportedOperationException.class, lazy::getDescriptorForType);
        assertThrows(UnsupportedOperationException.class, lazy::getAllFields);
        assertThrows(UnsupportedOperationException.class, lazy::getUnknownFields);
        assertThrows(UnsupportedOperationException.class, () -> lazy.hasField(null));
        assertThrows(UnsupportedOperationException.class, () -> lazy.getField(null));
        assertThrows(UnsupportedOperationException.class, () -> lazy.setField(null, null));
        assertThrows(UnsupportedOperationException.class, () -> lazy.clearField(null));
        assertThrows(UnsupportedOperationException.class, () -> lazy.setUnknownFields(UnknownFieldSet.getDefaultInstance()));

        // 4. Explicit rawBytesEquals replacement works as expected
        assertTrue(lazy.rawBytesEquals(unscanned));
    }
}
