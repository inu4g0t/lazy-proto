#pragma once

#include <cstdint>
#include <cstring>
#include <string>
#include <string_view>
#include <vector>
#include <stdexcept>
#include <algorithm>

namespace lazyproto {

// ============================================================================
// 1. Wire Constants & Branchless SWAR / BMI2 Primitives
// ============================================================================

constexpr uint8_t WIRETYPE_VARINT           = 0;
constexpr uint8_t WIRETYPE_FIXED64          = 1;
constexpr uint8_t WIRETYPE_LENGTH_DELIMITED = 2;
constexpr uint8_t WIRETYPE_FIXED32          = 5;

constexpr uint8_t FLAG_CLEAN_RAW       = 0;
constexpr uint8_t FLAG_DIRTY_SAME_SIZE = 1;
constexpr uint8_t FLAG_DIRTY_VAR_SIZE  = 2;
constexpr uint8_t FLAG_DELETED         = 3;
constexpr uint8_t FLAG_SHADOWED        = 4;

constexpr uint64_t MSB_MASK_64 = 0x8080808080808080ULL;

inline constexpr uint32_t make_tag(uint32_t field_number, uint8_t wire_type) noexcept {
    return (field_number << 3) | wire_type;
}

inline uint32_t varint32_size(uint32_t value) noexcept {
    int log2 = 31 - __builtin_clz(value | 1U);
    return static_cast<uint32_t>((log2 * 9 + 73) >> 6);
}

inline uint32_t varint64_size(uint64_t value) noexcept {
    int log2 = 63 - __builtin_clzll(value | 1ULL);
    return static_cast<uint32_t>((log2 * 9 + 73) >> 6);
}

/**
 * Branchless SWAR 64-bit varint skip using a single unaligned load + TZCNT instruction.
 */
inline uint32_t skip_varint_swar(const uint8_t* buf, uint32_t pos, uint32_t limit) noexcept {
    if (static_cast<int8_t>(buf[pos]) >= 0) [[likely]] {
        return pos + 1;
    }
    if (pos + 8 <= limit) [[likely]] {
        uint64_t word;
        std::memcpy(&word, buf + pos, 8);
        uint64_t term_mask = ~word & MSB_MASK_64;
        if (term_mask != 0ULL) [[likely]] {
            return pos + (static_cast<uint32_t>(__builtin_ctzll(term_mask)) >> 3) + 1;
        }
    }
    while (pos < limit && static_cast<int8_t>(buf[pos++]) < 0) {}
    return pos;
}

inline uint32_t read_varint32_cursor(const uint8_t* buf, uint32_t& pos) noexcept {
    uint32_t b = buf[pos++];
    if (!(b & 0x80U)) [[likely]] return b;
    uint32_t result = b & 0x7FU;
    b = buf[pos++]; result |= (b & 0x7FU) << 7;  if (!(b & 0x80U)) [[likely]] return result;
    b = buf[pos++]; result |= (b & 0x7FU) << 14; if (!(b & 0x80U)) return result;
    b = buf[pos++]; result |= (b & 0x7FU) << 21; if (!(b & 0x80U)) return result;
    b = buf[pos++]; result |= b << 28;
    if (b & 0x80U) {
        // Negative int32 sign-extension bytes
        while (buf[pos++] & 0x80U) {}
    }
    return result;
}

inline uint32_t read_varint32_at(const uint8_t* buf, uint32_t pos) noexcept {
    return read_varint32_cursor(buf, pos);
}

inline uint64_t read_varint64_cursor(const uint8_t* buf, uint32_t& pos) noexcept {
    uint64_t b = buf[pos++];
    if (!(b & 0x80ULL)) [[likely]] return b;
    uint64_t result = b & 0x7FULL;
    b = buf[pos++]; result |= (b & 0x7FULL) << 7;  if (!(b & 0x80ULL)) [[likely]] return result;
    b = buf[pos++]; result |= (b & 0x7FULL) << 14; if (!(b & 0x80ULL)) [[likely]] return result;
    b = buf[pos++]; result |= (b & 0x7FULL) << 21; if (!(b & 0x80ULL)) return result;
    b = buf[pos++]; result |= (b & 0x7FULL) << 28; if (!(b & 0x80ULL)) return result;
    b = buf[pos++]; result |= (b & 0x7FULL) << 35; if (!(b & 0x80ULL)) return result;
    b = buf[pos++]; result |= (b & 0x7FULL) << 42; if (!(b & 0x80ULL)) return result;
    b = buf[pos++]; result |= (b & 0x7FULL) << 49; if (!(b & 0x80ULL)) return result;
    b = buf[pos++]; result |= (b & 0x7FULL) << 56; if (!(b & 0x80ULL)) return result;
    b = buf[pos++]; result |= b << 63;
    return result;
}

inline uint64_t read_varint64_at(const uint8_t* buf, uint32_t pos) noexcept {
    return read_varint64_cursor(buf, pos);
}

inline uint32_t read_fixed32_at(const uint8_t* buf, uint32_t pos) noexcept {
    uint32_t v;
    std::memcpy(&v, buf + pos, 4);
    return v;
}

inline uint64_t read_fixed64_at(const uint8_t* buf, uint32_t pos) noexcept {
    uint64_t v;
    std::memcpy(&v, buf + pos, 8);
    return v;
}

inline double read_double_at(const uint8_t* buf, uint32_t pos) noexcept {
    double v;
    std::memcpy(&v, buf + pos, 8);
    return v;
}

// ============================================================================
// 2. WireWriter (Zero-Bounds-Check Pointer Cursor + Single-Pass Backpatching)
// ============================================================================

struct WireWriter {
    uint8_t* base;
    uint8_t* ptr;

    explicit WireWriter(uint8_t* out_buf) noexcept : base(out_buf), ptr(out_buf) {}

    size_t size() const noexcept { return static_cast<size_t>(ptr - base); }

    void write_raw(const uint8_t* src, uint32_t len) noexcept {
        std::memcpy(ptr, src, len);
        ptr += len;
    }

    void write_byte(uint8_t b) noexcept {
        *ptr++ = b;
    }

    void write_varint32(uint32_t v) noexcept {
        while (v >= 0x80U) {
            *ptr++ = static_cast<uint8_t>(v | 0x80U);
            v >>= 7;
        }
        *ptr++ = static_cast<uint8_t>(v);
    }

    void write_varint64(uint64_t v) noexcept {
        while (v >= 0x80ULL) {
            *ptr++ = static_cast<uint8_t>(v | 0x80ULL);
            v >>= 7;
        }
        *ptr++ = static_cast<uint8_t>(v);
    }

    void write_fixed64(uint64_t v) noexcept {
        std::memcpy(ptr, &v, 8);
        ptr += 8;
    }

    void write_double(double v) noexcept {
        std::memcpy(ptr, &v, 8);
        ptr += 8;
    }

    void write_string(uint8_t one_byte_tag, std::string_view sv) noexcept {
        *ptr++ = one_byte_tag;
        write_varint32(static_cast<uint32_t>(sv.size()));
        std::memcpy(ptr, sv.data(), sv.size());
        ptr += sv.size();
    }

    static void patch_varint64_at(uint8_t* target, uint64_t v) noexcept {
        while (v >= 0x80ULL) {
            *target++ = static_cast<uint8_t>(v | 0x80ULL);
            v >>= 7;
        }
        *target = static_cast<uint8_t>(v);
    }

