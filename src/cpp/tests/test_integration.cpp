#include <catch2/catch_test_macros.hpp>
#include <delta/delta.h>

#include <algorithm>
#include <numeric>
#include <random>
#include <vector>

using namespace delta;

// ── helpers ──────────────────────────────────────────────────────────────

using DiffFn = std::vector<Command>(*)(
    std::span<const uint8_t>, std::span<const uint8_t>,
    size_t, size_t, bool, bool);

static std::vector<uint8_t> roundtrip(DiffFn algo_fn,
    std::span<const uint8_t> r, std::span<const uint8_t> v, size_t p) {
    auto cmds = algo_fn(r, v, p, TABLE_SIZE, false, false);
    auto placed = place_commands(cmds);
    auto delta_bytes = encode_delta(placed, false, output_size(cmds));
    auto [placed2, ip, vs] = decode_delta(delta_bytes);
    std::vector<uint8_t> out(v.size(), 0);
    apply_placed_to(r, placed2, out);
    return out;
}

static std::vector<uint8_t> inplace_roundtrip(DiffFn algo_fn,
    std::span<const uint8_t> r, std::span<const uint8_t> v,
    CyclePolicy policy, size_t p) {
    auto cmds = algo_fn(r, v, p, TABLE_SIZE, false, false);
    auto ip = make_inplace(r, cmds, policy);
    return apply_delta_inplace(r, ip, v.size());
}

static std::vector<uint8_t> inplace_binary_roundtrip(DiffFn algo_fn,
    std::span<const uint8_t> r, std::span<const uint8_t> v,
    CyclePolicy policy, size_t p) {
    auto cmds = algo_fn(r, v, p, TABLE_SIZE, false, false);
    auto ip = make_inplace(r, cmds, policy);
    auto delta_bytes = encode_delta(ip, true, v.size());
    auto [ip2, is_ip, vs] = decode_delta(delta_bytes);
    return apply_delta_inplace(r, ip2, vs);
}

// Correcting wrapper to match DiffFn signature
static std::vector<Command> correcting_wrapper(
    std::span<const uint8_t> r, std::span<const uint8_t> v,
    size_t p, size_t q, bool /*verbose*/, bool use_splay) {
    return diff_correcting(r, v, p, q, 256, false, use_splay);
}

struct AlgoEntry { const char* name; DiffFn fn; };

static std::vector<AlgoEntry> all_algos() {
    return {
        {"greedy", diff_greedy},
        {"onepass", diff_onepass},
        {"correcting", correcting_wrapper},
    };
}

static std::vector<CyclePolicy> all_policies() {
    return {CyclePolicy::Constant, CyclePolicy::Localmin};
}

static std::vector<uint8_t> repeat(std::span<const uint8_t> base, size_t count) {
    std::vector<uint8_t> out;
    out.reserve(base.size() * count);
    for (size_t i = 0; i < count; ++i)
        out.insert(out.end(), base.begin(), base.end());
    return out;
}

// ── standard differencing ────────────────────────────────────────────────

TEST_CASE("paper example (Section 2.1.1)", "[integration]") {
    std::vector<uint8_t> r = {'A','B','C','D','E','F','G','H',
                              'I','J','K','L','M','N','O','P'};
    std::vector<uint8_t> v = {'Q','W','I','J','K','L','M','N','O',
                              'B','C','D','E','F','G','H',
                              'Z','D','E','F','G','H','I','J','K','L'};
    for (auto& [name, algo] : all_algos()) {
        auto result = apply_delta(r, algo(r, v, 2, TABLE_SIZE, false, false));
        REQUIRE(result == v);
    }
}

