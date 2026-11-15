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

    /** A character class as parsed — {@code [â¦]}, the items in source
     *  order: symbolic at the parse tier (the scoped {@code (?i)} and the
     *  fold-universe selection ride the node; every item carries what its
     *  expansion needs) — and the meaning-resolution pass flattens it to a
     *  {@link CharClass}: the {@code \s} table from the compile's {@code
     *  ClassTable} component, the Unicode twins from the provider, the
     *  class fold (the written-range/provider reading vs the orbit
     *  closure) under the SHORTHAND_FOLD policy. The item markers
     *  reproduce the parse-time accumulation exactly: a WRITTEN range
     *  ({@code a-z}, dashed in the source) folds by the provider's
     *  range semantics where pinned, everything else by the per-member
     *  orbit closure, and shorthand-derived items skip the fold on the
     *  non-folding lane (java.util.regex keeps the predefined classes at
     *  their plain tables under every fold universe). Post-resolution
     *  consumers never see one. */
    public static final class ClassExpr extends Ast {
        /** The class items in source order (never empty — {@code []} is a
         *  syntax error and {@code []]} makes the bracket a member). */
        public final List<Item> items;
        /** The {@code [^...]} caret. */
        public final boolean negated;
        /** The scoped {@code (?i)} state captured at the closing bracket. */
        public final boolean ci;
        /** The full-universe selection captured at the closing bracket (the
         *  class fold's universe). */
        public final boolean uni;

        public ClassExpr(List<Item> items, boolean negated, boolean ci, boolean uni) {
            this.items = items;
            this.negated = negated;
            this.ci = ci;
            this.uni = uni;
        }

        @Override
        public String toString() {
            return negated ? "[^...]" : "[...]";
        }

        /** One class item — a marker hierarchy, deliberately NOT an
         *  {@link Ast}: the class is a leaf from every walker's
         *  perspective until resolution flattens it. */
        public static abstract class Item {
            Item() {
            }
        }

        /** A literal member or written range: {@code lo-hi} ({@code hi ==
         *  lo} for a single member); {@code written} marks a DASHED range
         *  (degenerate {@code [x-x]} included) — java.util.regex folds
         *  written ranges by the two-sided image test while single members
         *  take the orbit closure. */
        public static final class Member extends Item {
            public final int lo;
            public final int hi;
            public final boolean written;

            public Member(int lo, int hi, boolean written) {
                this.lo = lo;
                this.hi = hi;
                this.written = written;
            }
        }

        /** A materialized range set the GRAMMAR owns or the parse already
         *  resolved: POSIX classes, the JDK {@code \v}/{@code \h} tables,
         *  property classes ({@code \p{...}} — a provider lookup is a
         *  compile input, not a policy application). {@code shorthand}
         *  marks the shorthand-derived sets ({@code \v}/{@code \h}) so the
         *  class fold skips them on the non-folding lane like every other
         *  shorthand item. */
        public static final class Table extends Item {
            public final int[] ranges;
            public final boolean shorthand;

            public Table(int[] ranges, boolean shorthand) {
                this.ranges = ranges;
                this.shorthand = shorthand;
            }
        }

        /** An in-class shorthand twin ({@code [\d] [\w] [\s]} and the
         *  uppercase complements): symbolic — the scoped state rides the
         *  item, the pass materializes it (the {@code \s} table, the
         *  Unicode twins, the folded complement on the folding lane). */
        public static final class Shorthand extends Item {
            public final char kind;
            public final boolean negated;
            public final boolean unicode;
            public final boolean ci;
            public final boolean uni;

            public Shorthand(char kind, boolean negated, boolean unicode, boolean ci, boolean uni) {
                this.kind = kind;
                this.negated = negated;
                this.unicode = unicode;
                this.ci = ci;
                this.uni = uni;
            }
        }
    }

    /** The zero-width position assertions, unified — one node over a
     *  closed named vocabulary ({@link AssertionKind}). The kinds the
     *  parse can name are resolved MEANINGS already ({@code \A} is
     *  text-start, {@code ^} under {@code (?m)} is line-start); the one
     *  policy-dependent reading — a bare {@code $} outside multiline —
     *  parses as {@link AssertionKind#DOLLAR} and the meaning-resolution
     *  pass rewrites it against the compile's END_OF_TEXT_ONLY policy
     *  ({@code \z} vs java.util.regex's before-final-terminator reading),
     *  so the TNFA builder never consults a policy: kinds map one-to-one
     *  onto its anchor bits. New assertion kinds land with their support
     *  (the vocabulary tracks capability — see
     *  docs/engine-compat-surface.md). */
    public static final class Assertion extends Ast {
        /** The named reading. */
        public final AssertionKind kind;

        public Assertion(AssertionKind kind) {
            this.kind = kind;
        }

        @Override
        public String toString() {
            return kind.toString();
        }
    }

    /** The closed assertion vocabulary. */
    public enum AssertionKind {
        /** {@code \A}, or a bare {@code ^} parsed outside multiline: position 0 only. */
        TEXT_START("\\A"),
        /** {@code ^} under {@code (?m)}: line begin, terminator-set-aware at match time. */
        LINE_START("^m"),
        /** {@code \z}: end of input only. */
        TEXT_END("\\z"),
        /** {@code $} under {@code (?m)}: line end, terminator-set-aware at match time. */
        LINE_END("$m"),
        /** java.util.regex's {@code \Z} (and the plain-{@code $} reading): end of
         *  input, or immediately before the final terminator run. */
        FINAL_END("\\Z"),
        /** A bare {@code $} outside multiline — the POLICY-pending kind: the
         *  meaning-resolution pass rewrites it to {@link #TEXT_END} or
         *  {@link #FINAL_END} against END_OF_TEXT_ONLY; it never reaches the
         *  TNFA builder. */
        DOLLAR("$"),
        /** {@code \b}: a word/non-word boundary. */
        WORD_BOUNDARY("\\b"),
        /** {@code \B}: the complement of {@link #WORD_BOUNDARY}. */
        NON_WORD_BOUNDARY("\\B"),
        /** {@code \G}: the end of the previous match (java's {@code oldLast}
         *  protocol — the op's start when no previous match, the last match's
         *  end otherwise, unchanged by failed searches). An op-constant BASE,
         *  like the region's anchoring bounds: the bit rides the generic
         *  walk's threaded bases, never the position-flag tables. */
        PREV_MATCH_END("\\G");

        private final String symbol;

        AssertionKind(String symbol) {
            this.symbol = symbol;
        }

        @Override
        public String toString() {
            return symbol;
        }
    }

    /** The bare-dot atom as parsed: the engine's one syntax whose MEANING is
     *  a compile policy — everything under the scoped {@code (?s)} state the
     *  node carries, the compile's terminator-set complement otherwise.
     *  Symbolic by design: the meaning-resolution pass
     *  ({@code io.github.jemmix.tdfa.core.semantic.Resolve}) expands it into a
     *  {@link CharClass} against the compile's {@code Terminators} value, so
     *  the parser needs no table axes and the TNFA builder (and every
     *  post-resolution consumer) never sees one. */
    public static final class Dot extends Ast {
        /** The scoped {@code (?s)} state captured at the atom. */
        public final boolean dotall;

        public Dot(boolean dotall) {
            this.dotall = dotall;
        }

        @Override
        public String toString() {
            return dotall ? "(?s)." : ".";
        }
    }

    /** A literal under an active {@code (?i)} at a site whose reading
     *  folds: symbolic at the parse tier — the scoped fold-universe
     *  selection ({@code unicodeCase || unicodeShorthand} at the atom,
     *  upgraded by the scoped {@code (?u)}/{@code (?U)} letters) rides the
     *  node — and the meaning-resolution pass expands the orbit (or demotes
     *  to the plain literal when the universe has no counterpart for the
     *  codepoint). Post-resolution consumers never see one. */
    public static final class FoldLiteral extends Ast {
        /** The literal codepoint (supplementary included). */
        public final int cp;
        /** The full-universe selection captured at the atom. */
        public final boolean uni;

        public FoldLiteral(int cp, boolean uni) {
            this.cp = cp;
            this.uni = uni;
        }

        @Override
        public String toString() {
            return "\\i<" + Integer.toHexString(cp) + ">";
        }
    }

    /** A predefined-class escape atom ({@code \d \D \w \W \s \S} outside a
     *  character class): symbolic at the parse tier — the scoped state the
     *  expansion needs (Unicode shorthand under {@code (?u)}, the active
     *  {@code (?i)}, the fold-universe selection) rides the node — and the
     *  meaning-resolution pass materializes it: the {@code \s} table from
     *  the compile's {@code ClassTable} component, the Unicode twins from
     *  the provider, the word orbit under the SHORTHAND_FOLD policy.
     *  Post-resolution consumers never see one. */
    public static final class Shorthand extends Ast {
        /** The lowercase base letter: {@code 'd'}, {@code 'w'} or {@code 's'}. */
        public final char kind;
        /** The uppercase form ({@code \D \W \S}). */
        public final boolean negated;
        /** The scoped {@code (?u)} state captured at the atom. */
        public final boolean unicode;
        /** The scoped {@code (?i)} state captured at the atom. */
        public final boolean ci;
        /** The full-universe selection captured at the atom (the word fold). */
        public final boolean uni;

        public Shorthand(char kind, boolean negated, boolean unicode, boolean ci, boolean uni) {
            this.kind = kind;
            this.negated = negated;
            this.unicode = unicode;
            this.ci = ci;
            this.uni = uni;
        }

        @Override
        public String toString() {
            return "\\" + (negated ? Character.toUpperCase(kind) : kind);
        }
    }

    /** Tag (capture-group boundary). Numbered 1..n.
     *  <p>{@code fixedOn} / {@code fixedOffset} are mutable annotations set by
     *  the BT22 §6.4 fixed-tags pass (see {@code io.github.jemmix.tdfa.core.ast.FixedTags}).
     *  When {@code fixedOn != 0}, this tag's position can be reconstructed at match
     *  time as {@code tag[fixedOn] - fixedOffset} (or NIL if the base is NIL), so
     *  the tag is omitted from NFA construction and register allocation.
     *  <p>{@code dissolved} is a mutable annotation set by the meaning-resolution pass
     *  (family 6 sub-family A, the EMPTY_ITERATION_SPANS axis): the tag pair belongs to a
     *  capture dissolved by a greedy min-0 loop quantifier over an
     *  all-zero-width deterministic body — java.util.regex's {@code GroupCurly}
     *  rolls the zero-width iteration back to the pre-curly bounds, so on the
     *  java.util.regex reading the pair reports NIL at every accept (the determinizer's φ
     *  override; see {@code Tnfa#dissolvedTags}). The fixed-tags pass keeps a
     *  dissolved tag un-fixed and base-free: its final value is protocol-NIL,
     *  not a reconstructable distance.
     *  <p>{@code surfaceFinal} is the family-6 sub-family B twin: the pair
     *  belongs to a capture under an UNBOUNDED greedy quantifier over an
     *  empty-capable non-deterministic body — java.util.regex's
     *  {@code Prolog}+{@code Loop} reports the final zero-width iteration,
     *  which the determinizer's same-position closure subsumption cuts; the
     *  accepting kernels where the cut fired surface the pair's writes as a
     *  φ SET_POS at the accept position (see {@code Tnfa#surfaceFinalTags}).*/
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
        /**
         * Finding 2 (campaign 2026-10-16): java.util.regex compiles a
         * bounded {@code {n,m}} / {@code {n,}} over a body that can match
         * empty through a choice point as {@code Prolog}+{@code Loop},
         * whose zero-width guard ({@code i > locals[begin]}) forces the
         * loop to EXIT after any zero-width iteration — even under
         * {@code cmin} (the count check sits inside the guard). The NFA
         * builder splits every desugared copy's zero-width paths to the
         * quantifier exit (see {@code Tnfa.Builder}'s BOUNDED_ZWE frame).
         * Set by the meaning-resolution pass on the JUR reading only;
         * all-zero-width deterministic bodies stay unmarked — java's
         * GroupCurly COMMITS those (no guard), and the plain structural
         * desugar already agrees.
         */
        public boolean zeroWidthExit;

        /**
         * Parse-time structural fact, set at the capturing group's
         * {@code ')'}: the directly quantified atom was that CAPTURE's
         * tag-pair product — {@code (x)*} yes, {@code (?:(x))*} no (the
         * same resolved tree shape, a different quantified atom). The
         * meaning-resolution pass reads it with the
         * EMPTY_ITERATION_SPANS policy to place the family-6 marks
         * ({@link Tag#dissolved}/{@link Tag#surfaceFinal}): the mark
         * belongs to the capture the quantifier rides, never to a
         * transparent wrapper around one ({@code (\b)*} dissolves,
         * {@code (?:(\b))*} keeps the span).
         */
        public boolean quantifiedCapture;

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
}
