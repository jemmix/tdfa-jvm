# tdfa-jvm — TODO

Open work only; when empty, the library is finished. See the
[vision](README.md#vision). Completed items and round logs live in
git history (TODO.md before 2026-09-30).

## Correctness

- [ ] JUR-compat default: flip the six divergence families, inverted opt-out flags + `RE2_COMPAT` preset ([design](docs/jur-compat-default.md), [work breakdown](docs/jur-compat-wbs.md))
- [ ] Fuzz (?u) lane vs jur oracle (UCC flag in the differential flag matrix)
- [ ] Overnight soak with cross-rung on; refresh README fuzz numbers
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
