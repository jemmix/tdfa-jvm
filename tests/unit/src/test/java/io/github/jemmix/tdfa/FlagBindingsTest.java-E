package io.github.jemmix.tdfa;

import io.github.jemmix.tdfa.core.budget.Budgets;
import io.github.jemmix.tdfa.core.budget.WorkMeter;
import io.github.jemmix.tdfa.core.determinize.Determinizer;
import io.github.jemmix.tdfa.core.dfa.Tdfa;
import io.github.jemmix.tdfa.core.dfa.TdfaRunner;
import io.github.jemmix.tdfa.core.engine.RegexEngine;
import io.github.jemmix.tdfa.core.parser.FlagAction;
import io.github.jemmix.tdfa.core.parser.FlagBindings;
import io.github.jemmix.tdfa.core.parser.PatternSyntaxException;
import io.github.jemmix.tdfa.core.report.CompileObserver;
import io.github.jemmix.tdfa.core.tnfa.Semantics;
import io.github.jemmix.tdfa.core.tnfa.Tnfa;
import io.github.jemmix.tdfa.core.unicode.UnicodeProviders;
import org.junit.jupiter.api.Test;

import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link FlagBindings} — the letter&rarr;behavior seam (the closed
 * {@link FlagAction} vocabulary; only the binding is data). The stock
 * lanes pin the two shipped readings ({@code U} = ungreedy / scoped
 * Unicode case); a custom surface composes a subset or a renamed
 * alphabet with <b>zero core changes</b>, and every letter it does not
 * bind rejects at the parse. Reading-level expectations are
 * cross-checked against the live {@code java.util.regex} oracle in its
 * own spelling (the engine's u/U letters are crossed vs the JDK: our
 * {@code (?u)} is java's {@code (?U)}, our JUR {@code (?U)} is java's
 * {@code (?u)}).
 */
class FlagBindingsTest {

    /** A renamed fold letter beside the stock JUR alphabet. */
    private static final FlagBindings V_AS_FOLD = FlagBindings.builder().bind('i', FlagAction.CASE_INSENSITIVE)
        .bind('s', FlagAction.DOTALL).bind('m', FlagAction.MULTILINE).bind('u', FlagAction.UNICODE_CHARACTER_CLASS)
        .bind('U', FlagAction.UNICODE_CASE).bind('v', FlagAction.UNICODE_CASE).build();

    /** A renamed class-tables letter beside the stock JUR alphabet. */
    private static final FlagBindings V_AS_UCC =
        FlagBindings.builder().bind('i', FlagAction.CASE_INSENSITIVE).bind('m', FlagAction.MULTILINE)
            .bind('u', FlagAction.UNICODE_CHARACTER_CLASS).bind('v', FlagAction.UNICODE_CHARACTER_CLASS).build();

    /** A subset surface: i and m only — s, u and U are not in the alphabet. */
    private static final FlagBindings NO_DOTALL =
        FlagBindings.builder().bind('i', FlagAction.CASE_INSENSITIVE).bind('m', FlagAction.MULTILINE).build();

    private static Tdfa compile(String pattern, FlagBindings bindings) {
        WorkMeter ledger = new WorkMeter(Budgets.compileComputeTicks());
        Tnfa nfa = Tnfa.compile(pattern, false, false, false, Semantics.of(), bindings, UnicodeProviders.get(),
            CompileObserver.NONE, ledger);
        return Determinizer.compileWithWholeSide(nfa, false, CompileObserver.NONE, ledger.fork(0));
    }

    /** First-match span (or "none") — the full-match view of the protocol. */
    private static String first(RegexEngine eng, CharSequence in) {
        var m = eng.match(in, 0, null);
        return m == null ? "none" : m.start(0) + ".." + m.end(0);
    }

    @Test
    void unboundLetterStaysAnError() {
        assertThatThrownBy(() -> compile("(?v)a", FlagBindings.JUR_LANE)).isInstanceOf(PatternSyntaxException.class);
        // the empty alphabet binds nothing at all
        assertThatThrownBy(() -> compile("(?y)a", FlagBindings.builder().build()))
            .isInstanceOf(PatternSyntaxException.class);
        assertThatThrownBy(() -> compile("(?i)a", FlagBindings.builder().build()))
            .isInstanceOf(PatternSyntaxException.class);
    }

