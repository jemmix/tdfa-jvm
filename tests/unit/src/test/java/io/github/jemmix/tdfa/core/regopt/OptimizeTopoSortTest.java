package io.github.jemmix.tdfa.core.regopt;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * BT22 §3.3 {@code topological_sort} as used by normalization (paper
 * Algorithm 6, line 44): same reader-first peel as the determinization-side
 * sorter, but here the {@code nontrivial_cycle} flag is DISCARDED — cycles
 * in a normalized block would be a regopt construction bug, not an input
 * property; the flag is load-bearing only in {@code map}, which rejects on
 * it. These tests pin the flag and the append-remainder behavior so the two
 * sorters cannot silently drift apart.
 */
class OptimizeTopoSortTest {

    private static List<Cfg.Op> run(Cfg.Op... ops) {
        return new ArrayList<>(Arrays.asList(ops));
    }

    private static String render(List<Cfg.Op> ops) {
        StringBuilder sb = new StringBuilder();
        for (Cfg.Op op : ops) {
            if (sb.length() > 0) {
                sb.append("; ");
            }
            sb.append(op);
        }
        return sb.toString();
    }

    @Test
    void sortsChainAndReportsNoCycle() {
        List<Cfg.Op> run = run(Cfg.Op.copy(7, 9), Cfg.Op.copy(5, 7));
        assertThat(Optimize.topoSortCopy(run, null)).isFalse();
        assertThat(render(run)).isEqualTo("r5=r7; r7=r9");
    }

    @Test
    void detectsTwoCycleAndKeepsRemainderOrder() {
        List<Cfg.Op> run = run(Cfg.Op.copy(1, 2), Cfg.Op.copy(2, 1));
        assertThat(Optimize.topoSortCopy(run, null)).isTrue();
        assertThat(render(run)).isEqualTo("r1=r2; r2=r1");
    }

    @Test
    void detectsThreeCycle() {
        List<Cfg.Op> run = run(Cfg.Op.copy(1, 3), Cfg.Op.copy(3, 2), Cfg.Op.copy(2, 1));
        assertThat(Optimize.topoSortCopy(run, null)).isTrue();
        assertThat(render(run)).isEqualTo("r1=r3; r3=r2; r2=r1");
    }

    @Test
    void peelsReadersBeforeReportingCycle() {
        List<Cfg.Op> run = run(Cfg.Op.copy(1, 2), Cfg.Op.copy(2, 1), Cfg.Op.copy(3, 1));
        assertThat(Optimize.topoSortCopy(run, null)).isTrue();
        assertThat(render(run)).isEqualTo("r3=r1; r1=r2; r2=r1");
    }

    @Test
    void selfCopiesAreTrivialCycles() {
        List<Cfg.Op> run = run(Cfg.Op.copy(4, 4));
        assertThat(Optimize.topoSortCopy(run, null)).isFalse();
        assertThat(render(run)).isEqualTo("r4=r4");
    }

    @Test
    void emptyAndSingletonRuns() {
        assertThat(Optimize.topoSortCopy(new ArrayList<Cfg.Op>(), null)).isFalse();
        assertThat(Optimize.topoSortCopy(run(Cfg.Op.copy(0, 1)), null)).isFalse();
    }

    @Test
    void normalizationDiscardsTheFlagPerPaper() {
        // Algorithm 6 line 44 calls topological_sort and ignores the return:
        // a cycle must not make normalization throw or drop ops — the run
        // passes through reordered-then-appended, byte-identical semantics
        // to the pre-flag behavior.
        Cfg cfg = new Cfg(1, 4);
        Cfg.Block b = cfg.newBlock(Cfg.BLOCK_BASIC, 0, 0);
        b.ops.add(Cfg.Op.copy(1, 2));
        b.ops.add(Cfg.Op.copy(2, 1));
        b.ops.add(Cfg.Op.copy(3, 1));
        Optimize.normalization(cfg, null);
        assertThat(render(b.ops)).isEqualTo("r3=r1; r1=r2; r2=r1");
        assertThat(b.ops).hasSize(3);
    }
}
