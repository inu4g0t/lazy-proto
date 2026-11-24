#pragma once

#include "lazy_proto.hpp"
#include <cstdint>
#include <cstring>
#include <string>
#include <vector>

namespace stdproto {

using namespace lazyproto;

/**
 * Standard C++ Eager Protobuf Object Model (matching standard protoc C++ generated classes):
 * - Eager recursive full-tree deserialization into std::string and std::vector<StdLineItem>
 * - Retains unknown fields in an UnknownField buffer
 * - Two-pass serialization: ByteSizeLong() (recursive size calculation) + SerializeToArray()
 */

struct UnknownRecord {
    uint32_t tag;
    uint64_t varint_val;
    std::string bytes_val;
};

struct StdUserProfile {
    uint64_t user_id = 0;
    std::string city;
    int32_t vip_level = 0;
    double score = 0.0;
    std::string email;
    std::vector<UnknownRecord> unknowns;
    mutable uint32_t cached_size = 0;

    void Clear() {
        user_id = 0;
        city.clear();
        vip_level = 0;
        score = 0.0;
        email.clear();
        unknowns.clear();
        cached_size = 0;
    }

    bool ParseFromArray(const uint8_t* buf, uint32_t len) {
        Clear();
        uint32_t cursor = 0;
        while (cursor < len) {
            uint32_t tag = read_varint32_cursor(buf, cursor);
            switch (tag) {
                case 8:  user_id = read_varint64_cursor(buf, cursor); break;
                case 18: {
                    uint32_t slen = read_varint32_cursor(buf, cursor);
                    city.assign(reinterpret_cast<const char*>(buf + cursor), slen);
                    cursor += slen;
                    break;
                }
                case 24: vip_level = static_cast<int32_t>(read_varint32_cursor(buf, cursor)); break;
                case 33: score = read_double_at(buf, cursor); cursor += 8; break;
                case 42: {
                    uint32_t slen = read_varint32_cursor(buf, cursor);
                    email.assign(reinterpret_cast<const char*>(buf + cursor), slen);
                    cursor += slen;
                    break;
                }
                default: {
                    uint8_t wt = tag & 7U;
                    if (wt == WIRETYPE_VARINT) {
                        unknowns.push_back({tag, read_varint64_cursor(buf, cursor), {}});
                    } else if (wt == WIRETYPE_LENGTH_DELIMITED) {
                        uint32_t ulen = read_varint32_cursor(buf, cursor);
                        unknowns.push_back({tag, 0, std::string(reinterpret_cast<const char*>(buf + cursor), ulen)});
                        cursor += ulen;
                    }
                    break;
                }
            }
        }
        return true;
    }

    uint32_t ByteSizeLong() const noexcept {
        uint32_t sz = 0;
        if (user_id != 0) sz += 1 + varint64_size(user_id);
        if (!city.empty()) sz += 1 + varint32_size(static_cast<uint32_t>(city.size())) + static_cast<uint32_t>(city.size());
        if (vip_level != 0) sz += 1 + varint32_size(static_cast<uint32_t>(vip_level));
        if (score != 0.0) sz += 1 + 8;
        if (!email.empty()) sz += 1 + varint32_size(static_cast<uint32_t>(email.size())) + static_cast<uint32_t>(email.size());
        for (const auto& u : unknowns) {
            sz += varint32_size(u.tag);
            if ((u.tag & 7U) == WIRETYPE_VARINT) sz += varint64_size(u.varint_val);
            else sz += varint32_size(static_cast<uint32_t>(u.bytes_val.size())) + static_cast<uint32_t>(u.bytes_val.size());
        }
        cached_size = sz;
        return sz;
    }

    void SerializeWithCachedSizes(WireWriter& w) const noexcept {
        if (user_id != 0) { w.write_byte(0x08); w.write_varint64(user_id); }
        if (!city.empty()) { w.write_string(0x12, city); }
        if (vip_level != 0) { w.write_byte(0x18); w.write_varint32(static_cast<uint32_t>(vip_level)); }
        if (score != 0.0) { w.write_byte(0x21); w.write_double(score); }
        if (!email.empty()) { w.write_string(0x2A, email); }
        for (const auto& u : unknowns) {
            w.write_varint32(u.tag);
            if ((u.tag & 7U) == WIRETYPE_VARINT) w.write_varint64(u.varint_val);
            else {
                w.write_varint32(static_cast<uint32_t>(u.bytes_val.size()));
                w.write_raw(reinterpret_cast<const uint8_t*>(u.bytes_val.data()), static_cast<uint32_t>(u.bytes_val.size()));
            }
        }
    }
};

struct StdLineItem {
    uint64_t item_id = 0;
    std::string sku;
    int32_t quantity = 0;
    uint64_t price_cents = 0;
    std::string description;
    mutable uint32_t cached_size = 0;

