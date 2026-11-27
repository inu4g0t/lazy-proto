#pragma once

#include "lazyproto/tape.hpp"

namespace lazyproto {

// ============================================================================
// Generic Lower-Level Input-Bytes Wrapper: WireView
//   - Wrap any protobuf byte slice with O(1) cost
//   - Single mutually-exclusive FieldState state machine per field slot
//   - Automatic child->parent dirty propagation & stale-child detachment
//   - Merge modifications back with untouched original bytes (FP0/FP1/FP2/FP3)
// ============================================================================

class WireView {
public:
    static constexpr uint32_t DIRECT_MAX = 32;

    enum class FieldState : uint8_t {
        RAW_CLEAN = 0,
        SCALAR_OVERRIDE = 1,
        SUBVIEW_OVERRIDE = 2,
        REPEATED_PATCHED = 3,
        DELETED = 4
    };

    enum class DirtyMode : uint8_t {
        NONE = 0,
        SAME_SIZE = 1,
        VAR_SIZE = 2,
        APPENDED = 3
    };

    enum class CacheKind : uint8_t {
        NONE = 0,
        VARINT = 1,
        FIXED32 = 2,
        FIXED64 = 3,
        BYTES = 4,
        SUBVIEW = 5
    };

    struct FieldCache {
        uint32_t field_number = 0;
        int32_t  last_tape_idx = -1;
        bool     present = false;
        bool     decoded = false;
        FieldState state = FieldState::RAW_CLEAN;
        DirtyMode dirty_mode = DirtyMode::NONE;
        CacheKind kind = CacheKind::NONE;

        uint64_t num_val = 0;
        bool     bytes_in_arena = false;
        std::string_view bytes_val;
        ScratchArena::SliceRef arena_ref{};
        std::string materialized_sub_tmp;

        uint32_t child_pool_idx = UINT32_MAX;
        std::vector<uint32_t> repeated_tape_indices;
        std::vector<uint32_t> repeated_child_pool_indices;
        uint32_t rep_dirty_count = 0;
    };

    WireView() noexcept { reset_internal(nullptr, 0); }
    WireView(const uint8_t* data, size_t len) noexcept { reset_internal(data, len); }

    void wrap(const uint8_t* data, size_t len) noexcept {
        WireView* saved_parent = parent_;
        uint32_t saved_field = parent_field_;
        int32_t saved_rep = parent_repeated_idx_;
        reset_internal(data, len);
        if (saved_parent != nullptr) {
            parent_ = saved_parent;
            parent_field_ = saved_field;
            parent_repeated_idx_ = saved_rep;
            replaced_from_wrap_ = true;
            notify_parent();
        }
    }

    bool is_scanned() const noexcept { return scanned_; }

    void validate_deep() { ensure_scanned(); }

    bool is_clean() const noexcept {
        return !replaced_from_wrap_ && is_self_clean();
    }
    bool is_same_size_dirty_only() const noexcept {
        return !replaced_from_wrap_ && is_self_same_size_dirty_only();
    }

    bool is_self_clean() const noexcept {
        return !multi_segment_
            && same_size_dirty_count_ == 0 && var_size_dirty_count_ == 0
            && appended_count_ == 0 && !has_deleted_;
    }
    bool is_self_same_size_dirty_only() const noexcept {
        return !multi_segment_
            && var_size_dirty_count_ == 0 && appended_count_ == 0
            && !has_deleted_ && same_size_dirty_count_ != 0;
    }

    size_t raw_size() const noexcept { return raw_len_; }

    // ==================== Read API ====================

    bool has(uint32_t field_number) {
        return resolve_for_read(field_number) != nullptr;
    }

    uint64_t get_varint(uint32_t field_number, uint64_t default_val = 0) {
        FieldCache* fc = resolve_for_read(field_number);
        if (!fc) return default_val;
        if (fc->decoded && fc->kind == CacheKind::VARINT) return fc->num_val;
        if (fc->state == FieldState::SCALAR_OVERRIDE || fc->state == FieldState::SUBVIEW_OVERRIDE) return default_val;
        if (fc->last_tape_idx < 0) return default_val;
        const TapeEntry& e = tape_[static_cast<uint32_t>(fc->last_tape_idx)];
        if (e.wire_type() != WIRETYPE_VARINT) return default_val;
        fc->num_val = read_varint64_at(buf_, e.value_offset());
        fc->kind = CacheKind::VARINT;
        fc->decoded = true;
        return fc->num_val;
    }

