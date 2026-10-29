package io.github.jemmix.tdfa.fuzz;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static io.github.jemmix.tdfa.fuzz.DifferentialFuzzer.BATCH_K;
import static io.github.jemmix.tdfa.fuzz.DifferentialFuzzer.FLAG_CI;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the two replayability tripwires the 2026-10-24 soak prescribed for
 * finding 1 (the 2026-10-24 campaign's "Replayability note"):
 * the record is only as good as its replay. Both landed with the W1
 * tripwire PR (#94) — the determinism double-run
 * ({@code MEMO_STATE_MISMATCH}, default on) and warm batch replay for
 * {@code fuzz.one} ({@code -Pfuzz.warm=1}) — and this class is their
 * gate:
 * <ul>
 *   <li><b>double-run determinism</b> — pinned on the exact finding-1
 *       batch (case index 6 of batch 1082986568463112598, siblings
 *       …84–…91): the batch the report says the double-run "would have
 *       self-pinned finding 1 in the same chunk that produced it". Only
 *       the memo axis is asserted — the cross-rung divergence itself is
 *       finding 1's own item (oracle-adjudicated, deterministic cold),
 *       so this gate is independent of that fix's fate.</li>
 *   <li><b>warm replay</b> — the batch siblings run on the same artifact
 *       before the case ({@link DifferentialFuzzer#warmReplay(long,
 *       DifferentialFuzzer.Case)}); pinned as warm protocol == cold
 *       protocol on the finding-1 seed.</li>
 *   <li><b>record plumbing</b> — a deliberately perturbed double-run
 *       lands as a counted, signature-capped {@code MEMO_STATE_MISMATCH}
 *       record (12 records → 12 counted, 8 written to failures.ndjson —
 *       the per-signature cap every failure kind rides).</li>
 * </ul>
 */
class WarmReplayDoubleRunTest {

    /** The 2026-10-24 finding-1 record: case index 6 of batch
     * 1082986568463112598, jur lane (the soak's pin), stretch-probed. */
    private static final long F1_CASE_SEED = 8663892547704900790L;

    private static final long F1_BATCH = Math.floorDiv(F1_CASE_SEED, BATCH_K);

    /** One compile per batch, like the soak: every sibling warms the same
     *  artifact, and each case's double-run must re-answer its own
     *  protocol byte-for-byte on it. */
    @Test
    void finding1BatchDoubleRunIsByteIdentical() {
        DifferentialFuzzer.Prepared pr = null;
        for (int i = 0; i < BATCH_K; i++) {
            long caseSeed = F1_BATCH * BATCH_K + i;
            DifferentialFuzzer.Case c = DifferentialFuzzer.generate(caseSeed, DifferentialFuzzer.Lane.JUR);
            pr = i == 0 ? DifferentialFuzzer.prepare(c.pattern(), c.flags(), DifferentialFuzzer.Lane.JUR) : pr;
            DifferentialFuzzer.Outcome o = DifferentialFuzzer.matchCase(pr, c);
            assertThat(o.memo).as("case %d: warm != cold on the shared artifact", caseSeed).isEmpty();
        }
    }

    /** Warm replay runs the prior siblings on the same artifact, then the
     *  case — and the warmed case must answer what the cold solo replay
     *  answers (the property whose absence stranded finding 1). */
    @Test
    void warmReplayMatchesColdReplayOnTheFinding1Seed() {
        DifferentialFuzzer.Case c = DifferentialFuzzer.generate(F1_CASE_SEED, DifferentialFuzzer.Lane.JUR);
        DifferentialFuzzer.Outcome warm = DifferentialFuzzer.warmReplay(F1_CASE_SEED, c);
        assertThat(warm.asm).as("case ran on the warmed artifact").doesNotStartWith("<");
        assertThat(warm.memo).as("double-run on the warmed artifact").isEmpty();
        assertThat(warm.asm).as("warm protocol vs cold solo replay").isEqualTo(DifferentialFuzzer.runOne(c).asm);
    }

    /** The exact event the double-run exists to catch — a warm artifact
     *  answering differently than the cold pass recorded — fired by
     *  perturbing the recorded cold answer on a real compiled case; the
     *  second pass must record the divergence, classify the case
     *  {@code MEMO_STATE_MISMATCH}, and fail it. */
    @Test
    void perturbedDoubleRunRecordsMemoStateMismatch() {
        DifferentialFuzzer.Case c = new DifferentialFuzzer.Case("(a+)b", "xaab", 0, DifferentialFuzzer.Lane.RE2J);
        DifferentialFuzzer.Prepared pr =
            DifferentialFuzzer.prepare(c.pattern(), c.flags(), DifferentialFuzzer.Lane.RE2J);
        DifferentialFuzzer.Outcome o = DifferentialFuzzer.matchCase(pr, c);
        assertThat(o.memo).as("unperturbed baseline").isEmpty();
        o.asm = o.asm + " <perturbed-cold>";
        DifferentialFuzzer.doubleRun(pr, c, o);
        assertThat(o.memo).as("deliberately perturbed double-run").isNotEmpty();
        assertThat(o.memo.get(0)).contains("MISMATCH asm warm");
        assertThat(DifferentialFuzzer.Results.kindOf(o)).isEqualTo("MEMO_STATE_MISMATCH");
        assertThat(o.failed()).isTrue();
    }

    /** The record path a real divergence rides: counted per case, ndjson
     *  records capped per signature (the same 8-record cap every failure
     *  kind rides), summary-visible signature. The outcome is synthetic —
     *  the exact shape a double-run divergence produces (memo non-empty,
     *  everything else clean) — so the plumbing pin needs no engine. */
    @Test
    void memoStateMismatchRecordsAreCountedAndCapped(@TempDir Path tmp) throws Exception {
        DifferentialFuzzer.Case c = new DifferentialFuzzer.Case("(a+)b", "xaab", FLAG_CI, DifferentialFuzzer.Lane.RE2J);
        DifferentialFuzzer.Outcome o = new DifferentialFuzzer.Outcome(c);
        o.oracle = "F=true 1..4";
        o.asm = "F=true 1..4";
        o.vm = "F=true 1..4";
        o.memo.add("MISMATCH asm warm [F=true 1..4] cold [F=true 1..4 <perturbed-cold>]");
        DifferentialFuzzer.Results r = new DifferentialFuzzer.Results(0);
        try (DifferentialFuzzer.Logs logs = new DifferentialFuzzer.Logs(tmp)) {
            for (int i = 0; i < 12; i++) {
                r.record(F1_CASE_SEED, o, logs);
            }
        }
        assertThat(r.failures).as("every occurrence counted").isEqualTo(12);
        String sig = "MEMO_STATE_MISMATCH | shape~" + DifferentialFuzzer.Results.shape(o.c.pattern());
        assertThat(r.signatures).containsKey(sig);
        assertThat(r.signatures.get(sig).total).isEqualTo(12);
        assertThat(r.signatures.get(sig).recorded).as("ndjson records capped per signature").isEqualTo(8);
        try (var lines = Files.lines(tmp.resolve("failures.ndjson"))) {
            assertThat(lines.count()).as("failures.ndjson lines written").isEqualTo(8);
        }
    }
}
