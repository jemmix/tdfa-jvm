package io.github.jemmix.tdfa;

import io.github.jemmix.tdfa.core.CompilationReport;
import io.github.jemmix.tdfa.core.CompileOptions;
import io.github.jemmix.tdfa.core.PatternSyntaxException;
import io.github.jemmix.tdfa.tdfa.Budgets;
import io.github.jemmix.tdfa.tdfa.Tdfa;
import io.github.jemmix.tdfa.tnfa.Tnfa;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * The end-of-compile execution-RAM check: {@code Pattern.compile} fails
 * with the clean "pattern too large" family when the RETAINED artifact
 * tables alone exceed {@code tdfa.budget.runtime.memory}, and the lazy
 * memo allowances draw from the RESIDUAL (budget minus retained) — the
 * user-facing "if it compiles, it will execute within budget" contract
 * (see {@link Budgets}). Memo floors on a tiny residual are the
 * documented bounded carve-out: tiny budgets still compile (clamped
 * caps, not rejection). Engine-tier construction-time tables (dispatch
 * tiers, generated statics) stay outside the budget per the r11 scope
 * decision — the flagship dictionary shapes keep their fast paths.
 */
class ExecutionRamCheckTest {

    @AfterEach
    void cleanup() {
        System.clearProperty(Budgets.RUNTIME_MEMORY_PROP);
    }

