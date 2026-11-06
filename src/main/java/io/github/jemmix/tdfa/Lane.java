package io.github.jemmix.tdfa;

import io.github.jemmix.tdfa.asm.AsmEngineBackend;
import io.github.jemmix.tdfa.core.compile.PatternCompiler;
import io.github.jemmix.tdfa.core.compile.RegexEngineFactory;
import io.github.jemmix.tdfa.core.compile.TDFAPattern;
import io.github.jemmix.tdfa.core.report.CompileObserver;
import io.github.jemmix.tdfa.core.tnfa.Semantics;
import io.github.jemmix.tdfa.core.unicode.UnicodeDataProvider;
import io.github.jemmix.tdfa.core.unicode.UnicodeProviders;

/**
 * The jur facade's compile funnel — everything facade-local, resolved in
 * place before the machinery is called: the JDK-mirroring flag roster
 * ({@link Pattern} spells the same bits publicly; out-of-roster bits
 * reject here), the {@code java.util.regex}-parity lane a semantics-less
 * compile defaults to, the property-resolution Unicode default, and the
 * wrap of no-codegen results ({@code -Dtdfa.engine=VM}) into the
 * facade's own carrier. The machinery below is facade-blind otherwise:
 * same pipeline, same budgets, same shells — the lane is the one
 * argument the two facades differ by.
 */
final class Lane {

    /** The jur lane: every JUR-compat axis unset (java.util.regex parity). */
    static final Semantics DEFAULT_SEMANTICS = Semantics.of();

    static final int VALID_FLAGS = PatternCompiler.CASE_INSENSITIVE | PatternCompiler.DOTALL | PatternCompiler.MULTILINE
        | PatternCompiler.DISABLE_UNICODE_GROUPS | PatternCompiler.LONGEST_MATCH
        | PatternCompiler.UNICODE_CHARACTER_CLASS | PatternCompiler.FIND_ONLY | PatternCompiler.MULTI_VALUED_TAGS;

    static final String FLAGS_MESSAGE = "Flags should only be a combination of MULTILINE, DOTALL, CASE_INSENSITIVE,"
        + " DISABLE_UNICODE_GROUPS, LONGEST_MATCH, UNICODE_CHARACTER_CLASS, FIND_ONLY, MULTI_VALUED_TAGS (the jur"
        + " facade; the RE2 reading ships as the :re2j module's Pattern, and per-axis probes ride"
        + " CompileOptions.semantics)";

    private Lane() {
    }

    static Pattern compile(String regex, int flags, RegexEngineFactory factory, UnicodeDataProvider provider,
        CompileObserver observer, Semantics explicitSemantics) {
        if (regex == null) {
            throw new NullPointerException("pattern is null");
        }
        if ((flags & ~VALID_FLAGS) != 0) {
            throw new IllegalArgumentException(FLAGS_MESSAGE);
        }
        TDFAPattern p =
            PatternCompiler.compile(regex, flags, factory, AsmEngineBackend.INSTANCE, Pattern.class, provider,
                UnicodeProviders::get, observer, explicitSemantics != null ? explicitSemantics : DEFAULT_SEMANTICS);
        if (p.getClass() != TDFAPattern.class) {
            return (Pattern) p; // generated shell: implements the facade interface by emission
        }
        return new VmPattern(p); // no-codegen path: wrap into the facade's carrier
    }
}
