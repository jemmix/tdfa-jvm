package io.github.jemmix.tdfa.tdfa;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

    final class DfaStateBuilder {
        final int id;
        final List<Range> ranges = new ArrayList<>();
        int[] finalOpsArr;  // populated during materialization
        /** Position-aware φ variants (deduped op lists); null = mask-uniform. */
        int[][] finalOpsVariants;
        /** [64] posFlags → variant index, or -1 (no accept config alive). */
        int[] finalMaskVariant;
        DfaStateBuilder(int id) { this.id = id; }
        void addRange(int lo, int hi, int target, int[] ops, int requiredMask) {
            ranges.add(new Range(lo, hi, target, ops, requiredMask));
        }
        void coalesce() {
            ranges.sort(Comparator.comparingInt(r -> r.lo));
            if (ranges.size() <= 1) return;
            List<Range> out = new ArrayList<>();
            Range cur = ranges.get(0);
            for (int i = 1; i < ranges.size(); i++) {
                Range next = ranges.get(i);
                if (next.lo == cur.hi + 1 && next.target == cur.target
                        && Arrays.equals(next.ops, cur.ops)
                        && next.requiredMask == cur.requiredMask) {
                    cur = new Range(cur.lo, next.hi, cur.target, cur.ops, cur.requiredMask);
                } else {
                    out.add(cur); cur = next;
                }
            }
            out.add(cur);
            ranges.clear();
            ranges.addAll(out);
        }
        /**
         * Sort ranges so that for the same lo, ranges with more assertion bits in requiredMask
         * come first. This ensures the runner tries assertion-gated transitions before ungated
         * ones — e.g. for a*(^a) at pos 0, the BEGIN_TEXT-gated transition (leading to accept)
         * must be tried before the mask=0 loop transition (which would skip past the accept).
         */
        void sortByMaskSpecificity() {
            ranges.sort((a, b) -> {
                int cmp = Integer.compare(a.lo, b.lo);
                if (cmp != 0) return cmp;
                int bc = Integer.compare(Integer.bitCount(b.requiredMask), Integer.bitCount(a.requiredMask));
                if (bc != 0) return bc;
                // dead markers precede live ranges at equal specificity: a
                // more-specific context's DEAD must block a less-specific
                // context's live range for the same symbol cell.
                return Integer.compare(a.target >= 0 ? 1 : 0, b.target >= 0 ? 1 : 0);
            });
        }
    }
