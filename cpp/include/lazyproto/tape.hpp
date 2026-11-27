#pragma once

#include "lazyproto/wire.hpp"

namespace lazyproto {

// ============================================================================
// 3. Shared 16-Byte TapeEntry, Overflow-Safe TapeStorage, and Wire Splicing
// ============================================================================

struct alignas(16) TapeEntry {
    uint32_t tag_offset;
    uint32_t record_end;
    uint32_t tag;        // [31..3] 29-bit field_number | [2..0] wire_type
    uint8_t  header_len; // value_offset - tag_offset (<= 20 even for overlong tag+len)
    uint8_t  flags;
    uint16_t reserved;

    uint32_t field_number() const noexcept { return tag >> 3; }
    uint8_t  wire_type() const noexcept { return static_cast<uint8_t>(tag & 7U); }
    uint32_t value_offset() const noexcept { return tag_offset + header_len; }
    uint32_t value_length() const noexcept { return record_end - (tag_offset + header_len); }
    uint32_t record_length() const noexcept { return record_end - tag_offset; }
};
static_assert(sizeof(TapeEntry) == 16, "TapeEntry must remain 16 bytes");

/**
 * Inline POD buffer up to InlineCap entries (zero constructor initialization work on inline_buf_),
 * spilling transparently to std::vector while retaining heap capacity across wrap() resets.
 */
template <uint32_t InlineCap>
class TapeStorage {
public:
    TapeEntry& operator[](uint32_t idx) noexcept { return data()[idx]; }
    const TapeEntry& operator[](uint32_t idx) const noexcept { return data()[idx]; }

    TapeEntry* data() noexcept {
        return spilled_ ? heap_.data() : inline_buf_;
    }

    const TapeEntry* data() const noexcept {
        return spilled_ ? heap_.data() : inline_buf_;
    }

    uint32_t size() const noexcept { return count_; }
    bool empty() const noexcept { return count_ == 0; }

    void reset() noexcept {
        count_ = 0;
        spilled_ = false;
        heap_.clear();
    }

