#pragma once

/// A top-down splay tree keyed on fingerprints (Sleator and Tarjan,
/// "Self-Adjusting Binary Search Trees", JACM 32(3), 1985).
///
/// Every find and insert moves the node it touches to the root, so the keys
/// used most often stay near the top.  Operations are O(log n) amortized.

#include <cstddef>
#include <cstdint>
#include <utility>

namespace delta {

template <typename V>
class SplayTree {
public:
    SplayTree() = default;
    ~SplayTree() { clear(); }

    SplayTree(const SplayTree&) = delete;
    SplayTree& operator=(const SplayTree&) = delete;

    SplayTree(SplayTree&& o) noexcept
        : root_(std::exchange(o.root_, nullptr)), size_(std::exchange(o.size_, 0)) {}

    SplayTree& operator=(SplayTree&& o) noexcept {
        if (this != &o) {
            clear();
            root_ = std::exchange(o.root_, nullptr);
            size_ = std::exchange(o.size_, 0);
        }
        return *this;
    }

    /// The value stored under key, or nullptr.  Nodes are never moved or
    /// freed singly, so the pointer is valid until the tree is cleared or
    /// destroyed.
    V* find(uint64_t key) {
        if (!root_) { return nullptr; }
        splay(key);
        return root_->key == key ? &root_->value : nullptr;
    }

    /// The value stored under key, after storing value there if the key was
    /// absent.  An existing value is kept.
    V& insert_or_get(uint64_t key, V value) {
        if (root_) {
            splay(key);
            if (root_->key == key) { return root_->value; }
        }
        add_root(key, std::move(value));
        return root_->value;
    }

    /// Stores value under key, replacing any existing value.
    void insert(uint64_t key, V value) {
        if (root_) {
            splay(key);
            if (root_->key == key) {
                root_->value = std::move(value);
                return;
            }
        }
        add_root(key, std::move(value));
    }

    /// Removes every entry.
    void clear() {
        // Rotating each left child up turns the tree into a list as it is
        // freed, so no stack is needed however deep the tree is.
        for (Node* n = root_; n;) {
            if (Node* l = n->left) {
                n->left = l->right;
                l->right = n;
                n = l;
            } else {
                Node* r = n->right;
                delete n;
                n = r;
            }
        }
        root_ = nullptr;
        size_ = 0;
    }

    size_t size() const { return size_; }
    bool empty() const { return size_ == 0; }

private:
    struct Node {
        uint64_t key;
        V value;
        Node* left;
        Node* right;
    };

    Node* root_ = nullptr;
    size_t size_ = 0;

    /// Moves the node with the given key to the root; if there is none, the
    /// last node on the search path.  The tree must not be empty.
    void splay(uint64_t key) {
        // The nodes passed on the way down are collected into two trees:
        // those smaller than key and those larger.  Each tail points at the
        // link where the next such node belongs.
        Node* smaller = nullptr;
        Node* larger = nullptr;
        Node** smaller_tail = &smaller;
        Node** larger_tail = &larger;
        Node* t = root_;

        for (;;) {
            if (key < t->key) {
                if (!t->left) { break; }
                if (key < t->left->key) {
                    Node* y = t->left; // zig-zig: rotate right
                    t->left = y->right;
                    y->right = t;
                    t = y;
                    if (!t->left) { break; }
                }
                *larger_tail = t;
                larger_tail = &t->left;
                t = t->left;
            } else if (key > t->key) {
                if (!t->right) { break; }
                if (key > t->right->key) {
                    Node* y = t->right; // zig-zig: rotate left
                    t->right = y->left;
                    y->left = t;
                    t = y;
                    if (!t->right) { break; }
                }
                *smaller_tail = t;
                smaller_tail = &t->right;
                t = t->right;
            } else {
                break;
            }
        }

        *smaller_tail = t->left;
        *larger_tail = t->right;
        t->left = smaller;
        t->right = larger;
        root_ = t;
    }

    /// Makes a new node the root.  If the tree is not empty it must just have
    /// been splayed on key, and key must be absent.
    void add_root(uint64_t key, V value) {
        Node* n = new Node{key, std::move(value), nullptr, nullptr};
        if (root_) {
            if (key < root_->key) {
                n->left = std::exchange(root_->left, nullptr);
                n->right = root_;
            } else {
                n->right = std::exchange(root_->right, nullptr);
                n->left = root_;
            }
        }
        root_ = n;
        ++size_;
    }
};

} // namespace delta
