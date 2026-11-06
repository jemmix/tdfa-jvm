package io.github.jemmix.tdfa;

import io.github.jemmix.tdfa.core.budget.Budgets;
import io.github.jemmix.tdfa.core.budget.StreamWindowOverflowException;
import io.github.jemmix.tdfa.core.compile.PatternMatcher;
import io.github.jemmix.tdfa.core.dfa.StreamMatcher;
import io.github.jemmix.tdfa.core.dfa.Tdfa;
import io.github.jemmix.tdfa.core.dfa.TdfaRunner;
import io.github.jemmix.tdfa.core.engine.ChunkSource;
import io.github.jemmix.tdfa.core.engine.ChunkSources;
import io.github.jemmix.tdfa.core.engine.MatchResult;
import io.github.jemmix.tdfa.core.engine.MatchScratch;
import io.github.jemmix.tdfa.core.engine.RegexEngine;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.nio.ByteBuffer;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The streaming-input surface (see docs/streaming-input.md): chunk-split
 * differential evidence that a streaming matcher answers EXACTLY what
 * the String surface answers over the concatenated input — every split
 * position on small inputs (including splits between surrogate pairs,
 * inside terminator runs, and at both ends), both engines (ASM default
 * and the interpreter factory), both lanes (jur facade, re2j facade) —
 * plus the adapters, the window budget's loud rejection, and the
 * contract violations.
 *
 * <p>This is the documented equivalence argument's pin: no second
 * streaming implementation exists to diff against (PikeSim included);
 * the driver reuses the runner's walk/simulation definitions and the
 * pending rule, and this battery holds the identity at every boundary.
 */
class StreamingInputTest {

    /** The shape corpus: patterns and inputs exercising each pend-rule axis. */
    private static final Object[][] CORPUS = {{"needle", "haystack needle hay needle"}, // literal rung
        {"(\\w+)@(\\w+)", "a@b none cd ef@gh"}, // captures across chunks
        {"(a|ab)(c|bcd)", "abcd"}, // leftmost-first priority shapes
        {"(a|ab)(c|bcd)(d*)", "abcd"}, // priority + zero-width tail
        {"a*", "baab aa!"}, // zero-width protocol
        {"a+?", "aaa b"}, // lazy
        {"^(\\w+)", "one two"}, // begin-text
        {"(?m)^(\\w+)$", "one\ntwo\n"}, // multiline terminators ($/final \n)
        {"x$", "ax\nbx"}, // end-text vs final terminator
        {"\\bword\\b", "a word boundary wordword"}, // word boundaries
        {"\\b\\w+\\b", "ab cd\u0000ef"}, // boundary + consumption
        {"\\A[ab]+\\z", "abba"}, // abs anchors
        {"(?i)AbC", "abc ABC abx"}, // fold
        {"[a-z]+ing", "sing sting working"}, // live-tail walk shapes
        {"(?s).+x", "abcxdefxgh"}, // sim-kill shapes (large chunks)
        {".*x", "abcxdefx"}, //
        {"(\\d+)\\.(\\d+)", "1.22.333.4444"}, // adjacent matches
        {"\\p{L}+", "abc\ud83d\ude00def \u00e9\u00f6"}, // supplementary pairs + Latin-1
        {"\ud83d\ude00+", "x\ud83d\ude00\ud83d\ude00y"}, // literal needle WITH pairs
        {".", "a\ud800b\ud83d\ude00c"}, // lone surrogate + pairs, unit lanes
        {"(?m)^", "a\nb\n\n"}, // zero-width multiline begins
        {"a|b|ab", "abab"}, // alternation priority
    };

    /** Differential: streaming over every single split vs the String surface. */
    @Test
    void everyChunkSplitMatchesTheStringSurface() {
        for (Object[] row : CORPUS) {
            String regex = (String) row[0];
            String input = (String) row[1];
            for (int split = 0; split <= input.length(); split++) {
                assertStreamingEqualsString(regex, 0, input, pieces(input, split));
            }
        }
    }

    /** Multi-chunk shapes: char-by-char, pairs, and a fixed-seed random split. */
    @Test
    void multiChunkSplitsMatchTheStringSurface() {
        Random rnd = new Random(0x570DCAFEL);
        for (Object[] row : CORPUS) {
            String regex = (String) row[0];
            String input = (String) row[1];
            // every unit its own chunk
            List<String> perChar = new ArrayList<>();
            for (int i = 0; i < input.length(); i++) {
                perChar.add(input.substring(i, i + 1));
            }
            assertStreamingEqualsString(regex, 0, input, perChar);
            // fixed-seed random re-chunking of the per-char pieces
            List<String> random = new ArrayList<>();
            int i = 0;
            while (i < perChar.size()) {
                int take = 1 + rnd.nextInt(3);
                StringBuilder sb = new StringBuilder();
                for (int j = 0; j < take && i < perChar.size(); j++, i++) {
                    sb.append(perChar.get(i));
                }
                random.add(sb.toString());
            }
            assertStreamingEqualsString(regex, 0, input, random);
        }
    }

