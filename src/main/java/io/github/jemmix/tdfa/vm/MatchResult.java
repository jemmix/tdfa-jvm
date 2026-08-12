package io.github.jemmix.tdfa.vm;

/**
 * A successful match. Per-tag offsets are stored in the final-register block of the
 * runtime register file (R_f at indices [{@code tags .. 2*tags - 1}]). Whole-match
 * bounds are passed separately by the runner.
 */
public final class MatchResult {
    private final int[] regs;
    /** Offset of the final-register block within {@link #regs}. Tag {@code t}'s value
     *  lives at {@code regs[finalRegBase + t - 1]}. Defaults to {@code tagCount} (working
     *  registers [0..T-1], final registers [T..2T-1]); may differ after BT22 §6.3
     *  register optimizations consolidate the working space. */
    private final int finalRegBase;
    private final int groupCount;
    private final int matchStart;
    private final int matchEnd;

    public MatchResult(int[] regs, int finalRegBase, int groupCount, int matchStart, int matchEnd) {
        this.regs = regs;
        this.finalRegBase = finalRegBase;
        this.groupCount = groupCount;
        this.matchStart = matchStart; this.matchEnd = matchEnd;
    }

    public int groupCount() { return groupCount; }

    /** Tag t (1-indexed). Tag 2i-1 = open of group i, tag 2i = close of group i. */
    public int tag(int t) {
        return regs[finalRegBase + (t - 1)];
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

    /**
     * Apply BT22 §6.4 fixed-tag reconstruction in place on {@code regs}.
     * <p>For each tag {@code t} with {@code fixedBase[t] != 0}, set
     * {@code regs[finalRegBase + t - 1]} to {@code baseVal - fixedOffset[t]} if the
     * base tag's slot is non-NIL, else NIL. Base tags are never themselves fixed,
     * so iteration order doesn't matter.
     * <p>No-op if {@code fixedBase == null} (no tags were fixed for this regex).
     *
     * @param finalRegBase offset of the final-register block within {@code regs}
     *                     (passed by runners as {@code tdfa.finalRegBase}, which may
     *                     differ from {@code tdfa.tagCount} after §6.3 register opts)
     */
    public static void reconstructFixed(int[] regs, int finalRegBase, int[] fixedBase, int[] fixedOffset) {
        if (fixedBase == null) return;
        int tagCount = fixedBase.length - 1;
        for (int t = 1; t <= tagCount; t++) {
            int base = fixedBase[t];
            if (base != 0) {
                int baseVal = regs[finalRegBase + base - 1];
                regs[finalRegBase + t - 1] = baseVal < 0 ? -1 : baseVal - fixedOffset[t];
            }
        }
    }
}
