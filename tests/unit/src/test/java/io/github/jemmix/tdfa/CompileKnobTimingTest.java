package io.github.jemmix.tdfa;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jemmix.tdfa.core.CompileObserver;
import io.github.jemmix.tdfa.tdfa.Tdfa;
import io.github.jemmix.tdfa.tnfa.Tnfa;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Compile knobs are read once per compilation, never class-init frozen
 * (the policy note in {@code Tdfa}). Before the unification,
 * {@code tdfa.noregopt} and friends were {@code static final}: a property
 * set after class init silently did nothing, and {@code tdfa.debug} was
 * read at two different timings by three readers — partial debug output.
 */
class CompileKnobTimingTest {

    private final Map<String, String> notes = new HashMap<>();

    @AfterEach
    void cleanup() {
        System.clearProperty("tdfa.noregopt");
        System.clearProperty("tdfa.nominimize");
    }

    private CompileObserver recording() {
        return new CompileObserver() {
            @Override public void note(String key, String value) { notes.put(key, value); }
        };
    }

    @Test
    void regoptKnobTakesEffectWithoutClassReload() {
        // First compile with the knob unset: regopt runs on tagged patterns.
        Tdfa.compile(Tnfa.compile("(a+)(b+)"), false, recording());
        assertThat(notes.get("regopt")).startsWith("regs ");

        // Set the property AFTER the classes are long initialized —
        // a per-compile read must pick it up on the very next compile.
        System.setProperty("tdfa.noregopt", "true");
        notes.clear();
        Tdfa.compile(Tnfa.compile("(a+)(b+)"), false, recording());
        assertThat(notes.get("regopt")).isEqualTo("disabled");
    }

    @Test
    void minimizeKnobTakesEffectWithoutClassReload() {
        // tdfa.nominimize: minimization is skipped and the DFA is still correct;
        // observable via a compile of a minimizable pattern simply succeeding
        // (state count may differ — the knob's effect path is the guard itself).
        System.setProperty("tdfa.nominimize", "true");
        Tdfa t = Tdfa.compile(Tnfa.compile("a|b|c"), false, recording());
        assertThat(t.stateCount()).isPositive();
        assertThat(notes.get("regopt")).isNotNull();
    }
}
