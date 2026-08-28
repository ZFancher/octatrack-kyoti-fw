# Octatrack MIDI Manual-Trig Bug — Handoff 7 (session 6 → session 7, Claude Code)

## What this is

Reverse-engineering the Elektron Octatrack MKII firmware (OS 1.40C) to root-cause and fix a
real, hardware-reproducible bug: **a Plays-Free MIDI track with trig quantization "Direct" and
the pattern's scale set to "Per Track" stalls after its first step when manually triggered** —
step 1's note fires, step 2's never does. Same investigation as handoffs 1–6.

**Session 6 result: the fix is written, built into two flashable firmware images, and validated
in the ColdFire emulator.**

**Session 7 update (2026-08-28): DONE. Build A (`OCTATRACK_OS1.40C_PLAYSFREEFIX`, stock 1.40C +
fix) was flashed to a real Octatrack MKI — it boots normally, OS version still reads `1.40C`,
and the manual-trig stall is gone with no regression. The fix is hardware-confirmed.** Only
Build A has been flashed; Build B (`_MAXO_R13`) carries the identical fix but the R13 bundle
has not been re-flashed since `patch_trigscale` was added. The §6 debugging playbook stays in
place should Build B ever misbehave.

## Repo & environment

Working dir: `~/Documents/octamax/` on the user's Apple-Silicon Mac (OS user `kyoti_m4`).

- **Ghidra**: Homebrew `/opt/homebrew/Cellar/ghidra/12.1.2/`. JDK:
  `/opt/homebrew/Cellar/openjdk@21/21.0.12/libexec/openjdk.jdk/Contents/Home`.
  Headless (≈4 s):
  ```
  export JAVA_HOME=/opt/homebrew/Cellar/openjdk@21/21.0.12/libexec/openjdk.jdk/Contents/Home
  export PATH="$JAVA_HOME/bin:$PATH"
  /opt/homebrew/Cellar/ghidra/12.1.2/libexec/support/analyzeHeadless \
    ~/Documents/octamax/ghidra_project octamax -process "section_3_MAIN_OS.bin" -noanalysis \
    -scriptPath ~/Documents/octamax/tools -postScript GhidraResolveNN.java
  ```
- **Assembler**: `m68k-elf-as` / `-ld` / `-objcopy` in `/opt/homebrew/bin`, target `-mcpu=5407`
  (ColdFire V4e). Binutils 2.47.
- **elektron-firmware-tool**: built at `vendor/elektron-firmware-tool/elektron-firmware-tool`
  (arm64). Wraps `out/mainos.bin` back into `.syx` / emits the ELEK container.
- **Emulator deps**: Homebrew Python is externally-managed → use a venv:
  `python3 -m venv <dir>/venv && <dir>/venv/bin/pip install unicorn` (unicorn 2.1.4). The
  session-5/6 venv lived in the scratchpad and does **not** persist — recreate it.
- **macOS TCC gotcha (still live)**: `~/Documents` is protected; the responsible process is the
  Anthropic `claude` binary, not VS Code. Add
  `~/.vscode/extensions/anthropic.claude-code-<ver>-darwin-arm64/resources/native-binary/claude`
  to Full Disk Access (re-add after each extension update), or run `claude` from Terminal.
  **Racy on process start** — the first file reads after a fresh `claude` often fail `EPERM`
  for 10–30 s, then work. Retry in a loop; don't conclude the file is gone.

## The bug and the fix (done)

**Root cause** (NOTES.md "Session 5 part 3"): `FUN_4009b5c8`'s per-track-scale seed, gated by
pattern SCALE_MODE (`blob +0x8e55` == 1, "Per Track"), reads the scale byte at
`track*0x91a + 0x51`. `0x91a` is the **audio** track stride; for MIDI tracks (index 8..15) the
correct stride/offset is `(track-8)*0x8b0 + 0x48f9`. The audio-stride read overshoots into the
MIDI track's trig data → garbage into `DAT_8000663e[track]` (= `DAT_80006646[track-8]`, a
scale index) → `FUN_400a1eea` indexes the 13-entry step-length table `DAT_400aba50` far out of
bounds → huge garbage step length → the MIDI step-advance gate is always false → the track
never leaves step 1. Preconditions: MIDI + Plays Free + Direct + Per Track; trig mode
(ONE/ONE2/HOLD) irrelevant; **audio tracks unaffected** (the audio-stride read is correct for
them).

**Fix** — `tools/patch_trigscale.s`: an 18-byte detour at `0x4009b6f2`
(`jmp 0x400d7b00` + 6× `nop`, replacing `move.l #0x91a,D0` exactly) into a 62-byte code cave at
`0x400d7b00`. The cave branches on track type: tracks 0–7 get the original audio math; tracks
8–15 get `blob + pattern*0x8ed8 + (track-8)*0x8b0 + 0x48f9`; then it jumps back to
`0x4009b704`. Cave clobbers `D0`/`A0` (scratch on both paths) and `D6` (pattern index, dead
past `0x4009b6d6`). Assembled `-mcpu=5407`. Cave sits in the same `0x400d64da..0x400d7c3c`
zero region R11's shipped detours already run from (patch_arp ends `0x400d7224`).