    int32_t get_int32(uint32_t field_number, int32_t default_val = 0) {
        return static_cast<int32_t>(get_varint(field_number, static_cast<uint64_t>(static_cast<int64_t>(default_val))));
    }

    uint32_t get_fixed32(uint32_t field_number, uint32_t default_val = 0) {
        FieldCache* fc = resolve_for_read(field_number);
        if (!fc) return default_val;
        if (fc->decoded && fc->kind == CacheKind::FIXED32) return static_cast<uint32_t>(fc->num_val);
        if (fc->state == FieldState::SCALAR_OVERRIDE || fc->state == FieldState::SUBVIEW_OVERRIDE) return default_val;
        if (fc->last_tape_idx < 0) return default_val;
        const TapeEntry& e = tape_[static_cast<uint32_t>(fc->last_tape_idx)];
        if (e.wire_type() != WIRETYPE_FIXED32) return default_val;
        fc->num_val = read_fixed32_at(buf_, e.value_offset());
        fc->kind = CacheKind::FIXED32;
        fc->decoded = true;
        return static_cast<uint32_t>(fc->num_val);
    }

    uint64_t get_fixed64(uint32_t field_number, uint64_t default_val = 0) {
        FieldCache* fc = resolve_for_read(field_number);
        if (!fc) return default_val;
        if (fc->decoded && fc->kind == CacheKind::FIXED64) return fc->num_val;
        if (fc->state == FieldState::SCALAR_OVERRIDE || fc->state == FieldState::SUBVIEW_OVERRIDE) return default_val;
        if (fc->last_tape_idx < 0) return default_val;
        const TapeEntry& e = tape_[static_cast<uint32_t>(fc->last_tape_idx)];
        if (e.wire_type() != WIRETYPE_FIXED64) return default_val;
        fc->num_val = read_fixed64_at(buf_, e.value_offset());
        fc->kind = CacheKind::FIXED64;
        fc->decoded = true;
        return fc->num_val;
    }

    double get_double(uint32_t field_number, double default_val = 0.0) {
        uint64_t def_bits = 0;
        std::memcpy(&def_bits, &default_val, 8);
        uint64_t bits = get_fixed64(field_number, def_bits);
        double res = 0.0;
        std::memcpy(&res, &bits, 8);
        return res;
    }

    std::string_view get_bytes(uint32_t field_number) {
        FieldCache* fc = resolve_for_read(field_number);
        if (!fc) return {};
        if (fc->state == FieldState::SUBVIEW_OVERRIDE && fc->child_pool_idx != UINT32_MAX) {
            child_pool_[fc->child_pool_idx]->merge_to(fc->materialized_sub_tmp);
            return fc->materialized_sub_tmp;
        }
        if (fc->state == FieldState::SCALAR_OVERRIDE) {
            if (fc->kind != CacheKind::BYTES) return {};
            return fc->bytes_in_arena ? arena_.resolve(fc->arena_ref) : fc->bytes_val;
        }
        if (fc->decoded && fc->kind == CacheKind::BYTES) {
            return fc->bytes_in_arena ? arena_.resolve(fc->arena_ref) : fc->bytes_val;
        }
        if (fc->last_tape_idx < 0) return {};
        const TapeEntry& e = tape_[static_cast<uint32_t>(fc->last_tape_idx)];
        if (e.wire_type() != WIRETYPE_LENGTH_DELIMITED) return {};
        fc->bytes_val = std::string_view(reinterpret_cast<const char*>(buf_ + e.value_offset()), e.value_length());
        fc->bytes_in_arena = false;
        if (fc->kind == CacheKind::NONE) {
            fc->kind = CacheKind::BYTES;
            fc->decoded = true;
        }
        return fc->bytes_val;
    }

    WireView& sub(uint32_t field_number) {
        ensure_scanned();
        int32_t s = find_or_create_slot(field_number);
        FieldCache& fc = slots_[static_cast<size_t>(s)];
        if (fc.child_pool_idx != UINT32_MAX) {
            return *child_pool_[fc.child_pool_idx];
        }
        WireView* child = alloc_child(field_number, -1);
        if (fc.present && fc.state != FieldState::DELETED) {
            if (fc.state == FieldState::SCALAR_OVERRIDE) {
                if (fc.kind == CacheKind::BYTES) {
                    std::string_view sv = fc.bytes_in_arena ? arena_.resolve(fc.arena_ref) : fc.bytes_val;
                    child->owned_input_copy_.assign(sv.data(), sv.size());
                    child->reset_internal(reinterpret_cast<const uint8_t*>(child->owned_input_copy_.data()),
                                          child->owned_input_copy_.size());
                }
            } else if (fc.repeated_tape_indices.size() > 1U) {
                child->wrap_multi_segments(buf_, tape_, fc.repeated_tape_indices);
            } else if (fc.last_tape_idx >= 0) {
                const TapeEntry& e = tape_[static_cast<uint32_t>(fc.last_tape_idx)];
                if (e.wire_type() == WIRETYPE_LENGTH_DELIMITED) {
                    child->reset_internal(buf_ + e.value_offset(), e.value_length());
                }
            }
        }
        child->parent_ = this;
        child->parent_field_ = field_number;
        child->parent_repeated_idx_ = -1;
        if (fc.state != FieldState::SCALAR_OVERRIDE) {
            fc.kind = CacheKind::SUBVIEW;
            fc.decoded = true;
        }
        fc.child_pool_idx = static_cast<uint32_t>(child_pool_used_ - 1);
        return *child;
    }

