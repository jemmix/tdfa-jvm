package io.github.jemmix.tdfa.core.dfa;

import io.github.jemmix.tdfa.core.budget.Budgets;
import io.github.jemmix.tdfa.core.budget.StreamWindowOverflowException;
import io.github.jemmix.tdfa.core.engine.ChunkSource;
import io.github.jemmix.tdfa.core.engine.MatchResult;
import io.github.jemmix.tdfa.core.engine.MatchScratch;
import io.github.jemmix.tdfa.core.engine.TagTree;

import java.util.Arrays;

/**
 * A streaming matcher over one chunk source (see
 * {@code docs/streaming-input.md} in the repository root): pull-based
 * leftmost-first search over an input that arrives in chunks, without
 * materializing the whole input as a String. Created through
 * {@code Pattern.streamingMatcher(source)} on either facade or
 * {@link TdfaRunner#streamingMatcher(ChunkSource)} at the core tier;
 * both engines (interpreter and ASM-generated) serve it through the one
 * driver, so answers are identical by construction.
 *
 * <p><b>Use.</b> {@link #next()} advances to the next confirmed match
 * (pulling chunks as needed) and returns false when the stream is
 * exhausted; {@link #result()} is the immutable snapshot (absolute
 * UTF-16 stream offsets, every group, multi-valued readouts), valid
 * until the next {@code next()}. {@link #group(int)} /
 * {@link #group(String)} slice the retained window — the text of a
 * reported match stays readable while the search stands at it, and a
 * slice past what the window retains fails with
 * {@link IllegalStateException} naming the span (copy at report time if
 * you need it longer). Matches are reported as soon as they are
 * <b>decidable</b>: a match ending strictly before the frontier is
 * confirmed mid-stream; everything else waits for the bytes that decide
 * it, exactly the String surface's answers over the growing prefix.
 *
 * <p><b>Contract.</b> One matcher per stream, single-threaded — the
 * {@code Matcher} shape. The window retains the undecided search's
 * input (the leftmost undecided match start through the frontier) and
 * compacts past it on every advance; its size is bounded by
 * {@value Budgets#STREAM_WINDOW_PROP} UTF-16 units, and exceeding that
 * fails the stream with {@link StreamWindowOverflowException} — never a
 * silent spill. Whole-input {@code matches()}-shaped queries are not
 * offered (decidable only at EOF; express them as find with
 * {@code \A...\z} anchors), and neither are streaming
 * {@code split}/{@code replaceAll} (they materialize output by nature).
 *
 * <p><b>State discipline.</b> This class is a per-call carrier in the
 * runner-immutability sense ({@code docs/runner-immutability.md}): all
 * mutable state of one stream's search lives here, on the thread that
 * owns the stream; the engine and its artifact stay frozen.
 */
public final class StreamMatcher implements CharSequence {

    private final TdfaRunner runner;
    private final ChunkSource source;
    private final long windowBudget;
    /** This stream's scratch carrier (simulation buffers; the walk's pooled regs/tree). */
    final MatchScratch scratch = new MatchScratch();

    // ===== window: absolute-coordinate retained input =====
    private char[] buf = new char[256];
    private int bufLen;
    /** Absolute position of buf[0]. */
    private int base;
    /** Absolute frontier: units arrived so far (== length()). */
    private int frontier;
    private boolean eof;

    // ===== search state (written only by the driver in TdfaRunner) =====
    boolean done;
    /** Next candidate start to try (absolute). */
    int from;
    /** The search's origin: candidates past it get the enumeration gates. */
    int epochFrom;
    private MatchResult last;
    /** Literal-rung scan position (absolute); == epochFrom when idle. */
    int scanPos;
    /** EOF endgame scan ran for the current epoch (its W floor applied). */
    boolean eofScanned;

    // ===== active walk (see TdfaRunner.streamWalkRun) =====
    boolean walkActive;
    int walkStart;
    int walkState;
    int walkPos;
    int[] walkRegs;
    TagTree walkTree;
    int walkLastAccept = -1;
    boolean walkHaveAccept;
    /** Position-flags cache for walkPos; -1 = not computed. */
    int walkPosFlags = -1;
    /** Accept protocol completed at walkPos (resume skips the record). */
    boolean walkAcceptDone;

    StreamMatcher(TdfaRunner runner, ChunkSource source) {
        if (source == null) {
            throw new NullPointerException("source must be non-null");
        }
        this.runner = runner;
        this.source = source;
        this.windowBudget = Math.max(Budgets.streamWindowUnits(), 16L);
    }

    /**
     * Advance to the next confirmed match, pulling chunks as the search
     * needs them. Returns false exactly once — the stream is exhausted
     * (or the window budget tripped, loudly).
     *
     * @return whether a new match stands at {@link #result()}
     */
    public boolean next() {
        return runner.streamAdvance(this);
    }

    /**
     * The current match (absolute stream offsets; all groups), or null
     * before the first {@link #next()} / after exhaustion.
     */
    public MatchResult result() {
        return last;
    }

