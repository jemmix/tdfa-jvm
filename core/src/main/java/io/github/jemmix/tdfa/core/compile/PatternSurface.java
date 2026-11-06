package io.github.jemmix.tdfa.core.compile;

import io.github.jemmix.tdfa.core.report.CompileObserver;
import io.github.jemmix.tdfa.core.tnfa.Semantics;
import io.github.jemmix.tdfa.core.unicode.UnicodeDataProvider;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

/**
 * One {@code Pattern} surface: the reader a thin surface module (the jur
 * facade at the root, the {@code :re2j} module) bakes into its compiles —
 * the default {@link Semantics} lane, the flag bits that surface accepts,
 * the {@link EngineBackend} that serves it, and the not-generated carrier
 * for compiles that must not emit code. The machinery
 * ({@link PatternCompiler}) is surface-blind otherwise: same pipeline,
 * same budgets, same shells — the surface is the one argument the two
 * lanes differ by.
 *
 * <p><b>Registration.</b> A surface registers itself from its owning
 * module (a static initializer, exactly once per process); the registry
 * is how the surface-aware {@link SerialProxy} finds the right
 * recompiler on read — a serialized pattern names its surface, and the
 * proxy resolves the registration ({@link #resolve}), loading the
 * surface's owning class if this process has not compiled through it
 * yet. Serialization stability therefore rests on {@code id} +
 * {@code triggerClass} staying fixed once a surface ships.
 *
 * <p>Public-but-internal: machinery tier ({@code core.compile}),
 * referenced by the surfaces only — not shipped API.
 */
public final class PatternSurface {

    // ---- the shared flag-bit assignments ----
    // Every surface spells these constants publicly on its own Pattern
    // interface (with its own javadoc); the machinery decodes by these
    // values. Bit values are this library's own — names and semantics
    // mirror the JDK flags they snapped onto, numerics do not. Both
    // surfaces share the assignments, so the same int round-trips both.

    /** Case insensitive matching (folds the surface's default universe). */
    public static final int CASE_INSENSITIVE = 1;

    /** Dot ({@code .}) matches all characters, including newline. */
    public static final int DOTALL = 2;

    /** Multiline matching ({@code ^}/{@code $} at line boundaries). */
    public static final int MULTILINE = 4;

    /** Reject {@code \p{...}} / {@code \P{...}} at compile time. */
    public static final int DISABLE_UNICODE_GROUPS = 8;

    /** Leftmost-longest (POSIX) matching. */
    public static final int LONGEST_MATCH = 16;

    /** Unicode-aware predefined classes (the jur reading; jur surface only). */
    public static final int UNICODE_CHARACTER_CLASS = 32;

    /** Compile for find-shaped matching only (whole-input methods refuse). */
    public static final int FIND_ONLY = 64;

    /** Multi-valued tags (BT22 &sect;3.1) — per-iteration group spans. */
    public static final int MULTI_VALUED_TAGS = 128;

    private static final Map<String, PatternSurface> REGISTERED = new ConcurrentHashMap<>();

    private final String id;

    /** FQCN whose initialization registers this surface (the surface module's registration holder). */
    private final String triggerClass;

    /** Slashed internal name of the surface's {@code Pattern} interface (the emitted shells implement it). */
    private final String patternInterface;

    private final Semantics defaultSemantics;

    private final int validFlags;

    /** The IllegalArgumentException message for out-of-roster flag bits (names the surface's roster). */
    private final String flagsMessage;

    private final EngineBackend backend;

    /** Wraps a bare machinery instance into the surface's Pattern type (the no-codegen paths). */
    private final UnaryOperator<TDFAPattern> noCodegenWrapper;

    /** The Unicode source a provider-less compile through this surface
     *  resolves to — the surface's fold-universe default: the jur surface
     *  the property resolution (JDK tables, java.util.regex folds), the
     *  re2j surface the same tables under the built-in RE2-lineage fold
     *  reading (İ/ı inert — its oracle's orbits). Lazy: the supplier runs
     *  per compile, so provider selection (and its failure memo) stays
     *  on the normal path. */
    private final Supplier<UnicodeDataProvider> defaultProvider;

    private PatternSurface(String id, String triggerClass, String patternInterface, Semantics defaultSemantics,
        int validFlags, String flagsMessage, EngineBackend backend, UnaryOperator<TDFAPattern> noCodegenWrapper,
        Supplier<UnicodeDataProvider> defaultProvider) {
        this.id = id;
        this.triggerClass = triggerClass;
        this.patternInterface = patternInterface;
        this.defaultSemantics = defaultSemantics;
        this.validFlags = validFlags;
        this.flagsMessage = flagsMessage;
        this.backend = backend;
        this.noCodegenWrapper = noCodegenWrapper;
        this.defaultProvider = defaultProvider;
    }

