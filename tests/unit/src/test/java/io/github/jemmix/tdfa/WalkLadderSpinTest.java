package io.github.jemmix.tdfa;

import io.github.jemmix.tdfa.core.compile.RegexEngineFactory;
import io.github.jemmix.tdfa.core.dfa.TdfaRunner;
import io.github.jemmix.tdfa.core.engine.Matcher;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The campaign 2026-10-24 finding-4 shapes, pinned (see
 * docs/fuzz-campaigns-2026-10-24.md): {@code $}/{@code \B}/{@code ^} gates
 * after an unbounded class-plus — {@code .+$\w},
 * {@code .+(?m:^\w)}, {@code [^9]+?\B\w+?} — spun the walk ladder
 * 20–36 s on the soak's stretched inputs (plus the three clean
 * siblings that must STAY clean). Re-profiling on the frozen-runner
 * rework's main split the finding: every NATURAL path was already
 * milliseconds (the #76/#77 kill-point window does its job wherever it
 * is wired); the spin lived in the one ladder site it was never wired
 * to — the fuzz force hook's restart floor ({@code forcedRestart}),
 * whose unbounded per-start walk loop spent n restarts x O(n) walks per
 * find at the campaign stretch. The floor now consults the same window
 * (starts below {@code W} are provably dead); this test pins the six
 * seeds' exact pattern/flags/input draws — oracle-differential on first
 * {@code find()}, {@code lookingAt()}, the {@code find(len/2)} restart,
 * and the exhaustive iteration, on the seed input and its 3x/5x
 * stretches — plus the forced-floor wall canary on the campaign's own
 * ~4.3 k-char stretch (the pre-fix spins measured 17–64 s there; the
 * windowed paths measure single-digit milliseconds).
 */
class WalkLadderSpinTest {

    private static final String[] PATTERNS =
        {".+$\\w", ".+(?m:^\\w)", "[^9]+?\\B\\w+?", "(?s:.*?$\\w)(\\Q#@0\\E\u0442)", "(?m:.*$)\\w\\D", ".{2,}$\\D",};

    private static final int[] FLAGS =
        {Pattern.CASE_INSENSITIVE | Pattern.DOTALL | Pattern.MULTILINE | Pattern.UNICODE_CHARACTER_CLASS,
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL | Pattern.UNICODE_CHARACTER_CLASS,
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL | Pattern.UNICODE_CHARACTER_CLASS,
            Pattern.CASE_INSENSITIVE | Pattern.MULTILINE | Pattern.UNICODE_CHARACTER_CLASS,
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CHARACTER_CLASS,
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL | Pattern.UNICODE_CHARACTER_CLASS,};

    private static final String[] INPUTS = {
        // CR/LF/tab are pinned with the string escapes u000d-style unicode
        // preprocessing would split the literal (escapes apply in comments too).
        "\u0000\udbff\udc21\ud835\udd04\ud800\udc00\ua64a\u0442\udfff\u1c88\r\u1c80\ud801\udc21\u4e00\u0130\u00df\nY\udfff",
        "\ud800\ud801\udc21.\udfff\u1c88\ud800\ud807\udc07\ud800\udfff0\u0000\udc00Y9\ud801\udc21\ud800\udfffz",
        "\udc00", "\udfff\ud835\udd04\t\nY\ud807\udc07\n\u6f22\u4e00\udc00\ud800\udfff~\r\u017f\ud801\udc02\udfff",
        "\ud800\udc00", "\udfff\udc00\ud800\udfffZ",};

    private static final long[] CASE_SEEDS = {7149332676185069355L, 8643965881125084892L, 4848892083176547229L,
        541553829559589526L, 2799524498384578279L, 5866438399429661988L,};

    private static Stream<RegexEngineFactory> tiers() {
        return Stream.<RegexEngineFactory>of(null, TdfaRunner::new);
    }

    /**
     * Oracle differential on every shape, tier, and stretch: the three
     * find-family probes must equal {@code java.util.regex} exactly
     * (span strings, {@code false} included), and the exhaustive
     * iteration must agree too — the ladder's continuation path rides
     * the same walk machinery.
     */
    @ParameterizedTest
    @MethodSource("tiers")
    void finding4ShapesMatchTheOracle(RegexEngineFactory tier) {
        for (int i = 0; i < PATTERNS.length; i++) {
            String pattern = PATTERNS[i];
            int flags = FLAGS[i];
            String input = INPUTS[i];
            for (int n : new int[]{1, 3, 5}) {
                String in = input.repeat(n);
                String oracle = probeJur(pattern, flags, in);
                String actual = probe(Pattern.compile(pattern, flags, tier), in);
                assertThat(actual)
                    .as("finding-4 seed %d /%s/ flags %d x%d tier %s", CASE_SEEDS[i], pattern, flags, n, name(tier))
                    .isEqualTo(oracle);

                List<String> spans = new ArrayList<>(), oracleSpans = new ArrayList<>();
                for (Matcher m = Pattern.compile(pattern, flags, tier).matcher(in); m.find();) {
                    spans.add(m.start() + ".." + m.end());
                }
                java.util.regex.Matcher jm = java.util.regex.Pattern.compile(pattern, jurFlags(flags)).matcher(in);
                while (jm.find()) {
                    oracleSpans.add(jm.start() + ".." + jm.end());
                }
                assertThat(spans).as("exhaustive seed %d /%s/ x%d tier %s", CASE_SEEDS[i], pattern, n, name(tier))
                    .isEqualTo(oracleSpans);
            }
        }
    }

