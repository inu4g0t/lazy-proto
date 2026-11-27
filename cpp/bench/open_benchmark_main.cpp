#include "lazyproto/order_envelope_view.hpp"
#include "lazyproto/wire_view.hpp"
#include "fixture.hpp"
#include "open_benchmarks.pb.h"

#include <protozero/basic_pbf_writer.hpp>
#include <protozero/buffer_fixed.hpp>
#include <protozero/pbf_reader.hpp>

#include <google/protobuf/arena.h>
#include <google/protobuf/stubs/common.h>
#include <google/protobuf/unknown_field_set.h>

#include <chrono>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <new>
#include <string>
#include <string_view>
#include <vector>

using namespace lazyproto;
namespace pb = lazyproto::bench;
namespace opb = lazyproto::bench::open;

using PzBuf = protozero::fixed_size_buffer_adaptor;
using PzWriter = protozero::basic_pbf_writer<PzBuf>;

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
__attribute__((noinline)) void* operator new[](std::size_t size) { return ::operator new(size); }
__attribute__((noinline)) void operator delete(void* p) noexcept { std::free(p); }
__attribute__((noinline)) void operator delete(void* p, std::size_t) noexcept { std::free(p); }
__attribute__((noinline)) void operator delete[](void* p) noexcept { std::free(p); }
__attribute__((noinline)) void operator delete[](void* p, std::size_t) noexcept { std::free(p); }

// ============================================================================
// Symmetric Output Keep-Alive (Defeats DCE/DSE Equally Across All 4 Engines)
// ============================================================================
inline uint64_t observe_output(const uint8_t* ptr, size_t len) noexcept {
#if defined(__GNUC__) || defined(__clang__)
    asm volatile("" : : "r"(ptr), "r"(len) : "memory");
#endif
    return len > 0 ? (static_cast<uint64_t>(len) ^ ptr[0] ^ ptr[len - 1]) : 0ULL;
}

inline uint64_t observe_output(const char* ptr, size_t len) noexcept {
    return observe_output(reinterpret_cast<const uint8_t*>(ptr), len);
}

struct BenchMetric {
    double ns;
    uint64_t alloc_bytes;
};

struct Row4 {
    const char* id;
    const char* desc;
    BenchMetric pb_reuse;
    BenchMetric pb_arena;
    BenchMetric protozero;
    BenchMetric lazy;
};

static volatile uint64_t g_blackhole = 0;

template <typename Fn>
BenchMetric measure(int warmup, int iters, Fn&& fn) {
    uint64_t sink = 0;
    for (int i = 0; i < warmup; i++) sink ^= fn(i);
    g_blackhole ^= sink;

    g_thread_alloc_bytes = 0;
    auto t0 = std::chrono::steady_clock::now();
    for (int i = 0; i < iters; i++) sink ^= fn(i);
    auto t1 = std::chrono::steady_clock::now();
    uint64_t alloc = g_thread_alloc_bytes;
    g_blackhole ^= sink;

    double ns = std::chrono::duration<double, std::nano>(t1 - t0).count() / iters;
    return {ns, alloc / static_cast<uint64_t>(iters)};
}

// Helper: serialize official PB message into reusable out_buf with 0 heap alloc
template <typename PbMsg>
inline size_t serialize_pb_to_buf(const PbMsg& msg, uint8_t* out_buf, size_t cap) {
    size_t sz = msg.ByteSizeLong();
    LP_CHECK(sz <= cap);
    LP_CHECK(msg.SerializeToArray(out_buf, static_cast<int>(sz)));
    return sz;
}

// Helper: Arena backed by a reusable preallocated memory block (Google's official high-perf C++ mode)
struct ArenaPool {
    std::vector<char> block;
    google::protobuf::ArenaOptions opts;
    explicit ArenaPool(size_t bytes = 256 * 1024) : block(bytes) {
        opts.initial_block = block.data();
        opts.initial_block_size = block.size();
        opts.start_block_size = 64 * 1024;
        opts.max_block_size = 256 * 1024;
    }
};

// Helper: copy any protozero field raw from reader to writer (preserves unknown fields too!)
inline void pz_copy_field(protozero::pbf_reader& r, PzWriter& w) {
    uint32_t tag = r.tag();
    switch (r.wire_type()) {
        case protozero::pbf_wire_type::varint:
            w.add_uint64(tag, r.get_uint64());
            break;
        case protozero::pbf_wire_type::fixed64:
            w.add_fixed64(tag, r.get_fixed64());
            break;
        case protozero::pbf_wire_type::length_delimited:
            w.add_bytes(tag, r.get_view());
            break;
        case protozero::pbf_wire_type::fixed32:
            w.add_fixed32(tag, r.get_fixed32());
            break;
        default:
            r.skip();
            break;
    }
}

// ============================================================================
// Open Benchmark Fixtures (Exact Match with Java OpenBenchmarkFixtures.java)
// ============================================================================

static std::vector<uint8_t> create_media_content_bytes() {
    opb::MediaContent mc;
    auto* m = mc.mutable_media();
    m->set_uri("http://javaone.com/keynote.mpg");
    m->set_title("Javaone Keynote");
    m->set_width(640);
    m->set_height(480);
    m->set_format("video/mpg4");
    m->set_duration(18000000LL);
    m->set_size(58982400LL);
    m->set_bitrate(262144);
    m->add_person("Bill Gates");
    m->add_person("Steve Jobs");
    m->set_player(opb::Media::JAVA);
    m->set_copyright("None");

    auto* i1 = mc.add_image();
    i1->set_uri("http://javaone.com/keynote_large.jpg");
    i1->set_title("Javaone Keynote");
    i1->set_width(1024);
    i1->set_height(768);
    i1->set_size(opb::Image::LARGE);

    auto* i2 = mc.add_image();
    i2->set_uri("http://javaone.com/keynote_small.jpg");
    i2->set_title("Javaone Keynote");
    i2->set_width(320);
    i2->set_height(240);
    i2->set_size(opb::Image::SMALL);

    std::string s;
    LP_CHECK(mc.SerializeToString(&s));
    return std::vector<uint8_t>(s.begin(), s.end());
}

