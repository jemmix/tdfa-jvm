# TDFA §6 Optimization Pipeline — Implementation Plan

Reference: Borsotti & Trofimovich, *A closer look at TDFA* (2022), §6
*Implementation*. TeX at
`re2c/doc/papers/2022_a_closer_look_at_tdfa/2022_borsotti_trofimovich_a_closer_look_at_tdfa.pdf`.

This plan covers the remaining canonical-TDFA optimizations from the paper
that we have not yet implemented. Together with what is already in place, they
constitute a faithful BT2022 §5–6 implementation — the README's first stated
goal. The plan is the faithfulness-focused companion to
`REBAR-SPEEDUP-PLAN.md` (which is about wall-time on the parity suite).

re2c reference paths point at `src/` (the AOT lexer generator, which is the
mirror of our compile-once-match-many model — see README §Vision).

## Scope

| § | Item | re2c file | Status |
|---|---|---|---|
| 5 | TDFA(1) determinization (Algorithm 3) | `src/dfa/determinization.cc` | ✅ done (`Tdfa.Compiler`) |
| 5 | `map` register-bijection dedup | (in `find_state.cc`) | ✅ done (`Compiler.tryMap`) |
| 5 | `topological_sort` for copy ops | (in `tcmd.cc`) | ✅ done (`Compiler.topologicalSort`) |
| 6.1 | UTree prefix tree for tag paths | (BT19, `tag_history.h`) | ✅ done (`UTree.java`) |
| 6.2.2 | Register-aware Moore minimization | `src/dfa/minimization.cc` | ✅ done (`Tdfa.DfaMinimizer`) |
| 6.4 | Fixed tags | `src/regexp/fixed_tags.cc` | ✅ done (`io.github.jemmix.tdfa.opt.FixedTags`) |
| 6.3 | Register optimizations pipeline | `src/cfg/{compact,dce,interfere,liveanal,normalize,varalloc,optimize,rename}.cc` | ✅ done (`io.github.jemmix.tdfa.cfg.{Cfg,Optimize}`) |
| 6.2 | Fallback operations | `src/dfa/fallback_tags.cc` | ✅ done (`io.github.jemmix.tdfa.tdfa.FallbackOps`) |
| 7 | Multi-pass TDFA | `lib/reg{comp,exec}_dfa_multipass.*` | out of scope — JIT use case, see README §Vision |
| 9 | Deterministic points | (future work in paper itself) | out of scope — paper has no concrete algorithm |

## Why these three

The paper's ordering, followed by re2c, is: **fixed tags (RE level) →
determinization → register optimizations (CFG level) → minimization**.
We already have determinization + minimization, so the gaps are the two
pre-minimization passes and the fallback semantics:

- **#1 Fixed tags** drops tags at the RE level before NFA construction —
  reduces |T|, the tag count that drives register and op counts downstream.
- **#2 Register optimizations** runs on the post-determinization CFG and is
  the paper's recommended precondition for effective minimization. Our
  current minimization works but rarely fires precisely because register-op
  lists aren't normalized.
- **#3 Fallback operations** is required for correctness of POSIX
  leftmost-longest capture extraction (the README caveat "Full POSIX closure —
  heuristic only"). It is not a perf optimization per se; it closes a latent
  capture-correctness bug on patterns like `(a|ab)(c*)` where the longer
  alternative loses and we currently fail to restore `c`'s start register.

## Implementation order

Recommended order, by paper's dataflow + ROI:

```
   RE level ─→  determinization ─→  register opts  ─→  minimization
       │                                              (already done)
       ▼
   #1 Fixed tags                                  #2 Register opts
                                                       │
                                                       ▼
                                                 #3 Fallback ops
                                                 (post-minimization
                                                  semantic fix)
```

1. **#1 Fixed tags** — cheapest, RE-level only, no interaction with existing
   code. ~1 day.
2. **#2 Register optimizations pipeline** — biggest faithfulness gap, makes
   #1's tag reductions visible in the runtime register file, and unlocks
   additional minimization wins. ~3 days.
3. **#3 Fallback operations** — depends on the CFG from #2 (it's another
   register-op emitter). ~2 days.

