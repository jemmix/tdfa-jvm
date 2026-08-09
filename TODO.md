# tdfa-jvm — Roadmap

Everything between here and "done." When this list is empty, the library is
finished. See the [vision](README.md#vision).

## Feature parity

- [x] Clear all pending parity tests — 0 remaining (was 41; all cleared: POSIX classes, escape rejection, byte[] overloads, split, DISABLE_UNICODE_GROUPS, matches() anchored groups, programSize, Serializable, `\A`/`\z` multiline invariance, re2j-exact Unicode provider). See `docs/PARITY-PLAN.md`.
- [ ] Add more parity tests — expand coverage to edge cases not yet exercised (backreference semantics, large repetition counts, nested quantifiers, Unicode line boundaries, canonical equivalents, etc.). Gate known-failing or not-yet-implemented cases with `@EnabledIfSystemProperty(named = "tdfa.pending", matches = "true")` and a `// PENDING:` comment; run the full set with `./gradlew test -Dtdfa.pending=true`. Clear the gate as each feature lands.
- [ ] Multiline mode `(?m)` — `^`/`$` at line boundaries
- [ ] Full POSIX leftmost-longest — activate BT22 §7 `closure_gtop` winner selection
- [ ] Unicode case folding completeness (currently partial)
- [ ] `\b` / `\B` Unicode word boundary semantics (currently ASCII)

## Correctness

- [ ] VM backend: regex `(?:(?:^)|.)?` matches `[0,1]` instead of `[0,0]` at start of input — caught by Google's ExecTest running against both backends (`:tests:parity:re2j-suite:testOnVm`).
- [ ] 14 rebar scenario mismatches — see `:tests:parity:rebar:test` report. Includes: catastrophic-backtracking test handling, literal-uppercase-in-pattern detection (`fooYbarZquux`), `[A-Z]awyer|[A-Z]inn` alternation captures.
- [ ] Differential fuzzing vs `re2j` and `java.util.regex` (Jazzer or custom harness)
- [ ] Deterministic compilation — same regex → identical TDFA across runs
- [ ] `map` + topological sort: reject non-trivial cycles (BT22 §3.3)
- [ ] Fallback / backup operations (BT22 §3.2) — restore clobberable registers on dead-end
- [ ] Verify TDFA(1) strict conformance vs paper wording (lookahead delay semantics)
- [ ] Multi-valued tags (tags under repetition accumulating multiple offsets)
- [ ] Property-based testing (random regex generation + differential oracle)

## Performance

- [ ] DFA minimization (Moore-style, register-aware)
- [ ] ASM register coalescing / scalar replacement (registers → JVM locals)
- [ ] Cache-friendly flat-array data layout for VM backend
- [ ] Unanchored `find()` — scan DFA or `.*?` desugaring instead of O(n × states) restart
- [ ] ASM method-size splitting for large automata (>65 KB bytecode limit)
- [ ] Lazy accept-snapshot (avoid `regs.clone()` on every accept-state visit)
- [ ] ASCII fast-path specialization (128-entry byte table per state)

## Benchmark coverage

- [x] Vendor [rebar](https://github.com/BurntSushi/rebar) scenario corpus — `vendor/rebar-<sha>.tar.gz`; parsed by `:testlib:rebar`.
- [x] Tracer-bullet parity test against rebar scenarios — `:tests:parity:rebar:RebarScenarioParityTest`. 30 scenarios run; 16 pass, 14 surface real bugs (quadratic-backtracking handling, `[A-Z]` literal detection, alternation capture).
- [ ] Expand rebar parity — bump MAX_RAN cap, fix surfaced bugs, add ASM backend, support more rebar models (`grep`, `count-captures`).
- [ ] Hyperscan corpus / Snort rule set
- [ ] Long-input scan across diverse patterns (not just `\w+\d+\w+`)
- [ ] CI performance regression tracking (JMH + comparison thresholds)

## Engineering — "SQLite levels"

- [ ] SpotBugs / Error Prone / PMD — zero warnings
- [ ] Checkstyle / Spotless — enforced code style
- [ ] JaCoCo coverage targets (line + branch)
- [ ] JavaDoc for all public API surface
- [ ] API stability guarantees (signatures locked at 1.0)
- [ ] Multi-JDK CI matrix (11, 17, 21, 25)
- [ ] GraalVM native-image compatibility
- [ ] Android API-level compatibility check
- [ ] JPMS module info (`module-info.java`)
- [ ] Reproducible builds (deterministic jar output)
- [ ] Thread safety audit (`Matcher` reuse, `Pattern` sharing)
- [ ] Memory leak testing (generated class GC under load)
- [ ] Security review (untrusted regex DoS: compile-time blowup, state explosion)

## Wishlist (maybe, someday, if motivated)

- [ ] `condy` / `invokedynamic` for lazy per-regex specialization
- [ ] Tiered compilation hints (`@Contended`, `@Stable`)
- [ ] SIMD-accelerated `find()` for fixed-string prefixes (`String.indexOf` vectorization)
- [ ] Ahead-of-time class persistence (compile regex to `.class` on disk, load at startup)
- [ ] POSIX longest-leftmost capture groups (not just match boundaries)
- [ ] Streaming input (match against `InputStream` / `ByteBuffer` without materializing)
