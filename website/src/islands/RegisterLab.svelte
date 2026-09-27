<script lang="ts">
    import LabModal from './LabModal.svelte';

    let expanded = $state(false);

    // Hand-compiled TDFA for ([a-z]+)@([a-z]+) — a faithful simplification of
    // what TdfaCompiler emits for this shape (one working register per tag,
    // final registers f1..f4 written by the accept-time φ ops).

    interface Step {
        pos: number;
        state: number;
        prev: number;
        char: string;
        ops: { text: string; regs: number[] }[]; // ops executed at this transition
        acceptCheck?: { accepted: boolean; note: string };
    }

    const LETTER = /[a-z]/;

    let input = $state('ann@server');
    let i = $state(0);
    let playing = $state(false);
    let timer: ReturnType<typeof setInterval> | undefined;

    let run = $derived.by(() => {
        const steps: Step[] = [];
        const regs = [-1, -1, -1, -1]; // r_t1 r_t2 r_t3 r_t4 (working)
        const finals = [-1, -1, -1, -1]; // f1..f4
        let state = 0;
        let matched = false;
        let matchEnd = -1;
        let lastFinals: number[] | null = null;
        const transitions: { from: number; test: (c: string) => boolean; to: number; ops: { text: string; regs: number[] }[] }[] = [
            { from: 0, test: (c) => LETTER.test(c), to: 1, ops: [{ text: 'SET_POS r_t1 ← pos', regs: [0] }] },
            { from: 1, test: (c) => LETTER.test(c), to: 1, ops: [] },
            { from: 1, test: (c) => c === '@', to: 2, ops: [{ text: 'SET_POS r_t2 ← pos', regs: [1] }] },
            { from: 2, test: (c) => LETTER.test(c), to: 3, ops: [{ text: 'SET_POS r_t3 ← pos', regs: [2] }] },
            { from: 3, test: (c) => LETTER.test(c), to: 3, ops: [] },
        ];
        for (let pos = 0; pos <= input.length; pos++) {
            const isAccept = state === 3;
            let accepted = false;
            if (isAccept) {
                // φ final ops run eagerly at accept-record time, with pos = match end
                const f = [...regs];
                f[3] = pos; // SET_POS f_t4 ← pos (t4 is fresh in the accepting closure)
                lastFinals = f;
                matchEnd = pos;
                matched = true;
                accepted = true;
            }
            if (pos === input.length) {
                steps.push({ pos, state, prev: state, char: '␃', ops: [], acceptCheck: { accepted, note: accepted ? 'accept recorded' : state === 3 ? '' : 'dead — no match' } });
                break;
            }
            const ch = input[pos];
            const tr = transitions.find((t) => t.from === state && t.test(ch));
            if (!tr) {
                steps.push({ pos, state, prev: state, char: ch, ops: [], acceptCheck: { accepted, note: accepted ? 'accept recorded' : `no transition on '${ch}' — the walk dies` } });
                break;
            }
            for (const op of tr.ops) {
                for (const r of op.regs) regs[r] = pos;
            }
            steps.push({ pos, state, prev: state, char: ch, ops: tr.ops, acceptCheck: isAccept ? { accepted, note: 'accept recorded (φ ran)' } : undefined });
            state = tr.to;
        }
        return { steps, regs, finals: lastFinals, matched, matchEnd };
    });

    let upto = $derived.by(() => {
        // register file state after `i` steps — replay
        const regs = [-1, -1, -1, -1];
        for (let k = 0; k < i && k < run.steps.length; k++) {
            for (const op of run.steps[k].ops) for (const r of op.regs) regs[r] = run.steps[k].pos;
        }
        return regs;
    });

    let step = $derived(run.steps[Math.min(i, run.steps.length - 1)]);

    let shownFinals = $derived(run.matched && i >= run.steps.length - 1 ? run.finals : null);

    let groups = $derived.by(() => {
        const f = shownFinals;
        if (!f) return null;
        const g1s = f[0];
        const g1e = f[1];
        const g2s = f[2];
        const g2e = f[3];
        return [
            { name: 'group 1', span: `[${g1s},${g1e})`, val: input.slice(g1s, g1e) },
            { name: 'group 2', span: `[${g2s},${g2e})`, val: input.slice(g2s, g2e) },
        ];
    });

    function reset(): void {
        stop();
        i = 0;
    }
    function stepFn(): void {
        if (i < run.steps.length - 1) i++;
        else stop();
    }
    function stop(): void {
        playing = false;
        if (timer) clearInterval(timer);
        timer = undefined;
    }
    function play(): void {
        if (playing) return stop();
        if (i >= run.steps.length - 1) i = 0;
        playing = true;
        timer = setInterval(stepFn, 700);
    }
    $effect(() => () => stop());

    const stateNames = ['S0 (start)', 'S1 (in group 1)', 'S2 (saw @)', 'S3 (in group 2) ★'];
    const regNames = ['r_t1', 'r_t2', 'r_t3', 'r_t4'];
    const fNames = ['f_t1', 'f_t2', 'f_t3', 'f_t4'];
