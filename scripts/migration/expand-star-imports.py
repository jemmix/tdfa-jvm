#!/usr/bin/env python3
"""One-shot star-import expansion for the tdfa codestyle migration.

Replaces every `import p.*;` / `import static p.C.*;` in codestyle-scoped
sources with explicit single-name imports (the no-star-imports rule,
CI-enforced by checkstyle AvoidStarImport). Members are resolved from:
  - the platform image (java.*)
  - the Gradle-cached jar that provides the package (jmh annotations)
  - javap over the cached jar (assertj statics)
  - same-repo sources (Tdfa / Re2jOracle statics)
Usage is matched on comment/string-masked text, so literals never count.
Compiler + checkstyle verify the result; spotless then orders the imports.
"""
import os
import re
import subprocess
import sys
import zipfile
import pathlib

ROOT = pathlib.Path(__file__).resolve().parent.parent
JAVA_HOME = pathlib.Path(os.environ.get('JAVA_HOME') or subprocess.check_output(
    ['/usr/libexec/java_home']).decode().strip())
GRADLE_CACHE = pathlib.Path.home() / '.gradle/caches/modules-2/files-2.1'

ASSERTJ_JAR = GRADLE_CACHE / 'org.assertj/assertj-core/3.25.3'
JMH_JAR = next((GRADLE_CACHE / 'org.openjdk.jmh/jmh-core/1.37').glob('*/jmh-core-1.37.jar'))

SOURCES = {
    'io.github.jemmix.tdfa.tdfa.Tdfa':
        ROOT / 'core/src/main/java/io/github/jemmix/tdfa/tdfa/Tdfa.java',
    'io.github.jemmix.tdfa.parity.Re2jOracle':
        ROOT / 'tests/parity/re2j/src/test/java/io/github/jemmix/tdfa/parity/Re2jOracle.java',
}
EXCLUDE = ('tests/parity/re2j-suite/', 'unicode/')
INCLUDE_DIRS = ['core', 'asm', 'src', 'tests', 'testlib', 'lib', 'benchmarks', 'ci']


def mask_text(text):
    lines = text.split('\n')
    out = []
    block = False
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
            c = line[i]
            if line[i:i + 2] == '//':
                buf.append(' ' * (n - i))
                i = n
            elif line[i:i + 2] == '/*':
                block = True
                buf.append('  ')
                i += 2
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
    return '\n'.join(out)


def platform_classes(package):
    """Top-level class simple names of `package` from the platform image."""
    img = JAVA_HOME / 'lib/modules'
    listing = subprocess.check_output(
        ['jimage', 'list', str(img)]).decode()
    prefix = package.replace('.', '/') + '/'
    names = set()
    for entry in listing.splitlines():
        entry = entry.strip()
        # entries print as `java/util/List.class` (or `java.base/java/util/...`
        # on some JDKs); either way match the trailing package path
        rel = '/' + entry
        want = '/' + prefix
        if want not in rel or not entry.endswith('.class'):
            continue
        cls = rel[rel.index(want) + len(want):]
        if '/' in cls:
            continue
        simple = cls[:-6]
        if '$' in simple or not simple[0].isupper():
            continue
        names.add(simple)
    return names


def jar_classes(jar, package):
    prefix = package.replace('.', '/') + '/'
    names = set()
    with zipfile.ZipFile(jar) as zf:
        for entry in zf.namelist():
            if not entry.startswith(prefix):
                continue
            cls = entry[len(prefix):]
            if '/' in cls or not cls.endswith('.class'):
                continue
            simple = cls[:-6]
            if '$' in simple or not simple[0].isupper():
                continue
            names.add(simple)
    return names


def static_members_of_class(class_fqn):
    """(methods, fields) — public static member names of a same-repo class."""
    path = SOURCES[class_fqn]
    text = mask_text(path.read_text())
    methods, fields = set(), set()
    for m in re.finditer(r'public\s+static\s+(?:final\s+)?[\w<>\[\],.?\s]+?(\w+)\s*\(', text):
        methods.add(m.group(1))
    for m in re.finditer(r'public\s+static\s+(?:final\s+)?[\w<>\[\],.?\s]+?(\w+)\s*(?:=|;)', text):
        name = m.group(1)
        if name not in methods:
            fields.add(name)
    return methods, fields


def static_members_of_jar_class(jar, class_fqn):
    out = subprocess.check_output(
        ['javap', '-cp', str(jar), class_fqn]).decode()
    methods, fields = set(), set()
    for line in out.splitlines():
        m = re.match(r'\s+public static .*', line)
        if not m:
            continue
        name = re.search(r'(\w+)\s*\(', line)
        if name:
            methods.add(name.group(1))
        else:
            name = re.search(r'(\w+)\s*;', line)
            if name:
                fields.add(name.group(1))
    return methods, fields


def resolve_members(is_static, fqn):
    """Returns (candidate_names, needs_paren) for the star import of fqn."""
    if is_static:
        if fqn in SOURCES:
            methods, fields = static_members_of_class(fqn)
        else:
            jar_dir = GRADLE_CACHE / 'org.assertj/assertj-core/3.25.3'
            jar = next(p for p in jar_dir.glob('*/assertj-core-3.25.3.jar'))
            methods, fields = static_members_of_jar_class(jar, fqn)
        return methods, fields
    if fqn == 'java.util':
        return set(), platform_classes('java/util')
    if fqn == 'org.openjdk.jmh.annotations':
        return set(), jar_classes(JMH_JAR, 'org.openjdk.jmh.annotations')
    raise SystemExit(f'unknown package for star import: {fqn}')


def import_block_extent(masked_lines):
    """(start, end_exclusive) of the leading import block."""
    start = end = None
    for i, line in enumerate(masked_lines):
        s = line.strip()
        if s.startswith('import '):
            if start is None:
                start = i
            end = i + 1
        elif s == '' and start is not None:
            continue
        elif start is not None:
            break
    return start or 0, end or 0


def expand_file(path):
    text = path.read_text()
    lines = text.split('\n')
    masked = mask_text(text).split('\n')
    ib_start, ib_end = import_block_extent(masked)
    body = '\n'.join(masked[ib_end:])
    already = {re.match(r'import\s+(?:static\s+)?([\w.]+)\s*;', l).group(1)
               for l in masked[ib_start:ib_end]
               if re.match(r'import\s+(?:static\s+)?[\w.]+\s*;', l)}
    changed = False
    out = []
    for line in lines:
        m = re.match(r'import\s+(static\s+)?([\w.]+)\s*\.\s*\*\s*;\s*$', line)
        if not m:
            out.append(line)
            continue
        is_static, fqn = bool(m.group(1)), m.group(2)
        methods, fields = resolve_members(is_static, fqn)
        used = []
        for name in sorted(methods):
            if fqn + '.' + name in already:
                continue
            if re.search(r'\b' + re.escape(name) + r'\s*\(', body):
                used.append(name)
        for name in sorted(fields):
            if fqn + '.' + name in already:
                continue
            if re.search(r'\b' + re.escape(name) + r'\b', body):
                used.append(name)
        if not used:
            print(f'  {path.relative_to(ROOT)}: {fqn}.* -> unused, dropped')
            changed = True
            continue
        kw = 'import static ' if is_static else 'import '
        for name in used:
            out.append(kw + fqn + '.' + name + ';')
        changed = True
        print(f'  {path.relative_to(ROOT)}: {fqn}.* -> {len(used)} imports')
    if changed:
        path.write_text('\n'.join(out))
    return changed


def main():
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
            expand_file(p)


if __name__ == '__main__':
    main()
