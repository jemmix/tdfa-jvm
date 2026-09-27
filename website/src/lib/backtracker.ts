// A deliberately naive backtracking regex VM — the kind of engine
// java.util.regex historically was (and PCRE still is at heart).
// Compiled program: Char / Split(prefer-x) / Jmp / Match. Backtracking via an
// explicit (pc, sp) stack. Every executed instruction counts one step, capped.

import type { Ast } from './parse';

export type Inst =
    | { op: 'char'; ranges: [number, number][]; negated: boolean }
    | { op: 'split'; x: number; y: number } // prefer x
    | { op: 'jmp'; x: number }
    | { op: 'match' };

export class StepBudgetExceeded extends Error {}

export function compileBacktracker(ast: Ast): Inst[] {
    const prog: Inst[] = [];
    const emit = (i: Inst): number => {
        prog.push(i);
        return prog.length - 1;
    };
    const build = (a: Ast): void => {
        switch (a.kind) {
            case 'empty':
                return;
            case 'symbol':
                emit({ op: 'char', ranges: [[a.c, a.c]], negated: false });
                return;
            case 'class':
                emit({ op: 'char', ranges: a.ranges, negated: a.negated });
                return;
            case 'tag':
                return; // captures ignored — this lab only counts steps
            case 'concat':
                a.children.forEach(build);
                return;
            case 'alt': {
                const jmps: number[] = [];
                for (let i = 0; i < a.children.length; i++) {
                    if (i < a.children.length - 1) {
                        const sp = emit({ op: 'split', x: 0, y: 0 });
                        prog[sp].x = prog.length; // prefer this branch
                        build(a.children[i]);
                        jmps.push(emit({ op: 'jmp', x: -1 }));
                        prog[sp].y = prog.length;
                    } else {
                        build(a.children[i]);
                    }
                }
                const end = prog.length;
                jmps.forEach((j) => (prog[j].x = end));
                return;
            }
            case 'repeat': {
                const { body, min, max, greedy } = a;
                if (max === Infinity) {
                    // x{n,} unrolls to x^(n-1) x+ ; x* and x+ are the n=0/1 shapes
                    for (let i = 0; i < Math.min(min === 0 ? 0 : min - 1, 200); i++) build(body);
                    if (min === 0) {
                        // x*: L: Split(body, out); body; Jmp L
                        const l = prog.length;
                        const sp = emit({ op: 'split', x: 0, y: 0 });
                        if (greedy) prog[sp].x = prog.length; // prefer body
                        else prog[sp].y = prog.length;
                        build(body);
                        emit({ op: 'jmp', x: l });
                        const after = prog.length;
                        if (greedy) prog[sp].y = after;
                        else prog[sp].x = after;
                    } else {
                        // x+: L: body; Split(L, out) — one mandatory pass, then the loop
                        const l = prog.length;
                        build(body);
                        const sp = emit({ op: 'split', x: 0, y: 0 });
                        const after = prog.length;
                        if (greedy) {
                            prog[sp].x = l;
                            prog[sp].y = after;
                        } else {
                            prog[sp].x = after;
                            prog[sp].y = l;
                        }
                    }
                } else {
                    // {n,m}: min mandatory copies + (max-min) nested optionals
                    for (let i = 0; i < Math.min(min, 200); i++) build(body);
                    const opts = Math.min(max - min, 200);
                    const splits: number[] = [];
                    for (let i = 0; i < opts; i++) {
                        const sp = emit({ op: 'split', x: 0, y: 0 });
                        splits.push(sp);
                        if (greedy) prog[sp].x = prog.length;
                        else prog[sp].y = prog.length;
                        build(body);
                    }
                    const after = prog.length;
                    splits.forEach((sp) => {
                        if (greedy) prog[sp].y = after;
                        else prog[sp].x = after;
                    });
                }
                return;
            }
            case 'startAnchor':
            case 'endAnchor':
            case 'wordBoundary':
            case 'noWordBoundary':
                // treat assertions as always-true in this lab (patterns here avoid them)
                return;
        }
    };
    build(ast);
    emit({ op: 'match' });
    return prog;
}

export interface BacktrackResult {
    matched: boolean;
    steps: number;
    aborted: boolean;
}

export function runBacktracker(prog: Inst[], input: string, budget = 5_000_000, from = 0): BacktrackResult {
    let steps = 0;
    const stack: { pc: number; sp: number }[] = [];
    let pc = 0;
    let sp = from;
    for (;;) {
        if (++steps > budget) return { matched: false, steps, aborted: true };
        const inst = prog[pc];
        switch (inst.op) {
            case 'char': {
                const c = sp < input.length ? input.charCodeAt(sp) : -1;
                const inR = c >= 0 && inst.ranges.some(([lo, hi]) => c >= lo && c <= hi);
                if (inR !== inst.negated && c >= 0) {
                    pc++;
                    sp++;
                } else {
                    if (stack.length === 0) return { matched: false, steps, aborted: false };
                    const r = stack.pop()!;
                    pc = r.pc;
                    sp = r.sp;
                }
                break;
            }
            case 'split':
                stack.push({ pc: inst.y, sp });
                pc = inst.x;
                break;
            case 'jmp':
                pc = inst.x;
                break;
            case 'match':
                return { matched: true, steps, aborted: false };
        }
    }
}
