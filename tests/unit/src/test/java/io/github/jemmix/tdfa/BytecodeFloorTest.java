package io.github.jemmix.tdfa;

import org.junit.jupiter.api.Test;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Bytecode floor per shipped module (docs/REVIEW-2026-09.md §1d): core and
 * asm must be class-file major version 52 or lower — loadable by a Java 8
 * runtime — while the facade, pikesim, and the unicode data modules ship as
 * Java 25 bytecode (latest LTS, major 69).
 *
 * <p>The {@code --release} flags (and the real-javac-8 toolchain mode) in
 * the build.gradle files are the primary enforcement; this test is the
 * independent backstop that survives a build config regression (a forgotten
 * flag, a moved module, a new source root). It locates each published
 * module's compiled output through the module's own classes (code source),
 * then asserts on every class file in it — the exact artifact set a
 * consumer receives. Under {@code -PagainstJars} the code sources are the
 * packaged jars, so this pins the very artifacts CI ships.
 *
 * <p>Major-version reference: 52 = Java 8, 69 = Java 25.
 */
class BytecodeFloorTest {

    /** Java 8. Deliberately local: this number IS the specification here. */
    private static final int MAJOR_JAVA_8 = 52;

    /** Java 25 (latest LTS). */
    private static final int MAJOR_JAVA_25 = 69;

    /** One anchor + ceiling per shipped module — compile-time presence guarantee. */
    private static final Object[][] ANCHORS = {
            {io.github.jemmix.tdfa.Pattern.class, MAJOR_JAVA_25},                    // facade
            {io.github.jemmix.tdfa.tdfa.Tdfa.class, MAJOR_JAVA_8},                   // core
            {io.github.jemmix.tdfa.asm.TdfaAsmBackend.class, MAJOR_JAVA_8},          // asm
            {io.github.jemmix.tdfa.sim.PikeSim.class, MAJOR_JAVA_25},                // lib:pikesim
            {io.github.jemmix.tdfa.unicode.v6_0.Unicode6_0.class, MAJOR_JAVA_25},    // unicode:v6_0
            {io.github.jemmix.tdfa.unicode.v17_0.Unicode17_0.class, MAJOR_JAVA_25},  // unicode:v17_0
    };

    @Test
    void everyShippedClassIsAtItsFloor() throws IOException {
        Set<Path> roots = new LinkedHashSet<>();
        for (Object[] anchor : ANCHORS) roots.add(codeSourceDir((Class<?>) anchor[0]));
        // Every anchor resolved to a distinct, plausible classes directory.
        assertThat(roots).hasSize(ANCHORS.length);

        List<String> offenders = new ArrayList<>();
        int[] checked = {0};
        for (Object[] anchor : ANCHORS) {
            Path root = codeSourceDir((Class<?>) anchor[0]);
            int limit = (Integer) anchor[1];
            System.out.println("floor: " + root + " (max major " + limit + ")");
            int before = checked[0];
            if (Files.isDirectory(root)) {
                Files.walk(root).filter(f -> f.toString().endsWith(".class")).forEach(f -> {
                    checked[0]++;
                    checkOne(f.toString(), limit, offenders, () -> Files.newInputStream(f));
                });
            } else if (root.toString().endsWith(".jar")) {
                // Gradle may place a project dependency as its packaged jar
                // (the -PagainstJars CI pipeline always does).
                try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(root.toFile())) {
                    java.util.Enumeration<? extends java.util.zip.ZipEntry> entries = zip.entries();
                    while (entries.hasMoreElements()) {
                        java.util.zip.ZipEntry e = entries.nextElement();
                        if (!e.getName().endsWith(".class")) continue;
                        checked[0]++;
                        java.util.zip.ZipEntry entry = e;
                        checkOne(root + "!" + e.getName(), limit, offenders, () -> zip.getInputStream(entry));
                    }
                }
            }
            // Vacuous-success guard per module: an empty/ relocated output
            // directory must fail loudly, not silently pass.
            assertThat(checked[0] - before)
                    .as("class files under %s (output layout change?)", root)
                    .isGreaterThanOrEqualTo(3);
        }
        assertThat(checked[0]).isGreaterThanOrEqualTo(60);
        assertThat(offenders)
                .as("classes above their module's floor major")
                .isEmpty();
    }

    /** The compiled-output directory backing {@code clazz}. */
    private static Path codeSourceDir(Class<?> clazz) {
        try {
            if (clazz.getProtectionDomain().getCodeSource() != null) {
                return Paths.get(clazz.getProtectionDomain().getCodeSource().getLocation().toURI());
            }
        } catch (Exception expected) {
            // fall through to the resource-based lookup
        }
        String resource = clazz.getName().replace('.', '/') + ".class";
        URL url = clazz.getClassLoader().getResource(resource);
        assertThat(url).as("resource %s", resource).isNotNull();
        assertThat(url.getProtocol()).as("protocol for %s", resource).isEqualTo("file");
        String p = url.getFile();
        return Paths.get(p.substring(0, p.length() - resource.length()));
    }

    private static void checkOne(String name, int limit, List<String> offenders, IOSupplier<InputStream> open) {
        try (InputStream in = open.get(); DataInputStream data = new DataInputStream(in)) {
            int magic = data.readInt();
            if (magic != 0xCAFEBABE) throw new IOException("not a class file: " + name);
            data.readUnsignedShort();                       // minor
            int major = data.readUnsignedShort();
            if (major > limit) offenders.add("major " + major + " > " + limit + ": " + name);
        } catch (IOException e) {
            offenders.add("unreadable (" + e + "): " + name);
        }
    }

    private interface IOSupplier<T> {
        T get() throws IOException;
    }
}
