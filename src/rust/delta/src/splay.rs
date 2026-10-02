//! A top-down splay tree keyed on fingerprints (Sleator and Tarjan,
//! "Self-Adjusting Binary Search Trees", JACM 32(3), 1985).
//!
//! Every lookup and insertion moves the node it touches to the root, so a
//! fingerprint that recurs is found quickly.  Operations take O(log n)
//! amortized time.

use std::mem::MaybeUninit;
use std::ptr;

struct Node<V> {
    key: u64,
    value: V,
    left: *mut Node<V>,
    right: *mut Node<V>,
}

/// A map from u64 keys to values of type `V`.
///
/// Lookups take `&mut self` because they restructure the tree.
pub struct SplayTree<V> {
    // Every node is a leaked Box that the tree owns; Drop frees them.
    root: *mut Node<V>,
    len: usize,
}

impl<V> Default for SplayTree<V> {
    fn default() -> Self {
        Self::new()
    }
}

impl<V> SplayTree<V> {
    /// Returns an empty tree.
    pub fn new() -> Self {
        SplayTree {
            root: ptr::null_mut(),
            len: 0,
        }
    }

    /// The number of keys in the tree.
    pub fn len(&self) -> usize {
        self.len
    }

    pub fn is_empty(&self) -> bool {
        self.len == 0
    }

    /// Returns the value stored under `key`, if any.
    pub fn find(&mut self, key: u64) -> Option<&mut V> {
        if self.splay(key) {
            // SAFETY: splay returned true, so root is a live node.
            Some(unsafe { &mut (*self.root).value })
        } else {
            None
        }
    }

    /// Returns the value stored under `key`, first storing `value` there if
    /// the key is absent.
    pub fn insert_or_get(&mut self, key: u64, value: V) -> &mut V {
        if !self.splay(key) {
            self.insert_root(key, value);
        }
        // SAFETY: the key was found or has just been inserted, at the root.
        unsafe { &mut (*self.root).value }
    }

    /// Stores `value` under `key`, replacing any value already there.
    pub fn insert(&mut self, key: u64, value: V) {
        if self.splay(key) {
            // SAFETY: splay returned true, so root is a live node.
            unsafe { (*self.root).value = value };
        } else {
            self.insert_root(key, value);
        }
    }

    /// Makes a new node for `key` the root.  The caller has just splayed for
    /// `key` and not found it, so the old root, if any, is the key's
    /// neighbor in order and the new node splits the tree there.
    fn insert_root(&mut self, key: u64, value: V) {
        let node = Box::into_raw(Box::new(Node {
            key,
            value,
            left: ptr::null_mut(),
            right: ptr::null_mut(),
        }));
        let old = self.root;
        if !old.is_null() {
            // SAFETY: node was just allocated and old is the live root; they
            // are distinct.
            unsafe {
                if key < (*old).key {
                    (*node).left = (*old).left;
                    (*node).right = old;
                    (*old).left = ptr::null_mut();
                } else {
                    (*node).right = (*old).right;
                    (*node).left = old;
                    (*old).right = ptr::null_mut();
                }
            }
        }
        self.root = node;
        self.len += 1;
    }

    /// Moves the node with `key` to the root and reports whether there is
    /// one.  If there is not, the root becomes the last node on the search
    /// path, a neighbor of `key` in order.
    fn splay(&mut self, key: u64) -> bool {
        if self.root.is_null() {
            return false;
        }

        // Nodes less than the key are hung off header.right through `l`;
        // nodes greater, off header.left through `r`.  Only the header's two
        // links are ever written or read, so the rest stays uninitialized.
        let mut header = MaybeUninit::<Node<V>>::uninit();
        let header = header.as_mut_ptr();
        let mut l = header;
        let mut r = header;
        let mut t = self.root;

        // SAFETY: t starts at the live root and only ever follows non-null
        // links; l and r are the header or nodes already visited.  Writes to
        // the header go through raw field pointers and touch only its links.
        unsafe {
            ptr::addr_of_mut!((*header).left).write(ptr::null_mut());
            ptr::addr_of_mut!((*header).right).write(ptr::null_mut());
            loop {
                if key < (*t).key {
                    if (*t).left.is_null() {
                        break;
                    }
                    if key < (*(*t).left).key {
                        // Zig-zig: rotate right.
                        let y = (*t).left;
                        (*t).left = (*y).right;
                        (*y).right = t;
                        t = y;
                        if (*t).left.is_null() {
                            break;
                        }
                    }
                    (*r).left = t;
                    r = t;
                    t = (*t).left;
                } else if key > (*t).key {
                    if (*t).right.is_null() {
                        break;
                    }
                    if key > (*(*t).right).key {
                        // Zig-zig: rotate left.
                        let y = (*t).right;
                        (*t).right = (*y).left;
                        (*y).left = t;
                        t = y;
                        if (*t).right.is_null() {
                            break;
                        }
                    }
                    (*l).right = t;
                    l = t;
                    t = (*t).right;
                } else {
                    break;
                }
            }

            (*l).right = (*t).left;
            (*r).left = (*t).right;
            (*t).left = (*header).right;
            (*t).right = (*header).left;
            self.root = t;
            (*t).key == key
        }
    }
}

impl<V> Drop for SplayTree<V> {
    fn drop(&mut self) {
        // A splay tree can be a path as long as the tree is large, so the
        // nodes are freed with an explicit stack rather than by recursion.
        let mut stack = vec![self.root];
        while let Some(node) = stack.pop() {
            if node.is_null() {
                continue;
            }
            // SAFETY: each node is reachable from the root by one path, so it
            // is pushed and freed once.
            let node = unsafe { Box::from_raw(node) };
            stack.push(node.left);
            stack.push(node.right);
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn insert_and_find() {
        let mut tree = SplayTree::new();
        tree.insert_or_get(42, vec![0usize]);
        tree.find(42).unwrap().push(1);
        assert_eq!(tree.find(42).unwrap(), &vec![0, 1]);
        assert!(tree.find(99).is_none());
        assert_eq!(tree.len(), 1);
    }

    #[test]
    fn insert_or_get_retains_existing() {
        let mut tree = SplayTree::new();
        tree.insert_or_get(10, 100usize);
        tree.insert_or_get(10, 200);
        assert_eq!(*tree.find(10).unwrap(), 100);
    }

    #[test]
    fn insert_overwrites() {
        let mut tree = SplayTree::new();
        tree.insert(10, 100usize);
        tree.insert(10, 200);
        assert_eq!(*tree.find(10).unwrap(), 200);
    }

    #[test]
    fn many_keys() {
        let mut tree = SplayTree::new();
        for i in 0..1000u64 {
            tree.insert_or_get(i, i as usize);
        }
        assert_eq!(tree.len(), 1000);
        for i in 0..1000u64 {
            assert_eq!(*tree.find(i).unwrap(), i as usize);
        }
    }
}
