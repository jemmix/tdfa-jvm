package io.github.jemmix.tdfa.bench;

import io.github.jemmix.tdfa.core.RegexEngine;
import io.github.jemmix.tdfa.core.RegexEngineFactory;
import io.github.jemmix.tdfa.tdfa.TdfaRunner;
import io.github.jemmix.tdfa.ast.Ast;
import io.github.jemmix.tdfa.ast.CharClass;
import io.github.jemmix.tdfa.parser.Parser;
import io.github.jemmix.tdfa.rebar.Scenario;
import io.github.jemmix.tdfa.rebar.ScenarioLoader;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.Locale;

/**
 * rebar-corpus 5-engine benchmark (plain main, QuickBench convention — not JMH).
 *
 * <p>Runs every in-scope rebar scenario (the same filter as
 * {@code RebarScenarioParityTest}: engines list includes {@code java/},
 * supported model, sane sizes) under its declared rebar model against five
 * engines — java.util.regex, com.google.re2j, reggie, and our VM + ASM
 * backends — and prints a review-friendly 5-column table.
 *
 * <p><b>Goal framing:</b> we must beat re2j decisively and reach parity with
 * java.util.regex; reggie is reference only.
 *
 * <p><b>Integrity:</b> every engine's model result is counted during the
 * warmup pass and cross-checked against java.util.regex on the same input
 * (fallback: re2j, then VM). A {@code *} marks count divergence — the timing
 * is still printed but flags different work, not just different speed.
 *
 * <p><b>Modes:</b>
 * <ul>
 *   <li>{@code fast} — haystack truncated to 2M chars, 1 warmup + min of 2
 *       passes, compile budget 20 s, pass budget 5 s, whole-run deadline
 *       9 min. Target: triage signal in minutes.</li>
 *   <li>{@code accurate} — full haystacks (up to 80 MB), 2 warmups + min of
 *       5 passes, compile budget 300 s, pass budget 60 s. Overnight.</li>
 * </ul>
 *
 * <p><b>Thermal fairness:</b> measured passes are interleaved
 * engine-round-robin within each pass, so CPU thermal drift hits all engines
 * symmetrically (see QuickBench history for why single-run raw deltas lie on
 * this machine).
 *
 * <p><b>Timeout orphans:</b> a timed-out compile keeps burning a background
 * virtual thread (parity-test convention); the fast compile budget (20 s) is
 * chosen to fully cover dictionary/single (~14 s single-threaded) so the
 * common case produces no orphans.
 *
 * <p>Usage: {@code RebarBench [fast|accurate] --dir <rebar-benchmarks-dir>
 * [--filter substr] [--passes N] [--max-chars N]}
 */
public final class RebarBench {

    // ===== engine adapter =====

    interface M {
        void reset(CharSequence cs);
        boolean find();
        int start();
        int end();
        int start(int group);
        int groupCount();
    }

    interface Engine {
        String name();
        Object compile(String regex, boolean ci, boolean uni) throws Exception;
        M matcher(Object pattern, CharSequence cs);
    }

    /** java.util.regex / re2j / our re2j-compat Matcher share the exact shape. */
    private static final class ReflectFreeAdapter implements M {
        // built per-call via lambdas below; no reflection, direct calls
        private final java.util.function.Consumer<CharSequence> reset;
        private final java.util.function.BooleanSupplier find;
        private final java.util.function.IntSupplier start, end, groupCount;
        private final java.util.function.IntUnaryOperator startG;
        ReflectFreeAdapter(java.util.function.Consumer<CharSequence> reset,
                           java.util.function.BooleanSupplier find,
                           java.util.function.IntSupplier start,
                           java.util.function.IntSupplier end,
                           java.util.function.IntUnaryOperator startG,
                           java.util.function.IntSupplier groupCount) {
            this.reset = reset; this.find = find; this.start = start; this.end = end;
            this.startG = startG; this.groupCount = groupCount;
        }
        @Override public void reset(CharSequence cs) { reset.accept(cs); }
        @Override public boolean find() { return find.getAsBoolean(); }
        @Override public int start() { return start.getAsInt(); }
        @Override public int end() { return end.getAsInt(); }
        @Override public int start(int g) { return startG.applyAsInt(g); }
        @Override public int groupCount() { return groupCount.getAsInt(); }
    }

