package io.github.jemmix.tdfa.core.unicode;

/**
 * Resolves the {@link UnicodeDataProvider} to use, based on the system
 * property {@link #PROPERTY_NAME}. Resolved exactly once and cached; if
 * resolution fails, the exception is re-thrown on every subsequent call
 * (no silent fallback to a default).
 *
 * <h2>Property values</h2>
 * <ul>
 *   <li>{@code "jdk"} (default if unset) — {@link JdkUnicodeDataProvider}</li>
 *   <li>Any other value — treated as the fully-qualified name of a class
 *       implementing {@link UnicodeDataProvider}, loaded reflectively</li>
 * </ul>
 *
 * <p>If the custom class can't be loaded, instantiated, or doesn't implement
 * the interface, an {@link IllegalStateException} is thrown (no silent
 * fallback).
 */
public final class UnicodeProviders {
    /** System property name used to select the {@link UnicodeDataProvider}. */
    public static final String PROPERTY_NAME = "io.github.jemmix.tdfa.unicode.provider";

    private static volatile UnicodeDataProvider cached;
    /** Failure memo: deliberately cached and re-thrown on every subsequent
     *  get() (see class doc — no silent fallback). Caching the Throwable is
     *  the point; it is never rethrown with a stale stack, it only wraps. */
    @SuppressWarnings("StaticAssignmentOfThrowable")
    private static volatile Throwable failure;

    private UnicodeProviders() {
    }

    /** Returns the resolved provider, or throws if resolution failed. */
    @SuppressWarnings("StaticAssignmentOfThrowable")
    public static UnicodeDataProvider get() {
        UnicodeDataProvider p = cached;
        if (p != null) {
            return p;
        }
        synchronized (UnicodeProviders.class) {
            if (cached != null) {
                return cached;
            }
            if (failure != null) {
                throw initFailure(failure);
            }
            try {
                p = resolve();
                cached = p;
                return p;
            } catch (Throwable t) {
                // Memoize the failure (Throwable, not RuntimeException: a
                // user provider's static initializer can die with an Error)
                // so every later get() throws the same diagnosis.
                failure = t;
                throw initFailure(t);
            }
        }
    }

    /**
     * The tables a compile resolves against: the pinned
     * {@code provider} when the compile carried one, the process default
     * ({@link #get()}) otherwise — the one place the null-means-default
     * policy lives for the core tier. Compile boundaries resolve their
     * provider through this exactly once and hand a non-null provider to
     * everything below. (The surface tier's default differs by design —
     * see {@code PatternCompiler.compile}'s provider resolution.)
     */
    public static UnicodeDataProvider effective(UnicodeDataProvider provider) {
        return provider != null ? provider : get();
    }

    /** Fresh-wrapped resolution failure — same diagnosis each call, current
     *  stack. The cause is the memoized original. */
    private static IllegalStateException initFailure(Throwable cause) {
        return new IllegalStateException("Unicode data provider initialisation failed (property " + PROPERTY_NAME
            + " = \"" + System.getProperty(PROPERTY_NAME) + "\"); see cause", cause);
    }

    private static UnicodeDataProvider resolve() {
        String v = System.getProperty(PROPERTY_NAME, "jdk");
        switch (v) {
            case "jdk" :
                return JdkUnicodeDataProvider.INSTANCE;
            default :
                Class<?> cls;
                try {
                    cls = Class.forName(v);
                } catch (ClassNotFoundException e) {
                    throw new IllegalArgumentException("Unicode provider class not found on classpath: " + v, e);
                }
                if (!UnicodeDataProvider.class.isAssignableFrom(cls)) {
                    throw new IllegalArgumentException(
                        "Unicode provider class " + v + " does not implement " + UnicodeDataProvider.class.getName());
                }
                try {
                    return (UnicodeDataProvider) cls.getDeclaredConstructor().newInstance();
                } catch (ReflectiveOperationException e) {
                    throw new IllegalStateException(
                        "Unicode provider class " + v + " has no accessible no-arg constructor", e);
                }
        }
    }
}
