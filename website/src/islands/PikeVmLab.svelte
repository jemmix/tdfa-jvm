<script lang="ts">
    import { compileTnfa, type Tnfa } from '../lib/thompson';
    import { runPikeVm, type Mode } from '../lib/pikevm';
    import { layoutTnfa, edgePath } from '../lib/graph';
    import GraphModal from './GraphModal.svelte';

    const presets: { pattern: string; text: string; note: string }[] = [
        { pattern: '(a|ab)(c|bc)', text: 'abc', note: 'the classic ambiguity: two ways to split "abc"' },
        { pattern: 'a|ab', text: 'ab', note: 'leftmost-first picks "a", leftmost-longest picks "ab"' },
        { pattern: '(\\w+)@(\\w+)', text: 'hi@host42', note: 'a capture-heavy everyday pattern' },
        { pattern: '(a+)+', text: 'aaa', note: 'nested greedy quantifier — catastrophic for backtracking, boring here' },
    ];
    let presetIdx = $state(0);
    let pattern = $state(presets[0].pattern);
    let text = $state(presets[0].text);
    let mode: Mode = $state('first');
    let i = $state(0);
    let playing = $state(false);
    let timer: ReturnType<typeof setInterval> | undefined;
    let expanded = $state(false);

    let nfa: { ok: true; nfa: Tnfa } | { ok: false; err: string } = $derived.by(() => {
        try {
            return { ok: true as const, nfa: compileTnfa(pattern) };
        } catch (e) {
            return { ok: false as const, err: (e as Error).message };
        }
    });
    let layout = $derived(nfa.ok ? layoutTnfa(nfa.nfa) : null);
    let trace = $derived(nfa.ok ? runPikeVm(nfa.nfa, text, mode) : null);
    let ev = $derived(trace && i < trace.events.length ? trace.events[i] : null);

    let liveStates = $derived(new Set(ev ? ev.threads.map((t) => t.state) : []));

    function setPreset(k: number): void {
        presetIdx = k;
        pattern = presets[k].pattern;
        text = presets[k].text;
        reset();
    }
    function reset(): void {
        stop();
        i = 0;
    }
    function step(): void {
        if (trace && i < trace.events.length - 1) i++;
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
        if (trace && i >= trace.events.length - 1) i = 0;
        playing = true;
        timer = setInterval(step, 900);
    }

    $effect(() => {
        return () => stop();
    });

    let groups = $derived.by(() => {
        if (!trace || !trace.match || !nfa.ok) return null;
        const out: { g: number; span: string; val: string }[] = [{ g: 0, span: `[${trace.match.start},${trace.match.end})`, val: text.slice(trace.match.start, trace.match.end) }];
        for (let g = 1; g <= nfa.nfa.groupCount; g++) {
            const s = trace.match.tags[2 * g - 2];
            const e = trace.match.tags[2 * g - 1];
            out.push({ g, span: s < 0 || e < 0 ? 'unset' : `[${s},${e})`, val: s < 0 || e < 0 ? '—' : text.slice(s, e) });
        }
        return out;
    });

    function tagCells(tags: Int32Array): string[] {
        const out: string[] = [];
        for (let t = 0; t < tags.length; t++) out.push(tags[t] < 0 ? '∅' : String(tags[t]));
        return out;
    }
    const stateColor = (s: number): string => (liveStates.has(s) ? '#0f766e' : '#d4d4d8');

    // tallest the thread table ever gets — reserved so stepping never reflows the page
    let threadsMinH = $derived.by(() => {
        if (!trace) return 64;
        const maxRows = Math.max(1, ...trace.events.map((e) => e.threads.length));
        return 34 + maxRows * 34 + 4;
    });
</script>

<svelte:window onkeydown={(e) => e.key === 'ArrowRight' && step()} />

