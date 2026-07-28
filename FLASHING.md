# Safe flashing guide — modified Octatrack MKII firmware

How to flash `out/OCTATRACK_OS1.40C_FULL_MAXO_V4.syx` onto your Octatrack MKII, with a full
safety net. This firmware introduces FIVE behavior changes + boot branding:
1. **Lazy part apply**: when you switch patterns, the tracks that are playing keep their params (no
   volume jump); they apply the destination Part on their first trig.
2. **GUI-in-transition**: while a track is in transition, turning its knobs edits the **source Part**
   (the one that's playing) and sculpts that sound in real time.
3. **Sticky scenes**: when you switch pattern/Part, the A/B scene selection is **kept** (it does not
   jump to the destination Part's saved scenes); it only changes if you assign a scene manually
   (SCENE A/B + trig), which always wins.
4. **Dirty track LEDs**: a track still sounding with the *source* Part's parameters is lit
   **dimmer** until you re-trig it.
5. **Dirty scene trig**: the selected scene trig goes **amber** while any track is in that state.
6. **Boot branding**: the startup screen (and SYSTEM STATUS → OS VERSION) shows **`MAXOLYDIAN`**
   instead of `1.40C`.

> **Do not use builds earlier than V4.** They carry a reentrancy bug in the GUI patch that
> crashes the unit (`EXCEPTION VEC:0B`) when you hold `[SCENE B]` and turn an encoder on a
> track in transition. V4 fixes it.

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
- [ ] **The patched firmware**: `out/OCTATRACK_OS1.40C_FULL_MAXO_V4.syx`
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

## 3. Flash the patched firmware

1. **Connect MIDI**: your interface's MIDI OUT → the Octatrack's **MIDI IN** (DIN, not USB).
2. **Open SysEx Librarian**, and in its destination selector choose your MIDI interface (the output
   port connected to the OT).
3. **Drag** `out/OCTATRACK_OS1.40C_FULL_MAXO_V4.syx` into the SysEx Librarian list.
4. On the Octatrack: turn it off, hold **[FUNC]** and turn it on → **STARTUP MENU**.
5. Press **[TRIG 3]** (MIDI UPGRADE) → it should say **"READY TO RECEIVE MIDI UPGRADE…"**.
6. In SysEx Librarian, select the file (`_FULL_MAXO_V4.syx`) and press **Play**.
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
     another copy): retry with `_FULL_MAXO_V4.syx`. The change is purely cosmetic and doesn't affect operation.

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

### Regression test — the crash fixed in V4
15. Play B1 P1, switch to B2 P1, then hold **[SCENE B]** and turn the amp volume of a track that
    is in transition. **Expected**: it just edits, nothing else happens.
    - Builds before V4 threw `EXCEPTION VEC:0B` here. If you ever see that screen, power-cycle
      the unit — nothing is damaged, the OS just trapped — and report it.

---

## 5. Reverting to the official firmware

Whenever you want (or if something doesn't convince you), reflash the official one following the **same
steps in section 3**, but sending `downloads/extracted/OCTATRACK_OS1.40C.syx`. Your CF card and projects
are not affected.

---

## Risk notes (honest)

- This firmware is **modified by you, for your own unit, for study purposes.** It is not official
  Elektron firmware and has no support from them.
- The patches are **validated in a ColdFire emulator**, and the audio/GUI/sticky-scene behaviour is
  confirmed on hardware. The emulator harnesses exercise one call at a time, which is exactly how a
  reentrancy crash slipped through into builds 1.0–3.0 — treat emulator green as necessary, not
  sufficient, and go in with the recovery net ready.
- The only truly delicate moment is **"UPDATING FLASH"**: don't cut power there.
- Residual risk of a *hard* (unrecoverable) brick: very low — the rescue bootloader is not touched in
  a normal OS update.

---

### Quick file reference

| File | What it is |
|---|---|
| `out/OCTATRACK_OS1.40C_FULL_MAXO_V4.syx` | **Patched firmware** — everything: lazy part, GUI-in-transition, sticky scenes v2, dirty indicators, "MAXOLYDIAN" branding. The one you're going to flash |
| `out/OCTATRACK_OS1.40C_LAZYPART_GUI.syx` | Variant without boot branding — ⚠️ pre-V4, has the crash |
| `out/OCTATRACK_OS1.40C_LAZYPART.syx` | Audio-only variant (no GUI patch, so no crash) |
| `downloads/extracted/OCTATRACK_OS1.40C.syx` | **Official rescue OS** — for recovery or reverting |
