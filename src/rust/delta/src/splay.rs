//! Tarjan-Sleator splay tree keyed on u64 fingerprints.
//!
//! A self-adjusting binary search tree: every access (find/insert)
//! splays the accessed node to the root via zig/zig-zig/zig-zag
//! rotations.  Amortized O(log n) per operation.
//!
//! Reference: Sleator & Tarjan, "Self-Adjusting Binary Search Trees",
//! JACM 32(3), 1985.

use std::ptr;

/// A node in the splay tree.
struct Node<V> {
    key: u64,
    value: V,
    left: *mut Node<V>,
    right: *mut Node<V>,
}

/// A splay tree mapping u64 keys to values of type V.
pub struct SplayTree<V> {
    root: *mut Node<V>,
    len: usize,
}

impl<V> SplayTree<V> {
    pub fn new() -> Self {
        SplayTree {
            root: ptr::null_mut(),
            len: 0,
        }
    }

    pub fn len(&self) -> usize {
        self.len
    }

    pub fn is_empty(&self) -> bool {
        self.len == 0
    }

    /// Find key; returns reference to value or None.
    /// Splays the found node (or last visited) to root.
    pub fn find(&mut self, key: u64) -> Option<&mut V> {
        if self.root.is_null() {
            return None;
        }
        self.splay(key);
        unsafe {
            if (*self.root).key == key {
                Some(&mut (*self.root).value)
            } else {
                None
            }
        }
    }

    /// Insert key with value if absent; returns mutable reference to
    /// the (possibly pre-existing) value. Splays to root.
    pub fn insert_or_get(&mut self, key: u64, value: V) -> &mut V {
        if self.root.is_null() {
            let node = Box::into_raw(Box::new(Node {
                key,
                value,
                left: ptr::null_mut(),
                right: ptr::null_mut(),
            }));
            self.root = node;
            self.len += 1;
            return unsafe { &mut (*self.root).value };
        }

        self.splay(key);

        unsafe {
            if (*self.root).key == key {
                return &mut (*self.root).value;
            }

            let node = Box::into_raw(Box::new(Node {
                key,
                value,
                left: ptr::null_mut(),
                right: ptr::null_mut(),
            }));
            self.len += 1;

            if key < (*self.root).key {
                (*node).left = (*self.root).left;
                (*node).right = self.root;
                (*self.root).left = ptr::null_mut();
            } else {
                (*node).right = (*self.root).right;
                (*node).left = self.root;
                (*self.root).right = ptr::null_mut();
            }
            self.root = node;
            &mut (*self.root).value
        }
    }

    /// Insert key with value, overwriting any existing entry.
    pub fn insert(&mut self, key: u64, value: V) {
        if self.root.is_null() {
            let node = Box::into_raw(Box::new(Node {
                key,
                value,
                left: ptr::null_mut(),
                right: ptr::null_mut(),
            }));
            self.root = node;
            self.len += 1;
            return;
        }

        self.splay(key);

        unsafe {
            if (*self.root).key == key {
                (*self.root).value = value;
                return;
            }

            let node = Box::into_raw(Box::new(Node {
                key,
                value,
                left: ptr::null_mut(),
                right: ptr::null_mut(),
            }));
            self.len += 1;

            if key < (*self.root).key {
                (*node).left = (*self.root).left;
                (*node).right = self.root;
                (*self.root).left = ptr::null_mut();
            } else {
                (*node).right = (*self.root).right;
                (*node).left = self.root;
                (*self.root).right = ptr::null_mut();
            }
            self.root = node;
        }
    }

    /// Top-down splay (Sleator & Tarjan 1985).
    ///
    /// Uses MaybeUninit for the header sentinel since we only access
    /// the left/right pointer fields, never the key or value.
    fn splay(&mut self, key: u64) {
        use std::mem::MaybeUninit;

        if self.root.is_null() {
            return;
        }

        // Sentinel header — only left/right are used.
        let mut header = MaybeUninit::<Node<V>>::uninit();
        let header_ptr = header.as_mut_ptr();
        unsafe {
            (*header_ptr).left = ptr::null_mut();
            (*header_ptr).right = ptr::null_mut();
        }
        let mut l: *mut Node<V> = header_ptr;
        let mut r: *mut Node<V> = header_ptr;
        let mut t = self.root;

        unsafe {
            loop {
                if key < (*t).key {
                    if (*t).left.is_null() {
                        break;
                    }
                    if key < (*(*t).left).key {
                        // Zig-zig: rotate right
                        let y = (*t).left;
                        (*t).left = (*y).right;
                        (*y).right = t;
                        t = y;
                        if (*t).left.is_null() {
                            break;
                        }
                    }
                    // Link right
                    (*r).left = t;
                    r = t;
                    t = (*t).left;
                } else if key > (*t).key {
                    if (*t).right.is_null() {
                        break;
                    }
                    if key > (*(*t).right).key {
                        // Zig-zig: rotate left
                        let y = (*t).right;
                        (*t).right = (*y).left;
                        (*y).left = t;
                        t = y;
                        if (*t).right.is_null() {
                            break;
                        }
                    }
                    // Link left
                    (*l).right = t;
                    l = t;
                    t = (*t).right;
                } else {
                    break; // found
                }
            }

            // Assemble
            (*l).right = (*t).left;
            (*r).left = (*t).right;
            (*t).left = (*header_ptr).right;
            (*t).right = (*header_ptr).left;
            self.root = t;
        }
    }
}

impl<V> Drop for SplayTree<V> {
    fn drop(&mut self) {
        // Iterative destruction using a stack to avoid deep recursion.
        let mut stack = Vec::new();
        if !self.root.is_null() {
            stack.push(self.root);
        }
        while let Some(node) = stack.pop() {
            unsafe {
                if !(*node).left.is_null() {
                    stack.push((*node).left);
                }
                if !(*node).right.is_null() {
                    stack.push((*node).right);
                }
                drop(Box::from_raw(node));
            }
        }
        self.root = ptr::null_mut();
        self.len = 0;
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
