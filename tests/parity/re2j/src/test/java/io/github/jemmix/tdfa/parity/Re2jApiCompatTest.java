package io.github.jemmix.tdfa.parity;

import io.github.jemmix.tdfa.core.compile.PatternMatcher;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The re2j facade's API-compat contract, verified by reflection against
 * the re2j oracle's own surface (the patched vendored jar rides this
 * suite's classpath) — the re2j twin of the jur facade's
 * {@code JurApiCompatTest} (which checks against the live JDK's classes
 * in the unit suite): every public member of
 * {@code com.google.re2j.Pattern} / {@code com.google.re2j.Matcher} has
 * a compatible spelling on the {@code :re2j} facade and the shared
 * matcher — the drop-in promise as an executable check, answering the
 * "how is API compat verified" question mechanically instead of by
 * assertion. Additive members (the tdfa extensions) are fine; the JDK's
 * region/bounds family on the shared matcher is additive from re2j's
 * point of view and asserted as such.
 */
class Re2jApiCompatTest {

    /** re2j type name → this library's spelling. */
    private static final Map<String, String> TYPE_MAP = new HashMap<>();

    static {
        TYPE_MAP.put("com.google.re2j.Pattern", "io.github.jemmix.tdfa.re2j.Pattern");
        TYPE_MAP.put("com.google.re2j.Matcher", "io.github.jemmix.tdfa.core.compile.PatternMatcher");
        TYPE_MAP.put("com.google.re2j.MatcherInput", "java.lang.CharSequence");
        TYPE_MAP.put("io.github.jemmix.tdfa.core.engine.Matcher", "io.github.jemmix.tdfa.core.compile.PatternMatcher");
        TYPE_MAP.put("io.github.jemmix.tdfa.core.compile.TDFAPattern", "io.github.jemmix.tdfa.re2j.Pattern");
    }

    private static final Set<String> OBJECT_MEMBERS =
        Set.of("toString", "equals", "hashCode", "getClass", "notify", "notifyAll", "wait", "clone", "finalize");

    @Test
    void patternStaticSurfaceCoversRe2j() {
        assertThat(missingStatics(com.google.re2j.Pattern.class, io.github.jemmix.tdfa.re2j.Pattern.class)).isEmpty();
    }

    @Test
    void patternInstanceSurfaceCoversRe2j() {
        assertThat(missingInstanceMethods(com.google.re2j.Pattern.class, io.github.jemmix.tdfa.re2j.Pattern.class))
            .isEmpty();
    }

    @Test
    void patternFlagConstantsCoverRe2j() {
        Set<String> missing = intConstants(com.google.re2j.Pattern.class);
        missing.removeAll(intConstants(io.github.jemmix.tdfa.re2j.Pattern.class));
        assertThat(missing).isEmpty();
    }

    @Test
    void matcherSurfaceCoversRe2j() {
        assertThat(missingInstanceMethods(com.google.re2j.Matcher.class, PatternMatcher.class)).isEmpty();
    }

    @Test
    void sharedMatcherIsAdditiveOverRe2j() {
        // The JDK region/bounds family (and the stream readouts) are ADDITIVE
        // from re2j's point of view: present on the shared matcher, absent
        // from re2j's — the jur facade is the one that claims them (see
        // JurApiParityTest); re2j's own usePattern is NOT additive (re2j
        // has one too — the machinery tier serves it). This pins that the
        // re2j surface never LOST anything by their arrival.
        Set<String> jdkFamily =
            Set.of("region", "regionStart", "regionEnd", "hitEnd", "requireEnd", "toMatchResult", "results");
        Set<String> re2jHas = methodNames(com.google.re2j.Matcher.class);
        assertThat(re2jHas).doesNotContainAnyElementsOf(jdkFamily);
        assertThat(methodNames(io.github.jemmix.tdfa.core.engine.Matcher.class)).containsAll(jdkFamily);
    }

    // ---- machinery (the jur twin's shape; see JurApiCompatTest) ----

    private static Set<String> methodNames(Class<?> c) {
        return Arrays.stream(c.getMethods()).filter(m -> m.getDeclaringClass() != Object.class)
            .filter(m -> !OBJECT_MEMBERS.contains(m.getName())).map(Method::getName)
            .collect(Collectors.toCollection(TreeSet::new));
    }

    private static Set<String> intConstants(Class<?> c) {
        return Arrays.stream(c.getFields()).filter(f -> (f.getModifiers() & Modifier.STATIC) != 0)
            .filter(f -> f.getType() == int.class).map(Field::getName).collect(Collectors.toCollection(TreeSet::new));
    }

    private static String desc(Class<?> r) {
        if (r.isArray()) {
            return desc(r.getComponentType()) + "[]";
        }
        return TYPE_MAP.getOrDefault(r.getName(), r.getName());
    }

    private static String shape(Method m) {
        return m.getName() + "("
            + Arrays.stream(m.getParameterTypes()).map(Re2jApiCompatTest::desc).collect(Collectors.joining(",")) + ")->"
            + desc(m.getReturnType());
    }

    private static Set<String> missingStatics(Class<?> ref, Class<?> ours) {
        Set<String> have = new HashSet<>();
        for (Method m : ours.getMethods()) {
            if ((m.getModifiers() & Modifier.STATIC) != 0) {
                have.add(shape(m));
            }
        }
        return shapes(ref, Modifier.STATIC, true).stream().filter(s -> !have.contains(s))
            .collect(Collectors.toCollection(TreeSet::new));
    }

    private static Set<String> missingInstanceMethods(Class<?> ref, Class<?> ours) {
        Set<String> have = new HashSet<>();
        for (Method m : ours.getMethods()) {
            if ((m.getModifiers() & Modifier.STATIC) == 0) {
                have.add(shape(m));
            }
        }
        return shapes(ref, Modifier.STATIC, false).stream().filter(s -> !have.contains(s))
            .collect(Collectors.toCollection(TreeSet::new));
    }

    private static Set<String> shapes(Class<?> c, int staticBit, boolean wantStatic) {
        return Arrays.stream(c.getMethods()).filter(m -> m.getDeclaringClass() != Object.class)
            .filter(m -> !OBJECT_MEMBERS.contains(m.getName()))
            .filter(m -> ((m.getModifiers() & staticBit) != 0) == wantStatic).map(Re2jApiCompatTest::shape)
            .collect(Collectors.toCollection(TreeSet::new));
    }
}
