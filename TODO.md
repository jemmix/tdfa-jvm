# tdfa-jvm — TODO

Open work only; when empty, the library is finished. See the
[vision](README.md#vision).

Format — enforced by `scripts/check-todo.sh`, which rides
`./gradlew check` (the local and CI gate alike):

- one line per item, at most **10 words** — terse enough to scan;
- detail lives in a linked doc or the landing PR, never inline prose;
- no completed items — delete on landing; history lives in git
  (TODO.md before 2026-09-30).

## Features

- [ ] `\G` previous-match-end anchor, both tiers
- [ ] `\X` grapheme clusters via UAX #29 break properties
- [ ] `\b{g}`/`\b{gc}` boundaries via tracker automaton
- [ ] `CANON_EQ`: compile-time canonical-equivalence pattern rewrite
- [ ] Scoped inline `(?d)`: per-scope terminator tables
- [ ] Jur lane `\1`–`\9`: java backref precedence, loud reject
- [ ] ASM `usePattern`: delegate to machinery matcher
- [ ] Possessive quantifiers: prune directive, greedy-coincident tags
- [ ] Atomic groups: decision record, VM prototype first

## Correctness


- [ ] JUR-compat re-base tail: local soak, README numbers
- [ ] Watch JDK-8381696; leak skips auto-retire via probe ([analysis](docs/jdk-8381696-restart-tag-leak-fix.md))
- [ ] Local overnight cross-rung soak; refresh README numbers
- [ ] Verify TDFA(1) conformance vs paper wording (lookahead delay)
- [ ] Upstream re2j PRs #208/#212/#213: maintainer review

## Performance

- [ ] Required-infix needle-set prefilter: decision record ([SafeRE comparison](docs/safere-comparison.md))

## Benchmarks

- [ ] Hyperscan corpus / Snort rules
- [ ] Long-input scans across diverse patterns

## Engineering

- [ ] Complexity review: land recorded removals, simplifications ([review](docs/complexity-review.md))
- [ ] Namespace move jemmix → tagmaton (gates publish)
- [ ] First Maven publish
- [ ] Dispatch-engine spin-off covering tdfa weak points ([design](docs/dispatch-engine.md))
- [ ] japicmp baselines ×4
- [ ] License headers
- [ ] TdfaRunner trace → per-engine (test-only)
- [ ] JPMS module-info
- [ ] JavaDoc all public API
- [ ] Glossary: prune mannered/unnecessary terms, keep load-bearing ones
- [ ] Decide Semantics scope (parity axes vs knobs) before lock
- [ ] Compat surface rungs 2–4 ([design](docs/engine-compat-surface.md))
- [ ] Lock 1.0 API stability guarantees
- [ ] JaCoCo coverage targets
- [ ] Reproducible jar builds
- [ ] GraalVM native-image compatibility
- [ ] Android API-level compatibility
- [ ] Qodana: widen gate past javadoc (baseline?)

## Wishlist

- [ ] POSIX longest-leftmost capture groups
- [ ] condy / invokedynamic per-regex specialization
- [ ] Ahead-of-time class persistence (.class on disk)
- [ ] Tiered compilation hints (@Contended, @Stable)
- [ ] Loop-based bounded repetition (only if ever needed)
- [ ] Kotlin/Native compile-time TDFA emitter ([SafeRE comparison](docs/safere-comparison.md))
- [ ] Shared Tdfa for asm+vm (halve fuzz compile churn)
- [ ] CFG successors deboxing (perf polish)
- [ ] ZGC vs G1 A/B