    size_t repeated_count(uint32_t field_number) {
        ensure_scanned();
        int32_t s = find_slot(field_number);
        if (s < 0) return 0;
        const FieldCache& fc = slots_[static_cast<size_t>(s)];
        if (fc.state == FieldState::DELETED) return 0;
        return fc.repeated_tape_indices.size();
    }

    std::string_view get_repeated_bytes(uint32_t field_number, size_t idx) {
        ensure_scanned();
        int32_t s = find_slot(field_number);
        if (s < 0 || slots_[static_cast<size_t>(s)].state == FieldState::DELETED
                || idx >= slots_[static_cast<size_t>(s)].repeated_tape_indices.size()) {
            throw std::out_of_range("WireView get_repeated_bytes index out of range");
        }
        uint32_t t_idx = slots_[static_cast<size_t>(s)].repeated_tape_indices[idx];
        const TapeEntry& e = tape_[t_idx];
        if (e.wire_type() != WIRETYPE_LENGTH_DELIMITED) return {};
        return std::string_view(reinterpret_cast<const char*>(buf_ + e.value_offset()), e.value_length());
    }

    WireView& repeated_sub(uint32_t field_number, size_t idx) {
        ensure_scanned();
        int32_t s = find_slot(field_number);
        if (s < 0 || slots_[static_cast<size_t>(s)].state == FieldState::DELETED
                || idx >= slots_[static_cast<size_t>(s)].repeated_tape_indices.size()) {
            throw std::out_of_range("WireView repeated_sub index out of range");
        }
        FieldCache& fc = slots_[static_cast<size_t>(s)];
        if (fc.repeated_child_pool_indices.size() < fc.repeated_tape_indices.size()) {
            fc.repeated_child_pool_indices.resize(fc.repeated_tape_indices.size(), UINT32_MAX);
        }
        uint32_t pidx = fc.repeated_child_pool_indices[idx];
        if (pidx != UINT32_MAX) {
            return *child_pool_[pidx];
        }
        uint32_t t_idx = fc.repeated_tape_indices[idx];
        const TapeEntry& e = tape_[t_idx];
        WireView* child = alloc_child(field_number, static_cast<int32_t>(idx));
        if (e.wire_type() == WIRETYPE_LENGTH_DELIMITED) {
            child->reset_internal(buf_ + e.value_offset(), e.value_length());
        }
        child->parent_ = this;
        child->parent_field_ = field_number;
        child->parent_repeated_idx_ = static_cast<int32_t>(idx);
        fc.repeated_child_pool_indices[idx] = static_cast<uint32_t>(child_pool_used_ - 1);
        return *child;
    }

    // ==================== Write API (Single Atomic State Machine) ====================

    WireView& set_varint(uint32_t field_number, uint64_t val) {
        ensure_scanned();
        FieldCache& fc = slots_[static_cast<size_t>(find_or_create_slot(field_number))];
        enter_scalar_override(fc, CacheKind::VARINT, WIRETYPE_VARINT, varint64_size(val));
        fc.num_val = val;
        notify_parent();
        return *this;
    }

    WireView& set_int32(uint32_t field_number, int32_t val) {
        return set_varint(field_number, static_cast<uint64_t>(static_cast<int64_t>(val)));
    }

    WireView& set_fixed32(uint32_t field_number, uint32_t val) {
        ensure_scanned();
        FieldCache& fc = slots_[static_cast<size_t>(find_or_create_slot(field_number))];
        enter_scalar_override(fc, CacheKind::FIXED32, WIRETYPE_FIXED32, 4U);
        fc.num_val = val;
        notify_parent();
        return *this;
    }