TEST_CASE("identical strings produce only copies", "[integration]") {
    std::vector<uint8_t> base = {'T','h','e',' ','q','u','i','c','k',' ',
                                 'b','r','o','w','n',' ','f','o','x',' ',
                                 'j','u','m','p','s',' ','o','v','e','r',
                                 ' ','t','h','e',' ','l','a','z','y',' ',
                                 'd','o','g','.'};
    auto data = repeat(base, 10);
    for (auto& [name, algo] : all_algos()) {
        auto cmds = algo(data, data, 2, TABLE_SIZE, false, false);
        auto result = apply_delta(data, cmds);
        REQUIRE(result == data);
        for (const auto& cmd : cmds) {
            REQUIRE(std::holds_alternative<CopyCmd>(cmd));
        }
    }
}

TEST_CASE("completely different strings", "[integration]") {
    std::vector<uint8_t> r(512), v(512);
    std::iota(r.begin(), r.end(), 0);
    // Reverse
    std::copy(r.rbegin(), r.rend(), v.begin());
    for (auto& [name, algo] : all_algos()) {
        auto result = apply_delta(r, algo(r, v, 2, TABLE_SIZE, false, false));
        REQUIRE(result == v);
    }
}

TEST_CASE("empty version", "[integration]") {
    std::vector<uint8_t> r = {'h','e','l','l','o'};
    std::vector<uint8_t> v;
    for (auto& [name, algo] : all_algos()) {
        auto cmds = algo(r, v, 2, TABLE_SIZE, false, false);
        REQUIRE(cmds.empty());
        REQUIRE(apply_delta(r, cmds).empty());
    }
}

TEST_CASE("empty reference", "[integration]") {
    std::vector<uint8_t> r;
    std::vector<uint8_t> v = {'h','e','l','l','o',' ','w','o','r','l','d'};
    for (auto& [name, algo] : all_algos()) {
        auto result = apply_delta(r, algo(r, v, 2, TABLE_SIZE, false, false));
        REQUIRE(result == v);
    }
}

TEST_CASE("binary roundtrip", "[integration]") {
    std::vector<uint8_t> base_r = {'A','B','C','D','E','F','G','H','I','J',
                                   'K','L','M','N','O','P','Q','R','S','T',
                                   'U','V','W','X','Y','Z'};
    auto r = repeat(base_r, 100);
    std::vector<uint8_t> base_v = {'0','1','2','3','E','F','G','H','I','J',
                                   'K','L','M','N','O','P','Q','R','S',
                                   '4','5','6','A','B','C','D','E','F','G',
                                   'H','I','J','K','L','7','8','9'};
    auto v = repeat(base_v, 100);
    for (auto& [name, algo] : all_algos()) {
        REQUIRE(roundtrip(algo, r, v, 4) == v);
    }
}

TEST_CASE("binary encoding roundtrip", "[integration]") {
    std::vector<PlacedCommand> placed = {
        PlacedAdd{0, {100, 101, 102}},
        PlacedCopy{888, 3, 488},
    };
    auto encoded = encode_delta(placed, false, 491);
    auto [decoded, is_ip, vs] = decode_delta(encoded);
    CHECK_FALSE(is_ip);
    CHECK(vs == 491);
    REQUIRE(decoded == placed);
}

TEST_CASE("binary encoding inplace flag", "[integration]") {
    std::vector<PlacedCommand> placed = {PlacedCopy{0, 10, 5}};
    auto standard = encode_delta(placed, false, 15);
    auto inplace = encode_delta(placed, true, 15);

    CHECK_FALSE(is_inplace_delta(standard));
    CHECK(is_inplace_delta(inplace));

    auto [d1, ip1, vs1] = decode_delta(standard);
    auto [d2, ip2, vs2] = decode_delta(inplace);
    CHECK_FALSE(ip1);
    CHECK(ip2);
    CHECK(vs1 == vs2);
    REQUIRE(d1 == d2);
}

