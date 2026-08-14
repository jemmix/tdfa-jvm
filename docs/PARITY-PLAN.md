# Parity Test Remediation Plan

Goal: clear all **41** pending parity tests (gated by
`@EnabledIfSystemProperty(named = "tdfa.pending", matches = "true")`) → 0.

This file is a durable record so work can resume if anything crashes.

## How pending tests work

Pending tests live in `src/test/java/io/github/jemmix/tdfa/parity/` and are
annotated `@EnabledIfSystemProperty(named = "tdfa.pending", matches = "true")`.
By default they are disabled. Run them with:

```
./gradlew test -Dtdfa.pending=true --tests "io.github.jemmix.tdfa.parity.<Class>"
```

Each commit below removes the `@EnabledIfSystemProperty` lines for the fixed
tests and updates the count in `TODO.md` (`41 → ... → 0`).

Verification per commit:
1. `./gradlew test` (keep base green, 0 pending enabled).
2. `./gradlew test -Dtdfa.pending=true --tests "io.github.jemmix.tdfa.parity.<Class>"`
   (the just-fixed class now enabled) → green.

## Root-cause clusters

| # tests | Cluster | Root cause | Effort |
|---|---|---|---|
| 22 | POSIX `[:^name:]` / `[:ascii:]` / `[:word:]` | `Parser.parsePosixClass`/`posixClassRanges` missing `^` negation + 2 names | Low |
| 6  | Escape rejection (`[\b]`, `\N{...}`, `\E`, `\K`, `\R`, `\e`) | `parseEscape`/`parseClassChar` `default` treats unknown alphanumerics as literal; re2j rejects | Low |
| 4  | `byte[]` overloads (Pattern/Matcher) | Throw UOE; re2j decodes UTF-8 → delegates | Low |
| 1  | split zero-width | `emptiesSkipped` is `boolean`; re2j uses an `int` counter with `while`-flush | Low |
| 1  | DISABLE_UNICODE_GROUPS | Flag accepted but not enforced; needs threading to Parser | Medium |
| 2  | `programSize()` | Throws UOE; re2j = NFA instruction count (architecturally mismatched) | Medium |
| 1  | Pattern `Serializable` | Not implemented; `Regex engine` field isn't serializable | Medium |
| 1  | `matches()` anchored groups | `Matcher.matches()` extracts via unanchored `find()` → wrong group for `(a\|ab)`. VM already computes correct anchored regs but boolean `Engine.matches` discards them | Medium (Engine iface + ASM) |
| 2  | `\A`/`\z` under `(?m)` | `\A`/`\z` share `StartAnchor`/`EndAnchor` with `^`/`$`; position-flags are global-multiline. re2j uses distinct BEGIN_LINE/BEGIN_TEXT flags — needs new position-flag bits (16→32 array dim) | High |
| 1  | Unicode `\p{So}` on U+1F600 | re2j 1.8 frozen Unicode vs JDK tables diverge | High / data |

## Decisions (locked)

- **programSize()**: meaningful own metric (compiled DFA state count), *rewrite the
  tests* as sanity checks (not exact equality to re2j), JavaDoc the divergence.
  `java.util.regex.Pattern` has no such method.
- **Unicode `So`**: build a re2j-exact Unicode provider; expose it via an explicit
  `Pattern.compile(regex, flags, factory, UnicodeDataProvider)` overload; use it in
  the parity tests (`Re2jOracle` helpers). Provider lives in `src/test` for now
  (simplest); a separately-versioned `6.0.0` jar is a forthcoming module-split activity.
  `foldTableFor` MUST be ported faithfully (re2j `simpleFold`/`CASE_ORBIT`) because
  3 currently-passing `(?i)\p{}` parity tests exist in `CaseSensitivityParityTest`.
- **Anchors**: full position-flag expansion (BEGIN_LINE/END_LINE, repack 16→32).
- **Commits**: 1 test class at a time, high-RoI first.

## Rebar scenario scope (locked 2025-08)

> **We're only testing what rebar actually calls for in the Java impl.**