After #3, the README's "Full POSIX closure — heuristic only" caveat can be
removed and goal G1 ("faithful BT2022 §5–6") is genuinely met.

---

## #1 Fixed tags (BT22 §6.4) — ✅ DONE

> Paper algorithm: Figure 9 (alg_fixed_tags), p. 28. re2c:
> `src/regexp/fixed_tags.cc`. **Landed** in `io.github.jemmix.tdfa.opt.FixedTags`
> (commit pending).

### What it does

If two tags are within a fixed character-distance on every path through a
subexpression, drop one and reconstruct it post-match from the other.
Linear-time top-down recursion over the AST.

Example (paper): `(1a2)^* 3 (a|4b) 5 b^*` — `t1` is one symbol before `t2`, so
`t1 = (NIL if t2 = NIL else t2 - 1)`. `t3` is one symbol before `t5`, so
`t3 = t5 - 1`. After optimization, only `t2, t4, t5` need registers.

The paper notes that on POSIX REs with nested submatch groups this pass alone
can beat all register optimizations combined.

### Algorithm sketch

```
fixed_tags(e, base_tag, dist, level_dist):
    if e is ε:               return (base_tag, dist, level_dist)
    if e is symbol:          return (base_tag, dist + 1, level_dist + 1)
    if e is e1 | e2:
        (_, _, k1) = fixed_tags(e1, NO_BASE, NAN, 0)
        (_, _, k2) = fixed_tags(e2, NO_BASE, NAN, 0)
        if k1 == k2:         return (base_tag, dist + k1, level_dist + k1)
        else:                return (base_tag, NAN, NAN)         // branches disagree
    if e is e1 e2 (concat):
        (t2, d2, k2) = fixed_tags(e2, base_tag, dist, level_dist)
        (t1, d1, k1) = fixed_tags(e1, t2,       d2,   k2)
        return (t1, d1, k1)
    if e is e1^{n, m}:
        (_, _, k1) = fixed_tags(e1, NO_BASE, NAN, 0)
        if n == m:           return (base_tag, dist + n*k1, level_dist + n*k1)
        else:                return (base_tag, NAN, NAN)     // variable length
    if e is tag t1:
        if base_tag != NO_BASE and dist != NAN:
            mark t1 fixed-on base_tag with offset dist
            return (base_tag, dist, level_dist)              // base unchanged
        else:
            return (t1, 0, level_dist)                       // t1 becomes base
```

`NO_BASE = -1`, `NAN` propagates through arithmetic (any op → `NAN`).

Levels: descend into alt/repetition starts a new level (no base inherited);
concat does not. Two tags on different levels cannot fix on each other even
if they're at fixed distance on some path (because there's another path that
visits only one).

### Where to add

- **New file**: `src/main/java/io/github/jemmix/tdfa/opt/FixedTags.java`.
- **New AST annotation**: `Ast.Tag.fixedOn` (int, default -1) and `Ast.Tag.fixedOffset` (int).
- **Call site**: between `Parser.parse(src)` and `Tnfa.compile(ast)` in
  `Regex.compile` (Regex.java:60). One extra line: `FixedTags.apply(ast)`.
- **Capture extraction**: in `MatchResult` (or wherever raw tag offsets are
  resolved to group bounds), add `tag[i] = tag[fixedOn] == NIL ? NIL :
  tag[fixedOn] - fixedOffset` for fixed tags. Find existing spot in
  `TdfaRunner` where the final-register block is exposed.

### Tag exclusion from NFA construction

`Tnfa.compile` must skip tags marked fixed-on so they don't get NFA edges,
registers, or regops. They're reconstructed lazily at match time.

### Verification

- Unit tests: every pattern from paper §6.4 example + the cases in
  `re2c/test/messages/fixed_tags.i` and `re2c/test/posix/fixed_tags*`.
- End-to-end: parity with previous capture output on the rebar suite
  (counts must not change).
