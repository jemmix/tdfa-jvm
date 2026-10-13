package io.github.jemmix.tdfa.re2j;

import io.github.jemmix.tdfa.core.compile.CompileOptions;
import io.github.jemmix.tdfa.core.compile.PatternMatcher;
import io.github.jemmix.tdfa.core.compile.TDFAPattern;
import io.github.jemmix.tdfa.core.dfa.TdfaRunner;
import io.github.jemmix.tdfa.core.tnfa.Semantics;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The re2j surface, pinned: the RE2-lineage lane is baked in (every
 * JUR-compat axis set by construction — there are no opt-out bits to
 * forget), the flag roster is the re2j dial set (+ the two documented
 * tdfa extensions), serialization recompiles through this surface, and
 * the facade's jur default answers differently on the same inputs — the
 * two-surface split's whole point.
 */
class Re2jSurfaceTest {

    private static final String SURFACE_MISMATCH =
        "Flags should only be a combination of MULTILINE, DOTALL," + " CASE_INSENSITIVE, DISABLE_UNICODE_GROUPS,";

    @Test
    void dollarMatchesEndOfTextOnlyTheRe2Reading() {
        // RE2 axis: $ == \z. java.util.regex allows a final terminator.
        assertThat(Pattern.compile("a$").matcher("a\n").find()).isFalse();
        assertThat(Pattern.compile("a$").matcher("a").find()).isTrue();
        // Contrast: the jur facade default matches "a\n" here.
        assertThat(io.github.jemmix.tdfa.Pattern.compile("a$").matcher("a\n").find()).isTrue();
    }

    @Test
    void multilineCaretMatchesTheEmptyLastLineTheRe2Reading() {
        // RE2 axis: (?m)^ may match after a trailing terminator.
        PatternMatcher m = Pattern.compile("(?m)^").matcher("a\n");
        assertThat(m.find()).isTrue();
        assertThat(m.start()).isZero();
        assertThat(m.find()).isTrue();
        assertThat(m.start()).isEqualTo(2);
        assertThat(m.end()).isEqualTo(2);
        assertThat(m.find()).isFalse();
        // Contrast: the jur facade default matches at 0 only.
        PatternMatcher jur = io.github.jemmix.tdfa.Pattern.compile("(?m)^").matcher("a\n");
        assertThat(jur.find()).isTrue();
        assertThat(jur.find()).isFalse();
    }

    @Test
    void caratRecognizesOnlyNewlineAsTerminator() {
        // RE2 axis: \r is not a line terminator.
        assertThat(Pattern.compile("(?m)^b").matcher("a\rb").find()).isFalse();
        // Contrast: the jur facade default recognizes the full JDK set.
        assertThat(io.github.jemmix.tdfa.Pattern.compile("(?m)^b").matcher("a\rb").find()).isTrue();
    }

    @Test
    void inlineUFoldsTheFullUniverseByDefault() {
        // RE2 fold universe: bare (?i) is the full simple-fold set —
        // the kelvin sign is in k's orbit.
        assertThat(Pattern.compile("(?i)k").matcher("\u212A").find()).isTrue();
        // Contrast: the jur facade default folds ASCII only.
        assertThat(io.github.jemmix.tdfa.Pattern.compile("(?i)k").matcher("\u212A").find()).isFalse();
    }

    @Test
    void inlineUMeansUngreedy() {
        // RE2 axis: (?U) flips quantifier greediness.
        PatternMatcher m = Pattern.compile("(?U)a+").matcher("aaa");
        assertThat(m.find()).isTrue();
        assertThat(m.start()).isZero();
        assertThat(m.end()).isEqualTo(1);
        // Contrast: the jur facade default reads (?U) as scoped UNICODE_CASE.
        PatternMatcher jur = io.github.jemmix.tdfa.Pattern.compile("(?U)a+").matcher("aaa");
        assertThat(jur.find()).isTrue();
        assertThat(jur.end()).isEqualTo(3);
    }

    @Test
    void boundariesAreCodepointDisciplined() {
        // RE2 axis: scans skip surrogate-pair interiors — dot matches the
        // whole codepoint, once.
        PatternMatcher m = Pattern.compile(".").matcher("\uD83D\uDE00");
        assertThat(m.find()).isTrue();
        assertThat(m.start()).isZero();
        assertThat(m.end()).isEqualTo(2);
        assertThat(m.find()).isFalse();
    }

    @Test
    void zeroWidthIterationReportsItsSpan() {
        // RE2 axis: a sole zero-width iteration reports its group span.
        PatternMatcher m = Pattern.compile("(\\z)*").matcher("");
        assertThat(m.find()).isTrue();
        assertThat(m.start(1)).isZero();
        assertThat(m.end(1)).isZero();
        // Contrast: the jur facade default leaves the group non-participating.
        PatternMatcher jur = io.github.jemmix.tdfa.Pattern.compile("(\\z)*").matcher("");
        assertThat(jur.find()).isTrue();
        assertThat(jur.start(1)).isEqualTo(-1);
    }

