// Mini parser mirroring tdfa-jvm's Parser.java (core/.../parser/Parser.java):
// same AST shapes, same tag numbering (group g -> open tag 2g-1, close tag 2g),
// same re2j-parity quirks we demo on the site. Supports a teaching subset.

export type ClassRanges = { ranges: [number, number][]; negated: boolean };

export type Ast =
    | { kind: 'empty' }
    | { kind: 'symbol'; c: number }
    | { kind: 'class'; ranges: [number, number][]; negated: boolean }
    | { kind: 'tag'; tag: number; group: number; open: boolean }
    | { kind: 'concat'; children: Ast[] }
    | { kind: 'alt'; children: Ast[] }
    | { kind: 'repeat'; body: Ast; min: number; max: number; greedy: boolean }
    | { kind: 'startAnchor'; multiline: boolean }
    | { kind: 'endAnchor'; multiline: boolean }
    | { kind: 'wordBoundary' }
    | { kind: 'noWordBoundary' };

export class ParseError extends Error {}

export interface ParseResult {
    ast: Ast;
    tagCount: number;
    groupCount: number;
    namedGroups: Map<string, number>;
}

export const MAX_REPEAT_COUNT = 1000;

const DIGIT: [number, number][] = [[48, 57]];
const WORD: [number, number][] = [
    [48, 57],
    [65, 90],
    [95, 95],
    [97, 122],
];
const SPACE: [number, number][] = [
    [9, 10],
    [12, 13],
    [32, 32],
];

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

export function classMatches(cl: ClassRanges, cp: number): boolean {
    let inRanges = cl.ranges.some(([lo, hi]) => cp >= lo && cp <= hi);
    return cl.negated ? !inRanges : inRanges;
}

class Parser {
    private src: string;
    private pos = 0;
    private nextTag = 1;
    private groupCount = 0;
    private namedGroups = new Map<string, number>();

    constructor(src: string) {
        this.src = src;
    }

    private peek(): number {
        return this.pos < this.src.length ? this.src.charCodeAt(this.pos) : -1;
    }

    private next(): number {
        return this.pos < this.src.length ? this.src.charCodeAt(this.pos++) : -1;
    }

    parse(): ParseResult {
        const ast = this.parseAlt();
        if (this.pos < this.src.length) {
            if (this.peek() === 41) throw new ParseError('unmatched closing parenthesis');
            throw new ParseError(`unexpected character '${this.src[this.pos]}'`);
        }
        return { ast, tagCount: this.nextTag - 1, groupCount: this.groupCount, namedGroups: this.namedGroups };
    }

    private parseAlt(): Ast {
        const branches: Ast[] = [this.parseConcat()];
        while (this.peek() === 124) {
            this.pos++;
            branches.push(this.parseConcat());
        }
        return branches.length === 1 ? branches[0] : { kind: 'alt', children: branches };
    }

    private parseConcat(): Ast {
        const parts: Ast[] = [];
        while (this.pos < this.src.length && this.peek() !== 124 && this.peek() !== 41) {
            parts.push(this.parseRepeat());
        }
        if (parts.length === 0) return { kind: 'empty' };
        if (parts.length === 1) return parts[0];
        return { kind: 'concat', children: parts };
    }

    private parseRepeat(): Ast {
        let atom = this.parseAtom();
        for (;;) {
            const c = this.peek();
            let min: number, max: number;
            if (c === 42) {
                min = 0;
                max = Infinity;
            } else if (c === 43) {
                min = 1;
                max = Infinity;
            } else if (c === 63) {
                min = 0;
                max = 1;
            } else if (c === 123) {
                const q = this.parseBraces();
                if (!q) return atom;
                min = q.min;
                max = q.max;
            } else {
                return atom;
            }
            if (atom.kind === 'repeat' || atom.kind === 'startAnchor' || atom.kind === 'endAnchor' || atom.kind === 'wordBoundary' || atom.kind === 'noWordBoundary') {
                if (atom.kind === 'repeat') throw new ParseError('invalid nested repetition operator');
            }
            this.pos++;
            let greedy = true;
            if (this.peek() === 63) {
                greedy = false;
                this.pos++;
            } else if (this.peek() === 43) {
                throw new ParseError('possessive quantifiers are DFA-incompatible (nothing to backtrack on)');
            }
            atom = { kind: 'repeat', body: atom, min, max, greedy };
        }
    }

