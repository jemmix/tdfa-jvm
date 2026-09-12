package io.github.jemmix.tdfa;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jemmix.tdfa.core.EmittedSurface;
import io.github.jemmix.tdfa.core.MatchResult;
import io.github.jemmix.tdfa.core.Matcher;
import io.github.jemmix.tdfa.tdfa.MatchHolder;
import io.github.jemmix.tdfa.tdfa.RegPool;
import io.github.jemmix.tdfa.tdfa.Tdfa;
import io.github.jemmix.tdfa.tdfa.TdfaRunner;
import io.github.jemmix.tdfa.unicode.UnicodeDataProvider;
import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;

/**
 * The ASM hook surface, enforced. The emitters (TdfaAsmBackend,
 * ShellEmitter) link these members by name through descriptor string
 * constants; renaming or re-signaturing one compiles fine and then breaks
 * pattern compilation with NoSuchMethodError/NoSuchFieldError in
 * production. This test mirrors the emitters' descriptor table: every hook
 * must exist with the exact signature AND carry {@link EmittedSurface}, so
 * drift fails here instead.
 *
 * <p>Keeping the manifest in sync: if you add a name+descriptor string to
 * either emitter, add the row here in the same commit.
 */
class EmittedSurfaceConformanceTest {

    private static void assertMarked(AnnotatedElement m, Class<?> owner, String what) {
        assertThat(m.isAnnotationPresent(EmittedSurface.class)
                || owner.isAnnotationPresent(EmittedSurface.class))
                .as("%s.%s is linked by name from emitted bytecode but carries no @EmittedSurface",
                        owner.getSimpleName(), what)
                .isTrue();
    }

    private static void hookM(Class<?> owner, String name, Class<?>... params) {
        try {
            Method m = owner.getDeclaredMethod(name, params);
            m.setAccessible(true);
            assertMarked(m, owner, name);
        } catch (NoSuchMethodException e) {
            throw new AssertionError("emitted-code hook missing: " + owner.getName()
                    + "." + name + " — an emitter's descriptor constant is now stale", e);
        }
    }

    private static void hookC(Class<?> owner, Class<?>... params) {
        try {
            Constructor<?> c = owner.getDeclaredConstructor(params);
            c.setAccessible(true);
            assertMarked(c, owner, "<init>");
        } catch (NoSuchMethodException e) {
            throw new AssertionError("emitted-code hook missing: " + owner.getName()
                    + "<init> — an emitter's descriptor constant is now stale", e);
        }
    }

    private static void hookF(Class<?> owner, String name) {
        try {
            Field f = owner.getDeclaredField(name);
            f.setAccessible(true);
            assertMarked(f, owner, name);
        } catch (NoSuchFieldException e) {
            throw new AssertionError("emitted-code hook missing: " + owner.getName()
                    + "." + name + " — an emitter's descriptor constant is now stale", e);
        }
    }

    @Test
    void tdfaRunnerHooks() {
        hookC(TdfaRunner.class, Tdfa.class);
        hookM(TdfaRunner.class, "matches", CharSequence.class);
        hookM(TdfaRunner.class, "find", CharSequence.class);
        hookM(TdfaRunner.class, "match", CharSequence.class, int.class);
        hookM(TdfaRunner.class, "startBits");
        hookM(TdfaRunner.class, "candScanMax");
        hookM(TdfaRunner.class, "restartExtract", String.class, int.class, int.class, int.class);
        hookM(TdfaRunner.class, "originSimBudget");
        hookM(TdfaRunner.class, "originSimLeftmost", CharSequence.class, int.class, int.class, int.class);
        hookM(TdfaRunner.class, "triggerScanTop", String.class, int.class, int.class);
        hookM(TdfaRunner.class, "booleanMatchFrom", String.class, int.class, int.class);
        hookM(TdfaRunner.class, "groupCount");
        hookM(TdfaRunner.class, "namedGroups");
        hookM(TdfaRunner.class, "programSize");
        hookM(TdfaRunner.class, "trace", TdfaRunner.Strategy.class);
        hookF(TdfaRunner.class, "ADAPTIVE_PREFILTER_AFTER");
    }

    @Test
    void carrierHooks() {
        hookC(MatchHolder.class, int.class, int.class, int[].class);
        hookF(MatchHolder.class, "matchStart");
        hookF(MatchHolder.class, "matchEnd");
        hookF(MatchHolder.class, "regs");
        hookM(RegPool.class, "take", int.class);
        hookC(MatchResult.class, int[].class, int.class, int.class, int.class, int.class);
        hookM(MatchResult.class, "reconstructFixed", int[].class, int.class, int[].class, int[].class);
        hookM(MatchResult.class, "start", int.class);
        hookM(MatchResult.class, "end", int.class);
    }

    @Test
    void shellHooks() throws Exception {
        // core.Matcher's seven protected fields, linked from emitted shells.
        for (String f : new String[]{"input", "inputLength", "match", "hasMatch",
                "lastMatchStart", "lastMatchEnd", "appendPos"}) {
            hookF(Matcher.class, f);
        }
        // Facade ctors linked by descriptor from ShellEmitter.
        hookC(TDFAPattern.class, String.class, int.class, int.class,
                io.github.jemmix.tdfa.core.RegexEngine.class, io.github.jemmix.tdfa.core.RegexEngine.class,
                UnicodeDataProvider.class);
        hookC(PatternMatcher.class, TDFAPattern.class, CharSequence.class);
        hookM(Class.forName("io.github.jemmix.tdfa.Pattern$Utf8"), "decode", byte[].class);
    }

    @Test
    void emittedInitCopiesTdfaTables() {
        // Generated engine <init>s copy the Tdfa's flat tables through these
        // accessors (one call per array per engine construction).
        hookM(Tdfa.class, "stateMeta");
        hookM(Tdfa.class, "stateBase");
        hookM(Tdfa.class, "ranges");
        hookM(Tdfa.class, "stateEntryMask");
        hookM(Tdfa.class, "stateAcceptMask");
        hookM(Tdfa.class, "wordRanges");
        hookM(Tdfa.class, "fixedBase");
        hookM(Tdfa.class, "fixedOffset");
        hookM(Tdfa.class, "stopOnAcceptMask");
    }
}
