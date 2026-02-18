/*
 * splay.c — Tarjan-Sleator splay tree keyed on uint64_t fingerprints
 *
 * Self-adjusting binary search tree: every access splays the accessed
 * node to the root via zig/zig-zig/zig-zag rotations.  Amortized
 * O(log n) per operation.
 *
 * Reference: Sleator & Tarjan, "Self-Adjusting Binary Search Trees",
 * JACM 32(3), 1985.
 *
 * Values are fixed-size, stored inline via flexible array member.
 */

#include "delta.h"

#include <stdlib.h>
#include <string.h>

/* ── Node allocation ───────────────────────────────────────────────── */

static delta_splay_node_t *
node_alloc(uint64_t key, const void *value, size_t value_size)
{
	delta_splay_node_t *n = delta_malloc(sizeof(*n) + value_size);
	n->key = key;
	n->left = NULL;
	n->right = NULL;
	memcpy(n->value, value, value_size);
	return n;
}

/* ── Top-down splay (Sleator & Tarjan 1985) ────────────────────────── */

static void
splay(delta_splay_t *t, uint64_t key)
{
	delta_splay_node_t header, *l, *r, *tp, *y;

	if (!t->root) { return; }

	memset(&header, 0, sizeof(header));
	l = r = &header;
	tp = t->root;

	for (;;) {
		if (key < tp->key) {
			if (!tp->left) { break; }
			if (key < tp->left->key) {
				/* Zig-zig: rotate right */
				y = tp->left;
				tp->left = y->right;
				y->right = tp;
				tp = y;
				if (!tp->left) { break; }
			}
			/* Link right */
			r->left = tp;
			r = tp;
			tp = tp->left;
		} else if (key > tp->key) {
			if (!tp->right) { break; }
			if (key > tp->right->key) {
				/* Zig-zig: rotate left */
				y = tp->right;
				tp->right = y->left;
				y->left = tp;
				tp = y;
				if (!tp->right) { break; }
			}
			/* Link left */
			l->right = tp;
			l = tp;
			tp = tp->right;
		} else {
			break;
		}
	}

	/* Assemble */
	l->right = tp->left;
	r->left = tp->right;
	tp->left = header.right;
	tp->right = header.left;
	t->root = tp;
}

/* ── Public API ────────────────────────────────────────────────────── */

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
	if (!t->root) { return NULL; }
	splay(t, key);
	return (t->root->key == key) ? t->root->value : NULL;
}

void *
delta_splay_insert_or_get(delta_splay_t *t, uint64_t key, const void *value)
{
	delta_splay_node_t *n;

	if (!t->root) {
		t->root = node_alloc(key, value, t->value_size);
		t->size++;
		return t->root->value;
	}

	splay(t, key);
	if (t->root->key == key) {
		return t->root->value;  /* retain existing */
	}

	n = node_alloc(key, value, t->value_size);
	t->size++;
	if (key < t->root->key) {
		n->left = t->root->left;
		n->right = t->root;
		t->root->left = NULL;
	} else {
		n->right = t->root->right;
		n->left = t->root;
		t->root->right = NULL;
	}
	t->root = n;
	return t->root->value;
}

void
delta_splay_insert(delta_splay_t *t, uint64_t key, const void *value)
{
	delta_splay_node_t *n;

	if (!t->root) {
		t->root = node_alloc(key, value, t->value_size);
		t->size++;
		return;
	}

	splay(t, key);
	if (t->root->key == key) {
		memcpy(t->root->value, value, t->value_size);
		return;
	}

	n = node_alloc(key, value, t->value_size);
	t->size++;
	if (key < t->root->key) {
		n->left = t->root->left;
		n->right = t->root;
		t->root->left = NULL;
	} else {
		n->right = t->root->right;
		n->left = t->root;
		t->root->right = NULL;
	}
	t->root = n;
}

static void
destroy(delta_splay_node_t *n, void (*value_free)(void *))
{
	if (!n) { return; }
	destroy(n->left, value_free);
	destroy(n->right, value_free);
	if (value_free) {
		value_free(n->value);
	}
	free(n);
}

void
delta_splay_clear(delta_splay_t *t)
{
	destroy(t->root, t->value_free);
	t->root = NULL;
	t->size = 0;
}

void
delta_splay_free(delta_splay_t *t)
{
	delta_splay_clear(t);
}
