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

## octa-bt-pt / ems-octakit — Python image writers

Both generate a flashable image from the user's own OS copy in Python. Cross-check
their checksum/section handling against our `build_*.py` as an independent
implementation (see `container-format.md`).

_(Extend as patterns recur.)_