    /** ~302-state suffix-chain DFA: ~14 KB of retained flat tables — far
     *  over a 2 KB runtime budget, far under every compile budget. */
    private static String suffixChain(int optionalAs) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < optionalAs; i++) {
            sb.append("a?");
        }
        return sb.append('b').toString();
    }

    /** Retained artifact tables beyond the runtime RAM budget reject the
     *  compile with the standard clean family, pointing at the property —
     *  and the same pattern compiles at the default budget. */
    @Test
    void retainedTablesOverRuntimeBudgetRejectCleanly() {
        String pattern = suffixChain(300);
        System.setProperty(Budgets.RUNTIME_MEMORY_PROP, "2048");
        try {
            long t0 = System.nanoTime();
            assertThatCode(() -> Pattern.compile(pattern)).isInstanceOf(PatternSyntaxException.class)
                .hasMessageContaining("pattern too large").hasMessageContaining("retained execution RAM")
                .hasMessageContaining(Budgets.RUNTIME_MEMORY_PROP);
            assertThat((System.nanoTime() - t0) / 1_000_000).as("wall to the end-of-compile rejection")
                .isLessThan(10_000);
        } finally {
            System.clearProperty(Budgets.RUNTIME_MEMORY_PROP);
        }
        // The residual model, not the check, is what admits it at default:
        assertThatCode(() -> Pattern.compile(pattern)).doesNotThrowAnyException();
    }

    /** A budget too small for the memo floors still compiles and matches
     *  exactly — floors CLAMP the residual's derived caps instead of
     *  rejecting (the pinned tiny-budget usability contract; see
     *  BudgetModelTest.walkMemoIsBudgetBoundedAndCorrect). */
    @Test
    void tinyBudgetsClampToMemoFloors() {
        System.setProperty(Budgets.RUNTIME_MEMORY_PROP, "20480"); // 20 KB
        Pattern p = Pattern.compile("[\\x{400}-\\x{600}]{2,}");
        assertThat(p.matcher("\u0451\u04FF\u0500x").find()).isTrue();
        assertThat(p.matcher("x\u0451x").find()).isFalse();
    }

    /** A pike-cut shape is ONE artifact now (its partial-whole side table
     *  is retained bytes of the find artifact — no engine split, exact
     *  whole/find answers); with the side disabled the pair form (find +
     *  dedicated whole artifact) charges BOTH retained tables and splits
     *  the residual per engine. Mid budgets compile in both forms. */
    @Test
    void pairShapesChargeBothArtifactsAndSplitTheResidual() {
        CompilationReport r = new CompilationReport();
        System.setProperty(Budgets.RUNTIME_MEMORY_PROP, "65536"); // 64 KB
        Pattern p = Pattern.compile("ab|a|ac", CompileOptions.of().observer(r));
        assertThat(p.matcher("ab").find()).isTrue();
        assertThat(p.matcher("ab").matches()).isTrue();
        assertThat(p.matcher("ac").matches()).isTrue();
        assertThat(p.matcher("a").matches()).isTrue();
        assertThat(p.matcher("ad").matches()).isFalse();
        assertThat(r.notes().get("runtimeFootprint")).contains("retained ").doesNotContain("(split per engine)");
        assertThat(r.notes()).containsKey("partialWhole");

        System.setProperty("tdfa.nopartialwhole", "true");
        try {
            CompilationReport r2 = new CompilationReport();
            Pattern q = Pattern.compile("ab|a|ac", CompileOptions.of().observer(r2));
            assertThat(q.matcher("ab").find()).isTrue();
            assertThat(q.matcher("ac").matches()).isTrue();
            assertThat(r2.notes().get("runtimeFootprint")).contains("retained ").contains("(split per engine)");
            assertThat(r2.notes()).doesNotContainKey("partialWhole");
        } finally {
            System.clearProperty("tdfa.nopartialwhole");
        }
    }

    /** The check is transparent: every compile emits a runtimeFootprint
     *  note with the retained/allowance/budget ledger. */
    @Test
    void footprintNoteIsRecorded() {
        CompilationReport r = new CompilationReport();
        Pattern.compile("a(b|c)*d", CompileOptions.of().observer(r));
        String note = r.notes().get("runtimeFootprint");
        assertThat(note).isNotNull().contains("retained ").contains("memo allowance ").contains("budget 16777216 B");
    }

    /** The artifact term is the weighted flat sum of the artifact's own
     *  packed arrays (16 B header + cells), nullables excluded — pinned
     *  against the accessors (and the entryHiPrefix==entries /
     *  uniform-tier==stateCount packing invariants) so the weight model
     *  cannot drift. The stop-table term: a pattern with no zero-width
     *  assertions deterministically takes the Perl uniform tier
     *  (byte[stateCount]); POSIX stores neither tier. */
    @Test
    void retainedTableBytesIsTheFlatAccessorSum() {
        // Perl mode: 5 per-state int tables + flat ranges/prefix/ops +
        // the uniform byte[stateCount] stop tier
        Tdfa perl = Tdfa.compile(Tnfa.compile("a[bc]*(d|e?)"), false);
        assertThat(perl.retainedTableBytes()).isEqualTo(flatSum(perl) + 16 + perl.stateCount());
        // POSIX (longest) mode: no stop tier at all
        Tdfa posix = Tdfa.compile(Tnfa.compile("a[bc]*(d|e?)"), true);
        assertThat(posix.retainedTableBytes()).isEqualTo(flatSum(posix));
        // and the allowance derivation clamps at zero:
        assertThat(Budgets.runtimeMemoAllowance(perl.retainedTableBytes()))
            .isEqualTo((16L << 20) - perl.retainedTableBytes());
        assertThat(Budgets.runtimeMemoAllowance((16L << 20) + 1)).isZero();
    }

    private static long flatSum(Tdfa t) {
        int entries = t.ranges().length / 5; // entryHiPrefix packs one cell per entry
        long sum = intArr(t.stateMeta().length) + intArr(t.stateBase().length) + intArr(t.stateFinalOpsOff().length)
            + intArr(t.stateEntryMask().length) + intArr(t.stateAcceptMask().length) + intArr(t.ranges().length)
            + intArr(entries) + intArr(t.ops().length);
        if (t.stateFinalOpsByMask() != null) {
            sum += intArr(t.stateFinalOpsByMask().length);
        }
        if (t.wordRanges() != null) {
            sum += intArr(t.wordRanges().length);
        }
        if (t.fixedBase() != null) {
            sum += intArr(t.fixedBase().length) + intArr(t.fixedOffset().length);
        }
        return sum;
    }

    private static long intArr(int cells) {
        return 16L + 4L * cells;
    }
}
