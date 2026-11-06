package io.github.jemmix.tdfa.bench;

import io.github.jemmix.tdfa.Pattern;
import io.github.jemmix.tdfa.core.compile.PatternMatcher;
import io.github.jemmix.tdfa.core.compile.RegexEngineFactory;
import io.github.jemmix.tdfa.core.dfa.StreamMatcher;
import io.github.jemmix.tdfa.core.dfa.TdfaRunner;
import io.github.jemmix.tdfa.core.engine.ChunkSource;
import io.github.jemmix.tdfa.core.engine.ChunkSources;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OperationsPerInvocation;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Random;
import java.util.concurrent.TimeUnit;

/**
 * Streaming input vs the materialized String surface over the same data
 * (JMH SingleShotTime, ns per full-stream scan; see
 * {@code docs/streaming-input.md}). The streaming pitch is memory shape,
 * not raw speed: the matcher's window stays at the retention floor
 * (needle-length for literal searches, the undecided walk span otherwise)
 * while the String surface materializes the whole stream — these rows
 * price the trade honestly, same engine, same pattern, same data.
 *
 * <p>Shapes: literal needle (the bounded-window flagship), log-shape
 * capture extraction, a no-match scan, and a live-tail (a walk that can
 * match but never resolves until EOF — the window grows to the stream;
 * the honest worst case, priced small). The utf8 rows run the byte-side
 * pipeline (ByteArrayInputStream + incremental decoder) to show decode
 * cost beside matching cost.
 *
 * <p>Setup verifies the streaming and String surfaces agree on every
 * match count. Reproduce with
 * {@code ./gradlew :benchmarks:micro:jmh -PjmhInclude='StreamingBench'}.
 */
@BenchmarkMode(Mode.SingleShotTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 3)
@Measurement(iterations = 8)
@Fork(1)
@OperationsPerInvocation(StreamingBench.SCANS)
public class StreamingBench {

    static final int SCANS = 20;
    static final int CHARS = 2 * 1024 * 1024;

    @State(Scope.Benchmark)
    public static class BenchState {
        @Param({"vm", "asm"})
        public String engine;

        @Param({"needle", "ipExtract", "noMatch", "liveTail"})
        public String shape;

        @Param({"4096", "65536"})
        public int chunkChars;

        String text;
        byte[] utf8;
        Pattern pattern;
        long expectedCount;

        @Setup(Level.Trial)
        public void setUp() {
            Random rnd = new Random(0x57DEA11L);
            StringBuilder sb = new StringBuilder(CHARS + 128);
            int needles = 0;
            int lines = 0;
            while (sb.length() < CHARS) {
                int kind = rnd.nextInt(9);
                if (shape.equals("needle") && kind == 0) {
                    sb.append("prefix needle suffix\n");
                    needles++;
                } else if (shape.equals("ipExtract") && kind <= 2) {
                    sb.append("lvl=warn ip=").append(10 + rnd.nextInt(200)).append('.').append(rnd.nextInt(256))
                        .append('.').append(rnd.nextInt(256)).append('.').append(1 + rnd.nextInt(250))
                        .append(" msg=ok session=0x").append(Integer.toHexString(rnd.nextInt())).append('\n');
                } else if (shape.equals("liveTail") && kind == 0 && needles == 0) {
                    // exactly one BEGIN, never an THEEND: the live-tail walk
                    sb.append("BEGIN transaction payload ");
                    needles++;
                } else {
                    sb.append("lvl=info thread=worker-").append(rnd.nextInt(32)).append(" msg=flushed bytes=")
                        .append(100 + rnd.nextInt(900)).append(" ms=").append(rnd.nextInt(1000)).append('\n');
                }
                lines++;
            }
            text = sb.toString();
            utf8 = text.getBytes(StandardCharsets.UTF_8);
            String regex =
                shape.equals("needle") ? "needle" : shape.equals("ipExtract") ? "ip=(\\d+\\.\\d+\\.\\d+\\.\\d+)"
                    : shape.equals("noMatch") ? "[a-z]+@[a-z]+\\.[a-z]{3}" : "(?s)BEGIN.*THEEND";
            RegexEngineFactory factory = engine.equals("vm") ? TdfaRunner::new : null;
            pattern = Pattern.compile(regex, 0, factory);
            PatternMatcher m = pattern.matcher(text);
            long count = 0;
            while (m.find()) {
                count++;
            }
            expectedCount = count;
            if (streamCount() != expectedCount) {
                throw new IllegalStateException(
                    "stream/string disagreement: " + shape + " " + streamCount() + " vs " + expectedCount);
            }
        }

        private long streamCount() {
            StreamMatcher sm = pattern.streamingMatcher(chunkSource());
            long count = 0;
            while (sm.next()) {
                count++;
            }
            return count;
        }

        ChunkSource chunkSource() {
            return new ChunkSource() {
                private int pos;

                @Override
                public CharSequence next() {
                    if (pos >= text.length()) {
                        return null;
                    }
                    int end = Math.min(pos + chunkChars, text.length());
                    String chunk = text.substring(pos, end);
                    pos = end;
                    return chunk;
                }
            };
        }

        InputStream utf8Stream() {
            return new ByteArrayInputStream(utf8);
        }
    }

    @Benchmark
    public long streamScan(BenchState s, Blackhole bh) {
        StreamMatcher sm = s.pattern.streamingMatcher(s.chunkSource());
        long count = 0;
        while (sm.next()) {
            count++;
        }
        bh.consume(count);
        return count;
    }

    @Benchmark
    public long utf8StreamScan(BenchState s, Blackhole bh) {
        StreamMatcher sm = s.pattern.streamingMatcher(ChunkSources.utf8(s.utf8Stream(), s.chunkChars));
        long count = 0;
        while (sm.next()) {
            count++;
        }
        bh.consume(count);
        return count;
    }

    @Benchmark
    public long stringScan(BenchState s, Blackhole bh) {
        PatternMatcher m = s.pattern.matcher(s.text);
        long count = 0;
        while (m.find()) {
            count++;
        }
        bh.consume(count);
        return count;
    }
}