    /**
     * Register a surface. Called exactly once per surface, from the
     * owning module's static initialization (that class is the
     * {@code triggerClass} — it must be loadable by name from a
     * deserializing process).
     */
    public static PatternSurface register(String id, String triggerClass, String patternInterface,
        Semantics defaultSemantics, int validFlags, String flagsMessage, EngineBackend backend,
        UnaryOperator<TDFAPattern> noCodegenWrapper, Supplier<UnicodeDataProvider> defaultProvider) {
        PatternSurface s = new PatternSurface(id, triggerClass, patternInterface, defaultSemantics, validFlags,
            flagsMessage, backend, noCodegenWrapper, defaultProvider);
        PatternSurface prev = REGISTERED.putIfAbsent(id, s);
        if (prev != null) {
            throw new IllegalStateException(
                "pattern surface '" + id + "' registered twice" + " (each surface module registers exactly once)");
        }
        return s;
    }

    /**
     * Resolve a registered surface by id — the serialization path. If
     * this process has not compiled through the surface yet, loading the
     * surface's trigger class performs the registration; a surface that
     * cannot be resolved fails loudly ({@link IllegalArgumentException})
     * — never a silent recompile in the wrong lane.
     */
    public static PatternSurface resolve(String id, String triggerClass) {
        PatternSurface s = REGISTERED.get(id);
        if (s == null && triggerClass != null) {
            try {
                Class.forName(triggerClass, true, loader());
            } catch (ClassNotFoundException e) {
                throw new IllegalArgumentException("serialized pattern surface '" + id
                    + "' is not on the classpath (its owning module failed to load: " + triggerClass + ")", e);
            }
            s = REGISTERED.get(id);
        }
        if (s == null) {
            throw new IllegalArgumentException("unknown pattern surface '" + id + "'");
        }
        return s;
    }

    private static ClassLoader loader() {
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        return cl != null ? cl : PatternSurface.class.getClassLoader();
    }

    /**
     * Test-only seam (eviction coverage for {@link #resolve}'s lazy
     * registration path): swap a surface's registration — a {@code null}
     * replacement evicts it — and return the previous registration so the
     * caller can restore process state. No production caller: the registry
     * is write-once per process, filled by the surfaces' own static
     * initialization; only a test needs the not-yet-compiled-through state
     * of a reading process.
     */
    static PatternSurface swapRegistrationForTest(String id, PatternSurface replacement) {
        return replacement == null ? REGISTERED.remove(id) : REGISTERED.put(id, replacement);
    }

    /**
     * Compile through this surface: the machinery compile with this
     * surface's lane/roster/backend baked in, then (only on the
     * no-codegen path, which comes back bare) wrap into the surface's
     * own Pattern carrier — every instance a surface hands out
     * implements its Pattern interface.
     */
    public TDFAPattern compile(String regex, int flags, RegexEngineFactory factory, UnicodeDataProvider provider,
        CompileObserver observer, Semantics explicitSemantics) {
        TDFAPattern p = PatternCompiler.compile(regex, flags, factory, provider, observer, explicitSemantics, this);
        return p.getClass() == TDFAPattern.class ? noCodegenWrapper.apply(p) : p;
    }

    /**
     * Recompile on deserialization (pattern + flags + pinned provider,
     * default engine tier) — the read side of the surface-aware proxy.
     */
    public TDFAPattern recompile(String regex, int flags, UnicodeDataProvider provider) {
        return compile(regex, flags, null, provider, null, null);
    }

    /** The stable serialization key (e.g. {@code "jur"}, {@code "re2j"}). */
    public String id() {
        return id;
    }

    /** The registration holder's FQCN (loads the surface on demand). */
    public String triggerClass() {
        return triggerClass;
    }

    /** The surface's {@code Pattern} interface, slashed internal name. */
    public String patternInterface() {
        return patternInterface;
    }

    /** The lane this surface compiles in when no explicit semantics is given. */
    public Semantics defaultSemantics() {
        return defaultSemantics;
    }

    /** The flag bits this surface accepts (the roster mask). */
    public int validFlags() {
        return validFlags;
    }

    /** The rejection message for out-of-roster flag bits. */
    public String flagsMessage() {
        return flagsMessage;
    }

    /** The engine backend this surface's compiles are served by. */
    public EngineBackend backend() {
        return backend;
    }

    /** The Unicode source a provider-less compile through this surface
     *  resolves to (the surface's fold-universe default). */
    public Supplier<UnicodeDataProvider> defaultProvider() {
        return defaultProvider;
    }

    @Override
    public String toString() {
        return "PatternSurface[" + id + "]";
    }
}
