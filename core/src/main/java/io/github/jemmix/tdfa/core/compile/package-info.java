/**
 * The compilation front end: CompiledRegex (the evergreen find-only entry
over the interpreter), the wither-style options, the engine-selection hook
(RegexEngineFactory), and — since the two-surface split — the shared Pattern
machinery the thin surfaces compile through (PatternCompiler, TDFAPattern,
PatternMatcher, SerialProxy, FindOnlyWholeEngine, the PatternSurface
registration and the EngineBackend/ShellSpec emission SPI). Top layer — the
only tier that wires the whole pipeline; the machinery classes are
public-but-internal (documented, not shipped API — the surfaces' Pattern
interfaces are the API).
 */
package io.github.jemmix.tdfa.core.compile;
