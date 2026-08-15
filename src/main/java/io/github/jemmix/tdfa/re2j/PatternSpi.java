package io.github.jemmix.tdfa.re2j;

import io.github.jemmix.tdfa.Regex;

/**
 * Package-private engine SPI over {@link Pattern}: implemented by the shared
 * {@link VmPattern} and by generated per-pattern Patterns alike, so the shared
 * Matcher machinery can reach the engines without knowing the implementation.
 */
interface PatternSpi extends Pattern {

    /** The main (unanchored) engine. */
    Regex engine();

    /** Engine for {@code matches()}: pattern wrapped in {@code \A(?:...)\z}, compiled lazily. */
    Regex wholeEngine();
}