    WireView& set_fixed64(uint32_t field_number, uint64_t val) {
        ensure_scanned();
        FieldCache& fc = slots_[static_cast<size_t>(find_or_create_slot(field_number))];
        enter_scalar_override(fc, CacheKind::FIXED64, WIRETYPE_FIXED64, 8U);
        fc.num_val = val;
        notify_parent();
        return *this;
    }

    WireView& set_double(uint32_t field_number, double val) {
        uint64_t bits = 0;
        std::memcpy(&bits, &val, 8);
        return set_fixed64(field_number, bits);
    }

    WireView& set_bytes(uint32_t field_number, std::string_view sv) {
        ensure_scanned();
        FieldCache& fc = slots_[static_cast<size_t>(find_or_create_slot(field_number))];
        enter_scalar_override(fc, CacheKind::BYTES, WIRETYPE_LENGTH_DELIMITED, static_cast<uint32_t>(sv.size()));
        fc.bytes_val = sv;
        fc.bytes_in_arena = false;
        notify_parent();
        return *this;
    }

    WireView& set_bytes_copy(uint32_t field_number, std::string_view sv) {
        ensure_scanned();
        FieldCache& fc = slots_[static_cast<size_t>(find_or_create_slot(field_number))];
        ScratchArena::SliceRef ref = arena_.store(sv);
        enter_scalar_override(fc, CacheKind::BYTES, WIRETYPE_LENGTH_DELIMITED, static_cast<uint32_t>(sv.size()));
        fc.arena_ref = ref;
        fc.bytes_in_arena = true;
        notify_parent();
        return *this;
    }

    WireView& erase(uint32_t field_number) {
        ensure_scanned();
        int32_t s = find_slot(field_number);
        if (s < 0) return *this;
        FieldCache& fc = slots_[static_cast<size_t>(s)];
        leave_current_state(fc, false, false);
        fc.state = FieldState::DELETED;
        fc.present = false;
        fc.decoded = false;
        fc.kind = CacheKind::NONE;
        if (!fc.repeated_tape_indices.empty()) {
            for (uint32_t t_idx : fc.repeated_tape_indices) {
                tape_[t_idx].flags = FLAG_DELETED;
            }
            has_deleted_ = true;
        }
        notify_parent();
        return *this;
    }

    // ==================== Serialization (FP0 / FP1 / FP2 / FP3) ====================

    size_t max_merged_size() const noexcept {
        if (is_self_clean() || is_self_same_size_dirty_only()) {
            return raw_len_;
        }
        size_t upper = raw_len_;
        for (size_t si = 0; si < slot_count_; si++) {
            const FieldCache& fc = slots_[si];
            switch (fc.state) {
                case FieldState::REPEATED_PATCHED:
                    for (uint32_t pidx : fc.repeated_child_pool_indices) {
                        if (pidx != UINT32_MAX && !child_pool_[pidx]->is_clean()) {
                            size_t sub_max = child_pool_[pidx]->max_merged_size();
                            upper += tag_size(fc.field_number) + varint32_size(static_cast<uint32_t>(sub_max)) + sub_max;
                        }
                    }
                    break;
                case FieldState::SUBVIEW_OVERRIDE:
                    if ((fc.dirty_mode == DirtyMode::VAR_SIZE || fc.dirty_mode == DirtyMode::APPENDED)
                            && fc.child_pool_idx != UINT32_MAX) {
                        size_t sub_max = child_pool_[fc.child_pool_idx]->max_merged_size();
                        upper += tag_size(fc.field_number) + varint32_size(static_cast<uint32_t>(sub_max)) + sub_max;
                    }
                    break;
                case FieldState::SCALAR_OVERRIDE:
                    if (fc.dirty_mode == DirtyMode::VAR_SIZE || fc.dirty_mode == DirtyMode::APPENDED) {
                        if (fc.kind == CacheKind::BYTES) {
                            size_t len = fc.bytes_in_arena ? fc.arena_ref.length : fc.bytes_val.size();
                            upper += tag_size(fc.field_number) + varint32_size(static_cast<uint32_t>(len)) + len;
                        } else if (fc.kind == CacheKind::FIXED32) {
                            upper += tag_size(fc.field_number) + 4U;
                        } else if (fc.kind == CacheKind::FIXED64) {
                            upper += tag_size(fc.field_number) + 8U;
                        } else {
                            upper += tag_size(fc.field_number) + 10U;
                        }
                    }
                    break;
                case FieldState::RAW_CLEAN:
                case FieldState::DELETED:
                    break;
            }
        }
        return upper;
    }

