#include "lazy_proto.hpp"
#include "std_proto.hpp"
#include "order_envelope.pb.h"

#include <chrono>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <google/protobuf/stubs/common.h>
#include <google/protobuf/unknown_field_set.h>
#include <new>
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
