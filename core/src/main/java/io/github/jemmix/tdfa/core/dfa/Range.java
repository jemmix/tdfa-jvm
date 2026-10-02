package io.github.jemmix.tdfa.core.dfa;

/**
 * One transition range in a builder state; ops rewritten in place by CFG optimization.
 */
public final class Range {
    public final int lo, hi, target;
    public final int requiredMask;
    public int[] ops; // non-final: rewritten in place by CFG optimization (BT22 §6.3)

    public Range(int lo, int hi, int target, int[] ops, int requiredMask) {
        this.lo = lo;
        this.hi = hi;
        this.target = target;
        this.ops = ops;
        this.requiredMask = requiredMask;
    }
}
