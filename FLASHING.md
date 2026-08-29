# Safe flashing guide — modified Octatrack MKII firmware

How to flash the patched firmware onto your Octatrack MKII, with a full
safety net.

> **Everything is OFF by default.** Straight after flashing the unit behaves exactly like
> stock firmware. The changes below are switched on from **PERSONALIZE** (see §4).

> **This firmware carries an always-on bug fix** (no PERSONALIZE switch): a **Plays-Free MIDI
> track** with **trig quantize = Direct** and the pattern's **scale = Per Track** used to
> **stall after its first step** when manually triggered — step 1's note fired, step 2's
> never did. Root
> cause: `FUN_4009b5c8` seeded the per-track scale index using the *audio* track stride for
> MIDI tracks, corrupting the step-length lookup. Audio tracks were never affected. This is a
> pure fix — it only changes behaviour in that exact broken configuration. See NOTES.md
> "Session 5 part 3" / "Session 6".
>
> **The fix ships in two builds** (see the file reference at the bottom): **A** = fix on
> otherwise-stock 1.40C (`out/OCTATRACK_OS1.40C_PLAYSFREEFIX.syx` / `.bin`); **B** = fix +
> the optional MAXOLYDIAN mods (`out/OCTATRACK_OS1.40C_MAXO_R13.syx` / `.bin`). Same fix in
> both. The rest of this guide's feature/PERSONALIZE sections apply to build B only.

The firmware introduces TWO optional behavior changes + boot branding:
1. **Lazy transitions**: when you switch to a pattern that uses a different Part, the tracks that
   are playing keep the previous Part's sound — no volume jump. A track's **LED dims while it has
   not yet been re-trigged** since the Part change; a **trig** (sequencer or manual) commits it to
   the destination Part and clears the dim. So the dim tells you, at a glance, which playing tracks
   are still on the previous Part and haven't been re-trigged. Turning an **encoder** applies the
   destination Part's sound immediately (a live preview/commit of the audio) — it is not a trig, so
   it does not clear the dim. The same switch also keeps the **A/B scene pointers** on the same
   slots across the Part change.
2. **No BANK/PTN countdown**: the SELECT BANK / SELECT PATTERN windows no longer expire after four
   seconds. They stay open until you pick a trig or press the same key again to abort — the
   press-again-to-exit toggle already existed in stock firmware. The four countdown boxes stay full
   and now just mean "selection mode is active".
3. **Boot branding**: the startup screen (and SYSTEM STATUS → OS VERSION) shows **`MAXOLYDIAN`**
   instead of `1.40C`.

They are controlled by **LAZY TRANSITIONS** and **NO BANK/PTN TIMER**, two new entries at the
bottom of the PERSONALIZE menu.
7. **Boot branding**: the startup screen (and SYSTEM STATUS → OS VERSION) shows **`MAXOLYDIAN`**
   instead of `1.40C`.

> **Use only the current build.** Earlier ones carried a GUI-in-transition patch that could crash
> the unit; it has been removed entirely — the new spec wants an encoder move to *end* the
> transition, which is the opposite of what that patch did.

> **About the boot branding**: the version you see at power-on lives as text in the header of the
> ELEK container (flash address `0x4008`), in a **fixed-width, 10-character** field that cannot be
> enlarged without breaking OS decompression. That's why the text is `MAXOLYDIAN` (exactly 10 chars)
> and not `MAXOLYDIAN 1.40C` (16, doesn't fit). The internal version code (`0178`, used by the
> downgrade check) stays intact, so the unit still recognizes the OS correctly.

> Want just the audio+GUI change without the branding? Use `out/OCTATRACK_OS1.40C_LAZYPART_GUI.syx`.
> Just the audio change? Use `out/OCTATRACK_OS1.40C_LAZYPART.syx`.

> **Guiding principle: learn how to recover BEFORE flashing.** A brick here is *soft and
> recoverable* — the Startup Menu (bootloader) lives in a region that the OS update doesn't touch,
> so you can always return to a good OS over MIDI. Read the recovery section first.

---

## 0. What you need (checklist)

