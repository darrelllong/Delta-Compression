package delta;

/**
 * Tarjan-Sleator splay tree keyed on long (fingerprint).
 *
 * A self-adjusting binary search tree: every access (find/insert)
 * splays the accessed node to the root via zig/zig-zig/zig-zag
 * rotations.  Amortized O(log n) per operation.
 *
 * Reference: Sleator & Tarjan, "Self-Adjusting Binary Search Trees",
 * JACM 32(3), 1985.
 *
 * @param <V> value type
 */
public final class SplayTree<V> {
    private static final class Node<V> {
        long key;
        V value;
        Node<V> left, right;

        Node(long key, V value) {
            this.key = key;
            this.value = value;
        }
    }

    private Node<V> root;
    private int size;

    public int size() { return size; }
    public boolean isEmpty() { return size == 0; }

    /** Find key; returns value or null. Splays found node to root. */
    public V find(long key) {
        if (root == null) return null;
        splay(key);
        return root.key == key ? root.value : null;
    }

    /** Insert if absent; return existing or new value. */
    public V insertOrGet(long key, V value) {
        if (root == null) {
            root = new Node<>(key, value);
            size++;
            return root.value;
        }
        splay(key);
        if (root.key == key) return root.value;

        Node<V> node = new Node<>(key, value);
        size++;
        if (key < root.key) {
            node.left = root.left;
            node.right = root;
            root.left = null;
        } else {
            node.right = root.right;
            node.left = root;
            root.right = null;
        }
        root = node;
        return root.value;
    }

    /** Insert key with value, overwriting any existing entry. */
    public void insert(long key, V value) {
        if (root == null) {
            root = new Node<>(key, value);
            size++;
            return;
        }
        splay(key);
        if (root.key == key) {
            root.value = value;
            return;
        }

        Node<V> node = new Node<>(key, value);
        size++;
        if (key < root.key) {
            node.left = root.left;
            node.right = root;
            root.left = null;
        } else {
            node.right = root.right;
            node.left = root;
            root.right = null;
        }
        root = node;
    }

    /** Set value for existing key (after find). */
    public void setValue(V value) {
        if (root != null) root.value = value;
    }

    /** Top-down splay (Sleator & Tarjan 1985). */
    private void splay(long key) {
        if (root == null) return;

        // Sentinel header — only left/right used.
        Node<V> header = new Node<>(0, null);
        Node<V> l = header, r = header;
        Node<V> t = root;

        for (;;) {
            if (key < t.key) {
                if (t.left == null) break;
                if (key < t.left.key) {
                    // Zig-zig: rotate right
                    Node<V> y = t.left;
                    t.left = y.right;
                    y.right = t;
                    t = y;
                    if (t.left == null) break;
                }
                // Link right
                r.left = t;
                r = t;
                t = t.left;
            } else if (key > t.key) {
                if (t.right == null) break;
                if (key > t.right.key) {
                    // Zig-zig: rotate left
                    Node<V> y = t.right;
                    t.right = y.left;
                    y.left = t;
                    t = y;
                    if (t.right == null) break;
                }
                // Link left
                l.right = t;
                l = t;
                t = t.right;
            } else {
                break; // found
            }
        }

        // Assemble
        l.right = t.left;
        r.left = t.right;
        t.left = header.right;
        t.right = header.left;
        root = t;
    }
}
