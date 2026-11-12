package io.github.jemmix.tdfa;

import io.github.jemmix.tdfa.asm.AsmEngineBackend;
import io.github.jemmix.tdfa.core.compile.PatternCompiler;
import io.github.jemmix.tdfa.core.compile.RegexEngineFactory;
import io.github.jemmix.tdfa.core.compile.TDFAPattern;
import io.github.jemmix.tdfa.core.dialect.Terminators;
import io.github.jemmix.tdfa.core.report.CompileObserver;
import io.github.jemmix.tdfa.core.tnfa.Semantics;
import io.github.jemmix.tdfa.core.unicode.UnicodeDataProvider;
import io.github.jemmix.tdfa.core.unicode.UnicodeProviders;

/**
 * The jur facade's compile funnel — everything facade-local, resolved in
 * place before the machinery is called: the JDK-mirroring flag roster
 * ({@link Pattern} spells the same bits publicly; out-of-roster bits
 * reject here), the {@code java.util.regex}-parity lane a semantics-less
 * compile defaults to, the property-resolution Unicode default. The
 * machinery below is facade-blind end to end — it compiles a core-typed
 * {@link TDFAPattern} with no knowledge of this facade — and this
 * funnel performs the one facade-owned step on the way out: this
 * module's own codegen ({@link Shells}) mints the API-typed subclass of
 * the machinery shell, serializing through this module's own
 * {@link SerializationProxy}. Same pipeline, same budgets, same core
 * shells; the lane is the one argument the two facades differ by.
 */
final class Lane {

    /** The jur lane: the all-defaults policy — every JDK-stock component
     *  ({@code Terminators.JDK}, {@code ClassTable.SPACE_JDK}, {@code
     *  EscapeAlphabet.JDK}, {@code FlagBindings.JDK}) and no policy bit. */
    static final Semantics DEFAULT_SEMANTICS = Semantics.of();

    static final int VALID_FLAGS = PatternCompiler.CASE_INSENSITIVE | PatternCompiler.DOTALL | PatternCompiler.MULTILINE
        | PatternCompiler.DISABLE_UNICODE_GROUPS | PatternCompiler.LONGEST_MATCH
        | PatternCompiler.UNICODE_CHARACTER_CLASS | PatternCompiler.FIND_ONLY | PatternCompiler.MULTI_VALUED_TAGS
        | PatternCompiler.UNIX_LINES | PatternCompiler.UNICODE_CASE | PatternCompiler.LITERAL
        | PatternCompiler.COMMENTS;

    static final String FLAGS_MESSAGE = "Flags should only be a combination of MULTILINE, DOTALL, CASE_INSENSITIVE,"
        + " DISABLE_UNICODE_GROUPS, LONGEST_MATCH, UNICODE_CHARACTER_CLASS, FIND_ONLY, MULTI_VALUED_TAGS, UNIX_LINES,"
        + " UNICODE_CASE, LITERAL, COMMENTS (the jur facade; CANON_EQ is not supported — canonical-equivalence"
        + " matching needs normalization-aware compilation, outside the TDFA mission; the RE2 reading ships as the"
        + " :re2j module's Pattern, and per-axis probes ride CompileOptions.semantics)";

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
        // The JDK axis-mirroring bits compose onto the lane (an explicit
        // Semantics still wins as the base — the bits select that lane's
        // sides of the UNIX_LINES / UNICODE_CASE axes).
        Semantics lane = explicitSemantics != null ? explicitSemantics : DEFAULT_SEMANTICS;
        if ((flags & PatternCompiler.UNIX_LINES) != 0) {
            lane = lane.terminators(Terminators.NL_ONLY);
        }
        if ((flags & PatternCompiler.UNICODE_CASE) != 0) {
            lane = lane.unicodeCase();
        }
        TDFAPattern p = PatternCompiler.compile(regex, flags, factory, AsmEngineBackend.INSTANCE, provider,
            UnicodeProviders::get, observer, lane);
        // The one facade-owned step: mint this facade's API-typed subclass
        // of the machinery shell (this module's own codegen).
        return Shells.mint(p);
    }
}
