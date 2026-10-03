package io.github.jemmix.tdfa.core.engine;

import io.github.jemmix.tdfa.core.emit.EmittedSurface;

import java.util.Arrays;

/**
 * BT22 &sect;3.1 multi-valued tag storage: a prefix tree of tag offsets.
 * A multi-valued register does not hold one offset — it holds the SEQUENCE
 * of offsets assigned to the tag across every repetition iteration (nil
 * entries included, one per bypassed iteration). The tree stores nodes as
 * {@code (pred, val)} pairs addressed by small integer ids: node 0 is the
 * shared root and every {@code head <= 0} denotes the empty sequence.
 * Appends allocate one node (amortized O(1), arrays grow by doubling);
 * copies are scalar (a head id), so the runtime register file stays a flat
 * {@code int[]} on both engine tiers.
 *
 * <p><b>Lifecycle.</b> One grow-only tree per {@link MatchScratch} carrier,
 * {@link #reset()} by the owning walk before it starts appending; nodes are
 * strictly append-only, so heads recorded at an earlier accept remain valid
 * (their chains frozen) while the walk keeps appending. Successful walks
 * hand a {@link #snapshot()} to the result holder — a trimmed immutable
 * copy the tree can never touch again, which is what makes
 * {@link MatchResult} shareable across threads like the rest of its state.
 *
 * <p><b>Values.</b> Appended values are match positions (>= 0); negative
 * assignments never append (the engine's re2j capture contract keeps a
 * value once set — bypassed iterations contribute no node).
 *
 * <p>Not thread-safe; snapshots are.
 */
@EmittedSurface // class-level: generated engines link snapshot() by name
public final class TagTree {

    private int[] pred;
    private int[] val;
    private int top;

    public TagTree() {
        this.pred = new int[16];
        this.val = new int[16];
    }

    private TagTree(int[] pred, int[] val, int top) {
        this.pred = pred;
        this.val = val;
        this.top = top;
    }

    /** Drop all nodes (walk start; heads from earlier walks must not be reused). */
    public void reset() {
        top = 0;
    }

    /**
     * Append one element to the sequence addressed by {@code head} and
     * return the new head. {@code head <= 0} starts at the root.
     */
    public int append(int head, int value) {
        if (top + 1 >= pred.length) {
            int n = Math.max(top + 2, pred.length * 2);
            pred = Arrays.copyOf(pred, n);
            val = Arrays.copyOf(val, n);
        }
        top++;
        pred[top] = head > 0 ? head : 0;
        val[top] = value;
        return top;
    }

    /** The LAST element of the sequence at {@code head} — the current single value; {@code -1} if empty. */
    public int last(int head) {
        return head > 0 && head <= top ? val[head] : -1;
    }

    /**
     * Write the sequence at {@code head} in chronological order into
     * {@code out} (which must have room for {@link #count(int)} elements)
     * and return the element count.
     */
    public int collect(int head, int[] out) {
        int n = 0;
        for (int h = head; h > 0 && h <= top; h = pred[h]) {
            out[n++] = val[h];
        }
        // reverse in place: the chain is newest-first
        for (int i = 0, j = n - 1; i < j; i++, j--) {
            int t = out[i];
            out[i] = out[j];
            out[j] = t;
        }
        return n;
    }

    /** Number of elements in the sequence at {@code head}. */
    public int count(int head) {
        int n = 0;
        for (int h = head; h > 0 && h <= top; h = pred[h]) {
            n++;
        }
        return n;
    }

    /**
     * Immutable trimmed copy for result holders — the walk's tree keeps
     * mutating, the snapshot never does. Linked by name from generated
     * engines (holder construction).
     */
    @EmittedSurface
    public TagTree snapshot() {
        return new TagTree(Arrays.copyOf(pred, top + 1), Arrays.copyOf(val, top + 1), top);
    }

    /** Value equality over the live nodes (snapshot comparison in tests). */
    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof TagTree)) {
            return false;
        }
        TagTree t = (TagTree) o;
        if (top != t.top) {
            return false;
        }
        for (int i = 1; i <= top; i++) {
            if (pred[i] != t.pred[i] || val[i] != t.val[i]) {
                return false;
            }
        }
        return true;
    }

    @Override
    public int hashCode() {
        int h = top;
        for (int i = 1; i <= top; i++) {
            h = 31 * h + pred[i];
            h = 31 * h + val[i];
        }
        return h;
    }

    @Override
    public String toString() {
        return "[" + top + " nodes, last=" + last(top) + "]";
    }
}
