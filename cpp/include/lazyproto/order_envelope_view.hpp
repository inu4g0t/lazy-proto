#pragma once

#include "lazyproto/tape.hpp"

namespace lazyproto {

// ============================================================================
// 5. Typed Schema Lower-Level Views (Zero Interface Tax, Shared TapeCore)
//    - Uses shared TapeStorage, scan_wire_range, write_spliced_tape
//    - Automatic child->parent dirty propagation (no manual sync_* calls)
//    - Lazy on-demand LineItem pool (no 33KB eager construction overhead)
// ============================================================================

class OrderEnvelopeView;

class UserProfileView {
public:
    static constexpr int SLOT_USER_ID   = 0;
    static constexpr int SLOT_CITY      = 1;
    static constexpr int SLOT_VIP_LEVEL = 2;
    static constexpr int SLOT_SCORE     = 3;
    static constexpr int SLOT_EMAIL     = 4;

    static constexpr uint32_t BIT_USER_ID   = 1U << 0;
    static constexpr uint32_t BIT_CITY      = 1U << 1;
    static constexpr uint32_t BIT_VIP_LEVEL = 1U << 2;
    static constexpr uint32_t BIT_SCORE     = 1U << 3;
    static constexpr uint32_t BIT_EMAIL     = 1U << 4;

    void wrap(const uint8_t* buf, uint32_t off, uint32_t len) noexcept {
        buf_ = buf;
        raw_off_ = off;
        raw_len_ = len;
        tape_.reset();
        scanned_ = false;
        multi_segment_ = false;
        present_mask_ = 0;
        decoded_mask_ = 0;
        same_size_dirty_mask_ = 0;
        var_size_dirty_mask_ = 0;
        appended_mask_ = 0;
        std::memset(slot_tape_idx_, -1, sizeof(slot_tape_idx_));
    }

    void init_scratch() noexcept {
        wrap(nullptr, 0, 0);
        scanned_ = true;
    }

    void bind_parent(OrderEnvelopeView* parent) noexcept { parent_ = parent; }

    bool is_clean() const noexcept {
        return !multi_segment_ && (same_size_dirty_mask_ | var_size_dirty_mask_ | appended_mask_) == 0;
    }

    bool is_same_size_dirty_only() const noexcept {
        return !multi_segment_ && (var_size_dirty_mask_ | appended_mask_) == 0 && same_size_dirty_mask_ != 0;
    }

    bool is_scanned() const noexcept { return scanned_; }

    void validate_deep() { ensure_scanned(); }

    uint64_t user_id() {
        if (!(decoded_mask_ & BIT_USER_ID)) {
            ensure_scanned();
            int32_t idx = slot_tape_idx_[SLOT_USER_ID];
            user_id_ = idx >= 0 ? read_varint64_at(buf_, tape_[static_cast<uint32_t>(idx)].value_offset()) : 0ULL;
            decoded_mask_ |= BIT_USER_ID;
        }
        return user_id_;
    }

    std::string_view city() {
        if (!(decoded_mask_ & BIT_CITY)) {
            ensure_scanned();
            int32_t idx = slot_tape_idx_[SLOT_CITY];
            city_ = idx >= 0
                ? std::string_view(reinterpret_cast<const char*>(buf_ + tape_[static_cast<uint32_t>(idx)].value_offset()),
                                   tape_[static_cast<uint32_t>(idx)].value_length())
                : std::string_view();
            decoded_mask_ |= BIT_CITY;
        }
        return city_;
    }

    int32_t vip_level() {
        if (!(decoded_mask_ & BIT_VIP_LEVEL)) {
            ensure_scanned();
            int32_t idx = slot_tape_idx_[SLOT_VIP_LEVEL];
            vip_level_ = idx >= 0 ? static_cast<int32_t>(read_varint32_at(buf_, tape_[static_cast<uint32_t>(idx)].value_offset())) : 0;
            decoded_mask_ |= BIT_VIP_LEVEL;
        }
        return vip_level_;
    }

    double score() {
        if (!(decoded_mask_ & BIT_SCORE)) {
            ensure_scanned();
            int32_t idx = slot_tape_idx_[SLOT_SCORE];
            score_ = idx >= 0 ? read_double_at(buf_, tape_[static_cast<uint32_t>(idx)].value_offset()) : 0.0;
            decoded_mask_ |= BIT_SCORE;
        }
        return score_;
    }

    std::string_view email() {
        if (!(decoded_mask_ & BIT_EMAIL)) {
            ensure_scanned();
            int32_t idx = slot_tape_idx_[SLOT_EMAIL];
            email_ = idx >= 0
                ? std::string_view(reinterpret_cast<const char*>(buf_ + tape_[static_cast<uint32_t>(idx)].value_offset()),
                                   tape_[static_cast<uint32_t>(idx)].value_length())
                : std::string_view();
            decoded_mask_ |= BIT_EMAIL;
        }
        return email_;
    }

    UserProfileView& set_user_id(uint64_t v) {
        ensure_scanned();
        user_id_ = v;
        decoded_mask_ |= BIT_USER_ID;
        present_mask_ |= BIT_USER_ID;
        mark_modified(SLOT_USER_ID, BIT_USER_ID, WIRETYPE_VARINT, varint64_size(v));
        notify_parent();
        return *this;
    }

    UserProfileView& set_city(std::string_view v) {
        ensure_scanned();
        city_ = v;
        decoded_mask_ |= BIT_CITY;
        present_mask_ |= BIT_CITY;
        mark_modified(SLOT_CITY, BIT_CITY, WIRETYPE_LENGTH_DELIMITED, static_cast<uint32_t>(v.size()));
        notify_parent();
        return *this;
    }

    UserProfileView& set_vip_level(int32_t v) {
        ensure_scanned();
        vip_level_ = v;
        decoded_mask_ |= BIT_VIP_LEVEL;
        present_mask_ |= BIT_VIP_LEVEL;
        mark_modified(SLOT_VIP_LEVEL, BIT_VIP_LEVEL, WIRETYPE_VARINT, varint_int32_size(v));
        notify_parent();
        return *this;
    }

    UserProfileView& set_score(double v) {
        ensure_scanned();
        score_ = v;
        decoded_mask_ |= BIT_SCORE;
        present_mask_ |= BIT_SCORE;
        mark_modified(SLOT_SCORE, BIT_SCORE, WIRETYPE_FIXED64, 8U);
        notify_parent();
        return *this;
    }

    UserProfileView& set_email(std::string_view v) {
        ensure_scanned();
        email_ = v;
        decoded_mask_ |= BIT_EMAIL;
        present_mask_ |= BIT_EMAIL;
        mark_modified(SLOT_EMAIL, BIT_EMAIL, WIRETYPE_LENGTH_DELIMITED, static_cast<uint32_t>(v.size()));
        notify_parent();
        return *this;
    }

    void apply_in_place_patches(uint8_t* target, uint32_t base_offset) const noexcept {
        uint32_t mask = same_size_dirty_mask_;
        while (mask != 0U) {
            int slot = __builtin_ctz(mask);
            int32_t idx = slot_tape_idx_[slot];
            if (idx >= 0 && tape_[static_cast<uint32_t>(idx)].flags == FLAG_DIRTY_SAME_SIZE) {
                uint8_t* patch_ptr = target + (tape_[static_cast<uint32_t>(idx)].value_offset() - base_offset);
                switch (slot) {
                    case SLOT_USER_ID:   WireWriter::patch_varint64_at(patch_ptr, user_id_); break;
                    case SLOT_CITY:      if (!city_.empty()) std::memcpy(patch_ptr, city_.data(), city_.size()); break;
                    case SLOT_VIP_LEVEL: WireWriter::patch_int32_at(patch_ptr, vip_level_); break;
                    case SLOT_SCORE:     WireWriter::patch_double_at(patch_ptr, score_); break;
                    case SLOT_EMAIL:     if (!email_.empty()) std::memcpy(patch_ptr, email_.data(), email_.size()); break;
                }
            }
            mask &= (mask - 1U);
        }
    }

