# START HERE — onboarding for a new session

Octatrack (OS 1.40C) firmware reverse-engineering + small optional behaviour patches.
This file is the stable entry point. Read it, then the pointers it names. Keep it short;
update the **Current frontier** section at the end of each session.

---

## 0. What loads automatically

Running Claude on this Mac auto-loads the project memory
(`~/.claude/projects/-Users-kyoti-m4/memory/octamax-re-project.md`) — a per-session
digest of state. Treat it as the summary; this repo's docs are the detail.

Local repo: `~/Documents/octatrack-kyoti-fw/` (was `~/Documents/octamax/` until
2026-09-01). Published as <https://github.com/ZFancher/octatrack-kyoti-fw>, a fork
of `mxldyn/octamax`. Remotes: `origin` = your fork, `upstream` = mxldyn (fetch only).

## 1. Read order for a new chat

1. **This file** (constraints + current frontier).
2. **`NOTES.md`** — the RE log. **Do not read top-to-bottom** (it starts at 2026-07 recon).
   Jump to the newest `## Session N` section and the most recent
   `### … STATE OF PLAY` / `### NEXT …` blocks. Section index: `grep -nE '^## ' NOTES.md`.
3. **Task-specific docs** from the map below.
4. Only then open `tools/*` and `out/ghidra/*` for the subsystem in question.

## 2. Document map — what each file is for

| File | Use it for |
|---|---|
| `START_HERE.md` | this — onboarding + current frontier |
| `NOTES.md` | the full chronological RE log; every finding, every session, every dead end |
| `reference/kb/*.md` | **distilled knowledge base** — address map + file format + DSP + container + techniques, ours merged with external RE. Read the relevant one before a new patch |
| `reference/EXTERNAL_RESEARCH.md` | index of the 6 external OT-RE repos + the sync/distill workflow (`tools/refs/`) |
| `README.md` | project intent, repo layout, and the **build/flash recipe** (§"Building a `.syx` or `.bin`") |
| `COVERAGE.md` | what firmware subsystems are mapped vs untouched; the DSP-is-a-separate-blob caveat |
| `ARCHITECTURE.md` | memory map, container format, boot/upgrade chain |
| `FLASHING.md` | step-by-step flashing (MIDI + CF card) and the per-feature hardware test procedures |
| `DESIGN_BANKPAGE.md` | design notes for the (shelved) live bank-paging feature |
| `HANDOFF.md` | the shipped LED / encoder "dirty indicator" patches — a *separate* line of work (self-marked historical; `NOTES.md` is the current reference) |

## 3. Hard constraints (do not relearn these the hard way)

- **Hardware = Octatrack MKI only.** The user does not own a MKII. Stock 1.40C is one image
  for both; the boot `0x46c8d18c` probe adapts it (e.g. the MKI shows 15 PERSONALIZE items,
  no `LED BRIGHTNESS`). Earlier notes that said "MKII" were wrong and are corrected.
- **Test data**: only ever use real hardware-exported project files. Never fabricate or
  hand-edit a `.work`/bank/project blob. If a run needs test banks, ask the user to export.
- **macOS TCC**: `~/Documents` is protected; the responsible binary is the Anthropic `claude`
  binary, **not** VS Code. Grant Full Disk Access to
  `~/.vscode/extensions/anthropic.claude-code-*/resources/native-binary/claude`
  (re-add after each extension update) or run `claude` from Terminal.app.
- **Builds are guarded binary patches**, never hand-assembled images: every splice asserts the
  stock bytes it overwrites, asserts caves are free / non-overlapping / within the free zone,
  and round-trips through Elektron's own firmware tool. Keep it that way.
- "Corruption" in the notes = **Ghidra failing to decompile** dense ColdFire functions
  (`halt_baddata()` markers), a readability limit — *not* corruption in the OS or our output.

## 4. Toolchain entry points

| Need | Command |
|---|---|
| decompressed stock image | `out/raw/section_3_MAIN_OS.bin` (base `0x40000400`); regen via `./fetch-os.sh && ./analyze.sh` |
| disassemble | `./disasm.sh` (r2, m68k BE, base wired) — note r2 mis-decodes some ColdFire ops; prefer Ghidra |
| Ghidra headless | JDK 21 + Ghidra 12.1.2 paths in the memory file; project `ghidra_project octamax`, `-process section_3_MAIN_OS.bin -noanalysis`; one-shot probe scripts in `tools/ghidra/attic/*.java` (see `tools/ghidra/README.md`), dumps land in `out/ghidra/` |
| CPU emulation | `tools/emu_*.py` (Unicorn, runs the real image bytes) — one per feature |
| build a flashable | `tools/build_*.py` → `.syx` (MIDI) + `.bin` (CF card); see README §"Building" |
| external RE research | `python3 tools/refs/sync.py` (clone/refresh the 6 repos into `refs/`, gitignored) · `python3 tools/refs/whatsnew.py` (what changed upstream → re-distil into `reference/kb/`) |