    size_t merge_to(uint8_t* out_buf, size_t out_cap) const {
        if (is_self_clean()) {
            if (out_cap < raw_len_) throw std::length_error("WireView output buffer too small");
            if (raw_len_ > 0) std::memcpy(out_buf, buf_, raw_len_);
            return raw_len_;
        }
        if (is_self_same_size_dirty_only()) {
            if (out_cap < raw_len_) throw std::length_error("WireView output buffer too small");
            if (raw_len_ > 0) std::memcpy(out_buf, buf_, raw_len_);
            apply_in_place_patches(out_buf);
            return raw_len_;
        }
        size_t need = max_merged_size();
        if (out_cap < need) throw std::length_error("WireView output buffer smaller than max_merged_size");
        WireWriter w(out_buf);
        write_spliced_to(w);
        return w.size();
    }

    void merge_to(std::string& out) const {
        size_t cap = max_merged_size();
        out.resize(cap);
        size_t actual = merge_to(reinterpret_cast<uint8_t*>(out.data()), cap);
        out.resize(actual);
    }

private:
    const uint8_t* buf_ = nullptr;
    uint32_t raw_len_ = 0;
    bool scanned_ = false;
    bool multi_segment_ = false;
    bool has_deleted_ = false;
    bool replaced_from_wrap_ = false;
    uint32_t same_size_dirty_count_ = 0;
    uint32_t var_size_dirty_count_ = 0;
    uint32_t appended_count_ = 0;

    TapeStorage<16> tape_;
    std::vector<FieldCache> slots_;
    size_t slot_count_ = 0;
    int32_t direct_slot_[DIRECT_MAX + 1]{};
    ScratchArena arena_;

    std::vector<std::unique_ptr<WireView>> child_pool_;
    size_t child_pool_used_ = 0;
    std::string owned_input_copy_;

    WireView* parent_ = nullptr;
    uint32_t parent_field_ = 0;
    int32_t parent_repeated_idx_ = -1;

    void reset_internal(const uint8_t* data, size_t len) noexcept {
        buf_ = data;
        raw_len_ = static_cast<uint32_t>(len);
        scanned_ = false;
        multi_segment_ = false;
        has_deleted_ = false;
        replaced_from_wrap_ = false;
        same_size_dirty_count_ = 0;
        var_size_dirty_count_ = 0;
        appended_count_ = 0;
        tape_.reset();
        for (size_t i = 0; i < slot_count_; i++) {
            FieldCache& fc = slots_[i];
            fc.field_number = 0;
            fc.last_tape_idx = -1;
            fc.present = false;
            fc.decoded = false;
            fc.state = FieldState::RAW_CLEAN;
            fc.dirty_mode = DirtyMode::NONE;
            fc.kind = CacheKind::NONE;
            fc.num_val = 0;
            fc.bytes_in_arena = false;
            fc.bytes_val = {};
            fc.child_pool_idx = UINT32_MAX;
            fc.repeated_tape_indices.clear();
            fc.repeated_child_pool_indices.clear();
            fc.rep_dirty_count = 0;
        }
        slot_count_ = 0;
        arena_.reset();
        for (size_t i = 0; i < child_pool_used_; i++) {
            child_pool_[i]->parent_ = nullptr;
        }
        child_pool_used_ = 0;
        std::memset(direct_slot_, -1, sizeof(direct_slot_));
        parent_ = nullptr;
        parent_field_ = 0;
        parent_repeated_idx_ = -1;
    }

    void wrap_multi_segments(
            const uint8_t* parent_buf,
            const TapeStorage<16>& parent_tape,
            const std::vector<uint32_t>& seg_indices) {
        reset_internal(parent_buf, 0);
        multi_segment_ = true;
        scanned_ = true;
        uint32_t total = 0;
        for (uint32_t t_idx : seg_indices) {
            const TapeEntry& se = parent_tape[t_idx];
            if (se.wire_type() != WIRETYPE_LENGTH_DELIMITED) continue;
            total += se.value_length();
            scan_wire_range(parent_buf, se.value_offset(), se.record_end, tape_, [this](uint32_t idx, TapeEntry& e) {
                on_scan_entry(idx, e);
            });
        }
        raw_len_ = total;
    }

    void ensure_scanned() {
        if (!scanned_) [[unlikely]] {
            scanned_ = true;
            if (buf_ != nullptr && raw_len_ > 0) {
                scan_wire_range(buf_, 0U, raw_len_, tape_, [this](uint32_t idx, TapeEntry& e) {
                    on_scan_entry(idx, e);
                });
            }
        }
    }

