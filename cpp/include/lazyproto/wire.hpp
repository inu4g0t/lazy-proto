#pragma once

#include <algorithm>
#include <cstdint>
#include <cstring>
#include <memory>
#include <stdexcept>
#include <string>
#include <string_view>
#include <vector>

namespace lazyproto {

// ============================================================================
// 1. Wire Constants, Branchless SWAR Varint Primitives & Safe Scanner Helpers
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
constexpr uint8_t FLAG_SPLIT_CONT      = 5;
constexpr uint8_t FLAG_SPLIT_TAIL      = 6;

constexpr uint64_t MSB_MASK_64 = 0x8080808080808080ULL;

inline constexpr uint32_t make_tag(uint32_t field_number, uint8_t wire_type) noexcept {
    return (field_number << 3) | wire_type;
}

inline uint32_t varint32_size(uint32_t value) noexcept {
    int log2 = 31 - __builtin_clz(value | 1U);
    return static_cast<uint32_t>((log2 * 9 + 73) >> 6);
}

inline uint32_t varint_int32_size(int32_t value) noexcept {
    return value < 0 ? 10U : varint32_size(static_cast<uint32_t>(value));
}

inline uint32_t varint64_size(uint64_t value) noexcept {
    int log2 = 63 - __builtin_clzll(value | 1ULL);
    return static_cast<uint32_t>((log2 * 9 + 73) >> 6);
}

inline uint32_t tag_size(uint32_t field_number) noexcept {
    return varint32_size(field_number << 3);
}

/**
 * Bounds-checked branchless SWAR 64-bit varint skip using unaligned load + TZCNT.
 */
inline bool skip_varint_swar_checked(const uint8_t* buf, uint32_t pos, uint32_t limit, uint32_t& out_next) noexcept {
    if (pos < limit && static_cast<int8_t>(buf[pos]) >= 0) [[likely]] {
        out_next = pos + 1;
        return true;
    }
    if (pos + 8 <= limit) [[likely]] {
        uint64_t word;
        std::memcpy(&word, buf + pos, 8);
        uint64_t term_mask = ~word & MSB_MASK_64;
        if (term_mask != 0ULL) [[likely]] {
            out_next = pos + (static_cast<uint32_t>(__builtin_ctzll(term_mask)) >> 3) + 1;
            return true;
        }
    }
    uint32_t max_end = std::min(limit, pos + 10U);
    while (pos < max_end) {
        if (static_cast<int8_t>(buf[pos++]) >= 0) {
            out_next = pos;
            return true;
        }
    }
    return false;
}

inline uint32_t skip_varint_swar(const uint8_t* buf, uint32_t pos, uint32_t limit) noexcept {
    uint32_t next = limit;
    skip_varint_swar_checked(buf, pos, limit, next);
    return next;
}

inline bool read_varint32_checked(const uint8_t* buf, uint32_t& pos, uint32_t limit, uint32_t& out) noexcept {
    if (pos >= limit) [[unlikely]] return false;
    uint32_t b = buf[pos++];
    if (!(b & 0x80U)) [[likely]] {
        out = b;
        return true;
    }
    uint32_t result = b & 0x7FU;
    for (int shift = 7; shift <= 28; shift += 7) {
        if (pos >= limit) [[unlikely]] return false;
        b = buf[pos++];
        result |= (b & 0x7FU) << shift;
        if (!(b & 0x80U)) {
            out = result;
            return true;
        }
    }
    // Negative int32 10-byte sign extension
    for (int i = 0; i < 5; i++) {
        if (pos >= limit) [[unlikely]] return false;
        if (!(buf[pos++] & 0x80U)) {
            out = result;
            return true;
        }
    }
    return false;
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
// 2. WireWriter & Reusable ScratchArena
// ============================================================================

struct WireWriter {
    uint8_t* base;
    uint8_t* ptr;

    explicit WireWriter(uint8_t* out_buf) noexcept : base(out_buf), ptr(out_buf) {}

    size_t size() const noexcept { return static_cast<size_t>(ptr - base); }

    void write_raw(const uint8_t* src, uint32_t len) noexcept {
        if (len != 0U) {
            std::memcpy(ptr, src, len);
            ptr += len;
        }
    }

    void write_byte(uint8_t b) noexcept { *ptr++ = b; }

    void write_tag(uint32_t field_number, uint8_t wire_type) noexcept {
        write_varint32(make_tag(field_number, wire_type));
    }

    void write_varint32(uint32_t v) noexcept {
        while (v >= 0x80U) {
            *ptr++ = static_cast<uint8_t>(v | 0x80U);
            v >>= 7;
        }
        *ptr++ = static_cast<uint8_t>(v);
    }

    void write_int32(int32_t v) noexcept {
        if (v >= 0) {
            write_varint32(static_cast<uint32_t>(v));
        } else {
            write_varint64(static_cast<uint64_t>(static_cast<int64_t>(v)));
        }
    }

    void write_varint64(uint64_t v) noexcept {
        while (v >= 0x80ULL) {
            *ptr++ = static_cast<uint8_t>(v | 0x80ULL);
            v >>= 7;
        }
        *ptr++ = static_cast<uint8_t>(v);
    }

    void write_fixed32(uint32_t v) noexcept {
        std::memcpy(ptr, &v, 4);
        ptr += 4;
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
        if (!sv.empty()) {
            std::memcpy(ptr, sv.data(), sv.size());
            ptr += sv.size();
        }
    }

    void write_len_delimited(uint32_t field_number, std::string_view sv) noexcept {
        write_tag(field_number, WIRETYPE_LENGTH_DELIMITED);
        write_varint32(static_cast<uint32_t>(sv.size()));
        if (!sv.empty()) {
            std::memcpy(ptr, sv.data(), sv.size());
            ptr += sv.size();
        }
    }

    static void patch_varint32_at(uint8_t* target, uint32_t v) noexcept {
        while (v >= 0x80U) {
            *target++ = static_cast<uint8_t>(v | 0x80U);
            v >>= 7;
        }
        *target = static_cast<uint8_t>(v);
    }

    static void patch_int32_at(uint8_t* target, int32_t v) noexcept {
        if (v >= 0) {
            patch_varint32_at(target, static_cast<uint32_t>(v));
        } else {
            patch_varint64_at(target, static_cast<uint64_t>(static_cast<int64_t>(v)));
        }
    }

    static void patch_varint64_at(uint8_t* target, uint64_t v) noexcept {
        while (v >= 0x80ULL) {
            *target++ = static_cast<uint8_t>(v | 0x80ULL);
            v >>= 7;
        }
        *target = static_cast<uint8_t>(v);
    }

    static void patch_fixed32_at(uint8_t* target, uint32_t v) noexcept {
        std::memcpy(target, &v, 4);
    }

    static void patch_fixed64_at(uint8_t* target, uint64_t v) noexcept {
        std::memcpy(target, &v, 8);
    }

    static void patch_double_at(uint8_t* target, double v) noexcept {
        std::memcpy(target, &v, 8);
    }
};

/**
 * Reusable contiguous byte arena for optional owned string/bytes copies during reuse loops.
 * Stores offset+length so vector growth never invalidates previously stored slices.
 */
class ScratchArena {
public:
    struct SliceRef {
        uint32_t offset = 0;
        uint32_t length = 0;
    };

    void reset() noexcept { buf_.clear(); }

    SliceRef store(std::string_view sv) {
        uint32_t off = static_cast<uint32_t>(buf_.size());
        uint32_t len = static_cast<uint32_t>(sv.size());
        if (len > 0) {
            buf_.insert(buf_.end(), reinterpret_cast<const uint8_t*>(sv.data()),
                        reinterpret_cast<const uint8_t*>(sv.data()) + len);
        }
        return {off, len};
    }

    std::string_view resolve(SliceRef ref) const noexcept {
        return ref.length == 0
            ? std::string_view()
            : std::string_view(reinterpret_cast<const char*>(buf_.data() + ref.offset), ref.length);
    }

private:
    std::vector<uint8_t> buf_;
};


} // namespace lazyproto
