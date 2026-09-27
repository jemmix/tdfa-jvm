<script lang="ts">
    import { parse } from '../lib/parse';
    import { compileBacktracker, runBacktracker } from '../lib/backtracker';
    import LabModal from './LabModal.svelte';

    let expanded = $state(false);

    let pattern = $state('(a+)+b');
    let n = $state(18);
    let appendB = $state(false);

    const presets = ['(a+)+b', '(a|aa)+b', '(a|a)*b', '(a*)*c'];

    let input = $derived('a'.repeat(n) + (appendB ? 'b' : ''));
    let error = $derived.by(() => {
        try {
            parse(pattern);
            return null;
        } catch (e) {
            return (e as Error).message;
        }
    });
    let bt = $derived.by(() => {
        if (error) return null;
        try {
            const prog = compileBacktracker(parse(pattern).ast);
            return runBacktracker(prog, input, 5_000_000);
        } catch {
            return null;
        }
    });
    let dfaSteps = $derived(input.length + 1);

    function fmt(v: number): string {
        return v.toLocaleString('en-US');
    }

    let maxBar = $derived(Math.max(bt?.steps ?? 0, dfaSteps, 1000));
</script>

{#snippet labBody()}
    <div class="space-y-5">
        <div class="flex flex-wrap items-end gap-4">
            <label class="text-sm">
                <span class="mb-1 block font-medium text-zinc-700">Pattern</span>
                <select class="field !w-44" bind:value={pattern}>
                    {#each presets as p}
                        <option value={p}>{p}</option>
                    {/each}
                </select>
            </label>
            <label class="text-sm">
                <span class="mb-1 block font-medium text-zinc-700">Input length (a's)</span>
                <input type="range" min="4" max="30" bind:value={n} class="block w-56 accent-teal-700" />
                <span class="mono text-zinc-500">n = {n}</span>
            </label>
            <label class="flex items-center gap-2 pb-1.5 text-sm font-medium text-zinc-700">
                <input type="checkbox" bind:checked={appendB} class="accent-teal-700" />
                append the matching terminator
            </label>
        </div>

        {#if error}
            <p class="mono rounded-md border border-amber-300 bg-amber-50 px-3 py-2 text-sm text-amber-800">{error}</p>
        {:else if bt}
            <div class="grid gap-4 sm:grid-cols-2">
                <div class="rounded-lg border border-zinc-200 p-4">
                    <div class="mb-1 flex items-baseline justify-between">
                        <span class="text-sm font-semibold text-zinc-800">Backtracking engine</span>
                        <span class="text-xs {bt.aborted ? 'text-red-600 font-semibold' : 'text-zinc-500'}">
                            {bt.aborted ? 'aborted at cap' : 'finished'}
                        </span>
                    </div>
                    <div class="mono text-2xl font-bold {bt.aborted ? 'text-red-600' : 'text-zinc-900'}">
                        {bt.aborted ? '> ' : ''}{fmt(bt.steps)} <span class="text-sm font-normal text-zinc-500">steps</span>
                    </div>
                    <div class="mt-2 h-2.5 rounded-full bg-zinc-100">
                        <div class="h-2.5 rounded-full {bt.aborted ? 'bg-red-500' : 'bg-amber-500'}" style="width: {Math.max(2, (Math.log10(bt.steps) / Math.log10(maxBar)) * 100)}%"></div>
                    </div>
                    <div class="mt-2 text-xs text-zinc-500">
                        result: {bt.aborted ? 'unknown — step cap hit' : bt.matched ? 'MATCH' : 'no match'}
                    </div>
                </div>
                <div class="rounded-lg border border-teal-200 bg-teal-50/40 p-4">
                    <div class="mb-1 flex items-baseline justify-between">
                        <span class="text-sm font-semibold text-zinc-800">tdfa-jvm-style walk</span>
                        <span class="text-xs text-teal-700">finished</span>
                    </div>
                    <div class="mono text-2xl font-bold text-teal-800">
                        {fmt(dfaSteps)} <span class="text-sm font-normal text-zinc-500">steps</span>
                    </div>
                    <div class="mt-2 h-2.5 rounded-full bg-zinc-100">
                        <div class="h-2.5 rounded-full bg-teal-600" style="width: {Math.max(2, (Math.log10(dfaSteps) / Math.log10(maxBar)) * 100)}%"></div>
                    </div>
                    <div class="mt-2 text-xs text-zinc-500">
                        result: {appendB ? 'MATCH' : 'no match'} — one array lookup per character, always
                    </div>
                </div>
            </div>
            <div class="mono truncate rounded-md bg-zinc-900 px-3 py-2 text-sm text-zinc-100">
                input: <span class="text-teal-300">"{input.slice(0, 60)}"{input.length > 60 ? '…' : ''}</span>
            </div>
        {/if}
    </div>
{/snippet}

<div class="island">
    <div class="island-header">
        <span class="island-title">Backtracking vs. DFA, step by step</span>
        <span class="ml-auto text-xs text-zinc-500">naive backtracker (left) · tdfa-jvm-style walk (right)</span>
        <button class="btn !py-1 text-xs" onclick={() => (expanded = true)} title="open this lab fullscreen">⤢ expand</button>
    </div>
    <div class="island-body">
        {@render labBody()}
    </div>
    <div class="island-caption">
        The backtracker re-tries every way of splitting the a's between the nested quantifiers — the count roughly doubles (or Fibonaccis) with each added character. The DFA walk consumes one character per step because all the "ways to split" live in the DFA state, not in a choice stack. Bars are log-scaled; the backtracking cap here is 5,000,000 steps (the real engine has no such cliff to hit).
    </div>
</div>

<LabModal bind:open={expanded} title="Backtracking vs. DFA, step by step">
    {@render labBody()}
</LabModal>
