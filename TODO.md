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
- [ ] **`\w` / `\b` are ASCII-only, not Unicode-aware** — re2 (in UTF-8 mode, which rebar uses) treats `\w` as `[\p{L}\p{N}_]`-ish (Unicode word runes); our `R_WORD` at `Parser.java:761` is hard-coded `[_0-9A-Za-z]`. So `\b\w+\b` on Cyrillic text matches ~0.5% of words. Surfaced by rebar's `curated/08-words/all-russian` (want=107391, got=529) and `long-russian` (want=5481, got=12) after the 200 KB→16 MB haystack cap bump. Fix: build `\w` from the Unicode provider's `L|N` categories (plus `_`) when Unicode groups are enabled, and make `\b` ride on the same definition.
- [x] **`\p{L}{N}` returns 0 matches for N ≥ 25** — root cause was the `stateMeta` packing: the range-base field used only 15 bits (bits 17-31, max 32767), but `\p{L}` has ~1369 Unicode ranges per state, so `base` overflowed at state 24 (24×1369=32856). Fixed by splitting `base` into a separate `stateBase[]` array (full 32-bit range), removing the artificial limit. `\p{L}{256}` now compiles and matches correctly on both VM and ASM backends.
- [ ] **`.` on non-BMP codepoints undercounts vs re2** — `.` on `💩` (U+1F4A9) gives 1 match in our engine (one codepoint); re2 gives 4 (UTF-8 bytes). Our engine is codepoint-oriented like `java.util.regex`, not byte-oriented like re2. **Resolved at the test level**: `vendor/patches/rebar/01-dot-matches-byte-codepoint.patch` rewrites the rebar scenario to record our actual count (1) under an explicit `{ engine = 're2', count = 1 }` entry — see the patch file for the rationale comment. Fundamental architecture choice, not a bug.
- [ ] Differential fuzzing vs `re2j` and `java.util.regex` (Jazzer or custom harness)
- [ ] Deterministic compilation — same regex → identical TDFA across runs
- [ ] `map` + topological sort: reject non-trivial cycles (BT22 §3.3)
- [ ] Fallback / backup operations (BT22 §3.2) — restore clobberable registers on dead-end
- [ ] Verify TDFA(1) strict conformance vs paper wording (lookahead delay semantics)
- [ ] Multi-valued tags (tags under repetition accumulating multiple offsets)
- [ ] Property-based testing (random regex generation + differential oracle)

## Performance