TEST_CASE("large copy roundtrip", "[integration]") {
    std::vector<PlacedCommand> placed = {PlacedCopy{100000, 0, 50000}};
    auto encoded = encode_delta(placed, false, 50000);
    auto [decoded, ip, vs] = decode_delta(encoded);
    REQUIRE(decoded.size() == 1);
    auto* c = std::get_if<PlacedCopy>(&decoded[0]);
    REQUIRE(c != nullptr);
    CHECK(c->src == 100000);
    CHECK(c->dst == 0);
    CHECK(c->length == 50000);
}

TEST_CASE("large add roundtrip", "[integration]") {
    std::vector<uint8_t> big_data(1024);
    std::iota(big_data.begin(), big_data.end(), 0);
    std::vector<PlacedCommand> placed = {PlacedAdd{0, big_data}};
    auto encoded = encode_delta(placed, false, big_data.size());
    auto [decoded, ip, vs] = decode_delta(encoded);
    REQUIRE(decoded.size() == 1);
    auto* a = std::get_if<PlacedAdd>(&decoded[0]);
    REQUIRE(a != nullptr);
    CHECK(a->dst == 0);
    REQUIRE(a->data == big_data);
}

TEST_CASE("backward extension", "[integration]") {
    std::vector<uint8_t> block_base = {'A','B','C','D','E','F','G','H',
                                       'I','J','K','L','M','N','O','P'};
    auto block = repeat(block_base, 20);
    std::vector<uint8_t> r = {'_','_','_','_'};
    r.insert(r.end(), block.begin(), block.end());
    r.insert(r.end(), {'_','_','_','_'});
    std::vector<uint8_t> v = {'*','*'};
    v.insert(v.end(), block.begin(), block.end());
    v.insert(v.end(), {'*','*'});
    for (auto& [name, algo] : all_algos()) {
        auto result = apply_delta(r, algo(r, v, 4, TABLE_SIZE, false, false));
        REQUIRE(result == v);
    }
}

TEST_CASE("transposition", "[integration]") {
    std::vector<uint8_t> x_base = {'F','I','R','S','T','_','B','L','O','C',
                                   'K','_','D','A','T','A','_'};
    std::vector<uint8_t> y_base = {'S','E','C','O','N','D','_','B','L','O',
                                   'C','K','_','D','A','T','A'};
    auto x = repeat(x_base, 10);
    auto y = repeat(y_base, 10);
    std::vector<uint8_t> r = x;
    r.insert(r.end(), y.begin(), y.end());
    std::vector<uint8_t> v = y;
    v.insert(v.end(), x.begin(), x.end());
    for (auto& [name, algo] : all_algos()) {
        auto result = apply_delta(r, algo(r, v, 4, TABLE_SIZE, false, false));
        REQUIRE(result == v);
    }
}

TEST_CASE("scattered modifications", "[integration]") {
    std::mt19937 rng(42);
    std::vector<uint8_t> r(2000);
    for (auto& b : r) b = rng() & 0xFF;
    auto v = r;
    std::uniform_int_distribution<size_t> dist(0, v.size() - 1);
    for (int i = 0; i < 100; ++i) {
        v[dist(rng)] = rng() & 0xFF;
    }
    for (auto& [name, algo] : all_algos()) {
        REQUIRE(roundtrip(algo, r, v, 4) == v);
    }
}

// ── in-place basics ──────────────────────────────────────────────────────

TEST_CASE("inplace paper example", "[inplace]") {
    std::vector<uint8_t> r = {'A','B','C','D','E','F','G','H',
                              'I','J','K','L','M','N','O','P'};
    std::vector<uint8_t> v = {'Q','W','I','J','K','L','M','N','O',
                              'B','C','D','E','F','G','H',
                              'Z','D','E','F','G','H','I','J','K','L'};
    for (auto& [name, algo] : all_algos()) {
        for (auto pol : all_policies()) {
            REQUIRE(inplace_roundtrip(algo, r, v, pol, 2) == v);
        }
    }
}

