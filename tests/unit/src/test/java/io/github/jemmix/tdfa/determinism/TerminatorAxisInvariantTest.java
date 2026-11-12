package io.github.jemmix.tdfa.determinism;

import io.github.jemmix.tdfa.core.budget.Budgets;
import io.github.jemmix.tdfa.core.budget.WorkMeter;
import io.github.jemmix.tdfa.core.determinize.Determinizer;
import io.github.jemmix.tdfa.core.dfa.Tdfa;
import io.github.jemmix.tdfa.core.dialect.ClassTable;
import io.github.jemmix.tdfa.core.dialect.Terminators;
import io.github.jemmix.tdfa.core.report.CompileObserver;
import io.github.jemmix.tdfa.core.tnfa.Semantics;
import io.github.jemmix.tdfa.core.tnfa.Tnfa;
import io.github.jemmix.tdfa.core.unicode.UnicodeProviders;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The terminator axis never enters the artifact tables: the set selects
 * only the runner's/ASM's position predicates, so two compiles of the
 * same DOT-free pattern that differ <b>only</b> in their terminator set
 * must be table-identical (the {@link ArtifactFingerprint} oracle).
 * The DOT and the space table are the controls: both materialize into
 * classes and MUST change the fingerprint. Together these pin the
 * sets-as-data contract of {@code docs/dialect-agnostic-core.md}:
 * match-time sets stay out of the artifact, parse-time tables bake in.
 */
class TerminatorAxisInvariantTest {

    private static final String[] ANCHOR_CORPUS =
        {"(?m)^a", "a$", "(?m)^a$b", "^a$b$", "(?m)^(?:a|b)$", "x*\\bword\\b$", "(?m)^|$"};

    /** The parse-time face of the same axis: {@code .} is the set's
     *  complement, a real class — these MUST diverge the tables. */
    private static final String[] DOT_CORPUS = {"(?m)^a$.?", "a.b", "."};

    private static Tdfa compile(String pattern, Semantics semantics) {
        WorkMeter ledger = new WorkMeter(Budgets.compileComputeTicks());
        Tnfa nfa =
            Tnfa.compile(pattern, false, false, false, semantics, UnicodeProviders.get(), CompileObserver.NONE, ledger);
        return Determinizer.compileWithWholeSide(nfa, false, CompileObserver.NONE, ledger.fork(0));
    }

    @Test
    void terminatorSetNeverEntersTheArtifactTables() {
        for (String pat : ANCHOR_CORPUS) {
            String jdk = ArtifactFingerprint.of(compile(pat, Semantics.of()));
            String nlOnly = ArtifactFingerprint.of(compile(pat, Semantics.of().terminators(Terminators.NL_ONLY)));
            String custom = ArtifactFingerprint.of(compile(pat, Semantics.of().terminators(Terminators.of('\r'))));
            assertThat(nlOnly).as("NL_ONLY tables identical to JDK for %s", pat).isEqualTo(jdk);
            assertThat(custom).as("custom-set tables identical to JDK for %s", pat).isEqualTo(jdk);
        }
    }

    @Test
    void dotComplementDoesEnterTheArtifactTables() {
        // The same axis' parse-time face: '.' is the set's complement, a
        // real class — the tables MUST diverge between the stock sets.
        for (String pat : DOT_CORPUS) {
            String jdk = ArtifactFingerprint.of(compile(pat, Semantics.of()));
            String nlOnly = ArtifactFingerprint.of(compile(pat, Semantics.of().terminators(Terminators.NL_ONLY)));
            assertThat(nlOnly).as("DOT complement diverges the tables for %s", pat).isNotEqualTo(jdk);
        }
    }

    @Test
    void spaceTableDoesEnterTheArtifactTables() {
        // The control: \s materializes into classes → breakpoints → the
        // ranges table, so the two stock tables MUST diverge the fingerprint.
        String jdk = ArtifactFingerprint.of(compile("\\s", Semantics.of()));
        String re2 = ArtifactFingerprint.of(compile("\\s", Semantics.of().spaceTable(ClassTable.SPACE_RE2)));
        assertThat(re2).isNotEqualTo(jdk);
    }
}