    static final Engine JUR = new Engine() {
        @Override public String name() { return "jur"; }
        @Override public Object compile(String regex, boolean ci, boolean uni) {
            int f = (ci ? java.util.regex.Pattern.CASE_INSENSITIVE : 0)
                    | (uni ? java.util.regex.Pattern.UNICODE_CHARACTER_CLASS : 0);
            return java.util.regex.Pattern.compile(regex, f);
        }
        @Override public M matcher(Object p, CharSequence cs) {
            java.util.regex.Matcher m = ((java.util.regex.Pattern) p).matcher(cs);
            return new ReflectFreeAdapter(m::reset, m::find, m::start, m::end, m::start, m::groupCount);
        }
    };

    static final Engine RE2J = new Engine() {
        @Override public String name() { return "re2j"; }
        @Override public Object compile(String regex, boolean ci, boolean uni) {
            return com.google.re2j.Pattern.compile(regex, ci ? com.google.re2j.Pattern.CASE_INSENSITIVE : 0);
        }
        @Override public M matcher(Object p, CharSequence cs) {
            com.google.re2j.Matcher m = ((com.google.re2j.Pattern) p).matcher(cs);
            return new ReflectFreeAdapter(m::reset, m::find, m::start, m::end, m::start, m::groupCount);
        }
    };

    /** reggie: stateless-per-call API — emulate java.util.regex cursor semantics. */
    static final class ReggieM implements M {
        private final com.datadoghq.reggie.runtime.ReggieMatcher rm;
        private String s = "";
        private int from;
        private com.datadoghq.reggie.runtime.MatchResult last;
        ReggieM(com.datadoghq.reggie.runtime.ReggieMatcher rm) { this.rm = rm; }
        @Override public void reset(CharSequence cs) { s = cs.toString(); from = 0; last = null; }
        @Override public boolean find() {
            if (from > s.length()) return false;
            com.datadoghq.reggie.runtime.MatchResult mr = rm.findMatchFrom(s, from);
            if (mr == null) { from = s.length() + 1; return false; }
            last = mr;
            from = mr.end() > mr.start() ? mr.end() : mr.end() + 1;
            return true;
        }
        @Override public int start() { return last.start(); }
        @Override public int end() { return last.end(); }
        @Override public int start(int g) { return last.start(g); }
        @Override public int groupCount() { return last.groupCount(); }
    }

    static final Engine REGGIE = new Engine() {
        @Override public String name() { return "reggie"; }
        @Override public Object compile(String regex, boolean ci, boolean uni) {
            return com.datadoghq.reggie.Reggie.compile(regex,
                    ci ? com.datadoghq.reggie.ReggieFlags.CASE_INSENSITIVE : 0);
        }
        @Override public M matcher(Object p, CharSequence cs) {
            ReggieM m = new ReggieM((com.datadoghq.reggie.runtime.ReggieMatcher) p);
            m.reset(cs);
            return m;
        }
    };

    static Engine tdfa(RegexEngineFactory factory, String name) {
        return new Engine() {
            @Override public String name() { return name; }
            @Override public Object compile(String regex, boolean ci, boolean uni) {
                int f = (ci ? io.github.jemmix.tdfa.Pattern.CASE_INSENSITIVE : 0)
                        | (uni ? io.github.jemmix.tdfa.Pattern.UNICODE_CHARACTER_CLASS : 0);
                return io.github.jemmix.tdfa.Pattern.compile(regex, f, factory);
            }
            @Override public M matcher(Object p, CharSequence cs) {
                io.github.jemmix.tdfa.core.Matcher m =
                        ((io.github.jemmix.tdfa.Pattern) p).matcher(cs);
                return new ReflectFreeAdapter(m::reset, m::find, m::start, m::end, m::start, m::groupCount);
            }
        };
    }

    static final Engine[] ENGINES = { JUR, RE2J, REGGIE, tdfa(TdfaRunner::new, "vm"), tdfa(null, "asm") };

    // ===== rebar models, engine-agnostic (ports of RebarScenarioParityTest) =====

