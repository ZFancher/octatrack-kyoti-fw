```
   ▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄
  ▐░░  O T   K Y O T I   F W  ·  custom Octatrack firmware  ░░▌
  ▐░░  small · optional · reversible · educational          ░░▌
   ▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀
```

# OT Kyoti FW

**A custom firmware for the Elektron Octatrack (OS 1.40C) — a hardware-confirmed
MIDI bug fix and a set of small, optional, reversible behaviour changes, built
from a reverse-engineering study of the stock OS.**

Everything here is **educational**. You bring your own copy of the official OS;
the tools analyze it and, if you ask, produce a modified image byte-for-byte
reproducibly from *your* copy. No `.bin` / `.syx` is ever distributed — only the
tools to roll your own.

This repository is a fork of
[`mxldyn/octamax`](https://github.com/mxldyn/octamax) by Maxolydian and inherits
its method and infrastructure — the container / update-chain analysis, the
guarded binary-patch build pipeline, the code-cave detour technique, and the
flashing procedure. Full lineage and acknowledgements:
[`CREDITS.md`](CREDITS.md).

---

## ⚠️ Warning — read before doing anything

**This is for personal study.** Updating an Elektron unit with anything other
than official firmware is risky: it puts the warranty in question and can leave
the unit needing the bootloader recovery path. Static analysis of the public OS
is harmless; *writing* a non-official OS to real hardware is not. Nothing here is
endorsed by, supported by, or affiliated with Elektron. If you flash a modified
OS you do so entirely at your own risk. If in doubt, don't flash — just read,
disassemble, and learn.

Elektron ships **one OS 1.40C image for the Octatrack MKI and MKII**; the boot
`0x46c8d18c` probe adapts the unit-specific details. All hardware testing in this
project is on an Octatrack **MKI** the author owns — including the flash that
confirmed the Bug-1 fix.

---

## What this firmware does

> **Branches.** The published **`main`** is the conservative line: the Bug-1 fix
> + MUTE MODE `OT` / `OT+FX` only, at the exact patch that was flashed and
> confirmed on hardware. **`wip/mute-mode`** is the working branch and carries
> everything below, including work that has **not** been on hardware. Per-feature
> status is in the tables.

### Bug 1 — Plays-Free MIDI manual-trig stall  ·  **fixed, hardware-confirmed (MKI)**

A Plays-Free MIDI track with trig quantize *Direct* and pattern scale *Per Track*
stalled after its first step on a manual trig: `FUN_4009b5c8` seeded the
per-track scale index with the **audio**-track stride for MIDI tracks. Fixed with
a 6-byte detour into a code cave (`tools/patch_trigscale.s`). Flashed to a real
Octatrack MKI (2026-08-28) — the stall is gone, no regression. Write-up:
[`NOTES.md`](NOTES.md) "Session 5–7"; emulator `tools/emu_trigbug.py`.

### MUTE MODE — a PERSONALIZE toggle for audio-track mute behaviour

Off by default (`MUTE MODE = OT`). Stored in a battery-backed PERSONALIZE word,
so a freshly flashed unit is stock until you opt in.

| mode | effect | build |
|---|---|---|
| **OT** | stock behaviour, byte-for-byte. | any |
| **OT+FX** | *soft mute*: on mute the dry signal cuts fast and clean (like a per-track STOP), the track's FX inserts ring their delay/reverb tails out, and a muted track's trigs make no sound. On **`wip/mute-mode` this also extends to SOLO** — a track silenced because another track is soloed gets the same soft cut instead of the stock hard cut (softmute V7). | `build_mutemode.py` |
| **DT** | pure *sequencer* mute, Digitakt-style: the voice that is already sounding keeps playing under its own AMP envelope, its FX ring, and only *new* trigs are suppressed. | `build_mutemode_dt.py` |

Sources: `tools/patch_mutemode.s`, `tools/patch_softmute.s` (V7 on this branch,
`--defsym DT_MODE=1` for the DT build); emulators `tools/emu_mutemode.py`,
`tools/emu_mute.py`, `tools/emu_solo.py`, `tools/emu_dt.py`; write-ups
[`NOTES.md`](NOTES.md) "Session 9–12". The toggle also writes the checksummed
`'ANDY'` battery-SRAM shadow so it survives a power cycle ("Session 19").

**A fourth mode is designed and reverse-engineered but not built** — `OTFX`:
instant dry cut, FX tails ring, and unmute **resumes the sample at the playhead**
(the current `OT+FX` and `DT` are *trig-mutes* — the track stays silent until the
next trig). Landing it makes the menu `OT / OTFX / OTFX-T / DT-T`. The per-frame
mute gate `FUN_40004db8` is fully disassembled; two DSP-behaviour unknowns
remain, and the plan is to flash `DT` first to settle them. Write-up:
[`NOTES.md`](NOTES.md) "Session 14".

### DIRECT JUMP — an Elektron-style immediate pattern change  ·  *emulator only, not flashed*

Toggled by **`[PTN]` + `[YES]`** (a transient "DIRECT JUMP ON/OFF" overlay), no
PERSONALIZE entry. When on, manually cueing a new pattern:

- switches on the **next step tick** instead of quantising to the end of the
  current pattern,
- keeps the **playhead step position** (the new pattern resumes where the old
  one was, modulo its length) rather than restarting at step 1,
- loads the new **Part immediately**,
- sends the MIDI Program Change ~1 step early.

The arranger and pattern chains are untouched. `tools/patch_directjump.s` — a
front-panel toggle stub + three hooks into the per-step sequencer engine
(`FUN_400a1eea`). `python3 tools/build_directjump.py` → `140C_KYOTI`.
`build_directjump_v2.py` is the same feature with a box-free toast overlay.
Write-up: [`NOTES.md`](NOTES.md) "Session 15" + "Session 21" + "Session 35";
emulator `tools/emu_directjump.py` (the hooks are exercised as stubs —
`FUN_400a1eea` itself has instructions Unicorn can't run, so this is **not** a
full-handler test). Hardware-only unknowns are listed in `NOTES.md`; **never
flashed**.

### SIDE-CHAIN COMPRESSOR — external key input for the stock DynamiX compressor  ·  *in progress, not flashed*

Adds a `KEY` parameter (and, at step 3, `KEY FLT` / `KEY GAIN` / `SC LISTEN`) to
the COMPRESSOR effect's page 2: pick one of the eight audio tracks to *drive* the
compression on the track the compressor sits on (classic kick-ducks-the-pad), and
it keeps keying even when the key track is muted. Scoped to the **same DSP core**
— a compressor on tracks 1–4 chooses a key among 1–4, one on 5–8 among 5–8.

This is a DSP56300 job, not ColdFire — the compressor is 180 DSP words. Built in
stages, none flashed:

| build | contents | state |
|---|---|---|
| `build_sidechain.py` | the `KEY` menu parameter only; the DSP is untouched, so it does nothing audible | menu + dynamic `T1..T8` formatter **emulator-verified** |
| `build_sidechain2.py` | + the DSP hooks: every track publishes its pre-FX block to a shared ring, and the compressor's detector reads the chosen track's ring. **SPATIALIZER is donated** for the code space and removed from the FX menu. | hooks **emulator-verified** under dsp56kEmu; the gain-reduction audio path is a **hardware** test |
| `build_sidechain3.py` | + `KEY GAIN` scaler + `KEY FLT` 2-pole Chamberlin SVF + `SC LISTEN` in the DSP | transforms **emulator-verified** vs a Python SVF reference |

Write-up: [`NOTES.md`](NOTES.md) "Session 17" (+ "Session 36"); DSP source
`tools/patch_sc_dsp.asm` / `patch_sc_dsp3.asm`; emulators `tools/emu_sidechain.py`,
`tools/emu_sc_dsp.py`, `tools/emu_sc_dsp3.py`.

### Backlog — scoped, not built

- **Auto-remove an emptied trigless lock.** When a LIVE-REC `[NO]`+knob erase
  clears a step's last p-lock, drop the now-purposeless trigless lock too. The
  data model is deeply mapped (`reference/kb/file-format.md`); the one missing
  piece is the `+0x4900` → stored-record commit, best settled by a hardware
  export-and-diff pass. Brief: [`NOTES.md`](NOTES.md) "Session 13" + "Session 39".

### Hardware-test status — read before you flash

| element | build | on-hardware status (Octatrack MKI) |
|---|---|---|
| Bug 1 manual-trig fix | all | **confirmed** — flashed 2026-08-28, stall gone, no regression |
| MUTE MODE menu + `OT+FX` soft **mute** mechanism | `build_mutemode.py` | **confirmed** — the Session-10 build was flashed and works |
| ↳ the `'ANDY'`-shadow persistence (survives power cycle) | `build_mutemode.py` | emulator-verified, **not yet flashed** |
| ↳ the **SOLO** extension (softmute V7) | `build_mutemode.py` (`wip/mute-mode`) | **emulator only**, never flashed |
| **DT** sequencer-mute mode | `build_mutemode_dt.py` | **emulator only**, never flashed |
| MUTE MODE 4th option (`OTFX` playhead-resume) | — | **reverse-engineered only**, not built |
| **DIRECT JUMP** pattern-change mode | `build_directjump*.py` | **emulator only** (stub-level), never flashed |
| side-chain `KEY` menu + formatter | `build_sidechain.py` / `build_sidechain3.py` | **emulator only**, never flashed |
| side-chain DSP hooks | `build_sidechain2.py` / `build_sidechain3.py` | hooks **emulator-verified** (dsp56kEmu); audio path untested, never flashed |

`OT` mode is byte-for-byte stock, and every mod is `OFF` by default. The soft
paths and DIRECT JUMP are validated in a ColdFire emulator (Unicorn, real image
bytes) — control-flow + frame-word edits, not the audio engine; the side-chain
DSP runs in dsp56kEmu. Nothing here proves how anything *sounds* on the unit.
Flash at your own risk; keep the official `.syx` on hand
([`FLASHING.md`](FLASHING.md)).

---

## What has been investigated

Verified against the official **OS 1.40C** — from the firmware's own checksums,
byte-exact decompilation, or direct disassembly. This is the reverse-engineering
foundation the firmware changes are built on. Consolidated write-ups:
[`ARCHITECTURE.md`](ARCHITECTURE.md); the address-keyed knowledge base:
[`reference/kb/`](reference/kb/); chronological log: [`NOTES.md`](NOTES.md);
mapped-vs-untouched: [`COVERAGE.md`](COVERAGE.md).

### Hardware
- **CPU:** Freescale/NXP **ColdFire** (likely MCF5445x, 32-bit, big-endian,
  ~266 MHz) — a 68000-family core, *not* ARM. The firmware drives the on-chip
  ATA controller in the MBAR region (`0xFC04_51xx`) characteristic of the MCF5445x.
- **Audio DSP:** Freescale **DSP56xxx** (DSP56721, two cores — tracks 1–4 / 5–8),
  confirmed by the 24-bit word size the boot loader uses uploading the DSP
  program 3 bytes at a time.
- **Storage:** **CompactFlash** (FAT16/32) over the ColdFire's on-chip ATA
  controller, reached through the FlexBus.

### Firmware format and update chain
Elektron ships a ZIP with **two transports of the same OS** — a `.bin` and a
`.syx` — both wrapping the same compressed container:

```
.bin  = [ELUP hdr][seed] + XOR-feedback( [len] + ELEK( aPLib( MAIN OS ) ) ) + checksum
.syx  = SysEx 7-bit(              ELEK( aPLib( MAIN OS ) )              )
```

- **ELUP layer** (`.bin` only): XOR obfuscation with feedback plus an additive
  checksum. Reimplemented in `tools/make_bin.py` / `tools/bin_decode.py`,
  validated by regenerating Elektron's own official `.bin` byte-for-byte.
- **ELEK layer:** a proprietary container whose payload is compressed with
  **aPLib**; it decompresses to the **MAIN OS** (1,112,560 bytes, base `0x40000400`).
- **No cryptographic signature** on any layer — the OS is analyzable and, with
  recalculated checksums, rebuildable. That is *why* the format can be repacked;
  it is not a security bypass.
- The updater validates the OS (`FUN_4007f748`) with explicit error codes:
  `-2` not a valid OS · `-3` length · `-4` checksum · `-5` version string
  `<"0156"` · `-6` no downgrade. (`-5` is a version floor, not a unit-model gate.)

### Operating system
- A **proprietary preemptive microkernel** (banner `ElektronOctatrack DPS-1` —
  not MQX/ThreadX/VxWorks). Task Control Blocks, per-priority ready queues,
  context switch via `TRAP #0`, blocking message queues, a time slice driven by
  the ColdFire PIT timer (`0xFC08_0000`).
- The same message-queue pattern unifies the firmware: the ATA "async queues" and
  the audio "voice mailboxes" *are* kernel message queues.

### Audio engine and sequencer
- 8 track voices in the `0x80000000` shared-RAM window (base `0x800049d8`,
  stride `0xA8`).
- Control path: a sequencer trig writes a voice mailbox → a control-rate frame
  builder assembles a parameter frame into a **double buffer** → handshake to the
  **DSP56xxx** over MMIO at `0x20000000`, which does the real-time synthesis.
- Work split: **ColdFire = control** (RTOS, sequencer, parameter assembly);
  **DSP = signal** (playback, time-stretch, filters, FX).

---

## Repository layout

```
START_HERE.md        onboarding + current frontier (read first)
README.md            this — what the firmware is, and lineage
BUILD_KYOTI.md       roll-your-own build guide (Bug 1 fix, MUTE MODE, side-chain)
CREDITS.md           lineage and acknowledgements
ARCHITECTURE.md      consolidated architecture (hardware, OS, memory map, container)
COVERAGE.md          what firmware subsystems are mapped vs untouched
NOTES.md             the full chronological reverse-engineering log
FLASHING.md          safe-flashing guide + bootloader recovery net (read before flashing)

reference/kb/         distilled knowledge base (address map, formats, DSP) — ours + external RE
reference/            EXTERNAL_RESEARCH.md (the mined prior-art repos + workflow), UPSTREAM_INBOX.md
reference/upstream-notes.md   inherited octamax mod-design notes (not part of this firmware)
refs/                MANIFEST.{toml,lock} tracked; the clone cache under it is git-ignored
sysex/               the Bug-1 fix as JSON hunks + a no-assembler applier
tools/               build scripts, ColdFire patch sources, Unicorn + DSP56300 emulators, packers
tools/attic/         inherited octamax mod patch sources — kept for RE cross-reference, not built here
tools/refs/          sync.py / whatsnew.py — clone + track the external-RE repos
tools/ghidra/        Ghidra headless helpers; attic/ = one-shot probe scripts (provenance)
fetch-os.sh          download + extract the official OS
analyze.sh           entropy + binwalk + strings + container unpack -> out/
setup.sh             clone/patch/build elektron-firmware-tool into vendor/
disasm.sh            radare2 disassembly (m68k BE, base wired)
```

Downloaded Elektron binaries and generated images (`downloads/`, `out/`,
`vendor/*.bin`, `*.syx`, `*.bin`, `*.pdf`) are **git-ignored on purpose** — none
are redistributed.

Maxolydian's own octamax behaviour mods (lazy Part transitions, no BANK/PTN
countdown, arp key-scales, LED/encoder "dirty" indicators, boot branding) are
**not** part of any OT Kyoti FW build. Their patch sources live in
[`tools/attic/`](tools/attic/) for reverse-engineering cross-reference; see
[`CREDITS.md`](CREDITS.md).

