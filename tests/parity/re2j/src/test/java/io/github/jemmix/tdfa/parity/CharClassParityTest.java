package io.github.jemmix.tdfa.parity;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import io.github.jemmix.tdfa.EngineFactory;

import static io.github.jemmix.tdfa.parity.Re2jOracle.*;

/**
 * Character class parity: [abc], [a-z], [^...], POSIX classes, Unicode \p{},
 * shorthand \d\w\s inside classes, leading-] edge cases.
 */
class CharClassParityTest {

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void basicClass(EngineFactory factory) { assertSameFind("[abc]", "a", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void basicClass2(EngineFactory factory) { assertSameFind("[abc]", "d", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void classRange(EngineFactory factory) { assertSameFind("[a-z]", "f", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void classRangeUpper(EngineFactory factory) { assertSameFind("[A-Z]", "G", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void negatedClass(EngineFactory factory) { assertSameFind("[^abc]", "d", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void negatedClassNoMatch(EngineFactory factory) { assertSameFind("[^abc]", "a", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void negatedClassRange(EngineFactory factory) { assertSameFind("[^0-9]", "x", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void classWithDigit(EngineFactory factory) { assertSameFind("[\\d]", "5", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void classWithWord(EngineFactory factory) { assertSameFind("[\\w]", "_", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void classWithSpace(EngineFactory factory) { assertSameFind("[\\s]", " ", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void classMultipleRanges(EngineFactory factory) { assertSameFind("[a-zA-Z0-9_]", "Z", factory); }

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void leadingCloseBracket(EngineFactory factory) { assertSameFind("[]]", "]", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void leadingCloseBracketWithChars(EngineFactory factory) { assertSameFind("[]a]", "]", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void leadingCloseBracketNegated(EngineFactory factory) { assertSameFind("[^]]", "a", factory); }

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void posixAlpha(EngineFactory factory) { assertSameFind("[[:alpha:]]", "g", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void posixDigit(EngineFactory factory) { assertSameFind("[[:digit:]]", "7", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void posixDigitNoMatch(EngineFactory factory) { assertSameFind("[[:digit:]]", "x", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void posixSpace(EngineFactory factory) { assertSameFind("[[:space:]]", "\t", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void posixSpaceV(EngineFactory factory) { assertSameFind("[[:space:]]", "\u000B", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void posixUpper(EngineFactory factory) { assertSameFind("[[:upper:]]", "H", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void posixLower(EngineFactory factory) { assertSameFind("[[:lower:]]", "h", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void posixAlnum(EngineFactory factory) { assertSameFind("[[:alnum:]]", "9", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void posixPunct(EngineFactory factory) { assertSameFind("[[:punct:]]", "!", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void posixXdigit(EngineFactory factory) { assertSameFind("[[:xdigit:]]", "f", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void posixBlank(EngineFactory factory) { assertSameFind("[[:blank:]]", "\t", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void posixCntrl(EngineFactory factory) { assertSameFind("[[:cntrl:]]", "\u0001", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void posixGraph(EngineFactory factory) { assertSameFind("[[:graph:]]", "A", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void posixPrint(EngineFactory factory) { assertSameFind("[[:print:]]", " ", factory); }

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void unicodeCategoryL(EngineFactory factory) { assertSameFind("\\p{L}", "A", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void unicodeCategoryLu(EngineFactory factory) { assertSameFind("\\p{Lu}", "A", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void unicodeCategoryLl(EngineFactory factory) { assertSameFind("\\p{Ll}", "a", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void unicodeCategoryNd(EngineFactory factory) { assertSameFind("\\p{Nd}", "5", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void unicodeScriptGreek(EngineFactory factory) { assertSameFind("\\p{Greek}", "\u03B1", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void unicodeNegated(EngineFactory factory) { assertSameFind("\\P{Nd}", "x", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void unicodeSingleLetter(EngineFactory factory) { assertSameFind("\\pL", "A", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void unicodeAny(EngineFactory factory) { assertSameFind("\\p{Any}", "x", factory); }

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void negatedClassWithInput(EngineFactory factory) { assertSameFind("[^a-z]", "A", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void negatedWithSpecial(EngineFactory factory) { assertSameFind("[^.]", "x", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void classWithDashAtEnd(EngineFactory factory) { assertSameFind("[a-]", "-", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void classWithDashAtStart(EngineFactory factory) { assertSameFind("[-a]", "-", factory); }

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void findAllWithClass(EngineFactory factory) { assertSameAllMatches("[aeiou]", "hello world", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void findAllNegated(EngineFactory factory) { assertSameAllMatches("[^aeiou ]", "hello world", factory); }

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void unicodeNonBmpCodepoint(EngineFactory factory) {
        assertSameFind("\\x{10000}", new String(Character.toChars(0x10000)), factory);
    }

    // ---- POSIX classes: ascii/word + [:^name:] negation ----

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void posixAscii(EngineFactory factory) { assertSameFind("[[:ascii:]]", "A", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void posixAsciiNoMatch(EngineFactory factory) { assertSameFind("[[:ascii:]]", "\u00E9", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void posixWord(EngineFactory factory) { assertSameFind("[[:word:]]", "_", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void posixWordNoMatch(EngineFactory factory) { assertSameFind("[[:word:]]", "!", factory); }

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void posixNegatedAlpha(EngineFactory factory) { assertSameFind("[[:^alpha:]]", "5", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void posixNegatedAlphaMatch(EngineFactory factory) { assertSameFind("[[:^alpha:]]", "A", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void posixNegatedDigit(EngineFactory factory) { assertSameFind("[[:^digit:]]", "x", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void posixNegatedDigitNoMatch(EngineFactory factory) { assertSameFind("[[:^digit:]]", "5", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void posixNegatedSpace(EngineFactory factory) { assertSameFind("[[:^space:]]", "x", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void posixNegatedUpper(EngineFactory factory) { assertSameFind("[[:^upper:]]", "a", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void posixNegatedLower(EngineFactory factory) { assertSameFind("[[:^lower:]]", "A", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void posixNegatedCntrl(EngineFactory factory) { assertSameFind("[[:^cntrl:]]", "A", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void posixNegatedPunct(EngineFactory factory) { assertSameFind("[[:^punct:]]", "A", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void posixNegatedXdigit(EngineFactory factory) { assertSameFind("[[:^xdigit:]]", "z", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void posixNegatedBlank(EngineFactory factory) { assertSameFind("[[:^blank:]]", "x", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void posixNegatedGraph(EngineFactory factory) { assertSameFind("[[:^graph:]]", " ", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void posixNegatedPrint(EngineFactory factory) { assertSameFind("[[:^print:]]", "\u0001", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void posixNegatedAlnum(EngineFactory factory) { assertSameFind("[[:^alnum:]]", "!", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void posixNegatedAscii(EngineFactory factory) { assertSameFind("[[:^ascii:]]", "\u00E9", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void posixNegatedAsciiMatch(EngineFactory factory) { assertSameFind("[[:^ascii:]]", "A", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void posixNegatedWord(EngineFactory factory) { assertSameFind("[[:^word:]]", "!", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void posixNegatedWordMatch(EngineFactory factory) { assertSameFind("[[:^word:]]", "_", factory); }

    // ---- Unicode general categories ----

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void unicodeN(EngineFactory factory) { assertSameFind("\\p{N}", "5", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void unicodeNl(EngineFactory factory) { assertSameFind("\\p{Nl}", "\u2160", factory); }     // Roman numeral I
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void unicodeNo(EngineFactory factory) { assertSameFind("\\p{No}", "\u00BD", factory); }     // ½
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void unicodeM(EngineFactory factory) { assertSameFind("\\p{M}", "\u0300", factory); }       // combining grave
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void unicodeMn(EngineFactory factory) { assertSameFind("\\p{Mn}", "\u0300", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void unicodeS(EngineFactory factory) { assertSameFind("\\p{S}", "+", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void unicodeSm(EngineFactory factory) { assertSameFind("\\p{Sm}", "+", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void unicodeSc(EngineFactory factory) { assertSameFind("\\p{Sc}", "$", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void unicodeSk(EngineFactory factory) { assertSameFind("\\p{Sk}", "\u005E", factory); }     // circumflex
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void unicodeP(EngineFactory factory) { assertSameFind("\\p{P}", ".", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void unicodePd(EngineFactory factory) { assertSameFind("\\p{Pd}", "-", factory); }          // dash punctuation
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void unicodePc(EngineFactory factory) { assertSameFind("\\p{Pc}", "_", factory); }          // connector punctuation
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void unicodeZ(EngineFactory factory) { assertSameFind("\\p{Z}", " ", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void unicodeZs(EngineFactory factory) { assertSameFind("\\p{Zs}", " ", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void unicodeC(EngineFactory factory) { assertSameFind("\\p{C}", "\u0000", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void unicodeCf(EngineFactory factory) { assertSameFind("\\p{Cf}", "\u200B", factory); }     // zero-width space
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void unicodeCc(EngineFactory factory) { assertSameFind("\\p{Cc}", "\u0001", factory); }

    // ---- Unicode scripts ----

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void unicodeScriptLatin(EngineFactory factory) { assertSameFind("\\p{Latin}", "A", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void unicodeScriptCyrillic(EngineFactory factory) { assertSameFind("\\p{Cyrillic}", "\u0410", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void unicodeScriptHan(EngineFactory factory) { assertSameFind("\\p{Han}", "\u4E00", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void unicodeScriptHiragana(EngineFactory factory) { assertSameFind("\\p{Hiragana}", "\u3042", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void unicodeScriptKatakana(EngineFactory factory) { assertSameFind("\\p{Katakana}", "\u30A2", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void unicodeScriptArabic(EngineFactory factory) { assertSameFind("\\p{Arabic}", "\u0627", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void unicodeScriptHebrew(EngineFactory factory) { assertSameFind("\\p{Hebrew}", "\u05D0", factory); }

    // ---- Internal negation syntax \p{^X} ----

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void unicodeInternalNegation(EngineFactory factory) { assertSameFind("\\p{^Nd}", "x", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void unicodeInternalNegationMatch(EngineFactory factory) { assertSameFind("\\p{^Nd}", "5", factory); }

    // ---- Edge cases ----

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void nullByteInClass(EngineFactory factory) { assertSameFind("[\\x00]", "\u0000", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void literalDotInClass(EngineFactory factory) { assertSameFind("[.]", ".", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void literalPlusInClass(EngineFactory factory) { assertSameFind("[+]", "+", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void literalPipeInClass(EngineFactory factory) { assertSameFind("[|]", "|", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void literalParenInClass(EngineFactory factory) { assertSameFind("[(]", "(", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void nestedPosixInClass(EngineFactory factory) { assertSameFind("[a-z[:digit:]]", "5", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void supplementaryPlaneInput(EngineFactory factory) {
        String s = new String(Character.toChars(0x1F600));
        assertSameFind("\\p{So}", s, factory);
    }

    // ---- Additional Unicode sub-categories ----

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void unicodeLo(EngineFactory factory) { assertSameFind("\\p{Lo}", "\u4E00", factory); }       // CJK ideograph
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void unicodeLm(EngineFactory factory) { assertSameFind("\\p{Lm}", "\u02B0", factory); }       // modifier letter
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void unicodeLt(EngineFactory factory) { assertSameFind("\\p{Lt}", "\u01C5", factory); }       // titlecase letter (DZ)
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void unicodePs(EngineFactory factory) { assertSameFind("\\p{Ps}", "(", factory); }            // open punctuation
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void unicodePe(EngineFactory factory) { assertSameFind("\\p{Pe}", ")", factory); }            // close punctuation
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void unicodePi(EngineFactory factory) { assertSameFind("\\p{Pi}", "\u201C", factory); }      // initial quote "
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void unicodePf(EngineFactory factory) { assertSameFind("\\p{Pf}", "\u201D", factory); }      // final quote "
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void unicodePo(EngineFactory factory) { assertSameFind("\\p{Po}", ".", factory); }            // other punctuation
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void unicodeMc(EngineFactory factory) { assertSameFind("\\p{Mc}", "\u0903", factory); }      // spacing combining mark
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void unicodeMe(EngineFactory factory) { assertSameFind("\\p{Me}", "\u0488", factory); }      // enclosing mark
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void unicodeSo(EngineFactory factory) { assertSameFind("\\p{So}", "\u263A", factory); }      // other symbol ☺
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void unicodeZl(EngineFactory factory) { assertSameFind("\\p{Zl}", "\u2028", factory); }      // line separator
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void unicodeZp(EngineFactory factory) { assertSameFind("\\p{Zp}", "\u2029", factory); }      // paragraph separator
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void unicodeCo(EngineFactory factory) { assertSameFind("\\p{Co}", "\uE000", factory); }      // private use

    // ---- [\b] rejection (re2j rejects too) ----

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void backspaceInClassRejects(EngineFactory factory) { assertSameCompileReject("[\\b]", factory); }

    // ---- \N{...} rejection (re2j rejects) ----

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void namedEscapeRejects(EngineFactory factory) { assertSameCompileReject("\\N{LATIN SMALL LETTER A}", factory); }

    // ---- \777 overflow: re2j accepts (wraps to 0xFF) ----

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void octalOverflow(EngineFactory factory) { assertSameFind("\\777", "\u00FF", factory); }

    // ---- \x{D800} lone surrogate: re2j accepts ----

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void hexSurrogate(EngineFactory factory) { assertSameCompileSuccess("\\x{D800}", factory); }

    // ---- Long input ----

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void longInputFind(EngineFactory factory) {
        String in = "a".repeat(10000) + "b";
        assertSameFind("a+b", in, factory);
    }
}