TEST_CASE("inplace binary roundtrip", "[inplace]") {
    std::vector<uint8_t> base_r = {'A','B','C','D','E','F','G','H','I','J',
                                   'K','L','M','N','O','P','Q','R','S','T',
                                   'U','V','W','X','Y','Z'};
    auto r = repeat(base_r, 100);
    std::vector<uint8_t> base_v = {'0','1','2','3','E','F','G','H','I','J',
                                   'K','L','M','N','O','P','Q','R','S',
                                   '4','5','6','A','B','C','D','E','F','G',
                                   'H','I','J','K','L','7','8','9'};
    auto v = repeat(base_v, 100);
    for (auto& [name, algo] : all_algos()) {
        for (auto pol : all_policies()) {
            REQUIRE(inplace_binary_roundtrip(algo, r, v, pol, 4) == v);
        }
    }
}

TEST_CASE("inplace simple transposition", "[inplace]") {
    std::vector<uint8_t> x_base = {'F','I','R','S','T','_','B','L','O','C',
                                   'K','_','D','A','T','A','_'};
    std::vector<uint8_t> y_base = {'S','E','C','O','N','D','_','B','L','O',
                                   'C','K','_','D','A','T','A'};
    auto x = repeat(x_base, 20);
    auto y = repeat(y_base, 20);
    std::vector<uint8_t> r = x;
    r.insert(r.end(), y.begin(), y.end());
    std::vector<uint8_t> v = y;
    v.insert(v.end(), x.begin(), x.end());
    for (auto& [name, algo] : all_algos()) {
        for (auto pol : all_policies()) {
            REQUIRE(inplace_roundtrip(algo, r, v, pol, 4) == v);
        }
    }
}

TEST_CASE("inplace version larger", "[inplace]") {
    std::vector<uint8_t> r_base = {'A','B','C','D','E','F','G','H'};
    auto r = repeat(r_base, 50);
    std::vector<uint8_t> v_base = {'X','X','A','B','C','D','E','F','G','H'};
    auto v = repeat(v_base, 50);
    std::vector<uint8_t> extra_base = {'Y','Y','A','B','C','D','E','F','G','H'};
    auto extra = repeat(extra_base, 50);
    v.insert(v.end(), extra.begin(), extra.end());
    for (auto& [name, algo] : all_algos()) {
        for (auto pol : all_policies()) {
            REQUIRE(inplace_roundtrip(algo, r, v, pol, 4) == v);
        }
    }
}

TEST_CASE("inplace version smaller", "[inplace]") {
    std::vector<uint8_t> r_base = {'A','B','C','D','E','F','G','H',
                                   'I','J','K','L','M','N','O','P'};
    auto r = repeat(r_base, 100);
    std::vector<uint8_t> v_base = {'E','F','G','H','I','J','K','L'};
    auto v = repeat(v_base, 50);
    for (auto& [name, algo] : all_algos()) {
        for (auto pol : all_policies()) {
            REQUIRE(inplace_roundtrip(algo, r, v, pol, 4) == v);
        }
    }
}

TEST_CASE("inplace identical", "[inplace]") {
    std::vector<uint8_t> base = {'T','h','e',' ','q','u','i','c','k',' ',
                                 'b','r','o','w','n',' ','f','o','x',' ',
                                 'j','u','m','p','s',' ','o','v','e','r',
                                 ' ','t','h','e',' ','l','a','z','y',' ',
                                 'd','o','g','.'};
    auto data = repeat(base, 10);
    for (auto& [name, algo] : all_algos()) {
        for (auto pol : all_policies()) {
            REQUIRE(inplace_roundtrip(algo, data, data, pol, 2) == data);
        }
    }
}

TEST_CASE("inplace empty version", "[inplace]") {
    std::vector<uint8_t> r = {'h','e','l','l','o'};
    std::vector<uint8_t> v;
    for (auto& [name, algo] : all_algos()) {
        auto cmds = algo(r, v, 2, TABLE_SIZE, false, false);
        auto ip = make_inplace(r, cmds, CyclePolicy::Localmin);
        REQUIRE(apply_delta_inplace(r, ip, 0).empty());
    }
}