    /**
     * Perf canary: the whole six-shape battery — both tiers, base and
     * 3x/5x stretches — answers in well under the campaign's 10 s
     * watchdog per shape (the spins measured 20–36 s solo; the
     * frozen-runner ladders measure single-digit milliseconds here).
     */
    @Test
    void sixShapesStayWithinTheWatchdogPerShape() {
        for (int i = 0; i < PATTERNS.length; i++) {
            long t0 = System.nanoTime();
            for (RegexEngineFactory tier : new RegexEngineFactory[]{null, TdfaRunner::new}) {
                Pattern p = Pattern.compile(PATTERNS[i], FLAGS[i], tier);
                for (int n : new int[]{1, 3, 5}) {
                    String in = INPUTS[i].repeat(n);
                    Matcher m = p.matcher(in);
                    if (m.find()) {
                        m.start();
                        m.end();
                    }
                    p.matcher(in).lookingAt();
                    p.matcher(in).find(in.length() / 2);
                }
            }
            long ms = (System.nanoTime() - t0) / 1_000_000;
            assertThat(ms).as("seed %d /%s/ both tiers, base+3x+5x wall", CASE_SEEDS[i], PATTERNS[i])
                .isLessThan(10_000);
        }
    }

    /**
     * The spin's actual home: the forced restart floor (the fuzz force
     * hook's WALK_RESTART rung) on the campaign's own stretch — whole
     * string repeated past 4 300 chars, the soak's stretched-probe
     * recipe. Unbounded, that floor spent n restarts x O(n) walks per
     * find: 17–64 s per shape here, the HANG_ENGINE records of the
     * campaign. Bounded by the kill-point window, every forced rung
     * must answer what the natural ladder answers, both tiers, inside
     * the 10 s watchdog.
     */
    @Test
    void forcedRestartFloorIsWindowedOnTheCampaignStretch() {
        for (int i = 0; i < PATTERNS.length; i++) {
            String input = INPUTS[i];
            String stretched = input.repeat(4300 / input.length() + 1);
            for (RegexEngineFactory tier : new RegexEngineFactory[]{null, TdfaRunner::new}) {
                Pattern p = Pattern.compile(PATTERNS[i], FLAGS[i], tier);
                boolean natural = p.matcher(stretched).find();
                long t0 = System.nanoTime();
                boolean forced = false;
                try {
                    TdfaRunner.setForcedStrategy(TdfaRunner.Strategy.WALK_RESTART);
                    forced = p.matcher(stretched).find();
                } finally {
                    TdfaRunner.setForcedStrategy(null);
                }
                long ms = (System.nanoTime() - t0) / 1_000_000;
                assertThat(forced)
                    .as("forced floor == natural ladder, seed %d /%s/ tier %s", CASE_SEEDS[i], PATTERNS[i], name(tier))
                    .isEqualTo(natural);
                assertThat(ms).as("seed %d /%s/ forced floor, %d-char stretch, tier %s", CASE_SEEDS[i], PATTERNS[i],
                    stretched.length(), name(tier)).isLessThan(10_000);
            }
        }
    }

    /** Facade flag bits → the same reading's java.util.regex bits. */
    private static int jurFlags(int flags) {
        int jf = (flags & Pattern.CASE_INSENSITIVE) != 0 ? java.util.regex.Pattern.CASE_INSENSITIVE : 0;
        jf |= (flags & Pattern.DOTALL) != 0 ? java.util.regex.Pattern.DOTALL : 0;
        jf |= (flags & Pattern.MULTILINE) != 0 ? java.util.regex.Pattern.MULTILINE : 0;
        jf |= (flags & Pattern.UNICODE_CHARACTER_CLASS) != 0 ? java.util.regex.Pattern.UNICODE_CHARACTER_CLASS : 0;
        return jf;
    }

    private static String name(RegexEngineFactory f) {
        return f == null ? "asm" : "vm";
    }

    private static String probe(Pattern p, String input) {
        Matcher m = p.matcher(input);
        String f = m.find() ? m.start() + ".." + m.end() : "false";
        Matcher l = p.matcher(input);
        String lo = l.lookingAt() ? l.start() + ".." + l.end() : "false";
        Matcher r = p.matcher(input);
        String re = r.find(input.length() / 2) ? r.start() + ".." + r.end() : "false";
        return "F=" + f + " L=" + lo + " R=" + re;
    }

    private static String probeJur(String pattern, int flags, String input) {
        java.util.regex.Pattern jp = java.util.regex.Pattern.compile(pattern, jurFlags(flags));
        java.util.regex.Matcher m = jp.matcher(input);
        String f = m.find() ? m.start() + ".." + m.end() : "false";
        java.util.regex.Matcher l = jp.matcher(input);
        String lo = l.lookingAt() ? l.start() + ".." + l.end() : "false";
        java.util.regex.Matcher r = jp.matcher(input);
        String re = r.find(input.length() / 2) ? r.start() + ".." + r.end() : "false";
        return "F=" + f + " L=" + lo + " R=" + re;
    }
}
