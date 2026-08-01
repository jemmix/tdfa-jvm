package io.github.jemmix.tdfa.tdfa;

import io.github.jemmix.tdfa.Regex;
import io.github.jemmix.tdfa.tnfa.Tnfa;
import io.github.jemmix.tdfa.vm.MatchResult;
import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertNotNull;

/**
 * Validates {@link PosixNfaSimulator} against the 5 testFowlerBasic cases that
 * the existing TDFA heuristic gets wrong. The simulator isn't wired into
 * {@link Regex#compile} by default (it regresses on testRE2Exhaustive); these
 * tests prove the BT19 algorithm itself is sound for the target cases.
 *
 * <p>When the BT19 closure is integrated into {@link Tdfa}'s determinization
 * (Stage 2 per docs/BT19.md), these tests will pass via the standard
 * {@link TdfaRunner} path and this file can be deleted.
 */
public class PosixNfaSimulatorTest {

    private static MatchResult match(String pattern, String text) {
        Tnfa nfa = Tnfa.compile(pattern);
        PosixNfaSimulator sim = new PosixNfaSimulator(nfa);
        return sim.match(text, 0);
    }

    private static int[] groups(MatchResult m) {
        int[] out = new int[2 * (m.groupCount() + 1)];
        out[0] = m.start(0);
        out[1] = m.end(0);
        for (int g = 1; g <= m.groupCount(); g++) {
            out[2 * g] = m.start(g);
            out[2 * g + 1] = m.end(g);
        }
        return out;
    }

    @Test public void case29_aStarBOptBPlusB3() {
        // (a*)(b?)(b+)b{3} on "aaabbbbbbb" — POSIX leftmost-longest submatch.
        MatchResult m = match("(a*)(b?)(b+)b{3}", "aaabbbbbbb");
        assertNotNull(m);
        // Expected: g1=[0,3], g2=[3,4], g3=[4,7]
        assertArrayEquals(new int[]{0, 10, 0, 3, 3, 4, 4, 7}, groups(m));
    }

    @Test public void case137_starClassBcd() {
        // ([abc])*bcd on "abcd" — POSIX submatch for *-repeated groups.
        MatchResult m = match("([abc])*bcd", "abcd");
        assertNotNull(m);
        assertArrayEquals(new int[]{0, 4, 0, 1}, groups(m));
    }

    @Test public void case34_aStarAltAA() {
        // a*(a.|aa) on "aaaa" — POSIX alternation longest-branch.
        MatchResult m = match("a*(a.|aa)", "aaaa");
        assertNotNull(m);
        assertArrayEquals(new int[]{0, 4, 2, 4}, groups(m));
    }

    @Test public void sanity_aStar() {
        // (a)* on "aaa" — POSIX leftmost-longest for repetition.
        MatchResult m = match("(a)*", "aaa");
        assertNotNull(m);
        assertArrayEquals(new int[]{0, 3, 2, 3}, groups(m));
    }

    @Test public void sanity_aStarB() {
        // (a)*b on "aaab" — POSIX submatch extraction with trailing literal.
        MatchResult m = match("(a)*b", "aaab");
        assertNotNull(m);
        assertArrayEquals(new int[]{0, 4, 2, 3}, groups(m));
    }
}
