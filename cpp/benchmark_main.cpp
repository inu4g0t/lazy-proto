#include "lazy_proto.hpp"
#include "std_proto.hpp"
#include "order_envelope.pb.h"

#include <chrono>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <deque>
#include <google/protobuf/stubs/common.h>
#include <google/protobuf/unknown_field_set.h>
#include <new>
#include <random>
#include <stdexcept>
#include <string>
#include <vector>

using namespace lazyproto;
using namespace stdproto;
namespace pb = lazyproto::bench;

// Always-active runtime check (never stripped by -DNDEBUG)
#define LP_CHECK(cond)                                                                      \
    do {                                                                                    \
        if (!(cond)) {                                                                      \
            std::fprintf(stderr, "CHECK FAILED at %s:%d: %s\n", __FILE__, __LINE__, #cond); \
            std::abort();                                                                   \
        }                                                                                   \
    } while (0)

// ============================================================================
// Global Heap Allocation Counter (tracks malloc/new bytes across C++ & libprotobuf)
// Disabled under ASan so ASan's own allocator/mismatch interceptors stay active.
// ============================================================================
static thread_local uint64_t g_thread_alloc_bytes = 0;

#if defined(__SANITIZE_ADDRESS__)
#define LP_ASAN_ENABLED 1
#elif defined(__has_feature)
#if __has_feature(address_sanitizer)
#define LP_ASAN_ENABLED 1
#endif
#endif

#if !defined(LP_ASAN_ENABLED)
__attribute__((noinline)) void* operator new(std::size_t size) {
    g_thread_alloc_bytes += size;
    void* p = std::malloc(size);
    if (!p) throw std::bad_alloc();
    return p;
}

__attribute__((noinline)) void* operator new[](std::size_t size) {
    return ::operator new(size);
}

__attribute__((noinline)) void operator delete(void* p) noexcept {
    std::free(p);
}

__attribute__((noinline)) void operator delete(void* p, std::size_t) noexcept {
    std::free(p);
}

__attribute__((noinline)) void operator delete[](void* p) noexcept {
    std::free(p);
}

__attribute__((noinline)) void operator delete[](void* p, std::size_t) noexcept {
    std::free(p);
}
#endif

// ============================================================================
// Symmetric Output Keep-Alive (Prevents DCE/DSE on Both Official & Lazy Outputs)
// ============================================================================
inline uint64_t observe_output(const uint8_t* ptr, size_t len) noexcept {
#if defined(__GNUC__) || defined(__clang__)
    asm volatile("" : : "r"(ptr), "r"(len) : "memory");
#endif
    return len > 0 ? (static_cast<uint64_t>(len) ^ ptr[0] ^ ptr[len - 1]) : 0ULL;
}

inline uint64_t observe_output(const std::string& s) noexcept {
    return observe_output(reinterpret_cast<const uint8_t*>(s.data()), s.size());
}

// ============================================================================
// Test Fixture Generator (Cross-Checked with Official libprotobuf)
// ============================================================================
std::vector<uint8_t> create_complex_order_bytes(int item_count, int payload_size) {
    StdOrderEnvelope env;
    env.order_id = 202611240001ULL;
    env.buyer_name = "alice_wonderland";
    env.status = 1;
    env.updated_at_fixed64 = 1795497000123ULL;

    env.has_user = true;
    env.user.user_id = 88001122ULL;
    env.user.city = "SH";
    env.user.vip_level = 3;
    env.user.score = 95.5;
    env.user.email = "alice@example.com";

    env.payload.resize(payload_size);
    for (int i = 0; i < payload_size; i++) {
        env.payload[i] = static_cast<char>((i * 31 + 7) & 0xFF);
    }
    env.auth_token = "secret-jwt-token-xyz-987654321";
    env.trace_id = "trace-00112233445566778899aabbccddeeff";
    env.total_cents = 1250000ULL;

    for (int i = 0; i < item_count; i++) {
        StdLineItem item;
        item.item_id = 10000ULL + i;
        char sku_buf[32];
        std::snprintf(sku_buf, sizeof(sku_buf), "SKU-PRO-%04d", i);
        item.sku = sku_buf;
        item.quantity = (i % 5) + 1;
        item.price_cents = 19900ULL + i * 100ULL;
        item.description = "High performance component item #" + std::to_string(i) + " with extra details";
        env.items.push_back(std::move(item));
    }

    env.unknowns.push_back({make_tag(50, WIRETYPE_VARINT), 9876543210ULL, {}});
    env.unknowns.push_back({make_tag(51, WIRETYPE_LENGTH_DELIMITED), 0, "unknown-extension-metadata-v2"});

    uint32_t sz = env.ByteSizeLong();
    std::vector<uint8_t> out(sz + 16, 0);
    size_t written = env.SerializeToArray(out.data());
    LP_CHECK(written == sz);
    out.resize(sz);

    pb::OrderEnvelope pb_check;
    LP_CHECK(pb_check.ParseFromArray(out.data(), static_cast<int>(out.size())));
    LP_CHECK(pb_check.order_id() == 202611240001ULL);
    LP_CHECK(pb_check.items_size() == item_count);
    LP_CHECK(pb_check.GetReflection()->GetUnknownFields(pb_check).field_count() == 2);
    return out;
}

// ============================================================================
// Correctness Verification Suite (Always Executed Even Under -DNDEBUG)
// ============================================================================
void run_correctness_tests() {
    std::printf("[Correctness] Running C++20 Lower-Level WireView & OrderEnvelopeView vs Official libprotobuf...\n");
    std::vector<uint8_t> raw = create_complex_order_bytes(100, 4096);
    std::vector<uint8_t> out_buf(raw.size() + 8192);

    // 1. A1 & A2: Partial Read & Fast-Path 0 Passthrough
    {
        OrderEnvelopeView lazy;
        lazy.wrap(raw.data(), 0, static_cast<uint32_t>(raw.size()));
        LP_CHECK(!lazy.scanned_);
        LP_CHECK(lazy.order_id() == 202611240001ULL);
        LP_CHECK(lazy.buyer_name() == "alice_wonderland");
        LP_CHECK(lazy.status() == 1);
        LP_CHECK(lazy.user().vip_level() == 3);
        LP_CHECK(lazy.user().city() == "SH");
        LP_CHECK(lazy.items_count() == 100);
        LP_CHECK(lazy.items(3).sku() == "SKU-PRO-0003");
        LP_CHECK(lazy.is_clean());

        size_t len = lazy.merge_to(out_buf.data(), out_buf.size());
        LP_CHECK(len == raw.size());
        LP_CHECK(std::memcmp(raw.data(), out_buf.data(), len) == 0);
    }

    // 2. A3: Fast-Path 1 Deep In-Place Binary Patching (Automatic Dirty Propagation!)
    {
        OrderEnvelopeView lazy;
        lazy.wrap(raw.data(), 0, static_cast<uint32_t>(raw.size()));
        lazy.set_status(2);
        lazy.set_updated_at_fixed64(1800000000999ULL);
        lazy.mutable_user().set_city("BJ");
        lazy.mutable_user().set_score(99.875);
        lazy.mutable_user().set_vip_level(5);
        lazy.items(7).set_quantity(9);

        LP_CHECK(lazy.is_same_size_dirty_only());
        size_t len = lazy.merge_to(out_buf.data(), out_buf.size());
        LP_CHECK(len == raw.size());

        pb::OrderEnvelope pb_decoded;
        LP_CHECK(pb_decoded.ParseFromArray(out_buf.data(), static_cast<int>(len)));
        LP_CHECK(pb_decoded.status() == 2);
        LP_CHECK(pb_decoded.updated_at_fixed64() == 1800000000999ULL);
        LP_CHECK(pb_decoded.user().city() == "BJ");
        LP_CHECK(pb_decoded.user().score() == 99.875);
        LP_CHECK(pb_decoded.user().vip_level() == 5);
        LP_CHECK(pb_decoded.items(7).quantity() == 9);
        LP_CHECK(pb_decoded.GetReflection()->GetUnknownFields(pb_decoded).field_count() == 2);
    }

    // 3. A4 & A5: Fast-Path 2 & 3 Single-Pass Span Splicing + Single Repeated Item Mutation
    {
        OrderEnvelopeView lazy;
        lazy.wrap(raw.data(), 0, static_cast<uint32_t>(raw.size()));
        lazy.set_buyer_name("alexander_the_great_vip_customer_01");
        lazy.set_status(50000);
        lazy.mutable_user().set_city("SHANGHAI_PUDONG_NEW_AREA");
        lazy.items(42).set_description("UPDATED_SHORT_DESC");
        lazy.items(42).set_quantity(9999);

        size_t len = lazy.merge_to(out_buf.data(), out_buf.size());
        pb::OrderEnvelope pb_decoded;
        LP_CHECK(pb_decoded.ParseFromArray(out_buf.data(), static_cast<int>(len)));
        LP_CHECK(pb_decoded.buyer_name() == "alexander_the_great_vip_customer_01");
        LP_CHECK(pb_decoded.status() == 50000);
        LP_CHECK(pb_decoded.user().city() == "SHANGHAI_PUDONG_NEW_AREA");
        LP_CHECK(pb_decoded.user().email() == "alice@example.com");
        LP_CHECK(pb_decoded.items_size() == 100);
        LP_CHECK(pb_decoded.items(42).description() == "UPDATED_SHORT_DESC");
        LP_CHECK(pb_decoded.items(42).quantity() == 9999);
        LP_CHECK(pb_decoded.items(42).sku() == "SKU-PRO-0042");
        LP_CHECK(pb_decoded.items(99).sku() == "SKU-PRO-0099");
        LP_CHECK(pb_decoded.GetReflection()->GetUnknownFields(pb_decoded).field_count() == 2);
    }

    // 4. A6: Gateway Field Redaction + Delete->Re-Set & Same->Var->Same State Transitions
    {
        OrderEnvelopeView lazy;
        lazy.wrap(raw.data(), 0, static_cast<uint32_t>(raw.size()));
        lazy.clear_auth_token();
        size_t len = lazy.merge_to(out_buf.data(), out_buf.size());
        LP_CHECK(len < raw.size());

        pb::OrderEnvelope pb_decoded;
        LP_CHECK(pb_decoded.ParseFromArray(out_buf.data(), static_cast<int>(len)));
        LP_CHECK(pb_decoded.auth_token().empty());
        LP_CHECK(pb_decoded.trace_id() == "trace-00112233445566778899aabbccddeeff");
        LP_CHECK(pb_decoded.GetReflection()->GetUnknownFields(pb_decoded).field_count() == 2);

        // Transition: same-size -> var-size -> back to same-size
        OrderEnvelopeView sm;
        sm.wrap(raw.data(), 0, static_cast<uint32_t>(raw.size()));
        sm.set_status(2);
        LP_CHECK(sm.is_same_size_dirty_only());
        sm.set_status(50000);
        LP_CHECK(!sm.is_same_size_dirty_only());
        sm.set_status(3);
        LP_CHECK(sm.is_same_size_dirty_only());
        size_t sm_len = sm.merge_to(out_buf.data(), out_buf.size());
        LP_CHECK(sm_len == raw.size());
        LP_CHECK(pb_decoded.ParseFromArray(out_buf.data(), static_cast<int>(sm_len)));
        LP_CHECK(pb_decoded.status() == 3);
    }

    // 5. Generic Dynamic WireView: Read Any Field + Cache + Modify with Cache + Merge
    {
        WireView view(raw.data(), raw.size());
        LP_CHECK(!view.is_scanned());
        LP_CHECK(view.get_varint(1) == 202611240001ULL);
        LP_CHECK(view.get_bytes(2) == "alice_wonderland");
        LP_CHECK(view.get_varint(50) == 9876543210ULL); // Unknown field #50!
        LP_CHECK(view.get_bytes(51) == "unknown-extension-metadata-v2"); // Unknown field #51!
        LP_CHECK(view.sub(5).get_bytes(2) == "SH");
        LP_CHECK(view.repeated_count(6) == 100);
        LP_CHECK(view.repeated_sub(6, 42).get_bytes(2) == "SKU-PRO-0042");

        // Modify with temporary std::string copied into ScratchArena + nested + repeated + erase
        {
            std::string temp_buyer = "wireview_cached_buyer_name";
            view.set_bytes_copy(2, temp_buyer);
            temp_buyer.assign(temp_buyer.size(), 'X'); // Mutate temp to verify arena copy!
        }
        LP_CHECK(view.get_bytes(2) == "wireview_cached_buyer_name");
        view.sub(5).set_bytes(2, "HANGZHOU_WIREVIEW");
        view.repeated_sub(6, 42).set_varint(3, 777);
        view.erase(8); // Delete auth_token

        std::string merged;
        view.merge_to(merged);
        pb::OrderEnvelope pb_decoded;
        LP_CHECK(pb_decoded.ParseFromArray(merged.data(), static_cast<int>(merged.size())));
        LP_CHECK(pb_decoded.buyer_name() == "wireview_cached_buyer_name");
        LP_CHECK(pb_decoded.user().city() == "HANGZHOU_WIREVIEW");
        LP_CHECK(pb_decoded.items(42).quantity() == 777);
        LP_CHECK(pb_decoded.auth_token().empty());
        LP_CHECK(pb_decoded.GetReflection()->GetUnknownFields(pb_decoded).field_count() == 2);
    }

    // 6. Tape Overflow Safety (>160 fields & >128 items) + Split Sub-Message + Truncated Input Guard
    {
        std::vector<uint8_t> large_raw = create_complex_order_bytes(200, 64);
        OrderEnvelopeView large_view;
        large_view.wrap(large_raw.data(), 0, static_cast<uint32_t>(large_raw.size()));
        LP_CHECK(large_view.items_count() == 200);
        large_view.items(199).set_quantity(888);
        std::string large_merged;
        large_view.merge_to(large_merged);
        pb::OrderEnvelope pb_large;
        LP_CHECK(pb_large.ParseFromArray(large_merged.data(), static_cast<int>(large_merged.size())));
        LP_CHECK(pb_large.items_size() == 200);
        LP_CHECK(pb_large.items(199).quantity() == 888);

        // Split singular sub-message (#5 appearing twice)
        pb::UserProfile s1, s2;
        s1.set_user_id(111);
        s1.set_city("SH");
        s2.set_vip_level(9);
        s2.set_email("split@example.com");
        std::string s1_b = s1.SerializeAsString();
        std::string s2_b = s2.SerializeAsString();
        std::vector<uint8_t> split_wire;
        WireWriter sw_buf(out_buf.data());
        sw_buf.write_len_delimited(5, s1_b);
        sw_buf.write_byte(0x18); sw_buf.write_varint32(1);
        sw_buf.write_len_delimited(5, s2_b);
        split_wire.assign(out_buf.data(), out_buf.data() + sw_buf.size());

        OrderEnvelopeView split_view;
        split_view.wrap(split_wire.data(), 0, static_cast<uint32_t>(split_wire.size()));
        LP_CHECK(split_view.user().user_id() == 111);
        LP_CHECK(split_view.user().vip_level() == 9);
        split_view.mutable_user().set_city("SPLIT_MERGED");
        std::string split_out;
        split_view.merge_to(split_out);
        pb::OrderEnvelope pb_split;
        LP_CHECK(pb_split.ParseFromArray(split_out.data(), static_cast<int>(split_out.size())));
        LP_CHECK(pb_split.user().user_id() == 111);
        LP_CHECK(pb_split.user().vip_level() == 9);
        LP_CHECK(pb_split.user().city() == "SPLIT_MERGED");
        LP_CHECK(pb_split.user().email() == "split@example.com");

        // Truncated wire guard
        std::vector<uint8_t> truncated = {0x12, 0x10, 'a', 'b', 'c'};
        bool caught = false;
        try {
            WireView bad(truncated.data(), truncated.size());
            bad.get_bytes(2);
        } catch (const std::runtime_error&) {
            caught = true;
        }
        LP_CHECK(caught);

        // Issue #7 Regressions:
        // (a) Default-constructed WireView has/set/merge
        {
            WireView def_view;
            LP_CHECK(!def_view.has(1));
            LP_CHECK(def_view.get_varint(1, 99) == 99);
            def_view.set_varint(1, 42);
            LP_CHECK(def_view.has(1) && def_view.get_varint(1) == 42);
            std::string def_out;
            def_view.merge_to(def_out);
            pb::OrderEnvelope pb_def;
            LP_CHECK(pb_def.ParseFromArray(def_out.data(), static_cast<int>(def_out.size())));
            LP_CHECK(pb_def.order_id() == 42);
        }

        // (b) WireView repeated_sub growth capacity (32 02 08 01 + 200B string -> 208B)
        {
            const uint8_t rep_in[] = {0x32, 0x02, 0x08, 0x01};
            WireView rv(rep_in, sizeof(rep_in));
            std::string long_sku(200, 'S');
            rv.repeated_sub(6, 0).set_bytes_copy(2, long_sku);
            size_t max_sz = rv.max_merged_size();
            std::vector<uint8_t> exact_buf(max_sz);
            size_t actual_sz = rv.merge_to(exact_buf.data(), exact_buf.size());
            LP_CHECK(actual_sz <= max_sz);
            LP_CHECK(actual_sz >= 208);
            pb::OrderEnvelope pb_rep;
            LP_CHECK(pb_rep.ParseFromArray(exact_buf.data(), static_cast<int>(actual_sz)));
            LP_CHECK(pb_rep.items_size() == 1 && pb_rep.items(0).item_id() == 1);
            LP_CHECK(pb_rep.items(0).sku() == long_sku);
        }

        // (c) WireView split sub-message (2a 02 08 01 2a 02 18 02), set_bytes -> sub, sub edit -> get_bytes
        {
            const uint8_t split_in[] = {0x2A, 0x02, 0x08, 0x01, 0x2A, 0x02, 0x18, 0x02};
            WireView sv(split_in, sizeof(split_in));
            sv.sub(5).set_varint(3, 7);
            LP_CHECK(sv.sub(5).get_varint(3) == 7);
            std::string_view sub_bytes = sv.get_bytes(5);
            LP_CHECK(!sub_bytes.empty());
            std::string out_split;
            sv.merge_to(out_split);
            pb::OrderEnvelope pb_split2;
            LP_CHECK(pb_split2.ParseFromArray(out_split.data(), static_cast<int>(out_split.size())));
            LP_CHECK(pb_split2.user().user_id() == 1);
            LP_CHECK(pb_split2.user().vip_level() == 7);

            // set_bytes -> sub() sees new bytes
            pb::UserProfile new_u;
            new_u.set_user_id(555);
            new_u.set_city("NEW_CITY");
            std::string new_u_bytes = new_u.SerializeAsString();
            sv.set_bytes_copy(5, new_u_bytes);
            LP_CHECK(sv.sub(5).get_varint(1) == 555);
            LP_CHECK(sv.sub(5).get_bytes(2) == "NEW_CITY");
        }

        // (d) WireView repeated scalar preservation (08 01 08 02 + add field 3 -> preserves [1, 2])
        {
            const uint8_t rep_scalar[] = {0x08, 0x01, 0x08, 0x02};
            WireView rsv(rep_scalar, sizeof(rep_scalar));
            rsv.set_varint(3, 99);
            std::string out_rs;
            rsv.merge_to(out_rs);
            // Parse raw records: must contain field 1 = 1, field 1 = 2, and field 3 = 99
            WireView verify(reinterpret_cast<const uint8_t*>(out_rs.data()), out_rs.size());
            LP_CHECK(verify.repeated_count(1) == 2);
            LP_CHECK(verify.get_varint(3) == 99);
            // Verify wire starts with 0x08, 0x01, 0x08, 0x02
            LP_CHECK(out_rs.size() >= 6);
            LP_CHECK(static_cast<uint8_t>(out_rs[0]) == 0x08 && static_cast<uint8_t>(out_rs[1]) == 0x01);
            LP_CHECK(static_cast<uint8_t>(out_rs[2]) == 0x08 && static_cast<uint8_t>(out_rs[3]) == 0x02);
        }

        // (e) OrderEnvelopeView multi-round wrap reuse across different inputs & A5 parity
        {
            OrderEnvelopeView reused;
            std::vector<uint8_t> alt = create_complex_order_bytes(60, 128);
            for (int r = 0; r < 3; r++) {
                reused.wrap(raw.data(), 0, static_cast<uint32_t>(raw.size()));
                reused.items(42).set_quantity(999).set_description("UPDATED_ITEM_42_DESC");
                std::string m;
                reused.merge_to(m);
                pb::OrderEnvelope p;
                LP_CHECK(p.ParseFromArray(m.data(), static_cast<int>(m.size())));
                LP_CHECK(p.items_size() == 100);
                LP_CHECK(p.items(42).quantity() == 999);
                LP_CHECK(p.items(42).description() == "UPDATED_ITEM_42_DESC");

                reused.wrap(alt.data(), 0, static_cast<uint32_t>(alt.size()));
                LP_CHECK(reused.items_count() == 60);
                LP_CHECK(reused.items(42).quantity() == (42 % 5) + 1);
            }
        }

        // Issue #8 (f): sub.wrap() / repeated_sub.wrap() without calling any child getter
        {
            const uint8_t p_in[] = {0x2A, 0x02, 0x08, 0x01};
            WireView wv(p_in, sizeof(p_in));
            const uint8_t u2[] = {0x08, 0x02};
            wv.sub(5).wrap(u2, sizeof(u2));
            std::string out_u;
            wv.merge_to(out_u);
            LP_CHECK(out_u.size() == 4);
            LP_CHECK(static_cast<uint8_t>(out_u[0]) == 0x2A && static_cast<uint8_t>(out_u[1]) == 0x02
                  && static_cast<uint8_t>(out_u[2]) == 0x08 && static_cast<uint8_t>(out_u[3]) == 0x02);
            pb::OrderEnvelope pb_u;
            LP_CHECK(pb_u.ParseFromArray(out_u.data(), static_cast<int>(out_u.size())));
            LP_CHECK(pb_u.user().user_id() == 2);

            const uint8_t rep_in[] = {0x32, 0x02, 0x18, 0x01};
            WireView r_wv(rep_in, sizeof(rep_in));
            const uint8_t q9[] = {0x18, 0x09};
            r_wv.repeated_sub(6, 0).wrap(q9, sizeof(q9));
            std::string out_r;
            r_wv.merge_to(out_r);
            LP_CHECK(out_r.size() == 4);
            LP_CHECK(static_cast<uint8_t>(out_r[0]) == 0x32 && static_cast<uint8_t>(out_r[1]) == 0x02
                  && static_cast<uint8_t>(out_r[2]) == 0x18 && static_cast<uint8_t>(out_r[3]) == 0x09);
            pb::OrderEnvelope pb_r;
            LP_CHECK(pb_r.ParseFromArray(out_r.data(), static_cast<int>(out_r.size())));
            LP_CHECK(pb_r.items_size() == 1 && pb_r.items(0).quantity() == 9);
        }
    }

    // 7. B1: From-Scratch Construction
    {
        OrderEnvelopeView scratch;
        scratch.init_scratch();
        scratch.set_order_id(999888777ULL)
               .set_buyer_name("scratch_user")
               .set_status(4)
               .set_updated_at_fixed64(1234567890123ULL)
               .set_total_cents(88800ULL);
        auto& u = scratch.mutable_user();
        u.set_user_id(555ULL);
        u.set_city("HANGZHOU");
        u.set_vip_level(2);
        u.set_score(88.25);
        u.set_email("scratch@example.com");

        for (int i = 0; i < 10; i++) {
            auto& item = scratch.add_item();
            item.set_item_id(100ULL + i);
            item.set_sku("SKU-SCRATCH");
            item.set_quantity(2);
            item.set_price_cents(1500ULL);
            item.set_description("Item desc");
        }

        size_t len = scratch.merge_to(out_buf.data(), out_buf.size());
        pb::OrderEnvelope pb_decoded;
        LP_CHECK(pb_decoded.ParseFromArray(out_buf.data(), static_cast<int>(len)));
        LP_CHECK(pb_decoded.order_id() == 999888777ULL);
        LP_CHECK(pb_decoded.user().city() == "HANGZHOU");
        LP_CHECK(pb_decoded.items_size() == 10);
    }

    // 8. Lower-Level Bug Regressions (Multi-Entry FP1 Shadow, repeated_sub Counter Leak, Child Detach, read_item_eager)
    {
        // Bug 1a: Split submessage (2a 02 08 01 2a 02 18 02) + same-size set_bytes(5, {0x08, 0x09})
        {
            const uint8_t split_user[] = {0x2A, 0x02, 0x08, 0x01, 0x2A, 0x02, 0x18, 0x02};
            WireView wv(split_user, sizeof(split_user));
            const char repl[] = {'\x08', '\x09'};
            wv.set_bytes(5, std::string_view(repl, 2));
            LP_CHECK(!wv.is_same_size_dirty_only());
            std::string out;
            wv.merge_to(out);
            LP_CHECK(out.size() == 4);
            LP_CHECK(static_cast<uint8_t>(out[0]) == 0x2A && static_cast<uint8_t>(out[1]) == 0x02
                  && static_cast<uint8_t>(out[2]) == 0x08 && static_cast<uint8_t>(out[3]) == 0x09);
            pb::OrderEnvelope dec;
            LP_CHECK(dec.ParseFromArray(out.data(), static_cast<int>(out.size())));
            LP_CHECK(dec.user().user_id() == 9);
            LP_CHECK(dec.user().vip_level() == 0);
        }

        // Bug 1b: Repeated field (32 02 18 01 32 02 18 02) + same-size set_bytes(6, {0x18, 0x09})
        {
            const uint8_t two_items[] = {0x32, 0x02, 0x18, 0x01, 0x32, 0x02, 0x18, 0x02};
            WireView wv(two_items, sizeof(two_items));
            const char repl[] = {'\x18', '\x09'};
            wv.set_bytes(6, std::string_view(repl, 2));
            LP_CHECK(!wv.is_same_size_dirty_only());
            std::string out;
            wv.merge_to(out);
            LP_CHECK(out.size() == 4);
            LP_CHECK(static_cast<uint8_t>(out[0]) == 0x32 && static_cast<uint8_t>(out[1]) == 0x02
                  && static_cast<uint8_t>(out[2]) == 0x18 && static_cast<uint8_t>(out[3]) == 0x09);
            pb::OrderEnvelope dec;
            LP_CHECK(dec.ParseFromArray(out.data(), static_cast<int>(out.size())));
            LP_CHECK(dec.items_size() == 1 && dec.items(0).quantity() == 9);
        }

        // Bug 1c: Duplicate scalar (08 01 08 02) + same-size set_varint(1, 5)
        {
            const uint8_t dup_scalar[] = {0x08, 0x01, 0x08, 0x02};
            WireView wv(dup_scalar, sizeof(dup_scalar));
            wv.set_varint(1, 5);
            LP_CHECK(!wv.is_same_size_dirty_only());
            std::string out;
            wv.merge_to(out);
            LP_CHECK(out.size() == 2 && static_cast<uint8_t>(out[0]) == 0x08 && static_cast<uint8_t>(out[1]) == 0x05);
        }

        // Bug 2: repeated_sub mutate -> set_bytes / set_varint / erase dirty-counter leak check
        {
            const uint8_t one_item[] = {0x32, 0x02, 0x18, 0x01};
            WireView wv(one_item, sizeof(one_item));
            wv.repeated_sub(6, 0).set_int32(3, 500); // var_size_dirty_count_ = 1
            LP_CHECK(!wv.is_same_size_dirty_only());
            const char same_repl[] = {'\x18', '\x09'};
            wv.set_bytes(6, std::string_view(same_repl, 2)); // must decrement leaked var_size_dirty_count_!
            LP_CHECK(wv.is_same_size_dirty_only());
            std::string out1;
            wv.merge_to(out1);
            LP_CHECK(out1.size() == 4
                  && static_cast<uint8_t>(out1[0]) == 0x32 && static_cast<uint8_t>(out1[1]) == 0x02
                  && static_cast<uint8_t>(out1[2]) == 0x18 && static_cast<uint8_t>(out1[3]) == 0x09);

            const uint8_t two_items[] = {0x32, 0x02, 0x18, 0x01, 0x32, 0x02, 0x18, 0x02};
            wv.wrap(two_items, sizeof(two_items));
            wv.repeated_sub(6, 0).set_int32(3, 5);
            wv.repeated_sub(6, 1).set_int32(3, 500);
            wv.set_bytes(6, std::string_view(same_repl, 2));
            LP_CHECK(!wv.is_same_size_dirty_only());
            std::string out2;
            wv.merge_to(out2);
            LP_CHECK(out2.size() == 4 && static_cast<uint8_t>(out2[3]) == 0x09);
            wv.erase(6);
            std::string out_empty;
            wv.merge_to(out_empty);
            LP_CHECK(out_empty.empty());

            const uint8_t item_and_order[] = {0x08, 0x01, 0x32, 0x02, 0x18, 0x01};
            wv.wrap(item_and_order, sizeof(item_and_order));
            wv.repeated_sub(6, 0).set_int32(3, 5);
            LP_CHECK(wv.is_same_size_dirty_only());
            wv.erase(6);
            LP_CHECK(!wv.is_same_size_dirty_only());
            std::string out3;
            wv.merge_to(out3);
            LP_CHECK(out3.size() == 2 && static_cast<uint8_t>(out3[0]) == 0x08 && static_cast<uint8_t>(out3[1]) == 0x01);
        }

        // Bug 3: Stale child view detachment after wrap(), set_bytes(), erase()
        {
            const uint8_t msg1[] = {0x2A, 0x02, 0x08, 0x01, 0x32, 0x02, 0x18, 0x01};
            const uint8_t msg2[] = {0x2A, 0x02, 0x08, 0x02, 0x32, 0x02, 0x18, 0x02};
            WireView wv(msg1, sizeof(msg1));
            WireView& stale_sub = wv.sub(5);
            WireView& stale_rep = wv.repeated_sub(6, 0);
            WireView& stale_grand = stale_sub.sub(2);

            wv.wrap(msg2, sizeof(msg2));
            stale_sub.set_varint(1, 999);
            const uint8_t repl_u[] = {0x08, 0x77};
            stale_sub.wrap(repl_u, sizeof(repl_u));
            stale_rep.set_int32(3, 888);
            const uint8_t repl_i[] = {0x18, 0x66};
            stale_rep.wrap(repl_i, sizeof(repl_i));
            stale_grand.set_varint(1, 777);

            LP_CHECK(wv.is_clean());
            std::string out_clean;
            wv.merge_to(out_clean);
            LP_CHECK(out_clean.size() == sizeof(msg2) && std::memcmp(out_clean.data(), msg2, sizeof(msg2)) == 0);

            WireView& stale_sub2 = wv.sub(5);
            WireView& stale_rep2 = wv.repeated_sub(6, 0);
            const char new_u[] = {'\x08', '\x07'};
            wv.set_bytes(5, std::string_view(new_u, 2));
            wv.erase(6);
            stale_sub2.set_varint(1, 555);
            stale_rep2.set_int32(3, 444);

            std::string out_override;
            wv.merge_to(out_override);
            pb::OrderEnvelope dec;
            LP_CHECK(dec.ParseFromArray(out_override.data(), static_cast<int>(out_override.size())));
            LP_CHECK(dec.user().user_id() == 7);
            LP_CHECK(dec.items_size() == 0);
        }

        // Bug 4: OrderEnvelopeView.wrap() -> read_item_eager(0, out) directly without calling items_count() first
        {
            pb::OrderEnvelope src;
            src.set_order_id(77);
            auto* it0 = src.add_items();
            it0->set_item_id(101);
            it0->set_sku("SKU-DIRECT");
            it0->set_quantity(4);
            it0->set_price_cents(2500);
            it0->set_description("Direct eager read");
            src.add_items(); // empty second item
            std::string wire;
            LP_CHECK(src.SerializeToString(&wire));

            OrderEnvelopeView env;
            env.wrap(reinterpret_cast<const uint8_t*>(wire.data()), 0, static_cast<uint32_t>(wire.size()));
            LP_CHECK(!env.scanned_);
            LineItemView out_item;
            env.read_item_eager(0, out_item);
            LP_CHECK(env.scanned_);
            LP_CHECK(out_item.item_id_ == 101ULL);
            LP_CHECK(out_item.sku_ == "SKU-DIRECT");
            LP_CHECK(out_item.quantity_ == 4);
            LP_CHECK(out_item.price_cents_ == 2500ULL);
            LP_CHECK(out_item.description_ == "Direct eager read");

            // Reading empty item into reused out_item must reset previous values
            env.read_item_eager(1, out_item);
            LP_CHECK(out_item.item_id_ == 0ULL);
            LP_CHECK(out_item.sku_.empty());
            LP_CHECK(out_item.quantity_ == 0);
            LP_CHECK(out_item.price_cents_ == 0ULL);
            LP_CHECK(out_item.description_.empty());
        }
    }

    // 9. Stateful Random Differential Fuzzing for WireView & OrderEnvelopeView
    {
        auto assert_pb_equal = [](const pb::OrderEnvelope& expected, const pb::OrderEnvelope& actual) {
            LP_CHECK(expected.order_id() == actual.order_id());
            LP_CHECK(expected.buyer_name() == actual.buyer_name());
            LP_CHECK(expected.status() == actual.status());
            LP_CHECK(expected.updated_at_fixed64() == actual.updated_at_fixed64());
            LP_CHECK(expected.has_user() == actual.has_user());
            if (expected.has_user()) {
                LP_CHECK(expected.user().user_id() == actual.user().user_id());
                LP_CHECK(expected.user().city() == actual.user().city());
                LP_CHECK(expected.user().vip_level() == actual.user().vip_level());
                uint64_t eb = 0, ab = 0;
                double es = expected.user().score(), as = actual.user().score();
                std::memcpy(&eb, &es, 8);
                std::memcpy(&ab, &as, 8);
                LP_CHECK(eb == ab);
                LP_CHECK(expected.user().email() == actual.user().email());
            }
            LP_CHECK(expected.items_size() == actual.items_size());
            for (int i = 0; i < expected.items_size(); i++) {
                const auto& ei = expected.items(i);
                const auto& ai = actual.items(i);
                LP_CHECK(ei.item_id() == ai.item_id());
                LP_CHECK(ei.sku() == ai.sku());
                LP_CHECK(ei.quantity() == ai.quantity());
                LP_CHECK(ei.price_cents() == ai.price_cents());
                LP_CHECK(ei.description() == ai.description());
            }
            LP_CHECK(expected.payload() == actual.payload());
            LP_CHECK(expected.auth_token() == actual.auth_token());
            LP_CHECK(expected.trace_id() == actual.trace_id());
            LP_CHECK(expected.total_cents() == actual.total_cents());
            const auto& eu = expected.GetReflection()->GetUnknownFields(expected);
            const auto& au = actual.GetReflection()->GetUnknownFields(actual);
            LP_CHECK(eu.field_count() == au.field_count());
        };

        const std::vector<std::string> sample_strings = {
            "", "A", "SH", "BJ", "NYC", "SKU-1", "Shanghai", "SanFrancisco-CA",
            "Hangzhou-UTF8-Test", std::string(140, 'x')
        };
        const uint64_t sample_varints[] = {
            0ULL, 1ULL, 2ULL, 7ULL, 127ULL, 128ULL, 255ULL, 16383ULL, 16384ULL,
            (1ULL << 21) + 5ULL, (1ULL << 35) + 11ULL, 0x7FFFFFFFFFFFFFFFULL
        };
        constexpr size_t num_varints = sizeof(sample_varints) / sizeof(sample_varints[0]);

        auto make_random_wire = [&](std::mt19937_64& rng, bool allow_large) -> std::string {
            uint64_t mode = rng() % 10;
            if (mode == 0) return {};
            pb::OrderEnvelope msg;
            if (rng() & 1) msg.set_order_id(static_cast<int64_t>(100 + (rng() % 50000)));
            if (rng() & 1) msg.set_buyer_name(sample_strings[1 + (rng() % (sample_strings.size() - 1))]);
            if (rng() & 1) msg.set_status(static_cast<int32_t>(1 + (rng() % 120)));
            if (rng() & 1) msg.set_updated_at_fixed64(0x1122334455667788ULL ^ rng());
            if (rng() & 1) {
                auto* u = msg.mutable_user();
                u->set_user_id(static_cast<int64_t>(10 + (rng() % 5000)));
                u->set_city(sample_strings[1 + (rng() % 5)]);
                u->set_vip_level(static_cast<int32_t>(1 + (rng() % 9)));
                u->set_score(12.5 + static_cast<double>(rng() % 50));
                u->set_email("u" + std::to_string(rng() % 100) + "@test.io");
            }
            size_t icnt = (allow_large && mode == 9) ? (135 + (rng() % 20)) : (rng() % 6);
            for (size_t i = 0; i < icnt; i++) {
                auto* it = msg.add_items();
                it->set_item_id(static_cast<int64_t>(1000 + i));
                it->set_sku("SKU-" + std::to_string(i));
                it->set_quantity(static_cast<int32_t>(1 + (rng() % 100)));
                it->set_price_cents(static_cast<int64_t>(500 + (rng() % 9000)));
                it->set_description("Desc-" + std::to_string(i));
            }
            if (rng() & 1) {
                std::string pl(1 + (rng() % 24), '\0');
                for (char& c : pl) c = static_cast<char>(rng() & 0xFF);
                msg.set_payload(pl);
            }
            if (rng() & 1) msg.set_auth_token("tok-" + std::to_string(rng() % 9999));
            if (rng() & 1) msg.set_trace_id("tr-" + std::to_string(rng() % 9999));
            if (rng() & 1) msg.set_total_cents(static_cast<int64_t>(1000 + (rng() % 100000)));
            if (rng() & 1) {
                auto* uf = msg.GetReflection()->MutableUnknownFields(&msg);
                uf->AddVarint(50, 123456789ULL + (rng() % 1000));
                uf->AddLengthDelimited(51, "unk-" + std::to_string(rng() % 100));
            }
            std::string base;
            LP_CHECK(msg.SerializeToString(&base));
            if (mode == 1 || mode == 2) {
                pb::UserProfile s1, s2;
                s1.set_user_id(static_cast<int64_t>(11 + (rng() % 100)));
                s1.set_city("SH");
                s2.set_vip_level(static_cast<int32_t>(1 + (rng() % 9)));
                s2.set_email("split@io");
                std::string b1 = s1.SerializeAsString();
                std::string b2 = s2.SerializeAsString();
                std::string res;
                res.resize(base.size() + b1.size() + b2.size() + 32);
                WireWriter w(reinterpret_cast<uint8_t*>(res.data()));
                w.write_len_delimited(5, b1);
                w.write_raw(reinterpret_cast<const uint8_t*>(base.data()), static_cast<uint32_t>(base.size()));
                w.write_len_delimited(5, b2);
                res.resize(w.size());
                return res;
            }
            return base;
        };

        // 9a. OrderEnvelopeView stateful differential fuzzer (80 seeds x 4 rounds x 25 steps)
        {
            OrderEnvelopeView env;
            LineItemView eager_tmp;
            for (uint64_t seed = 1; seed <= 80; seed++) {
                std::mt19937_64 rng(seed * 0x517CC1B727220A95ULL);
                for (int round = 0; round < 4; round++) {
                    std::deque<std::string> owned_strings;
                    auto pin = [&](std::string s) -> std::string_view {
                        owned_strings.push_back(std::move(s));
                        return owned_strings.back();
                    };

                    bool scratch_init = (round == 3 && (rng() % 4) == 0);
                    std::string init_wire;
                    pb::OrderEnvelope oracle;
                    if (scratch_init) {
                        env.init_scratch();
                    } else {
                        init_wire = make_random_wire(rng, true);
                        env.wrap(reinterpret_cast<const uint8_t*>(init_wire.data()), 0, static_cast<uint32_t>(init_wire.size()));
                        LP_CHECK(oracle.ParseFromArray(init_wire.data(), static_cast<int>(init_wire.size())));
                    }

                    if (!scratch_init && oracle.items_size() > 0 && (rng() & 1)) {
                        uint32_t idx = static_cast<uint32_t>(rng() % static_cast<uint64_t>(oracle.items_size()));
                        env.read_item_eager(idx, eager_tmp);
                        const auto& exp = oracle.items(static_cast<int>(idx));
                        LP_CHECK(eager_tmp.item_id_ == static_cast<uint64_t>(exp.item_id()));
                        LP_CHECK(eager_tmp.sku_ == exp.sku());
                        LP_CHECK(eager_tmp.quantity_ == exp.quantity());
                        LP_CHECK(eager_tmp.price_cents_ == static_cast<uint64_t>(exp.price_cents()));
                        LP_CHECK(eager_tmp.description_ == exp.description());
                    }

                    bool do_gets = (rng() & 1) != 0;
                    for (int step = 0; step < 25; step++) {
                        uint64_t op = rng() % 15;
                        switch (op) {
                            case 0: {
                                uint64_t v = sample_varints[rng() % num_varints];
                                env.set_order_id(v);
                                oracle.set_order_id(static_cast<int64_t>(v));
                                break;
                            }
                            case 1: {
                                std::string_view sv = pin(sample_strings[rng() % sample_strings.size()]);
                                env.set_buyer_name(sv);
                                oracle.set_buyer_name(sv.data(), sv.size());
                                break;
                            }
                            case 2: {
                                int32_t st = (rng() & 1) ? static_cast<int32_t>(1 + (rng() % 126))
                                                         : static_cast<int32_t>(128 + (rng() % 50000));
                                env.set_status(st);
                                oracle.set_status(st);
                                break;
                            }
                            case 3: {
                                uint64_t f = rng();
                                env.set_updated_at_fixed64(f);
                                oracle.set_updated_at_fixed64(f);
                                break;
                            }
                            case 4: {
                                std::string pl(rng() % 32, '\0');
                                for (char& c : pl) c = static_cast<char>(rng() & 0xFF);
                                std::string_view sv = pin(std::move(pl));
                                env.set_payload(sv);
                                oracle.set_payload(sv.data(), sv.size());
                                break;
                            }
                            case 5: {
                                std::string_view sv = pin(sample_strings[rng() % sample_strings.size()]);
                                env.set_auth_token(sv);
                                oracle.set_auth_token(sv.data(), sv.size());
                                break;
                            }
                            case 6: {
                                env.clear_auth_token();
                                oracle.clear_auth_token();
                                break;
                            }
                            case 7: {
                                std::string_view sv = pin(sample_strings[rng() % sample_strings.size()]);
                                env.set_trace_id(sv);
                                oracle.set_trace_id(sv.data(), sv.size());
                                break;
                            }
                            case 8: {
                                uint64_t v = sample_varints[rng() % num_varints];
                                env.set_total_cents(v);
                                oracle.set_total_cents(static_cast<int64_t>(v));
                                break;
                            }
                            case 9: {
                                auto& u = env.mutable_user();
                                auto* ou = oracle.mutable_user();
                                switch (rng() % 5) {
                                    case 0: { uint64_t v = sample_varints[rng() % num_varints]; u.set_user_id(v); ou->set_user_id(static_cast<int64_t>(v)); break; }
                                    case 1: { std::string_view sv = pin(sample_strings[rng() % sample_strings.size()]); u.set_city(sv); ou->set_city(sv.data(), sv.size()); break; }
                                    case 2: { int32_t v = static_cast<int32_t>(1 + (rng() % 500)); u.set_vip_level(v); ou->set_vip_level(v); break; }
                                    case 3: { double sc = static_cast<double>(rng() % 1000) * 0.25; u.set_score(sc); ou->set_score(sc); break; }
                                    case 4: { std::string_view sv = pin(sample_strings[rng() % sample_strings.size()]); u.set_email(sv); ou->set_email(sv.data(), sv.size()); break; }
                                }
                                break;
                            }
                            case 10:
                            case 11: {
                                if (oracle.items_size() > 0) {
                                    uint32_t idx = static_cast<uint32_t>(rng() % static_cast<uint64_t>(oracle.items_size()));
                                    auto& it = env.items(idx);
                                    auto* oit = oracle.mutable_items(static_cast<int>(idx));
                                    switch (rng() % 5) {
                                        case 0: { uint64_t v = sample_varints[rng() % num_varints]; it.set_item_id(v); oit->set_item_id(static_cast<int64_t>(v)); break; }
                                        case 1: { std::string_view sv = pin(sample_strings[rng() % sample_strings.size()]); it.set_sku(sv); oit->set_sku(sv.data(), sv.size()); break; }
                                        case 2: { int32_t q = static_cast<int32_t>(1 + (rng() % 500)); it.set_quantity(q); oit->set_quantity(q); break; }
                                        case 3: { uint64_t p = sample_varints[rng() % num_varints]; it.set_price_cents(p); oit->set_price_cents(static_cast<int64_t>(p)); break; }
                                        case 4: { std::string_view sv = pin(sample_strings[rng() % sample_strings.size()]); it.set_description(sv); oit->set_description(sv.data(), sv.size()); break; }
                                    }
                                }
                                break;
                            }
                            case 12: {
                                if (oracle.items_size() < 165) {
                                    auto& it = env.add_item();
                                    auto* oit = oracle.add_items();
                                    uint64_t id = 5000ULL + static_cast<uint64_t>(step);
                                    std::string_view sku = pin("APP-" + std::to_string(rng() % 1000));
                                    int32_t q = static_cast<int32_t>(1 + (rng() % 50));
                                    it.set_item_id(id).set_sku(sku).set_quantity(q);
                                    oit->set_item_id(static_cast<int64_t>(id));
                                    oit->set_sku(sku.data(), sku.size());
                                    oit->set_quantity(q);
                                }
                                break;
                            }
                            case 13: {
                                if (do_gets) {
                                    LP_CHECK(env.order_id() == static_cast<uint64_t>(oracle.order_id()));
                                    LP_CHECK(env.buyer_name() == oracle.buyer_name());
                                    LP_CHECK(env.status() == oracle.status());
                                    LP_CHECK(env.updated_at_fixed64() == oracle.updated_at_fixed64());
                                    LP_CHECK(env.auth_token() == oracle.auth_token());
                                    LP_CHECK(env.trace_id() == oracle.trace_id());
                                    LP_CHECK(env.total_cents() == static_cast<uint64_t>(oracle.total_cents()));
                                    LP_CHECK(env.items_count() == static_cast<uint32_t>(oracle.items_size()));
                                }
                                break;
                            }
                            case 14: {
                                size_t max_sz = env.max_merged_size();
                                std::vector<uint8_t> exact(max_sz);
                                size_t actual = env.merge_to(exact.data(), exact.size());
                                LP_CHECK(actual <= max_sz);
                                pb::OrderEnvelope dec;
                                LP_CHECK(dec.ParseFromArray(exact.data(), static_cast<int>(actual)));
                                assert_pb_equal(oracle, dec);
                                break;
                            }
                        }
                    }

                    size_t max_sz = env.max_merged_size();
                    std::vector<uint8_t> exact(max_sz);
                    size_t actual = env.merge_to(exact.data(), exact.size());
                    LP_CHECK(actual <= max_sz);
                    std::string merged_str;
                    env.merge_to(merged_str);
                    LP_CHECK(merged_str.size() == actual);
                    if (actual > 0) LP_CHECK(std::memcmp(exact.data(), merged_str.data(), actual) == 0);
                    pb::OrderEnvelope dec;
                    LP_CHECK(dec.ParseFromArray(exact.data(), static_cast<int>(actual)));
                    assert_pb_equal(oracle, dec);
                }
            }
        }

        // 9b. WireView stateful differential fuzzer (80 seeds x 4 rounds x 28 steps)
        {
            WireView wv;
            for (uint64_t seed = 1; seed <= 80; seed++) {
                std::mt19937_64 rng(seed * 0x9E3779B97F4A7C15ULL);
                // Keep all round input/slice buffers alive across rounds so mutating stale child
                // views from round r-1 after wrap() still has valid backing memory.
                std::deque<std::string> owned_bufs;
                auto pin = [&](std::string s) -> std::string_view {
                    owned_bufs.push_back(std::move(s));
                    return owned_bufs.back();
                };
                WireView* stale_sub_prev = nullptr;
                WireView* stale_rep_prev = nullptr;
                for (int round = 0; round < 4; round++) {
                    std::string_view init_wire = pin(make_random_wire(rng, false));
                    wv.wrap(reinterpret_cast<const uint8_t*>(init_wire.data()), init_wire.size());
                    pb::OrderEnvelope oracle;
                    LP_CHECK(oracle.ParseFromArray(init_wire.data(), static_cast<int>(init_wire.size())));

                    // Mutate stale child pointers from previous round (must be detached!)
                    if (stale_sub_prev != nullptr) {
                        stale_sub_prev->set_varint(1, 999999ULL);
                        stale_sub_prev->set_bytes_copy(2, "STALE_CORRUPT");
                    }
                    if (stale_rep_prev != nullptr) {
                        stale_rep_prev->set_int32(3, 888888);
                    }
                    LP_CHECK(wv.is_clean());

                    bool field6_overridden = false;
                    bool do_gets = (rng() & 1) != 0;
                    WireView* held_stale_sub = nullptr;
                    WireView* held_stale_rep = nullptr;

                    for (int step = 0; step < 28; step++) {
                        uint64_t op = rng() % 18;
                        switch (op) {
                            case 0: {
                                uint64_t v = sample_varints[rng() % num_varints];
                                wv.set_varint(1, v);
                                oracle.set_order_id(static_cast<int64_t>(v));
                                break;
                            }
                            case 1: {
                                std::string s = sample_strings[rng() % sample_strings.size()];
                                if (rng() & 1) wv.set_bytes_copy(2, s);
                                else wv.set_bytes(2, pin(s));
                                oracle.set_buyer_name(s);
                                break;
                            }
                            case 2: {
                                int32_t st = (rng() & 1) ? static_cast<int32_t>(1 + (rng() % 126))
                                                         : static_cast<int32_t>(128 + (rng() % 50000));
                                wv.set_int32(3, st);
                                oracle.set_status(st);
                                break;
                            }
                            case 3: {
                                uint64_t f = rng();
                                wv.set_fixed64(4, f);
                                oracle.set_updated_at_fixed64(f);
                                break;
                            }
                            case 4: {
                                WireView& sub = wv.sub(5);
                                stale_sub_prev = &sub;
                                switch (rng() % 6) {
                                    case 0: { uint64_t v = sample_varints[rng() % num_varints]; sub.set_varint(1, v); oracle.mutable_user()->set_user_id(static_cast<int64_t>(v)); break; }
                                    case 1: { std::string c = sample_strings[rng() % sample_strings.size()]; sub.set_bytes_copy(2, c); oracle.mutable_user()->set_city(c); break; }
                                    case 2: { int32_t v = (rng() & 1) ? static_cast<int32_t>(1 + (rng() % 126)) : static_cast<int32_t>(128 + (rng() % 500)); sub.set_int32(3, v); oracle.mutable_user()->set_vip_level(v); break; }
                                    case 3: { double sc = static_cast<double>(rng() % 1000) * 0.5; sub.set_double(4, sc); oracle.mutable_user()->set_score(sc); break; }
                                    case 4: { std::string e = sample_strings[rng() % sample_strings.size()]; sub.set_bytes_copy(5, e); oracle.mutable_user()->set_email(e); break; }
                                    case 5: {
                                        if (oracle.has_user()) {
                                            sub.erase(2);
                                            oracle.mutable_user()->clear_city();
                                        }
                                        break;
                                    }
                                }
                                break;
                            }
                            case 5: {
                                pb::UserProfile nu;
                                nu.set_user_id(static_cast<int64_t>(1 + (rng() % 120)));
                                nu.set_city((rng() & 1) ? "SH" : sample_strings[rng() % sample_strings.size()]);
                                nu.set_vip_level(static_cast<int32_t>(1 + (rng() % 9)));
                                std::string_view ub = pin(nu.SerializeAsString());
                                WireView& sub = wv.sub(5);
                                stale_sub_prev = &sub;
                                sub.wrap(reinterpret_cast<const uint8_t*>(ub.data()), ub.size());
                                *oracle.mutable_user() = nu;
                                break;
                            }
                            case 6: {
                                held_stale_sub = &wv.sub(5);
                                pb::UserProfile nu;
                                nu.set_user_id(static_cast<int64_t>(1 + (rng() % 120)));
                                if (rng() & 1) nu.set_city("C" + std::to_string(rng() % 99));
                                std::string ub = nu.SerializeAsString();
                                wv.set_bytes_copy(5, ub);
                                *oracle.mutable_user() = nu;
                                break;
                            }
                            case 7:
                            case 8: {
                                if (!field6_overridden && oracle.items_size() > 0) {
                                    size_t cnt = wv.repeated_count(6);
                                    LP_CHECK(cnt == static_cast<size_t>(oracle.items_size()));
                                    size_t idx = rng() % cnt;
                                    WireView& rv = wv.repeated_sub(6, idx);
                                    stale_rep_prev = &rv;
                                    auto* oit = oracle.mutable_items(static_cast<int>(idx));
                                    switch (rng() % 6) {
                                        case 0: { uint64_t v = sample_varints[rng() % num_varints]; rv.set_varint(1, v); oit->set_item_id(static_cast<int64_t>(v)); break; }
                                        case 1: { std::string s = sample_strings[rng() % sample_strings.size()]; rv.set_bytes_copy(2, s); oit->set_sku(s); break; }
                                        case 2: { int32_t q = (rng() & 1) ? static_cast<int32_t>(1 + (rng() % 126)) : static_cast<int32_t>(128 + (rng() % 500)); rv.set_int32(3, q); oit->set_quantity(q); break; }
                                        case 3: { uint64_t p = sample_varints[rng() % num_varints]; rv.set_varint(4, p); oit->set_price_cents(static_cast<int64_t>(p)); break; }
                                        case 4: { std::string d = sample_strings[rng() % sample_strings.size()]; rv.set_bytes_copy(5, d); oit->set_description(d); break; }
                                        case 5: { rv.erase(5); oit->clear_description(); break; }
                                    }
                                }
                                break;
                            }
                            case 9: {
                                if (!field6_overridden && oracle.items_size() > 0) {
                                    size_t idx = rng() % static_cast<size_t>(oracle.items_size());
                                    pb::LineItem nit;
                                    nit.set_item_id(static_cast<int64_t>(1 + (rng() % 100)));
                                    nit.set_quantity(static_cast<int32_t>(1 + (rng() % 120)));
                                    nit.set_sku("W-" + std::to_string(rng() % 99));
                                    std::string_view ib = pin(nit.SerializeAsString());
                                    WireView& rv = wv.repeated_sub(6, idx);
                                    stale_rep_prev = &rv;
                                    rv.wrap(reinterpret_cast<const uint8_t*>(ib.data()), ib.size());
                                    *oracle.mutable_items(static_cast<int>(idx)) = nit;
                                }
                                break;
                            }
                            case 10: {
                                if (!field6_overridden && oracle.items_size() > 0) {
                                    held_stale_rep = &wv.repeated_sub(6, 0);
                                }
                                pb::LineItem sit;
                                sit.set_quantity(static_cast<int32_t>(1 + (rng() % 126)));
                                if (rng() & 1) sit.set_sku("S" + std::to_string(rng() % 10));
                                std::string ib = sit.SerializeAsString();
                                wv.set_bytes_copy(6, ib);
                                oracle.clear_items();
                                *oracle.add_items() = sit;
                                field6_overridden = true;
                                break;
                            }
                            case 11: {
                                switch (rng() % 4) {
                                    case 0: {
                                        std::string pl(rng() % 24, '\0');
                                        for (char& c : pl) c = static_cast<char>(rng() & 0xFF);
                                        wv.set_bytes_copy(7, pl);
                                        oracle.set_payload(pl);
                                        break;
                                    }
                                    case 1: { std::string s = sample_strings[rng() % sample_strings.size()]; wv.set_bytes_copy(8, s); oracle.set_auth_token(s); break; }
                                    case 2: { std::string s = sample_strings[rng() % sample_strings.size()]; wv.set_bytes_copy(9, s); oracle.set_trace_id(s); break; }
                                    case 3: { uint64_t v = sample_varints[rng() % num_varints]; wv.set_varint(10, v); oracle.set_total_cents(static_cast<int64_t>(v)); break; }
                                }
                                break;
                            }
                            case 12: {
                                uint32_t target = static_cast<uint32_t>(1 + (rng() % 10));
                                if (target == 5) held_stale_sub = &wv.sub(5);
                                if (target == 6 && !field6_overridden && oracle.items_size() > 0) {
                                    held_stale_rep = &wv.repeated_sub(6, 0);
                                }
                                wv.erase(target);
                                switch (target) {
                                    case 1: oracle.clear_order_id(); break;
                                    case 2: oracle.clear_buyer_name(); break;
                                    case 3: oracle.clear_status(); break;
                                    case 4: oracle.clear_updated_at_fixed64(); break;
                                    case 5: oracle.clear_user(); break;
                                    case 6: oracle.clear_items(); field6_overridden = true; break;
                                    case 7: oracle.clear_payload(); break;
                                    case 8: oracle.clear_auth_token(); break;
                                    case 9: oracle.clear_trace_id(); break;
                                    case 10: oracle.clear_total_cents(); break;
                                }
                                break;
                            }
                            case 13: {
                                if (held_stale_sub != nullptr) {
                                    held_stale_sub->set_varint(1, 777777ULL);
                                    held_stale_sub->set_bytes_copy(2, "DETACHED_SUB");
                                }
                                if (held_stale_rep != nullptr) {
                                    held_stale_rep->set_int32(3, 666666);
                                }
                                break;
                            }
                            case 14: {
                                if (do_gets) {
                                    LP_CHECK(wv.get_varint(1) == static_cast<uint64_t>(oracle.order_id()));
                                    LP_CHECK(wv.get_bytes(2) == oracle.buyer_name());
                                    LP_CHECK(wv.get_int32(3) == oracle.status());
                                    LP_CHECK(wv.get_fixed64(4) == oracle.updated_at_fixed64());
                                    LP_CHECK(wv.get_bytes(8) == oracle.auth_token());
                                    LP_CHECK(wv.get_bytes(9) == oracle.trace_id());
                                    LP_CHECK(wv.get_varint(10) == static_cast<uint64_t>(oracle.total_cents()));
                                    if (oracle.has_user()) {
                                        LP_CHECK(wv.sub(5).get_varint(1) == static_cast<uint64_t>(oracle.user().user_id()));
                                        LP_CHECK(wv.sub(5).get_bytes(2) == oracle.user().city());
                                    }
                                }
                                break;
                            }
                            case 15:
                            case 16:
                            case 17: {
                                size_t max_sz = wv.max_merged_size();
                                std::vector<uint8_t> exact(max_sz);
                                size_t actual = wv.merge_to(exact.data(), exact.size());
                                LP_CHECK(actual <= max_sz);
                                pb::OrderEnvelope dec;
                                LP_CHECK(dec.ParseFromArray(exact.data(), static_cast<int>(actual)));
                                assert_pb_equal(oracle, dec);
                                break;
                            }
                        }
                    }

                    size_t max_sz = wv.max_merged_size();
                    std::vector<uint8_t> exact(max_sz);
                    size_t actual = wv.merge_to(exact.data(), exact.size());
                    LP_CHECK(actual <= max_sz);
                    std::string merged_str;
                    wv.merge_to(merged_str);
                    LP_CHECK(merged_str.size() == actual);
                    if (actual > 0) LP_CHECK(std::memcmp(exact.data(), merged_str.data(), actual) == 0);
                    pb::OrderEnvelope dec;
                    LP_CHECK(dec.ParseFromArray(exact.data(), static_cast<int>(actual)));
                    assert_pb_equal(oracle, dec);
                }
            }
        }
    }

    std::printf("[Correctness] All C++20 Lower-Level vs Official libprotobuf checks PASSED.\n\n");
}

// ============================================================================
// Benchmark Harness (Comparable Lifecycle & Symmetric Output Keep-Alive)
// ============================================================================
struct BenchMetric {
    double ns;
    uint64_t alloc_bytes;
};

struct FullBenchRow {
    const char* category;
    std::string name;
    BenchMetric pb_fresh_alloc;    // New pb::OrderEnvelope + new std::string
    BenchMetric pb_reuse_buf;      // Reused pb::OrderEnvelope + reused out_buf
    BenchMetric hand_eager;        // Hand-written StdOrderEnvelope + reused out_buf
    BenchMetric lazy_fresh_alloc;  // New OrderEnvelopeView per iter + new std::string
    BenchMetric lazy_wrap_alloc;   // Reused OrderEnvelopeView.wrap + new std::string
    BenchMetric lazy_reuse_buf;    // Reused OrderEnvelopeView.wrap + reused out_buf
};

static volatile uint64_t g_blackhole = 0;

template <typename Fn>
BenchMetric measure_bench(int warmup, int iters, Fn&& fn) {
    uint64_t sink = 0;
    for (int i = 0; i < warmup; i++) {
        sink ^= fn(i);
    }
    g_blackhole ^= sink;

    g_thread_alloc_bytes = 0;
    auto t0 = std::chrono::steady_clock::now();
    for (int i = 0; i < iters; i++) {
        sink ^= fn(i);
    }
    auto t1 = std::chrono::steady_clock::now();
    uint64_t total_alloc = g_thread_alloc_bytes;
    g_blackhole ^= sink;

    double ns = std::chrono::duration<double, std::nano>(t1 - t0).count() / iters;
    return {ns, total_alloc / static_cast<uint64_t>(iters)};
}

int main(int argc, char** argv) {
    GOOGLE_PROTOBUF_VERIFY_VERSION;
    bool test_only = false;
    for (int i = 1; i < argc; i++) {
        if (std::strcmp(argv[i], "--test-only") == 0) {
            test_only = true;
        }
    }
    run_correctness_tests();
    if (test_only) {
        google::protobuf::ShutdownProtobufLibrary();
        return 0;
    }

    std::vector<uint8_t> complex_bytes = create_complex_order_bytes(100, 4096);
    std::vector<uint8_t> medium_bytes  = create_complex_order_bytes(20, 256);

    pb::OrderEnvelope tiny_pb;
    tiny_pb.set_order_id(1001LL);
    tiny_pb.set_status(1);
    tiny_pb.set_total_cents(5000LL);
    std::string tiny_str;
    LP_CHECK(tiny_pb.SerializeToString(&tiny_str));
    std::vector<uint8_t> tiny_bytes(tiny_str.begin(), tiny_str.end());
    LP_CHECK(!tiny_bytes.empty());

    std::vector<uint8_t> out_buf(complex_bytes.size() + 8192);
    OrderEnvelopeView reusable_lazy;
    pb::OrderEnvelope reusable_pb;

    constexpr int WARMUP = 8000;
    constexpr int ITERS  = 30000;

    std::vector<FullBenchRow> rows;

    // A1: Partial Read Routing (Read 3 fields out of ~12.4 KB message, no serialization)
    {
        auto m_pb_fresh = measure_bench(WARMUP, ITERS, [&](int) {
            pb::OrderEnvelope msg;
            LP_CHECK(msg.ParseFromArray(complex_bytes.data(), static_cast<int>(complex_bytes.size())));
            return static_cast<uint64_t>(msg.order_id() + msg.status() + msg.user().vip_level());
        });
        auto m_pb_reuse = measure_bench(WARMUP, ITERS, [&](int) {
            LP_CHECK(reusable_pb.ParseFromArray(complex_bytes.data(), static_cast<int>(complex_bytes.size())));
            return static_cast<uint64_t>(reusable_pb.order_id() + reusable_pb.status() + reusable_pb.user().vip_level());
        });
        auto m_hand = measure_bench(WARMUP, ITERS, [&](int) {
            StdOrderEnvelope msg;
            msg.ParseFromArray(complex_bytes.data(), static_cast<uint32_t>(complex_bytes.size()));
            return msg.order_id + msg.status + msg.user.vip_level;
        });
        auto m_lazy_fresh = measure_bench(WARMUP, ITERS, [&](int) {
            OrderEnvelopeView msg;
            msg.wrap(complex_bytes.data(), 0, static_cast<uint32_t>(complex_bytes.size()));
            return msg.order_id() + msg.status() + msg.user().vip_level();
        });
        auto m_lazy_reuse = measure_bench(WARMUP, ITERS, [&](int) {
            reusable_lazy.wrap(complex_bytes.data(), 0, static_cast<uint32_t>(complex_bytes.size()));
            return reusable_lazy.order_id() + reusable_lazy.status() + reusable_lazy.user().vip_level();
        });
        rows.push_back({"Advantageous (A1)", "Partial Read (3 fields from 12.4KB)",
                        m_pb_fresh, m_pb_reuse, m_hand, m_lazy_fresh, m_lazy_reuse, m_lazy_reuse});
    }

    // A2: Inspect 2 fields + Unmodified Passthrough (Fast-Path 0)
    {
        auto m_pb_fresh = measure_bench(WARMUP, ITERS, [&](int) -> uint64_t {
            pb::OrderEnvelope msg;
            LP_CHECK(msg.ParseFromArray(complex_bytes.data(), static_cast<int>(complex_bytes.size())));
            if (msg.order_id() > 0 && msg.status() == 1) {
                std::string out;
                LP_CHECK(msg.SerializeToString(&out));
                return observe_output(out);
            }
            return 0;
        });
        auto m_pb_reuse = measure_bench(WARMUP, ITERS, [&](int) -> uint64_t {
            LP_CHECK(reusable_pb.ParseFromArray(complex_bytes.data(), static_cast<int>(complex_bytes.size())));
            if (reusable_pb.order_id() > 0 && reusable_pb.status() == 1) {
                int sz = static_cast<int>(reusable_pb.ByteSizeLong());
                LP_CHECK(reusable_pb.SerializeToArray(out_buf.data(), sz));
                return observe_output(out_buf.data(), static_cast<size_t>(sz));
            }
            return 0;
        });
        auto m_hand = measure_bench(WARMUP, ITERS, [&](int) -> uint64_t {
            StdOrderEnvelope msg;
            msg.ParseFromArray(complex_bytes.data(), static_cast<uint32_t>(complex_bytes.size()));
            if (msg.order_id > 0 && msg.status == 1) {
                size_t sz = msg.SerializeToArray(out_buf.data());
                return observe_output(out_buf.data(), sz);
            }
            return 0;
        });
        auto m_lazy_fresh = measure_bench(WARMUP, ITERS, [&](int) -> uint64_t {
            OrderEnvelopeView msg;
            msg.wrap(complex_bytes.data(), 0, static_cast<uint32_t>(complex_bytes.size()));
            if (msg.order_id() > 0 && msg.status() == 1) {
                std::string out;
                msg.merge_to(out);
                return observe_output(out);
            }
            return 0;
        });
        auto m_lazy_wrap_alloc = measure_bench(WARMUP, ITERS, [&](int) -> uint64_t {
            reusable_lazy.wrap(complex_bytes.data(), 0, static_cast<uint32_t>(complex_bytes.size()));
            if (reusable_lazy.order_id() > 0 && reusable_lazy.status() == 1) {
                std::string out;
                reusable_lazy.merge_to(out);
                return observe_output(out);
            }
            return 0;
        });
        auto m_lazy_reuse = measure_bench(WARMUP, ITERS, [&](int) -> uint64_t {
            reusable_lazy.wrap(complex_bytes.data(), 0, static_cast<uint32_t>(complex_bytes.size()));
            if (reusable_lazy.order_id() > 0 && reusable_lazy.status() == 1) {
                size_t sz = reusable_lazy.write_to_buffer(out_buf.data());
                return observe_output(out_buf.data(), sz);
            }
            return 0;
        });
        rows.push_back({"Advantageous (A2)", "Inspect 2 + Passthrough (Fast-Path 0)",
                        m_pb_fresh, m_pb_reuse, m_hand, m_lazy_fresh, m_lazy_wrap_alloc, m_lazy_reuse});
    }

    // A3: Modify 3 Same-Size/Fixed Fields + Serialize (Fast-Path 1)
    {
        auto m_pb_fresh = measure_bench(WARMUP, ITERS, [&](int iter) {
            pb::OrderEnvelope msg;
            LP_CHECK(msg.ParseFromArray(complex_bytes.data(), static_cast<int>(complex_bytes.size())));
            msg.set_status(2);
            msg.set_updated_at_fixed64(1800000000000ULL + (iter & 0xFF));
            msg.mutable_user()->set_score(98.75);
            std::string out;
            LP_CHECK(msg.SerializeToString(&out));
            return observe_output(out);
        });
        auto m_pb_reuse = measure_bench(WARMUP, ITERS, [&](int iter) {
            LP_CHECK(reusable_pb.ParseFromArray(complex_bytes.data(), static_cast<int>(complex_bytes.size())));
            reusable_pb.set_status(2);
            reusable_pb.set_updated_at_fixed64(1800000000000ULL + (iter & 0xFF));
            reusable_pb.mutable_user()->set_score(98.75);
            int sz = static_cast<int>(reusable_pb.ByteSizeLong());
            LP_CHECK(reusable_pb.SerializeToArray(out_buf.data(), sz));
            return observe_output(out_buf.data(), static_cast<size_t>(sz));
        });
        auto m_hand = measure_bench(WARMUP, ITERS, [&](int iter) {
            StdOrderEnvelope msg;
            msg.ParseFromArray(complex_bytes.data(), static_cast<uint32_t>(complex_bytes.size()));
            msg.status = 2;
            msg.updated_at_fixed64 = 1800000000000ULL + (iter & 0xFF);
            msg.user.score = 98.75;
            size_t sz = msg.SerializeToArray(out_buf.data());
            return observe_output(out_buf.data(), sz);
        });
        auto m_lazy_fresh = measure_bench(WARMUP, ITERS, [&](int iter) {
            OrderEnvelopeView msg;
            msg.wrap(complex_bytes.data(), 0, static_cast<uint32_t>(complex_bytes.size()));
            msg.set_status(2);
            msg.set_updated_at_fixed64(1800000000000ULL + (iter & 0xFF));
            msg.mutable_user().set_score(98.75);
            std::string out;
            msg.merge_to(out);
            return observe_output(out);
        });
        auto m_lazy_wrap_alloc = measure_bench(WARMUP, ITERS, [&](int iter) {
            reusable_lazy.wrap(complex_bytes.data(), 0, static_cast<uint32_t>(complex_bytes.size()));
            reusable_lazy.set_status(2);
            reusable_lazy.set_updated_at_fixed64(1800000000000ULL + (iter & 0xFF));
            reusable_lazy.mutable_user().set_score(98.75);
            std::string out;
            reusable_lazy.merge_to(out);
            return observe_output(out);
        });
        auto m_lazy_reuse = measure_bench(WARMUP, ITERS, [&](int iter) {
            reusable_lazy.wrap(complex_bytes.data(), 0, static_cast<uint32_t>(complex_bytes.size()));
            reusable_lazy.set_status(2);
            reusable_lazy.set_updated_at_fixed64(1800000000000ULL + (iter & 0xFF));
            reusable_lazy.mutable_user().set_score(98.75);
            size_t sz = reusable_lazy.write_to_buffer(out_buf.data());
            return observe_output(out_buf.data(), sz);
        });
        rows.push_back({"Advantageous (A3)", "3 Fixed/Same-Size Edits (Fast-Path 1)",
                        m_pb_fresh, m_pb_reuse, m_hand, m_lazy_fresh, m_lazy_wrap_alloc, m_lazy_reuse});
    }

    // A4: Modify Variable-Len String & Nested City + Splice (Fast-Path 2)
    {
        auto m_pb_fresh = measure_bench(WARMUP, ITERS, [&](int) {
            pb::OrderEnvelope msg;
            LP_CHECK(msg.ParseFromArray(complex_bytes.data(), static_cast<int>(complex_bytes.size())));
            msg.set_buyer_name("alexander_the_great_vip_buyer");
            msg.set_status(50000);
            msg.mutable_user()->set_city("SHANGHAI_PUDONG");
            std::string out;
            LP_CHECK(msg.SerializeToString(&out));
            return observe_output(out);
        });
        auto m_pb_reuse = measure_bench(WARMUP, ITERS, [&](int) {
            LP_CHECK(reusable_pb.ParseFromArray(complex_bytes.data(), static_cast<int>(complex_bytes.size())));
            reusable_pb.set_buyer_name("alexander_the_great_vip_buyer");
            reusable_pb.set_status(50000);
            reusable_pb.mutable_user()->set_city("SHANGHAI_PUDONG");
            int sz = static_cast<int>(reusable_pb.ByteSizeLong());
            LP_CHECK(reusable_pb.SerializeToArray(out_buf.data(), sz));
            return observe_output(out_buf.data(), static_cast<size_t>(sz));
        });
        auto m_hand = measure_bench(WARMUP, ITERS, [&](int) {
            StdOrderEnvelope msg;
            msg.ParseFromArray(complex_bytes.data(), static_cast<uint32_t>(complex_bytes.size()));
            msg.buyer_name = "alexander_the_great_vip_buyer";
            msg.status = 50000;
            msg.user.city = "SHANGHAI_PUDONG";
            size_t sz = msg.SerializeToArray(out_buf.data());
            return observe_output(out_buf.data(), sz);
        });
        auto m_lazy_fresh = measure_bench(WARMUP, ITERS, [&](int) {
            OrderEnvelopeView msg;
            msg.wrap(complex_bytes.data(), 0, static_cast<uint32_t>(complex_bytes.size()));
            msg.set_buyer_name("alexander_the_great_vip_buyer").set_status(50000);
            msg.mutable_user().set_city("SHANGHAI_PUDONG");
            std::string out;
            msg.merge_to(out);
            return observe_output(out);
        });
        auto m_lazy_wrap_alloc = measure_bench(WARMUP, ITERS, [&](int) {
            reusable_lazy.wrap(complex_bytes.data(), 0, static_cast<uint32_t>(complex_bytes.size()));
            reusable_lazy.set_buyer_name("alexander_the_great_vip_buyer").set_status(50000);
            reusable_lazy.mutable_user().set_city("SHANGHAI_PUDONG");
            std::string out;
            reusable_lazy.merge_to(out);
            return observe_output(out);
        });
        auto m_lazy_reuse = measure_bench(WARMUP, ITERS, [&](int) {
            reusable_lazy.wrap(complex_bytes.data(), 0, static_cast<uint32_t>(complex_bytes.size()));
            reusable_lazy.set_buyer_name("alexander_the_great_vip_buyer").set_status(50000);
            reusable_lazy.mutable_user().set_city("SHANGHAI_PUDONG");
            size_t sz = reusable_lazy.write_to_buffer(out_buf.data());
            return observe_output(out_buf.data(), sz);
        });
        rows.push_back({"Advantageous (A4)", "Var-Size String + Nested City (Path 2)",
                        m_pb_fresh, m_pb_reuse, m_hand, m_lazy_fresh, m_lazy_wrap_alloc, m_lazy_reuse});
    }

    // A5: Mutate 1 of 100 Repeated LineItems + Single-Pass Splice
    {
        auto m_pb_fresh = measure_bench(WARMUP, ITERS, [&](int) {
            pb::OrderEnvelope msg;
            LP_CHECK(msg.ParseFromArray(complex_bytes.data(), static_cast<int>(complex_bytes.size())));
            msg.mutable_items(42)->set_quantity(999);
            msg.mutable_items(42)->set_description("UPDATED_ITEM_42_DESC");
            std::string out;
            LP_CHECK(msg.SerializeToString(&out));
            return observe_output(out);
        });
        auto m_pb_reuse = measure_bench(WARMUP, ITERS, [&](int) {
            LP_CHECK(reusable_pb.ParseFromArray(complex_bytes.data(), static_cast<int>(complex_bytes.size())));
            reusable_pb.mutable_items(42)->set_quantity(999);
            reusable_pb.mutable_items(42)->set_description("UPDATED_ITEM_42_DESC");
            int sz = static_cast<int>(reusable_pb.ByteSizeLong());
            LP_CHECK(reusable_pb.SerializeToArray(out_buf.data(), sz));
            return observe_output(out_buf.data(), static_cast<size_t>(sz));
        });
        auto m_hand = measure_bench(WARMUP, ITERS, [&](int) {
            StdOrderEnvelope msg;
            msg.ParseFromArray(complex_bytes.data(), static_cast<uint32_t>(complex_bytes.size()));
            msg.items[42].quantity = 999;
            msg.items[42].description = "UPDATED_ITEM_42_DESC";
            size_t sz = msg.SerializeToArray(out_buf.data());
            return observe_output(out_buf.data(), sz);
        });
        auto m_lazy_fresh = measure_bench(WARMUP, ITERS, [&](int) {
            OrderEnvelopeView msg;
            msg.wrap(complex_bytes.data(), 0, static_cast<uint32_t>(complex_bytes.size()));
            auto& item42 = msg.items(42);
            item42.set_quantity(999);
            item42.set_description("UPDATED_ITEM_42_DESC");
            std::string out;
            msg.merge_to(out);
            return observe_output(out);
        });
        auto m_lazy_wrap_alloc = measure_bench(WARMUP, ITERS, [&](int) {
            reusable_lazy.wrap(complex_bytes.data(), 0, static_cast<uint32_t>(complex_bytes.size()));
            auto& item42 = reusable_lazy.items(42);
            item42.set_quantity(999);
            item42.set_description("UPDATED_ITEM_42_DESC");
            std::string out;
            reusable_lazy.merge_to(out);
            return observe_output(out);
        });
        auto m_lazy_reuse = measure_bench(WARMUP, ITERS, [&](int) {
            reusable_lazy.wrap(complex_bytes.data(), 0, static_cast<uint32_t>(complex_bytes.size()));
            auto& item42 = reusable_lazy.items(42);
            item42.set_quantity(999);
            item42.set_description("UPDATED_ITEM_42_DESC");
            size_t sz = reusable_lazy.write_to_buffer(out_buf.data());
            return observe_output(out_buf.data(), sz);
        });
        rows.push_back({"Advantageous (A5)", "Mutate 1 of 100 Repeated Items",
                        m_pb_fresh, m_pb_reuse, m_hand, m_lazy_fresh, m_lazy_wrap_alloc, m_lazy_reuse});
    }

    // A6: Gateway Redact 1 Sensitive Field + Forward 12.4KB Msg
    {
        auto m_pb_fresh = measure_bench(WARMUP, ITERS, [&](int) {
            pb::OrderEnvelope msg;
            LP_CHECK(msg.ParseFromArray(complex_bytes.data(), static_cast<int>(complex_bytes.size())));
            msg.clear_auth_token();
            std::string out;
            LP_CHECK(msg.SerializeToString(&out));
            return observe_output(out);
        });
        auto m_pb_reuse = measure_bench(WARMUP, ITERS, [&](int) {
            LP_CHECK(reusable_pb.ParseFromArray(complex_bytes.data(), static_cast<int>(complex_bytes.size())));
            reusable_pb.clear_auth_token();
            int sz = static_cast<int>(reusable_pb.ByteSizeLong());
            LP_CHECK(reusable_pb.SerializeToArray(out_buf.data(), sz));
            return observe_output(out_buf.data(), static_cast<size_t>(sz));
        });
        auto m_hand = measure_bench(WARMUP, ITERS, [&](int) {
            StdOrderEnvelope msg;
            msg.ParseFromArray(complex_bytes.data(), static_cast<uint32_t>(complex_bytes.size()));
            msg.auth_token.clear();
            size_t sz = msg.SerializeToArray(out_buf.data());
            return observe_output(out_buf.data(), sz);
        });
        auto m_lazy_fresh = measure_bench(WARMUP, ITERS, [&](int) {
            OrderEnvelopeView msg;
            msg.wrap(complex_bytes.data(), 0, static_cast<uint32_t>(complex_bytes.size()));
            msg.clear_auth_token();
            std::string out;
            msg.merge_to(out);
            return observe_output(out);
        });
        auto m_lazy_wrap_alloc = measure_bench(WARMUP, ITERS, [&](int) {
            reusable_lazy.wrap(complex_bytes.data(), 0, static_cast<uint32_t>(complex_bytes.size()));
            reusable_lazy.clear_auth_token();
            std::string out;
            reusable_lazy.merge_to(out);
            return observe_output(out);
        });
        auto m_lazy_reuse = measure_bench(WARMUP, ITERS, [&](int) {
            reusable_lazy.wrap(complex_bytes.data(), 0, static_cast<uint32_t>(complex_bytes.size()));
            reusable_lazy.clear_auth_token();
            size_t sz = reusable_lazy.write_to_buffer(out_buf.data());
            return observe_output(out_buf.data(), sz);
        });
        rows.push_back({"Advantageous (A6)", "Gateway Redact 1 Field + Forward",
                        m_pb_fresh, m_pb_reuse, m_hand, m_lazy_fresh, m_lazy_wrap_alloc, m_lazy_reuse});
    }

    // B1: Build from Scratch (10 items, 0% input reuse)
    std::string small_payload(256, 'x');
    static const char* SKU_TABLE[10] = {
        "SKU-000", "SKU-001", "SKU-002", "SKU-003", "SKU-004",
        "SKU-005", "SKU-006", "SKU-007", "SKU-008", "SKU-009"
    };
    {
        auto populate_pb = [&](pb::OrderEnvelope& msg, int iter) {
            msg.set_order_id(202611240001LL + iter);
            msg.set_buyer_name("scratch_buyer");
            msg.set_status(1);
            msg.set_updated_at_fixed64(1795497000123ULL);
            auto* u = msg.mutable_user();
            u->set_user_id(88001122LL);
            u->set_city("SHANGHAI");
            u->set_vip_level(3);
            u->set_score(95.5);
            u->set_email("alice@example.com");
            msg.set_payload(small_payload);
            msg.set_auth_token("token-123456");
            msg.set_trace_id("trace-123456");
            msg.set_total_cents(99000LL);
            for (int i = 0; i < 10; i++) {
                auto* item = msg.add_items();
                item->set_item_id(100LL + i);
                item->set_sku(SKU_TABLE[i]);
                item->set_quantity(2);
                item->set_price_cents(1500LL);
                item->set_description("Item desc");
            }
        };
        auto m_pb_fresh = measure_bench(WARMUP, ITERS, [&](int iter) {
            pb::OrderEnvelope msg;
            populate_pb(msg, iter);
            std::string out;
            LP_CHECK(msg.SerializeToString(&out));
            return observe_output(out);
        });
        auto m_pb_reuse = measure_bench(WARMUP, ITERS, [&](int iter) {
            reusable_pb.Clear();
            populate_pb(reusable_pb, iter);
            int sz = static_cast<int>(reusable_pb.ByteSizeLong());
            LP_CHECK(reusable_pb.SerializeToArray(out_buf.data(), sz));
            return observe_output(out_buf.data(), static_cast<size_t>(sz));
        });
        auto m_hand = measure_bench(WARMUP, ITERS, [&](int iter) {
            StdOrderEnvelope msg;
            msg.order_id = 202611240001ULL + iter;
            msg.buyer_name = "scratch_buyer";
            msg.status = 1;
            msg.updated_at_fixed64 = 1795497000123ULL;
            msg.has_user = true;
            msg.user.user_id = 88001122ULL;
            msg.user.city = "SHANGHAI";
            msg.user.vip_level = 3;
            msg.user.score = 95.5;
            msg.user.email = "alice@example.com";
            msg.payload = small_payload;
            msg.auth_token = "token-123456";
            msg.trace_id = "trace-123456";
            msg.total_cents = 99000ULL;
            msg.items.reserve(10);
            for (int i = 0; i < 10; i++) {
                StdLineItem item;
                item.item_id = 100ULL + i;
                item.sku = SKU_TABLE[i];
                item.quantity = 2;
                item.price_cents = 1500ULL;
                item.description = "Item desc";
                msg.items.push_back(std::move(item));
            }
            size_t sz = msg.SerializeToArray(out_buf.data());
            return observe_output(out_buf.data(), sz);
        });
        auto populate_lazy = [&](OrderEnvelopeView& target, int iter) {
            target.init_scratch();
            target.set_order_id(202611240001ULL + iter)
                  .set_buyer_name("scratch_buyer")
                  .set_status(1)
                  .set_updated_at_fixed64(1795497000123ULL)
                  .set_payload(small_payload)
                  .set_auth_token("token-123456")
                  .set_trace_id("trace-123456")
                  .set_total_cents(99000ULL);
            auto& u = target.mutable_user();
            u.set_user_id(88001122ULL);
            u.set_city("SHANGHAI");
            u.set_vip_level(3);
            u.set_score(95.5);
            u.set_email("alice@example.com");
            for (int i = 0; i < 10; i++) {
                auto& item = target.add_item();
                item.set_item_id(100ULL + i);
                item.set_sku(SKU_TABLE[i]);
                item.set_quantity(2);
                item.set_price_cents(1500ULL);
                item.set_description("Item desc");
            }
        };
        auto m_lazy_fresh = measure_bench(WARMUP, ITERS, [&](int iter) {
            OrderEnvelopeView msg;
            populate_lazy(msg, iter);
            std::string out;
            msg.merge_to(out);
            return observe_output(out);
        });
        auto m_lazy_wrap_alloc = measure_bench(WARMUP, ITERS, [&](int iter) {
            populate_lazy(reusable_lazy, iter);
            std::string out;
            reusable_lazy.merge_to(out);
            return observe_output(out);
        });
        auto m_lazy_reuse = measure_bench(WARMUP, ITERS, [&](int iter) {
            populate_lazy(reusable_lazy, iter);
            size_t sz = reusable_lazy.write_to_buffer(out_buf.data());
            return observe_output(out_buf.data(), sz);
        });
        rows.push_back({"Non-Advantageous (B1)", "Build from Scratch (10 items)",
                        m_pb_fresh, m_pb_reuse, m_hand, m_lazy_fresh, m_lazy_wrap_alloc, m_lazy_reuse});
    }

    // B2: 100% Full-Tree Read
    {
        auto read_pb = [](const pb::OrderEnvelope& msg) {
            uint64_t sum = static_cast<uint64_t>(msg.order_id()) + msg.buyer_name().size() + msg.status()
                         + msg.updated_at_fixed64() + msg.payload().size()
                         + msg.auth_token().size() + msg.trace_id().size() + msg.total_cents();
            const auto& u = msg.user();
            sum += static_cast<uint64_t>(u.user_id()) + u.city().size() + u.vip_level()
                 + static_cast<uint64_t>(u.score()) + u.email().size();
            for (const auto& item : msg.items()) {
                sum += static_cast<uint64_t>(item.item_id()) + item.sku().size() + item.quantity()
                     + item.price_cents() + item.description().size();
            }
            return sum;
        };
        auto read_lazy = [](OrderEnvelopeView& msg) {
            uint64_t sum = msg.order_id() + msg.buyer_name().size() + msg.status()
                         + msg.updated_at_fixed64() + msg.payload().size()
                         + msg.auth_token().size() + msg.trace_id().size() + msg.total_cents();
            auto& u = msg.user();
            sum += u.user_id() + u.city().size() + u.vip_level()
                 + static_cast<uint64_t>(u.score()) + u.email().size();
            uint32_t n = msg.items_count();
            LineItemView tmp_item;
            for (uint32_t i = 0; i < n; i++) {
                msg.read_item_eager(i, tmp_item);
                sum += tmp_item.item_id_ + tmp_item.sku_.size() + tmp_item.quantity_
                     + tmp_item.price_cents_ + tmp_item.description_.size();
            }
            return sum;
        };
        auto m_pb_fresh = measure_bench(WARMUP, ITERS, [&](int) {
            pb::OrderEnvelope msg;
            LP_CHECK(msg.ParseFromArray(complex_bytes.data(), static_cast<int>(complex_bytes.size())));
            return read_pb(msg);
        });
        auto m_pb_reuse = measure_bench(WARMUP, ITERS, [&](int) {
            LP_CHECK(reusable_pb.ParseFromArray(complex_bytes.data(), static_cast<int>(complex_bytes.size())));
            return read_pb(reusable_pb);
        });
        auto m_hand = measure_bench(WARMUP, ITERS, [&](int) {
            StdOrderEnvelope msg;
            msg.ParseFromArray(complex_bytes.data(), static_cast<uint32_t>(complex_bytes.size()));
            uint64_t sum = msg.order_id + msg.buyer_name.size() + msg.status
                         + msg.updated_at_fixed64 + msg.payload.size()
                         + msg.auth_token.size() + msg.trace_id.size() + msg.total_cents;
            sum += msg.user.user_id + msg.user.city.size() + msg.user.vip_level
                 + static_cast<uint64_t>(msg.user.score) + msg.user.email.size();
            for (const auto& item : msg.items) {
                sum += item.item_id + item.sku.size() + item.quantity
                     + item.price_cents + item.description.size();
            }
            return sum;
        });
        auto m_lazy_fresh = measure_bench(WARMUP, ITERS, [&](int) {
            OrderEnvelopeView msg;
            msg.wrap(complex_bytes.data(), 0, static_cast<uint32_t>(complex_bytes.size()));
            return read_lazy(msg);
        });
        auto m_lazy_reuse = measure_bench(WARMUP, ITERS, [&](int) {
            reusable_lazy.wrap(complex_bytes.data(), 0, static_cast<uint32_t>(complex_bytes.size()));
            return read_lazy(reusable_lazy);
        });
        rows.push_back({"Non-Advantageous (B2)", "100% Full-Tree Read (100 Items)",
                        m_pb_fresh, m_pb_reuse, m_hand, m_lazy_fresh, m_lazy_reuse, m_lazy_reuse});
    }

    // B3: 100% Full-Tree Variable-Size Mutation
    std::string new_payload(128, 'z');
    {
        auto mutate_pb = [&](pb::OrderEnvelope& msg) {
            msg.set_order_id(1LL);
            msg.set_buyer_name("mutated_full_tree_buyer_name_long");
            msg.set_status(99999);
            msg.set_updated_at_fixed64(42ULL);
            msg.set_payload(new_payload);
            msg.set_auth_token("new_short_tok");
            msg.set_trace_id("new_trace_id_full_mutation_long_string");
            msg.set_total_cents(5LL);
            auto* u = msg.mutable_user();
            u->set_user_id(2LL);
            u->set_city("GUANGZHOU_FULL_MUTATE");
            u->set_vip_level(10000);
            u->set_score(12.34);
            u->set_email("full_mutate_user@example.org");
            for (int i = 0; i < msg.items_size(); i++) {
                auto* item = msg.mutable_items(i);
                item->set_item_id(9000000LL + i);
                item->set_sku("NEW-LONG-SKU-CODE-MUTATED");
                item->set_quantity(50000);
                item->set_price_cents(10LL);
                item->set_description("Short");
            }
        };
        auto m_pb_fresh = measure_bench(WARMUP, ITERS, [&](int) {
            pb::OrderEnvelope msg;
            LP_CHECK(msg.ParseFromArray(medium_bytes.data(), static_cast<int>(medium_bytes.size())));
            mutate_pb(msg);
            std::string out;
            LP_CHECK(msg.SerializeToString(&out));
            return observe_output(out);
        });
        auto m_pb_reuse = measure_bench(WARMUP, ITERS, [&](int) {
            LP_CHECK(reusable_pb.ParseFromArray(medium_bytes.data(), static_cast<int>(medium_bytes.size())));
            mutate_pb(reusable_pb);
            int sz = static_cast<int>(reusable_pb.ByteSizeLong());
            LP_CHECK(reusable_pb.SerializeToArray(out_buf.data(), sz));
            return observe_output(out_buf.data(), static_cast<size_t>(sz));
        });
        auto m_hand = measure_bench(WARMUP, ITERS, [&](int) {
            StdOrderEnvelope msg;
            msg.ParseFromArray(medium_bytes.data(), static_cast<uint32_t>(medium_bytes.size()));
            msg.order_id = 1ULL;
            msg.buyer_name = "mutated_full_tree_buyer_name_long";
            msg.status = 99999;
            msg.updated_at_fixed64 = 42ULL;
            msg.payload = new_payload;
            msg.auth_token = "new_short_tok";
            msg.trace_id = "new_trace_id_full_mutation_long_string";
            msg.total_cents = 5ULL;
            msg.user.user_id = 2ULL;
            msg.user.city = "GUANGZHOU_FULL_MUTATE";
            msg.user.vip_level = 10000;
            msg.user.score = 12.34;
            msg.user.email = "full_mutate_user@example.org";
            for (size_t i = 0; i < msg.items.size(); i++) {
                msg.items[i].item_id = 9000000ULL + i;
                msg.items[i].sku = "NEW-LONG-SKU-CODE-MUTATED";
                msg.items[i].quantity = 50000;
                msg.items[i].price_cents = 10ULL;
                msg.items[i].description = "Short";
            }
            size_t sz = msg.SerializeToArray(out_buf.data());
            return observe_output(out_buf.data(), sz);
        });
        auto mutate_lazy = [&](OrderEnvelopeView& target) {
            target.wrap(medium_bytes.data(), 0, static_cast<uint32_t>(medium_bytes.size()));
            target.set_order_id(1ULL)
                  .set_buyer_name("mutated_full_tree_buyer_name_long")
                  .set_status(99999)
                  .set_updated_at_fixed64(42ULL)
                  .set_payload(new_payload)
                  .set_auth_token("new_short_tok")
                  .set_trace_id("new_trace_id_full_mutation_long_string")
                  .set_total_cents(5ULL);
            auto& u = target.mutable_user();
            u.set_user_id(2ULL);
            u.set_city("GUANGZHOU_FULL_MUTATE");
            u.set_vip_level(10000);
            u.set_score(12.34);
            u.set_email("full_mutate_user@example.org");
            uint32_t n = target.items_count();
            for (uint32_t i = 0; i < n; i++) {
                auto& item = target.items(i);
                item.set_item_id(9000000ULL + i);
                item.set_sku("NEW-LONG-SKU-CODE-MUTATED");
                item.set_quantity(50000);
                item.set_price_cents(10ULL);
                item.set_description("Short");
            }
        };
        auto m_lazy_fresh = measure_bench(WARMUP, ITERS, [&](int) {
            OrderEnvelopeView msg;
            mutate_lazy(msg);
            std::string out;
            msg.merge_to(out);
            return observe_output(out);
        });
        auto m_lazy_wrap_alloc = measure_bench(WARMUP, ITERS, [&](int) {
            mutate_lazy(reusable_lazy);
            std::string out;
            reusable_lazy.merge_to(out);
            return observe_output(out);
        });
        auto m_lazy_reuse = measure_bench(WARMUP, ITERS, [&](int) {
            mutate_lazy(reusable_lazy);
            size_t sz = reusable_lazy.write_to_buffer(out_buf.data());
            return observe_output(out_buf.data(), sz);
        });
        rows.push_back({"Non-Advantageous (B3)", "100% Full-Tree Var-Size Mutate",
                        m_pb_fresh, m_pb_reuse, m_hand, m_lazy_fresh, m_lazy_wrap_alloc, m_lazy_reuse});
    }

    // B4: Tiny Message (Dynamically labeled with exact serialized byte size!)
    {
        auto m_pb_fresh = measure_bench(WARMUP, ITERS, [&](int) {
            pb::OrderEnvelope msg;
            LP_CHECK(msg.ParseFromArray(tiny_bytes.data(), static_cast<int>(tiny_bytes.size())));
            uint64_t sum = static_cast<uint64_t>(msg.order_id() + msg.status() + msg.total_cents());
            msg.set_order_id(static_cast<int64_t>(sum + 100000000ULL));
            msg.set_status(30000);
            msg.set_total_cents(999999999LL);
            std::string out;
            LP_CHECK(msg.SerializeToString(&out));
            return observe_output(out);
        });
        auto m_pb_reuse = measure_bench(WARMUP, ITERS, [&](int) {
            LP_CHECK(reusable_pb.ParseFromArray(tiny_bytes.data(), static_cast<int>(tiny_bytes.size())));
            uint64_t sum = static_cast<uint64_t>(reusable_pb.order_id() + reusable_pb.status() + reusable_pb.total_cents());
            reusable_pb.set_order_id(static_cast<int64_t>(sum + 100000000ULL));
            reusable_pb.set_status(30000);
            reusable_pb.set_total_cents(999999999LL);
            int sz = static_cast<int>(reusable_pb.ByteSizeLong());
            LP_CHECK(reusable_pb.SerializeToArray(out_buf.data(), sz));
            return observe_output(out_buf.data(), static_cast<size_t>(sz));
        });
        auto m_hand = measure_bench(WARMUP, ITERS, [&](int) {
            StdOrderEnvelope msg;
            msg.ParseFromArray(tiny_bytes.data(), static_cast<uint32_t>(tiny_bytes.size()));
            uint64_t sum = msg.order_id + msg.status + msg.total_cents;
            msg.order_id = sum + 100000000ULL;
            msg.status = 30000;
            msg.total_cents = 999999999ULL;
            size_t sz = msg.SerializeToArray(out_buf.data());
            return observe_output(out_buf.data(), sz);
        });
        auto m_lazy_fresh = measure_bench(WARMUP, ITERS, [&](int) {
            OrderEnvelopeView msg;
            msg.wrap(tiny_bytes.data(), 0, static_cast<uint32_t>(tiny_bytes.size()));
            uint64_t sum = msg.order_id() + msg.status() + msg.total_cents();
            msg.set_order_id(sum + 100000000ULL).set_status(30000).set_total_cents(999999999ULL);
            std::string out;
            msg.merge_to(out);
            return observe_output(out);
        });
        auto m_lazy_wrap_alloc = measure_bench(WARMUP, ITERS, [&](int) {
            reusable_lazy.wrap(tiny_bytes.data(), 0, static_cast<uint32_t>(tiny_bytes.size()));
            uint64_t sum = reusable_lazy.order_id() + reusable_lazy.status() + reusable_lazy.total_cents();
            reusable_lazy.set_order_id(sum + 100000000ULL).set_status(30000).set_total_cents(999999999ULL);
            std::string out;
            reusable_lazy.merge_to(out);
            return observe_output(out);
        });
        auto m_lazy_reuse = measure_bench(WARMUP, ITERS, [&](int) {
            reusable_lazy.wrap(tiny_bytes.data(), 0, static_cast<uint32_t>(tiny_bytes.size()));
            uint64_t sum = reusable_lazy.order_id() + reusable_lazy.status() + reusable_lazy.total_cents();
            reusable_lazy.set_order_id(sum + 100000000ULL).set_status(30000).set_total_cents(999999999ULL);
            size_t sz = reusable_lazy.write_to_buffer(out_buf.data());
            return observe_output(out_buf.data(), sz);
        });
        char b4_name[64];
        std::snprintf(b4_name, sizeof(b4_name), "Tiny %zu-Byte Message (Read+Mutate)", tiny_bytes.size());
        rows.push_back({"Non-Advantageous (B4)", b4_name,
                        m_pb_fresh, m_pb_reuse, m_hand, m_lazy_fresh, m_lazy_wrap_alloc, m_lazy_reuse});
    }

    int ver_major = GOOGLE_PROTOBUF_VERSION / 1000000;
    int ver_minor = (GOOGLE_PROTOBUF_VERSION / 1000) % 1000;
    int ver_patch = GOOGLE_PROTOBUF_VERSION % 1000;
    std::printf("========================================================================================================================================================================\n");
    std::printf("  C++20 LOWER-LEVEL BENCHMARK: Official libprotobuf %d.%d.%d vs HandEager vs Lazy-Proto C++20 | GCC %s (-O3 -march=native -std=c++20)\n",
                ver_major, ver_minor, ver_patch, __VERSION__);
    std::printf("  Lifecycles: PB-Fresh/Lazy-Fresh = new msg + alloc string; Lazy-WrapAlloc = reused wrapper + alloc string; PB-Reuse/Lazy-Reuse = reused msg + reused out_buf.\n");
    std::printf("========================================================================================================================================================================\n");
    std::printf("%-21s | %-36s | %9s | %9s | %9s | %10s | %10s | %10s | %8s | %8s\n",
                "Category", "Workload Scenario", "PB-Fresh", "PB-Reuse", "HandEager", "LazyFresh", "LazyWrpAlc", "LazyReuse", "vsPB-Fsh", "vsPB-Rse");
    std::printf("------------------------------------------------------------------------------------------------------------------------------------------------------------------------\n");
    for (const auto& r : rows) {
        std::printf("%-21s | %-36s | %9.1f | %9.1f | %9.1f | %10.1f | %10.1f | %10.1f | %7.2fx | %7.2fx\n",
                    r.category, r.name.c_str(),
                    r.pb_fresh_alloc.ns, r.pb_reuse_buf.ns, r.hand_eager.ns,
                    r.lazy_fresh_alloc.ns, r.lazy_wrap_alloc.ns, r.lazy_reuse_buf.ns,
                    r.pb_fresh_alloc.ns / r.lazy_fresh_alloc.ns,
                    r.pb_reuse_buf.ns / r.lazy_reuse_buf.ns);
    }
    std::printf("------------------------------------------------------------------------------------------------------------------------------------------------------------------------\n");
    std::printf("  HEAP ALLOCATIONS (Bytes/op):\n");
    std::printf("%-21s | %-36s | %9s | %9s | %9s | %10s | %10s | %10s\n",
                "Category", "Workload Scenario", "PB-Fresh", "PB-Reuse", "HandEager", "LazyFresh", "LazyWrpAlc", "LazyReuse");
    std::printf("------------------------------------------------------------------------------------------------------------------------------------------------------------------------\n");
    for (const auto& r : rows) {
        std::printf("%-21s | %-36s | %9llu | %9llu | %9llu | %10llu | %10llu | %10llu\n",
                    r.category, r.name.c_str(),
                    static_cast<unsigned long long>(r.pb_fresh_alloc.alloc_bytes),
                    static_cast<unsigned long long>(r.pb_reuse_buf.alloc_bytes),
                    static_cast<unsigned long long>(r.hand_eager.alloc_bytes),
                    static_cast<unsigned long long>(r.lazy_fresh_alloc.alloc_bytes),
                    static_cast<unsigned long long>(r.lazy_wrap_alloc.alloc_bytes),
                    static_cast<unsigned long long>(r.lazy_reuse_buf.alloc_bytes));
    }
    std::printf("========================================================================================================================================================================\n");

    google::protobuf::ShutdownProtobufLibrary();
    return 0;
}