    /** Both engines: the ASM default compile and the interpreter factory agree. */
    @Test
    void bothEnginesStreamIdentically() {
        for (Object[] row : CORPUS) {
            String regex = (String) row[0];
            String input = (String) row[1];
            for (int split = 0; split <= input.length(); split += Math.max(1, input.length() / 4)) {
                List<String> pieces = pieces(input, split);
                List<int[]> asm = streamAll(Pattern.compile(regex), pieces);
                List<int[]> vm = streamAll(Pattern.compile(regex, 0, TdfaRunner::new), pieces);
                List<int[]> string = stringAll(Pattern.compile(regex), input);
                assertThat(asm).as("ASM vs VM, %s split %d", regex, split).usingRecursiveComparison().isEqualTo(vm);
                assertThat(asm).as("ASM vs String, %s split %d", regex, split).usingRecursiveComparison()
                    .isEqualTo(string);
            }
        }
    }

    /** The re2j lane: same battery shape through the other facade. */
    @Test
    void re2jLaneStreamsTheStringSurface() {
        Object[][] re2jCorpus = {{"needle", "x needle y"}, {"x$", "ax\nbx"}, {"(?m)^(\\w+)$", "one\ntwo"},
            {"a*", "baab"}, {"\\b\\w+\\b", "ab cd"}, {".", "a\ud800b\ud83d\ude00c"},};
        for (Object[] row : re2jCorpus) {
            String regex = (String) row[0];
            String input = (String) row[1];
            io.github.jemmix.tdfa.re2j.Pattern p = io.github.jemmix.tdfa.re2j.Pattern.compile(regex);
            for (int split = 0; split <= input.length(); split++) {
                List<int[]> streamed = streamAll(p, pieces(input, split));
                List<int[]> string = stringAll(p, input);
                assertThat(streamed).as("re2j lane, %s split %d", regex, split).usingRecursiveComparison()
                    .isEqualTo(string);
            }
        }
    }

    /** Flags compose: LONGEST_MATCH and MULTI_VALUED_TAGS through the stream. */
    @Test
    void flagCompilesMatchTheStringSurface() {
        String input = "abab";
        for (int split = 0; split <= input.length(); split++) {
            assertThat(streamAll(Pattern.compile("a|ab", Pattern.LONGEST_MATCH), pieces(input, split)))
                .as("LONGEST split %d", split).usingRecursiveComparison()
                .isEqualTo(stringAll(Pattern.compile("a|ab", Pattern.LONGEST_MATCH), input));
        }
        String mvtInput = "abab";
        Pattern mvt = Pattern.compile("(?:(a)|(b))+", Pattern.MULTI_VALUED_TAGS);
        for (int split = 0; split <= mvtInput.length(); split++) {
            StreamMatcher sm = mvt.streamingMatcher(source(pieces(mvtInput, split)));
            List<int[]> spans = new ArrayList<>();
            List<int[]> groupSpans = new ArrayList<>();
            while (sm.next()) {
                spans.add(new int[]{sm.result().start(0), sm.result().end(0)});
                groupSpans.add(sm.result().groupSpans(1));
            }
            PatternMatcher m = mvt.matcher(mvtInput);
            List<int[]> wantSpans = new ArrayList<>();
            List<int[]> wantGroupSpans = new ArrayList<>();
            while (m.find()) {
                wantSpans.add(new int[]{m.start(), m.end()});
                wantGroupSpans.add(m.groupSpans(1));
            }
            assertThat(spans).as("MVT spans split %d", split).usingRecursiveComparison().isEqualTo(wantSpans);
            assertThat(groupSpans).as("MVT groupSpans split %d", split).usingRecursiveComparison()
                .isEqualTo(wantGroupSpans);
        }
    }

    /** Group text is readable while the search stands at the match; offsets are absolute. */
    @Test
    void groupTextAndAbsoluteOffsets() {
        String text = "one two three four";
        StreamMatcher sm = Pattern.compile("(\\w+) (\\w+)").streamingMatcher(charChunks(text, 5));
        assertThat(sm.next()).isTrue();
        assertThat(sm.result().start(0)).isEqualTo(0);
        assertThat(sm.result().end(0)).isEqualTo(7);
        assertThat(sm.group(1)).isEqualTo("one");
        assertThat(sm.group(2)).isEqualTo("two");
        assertThat(sm.next()).isTrue();
        assertThat(sm.result().start(0)).isEqualTo(8);
        assertThat(sm.group(1)).isEqualTo("three");
        assertThat(sm.group(2)).isEqualTo("four");
        assertThat(sm.next()).isFalse();
        assertThat(sm.result()).isNotNull(); // last match stays readable after exhaustion
    }

