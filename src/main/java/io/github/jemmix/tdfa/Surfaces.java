package io.github.jemmix.tdfa;

import io.github.jemmix.tdfa.asm.AsmEngineBackend;
import io.github.jemmix.tdfa.core.compile.PatternSurface;
import io.github.jemmix.tdfa.core.tnfa.Semantics;
import io.github.jemmix.tdfa.core.unicode.UnicodeProviders;

/**
 * The jur surface's registration: the compile lane and flag roster the
 * root facade bakes into every compile — {@code java.util.regex} parity
 * throughout (every axis unset), the JDK-mirroring flag set. Loaded on
 * first compile (and by the serialization proxy, by name); registers
 * exactly once per process.
 */
final class Surfaces {

    static final PatternSurface JUR = PatternSurface.register("jur", "io.github.jemmix.tdfa.Surfaces",
        "io/github/jemmix/tdfa/Pattern", Semantics.of(),
        PatternSurface.CASE_INSENSITIVE | PatternSurface.DOTALL | PatternSurface.MULTILINE
            | PatternSurface.DISABLE_UNICODE_GROUPS | PatternSurface.LONGEST_MATCH
            | PatternSurface.UNICODE_CHARACTER_CLASS | PatternSurface.FIND_ONLY | PatternSurface.MULTI_VALUED_TAGS,
        "Flags should only be a combination of MULTILINE, DOTALL, CASE_INSENSITIVE, DISABLE_UNICODE_GROUPS,"
            + " LONGEST_MATCH, UNICODE_CHARACTER_CLASS, FIND_ONLY, MULTI_VALUED_TAGS (the jur facade; the RE2"
            + " reading ships as the :re2j surface, and per-axis probes ride CompileOptions.semantics)",
        AsmEngineBackend.INSTANCE, VmPattern::new, UnicodeProviders::get);

    private Surfaces() {
    }
}