    /** Number of capturing groups (excluding group 0). */
    public int groupCount() {
        return runner.groupCount();
    }

    /**
     * Text of {@code group} in the current match, sliced from the
     * retained window; {@code null} for a non-participating group.
     * @throws IndexOutOfBoundsException group index out of range
     * @throws IllegalStateException no match stands, or the window no
     *         longer retains the span
     */
    public String group(int group) {
        MatchResult r = ensureMatch();
        int s = r.start(group);
        int e = r.end(group);
        if (s < 0 || e < 0) {
            return null;
        }
        return slice(s, e);
    }

    /** Named-group form of {@link #group(int)}. */
    public String group(String name) {
        ensureMatch();
        Integer idx = runner.namedGroups().get(name);
        if (idx == null) {
            throw new IllegalArgumentException("group '" + name + "' not found");
        }
        return group(idx);
    }

    // ===== CharSequence: absolute-coordinate window view =====
    //
    // The sequence addresses the WHOLE stream (index == absolute unit
    // position, length() == frontier); indices below the compaction base
    // are dropped input and fail with IndexOutOfBoundsException — the
    // loud dropped-chunk rejection, never a wrong slice. Only the
    // streaming driver and this class's own readers use this view; it is
    // not handed to general-purpose CharSequence machinery.

    @Override
    public int length() {
        return frontier;
    }

    @Override
    public char charAt(int index) {
        if (index < base || index >= frontier) {
            throw new IndexOutOfBoundsException(
                "index: " + index + ", window: [" + base + ", " + frontier + ") — dropped input cannot be re-read");
        }
        return buf[index - base];
    }

    @Override
    public CharSequence subSequence(int start, int end) {
        return slice(start, end);
    }

    @Override
    public String toString() {
        return slice(base, frontier);
    }

    private String slice(int s, int e) {
        if (s < base || e > frontier || s > e) {
            throw new IllegalStateException("span [" + s + "," + e + ") outside the retained window [" + base + ","
                + frontier + ") — the window compacted past it; copy group text at report time");
        }
        return new String(buf, s - base, e - s);
    }

    private MatchResult ensureMatch() {
        if (last == null) {
            throw new IllegalStateException("no match stands — advance with next() first");
        }
        return last;
    }

    // ===== window mechanics (called by the driver in TdfaRunner) =====

    boolean atEof() {
        return eof;
    }

    /**
     * Pull one chunk (false = end of input), appending it to the window
     * under the budget. Compacts to the current retention floor first
     * (everything before the next candidate start; see the driver).
     */
    boolean fill() {
        if (eof) {
            return false;
        }
        CharSequence chunk = source.next();
        if (chunk == null) {
            eof = true;
            return false;
        }
        int n = chunk.length();
        if (n == 0) {
            throw new IllegalArgumentException("ChunkSource returned an empty chunk — contract: non-empty or null");
        }
        compactTo(Math.max(from - 1, 0));
        long needed = bufLen + (long) n;
        if (needed > windowBudget) {
            throw new StreamWindowOverflowException(needed, windowBudget);
        }
        if (frontier + (long) n > Integer.MAX_VALUE) {
            throw new IllegalStateException(
                "stream exceeds int-indexable length (2^31-1 UTF-16 units) — match offsets could not be represented");
        }
        long cap = Math.min(Math.max(2L * buf.length, needed), windowBudget);
        int grow = (int) Math.max(cap, needed);
        if (grow > buf.length) {
            char[] nb = new char[grow];
            System.arraycopy(buf, 0, nb, 0, bufLen);
            buf = nb;
        }
        for (int i = 0; i < n; i++) {
            buf[bufLen + i] = chunk.charAt(i);
        }
        bufLen += n;
        frontier += n;
        return true;
    }

    /** Drop everything before {@code floor} (absolute). */
    void compactTo(int floor) {
        int f = Math.min(floor, frontier);
        if (f <= base) {
            return;
        }
        int rel = f - base;
        if (rel >= bufLen) {
            bufLen = 0;
        } else {
            System.arraycopy(buf, rel, buf, 0, bufLen - rel);
            bufLen -= rel;
        }
        base = f;
    }

    /** Record a confirmed match and advance the search past it. */
    void report(MatchResult r) {
        last = r;
        int end = r.end(0);
        from = end == r.start(0) ? end + 1 : end;
        epochFrom = from;
        scanPos = from;
        eofScanned = false;
        walkActive = false;
    }

    /** Seed a fresh single-start walk at {@code start}. */
    void startWalk(int start, int regSize, boolean multi) {
        walkStart = start;
        walkState = runner.startState;
        walkPos = start;
        if (regSize > 0) {
            walkRegs = scratch.takeRegs(regSize);
            Arrays.fill(walkRegs, 0, regSize, -1);
        } else {
            walkRegs = null;
        }
        walkTree = multi ? scratch.takeTree() : null;
        walkLastAccept = -1;
        walkHaveAccept = false;
        walkPosFlags = -1;
        walkAcceptDone = false;
        walkActive = true;
    }
}