    @Test
    void customLetterSelectsTheFoldUniverse() {
        // (?v) bound to UNICODE_CASE — the JUR (?U) reading under a
        // renamed letter, scoped like every inline flag
        RegexEngine eng = new TdfaRunner(compile("(?v)(?i)k", V_AS_FOLD), 1 << 20);
        assertThat(first(eng, "\u212A")).as("v-bound fold widens to the Kelvin sign").isEqualTo("0..1");
        assertThat(first(eng, "\u017F")).as("ſ is not in k's orbit").isEqualTo("none");
        assertThat(Pattern.compile("(?u)(?i)k").matcher("\u212A").find())
            .as("the live JDK reading under java's own spelling").isTrue();
        RegexEngine scoped = new TdfaRunner(compile("(?i)(?v:k)k", V_AS_FOLD), 1 << 20);
        assertThat(first(scoped, "\u212Ak")).as("v-scoped fold widens inside, restores at ')'").isEqualTo("0..2");
        assertThat(first(scoped, "\u212A\u212A")).as("the atom after the scoped group folds ASCII").isEqualTo("none");
        assertThat(Pattern.compile("(?i)(?u:k)k").matcher("\u212Ak").find()).isTrue();
        assertThat(Pattern.compile("(?i)(?u:k)k").matcher("\u212A\u212A").find()).isFalse();
    }

    @Test
    void customLetterSelectsTheClassTables() {
        // (?v) bound to UNICODE_CHARACTER_CLASS — java's (?U) reading
        RegexEngine eng = new TdfaRunner(compile("(?v)\\w", V_AS_UCC), 1 << 20);
        assertThat(first(eng, "é")).as("v-bound UCC widens \\w to Unicode").isEqualTo("0..1");
        assertThat(Pattern.compile("(?U)\\w").matcher("é").find()).as("the live JDK reading under java's own spelling")
            .isTrue();
        // and the ASCII tables return after the scope closes
        RegexEngine scoped = new TdfaRunner(compile("(?v:\\w)\\w", V_AS_UCC), 1 << 20);
        assertThat(first(scoped, "é")).as("the atom after the scoped group keeps ASCII \\w").isEqualTo("none");
    }

    @Test
    void subsetSurfaceShrinksTheAlphabet() {
        assertThatThrownBy(() -> compile("(?s).", NO_DOTALL)).as("a letter the subset surface does not bind rejects")
            .isInstanceOf(PatternSyntaxException.class);
        assertThatThrownBy(() -> compile("(?u)\\w", NO_DOTALL)).isInstanceOf(PatternSyntaxException.class);
        assertThatCode(() -> compile("a", NO_DOTALL)).doesNotThrowAnyException();
        assertThat(first(new TdfaRunner(compile("(?i)K", NO_DOTALL), 1 << 20), "k"))
            .as("the bound letters still activate their behaviors").isEqualTo("0..1");
    }

    @Test
    void stockLanesDisagreeOnExactlyTheULetter() {
        // U = UNGREEDY: (?U)a+ is lazy, (?U)a+? is greedy (double swap)
        assertThat(first(new TdfaRunner(compile("(?U)a+", FlagBindings.RE2_LANE), 1 << 20), "aaa"))
            .as("RE2 lane: U swaps the quantifier preference").isEqualTo("0..1");
        assertThat(first(new TdfaRunner(compile("(?U)a+?", FlagBindings.RE2_LANE), 1 << 20), "aaa"))
            .as("RE2 lane: a+? under U is greedy").isEqualTo("0..3");
        // U = UNICODE_CASE: the quantifier is untouched
        assertThat(first(new TdfaRunner(compile("(?U)a+", FlagBindings.JUR_LANE), 1 << 20), "aaa"))
            .as("JUR lane: U does not touch greed").isEqualTo("0..3");
        // the two lanes agree on every other letter
        assertThat(FlagBindings.JUR_LANE.asMap().get('u')).isEqualTo(FlagBindings.RE2_LANE.asMap().get('u'));
        assertThat(FlagBindings.JUR_LANE.asMap().get('i')).isEqualTo(FlagBindings.RE2_LANE.asMap().get('i'));
    }

    @Test
    void bindingsAreValues() {
        // lane identity for the campaign harness: equality is the map
        assertThat(FlagBindings.builder().bind('U', FlagAction.UNGREEDY).bind('i', FlagAction.CASE_INSENSITIVE).build())
            .isEqualTo(
                FlagBindings.builder().bind('i', FlagAction.CASE_INSENSITIVE).bind('U', FlagAction.UNGREEDY).build());
        assertThat(FlagBindings.JUR_LANE).isNotEqualTo(FlagBindings.RE2_LANE);
        assertThat(FlagBindings.JUR_LANE.toString()).contains("U=UNICODE_CASE").contains("u=UNICODE_CHARACTER_CLASS");
        // construction is honest: rebinding and non-letters reject
        assertThatThrownBy(() -> FlagBindings.builder().bind('v', FlagAction.UNGREEDY).bind('v', FlagAction.MULTILINE))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> FlagBindings.builder().bind('3', FlagAction.UNGREEDY))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> FlagBindings.builder().bind('ä', FlagAction.UNGREEDY))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
