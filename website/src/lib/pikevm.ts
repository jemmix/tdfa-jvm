// Mini Pike VM over a mini TNFA — mirrors how tdfa-jvm's ε-closure resolves
// ambiguity: priority-ordered DFS, first arrival wins a slot, and (Perl mode)
// the Pike post-match cut: threads ranked below the first alive accept are dead.

import type { Tnfa } from './thompson';
import { sortedEps, symEdgesOf, BEGIN_TEXT, END_TEXT, WORD_BOUNDARY, NO_WORD_BOUNDARY, ABS_BEGIN, ABS_END } from './thompson';
import { classMatches } from './parse';

export type Mode = 'first' | 'longest';

export interface VmThread {
    state: number;
    tags: Int32Array; // tag -> position, -1 = NIL
}

export interface PositionEvent {
    pos: number;
    threads: { state: number; tags: Int32Array; fresh: boolean }[];
    killedCount: number;
    cutFrom?: number; // index of the first alive accept; threads after it were cut (Perl mode)
    accept?: { end: number; tags: Int32Array };
    stopped?: boolean;
    seeded?: boolean;
}

export interface VmTrace {
    events: PositionEvent[];
    match: { start: number; end: number; tags: Int32Array } | null;
    totalSteps: number;
}

function isWord(cp: number): boolean {
    return (cp >= 48 && cp <= 57) || (cp >= 65 && cp <= 90) || cp === 95 || (cp >= 97 && cp <= 122);
}

function positionFlags(input: string, pos: number): number {
    let flags = 0;
    if (pos === 0) flags |= BEGIN_TEXT | ABS_BEGIN;
    if (pos === input.length) flags |= END_TEXT | ABS_END;
    const before = pos > 0 ? input.charCodeAt(pos - 1) : -1;
    const after = pos < input.length ? input.charCodeAt(pos) : -1;
    const wordBefore = before >= 0 && isWord(before);
    const wordAfter = after >= 0 && isWord(after);
    flags |= wordBefore !== wordAfter ? WORD_BOUNDARY : NO_WORD_BOUNDARY;
    return flags;
}

export function assertOk(nfa: Tnfa, mask: number, flags: number): boolean {
    if ((mask & BEGIN_TEXT) && !(flags & BEGIN_TEXT)) return false;
    if ((mask & END_TEXT) && !(flags & END_TEXT)) return false;
    if ((mask & ABS_BEGIN) && !(flags & ABS_BEGIN)) return false;
    if ((mask & ABS_END) && !(flags & ABS_END)) return false;
    if ((mask & WORD_BOUNDARY) && !(flags & WORD_BOUNDARY)) return false;
    if ((mask & NO_WORD_BOUNDARY) && !(flags & NO_WORD_BOUNDARY)) return false;
    return true;
}

/** Priority-ordered ε-closure: DFS over ε edges, preferred child explored first. */
function addThread(nfa: Tnfa, list: VmThread[], seen: Set<number>, state: number, tags: Int32Array, pos: number, flags: number): void {
    const key = state;
    if (seen.has(key)) return;
    seen.add(key);
    const eps = sortedEps(nfa, state); // ascending priority = preferred first
    for (let i = 0; i < eps.length; i++) {
        const e = eps[i];
        if (e.mask !== 0 && !assertOk(nfa, e.mask, flags)) continue;
        const nt = e.tag === 0 ? tags : ((): Int32Array => {
            const c = tags.slice();
            if (e.tag > 0) c[e.tag - 1] = pos;
            else c[-e.tag - 1] = -1;
            return c;
        })();
        addThread(nfa, list, seen, e.to, nt, pos, flags);
    }
    // state itself is a thread if it has symbol edges or is accept
    const hasSym = symEdgesOf(nfa, state).length > 0;
    if (hasSym || state === nfa.accept) list.push({ state, tags });
}

export function runPikeVm(nfa: Tnfa, input: string, mode: Mode, from = 0): VmTrace {
    const events: PositionEvent[] = [];
    let threads: VmThread[] = [];
    let match: { start: number; end: number; tags: Int32Array } | null = null;
    let totalSteps = 0;
    const emptyTags = (): Int32Array => new Int32Array(nfa.tagCount).fill(-1);

    for (let pos = from; pos <= input.length; pos++) {
        const flags = positionFlags(input, pos);
        if (pos === from) {
            const list: VmThread[] = [];
            addThread(nfa, list, new Set(), nfa.start, emptyTags(), pos, flags);
            threads = list;
            events.push({ pos, threads: list.map((t) => ({ ...t, fresh: true })), killedCount: 0, seeded: true });
        }
        // accept handling + pike cut
        const prev = events[events.length - 1];
        const ev: PositionEvent =
            prev && prev.pos === pos
                ? prev // annotate the event pushed by the previous step (or the seed event)
                : { pos, threads: threads.map((t) => ({ state: t.state, tags: t.tags, fresh: true })), killedCount: 0 };
        let stopped = false;
        const acceptIdx = threads.findIndex((t) => t.state === nfa.accept);
        if (acceptIdx >= 0) {
            const winner = threads[acceptIdx];
            match = { start: from, end: pos, tags: winner.tags.slice() };
            ev.accept = { end: pos, tags: match.tags };
            if (mode === 'first') {
                ev.killedCount = threads.length - (acceptIdx + 1);
                ev.cutFrom = acceptIdx;
                threads = threads.slice(0, acceptIdx + 1);
                if (acceptIdx === 0) stopped = true; // nothing ranked above: stop NOW
            }
        }
        ev.stopped = stopped || undefined;
        if (stopped || pos === input.length) break;

        // step on one character
        const c = input.charCodeAt(pos);
        const seen = new Set<number>();
        const list: VmThread[] = [];
        for (const t of threads) {
            for (const se of symEdgesOf(nfa, t.state)) {
                if (classMatches({ ranges: se.ranges, negated: se.negated }, c)) {
                    addThread(nfa, list, seen, se.to, t.tags, pos + 1, positionFlags(input, pos + 1));
                    totalSteps++;
                }
            }
        }
        threads = list;
        events.push({ pos: pos + 1, threads: threads.map((t) => ({ ...t, fresh: true })), killedCount: 0 });
    }

    return { events, match, totalSteps };
}

/** Unanchored search via re-seeding the start at every position (the naive baseline the search ladder improves on). */
export function searchPikeVm(nfa: Tnfa, input: string, mode: Mode): (VmTrace & { from: number }) | null {
    for (let from = 0; from <= input.length; from++) {
        if (from > 0) {
            const lo = input.charCodeAt(from - 1);
            const hi = input.charCodeAt(from);
            const interior = from < input.length && lo >= 0xd800 && lo <= 0xdbff && hi >= 0xdc00 && hi <= 0xdfff;
            if (interior) continue; // never start inside a surrogate pair
        }
        const tr = runPikeVm(nfa, input, mode, from);
        if (tr.match && tr.match.end > tr.match.start) return { ...tr, from };
        if (tr.match && tr.match.end === tr.match.start && tr.match.start === from) return { ...tr, from };
    }
    return null;
}
