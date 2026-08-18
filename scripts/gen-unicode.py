#!/usr/bin/env python3
"""gen-unicode.py — deterministic Unicode table generator for tdfa-unicode.

Consumes pinned UCD files (UnicodeData.txt, Scripts.txt, CaseFolding.txt)
and emits one Java provider class in the tdfa-unicode table format:

  - tableFor(name): 30 two-letter general categories + one-letter
    containers (L M N P S Z C) + "Any" + script names, as re2j-style
    {lo, hi, stride} sparse triples expanded to flat [lo,hi] pairs lazily
    at runtime (cached per name).
  - foldTableFor(name): case-fold counterpart ranges derived from embedded
    simple-fold orbit pairs (CaseFolding.txt statuses C+S, BMP only) —
    codepoints outside the table fold-equivalent to one inside it.

Determinism: every structure is iterated in sorted order; the output is a
pure function of the three input files. Inputs are checksum-verified
against <dir>/<file>.sha256 sidecars before generation.

Usage:
  gen-unicode.py <ucd-dir> <ver-under> <ver-dotted> <out.java>

  gen-unicode.py vendor/ucd/6.0.0 6_0 6.0.0 \
      unicode/v6_0/src/gen/java/io/github/jemmix/tdfa/unicode/v6_0/Unicode6_0.java
"""

import hashlib
import os
import sys

MAX_CP = 0x10FFFF

TWO_LETTER = ["Cc", "Cf", "Cn", "Co", "Cs", "Ll", "Lm", "Lo", "Lt", "Lu",
              "Mc", "Me", "Mn", "Nd", "Nl", "No", "Pc", "Pd", "Pe", "Pf",
              "Pi", "Po", "Ps", "Sc", "Sk", "Sm", "So", "Zl", "Zp", "Zs"]
CONTAINERS = {
    "L": ["Lu", "Ll", "Lt", "Lm", "Lo"],
    "M": ["Mn", "Me", "Mc"],
    "N": ["Nd", "Nl", "No"],
    "P": ["Pc", "Pd", "Ps", "Pe", "Pi", "Pf", "Po"],
    "S": ["Sm", "Sc", "Sk", "So"],
    "C": ["Cc", "Cf", "Co", "Cs", "Cn"],
    "Z": ["Zs", "Zl", "Zp"],
}


def verify(ucd_dir, name):
    with open(os.path.join(ucd_dir, name), "rb") as f:
        data = f.read()
    with open(os.path.join(ucd_dir, name + ".sha256")) as f:
        want = f.read().split()[0]
    got = hashlib.sha256(data).hexdigest()
    if got != want:
        sys.exit(f"checksum mismatch for {name}: expected {want}, got {got}")
    return data.decode("utf-8")


def merge_ranges(ranges):
    """Merge (lo,hi) runs: overlapping or adjacent (gap <= 1) collapse."""
    out = []
    for lo, hi in sorted(ranges):
        if out and lo <= out[-1][1] + 1:
            out[-1][1] = max(out[-1][1], hi)
        else:
            out.append([lo, hi])
    return out


def compress(ranges):
    """Compress merged ranges to re2j-style {lo,hi,stride} triples.

    Singleton ranges in an arithmetic progression with common stride > 1
    collapse to one triple; contiguous runs stay stride 1.
    """
    triples = []
    i = 0
    n = len(ranges)
    while i < n:
        lo, hi = ranges[i]
        if lo != hi:
            triples.append((lo, hi, 1))
            i += 1
            continue
        # singleton: find the longest run of singletons with constant stride
        j = i + 1
        stride = ranges[j][0] - lo if j < n and ranges[j][0] == ranges[j][1] else 0
        if stride > 1:
            k = j
            while (k + 1 < n and ranges[k + 1][0] == ranges[k + 1][1]
                   and ranges[k + 1][0] - ranges[k][0] == stride):
                k += 1
            triples.append((lo, ranges[k][0], stride))
            i = k + 1
        else:
            triples.append((lo, hi, 1))
            i += 1
    return triples


