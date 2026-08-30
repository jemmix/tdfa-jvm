package io.github.jemmix.tdfa.parity;

import io.github.jemmix.tdfa.sim.PikeSim;
import io.github.jemmix.tdfa.unicode.UnicodeDataProvider;
import io.github.jemmix.tdfa.unicode.UnicodeProviders;

/**
 * Layered differential comparator: one case, four engines, one pre-localized
 * verdict. Splits the end-to-end oracle comparison into two legs so a failure
 * arrives with its layer named instead of requiring manual bisection:
 *
 * <ul>
 *   <li>{@code R} = re2j — the external oracle (parser + everything)</li>
 *   <li>{@code S} = PikeSim over our Tnfa — the internal reference (parser +
 *       NFA, no determinizer)</li>
 *   <li>{@code V} = the VM tier (DFA + interpreter)</li>
 *   <li>{@code A} = the ASM tier (DFA + emitted code)</li>
 * </ul>
 *
 * Verdicts (total over the four columns — see {@link #classify}):
 * <ul>
 *   <li>{@code PASS} — all four agree</li>
 *   <li>{@code TIER} — V ≠ A: the tiers disagree with each other (codegen)</li>
 *   <li>{@code CONSTRUCTION} — V == A ≠ S, S == R: the DFA diverges from our
 *       own NFA; the oracle sides with the NFA. This is where every fuzz
 *       round 3–6 determinizer bug landed.</li>
 *   <li>{@code PARSER} — V == A == S ≠ R: the whole stack agrees with itself;
 *       the divergence originates in parser/NFA semantics (ſ-folding lived
 *       here; so do deliberate ones like lone-surrogate boundaries — the
 *       KNOWN_DIVERGENCE classifier composes on top)</li>
 *   <li>{@code SIM_SUSPECT} — V == R ≠ S: 2-vs-1 the other way; the reference
 *       itself is wrong</li>
 *   <li>{@code CHAOS} — three different answers; everything suspect</li>
 * </ul>
 *
 * <p>Used at three sites: failure-time attribution in the parity tests
 * (zero cost on the green path), per-failure layer fields in the fuzzer's
 * records, and as a CLI probe for debugging sessions.
 */
public final class LayeredComparator {

    public enum Layer { PASS, TIER, CONSTRUCTION, PARSER, SIM_SUSPECT, CHAOS }

    public record Report(Layer layer, String re2j, String sim, String vm, String asm) {
        /** One-line human attribution, e.g. {@code layer=CONSTRUCTION (vm==asm != sim; sim==re2j)}. */
        public String attribution() {
            return switch (layer) {
                case PASS -> "layer=PASS";
                case TIER -> "layer=TIER (vm != asm — tier/codegen divergence)";
                case CONSTRUCTION -> "layer=CONSTRUCTION (vm==asm != sim; sim==re2j — determinizer/tables)";
                case PARSER -> "layer=PARSER (vm==asm==sim != re2j — parser/NFA semantics)";
                case SIM_SUSPECT -> "layer=SIM_SUSPECT (vm==re2j != sim — reference bug?)";
                case CHAOS -> "layer=CHAOS (three distinct answers)";
            };
        }
    }

    private final UnicodeDataProvider provider;

    public LayeredComparator(UnicodeDataProvider provider) {
        this.provider = provider;
    }

    public Report compare(String pattern, String input) {
        String[] cols = run(pattern, input);
        return new Report(classify(cols), cols[0], cols[1], cols[2], cols[3]);
    }

    private String[] run(String pattern, String input) {
        String[] out = new String[4];
        out[0] = re2jProtocol(pattern, input);
        out[1] = simProtocol(pattern, input);
        out[2] = engineProtocol(pattern, input, true);
        out[3] = engineProtocol(pattern, input, false);
        return out;
    }

