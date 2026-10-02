package io.github.jemmix.tdfa.core.determinize;

import io.github.jemmix.tdfa.core.budget.WorkMeter;
import io.github.jemmix.tdfa.core.tnfa.Tnfa;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static io.github.jemmix.tdfa.core.dfa.Tdfa.OP_COPY;
import static io.github.jemmix.tdfa.core.dfa.Tdfa.OP_SET_NIL;
import static io.github.jemmix.tdfa.core.dfa.Tdfa.OP_SET_POS;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * BT22 §3.3 {@code topological_sort} semantics as used by determinization's
 * {@code map} (§2, line 43: {@code return topological_sort(O)}): reader-first
 * ordering ({@code I[dst] = 0} gates every op, so all reads of a register
 * precede the write that updates it) and — the point of the TODO —
 * non-trivial copy cycles are REJECTED (map fails, add_state creates a fresh
 * state) because executing them without a temporary register corrupts values.
 * Trivial cycles (self-copies, dst = src) are no-ops and accepted.
 */
class TopologicalSortTest {

    private static TdfaCompiler compiler() {
        return new TdfaCompiler(Tnfa.compile("(a)(b)"), false, false, false, new WorkMeter(1L << 32));
    }

    private static List<int[]> ops(int[]... triples) {
        return new ArrayList<>(Arrays.asList(triples));
    }

    private static int[] copy(int dst, int src) {
        return new int[]{OP_COPY, dst, src};
    }

    private static int[] setPos(int dst) {
        return new int[]{OP_SET_POS, dst, 0};
    }

    private static int[] setNil(int dst) {
        return new int[]{OP_SET_NIL, dst, 0};
    }

    private static String render(List<int[]> ops) {
        StringBuilder sb = new StringBuilder();
        for (int[] op : ops) {
            if (sb.length() > 0) {
                sb.append("; ");
            }
            switch (op[0]) {
                case OP_COPY :
                    sb.append("r").append(op[1]).append("<-r").append(op[2]);
                    break;
                case OP_SET_POS :
                    sb.append("r").append(op[1]).append("<-pos");
                    break;
                default :
                    sb.append("r").append(op[1]).append("<-nil");
            }
        }
        return sb.toString();
    }

    @Test
    void ordersCopyChainReadersFirst() {
        TdfaCompiler c = compiler();
        List<int[]> ops = ops(copy(7, 9), copy(5, 7));
        assertThat(c.topologicalSort(ops)).isTrue();
        // r5<-r7 reads r7, so it must run before r7 is overwritten by r7<-r9;
        // r7<-r9 reads r9, so it must run before any write of r9.
        assertThat(render(ops)).isEqualTo("r5<-r7; r7<-r9");
    }

    @Test
    void setWaitsForItsCopyReaders() {
        TdfaCompiler c = compiler();
        List<int[]> ops = ops(setNil(2), copy(3, 2));
        assertThat(c.topologicalSort(ops)).isTrue();
        // The copy reads the OLD r2: it must precede the SET that clobbers r2.
        assertThat(render(ops)).isEqualTo("r3<-r2; r2<-nil");
    }

    @Test
    void chainExtendsThroughSet() {
        TdfaCompiler c = compiler();
        List<int[]> ops = ops(setPos(9), copy(7, 9), copy(5, 7));
        assertThat(c.topologicalSort(ops)).isTrue();
        assertThat(render(ops)).isEqualTo("r5<-r7; r7<-r9; r9<-pos");
    }

    @Test
    void writersWaitForReadersEvenMidList() {
        TdfaCompiler c = compiler();
        List<int[]> ops = ops(copy(5, 2), setPos(2), copy(8, 5));
        assertThat(c.topologicalSort(ops)).isTrue();
        // r8<-r5 reads r5, so r5's writer (r5<-r2) waits; r5<-r2 reads r2,
        // so r2's SET waits. Readers-first regardless of input position.
        assertThat(render(ops)).isEqualTo("r8<-r5; r5<-r2; r2<-pos");
    }

    @Test
    void rejectsTwoCycle() {
        TdfaCompiler c = compiler();
        List<int[]> ops = ops(copy(1, 2), copy(2, 1));
        assertThat(c.topologicalSort(ops)).isFalse();
        // Remainder appended in original order (paper Algorithm 6).
        assertThat(render(ops)).isEqualTo("r1<-r2; r2<-r1");
    }

    @Test
    void rejectsThreeCycle() {
        TdfaCompiler c = compiler();
        List<int[]> ops = ops(copy(1, 3), copy(3, 2), copy(2, 1));
        assertThat(c.topologicalSort(ops)).isFalse();
        assertThat(render(ops)).isEqualTo("r1<-r3; r3<-r2; r2<-r1");
    }

    @Test
    void rejectsCycleAfterPeelingItsTail() {
        TdfaCompiler c = compiler();
        List<int[]> ops = ops(copy(1, 2), copy(2, 1), copy(3, 1));
        assertThat(c.topologicalSort(ops)).isFalse();
        // r3<-r1 is a reader of r1, so it peels first (reads old r1 before
        // the cycle clobbers it); the r1/r2 swap itself is unexecutable.
        assertThat(render(ops)).isEqualTo("r3<-r1; r1<-r2; r2<-r1");
    }

    @Test
    void trivialSelfCopyCycleIsAccepted() {
        TdfaCompiler c = compiler();
        List<int[]> ops = ops(setPos(4), copy(4, 4));
        assertThat(c.topologicalSort(ops)).isTrue();
        // r4<-r4 is a no-op wherever it runs; the paper counts only
        // dst != src copies as non-trivial.
        assertThat(render(ops)).isEqualTo("r4<-pos; r4<-r4");
    }

    @Test
    void singletonAndEmptyAreTriviallyAcyclic() {
        TdfaCompiler c = compiler();
        assertThat(c.topologicalSort(ops(copy(1, 2)))).isTrue();
        assertThat(c.topologicalSort(ops())).isTrue();
    }

    @Test
    void scratchReusedAcrossCallsStaysClean() {
        TdfaCompiler c = compiler();
        // A rejected call leaves no reader counts behind: a subsequent
        // chain over the SAME registers must still sort and succeed.
        assertThat(c.topologicalSort(ops(copy(1, 2), copy(2, 1)))).isFalse();
        assertThat(c.topologicalSort(ops(copy(1, 2), copy(2, 1)))).isFalse();
        List<int[]> chain = ops(copy(2, 1), copy(1, 3));
        assertThat(c.topologicalSort(chain)).isTrue();
        assertThat(render(chain)).isEqualTo("r2<-r1; r1<-r3");
    }

    @Test
    void idempotentOnSortedOutput() {
        TdfaCompiler c = compiler();
        List<int[]> ops = ops(setPos(9), copy(7, 9), copy(5, 7));
        assertThat(c.topologicalSort(ops)).isTrue();
        String once = render(ops);
        assertThat(c.topologicalSort(ops)).isTrue();
        assertThat(render(ops)).isEqualTo(once);
    }
}
