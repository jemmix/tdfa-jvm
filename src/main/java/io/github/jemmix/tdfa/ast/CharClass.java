package io.github.jemmix.tdfa.ast;

/**
 * Character class: lo/hi ranges (flattened). Negated if {@code negated}.
 * Matches one char per evaluation.
 */
public final class CharClass extends Ast {
    public final int[] ranges; // flattened lo0,hi0,lo1,hi1,...; hi inclusive
    public final boolean negated;

    public CharClass(int[] ranges, boolean negated) {
        this.ranges = ranges; this.negated = negated;
    }

    public boolean matches(char c) {
        for (int i = 0; i < ranges.length; i += 2) {
            if (c >= ranges[i] && c <= ranges[i + 1]) return !negated;
        }
        return negated;
    }

    @Override public String toString() {
        StringBuilder sb = new StringBuilder(negated ? "[^" : "[");
        for (int i = 0; i < ranges.length; i += 2) {
            if (i > 0) sb.append(',');
            sb.append((char) ranges[i]).append('-').append((char) ranges[i + 1]);
        }
        return sb.append(']').toString();
    }
}
