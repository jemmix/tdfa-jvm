package io.github.jemmix.tdfa.determinism;

import io.github.jemmix.tdfa.core.dfa.Tdfa;

import java.util.Map;
import java.util.TreeMap;

/**
 * Structural fingerprint of a compiled {@link Tdfa}: a 64-bit FNV-1a over
 * EVERY artifact table and scalar the public accessors expose (state
 * meta/base/offsets, ranges, ops stream, entry/accept masks, the stop tier,
 * per-mask final ops, word/fixed tables, named groups). Two compiles of the
 * same pattern under the same flags and knobs produce the same fingerprint
 * IFF the artifacts are bit-identical — this is the oracle behind the
 * deterministic-compilation gate (TODO "same regex → identical TDFA across
 * runs"). hiPrefix is derived from ranges and not separately exposed, so it
 * is covered transitively.
 */
final class ArtifactFingerprint {
    private long h = 0xcbf29ce484222325L;

    static String of(Tdfa t) {
        ArtifactFingerprint f = new ArtifactFingerprint();
        f.scalar(t.tagCount());
        f.scalar(t.groupCount());
        f.scalar(t.registerCount());
        f.scalar(t.finalRegBase());
        f.scalar(t.stateCount());
        f.scalar(t.longestMatch() ? 1 : 0);
        f.scalar(t.multiline() ? 1 : 0);
        f.scalar(t.unicodeWordBoundary() ? 1 : 0);
        f.scalar(t.pikeCutMatters() ? 1 : 0);
        f.array(t.stateMeta());
        f.array(t.stateBase());
        f.array(t.stateFinalOpsOff());
        f.array(t.stateFinalOpsByMask());
        f.array(t.ranges());
        f.array(t.ops());
        f.array(t.stateEntryMask());
        f.array(t.stateAcceptMask());
        f.array(t.stopOnAcceptMask());
        f.array(t.wordRanges());
        f.array(t.fixedBase());
        f.array(t.fixedOffset());
        Map<String, Integer> named = new TreeMap<>(t.namedGroups());
        f.scalar(named.size());
        for (Map.Entry<String, Integer> e : named.entrySet()) {
            for (int c : e.getKey().chars().toArray()) {
                f.scalar(c);
            }
            f.scalar(0);
            f.scalar(e.getValue());
        }
        return String.format("%016x", f.h);
    }

    private void scalar(int v) {
        h = (h ^ (v & 0xFFFFFFFFL)) * 0x100000001B3L;
    }

    private void array(int[] a) {
        scalar(a == null ? -1 : a.length);
        if (a != null) {
            for (int w : a) {
                scalar(w);
            }
        }
    }
}
