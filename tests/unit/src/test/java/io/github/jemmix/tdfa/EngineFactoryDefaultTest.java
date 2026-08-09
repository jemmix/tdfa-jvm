package io.github.jemmix.tdfa;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that {@code Pattern.compile()} without an explicit {@link EngineFactory}
 * uses the {@link EngineFactory#DEFAULT} singleton (resolved once at class init
 * from the {@code tdfa.engine} system property).
 *
 * <p>This is intentionally outside the parity suite — it tests the default-engine
 * wiring, not parity with re2j.
 */
class EngineFactoryDefaultTest {

    @Test void defaultFactoryIsResolved() {
        assertThat(EngineFactory.DEFAULT).isNotNull();
    }

    @Test void compileWithoutFactoryUsesDefault() {
        io.github.jemmix.tdfa.re2j.Pattern p =
                io.github.jemmix.tdfa.re2j.Pattern.compile("abc");
        assertThat(p.matcher("abc").matches()).isTrue();
        assertThat(p.matcher("abcd").matches()).isFalse();
    }

    @Test void compileWithFlagsWithoutFactoryUsesDefault() {
        io.github.jemmix.tdfa.re2j.Pattern p =
                io.github.jemmix.tdfa.re2j.Pattern.compile("ABC",
                        io.github.jemmix.tdfa.re2j.Pattern.CASE_INSENSITIVE);
        assertThat(p.matcher("abc").matches()).isTrue();
    }

    @Test void staticMatchesUsesDefault() {
        assertThat(io.github.jemmix.tdfa.re2j.Pattern.matches("abc", "abc")).isTrue();
        assertThat(io.github.jemmix.tdfa.re2j.Pattern.matches("abc", "abd")).isFalse();
    }

    @Test void defaultFactoryMatchesSystemProperty() {
        String prop = System.getProperty("tdfa.engine");
        if ("VM".equalsIgnoreCase(prop)) {
            assertThat(EngineFactory.DEFAULT).isSameAs(EngineFactory.VM);
        } else {
            assertThat(EngineFactory.DEFAULT).isSameAs(EngineFactory.ASM);
        }
    }
}
