// Layered layout for small automata rendered as SVG in the islands,
// powered by dagre (Sugiyama-style layered layout): proper ranking,
// crossing minimization, edge routing and RESERVED SPACE for edge labels,
// so labels never overlap edges or nodes.

import dagre from 'dagre';
import type { Tnfa } from './thompson';

export interface LaidNode {
    id: number;
    x: number;
    y: number;
    r: number;
}

export interface LaidEdge {
    id: string;
    from: number;
    to: number;
    kind: 'eps' | 'sym';
    pri?: number;
    tag?: number;
    mask?: number;
    label: string;
    points: { x: number; y: number }[];
    lx: number;
    ly: number;
    lw: number;
    lh: number;
}

export interface Layout {
    nodes: LaidNode[];
    edges: LaidEdge[];
    width: number;
    height: number;
}

const R = 11;
const LABEL_H = 15;

function tagLabel(t: number): string {
    return t > 0 ? `+t${t}` : `−t${-t}`;
}

function maskLabel(m: number): string {
    if (m === 0) return '';
    const parts: string[] = [];
    if (m & 16) parts.push('\\A');
    if (m & 1) parts.push('^');
    if (m & 32) parts.push('\\z');
    if (m & 2) parts.push('$');
    if (m & 4) parts.push('\\b');
    if (m & 8) parts.push('\\B');
    return parts.join(' ');
}

function symLabel(ranges: [number, number][], negated: boolean): string {
    const ch = (cp: number) => (cp >= 33 && cp <= 126 ? String.fromCharCode(cp) : `U+${cp.toString(16)}`);
    const parts = ranges.slice(0, 3).map(([lo, hi]) => (lo === hi ? ch(lo) : hi === lo + 1 ? `${ch(lo)}${ch(hi)}` : `${ch(lo)}-${ch(hi)}`));
    let s = parts.join('');
    if (ranges.length > 3) s += '…';
    if (negated) return `[^${s}]`;
    return ranges.length === 1 && ranges[0][0] === ranges[0][1] ? s : `[${s}]`;
}

/** path through dagre waypoints (endpoints sit on the node borders) */
export function edgePath(points: { x: number; y: number }[]): string {
    if (points.length === 0) return '';
    let d = `M ${points[0].x.toFixed(1)} ${points[0].y.toFixed(1)}`;
    for (let i = 1; i < points.length; i++) d += ` L ${points[i].x.toFixed(1)} ${points[i].y.toFixed(1)}`;
    return d;
}

export function layoutTnfa(nfa: Tnfa): Layout {
    const g = new dagre.graphlib.Graph({ multigraph: true });
    g.setGraph({
        rankdir: 'LR',
        nodesep: 44,
        edgesep: 8,
        ranksep: 100,
        marginx: 30,
        marginy: 26,
    });
    g.setDefaultEdgeLabel(() => ({ label: '' }));

    for (let s = 0; s < nfa.stateCount; s++) {
        g.setNode(String(s), { width: R * 2, height: R * 2 });
    }

    const setEdge = (id: string, from: number, to: number, kind: 'eps' | 'sym', label: string, meta: Partial<LaidEdge>): void => {
        const lw = label.length === 0 ? 0 : Math.max(18, label.length * 5.9 + 8);
        g.setEdge(String(from), String(to), { label, width: lw, height: label ? LABEL_H : 0 }, id);
        edgeMeta.set(id, { kind, label, from, to, ...meta });
    };
    const edgeMeta = new Map<string, Partial<LaidEdge>>();

    nfa.eps.forEach((e, i) => {
        const ml = maskLabel(e.mask);
        const tl = e.tag !== 0 ? tagLabel(e.tag) : '';
        const pr = e.pri > 1 ? `p${e.pri}` : '';
        const label = [tl, ml, pr].filter(Boolean).join(' ');
        setEdge(`e${i}`, e.from, e.to, 'eps', label, { pri: e.pri, tag: e.tag, mask: e.mask });
    });
    nfa.sym.forEach((e, i) => {
        setEdge(`s${i}`, e.from, e.to, 'sym', symLabel(e.ranges, e.negated), {});
    });

    dagre.layout(g);

    const nodes: LaidNode[] = [];
    for (let s = 0; s < nfa.stateCount; s++) {
        const n = g.node(String(s));
        nodes.push({ id: s, x: n.x, y: n.y, r: R });
    }

    const edges: LaidEdge[] = [];
    for (const [id, meta] of edgeMeta) {
        const le = g.edge(String(meta.from), String(meta.to), id);
        edges.push({
            id,
            from: meta.from!,
            to: meta.to!,
            label: meta.label ?? '',
            kind: meta.kind!,
            pri: meta.pri,
            tag: meta.tag,
            mask: meta.mask,
            points: le.points.map((p) => ({ x: p.x, y: p.y })),
            lx: le.x ?? 0,
            ly: le.y ?? 0,
            lw: le.width ?? 0,
            lh: le.height ?? 0,
        });
    }

    return { nodes, edges, width: g.graph().width ?? 100, height: g.graph().height ?? 100 };
}