TEST_CASE("inplace scattered", "[inplace]") {
    std::mt19937 rng(99);
    std::vector<uint8_t> r(2000);
    for (auto& b : r) b = rng() & 0xFF;
    auto v = r;
    std::uniform_int_distribution<size_t> dist(0, v.size() - 1);
    for (int i = 0; i < 100; ++i) {
        v[dist(rng)] = rng() & 0xFF;
    }
    for (auto& [name, algo] : all_algos()) {
        for (auto pol : all_policies()) {
            REQUIRE(inplace_binary_roundtrip(algo, r, v, pol, 4) == v);
        }
    }
}

TEST_CASE("standard not detected as inplace", "[inplace]") {
    std::vector<uint8_t> r_base = {'A','B','C','D','E','F','G','H'};
    auto r = repeat(r_base, 10);
    std::vector<uint8_t> v_base = {'E','F','G','H','A','B','C','D'};
    auto v = repeat(v_base, 10);
    auto cmds = diff_greedy(r, v, 2, TABLE_SIZE, false, false);
    auto placed = place_commands(cmds);
    auto delta_bytes = encode_delta(placed, false, v.size());
    CHECK_FALSE(is_inplace_delta(delta_bytes));
}

TEST_CASE("inplace detected", "[inplace]") {
    std::vector<uint8_t> r_base = {'A','B','C','D','E','F','G','H'};
    auto r = repeat(r_base, 10);
    std::vector<uint8_t> v_base = {'E','F','G','H','A','B','C','D'};
    auto v = repeat(v_base, 10);
    auto cmds = diff_greedy(r, v, 2, TABLE_SIZE, false, false);
    auto ip = make_inplace(r, cmds, CyclePolicy::Localmin);
    auto delta_bytes = encode_delta(ip, true, v.size());
    CHECK(is_inplace_delta(delta_bytes));
}

// ── variable-length block tests ──────────────────────────────────────────

static std::vector<std::vector<uint8_t>> make_blocks() {
    std::vector<size_t> sizes = {200, 500, 1234, 3000, 800, 4999, 1500, 2750};
    std::vector<std::vector<uint8_t>> blocks;
    for (size_t bi = 0; bi < sizes.size(); ++bi) {
        std::vector<uint8_t> block(sizes[bi]);
        for (size_t j = 0; j < sizes[bi]; ++j) {
            block[j] = static_cast<uint8_t>((bi * 37 + j) & 0xFF);
        }
        blocks.push_back(std::move(block));
    }
    return blocks;
}

static std::vector<uint8_t> blocks_ref(const std::vector<std::vector<uint8_t>>& blocks) {
    std::vector<uint8_t> out;
    for (const auto& b : blocks) out.insert(out.end(), b.begin(), b.end());
    return out;
}

TEST_CASE("inplace varlen permutation", "[inplace]") {
    auto blocks = make_blocks();
    auto r = blocks_ref(blocks);
    std::mt19937 rng(2003);
    std::vector<size_t> perm = {0,1,2,3,4,5,6,7};
    std::shuffle(perm.begin(), perm.end(), rng);
    std::vector<uint8_t> v;
    for (auto i : perm) v.insert(v.end(), blocks[i].begin(), blocks[i].end());
    for (auto& [name, algo] : all_algos()) {
        for (auto pol : all_policies()) {
            REQUIRE(inplace_roundtrip(algo, r, v, pol, 4) == v);
        }
    }
}

TEST_CASE("inplace varlen reverse", "[inplace]") {
    auto blocks = make_blocks();
    auto r = blocks_ref(blocks);
    std::vector<uint8_t> v;
    for (auto it = blocks.rbegin(); it != blocks.rend(); ++it)
        v.insert(v.end(), it->begin(), it->end());
    for (auto& [name, algo] : all_algos()) {
        for (auto pol : all_policies()) {
            REQUIRE(inplace_roundtrip(algo, r, v, pol, 4) == v);
        }
    }
}

