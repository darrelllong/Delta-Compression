#include "test_harness.h"
#include <delta/splay.h>

#include <string>
#include <utility>

using delta::SplayTree;

TEST_CASE("splay find on empty tree", "[splay]") {
    SplayTree<int> t;
    CHECK(t.empty());
    CHECK(t.find(1) == nullptr);
}

TEST_CASE("splay insert replaces, insert_or_get keeps", "[splay]") {
    SplayTree<std::string> t;
    t.insert(7, "a");
    t.insert(7, "b");
    CHECK(t.size() == 1);
    REQUIRE(t.find(7) != nullptr);
    CHECK(*t.find(7) == "b");

    CHECK(t.insert_or_get(7, "c") == "b");
    CHECK(t.insert_or_get(9, "d") == "d");
    CHECK(t.size() == 2);
    CHECK(t.find(8) == nullptr);
}

TEST_CASE("splay finds every key after scattered inserts", "[splay]") {
    SplayTree<uint64_t> t;
    const uint64_t n = 5000;
    // 7919 is coprime to n, so this visits every key once, out of order.
    for (uint64_t i = 0; i < n; ++i) {
        uint64_t key = i * 7919 % n;
        t.insert(key, key * 2);
    }
    REQUIRE(t.size() == n);
    for (uint64_t key = 0; key < n; ++key) {
        uint64_t* v = t.find(key);
        REQUIRE(v != nullptr);
        REQUIRE(*v == key * 2);
    }
    CHECK(t.find(n) == nullptr);
}

TEST_CASE("splay tree of one long chain can be destroyed", "[splay]") {
    // Keys inserted in increasing order leave a tree as deep as it is large.
    SplayTree<int> t;
    for (uint64_t key = 0; key < (1u << 20); ++key) t.insert(key, 0);
    CHECK(t.size() == (1u << 20));
    t.clear();
    CHECK(t.empty());
    CHECK(t.find(0) == nullptr);
}

TEST_CASE("splay move leaves the source empty", "[splay]") {
    SplayTree<int> a;
    a.insert(1, 10);
    SplayTree<int> b = std::move(a);
    CHECK(a.empty());
    REQUIRE(b.find(1) != nullptr);
    CHECK(*b.find(1) == 10);
}
