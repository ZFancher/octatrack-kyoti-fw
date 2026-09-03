# External Octatrack RE research — index

Prior art we mine so we don't rebuild the wheel. See also [`CREDITS.md`](../CREDITS.md)
(lineage + legal) and [`COVERAGE.md`](../COVERAGE.md) (what this repo has vs hasn't mapped).

## How this works

```
refs/MANIFEST.toml      the six repos + one line each on what to mine
refs/MANIFEST.lock      the exact commit last synced (tracked)
refs/<name>/            local clone — GITIGNORED, disposable cache, never committed
tools/refs/sync.py      clone/fetch all to the pinned commit, rewrite the lock
tools/refs/whatsnew.py  list upstream commits newer than the lock -> what to re-distil
reference/kb/*.md       the durable asset: distilled, address-keyed, attributed
reference/UPSTREAM_INBOX.md   dated triage list of upstream changes not yet distilled
```

**Never commit anything under `refs/` except the two manifest files** — mixed and
absent licenses, and this project redistributes no third-party material.

### Session workflow

1. Doing cross-repo work? `python3 tools/refs/whatsnew.py` — anything listed is a
   candidate to re-distil.
2. Need a repo's detail? It's already checked out under `refs/<name>/` at the
   locked commit — `rg` it directly.
3. Found something reusable? Add it to the right `reference/kb/*.md` with
   **source repo + file + commit hash (from `MANIFEST.lock`) + date**. Same
   attribution discipline as the memory files.
4. First sync on a new machine: `python3 tools/refs/sync.py`.

Forum threads (Elektronauts etc.) can't be synced — `WebFetch` the specific
thread on demand and distil the finding into `kb/` with the URL + retrieval date.

## The repos

| Repo | Side | Mine it for |
|---|---|---|
| [mxldyn/octamax](https://github.com/mxldyn/octamax) | ColdFire | Upstream of this fork. Container/update-chain analysis, the patch/build pipeline, PERSONALIZE-menu map, first behaviour mods. Track for new mods + newly named functions. |
| [mischa85/elektron-firmware-tool](https://github.com/mischa85/elektron-firmware-tool) | tooling | ELEK container pack/unpack, aPLib, `.bin`/`.syx` transports. Vendored separately in `vendor/`; kb needs the format notes + any format fixes. → [`kb/container-format.md`](kb/container-format.md) |
| [snugsound/OctaLib](https://github.com/snugsound/OctaLib) | file-format | on-CF project/bank/part/arrangement struct layouts. Directly feeds the unmapped per-step trig / p-lock / sample-lock model (NOTES Session 13 backlog). → [`kb/file-format.md`](kb/file-format.md) |
| [emuyia/ems-octakit](https://github.com/emuyia/ems-octakit) | file-format | **Closed-source** — repo is README + issue templates only; patcher runs in-browser, unpublished. Swaps 4 Parts/Bank for 256 Kits/Project. Only the behavioural description is usable (no code/offsets). → [`kb/file-format.md`](kb/file-format.md) |
| [bryantysinger/octa-bt-pt](https://github.com/bryantysinger/octa-bt-pt) | ColdFire + DSP | Parameter-default patch tool (OS 1.40C, Python/Streamlit). **Distilled 2026-09-02:** full FX/machine descriptor table (`0x400d2fe4`–`0x400d5e04`), effect id codes, DSP module-map parser + AMF `mpysu`→`mpyuu` bug fix, ELUP cipher constants. → [`kb/memory-map.md`](kb/memory-map.md), [`kb/dsp56300.md`](kb/dsp56300.md), [`kb/container-format.md`](kb/container-format.md) |
| [sambanks/octabam](https://github.com/sambanks/octabam) | DSP | Adds DSP56300 effects to the MKII by patching new DSP assembly + a Python build system. The DSP side `COVERAGE.md` flags as out of scope here — mapped, not patched. → [`kb/dsp56300.md`](kb/dsp56300.md) |

## Licence posture

Each repo keeps its own terms. octamax ships no `LICENSE` (this fork exists under
GitHub's ToS, educational use only, per `CREDITS.md`). octabam / ems-octakit /
octa-bt-pt each carry their own — check `refs/<name>/LICENSE` before quoting more
than a fact. We store **findings and small factual excerpts** in `kb/`, never
wholesale copies of their source.
