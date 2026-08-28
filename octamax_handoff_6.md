# Octatrack MIDI Manual-Trig Bug — Handoff 6 (session 5 → session 6, Claude Code)

## What this is

Reverse-engineering the Elektron Octatrack firmware to root-cause a real, hardware-reproducible
bug: **a Plays-Free MIDI track with trig quantization "Direct" and the pattern's scale set to
"Per Track" stalls after its first step when manually triggered** — the step-1 note fires, the
step-2 note never does. Same investigation as handoffs 1–5. Only real hardware-exported project
files are used as test data; findings are cross-checked against raw disassembly and against the
real firmware code running in a Unicorn CPU emulator.

**Session 5 result: root cause found and evidenced (not yet patched).** See NOTES.md
"Session 5 part 3" for the full reasoning; this is the distilled version.

## Repo & environment (this is now simpler than handoffs 1–5 assumed)

Working dir: `~/Documents/octamax/` on the user's Apple-Silicon Mac (OS user `kyoti_m4`).

- **Ghidra**: Homebrew, `/opt/homebrew/Cellar/ghidra/12.1.2/`. The official `mac_arm_64`
  native decompiler works — **no from-source `ghidra_opt` build** (the handoff-5 recipe is
  obsolete). JDK: `/opt/homebrew/Cellar/openjdk@21/21.0.12/libexec/openjdk.jdk/Contents/Home`.
- Headless invocation (≈4 s):
  ```
  export JAVA_HOME=/opt/homebrew/Cellar/openjdk@21/21.0.12/libexec/openjdk.jdk/Contents/Home
  export PATH="$JAVA_HOME/bin:$PATH"
  /opt/homebrew/Cellar/ghidra/12.1.2/libexec/support/analyzeHeadless \
    ~/Documents/octamax/ghidra_project octamax -process "section_3_MAIN_OS.bin" -noanalysis \
    -scriptPath ~/Documents/octamax/tools -postScript GhidraResolveNN.java
  ```
  `GHIDRA_JAVA_OPTIONS=-Duser.name=kyoti_m4` not needed. The old sandbox's
  "local host name" / `DuplicateFileException` errors don't occur here.
- **macOS TCC gotcha**: `~/Documents` is protected and the responsible process is the
  Anthropic `claude` binary (`com.anthropic.claude-code`), *not* VS Code — granting VS Code
  Full Disk Access does nothing. Add
  `~/.vscode/extensions/anthropic.claude-code-<ver>-darwin-arm64/resources/native-binary/claude`
  to Full Disk Access (re-add after each extension update), or run `claude` from Terminal.app.
  It's also **racy on process start**: the first file read after a fresh `claude` process
  often fails with `EPERM`, then works — wrap reads in a retry loop, or just retry.
- **Emulator deps**: Homebrew Python 3.14 is externally-managed; use a venv:
  `python3 -m venv <dir>/venv && <dir>/venv/bin/pip install unicorn` (unicorn 2.1.4 works).

## Key files

- `NOTES.md` — source of truth. Read **"Session 5 part 3"** first (part 2 is marked
  SUPERSEDED — its ONE2 story is wrong), then parts 1 and 2 for the byte-level facts and
  the emulator harness build-out, then Session 4 for the earlier chain.
- `tools/emu_bankdeserialize.py` — runs the real firmware bank deserializer (`FUN_4008ded0`)
  in Unicorn; the base for everything emulated.
- `tools/emu_trigbug.py` — **the execution harness built this session.** Deserializes a real
  bank into RAM at the real blob base `0x400e21e0`, sets the globals the trig path reads,
  calls the real `FUN_40044584(track, press)`. `scale_evidence()` demonstrates the root
  cause directly (run it with no args). Handles Unicorn's inability to execute privileged
  `move …,SR` (runtime skip) and stubs `FUN_40000c3c` / `FUN_40010bc8` / `FUN_400108b0`.
- `tools/GhidraResolve36.java … 41.java` — this session's headless scripts (clamp function,
  the SCALE_MODE reads, the dispatcher, the step engine prep). Cheap to re-run.
