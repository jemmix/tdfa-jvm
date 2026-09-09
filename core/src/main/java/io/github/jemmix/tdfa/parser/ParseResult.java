package io.github.jemmix.tdfa.parser;

import io.github.jemmix.tdfa.ast.Ast;

import java.util.Map;

/**
 * The full output of parsing a pattern: the AST plus every fact the later
 * pipeline stages need that is not derivable from the AST alone — tag and
 * group numbering, effective scanner flags, and named-group metadata.
 *
 * <p>Replaces the former {@code Parser.capture()} side-channel (a Parser
 * instance whose mutable counters were read after parsing).
 *
 * <p>Components: {@code ast} is the parsed syntax tree (anchors injected if
 * requested); {@code tagCount} counts capture tags (2 per group; 1-indexed);
 * {@code groupCount} counts capturing groups (excluding group 0);
 * {@code multiline} mirrors {@code (?m)} ({@code ^}/{@code $} at line
 * boundaries); {@code unicodeShorthand} mirrors {@code (?u)} (Unicode-aware
 * {@code \w \d \s \b}); {@code unicodeWordRanges} carries the word-character
 * ranges when {@code unicodeShorthand} is set (else {@code null});
 * {@code namedGroups} is the unmodifiable name&rarr;group-index map.
 */
// Not a record: the Java 8 bytecode floor forbids them; semantics
// (defensive copies, value-based equals) are preserved by hand.
public final class ParseResult {
    private final Ast ast;
    private final int tagCount;
    private final int groupCount;
    private final boolean multiline;
    private final boolean unicodeShorthand;
    private final int[] unicodeWordRanges;
    private final Map<String, Integer> namedGroups;

    public ParseResult(Ast ast, int tagCount, int groupCount, boolean multiline,
                       boolean unicodeShorthand, int[] unicodeWordRanges,
                       Map<String, Integer> namedGroups) {
        this.ast = ast;
        this.tagCount = tagCount;
        this.groupCount = groupCount;
        this.multiline = multiline;
        this.unicodeShorthand = unicodeShorthand;
        // Unmodifiable copy on construction.
        this.namedGroups = namedGroups != null
                ? java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(namedGroups))
                : java.util.Collections.emptyMap();
        // Defensive copy: an array field is otherwise only as immutable as
        // the caller's discipline.
        this.unicodeWordRanges = unicodeWordRanges != null ? unicodeWordRanges.clone() : null;
    }

    public Ast ast() { return ast; }
    public int tagCount() { return tagCount; }
    public int groupCount() { return groupCount; }
    public boolean multiline() { return multiline; }
    public boolean unicodeShorthand() { return unicodeShorthand; }

    /** Defensive copy on read too — hands out a fresh array each call. */
    public int[] unicodeWordRanges() {
        return unicodeWordRanges != null ? unicodeWordRanges.clone() : null;
    }

    public Map<String, Integer> namedGroups() { return namedGroups; }

    @Override public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ParseResult)) return false;
        ParseResult r = (ParseResult) o;
        return tagCount == r.tagCount && groupCount == r.groupCount
                && multiline == r.multiline && unicodeShorthand == r.unicodeShorthand
                && java.util.Objects.equals(ast, r.ast)
                && java.util.Arrays.equals(unicodeWordRanges, r.unicodeWordRanges)
                && java.util.Objects.equals(namedGroups, r.namedGroups);
    }

    @Override public int hashCode() {
        int h = 31 * (31 * (31 * tagCount + groupCount) + Boolean.hashCode(multiline))
                + Boolean.hashCode(unicodeShorthand);
        return 31 * h + java.util.Objects.hash(ast, java.util.Arrays.hashCode(unicodeWordRanges), namedGroups);
    }

    @Override public String toString() {
        return "ParseResult[ast=" + ast + ", tagCount=" + tagCount + ", groupCount=" + groupCount
                + ", multiline=" + multiline + ", unicodeShorthand=" + unicodeShorthand
                + ", unicodeWordRanges=" + java.util.Arrays.toString(unicodeWordRanges)
                + ", namedGroups=" + namedGroups + "]";
    }
}
