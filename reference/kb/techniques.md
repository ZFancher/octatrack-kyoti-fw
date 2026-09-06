# Techniques worth borrowing — patch method, menu recipe, build pipeline

Cross-project method notes. Our own approach is in `README.md` §"Building" +
`START_HERE.md` §3; this file records what the other repos do differently and
what's worth adopting.

---

## octabam — module / remix build system

> source: `refs/octabam/README.md`, `docs/TOOLING.md`, `docs/HARNESS.md` @ `e1dcfa9` · fetched 2026-09-02

- **Module = one contribution; remix = a named selection composed into one image.**
  `modules/*/manifest.py` *is* the registry — adding a module is adding a directory.
- The build **refuses to start if two selected modules collide** — same FX id,
  ColdFire cave, hook site, private word, or buffer region — and names both.
  We do the equivalent with per-splice assertions (stock-byte check, cave
  free/non-overlap/in-range); octabam's *named registry of reserved regions* is
  the idea worth stealing if our patch count keeps growing (we're at ~6:
  trigscale, softmute, mutemode, directjump, arp, led…).
- Live "budget" reporting (free program words / spare cycles) printed by the build.
- octabam is **Claude-Code-developed too** (`refs/octabam/CLAUDE.md`) — its doc
  discipline (per-fact confidence markers, retracted values kept visible) is the
  same style as our `COVERAGE.md`; worth mirroring.

### Shared lineage — octabam ⇄ octamax ⇄ this repo

`DESIGN_BANKPAGE.md` exists in all three; octabam's `docs/history/{NOTES,COVERAGE}.md`
read like ancestors of ours. **octabam's ColdFire function names are directly
comparable to ours** — when RE'ing a new ColdFire area, grep `refs/octabam/docs/`
for the `FUN_4000xxxx` first, it may already be named and explained.

First sweep (2026-09-02): of ~140 `FUN_40xxxxxx` in octabam's docs, 27 are ones
we hadn't recorded — mostly the **SETTINGS-tree menu / screen-drawing** cluster
(`FUN_40064908` draw, `FUN_40064c18` open, `FUN_4005578c` key dispatch,
`FUN_40012bd8` text primitive) plus part/track teardown. Folded the menu cluster
into `memory-map.md` "UI / menu"; the rest listed there under "To import next".
octabam also gives the full `FUN_4006d57c` confirm-popup signature we use for
PERSONALIZE entries.

## octamax (upstream) — the pipeline we inherited

`sysex/apply_patch.py`, `tools/build.py`, the code-cave detour method, the
PERSONALIZE-menu mapping. Track `whatsnew.py octamax` for new mods / newly named
functions to fold back.

## PERSONALIZE-menu entry recipe (ours, consolidated)

Used for MUTE MODE (`tools/patch_mutemode.s`) and DIRECT JUMP
(`tools/patch_directjump.s`):

1. Relocate the menu's 3 parallel arrays (labels / value-tables / handlers) to a cave.
2. Bump the item count (`moveq #15` → `#16`; note the MKI/MKII `0x46c8d18c` probe
   that makes it 15 vs 16 — patch the post-probe constant).
3. Splice the new entry at the chosen index.
4. State goes in a free work-RAM word — see `memory-map.md` "Free scratch words"
   (`0x800000d4/d8/dc` still free; `0x800000a8` taken by DIRECT JUMP).
5. Dialog construction via `FUN_4006d57c`.

## octa-bt-pt — Python image writer

Generates a flashable image from the user's own OS copy in Python. Cross-check its
checksum/section handling against our `build_*.py` as an independent
implementation (see `container-format.md`).

## ems-octakit — guarded patch recipe as data (open-sourced 2026-09)

> source: `refs/ems-octakit/{build.py,runtime/firmware.json,patcher/}` @ `ca3b527`

Same "bring your own OS, ship no binary" stance as us, but the patch set is a
**declarative recipe** (`runtime/firmware.json`), not code:

- **598 patch sites**, each `{offset, length, sha256, writes:[{offset,data}]}` —
  the `sha256` guards the stock bytes exactly like our per-splice assert, but
  machine-checkable and enumerable. Our asserts are inline in `patch_*.s`.
- **411 relocation ops** (`m68k-relocate` / `stock-copy`) rebuild the appended
  runtime from the user's stock image — every output byte is classified by origin
  (`sparse-public-write-v3`), so the repo provably contains no official code.
- Toolchain pinned in the recipe: `m68k-elf-gcc 16.1.0 -mcfv4e -Os`, Rust 1.97.1.
- Worth stealing if our patch count keeps growing: a single JSON manifest of
  `{addr, stock-sha, replacement}` that `build_*.py` consumes, instead of the
  guard logic living in each `.s`. Cross-refs the octabam "named registry of
  reserved regions" idea (above).

Full address map from its `abi.inc` → [`kb/octakit-abi.md`](octakit-abi.md).

_(Extend as patterns recur.)_
