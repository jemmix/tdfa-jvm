package io.github.jemmix.tdfa.core.compile;

import io.github.jemmix.tdfa.core.dialect.ClassTable;
import io.github.jemmix.tdfa.core.dialect.EscapeAlphabet;
import io.github.jemmix.tdfa.core.dialect.FlagAction;
import io.github.jemmix.tdfa.core.dialect.FlagBindings;
import io.github.jemmix.tdfa.core.dialect.Terminators;
import io.github.jemmix.tdfa.core.tnfa.Semantics;
import io.github.jemmix.tdfa.core.unicode.UnicodeDataProvider;

import java.io.InvalidObjectException;
import java.io.ObjectStreamException;
import java.io.Serializable;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

/**
 * Serialization proxy for the machinery's own {@link TDFAPattern} level:
 * serializes {@code pattern + flags + policy + provider identity} and
 * recompiles through {@link PatternCompiler} on readResolve — pure
 * machinery data, no facade identity, no reflection. This is what makes
 * generated per-pattern Patterns — whose classes live in a child
 * classloader that will not exist in the reading process — serializable
 * without pinning generated classes. The facade {@code Pattern} types
 * serialize through their own facade-owned proxies (same shape, plus the
 * route back into the facade's compile funnel); this proxy serves the
 * core tier and any bare machinery pattern.
 *
 * <p><b>Wire form (v2):</b> {@code semanticsBits} — the v1 encoding,
 * whose four retired bits are spelled by the stock components — plus
 * the policy's component payloads ({@link Components}: terminator units
 * and pair, space-table ranges, escape-alphabet name, binding
 * letter&rarr;action pairs). A v2 stream always carries the payloads, so
 * a custom table or alphabet round-trips exactly; a v1 stream (payloads
 * absent) is translated by {@link Semantics#ofBits}. The
 * {@code serialVersionUID} bumped at v2: an older library meeting a new
 * stream fails loudly rather than silently recompiling in the stock
 * dialect the bits approximate to — the same doctrine as
 * {@code ofBits}' unknown-bit rejection.
 *
 * <p>Provider pinning round-trips: a pattern compiled against a pinned
 * {@link UnicodeDataProvider} keeps that provider across serialization —
 * silently recompiling with the reader's default tables would be exactly
 * the reproducibility bug the pinned-provider API exists to prevent. The
 * provider travels as its class NAME (providers
 * are not required to be Serializable) and is resolved on read per the
 * convention documented on {@code UnicodeDataProvider}: a static no-arg
 * {@code provider()} method returning the instance, else a public no-arg
 * constructor. A pattern compiled against the process default serializes
 * with a {@code null} provider class and recompiles against the reader's
 * default (the machinery level's default — the property resolution).
 *
 * <p>Resolution failures throw {@link InvalidObjectException} — loud, never
 * a silent fallback to different Unicode tables or a different policy.
 *
 * <p>Public-but-internal: machinery tier ({@code core.compile}) — not
 * shipped API.
 */
public final class SerialProxy implements Serializable {

    private static final long serialVersionUID = 6L;

    private final String pattern;

    private final int flags;

    /** The policy's v1 bit encoding (see the class doc). */
    private final int semanticsBits;

    /**
     * FQCN of the pinned UnicodeDataProvider, or {@code null} = process default.
     */
    private final String providerClass;

    /** The policy's component payloads, or {@code null} = a v1 stream. */
    private final Components components;

    SerialProxy(String pattern, int flags, int semanticsBits, String providerClass, Components components) {
        this.pattern = pattern;
        this.flags = flags;
        this.semanticsBits = semanticsBits;
        this.providerClass = providerClass;
        this.components = components;
    }

    /**
     * The component payloads of a policy — the one wire encoding shared
     * by every proxy tier (the core proxy here and both facades'): the
     * terminator set as sorted units plus its pair, the space table as
     * ranges, the escape alphabet as its enum name, the letter bindings
     * as parallel arrays. Always populated on the write side; values
     * are the plain data of the {@code core.dialect} types.
     */
    public static Components componentsOf(Semantics semantics) {
        return new Components(semantics.terminators().units(), semantics.terminators().pairLo(),
            semantics.terminators().pairHi(), semantics.spaceTable().ranges(), semantics.escapes().name(),
            bindingLetters(semantics.flagBindings()), bindingActions(semantics.flagBindings()));
    }

    /**
     * Restore a policy from the wire: v1 bits translated (the retired
     * bits spell the stock components), then the v2 payloads — when
     * present — overlaid on top. Garbage payloads (unknown enum name,
     * malformed set or table, broken binding pairs) fail as
     * {@link InvalidObjectException}, never a silent fallback.
     */
    public static Semantics semantics(int bits, Components components) throws ObjectStreamException {
        Semantics s = Semantics.ofBits(bits);
        if (components == null) {
            return s;
        }
        try {
            if (components.terminatorUnits != null) {
                Terminators terms = components.terminatorPairLo == 0 && components.terminatorPairHi == 0
                    ? Terminators.of(components.terminatorUnits)
                    : Terminators.ofPair(components.terminatorPairLo, components.terminatorPairHi,
                        components.terminatorUnits);
                s = s.terminators(terms);
            }
            if (components.spaceRanges != null) {
                s = s.spaceTable(ClassTable.ofRanges(components.spaceRanges));
            }
            if (components.escapesName != null) {
                s = s.escapes(EscapeAlphabet.valueOf(components.escapesName));
            }
            if (components.bindingLetters != null) {
                FlagBindings.Builder b = FlagBindings.builder();
                for (int i = 0; i < components.bindingLetters.length; i++) {
                    b.bind(components.bindingLetters[i], FlagAction.valueOf(components.bindingActions[i]));
                }
                s = s.flagBindings(b.build());
            }
            return s;
        } catch (IllegalArgumentException | NullPointerException | IndexOutOfBoundsException e) {
            throw new InvalidObjectException("cannot restore the serialized dialect policy: " + e);
        }
    }