    uint32_t append(const TapeEntry& entry) {
        uint32_t idx = count_;
        if (!spilled_) {
            if (idx < InlineCap) [[likely]] {
                inline_buf_[idx] = entry;
                count_ = idx + 1;
                return idx;
            }
            heap_.assign(inline_buf_, inline_buf_ + InlineCap);
            spilled_ = true;
        }
        heap_.push_back(entry);
        count_ = idx + 1;
        return idx;
    }

private:
    TapeEntry inline_buf_[InlineCap]; // Intentionally uninitialized POD for O(1) wrapper construction
    std::vector<TapeEntry> heap_;
    uint32_t count_ = 0;
    bool spilled_ = false;
};

/**
 * Shared bounds-checked shallow wire scanner invoking on_field(idx, TapeEntry&).
 */
template <uint32_t InlineCap, typename Callback>
inline void scan_wire_range(
        const uint8_t* buf,
        uint32_t start,
        uint32_t limit,
        TapeStorage<InlineCap>& tape,
        Callback&& on_field) {
    uint32_t cursor = start;
    while (cursor < limit) {
        const uint32_t tag_off = cursor;
        uint32_t tag = buf[cursor++];
        if (tag & 0x80U) [[unlikely]] {
            cursor--;
            if (!read_varint32_checked(buf, cursor, limit, tag)) {
                throw std::runtime_error("Truncated protobuf tag varint");
            }
        }
        const uint32_t field_num = tag >> 3;
        if (field_num == 0U) [[unlikely]] {
            throw std::runtime_error("Invalid protobuf field_number 0");
        }
        const uint8_t wt = static_cast<uint8_t>(tag & 7U);
        uint32_t val_off = cursor;

        if (wt == WIRETYPE_VARINT) {
            if (!skip_varint_swar_checked(buf, cursor, limit, cursor)) {
                throw std::runtime_error("Truncated protobuf varint value");
            }
        } else if (wt == WIRETYPE_LENGTH_DELIMITED) {
            uint32_t len = 0;
            if (cursor < limit && !(buf[cursor] & 0x80U)) [[likely]] {
                len = buf[cursor++];
            } else if (!read_varint32_checked(buf, cursor, limit, len)) {
                throw std::runtime_error("Truncated protobuf length-delimited header");
            }
            if (len > limit - cursor) [[unlikely]] {
                throw std::runtime_error("Truncated protobuf length-delimited payload");
            }
            val_off = cursor;
            cursor += len;
        } else if (wt == WIRETYPE_FIXED64) {
            if (8U > limit - cursor) [[unlikely]] {
                throw std::runtime_error("Truncated protobuf fixed64 value");
            }
            cursor += 8U;
        } else if (wt == WIRETYPE_FIXED32) {
            if (4U > limit - cursor) [[unlikely]] {
                throw std::runtime_error("Truncated protobuf fixed32 value");
            }
            cursor += 4U;
        } else {
            throw std::runtime_error("Unsupported protobuf wire_type");
        }

        uint32_t header_len = val_off - tag_off;
        uint32_t idx = tape.append(TapeEntry{
                tag_off, cursor, tag,
                static_cast<uint8_t>(header_len), FLAG_CLEAN_RAW, 0U});
        on_field(idx, tape[idx]);
    }
}

/**
 * Shared single-pass contiguous-span splicer across TapeStorage.
 */
template <uint32_t InlineCap, typename DirtyWriter, typename AppendWriter>
inline void write_spliced_tape(
        const uint8_t* buf,
        const TapeStorage<InlineCap>& tape,
        WireWriter& w,
        DirtyWriter&& write_dirty,
        AppendWriter&& write_appended) noexcept {
    const TapeEntry* entries = tape.data();
    const uint32_t n = tape.size();
    int64_t span_start = -1;
    uint32_t span_end = 0;

    for (uint32_t i = 0; i < n; i++) {
        const TapeEntry& e = entries[i];
        if (e.flags == FLAG_CLEAN_RAW || e.flags == FLAG_SPLIT_CONT || e.flags == FLAG_SPLIT_TAIL) {
            if (span_start < 0) {
                span_start = static_cast<int64_t>(e.tag_offset);
                span_end = e.record_end;
            } else if (span_end == e.tag_offset) {
                span_end = e.record_end;
            } else {
                w.write_raw(buf + span_start, span_end - static_cast<uint32_t>(span_start));
                span_start = static_cast<int64_t>(e.tag_offset);
                span_end = e.record_end;
            }
        } else if (e.flags == FLAG_DELETED || e.flags == FLAG_SHADOWED) {
            if (span_start >= 0) {
                w.write_raw(buf + span_start, span_end - static_cast<uint32_t>(span_start));
                span_start = -1;
            }
        } else {
            if (span_start >= 0) {
                w.write_raw(buf + span_start, span_end - static_cast<uint32_t>(span_start));
                span_start = -1;
            }
            write_dirty(i, e, w);
        }
    }
    if (span_start >= 0) {
        w.write_raw(buf + span_start, span_end - static_cast<uint32_t>(span_start));
    }
    write_appended(w);
}

/**
 * Shared single-pass sub-message writer with speculative 1-byte length backpatching.
 */
template <typename WriteBodyFn>
inline void write_submessage_single_pass(uint32_t field_number, WireWriter& w, WriteBodyFn&& write_body) noexcept {
    if (field_number <= 15U) [[likely]] {
        w.write_byte(static_cast<uint8_t>((field_number << 3) | WIRETYPE_LENGTH_DELIMITED));
    } else {
        w.write_tag(field_number, WIRETYPE_LENGTH_DELIMITED);
    }
    uint8_t* len_slot = w.ptr++; // Speculatively reserve 1 byte (< 128B)
    uint8_t* body_start = w.ptr;
    write_body(w);
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


} // namespace lazyproto