</script>

{#snippet labBody()}
    <div class="space-y-4">
        <div class="flex flex-wrap items-end gap-3">
            <label class="min-w-56 flex-1 text-sm">
                <span class="mb-1 block font-medium text-zinc-700">Input (letters and one @)</span>
                <input type="text" class="field" bind:value={input} oninput={reset} spellcheck="false" maxlength="20" />
            </label>
            <div class="flex gap-2 pb-0.5">
                <button class="btn" onclick={reset}>⟲</button>
                <button class="btn" onclick={() => i > 0 && i--} disabled={i === 0}>◀</button>
                <button class="btn btn-primary" onclick={stepFn} disabled={i >= run.steps.length - 1}>step ▶</button>
                <button class="btn" onclick={play}>{playing ? '❚❚' : '▷'}</button>
            </div>
        </div>

        <div class="overflow-x-auto">
            <div class="flex min-w-max items-end gap-px">
                {#each [...input] as ch, k}
                    <div class="flex w-8 flex-col items-center gap-1">
                        {#if step && k === step.pos && i < run.steps.length}
                            <div class="h-1.5 w-6 rounded-full bg-teal-600"></div>
                        {:else}
                            <div class="h-1.5 w-6"></div>
                        {/if}
                        <div class="flex h-7 w-7 items-center justify-center rounded border font-mono text-sm {step && k === step.pos && k < run.steps.length - 1 ? 'border-teal-600 bg-teal-600 text-white' : k < (step?.pos ?? 0) ? 'border-zinc-300 bg-zinc-100 text-zinc-500' : 'border-zinc-300 text-zinc-700'}">{ch}</div>
                        <span class="text-[10px] text-zinc-400">{k}</span>
                    </div>
                {/each}
            </div>
        </div>

        <div class="grid gap-4 md:grid-cols-2">
            <div class="rounded-lg border border-zinc-200 p-3">
                <div class="mb-2 text-sm font-semibold text-zinc-800">Current step</div>
                <div class="mono min-h-[132px] space-y-1 text-[13px] text-zinc-700">
                    <div>consuming <span class="rounded bg-zinc-100 px-1">'{step?.char}'</span> at pos {step?.pos}</div>
                    <div>state: <span class="font-bold">{stateNames[step?.state ?? 0]}</span></div>
                    <div class="min-h-[52px]">
                        {#if step?.ops.length}
                            <div class="rounded bg-sky-50 p-1.5">
                                {#each step.ops as op}
                                    <div class="text-sky-800">{op.text}</div>
                                {/each}
                            </div>
                        {:else if step && step.prev === step.state}
                            <div class="text-zinc-400">no ops — loop transition</div>
                        {/if}
                    </div>
                    <div class="min-h-[52px]">
                        {#if step?.acceptCheck?.accepted}
                            <div class="rounded bg-teal-50 p-1.5 text-teal-800">★ accepting: φ ops copy r_t1..r_t3 → final and SET f_t4 ← {step.pos}</div>
                        {:else if step?.acceptCheck && !step.acceptCheck.accepted && step.acceptCheck.note}
                            <div class="rounded bg-red-50 p-1.5 text-red-700">{step.acceptCheck.note}</div>
                        {/if}
                    </div>
                </div>
            </div>
            <div class="rounded-lg border border-zinc-200 p-3">
                <div class="mb-2 text-sm font-semibold text-zinc-800">Register file</div>
                <table class="w-full text-sm">
                    <thead>
                        <tr class="text-left text-[11px] tracking-wide text-zinc-400 uppercase">
                            <th class="pb-1">working</th>
                            {#each regNames as rn}
                                <th class="pb-1 text-center">{rn}</th>
                            {/each}
                        </tr>
                    </thead>
                    <tbody>
                        <tr class="mono text-center">
                            <td class="text-left text-xs text-zinc-400">value</td>
                            {#each upto as v, k}
                                <td class="rounded border {v >= 0 ? 'border-sky-200 bg-sky-50 text-sky-900' : 'border-zinc-200 text-zinc-300'} m-0.5">{v < 0 ? '∅' : v}</td>
                            {/each}
                        </tr>
                    </tbody>
                    <thead>
                        <tr class="text-left text-[11px] tracking-wide text-zinc-400 uppercase">
                            <th class="pb-1 pt-3">final</th>
                            {#each fNames as fn}
                                <th class="pb-1 pt-3 text-center">{fn}</th>
                            {/each}
                        </tr>
                    </thead>
                    <tbody>
                        <tr class="mono text-center">
                            <td class="text-left text-xs text-zinc-400">value</td>
                            {#each (shownFinals ?? [-1, -1, -1, -1]) as v}
                                <td class="rounded border {v >= 0 ? 'border-teal-200 bg-teal-50 text-teal-900' : 'border-zinc-200 text-zinc-300'} m-0.5">{v < 0 ? '∅' : v}</td>
                            {/each}
                        </tr>
                    </tbody>
                 </table>
                 <div class="mt-3 min-h-[64px] border-t border-zinc-200 pt-2.5">
                     {#if groups}
                         <div class="flex flex-wrap gap-2">
                             {#each groups as g}
                                 <span class="rounded-md border border-teal-200 bg-teal-50 px-2 py-0.5 font-mono text-xs text-teal-900">{g.name}: <b>{g.val}</b> <span class="text-zinc-400">{g.span}</span></span>
                             {/each}
                         </div>
                     {:else}
                         <span class="text-xs text-zinc-300">groups appear here when the walk accepts</span>
                     {/if}
                 </div>
             </div>
         </div>
    </div>
{/snippet}

<div class="island">
    <div class="island-header">
        <span class="island-title">Run a TDFA: one register file, no backtracking</span>
        <span class="ml-auto text-xs text-zinc-500">pattern <span class="mono">([a-z]+)@([a-z]+)</span>, anchored walk</span>
        <button class="btn !py-1 text-xs" onclick={() => (expanded = true)} title="open this lab fullscreen">⤢ expand</button>
    </div>
    <div class="island-body">
        {@render labBody()}
    </div>
    <div class="island-caption">
        Everything the engine remembers during a match is this one flat <span class="mono">int[]</span>: working registers (written by transition ops as characters are consumed) and final registers (written by the accept-time φ ops). ∅ is −1 — an unset group just never got written. This hand-compiled example uses one register per tag; the real engine's regopt pass shares registers with non-overlapping lifetimes and reconstructs "fixed" tags arithmetically (e.g. the close tag of <span class="mono">(abc)</span> is just open+3 — no register at all).
    </div>
</div>

<LabModal bind:open={expanded} title="Run a TDFA — one register file, no backtracking">
    {@render labBody()}
</LabModal>
