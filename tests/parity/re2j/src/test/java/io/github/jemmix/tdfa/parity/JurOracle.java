package io.github.jemmix.tdfa.parity;

import io.github.jemmix.tdfa.core.compile.CompileOptions;
import io.github.jemmix.tdfa.core.compile.PatternMatcher;
import io.github.jemmix.tdfa.core.compile.PatternSurface;
import io.github.jemmix.tdfa.core.compile.RegexEngineFactory;
import io.github.jemmix.tdfa.core.dfa.TdfaRunner;
import io.github.jemmix.tdfa.core.tnfa.Semantics;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Jur oracle helpers — the default-lane twin of {@link Re2jOracle}: compile
 * each pattern in the live {@code java.util.regex} (the spec source this
 * lane targets) and through the jur facade
 * ({@code io.github.jemmix.tdfa.Pattern} — default lane, default JDK-derived
 * universe, so parity with the same-JVM oracle holds by construction), then
 * compare. The re2j parity suites widen with these twins per WBS item 7:
 * translatable cases run BOTH lanes on BOTH engine factories; shapes with no
 * jur reading document themselves RE2-lane-pinned.
 *
 * <p><b>The bit&harr;JDK-flag translation</b> ({@link #jdkFlags(int)}):
 * <ul>
 *   <li>{@code CASE_INSENSITIVE}/{@code DOTALL}/{@code MULTILINE}/
 *       {@code UNICODE_CHARACTER_CLASS} map to the same-named JDK flags.</li>
 *   <li>The machinery axes {@code UNIX_LINES} and {@code UNICODE_CASE} are
 *       NOT facade bits (out of the jur roster) — they are spellable through
 *       {@link CompileOptions#semantics(Semantics)} and map to the JDK flags
 *       of the same names ({@link #assertSemanticsSameFind} is that route;
 *       ASM-only, see its javadoc).</li>
 *   <li>{@code LONGEST_MATCH}, {@code MULTI_VALUED_TAGS}, {@code FIND_ONLY},
 *       {@code DISABLE_UNICODE_GROUPS} and the RE2-only spellings (ungreedy
 *       {@code (?U:}) have <em>no jur reading</em> — passing them rejects.</li>
 * </ul>
 *
 * <p><b>Group clauses are carried in full</b> — the span arrays include
 * every group, {@code -1} for non-participating (the family-6 battery's
 * live-JDK re-verification shape; no skeleton stripping).
 *
 * <p>Every tdfa helper accepts an {@link RegexEngineFactory} so twins run on
 * both ASM and VM backends, mirroring {@link Re2jOracle#engineFactories()}.
 */
public final class JurOracle {

    private JurOracle() {
    }

    /**
     * Engine compositions for parameterized tests — same composition as
     * {@link Re2jOracle#engineFactories()}: {@code null} = default
     * per-pattern generation (ASM); {@code TdfaRunner::new} = the
     * interpreter via the generic shell.
     */
    public static Stream<RegexEngineFactory> engineFactories() {
        return Stream.<RegexEngineFactory>of(null, TdfaRunner::new);
    }

    // ---- the bit<->JDK-flag translation ----

    /**
     * Translate facade flag bits to {@code java.util.regex.Pattern} flags:
     * {@code CASE_INSENSITIVE}/{@code DOTALL}/{@code MULTILINE}/
     * {@code UNICODE_CHARACTER_CLASS} one-to-one, plus the machinery axes
     * {@code UNIX_LINES}/{@code UNICODE_CASE} (the
     * {@link CompileOptions#semantics(Semantics)} route's JDK side).
     * {@code LONGEST_MATCH}, {@code MULTI_VALUED_TAGS}, {@code FIND_ONLY}
     * and {@code DISABLE_UNICODE_GROUPS} have no jur reading and reject —
     * a suite pinning those stays RE2-lane-pinned by construction.
     */
    public static int jdkFlags(int flags) {
        int jdk = 0;
        if ((flags & io.github.jemmix.tdfa.Pattern.CASE_INSENSITIVE) != 0) {
            jdk |= java.util.regex.Pattern.CASE_INSENSITIVE;
        }
        if ((flags & io.github.jemmix.tdfa.Pattern.DOTALL) != 0) {
            jdk |= java.util.regex.Pattern.DOTALL;
        }
        if ((flags & io.github.jemmix.tdfa.Pattern.MULTILINE) != 0) {
            jdk |= java.util.regex.Pattern.MULTILINE;
        }
        if ((flags & io.github.jemmix.tdfa.Pattern.UNICODE_CHARACTER_CLASS) != 0) {
            jdk |= java.util.regex.Pattern.UNICODE_CHARACTER_CLASS;
        }
        if ((flags & PatternSurface.UNIX_LINES) != 0) {
            jdk |= java.util.regex.Pattern.UNIX_LINES;
        }
        if ((flags & PatternSurface.UNICODE_CASE) != 0) {
            jdk |= java.util.regex.Pattern.UNICODE_CASE;
        }
        int noJurReading = flags & (io.github.jemmix.tdfa.Pattern.LONGEST_MATCH
            | io.github.jemmix.tdfa.Pattern.FIND_ONLY | io.github.jemmix.tdfa.Pattern.MULTI_VALUED_TAGS
            | io.github.jemmix.tdfa.Pattern.DISABLE_UNICODE_GROUPS);
        if (noJurReading != 0) {
            throw new IllegalArgumentException(
                "No jur reading for flags 0x" + Integer.toHexString(noJurReading) + " (RE2-lane-pinned bits)");
        }
        return jdk;
    }

    // ---- live java.util.regex oracle helpers (no engine parameter) ----

    public static int[] jurFind(String pattern, String input) {
        return jurFind(pattern, input, 0);
    }

    public static int[] jurFind(String pattern, String input, int flags) {
        Matcher m = java.util.regex.Pattern.compile(pattern, jdkFlags(flags)).matcher(input);
        return m.find() ? spans(m) : null;
    }

    public static List<String> jurFindAll(String pattern, String input) {
        return jurFindAll(pattern, input, 0);
    }

    public static List<String> jurFindAll(String pattern, String input, int flags) {
        List<String> out = new ArrayList<>();
        Matcher m = java.util.regex.Pattern.compile(pattern, jdkFlags(flags)).matcher(input);
        while (m.find()) {
            out.add(m.group());
        }
        return out;
    }

    /** Match spans with every group clause — the JDK reports unmatched groups as -1. */
    private static int[] spans(Matcher m) {
        int gc = m.groupCount();
        int[] out = new int[2 + 2 * gc];
        out[0] = m.start();
        out[1] = m.end();
        for (int g = 1; g <= gc; g++) {
            out[2 * g] = m.start(g);
            out[2 * g + 1] = m.end(g);
        }
        return out;
    }

    // ---- tdfa helpers: the jur facade, default lane + default universe ----

    public static int[] tdfaFind(String pattern, String input, RegexEngineFactory factory) {
        return tdfaFind(pattern, input, 0, factory);
    }

    public static int[] tdfaFind(String pattern, String input, int flags, RegexEngineFactory factory) {
        PatternMatcher m = io.github.jemmix.tdfa.Pattern.compile(pattern, flags, factory).matcher(input);
        if (!m.find()) {
            return null;
        }
        int gc = m.groupCount();
        int[] out = new int[2 + 2 * gc];
        out[0] = m.start();
        out[1] = m.end();
        for (int g = 1; g <= gc; g++) {
            out[2 * g] = m.start(g);
            out[2 * g + 1] = m.end(g);
        }
        return out;
    }

    public static List<String> tdfaFindAll(String pattern, String input, RegexEngineFactory factory) {
        return tdfaFindAll(pattern, input, 0, factory);
    }

    public static List<String> tdfaFindAll(String pattern, String input, int flags, RegexEngineFactory factory) {
        List<String> out = new ArrayList<>();
        PatternMatcher m = io.github.jemmix.tdfa.Pattern.compile(pattern, flags, factory).matcher(input);
        while (m.find()) {
            out.add(m.group());
        }
        return out;
    }

    // ---- assertSame helpers (facade vs the live java.util.regex oracle) ----

    public static void assertSameFind(String pattern, String input, RegexEngineFactory factory) {
        assertSameFind(pattern, input, 0, factory);
    }

    public static void assertSameFind(String pattern, String input, int flags, RegexEngineFactory factory) {
        int[] expected = jurFind(pattern, input, flags);
        int[] actual = tdfaFind(pattern, input, flags, factory);
        assertThat(actual).as("jur pattern=\"%s\" input=\"%s\" flags=0x%x [%s]", pattern, input, flags, factory)
            .isEqualTo(expected);
    }

    public static void assertSameAllMatches(String pattern, String input, RegexEngineFactory factory) {
        assertSameAllMatches(pattern, input, 0, factory);
    }

    public static void assertSameAllMatches(String pattern, String input, int flags, RegexEngineFactory factory) {
        List<String> expected = jurFindAll(pattern, input, flags);
        List<String> actual = tdfaFindAll(pattern, input, flags, factory);
        assertThat(actual).as("jur findAll pattern=\"%s\" input=\"%s\" flags=0x%x [%s]", pattern, input, flags, factory)
            .isEqualTo(expected);
    }

    public static void assertSameCompileSuccess(String pattern, RegexEngineFactory factory) {
        java.util.regex.Pattern.compile(pattern);
        io.github.jemmix.tdfa.Pattern.compile(pattern, 0, factory);
    }

    /** Shared malformed syntax: the JDK oracle and the jur facade BOTH reject. */
    public static void assertSameCompileReject(String pattern, RegexEngineFactory factory) {
        boolean jurThrew = false, tdfaThrew = false;
        try {
            java.util.regex.Pattern.compile(pattern);
        } catch (Exception e) {
            jurThrew = true;
        }
        try {
            io.github.jemmix.tdfa.Pattern.compile(pattern, 0, factory);
        } catch (Exception e) {
            tdfaThrew = true;
        }
        assertThat(jurThrew).as("java.util.regex should reject: %s", pattern).isTrue();
        assertThat(tdfaThrew).as("jur facade should reject: %s [%s]", pattern, factory).isTrue();
    }

    /**
     * A deliberate accept/reject divergence: the JDK oracle COMPILES the
     * pattern, the jur facade rejects — the DFA-discipline family
     * (backreferences, lookarounds, atomic groups, possessive quantifiers)
     * and the resource-budget family ("pattern too large" counts beyond the
     * 1000 cap). Pinning the divergence keeps it visible instead of silent.
     */
    public static void assertDivergentReject(String pattern, RegexEngineFactory factory) {
        java.util.regex.Pattern.compile(pattern);
        boolean tdfaThrew = false;
        try {
            io.github.jemmix.tdfa.Pattern.compile(pattern, 0, factory);
        } catch (Exception e) {
            tdfaThrew = true;
        }
        assertThat(tdfaThrew).as("jur facade should reject (JDK accepts): %s [%s]", pattern, factory).isTrue();
    }

    /**
     * The mirror divergence: the jur facade COMPILES a RE2-lineage syntax
     * extension the JDK rejects (POSIX bracket classes {@code [[:alpha:]]},
     * {@code (?P<name>...)} spellings, digit-first group names) — the shared
     * parser keeps the extension on both surfaces by design.
     */
    public static void assertDivergentAccept(String pattern, RegexEngineFactory factory) {
        io.github.jemmix.tdfa.Pattern.compile(pattern, 0, factory);
        boolean jurThrew = false;
        try {
            java.util.regex.Pattern.compile(pattern);
        } catch (Exception e) {
            jurThrew = true;
        }
        assertThat(jurThrew).as("java.util.regex should reject (facade accepts): %s", pattern).isTrue();
    }

    // ---- the semantics route (machinery axes: UNIX_LINES, UNICODE_CASE, ...) ----

    /**
     * The {@link CompileOptions#semantics(Semantics)} route's twin: the
     * facade compiles under the given per-axis {@link Semantics} (e.g.
     * {@code Semantics.of().unixLines()}), the JDK under the same-named
     * flags ({@link #jdkFlags(int)} translates the axis bits). ASM-only:
     * the options route carries no engine factory, and the axes act at
     * parse/determinize time shared by both tiers (the family-6 battery
     * cross-pins the VM tier against the same oracle).
     */
    public static void assertSemanticsSameFind(String pattern, String input, Semantics semantics,
        int semanticsJdkFlags) {
        Matcher jdk = java.util.regex.Pattern.compile(pattern, semanticsJdkFlags).matcher(input);
        int[] expected = jdk.find() ? spans(jdk) : null;
        PatternMatcher m =
            io.github.jemmix.tdfa.Pattern.compile(pattern, CompileOptions.of().semantics(semantics)).matcher(input);
        int[] actual = m.find() ? tdfaSpans(m) : null;
        assertThat(actual).as("jur-semantics pattern=\"%s\" input=\"%s\" %s", pattern, input, semantics)
            .isEqualTo(expected);
    }

    private static int[] tdfaSpans(PatternMatcher m) {
        int gc = m.groupCount();
        int[] out = new int[2 + 2 * gc];
        out[0] = m.start();
        out[1] = m.end();
        for (int g = 1; g <= gc; g++) {
            out[2 * g] = m.start(g);
            out[2 * g + 1] = m.end(g);
        }
        return out;
    }
}
