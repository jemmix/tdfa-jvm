/**
 * The compilation front end: CompiledRegex (the evergreen find-only entry
 * over the interpreter), the wither-style options, the engine-selection hook
 * (RegexEngineFactory), and — since the two-facade split — the shared Pattern
 * machinery the thin facades compile through (PatternCompiler with its
 * flag-bit vocabulary, TDFAPattern, PatternMatcher, SerialProxy,
 * FindOnlyWholeEngine, the EngineBackend/ShellSpec emission SPI). Top layer —
 * the only tier that wires the whole pipeline; the machinery classes are
 * public-but-internal (documented, not shipped API — the facades' Pattern
 * interfaces are the API).
 */
package io.github.jemmix.tdfa.core.compile;
