#include "lazyproto/wire_view.hpp"
#include "lazyproto/order_envelope_view.hpp"
#include "fixture.hpp"

#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <google/protobuf/stubs/common.h>
#include <google/protobuf/unknown_field_set.h>
#include <limits>
#include <random>
#include <stdexcept>
#include <string>
#include <vector>

using namespace lazyproto;
using namespace stdproto;
namespace pb = lazyproto::bench;

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

        // Issue #7 Regressions:
        // (a) Default-constructed WireView has/set/merge
        {
            WireView def_view;
            LP_CHECK(!def_view.has(1));
            LP_CHECK(def_view.get_varint(1, 99) == 99);
            def_view.set_varint(1, 42);
            LP_CHECK(def_view.has(1) && def_view.get_varint(1) == 42);
            std::string def_out;
            def_view.merge_to(def_out);
            pb::OrderEnvelope pb_def;
            LP_CHECK(pb_def.ParseFromArray(def_out.data(), static_cast<int>(def_out.size())));
            LP_CHECK(pb_def.order_id() == 42);
        }

        // (b) WireView repeated_sub growth capacity (32 02 08 01 + 200B string -> 208B)
        {
            const uint8_t rep_in[] = {0x32, 0x02, 0x08, 0x01};
            WireView rv(rep_in, sizeof(rep_in));
            std::string long_sku(200, 'S');
            rv.repeated_sub(6, 0).set_bytes_copy(2, long_sku);
            size_t max_sz = rv.max_merged_size();
            std::vector<uint8_t> exact_buf(max_sz);
            size_t actual_sz = rv.merge_to(exact_buf.data(), exact_buf.size());
            LP_CHECK(actual_sz <= max_sz);
            LP_CHECK(actual_sz >= 208);
            pb::OrderEnvelope pb_rep;
            LP_CHECK(pb_rep.ParseFromArray(exact_buf.data(), static_cast<int>(actual_sz)));
            LP_CHECK(pb_rep.items_size() == 1 && pb_rep.items(0).item_id() == 1);
            LP_CHECK(pb_rep.items(0).sku() == long_sku);
        }

        // (c) WireView split sub-message (2a 02 08 01 2a 02 18 02), set_bytes -> sub, sub edit -> get_bytes
        {
            const uint8_t split_in[] = {0x2A, 0x02, 0x08, 0x01, 0x2A, 0x02, 0x18, 0x02};
            WireView sv(split_in, sizeof(split_in));
            sv.sub(5).set_varint(3, 7);
            LP_CHECK(sv.sub(5).get_varint(3) == 7);
            std::string_view sub_bytes = sv.get_bytes(5);
            LP_CHECK(!sub_bytes.empty());
            std::string out_split;
            sv.merge_to(out_split);
            pb::OrderEnvelope pb_split2;
            LP_CHECK(pb_split2.ParseFromArray(out_split.data(), static_cast<int>(out_split.size())));
            LP_CHECK(pb_split2.user().user_id() == 1);
            LP_CHECK(pb_split2.user().vip_level() == 7);

            // set_bytes -> sub() sees new bytes
            pb::UserProfile new_u;
            new_u.set_user_id(555);
            new_u.set_city("NEW_CITY");
            std::string new_u_bytes = new_u.SerializeAsString();
            sv.set_bytes_copy(5, new_u_bytes);
            LP_CHECK(sv.sub(5).get_varint(1) == 555);
            LP_CHECK(sv.sub(5).get_bytes(2) == "NEW_CITY");
        }

        // (d) WireView repeated scalar preservation (08 01 08 02 + add field 3 -> preserves [1, 2])
        {
            const uint8_t rep_scalar[] = {0x08, 0x01, 0x08, 0x02};
            WireView rsv(rep_scalar, sizeof(rep_scalar));
            rsv.set_varint(3, 99);
            std::string out_rs;
            rsv.merge_to(out_rs);
            // Parse raw records: must contain field 1 = 1, field 1 = 2, and field 3 = 99
            WireView verify(reinterpret_cast<const uint8_t*>(out_rs.data()), out_rs.size());
            LP_CHECK(verify.repeated_count(1) == 2);
            LP_CHECK(verify.get_varint(3) == 99);
            // Verify wire starts with 0x08, 0x01, 0x08, 0x02
            LP_CHECK(out_rs.size() >= 6);
            LP_CHECK(static_cast<uint8_t>(out_rs[0]) == 0x08 && static_cast<uint8_t>(out_rs[1]) == 0x01);
            LP_CHECK(static_cast<uint8_t>(out_rs[2]) == 0x08 && static_cast<uint8_t>(out_rs[3]) == 0x02);
        }

        // (e) OrderEnvelopeView multi-round wrap reuse across different inputs & A5 parity
        {
            OrderEnvelopeView reused;
            std::vector<uint8_t> alt = create_complex_order_bytes(60, 128);
            for (int r = 0; r < 3; r++) {
                reused.wrap(raw.data(), 0, static_cast<uint32_t>(raw.size()));
                reused.items(42).set_quantity(999).set_description("UPDATED_ITEM_42_DESC");
                std::string m;
                reused.merge_to(m);
                pb::OrderEnvelope p;
                LP_CHECK(p.ParseFromArray(m.data(), static_cast<int>(m.size())));
                LP_CHECK(p.items_size() == 100);
                LP_CHECK(p.items(42).quantity() == 999);
                LP_CHECK(p.items(42).description() == "UPDATED_ITEM_42_DESC");

                reused.wrap(alt.data(), 0, static_cast<uint32_t>(alt.size()));
                LP_CHECK(reused.items_count() == 60);
                LP_CHECK(reused.items(42).quantity() == (42 % 5) + 1);
            }
        }

        // Issue #8 (f): sub.wrap() / repeated_sub.wrap() without calling any child getter
        {
            const uint8_t p_in[] = {0x2A, 0x02, 0x08, 0x01};
            WireView wv(p_in, sizeof(p_in));
            const uint8_t u2[] = {0x08, 0x02};
            wv.sub(5).wrap(u2, sizeof(u2));
            std::string out_u;
            wv.merge_to(out_u);
            LP_CHECK(out_u.size() == 4);
            LP_CHECK(static_cast<uint8_t>(out_u[0]) == 0x2A && static_cast<uint8_t>(out_u[1]) == 0x02
                  && static_cast<uint8_t>(out_u[2]) == 0x08 && static_cast<uint8_t>(out_u[3]) == 0x02);
            pb::OrderEnvelope pb_u;
            LP_CHECK(pb_u.ParseFromArray(out_u.data(), static_cast<int>(out_u.size())));
            LP_CHECK(pb_u.user().user_id() == 2);

            const uint8_t rep_in[] = {0x32, 0x02, 0x18, 0x01};
            WireView r_wv(rep_in, sizeof(rep_in));
            const uint8_t q9[] = {0x18, 0x09};
            r_wv.repeated_sub(6, 0).wrap(q9, sizeof(q9));
            std::string out_r;
            r_wv.merge_to(out_r);
            LP_CHECK(out_r.size() == 4);
            LP_CHECK(static_cast<uint8_t>(out_r[0]) == 0x32 && static_cast<uint8_t>(out_r[1]) == 0x02
                  && static_cast<uint8_t>(out_r[2]) == 0x18 && static_cast<uint8_t>(out_r[3]) == 0x09);
            pb::OrderEnvelope pb_r;
            LP_CHECK(pb_r.ParseFromArray(out_r.data(), static_cast<int>(out_r.size())));
            LP_CHECK(pb_r.items_size() == 1 && pb_r.items(0).quantity() == 9);
        }
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

    // 8. Phase-0 Regressions (29-bit field numbers, negative int32, FP1 repCount guard, counter reclaim, detach, eager bounds)
    {
        // (a) 29-bit field numbers (>65535) must not truncate/collide with field #1
        {
            std::vector<uint8_t> buf(128, 0);
            WireWriter ww(buf.data());
            ww.write_tag(1, WIRETYPE_VARINT); ww.write_varint64(12345ULL);
            ww.write_tag(65537U, WIRETYPE_VARINT); ww.write_varint64(99999ULL); // 65537 & 0xFFFF == 1!
            ww.write_tag(536870911U, WIRETYPE_LENGTH_DELIMITED); ww.write_varint32(3); ww.write_raw(reinterpret_cast<const uint8_t*>("MAX"), 3);
            buf.resize(ww.size());

            OrderEnvelopeView ov;
            ov.wrap(buf.data(), 0, static_cast<uint32_t>(buf.size()));
            LP_CHECK(ov.order_id() == 12345ULL);
            ov.set_status(7);
            std::string merged;
            ov.merge_to(merged);
            pb::OrderEnvelope dec;
            LP_CHECK(dec.ParseFromArray(merged.data(), static_cast<int>(merged.size())));
            LP_CHECK(dec.order_id() == 12345ULL);
            LP_CHECK(dec.status() == 7);
            LP_CHECK(dec.GetReflection()->GetUnknownFields(dec).field_count() == 2);

            WireView wv(buf.data(), buf.size());
            LP_CHECK(wv.get_varint(1) == 12345ULL);
            LP_CHECK(wv.get_varint(65537U) == 99999ULL);
            LP_CHECK(wv.get_bytes(536870911U) == "MAX");
            wv.set_varint(65537U, 88888ULL);
            std::string wout;
            wv.merge_to(wout);
            WireView verify(reinterpret_cast<const uint8_t*>(wout.data()), wout.size());
            LP_CHECK(verify.get_varint(1) == 12345ULL);
            LP_CHECK(verify.get_varint(65537U) == 88888ULL);
            LP_CHECK(verify.get_bytes(536870911U) == "MAX");
        }

        // (b) Negative int32 must encode as 10-byte sign-extended varint matching libprotobuf
        {
            const int32_t negs[] = {-1, -42, -128, std::numeric_limits<int32_t>::min()};
            for (int32_t v : negs) {
                pb::OrderEnvelope pb_ref;
                pb_ref.set_status(v);
                pb_ref.mutable_user()->set_vip_level(v);
                pb_ref.add_items()->set_quantity(v);
                std::string ref_bytes;
                LP_CHECK(pb_ref.SerializeToString(&ref_bytes));

                OrderEnvelopeView ov;
                ov.init_scratch();
                ov.set_status(v);
                ov.mutable_user().set_vip_level(v);
                ov.add_item().set_quantity(v);
                std::string lazy_bytes;
                ov.merge_to(lazy_bytes);
                LP_CHECK(lazy_bytes.size() == ref_bytes.size());
                pb::OrderEnvelope dec;
                LP_CHECK(dec.ParseFromArray(lazy_bytes.data(), static_cast<int>(lazy_bytes.size())));
                LP_CHECK(dec.status() == v);
                LP_CHECK(dec.user().vip_level() == v);
                LP_CHECK(dec.items_size() == 1 && dec.items(0).quantity() == v);

                // FP1 in-place patch negative -> negative (both 10 bytes)
                ov.wrap(reinterpret_cast<const uint8_t*>(ref_bytes.data()), 0, static_cast<uint32_t>(ref_bytes.size()));
                int32_t v2 = (v == -1) ? -2 : -1;
                ov.set_status(v2);
                LP_CHECK(ov.is_same_size_dirty_only());
                std::string patched;
                ov.merge_to(patched);
                LP_CHECK(patched.size() == ref_bytes.size());
                LP_CHECK(dec.ParseFromArray(patched.data(), static_cast<int>(patched.size())));
                LP_CHECK(dec.status() == v2);

                // WireView set_int32 negative -> 1-byte tag + 10-byte varint
                WireView wv;
                wv.set_int32(3, v);
                std::string w_out;
                wv.merge_to(w_out);
                LP_CHECK(w_out.size() == 11U);
                LP_CHECK(dec.ParseFromArray(w_out.data(), static_cast<int>(w_out.size())));
                LP_CHECK(dec.status() == v);
            }
        }

        // (c) WireView split sub-message + same-size set_bytes must drop shadowed segment 0 (no FP1 leak)
        {
            const uint8_t split_in[] = {0x2A, 0x02, 0x08, 0x01, 0x2A, 0x02, 0x18, 0x02};
            WireView wv(split_in, sizeof(split_in));
            const char repl[2] = {0x18, 0x09};
            wv.set_bytes(5, std::string_view(repl, 2));
            LP_CHECK(!wv.is_same_size_dirty_only());
            std::string out;
            wv.merge_to(out);
            LP_CHECK(out.size() == 4U);
            pb::OrderEnvelope dec;
            LP_CHECK(dec.ParseFromArray(out.data(), static_cast<int>(out.size())));
            LP_CHECK(dec.user().user_id() == 0);
            LP_CHECK(dec.user().vip_level() == 9);
        }

        // (d) WireView repeated_sub dirty-counter reclaim + detached child isolation across set_bytes/erase/wrap
        {
            const uint8_t rep_in[] = {0x32, 0x02, 0x18, 0x01};
            WireView wv(rep_in, sizeof(rep_in));
            WireView& stale_rep = wv.repeated_sub(6, 0);
            stale_rep.set_varint(3, 50000);
            LP_CHECK(!wv.is_same_size_dirty_only());

            const char same2[2] = {0x18, 0x07};
            wv.set_bytes(6, std::string_view(same2, 2));
            LP_CHECK(wv.is_same_size_dirty_only());
            stale_rep.set_varint(3, 99999); // detached child must NOT notify parent!
            LP_CHECK(wv.is_same_size_dirty_only());
            std::string out;
            wv.merge_to(out);
            LP_CHECK(out.size() == 4U && static_cast<uint8_t>(out[3]) == 0x07);

            const uint8_t u1[] = {0x2A, 0x02, 0x08, 0x01};
            const uint8_t u5[] = {0x2A, 0x02, 0x08, 0x05};
            wv.wrap(u1, sizeof(u1));
            WireView& stale_sub = wv.sub(5);
            LP_CHECK(stale_sub.get_varint(1) == 1);
            wv.wrap(u5, sizeof(u5));
            stale_sub.set_varint(1, 777); // stale child from previous wrap!
            LP_CHECK(wv.is_clean());
            wv.merge_to(out);
            pb::OrderEnvelope dec;
            LP_CHECK(dec.ParseFromArray(out.data(), static_cast<int>(out.size())));
            LP_CHECK(dec.user().user_id() == 5);
        }

        // (e) LineItemView::decode_all_eager reuse reset & bounds rejection
        {
            pb::OrderEnvelope pb_e;
            auto* it0 = pb_e.add_items();
            it0->set_item_id(10); it0->set_sku("SKU0"); it0->set_description("DESC0");
            auto* it1 = pb_e.add_items();
            it1->set_item_id(20); // empty sku & description!
            std::string wire;
            LP_CHECK(pb_e.SerializeToString(&wire));
            OrderEnvelopeView ov;
            ov.wrap(reinterpret_cast<const uint8_t*>(wire.data()), 0, static_cast<uint32_t>(wire.size()));
            LP_CHECK(ov.items_count() == 2);
            LineItemView tmp;
            ov.read_item_eager(0, tmp);
            LP_CHECK(tmp.item_id_ == 10 && tmp.sku_ == "SKU0" && tmp.description_ == "DESC0");
            ov.read_item_eager(1, tmp);
            LP_CHECK(tmp.item_id_ == 20 && tmp.sku_.empty() && tmp.description_.empty());

            // Truncated item must throw in decode_all_eager
            const uint8_t bad_item[] = {0x32, 0x03, 0x12, 0x10, 'A'};
            ov.wrap(bad_item, 0, sizeof(bad_item));
            LP_CHECK(ov.items_count() == 1);
            bool threw = false;
            try {
                ov.read_item_eager(0, tmp);
            } catch (const std::runtime_error&) {
                threw = true;
            }
            LP_CHECK(threw);

            // (f) validate_deep() succeeds on valid envelope and throws on unvisited corrupt nested user/item
            ov.wrap(raw.data(), 0, static_cast<uint32_t>(raw.size()));
            ov.validate_deep();

            const uint8_t corrupt_user[] = {0x08, 0x01, 0x2A, 0x03, 0x12, 0x10, 'A'};
            ov.wrap(corrupt_user, 0, sizeof(corrupt_user));
            LP_CHECK(ov.order_id() == 1ULL);
            std::string passthrough;
            ov.merge_to(passthrough);
            LP_CHECK(passthrough.size() == sizeof(corrupt_user));
            bool deep_threw = false;
            try {
                ov.validate_deep();
            } catch (const std::runtime_error&) {
                deep_threw = true;
            }
            LP_CHECK(deep_threw);
        }
    }

    // 9. Stateful Model-Checking Fuzzer: OrderEnvelopeView & WireView vs Official libprotobuf
    {
        auto pick_i32 = [](std::mt19937_64& rng) -> int32_t {
            switch (rng() % 6) {
                case 0: return 0;
                case 1: return static_cast<int32_t>(1 + (rng() % 126));
                case 2: return static_cast<int32_t>(128 + (rng() % 50000));
                case 3: return -1;
                case 4: return -static_cast<int32_t>(500 + (rng() % 5000));
                default: return std::numeric_limits<int32_t>::min();
            }
        };
        auto pick_u64 = [](std::mt19937_64& rng) -> uint64_t {
            switch (rng() % 5) {
                case 0: return 0ULL;
                case 1: return 1ULL + (rng() % 126);
                case 2: return 128ULL + (rng() % 16000);
                case 3: return 1000000000ULL + (rng() % 1000);
                default: return 0x7FFFFFFFFFFFFFFFULL;
            }
        };
        auto pick_str = [](std::mt19937_64& rng, std::vector<std::string>& arena_strs) -> std::string_view {
            std::string s;
            switch (rng() % 5) {
                case 0: s = ""; break;
                case 1: s = "BJ"; break;
                case 2: s = "str_" + std::to_string(rng() % 1000); break;
                case 3: s = std::string(140 + (rng() % 40), 'Z'); break;
                default: s = "alice_wonderland"; break;
            }
            arena_strs.push_back(std::move(s));
            return arena_strs.back();
        };

        std::vector<uint8_t> med = create_complex_order_bytes(16, 64);
        std::vector<uint8_t> big = create_complex_order_bytes(140, 64);
        std::vector<uint8_t> empty_v;
        const std::vector<uint8_t>* corpus[3] = {&med, &big, &empty_v};

        // 9A. OrderEnvelopeView Fuzzer (150 seeds x 35 steps)
        OrderEnvelopeView ov;
        std::vector<std::string> str_pool;
        str_pool.reserve(4096);
        for (uint64_t seed = 0; seed < 150; seed++) {
            std::mt19937_64 rng(0xA11CE000ULL + seed);
            str_pool.clear();
            const std::vector<uint8_t>& init = *corpus[rng() % 3];
            pb::OrderEnvelope oracle;
            if (rng() % 5 == 0) {
                ov.init_scratch();
            } else {
                ov.wrap(init.data(), 0, static_cast<uint32_t>(init.size()));
                LP_CHECK(oracle.ParseFromArray(init.data(), static_cast<int>(init.size())));
            }
            for (int step = 0; step < 35; step++) {
                switch (rng() % 12) {
                    case 0:
                        LP_CHECK(ov.order_id() == static_cast<uint64_t>(oracle.order_id()));
                        LP_CHECK(ov.buyer_name() == oracle.buyer_name());
                        LP_CHECK(ov.status() == oracle.status());
                        LP_CHECK(ov.items_count() == static_cast<uint32_t>(oracle.items_size()));
                        break;
                    case 1: { uint64_t v = pick_u64(rng); ov.set_order_id(v); oracle.set_order_id(static_cast<int64_t>(v)); break; }
                    case 2: { auto s = pick_str(rng, str_pool); ov.set_buyer_name(s); oracle.set_buyer_name(std::string(s)); break; }
                    case 3: { int32_t v = pick_i32(rng); ov.set_status(v); oracle.set_status(v); break; }
                    case 4: { uint64_t v = rng(); ov.set_updated_at_fixed64(v); oracle.set_updated_at_fixed64(v); break; }
                    case 5: {
                        auto s = pick_str(rng, str_pool);
                        int32_t vip = pick_i32(rng);
                        ov.mutable_user().set_city(s).set_vip_level(vip);
                        oracle.mutable_user()->set_city(std::string(s));
                        oracle.mutable_user()->set_vip_level(vip);
                        break;
                    }
                    case 6:
                    case 7: {
                        if (oracle.items_size() > 0) {
                            uint32_t idx = static_cast<uint32_t>(rng() % oracle.items_size());
                            int32_t q = pick_i32(rng);
                            auto sku = pick_str(rng, str_pool);
                            ov.items(idx).set_quantity(q).set_sku(sku);
                            oracle.mutable_items(static_cast<int>(idx))->set_quantity(q);
                            oracle.mutable_items(static_cast<int>(idx))->set_sku(std::string(sku));
                        }
                        break;
                    }
                    case 8: {
                        if (oracle.items_size() < 160) {
                            int32_t q = pick_i32(rng);
                            auto sku = pick_str(rng, str_pool);
                            ov.add_item().set_item_id(5000ULL + step).set_quantity(q).set_sku(sku);
                            auto* it = oracle.add_items();
                            it->set_item_id(5000LL + step); it->set_quantity(q); it->set_sku(std::string(sku));
                        }
                        break;
                    }
                    case 9: { ov.clear_auth_token(); oracle.clear_auth_token(); break; }
                    case 10: { auto s = pick_str(rng, str_pool); ov.set_auth_token(s); oracle.set_auth_token(std::string(s)); break; }
                    case 11: {
                        size_t max_sz = ov.max_merged_size();
                        std::vector<uint8_t> exact(max_sz);
                        size_t actual = ov.merge_to(exact.data(), exact.size());
                        LP_CHECK(actual <= max_sz);
                        pb::OrderEnvelope dec;
                        LP_CHECK(dec.ParseFromArray(exact.data(), static_cast<int>(actual)));
                        LP_CHECK(dec.order_id() == oracle.order_id());
                        LP_CHECK(dec.buyer_name() == oracle.buyer_name());
                        LP_CHECK(dec.status() == oracle.status());
                        LP_CHECK(dec.user().city() == oracle.user().city());
                        LP_CHECK(dec.user().vip_level() == oracle.user().vip_level());
                        LP_CHECK(dec.items_size() == oracle.items_size());
                        LP_CHECK(dec.auth_token() == oracle.auth_token());
                        break;
                    }
                }
            }
            size_t max_sz = ov.max_merged_size();
            std::vector<uint8_t> exact(max_sz);
            size_t actual = ov.merge_to(exact.data(), exact.size());
            LP_CHECK(actual <= max_sz);
            pb::OrderEnvelope dec;
            LP_CHECK(dec.ParseFromArray(exact.data(), static_cast<int>(actual)));
            LP_CHECK(dec.order_id() == oracle.order_id());
            LP_CHECK(dec.buyer_name() == oracle.buyer_name());
            LP_CHECK(dec.status() == oracle.status());
            LP_CHECK(dec.updated_at_fixed64() == oracle.updated_at_fixed64());
            LP_CHECK(dec.has_user() == oracle.has_user());
            LP_CHECK(dec.user().city() == oracle.user().city());
            LP_CHECK(dec.user().vip_level() == oracle.user().vip_level());
            LP_CHECK(dec.items_size() == oracle.items_size());
            for (int i = 0; i < oracle.items_size(); i++) {
                LP_CHECK(dec.items(i).item_id() == oracle.items(i).item_id());
                LP_CHECK(dec.items(i).quantity() == oracle.items(i).quantity());
                LP_CHECK(dec.items(i).sku() == oracle.items(i).sku());
            }
            LP_CHECK(dec.auth_token() == oracle.auth_token());
        }

        // 9B. WireView Fuzzer (150 seeds x 30 steps)
        WireView wv;
        for (uint64_t seed = 0; seed < 150; seed++) {
            std::mt19937_64 rng(0xB0B00000ULL + seed);
            str_pool.clear();
            const std::vector<uint8_t>& init = (seed % 3 == 0) ? empty_v : med;
            wv.wrap(init.data(), init.size());
            pb::OrderEnvelope oracle;
            LP_CHECK(oracle.ParseFromArray(init.data(), static_cast<int>(init.size())));
            std::vector<WireView*> detached;

            for (int step = 0; step < 30; step++) {
                switch (rng() % 11) {
                    case 0: {
                        LP_CHECK(wv.get_varint(1) == static_cast<uint64_t>(oracle.order_id()));
                        LP_CHECK(wv.get_bytes(2) == oracle.buyer_name());
                        LP_CHECK(wv.get_int32(3) == oracle.status());
                        break;
                    }
                    case 1: { uint64_t v = pick_u64(rng); wv.set_varint(1, v); oracle.set_order_id(static_cast<int64_t>(v)); break; }
                    case 2: { auto s = pick_str(rng, str_pool); wv.set_bytes_copy(2, s); oracle.set_buyer_name(std::string(s)); break; }
                    case 3: { int32_t v = pick_i32(rng); wv.set_int32(3, v); oracle.set_status(v); break; }
                    case 4: {
                        auto s = pick_str(rng, str_pool);
                        int32_t vip = pick_i32(rng);
                        wv.sub(5).set_bytes_copy(2, s).set_int32(3, vip);
                        oracle.mutable_user()->set_city(std::string(s));
                        oracle.mutable_user()->set_vip_level(vip);
                        break;
                    }
                    case 5: {
                        pb::UserProfile nu;
                        nu.set_user_id(100 + static_cast<int64_t>(rng() % 500));
                        nu.set_city(std::string(pick_str(rng, str_pool)));
                        std::string ub;
                        LP_CHECK(nu.SerializeToString(&ub));
                        str_pool.push_back(std::move(ub));
                        const std::string& held = str_pool.back();
                        wv.sub(5).wrap(reinterpret_cast<const uint8_t*>(held.data()), held.size());
                        *oracle.mutable_user() = nu;
                        break;
                    }
                    case 6: {
                        detached.push_back(&wv.sub(5));
                        pb::UserProfile nu;
                        nu.set_vip_level(pick_i32(rng));
                        std::string ub;
                        LP_CHECK(nu.SerializeToString(&ub));
                        wv.set_bytes_copy(5, ub);
                        *oracle.mutable_user() = nu;
                        break;
                    }
                    case 7: {
                        detached.push_back(&wv.sub(5));
                        wv.erase(5);
                        oracle.clear_user();
                        break;
                    }
                    case 8: {
                        size_t rc = wv.repeated_count(6);
                        if (rc > 0 && rc == static_cast<size_t>(oracle.items_size())) {
                            size_t idx = rng() % rc;
                            int32_t q = pick_i32(rng);
                            wv.repeated_sub(6, idx).set_int32(3, q);
                            oracle.mutable_items(static_cast<int>(idx))->set_quantity(q);
                        }
                        break;
                    }
                    case 9: {
                        if (wv.repeated_count(6) > 0) detached.push_back(&wv.repeated_sub(6, 0));
                        pb::LineItem single;
                        single.set_item_id(777);
                        single.set_quantity(pick_i32(rng));
                        std::string ib;
                        LP_CHECK(single.SerializeToString(&ib));
                        wv.set_bytes_copy(6, ib);
                        oracle.clear_items();
                        *oracle.add_items() = single;
                        break;
                    }
                    case 10: {
                        if (!detached.empty()) {
                            detached[rng() % detached.size()]->set_int32(3, 424242);
                        }
                        break;
                    }
                }
            }
            size_t max_sz = wv.max_merged_size();
            std::vector<uint8_t> exact(max_sz);
            size_t actual = wv.merge_to(exact.data(), exact.size());
            LP_CHECK(actual <= max_sz);
            pb::OrderEnvelope dec;
            LP_CHECK(dec.ParseFromArray(exact.data(), static_cast<int>(actual)));
            LP_CHECK(dec.order_id() == oracle.order_id());
            LP_CHECK(dec.buyer_name() == oracle.buyer_name());
            LP_CHECK(dec.status() == oracle.status());
            LP_CHECK(dec.has_user() == oracle.has_user());
            LP_CHECK(dec.user().user_id() == oracle.user().user_id());
            LP_CHECK(dec.user().city() == oracle.user().city());
            LP_CHECK(dec.user().vip_level() == oracle.user().vip_level());
            LP_CHECK(dec.items_size() == oracle.items_size());
            for (int i = 0; i < oracle.items_size(); i++) {
                LP_CHECK(dec.items(i).item_id() == oracle.items(i).item_id());
                LP_CHECK(dec.items(i).quantity() == oracle.items(i).quantity());
            }
        }
    }

    std::printf("[Correctness] All C++20 Lower-Level vs Official libprotobuf checks & fuzzers PASSED.\n\n");
}


int main() {
    GOOGLE_PROTOBUF_VERIFY_VERSION;
    run_correctness_tests();
    google::protobuf::ShutdownProtobufLibrary();
    return 0;
}