    void on_scan_entry(uint32_t idx, TapeEntry& e) {
        int32_t s = find_or_create_slot(e.field_number());
        FieldCache& fc = slots_[static_cast<size_t>(s)];
        fc.last_tape_idx = static_cast<int32_t>(idx);
        fc.present = true;
        fc.repeated_tape_indices.push_back(idx);
    }

    int32_t find_slot(uint32_t fn) const noexcept {
        if (fn <= DIRECT_MAX) return direct_slot_[fn];
        for (size_t i = 0; i < slot_count_; i++) {
            if (slots_[i].field_number == fn) return static_cast<int32_t>(i);
        }
        return -1;
    }

    int32_t find_or_create_slot(uint32_t fn) {
        int32_t s = find_slot(fn);
        if (s >= 0) return s;
        s = static_cast<int32_t>(slot_count_);
        if (slot_count_ == slots_.size()) {
            slots_.emplace_back();
        }
        slots_[slot_count_++].field_number = fn;
        if (fn <= DIRECT_MAX) direct_slot_[fn] = s;
        return s;
    }

    FieldCache* resolve_for_read(uint32_t fn) {
        ensure_scanned();
        int32_t s = find_slot(fn);
        if (s < 0) return nullptr;
        FieldCache& fc = slots_[static_cast<size_t>(s)];
        if (!fc.present || fc.state == FieldState::DELETED) return nullptr;
        return &fc;
    }

    WireView* alloc_child(uint32_t field_number, int32_t rep_idx) {
        if (child_pool_used_ == child_pool_.size()) {
            child_pool_.push_back(std::make_unique<WireView>());
        }
        WireView* child = child_pool_[child_pool_used_++].get();
        child->reset_internal(nullptr, 0);
        child->parent_ = this;
        child->parent_field_ = field_number;
        child->parent_repeated_idx_ = rep_idx;
        return child;
    }

    void leave_current_state(FieldCache& fc, bool keep_sub_view, bool keep_rep_views) noexcept {
        switch (fc.state) {
            case FieldState::SCALAR_OVERRIDE:
            case FieldState::SUBVIEW_OVERRIDE:
                if (fc.dirty_mode == DirtyMode::SAME_SIZE) same_size_dirty_count_--;
                else if (fc.dirty_mode == DirtyMode::VAR_SIZE) var_size_dirty_count_--;
                else if (fc.dirty_mode == DirtyMode::APPENDED) appended_count_--;
                fc.dirty_mode = DirtyMode::NONE;
                for (uint32_t t_idx : fc.repeated_tape_indices) {
                    tape_[t_idx].flags = FLAG_CLEAN_RAW;
                }
                break;
            case FieldState::REPEATED_PATCHED:
                for (uint32_t t_idx : fc.repeated_tape_indices) {
                    uint8_t fl = tape_[t_idx].flags;
                    if (fl == FLAG_DIRTY_SAME_SIZE) same_size_dirty_count_--;
                    else if (fl == FLAG_DIRTY_VAR_SIZE) var_size_dirty_count_--;
                    tape_[t_idx].flags = FLAG_CLEAN_RAW;
                }
                fc.rep_dirty_count = 0;
                break;
            case FieldState::DELETED:
                for (uint32_t t_idx : fc.repeated_tape_indices) {
                    tape_[t_idx].flags = FLAG_CLEAN_RAW;
                }
                break;
            case FieldState::RAW_CLEAN:
                break;
        }

        if (!keep_sub_view && fc.child_pool_idx != UINT32_MAX) {
            child_pool_[fc.child_pool_idx]->parent_ = nullptr;
            fc.child_pool_idx = UINT32_MAX;
        }
        if (!keep_rep_views) {
            for (uint32_t pidx : fc.repeated_child_pool_indices) {
                if (pidx != UINT32_MAX) {
                    child_pool_[pidx]->parent_ = nullptr;
                }
            }
            fc.repeated_child_pool_indices.clear();
        }
        fc.bytes_in_arena = false;
        fc.bytes_val = {};
        fc.state = FieldState::RAW_CLEAN;
    }

