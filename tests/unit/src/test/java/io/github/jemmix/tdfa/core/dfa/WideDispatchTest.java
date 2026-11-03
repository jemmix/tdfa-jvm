package io.github.jemmix.tdfa.core.dfa;

import io.github.jemmix.tdfa.asm.TdfaAsmBackend;
import io.github.jemmix.tdfa.core.budget.Budgets;
import io.github.jemmix.tdfa.core.budget.WorkMeter;
import io.github.jemmix.tdfa.core.determinize.Determinizer;
import io.github.jemmix.tdfa.core.engine.MatchResult;
import io.github.jemmix.tdfa.core.engine.RegexEngine;
import io.github.jemmix.tdfa.core.report.CompileObserver;
import io.github.jemmix.tdfa.core.tnfa.Semantics;
import io.github.jemmix.tdfa.core.tnfa.Tnfa;
import io.github.jemmix.tdfa.core.unicode.UnicodeProviders;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The wide-class dispatch index (see {@link WideDispatch}): built for the
 * wide Unicode classes, absent for narrow and overlapping artifacts, and —
 * wherever built — resolving EXACTLY the entry the binary search finds,
 * over the whole codepoint space including supplementary planes. The index
 * serves every walk / simulation dispatch past Latin-1, so an off-by-one
 * here would be a wrong match, not a slow one.
 */
class WideDispatchTest {

    private static Tdfa compileTdfa(String pattern) {
        WorkMeter ledger = new WorkMeter(Budgets.compileComputeTicks());
        Tnfa nfa = Tnfa.compile(pattern, false, false, false, Semantics.of(), UnicodeProviders.get(),
            CompileObserver.NONE, ledger);
        return Determinizer.compileWithWholeSide(nfa, false, CompileObserver.NONE, ledger.fork(0));
    }

    /** The reference resolution: the entry containing cp over the state's
     *  (lo-sorted, disjoint) entries, dead markers included. */
    private static int referenceIndex(Tdfa tdfa, int s, int cp) {
        int base = tdfa.stateBase[s];
        int cnt = (tdfa.stateMeta[s] >>> 1) & 0xFFFF;
        for (int i = 0; i < cnt; i++) {
            int lo = tdfa.ranges[(base + i) * 5];
            if (lo > cp) {
                break; // sorted by lo
            }
            if (cp <= tdfa.ranges[(base + i) * 5 + 1]) {
                return i;
            }
        }
        return -1;
    }

    /** Interesting codepoints: every entry edge ±1, edges of touched blocks
     *  ±1, the space edges, random samples. */
    private static List<Integer> probePoints(Tdfa tdfa) {
        List<Integer> pts = new ArrayList<>();
        Random rnd = new Random(42);
        for (int s = 0; s < tdfa.stateCount; s++) {
            int base = tdfa.stateBase[s];
            int cnt = (tdfa.stateMeta[s] >>> 1) & 0xFFFF;
            for (int i = 0; i < cnt; i++) {
                int lo = tdfa.ranges[(base + i) * 5];
                int hi = tdfa.ranges[(base + i) * 5 + 1];
                for (int cp : new int[]{lo - 1, lo, lo + 1, hi - 1, hi, hi + 1}) {
                    if (cp >= 0 && cp <= 0x10FFFF) {
                        pts.add(cp);
                    }
                }
                int bLo = lo >>> WideDispatch.BLOCK_SHIFT;
                int bHi = Math.min(hi >>> WideDispatch.BLOCK_SHIFT, bLo + 3);
                for (int b = bLo; b <= bHi; b++) {
                    int bs = b << WideDispatch.BLOCK_SHIFT;
                    for (int cp : new int[]{bs - 1, bs, bs + 1, bs + WideDispatch.BLOCK_SHIFT - 1,
                        bs + WideDispatch.BLOCK_SHIFT}) {
                        if (cp >= 0 && cp <= 0x10FFFF) {
                            pts.add(cp);
                        }
                    }
                }
            }
        }
        for (int cp : new int[]{0, 1, 0x7F, 0x80, 0xFF, 0x100, 0xD7FF, 0xD800, 0xDBFF, 0xDC00, 0xDFFF, 0xE000, 0xFFFF,
            0x10000, 0x1D400, 0x2FA1D, 0xE01EF, 0x10FFFE, 0x10FFFF}) {
            pts.add(cp);
        }
        for (int i = 0; i < 3000; i++) {
            pts.add(rnd.nextInt(0x110000));
        }
        return pts;
    }

