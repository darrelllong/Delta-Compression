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
class SplayTree[V] {

  private class Node(val key: Long, var value: V,
                     var left: Node = null, var right: Node = null)

  private var root: Node = null
  var size: Int = 0

  def isEmpty: Boolean = size == 0

  /** Find key; returns Some(value) or None. Splays found node to root. */
  def find(key: Long): Option[V] = {
    if root == null then return None
    splay(key)
    if root.key == key then Some(root.value) else None
  }

  /** Insert if absent; return existing or new value. */
  def insertOrGet(key: Long, value: V): V = {
    if root == null then {
      root = new Node(key, value)
      size += 1
      return root.value
    }
    splay(key)
    if root.key == key then return root.value

    val node = new Node(key, value)
    size += 1
    if key < root.key then {
      node.left  = root.left
      node.right = root
      root.left  = null
    } else {
      node.right = root.right
      node.left  = root
      root.right = null
    }
    root = node
    root.value
  }

  /** Insert key with value, overwriting any existing entry. */
  def insert(key: Long, value: V): Unit = {
    if root == null then {
      root = new Node(key, value)
      size += 1
      return
    }
    splay(key)
    if root.key == key then {
      root.value = value
      return
    }
    val node = new Node(key, value)
    size += 1
    if key < root.key then {
      node.left  = root.left
      node.right = root
      root.left  = null
    } else {
      node.right = root.right
      node.left  = root
      root.right = null
    }
    root = node
  }

  /** Top-down splay (Sleator & Tarjan 1985). */
  private def splay(key: Long): Unit = {
    if root == null then return

    // Sentinel header — only left/right used; key/value are placeholders.
    val header = new Node(0L, root.value)
    var l      = header
    var rr     = header
    var t      = root
    var cont   = true

    while cont do {
      if key < t.key then {
        if t.left == null then {
          cont = false
        } else {
          val left = t.left
          if key < left.key then {
            // Zig-zig: rotate right
            t.left   = left.right
            left.right = t
            t = left
            if t.left == null then cont = false
          }
          if cont then {
            // Link right
            rr.left = t
            rr = t
            t = t.left
          }
        }
      } else if key > t.key then {
        if t.right == null then {
          cont = false
        } else {
          val right = t.right
          if key > right.key then {
            // Zig-zig: rotate left
            t.right   = right.left
            right.left = t
            t = right
            if t.right == null then cont = false
          }
          if cont then {
            // Link left
            l.right = t
            l = t
            t = t.right
          }
        }
      } else {
        cont = false  // found
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
