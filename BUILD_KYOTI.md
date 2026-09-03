# Roll your own OT Kyoti FW

This builds a **modified Octatrack OS 1.40C** from *your own* copy of the official
firmware. No Elektron binary is included here or produced for anyone but you; the
build is byte-for-byte reproducible from the stock file.

> **Read [`FLASHING.md`](FLASHING.md) before you flash anything.** Flashing
> non-official firmware risks the warranty and can leave the unit needing the
> bootloader recovery path. Static analysis is harmless; writing to hardware is
> not. The author runs these on an Octatrack **MKI** he owns.

> **Branch:** this is `wip/mute-mode`. The published **`main`** branch builds
> only what has run on hardware — `build_trigscale_only.py`, `build_mutemode.py`
> (softmute **V6b**, `OT` / `OT+FX`), `build_softmute.py`. This branch's
> `build_mutemode.py` is softmute **V7** (the `OT+FX` cut extended to SOLO), and
> it adds the DT mode and the side-chain builds below — all emulator-verified,
> none flashed. See *Hardware-test status*.

## What you get

| build command | version string | contents |
|---|---|---|
| `python3 tools/build_trigscale_only.py` | `1.40C` (unchanged) | **Bug 1 fix only** — the Plays-Free MIDI manual-trig stall — on otherwise-stock 1.40C |
| `python3 tools/build_mutemode.py` | `140C_KYOTI` | Bug 1 fix + **MUTE MODE** toggle: `OT` (stock) / `OT+FX` (soft mute — dry cuts clean, FX tails ring; **and, on this branch, soloed-out tracks get the same soft cut**) |
| `python3 tools/build_mutemode_dt.py` | `140C_KYOTI` | as above **+ a third mode `DT`** — pure sequencer mute (a sounding voice rides its own AMP envelope; only new trigs are suppressed) |
| `python3 tools/build_softmute.py` | `140C_KYOTI` | Bug 1 fix + the same soft mute **always on**, no menu entry |
| `python3 tools/build_sidechain.py` | `140C_KYOTI` | Bug 1 fix + a `KEY` parameter on the COMPRESSOR page — **menu only, DSP untouched** (does nothing audible; proves the control surface) |
| `python3 tools/build_sidechain2.py` | `140C_KYOTI` | + the DSP hooks: same-DSP-core side-chain — a compressor keys off a chosen track (even muted). **SPATIALIZER is donated** for the code space and removed from the FX menu |
| `python3 tools/build_sidechain3.py` | `140C_KYOTI` | Bug 1 fix + the full side-chain **menu scaffolding** (`KEY` `KFLT` `KGAIN` `MON`), **no DSP** |

All mods are **OFF by default** (`MUTE MODE = OT`; `KEY = OFF`, stored per Part).
A freshly flashed unit is indistinguishable from stock until you opt in. An OS
upgrade resets PERSONALIZE.

These builds carry **no** Maxolydian mods (no arp key-scales, lazy transitions,
BANK/PTN countdown removal, LED indicators, or `MAXOLYDIAN` branding). For those,
use `tools/build.py` / `sysex/apply_patch.py` instead — see [`sysex/README.md`](sysex/README.md).

### Hardware-test status