    void write_spliced_to(WireWriter& w) const noexcept {
        if (is_clean()) {
            if (raw_len_ > 0) w.write_raw(buf_ + raw_off_, raw_len_);
            return;
        }
        if (is_same_size_dirty_only()) {
            uint8_t* start = w.ptr;
            w.write_raw(buf_ + raw_off_, raw_len_);
            apply_in_place_patches(start, raw_off_);
            return;
        }
        write_spliced_tape(
                buf_, tape_, w,
                [this](uint32_t, const TapeEntry& e, WireWriter& dw) { write_field(e.field_number(), dw); },
                [this](WireWriter& aw) {
                    uint32_t m = appended_mask_;
                    while (m != 0U) {
                        int slot = __builtin_ctz(m);
                        write_field(static_cast<uint32_t>(slot + 1), aw);
                        m &= (m - 1U);
                    }
                });
    }

    size_t max_serialized_size() const noexcept {
        if (is_clean() || is_same_size_dirty_only()) return raw_len_;
        size_t sz = raw_len_;
        if ((var_size_dirty_mask_ | appended_mask_) & BIT_USER_ID) sz += 1 + varint64_size(user_id_);
        if ((var_size_dirty_mask_ | appended_mask_) & BIT_CITY)    sz += 1 + varint32_size(static_cast<uint32_t>(city_.size())) + city_.size();
        if ((var_size_dirty_mask_ | appended_mask_) & BIT_VIP_LEVEL) sz += 1 + varint_int32_size(vip_level_);
        if ((var_size_dirty_mask_ | appended_mask_) & BIT_SCORE)   sz += 1 + 8;
        if ((var_size_dirty_mask_ | appended_mask_) & BIT_EMAIL)   sz += 1 + varint32_size(static_cast<uint32_t>(email_.size())) + email_.size();
        return sz;
    }

private:
    friend class OrderEnvelopeView;

    const uint8_t* buf_ = nullptr;
    uint32_t raw_off_ = 0;
    uint32_t raw_len_ = 0;

    TapeStorage<16> tape_;
    int32_t slot_tape_idx_[5] = { -1, -1, -1, -1, -1 };

    bool scanned_ = false;
    bool multi_segment_ = false;
    uint32_t present_mask_ = 0;
    uint32_t decoded_mask_ = 0;
    uint32_t same_size_dirty_mask_ = 0;
    uint32_t var_size_dirty_mask_ = 0;
    uint32_t appended_mask_ = 0;

    uint64_t user_id_ = 0;
    std::string_view city_;
    int32_t vip_level_ = 0;
    double score_ = 0.0;
    std::string_view email_;
    OrderEnvelopeView* parent_ = nullptr;

    void wrap_multi_segments(const uint8_t* buf, const TapeEntry* parent_entries, const uint32_t* seg_indices, uint32_t seg_count) {
        wrap(buf, 0, 0);
        multi_segment_ = true;
        scanned_ = true;
        uint32_t total = 0;
        for (uint32_t i = 0; i < seg_count; i++) {
            const TapeEntry& se = parent_entries[seg_indices[i]];
            total += se.value_length();
            scan_wire_range(buf, se.value_offset(), se.record_end, tape_, [this](uint32_t idx, TapeEntry& e) {
                on_scan_entry(idx, e);
            });
        }
        raw_len_ = total;
    }

    void ensure_scanned() {
        if (!scanned_) [[unlikely]] {
            scanned_ = true;
            if (buf_ != nullptr && raw_len_ > 0) {
                scan_wire_range(buf_, raw_off_, raw_off_ + raw_len_, tape_, [this](uint32_t idx, TapeEntry& e) {
                    on_scan_entry(idx, e);
                });
            }
        }
    }

    void on_scan_entry(uint32_t idx, TapeEntry&) noexcept {
        TapeEntry& e = tape_[idx];
        int slot = -1;
        switch (e.field_number()) {
            case 1: if (e.wire_type() == WIRETYPE_VARINT) slot = SLOT_USER_ID; break;
            case 2: if (e.wire_type() == WIRETYPE_LENGTH_DELIMITED) slot = SLOT_CITY; break;
            case 3: if (e.wire_type() == WIRETYPE_VARINT) slot = SLOT_VIP_LEVEL; break;
            case 4: if (e.wire_type() == WIRETYPE_FIXED64) slot = SLOT_SCORE; break;
            case 5: if (e.wire_type() == WIRETYPE_LENGTH_DELIMITED) slot = SLOT_EMAIL; break;
        }
        if (slot >= 0) [[likely]] {
            int32_t prev = slot_tape_idx_[slot];
            if (prev >= 0) [[unlikely]] tape_[static_cast<uint32_t>(prev)].flags = FLAG_SHADOWED;
            slot_tape_idx_[slot] = static_cast<int32_t>(idx);
            present_mask_ |= (1U << slot);
        }
    }

    void mark_modified(int slot, uint32_t bit, uint8_t expected_wt, uint32_t new_val_len) noexcept {
        int32_t idx = slot_tape_idx_[slot];
        if (idx >= 0) {
            TapeEntry& e = tape_[static_cast<uint32_t>(idx)];
            if (e.wire_type() == expected_wt && e.value_length() == new_val_len) {
                e.flags = FLAG_DIRTY_SAME_SIZE;
                same_size_dirty_mask_ |= bit;
                var_size_dirty_mask_ &= ~bit;
                return;
            }
            e.flags = FLAG_DIRTY_VAR_SIZE;
            same_size_dirty_mask_ &= ~bit;
            var_size_dirty_mask_ |= bit;
            return;
        }
        appended_mask_ |= bit;
        same_size_dirty_mask_ &= ~bit;
        var_size_dirty_mask_ |= bit;
    }

    inline void notify_parent() noexcept;

    void write_field(uint32_t field_num, WireWriter& w) const noexcept {
        switch (field_num) {
            case 1: if (user_id_ != 0 || slot_tape_idx_[0] >= 0) { w.write_byte(0x08); w.write_varint64(user_id_); } break;
            case 2: if (!city_.empty() || slot_tape_idx_[1] >= 0) { w.write_string(0x12, city_); } break;
            case 3: if (vip_level_ != 0 || slot_tape_idx_[2] >= 0) { w.write_byte(0x18); w.write_int32(vip_level_); } break;
            case 4: if (score_ != 0.0 || slot_tape_idx_[3] >= 0) { w.write_byte(0x21); w.write_double(score_); } break;
            case 5: if (!email_.empty() || slot_tape_idx_[4] >= 0) { w.write_string(0x2A, email_); } break;
        }
    }
};

class LineItemView {
public:
    static constexpr int SLOT_ITEM_ID     = 0;
    static constexpr int SLOT_SKU         = 1;
    static constexpr int SLOT_QUANTITY    = 2;
    static constexpr int SLOT_PRICE_CENTS = 3;
    static constexpr int SLOT_DESCRIPTION = 4;

    static constexpr uint32_t BIT_ITEM_ID     = 1U << 0;
    static constexpr uint32_t BIT_SKU         = 1U << 1;
    static constexpr uint32_t BIT_QUANTITY    = 1U << 2;
    static constexpr uint32_t BIT_PRICE_CENTS = 1U << 3;
    static constexpr uint32_t BIT_DESCRIPTION = 1U << 4;

    const uint8_t* buf_ = nullptr;
    uint32_t raw_off_ = 0;
    uint32_t raw_len_ = 0;

    TapeStorage<8> tape_;
    int32_t slot_tape_idx_[5] = { -1, -1, -1, -1, -1 };

    bool scanned_ = false;
    uint32_t present_mask_ = 0;
    uint32_t decoded_mask_ = 0;
    uint32_t same_size_dirty_mask_ = 0;
    uint32_t var_size_dirty_mask_ = 0;
    uint32_t appended_mask_ = 0;

    uint64_t item_id_ = 0;
    std::string_view sku_;
    int32_t quantity_ = 0;
    uint64_t price_cents_ = 0;
    std::string_view description_;

    OrderEnvelopeView* parent_ = nullptr;
    uint32_t parent_item_idx_ = 0;

