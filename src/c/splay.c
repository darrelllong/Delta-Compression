// Top-down splay tree (Sleator and Tarjan, JACM 32(3), 1985, Section 4).
// Every access brings the key it sought, or a neighbour of it, to the root,
// in O(log n) amortised time.

#include "internal.h"

static delta_splay_node_t *
node_new(uint64_t key, const void *value, size_t value_size)
{
	delta_splay_node_t *n = delta_malloc(sizeof(*n) + value_size);
	n->key = key;
	n->left = NULL;
	n->right = NULL;
	memcpy(n->value, value, value_size);
	return n;
}

// splay brings the node with the given key to the root; if there is none, the
// last node on the search path.  The tree must not be empty.
//
// The nodes passed on the way down are hung on two trees, l of keys less than
// the key sought and r of keys greater, which grow at their inner edges.
// header's right and left fields are the roots of l and r.
static void
splay(delta_splay_t *t, uint64_t key)
{
	delta_splay_node_t header = { .left = NULL, .right = NULL };
	delta_splay_node_t *l = &header, *r = &header;
	delta_splay_node_t *x = t->root;

	for (;;) {
		if (key < x->key) {
			if (!x->left) {
				break;
			}
			if (key < x->left->key) {
				delta_splay_node_t *y = x->left; // rotate right
				x->left = y->right;
				y->right = x;
				x = y;
				if (!x->left) {
					break;
				}
			}
			r->left = x;
			r = x;
			x = x->left;
		} else if (key > x->key) {
			if (!x->right) {
				break;
			}
			if (key > x->right->key) {
				delta_splay_node_t *y = x->right; // rotate left
				x->right = y->left;
				y->left = x;
				x = y;
				if (!x->right) {
					break;
				}
			}
			l->right = x;
			l = x;
			x = x->right;
		} else {
			break;
		}
	}

	l->right = x->left;
	r->left = x->right;
	x->left = header.right;
	x->right = header.left;
	t->root = x;
}

// insert_at_root adds a key that is not in the tree, which has just been
// splayed about that key: the root is the key's neighbour.
static void *
insert_at_root(delta_splay_t *t, uint64_t key, const void *value)
{
	delta_splay_node_t *n = node_new(key, value, t->value_size);
	delta_splay_node_t *root = t->root;

	if (root) {
		if (key < root->key) {
			n->left = root->left;
			n->right = root;
			root->left = NULL;
		} else {
			n->right = root->right;
			n->left = root;
			root->right = NULL;
		}
	}
	t->root = n;
	t->size++;
	return n->value;
}

void
delta_splay_init(delta_splay_t *t, size_t value_size)
{
	t->root = NULL;
	t->size = 0;
	t->value_size = value_size;
	t->value_free = NULL;
}

void *
delta_splay_find(delta_splay_t *t, uint64_t key)
{
	if (!t->root) {
		return NULL;
	}
	splay(t, key);
	return t->root->key == key ? t->root->value : NULL;
}

void *
delta_splay_insert_or_get(delta_splay_t *t, uint64_t key, const void *value)
{
	if (t->root) {
		splay(t, key);
		if (t->root->key == key) {
			return t->root->value;
		}
	}
	return insert_at_root(t, key, value);
}

void
delta_splay_insert(delta_splay_t *t, uint64_t key, const void *value)
{
	if (t->root) {
		splay(t, key);
		if (t->root->key == key) {
			memcpy(t->root->value, value, t->value_size);
			return;
		}
	}
	insert_at_root(t, key, value);
}

// A splay tree can be a path as long as the tree is large, so this must not
// recurse.  It rotates left children up until the root has none, then frees
// the root and continues with its right subtree.
void
delta_splay_clear(delta_splay_t *t)
{
	delta_splay_node_t *n = t->root;

	while (n) {
		delta_splay_node_t *l = n->left;
		if (l) {
			n->left = l->right;
			l->right = n;
			n = l;
			continue;
		}
		delta_splay_node_t *next = n->right;
		if (t->value_free) {
			t->value_free(n->value);
		}
		free(n);
		n = next;
	}
	t->root = NULL;
	t->size = 0;
}

void
delta_splay_free(delta_splay_t *t)
{
	delta_splay_clear(t);
}
