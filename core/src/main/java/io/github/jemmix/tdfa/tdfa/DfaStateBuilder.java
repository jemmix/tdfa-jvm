package io.github.jemmix.tdfa.tdfa;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

final class DfaStateBuilder {
    final List<Range> ranges = new ArrayList<>();
    /**
     * The whole-walk transition list (the partial-whole side table): the
     * state's FULL uncut relation — every (context, cell) entry, with the
     * cut contexts' entries pointing at their uncut targets instead of the
     * pike-cut ones. Null until some context of this state cuts, so the
     * overwhelming majority of states (no cut) never allocate it and the
     * whole walk falls back to {@link #ranges}, which is then identical to
     * the uncut relation. Maintained in lockstep with {@link #ranges} by
     * the emission sweep; coalesce/sort run over it exactly as over the
     * pruned list.
     */
    List<Range> wholeRanges;
    int[] finalOpsArr; // populated during materialization
    /**
     * Position-aware φ variants (deduped op lists); null = mask-uniform.
     */
    int[][] finalOpsVariants;
    /**
     * [64] posFlags → variant index, or -1 (no accept config alive).
     */
    int[] finalMaskVariant;

    /**
     * Append one range entry to {@code list}, coalescing INLINE with the
     * previous entry when it is the immediately adjacent cell run with
     * identical (target, ops, requiredMask). Determinization emits per
     * breakpoint cell in ascending order (per assertion context, which
     * requiredMask separates), so adjacency-merge at append time keeps the
     * LIVE boxed range count at the post-coalesce total throughout the
     * sweep — the figure the compile RAM budget charges ({@link
     * BudgetWeights#RANGE_BOXED_BYTES} per new range), instead of one
     * boxed object per cell pending the materialization-time coalesce.
     *
     * @return true iff a NEW live range was created (the caller's charge
     * signal); false when merged into the previous entry.
     */
    boolean addRange(int lo, int hi, int target, int[] ops, int requiredMask) {
        return addRange(ranges, lo, hi, target, ops, requiredMask);
    }

    static boolean addRange(List<Range> list, int lo, int hi, int target, int[] ops, int requiredMask) {
        if (!list.isEmpty()) {
            Range last = list.get(list.size() - 1);
            if (last.hi == lo - 1 && last.target == target && last.requiredMask == requiredMask
                && Arrays.equals(last.ops, ops)) {
                list.set(list.size() - 1, new Range(last.lo, hi, target, ops, requiredMask));
                return false;
            }
        }
        list.add(new Range(lo, hi, target, ops, requiredMask));
        return true;
    }

    void coalesce() {
        coalesce(ranges);
        if (wholeRanges != null) {
            coalesce(wholeRanges);
        }
    }

    static void coalesce(List<Range> list) {
        list.sort(Comparator.comparingInt(r -> r.lo));
        if (list.size() <= 1) {
            return;
        }
        List<Range> out = new ArrayList<>();
        Range cur = list.get(0);
        for (int i = 1; i < list.size(); i++) {
            Range next = list.get(i);
            if (next.lo == cur.hi + 1 && next.target == cur.target && Arrays.equals(next.ops, cur.ops)
                && next.requiredMask == cur.requiredMask) {
                cur = new Range(cur.lo, next.hi, cur.target, cur.ops, cur.requiredMask);
            } else {
                out.add(cur);
                cur = next;
            }
        }
        out.add(cur);
        list.clear();
        list.addAll(out);
    }

    /**
     * Sort ranges so that for the same lo, ranges with more assertion bits in requiredMask
     * come first. This ensures the runner tries assertion-gated transitions before ungated
     * ones — e.g. for a*(^a) at pos 0, the BEGIN_TEXT-gated transition (leading to accept)
     * must be tried before the mask=0 loop transition (which would skip past the accept).
     */
    void sortByMaskSpecificity() {
        sortByMaskSpecificity(ranges);
        if (wholeRanges != null) {
            sortByMaskSpecificity(wholeRanges);
        }
    }

    static void sortByMaskSpecificity(List<Range> list) {
        list.sort((a, b) -> {
            int cmp = Integer.compare(a.lo, b.lo);
            if (cmp != 0) {
                return cmp;
            }
            int bc = Integer.compare(Integer.bitCount(b.requiredMask), Integer.bitCount(a.requiredMask));
            if (bc != 0) {
                return bc;
            }
            // dead markers precede live ranges at equal specificity: a
            // more-specific context's DEAD must block a less-specific
            // context's live range for the same symbol cell.
            return Integer.compare(a.target >= 0 ? 1 : 0, b.target >= 0 ? 1 : 0);
        });
    }
}