    void Clear() {
        item_id = 0;
        sku.clear();
        quantity = 0;
        price_cents = 0;
        description.clear();
        cached_size = 0;
    }

    bool ParseFromArray(const uint8_t* buf, uint32_t len) {
        Clear();
        uint32_t cursor = 0;
        while (cursor < len) {
            uint32_t tag = read_varint32_cursor(buf, cursor);
            switch (tag) {
                case 8:  item_id = read_varint64_cursor(buf, cursor); break;
                case 18: {
                    uint32_t slen = read_varint32_cursor(buf, cursor);
                    sku.assign(reinterpret_cast<const char*>(buf + cursor), slen);
                    cursor += slen;
                    break;
                }
                case 24: quantity = static_cast<int32_t>(read_varint32_cursor(buf, cursor)); break;
                case 32: price_cents = read_varint64_cursor(buf, cursor); break;
                case 42: {
                    uint32_t slen = read_varint32_cursor(buf, cursor);
                    description.assign(reinterpret_cast<const char*>(buf + cursor), slen);
                    cursor += slen;
                    break;
                }
                default: {
                    uint8_t wt = tag & 7U;
                    if (wt == 0) cursor = skip_varint_swar(buf, cursor, len);
                    else if (wt == 2) { uint32_t l = read_varint32_cursor(buf, cursor); cursor += l; }
                    break;
                }
            }
        }
        return true;
    }

    uint32_t ByteSizeLong() const noexcept {
        uint32_t sz = 0;
        if (item_id != 0) sz += 1 + varint64_size(item_id);
        if (!sku.empty()) sz += 1 + varint32_size(static_cast<uint32_t>(sku.size())) + static_cast<uint32_t>(sku.size());
        if (quantity != 0) sz += 1 + varint32_size(static_cast<uint32_t>(quantity));
        if (price_cents != 0) sz += 1 + varint64_size(price_cents);
        if (!description.empty()) sz += 1 + varint32_size(static_cast<uint32_t>(description.size())) + static_cast<uint32_t>(description.size());
        cached_size = sz;
        return sz;
    }

    void SerializeWithCachedSizes(WireWriter& w) const noexcept {
        if (item_id != 0) { w.write_byte(0x08); w.write_varint64(item_id); }
        if (!sku.empty()) { w.write_string(0x12, sku); }
        if (quantity != 0) { w.write_byte(0x18); w.write_varint32(static_cast<uint32_t>(quantity)); }
        if (price_cents != 0) { w.write_byte(0x20); w.write_varint64(price_cents); }
        if (!description.empty()) { w.write_string(0x2A, description); }
    }
};

struct StdOrderEnvelope {
    uint64_t order_id = 0;
    std::string buyer_name;
    int32_t status = 0;
    uint64_t updated_at_fixed64 = 0;
    bool has_user = false;
    StdUserProfile user;
    std::vector<StdLineItem> items;
    std::string payload;
    std::string auth_token;
    std::string trace_id;
    uint64_t total_cents = 0;
    std::vector<UnknownRecord> unknowns;
    mutable uint32_t cached_size = 0;

    void Clear() {
        order_id = 0;
        buyer_name.clear();
        status = 0;
        updated_at_fixed64 = 0;
        has_user = false;
        user.Clear();
        items.clear();
        payload.clear();
        auth_token.clear();
        trace_id.clear();
        total_cents = 0;
        unknowns.clear();
        cached_size = 0;
    }

    bool ParseFromArray(const uint8_t* buf, uint32_t len) {
        Clear();
        uint32_t cursor = 0;
        while (cursor < len) {
            uint32_t tag = read_varint32_cursor(buf, cursor);
            switch (tag) {
                case 8:  order_id = read_varint64_cursor(buf, cursor); break;
                case 18: {
                    uint32_t slen = read_varint32_cursor(buf, cursor);
                    buyer_name.assign(reinterpret_cast<const char*>(buf + cursor), slen);
                    cursor += slen;
                    break;
                }
                case 24: status = static_cast<int32_t>(read_varint32_cursor(buf, cursor)); break;
                case 33: updated_at_fixed64 = read_fixed64_at(buf, cursor); cursor += 8; break;
                case 42: {
                    uint32_t slen = read_varint32_cursor(buf, cursor);
                    has_user = true;
                    user.ParseFromArray(buf + cursor, slen);
                    cursor += slen;
                    break;
                }
                case 50: {
                    uint32_t slen = read_varint32_cursor(buf, cursor);
                    items.emplace_back();
                    items.back().ParseFromArray(buf + cursor, slen);
                    cursor += slen;
                    break;
                }
                case 58: {
                    uint32_t slen = read_varint32_cursor(buf, cursor);
                    payload.assign(reinterpret_cast<const char*>(buf + cursor), slen);
                    cursor += slen;
                    break;
                }
                case 66: {
                    uint32_t slen = read_varint32_cursor(buf, cursor);
                    auth_token.assign(reinterpret_cast<const char*>(buf + cursor), slen);
                    cursor += slen;
                    break;
                }
                case 74: {
                    uint32_t slen = read_varint32_cursor(buf, cursor);
                    trace_id.assign(reinterpret_cast<const char*>(buf + cursor), slen);
                    cursor += slen;
                    break;
                }
                case 80: total_cents = read_varint64_cursor(buf, cursor); break;
                default: {
                    uint8_t wt = tag & 7U;
                    if (wt == WIRETYPE_VARINT) {
                        unknowns.push_back({tag, read_varint64_cursor(buf, cursor), {}});
                    } else if (wt == WIRETYPE_LENGTH_DELIMITED) {
                        uint32_t ulen = read_varint32_cursor(buf, cursor);
                        unknowns.push_back({tag, 0, std::string(reinterpret_cast<const char*>(buf + cursor), ulen)});
                        cursor += ulen;
                    }
                    break;
                }
            }
        }
        return true;
    }

