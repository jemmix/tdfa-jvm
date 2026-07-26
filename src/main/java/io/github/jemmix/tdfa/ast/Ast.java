package io.github.jemmix.tdfa.ast;

import java.util.List;

/** Base class for regex AST. */
public abstract class Ast {
    @Override public abstract String toString();

    public static final class Empty extends Ast {
        @Override public String toString() { return "\u03B5"; }
    }

    public static final class Symbol extends Ast {
        public final char c;
        public Symbol(char c) { this.c = c; }
        @Override public String toString() { return String.valueOf(c); }
    }

    /** Tag (capture-group boundary). Numbered 1..n. */
    public static final class Tag extends Ast {
        public final int tag;
        public Tag(int tag) { this.tag = tag; }
        @Override public String toString() { return Integer.toString(tag); }
    }

    public static final class Concat extends Ast {
        public final List<Ast> children;
        public Concat(List<Ast> children) { this.children = children; }
        @Override public String toString() { return children.toString(); }
    }

    public static final class Alt extends Ast {
        public final List<Ast> children;
        public Alt(List<Ast> children) { this.children = children; }
        @Override public String toString() { return "Alt" + children; }
    }

    /** Generalized repetition e^{n,m}. m == Integer.MAX_VALUE means unbounded. */
    public static final class Repeat extends Ast {
        public final Ast body;
        public final int min, max;
        public final boolean greedy;
        public Repeat(Ast body, int min, int max, boolean greedy) {
            this.body = body; this.min = min; this.max = max; this.greedy = greedy;
        }
        @Override public String toString() {
            return body + "{" + min + "," + (max == Integer.MAX_VALUE ? "" : max) + "}" + (greedy ? "" : "?");
        }
    }

    public static final class StartAnchor extends Ast {
        @Override public String toString() { return "^"; }
    }

    public static final class EndAnchor extends Ast {
        @Override public String toString() { return "$"; }
    }

    /** Word boundary assertion `\b`. Zero-width: true at any position where
     *  {@code isWord(prev) != isWord(curr)}. */
    public static final class WordBoundary extends Ast {
        @Override public String toString() { return "\\b"; }
    }

    /** Non-word-boundary assertion `\B`. Zero-width: complement of {@link WordBoundary}. */
    public static final class NoWordBoundary extends Ast {
        @Override public String toString() { return "\\B"; }
    }
}