- [ ] **Octatrack MKII** (the firmware is OS 1.40C for MKII — it will NOT work on the MKI).
- [ ] A **5-pin MIDI (DIN) interface** between the Mac and the Octatrack's MIDI IN.
      ⚠️ **The upgrade does NOT work over USB** — it has to be MIDI DIN. A USB-MIDI cable or an
      audio interface with MIDI works.
- [ ] **SysEx Librarian.app** (you already have it installed). It's the standard app on Mac for sending `.syx`.
- [ ] **The patched firmware**: your chosen build's `.syx` (`_PLAYSFREEFIX` or `_MAXO_R13`)
- [ ] **The official rescue firmware** (essential!): `downloads/extracted/OCTATRACK_OS1.40C.syx`
- [ ] **Stable power** — don't power it from a dubious power strip; don't move it during flashing.

---

## 1. Safety net — the recovery path (READ THIS FIRST)

If something goes wrong (a "Z" screen, won't boot, a hang), **DON'T panic**. You recover like this:

1. Turn off the Octatrack.
2. Holding **[FUNC]** pressed, turn it on → you enter the **STARTUP MENU**.
3. Press **[TRIG 3]** → **MIDI UPGRADE** → "READY TO RECEIVE MIDI UPGRADE…" appears.
4. From SysEx Librarian, send the **official rescue OS**
   (`downloads/extracted/OCTATRACK_OS1.40C.syx`).
5. Wait for "PREPARING FLASH" → "UPDATING FLASH". **Don't power off.** You're back on the factory OS.

This menu works **even if the OS is corrupt** (it's the bootloader). That's why the real risk of
losing the unit is very low.

> **Also**: [TRIG 2] = EMPTY RESET (resets the battery-backed RAM and clears settings, **but NOT the
> CF card**). Rarely needed, but it's there.

---

## 2. Before flashing — backup

Flashing the OS **does not touch the CF card** (your sets, projects and samples live there and stay intact).
Even so, as a precaution:

- [ ] Back up your CF card to the computer (mount the OT in USB DISK MODE and copy everything), or
      at least the projects that matter to you.
- [ ] Optional but recommended: create a **RESTORE POINT** of your active project (OT menu).

---

## 3a. Flash from the CF card — the fast way (recommended)

Manual §8.5.2. Reads the file off the card instead of trickling it over MIDI at 31250 baud, so it
takes seconds rather than minutes.

1. Connect the OT over USB, select **USB DISK MODE** and press **[YES]**. The CF card appears as a
   drive on the computer.
2. Copy your chosen build's `.bin` (`out/OCTATRACK_PLAYSFREEFIX.bin` or `out/OCTATRACK_MAXO_R13.bin`) to the **ROOT** of the card — not inside any folder.
3. **Eject the card properly** on the computer, then leave USB DISK MODE on the OT. Skipping the
   eject can leave the write in cache and the OT reads a truncated file.
4. **PROJECT → OS UPGRADE → [YES]**, confirm the prompt.

The active project is synced to the card automatically before the upgrade.

> This needs a unit that boots. If it does not, use the MIDI path in §3b — the Startup Menu is in
> a region the OS update never touches.

`tools/make_bin.py` builds the `.bin`. Its correctness is not assumed: it regenerates Elektron's
own official `.bin` byte-for-byte from that file's own container.

---

## 3b. Flash over MIDI — for recovery, or if the card path fails

1. **Connect MIDI**: your interface's MIDI OUT → the Octatrack's **MIDI IN** (DIN, not USB).
2. **Open SysEx Librarian**, and in its destination selector choose your MIDI interface (the output
   port connected to the OT).
3. **Drag** your chosen build's `.syx` (`_PLAYSFREEFIX` or `_MAXO_R13`) into the SysEx Librarian list.
4. On the Octatrack: turn it off, hold **[FUNC]** and turn it on → **STARTUP MENU**.
5. Press **[TRIG 3]** (MIDI UPGRADE) → it should say **"READY TO RECEIVE MIDI UPGRADE…"**.
6. In SysEx Librarian, select that file and press **Play**.
   - The OT's **[TRIG]** lights turn on one by one as it receives. **It takes a while** (be patient).
7. When the transfer finishes: **"PREPARING FLASH"** appears and then **"UPDATING FLASH"**.
   - **⚠️ DO NOT POWER OFF OR DISCONNECT** during "…FLASH". Interrupting here corrupts the OS (→ "Z" screen).
8. The OT may update the bootstrap after flashing. **Wait** for it to finish its boot sequence or to
   explicitly tell you to restart. Only then is it ready.

> If SysEx Librarian sends too fast and the OT loses sync, lower the send speed in its *Preferences*
> (increase the "pause between messages", e.g. to 100–300 ms).

---

## 4. Verify that the patch works

The change is subtle and is only noticeable in one specific situation. To test it:

1. Prepare **two patterns** that use **different Parts**, with an **audio track at a different LEVEL**
   in each Part (e.g. Part 1 with track 1 at a high level, Part 2 with track 1 at a low level).
2. In pattern 1, trigger track 1 so it **keeps playing** (a long sample or a loop).
3. **Switch to pattern 2** (with the other Part) **without re-triggering** that track.
4. **Expected behavior with the patch**: the track keeps playing **at the same volume** (it keeps the
   source LEVEL) — **without the jump** you had before.
5. As soon as you **trigger it again** (its first trig in the new pattern), it adopts the LEVEL/params
   of the destination Part. That's the correct behavior.

If instead the volume jumps when you switch patterns (as before), the patch is not active
(did you flash the right file?).

### Testing the GUI-in-transition
6. With the track from step 2 still **in transition** (playing, without re-triggering after the pattern change),
   **turn its knobs** (e.g. FX, filter, LEVEL).
7. **Expected**: you hear the sound in transition change in real time, and those edits land in the
   **source Part** (not the destination one). When you re-trigger the track, it adopts the destination Part.

### Verify the boot branding
8. Restart the unit: the **first screen** should show **`MAXOLYDIAN`** where it used to say `1.40C`.
9. Also in **SYSTEM (menu) → SYSTEM STATUS → OS VERSION** it should read `MAXOLYDIAN`.
   - If it still says `1.40C`, this file wasn't flashed (or the bootloader reads the version from
     another copy): retry with build B's `.syx`. The change is purely cosmetic and doesn't affect operation.

### Testing sticky scenes
10. Have two patterns with different Parts and different A/B scenes selected in each (e.g. P1 with
    A1/B2, P2 with A5/B6). Select A1/B2 in P1.
11. Switch from P1 to P2 **without re-selecting a scene**. **Expected**: A1/B2 stay selected (they don't
    jump to A5/B6). The crossfader morphs between A1 and B2 as in P1.
12. You assign a scene manually (SCENE A/B + trig) → that becomes the new "sticky" selection.
    - If the scenes jump anyway when you switch Part, the patch had no effect (it doesn't harm anything;
      reflash or report). Note: the "sticky" selection modifies the destination pattern's saved
      selection in the working copy; if you save the project, it persists.

### Testing the dirty indicators
13. With a track still in transition (step 2), look at its **track LED**: it should be
    noticeably **dimmer** than the others. Re-trig that track → it returns to full brightness.
    This is the exact, per-track signal: dim means "still sounding with the source Part's params".
14. While any track is in that state, the **selected scene trig** should light **amber** (both
    dies of the bi-colour LED) instead of its usual colour. This one is a global hint — it says
    "something is still on the source Part", not which track.

### Testing the MIDI manual-trig fix (R13, always on)

This one needs no PERSONALIZE switch — it is active immediately after flashing.

> **Hardware-confirmed (2026-08-28):** Build A (`OCTATRACK_OS1.40C_PLAYSFREEFIX`) was flashed
> to a real Octatrack **MKI** and the stall below no longer happens — C then C# both sound and
> the phrase loops normally, no regression. The steps below are still the way to re-verify on
> your own unit.

**Repro of the original bug** (do this on stock 1.40C first if you want to see it, then
compare after flashing R13):

1. On a **MIDI track**, set **PLAYBACK** (FUNC+PLAYBACK / the track's playback setup) so the
   track is **PLAYS FREE**.
2. Set that MIDI track's **trig quantization to DIRECT** (TRIG page → QUANT, or the per-track
   quantize setting) — i.e. no quantization on manual trigs.
3. On the pattern, set **SCALE MODE = PER TRACK** (SCALE SETUP → the PATTERN/PER TRACK toggle),
   and give the MIDI track a scale **length** (any per-track length works; the default is fine).
4. Put a MIDI note trig on **step 1** and another on **step 2** (different notes, e.g. C then
   C#, so they're easy to tell apart), with MIDI OUT going somewhere you can hear/monitor
   (a synth, or the OT's own MIDI monitor / a DAW).
5. **Stop the sequencer.** Press and hold the MIDI track's **[TRIG]** key (manual trig) so
   the track plays free from step 1.

- **Stock 1.40C (bug):** you hear **only the step-1 note** (C). The track never advances to
  step 2 — C# never sounds. Releasing and re-pressing just replays step 1.
- **R13 (fixed):** you hear **C, then C#, then it loops** the two-step phrase normally, exactly
  as it does with SCALE MODE = PATTERN.

6. **Regression checks** (all should behave exactly as on stock):
   - Same setup but **SCALE MODE = PATTERN** → was always fine, still fine.
   - Same setup but **trig quant ≠ Direct** (e.g. 1/16) → still fine.
   - Same setup but **not Plays Free** → still fine.
   - An **audio track** with Plays Free + Direct + Per-Track scale, manually trigged → plays
     and advances normally (audio was never affected; confirm the fix didn't disturb it).
   - Try trig modes **ONE / ONE2 / HOLD** on the MIDI track — all three reproduced the stall
     on stock and all three should be fixed on R13.

> Emulator evidence for this fix: `tools/emu_trigbug.py` (run with no args, or `--drift`).
> On the repro bank the corrupted scale index goes from **255** (out of range → stalled
> step-length gate) to a valid **2** after the patch, matching the non-buggy Pattern-scale
> path; audio tracks read identically patched vs unpatched.

> **An OS upgrade resets the PERSONALIZE settings.** Both switches come back unchecked
> after every flash, so the unit is stock until you re-enable them. Worth knowing when
> testing: a build that looks like it changed nothing may simply have its features off.

### Testing SOFT MUTE (Build C only — always on, no PERSONALIZE switch)

**V6 (ship candidate).**  Muting an audio track (FUNC+[TRACK], the MIXER menu, or QUICK MUTE)
now behaves like a **single STOP** for that track: the sample audio cuts with a fast clean
fade, the track's **FX inserts (delay / reverb) ring their tails out**, and a muted track's
sequencer trigs make no sound.  Two hooks: one on the per-frame mute gate (`FUN_40004dbc`,
keeps the frame level words + maintains a per-track note-off), one on the voice-command queue
(`FUN_40005178`, drops "start" commands for muted tracks — kills the 1-frame trig blip that
V5 had).

The version screen / **SYSTEM STATUS → OS VERSION** reads **`140C_KYOTI`** (the field is a
fixed 10 characters, so `1.40C_KYOTI` at 11 does not fit).

**Notes:** SOLO is left stock (hard cut).  MUTE LED works.  Mute state saves to the pattern
normally.  Unmute returns the track from its next trig, not mid-sample.  The dry cut is fast
(a few ms declick) — it does not fade over the AMP RELEASE time; that would need a much
bigger change.  SOFT MUTE is always on (no PERSONALIZE toggle yet).

1. Audio track playing, with a **delay or reverb** on it, obvious tail.
2. **FUNC + [that track's key]** (and try the **MIXER** menu / **QUICK MUTE** — same code).
   - **Stock 1.40C:** dead silence instantly — dry *and* the FX tail.
   - **Build C (V6):** dry cuts fast; the delay repeats / reverb tail **ring out**.
3. With the sequencer running, mute a track with a trig on it — there should be **no blip** on
   the muted trigs (V5 had a faint one).
4. **FUNC + [key] again** to unmute.
5. Regression: MIDI manual-trig fix still works (same bytes as Build A); other tracks
   unaffected; **SOLO** unchanged; boot screen and OS VERSION say `140C_KYOTI`.

> History (2026-08-28): V1/V2/D1 hooked `FUN_400836d8` / its `0x?040` voice command; V3 hooked
> `FUN_40030c60`.  None had any effect.  V4 found the real gate (`FUN_40004dbc`); V5 added the
> note-off (FX tails then rang, dry cut fast, faint trig blip); V6 adds the trig-blip fix +
> the `140C_KYOTI` branding.

### Turning the features on
15. Go to **PROJECT → PERSONALIZE**. Scroll to the bottom: two new entries,
    **NO BANK/PTN TIMER** and **LAZY TRANSITIONS**, both unchecked.
    Check them with **[YES]** (or the arrow keys). The 16 stock entries above must still show
    their own values correctly.
16. The settings live in battery-backed RAM, so they survive a power cycle. Turn the unit off
    and on to confirm they stay checked. A Startup Menu **EMPTY RESET** clears them back to
    factory, like every other PERSONALIZE setting.

### Testing the BANK/PTN toggle
18. Press **[PTN]**: the SELECT PATTERN window opens. Wait more than four seconds — **it must stay
    open**, with the four boxes full and unmoving. Press a **[TRIG]** and the pattern changes.
19. Press **[PTN]** again instead of a trig → aborts back to the sequencer.
20. Press **[BANK]**, pick a bank with a **[TRIG]** → the display asks for the pattern, also with no
    time limit. Pick a trig and you are back to normal.

### Regression test — the crash fixed in V4
17. Play B1 P1, switch to B2 P1, then hold **[SCENE B]** and turn the amp volume of a track that
    is in transition. **Expected**: it just edits, nothing else happens.
    - Builds before V4 threw `EXCEPTION VEC:0B` here. If you ever see that screen, power-cycle
      the unit — nothing is damaged, the OS just trapped — and report it.

---

## 5. Reverting to the official firmware

Whenever you want (or if something doesn't convince you), reflash the official one following the **same
steps in section 3**, but sending `downloads/extracted/OCTATRACK_OS1.40C.syx`. Your CF card and projects
are not affected.

---

## 6. If flashing fails, or the flashed OS misbehaves

**First, always: get back to a known-good OS.** Hold **[FUNC]** on power-on → STARTUP MENU →
**[TRIG 3]** MIDI UPGRADE → send `downloads/extracted/OCTATRACK_OS1.40C.syx` from SysEx
Librarian → wait through "UPDATING FLASH". This works even from a black/"Z" screen — the
bootloader is never touched by an OS update. Nothing on the CF card is affected. Then work out
what happened before trying again.

Figure out **which of three things** you're looking at:

### (a) The transfer / flash never completed

Symptoms: SysEx Librarian errors or stalls; the OT's TRIG lights stop advancing; "PREPARING
FLASH" never appears; the CF `.bin` path reports a bad/'not a valid OS' file (`-2`), wrong
length (`-3`), or bad checksum (`-4`).

This is almost never the patch — it's the transport. The two builds only differ from stock by
72 bytes (A) or ~1500 bytes (B); both repack through the same checksummed container as the
official file.

- **MIDI:** lower SysEx Librarian's send speed (Preferences → raise "pause between messages"
  to 100–300 ms). Use a real 5-pin DIN interface, not USB. Try a different interface/cable.
- **CF card:** re-copy the `.bin` to the **card root**, **eject properly** (a cached/truncated
  write is the usual `-4`), re-seat the card. Confirm the file size matches what's in `out/`.
- Re-verify the artifact before re-sending: `elektron-firmware-tool -i <file>.syx` must print
  `checksums : ok`; for the `.bin`, `python3 tools/bin_decode.py <file>.bin` must print
  `✓ COINCIDE`. If either fails, the local file is corrupt — rebuild it (§ Quick file
  reference → "Rebuild from source").

### (b) The flash completed, but the OS won't boot / traps / hangs

Symptoms: logo screen forever, `EXCEPTION VEC:xx`, a hang, garbled display — **after** a
"UPDATING FLASH" that finished.

Now the patched code is suspect. Isolate it:

1. If you flashed **B (MAXO)** → flash **A (PLAYSFREEFIX)**. If A boots fine, the fault is in
   the MAXOLYDIAN mods (lazy transitions / encoder / LED / scene / menu / arp), **not** the
   MIDI-trig fix. Report which build broke.
2. If **A also fails to boot** → the MIDI-trig fix itself is the problem on real silicon
   (it passed the ColdFire emulator, which is not a perfect model). Revert to stock and
   report it — this needs a code change, not a reflash. See "For a future debugging session"
   below.
3. Either way you are never stuck: the rescue path in this section always brings the unit back.

### (c) The OS boots and runs, but the fix doesn't work (or something else is wrong)

- **Did the flash actually take?** Build B: SYSTEM → SYSTEM STATUS → OS VERSION should read
  `MAXOLYDIAN`. Build A: it still says `1.40C` (by design) — so instead confirm by the
  behaviour test below. An OS upgrade **resets PERSONALIZE**, but the MIDI-trig fix is
  **not** gated by PERSONALIZE — it is always on — so a fresh flash is enough to test it.
- **Re-run the exact repro** from "Testing the MIDI manual-trig fix" above (PLAYS FREE +
  trig quant Direct + pattern SCALE MODE = PER TRACK, MIDI trigs on steps 1 and 2, sequencer
  stopped, hold the track's [TRIG]). Fixed = you hear step 1 then step 2 then it loops.
  Still stuck on step 1 = the fix isn't active → you flashed the wrong/old file, or see (b).
- **A regression** (something that worked on stock now misbehaves): note the exact steps and
  whether it happens on **A** too. A-only-clean points at the MAXOLYDIAN mods; both-broken
  points at the MIDI-trig fix.

### For a future debugging session (hand this to Claude)

If the MIDI-trig fix is implicated on hardware (case (b).2 or a (c) regression tied to it):

- The whole fix is `tools/patch_trigscale.s`: an 18-byte detour at `0x4009b6f2`
  (`jmp 0x400d7b00` + 6× `nop`) into a 62-byte cave at `0x400d7b00`. Nothing else.
- Re-check, on real-hardware assumptions (not just the Unicorn emulator, which passed):
  1. **Cave liveness** — the cave clobbers `D0`, `A0`, and (MIDI arm only) `D6`. `D0`/`A0`
     are scratch on both original paths. `D6` was argued dead past `0x4009b6d6`; re-verify
     against the *full* `FUN_4009b5c8` disasm (`out/ghidra/GhidraResolve38_session5.txt`).
     If `D6` is live, push/pop it (`move.l %d6,-(%sp)` / `move.l (%sp)+,%d6`) around the
     multiply — the function is inside a `move #0x2700,SR` critical section so the stack is
     safe.
  2. **The orphaned bytes** `0x4009b6f8..0x4009b703` (stale `muls.l`/`add.l`/`movea.l`/`lea`
     after the `jmp`). Argued unreachable. If a disassembler or an indirect path *can* reach
     them, NOP-fill all 18 (the detour already does on the `.bin`/`.syx`; confirm in the
     shipped image with `m68k-elf-objdump`).
  3. **Cave executability** — `0x400d7b00` is inside the same `0x400d64da..0x400d7c3c` zero
     cave that R11's shipped detours already run from on hardware, ~2.3 KB further in. If in
     doubt, move the cave to `0x400d7300` (right after `patch_arp`, still proven range) and
     rebuild.
  4. **ISA** — assembled `-mcpu=5407` (ColdFire V4e), same as every other stub. `muls.l`
     with a register source and `addi.l #imm,Dn` are valid V4e; `lea (d8,An,Xn)` is the only
     supported indexed form (8-bit disp) — that's why `0x48f9` is folded into `D0` first.
  5. **Bisect** — build a variant whose cave is just `jmp 0x4009b704` (does nothing) to test
     the trampoline mechanism alone, then add the audio arm, then the MIDI arm.
- Emulator harness for any candidate: `tools/emu_trigbug.py` (`scale_evidence()` /
  `drift_check()`); `FIX_SCALE` in that file is the patch-dict. `tools/build_trigscale_only.py`
  rebuilds build A; `tools/build.py` rebuilds build B.
- Full reasoning: `NOTES.md` "Session 5 part 3" and "Session 6".

---

## Risk notes (honest)

- This firmware is **modified by you, for your own unit, for study purposes.** It is not official
  Elektron firmware and has no support from them.
- The patches are **validated in a ColdFire emulator**. The audio/GUI/sticky-scene behaviour of
  the MAXOLYDIAN mods is confirmed on hardware. The **MIDI manual-trig fix is now also confirmed
  on hardware**: Build A (`OCTATRACK_OS1.40C_PLAYSFREEFIX`, fix on stock 1.40C) was flashed to a
  real Octatrack **MKI** on 2026-08-28 — the unit boots normally and the step-1 stall is gone,
  with no regression. Build B (`_MAXO_R13`, fix + mods) carries the identical fix but the R13
  bundle has not been re-flashed since the fix was added, so treat B as emulator-validated for
  the fix. In general the emulator harnesses exercise one call at a time, which is exactly how a
  reentrancy crash slipped through into builds 1.0–3.0 — treat emulator green as necessary, not
  sufficient, and go in with the recovery net ready (§6 has the failure playbook).
- The only truly delicate moment is **"UPDATING FLASH"**: don't cut power there.
- Residual risk of a *hard* (unrecoverable) brick: very low — the rescue bootloader is not touched in
  a normal OS update.

---

### Quick file reference

There are **two builds of the fix**. Both contain the identical MIDI manual-trig fix; they
differ only in what else is on board. Pick one.

**A. Fix only — otherwise 100% stock 1.40C** (version screen still says `1.40C`):

| File | What it is |
|---|---|
| `out/OCTATRACK_OS1.40C_PLAYSFREEFIX.syx` | MIDI-DIN upgrade. Stock 1.40C with **only** the MIDI manual-trig fix (2 code hunks, 72 bytes). Byte-identical to stock everywhere else. |
| `out/OCTATRACK_PLAYSFREEFIX.bin` | Same, for the CF-card path (card ROOT → PROJECT → OS UPGRADE). |

**B. Fix + the MAXOLYDIAN mods** (arp key-scales, lazy transitions, no BANK/PTN timer, sticky
scenes, dirty indicators, `MAXOLYDIAN` branding — the feature mods are all **off by default**
in PERSONALIZE):

| File | What it is |
|---|---|
| `out/OCTATRACK_OS1.40C_MAXO_R13.syx` | MIDI-DIN upgrade. |
| `out/OCTATRACK_MAXO_R13.bin` | CF-card path. |

**C. MIDI manual-trig fix + SOFT MUTE** — otherwise stock 1.40C (version screen says `1.40C`):

| File | What it is |
|---|---|
| `out/OCTATRACK_OS1.40C_SOFTMUTE_PFFIX.syx` | MIDI-DIN upgrade. The Build-A fix **plus** "trig-mute"-style audio-track mutes (§4 "Testing SOFT MUTE"). **SOFT MUTE is always-on in this build — no PERSONALIZE switch.** 4 code hunks, ~280 bytes; byte-identical to stock everywhere else, and the manual-trig fix bytes are identical to Build A. |
| `out/OCTATRACK_SOFTMUTE_PFFIX.bin` | Same, for the CF-card path. |

> **Not yet hardware-tested.** This is the first flash of the SOFT MUTE detour. If mutes go
> weird, reflash the rescue OS. Revert = just flash stock 1.40C (nothing persists).

**Rescue (either way):**

| File | What it is |
|---|---|
| `downloads/extracted/OCTATRACK_OS1.40C.syx` | **Official rescue OS** — for recovery or reverting. |

Rebuild from source:

```sh
# Build A — fix only, on stock
python3 tools/build_trigscale_only.py                  # -> out/mainos_trigscale_only.bin
EFT_EMIT_CONTAINER=out/elek_pffix.bin \
  vendor/elektron-firmware-tool/elektron-firmware-tool \
  -i downloads/extracted/OCTATRACK_OS1.40C.syx -c 3 out/mainos_trigscale_only.bin \
  -o out/OCTATRACK_OS1.40C_PLAYSFREEFIX.syx            # no -V: keeps the "1.40C" version field

# Build C — manual-trig fix + SOFT MUTE V4 (always-on), on stock
python3 tools/build_softmute.py                        # -> out/mainos_softmute.bin
EFT_EMIT_CONTAINER=out/elek_softmute.bin \
  vendor/elektron-firmware-tool/elektron-firmware-tool \
  -i downloads/extracted/OCTATRACK_OS1.40C.syx -c 3 out/mainos_softmute.bin \
  -o out/OCTATRACK_OS1.40C_SOFTMUTE_PFFIX.syx
python3 tools/make_bin.py out/elek_softmute.bin -o out/OCTATRACK_SOFTMUTE_PFFIX.bin
python3 tools/make_bin.py out/elek_pffix.bin -o out/OCTATRACK_PLAYSFREEFIX.bin

# Build B — fix + MAXOLYDIAN mods
python3 tools/build.py                                 # -> out/mainos.bin
EFT_EMIT_CONTAINER=out/elek_r13.bin \
  vendor/elektron-firmware-tool/elektron-firmware-tool \
  -i downloads/extracted/OCTATRACK_OS1.40C.syx -c 3 out/mainos.bin -V MAXOLYDIAN \
  -o out/OCTATRACK_OS1.40C_MAXO_R13.syx
python3 tools/make_bin.py out/elek_r13.bin -o out/OCTATRACK_MAXO_R13.bin
```

Reproducible one-shot (verifies stock + result checksums, no assembler needed):

```sh
python3 sysex/apply_patch.py -i <your stock .syx> -p sysex/patches/playsfreefix-r1.json  -o A.syx
python3 sysex/apply_patch.py -i <your stock .syx> -p sysex/patches/maxolydian-r13.json   -o B.syx
```

(Regenerate those JSONs from a fresh build with `sysex/gen_patch_json.py`.)

---

## Live bank paging (experimental — R12)

Reach more than 16 banks in a live set by paging in whole **sibling projects** from the CF
card, **without stopping the sequencer/audio**.

### Setup (sibling projects)
1. Load your base project (e.g. `MYSET`).
2. **PROJECT → SAVE PROJECT AS → `MYSET_2`** (this copies the sample pool). Optionally `_3`, `_4`.
   Edit patterns/parts in each; **keep the sample pool / slot assignments identical** across
   siblings — samples are project-level, so paged banks play whatever sample sits in each slot.
3. Load the base `MYSET` again to perform.

### Use
1. Press **[BANK]** to open SELECT BANK.
2. Press **[PAGE]**: a **"LOAD BANKS?"** popup shows the target project (`MYSET_2`, then `_3`,
   `_4`, then back to the base). Each press cycles to the next page.
3. **[YES]** loads that page's banks (all except the one currently playing) in the background —
   **audio keeps playing** — and drops you back in SELECT BANK to pick a bank + pattern.
   **[NO]** aborts back to the sequencer.

### Notes / current limitations (release candidate)
- The **background load is hardware-proven not to stop audio**; the surrounding UX (cycling,
  the popup) is validated in the emulator and on-device for the core path, but the full flow is
  still a release candidate — test it before relying on it live, and keep the official `.syx`
  handy for recovery.
- **The bank you're playing when you page keeps the base content** until you switch away from it
  (loading the playing bank would interrupt audio). Switching to another bank frees it; a
  "catch-up" of that bank is a planned refinement.
- **Don't SAVE while paged** — the RAM banks hold the sibling's content and a save would write it
  into the *base* project. Treat paging as performance-only for now.
- Pressing **[PAGE]** in SELECT BANK pops the confirm even for projects without siblings (just
  press [NO]); an existence check that keeps [PAGE] stock for non-paged projects is planned.
- A page that doesn't exist on the card just raises the normal load-error dialog.