    void wrap(const uint8_t* buf, uint32_t off, uint32_t len) noexcept {
        buf_ = buf;
        raw_off_ = off;
        raw_len_ = len;
        tape_.reset();
        scanned_ = false;
        present_mask_ = 0;
        decoded_mask_ = 0;
        same_size_dirty_mask_ = 0;
        var_size_dirty_mask_ = 0;
        appended_mask_ = 0;
        std::memset(slot_tape_idx_, -1, sizeof(slot_tape_idx_));
    }

    void init_scratch() noexcept {
        wrap(nullptr, 0, 0);
        scanned_ = true;
    }

    void bind_parent(OrderEnvelopeView* parent, uint32_t item_idx) noexcept {
        parent_ = parent;
        parent_item_idx_ = item_idx;
    }

    bool is_clean() const noexcept {
        return (same_size_dirty_mask_ | var_size_dirty_mask_ | appended_mask_) == 0;
    }

    bool is_same_size_dirty_only() const noexcept {
        return (var_size_dirty_mask_ | appended_mask_) == 0 && same_size_dirty_mask_ != 0;
    }

    void validate_deep() { ensure_scanned(); }

    void decode_all_eager() {
        item_id_ = 0ULL;
        sku_ = std::string_view();
        quantity_ = 0;
        price_cents_ = 0ULL;
        description_ = std::string_view();
        uint32_t cursor = raw_off_;
        const uint32_t limit = raw_off_ + raw_len_;
        const uint8_t* b = buf_;
        while (cursor < limit) {
            uint32_t tag = b[cursor++];
            if (tag & 0x80U) [[unlikely]] {
                cursor--;
                if (!read_varint32_checked(b, cursor, limit, tag)) {
                    throw std::runtime_error("Truncated LineItem tag varint");
                }
            }
            if ((tag >> 3) == 0U) [[unlikely]] {
                throw std::runtime_error("Invalid LineItem field_number 0");
            }
            switch (tag) {
                case 8: {
                    uint32_t val_pos = cursor;
                    if (!skip_varint_swar_checked(b, cursor, limit, cursor)) {
                        throw std::runtime_error("Truncated LineItem item_id");
                    }
                    item_id_ = read_varint64_at(b, val_pos);
                    break;
                }
                case 18: {
                    uint32_t len = 0;
                    if (cursor < limit && !(b[cursor] & 0x80U)) [[likely]] {
                        len = b[cursor++];
                    } else if (!read_varint32_checked(b, cursor, limit, len)) {
                        throw std::runtime_error("Truncated LineItem sku length");
                    }
                    if (len > limit - cursor) [[unlikely]] {
                        throw std::runtime_error("Truncated LineItem sku payload");
                    }
                    sku_ = std::string_view(reinterpret_cast<const char*>(b + cursor), len);
                    cursor += len;
                    break;
                }
                case 24: {
                    uint32_t val_pos = cursor;
                    if (!skip_varint_swar_checked(b, cursor, limit, cursor)) {
                        throw std::runtime_error("Truncated LineItem quantity");
                    }
                    quantity_ = static_cast<int32_t>(read_varint32_at(b, val_pos));
                    break;
                }
                case 32: {
                    uint32_t val_pos = cursor;
                    if (!skip_varint_swar_checked(b, cursor, limit, cursor)) {
                        throw std::runtime_error("Truncated LineItem price_cents");
                    }
                    price_cents_ = read_varint64_at(b, val_pos);
                    break;
                }
                case 42: {
                    uint32_t len = 0;
                    if (cursor < limit && !(b[cursor] & 0x80U)) [[likely]] {
                        len = b[cursor++];
                    } else if (!read_varint32_checked(b, cursor, limit, len)) {
                        throw std::runtime_error("Truncated LineItem description length");
                    }
                    if (len > limit - cursor) [[unlikely]] {
                        throw std::runtime_error("Truncated LineItem description payload");
                    }
                    description_ = std::string_view(reinterpret_cast<const char*>(b + cursor), len);
                    cursor += len;
                    break;
                }
                default: {
                    uint8_t wt = static_cast<uint8_t>(tag & 7U);
                    if (wt == WIRETYPE_VARINT) {
                        if (!skip_varint_swar_checked(b, cursor, limit, cursor)) {
                            throw std::runtime_error("Truncated LineItem unknown varint");
                        }
                    } else if (wt == WIRETYPE_FIXED64) {
                        if (8U > limit - cursor) throw std::runtime_error("Truncated LineItem unknown fixed64");
                        cursor += 8U;
                    } else if (wt == WIRETYPE_LENGTH_DELIMITED) {
                        uint32_t l = 0;
                        if (!read_varint32_checked(b, cursor, limit, l) || l > limit - cursor) {
                            throw std::runtime_error("Truncated LineItem unknown length-delimited");
                        }
                        cursor += l;
                    } else if (wt == WIRETYPE_FIXED32) {
                        if (4U > limit - cursor) throw std::runtime_error("Truncated LineItem unknown fixed32");
                        cursor += 4U;
                    } else {
                        throw std::runtime_error("Unsupported LineItem wire_type");
                    }
                    break;
                }
            }
        }
        decoded_mask_ = 0x1FU;
    }

    uint64_t item_id() {
        if (!(decoded_mask_ & BIT_ITEM_ID)) {
            ensure_scanned();
            int32_t idx = slot_tape_idx_[SLOT_ITEM_ID];
            item_id_ = idx >= 0 ? read_varint64_at(buf_, tape_[static_cast<uint32_t>(idx)].value_offset()) : 0ULL;
            decoded_mask_ |= BIT_ITEM_ID;
        }
        return item_id_;
    }

    std::string_view sku() {
        if (!(decoded_mask_ & BIT_SKU)) {
            ensure_scanned();
            int32_t idx = slot_tape_idx_[SLOT_SKU];
            sku_ = idx >= 0
                ? std::string_view(reinterpret_cast<const char*>(buf_ + tape_[static_cast<uint32_t>(idx)].value_offset()),
                                   tape_[static_cast<uint32_t>(idx)].value_length())
                : std::string_view();
            decoded_mask_ |= BIT_SKU;
        }
        return sku_;
    }

    int32_t quantity() {
        if (!(decoded_mask_ & BIT_QUANTITY)) {
            ensure_scanned();
            int32_t idx = slot_tape_idx_[SLOT_QUANTITY];
            quantity_ = idx >= 0 ? static_cast<int32_t>(read_varint32_at(buf_, tape_[static_cast<uint32_t>(idx)].value_offset())) : 0;
            decoded_mask_ |= BIT_QUANTITY;
        }
        return quantity_;
    }

    uint64_t price_cents() {
        if (!(decoded_mask_ & BIT_PRICE_CENTS)) {
            ensure_scanned();
            int32_t idx = slot_tape_idx_[SLOT_PRICE_CENTS];
            price_cents_ = idx >= 0 ? read_varint64_at(buf_, tape_[static_cast<uint32_t>(idx)].value_offset()) : 0ULL;
            decoded_mask_ |= BIT_PRICE_CENTS;
        }
        return price_cents_;
    }

    std::string_view description() {
        if (!(decoded_mask_ & BIT_DESCRIPTION)) {
            ensure_scanned();
            int32_t idx = slot_tape_idx_[SLOT_DESCRIPTION];
            description_ = idx >= 0
                ? std::string_view(reinterpret_cast<const char*>(buf_ + tape_[static_cast<uint32_t>(idx)].value_offset()),
                                   tape_[static_cast<uint32_t>(idx)].value_length())
                : std::string_view();
            decoded_mask_ |= BIT_DESCRIPTION;
        }
        return description_;
    }

    LineItemView& set_item_id(uint64_t v) {
        ensure_scanned();
        item_id_ = v;
        decoded_mask_ |= BIT_ITEM_ID;
        present_mask_ |= BIT_ITEM_ID;
        mark_modified(SLOT_ITEM_ID, BIT_ITEM_ID, WIRETYPE_VARINT, varint64_size(v));
        notify_parent();
        return *this;
    }

