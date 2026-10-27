package io.github.jemmix.tdfa;

import io.github.jemmix.tdfa.core.compile.PatternMatcher;
import io.github.jemmix.tdfa.core.compile.TDFAPattern;
import io.github.jemmix.tdfa.core.dfa.TdfaRunner;
import io.github.jemmix.tdfa.core.engine.RegexEngine;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The concurrency hammer + freeze watchdog of the runner-immutability
 * program (docs/runner-immutability.md, tripwires 4-6), run against the
 * CURRENT structures so the W2-W4 conversions start from a pinned
 * baseline:
 *
 * <ul>
 * <li><b>Hammer</b> — N threads scan one shared {@code Pattern} over
 * multi-KB inputs (wide classes exercise the walk-block memo;
 * supplementary segments exercise the codepoint paths), on both tiers
 * (the emitted engine and the interpreter). Every thread's byte-for-byte
 * protocol must equal every other's AND a cold artifact twin's: warm ==
 * cold == concurrent.</li>
 * <li><b>Freeze watchdog</b> — every frozen table (final primitive-array
 * field of the runner, its artifact, and the emitted classes' table
 * statics) is content-hashed before and after the hammer; any post-
 * construction mutation — including a stray out-of-bounds write — trips.</li>
 * <li><b>Emitted finality</b> — generated classes carry only final
 * fields (the emitted {@code WORD_RANGES} static is the one documented
 * exception until W4).</li>
 * </ul>
 *
 * <p>The sampled-recompute audit hook (tripwire 4) retired with the
 * search-DFA memo it audited (see docs/search-dfa-memo-tradeoffs.md).
 */
@Timeout(240)
final class RunnerImmutabilityTest {

    private static final int THREADS = 4;

    /** The only non-final fields allowed on emitted classes until W4. */
    private static final List<String> EMITTED_EXCEPTIONS = Arrays.asList("WORD_RANGES");

    private static final String[] INPUTS = buildInputs();

    /** {regex, flags} pairs; the unicode row forces wordRanges/WORD_RANGES. */
    private static final String[][] PATTERNS = {{"ab*cde", "0"}, {"(a|bb)+cde", "0"}, {"(?:ab){2,}c", "0"},
        {"(((a)(b))c){2,}", "0"}, {"[a-c]{3}d", "0"}, {"\\w+\\b", String.valueOf(Pattern.UNICODE_CHARACTER_CLASS)},
        {"(\\p{L}{2,})x", "0"}, {"a\uD801\uDC00{2,}", "0"}, {"(?s).{8,9}z", "0"},};

    @Test
    void warmEqualsColdEqualsConcurrent() throws Exception {
        for (String[] pf : PATTERNS) {
            String regex = pf[0];
            int flags = Integer.parseInt(pf[1]);
            // Both tiers share the TdfaRunner memo machinery; hammer both.
            hammer(regex, flags, (TDFAPattern) Pattern.compile(regex, flags));
            hammer(regex, flags, (TDFAPattern) Pattern.compile(regex, flags, TdfaRunner::new));
        }
    }

    private static void hammer(String regex, int flags, TDFAPattern warm) throws Exception {
        TDFAPattern cold = (TDFAPattern) (warm.engine() instanceof TdfaRunner
            ? Pattern.compile(regex, flags, TdfaRunner::new) : Pattern.compile(regex, flags));
        Map<String, Integer> frozenBefore = frozenFingerprint(warm);
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        try {
            List<Future<String>> futures = new ArrayList<>();
            for (int t = 0; t < THREADS; t++) {
                futures.add(pool.submit(() -> battery(warm)));
            }
            // The cold twin serves its first battery while the hammer runs.
            String coldResult = battery(cold);
            String first = null;
            for (Future<String> f : futures) {
                String r = f.get();
                if (first == null) {
                    first = r;
                }
                assertThat(r).as("concurrent thread result vs first, %s", regex).isEqualTo(first);
            }
            assertThat(coldResult).as("cold artifact vs warm concurrent, %s", regex).isEqualTo(first);
        } finally {
            pool.shutdownNow();
        }
        assertThat(frozenFingerprint(warm)).as("frozen tables mutated by the hammer, %s", regex)
            .isEqualTo(frozenBefore);
    }

    @Test
    void emittedClassesCarryOnlyFinalFields() throws Exception {
        List<String> errors = new ArrayList<>();
        for (String[] pf : PATTERNS) {
            TDFAPattern p = (TDFAPattern) Pattern.compile(pf[0], Integer.parseInt(pf[1]));
            assertAllFieldsFinal(p.engine().getClass(), errors);
            if (p.wholeEngine() != null) {
                assertAllFieldsFinal(p.wholeEngine().getClass(), errors);
            }
            assertAllFieldsFinal(p.matcher(INPUTS[0]).getClass(), errors);
        }
        assertThat(errors).as("non-final fields on emitted classes (the exceptions list is the W4 burn-down)")
            .isEmpty();
    }

    /**
     * Generated classes live in {@code io.github.jemmix.tdfa.gen}; walking
     * stops at core base classes ({@code Matcher}, {@code PatternMatcher})
     * whose per-call carriers are the finality gate's scope, not ours.
     */
    private static void assertAllFieldsFinal(Class<?> c, List<String> errors) {
        for (Class<?> k = c; k != null && k != Object.class; k = k.getSuperclass()) {
            if (!k.getName().startsWith("io.github.jemmix.tdfa.gen.")) {
                continue;
            }
            for (Field f : k.getDeclaredFields()) {
                if (f.isSynthetic() || EMITTED_EXCEPTIONS.contains(f.getName())) {
                    continue;
                }
                if (!Modifier.isFinal(f.getModifiers())) {
                    errors.add(k.getName() + "." + f.getName());
                }
            }
        }
    }