    private static void assertIndexAgrees(String pattern) {
        Tdfa tdfa = compileTdfa(pattern);
        boolean disjoint = RunnerTables.checkRangesDisjoint(tdfa);
        WideDispatch wd = WideDispatch.build(tdfa, disjoint);
        if (wd == null) {
            // Only legitimate when no state is wide enough to index.
            for (int s = 0; s < tdfa.stateCount; s++) {
                assertThat((tdfa.stateMeta[s] >>> 1) & 0xFFFF).as("%s: no index but state %s is wide", pattern, s)
                    .isLessThan(WideDispatch.MIN_RANGES);
            }
            return;
        }
        for (int s = 0; s < tdfa.stateCount; s++) {
            int cnt = (tdfa.stateMeta[s] >>> 1) & 0xFFFF;
            if (cnt < WideDispatch.MIN_RANGES) {
                assertThat(wd.rangeIndex(s, 'a')).as("%s: narrow state %s must report NONE", pattern, s)
                    .isEqualTo(WideDispatch.NONE);
                continue;
            }
            for (int cp : probePoints(tdfa)) {
                assertThat(wd.rangeIndex(s, cp)).as("%s: state %s cp %04x", pattern, s, cp)
                    .isEqualTo(referenceIndex(tdfa, s, cp));
            }
            // Full BMP sweep for genuinely wide states.
            for (int cp = 0; cp <= 0xFFFF; cp++) {
                assertThat(wd.rangeIndex(s, cp)).as("%s: state %s bmp %04x", pattern, s, cp)
                    .isEqualTo(referenceIndex(tdfa, s, cp));
            }
        }
    }

    @Test
    void wideClassesResolveExactly() {
        for (String p : new String[]{"\\p{L}{2,}", "\\p{L}{2,}x", "\\p{L}+", "[\\p{L}\\p{N}_]+", "[\\p{L}\\p{N}_]+x",
            "[\\p{L}\\p{N}]{3,}:", "\\p{Greek}+;", "[^\\p{L}\\p{N}_]+", "\\p{N}{4}-\\p{N}{4}", "[\\p{L}_.]{2,}=",
            "\\S+\\t"}) {
            assertIndexAgrees(p);
        }
    }

    @Test
    void narrowClassesHaveNoIndex() {
        for (String p : new String[]{"[a-z]+qrst", "(a|b)*cde", "[а-яА-ЯёЁ]{4,}", "abc"}) {
            Tdfa tdfa = compileTdfa(p);
            assertThat(WideDispatch.build(tdfa, RunnerTables.checkRangesDisjoint(tdfa))).as("%s", p).isNull();
        }
    }

    /** Hand-built artifact helper: disjoint [lo,hi] pairs per state, all
     *  transitions self-targeting (resolution is what is under test). */
    private static Tdfa synthetic(int[][][] perStatePairs) {
        int stateCount = perStatePairs.length;
        int total = 0;
        for (int[][] pairs : perStatePairs) {
            total += pairs.length;
        }
        int[] ranges = new int[total * 5];
        int[] stateMeta = new int[stateCount];
        int[] stateBase = new int[stateCount];
        int[] entryHiPrefix = new int[total];
        int r = 0;
        for (int s = 0; s < stateCount; s++) {
            stateBase[s] = r;
            int cnt = perStatePairs[s].length;
            stateMeta[s] = (s == 0 ? 1 : 0) | (cnt << 1);
            int maxHi = 0;
            for (int i = 0; i < cnt; i++) {
                int lo = perStatePairs[s][i][0];
                int hi = perStatePairs[s][i][1];
                ranges[r * 5] = lo;
                ranges[r * 5 + 1] = hi;
                ranges[r * 5 + 2] = s;
                maxHi = Math.max(maxHi, hi);
                entryHiPrefix[r] = maxHi;
                r++;
            }
        }
        // The fullest constructor, constants spelled (the telescoping
        // shortcuts are gone): no side table, no multi-valued tags, the
        // java.util.regex-parity lane, the 64-cell flag table.
        return new Tdfa(0, 0, Map.of(), 0, 0, 0, stateCount, stateMeta, stateBase, new int[stateCount], null, ranges,
            new int[]{0}, entryHiPrefix, new int[stateCount], new int[stateCount], false, null, null, false, false,
            null, null, null, false, null, null, null, null, false, false, Semantics.of(), 64, false);
    }

