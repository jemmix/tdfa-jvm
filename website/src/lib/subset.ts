// Mini subset construction mirroring tdfa-jvm's TdfaCompiler (core/.../tdfa/TdfaCompiler.java):
// - breakpoints: every class range boundary becomes a cut point; cells between
//   consecutive breakpoints are equivalence classes of codepoints
// - DFA state = kernel = ordered list of configs (TNFA state + emptyMask + tag
//   history l), interned by the exact arrival-order signature INCLUDING l
// - the key trick this lab shows: histories reset on every symbol step
//   (stepOnSymbol carries l -> h and starts the next l empty), so kernels stay
//   small and the DFA is finite even though "the tag history" is unbounded
// - transition ops: last sign per tag over the target configs' h (the pre-step
//   history) — one SET_POS per freshly-crossed tag (simplified register model:
//   one register per tag; the real engine allocates per (source, tag, sign)
//   and merges same-shape states with COPY ops)

import type { Tnfa } from './thompson';
import { sortedEps, symEdgesOf } from './thompson';
import { classMatches } from './parse';

export interface Config {
    state: number;
    mask: number;
    l: number[]; // signed tag ids crossed since the last symbol step (only +t recorded, like the real engine)
}

export interface DfaTransition {
    lo: number;
    hi: number;
    target: number; // DFA state id
    ops: number[][]; // [opcode, tag] with opcode 1=SET_POS 2=SET_NIL
}

export interface DfaStateInfo {
    id: number;
    kernel: Config[];
    accept: boolean;
    finalOps: number[][]; // [opcode, tag]: 1=SET_POS at end, 3=COPY work->final
    trans: DfaTransition[];
}

export type SubsetEvent =
    | { type: 'start'; seedState: number }
    | { type: 'pop'; sid: number; kernel: Config[] }
    | { type: 'cell'; sid: number; lo: number; hi: number; rep: number; stepped: { from: Config; toState: number; h: number[] }[]; ops: number[][]; targetKernel: Config[]; verdict: 'new' | 'existing'; target: number }
    | { type: 'final'; sid: number; ops: number[][] }
    | { type: 'done'; stateCount: number };

export interface SubsetResult {
    states: DfaStateInfo[];
    events: SubsetEvent[];
    breakpoints: number[];
    cells: [number, number][];
}

export const OP_SET_POS = 1;
export const OP_SET_NIL = 2;
export const OP_COPY = 3;

function closure(nfa: Tnfa, seeds: Config[]): Config[] {
    const out: Config[] = [];
    const seen = new Set<number>(); // (state, mask) — first POP wins, like the real visited set
    const stack: Config[] = [];
    for (let i = seeds.length - 1; i >= 0; i--) stack.push(seeds[i]); // reverse push => seeds[0] pops first
    while (stack.length > 0) {
        const c = stack.pop()!;
        const key = (c.state + 1) * 64 + c.mask;
        if (seen.has(key)) continue;
        seen.add(key);
        out.push(c);
        const eps = sortedEps(nfa, c.state); // ascending priority
        for (let i = eps.length - 1; i >= 0; i--) {
            // reverse push => preferred child pops first; its subtree completes first (DFS arrival order)
            const e = eps[i];
            const l = e.tag > 0 ? [...c.l, e.tag] : c.l; // only POSITIVE tags are recorded
            stack.push({ state: e.to, mask: c.mask | e.mask, l });
        }
    }
    return out;
}

function sigOf(configs: Config[]): string {
    return configs.map((c) => `${c.state}.${c.mask}:${c.l.join('.')}`).join(',');
}

/** last sign of each tag over the union of the given histories */
function lastSigns(hists: number[][], tagCount: number): Int8Array {
    const signs = new Int8Array(tagCount + 1);
    for (const h of hists) {
        for (const t of h) {
            if (t > 0) signs[t] = 1;
            else if (t < 0) signs[-t] = -1;
        }
    }
    return signs;
}

