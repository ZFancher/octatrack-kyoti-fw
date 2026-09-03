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
| `README.md` | project intent, repo layout, and the **build/flash recipe** (§"Building a `.syx` or `.bin`") |
| `COVERAGE.md` | what firmware subsystems are mapped vs untouched; the DSP-is-a-separate-blob caveat |
| `ARCHITECTURE.md` | memory map, container format, boot/upgrade chain |
| `FLASHING.md` | step-by-step flashing (MIDI + CF card) and the per-feature hardware test procedures |
| `DESIGN_BANKPAGE.md` | design notes for the (shelved) live bank-paging feature |
| `HANDOFF.md` | the shipped LED / encoder "dirty indicator" patches — a *separate* line of work |
| `octamax_handoff_{5,6,7}.md` | frozen distilled briefs from earlier sessions (7 = the MIDI trig bug, pre-Session-9) |

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

## 5. Shipped / in-flight work (2026-09-01)

| Thread | State |
|---|---|
| **Bug 1** — Plays-Free MIDI manual-trig stall | **FIXED + HW-confirmed on MKI.** `tools/patch_trigscale.s`, always-on. Carried into every build below. |
| **Bug 2** — MIDI LFO SETUP knobs send CC on the twin audio channel | Emulation says **likely already fixed in 1.40C**; awaiting HW confirmation. `tools/emu_lfocc.py`. |
| **MUTE MODE** PERSONALIZE toggle | Menu entry added (`tools/patch_mutemode.s`), values `OT / OT+FX / DT`. |
| ↳ **OT+FX** (soft mute: dry cuts fast+clean, FX inserts ring; extended to SOLO) | `patch_softmute.s` V7. **Session 10 build flashed on MKI, works.** V7 (solo) built, not yet flashed. |
| ↳ **DT** (Digitakt-style pure sequencer mute: sounding voice rides its own amp env, only new trigs suppressed) | Built + emu-verified (`tools/emu_dt.py`), **not flashed**. Build: `python3 tools/build_mutemode_dt.py` → `out/OCTATRACK_*MUTEMODE_DT.*`. |
| Older shipped mods (branding, no BANK/PTN countdown, lazy Part transitions, arp key-scales, LED dirty indicators) | See `NOTES.md` + `HANDOFF.md`; live in `tools/build.py` / `sysex/`. |

Build outputs (all `140C_KYOTI`, all carry Bug-1 fix):
- `out/OCTATRACK_*MUTEMODE.*` — OT / OT+FX (2-mode). **User has flashed this line.**
- `out/OCTATRACK_*MUTEMODE_DT.*` — OT / OT+FX / DT (3-mode). Separate files; not flashed.
- `build_mutemode.py` and `build_mutemode_dt.py` produce byte-identical OT/OT+FX code
  (DT additions are `.ifdef DT_MODE`).

---

## 6. Current frontier — UPDATE THIS EACH SESSION

**As of 2026-09-01 (Session 12):**

- The user is **away from the MKI for ~2 weeks** — build + emulate only, no flashing.
- **DT mute mode** is built and emulator-clean but unproven on hardware. The open risk:
  whether the DSP keeps advancing a plain FLEX one-shot's amp envelope while the frame
  words flow untouched (should — `FUN_40004db8` is downstream of the voice updater).
  Fallback if not: also clear the `46c7ff64` output-mute bit for DT tracks.
- Hardware test checklists waiting: `NOTES.md` "Session 12 → NEXT" (DT), plus the still-open
  "Session 11 → NEXT" (OT+FX solo) and "Session 10 → NEXT" (MUTE MODE menu + persistence).
- Not started: any refinement past DT; DSP56300 extraction (would be a separate project —
  see `COVERAGE.md`).

**Session 14 (2026-09-01, `wip/mute-mode`, RE only):** scoped a **4th MUTE MODE** — instant
dry cut + FX tails + *resume at playhead* (stock-OT unmute). `FUN_40004db8` fully disassembled
(`tools/emu_otfx.py`, ALL GOOD): its only mute lever is zeroing the post-FX MAIN word; no
pre-FX voice control there. Candidate = keep MAIN word open (V6 D5-trick) + force the
per-voice pre-FX amp array `0x46c7ff42[t]` to 0 (filled every frame by `FUN_4000d16c` at
`0x4000d36e`), voice struct untouched. Two HW-only unknowns (is `0x46c7ff42` pre-FX? does the
DSP keep advancing a 0-amp voice?). **DT rests on the same 2nd unknown — flash DT first to
settle it, then build this.** Rename on landing: `OT / OT+FX (new) / OT+FX TRIG / DT`.
Full writeup: `NOTES.md` "Session 14".

**Session 15 (2026-09-02, `wip/mute-mode`, RE only):** feasibility of an Elektron-style
**DIRECT JUMP** pattern-change mode (switch immediately, keep the step position, load the
new Part at once). **Verdict: FEASIBLE, medium effort**, entirely in the mapped sequencer
engine. Found the cue choke point `FUN_400a0570` (pending pattern → `DAT_800065bf/c0`), the
master step counter `_DAT_800065b4` (zeroed in every one of `FUN_400a1eea`'s 3 pattern-reload
blocks), and pattern length `_DAT_800065b6`. Design = 1 detour on `FUN_400a0570` (arm an
immediate switch) + a LAZY-PART-style save/restore-with-modulo stub around the reload block,
plus a NORMAL/DIRECT JUMP PERSONALIZE toggle (MUTE MODE template). Full writeup + function
map + open items: `NOTES.md` "Session 15".

**Session 15 continued:** mapped the real per-step pattern switch in `FUN_400a1eea` at the
instruction level (commit at `0x400a44d0`; master step `DAT_800065b6`; CHAIN-AFTER gate; MIDI
Program Change = `FUN_4009e884`, sent 2 steps early). **DIRECT JUMP BUILT (S1+S2+S3, NOT
FLASHED):** `tools/patch_directjump.s` (menu `DIRECT JUMP : OFF/ON` in free word `0x800000a8`
+ 3 hooks — arm/PC-send `0x400a4006`, gate-bypass `0x400a42fa`, playhead-resume `0x400a4840`)
+ `tools/build_directjump.py` → `out/OCTATRACK_OS1.40C_DIRECTJUMP.{syx,bin}`, 684 B vs stock.
`tools/emu_directjump.py` ALL GOOD (stubs in isolation). Behaviour: a manually cued pattern
switches on the next step tick, keeps the playhead position, loads the new Part at once, PC
~1 step ahead; arranger/chain untouched. Full writeup + HW-only unknowns in `NOTES.md`
"Session 15 continued" → "S2 + S3 BUILT".

**Next likely tasks:** HW-flash DIRECT JUMP (5 unknowns listed in NOTES); flash DT (Session
14's key unknown), then the 4th mute mode; more ideas; or unrelated RE. Point the new chat
here first, then the relevant `NOTES.md` Session section.

**Backlog idea (scoped, not started):** auto-remove a trigless lock once a LIVE-REC
`[NO]`+knob erase clears its last p-lock. Feasible, ~3-5 sessions, needs the (unmapped)
per-step trig/p-lock data model first. Full brief: `NOTES.md` "Session 13 — SCOPING ONLY".