| element | status |
|---|---|
| Bug 1 manual-trig fix | **hardware-confirmed** (flashed 2026-08-28; the whole of `build_trigscale_only.py`) |
| MUTE MODE menu + `OT+FX` soft **mute** mechanism | **hardware-confirmed** — the Session-10 build (softmute V6b, on `main`) was flashed and works |
| soft cut extended to **SOLO** (softmute V7 — this branch's `build_mutemode.py`) | **emulator only**, never flashed |
| **DT** mode (`build_mutemode_dt.py`) | **emulator only**, never flashed |
| side-chain `KEY` menu + formatter (`build_sidechain.py`, `build_sidechain3.py`) | **emulator only**, never flashed |
| side-chain DSP hooks (`build_sidechain2.py`) | hooks **emulator-verified** under dsp56kEmu; the audio result is untested |

The ColdFire emulator (Unicorn, real image bytes) proves control-flow and the
DSP frame-word edits; the DSP emulator (dsp56kEmu) runs the actual DSP56300
code. Neither proves how anything *sounds* on the unit. `OT` mode is
byte-for-byte stock. Flash at your own risk.

Each build emits, in `out/`:

```
mainos_*.bin                      the patched MAIN OS section
elek_*.bin                        the rebuilt ELEK container
OCTATRACK_OS1.40C_*.syx           MIDI-DIN upgrade transport
OCTATRACK_*.bin                   CF-card OS UPGRADE transport (faster)
```

## Prerequisites

- **Python 3.8+**
- **ColdFire cross-assembler** — `m68k-elf-as`, `m68k-elf-ld`, `m68k-elf-objcopy`
  (targeting `-mcpu=5407`). On macOS: `brew install m68k-elf-gcc` (or a
  `m68k-elf-binutils` formula/tap).
- **Your own copy of the official OS 1.40C** — from
  <https://www.elektron.se/support-downloads/octatrack-mkii>. The same image
  serves MKI and MKII.
- *For `build_sidechain2.py` only* — the DSP56300 toolchain (`dsp_asm`,
  `dsp56kDisassemble`) built into `vendor/dsp56300/`, and the external-RE clone
  cache (`python3 tools/refs/sync.py`, which `build_sidechain2.py` reads
  `dsp_modmap.py` from). `build_sidechain.py` / `build_sidechain3.py` need
  neither — they are ColdFire-only.

## One-time setup

```sh
./fetch-os.sh     # downloads the official OS 1.40C into downloads/ and extracts it
./analyze.sh      # unpacks it -> out/raw/section_3_MAIN_OS.bin  (the decompressed MAIN OS)
./setup.sh        # clones + patches + builds elektron-firmware-tool into vendor/
```

`fetch-os.sh` pulls the Elektron download; if the URL has moved, drop the
`OCTATRACK_OS1.40C.zip` (or the extracted `.syx`) into `downloads/` yourself and
re-run `./analyze.sh`.

## Build

```sh
python3 tools/build_mutemode.py               # -> out/OCTATRACK_*MUTEMODE.{syx,bin}
# or build_softmute.py (always-on, no menu), or build_trigscale_only.py (fix only)
```

Every build is a **guarded binary patch**: it asserts the stock bytes at each
splice site, checks the code caves are free / non-overlapping / inside the free
zone, derives every detour target from the linker symbol table (never
hardcoded), and round-trips the result through `elektron-firmware-tool`. It
aborts before writing if anything is off — a wrong stock file, an already-patched
image, or a checksum mismatch.

Pass a custom version string as the first argument if you want
(`python3 tools/build_mutemode.py MY_BUILD`), but the Kyoti builds default to
`140C_KYOTI` and that is what appears on the boot splash and SYSTEM STATUS.

## The reproducible patch (no assembler needed)

`sysex/` carries the older MAXOLYDIAN patch set captured hunk-by-hunk as JSON
(load address + expected original bytes + replacement bytes) and applies it with
`sysex/apply_patch.py` — no cross-assembler required. See
[`sysex/README.md`](sysex/README.md). The MUTE MODE and side-chain work is
build-from-source only.

## Flashing

See [`FLASHING.md`](FLASHING.md). Short version: MIDI **DIN** (not USB) for the
`.syx`, or **PROJECT → OS UPGRADE** from the CF card for the `.bin` (much
faster). Keep the official `.syx` on hand — `[FUNC]` + power on → `[TRIG 3]`
recovers the unit even from a bad OS, because an OS update never touches the
bootloader. Never cut power during `UPDATING FLASH`. Your CF card, projects and
samples are untouched by an OS update.