def parse_unicode_data(text):
    """Yield (lo, hi, category) covering every assigned codepoint."""
    entries = []  # (cp, name, cat)
    for line in text.splitlines():
        line = line.split("#", 1)[0].strip()
        if not line:
            continue
        fields = line.split(";")
        cp = int(fields[0], 16)
        name = fields[1]
        cat = fields[2]
        entries.append((cp, name, cat))
    spans = []
    pending_first = None
    for cp, name, cat in entries:
        if name.endswith(", First>"):
            pending_first = (cp, cat)
        elif name.endswith(", Last>"):
            spans.append((pending_first[0], cp, pending_first[1]))
            pending_first = None
        else:
            spans.append((cp, cp, cat))
    return spans


def parse_scripts(text):
    spans = []
    for line in text.splitlines():
        line = line.split("#", 1)[0].strip()
        if not line:
            continue
        rng, script = [x.strip() for x in line.split(";", 1)]
        if ".." in rng:
            a, b = rng.split("..")
            spans.append((int(a, 16), int(b, 16), script))
        else:
            spans.append((int(rng, 16), int(rng, 16), script))
    return spans


def parse_case_folding(text):
    """Simple-fold orbits (statuses C+S), BMP only. Returns union-find parents."""
    parent = {}

    def find(x):
        parent.setdefault(x, x)
        while parent[x] != x:
            parent[x] = parent[parent[x]]
            x = parent[x]
        return x

    def union(a, b):
        ra, rb = find(a), find(b)
        if ra != rb:
            if ra < rb:
                parent[rb] = ra
            else:
                parent[ra] = rb

    for line in text.splitlines():
        line = line.split("#", 1)[0].strip()
        if not line:
            continue
        fields = [x.strip() for x in line.split(";")]
        cp, status, mapping = int(fields[0], 16), fields[1], fields[2]
        if status not in ("C", "S"):
            continue
        target = int(mapping.split()[0], 16)
        if cp > 0xFFFF or target > 0xFFFF:
            continue
        union(cp, target)
    # clique pairs: every member paired with every other, so the runtime's
    # per-pair XOR membership test is transitively complete without closure
    orbits = {}
    for x in sorted(parent):
        orbits.setdefault(find(x), []).append(x)
    pairs = []
    for root in sorted(orbits):
        members = orbits[root]
        for i in range(len(members)):
            for j in range(i + 1, len(members)):
                pairs.append(members[i])
                pairs.append(members[j])
    return pairs


def fmt_triples(triples):
    parts = []
    line = ""
    for lo, hi, stride in triples:
        piece = "{%d,%d,%d}" % (lo, hi, stride)
        if line and len(line) + len(piece) > 100:
            parts.append(line)
            line = piece
        else:
            line = (line + "," + piece) if line else piece
    if line:
        parts.append(line)
    return ",\n            ".join(parts)


def fmt_ints(ints):
    parts = []
    line = ""
    for v in ints:
        piece = str(v)
        if line and len(line) + len(piece) + 1 > 100:
            parts.append(line)
            line = piece
        else:
            line = (line + "," + piece) if line else piece
    if line:
        parts.append(line)
    return ",\n            ".join(parts)