    /** Boundary blocks: entries starting/stopping mid-block, one-codepoint
     *  entries, supplementary edges — synthetic shapes with exact answers. */
    @Test
    void syntheticBoundariesResolveExactly() {
        int[][][] ranges = {
            {{0, 0xFF}, {0x200, 0x3FF}, {0x400, 0x452}, {0x453, 0x454}, {0x455, 0x455}, {0x456, 0x7FF},
                {0x10000, 0x10FFF}, {0x1D400, 0x1D7FF}, {0x10FF00, 0x10FFFF}},
            {{0x300, 0x3FF}, {0x500, 0x5FF}, {0x900, 0x9FF}, {0xD00, 0xDFF}, {0x1100, 0x11FF}, {0x1500, 0x15FF},
                {0x1900, 0x19FF}, {0x1D00, 0x1DFF}},
            {{0, 0}}};
        Tdfa tdfa = synthetic(ranges);
        WideDispatch wd = WideDispatch.build(tdfa, true);
        assertThat(wd).isNotNull();
        for (int s = 0; s < 2; s++) {
            for (int cp = 0; cp <= 0x2000; cp++) {
                assertThat(wd.rangeIndex(s, cp)).as("state %s cp %04x", s, cp).isEqualTo(referenceIndex(tdfa, s, cp));
            }
            for (int[] window : new int[][]{{0xFFF0, 0x11010}, {0x1D3F0, 0x1D410}, {0x1D7F0, 0x1D801},
                {0x10FEF0, 0x10FFFF}}) {
                for (int cp = window[0]; cp <= window[1]; cp++) {
                    assertThat(wd.rangeIndex(s, cp)).as("state %s cp %05x", s, cp)
                        .isEqualTo(referenceIndex(tdfa, s, cp));
                }
            }
        }
        assertThat(wd.rangeIndex(2, 0)).isEqualTo(WideDispatch.NONE); // below the width threshold
    }

    /** Overlapping entries (mask-priority artifacts): the index must not
     *  build — resolution is ambiguous where entries share codepoints. */
    @Test
    void overlappingArtifactRejected() {
        int[][][] ranges = {{{0, 0x2000}, {0x100, 0x300}}, {{0, 0}}};
        Tdfa tdfa = synthetic(ranges);
        assertThat(RunnerTables.checkRangesDisjoint(tdfa)).isFalse();
        assertThat(WideDispatch.build(tdfa, false)).isNull();
    }

    /** The all-or-nothing byte cap: a synthetic artifact whose index would
     *  exceed MAX_BYTES keeps the binary search. */
    @Test
    void byteCapDropsIndex() {
        int states = WideDispatch.MAX_STATES;
        int[][][] ranges = new int[states + 1][][];
        for (int s = 0; s < states; s++) {
            ranges[s] = new int[256][];
            for (int i = 0; i < 256; i++) {
                // Two-codepoint entries straddling alternating 1024-codepoint
                // blocks: every entry pair forces boundary blocks pool-wide.
                ranges[s][i] = new int[]{0x800 * i + (s & 1), 0x800 * i + 1 + (s & 1)};
            }
        }
        ranges[states] = new int[][]{{0, 0}};
        Tdfa tdfa = synthetic(ranges);
        assertThat(WideDispatch.build(tdfa, true)).isNull();
    }

    // ===== behavior: the index serves every dispatch site identically =====

