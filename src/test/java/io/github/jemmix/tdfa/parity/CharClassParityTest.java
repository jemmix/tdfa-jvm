package io.github.jemmix.tdfa.parity;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

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

    @EnabledIfSystemProperty(named = "tdfa.pending", matches = "true") // PENDING: [:ascii:] and [:word:] POSIX classes not implemented
    @Test void posixAscii() { assertSameFind("[[:ascii:]]", "A"); }
    @EnabledIfSystemProperty(named = "tdfa.pending", matches = "true") // PENDING: [:ascii:] and [:word:] POSIX classes not implemented
    @Test void posixAsciiNoMatch() { assertSameFind("[[:ascii:]]", "\u00E9"); }
    @EnabledIfSystemProperty(named = "tdfa.pending", matches = "true") // PENDING: [:ascii:] and [:word:] POSIX classes not implemented
    @Test void posixWord() { assertSameFind("[[:word:]]", "_"); }
    @EnabledIfSystemProperty(named = "tdfa.pending", matches = "true") // PENDING: [:ascii:] and [:word:] POSIX classes not implemented
    @Test void posixWordNoMatch() { assertSameFind("[[:word:]]", "!"); }

    @EnabledIfSystemProperty(named = "tdfa.pending", matches = "true") // PENDING: [:^name:] negated POSIX classes not implemented
    @Test void posixNegatedAlpha() { assertSameFind("[[:^alpha:]]", "5"); }
    @EnabledIfSystemProperty(named = "tdfa.pending", matches = "true") // PENDING: [:^name:] negated POSIX classes not implemented
    @Test void posixNegatedAlphaMatch() { assertSameFind("[[:^alpha:]]", "A"); }
    @EnabledIfSystemProperty(named = "tdfa.pending", matches = "true") // PENDING: [:^name:] negated POSIX classes not implemented
    @Test void posixNegatedDigit() { assertSameFind("[[:^digit:]]", "x"); }
    @EnabledIfSystemProperty(named = "tdfa.pending", matches = "true") // PENDING: [:^name:] negated POSIX classes not implemented
    @Test void posixNegatedDigitNoMatch() { assertSameFind("[[:^digit:]]", "5"); }
    @EnabledIfSystemProperty(named = "tdfa.pending", matches = "true") // PENDING: [:^name:] negated POSIX classes not implemented
    @Test void posixNegatedSpace() { assertSameFind("[[:^space:]]", "x"); }
    @EnabledIfSystemProperty(named = "tdfa.pending", matches = "true") // PENDING: [:^name:] negated POSIX classes not implemented
    @Test void posixNegatedUpper() { assertSameFind("[[:^upper:]]", "a"); }
    @EnabledIfSystemProperty(named = "tdfa.pending", matches = "true") // PENDING: [:^name:] negated POSIX classes not implemented
    @Test void posixNegatedLower() { assertSameFind("[[:^lower:]]", "A"); }
    @EnabledIfSystemProperty(named = "tdfa.pending", matches = "true") // PENDING: [:^name:] negated POSIX classes not implemented
    @Test void posixNegatedCntrl() { assertSameFind("[[:^cntrl:]]", "A"); }
    @EnabledIfSystemProperty(named = "tdfa.pending", matches = "true") // PENDING: [:^name:] negated POSIX classes not implemented
    @Test void posixNegatedPunct() { assertSameFind("[[:^punct:]]", "A"); }
    @EnabledIfSystemProperty(named = "tdfa.pending", matches = "true") // PENDING: [:^name:] negated POSIX classes not implemented
    @Test void posixNegatedXdigit() { assertSameFind("[[:^xdigit:]]", "z"); }
    @EnabledIfSystemProperty(named = "tdfa.pending", matches = "true") // PENDING: [:^name:] negated POSIX classes not implemented
    @Test void posixNegatedBlank() { assertSameFind("[[:^blank:]]", "x"); }
    @EnabledIfSystemProperty(named = "tdfa.pending", matches = "true") // PENDING: [:^name:] negated POSIX classes not implemented
    @Test void posixNegatedGraph() { assertSameFind("[[:^graph:]]", " "); }
    @EnabledIfSystemProperty(named = "tdfa.pending", matches = "true") // PENDING: [:^name:] negated POSIX classes not implemented
    @Test void posixNegatedPrint() { assertSameFind("[[:^print:]]", "\u0001"); }
    @EnabledIfSystemProperty(named = "tdfa.pending", matches = "true") // PENDING: [:^name:] negated POSIX classes not implemented
    @Test void posixNegatedAlnum() { assertSameFind("[[:^alnum:]]", "!"); }
    @EnabledIfSystemProperty(named = "tdfa.pending", matches = "true") // PENDING: [:^name:] negated POSIX classes not implemented
    @Test void posixNegatedAscii() { assertSameFind("[[:^ascii:]]", "\u00E9"); }
    @EnabledIfSystemProperty(named = "tdfa.pending", matches = "true") // PENDING: [:^name:] negated POSIX classes not implemented
    @Test void posixNegatedAsciiMatch() { assertSameFind("[[:^ascii:]]", "A"); }
    @EnabledIfSystemProperty(named = "tdfa.pending", matches = "true") // PENDING: [:^name:] negated POSIX classes not implemented
    @Test void posixNegatedWord() { assertSameFind("[[:^word:]]", "!"); }
    @EnabledIfSystemProperty(named = "tdfa.pending", matches = "true") // PENDING: [:^name:] negated POSIX classes not implemented
    @Test void posixNegatedWordMatch() { assertSameFind("[[:^word:]]", "_"); }

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
    @EnabledIfSystemProperty(named = "tdfa.pending", matches = "true") // PENDING: Unicode table version mismatch (re2j 1.8 vs JDK)
    @Test void supplementaryPlaneInput() {
        String s = new String(Character.toChars(0x1F600));
        assertSameFind("\\p{So}", s);
    }

    // ---- Additional Unicode sub-categories ----

    @Test void unicodeLo() { assertSameFind("\\p{Lo}", "\u4E00"); }       // CJK ideograph
    @Test void unicodeLm() { assertSameFind("\\p{Lm}", "\u02B0"); }       // modifier letter
    @Test void unicodeLt() { assertSameFind("\\p{Lt}", "\u01C5"); }       // titlecase letter (DZ)
    @Test void unicodePs() { assertSameFind("\\p{Ps}", "("); }            // open punctuation
    @Test void unicodePe() { assertSameFind("\\p{Pe}", ")"); }            // close punctuation
    @Test void unicodePi() { assertSameFind("\\p{Pi}", "\u201C"); }      // initial quote "
    @Test void unicodePf() { assertSameFind("\\p{Pf}", "\u201D"); }      // final quote "
    @Test void unicodePo() { assertSameFind("\\p{Po}", "."); }            // other punctuation
    @Test void unicodeMc() { assertSameFind("\\p{Mc}", "\u0903"); }      // spacing combining mark
    @Test void unicodeMe() { assertSameFind("\\p{Me}", "\u0488"); }      // enclosing mark
    @Test void unicodeSo() { assertSameFind("\\p{So}", "\u263A"); }      // other symbol ☺
    @Test void unicodeZl() { assertSameFind("\\p{Zl}", "\u2028"); }      // line separator
    @Test void unicodeZp() { assertSameFind("\\p{Zp}", "\u2029"); }      // paragraph separator
    @Test void unicodeCo() { assertSameFind("\\p{Co}", "\uE000"); }      // private use

    // ---- [\b] rejection (re2j rejects too) ----

    @EnabledIfSystemProperty(named = "tdfa.pending", matches = "true") // PENDING: [b] should be rejected in class context
    @Test void backspaceInClassRejects() { assertSameCompileReject("[\\b]"); }

    // ---- \N{...} rejection (re2j rejects) ----

    @EnabledIfSystemProperty(named = "tdfa.pending", matches = "true") // PENDING: N{...} should be rejected as unknown escape
    @Test void namedEscapeRejects() { assertSameCompileReject("\\N{LATIN SMALL LETTER A}"); }

    // ---- \777 overflow: re2j accepts (wraps to 0xFF) ----

    @Test void octalOverflow() { assertSameFind("\\777", "\u00FF"); }

    // ---- \x{D800} lone surrogate: re2j accepts ----

    @Test void hexSurrogate() { assertSameCompileSuccess("\\x{D800}"); }

    // ---- Long input ----

    @Test void longInputFind() {
        String in = "a".repeat(10000) + "b";
        assertSameFind("a+b", in);
    }
}
