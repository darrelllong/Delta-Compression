package delta;

/**
 * A splay tree from long keys to values (Sleator and Tarjan, "Self-Adjusting
 * Binary Search Trees", JACM 32(3), 1985).
 *
 * Every lookup or insertion moves the key it touches to the root, so keys
 * used often stay near the top.  Each takes O(log n) amortized time.
 *
 * @param <V> the value type; null is not a useful value, since find returns
 *            null for a missing key
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

    // Scratch node for splay: its right and left children collect the trees
    // of keys smaller and larger than the one sought.
    private final Node<V> header = new Node<>(0, null);

    /** Returns the number of keys. */
    public int size() { return size; }

    /** Returns the value stored for key, or null if there is none. */
    public V find(long key) {
        if (root == null) return null;
        splay(key);
        return root.key == key ? root.value : null;
    }

    /** Returns the value stored for key, first storing value if there is none. */
    public V insertOrGet(long key, V value) {
        if (root != null) {
            splay(key);
            if (root.key == key) return root.value;
        }
        addRoot(key, value);
        return value;
    }

    /** Stores value for key, replacing any value already there. */
    public void insert(long key, V value) {
        if (root != null) {
            splay(key);
            if (root.key == key) {
                root.value = value;
                return;
            }
        }
        addRoot(key, value);
    }

    /**
     * Makes a new node for key the root.  Requires that key is absent and
     * that the tree, if not empty, has just been splayed on key, so that the
     * old root is key's neighbour and splits the tree around it.
     */
    private void addRoot(long key, V value) {
        Node<V> node = new Node<>(key, value);
        if (root != null) {
            if (key < root.key) {
                node.left = root.left;
                node.right = root;
                root.left = null;
            } else {
                node.right = root.right;
                node.left = root;
                root.right = null;
            }
        }
        root = node;
        size++;
    }

    /**
     * Top-down splay: moves the node for key to the root, or, if key is
     * absent, the last node on the search path.  Requires a non-empty tree.
     */
    private void splay(long key) {
        Node<V> l = header, r = header;
        Node<V> t = root;
        header.left = header.right = null;

        for (;;) {
            if (key < t.key) {
                if (t.left == null) break;
                if (key < t.left.key) {
                    Node<V> y = t.left; // rotate right
                    t.left = y.right;
                    y.right = t;
                    t = y;
                    if (t.left == null) break;
                }
                r.left = t; // t and its right subtree are larger than key
                r = t;
                t = t.left;
            } else if (key > t.key) {
                if (t.right == null) break;
                if (key > t.right.key) {
                    Node<V> y = t.right; // rotate left
                    t.right = y.left;
                    y.left = t;
                    t = y;
                    if (t.right == null) break;
                }
                l.right = t; // t and its left subtree are smaller than key
                l = t;
                t = t.right;
            } else {
                break;
            }
        }

        l.right = t.left;
        r.left = t.right;
        t.left = header.right;
        t.right = header.left;
        root = t;
    }
}
