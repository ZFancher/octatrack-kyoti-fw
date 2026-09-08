# Safe flashing guide — OT Kyoti FW

How to flash an OT Kyoti FW image onto your Octatrack, with a full safety net.

> **The behaviour change is OFF by default.** Straight after flashing, the unit
> behaves like stock firmware apart from the one always-on bug fix below. MUTE
> MODE is switched on from PERSONALIZE.

> **Octatrack MKI or MKII.** Elektron ships one OS 1.40C image for both units and
> the reverse engineering / builds here apply to both; the boot `0x46c8d18c`
> probe adapts the unit-specific details. All hardware testing in this project is
> on a **MKI** the author owns — including the flash that confirmed the Bug-1 fix.

> **The always-on bug fix (no PERSONALIZE switch):** a **Plays-Free MIDI track**
> with **trig quantize = Direct** and the pattern's **scale = Per Track** used to
> **stall after its first step** when manually triggered — step 1's note fired,
> step 2's never did. Root cause: `FUN_4009b5c8` seeded the per-track scale index
> using the *audio* track stride for MIDI tracks, corrupting the step-length
> lookup. Audio tracks were never affected. It is a pure fix — it only changes
> behaviour in that exact broken configuration. See `NOTES.md` "Session 5 part 3"
> / "Session 6"; hardware-confirmed on a MKI 2026-08-28.

> **Guiding principle: learn how to recover BEFORE flashing.** A brick here is
> *soft and recoverable* — the Startup Menu (bootloader) lives in a region that
> the OS update doesn't touch, so you can always return to a good OS over MIDI.
> Read the recovery section first.

---

## 0. What you need (checklist)

- [ ] An **Octatrack** (MKI or MKII).
- [ ] A **5-pin MIDI (DIN) interface** between the computer and the Octatrack's
      MIDI IN. ⚠️ **The MIDI upgrade does NOT work over USB** — it has to be MIDI
      DIN. A USB-MIDI cable or an audio interface with MIDI works. (Or use the
      CF-card path in §3a, which needs no MIDI.)
- [ ] **SysEx Librarian.app** (Mac) or any tool that sends a raw `.syx`.
- [ ] **The build you want** — the `.syx` (MIDI) or `.bin` (CF card) from `out/`.
- [ ] **The official rescue firmware** (essential!):
      `downloads/extracted/OCTATRACK_OS1.40C.syx`.
- [ ] **Stable power** — don't power from a dubious strip; don't move the unit
      during flashing.

---

## 1. Safety net — the recovery path (READ THIS FIRST)

