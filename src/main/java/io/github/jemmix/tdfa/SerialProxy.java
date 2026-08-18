package io.github.jemmix.tdfa;

import java.io.Serializable;

/**
 * Serialization proxy for Pattern implementations: serializes only
 * {@code pattern + flags} and recompiles (with the default engine) on
 * readResolve. This is what makes generated per-pattern Patterns — whose
 * classes live in a child classloader that will not exist in the reading
 * process — serializable without pinning generated classes.
 */
final class SerialProxy implements Serializable {

    private static final long serialVersionUID = 1L;

    private final String pattern;
    private final int flags;

    SerialProxy(String pattern, int flags) {
        this.pattern = pattern;
        this.flags = flags;
    }

    private Object readResolve() {
        return Pattern.compile(pattern, flags);
    }
}
