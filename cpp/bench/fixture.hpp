#pragma once

#include "lazyproto/wire.hpp"
#include "order_envelope.pb.h"
#include "std_proto.hpp"

#include <cstdio>
#include <cstdlib>
#include <google/protobuf/unknown_field_set.h>
#include <string>
#include <vector>

// Always-active runtime check (never stripped by -DNDEBUG)
#define LP_CHECK(cond)                                                                      \
    do {                                                                                    \
        if (!(cond)) {                                                                      \
            std::fprintf(stderr, "CHECK FAILED at %s:%d: %s\n", __FILE__, __LINE__, #cond); \
            std::abort();                                                                   \
        }                                                                                   \
    } while (0)

inline std::vector<uint8_t> create_complex_order_bytes(int item_count, int payload_size) {
    using namespace lazyproto;
    using namespace stdproto;
    namespace pb = lazyproto::bench;

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
