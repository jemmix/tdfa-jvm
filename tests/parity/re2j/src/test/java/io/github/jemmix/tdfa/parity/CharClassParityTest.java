package io.github.jemmix.tdfa.parity;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import io.github.jemmix.tdfa.core.Matcher;
import io.github.jemmix.tdfa.core.PatternSyntaxException;
import io.github.jemmix.tdfa.core.RegexEngineFactory;

import static io.github.jemmix.tdfa.parity.Re2jOracle.*;

/**
 * Character class parity: [abc], [a-z], [^...], POSIX classes, Unicode \p{},
 * shorthand \d\w\s inside classes, leading-] edge cases.
 */
class CharClassParityTest {

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void basicClass(RegexEngineFactory factory) { assertSameFind("[abc]", "a", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void basicClass2(RegexEngineFactory factory) { assertSameFind("[abc]", "d", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void classRange(RegexEngineFactory factory) { assertSameFind("[a-z]", "f", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void classRangeUpper(RegexEngineFactory factory) { assertSameFind("[A-Z]", "G", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void negatedClass(RegexEngineFactory factory) { assertSameFind("[^abc]", "d", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void negatedClassNoMatch(RegexEngineFactory factory) { assertSameFind("[^abc]", "a", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void negatedClassRange(RegexEngineFactory factory) { assertSameFind("[^0-9]", "x", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void classWithDigit(RegexEngineFactory factory) { assertSameFind("[\\d]", "5", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void classWithWord(RegexEngineFactory factory) { assertSameFind("[\\w]", "_", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void classWithSpace(RegexEngineFactory factory) { assertSameFind("[\\s]", " ", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void classMultipleRanges(RegexEngineFactory factory) { assertSameFind("[a-zA-Z0-9_]", "Z", factory); }

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void leadingCloseBracket(RegexEngineFactory factory) { assertSameFind("[]]", "]", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void leadingCloseBracketWithChars(RegexEngineFactory factory) { assertSameFind("[]a]", "]", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void leadingCloseBracketNegated(RegexEngineFactory factory) { assertSameFind("[^]]", "a", factory); }

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void posixAlpha(RegexEngineFactory factory) { assertSameFind("[[:alpha:]]", "g", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void posixDigit(RegexEngineFactory factory) { assertSameFind("[[:digit:]]", "7", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void posixDigitNoMatch(RegexEngineFactory factory) { assertSameFind("[[:digit:]]", "x", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void posixSpace(RegexEngineFactory factory) { assertSameFind("[[:space:]]", "\t", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void posixSpaceV(RegexEngineFactory factory) { assertSameFind("[[:space:]]", "\u000B", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void posixUpper(RegexEngineFactory factory) { assertSameFind("[[:upper:]]", "H", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void posixLower(RegexEngineFactory factory) { assertSameFind("[[:lower:]]", "h", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void posixAlnum(RegexEngineFactory factory) { assertSameFind("[[:alnum:]]", "9", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void posixPunct(RegexEngineFactory factory) { assertSameFind("[[:punct:]]", "!", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void posixXdigit(RegexEngineFactory factory) { assertSameFind("[[:xdigit:]]", "f", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void posixBlank(RegexEngineFactory factory) { assertSameFind("[[:blank:]]", "\t", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void posixCntrl(RegexEngineFactory factory) { assertSameFind("[[:cntrl:]]", "\u0001", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void posixGraph(RegexEngineFactory factory) { assertSameFind("[[:graph:]]", "A", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void posixPrint(RegexEngineFactory factory) { assertSameFind("[[:print:]]", " ", factory); }

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void unicodeCategoryL(RegexEngineFactory factory) { assertSameFind("\\p{L}", "A", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void unicodeCategoryLu(RegexEngineFactory factory) { assertSameFind("\\p{Lu}", "A", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void unicodeCategoryLl(RegexEngineFactory factory) { assertSameFind("\\p{Ll}", "a", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void unicodeCategoryNd(RegexEngineFactory factory) { assertSameFind("\\p{Nd}", "5", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void unicodeScriptGreek(RegexEngineFactory factory) { assertSameFind("\\p{Greek}", "\u03B1", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void unicodeNegated(RegexEngineFactory factory) { assertSameFind("\\P{Nd}", "x", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void unicodeSingleLetter(RegexEngineFactory factory) { assertSameFind("\\pL", "A", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void unicodeAny(RegexEngineFactory factory) { assertSameFind("\\p{Any}", "x", factory); }

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void negatedClassWithInput(RegexEngineFactory factory) { assertSameFind("[^a-z]", "A", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void negatedWithSpecial(RegexEngineFactory factory) { assertSameFind("[^.]", "x", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void classWithDashAtEnd(RegexEngineFactory factory) { assertSameFind("[a-]", "-", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void classWithDashAtStart(RegexEngineFactory factory) { assertSameFind("[-a]", "-", factory); }

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void findAllWithClass(RegexEngineFactory factory) { assertSameAllMatches("[aeiou]", "hello world", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void findAllNegated(RegexEngineFactory factory) { assertSameAllMatches("[^aeiou ]", "hello world", factory); }

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void unicodeNonBmpCodepoint(RegexEngineFactory factory) {
        assertSameFind("\\x{10000}", new String(Character.toChars(0x10000)), factory);
    }

    // ---- POSIX classes: ascii/word + [:^name:] negation ----

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void posixAscii(RegexEngineFactory factory) { assertSameFind("[[:ascii:]]", "A", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void posixAsciiNoMatch(RegexEngineFactory factory) { assertSameFind("[[:ascii:]]", "\u00E9", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void posixWord(RegexEngineFactory factory) { assertSameFind("[[:word:]]", "_", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void posixWordNoMatch(RegexEngineFactory factory) { assertSameFind("[[:word:]]", "!", factory); }

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void posixNegatedAlpha(RegexEngineFactory factory) { assertSameFind("[[:^alpha:]]", "5", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void posixNegatedAlphaMatch(RegexEngineFactory factory) { assertSameFind("[[:^alpha:]]", "A", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void posixNegatedDigit(RegexEngineFactory factory) { assertSameFind("[[:^digit:]]", "x", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void posixNegatedDigitNoMatch(RegexEngineFactory factory) { assertSameFind("[[:^digit:]]", "5", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void posixNegatedSpace(RegexEngineFactory factory) { assertSameFind("[[:^space:]]", "x", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void posixNegatedUpper(RegexEngineFactory factory) { assertSameFind("[[:^upper:]]", "a", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void posixNegatedLower(RegexEngineFactory factory) { assertSameFind("[[:^lower:]]", "A", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void posixNegatedCntrl(RegexEngineFactory factory) { assertSameFind("[[:^cntrl:]]", "A", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void posixNegatedPunct(RegexEngineFactory factory) { assertSameFind("[[:^punct:]]", "A", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void posixNegatedXdigit(RegexEngineFactory factory) { assertSameFind("[[:^xdigit:]]", "z", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void posixNegatedBlank(RegexEngineFactory factory) { assertSameFind("[[:^blank:]]", "x", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void posixNegatedGraph(RegexEngineFactory factory) { assertSameFind("[[:^graph:]]", " ", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void posixNegatedPrint(RegexEngineFactory factory) { assertSameFind("[[:^print:]]", "\u0001", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void posixNegatedAlnum(RegexEngineFactory factory) { assertSameFind("[[:^alnum:]]", "!", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void posixNegatedAscii(RegexEngineFactory factory) { assertSameFind("[[:^ascii:]]", "\u00E9", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void posixNegatedAsciiMatch(RegexEngineFactory factory) { assertSameFind("[[:^ascii:]]", "A", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void posixNegatedWord(RegexEngineFactory factory) { assertSameFind("[[:^word:]]", "!", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void posixNegatedWordMatch(RegexEngineFactory factory) { assertSameFind("[[:^word:]]", "_", factory); }

    // ---- Unicode general categories ----

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void unicodeN(RegexEngineFactory factory) { assertSameFind("\\p{N}", "5", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void unicodeNl(RegexEngineFactory factory) { assertSameFind("\\p{Nl}", "\u2160", factory); }     // Roman numeral I
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void unicodeNo(RegexEngineFactory factory) { assertSameFind("\\p{No}", "\u00BD", factory); }     // ½
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void unicodeM(RegexEngineFactory factory) { assertSameFind("\\p{M}", "\u0300", factory); }       // combining grave
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void unicodeMn(RegexEngineFactory factory) { assertSameFind("\\p{Mn}", "\u0300", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void unicodeS(RegexEngineFactory factory) { assertSameFind("\\p{S}", "+", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void unicodeSm(RegexEngineFactory factory) { assertSameFind("\\p{Sm}", "+", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void unicodeSc(RegexEngineFactory factory) { assertSameFind("\\p{Sc}", "$", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void unicodeSk(RegexEngineFactory factory) { assertSameFind("\\p{Sk}", "\u005E", factory); }     // circumflex
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void unicodeP(RegexEngineFactory factory) { assertSameFind("\\p{P}", ".", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void unicodePd(RegexEngineFactory factory) { assertSameFind("\\p{Pd}", "-", factory); }          // dash punctuation
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void unicodePc(RegexEngineFactory factory) { assertSameFind("\\p{Pc}", "_", factory); }          // connector punctuation
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void unicodeZ(RegexEngineFactory factory) { assertSameFind("\\p{Z}", " ", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void unicodeZs(RegexEngineFactory factory) { assertSameFind("\\p{Zs}", " ", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void unicodeC(RegexEngineFactory factory) { assertSameFind("\\p{C}", "\u0000", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void unicodeCf(RegexEngineFactory factory) { assertSameFind("\\p{Cf}", "\u200B", factory); }     // zero-width space
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void unicodeCc(RegexEngineFactory factory) { assertSameFind("\\p{Cc}", "\u0001", factory); }

    // ---- Unicode scripts ----

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void unicodeScriptLatin(RegexEngineFactory factory) { assertSameFind("\\p{Latin}", "A", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void unicodeScriptCyrillic(RegexEngineFactory factory) { assertSameFind("\\p{Cyrillic}", "\u0410", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void unicodeScriptHan(RegexEngineFactory factory) { assertSameFind("\\p{Han}", "\u4E00", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void unicodeScriptHiragana(RegexEngineFactory factory) { assertSameFind("\\p{Hiragana}", "\u3042", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void unicodeScriptKatakana(RegexEngineFactory factory) { assertSameFind("\\p{Katakana}", "\u30A2", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void unicodeScriptArabic(RegexEngineFactory factory) { assertSameFind("\\p{Arabic}", "\u0627", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void unicodeScriptHebrew(RegexEngineFactory factory) { assertSameFind("\\p{Hebrew}", "\u05D0", factory); }

    // ---- Internal negation syntax \p{^X} ----

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void unicodeInternalNegation(RegexEngineFactory factory) { assertSameFind("\\p{^Nd}", "x", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void unicodeInternalNegationMatch(RegexEngineFactory factory) { assertSameFind("\\p{^Nd}", "5", factory); }

    // ---- Edge cases ----

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void nullByteInClass(RegexEngineFactory factory) { assertSameFind("[\\x00]", "\u0000", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void literalDotInClass(RegexEngineFactory factory) { assertSameFind("[.]", ".", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void literalPlusInClass(RegexEngineFactory factory) { assertSameFind("[+]", "+", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void literalPipeInClass(RegexEngineFactory factory) { assertSameFind("[|]", "|", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void literalParenInClass(RegexEngineFactory factory) { assertSameFind("[(]", "(", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void nestedPosixInClass(RegexEngineFactory factory) { assertSameFind("[a-z[:digit:]]", "5", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void supplementaryPlaneInput(RegexEngineFactory factory) {
        String s = new String(Character.toChars(0x1F600));
        assertSameFind("\\p{So}", s, factory);
    }

    // ---- Additional Unicode sub-categories ----

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void unicodeLo(RegexEngineFactory factory) { assertSameFind("\\p{Lo}", "\u4E00", factory); }       // CJK ideograph
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void unicodeLm(RegexEngineFactory factory) { assertSameFind("\\p{Lm}", "\u02B0", factory); }       // modifier letter
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void unicodeLt(RegexEngineFactory factory) { assertSameFind("\\p{Lt}", "\u01C5", factory); }       // titlecase letter (DZ)
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void unicodePs(RegexEngineFactory factory) { assertSameFind("\\p{Ps}", "(", factory); }            // open punctuation
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void unicodePe(RegexEngineFactory factory) { assertSameFind("\\p{Pe}", ")", factory); }            // close punctuation
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void unicodePi(RegexEngineFactory factory) { assertSameFind("\\p{Pi}", "\u201C", factory); }      // initial quote "
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void unicodePf(RegexEngineFactory factory) { assertSameFind("\\p{Pf}", "\u201D", factory); }      // final quote "
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void unicodePo(RegexEngineFactory factory) { assertSameFind("\\p{Po}", ".", factory); }            // other punctuation
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void unicodeMc(RegexEngineFactory factory) { assertSameFind("\\p{Mc}", "\u0903", factory); }      // spacing combining mark
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void unicodeMe(RegexEngineFactory factory) { assertSameFind("\\p{Me}", "\u0488", factory); }      // enclosing mark
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void unicodeSo(RegexEngineFactory factory) { assertSameFind("\\p{So}", "\u263A", factory); }      // other symbol ☺
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void unicodeZl(RegexEngineFactory factory) { assertSameFind("\\p{Zl}", "\u2028", factory); }      // line separator
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void unicodeZp(RegexEngineFactory factory) { assertSameFind("\\p{Zp}", "\u2029", factory); }      // paragraph separator
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void unicodeCo(RegexEngineFactory factory) { assertSameFind("\\p{Co}", "\uE000", factory); }      // private use

    // ---- [\b] rejection (re2j rejects too) ----

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void backspaceInClassRejects(RegexEngineFactory factory) { assertSameCompileReject("[\\b]", factory); }

    // ---- \N{...} rejection (re2j rejects) ----

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void namedEscapeRejects(RegexEngineFactory factory) { assertSameCompileReject("\\N{LATIN SMALL LETTER A}", factory); }

    // ---- \777 overflow: re2j accepts (wraps to 0xFF) ----

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void octalOverflow(RegexEngineFactory factory) { assertSameFind("\\777", "\u00FF", factory); }

    // ---- \x{D800} lone surrogate: re2j accepts ----

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void hexSurrogate(RegexEngineFactory factory) { assertSameCompileSuccess("\\x{D800}", factory); }

    // ---- Long input ----

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void longInputFind(RegexEngineFactory factory) {
        String in = "a".repeat(10000) + "b";
        assertSameFind("a+b", in, factory);
    }
}