    private parseBraces(): { min: number; max: number } | null {
        const save = this.pos;
        this.pos++; // consume '{'
        const min = this.readNumber();
        if (min === null || min > MAX_REPEAT_COUNT) {
            this.pos = save;
            return null;
        }
        let max = min;
        if (this.peek() === 44) {
            this.pos++;
            if (this.peek() === 125) {
                max = Infinity;
            } else {
                max = this.readNumber()!;
                if (max === null || max > MAX_REPEAT_COUNT) {
                    this.pos = save;
                    return null;
                }
            }
        }
        if (this.peek() !== 125) {
            this.pos = save;
            return null;
        }
        if (max < min) throw new ParseError('min > max in {n,m}');
        return { min, max };
    }

    private readNumber(): number | null {
        let v = 0;
        let any = false;
        while (this.peek() >= 48 && this.peek() <= 57) {
            v = v * 10 + (this.next() - 48);
            any = true;
        }
        return any ? v : null;
    }

    private parseAtom(): Ast {
        const c = this.next();
        switch (c) {
            case -1:
                throw new ParseError('unexpected end of pattern');
            case 40: {
                // '('
                return this.parseGroup();
            }
            case 41:
                throw new ParseError('unmatched closing parenthesis');
            case 91: {
                // '['
                return this.parseClass();
            }
            case 46: {
                // '.' — anything except '\n', kept as a symbolically negated class
                return { kind: 'class', ranges: [[10, 10]], negated: true };
            }
            case 94: // '^'
                return { kind: 'startAnchor', multiline: false };
            case 36: // '$'
                return { kind: 'endAnchor', multiline: false };
            case 92: {
                // '\\'
                return this.parseEscape(false);
            }
            case 42:
            case 43:
            case 63:
                throw new ParseError('missing argument to repetition operator');
            default:
                return { kind: 'symbol', c };
        }
    }

    private parseGroup(): Ast {
        let capturing = true;
        let name: string | null = null;
        if (this.peek() === 63) {
            this.pos++;
            const c = this.peek();
            if (c === 58) {
                this.pos++;
                capturing = false;
            } else if (c === 60 || (c === 80 && this.src[this.pos + 1] === '<')) {
                if (c === 80) this.pos++;
                this.pos++; // '<'
                let nm = '';
                while (this.peek() !== 62 && this.peek() !== -1) nm += String.fromCharCode(this.next());
                if (this.peek() !== 62) throw new ParseError('invalid group name');
                this.pos++;
                if (!/^[A-Za-z0-9_]+$/.test(nm)) throw new ParseError('invalid group name');
                name = nm;
            } else if (c === 61 || c === 33) {
                throw new ParseError('lookaround is DFA-incompatible (it needs memory of the future)');
            } else if (c === 62) {
                throw new ParseError('atomic groups are DFA-incompatible (they exist to prune backtracking)');
            } else {
                // inline flags (?i) etc — not supported in the playground
                throw new ParseError('inline flags are not supported in this playground');
            }
        }
        let open = -1;
        let close = -1;
        const group = ++this.groupCount;
        if (capturing) {
            open = this.nextTag++;
            close = this.nextTag++;
            if (name !== null) {
                if (this.namedGroups.has(name)) throw new ParseError(`duplicate capture group name: \`${name}\``);
                this.namedGroups.set(name, group);
            }
        }
        const body = this.parseAlt();
        if (this.peek() !== 41) throw new ParseError('missing closing parenthesis');
        this.pos++;
        if (!capturing) return body;
        return {
            kind: 'concat',
            children: [
                { kind: 'tag', tag: open, group, open: true },
                body,
                { kind: 'tag', tag: close, group, open: false },
            ],
        };
    }