If something goes wrong (a "Z" screen, won't boot, a hang), **DON'T panic**. You
recover like this:

1. Turn off the Octatrack.
2. Holding **[FUNC]** pressed, turn it on → you enter the **STARTUP MENU**.
3. Press **[TRIG 3]** → **MIDI UPGRADE** → "READY TO RECEIVE MIDI UPGRADE…"
   appears.
4. From SysEx Librarian, send the **official rescue OS**
   (`downloads/extracted/OCTATRACK_OS1.40C.syx`).
5. Wait for "PREPARING FLASH" → "UPDATING FLASH". **Don't power off.** You're back
   on the factory OS.

This menu works **even if the OS is corrupt** (it's the bootloader). That is why
the real risk of losing the unit is very low.

> **Also**: [TRIG 2] = EMPTY RESET (resets the battery-backed RAM and clears
> settings, **but NOT the CF card**). Rarely needed, but it's there.

---

## 2. Before flashing — backup

Flashing the OS **does not touch the CF card** (your sets, projects and samples
live there and stay intact). Even so, as a precaution:

- [ ] Back up your CF card to the computer (USB DISK MODE and copy everything), or
      at least the projects that matter.
- [ ] Optional but recommended: create a **RESTORE POINT** of your active project.

---

## 3a. Flash from the CF card — the fast way (recommended)

Manual §8.5.2. Reads the file off the card instead of trickling it over MIDI at
31250 baud, so it takes seconds rather than minutes.

1. Connect the OT over USB, select **USB DISK MODE**, press **[YES]**. The CF card
   appears as a drive.
2. Copy your chosen build's `.bin` (e.g. `out/OCTATRACK_MUTEMODE.bin`) to the
   **ROOT** of the card — not inside any folder.
3. **Eject the card properly**, then leave USB DISK MODE on the OT. Skipping the
   eject can leave the write in cache and the OT reads a truncated file.
4. **PROJECT → OS UPGRADE → [YES]**, confirm the prompt.

The active project is synced to the card automatically before the upgrade.

> This needs a unit that boots. If it does not, use the MIDI path in §3b.

`tools/make_bin.py` builds the `.bin`. Its correctness is not assumed: it
regenerates Elektron's own official `.bin` byte-for-byte from that file's own
container.

---

## 3b. Flash over MIDI — for recovery, or if the card path fails

1. **Connect MIDI**: your interface's MIDI OUT → the Octatrack's **MIDI IN**
   (DIN, not USB).
2. **Open SysEx Librarian**, choose your MIDI interface as the destination.
3. **Drag** your chosen build's `.syx` into the SysEx Librarian list.
4. On the Octatrack: turn it off, hold **[FUNC]** and turn it on → **STARTUP
   MENU**.
5. Press **[TRIG 3]** (MIDI UPGRADE) → **"READY TO RECEIVE MIDI UPGRADE…"**.
6. In SysEx Librarian, select the file and press **Play**. The OT's **[TRIG]**
   lights turn on one by one as it receives. **It takes a while.**
7. When the transfer finishes: **"PREPARING FLASH"** then **"UPDATING FLASH"**.
   - **⚠️ DO NOT POWER OFF OR DISCONNECT** during "…FLASH". Interrupting here
     corrupts the OS (→ "Z" screen).
8. The OT may update the bootstrap after flashing. **Wait** for it to finish or to
   tell you to restart.

> If SysEx Librarian sends too fast and the OT loses sync, lower the send speed in
> *Preferences* (increase "pause between messages", e.g. to 100–300 ms).

---

## 4. Verify the flash and test each feature

An OS upgrade **resets PERSONALIZE**, so MUTE MODE is back to `OT` after a flash
until you re-enable it. The Bug-1 fix is always on.

### 4.0  Boot check
The startup screen and **SYSTEM → SYSTEM STATUS → OS VERSION** read `140C_KYOTI`
(the field is fixed at 10 chars; `1.40C_KYOTI` at 11 does not fit). The
`build_trigscale_only.py` fix-only build deliberately keeps the stock `1.40C`
string.

### 4.1  The MIDI manual-trig fix  (always on)

> **Hardware-confirmed (MKI, 2026-08-28.)** The fix-only build was flashed and the
> stall below no longer happens. The steps below re-verify on your own unit.

1. On a **MIDI track**, set **PLAYBACK** so the track is **PLAYS FREE**.
2. Set that MIDI track's **trig quantization to DIRECT**.
3. On the pattern, set **SCALE MODE = PER TRACK** (SCALE SETUP), give the MIDI
   track any per-track length.
4. Put a MIDI note trig on **step 1** and another on **step 2** (different notes,
   e.g. C then C#), MIDI OUT to something you can hear/monitor.
5. **Stop the sequencer.** Press and hold the MIDI track's **[TRIG]** so it plays
   free from step 1.

- **Stock 1.40C (bug):** only the step-1 note (C). It never advances to step 2.
- **Fixed:** C, then C#, then it loops the two-step phrase — like SCALE MODE =
  PATTERN.

6. **Regression checks** (all should behave exactly as on stock):
   - SCALE MODE = PATTERN → still fine.
   - trig quant ≠ Direct → still fine.
   - not Plays Free → still fine.
   - an **audio** track with Plays Free + Direct + Per-Track scale, manually
     trigged → plays and advances normally (audio was never affected).
   - trig modes ONE / ONE2 / HOLD on the MIDI track — all three should be fixed.

> Emulator evidence: `tools/emu_trigbug.py` (`--drift`). On the repro bank the
> corrupted scale index goes 255 → a valid 2 after the patch.

### 4.2  MUTE MODE  (`build_mutemode.py` — hardware-confirmed for `OT+FX` mute)

1. **PROJECT → PERSONALIZE**, scroll to **MUTE MODE**. It cycles `OT` / `OT+FX`.
   Default `OT`. The 15/16 stock entries above must still show their own values.
2. Set **`OT+FX`**. Put a **delay or reverb** on an audio track, obvious tail.
3. Play the track, then **FUNC + [that track's key]** (also try the MIXER menu /
   QUICK MUTE — same code path):
   - **`OT`:** dead silence instantly — dry *and* the FX tail (stock).
   - **`OT+FX`:** the dry cuts fast and clean; the delay repeats / reverb tail
     **ring out**. A muted track's sequencer trigs make no sound. Unmute returns
     the track on its next trig.
4. **Persistence:** power-cycle the unit — MUTE MODE stays where you left it
   (the setter writes the `'ANDY'` battery-SRAM shadow — "Session 19"). An EMPTY
   RESET clears it to factory.
5. Regression: the manual-trig fix still works; other tracks unaffected;
   in `OT` mode SOLO is a stock hard cut.

> `build_softmute.py` is the same `OT+FX` soft-mute mechanism **always on** with
> no menu entry — test it as in step 3 (there is no PERSONALIZE toggle to set).

> **On the `wip/mute-mode` branch** the soft cut also extends to SOLO (softmute
> V7), there is a `DT` Digitakt-style sequencer mute (`build_mutemode_dt.py`), a
> `[PTN]`+`[YES]` DIRECT JUMP mode and an in-progress DSP side-chain compressor —
> all emulator-verified, none flashed. Their test procedures are in that branch's
> `FLASHING.md`.

---

## 5. Reverting to the official firmware

Reflash the official one following the **same steps in §3**, sending
`downloads/extracted/OCTATRACK_OS1.40C.syx`. Your CF card and projects are not
affected. Nothing the mods store persists a revert except the PERSONALIZE /
`'ANDY'` block, which an EMPTY RESET clears.

---

## 6. If flashing fails, or the flashed OS misbehaves

**First, always: get back to a known-good OS** — the §1 recovery path works even
from a black/"Z" screen. Then work out what happened.

### (a) The transfer / flash never completed

Symptoms: SysEx Librarian errors or stalls; the TRIG lights stop advancing;
"PREPARING FLASH" never appears; the CF `.bin` path reports `-2` (not a valid OS),
`-3` (length) or `-4` (checksum).

This is almost never the patch — it's the transport. The Kyoti builds differ from
stock by tens to ~1500 bytes and repack through the same checksummed container as
the official file.

- **MIDI:** lower SysEx Librarian's send speed. Use a real 5-pin DIN interface.
  Try another interface/cable.
- **CF card:** re-copy the `.bin` to the card **root**, **eject properly** (a
  cached/truncated write is the usual `-4`), re-seat the card.
- Re-verify the artifact: `elektron-firmware-tool -i <file>.syx` must print
  `checksums : ok`; for the `.bin`, `python3 tools/bin_decode.py <file>.bin` must
  print `✓ COINCIDE`. If either fails, rebuild it.

### (a-bis) It boots, but audio is garbled — **power-cycle first**

An OS upgrade rewrites program memory but does **not** clear the DSP state RAM. An
engine whose warm-up tag is still valid runs on the previous firmware's buffer
contents, so audio can be garbled right after `UPDATING FLASH` — worst on the
delay/reverb. **Turn the unit fully off and on before judging anything.** (Source:
`refs/octabam/docs/FAILURE_MODES.md`.)

### (b) It flashed and finished "UPDATING FLASH", but the OS won't boot / traps / hangs

Now the patched code is suspect. Isolate it:

1. Flash **`build_trigscale_only.py`** (Bug-1 fix on stock). If that boots fine,
   the fault is in the mod you flashed, not the fix. Report which build.
2. If the fix-only build **also** fails to boot → the fix itself is the problem on
   real silicon (it passed the ColdFire emulator, which is not a perfect model).
   Revert to stock and report — this needs a code change, not a reflash.
3. Either way you are never stuck: the §1 rescue path always brings the unit back.

### (c) It boots and runs, but a feature doesn't work

- **Did the flash take?** OS VERSION should read `140C_KYOTI` (fix-only build:
  still `1.40C`, so test by behaviour). PERSONALIZE is reset by every flash — the
  mods are off until you re-enable them.
- **Re-run the exact test** from §4 for that feature.
- **A regression** (something that worked on stock now misbehaves): note the exact
  steps and whether it also happens on the fix-only build. Fix-only-clean points
  at the mod; both-broken points at the Bug-1 fix.

### For a future debugging session (hand this to Claude)

If the MIDI-trig fix is implicated on hardware (case (b).2 or a (c) regression
tied to it):

- The whole fix is `tools/patch_trigscale.s`: an 18-byte detour at `0x4009b6f2`
  (`jmp 0x400d7b00` + 6× `nop`) into a 62-byte cave at `0x400d7b00`. Nothing else.
- Re-check on real-hardware assumptions (not just Unicorn):
  1. **Cave liveness** — the cave clobbers `D0`, `A0`, and (MIDI arm only) `D6`.
     `D0`/`A0` are scratch on both original paths. `D6` was argued dead past
     `0x4009b6d6`; re-verify against the full `FUN_4009b5c8` disasm
     (`out/ghidra/GhidraResolve38_session5.txt`). If `D6` is live, push/pop it
     around the multiply.
  2. **The orphaned bytes** `0x4009b6f8..0x4009b703`. Argued unreachable; if a
     path can reach them, NOP-fill all 18.
  3. **Cave executability** — `0x400d7b00` is inside the same
     `0x400d64da..0x400d7c3c` zero cave that other shipped detours run from.
  4. **ISA** — assembled `-mcpu=5407` (ColdFire V4e), same as every other stub.
  5. **Bisect** — a variant whose cave is just `jmp 0x4009b704` tests the
     trampoline alone, then add the audio arm, then the MIDI arm.
- Harness: `tools/emu_trigbug.py`; `tools/build_trigscale_only.py` rebuilds it.
- Full reasoning: `NOTES.md` "Session 5 part 3" and "Session 6".

---

## Risk notes (honest)

- This firmware is **modified by you, for your own unit, for study purposes.** It
  is not official Elektron firmware and has no support from them.
- The patches are **validated in a ColdFire emulator** (Unicorn, real image
  bytes) — control flow and DSP frame-word edits, not the audio engine. **Both
  the Bug-1 fix and the `OT+FX` soft-mute mechanism have run on hardware** (an
  Octatrack MKI). Treat emulator-green as necessary, not sufficient, and go in
  with the recovery net ready (§1, §6).
- The only truly delicate moment is **"UPDATING FLASH"**: don't cut power there.
- Residual risk of a *hard* (unrecoverable) brick: very low — the rescue
  bootloader is not touched in a normal OS update.

---

### Quick file reference

Every Kyoti build lands in `out/` as four files:

```
mainos_*.bin                      the patched MAIN OS section
elek_*.bin                        the rebuilt ELEK container
OCTATRACK_OS1.40C_*.syx           MIDI-DIN upgrade transport
OCTATRACK_*.bin                   CF-card OS UPGRADE transport (faster)
```

| build command | version | contents |
|---|---|---|
| `python3 tools/build_trigscale_only.py` | `1.40C` | Bug-1 fix only, on otherwise-stock 1.40C |
| `python3 tools/build_mutemode.py` | `140C_KYOTI` | Bug-1 fix + MUTE MODE `OT` / `OT+FX` |
| `python3 tools/build_softmute.py` | `140C_KYOTI` | Bug-1 fix + soft mute **always on**, no menu |

(The `wip/mute-mode` branch adds `build_mutemode_dt.py`, `build_directjump*.py`
and `build_sidechain*.py` — see that branch.)

| File | What it is |
|---|---|
| `downloads/extracted/OCTATRACK_OS1.40C.syx` | **Official rescue OS** — for recovery or reverting. |

Reproducible one-shot for the Bug-1 fix (no assembler needed):

```sh
python3 sysex/apply_patch.py -i <your stock .syx> \
    -p sysex/patches/playsfreefix-r1.json -o OCTATRACK_OS1.40C_PLAYSFREEFIX.syx
```

(Regenerate the JSON from a fresh build with `sysex/gen_patch_json.py`.)
