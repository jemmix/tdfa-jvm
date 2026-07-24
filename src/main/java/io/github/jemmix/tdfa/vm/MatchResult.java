package io.github.jemmix.tdfa.vm;

/**
 * A successful match. Per-tag offsets are stored in the final-register block of the
 * runtime register file (R_f at indices [{@code tags .. 2*tags - 1}]). Whole-match
 * bounds are passed separately by the runner.
 */
public final class MatchResult {
    private final int[] regs;
    private final int tagCount;
    private final int groupCount;
    private final int matchStart;
    private final int matchEnd;

    public MatchResult(int[] regs, int tagCount, int groupCount, int matchStart, int matchEnd) {
        this.regs = regs; this.tagCount = tagCount;
        this.groupCount = groupCount;
        this.matchStart = matchStart; this.matchEnd = matchEnd;
    }

    public int groupCount() { return groupCount; }

    /** Tag t (1-indexed). Tag 2i-1 = open of group i, tag 2i = close of group i. */
    public int tag(int t) {
        return regs[tagCount + (t - 1)];
    }

    public int start(int group) {
        if (group < 0 || group > groupCount) throw new IndexOutOfBoundsException("group " + group);
        if (group == 0) return matchStart;
        return tag(2 * (group - 1) + 1);
    }

    public int end(int group) {
        if (group < 0 || group > groupCount) throw new IndexOutOfBoundsException("group " + group);
        if (group == 0) return matchEnd;
        return tag(2 * group);
    }

    public int[] groups() {
        int[] out = new int[2 * (groupCount + 1)];
        out[0] = matchStart; out[1] = matchEnd;
        for (int g = 1; g <= groupCount; g++) {
            out[2 * g] = start(g);
            out[2 * g + 1] = end(g);
        }
        return out;
    }

    int[] raw() { return regs; }
}
