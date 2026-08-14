#!/usr/bin/env python3
"""Compare two JMH JSON result files and fail on regressions.

Usage: bench-compare.py BASELINE.json CURRENT.json [--threshold 0.10]

For every benchmark present in both files, compares primaryMetric.score
(lower = faster). Fails (exit 1) if CURRENT is slower than BASELINE by more
than the threshold (default 10%). Benchmarks missing from either file are
reported; missing-from-current is a failure (benchmark deleted or renamed =
silent coverage loss). Improvements are reported informationally.

Machine-specific: only compare runs from the same machine. JMH's own error
margins are printed but deliberately not used for the verdict — keep the
threshold conservative instead.
"""
import json
import sys


def load(path):
    with open(path) as f:
        data = json.load(f)
    return {e["benchmark"]: e["primaryMetric"]["score"] for e in data}


def main():
    threshold = 0.10
    argv = list(sys.argv[1:])
    for i, a in enumerate(argv):
        if a is None:
            continue
        if a == "--threshold" and i + 1 < len(argv):
            threshold = float(argv[i + 1])
            argv[i] = None
            argv[i + 1] = None
        elif a.startswith("--threshold="):
            threshold = float(a.split("=", 1)[1])
            argv[i] = None
    args = [a for a in argv if a is not None]
    if len(args) != 2:
        print(__doc__)
        sys.exit(2)
    base, cur = load(args[0]), load(args[1])

    regressions, improvements = [], []
    for name in sorted(base):
        if name not in cur:
            regressions.append((name, base[name], None))
            continue
        b, c = base[name], cur[name]
        delta = (c - b) / b
        if delta > threshold:
            regressions.append((name, b, c))
        elif delta < -threshold:
            improvements.append((name, b, c))
    for name in sorted(cur):
        if name not in base:
            print(f"NEW       {name}  {cur[name]:.3f}")

    for name, b, c in improvements:
        print(f"IMPROVED  {name}  {b:.3f} -> {c:.3f}  ({(c - b) / b:+.1%})")
    if regressions:
        for name, b, c in regressions:
            if c is None:
                print(f"MISSING   {name}  baseline {b:.3f} (not in current run)")
            else:
                print(f"REGRESSED {name}  {b:.3f} -> {c:.3f}  ({(c - b) / b:+.1%})")
        sys.exit(1)
    print(f"OK: {len(base)} benchmarks vs baseline, no regression > {threshold:.0%}")


if __name__ == "__main__":
    main()