    LineItemView& set_sku(std::string_view v) {
        ensure_scanned();
        sku_ = v;
        decoded_mask_ |= BIT_SKU;
        present_mask_ |= BIT_SKU;
        mark_modified(SLOT_SKU, BIT_SKU, WIRETYPE_LENGTH_DELIMITED, static_cast<uint32_t>(v.size()));
        notify_parent();
        return *this;
    }

    LineItemView& set_quantity(int32_t v) {
        ensure_scanned();
        quantity_ = v;
        decoded_mask_ |= BIT_QUANTITY;
        present_mask_ |= BIT_QUANTITY;
        mark_modified(SLOT_QUANTITY, BIT_QUANTITY, WIRETYPE_VARINT, varint_int32_size(v));
        notify_parent();
        return *this;
    }

    LineItemView& set_price_cents(uint64_t v) {
        ensure_scanned();
        price_cents_ = v;
        decoded_mask_ |= BIT_PRICE_CENTS;
        present_mask_ |= BIT_PRICE_CENTS;
        mark_modified(SLOT_PRICE_CENTS, BIT_PRICE_CENTS, WIRETYPE_VARINT, varint64_size(v));
        notify_parent();
        return *this;
    }

    LineItemView& set_description(std::string_view v) {
        ensure_scanned();
        description_ = v;
        decoded_mask_ |= BIT_DESCRIPTION;
        present_mask_ |= BIT_DESCRIPTION;
        mark_modified(SLOT_DESCRIPTION, BIT_DESCRIPTION, WIRETYPE_LENGTH_DELIMITED, static_cast<uint32_t>(v.size()));
        notify_parent();
        return *this;
    }

    void apply_in_place_patches(uint8_t* target, uint32_t base_offset) const noexcept {
        uint32_t mask = same_size_dirty_mask_;
        while (mask != 0U) {
            int slot = __builtin_ctz(mask);
            int32_t idx = slot_tape_idx_[slot];
            if (idx >= 0 && tape_[static_cast<uint32_t>(idx)].flags == FLAG_DIRTY_SAME_SIZE) {
                uint8_t* patch_ptr = target + (tape_[static_cast<uint32_t>(idx)].value_offset() - base_offset);
                switch (slot) {
                    case SLOT_ITEM_ID:     WireWriter::patch_varint64_at(patch_ptr, item_id_); break;
                    case SLOT_SKU:         if (!sku_.empty()) std::memcpy(patch_ptr, sku_.data(), sku_.size()); break;
                    case SLOT_QUANTITY:    WireWriter::patch_int32_at(patch_ptr, quantity_); break;
                    case SLOT_PRICE_CENTS: WireWriter::patch_varint64_at(patch_ptr, price_cents_); break;
                    case SLOT_DESCRIPTION: if (!description_.empty()) std::memcpy(patch_ptr, description_.data(), description_.size()); break;
                }
            }
            mask &= (mask - 1U);
        }
    }

    void write_spliced_to(WireWriter& w) const noexcept {
        if (is_clean()) {
            if (raw_len_ > 0) w.write_raw(buf_ + raw_off_, raw_len_);
            return;
        }
        if (is_same_size_dirty_only()) {
            uint8_t* start = w.ptr;
            w.write_raw(buf_ + raw_off_, raw_len_);
            apply_in_place_patches(start, raw_off_);
            return;
        }
        write_spliced_tape(
                buf_, tape_, w,
                [this](uint32_t, const TapeEntry& e, WireWriter& dw) { write_field(e.field_number(), dw); },
                [this](WireWriter& aw) {
                    uint32_t m = appended_mask_;
                    while (m != 0U) {
                        int slot = __builtin_ctz(m);
                        write_field(static_cast<uint32_t>(slot + 1), aw);
                        m &= (m - 1U);
                    }
                });
    }

    size_t max_serialized_size() const noexcept {
        if (is_clean() || is_same_size_dirty_only()) return raw_len_;
        size_t sz = raw_len_;
        if ((var_size_dirty_mask_ | appended_mask_) & BIT_ITEM_ID)     sz += 1 + varint64_size(item_id_);
        if ((var_size_dirty_mask_ | appended_mask_) & BIT_SKU)         sz += 1 + varint32_size(static_cast<uint32_t>(sku_.size())) + sku_.size();
        if ((var_size_dirty_mask_ | appended_mask_) & BIT_QUANTITY)    sz += 1 + varint_int32_size(quantity_);
        if ((var_size_dirty_mask_ | appended_mask_) & BIT_PRICE_CENTS) sz += 1 + varint64_size(price_cents_);
        if ((var_size_dirty_mask_ | appended_mask_) & BIT_DESCRIPTION) sz += 1 + varint32_size(static_cast<uint32_t>(description_.size())) + description_.size();
        return sz;
    }

private:
    void ensure_scanned() {
        if (!scanned_) [[unlikely]] {
            scanned_ = true;
            if (buf_ != nullptr && raw_len_ > 0) {
                scan_wire_range(buf_, raw_off_, raw_off_ + raw_len_, tape_, [this](uint32_t idx, TapeEntry& e) {
                    int slot = -1;
                    switch (e.field_number()) {
                        case 1: if (e.wire_type() == WIRETYPE_VARINT) slot = SLOT_ITEM_ID; break;
                        case 2: if (e.wire_type() == WIRETYPE_LENGTH_DELIMITED) slot = SLOT_SKU; break;
                        case 3: if (e.wire_type() == WIRETYPE_VARINT) slot = SLOT_QUANTITY; break;
                        case 4: if (e.wire_type() == WIRETYPE_VARINT) slot = SLOT_PRICE_CENTS; break;
                        case 5: if (e.wire_type() == WIRETYPE_LENGTH_DELIMITED) slot = SLOT_DESCRIPTION; break;
                    }
                    if (slot >= 0) [[likely]] {
                        int32_t prev = slot_tape_idx_[slot];
                        if (prev >= 0) [[unlikely]] tape_[static_cast<uint32_t>(prev)].flags = FLAG_SHADOWED;
                        slot_tape_idx_[slot] = static_cast<int32_t>(idx);
                        present_mask_ |= (1U << slot);
                    }
                });
            }
        }
    }

    void mark_modified(int slot, uint32_t bit, uint8_t expected_wt, uint32_t new_val_len) noexcept {
        int32_t idx = slot_tape_idx_[slot];
        if (idx >= 0) {
            TapeEntry& e = tape_[static_cast<uint32_t>(idx)];
            if (e.wire_type() == expected_wt && e.value_length() == new_val_len) {
                e.flags = FLAG_DIRTY_SAME_SIZE;
                same_size_dirty_mask_ |= bit;
                var_size_dirty_mask_ &= ~bit;
                return;
            }
            e.flags = FLAG_DIRTY_VAR_SIZE;
            same_size_dirty_mask_ &= ~bit;
            var_size_dirty_mask_ |= bit;
            return;
        }
        appended_mask_ |= bit;
        same_size_dirty_mask_ &= ~bit;
        var_size_dirty_mask_ |= bit;
    }

    inline void notify_parent() noexcept;

    void write_field(uint32_t field_num, WireWriter& w) const noexcept {
        switch (field_num) {
            case 1: if (item_id_ != 0 || slot_tape_idx_[0] >= 0) { w.write_byte(0x08); w.write_varint64(item_id_); } break;
            case 2: if (!sku_.empty() || slot_tape_idx_[1] >= 0) { w.write_string(0x12, sku_); } break;
            case 3: if (quantity_ != 0 || slot_tape_idx_[2] >= 0) { w.write_byte(0x18); w.write_int32(quantity_); } break;
            case 4: if (price_cents_ != 0 || slot_tape_idx_[3] >= 0) { w.write_byte(0x20); w.write_varint64(price_cents_); } break;
            case 5: if (!description_.empty() || slot_tape_idx_[4] >= 0) { w.write_string(0x2A, description_); } break;
        }
    }
};

class OrderEnvelopeView {
public:
    static constexpr int SLOT_ORDER_ID   = 0;
    static constexpr int SLOT_BUYER_NAME = 1;
    static constexpr int SLOT_STATUS     = 2;
    static constexpr int SLOT_UPDATED_AT = 3;
    static constexpr int SLOT_USER       = 4;
    static constexpr int SLOT_ITEMS      = 5;
    static constexpr int SLOT_PAYLOAD    = 6;
    static constexpr int SLOT_AUTH_TOKEN = 7;
    static constexpr int SLOT_TRACE_ID   = 8;
    static constexpr int SLOT_TOTAL      = 9;

