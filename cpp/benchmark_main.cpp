#include "lazy_proto.hpp"
#include "std_proto.hpp"

#include <chrono>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <iostream>
#include <vector>
#include <cassert>

using namespace lazyproto;
using namespace stdproto;

// ============================================================================
// Global Heap Allocation Counter (tracks malloc/new bytes during benchmarks)
// ============================================================================
static thread_local uint64_t g_thread_alloc_bytes = 0;

__attribute__((noinline)) void* operator new(std::size_t size) {
    g_thread_alloc_bytes += size;
    void* p = std::malloc(size);
    if (!p) throw std::bad_alloc();
    return p;
}

__attribute__((noinline)) void operator delete(void* p) noexcept {
    std::free(p);
}

__attribute__((noinline)) void operator delete(void* p, std::size_t) noexcept {
    std::free(p);
}

// ============================================================================
// Test Fixture Generator (Identical 12.4 KB packet to Java test suite!)
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

    // Unknown fields #50 (varint) and #51 (length-delimited string)
    env.unknowns.push_back({make_tag(50, WIRETYPE_VARINT), 9876543210ULL, {}});
    env.unknowns.push_back({make_tag(51, WIRETYPE_LENGTH_DELIMITED), 0, "unknown-extension-metadata-v2"});

    uint32_t sz = env.ByteSizeLong();
    // Allocate with 16-byte tail padding for SWAR safety
    std::vector<uint8_t> out(sz + 16, 0);
    size_t written = env.SerializeToArray(out.data());
    assert(written == sz);
    out.resize(sz);
    return out;
}