    static void patch_fixed64_at(uint8_t* target, uint64_t v) noexcept {
        std::memcpy(target, &v, 8);
    }

    static void patch_double_at(uint8_t* target, double v) noexcept {
        std::memcpy(target, &v, 8);
    }
};

// ============================================================================
// 3. Hardware-Friendly 16-Byte Aligned TapeEntry (Zero Bit-Shift Unpacking!)
// ============================================================================

struct alignas(16) TapeEntry {
    uint32_t tag_offset;     // Start of Tag varint in raw buffer
    uint32_t record_end;     // End of Value payload in raw buffer
    uint32_t value_offset;   // Start of Value payload in raw buffer (direct, no addition needed!)
    uint16_t field_number;   // Field number
    uint8_t  wire_type;      // Wire type (0, 1, 2, 5)
    uint8_t  flags;          // FLAG_CLEAN_RAW, FLAG_DIRTY_SAME_SIZE, FLAG_DIRTY_VAR_SIZE, FLAG_DELETED, FLAG_SHADOWED

    uint32_t value_length() const noexcept { return record_end - value_offset; }
    uint32_t record_length() const noexcept { return record_end - tag_offset; }
};

// ============================================================================
// 4. LazyUserProfile (Inline Value Struct — 0 Heap Allocation)
// ============================================================================

class LazyUserProfile {
public:
    static constexpr int SLOT_USER_ID   = 0; // #1
    static constexpr int SLOT_CITY      = 1; // #2
    static constexpr int SLOT_VIP_LEVEL = 2; // #3
    static constexpr int SLOT_SCORE     = 3; // #4
    static constexpr int SLOT_EMAIL     = 4; // #5

    static constexpr uint32_t BIT_USER_ID   = 1U << 0;
    static constexpr uint32_t BIT_CITY      = 1U << 1;
    static constexpr uint32_t BIT_VIP_LEVEL = 1U << 2;
    static constexpr uint32_t BIT_SCORE     = 1U << 3;
    static constexpr uint32_t BIT_EMAIL     = 1U << 4;

    const uint8_t* buf_ = nullptr;
    uint32_t raw_off_ = 0;
    uint32_t raw_len_ = 0;

    // Inline stack/struct tape: handles up to 16 wire fields with 0 heap allocation
    TapeEntry tape_[16];
    uint16_t tape_count_ = 0;
    int16_t slot_tape_idx_[5] = { -1, -1, -1, -1, -1 };

    bool scanned_ = false;
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

