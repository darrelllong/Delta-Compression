#pragma once

/// Tarjan-Sleator splay tree keyed on uint64_t fingerprints.
///
/// A self-adjusting binary search tree: every access (find/insert)
/// splays the accessed node to the root via zig/zig-zig/zig-zag
/// rotations.  Amortized O(log n) per operation.
///
/// Reference: Sleator & Tarjan, "Self-Adjusting Binary Search Trees",
/// JACM 32(3), 1985.

#include <cstddef>
#include <cstdint>
#include <functional>
#include <utility>

namespace delta {

template <typename V>
class SplayTree {
public:
    SplayTree() = default;

    ~SplayTree() { clear(); }

    // Non-copyable, movable.
    SplayTree(const SplayTree&) = delete;
    SplayTree& operator=(const SplayTree&) = delete;

    SplayTree(SplayTree&& o) noexcept
        : root_(o.root_), size_(o.size_) {
        o.root_ = nullptr;
        o.size_ = 0;
    }

    SplayTree& operator=(SplayTree&& o) noexcept {
        if (this != &o) {
            clear();
            root_ = o.root_;
            size_ = o.size_;
            o.root_ = nullptr;
            o.size_ = 0;
        }
        return *this;
    }

    /// Find key; returns pointer to value or nullptr.
    /// Splays the found node (or last visited) to root.
    V* find(uint64_t key) {
        if (!root_) { return nullptr; }
        splay(key);
        return (root_->key == key) ? &root_->value : nullptr;
    }

    /// Insert key with value if absent; returns reference to
    /// the (possibly pre-existing) value.  Splays to root.
    V& insert_or_get(uint64_t key, V value) {
        if (!root_) {
            root_ = new Node{key, std::move(value), nullptr, nullptr};
            ++size_;
            return root_->value;
        }

        splay(key);

        if (root_->key == key) {
            return root_->value; // already present — retain existing
        }

        auto* n = new Node{key, std::move(value), nullptr, nullptr};
        ++size_;

        if (key < root_->key) {
            n->left = root_->left;
            n->right = root_;
            root_->left = nullptr;
        } else {
            n->right = root_->right;
            n->left = root_;
            root_->right = nullptr;
        }
        root_ = n;
        return root_->value;
    }

    /// Insert key with value, overwriting any existing entry.
    void insert(uint64_t key, V value) {
        if (!root_) {
            root_ = new Node{key, std::move(value), nullptr, nullptr};
            ++size_;
            return;
        }

        splay(key);

        if (root_->key == key) {
            root_->value = std::move(value);
            return;
        }

        auto* n = new Node{key, std::move(value), nullptr, nullptr};
        ++size_;

        if (key < root_->key) {
            n->left = root_->left;
            n->right = root_;
            root_->left = nullptr;
        } else {
            n->right = root_->right;
            n->left = root_;
            root_->right = nullptr;
        }
        root_ = n;
    }

    /// Deallocate all nodes.
    void clear() {
        destroy(root_);
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

    /// Top-down splay (Sleator & Tarjan 1985).
    ///
    /// Restructures the tree so that the node with the given key
    /// (or the last node on the search path) becomes the root.
    void splay(uint64_t key) {
        if (!root_) { return; }

        // Sentinel header node; left/right trees accumulate in l/r.
        Node header{0, V{}, nullptr, nullptr};
        Node* l = &header;
        Node* r = &header;
        Node* t = root_;

        for (;;) {
            if (key < t->key) {
                if (!t->left) { break; }
                if (key < t->left->key) {
                    // Zig-zig: rotate right
                    Node* y = t->left;
                    t->left = y->right;
                    y->right = t;
                    t = y;
                    if (!t->left) { break; }
                }
                // Link right
                r->left = t;
                r = t;
                t = t->left;
            } else if (key > t->key) {
                if (!t->right) { break; }
                if (key > t->right->key) {
                    // Zig-zig: rotate left
                    Node* y = t->right;
                    t->right = y->left;
                    y->left = t;
                    t = y;
                    if (!t->right) { break; }
                }
                // Link left
                l->right = t;
                l = t;
                t = t->right;
            } else {
                break; // found
            }
        }

        // Assemble
        l->right = t->left;
        r->left = t->right;
        t->left = header.right;
        t->right = header.left;
        root_ = t;
    }

    /// Recursively destroy subtree (iterative stack would be safer
    /// for very deep trees, but fingerprints are well-distributed
    /// and splay keeps depth reasonable).
    void destroy(Node* n) {
        if (!n) { return; }
        destroy(n->left);
        destroy(n->right);
        delete n;
    }
};

} // namespace delta
