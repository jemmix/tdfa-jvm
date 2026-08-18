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
 *   <li>{@link Stage#FALLBACK} — detail: fallback state count (BT22 §6.2);</li>
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
 * <p><b>Lazy stages.</b> The anchored whole-engine backing {@code matches()}
 * compiles on first use; its stages fire into the same observer at that
 * point, not during {@code compile()}.
 */
public interface CompileObserver {

    /** Pipeline stages, in firing order. */
    enum Stage { PARSE, TNFA, DETERMINIZE, MINIMIZE, REGOPT, FALLBACK, ENGINE }

    /** A stage completed: {@code nanos} wall-clock, {@code detail} per-stage value (see class doc). */
    default void stage(Stage stage, long nanos, int detail) { }

    /** A compile-time decision or warning (key=value shaped keys, e.g. {@code engine}). */
    default void note(String key, String value) { }

    /** A no-op observer (the default when none is attached). */
    CompileObserver NONE = new CompileObserver() { };
}
