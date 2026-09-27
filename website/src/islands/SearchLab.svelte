<script lang="ts">
    import LabModal from './LabModal.svelte';

    let expanded = $state(false);

    import { compileTnfa, sortedEps, symEdgesOf, type Tnfa } from '../lib/thompson';
    import { runPikeVm } from '../lib/pikevm';
    import { classMatches } from '../lib/parse';

    const presets = [
        { pattern: 'ab', text: 'xxabyabzz' },
        { pattern: '[0-9]+px', text: 'w=64px; h=12px' },
        { pattern: '(a|b)+c', text: 'abbabc' },
    ];
    let presetIdx = $state(0);
    let text = $state(presets[0].text);
    let i = $state(0); // positions revealed
    let playing = $state(false);
    let timer: ReturnType<typeof setInterval> | undefined;

    let built = $derived.by(() => {
        try {
            return { ok: true as const, nfa: compileTnfa(presets[presetIdx].pattern) };
        } catch (e) {
            return { ok: false as const, err: (e as Error).message };
        }
    });

    interface Row {
        pos: number;
        states: number[];
        rowId: number | null; // interned id if this content was seen before
        cached: boolean;
        kill: boolean;
        trigger: boolean;
        W: number;
    }

    let sim = $derived.by(() => {
        if (!built.ok) return null;
        const nfa = built.nfa;
        const rows: Row[] = [];
        const ids = new Map<string, number>();
        const closure = (states: Set<number>): Set<number> => {
            const out = new Set<number>();
            const stack = [...states];
            while (stack.length) {
                const s = stack.pop()!;
                if (out.has(s)) continue;
                out.add(s);
                for (const e of sortedEps(nfa, s)) stack.push(e.to);
            }
            return out;
        };
        let current = closure(new Set([nfa.start]));
        let W = 0;
        let match: { start: number; end: number } | null = null;
        for (let pos = 0; pos < text.length; pos++) {
            const c = text.charCodeAt(pos);
            const stepped = new Set<number>();
            for (const s of current) {
                for (const se of symEdgesOf(nfa, s)) {
                    if (classMatches({ ranges: se.ranges, negated: se.negated }, c)) stepped.add(se.to);
                }
            }
            const kill = stepped.size === 0;
            if (kill) {
                // every thread died: the next row is the pure seed, and the window base W can jump past here
                current = closure(new Set([nfa.start]));
                W = pos + 1;
            } else {
                stepped.add(nfa.start); // the self-loop re-seed: unanchored search's implicit .*? prefix
                current = closure(stepped);
            }
            const key = [...current].sort((a, b) => a - b).join(',');
            const cached = ids.has(key);
            const rowId = cached ? ids.get(key)! : ids.size;
            if (!cached) ids.set(key, rowId);
            const trigger = current.has(nfa.accept);
            rows.push({ pos, states: [...current].sort((a, b) => a - b), rowId, cached, kill, trigger, W });
            if (trigger) {
                // verify with the exact anchored walk from W (the real engine's extract walk)
                const t = runPikeVm(nfa, text, 'first', W);
                if (t.match) {
                    match = { start: t.match.start, end: t.match.end };
                    break;
                }
            }
        }
        return { rows, match, rowIds: ids.size, nfa };
    });

    let visibleRows = $derived(sim ? sim.rows.slice(0, Math.min(i, sim.rows.length)) : []);

    function setPreset(k: number): void {
        presetIdx = k;
        text = presets[k].text;
        reset();
    }
    function reset(): void {
        stop();
        i = 0;
    }
    function step(): void {
        if (sim && i < sim.rows.length) i++;
        else stop();
    }
    function stop(): void {
        playing = false;
        if (timer) clearInterval(timer);
        timer = undefined;
    }
    function play(): void {
        if (playing) return stop();
        if (!sim || i >= sim.rows.length) i = 0;
        playing = true;
        timer = setInterval(step, 650);
    }
    $effect(() => () => stop());

    let lastRow = $derived(visibleRows.length ? visibleRows[visibleRows.length - 1] : null);
</script>

