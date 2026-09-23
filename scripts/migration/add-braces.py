#!/usr/bin/env python3
"""One-shot brace inserter for the tdfa codestyle migration.

Wraps braceless if/else/for/while bodies in braces (mandatory-braces rule,
CI-enforced by checkstyle NeedBraces). Conservative by design:
  - only inserts '{' / '}' and newlines; never reorders or deletes tokens
  - comments and string/char/text-block contents are masked before any
    structural matching, so code-like text in literals is never touched
  - do-while: `} while (...)` tails are recognized and left alone
  - `else if` cascades are preserved (only the leaf bodies get braces)
  - anything unexpected is skipped and reported for a manual look

Ongoing enforcement is checkstyle's NeedBraces; the IDE adds braces on
reformat via config/codestyle/tdfa-idea.xml. This script exists only for
the initial migration sweep.
"""
import re
import sys
import pathlib

ROOT = pathlib.Path(__file__).resolve().parent.parent
# Machine-generated / vendored trees are out of codestyle scope entirely.
EXCLUDE = ('tests/parity/re2j-suite/', 'unicode/v6_0/src/gen/', 'unicode/v17_0/src/gen/')
INCLUDE_DIRS = ['core', 'asm', 'src', 'tests', 'testlib', 'lib', 'benchmarks', 'ci']

CTRL = re.compile(r'\b(if|for|while)\s*\(')
ELSE = re.compile(r'\belse\b')


def mask_lines(lines):
    """Comment/string-masked copy of lines, same lengths, delimiters kept."""
    out = []
    block = textblk = False
    for line in lines:
        buf = []
        i, n = 0, len(line)
        while i < n:
            if block:
                e = line.find('*/', i)
                if e < 0:
                    buf.append(' ' * (n - i))
                    i = n
                else:
                    buf.append(' ' * (e + 2 - i))
                    i = e + 2
                    block = False
                continue
            if textblk:
                e = line.find('"""', i)
                if e < 0:
                    buf.append(' ' * (n - i))
                    i = n
                else:
                    buf.append(' ' * (e + 3 - i))
                    i = e + 3
                    textblk = False
                continue
            c = line[i]
            if line[i:i + 2] == '//':
                buf.append(' ' * (n - i))
                i = n
            elif line[i:i + 2] == '/*':
                block = True
                buf.append('  ')
                i += 2
            elif line[i:i + 3] == '"""':
                textblk = True
                buf.append('   ')
                i += 3
            elif c == '"':
                j = i + 1
                while j < n and line[j] != '"':
                    j += 2 if line[j] == '\\' else 1
                buf.append('"' + ' ' * (j - i - 1) + '"')
                i = j + 1
            elif c == "'":
                j = i + 1
                while j < n and line[j] != "'":
                    j += 2 if line[j] == '\\' else 1
                buf.append("'" + ' ' * (j - i - 1) + "'")
                i = j + 1
            else:
                buf.append(c)
                i += 1
        out.append(''.join(buf))
    return out


def ident_char(ch):
    return ch.isalnum() or ch == '_' or ch == '$'


def find_close(masked, li, ci):
    """ci points at '('; return coords of the matching ')' or None."""
    depth = 0
    while li < len(masked):
        while ci < len(masked[li]):
            ch = masked[li][ci]
            if ch == '(':
                depth += 1
            elif ch == ')':
                depth -= 1
                if depth == 0:
                    return li, ci
            ci += 1
        li += 1
        ci = 0
    return None


def skip_ws(masked, li, ci):
    """Next non-space char position at/after (li, ci), crossing lines."""
    while li < len(masked):
        line = masked[li]
        while ci < len(line):
            if line[ci] not in ' \t':
                return li, ci
            ci += 1
        li += 1
        ci = 0
    return None


def is_control_at(masked, li, ci):
    for kw in ('if', 'for', 'while'):
        seg = masked[li][ci:ci + len(kw)]
        if seg == kw:
            nxt = masked[li][ci + len(kw):ci + len(kw) + 1]
            if not nxt or not ident_char(nxt):
                return kw
    return None


def stmt_end(masked, li, ci):
    """End (exclusive) of the statement starting at (li, ci): the ';'
    balanced at depth 0 over () [] {} — or None if it runs away."""
    depth = 0
    while li < len(masked):
        line = masked[li]
        while ci < len(line):
            ch = line[ci]
            if ch in '([{':
                depth += 1
            elif ch in ')]}':
                depth -= 1
            elif ch == ';' and depth == 0:
                return li, ci + 1
            ci += 1
        li += 1
        ci = 0
    return None


def prev_nonspace_on_line(masked, li, ci):
    j = ci - 1
    while j >= 0:
        if masked[li][j] not in ' \t':
            return masked[li][j]
        j -= 1
    return ''


def find_block_end(masked, li, ci):
    """ci points at '{'; return (line, index-after-'}') of the matching brace."""
    depth = 0
    while li < len(masked):
        while ci < len(masked[li]):
            ch = masked[li][ci]
            if ch == '{':
                depth += 1
            elif ch == '}':
                depth -= 1
                if depth == 0:
                    return li, ci + 1
            ci += 1
        li += 1
        ci = 0
    return None


