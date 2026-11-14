package io.github.jemmix.tdfa.determinism;

import io.github.jemmix.tdfa.core.ast.Ast;
import io.github.jemmix.tdfa.core.budget.Budgets;
import io.github.jemmix.tdfa.core.budget.WorkMeter;
import io.github.jemmix.tdfa.core.determinize.Determinizer;
import io.github.jemmix.tdfa.core.dfa.Tdfa;
import io.github.jemmix.tdfa.core.dfa.TdfaRunner;
import io.github.jemmix.tdfa.core.dialect.ClassTable;
import io.github.jemmix.tdfa.core.dialect.EscapeAlphabet;
import io.github.jemmix.tdfa.core.dialect.FlagBindings;
import io.github.jemmix.tdfa.core.dialect.Terminators;
import io.github.jemmix.tdfa.core.parser.ParseOptions;
import io.github.jemmix.tdfa.core.parser.ParseResult;
import io.github.jemmix.tdfa.core.parser.Parser;
import io.github.jemmix.tdfa.core.report.CompileObserver;
import io.github.jemmix.tdfa.core.tnfa.Semantics;
import io.github.jemmix.tdfa.core.tnfa.Tnfa;
import io.github.jemmix.tdfa.core.unicode.UnicodeProviders;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The meaning-resolution pass (core.semantic.Resolve) and the IR entry —
 * rung 1 of the compat surface (docs/engine-compat-surface.md). Pins the
 * three contracts the rung introduces:
 * <ul>
 * <li><b>Symbolic dot</b>: the parser emits {@code Ast.Dot} with its scoped
 *     {@code (?s)} state, and resolution expands it against the compile's
 *     terminator set — stock lanes must answer exactly the readings the
 *     pre-symbolic parser baked in (behavioral pins per lane, custom set
 *     included);</li>
 * <li><b>Protocol marks</b>: the family-6 marks and the
 *     {@code zeroWidthExit} split are placed by resolution from the
 *     EMPTY_ITERATION_SPANS policy, keyed on the parser's
 *     quantified-capture fact — the wrapper case ({@code (?:(\b))*}) stays
 *     unmarked exactly as before;</li>
 * <li><b>IR entry</b>: {@code Tnfa.compile(ParseResult, ...)} is
 *     fingerprint-identical to the string path over the same grammar, and
 *     rejects inconsistent producer metadata loudly.</li>
 * </ul>
 * The stock-lane artifact-identity discipline at large is pinned by
 * {@link TerminatorAxisInvariantTest} and the facade batteries; this class
 * covers only what the rung newly made movable.
 */
class MeaningResolutionTest {

    /** The RE2-lineage policy (every axis set — the :re2j facade's lane). */
    private static final Semantics RE2 = Semantics.of().unixLines().unicodeCase().codepointBoundaries().emptyLastLine()
        .endOfTextOnly().emptyIterationSpans().shorthandFold().terminators(Terminators.NL_ONLY)
        .spaceTable(ClassTable.SPACE_RE2).escapes(EscapeAlphabet.RE2_MINIMAL).flagBindings(FlagBindings.RE2);

    private static Tnfa tnfa(String pattern, Semantics semantics) {
        return Tnfa.compile(pattern, false, false, false, semantics, UnicodeProviders.get(), CompileObserver.NONE,
            new WorkMeter(Budgets.compileComputeTicks()));
    }

    private static Tdfa tdfa(String pattern, Semantics semantics) {
        WorkMeter ledger = new WorkMeter(Budgets.compileComputeTicks());
        Tnfa nfa =
            Tnfa.compile(pattern, false, false, false, semantics, UnicodeProviders.get(), CompileObserver.NONE, ledger);
        return Determinizer.compile(nfa, false, CompileObserver.NONE, ledger.fork(0));
    }

    private static boolean finds(Tdfa tdfa, CharSequence input) {
        return new TdfaRunner(tdfa).find(input);
    }

    // ---- the symbolic dot: one syntax, the policy's complement ----

    @Test
    void dotResolvesAgainstTheCompileTerminatorSet() {
        // The JUR reading: the full java.util.regex terminator set — the
        // dot excludes \n, \r, U+0085, U+2028, U+2029.
        assertThat(finds(tdfa("a.b", Semantics.of()), "a\nb")).as("jur dot excludes \\n").isFalse();
        assertThat(finds(tdfa("a.b", Semantics.of()), "a\rb")).as("jur dot excludes \\r").isFalse();
        assertThat(finds(tdfa("a.b", Semantics.of()), "a\u0085b")).as("jur dot excludes NEL").isFalse();
        assertThat(finds(tdfa("a.b", Semantics.of()), "axb")).as("jur dot matches a plain unit").isTrue();
        // The RE2-lineage reading: \n only.
        assertThat(finds(tdfa("a.b", RE2), "a\nb")).as("re2 dot excludes \\n").isFalse();
        assertThat(finds(tdfa("a.b", RE2), "a\rb")).as("re2 dot includes \\r").isTrue();
        assertThat(finds(tdfa("a.b", RE2), "axb")).isTrue();
        // A custom set: the complement is data-driven.
        Semantics custom = Semantics.of().terminators(Terminators.of('\r'));
        assertThat(finds(tdfa("a.b", custom), "a\rb")).as("custom \\r-only dot excludes \\r").isFalse();
        assertThat(finds(tdfa("a.b", custom), "a\nb")).as("custom \\r-only dot includes \\n").isTrue();
    }

    @Test
    void dotallIsScopedStateNotAPolicy() {
        // (?s) rides the node; both lanes answer identically.
        assertThat(finds(tdfa("(?s)a.b", Semantics.of()), "a\nb")).isTrue();
        assertThat(finds(tdfa("(?s:a.b)", RE2), "a\rb")).isTrue();
        assertThat(finds(tdfa("a(?i:s)b", Semantics.of()), "a\rb")).as("no (?s): still excluded").isFalse();
    }

