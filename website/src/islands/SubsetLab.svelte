<script lang="ts">
    import { compileTnfa } from '../lib/thompson';
    import { subsetConstruct, type SubsetEvent, OP_SET_POS, OP_SET_NIL, OP_COPY } from '../lib/subset';
    import { layoutTnfa } from '../lib/graph';
    import { esc } from '../lib/parse';

    const presets = ['(a|b)*c', '(a)*', '(ab|a)(c|bc)'];
    let presetIdx = $state(0);
    let i = $state(0);
    let playing = $state(false);
    let timer: ReturnType<typeof setInterval> | undefined;

    let nfa = $derived(compileTnfa(presets[presetIdx]));
    let result = $derived(subsetConstruct(nfa));
    let layout = $derived(layoutTnfa(nfa));
    let ev: SubsetEvent | null = $derived(i < result.events.length ? result.events[i] : null);
    let doneState = $derived(result.events[result.events.length - 1]);
    let cells = $derived(result.cells.filter(([lo]) => lo < 128 && lo >= 32));

    // states discovered so far (for the table): a state exists from its 'new' verdict event on
    let discovered = $derived.by(() => {
        const set = new Set<number>([0]);
        for (let k = 0; k <= Math.min(i, result.events.length - 1); k++) {
            const e = result.events[k];
            if (e.type === 'cell' && e.verdict === 'new') set.add(e.target);
        }
        return set;
    });

    let curSid = $derived(ev?.type === 'pop' || ev?.type === 'cell' || ev?.type === 'final' || ev?.type === 'closure' ? ev.sid : null);
    let targetSid = $derived(ev?.type === 'cell' ? ev.target : null);
    let kernelStates = $derived.by(() => {
        if (!ev) return new Set<number>();
        if (ev.type === 'pop' || ev.type === 'closure') return new Set(ev.kernel.map((c) => c.state));
        if (ev.type === 'cell') return new Set(ev.targetKernel.map((c) => c.state));
        return new Set<number>();
    });

    function setPreset(k: number): void {
        presetIdx = k;
        reset();
    }
    function reset(): void {
        stop();
        i = 0;
    }
    function step(): void {
        if (i < result.events.length - 1) i++;
        else stop();
    }
    function back(): void {
        if (i > 0) i--;
    }
    function stop(): void {
        playing = false;
        if (timer) clearInterval(timer);
        timer = undefined;
    }
    function play(): void {
        if (playing) return stop();
        if (i >= result.events.length - 1) i = 0;
        playing = true;
        timer = setInterval(step, 750);
    }
    $effect(() => () => stop());

    const opLabel = (op: number[]): string =>
        op[0] === OP_SET_POS ? `SET t${op[1]} ← pos` : op[0] === OP_SET_NIL ? `SET t${op[1]} ← ∅` : `COPY final t${op[1]} ← work`;

    function rangeLabel(lo: number, hi: number): string {
        return lo === hi ? esc(lo) : `${esc(lo)}-${esc(hi)}`;
    }

    let logLine = $derived.by(() => {
        if (!ev) return '';
        switch (ev.type) {
            case 'start':
                return `seed: ε-closure of NFA start state → DFA state 0`;
            case 'pop':
                return `pop DFA state ${ev.sid} off the worklist`;
            case 'cell':
                return `state ${ev.sid} on [${rangeLabel(ev.lo, ev.hi)}]: step ${ev.stepped.length} config${ev.stepped.length === 1 ? '' : 's'} → ε-closure {${ev.targetKernel.map((c) => c.state).join(', ')}} ${ev.verdict === 'new' ? `→ NEW state ${ev.target}` : `→ already exists as state ${ev.target}`}${ev.ops.length ? ` · ops: ${ev.ops.map(opLabel).join(', ')}` : ''}`;
            case 'final':
                return `state ${ev.sid} accepts — bake final ops: ${ev.ops.map(opLabel).join(', ') || 'none'}`;
            case 'done':
                return `done: ${ev.stateCount} DFA states`;
        }
    });
</script>

