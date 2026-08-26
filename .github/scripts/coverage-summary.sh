#!/usr/bin/env bash
# Turns the merged JaCoCo CSV into a per-package table in the workflow summary,
# so a coverage drop is visible in the run rather than only in a downloaded zip.
set -euo pipefail

CSV="${1:?path to jacoco.csv required}"
[[ -f "$CSV" ]] || { echo "No coverage data at $CSV"; exit 0; }

python3 - "$CSV" <<'PY' >> "${GITHUB_STEP_SUMMARY:-/dev/stdout}"
import collections, csv, sys

rows = list(csv.DictReader(open(sys.argv[1])))
overall = collections.Counter()
by_package = collections.defaultdict(collections.Counter)

for row in rows:
    for metric in ("LINE", "BRANCH"):
        missed, covered = int(row[metric + "_MISSED"]), int(row[metric + "_COVERED"])
        overall[metric + "_M"] += missed
        overall[metric + "_C"] += covered
        by_package[row["PACKAGE"]][metric + "_M"] += missed
        by_package[row["PACKAGE"]][metric + "_C"] += covered

def pct(counter, metric):
    covered, missed = counter[metric + "_C"], counter[metric + "_M"]
    return 100 * covered / (covered + missed) if covered + missed else 0.0

print("### Coverage — merged across unit, integration and system suites")
print()
print(f"**Line {pct(overall, 'LINE'):.1f}% · Branch {pct(overall, 'BRANCH'):.1f}%**")
print()
print("| Package | Line | Branch |")
print("| ------- | ---: | -----: |")
for package in sorted(by_package):
    counter = by_package[package]
    print(f"| `{package}` | {pct(counter, 'LINE'):.1f}% | {pct(counter, 'BRANCH'):.1f}% |")
PY
