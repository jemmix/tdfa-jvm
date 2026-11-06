package io.github.jemmix.tdfa.core.compile;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins {@link PatternSurface#resolve}'s lazy-registration path — the
 * entire reason {@code triggerClass} exists: a reading process that has
 * never compiled through a surface must still deserialize that surface's
 * patterns, the surface's registration holder loading by name (review
 * item C52; before this test every round-trip test compiled first, so the
 * {@code REGISTERED} map always hit and the reflective branch could
 * regress silently until a real cross-process deserialization failed).
 *
 * <p>An in-JVM eviction alone cannot exercise the branch: the trigger
 * class is already initialized in this process, so {@code Class.forName}
 * is a no-op and the resolve correctly fails. The faithful emulation is
 * a classloader boundary — the reading process never to have initialized
 * the holder — so the deserialization below runs with a context
 * classloader that loads the re2j surface module's classes child-first.
 * The re-registration then flows through the documented path (the
 * holder's static initializer calling {@code PatternSurface.register})
 * into the same shared registry, exactly as in a fresh reader process.
 *
 * <p>Lane survival is proven on a shape the two lanes read differently:
 * {@code \s} and U+000B — the jur reading's {@code \s} has the vertical
 * tab, the re2j reading's does not (re2j 1.8 / RE2 ASCII {@code \s}).
 */
class PatternSurfaceEvictionTest {

    private static final String VT_INPUT = "\u000B";

    @Test
    void evictedRegistrationReRegistersThroughHolderAndKeepsTheLane() throws Exception {
        io.github.jemmix.tdfa.re2j.Pattern before = io.github.jemmix.tdfa.re2j.Pattern.compile("\\s");
        // Pin the divergence premise against the live java.util.regex oracle.
        assertTrue(java.util.regex.Pattern.compile("\\s").matcher(VT_INPUT).find(), "jur reading: \\s has U+000B");
        assertFalse(before.matcher(VT_INPUT).find(), "re2j reading: \\s has no U+000B");
        assertTrue(before.matcher(" \n").find(), "re2j reading: \\s still has space and \\n");

        byte[] blob = serialize(before);
        PatternSurface original = PatternSurface.swapRegistrationForTest("re2j", null);
        try {
            assertTrue(original != null, "the surface must have been registered before the eviction");
            ClassLoader saved = Thread.currentThread().getContextClassLoader();
            Thread.currentThread().setContextClassLoader(re2jChildFirstLoader(saved));
            Object revived;
            try {
                ObjectInputStream in = new ObjectInputStream(new ByteArrayInputStream(blob));
                try {
                    revived = in.readObject();
                } finally {
                    in.close();
                }
            } finally {
                Thread.currentThread().setContextClassLoader(saved);
            }
            // The revived carrier is typed in the child loader's re2j module,
            // but it is a TDFAPattern (shared core type) and must keep
            // answering in the RE2 lane, not fall back to the jur default.
            TDFAPattern revivedPattern = (TDFAPattern) revived;
            assertFalse(revivedPattern.matcher(VT_INPUT).find(), "revived pattern must stay in the re2j lane");
            assertTrue(revivedPattern.matcher(" \n").find(), "revived pattern still matches what re2j \\s matches");
            // And the registry was re-filled by the child-loaded holder's
            // static initializer — the documented lazy-registration path.
            PatternSurface reRegistered = PatternSurface.resolve("re2j", "io.github.jemmix.tdfa.re2j.Surfaces");
            assertTrue(reRegistered != original, "the holder's static init must have re-registered the surface");
        } finally {
            // Restore process state: the child-loaded holder re-registered a
            // functionally identical surface; the original instance goes back.
            PatternSurface.swapRegistrationForTest("re2j", original);
        }
    }

    @Test
    void unknownSurfaceIdFailsLoudly() {
        assertThrows(IllegalArgumentException.class, () -> PatternSurface.resolve("never-registered-surface", null));
        assertThrows(IllegalArgumentException.class,
            () -> PatternSurface.resolve("never-registered-surface", "io.github.jemmix.tdfa.no.SuchHolder"));
    }

    private static byte[] serialize(Object o) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        ObjectOutputStream out = new ObjectOutputStream(bos);
        try {
            out.writeObject(o);
        } finally {
            out.close();
        }
        return bos.toByteArray();
    }

    /**
     * Loads the re2j surface module's classes (the registration holder
     * {@code Surfaces} and its carriers) child-first — so its static
     * initializer runs again, as it would in a fresh reader process —
     * while everything else (core, the shared registry it registers into)
     * delegates to the parent unchanged.
     */
    private static ClassLoader re2jChildFirstLoader(ClassLoader parent) throws IOException {
        URL holder = parent.getResource("io/github/jemmix/tdfa/re2j/Surfaces.class");
        assertTrue(holder != null, "the re2j surface classes must be on the test classpath");
        String u = holder.toString();
        URL root;
        if (u.startsWith("jar:")) {
            // jar:file:/path/re2j.jar!/io/github/... → the jar's file: URL
            // (URLClassLoader accepts a jar by its plain file URL)
            root = URI.create(u.substring(4, u.indexOf('!'))).toURL();
        } else {
            // file:/classes-root/io/github/... → the classes root
            String suffix = "io/github/jemmix/tdfa/re2j/Surfaces.class";
            root = URI.create(u.substring(0, u.length() - suffix.length())).toURL();
        }
        return new URLClassLoader(new URL[]{root}, parent) {
            @Override
            protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                if (name.startsWith("io.github.jemmix.tdfa.re2j.")) {
                    Class<?> c = findLoadedClass(name);
                    if (c == null) {
                        c = findClass(name);
                    }
                    if (resolve) {
                        resolveClass(c);
                    }
                    return c;
                }
                return super.loadClass(name, resolve);
            }
        };
    }
}
