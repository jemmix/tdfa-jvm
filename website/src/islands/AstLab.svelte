<script lang="ts">
    import { parse, type Ast } from '../lib/parse';
    import LabModal from './LabModal.svelte';

    let expanded = $state(false);

    let src = $state('(\\w+)@(\\w+)');
    const presets = ['(\\w+)@(\\w+)', '(a|b)*c', '((a{2}){3}){2}', '(?:ab|cd)+?', 'back\\b'];

    let result = $derived.by(() => {
        try {
            return { ok: true as const, res: parse(src) };
        } catch (e) {
            return { ok: false as const, err: (e as Error).message };
        }
    });

    function label(a: Ast): string {
        switch (a.kind) {
            case 'empty':
                return 'ε (empty)';
            case 'symbol': {
                const c = String.fromCharCode(a.c);
                return `'${c === '\\' ? '\\\\' : c}'`;
            }
            case 'class': {
                const parts = a.ranges.slice(0, 3).map(([lo, hi]) =>
                    lo === hi ? esc(lo) : hi === lo + 1 ? esc(lo) + esc(hi) : `${esc(lo)}-${esc(hi)}`,
                );
                const inner = parts.join('') + (a.ranges.length > 3 ? '…' : '');
                return a.negated ? `[^${inner}]` : `[${inner}]`;
            }
            case 'tag':
                return `tag ${a.open ? '' : '/'}${a.group} · t${a.tag}`;
            case 'concat':
                return 'concat';
            case 'alt':
                return 'alt';
            case 'repeat': {
                const q = a.max === Infinity ? (a.min === 0 ? '*' : '+') : a.min === 0 && a.max === 1 ? '?' : `{${a.min},${a.max === Infinity ? '' : a.max}}`;
                return `repeat ${q}${a.greedy ? '' : ' (lazy)'}`;
            }
            case 'startAnchor':
                return "'\\A' (start)";
            case 'endAnchor':
                return "'\\z' (end)";
            case 'wordBoundary':
                return "'\\b'";
            case 'noWordBoundary':
                return "'\\B'";
        }
    }
    function esc(cp: number): string {
        if (cp === 10) return '\\n';
        if (cp >= 48 && cp <= 57) return String.fromCharCode(cp);
        if (cp >= 65 && cp <= 90) return String.fromCharCode(cp);
        if (cp >= 97 && cp <= 122) return String.fromCharCode(cp);
        return cp >= 33 && cp <= 126 ? String.fromCharCode(cp) : `U+${cp.toString(16)}`;
    }
    function kids(a: Ast): Ast[] {
        if (a.kind === 'concat' || a.kind === 'alt') return a.children;
        if (a.kind === 'repeat') return [a.body];
        return [];
    }

    function flatten(a: Ast, depth: number, out: { node: Ast; depth: number; last: boolean[] }[], path: boolean[] = []): void {
        out.push({ node: a, depth, last: path });
        const cs = kids(a);
        cs.forEach((c, i) => flatten(c, depth + 1, out, [...path, i === cs.length - 1]));
    }
    let rows = $derived(result.ok ? (() => { const o: { node: Ast; depth: number; last: boolean[] }[] = []; flatten(result.res.ast, 0, o); return o; })() : []);
</script>

{#snippet labBody()}
    <div class="space-y-4">
        <div class="flex flex-wrap items-end gap-3">
            <label class="min-w-56 flex-1 text-sm">
                <span class="mb-1 block font-medium text-zinc-700">Pattern</span>
                <input type="text" class="field" bind:value={src} spellcheck="false" />
            </label>
            <select class="field !w-56" bind:value={src}>
                {#each presets as p}
                    <option value={p}>{p}</option>
                {/each}
            </select>
        </div>

        {#if result.ok}
            <div class="flex flex-wrap gap-x-6 gap-y-1 rounded-md border border-zinc-200 bg-zinc-50 px-3 py-2 text-xs text-zinc-600">
                <span>groups: <b>{result.res.groupCount}</b></span>
                <span>tags: <b>{result.res.tagCount}</b></span>
                <span>group g → open tag <b>2g−1</b>, close tag <b>2g</b></span>
                {#if result.res.namedGroups.size > 0}
                    <span>named: {[...result.res.namedGroups].map(([k, v]) => `${k}→g${v}`).join(', ')}</span>
                {/if}
            </div>
            <div class="overflow-x-auto rounded-lg border border-zinc-200 bg-white p-4">
                <div class="mono leading-7 whitespace-pre">
                    {#each rows as r, i}
                        <div class="flex items-baseline">
                            <span class="text-zinc-300">{r.last.map((l) => (l ? '   ' : '│  ')).join('')}{i === 0 ? '' : r.last[r.last.length - 1] ? '└─ ' : '├─ '}</span>
                            <span class={(r.node.kind === 'tag' ? 'rounded bg-sky-100 px-1 text-sky-800' : r.node.kind === 'concat' || r.node.kind === 'alt' || r.node.kind === 'repeat' ? 'font-semibold text-zinc-800' : 'text-zinc-700') + ' whitespace-pre'}>
                                {label(r.node)}
                            </span>
                        </div>
                    {/each}
                </div>
            </div>
        {:else}
            <p class="mono rounded-md border border-red-300 bg-red-50 px-3 py-2 text-sm text-red-700">PatternSyntaxException: {result.err}</p>
        {/if}
    </div>
{/snippet}

<div class="island">
    <div class="island-header">
        <span class="island-title">Pattern → AST</span>
        <span class="ml-auto text-xs text-zinc-500">capturing groups desugar to tag · body · tag</span>
        <button class="btn !py-1 text-xs" onclick={() => (expanded = true)} title="open this lab fullscreen">⤢ expand</button>
    </div>
    <div class="island-body">
        {@render labBody()}
    </div>
    <div class="island-caption">
        This mini parser follows the real one's shapes (Parser.java): precedence alternation → concatenation → repetition → atom; a capturing group is not a node — closeGroup rewrites it to <span class="mono">concat[tag 2g−1, body, tag 2g]</span>. Tags are numbered at the <span class="mono">(</span>, so an outer group's tags are lower numbers than an inner group's. Try <span class="mono">(a)\1</span> or <span class="mono">(?=x)</span> to see a compile-time rejection.
    </div>
</div>

<LabModal bind:open={expanded} title="Pattern → AST">
    {@render labBody()}
</LabModal>