    static final Set<String> SUPPORTED_MODELS =
            Set.of("count", "count-spans", "count-captures", "grep", "compile", "grep-captures");

    static long runModel(String model, M m, String hs) {
        switch (model) {
            case "count":
            case "compile": {
                long n = 0;
                m.reset(hs);
                while (m.find()) n++;
                return n;
            }
            case "count-spans": {
                long sum = 0;
                m.reset(hs);
                while (m.find()) sum += m.end() - m.start();
                return sum;
            }
            case "count-captures": {
                long n = 0;
                m.reset(hs);
                while (m.find()) {
                    for (int g = 0; g <= m.groupCount(); g++) {
                        if (m.start(g) >= 0) n++;
                    }
                }
                return n;
            }
            case "grep": {
                long matched = 0;
                int lineStart = 0;
                for (int i = 0; i <= hs.length(); i++) {
                    if (i == hs.length() || hs.charAt(i) == '\n') {
                        int lineEnd = i;
                        if (lineEnd > lineStart && hs.charAt(lineEnd - 1) == '\r') lineEnd--;
                        m.reset(hs.substring(lineStart, lineEnd));
                        if (m.find()) matched++;
                        lineStart = i + 1;
                    }
                }
                return matched;
            }
            case "grep-captures": {
                long n = 0;
                int lineStart = 0;
                for (int i = 0; i <= hs.length(); i++) {
                    if (i == hs.length() || hs.charAt(i) == '\n') {
                        int lineEnd = i;
                        if (lineEnd > lineStart && hs.charAt(lineEnd - 1) == '\r') lineEnd--;
                        m.reset(hs.substring(lineStart, lineEnd));
                        while (m.find()) {
                            for (int g = 0; g <= m.groupCount(); g++) {
                                if (m.start(g) >= 0) n++;
                            }
                        }
                        lineStart = i + 1;
                    }
                }
                return n;
            }
            default: throw new IllegalStateException("unsupported model: " + model);
        }
    }

    // ===== scenario scope (mirrors RebarScenarioParityTest filters) =====

    static boolean enginesIncludeJava(Scenario s) {
        return s.engines().stream().anyMatch(e -> e.startsWith("java/"));
    }

    static long haystackByteSize(Scenario s, Path dir) {
        long base, repeat, extra = 0;
        if (s.haystackSpec() instanceof Scenario.HaystackSpec.Inline i) {
            base = i.contents().length();
            repeat = i.repeat() == null ? 1 : i.repeat();
            if (i.prepend() != null) extra += i.prepend().length();
            if (i.append() != null) extra += i.append().length();
        } else if (s.haystackSpec() instanceof Scenario.HaystackSpec.FromPath p) {
            try {
                base = Files.size(dir.resolve("haystacks").resolve(p.path()));
            } catch (Exception e) {
                return -1;
            }
            repeat = p.repeat() == null ? 1 : p.repeat();
            if (p.prepend() != null) extra += p.prepend().length();
            if (p.append() != null) extra += p.append().length();
        } else {
            return -1;
        }
        try {
            return Math.multiplyExact(base, repeat) + extra;
        } catch (ArithmeticException overflow) {
            return Long.MAX_VALUE;
        }
    }

    /**
     * AST-level bomb detector, ported verbatim from RebarScenarioParityTest
     * (proven conservative there). Skips the 4 known COMPILE_TIMEOUT shapes
     * before they burn the wall budget on DFA state explosion.
     */
    static boolean exceedsCompileBudget(String regex) {
        Ast ast;
        try {
            ast = Parser.parse(regex);
        } catch (RuntimeException e) {
            return false;
        }
        return scanForBomb(ast, regex.length());
    }

    private static boolean scanForBomb(Ast ast, int regexLen) {
        if (ast instanceof Ast.Repeat r) {
            boolean variable = r.max != Integer.MAX_VALUE && r.max > r.min;
            if (variable) {
                int reps = r.max - r.min + 1;
                if (containsWideUnboundedRepeat(r.body)) return true;
                if (reps >= 50 && containsWideClass(r.body)) return true;
            }
            return scanForBomb(r.body, regexLen);
        }
        if (ast instanceof Ast.Alt a) {
            if (regexLen > 2_000 && a.children.size() > 50) {
                for (Ast child : a.children) {
                    if (!isPlainLiteral(child)) return true;
                }
            }
            for (Ast child : a.children) {
                if (scanForBomb(child, regexLen)) return true;
            }
            return false;
        }
        if (ast instanceof Ast.Concat c) {
            for (Ast child : c.children) {
                if (scanForBomb(child, regexLen)) return true;
            }
            return false;
        }
        return false;
    }

