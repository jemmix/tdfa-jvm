<script lang="ts">
    // Fullscreen overlay for an entire lab: the island's body rendered at full
    // width (same state, same bindings — editing or stepping works in either
    // view), so graphs, tables and controls are visible together.
    let { open = $bindable(false), title, children } = $props();
</script>

<svelte:window onkeydown={(e) => e.key === 'Escape' && open && (open = false)}></svelte:window>

{#if open}
    <!-- svelte-ignore a11y_click_events_have_key_events, a11y_no_static_element_interactions -->
    <div
        class="fixed inset-0 z-50 flex flex-col bg-zinc-950/70 p-3 backdrop-blur-sm sm:p-6 lg:p-8"
        role="dialog"
        aria-modal="true"
        aria-label={title}
        onclick={(e) => e.target === e.currentTarget && (open = false)}
    >
        <div class="mx-auto flex min-h-0 w-full max-w-6xl flex-1 flex-col overflow-hidden rounded-xl bg-white shadow-2xl">
            <div class="flex shrink-0 items-center justify-between gap-3 border-b border-zinc-200 bg-zinc-50 px-4 py-2.5">
                <span class="truncate text-sm font-semibold text-zinc-800">{title}</span>
                <button class="btn shrink-0" onclick={() => (open = false)}>esc · close</button>
            </div>
            <div class="min-h-0 flex-1 overflow-auto p-4 sm:p-6">
                <div class="mx-auto max-w-5xl">
                    {@render children()}
                </div>
            </div>
        </div>
    </div>
{/if}
