package io.github.jemmix.tdfa;

import io.github.jemmix.tdfa.core.dfa.MemoCache;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.net.URL;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The finality gate of the runner-immutability program
 * (docs/runner-immutability.md): every non-final field on the match-time
 * classes is categorized, or the build fails.
 *
 * <p>Rules, matching the doc's invariant:
 * <ul>
 * <li>Non-final <b>instance</b> fields on shared artifacts are legal only
 * while carrying {@link MemoCache} <em>and</em> sitting on the transition
 * allowlist below (each entry names the work item that deletes it). The
 * end state has an empty allowlist.</li>
 * <li>Non-final <b>static</b> fields must be on the static allowlist with
 * a justification line (test/debug instruments).</li>
 * <li>Per-call carriers (contractually single-threaded, per-{@code Matcher})
 * and compile-time-only builders are named exemptions — never caches.</li>
 * <li>Stale allowlist entries fail the gate: an entry whose field or class
 * no longer exists (or went final) must be deleted, so the allowlist only
 * ever shrinks.</li>
 * </ul>
 *
 * <p>Scope: {@code core.dfa} + {@code core.engine}, the packages the doc
 * names. The facade's serialization transients ({@code TDFAPattern}) are
 * outside it — they are written exactly once, at readResolve.
 */
final class RunnerFinalityGateTest {

    /** Scanned packages (doc: "reflection scan of core.dfa/core.engine"). */
    private static final String[] PACKAGES = {"io.github.jemmix.tdfa.core.dfa", "io.github.jemmix.tdfa.core.engine"};

    /**
     * Transition allowlist: class → (field → deleting work item). Every
     * entry carries {@link MemoCache} at the declaration site with the
     * purity argument. Burned down to empty by W2-W4.
     */
    private static final Map<String, Map<String, String>> MEMO_ALLOWLIST = new HashMap<>();

    static {
        Map<String, String> sdfa = new HashMap<>();
        sdfa.put("capped", "W2 — honest-cache conversion");
        sdfa.put("rowWordsArr", "W2");
        sdfa.put("rowBlockIdsArr", "W2");
        sdfa.put("blocksArr", "W2");
        MEMO_ALLOWLIST.put("io.github.jemmix.tdfa.core.dfa.SearchDfa", sdfa);
        Map<String, String> wrapper = new HashMap<>();
        wrapper.put("a", "W2 — intern-table probe, lock-confined");
        wrapper.put("hash", "W2");
        MEMO_ALLOWLIST.put("io.github.jemmix.tdfa.core.dfa.SearchDfa$Wrapper", wrapper);
        Map<String, String> walk = new HashMap<>();
        walk.put("walkBlocksArr", "W3 — honest-cache conversion");
        walk.put("walkBlockCount", "W3");
        walk.put("chargedBytes", "W3");
        MEMO_ALLOWLIST.put("io.github.jemmix.tdfa.core.dfa.WalkIndex", walk);
        Map<String, String> tdfa = new HashMap<>();
        tdfa.put("stopMaskTableCache", "W4 — precompute at construction");
        tdfa.put("posFlagDepsCache", "W4");
        tdfa.put("absBeginBoundCache", "W4");
        MEMO_ALLOWLIST.put("io.github.jemmix.tdfa.core.dfa.Tdfa", tdfa);
    }

    /**
     * Static allowlist: class → (field → justification). Test/debug
     * instruments only; frozen-at-init knobs are final and need no entry.
     */
    private static final Map<String, Map<String, String>> STATIC_ALLOWLIST = new HashMap<>();

    static {
        Map<String, String> runner = new HashMap<>();
        runner.put("TRACE",
            "strategy-trace conformance instrument; runtime-toggleable by the test hook, " + "off the answer path");
        runner.put("anyForced", "forced-rung fuzz hook fast path; set-only latch, read never resets");
        STATIC_ALLOWLIST.put("io.github.jemmix.tdfa.core.dfa.TdfaRunner", runner);
    }

    /** Per-call carriers: contractually single-threaded match state. */
    private static final Set<String> CARRIERS = new HashSet<>();

    static {
        CARRIERS.add("io.github.jemmix.tdfa.core.engine.Matcher");
        CARRIERS.add("io.github.jemmix.tdfa.core.engine.MatchScratch");
        CARRIERS.add("io.github.jemmix.tdfa.core.engine.TagTree");
        CARRIERS.add("io.github.jemmix.tdfa.core.engine.RegexEngine$1"); // findAll iterator
    }

    /** Compile-time-only builders: never reachable from a compiled Pattern. */
    private static final Set<String> COMPILE_TIME = new HashSet<>();

    static {
        COMPILE_TIME.add("io.github.jemmix.tdfa.core.dfa.DfaStateBuilder");
        COMPILE_TIME.add("io.github.jemmix.tdfa.core.dfa.Range");
    }