    void enter_scalar_override(FieldCache& fc, CacheKind kind, uint8_t expected_wt, uint32_t new_val_len) noexcept {
        leave_current_state(fc, false, false);
        fc.state = FieldState::SCALAR_OVERRIDE;
        fc.kind = kind;
        fc.decoded = true;
        fc.present = true;
        if (fc.last_tape_idx >= 0) {
            for (size_t i = 0; i + 1 < fc.repeated_tape_indices.size(); i++) {
                tape_[fc.repeated_tape_indices[i]].flags = FLAG_SHADOWED;
            }
            TapeEntry& e = tape_[static_cast<uint32_t>(fc.last_tape_idx)];
            if (fc.repeated_tape_indices.size() == 1U
                    && e.wire_type() == expected_wt
                    && e.value_length() == new_val_len) {
                e.flags = FLAG_DIRTY_SAME_SIZE;
                fc.dirty_mode = DirtyMode::SAME_SIZE;
                same_size_dirty_count_++;
            } else {
                e.flags = FLAG_DIRTY_VAR_SIZE;
                fc.dirty_mode = DirtyMode::VAR_SIZE;
                var_size_dirty_count_++;
            }
        } else {
            fc.dirty_mode = DirtyMode::APPENDED;
            appended_count_++;
        }
    }

    void notify_parent() noexcept {
        if (parent_ != nullptr) {
            parent_->on_child_dirty(parent_field_, parent_repeated_idx_, *this);
        }
    }

    void on_child_dirty(uint32_t field_number, int32_t rep_idx, const WireView& child) noexcept {
        int32_t s = find_slot(field_number);
        if (s < 0) return;
        FieldCache& fc = slots_[static_cast<size_t>(s)];
        if (rep_idx >= 0) {
            if (fc.state != FieldState::REPEATED_PATCHED) {
                leave_current_state(fc, false, true);
                fc.state = FieldState::REPEATED_PATCHED;
                fc.present = true;
            }
            uint32_t t_idx = fc.repeated_tape_indices[static_cast<size_t>(rep_idx)];
            TapeEntry& e = tape_[t_idx];
            uint8_t prev = e.flags;
            if (prev == FLAG_DIRTY_SAME_SIZE) same_size_dirty_count_--;
            else if (prev == FLAG_DIRTY_VAR_SIZE) var_size_dirty_count_--;
            else fc.rep_dirty_count++;
            if (child.is_same_size_dirty_only()) {
                e.flags = FLAG_DIRTY_SAME_SIZE;
                same_size_dirty_count_++;
            } else {
                e.flags = FLAG_DIRTY_VAR_SIZE;
                var_size_dirty_count_++;
            }
        } else {
            leave_current_state(fc, true, false);
            fc.state = FieldState::SUBVIEW_OVERRIDE;
            fc.kind = CacheKind::SUBVIEW;
            fc.decoded = true;
            fc.present = true;
            if (!fc.repeated_tape_indices.empty()) {
                for (size_t i = 0; i + 1 < fc.repeated_tape_indices.size(); i++) {
                    tape_[fc.repeated_tape_indices[i]].flags = FLAG_SHADOWED;
                }
                TapeEntry& tail = tape_[fc.repeated_tape_indices.back()];
                if (fc.repeated_tape_indices.size() == 1U && child.is_same_size_dirty_only()) {
                    tail.flags = FLAG_DIRTY_SAME_SIZE;
                    fc.dirty_mode = DirtyMode::SAME_SIZE;
                    same_size_dirty_count_++;
                } else {
                    tail.flags = FLAG_DIRTY_VAR_SIZE;
                    fc.dirty_mode = DirtyMode::VAR_SIZE;
                    var_size_dirty_count_++;
                }
            } else {
                fc.dirty_mode = DirtyMode::APPENDED;
                appended_count_++;
            }
        }
        notify_parent();
    }

    void apply_in_place_patches(uint8_t* target) const noexcept {
        for (size_t si = 0; si < slot_count_; si++) {
            const FieldCache& fc = slots_[si];
            switch (fc.state) {
                case FieldState::SUBVIEW_OVERRIDE:
                    if (fc.dirty_mode == DirtyMode::SAME_SIZE && fc.last_tape_idx >= 0 && fc.child_pool_idx != UINT32_MAX) {
                        const TapeEntry& e = tape_[static_cast<uint32_t>(fc.last_tape_idx)];
                        child_pool_[fc.child_pool_idx]->apply_in_place_patches(target + e.value_offset());
                    }
                    break;
                case FieldState::SCALAR_OVERRIDE:
                    if (fc.dirty_mode == DirtyMode::SAME_SIZE && fc.last_tape_idx >= 0) {
                        const TapeEntry& e = tape_[static_cast<uint32_t>(fc.last_tape_idx)];
                        uint8_t* dst = target + e.value_offset();
                        if (fc.kind == CacheKind::VARINT) WireWriter::patch_varint64_at(dst, fc.num_val);
                        else if (fc.kind == CacheKind::FIXED32) WireWriter::patch_fixed32_at(dst, static_cast<uint32_t>(fc.num_val));
                        else if (fc.kind == CacheKind::FIXED64) WireWriter::patch_fixed64_at(dst, fc.num_val);
                        else if (fc.kind == CacheKind::BYTES) {
                            std::string_view sv = fc.bytes_in_arena ? arena_.resolve(fc.arena_ref) : fc.bytes_val;
                            if (!sv.empty()) std::memcpy(dst, sv.data(), sv.size());
                        }
                    }
                    break;
                case FieldState::REPEATED_PATCHED:
                    for (size_t i = 0; i < fc.repeated_child_pool_indices.size(); i++) {
                        uint32_t pidx = fc.repeated_child_pool_indices[i];
                        if (pidx != UINT32_MAX) {
                            const TapeEntry& e = tape_[fc.repeated_tape_indices[i]];
                            if (e.flags == FLAG_DIRTY_SAME_SIZE) {
                                child_pool_[pidx]->apply_in_place_patches(target + e.value_offset());
                            }
                        }
                    }
                    break;
                case FieldState::RAW_CLEAN:
                case FieldState::DELETED:
                    break;
            }
        }
    }

