package io.github.jemmix.tdfa;

import org.junit.jupiter.api.Test;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.JarURLConnection;
import java.net.URL;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The core package DAG, enforced. The {@code io.github.jemmix.tdfa.core.*}
 * tree is layered (see each package's package-info.java): dependencies may
 * only point to a package's ALLOWED set below. The graph was deliberately
 * made acyclic when the tree was introduced (the artifact's interpreter
 * lives beside the artifact, the engine factory sits in the top compile
 * tier, shared builder vocabulary lives in the artifact model) so that any
 * upper cut of the layer table is a valid future module boundary.
 *
 * <p>This test mirrors the ALLOWED table: every {@code core.*} class file
 * on the test classpath has its constant pool scanned for internal names
 * ({@code io/github/jemmix/tdfa/core/...} in class references, field and
 * method descriptors, and generics signatures), and each discovered edge
 * must be in the emitting package's allowed set. A new edge fails here
 * with the exact package pair — before it quietly reintroduces a cycle.
 *
 * <p>Works against class directories (default) and jars ({@code
 * -PagainstJars}), so the realJdk8 CI job enforces the same graph.
 */
class PackageLayeringTest {

    private static final String ROOT = "io/github/jemmix/tdfa/core/";

    /** The DAG: package -> packages it may reference. */
    private static Map<String, SortedSet<String>> allowed() {
        Map<String, SortedSet<String>> m = new HashMap<>();
        String[][] edges = {{"emit"}, {"unicode"}, {"budget"}, {"report"}, // leaves
            {"ast", "budget"}, {"parser", "ast", "budget", "unicode"},
            {"tnfa", "parser", "ast", "budget", "unicode", "report"}, {"engine", "emit"},
            {"dfa", "engine", "emit", "ast", "tnfa", "budget"}, {"regopt", "dfa", "budget"},
            {"determinize", "tnfa", "ast", "regopt", "dfa", "budget", "report"},
            {"compile", "engine", "dfa", "determinize", "tnfa", "budget", "unicode", "report"},};
        for (String[] e : edges) {
            SortedSet<String> deps = new TreeSet<>();
            for (int i = 1; i < e.length; i++) {
                deps.add(e[i]);
            }
            m.put(e[0], deps);
        }
        return m;
    }

    @Test
    void corePackageGraphIsAcyclicByAllowlist() throws Exception {
        Map<String, SortedSet<String>> allowed = allowed();
        Map<String, SortedSet<String>> seen = new HashMap<>();
        for (String cls : coreClassFiles()) {
            String pkg = packageOf(cls);
            SortedSet<String> refs = scanConstantPool(cls);
            for (String ref : refs) {
                String refPkg = packageOf(ref);
                if (refPkg.equals(pkg) || ref.endsWith('/' + simple(cls))) {
                    continue; // self package / own class
                }
                if (!allowed.get(pkg).contains(refPkg)) {
                    throw new AssertionError(String.format(
                        "layering violation: %s references %s (%s -> %s not allowed)%n" + "allowed for %s: %s",
                        simple(cls), simple(ref), pkg, refPkg, pkg, allowed.get(pkg)));
                }
                seen.computeIfAbsent(pkg, k -> new TreeSet<>()).add(refPkg);
            }
        }
        // every non-leaf package must actually exercise at least its lower
        // neighbor set shape: sanity that the scan saw the classes at all.
        assertThat(seen.keySet()).as("packages with discovered edges")
            .containsAll(Arrays.asList("dfa", "determinize", "compile", "tnfa"));
    }

    private static String packageOf(String internalName) {
        int i = internalName.lastIndexOf('/');
        return internalName.substring(ROOT.length(), i);
    }

    private static String simple(String internalName) {
        return internalName.substring(internalName.lastIndexOf('/') + 1);
    }

    /** All core .class resources on the classpath (dirs and jars), restricted
     *  to the core module's own code source (test classes may share the
     *  package tree without joining the DAG). */
    private static List<String> coreClassFiles() throws Exception {
        Object coreSrc =
            Class.forName("io.github.jemmix.tdfa.core.dfa.Tdfa").getProtectionDomain().getCodeSource().getLocation();
        List<String> names = new ArrayList<>();
        Enumeration<URL> roots = PackageLayeringTest.class.getClassLoader().getResources(ROOT);
        while (roots.hasMoreElements()) {
            URL url = roots.nextElement();
            if ("jar".equals(url.getProtocol())) {
                JarURLConnection c = (JarURLConnection) url.openConnection();
                if (!c.getJarFileURL().equals(coreSrc)) {
                    continue; // a jar carrying core.* test classes, not the module
                }
                JarFile jar = c.getJarFile();
                Enumeration<JarEntry> es = jar.entries();
                while (es.hasMoreElements()) {
                    String n = es.nextElement().getName();
                    if (n.startsWith(ROOT) && n.endsWith(".class") && !n.contains("$")) {
                        names.add(n.substring(0, n.length() - ".class".length()));
                    }
                }
            } else {
                if (!Paths.get(url.toURI()).equals(Paths.get(((URL) coreSrc).toURI()))) {
                    continue; // a classes dir carrying core.* test classes
                }
                Path dir = Paths.get(url.toURI());
                try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir)) {
                    for (Path p : ds) {
                        // resources() returns one URL per package directory; the
                        // tree has 12 package dirs directly under core/
                        walk(p, dir, names);
                    }
                }
            }
        }
        assertThat(names.size()).as("core classes scanned").isGreaterThan(40);
        return names;
    }

    private static void walk(Path p, Path root, List<String> names) throws IOException {
        if (Files.isDirectory(p)) {
            try (DirectoryStream<Path> ds = Files.newDirectoryStream(p)) {
                for (Path c : ds) {
                    walk(c, root, names);
                }
            }
        } else if (p.toString().endsWith(".class") && !p.getFileName().toString().contains("$")) {
            String rel = root.relativize(p).toString().replace('\\', '/');
            names.add(ROOT + rel.substring(0, rel.length() - ".class".length()));
        }
    }

    /** Utf8 constant-pool entries that embed core internal names. */
    private static SortedSet<String> scanConstantPool(String internalName) throws IOException {
        SortedSet<String> refs = new TreeSet<>();
        try (InputStream in = PackageLayeringTest.class.getClassLoader().getResourceAsStream(internalName + ".class")) {
            assertThat(in).as("class resource %s", internalName).isNotNull();
            DataInputStream d = new DataInputStream(in);
            assertThat(d.readInt()).as("magic of %s", internalName).isEqualTo(0xCAFEBABE);
            d.readInt(); // minor + major version (2+2 bytes)
            int count = d.readUnsignedShort();
            for (int i = 1; i < count; i++) {
                int tag = d.readUnsignedByte();
                switch (tag) {
                    case 1 : // Utf8
                        String s = d.readUTF();
                        int at = s.indexOf(ROOT);
                        while (at >= 0) {
                            int end = at;
                            while (end < s.length() && s.charAt(end) != ';' && s.charAt(end) != '<'
                                && s.charAt(end) != '(' && s.charAt(end) != ')') {
                                end++;
                            }
                            String name = s.substring(at, end);
                            if (name.endsWith(".class")) {
                                name = name.substring(0, name.length() - 6);
                            }
                            if (name.length() > ROOT.length()) {
                                refs.add(name);
                            }
                            at = s.indexOf(ROOT, end);
                        }
                        break;
                    case 7 : // Class
                    case 8 : // String
                    case 16 : // MethodType
                    case 19 : // Module
                    case 20 : // Package
                        d.skipBytes(2);
                        break;
                    case 15 : // MethodHandle
                        d.skipBytes(3);
                        break;
                    case 3 :
                    case 4 : // Integer/Float
                    case 9 :
                    case 10 :
                    case 11 :
                    case 12 :
                    case 17 :
                    case 18 : // refs
                        d.skipBytes(4);
                        break;
                    case 5 :
                    case 6 : // Long/Double: takes two slots
                        d.skipBytes(8);
                        i++;
                        break;
                    default :
                        throw new AssertionError("unknown constant pool tag " + tag + " in " + internalName);
                }
            }
        }
        return refs;
    }
}
