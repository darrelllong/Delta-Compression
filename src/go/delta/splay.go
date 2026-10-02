package delta

// SplayTree is a self-adjusting binary search tree keyed on fingerprints
// (Sleator and Tarjan, "Self-Adjusting Binary Search Trees", JACM 32(3),
// 1985). Every operation splays the node it touches to the root, so
// operations take O(log n) amortized time and recently used keys are cheap
// to reach again. The zero value is an empty tree.
type SplayTree[V any] struct {
	root *splayNode[V]
	size int
}

type splayNode[V any] struct {
	key         uint64
	value       V
	left, right *splayNode[V]
}

// Len returns the number of keys in the tree.
func (t *SplayTree[V]) Len() int { return t.size }

// Find returns the value stored at key and whether the key is present.
func (t *SplayTree[V]) Find(key uint64) (V, bool) {
	if t.splay(key) {
		return t.root.value, true
	}
	var zero V
	return zero, false
}

// InsertOrGet stores value at key if the key is absent. It returns the value
// now at key and whether it was inserted.
func (t *SplayTree[V]) InsertOrGet(key uint64, value V) (V, bool) {
	if t.splay(key) {
		return t.root.value, false
	}
	t.insertRoot(key, value)
	return value, true
}

// At returns a pointer to the value stored at key, first storing the zero
// value there if the key is absent.
func (t *SplayTree[V]) At(key uint64) *V {
	if !t.splay(key) {
		var zero V
		t.insertRoot(key, zero)
	}
	return &t.root.value
}

// insertRoot makes key the new root. The tree must have just been splayed
// on key and must not contain it, so the old root is key's predecessor or
// successor.
func (t *SplayTree[V]) insertRoot(key uint64, value V) {
	n := &splayNode[V]{key: key, value: value}
	if old := t.root; old != nil {
		if key < old.key {
			n.left, n.right = old.left, old
			old.left = nil
		} else {
			n.left, n.right = old, old.right
			old.right = nil
		}
	}
	t.root = n
	t.size++
}

// splay moves the node with the given key to the root and reports whether
// it exists. If it does not, the last node on the search path becomes the
// root. This is the top-down splay of Sleator and Tarjan, Section 4.
func (t *SplayTree[V]) splay(key uint64) bool {
	cur := t.root
	if cur == nil {
		return false
	}
	// header.right and header.left collect the trees of keys less than and
	// greater than key; l and r are where the next such subtree is hung.
	var header splayNode[V]
	l, r := &header, &header
	for key != cur.key {
		if key < cur.key {
			if cur.left == nil {
				break
			}
			if key < cur.left.key { // zig-zig: rotate right
				y := cur.left
				cur.left = y.right
				y.right = cur
				cur = y
				if cur.left == nil {
					break
				}
			}
			r.left = cur // link right
			r = cur
			cur = cur.left
		} else {
			if cur.right == nil {
				break
			}
			if key > cur.right.key { // zig-zig: rotate left
				y := cur.right
				cur.right = y.left
				y.left = cur
				cur = y
				if cur.right == nil {
					break
				}
			}
			l.right = cur // link left
			l = cur
			cur = cur.right
		}
	}
	l.right = cur.left
	r.left = cur.right
	cur.left = header.right
	cur.right = header.left
	t.root = cur
	return cur.key == key
}
