package io.github.jemmix.tdfa.bench;

import io.github.jemmix.tdfa.EngineFactory;
import io.github.jemmix.tdfa.Regex;
import io.github.jemmix.tdfa.core.MatchResult;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

import java.util.concurrent.TimeUnit;

/**
 * Regression-tracking suite — OUR engine only (VM + ASM), no cross-engine comparison.
 *
 * <p>Covers the workload shapes that the performance roadmap touches, so each
 * optimization step can be validated against a stored baseline
 * ({@code scripts/bench-regression.sh} + {@code scripts/bench-compare.py}):
 * <ul>
 *   <li>{@code anchored*}   — short-input full-match (basic engine speed)</li>
 *   <li>{@code extract*}    — capture-group extraction, short input</li>
 *   <li>{@code scanNoMatch} — long-input boolean find, no match (multi-state sim + per-char table path)</li>
 *   <li>{@code findAll*}    — repeated find-with-extraction over dense/sparse matches
 *       (the O(n²) extract-restart shape — see TODO P1)</li>
 *   <li>{@code compile*}    — compile-time cost (TODO P6)</li>
 * </ul>
 *
 * <p>Iterations are deliberately short: the suite must be cheap enough to run
 * as a gate (~1 min). Scores are wall-time per operation; dense findAll is
 * reported in µs per full pass over the haystack.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 2, time = 1)
@Measurement(iterations = 3, time = 1)
@Fork(1)
public class RegressionBench {

    // ===== patterns =====
    static final Regex VM_TWO = Regex.compile("(\\w+)\\s+(\\w+)", EngineFactory.VM);
    static final Regex ASM_TWO = Regex.compile("(\\w+)\\s+(\\w+)", EngineFactory.ASM);
    static final String IN_TWO = "hello brave new world 42";

    static final Regex VM_IP = Regex.compile("(\\d+)\\.(\\d+)\\.(\\d+)\\.(\\d+)", EngineFactory.VM);
    static final Regex ASM_IP = Regex.compile("(\\d+)\\.(\\d+)\\.(\\d+)\\.(\\d+)", EngineFactory.ASM);
    static final String IN_IP = "ip=192.168.1.77 rest";

    static final Regex VM_SCAN = Regex.compile("[a-z]+qrst", EngineFactory.VM);
    static final Regex ASM_SCAN = Regex.compile("[a-z]+qrst", EngineFactory.ASM);

    static final Regex VM_DENSE = Regex.compile("[a-z]+ing", EngineFactory.VM);
    static final Regex ASM_DENSE = Regex.compile("[a-z]+ing", EngineFactory.ASM);

    static final Regex VM_SPARSE = Regex.compile("z[0-9]{3}q", EngineFactory.VM);
    static final Regex ASM_SPARSE = Regex.compile("z[0-9]{3}q", EngineFactory.ASM);

    // ===== haystacks (built statically; each ~64 KB) =====
    static final String NO_MATCH_64K = buildNoMatch(1 << 16);
    static final String DENSE_64K = buildDense(1 << 16);
    static final String SPARSE_64K = buildSparse(1 << 16);

    static String buildNoMatch(int len) {
        StringBuilder b = new StringBuilder(len + 16);
        String unit = "the quick brown fox jumps over lazy dogs 0123 ";
        while (b.length() < len) b.append(unit);
        b.append("never matching tail");
        return b.toString();
    }

    static String buildDense(int len) {
        StringBuilder b = new StringBuilder(len + 16);
        String unit = "running singing hopping jumping coding ";  // match every ~7 chars
        while (b.length() < len) b.append(unit);
        return b.toString();
    }

    static String buildSparse(int len) {
        StringBuilder b = new StringBuilder(len + 16);
        String unit = "lorem ipsum dolor sit z123q amet consec z987q tetur adiscing elit ";
        while (b.length() < len) b.append(unit);
        return b.toString();
    }

    // ===== anchored short match =====
    @Benchmark public boolean vmAnchored() { return VM_TWO.matches(IN_TWO); }
    @Benchmark public boolean asmAnchored() { return ASM_TWO.matches(IN_TWO); }

    // ===== short-input extraction =====
    @Benchmark public MatchResult vmExtract() { return VM_IP.find(IN_IP, 0); }
    @Benchmark public MatchResult asmExtract() { return ASM_IP.find(IN_IP, 0); }

    // ===== long-scan, no match (multi-state sim + ASCII table per-char cost) =====
    @Benchmark public boolean vmScanNoMatch() { return VM_SCAN.find(NO_MATCH_64K); }
    @Benchmark public boolean asmScanNoMatch() { return ASM_SCAN.find(NO_MATCH_64K); }

    // ===== findAll: dense matches — the extract-restart shape (P1 target) =====
    @Benchmark public int vmFindAllDense() { return findAll(VM_DENSE, DENSE_64K); }
    @Benchmark public int asmFindAllDense() { return findAll(ASM_DENSE, DENSE_64K); }

    // ===== findAll: sparse matches — restart-per-match without dense-path pressure =====
    @Benchmark public int vmFindAllSparse() { return findAll(VM_SPARSE, SPARSE_64K); }
    @Benchmark public int asmFindAllSparse() { return findAll(ASM_SPARSE, SPARSE_64K); }

    static int findAll(Regex r, String in) {
        int n = 0;
        int from = 0;
        MatchResult m;
        while ((m = r.find(in, from)) != null) {
            n++;
            from = m.end(0) > m.start(0) ? m.end(0) : m.end(0) + 1;
        }
        return n;
    }

    // ===== compile-time (P6 target) =====
    static final String COMPILE_RE = "(\\w+)@(\\w+)\\.(com|org|net)|#([0-9a-f]{6})|\\bword\\b";
    @Benchmark public Regex vmCompile() {
        return Regex.compile(COMPILE_RE, EngineFactory.VM);
    }
    @Benchmark public Regex asmCompile() {
        return Regex.compile(COMPILE_RE, EngineFactory.ASM);
    }
}
