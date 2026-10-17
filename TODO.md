# tdfa-jvm — TODO

Open work only; when empty, the library is finished. See the
[vision](README.md#vision). Completed items and round logs live in
git history (TODO.md before 2026-09-30).

## Correctness

- [ ] JUR-compat evidence re-base tail: both-lane overnight soak + README numbers (per-flag unit tests, the classifier flip — İ/ı fixed in literals AND ranges now, tail/surrogate reclassified as asserted domain exclusions — the re2j-surface + `(?u)` lanes in the fuzzer matrix, and the 5.7 M exhaustive re-run landed; the jur twins for the re2j parity suites landed with item 7; [design](docs/jur-compat-default.md), [work breakdown](docs/jur-compat-wbs.md))
- [ ] Fix quantified-group alternation order vs jur (`(\A|.?o*.){3}` seeds) — root-caused: java.util.regex's `GroupCurly` COMMITS the first `cmin` iterations of a bounded `{n,m}` group greedily and never re-routes them (the match0 backoff only covers iterations beyond `j > min`; OpenJDK `Pattern$GroupCurly`), while our whole-match artifact accepts any path through the unrolled copies. Divergence surface probed: `{n}` / `{n,m}` with **cmin ≥ 2** over a group whose body has alternation with an ε-able branch (`(\A|.){2}`, `(\A|.?o*.){3}` — `matches()` diverges, find/lookingAt agree); `{0,m}`, `{1}`, `{1,m}` and `*`/`+` agree. Fix direction: a committed-copy cut in the whole determinization (prefer-and-kill scoped to the first cmin copies on the whole side only) — see [campaign follow-ups](docs/fuzz-campaigns-2026-10-16.md)
- [ ] Fix forcedRestart quadratic scans on `.*`-anchor shapes (root cause: the unit-semantics ladder has no trigger bound — the codepoint-stepped kill-window proof would miss interior-unit starts, so every unanchored find() is the per-unit restart floor; masked `.*`-prefix walks run the full remaining span per restart. Contained since #70 by the 2 s match budget + the fuzz lane's 500 ms `MATCH_BUDGET` class; measured hot spots for a root fix: the non-disjoint dispatch walk-back (37% of samples) and `positionFlags`'s pair decode)
- [ ] Fast-path positionFlags word scans (isUnicodeWordCodepoint binary-searches the full ~700-range word table per surrogate-pair decode; the supplementary tail is only ~200 ranges — slice it, and consider a per-operation flags memo on the scratch carrier)
- [ ] Retire known-divergence classifiers once engines agree everywhere (gated on the GroupCurly item above — the last known family)
- [ ] Overnight soak with cross-rung on, both lanes (`-Pfuzz.lane` pins; mix by default); refresh README fuzz numbers; target: zero mismatches, zero hangs — first pass found [six findings](docs/fuzz-campaigns-2026-10-16.md) — findings 1, 3, 4 addressed (see [follow-ups](docs/fuzz-campaigns-2026-10-16.md)); only the GroupCurly family should still surface
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
