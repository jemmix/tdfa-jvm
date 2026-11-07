package io.github.jemmix.tdfa.re2j;

import io.github.jemmix.tdfa.asm.AsmEngineBackend;
import io.github.jemmix.tdfa.core.compile.PatternCompiler;
import io.github.jemmix.tdfa.core.compile.RegexEngineFactory;
import io.github.jemmix.tdfa.core.compile.TDFAPattern;
import io.github.jemmix.tdfa.core.report.CompileObserver;
import io.github.jemmix.tdfa.core.tnfa.Semantics;
import io.github.jemmix.tdfa.core.unicode.Re2jUniverseProvider;
import io.github.jemmix.tdfa.core.unicode.UnicodeDataProvider;

/**
 * The re2j facade's compile funnel — everything facade-local, resolved
 * in place before the machinery is called: the re2j flag roster
 * ({@link Pattern} spells the same bits publicly; out-of-roster bits
 * reject here), the RE2-lineage lane every compile through this module
 * defaults to (every JUR-compat axis set: {@code \n}-only terminators,
 * full-Unicode bare fold, codepoint boundaries, empty-last-line caret,
 * end-of-text dollar, maximal-iteration spans, ungreedy {@code (?U)},
 * re2j's ASCII {@code \s} table without U+000B), and the RE2-lineage
 * fold universe a provider-less compile resolves to. The machinery
 * below is facade-blind end to end — it compiles a core-typed
 * {@link TDFAPattern} with no knowledge of this facade — and this
 * funnel performs the one facade-owned step on the way out: this
 * module's own codegen ({@link Shells}) mints the API-typed subclass of
 * the machinery shell, serializing through this module's own
 * {@link SerializationProxy}. Same pipeline, same budgets, same core
 * shells; the lane is the one argument the two facades differ by.
 */
final class Lane {

    /** The RE2-lineage lane: every JUR-compat axis set. */
    static final Semantics DEFAULT_SEMANTICS = Semantics.of().unixLines().unicodeCase().codepointBoundaries()
        .emptyLastLine().endOfTextOnly().emptyIterationSpans().ungreedyU().spaceRe2().shorthandFold();

    static final int VALID_FLAGS = PatternCompiler.CASE_INSENSITIVE | PatternCompiler.DOTALL | PatternCompiler.MULTILINE
        | PatternCompiler.DISABLE_UNICODE_GROUPS | PatternCompiler.LONGEST_MATCH | PatternCompiler.FIND_ONLY
        | PatternCompiler.MULTI_VALUED_TAGS;

    static final String FLAGS_MESSAGE = "Flags should only be a combination of MULTILINE, DOTALL, CASE_INSENSITIVE,"
        + " DISABLE_UNICODE_GROUPS, LONGEST_MATCH, FIND_ONLY, MULTI_VALUED_TAGS (the re2j facade compiles the RE2"
        + " lane throughout; there are no JUR-compat opt-out bits here)";

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
        TDFAPattern p = PatternCompiler.compile(regex, flags, factory, AsmEngineBackend.INSTANCE, provider,
            Re2jUniverseProvider::instance, observer,
            explicitSemantics != null ? explicitSemantics : DEFAULT_SEMANTICS);
        // The one facade-owned step: mint this facade's API-typed subclass
        // of the machinery shell (this module's own codegen).
        return Shells.mint(p);
    }
}