**Validation** — `tools/emu_trigbug.py` (run with no args, or `--drift`). On the confirmed repro
bank `test1_PFD_scale`: stock → corrupted index **255**; fixed → valid **2** (matches the
non-buggy Pattern-scale path). Audio track scale byte identical patched/unpatched. Log:
`out/ghidra/emu_trigbug_fix_session6.txt`. Also ran with the harness pointed at the real built
image — same result. Open items 2 (full `FUN_400a1eea` behavioural run) and 4 (a trig-mode-ONE
hardware export) were judged nice-to-have and deferred.

## The two flashable builds (both carry the identical always-on fix)

The fix is **not** gated by PERSONALIZE — it is a bug fix and only changes behaviour in the
exact broken config.

| | Build A — fix only | Build B — fix + MAXOLYDIAN mods |
|---|---|---|
| `.syx` (MIDI DIN) | `out/OCTATRACK_OS1.40C_PLAYSFREEFIX.syx` | `out/OCTATRACK_OS1.40C_MAXO_R13.syx` |
| `.bin` (CF card) | `out/OCTATRACK_PLAYSFREEFIX.bin` | `out/OCTATRACK_MAXO_R13.bin` |
| vs stock | 72 bytes (2 hunks), version stays `1.40C` | ~1490 bytes, version `MAXOLYDIAN` |
| builder | `tools/build_trigscale_only.py` | `tools/build.py` |
| reproducible JSON | `sysex/patches/playsfreefix-r1.json` | `sysex/patches/maxolydian-r13.json` |
| SHA-256 of `.syx` | `a2f5d5bd…25b81acc` | `29eec95b…e7a97b92` |

Both `.syx` reproduce byte-identically via `python3 sysex/apply_patch.py -i <stock.syx> -p
<json> -o <out.syx>` (checks stock + result checksums; no assembler needed). `out/` is
git-ignored — the artifacts exist only on the user's disk.

`sysex/gen_patch_json.py` (new) regenerates a JSON by diffing stock vs a built image:
`--trigscale-only` / `--name playsfreefix` / `--display-version keep` for build A. `apply_patch.py`
now skips `-V` when `display_version` is null (that's how build A keeps the stock version field).

Build B = the shipped R11 feature set (arp key-scales + lazy transitions + no BANK/PTN timer +
sticky scenes + dirty LEDs + branding) **plus** this fix. R12 (bank paging) was shelved and is
not in `build.py`. The MAXOLYDIAN features are all off by default in PERSONALIZE.

## If the user comes back about flashing — playbook (also in FLASHING.md §6)

**Always first:** get them to a known-good OS. Hold **[FUNC]** on power-on → STARTUP MENU →
**[TRIG 3]** MIDI UPGRADE → send `downloads/extracted/OCTATRACK_OS1.40C.syx`. Works from a
black/"Z" screen; the bootloader is never touched; CF card untouched. Then diagnose.

**Classify the failure:**

1. **Transfer/flash never completed** (SysEx Librarian error, TRIG lights stall, `-2`/`-3`/`-4`
   from the CF path, no "PREPARING FLASH"). → Transport, not the patch. Re-verify the local
   file (`elektron-firmware-tool -i <f>.syx` → `checksums : ok`; `tools/bin_decode.py <f>.bin`
   → `✓ COINCIDE`); rebuild if bad. Slow down SysEx Librarian (100–300 ms pause); use real DIN
   not USB; for CF, re-copy to card root and **eject properly** (cached write = `-4`).

2. **Flash finished but OS won't boot / traps / hangs.** → Patched code is suspect. Isolate:
   - Flashed **B** and it bricks → flash **A**. If A boots, fault is in the MAXOLYDIAN mods,
     not the MIDI-trig fix.
   - **A also bricks** → the MIDI-trig fix fails on real silicon (emulator is imperfect). This
     needs a code change, not a reflash. Go to "Debugging the fix" below.

3. **OS runs but the fix doesn't work / a regression.** → Confirm the flash took (B: OS VERSION
   = `MAXOLYDIAN`; A: still `1.40C` by design, verify by behaviour). Re-run the exact repro
   (FLASHING.md "Testing the MIDI manual-trig fix"). Still stuck on step 1 = fix not active
   (wrong file) or case 2. For a regression: check whether it also happens on A — A-clean
   points at the mods, both-broken points at the fix.

**Debugging the fix** (if case 2.A or a fix-linked regression):