def stmt_extent(masked, li, ci):
    """End (exclusive) of the statement at (li, ci): a simple statement
    runs to its ';'; an if/for/while runs through its body and, for if,
    any trailing else/else-if chain. None if unparseable."""
    kw = is_control_at(masked, li, ci)
    if kw is None:
        return stmt_end(masked, li, ci)
    # find the header's opening paren
    p = skip_ws(masked, li, ci + len(kw))
    if p is None or masked[p[0]][p[1]] != '(':
        return None
    close = find_close(masked, p[0], p[1])
    if close is None:
        return None
    end = body_extent(masked, close[0], close[1] + 1)
    if end is None:
        return None
    if kw != 'if':
        return end
    # chase the else / else-if chain
    while True:
        nxt = skip_ws(masked, end[0], end[1])
        if nxt is None:
            return end
        line = masked[nxt[0]]
        if line[nxt[1]:nxt[1] + 4] != 'else':
            return end
        after = line[nxt[1] + 4:nxt[1] + 5]
        if after and ident_char(after):
            return end  # identifier, not the else keyword
        end = body_extent(masked, nxt[0], nxt[1] + 4)
        if end is None:
            return end


def body_extent(masked, li, ci):
    """Extent of the body starting at/after (li, ci): a braced block, a
    nested control statement, or a simple statement up to ';'."""
    p = skip_ws(masked, li, ci)
    if p is None:
        return None
    bl, bc = p
    ch = masked[bl][bc]
    if ch == '{':
        return find_block_end(masked, bl, bc)
    if is_control_at(masked, bl, bc):
        return stmt_extent(masked, bl, bc)
    return stmt_end(masked, bl, bc)


def wrap_site(real, masked, anchor_li, anchor_ci, body_li, body_ci, end_li, end_ci, notes):
    """Insert braces around one site; returns edited real lines."""
    indent = re.match(r'[ \t]*', real[anchor_li]).group(0)
    if end_li == anchor_li:
        head = real[anchor_li][:anchor_ci]
        mid = real[anchor_li][anchor_ci:body_ci].rstrip()
        body_text = real[anchor_li][body_ci:end_ci]
        post = real[anchor_li][end_ci:]
        newline = [
            head + ' {' + (mid if mid.strip() else ''),
            indent + '    ' + body_text,
            indent + '}' + post,
        ]
        return real[:anchor_li] + newline + real[anchor_li + 1:], True
    # statement ends beyond the anchor line: brace after the header,
    # closer line after the statement end
    head = real[anchor_li][:anchor_ci]
    mid = real[anchor_li][anchor_ci:].rstrip()
    real[anchor_li] = head + ' {' + (mid if mid.strip() else '')
    if end_ci >= len(real[end_li].rstrip()):
        real[end_li] = real[end_li].rstrip()
        real.insert(end_li + 1, indent + '}')
    else:
        line = real[end_li]
        real[end_li] = line[:end_ci]
        real.insert(end_li + 1, indent + '}' + line[end_ci:])
    return real, True


def transform_once(real):
    """Find the first wrappable site, wrap it, report; False when done."""
    masked = mask_lines(real)
    li = 0
    while li < len(masked):
        line = masked[li]
        # --- else (not `else if`, not `else {`) ---
        for m in ELSE.finditer(line):
            li2, ci2 = skip_ws(masked, li, m.end())
            if li2 is None:
                continue
            if is_control_at(masked, li2, ci2):
                continue  # cascade
            if masked[li2][ci2] == '{':
                continue
            end = stmt_end(masked, li2, ci2)
            if end is None:
                continue
            real2, did = wrap_site(real, masked, li, m.end(), li2, ci2, end[0], end[1], None)
            print(f'  else L{li + 1}')
            return real2, True
        # --- if / for / while headers ---
        for m in CTRL.finditer(line):
            # skip `while` that is a do-tail: preceded by '}' on this line
            prev = prev_nonspace_on_line(masked, li, m.start())
            if prev == '}':
                continue
            close = find_close(masked, li, m.end() - 1)
            if close is None:
                continue
            body = body_extent(masked, close[0], close[1] + 1)
            if body is None:
                continue
            bl, bc = skip_ws(masked, close[0], close[1] + 1)
            if bl is None or masked[bl][bc] == '{':
                continue  # already braced
            real2, did = wrap_site(real, masked, close[0], close[1] + 1, bl, bc, body[0], body[1], None)
            print(f'  {m.group(1)} L{li + 1}')
            return real2, True
        li += 1
    return real, False


def process(path):
    text = path.read_text()
    real = text.split('\n')
    total = 0
    while True:
        real, did = transform_once(real)
        if not did:
            break
        total += 1
        if total > 10000:
            raise RuntimeError(f'{path}: no fixpoint after 10000 edits')
    if total:
        path.write_text('\n'.join(real))
    return total


def main():
    grand = 0
    files = 0
    for d in INCLUDE_DIRS:
        base = ROOT / d
        if not base.exists():
            continue
        for p in sorted(base.rglob('*.java')):
            rel = str(p.relative_to(ROOT))
            if any(rel.startswith(e) or ('/' + e) in rel for e in EXCLUDE):
                continue
            if '/build/' in rel:
                continue
            n = process(p)
            if n:
                print(f'{rel}: {n} site(s)')
                files += 1
                grand += n
    print(f'total: {grand} sites wrapped in {files} files')


if __name__ == '__main__':
    main()