    static constexpr uint32_t BIT_ORDER_ID   = 1U << 0;
    static constexpr uint32_t BIT_BUYER_NAME = 1U << 1;
    static constexpr uint32_t BIT_STATUS     = 1U << 2;
    static constexpr uint32_t BIT_UPDATED_AT = 1U << 3;
    static constexpr uint32_t BIT_USER       = 1U << 4;
    static constexpr uint32_t BIT_ITEMS      = 1U << 5;
    static constexpr uint32_t BIT_PAYLOAD    = 1U << 6;
    static constexpr uint32_t BIT_AUTH_TOKEN = 1U << 7;
    static constexpr uint32_t BIT_TRACE_ID   = 1U << 8;
    static constexpr uint32_t BIT_TOTAL      = 1U << 9;

    const uint8_t* buf_ = nullptr;
    uint32_t raw_off_ = 0;
    uint32_t raw_len_ = 0;
    bool scanned_ = false;

    OrderEnvelopeView() noexcept {
        std::memset(slot_tape_idx_, -1, sizeof(slot_tape_idx_));
    }

    void wrap(const uint8_t* buf, uint32_t off, uint32_t len) noexcept {
        buf_ = buf;
        raw_off_ = off;
        raw_len_ = len;
        tape_.reset();
        item_tape_idx_.clear();
        user_seg_tape_idx_.clear();
        active_items_.clear();
        active_item_states_.clear();
        appended_items_.clear();
        pool_used_ = 0;
        scanned_ = false;
        has_deleted_ = false;
        present_mask_ = 0;
        decoded_mask_ = 0;
        same_size_dirty_mask_ = 0;
        var_size_dirty_mask_ = 0;
        appended_mask_ = 0;
        same_size_item_count_ = 0;
        var_size_item_count_ = 0;
        std::memset(slot_tape_idx_, -1, sizeof(slot_tape_idx_));
    }

    void init_scratch() noexcept {
        wrap(nullptr, 0, 0);
        scanned_ = true;
    }

    bool is_clean() const noexcept {
        return (same_size_dirty_mask_ | var_size_dirty_mask_ | appended_mask_) == 0 && !has_deleted_;
    }

    bool is_same_size_dirty_only() const noexcept {
        return (var_size_dirty_mask_ | appended_mask_) == 0 && !has_deleted_ && same_size_dirty_mask_ != 0;
    }

    void validate_deep() {
        ensure_scanned();
        if (!user_seg_tape_idx_.empty() || (present_mask_ & BIT_USER)) {
            user().validate_deep();
        }
        uint32_t n = items_count();
        for (uint32_t i = 0; i < n; i++) {
            items(i).validate_deep();
        }
    }

    // ==================== Field Getters (Cached on First Access) ====================

    uint64_t order_id() {
        if (!(decoded_mask_ & BIT_ORDER_ID)) {
            ensure_scanned();
            int32_t idx = slot_tape_idx_[SLOT_ORDER_ID];
            order_id_ = idx >= 0 ? read_varint64_at(buf_, tape_[static_cast<uint32_t>(idx)].value_offset()) : 0ULL;
            decoded_mask_ |= BIT_ORDER_ID;
        }
        return order_id_;
    }

    std::string_view buyer_name() {
        if (!(decoded_mask_ & BIT_BUYER_NAME)) {
            ensure_scanned();
            int32_t idx = slot_tape_idx_[SLOT_BUYER_NAME];
            buyer_name_ = idx >= 0
                ? std::string_view(reinterpret_cast<const char*>(buf_ + tape_[static_cast<uint32_t>(idx)].value_offset()),
                                   tape_[static_cast<uint32_t>(idx)].value_length())
                : std::string_view();
            decoded_mask_ |= BIT_BUYER_NAME;
        }
        return buyer_name_;
    }

    int32_t status() {
        if (!(decoded_mask_ & BIT_STATUS)) {
            ensure_scanned();
            int32_t idx = slot_tape_idx_[SLOT_STATUS];
            status_ = idx >= 0 ? static_cast<int32_t>(read_varint32_at(buf_, tape_[static_cast<uint32_t>(idx)].value_offset())) : 0;
            decoded_mask_ |= BIT_STATUS;
        }
        return status_;
    }

    uint64_t updated_at_fixed64() {
        if (!(decoded_mask_ & BIT_UPDATED_AT)) {
            ensure_scanned();
            int32_t idx = slot_tape_idx_[SLOT_UPDATED_AT];
            updated_at_fixed64_ = idx >= 0 ? read_fixed64_at(buf_, tape_[static_cast<uint32_t>(idx)].value_offset()) : 0ULL;
            decoded_mask_ |= BIT_UPDATED_AT;
        }
        return updated_at_fixed64_;
    }

    UserProfileView& user() {
        if (!(decoded_mask_ & BIT_USER)) {
            ensure_scanned();
            if (user_seg_tape_idx_.size() > 1U) {
                user_.wrap_multi_segments(buf_, tape_.data(), user_seg_tape_idx_.data(),
                                          static_cast<uint32_t>(user_seg_tape_idx_.size()));
            } else if (user_seg_tape_idx_.size() == 1U) {
                const TapeEntry& e = tape_[user_seg_tape_idx_[0]];
                user_.wrap(buf_, e.value_offset(), e.value_length());
            } else {
                user_.init_scratch();
            }
            user_.bind_parent(this);
            decoded_mask_ |= BIT_USER;
        }
        return user_;
    }

    UserProfileView& mutable_user() {
        return user();
    }

    uint32_t items_count() {
        ensure_scanned();
        return static_cast<uint32_t>(item_tape_idx_.size() + appended_items_.size());
    }

    LineItemView& items(uint32_t index) {
        ensure_scanned();
        if (index >= item_tape_idx_.size()) {
            size_t app_idx = index - item_tape_idx_.size();
            if (app_idx >= appended_items_.size()) throw std::out_of_range("LineItem index out of range");
            return *appended_items_[app_idx];
        }
        if (active_items_.size() < item_tape_idx_.size()) {
            active_items_.resize(item_tape_idx_.size(), nullptr);
            active_item_states_.resize(item_tape_idx_.size(), 0);
        }
        LineItemView* view = active_items_[index];
        if (view == nullptr) {
            uint32_t t_idx = item_tape_idx_[index];
            view = borrow_item_view();
            view->wrap(buf_, tape_[t_idx].value_offset(), tape_[t_idx].value_length());
            view->bind_parent(this, index);
            active_items_[index] = view;
        }
        return *view;
    }

    void read_item_eager(uint32_t index, LineItemView& out_item) const {
        uint32_t t_idx = item_tape_idx_[index];
        out_item.buf_ = buf_;
        out_item.raw_off_ = tape_[t_idx].value_offset();
        out_item.raw_len_ = tape_[t_idx].value_length();
        out_item.decode_all_eager();
    }

    LineItemView& add_item() {
        ensure_scanned();
        LineItemView* view = borrow_item_view();
        view->init_scratch();
        uint32_t logical_idx = static_cast<uint32_t>(item_tape_idx_.size() + appended_items_.size());
        view->bind_parent(this, logical_idx);
        appended_items_.push_back(view);
        present_mask_ |= BIT_ITEMS;
        appended_mask_ |= BIT_ITEMS;
        var_size_dirty_mask_ |= BIT_ITEMS;
        return *view;
    }

    std::string_view payload() {
        if (!(decoded_mask_ & BIT_PAYLOAD)) {
            ensure_scanned();
            int32_t idx = slot_tape_idx_[SLOT_PAYLOAD];
            payload_ = idx >= 0
                ? std::string_view(reinterpret_cast<const char*>(buf_ + tape_[static_cast<uint32_t>(idx)].value_offset()),
                                   tape_[static_cast<uint32_t>(idx)].value_length())
                : std::string_view();
            decoded_mask_ |= BIT_PAYLOAD;
        }
        return payload_;
    }

