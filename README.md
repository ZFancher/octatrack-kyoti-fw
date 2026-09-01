```
   ▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄
  ▐░░  O T   K Y O T I   F W  ·  Octatrack firmware study  ░░▌
  ▐░░  a reverse-engineering workspace · educational use   ░░▌
   ▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀
```

# OT Kyoti FW

**A reverse-engineering workspace for the Elektron Octatrack OS — and a set of
small, optional, reversible firmware changes built from it.**

This repository is a **fork of [`mxldyn/octamax`](https://github.com/mxldyn/octamax)**
by Maxolydian. It keeps that project's method — study the OS, prove the
understanding by making tiny guarded patches, redistribute **no** Elektron
binary — and continues it along one line of work: a hardware-confirmed MIDI bug
fix, and a **MUTE MODE** that changes how an audio-track mute or solo behaves.

Full lineage and acknowledgements: [`CREDITS.md`](CREDITS.md).

Everything here is **educational**. You bring your own copy of the official OS;
the tools analyze it and, if you ask, produce a modified image byte-for-byte
reproducibly from *your* copy. No `.bin` / `.syx` is ever distributed — only the
tools to roll your own.

---

## ⚠️ Warning — read before doing anything

**This is for personal study.** Updating an Elektron unit with anything other
than official firmware is risky: it puts the warranty in question and can leave
the unit needing the bootloader recovery path. Static analysis of the public OS
is harmless; *writing* a non-official OS to real hardware is not. Nothing here is
endorsed by, supported by, or affiliated with Elektron. If you flash a modified
OS you do so entirely at your own risk. If in doubt, don't flash — just read,
disassemble, and learn.

Hardware testing in this fork is done on an Octatrack **MKI** the author owns.

---

## What has been investigated

Verified against the official **OS 1.40C** — from the firmware's own checksums,
byte-exact decompilation, or direct disassembly. Elektron ships one 1.40C image
for both the Octatrack MKI and MKII; the boot `0x46c8d18c` probe adapts it.
Consolidated write-ups: [`ARCHITECTURE.md`](ARCHITECTURE.md); chronological log:
[`NOTES.md`](NOTES.md); mapped-vs-untouched: [`COVERAGE.md`](COVERAGE.md).

### Hardware
- **CPU:** Freescale/NXP **ColdFire** (likely MCF5445x, 32-bit, big-endian,
  ~266 MHz) — a 68000-family core, *not* ARM. The firmware drives the on-chip
  ATA controller in the MBAR region (`0xFC04_51xx`) characteristic of the MCF5445x.
- **Audio DSP:** Freescale **DSP56xxx**, confirmed by the 24-bit word size the
  boot loader uses when uploading the DSP program 3 bytes at a time.
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
  `-2` not a valid OS · `-3` length · `-4` checksum · `-5` MK1 not allowed ·
  `-6` no downgrade.

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

## What this fork adds

### Bug 1 — Plays-Free MIDI manual-trig stall  ·  **fixed, hardware-confirmed (MKI)**

A Plays-Free MIDI track with trig quantize *Direct* and pattern scale *Per Track*
stalled after its first step on a manual trig: `FUN_4009b5c8` seeded the
per-track scale index with the **audio**-track stride for MIDI tracks. Fixed with
a 6-byte detour into a code cave (`tools/patch_trigscale.s`). Flashed to a real
Octatrack MKI (2026-08-28) — the stall is gone, no regression. Write-up:
[`NOTES.md`](NOTES.md) "Session 5–7"; emulator `tools/emu_trigbug.py`.

### MUTE MODE — a PERSONALIZE toggle for audio-track mute / solo behaviour

Off by default (`MUTE MODE = OT`). Stored in a battery-backed PERSONALIZE word,
so a freshly flashed unit is stock until you opt in.

| mode | effect |
|---|---|
| **OT** | stock behaviour, byte-for-byte. |
| **OT+FX** | *soft mute*: the dry signal cuts fast and clean (like a per-track STOP), the track's FX inserts ring their delay/reverb tails out, new trigs on a silenced track make no sound. SOLO silencing folds into the same path. |
| **DT** | *Digitakt-style sequencer mute*: a voice already sounding keeps playing under its own amp envelope (fades/sustains/loops per the AMP page), its FX ring, and only **new** trigs are suppressed. |

OT+FX is hardware-confirmed on MKI. DT is emulator-verified, not yet flashed.
Sources: `tools/patch_mutemode.s`, `tools/patch_softmute.s`; emulators
`tools/emu_mutemode.py`, `tools/emu_solo.py`, `tools/emu_dt.py`; write-ups
[`NOTES.md`](NOTES.md) "Session 9–13".

### Carried in from octamax (Maxolydian)

Boot branding, no BANK/PTN countdown, lazy Part transitions, arp key-scales, LED
dirty indicators — all optional, all off by default. See [`CREDITS.md`](CREDITS.md),
[`sysex/README.md`](sysex/README.md), [`HANDOFF.md`](HANDOFF.md).

---

## Repository layout

```
START_HERE.md        onboarding + current frontier (read first)
README.md            this — project intent and lineage
BUILD_KYOTI.md       roll-your-own build guide (Bug 1 fix, MUTE MODE, DT)
CREDITS.md           lineage and acknowledgements
ARCHITECTURE.md      consolidated architecture (hardware, OS, memory map, container)
COVERAGE.md          what firmware subsystems are mapped vs untouched
NOTES.md             the full chronological reverse-engineering log
FLASHING.md          safe-flashing guide + bootloader recovery net (read before flashing)
DESIGN_BANKPAGE.md   design notes for the shelved live bank-paging feature
HANDOFF.md           the shipped LED / encoder "dirty indicator" patches
octamax_handoff_*.md frozen briefs from earlier sessions

sysex/               the older MAXOLYDIAN patch as JSON hunks + a no-assembler applier
tools/               build scripts, ColdFire patch sources, Unicorn emulators, packers
tools/ghidra/        Ghidra headless helpers; attic/ = one-shot probe scripts (provenance)
fetch-os.sh          download + extract the official OS
analyze.sh           entropy + binwalk + strings + container unpack -> out/
setup.sh             clone/patch/build elektron-firmware-tool into vendor/
disasm.sh            radare2 disassembly (m68k BE, base wired)
```

Downloaded Elektron binaries and generated images (`downloads/`, `out/`,
`vendor/*.bin`, `*.syx`, `*.bin`, `*.pdf`) are **git-ignored on purpose** — none
are redistributed.

---

## Building

See **[`BUILD_KYOTI.md`](BUILD_KYOTI.md)** for the full walkthrough. In short:

```sh
./fetch-os.sh && ./analyze.sh && ./setup.sh   # one-time: bring your own OS 1.40C + tools
python3 tools/build_mutemode_dt.py            # -> out/OCTATRACK_*MUTEMODE_DT.{syx,bin}
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