    private static boolean containsWideUnboundedRepeat(Ast ast) {
        if (ast instanceof Ast.Repeat r) {
            if (r.max == Integer.MAX_VALUE && containsWideClass(r.body)) return true;
            return containsWideUnboundedRepeat(r.body);
        }
        if (ast instanceof Ast.Concat c) {
            for (Ast child : c.children) if (containsWideUnboundedRepeat(child)) return true;
            return false;
        }
        if (ast instanceof Ast.Alt a) {
            for (Ast child : a.children) if (containsWideUnboundedRepeat(child)) return true;
            return false;
        }
        return false;
    }

    private static boolean containsWideClass(Ast ast) {
        if (ast instanceof CharClass cc) return classWidth(cc) > 10_000;
        if (ast instanceof Ast.Repeat r) return containsWideClass(r.body);
        if (ast instanceof Ast.Concat c) {
            for (Ast child : c.children) if (containsWideClass(child)) return true;
            return false;
        }
        if (ast instanceof Ast.Alt a) {
            for (Ast child : a.children) if (containsWideClass(child)) return true;
            return false;
        }
        return false;
    }

    private static boolean isPlainLiteral(Ast ast) {
        if (ast instanceof Ast.Symbol) return true;
        if (ast instanceof Ast.Concat c) {
            for (Ast child : c.children) {
                if (!(child instanceof Ast.Symbol)) return false;
            }
            return true;
        }
        return false;
    }

    private static long classWidth(CharClass cc) {
        long w = 0;
        for (int i = 0; i + 1 < cc.ranges.length; i += 2) {
            w += (long) cc.ranges[i + 1] - cc.ranges[i] + 1;
            if (w > 100_000) return 100_001;
        }
        return w;
    }

    // ===== harness =====

    record Mode(String name, int maxChars, int warmups, int passes,
                long compileBudgetMs, long passBudgetMs, long deadlineMs) {}

    static final Mode FAST = new Mode("fast", 2_000_000, 1, 3, 20_000, 5_000, 9 * 60_000);
    static final Mode ACCURATE = new Mode("accurate", 200_000_000, 2, 5, 300_000, 60_000,
            12L * 60 * 60_000);

    static final class Cell {
        Object pattern;                 // compiled handle from the compile phase
        long compileMs = -1;
        String compileStatus = "";      // "" ok | "ERR" | "cTO"
        String compileNote = "";
        long minNs = Long.MAX_VALUE;
        long count = Long.MIN_VALUE;
        boolean countDiverges;
        String status = "";             // "" ok | "TO" | "ERR"
        String note = "";
        boolean ok() { return status.isEmpty() && minNs != Long.MAX_VALUE; }
        boolean compiled() { return compileStatus.isEmpty() && compileMs >= 0; }
    }

    record Row(Scenario s, double mb, Cell[] cells) {}

