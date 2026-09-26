package io.github.jemmix.tdfa.core;

/**
 * Per-matcher scratch carrier: the mutable buffers a single match operation
 * borrows — the walk register file ({@code regs}) and the multi-state
 * simulation buffers ({@code live}/{@code next} bitsets, {@code origin}/
 * {@code originNext} tracking). The java.util.regex/re2j shape: the stateful
 * {@link Matcher} owns one carrier for its lifetime and hands it down the
 * match ladder, so the buffers are reused across {@code find()} iterations,
 * shared by the interpreter and the generated tier, and released with the
 * matcher — no {@code ThreadLocal} retention on any thread (retaining grown
 * per-thread scratch would keep ~2 MB alive per thread after one 234 K-state
 * DFA, and virtual threads would pay a fresh Scratch + ThreadLocal entry
 * each).
 *
 * <p><b>Lifecycle.</b> One carrier per {@link Matcher} whose engines want
 * one (see {@code RegexEngine.wantsScratch()}) — carrier-free engines get a
 * {@code null} field and run their whole ladder without a scratch
 * allocation, the carrier-consuming fallbacks allocating a fresh carrier on
 * demand. Callers without a matcher may pass {@code null} down the engine
 * entries for the same effect. Either way the carrier is single-threaded,
 * non-reentrant, and its contents are undefined between operations.
 *
 * <p><b>Correctness contract.</b> Contents are undefined on take — callers
 * fill {@code [0, n)} before reading — and callers must clone before an
 * array escapes (walks clone into their result holder on success). The
 * returned arrays may be longer than {@code n} (grown by a capture-heavier
 * pattern or a larger DFA earlier in the carrier's life); only
 * {@code [0, n)} is meaningful, and downstream consumers (MatchHolder /
 * MatchResult) index within the pattern's sizes, never the array length.
 * Sizes are re-validated on every take; the carrier grows to the largest
 * need seen and never shrinks.
 *
 * <p><b>No nested aliasing.</b> A holder of pooled regs never calls another
 * taker while reading its own {@code [0, n)} window — the one re-entry
 * (the fast walk's non-ASCII fallback into the generic walk) returns the
 * callee's result immediately, and every taker refills before use.
 */
@EmittedSurface // class-level: generated wrappers link the no-arg ctor by descriptor
public final class MatchScratch {

    private int[] regs;
    private int[] live, next, origin, originNext;

    /**
     * Grow-only register file: returns an array of at least {@code n} ints,
     * cached in this carrier. Contents undefined; refill {@code [0, n)}
     * before reading.
     */
    public int[] takeRegs(int n) {
        int[] r = regs;
        if (r == null || r.length < n) {
            r = new int[n];
            regs = r;
        }
        return r;
    }

    /**
     * Live-set bitset buffer ({@code n} = words, not states): at least
     * {@code n} ints, cached. Contents undefined; zero/fill before use.
     */
    public int[] takeLive(int n) {
        int[] r = live;
        if (r == null || r.length < n) {
            r = new int[n];
            live = r;
        }
        return r;
    }

    /**
     * Second live-set buffer (double-buffered step set of {@link #takeLive}):
     * at least {@code n} ints, cached. Contents undefined; zero before use.
     */
    public int[] takeNext(int n) {
        int[] r = next;
        if (r == null || r.length < n) {
            r = new int[n];
            next = r;
        }
        return r;
    }

    /**
     * Per-state origin tracking buffer ({@code n} = state count): at least
     * {@code n} ints, cached. Contents undefined; seed before use.
     */
    public int[] takeOrigin(int n) {
        int[] r = origin;
        if (r == null || r.length < n) {
            r = new int[n];
            origin = r;
        }
        return r;
    }

    /**
     * Second origin buffer (double-buffered with {@link #takeOrigin}): at
     * least {@code n} ints, cached. Contents undefined; written under bit
     * cover of the paired step set, never read stale.
     */
    public int[] takeOriginNext(int n) {
        int[] r = originNext;
        if (r == null || r.length < n) {
            r = new int[n];
            originNext = r;
        }
        return r;
    }
}