    void wrap(const uint8_t* buf, uint32_t off, uint32_t len) noexcept {
        buf_ = buf;
        raw_off_ = off;
        raw_len_ = len;
        tape_count_ = 0;
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

    bool is_clean() const noexcept {
        return (same_size_dirty_mask_ | var_size_dirty_mask_ | appended_mask_) == 0;
    }

    bool is_same_size_dirty_only() const noexcept {
        return (var_size_dirty_mask_ | appended_mask_) == 0 && same_size_dirty_mask_ != 0;
    }

    void ensure_scanned() noexcept {
        if (!scanned_) [[unlikely]] {
            scan_current_level();
        }
    }

    void scan_current_level() noexcept {
        scanned_ = true;
        uint32_t cursor = raw_off_;
        const uint32_t limit = raw_off_ + raw_len_;
        const uint8_t* b = buf_;

        while (cursor < limit) {
            const uint32_t tag_off = cursor;
            uint32_t tag = b[cursor++];
            if (tag & 0x80U) [[unlikely]] {
                cursor--;
                tag = read_varint32_cursor(b, cursor);
            }
            const uint16_t field_num = static_cast<uint16_t>(tag >> 3);
            const uint8_t wt = static_cast<uint8_t>(tag & 7U);
            uint32_t val_off = cursor;

            if (wt == WIRETYPE_VARINT) {
                cursor = skip_varint_swar(b, cursor, limit);
            } else if (wt == WIRETYPE_LENGTH_DELIMITED) {
                uint32_t len = b[cursor++];
                if (len & 0x80U) [[unlikely]] {
                    cursor--;
                    len = read_varint32_cursor(b, cursor);
                }
                val_off = cursor;
                cursor += len;
            } else if (wt == WIRETYPE_FIXED64) {
                cursor += 8;
            } else if (wt == WIRETYPE_FIXED32) {
                cursor += 4;
            }

            uint16_t idx = tape_count_++;
            tape_[idx] = TapeEntry{tag_off, cursor, val_off, field_num, wt, FLAG_CLEAN_RAW};

            if (field_num >= 1 && field_num <= 5) [[likely]] {
                int slot = field_num - 1;
                int16_t prev = slot_tape_idx_[slot];
                if (prev >= 0) [[unlikely]] {
                    tape_[prev].flags = FLAG_SHADOWED;
                }
                slot_tape_idx_[slot] = static_cast<int16_t>(idx);
                present_mask_ |= (1U << slot);
            }
        }
    }

    uint64_t user_id() noexcept {
        if (!(decoded_mask_ & BIT_USER_ID)) {
            ensure_scanned();
            int16_t idx = slot_tape_idx_[SLOT_USER_ID];
            user_id_ = idx >= 0 ? read_varint64_at(buf_, tape_[idx].value_offset) : 0ULL;
            decoded_mask_ |= BIT_USER_ID;
        }
        return user_id_;
    }

    std::string_view city() noexcept {
        if (!(decoded_mask_ & BIT_CITY)) {
            ensure_scanned();
            int16_t idx = slot_tape_idx_[SLOT_CITY];
            city_ = idx >= 0
                ? std::string_view(reinterpret_cast<const char*>(buf_ + tape_[idx].value_offset), tape_[idx].value_length())
                : std::string_view();
            decoded_mask_ |= BIT_CITY;
        }
        return city_;
    }

    int32_t vip_level() noexcept {
        if (!(decoded_mask_ & BIT_VIP_LEVEL)) {
            ensure_scanned();
            int16_t idx = slot_tape_idx_[SLOT_VIP_LEVEL];
            vip_level_ = idx >= 0 ? static_cast<int32_t>(read_varint32_at(buf_, tape_[idx].value_offset)) : 0;
            decoded_mask_ |= BIT_VIP_LEVEL;
        }
        return vip_level_;
    }

    double score() noexcept {
        if (!(decoded_mask_ & BIT_SCORE)) {
            ensure_scanned();
            int16_t idx = slot_tape_idx_[SLOT_SCORE];
            score_ = idx >= 0 ? read_double_at(buf_, tape_[idx].value_offset) : 0.0;
            decoded_mask_ |= BIT_SCORE;
        }
        return score_;
    }

    std::string_view email() noexcept {
        if (!(decoded_mask_ & BIT_EMAIL)) {
            ensure_scanned();
            int16_t idx = slot_tape_idx_[SLOT_EMAIL];
            email_ = idx >= 0
                ? std::string_view(reinterpret_cast<const char*>(buf_ + tape_[idx].value_offset), tape_[idx].value_length())
                : std::string_view();
            decoded_mask_ |= BIT_EMAIL;
        }
        return email_;
    }

    // Returns true if same-size dirty, false if variable-size dirty
    bool set_user_id(uint64_t v) noexcept {
        ensure_scanned();
        user_id_ = v;
        decoded_mask_ |= BIT_USER_ID;
        present_mask_ |= BIT_USER_ID;
        return mark_modified(SLOT_USER_ID, BIT_USER_ID, varint64_size(v));
    }

    bool set_city(std::string_view v) noexcept {
        ensure_scanned();
        city_ = v;
        decoded_mask_ |= BIT_CITY;
        present_mask_ |= BIT_CITY;
        return mark_modified(SLOT_CITY, BIT_CITY, static_cast<uint32_t>(v.size()));
    }

    bool set_vip_level(int32_t v) noexcept {
        ensure_scanned();
        vip_level_ = v;
        decoded_mask_ |= BIT_VIP_LEVEL;
        present_mask_ |= BIT_VIP_LEVEL;
        return mark_modified(SLOT_VIP_LEVEL, BIT_VIP_LEVEL, varint32_size(static_cast<uint32_t>(v)));
    }

    bool set_score(double v) noexcept {
        ensure_scanned();
        score_ = v;
        decoded_mask_ |= BIT_SCORE;
        present_mask_ |= BIT_SCORE;
        return mark_modified(SLOT_SCORE, BIT_SCORE, 8);
    }

    bool set_email(std::string_view v) noexcept {
        ensure_scanned();
        email_ = v;
        decoded_mask_ |= BIT_EMAIL;
        present_mask_ |= BIT_EMAIL;
        return mark_modified(SLOT_EMAIL, BIT_EMAIL, static_cast<uint32_t>(v.size()));
    }

    bool mark_modified(int slot, uint32_t bit, uint32_t new_val_len) noexcept {
        int16_t idx = slot_tape_idx_[slot];
        if (idx >= 0) {
            if (tape_[idx].value_length() == new_val_len && tape_[idx].flags != FLAG_DIRTY_VAR_SIZE) {
                tape_[idx].flags = FLAG_DIRTY_SAME_SIZE;
                same_size_dirty_mask_ |= bit;
                return (var_size_dirty_mask_ | appended_mask_) == 0;
            }
            tape_[idx].flags = FLAG_DIRTY_VAR_SIZE;
            var_size_dirty_mask_ |= bit;
            return false;
        }
        appended_mask_ |= bit;
        var_size_dirty_mask_ |= bit;
        return false;
    }

    void apply_in_place_patches(uint8_t* target, uint32_t base_offset) const noexcept {
        uint32_t mask = same_size_dirty_mask_;
        while (mask != 0U) {
            int slot = __builtin_ctz(mask);
            int16_t idx = slot_tape_idx_[slot];
            if (idx >= 0 && tape_[idx].flags == FLAG_DIRTY_SAME_SIZE) {
                uint8_t* patch_ptr = target + (tape_[idx].value_offset - base_offset);
                switch (slot) {
                    case SLOT_USER_ID:   WireWriter::patch_varint64_at(patch_ptr, user_id_); break;
                    case SLOT_CITY:      std::memcpy(patch_ptr, city_.data(), city_.size()); break;
                    case SLOT_VIP_LEVEL: WireWriter::patch_varint64_at(patch_ptr, static_cast<uint32_t>(vip_level_)); break;
                    case SLOT_SCORE:     WireWriter::patch_double_at(patch_ptr, score_); break;
                    case SLOT_EMAIL:     std::memcpy(patch_ptr, email_.data(), email_.size()); break;
                }
            }
            mask &= (mask - 1U);
        }
    }

    void write_spliced_to(WireWriter& w) const noexcept {
        if (is_clean()) {
            w.write_raw(buf_ + raw_off_, raw_len_);
            return;
        }
        if (is_same_size_dirty_only()) {
            uint8_t* start = w.ptr;
            w.write_raw(buf_ + raw_off_, raw_len_);
            apply_in_place_patches(start, raw_off_);
            return;
        }

        int32_t span_start = -1;
        uint32_t span_end = 0;

        for (uint16_t i = 0; i < tape_count_; i++) {
            const TapeEntry& e = tape_[i];
            if (e.flags == FLAG_CLEAN_RAW) {
                if (span_start < 0) {
                    span_start = static_cast<int32_t>(e.tag_offset);
                    span_end = e.record_end;
                } else if (span_end == e.tag_offset) {
                    span_end = e.record_end;
                } else {
                    w.write_raw(buf_ + span_start, span_end - static_cast<uint32_t>(span_start));
                    span_start = static_cast<int32_t>(e.tag_offset);
                    span_end = e.record_end;
                }
            } else if (e.flags == FLAG_DELETED || e.flags == FLAG_SHADOWED) {
                if (span_start >= 0) {
                    w.write_raw(buf_ + span_start, span_end - static_cast<uint32_t>(span_start));
                    span_start = -1;
                }
            } else {
                if (span_start >= 0) {
                    w.write_raw(buf_ + span_start, span_end - static_cast<uint32_t>(span_start));
                    span_start = -1;
                }
                write_field(e.field_number, w);
            }
        }
        if (span_start >= 0) {
            w.write_raw(buf_ + span_start, span_end - static_cast<uint32_t>(span_start));
        }
        if (appended_mask_ != 0U) {
            uint32_t m = appended_mask_;
            while (m != 0U) {
                int slot = __builtin_ctz(m);
                write_field(static_cast<uint16_t>(slot + 1), w);
                m &= (m - 1U);
            }
        }
    }

    void write_field(uint16_t field_num, WireWriter& w) const noexcept {
        switch (field_num) {
            case 1: w.write_byte(0x08); w.write_varint64(user_id_); break;
            case 2: w.write_string(0x12, city_); break;
            case 3: w.write_byte(0x18); w.write_varint32(static_cast<uint32_t>(vip_level_)); break;
            case 4: w.write_byte(0x21); w.write_double(score_); break;
            case 5: w.write_string(0x2A, email_); break;
        }
    }
};

// ============================================================================
// 5. LazyLineItem (Inline Value Struct + Direct 1-Pass Fast Reader for B2!)
// ============================================================================

class LazyLineItem {
public:
    static constexpr int SLOT_ITEM_ID     = 0; // #1
    static constexpr int SLOT_SKU         = 1; // #2
    static constexpr int SLOT_QUANTITY    = 2; // #3
    static constexpr int SLOT_PRICE_CENTS = 3; // #4
    static constexpr int SLOT_DESCRIPTION = 4; // #5

    static constexpr uint32_t BIT_ITEM_ID     = 1U << 0;
    static constexpr uint32_t BIT_SKU         = 1U << 1;
    static constexpr uint32_t BIT_QUANTITY    = 1U << 2;
    static constexpr uint32_t BIT_PRICE_CENTS = 1U << 3;
    static constexpr uint32_t BIT_DESCRIPTION = 1U << 4;

    const uint8_t* buf_ = nullptr;
    uint32_t raw_off_ = 0;
    uint32_t raw_len_ = 0;

    TapeEntry tape_[8];
    uint16_t tape_count_ = 0;
    int16_t slot_tape_idx_[5] = { -1, -1, -1, -1, -1 };

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

