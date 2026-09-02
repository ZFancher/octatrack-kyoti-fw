#!/usr/bin/env python3
"""Show upstream commits newer than what we last synced, per repo.

  python3 tools/refs/whatsnew.py           # all repos
  python3 tools/refs/whatsnew.py octamax   # one

Reads the synced HEAD from refs/MANIFEST.lock and diffs it against origin/<branch>
(after a quiet fetch). Use it at the top of a cross-repo session: anything listed
is a candidate to re-distil into reference/kb/. Nothing listed = kb is current
with upstream as of the fetch.
"""
from __future__ import annotations
import re
import subprocess
import sys
from pathlib import Path

from _manifest import REFS_DIR, LOCK, load

_LOCKLINE = re.compile(r'^([A-Za-z0-9_-]+)\s*=\s*"([0-9a-f]{7,40})"')


def locked() -> dict[str, str]:
    if not LOCK.exists():
        return {}
    out = {}
    for line in LOCK.read_text().splitlines():
        m = _LOCKLINE.match(line)
        if m:
            out[m.group(1)] = m.group(2)
    return out


def git(*args: str, cwd: Path) -> str:
    return subprocess.run(
        ["git", *args], cwd=cwd, check=True, capture_output=True, text=True
    ).stdout.rstrip()


def main(argv: list[str]) -> int:
    wanted = set(a for a in argv if not a.startswith("-"))
    lock = locked()
    repos = load()
    any_new = False
    for name, meta in repos.items():
        if wanted and name not in wanted:
            continue
        dest = REFS_DIR / name
        if not (dest / ".git").exists():
            print(f"[{name}] not cloned — run tools/refs/sync.py")
            continue
        branch = meta.get("branch", "HEAD")
        git("fetch", "--quiet", "origin", cwd=dest)
        base = lock.get(name)
        if not base:
            print(f"[{name}] no lock entry — run tools/refs/sync.py")
            continue
        log = git(
            "log", "--oneline", "--no-decorate", f"{base}..origin/{branch}", cwd=dest
        )
        if log:
            any_new = True
            print(f"\n[{name}]  {len(log.splitlines())} new commit(s) since last sync:")
            print(log)
        else:
            print(f"[{name}] up to date")
    if any_new:
        print("\n-> re-sync (tools/refs/sync.py) and re-distil the affected reference/kb/ files.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
