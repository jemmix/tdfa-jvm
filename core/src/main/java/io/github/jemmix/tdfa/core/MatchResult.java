package io.github.jemmix.tdfa.core;

/**
 * A successful match: an immutable snapshot of the whole-match bounds and
 * every capture tag's offsets. Per-tag offsets are stored in the
 * final-register block of the runtime register file (R_f at indices
 * {@code [tags .. 2*tags - 1]}); whole-match bounds are passed separately by
 * the runner.
 *
 * <p><b>NIL convention:</b> a group (or tag) that did not participate in the
 * match reports {@code -1} for both its start and end — there is no separate
 * "matched" flag; {@code start(g) == -1} IS the test. Whole-match bounds are
 * always {@code >= 0}.
 *
 * <p><b>Thread safety:</b> immutable; safe to share across threads.
 *
 * <p><b>Engine-surface members:</b> the constructor, {@link #reconstructFixed}
 * and {@code raw()} are consumed by the engines — including ASM-GENERATED
 * classes, which resolve them by name at runtime. They are public for that
 * mechanical reason only and are NOT part of the user API; their signatures
 * are frozen with the emitted-bytecode surface (rename = linkage error in
 * generated code).
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

    /** Engine surface (see class doc): builds a snapshot over the runner's
     *  register file. {@code regs} is retained, not copied — runners hand
     *  over ownership of a per-match array. */
    public MatchResult(int[] regs, int finalRegBase, int groupCount, int matchStart, int matchEnd) {
        this.regs = regs;
        this.finalRegBase = finalRegBase;
        this.groupCount = groupCount;
        this.matchStart = matchStart;
        this.matchEnd = matchEnd;
    }

    /** Number of capturing groups, excluding group 0. */
    public int groupCount() { return groupCount; }

    /** Tag {@code t} (1-indexed; tag 2i-1 = open of group i, tag 2i = close of
     *  group i). Valid range {@code [1, 2*groupCount()]}; {@code -1} = unset (NIL).
     * @throws IndexOutOfBoundsException outside the valid range. */
    public int tag(int t) {
        if (t < 1 || t > 2 * groupCount)
            throw new IndexOutOfBoundsException("tag " + t + " (valid: 1.." + 2 * groupCount + ")");
        return regs[finalRegBase + (t - 1)];
    }

    /** Start offset (inclusive) of {@code group} (0 = whole match); {@code -1} = unset (NIL).
     * @throws IndexOutOfBoundsException outside {@code [0, groupCount()]}. */
    public int start(int group) {
        if (group < 0 || group > groupCount) throw new IndexOutOfBoundsException("group " + group);
        if (group == 0) return matchStart;
        return tag(2 * (group - 1) + 1);
    }

    /** End offset (exclusive) of {@code group} (0 = whole match); {@code -1} = unset (NIL).
     * @throws IndexOutOfBoundsException outside {@code [0, groupCount()]}. */
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

    /** Engine surface (see class doc): the live register array backing this snapshot. */
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
