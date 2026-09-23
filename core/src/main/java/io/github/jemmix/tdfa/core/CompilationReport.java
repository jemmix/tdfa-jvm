package io.github.jemmix.tdfa.core;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * A collecting {@link CompileObserver}: accumulates per-stage timings,
 * details, and notes into a queryable snapshot — the pull counterpart of the
 * push hook.
 *
 * <pre>
 *   CompilationReport r = new CompilationReport();
 *   Pattern p = Pattern.compile(regex, CompileOptions.of().observer(r));
 *   r.detail(CompileObserver.Stage.DETERMINIZE);   // DFA state count
 *   r.nanos(CompileObserver.Stage.REGOPT);         // register-opt time
 *   r.notes();                                      // decisions taken
 * </pre>
 *
 * <p>Not thread-safe; attach one per compilation.
 */
public final class CompilationReport implements CompileObserver {

    private final Map<CompileObserver.Stage, Long> nanos = new LinkedHashMap<>();
    private final Map<CompileObserver.Stage, Integer> details = new LinkedHashMap<>();
    private final Map<String, String> notes = new LinkedHashMap<>();

    @Override
    public void stage(CompileObserver.Stage stage, long durationNanos, int detail) {
        nanos.merge(stage, durationNanos, Long::sum);
        details.merge(stage, detail, Integer::sum);
    }

    @Override
    public void note(String key, String value) {
        notes.put(key, value);
    }

    /** Total wall-clock nanoseconds spent in {@code stage} (summed across the compile's artifacts). */
    public long nanos(CompileObserver.Stage stage) {
        return nanos.getOrDefault(stage, 0L);
    }

    /** Stage detail (per-stage semantics, summed if the stage fired more than once). */
    public int detail(CompileObserver.Stage stage) {
        return details.getOrDefault(stage, 0);
    }

    /** Unmodifiable decision/warning notes in arrival order. */
    public Map<String, String> notes() {
        return Collections.unmodifiableMap(notes);
    }

    /** Sum of all recorded stage timings. */
    public long totalNanos() {
        return nanos.values().stream().mapToLong(Long::longValue).sum();
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("CompilationReport{");
        boolean first = true;
        for (CompileObserver.Stage s : CompileObserver.Stage.values()) {
            if (nanos.containsKey(s)) {
                if (!first) {
                    sb.append(", ");
                }
                sb.append(s.name().toLowerCase(Locale.ROOT)).append("=").append(nanos.get(s)).append("ns/").append(details.get(s));
                first = false;
            }
        }
        if (!notes.isEmpty()) {
            sb.append(", notes=").append(notes);
        }
        return sb.append('}').toString();
    }

    /** Value equality over the accumulated snapshot (two reports that observed
     *  the same compile compare equal). The instance itself stays mutable. */
    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof CompilationReport)) {
            return false;
        }
        CompilationReport r = (CompilationReport) o;
        return nanos.equals(r.nanos) && details.equals(r.details) && notes.equals(r.notes);
    }

    @Override
    public int hashCode() {
        return 31 * (31 * nanos.hashCode() + details.hashCode()) + notes.hashCode();
    }
}