---

## Building

See **[`BUILD_KYOTI.md`](BUILD_KYOTI.md)** for the full walkthrough. In short:

```sh
./fetch-os.sh && ./analyze.sh && ./setup.sh   # one-time: bring your own OS 1.40C + tools
python3 tools/build_mutemode.py               # -> out/OCTATRACK_*MUTEMODE.{syx,bin}
```

Every build is a guarded binary patch: it asserts the stock bytes at each splice,
verifies the code caves, derives detour targets from the linker symbol table, and
round-trips through `elektron-firmware-tool`. It aborts before writing if the
stock file is wrong, already patched, or the checksum is off.

---

## Legality (not legal advice)

- Static analysis of the publicly distributed OS carries **zero risk to the
  hardware** and is the point of this project.
- EU: Directive 2009/24/EC Art. 5 (observe/study/test a program you lawfully use)
  and Art. 6 (decompilation for interoperability). Elektron's EULA may contain
  anti-RE clauses — a contractual matter separate from copyright.
- Private and educational use is low-risk. Redistributing modified binaries is a
  different question; this repo deliberately redistributes **no** Elektron binary.

---

*OT Kyoti FW is an independent, unofficial, educational project — a fork of
`mxldyn/octamax`. "Elektron" and "Octatrack" are trademarks of Elektron Music
Machines MAV AB, used here only to identify the hardware under study. Not
affiliated with or endorsed by Elektron.*
