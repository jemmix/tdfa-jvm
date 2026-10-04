package io.github.jemmix.tdfa.determinism;

import io.github.jemmix.tdfa.core.budget.Budgets;
import io.github.jemmix.tdfa.core.budget.WorkMeter;
import io.github.jemmix.tdfa.core.determinize.Determinizer;
import io.github.jemmix.tdfa.core.dfa.Tdfa;
import io.github.jemmix.tdfa.core.tnfa.Tnfa;

import java.util.ArrayList;
import java.util.List;

/**
 * Corpus for the deterministic-compilation gate. Every family that exercises
 * a distinct pipeline branch is represented: tagless stepping, tagged
 * closures with register optimization (nested counted repeats), the pike
 * cut and its hazard ladder, assertion-context splitting with dead markers,
 * empty-iteration subsumption cuts, final-φ variants under anchors,
 * minimizer merging, case-fold and wide-Unicode breakpoints, named groups,
 * and the POSIX (leftmost-longest) tier. Each entry is compiled under the
 * artifact mode it names; knob entries additionally pin the
 * minimization/regopt-disabled paths, which must be deterministic per knob
 * setting just like the defaults.
 */
final class DeterminismCorpus {
    enum Mode {
        PERL, LONGEST, UNPRUNED, MULTI
    }

    enum Knob {
        NONE, NOMINIMIZE, NOREGOPT
    }

    record Entry(String id, String pattern, Mode mode, Knob knob) {
    }

    private DeterminismCorpus() {
    }

    static List<Entry> corpus() {
        List<Entry> out = new ArrayList<>();
        add(out, "plain", "a", Mode.PERL, Knob.NONE);
        add(out, "plain-l", "a", Mode.LONGEST, Knob.NONE);
        add(out, "tagless", "ab+c|d*[e-g]\\s?h", Mode.PERL, Knob.NONE);
        add(out, "tagless-l", "ab+c|d*[e-g]\\s?h", Mode.LONGEST, Knob.NONE);
        add(out, "captures", "(a(b c?)?)+", Mode.PERL, Knob.NONE);
        add(out, "alt-captures", "(cat)|(dog)|(bird)|(fish)", Mode.PERL, Knob.NONE);
        add(out, "alt-captures-l", "(cat)|(dog)|(bird)|(fish)", Mode.LONGEST, Knob.NONE);
        add(out, "minimize-merge", "(a|ab)(b|c)", Mode.PERL, Knob.NONE);
        add(out, "pike-cut", "(a|ab)(c|bcd)", Mode.PERL, Knob.NONE);
        add(out, "pike-cut-u", "(a|ab)(c|bcd)", Mode.UNPRUNED, Knob.NONE);
        add(out, "empty-iter", "(?:.*?9{0,}\\b){1,}", Mode.PERL, Knob.NONE);
        add(out, "empty-iter-u", "(?:.*?9{0,}\\b){1,}", Mode.UNPRUNED, Knob.NONE);
        add(out, "junction", "(?:^|$)+", Mode.PERL, Knob.NONE);
        add(out, "final-variants", "(\\B)*\\z", Mode.PERL, Knob.NONE);
        add(out, "lazy-skip", "(\\A)??.+", Mode.PERL, Knob.NONE);
        add(out, "word-pairs", ".+\\b.", Mode.PERL, Knob.NONE);
        add(out, "counted-lazy", "(?:..{3,5}?\\B)+\\S", Mode.PERL, Knob.NONE);
        add(out, "dead-markers", "\\D+?\\s*\\B", Mode.PERL, Knob.NONE);
        add(out, "nested-repeats", "(a{1,20}){1,20}", Mode.PERL, Knob.NONE);
        add(out, "nested-repeats-l", "(a{1,20}){1,20}", Mode.LONGEST, Knob.NONE);
        add(out, "bounded-any", "[\\s\\S]{0,30}x[\\s\\S]{0,30}", Mode.PERL, Knob.NONE);
        add(out, "named", "(?<word>\\w+)\\s+(?<num>\\d+)", Mode.PERL, Knob.NONE);
        add(out, "fold-ascii", "(?i)[a-z]+", Mode.PERL, Knob.NONE);
        add(out, "fold-class", "(?i)\\p{Lu}+", Mode.PERL, Knob.NONE);
        add(out, "wide-class", "\\p{L}{8}", Mode.PERL, Knob.NONE);
        add(out, "wide-anchors", "(?u)\\b\\w{12,}\\b", Mode.PERL, Knob.NONE);
        add(out, "nominimize", "(a{1,20}){1,20}", Mode.PERL, Knob.NOMINIMIZE);
        add(out, "noregopt", "(a{1,20}){1,20}", Mode.PERL, Knob.NOREGOPT);
        // Multi-valued lane (BT22 §3.1): append ops, no fixed tags, no
        // regopt, exact+rename state dedup — its own deterministic branch.
        add(out, "multi", "(a(b c?)?)+", Mode.MULTI, Knob.NONE);
        add(out, "multi-alt", "(cat)|(dog)|(bird)|(fish)", Mode.MULTI, Knob.NONE);
        add(out, "multi-pike", "(a|ab)(c|bcd)", Mode.MULTI, Knob.NONE);
        add(out, "multi-counted", "(a{1,20}){1,20}", Mode.MULTI, Knob.NONE);
        add(out, "multi-empty-iter", "(?:.*?9{0,}\b){1,}", Mode.MULTI, Knob.NONE);
        add(out, "multi-anchors", "(\\B)*\\z", Mode.MULTI, Knob.NONE);
        return out;
    }

    private static void add(List<Entry> out, String id, String pattern, Mode mode, Knob knob) {
        out.add(new Entry(id, pattern, mode, knob));
    }

    static String compileFingerprint(Entry e) {
        String prop = switch (e.knob()) {
            case NOMINIMIZE -> "tdfa.nominimize";
            case NOREGOPT -> "tdfa.noregopt";
            case NONE -> null;
        };
        if (prop != null) {
            System.setProperty(prop, "true"); // read per compile (knob policy)
        }
        try {
            return switch (e.mode()) {
                case PERL -> ArtifactFingerprint.of(Determinizer.compile(Tnfa.compile(e.pattern()), false));
                case LONGEST -> ArtifactFingerprint.of(Determinizer.compile(Tnfa.compile(e.pattern()), true));
                case UNPRUNED ->
                    ArtifactFingerprint.of(Determinizer.compileUnpruned(Tnfa.compile(e.pattern()), false, null));
                case MULTI -> ArtifactFingerprint.of(Determinizer.compile(
                    Tnfa.compileMulti(e.pattern(), false, null, null, new WorkMeter(Budgets.compileComputeTicks())),
                    false));
            };
        } finally {
            if (prop != null) {
                System.clearProperty(prop);
            }
        }
    }
}