TEST_CASE("inplace varlen junk", "[inplace]") {
    auto blocks = make_blocks();
    auto r = blocks_ref(blocks);
    std::mt19937 rng(20030);
    std::vector<uint8_t> junk(300);
    for (auto& b : junk) b = rng() & 0xFF;
    std::vector<size_t> perm = {0,1,2,3,4,5,6,7};
    std::shuffle(perm.begin(), perm.end(), rng);
    std::vector<uint8_t> v;
    std::uniform_int_distribution<size_t> jdist(50, 300);
    for (auto i : perm) {
        v.insert(v.end(), blocks[i].begin(), blocks[i].end());
        size_t jlen = std::min(jdist(rng), junk.size());
        v.insert(v.end(), junk.begin(), junk.begin() + jlen);
    }
    for (auto& [name, algo] : all_algos()) {
        for (auto pol : all_policies()) {
            REQUIRE(inplace_roundtrip(algo, r, v, pol, 4) == v);
        }
    }
}

TEST_CASE("inplace varlen drop dup", "[inplace]") {
    auto blocks = make_blocks();
    auto r = blocks_ref(blocks);
    std::vector<uint8_t> v;
    for (auto i : {3, 0, 0, 5, 3})
        v.insert(v.end(), blocks[i].begin(), blocks[i].end());
    for (auto& [name, algo] : all_algos()) {
        for (auto pol : all_policies()) {
            REQUIRE(inplace_roundtrip(algo, r, v, pol, 4) == v);
        }
    }
}

TEST_CASE("inplace varlen double sized", "[inplace]") {
    auto blocks = make_blocks();
    auto r = blocks_ref(blocks);
    std::mt19937 rng(7001);
    std::vector<size_t> p1 = {0,1,2,3,4,5,6,7};
    std::shuffle(p1.begin(), p1.end(), rng);
    std::vector<size_t> p2 = {0,1,2,3,4,5,6,7};
    std::shuffle(p2.begin(), p2.end(), rng);
    std::vector<uint8_t> v;
    for (auto i : p1) v.insert(v.end(), blocks[i].begin(), blocks[i].end());
    for (auto i : p2) v.insert(v.end(), blocks[i].begin(), blocks[i].end());
    for (auto& [name, algo] : all_algos()) {
        for (auto pol : all_policies()) {
            REQUIRE(inplace_roundtrip(algo, r, v, pol, 4) == v);
        }
    }
}

TEST_CASE("inplace varlen subset", "[inplace]") {
    auto blocks = make_blocks();
    auto r = blocks_ref(blocks);
    std::vector<uint8_t> v;
    v.insert(v.end(), blocks[6].begin(), blocks[6].end());
    v.insert(v.end(), blocks[2].begin(), blocks[2].end());
    for (auto& [name, algo] : all_algos()) {
        for (auto pol : all_policies()) {
            REQUIRE(inplace_roundtrip(algo, r, v, pol, 4) == v);
        }
    }
}

TEST_CASE("inplace varlen half block scramble", "[inplace]") {
    auto blocks = make_blocks();
    auto r = blocks_ref(blocks);
    std::vector<std::vector<uint8_t>> halves;
    for (const auto& b : blocks) {
        size_t mid = b.size() / 2;
        halves.emplace_back(b.begin(), b.begin() + mid);
        halves.emplace_back(b.begin() + mid, b.end());
    }
    std::mt19937 rng(5555);
    std::vector<size_t> perm(halves.size());
    std::iota(perm.begin(), perm.end(), 0);
    std::shuffle(perm.begin(), perm.end(), rng);
    std::vector<uint8_t> v;
    for (auto i : perm) v.insert(v.end(), halves[i].begin(), halves[i].end());
    for (auto& [name, algo] : all_algos()) {
        for (auto pol : all_policies()) {
            REQUIRE(inplace_roundtrip(algo, r, v, pol, 4) == v);
            REQUIRE(inplace_binary_roundtrip(algo, r, v, pol, 4) == v);
        }
    }
}

