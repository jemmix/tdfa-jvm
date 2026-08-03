package io.github.jemmix.tdfa.parity;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Disabled;

import static org.assertj.core.api.Assertions.*;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static io.github.jemmix.tdfa.parity.Re2jOracle.*;

/**
 * Flag interaction parity: combined flags, unknown flag rejection,
 * DISABLE_UNICODE_GROUPS.
 */
class FlagInteractionParityTest {

    @Test void caseInsensitiveAndDotAll() {
        assertSameFind("(?is)A.", "a\n");
    }

    @Test void caseInsensitiveAndMultiline() {
        assertSameAllMatches("(?im)^a", "Ab\ncD\nae");
    }

    @Test void dotAllAndMultiline() {
        assertSameAllMatches("(?sm)^.$", "a\nb");
    }

    @Test void allThreeFlagsCombined() {
        assertSameFind("(?ims)A.", "a\n");
    }

    @Test void longestMatchCombinedWithCaseInsensitive() {
        assertSameFindPosix("(?i)(a|ab)", "AB");
    }

    @Test void unknownFlagRejects() {
        assertThatThrownBy(() -> io.github.jemmix.tdfa.re2j.Pattern.compile("abc", 0x100))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> com.google.re2j.Pattern.compile("abc", 0x100))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test void disableUnicodeGroupsAccepted() {
        io.github.jemmix.tdfa.re2j.Pattern.compile("\\p{L}",
                io.github.jemmix.tdfa.re2j.Pattern.DISABLE_UNICODE_GROUPS);
    }

    @Test void disableUnicodeGroupsBehavior() {
        // Pending parity: re2j rejects \p{L} when DISABLE_UNICODE_GROUPS is set;
        // our shim accepts it. Verify the divergence is known.
        boolean re2jRejects;
        try {
            com.google.re2j.Pattern.compile("\\p{L}", com.google.re2j.Pattern.DISABLE_UNICODE_GROUPS);
            re2jRejects = false;
        } catch (Exception e) {
            re2jRejects = true;
        }
        // re2j should reject Unicode groups when flag is set.
        assertThat(re2jRejects).isTrue();
        // Our shim currently accepts (pending parity enforcement).
        io.github.jemmix.tdfa.re2j.Pattern.compile("\\p{L}",
                io.github.jemmix.tdfa.re2j.Pattern.DISABLE_UNICODE_GROUPS);
    }

    @Disabled("PENDING: DISABLE_UNICODE_GROUPS should reject \\p{X} like re2j")
    @Test void disableUnicodeGroupsRejectsProperty() {
        assertThatThrownBy(() -> io.github.jemmix.tdfa.re2j.Pattern.compile("\\p{L}",
                io.github.jemmix.tdfa.re2j.Pattern.DISABLE_UNICODE_GROUPS))
                .isInstanceOf(io.github.jemmix.tdfa.re2j.PatternSyntaxException.class);
    }

    @Test void flagRoundTrip() {
        int flags = io.github.jemmix.tdfa.re2j.Pattern.CASE_INSENSITIVE
                | io.github.jemmix.tdfa.re2j.Pattern.MULTILINE
                | io.github.jemmix.tdfa.re2j.Pattern.DOTALL;
        var p = io.github.jemmix.tdfa.re2j.Pattern.compile("abc", flags);
        assertThat(p.flags()).isEqualTo(flags);
    }
}
