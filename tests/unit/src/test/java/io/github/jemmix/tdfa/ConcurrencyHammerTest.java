package io.github.jemmix.tdfa;

import io.github.jemmix.tdfa.core.MatchResult;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Conformance of the RegexEngine thread-safety contract ("effectively
 * immutable, safe for concurrent use") under real contention (2026-09
 * pre-freeze review). Hammers the two lazily-materialized shared structures:
 * the per-runner search-DFA memo (SearchDfa) and the per-state walk blocks
 * (walkBlockIdx) — both previously mutated/read without synchronization or
 * safe publication. Every thread must observe bit-identical results to the
 * single-threaded reference, on inputs long enough to engage the memoized
 * paths (search-DFA window ≥ 2048 chars; walk blocks on non-Latin-1 text).
 */
class ConcurrencyHammerTest {

    private static final int THREADS = 8;

    private static String text(String seed, int kb) {
        StringBuilder sb = new StringBuilder(kb * 1024 + 64);
        long x = 12345;
        while (sb.length() < kb * 1024) {
            x = x * 6364136223846793005L + 1442695040888963407L;
            int r = (int) (x >>> 33);
            switch (r & 7) {
                case 0 -> sb.append("word").append(r % 100).append(' ');
                case 1 -> sb.append("setting ");
                case 2 -> sb.append("лttesпословица").append(r % 10).append(' ');   // Cyrillic: walk blocks
                case 3 -> sb.append("ing ".repeat(1 + (r % 3)));
                case 4 -> sb.append('\n');
                case 5 -> sb.append("Λγώ").append(r % 7);                             // Greek: non-Latin-1
                case 6 -> sb.append((char) ('a' + (r % 26)));
                default -> sb.append("email").append(r % 1000).append("@host\n");
            }
        }
        return sb.toString();
    }

    /** Deterministic result digest of find()-iteration over the text. */
    private static long digest(Pattern p, String input) {
        long h = 17;
        io.github.jemmix.tdfa.core.Matcher m = p.matcher(input);
        int n = 0;
        while (m.find()) {
            h = h * 1000003L + m.start();
            h = h * 1000003L + m.end();
            if (m.groupCount() > 0) {
                String g = m.group(1);
                h = h * 1000003L + (g == null ? -1 : g.hashCode());
            }
            n++;
        }
        return h * 1000003L + n;
    }

    private static long digestCore(io.github.jemmix.tdfa.core.CompiledRegex r, String input) {
        long h = 29;
        for (MatchResult m : r.findAll(input)) {
            h = h * 1000003L + m.start(0);
            h = h * 1000003L + m.end(0);
        }
        return h;
    }

    private static void hammer(String name, Callable<Long> reference, Callable<Long> worker) throws Exception {
        long expect = 17;
        for (int rep = 0; rep < 3; rep++) expect = expect * 31 + reference.call();
        assertThat(expect).as(name + ": reference found at least one match").isNotZero();
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        try {
            CyclicBarrier start = new CyclicBarrier(THREADS);
            List<Future<Long>> fs = new ArrayList<>();
            for (int t = 0; t < THREADS; t++) {
                fs.add(pool.submit(() -> {
                    start.await();                       // maximize simultaneous cold-start
                    long h = 17;
                    for (int rep = 0; rep < 3; rep++) h = h * 31 + worker.call();
                    return h;
                }));
            }
            for (Future<Long> f : fs) {
                assertThat(f.get(60, TimeUnit.SECONDS))
                        .as(name + ": concurrent results identical to single-threaded")
                        .isEqualTo(expect);
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test void searchDfaMemoConcurrentFind() throws Exception {
        String input = text("sdfa", 40);   // 40 KB: well past the 2048-char trigger window
        Pattern p = Pattern.compile("\\w+ing\\b|email\\d+@host");
        hammer("searchDfa", () -> digest(p, input), () -> digest(p, input));
    }

    @Test void walkBlocksConcurrentWideClassScan() throws Exception {
        String input = text("walk", 30);   // Cyrillic/Greek mix builds per-state walk blocks
        Pattern p = Pattern.compile("\\p{L}{3,}\\d");
        hammer("walkBlocks", () -> digest(p, input), () -> digest(p, input));
    }

    @Test void anchoredMatchesConcurrent() throws Exception {
        String input = text("anch", 20);
        Pattern p = Pattern.compile("(\\w+)@(\\w+)");
        hammer("anchored", () -> digest(p, input), () -> digest(p, input));
    }

    @Test void coreTierConcurrentFindAll() throws Exception {
        String input = text("core", 25);
        io.github.jemmix.tdfa.core.CompiledRegex r =
                io.github.jemmix.tdfa.core.CompiledRegex.compile("[\\x{400}-\\x{4FF}]{2,}|\\w+ing");
        hammer("core", () -> digestCore(r, input), () -> digestCore(r, input));
    }

    @Test void lazyQuantifierTriggerScanConcurrent() throws Exception {
        String input = text("lazy", 30);
        Pattern p = Pattern.compile(".*?-ing|Л{2}");
        hammer("lazyTrigger", () -> digest(p, input), () -> digest(p, input));
    }
}
