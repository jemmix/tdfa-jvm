# tdfa-jvm — Roadmap

Everything between here and "done." When this list is empty, the library is
finished. See the [vision](README.md#vision).

## Feature parity

- [x] Clear all pending parity tests — 0 remaining (was 41; all cleared: POSIX classes, escape rejection, byte[] overloads, split, DISABLE_UNICODE_GROUPS, matches() anchored groups, programSize, Serializable, `\A`/`\z` multiline invariance, re2j-exact Unicode provider). See `docs/PARITY-PLAN.md`.
- [ ] Add more parity tests — expand coverage to edge cases not yet exercised (backreference semantics, large repetition counts, nested quantifiers, Unicode line boundaries, canonical equivalents, etc.). Gate known-failing or not-yet-implemented cases with `@EnabledIfSystemProperty(named = "tdfa.pending", matches = "true")` and a `// PENDING:` comment; run the full set with `./gradlew test -Dtdfa.pending=true`. Clear the gate as each feature lands.
- [ ] Multiline mode `(?m)` — `^`/`$` at line boundaries
- [ ] Full POSIX leftmost-longest — activate BT22 §7 `closure_gtop` winner selection
- [x] Unicode case folding for literal chars — CaseFoldTable handles all BMP simple case folds (28 groups with >2 members including s↔ſ, k↔K, Ω↔ω). Class-range folding still ASCII-only.
- [ ] `\b` / `\B` Unicode word boundary semantics for supplementary codepoints

## Correctness

- [x] **`(a*)(a*)` crashes at compile time** — FIXED. `ArrayIndexOutOfBoundsException` in `Optimize.findFinalRegBase` on both backends and both disambiguation modes; affected `(a+)(a*)`, `(a*)(a?)`, `(.*) (.*)`, `(a*)(a*)(a*)`. Root cause: register allocation coalesced FINAL registers — with each other via the same-value rule (two SET-pos finals), or with working registers via COPY coalescing. Finals must share nothing: the `MatchResult` readout protocol requires tag *t* at a dedicated `regs[finalRegBase + t - 1]` slot. When finals coalesced, the block either scattered (silently wrong captures) or `regCount` dropped below `tagCount` (negative base → crash). Fix: `Optimize.registerAllocation` runs coalescing over working registers only and assigns finals dedicated consecutive top slots in tag order (BT22's dedicated `R_f` layout). Tests: `CaptureGroupAllocationTest` (ungated).
- [ ] **`\b` mishandles supplementary codepoints** — `TdfaRunner.isWordChar(char)` checks individual UTF-16 code units, so supplementary letters (e.g. U+1D504 MATHEMATICAL FRAKTUR A) are not word chars even under `(?u)`. `(?u).\b.` on `a𝔄b` wrongly matches `[0,3]` (no boundary should fire between two word chars); `(?u)\b\w` on `𝔄𝔅` wrongly returns null (boundary should hold at start-of-text). java.util.regex returns null / `[0,2]`. Fix: decode surrogate pairs in `positionFlags`/`positionFlagsCS` and search `wordRanges` (which already include supplementary codepoints) by codepoint. Tests: `WordBoundaryTest` (gated `tdfa.pending`).
- [x] **`(?iu)` char-class ranges don't include Unicode fold equivalents** — FIXED. `Parser.parseClass` now expands every member codepoint's `CaseFoldTable.foldRanges` when `unicodeShorthand && caseInsensitive` (`(?iu)[r-t]` matches `ſ`); ASCII-only fold retained without `(?u)` (re2j semantics).
- [x] **`(?iu)` negated classes don't exclude Unicode fold equivalents** — FIXED by the same range-fold expansion (fold members join the positive set before `CharClass` negation, so `(?iu)[^s]` rejects `ſ`).
- [x] **`JdkUnicodeDataProvider.foldTableFor` incomplete** — FIXED. `buildFoldTable` only closed the `toUpperCase` direction (worked for `\p{Lu}`, null for `\p{Ll}`). Now checks all `CaseFoldTable` fold-group members, so `(?i)\p{Ll}` matches `A` and multi-member groups (s/S/ſ) close in both directions.
- [ ] VM backend: regex `(?:(?:^)|.)?` matches `[0,1]` instead of `[0,0]` at start of input — caught by Google's ExecTest running against both backends (`:tests:parity:re2j-suite:testOnVm`).
- [x] **PERL disambiguation with `\b` in alternation** — was described as "picks wrong alternative" but root cause was a determinization flaw: `\b`-guarded transitions didn't include identifier continuations from non-`\b` branches, causing dead-end DFA paths. Fixed in commit `d133d20` by including subset-mask configs in the step input during mask-group processing.
- [x] **Unicode case folding for single-char literals** — Fixed in commit `74ab652`. Added `CaseFoldTable` with a reverse fold table; parser uses it when `unicodeShorthand && caseInsensitive`. `(?i)s` now matches `ſ` (U+017F).
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
- [x] **M2 regopt interference-analysis bug** — Fixed in commit `6b335e2`. `Optimize.interferenceAnalysis` walked ops FORWARD and cloned `L[b]` (end-of-block liveness) for EACH op, missing the fact that COPY sources become live BEFORE the op and conflict with registers written by LATER ops in the same block. Rewrote to walk ops in REVERSE with a running live set (BT22 Fig. 7), keeping a forward pre-pass for the value-tracking `V[]` snapshots. All 61500 veryl matches now report exactly 1 participating group, matching `java.util.regex`.
- [x] **`\b` in alternation causes dead-end DFA paths** — Fixed in commit `d133d20`. Modified `Tdfa.Compiler.compile()` to include subset-mask group configs in the step input, ensuring `\b`-guarded transitions include identifier continuations. Group's own configs added first to preserve priority in ε-closure dedup. The veryl scenario now reports the expected 124800 captures.
- [x] **Unicode case-fold `s ↔ ſ` for literal chars under `(?i)`** — Fixed in commit `74ab652`. Added `CaseFoldTable` (unicode/CaseFoldTable.java) with a reverse fold table mapping `toUpperCase(toLowerCase(cp))` to all BMP codepoints sharing it. Parser uses it when `unicodeShorthand && caseInsensitive`.
- [ ] ASM register coalescing / scalar replacement (registers → JVM locals)
- [ ] Cache-friendly flat-array data layout for VM backend
- [ ] Lazy accept-snapshot (avoid `regs.clone()` on every accept-state visit)
- [ ] ASCII fast-path specialization (128-entry byte table per state)

## Benchmark coverage

- [x] Vendor [rebar](https://github.com/BurntSushi/rebar) scenario corpus — `vendor/rebar-<sha>.tar.gz`; parsed by `:testlib:rebar`.
- [x] Tracer-bullet parity test against rebar scenarios — `:tests:parity:rebar:RebarScenarioParityTest`. With the radical timeout/cap relaxation (`COMPILE_TIMEOUT_MS` 5 s → 2 min, `RUN_TIMEOUT_MS` 10 s → 10 min, `MAX_HAYSTACK_BYTES` 16 MB → 80 MB, `MAX_REGEX_LEN` 32 KB → 2 MB), `utf8-lossy` loader support, the scope restricted to scenarios rebar actually tests against Java (`java/hotspot` in engines list — see `docs/PARITY-PLAN.md`), and `compile` / `grep-captures` models implemented: **108 of 114 in-scope scenarios pass** (94.7 %), 2 surface known engine bugs (see "Correctness" below), 4 skip on `COMPILE_TIMEOUT` (bounded-repeat state explosion — see Performance below), 245 skip on the Java-scope filter (out of scope per the locked 2025-08 rule). End-of-suite `@AfterAll` summary prints skip-reason histogram + top-20 slowest tests + wall-time totals — see `docs/REBAR-PARITY-PLAN.md`.
- [x] Expand rebar parity — Phase 6.3 of `REBAR-PARITY-PLAN.md`: utf8-lossy loader fix, radical timeout/cap bumps. Surfaced the O(n²) extract bug (Phase 6.1) and the 4 bounded-repeat compile bombs (Phase 6.2). +34 scenarios passing (74 → 108).
- [x] Remaining rebar parity — **All in-scope scenarios now pass** (220/220 parameterized over ASM+VM). The 3 engine correctness bugs (§A regopt interference, §B `\b` dead-end, §C Unicode case-fold) are fixed. Remaining skips: 490 scope (no `java/hotspot` engine), 8 COMPILE_TIMEOUT (DFA state explosion — needs minimization, see Performance below).
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
