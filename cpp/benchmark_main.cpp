#include "lazy_proto.hpp"
#include "std_proto.hpp"
#include "order_envelope.pb.h"

#include <cassert>
#include <chrono>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <google/protobuf/stubs/common.h>
#include <google/protobuf/unknown_field_set.h>
#include <new>
#include <string>
#include <vector>

using namespace lazyproto;
using namespace stdproto;
namespace pb = lazyproto::bench;

// ============================================================================
// Global Heap Allocation Counter (tracks malloc/new bytes across C++ & libprotobuf)
// ============================================================================
static thread_local uint64_t g_thread_alloc_bytes = 0;

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

// ============================================================================
// Test Fixture Generator (Built with Official protoc/libprotobuf C++ classes)
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
    std::vector<uint8_t> out(sz + 16, 0);
    size_t written = env.SerializeToArray(out.data());
    assert(written == sz);
    out.resize(sz);

    // Cross-check with official libprotobuf parser
    pb::OrderEnvelope pb_check;
    assert(pb_check.ParseFromArray(out.data(), static_cast<int>(out.size())));
    assert(pb_check.order_id() == 202611240001ULL);
    assert(pb_check.items_size() == item_count);
    assert(pb_check.GetReflection()->GetUnknownFields(pb_check).field_count() == 2);
    return out;
}

// ============================================================================
// Correctness Verification Suite (Cross-checked against Official libprotobuf)
// ============================================================================
void run_correctness_tests() {
    std::printf("[Correctness] Running C++20 Lazy-Proto vs Official libprotobuf verification suite...\n");
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

        pb::OrderEnvelope pb_decoded;
        assert(pb_decoded.ParseFromArray(out_buf.data(), static_cast<int>(len)));
        assert(pb_decoded.status() == 2);
        assert(pb_decoded.updated_at_fixed64() == 1800000000999ULL);
        assert(pb_decoded.user().city() == "BJ");
        assert(pb_decoded.user().score() == 99.875);
        assert(pb_decoded.user().vip_level() == 5);
        assert(pb_decoded.items(7).quantity() == 9);
        assert(pb_decoded.GetReflection()->GetUnknownFields(pb_decoded).field_count() == 2);
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
        pb::OrderEnvelope pb_decoded;
        assert(pb_decoded.ParseFromArray(out_buf.data(), static_cast<int>(len)));
        assert(pb_decoded.buyer_name() == "alexander_the_great_vip_customer_01");
        assert(pb_decoded.status() == 50000);
        assert(pb_decoded.user().city() == "SHANGHAI_PUDONG_NEW_AREA");
        assert(pb_decoded.user().email() == "alice@example.com");
        assert(pb_decoded.items_size() == 100);
        assert(pb_decoded.items(42).description() == "UPDATED_SHORT_DESC");
        assert(pb_decoded.items(42).quantity() == 9999);
        assert(pb_decoded.items(42).sku() == "SKU-PRO-0042");
        assert(pb_decoded.items(99).sku() == "SKU-PRO-0099");
        assert(pb_decoded.GetReflection()->GetUnknownFields(pb_decoded).field_count() == 2);
    }

    // 4. A6: Gateway Field Redaction (clear_auth_token)
    {
        LazyOrderEnvelope lazy;
        lazy.wrap(raw.data(), 0, static_cast<uint32_t>(raw.size()));
        lazy.clear_auth_token();
        size_t len = lazy.write_to_buffer(out_buf.data());
        assert(len < raw.size());

        pb::OrderEnvelope pb_decoded;
        assert(pb_decoded.ParseFromArray(out_buf.data(), static_cast<int>(len)));
        assert(pb_decoded.auth_token().empty());
        assert(pb_decoded.trace_id() == "trace-00112233445566778899aabbccddeeff");
        assert(pb_decoded.GetReflection()->GetUnknownFields(pb_decoded).field_count() == 2);
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
        pb::OrderEnvelope pb_decoded;
        assert(pb_decoded.ParseFromArray(out_buf.data(), static_cast<int>(len)));
        assert(pb_decoded.order_id() == 999888777ULL);
        assert(pb_decoded.user().city() == "HANGZHOU");
        assert(pb_decoded.items_size() == 10);
    }

    std::printf("[Correctness] All C++20 Lazy-Proto vs Official libprotobuf checks PASSED.\n\n");
}