    /**
     * The classification table — total over the four protocol strings. This
     * is the entire "bisection": no search, one vote. Package-private seam
     * for the synthetic verdict-table test (a wrong classification here is
     * the tool lying about localization).
     */
    static Layer classify(String[] cols) {
        String r = cols[0], s = cols[1], v = cols[2], a = cols[3];
        boolean va = v.equals(a);
        boolean vs = v.equals(s);
        boolean sr = s.equals(r);
        if (va && vs && sr) return Layer.PASS;
        if (!va) return Layer.TIER;
        if (vs && !sr) return Layer.PARSER;
        if (!vs && sr) return Layer.CONSTRUCTION;
        if (!vs && !sr && v.equals(r)) return Layer.SIM_SUSPECT;
        return Layer.CHAOS;
    }

    /**
     * "span g1=.." protocol; exceptions as values (a crashing column counts).
     * Compile-phase rejections normalize to {@code <reject>} — the three
     * engines throw three different exception CLASSES for the same "bad
     * pattern" (re2j PatternSyntaxException, the sim IllegalArgumentException),
     * and class-name differences would masquerade as engine divergence.
     */
    static String re2jProtocol(String p, String in) {
        com.google.re2j.Pattern pat;
        try {
            pat = com.google.re2j.Pattern.compile(p);
        } catch (Throwable t) {
            return "<reject>";
        }
        try {
            var m = pat.matcher(in);
            if (!m.find()) return "no";
            return fmt(m.start(), m.end(), g -> {
                try { return m.group(g); } catch (RuntimeException e) { return null; }
            }, m.groupCount());
        } catch (Throwable t) {
            return "<exception:" + t.getClass().getSimpleName() + ">";
        }
    }

    static String simProtocol(String p, String in, UnicodeDataProvider provider) {
        PikeSim sim;
        try {
            sim = PikeSim.compile(p, provider);
        } catch (Throwable t) {
            return "<reject>";
        }
        try {
            var m = sim.matcher(in);
            if (!m.find()) return "no";
            return fmt(m.start(), m.end(), m::group, m.groupCount());
        } catch (Throwable t) {
            return "<exception:" + t.getClass().getSimpleName() + ">";
        }
    }

    static String engineProtocol(String p, String in, boolean vm, UnicodeDataProvider provider) {
        io.github.jemmix.tdfa.Pattern pat;
        try {
            pat = io.github.jemmix.tdfa.Pattern.compile(p, 0,
                    vm ? io.github.jemmix.tdfa.tdfa.TdfaRunner::new : null, provider);
        } catch (Throwable t) {
            return "<reject>";
        }
        try {
            var m = pat.matcher(in);
            if (!m.find()) return "no";
            return fmt(m.start(), m.end(), g -> {
                try { return m.group(g); } catch (RuntimeException e) { return null; }
            }, m.groupCount());
        } catch (Throwable t) {
            return "<exception:" + t.getClass().getSimpleName() + ">";
        }
    }

    private interface GroupFn { String get(int g); }

    private static String fmt(int start, int end, GroupFn g, int groupCount) {
        StringBuilder sb = new StringBuilder(start + ".." + end);
        for (int i = 1; i <= groupCount; i++) {
            String v = g.get(i);
            sb.append(" g").append(i).append('=').append(v == null ? "null" : "'" + v + "'");
        }
        return sb.toString();
    }

    // -- per-instance protocol shorthands (provider-threaded) --

    private String simProtocol(String p, String in) { return simProtocol(p, in, provider); }

    private String engineProtocol(String p, String in, boolean vm) { return engineProtocol(p, in, vm, provider); }

    /**
     * CLI probe: {@code LayeredComparator <pattern> <input>} — prints the four
     * columns and the verdict. The default provider is the JDK one; tests
     * thread their own. This formalizes the ad-hoc debug probes the fuzz
     * rounds kept re-inventing.
     */
    public static void main(String[] args) {
        if (args.length < 2) {
            System.err.println("usage: LayeredComparator <pattern> <input>");
            System.exit(2);
        }
        LayeredComparator c = new LayeredComparator(UnicodeProviders.get());
        Report rep = c.compare(args[0], args[1]);
        System.out.println("re2j: " + rep.re2j());
        System.out.println("sim : " + rep.sim());
        System.out.println("vm  : " + rep.vm());
        System.out.println("asm : " + rep.asm());
        System.out.println(rep.attribution());
    }
}
