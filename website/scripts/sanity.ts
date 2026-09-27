import { parse } from '../src/lib/parse';
import { compileTnfa, isNullable } from '../src/lib/thompson';
import { runPikeVm, searchPikeVm } from '../src/lib/pikevm';
import { subsetConstruct } from '../src/lib/subset';
import { compileBacktracker, runBacktracker } from '../src/lib/backtracker';

let failures = 0;
function check(name: string, cond: boolean, detail = ''): void {
    if (!cond) {
        failures++;
        console.error(`FAIL ${name} ${detail}`);
    } else {
        console.log(`ok   ${name}`);
    }
}

// --- parser basics
check('tag numbering', parse('(a)(b)').tagCount === 4);
check('named group', parse('(?<x>a)').namedGroups.get('x') === 1);
let threw = false;
try {
    parse('(a)\\1');
} catch {
    threw = true;
}
check('backreference rejected', threw);
threw = false;
try {
    parse('(?=a)');
} catch {
    threw = true;
}
check('lookaround rejected', threw);
threw = false;
try {
    parse('a{2}{3}');
} catch {
    threw = true;
}
check('nested quantifier rejected', threw);
check('nullable', isNullable(parse('(a?)*').ast));

// --- pike vm semantics vs java.util.regex/PCRE expectations
function groupsOf(pattern: string, text: string, mode: 'first' | 'longest' = 'first'): string {
    const nfa = compileTnfa(pattern);
    const tr = runPikeVm(nfa, text, mode);
    if (!tr.match) return 'NO MATCH';
    const parts: string[] = [`${tr.match.start},${tr.match.end}`];
    for (let g = 1; g <= nfa.groupCount; g++) {
        const s = tr.match.tags[2 * g - 2];
        const e = tr.match.tags[2 * g - 1];
        parts.push(s < 0 || e < 0 ? 'null' : text.slice(s, e));
    }
    return parts.join(' | ');
}

check('classic ambiguity', groupsOf('(a|ab)(c|bc)', 'abc') === '0,3 | a | bc', groupsOf('(a|ab)(c|bc)', 'abc'));
check('alt first', groupsOf('a|ab', 'ab') === '0,1');
check('alt longest', groupsOf('a|ab', 'ab', 'longest') === '0,2');
check('greedy loop captures', groupsOf('(a)+', 'aaa') === '0,3 | a', groupsOf('(a)+', 'aaa'));
check('greedy inner plus', groupsOf('(a+)+', 'aaa') === '0,3 | aaa', groupsOf('(a+)+', 'aaa'));
check('lazy', groupsOf('a+?', 'aaa') === '0,1');
check('nested groups', groupsOf('((a)b)', 'ab') === '0,2 | ab | a', groupsOf('((a)b)', 'ab'));
check('optional skip nil', groupsOf('(a)?b', 'b') === '0,1 | null');
check('star zero iters', groupsOf('(a)*b', 'b') === '0,1 | null');
check('quantified group last iter', groupsOf('(a){3}', 'aaa') === '0,3 | a', groupsOf('(a){3}', 'aaa'));
check('range quantifier', groupsOf('a{2,3}', 'aaaa') === '0,3');
check('class + negation', groupsOf('[^a]+', 'xyz') === '0,3');
check('anchors', groupsOf('\\babc\\b', 'abc') === '0,3');
check('anchor fail', groupsOf('\\babc\\b', 'zabc') === 'NO MATCH');
check('empty match', groupsOf('a*', '') === '0,0');
check('word boundary anchored', groupsOf('\\bba\\b', 'ba ba') === '0,2', groupsOf('\\bba\\b', 'ba ba'));
check('word boundary at 0', groupsOf('\\bba\\b', 'ba') === '0,2', groupsOf('\\bba\\b', 'ba'));

// search (unanchored baseline)
const s1 = searchPikeVm(compileTnfa('ab'), 'xxabyy', 'first');
check('search finds', !!s1 && s1.match!.start === 2 && s1.match!.end === 4);
const s2 = searchPikeVm(compileTnfa('(\\w+)@(\\w+)'), ' user@host ', 'first');
check('search captures', !!s2 && s2.match!.tags[0] === 1 && s2.match!.tags[1] === 5, s2 ? JSON.stringify([...s2.match!.tags]) : 'null');

// --- subset construction
const sub1 = subsetConstruct(compileTnfa('(a|b)*c'));
check('subset (a|b)*c states', sub1.states.length === 3, `got ${sub1.states.length}`);
const sub2 = subsetConstruct(compileTnfa('(a)*'));
check('subset (a)* loops (finite)', sub2.states.length === 2, `got ${sub2.states.length}`);
const sub3 = subsetConstruct(compileTnfa('(ab|a)(c|bc)'));
check('subset ambiguous pattern', sub3.states.length >= 5 && sub3.states.length <= 10, `got ${sub3.states.length}`);

// subset run semantics: walk the DFA ourselves for (a|b)*c
{
    const { states } = subsetConstruct(compileTnfa('(a|b)*c'));
    const walk = (input: string): boolean => {
        let s = 0;
        for (const ch of input) {
            const tr = states[s].trans.find((t) => ch.charCodeAt(0) >= t.lo && ch.charCodeAt(0) <= t.hi);
            if (!tr) return false;
            s = tr.target;
        }
        return states[s].accept;
    };
    check('dfa accept abbc', walk('abbc'));
    check('dfa reject abb', !walk('abb'));
}

// --- backtracker vs pike vm agreement on random small inputs (no captures)
{
    const alpha = 'abc';
    const patterns = ['(a|b)*c', 'a?b*c', '(ab|a)(c|bc)', '(a+)+b', 'a|ab'];
    let agree = 0;
    let total = 0;
    for (const p of patterns) {
        const prog = compileBacktracker(parse(p).ast);
        for (let mask = 0; mask < 64; mask++) {
            let input = '';
            for (let k = 0; k < 5; k++) input += alpha[mask >> k & 1 ? 1 : (mask >> ((k + 2) % 5) & 1 ? 0 : 2)];
            total++;
            const bt = runBacktracker(prog, input, 500000);
            const vm = searchPikeVm(compileTnfa(p), input, 'first');
            const btFull = (() => { // backtracker anchored at 0? make it search by trying each start
                for (let st = 0; st <= input.length; st++) {
                    const r = runBacktracker(prog, input, 500000, st);
                    if (r.matched) return true;
                }
                return false;
            })();
            if (btFull === !!vm) agree++;
            else {
                check(`agree ${p} "${input}"`, false, `bt=${btFull} vm=${!!vm} steps=${bt.steps}`);
            }
        }
    }
    check(`backtracker/pikevm agree (${agree}/${total})`, agree === total);
}

// --- ReDoS demo math
{
    const prog = compileBacktracker(parse('(a+)+b').ast);
    const r20 = runBacktracker(prog, 'a'.repeat(20), 50_000_000);
    const r24 = runBacktracker(prog, 'a'.repeat(24), 50_000_000);
    console.log(`  backtracker steps (a+)+b, n=20: ${r20.steps}, n=24: ${r24.steps}`);
    check('exponential growth', r24.steps > r20.steps * 8, `${r20.steps} -> ${r24.steps}`);
    check('matches with b', runBacktracker(prog, 'a'.repeat(24) + 'b', 50_000_000).matched);
}

console.log(failures === 0 ? '\nALL PASS' : `\n${failures} FAILURES`);
process.exit(failures === 0 ? 0 : 1);