TEST_CASE("inplace varlen random trials", "[inplace]") {
    auto blocks = make_blocks();
    auto r = blocks_ref(blocks);
    std::mt19937 rng(9999);

    struct Trial { std::vector<size_t> indices; std::vector<uint8_t> data; };
    std::vector<Trial> trials;
    for (int t = 0; t < 20; ++t) {
        std::uniform_int_distribution<size_t> kdist(3, 8);
        size_t k = kdist(rng);
        std::vector<size_t> indices = {0,1,2,3,4,5,6,7};
        std::shuffle(indices.begin(), indices.end(), rng);
        indices.resize(k);
        std::shuffle(indices.begin(), indices.end(), rng);
        std::vector<uint8_t> v;
        for (auto i : indices) v.insert(v.end(), blocks[i].begin(), blocks[i].end());
        trials.push_back({indices, v});
    }

    for (auto& [name, algo] : all_algos()) {
        for (auto pol : all_policies()) {
            for (const auto& trial : trials) {
                REQUIRE(inplace_roundtrip(algo, r, trial.data, pol, 4) == trial.data);
            }
        }
    }
}

TEST_CASE("localmin picks smallest", "[inplace]") {
    auto blocks = make_blocks();
    auto r = blocks_ref(blocks);
    std::vector<uint8_t> v;
    for (auto it = blocks.rbegin(); it != blocks.rend(); ++it)
        v.insert(v.end(), it->begin(), it->end());

    auto cmds = diff_greedy(r, v, 4, TABLE_SIZE, false, false);
    auto ip_const = make_inplace(r, cmds, CyclePolicy::Constant);
    auto ip_lmin = make_inplace(r, cmds, CyclePolicy::Localmin);

    size_t add_const = 0, add_lmin = 0;
    for (const auto& c : ip_const)
        if (auto* a = std::get_if<PlacedAdd>(&c)) add_const += a->data.size();
    for (const auto& c : ip_lmin)
        if (auto* a = std::get_if<PlacedAdd>(&c)) add_lmin += a->data.size();
    CHECK(add_lmin <= add_const);
}

// ── checkpointing tests ─────────────────────────────────────────────────

TEST_CASE("correcting checkpointing tiny table", "[correcting]") {
    std::vector<uint8_t> base = {'A','B','C','D','E','F','G','H',
                                 'I','J','K','L','M','N','O','P'};
    auto r = repeat(base, 20); // 320 bytes
    std::vector<uint8_t> v(r.begin(), r.begin() + 160);
    v.insert(v.end(), {'X','X','X','X','Y','Y','Y','Y'});
    v.insert(v.end(), r.begin() + 160, r.end());
    auto cmds = diff_correcting(r, v, 16, 7, 256, false, false);
    auto recovered = apply_delta(r, cmds);
    REQUIRE(recovered == v);
}

TEST_CASE("correcting checkpointing various sizes", "[correcting]") {
    std::vector<uint8_t> r(2000);
    std::iota(r.begin(), r.end(), 0);
    std::vector<uint8_t> v(r.begin(), r.begin() + 500);
    v.insert(v.end(), 50, 0xFF);
    v.insert(v.end(), r.begin() + 500, r.end());
    for (size_t q : {size_t{7}, size_t{31}, size_t{101}, size_t{1009}, TABLE_SIZE}) {
        auto cmds = diff_correcting(r, v, 16, q, 256, false, false);
        auto recovered = apply_delta(r, cmds);
        REQUIRE(recovered == v);
    }
}

TEST_CASE("next_prime is prime", "[hash]") {
    CHECK(is_prime(TABLE_SIZE));
    CHECK(is_prime(next_prime(1048574)));
    CHECK(next_prime(1048573) == 1048573);
}
