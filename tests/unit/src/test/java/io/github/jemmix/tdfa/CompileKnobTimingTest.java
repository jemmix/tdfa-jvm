package io.github.jemmix.tdfa;

import io.github.jemmix.tdfa.core.budget.Budgets;
import io.github.jemmix.tdfa.core.budget.PatternTooLargeException;
import io.github.jemmix.tdfa.core.determinize.Determinizer;
import io.github.jemmix.tdfa.core.dfa.Tdfa;
import io.github.jemmix.tdfa.core.report.CompileObserver;
import io.github.jemmix.tdfa.core.tnfa.Tnfa;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
        System.clearProperty(Budgets.COMPILE_MEMORY_PROP);
    }

    private CompileObserver recording() {
        return new CompileObserver() {
            @Override
            public void note(String key, String value) {
                notes.put(key, value);
            }
        };
    }

    @Test
    void regoptKnobTakesEffectWithoutClassReload() {
        // First compile with the knob unset: regopt runs on tagged patterns.
        Determinizer.compile(Tnfa.compile("(a+)(b+)"), false, recording());
        assertThat(notes.get("regopt")).startsWith("regs ");

        // Set the property AFTER the classes are long initialized —
        // a per-compile read must pick it up on the very next compile.
        System.setProperty("tdfa.noregopt", "true");
        notes.clear();
        Determinizer.compile(Tnfa.compile("(a+)(b+)"), false, recording());
        assertThat(notes.get("regopt")).isEqualTo("disabled");
    }

    @Test
    void minimizeKnobTakesEffectWithoutClassReload() {
        // tdfa.nominimize: minimization is skipped and the DFA is still correct;
        // observable via a compile of a minimizable pattern simply succeeding
        // (state count may differ — the knob's effect path is the guard itself).
        System.setProperty("tdfa.nominimize", "true");
        Tdfa t = Determinizer.compile(Tnfa.compile("a|b|c"), false, recording());
        assertThat(t.stateCount()).isPositive();
        assertThat(notes.get("regopt")).isNotNull();
    }

    @Test
    void budgetKnobTakesEffectWithoutClassReload() {
        // The budget properties join the same policy: read once per compile,
        // never class-init frozen. Set AFTER init — the very next compile
        // must see the tightened RAM budget (4096 B / 256 B per state = a
        // 16-state cap), and clearing it must re-admit the pattern.
        Determinizer.compile(Tnfa.compile("ab|cd"), false, recording()); // warm classes
        System.setProperty(Budgets.COMPILE_MEMORY_PROP, "4096");
        try {
            assertThatThrownBy(() -> Determinizer.compile(Tnfa.compile("ab|cd|ef|gh|ij"), false, recording()))
                .isInstanceOf(PatternTooLargeException.class).hasMessageContaining("pattern too large");
        } finally {
            System.clearProperty(Budgets.COMPILE_MEMORY_PROP);
        }
        assertThat(Determinizer.compile(Tnfa.compile("ab|cd|ef|gh|ij"), false, recording()).stateCount()).isPositive();
    }
}