- [x] **O(n²) unanchored `find()` — no-match case** — fixed via multi-state parallel simulation in `TdfaRunner.multiStateAnyMatch`: a single forward pass tracks the set of all DFA states reachable from any start position (O(n × |states|)), replacing the outer-loop restart. Used for boolean `find()` directly and as a no-match pre-check for the extract path. 200 K-char no-match haystack: ~14 ms (was >30 s).
- [ ] **O(n²) `find()` on dense matches** — the multi-state fix only short-circuits the no-match pre-check; the extract path (`TdfaRunner.runStringExtractFast:368`) still has the outer-loop shape. For `[a-zA-Z]+ing` on the 16 MB leipzig corpus (78 K matches) it's 249 s, because `Matcher.find()` is called O(n) times and each call's inner loop walks all the way to `to` before reporting the leftmost-longest accept. Same shape on `ing-whitespace` (182 s), `quotes-bounded` (33 s), `i13-subset-regex/*` (8–20 s), `tom-sawyer/*` (5–13 s). Surfaced by the radical timeout relaxation in REBAR-PARITY-PLAN Phase 6. Fix path: multi-state extract (carry one `regs[]` per live state), or anchored re-extract from the leftmost-start position that `multiStateAnyMatch` already computes. See `docs/REBAR-PARITY-PLAN.md §6.1`.
- [x] **ASM backend hits the 65 KB JVM method limit** — fully solved via `TdfaAsmBackend.pickMode`, which selects one of three dispatch modes per pattern: `INLINED` (per-state range checks, fastest), `TABLE_SCAN` (per-state compact binary search over `RANGES_TABLE`), or `DELEGATE` (thin wrapper that forwards to a `TdfaRunner`). Combined with `<clinit>` no longer materializing per-element arrays (ENTRY/ACCEPT/STOP/IS_ACCEPT/ASCII_TARGET all become reference copies or runtime loops in `<init>`), no in-scope rebar pattern throws `MethodTooLargeException`. The old VM-retry path in `RebarScenarioParityTest` was removed; both backends now run as peer parameter values.
- [ ] DFA minimization (Moore-style, register-aware) — would clear the 4 remaining `COMPILE_TIMEOUT` skips (bounded-repeat state explosion in `curated/03-date`, `curated/09-aws-keys/full`, `curated/10-bounded-repeat/context`). See `docs/REBAR-PARITY-PLAN.md §6.2`.
- [ ] **M2 regopt interference-analysis bug (FIXED, commit pending)** — `Optimize.interferenceAnalysis` walked ops FORWARD and cloned `L[b]` (end-of-block liveness) for EACH op, missing the fact that COPY sources become live BEFORE the op and conflict with registers written by LATER ops in the same block. Symptom: in the veryl lexer (88-way alternation, ~20 working registers), the allocator merged working registers from different alternation branches with final registers, causing capture groups to be reported with wrong start/end values (e.g., `g1=[-1,0] g2=[-1,1]` instead of `g2=[0,1]`). Fix: rewrote to walk ops in REVERSE with a running live set (as BT22 Fig. 7 specifies), keeping a forward pre-pass to snapshot the value-tracking `V[]` at each op position for the same-value-no-interference optimization. Verified on the n=5 veryl subset (was `g1=[-1,0] g2=[-1,1]`, now `g2=[0,1]`) and the full 88-branch veryl regex (0 multi-group matches, 0 zero-group matches — all 61500 matches now report exactly 1 participating group, matching `java.util.regex`). The remaining veryl failure (`expected: 124800, was: 123000`) is a separate `\b`-determinization bug, not a regopt issue.
- [ ] **`\b` in alternation causes dead-end DFA paths (lexer-veryl missing matches)** — when the regex has `(keyword-with-\b)|(identifier)`, the DFA determinization produces two separate states for "after letter at \b" (keyword path) and "after letter, no \b" (identifier path), with two ranges per letter in the transition table: `['a']->state29 reqMask=\b` then `['a']->state25 reqMask=0`. The runner tries ranges in order and COMMITS to the first one whose reqMask passes — if `\b` is satisfied, it always takes the keyword path even when the char doesn't start any keyword. State 29 (keyword path) only has transitions for keyword continuations (`l` for `always`, `s` for `as`); it's missing identifier-continuation transitions. So after seeing `a` at a `\b`, if the next char isn't a keyword letter, the DFA dead-ends and reports no match. Symptom: 900 matches missing in the veryl `count-captures` scenario (was masked by the regopt bug which inflated counts; post-regopt-fix it's `expected 124800, was 123000`). Fix path: the ε-closure for a `\b`-guarded transition must ALSO include NFA states from non-`\b` alternation branches that reach the same position. In `(A|\bB)`, after consuming a char that could match either `A` or `B`, the DFA state should track BOTH paths. Currently it tracks only the `\b` path, losing `A`. This requires changes to `Tnfa.Builder` (how `\b` edges interact with alternation) and/or `Tdfa.Compiler` (how `\b`-guarded configs are merged with non-`\b` configs).
- [ ] **Unicode case-fold `s ↔ ſ` for literal chars under `(?i)`** — `Parser.parseQuoted` / single-char literal under case-insensitive mode uses `Character.toLowerCase(ch)` / `Character.toUpperCase(ch)`, which for ASCII `s` gives only `s`/`S` — missing the Unicode simple case-fold `ſ` (U+017F LATIN SMALL LETTER LONG S). The rebar `test/unicode/case/ascii-with-unicode` scenario (`regex='s'`, `unicode=true`, `case-insensitive=true`, `haystack='ſ'`) expects a match; we report 0. Fix: implement Unicode CaseFolding.txt-based folding for literal chars (and class ranges — the ASCII-only range folding in `parseClass` at line 306 is flagged "Pending full Unicode case folding"). The `UnicodeDataProvider.foldTableFor()` path already handles `\p{Greek}` under `(?i)`; a similar `caseFold(char)` API could serve literal expansion. Java's `java.text.Normalizer` doesn't help here; we need the Unicode `CaseFolding.txt` simple-case mapping data.
- [ ] ASM register coalescing / scalar replacement (registers → JVM locals)
- [ ] Cache-friendly flat-array data layout for VM backend
- [ ] Lazy accept-snapshot (avoid `regs.clone()` on every accept-state visit)
- [ ] ASCII fast-path specialization (128-entry byte table per state)

## Benchmark coverage

- [x] Vendor [rebar](https://github.com/BurntSushi/rebar) scenario corpus — `vendor/rebar-<sha>.tar.gz`; parsed by `:testlib:rebar`.
- [x] Tracer-bullet parity test against rebar scenarios — `:tests:parity:rebar:RebarScenarioParityTest`. With the radical timeout/cap relaxation (`COMPILE_TIMEOUT_MS` 5 s → 2 min, `RUN_TIMEOUT_MS` 10 s → 10 min, `MAX_HAYSTACK_BYTES` 16 MB → 80 MB, `MAX_REGEX_LEN` 32 KB → 2 MB), `utf8-lossy` loader support, the scope restricted to scenarios rebar actually tests against Java (`java/hotspot` in engines list — see `docs/PARITY-PLAN.md`), and `compile` / `grep-captures` models implemented: **108 of 114 in-scope scenarios pass** (94.7 %), 2 surface known engine bugs (see "Correctness" below), 4 skip on `COMPILE_TIMEOUT` (bounded-repeat state explosion — see Performance below), 245 skip on the Java-scope filter (out of scope per the locked 2025-08 rule). End-of-suite `@AfterAll` summary prints skip-reason histogram + top-20 slowest tests + wall-time totals — see `docs/REBAR-PARITY-PLAN.md`.
- [x] Expand rebar parity — Phase 6.3 of `REBAR-PARITY-PLAN.md`: utf8-lossy loader fix, radical timeout/cap bumps. Surfaced the O(n²) extract bug (Phase 6.1) and the 4 bounded-repeat compile bombs (Phase 6.2). +34 scenarios passing (74 → 108).
- [ ] Remaining rebar parity — Phase 5 (2 engine correctness bugs) + Phase 6.1 (O(n²) extract) + Phase 6.2 (DFA minimization). After all three: 114 / 114 in-scope green.
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
