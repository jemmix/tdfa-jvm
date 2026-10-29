#!/usr/bin/env python3
"""Open a GitHub issue for NEW nightly-fuzz mismatches (one issue per run).

Reads the chunked soak's out dir (default ``build/fuzz-nightly`` — the same
dir scripts/fuzz-soak.sh was pointed at), classifies every
``failures.ndjson`` record, and — if any record has a mismatch signature not
already tracked by an OPEN ``nightly-fuzz`` issue — creates exactly one
issue listing the new signatures with reproduction commands.

"New" is signature-level, mirroring DifferentialFuzzer's own dedup
(``kind | shape~<structural chars>``): a fresh random seed base every night
re-DISCOVERS the same underlying bug under new caseSeeds, and one issue per
bug (not per seed) is the useful signal. Signatures already present in an
open issue's body (``fuzz-sig:`` lines) are skipped; closing a tracked issue
re-arms its signatures.

Records the fuzzer counts but that are not findings — KNOWN_DIVERGENCE
(classified), JURLANE_SKIP / JURLANE_GUARD (jur-lane domain exclusions and
oracle-bomb guards), BUDGET_REJECT (documented fuzz-scoped budget
contract), HANG_ORACLE (oracle-side limit) — never open issues. HANG_ENGINE
records are included; when the soak's post-hoc hang replay
(hang-replays.log) has a verdict for the seed it is quoted inline.

Usage (repo root, GH_TOKEN in env):
  python3 scripts/fuzz-nightly-issue.py [out-dir] [run-url]
Exits 0 when nothing new (or nothing actionable at all); nonzero only on
real tooling failure so the workflow step fails loudly.
"""

import json
import os
import re
import subprocess
import sys
from collections import defaultdict
from datetime import datetime, timezone

LABEL = "nightly-fuzz"
ACTIONABLE = ("RESULT_MISMATCH", "COMPILE_PARITY", "EXCEPTION", "MEMO_STATE_MISMATCH",
              "CROSS_RUNG", "CROSS_ROUTING", "HANG_ENGINE")
CASES_PER_SIG = 8  # mirrors the fuzzer's own per-signature ndjson cap


def shape(pattern: str) -> str:
    """DifferentialFuzzer.shape: structural chars only, capped at 24."""
    return "".join(ch for ch in pattern if ch in "()[]|*+?{}")[:24]


def gh(*args: str) -> str:
    env = dict(os.environ)
    env.setdefault("GH_TOKEN", env.get("GITHUB_TOKEN", ""))
    out = subprocess.run(["gh", *args], capture_output=True, text=True, env=env)
    if out.returncode != 0:
        sys.stderr.write(out.stderr)
        raise SystemExit(f"gh {' '.join(args[:2])} failed with exit {out.returncode}")
    return out.stdout


def open_issue_signatures() -> set:
    issues = json.loads(gh("issue", "list", "--label", LABEL, "--state", "open",
                           "--json", "number,body", "--limit", "200"))
    sigs = set()
    for issue in issues:
        sigs.update(re.findall(r"^fuzz-sig:\s*(.+)$", issue["body"], re.M))
    return sigs


def main() -> int:
    out_dir = sys.argv[1] if len(sys.argv) > 1 else "build/fuzz-nightly"
    run_url = sys.argv[2] if len(sys.argv) > 2 else os.environ.get("RUN_URL", "(run url unavailable)")

    ndjson = os.path.join(out_dir, "failures.ndjson")
    records = []
    if os.path.exists(ndjson):
        with open(ndjson, encoding="utf-8", errors="replace") as fh:
            for line in fh:
                try:
                    records.append(json.loads(line))
                except json.JSONDecodeError:
                    continue  # torn tail line from a watchdog-killed chunk

    actionable = [r for r in records
                  if str(r.get("kind", "")).split(" ", 1)[0] in ACTIONABLE]
    if not actionable:
        print(f"no actionable records ({len(records)} total records, informational kinds excluded)")
        return 0

    by_sig = defaultdict(list)
    for r in actionable:
        kind = str(r.get("kind", "?")).split(" ", 1)[0]
        by_sig[f"{kind} | shape~{shape(str(r.get('pattern', '')))}"].append(r)

    tracked = open_issue_signatures()
    new_sigs = {sig: cases for sig, cases in by_sig.items() if sig not in tracked}
    if not new_sigs:
        print(f"{len(actionable)} actionable records, all {len(by_sig)} signatures "
              f"already tracked in open {LABEL} issues — nothing new")
        return 0

    replay_verdicts = {}
    hang_log = os.path.join(out_dir, "hang-replays.log")
    if os.path.exists(hang_log):
        with open(hang_log, encoding="utf-8", errors="replace") as fh:
            for line in fh:
                m = re.search(r"seed=(\d+).*?(REPLAY.*)$", line)
                if m:
                    replay_verdicts[m.group(1)] = m.group(2)

    day = datetime.now(timezone.utc).strftime("%Y-%m-%d")
    lines = [
        f"Nightly differential fuzz soak ({day}) found **{len(new_sigs)} new** mismatch "
        f"signature(s) — {len(actionable)} actionable records total, signatures already "
        f"tracked in open {LABEL} issues excluded.",
        "",
        f"Soak run: {run_url}",
        f"Artifacts stayed on the runner; the records below carry the essentials. "
        f"The seed base is recorded in `{out_dir}/iterations.log` — a local re-run "
        f"reproduces the same case universe:",
        "",
        "```",
        "scripts/fuzz-soak.sh 240 1 build/fuzz-repro <seedBase>",
        "```",
        "",
        "Reproduce a single case:",
        "",
        "```",
        "./gradlew :tests:parity:re2j:fuzz -Pfuzz.one=<caseSeed>",
        "```",
        "",
    ]
    for sig, cases in sorted(new_sigs.items()):
        lines += [f"## {sig} — {len(cases)} record(s)", ""]
        for r in cases[:CASES_PER_SIG]:
            seed = r.get("caseSeed", "?")
            lines.append(f"- `caseSeed={seed}` kind=`{r.get('kind', '?')}` lane=`{r.get('lane', '?')}` "
                         f"pattern=`{str(r.get('pattern', ''))[:80]}`")
            if str(r.get("kind", "")).startswith("HANG_ENGINE") and str(seed) in replay_verdicts:
                lines.append(f"  - replay verdict: {replay_verdicts[str(seed)]}")
        if len(cases) > CASES_PER_SIG:
            lines.append(f"- … {len(cases) - CASES_PER_SIG} more (see failures.ndjson on the runner)")
        lines.append("")
    lines += ["<!-- machine-readable tracked signatures (scripts/fuzz-nightly-issue.py):"] \
        + [f"fuzz-sig: {sig}" for sig in sorted(new_sigs)] + ["-->"]

    gh("label", "create", LABEL, "--color", "B60205",
       "--description", "new mismatches found by the nightly fuzz soak")
    body_file = os.path.join(out_dir, "issue-body.md")
    with open(body_file, "w", encoding="utf-8") as fh:
        fh.write("\n".join(lines))
    url = gh("issue", "create",
             "--title", f"nightly fuzz {day}: {len(new_sigs)} new mismatch signature(s)",
             "--body-file", body_file, "--label", LABEL).strip()
    print(f"opened {url}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
