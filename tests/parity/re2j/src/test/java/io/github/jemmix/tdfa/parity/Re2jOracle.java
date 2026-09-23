package io.github.jemmix.tdfa.parity;

import com.google.re2j.Re2jUnicodeProvider;
import io.github.jemmix.tdfa.core.Matcher;
import io.github.jemmix.tdfa.core.RegexEngineFactory;
import io.github.jemmix.tdfa.tdfa.TdfaRunner;
import io.github.jemmix.tdfa.unicode.UnicodeDataProvider;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Oracle helpers: compile each pattern in both com.google.re2j (upstream) and
 * io.github.jemmix.tdfa.re2j (our shim), then compare results.
 *
 * Every tdfa helper accepts an {@link RegexEngineFactory} so tests can run on both
 * ASM and VM backends via {@code @MethodSource("engineFactories")}.
 */
public final class Re2jOracle {

    /** re2j-exact Unicode tables so parity tests are bit-exact against re2j, not JDK-version-dependent. */
    private static final UnicodeDataProvider UNICODE = Re2jUnicodeProvider.INSTANCE;

    private Re2jOracle() {
    }

    /**
     * Which re2j is on the test classpath: released 1.8 (true) or the
     * patched fork (false). Probed, not declared — the lone-low surrogate
     * pair-interior behavior is the discriminator (same probe as the
     * fuzzer).
     */
    public static boolean releasedOracle() {
        return com.google.re2j.Pattern.compile("\uDC21").matcher("a\uD801\uDC21zz").find();
    }

    /**
     * Engine compositions for parameterized tests: {@code null} = default
     * per-pattern generation (ASM); {@code TdfaRunner::new} = bring-your-own
     * interpreter via the generic shell — exercising BYO-engine composition
     * through every parity case.
     */
    public static Stream<RegexEngineFactory> engineFactories() {
        return Stream.<RegexEngineFactory>of(null, TdfaRunner::new);
    }

    // ---- re2j helpers (no engine parameter — upstream oracle) ----

    public static int[] re2jFind(String pattern, String input) {
        com.google.re2j.Matcher m = com.google.re2j.Pattern.compile(pattern).matcher(input);
        if (!m.find()) {
            return null;
        }
        int gc = m.groupCount();
        int[] out = new int[2 + 2 * gc];
        out[0] = m.start();
        out[1] = m.end();
        for (int g = 1; g <= gc; g++) {
            try {
                out[2 * g] = m.start(g);
                out[2 * g + 1] = m.end(g);
            } catch (IllegalStateException e) {
                out[2 * g] = -1;
                out[2 * g + 1] = -1;
            }
        }
        return out;
    }

    public static int[] re2jFindPosix(String pattern, String input) {
        com.google.re2j.Matcher m = com.google.re2j.Pattern.compile(pattern, com.google.re2j.Pattern.LONGEST_MATCH).matcher(input);
        if (!m.find()) {
            return null;
        }
        int gc = m.groupCount();
        int[] out = new int[2 + 2 * gc];
        out[0] = m.start();
        out[1] = m.end();
        for (int g = 1; g <= gc; g++) {
            try {
                out[2 * g] = m.start(g);
                out[2 * g + 1] = m.end(g);
            } catch (IllegalStateException e) {
                out[2 * g] = -1;
                out[2 * g + 1] = -1;
            }
        }
        return out;
    }

    public static List<String> re2jFindAll(String pattern, String input) {
        List<String> out = new ArrayList<>();
        com.google.re2j.Matcher m = com.google.re2j.Pattern.compile(pattern).matcher(input);
        while (m.find()) {
            out.add(m.group());
        }
        return out;
    }

    // ---- tdfa helpers (engine-parameterised) ----

    /** tdfa under its DEFAULT fold universe (JDK-derived, modern orbits) —
     *  the engine-truth lane: what the library does when not pinned to an
     *  oracle/snapshot fold universe. */
    public static int[] tdfaFindDefaultUniverse(String pattern, String input) {
        return tdfaSpans(io.github.jemmix.tdfa.Pattern.compile(pattern, 0, null, null).matcher(input));
    }

