package io.github.jemmix.tdfa.asm;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * {@link FrameClassWriter#getCommonSuperClass} semantics: total (never
 * throws on unresolvable types), correct for the identical-type merges
 * generated code performs, and ASM-walk-correct for loadable pairs.
 */
class FrameClassWriterTest {

    /** Expose the protected hook (same package, subclass access). */
    private static final class Exposed extends FrameClassWriter {
        Exposed() { super(0); }

        String common(String a, String b) { return getCommonSuperClass(a, b); }
    }

    private final Exposed w = new Exposed();

    @Test
    void identicalTypesResolveToThemselves() {
        // Covers every merge involving generated (child-loader) types.
        assertThat(w.common("io/github/jemmix/tdfa/gen/Engine42",
                "io/github/jemmix/tdfa/gen/Engine42"))
                .isEqualTo("io/github/jemmix/tdfa/gen/Engine42");
        assertThat(w.common("[I", "[I")).isEqualTo("[I");
    }

    @Test
    void objectOperandShortCircuits() {
        assertThat(w.common("java/lang/Object", "java/lang/String"))
                .isEqualTo("java/lang/Object");
        assertThat(w.common("io/github/jemmix/tdfa/gen/Shell7", "java/lang/Object"))
                .isEqualTo("java/lang/Object");
    }

    @Test
    void loadablePairsUseTheHierarchyWalk() {
        assertThat(w.common("java/lang/String", "java/lang/CharSequence"))
                .isEqualTo("java/lang/CharSequence");
        assertThat(w.common("java/lang/StringBuilder", "java/lang/String"))
                .isEqualTo("java/lang/Object");
        // Unrelated interfaces: ASM default answer is Object; we match it.
        assertThat(w.common("java/lang/Runnable", "java/lang/Comparable"))
                .isEqualTo("java/lang/Object");
    }

    @Test
    void unresolvablePairsDegradeToObjectInsteadOfThrowing() {
        // Default ClassWriter throws TypeNotPresentException here; emission
        // of any class referencing two such types would crash compile.
        assertThat(w.common("io/github/jemmix/tdfa/gen/Absent1",
                "io/github/jemmix/tdfa/gen/Absent2"))
                .isEqualTo("java/lang/Object");
        // Unresolvable vs loadable: same conservative answer.
        assertThat(w.common("io/github/jemmix/tdfa/gen/Absent1", "java/lang/String"))
                .isEqualTo("java/lang/Object");
    }

    @Test
    void nonIdenticalArraysResolveToObject() {
        assertThat(w.common("[I", "[Ljava/lang/String;")).isEqualTo("java/lang/Object");
        assertThat(w.common("[I", "java/lang/String")).isEqualTo("java/lang/Object");
    }
}
