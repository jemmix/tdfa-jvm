package io.github.jemmix.tdfa;

import io.github.jemmix.tdfa.core.compile.PatternMatcher;
import io.github.jemmix.tdfa.core.compile.TDFAPattern;
import io.github.jemmix.tdfa.core.engine.MatchSnapshot;
import io.github.jemmix.tdfa.core.engine.Matcher;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.StringJoiner;
import java.util.TreeSet;
import java.util.regex.MatchResult;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The jur facade's API-compat contract, verified by reflection against
 * the JDK's own surface: every public member of
 * {@code java.util.regex.Pattern} / {@code java.util.regex.Matcher} has
 * a compatible spelling on the {@link Pattern} facade and the shared
 * {@link Matcher} — the drop-in promise as an executable check that
 * rides every unit run with zero dependencies (the reference is the
 * live JDK). The re2j facade's twin lives beside the re2j parity suites
 * ({@code Re2jApiCompatTest}) where the re2j oracle jar is.
 *
 * <p><b>Type mapping.</b> The JDK's parameter/return types map onto this
 * library's spellings; everything else must match exactly:
 * <ul>
 * <li>{@code java.util.regex.Pattern} &rarr; {@link Pattern} on the
 *     facade — and, where the shared matcher needs the machinery type
 *     ({@code usePattern}'s parameter), {@link TDFAPattern}: every
 *     facade pattern IS one at runtime (the minted shells extend it);</li>
 * <li>{@code java.util.regex.Matcher} &rarr; the shared matcher class
 *     (a SUPERSET is fine — the re2j-shaped roster and the tdfa
 *     extensions ride along on both facades);</li>
 * <li>{@code java.util.regex.MatchResult} &rarr; {@link MatchSnapshot},
 *     which implements the JDK interface outright.</li>
 * </ul>
 *
 * <p><b>Documented exclusions</b> (asserted, never silent): the
 * {@code CANON_EQ} flag bit is deliberately absent — canonical
 * equivalence needs normalization-aware compilation, outside the TDFA
 * mission — and the facade's roster message names it, so a caller
 * passing the JDK bit value learns why.
 */
class JurApiCompatTest {

    /** JDK type name → this library's spelling. */
    private static final Map<String, String> TYPE_MAP = new HashMap<>();

    static {
        TYPE_MAP.put("java.util.regex.Matcher", "io.github.jemmix.tdfa.core.engine.Matcher");
        TYPE_MAP.put("java.util.regex.MatchResult", "io.github.jemmix.tdfa.core.engine.MatchSnapshot");
        TYPE_MAP.put("io.github.jemmix.tdfa.core.compile.PatternMatcher", "io.github.jemmix.tdfa.core.engine.Matcher");
    }

    /** Where a j.u.r Pattern-typed member maps onto the MACHINERY pattern
     *  type: usePattern's parameter (the shared matcher takes the type
     *  every facade pattern IS at runtime) and the matcher's own
     *  pattern()/usePattern() returns. */
    private static final Set<String> MACHINERY_PATTERN_MEMBERS = Set.of("usePattern", "pattern");

    private static final Set<String> OBJECT_MEMBERS =
        Set.of("toString", "equals", "hashCode", "getClass", "notify", "notifyAll", "wait", "clone", "finalize");

    @Test
    void patternStaticSurfaceCoversTheJdk() {
        assertThat(missingStatics(java.util.regex.Pattern.class, Pattern.class)).isEmpty();
    }

    @Test
    void patternInstanceSurfaceCoversTheJdk() {
        assertThat(missingInstanceMethods(java.util.regex.Pattern.class, Pattern.class)).isEmpty();
    }

    @Test
    void patternFlagConstantsCoverTheJdkExceptCanonEq() {
        Set<String> missing = intConstants(java.util.regex.Pattern.class);
        missing.removeAll(intConstants(Pattern.class));
        // The one documented exclusion (see the class doc).
        assertThat(missing).containsExactly("CANON_EQ");
        // And the roster rejection names it — no silent unknown-bit wall.
        assertThat(Lane.FLAGS_MESSAGE).contains("CANON_EQ");
    }

    @Test
    void matcherSurfaceCoversTheJdk() {
        // Against the facade matcher type (PatternMatcher extends the shared
        // engine Matcher and owns the pattern()/usePattern spellings).
        assertThat(missingInstanceMethods(java.util.regex.Matcher.class, PatternMatcher.class)).isEmpty();
        assertThat(missingStatics(java.util.regex.Matcher.class, Matcher.class)).isEmpty();
    }

    @Test
    void snapshotImplementsTheJdkMatchResultVerbatim() {
        // No mapping here: the snapshot IS a java.util.regex.MatchResult,
        // so the JDK's surface applies verbatim.
        assertThat(MatchResult.class).isAssignableFrom(MatchSnapshot.class);
        assertThat(missingInstanceMethods(MatchResult.class, MatchSnapshot.class)).isEmpty();
    }

    @Test
    void facadeMatchersServeWhereverTheJdkExpectsOne() {
        // matcher() hands out the shared matcher type, which implements
        // the JDK's MatchResult — the facade's results()/replaceAll(fn)
        // readouts work wherever java.util.regex.MatchResult is expected.
        assertThat(MatchResult.class).isAssignableFrom(PatternMatcher.class);
        assertThat(Pattern.compile("a").matcher("a")).isInstanceOf(MatchResult.class);
    }

    // ---- machinery ----

    private static Set<String> intConstants(Class<?> c) {
        return Arrays.stream(c.getFields()).filter(f -> (f.getModifiers() & Modifier.STATIC) != 0)
            .filter(f -> f.getType() == int.class).map(Field::getName).collect(Collectors.toCollection(TreeSet::new));
    }

    private static String desc(Class<?> r, String member) {
        if (r.isArray()) {
            return desc(r.getComponentType(), member) + "[]";
        }
        if ("java.util.regex.Pattern".equals(r.getName())) {
            // usePattern's parameter / the matcher's pattern returns map
            // onto the machinery type every facade pattern IS at runtime;
            // everywhere else onto the facade interface.
            return MACHINERY_PATTERN_MEMBERS.contains(member) ? "io.github.jemmix.tdfa.core.compile.TDFAPattern"
                : "io.github.jemmix.tdfa.Pattern";
        }
        return TYPE_MAP.getOrDefault(r.getName(), r.getName());
    }

    private static String shape(Method m) {
        StringJoiner params = new StringJoiner(",");
        for (Class<?> p : m.getParameterTypes()) {
            params.add(desc(p, m.getName()));
        }
        return m.getName() + "(" + params + ")->" + desc(m.getReturnType(), m.getName());
    }

    /** Public static methods of {@code jdk} with no compatible spelling on {@code ours}. */
    private static Set<String> missingStatics(Class<?> jdk, Class<?> ours) {
        Set<String> have = new HashSet<>();
        for (Method m : ours.getMethods()) {
            if ((m.getModifiers() & Modifier.STATIC) != 0) {
                have.add(shape(m));
            }
        }
        return shapes(jdk, Modifier.STATIC, true).stream().filter(s -> !have.contains(s))
            .collect(Collectors.toCollection(TreeSet::new));
    }

    /** Public instance methods of {@code jdk} with no compatible spelling on {@code ours}. */
    private static Set<String> missingInstanceMethods(Class<?> jdk, Class<?> ours) {
        Set<String> have = new HashSet<>();
        for (Method m : ours.getMethods()) {
            if ((m.getModifiers() & Modifier.STATIC) == 0) {
                have.add(shape(m));
            }
        }
        return shapes(jdk, Modifier.STATIC, false).stream().filter(s -> !have.contains(s))
            .collect(Collectors.toCollection(TreeSet::new));
    }

    private static Set<String> shapes(Class<?> c, int staticBit, boolean wantStatic) {
        return Arrays.stream(c.getMethods()).filter(m -> m.getDeclaringClass() != Object.class)
            .filter(m -> !OBJECT_MEMBERS.contains(m.getName()))
            .filter(m -> ((m.getModifiers() & staticBit) != 0) == wantStatic).map(JurApiCompatTest::shape)
            .collect(Collectors.toCollection(TreeSet::new));
    }
}
