package io.github.jemmix.tdfa.tdfa;

import java.util.ArrayList;
import java.util.List;

/**
 * Persistent prefix tree of tag paths (BT19 §6; re2c's {@code phistory_t}).
 *
 * <p>Each ε-closure builds a fresh tree of the tag-sequence paths from the
 * seed configurations to the discovered configurations. Common path prefixes
 * are shared (append-only; parent index {@code <} child index). A path is
 * addressed by its leaf node index; copying a path = copying an int.
 *
 * <p>The two-fingers fork-finding algorithm in {@link PosixCompare} walks two
 * paths upward via {@link #pred(int)} until they meet, taking the running
 * minimum of tag heights (the "trace" of BT19 §8).
 *
 * <p>Node 0 is the root (empty path). Tag-info value 0 means "untagged
 * ε-transition" (the {@link #extend} call returns the same node unchanged).
 *
 * <h2>Thread safety</h2>
 * Not thread-safe; owned by one closure computation.
 */
final class UTree {

    /** Tag-info packed value: high bit = negative (nil), low bits = tag number (1..). 0 = no tag. */
    private final ArrayList<Integer> info;
    /** Parent index of each node. Node 0 is root (pred = -1). */
    private final ArrayList<Integer> pred;
    /** Sibling arc list head; -1 if no children. Used for O(m²) prectable DFS. */
    private final ArrayList<Integer> firstChild;
    /** Next-sibling pointer for the arc list. */
    private final ArrayList<Integer> nextSibling;

    UTree() {
        info = new ArrayList<>();
        pred = new ArrayList<>();
        firstChild = new ArrayList<>();
        nextSibling = new ArrayList<>();
        // Node 0 = root.
        info.add(0);
        pred.add(-1);
        firstChild.add(-1);
        nextSibling.add(-1);
    }

    int root() { return 0; }

    int size() { return info.size(); }

    /** Tag info at {@code node}; 0 = no tag (untagged ε-edge). */
    int info(int node) { return info.get(node); }

    /** Parent of {@code node}; -1 for root. */
    int pred(int node) { return pred.get(node); }

    /**
     * Append a tag to the path ending at {@code node}, returning the new leaf.
     * If {@code tagInfo == 0} (untagged ε-transition) returns {@code node}
     * unchanged — no new node is created.
     *
     * <p>Idempotent: if {@code node} already has a child with this exact
     * tag-info, returns the existing child. This preserves the prefix-tree
     * property that siblings have distinct tags.
     */
    int extend(int node, int tagInfo) {
        if (tagInfo == 0) return node;
        // Look for an existing child with this tag-info.
        for (int c = firstChild.get(node); c != -1; c = nextSibling.get(c)) {
            if (info.get(c) == tagInfo) return c;
        }
        // Create new child.
        int newIdx = info.size();
        info.add(tagInfo);
        pred.add(node);
        firstChild.add(-1);
        nextSibling.add(firstChild.get(node));
        firstChild.set(node, newIdx);
        return newIdx;
    }

    /**
     * Walk {@code node} to root, collecting tag-infos in order.
     * For result extraction (POSIX offsets) and debugging.
     */
    int[] path(int node) {
        List<Integer> list = new ArrayList<>();
        while (node != 0) {
            list.add(info.get(node));
            node = pred.get(node);
        }
        int[] out = new int[list.size()];
        for (int i = 0; i < list.size(); i++) out[i] = list.get(list.size() - 1 - i);
        return out;
    }

    /** Pack (tag, neg) into a tag-info value. tag ≤ 0 is invalid; neg=true ⇒ nil/no-match tag. */
    static int packInfo(int tag, boolean neg) {
        if (tag <= 0) throw new IllegalArgumentException("tag must be positive: " + tag);
        return neg ? -tag : tag;
    }

    /** True if this tag-info represents a nil/no-match tag. */
    static boolean isNegative(int info) { return info < 0; }

    /** Tag number (always positive); valid only if {@code info != 0}. */
    static int tagOf(int info) { return Math.abs(info); }

    @Override public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("UTree{");
        for (int i = 0; i < info.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(i).append(":<info=").append(info.get(i))
              .append(",pred=").append(pred.get(i)).append(">");
        }
        return sb.append("}").toString();
    }
}