    std::string_view auth_token() {
        if (!(decoded_mask_ & BIT_AUTH_TOKEN)) {
            ensure_scanned();
            int32_t idx = slot_tape_idx_[SLOT_AUTH_TOKEN];
            auth_token_ = idx >= 0
                ? std::string_view(reinterpret_cast<const char*>(buf_ + tape_[static_cast<uint32_t>(idx)].value_offset()),
                                   tape_[static_cast<uint32_t>(idx)].value_length())
                : std::string_view();
            decoded_mask_ |= BIT_AUTH_TOKEN;
        }
        return auth_token_;
    }

    std::string_view trace_id() {
        if (!(decoded_mask_ & BIT_TRACE_ID)) {
            ensure_scanned();
            int32_t idx = slot_tape_idx_[SLOT_TRACE_ID];
            trace_id_ = idx >= 0
                ? std::string_view(reinterpret_cast<const char*>(buf_ + tape_[static_cast<uint32_t>(idx)].value_offset()),
                                   tape_[static_cast<uint32_t>(idx)].value_length())
                : std::string_view();
            decoded_mask_ |= BIT_TRACE_ID;
        }
        return trace_id_;
    }

    uint64_t total_cents() {
        if (!(decoded_mask_ & BIT_TOTAL)) {
            ensure_scanned();
            int32_t idx = slot_tape_idx_[SLOT_TOTAL];
            total_cents_ = idx >= 0 ? read_varint64_at(buf_, tape_[static_cast<uint32_t>(idx)].value_offset()) : 0ULL;
            decoded_mask_ |= BIT_TOTAL;
        }
        return total_cents_;
    }

    // ==================== Field Setters (Update Cache + Dirty Masks) ====================

    OrderEnvelopeView& set_order_id(uint64_t v) {
        ensure_scanned();
        order_id_ = v;
        decoded_mask_ |= BIT_ORDER_ID;
        present_mask_ |= BIT_ORDER_ID;
        mark_modified(SLOT_ORDER_ID, BIT_ORDER_ID, WIRETYPE_VARINT, varint64_size(v));
        return *this;
    }

    OrderEnvelopeView& set_buyer_name(std::string_view v) {
        ensure_scanned();
        buyer_name_ = v;
        decoded_mask_ |= BIT_BUYER_NAME;
        present_mask_ |= BIT_BUYER_NAME;
        mark_modified(SLOT_BUYER_NAME, BIT_BUYER_NAME, WIRETYPE_LENGTH_DELIMITED, static_cast<uint32_t>(v.size()));
        return *this;
    }

    OrderEnvelopeView& set_status(int32_t v) {
        ensure_scanned();
        status_ = v;
        decoded_mask_ |= BIT_STATUS;
        present_mask_ |= BIT_STATUS;
        mark_modified(SLOT_STATUS, BIT_STATUS, WIRETYPE_VARINT, varint_int32_size(v));
        return *this;
    }

    OrderEnvelopeView& set_updated_at_fixed64(uint64_t v) {
        ensure_scanned();
        updated_at_fixed64_ = v;
        decoded_mask_ |= BIT_UPDATED_AT;
        present_mask_ |= BIT_UPDATED_AT;
        mark_modified(SLOT_UPDATED_AT, BIT_UPDATED_AT, WIRETYPE_FIXED64, 8U);
        return *this;
    }

    OrderEnvelopeView& set_payload(std::string_view v) {
        ensure_scanned();
        payload_ = v;
        decoded_mask_ |= BIT_PAYLOAD;
        present_mask_ |= BIT_PAYLOAD;
        mark_modified(SLOT_PAYLOAD, BIT_PAYLOAD, WIRETYPE_LENGTH_DELIMITED, static_cast<uint32_t>(v.size()));
        return *this;
    }

    OrderEnvelopeView& set_auth_token(std::string_view v) {
        ensure_scanned();
        auth_token_ = v;
        decoded_mask_ |= BIT_AUTH_TOKEN;
        present_mask_ |= BIT_AUTH_TOKEN;
        mark_modified(SLOT_AUTH_TOKEN, BIT_AUTH_TOKEN, WIRETYPE_LENGTH_DELIMITED, static_cast<uint32_t>(v.size()));
        return *this;
    }

    OrderEnvelopeView& clear_auth_token() {
        ensure_scanned();
        auth_token_ = std::string_view();
        decoded_mask_ |= BIT_AUTH_TOKEN;
        present_mask_ &= ~BIT_AUTH_TOKEN;
        appended_mask_ &= ~BIT_AUTH_TOKEN;
        same_size_dirty_mask_ &= ~BIT_AUTH_TOKEN;
        int32_t idx = slot_tape_idx_[SLOT_AUTH_TOKEN];
        if (idx >= 0) {
            tape_[static_cast<uint32_t>(idx)].flags = FLAG_DELETED;
            has_deleted_ = true;
            var_size_dirty_mask_ |= BIT_AUTH_TOKEN;
        } else {
            var_size_dirty_mask_ &= ~BIT_AUTH_TOKEN;
        }
        return *this;
    }

    OrderEnvelopeView& set_trace_id(std::string_view v) {
        ensure_scanned();
        trace_id_ = v;
        decoded_mask_ |= BIT_TRACE_ID;
        present_mask_ |= BIT_TRACE_ID;
        mark_modified(SLOT_TRACE_ID, BIT_TRACE_ID, WIRETYPE_LENGTH_DELIMITED, static_cast<uint32_t>(v.size()));
        return *this;
    }

    OrderEnvelopeView& set_total_cents(uint64_t v) {
        ensure_scanned();
        total_cents_ = v;
        decoded_mask_ |= BIT_TOTAL;
        present_mask_ |= BIT_TOTAL;
        mark_modified(SLOT_TOTAL, BIT_TOTAL, WIRETYPE_VARINT, varint64_size(v));
        return *this;
    }

    // ==================== Merge / Serialization API ====================

    size_t max_merged_size() const noexcept {
        if (is_clean() || is_same_size_dirty_only()) return raw_len_;
        size_t sz = raw_len_;
        uint32_t d = var_size_dirty_mask_ | appended_mask_;
        if (d & BIT_ORDER_ID)   sz += 1 + varint64_size(order_id_);
        if (d & BIT_BUYER_NAME) sz += 1 + varint32_size(static_cast<uint32_t>(buyer_name_.size())) + buyer_name_.size();
        if (d & BIT_STATUS)     sz += 1 + varint_int32_size(status_);
        if (d & BIT_UPDATED_AT) sz += 1 + 8;
        if (d & BIT_USER) {
            size_t usz = user_.max_serialized_size();
            sz += 1 + varint32_size(static_cast<uint32_t>(usz)) + usz;
        }
        if (d & BIT_ITEMS) {
            for (size_t i = 0; i < active_items_.size(); i++) {
                if (active_items_[i] && active_item_states_[i] == 2) {
                    size_t isz = active_items_[i]->max_serialized_size();
                    sz += 1 + varint32_size(static_cast<uint32_t>(isz)) + isz;
                }
            }
            for (const LineItemView* app : appended_items_) {
                size_t isz = app->max_serialized_size();
                sz += 1 + varint32_size(static_cast<uint32_t>(isz)) + isz;
            }
        }
        if (d & BIT_PAYLOAD)    sz += 1 + varint32_size(static_cast<uint32_t>(payload_.size())) + payload_.size();
        if (d & BIT_AUTH_TOKEN) sz += 1 + varint32_size(static_cast<uint32_t>(auth_token_.size())) + auth_token_.size();
        if (d & BIT_TRACE_ID)   sz += 1 + varint32_size(static_cast<uint32_t>(trace_id_.size())) + trace_id_.size();
        if (d & BIT_TOTAL)      sz += 1 + varint64_size(total_cents_);
        return sz;
    }

