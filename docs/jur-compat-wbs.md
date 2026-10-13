# JUR-compat default — work breakdown

Open work only, [TODO](../TODO.md)-style one-liners; when every box is
checked the JUR-parity default is fully evidenced and this page retires.
Items 0–5 — boilerplate, the finding-1 prerequisite, pivot parameterization,
fold + U bits, family 6, the flip — are landed; their essays and round logs
live in git history (this file before 2026-10-11). The
[design](jur-compat-default.md) is the full accounting (families, flags,
consequences, decided open questions).

## 6. Evidence re-base

- [ ] Per-flag unit tests from the campaign replay seeds
- [ ] jur campaign classifiers flip from soft-known to hard assertions (each residual — group-participation-tail, İ/ı full-fold, lone-surrogate — fixed or consciously reclassified, none left soft by default)
- [ ] `DifferentialFuzzer` matrix gains the `RE2_COMPAT` lane (lane becomes a matrix axis beside the flag bits; the jur side needs the campaign's flag translation)
- [ ] `(?u)`/UCC flag in the differential matrix vs the jur oracle — the [TODO](../TODO.md) "Fuzz (?u) lane vs jur oracle" item joins this program
- [ ] Re-run the re2j-pinned evidence (5.7 M exhaustive, ~300 M fuzz) in the `RE2_COMPAT` lane
- [ ] Overnight soak + README numbers refresh, both lanes (re2j vs `RE2_COMPAT`, jur vs the default lane)

## 7. Parity suites vs both oracles

The re2j parity suites (`tests/parity/re2j`, the `*ParityTest` classes)
pin the `RE2_COMPAT` lane against re2j; post-flip that oracle monopoly is
historical. Widen every suite that translates to ALSO pin the default
(JUR) lane against the live `java.util.regex` oracle.

- [ ] `JurOracle` beside `Re2jOracle`: default-lane facade compile + the bit↔JDK-flag translation (`CASE_INSENSITIVE`/`DOTALL`/`MULTILINE`/`UNIX_LINES`/`UNICODE_CASE`/`UNICODE_CHARACTER_CLASS` map; `LONGEST_MATCH`, `MULTI_VALUED_TAGS`, `FIND_ONLY`, `DISABLE_UNICODE_GROUPS` and the RE2-only spellings — ungreedy `(?U:` — have no jur reading)
- [ ] Adopt the jur twin per suite as appropriate: translatable cases run both lanes on both engine factories (ASM + VM); re2j-only shapes (POSIX longest, ungreedy `(?U`, re2j-specific accept/reject) document themselves RE2-lane-pinned
- [ ] Group-protocol comparison on the jur side carries group clauses (the family-6 battery's live-JDK re-verification is the shape — no skeleton stripping)

## 8. One `Pattern` or two — decide before the API lock

The flip put two lineages on one surface: the default is JUR parity, the
re2j reading rides the seven opt-out bits / the `RE2_COMPAT` migration OR.
The alternative shape: jur-compat `Pattern` and re2j-compat `Pattern` as
SEPARATE surfaces, each owning its default outright (no migration OR, no
opt-out ceremony; open questions: what happens to the seven public bits,
the `CompileOptions.semantics` route, japicmp surface cost, README/javadoc
narrative).

- [x] Evaluate and decide — **decided: two surfaces, landed (2026-10-13)**
  (the jur facade keeps the root module; the re2j reading ships from its own
  `re2j/` module, the seven bits + `RE2_COMPAT` preset are gone from the
  public API, the machinery lives in `core.compile` behind a two-method emit
  SPI). The split's design + work breakdown pages are retired (landed); the
  `surface-split/*` squash commits carry the design accounting and the
  closed open decisions (re2j flag roster, shared matcher typing,
  split-before-namespace-move sequencing)