    private parseClass(): Ast {
        const ranges: [number, number][] = [];
        let negated = false;
        if (this.peek() === 94) {
            negated = true;
            this.pos++;
        }
        let first = true;
        for (;;) {
            let c = this.next();
            if (c === -1) throw new ParseError('missing closing ]');
            if (c === 93 && !first) break; // ']' literal when first
            first = false;
            let lo: number;
            if (c === 92) {
                const esc = this.classEscape();
                if (esc.ranges) {
                    ranges.push(...esc.ranges);
                    continue;
                }
                lo = esc.cp!;
            } else {
                lo = c;
            }
            let hi = lo;
            if (this.peek() === 45 && this.pos + 1 < this.src.length && this.src.charCodeAt(this.pos + 1) !== 93) {
                this.pos++; // '-'
                let c2 = this.next();
                if (c2 === 92) {
                    const esc = this.classEscape();
                    if (esc.ranges) throw new ParseError('invalid range endpoint');
                    c2 = esc.cp!;
                }
                hi = c2;
                if (hi < lo) throw new ParseError('invalid range order in character class');
            }
            ranges.push([lo, hi]);
        }
        if (ranges.length === 0) throw new ParseError('empty character class');
        return { kind: 'class', ranges, negated };
    }

    private classEscape(): { cp?: number; ranges?: [number, number][] } {
        const c = this.next();
        switch (c) {
            case 100:
                return { ranges: DIGIT };
            case 119:
                return { ranges: WORD };
            case 115:
                return { ranges: SPACE };
            case 110:
                return { cp: 10 };
            case 116:
                return { cp: 9 };
            case 114:
                return { cp: 13 };
            default:
                return { cp: c };
        }
    }

    private parseEscape(inClass: boolean): Ast {
        const c = this.next();
        switch (c) {
            case 100:
                return { kind: 'class', ranges: DIGIT, negated: false };
            case 68:
                return { kind: 'class', ranges: DIGIT, negated: true };
            case 119:
                return { kind: 'class', ranges: WORD, negated: false };
            case 87:
                return { kind: 'class', ranges: WORD, negated: true };
            case 115:
                return { kind: 'class', ranges: SPACE, negated: false };
            case 83:
                return { kind: 'class', ranges: SPACE, negated: true };
            case 98:
                return { kind: 'wordBoundary' };
            case 66:
                return { kind: 'noWordBoundary' };
            case 110:
                return { kind: 'symbol', c: 10 };
            case 116:
                return { kind: 'symbol', c: 9 };
            case 114:
                return { kind: 'symbol', c: 13 };
            case 49:
            case 50:
            case 51:
            case 52:
            case 53:
            case 54:
            case 55:
            case 56:
            case 57:
                throw new ParseError('backreferences are DFA-incompatible (they need memory of the match)');
            default:
                if (!inClass && /[a-zA-Z]/.test(String.fromCharCode(c)) && 'abcdefghjklmoquvwxyzABCDEFHIJKLMOQUVWXYZ'.includes(String.fromCharCode(c))) {
                    throw new ParseError(`unknown escape sequence \\${String.fromCharCode(c)}`);
                }
                return { kind: 'symbol', c };
        }
    }
}

export function parse(src: string): ParseResult {
    if (src.length > 200) throw new ParseError('pattern too long for this playground (200 chars max)');
    return new Parser(src).parse();
}

export function classLabel(cl: ClassRanges): string {
    const parts = cl.ranges.slice(0, 4).map(([lo, hi]) =>
        lo === hi ? esc(lo) : hi === lo + 1 ? `${esc(lo)}${esc(hi)}` : `${esc(lo)}-${esc(hi)}`,
    );
    let inner = cl.ranges.length > 4 ? `${parts.join('')}…` : parts.join('');
    if (cl.ranges.length === 1 && cl.ranges[0][0] === cl.ranges[0][1] && !cl.negated) return esc(cl.ranges[0][0]);
    return cl.negated ? `[^${inner}]` : `[${inner}]`;
}

export function esc(cp: number): string {
    if (cp === 10) return '\\n';
    if (cp === 9) return '\\t';
    if (cp === 13) return '\\r';
    if (cp >= 33 && cp <= 126) return String.fromCharCode(cp);
    if (cp >= 0x20 && cp <= 0x10ffff) return String.fromCodePoint(cp);
    return `U+${cp.toString(16).toUpperCase().padStart(4, '0')}`;
}

export function tagName(tag: number): string {
    const abs = Math.abs(tag);
    const group = Math.ceil(abs / 2);
    const open = abs % 2 === 1;
    return `t${abs} (g${group} ${open ? 'open' : 'close'})`;
}