    size_t write_to_buffer(uint8_t* out_buf) const noexcept {
        if (is_clean()) {
            if (raw_len_ > 0) std::memcpy(out_buf, buf_ + raw_off_, raw_len_);
            return raw_len_;
        }
        if (is_same_size_dirty_only()) {
            std::memcpy(out_buf, buf_ + raw_off_, raw_len_);
            apply_in_place_patches(out_buf, raw_off_);
            return raw_len_;
        }
        WireWriter w(out_buf);
        write_spliced_tape(
                buf_, tape_, w,
                [this](uint32_t tape_idx, const TapeEntry& e, WireWriter& dw) {
                    write_dirty_tape_entry(tape_idx, e.field_number(), dw);
                },
                [this](WireWriter& aw) {
                    if (appended_mask_ != 0U) write_appended_fields(aw);
                });
        return w.size();
    }

    size_t merge_to(uint8_t* out_buf, size_t out_cap) const {
        if (out_cap < max_merged_size()) {
            throw std::length_error("OrderEnvelopeView output buffer smaller than max_merged_size");
        }
        return write_to_buffer(out_buf);
    }

    void merge_to(std::string& out) const {
        size_t cap = max_merged_size();
        if (cap <= 256U) {
            uint8_t stack_buf[256];
            size_t actual = write_to_buffer(stack_buf);
            out.assign(reinterpret_cast<const char*>(stack_buf), actual);
            return;
        }
        out.resize(cap);
        size_t actual = write_to_buffer(reinterpret_cast<uint8_t*>(out.data()));
        out.resize(actual);
    }

private:
    friend class UserProfileView;
    friend class LineItemView;

    template <typename T, uint32_t InlineCap>
    struct SmallPodList {
        T inline_buf[InlineCap];
        std::vector<T> heap_buf;
        uint32_t count = 0;
        bool spilled = false;

        void clear() noexcept { count = 0; spilled = false; heap_buf.clear(); }
        uint32_t size() const noexcept { return count; }
        bool empty() const noexcept { return count == 0; }
        T* data() noexcept { return spilled ? heap_buf.data() : inline_buf; }
        const T* data() const noexcept { return spilled ? heap_buf.data() : inline_buf; }
        T& operator[](size_t i) noexcept { return data()[i]; }
        const T& operator[](size_t i) const noexcept { return data()[i]; }
        T& back() noexcept { return data()[count - 1]; }
        const T& back() const noexcept { return data()[count - 1]; }
        void push_back(T v) {
            if (!spilled) {
                if (count < InlineCap) [[likely]] {
                    inline_buf[count++] = v;
                    return;
                }
                heap_buf.assign(inline_buf, inline_buf + InlineCap);
                spilled = true;
            }
            heap_buf.push_back(v);
            count++;
        }
    };

    TapeStorage<128> tape_;
    int32_t slot_tape_idx_[10]{};
    SmallPodList<uint32_t, 128> item_tape_idx_;
    SmallPodList<uint32_t, 4> user_seg_tape_idx_;

    bool has_deleted_ = false;
    uint32_t present_mask_ = 0;
    uint32_t decoded_mask_ = 0;
    uint32_t same_size_dirty_mask_ = 0;
    uint32_t var_size_dirty_mask_ = 0;
    uint32_t appended_mask_ = 0;

    uint64_t order_id_ = 0;
    std::string_view buyer_name_;
    int32_t status_ = 0;
    uint64_t updated_at_fixed64_ = 0;
    UserProfileView user_;
    std::vector<LineItemView*> active_items_;
    std::vector<uint8_t> active_item_states_; // 0=clean, 1=same_size, 2=var_size
    std::vector<LineItemView*> appended_items_;
    uint32_t same_size_item_count_ = 0;
    uint32_t var_size_item_count_ = 0;

    LineItemView inline_pool_[2]; // Zero heap alloc when <= 2 items accessed/mutated!
    std::vector<std::unique_ptr<LineItemView>> heap_pool_;
    size_t pool_used_ = 0;

    std::string_view payload_;
    std::string_view auth_token_;
    std::string_view trace_id_;
    uint64_t total_cents_ = 0;

    LineItemView* borrow_item_view() {
        size_t idx = pool_used_++;
        if (idx < 2U) return &inline_pool_[idx];
        size_t h_idx = idx - 2U;
        if (h_idx >= heap_pool_.size()) {
            heap_pool_.push_back(std::make_unique<LineItemView>());
        }
        return heap_pool_[h_idx].get();
    }

    void ensure_scanned() {
        if (!scanned_) [[unlikely]] {
            scanned_ = true;
            if (buf_ != nullptr && raw_len_ > 0) {
                scan_wire_range(buf_, raw_off_, raw_off_ + raw_len_, tape_, [this](uint32_t idx, TapeEntry& e) {
                    if (e.field_number() == 6 && e.wire_type() == WIRETYPE_LENGTH_DELIMITED) {
                        item_tape_idx_.push_back(idx);
                        present_mask_ |= BIT_ITEMS;
                        return;
                    }
                    if (e.field_number() == 5 && e.wire_type() == WIRETYPE_LENGTH_DELIMITED) {
                        user_seg_tape_idx_.push_back(idx);
                        slot_tape_idx_[SLOT_USER] = static_cast<int32_t>(idx);
                        present_mask_ |= BIT_USER;
                        return;
                    }
                    int slot = -1;
                    switch (e.field_number()) {
                        case 1:  if (e.wire_type() == WIRETYPE_VARINT) slot = SLOT_ORDER_ID; break;
                        case 2:  if (e.wire_type() == WIRETYPE_LENGTH_DELIMITED) slot = SLOT_BUYER_NAME; break;
                        case 3:  if (e.wire_type() == WIRETYPE_VARINT) slot = SLOT_STATUS; break;
                        case 4:  if (e.wire_type() == WIRETYPE_FIXED64) slot = SLOT_UPDATED_AT; break;
                        case 7:  if (e.wire_type() == WIRETYPE_LENGTH_DELIMITED) slot = SLOT_PAYLOAD; break;
                        case 8:  if (e.wire_type() == WIRETYPE_LENGTH_DELIMITED) slot = SLOT_AUTH_TOKEN; break;
                        case 9:  if (e.wire_type() == WIRETYPE_LENGTH_DELIMITED) slot = SLOT_TRACE_ID; break;
                        case 10: if (e.wire_type() == WIRETYPE_VARINT) slot = SLOT_TOTAL; break;
                    }
                    if (slot >= 0) [[likely]] {
                        int32_t prev = slot_tape_idx_[slot];
                        if (prev >= 0) [[unlikely]] tape_[static_cast<uint32_t>(prev)].flags = FLAG_SHADOWED;
                        slot_tape_idx_[slot] = static_cast<int32_t>(idx);
                        present_mask_ |= (1U << slot);
                    }
                });
            }
        }
    }

    void mark_modified(int slot, uint32_t bit, uint8_t expected_wt, uint32_t new_val_len) noexcept {
        int32_t idx = slot_tape_idx_[slot];
        if (idx >= 0) {
            TapeEntry& e = tape_[static_cast<uint32_t>(idx)];
            if (e.wire_type() == expected_wt && e.value_length() == new_val_len) {
                e.flags = FLAG_DIRTY_SAME_SIZE;
                same_size_dirty_mask_ |= bit;
                var_size_dirty_mask_ &= ~bit;
                return;
            }
            e.flags = FLAG_DIRTY_VAR_SIZE;
            same_size_dirty_mask_ &= ~bit;
            var_size_dirty_mask_ |= bit;
            return;
        }
        appended_mask_ |= bit;
        same_size_dirty_mask_ &= ~bit;
        var_size_dirty_mask_ |= bit;
    }

