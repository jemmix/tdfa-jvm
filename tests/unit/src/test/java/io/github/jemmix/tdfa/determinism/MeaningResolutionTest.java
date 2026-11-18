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
            "(?i)[a-z]+\\s\\d", "(?m)^a$b\\b.*", "(?i)k\\x{10400}ſ", "(?u)(?i)\\w\\W", "(?i)\\QkK\\E",
            "(?i)[a-z\\s\\w]", "[\\x{10400}-\\x{10428}]", "(?i)[^s\\W]", "[]]", "[a-]", "(?i)[:upper:]"};
        for (Semantics lane : new Semantics[]{Semantics.of(), RE2}) {
            for (String pattern : corpus) {
                WorkMeter ledger = new WorkMeter(Budgets.compileComputeTicks());
                // The producer side: the stock parse under the same grammar
                // the string path projects for this lane (the meaning axes
                // stay in the Semantics — the whole point of the rung).
                ParseResult parsed =
                    Parser.parseResult(pattern, false, false, ParseOptions.of().unicodeCase(lane.isUnicodeCase())
                        .flagBindings(lane.flagBindings()).escapes(lane.escapes()), UnicodeProviders.get(), ledger);
                Tnfa viaIr = Tnfa.compile(parsed, false, lane, null, CompileObserver.NONE, ledger);
                Tdfa ir = Determinizer.compile(viaIr, false, CompileObserver.NONE, ledger.fork(0));
                String fromIr = ArtifactFingerprint.of(ir);
                String fromString = ArtifactFingerprint.of(tdfa(pattern, lane));
                assertThat(fromIr).as("IR path == string path for %s in %s", pattern, lane).isEqualTo(fromString);
            }
        }
    }

    // ---- the fold/shorthand atoms: one syntax, the policy's expansion ----

    @Test
    void foldLiteralExpandsPerTheLanesUniverse() {
        // The ASCII universe (jur bare CI): the 26 letter pairs, nothing else.
        assertThat(finds(tdfa("(?i)k", Semantics.of()), "K")).as("jur (?i)k folds K").isTrue();
        assertThat(finds(tdfa("(?i)s", Semantics.of()), "\u017F")).as("jur (?i)s does NOT fold \u017F").isFalse();
        // The full universe (RE2 lane): the s-orbit reaches \u017F (long s),
        // the k-orbit reaches the Kelvin sign.
        assertThat(finds(tdfa("(?i)s", RE2), "\u017F")).as("re2 (?i)s folds \u017F").isTrue();
        assertThat(finds(tdfa("(?i)k", RE2), "\u212A")).as("re2 (?i)k folds the Kelvin sign").isTrue();
        assertThat(finds(tdfa("(?i)k", Semantics.of()), "\u212A")).as("jur bare CI stays at the ASCII pairs").isFalse();
        // A supplementary orbit (Deseret): full universe only — via the RAW
        // literal path (the hex/backslash-u escapes stay fold-inert, a
        // pinned pre-existing reading the resolver preserves).
        assertThat(finds(tdfa("(?i)\uD801\uDC00", RE2), "\uD801\uDC28")).as("re2 folds the raw supplementary pair")
            .isTrue();
        assertThat(finds(tdfa("(?i)\uD801\uDC00", Semantics.of()), "\uD801\uDC28"))
            .as("jur bare CI never folds a supplementary").isFalse();
        assertThat(finds(tdfa("(?i)\\x{10400}", RE2), "\uD801\uDC28"))
            .as("\\x{} escapes stay fold-inert (pre-existing reading)").isFalse();
        // A literal with no counterparts demotes to the plain literal.
        assertThat(finds(tdfa("(?i)5", Semantics.of()), "5")).isTrue();
        // The quoted-literal fold path takes the same reading.
        assertThat(finds(tdfa("(?i)\\Qs\\E", RE2), "\u017F")).isTrue();
    }

    @Test
    void shorthandAtomsExpandPerThePolicy() {
        // The \s table: U+000B is whitespace on the JDK table, not on RE2's.
        assertThat(finds(tdfa("\\s", Semantics.of()), "\u000B")).as("jur \\s includes VT").isTrue();
        assertThat(finds(tdfa("\\s", RE2), "\u000B")).as("re2 \\s excludes VT").isFalse();
        assertThat(finds(tdfa("\\S", RE2), "\u000B")).isTrue();
        // The word fold: re2j folds (?i)\w through the s-orbit; java never
        // folds a shorthand (the SHORTHAND_FOLD policy).
        assertThat(finds(tdfa("(?i)\\w", RE2), "\u017F")).as("re2 (?i)\\w folds \u017F in").isTrue();
        assertThat(finds(tdfa("(?i)\\w", Semantics.of()), "\u017F")).as("jur (?i)\\w stays at its table").isFalse();
        assertThat(finds(tdfa("(?i)\\W", RE2), "\u017F")).as("re2 (?i)\\W folds the POSITIVE set first").isFalse();
        assertThat(finds(tdfa("(?i)\\W", Semantics.of()), "\u017F")).isTrue();
        // The unicode twins come from the provider tables.
        assertThat(finds(tdfa("(?u)\\w", Semantics.of()), "\u017F")).as("(?u)\\w is the Unicode word table").isTrue();
        assertThat(finds(tdfa("(?u)\\d", Semantics.of()), "\u0660")).as("(?u)\\d is \\p{Nd}").isTrue();
        assertThat(finds(tdfa("(?u)\\s", Semantics.of()), "\u00A0")).as("(?u)\\s is White_Space").isTrue();
    }

    @Test
    void classItemsFlattenPerThePolicy() {
        // The \s twin: the table axis, in class context.
        assertThat(finds(tdfa("[\\s]", Semantics.of()), "\u000B")).as("jur [\\s] includes VT").isTrue();
        assertThat(finds(tdfa("[\\s]", RE2), "\u000B")).as("re2 [\\s] excludes VT").isFalse();
        assertThat(finds(tdfa("[^\\s]", RE2), "\u000B")).as("re2 [^\\s] inverts the same table").isTrue();
        // The class fold: members join the positive set before negation.
        assertThat(finds(tdfa("(?i)[a-z]", RE2), "\u017F")).as("re2 (?i)[a-z] folds \u017F in").isTrue();
        assertThat(finds(tdfa("(?i)[a-z]", Semantics.of()), "\u017F")).as("jur (?i)[a-z] stays ASCII").isFalse();
        assertThat(finds(tdfa("(?i)[^s]", RE2), "\u017F")).as("re2 (?i)[^s] excludes the fold member").isFalse();
        // The shorthand twin skips the fold on the non-folding lane, and
        // the complement folds the positive set first on the folding lane.
        assertThat(finds(tdfa("(?i)[\\w]", RE2), "\u017F")).as("re2 (?i)[\\w] folds").isTrue();
        assertThat(finds(tdfa("(?i)[\\w]", Semantics.of()), "\u017F")).as("jur (?i)[\\w] stays at its table").isFalse();
        assertThat(finds(tdfa("(?i)[\\W]", RE2), "\u017F")).as("re2 (?i)[\\W] folds the positive set first").isFalse();
        // The grammar-owned JDK tables ride the escape alphabet.
        assertThat(finds(tdfa("[\\v]", Semantics.of()), "\r")).as("jur [\\v] is the VertWS table").isTrue();
        assertThat(finds(tdfa("[\\v]", RE2), "\r")).as("re2 \\v in class reads U+000B only").isFalse();
        assertThat(finds(tdfa("[\\v]", RE2), "\u000B")).isTrue();
        assertThat(finds(tdfa("[\\v-z]", Semantics.of()), "\u000B")).as("[\\v-z] reads \\v as U+000B (isrange)")
            .isTrue();
        assertThat(finds(tdfa("[\\v-z]", Semantics.of()), "\u2028"))
            .as("[\\v-z] is a RANGE, not the VertWS table (LS is outside it)").isFalse();
        // POSIX classes fold as plain members on the folding lane.
        assertThat(finds(tdfa("(?i)[[:alpha:]]", RE2), "\u017F")).isTrue();
        assertThat(finds(tdfa("(?i)[[:alpha:]]", Semantics.of()), "\u017F")).isFalse();
        // Property classes in class context keep their parse-time reading.
        assertThat(finds(tdfa("[\\p{Lu}]", Semantics.of()), "K")).isTrue();
        assertThat(finds(tdfa("[\\p{Lu}]", Semantics.of()), "k")).isFalse();
        // The empty members and first-] rule survive the structured item.
        assertThat(finds(tdfa("[]]", Semantics.of()), "]")).isTrue();
        assertThat(finds(tdfa("[a-]", Semantics.of()), "-")).as("a trailing dash is a literal member").isTrue();
    }

    @Test
    void inClassComplementsAreOrderIndependent() {
        // The word table spells its pairs alphabetically (a-z, A-Z, 0-9, _),
        // not by codepoint — the complements must still punch every hole
        // (regression: [\W] once silently dropped the A-Z/0-9/_ pairs and
        // matched letters; found by ShorthandFoldParityTest's scoped (?U)
        // leg, pinned here at the unit tier).
        Semantics[] lanes = {Semantics.of(), RE2};
        for (Semantics lane : lanes) {
            assertThat(finds(tdfa("[\\W]", lane), "S")).as("[\\W] excludes word letters in %s", lane).isFalse();
            assertThat(finds(tdfa("[\\W]", lane), "5")).isFalse();
            assertThat(finds(tdfa("[\\W]", lane), "_")).isFalse();
            assertThat(finds(tdfa("[\\W]", lane), "\u017F")).as("ſ is non-word without (?u)").isTrue();
            assertThat(finds(tdfa("(?i)(?U:[\\W])", lane), "S")).as("the scoped (?U) never folds a jur shorthand")
                .isFalse();
        }
    }

    // ---- the assertion family: kinds are resolved meanings ----

    @Test
    void dollarResolvesAgainstTheEndOfTextOnlyPolicy() {
        // The jur reading: plain $ is \Z — end of input, or immediately
        // before the final terminator run.
        assertThat(finds(tdfa("a$", Semantics.of()), "a\n")).as("jur $ matches before the final terminator").isTrue();
        assertThat(finds(tdfa("a$", Semantics.of()), "a")).isTrue();
        assertThat(finds(tdfa("a$", Semantics.of()), "a\n\n")).as("jur $ is BEFORE THE FINAL terminator run only")
            .isFalse();
        // The RE2-lineage reading: plain $ is \z — end of input only.
        assertThat(finds(tdfa("a$", RE2), "a\n")).as("re2 $ is end-of-text only").isFalse();
        assertThat(finds(tdfa("a$", RE2), "a")).isTrue();
        // The explicit spellings are policy-immune.
        assertThat(finds(tdfa("a\\z", RE2), "a")).isTrue();
        assertThat(finds(tdfa("a\\z", Semantics.of()), "a\n")).as("\\z never matches before a terminator").isFalse();
        assertThat(finds(tdfa("a\\Z", Semantics.of()), "a\n")).as("\\Z is the before-final reading").isTrue();
        // Multiline $ is a LINE-end predicate in both lanes.
        assertThat(finds(tdfa("(?m)a$", RE2), "a\nb")).isTrue();
        assertThat(finds(tdfa("(?m)a$", Semantics.of()), "a\nb")).isTrue();
    }

    @Test
    void caretsAreAbsoluteOrLinePerScopedMultiline() {
        // \A is text-start under (?m) too; a bare ^ outside multiline is
        // text-start; under (?m) it is line-start.
        assertThat(finds(tdfa("(?m)\\Ab", RE2), "a\nb")).as("\\A ignores (?m)").isFalse();
        assertThat(finds(tdfa("^b", Semantics.of()), "a\nb")).as("bare ^ is text-start without (?m)").isFalse();
        assertThat(finds(tdfa("(?m)^b", Semantics.of()), "a\nb")).isTrue();
        assertThat(finds(tdfa("(?m:^b)?c|(?m:^b)", RE2), "a\nb")).isTrue();
        // The word boundaries ride the family unchanged.
        assertThat(finds(tdfa("\\bword\\b", Semantics.of()), "a word")).isTrue();
        assertThat(finds(tdfa("\\Bword", Semantics.of()), "a word")).isFalse();
    }

    @Test
    void irEntryRejectsInconsistentProducerMetadata() {
        // Tag outside the declared counters.
        ParseResult badTag =
            new ParseResult(new Ast.Tag(99), 2, 1, false, false, null, Collections.emptyMap(), false, false, null);
        assertThatThrownBy(() -> Tnfa.compile(badTag, false, Semantics.of(), null, CompileObserver.NONE,
            new WorkMeter(Budgets.compileComputeTicks()))).isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("tag 99");
        // Named group outside the declared group count.
        ParseResult badName = new ParseResult(new Ast.Symbol('a'), 2, 1, false, false, null,
            Collections.singletonMap("x", 5), false, false, null);
        assertThatThrownBy(() -> Tnfa.compile(badName, false, Semantics.of(), null, CompileObserver.NONE,
            new WorkMeter(Budgets.compileComputeTicks()))).isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("named group 'x'");
        // Counters that cannot describe any tree (2 tags cannot hold 5 groups).
        ParseResult badCounts =
            new ParseResult(new Ast.Symbol('a'), 2, 5, false, false, null, Collections.emptyMap(), false, false, null);
        assertThatThrownBy(() -> Tnfa.compile(badCounts, false, Semantics.of(), null, CompileObserver.NONE,
            new WorkMeter(Budgets.compileComputeTicks()))).isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("inconsistent parse result");
    }

    @Test
    void irEntryResolvesAProducerDotAgainstThePolicy() {
        // A hand-built tree with a symbolic dot: the entry resolves it —
        // no Ast.Dot can reach the builder regardless of who built the tree.
        ParseResult parsed =
            new ParseResult(new Ast.Concat(Arrays.asList(new Ast.Symbol('a'), new Ast.Dot(false), new Ast.Symbol('b'))),
                0, 0, false, false, null, Collections.emptyMap(), false, false, null);
        WorkMeter ledger = new WorkMeter(Budgets.compileComputeTicks());
        Tnfa viaIr = Tnfa.compile(parsed, false, RE2, null, CompileObserver.NONE, ledger);
        Tdfa ir = Determinizer.compile(viaIr, false, CompileObserver.NONE, ledger.fork(0));
        assertThat(finds(ir, "a\rb")).as("producer dot resolves against the RE2 lane").isTrue();
        assertThat(finds(ir, "a\nb")).as("re2 dot still excludes \\n").isFalse();
    }
}
