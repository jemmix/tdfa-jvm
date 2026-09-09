package io.github.jemmix.tdfa;

import org.junit.jupiter.api.Test;

import java.io.DataInputStream;
import java.io.File;
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
 * Bytecode floor: every SHIPPED class (facade, core, asm, pikesim, unicode
 * data modules) must be class-file major version 52 or lower — i.e. loadable
 * by a Java 8 runtime (docs/REVIEW-2026-09.md §1b, the re2j-charter floor).
 *
 * <p>The {@code --release 8} flags in the build.gradle files are the primary
 * enforcement; this test is the independent backstop that survives a build
 * config regression (a forgotten flag, a moved module, a new source root).
 * It locates each published module's compiled output through the module's
 * own classes (code source), then asserts on every class file in it — the
 * exact artifact set a consumer receives.
 *
 * <p>Major-version reference: 52 = Java 8, 61 = Java 17, 70 = Java 26.
 */
class BytecodeFloorTest {

    /** Java 8. Deliberately local: this number IS the specification here. */
    private static final int MAX_MAJOR = 52;

    /** One anchor class per shipped module — compile-time presence guarantee. */
    private static final Class<?>[] ANCHORS = {
            io.github.jemmix.tdfa.Pattern.class,                          // facade
            io.github.jemmix.tdfa.tdfa.Tdfa.class,                        // core
            io.github.jemmix.tdfa.asm.TdfaAsmBackend.class,               // asm
            io.github.jemmix.tdfa.sim.PikeSim.class,                      // lib:pikesim
            io.github.jemmix.tdfa.unicode.v6_0.Unicode6_0.class,          // unicode:v6_0
            io.github.jemmix.tdfa.unicode.v17_0.Unicode17_0.class,        // unicode:v17_0
    };

    @Test
    void everyShippedClassIsAtMostJava8() throws IOException {
        Set<Path> roots = new LinkedHashSet<>();
        for (Class<?> anchor : ANCHORS) roots.add(codeSourceDir(anchor));
        // Every anchor resolved to a distinct, plausible classes directory.
        assertThat(roots).hasSameSizeAs(ANCHORS);

        List<String> offenders = new ArrayList<>();
        int[] checked = {0};
        for (Path root : roots) {
            int before = checked[0];
            if (Files.isDirectory(root)) {
                Files.walk(root).filter(f -> f.toString().endsWith(".class")).forEach(f -> {
                    checked[0]++;
                    checkOne(f.toString(), offenders, () -> Files.newInputStream(f));
                });
            } else if (root.toString().endsWith(".jar")) {
                // Gradle may place a project dependency as its packaged jar.
                try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(root.toFile())) {
                    java.util.Enumeration<? extends java.util.zip.ZipEntry> entries = zip.entries();
                    while (entries.hasMoreElements()) {
                        java.util.zip.ZipEntry e = entries.nextElement();
                        if (!e.getName().endsWith(".class")) continue;
                        checked[0]++;
                        java.util.zip.ZipEntry entry = e;
                        checkOne(root + "!" + e.getName(), offenders, () -> zip.getInputStream(entry));
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
                .as("classes above major %d (Java 8)", MAX_MAJOR)
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

    private static void checkOne(String name, List<String> offenders, IOSupplier<InputStream> open) {
        try (InputStream in = open.get(); DataInputStream data = new DataInputStream(in)) {
            int magic = data.readInt();
            if (magic != 0xCAFEBABE) throw new IOException("not a class file: " + name);
            data.readUnsignedShort();                       // minor
            int major = data.readUnsignedShort();
            if (major > MAX_MAJOR) offenders.add("major " + major + ": " + name);
        } catch (IOException e) {
            offenders.add("unreadable (" + e + "): " + name);
        }
    }

    private interface IOSupplier<T> {
        T get() throws IOException;
    }
}
