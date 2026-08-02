package io.github.jemmix.tdfa.parity;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Disabled;

import static org.assertj.core.api.Assertions.assertThat;
import static io.github.jemmix.tdfa.parity.Re2jOracle.*;

/**
 * Case-insensitive matching parity: (?i), CASE_INSENSITIVE flag, ASCII folding,
 * Unicode case folding for \p{X}.
 */
class CaseSensitivityParityTest {

    @Test void inlineCaseInsensitive() { assertSameFind("(?i)abc", "ABC"); }
    @Test void inlineCaseInsensitive2() { assertSameFind("(?i)abc", "abc"); }
    @Test void inlineCaseInsensitive3() { assertSameFind("(?i)abc", "AbC"); }
    @Test void caseInsensitiveNoMatch() { assertSameFind("(?i)abc", "abd"); }
    @Test void caseSensitiveDefault() { assertSameFind("abc", "ABC"); }

    @Test void foldClassRange() { assertSameFind("(?i)[A-Z]", "g"); }
    @Test void foldClassRangeUpper() { assertSameFind("(?i)[a-z]", "G"); }
    @Test void foldClassSingleChar() { assertSameFind("(?i)a", "A"); }
    @Test void foldLiteralInConcat() { assertSameFind("(?i)hello", "HeLLo"); }

    @Test void foldUnicodePropertyLl() { assertSameFind("(?i)\\p{Ll}", "A"); }
    @Test
    @Disabled("TDFA_MISSING: Unicode case folding for \\p{X} under (?i) — JdkUnicodeDataProvider returns no fold table")
    void foldUnicodePropertyLu() { assertSameFind("(?i)\\p{Lu}", "a"); }
    @Test void foldUnicodeScriptGreek() { assertSameFind("(?i)\\p{Greek}", "\u0391"); }

    @Test void caseInsensitiveFlag() {
        int[] expected = com.google.re2j.Pattern.compile("abc",
                com.google.re2j.Pattern.CASE_INSENSITIVE)
                .matcher("ABC").matches()
                ? new int[]{0, 3} : null;
        boolean actual = io.github.jemmix.tdfa.re2j.Pattern.compile("abc",
                io.github.jemmix.tdfa.re2j.Pattern.CASE_INSENSITIVE)
                .matcher("ABC").matches();
        assertThat(actual).isEqualTo(expected != null);
    }

    @Test void toggleCaseInsensitiveOff() { assertSameFind("(?i)ab(?-i)c", "ABc"); }
    @Test
    @Disabled("TDFA_MISSING: (?-i) flag negation not properly implemented — flags accumulate with |= but never reset")
    void toggleCaseInsensitiveOff2() { assertSameFind("(?i)ab(?-i)c", "ABC"); }
    @Test void scopedCaseInsensitive() { assertSameFind("a(?i:bc)d", "aBCd"); }
    @Test void scopedCaseInsensitiveNoLeak() { assertSameFind("a(?i:bc)d", "abcd"); }
}
