# HANDOFF — compact status + GUI-in-transition plan

Consolidation of the conversation for autonomous work. Full detail in `NOTES.md`,
`ARCHITECTURE.md`, `COVERAGE.md`.

## What has been achieved (verified)

- **Complete RE** of the Octatrack MKII OS 1.40C firmware: ColdFire (MCF5445x) + DSP56xxx + proprietary
  RTOS. OS format: `.bin`(ELUP, XOR+checksum) / `.syx`(SysEx) → ELEK → aPLib → MAIN OS
  (1,112,560 B @ base `0x40000400`). No cryptographic signature → patchable.
- **Toolchain**: decode (`tools/bin_decode.py`, `decode_elek.c`), Ghidra headless (Coldfire),
  radare2, **ColdFire Unicorn emulator** (`tools/emu_*.py`), DSP56300 disassembler, `m68k-elf-as`.
- **Repackaging tested**: `elektron-firmware-tool -c 3 <mainos> -o <out.syx>` → checksums ok.
- **"lazy part apply" audio patch** implemented+validated+packaged:
  `out/OCTATRACK_OS1.40C_LAZYPART.syx`. Save/restore in `FUN_40009094` (entry `0x40009094`→save_stub,
  exit tail-call `0x40009664`→restore_stub, cave `0x400d64e0`). Tracks that are sounding keep their params on
  pattern change; they apply the destination Part on the first trig (D6 gate of the frame builder `0x4000c9e2`).

## Key structures (RAM)
- `per_track_part[8]` @ `0x8000182a`, `per_track_pattern[8]` @ `0x80001832` (byte/track).
- Active Part (audio) `0x80000002`, active pattern `0x80000003`. Applied GLOBAL `0x80001828/29`.
- DISPLAYED/edited pattern (GUI) `DAT_100b14cf`; current track `DAT_100b14cc`.
- Voice buffer (params→DSP) `0x80000a50` stride `0x40`/track. Active voice `0x800049d8` stride `0xA8` byte0.
- `_DAT_46c82456` = project RAM base (part/pattern data).
- Free code cave: `0x400d64da` (5986 B; the audio patch uses from `0x400d64e0`, ~184 B → free from
  ~`0x400d65a0`). Free RAM: `0x80006a00+` (the audio patch used `0x80006a00`..`0x80006c28`).

## The encoder editor (pinned down) — `FUN_40052e98(encoder_idx 0-6, delta)`
- Reads the param's current value from the Part data (indexed by `DAT_100b14cf` + the part that uses that pattern
  + track + param), adds delta, clamps, writes it back + dirty flag `0x9b332`.
- **Gated live-update**: only updates the voice buffer (sound) IF
  `DAT_80000002==per_track_part[track] && DAT_80000003==per_track_pattern[track]`.
- Encoder 6 = LEVEL (special case 0/0x7f). Others: read-modify-write at `…0x8f3e2`.
- Param definition (min/max) via `FUN_40031f28`/`FUN_40031ee0`/`FUN_40031da4`.

## GOAL: GUI-in-transition
When a track is IN TRANSITION (`per_track_part[track] != active_Part`, and sounding), editing a knob should:
1. **Write to the SOURCE Part** (`per_track_part[track]` / `per_track_pattern[track]`), not the destination one.
2. **Update the sound live** (which today is gated OFF by `per_track != active`).
→ This way you can sculpt the transitioning sound in real time.

### Plan
1. Deep RE of `FUN_40052e98`: understand exactly which globals determine the read/write direction of the
   param, and the target of the live-update. Trace it with the emulator.
2. Design: detect transition (per_track_part[track]!=active && voice_active[track]); if in transition,
   redirect the editor's part/pattern index to `per_track_*[track]` and force the live-update gate.
3. Implement (cave + detour in `FUN_40052e98`), validate in the emulator, repackage.

### Progress — ✅ COMPLETED
- [x] Consolidated.
- [x] RE of the editor addressing (empirical trace in the emulator, `tools/emu_editor.py`).
- [x] Patch design (wrapper with return-hook: override globals→source during transition).
- [x] Implementation (`tools/patch_gui.s`, assembled with m68k-elf-as/ld @ `0x400d6600`).
- [x] Emulator validation (transition→writes source + live-update; normal path intact; globals
      restored; robustness 4 tracks × 4 encoders all ✓).
- [x] Repackaged → `out/OCTATRACK_OS1.40C_LAZYPART_GUI.syx` (checksums ok).

## ✅ CURRENT BUILD — `out/OCTATRACK_OS1.40C_FULL_MAXO_V6.syx`

Everything: lazy-part + GUI-in-transition (reentrancy-fixed) + sticky scenes v2 + dirty
indicators + MAXOLYDIAN branding. 809 patch bytes, checksums ok, round-trip identical,
reproducible from the stock `.syx` via `sysex/apply_patch.py`.

