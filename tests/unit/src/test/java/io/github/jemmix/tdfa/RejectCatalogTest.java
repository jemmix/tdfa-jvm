package io.github.jemmix.tdfa;

import io.github.jemmix.tdfa.Pattern;
import io.github.jemmix.tdfa.core.parser.PatternSyntaxException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The reject catalog — the executable capability manifest of
 * docs/facade-kit.md: every family the engine cannot run is rejected at
 * the grammar tier, loudly, with per-reading wording. A facade's
 * compatibility surface is its rejections as much as its acceptances, so
 * the messages are pinned cell-for-cell here; a reading that changes
 * updates this test and the kit's table together.
 *
 * <p>The doctrine (docs/engine-compat-surface.md): the IR can express
 * exactly what the pipeline can compile — backreferences, lookaround,
 * atomic groups and friends have no node type, so no producer can
 * smuggle them past the boundary; rejection lives where the engine's own
 * wording lives, the grammar tier.
 */
class RejectCatalogTest {

    /** One catalog row: the pattern, the lane, the message it must reject
     *  with (a substring of the parse error's description). */
    private static final class Row {
        final String pattern;
        final boolean re2Lane;
        final String message;

        Row(String pattern, boolean re2Lane, String message) {
            this.pattern = pattern;
            this.re2Lane = re2Lane;
            this.message = message;
        }
    }

    private static final List<Row> CATALOG = List.of(
        // ---- the stable capability exclusions: both lanes reject, no
        // ---- open work item moves them (lookaround is mission-bound; the
        // ---- backref/possessive/atomic/grapheme families have open TODO
        // ---- items and join the catalog when their readings land) ----
        new Row("(?=a)", false, "lookahead not supported"), new Row("(?!a)", false, "lookahead not supported"),
        new Row("(?<=a)", false, "lookbehind not supported"), new Row("(?<!a)", false, "lookbehind not supported"),
        new Row("\\C", false, "invalid escape sequence"), new Row("\\C", true, "invalid escape sequence"),
        // ---- the grammar-tied rejections: per-alphabet wording ----
        new Row("\\e", true, "invalid escape sequence: \\e"), new Row("\\R", true, "invalid escape sequence: \\R"),
        new Row("\\cA", true, "invalid escape sequence: \\c"), new Row("\\Z", true, "invalid escape sequence: \\Z"),
        new Row("\\V", true, "invalid escape sequence: \\V"), new Row("\\h", true, "invalid escape sequence: \\h"),
        new Row("[\\e]", true, "invalid escape sequence: \\e"),
        // the jur octal rule: \0 needs a following octal digit
        new Row("\\0", false, "invalid escape sequence: \\0"),
        // unbound letters reject with the alphabet's own message
        new Row("(?x)a b", true, "invalid or unsupported Perl syntax: (?+x"),
        // the shared syntax families reject identically in both lanes
        // the jur octal rule: \0 needs a following octal digit (the
        // \1-\9 precedence work is in flight — its landing extends this
        // catalog, not this pin)
        new Row("\\0", false, "invalid escape sequence: \\0"),
        new Row("[a-\\d]", false, "invalid escape sequence: \\d"), new Row("[z-a]", false, "inverted range in class"),
        new Row("[[:foo:]]", false, "unknown POSIX class: [:foo:]"),
        new Row("\\p{Foo}", false, "unknown character class name: Foo"),
        new Row("a{1001}", false, "invalid repeat count"), new Row("a{2,1}", false, "min > max"));

    @Test
    void everyCatalogRowRejectsWithItsDocumentedMessage() {
        for (Row row : CATALOG) {
            Runnable compile = row.re2Lane ? () -> io.github.jemmix.tdfa.re2j.Pattern.compile(row.pattern)
                : () -> Pattern.compile(row.pattern);
            assertThatThrownBy(compile::run)
                .as("catalog row %s in the %s lane", row.pattern, row.re2Lane ? "re2j" : "jur")
                .isInstanceOf(PatternSyntaxException.class).hasMessageContaining(row.message);
        }
    }

    /** The acceptance twins: what a row rejects in one lane the other
     *  lane accepts where the kit's table says so — the wording and the
     *  acceptance move together. */
    @Test
    void theGrammarTiedRowsAcceptInTheOtherLane() {
        // What a row rejects in one lane the other lane accepts where the
        // kit's table says so — the wording and the acceptance move together.
        assertThatCodeQuietly("\\e", "\\R", "\\cA", "\\Z", "\\v", "\\h");
        assertThat(io.github.jemmix.tdfa.re2j.Pattern.compile("(?U)a+?").matcher("aaa").find())
            .as("re2j binds (?U) = ungreedy").isTrue();
        assertThat(Pattern.compile("(?x)a b").matcher("ab").find()).as("jur binds the x letter").isTrue();
    }

    private static void assertThatCodeQuietly(String... patterns) {
        for (String p : patterns) {
            assertThat(Pattern.compile(p)).as("jur accepts %s", p).isNotNull();
        }
    }
}
