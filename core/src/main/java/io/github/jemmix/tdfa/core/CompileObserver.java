package io.github.jemmix.tdfa.core;

/**
 * Compilation transparency hook: receives stage events while a pattern
 * compiles. Attach via {@link CompileOptions#observer(CompileObserver)}.
 *
 * <p>Stages fire in pipeline order, each with its wall-clock duration and a
 * stage-specific detail value:
 * <ul>
 *   <li>{@link Stage#PARSE} — detail: tag count;</li>
 *   <li>{@link Stage#TNFA} — detail: NFA state count;</li>
 *   <li>{@link Stage#DETERMINIZE} — detail: DFA state count;</li>
 *   <li>{@link Stage#MINIMIZE} — detail: DFA state count after minimization;</li>
 *   <li>{@link Stage#REGOPT} — detail: register count after optimization;</li>
 *   <li>{@link Stage#ENGINE} — detail: 0 (engine instantiation; code-generated
 *       engines carry their emission time here).</li>
 * </ul>
 *
 * <p>Decisions and warnings arrive as {@link #note(String, String) notes}
 * (e.g. {@code engine=generated-inline}, {@code minimize=skipped},
 * {@code fixed-tags=dropped 3/8}). Observers are invoked on the compiling
 * thread; implementations should be fast and side-effect-only. The default
 * implementation is a no-op — compile with no observer attached pays a handful
 * of virtual calls, nothing else.
 *
 * <p><b>Stage multiplicity.</b> A compile runs the pipeline once for the
 * whole-match artifact (cut-free determinization) and — only when the pike
 * cut matters for find() or the whole attempt is over budget — once more for
 * the pruned find artifact; stages fire once per artifact, in that order.
 * The single-artifact case (the vast majority) fires each stage exactly
 * once, all inside {@code compile()} — nothing compiles lazily anymore,
 * except the documented over-budget bomb corner ({@code whole} note), where
 * matches() compiles the anchored engine on first use.
 */
public interface CompileObserver {

    /** Pipeline stages, in firing order. */
    enum Stage { PARSE, TNFA, DETERMINIZE, MINIMIZE, REGOPT, ENGINE }

    /** A stage completed: {@code nanos} wall-clock, {@code detail} per-stage value (see class doc). */
    default void stage(Stage stage, long nanos, int detail) { }

    /** A compile-time decision or warning (key=value shaped keys, e.g. {@code engine}). */
    default void note(String key, String value) { }

    /** A no-op observer (the default when none is attached). */
    CompileObserver NONE = new CompileObserver() { };
}