    void wrap(const uint8_t* buf, uint32_t off, uint32_t len) noexcept {
        buf_ = buf;
        raw_off_ = off;
        raw_len_ = len;
        tape_count_ = 0;
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

    bool is_clean() const noexcept {
        return (same_size_dirty_mask_ | var_size_dirty_mask_ | appended_mask_) == 0;
    }

    bool is_same_size_dirty_only() const noexcept {
        return (var_size_dirty_mask_ | appended_mask_) == 0 && same_size_dirty_mask_ != 0;
    }

    void ensure_scanned() noexcept {
        if (!scanned_) [[unlikely]] {
            scan_current_level();
        }
    }

    void scan_current_level() noexcept {
        scanned_ = true;
        uint32_t cursor = raw_off_;
        const uint32_t limit = raw_off_ + raw_len_;
        const uint8_t* b = buf_;

        while (cursor < limit) {
            const uint32_t tag_off = cursor;
            uint32_t tag = b[cursor++];
            if (tag & 0x80U) [[unlikely]] {
                cursor--;
                tag = read_varint32_cursor(b, cursor);
            }
            const uint16_t field_num = static_cast<uint16_t>(tag >> 3);
            const uint8_t wt = static_cast<uint8_t>(tag & 7U);
            uint32_t val_off = cursor;

            if (wt == WIRETYPE_VARINT) {
                cursor = skip_varint_swar(b, cursor, limit);
            } else if (wt == WIRETYPE_LENGTH_DELIMITED) {
                uint32_t len = b[cursor++];
                if (len & 0x80U) [[unlikely]] {
                    cursor--;
                    len = read_varint32_cursor(b, cursor);
                }
                val_off = cursor;
                cursor += len;
            } else if (wt == WIRETYPE_FIXED64) {
                cursor += 8;
            } else if (wt == WIRETYPE_FIXED32) {
                cursor += 4;
            }

            uint16_t idx = tape_count_++;
            tape_[idx] = TapeEntry{tag_off, cursor, val_off, field_num, wt, FLAG_CLEAN_RAW};

            if (field_num >= 1 && field_num <= 5) [[likely]] {
                int slot = field_num - 1;
                int16_t prev = slot_tape_idx_[slot];
                if (prev >= 0) [[unlikely]] {
                    tape_[prev].flags = FLAG_SHADOWED;
                }
                slot_tape_idx_[slot] = static_cast<int16_t>(idx);
                present_mask_ |= (1U << slot);
            }
        }
    }

    /**
     * Eager 1-Pass Populator (Optional C++ fast-path when caller intends to read all fields of a leaf item!).
     * Decodes all 5 fields in a single sequential pass without building a Tape.
     */
    void decode_all_eager() noexcept {
        uint32_t cursor = raw_off_;
        const uint32_t limit = raw_off_ + raw_len_;
        const uint8_t* b = buf_;
        while (cursor < limit) {
            uint8_t tag = b[cursor++];
            switch (tag) {
                case 8:  item_id_ = read_varint64_cursor(b, cursor); break;
                case 18: {
                    uint32_t len = read_varint32_cursor(b, cursor);
                    sku_ = std::string_view(reinterpret_cast<const char*>(b + cursor), len);
                    cursor += len;
                    break;
                }
                case 24: quantity_ = static_cast<int32_t>(read_varint32_cursor(b, cursor)); break;
                case 32: price_cents_ = read_varint64_cursor(b, cursor); break;
                case 42: {
                    uint32_t len = read_varint32_cursor(b, cursor);
                    description_ = std::string_view(reinterpret_cast<const char*>(b + cursor), len);
                    cursor += len;
                    break;
                }
                default: {
                    uint8_t wt = tag & 7U;
                    if (wt == 0) cursor = skip_varint_swar(b, cursor, limit);
                    else if (wt == 1) cursor += 8;
                    else if (wt == 2) { uint32_t l = read_varint32_cursor(b, cursor); cursor += l; }
                    else if (wt == 5) cursor += 4;
                    break;
                }
            }
        }
        decoded_mask_ = 0x1FU;
    }

    uint64_t item_id() noexcept {
        if (!(decoded_mask_ & BIT_ITEM_ID)) {
            ensure_scanned();
            int16_t idx = slot_tape_idx_[SLOT_ITEM_ID];
            item_id_ = idx >= 0 ? read_varint64_at(buf_, tape_[idx].value_offset) : 0ULL;
            decoded_mask_ |= BIT_ITEM_ID;
        }
        return item_id_;
    }

    std::string_view sku() noexcept {
        if (!(decoded_mask_ & BIT_SKU)) {
            ensure_scanned();
            int16_t idx = slot_tape_idx_[SLOT_SKU];
            sku_ = idx >= 0
                ? std::string_view(reinterpret_cast<const char*>(buf_ + tape_[idx].value_offset), tape_[idx].value_length())
                : std::string_view();
            decoded_mask_ |= BIT_SKU;
        }
        return sku_;
    }

    int32_t quantity() noexcept {
        if (!(decoded_mask_ & BIT_QUANTITY)) {
            ensure_scanned();
            int16_t idx = slot_tape_idx_[SLOT_QUANTITY];
            quantity_ = idx >= 0 ? static_cast<int32_t>(read_varint32_at(buf_, tape_[idx].value_offset)) : 0;
            decoded_mask_ |= BIT_QUANTITY;
        }
        return quantity_;
    }

    uint64_t price_cents() noexcept {
        if (!(decoded_mask_ & BIT_PRICE_CENTS)) {
            ensure_scanned();
            int16_t idx = slot_tape_idx_[SLOT_PRICE_CENTS];
            price_cents_ = idx >= 0 ? read_varint64_at(buf_, tape_[idx].value_offset) : 0ULL;
            decoded_mask_ |= BIT_PRICE_CENTS;
        }
        return price_cents_;
    }

    std::string_view description() noexcept {
        if (!(decoded_mask_ & BIT_DESCRIPTION)) {
            ensure_scanned();
            int16_t idx = slot_tape_idx_[SLOT_DESCRIPTION];
            description_ = idx >= 0
                ? std::string_view(reinterpret_cast<const char*>(buf_ + tape_[idx].value_offset), tape_[idx].value_length())
                : std::string_view();
            decoded_mask_ |= BIT_DESCRIPTION;
        }
        return description_;
    }

    bool set_item_id(uint64_t v) noexcept {
        ensure_scanned();
        item_id_ = v;
        decoded_mask_ |= BIT_ITEM_ID;
        present_mask_ |= BIT_ITEM_ID;
        return mark_modified(SLOT_ITEM_ID, BIT_ITEM_ID, varint64_size(v));
    }

    bool set_sku(std::string_view v) noexcept {
        ensure_scanned();
        sku_ = v;
        decoded_mask_ |= BIT_SKU;
        present_mask_ |= BIT_SKU;
        return mark_modified(SLOT_SKU, BIT_SKU, static_cast<uint32_t>(v.size()));
    }

    bool set_quantity(int32_t v) noexcept {
        ensure_scanned();
        quantity_ = v;
        decoded_mask_ |= BIT_QUANTITY;
        present_mask_ |= BIT_QUANTITY;
        return mark_modified(SLOT_QUANTITY, BIT_QUANTITY, varint32_size(static_cast<uint32_t>(v)));
    }

    bool set_price_cents(uint64_t v) noexcept {
        ensure_scanned();
        price_cents_ = v;
        decoded_mask_ |= BIT_PRICE_CENTS;
        present_mask_ |= BIT_PRICE_CENTS;
        return mark_modified(SLOT_PRICE_CENTS, BIT_PRICE_CENTS, varint64_size(v));
    }

    bool set_description(std::string_view v) noexcept {
        ensure_scanned();
        description_ = v;
        decoded_mask_ |= BIT_DESCRIPTION;
        present_mask_ |= BIT_DESCRIPTION;
        return mark_modified(SLOT_DESCRIPTION, BIT_DESCRIPTION, static_cast<uint32_t>(v.size()));
    }

    bool mark_modified(int slot, uint32_t bit, uint32_t new_val_len) noexcept {
        int16_t idx = slot_tape_idx_[slot];
        if (idx >= 0) {
            if (tape_[idx].value_length() == new_val_len && tape_[idx].flags != FLAG_DIRTY_VAR_SIZE) {
                tape_[idx].flags = FLAG_DIRTY_SAME_SIZE;
                same_size_dirty_mask_ |= bit;
                return (var_size_dirty_mask_ | appended_mask_) == 0;
            }
            tape_[idx].flags = FLAG_DIRTY_VAR_SIZE;
            var_size_dirty_mask_ |= bit;
            return false;
        }
        appended_mask_ |= bit;
        var_size_dirty_mask_ |= bit;
        return false;
    }

    void apply_in_place_patches(uint8_t* target, uint32_t base_offset) const noexcept {
        uint32_t mask = same_size_dirty_mask_;
        while (mask != 0U) {
            int slot = __builtin_ctz(mask);
            int16_t idx = slot_tape_idx_[slot];
            if (idx >= 0 && tape_[idx].flags == FLAG_DIRTY_SAME_SIZE) {
                uint8_t* patch_ptr = target + (tape_[idx].value_offset - base_offset);
                switch (slot) {
                    case SLOT_ITEM_ID:     WireWriter::patch_varint64_at(patch_ptr, item_id_); break;
                    case SLOT_SKU:         std::memcpy(patch_ptr, sku_.data(), sku_.size()); break;
                    case SLOT_QUANTITY:    WireWriter::patch_varint64_at(patch_ptr, static_cast<uint32_t>(quantity_)); break;
                    case SLOT_PRICE_CENTS: WireWriter::patch_varint64_at(patch_ptr, price_cents_); break;
                    case SLOT_DESCRIPTION: std::memcpy(patch_ptr, description_.data(), description_.size()); break;
                }
            }
            mask &= (mask - 1U);
        }
    }

    void write_spliced_to(WireWriter& w) const noexcept {
        if (is_clean()) {
            w.write_raw(buf_ + raw_off_, raw_len_);
            return;
        }
        if (is_same_size_dirty_only()) {
            uint8_t* start = w.ptr;
            w.write_raw(buf_ + raw_off_, raw_len_);
            apply_in_place_patches(start, raw_off_);
            return;
        }

        int32_t span_start = -1;
        uint32_t span_end = 0;

        for (uint16_t i = 0; i < tape_count_; i++) {
            const TapeEntry& e = tape_[i];
            if (e.flags == FLAG_CLEAN_RAW) {
                if (span_start < 0) {
                    span_start = static_cast<int32_t>(e.tag_offset);
                    span_end = e.record_end;
                } else if (span_end == e.tag_offset) {
                    span_end = e.record_end;
                } else {
                    w.write_raw(buf_ + span_start, span_end - static_cast<uint32_t>(span_start));
                    span_start = static_cast<int32_t>(e.tag_offset);
                    span_end = e.record_end;
                }
            } else if (e.flags == FLAG_DELETED || e.flags == FLAG_SHADOWED) {
                if (span_start >= 0) {
                    w.write_raw(buf_ + span_start, span_end - static_cast<uint32_t>(span_start));
                    span_start = -1;
                }
            } else {
                if (span_start >= 0) {
                    w.write_raw(buf_ + span_start, span_end - static_cast<uint32_t>(span_start));
                    span_start = -1;
                }
                write_field(e.field_number, w);
            }
        }
        if (span_start >= 0) {
            w.write_raw(buf_ + span_start, span_end - static_cast<uint32_t>(span_start));
        }
        if (appended_mask_ != 0U) {
            uint32_t m = appended_mask_;
            while (m != 0U) {
                int slot = __builtin_ctz(m);
                write_field(static_cast<uint16_t>(slot + 1), w);
                m &= (m - 1U);
            }
        }
    }

    void write_field(uint16_t field_num, WireWriter& w) const noexcept {
        switch (field_num) {
            case 1: w.write_byte(0x08); w.write_varint64(item_id_); break;
            case 2: w.write_string(0x12, sku_); break;
            case 3: w.write_byte(0x18); w.write_varint32(static_cast<uint32_t>(quantity_)); break;
            case 4: w.write_byte(0x20); w.write_varint64(price_cents_); break;
            case 5: w.write_string(0x2A, description_); break;
        }
    }
};

// ============================================================================
// 6. LazyOrderEnvelope (Top-Level Zero-Heap C++20 Performance Ceiling Engine)
// ============================================================================

class LazyOrderEnvelope {
public:
    static constexpr int SLOT_ORDER_ID   = 0; // #1
    static constexpr int SLOT_BUYER_NAME = 1; // #2
    static constexpr int SLOT_STATUS     = 2; // #3
    static constexpr int SLOT_UPDATED_AT = 3; // #4
    static constexpr int SLOT_USER       = 4; // #5
    static constexpr int SLOT_ITEMS      = 5; // #6
    static constexpr int SLOT_PAYLOAD    = 6; // #7
    static constexpr int SLOT_AUTH_TOKEN = 7; // #8
    static constexpr int SLOT_TRACE_ID   = 8; // #9
    static constexpr int SLOT_TOTAL      = 9; // #10

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