    private static char[] bindingLetters(FlagBindings bindings) {
        char[] letters = new char[bindings.asMap().size()];
        int i = 0;
        for (char c : bindings.asMap().keySet()) {
            letters[i++] = c;
        }
        return letters;
    }

    private static String[] bindingActions(FlagBindings bindings) {
        String[] actions = new String[bindings.asMap().size()];
        int i = 0;
        for (FlagAction a : bindings.asMap().values()) {
            actions[i++] = a.name();
        }
        return actions;
    }

    /**
     * Resolve a serialized provider by class name, per the
     * {@link UnicodeDataProvider} serialization convention: a static
     * no-arg {@code provider()} method returning the instance (the shape
     * of the shipped pinned-table providers), else a no-arg constructor
     * (may be private for singletons). Shared by the facade-owned
     * serialization proxies — one convention, one resolver, machinery
     * tier. Failures throw {@link InvalidObjectException}, for use from
     * a proxy's {@code readResolve}.
     */
    public static UnicodeDataProvider resolveProvider(String cls) throws ObjectStreamException {
        try {
            Class<?> c = Class.forName(cls);
            if (!UnicodeDataProvider.class.isAssignableFrom(c)) {
                throw new InvalidObjectException(
                    "serialized provider " + cls + " does not implement UnicodeDataProvider");
            }
            // Convention 1: public static UnicodeDataProvider provider() (the
            // shape of the shipped pinned-table providers, which are
            // singletons). Found by scanning, not getMethod: probing with
            // getMethod would use NoSuchMethodException as control flow for
            // the absent case. Convention 2 covers non-public construction.
            boolean viaFactory = false;
            UnicodeDataProvider fromFactory = null;
            for (Method m : c.getMethods()) {
                if ("provider".equals(m.getName()) && m.getParameterCount() == 0
                    && UnicodeDataProvider.class.isAssignableFrom(m.getReturnType())
                    && Modifier.isStatic(m.getModifiers())) {
                    m.setAccessible(true);
                    fromFactory = (UnicodeDataProvider) m.invoke(null);
                    viaFactory = true;
                    break;
                }
            }
            if (viaFactory) {
                return fromFactory;
            }
            // Convention 2: no-arg constructor (may be private for singletons).
            Constructor<?> ctor = c.getDeclaredConstructor();
            ctor.setAccessible(true);
            return (UnicodeDataProvider) ctor.newInstance();
        } catch (InvalidObjectException e) {
            throw e;
        } catch (ReflectiveOperationException | RuntimeException e) {
            throw new InvalidObjectException("cannot resolve serialized Unicode provider " + cls
                + " (needs a static provider() method or a public no-arg constructor;"
                + " see UnicodeDataProvider's serialization convention): " + e);
        }
    }

    /**
     * Recompile through the machinery: the recorded policy, the default
     * engine tier (the read path does not depend on the bytecode backend
     * — a bare machinery pattern recompiles on the shared interpreter,
     * same matches, no generated classes to pin).
     */
    static TDFAPattern recompile(String pattern, int flags, Semantics semantics, UnicodeDataProvider provider)
        throws ObjectStreamException {
        return PatternCompiler.compile(pattern, flags, null, null, provider, null, null, semantics);
    }

    private Object readResolve() throws ObjectStreamException {
        UnicodeDataProvider p = null;
        if (providerClass != null) {
            p = resolveProvider(providerClass);
        }
        return recompile(pattern, flags, semantics(semanticsBits, components), p);
    }

    /**
     * The component payloads (see {@link #componentsOf}): plain data of
     * the {@code core.dialect} types, one wire encoding for every proxy
     * tier. Fields are {@code null} one by one only when a v1 stream is
     * being read (a v2 writer always populates them all).
     */
    public static final class Components implements Serializable {

        private static final long serialVersionUID = 1L;

        /** Sorted terminator units (pair halves included), or {@code null}. */
        public final int[] terminatorUnits;

        /** The pair's first unit, {@code 0} = none. */
        public final int terminatorPairLo;

        /** The pair's second unit, {@code 0} = none. */
        public final int terminatorPairHi;

        /** Space-table ranges (ascending lo/hi), or {@code null}. */
        public final int[] spaceRanges;

        /** {@link EscapeAlphabet} name, or {@code null}. */
        public final String escapesName;

        /** Bound letters, or {@code null} — parallel to {@link #bindingActions}. */
        public final char[] bindingLetters;

        /** {@link FlagAction} names, parallel to {@link #bindingLetters}. */
        public final String[] bindingActions;

        public Components(int[] terminatorUnits, int terminatorPairLo, int terminatorPairHi, int[] spaceRanges,
            String escapesName, char[] bindingLetters, String[] bindingActions) {
            this.terminatorUnits = terminatorUnits;
            this.terminatorPairLo = terminatorPairLo;
            this.terminatorPairHi = terminatorPairHi;
            this.spaceRanges = spaceRanges;
            this.escapesName = escapesName;
            this.bindingLetters = bindingLetters;
            this.bindingActions = bindingActions;
        }
    }
}
