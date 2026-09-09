package io.github.jemmix.tdfa;

import java.io.InvalidObjectException;
import java.io.ObjectStreamException;
import java.io.Serializable;

/**
 * Serialization proxy for Pattern implementations: serializes {@code pattern
 * + flags + provider identity} and recompiles (with the default engine) on
 * readResolve. This is what makes generated per-pattern Patterns — whose
 * classes live in a child classloader that will not exist in the reading
 * process — serializable without pinning generated classes.
 *
 * <p>Provider pinning round-trips (2026-09, review Phase C / P1 #17): a
 * pattern compiled against a pinned {@code UnicodeDataProvider} keeps that
 * provider across serialization — previously it silently recompiled with the
 * reader's default tables, the exact reproducibility bug the pinned-provider
 * API exists to prevent. The provider travels as its class NAME (providers
 * are not required to be Serializable) and is resolved on read per the
 * convention documented on {@code UnicodeDataProvider}: a static no-arg
 * {@code provider()} method returning the instance, else a public no-arg
 * constructor. A pattern compiled against the process default serializes
 * with a {@code null} provider class and recompiles against the reader's
 * default — the historical behavior.
 *
 * <p>Resolution failures throw {@link InvalidObjectException} — loud, never
 * a silent fallback to different Unicode tables.
 */
final class SerialProxy implements Serializable {

    private static final long serialVersionUID = 2L;

    private final String pattern;
    private final int flags;
    /** FQCN of the pinned UnicodeDataProvider, or {@code null} = process default. */
    private final String providerClass;

    SerialProxy(String pattern, int flags, String providerClass) {
        this.pattern = pattern;
        this.flags = flags;
        this.providerClass = providerClass;
    }

    private Object readResolve() throws ObjectStreamException {
        io.github.jemmix.tdfa.unicode.UnicodeDataProvider p = null;
        if (providerClass != null) {
            p = resolveProvider(providerClass);
        }
        return Pattern.compile(pattern, flags, null, p);
    }

    private static io.github.jemmix.tdfa.unicode.UnicodeDataProvider resolveProvider(
            String cls) throws ObjectStreamException {
        try {
            Class<?> c = Class.forName(cls);
            if (!io.github.jemmix.tdfa.unicode.UnicodeDataProvider.class.isAssignableFrom(c)) {
                throw new InvalidObjectException(
                        "serialized provider " + cls + " does not implement UnicodeDataProvider");
            }
            // Convention 1: static UnicodeDataProvider provider() (the shape
            // of the shipped pinned-table providers, which are singletons;
            // may be private — same module, opened for reflection).
            try {
                java.lang.reflect.Method m = c.getMethod("provider");
                if (io.github.jemmix.tdfa.unicode.UnicodeDataProvider.class.isAssignableFrom(m.getReturnType())
                        && java.lang.reflect.Modifier.isStatic(m.getModifiers())) {
                    m.setAccessible(true);
                    return (io.github.jemmix.tdfa.unicode.UnicodeDataProvider) m.invoke(null);
                }
            } catch (NoSuchMethodException expected) {
                // fall through to convention 2
            }
            // Convention 2: no-arg constructor (may be private for singletons).
            java.lang.reflect.Constructor<?> ctor = c.getDeclaredConstructor();
            ctor.setAccessible(true);
            return (io.github.jemmix.tdfa.unicode.UnicodeDataProvider) ctor.newInstance();
        } catch (InvalidObjectException e) {
            throw e;
        } catch (ReflectiveOperationException | RuntimeException e) {
            throw new InvalidObjectException(
                    "cannot resolve serialized Unicode provider " + cls
                            + " (needs a static provider() method or a public no-arg constructor;"
                            + " see UnicodeDataProvider's serialization convention): " + e);
        }
    }
}