    void write_field_value(const FieldCache& fc, WireWriter& w) const noexcept {
        if (fc.state == FieldState::SUBVIEW_OVERRIDE && fc.child_pool_idx != UINT32_MAX) {
            const WireView* child = child_pool_[fc.child_pool_idx].get();
            write_submessage_single_pass(fc.field_number, w, [child](WireWriter& cw) {
                child->write_spliced_to(cw);
            });
            return;
        }
        switch (fc.kind) {
            case CacheKind::VARINT:
                w.write_tag(fc.field_number, WIRETYPE_VARINT);
                w.write_varint64(fc.num_val);
                break;
            case CacheKind::FIXED32:
                w.write_tag(fc.field_number, WIRETYPE_FIXED32);
                w.write_fixed32(static_cast<uint32_t>(fc.num_val));
                break;
            case CacheKind::FIXED64:
                w.write_tag(fc.field_number, WIRETYPE_FIXED64);
                w.write_fixed64(fc.num_val);
                break;
            case CacheKind::BYTES: {
                std::string_view sv = fc.bytes_in_arena ? arena_.resolve(fc.arena_ref) : fc.bytes_val;
                w.write_len_delimited(fc.field_number, sv);
                break;
            }
            case CacheKind::SUBVIEW:
                if (fc.child_pool_idx != UINT32_MAX) {
                    const WireView* child = child_pool_[fc.child_pool_idx].get();
                    write_submessage_single_pass(fc.field_number, w, [child](WireWriter& cw) {
                        child->write_spliced_to(cw);
                    });
                }
                break;
            case CacheKind::NONE:
                break;
        }
    }

    void write_spliced_to(WireWriter& w) const noexcept {
        if (is_self_clean()) {
            if (raw_len_ > 0) w.write_raw(buf_, raw_len_);
            return;
        }
        if (is_self_same_size_dirty_only()) {
            uint8_t* start = w.ptr;
            if (raw_len_ > 0) w.write_raw(buf_, raw_len_);
            apply_in_place_patches(start);
            return;
        }
        write_spliced_tape(
                buf_, tape_, w,
                [this](uint32_t tape_idx, const TapeEntry& e, WireWriter& dw) {
                    int32_t s = find_slot(e.field_number());
                    if (s < 0) return;
                    const FieldCache& fc = slots_[static_cast<size_t>(s)];
                    if (fc.state == FieldState::REPEATED_PATCHED && e.wire_type() == WIRETYPE_LENGTH_DELIMITED) {
                        for (size_t i = 0; i < fc.repeated_tape_indices.size(); i++) {
                            if (fc.repeated_tape_indices[i] == tape_idx) {
                                uint32_t pidx = fc.repeated_child_pool_indices[i];
                                if (pidx != UINT32_MAX) {
                                    const WireView* child = child_pool_[pidx].get();
                                    write_submessage_single_pass(e.field_number(), dw, [child](WireWriter& cw) {
                                        child->write_spliced_to(cw);
                                    });
                                    return;
                                }
                            }
                        }
                    }
                    write_field_value(fc, dw);
                },
                [this](WireWriter& aw) {
                    if (appended_count_ == 0) return;
                    for (size_t si = 0; si < slot_count_; si++) {
                        const FieldCache& fc = slots_[si];
                        if (fc.dirty_mode == DirtyMode::APPENDED && fc.present && fc.state != FieldState::DELETED) {
                            write_field_value(fc, aw);
                        }
                    }
                });
    }
};

} // namespace lazyproto
