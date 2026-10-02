/**
 * Register optimization (BT22 6.3): CFG construction over the
determinizer's builder states and the optimization passes - liveness,
DCE, copy propagation, allocation, topological normalization. Sits above
the dfa vocabulary and the budget leaf; consumed by core.determinize.
 */
package io.github.jemmix.tdfa.core.regopt;
