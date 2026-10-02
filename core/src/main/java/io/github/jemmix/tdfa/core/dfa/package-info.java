/**
 * The tagged-DFA data model and its reference interpreter: the compiled
artifact (Tdfa), the shared builder vocabulary (Range, OpSeq,
DfaStateBuilder), and the table interpreter (TdfaRunner) with its search
DFA, walk index, runner tables and match holder. The artifact's fields
stay package-private precisely because the interpreter lives beside the
artifact - zero-copy reads on the hot paths. Sits above the engine SPI it
implements; construction lives in core.determinize.
 */
package io.github.jemmix.tdfa.core.dfa;