- Perf: lexer-veryl compile should drop noticeably (it has ~88 capture
  groups, many keyword-bound `\b…\b` pairs at fixed distance).

### Effort

~1 day. Algorithm is ~20 lines; integration is ~50.

---

## #2 Register optimizations pipeline (BT22 §6.3) — ✅ DONE

> Paper algorithms: Figure 7 (alg_opt1 — compaction, liveness, DCE,
> interference, register allocation) + Figure 8 (alg_opt2 — normalization,
> topological sort). re2c: `src/cfg/*`. **Landed** in
> `io.github.jemmix.tdfa.cfg.{Cfg,Optimize}` (commits eeeac46, 18c0561, 5abba8c).

### What it does

After determinization, the DFA's register ops form a control flow graph (CFG).
Apply standard compiler optimizations to it:

```
1. compaction         — drop registers never written or read
2. for i in 1..N=2:
     a. liveness      — backward dataflow, L[block][reg] = bool
     b. dead-code elim — remove writes to dead registers
     c. interference   — graph I[i][j] = "can't share a register"
     d. allocation     — Chaitin-style with copy coalescing
     e. normalization  — sort+dedup set ops; topo-sort copy ops
3. renaming           — apply allocation map V[old] → new throughout
```

Effect: dramatically fewer registers, fewer COPY ops, normalized op lists
(which lets minimization's `opSeqId` interning find more equivalences).

### Where to add

- **New package**: `io.github.jemmix.tdfa.cfg`.
- **New files** mirroring re2c's `src/cfg/`:
  - `Cfg.java` — the CFG itself: nodes = register-op blocks (per-transition
    and per-final-state), arcs = reachability without intervening ops.
  - `Compact.java` — compaction pass.
  - `LiveAnal.java` — liveness analysis.
  - `Dce.java` — dead-code elimination.
  - `Interfere.java` — interference graph.
  - `VarAlloc.java` — allocation with copy coalescing.
  - `Normalize.java` — normalization.
  - `Rename.java` — apply the renaming.
  - `Optimize.java` — driver (orchestrates the 2-iteration loop).
- **Driver call site**: in `Tdfa.Compiler.compile`, between determinization
  (line 311, after the work loop) and the array-materialization second pass
  (line 411). Replaces the current ad-hoc per-transition op emission with a
  post-processed CFG.

### CFG construction

Three node kinds (paper §6.3):
- **Basic blocks** — register ops on symbolic transitions (from
  `transitionRegops`).
- **Final blocks** — final-register ops on accepting states (from
  `finalRegops`).
- **Fallback blocks** — fallback-register ops (added by #3, see below).

Arcs: B1 → B2 if B2 is reachable from B1 without passing through another
register-op block. Plus fallback-block arcs to all blocks reachable on
fallback paths.

The data is already in `stateMeta/stateBase/stateFinalOpsOff/ranges/ops` after
determinization; the CFG builder reads these and produces a more abstract
graph.

### Tricky parts

- **Liveness with cycles**: DFA transition graph has cycles (loops). The
  paper's algorithm is a fixpoint iteration over basic blocks in post-order
  until no row changes. For large DFAs the boolean matrix
  `L[block][register]` can be memory-heavy — use bitsets.
- **Append operations** (multi-valued tags): the interference pass special-
  cases them — registers used in append ops interfere with all registers not
  used in append ops. We currently only support single-valued tags, so this
  can be deferred.
- **Normalization invariants**: set-op blocks must be deduped+sorted; copy-op
  blocks must be topo-sorted (paper alg `topological_sort` on p. 24). Our
  existing `topologicalSort` is per-transition only; needs generalization to
  whole-block scope.

### Verification

- Differential: rebar suite pass/fail counts unchanged.
- Perf: register count (`globalMaxReg`) should drop on capture-heavy patterns
  (lexer-veryl, tom-sawyer). Minimization should fire more often (visible via
  `-Dtdfa.debug` → more `[tdfa] minimized: N -> M` lines).
- Unit: small hand-crafted cases where the optimal allocation is known.

### Effort

~3 days. Pseudocode in paper is ~150 lines; Java translation plus CFG
plumbing is ~600.

---

## #3 Fallback operations (BT22 §6.2) — ✅ DONE

> Paper algorithm: Figure 5 (alg_fallback), p. 22. re2c:
> `src/dfa/fallback_tags.cc`. **Landed** in
> `io.github.jemmix.tdfa.tdfa.FallbackOps` (commit pending).

### What it does

For POSIX leftmost-longest matching, the runner may accept at state S, keep
stepping for a longer match, fail, and **fall back** to S. The position
backup we already do (`lastAcceptPos` in `TdfaRunner`). But registers
clobbered on the failing path must also be restored, otherwise capture
extraction reads garbage.

The paper's algorithm:

1. Add a **default state** making δ total (target=-1 ranges effectively
   become transitions to default; if EOF is possible, add quasi-transition
   from non-final states to default).
2. Backward propagation: a state is a **fallback state** iff it's a final
   state AND the default state is reachable from it.
3. For each fallback state S, DFS over states from which default is
   reachable, collecting clobbered registers (LHS of any register op).
4. Emit **backup COPY ops** on transitions out of S (into the unused final
   register slots `R_f`), and **restore COPY ops** on the fallback quasi-
   transition (the runner's "give up the longer match and report S as the
   match" path).

### Implementation notes

- We use **dedicated backup slots** (new registers allocated above the
  regopt range) instead of the paper's "final[i] as backup slot" strategy.
  Reason: BT22 §6.3 register optimizations may coalesce `working[j]` and
  `final[i]` into one slot, making `final[i] ← working[j]` a no-op backup
  that the clobbering immediately overwrites. Dedicated slots are immune
  to coalescing.
- The runner chooses between φ and ψ at runtime: ψ is used iff
  `stateIsFallback[lastAcceptState] && pos > lastAcceptPos` (i.e. we
  actually took a transition since the last accept and might have
  clobbered). Direct accepts use φ.
- Both VM (`TdfaRunner`) and ASM (`TdfaAsmBackend`) backends updated.
- Toggle with `-Dtdfa.nofallback=true`.

### Demonstration

Pattern `([A-Z][a-z]+)+(,)?`:

| Input     | Group 0   | Group 1 (M3 off) | Group 1 (M3 on) |
|-----------|-----------|------------------|-----------------|
| `Hello`   | `[0,5)`   | `[0,5)`          | `[0,5)`         |
| `HelloW`  | `[0,5)`   | `[5,5)` ✗        | `[0,5)` ✓       |
| `HelloWor`| `[0,8)`   | `[5,8)`          | `[5,8)`         |

`HelloW` is the canonical fallback case: after matching `Hello`, the
runner takes the `W` transition (start of a new word that doesn't complete
before EOF), then falls back. The `W` transition's `SET working[g1_open]
= pos` clobbers the open register; without M3, group 1 is `[5,5)` instead
of `[0,5)`.

### What it does NOT fix

The pre-existing `curated/05-lexer-veryl/single` count-captures failure
(123999 vs expected 124800) is unrelated: the lexer DFA is total (every
char matches the trailing `.` branch), so no fallback states are detected
and M3 doesn't fire. That bug appears to be in match-counting or match-
boundary computation, not capture preservation.

---

## Out of scope

### §7 Multi-pass TDFA

Paper §7 explicitly pitches this as the JIT/RE-library alternative to
canonical TDFA, trading slower matches for faster compile. Our model is AOT
(README §Vision: "compile every accepted pattern to a tagged deterministic
finite automaton, then to JVM bytecode"). re2c itself splits the two: `src/`
(AOT lexer generator, canonical TDFA + §6 optimizations) vs `lib/` (JIT regex
library, multi-pass TDFA). We mirror `src/`.

### §9 Deterministic points

The paper lists this as future work without a concrete algorithm. Defer until
the paper (or re2c) publishes one.

---

## Milestone summary

| Milestone | Items | Faithfulness delta | Wall-time delta on rebar |
|---|---|---|---|
| M1 | #1 Fixed tags ✅ | Tag count down on capture-heavy REs | ~2 s (56 s → 56 s; gain masked by other compile cost) |
| M2 | #2 Register optimizations ✅ | `globalMaxReg` down 50–66%; minimization fires more often | neutral at suite level (gains masked; large-DFAs skip via cap) |
| M3 | #3 Fallback operations ✅ | POSIX capture correctness on fallback states | neutral (correctness fix; suite DFAs mostly total) |

After M3, goal G1 (*faithful BT2022 §5–6*) is met. The README caveat about
POSIX closure is no longer accurate for the canonical case (capture
extraction on fallback states is now correct); remaining capture failures
are unrelated engine bugs, not faithfulness gaps.

### M1 verification (observed)

| Pattern | Tags dropped | Notes |
|---|---|---|
| `(abc)` | 1/2 | close fixes on open, offset 3 |
| `(a)(b)(c)` | 5/6 | all fix on rightmost close |
| `(ab)(cd)` | 3/4 | all fix on rightmost close |
| `((ab))` | 3/4 | nested-group case |
| `(a){3}` | 1/2 | single-iteration fixing; outer count is bounded fixed |
| `(a\|b)c` | 1/2 | same-length alt branches |
| `(a\|bb)c` | 0/4 | different-length alt → NaN propagation |
| `(\d+)\.(\d+)` | 1/4 | only the across-`.` adjacency fixes |
| `(a+)` | 0/2 | variable body → no fixing |

Rebar parity: pass=108 fail=2 skip=249 (unchanged); both failures pre-existing.

### M2 verification (observed)

| Pattern | Initial regs | After §6.3 | Reduction |
|---|---|---|---|
| `(abc)` | 4 | 2 | 50% |
| `(\w+@(\w+\.)*\w+)` | 12 | 4 | 66% |
| `(a\|b)*c` | 5 | 2 | 60% |
| `(\d+\.)+\d+` | 7 | 3 | 57% |
| `(\d+)\.(\d+)` | 10 | 4 | 60% |
| `(a+)(b+)` | 10 | 4 | 60% |
| `((ab)+)` | 10 | 4 | 60% |

Rebar parity: pass=108 fail=2 skip=249 (unchanged); both failures pre-existing.
REGOPT_MAX_STATES defaults to 2000: above that the per-DFA pipeline cost
outweighs the benefit. Disable with `-Dtdfa.noregopt=true`.

### M3 verification (observed)

| Pattern                | Input       | Group 1 (M3 off) | Group 1 (M3 on) |
|---|---|---|---|
| `([A-Z][a-z]+)+(,)?`   | `Hello`     | `[0,5)`          | `[0,5)`         |
| `([A-Z][a-z]+)+(,)?`   | `HelloW`    | `[5,5)` ✗        | `[0,5)` ✓       |
| `([A-Z][a-z]+)+(,)?`   | `HelloWor`  | `[5,8)`          | `[5,8)`         |
| `([A-Z][a-z]+)+(,)?`   | `Hello,`    | `[0,5)`          | `[0,5)`         |
| `(a)(b)?`              | `ax`        | `[0,1)`          | `[0,1)`         |

`HelloW` is the canonical fallback case: after matching `Hello`, the runner
takes the `W` transition (start of a new word that doesn't complete before
EOF), then falls back. The transition's `SET working[g1_open] = pos`
clobbers the open register; without M3, group 1 is `[5,5)` instead of
`[0,5)`. With M3's dedicated backup slot, the pre-clobber value is preserved
and ψ restores it at fallback.

Rebar parity: pass=108 fail=2 skip=249 (unchanged); wall 55–66s. The
pre-existing lexer-veryl capture failure is unrelated — the lexer DFA is
total so no fallback states fire. Disable with `-Dtdfa.nofallback=true`.

All BT2022 §5–6 items now landed. Goal G1 (*faithful BT2022 §5–6*) is met.
