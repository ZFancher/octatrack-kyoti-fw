#!/bin/sh
# refresh.sh — periodic upstream check for the external-RE knowledge base.
#
# SAFE to run anytime. It only FETCHES and REPORTS. It does NOT:
#   - move refs/MANIFEST.lock              (the "distilled up to here" marker)
#   - touch reference/kb/*                 (distillation is judgement work — manual)
#   - check out new commits into refs/     (that's `sync.py --update <name>`)
#
# Output: refs/.whatsnew-report.md  (gitignored). On macOS it also posts a
# notification when an upstream repo has moved past our last sync.
#
# Usage:
#   sh tools/refs/refresh.sh              # check every repo
#   sh tools/refs/refresh.sh octabam      # check one
#
# See reference/KB_REFRESH.md for the full workflow (what to do when it finds
# something).

set -eu

here=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
cd "$here"

mkdir -p refs
report="refs/.whatsnew-report.md"

{
  echo "# Upstream check — $(date '+%Y-%m-%d %H:%M %Z')"
  echo
  echo "Run \`python3 tools/refs/sync.py --update <repo>\` then re-distil into"
  echo "\`reference/kb/\` for anything relevant. See reference/KB_REFRESH.md."
  echo
  echo '```'
  python3 tools/refs/whatsnew.py --limit 20 "$@" || echo "(whatsnew.py exited non-zero)"
  echo '```'
} > "$report" 2>&1

if grep -q "new commit(s) since last sync" "$report"; then
  echo "new upstream commits — see $report"
  if command -v osascript >/dev/null 2>&1; then
    osascript -e 'display notification "Upstream RE repos have new commits — see refs/.whatsnew-report.md" with title "OT Kyoti FW — KB refresh"' >/dev/null 2>&1 || true
  fi
else
  echo "kb is current with upstream (fetched $(date '+%H:%M'))"
fi