- `out/ghidra/*_session5.txt` — persisted output logs, incl. `emu_trigbug_scale_session5.txt`.
- Test banks (real hardware exports), on `~/Desktop/`: `test1_PF_`, `test1_PFD`,
  `test1_PFD_scale` (the confirmed repro), `test1nil`, `test1nil_scale`. Each has MIDI trigs
  on step 1 (C) and step 2 (C#) of MIDI track 0 (= track index 8), pattern 0.

## Confirmed bug preconditions (user-verified on hardware; all 4 + MIDI_MODE)

1. **MIDI track**, and project **MIDI_MODE** on (`_DAT_80000012 != 0`).
2. **Plays Free** — blob per-track `+0x48fc == 1`.
3. **Trig quant = Direct** — blob per-track `+0x48fe == -1` (`0xff`).
4. **Pattern scale = Per Track** — blob pattern-level `+0x8e55 == 1`.

**Trig mode (ONE / ONE2 / HOLD, blob `+0x48fd` = 0/1/2) is NOT a precondition** — the user
confirmed all three reproduce it. (Handoff 5 wrongly treated pattern-scale as a passenger and
chased ONE2; both were corrected this session.) **Audio tracks with the same settings are
fine** — the bug is MIDI-only.

## Root cause (Session 5 part 3)

`FUN_4009b5c8` (the "start a track" function), in its full-init branch, seeds the track's
scale index when pattern SCALE_MODE (`+0x8e55`) is "Per Track":

```
DAT_8000663e[param_1] = blob[ pattern*0x8ed8 + param_1*0x91a + 0x51 ]      # raw asm 0x4009b6f2..0x4009b704
```

`0x91a` is the **audio-track stride**. For audio (`param_1` 0–7) this is that track's real
scale byte and matches how `FUN_400a1eea` reads it → audio is self-consistent. For a **MIDI
track** (`param_1` 8–15) it overshoots: `param_1 = 8` → `blob + 0x4921`, which is `0x21`
bytes into MIDI track 0's trig data (drifts a further `0x6a`/track). The correct MIDI read —
what `FUN_400a1eea`'s MIDI loop uses — is `blob + pattern*0x8ed8 + (param_1-8)*0x8b0 + 0x48f9`.
`FUN_4009b5c8` never branched on track type for this one read.

Result: `DAT_8000663e[8]` (= `DAT_80006646[0]`, aliased) gets a garbage byte (0xff in the
repro bank) instead of a scale index in 0–12. `FUN_400a1eea`'s MIDI step-advance gate is
`DAT_400aba50[DAT_80006646[track]] <= subcounter+1`; `DAT_400aba50` is `int32[13]` =
`{3,4,6,8,12,24,48,96,48,24,12,6,0}`, so index 255 reads far out of bounds → a huge garbage
"step length" → the gate is **always false** → the track never advances past step 1. The
step-1 C is emitted by the trig-start path itself; step-2's C# needs this wedged loop.

Why each precondition:
- **Per Track** — only then is the audio-stride read done (Normal uses pattern `+0x8e54`, valid).
- **Direct** — needed twice: `FUN_4009b5c8`'s non-Direct/soft path `return`s *before* the
  corrupting write; and in `FUN_400a1eea` a Direct MIDI track hits `if (cVar11 < 1) goto
  LAB_400a37f0`, skipping the block that would recompute `DAT_80006646[track]` from `+0x48f9`
  (the self-heal), so the garbage is permanent.
- **Plays Free** — the gate to reach `FUN_4009b5c8` for a MIDI track (non-PF → `FUN_4009b95a`
  stub).
- **Trig mode** — `FUN_4009b5c8` never reads `+0x48fd`; all modes call it on the first trig.

## Open items for session 6, priority order

1. **Draft + validate the fix.** It belongs in `FUN_4009b5c8`: for MIDI tracks the per-track
   scale read must be `blob + pattern*0x8ed8 + bank*0x9b340 + (param_1-8)*0x8b0 + 0x48f9`.
   There is **no room in place** (18 bytes at `0x4009b6f2`–`0x4009b704`, and `D3` is live as
   the destination index at `0x4009b704`) — needs a **trampoline to a code cave**. Find
   padding in `section_3_MAIN_OS.bin`, write the corrected address math + `bra` back, patch
   the branch. Validate with `emu_trigbug.py`'s `scale_evidence()` (`DAT_80006646[0]` must be
   0–12, not 255) — the harness already has a `FIX_*` patch-dict mechanism; add the new one.
2. **Full behavioural repro.** Extend `emu_trigbug.py` to actually run `FUN_400a1eea` for a
   dozen ticks after the trig and watch `FUN_40010bc8` (MIDI send) calls — should show C then
   C# for `test1_PFD`, and C-then-stall for `test1_PFD_scale`. Needs the `a0` live-in and the
   sub-step counters set up (see `GhidraResolve41_session5.txt` for the prologue; `a0` is an
   implicit pointer, `not.b (a0)` at entry, `[a0+0x6632]` read as a word).
3. **Confirm the drift** for MIDI tracks 1–7 (`blob + track*0x91a + 0x51` lands at a
   different wrong offset per track) — a quick `insp_banks.py`-style check.
4. **Nice-to-have hardware export**: Plays Free + Direct + Per Track + trig mode **ONE**
   (every current bug-config export happens to be ONE2) — pins fact #2 against the byte layout.
5. The withdrawn part-2 finding (`FUN_40044584` ONE2 press → `FUN_4009f3a4` clear-only, no
   restart) is a *separate* real oddity. Only worth revisiting if the user reports an
   independent "ONE2 retrigger stops the track" symptom.

## Lessons carried forward

- The whole-image operand scan **misses register-relative offsets** (`[reg + 0x8e54] + 1`
  form) — it missed `+0x8e55` reads twice. For any offset, also check `adda/lea/addi` with
  that immediate and `(disp,An)` displacements.
- Decompiled C misrenders register reuse as false branch conditions (`if (uVar20*0x8b0 == 0)`
  was really "if DIRECT byte == 0"). Check raw disasm for any surprising branch.
- Never fabricate test data; toggling one confirmed-meaning byte in a *deserialized RAM blob*
  for an emulation what-if is acceptable if flagged, but a new question wants a hardware export.
- Don't over-commit to a mechanism before the user has confirmed the symptom scope — part 2
  built a whole story on ONE2 that a single user sentence ("it hits all trig modes") dissolved.
