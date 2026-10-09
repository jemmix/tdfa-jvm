package io.github.jemmix.tdfa.core.ast;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

/** Base class for regex AST. */
public abstract class Ast {
    @Override
    public abstract String toString();

    /** Render a whole subtree with an explicit token stack: container
     *  {@code toString()} implementations must not descend through their
     *  children's {@code toString()}, because AST depth scales with pattern
     *  nesting. Output matches {@link java.util.AbstractList#toString}
     *  formatting for Concat/Alt ("[a, b, c]"). */
    static void render(Ast root, StringBuilder sb) {
        Deque<Object> tokens = new ArrayDeque<>();
        tokens.push(root);
        while (!tokens.isEmpty()) {
            Object o = tokens.pop();
            if (o instanceof String) {
                sb.append((String) o);
                continue;
            }
            Ast e = (Ast) o;
            if (e instanceof Concat || e instanceof Alt) {
                List<Ast> ch = e instanceof Concat ? ((Concat) e).children : ((Alt) e).children;
                if (e instanceof Alt) {
                    sb.append("Alt");
                }
                sb.append('[');
                tokens.push("]");
                for (int i = ch.size() - 1; i >= 0; i--) {
                    tokens.push(ch.get(i));
                    if (i > 0) {
                        tokens.push(", ");
                    }
                }
            } else if (e instanceof Repeat) {
                Repeat r = (Repeat) e;
                tokens
                    .push("{" + r.min + "," + (r.max == Integer.MAX_VALUE ? "" : r.max) + "}" + (r.greedy ? "" : "?"));
                tokens.push(r.body);
            } else {
                sb.append(e.toString());
            }
        }
    }

    public static final class Empty extends Ast {
        @Override
        public String toString() {
            return "\u03B5";
        }
    }

    public static final class Symbol extends Ast {
        public final char c;

        public Symbol(char c) {
            this.c = c;
        }

        @Override
        public String toString() {
            return String.valueOf(c);
        }
    }

    /** Tag (capture-group boundary). Numbered 1..n.
     *  <p>{@code fixedOn} / {@code fixedOffset} are mutable annotations set by
     *  the BT22 §6.4 fixed-tags pass (see {@code io.github.jemmix.tdfa.core.ast.FixedTags}).
     *  When {@code fixedOn != 0}, this tag's position can be reconstructed at match
     *  time as {@code tag[fixedOn] - fixedOffset} (or NIL if the base is NIL), so
     *  the tag is omitted from NFA construction and register allocation.
     *  <p>{@code dissolved} is a mutable annotation set by the parser (family 6
     *  sub-family A, the EMPTY_ITERATION_SPANS axis): the tag pair belongs to a
     *  capture dissolved by a greedy min-0 loop quantifier over an
     *  all-zero-width deterministic body — java.util.regex's {@code GroupCurly}
     *  rolls the zero-width iteration back to the pre-curly bounds, so on the
     *  JUR lane the pair reports NIL at every accept (the determinizer's φ
     *  override; see {@code Tnfa#dissolvedTags}). The fixed-tags pass keeps a
     *  dissolved tag un-fixed and base-free: its final value is protocol-NIL,
     *  not a reconstructable distance.
     *  <p>{@code surfaceFinal} is the family-6 sub-family B twin: the pair
     *  belongs to a capture under an UNBOUNDED greedy quantifier over an
     *  empty-capable non-deterministic body — java.util.regex's
     *  {@code Prolog}+{@code Loop} reports the final zero-width iteration,
     *  which the determinizer's same-position closure subsumption cuts; the
     *  accepting kernels where the cut fired surface the pair's writes as a
     *  φ SET_POS at the accept position (see {@code Tnfa#surfaceFinalTags}). */
    public static final class Tag extends Ast {
        public final int tag;
        public int fixedOn;
        public int fixedOffset;
        public boolean dissolved;
        public boolean surfaceFinal;

        public Tag(int tag) {
            this.tag = tag;
        }

        @Override
        public String toString() {
            return Integer.toString(tag);
        }
    }

    public static final class Concat extends Ast {
        public final List<Ast> children;

        public Concat(List<Ast> children) {
            this.children = children;
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            render(this, sb);
            return sb.toString();
        }
    }

    public static final class Alt extends Ast {
        public final List<Ast> children;

        public Alt(List<Ast> children) {
            this.children = children;
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            render(this, sb);
            return sb.toString();
        }
    }

    /** Generalized repetition e^{n,m}. m == Integer.MAX_VALUE means unbounded. */
    public static final class Repeat extends Ast {
        public final Ast body;
        public final int min, max;
        public final boolean greedy;

        public Repeat(Ast body, int min, int max, boolean greedy) {
            this.body = body;
            this.min = min;
            this.max = max;
            this.greedy = greedy;
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            render(this, sb);
            return sb.toString();
        }
    }

    /**
     * Start-of-text/line anchor: {@code ^} (line, multiline-sensitive) or {@code \A}
     * (absolute, always position 0) when {@link #absolute} is set.
     */
    public static final class StartAnchor extends Ast {
        public final boolean absolute;
        /** Parse-time (?m) flavor: line-begin (^ under m) vs text-begin. */
        public final boolean multiline;

        public StartAnchor() {
            this(false, false);
        }

        public StartAnchor(boolean absolute) {
            this(absolute, false);
        }

        public StartAnchor(boolean absolute, boolean multiline) {
            this.absolute = absolute;
            this.multiline = multiline;
        }

        @Override
        public String toString() {
            return absolute ? "\\A" : "^";
        }
    }

    /**
     * End-of-text/line anchor: {@code $} (line, multiline-sensitive) or {@code \z}
     * (absolute, always end-of-input) when {@link #absolute} is set.
     */
    public static final class EndAnchor extends Ast {
        public final boolean absolute;
        /** Parse-time (?m) flavor: line-end ($ under m) vs text-end. */
        public final boolean multiline;

        public EndAnchor() {
            this(false, false);
        }

        public EndAnchor(boolean absolute) {
            this(absolute, false);
        }

        public EndAnchor(boolean absolute, boolean multiline) {
            this.absolute = absolute;
            this.multiline = multiline;
        }

        @Override
        public String toString() {
            return absolute ? "\\z" : "$";
        }
    }

    /** Word boundary assertion `\b`. Zero-width: true at any position where
     *  {@code isWord(prev) != isWord(curr)}. */
    public static final class WordBoundary extends Ast {
        @Override
        public String toString() {
            return "\\b";
        }
    }

    /** Non-word-boundary assertion `\B`. Zero-width: complement of {@link WordBoundary}. */
    public static final class NoWordBoundary extends Ast {
        @Override
        public String toString() {
            return "\\B";
        }
    }
}