export function subsetConstruct(nfa: Tnfa): SubsetResult {
    const events: SubsetEvent[] = [];
    // breakpoints: every class boundary (negated classes materialized first)
    const bps = new Set<number>([0, 0x110000]);
    for (const se of nfa.sym) {
        const ranges = se.negated ? negate(se.ranges) : se.ranges;
        for (const [lo, hi] of ranges) {
            bps.add(lo);
            if (hi + 1 <= 0x10ffff) bps.add(hi + 1);
        }
    }
    const breakpoints = [...bps].sort((a, b) => a - b);
    const cells: [number, number][] = [];
    for (let i = 0; i + 1 < breakpoints.length; i++) cells.push([breakpoints[i], breakpoints[i + 1] - 1]);

    const states: DfaStateInfo[] = [];
    const index = new Map<string, number>();
    const work: number[] = [];

    events.push({ type: 'start', seedState: nfa.start });
    const initClosure = closure(nfa, [{ state: nfa.start, mask: 0, l: [] }]);
    index.set(sigOf(initClosure), 0);
    states.push(mkState(0, initClosure, nfa));
    work.push(0);

    while (work.length > 0) {
        const sid = work.pop()!;
        const st = states[sid];
        events.push({ type: 'pop', sid, kernel: st.kernel });

        for (const [lo, hi] of cells) {
            // step every config whose symbol edges match this cell
            const stepped: { from: Config; toState: number; h: number[] }[] = [];
            const seen = new Set<number>();
            for (const c of st.kernel) {
                for (const se of symEdgesOf(nfa, c.state)) {
                    if (classMatches({ ranges: se.ranges, negated: se.negated }, lo) && !seen.has(se.to)) {
                        seen.add(se.to);
                        stepped.push({ from: c, toState: se.to, h: c.l }); // h := pre-step history
                    }
                }
            }
            if (stepped.length === 0) continue;

            // target kernel: closure of the stepped seeds, each with l reset to empty
            const seeds: Config[] = stepped.map((s) => ({ state: s.toState, mask: 0, l: [] }));
            const targetKernel = closure(nfa, seeds);
            if (targetKernel.length === 0) continue;

            // transition ops: last sign per tag over the target configs' h
            const signs = lastSigns(stepped.map((s) => s.h), nfa.tagCount);
            const ops: number[][] = [];
            for (let t = 1; t <= nfa.tagCount; t++) {
                if (signs[t] === 1) ops.push([OP_SET_POS, t]);
                else if (signs[t] === -1) ops.push([OP_SET_NIL, t]);
            }

            const sig = sigOf(targetKernel);
            let verdict: 'new' | 'existing';
            let target: number;
            if (index.has(sig)) {
                target = index.get(sig)!;
                verdict = 'existing';
            } else {
                target = states.length;
                index.set(sig, target);
                states.push(mkState(target, targetKernel, nfa));
                work.push(target);
                verdict = 'new';
            }
            mergeTrans(states[sid].trans, { lo, hi, target, ops });
            events.push({ type: 'cell', sid, lo, hi, rep: lo, stepped, ops, targetKernel, verdict, target });
        }

        if (st.accept) events.push({ type: 'final', sid, ops: st.finalOps });
    }
    events.push({ type: 'done', stateCount: states.length });
    return { states, events, breakpoints: breakpoints.filter((b) => b < 0x110000), cells };
}

function negate(ranges: [number, number][]): [number, number][] {
    const sorted = [...ranges].sort((a, b) => a[0] - b[0]);
    const out: [number, number][] = [];
    let next = 0;
    for (const [lo, hi] of sorted) {
        if (lo > next) out.push([next, lo - 1]);
        next = Math.max(next, hi + 1);
    }
    if (next <= 0x10ffff) out.push([next, 0x10ffff]);
    return out;
}

function mkState(id: number, kernel: Config[], nfa: Tnfa): DfaStateInfo {
    const accept = kernel.some((c) => c.state === nfa.accept);
    const finalOps: number[][] = [];
    if (accept) {
        const acc = kernel.find((c) => c.state === nfa.accept)!;
        const signs = lastSigns([acc.l], nfa.tagCount);
        for (let t = 1; t <= nfa.tagCount; t++) {
            if (signs[t] === 1) finalOps.push([OP_SET_POS, t]);
            else if (signs[t] === -1) finalOps.push([OP_SET_NIL, t]);
            else finalOps.push([OP_COPY, t]);
        }
    }
    return { id, kernel, accept, finalOps, trans: [] };
}

/** coalesce adjacent cells with equal (target, ops) into one range */
function mergeTrans(trans: DfaTransition[], tr: DfaTransition): void {
    const last = trans[trans.length - 1];
    if (last && last.target === tr.target && JSON.stringify(last.ops) === JSON.stringify(tr.ops) && last.hi + 1 === tr.lo) {
        last.hi = tr.hi;
    } else {
        trans.push({ ...tr, ops: tr.ops.map((o) => [...o]) });
    }
}
