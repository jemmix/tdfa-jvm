package io.github.jemmix.tdfa;

import io.github.jemmix.tdfa.core.budget.PatternTooLargeException;
import io.github.jemmix.tdfa.core.compile.CompiledRegex;
import io.github.jemmix.tdfa.core.dfa.TdfaRunner;
import io.github.jemmix.tdfa.core.engine.MatchResult;
import io.github.jemmix.tdfa.core.engine.MatchScratch;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The FIND_ONLY compile shape ({@link Pattern#FIND_ONLY} /
 * {@link Pattern#compileFind}): a compile for consumers who never call
 * whole-input methods. The whole machinery is not even attempted — the
 * plain pruned determinization, no partial-whole side table, no
 * exactness gate — so patterns whose whole divergence rejects a full
 * compile ("pattern too large: whole-match divergence", pinned in
 * {@code PartialWholeTest}) are accepted, and every whole-input method
 * on the result refuses with {@link UnsupportedOperationException} at
 * match time. Pinned here:
 * <ul>
 *   <li>the whole bomb compiles find-only and finds, on both engine tiers
 *       (generated shells and the VM switch), while the full compile
 *       rejects it;</li>
 *   <li>every whole entry point ({@code Pattern.matches(String)/(byte[])}
 *       and {@code Matcher.matches()}) throws UOE with a message naming
 *       FIND_ONLY and the pattern, on both tiers — the generated shell's
 *       inlined {@code matches()} funnels through the same refusing
 *       engine;</li>
 *   <li>the find surface is bit-identical to a full compile (and to the
 *       core tier's plain compile): find rows, lookingAt, split,
 *       replaceAll — the divergence-class catalog plus the bomb;</li>
 *   <li>the shape round-trips serialization (flags carry the bit) and is
 *       distinct under equals/hashCode;</li>
 *   <li>the core tier ({@code CompiledRegex}) is the standing find-only
 *       API — facade find-only answers agree with it.</li>
 * </ul>
 */
class FindOnlyCompileTest {

    /** The nested-counted whole bomb from PartialWholeTest: find fits, whole divergence does not. */
    private static final String BOMB = "(a{1,100}){1,100}";

    private static final String[] CATALOG = {"(a|ab)", "ab|a|ac", "ax?|a.y", "(a)(b|bc)", "(a)|(ab)", "(ab|a)+",
        "(a|ab)+", "(a??b??)*", "(a{1,3}?)b", "(?:ab|a)(?:c|bcd)", "((a)|b)+", "a*?b", "(a|ab)(c|bcd)?", "(x{0,2}?)x?",
        "(a+?)(a*)", "(|a)b?", "((?:a|ab)??c?){1,2}", "a(?:b|bc)*", "(a|ab)\\b", "(a|ab)?c", "(a|ab)(?:$)?"};

    private static final String[] INPUTS =
        {"", "a", "ab", "abc", "ac", "b", "bc", "aab", "abab", "ababc", "aaaa", "aaab", "acb", "abcd", "x"};

    @AfterEach
    void clearKnobs() {
        System.clearProperty("tdfa.engine");
    }

    /**
     * The escape hatch works where the default rejects: the whole bomb's
     * find artifact compiles under FIND_ONLY (both engine tiers, factory
     * and flag spellings) and finds — no side exploration ran, so no
     * whole budget was ever drawn.
     */
    @Test
    void wholeBombCompilesFindOnlyAndFinds() {
        assertThatThrownBy(() -> Pattern.compile(BOMB)).isInstanceOf(PatternTooLargeException.class)
            .hasMessageContaining("whole-match divergence");
        long t0 = System.nanoTime();
        for (boolean vm : new boolean[]{false, true}) {
            if (vm) {
                System.setProperty("tdfa.engine", "VM");
            }
            Pattern p = Pattern.compileFind(BOMB);
            assertThat(p.flags() & Pattern.FIND_ONLY).as("flag bit set (vm=%s)", vm).isNotZero();
            PatternMatcher m = p.matcher("xxaaaa");
            assertThat(m.find()).as("find works (vm=%s)", vm).isTrue();
            assertThat(m.group()).isEqualTo("aaaa");
            assertThat(p.matcher("a".repeat(250)).find()).as("long input (vm=%s)", vm).isTrue();
        }
        Pattern viaFlag = Pattern.compile(BOMB, Pattern.FIND_ONLY);
        assertThat(viaFlag.matcher("aa").find()).isTrue();
        assertThat((System.nanoTime() - t0) / 1_000_000).as("no whole machinery ran (wall)").isLessThan(30_000);
    }

    /**
     * Every whole-input entry point refuses with UOE naming FIND_ONLY and
     * the pattern — Pattern.matches both overloads and Matcher.matches(),
     * on both engine tiers (the generated shell's inlined matches() calls
     * wholeEngine().matchWhole, the refusing engine).
     */
    @Test
    void wholeSurfaceThrowsUoeOnBothTiers() {
        for (boolean vm : new boolean[]{false, true}) {
            if (vm) {
                System.setProperty("tdfa.engine", "VM");
            }
            Pattern p = Pattern.compileFind("(a|ab)");
            assertThatThrownBy(() -> p.matches("ab")).as("Pattern.matches(String) (vm=%s)", vm)
                .isInstanceOf(UnsupportedOperationException.class).hasMessageContaining("FIND_ONLY")
                .hasMessageContaining("(a|ab)");
            assertThatThrownBy(() -> p.matches("ab".getBytes())).as("Pattern.matches(byte[]) (vm=%s)", vm)
                .isInstanceOf(UnsupportedOperationException.class).hasMessageContaining("FIND_ONLY");
            assertThatThrownBy(() -> p.matcher("ab").matches()).as("Matcher.matches() (vm=%s)", vm)
                .isInstanceOf(UnsupportedOperationException.class).hasMessageContaining("FIND_ONLY");
            // The refusing engine itself (the generated shell's matches()
            // funnels through it by contract).
            assertThatThrownBy(() -> ((TDFAPattern) p).wholeEngine().matchWhole("ab", new MatchScratch()))
                .isInstanceOf(UnsupportedOperationException.class);
        }
    }

    /**
     * UOE even on patterns whose whole side would have built trivially:
     * FIND_ONLY is a consumer contract, not an internal artifact switch.
     */
    @Test
    void uoeEvenWhenWholeWouldHaveBuilt() {
        Pattern p = Pattern.compileFind("abc");
        assertThatThrownBy(() -> p.matcher("abc").matches()).isInstanceOf(UnsupportedOperationException.class);
        // LONGEST_MATCH compiles have no side anyway (no pike cut in POSIX
        // mode) — the whole surface still refuses, uniformly.
        Pattern posix = Pattern.compileFind("(a|ab)", Pattern.LONGEST_MATCH);
        PatternMatcher pm = posix.matcher("ab");
        assertThat(pm.find()).isTrue();
        assertThat(pm.end()).isEqualTo(2);
        assertThatThrownBy(() -> posix.matcher("ab").matches()).isInstanceOf(UnsupportedOperationException.class);
    }

    /**
     * The find surface is untouched by the shape: identical rows through
     * a full compile and a find-only compile over the divergence-class
     * catalog (both engine tiers), including the find-shaped extras
     * (lookingAt, split, replaceAll) and flag combinations.
     */
    @Test
    void findSurfaceIdenticalToFullCompile() {
        for (boolean vm : new boolean[]{false, true}) {
            if (vm) {
                System.setProperty("tdfa.engine", "VM");
            }
            for (String p : CATALOG) {
                assertThat(findRows(p, 0)).as("find rows (vm=%s): %s", vm, p).isEqualTo(findRows(p, Pattern.FIND_ONLY));
            }
        }
        // find-shaped extras on a divergence pattern
        Pattern full = Pattern.compile("(a|ab)c");
        Pattern fo = Pattern.compileFind("(a|ab)c");
        assertThat(fo.matcher("abc").lookingAt()).isEqualTo(full.matcher("abc").lookingAt());
        assertThat(fo.split("xabcyabc")).containsExactly("x", "y");
        assertThat(full.split("xabcyabc")).containsExactly("x", "y");
        assertThat(fo.matcher("xabcy").replaceAll("[$0]")).isEqualTo("x[abc]y");
        assertThat(full.matcher("xabcy").replaceAll("[$0]")).isEqualTo("x[abc]y");
        // flags still fold
        assertThat(Pattern.compileFind("(A|AB)", Pattern.CASE_INSENSITIVE).matcher("xab").find()).isTrue();
    }

    /**
     * The facade's find-only compile agrees with the core tier's standing
     * find-only API (the plain compile, no side): same rows.
     */
    @Test
    void agreesWithCoreTierFindOnly() {
        for (String p : CATALOG) {
            List<String> facade = findRows(p, Pattern.FIND_ONLY);
            List<String> core = new ArrayList<>();
            CompiledRegex r = CompiledRegex.compile(p);
            for (String s : INPUTS) {
                StringBuilder row = new StringBuilder();
                for (MatchResult m : r.findAll(s)) {
                    row.append('[').append(m.start(0)).append(',').append(m.end(0)).append(')');
                    for (int g = 1; g <= m.groupCount(); g++) {
                        row.append(';').append(m.start(g) < 0 ? "-" : m.start(g) + "," + m.end(g));
                    }
                    row.append(' ');
                }
                core.add(row.toString());
            }
            assertThat(facade).as("facade find-only == core plain compile: %s", p).isEqualTo(core);
        }
    }

    /**
     * The shape round-trips serialization: the SerialProxy carries the
     * flags, the reader recompiles find-only, and both halves of the
     * contract survive (find works, whole still refuses).
     */
    @Test
    void serializationRoundTripKeepsFindOnly() throws Exception {
        Pattern p = Pattern.compileFind("(?<w>\\w+)-(\\d+)");
        Pattern copy;
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ObjectOutputStream out = new ObjectOutputStream(bos)) {
            out.writeObject(p);
        }
        try (ObjectInputStream in = new ObjectInputStream(new ByteArrayInputStream(bos.toByteArray()))) {
            copy = (Pattern) in.readObject();
        }
        assertThat(copy.flags() & Pattern.FIND_ONLY).isNotZero();
        PatternMatcher m = copy.matcher("order-77");
        assertThat(m.find()).isTrue();
        assertThat(m.group("w")).isEqualTo("order");
        assertThatThrownBy(() -> copy.matcher("order-77").matches()).isInstanceOf(UnsupportedOperationException.class);
    }

    /**
     * The shape is visible in the object contract: equals/hashCode follow
     * pattern+flags (re2j semantics), so a find-only compile is NOT the
     * full compile of the same regex; unknown bits still reject, and the
     * message names the new flag.
     */
    @Test
    void equalityFlagsAndValidation() {
        Pattern full = Pattern.compile("a+");
        Pattern fo = Pattern.compileFind("a+");
        assertThat(fo).isNotEqualTo(full);
        assertThat(fo.hashCode()).isNotEqualTo(full.hashCode());
        assertThat(fo.pattern()).isEqualTo("a+");
        assertThat(Pattern.compileFind("a+", Pattern.CASE_INSENSITIVE | Pattern.DOTALL).flags())
            .isEqualTo(Pattern.FIND_ONLY | Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
        assertThatThrownBy(() -> Pattern.compile("a+", 1 << 20)).isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("FIND_ONLY");
        // BYO factory engines also take the shape
        Pattern byo = Pattern.compile("a+", Pattern.FIND_ONLY, TdfaRunner::new);
        assertThat(byo.matcher("xxaa").find()).isTrue();
        assertThatThrownBy(() -> byo.matches("aa")).isInstanceOf(UnsupportedOperationException.class);
    }

    private static List<String> findRows(String pattern, int extraFlags) {
        List<String> out = new ArrayList<>();
        Pattern p = Pattern.compile(pattern, extraFlags);
        for (String s : INPUTS) {
            PatternMatcher m = p.matcher(s);
            StringBuilder row = new StringBuilder();
            while (m.find()) {
                row.append('[').append(m.start()).append(',').append(m.end()).append(')');
                for (int g = 1; g <= m.groupCount(); g++) {
                    row.append(';').append(m.start(g) < 0 ? "-" : m.start(g) + "," + m.end(g));
                }
                row.append(' ');
            }
            out.add(row.toString());
        }
        return out;
    }
}
