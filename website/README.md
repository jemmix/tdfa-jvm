# tdfa-jvm explained (website)

A long-form explainer site for the [tdfa-jvm](https://github.com/jemmix/tdfa-jvm) regex engine: 14 chapters covering the full compile pipeline (parse → TNFA → determinization → registers → minimization → bytecode/VM execution) and the engineering around it (budgets, testing).

Stack: [Astro](https://astro.build) static site + Tailwind CSS v4 + Svelte 5 islands, with [dagre](https://github.com/dagrejs/dagre) for automata graph layout. No client framework — each island hydrates independently. Every lab has an **⤢ expand** button that opens the whole lab (graph, tables, controls, inputs — same state) in a fullscreen overlay; the step-through islands reserve stable section heights so stepping never reflows the page.

## Develop

```
npm install
npm run dev        # http://localhost:4321
```

## Build

```
npm run build      # static output in dist/
npm run preview
```

## Interactive islands

Seven Svelte islands (`src/islands/`), each backed by a faithful miniature of the corresponding engine code in `src/lib/`:

| Island | Page | Lib | Mirrors |
|---|---|---|---|
| BacktrackingLab | /backtracking/ | `backtracker.ts` | a naive backtracking VM (the thing the engine refuses to be) |
| AstLab | /parsing/ | `parse.ts` | `parser/Parser.java` — same AST shapes, same tag numbering, same rejections |
| PikeVmLab | /tnfa/ | `thompson.ts`, `pikevm.ts` | `tnfa/Tnfa.java` shapes (priorities, ntag chains, star hubs, {n,m} desugaring) + the reference Pike VM |
| AlphabetLab | /alphabet/ | — | `TdfaCompiler.computeBreakpoints` (minterm partition) |
| SubsetLab | /determinization/ | `subset.ts` | `tdfa/TdfaCompiler.java` — kernels, h/l history rotation, order-exact interning, transition ops |
| RegisterLab | /registers/ | — | a hand-compiled TDFA showing the register file and φ ops |
| SearchLab | /execution/ | `thompson.ts` | the live-set scan: re-seed, interning, kill points, triggers (`SearchDfa` / `TdfaRunner` strategy ladder) |

The mini-library is intentionally aligned with the Java implementation (same shapes, same constants where they appear), with documented simplifications in each island's caption.

## Tests

`scripts/sanity.ts` differential-tests the mini-library (parser/Thompson/Pike VM/subset construction against a naive backtracker) — 320/320 cases must agree:

```
npx esbuild scripts/sanity.ts --bundle --format=esm --outfile=/tmp/sanity.mjs && node /tmp/sanity.mjs
```
