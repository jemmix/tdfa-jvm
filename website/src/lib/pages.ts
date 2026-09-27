export type PageMeta = {
    href: string;
    num: string;
    title: string;
    short: string;
};

export const pages: PageMeta[] = [
    { href: '/backtracking/', num: '01', title: 'Why no backtracking', short: 'Why' },
    { href: '/overview/', num: '02', title: 'The pipeline on one page', short: 'Pipeline' },
    { href: '/parsing/', num: '03', title: 'Parsing: pattern to AST', short: 'Parsing' },
    { href: '/tnfa/', num: '04', title: 'The TNFA: an NFA with tags', short: 'TNFA' },
    { href: '/alphabet/', num: '05', title: 'The alphabet: characters into cells', short: 'Alphabet' },
    { href: '/determinization/', num: '06', title: 'Determinization: subset construction with luggage', short: 'Determinization' },
    { href: '/registers/', num: '07', title: 'Registers: captures that survive determinization', short: 'Registers' },
    { href: '/anchors/', num: '08', title: 'Anchors and other zero-width assertions', short: 'Anchors' },
    { href: '/minimization/', num: '09', title: 'Minimization: merging twins', short: 'Minimization' },
    { href: '/execution/', num: '10', title: 'Execution: the run loop and the search ladder', short: 'Execution' },
    { href: '/asm/', num: '11', title: 'The bytecode backend', short: 'Bytecode' },
    { href: '/budgets/', num: '12', title: 'Budgets: bounded by design', short: 'Budgets' },
    { href: '/testing/', num: '13', title: 'How we know it matches what you meant', short: 'Testing' },
];
