package io.github.jemmix.tdfa.re2j;

import io.github.jemmix.tdfa.asm.AsmEngineBackend;
import io.github.jemmix.tdfa.core.compile.PatternSurface;
import io.github.jemmix.tdfa.core.tnfa.Semantics;

/**
 * The re2j surface's registration: the compile lane and flag roster this
 * module bakes into every compile — the RE2-lineage reading throughout
 * (every axis set: {@code \n}-only terminators, full-Unicode bare fold,
 * codepoint boundaries, empty-last-line caret, end-of-text dollar,
 * maximal-iteration spans, ungreedy {@code (?U)}), the re2j flag
 * roster. Loaded on first compile (and by the serialization proxy, by
 * name); registers exactly once per process.
 */
final class Surfaces {

    static final PatternSurface RE2J = PatternSurface.register("re2j", "io.github.jemmix.tdfa.re2j.Surfaces",
        "io/github/jemmix/tdfa/re2j/Pattern", re2(),
        PatternSurface.CASE_INSENSITIVE | PatternSurface.DOTALL | PatternSurface.MULTILINE
            | PatternSurface.DISABLE_UNICODE_GROUPS | PatternSurface.LONGEST_MATCH | PatternSurface.FIND_ONLY
            | PatternSurface.MULTI_VALUED_TAGS,
        "Flags should only be a combination of MULTILINE, DOTALL, CASE_INSENSITIVE, DISABLE_UNICODE_GROUPS,"
            + " LONGEST_MATCH, FIND_ONLY, MULTI_VALUED_TAGS (the re2j surface compiles the RE2 lane throughout;"
            + " there are no JUR-compat opt-out bits here)",
        AsmEngineBackend.INSTANCE, VmPattern::new);

    private Surfaces() {
    }

    /** The RE2-lineage lane: all seven JUR-compat axes set. */
    private static Semantics re2() {
        return Semantics.of().unixLines().unicodeCase().codepointBoundaries().emptyLastLine().endOfTextOnly()
            .emptyIterationSpans().ungreedyU();
    }
}
