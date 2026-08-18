package io.github.jemmix.tdfa;

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

    @Test void defaultCompileUsesGeneration() {
        String prev = System.getProperty("tdfa.engine");
        try {
            System.clearProperty("tdfa.engine");
            Pattern p = Pattern.compile("abc");
            assertThat(p.getClass().getSimpleName()).as("generated shell expected")
                    .startsWith("Gen").endsWith("Pattern");
            assertThat(p.matcher("abc").matches()).isTrue();
            assertThat(p.matcher("abcd").matches()).isFalse();
        } finally {
            restore(prev);
        }
    }

    @Test void vmSwitchForcesSharedImplementation() {
        String prev = System.getProperty("tdfa.engine");
        try {
            System.setProperty("tdfa.engine", "VM");
            Pattern p = Pattern.compile("abc");
            assertThat(p).isInstanceOf(TDFAPattern.class);
            assertThat(p.getClass()).isEqualTo(TDFAPattern.class);
            assertThat(p.matcher("abc").matches()).isTrue();
        } finally {
            restore(prev);
        }
    }

    @Test void staticMatchesUsesDefault() {
        assertThat(Pattern.matches("abc", "abc")).isTrue();
        assertThat(Pattern.matches("abc", "abd")).isFalse();
    }

    @Test void flagsCompileUsesDefault() {
        Pattern p = Pattern.compile("ABC", Pattern.CASE_INSENSITIVE);
        assertThat(p.matcher("abc").matches()).isTrue();
    }

    private static void restore(String prev) {
        if (prev == null) System.clearProperty("tdfa.engine");
        else System.setProperty("tdfa.engine", prev);
    }
}
