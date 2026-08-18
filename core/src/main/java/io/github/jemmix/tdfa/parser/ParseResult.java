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
 * @param ast              parsed syntax tree (anchors injected if requested)
 * @param tagCount         number of capture tags (2 per group; 1-indexed)
 * @param groupCount       number of capturing groups (excluding group 0)
 * @param multiline        {@code (?m)} seen — {@code ^}/{@code $} at line boundaries
 * @param unicodeShorthand {@code (?u)} seen — Unicode-aware {@code \w \d \s \b}
 * @param unicodeWordRanges word-character ranges when {@code unicodeShorthand}
 *                          is set, else {@code null}
 * @param namedGroups      unmodifiable name&rarr;group-index map
 */
public record ParseResult(
        Ast ast,
        int tagCount,
        int groupCount,
        boolean multiline,
        boolean unicodeShorthand,
        int[] unicodeWordRanges,
        Map<String, Integer> namedGroups) {

    public ParseResult {
        namedGroups = namedGroups != null ? Map.copyOf(namedGroups) : Map.of();
    }
}