    /** The gap shapes, run through find() on both backends and every
     *  forceable rung, pinned against java.util.regex. */
    @Test
    void gapShapesMatchOracleAcrossRungsAndBackends() {
        String[] patterns =
            {"\\p{L}{2,}", "\\p{L}{2,}x", "[\\p{L}\\p{N}_]+", "[\\p{L}\\p{N}]{3,}:", "\\p{N}{2,}\\p{L}{2,}"};
        String[] inputs = {"Привет мир, вот тестовое предложение короткое", "abc 123 def 456:", "𝛅αβ 𝛅αβ γδ",
            "مرحبا بالعالم 123", "0123456789", "Ματανη εξ αλφα", "ab\u0301cd é",
            "\uD835\uDD04\uD835\uDD05 数学与字母 mixed \uD83D\uDE00 text", ""};
        for (String p : patterns) {
            Tdfa tdfa = compileTdfa(p);
            RegexEngine vm = new TdfaRunner(tdfa);
            RegexEngine asm = TdfaAsmBackend.generate(tdfa);
            Pattern oracle = Pattern.compile(p);
            for (String in : inputs) {
                for (int from = 0; from <= in.length(); from++) {
                    String ctx = p + " [" + from + "..] over " + in.length() + " units";
                    Matcher om = oracle.matcher(in).region(from, in.length());
                    String expected = om.find() ? om.start() + ".." + om.end() : "none";
                    MatchResult m = vm.match(in, from, null);
                    assertThat(m == null ? "none" : m.start(0) + ".." + m.end(0)).as("vm %s", ctx).isEqualTo(expected);
                    MatchResult am = asm.match(in, from, null);
                    assertThat(am == null ? "none" : am.start(0) + ".." + am.end(0)).as("asm %s", ctx)
                        .isEqualTo(expected);
                    for (TdfaRunner.Strategy f : new TdfaRunner.Strategy[]{TdfaRunner.Strategy.ORIGIN_SIM,
                        TdfaRunner.Strategy.RAW_SCAN}) {
                        String forced;
                        try {
                            TdfaRunner.setForcedStrategy(f);
                            MatchResult fm = vm.match(in, from, null);
                            forced = fm == null ? "none" : fm.start(0) + ".." + fm.end(0);
                        } finally {
                            TdfaRunner.setForcedStrategy(null);
                        }
                        assertThat(forced).as("forced %s %s", f, ctx).isEqualTo(expected);
                    }
                }
            }
        }
    }

    /** findAll over longer mixed-script text: the sim/trigger/restart paths
     *  with the index wired in, differentially against the oracle. */
    @Test
    void findAllUnicodeSweepMatchesOracle() {
        String[] patterns = {"\\p{L}{3,}", "[\\p{L}\\p{N}_]{5,}", "(\\p{L}{2,})(\\p{N}{2,})"};
        StringBuilder in = new StringBuilder();
        Random rnd = new Random(7);
        for (int i = 0; i < 4000; i++) {
            switch (rnd.nextInt(8)) {
                case 0 -> in.append(' ');
                case 1 -> in.append((char) ('0' + rnd.nextInt(10)));
                case 2 -> in.append((char) ('a' + rnd.nextInt(26)));
                case 3 -> in.append((char) (0x400 + rnd.nextInt(64))); // Cyrillic
                case 4 -> in.append((char) (0x370 + rnd.nextInt(48))); // Greek
                case 5 -> in.appendCodePoint(0x1D400 + rnd.nextInt(64)); // math letters
                case 6 -> in.append(':');
                default -> in.append('\u0323'); // combining mark below
            }
        }
        String input = in.toString();
        for (String p : patterns) {
            Tdfa tdfa = compileTdfa(p);
            RegexEngine vm = new TdfaRunner(tdfa);
            RegexEngine asm = TdfaAsmBackend.generate(tdfa);
            Pattern oracle = Pattern.compile(p);
            List<String> expected = new ArrayList<>();
            Matcher om = oracle.matcher(input);
            while (om.find()) {
                expected.add(om.start() + ".." + om.end());
            }
            for (RegexEngine eng : new RegexEngine[]{vm, asm}) {
                List<String> got = new ArrayList<>();
                int from = 0;
                while (from <= input.length()) {
                    MatchResult m = eng.match(input, from, null);
                    if (m == null) {
                        break;
                    }
                    got.add(m.start(0) + ".." + m.end(0));
                    from = m.end(0) > m.start(0) ? m.end(0) : m.end(0) + 1;
                }
                assertThat(got).as("%s spans", p).isEqualTo(expected);
            }
        }
    }
}