    /** The UTF-8 adapter: incremental decode with multi-byte straddles at read level. */
    @Test
    void utf8AdapterHandlesStraddledSequences() {
        String text = "a\u00e9\u20ac\ud83d\ude00\u007f b\u00e9"; // 1,2,3,4-byte sequences
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        for (int maxRead = 1; maxRead <= bytes.length; maxRead++) {
            assertThat(streamAllText(Pattern.compile("\\p{L}+"),
                ChunkSources.utf8(new FixedReadInputStream(bytes, maxRead), 2))).as("utf8 read window %d", maxRead)
                .isEqualTo(stringTexts(Pattern.compile("\\p{L}+"), text));
        }
        // malformed tail: replacement char, mirroring new String(bytes, UTF_8)
        byte[] malformed = {0x61, (byte) 0xC3}; // 'a' + dangling 2-byte lead
        StreamMatcher sm =
            Pattern.compile("a.").streamingMatcher(ChunkSources.utf8(new ByteArrayInputStream(malformed)));
        assertThat(sm.next()).isTrue();
        assertThat(sm.group(0)).isEqualTo("a\uFFFD");
    }

    /** The Reader and ByteBuffer adapters. */
    @Test
    void readerAndByteBufferAdapters() {
        String text = "alpha beta gamma";
        assertThat(streamAllText(Pattern.compile("\\w+"), ChunkSources.reader(new StringReader(text), 3)))
            .isEqualTo(stringTexts(Pattern.compile("\\w+"), text));
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        List<ByteBuffer> buffers = new ArrayList<>();
        for (int i = 0; i < bytes.length; i += 2) {
            int n = Math.min(2, bytes.length - i);
            buffers.add(ByteBuffer.wrap(bytes, i, n).slice());
        }
        CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPLACE)
            .onUnmappableCharacter(CodingErrorAction.REPLACE);
        assertThat(streamAllText(Pattern.compile("\\w+"), ChunkSources.byteBuffers(buffers.iterator(), decoder, 4)))
            .isEqualTo(stringTexts(Pattern.compile("\\w+"), text));
    }

    /** The window budget: a live-tail pattern over an unresolvable stream fails loudly. */
    @Test
    void windowBudgetTripsLoudly() {
        String prev = System.setProperty(Budgets.STREAM_WINDOW_PROP, "8");
        try {
            StringBuilder text = new StringBuilder();
            for (int i = 0; i < 32; i++) {
                text.append('a');
            }
            Pattern p = Pattern.compile("a.*x"); // walk from 0 stays live to EOF
            StreamMatcher sm = p.streamingMatcher(ChunkSources.of(text));
            assertThatThrownBy(sm::next).isInstanceOf(StreamWindowOverflowException.class)
                .hasMessageContaining("tdfa.budget.stream.window");
        } finally {
            if (prev == null) {
                System.clearProperty(Budgets.STREAM_WINDOW_PROP);
            } else {
                System.setProperty(Budgets.STREAM_WINDOW_PROP, prev);
            }
        }
    }

    /** The bounded-memory flagship: a needle search retains only the tail. */
    @Test
    void literalNeedleBoundedAcrossHugeStream() {
        int repeats = 100_000;
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < repeats; i++) {
            text.append("filler ").append(i).append(" needle tail\n");
        }
        StreamMatcher sm = Pattern.compile("needle").streamingMatcher(ChunkSources.of(text));
        int found = 0;
        while (sm.next()) {
            found++;
        }
        assertThat(found).isEqualTo(repeats);
    }

    /** Contract: an empty chunk is a loud violation, not a silent skip. */
    @Test
    void emptyChunkIsRejected() {
        StreamMatcher sm = Pattern.compile("a").streamingMatcher(() -> "");
        assertThatThrownBy(sm::next).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("empty chunk");
    }

    /** Engines without the seam are rejected loudly by the facade. */
    @Test
    void nonStreamingEngineRejectedLoudly() {
        Pattern p = Pattern.compile("a", 0, NotStreamingEngine::new);
        assertThatThrownBy(() -> p.streamingMatcher(ChunkSources.of("a")))
            .isInstanceOf(UnsupportedOperationException.class).hasMessageContaining("StreamEngine");
    }

    /** The dropped-chunk rejection: absolute CharSequence view below the base. */
    @Test
    void droppedInputFailsLoudlyOnRead() {
        StreamMatcher sm = Pattern.compile("zzz").streamingMatcher(ChunkSources.of("aaaaazzz"));
        assertThat(sm.next()).isTrue();
        // exhaust (no further match) — the window keeps [6, 8); index 5 is dropped
        assertThat(sm.next()).isFalse();
        assertThatThrownBy(() -> sm.charAt(0)).isInstanceOf(IndexOutOfBoundsException.class)
            .hasMessageContaining("dropped input");
    }

    // ===== helpers =====

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

    private static ChunkSource source(List<String> pieces) {
        return new ChunkSource() {
            private int i;

            @Override
            public CharSequence next() {
                return i < pieces.size() ? pieces.get(i++) : null;
            }
        };
    }

    /** Chunks of exactly {@code size} chars, skipping the empty tail. */
    private static ChunkSource charChunks(String text, int size) {
        List<String> pieces = new ArrayList<>();
        for (int i = 0; i < text.length(); i += size) {
            pieces.add(text.substring(i, Math.min(i + size, text.length())));
        }
        return source(pieces);
    }

    private static void assertStreamingEqualsString(String regex, int flags, String input, List<String> pieces) {
        List<int[]> streamed = streamAll(Pattern.compile(regex, flags), pieces);
        List<int[]> string = stringAll(Pattern.compile(regex, flags), input);
        assertThat(streamed).as("regex <%s> input <%s> pieces %s", regex, input, pieces).usingRecursiveComparison()
            .isEqualTo(string);
    }

    /** All matches as flat [start0,end0,start1,end1,...] arrays (groups included). */
    private static List<int[]> streamAll(Pattern p, List<String> pieces) {
        return streamAllText0(p.streamingMatcher(source(pieces)));
    }

    private static List<int[]> streamAll(io.github.jemmix.tdfa.re2j.Pattern p, List<String> pieces) {
        return streamAllText0(p.streamingMatcher(source(pieces)));
    }

    private static List<int[]> stringAll(Pattern p, String input) {
        PatternMatcher m = p.matcher(input);
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

    private static List<int[]> stringAll(io.github.jemmix.tdfa.re2j.Pattern p, String input) {
        PatternMatcher m = p.matcher(input);
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

    private static List<int[]> streamAllText0(StreamMatcher sm) {
        List<int[]> out = new ArrayList<>();
        while (sm.next()) {
            out.add(flat(sm.result()));
        }
        return out;
    }

    /** Whole-match texts (group text through the window). */
    private static List<String> streamAllText(Pattern p, ChunkSource source) {
        StreamMatcher sm = p.streamingMatcher(source);
        List<String> out = new ArrayList<>();
        while (sm.next()) {
            out.add(sm.group(0));
        }
        return out;
    }

    private static List<String> stringTexts(Pattern p, String input) {
        PatternMatcher m = p.matcher(input);
        List<String> out = new ArrayList<>();
        while (m.find()) {
            out.add(m.group());
        }
        return out;
    }

    private static int[] flat(MatchResult r) {
        int gc = r.groupCount();
        int[] row = new int[2 * (gc + 1)];
        row[0] = r.start(0);
        row[1] = r.end(0);
        for (int g = 1; g <= gc; g++) {
            row[2 * g] = r.start(g);
            row[2 * g + 1] = r.end(g);
        }
        return row;
    }

    /** InputStream that returns at most {@code max} bytes per read (straddle forcing). */
    private static final class FixedReadInputStream extends InputStream {
        private final byte[] bytes;
        private final int max;
        private int pos;

        FixedReadInputStream(byte[] bytes, int max) {
            this.bytes = bytes;
            this.max = max;
        }

        @Override
        public int read() throws IOException {
            if (pos >= bytes.length) {
                return -1;
            }
            return bytes[pos++] & 0xFF;
        }

        @Override
        public int read(byte[] b, int off, int len) {
            if (pos >= bytes.length) {
                return -1;
            }
            int n = Math.min(Math.min(len, max), bytes.length - pos);
            System.arraycopy(bytes, pos, b, off, n);
            pos += n;
            return n;
        }
    }

    /** A minimal engine that implements no streaming seam. */
    private static final class NotStreamingEngine implements RegexEngine {
        NotStreamingEngine(Tdfa tdfa) {
            // artifact ignored: this engine answers nothing
        }

        @Override
        public boolean matches(CharSequence input) {
            return false;
        }

        @Override
        public boolean find(CharSequence input) {
            return false;
        }

        @Override
        public MatchResult match(CharSequence input, int from, MatchScratch scratch) {
            return null;
        }

        @Override
        public int groupCount() {
            return 0;
        }

        @Override
        public Map<String, Integer> namedGroups() {
            return Collections.emptyMap();
        }

        @Override
        public int programSize() {
            return 1;
        }
    }
}
