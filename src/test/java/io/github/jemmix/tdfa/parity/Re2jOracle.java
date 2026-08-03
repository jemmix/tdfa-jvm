package io.github.jemmix.tdfa.parity;

import io.github.jemmix.tdfa.EngineFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Oracle helpers: compile each pattern in both com.google.re2j (upstream) and
 * io.github.jemmix.tdfa.re2j (our shim), then compare results.
 *
 * Every tdfa helper accepts an {@link EngineFactory} so tests can run on both
 * ASM and VM backends via {@code @MethodSource("engineFactories")}.
 *
 * Pending-test convention:
 *   @EnabledIfSystemProperty(named = "tdfa.pending", matches = "true")
 *   // PENDING: <description of what needs to be implemented>
 *   Run with -Dtdfa.pending=true to include pending tests.
 */
public final class Re2jOracle {

    private Re2jOracle() {}

    /** Provides both built-in engines for parameterized tests. */
    public static Stream<EngineFactory> engineFactories() {
        return Stream.of(EngineFactory.ASM, EngineFactory.VM);
    }

    // ---- re2j helpers (no engine parameter — upstream oracle) ----

    public static int[] re2jFind(String pattern, String input) {
        com.google.re2j.Matcher m = com.google.re2j.Pattern.compile(pattern).matcher(input);
        if (!m.find()) return null;
        int gc = m.groupCount();
        int[] out = new int[2 + 2 * gc];
        out[0] = m.start(); out[1] = m.end();
        for (int g = 1; g <= gc; g++) {
            try { out[2*g] = m.start(g); out[2*g+1] = m.end(g); }
            catch (IllegalStateException e) { out[2*g] = -1; out[2*g+1] = -1; }
        }
        return out;
    }

    public static int[] re2jFindPosix(String pattern, String input) {
        com.google.re2j.Matcher m = com.google.re2j.Pattern.compile(pattern,
                com.google.re2j.Pattern.LONGEST_MATCH).matcher(input);
        if (!m.find()) return null;
        int gc = m.groupCount();
        int[] out = new int[2 + 2 * gc];
        out[0] = m.start(); out[1] = m.end();
        for (int g = 1; g <= gc; g++) {
            try { out[2*g] = m.start(g); out[2*g+1] = m.end(g); }
            catch (IllegalStateException e) { out[2*g] = -1; out[2*g+1] = -1; }
        }
        return out;
    }

    public static List<String> re2jFindAll(String pattern, String input) {
        List<String> out = new ArrayList<>();
        com.google.re2j.Matcher m = com.google.re2j.Pattern.compile(pattern).matcher(input);
        while (m.find()) out.add(m.group());
        return out;
    }

    // ---- tdfa helpers (engine-parameterised) ----

    public static int[] tdfaFind(String pattern, String input, EngineFactory factory) {
        io.github.jemmix.tdfa.re2j.Matcher m =
                io.github.jemmix.tdfa.re2j.Pattern.compile(pattern, 0, factory).matcher(input);
        if (!m.find()) return null;
        int gc = m.groupCount();
        int[] out = new int[2 + 2 * gc];
        out[0] = m.start(); out[1] = m.end();
        for (int g = 1; g <= gc; g++) {
            try { out[2*g] = m.start(g); out[2*g+1] = m.end(g); }
            catch (IllegalStateException e) { out[2*g] = -1; out[2*g+1] = -1; }
        }
        return out;
    }

    public static int[] tdfaFindPosix(String pattern, String input, EngineFactory factory) {
        io.github.jemmix.tdfa.re2j.Matcher m =
                io.github.jemmix.tdfa.re2j.Pattern.compile(pattern,
                        io.github.jemmix.tdfa.re2j.Pattern.LONGEST_MATCH, factory).matcher(input);
        if (!m.find()) return null;
        int gc = m.groupCount();
        int[] out = new int[2 + 2 * gc];
        out[0] = m.start(); out[1] = m.end();
        for (int g = 1; g <= gc; g++) {
            try { out[2*g] = m.start(g); out[2*g+1] = m.end(g); }
            catch (IllegalStateException e) { out[2*g] = -1; out[2*g+1] = -1; }
        }
        return out;
    }

    public static List<String> tdfaFindAll(String pattern, String input, EngineFactory factory) {
        List<String> out = new ArrayList<>();
        io.github.jemmix.tdfa.re2j.Matcher m =
                io.github.jemmix.tdfa.re2j.Pattern.compile(pattern, 0, factory).matcher(input);
        while (m.find()) out.add(m.group());
        return out;
    }

    // ---- assertSame helpers ----

    public static void assertSameFind(String pattern, String input, EngineFactory factory) {
        int[] expected = re2jFind(pattern, input);
        int[] actual = tdfaFind(pattern, input, factory);
        assertThat(actual).as("pattern=\"%s\" input=\"%s\" [%s]", pattern, input, factory)
                .isEqualTo(expected);
    }

    public static void assertSameFindPosix(String pattern, String input, EngineFactory factory) {
        int[] expected = re2jFindPosix(pattern, input);
        int[] actual = tdfaFindPosix(pattern, input, factory);
        assertThat(actual).as("POSIX pattern=\"%s\" input=\"%s\" [%s]", pattern, input, factory)
                .isEqualTo(expected);
    }

    public static void assertSameAllMatches(String pattern, String input, EngineFactory factory) {
        List<String> expected = re2jFindAll(pattern, input);
        List<String> actual = tdfaFindAll(pattern, input, factory);
        assertThat(actual).as("findAll pattern=\"%s\" input=\"%s\" [%s]", pattern, input, factory)
                .isEqualTo(expected);
    }

    public static void assertSameCompileSuccess(String pattern, EngineFactory factory) {
        com.google.re2j.Pattern.compile(pattern);
        io.github.jemmix.tdfa.re2j.Pattern.compile(pattern, 0, factory);
    }

    public static void assertSameCompileReject(String pattern, EngineFactory factory) {
        boolean re2jThrew = false, tdfaThrew = false;
        try { com.google.re2j.Pattern.compile(pattern); } catch (Exception e) { re2jThrew = true; }
        try { io.github.jemmix.tdfa.re2j.Pattern.compile(pattern, 0, factory); } catch (Exception e) { tdfaThrew = true; }
        assertThat(re2jThrew).as("re2j should reject: %s", pattern).isTrue();
        assertThat(tdfaThrew).as("tdfa should reject: %s [%s]", pattern, factory).isTrue();
    }
}