    // ---- the protocol marks: placed by resolution, keyed on the facts ----

    @Test
    void family6DissolveMarksThroughTheResolver() {
        Tnfa dissolved = tnfa("(\\b)*", Semantics.of());
        assertThat(dissolved.dissolvedTags).as("jur (\\b)* dissolves the capture").isNotNull();
        assertThat(dissolved.dissolvedTags[1]).isTrue();
        assertThat(dissolved.dissolvedTags[2]).isTrue();
        // The wrapper: the capture is NOT the quantified atom — unmarked.
        Tnfa wrapped = tnfa("(?:(\\b))*", Semantics.of());
        assertThat(wrapped.dissolvedTags).as("(?:(\\b))* keeps the span").isNull();
        // The axis-set reading never marks.
        assertThat(tnfa("(\\b)*", RE2).dissolvedTags).as("re2 lane never dissolves").isNull();
    }

    @Test
    void family6SurfaceMarksThroughTheResolver() {
        Tnfa surfaced = tnfa("((a*))*", Semantics.of());
        assertThat(surfaced.surfaceFinalTags).as("jur ((a*))* surfaces the final iteration").isNotNull();
        assertThat(surfaced.surfaceFinalTags[1]).as("the outer pair surfaces").isTrue();
        assertThat(surfaced.surfaceFinalTags[3]).as("containment: the inner pair surfaces too").isTrue();
        assertThat(tnfa("((a*))*", RE2).surfaceFinalTags).as("re2 lane never surfaces").isNull();
    }

    // ---- the IR entry: fingerprint-identical to the string path ----

    @Test
    void irEntryIsFingerprintIdenticalToTheStringPath() {
        String[] corpus = {"a.b", "(?s)a.b", "(\\b)*", "((a*))*", "(?:(\\b))*", "(a+)+b", "(\\w+)@(\\w+)",
            "(?i)[a-z]+\\s\\d", "(?m)^a$b\\b.*"};
        for (Semantics lane : new Semantics[]{Semantics.of(), RE2}) {
            for (String pattern : corpus) {
                WorkMeter ledger = new WorkMeter(Budgets.compileComputeTicks());
                // The producer side: the stock parse under the same grammar
                // the string path projects for this lane (the meaning axes
                // stay in the Semantics — the whole point of the rung).
                ParseResult parsed = Parser.parseResult(pattern, false, false,
                    ParseOptions.of().unicodeCase(lane.isUnicodeCase()).flagBindings(lane.flagBindings())
                        .spaceTable(lane.spaceTable()).shorthandFold(lane.isShorthandFold()).escapes(lane.escapes()),
                    UnicodeProviders.get(), ledger);
                Tnfa viaIr = Tnfa.compile(parsed, false, lane, CompileObserver.NONE, ledger);
                Tdfa ir = Determinizer.compile(viaIr, false, CompileObserver.NONE, ledger.fork(0));
                String fromIr = ArtifactFingerprint.of(ir);
                String fromString = ArtifactFingerprint.of(tdfa(pattern, lane));
                assertThat(fromIr).as("IR path == string path for %s in %s", pattern, lane).isEqualTo(fromString);
            }
        }
    }

    @Test
    void irEntryRejectsInconsistentProducerMetadata() {
        // Tag outside the declared counters.
        ParseResult badTag =
            new ParseResult(new Ast.Tag(99), 2, 1, false, false, null, Collections.emptyMap(), false, false);
        assertThatThrownBy(() -> Tnfa.compile(badTag, false, Semantics.of(), CompileObserver.NONE,
            new WorkMeter(Budgets.compileComputeTicks()))).isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("tag 99");
        // Named group outside the declared group count.
        ParseResult badName = new ParseResult(new Ast.Symbol('a'), 2, 1, false, false, null,
            Collections.singletonMap("x", 5), false, false);
        assertThatThrownBy(() -> Tnfa.compile(badName, false, Semantics.of(), CompileObserver.NONE,
            new WorkMeter(Budgets.compileComputeTicks()))).isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("named group 'x'");
        // Counters that cannot describe any tree (2 tags cannot hold 5 groups).
        ParseResult badCounts =
            new ParseResult(new Ast.Symbol('a'), 2, 5, false, false, null, Collections.emptyMap(), false, false);
        assertThatThrownBy(() -> Tnfa.compile(badCounts, false, Semantics.of(), CompileObserver.NONE,
            new WorkMeter(Budgets.compileComputeTicks()))).isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("inconsistent parse result");
    }

    @Test
    void irEntryResolvesAProducerDotAgainstThePolicy() {
        // A hand-built tree with a symbolic dot: the entry resolves it —
        // no Ast.Dot can reach the builder regardless of who built the tree.
        ParseResult parsed =
            new ParseResult(new Ast.Concat(Arrays.asList(new Ast.Symbol('a'), new Ast.Dot(false), new Ast.Symbol('b'))),
                0, 0, false, false, null, Collections.emptyMap(), false, false);
        WorkMeter ledger = new WorkMeter(Budgets.compileComputeTicks());
        Tnfa viaIr = Tnfa.compile(parsed, false, RE2, CompileObserver.NONE, ledger);
        Tdfa ir = Determinizer.compile(viaIr, false, CompileObserver.NONE, ledger.fork(0));
        assertThat(finds(ir, "a\rb")).as("producer dot resolves against the RE2 lane").isTrue();
        assertThat(finds(ir, "a\nb")).as("re2 dot still excludes \\n").isFalse();
    }
}
