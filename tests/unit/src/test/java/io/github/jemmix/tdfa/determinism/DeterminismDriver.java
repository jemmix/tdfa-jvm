package io.github.jemmix.tdfa.determinism;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Forked-JVM driver for the deterministic-compilation gate: compiles the
 * {@link DeterminismCorpus} in a fresh JVM and prints one stable
 * {@code id<TAB>fingerprint} line per entry to STDOUT (nothing else — the
 * test diffs forked runs line-for-line). Within the run it also compiles
 * the corpus in REVERSE and in a fixed odd-even interleave, asserting
 * in-process that compile order (and any lazily initialized static tables
 * the earlier compiles touched) never changes an artifact; order
 * mismatches go to STDERR with exit code 1.
 */
public final class DeterminismDriver {
    private DeterminismDriver() {
    }

    public static void main(String[] args) {
        List<DeterminismCorpus.Entry> corpus = DeterminismCorpus.corpus();
        Map<String, String> first = compileAll(corpus);
        for (Map.Entry<String, String> e : first.entrySet()) {
            System.out.println(e.getKey() + "\t" + e.getValue());
        }
        Map<String, String> reversed = compileAll(reversed(corpus));
        Map<String, String> interleaved = compileAll(interleaved(corpus));
        List<String> failures = new ArrayList<>();
        for (Map.Entry<String, String> e : first.entrySet()) {
            if (!e.getValue().equals(reversed.get(e.getKey())) || !e.getValue().equals(interleaved.get(e.getKey()))) {
                failures.add(e.getKey() + ": " + e.getValue() + " / " + reversed.get(e.getKey()) + " / "
                    + interleaved.get(e.getKey()));
            }
        }
        if (!failures.isEmpty()) {
            for (String f : failures) {
                System.err.println("[determinism] ORDER-DEPENDENT: " + f);
            }
            System.exit(1);
        }
    }

    private static Map<String, String> compileAll(List<DeterminismCorpus.Entry> entries) {
        Map<String, String> fps = new LinkedHashMap<>();
        for (DeterminismCorpus.Entry e : entries) {
            fps.put(e.id(), DeterminismCorpus.compileFingerprint(e));
        }
        return fps;
    }

    private static List<DeterminismCorpus.Entry> reversed(List<DeterminismCorpus.Entry> corpus) {
        List<DeterminismCorpus.Entry> out = new ArrayList<>(corpus);
        Collections.reverse(out);
        return out;
    }

    private static List<DeterminismCorpus.Entry> interleaved(List<DeterminismCorpus.Entry> corpus) {
        List<DeterminismCorpus.Entry> out = new ArrayList<>(corpus.size());
        for (int i = 1; i < corpus.size(); i += 2) {
            out.add(corpus.get(i));
        }
        for (int i = 0; i < corpus.size(); i += 2) {
            out.add(corpus.get(i));
        }
        return out;
    }
}
