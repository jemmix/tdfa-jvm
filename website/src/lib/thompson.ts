// Mini Thompson construction mirroring tdfa-jvm's Tnfa.java (core/.../tnfa/Tnfa.java):
// - epsilon edges carry (priority, tag, emptyMask); lower priority number = preferred
// - greedy/lazy decided by body/skip priority assignment
// - ntag chains (-t "clear tag") on alternation branches missing a group and on
//   the initial skip of e? / e* — the structural "no match" encoding (BT19 §7.3)
// - star with nullable body becomes (e+)? ; plain star gets a shared loop hub
// - {n,m} desugars eagerly: x{n,} = x^{n-1} x+ ; x{n,m} = x^n (x (x …)?)? right-nested

import type { Ast } from './parse';
import { parse } from './parse';

export const BEGIN_TEXT = 1;
export const END_TEXT = 2;
export const WORD_BOUNDARY = 4;
export const NO_WORD_BOUNDARY = 8;
export const ABS_BEGIN = 16;
export const ABS_END = 32;

export interface EpsEdge {
    from: number;
    to: number;
    pri: number;
    tag: number; // 0 none, +t set tag t, -t clear tag t
    mask: number;
}

export interface SymEdge {
    from: number;
    to: number;
    ranges: [number, number][];
    negated: boolean;
}

export interface Tnfa {
    stateCount: number;
    eps: EpsEdge[];
    sym: SymEdge[];
    start: number;
    accept: number;
    tagCount: number;
    groupCount: number;
    namedGroups: Map<string, number>;
}

class Builder {
    private counter = 0;
    eps: EpsEdge[] = [];
    sym: SymEdge[] = [];

    fresh(): number {
        return this.counter++;
    }

    epsEdge(from: number, to: number, pri: number, tag = 0, mask = 0): void {
        this.eps.push({ from, to, pri, tag, mask });
    }

    symEdge(from: number, to: number, ranges: [number, number][], negated: boolean): void {
        this.sym.push({ from, to, ranges, negated });
    }

    /** Build a fragment matching `ast` that flows into `entryTo`; returns the fragment's start state. */
    build(ast: Ast, entryTo: number): number {
        switch (ast.kind) {
            case 'empty':
                return entryTo;
            case 'symbol': {
                const s = this.fresh();
                this.symEdge(s, entryTo, [[ast.c, ast.c]], false);
                return s;
            }
            case 'class': {
                const s = this.fresh();
                this.symEdge(s, entryTo, ast.ranges, ast.negated);
                return s;
            }
            case 'tag': {
                const s = this.fresh();
                this.epsEdge(s, entryTo, 1, ast.tag);
                return s;
            }
            case 'concat': {
                let to = entryTo;
                for (let i = ast.children.length - 1; i >= 0; i--) to = this.build(ast.children[i], to);
                return to;
            }
            case 'alt': {
                const s = this.fresh();
                const allGroups = unionGroups(ast.children);
                ast.children.forEach((branch, i) => {
                    const bs = this.build(branch, entryTo);
                    const missing = allGroups.filter((g) => !groupsOf(branch).has(g)).sort((a, b) => b - a);
                    const chainStart = this.ntagChain(missing, bs);
                    this.epsEdge(s, chainStart, i + 1); // branch priority: source order
                });
                return s;
            }
            case 'repeat':
                return this.buildRepeat(ast, entryTo);
            case 'startAnchor': {
                const s = this.fresh();
                this.epsEdge(s, entryTo, 1, 0, ast.multiline ? BEGIN_TEXT : ABS_BEGIN);
                return s;
            }
            case 'endAnchor': {
                const s = this.fresh();
                this.epsEdge(s, entryTo, 1, 0, ast.multiline ? END_TEXT : ABS_END);
                return s;
            }
            case 'wordBoundary': {
                const s = this.fresh();
                this.epsEdge(s, entryTo, 1, 0, WORD_BOUNDARY);
                return s;
            }
            case 'noWordBoundary': {
                const s = this.fresh();
                this.epsEdge(s, entryTo, 1, 0, NO_WORD_BOUNDARY);
                return s;
            }
        }
    }