- Everything is `tools/patch_trigscale.s` — detour + 62-byte cave, nothing else.
- Re-verify against the **full** `FUN_4009b5c8` disasm (`out/ghidra/GhidraResolve38_session5.txt`):
  (1) is `D6` really dead past `0x4009b6d6`? If not, push/pop it around the multiply (safe —
  the function is in a `move #0x2700,SR` critical section). (2) Can anything reach the orphaned
  bytes `0x4009b6f8..0x4009b703`? If so, NOP-fill all 18 in the shipped image and confirm with
  `m68k-elf-objdump`. (3) Cave executability at `0x400d7b00` — if suspect, move to `0x400d7300`
  (right after patch_arp) and rebuild. (4) ISA: `muls.l Dn,Dn` and `addi.l #imm,Dn` are valid
  V4e; only `lea (d8,An,Xn)` (8-bit disp) is supported, hence `0x48f9` folded into `D0`.
- **Bisect:** build a variant whose cave is just `jmp 0x4009b704` (no-op) to test the
  trampoline alone; then add the audio arm; then the MIDI arm. `FIX_SCALE` in
  `tools/emu_trigbug.py` is the patch-dict; `Machine(blob, patch=…)` applies it.
- Rebuild A: `tools/build_trigscale_only.py`. Rebuild B: `tools/build.py`. Re-wrap: see
  FLASHING.md "Rebuild from source".

## Key files

- `NOTES.md` — source of truth. Read **"Session 6"** (end of file) and **"Session 5 part 3"**.
- `tools/patch_trigscale.s` — the fix. `tools/build_trigscale_only.py` — build A.
  `tools/build.py` — build B (now includes `patch_trigscale` in STUBS/DETOURS/EXPECT).
- `tools/emu_trigbug.py` — the emulator harness; `scale_evidence()` + `drift_check()` +
  `FIX_SCALE`. `tools/emu_bankdeserialize.py` — the shared deserializer primitive.
- `sysex/apply_patch.py` + `sysex/gen_patch_json.py` + `sysex/patches/{playsfreefix-r1,
  maxolydian-r13}.json` — the reproducible packaging path.
- `FLASHING.md` — both builds (A/B), the hardware repro/regression test, and **§6 the failure
  playbook**.
- Test banks (real hardware exports): `test1_PFD_scale` (the confirmed repro), `test1_PFD`,
  `test1_PF_`, `test1nil`, `test1nil_scale`. MIDI trigs on step 1 (C) / step 2 (C#) of MIDI
  track 0 (= track index 8), pattern 0. All happen to be trig mode ONE2. They lived at
  `~/Desktop/<name>/bank01.work` during session 6 but were **removed from the Desktop after
  the validation runs** — `emu_trigbug.py` / `insp_banks.py` `load_blob()` also check
  `tools/banks/<name>.bank01.work`. If a future run needs them, ask the user to re-drop the
  folders on the Desktop or into `tools/banks/`. All session-6 validation output is captured
  in `out/ghidra/emu_trigbug_fix_session6.txt`.

## Open items for session 7

1. **Flash a build and confirm** — ✅ DONE 2026-08-28. User flashed Build A to a real Octatrack
   MKI; boots fine, stall gone, no regression. See NOTES.md "Session 7".
2. Mark the fix done in NOTES.md + memory — ✅ DONE. Still open/optional: fold the fix into the
   "shipped" line and retire the superseded R10 JSON; re-flash Build B (R13 bundle) if you want
   the mods + fix confirmed together on hardware.
3. (nice-to-have) Full `FUN_400a1eea` behavioural harness — deferred; huge function (A0 struct
   live-in, `[A0+0x6632]` word, sub-step counters, calls FUN_4009cf4c/d1e8/e884/33968/4a668).
4. (nice-to-have) A trig-mode-**ONE** hardware export to pin `+0x48fd` against the byte layout.
5. The withdrawn handoff-5 part-2 ONE2 oddity (`FUN_40044584` ONE2 press → `FUN_4009f3a4`
   clear-only, no restart) — only if the user reports an independent "ONE2 retrigger stops the
   track" symptom.

## Lessons carried forward

- Emulator-green ≠ hardware-good. A reentrancy crash slipped through in builds 1.0–3.0 the same
  way (the harnesses run one call at a time). Flash with the rescue path ready.
- The whole-image operand scan misses register-relative offsets (`[reg + 0x8e54]` form) — it
  missed `+0x8e55` twice. For any offset also check `adda/lea/addi #imm` and `(disp,An)`.
- Decompiled C misrenders ColdFire register reuse as false branch conditions — check raw disasm
  for any surprising branch.
- Never fabricate test data. Toggling a confirmed-meaning byte in a *deserialized RAM blob* for
  an emulation what-if is OK if flagged (drift_check does this); a genuinely new question wants
  a fresh hardware export.
