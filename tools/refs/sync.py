#!/usr/bin/env python3
"""Clone/fetch every repo in refs/MANIFEST.toml into refs/<name>/ and record the
resolved commit hashes in refs/MANIFEST.lock.

  python3 tools/refs/sync.py            # sync all to their pinned commit
  python3 tools/refs/sync.py OctaLib    # just one
  python3 tools/refs/sync.py --update   # ignore pins, check out each branch tip,
                                        # record the new hashes in MANIFEST.lock

A repo with pin = "HEAD" always tracks its branch tip. To freeze one, put a real
commit hash in its `pin` field in MANIFEST.toml.

The clones are a disposable cache: gitignored, never committed. Distil anything
useful into reference/kb/ with attribution + the commit hash from the lock file.
"""
from __future__ import annotations
import subprocess
import sys
import datetime as _dt
from pathlib import Path

from _manifest import REFS_DIR, LOCK, MANIFEST, load


def git(*args: str, cwd: Path | None = None) -> str:
    return subprocess.run(
        ["git", *args], cwd=cwd, check=True, capture_output=True, text=True
    ).stdout.strip()


def _rev_exists(cwd: Path, rev: str) -> bool:
    return subprocess.run(
        ["git", "rev-parse", "--verify", "--quiet", rev], cwd=cwd, capture_output=True
    ).returncode == 0


def sync_one(name: str, meta: dict[str, str], *, update: bool) -> tuple[str, str]:
    dest = REFS_DIR / name
    url, branch, pin = meta["url"], meta.get("branch", "HEAD"), meta.get("pin", "HEAD")
    if not (dest / ".git").exists():
        print(f"  clone {name} <- {url}")
        git("clone", "--quiet", url, str(dest))
    print(f"  fetch {name}")
    git("fetch", "--quiet", "--tags", "origin", cwd=dest)

    # verify the configured branch exists; fall back to the remote's default HEAD
    if not _rev_exists(dest, f"origin/{branch}"):
        git("remote", "set-head", "origin", "--auto", cwd=dest)
        branch = git("symbolic-ref", "--short", "refs/remotes/origin/HEAD", cwd=dest).split("/")[-1]
        print(f"  (branch fallback -> {branch})")

    target = f"origin/{branch}" if (update or pin == "HEAD") else pin
    git("checkout", "--quiet", "--detach", target, cwd=dest)
    head = git("rev-parse", "HEAD", cwd=dest)
    subject = git("log", "-1", "--format=%s", cwd=dest)
    return head, subject


def main(argv: list[str]) -> int:
    update = "--update" in argv
    wanted = [a for a in argv if not a.startswith("-")]
    repos = load()
    if wanted:
        repos = {k: v for k, v in repos.items() if k in wanted}
        if not repos:
            print(f"no such repo(s): {wanted}", file=sys.stderr)
            return 2

    REFS_DIR.mkdir(exist_ok=True)
    lock_lines = [
        "# Resolved by tools/refs/sync.py — do not edit by hand.",
        f"# last sync: {_dt.datetime.now().isoformat(timespec='seconds')}",
        "",
    ]
    for name, meta in load().items():  # keep lock ordered like the manifest
        if name not in repos:
            prev = _prev_lock_line(name)  # untouched repo: keep its old lock line
            if prev:
                lock_lines.append(prev)
            continue
        print(f"[{name}]")
        head, subject = sync_one(name, meta, update=update)
        lock_lines.append(f'{name} = "{head}"  # {subject[:70]}')

    LOCK.write_text("\n".join(lock_lines) + "\n")
    print(f"\nwrote {LOCK.relative_to(REFS_DIR.parent)}")
    return 0


def _prev_lock_line(name: str) -> str | None:
    if not LOCK.exists():
        return None
    for line in LOCK.read_text().splitlines():
        if line.startswith(f"{name} ="):
            return line
    return None


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