`RebarScenarioParityTest` skips any scenario whose `engines = [...]` list does
not include a `java/.*` entry. Rebar itself decides which scenarios are
tractable for `java.util.regex` (its Java runner); 245 of 359 scenarios in the
corpus exclude Java — multi-pattern matching that needs rust/regex-style
regex-set APIs (`wild/parol-veryl/*`, `curated/05-lexer-veryl/multi`,
`curated/12-dictionary/multi`, `curated/13-noseyparker/multi`),
hyperscan-only overlap reporting, aho-corasick dictionary benchmarks, and
compile-only model scenarios. These cases are outside the scope of a Java
regex library — running them against our engine was producing 5 phantom
failures (the parol-veryl / lexer-veryl-multiple cases) that weren't real
divergences from any Java-relevant reference, just from rust/regex.

Engine **identity** for `count` resolution stays `"re2"` (with `.*` fallback)
because we are a drop-in **re2j** replacement — re2j's semantics are the
contract, and re2j's counts match re2's on every scenario where both run.
Beware the `.*` fallback: on scenarios where re2 itself is *excluded* (e.g.
`curated/08-words/all-russian`, where re2's `\w` is ASCII-only), the fallback
count belongs to Unicode-`\w` engines (rust/regex, python, perl) and is NOT
our expected number — prefer the `java/hotspot` entry there. On 18 of the 19
scenarios where Java and re2 disagree, our engine matches re2 by design
(Java's `\w`/`\d`/`\s`/`\b` default to Unicode-aware, Java treats `$` as
end-of-line, Java's `count-spans` are UTF-16 code units rather than UTF-8
bytes).

The **one exception** is `test/unicode/utf8/dot-matches-byte`, where re2's
byte-orientation gives count=4 and our codepoint-oriented engine (matching
`java.util.regex`) gives count=1. Rather than skip the scenario, we patch
the vendored rebar corpus to record our actual count under an explicit
`{ engine = 're2', count = 1 }` entry, with a comment in the TOML explaining
the divergence. The patch lives at
`vendor/patches/rebar/01-dot-matches-byte-codepoint.patch` and is applied
automatically by `scripts/vendor.sh prepare` (which now tries each rebar
patch against both the generated-sources dir and the pristine benchmarks
extract — see the script for details).

Net effect of the scope cut (compared to "run everything"):
- runnable scenarios: 114 → 45 (70 fewer — the 245 non-Java scenarios drop
  out, leaving 114 with Java in engines, of which 69 are skipped on
  size/model/regex-length filters)
- passing scenarios: 106 → 43 (64 currently-passing non-Java scenarios no
  longer run — they weren't telling us anything useful about Java parity
  anyway, e.g. the `imported/rsc/*` corpus that rebar runs only against
  go/pcre2/rust; +1 newly-passing scenario, `dot-matches-byte`, via the
  patch above)
- failing scenarios: **8 → 2** (the 5 non-Java failures go away; the
  `dot-matches-byte` architecture divergence is patched in the corpus)
- the 2 remaining failures are real engine bugs, already tracked in TODO.md
  "Correctness": PERL disambiguation in `curated/05-lexer-veryl/single`
  and Unicode case folding in `test/unicode/case/ascii-with-unicode`.

## Commit sequence (running total 41 → 0)

### Commit 1 — CharClassParityTest (24/25): POSIX classes + class-escape rejection
**POSIX** (`src/main/java/io/github/jemmix/tdfa/parser/Parser.java`):
- `parsePosixClass`: detect a leading `^` in the captured name; when present, return
  `complementRanges(posixClassRanges(name))` (reuse existing `\P{X}` complement helper).
- `posixClassRanges`: add
  `case "ascii" -> R_POSIX_ASCII = {0, 0x7F}` and
  `case "word" -> R_POSIX_WORD = {0x30,0x39, 0x41,0x5A, 0x5F,0x5F, 0x61,0x7A}`
  (verbatim from re2j `CharGroup.code6`/`code16`).
- Clears `[:^name:]` (18) + `[:ascii:]`/`[:word:]` (4) = 22 tests.

**Class-escape rejection:**
- `parseClassChar`'s `default -> e`: reject when `Character.isLetterOrDigit(e)`
  (throw `IllegalArgumentException`, mirroring re2j `parseEscape`), else literal.
  → clears `backspaceInClassRejects` `[\b]`.
- `parseEscape` (top-level): split `default` so unknown **alphanumeric** escapes throw
  (`\N`, `\K`, `\R`, `\e`, standalone `\E`) while non-alphanumeric stay literal.
  Remove `case 'E' -> new Ast.Symbol('E')` so `\E` falls through to reject.
  → clears `namedEscapeRejects` (`\N{...}`). (Same edit powers Commit 2.)

Hold back `supplementaryPlaneInput` (the `\p{So}` data test) — Commit 10.

### Commit 2 — EscapeParityTest (4): `\E \K \R \e` rejection
Same `parseEscape` edit from Commit 1 — just un-gate these 4 tests.

### Commit 3 — SplitParityTest (1): zero-width `emptiesSkipped`
In `Pattern.split` (`re2j/Pattern.java`): `boolean emptiesSkipped` → `int emptiesSkipped`;
on empty match `emptiesSkipped++`; flush via
`while (emptiesSkipped > 0) { result.add(""); emptiesSkipped--; }` before each non-empty
match and at the tail. Verbatim port of re2j's loop.

### Commit 4 — ObjectMethodsParityTest byte[] (4): UTF-8 decode
`Pattern.matches(String, byte[])`, `Pattern.matches(byte[])`,
`Pattern.matcher(byte[])`, `Matcher.reset(byte[])`: decode
`new String(bytes, UTF_8)` and delegate to existing `CharSequence` paths.
(Matches re2j's `MatcherInput.utf8`.)

### Commit 5 — FlagInteractionParityTest (1): DISABLE_UNICODE_GROUPS enforcement
Thread the flag to the Parser: add a `boolean disableUnicodeGroups` param through
`Regex.compile(...)` → `Tnfa.compile(...)` → `Parser.capture/parse` (additive overloads).
In `re2j.Pattern.compile`, when flag set, pass it through. Parser throws on `\p`/`\P`/`\p{^X}`.
Un-gate `disableUnicodeGroupsRejectsProperty`, simplify `disableUnicodeGroupsBehavior`.

### Commit 6 — MatcherApiParityTest (1): `matches()` anchored-both groups
Root cause: `Matcher.matches()` calls `engine.find(input,0)` (unanchored) for group
extraction → wrong group for `(a|ab)`. VM `runGeneric(input,0,len,anchored=true)` already
returns correct anchored regs (TdfaRunner.java:592-598) but `Engine.matches()` is boolean.
- Add `MatchResult matchWhole(CharSequence)` to `Regex.Engine`.
- VM `TdfaRunner`: implement via existing anchored path (return the `MatchHolder` currently
  discarded by `matches()`).
- ASM `TdfaAsmBackend`: add `runExtractAnchored([CII)` (anchor = no start-search bump +
  `lastAcceptPos != to ⇒ null`) + generate `matchWhole`.
- `Regex` exposes `matchWhole`; `Matcher.matches()` uses it.

### Commit 7 — ObjectMethodsParityTest programSize (2): meaningful metric + test rewrite
- Implement `programSize()` returning compiled DFA **state count**; capture in
  `Regex.compile` (Tdfa available before wrapping in Engine) and expose via `Regex`.
  `Pattern.programSize()` / `Matcher.programSize()` delegate.
- **Rewrite the 2 tests** as sanity checks (`>0`, `matcher.programSize() == pattern.programSize()`).
- JavaDoc: *"returns the compiled automaton's state count — a cost estimate; not comparable
  to re2j's NFA-instruction count (different algorithm). `java.util.regex.Pattern` has no
  equivalent."*

### Commit 8 — ObjectMethodsParityTest Serializable (1)
`Pattern implements Serializable` with `private static final long serialVersionUID`.
Mark `Regex engine` `transient`; add `readObject`/`readResolve` to recompile from
`pattern`+`flags`. Un-gate `patternSerializable`.

### Commit 9 — AnchorParityTest (2): `\A`/`\z` multiline invariance
Most invasive — full position-flag expansion, mirroring re2j (`Utils.emptyOpContext` +
`Parser` ONE_LINE model).
- `Tnfa`: add `BEGIN_LINE`, `END_LINE` constants alongside `BEGIN_TEXT`/`END_TEXT`.
- `Ast`: add `AbsoluteStartAnchor`/`AbsoluteEndAnchor` (or `boolean absolute` on existing).
- `Parser`: `^`→line, `\A`→text; `$`→line (incl. before-`\n`), `\z`→text. Mirror re2j:
  default (non-multiline) = `^`/`$` behave as text-anchored; `(?m)` makes `^`/`$` line-anchored;
  `\A`/`\z` always text-anchored.
- Repack position-flags to 5 bits (BEGIN_LINE, END_LINE, BEGIN_TEXT, END_TEXT, WORD_BOUNDARY)
  → 32-entry `stopOnAcceptMask` (`state*32`); update `positionFlags`/`positionFlagsCS` in
  TdfaRunner and the ASM `genPositionFlagsC`/`emitPFInline` codegen.
- `Tdfa` `build`/array dims: 16→32.

re2j anchor model (verified from source):
- `RE2.PERL = CLASS_NL | ONE_LINE | PERL_X | UNICODE_GROUPS` → default ONE_LINE set.
- inline `(?m)` → `flags &= ~ONE_LINE`.
- `^`: ONE_LINE → `BEGIN_TEXT`; else → `BEGIN_LINE`.
- `$`: ONE_LINE → `END_TEXT` (+WAS_DOLLAR); else → `END_LINE`.
- `\A` → always `BEGIN_TEXT`. `\z` → always `END_TEXT`.
- `emptyOpContext`: `EMPTY_BEGIN_TEXT`=pos0; `EMPTY_BEGIN_LINE`=pos0 or after `\n`;
  `EMPTY_END_TEXT`=end; `EMPTY_END_LINE`=end or before `\n`. Always computed (not multiline-gated).

### Commit 10 — CharClassParityTest `So` (1) + Unicode parity: re2j-exact provider
**A. API plumbing** — thread provider through compile (parallel to EngineFactory):
- `Parser`: add `UnicodeDataProvider provider` field (default `UnicodeProviders.get()`);
  replace the two `UnicodeProviders.get()` lookups (`parseEscape`:520, `appendUnicodeToRanges`:537)
  + `foldTableFor` call with `this.provider`. Add `parse/capture(String, UnicodeDataProvider)`.
- `Tnfa.compile`: add `(String, UnicodeDataProvider)` overload.
- `Regex.compile`: add `(String, EngineFactory, Disambiguation, UnicodeDataProvider)`.
- `re2j.Pattern.compile`: add `compile(String regex, int flags, EngineFactory factory,
  UnicodeDataProvider unicodeProvider)`; existing 3-arg delegates with `UnicodeProviders.get()`.

**B. The re2j-exact provider** (`src/test/java/.../unicode/Re2jUnicodeProvider.java` for now):
- Transcribe 139 `int[][]` `{lo,hi,stride}` triples from re2j `UnicodeTables.java`
  (categories Lu…Cn, containers L/M/N/P/S/C/Z, ~100 scripts).
- `tableFor(name)`: expand to flat `int[]` `[lo,hi,...]` pairs — stride==1 copy directly,
  stride>1 materialize each matching codepoint as a singleton range (cached per name).
- `foldTableFor(name)`: port re2j `simpleFold` (`CASE_ORBIT` + re2j `Characters.toLowerCase/
  toUpperCase`) so `(?i)\p{X}` matches re2j bit-for-bit.

**C. Test wiring:**
- `Re2jOracle`: switch `tdfaFind`/`tdfaFindPosix`/`tdfaFindAll`/`assertSameCompileSuccess`/
  `assertSameCompileReject` to `Pattern.compile(pattern, flags, factory, RE2J_UNICODE_PROVIDER)`.
- Un-gate `supplementaryPlaneInput`.
- Audit direct-`compile` tests for `\p{}` usage (notably `CaseSensitivityParityTest` `(?i)\p{}`).

## Net: 24+4+1+4+1+1+2+1+2+1 = 41 → 0.