    private buildRepeat(ast: Extract<Ast, { kind: 'repeat' }>, entryTo: number): number {
        const { body, min, max, greedy } = ast;
        const bodyPri = greedy ? 1 : 2;
        const skipPri = greedy ? 2 : 1;

        if (max === 0) return this.build({ kind: 'empty' }, entryTo); // {0,0}

        if (min === 0 && max === 1) {
            // e? (also the base case the {n,m} desugaring bottoms out in)
            const s = this.fresh();
            const bs = this.build(body, entryTo);
            const skip = this.ntagChain([...groupsOf(body)].sort((a, b) => b - a), entryTo);
            this.epsEdge(s, bs, bodyPri);
            this.epsEdge(s, skip, skipPri);
            return s;
        }

        if (max !== Infinity) {
            if (min === max) {
                // {n}: n concatenated copies (tags duplicated per copy, like the real builder)
                const copies: Ast[] = Array.from({ length: min }, () => body);
                return this.build({ kind: 'concat', children: copies }, entryTo);
            }
            if (min >= 2) {
                // {n,m} with n ≥ 2: n−1 mandatory copies, then the {1, m−n+1} remainder
                const copies: Ast[] = Array.from({ length: min - 1 }, () => body);
                const rest: Ast = { kind: 'repeat', body, min: 1, max: max - min + 1, greedy };
                return this.build({ kind: 'concat', children: [...copies, rest] }, entryTo);
            }
            // min is 0 or 1 here, max ≥ 2
            if (min === 1) {
                // {1,m}: body then {0,m−1} — one mandatory pass, right-nested optional suffix
                const opts = max - 1;
                let tail: Ast = { kind: 'empty' };
                for (let i = 0; i < opts; i++) {
                    tail = { kind: 'concat', children: [body, { kind: 'repeat', body: tail, min: 0, max: 1, greedy }] };
                }
                return this.build({ kind: 'concat', children: [body, tail] }, entryTo);
            }
            // {0,m}: right-nested optional suffix alone: x (x (x …)? )?
            const opts = max;
            let tail: Ast = { kind: 'empty' };
            for (let i = 0; i < opts; i++) {
                tail = { kind: 'concat', children: [body, { kind: 'repeat', body: tail, min: 0, max: 1, greedy }] };
            }
            return this.build(tail, entryTo);
        }

        if (min >= 2 && max === Infinity) {
            // {n,} = (n−1) copies + body+ — never body* (the plus tail cuts empty re-iteration)
            const copies: Ast[] = Array.from({ length: min - 1 }, () => body);
            const rest: Ast = { kind: 'repeat', body, min: 1, max: Infinity, greedy };
            return this.build({ kind: 'concat', children: [...copies, rest] }, entryTo);
        }

        if (min === 1 && max === Infinity) {
            // e+ : body flows into a post-body hub that either iterates or exits.
            // The fragment's ENTRY is the body start — one iteration is mandatory.
            const s = this.fresh();
            const bs = this.build(body, s);
            this.epsEdge(s, bs, bodyPri);
            this.epsEdge(s, entryTo, skipPri);
            return bs;
        }
        // e*
        if (isNullable(body)) {
            // (e+)? — nullable bodies need the quest-around-plus shape for correct priority order
            const s = this.fresh();
            const hub = this.fresh();
            const bs = this.build(body, hub);
            this.epsEdge(hub, bs, bodyPri); // iterate
            this.epsEdge(hub, entryTo, skipPri); // exit (no ntags: a real iteration may have happened)
            const skip = this.ntagChain([...groupsOf(body)].sort((a, b) => b - a), entryTo); // 0-iteration path
            this.epsEdge(s, hub, bodyPri);
            this.epsEdge(s, skip, skipPri);
            return s;
        }
        // plain star: pre-loop decision + shared loop hub; ntags only on the INITIAL skip
        const s0 = this.fresh();
        const hub = this.fresh();
        const bs = this.build(body, hub); // body returns to the hub
        this.epsEdge(hub, bs, bodyPri); // iterate
        this.epsEdge(hub, entryTo, skipPri); // exit
        const skip = this.ntagChain([...groupsOf(body)].sort((a, b) => b - a), entryTo); // initial skip = 0 iterations
        this.epsEdge(s0, bs, bodyPri); // initial enter
        this.epsEdge(s0, skip, skipPri);
        return s0;
    }

    /** Chain of "clear tag" epsilon edges (one per group), descending group order, ending at `target`. */
    private ntagChain(groups: number[], target: number): number {
        let to = target;
        for (const g of groups) {
            const s = this.fresh();
            this.epsEdge(s, to, 1, -(2 * g)); // negative = clear the group's close tag
            to = s;
        }
        return to;
    }
}

function groupsOf(ast: Ast, acc = new Set<number>()): Set<number> {
    if (ast.kind === 'tag') acc.add(ast.group);
    if (ast.kind === 'concat' || ast.kind === 'alt') ast.children.forEach((c) => groupsOf(c, acc));
    if (ast.kind === 'repeat') groupsOf(ast.body, acc);
    return acc;
}

function unionGroups(asts: Ast[]): number[] {
    const s = new Set<number>();
    asts.forEach((a) => groupsOf(a, s));
    return [...s];
}

export function isNullable(ast: Ast): boolean {
    switch (ast.kind) {
        case 'empty':
        case 'startAnchor':
        case 'endAnchor':
        case 'wordBoundary':
        case 'noWordBoundary':
        case 'tag':
            return true;
        case 'symbol':
        case 'class':
            return false;
        case 'concat':
            return ast.children.every(isNullable);
        case 'alt':
            return ast.children.some(isNullable);
        case 'repeat':
            return ast.min === 0 || isNullable(ast.body);
    }
}

export function compileTnfa(pattern: string): Tnfa {
    const res = parse(pattern);
    const b = new Builder();
    const accept = b.fresh(); // accept minted first, mirroring the real builder
    const start = b.build(res.ast, accept);
    return {
        stateCount: b.counter,
        eps: b.eps,
        sym: b.sym,
        start,
        accept,
        tagCount: res.tagCount,
        groupCount: res.groupCount,
        namedGroups: res.namedGroups,
    };
}

/** Epsilon edges leaving a state, sorted by ascending priority (preferred first). */
export function sortedEps(nfa: Tnfa, state: number): EpsEdge[] {
    return nfa.eps.filter((e) => e.from === state).sort((a, b) => a.pri - b.pri);
}

export function symEdgesOf(nfa: Tnfa, state: number): SymEdge[] {
    return nfa.sym.filter((e) => e.from === state);
}
