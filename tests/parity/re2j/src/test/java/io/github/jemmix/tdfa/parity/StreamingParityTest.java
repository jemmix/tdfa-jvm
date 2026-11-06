package io.github.jemmix.tdfa.parity;

import io.github.jemmix.tdfa.core.compile.RegexEngineFactory;
import io.github.jemmix.tdfa.core.dfa.StreamMatcher;
import io.github.jemmix.tdfa.core.engine.ChunkSource;
import io.github.jemmix.tdfa.sim.PikeSim;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.regex.Matcher;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The streaming surface against the live oracles (see
 * {@code docs/streaming-input.md}): chunk-split matching must answer
 * exactly what each lane's oracle answers over the whole input — the
 * jur facade against {@code java.util.regex} in this JVM, the re2j
 * facade against the vendored re2j, and the re2j-lane streaming against
 * PikeSim (the reference sim — its non-streaming matcher over the whole
 * input IS the spec-of-record here; the driver reuses the engine's walk
 * definitions, so the equivalence argument plus this pin replaces a
 * streaming PikeSim mode).
 *
 * <p>Chunkings: every split position on every case (including splits
 * between surrogate halves, inside terminator runs, and at both ends),
 * per-unit chunks, and a seeded random re-chunking. Engines: both
 * factories (ASM default, VM interpreter).
 */
class StreamingParityTest {

    private static final String HI = "\uD83D";
    private static final String LO = "\uDE00";
    private static final String PAIR = HI + LO; // U+1F600
    private static final int UCC = io.github.jemmix.tdfa.Pattern.UNICODE_CHARACTER_CLASS;

    /** (pattern, flags, input) — shapes exercising each pend-rule axis. */
    private static final Object[][] CORPUS = {{"needle", 0, "x needle y needle"}, // literal rung
        {"(\\w+)@(\\w+)", 0, "a@b none cd ef@gh"}, // captures across chunks
        {"(a|ab)(c|bcd)", 0, "abcd abcd"}, // leftmost-first priority
        {"a*", 0, "baab aa!"}, // zero-width protocol
        {"x$", 0, "ax\nbx"}, // end-text vs the final terminator
        {"(?m)^(\\w+)$", 0, "one\ntwo\n\n"}, // multiline + trailing terminator run
        {"\\bword\\b", 0, "a word boundary wordword"}, {"\\b(\\w+)\\s+(\\w+)\\b", UCC, "ab cd ef"}, // unicode-word boundaries
        {"(?i)AbC", 0, "abc ABC abx"}, {"[a-z]+ing", 0, "sing sting working"}, // live-tail walks
        {"(?s).+x", 0, "abcxdefxgh"}, {"(\\d+)\\.(\\d+)", 0, "1.22.333.4444"},
        {"\\p{L}+", 0, "abc" + PAIR + "def \u00e9\u00f6"}, // supplementary pairs
        {"\uD83D\uDE00+", 0, "x" + PAIR + PAIR + "y"}, // needle WITH pairs
        {".", 0, "a\uD800b" + PAIR + "c"}, // lone surrogate + pairs
        {"(?m)^", 0, "a\nb\n\n"}, // zero-width multiline begins
        {"a|b|ab", 0, "abab"},};

    // ---- jur lane: streaming vs the live java.util.regex oracle ----

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.JurOracle#engineFactories")
    void jurStreamingMatchesTheLiveOracleAtEverySplit(RegexEngineFactory factory) {
        for (Object[] row : CORPUS) {
            String pattern = (String) row[0];
            int flags = (int) row[1];
            String input = (String) row[2];
            List<int[]> oracle = jurSpans(pattern, flags, input);
            for (int split = 0; split <= input.length(); split++) {
                assertThat(
                    streamSpans(io.github.jemmix.tdfa.Pattern.compile(pattern, flags, factory), pieces(input, split)))
                    .as("jur pattern=\"%s\" flags=0x%x input=\"%s\" split=%d [%s]", pattern, flags, input, split,
                        factory)
                    .usingRecursiveComparison().isEqualTo(oracle);
            }
            assertThat(streamSpans(io.github.jemmix.tdfa.Pattern.compile(pattern, flags, factory), perUnit(input)))
                .as("jur per-unit [%s]", factory).usingRecursiveComparison().isEqualTo(oracle);
            assertThat(streamSpans(io.github.jemmix.tdfa.Pattern.compile(pattern, flags, factory), randomChunks(input)))
                .as("jur random chunks [%s]", factory).usingRecursiveComparison().isEqualTo(oracle);
        }
    }

    // ---- re2j lane: streaming vs the vendored re2j oracle ----

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.JurOracle#engineFactories")
    void re2jStreamingMatchesTheLiveOracleAtEverySplit(RegexEngineFactory factory) {
        Object[][] re2jCorpus =
            {{"needle", 0, "x needle y"}, {"x$", 0, "ax\nbx"}, {"(?m)^(\\w+)$", 0, "one\ntwo"}, {"a*", 0, "baab"},
                {"\\b\\w+\\b", 0, "ab cd"}, {".", 0, "a\uD800b" + PAIR + "c"}, {"(a|ab)(c|bcd)", 0, "abcd"},};
        for (Object[] row : re2jCorpus) {
            String pattern = (String) row[0];
            String input = (String) row[2];
            List<int[]> oracle = re2jSpans(pattern, input);
            for (int split = 0; split <= input.length(); split++) {
                assertThat(
                    streamSpans(io.github.jemmix.tdfa.re2j.Pattern.compile(pattern, 0, factory), pieces(input, split)))
                    .as("re2j pattern=\"%s\" input=\"%s\" split=%d [%s]", pattern, input, split, factory)
                    .usingRecursiveComparison().isEqualTo(oracle);
            }
        }
    }

