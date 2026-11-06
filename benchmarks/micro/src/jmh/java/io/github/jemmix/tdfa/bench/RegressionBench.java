package io.github.jemmix.tdfa.bench;

import io.github.jemmix.tdfa.Pattern;
import io.github.jemmix.tdfa.core.dfa.TdfaRunner;
import io.github.jemmix.tdfa.core.engine.Matcher;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

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

    // ===== compile-time (P6 target) =====
    static final String COMPILE_RE = "(\\w+)@(\\w+)\\.(com|org|net)|#([0-9a-f]{6})|\\bword\\b";

    // ===== patterns =====
    static final Pattern VM_TWO = Pattern.compile("(\\w+)\\s+(\\w+)", 0, TdfaRunner::new);
    static final Pattern ASM_TWO = Pattern.compile("(\\w+)\\s+(\\w+)");
    static final String IN_TWO = "hello brave new world 42";

    static final Pattern VM_IP = Pattern.compile("(\\d+)\\.(\\d+)\\.(\\d+)\\.(\\d+)", 0, TdfaRunner::new);
    static final Pattern ASM_IP = Pattern.compile("(\\d+)\\.(\\d+)\\.(\\d+)\\.(\\d+)");
    static final String IN_IP = "ip=192.168.1.77 rest";

    static final Pattern VM_SCAN = Pattern.compile("[a-z]+qrst", 0, TdfaRunner::new);
    static final Pattern ASM_SCAN = Pattern.compile("[a-z]+qrst");

    static final Pattern VM_DENSE = Pattern.compile("[a-z]+ing", 0, TdfaRunner::new);
    static final Pattern ASM_DENSE = Pattern.compile("[a-z]+ing");

    static final Pattern VM_SPARSE = Pattern.compile("z[0-9]{3}q", 0, TdfaRunner::new);
    static final Pattern ASM_SPARSE = Pattern.compile("z[0-9]{3}q");

    // Codepoint-lane rows (the :re2j surface) — wide classes, dense findAll
    // and a dense no-match scan, supplementary letters included (review item
    // C46; no ASCII 'x' anywhere, so the no-match shape cannot match).
    static final io.github.jemmix.tdfa.re2j.Pattern VM_RE2J_WIDE =
        io.github.jemmix.tdfa.re2j.Pattern.compile("\\p{L}{2,}", 0, TdfaRunner::new);
    static final io.github.jemmix.tdfa.re2j.Pattern ASM_RE2J_WIDE =
        io.github.jemmix.tdfa.re2j.Pattern.compile("\\p{L}{2,}");
    static final io.github.jemmix.tdfa.re2j.Pattern VM_RE2J_WIDE_X =
        io.github.jemmix.tdfa.re2j.Pattern.compile("\\p{L}{2,}x", 0, TdfaRunner::new);
    static final io.github.jemmix.tdfa.re2j.Pattern ASM_RE2J_WIDE_X =
        io.github.jemmix.tdfa.re2j.Pattern.compile("\\p{L}{2,}x");

    // ===== haystacks (built statically; each ~64 KB) =====
    static final String NO_MATCH_64K = buildNoMatch(1 << 16);
    static final String DENSE_64K = buildDense(1 << 16);
    static final String SPARSE_64K = buildSparse(1 << 16);
    static final String WIDE_RE2J_64K = buildWideRe2j(1 << 16);

    static String buildNoMatch(int len) {
        StringBuilder b = new StringBuilder(len + 16);
        String unit = "the quick brown fox jumps over lazy dogs 0123 ";
        while (b.length() < len) {
            b.append(unit);
        }
        b.append("never matching tail");
        return b.toString();
    }

    static String buildDense(int len) {
        StringBuilder b = new StringBuilder(len + 16);
        String unit = "running singing hopping jumping coding "; // match every ~7 chars
        while (b.length() < len) {
            b.append(unit);
        }
        return b.toString();
    }

    static String buildSparse(int len) {
        StringBuilder b = new StringBuilder(len + 16);
        String unit = "lorem ipsum dolor sit z123q amet consec z987q tetur adiscing elit ";
        while (b.length() < len) {
            b.append(unit);
        }
        return b.toString();
    }

    static String buildWideRe2j(int len) {
        StringBuilder b = new StringBuilder(len + 16);
        String unit =
            "\u0441\u043b\u043e\u0432\u043e \u0437\u0430\u043f\u0430\u0441 \uD801\uDC00\uD801\uDC01 \u043e\u0431\u0441\u0442\u043e\u044f\u0442\u0435\u043b\u044c\u043d\u043e ";
        while (b.length() < len) {
            b.append(unit);
        }
        return b.toString();
    }

    // ===== anchored short match =====
    @Benchmark
    public boolean vmAnchored() {
        return VM_TWO.matches(IN_TWO);
    }

    @Benchmark
    public boolean asmAnchored() {
        return ASM_TWO.matches(IN_TWO);
    }

    // ===== short-input extraction =====
    @Benchmark
    public boolean vmExtract() {
        return VM_IP.matcher(IN_IP).find();
    }

    @Benchmark
    public boolean asmExtract() {
        return ASM_IP.matcher(IN_IP).find();
    }

    // ===== long-scan, no match (multi-state sim + ASCII table per-char cost) =====
    @Benchmark
    public boolean vmScanNoMatch() {
        return VM_SCAN.matcher(NO_MATCH_64K).find();
    }

    @Benchmark
    public boolean asmScanNoMatch() {
        return ASM_SCAN.matcher(NO_MATCH_64K).find();
    }

    // ===== findAll: dense matches — the extract-restart shape (P1 target) =====
    @Benchmark
    public int vmFindAllDense() {
        return findAll(VM_DENSE, DENSE_64K);
    }

    @Benchmark
    public int asmFindAllDense() {
        return findAll(ASM_DENSE, DENSE_64K);
    }

    // ===== findAll: sparse matches — restart-per-match without dense-path pressure =====
    @Benchmark
    public int vmFindAllSparse() {
        return findAll(VM_SPARSE, SPARSE_64K);
    }

    @Benchmark
    public int asmFindAllSparse() {
        return findAll(ASM_SPARSE, SPARSE_64K);
    }

    // ===== codepoint lane (:re2j surface): wide-class dense findAll and
    // dense no-match scan — ORIGIN_SIM / codepoint triggerScan / pair-interior
    // skips, the rungs the C6–C9 folds touch (review item C46) =====
    @Benchmark
    public int vmRe2jFindAllWide() {
        return findAll(VM_RE2J_WIDE, WIDE_RE2J_64K);
    }

    @Benchmark
    public int asmRe2jFindAllWide() {
        return findAll(ASM_RE2J_WIDE, WIDE_RE2J_64K);
    }

    @Benchmark
    public boolean vmRe2jScanWideNoMatch() {
        return VM_RE2J_WIDE_X.matcher(WIDE_RE2J_64K).find();
    }

    @Benchmark
    public boolean asmRe2jScanWideNoMatch() {
        return ASM_RE2J_WIDE_X.matcher(WIDE_RE2J_64K).find();
    }

    static int findAll(io.github.jemmix.tdfa.re2j.Pattern r, String in) {
        int n = 0;
        for (Matcher m = r.matcher(in); m.find();) {
            n++;
        }
        return n;
    }

    static int findAll(Pattern r, String in) {
        int n = 0;
        for (Matcher m = r.matcher(in); m.find();) {
            n++;
        }
        return n;
    }

    @Benchmark
    public Pattern vmCompile() {
        return Pattern.compile(COMPILE_RE, 0, TdfaRunner::new);
    }

    @Benchmark
    public Pattern asmCompile() {
        return Pattern.compile(COMPILE_RE);
    }
}