| patch | source | detour | stub |
|---|---|---|---|
| lazy part apply | `patch.s` | `0x40009094` | `0x400d64e0` |
| GUI-in-transition | `patch_gui2.s` | `0x40052e98` | `0x400d6600` |
| sticky scenes v2 | `patch_scene2.s` | `0x40009094`, `0x4003f1b4` | `0x400d6700` |
| dirty track LED | `patch_led.s` | `0x40083fb4` | `0x400d6800` |
| dirty scene trig | `patch_trig.s` | `0x40034b5e` | `0x400d6900` |

**Two corrections happened here, both documented in `NOTES.md`:**

1. **Sticky scenes v1 was wrong.** It assumed `FUN_40009094` == "pattern change"; it is
   actually `apply_part(part, pattern)` reached from 10 sites including the manual
   scene-assign path, so v1 clobbered manual assignment. v2 polls `DAT_80000003` instead
   and adopts vs imposes based on whether the index actually changed.
2. **The GUI patch crashed the unit.** Its return-hook used one global slot; a nested entry
   clobbered it and the outer return jumped to a dead address (`EXCEPTION VEC:0B`). Fixed by
   a reentrancy guard in `patch_gui2.s`.

Confirmed on hardware: lazy part, GUI-in-transition, sticky scenes v2. Pending hardware
confirmation: the two dirty indicators, and the crash fix.

## ✅ FINAL RESULT — `out/OCTATRACK_OS1.40C_LAZYPART_GUI_MAXO.syx`

Flashable firmware with the TWO behavior patches (below) **+ boot branding**: the startup
screen and SYSTEM STATUS → OS VERSION show **`MAXOLYDIAN`** instead of `1.40C`.
- The displayed version lives in the ELEK header (flash `0x4008`), a display field of **10 fixed chars**
  (`0x08–0x11`); it cannot be enlarged (the aPLib section starts at `0x12`, a hardcoded offset). That's why
  `MAXOLYDIAN` (10) and not `MAXOLYDIAN 1.40C` (16). Internal code `0178` intact. Detail in `NOTES.md`.
- Build: `elektron-firmware-tool -i <stock.syx> -c 3 out/mainos_combined.bin -V "MAXOLYDIAN" -o <out>`
  (extended the tool's `set_version()` to write the full display field from `0x08`).
- Verified: header `0x08–0x11`="MAXOLYDIAN", `0178` intact, `0x12`=NUL, checksums ok, round-trip of the
  MAIN OS byte-identical (audio+GUI patches intact).
- Variants without branding: `_GUI.syx` (audio+GUI) and `_LAZYPART.syx` (audio only).

## Previous RESULT (audio + GUI)

**`out/OCTATRACK_OS1.40C_LAZYPART_GUI.syx`** = flashable firmware with BOTH patches:
1. **Lazy part apply** (audio): tracks that are sounding keep their params when changing pattern; they apply
   the destination Part on their first trig.
2. **GUI-in-transition**: turning a knob while a track is in transition **edits the SOURCE Part
   and sculpts the transitioning sound in real time** (previously it edited the destination without sounding).

### The GUI patch (`FUN_40052e98`, the encoder editor)
- **Entry `0x40052e98` → setup** (cave `0x400d6600`): if `per_track_part[track] != active_Part`
  (transition), it saves `DAT_100b14cf`/`0x80000002`/`0x80000003`, sets them = source
  (`per_track_pattern[track]`/`per_track_part[track]`), and **hooks the return to cleanup** (return-hook,
  covers ALL exits of the editor: rts + tail-call). Executes the displaced entry, `jmp 0x40052ea0`.
- **cleanup** (`0x400d6690`): restores the 3 globals, `jmp` to the real return.
- Save area RAM: `0x80006c30` (SAVE_CF/02/03, DID_OVERRIDE, SAVE_RET). GUI cave: `0x400d6600`.
  (Does not overlap with the audio patch: cave `0x400d64e0`, RAM `0x80006a00`.)
- Key discovery (emulator): redirecting only `DAT_100b14cf` → source makes the editor read/write
  the source Part (iVar10 follows automatically); + setting `0x80000002/03` = source makes it pass the
  live-update gate. Validated byte by byte.

### Verification / how to test
- `tools/emu_editor.py`, `tools/emu_gui_concept.py`: trace the editor (concept).
- Combine+validate: the `out/mainos_combined.bin` image; validation scripts above.
- **Risk/caveat**: the gate is passed by temporarily setting `0x80000002/03` (audio active Part)
  during the edit (µs). The frame builder skips sounding non-triggered tracks (D6 gate), so
  the transitioning track is not affected; another track that trigs in that µs window could (very unlikely)
  take the wrong Part for 1 frame. Acceptable for experimental use; documented.
- **Flashing**: see `FLASHING.md` — same procedure, use `out/OCTATRACK_OS1.40C_LAZYPART_GUI.syx`.
