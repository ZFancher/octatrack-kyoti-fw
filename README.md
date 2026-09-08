```
   ▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄
  ▐░░  O T   K Y O T I   F W  ·  custom Octatrack firmware  ░░▌
  ▐░░  small · optional · reversible · educational          ░░▌
   ▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀
```

# OT Kyoti FW

**A custom firmware for the Elektron Octatrack (OS 1.40C) — a hardware-confirmed
MIDI bug fix and a small, optional, reversible mute-behaviour change, built from a
reverse-engineering study of the stock OS.**

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

> **Branches.** This **`main`** branch is the conservative line: the Bug-1 fix +
> MUTE MODE `OT` / `OT+FX` only, at the exact patch that was flashed and confirmed
> on hardware. The [`wip/mute-mode`](../../tree/wip/mute-mode) branch is the
> working frontier — it adds a `DT` mute mode, the soft cut extended to SOLO, a
> DIRECT JUMP pattern-change mode and an in-progress DSP side-chain compressor,
> all emulator-verified and none flashed.

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

| mode | effect |
|---|---|
| **OT** | stock behaviour, byte-for-byte. |
| **OT+FX** | *soft mute*: on mute the dry signal cuts fast and clean (like a per-track STOP), the track's FX inserts ring their delay/reverb tails out, and a muted track's trigs make no sound. SOLO is unchanged (stock hard cut). |

Sources: `tools/patch_mutemode.s`, `tools/patch_softmute.s` (V6b); emulators
`tools/emu_mutemode.py`, `tools/emu_mute.py`; write-ups [`NOTES.md`](NOTES.md)
"Session 9–10" (+ "Session 19" for the `'ANDY'`-shadow power-cycle persistence).

### Hardware-test status — read this before you flash

| element | on-hardware status (Octatrack MKI) |
|---|---|
| Bug 1 manual-trig fix | **confirmed** — flashed 2026-08-28, stall gone, no regression |
| MUTE MODE menu + `OT+FX` soft mute | **confirmed** — the Session-10 build was flashed and works; `patch_softmute.s` here (V6b) is a faithful reconstruction of it, emulator-verified |
| the `'ANDY'`-shadow persistence (survives power cycle) | emulator-verified, **not yet flashed** |

`OT` mode is byte-for-byte stock. The soft path is also validated in a ColdFire
emulator (Unicorn, real image bytes), which proves control-flow and the DSP
frame-word edits but does not model the DSP or the audio engine. Flash at your
own risk; keep the official `.syx` on hand ([`FLASHING.md`](FLASHING.md)).

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
BUILD_KYOTI.md       roll-your-own build guide (Bug 1 fix, MUTE MODE)
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
tools/               build scripts, ColdFire patch sources, Unicorn emulators, packers
tools/attic/         inherited octamax mod patch sources — kept for RE cross-reference, not built here
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
