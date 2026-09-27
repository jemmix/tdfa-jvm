<script lang="ts">
    // Breakpoints / minterms lab: toggle character classes, watch the
    // codepoint line get cut at every class boundary.

    interface Cls {
        name: string;
        ranges: [number, number][];
        negated: boolean;
        on: boolean;
        color: string;
    }

    let classes = $state<Cls[]>([
        { name: '[a-z]', ranges: [[97, 122]], negated: false, on: true, color: '#0d9488' },
        { name: '[0-9]', ranges: [[48, 57]], negated: false, on: true, color: '#0284c7' },
        { name: '[a-f]', ranges: [[97, 102]], negated: false, on: true, color: '#c2410c' },
        { name: '[@.]', ranges: [[46, 46], [64, 64]], negated: false, on: true, color: '#7c3aed' },
        { name: '[^a-y 0-9 . @ A-Z]', ranges: [[48, 57], [64, 64], [65, 90], [97, 121]], negated: true, on: false, color: '#be185d' },
    ]);

    const MIN = 32;
    const MAX = 127;

    function matches(c: Cls, cp: number): boolean {
        const inR = c.ranges.some(([lo, hi]) => cp >= lo && cp <= hi);
        return c.negated ? !inR : inR;
    }

    let breakpoints = $derived.by(() => {
        const bps = new Set<number>([MIN, MAX + 1]);
        for (const c of classes) {
            if (!c.on) continue;
            const rs = c.negated ? negate(c.ranges) : c.ranges;
            for (const [lo, hi] of rs) {
                if (lo > MIN && lo <= MAX) bps.add(lo);
                if (hi + 1 > MIN && hi + 1 <= MAX) bps.add(hi + 1);
            }
        }
        return [...bps].sort((a, b) => a - b);
    });

    let cells = $derived.by(() => {
        const out: { lo: number; hi: number; active: Cls[] }[] = [];
        for (let i = 0; i + 1 < breakpoints.length; i++) {
            const lo = Math.max(breakpoints[i], MIN);
            const hi = Math.min(breakpoints[i + 1] - 1, MAX);
            if (lo > hi) continue;
            const active = classes.filter((c) => c.on && matches(c, lo));
            out.push({ lo, hi, active });
        }
        return out;
    });

    function negate(rs: [number, number][]): [number, number][] {
        const sorted = [...rs].sort((a, b) => a[0] - b[0]);
        const out: [number, number][] = [];
        let next = 0;
        for (const [lo, hi] of sorted) {
            if (lo > next) out.push([next, lo - 1]);
            next = Math.max(next, hi + 1);
        }
        if (next <= 0x10ffff) out.push([next, 0x10ffff]);
        return out;
    }

    function cpLabel(cp: number): string {
        return cp === 127 ? 'DEL' : String.fromCharCode(cp) === ' ' ? '␠' : String.fromCharCode(cp);
    }
    let hoverCell = $state<number | null>(null);
</script>

<div class="island">
    <div class="island-header">
        <span class="island-title">Breakpoints: the partition that makes transitions finite</span>
        <span class="text-xs text-zinc-500">ASCII 32–127 shown; the real engine partitions all 1,114,112 codepoints</span>
    </div>
    <div class="island-body space-y-5">
        <div class="flex flex-wrap gap-2">
            {#each classes as c, k}
                <button
                    class="flex items-center gap-2 rounded-full border px-3 py-1.5 font-mono text-xs transition"
                    class:border-zinc-300={!c.on}
                    class:bg-white={!c.on}
                    class:text-zinc-400={!c.on}
                    class:border-zinc-400={c.on}
                    class:bg-zinc-50={c.on}
                    class:text-zinc-800={c.on}
                    onclick={() => (classes[k].on = !classes[k].on)}
                >
                    <span class="inline-block h-2.5 w-2.5 rounded-full" style="background: {c.on ? c.color : '#d4d4d8'}"></span>
                    {c.name}
                </button>
            {/each}
        </div>

        <div>
            <div class="mb-1 flex justify-between text-xs text-zinc-400"><span>{String.fromCharCode(MIN) === ' ' ? '␠ space' : ''} codepoint {MIN}</span><span>{MAX}</span></div>
            <div class="flex h-14 w-full overflow-hidden rounded-lg border border-zinc-300">
                {#each cells as cell, k}
                    <div
                        class="relative flex flex-1 cursor-default flex-col items-center justify-center border-r border-zinc-200 last:border-r-0 transition-all"
                        style="background: {cell.active.length === 0 ? '#fafafa' : blend(cell.active)}"
                        class:bg-teal-50={cell.active.length > 0}
                        onmouseenter={() => (hoverCell = k)}
                        onmouseleave={() => (hoverCell = null)}
                        title="[{cell.lo}, {cell.hi}]"
                    >
                        <span class="truncate px-0.5 text-center text-[10px] text-zinc-600">{cpLabel(cell.lo)}{cell.hi > cell.lo ? '–' + cpLabel(cell.hi) : ''}</span>
                        <span class="text-[9px] {cell.active.length === 0 ? 'text-zinc-300' : 'text-zinc-500'}">{cell.active.length === 0 ? '∅' : cell.active.map((c) => c.name).join('·')}</span>
                    </div>
                {/each}
            </div>
            <div class="mt-1 text-xs text-zinc-500">
                {cells.length} cells = {breakpoints.length - 1} intervals between breakpoints.
            </div>
            <div class="mt-0.5 min-h-[3.4em] text-xs leading-relaxed text-zinc-500">
                {#if hoverCell !== null && cells[hoverCell]}
                    Cell [{cells[hoverCell].lo}, {cells[hoverCell].hi}]: every class above makes the same yes/no decision for all of it — the DFA needs one transition per cell.
                {:else}
                    <span class="text-zinc-400">Hover a cell to inspect it.</span>
                {/if}
            </div>
        </div>

        <div class="rounded-lg border border-zinc-200 bg-zinc-50 p-3 text-xs leading-relaxed text-zinc-600">
            <b class="text-zinc-800">Why it matters:</b> a DFA transition table needs "for each state, for each character, where do I go?" — that's states × 1,114,112 entries. Because every class boundary is a breakpoint, all codepoints inside one cell behave identically for <i>every</i> class in the pattern, so the engine emits one transition per cell and coalesces adjacent cells that share a target into <span class="mono">Range(lo, hi)</span> rows. Toggle <span class="mono">[a-f]</span> off and watch <span class="mono">[a-z]</span> re-coalesce into one piece.
        </div>
    </div>
    <div class="island-caption">
        Mirrors TdfaCompiler.computeBreakpoints() (core/.../tdfa/TdfaCompiler.java:431): a TreeSet seeded with 0 and 0x110000, plus every lo and hi+1 of every symbol-edge class — negated classes are materialized first. Per cell, the compiler precomputes the bitset of "active" symbol edges and interns identical sets, so the expensive closure/regops pipeline runs once per distinct set, not once per cell.
    </div>
</div>

<script module lang="ts">
    export function blend(cls: { color: string }[]): string {
        // simple average blend of up to 3 colors, kept pale
        let r = 0;
        let g = 0;
        let b = 0;
        for (const c of cls) {
            const h = c.color.slice(1);
            r += parseInt(h.slice(0, 2), 16);
            g += parseInt(h.slice(2, 4), 16);
            b += parseInt(h.slice(4, 6), 16);
        }
        r = Math.round(r / cls.length / 4 + 191);
        g = Math.round(g / cls.length / 4 + 191);
        b = Math.round(b / cls.length / 4 + 191);
        return `rgb(${r},${g},${b})`;
    }
</script>
