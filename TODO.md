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

- [ ] ORIGIN_SIM leftmost-start miss: forced rung, deterministic repro ([findings](docs/fuzz-campaigns-2026-10-24.md))
- [ ] Review memo deletion; restore if unicode long-scan regime matters ([pricing](docs/search-dfa-memo-tradeoffs.md))
- [ ] Runner immutability: frozen fields, honest LRU memos ([design](docs/runner-immutability.md))
- [ ] Triage 2026-10-24 jur soak findings ([findings](docs/fuzz-campaigns-2026-10-24.md))
- [ ] JUR-compat re-base tail: both-lane soak, README numbers ([design](docs/jur-compat-default.md), [WBS](docs/jur-compat-wbs.md))
- [ ] Watch JDK-8381696; retire `SKIP_RESTART_TAG_LEAK` on JDK fix ([analysis](docs/jdk-8381696-restart-tag-leak-fix.md))
- [ ] Overnight cross-rung soak, both lanes; refresh README numbers ([findings](docs/fuzz-campaigns-2026-10-24.md))
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
- [ ] Decide Semantics scope (parity axes vs knobs) before lock
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