    @Test
    void flagRosterIsTheRe2jDialSet() {
        // Every roster bit accepted, round-trips, composes.
        int all = Pattern.CASE_INSENSITIVE | Pattern.DOTALL | Pattern.MULTILINE | Pattern.LONGEST_MATCH
            | Pattern.DISABLE_UNICODE_GROUPS | Pattern.FIND_ONLY | Pattern.MULTI_VALUED_TAGS;
        assertThat(Pattern.compile("a", all).flags()).isEqualTo(all);
        assertThat(Pattern.compile("(?<w>\\w+)-(\\d+)").flags()).isZero();
        assertThat(Pattern.compile("(?<w>\\w+)-(\\d+)").groupCount()).isEqualTo(2);
        assertThat(Pattern.compile("(?<w>x)").namedGroups()).containsEntry("w", 1);
        assertThat(Pattern.compile("ab").programSize()).isPositive();
    }

    @Test
    void jurOnlyBitsAreRejected() {
        // No UNICODE_CHARACTER_CLASS (the lane's class sets are the re2j
        // reading), no JUR-compat opt-out bits (the lane is the default).
        assertThatThrownBy(() -> Pattern.compile("a", 32)).isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining(SURFACE_MISMATCH);
        assertThatThrownBy(() -> Pattern.compile("a", 256)).isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining(SURFACE_MISMATCH);
        assertThatThrownBy(() -> Pattern.compile("a", 1 << 20)).isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining(SURFACE_MISMATCH);
    }

    @Test
    void compileFindRefusesTheWholeSurface() {
        Pattern p = Pattern.compileFind("a+");
        assertThat(p.matcher("aaa").find()).isTrue();
        assertThatThrownBy(() -> p.matcher("aaa").matches()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void longestMatchSelectsLeftmostLongest() {
        PatternMatcher m = Pattern.compile("a|ab", Pattern.LONGEST_MATCH).matcher("ab");
        assertThat(m.find()).isTrue();
        assertThat(m.end()).isEqualTo(2);
    }

    @Test
    void compileOptionsIsThePerAxisEscapeHatch() {
        // A jur-lane Semantics on the re2j surface selects that reading —
        // mixed-axis probes are a debugging tool, not a user contract.
        PatternMatcher m = Pattern.compile("a$", CompileOptions.of().semantics(Semantics.of())).matcher("a\n");
        assertThat(m.find()).isTrue();
        assertThat(m.end()).isEqualTo(1);
        // And the plain options (POSIX longest) ride the surface default.
        PatternMatcher l = Pattern.compile("a|ab", CompileOptions.of().longestMatch()).matcher("ab");
        assertThat(l.find()).isTrue();
        assertThat(l.end()).isEqualTo(2);
    }

    @Test
    void engineFactoryRouteServesTheInterpreter() {
        Pattern p = Pattern.compile("a+", 0, TdfaRunner::new);
        assertThat(p).isInstanceOf(TDFAPattern.class);
        PatternMatcher m = p.matcher("aaa");
        assertThat(m.find()).isTrue();
        assertThat(m.start()).isZero();
        assertThat(m.end()).isEqualTo(3);
    }

    @Test
    void vmSwitchHandsOutTheWrappedSurfaceCarrier() {
        String prev = System.getProperty("tdfa.engine");
        try {
            System.setProperty("tdfa.engine", "VM");
            Pattern p = Pattern.compile("a+");
            assertThat(p).isInstanceOf(TDFAPattern.class);
            assertThat(p.getClass().getSimpleName()).isEqualTo("VmPattern");
            assertThat(p.matcher("aaa").find()).isTrue();
        } finally {
            if (prev == null) {
                System.clearProperty("tdfa.engine");
            } else {
                System.setProperty("tdfa.engine", prev);
            }
        }
    }

    @Test
    void surfacesAreDistinctInEquality() {
        // Same regex + same flags, different surface: different lanes,
        // never equal.
        assertThat(Pattern.compile("a")).isNotEqualTo(io.github.jemmix.tdfa.Pattern.compile("a"));
        assertThat(Pattern.compile("a")).isEqualTo(Pattern.compile("a"));
        assertThat(Pattern.compile("a", Pattern.MULTILINE)).isNotEqualTo(Pattern.compile("a"));
    }

    @Test
    void serializationRecompilesThroughThisSurface() throws Exception {
        Pattern p = Pattern.compile("a$");
        assertThat(p).isInstanceOf(TDFAPattern.class);
        assertThat(((TDFAPattern) p).surface().id()).isEqualTo("re2j");

        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ObjectOutputStream out = new ObjectOutputStream(bos)) {
            out.writeObject(p);
        }
        Object copy;
        try (ObjectInputStream in = new ObjectInputStream(new ByteArrayInputStream(bos.toByteArray()))) {
            copy = in.readObject();
        }
        assertThat(copy).isEqualTo(p);
        // The lane survived the round trip: still the re2j reading.
        assertThat(((TDFAPattern) copy).surface().id()).isEqualTo("re2j");
        assertThat(((Pattern) copy).matcher("a\n").find()).isFalse();
        assertThat(((Pattern) copy).matcher("a").find()).isTrue();

        // Facade patterns round-trip through their own surface.
        io.github.jemmix.tdfa.Pattern jur = io.github.jemmix.tdfa.Pattern.compile("a$");
        bos = new ByteArrayOutputStream();
        try (ObjectOutputStream out = new ObjectOutputStream(bos)) {
            out.writeObject(jur);
        }
        try (ObjectInputStream in = new ObjectInputStream(new ByteArrayInputStream(bos.toByteArray()))) {
            assertThat(in.readObject()).isEqualTo(jur);
        }
    }
}