    @Test
    void everyNonFinalFieldIsCategorized() throws Exception {
        Set<String> seen = new HashSet<>();
        StringBuilder errors = new StringBuilder();
        for (String pkg : PACKAGES) {
            for (Class<?> c : classesOf(pkg)) {
                String cn = c.getName();
                for (Field f : c.getDeclaredFields()) {
                    if (f.isSynthetic()) {
                        continue;
                    }
                    String name = cn + "." + f.getName();
                    seen.add(name);
                    if (Modifier.isStatic(f.getModifiers())) {
                        if (Modifier.isFinal(f.getModifiers())) {
                            continue;
                        }
                        if (justification(STATIC_ALLOWLIST, cn, f.getName()) == null) {
                            errors.append("non-final static without allowlist entry: ").append(name).append('\n');
                        }
                        continue;
                    }
                    if (CARRIERS.contains(cn) || COMPILE_TIME.contains(cn)) {
                        continue;
                    }
                    boolean fin = Modifier.isFinal(f.getModifiers());
                    String memo = justification(MEMO_ALLOWLIST, cn, f.getName());
                    boolean annotated = f.isAnnotationPresent(MemoCache.class);
                    if (!fin && memo == null) {
                        errors.append("non-final instance field without @MemoCache allowlist entry: ").append(name)
                            .append('\n');
                    } else if (!fin && !annotated) {
                        errors.append("allowlisted memo field missing @MemoCache: ").append(name).append('\n');
                    } else if (fin && annotated) {
                        errors.append("@MemoCache on a final field (stale — delete the annotation and entry): ")
                            .append(name).append('\n');
                    }
                }
            }
        }
        assertThat(seen.size()).as("scanned field count sanity").isGreaterThan(80);
        assertThat(errors.toString()).as("finality gate violations").isEmpty();
    }

    @Test
    void allowlistsCarryNoStaleEntries() throws Exception {
        StringBuilder errors = new StringBuilder();
        for (String pkg : PACKAGES) {
            for (Class<?> c : classesOf(pkg)) {
                String cn = c.getName();
                Map<String, String> memo = MEMO_ALLOWLIST.get(cn);
                if (memo != null) {
                    for (String fn : memo.keySet()) {
                        Field f = fieldOrNull(c, fn);
                        if (f == null || Modifier.isFinal(f.getModifiers())
                            || !f.isAnnotationPresent(MemoCache.class)) {
                            errors.append("stale MEMO_ALLOWLIST entry (field gone, final, or unannotated): ").append(cn)
                                .append('.').append(fn).append('\n');
                        }
                    }
                }
                Map<String, String> stat = STATIC_ALLOWLIST.get(cn);
                if (stat != null) {
                    for (String fn : stat.keySet()) {
                        Field f = fieldOrNull(c, fn);
                        if (f == null || Modifier.isFinal(f.getModifiers())) {
                            errors.append("stale STATIC_ALLOWLIST entry: ").append(cn).append('.').append(fn)
                                .append('\n');
                        }
                    }
                }
            }
        }
        assertThat(errors.toString()).as("stale allowlist entries").isEmpty();
    }

    /**
     * The headline the refined doc pins: the runner body itself is
     * frozen — zero non-final instance fields on {@code TdfaRunner}.
     */
    @Test
    void runnerBodyIsFrozen() throws Exception {
        Class<?> runner = Class.forName("io.github.jemmix.tdfa.core.dfa.TdfaRunner");
        for (Field f : runner.getDeclaredFields()) {
            if (!f.isSynthetic() && !Modifier.isStatic(f.getModifiers())) {
                assertThat(Modifier.isFinal(f.getModifiers()))
                    .as("TdfaRunner.%s must be final — per-call state belongs on the Matcher", f.getName()).isTrue();
            }
        }
    }

    private static String justification(Map<String, Map<String, String>> list, String cls, String field) {
        Map<String, String> m = list.get(cls);
        return m == null ? null : m.get(field);
    }

    private static Field fieldOrNull(Class<?> c, String name) {
        for (Field f : c.getDeclaredFields()) {
            if (f.getName().equals(name)) {
                return f;
            }
        }
        return null;
    }

    /** Zero-dependency package scan: classloader resources, dirs and jars. */
    private static Set<Class<?>> classesOf(String pkg) throws Exception {
        Set<Class<?>> out = new HashSet<>();
        String path = pkg.replace('.', '/');
        ClassLoader cl = RunnerFinalityGateTest.class.getClassLoader();
        for (Enumeration<URL> e = cl.getResources(path); e.hasMoreElements();) {
            URL url = e.nextElement();
            String u = url.toString();
            if (u.startsWith("jar:")) {
                String jarPath = u.substring(9, u.indexOf('!'));
                try (ZipFile zip = new ZipFile(jarPath)) {
                    Enumeration<? extends ZipEntry> entries = zip.entries();
                    while (entries.hasMoreElements()) {
                        String n = entries.nextElement().getName();
                        if (n.startsWith(path + '/') && n.endsWith(".class")) {
                            out.add(Class.forName(n.substring(0, n.length() - 6).replace('/', '.'), false, cl));
                        }
                    }
                }
            } else {
                File dir = new File(url.toURI());
                String[] files = dir.list();
                if (files == null) {
                    continue;
                }
                for (String n : files) {
                    if (n.endsWith(".class")) {
                        out.add(Class.forName(pkg + '.' + n.substring(0, n.length() - 6), false, cl));
                    }
                }
            }
        }
        assertThat(out).as("package %s must yield classes (scan broken?)", pkg).isNotEmpty();
        return out;
    }
}