    void on_user_mutated() noexcept {
        if (user_.is_clean()) {
            same_size_dirty_mask_ &= ~BIT_USER;
            var_size_dirty_mask_ &= ~BIT_USER;
            return;
        }
        present_mask_ |= BIT_USER;
        if (!user_seg_tape_idx_.empty()) {
            if (user_seg_tape_idx_.size() == 1U && user_.is_same_size_dirty_only()) {
                tape_[user_seg_tape_idx_[0]].flags = FLAG_DIRTY_SAME_SIZE;
                same_size_dirty_mask_ |= BIT_USER;
                var_size_dirty_mask_ &= ~BIT_USER;
            } else {
                for (size_t i = 0; i + 1 < user_seg_tape_idx_.size(); i++) {
                    tape_[user_seg_tape_idx_[i]].flags = FLAG_SHADOWED;
                }
                tape_[user_seg_tape_idx_.back()].flags = FLAG_DIRTY_VAR_SIZE;
                same_size_dirty_mask_ &= ~BIT_USER;
                var_size_dirty_mask_ |= BIT_USER;
            }
            appended_mask_ &= ~BIT_USER;
        } else {
            same_size_dirty_mask_ &= ~BIT_USER;
            var_size_dirty_mask_ |= BIT_USER;
            appended_mask_ |= BIT_USER;
        }
    }

    void on_item_mutated(uint32_t item_idx) noexcept {
        if (item_idx >= item_tape_idx_.size()) return; // Appended items already set BIT_ITEMS
        const LineItemView* view = active_items_[item_idx];
        uint8_t old_state = active_item_states_[item_idx];
        uint8_t new_state = view->is_clean() ? 0 : (view->is_same_size_dirty_only() ? 1 : 2);
        if (old_state == 1) same_size_item_count_--;
        else if (old_state == 2) var_size_item_count_--;
        active_item_states_[item_idx] = new_state;
        uint32_t t_idx = item_tape_idx_[item_idx];
        if (new_state == 0) {
            tape_[t_idx].flags = FLAG_CLEAN_RAW;
        } else if (new_state == 1) {
            tape_[t_idx].flags = FLAG_DIRTY_SAME_SIZE;
            same_size_item_count_++;
        } else {
            tape_[t_idx].flags = FLAG_DIRTY_VAR_SIZE;
            var_size_item_count_++;
        }
        if (var_size_item_count_ > 0) {
            same_size_dirty_mask_ &= ~BIT_ITEMS;
            var_size_dirty_mask_ |= BIT_ITEMS;
        } else if (same_size_item_count_ > 0) {
            same_size_dirty_mask_ |= BIT_ITEMS;
            if (appended_items_.empty()) var_size_dirty_mask_ &= ~BIT_ITEMS;
        } else {
            same_size_dirty_mask_ &= ~BIT_ITEMS;
            if (appended_items_.empty()) var_size_dirty_mask_ &= ~BIT_ITEMS;
        }
    }

    void apply_in_place_patches(uint8_t* target, uint32_t base_offset) const noexcept {
        uint32_t mask = same_size_dirty_mask_;
        while (mask != 0U) {
            int slot = __builtin_ctz(mask);
            if (slot == SLOT_USER) {
                user_.apply_in_place_patches(target, base_offset);
            } else if (slot == SLOT_ITEMS) {
                for (size_t i = 0; i < active_items_.size(); i++) {
                    if (active_item_states_[i] == 1 && active_items_[i] != nullptr) {
                        active_items_[i]->apply_in_place_patches(target, base_offset);
                    }
                }
            } else {
                int32_t idx = slot_tape_idx_[slot];
                if (idx >= 0 && tape_[static_cast<uint32_t>(idx)].flags == FLAG_DIRTY_SAME_SIZE) {
                    uint8_t* patch_ptr = target + (tape_[static_cast<uint32_t>(idx)].value_offset() - base_offset);
                    switch (slot) {
                        case SLOT_ORDER_ID:   WireWriter::patch_varint64_at(patch_ptr, order_id_); break;
                        case SLOT_BUYER_NAME: if (!buyer_name_.empty()) std::memcpy(patch_ptr, buyer_name_.data(), buyer_name_.size()); break;
                        case SLOT_STATUS:     WireWriter::patch_int32_at(patch_ptr, status_); break;
                        case SLOT_UPDATED_AT: WireWriter::patch_fixed64_at(patch_ptr, updated_at_fixed64_); break;
                        case SLOT_PAYLOAD:    if (!payload_.empty()) std::memcpy(patch_ptr, payload_.data(), payload_.size()); break;
                        case SLOT_AUTH_TOKEN: if (!auth_token_.empty()) std::memcpy(patch_ptr, auth_token_.data(), auth_token_.size()); break;
                        case SLOT_TRACE_ID:   if (!trace_id_.empty()) std::memcpy(patch_ptr, trace_id_.data(), trace_id_.size()); break;
                        case SLOT_TOTAL:      WireWriter::patch_varint64_at(patch_ptr, total_cents_); break;
                    }
                }
            }
            mask &= (mask - 1U);
        }
    }

    void write_dirty_tape_entry(uint32_t tape_idx, uint32_t field_num, WireWriter& w) const noexcept {
        switch (field_num) {
            case 1:  w.write_byte(0x08); w.write_varint64(order_id_); break;
            case 2:  w.write_string(0x12, buyer_name_); break;
            case 3:  w.write_byte(0x18); w.write_int32(status_); break;
            case 4:  w.write_byte(0x21); w.write_fixed64(updated_at_fixed64_); break;
            case 5:  write_submessage_single_pass(5U, w, [this](WireWriter& cw) { user_.write_spliced_to(cw); }); break;
            case 6: {
                const uint32_t* begin = item_tape_idx_.data();
                const uint32_t* it = std::lower_bound(begin, begin + item_tape_idx_.size(), tape_idx);
                size_t item_idx = static_cast<size_t>(it - begin);
                const LineItemView* item = active_items_[item_idx];
                write_submessage_single_pass(6U, w, [item](WireWriter& cw) { item->write_spliced_to(cw); });
                break;
            }
            case 7:  w.write_string(0x3A, payload_); break;
            case 8:  w.write_string(0x42, auth_token_); break;
            case 9:  w.write_string(0x4A, trace_id_); break;
            case 10: w.write_byte(0x50); w.write_varint64(total_cents_); break;
        }
    }

    void write_appended_fields(WireWriter& w) const noexcept {
        if ((appended_mask_ & BIT_ORDER_ID) && order_id_ != 0) { w.write_byte(0x08); w.write_varint64(order_id_); }
        if ((appended_mask_ & BIT_BUYER_NAME) && !buyer_name_.empty()) { w.write_string(0x12, buyer_name_); }
        if ((appended_mask_ & BIT_STATUS) && status_ != 0) { w.write_byte(0x18); w.write_int32(status_); }
        if ((appended_mask_ & BIT_UPDATED_AT) && updated_at_fixed64_ != 0) { w.write_byte(0x21); w.write_fixed64(updated_at_fixed64_); }
        if (appended_mask_ & BIT_USER) { write_submessage_single_pass(5U, w, [this](WireWriter& cw) { user_.write_spliced_to(cw); }); }
        if (appended_mask_ & BIT_ITEMS) {
            for (const LineItemView* item : appended_items_) {
                write_submessage_single_pass(6U, w, [item](WireWriter& cw) { item->write_spliced_to(cw); });
            }
        }
        if ((appended_mask_ & BIT_PAYLOAD) && !payload_.empty()) { w.write_string(0x3A, payload_); }
        if ((appended_mask_ & BIT_AUTH_TOKEN) && !auth_token_.empty()) { w.write_string(0x42, auth_token_); }
        if ((appended_mask_ & BIT_TRACE_ID) && !trace_id_.empty()) { w.write_string(0x4A, trace_id_); }
        if ((appended_mask_ & BIT_TOTAL) && total_cents_ != 0) { w.write_byte(0x50); w.write_varint64(total_cents_); }
    }
};

inline void UserProfileView::notify_parent() noexcept {
    if (parent_ != nullptr) parent_->on_user_mutated();
}

inline void LineItemView::notify_parent() noexcept {
    if (parent_ != nullptr) parent_->on_item_mutated(parent_item_idx_);
}

using LazyUserProfile = UserProfileView;
using LazyLineItem = LineItemView;
using LazyOrderEnvelope = OrderEnvelopeView;

} // namespace lazyproto
