#!/usr/bin/env python3
"""Tiny parser for refs/MANIFEST.toml — the fixed subset we write there.

No tomllib dependency (macOS CLT python3 may predate it). Recognises:
    [repo.<name>]
    key = "value"
Blank lines and #-comments are ignored.
"""
from __future__ import annotations
import re
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
MANIFEST = REPO_ROOT / "refs" / "MANIFEST.toml"
LOCK = REPO_ROOT / "refs" / "MANIFEST.lock"
REFS_DIR = REPO_ROOT / "refs"

_HDR = re.compile(r"^\[repo\.([A-Za-z0-9_-]+)\]\s*$")
_KV = re.compile(r'^([A-Za-z0-9_-]+)\s*=\s*"(.*)"\s*$')


def load(path: Path = MANIFEST) -> dict[str, dict[str, str]]:
    repos: dict[str, dict[str, str]] = {}
    cur: str | None = None
    for raw in path.read_text().splitlines():
        line = raw.split("#", 1)[0].rstrip() if not raw.lstrip().startswith("#") else ""
        if not line.strip():
            continue
        m = _HDR.match(line)
        if m:
            cur = m.group(1)
            repos[cur] = {}
            continue
        m = _KV.match(line.strip())
        if m and cur:
            repos[cur][m.group(1)] = m.group(2)
    return repos


if __name__ == "__main__":  # debug: print parsed manifest
    for name, meta in load().items():
        print(name, meta)
