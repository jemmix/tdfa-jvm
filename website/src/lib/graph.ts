// Layered layout for small automata rendered as SVG in the islands.
// BFS layering from the start state; nodes stack vertically inside a layer.

import type { Tnfa } from './thompson';

export interface LaidNode {
    id: number;
    x: number;
    y: number;
    layer: number;
}

export interface LaidEdge {
    from: number;
    to: number;
    kind: 'eps' | 'sym';
    pri?: number;
    tag?: number;
    mask?: number;
    label: string;
    dx1: number;
    dy1: number;
    dx2: number;
    dy2: number;
}

export interface Layout {
    nodes: LaidNode[];
    edges: LaidEdge[];
    width: number;
    height: number;
}

const DX = 108;
const DY = 74;
const PAD = 42;

export function layoutTnfa(nfa: Tnfa): Layout {
    // adjacency (undirected for BFS)
    const adj: Map<number, number[]> = new Map();
    const addAdj = (a: number, b: number) => {
        if (!adj.has(a)) adj.set(a, []);
        if (!adj.has(b)) adj.set(b, []);
        adj.get(a)!.push(b);
        adj.get(b)!.push(a);
    };
    nfa.eps.forEach((e) => addAdj(e.from, e.to));
    nfa.sym.forEach((e) => addAdj(e.from, e.to));

    // BFS layers from start
    const layer = new Map<number, number>();
    layer.set(nfa.start, 0);
    const queue = [nfa.start];
    while (queue.length > 0) {
        const s = queue.shift()!;
        for (const t of adj.get(s) ?? []) {
            if (!layer.has(t)) {
                layer.set(t, layer.get(s)! + 1);
                queue.push(t);
            }
        }
    }
    for (let s = 0; s < nfa.stateCount; s++) if (!layer.has(s)) layer.set(s, 0);

    // order within layer: keep mint order
    const byLayer = new Map<number, number[]>();
    for (let s = 0; s < nfa.stateCount; s++) {
        const l = layer.get(s)!;
        if (!byLayer.has(l)) byLayer.set(l, []);
        byLayer.get(l)!.push(s);
    }
    const maxLayer = Math.max(...[...byLayer.keys()]);
    const maxCol = Math.max(...[...byLayer.values()].map((v) => v.length));

    const pos = new Map<number, { x: number; y: number }>();
    for (const [l, states] of byLayer) {
        states.forEach((s, i) => {
            const span = (states.length - 1) * DY;
            pos.set(s, { x: PAD + (maxLayer - l) * DX, y: PAD + i * DY + (maxCol - 1) * DY * 0.5 - span / 2 });
        });
    }

    const nodes: LaidNode[] = [];
    for (let s = 0; s < nfa.stateCount; s++) nodes.push({ id: s, x: pos.get(s)!.x, y: pos.get(s)!.y, layer: layer.get(s)! });

    const width = PAD * 2 + maxLayer * DX + 56;
    const height = PAD * 2 + (maxCol - 1) * DY + 56;

    const edges: LaidEdge[] = [];
    const tagLabel = (t: number) => (t > 0 ? `+t${t}` : `−t${-t}`);
    const maskLabel = (m: number) => {
        if (m === 0) return '';
        const parts: string[] = [];
        if (m & 16) parts.push('\\A');
        if (m & 1) parts.push('^');
        if (m & 32) parts.push('\\z');
        if (m & 2) parts.push('$');
        if (m & 4) parts.push('\\b');
        if (m & 8) parts.push('\\B');
        return parts.join(' ');
    };
    const symLabel = (ranges: [number, number][], negated: boolean) => {
        const parts = ranges.slice(0, 3).map(([lo, hi]) => (lo === hi ? ch(lo) : hi === lo + 1 ? `${ch(lo)}${ch(hi)}` : `${ch(lo)}-${ch(hi)}`));
        let s = parts.join('');
        if (ranges.length > 3) s += '…';
        return negated ? `[^${s}]` : ranges.length === 1 && ranges[0][0] === ranges[0][1] ? s : `[${s}]`;
    };
    const ch = (cp: number) => (cp >= 33 && cp <= 126 ? String.fromCharCode(cp) : `U+${cp.toString(16)}`);

    const curve = (a: { x: number; y: number }, b: { x: number; y: number }) => {
        // horizontal-ish bezier; bump vertically when same layer
        if (Math.abs(a.y - b.y) < 8 && a.x !== b.x) {
            return { dx1: (b.x - a.x) * 0.4, dy1: -30, dx2: (b.x - a.x) * 0.6, dy2: -30 };
        }
        if (a.x > b.x) {
            // back edge (loop): swing below
            return { dx1: 26, dy1: 34, dx2: -26, dy2: 34 };
        }
        return { dx1: 30, dy1: 0, dx2: -30, dy2: 0 };
    };

    for (const e of nfa.eps) {
        const a = pos.get(e.from)!;
        const b = pos.get(e.to)!;
        const ml = maskLabel(e.mask);
        const tl = e.tag !== 0 ? tagLabel(e.tag) : '';
        const pr = e.pri !== 1 ? ` p${e.pri}` : '';
        const label = [tl, ml, pr].filter(Boolean).join(' ') || 'ε';
        const c = curve(a, b);
        edges.push({ from: e.from, to: e.to, kind: 'eps', pri: e.pri, tag: e.tag, mask: e.mask, label, dx1: a.x + c.dx1, dy1: a.y + c.dy1, dx2: b.x + c.dx2, dy2: b.y + c.dy2 });
    }
    for (const e of nfa.sym) {
        const a = pos.get(e.from)!;
        const b = pos.get(e.to)!;
        const c = curve(a, b);
        edges.push({ from: e.from, to: e.to, kind: 'sym', label: symLabel(e.ranges, e.negated), dx1: a.x + c.dx1, dy1: a.y + c.dy1, dx2: b.x + c.dx2, dy2: b.y + c.dy2 });
    }

    return { nodes, edges, width, height };
}
