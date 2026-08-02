package io.github.jemmix.tdfa.parity;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Oracle helpers: compile each pattern in both com.google.re2j (upstream) and
 * io.github.jemmix.tdfa.re2j (our shim), then compare results.
 *
 * Disabled-test convention:
 *   @Disabled("TDFA_MISSING: ...")  — re2j supports it, we don't (parity gap)
 *   @Disabled("RE2J_MISSING: ...")  — neither re2j nor we support it (not a gap)
 */
public final class Re2jOracle {

    private Re2jOracle() {}

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

    public static int[] tdfaFind(String pattern, String input) {
        io.github.jemmix.tdfa.re2j.Matcher m =
                io.github.jemmix.tdfa.re2j.Pattern.compile(pattern).matcher(input);
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

    public static int[] tdfaFindPosix(String pattern, String input) {
        io.github.jemmix.tdfa.re2j.Matcher m =
                io.github.jemmix.tdfa.re2j.Pattern.compile(pattern,
                        io.github.jemmix.tdfa.re2j.Pattern.LONGEST_MATCH).matcher(input);
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

    public static void assertSameFind(String pattern, String input) {
        int[] expected = re2jFind(pattern, input);
        int[] actual = tdfaFind(pattern, input);
        assertThat(actual).as("pattern=\"%s\" input=\"%s\"", pattern, input)
                .isEqualTo(expected);
    }

    public static void assertSameFindPosix(String pattern, String input) {
        int[] expected = re2jFindPosix(pattern, input);
        int[] actual = tdfaFindPosix(pattern, input);
        assertThat(actual).as("POSIX pattern=\"%s\" input=\"%s\"", pattern, input)
                .isEqualTo(expected);
    }

    public static List<String> re2jFindAll(String pattern, String input) {
        List<String> out = new ArrayList<>();
        com.google.re2j.Matcher m = com.google.re2j.Pattern.compile(pattern).matcher(input);
        while (m.find()) out.add(m.group());
        return out;
    }

    public static List<String> tdfaFindAll(String pattern, String input) {
        List<String> out = new ArrayList<>();
        io.github.jemmix.tdfa.re2j.Matcher m =
                io.github.jemmix.tdfa.re2j.Pattern.compile(pattern).matcher(input);
        while (m.find()) out.add(m.group());
        return out;
    }

    public static void assertSameAllMatches(String pattern, String input) {
        List<String> expected = re2jFindAll(pattern, input);
        List<String> actual = tdfaFindAll(pattern, input);
        assertThat(actual).as("findAll pattern=\"%s\" input=\"%s\"", pattern, input)
                .isEqualTo(expected);
    }

    public static void assertSameCompileSuccess(String pattern) {
        com.google.re2j.Pattern.compile(pattern);
        io.github.jemmix.tdfa.re2j.Pattern.compile(pattern);
    }

    public static void assertSameCompileReject(String pattern) {
        boolean re2jThrew = false, tdfaThrew = false;
        try { com.google.re2j.Pattern.compile(pattern); } catch (Exception e) { re2jThrew = true; }
        try { io.github.jemmix.tdfa.re2j.Pattern.compile(pattern); } catch (Exception e) { tdfaThrew = true; }
        assertThat(re2jThrew).as("re2j should reject: %s", pattern).isTrue();
        assertThat(tdfaThrew).as("tdfa should reject: %s", pattern).isTrue();
    }
}
