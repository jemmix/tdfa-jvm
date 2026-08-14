package io.github.jemmix.tdfa.tdfa;
import io.github.jemmix.tdfa.parser.Parser;
import io.github.jemmix.tdfa.tnfa.Tnfa;
public class DumpRanges {
    public static void main(String[] a) throws Exception {
        for (String rx : new String[]{"(?u)\\b\\w{12,}\\b", "(?u)\\b\\w+\\b", "(?u)\\p{L}{256}", "Twain", "(?u)[a-zA-Z]+ing"}) {
            var ast = Parser.parse(rx);
            Tnfa nfa = Tnfa.compile(rx, false, false, io.github.jemmix.tdfa.unicode.UnicodeProviders.get());
            Tdfa tdfa = Tdfa.compile(nfa, Disambiguation.PERL);
            int states = tdfa.stateCount;
            int maxCount = 0, tot = 0;
            for (int s = 0; s < states; s++) {
                int meta = tdfa.stateMeta[s];
                int count = (meta >>> 1) & 0xFFFF;
                maxCount = Math.max(maxCount, count);
                tot += count;
            }
            // per-state disjointness (same sweep as checkRangesDisjoint)
            int disStates = 0, disEntries = 0, linStates = 0, linEntries = 0;
            for (int s = 0; s < states; s++) {
                int meta = tdfa.stateMeta[s];
                int base = tdfa.stateBase[s], cnt = (meta >>> 1) & 0xFFFF;
                boolean sorted = true;
                for (int i = 1; i < cnt; i++)
                    if (tdfa.ranges[(base + i) * 5] < tdfa.ranges[(base + i - 1) * 5]) { sorted = false; break; }
                boolean disjoint = sorted;
                if (sorted && cnt > 1) {
                    int maxHi = tdfa.ranges[base * 5 + 1];
                    for (int i = 1; i < cnt; i++) {
                        int o = (base + i) * 5;
                        if (tdfa.ranges[o] <= maxHi) { disjoint = false; break; }
                        maxHi = tdfa.ranges[o + 1];
                    }
                }
                if (disjoint) { disStates++; disEntries += cnt; } else { linStates++; linEntries += cnt; }
            }
            System.out.printf("%-22s states=%4d entries max=%4d avg=%7.1f | disjoint: %d states/%d entries | linear: %d states/%d entries%n",
                rx, states, maxCount, (double) tot / states, tot, disStates, disEntries, linStates, linEntries);
        }
    }
}