<div class="island">
    <div class="island-header">
        <span class="island-title">Watch subset construction build a TDFA</span>
        <select class="ml-auto field !w-52" value={presetIdx} onchange={(e) => setPreset(Number((e.target as HTMLSelectElement).value))}>
            {#each presets as p, k}
                <option value={k}>{p}</option>
            {/each}
        </select>
    </div>
    <div class="island-body space-y-4">
        <div class="grid gap-4 lg:grid-cols-2">
            <div>
                <h4 class="mb-1.5 text-sm font-semibold text-zinc-800">TNFA <span class="font-normal text-zinc-400">— teal = in a kernel</span></h4>
                <div class="overflow-x-auto rounded-lg border border-zinc-200 bg-white p-2">
                    <svg viewBox="0 0 {layout.width} {layout.height}" class="min-w-[420px]">
                        <defs>
                            <marker id="sarr" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="7" markerHeight="7" orient="auto-start-reverse">
                                <path d="M 0 0 L 10 5 L 0 10 z" fill="#a1a1aa"></path>
                            </marker>
                        </defs>
                        {#each layout.edges as e}
                            <path
                                d="M {e.dx1} {e.dy1} C {e.dx1 + (e.dx2 - e.dx1) * 0.25} {e.dy1 + (e.dy2 - e.dy1) * 0.25 + (e.from === e.to ? -22 : 0)}, {e.dx1 + (e.dx2 - e.dx1) * 0.75} {e.dy1 + (e.dy2 - e.dy1) * 0.75 + (e.from === e.to ? -22 : 0)}, {e.dx2} {e.dy2}"
                                fill="none"
                                stroke="#e4e4e7"
                                stroke-width="1.1"
                                marker-end="url(#sarr)"
                            ></path>
                            {#if e.kind === 'eps' && (e.tag !== 0 || e.mask !== 0 || e.pri! > 1)}
                                <text x="{(e.dx1 + e.dx2) / 2}" y="{(e.dy1 + e.dy2) / 2 - (e.from === e.to ? 26 : 5)}" text-anchor="middle" font-size="8.5" fill="{e.tag < 0 ? '#dc2626' : e.tag > 0 ? '#0369a1' : '#c4b5fd'}">{e.label}</text>
                            {/if}
                            {#if e.kind === 'sym'}
                                <rect x="{(e.dx1 + e.dx2) / 2 - 8}" y="{(e.dy1 + e.dy2) / 2 - 15}" rx="3" width="16" height="11" fill="#fafafa" stroke="#d4d4d8"></rect>
                                <text x="{(e.dx1 + e.dx2) / 2}" y="{(e.dy1 + e.dy2) / 2 - 6.5}" text-anchor="middle" font-size="8" fill="#52525b">{e.label.length > 4 ? e.label.slice(0, 4) : e.label}</text>
                            {/if}
                        {/each}
                        {#each layout.nodes as nd (nd.id)}
                            <circle cx={nd.x} cy={nd.y} r="10" fill="{kernelStates.has(nd.id) ? '#99f6e4' : '#fafafa'}" stroke="{kernelStates.has(nd.id) ? '#0f766e' : '#d4d4d8'}" stroke-width="{kernelStates.has(nd.id) ? 2.4 : 1.4}"></circle>
                            {#if nd.id === nfa.accept}
                                <circle cx={nd.x} cy={nd.y} r="13.5" fill="none" stroke="{kernelStates.has(nd.id) ? '#0f766e' : '#d4d4d8'}"></circle>
                            {/if}
                            <text x={nd.x} y={nd.y + 3} text-anchor="middle" font-size="8.5" font-weight="600" fill="{kernelStates.has(nd.id) ? '#134e4a' : '#b1b1b8'}">{nd.id}</text>
                        {/each}
                    </svg>
                </div>
            </div>
            <div>
                <h4 class="mb-1.5 text-sm font-semibold text-zinc-800">TDFA <span class="font-normal text-zinc-400">— grows as states are interned</span></h4>
                <div class="max-h-96 overflow-auto rounded-lg border border-zinc-200">
                    <table class="w-full text-left text-xs">
                        <thead class="sticky top-0 bg-zinc-50 text-[11px] tracking-wide text-zinc-500 uppercase">
                            <tr>
                                <th class="px-2.5 py-1.5">state</th>
                                <th class="px-2.5 py-1.5">kernel (TNFA states + tag history)</th>
                                <th class="px-2.5 py-1.5">transitions</th>
                            </tr>
                        </thead>
                        <tbody>
                            {#each result.states as st}
                                {#if discovered.has(st.id)}
                                    <tr class="border-t border-zinc-100 align-top {st.id === curSid ? 'bg-amber-50' : ''} {st.id === targetSid ? 'bg-teal-50' : ''}">
                                        <td class="px-2.5 py-1.5">
                                            <span class="font-mono font-bold {st.id === curSid ? 'text-amber-700' : st.id === targetSid ? 'text-teal-700' : 'text-zinc-700'}">{st.id}</span>
                                            {#if st.accept}<span title="accepting" class="ml-0.5 text-teal-600">★</span>{/if}
                                        </td>
                                        <td class="px-2.5 py-1.5 font-mono text-[11px] text-zinc-600">
                                            {#each st.kernel as c}
                                                <span class="mr-1 inline-block rounded {c.l.length ? 'bg-sky-100 text-sky-800' : 'bg-zinc-100 text-zinc-600'} px-1">{c.state}{c.mask ? `·m${c.mask}` : ''}{c.l.length ? `·l=${c.l.map((t) => (t > 0 ? '+' : '−') + Math.abs(t)).join('')}` : ''}</span>
                                            {/each}
                                        </td>
                                        <td class="px-2.5 py-1.5">
                                            {#each st.trans as tr}
                                                <div class="font-mono text-[11px] {i > 0 && result.events[i - 1].type === 'cell' && result.events[i - 1].sid === st.id && result.events[i - 1].lo === tr.lo ? 'rounded bg-amber-100 px-1' : ''}">
                                                    {rangeLabel(tr.lo, tr.hi)} → {tr.target}
                                                    {#if tr.ops.length}<span class="text-zinc-400"> [{tr.ops.map(opLabel).join(', ')}]</span>{/if}
                                                </div>
                                            {/each}
                                            {#if st.accept && st.finalOps.length}
                                                <div class="font-mono text-[11px] text-teal-700">★ final: [{st.finalOps.map(opLabel).join(', ')}]</div>
                                            {/if}
                                        </td>
                                    </tr>
                                {/if}
                            {/each}
                        </tbody>
                    </table>
                </div>
            </div>
        </div>

        {#if ev?.type === 'cell'}
            <div class="grid gap-2 rounded-lg border border-zinc-200 bg-zinc-50 p-3 text-xs sm:grid-cols-[1fr_auto_1fr]">
                <div>
                    <div class="mb-1 font-semibold text-zinc-600">step configs</div>
                    {#each ev.stepped as s}
                        <span class="mr-1 inline-block rounded bg-white px-1.5 py-0.5 font-mono">{s.from.state} —[{rangeLabel(ev.lo, ev.hi)}]→ {s.toState} <span class="text-sky-700">h=[{s.h.map((t) => '+' + t).join('')}]</span></span>
                    {/each}
                </div>
                <div class="flex items-center justify-center text-2xl text-zinc-300">⟶</div>
                <div>
                    <div class="mb-1 font-semibold text-zinc-600">target kernel {ev.verdict === 'new' ? `(new: state ${ev.target})` : `(interned: state ${ev.target})`}</div>
                    {#each ev.targetKernel as c}
                        <span class="mr-1 inline-block rounded bg-white px-1.5 py-0.5 font-mono">{c.state}{c.mask ? `·m${c.mask}` : ''}{c.l.length ? ` <span class="text-sky-700">l=[${c.l.map((t) => '+' + t).join('')}]</span>` : ''}</span>
                    {/each}
                </div>
            </div>
        {/if}

        <div class="mono rounded-md bg-zinc-900 px-3 py-2 text-[12px] leading-relaxed text-zinc-100">
            <span class="mr-2 text-zinc-500">{i}/{result.events.length - 1}</span>
            {logLine}
        </div>

        <div class="flex items-center gap-2 border-t border-zinc-200 pt-3">
            <button class="btn" onclick={reset}>⟲</button>
            <button class="btn" onclick={back} disabled={i === 0}>◀ back</button>
            <button class="btn btn-primary" onclick={step} disabled={i >= result.events.length - 1}>step ▶</button>
            <button class="btn" onclick={play}>{playing ? '❚❚ pause' : '▷ play'}</button>
            <span class="ml-auto text-xs text-zinc-400">{doneState?.type === 'done' ? `${doneState.stateCount} states · ${cells.length}+ cells` : `cells (ASCII window): ${cells.length}`}</span>
        </div>
    </div>
    <div class="island-caption">
        The kernel signature includes each config's tag history <span class="mono">l</span> — and <span class="mono">l</span> resets to empty on every symbol step (stepOnSymbol moves it to <span class="mono">h</span>). That reset is why histories stay small and the DFA stays finite. Watch <span class="mono">(a)*</span>: state 1's kernel looks identical after every iteration, so the second arrival is interned to the <i>existing</i> state — the loop closes. This playground dedupes on the full signature (the real engine merges same-shape states with different register assignments via the map bijection + COPY ops, and applies the Pike cut in Perl mode — both are elided here for clarity; ops use one register per tag).
    </div>
</div>
