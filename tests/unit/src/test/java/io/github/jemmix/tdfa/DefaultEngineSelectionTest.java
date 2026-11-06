package io.github.jemmix.tdfa;

import io.github.jemmix.tdfa.core.compile.PatternMatcher;
import io.github.jemmix.tdfa.core.compile.TDFAPattern;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the {@code tdfa.engine} system-property wiring of
 * {@link Pattern#compile}: unset (or {@code ASM}) selects per-pattern
 * generation; {@code VM} forces the shared interpreter implementation
 * everywhere (global no-codegen switch). Read per compile, so settable
 * in-process.
 */
class DefaultEngineSelectionTest {

    @Test
    void defaultCompileUsesGeneration() {
        String prev = System.getProperty("tdfa.engine");
        try {
            System.clearProperty("tdfa.engine");
            Pattern p = Pattern.compile("abc");
            // The facade hands out its generated wrapper (WrapNNN), and the
            // matcher it produces is the emitted core pair (GenNNNMatcher)
            // — the devirtualized hot path lives under the wrapper.
            assertThat(p.getClass().getSimpleName()).as("generated wrapper expected").startsWith("Wrap");
            assertThat(p).isNotInstanceOf(TDFAPattern.class);
            assertThat(p.matcher("abc").getClass().getSimpleName()).as("emitted matcher expected").startsWith("Gen")
                .endsWith("Matcher");
            assertThat(p.matcher("abc").matches()).isTrue();
            assertThat(p.matcher("abcd").matches()).isFalse();
        } finally {
            restore(prev);
        }
    }

    @Test
    void vmSwitchForcesSharedImplementation() {
        String prev = System.getProperty("tdfa.engine");
        try {
            System.setProperty("tdfa.engine", "VM");
            Pattern p = Pattern.compile("abc");
            // The no-codegen path still hands out the facade's generated
            // wrapper (one cached Wrap class for the interpreter tier),
            // wrapping the bare machinery instance — and the matcher is
            // the shared interpreter carrier, not an emitted class.
            assertThat(p.getClass().getSimpleName()).as("interpreter-tier wrapper expected").startsWith("Wrap");
            assertThat(p).isNotInstanceOf(TDFAPattern.class);
            assertThat(p.matcher("abc").getClass()).isEqualTo(PatternMatcher.class);
            assertThat(p.matcher("abc").matches()).isTrue();
        } finally {
            restore(prev);
        }
    }

    @Test
    void staticMatchesUsesDefault() {
        assertThat(Pattern.matches("abc", "abc")).isTrue();
        assertThat(Pattern.matches("abc", "abd")).isFalse();
    }

    @Test
    void flagsCompileUsesDefault() {
        Pattern p = Pattern.compile("ABC", Pattern.CASE_INSENSITIVE);
        assertThat(p.matcher("abc").matches()).isTrue();
    }

    private static void restore(String prev) {
        if (prev == null) {
            System.clearProperty("tdfa.engine");
        } else {
            System.setProperty("tdfa.engine", prev);
        }
    }
}
