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
- [ ] **PERL disambiguation picks wrong alternative** when the first alternation is anchored with `\b` and a later alternation matches the same prefix via a character class. Repro: `(\bvar\b)|([a-zA-Z_][0-9a-zA-Z_]*)` on `"var"` returns group 2 (identifier), should be group 1 (`\bvar\b`). Affects `curated/05-lexer-veryl/single` in `:tests:parity:rebar:test` (`[32]`) — the missed alternations sum to ~300 captures / ~1800 spans. Bug is in TDFA compilation (both backends repro), not yet localised to a specific phase. (Previously also affected 6 `wild/parol-veryl/*` scenarios; those are now out of scope — rebar doesn't include `java/hotspot` in their engines lists.)
- [ ] **Unicode case folding for single-char literals** — `(?i)s` doesn't match `ſ` (U+017F, Latin small letter long s). The parser at `Parser.java:160-164` and `:537-541` only folds via `Character.toLowerCase` / `toUpperCase`, which doesn't see non-ASCII folds of ASCII letters (or vice versa). re2j/re2 do Unicode simple case folding here. Surfaced by rebar's `test/unicode/case/ascii-with-unicode`.
- [ ] **`.` on non-BMP codepoints undercounts vs re2** — `.` on `💩` (U+1F4A9) gives 1 match in our engine (one codepoint); re2 gives 4 (UTF-8 bytes). Our engine is codepoint-oriented like `java.util.regex`, not byte-oriented like re2. **Resolved at the test level**: `vendor/patches/rebar/01-dot-matches-byte-codepoint.patch` rewrites the rebar scenario to record our actual count (1) under an explicit `{ engine = 're2', count = 1 }` entry — see the patch file for the rationale comment. Fundamental architecture choice, not a bug.
- [ ] Differential fuzzing vs `re2j` and `java.util.regex` (Jazzer or custom harness)
- [ ] Deterministic compilation — same regex → identical TDFA across runs
- [ ] `map` + topological sort: reject non-trivial cycles (BT22 §3.3)
- [ ] Fallback / backup operations (BT22 §3.2) — restore clobberable registers on dead-end
- [ ] Verify TDFA(1) strict conformance vs paper wording (lookahead delay semantics)
- [ ] Multi-valued tags (tags under repetition accumulating multiple offsets)
- [ ] Property-based testing (random regex generation + differential oracle)

## Performance

- [ ] **O(n²) unanchored `find()`** — `TdfaRunner.runStringFindFast` (line 227) and `runStringExtractFast` (line 252) restart the DFA from every position; on a non-matching haystack each restart walks to end-of-input. ASM backend has the same shape (`emitRunCore`, line 510). Repro: rebar's `opt/nfa-sparse/small-repeated-class-{bytes,unicode}` (~92 K-char haystack, regex never matches) takes >30 s; should be < 50 ms with a single-pass multi-state search (re2/re2j do this). Fix is the standard NFA-style parallel simulation — track the set of live states at each position so a single pass suffices.
- [ ] **ASM backend hits the 65 KB JVM method limit** on moderately large DFAs (200+ states) — `TdfaAsmBackend.java` generates one giant `runExtract` / `<clinit>`. Repro: rebar's i787-keywords alternation (209 states), parol-veryl lexer (88 branches), `[0-9A-Za-z_]{256}`. Affects ~11 rebar scenarios; the rebar test now falls back to the VM backend automatically (see `RebarScenarioParityTest.java`), but the underlying fix is method-splitting. Track which scenarios use the fallback via the `ASM-FAIL` log line.
- [ ] DFA minimization (Moore-style, register-aware)
- [ ] ASM register coalescing / scalar replacement (registers → JVM locals)
- [ ] Cache-friendly flat-array data layout for VM backend
- [ ] ASM method-size splitting for large automata (>65 KB bytecode limit)
- [ ] Lazy accept-snapshot (avoid `regs.clone()` on every accept-state visit)
- [ ] ASCII fast-path specialization (128-entry byte table per state)

## Benchmark coverage

- [x] Vendor [rebar](https://github.com/BurntSushi/rebar) scenario corpus — `vendor/rebar-<sha>.tar.gz`; parsed by `:testlib:rebar`.
- [x] Tracer-bullet parity test against rebar scenarios — `:tests:parity:rebar:RebarScenarioParityTest`. With the loader now spec-compliant (per-line alternate/pattern, prepend/append, literal, array→alternation, `re2` count selection) and the engine running in its real default mode (ASM + PERL), and the **scope now restricted to scenarios rebar actually tests against Java** (`java/hotspot` in engines list — see `docs/PARITY-PLAN.md`), **43 of 45 runnable** scenarios pass; 2 surface known engine bugs (see "Correctness" below); 314 skip on scope/size/model filters (the `dot-matches-byte` architectural divergence is patched in the vendored corpus — see `vendor/patches/rebar/`).
- [ ] Expand rebar parity — bump `MAX_HAYSTACK_BYTES` / `MAX_REGEX_LEN` caps once the O(n²) `find()` is fixed (would unlock ~50 currently-skipped Java-eligible scenarios); add `grep-captures` and `compile` models; fix surfaced bugs.
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
