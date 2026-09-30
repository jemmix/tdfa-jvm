# Contributing to tdfa-jvm

The goal is a **finished library** — bounded scope, all bugs fixed, then
frozen ([vision](README.md#vision)). These are the working rules for getting
there. Short on process, long on bar.

## Principles

- **Do it the hard way.** The tempting shortcut — landing the feature,
  skipping the second backend, deferring the tests, leaving the docs for
  later — is how libraries rot. Every change goes the extra mile towards
  one cohesive artifact: both backends (ASM and VM), the oracle ladder,
  the benchmarks, the README / `BENCHMARKS.md` / `TODO.md` updates, in the
  same PR. A change you wouldn't ship as part of a 1.0 freeze isn't done.
- **Prevent problems by design.** The best bug is the unrepresentable one.
  Prefer types over validation, validation over defensive branches,
  rejection at pattern-compile time over special cases at match time —
  make the mistake impossible rather than catching it later. Tests verify
  a design; they don't substitute for one.
- **Reduce to the simplest form.** When a change lands, the tree should
  get *smaller*. Delete the system this one replaces instead of parking it
  next to the new one; never keep a fallback "just in case" (we never
  silently fall back to a slower engine — same rule for internal code
  paths); strip the historical comments and outdated code that narrate the
  reshaping — history lives in git, not in the source. Redundancy isn't
  safety: every extra path is another thing to test and another surface
  that can diverge.
- **Rough edges are OK — candidly.** This library is a demo of one
  algorithm and stays honest about that: unsupported syntax is rejected
  loudly, never silently degraded, and known gaps are written down
  (README, `BENCHMARKS.md`, `TODO.md`) rather than papered over. Polish
  what's in scope; state plainly what isn't. Don't trade the demo's
  honesty for a feature.

## Code style

One ruleset, three artifacts in `config/codestyle/` — import `tdfa-idea.xml`
in IntelliJ (Settings → Editor → Code Style → Scheme → Import) and IDE
reformat is CI-clean; `eclipse-formatter.properties` drives the machine
formatter (flat 4-space continuation indent, 120 margin, joins wraps that
fit); `checkstyle.xml` gates what formatters can't rewrite. Reformat with
`./scripts/format.sh`. CI enforces both (`spotlessCheck` + checkstyle ride
`./gradlew check`). Rules: 4-space indent and continuation indent, braces
mandatory for `if`/`for`/`while`/`do`, no star imports, imports instead of
qualified names (same-simple-name collisions stay qualified), members
ordered fields → constructors → methods, nested types last. Generated
(`:unicode:*`) and vendored (`re2j-suite`) code is exempt.

## Before you open a PR

```
./gradlew check          # full gate: tests both backends, spotless, checkstyle
./scripts/format.sh      # if spotless complains
```

- One logical change per PR; behavior changes carry differential evidence
  from the parity suites or a fuzz run
  (see [How it's tested](README.md#how-its-tested)).
- Perf-affecting changes: run `scripts/bench-regression.sh` (15% rule) and
  post the numbers.
- Update `TODO.md` — open work lives there, or it doesn't exist.
