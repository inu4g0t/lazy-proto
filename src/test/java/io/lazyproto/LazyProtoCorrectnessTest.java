package io.lazyproto;

import com.google.protobuf.ByteString;
import com.google.protobuf.CodedOutputStream;
import com.google.protobuf.Message;
import com.google.protobuf.UnknownFieldSet;
import io.lazyproto.core.RawOrderEnvelope;
import io.lazyproto.core.RawWireView;
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
        LazyOrderEnvelope.Builder b = lazy.toBuilder()
                .setStatus(2)                                // 1 -> 2 (both 1-byte varint!)
                .setUpdatedAtFixed64(1800000000999L);        // fixed64 (always 8 bytes!)
        // Also mutate nested sub-message fields with same byte size
        b.getUserBuilder()
                .setCity("BJ")                               // "SH" -> "BJ" (both 2 UTF-8 bytes!)
                .setScore(99.875)                            // double (always 8 bytes!)
                .setVipLevel(5);                             // 3 -> 5 (both 1-byte varint!)
        // Also mutate 1 element in repeated list with same varint byte size
        b.getItemsBuilder(7).setQuantity(9);                 // 3 -> 9 (both 1-byte varint!)
        LazyOrderEnvelope updated = b.build();

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

        LazyOrderEnvelope lazyOrig = LazyOrderEnvelope.parseFrom(raw);
        LazyOrderEnvelope.Builder b = lazyOrig.toBuilder();
        // 1. Variable-size top-level string change (16 bytes -> 35 bytes)
        b.setBuyerName("alexander_the_great_vip_customer_01");
        // 2. Variable-size top-level varint change (1 byte -> 3 bytes)
        b.setStatus(50000);
        // 3. Variable-size nested UserProfile string change (2 bytes -> 24 bytes)
        b.getUserBuilder().setCity("SHANGHAI_PUDONG_NEW_AREA");
        // 4. Variable-size mutation on item #42 out of 100 items
        b.getItemsBuilder(42).setDescription("UPDATED_SHORT_DESC");
        b.getItemsBuilder(42).setQuantity(9999);
        LazyOrderEnvelope lazy = b.build();

        assertTrue(lazyOrig.isClean(), "Immutable source must stay clean");
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

        LazyOrderEnvelope lazyOrig = LazyOrderEnvelope.parseFrom(raw);
        assertEquals("secret-jwt-token-xyz-987654321", lazyOrig.getAuthToken());

        // Redact auth_token via Builder overlay
        LazyOrderEnvelope redacted = lazyOrig.toBuilder().clearAuthToken().build();
        assertEquals("", redacted.getAuthToken());
        assertEquals("secret-jwt-token-xyz-987654321", lazyOrig.getAuthToken());

        byte[] redactedBytes = redacted.toByteArray();
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
        LazyOrderEnvelope updated = lazy.toBuilder().setStatus(300).build();
        byte[] out = updated.toByteArray();

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
        ProtoDocument.Builder docBuilder = doc.toBuilder();
        docBuilder.setInt32(3, 99);
        docBuilder.at(5).setString(2, "SHENZHEN");
        docBuilder.remove(8);

        byte[] out = docBuilder.build().toByteArray();
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
        LazyOrderEnvelope.Builder scratch = LazyOrderEnvelope.newBuilder()
                .setOrderId(999888777L)
                .setBuyerName("scratch_builder_user")
                .setStatus(4)
                .setUpdatedAtFixed64(1234567890123L)
                .setUser(LazyUserProfile.newBuilder()
                        .setUserId(555L)
                        .setCity("HANGZHOU")
                        .setVipLevel(2)
                        .setScore(88.25)
                        .setEmail("scratch@example.com")
                        .build())
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
                    .setDescription("Built from scratch item " + i)
                    .build());
        }

        byte[] out = scratch.build().toByteArray();
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
        LazyOrderEnvelope.Builder b = LazyOrderEnvelope.parseFrom(raw).toBuilder();

        b.setOrderId(1L)
            .setBuyerName("completely_new_buyer_name_with_different_length")
            .setStatus(999999)
            .setUpdatedAtFixed64(42L)
            .setPayloadBytes(new byte[] { 9, 8, 7 })
            .setAuthToken("short_tok")
            .setTraceId("new_trace_id_with_extra_padding_0000000000")
            .setTotalCents(10L);

        b.getUserBuilder()
            .setUserId(2L)
            .setCity("GUANGZHOU_TIANHE")
            .setVipLevel(10000)
            .setScore(12.34)
            .setEmail("new_long_email_address_for_full_mutation@domain.org");

        for (int i = 0; i < b.getItemsCount(); i++) {
            b.getItemsBuilder(i)
                .setItemId(900000000L + i)
                .setSku("MUTATED-LONG-SKU-IDENTIFIER-" + i)
                .setQuantity(100000 + i)
                .setPriceCents(i + 1L)
                .setDescription("Short-" + i);
        }

        byte[] out = b.build().toByteArray();
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
        LazyOrderEnvelope.Builder builder = lazy.toBuilder();

        assertEquals(202611240001L, orBuilder.getOrderId());
        assertTrue(msg.isInitialized());

        // Verify toBuilder() snapshot isolation: mutating builder must NOT affect original 'lazy' message!
        builder.setStatus(99).setBuyerName("mutated_in_builder");
        builder.getUserBuilder().setCity("BEIJING_ISOLATED");
        builder.getItemsBuilder(0).setQuantity(777);

        assertEquals(1, lazy.getStatus(), "Original message status must remain 1 after mutating toBuilder() copy");
        assertEquals("alice_wonderland", lazy.getBuyerName(), "Original buyerName must remain untouched");
        assertEquals("SH", lazy.getUser().getCity(), "Original nested user city must remain untouched");
        assertEquals(1, lazy.getItems(0).getQuantity(), "Original repeated item must remain untouched");
        assertTrue(lazy.isClean(), "Original message must still be 100% clean!");

        LazyOrderEnvelope built1 = builder.build();
        assertEquals(99, built1.getStatus());
        assertEquals("mutated_in_builder", built1.getBuyerName());
        assertEquals("BEIJING_ISOLATED", built1.getUser().getCity());
        assertEquals(777, built1.getItems(0).getQuantity());

        // Mutating the SAME builder AFTER build() must NOT corrupt built1!
        LazyUserProfile.Builder heldUserBuilder = builder.getUserBuilder();
        builder.setStatus(123).setBuyerName("post_build_mutation");
        heldUserBuilder.setCity("GUANGZHOU_POST_BUILD");
        LazyOrderEnvelope built2 = builder.build();

        assertEquals(99, built1.getStatus(), "built1 must remain immutable after post-build builder mutation");
        assertEquals("mutated_in_builder", built1.getBuyerName());
        assertEquals("BEIJING_ISOLATED", built1.getUser().getCity());

        assertEquals(123, built2.getStatus());
        assertEquals("post_build_mutation", built2.getBuyerName());
        assertEquals("GUANGZHOU_POST_BUILD", built2.getUser().getCity());

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
        assertThrows(UnsupportedOperationException.class, () -> builder.setField(null, null));
        assertThrows(UnsupportedOperationException.class, () -> builder.clearField(null));
        assertThrows(UnsupportedOperationException.class, () -> builder.setUnknownFields(UnknownFieldSet.getDefaultInstance()));

        // 4. Explicit rawBytesEquals replacement works as expected
        assertTrue(lazy.rawBytesEquals(unscanned));
    }

    // =========================================================================
    // Category D: Review Regression Tests (GitHub Issue #1)
    // =========================================================================

    @Test
    @DisplayName("D1: Replacing clean external User then toBuilder() fork (with & without prior getUser)")
    void testD1_ReplaceCleanExternalUserThenFork() throws IOException {
        byte[] parentBytes = createComplexOrderBytes(2, 32);
        byte[] otherUserBytes = UserProfile.newBuilder()
                .setUserId(999L)
                .setCity("N")
                .setVipLevel(9)
                .setScore(10.0)
                .setEmail("n@example.com")
                .build()
                .toByteArray();

        LazyOrderEnvelope base = LazyOrderEnvelope.parseFrom(parentBytes);
        LazyOrderEnvelope.Builder b = base.toBuilder();
        b.setUser(LazyUserProfile.parseFrom(otherUserBytes)); // Replaced with CLEAN external User!

        // Fork again via toBuilder() WITHOUT calling getUser() first
        LazyOrderEnvelope forked1 = b.build().toBuilder().build();
        byte[] out1 = forked1.toByteArray();
        OrderEnvelope decoded1 = OrderEnvelope.parseFrom(out1);
        assertEquals("N", decoded1.getUser().getCity());
        assertEquals(999L, decoded1.getUser().getUserId());

        // Fork again and read getUser() first
        LazyOrderEnvelope forked2 = b.build().toBuilder().build();
        assertEquals("N", forked2.getUser().getCity());
        OrderEnvelope decoded2 = OrderEnvelope.parseFrom(forked2.toByteArray());
        assertEquals("N", decoded2.getUser().getCity());
    }

    @Test
    @DisplayName("D2: Restoring original clean User restores Fast-Path 0 and invalidates cachedSerializedSize")
    void testD2_RestoreCleanUserInvalidatesSizeCache() throws IOException {
        byte[] raw = createComplexOrderBytes(2, 32);
        LazyOrderEnvelope base = LazyOrderEnvelope.parseFrom(raw);
        LazyUserProfile origCleanUser = base.getUser();

        // Case 1: Lengthen then cache size then restore original clean user
        LazyOrderEnvelope.Builder b1 = base.toBuilder();
        b1.getUserBuilder().setCity("VERY_LONG_CITY_NAME_FOR_CACHE_TEST");
        int longSize = b1.build().getSerializedSize();
        assertTrue(longSize > raw.length);

        b1.setUser(origCleanUser);
        LazyOrderEnvelope restored1 = b1.build();
        assertTrue(restored1.isClean(), "Restoring exact clean slice from base must restore Fast-Path 0");
        assertEquals(raw.length, restored1.getSerializedSize());
        byte[] out1 = restored1.toByteArray();
        assertEquals(raw.length, out1.length);
        assertEquals("SH", OrderEnvelope.parseFrom(out1).getUser().getCity());

        // Case 2: Shorten then cache size then restore original clean user
        LazyOrderEnvelope.Builder b2 = base.toBuilder();
        b2.getUserBuilder().setCity("A");
        int shortSize = b2.build().getSerializedSize();
        assertTrue(shortSize < raw.length);

        b2.setUser(origCleanUser);
        LazyOrderEnvelope restored2 = b2.build();
        assertTrue(restored2.isClean());
        assertEquals(raw.length, restored2.getSerializedSize());
        byte[] out2 = restored2.toByteArray();
        assertEquals(raw.length, out2.length);
        assertEquals("SH", OrderEnvelope.parseFrom(out2).getUser().getCity());
    }

    @Test
    @DisplayName("D3: ProtoDocument toBuilder(), clone(), repeated reuse() (including field #64), missing-field insert & unpacked repeated")
    void testD3_ProtoDocumentEdgeCases() throws IOException {
        byte[] raw = createComplexOrderBytes(2, 32);

        // 1. toBuilder() & clone() snapshot isolation
        ProtoDocument doc = ProtoDocument.wrapBytes(raw);
        ProtoDocument.Builder b = doc.toBuilder();
        ProtoDocument.Builder cloned = b.clone();
        b.setInt32(3, 77).setString(2, "builder_buyer");
        cloned.setInt32(3, 88).setString(2, "cloned_buyer");

        ProtoDocument m1 = b.build();
        b.setInt32(3, 99); // Post-build mutation
        ProtoDocument m2 = b.build();

        assertEquals(1, doc.getInt32(3));
        assertEquals("alice_wonderland", doc.getString(2));
        assertEquals(77, m1.getInt32(3));
        assertEquals("builder_buyer", m1.getString(2));
        assertEquals(99, m2.getInt32(3));
        assertEquals(88, cloned.build().getInt32(3));

        // 2. Repeated reuse() on Builder including field #64 and mutated string cache
        ByteArrayOutputStream baos1 = new ByteArrayOutputStream();
        CodedOutputStream cos1 = CodedOutputStream.newInstance(baos1);
        cos1.writeInt32(64, 6400);
        cos1.writeString(2, "first_msg");
        cos1.flush();

        ByteArrayOutputStream baos2 = new ByteArrayOutputStream();
        CodedOutputStream cos2 = CodedOutputStream.newInstance(baos2);
        cos2.writeString(2, "second_msg");
        cos2.flush();

        ProtoDocument.Builder reused = ProtoDocument.newBuilder();
        reused.reuse(baos1.toByteArray());
        assertEquals(6400, reused.getInt32(64));
        reused.setString(2, "mutated_first");
        assertEquals("mutated_first", reused.getString(2));

        reused.reuse(baos2.toByteArray());
        assertFalse(reused.has(64), "Field #64 from previous reuse must be cleared");
        assertEquals(0, reused.getInt32(64));
        assertEquals("second_msg", reused.getString(2), "Mutated string from previous reuse must not leak");

        // 3. Missing-field insertion & unpacked repeated preservation in ProtoDocument
        ByteArrayOutputStream baos3 = new ByteArrayOutputStream();
        CodedOutputStream cos3 = CodedOutputStream.newInstance(baos3);
        cos3.writeInt32(1, 100);
        cos3.writeInt32(15, 10);
        cos3.writeInt32(15, 20);
        cos3.writeInt32(15, 30);
        cos3.flush();

        ProtoDocument.Builder doc3 = ProtoDocument.wrapBytes(baos3.toByteArray()).toBuilder();
        doc3.setString(2, "inserted_buyer"); // Field #2 was absent!
        doc3.setInt32(3, 5);                 // Field #3 was absent!
        byte[] out3 = doc3.build().toByteArray();

        OrderEnvelope decoded3 = OrderEnvelope.parseFrom(out3);
        assertEquals(100L, decoded3.getOrderId());
        assertEquals("inserted_buyer", decoded3.getBuyerName());
        assertEquals(5, decoded3.getStatus());
        // All 3 unpacked repeated #15 values must still be in unknown fields (not shadowed!)
        assertEquals(3, decoded3.getUnknownFields().getField(15).getVarintList().size());
    }

    @Test
    @DisplayName("D4: Split Sub-Message Merge & Wrong Wire Type Passthrough")
    void testD4_SplitSubMessageMergeAndWrongWireType() throws IOException {
        // Construct wire stream with split UserProfile (#5 appearing twice) and wrong wire type on field #1 (fixed64 instead of varint)
        byte[] seg1 = UserProfile.newBuilder().setUserId(111L).setCity("SH").build().toByteArray();
        byte[] seg2 = UserProfile.newBuilder().setVipLevel(7).setEmail("split@example.com").build().toByteArray();

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        CodedOutputStream cos = CodedOutputStream.newInstance(baos);
        cos.writeFixed64(1, 999999L); // Wrong wire type for field #1 (expects VARINT, got FIXED64)
        cos.writeByteArray(5, seg1);
        cos.writeInt32(3, 1);
        cos.writeByteArray(5, seg2);
        cos.flush();
        byte[] wire = baos.toByteArray();

        // 1. Typed LazyOrderEnvelope: wrong wire type must NOT crash getOrderId(), and split user must merge!
        LazyOrderEnvelope lazy = LazyOrderEnvelope.parseFrom(wire);
        assertEquals(0L, lazy.getOrderId(), "Wrong wire type for field #1 must be ignored by typed getter");
        assertEquals(111L, lazy.getUser().getUserId());
        assertEquals("SH", lazy.getUser().getCity());
        assertEquals(7, lazy.getUser().getVipLevel());
        assertEquals("split@example.com", lazy.getUser().getEmail());

        // Mutate merged user and re-serialize: both segments must merge cleanly and wrong wire type field #1 must pass through
        LazyOrderEnvelope.Builder b = lazy.toBuilder();
        b.getUserBuilder().setCity("HANGZHOU_MERGED");
        byte[] out = b.build().toByteArray();

        OrderEnvelope std = OrderEnvelope.parseFrom(out);
        assertEquals(111L, std.getUser().getUserId());
        assertEquals("HANGZHOU_MERGED", std.getUser().getCity());
        assertEquals(7, std.getUser().getVipLevel());
        assertEquals("split@example.com", std.getUser().getEmail());
        assertTrue(std.getUnknownFields().hasField(1), "Wrong wire type #1 must be preserved as unknown field");

        // 2. Dynamic ProtoDocument: split sub-message merge
        ProtoDocument.Builder doc = ProtoDocument.wrapBytes(wire).toBuilder();
        assertEquals(111L, doc.at(5).getInt64(1));
        assertEquals(7, doc.at(5).getInt32(3));
        assertEquals("split@example.com", doc.at(5).getString(5));
        doc.at(5).setString(2, "DOC_MERGED");
        OrderEnvelope docOut = OrderEnvelope.parseFrom(doc.build().toByteArray());
        assertEquals("DOC_MERGED", docOut.getUser().getCity());
        assertEquals(111L, docOut.getUser().getUserId());
        assertEquals(7, docOut.getUser().getVipLevel());
    }

    @Test
    @DisplayName("E1: Official protoc-generated Message binary equivalence (parse, mutate, splice, unknown fields)")
    void testE1_OfficialProtocGeneratedEquivalence() throws IOException {
        byte[] complex = createComplexOrderBytes(100, 4096);
        io.lazyproto.bench.proto.OrderEnvelope officialOrig =
                io.lazyproto.bench.proto.OrderEnvelope.parseFrom(complex);
        assertEquals(100, officialOrig.getItemsCount());
        assertTrue(officialOrig.getUnknownFields().hasField(50));
        assertTrue(officialOrig.getUnknownFields().hasField(51));

        // Mutate same way on official protoc builder and lazy builder
        io.lazyproto.bench.proto.OrderEnvelope.Builder ob = officialOrig.toBuilder()
                .setBuyerName("protoc_cross_check_buyer")
                .setStatus(50000);
        ob.getUserBuilder().setCity("BEIJING");
        ob.getItemsBuilder(42).setQuantity(999).setDescription("PROTOC_VERIFIED");
        ob.clearAuthToken();
        io.lazyproto.bench.proto.OrderEnvelope offMutated = ob.build();

        LazyOrderEnvelope.Builder lb = LazyOrderEnvelope.parseFrom(complex).toBuilder()
                .setBuyerName("protoc_cross_check_buyer")
                .setStatus(50000);
        lb.getUserBuilder().setCity("BEIJING");
        lb.getItemsBuilder(42).setQuantity(999).setDescription("PROTOC_VERIFIED");
        lb.clearAuthToken();
        byte[] lazyWire = lb.build().toByteArray();

        io.lazyproto.bench.proto.OrderEnvelope offFromLazy =
                io.lazyproto.bench.proto.OrderEnvelope.parseFrom(lazyWire);
        assertEquals(offMutated, offFromLazy);
    }

    @Test
    @DisplayName("F1: Java Lower-Level RawOrderEnvelope & RawWireView correctness vs Official protoc")
    void testF1_JavaLowerLevelRawOrderAndWireView() throws IOException {
        byte[] raw = createComplexOrderBytes(100, 4096);
        byte[] outBuf = new byte[raw.length + 4096];

        // 1. RawOrderEnvelope: partial read + FP0 + FP1 + FP2 + item edit + redact + scratch
        RawOrderEnvelope rawEnv = new RawOrderEnvelope().wrap(raw, 0, raw.length);
        assertFalse(rawEnv.isScanned());
        assertEquals(202611240001L, rawEnv.getOrderId());
        assertEquals(3, rawEnv.user().getVipLevel());
        assertTrue(rawEnv.isClean());
        int fp0Len = rawEnv.writeToBuffer(outBuf, 0);
        assertEquals(raw.length, fp0Len);
        assertArrayEquals(raw, Arrays.copyOf(outBuf, fp0Len));

        // FP1
        rawEnv.setStatus(2).setUpdatedAtFixed64(1800000000999L);
        rawEnv.user().setScore(98.75);
        assertTrue(rawEnv.isSameSizeDirtyOnly());
        int fp1Len = rawEnv.writeToBuffer(outBuf, 0);
        assertEquals(raw.length, fp1Len);
        io.lazyproto.bench.proto.OrderEnvelope fp1Decoded =
                io.lazyproto.bench.proto.OrderEnvelope.parseFrom(Arrays.copyOf(outBuf, fp1Len));
        assertEquals(2, fp1Decoded.getStatus());
        assertEquals(98.75, fp1Decoded.getUser().getScore());

        // FP2 + item #42 + redact
        rawEnv.wrap(raw, 0, raw.length);
        rawEnv.setBuyerName("raw_lower_level_buyer_name");
        rawEnv.user().setCity("HANGZHOU_RAW");
        rawEnv.items(42).setQuantity(999).setDescription("RAW_UPDATED_42");
        rawEnv.clearAuthToken();
        int fp2Len = rawEnv.writeToBuffer(outBuf, 0);
        io.lazyproto.bench.proto.OrderEnvelope fp2Decoded =
                io.lazyproto.bench.proto.OrderEnvelope.parseFrom(Arrays.copyOf(outBuf, fp2Len));
        assertEquals("raw_lower_level_buyer_name", fp2Decoded.getBuyerName());
        assertEquals("HANGZHOU_RAW", fp2Decoded.getUser().getCity());
        assertEquals(999, fp2Decoded.getItems(42).getQuantity());
        assertEquals("RAW_UPDATED_42", fp2Decoded.getItems(42).getDescription());
        assertEquals("", fp2Decoded.getAuthToken());
        assertTrue(fp2Decoded.getUnknownFields().hasField(50));
        assertTrue(fp2Decoded.getUnknownFields().hasField(51));

        // 2. RawWireView: generic dynamic Input-Bytes Wrapper
        RawWireView view = new RawWireView().wrap(raw, 0, raw.length);
        assertFalse(view.isScanned());
        assertEquals(202611240001L, view.getInt64(1));
        assertEquals("SH", view.sub(5).getString(2));
        assertEquals(9876543210L, view.getInt64(50));
        assertEquals(100, view.repeatedCount(6));
        assertEquals("SKU-PRO-0042", view.repeatedSub(6, 42).getString(2));

        view.setString(2, "raw_wire_view_buyer");
        view.sub(5).setString(2, "SHANGHAI_WIRE_VIEW");
        view.repeatedSub(6, 42).setInt32(3, 555);
        view.erase(8);
        int viewLen = view.writeToBuffer(outBuf, 0);
        io.lazyproto.bench.proto.OrderEnvelope viewDecoded =
                io.lazyproto.bench.proto.OrderEnvelope.parseFrom(Arrays.copyOf(outBuf, viewLen));
        assertEquals("raw_wire_view_buyer", viewDecoded.getBuyerName());
        assertEquals("SHANGHAI_WIRE_VIEW", viewDecoded.getUser().getCity());
        assertEquals(555, viewDecoded.getItems(42).getQuantity());
        assertEquals("", viewDecoded.getAuthToken());
        assertTrue(viewDecoded.getUnknownFields().hasField(50));
    }

    @Test
    @DisplayName("F2: Issue #7 Lower-Level Regressions (multi-wrap reuse, shared arena, child/parent sync, repeated scalar, overlong headerLen, >4KB toByteArray)")
    void testF2_Issue7LowerLevelRegressions() throws IOException {
        byte[] complex = createComplexOrderBytes(100, 4096);
        byte[] alt = createComplexOrderBytes(55, 64);

        // 1. RawOrderEnvelope multi-round wrap() + sparse items(42) across different inputs
        RawOrderEnvelope rawEnv = new RawOrderEnvelope();
        for (int round = 0; round < 3; round++) {
            rawEnv.wrap(complex, 0, complex.length);
            rawEnv.items(42).setQuantity(999).setDescription("UPDATED_ITEM_42_DESC");
            byte[] out1 = rawEnv.toByteArray();
            io.lazyproto.bench.proto.OrderEnvelope p1 = io.lazyproto.bench.proto.OrderEnvelope.parseFrom(out1);
            assertEquals(100, p1.getItemsCount());
            assertEquals(999, p1.getItems(42).getQuantity());
            assertEquals("UPDATED_ITEM_42_DESC", p1.getItems(42).getDescription());

            // Switching to alt input must NOT retain stale item #42 cache
            rawEnv.wrap(alt, 0, alt.length);
            assertEquals(55, rawEnv.getItemsCount());
            assertEquals((42 % 5) + 1, rawEnv.items(42).getQuantity());
            assertEquals("High performance component item #42 with extra details", rawEnv.items(42).getDescription());
        }

        // 2. RawWireView: parent setString("TOP") then first sub(5).setString("KID") must NOT wipe parent arena
        {
            RawWireView wv = new RawWireView().wrap(complex, 0, complex.length);
            wv.setString(2, "TOP");
            wv.sub(5).setString(2, "KID");
            byte[] out = wv.toByteArray();
            io.lazyproto.bench.proto.OrderEnvelope dec = io.lazyproto.bench.proto.OrderEnvelope.parseFrom(out);
            assertEquals("TOP", dec.getBuyerName());
            assertEquals("KID", dec.getUser().getCity());
        }

        // 3. RawWireView: repeatedSub(6, 0) across wrap(32021801) -> wrap(32021802) & bound child wrap()
        {
            byte[] in1 = new byte[] {0x32, 0x02, 0x18, 0x01};
            byte[] in2 = new byte[] {0x32, 0x02, 0x18, 0x02};
            RawWireView rv = new RawWireView().wrap(in1, 0, in1.length);
            assertEquals(1, rv.repeatedSub(6, 0).getInt32(3));
            rv.wrap(in2, 0, in2.length);
            assertEquals(2, rv.repeatedSub(6, 0).getInt32(3));

            byte[] parentIn = new byte[] {0x2A, 0x02, 0x08, 0x01};
            byte[] childNew = new byte[] {0x08, 0x02};
            RawWireView pv = new RawWireView().wrap(parentIn, 0, parentIn.length);
            pv.sub(5).wrap(childNew, 0, childNew.length);
            assertEquals(2L, pv.sub(5).getInt64(1));
            io.lazyproto.bench.proto.OrderEnvelope pDec = io.lazyproto.bench.proto.OrderEnvelope.parseFrom(pv.toByteArray());
            assertEquals(2L, pDec.getUser().getUserId());
        }

        // 4. RawWireView: repeated scalar preservation (08 01 08 02 + unrelated field 3)
        {
            byte[] repScalar = new byte[] {0x08, 0x01, 0x08, 0x02};
            RawWireView wv = new RawWireView().wrap(repScalar, 0, repScalar.length);
            wv.setInt64(3, 99L);
            byte[] out = wv.toByteArray();
            assertEquals(0x08, out[0]);
            assertEquals(0x01, out[1]);
            assertEquals(0x08, out[2]);
            assertEquals(0x02, out[3]);
            RawWireView verify = new RawWireView().wrap(out, 0, out.length);
            assertEquals(2, verify.repeatedCount(1));
            assertEquals(99L, verify.getInt64(3));
        }

        // 5. RawWireView: setBytes -> sub() & sub() edit -> getString() preserves child edit
        {
            byte[] pIn = new byte[] {0x2A, 0x02, 0x08, 0x01};
            RawWireView wv = new RawWireView().wrap(pIn, 0, pIn.length);
            wv.sub(5).setInt64(1, 777L);
            assertFalse(wv.getSlice(5).byteLength() == 0);
            io.lazyproto.bench.proto.OrderEnvelope dec = io.lazyproto.bench.proto.OrderEnvelope.parseFrom(wv.toByteArray());
            assertEquals(777L, dec.getUser().getUserId());

            byte[] repBytes = io.lazyproto.bench.proto.UserProfile.newBuilder()
                    .setUserId(888L).setCity("BYTES_SUB").build().toByteArray();
            wv.setBytes(5, repBytes, 0, repBytes.length);
            assertEquals(888L, wv.sub(5).getInt64(1));
            assertEquals("BYTES_SUB", wv.sub(5).getString(2));
        }

        // 6. RawLineItem.decodeAllEager reset on empty LineItem & >4096B toByteArray()
        {
            byte[] item1 = io.lazyproto.bench.proto.LineItem.newBuilder()
                    .setItemId(123L).setSku("SKU-1").setQuantity(5).setPriceCents(999L).setDescription("DESC")
                    .build().toByteArray();
            byte[] twoItems = new byte[2 + item1.length + 2];
            twoItems[0] = 0x32;
            twoItems[1] = (byte) item1.length;
            System.arraycopy(item1, 0, twoItems, 2, item1.length);
            twoItems[2 + item1.length] = 0x32;
            twoItems[3 + item1.length] = 0x00; // Empty second LineItem

            RawOrderEnvelope re = new RawOrderEnvelope().wrap(twoItems, 0, twoItems.length);
            assertEquals(2, re.getItemsCount());
            RawOrderEnvelope.RawLineItem tmp = new RawOrderEnvelope.RawLineItem();
            re.readItemEager(0, tmp);
            assertEquals(123L, tmp.itemId);
            re.readItemEager(1, tmp);
            assertEquals(0L, tmp.itemId);
            assertEquals(0, tmp.skuLen);
            assertEquals(0, tmp.quantity);
            assertEquals(0L, tmp.priceCents);
            assertEquals(0, tmp.descLen);

            byte[] bigPayload = new byte[8192];
            Arrays.fill(bigPayload, (byte) 0x5A);
            String bigBuyer = "B".repeat(5000);
            re.wrap(new byte[0], 0, 0).setPayloadBytes(bigPayload).setBuyerName(bigBuyer);
            io.lazyproto.bench.proto.OrderEnvelope bigDec = io.lazyproto.bench.proto.OrderEnvelope.parseFrom(re.toByteArray());
            assertEquals(8192, bigDec.getPayload().size());
            assertEquals(5000, bigDec.getBuyerName().length());

            RawWireView bigWv = new RawWireView().wrap(new byte[0], 0, 0).setString(2, bigBuyer);
            assertEquals(5000, io.lazyproto.bench.proto.OrderEnvelope.parseFrom(bigWv.toByteArray()).getBuyerName().length());
        }

        // 7. 10-byte overlong tag + 10-byte overlong LEN (headerLen == 20) in WireTape & ImmutableWireBase
        {
            byte[] payload = "OVERLONG_20B_HDR".getBytes(StandardCharsets.UTF_8);
            byte[] overlong = new byte[20 + payload.length];
            // 10-byte overlong tag for field #2 LEN (0x12)
            overlong[0] = (byte) 0x92;
            for (int i = 1; i < 9; i++) overlong[i] = (byte) 0x80;
            overlong[9] = 0x00;
            // 10-byte overlong length for payload.length
            overlong[10] = (byte) ((payload.length & 0x7F) | 0x80);
            for (int i = 11; i < 19; i++) overlong[i] = (byte) 0x80;
            overlong[19] = 0x00;
            System.arraycopy(payload, 0, overlong, 20, payload.length);

            RawWireView wvOver = new RawWireView().wrap(overlong, 0, overlong.length);
            assertEquals("OVERLONG_20B_HDR", wvOver.getString(2));
            LazyOrderEnvelope compatOver = LazyOrderEnvelope.parseFrom(overlong);
            assertEquals("OVERLONG_20B_HDR", compatOver.getBuyerName());
        }
    }

    @Test
    public void testF3_Issue8LowerLevelEdgeCases() throws Exception {
        // 1. RawWireView: child.wrap() with zero getters called on child before parent serialization
        {
            byte[] pIn = new byte[] {0x2A, 0x02, 0x08, 0x01}; // #5: user_id=1
            RawWireView wv = new RawWireView().wrap(pIn, 0, pIn.length);
            wv.sub(5).wrap(new byte[] {0x08, 0x02}, 0, 2); // replace with user_id=2, NO getters called
            byte[] out = wv.toByteArray();
            assertEquals("2a020802", bytesToHex(out));
            io.lazyproto.bench.proto.OrderEnvelope dec = io.lazyproto.bench.proto.OrderEnvelope.parseFrom(out);
            assertEquals(2L, dec.getUser().getUserId());

            // Also verify repeatedSub().wrap() with zero getters called
            byte[] repIn = new byte[] {0x32, 0x02, 0x18, 0x01}; // #6: quantity=1
            RawWireView rWv = new RawWireView().wrap(repIn, 0, repIn.length);
            rWv.repeatedSub(6, 0).wrap(new byte[] {0x18, 0x09}, 0, 2);
            byte[] repOut = rWv.toByteArray();
            assertEquals("32021809", bytesToHex(repOut));
            io.lazyproto.bench.proto.OrderEnvelope decRep = io.lazyproto.bench.proto.OrderEnvelope.parseFrom(repOut);
            assertEquals(9, decRep.getItems(0).getQuantity());
        }

        // 2. RawWireView: cached repeatedSub followed by setBytes must honor explicit setBytes override
        {
            byte[] repIn = new byte[] {0x32, 0x02, 0x18, 0x01};
            RawWireView wv = new RawWireView().wrap(repIn, 0, repIn.length);
            assertEquals(1, wv.repeatedSub(6, 0).getInt32(3));
            wv.setBytes(6, new byte[] {24, (byte) 129, 1}, 0, 3);
            byte[] out = wv.toByteArray();
            assertEquals("3203188101", bytesToHex(out));
            io.lazyproto.bench.proto.OrderEnvelope dec = io.lazyproto.bench.proto.OrderEnvelope.parseFrom(out);
            assertEquals(129, dec.getItems(0).getQuantity());
        }

        // 3. RawOrderEnvelope: >128 items with partial access/edit on item(0) only
        {
            io.lazyproto.bench.proto.OrderEnvelope.Builder b = io.lazyproto.bench.proto.OrderEnvelope.newBuilder()
                    .setOrderId(9001L)
                    .setBuyerName("buyer_200");
            for (int i = 0; i < 200; i++) {
                b.addItems(io.lazyproto.bench.proto.LineItem.newBuilder()
                        .setItemId(1000L + i)
                        .setSku("SKU-" + i)
                        .setQuantity(i + 1)
                        .setPriceCents(50L + i)
                        .build());
            }
            byte[] raw200 = b.build().toByteArray();

            RawOrderEnvelope env = new RawOrderEnvelope().wrap(raw200, 0, raw200.length);
            // Var-size edit on item(0) only (activeItemStates.length == 128 < itemCount == 200)
            env.items(0).setSku("UPDATED_VAR_LEN_SKU_000");
            byte[] varOut = env.toByteArray();
            io.lazyproto.bench.proto.OrderEnvelope decVar = io.lazyproto.bench.proto.OrderEnvelope.parseFrom(varOut);
            assertEquals(200, decVar.getItemsCount());
            assertEquals("UPDATED_VAR_LEN_SKU_000", decVar.getItems(0).getSku());
            assertEquals("SKU-199", decVar.getItems(199).getSku());

            // Same-size edit on item(0) only (also checks applyInPlacePatches loop bound)
            env.wrap(raw200, 0, raw200.length);
            env.items(0).setSku("X-000"); // same length as "SKU-0"
            byte[] sameOut = new byte[raw200.length];
            int written = env.writeToBuffer(sameOut, 0);
            assertEquals(raw200.length, written);
            io.lazyproto.bench.proto.OrderEnvelope decSame = io.lazyproto.bench.proto.OrderEnvelope.parseFrom(sameOut);
            assertEquals(200, decSame.getItemsCount());
            assertEquals("X-000", decSame.getItems(0).getSku());
            assertEquals("SKU-199", decSame.getItems(199).getSku());
        }
    }

    @Test
    @DisplayName("F4: Lower-Level bug regressions (multi-entry FP1 shadow, repeatedSub counter leak, child detach, readItemEager)")
    public void testF4_LowerLevelBugRegressions() throws Exception {
        // Bug 1a: Split submessage (2a 02 08 01 2a 02 18 02) + same-size setBytes(5, {0x08, 0x09})
        // Must NOT take FP1; earlier shadowed entry must be stripped from output.
        {
            byte[] splitUser = new byte[] {0x2A, 0x02, 0x08, 0x01, 0x2A, 0x02, 0x18, 0x02};
            RawWireView wv = new RawWireView().wrap(splitUser, 0, splitUser.length);
            byte[] replacement = new byte[] {0x08, 0x09}; // same length (2B) as last segment
            wv.setBytes(5, replacement, 0, replacement.length);
            assertFalse(wv.isSameSizeDirtyOnly(), "Shadowed multi-entry field must not use FP1");
            byte[] out = wv.toByteArray();
            assertEquals("2a020809", bytesToHex(out));
            io.lazyproto.bench.proto.OrderEnvelope dec = io.lazyproto.bench.proto.OrderEnvelope.parseFrom(out);
            assertEquals(9L, dec.getUser().getUserId());
            assertEquals(0, dec.getUser().getVipLevel());
        }

        // Bug 1b: Repeated field (32 02 18 01 32 02 18 02) + same-size setBytes(6, {0x18, 0x09})
        {
            byte[] twoItems = new byte[] {0x32, 0x02, 0x18, 0x01, 0x32, 0x02, 0x18, 0x02};
            RawWireView wv = new RawWireView().wrap(twoItems, 0, twoItems.length);
            byte[] replacement = new byte[] {0x18, 0x09};
            wv.setBytes(6, replacement, 0, replacement.length);
            assertFalse(wv.isSameSizeDirtyOnly(), "Shadowed repeated field must not use FP1");
            byte[] out = wv.toByteArray();
            assertEquals("32021809", bytesToHex(out));
            io.lazyproto.bench.proto.OrderEnvelope dec = io.lazyproto.bench.proto.OrderEnvelope.parseFrom(out);
            assertEquals(1, dec.getItemsCount());
            assertEquals(9, dec.getItems(0).getQuantity());
        }

        // Bug 1c: Duplicate scalar (08 01 08 02) + same-size setInt64(1, 5)
        {
            byte[] dupScalar = new byte[] {0x08, 0x01, 0x08, 0x02};
            RawWireView wv = new RawWireView().wrap(dupScalar, 0, dupScalar.length);
            wv.setInt64(1, 5L);
            assertFalse(wv.isSameSizeDirtyOnly(), "Shadowed duplicate scalar must not use FP1");
            byte[] out = wv.toByteArray();
            assertEquals("0805", bytesToHex(out));
            io.lazyproto.bench.proto.OrderEnvelope dec = io.lazyproto.bench.proto.OrderEnvelope.parseFrom(out);
            assertEquals(5L, dec.getOrderId());
        }

        // Bug 2: repeatedSub mutate -> setBytes / setInt* / erase dirty-counter leak check
        {
            // Single entry: var-size repeatedSub edit -> same-size setBytes must restore FP1 eligibility
            byte[] oneItem = new byte[] {0x32, 0x02, 0x18, 0x01};
            RawWireView wv = new RawWireView().wrap(oneItem, 0, oneItem.length);
            wv.repeatedSub(6, 0).setInt32(3, 500); // varSizeDirtyCount = 1
            assertFalse(wv.isSameSizeDirtyOnly());
            byte[] sameRep = new byte[] {0x18, 0x09};
            wv.setBytes(6, sameRep, 0, sameRep.length); // must clear leaked varSizeDirtyCount!
            assertTrue(wv.isSameSizeDirtyOnly(), "varSizeDirtyCount leaked from prior repeatedSub edit");
            byte[] out1 = wv.toByteArray();
            assertEquals("32021809", bytesToHex(out1));
            io.lazyproto.bench.proto.OrderEnvelope dec1 = io.lazyproto.bench.proto.OrderEnvelope.parseFrom(out1);
            assertEquals(1, dec1.getItemsCount());
            assertEquals(9, dec1.getItems(0).getQuantity());

            // Multi-entry: same-size + var-size repeatedSub edits -> setBytes -> erase
            byte[] twoItems = new byte[] {0x32, 0x02, 0x18, 0x01, 0x32, 0x02, 0x18, 0x02};
            wv.wrap(twoItems, 0, twoItems.length);
            wv.repeatedSub(6, 0).setInt32(3, 5);   // same-size
            wv.repeatedSub(6, 1).setInt32(3, 500); // var-size
            wv.setBytes(6, sameRep, 0, sameRep.length);
            assertFalse(wv.isSameSizeDirtyOnly());
            assertEquals("32021809", bytesToHex(wv.toByteArray()));
            wv.erase(6);
            assertEquals("", bytesToHex(wv.toByteArray()));
            io.lazyproto.bench.proto.OrderEnvelope decEmpty = io.lazyproto.bench.proto.OrderEnvelope.parseFrom(wv.toByteArray());
            assertEquals(0, decEmpty.getItemsCount());

            // repeatedSub same-size edit -> erase with surviving sibling field
            byte[] itemAndOrder = new byte[] {0x08, 0x01, 0x32, 0x02, 0x18, 0x01};
            wv.wrap(itemAndOrder, 0, itemAndOrder.length);
            wv.repeatedSub(6, 0).setInt32(3, 5);
            assertTrue(wv.isSameSizeDirtyOnly());
            wv.erase(6);
            assertFalse(wv.isSameSizeDirtyOnly());
            assertEquals("0801", bytesToHex(wv.toByteArray()));

            // repeatedSub edit -> setInt64 on same field -> erase
            wv.wrap(oneItem, 0, oneItem.length);
            wv.repeatedSub(6, 0).setInt32(3, 500);
            wv.setInt64(6, 7L);
            assertEquals("3007", bytesToHex(wv.toByteArray()));
            wv.erase(6);
            assertEquals("", bytesToHex(wv.toByteArray()));
        }

        // Bug 3: Stale child view detachment after wrap(), setBytes(), and erase()
        {
            byte[] msg1 = new byte[] {0x2A, 0x02, 0x08, 0x01, 0x32, 0x02, 0x18, 0x01};
            byte[] msg2 = new byte[] {0x2A, 0x02, 0x08, 0x02, 0x32, 0x02, 0x18, 0x02};
            RawWireView wv = new RawWireView().wrap(msg1, 0, msg1.length);
            RawWireView staleSub = wv.sub(5);
            RawWireView staleRep = wv.repeatedSub(6, 0);
            RawWireView staleGrand = staleSub.sub(2);

            // Parent wrap() must detach staleSub, staleRep, and isolate staleGrand
            wv.wrap(msg2, 0, msg2.length);
            staleSub.setInt64(1, 999L);
            staleSub.wrap(new byte[] {0x08, 0x77}, 0, 2);
            staleRep.setInt32(3, 888);
            staleRep.wrap(new byte[] {0x18, 0x66}, 0, 2);
            staleGrand.setInt64(1, 777L);

            assertTrue(wv.isClean(), "Stale child mutations after parent wrap() must not dirty parent");
            assertEquals("2a02080232021802", bytesToHex(wv.toByteArray()));
            io.lazyproto.bench.proto.OrderEnvelope dec = io.lazyproto.bench.proto.OrderEnvelope.parseFrom(wv.toByteArray());
            assertEquals(2L, dec.getUser().getUserId());
            assertEquals(1, dec.getItemsCount());
            assertEquals(2, dec.getItems(0).getQuantity());

            // Parent setBytes() and erase() must detach stale sub and repeatedSub
            RawWireView staleSub2 = wv.sub(5);
            RawWireView staleRep2 = wv.repeatedSub(6, 0);
            byte[] newUser = new byte[] {0x08, 0x07};
            wv.setBytes(5, newUser, 0, newUser.length);
            wv.erase(6);
            staleSub2.setInt64(1, 555L);
            staleRep2.setInt32(3, 444);

            byte[] outAfterOverride = wv.toByteArray();
            assertEquals("2a020807", bytesToHex(outAfterOverride));
            io.lazyproto.bench.proto.OrderEnvelope dec2 = io.lazyproto.bench.proto.OrderEnvelope.parseFrom(outAfterOverride);
            assertEquals(7L, dec2.getUser().getUserId());
            assertEquals(0, dec2.getItemsCount());
        }

        // Bug 4: RawOrderEnvelope.wrap() -> readItemEager(0, out) directly without calling getItemsCount() first
        {
            byte[] rawOrder = io.lazyproto.bench.proto.OrderEnvelope.newBuilder()
                    .setOrderId(77L)
                    .addItems(io.lazyproto.bench.proto.LineItem.newBuilder()
                            .setItemId(101L)
                            .setSku("SKU-DIRECT")
                            .setQuantity(4)
                            .setPriceCents(2500L)
                            .setDescription("Direct eager read")
                            .build())
                    .addItems(io.lazyproto.bench.proto.LineItem.newBuilder().build())
                    .build().toByteArray();

            RawOrderEnvelope env = new RawOrderEnvelope().wrap(rawOrder, 0, rawOrder.length);
            assertFalse(env.isScanned());
            RawOrderEnvelope.RawLineItem out = new RawOrderEnvelope.RawLineItem();
            env.readItemEager(0, out); // Must scan lazily without needing getItemsCount() first
            assertTrue(env.isScanned());
            assertEquals(101L, out.itemId);
            assertEquals(4, out.quantity);
            assertEquals(2500L, out.priceCents);
            assertEquals("SKU-DIRECT", new String(rawOrder, out.skuOff, out.skuLen, StandardCharsets.UTF_8));
            assertEquals("Direct eager read", new String(rawOrder, out.descOff, out.descLen, StandardCharsets.UTF_8));

            // Subsequent empty item read must clear previous fields
            env.readItemEager(1, out);
            assertEquals(0L, out.itemId);
            assertEquals(0, out.skuLen);
            assertEquals(0, out.quantity);
            assertEquals(0L, out.priceCents);
            assertEquals(0, out.descLen);
        }
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >>> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }
}
