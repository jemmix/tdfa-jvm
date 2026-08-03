package io.github.jemmix.tdfa.parity;

import org.junit.jupiter.api.Test;

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

    @Test void unicodeNonBmpCodepoint() {
        assertSameFind("\\x{10000}", new String(Character.toChars(0x10000)));
    }

    // ---- POSIX classes (pending parity: [:ascii:], [:word:], [:^name:]) ----

    @Test void posixAscii() { assertSameFind("[[:ascii:]]", "A"); }
    @Test void posixAsciiNoMatch() { assertSameFind("[[:ascii:]]", "\u00E9"); }
    @Test void posixWord() { assertSameFind("[[:word:]]", "_"); }
    @Test void posixWordNoMatch() { assertSameFind("[[:word:]]", "!"); }

    @Test void posixNegatedAlpha() { assertSameFind("[[:^alpha:]]", "5"); }
    @Test void posixNegatedAlphaMatch() { assertSameFind("[[:^alpha:]]", "A"); }
    @Test void posixNegatedDigit() { assertSameFind("[[:^digit:]]", "x"); }
    @Test void posixNegatedDigitNoMatch() { assertSameFind("[[:^digit:]]", "5"); }
    @Test void posixNegatedSpace() { assertSameFind("[[:^space:]]", "x"); }
    @Test void posixNegatedUpper() { assertSameFind("[[:^upper:]]", "a"); }
    @Test void posixNegatedLower() { assertSameFind("[[:^lower:]]", "A"); }
    @Test void posixNegatedCntrl() { assertSameFind("[[:^cntrl:]]", "A"); }
    @Test void posixNegatedPunct() { assertSameFind("[[:^punct:]]", "A"); }
    @Test void posixNegatedXdigit() { assertSameFind("[[:^xdigit:]]", "z"); }
    @Test void posixNegatedBlank() { assertSameFind("[[:^blank:]]", "x"); }
    @Test void posixNegatedGraph() { assertSameFind("[[:^graph:]]", " "); }
    @Test void posixNegatedPrint() { assertSameFind("[[:^print:]]", "\u0001"); }
    @Test void posixNegatedAlnum() { assertSameFind("[[:^alnum:]]", "!"); }

    // ---- Unicode general categories ----

    @Test void unicodeN() { assertSameFind("\\p{N}", "5"); }
    @Test void unicodeNl() { assertSameFind("\\p{Nl}", "\u2160"); }     // Roman numeral I
    @Test void unicodeNo() { assertSameFind("\\p{No}", "\u00BD"); }     // ½
    @Test void unicodeM() { assertSameFind("\\p{M}", "\u0300"); }       // combining grave
    @Test void unicodeMn() { assertSameFind("\\p{Mn}", "\u0300"); }
    @Test void unicodeS() { assertSameFind("\\p{S}", "+"); }
    @Test void unicodeSm() { assertSameFind("\\p{Sm}", "+"); }
    @Test void unicodeSc() { assertSameFind("\\p{Sc}", "$"); }
    @Test void unicodeSk() { assertSameFind("\\p{Sk}", "\u005E"); }     // circumflex
    @Test void unicodeP() { assertSameFind("\\p{P}", "."); }
    @Test void unicodePd() { assertSameFind("\\p{Pd}", "-"); }          // dash punctuation
    @Test void unicodePc() { assertSameFind("\\p{Pc}", "_"); }          // connector punctuation
    @Test void unicodeZ() { assertSameFind("\\p{Z}", " "); }
    @Test void unicodeZs() { assertSameFind("\\p{Zs}", " "); }
    @Test void unicodeC() { assertSameFind("\\p{C}", "\u0000"); }
    @Test void unicodeCf() { assertSameFind("\\p{Cf}", "\u200B"); }     // zero-width space
    @Test void unicodeCc() { assertSameFind("\\p{Cc}", "\u0001"); }

    // ---- Unicode scripts ----

    @Test void unicodeScriptLatin() { assertSameFind("\\p{Latin}", "A"); }
    @Test void unicodeScriptCyrillic() { assertSameFind("\\p{Cyrillic}", "\u0410"); }
    @Test void unicodeScriptHan() { assertSameFind("\\p{Han}", "\u4E00"); }
    @Test void unicodeScriptHiragana() { assertSameFind("\\p{Hiragana}", "\u3042"); }
    @Test void unicodeScriptKatakana() { assertSameFind("\\p{Katakana}", "\u30A2"); }
    @Test void unicodeScriptArabic() { assertSameFind("\\p{Arabic}", "\u0627"); }
    @Test void unicodeScriptHebrew() { assertSameFind("\\p{Hebrew}", "\u05D0"); }

    // ---- Internal negation syntax \p{^X} ----

    @Test void unicodeInternalNegation() { assertSameFind("\\p{^Nd}", "x"); }
    @Test void unicodeInternalNegationMatch() { assertSameFind("\\p{^Nd}", "5"); }

    // ---- Edge cases ----

    @Test void nullByteInClass() { assertSameFind("[\\x00]", "\u0000"); }
    @Test void literalDotInClass() { assertSameFind("[.]", "."); }
    @Test void literalPlusInClass() { assertSameFind("[+]", "+"); }
    @Test void literalPipeInClass() { assertSameFind("[|]", "|"); }
    @Test void literalParenInClass() { assertSameFind("[(]", "("); }
    @Test void nestedPosixInClass() { assertSameFind("[a-z[:digit:]]", "5"); }
    @Test void supplementaryPlaneInput() {
        String s = new String(Character.toChars(0x1F600));
        assertSameFind("\\p{S}", s);
    }
}