    // ---- the reference sim: re2j-lane streaming vs PikeSim over the whole input ----

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.JurOracle#engineFactories")
    void streamingMatchesPikeSimOverTheWholeInput(RegexEngineFactory factory) {
        Object[][] simCorpus = {{"needle", "x needle y needle"}, {"(\\w+)@(\\w+)", "a@b none cd ef@gh"},
            {"a*", "baab aa!"}, {"(?s).+x", "abcxdefxgh"}, {"\\p{L}+", "abc" + PAIR + "def \u00e9"}, {"a|b|ab", "abab"},
            {"x$", "ax\nbx"},};
        for (Object[] row : simCorpus) {
            String pattern = (String) row[0];
            String input = (String) row[1];
            PikeSim.PikeMatcher pm = PikeSim.compile(pattern).matcher(input);
            List<int[]> sim = new ArrayList<>();
            while (pm.find()) {
                sim.add(new int[]{pm.start(), pm.end()});
            }
            for (int split = 0; split <= input.length(); split++) {
                List<int[]> streamed =
                    wholeSpans(io.github.jemmix.tdfa.re2j.Pattern.compile(pattern, 0, factory), pieces(input, split));
                assertThat(streamed)
                    .as("pike pattern=\"%s\" input=\"%s\" split=%d [%s]", pattern, input, split, factory)
                    .usingRecursiveComparison().isEqualTo(sim);
            }
        }
    }

    // ---- helpers ----

    private static List<String> pieces(String input, int split) {
        List<String> out = new ArrayList<>();
        if (split > 0) {
            out.add(input.substring(0, split));
        }
        if (split < input.length()) {
            out.add(input.substring(split));
        }
        return out;
    }

    private static List<String> perUnit(String input) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < input.length(); i++) {
            out.add(input.substring(i, i + 1));
        }
        return out;
    }

    private static List<String> randomChunks(String input) {
        Random rnd = new Random(0x57DEA11L);
        List<String> out = new ArrayList<>();
        int i = 0;
        while (i < input.length()) {
            int take = 1 + rnd.nextInt(3);
            out.add(input.substring(i, Math.min(i + take, input.length())));
            i += take;
        }
        return out;
    }

    private static ChunkSource source(List<String> pieces) {
        return new ChunkSource() {
            private int i;

            @Override
            public CharSequence next() {
                return i < pieces.size() ? pieces.get(i++) : null;
            }
        };
    }

    /** Full span arrays (match + every group) per match, through the jur facade. */
    private static List<int[]> streamSpans(io.github.jemmix.tdfa.Pattern p, List<String> pieces) {
        StreamMatcher sm = p.streamingMatcher(source(pieces));
        List<int[]> out = new ArrayList<>();
        while (sm.next()) {
            out.add(flat(sm));
        }
        return out;
    }

    /** Full span arrays through the re2j facade. */
    private static List<int[]> streamSpans(io.github.jemmix.tdfa.re2j.Pattern p, List<String> pieces) {
        StreamMatcher sm = p.streamingMatcher(source(pieces));
        List<int[]> out = new ArrayList<>();
        while (sm.next()) {
            out.add(flat(sm));
        }
        return out;
    }

    /** Whole-match spans through the re2j facade. */
    private static List<int[]> wholeSpans(io.github.jemmix.tdfa.re2j.Pattern p, List<String> pieces) {
        StreamMatcher sm = p.streamingMatcher(source(pieces));
        List<int[]> out = new ArrayList<>();
        while (sm.next()) {
            out.add(new int[]{sm.result().start(0), sm.result().end(0)});
        }
        return out;
    }

    private static int[] flat(StreamMatcher sm) {
        int gc = sm.result().groupCount();
        int[] row = new int[2 * (gc + 1)];
        row[0] = sm.result().start(0);
        row[1] = sm.result().end(0);
        for (int g = 1; g <= gc; g++) {
            row[2 * g] = sm.result().start(g);
            row[2 * g + 1] = sm.result().end(g);
        }
        return row;
    }

    private static List<int[]> jurSpans(String pattern, int flags, String input) {
        Matcher m = java.util.regex.Pattern.compile(pattern, JurOracle.jdkFlags(flags)).matcher(input);
        List<int[]> out = new ArrayList<>();
        while (m.find()) {
            int gc = m.groupCount();
            int[] row = new int[2 * (gc + 1)];
            row[0] = m.start();
            row[1] = m.end();
            for (int g = 1; g <= gc; g++) {
                row[2 * g] = m.start(g);
                row[2 * g + 1] = m.end(g);
            }
            out.add(row);
        }
        return out;
    }

    private static List<int[]> re2jSpans(String pattern, String input) {
        com.google.re2j.Matcher m = com.google.re2j.Pattern.compile(pattern).matcher(input);
        List<int[]> out = new ArrayList<>();
        while (m.find()) {
            int gc = m.groupCount();
            int[] row = new int[2 * (gc + 1)];
            row[0] = m.start();
            row[1] = m.end();
            for (int g = 1; g <= gc; g++) {
                row[2 * g] = m.start(g);
                row[2 * g + 1] = m.end(g);
            }
            out.add(row);
        }
        return out;
    }
}
