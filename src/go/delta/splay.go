package delta

// SplayTree is a self-adjusting binary search tree keyed on int64 (fingerprint).
//
// Every access splays the accessed node to the root via top-down splay.
// Amortized O(log n) per operation.
//
// Reference: Sleator & Tarjan, "Self-Adjusting Binary Search Trees",
// JACM 32(3), 1985.
type SplayTree[V any] struct {
	root *splayNode[V]
	size int
}

type splayNode[V any] struct {
	key         int64
	value       V
	left, right *splayNode[V]
}

// Len returns the number of entries in the tree.
func (t *SplayTree[V]) Len() int { return t.size }

// Find returns the value for key and true, or the zero value and false.
// The found node is splayed to the root.
func (t *SplayTree[V]) Find(key int64) (V, bool) {
	if t.root == nil {
		var zero V
		return zero, false
	}
	t.splay(key)
	if t.root.key == key {
		return t.root.value, true
	}
	var zero V
	return zero, false
}

// Insert stores value at key, overwriting any existing entry.
func (t *SplayTree[V]) Insert(key int64, value V) {
	if t.root == nil {
		t.root = &splayNode[V]{key: key, value: value}
		t.size++
		return
	}
	t.splay(key)
	if t.root.key == key {
		t.root.value = value
		return
	}
	node := &splayNode[V]{key: key, value: value}
	t.size++
	if key < t.root.key {
		node.left = t.root.left
		node.right = t.root
		t.root.left = nil
	} else {
		node.right = t.root.right
		node.left = t.root
		t.root.right = nil
	}
	t.root = node
}

// InsertOrGet inserts key with value if absent and returns (storedValue, inserted).
// If key already exists, the existing value is returned unchanged.
func (t *SplayTree[V]) InsertOrGet(key int64, value V) (V, bool) {
	if t.root == nil {
		t.root = &splayNode[V]{key: key, value: value}
		t.size++
		return t.root.value, true
	}
	t.splay(key)
	if t.root.key == key {
		return t.root.value, false
	}
	node := &splayNode[V]{key: key, value: value}
	t.size++
	if key < t.root.key {
		node.left = t.root.left
		node.right = t.root
		t.root.left = nil
	} else {
		node.right = t.root.right
		node.left = t.root
		t.root.right = nil
	}
	t.root = node
	return t.root.value, true
}

// SetValue updates the value of the root node (after Find or Insert).
func (t *SplayTree[V]) SetValue(value V) {
	if t.root != nil {
		t.root.value = value
	}
}

// splay performs top-down splay (Sleator & Tarjan 1985).
func (t *SplayTree[V]) splay(key int64) {
	if t.root == nil {
		return
	}
	var header splayNode[V] // sentinel; only left/right used
	l := &header
	r := &header
	cur := t.root

	for {
		if key < cur.key {
			if cur.left == nil {
				break
			}
			if key < cur.left.key {
				// Zig-zig: rotate right.
				y := cur.left
				cur.left = y.right
				y.right = cur
				cur = y
				if cur.left == nil {
					break
				}
			}
			// Link right.
			r.left = cur
			r = cur
			cur = cur.left
		} else if key > cur.key {
			if cur.right == nil {
				break
			}
			if key > cur.right.key {
				// Zig-zig: rotate left.
				y := cur.right
				cur.right = y.left
				y.left = cur
				cur = y
				if cur.right == nil {
					break
				}
			}
			// Link left.
			l.right = cur
			l = cur
			cur = cur.right
		} else {
			break // found
		}
	}

	// Assemble.
	l.right = cur.left
	r.left = cur.right
	cur.left = header.right
	cur.right = header.left
	t.root = cur
}
