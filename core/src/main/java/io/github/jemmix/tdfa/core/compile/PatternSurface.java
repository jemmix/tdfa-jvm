package io.github.jemmix.tdfa.core.compile;

import io.github.jemmix.tdfa.core.report.CompileObserver;
import io.github.jemmix.tdfa.core.tnfa.Semantics;
import io.github.jemmix.tdfa.core.unicode.UnicodeDataProvider;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
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

    // The JUR-compat axis bits: a set bit selects the RE2-lineage
    // side of that axis (see Semantics). The jur facade exposed them (and
    // their OR) as opt-out flags before the two-surface split; post-split
    // they are machinery-internal vocabulary — no surface accepts them,
    // and the default lane of each surface (or an explicit
    // CompileOptions.semantics) is the only public way to select axes.

    /** Axis bit: {@code .}/{@code ^}/{@code $} recognize only {@code \n}. */
    public static final int UNIX_LINES = 256;

    /** Axis bit: bare case-insensitivity folds the full Unicode universe. */
    public static final int UNICODE_CASE = 512;

    /** Axis bit: codepoint discipline (scans skip surrogate-pair interiors). */
    public static final int CODEPOINT_BOUNDARIES = 1024;

    /** Axis bit: a multiline {@code ^} may match the empty last line. */
    public static final int EMPTY_LAST_LINE = 2048;

    /** Axis bit: {@code $} matches only at end of input (exactly {@code \z}). */
    public static final int END_OF_TEXT_ONLY = 4096;

    /** Axis bit: zero-width final loop iterations report their group spans. */
    public static final int EMPTY_ITERATION_SPANS = 8192;

    /** Axis bit: {@code (?U)} means ungreedy (the PCRE/RE2 reading). */
    public static final int UNGREEDY_U = 16384;

    /** Axis bit: the ASCII {@code \s}/{@code \S} table excludes U+000B (re2j's). */
    public static final int SPACE_RE2 = 32768;

    /** The axis bits' OR — the machinery-side spelling of the
     *  pre-split {@code RE2_COMPAT} preset mask (the original seven; the
     *  {@code SPACE_RE2} axis joined after the split, found by the WBS
     *  item-7 parity-suite widening). */
    public static final int RE2_AXES = UNIX_LINES | UNICODE_CASE | CODEPOINT_BOUNDARIES | EMPTY_LAST_LINE
        | END_OF_TEXT_ONLY | EMPTY_ITERATION_SPANS | UNGREEDY_U | SPACE_RE2;

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

    private PatternSurface(String id, String triggerClass, String patternInterface, Semantics defaultSemantics,
        int validFlags, String flagsMessage, EngineBackend backend, UnaryOperator<TDFAPattern> noCodegenWrapper) {
        this.id = id;
        this.triggerClass = triggerClass;
        this.patternInterface = patternInterface;
        this.defaultSemantics = defaultSemantics;
        this.validFlags = validFlags;
        this.flagsMessage = flagsMessage;
        this.backend = backend;
        this.noCodegenWrapper = noCodegenWrapper;
    }

    /**
     * Register a surface. Called exactly once per surface, from the
     * owning module's static initialization (that class is the
     * {@code triggerClass} — it must be loadable by name from a
     * deserializing process).
     */
    public static PatternSurface register(String id, String triggerClass, String patternInterface,
        Semantics defaultSemantics, int validFlags, String flagsMessage, EngineBackend backend,
        UnaryOperator<TDFAPattern> noCodegenWrapper) {
        PatternSurface s = new PatternSurface(id, triggerClass, patternInterface, defaultSemantics, validFlags,
            flagsMessage, backend, noCodegenWrapper);
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

    @Override
    public String toString() {
        return "PatternSurface[" + id + "]";
    }
}