    public static int[] tdfaFind(String pattern, String input, RegexEngineFactory factory) {
        Matcher m = io.github.jemmix.tdfa.Pattern.compile(pattern, 0, factory, UNICODE).matcher(input);
        return tdfaSpans(m);
    }

    private static int[] tdfaSpans(Matcher m) {
        if (!m.find()) {
            return null;
        }
        int gc = m.groupCount();
        int[] out = new int[2 + 2 * gc];
        out[0] = m.start();
        out[1] = m.end();
        for (int g = 1; g <= gc; g++) {
            try {
                out[2 * g] = m.start(g);
                out[2 * g + 1] = m.end(g);
            } catch (IllegalStateException e) {
                out[2 * g] = -1;
                out[2 * g + 1] = -1;
            }
        }
        return out;
    }

    public static int[] tdfaFindPosix(String pattern, String input, RegexEngineFactory factory) {
        Matcher m = io.github.jemmix.tdfa.Pattern.compile(pattern, io.github.jemmix.tdfa.Pattern.LONGEST_MATCH, factory, UNICODE).matcher(input);
        if (!m.find()) {
            return null;
        }
        int gc = m.groupCount();
        int[] out = new int[2 + 2 * gc];
        out[0] = m.start();
        out[1] = m.end();
        for (int g = 1; g <= gc; g++) {
            try {
                out[2 * g] = m.start(g);
                out[2 * g + 1] = m.end(g);
            } catch (IllegalStateException e) {
                out[2 * g] = -1;
                out[2 * g + 1] = -1;
            }
        }
        return out;
    }

    public static List<String> tdfaFindAll(String pattern, String input, RegexEngineFactory factory) {
        List<String> out = new ArrayList<>();
        Matcher m = io.github.jemmix.tdfa.Pattern.compile(pattern, 0, factory, UNICODE).matcher(input);
        while (m.find()) {
            out.add(m.group());
        }
        return out;
    }

    // ---- assertSame helpers ----

    public static void assertSameFind(String pattern, String input, RegexEngineFactory factory) {
        int[] expected = re2jFind(pattern, input);
        int[] actual = tdfaFind(pattern, input, factory);
        assertThat(actual).as("pattern=\"%s\" input=\"%s\" [%s]", pattern, input, factory).isEqualTo(expected);
    }

    public static void assertSameFindPosix(String pattern, String input, RegexEngineFactory factory) {
        int[] expected = re2jFindPosix(pattern, input);
        int[] actual = tdfaFindPosix(pattern, input, factory);
        assertThat(actual).as("POSIX pattern=\"%s\" input=\"%s\" [%s]", pattern, input, factory).isEqualTo(expected);
    }

    public static void assertSameAllMatches(String pattern, String input, RegexEngineFactory factory) {
        List<String> expected = re2jFindAll(pattern, input);
        List<String> actual = tdfaFindAll(pattern, input, factory);
        assertThat(actual).as("findAll pattern=\"%s\" input=\"%s\" [%s]", pattern, input, factory).isEqualTo(expected);
    }

    public static void assertSameCompileSuccess(String pattern, RegexEngineFactory factory) {
        com.google.re2j.Pattern.compile(pattern);
        io.github.jemmix.tdfa.Pattern.compile(pattern, 0, factory, UNICODE);
    }

    public static void assertSameCompileReject(String pattern, RegexEngineFactory factory) {
        boolean re2jThrew = false, tdfaThrew = false;
        try {
            com.google.re2j.Pattern.compile(pattern);
        } catch (Exception e) {
            re2jThrew = true;
        }
        try {
            io.github.jemmix.tdfa.Pattern.compile(pattern, 0, factory, UNICODE);
        } catch (Exception e) {
            tdfaThrew = true;
        }
        assertThat(re2jThrew).as("re2j should reject: %s", pattern).isTrue();
        assertThat(tdfaThrew).as("tdfa should reject: %s [%s]", pattern, factory).isTrue();
    }
}
