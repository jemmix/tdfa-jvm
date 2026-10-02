/**
 * The matching API and SPI: RegexEngine (compile once, match many), the
whole-match seam, the matcher/replacement surface, and the per-match
protocol types (MatchResult, MatchScratch). Sits below the artifact tier
(implementors read core.dfa); the ASM tier generates classes implementing
this SPI.
 */
package io.github.jemmix.tdfa.core.engine;
