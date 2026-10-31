# tdfa-jvm — TODO

Open work only; when empty, the library is finished. See the
[vision](README.md#vision).

Format — enforced by `scripts/check-todo.sh`, which rides
`./gradlew check` (the local and CI gate alike):

- one line per item, at most **10 words** — terse enough to scan;
- detail lives in a linked doc or the landing PR, never inline prose;
- no completed items — delete on landing; history lives in git
  (TODO.md before 2026-09-30).

## Correctness

- [ ] Review memo deletions; restore if unicode regimes matter ([pricing](docs/search-dfa-memo-tradeoffs.md))
- [ ] JUR-compat re-base tail: both-lane soak, README numbers
- [ ] Watch JDK-8381696; retire `SKIP_RESTART_TAG_LEAK` on JDK fix ([analysis](docs/jdk-8381696-restart-tag-leak-fix.md))
- [ ] Overnight cross-rung soak, both lanes; refresh README numbers
- [ ] Oracle-check stretched inputs; compare ASM on them too
- [ ] Fuzz non-String CharSequence inputs against the reference sim
- [ ] Fuzz the multi-valued lane ([plan](docs/fuzz-multi-valued-tags.md))
- [ ] Verify TDFA(1) conformance vs paper wording (lookahead delay)
- [ ] Upstream re2j PRs #208/#212/#213: maintainer review

## Performance


## Benchmarks

- [ ] Hyperscan corpus / Snort rules
- [ ] Long-input scans across diverse patterns

## Engineering

- [ ] Complexity review: land recorded removals, simplifications ([review](docs/complexity-review.md))
- [ ] Namespace move jemmix → tagmaton (gates publish)
- [ ] First Maven publish
- [ ] japicmp baselines ×4
- [ ] License headers
- [ ] TdfaRunner trace → per-engine (test-only)
- [ ] JPMS module-info
- [ ] JavaDoc all public API
- [ ] Decide Semantics scope (parity axes vs knobs) before lock
- [ ] Lock 1.0 API stability guarantees
- [ ] JaCoCo coverage targets
- [ ] Reproducible jar builds
- [ ] GraalVM native-image compatibility
- [ ] Android API-level compatibility
- [ ] Qodana: widen gate past javadoc (baseline?)

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