// ============================================================================
// Benchmark Harness (Comparable Lifecycle & Output Buffer Modes)
// ============================================================================
struct BenchMetric {
    double ns;
    uint64_t alloc_bytes;
};

struct FullBenchRow {
    const char* category;
    const char* name;
    BenchMetric pb_fresh_alloc;    // Official protoc Message: Fresh parse + Alloc serialized string/vec
    BenchMetric pb_reuse_buf;      // Official protoc Message: Reuse Msg object + Reuse out_buf (SerializeToArray)
    BenchMetric hand_eager;        // Hand-Written Eager C++ (std_proto.hpp) + Reuse out_buf
    BenchMetric lazy_alloc_out;    // Lazy-Proto C++20: Stack wrap + Alloc output string
    BenchMetric lazy_reuse_buf;    // Lazy-Proto C++20: Stack wrap + Reuse out_buf (write_to_buffer)
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

template <typename LazyFn>
size_t serialize_lazy_to_fresh_string(LazyOrderEnvelope& lazy, std::string& scratch_unused, uint8_t* tmp_buf) {
    // Helper unused; see direct string-backed allocation helper below
    (void)lazy; (void)scratch_unused; (void)tmp_buf;
    return 0;
}

int main() {
    GOOGLE_PROTOBUF_VERIFY_VERSION;
    run_correctness_tests();

    std::vector<uint8_t> complex_bytes = create_complex_order_bytes(100, 4096);
    std::vector<uint8_t> medium_bytes  = create_complex_order_bytes(20, 256);

    pb::OrderEnvelope tiny_pb;
    tiny_pb.set_order_id(1001LL);
    tiny_pb.set_status(1);
    tiny_pb.set_total_cents(5000LL);
    std::string tiny_str;
    assert(tiny_pb.SerializeToString(&tiny_str));
    std::vector<uint8_t> tiny_bytes(tiny_str.begin(), tiny_str.end());

    std::vector<uint8_t> out_buf(complex_bytes.size() + 4096);
    LazyOrderEnvelope reusable_lazy;
    pb::OrderEnvelope reusable_pb;

    constexpr int WARMUP = 8000;
    constexpr int ITERS  = 30000;

    std::vector<FullBenchRow> rows;

    // A1: Partial Read Routing (Read 3 fields out of 12.4 KB message, no serialization)
    {
        auto m_pb_fresh = measure_bench(WARMUP, ITERS, [&](int) {
            pb::OrderEnvelope msg;
            msg.ParseFromArray(complex_bytes.data(), static_cast<int>(complex_bytes.size()));
            return static_cast<uint64_t>(msg.order_id() + msg.status() + msg.user().vip_level());
        });
        auto m_pb_reuse = measure_bench(WARMUP, ITERS, [&](int) {
            reusable_pb.ParseFromArray(complex_bytes.data(), static_cast<int>(complex_bytes.size()));
            return static_cast<uint64_t>(reusable_pb.order_id() + reusable_pb.status() + reusable_pb.user().vip_level());
        });
        auto m_hand = measure_bench(WARMUP, ITERS, [&](int) {
            StdOrderEnvelope msg;
            msg.ParseFromArray(complex_bytes.data(), static_cast<uint32_t>(complex_bytes.size()));
            return msg.order_id + msg.status + msg.user.vip_level;
        });
        auto m_lazy = measure_bench(WARMUP, ITERS, [&](int) {
            reusable_lazy.wrap(complex_bytes.data(), 0, static_cast<uint32_t>(complex_bytes.size()));
            return reusable_lazy.order_id() + reusable_lazy.status() + reusable_lazy.user().vip_level();
        });
        rows.push_back({"Advantageous (A1)", "Partial Read (3 fields from 12.4KB)", m_pb_fresh, m_pb_reuse, m_hand, m_lazy, m_lazy});
    }

    // A2: Inspect 2 fields + Unmodified Passthrough (Fast-Path 0)
    {
        auto m_pb_fresh = measure_bench(WARMUP, ITERS, [&](int) -> size_t {
            pb::OrderEnvelope msg;
            msg.ParseFromArray(complex_bytes.data(), static_cast<int>(complex_bytes.size()));
            if (msg.order_id() > 0 && msg.status() == 1) {
                std::string out;
                msg.SerializeToString(&out);
                return out.size();
            }
            return 0;
        });
        auto m_pb_reuse = measure_bench(WARMUP, ITERS, [&](int) -> size_t {
            reusable_pb.ParseFromArray(complex_bytes.data(), static_cast<int>(complex_bytes.size()));
            if (reusable_pb.order_id() > 0 && reusable_pb.status() == 1) {
                int sz = static_cast<int>(reusable_pb.ByteSizeLong());
                reusable_pb.SerializeToArray(out_buf.data(), sz);
                return static_cast<size_t>(sz);
            }
            return 0;
        });
        auto m_hand = measure_bench(WARMUP, ITERS, [&](int) -> size_t {
            StdOrderEnvelope msg;
            msg.ParseFromArray(complex_bytes.data(), static_cast<uint32_t>(complex_bytes.size()));
            if (msg.order_id > 0 && msg.status == 1) {
                return msg.SerializeToArray(out_buf.data());
            }
            return 0;
        });
        auto m_lazy_alloc = measure_bench(WARMUP, ITERS, [&](int) -> size_t {
            reusable_lazy.wrap(complex_bytes.data(), 0, static_cast<uint32_t>(complex_bytes.size()));
            if (reusable_lazy.order_id() > 0 && reusable_lazy.status() == 1) {
                std::string out(reusable_lazy.raw_len_, '\0');
                return reusable_lazy.write_to_buffer(reinterpret_cast<uint8_t*>(out.data()));
            }
            return 0;
        });
        auto m_lazy_reuse = measure_bench(WARMUP, ITERS, [&](int) -> size_t {
            reusable_lazy.wrap(complex_bytes.data(), 0, static_cast<uint32_t>(complex_bytes.size()));
            if (reusable_lazy.order_id() > 0 && reusable_lazy.status() == 1) {
                return reusable_lazy.write_to_buffer(out_buf.data());
            }
            return 0;
        });
        rows.push_back({"Advantageous (A2)", "Inspect 2 + Passthrough (Fast-Path 0)", m_pb_fresh, m_pb_reuse, m_hand, m_lazy_alloc, m_lazy_reuse});
    }

    // A3: Modify 3 Same-Size/Fixed Fields + Serialize (Fast-Path 1)
    {
        auto m_pb_fresh = measure_bench(WARMUP, ITERS, [&](int iter) {
            pb::OrderEnvelope msg;
            msg.ParseFromArray(complex_bytes.data(), static_cast<int>(complex_bytes.size()));
            msg.set_status(2);
            msg.set_updated_at_fixed64(1800000000000ULL + (iter & 0xFF));
            msg.mutable_user()->set_score(98.75);
            std::string out;
            msg.SerializeToString(&out);
            return out.size();
        });
        auto m_pb_reuse = measure_bench(WARMUP, ITERS, [&](int iter) {
            reusable_pb.ParseFromArray(complex_bytes.data(), static_cast<int>(complex_bytes.size()));
            reusable_pb.set_status(2);
            reusable_pb.set_updated_at_fixed64(1800000000000ULL + (iter & 0xFF));
            reusable_pb.mutable_user()->set_score(98.75);
            int sz = static_cast<int>(reusable_pb.ByteSizeLong());
            reusable_pb.SerializeToArray(out_buf.data(), sz);
            return static_cast<size_t>(sz);
        });
        auto m_hand = measure_bench(WARMUP, ITERS, [&](int iter) {
            StdOrderEnvelope msg;
            msg.ParseFromArray(complex_bytes.data(), static_cast<uint32_t>(complex_bytes.size()));
            msg.status = 2;
            msg.updated_at_fixed64 = 1800000000000ULL + (iter & 0xFF);
            msg.user.score = 98.75;
            return msg.SerializeToArray(out_buf.data());
        });
        auto m_lazy_alloc = measure_bench(WARMUP, ITERS, [&](int iter) {
            reusable_lazy.wrap(complex_bytes.data(), 0, static_cast<uint32_t>(complex_bytes.size()));
            reusable_lazy.set_status(2);
            reusable_lazy.set_updated_at_fixed64(1800000000000ULL + (iter & 0xFF));
            reusable_lazy.mutable_user().set_score(98.75);
            reusable_lazy.sync_user_dirty();
            std::string out(reusable_lazy.raw_len_, '\0');
            return reusable_lazy.write_to_buffer(reinterpret_cast<uint8_t*>(out.data()));
        });
        auto m_lazy_reuse = measure_bench(WARMUP, ITERS, [&](int iter) {
            reusable_lazy.wrap(complex_bytes.data(), 0, static_cast<uint32_t>(complex_bytes.size()));
            reusable_lazy.set_status(2);
            reusable_lazy.set_updated_at_fixed64(1800000000000ULL + (iter & 0xFF));
            reusable_lazy.mutable_user().set_score(98.75);
            reusable_lazy.sync_user_dirty();
            return reusable_lazy.write_to_buffer(out_buf.data());
        });
        rows.push_back({"Advantageous (A3)", "3 Fixed/Same-Size Edits (Fast-Path 1)", m_pb_fresh, m_pb_reuse, m_hand, m_lazy_alloc, m_lazy_reuse});
    }

    // A4: Modify Variable-Len String & Nested City + Splice (Fast-Path 2)
    {
        auto m_pb_fresh = measure_bench(WARMUP, ITERS, [&](int) {
            pb::OrderEnvelope msg;
            msg.ParseFromArray(complex_bytes.data(), static_cast<int>(complex_bytes.size()));
            msg.set_buyer_name("alexander_the_great_vip_buyer");
            msg.set_status(50000);
            msg.mutable_user()->set_city("SHANGHAI_PUDONG");
            std::string out;
            msg.SerializeToString(&out);
            return out.size();
        });
        auto m_pb_reuse = measure_bench(WARMUP, ITERS, [&](int) {
            reusable_pb.ParseFromArray(complex_bytes.data(), static_cast<int>(complex_bytes.size()));
            reusable_pb.set_buyer_name("alexander_the_great_vip_buyer");
            reusable_pb.set_status(50000);
            reusable_pb.mutable_user()->set_city("SHANGHAI_PUDONG");
            int sz = static_cast<int>(reusable_pb.ByteSizeLong());
            reusable_pb.SerializeToArray(out_buf.data(), sz);
            return static_cast<size_t>(sz);
        });
        auto m_hand = measure_bench(WARMUP, ITERS, [&](int) {
            StdOrderEnvelope msg;
            msg.ParseFromArray(complex_bytes.data(), static_cast<uint32_t>(complex_bytes.size()));
            msg.buyer_name = "alexander_the_great_vip_buyer";
            msg.status = 50000;
            msg.user.city = "SHANGHAI_PUDONG";
            return msg.SerializeToArray(out_buf.data());
        });
        auto m_lazy_alloc = measure_bench(WARMUP, ITERS, [&](int) {
            reusable_lazy.wrap(complex_bytes.data(), 0, static_cast<uint32_t>(complex_bytes.size()));
            reusable_lazy.set_buyer_name("alexander_the_great_vip_buyer").set_status(50000);
            reusable_lazy.mutable_user().set_city("SHANGHAI_PUDONG");
            reusable_lazy.sync_user_dirty();
            std::string out(complex_bytes.size() + 256, '\0');
            size_t len = reusable_lazy.write_to_buffer(reinterpret_cast<uint8_t*>(out.data()));
            out.resize(len);
            return out.size();
        });
        auto m_lazy_reuse = measure_bench(WARMUP, ITERS, [&](int) {
            reusable_lazy.wrap(complex_bytes.data(), 0, static_cast<uint32_t>(complex_bytes.size()));
            reusable_lazy.set_buyer_name("alexander_the_great_vip_buyer").set_status(50000);
            reusable_lazy.mutable_user().set_city("SHANGHAI_PUDONG");
            reusable_lazy.sync_user_dirty();
            return reusable_lazy.write_to_buffer(out_buf.data());
        });
        rows.push_back({"Advantageous (A4)", "Var-Size String + Nested City (Path 2)", m_pb_fresh, m_pb_reuse, m_hand, m_lazy_alloc, m_lazy_reuse});
    }

    // A5: Mutate 1 of 100 Repeated LineItems + Single-Pass Splice
    {
        auto m_pb_fresh = measure_bench(WARMUP, ITERS, [&](int) {
            pb::OrderEnvelope msg;
            msg.ParseFromArray(complex_bytes.data(), static_cast<int>(complex_bytes.size()));
            msg.mutable_items(42)->set_quantity(999);
            msg.mutable_items(42)->set_description("UPDATED_ITEM_42_DESC");
            std::string out;
            msg.SerializeToString(&out);
            return out.size();
        });
        auto m_pb_reuse = measure_bench(WARMUP, ITERS, [&](int) {
            reusable_pb.ParseFromArray(complex_bytes.data(), static_cast<int>(complex_bytes.size()));
            reusable_pb.mutable_items(42)->set_quantity(999);
            reusable_pb.mutable_items(42)->set_description("UPDATED_ITEM_42_DESC");
            int sz = static_cast<int>(reusable_pb.ByteSizeLong());
            reusable_pb.SerializeToArray(out_buf.data(), sz);
            return static_cast<size_t>(sz);
        });
        auto m_hand = measure_bench(WARMUP, ITERS, [&](int) {
            StdOrderEnvelope msg;
            msg.ParseFromArray(complex_bytes.data(), static_cast<uint32_t>(complex_bytes.size()));
            msg.items[42].quantity = 999;
            msg.items[42].description = "UPDATED_ITEM_42_DESC";
            return msg.SerializeToArray(out_buf.data());
        });
        auto m_lazy_alloc = measure_bench(WARMUP, ITERS, [&](int) {
            reusable_lazy.wrap(complex_bytes.data(), 0, static_cast<uint32_t>(complex_bytes.size()));
            auto& item42 = reusable_lazy.items(42);
            item42.set_quantity(999);
            item42.set_description("UPDATED_ITEM_42_DESC");
            reusable_lazy.sync_item_dirty(42);
            std::string out(complex_bytes.size() + 256, '\0');
            size_t len = reusable_lazy.write_to_buffer(reinterpret_cast<uint8_t*>(out.data()));
            out.resize(len);
            return out.size();
        });
        auto m_lazy_reuse = measure_bench(WARMUP, ITERS, [&](int) {
            reusable_lazy.wrap(complex_bytes.data(), 0, static_cast<uint32_t>(complex_bytes.size()));
            auto& item42 = reusable_lazy.items(42);
            item42.set_quantity(999);
            item42.set_description("UPDATED_ITEM_42_DESC");
            reusable_lazy.sync_item_dirty(42);
            return reusable_lazy.write_to_buffer(out_buf.data());
        });
        rows.push_back({"Advantageous (A5)", "Mutate 1 of 100 Repeated Items", m_pb_fresh, m_pb_reuse, m_hand, m_lazy_alloc, m_lazy_reuse});
    }

    // A6: Gateway Redact 1 Sensitive Field + Forward 12.4KB Msg
    {
        auto m_pb_fresh = measure_bench(WARMUP, ITERS, [&](int) {
            pb::OrderEnvelope msg;
            msg.ParseFromArray(complex_bytes.data(), static_cast<int>(complex_bytes.size()));
            msg.clear_auth_token();
            std::string out;
            msg.SerializeToString(&out);
            return out.size();
        });
        auto m_pb_reuse = measure_bench(WARMUP, ITERS, [&](int) {
            reusable_pb.ParseFromArray(complex_bytes.data(), static_cast<int>(complex_bytes.size()));
            reusable_pb.clear_auth_token();
            int sz = static_cast<int>(reusable_pb.ByteSizeLong());
            reusable_pb.SerializeToArray(out_buf.data(), sz);
            return static_cast<size_t>(sz);
        });
        auto m_hand = measure_bench(WARMUP, ITERS, [&](int) {
            StdOrderEnvelope msg;
            msg.ParseFromArray(complex_bytes.data(), static_cast<uint32_t>(complex_bytes.size()));
            msg.auth_token.clear();
            return msg.SerializeToArray(out_buf.data());
        });
        auto m_lazy_alloc = measure_bench(WARMUP, ITERS, [&](int) {
            reusable_lazy.wrap(complex_bytes.data(), 0, static_cast<uint32_t>(complex_bytes.size()));
            reusable_lazy.clear_auth_token();
            std::string out(complex_bytes.size(), '\0');
            size_t len = reusable_lazy.write_to_buffer(reinterpret_cast<uint8_t*>(out.data()));
            out.resize(len);
            return out.size();
        });
        auto m_lazy_reuse = measure_bench(WARMUP, ITERS, [&](int) {
            reusable_lazy.wrap(complex_bytes.data(), 0, static_cast<uint32_t>(complex_bytes.size()));
            reusable_lazy.clear_auth_token();
            return reusable_lazy.write_to_buffer(out_buf.data());
        });
        rows.push_back({"Advantageous (A6)", "Gateway Redact 1 Field + Forward", m_pb_fresh, m_pb_reuse, m_hand, m_lazy_alloc, m_lazy_reuse});
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
            msg.SerializeToString(&out);
            return out.size();
        });
        auto m_pb_reuse = measure_bench(WARMUP, ITERS, [&](int iter) {
            reusable_pb.Clear();
            populate_pb(reusable_pb, iter);
            int sz = static_cast<int>(reusable_pb.ByteSizeLong());
            reusable_pb.SerializeToArray(out_buf.data(), sz);
            return static_cast<size_t>(sz);
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
            return msg.SerializeToArray(out_buf.data());
        });
        auto populate_lazy = [&](int iter) {
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
        };
        auto m_lazy_alloc = measure_bench(WARMUP, ITERS, [&](int iter) {
            populate_lazy(iter);
            std::string out(1024, '\0');
            size_t len = reusable_lazy.write_to_buffer(reinterpret_cast<uint8_t*>(out.data()));
            out.resize(len);
            return out.size();
        });
        auto m_lazy_reuse = measure_bench(WARMUP, ITERS, [&](int iter) {
            populate_lazy(iter);
            return reusable_lazy.write_to_buffer(out_buf.data());
        });
        rows.push_back({"Non-Advantageous (B1)", "Build from Scratch (10 items)", m_pb_fresh, m_pb_reuse, m_hand, m_lazy_alloc, m_lazy_reuse});
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
        auto m_pb_fresh = measure_bench(WARMUP, ITERS, [&](int) {
            pb::OrderEnvelope msg;
            msg.ParseFromArray(complex_bytes.data(), static_cast<int>(complex_bytes.size()));
            return read_pb(msg);
        });
        auto m_pb_reuse = measure_bench(WARMUP, ITERS, [&](int) {
            reusable_pb.ParseFromArray(complex_bytes.data(), static_cast<int>(complex_bytes.size()));
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
        auto m_lazy = measure_bench(WARMUP, ITERS, [&](int) {
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
        rows.push_back({"Non-Advantageous (B2)", "100% Full-Tree Read (100 Items)", m_pb_fresh, m_pb_reuse, m_hand, m_lazy, m_lazy});
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
            msg.ParseFromArray(medium_bytes.data(), static_cast<int>(medium_bytes.size()));
            mutate_pb(msg);
            std::string out;
            msg.SerializeToString(&out);
            return out.size();
        });
        auto m_pb_reuse = measure_bench(WARMUP, ITERS, [&](int) {
            reusable_pb.ParseFromArray(medium_bytes.data(), static_cast<int>(medium_bytes.size()));
            mutate_pb(reusable_pb);
            int sz = static_cast<int>(reusable_pb.ByteSizeLong());
            reusable_pb.SerializeToArray(out_buf.data(), sz);
            return static_cast<size_t>(sz);
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
            return msg.SerializeToArray(out_buf.data());
        });
        auto mutate_lazy = [&]() {
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
        };
        auto m_lazy_alloc = measure_bench(WARMUP, ITERS, [&](int) {
            mutate_lazy();
            std::string out(medium_bytes.size() + 1024, '\0');
            size_t len = reusable_lazy.write_to_buffer(reinterpret_cast<uint8_t*>(out.data()));
            out.resize(len);
            return out.size();
        });
        auto m_lazy_reuse = measure_bench(WARMUP, ITERS, [&](int) {
            mutate_lazy();
            return reusable_lazy.write_to_buffer(out_buf.data());
        });
        rows.push_back({"Non-Advantageous (B3)", "100% Full-Tree Var-Size Mutate", m_pb_fresh, m_pb_reuse, m_hand, m_lazy_alloc, m_lazy_reuse});
    }

    // B4: Tiny 11-Byte Message
    {
        auto m_pb_fresh = measure_bench(WARMUP, ITERS, [&](int) {
            pb::OrderEnvelope msg;
            msg.ParseFromArray(tiny_bytes.data(), static_cast<int>(tiny_bytes.size()));
            uint64_t sum = static_cast<uint64_t>(msg.order_id() + msg.status() + msg.total_cents());
            msg.set_order_id(static_cast<int64_t>(sum + 100000000ULL));
            msg.set_status(30000);
            msg.set_total_cents(999999999LL);
            std::string out;
            msg.SerializeToString(&out);
            return out.size();
        });
        auto m_pb_reuse = measure_bench(WARMUP, ITERS, [&](int) {
            reusable_pb.ParseFromArray(tiny_bytes.data(), static_cast<int>(tiny_bytes.size()));
            uint64_t sum = static_cast<uint64_t>(reusable_pb.order_id() + reusable_pb.status() + reusable_pb.total_cents());
            reusable_pb.set_order_id(static_cast<int64_t>(sum + 100000000ULL));
            reusable_pb.set_status(30000);
            reusable_pb.set_total_cents(999999999LL);
            int sz = static_cast<int>(reusable_pb.ByteSizeLong());
            reusable_pb.SerializeToArray(out_buf.data(), sz);
            return static_cast<size_t>(sz);
        });
        auto m_hand = measure_bench(WARMUP, ITERS, [&](int) {
            StdOrderEnvelope msg;
            msg.ParseFromArray(tiny_bytes.data(), static_cast<uint32_t>(tiny_bytes.size()));
            uint64_t sum = msg.order_id + msg.status + msg.total_cents;
            msg.order_id = sum + 100000000ULL;
            msg.status = 30000;
            msg.total_cents = 999999999ULL;
            return msg.SerializeToArray(out_buf.data());
        });
        auto m_lazy_alloc = measure_bench(WARMUP, ITERS, [&](int) {
            reusable_lazy.wrap(tiny_bytes.data(), 0, static_cast<uint32_t>(tiny_bytes.size()));
            uint64_t sum = reusable_lazy.order_id() + reusable_lazy.status() + reusable_lazy.total_cents();
            reusable_lazy.set_order_id(sum + 100000000ULL).set_status(30000).set_total_cents(999999999ULL);
            std::string out(64, '\0');
            size_t len = reusable_lazy.write_to_buffer(reinterpret_cast<uint8_t*>(out.data()));
            out.resize(len);
            return out.size();
        });
        auto m_lazy_reuse = measure_bench(WARMUP, ITERS, [&](int) {
            reusable_lazy.wrap(tiny_bytes.data(), 0, static_cast<uint32_t>(tiny_bytes.size()));
            uint64_t sum = reusable_lazy.order_id() + reusable_lazy.status() + reusable_lazy.total_cents();
            reusable_lazy.set_order_id(sum + 100000000ULL).set_status(30000).set_total_cents(999999999ULL);
            return reusable_lazy.write_to_buffer(out_buf.data());
        });
        rows.push_back({"Non-Advantageous (B4)", "Tiny 11-Byte Message (Read+Mutate)", m_pb_fresh, m_pb_reuse, m_hand, m_lazy_alloc, m_lazy_reuse});
    }

    int ver_major = GOOGLE_PROTOBUF_VERSION / 1000000;
    int ver_minor = (GOOGLE_PROTOBUF_VERSION / 1000) % 1000;
    int ver_patch = GOOGLE_PROTOBUF_VERSION % 1000;
    std::printf("==========================================================================================================================================================\n");
    std::printf("  C++20 BENCHMARK: Official libprotobuf %d.%d.%d (-lprotobuf) vs Hand-Written Eager vs Lazy-Proto C++20 | GCC %s (-O3 -march=native -std=c++20)\n",
                ver_major, ver_minor, ver_patch, __VERSION__);
    std::printf("  Note: Fresh = new Message + alloc std::string; Reuse = reused Message.ParseFromArray/Clear + reused out_buf. Allocations tracked via operator new.\n");
    std::printf("==========================================================================================================================================================\n");
    std::printf("%-21s | %-36s | %10s | %10s | %10s | %10s | %10s | %9s | %9s\n",
                "Category", "Workload Scenario", "PB-Fresh", "PB-Reuse", "HandEager", "Lazy-Alloc", "Lazy-Reuse", "vsPB-Fsh", "vsPB-Rse");
    std::printf("----------------------------------------------------------------------------------------------------------------------------------------------------------\n");
    for (const auto& r : rows) {
        std::printf("%-21s | %-36s | %10.1f | %10.1f | %10.1f | %10.1f | %10.1f | %8.2fx | %8.2fx\n",
                    r.category, r.name,
                    r.pb_fresh_alloc.ns, r.pb_reuse_buf.ns, r.hand_eager.ns,
                    r.lazy_alloc_out.ns, r.lazy_reuse_buf.ns,
                    r.pb_fresh_alloc.ns / r.lazy_alloc_out.ns,
                    r.pb_reuse_buf.ns / r.lazy_reuse_buf.ns);
    }
    std::printf("----------------------------------------------------------------------------------------------------------------------------------------------------------\n");
    std::printf("  HEAP ALLOCATIONS (Bytes/op):\n");
    std::printf("%-21s | %-36s | %10s | %10s | %10s | %10s | %10s\n",
                "Category", "Workload Scenario", "PB-Fresh", "PB-Reuse", "HandEager", "Lazy-Alloc", "Lazy-Reuse");
    std::printf("----------------------------------------------------------------------------------------------------------------------------------------------------------\n");
    for (const auto& r : rows) {
        std::printf("%-21s | %-36s | %10llu | %10llu | %10llu | %10llu | %10llu\n",
                    r.category, r.name,
                    static_cast<unsigned long long>(r.pb_fresh_alloc.alloc_bytes),
                    static_cast<unsigned long long>(r.pb_reuse_buf.alloc_bytes),
                    static_cast<unsigned long long>(r.hand_eager.alloc_bytes),
                    static_cast<unsigned long long>(r.lazy_alloc_out.alloc_bytes),
                    static_cast<unsigned long long>(r.lazy_reuse_buf.alloc_bytes));
    }
    std::printf("==========================================================================================================================================================\n");

    google::protobuf::ShutdownProtobufLibrary();
    return 0;
}
