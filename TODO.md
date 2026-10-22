# tdfa-jvm — TODO

Open work only; when empty, the library is finished. See the
[vision](README.md#vision). Completed items and round logs live in
git history (TODO.md before 2026-09-30).

## Correctness

- [ ] JUR-compat evidence re-base tail: both-lane overnight soak + README numbers ([design](docs/jur-compat-default.md), [work breakdown](docs/jur-compat-wbs.md))
- [ ] Fix quantified-group alternation order vs jur (`(\A|.?o*.){3}` seeds) — root-caused: GroupCurly commits the first `cmin` iterations; `matches()` diverges on `{n≥2}` shapes ([campaign follow-ups](docs/fuzz-campaigns-2026-10-16.md))
- [ ] Retire known-divergence classifiers once engines agree everywhere (last family: the GroupCurly item)
- [ ] Un-skip the family-6 TAIL slice: the surfaced zero-width write rides the transition φ as a position-carrying ε-write (`(a*)*b` on `"aab"` → g1 `2..2`, the loop's REST position; composes with line-merged DFA states) — retires `SKIP_GROUP_PARTICIPATION_TAIL` ([design](docs/jur-compat-default.md))
- [ ] Un-skip the surrogate-unit family: a pair-aware JUR reading — classes never match one unit of a well-formed pair, `\b`/`\B` wordness reads the lone unit — java's UTF-16 discipline vs the lane's designed unit semantics; retires `SKIP_SURROGATE_UNIT_SEMANTICS` ([campaign follow-ups](docs/fuzz-campaigns-2026-10-16.md))
- [x] Fuzz harness: flip jur-lane match-time engine exceptions hard (`JURLANE_EXCEPTION` — the compile catch-all's twin from the re-base; the engine is non-recursive, so `StackOverflowError` too) — **landed**: hard kind with detail+first-frame records, `JUR_EXCEPTION` (never actually soft) left the allowlist beside it
- [ ] Fuzz harness: translate point-forward `(?U)` to the oracle's scoped `(?U:…)` wrap of the remainder — retires `SKIP_SCOPED_U_UNTRANSLATABLE`
- [ ] Overnight soak with cross-rung on, both lanes (`-Pfuzz.lane` pins; mix by default); refresh README fuzz numbers; target: zero mismatches, zero hangs — first pass found [six findings](docs/fuzz-campaigns-2026-10-16.md); 1/3/4 closed (3 root-fixed: ladder bound, unit trigger, exact simulation, budget retired)
- [ ] Fuzz families for fact-gated rungs: literal, prefix, cand-scan, anchored-fast
- [ ] Oracle-check stretched inputs; compare ASM on them too
- [ ] Fuzz non-String CharSequence inputs against the reference sim
- [ ] Fuzz budget-exhaustion paths with shrunk budget knobs
- [ ] Fuzz the multi-valued lane ([plan](docs/fuzz-multi-valued-tags.md))
- [ ] Property shrinking for minimal fuzz repros
- [ ] Verify TDFA(1) conformance vs paper wording (lookahead delay)
- [ ] Upstream re2j PRs #208/#212/#213: maintainer review

## Performance

- [ ] Prefix needle v2: fold-aware, \b-gated chains

## Benchmarks

- [ ] Hyperscan corpus / Snort rules
- [ ] Long-input scans across diverse patterns

## Engineering

- [ ] Namespace move jemmix → tagmaton (gates publish)
- [ ] First Maven publish
- [ ] japicmp baselines ×4
- [ ] License headers
- [ ] TdfaRunner trace → per-engine (test-only)
- [ ] JPMS module-info
- [ ] JavaDoc all public API
- [ ] Re-evaluate `Semantics` scope: parity-axes-only carrier vs absorbing mode knobs (`LONGEST_MATCH`, `MULTI_VALUED_TAGS`); decide before the API lock (breaking after)
- [ ] Lock 1.0 API stability guarantees
- [ ] JaCoCo coverage targets
- [ ] Reproducible jar builds
- [ ] GraalVM native-image compatibility
- [ ] Android API-level compatibility
- [ ] Qodana: keep or remove

## Wishlist

- [ ] POSIX longest-leftmost capture groups
- [ ] Streaming input (InputStream / ByteBuffer)
- [ ] condy / invokedynamic per-regex specialization
- [ ] Ahead-of-time class persistence (.class on disk)
- [ ] Tiered compilation hints (@Contended, @Stable)
- [ ] Loop-based bounded repetition (only if ever needed)
- [ ] Shared Tdfa for asm+vm (halve fuzz compile churn)
- [ ] CFG successors deboxing (perf polish)
- [ ] ZGC vs G1 A/B