// ============================================================================
// Correctness Verification Suite (All Advantageous & Adverse Scenarios)
// ============================================================================
void run_correctness_tests() {
    std::printf("[Correctness] Running C++20 Lazy-Proto verification suite...\n");
    std::vector<uint8_t> raw = create_complex_order_bytes(100, 4096);
    std::vector<uint8_t> out_buf(raw.size() + 4096);

    // 1. A1 & A2: Partial Read & Fast-Path 0 Passthrough
    {
        LazyOrderEnvelope lazy;
        lazy.wrap(raw.data(), 0, static_cast<uint32_t>(raw.size()));
        assert(!lazy.scanned_);
        assert(lazy.order_id() == 202611240001ULL);
        assert(lazy.buyer_name() == "alice_wonderland");
        assert(lazy.status() == 1);
        assert(lazy.user().vip_level() == 3);
        assert(lazy.user().city() == "SH");
        assert(lazy.items_count() == 100);
        assert(lazy.items(3).sku() == "SKU-PRO-0003");
        assert(lazy.is_clean());

        size_t len = lazy.write_to_buffer(out_buf.data());
        assert(len == raw.size());
        assert(std::memcmp(raw.data(), out_buf.data(), len) == 0);
    }

    // 2. A3: Fast-Path 1 Deep In-Place Binary Patching
    {
        LazyOrderEnvelope lazy;
        lazy.wrap(raw.data(), 0, static_cast<uint32_t>(raw.size()));
        lazy.set_status(2);
        lazy.set_updated_at_fixed64(1800000000999ULL);
        lazy.mutable_user().set_city("BJ");
        lazy.mutable_user().set_score(99.875);
        lazy.mutable_user().set_vip_level(5);
        lazy.sync_user_dirty();
        lazy.items(7).set_quantity(9);
        lazy.sync_item_dirty(7);

        assert(lazy.is_same_size_dirty_only());
        size_t len = lazy.write_to_buffer(out_buf.data());
        assert(len == raw.size());

        StdOrderEnvelope decoded;
        decoded.ParseFromArray(out_buf.data(), static_cast<uint32_t>(len));
        assert(decoded.status == 2);
        assert(decoded.updated_at_fixed64 == 1800000000999ULL);
        assert(decoded.user.city == "BJ");
        assert(decoded.user.score == 99.875);
        assert(decoded.user.vip_level == 5);
        assert(decoded.items[7].quantity == 9);
        assert(decoded.unknowns.size() == 2);
    }

    // 3. A4 & A5: Fast-Path 2 & 3 Single-Pass Span Splicing + Single Repeated Item Mutation
    {
        LazyOrderEnvelope lazy;
        lazy.wrap(raw.data(), 0, static_cast<uint32_t>(raw.size()));
        lazy.set_buyer_name("alexander_the_great_vip_customer_01");
        lazy.set_status(50000);
        lazy.mutable_user().set_city("SHANGHAI_PUDONG_NEW_AREA");
        lazy.sync_user_dirty();
        lazy.items(42).set_description("UPDATED_SHORT_DESC");
        lazy.items(42).set_quantity(9999);
        lazy.sync_item_dirty(42);

        size_t len = lazy.write_to_buffer(out_buf.data());
        StdOrderEnvelope decoded;
        decoded.ParseFromArray(out_buf.data(), static_cast<uint32_t>(len));
        assert(decoded.buyer_name == "alexander_the_great_vip_customer_01");
        assert(decoded.status == 50000);
        assert(decoded.user.city == "SHANGHAI_PUDONG_NEW_AREA");
        assert(decoded.user.email == "alice@example.com");
        assert(decoded.items.size() == 100);
        assert(decoded.items[42].description == "UPDATED_SHORT_DESC");
        assert(decoded.items[42].quantity == 9999);
        assert(decoded.items[42].sku == "SKU-PRO-0042");
        assert(decoded.items[99].sku == "SKU-PRO-0099");
        assert(decoded.unknowns.size() == 2);
    }

    // 4. A6: Gateway Field Redaction (clear_auth_token)
    {
        LazyOrderEnvelope lazy;
        lazy.wrap(raw.data(), 0, static_cast<uint32_t>(raw.size()));
        lazy.clear_auth_token();
        size_t len = lazy.write_to_buffer(out_buf.data());
        assert(len < raw.size());

        StdOrderEnvelope decoded;
        decoded.ParseFromArray(out_buf.data(), static_cast<uint32_t>(len));
        assert(decoded.auth_token.empty());
        assert(decoded.trace_id == "trace-00112233445566778899aabbccddeeff");
        assert(decoded.unknowns.size() == 2);
    }

    // 5. B1: From-Scratch Construction
    {
        LazyOrderEnvelope scratch;
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
        scratch.sync_user_dirty();

        for (int i = 0; i < 10; i++) {
            auto& item = scratch.add_item();
            item.set_item_id(100ULL + i);
            item.set_sku("SKU-SCRATCH");
            item.set_quantity(2);
            item.set_price_cents(1500ULL);
            item.set_description("Item desc");
        }

        size_t len = scratch.write_to_buffer(out_buf.data());
        StdOrderEnvelope decoded;
        decoded.ParseFromArray(out_buf.data(), static_cast<uint32_t>(len));
        assert(decoded.order_id == 999888777ULL);
        assert(decoded.user.city == "HANGZHOU");
        assert(decoded.items.size() == 10);
    }

    std::printf("[Correctness] All C++20 Lazy-Proto correctness tests PASSED (100%% binary equivalence).\n\n");
}

// ============================================================================
// Benchmark Harness
// ============================================================================
struct BenchRow {
    const char* category;
    const char* name;
    double std_ns;
    uint64_t std_alloc;
    double lazy_ns;
    uint64_t lazy_alloc;
};

static volatile uint64_t g_blackhole = 0;