def main():
    if len(sys.argv) != 5:
        sys.exit(__doc__)
    ucd_dir, ver_under, ver_dotted, out_path = sys.argv[1:5]

    ud = verify(ucd_dir, "UnicodeData.txt")
    sc = verify(ucd_dir, "Scripts.txt")
    cf = verify(ucd_dir, "CaseFolding.txt")

    # ---- category tables ----
    cat_ranges = {c: [] for c in TWO_LETTER}
    assigned = []
    for lo, hi, cat in parse_unicode_data(ud):
        cat_ranges[cat].append((lo, hi))
        assigned.append((lo, hi))
    assigned_merged = merge_ranges(assigned)
    # Cn = unassigned
    unassigned = []
    prev = 0
    for lo, hi in assigned_merged:
        if lo > prev:
            unassigned.append((prev, lo - 1))
        prev = hi + 1
    if prev <= MAX_CP:
        unassigned.append((prev, MAX_CP))
    cat_ranges["Cn"] = unassigned

    tables = {}
    for c in TWO_LETTER:
        tables[c] = merge_ranges(cat_ranges[c])
    for name, subs in sorted(CONTAINERS.items()):
        rs = []
        for sub in subs:
            rs.extend(tables[sub])
        tables[name] = merge_ranges(rs)
    tables["Any"] = [(0, MAX_CP)]

    # ---- script tables ----
    script_ranges = {}
    for lo, hi, script in parse_scripts(sc):
        script_ranges.setdefault(script, []).append((lo, hi))
    for s, rs in script_ranges.items():
        tables[s] = merge_ranges(rs)

    fold_pairs = parse_case_folding(cf)

    # ---- emit ----
    names = sorted(tables.keys())  # deterministic order: categories, containers, scripts, Any
    pkg = "io.github.jemmix.tdfa.unicode.v" + ver_under
    cls = "Unicode" + ver_under

    # Chunk tables into package-private holder classes so each <clinit>
    # stays under the 64KB bytecode cap (re2j does the same).
    CHUNK_BUDGET = 30000  # formatted chars per holder class
    chunks = []           # list of list[(name, triples-text)]
    cur, cur_len = [], 0
    for n in names:
        t = compress(tables[n])
        txt = fmt_triples(t) if t else ""
        if cur and cur_len + len(txt) > CHUNK_BUDGET:
            chunks.append(cur)
            cur, cur_len = [], 0
        cur.append((n, txt))
        cur_len += len(txt)
    if cur:
        chunks.append(cur)

    os.makedirs(os.path.dirname(out_path), exist_ok=True)
    # holder classes next to the main class
    base = os.path.dirname(out_path)
    for ci, chunk in enumerate(chunks):
        with open(os.path.join(base, "Tables%d.java" % ci), "w") as f:
            f.write("// Generated by scripts/gen-unicode.py from UCD %s. DO NOT EDIT.\n" % ver_dotted)
            f.write("package %s;\n\n" % pkg)
            f.write("/** Table chunk %d/%d. */\nfinal class Tables%d {\n" % (ci, len(chunks), ci))
            f.write("    static final int[][][] T = {\n")
            for i, (n, txt) in enumerate(chunk):
                comma = "," if i < len(chunk) - 1 else ""
                f.write("            /* %s */ {%s}%s\n" % (n, txt, comma))
            f.write("    };\n}\n")
    with open(out_path, "w") as f:
        w = f.write
        w("// Generated by scripts/gen-unicode.py from UCD %s. DO NOT EDIT.\n" % ver_dotted)
        w("// Source files pinned under vendor/ucd/%s (sha256 sidecars verified at generation).\n" % ver_dotted)
        w("package %s;\n\n" % pkg)
        w("import io.github.jemmix.tdfa.unicode.UnicodeDataProvider;\n\n")
        w("/**\n * Unicode %s tables for the TDFA pipeline: general categories (two-letter and\n" % ver_dotted)
        w(" * one-letter containers), scripts, and simple-case-fold orbits, frozen at the\n")
        w(" * UCD %s snapshot. Deterministically generated; select via\n" % ver_dotted)
        w(" * {@code CompileOptions.unicode(%s.provider())}.\n */\n" % cls)
        w("public final class %s implements UnicodeDataProvider {\n\n" % cls)
        w("    private static final %s INSTANCE = new %s();\n\n" % (cls, cls))
        w("    /** The pinned provider instance. */\n")
        w("    public static %s provider() { return INSTANCE; }\n\n" % cls)
        w("    private %s() { }\n\n" % cls)
        w("    private static final int MAX_CP = 0x10FFFF;\n\n")
        w("    private static final String[] NAMES = {\n")
        w("            " + fmt_ints([0] * 0) + "\"%s\"" % "\", \"".join(names) + "\n    };\n\n")
        w("    /** Table count (index into the chunked holders). */\n")
        w("    private static final int N_TABLES = %d;\n\n" % len(names))
        w("    /** Merged view over the Tables0..Tables%d chunk classes. */\n" % (len(chunks) - 1))
        w("    private static final int[][][] TRIPLES = buildAll();\n\n")
        w("    private static int[][][] buildAll() {\n")
        w("        int[][][] all = new int[N_TABLES][][];\n")
        idx = 0
        for chunk in chunks:
            w("        System.arraycopy(Tables%d.T, 0, all, %d, %d);\n" % (chunks.index(chunk), idx, len(chunk)))
            idx += len(chunk)
        w("        return all;\n")
        w("    }\n\n")
        w("    /** Simple-fold orbit pairs [a0,b0,a1,b1,...] (CaseFolding C+S, BMP). */\n")
        if fold_pairs:
            w("    private static final int[] FOLD_PAIRS = {\n            %s\n    };\n" % fmt_ints(fold_pairs))
        else:
            w("    private static final int[] FOLD_PAIRS = {};\n")
        w("""
    private final java.util.Map<String, int[]> expanded = new java.util.HashMap<>();
    private final java.util.Map<String, int[]> folds = new java.util.HashMap<>();

    @Override public int[] tableFor(String name) {
        if ("Any".equals(name)) return new int[]{0, MAX_CP};
        synchronized (expanded) {
            int[] cached = expanded.get(name);
            if (cached != null) return cached;
        }
        for (int i = 0; i < NAMES.length; i++) {
            if (NAMES[i].equals(name)) {
                int[] flat = expand(TRIPLES[i]);
                synchronized (expanded) { expanded.put(name, flat); }
                return flat;
            }
        }
        return null;
    }

    @Override public int[] foldTableFor(String name) {
        int[] table = tableFor(name);
        if (table == null) return null;
        synchronized (folds) {
            int[] cached = folds.get(name);
            if (cached != null) return cached.length == 0 ? null : cached;
            int[] result = buildFold(table);
            folds.put(name, result == null ? new int[0] : result);
            return result;
        }
    }

    /** Codepoints NOT in {@code table} that fold-equivalent to one that IS. */
    private static int[] buildFold(int[] table) {
        long[] out = new long[FOLD_PAIRS.length / 2];  // candidate codepoints, packed later
        int n = 0;
        for (int p = 0; p < FOLD_PAIRS.length; p += 2) {
            int a = FOLD_PAIRS[p], b = FOLD_PAIRS[p + 1];
            boolean ia = inRange(table, a), ib = inRange(table, b);
            if (ia != ib) out[n++] = ia ? b : a;
        }
        if (n == 0) return null;
        java.util.Arrays.sort(out, 0, n);
        // collect into merged ranges
        int[] flat = new int[n * 2];
        int w = 0;
        int start = (int) out[0], prev = start;
        for (int i = 1; i <= n; i++) {
            int v = i < n ? (int) out[i] : -1;
            if (v != prev + 1) {
                flat[w++] = start; flat[w++] = prev;
                if (i < n) { start = v; }
            }
            prev = v;
        }
        return java.util.Arrays.copyOf(flat, w);
    }

    private static boolean inRange(int[] table, int cp) {
        int lo = 0, hi = table.length / 2 - 1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            if (cp < table[2 * mid]) hi = mid - 1;
            else if (cp > table[2 * mid + 1]) lo = mid + 1;
            else return true;
        }
        return false;
    }

    /** Expand {lo,hi,stride} triples to flat contiguous [lo,hi] pairs:
     *  stride 1 emits one (lo,hi) pair; strided runs emit singleton pairs. */
    private static int[] expand(int[][] triples) {
        int count = 0;
        for (int[] t : triples) {
            count += t[2] == 1 ? 1 : (t[1] - t[0]) / t[2] + 1;
        }
        int[] flat = new int[count * 2];
        int w = 0;
        for (int[] t : triples) {
            if (t[2] == 1) {
                flat[w++] = t[0]; flat[w++] = t[1];
            } else {
                for (int cp = t[0]; cp <= t[1]; cp += t[2]) {
                    flat[w++] = cp; flat[w++] = cp;
                }
            }
        }
        return flat;
    }
}
""")
    print("wrote", out_path, "(%d tables, %d fold pairs)" % (len(names), len(fold_pairs) // 2))


if __name__ == "__main__":
    main()
