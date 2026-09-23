package io.github.jemmix.tdfa.tdfa;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * Construction-time validate() hardening: the four review-named gaps —
 * per-state array lengths unchecked, target ≤ -2 passing, missing OP_END
 * terminator crashing with a bare AIOOBE inside the validator, and a short
 * entryHiPrefix — must surface as precise IllegalStateExceptions at
 * construction, not as corruption downstream.
 */
class TdfaValidateHardeningTest {

    /** Minimal well-formed single-state artifact (tagless, no ranges, no ops). */
    private static Object[] validArrays() {
        return new Object[] {
            1, // stateCount
            new int[] {0}, // stateMeta: no ranges, not accepting
            new int[] {0}, // stateBase
            new int[] {0}, // stateFinalOpsOff
            null, // stateFinalOpsByMask
            new int[0], // ranges
            new int[0], // ops
            new int[0], // entryHiPrefix
            new int[] {0}, // stateEntryMask
            new int[] {0}, // stateAcceptMask
        };
    }

    private static Tdfa build(Object[] a) {
        return new Tdfa(
                0,
                0,
                null,
                0,
                0,
                0,
                (Integer) a[0],
                (int[]) a[1],
                (int[]) a[2],
                (int[]) a[3],
                (int[]) a[4],
                (int[]) a[5],
                (int[]) a[6],
                (int[]) a[7],
                (int[]) a[8],
                (int[]) a[9],
                false,
                null,
                null,
                false,
                false,
                null,
                null,
                null);
    }

    private static Tdfa buildValid() {
        return build(validArrays());
    }

    @Test
    void minimalArtifactPasses() {
        Tdfa t = buildValid();
        assertThat(t.stateCount()).isEqualTo(1);
    }

    @Test
    void shortStateMetaRejected() {
        Object[] a = validArrays();
        a[1] = new int[2];
        assertThatThrownBy(() -> build(a))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("stateMeta length 2");
    }

    @Test
    void shortStateBaseRejected() {
        Object[] a = validArrays();
        a[2] = new int[0];
        assertThatThrownBy(() -> build(a))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("stateBase length 0");
    }

    @Test
    void shortFinalOpsOffRejected() {
        Object[] a = validArrays();
        a[3] = new int[0];
        assertThatThrownBy(() -> build(a))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("stateFinalOpsOff length 0");
    }

    @Test
    void shortEntryMaskRejected() {
        Object[] a = validArrays();
        a[8] = new int[0];
        assertThatThrownBy(() -> build(a))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("stateEntryMask length 0");
    }

    @Test
    void shortHiPrefixRejected() {
        Object[] a = validArrays();
        a[5] = new int[5]; // one range entry
        a[7] = new int[0]; // but empty prefix table
        a[1] = new int[] {1 << 1};
        assertThatThrownBy(() -> build(a))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("entryHiPrefix length 0");
    }

    @Test
    void deadMarkerBelowMinusOneRejected() {
        Object[] a = validArrays();
        a[5] = new int[] {0, 0, -2, 0, 0}; // target = -2 (only -1 is the dead marker)
        a[7] = new int[1];
        a[1] = new int[] {1 << 1};
        assertThatThrownBy(() -> build(a))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("target -2 < -1");
    }

    @Test
    void unterminatedTransitionOpsRejected() {
        Object[] a = validArrays();
        a[5] = new int[] {0, 0, 0, 1, 0}; // one entry, opsOff = 1
        a[6] = new int[] {0, 3, 0, 0, 3, 0, 0}; // slot 0 = empty block; block at 1 runs off the stride grid
        a[7] = new int[1];
        a[1] = new int[] {1 << 1};
        assertThatThrownBy(() -> build(a))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not OP_END-terminated");
    }

    @Test
    void unterminatedFinalOpsRejected() {
        Object[] a = validArrays();
        a[3] = new int[] {1}; // final-ops offset 1
        a[6] = new int[] {0, 3, 0, 0, 3, 0, 0};
        assertThatThrownBy(() -> build(a))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not OP_END-terminated");
    }

    @Test
    void terminatedBlockAtArrayTailAccepted() {
        Object[] a = validArrays();
        a[5] = new int[] {0, 0, 0, 1, 0};
        a[6] = new int[] {0, 1, 2, 0, 0, Tdfa.OP_END}; // SET_POS triple + terminator as last int
        a[7] = new int[1];
        a[1] = new int[] {1 << 1};
        Tdfa t = build(a);
        assertThat(t.rangeCount(t.stateMeta()[0])).isEqualTo(1);
    }
}