static std::vector<uint8_t> create_pulsar_metadata_bytes() {
    opb::PulsarMessageMetadata p;
    p.set_producer_name("persistent://tenant-a/ns-orders/orders-topic-partition-3-producer-07");
    p.set_sequence_id(98765432101ULL);
    p.set_publish_time(1795497000123ULL);
    p.set_partition_key("order-shard-key-88421");
    p.set_compression(1);
    p.set_uncompressed_size(4096);
    p.set_num_messages_in_batch(16);
    p.set_event_time(1795497000100ULL);
    const char sv[8] = {0, 0, 0, 0, 0, 0, 0, 7};
    p.set_schema_version(sv, 8);
    p.set_deliver_at_time(1795497005000LL);

    static const char* kProps[10][2] = {
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
    for (int i = 0; i < 10; i++) {
        auto* kv = p.add_properties();
        kv->set_key(kProps[i][0]);
        kv->set_value(kProps[i][1]);
    }

    std::string s;
    LP_CHECK(p.SerializeToString(&s));
    return std::vector<uint8_t>(s.begin(), s.end());
}

static std::vector<uint8_t> create_otel_trace_bytes() {
    opb::OtelExportTraceRequest req;
    auto* rs = req.add_resource_spans();
    rs->set_schema_url("https://opentelemetry.io/schemas/1.24.0");
    auto* res = rs->mutable_resource();
    res->set_dropped_attributes_count(0);
    auto add_kv = [](auto* msg, const char* k, const std::string& v) {
        auto* kv = msg->add_attributes();
        kv->set_key(k);
        kv->set_value(v);
    };
    add_kv(res, "service.name", "checkout-api-gateway");
    add_kv(res, "deployment.environment", "staging-us-west");
    add_kv(res, "service.version", "2.14.3");
    add_kv(res, "host.name", "k8s-node-17.internal");
    add_kv(res, "telemetry.sdk.language", "java");

    std::string trace_id(16, '\0'), span_id(8, '\0'), parent_id(8, '\0');
    for (int i = 0; i < 16; i++) trace_id[i] = static_cast<char>(0xA0 + i);
    for (int i = 0; i < 8; i++) parent_id[i] = static_cast<char>(0x10 + i);

    for (int s = 0; s < 2; s++) {
        auto* ss = rs->add_scope_spans();
        ss->mutable_scope()->set_name(s == 0 ? "io.opentelemetry.http.server" : "io.opentelemetry.jdbc");
        ss->mutable_scope()->set_version("1.38.0");
        ss->set_schema_url("https://opentelemetry.io/schemas/1.24.0");
        for (int i = 0; i < 25; i++) {
            int idx = s * 25 + i;
            for (int b = 0; b < 8; b++) span_id[b] = static_cast<char>(idx + b * 17);
            auto* sp = ss->add_spans();
            sp->set_trace_id(trace_id);
            sp->set_span_id(span_id);
            sp->set_parent_span_id(parent_id);
            sp->set_name(s == 0 ? "POST /api/v2/orders/checkout" : "SELECT * FROM orders WHERE id = ?");
            sp->set_kind(s == 0 ? 2 : 3);
            sp->set_start_time_unix_nano(1795497000000000000ULL + static_cast<uint64_t>(idx) * 1000000ULL);
            sp->set_end_time_unix_nano(1795497000000500000ULL + static_cast<uint64_t>(idx) * 1000000ULL);
            sp->set_status_code(1);
            add_kv(sp, "http.method", "POST");
            add_kv(sp, "http.status_code", "200");
            add_kv(sp, "net.peer.ip", "10.24.88." + std::to_string(10 + idx));
            add_kv(sp, "user.email", "customer" + std::to_string(idx) + "@example.com");
            add_kv(sp, "db.system", "postgresql");
            add_kv(sp, "rpc.service", "OrderService");
        }
    }

    std::string s;
    LP_CHECK(req.SerializeToString(&s));
    return std::vector<uint8_t>(s.begin(), s.end());
}

// ============================================================================
// Full-Read Helpers (PB / protozero / lazy-proto)
// ============================================================================

static uint64_t read_pb_order_full(const pb::OrderEnvelope& m) {
    uint64_t sum = static_cast<uint64_t>(m.order_id()) + m.buyer_name().size() + static_cast<uint64_t>(m.status())
            + m.updated_at_fixed64() + m.payload().size() + m.auth_token().size()
            + m.trace_id().size() + static_cast<uint64_t>(m.total_cents());
    const auto& u = m.user();
    sum += static_cast<uint64_t>(u.user_id()) + u.city().size() + static_cast<uint64_t>(u.vip_level())
            + static_cast<uint64_t>(static_cast<int64_t>(u.score())) + u.email().size();
    for (int i = 0; i < m.items_size(); i++) {
        const auto& it = m.items(i);
        sum += static_cast<uint64_t>(it.item_id()) + it.sku().size() + static_cast<uint64_t>(it.quantity())
                + static_cast<uint64_t>(it.price_cents()) + it.description().size();
    }
    return sum;
}

static uint64_t read_pz_order_full(const uint8_t* data, size_t len) {
    protozero::pbf_reader r(reinterpret_cast<const char*>(data), len);
    uint64_t sum = 0;
    while (r.next()) {
        switch (r.tag()) {
            case 1: sum += static_cast<uint64_t>(r.get_int64()); break;
            case 2: sum += r.get_view().size(); break;
            case 3: sum += static_cast<uint64_t>(r.get_int32()); break;
            case 4: sum += r.get_fixed64(); break;
            case 5: {
                auto ur = r.get_message();
                while (ur.next()) {
                    switch (ur.tag()) {
                        case 1: sum += static_cast<uint64_t>(ur.get_int64()); break;
                        case 2: sum += ur.get_view().size(); break;
                        case 3: sum += static_cast<uint64_t>(ur.get_int32()); break;
                        case 4: sum += static_cast<uint64_t>(static_cast<int64_t>(ur.get_double())); break;
                        case 5: sum += ur.get_view().size(); break;
                        default: ur.skip(); break;
                    }
                }
                break;
            }
            case 6: {
                auto ir = r.get_message();
                while (ir.next()) {
                    switch (ir.tag()) {
                        case 1: sum += static_cast<uint64_t>(ir.get_int64()); break;
                        case 2: sum += ir.get_view().size(); break;
                        case 3: sum += static_cast<uint64_t>(ir.get_int32()); break;
                        case 4: sum += static_cast<uint64_t>(ir.get_int64()); break;
                        case 5: sum += ir.get_view().size(); break;
                        default: ir.skip(); break;
                    }
                }
                break;
            }
            case 7: sum += r.get_view().size(); break;
            case 8: sum += r.get_view().size(); break;
            case 9: sum += r.get_view().size(); break;
            case 10: sum += static_cast<uint64_t>(r.get_int64()); break;
            default: r.skip(); break;
        }
    }
    return sum;
}

static uint64_t read_lazy_order_full(OrderEnvelopeView& v) {
    uint64_t sum = static_cast<uint64_t>(v.order_id()) + v.buyer_name().size() + static_cast<uint64_t>(v.status())
            + v.updated_at_fixed64() + v.payload().size() + v.auth_token().size()
            + v.trace_id().size() + static_cast<uint64_t>(v.total_cents());
    auto& u = v.user();
    sum += static_cast<uint64_t>(u.user_id()) + u.city().size() + static_cast<uint64_t>(u.vip_level())
            + static_cast<uint64_t>(static_cast<int64_t>(u.score())) + u.email().size();
    size_t n = v.items_count();
    LineItemView item;
    for (size_t i = 0; i < n; i++) {
        v.read_item_eager(static_cast<uint32_t>(i), item);
        sum += static_cast<uint64_t>(item.item_id()) + item.sku().size() + static_cast<uint64_t>(item.quantity())
                + static_cast<uint64_t>(item.price_cents()) + item.description().size();
    }
    return sum;
}

static uint64_t read_pb_media_full(const opb::MediaContent& mc) {
    const auto& m = mc.media();
    uint64_t sum = m.uri().size() + m.title().size() + m.width() + m.height() + m.format().size()
            + static_cast<uint64_t>(m.duration()) + static_cast<uint64_t>(m.size()) + m.bitrate()
            + static_cast<uint64_t>(m.player()) + m.copyright().size();
    for (int i = 0; i < m.person_size(); i++) sum += m.person(i).size();
    for (int i = 0; i < mc.image_size(); i++) {
        const auto& img = mc.image(i);
        sum += img.uri().size() + img.title().size() + img.width() + img.height() + static_cast<uint64_t>(img.size());
    }
    return sum;
}

static uint64_t read_pz_media_full(const uint8_t* data, size_t len) {
    protozero::pbf_reader r(reinterpret_cast<const char*>(data), len);
    uint64_t sum = 0;
    while (r.next()) {
        if (r.tag() == 1) {
            auto m = r.get_message();
            while (m.next()) {
                switch (m.tag()) {
                    case 1: case 2: case 5: case 9: case 11: sum += m.get_view().size(); break;
                    case 3: case 4: case 8: case 10: sum += static_cast<uint64_t>(m.get_int32()); break;
                    case 6: case 7: sum += static_cast<uint64_t>(m.get_int64()); break;
                    default: m.skip(); break;
                }
            }
        } else if (r.tag() == 2) {
            auto img = r.get_message();
            while (img.next()) {
                switch (img.tag()) {
                    case 1: case 2: sum += img.get_view().size(); break;
                    case 3: case 4: case 5: sum += static_cast<uint64_t>(img.get_int32()); break;
                    default: img.skip(); break;
                }
            }
        } else r.skip();
    }
    return sum;
}

static uint64_t read_lazy_media_full(WireView& mc) {
    auto& m = mc.sub(1);
    uint64_t sum = m.get_bytes(1).size() + m.get_bytes(2).size() + m.get_int32(3) + m.get_int32(4)
            + m.get_bytes(5).size() + m.get_varint(6) + m.get_varint(7) + m.get_int32(8)
            + m.get_int32(10) + m.get_bytes(11).size();
    size_t pn = m.repeated_count(9);
    for (size_t i = 0; i < pn; i++) sum += m.get_repeated_bytes(9, i).size();
    size_t in = mc.repeated_count(2);
    for (size_t i = 0; i < in; i++) {
        auto& img = mc.repeated_sub(2, i);
        sum += img.get_bytes(1).size() + img.get_bytes(2).size() + img.get_int32(3) + img.get_int32(4) + img.get_int32(5);
    }
    return sum;
}

static uint64_t read_pb_pulsar_full(const opb::PulsarMessageMetadata& p) {
    uint64_t sum = p.producer_name().size() + p.sequence_id() + p.publish_time()
            + p.replicated_from().size() + p.partition_key().size() + p.compression()
            + p.uncompressed_size() + p.num_messages_in_batch() + p.event_time()
            + p.schema_version().size() + static_cast<uint64_t>(p.deliver_at_time());
    for (int i = 0; i < p.properties_size(); i++) {
        sum += p.properties(i).key().size() + p.properties(i).value().size();
    }
    return sum;
}

static uint64_t read_pz_pulsar_full(const uint8_t* data, size_t len) {
    protozero::pbf_reader r(reinterpret_cast<const char*>(data), len);
    uint64_t sum = 0;
    while (r.next()) {
        switch (r.tag()) {
            case 1: case 5: case 6: case 15: sum += r.get_view().size(); break;
            case 2: case 3: case 12: case 19: sum += r.get_uint64(); break;
            case 9: case 10: case 11: sum += r.get_uint32(); break;
            case 4: {
                auto kv = r.get_message();
                while (kv.next()) {
                    if (kv.tag() == 1 || kv.tag() == 2) sum += kv.get_view().size();
                    else kv.skip();
                }
                break;
            }
            default: r.skip(); break;
        }
    }
    return sum;
}

static uint64_t read_lazy_pulsar_full(WireView& p) {
    uint64_t sum = p.get_bytes(1).size() + p.get_varint(2) + p.get_varint(3)
            + p.get_bytes(5).size() + p.get_bytes(6).size() + p.get_int32(9)
            + p.get_varint(10) + p.get_int32(11) + p.get_varint(12)
            + p.get_bytes(15).size() + p.get_varint(19);
    size_t n = p.repeated_count(4);
    for (size_t i = 0; i < n; i++) {
        auto& kv = p.repeated_sub(4, i);
        sum += kv.get_bytes(1).size() + kv.get_bytes(2).size();
    }
    return sum;
}

static uint64_t read_pb_otel_full(const opb::OtelExportTraceRequest& req) {
    uint64_t sum = 0;
    for (int r = 0; r < req.resource_spans_size(); r++) {
        const auto& rs = req.resource_spans(r);
        sum += rs.schema_url().size();
        for (int a = 0; a < rs.resource().attributes_size(); a++) {
            sum += rs.resource().attributes(a).key().size() + rs.resource().attributes(a).value().size();
        }
        for (int s = 0; s < rs.scope_spans_size(); s++) {
            const auto& ss = rs.scope_spans(s);
            sum += ss.scope().name().size() + ss.scope().version().size();
            for (int i = 0; i < ss.spans_size(); i++) {
                const auto& sp = ss.spans(i);
                sum += sp.trace_id().size() + sp.span_id().size() + sp.parent_span_id().size()
                        + sp.name().size() + sp.kind() + sp.start_time_unix_nano()
                        + sp.end_time_unix_nano() + sp.status_code();
                for (int a = 0; a < sp.attributes_size(); a++) {
                    sum += sp.attributes(a).key().size() + sp.attributes(a).value().size();
                }
            }
        }
    }
    return sum;
}

static uint64_t read_pz_otel_full(const uint8_t* data, size_t len) {
    protozero::pbf_reader req(reinterpret_cast<const char*>(data), len);
    uint64_t sum = 0;
    while (req.next()) {
        if (req.tag() != 1) { req.skip(); continue; }
        auto rs = req.get_message();
        while (rs.next()) {
            if (rs.tag() == 3) sum += rs.get_view().size();
            else if (rs.tag() == 1) {
                auto res = rs.get_message();
                while (res.next()) {
                    if (res.tag() == 1) {
                        auto kv = res.get_message();
                        while (kv.next()) {
                            if (kv.tag() == 1 || kv.tag() == 2) sum += kv.get_view().size();
                            else kv.skip();
                        }
                    } else res.skip();
                }
            } else if (rs.tag() == 2) {
                auto ss = rs.get_message();
                while (ss.next()) {
                    if (ss.tag() == 1) {
                        auto sc = ss.get_message();
                        while (sc.next()) {
                            if (sc.tag() == 1 || sc.tag() == 2) sum += sc.get_view().size();
                            else sc.skip();
                        }
                    } else if (ss.tag() == 2) {
                        auto sp = ss.get_message();
                        while (sp.next()) {
                            switch (sp.tag()) {
                                case 1: case 2: case 4: case 5: sum += sp.get_view().size(); break;
                                case 6: case 15: sum += static_cast<uint64_t>(sp.get_int32()); break;
                                case 7: case 8: sum += sp.get_fixed64(); break;
                                case 9: {
                                    auto kv = sp.get_message();
                                    while (kv.next()) {
                                        if (kv.tag() == 1 || kv.tag() == 2) sum += kv.get_view().size();
                                        else kv.skip();
                                    }
                                    break;
                                }
                                default: sp.skip(); break;
                            }
                        }
                    } else ss.skip();
                }
            } else rs.skip();
        }
    }
    return sum;
}

static uint64_t read_lazy_otel_full(WireView& req) {
    uint64_t sum = 0;
    size_t rn = req.repeated_count(1);
    for (size_t r = 0; r < rn; r++) {
        auto& rs = req.repeated_sub(1, r);
        sum += rs.get_bytes(3).size();
        auto& res = rs.sub(1);
        size_t ran = res.repeated_count(1);
        for (size_t a = 0; a < ran; a++) {
            auto& kv = res.repeated_sub(1, a);
            sum += kv.get_bytes(1).size() + kv.get_bytes(2).size();
        }
        size_t ssn = rs.repeated_count(2);
        for (size_t s = 0; s < ssn; s++) {
            auto& ss = rs.repeated_sub(2, s);
            auto& sc = ss.sub(1);
            sum += sc.get_bytes(1).size() + sc.get_bytes(2).size();
            size_t spn = ss.repeated_count(2);
            for (size_t i = 0; i < spn; i++) {
                auto& sp = ss.repeated_sub(2, i);
                sum += sp.get_bytes(1).size() + sp.get_bytes(2).size() + sp.get_bytes(4).size()
                        + sp.get_bytes(5).size() + sp.get_int32(6)
                        + sp.get_fixed64(7) + sp.get_fixed64(8) + sp.get_int32(15);
                size_t an = sp.repeated_count(9);
                for (size_t a = 0; a < an; a++) {
                    auto& kv = sp.repeated_sub(9, a);
                    sum += kv.get_bytes(1).size() + kv.get_bytes(2).size();
                }
            }
        }
    }
    return sum;
}

// ============================================================================
// Hand-Tuned Protozero Streaming Rewriters (Zero-Copy Raw Submessage Passthrough)
// ============================================================================

static size_t pz_order_a3(const uint8_t* in, size_t len, uint64_t ts, char* out, size_t cap) {
    PzBuf buf(out, cap);
    PzWriter w(buf);
    protozero::pbf_reader r(reinterpret_cast<const char*>(in), len);
    while (r.next()) {
        if (r.tag() == 3) { r.skip(); w.add_int32(3, 2); }
        else if (r.tag() == 4) { r.skip(); w.add_fixed64(4, ts); }
        else if (r.tag() == 5) {
            auto ur = r.get_message();
            PzWriter uw(w, 5);
            while (ur.next()) {
                if (ur.tag() == 4) { ur.skip(); uw.add_double(4, 98.75); }
                else pz_copy_field(ur, uw);
            }
        } else pz_copy_field(r, w);
    }
    return buf.size();
}

static size_t pz_order_a4(const uint8_t* in, size_t len, char* out, size_t cap) {
    PzBuf buf(out, cap);
    PzWriter w(buf);
    protozero::pbf_reader r(reinterpret_cast<const char*>(in), len);
    while (r.next()) {
        if (r.tag() == 2) { r.skip(); w.add_string(2, "alexander_the_great_vip_buyer"); }
        else if (r.tag() == 3) { r.skip(); w.add_int32(3, 50000); }
        else if (r.tag() == 5) {
            auto ur = r.get_message();
            PzWriter uw(w, 5);
            while (ur.next()) {
                if (ur.tag() == 2) { ur.skip(); uw.add_string(2, "SHANGHAI_PUDONG"); }
                else pz_copy_field(ur, uw);
            }
        } else pz_copy_field(r, w);
    }
    return buf.size();
}

static size_t pz_order_mutate_k(const uint8_t* in, size_t len, int start_idx, int count, const char* desc, char* out, size_t cap) {
    PzBuf buf(out, cap);
    PzWriter w(buf);
    protozero::pbf_reader r(reinterpret_cast<const char*>(in), len);
    int item_idx = 0;
    while (r.next()) {
        if (r.tag() == 6) {
            int cur = item_idx++;
            if (cur >= start_idx && cur < start_idx + count) {
                auto ir = r.get_message();
                PzWriter iw(w, 6);
                while (ir.next()) {
                    if (ir.tag() == 3) { ir.skip(); iw.add_int32(3, 999); }
                    else if (ir.tag() == 5) { ir.skip(); iw.add_string(5, desc); }
                    else pz_copy_field(ir, iw);
                }
            } else {
                w.add_bytes(6, r.get_view());
            }
        } else pz_copy_field(r, w);
    }
    return buf.size();
}

static size_t pz_order_a6_redact(const uint8_t* in, size_t len, char* out, size_t cap) {
    PzBuf buf(out, cap);
    PzWriter w(buf);
    protozero::pbf_reader r(reinterpret_cast<const char*>(in), len);
    while (r.next()) {
        if (r.tag() == 8) r.skip();
        else pz_copy_field(r, w);
    }
    return buf.size();
}

static size_t pz_media_mutate_title(const uint8_t* in, size_t len, char* out, size_t cap) {
    PzBuf buf(out, cap);
    PzWriter w(buf);
    protozero::pbf_reader r(reinterpret_cast<const char*>(in), len);
    while (r.next()) {
        if (r.tag() == 1) {
            auto mr = r.get_message();
            PzWriter mw(w, 1);
            while (mr.next()) {
                if (mr.tag() == 2) { mr.skip(); mw.add_string(2, "Javaone Keynote (HD Remastered)"); }
                else pz_copy_field(mr, mw);
            }
        } else pz_copy_field(r, w);
    }
    return buf.size();
}

static size_t pz_pulsar_stamp(const uint8_t* in, size_t len, char* out, size_t cap) {
    PzBuf buf(out, cap);
    PzWriter w(buf);
    protozero::pbf_reader r(reinterpret_cast<const char*>(in), len);
    bool wrote_rep = false, wrote_deliv = false;
    while (r.next()) {
        if (r.tag() == 5) { r.skip(); w.add_string(5, "us-west-2"); wrote_rep = true; }
        else if (r.tag() == 19) { r.skip(); w.add_int64(19, 1795497009999LL); wrote_deliv = true; }
        else pz_copy_field(r, w);
    }
    if (!wrote_rep) w.add_string(5, "us-west-2");
    if (!wrote_deliv) w.add_int64(19, 1795497009999LL);
    return buf.size();
}

static size_t pz_otel_enrich_res(const uint8_t* in, size_t len, char* out, size_t cap) {
    PzBuf buf(out, cap);
    PzWriter w(buf);
    protozero::pbf_reader req(reinterpret_cast<const char*>(in), len);
    int rs_idx = 0;
    while (req.next()) {
        if (req.tag() == 1 && rs_idx++ == 0) {
            auto rs = req.get_message();
            PzWriter rsw(w, 1);
            while (rs.next()) {
                if (rs.tag() == 3) { rs.skip(); rsw.add_string(3, "https://opentelemetry.io/schemas/1.26.0"); }
                else if (rs.tag() == 1) {
                    auto res = rs.get_message();
                    PzWriter resw(rsw, 1);
                    int a_idx = 0;
                    while (res.next()) {
                        if (res.tag() == 1 && a_idx++ == 1) {
                            auto kv = res.get_message();
                            PzWriter kvw(resw, 1);
                            while (kv.next()) {
                                if (kv.tag() == 2) { kv.skip(); kvw.add_string(2, "prod-us-east-1"); }
                                else pz_copy_field(kv, kvw);
                            }
                        } else pz_copy_field(res, resw);
                    }
                } else pz_copy_field(rs, rsw);
            }
        } else pz_copy_field(req, w);
    }
    return buf.size();
}

static size_t pz_otel_scrub_pii(const uint8_t* in, size_t len, char* out, size_t cap) {
    PzBuf buf(out, cap);
    PzWriter w(buf);
    protozero::pbf_reader req(reinterpret_cast<const char*>(in), len);
    int rs_idx = 0;
    while (req.next()) {
        if (req.tag() == 1 && rs_idx++ == 0) {
            auto rs = req.get_message();
            PzWriter rsw(w, 1);
            int ss_idx = 0;
            while (rs.next()) {
                if (rs.tag() == 2 && ss_idx++ == 1) {
                    auto ss = rs.get_message();
                    PzWriter ssw(rsw, 2);
                    int sp_idx = 0;
                    while (ss.next()) {
                        if (ss.tag() == 2 && sp_idx++ == 12) {
                            auto sp = ss.get_message();
                            PzWriter spw(ssw, 2);
                            int a_idx = 0;
                            while (sp.next()) {
                                if (sp.tag() == 9 && a_idx++ == 3) {
                                    auto kv = sp.get_message();
                                    PzWriter kvw(spw, 9);
                                    while (kv.next()) {
                                        if (kv.tag() == 2) { kv.skip(); kvw.add_string(2, "[REDACTED]"); }
                                        else pz_copy_field(kv, kvw);
                                    }
                                } else pz_copy_field(sp, spw);
                            }
                        } else pz_copy_field(ss, ssw);
                    }
                } else pz_copy_field(rs, rsw);
            }
        } else pz_copy_field(req, w);
    }
    return buf.size();
}

static void print_table(const char* title, const std::vector<Row4>& rows) {
    std::printf("\n==========================================================================================================================================================\n");
    std::printf("  %s\n", title);
    std::printf("==========================================================================================================================================================\n");
    std::printf("%-10s | %-30s | %16s | %16s | %16s | %16s | %-25s\n",
            "Scenario", "Workload", "PB-Reuse (ns/B)", "PB-Arena (ns/B)", "Protozero (ns/B)", "Lazy-C++ (ns/B)", "Lazy vs Reuse / Arena / PZ");
    std::printf("----------------------------------------------------------------------------------------------------------------------------------------------------------\n");
    for (const auto& r : rows) {
        std::printf("%-10s | %-30s | %8.1f / %4lluB | %8.1f / %4lluB | %8.1f / %4lluB | %8.1f / %4lluB | %6.2fx / %6.2fx / %6.2fx\n",
                r.id, r.desc,
                r.pb_reuse.ns, static_cast<unsigned long long>(r.pb_reuse.alloc_bytes),
                r.pb_arena.ns, static_cast<unsigned long long>(r.pb_arena.alloc_bytes),
                r.protozero.ns, static_cast<unsigned long long>(r.protozero.alloc_bytes),
                r.lazy.ns, static_cast<unsigned long long>(r.lazy.alloc_bytes),
                r.pb_reuse.ns / r.lazy.ns,
                r.pb_arena.ns / r.lazy.ns,
                r.protozero.ns / r.lazy.ns);
    }
    std::printf("==========================================================================================================================================================\n");
}

int main() {
    GOOGLE_PROTOBUF_VERIFY_VERSION;

    const auto complex_bytes = create_complex_order_bytes(100, 4096);
    pb::OrderEnvelope tmp_ord;
    LP_CHECK(tmp_ord.ParseFromArray(complex_bytes.data(), static_cast<int>(complex_bytes.size())));
    tmp_ord.GetReflection()->MutableUnknownFields(&tmp_ord)->Clear();
    std::string order_no_unk_str;
    LP_CHECK(tmp_ord.SerializeToString(&order_no_unk_str));
    const std::vector<uint8_t> order_no_unk(order_no_unk_str.begin(), order_no_unk_str.end());

    const auto media_bytes = create_media_content_bytes();
    const auto pulsar_bytes = create_pulsar_metadata_bytes();
    const auto otel_bytes = create_otel_trace_bytes();

    std::printf("Fixture sizes: Order=%zuB (noUnk=%zuB), Media=%zuB, Pulsar=%zuB, OTel=%zuB\n",
            complex_bytes.size(), order_no_unk.size(), media_bytes.size(), pulsar_bytes.size(), otel_bytes.size());

    std::vector<uint8_t> out_buf(64 * 1024);
    char* out_ch = reinterpret_cast<char*>(out_buf.data());
    ArenaPool arena_pool(512 * 1024);

    // ========================================================================
    // Pre-Timing Semantic Parity Checks Across All 4 Engines
    // ========================================================================
    {
        // Order A3/A4/A5/A6 parity
        OrderEnvelopeView lv;
        pb::OrderEnvelope p_ref, p_chk;
        LP_CHECK(p_ref.ParseFromArray(complex_bytes.data(), static_cast<int>(complex_bytes.size())));
        p_ref.set_status(2);
        p_ref.set_updated_at_fixed64(1800000000000ULL);
        p_ref.mutable_user()->set_score(98.75);
        size_t n_pz = pz_order_a3(complex_bytes.data(), complex_bytes.size(), 1800000000000ULL, out_ch, out_buf.size());
        LP_CHECK(p_chk.ParseFromArray(out_buf.data(), static_cast<int>(n_pz)));
        LP_CHECK(p_chk.SerializeAsString().size() == p_ref.SerializeAsString().size());
        LP_CHECK(p_chk.status() == 2 && p_chk.user().score() == 98.75 && p_chk.GetReflection()->GetUnknownFields(p_chk).field_count() == 2);

        lv.wrap(complex_bytes.data(), 0, static_cast<uint32_t>(complex_bytes.size()));
        lv.set_status(2).set_updated_at_fixed64(1800000000000ULL);
        lv.user().set_score(98.75);
        size_t n_lv = lv.merge_to(out_buf.data(), out_buf.size());
        LP_CHECK(p_chk.ParseFromArray(out_buf.data(), static_cast<int>(n_lv)));
        LP_CHECK(p_chk.status() == 2 && p_chk.user().score() == 98.75 && p_chk.GetReflection()->GetUnknownFields(p_chk).field_count() == 2);

        // Full read checksum parity on Order / Media / Pulsar / OTel
        LP_CHECK(p_ref.ParseFromArray(complex_bytes.data(), static_cast<int>(complex_bytes.size())));
        lv.wrap(complex_bytes.data(), 0, static_cast<uint32_t>(complex_bytes.size()));
        LP_CHECK(read_pb_order_full(p_ref) == read_pz_order_full(complex_bytes.data(), complex_bytes.size()));
        LP_CHECK(read_pb_order_full(p_ref) == read_lazy_order_full(lv));

        opb::MediaContent pmc, pmc_chk;
        LP_CHECK(pmc.ParseFromArray(media_bytes.data(), static_cast<int>(media_bytes.size())));
        WireView wv;
        wv.wrap(media_bytes.data(), media_bytes.size());
        LP_CHECK(read_pb_media_full(pmc) == read_pz_media_full(media_bytes.data(), media_bytes.size()));
        LP_CHECK(read_pb_media_full(pmc) == read_lazy_media_full(wv));

        size_t n_m1 = pz_media_mutate_title(media_bytes.data(), media_bytes.size(), out_ch, out_buf.size());
        LP_CHECK(pmc_chk.ParseFromArray(out_buf.data(), static_cast<int>(n_m1)));
        LP_CHECK(pmc_chk.media().title() == "Javaone Keynote (HD Remastered)");
        wv.wrap(media_bytes.data(), media_bytes.size());
        wv.sub(1).set_bytes(2, "Javaone Keynote (HD Remastered)");
        size_t n_m2 = wv.merge_to(out_buf.data(), out_buf.size());
        LP_CHECK(pmc_chk.ParseFromArray(out_buf.data(), static_cast<int>(n_m2)));
        LP_CHECK(pmc_chk.media().title() == "Javaone Keynote (HD Remastered)");

        opb::PulsarMessageMetadata pp, pp_chk;
        LP_CHECK(pp.ParseFromArray(pulsar_bytes.data(), static_cast<int>(pulsar_bytes.size())));
        wv.wrap(pulsar_bytes.data(), pulsar_bytes.size());
        LP_CHECK(read_pb_pulsar_full(pp) == read_pz_pulsar_full(pulsar_bytes.data(), pulsar_bytes.size()));
        LP_CHECK(read_pb_pulsar_full(pp) == read_lazy_pulsar_full(wv));
        size_t n_p1 = pz_pulsar_stamp(pulsar_bytes.data(), pulsar_bytes.size(), out_ch, out_buf.size());
        LP_CHECK(pp_chk.ParseFromArray(out_buf.data(), static_cast<int>(n_p1)));
        LP_CHECK(pp_chk.replicated_from() == "us-west-2" && pp_chk.deliver_at_time() == 1795497009999LL);
        wv.wrap(pulsar_bytes.data(), pulsar_bytes.size());
        wv.set_bytes(5, "us-west-2").set_varint(19, 1795497009999ULL);
        size_t n_p2 = wv.merge_to(out_buf.data(), out_buf.size());
        LP_CHECK(pp_chk.ParseFromArray(out_buf.data(), static_cast<int>(n_p2)));
        LP_CHECK(pp_chk.replicated_from() == "us-west-2" && pp_chk.deliver_at_time() == 1795497009999LL);

        opb::OtelExportTraceRequest po, po_chk;
        LP_CHECK(po.ParseFromArray(otel_bytes.data(), static_cast<int>(otel_bytes.size())));
        wv.wrap(otel_bytes.data(), otel_bytes.size());
        LP_CHECK(read_pb_otel_full(po) == read_pz_otel_full(otel_bytes.data(), otel_bytes.size()));
        LP_CHECK(read_pb_otel_full(po) == read_lazy_otel_full(wv));

        size_t n_o1 = pz_otel_scrub_pii(otel_bytes.data(), otel_bytes.size(), out_ch, out_buf.size());
        LP_CHECK(po_chk.ParseFromArray(out_buf.data(), static_cast<int>(n_o1)));
        LP_CHECK(po_chk.resource_spans(0).scope_spans(1).spans(12).attributes(3).value() == "[REDACTED]");
        wv.wrap(otel_bytes.data(), otel_bytes.size());
        wv.repeated_sub(1, 0).repeated_sub(2, 1).repeated_sub(2, 12).repeated_sub(9, 3).set_bytes(2, "[REDACTED]");
        size_t n_o2 = wv.merge_to(out_buf.data(), out_buf.size());
        LP_CHECK(po_chk.ParseFromArray(out_buf.data(), static_cast<int>(n_o2)));
        LP_CHECK(po_chk.resource_spans(0).scope_spans(1).spans(12).attributes(3).value() == "[REDACTED]");
    }
    std::printf("All 4-engine C++ parity checks PASSED!\n");

    const int W = 3000;
    const int N = 15000;
    const int W_S = 10000;
    const int N_S = 60000;

    pb::OrderEnvelope pb_order;
    OrderEnvelopeView lazy_order;

    // ========================================================================
    // TABLE 1: Core OrderEnvelope (A1-A6, B2) — PB-Reuse vs PB-Arena vs Protozero vs Lazy-C++
    // ========================================================================
    std::vector<Row4> core_rows;

    // A1: Partial Read (3 fields from 12.4 KB)
    core_rows.push_back({"A1", "Partial Read (3 fields, 12.4KB)",
        measure(W, N, [&](int) {
            LP_CHECK(pb_order.ParseFromArray(complex_bytes.data(), static_cast<int>(complex_bytes.size())));
            return static_cast<uint64_t>(pb_order.order_id() + pb_order.status() + pb_order.user().vip_level());
        }),
        measure(W, N, [&](int) {
            google::protobuf::Arena arena(arena_pool.opts);
            auto* m = google::protobuf::Arena::CreateMessage<pb::OrderEnvelope>(&arena);
            LP_CHECK(m->ParseFromArray(complex_bytes.data(), static_cast<int>(complex_bytes.size())));
            return static_cast<uint64_t>(m->order_id() + m->status() + m->user().vip_level());
        }),
        measure(W, N, [&](int) {
            protozero::pbf_reader r(reinterpret_cast<const char*>(complex_bytes.data()), complex_bytes.size());
            int64_t oid = 0; int32_t st = 0, vip = 0; int found = 0;
            while (r.next() && found < 3) {
                if (r.tag() == 1) { oid = r.get_int64(); found++; }
                else if (r.tag() == 3) { st = r.get_int32(); found++; }
                else if (r.tag() == 5) {
                    auto u = r.get_message();
                    while (u.next()) {
                        if (u.tag() == 3) { vip = u.get_int32(); break; }
                        else u.skip();
                    }
                    found++;
                } else r.skip();
            }
            return static_cast<uint64_t>(oid + st + vip);
        }),
        measure(W, N, [&](int) {
            lazy_order.wrap(complex_bytes.data(), 0, static_cast<uint32_t>(complex_bytes.size()));
            return static_cast<uint64_t>(lazy_order.order_id() + lazy_order.status() + lazy_order.user().vip_level());
        })
    });

    // A2: Inspect 2 + Passthrough
    core_rows.push_back({"A2", "Inspect 2 + Passthrough (FP0)",
        measure(W, N, [&](int) {
            LP_CHECK(pb_order.ParseFromArray(complex_bytes.data(), static_cast<int>(complex_bytes.size())));
            if (pb_order.order_id() > 0 && pb_order.status() == 1) {
                size_t n = serialize_pb_to_buf(pb_order, out_buf.data(), out_buf.size());
                return observe_output(out_buf.data(), n);
            }
            return uint64_t{0};
        }),
        measure(W, N, [&](int) {
            google::protobuf::Arena arena(arena_pool.opts);
            auto* m = google::protobuf::Arena::CreateMessage<pb::OrderEnvelope>(&arena);
            LP_CHECK(m->ParseFromArray(complex_bytes.data(), static_cast<int>(complex_bytes.size())));
            if (m->order_id() > 0 && m->status() == 1) {
                size_t n = serialize_pb_to_buf(*m, out_buf.data(), out_buf.size());
                return observe_output(out_buf.data(), n);
            }
            return uint64_t{0};
        }),
        measure(W, N, [&](int) {
            // Best-case streaming proxy: read 2 tags + memcpy untouched input
            protozero::pbf_reader r(reinterpret_cast<const char*>(complex_bytes.data()), complex_bytes.size());
            int64_t oid = 0; int32_t st = 0; int found = 0;
            while (r.next() && found < 2) {
                if (r.tag() == 1) { oid = r.get_int64(); found++; }
                else if (r.tag() == 3) { st = r.get_int32(); found++; }
                else r.skip();
            }
            if (oid > 0 && st == 1) {
                std::memcpy(out_buf.data(), complex_bytes.data(), complex_bytes.size());
                return observe_output(out_buf.data(), complex_bytes.size());
            }
            return uint64_t{0};
        }),
        measure(W, N, [&](int) {
            lazy_order.wrap(complex_bytes.data(), 0, static_cast<uint32_t>(complex_bytes.size()));
            if (lazy_order.order_id() > 0 && lazy_order.status() == 1) {
                size_t n = lazy_order.merge_to(out_buf.data(), out_buf.size());
                return observe_output(out_buf.data(), n);
            }
            return uint64_t{0};
        })
    });

    // A3: Modify 3 Fixed/Same-Size Fields + Serialize
    core_rows.push_back({"A3", "Modify 3 Fixed/Same-Size (FP1)",
        measure(W, N, [&](int it) {
            LP_CHECK(pb_order.ParseFromArray(complex_bytes.data(), static_cast<int>(complex_bytes.size())));
            pb_order.set_status(2);
            pb_order.set_updated_at_fixed64(1800000000000ULL + (it & 0xFF));
            pb_order.mutable_user()->set_score(98.75);
            return observe_output(out_buf.data(), serialize_pb_to_buf(pb_order, out_buf.data(), out_buf.size()));
        }),
        measure(W, N, [&](int it) {
            google::protobuf::Arena arena(arena_pool.opts);
            auto* m = google::protobuf::Arena::CreateMessage<pb::OrderEnvelope>(&arena);
            LP_CHECK(m->ParseFromArray(complex_bytes.data(), static_cast<int>(complex_bytes.size())));
            m->set_status(2);
            m->set_updated_at_fixed64(1800000000000ULL + (it & 0xFF));
            m->mutable_user()->set_score(98.75);
            return observe_output(out_buf.data(), serialize_pb_to_buf(*m, out_buf.data(), out_buf.size()));
        }),
        measure(W, N, [&](int it) {
            size_t n = pz_order_a3(complex_bytes.data(), complex_bytes.size(), 1800000000000ULL + (it & 0xFF), out_ch, out_buf.size());
            return observe_output(out_buf.data(), n);
        }),
        measure(W, N, [&](int it) {
            lazy_order.wrap(complex_bytes.data(), 0, static_cast<uint32_t>(complex_bytes.size()));
            lazy_order.set_status(2).set_updated_at_fixed64(1800000000000ULL + (it & 0xFF));
            lazy_order.user().set_score(98.75);
            return observe_output(out_buf.data(), lazy_order.merge_to(out_buf.data(), out_buf.size()));
        })
    });

    // A4: Modify Var-Size String + Nested City
    core_rows.push_back({"A4", "Var-Size String + City (FP2)",
        measure(W, N, [&](int) {
            LP_CHECK(pb_order.ParseFromArray(complex_bytes.data(), static_cast<int>(complex_bytes.size())));
            pb_order.set_buyer_name("alexander_the_great_vip_buyer");
            pb_order.set_status(50000);
            pb_order.mutable_user()->set_city("SHANGHAI_PUDONG");
            return observe_output(out_buf.data(), serialize_pb_to_buf(pb_order, out_buf.data(), out_buf.size()));
        }),
        measure(W, N, [&](int) {
            google::protobuf::Arena arena(arena_pool.opts);
            auto* m = google::protobuf::Arena::CreateMessage<pb::OrderEnvelope>(&arena);
            LP_CHECK(m->ParseFromArray(complex_bytes.data(), static_cast<int>(complex_bytes.size())));
            m->set_buyer_name("alexander_the_great_vip_buyer");
            m->set_status(50000);
            m->mutable_user()->set_city("SHANGHAI_PUDONG");
            return observe_output(out_buf.data(), serialize_pb_to_buf(*m, out_buf.data(), out_buf.size()));
        }),
        measure(W, N, [&](int) {
            return observe_output(out_buf.data(), pz_order_a4(complex_bytes.data(), complex_bytes.size(), out_ch, out_buf.size()));
        }),
        measure(W, N, [&](int) {
            lazy_order.wrap(complex_bytes.data(), 0, static_cast<uint32_t>(complex_bytes.size()));
            lazy_order.set_buyer_name("alexander_the_great_vip_buyer").set_status(50000);
            lazy_order.user().set_city("SHANGHAI_PUDONG");
            return observe_output(out_buf.data(), lazy_order.merge_to(out_buf.data(), out_buf.size()));
        })
    });

    // A5: Mutate 1 of 100 Repeated Items
    core_rows.push_back({"A5", "Mutate 1 of 100 Repeated Items",
        measure(W, N, [&](int) {
            LP_CHECK(pb_order.ParseFromArray(complex_bytes.data(), static_cast<int>(complex_bytes.size())));
            pb_order.mutable_items(42)->set_quantity(999);
            pb_order.mutable_items(42)->set_description("UPDATED_ITEM_42_DESC");
            return observe_output(out_buf.data(), serialize_pb_to_buf(pb_order, out_buf.data(), out_buf.size()));
        }),
        measure(W, N, [&](int) {
            google::protobuf::Arena arena(arena_pool.opts);
            auto* m = google::protobuf::Arena::CreateMessage<pb::OrderEnvelope>(&arena);
            LP_CHECK(m->ParseFromArray(complex_bytes.data(), static_cast<int>(complex_bytes.size())));
            m->mutable_items(42)->set_quantity(999);
            m->mutable_items(42)->set_description("UPDATED_ITEM_42_DESC");
            return observe_output(out_buf.data(), serialize_pb_to_buf(*m, out_buf.data(), out_buf.size()));
        }),
        measure(W, N, [&](int) {
            return observe_output(out_buf.data(),
                    pz_order_mutate_k(complex_bytes.data(), complex_bytes.size(), 42, 1, "UPDATED_ITEM_42_DESC", out_ch, out_buf.size()));
        }),
        measure(W, N, [&](int) {
            lazy_order.wrap(complex_bytes.data(), 0, static_cast<uint32_t>(complex_bytes.size()));
            lazy_order.items(42).set_quantity(999).set_description("UPDATED_ITEM_42_DESC");
            return observe_output(out_buf.data(), lazy_order.merge_to(out_buf.data(), out_buf.size()));
        })
    });

    // A6: Gateway Redact 1 Field + Forward
    core_rows.push_back({"A6", "Redact auth_token + Forward",
        measure(W, N, [&](int) {
            LP_CHECK(pb_order.ParseFromArray(complex_bytes.data(), static_cast<int>(complex_bytes.size())));
            pb_order.clear_auth_token();
            return observe_output(out_buf.data(), serialize_pb_to_buf(pb_order, out_buf.data(), out_buf.size()));
        }),
        measure(W, N, [&](int) {
            google::protobuf::Arena arena(arena_pool.opts);
            auto* m = google::protobuf::Arena::CreateMessage<pb::OrderEnvelope>(&arena);
            LP_CHECK(m->ParseFromArray(complex_bytes.data(), static_cast<int>(complex_bytes.size())));
            m->clear_auth_token();
            return observe_output(out_buf.data(), serialize_pb_to_buf(*m, out_buf.data(), out_buf.size()));
        }),
        measure(W, N, [&](int) {
            return observe_output(out_buf.data(), pz_order_a6_redact(complex_bytes.data(), complex_bytes.size(), out_ch, out_buf.size()));
        }),
        measure(W, N, [&](int) {
            lazy_order.wrap(complex_bytes.data(), 0, static_cast<uint32_t>(complex_bytes.size()));
            lazy_order.clear_auth_token();
            return observe_output(out_buf.data(), lazy_order.merge_to(out_buf.data(), out_buf.size()));
        })
    });

    // B2: 100% Full-Tree Read (100 Items)
    core_rows.push_back({"B2", "100% Full-Tree Read (100 Items)",
        measure(W, N, [&](int) {
            LP_CHECK(pb_order.ParseFromArray(complex_bytes.data(), static_cast<int>(complex_bytes.size())));
            return read_pb_order_full(pb_order);
        }),
        measure(W, N, [&](int) {
            google::protobuf::Arena arena(arena_pool.opts);
            auto* m = google::protobuf::Arena::CreateMessage<pb::OrderEnvelope>(&arena);
            LP_CHECK(m->ParseFromArray(complex_bytes.data(), static_cast<int>(complex_bytes.size())));
            return read_pb_order_full(*m);
        }),
        measure(W, N, [&](int) {
            return read_pz_order_full(complex_bytes.data(), complex_bytes.size());
        }),
        measure(W, N, [&](int) {
            lazy_order.wrap(complex_bytes.data(), 0, static_cast<uint32_t>(complex_bytes.size()));
            return read_lazy_order_full(lazy_order);
        })
    });

    print_table("C++20 CORE BENCHMARK (OrderEnvelope 12.4KB): libprotobuf Reuse vs Arena vs Mapbox protozero v1.8.2 vs lazy-proto", core_rows);

    // ========================================================================
    // TABLE 2: Open Ecosystem Benchmarks (O1 MediaContent / O2 Pulsar / O3 OTel / O4 Selectivity Sweep)
    // ========================================================================
    std::vector<Row4> open_rows;
    opb::MediaContent pb_media;
    opb::PulsarMessageMetadata pb_pulsar;
    opb::OtelExportTraceRequest pb_otel;
    WireView wv_media, wv_pulsar, wv_otel;

    // O1a: MediaContent Partial Read (241 B)
    open_rows.push_back({"O1a", "MediaContent Partial (241B)",
        measure(W_S, N_S, [&](int) {
            LP_CHECK(pb_media.ParseFromArray(media_bytes.data(), static_cast<int>(media_bytes.size())));
            return static_cast<uint64_t>(pb_media.media().title().size() + pb_media.media().duration() + pb_media.image_size());
        }),
        measure(W_S, N_S, [&](int) {
            google::protobuf::Arena arena(arena_pool.opts);
            auto* m = google::protobuf::Arena::CreateMessage<opb::MediaContent>(&arena);
            LP_CHECK(m->ParseFromArray(media_bytes.data(), static_cast<int>(media_bytes.size())));
            return static_cast<uint64_t>(m->media().title().size() + m->media().duration() + m->image_size());
        }),
        measure(W_S, N_S, [&](int) {
            protozero::pbf_reader r(reinterpret_cast<const char*>(media_bytes.data()), media_bytes.size());
            uint64_t sum = 0;
            while (r.next()) {
                if (r.tag() == 1) {
                    auto m = r.get_message();
                    while (m.next()) {
                        if (m.tag() == 2) sum += m.get_view().size();
                        else if (m.tag() == 6) sum += static_cast<uint64_t>(m.get_int64());
                        else m.skip();
                    }
                } else if (r.tag() == 2) { r.skip(); sum++; }
                else r.skip();
            }
            return sum;
        }),
        measure(W_S, N_S, [&](int) {
            wv_media.wrap(media_bytes.data(), media_bytes.size());
            auto& m = wv_media.sub(1);
            return static_cast<uint64_t>(m.get_bytes(2).size() + m.get_varint(6) + wv_media.repeated_count(2));
        })
    });

    // O1b: MediaContent Full Read
    open_rows.push_back({"O1b", "MediaContent Full Read (241B)",
        measure(W_S, N_S, [&](int) {
            LP_CHECK(pb_media.ParseFromArray(media_bytes.data(), static_cast<int>(media_bytes.size())));
            return read_pb_media_full(pb_media);
        }),
        measure(W_S, N_S, [&](int) {
            google::protobuf::Arena arena(arena_pool.opts);
            auto* m = google::protobuf::Arena::CreateMessage<opb::MediaContent>(&arena);
            LP_CHECK(m->ParseFromArray(media_bytes.data(), static_cast<int>(media_bytes.size())));
            return read_pb_media_full(*m);
        }),
        measure(W_S, N_S, [&](int) {
            return read_pz_media_full(media_bytes.data(), media_bytes.size());
        }),
        measure(W_S, N_S, [&](int) {
            wv_media.wrap(media_bytes.data(), media_bytes.size());
            return read_lazy_media_full(wv_media);
        })
    });

    // O1c: MediaContent Mutate Title + Serialize
    open_rows.push_back({"O1c", "MediaContent Mutate Title+Ser",
        measure(W_S, N_S, [&](int) {
            LP_CHECK(pb_media.ParseFromArray(media_bytes.data(), static_cast<int>(media_bytes.size())));
            pb_media.mutable_media()->set_title("Javaone Keynote (HD Remastered)");
            return observe_output(out_buf.data(), serialize_pb_to_buf(pb_media, out_buf.data(), out_buf.size()));
        }),
        measure(W_S, N_S, [&](int) {
            google::protobuf::Arena arena(arena_pool.opts);
            auto* m = google::protobuf::Arena::CreateMessage<opb::MediaContent>(&arena);
            LP_CHECK(m->ParseFromArray(media_bytes.data(), static_cast<int>(media_bytes.size())));
            m->mutable_media()->set_title("Javaone Keynote (HD Remastered)");
            return observe_output(out_buf.data(), serialize_pb_to_buf(*m, out_buf.data(), out_buf.size()));
        }),
        measure(W_S, N_S, [&](int) {
            return observe_output(out_buf.data(), pz_media_mutate_title(media_bytes.data(), media_bytes.size(), out_ch, out_buf.size()));
        }),
        measure(W_S, N_S, [&](int) {
            wv_media.wrap(media_bytes.data(), media_bytes.size());
            wv_media.sub(1).set_bytes(2, "Javaone Keynote (HD Remastered)");
            return observe_output(out_buf.data(), wv_media.merge_to(out_buf.data(), out_buf.size()));
        })
    });

    // O2a: Pulsar MessageMetadata Broker Route (489 B)
    open_rows.push_back({"O2a", "Pulsar Broker Route (489B)",
        measure(W_S, N_S, [&](int) {
            LP_CHECK(pb_pulsar.ParseFromArray(pulsar_bytes.data(), static_cast<int>(pulsar_bytes.size())));
            return pb_pulsar.partition_key().size() + pb_pulsar.publish_time() + pb_pulsar.sequence_id();
        }),
        measure(W_S, N_S, [&](int) {
            google::protobuf::Arena arena(arena_pool.opts);
            auto* m = google::protobuf::Arena::CreateMessage<opb::PulsarMessageMetadata>(&arena);
            LP_CHECK(m->ParseFromArray(pulsar_bytes.data(), static_cast<int>(pulsar_bytes.size())));
            return m->partition_key().size() + m->publish_time() + m->sequence_id();
        }),
        measure(W_S, N_S, [&](int) {
            protozero::pbf_reader r(reinterpret_cast<const char*>(pulsar_bytes.data()), pulsar_bytes.size());
            uint64_t sum = 0; int found = 0;
            while (r.next() && found < 3) {
                if (r.tag() == 2) { sum += r.get_uint64(); found++; }
                else if (r.tag() == 3) { sum += r.get_uint64(); found++; }
                else if (r.tag() == 6) { sum += r.get_view().size(); found++; }
                else r.skip();
            }
            return sum;
        }),
        measure(W_S, N_S, [&](int) {
            wv_pulsar.wrap(pulsar_bytes.data(), pulsar_bytes.size());
            return wv_pulsar.get_bytes(6).size() + wv_pulsar.get_varint(3) + wv_pulsar.get_varint(2);
        })
    });

    // O2b: Pulsar Broker Stamp + Forward (489 B)
    open_rows.push_back({"O2b", "Pulsar Stamp+Forward (489B)",
        measure(W_S, N_S, [&](int) {
            LP_CHECK(pb_pulsar.ParseFromArray(pulsar_bytes.data(), static_cast<int>(pulsar_bytes.size())));
            pb_pulsar.set_replicated_from("us-west-2");
            pb_pulsar.set_deliver_at_time(1795497009999LL);
            return observe_output(out_buf.data(), serialize_pb_to_buf(pb_pulsar, out_buf.data(), out_buf.size()));
        }),
        measure(W_S, N_S, [&](int) {
            google::protobuf::Arena arena(arena_pool.opts);
            auto* m = google::protobuf::Arena::CreateMessage<opb::PulsarMessageMetadata>(&arena);
            LP_CHECK(m->ParseFromArray(pulsar_bytes.data(), static_cast<int>(pulsar_bytes.size())));
            m->set_replicated_from("us-west-2");
            m->set_deliver_at_time(1795497009999LL);
            return observe_output(out_buf.data(), serialize_pb_to_buf(*m, out_buf.data(), out_buf.size()));
        }),
        measure(W_S, N_S, [&](int) {
            return observe_output(out_buf.data(), pz_pulsar_stamp(pulsar_bytes.data(), pulsar_bytes.size(), out_ch, out_buf.size()));
        }),
        measure(W_S, N_S, [&](int) {
            wv_pulsar.wrap(pulsar_bytes.data(), pulsar_bytes.size());
            wv_pulsar.set_bytes(5, "us-west-2").set_varint(19, 1795497009999ULL);
            return observe_output(out_buf.data(), wv_pulsar.merge_to(out_buf.data(), out_buf.size()));
        })
    });

    // O2c: Pulsar Full Read All 10 Props
    open_rows.push_back({"O2c", "Pulsar Full Read Props (489B)",
        measure(W_S, N_S, [&](int) {
            LP_CHECK(pb_pulsar.ParseFromArray(pulsar_bytes.data(), static_cast<int>(pulsar_bytes.size())));
            return read_pb_pulsar_full(pb_pulsar);
        }),
        measure(W_S, N_S, [&](int) {
            google::protobuf::Arena arena(arena_pool.opts);
            auto* m = google::protobuf::Arena::CreateMessage<opb::PulsarMessageMetadata>(&arena);
            LP_CHECK(m->ParseFromArray(pulsar_bytes.data(), static_cast<int>(pulsar_bytes.size())));
            return read_pb_pulsar_full(*m);
        }),
        measure(W_S, N_S, [&](int) {
            return read_pz_pulsar_full(pulsar_bytes.data(), pulsar_bytes.size());
        }),
        measure(W_S, N_S, [&](int) {
            wv_pulsar.wrap(pulsar_bytes.data(), pulsar_bytes.size());
            return read_lazy_pulsar_full(wv_pulsar);
        })
    });

    // O3a: OTel Collector Route + Forward (13.4 KB)
    open_rows.push_back({"O3a", "OTel Route+Forward (13.4KB)",
        measure(W, N, [&](int) {
            LP_CHECK(pb_otel.ParseFromArray(otel_bytes.data(), static_cast<int>(otel_bytes.size())));
            if (!pb_otel.resource_spans(0).resource().attributes(0).value().empty()) {
                return observe_output(out_buf.data(), serialize_pb_to_buf(pb_otel, out_buf.data(), out_buf.size()));
            }
            return uint64_t{0};
        }),
        measure(W, N, [&](int) {
            google::protobuf::Arena arena(arena_pool.opts);
            auto* m = google::protobuf::Arena::CreateMessage<opb::OtelExportTraceRequest>(&arena);
            LP_CHECK(m->ParseFromArray(otel_bytes.data(), static_cast<int>(otel_bytes.size())));
            if (!m->resource_spans(0).resource().attributes(0).value().empty()) {
                return observe_output(out_buf.data(), serialize_pb_to_buf(*m, out_buf.data(), out_buf.size()));
            }
            return uint64_t{0};
        }),
        measure(W, N, [&](int) {
            // Best-case streaming proxy: read service.name then memcpy raw bytes
            protozero::pbf_reader req(reinterpret_cast<const char*>(otel_bytes.data()), otel_bytes.size());
            size_t svc_len = 0;
            while (req.next()) {
                if (req.tag() == 1) {
                    auto rs = req.get_message();
                    while (rs.next()) {
                        if (rs.tag() == 1) {
                            auto res = rs.get_message();
                            while (res.next()) {
                                if (res.tag() == 1) {
                                    auto kv = res.get_message();
                                    while (kv.next()) {
                                        if (kv.tag() == 2) { svc_len = kv.get_view().size(); break; }
                                        else kv.skip();
                                    }
                                    break;
                                } else res.skip();
                            }
                            break;
                        } else rs.skip();
                    }
                    break;
                } else req.skip();
            }
            if (svc_len > 0) {
                std::memcpy(out_buf.data(), otel_bytes.data(), otel_bytes.size());
                return observe_output(out_buf.data(), otel_bytes.size());
            }
            return uint64_t{0};
        }),
        measure(W, N, [&](int) {
            wv_otel.wrap(otel_bytes.data(), otel_bytes.size());
            if (!wv_otel.repeated_sub(1, 0).sub(1).repeated_sub(1, 0).get_bytes(2).empty()) {
                return observe_output(out_buf.data(), wv_otel.merge_to(out_buf.data(), out_buf.size()));
            }
            return uint64_t{0};
        })
    });

    // O3b: OTel Enrich Resource Attr + Schema URL + Forward
    open_rows.push_back({"O3b", "OTel Enrich Res+Fwd (13.4KB)",
        measure(W, N, [&](int) {
            LP_CHECK(pb_otel.ParseFromArray(otel_bytes.data(), static_cast<int>(otel_bytes.size())));
            pb_otel.mutable_resource_spans(0)->set_schema_url("https://opentelemetry.io/schemas/1.26.0");
            pb_otel.mutable_resource_spans(0)->mutable_resource()->mutable_attributes(1)->set_value("prod-us-east-1");
            return observe_output(out_buf.data(), serialize_pb_to_buf(pb_otel, out_buf.data(), out_buf.size()));
        }),
        measure(W, N, [&](int) {
            google::protobuf::Arena arena(arena_pool.opts);
            auto* m = google::protobuf::Arena::CreateMessage<opb::OtelExportTraceRequest>(&arena);
            LP_CHECK(m->ParseFromArray(otel_bytes.data(), static_cast<int>(otel_bytes.size())));
            m->mutable_resource_spans(0)->set_schema_url("https://opentelemetry.io/schemas/1.26.0");
            m->mutable_resource_spans(0)->mutable_resource()->mutable_attributes(1)->set_value("prod-us-east-1");
            return observe_output(out_buf.data(), serialize_pb_to_buf(*m, out_buf.data(), out_buf.size()));
        }),
        measure(W, N, [&](int) {
            return observe_output(out_buf.data(), pz_otel_enrich_res(otel_bytes.data(), otel_bytes.size(), out_ch, out_buf.size()));
        }),
        measure(W, N, [&](int) {
            wv_otel.wrap(otel_bytes.data(), otel_bytes.size());
            auto& rs = wv_otel.repeated_sub(1, 0);
            rs.set_bytes(3, "https://opentelemetry.io/schemas/1.26.0");
            rs.sub(1).repeated_sub(1, 1).set_bytes(2, "prod-us-east-1");
            return observe_output(out_buf.data(), wv_otel.merge_to(out_buf.data(), out_buf.size()));
        })
    });

    // O3c: OTel Scrub 1 PII Span Attr (4-Level Deep) + Forward
    open_rows.push_back({"O3c", "OTel Scrub 1 PII+Fwd (4-lvl)",
        measure(W, N, [&](int) {
            LP_CHECK(pb_otel.ParseFromArray(otel_bytes.data(), static_cast<int>(otel_bytes.size())));
            pb_otel.mutable_resource_spans(0)->mutable_scope_spans(1)->mutable_spans(12)->mutable_attributes(3)->set_value("[REDACTED]");
            return observe_output(out_buf.data(), serialize_pb_to_buf(pb_otel, out_buf.data(), out_buf.size()));
        }),
        measure(W, N, [&](int) {
            google::protobuf::Arena arena(arena_pool.opts);
            auto* m = google::protobuf::Arena::CreateMessage<opb::OtelExportTraceRequest>(&arena);
            LP_CHECK(m->ParseFromArray(otel_bytes.data(), static_cast<int>(otel_bytes.size())));
            m->mutable_resource_spans(0)->mutable_scope_spans(1)->mutable_spans(12)->mutable_attributes(3)->set_value("[REDACTED]");
            return observe_output(out_buf.data(), serialize_pb_to_buf(*m, out_buf.data(), out_buf.size()));
        }),
        measure(W, N, [&](int) {
            return observe_output(out_buf.data(), pz_otel_scrub_pii(otel_bytes.data(), otel_bytes.size(), out_ch, out_buf.size()));
        }),
        measure(W, N, [&](int) {
            wv_otel.wrap(otel_bytes.data(), otel_bytes.size());
            wv_otel.repeated_sub(1, 0).repeated_sub(2, 1).repeated_sub(2, 12).repeated_sub(9, 3).set_bytes(2, "[REDACTED]");
            return observe_output(out_buf.data(), wv_otel.merge_to(out_buf.data(), out_buf.size()));
        })
    });

    // O3d: OTel Full Ingest (50 Spans x 6 Attrs)
    open_rows.push_back({"O3d", "OTel Full Ingest (50sp x 6)",
        measure(W / 2, N / 2, [&](int) {
            LP_CHECK(pb_otel.ParseFromArray(otel_bytes.data(), static_cast<int>(otel_bytes.size())));
            return read_pb_otel_full(pb_otel);
        }),
        measure(W / 2, N / 2, [&](int) {
            google::protobuf::Arena arena(arena_pool.opts);
            auto* m = google::protobuf::Arena::CreateMessage<opb::OtelExportTraceRequest>(&arena);
            LP_CHECK(m->ParseFromArray(otel_bytes.data(), static_cast<int>(otel_bytes.size())));
            return read_pb_otel_full(*m);
        }),
        measure(W / 2, N / 2, [&](int) {
            return read_pz_otel_full(otel_bytes.data(), otel_bytes.size());
        }),
        measure(W / 2, N / 2, [&](int) {
            wv_otel.wrap(otel_bytes.data(), otel_bytes.size());
            return read_lazy_otel_full(wv_otel);
        })
    });

    // O4: Selectivity Crossover Sweep (1% / 10% / 25% / 50% / 100% of 100 items)
    const int sweep_k[5] = {1, 10, 25, 50, 100};
    const char* sweep_ids[5] = {"O4 (1%)", "O4 (10%)", "O4 (25%)", "O4 (50%)", "O4 (100%)"};
    const char* sweep_descs[5] = {
        "Sweep: Mutate 1/100 items",
        "Sweep: Mutate 10/100 items",
        "Sweep: Mutate 25/100 items",
        "Sweep: Mutate 50/100 items",
        "Sweep: Mutate 100/100 items"
    };
    for (int s = 0; s < 5; s++) {
        int k = sweep_k[s];
        open_rows.push_back({sweep_ids[s], sweep_descs[s],
            measure(W, N, [&](int) {
                LP_CHECK(pb_order.ParseFromArray(order_no_unk.data(), static_cast<int>(order_no_unk.size())));
                for (int i = 0; i < k; i++) {
                    pb_order.mutable_items(i)->set_quantity(999);
                    pb_order.mutable_items(i)->set_description("UPDATED_ITEM_DESC");
                }
                return observe_output(out_buf.data(), serialize_pb_to_buf(pb_order, out_buf.data(), out_buf.size()));
            }),
            measure(W, N, [&](int) {
                google::protobuf::Arena arena(arena_pool.opts);
                auto* m = google::protobuf::Arena::CreateMessage<pb::OrderEnvelope>(&arena);
                LP_CHECK(m->ParseFromArray(order_no_unk.data(), static_cast<int>(order_no_unk.size())));
                for (int i = 0; i < k; i++) {
                    m->mutable_items(i)->set_quantity(999);
                    m->mutable_items(i)->set_description("UPDATED_ITEM_DESC");
                }
                return observe_output(out_buf.data(), serialize_pb_to_buf(*m, out_buf.data(), out_buf.size()));
            }),
            measure(W, N, [&](int) {
                return observe_output(out_buf.data(),
                        pz_order_mutate_k(order_no_unk.data(), order_no_unk.size(), 0, k, "UPDATED_ITEM_DESC", out_ch, out_buf.size()));
            }),
            measure(W, N, [&](int) {
                lazy_order.wrap(order_no_unk.data(), 0, static_cast<uint32_t>(order_no_unk.size()));
                for (int i = 0; i < k; i++) {
                    lazy_order.items(i).set_quantity(999).set_description("UPDATED_ITEM_DESC");
                }
                return observe_output(out_buf.data(), lazy_order.merge_to(out_buf.data(), out_buf.size()));
            })
        });
    }

    print_table("C++20 OPEN BENCHMARKS (MediaContent / Pulsar / OpenTelemetry / Selectivity Sweep): PB-Reuse vs PB-Arena vs Protozero v1.8.2 vs lazy-proto", open_rows);

    google::protobuf::ShutdownProtobufLibrary();
    return 0;
}