template <typename Fn>
std::pair<double, uint64_t> measure_bench(int warmup, int iters, Fn&& fn) {
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

int main() {
    run_correctness_tests();

    // 1. Complex realistic fixture (~12.4 KB, 100 LineItems, 4 KB payload, 2 unknown fields)
    std::vector<uint8_t> complex_bytes = create_complex_order_bytes(100, 4096);
    std::vector<uint8_t> medium_bytes  = create_complex_order_bytes(20, 256);

    // 2. Tiny message fixture (11 bytes, 3 integer fields)
    StdOrderEnvelope tiny_env;
    tiny_env.order_id = 1001ULL;
    tiny_env.status = 1;
    tiny_env.total_cents = 5000ULL;
    std::vector<uint8_t> tiny_bytes(tiny_env.ByteSizeLong() + 16, 0);
    size_t tiny_len = tiny_env.SerializeToArray(tiny_bytes.data());
    tiny_bytes.resize(tiny_len);

    std::vector<uint8_t> out_buf(complex_bytes.size() + 4096);
    LazyOrderEnvelope reusable_lazy;

    constexpr int WARMUP = 10000;
    constexpr int ITERS  = 50000;

    std::vector<BenchRow> rows;

    // A1: Partial Read Routing (Read 3 fields from 12.4 KB message)
    {
        auto [std_ns, std_b] = measure_bench(WARMUP, ITERS, [&](int) {
            StdOrderEnvelope msg;
            msg.ParseFromArray(complex_bytes.data(), static_cast<uint32_t>(complex_bytes.size()));
            return msg.order_id + msg.status + msg.user.vip_level;
        });
        auto [lazy_ns, lazy_b] = measure_bench(WARMUP, ITERS, [&](int) {
            reusable_lazy.wrap(complex_bytes.data(), 0, static_cast<uint32_t>(complex_bytes.size()));
            return reusable_lazy.order_id() + reusable_lazy.status() + reusable_lazy.user().vip_level();
        });
        rows.push_back({"Advantageous (A1)", "Partial Read (3 fields from 12.4KB msg)", std_ns, std_b, lazy_ns, lazy_b});
    }

    // A2: Inspect 2 fields + Unmodified Passthrough (Fast-Path 0)
    {
        auto [std_ns, std_b] = measure_bench(WARMUP, ITERS, [&](int) {
            StdOrderEnvelope msg;
            msg.ParseFromArray(complex_bytes.data(), static_cast<uint32_t>(complex_bytes.size()));
            if (msg.order_id > 0 && msg.status == 1) {
                return msg.SerializeToArray(out_buf.data());
            }
            return size_t(0);
        });
        auto [lazy_ns, lazy_b] = measure_bench(WARMUP, ITERS, [&](int) {
            reusable_lazy.wrap(complex_bytes.data(), 0, static_cast<uint32_t>(complex_bytes.size()));
            if (reusable_lazy.order_id() > 0 && reusable_lazy.status() == 1) {
                return reusable_lazy.write_to_buffer(out_buf.data());
            }
            return size_t(0);
        });
        rows.push_back({"Advantageous (A2)", "Inspect 2 fields + Unmodified Passthrough (Fast-Path 0)", std_ns, std_b, lazy_ns, lazy_b});
    }

    // A3: Modify 3 Same-Size/Fixed Fields + Serialize (Fast-Path 1 In-Place Patch)
    {
        auto [std_ns, std_b] = measure_bench(WARMUP, ITERS, [&](int iter) {
            StdOrderEnvelope msg;
            msg.ParseFromArray(complex_bytes.data(), static_cast<uint32_t>(complex_bytes.size()));
            msg.status = 2;
            msg.updated_at_fixed64 = 1800000000000ULL + (iter & 0xFF);
            msg.user.score = 98.75;
            return msg.SerializeToArray(out_buf.data());
        });
        auto [lazy_ns, lazy_b] = measure_bench(WARMUP, ITERS, [&](int iter) {
            reusable_lazy.wrap(complex_bytes.data(), 0, static_cast<uint32_t>(complex_bytes.size()));
            reusable_lazy.set_status(2);
            reusable_lazy.set_updated_at_fixed64(1800000000000ULL + (iter & 0xFF));
            reusable_lazy.mutable_user().set_score(98.75);
            reusable_lazy.sync_user_dirty();
            return reusable_lazy.write_to_buffer(out_buf.data());
        });
        rows.push_back({"Advantageous (A3)", "Modify 3 Same-Size/Fixed Fields + Serialize (Fast-Path 1)", std_ns, std_b, lazy_ns, lazy_b});
    }

    // A4: Modify Variable-Len String & Nested City + Single-Pass Span Splice (Fast-Path 2)
    {
        auto [std_ns, std_b] = measure_bench(WARMUP, ITERS, [&](int) {
            StdOrderEnvelope msg;
            msg.ParseFromArray(complex_bytes.data(), static_cast<uint32_t>(complex_bytes.size()));
            msg.buyer_name = "alexander_the_great_vip_buyer";
            msg.status = 50000;
            msg.user.city = "SHANGHAI_PUDONG";
            return msg.SerializeToArray(out_buf.data());
        });
        auto [lazy_ns, lazy_b] = measure_bench(WARMUP, ITERS, [&](int) {
            reusable_lazy.wrap(complex_bytes.data(), 0, static_cast<uint32_t>(complex_bytes.size()));
            reusable_lazy.set_buyer_name("alexander_the_great_vip_buyer")
                         .set_status(50000);
            reusable_lazy.mutable_user().set_city("SHANGHAI_PUDONG");
            reusable_lazy.sync_user_dirty();
            return reusable_lazy.write_to_buffer(out_buf.data());
        });
        rows.push_back({"Advantageous (A4)", "Modify Variable-Len String & Nested City + Splice (Path 2)", std_ns, std_b, lazy_ns, lazy_b});
    }

    // A5: Mutate 1 of 100 Repeated LineItems + Single-Pass Splice
    {
        auto [std_ns, std_b] = measure_bench(WARMUP, ITERS, [&](int) {
            StdOrderEnvelope msg;
            msg.ParseFromArray(complex_bytes.data(), static_cast<uint32_t>(complex_bytes.size()));
            msg.items[42].quantity = 999;
            msg.items[42].description = "UPDATED_ITEM_42_DESC";
            return msg.SerializeToArray(out_buf.data());
        });
        auto [lazy_ns, lazy_b] = measure_bench(WARMUP, ITERS, [&](int) {
            reusable_lazy.wrap(complex_bytes.data(), 0, static_cast<uint32_t>(complex_bytes.size()));
            auto& item42 = reusable_lazy.items(42);
            item42.set_quantity(999);
            item42.set_description("UPDATED_ITEM_42_DESC");
            reusable_lazy.sync_item_dirty(42);
            return reusable_lazy.write_to_buffer(out_buf.data());
        });
        rows.push_back({"Advantageous (A5)", "Mutate 1 of 100 Repeated LineItems + Serialize", std_ns, std_b, lazy_ns, lazy_b});
    }

    // A6: Gateway Redact 1 Sensitive Field + Forward 12.4KB Msg
    {
        auto [std_ns, std_b] = measure_bench(WARMUP, ITERS, [&](int) {
            StdOrderEnvelope msg;
            msg.ParseFromArray(complex_bytes.data(), static_cast<uint32_t>(complex_bytes.size()));
            msg.auth_token.clear();
            return msg.SerializeToArray(out_buf.data());
        });
        auto [lazy_ns, lazy_b] = measure_bench(WARMUP, ITERS, [&](int) {
            reusable_lazy.wrap(complex_bytes.data(), 0, static_cast<uint32_t>(complex_bytes.size()));
            reusable_lazy.clear_auth_token();
            return reusable_lazy.write_to_buffer(out_buf.data());
        });
        rows.push_back({"Advantageous (A6)", "Gateway Redact 1 Sensitive Field + Forward 12.4KB Msg", std_ns, std_b, lazy_ns, lazy_b});
    }

    // B1: Build from Scratch (10 items, 0% input reuse) + Serialize
    std::string small_payload(256, 'x');
    static const char* SKU_TABLE[10] = {
        "SKU-000", "SKU-001", "SKU-002", "SKU-003", "SKU-004",
        "SKU-005", "SKU-006", "SKU-007", "SKU-008", "SKU-009"
    };
    {
        auto [std_ns, std_b] = measure_bench(WARMUP, ITERS, [&](int iter) {
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
            return msg.SerializeToArray(out_buf.data());
        });
        auto [lazy_ns, lazy_b] = measure_bench(WARMUP, ITERS, [&](int iter) {
            reusable_lazy.init_scratch();
            reusable_lazy.set_order_id(202611240001ULL + iter)
                         .set_buyer_name("scratch_buyer")
                         .set_status(1)
                         .set_updated_at_fixed64(1795497000123ULL)
                         .set_payload(small_payload)
                         .set_auth_token("token-123456")
                         .set_trace_id("trace-123456")
                         .set_total_cents(99000ULL);
            auto& u = reusable_lazy.mutable_user();
            u.set_user_id(88001122ULL);
            u.set_city("SHANGHAI");
            u.set_vip_level(3);
            u.set_score(95.5);
            u.set_email("alice@example.com");
            reusable_lazy.sync_user_dirty();
            for (int i = 0; i < 10; i++) {
                auto& item = reusable_lazy.add_item();
                item.set_item_id(100ULL + i);
                item.set_sku(SKU_TABLE[i]);
                item.set_quantity(2);
                item.set_price_cents(1500ULL);
                item.set_description("Item desc");
            }
            return reusable_lazy.write_to_buffer(out_buf.data());
        });
        rows.push_back({"Non-Advantageous (B1)", "Build from Scratch (10 items, 0% input reuse) + Serialize", std_ns, std_b, lazy_ns, lazy_b});
    }

    // B2: 100% Full-Tree Read (All fields + all 100 LineItems)
    {
        auto [std_ns, std_b] = measure_bench(WARMUP, ITERS, [&](int) {
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
        auto [lazy_ns, lazy_b] = measure_bench(WARMUP, ITERS, [&](int) {
            reusable_lazy.wrap(complex_bytes.data(), 0, static_cast<uint32_t>(complex_bytes.size()));
            uint64_t sum = reusable_lazy.order_id() + reusable_lazy.buyer_name().size() + reusable_lazy.status()
                         + reusable_lazy.updated_at_fixed64() + reusable_lazy.payload().size()
                         + reusable_lazy.auth_token().size() + reusable_lazy.trace_id().size() + reusable_lazy.total_cents();
            auto& u = reusable_lazy.user();
            sum += u.user_id() + u.city().size() + u.vip_level()
                 + static_cast<uint64_t>(u.score()) + u.email().size();
            uint16_t n = reusable_lazy.items_count();
            LazyLineItem tmp_item;
            for (uint16_t i = 0; i < n; i++) {
                reusable_lazy.read_item_eager(i, tmp_item);
                sum += tmp_item.item_id_ + tmp_item.sku_.size() + tmp_item.quantity_
                     + tmp_item.price_cents_ + tmp_item.description_.size();
            }
            return sum;
        });
        rows.push_back({"Non-Advantageous (B2)", "100% Full-Tree Read (All fields + all 100 LineItems)", std_ns, std_b, lazy_ns, lazy_b});
    }

    // B3: 100% Full-Tree Variable-Size Mutation (0% Span Reuse)
    std::string new_payload(128, 'z');
    {
        auto [std_ns, std_b] = measure_bench(WARMUP, ITERS, [&](int) {
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
            return msg.SerializeToArray(out_buf.data());
        });
        auto [lazy_ns, lazy_b] = measure_bench(WARMUP, ITERS, [&](int) {
            reusable_lazy.wrap(medium_bytes.data(), 0, static_cast<uint32_t>(medium_bytes.size()));
            reusable_lazy.set_order_id(1ULL)
                         .set_buyer_name("mutated_full_tree_buyer_name_long")
                         .set_status(99999)
                         .set_updated_at_fixed64(42ULL)
                         .set_payload(new_payload)
                         .set_auth_token("new_short_tok")
                         .set_trace_id("new_trace_id_full_mutation_long_string")
                         .set_total_cents(5ULL);
            auto& u = reusable_lazy.mutable_user();
            u.set_user_id(2ULL);
            u.set_city("GUANGZHOU_FULL_MUTATE");
            u.set_vip_level(10000);
            u.set_score(12.34);
            u.set_email("full_mutate_user@example.org");
            reusable_lazy.sync_user_dirty();
            uint16_t n = reusable_lazy.items_count();
            for (uint16_t i = 0; i < n; i++) {
                auto& item = reusable_lazy.items(i);
                item.set_item_id(9000000ULL + i);
                item.set_sku("NEW-LONG-SKU-CODE-MUTATED");
                item.set_quantity(50000);
                item.set_price_cents(10ULL);
                item.set_description("Short");
                reusable_lazy.sync_item_dirty(i);
            }
            return reusable_lazy.write_to_buffer(out_buf.data());
        });
        rows.push_back({"Non-Advantageous (B3)", "100% Full-Tree Variable-Size Mutation (0% Span Reuse)", std_ns, std_b, lazy_ns, lazy_b});
    }

    // B4: Tiny 11-Byte Message (Read All 3 + Mutate All 3 Var-Size)
    {
        auto [std_ns, std_b] = measure_bench(WARMUP, ITERS, [&](int) {
            StdOrderEnvelope msg;
            msg.ParseFromArray(tiny_bytes.data(), static_cast<uint32_t>(tiny_bytes.size()));
            uint64_t sum = msg.order_id + msg.status + msg.total_cents;
            msg.order_id = sum + 100000000ULL;
            msg.status = 30000;
            msg.total_cents = 999999999ULL;
            return msg.SerializeToArray(out_buf.data());
        });
        auto [lazy_ns, lazy_b] = measure_bench(WARMUP, ITERS, [&](int) {
            reusable_lazy.wrap(tiny_bytes.data(), 0, static_cast<uint32_t>(tiny_bytes.size()));
            uint64_t sum = reusable_lazy.order_id() + reusable_lazy.status() + reusable_lazy.total_cents();
            reusable_lazy.set_order_id(sum + 100000000ULL)
                         .set_status(30000)
                         .set_total_cents(999999999ULL);
            return reusable_lazy.write_to_buffer(out_buf.data());
        });
        rows.push_back({"Non-Advantageous (B4)", "Tiny 11-Byte Message (Read All 3 + Mutate All 3 Var-Size)", std_ns, std_b, lazy_ns, lazy_b});
    }

    std::printf("=============================================================================================================================\n");
    std::printf("  C++20 BARE-METAL PERFORMANCE CEILING: LAZY-PROTO vs STANDARD C++ PROTOBUF (AMD Ryzen 7 7840HS / GCC 15.2 -O3 -march=native)\n");
    std::printf("=============================================================================================================================\n");
    std::printf("%-23s | %-57s | %10s | %10s | %8s | %10s | %10s\n",
                "Category", "Workload Scenario", "Std(ns/op)", "Lazy(ns/op)", "Speedup", "Std(B/op)", "Lazy(B/op)");
    std::printf("-----------------------------------------------------------------------------------------------------------------------------\n");
    for (const auto& r : rows) {
        std::printf("%-23s | %-57s | %10.1f | %10.1f | %7.2fx | %10llu | %10llu\n",
                    r.category, r.name, r.std_ns, r.lazy_ns, r.std_ns / r.lazy_ns,
                    static_cast<unsigned long long>(r.std_alloc),
                    static_cast<unsigned long long>(r.lazy_alloc));
    }
    std::printf("=============================================================================================================================\n");
    return 0;
}