{#snippet labBody()}
    <div class="space-y-4">
        <div class="flex flex-wrap items-end gap-3">
            <label class="min-w-48 flex-1 text-sm">
                <span class="mb-1 block font-medium text-zinc-700">Input</span>
                <input type="text" class="field" bind:value={text} oninput={reset} spellcheck="false" maxlength="40" />
            </label>
            <select class="field !w-40" value={presetIdx} onchange={(e) => setPreset(Number((e.target as HTMLSelectElement).value))}>
                {#each presets as p, k}
                    <option value={k}>/{p.pattern}/</option>
                {/each}
            </select>
            <div class="flex gap-2 pb-0.5">
                <button class="btn" onclick={reset}>⟲</button>
                <button class="btn" onclick={() => i > 0 && i--} disabled={i === 0}>◀</button>
                <button class="btn btn-primary" onclick={step} disabled={!sim || i >= sim.rows.length}>step ▶</button>
                <button class="btn" onclick={play}>{playing ? '❚❚' : '▷'}</button>
            </div>
        </div>

        {#if sim}
            <div class="overflow-x-auto pb-1">
                <div class="flex min-w-max items-start">
                    {#each [...text] as ch, k}
                        {@const row = visibleRows.find((r) => r.pos === k)}
                        <div class="flex w-11 flex-col items-center gap-1">
                            <div
                                class="flex h-7 w-7 items-center justify-center rounded border font-mono text-sm
                                {row ? (row.trigger ? 'border-teal-600 bg-teal-600 text-white' : row.kill ? 'border-red-400 bg-red-50 text-red-500' : 'border-zinc-300 bg-white text-zinc-700') : 'border-zinc-200 text-zinc-300'}"
                            >
                                {ch}
                            </div>
                            <div class="flex h-24 w-10 flex-col justify-center gap-0.5 rounded border {row ? (row.trigger ? 'border-teal-300 bg-teal-50' : row.kill ? 'border-red-200 bg-red-50/50' : row.cached ? 'border-zinc-200 bg-zinc-50' : 'border-sky-200 bg-sky-50/60') : 'border-transparent'} p-0.5">
                                {#if row}
                                    {#if row.kill}
                                        <span class="text-center text-[10px] leading-tight text-red-500 font-semibold">✗ kill</span>
                                        <span class="text-center text-[9px] leading-tight text-red-400">W→{row.W}</span>
                                    {:else if row.cached}
                                        <span class="text-center text-[10px] leading-tight text-zinc-500 font-mono">row {row.rowId}</span>
                                        <span class="text-center text-[9px] leading-tight text-zinc-400">cached</span>
                                    {:else}
                                        <span class="text-center text-[10px] leading-tight text-sky-800 font-mono">row {row.rowId}</span>
                                        <span class="text-center text-[9px] leading-tight text-zinc-500">{row.states.slice(0, 6).join(' ')}</span>
                                    {/if}
                                {:else}
                                    <span class="text-center text-[10px] text-zinc-300">·</span>
                                {/if}
                            </div>
                            <span class="text-[10px] {sim.match && k >= sim.match.start && k < sim.match.end ? 'rounded bg-teal-600 px-1 text-white' : 'text-zinc-400'}">{k}</span>
                        </div>
                    {/each}
                </div>
            </div>

            <div class="grid gap-3 text-xs sm:grid-cols-3">
                <div class="rounded-md border border-sky-200 bg-sky-50/60 p-2.5">
                    <b class="text-sky-900">re-seed:</b> <span class="text-zinc-600">every step ORs the start state back into the live set — that's the <span class="mono">.*?</span> prefix of unanchored search, as one self-loop instead of restart loops.</span>
                </div>
                <div class="rounded-md border border-zinc-200 bg-zinc-50 p-2.5">
                    <b class="text-zinc-800">interning:</b> <span class="text-zinc-600">a row content seen before gets an id — those ids are the SearchDfa's states, and repeated rows become one array load.</span>
                </div>
                <div class="rounded-md border border-red-200 bg-red-50/60 p-2.5">
                    <b class="text-red-700">kill points:</b> <span class="text-zinc-600">when every thread dies, no match can start before the next position — the window base W jumps forward instead of re-scanning.</span>
                </div>
            </div>

            <!-- result (fixed reserve so the trigger box appearing doesn't shift anything) -->
            <div class="min-h-[64px]">
                {#if sim.match}
                    <div class="rounded-lg border border-teal-200 bg-teal-50/50 p-3 text-sm text-teal-900">
                        <span class="font-semibold">trigger at {sim.rows[sim.rows.length - 1].pos} → verified:</span>
                        match <span class="mono">[{sim.match.start},{sim.match.end})</span> = "<b>{text.slice(sim.match.start, sim.match.end)}</b>"
                        <span class="text-xs text-teal-700"> — the trigger row contains the accept state, so the engine runs one exact anchored walk from W (a few array lookups per character) to pin the match and captures.</span>
                    </div>
                {:else if i >= sim.rows.length && sim.rows.length > 0}
                    <div class="rounded-lg border border-zinc-200 bg-zinc-50 p-3 text-sm text-zinc-600">scanned the whole input — no match.</div>
                {:else}
                    <div class="rounded-lg border border-dashed border-zinc-200 p-3 text-sm text-zinc-300">scanning… a trigger fires when a row turns green.</div>
                {/if}
            </div>

            {#if lastRow && lastRow.trigger}
                <p class="text-xs text-zinc-500">Green = the live set contains the accept state: a match may end here (the scan over-approximates — masks are ignored — so a trigger is verified by the exact walk; it can fire early, never late).</p>
            {/if}
        {/if}
    </div>
{/snippet}

<div class="island">
    <div class="island-header">
        <span class="island-title">Unanchored search without restarts: the live-set scan</span>
        <span class="ml-auto text-xs text-zinc-500">re-seed · interning · kill points · trigger</span>
        <button class="btn !py-1 text-xs" onclick={() => (expanded = true)} title="open this lab fullscreen">⤢ expand</button>
    </div>
    <div class="island-body">
        {@render labBody()}
    </div>
    <div class="island-caption">
        This is the ORIGIN_SIM/TRIGGER tier of the real ladder (TdfaRunner.multiStateLeftmostStart / triggerScan over SearchDfa, core/.../tdfa/TdfaRunner.java). The real SearchDfa memoizes transitions as de-duplicated 512-codepoint blocks, caps itself against the runtime memory budget (default 16 MiB split 4:3:1 between rows, blocks and the walk memo), and stays thread-safe through immutable row snapshots. Below a 2048-codepoint window the scan runs unmemoized.
    </div>
</div>

<LabModal bind:open={expanded} title="Unanchored search — the live-set scan">
    {@render labBody()}
</LabModal>
