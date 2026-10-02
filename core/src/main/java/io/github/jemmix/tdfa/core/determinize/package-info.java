/**
 * Artifact construction: subset construction from the TNFA (TdfaCompiler),
final-phi variant solving, register optimization orchestration,
flat-array materialization and register-aware minimization
(TdfaMaterializer), and the pipeline seam (Determinizer) that runs the
halves under one meter. Sits above tnfa, regopt and the dfa model it
produces.
 */
package io.github.jemmix.tdfa.core.determinize;
