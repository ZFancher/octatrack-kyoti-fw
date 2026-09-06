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

### octabam `emu_rtos.py` — full-firmware emulator (route A)

> source: `refs/octabam/docs/RTOS_FORK.md` @ `2f241e1` (2026-09-06)

Runs **the firmware's own scheduler**: the handoff trap, PIT0 ticking, all eleven
tasks, tasks posting to each other through the kernel, a real CompactFlash mount,
a real `LOAD PROJECT`, and the sequencer stepping a saved bank end-to-end (a step
trig fires at the right frame). Interrupts are modelled (INTC0/INTC1, forced
sources, ATA, PITs), so it shows *which task runs when* — key press → engine,
engine → DSP task, recorder-arm stage-then-promote. What it does **not** do: audio
(DSP stays in `dsp_host`).

**This is the tool NOTES L413 asked for** ("locate the knob→param editor … via
dynamic analysis … the emulator we have is for the DSP, no use here"). For the
Session-13 p-lock work: drive a `[NO]`+knob event in LIVE REC and watch what
touches `[0x46c82456] + pat*0x18b2`. Kernel/task/interrupt map is in
`memory-map.md` "Kernel / RTOS". Not yet vendored — porting it is its own task
(brings octabam's kernel + card models); until then it's a reference.

### octabam `ot_project.py` — on-disk bank/project editor + differ

`pattern-trig` (write a step trig on disk), **`pattern-diff <A> <B> <bank>`**
(diff every step-mask between two saved projects → prints mask offset + changed
steps), `set_track_slot`, `set_machine_type`, `part-name`, `rigproj`/`stamp-defaults`.
`pattern-diff` turns "which bit is a recorder trig / trigless lock" into a
30-second hardware measurement — the Phase-0 lever for `file-format.md`'s p-lock map.

### Cave placement — the OS `.bss` tail is not free

> source: `refs/octabam/docs/FAILURE_MODES.md` @ `2f241e1` ("Line-F exception on [PROJECT]")

octabam pinned a cave at `0x40108800` (inside the OS image's last ~30 KB). A
static zero-check and a no-project emulator boot both passed — but that zero run
is **uninitialised OS data** (the PROJECT subsystem's RAM), and hitting PROJECT
threw a line-F exception. Their rule: caves live in **`0x400d2000..0x400d8000`**
(`SAFE_CAVE_CEIL = 0x400d8000`). Our builds already assert `< 0x400d7c3c` and sit
at `0x400d7400`+ — safely inside. Do not chase more cave space in the `0x4010xxxx`
tail.

## octamax (upstream) — the pipeline we inherited

`sysex/apply_patch.py`, `tools/build.py`, the code-cave detour method, the
PERSONALIZE-menu mapping. Track `whatsnew.py octamax` for new mods / newly named
functions to fold back.

### `emu_check.py` — Unicorn pre-flash gate (octamax `ec510e1`)

Unit-emulates individual firmware routines and **diffs a PATCHED image against
pristine STOCK** — "does this modified routine compute the same register/memory
effects as stock?". Catches the neutral-change / relocation-bug class without
flashing. Same idea as our per-feature `tools/emu_*.py`, but structured as a
reusable `CHECKS` list with a shared `Emu.call(entry, regs=, mem=, max_insn=)`
harness. Also `hookcheck` (`920c5c6`) — "fixed five analysis tools that gave
confident wrong answers", a caution worth heeding for our own emu scripts.
**Worth adopting the diff-vs-stock structure** if our emu scripts proliferate.

### PERSONALIZE persistence — the 'ANDY' battery-SRAM shadow (octamax `c78ff70`)

`0x800000xx` is volatile (boot re-images it). A toggle that must survive a power
cycle writes a shadow into the checksummed `'ANDY'` block at `0x100fff00` and the
build extends the block-restore `memcpy` length. Full mechanism + addresses in
`memory-map.md` "PERSONALIZE settings — persistence"; our port is Session 19
(`tools/patch_mutemode.s` + `build_mutemode.py`).

### OCTAMAX 2.x (`6ba0284`, Sep 2026) — not adopted, noted

Slice-playhead SRC>SLICES view (`patch_sliceview.s`), **dual-256 sample slots**
(a large DDR-relocation effort — `DUAL256.md`, "static pool reclaim"), persistent
PERSONALIZE toggles. The dual-256 relocation techniques (whole-block move of
operand refs only, combined-loop trampolines, boot-zero of the relocated region)
are the reference if we ever need to grow a stock table.

## Adding a whole menu SCREEN — the menu-state table (octabam)

> source: `refs/octabam/docs/MAINMENU.md` §9a @ `2f241e1` (2026-09-06)

For a new *screen* (not a PERSONALIZE row), octabam's "bus screen" grows the
**16-entry menu-state table at `0x400cbdac`** (stride `0x14`, entries
`{on_enter, on_exit, draw, key_handler, encoder_handler}`; a NULL member is
skipped `tstl %a0/beqs/jsr %a0@`). It's named by exactly **three `lea` immediates**
(`0x40064bd2`, `0x40064e34`, `0x400650e6`) and no pointer cell — so the same
relocate-and-repoint move as our PERSONALIZE arrays: copy `16*0x14` to a cave,
append a 17th, patch 3 operands, then point a menu action at state 17. Row
`+0x08` (menu-tree table `0x400cbcac`, separate) doubles as the selectable marker
(`0x40064f0a` / `0x40064fe8` skip rows with `+0x08 == 0`). This is the
lower-risk path when a feature needs its own page rather than a toggle.

## PERSONALIZE-menu entry recipe (ours, consolidated)

Used for MUTE MODE (`tools/patch_mutemode.s`) and DIRECT JUMP
(`tools/patch_directjump.s`):

1. Relocate the menu's 3 parallel arrays (labels / value-tables / handlers) to a cave.
2. Bump the item count (`moveq #15` → `#16`; note the MKI/MKII `0x46c8d18c` probe
   that makes it 15 vs 16 — patch the post-probe constant).
3. Splice the new entry at the chosen index.
4. State goes in a free work-RAM word `0x800000d4..df` — see `memory-map.md`
   "PERSONALIZE settings — persistence". **These words are volatile**: to survive
   a power cycle the setter must also write the `'ANDY'` shadow (`0x100fff00 +
   word − 0x80000070`) and the build must extend the 3 restore `pea 0x64` → `0x70`.
   MUTE MODE does this since Session 19; DIRECT JUMP (`0x800000a8`) does not yet.
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