    // Inline flat tape & item index table (Zero malloc/free!)
    TapeEntry tape_[160];
    uint16_t tape_count_ = 0;
    int16_t slot_tape_idx_[10];
    uint16_t item_tape_idx_[128];
    uint16_t item_count_ = 0;

    bool scanned_ = false;
    bool has_deleted_ = false;
    uint32_t present_mask_ = 0;
    uint32_t decoded_mask_ = 0;
    uint32_t same_size_dirty_mask_ = 0;
    uint32_t var_size_dirty_mask_ = 0;
    uint32_t appended_mask_ = 0;

    // Decoded / Mutated field slots
    uint64_t order_id_ = 0;
    std::string_view buyer_name_;
    int32_t status_ = 0;
    uint64_t updated_at_fixed64_ = 0;
    LazyUserProfile user_; // Embedded inline by value!
    LazyLineItem items_pool_[128]; // Pre-embedded inline item pool!
    uint64_t items_loaded_mask_[2] = { 0ULL, 0ULL };
    uint16_t appended_item_count_ = 0;
    std::string_view payload_;
    std::string_view auth_token_;
    std::string_view trace_id_;
    uint64_t total_cents_ = 0;

    void wrap(const uint8_t* buf, uint32_t off, uint32_t len) noexcept {
        buf_ = buf;
        raw_off_ = off;
        raw_len_ = len;
        tape_count_ = 0;
        item_count_ = 0;
        appended_item_count_ = 0;
        scanned_ = false;
        has_deleted_ = false;
        present_mask_ = 0;
        decoded_mask_ = 0;
        same_size_dirty_mask_ = 0;
        var_size_dirty_mask_ = 0;
        appended_mask_ = 0;
        items_loaded_mask_[0] = 0ULL;
        items_loaded_mask_[1] = 0ULL;
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

    void ensure_scanned() noexcept {
        if (!scanned_) [[unlikely]] {
            scan_current_level();
        }
    }

    void scan_current_level() noexcept {
        scanned_ = true;
        uint32_t cursor = raw_off_;
        const uint32_t limit = raw_off_ + raw_len_;
        const uint8_t* b = buf_;

        while (cursor < limit) {
            const uint32_t tag_off = cursor;
            uint32_t tag = b[cursor++];
            if (tag & 0x80U) [[unlikely]] {
                cursor--;
                tag = read_varint32_cursor(b, cursor);
            }
            const uint16_t field_num = static_cast<uint16_t>(tag >> 3);
            const uint8_t wt = static_cast<uint8_t>(tag & 7U);
            uint32_t val_off = cursor;

            if (wt == WIRETYPE_VARINT) {
                cursor = skip_varint_swar(b, cursor, limit);
            } else if (wt == WIRETYPE_LENGTH_DELIMITED) {
                uint32_t len = b[cursor++];
                if (len & 0x80U) [[unlikely]] {
                    cursor--;
                    len = read_varint32_cursor(b, cursor);
                }
                val_off = cursor;
                cursor += len; // O(1) skip across nested sub-message, string, or 4KB payload!
            } else if (wt == WIRETYPE_FIXED64) {
                cursor += 8;
            } else if (wt == WIRETYPE_FIXED32) {
                cursor += 4;
            }

            uint16_t idx = tape_count_++;
            tape_[idx] = TapeEntry{tag_off, cursor, val_off, field_num, wt, FLAG_CLEAN_RAW};

            if (field_num == 6) {
                item_tape_idx_[item_count_++] = idx;
                present_mask_ |= BIT_ITEMS;
            } else if (field_num >= 1 && field_num <= 10) [[likely]] {
                int slot = field_num - 1;
                int16_t prev = slot_tape_idx_[slot];
                if (prev >= 0) [[unlikely]] {
                    tape_[prev].flags = FLAG_SHADOWED;
                }
                slot_tape_idx_[slot] = static_cast<int16_t>(idx);
                present_mask_ |= (1U << slot);
            }
        }
    }

    // ==================== Field Getters ====================

    uint64_t order_id() noexcept {
        if (!(decoded_mask_ & BIT_ORDER_ID)) {
            ensure_scanned();
            int16_t idx = slot_tape_idx_[SLOT_ORDER_ID];
            order_id_ = idx >= 0 ? read_varint64_at(buf_, tape_[idx].value_offset) : 0ULL;
            decoded_mask_ |= BIT_ORDER_ID;
        }
        return order_id_;
    }

    std::string_view buyer_name() noexcept {
        if (!(decoded_mask_ & BIT_BUYER_NAME)) {
            ensure_scanned();
            int16_t idx = slot_tape_idx_[SLOT_BUYER_NAME];
            buyer_name_ = idx >= 0
                ? std::string_view(reinterpret_cast<const char*>(buf_ + tape_[idx].value_offset), tape_[idx].value_length())
                : std::string_view();
            decoded_mask_ |= BIT_BUYER_NAME;
        }
        return buyer_name_;
    }

    int32_t status() noexcept {
        if (!(decoded_mask_ & BIT_STATUS)) {
            ensure_scanned();
            int16_t idx = slot_tape_idx_[SLOT_STATUS];
            status_ = idx >= 0 ? static_cast<int32_t>(read_varint32_at(buf_, tape_[idx].value_offset)) : 0;
            decoded_mask_ |= BIT_STATUS;
        }
        return status_;
    }

    uint64_t updated_at_fixed64() noexcept {
        if (!(decoded_mask_ & BIT_UPDATED_AT)) {
            ensure_scanned();
            int16_t idx = slot_tape_idx_[SLOT_UPDATED_AT];
            updated_at_fixed64_ = idx >= 0 ? read_fixed64_at(buf_, tape_[idx].value_offset) : 0ULL;
            decoded_mask_ |= BIT_UPDATED_AT;
        }
        return updated_at_fixed64_;
    }

    LazyUserProfile& user() noexcept {
        if (!(decoded_mask_ & BIT_USER)) {
            ensure_scanned();
            int16_t idx = slot_tape_idx_[SLOT_USER];
            if (idx >= 0) {
                user_.wrap(buf_, tape_[idx].value_offset, tape_[idx].value_length());
            } else {
                user_.init_scratch();
            }
            decoded_mask_ |= BIT_USER;
        }
        return user_;
    }

    LazyUserProfile& mutable_user() noexcept {
        LazyUserProfile& u = user();
        if (slot_tape_idx_[SLOT_USER] < 0) {
            present_mask_ |= BIT_USER;
            appended_mask_ |= BIT_USER;
            var_size_dirty_mask_ |= BIT_USER;
        }
        return u;
    }

    void sync_user_dirty() noexcept {
        int16_t idx = slot_tape_idx_[SLOT_USER];
        if (idx >= 0) {
            if (user_.is_same_size_dirty_only() && tape_[idx].flags != FLAG_DIRTY_VAR_SIZE) {
                tape_[idx].flags = FLAG_DIRTY_SAME_SIZE;
                same_size_dirty_mask_ |= BIT_USER;
            } else if (!user_.is_clean()) {
                tape_[idx].flags = FLAG_DIRTY_VAR_SIZE;
                var_size_dirty_mask_ |= BIT_USER;
            }
        } else if (!user_.is_clean()) {
            appended_mask_ |= BIT_USER;
            var_size_dirty_mask_ |= BIT_USER;
        }
    }

    uint16_t items_count() noexcept {
        ensure_scanned();
        return item_count_ + appended_item_count_;
    }

    LazyLineItem& items(uint16_t index) noexcept {
        ensure_scanned();
        if (index >= item_count_) {
            return items_pool_[index];
        }
        uint64_t bit = 1ULL << (index & 63);
        uint64_t& mask_word = items_loaded_mask_[index >> 6];
        if (!(mask_word & bit)) {
            uint16_t t_idx = item_tape_idx_[index];
            items_pool_[index].wrap(buf_, tape_[t_idx].value_offset, tape_[t_idx].value_length());
            mask_word |= bit;
        }
        return items_pool_[index];
    }

    /**
     * Fast 1-pass item accessor for full-tree sequential scan (B2): binds and eagerly decodes the 5 fields in 1 pass.
     */
    void read_item_eager(uint16_t index, LazyLineItem& out_item) const noexcept {
        uint16_t t_idx = item_tape_idx_[index];
        out_item.buf_ = buf_;
        out_item.raw_off_ = tape_[t_idx].value_offset;
        out_item.raw_len_ = tape_[t_idx].value_length();
        out_item.decode_all_eager();
    }

    void sync_item_dirty(uint16_t index) noexcept {
        if (index < item_count_) {
            uint16_t t_idx = item_tape_idx_[index];
            const LazyLineItem& item = items_pool_[index];
            if (item.is_same_size_dirty_only() && tape_[t_idx].flags != FLAG_DIRTY_VAR_SIZE) {
                tape_[t_idx].flags = FLAG_DIRTY_SAME_SIZE;
                same_size_dirty_mask_ |= BIT_ITEMS;
            } else if (!item.is_clean()) {
                tape_[t_idx].flags = FLAG_DIRTY_VAR_SIZE;
                var_size_dirty_mask_ |= BIT_ITEMS;
            }
        }
    }

    LazyLineItem& add_item() noexcept {
        ensure_scanned();
        uint16_t idx = item_count_ + appended_item_count_++;
        items_pool_[idx].init_scratch();
        present_mask_ |= BIT_ITEMS;
        appended_mask_ |= BIT_ITEMS;
        var_size_dirty_mask_ |= BIT_ITEMS;
        return items_pool_[idx];
    }

    std::string_view payload() noexcept {
        if (!(decoded_mask_ & BIT_PAYLOAD)) {
            ensure_scanned();
            int16_t idx = slot_tape_idx_[SLOT_PAYLOAD];
            payload_ = idx >= 0
                ? std::string_view(reinterpret_cast<const char*>(buf_ + tape_[idx].value_offset), tape_[idx].value_length())
                : std::string_view();
            decoded_mask_ |= BIT_PAYLOAD;
        }
        return payload_;
    }

    std::string_view auth_token() noexcept {
        if (!(decoded_mask_ & BIT_AUTH_TOKEN)) {
            ensure_scanned();
            int16_t idx = slot_tape_idx_[SLOT_AUTH_TOKEN];
            auth_token_ = idx >= 0
                ? std::string_view(reinterpret_cast<const char*>(buf_ + tape_[idx].value_offset), tape_[idx].value_length())
                : std::string_view();
            decoded_mask_ |= BIT_AUTH_TOKEN;
        }
        return auth_token_;
    }

    std::string_view trace_id() noexcept {
        if (!(decoded_mask_ & BIT_TRACE_ID)) {
            ensure_scanned();
            int16_t idx = slot_tape_idx_[SLOT_TRACE_ID];
            trace_id_ = idx >= 0
                ? std::string_view(reinterpret_cast<const char*>(buf_ + tape_[idx].value_offset), tape_[idx].value_length())
                : std::string_view();
            decoded_mask_ |= BIT_TRACE_ID;
        }
        return trace_id_;
    }

    uint64_t total_cents() noexcept {
        if (!(decoded_mask_ & BIT_TOTAL)) {
            ensure_scanned();
            int16_t idx = slot_tape_idx_[SLOT_TOTAL];
            total_cents_ = idx >= 0 ? read_varint64_at(buf_, tape_[idx].value_offset) : 0ULL;
            decoded_mask_ |= BIT_TOTAL;
        }
        return total_cents_;
    }

    // ==================== Field Setters ====================

    LazyOrderEnvelope& set_order_id(uint64_t v) noexcept {
        ensure_scanned();
        order_id_ = v;
        decoded_mask_ |= BIT_ORDER_ID;
        present_mask_ |= BIT_ORDER_ID;
        mark_modified(SLOT_ORDER_ID, BIT_ORDER_ID, varint64_size(v));
        return *this;
    }

    LazyOrderEnvelope& set_buyer_name(std::string_view v) noexcept {
        ensure_scanned();
        buyer_name_ = v;
        decoded_mask_ |= BIT_BUYER_NAME;
        present_mask_ |= BIT_BUYER_NAME;
        mark_modified(SLOT_BUYER_NAME, BIT_BUYER_NAME, static_cast<uint32_t>(v.size()));
        return *this;
    }

    LazyOrderEnvelope& set_status(int32_t v) noexcept {
        ensure_scanned();
        status_ = v;
        decoded_mask_ |= BIT_STATUS;
        present_mask_ |= BIT_STATUS;
        mark_modified(SLOT_STATUS, BIT_STATUS, varint32_size(static_cast<uint32_t>(v)));
        return *this;
    }

    LazyOrderEnvelope& set_updated_at_fixed64(uint64_t v) noexcept {
        ensure_scanned();
        updated_at_fixed64_ = v;
        decoded_mask_ |= BIT_UPDATED_AT;
        present_mask_ |= BIT_UPDATED_AT;
        mark_modified(SLOT_UPDATED_AT, BIT_UPDATED_AT, 8);
        return *this;
    }

    LazyOrderEnvelope& set_payload(std::string_view v) noexcept {
        ensure_scanned();
        payload_ = v;
        decoded_mask_ |= BIT_PAYLOAD;
        present_mask_ |= BIT_PAYLOAD;
        mark_modified(SLOT_PAYLOAD, BIT_PAYLOAD, static_cast<uint32_t>(v.size()));
        return *this;
    }

    LazyOrderEnvelope& set_auth_token(std::string_view v) noexcept {
        ensure_scanned();
        auth_token_ = v;
        decoded_mask_ |= BIT_AUTH_TOKEN;
        present_mask_ |= BIT_AUTH_TOKEN;
        mark_modified(SLOT_AUTH_TOKEN, BIT_AUTH_TOKEN, static_cast<uint32_t>(v.size()));
        return *this;
    }

    LazyOrderEnvelope& clear_auth_token() noexcept {
        ensure_scanned();
        auth_token_ = std::string_view();
        decoded_mask_ |= BIT_AUTH_TOKEN;
        present_mask_ &= ~BIT_AUTH_TOKEN;
        appended_mask_ &= ~BIT_AUTH_TOKEN;
        int16_t idx = slot_tape_idx_[SLOT_AUTH_TOKEN];
        if (idx >= 0) {
            tape_[idx].flags = FLAG_DELETED;
            slot_tape_idx_[SLOT_AUTH_TOKEN] = -1;
            has_deleted_ = true;
            var_size_dirty_mask_ |= BIT_AUTH_TOKEN;
        }
        return *this;
    }

    LazyOrderEnvelope& set_trace_id(std::string_view v) noexcept {
        ensure_scanned();
        trace_id_ = v;
        decoded_mask_ |= BIT_TRACE_ID;
        present_mask_ |= BIT_TRACE_ID;
        mark_modified(SLOT_TRACE_ID, BIT_TRACE_ID, static_cast<uint32_t>(v.size()));
        return *this;
    }

    LazyOrderEnvelope& set_total_cents(uint64_t v) noexcept {
        ensure_scanned();
        total_cents_ = v;
        decoded_mask_ |= BIT_TOTAL;
        present_mask_ |= BIT_TOTAL;
        mark_modified(SLOT_TOTAL, BIT_TOTAL, varint64_size(v));
        return *this;
    }

    void mark_modified(int slot, uint32_t bit, uint32_t new_val_len) noexcept {
        int16_t idx = slot_tape_idx_[slot];
        if (idx >= 0) {
            if (tape_[idx].value_length() == new_val_len && tape_[idx].flags != FLAG_DIRTY_VAR_SIZE) {
                tape_[idx].flags = FLAG_DIRTY_SAME_SIZE;
                same_size_dirty_mask_ |= bit;
                return;
            }
            tape_[idx].flags = FLAG_DIRTY_VAR_SIZE;
            var_size_dirty_mask_ |= bit;
            return;
        }
        appended_mask_ |= bit;
        var_size_dirty_mask_ |= bit;
    }

    // ==================== Four-Tier Serialization Engine ====================

    void apply_in_place_patches(uint8_t* target, uint32_t base_offset) const noexcept {
        uint32_t mask = same_size_dirty_mask_;
        while (mask != 0U) {
            int slot = __builtin_ctz(mask);
            if (slot == SLOT_USER) {
                user_.apply_in_place_patches(target, base_offset);
            } else if (slot == SLOT_ITEMS) {
                for (uint16_t i = 0; i < item_count_; i++) {
                    uint16_t t_idx = item_tape_idx_[i];
                    if (tape_[t_idx].flags == FLAG_DIRTY_SAME_SIZE) {
                        items_pool_[i].apply_in_place_patches(target, base_offset);
                    }
                }
            } else {
                int16_t idx = slot_tape_idx_[slot];
                if (idx >= 0 && tape_[idx].flags == FLAG_DIRTY_SAME_SIZE) {
                    uint8_t* patch_ptr = target + (tape_[idx].value_offset - base_offset);
                    switch (slot) {
                        case SLOT_ORDER_ID:   WireWriter::patch_varint64_at(patch_ptr, order_id_); break;
                        case SLOT_BUYER_NAME: std::memcpy(patch_ptr, buyer_name_.data(), buyer_name_.size()); break;
                        case SLOT_STATUS:     WireWriter::patch_varint64_at(patch_ptr, static_cast<uint32_t>(status_)); break;
                        case SLOT_UPDATED_AT: WireWriter::patch_fixed64_at(patch_ptr, updated_at_fixed64_); break;
                        case SLOT_PAYLOAD:    std::memcpy(patch_ptr, payload_.data(), payload_.size()); break;
                        case SLOT_AUTH_TOKEN: std::memcpy(patch_ptr, auth_token_.data(), auth_token_.size()); break;
                        case SLOT_TRACE_ID:   std::memcpy(patch_ptr, trace_id_.data(), trace_id_.size()); break;
                        case SLOT_TOTAL:      WireWriter::patch_varint64_at(patch_ptr, total_cents_); break;
                    }
                }
            }
            mask &= (mask - 1U);
        }
    }

    /**
     * Writes a length-delimited sub-message in a SINGLE PASS using speculative 1-byte length backpatching!
     * Completely eliminates the separate computeSerializedSize() traversal!
     */
    template <typename SubMsg>
    static void write_submessage_single_pass(uint8_t tag_byte, const SubMsg& sub, WireWriter& w) noexcept {
        w.write_byte(tag_byte);
        uint8_t* len_slot = w.ptr++; // Speculatively reserve 1 byte for length (< 128 bytes)
        uint8_t* body_start = w.ptr;
        sub.write_spliced_to(w);
        uint32_t child_len = static_cast<uint32_t>(w.ptr - body_start);
        if (child_len < 128U) [[likely]] {
            *len_slot = static_cast<uint8_t>(child_len);
        } else {
            uint32_t len_bytes = varint32_size(child_len);
            std::memmove(body_start + (len_bytes - 1), body_start, child_len);
            w.ptr = len_slot;
            w.write_varint32(child_len);
            w.ptr += child_len;
        }
    }

    size_t write_to_buffer(uint8_t* out_buf) const noexcept {
        // Fast-Path 0: 100% unmodified message -> single memcpy
        if (is_clean()) {
            std::memcpy(out_buf, buf_ + raw_off_, raw_len_);
            return raw_len_;
        }
        // Fast-Path 1: Only same-size/fixed-size fields modified -> single memcpy + O(1) offset patch
        if (is_same_size_dirty_only()) {
            std::memcpy(out_buf, buf_ + raw_off_, raw_len_);
            apply_in_place_patches(out_buf, raw_off_);
            return raw_len_;
        }

        // Fast-Path 2 & 3: Single-Pass Contiguous Span Coalescing + Speculative Sub-message Backpatching
        WireWriter w(out_buf);
        int32_t span_start = -1;
        uint32_t span_end = 0;

        for (uint16_t i = 0; i < tape_count_; i++) {
            const TapeEntry& e = tape_[i];
            if (e.flags == FLAG_CLEAN_RAW) {
                if (span_start < 0) {
                    span_start = static_cast<int32_t>(e.tag_offset);
                    span_end = e.record_end;
                } else if (span_end == e.tag_offset) {
                    span_end = e.record_end;
                } else {
                    w.write_raw(buf_ + span_start, span_end - static_cast<uint32_t>(span_start));
                    span_start = static_cast<int32_t>(e.tag_offset);
                    span_end = e.record_end;
                }
            } else if (e.flags == FLAG_DELETED || e.flags == FLAG_SHADOWED) {
                if (span_start >= 0) {
                    w.write_raw(buf_ + span_start, span_end - static_cast<uint32_t>(span_start));
                    span_start = -1;
                }
            } else {
                if (span_start >= 0) {
                    w.write_raw(buf_ + span_start, span_end - static_cast<uint32_t>(span_start));
                    span_start = -1;
                }
                write_dirty_tape_entry(i, e.field_number, w);
            }
        }

        if (span_start >= 0) {
            w.write_raw(buf_ + span_start, span_end - static_cast<uint32_t>(span_start));
        }

        if (appended_mask_ != 0U) {
            write_appended_fields(w);
        }
        return w.size();
    }

    void write_dirty_tape_entry(uint16_t tape_idx, uint16_t field_num, WireWriter& w) const noexcept {
        switch (field_num) {
            case 1:  w.write_byte(0x08); w.write_varint64(order_id_); break;
            case 2:  w.write_string(0x12, buyer_name_); break;
            case 3:  w.write_byte(0x18); w.write_varint32(static_cast<uint32_t>(status_)); break;
            case 4:  w.write_byte(0x21); w.write_fixed64(updated_at_fixed64_); break;
            case 5:  write_submessage_single_pass(0x2A, user_, w); break;
            case 6: {
                // Binary search item index from item_tape_idx_
                const uint16_t* it = std::lower_bound(item_tape_idx_, item_tape_idx_ + item_count_, tape_idx);
                uint16_t item_idx = static_cast<uint16_t>(it - item_tape_idx_);
                write_submessage_single_pass(0x32, items_pool_[item_idx], w);
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
        if ((appended_mask_ & BIT_STATUS) && status_ != 0) { w.write_byte(0x18); w.write_varint32(static_cast<uint32_t>(status_)); }
        if ((appended_mask_ & BIT_UPDATED_AT) && updated_at_fixed64_ != 0) { w.write_byte(0x21); w.write_fixed64(updated_at_fixed64_); }
        if (appended_mask_ & BIT_USER) { write_submessage_single_pass(0x2A, user_, w); }
        if (appended_mask_ & BIT_ITEMS) {
            for (uint16_t i = 0; i < appended_item_count_; i++) {
                write_submessage_single_pass(0x32, items_pool_[item_count_ + i], w);
            }
        }
        if ((appended_mask_ & BIT_PAYLOAD) && !payload_.empty()) { w.write_string(0x3A, payload_); }
        if ((appended_mask_ & BIT_AUTH_TOKEN) && !auth_token_.empty()) { w.write_string(0x42, auth_token_); }
        if ((appended_mask_ & BIT_TRACE_ID) && !trace_id_.empty()) { w.write_string(0x4A, trace_id_); }
        if ((appended_mask_ & BIT_TOTAL) && total_cents_ != 0) { w.write_byte(0x50); w.write_varint64(total_cents_); }
    }
};

} // namespace lazyproto
