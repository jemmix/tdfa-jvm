package io.github.jemmix.tdfa.parity;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Disabled;

import static io.github.jemmix.tdfa.parity.Re2jOracle.*;

/**
 * Character class parity: [abc], [a-z], [^...], POSIX classes, Unicode \p{},
 * shorthand \d\w\s inside classes, leading-] edge cases.
 */
class CharClassParityTest {

    @Test void basicClass() { assertSameFind("[abc]", "a"); }
    @Test void basicClass2() { assertSameFind("[abc]", "d"); }
    @Test void classRange() { assertSameFind("[a-z]", "f"); }
    @Test void classRangeUpper() { assertSameFind("[A-Z]", "G"); }
    @Test void negatedClass() { assertSameFind("[^abc]", "d"); }
    @Test void negatedClassNoMatch() { assertSameFind("[^abc]", "a"); }
    @Test void negatedClassRange() { assertSameFind("[^0-9]", "x"); }
    @Test void classWithDigit() { assertSameFind("[\\d]", "5"); }
    @Test void classWithWord() { assertSameFind("[\\w]", "_"); }
    @Test void classWithSpace() { assertSameFind("[\\s]", " "); }
    @Test void classMultipleRanges() { assertSameFind("[a-zA-Z0-9_]", "Z"); }

    @Test void leadingCloseBracket() { assertSameFind("[]]", "]"); }
    @Test void leadingCloseBracketWithChars() { assertSameFind("[]a]", "]"); }
    @Test void leadingCloseBracketNegated() { assertSameFind("[^]]", "a"); }

    @Test void posixAlpha() { assertSameFind("[[:alpha:]]", "g"); }
    @Test void posixDigit() { assertSameFind("[[:digit:]]", "7"); }
    @Test void posixDigitNoMatch() { assertSameFind("[[:digit:]]", "x"); }
    @Test void posixSpace() { assertSameFind("[[:space:]]", "\t"); }
    @Test void posixSpaceV() { assertSameFind("[[:space:]]", "\u000B"); }
    @Test void posixUpper() { assertSameFind("[[:upper:]]", "H"); }
    @Test void posixLower() { assertSameFind("[[:lower:]]", "h"); }
    @Test void posixAlnum() { assertSameFind("[[:alnum:]]", "9"); }
    @Test void posixPunct() { assertSameFind("[[:punct:]]", "!"); }
    @Test void posixXdigit() { assertSameFind("[[:xdigit:]]", "f"); }
    @Test void posixBlank() { assertSameFind("[[:blank:]]", "\t"); }
    @Test void posixCntrl() { assertSameFind("[[:cntrl:]]", "\u0001"); }
    @Test void posixGraph() { assertSameFind("[[:graph:]]", "A"); }
    @Test void posixPrint() { assertSameFind("[[:print:]]", " "); }

    @Test void unicodeCategoryL() { assertSameFind("\\p{L}", "A"); }
    @Test void unicodeCategoryLu() { assertSameFind("\\p{Lu}", "A"); }
    @Test void unicodeCategoryLl() { assertSameFind("\\p{Ll}", "a"); }
    @Test void unicodeCategoryNd() { assertSameFind("\\p{Nd}", "5"); }
    @Test void unicodeScriptGreek() { assertSameFind("\\p{Greek}", "\u03B1"); }
    @Test void unicodeNegated() { assertSameFind("\\P{Nd}", "x"); }
    @Test void unicodeSingleLetter() { assertSameFind("\\pL", "A"); }
    @Test void unicodeAny() { assertSameFind("\\p{Any}", "x"); }

    @Test void negatedClassWithInput() { assertSameFind("[^a-z]", "A"); }
    @Test void negatedWithSpecial() { assertSameFind("[^.]", "x"); }
    @Test void classWithDashAtEnd() { assertSameFind("[a-]", "-"); }
    @Test void classWithDashAtStart() { assertSameFind("[-a]", "-"); }

    @Test void findAllWithClass() { assertSameAllMatches("[aeiou]", "hello world"); }
    @Test void findAllNegated() { assertSameAllMatches("[^aeiou ]", "hello world"); }

    @Test
    @Disabled("TDFA_MISSING: non-BMP codepoint \\x{10000} rejected — CharClass is char-based")
    void unicodeNonBmpCodepoint() {
        assertSameFind("\\x{10000}", new String(Character.toChars(0x10000)));
    }
}