## 5. Shipped / in-flight work (2026-09-01)

Two branches: **`main`** ships only what has been on hardware; **`wip/mute-mode`**
carries the untested continuation (solo, DT). `octamax-main` tracks upstream.

| Thread | State |
|---|---|
| **Bug 1** — Plays-Free MIDI manual-trig stall | **FIXED + HW-confirmed on MKI.** `tools/patch_trigscale.s`, always-on. In every build. |
| **Bug 2** — MIDI LFO SETUP knobs send CC on the twin audio channel | Emulation says **likely already fixed in 1.40C**; awaiting HW confirmation. `tools/emu_lfocc.py`. |
| **MUTE MODE** PERSONALIZE toggle (`main`) | `tools/patch_mutemode.s`, values `OT / OT+FX`. Menu surgery HW-confirmed (Session 10). |
| ↳ **OT+FX** soft mute — dry cuts fast+clean, FX inserts ring (`main`) | `patch_softmute.s` **V6b** (V6 mechanism + the Session-10 gate/frame fixes). **Flashed on MKI, works.** `python3 tools/build_mutemode.py`. |
| ↳ **OT+FX for SOLO** (non-soloed tracks keep FX tails) | `patch_softmute.s` **V7**, **`wip/mute-mode` only** — emulator-verified (`emu_solo.py`), never flashed. |
| ↳ **DT** (Digitakt-style pure sequencer mute) | **`wip/mute-mode` only** — emulator-verified (`emu_dt.py`, `build_mutemode_dt.py`), never flashed. |
| Maxolydian mods (branding, no BANK/PTN countdown, lazy Part transitions, arp key-scales, LED dirty indicators) | Not in the KYOTI builds. `tools/build.py` / `sysex/`; see `CREDITS.md`. |

Build outputs on `main` (all `140C_KYOTI`, all carry the Bug-1 fix):
- `build_trigscale_only.py` → `out/OCTATRACK_*PLAYSFREEFIX.*` — Bug-1 fix only, version stays `1.40C`.
- `build_mutemode.py` → `out/OCTATRACK_*MUTEMODE.*` — Bug-1 fix + MUTE MODE (`OT` / `OT+FX`). **The flashed line.**
- `build_softmute.py` → `out/OCTATRACK_*SOFTMUTE_PFFIX.*` — Bug-1 fix + V6b soft mute ALWAYS ON (no menu).

---

## 6. Current frontier — UPDATE THIS EACH SESSION

**As of 2026-09-01 (post-Session-13, repo published):**

- Repo published as `github.com/ZFancher/octatrack-kyoti-fw`. **`main` was trimmed to
  ship only hardware-tested tooling**: Bug-1 fix + MUTE MODE `OT`/`OT+FX` (V6b). The V7
  solo rewrite and DT mode moved to **`wip/mute-mode`** (pushed, emulator-verified only).
- The user is **away from the MKI for ~2 weeks** — build + emulate only, no flashing.
- To resume the untested work: `git checkout wip/mute-mode`. Open risks there:
  - **DT**: whether the DSP keeps advancing a plain FLEX one-shot's amp envelope while the
    frame words flow untouched (should — `FUN_40004db8` is downstream of the voice updater).
    Fallback: also clear the `46c7ff64` output-mute bit for DT tracks.
  - **solo**: the V7 "silenced set" path — `NOTES.md` "Session 11 → NEXT".
- Hardware test checklists waiting (on `wip/mute-mode`): `NOTES.md` "Session 12 → NEXT" (DT),
  "Session 11 → NEXT" (OT+FX solo). "Session 10 → NEXT" (menu + persistence) applies to `main`.
- Not started: any refinement past DT; DSP56300 extraction (a separate project — see
  `COVERAGE.md` and `sambanks/octabam` in `CREDITS.md`).

**Next likely tasks:** more mute-behaviour ideas from the user; or unrelated RE. Point the
new chat here first, then the relevant `NOTES.md` Session section.

**Backlog idea (scoped, not started):** auto-remove a trigless lock once a LIVE-REC
`[NO]`+knob erase clears its last p-lock. Feasible, ~3-5 sessions, needs the (unmapped)
per-step trig/p-lock data model first. Full brief: `NOTES.md` "Session 13 — SCOPING ONLY".
