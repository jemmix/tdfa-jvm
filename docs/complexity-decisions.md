# Decision queue — one line each

Open calls from the [complexity review](complexity-review.md) that are
yours to make. Work top-down; check a box when decided and the landing
order in the review does the rest. Evidence behind each: follow the Cxx
link.

- [ ] **1. Multi-valued tags — delete or fund it** ([C55](complexity-review.md#c55--multivalued_tags-the-flagship-challenge-decision-l)).
  A feature no Java/re2j user can call (they have no such API), carried
  through the whole engine — and never fuzz-tested. Delete while
  unpublished (cheap), or commit to fuzzing it. Worst option: keep as is.
- [ ] **2. Dialect-mixing dial — public or internal** ([C56](complexity-review.md#c56--compileoptionssemantics-the-public-axis-probe)).
  A public knob for blending the Java-style and RE2-style readings piece
  by piece; only tests turn it. Make it internal before the API freezes?
- [ ] **3. Plug-in-your-own-engine hook — public or test-only** ([C57](complexity-review.md#c57--regexenginefactory-bring-your-own-engine-as-public-api)).
  Lets callers swap in a custom matcher; only our tests use it. Make it
  test-only and shrink the public surface?
- [ ] **4. Compile-progress callback — public or internal** ([C58](complexity-review.md#c58--compileobservercompilationreport-as-public-machinery)).
  A "watch the compiler work" listener; only tests listen. Make it
  internal?
- [ ] **5. Qodana scanner — wire it up or drop it** ([C35](complexity-review.md#c35--qodana-s-decide)).
  Configured in the build but run by nothing, has found nothing so far.
  Give it a real CI job, or remove the config? (Your TODO, open.)
- [ ] **6. PikeSim — product or test tool** ([C34](complexity-review.md#c34--libpikesim-placement-sm-simplify)).
  Our tiny reference matcher is built like a shipped module though only
  tests use it. Decide before the first release: ship it or move it to
  the test area.
- [ ] **7. Old Unicode snapshot — ship or test-only** ([C30](complexity-review.md#c30--generated-version-modules-m-examine)).
  We freeze two Unicode versions; the older one mainly proves
  version-pinning works. Ship both at publish, or just the current one?
- [ ] **8. Broken Unicode-picker property — fix or delete** ([C45](complexity-review.md#c45--provider-selection-spi-untested-and-cannot-load-the-projects-own-pinned-modules)).
  The global `-D` switch for custom tables can't load our own modules and
  has no tests. Fix it, or delete it (the per-compile API still works)?
- [ ] **9. Fuzzer skip rule — deliberate or debris** ([C33](complexity-review.md#c33--fuzzer-harness-complexity-m-simplify)).
  One overnight-fuzz skip was deleted, then re-added 25 minutes later —
  rescue or accident? If accident, it goes with the other dead fuzzer
  bits.
- [ ] **10. API promise for 1.0 — two dialects, or mixable** ([C21](complexity-review.md#c21--the-8-axis-semantics-matrix-m-decide)).
  Review says: ship exactly two preset readings (Java-style, RE2-style)
  and no per-piece mixing promise. Confirm and the TODO closes.

*Decided calls land through the review's suggested order; nothing above
blocks the 29 already-verdicted cleanups.*