    uint32_t ByteSizeLong() const noexcept {
        uint32_t sz = 0;
        if (order_id != 0) sz += 1 + varint64_size(order_id);
        if (!buyer_name.empty()) sz += 1 + varint32_size(static_cast<uint32_t>(buyer_name.size())) + static_cast<uint32_t>(buyer_name.size());
        if (status != 0) sz += 1 + varint32_size(static_cast<uint32_t>(status));
        if (updated_at_fixed64 != 0) sz += 1 + 8;
        if (has_user) {
            uint32_t usz = user.ByteSizeLong();
            sz += 1 + varint32_size(usz) + usz;
        }
        for (const auto& item : items) {
            uint32_t isz = item.ByteSizeLong();
            sz += 1 + varint32_size(isz) + isz;
        }
        if (!payload.empty()) sz += 1 + varint32_size(static_cast<uint32_t>(payload.size())) + static_cast<uint32_t>(payload.size());
        if (!auth_token.empty()) sz += 1 + varint32_size(static_cast<uint32_t>(auth_token.size())) + static_cast<uint32_t>(auth_token.size());
        if (!trace_id.empty()) sz += 1 + varint32_size(static_cast<uint32_t>(trace_id.size())) + static_cast<uint32_t>(trace_id.size());
        if (total_cents != 0) sz += 1 + varint64_size(total_cents);
        for (const auto& u : unknowns) {
            sz += varint32_size(u.tag);
            if ((u.tag & 7U) == WIRETYPE_VARINT) sz += varint64_size(u.varint_val);
            else sz += varint32_size(static_cast<uint32_t>(u.bytes_val.size())) + static_cast<uint32_t>(u.bytes_val.size());
        }
        cached_size = sz;
        return sz;
    }

    size_t SerializeToArray(uint8_t* out_buf) const noexcept {
        // Standard Protobuf two-pass serialization: Pass 1 computes sizes, Pass 2 writes bytes
        ByteSizeLong();
        WireWriter w(out_buf);
        if (order_id != 0) { w.write_byte(0x08); w.write_varint64(order_id); }
        if (!buyer_name.empty()) { w.write_string(0x12, buyer_name); }
        if (status != 0) { w.write_byte(0x18); w.write_varint32(static_cast<uint32_t>(status)); }
        if (updated_at_fixed64 != 0) { w.write_byte(0x21); w.write_fixed64(updated_at_fixed64); }
        if (has_user) {
            w.write_byte(0x2A);
            w.write_varint32(user.cached_size);
            user.SerializeWithCachedSizes(w);
        }
        for (const auto& item : items) {
            w.write_byte(0x32);
            w.write_varint32(item.cached_size);
            item.SerializeWithCachedSizes(w);
        }
        if (!payload.empty()) { w.write_string(0x3A, payload); }
        if (!auth_token.empty()) { w.write_string(0x42, auth_token); }
        if (!trace_id.empty()) { w.write_string(0x4A, trace_id); }
        if (total_cents != 0) { w.write_byte(0x50); w.write_varint64(total_cents); }
        for (const auto& u : unknowns) {
            w.write_varint32(u.tag);
            if ((u.tag & 7U) == WIRETYPE_VARINT) w.write_varint64(u.varint_val);
            else {
                w.write_varint32(static_cast<uint32_t>(u.bytes_val.size()));
                w.write_raw(reinterpret_cast<const uint8_t*>(u.bytes_val.data()), static_cast<uint32_t>(u.bytes_val.size()));
            }
        }
        return w.size();
    }
};

} // namespace stdproto
