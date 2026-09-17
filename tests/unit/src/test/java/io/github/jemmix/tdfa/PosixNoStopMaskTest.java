package io.github.jemmix.tdfa;

import io.github.jemmix.tdfa.tdfa.Tdfa;
import io.github.jemmix.tdfa.tdfa.TdfaRunner;
import io.github.jemmix.tdfa.tnfa.Tnfa;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * POSIX (leftmost-longest) compiles allocate no stop-on-accept table at all:
 * no reader may consult it (Tdfa stores neither stop tier in POSIX mode and
 * every consumer gates on Perl mode), so the former unconditional n*64
 * alloc/fill — ~25 MB of churn at 100 K states — was pure waste (review P2).
 * Both the minimized and the -Dtdfa.nominimize paths must stay null and
 * keep matching correctly.
 */
class PosixNoStopMaskTest {

    @AfterEach
    void clearKnob() {
        System.clearProperty("tdfa.nominimize");
    }

    private static Tdfa posix(String pattern) {
        return Tdfa.compile(Tnfa.compile(pattern), true);
    }

    @Test
    void minimizedPosixCompileHasNoStopTable() {
        Tdfa t = posix("(a|ab)(c|bcd)");
        assertThat(t.stopOnAcceptMask()).as("POSIX artifact must not materialize a stop tier").isNull();
        TdfaRunner r = new TdfaRunner(t);
        assertThat(r.match("abcd", 0)).as("POSIX (a|ab)(c|bcd) matches 'abcd'").isNotNull();
        assertThat(r.match("abcd", 0).end(0)).isEqualTo(4); // leftmost-LONGEST: a+bcd spans "abcd"
    }

    @Test
    void unminimizedPosixCompileHasNoStopTable() {
        System.setProperty("tdfa.nominimize", "true"); // read per compile (knob policy)
        Tdfa t = posix("(a|ab)");
        assertThat(t.stopOnAcceptMask()).isNull();
        TdfaRunner r = new TdfaRunner(t);
        assertThat(r.match("ab", 0)).isNotNull();
        assertThat(r.match("ab", 0).end(0)).isEqualTo(2);
    }

    @Test
    void perlCompileStillMaterializesStopTier() {
        Tdfa t = Tdfa.compile(Tnfa.compile("(a|ab)"));
        assertThat(t.stopOnAcceptMask()).as("Perl artifact keeps its stop tier").isNotNull();
        TdfaRunner r = new TdfaRunner(t);
        assertThat(r.match("ab", 0)).isNotNull();
        assertThat(r.match("ab", 0).end(0)).isEqualTo(1); // leftmost-FIRST: "a"
    }
}
