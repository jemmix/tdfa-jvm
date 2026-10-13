package io.github.jemmix.tdfa.re2j;

import io.github.jemmix.tdfa.core.compile.TDFAPattern;

/**
 * The re2j surface's not-generated {@link Pattern} carrier: wraps the bare
 * machinery instance the no-codegen paths produce ({@code -Dtdfa.engine=VM})
 * so every pattern this module hands out implements its {@code Pattern}
 * interface. Generated shells implement it by emission instead; the state
 * and behavior all live in the shared {@link TDFAPattern} base — this
 * class only carries the type.
 */
final class VmPattern extends TDFAPattern implements Pattern {

    VmPattern(TDFAPattern src) {
        super(src.pattern(), src.flags(), src.programSize(), src.engine(), src.wholeEngine(), src.unicodeProvider(),
            src.surface());
    }
}