    /**
     * A deterministic byte-for-byte protocol over all inputs: matches(),
     * lookingAt(), and a full find() walk with groups — enough surface to
     * notice any memo-state dependence in an answer.
     */
    private static String battery(TDFAPattern p) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < INPUTS.length; i++) {
            String in = INPUTS[i];
            PatternMatcher m = p.matcher(in);
            out.append(i).append(":m=").append(m.matches()).append(",l=").append(m.lookingAt());
            int guard = 0;
            while (m.find() && guard++ < 64) {
                out.append(',').append(m.start()).append('-').append(m.end());
                for (int g = 1; g <= m.groupCount(); g++) {
                    out.append('(').append(m.start(g)).append('-').append(m.end(g)).append(')');
                }
            }
            out.append(';');
        }
        return out.toString();
    }

    private static String[] buildInputs() {
        String[] out = new String[3];
        SplittableRandom rnd = new SplittableRandom(20261026L);
        for (int i = 0; i < out.length; i++) {
            int len = 8192 + i * 4096; // long scans: the walk-block memo engages
            StringBuilder sb = new StringBuilder(len);
            for (int k = 0; k < len; k++) {
                int r = rnd.nextInt(100);
                if (r < 70) {
                    sb.append(rnd.nextBoolean() ? 'a' : 'b');
                } else if (r < 82) {
                    sb.append((char) ('c' + rnd.nextInt(3))); // c/d/e: hit material
                } else if (r < 92) {
                    sb.append((char) (0x410 + rnd.nextInt(64))); // Cyrillic: wide-class walks
                } else if (r < 96) {
                    sb.append('x');
                    sb.appendCodePoint(0x10400 + rnd.nextInt(8)); // supplementary pairs
                } else {
                    sb.append(rnd.nextBoolean() ? '\n' : 'z');
                }
            }
            out[i] = sb.toString();
        }
        return out;
    }

    // ===== freeze watchdog =====

    /**
     * Content hash of every frozen table reachable from the pattern's
     * engines: all final primitive-array fields of the runner and its
     * artifact, plus the emitted classes' static tables. Memo containers
     * (the honest-cache targets) are intentionally NOT hashed — their
     * correctness is the hammer's behavioral claim, not immutability.
     */
    private static Map<String, Integer> frozenFingerprint(TDFAPattern p) throws Exception {
        Map<String, Integer> out = new LinkedHashMap<>();
        Object runner = runnerOf(p);
        if (runner != null) {
            hashInstanceArrays(runner, out);
            Object tdfa = fieldValue(runner, "tdfa");
            if (tdfa != null) {
                hashInstanceArrays(tdfa, out);
            }
        }
        hashStaticArrays(p.engine().getClass(), out);
        if (p.wholeEngine() != null && p.wholeEngine() != p.engine()) {
            hashStaticArrays(p.wholeEngine().getClass(), out);
        }
        assertThat(out).as("frozen tables found").isNotEmpty();
        return out;
    }

    private static Object runnerOf(TDFAPattern p) throws Exception {
        RegexEngine e = p.engine();
        if (e instanceof TdfaRunner) {
            return e;
        }
        Object r = fieldValue(e, "runner");
        return r instanceof TdfaRunner ? r : null;
    }

    private static Object fieldValue(Object o, String name) {
        for (Class<?> c = o.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f.get(o);
            } catch (NoSuchFieldException e) {
                // walk up
            } catch (IllegalAccessException e) {
                throw new IllegalStateException(e);
            }
        }
        return null;
    }

    private static void hashInstanceArrays(Object o, Map<String, Integer> out) {
        for (Class<?> c = o.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (f.isSynthetic() || !Modifier.isFinal(f.getModifiers()) || !f.getType().isArray()) {
                    continue;
                }
                f.setAccessible(true);
                try {
                    putHash(o.getClass().getSimpleName() + "." + f.getName(), f.get(o), out);
                } catch (IllegalAccessException e) {
                    throw new IllegalStateException(e);
                }
            }
        }
    }

    private static void hashStaticArrays(Class<?> c, Map<String, Integer> out) {
        for (Class<?> k = c; k != null && k != Object.class; k = k.getSuperclass()) {
            for (Field f : k.getDeclaredFields()) {
                if (f.isSynthetic() || !Modifier.isStatic(f.getModifiers()) || !f.getType().isArray()) {
                    continue;
                }
                f.setAccessible(true);
                try {
                    putHash(k.getSimpleName() + "." + f.getName(), f.get(null), out);
                } catch (IllegalAccessException e) {
                    throw new IllegalStateException(e);
                }
            }
        }
    }

    private static void putHash(String name, Object arr, Map<String, Integer> out) {
        Integer h;
        if (arr instanceof int[]) {
            h = Arrays.hashCode((int[]) arr);
        } else if (arr instanceof long[]) {
            h = Arrays.hashCode((long[]) arr);
        } else if (arr instanceof byte[]) {
            h = Arrays.hashCode((byte[]) arr);
        } else if (arr instanceof boolean[]) {
            h = Arrays.hashCode((boolean[]) arr);
        } else if (arr instanceof char[]) {
            h = Arrays.hashCode((char[]) arr);
        } else if (arr instanceof short[]) {
            h = Arrays.hashCode((short[]) arr);
        } else if (arr instanceof double[]) {
            h = Arrays.hashCode((double[]) arr);
        } else if (arr instanceof float[]) {
            h = Arrays.hashCode((float[]) arr);
        } else {
            return; // object arrays are not frozen tables
        }
        out.put(name, h);
    }
}