<div class="island">
    <div class="island-header">
        <span class="island-title">Run the TNFA: threads, priorities, captures</span>
        <div class="ml-auto flex rounded-md border border-zinc-300 bg-white p-0.5 text-xs">
            <button class="rounded px-2.5 py-1 font-medium {mode === 'first' ? 'bg-teal-700 text-white' : 'text-zinc-600'}" onclick={() => { mode = 'first'; reset(); }}>leftmost-first</button>
            <button class="rounded px-2.5 py-1 font-medium {mode === 'longest' ? 'bg-teal-700 text-white' : 'text-zinc-600'}" onclick={() => { mode = 'longest'; reset(); }}>leftmost-longest</button>
        </div>
    </div>
    <div class="island-body space-y-4">
        <div class="flex flex-wrap items-end gap-3">
            <label class="min-w-52 flex-1 text-sm">
                <span class="mb-1 block font-medium text-zinc-700">Pattern</span>
                <input type="text" class="field" bind:value={pattern} oninput={reset} spellcheck="false" />
            </label>
            <label class="min-w-40 flex-1 text-sm">
                <span class="mb-1 block font-medium text-zinc-700">Input</span>
                <input type="text" class="field" bind:value={text} oninput={reset} spellcheck="false" maxlength="28" />
            </label>
            <select class="field !w-64" value={presetIdx} onchange={(e) => setPreset(Number((e.target as HTMLSelectElement).value))}>
                {#each presets as p, k}
                    <option value={k}>{p.pattern} on "{p.text}"</option>
                {/each}
            </select>
        </div>
        <p class="text-xs text-zinc-500">{presets[presetIdx]?.note ?? ''}</p>

        {#if !nfa.ok}
            <p class="mono rounded-md border border-red-300 bg-red-50 px-3 py-2 text-sm text-red-700">{nfa.err}</p>
        {:else if layout && trace}
            {#snippet graphSvg(svgClass: string, idp: string)}
                <svg viewBox="0 0 {layout.width} {layout.height}" class={svgClass}>
                    <defs>
                        <marker id="{idp}-arr" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="7" markerHeight="7" orient="auto-start-reverse">
                            <path d="M 0 0 L 10 5 L 0 10 z" fill="#71717a"></path>
                        </marker>
                        <marker id="{idp}-arr-live" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="7" markerHeight="7" orient="auto-start-reverse">
                            <path d="M 0 0 L 10 5 L 0 10 z" fill="#0f766e"></path>
                        </marker>
                    </defs>
                    {#each layout.edges as e (e.id)}
                        <path
                            d={edgePath(e.points)}
                            fill="none"
                            stroke={liveStates.has(e.from) ? '#0d9488' : '#d4d4d8'}
                            stroke-width={liveStates.has(e.from) ? 1.8 : 1.2}
                            marker-end={liveStates.has(e.from) ? `url(#${idp}-arr-live)` : `url(#${idp}-arr)`}
                        ></path>
                        {#if e.label}
                            <rect x="{e.lx - e.lw / 2}" y="{e.ly - e.lh / 2}" rx="3" width="{e.lw}" height="{e.lh}" fill="{liveStates.has(e.from) ? '#f0fdfa' : '#fafafa'}" stroke="{liveStates.has(e.from) ? '#99f6e4' : '#e4e4e7'}"></rect>
                            <text x="{e.lx}" y="{e.ly + 3.2}" text-anchor="middle" font-size="9" fill="{e.tag < 0 ? '#b91c1c' : e.tag > 0 ? '#0369a1' : e.kind === 'sym' ? '#3f3f46' : '#71717a'}">{e.label}</text>
                        {/if}
                    {/each}
                    {#each layout.nodes as nd (nd.id)}
                        <circle cx={nd.x} cy={nd.y} r="11" fill="{nd.id === nfa.nfa.accept ? (liveStates.has(nd.id) ? '#0f766e' : '#a7f3d0') : liveStates.has(nd.id) ? '#99f6e4' : '#fafafa'}" stroke={stateColor(nd.id)} stroke-width="2"></circle>
                        {#if nd.id === nfa.nfa.accept}
                            <circle cx={nd.x} cy={nd.y} r="15" fill="none" stroke={stateColor(nd.id)} stroke-width="1"></circle>
                        {/if}
                        {#if nd.id === nfa.nfa.start}
                            <path d="M {nd.x - 34} {nd.y} L {nd.x - 14} {nd.y}" stroke="#a1a1aa" stroke-width="1.4" marker-end="url(#{idp}-arr)"></path>
                            <text x="{nd.x - 34}" y="{nd.y - 6}" font-size="8.5" fill="#a1a1aa">start</text>
                        {/if}
                        <text x={nd.x} y={nd.y + 3.5} text-anchor="middle" font-size="9" font-weight="600" fill="{liveStates.has(nd.id) ? '#134e4a' : '#a1a1aa'}">{nd.id}</text>
                    {/each}
                </svg>
            {/snippet}

            {#snippet controlsRow(hint: string)}
                <div class="flex items-center gap-2 border-t border-zinc-200 pt-3">
                    <button class="btn" onclick={reset} title="reset">⟲</button>
                    <button class="btn" onclick={back} disabled={i === 0}>◀ back</button>
                    <button class="btn btn-primary" onclick={step} disabled={!trace || i >= trace.events.length - 1}>step ▶</button>
                    <button class="btn" onclick={play}>{playing ? '❚❚ pause' : '▷ play'}</button>
                    <span class="ml-auto text-xs text-zinc-400">{hint}</span>
                </div>
            {/snippet}

            <div class="relative overflow-x-auto rounded-lg border border-zinc-200 bg-white p-2">
                {@render graphSvg('min-w-[640px]', 'g1')}
                <button class="btn absolute top-2 right-2 !px-2 !py-1 text-xs" onclick={() => (expanded = true)} title="open the graph fullscreen">⤢ enlarge</button>
            </div>

            <GraphModal bind:open={expanded} title={'TNFA for ' + pattern + ' — live states highlighted, step through below'}>
                {@render graphSvg('h-full w-full', 'g2')}
                {#snippet controls()}
                    {@render controlsRow('arrow keys work too · esc closes')}
                {/snippet}
            </GraphModal>

            <!-- input ruler -->
            <div class="mono flex items-center gap-0 overflow-x-auto">
                {#each [...text] as ch, k}
                    <div class="flex w-7 shrink-0 flex-col items-center">
                        <div class="flex h-6 w-6 items-center justify-center rounded border text-sm {ev && k === ev.pos ? 'border-teal-600 bg-teal-600 text-white' : 'border-zinc-300 text-zinc-700'}">{ch}</div>
                        <span class="text-[10px] text-zinc-400">{k}</span>
                    </div>
                {/each}
                <div class="flex w-7 shrink-0 flex-col items-center">
                    <div class="flex h-6 w-6 items-center justify-center rounded border text-[10px] {ev && ev.pos === text.length ? 'border-teal-600 bg-teal-600 text-white' : 'border-zinc-300 text-zinc-400'}">end</div>
                    <span class="text-[10px] text-zinc-400">{text.length}</span>
                </div>
            </div>

            <!-- threads (fixed-height reserve: no vertical jumping between steps) -->
            <div>
                <div class="mb-1.5 flex items-center justify-between">
                    <h4 class="text-sm font-semibold text-zinc-800">Threads at position {ev?.pos ?? 0} <span class="font-normal text-zinc-400">(rank 0 = highest priority)</span></h4>
                    <span class="text-xs text-zinc-500">{trace.totalSteps} thread-steps total</span>
                </div>
                <div class="overflow-x-auto rounded-lg border border-zinc-200" style="min-height: {threadsMinH}px">
                    <table class="w-full text-sm">
                        <thead>
                            <tr class="border-b border-zinc-200 bg-zinc-50 text-left text-xs tracking-wide text-zinc-500 uppercase">
                                <th class="px-3 py-1.5">rank</th>
                                <th class="px-3 py-1.5">NFA state</th>
                                {#each Array(nfa.nfa.tagCount) as _, t}
                                    <th class="px-3 py-1.5">t{t + 1}</th>
                                {/each}
                                <th class="px-3 py-1.5"></th>
                            </tr>
                        </thead>
                        <tbody>
                            {#if ev && ev.threads.length === 0}
                                <tr><td colspan="8" class="px-3 py-1.5 text-center text-zinc-400">no live threads — the walk is over</td></tr>
                            {/if}
                            {#each ev?.threads ?? [] as t, k}
                                <tr class="border-b border-zinc-100 {k % 2 ? 'bg-zinc-50/60' : ''} {ev && ev.cutFrom !== undefined && k > ev.cutFrom ? 'opacity-40 line-through' : ''}">
                                    <td class="px-3 py-1.5 font-mono text-zinc-400">{k}</td>
                                    <td class="px-3 py-1.5 font-mono {t.state === nfa.nfa.accept ? 'font-bold text-teal-700' : 'text-zinc-800'}">{t.state}{t.state === nfa.nfa.accept ? ' ★' : ''}</td>
                                    {#each tagCells(t.tags) as v}
                                        <td class="px-3 py-1.5 font-mono {v === '∅' ? 'text-zinc-300' : 'text-sky-800'}">{v}</td>
                                    {/each}
                                    <td class="px-3 py-1.5 text-right text-xs">
                                        {#if ev && ev.cutFrom !== undefined && k === ev.cutFrom}
                                            <span class="rounded bg-teal-100 px-1.5 py-0.5 font-medium text-teal-800">accept — record &amp; cut below</span>
                                        {:else if ev && ev.cutFrom !== undefined && k > ev.cutFrom}
                                            <span class="text-red-500">killed by the cut</span>
                                        {/if}
                                    </td>
                                </tr>
                            {/each}
                        </tbody>
                    </table>
                </div>
                <div class="mt-2 min-h-[54px]">
                    {#if ev?.stopped}
                        <p class="rounded-md border border-teal-200 bg-teal-50 px-3 py-1.5 text-xs leading-snug text-teal-800">
                            Stop: the highest-ranked thread is accepting — nothing above it can extend the match, so the walk ends now.
                        </p>
                    {/if}
                </div>
            </div>

            <!-- result (reserve keeps controls pinned while stepping) -->
            <div class="min-h-[86px]">
                {#if groups}
                    <div class="rounded-lg border border-teal-200 bg-teal-50/50 p-3">
                        <div class="mb-1.5 text-sm font-semibold text-teal-900">{mode === 'first' ? 'leftmost-first' : 'leftmost-longest'} result</div>
                        <div class="flex flex-wrap gap-2">
                            {#each groups as g}
                                <span class="rounded-md border border-teal-200 bg-white px-2.5 py-1 font-mono text-xs text-teal-900">
                                    {g.g === 0 ? 'whole' : `group ${g.g}`}: <b>{g.val === '' ? "''" : g.val}</b> <span class="text-zinc-400">{g.span}</span>
                                </span>
                            {/each}
                        </div>
                    </div>
                {:else if trace}
                    <div class="rounded-lg border border-zinc-200 bg-zinc-50 p-3 text-sm text-zinc-600">no match from position 0 (the real engine would slide the start — that's the search ladder on the Execution page)</div>
                {/if}
            </div>

            <!-- controls -->
            {@render controlsRow('arrow keys work')}
        {/if}
    </div>
    <div class="island-caption">
        Each row is a thread: an NFA state plus a copy of the tag values. The list is kept in priority order — the order the ε-closure DFS first reached each state. Teal nodes in the graph are live. In leftmost-first mode, the moment an accepting thread is the first alive accept, every thread ranked below it is cut (shown struck through): those paths can never influence the answer. Switch modes and re-run <span class="mono">a|ab</span> on "ab" to feel the difference. This playground runs anchored at position 0; its longest mode keeps the longest overall match (the engine's POSIX submatch resolution uses the ntag machinery described above).
    </div>
</div>
