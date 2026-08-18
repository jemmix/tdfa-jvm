package io.github.jemmix.tdfa;

import io.github.jemmix.tdfa.core.CompileOptions;
import io.github.jemmix.tdfa.core.CompiledRegex;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pinned-Unicode-version table modules: category/script lookup and case-fold
 * counterparts resolve from the frozen UCD snapshot, not the JVM's tables.
 */
class PinnedUnicodeTablesTest {

    @Test void v6CategoriesAndScriptsResolve() {
        var p = io.github.jemmix.tdfa.unicode.v6_0.Unicode6_0.provider();
        assertThat(p.tableFor("L")).isNotNull();
        assertThat(p.tableFor("Lu")).isNotNull();
        assertThat(p.tableFor("Han")).isNotNull();
        assertThat(p.tableFor("Cyrillic")).isNotNull();
        assertThat(p.tableFor("NoSuchTable")).isNull();

        // Osage (U+104B0..) was added in Unicode 8.0: unassigned in 6.0
        assertThat(contains(p.tableFor("Cn"), 0x104B0)).isTrue();
        assertThat(contains(p.tableFor("Lu"), 0x104B0)).isFalse();
        // Math Fraktur capitals are Lu since Unicode 3.1 — stable across pins
        assertThat(contains(p.tableFor("Lu"), 0x1D504)).isTrue();
    }

    @Test void v17AssignsWhatV6DidNot() {
        var v6 = io.github.jemmix.tdfa.unicode.v6_0.Unicode6_0.provider();
        var v17 = io.github.jemmix.tdfa.unicode.v17_0.Unicode17_0.provider();
        // Unicode 8.0 assigned Osage capitals (U+104B0..) to Lu
        assertThat(contains(v17.tableFor("Lu"), 0x104B0)).isTrue();
        assertThat(contains(v6.tableFor("Lu"), 0x104B0)).isFalse();
        // Both versions agree on boring facts
        assertThat(contains(v6.tableFor("Lu"), 'A')).isTrue();
        assertThat(contains(v17.tableFor("Lu"), 'A')).isTrue();
    }

    @Test void foldCounterpartsFromPinnedOrbits() {
        var p = io.github.jemmix.tdfa.unicode.v6_0.Unicode6_0.provider();
        // ſ (U+017F) folds with s (U+0073): outside \p{Lu} but counterpart 'S' is inside
        int[] luFold = p.foldTableFor("Lu");
        assertThat(luFold).isNotNull();
        assertThat(contains(luFold, 's')).isTrue();   // lowercase counterparts of A-Z
        // K (U+212A KELVIN SIGN) folds with k: outside \p{Ll}, counterpart 'k' inside
        assertThat(contains(p.foldTableFor("Ll"), 0x212A)).isTrue();
        // Table without case pairs has no fold additions
        assertThat(p.foldTableFor("Nd")).isNull();
    }

    @Test void compilesThroughPipelineWithPinnedTables() {
        CompiledRegex r = CompiledRegex.compile("\\p{L}+",
                CompileOptions.of().unicode(io.github.jemmix.tdfa.unicode.v6_0.Unicode6_0.provider()));
        assertThat(r.find("abc")).isTrue();
        assertThat(r.find("123")).isFalse();
        // v17 matches Osage capitals; v6 (unassigned there) does not.
        // (Supplementary \p{} matching is an engine limitation — see
        // Tdfa breakpoints — so the delta probe uses the table API above.)
        String osage = "\uD801\uDCB0\uD801\uDCB1\uD801\uDCB2\uD801\uDCB3\uD801\uDCB4";
        CompiledRegex r17 = CompiledRegex.compile("\\p{Lu}{5}",
                CompileOptions.of().unicode(io.github.jemmix.tdfa.unicode.v17_0.Unicode17_0.provider()));
        assertThat(r17.find(osage)).isTrue();
        CompiledRegex r6 = CompiledRegex.compile("\\p{Lu}{5}",
                CompileOptions.of().unicode(io.github.jemmix.tdfa.unicode.v6_0.Unicode6_0.provider()));
        assertThat(r6.find(osage)).isFalse();
    }

    @Test void unknownPropertyStillRejected() {
        assertThatThrownBy(() ->
                CompiledRegex.compile("[\\p{NotAProperty}]", CompileOptions.of()
                        .unicode(io.github.jemmix.tdfa.unicode.v6_0.Unicode6_0.provider())))
                .isInstanceOf(io.github.jemmix.tdfa.core.PatternSyntaxException.class);
    }

    private static boolean contains(int[] table, int cp) {
        if (table == null) return false;
        int lo = 0, hi = table.length / 2 - 1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            if (cp < table[2 * mid]) hi = mid - 1;
            else if (cp > table[2 * mid + 1]) lo = mid + 1;
            else return true;
        }
        return false;
    }
}
