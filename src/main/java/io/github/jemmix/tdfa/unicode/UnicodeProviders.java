package io.github.jemmix.tdfa.unicode;

/**
 * Resolves the {@link UnicodeDataProvider} to use, based on the system
 * property {@link #PROPERTY_NAME}. Resolved exactly once and cached; if
 * resolution fails, the exception is re-thrown on every subsequent call
 * (no silent fallback to a default).
 *
 * <h2>Property values</h2>
 * <ul>
 *   <li>{@code "jdk"} (default if unset) — {@link JdkUnicodeDataProvider}</li>
 *   <li>{@code "no-unicode"} — {@link NoUnicodeProvider}; compiling any pattern
 *       containing {@code \p{...}} or {@code \P{...}} throws at parse time</li>
 *   <li>Any other value — treated as the fully-qualified name of a class
 *       implementing {@link UnicodeDataProvider}, loaded reflectively; the
 *       separate {@code tdfa-unicode-re2j} jar provides such a class for
 *       bit-exact re2j parity</li>
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
    private static volatile Throwable failure;

    private UnicodeProviders() {}

    /** Returns the resolved provider, or throws if resolution failed. */
    public static UnicodeDataProvider get() {
        UnicodeDataProvider p = cached;
        if (p != null) return p;
        if (failure != null) {
            throw new IllegalStateException(
                "Unicode data provider initialisation failed (property " + PROPERTY_NAME + " = \""
                + System.getProperty(PROPERTY_NAME) + "\"); see cause", failure);
        }
        synchronized (UnicodeProviders.class) {
            p = cached;
            if (p != null) return p;
            if (failure != null) {
                throw new IllegalStateException(
                    "Unicode data provider initialisation failed (property " + PROPERTY_NAME + " = \""
                    + System.getProperty(PROPERTY_NAME) + "\"); see cause", failure);
            }
            try {
                p = resolve();
                cached = p;
                return p;
            } catch (Throwable t) {
                failure = t;
                throw new IllegalStateException(
                    "Unicode data provider initialisation failed (property " + PROPERTY_NAME + " = \""
                    + System.getProperty(PROPERTY_NAME) + "\"); see cause", t);
            }
        }
    }

    private static UnicodeDataProvider resolve() {
        String v = System.getProperty(PROPERTY_NAME, "jdk");
        switch (v) {
            case "jdk":         return JdkUnicodeDataProvider.INSTANCE;
            case "no-unicode":  return NoUnicodeProvider.INSTANCE;
            default:
                Class<?> cls;
                try {
                    cls = Class.forName(v);
                } catch (ClassNotFoundException e) {
                    throw new IllegalArgumentException(
                        "Unicode provider class not found on classpath: " + v, e);
                }
                if (!UnicodeDataProvider.class.isAssignableFrom(cls)) {
                    throw new IllegalArgumentException(
                        "Unicode provider class " + v + " does not implement " +
                        UnicodeDataProvider.class.getName());
                }
                try {
                    return (UnicodeDataProvider) cls.getDeclaredConstructor().newInstance();
                } catch (ReflectiveOperationException e) {
                    throw new IllegalStateException(
                        "Unicode provider class " + v + " has no accessible no-arg constructor", e);
                }
        }
    }

    /** Test-only: clears the cached provider so the property can be re-read. */
    static void resetForTest() {
        synchronized (UnicodeProviders.class) {
            cached = null;
            failure = null;
        }
    }
}
