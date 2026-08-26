#!/usr/bin/env bash
# Renders a suite's results into the workflow summary. Written by hand rather than
# pulled from a marketplace action: it is twenty lines, it has no supply-chain
# surface, and the format is exactly what we want to read.
set -euo pipefail

SUITE="${1:?suite name required}"
REPORT_DIR="${2:?report directory required}"

if [[ ! -d "$REPORT_DIR" ]]; then
  echo "### $SUITE tests: no reports produced" >> "${GITHUB_STEP_SUMMARY:-/dev/stdout}"
  exit 0
fi

totals=$(python3 - "$REPORT_DIR" <<'PY'
import glob, sys, xml.etree.ElementTree as ET
tests = failures = errors = skipped = 0
time = 0.0
for path in glob.glob(sys.argv[1] + "/TEST-*.xml"):
    root = ET.parse(path).getroot()
    tests += int(root.get("tests", 0))
    failures += int(root.get("failures", 0))
    errors += int(root.get("errors", 0))
    skipped += int(root.get("skipped", 0))
    time += float(root.get("time", 0))
print(f"{tests} {failures} {errors} {skipped} {time:.1f}")
PY
)
read -r tests failures errors skipped time <<< "$totals"

status="passed"
[[ "$failures" -gt 0 || "$errors" -gt 0 ]] && status="failed"

{
  echo "### $SUITE tests — $status"
  echo
  echo "| Tests | Failures | Errors | Skipped | Time |"
  echo "| ----: | -------: | -----: | ------: | ---: |"
  echo "| $tests | $failures | $errors | $skipped | ${time}s |"
} >> "${GITHUB_STEP_SUMMARY:-/dev/stdout}"

if [[ "$status" == "failed" ]]; then
  {
    echo
    echo "<details><summary>Failing tests</summary>"
    echo
    echo '```'
    grep -h -A3 '<<< \(FAILURE\|ERROR\)!' "$REPORT_DIR"/*.txt 2>/dev/null | head -60 || true
    echo '```'
    echo
    echo "</details>"
  } >> "${GITHUB_STEP_SUMMARY:-/dev/stdout}"
fi
