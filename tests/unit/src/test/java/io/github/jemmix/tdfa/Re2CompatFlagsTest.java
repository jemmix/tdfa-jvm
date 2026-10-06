package io.github.jemmix.tdfa;

import io.github.jemmix.tdfa.core.budget.Budgets;
import io.github.jemmix.tdfa.core.budget.WorkMeter;
import io.github.jemmix.tdfa.core.determinize.Determinizer;
import io.github.jemmix.tdfa.core.dfa.TdfaRunner;
import io.github.jemmix.tdfa.core.tnfa.Semantics;
import io.github.jemmix.tdfa.core.tnfa.Tnfa;
import io.github.jemmix.tdfa.core.unicode.UnicodeProviders;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The JUR-compat flag-surface boilerplate
 * ({@code docs/jur-compat-default.md}): the seven opt-out bits and the
 * {@code RE2_COMPAT} preset exist, validate, and ride the pipeline
 * (PatternCompiler &rarr; {@code Tnfa.semantics} &rarr;
 * {@code Tdfa.semantics()} &rarr; the runner's frozen field) — but
 * select nothing yet. Pinned here:
 * <ul>
 *   <li>the preset composes: {@code RE2_COMPAT} is exactly the OR of the
 *       seven axis bits, disjoint from the eight pre-existing flags,
 *       every axis bit distinct;</li>
 *   <li>every bit (alone, as the preset, and composed with the legacy
 *       flags) is accepted by every compile spelling and round-trips
 *       {@code flags()}; bits outside the whitelist still reject with
 *       the message naming the new flags;</li>
 *   <li>pre-flip no-op: on a battery spanning all six divergence
 *       families, compiling with any axis bit or the preset is
 *       behavior-identical to compiling without it — {@code matches()},
 *       {@code lookingAt()} and the full {@code find()} scan with every
 *       group span — on both engine tiers (generated shells and the
 *       shared interpreter);</li>
 *   <li>the bits participate in {@code equals} and serialization (the
 *       flags int is capability identity, the FIND_ONLY contract);</li>
 *   <li>the pipeline carries {@link Semantics#RE2} pre-flip — the
 *       carrier is wired Tnfa &rarr; Tdfa ready for the pivot
 *       parameterization to read, and {@link Semantics} itself is a
 *       well-behaved value class.</li>
 * </ul>
 */
class Re2CompatFlagsTest {

    private static final int[] AXES = {Pattern.UNIX_LINES, Pattern.UNICODE_CASE, Pattern.CODEPOINT_BOUNDARIES,
        Pattern.EMPTY_LAST_LINE, Pattern.END_OF_TEXT_ONLY, Pattern.EMPTY_ITERATION_SPANS, Pattern.UNGREEDY_U};

    private static final int LEGACY =
        Pattern.CASE_INSENSITIVE | Pattern.DOTALL | Pattern.MULTILINE | Pattern.DISABLE_UNICODE_GROUPS
            | Pattern.LONGEST_MATCH | Pattern.UNICODE_CHARACTER_CLASS | Pattern.FIND_ONLY | Pattern.MULTI_VALUED_TAGS;

    /**
     * Family-representative regex/input pairs (the design's six
     * divergence families + the fold and (?U axes): terminator set,
     * caret/dollar-around-final-terminator, surrogate units and pairs,
     * unit-boundary wordness, group-participation lineage, fold
     * universe, ungreedy). The no-op property does not depend on what
     * today's answers ARE — only that the bits do not move them.
     */
    private static final String[][] FAMILY_BATTERY = {{".", "a\r\nb"}, {".+", "a\u0085b\u2028c\nd"}, {"(?m)^", "a\n"},
        {"(?m)^b", "a\nb"}, {"a$", "a\n"}, {"(?m)a$", "a\n\n"}, {"\\b", "\uD800x"}, {".", "\uD800"},
        {"(.)", "\uD83D\uDE00"}, {"\uD83D\uDE00", "\uD83D\uDE00"}, {"(\\z)*", ""}, {"(a|)*", "aa"}, {"(?i)\u017F", "S"},
        {"(?i)K", "k\u212A"}, {"(?U)a+", "aaa"}, {"(?i)(a|ab)", "ABab"},};

    /** The preset is the OR of the axes, nothing more, nothing shared. */
    @Test
    void presetComposesTheSevenAxes() {
        int or = 0;
        for (int axis : AXES) {
            assertThat(axis & LEGACY).as("axis 0x%x overlaps the legacy flag mask", axis).isZero();
            or |= axis;
        }
        assertThat(Pattern.RE2_COMPAT).isEqualTo(or);
        assertThat(Pattern.RE2_COMPAT & LEGACY).isZero();
        for (int i = 0; i < AXES.length; i++) {
            for (int j = i + 1; j < AXES.length; j++) {
                assertThat(AXES[i] & AXES[j]).as("axes 0x%x and 0x%x overlap", AXES[i], AXES[j]).isZero();
            }
        }
    }

    /** Every axis bit and the preset validate, on every compile spelling, and round-trip flags(). */
    @Test
    void bitsAreAcceptedAndRoundTrip() {
        int[] lanes = {Pattern.RE2_COMPAT, Pattern.UNIX_LINES, Pattern.UNICODE_CASE, Pattern.CODEPOINT_BOUNDARIES,
            Pattern.EMPTY_LAST_LINE, Pattern.END_OF_TEXT_ONLY, Pattern.EMPTY_ITERATION_SPANS, Pattern.UNGREEDY_U};
        for (int lane : lanes) {
            assertThat(Pattern.compile("a+", lane).flags()).isEqualTo(lane);
            assertThat(Pattern.compile("a+", lane, TdfaRunner::new).flags()).isEqualTo(lane);
            int combo = lane | Pattern.MULTILINE | Pattern.CASE_INSENSITIVE;
            assertThat(Pattern.compile("(?m)(?i)a+", combo).flags()).isEqualTo(combo);
        }
        assertThat(Pattern.compileFind("a+", Pattern.RE2_COMPAT).flags())
            .isEqualTo(Pattern.RE2_COMPAT | Pattern.FIND_ONLY);
    }

    /** Bits outside the whitelist still reject, naming the new surface. */
    @Test
    void unknownBitsStillReject() {
        for (int unknown : new int[]{1 << 17, 1 << 24}) {
            assertThatThrownBy(() -> Pattern.compile("a+", unknown)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("UNIX_LINES").hasMessageContaining("RE2_COMPAT");
        }
    }

    /**
     * The pre-flip contract: the bits move nothing. Per family battery
     * entry, per axis bit and the preset (plus one legacy-composed
     * lane), per engine tier — the whole observable surface (whole
     * match, lookingAt, find scan with all group spans) equals the
     * no-bits compile under the same engine.
     */
    @Test
    void preFlipNoOpEquivalence() {
        int[] lanes = new int[AXES.length + 2];
        System.arraycopy(AXES, 0, lanes, 0, AXES.length);
        lanes[AXES.length] = Pattern.RE2_COMPAT;
        lanes[AXES.length + 1] = Pattern.RE2_COMPAT | Pattern.MULTILINE | Pattern.CASE_INSENSITIVE;
        int baseLane = Pattern.MULTILINE | Pattern.CASE_INSENSITIVE;
        for (String[] row : FAMILY_BATTERY) {
            String regex = row[0];
            String input = row[1];
            for (boolean vm : new boolean[]{false, true}) {
                String tier = vm ? "interpreter" : "generated";
                List<String> expected = observable(compile(regex, baseLane, vm), input);
                for (int lane : lanes) {
                    List<String> actual = observable(compile(regex, baseLane | lane, vm), input);
                    assertThat(actual).as("{} tier: lane 0x{} moved /{}/ on [{}]", tier, Integer.toHexString(lane),
                        regex, escape(input)).isEqualTo(expected);
                }
            }
        }
    }

    /** The flags int is capability identity (FIND_ONLY contract): bits participate in equals. */
    @Test
    void flagsParticipateInEquals() {
        assertThat(Pattern.compile("a+")).isNotEqualTo(Pattern.compile("a+", Pattern.RE2_COMPAT));
        assertThat(Pattern.compile("a+", Pattern.RE2_COMPAT)).isNotEqualTo(Pattern.compile("a+", Pattern.UNIX_LINES));
        assertThat(Pattern.compile("a+", Pattern.RE2_COMPAT)).isEqualTo(Pattern.compile("a+", Pattern.RE2_COMPAT));
    }

    /** The bits survive serialization (pattern + flags is the serial form). */
    @Test
    void bitsRoundTripSerialization() throws Exception {
        Pattern p = Pattern.compile("stra\u00dfe", Pattern.RE2_COMPAT | Pattern.CASE_INSENSITIVE);
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ObjectOutputStream oos = new ObjectOutputStream(bos)) {
            oos.writeObject(p);
        }
        Pattern q;
        try (ObjectInputStream ois = new ObjectInputStream(new ByteArrayInputStream(bos.toByteArray()))) {
            q = (Pattern) ois.readObject();
        }
        assertThat(q.flags()).isEqualTo(Pattern.RE2_COMPAT | Pattern.CASE_INSENSITIVE);
        assertThat(q).isEqualTo(p);
    }

    /** The carrier is wired end-to-end and pre-flip always RE2; Semantics is a value class. */
    @Test
    void pipelineCarriesRe2PreFlip() {
        Tnfa nfa = Tnfa.compile("(?i)a+");
        assertThat(nfa.semantics).isEqualTo(Semantics.RE2);
        assertThat(Determinizer.compile(nfa).semantics()).isEqualTo(Semantics.RE2);
        Tnfa explicit = Tnfa.compile("(?i)a+", false, false, false,
            Semantics.of().unixLines().unicodeCase().codepointBoundaries().emptyLastLine().endOfTextOnly()
                .emptyIterationSpans().ungreedyU(),
            UnicodeProviders.get(), null, new WorkMeter(Budgets.compileComputeTicks()));
        assertThat(explicit.semantics).isEqualTo(Semantics.RE2);
        assertThat(explicit.semantics.isUnixLines()).isTrue();
        assertThat(Semantics.of()).isNotEqualTo(Semantics.RE2);
        Semantics once = Semantics.of().unixLines();
        assertThat(once.unixLines()).isSameAs(once);
        assertThat(Semantics.RE2.toString()).contains("UNIX_LINES").contains("UNGREEDY_U");
        assertThat(Semantics.of().toString()).isEqualTo("Semantics[]");
    }

    private static Pattern compile(String regex, int flags, boolean vm) {
        return Pattern.compile(regex, flags, vm ? TdfaRunner::new : null);
    }

    /** Whole match, lookingAt, and the find scan with every group span — the observable surface. */
    private static List<String> observable(Pattern p, CharSequence input) {
        List<String> out = new ArrayList<>();
        out.add("matches=" + p.matches(input.toString()));
        PatternMatcher looking = p.matcher(input);
        boolean la = looking.lookingAt();
        out.add(la ? "lookingAt=@" + looking.start() + ".." + looking.end() : "lookingAt=-");
        PatternMatcher m = p.matcher(input);
        while (m.find()) {
            StringBuilder row = new StringBuilder(m.start() + ".." + m.end());
            for (int g = 1; g <= p.groupCount(); g++) {
                row.append(' ').append(g).append(':').append(m.start(g)).append('-').append(m.end(g));
            }
            out.add(row.toString());
        }
        return out;
    }

    private static String escape(String s) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < 0x20 || c > 0x7e) {
                sb.append(String.format("\\u%04X", (int) c));
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}
