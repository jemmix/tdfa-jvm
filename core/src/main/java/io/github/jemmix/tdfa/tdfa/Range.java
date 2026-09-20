package io.github.jemmix.tdfa.tdfa;

/**
 * One transition range in a builder state; ops rewritten in place by CFG optimization.
 */
final class Range {
    final int lo, hi, target;
    final int requiredMask;
    int[] ops;  // non-final: rewritten in place by CFG optimization (BT22 §6.3)

    Range(int lo, int hi, int target, int[] ops, int requiredMask) {
        this.lo = lo;
        this.hi = hi;
        this.target = target;
        this.ops = ops;
        this.requiredMask = requiredMask;
    }
}
