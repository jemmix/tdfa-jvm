package io.github.jemmix.tdfa.core.compile;

import io.github.jemmix.tdfa.core.unicode.UnicodeDataProvider;

import java.io.InvalidObjectException;
import java.io.ObjectStreamException;
import java.io.Serializable;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

/**
 * Serialization proxy for {@code Pattern} implementations of every
 * facade: serializes {@code facade + pattern + flags + provider
 * identity} and recompiles on readResolve by reflecting the facade
 * interface's own {@code compile(String, int, RegexEngineFactory,
 * UnicodeDataProvider)} — the same static a caller hits, so the
 * facade's roster validation, default lane and provider default all
 * apply, and a serialized re2j-facade pattern stays re2j on read. This
 * is also what makes generated per-pattern Patterns — whose classes
 * live in a child classloader that will not exist in the reading
 * process — serializable without pinning generated classes.
 *
 * <p>The facade travels as its {@code Pattern} interface {@link Class}
 * (serializable by name): deserializing it loads the facade module if
 * the reading process has not compiled through it yet. Provider
 * pinning round-trips: a pattern compiled against a pinned
 * {@link UnicodeDataProvider} keeps that provider across serialization
 * — silently recompiling with the reader's default tables would be
 * exactly the reproducibility bug the pinned-provider API exists to
 * prevent. The provider travels as its class NAME (providers are not
 * required to be Serializable) and is resolved on read per the
 * convention documented on {@code UnicodeDataProvider}: a static no-arg
 * {@code provider()} method returning the instance, else a public
 * no-arg constructor. A pattern compiled against the process default
 * serializes with a {@code null} provider class and recompiles against
 * the reader's default.
 *
 * <p>Resolution failures throw {@link InvalidObjectException} — loud, never
 * a silent fallback to different Unicode tables or a different facade.
 *
 * <p>Public-but-internal: machinery tier ({@code core.compile}) — not
 * shipped API.
 */
public final class SerialProxy implements Serializable {

    private static final long serialVersionUID = 4L;

    private final Class<?> facade;

    private final String pattern;

    private final int flags;

    /**
     * FQCN of the pinned UnicodeDataProvider, or {@code null} = process default.
     */
    private final String providerClass;

    SerialProxy(Class<?> facade, String pattern, int flags, String providerClass) {
        this.facade = facade;
        this.pattern = pattern;
        this.flags = flags;
        this.providerClass = providerClass;
    }

    private static UnicodeDataProvider resolveProvider(String cls) throws ObjectStreamException {
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
     * Recompile through the facade: its own four-arg {@code compile}
     * static, reflected — the machinery cannot name either facade
     * module (the module DAG is root &rarr; core and re2j &rarr; core),
     * and the facade's entry is the one recompile path that keeps every
     * facade-local rule (roster, lane, provider default) in one place.
     * Default engine tier; the pinned provider, if any, rides along.
     */
    static TDFAPattern recompile(Class<?> facade, String pattern, int flags, UnicodeDataProvider provider)
        throws ObjectStreamException {
        try {
            Method compile = facade.getMethod("compile", String.class, int.class, RegexEngineFactory.class,
                UnicodeDataProvider.class);
            return (TDFAPattern) compile.invoke(null, pattern, flags, null, provider);
        } catch (InvocationTargetException e) {
            // The underlying compile already threw; surface it as itself —
            // a runtime exception (PatternSyntaxException, the budget
            // family) propagates raw, exactly like an in-process recompile.
            Throwable c = e.getCause();
            if (c instanceof RuntimeException) {
                throw (RuntimeException) c;
            }
            if (c instanceof Error) {
                throw (Error) c;
            }
            throw new InvalidObjectException("recompile through facade " + facade.getName() + " failed: " + c);
        } catch (ReflectiveOperationException e) {
            throw new InvalidObjectException("serialized facade " + facade.getName() + " does not declare"
                + " Pattern compile(String, int, RegexEngineFactory, UnicodeDataProvider): " + e);
        }
    }

    private Object readResolve() throws ObjectStreamException {
        UnicodeDataProvider p = null;
        if (providerClass != null) {
            p = resolveProvider(providerClass);
        }
        return recompile(facade, pattern, flags, p);
    }
}
