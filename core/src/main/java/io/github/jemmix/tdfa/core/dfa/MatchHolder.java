package io.github.jemmix.tdfa.core.dfa;

import io.github.jemmix.tdfa.core.emit.EmittedSurface;
import io.github.jemmix.tdfa.core.engine.TagTree;

/**
 * Immutable match result carrier (start/end/registers). Multi-valued
 * compiles additionally carry the walk's tag-tree {@link TagTree#snapshot()
 * snapshot} — register slots then hold tree heads instead of single offsets
 * ({@code null} on single-valued compiles).
 */
@EmittedSurface
public final class MatchHolder {
    public final int matchStart, matchEnd;
    public final int[] regs;
    /**
     * Tag-tree snapshot for multi-valued compiles; {@code null} otherwise.
     * Frozen before construction, so the holder stays immutable.
     */
    public final TagTree tree;

    @EmittedSurface
    public MatchHolder(int s, int e, int[] r) {
        this(s, e, r, null);
    }

    @EmittedSurface
    public MatchHolder(int s, int e, int[] r, TagTree tree) {
        matchStart = s;
        matchEnd = e;
        regs = r;
        this.tree = tree;
    }
}
