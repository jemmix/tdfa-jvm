package io.github.jemmix.tdfa;

import io.github.jemmix.tdfa.tdfa.Disambiguation;
import io.github.jemmix.tdfa.vm.MatchResult;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * BT22 §6.2 fallback operations: verifies that fallback states correctly
 * preserve capture-group values when the runner steps past an accept and
 * then falls back.
 *
 * <p>Without M3, a clobbering transition out of a final state corrupts the
 * captures that should be reported at fallback. These tests pin down the
 * fix.
 */
class FallbackOpsTest {

    @Test
    void fallbackPreservesCapture() {
        // ([A-Z][a-z]+)+(,)?
        // State after "Hello" is final AND has a non-accepting path on capital
        // letters (start of new word) — a fallback state. The 'W' transition
        // clobbers group 1's open register; without M3, falling back to the
        // "Hello" accept reports group 1 = [5,5) (clobbered) instead of [0,5).
        Regex r = Regex.compile("([A-Z][a-z]+)+(,)?", EngineFactory.VM, Disambiguation.POSIX);
        // Direct accept at "Hello" — no clobbering.
        check(r, "Hello", 0, 5, 0, 5, -1, -1);
        // Fallback after taking the 'W' transition (start of new word that
        // doesn't complete before EOF). M3 must restore group 1 = [0,5).
        check(r, "HelloW", 0, 5, 0, 5, -1, -1);
        // Successful extend through "World" — last iteration wins.
        check(r, "HelloWor", 0, 8, 5, 8, -1, -1);
        check(r, "HelloWorld", 0, 10, 5, 10, -1, -1);
        // Group 2 matched.
        check(r, "Hello,", 0, 6, 0, 5, 5, 6);
        // 9 doesn't continue the outer + (not [A-Z]) and doesn't match , — fallback.
        check(r, "Hello9", 0, 5, 0, 5, -1, -1);
    }

    @Test
    void optionalGroupFallback() {
        // (a)(b)? — after "a" we're at a final state; trying 'b' on a non-'b'
        // char takes us through a non-accepting path before falling back.
        Regex r = Regex.compile("(a)(b)?", EngineFactory.VM, Disambiguation.POSIX);
        check(r, "a", 0, 1, 0, 1, -1, -1);
        check(r, "ab", 0, 2, 0, 1, 1, 2);
        check(r, "ax", 0, 1, 0, 1, -1, -1);
    }

    @Test
    void quantifiedGroupFallback() {
        // (\d+\.)+\d+ — IP-like. After "1.2." we're at an intermediate final
        // state; the trailing \d+ requires digits. If the next char isn't a
        // digit, we fall back to the shorter match "1.2" (one iter + last \d+).
        Regex r = Regex.compile("(\\d+\\.)+\\d+", EngineFactory.VM, Disambiguation.POSIX);
        // Match succeeds all the way: "1.2.3".
        MatchResult m1 = r.find("1.2.3", 0);
        assertNotNull(m1);
        assertEquals(0, m1.start(0));
        assertEquals(5, m1.end(0));
        // Trailing 'x' breaks the final \d+: longest match is "1.2".
        MatchResult m = r.find("1.2.x", 0);
        assertNotNull(m);
        assertEquals(0, m.start(0));
        assertEquals(3, m.end(0));  // "1.2"
    }

    @Test
    void noFallbackWhenDfaIsTotal() {
        // Lexer-style alternation: last branch is '.', so every char matches
        // somewhere. No fallback states should be needed.
        Regex r = Regex.compile("(\\w+)|(.)", EngineFactory.VM, Disambiguation.POSIX);
        check(r, "abc123", 0, 6, 0, 6, -1, -1);
        check(r, "!", 0, 1, -1, -1, 0, 1);
    }

    private static void check(Regex r, String input,
                              int g0s, int g0e, int g1s, int g1e, int g2s, int g2e) {
        MatchResult m = r.find(input, 0);
        assertNotNull(m, "expected match for \"" + input + "\"");
        assertEquals(g0s, m.start(0), "g0 start for \"" + input + "\"");
        assertEquals(g0e, m.end(0), "g0 end for \"" + input + "\"");
        assertEquals(g1s, m.start(1), "g1 start for \"" + input + "\"");
        assertEquals(g1e, m.end(1), "g1 end for \"" + input + "\"");
        assertEquals(g2s, m.start(2), "g2 start for \"" + input + "\"");
        assertEquals(g2e, m.end(2), "g2 end for \"" + input + "\"");
    }
}

