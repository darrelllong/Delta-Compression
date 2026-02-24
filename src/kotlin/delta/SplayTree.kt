package delta

/**
 * Tarjan-Sleator splay tree keyed on Long (fingerprint).
 *
 * A self-adjusting binary search tree: every access (find/insert) splays
 * the accessed node to the root via zig/zig-zig/zig-zag rotations.
 * Amortized O(log n) per operation.
 *
 * Reference: Sleator & Tarjan, "Self-Adjusting Binary Search Trees",
 * JACM 32(3), 1985.
 */
class SplayTree<V> {

    private inner class Node(val key: Long, var value: V, var left: Node? = null, var right: Node? = null)

    private var root: Node? = null
    var size: Int = 0
        private set

    val isEmpty: Boolean get() = size == 0

    /** Find key; returns value or null. Splays found node to root. */
    fun find(key: Long): V? {
        if (root == null) return null
        splay(key)
        return if (root!!.key == key) root!!.value else null
    }

    /** Insert if absent; return existing or new value. */
    fun insertOrGet(key: Long, value: V): V {
        if (root == null) {
            root = Node(key, value)
            size++
            return root!!.value
        }
        splay(key)
        if (root!!.key == key) return root!!.value

        val node = Node(key, value)
        size++
        if (key < root!!.key) {
            node.left  = root!!.left
            node.right = root!!
            root!!.left = null
        } else {
            node.right = root!!.right
            node.left  = root!!
            root!!.right = null
        }
        root = node
        return root!!.value
    }

    /** Insert key with value, overwriting any existing entry. */
    fun insert(key: Long, value: V) {
        if (root == null) {
            root = Node(key, value)
            size++
            return
        }
        splay(key)
        if (root!!.key == key) {
            root!!.value = value
            return
        }
        val node = Node(key, value)
        size++
        if (key < root!!.key) {
            node.left  = root!!.left
            node.right = root!!
            root!!.left = null
        } else {
            node.right = root!!.right
            node.left  = root!!
            root!!.right = null
        }
        root = node
    }

    /** Top-down splay (Sleator & Tarjan 1985). */
    private fun splay(key: Long) {
        val r = root ?: return

        // Sentinel header — only left/right used; key/value are placeholders.
        val header = Node(0L, r.value)
        var l: Node = header
        var rr: Node = header
        var t: Node = r

        while (true) {
            when {
                key < t.key -> {
                    val left = t.left ?: break
                    if (key < left.key) {
                        // Zig-zig: rotate right
                        t.left = left.right
                        left.right = t
                        t = left
                        if (t.left == null) break
                    }
                    // Link right
                    rr.left = t
                    rr = t
                    t = t.left!!
                }
                key > t.key -> {
                    val right = t.right ?: break
                    if (key > right.key) {
                        // Zig-zig: rotate left
                        t.right = right.left
                        right.left = t
                        t = right
                        if (t.right == null) break
                    }
                    // Link left
                    l.right = t
                    l = t
                    t = t.right!!
                }
                else -> break  // found
            }
        }

        // Assemble
        l.right  = t.left
        rr.left  = t.right
        t.left   = header.right
        t.right  = header.left
        root = t
    }
}