    public static void main(String[] args) throws Exception {
        Locale.setDefault(Locale.ROOT); // review tables must not use German decimal commas
        String modeArg = "fast";
        String dirArg = null;
        String filter = null;
        Integer passesOverride = null;
        Integer maxCharsOverride = null;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--dir" -> dirArg = args[++i];
                case "--filter" -> filter = args[++i];
                case "--passes" -> passesOverride = Integer.parseInt(args[++i]);
                case "--max-chars" -> maxCharsOverride = Integer.parseInt(args[++i]);
                default -> modeArg = args[i];
            }
        }
        if (dirArg == null) {
            System.err.println("usage: RebarBench [fast|accurate] --dir <benchmarks> [--filter s] [--passes n] [--max-chars n]");
            System.exit(2);
        }
        Mode mode = modeArg.equals("accurate") ? ACCURATE : FAST;
        if (passesOverride != null) mode = new Mode(mode.name(), mode.maxChars(), mode.warmups(),
                passesOverride, mode.compileBudgetMs(), mode.passBudgetMs(), mode.deadlineMs());
        if (maxCharsOverride != null) mode = new Mode(mode.name(), maxCharsOverride, mode.warmups(),
                mode.passes(), mode.compileBudgetMs(), mode.passBudgetMs(), mode.deadlineMs());

        Path dir = Paths.get(dirArg);
        List<Scenario> all = new ScenarioLoader(dir).loadAll();
        List<Scenario> scoped = new ArrayList<>();
        int skippedScope = 0, skippedBomb = 0;
        for (Scenario s : all) {
            if (filter != null && !s.fullName().contains(filter)) continue;
            if (!enginesIncludeJava(s) || !SUPPORTED_MODELS.contains(s.model())
                    || s.expectedCount() == Long.MIN_VALUE
                    || s.regex() == null || s.regex().length() > 2_000_000
                    || haystackByteSize(s, dir) < 0 || haystackByteSize(s, dir) > 80_000_000) {
                skippedScope++;
                continue;
            }
            if (exceedsCompileBudget(s.regex())) { skippedBomb++; continue; }
            scoped.add(s);
        }

        long startWall = System.nanoTime();
        List<Row> rows = new ArrayList<>();
        boolean deadlineHit = false;

        for (Scenario s : scoped) {
            if (mode.deadlineMs() > 0
                    && (System.nanoTime() - startWall) / 1_000_000 > mode.deadlineMs()) {
                deadlineHit = true;
                break;
            }
            System.err.printf("  %-55s /%s/%n", s.fullName(),
                    s.regex().length() > 60 ? s.regex().substring(0, 60) + "..." : s.regex());
            String hs0 = s.resolveHaystack(dir);
            String hs = hs0.length() > mode.maxChars() ? hs0.substring(0, mode.maxChars()) : hs0;
            double mb = hs.length() / 1e6;
            Cell[] cells = new Cell[ENGINES.length];
            for (int e = 0; e < ENGINES.length; e++) cells[e] = new Cell();

            // compile (budgeted)
            for (int e = 0; e < ENGINES.length; e++) {
                Engine eng = ENGINES[e];
                final String regex = s.regex();
                final boolean ci = s.caseInsensitive(), uni = s.unicode();
                long t0 = System.nanoTime();
                try {
                    Object p = withTimeout(mode.compileBudgetMs(),
                            () -> eng.compile(regex, ci, uni));
                    cells[e].pattern = p;
                    cells[e].compileMs = (System.nanoTime() - t0) / 1_000_000;
                } catch (TimeoutException te) {
                    cells[e].compileStatus = "cTO";
                } catch (Throwable t) {
                    cells[e].compileStatus = "ERR";
                    cells[e].compileNote = t.getClass().getSimpleName();
                }
            }

            // warmup + count (one full model run per engine)
            for (int e = 0; e < ENGINES.length; e++) {
                if (!cells[e].compiled()) continue;
                try {
                    M m = ENGINES[e].matcher(cells[e].pattern, hs);
                    for (int w = 0; w < mode.warmups(); w++) {
                        cells[e].count = runModel(s.model(), m, hs);
                    }
                    if (mode.warmups() == 0) cells[e].count = runModel(s.model(), m, hs);
                } catch (Throwable t) {
                    cells[e].status = "ERR";
                    cells[e].note = t.getClass().getSimpleName();
                }
            }

            // count cross-check: reference = jur, fallback re2j, fallback vm
            long ref = Long.MIN_VALUE;
            if (cells[0].count != Long.MIN_VALUE) ref = cells[0].count;
            else if (cells[1].count != Long.MIN_VALUE) ref = cells[1].count;
            else if (cells[3].count != Long.MIN_VALUE) ref = cells[3].count;
            if (ref != Long.MIN_VALUE) {
                for (Cell c : cells) {
                    if (c.count != Long.MIN_VALUE && c.count != ref) c.countDiverges = true;
                }
            }

            // measured passes, interleaved engine-round-robin
            for (int p = 0; p < mode.passes(); p++) {
                for (int e = 0; e < ENGINES.length; e++) {
                    Cell c = cells[e];
                    if (!c.compiled() || "ERR".equals(c.status) || "TO".equals(c.status)) continue;
                    try {
                        M m = ENGINES[e].matcher(cells[e].pattern, hs);
                        long t0 = System.nanoTime();
                        runModel(s.model(), m, hs);
                        long ns = System.nanoTime() - t0;
                        if (ns / 1_000_000 > mode.passBudgetMs()) {
                            c.status = "TO";
                        } else if (ns < c.minNs) {
                            c.minNs = ns;
                        }
                    } catch (Throwable t) {
                        c.status = "ERR";
                        c.note = t.getClass().getSimpleName();
                    }
                }
            }
            rows.add(new Row(s, mb, cells));
        }

        printTables(mode, rows, scoped.size(), skippedScope, skippedBomb, deadlineHit,
                (System.nanoTime() - startWall) / 1_000_000);
    }

    private static <T> T withTimeout(long timeoutMs, Callable<T> task) throws Exception {
        var future = new FutureTask<T>(task);
        Thread.startVirtualThread(future);
        try {
            return future.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw e;
        }
    }

    // ===== output =====

    private static void printTables(Mode mode, List<Row> rows, int scoped, int skippedScope,
                                    int skippedBomb, boolean deadlineHit, long wallMs) {
        StringBuilder out = new StringBuilder();
        out.append(System.lineSeparator());
        out.append("═══ rebar corpus — 5-engine bench — mode=").append(mode.name())
                .append(" ═══").append(System.lineSeparator());
        out.append("scan = ms per MB of haystack (min of ").append(mode.passes())
                .append(" passes after ").append(mode.warmups())
                .append(" warmup; haystack cap ").append(mode.maxChars()).append(" chars)")
                .append(System.lineSeparator());
        out.append("* count diverges from java.util.regex on this input; cTO compile>budget;");
        out.append(" TO pass>budget; ERR exception").append(System.lineSeparator());
        out.append("goal: beat re2j decisively, parity with jur; reggie = reference")
                .append(System.lineSeparator());
        out.append("NOTE: fast mode is triage signal (JIT-cold, min-of-").append(mode.passes())
                .append("); isolated steady-state probes are ground truth for")
                .append(System.lineSeparator());
        out.append("single-scenario claims; sub-ms scenarios are noise-dominated. Use accurate mode for decisions.")
                .append(System.lineSeparator());
        out.append(String.format(Locale.ROOT, "scenarios run=%d (scoped=%d, out-of-scope=%d, bomb-skipped=%d)"
                        + "  wall=%.1fs%s%n", rows.size(), scoped, skippedScope, skippedBomb,
                wallMs / 1000.0, deadlineHit ? "  [DEADLINE HIT — tail skipped]" : ""));

        // ---- scan table ----
        out.append(System.lineSeparator());
        out.append("── SCAN ms/MB (lower is better) ──────────────────────────────────────────────")
                .append(System.lineSeparator());
        out.append(String.format("%-52s %9s %9s %9s %9s %9s%n",
                "scenario", "jur", "re2j", "reggie", "vm", "asm"));
        for (Row r : rows) {
            out.append(String.format("%-52s", abbrev(r.s().fullName() + " [" + r.s().model() + "]", 52)));
            for (Cell c : r.cells()) out.append(cellScan(c, r.mb()));
            out.append(System.lineSeparator());
        }

        // ---- compile table ----
        out.append(System.lineSeparator());
        out.append("── COMPILE ms ────────────────────────────────────────────────────────────────")
                .append(System.lineSeparator());
        out.append(String.format("%-52s %9s %9s %9s %9s %9s%n",
                "scenario", "jur", "re2j", "reggie", "vm", "asm"));
        for (Row r : rows) {
            out.append(String.format("%-52s", abbrev(r.s().fullName(), 52)));
            for (Cell c : r.cells()) out.append(cellCompile(c));
            out.append(System.lineSeparator());
        }

        // ---- summary ----
        out.append(System.lineSeparator());
        out.append("── SUMMARY ───────────────────────────────────────────────────────────────────")
                .append(System.lineSeparator());
        int iJur = 0, iRe2j = 1, iVm = 3, iAsm = 4;
        out.append(String.format("geomean scan ratio vs re2j: vm=%.2fx  asm=%.2fx   (n=%d, n=%d)%n",
                geomean(rows, iVm, iRe2j), geomean(rows, iAsm, iRe2j),
                nCommon(rows, iVm, iRe2j), nCommon(rows, iAsm, iRe2j)));
        out.append(String.format("geomean scan ratio vs jur : vm=%.2fx  asm=%.2fx   (n=%d, n=%d)%n",
                geomean(rows, iVm, iJur), geomean(rows, iAsm, iJur),
                nCommon(rows, iVm, iJur), nCommon(rows, iAsm, iJur)));
        out.append(worst(rows, "worst 10 vm  vs re2j", iVm, iRe2j));
        out.append(worst(rows, "worst 10 asm vs re2j", iAsm, iRe2j));
        out.append(worst(rows, "worst 10 vm  vs jur", iVm, iJur));
        out.append(worst(rows, "worst 10 asm vs jur", iAsm, iJur));
        System.out.println(out);
    }

    private static String cellScan(Cell c, double mb) {
        if (!c.compiled()) return String.format(" %8s", c.compileStatus);
        if ("ERR".equals(c.status)) return String.format(" %8s", "ERR:" + c.note);
        if ("TO".equals(c.status)) {
            if (c.minNs != Long.MAX_VALUE && mb > 0) {
                return String.format(" %8s", String.format("~%.1f", c.minNs / 1e6 / mb));
            }
            return String.format(" %8s", ">TO");
        }
        if (c.minNs == Long.MAX_VALUE) return String.format(" %8s", "--");
        double msPerMb = mb > 0 ? c.minNs / 1e6 / mb : c.minNs / 1e6;
        return String.format(" %8s", String.format("%.2f%s", msPerMb, c.countDiverges ? "*" : ""));
    }

    private static String cellCompile(Cell c) {
        if (!c.compiled()) return String.format(" %8s", c.compileStatus);
        return String.format(" %8s", c.compileMs + (c.compileNote.isEmpty() ? "" : "!" + c.compileNote));
    }

    private static double geomean(List<Row> rows, int a, int b) {
        double logSum = 0;
        int n = 0;
        for (Row r : rows) {
            Cell ca = r.cells()[a], cb = r.cells()[b];
            if (ca.ok() && cb.ok()) {
                logSum += Math.log((double) ca.minNs / cb.minNs);
                n++;
            }
        }
        return n == 0 ? Double.NaN : Math.exp(logSum / n);
    }

    private static int nCommon(List<Row> rows, int a, int b) {
        int n = 0;
        for (Row r : rows) if (r.cells()[a].ok() && r.cells()[b].ok()) n++;
        return n;
    }

    private static String worst(List<Row> rows, String title, int a, int b) {
        record Ratio(String name, double ratio, long aNs, long bNs) {}
        List<Ratio> list = new ArrayList<>();
        for (Row r : rows) {
            Cell ca = r.cells()[a], cb = r.cells()[b];
            if (ca.ok() && cb.ok() && cb.minNs > 0) {
                list.add(new Ratio(r.s().fullName(), (double) ca.minNs / cb.minNs, ca.minNs, cb.minNs));
            }
        }
        list.sort(java.util.Comparator.comparingDouble(Ratio::ratio).reversed());
        StringBuilder sb = new StringBuilder(title + " (ours slower = ratio > 1):" + System.lineSeparator());
        for (int i = 0; i < Math.min(10, list.size()); i++) {
            Ratio x = list.get(i);
            sb.append(String.format(Locale.ROOT, "  %6.2fx  %-50s  ours=%9d ns  theirs=%9d ns%n",
                    x.ratio(), abbrev(x.name(), 50), x.aNs(), x.bNs()));
        }
        return sb.toString();
    }

    private static String abbrev(String s, int max) {
        String oneLine = s.replace('\n', ' ').replace('\r', ' ');
        return oneLine.length() <= max ? oneLine : oneLine.substring(0, max - 3) + "...";
    }
}
