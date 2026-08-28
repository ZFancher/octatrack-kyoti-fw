# sysex/ — building the patched firmware

This folder does **not** contain a firmware image. It contains the patch — the ColdFire
code authored in this repository, captured hunk by hunk as JSON — plus a script that
applies it to **your own** copy of the official Elektron OS.

No Elektron binary is redistributed here. You download the stock OS yourself; the
script produces a `.syx` byte-identical to the reference build.

Two build profiles:

| JSON | contents | size vs stock |
|---|---|---|
| `patches/playsfreefix-r1.json` | the MIDI manual-trig **bug fix** only, on otherwise-stock 1.40C | 2 hunks, 72 B |
| `patches/maxolydian-r13.json` | the fix + all the MAXOLYDIAN behavior mods (the default) | 31 hunks, ~1.5 KB |

Regenerate either from a fresh build with `gen_patch_json.py`.

## Requirements

- The **official OS 1.40C for Octatrack MKII** (`OCTATRACK_OS1.40C.syx`), from
  elektron.se. `./fetch-os.sh` in the repo root downloads and extracts it.
- **`elektron-firmware-tool`** — `./setup.sh` clones it into `vendor/`, applies
  `tools/elektron-firmware-tool.patch` and builds it.
  Upstream: <https://github.com/mischa85/elektron-firmware-tool>

  The patch is two small local changes, both required to reproduce this build:
  `set_version()` writes the full 10-character ELEK display field from offset `0x08`
  (upstream only writes from `0x0D`, which fits 5 characters), and `EFT_EMIT_CONTAINER`
  dumps the rebuilt container so `tools/make_bin.py` can wrap it.
- Python 3.8+

## Usage

```sh
./fetch-os.sh                      # downloads the official OS into downloads/
./setup.sh                         # builds elektron-firmware-tool into vendor/

# fix + mods (maxolydian-r13.json is the default)
python3 sysex/apply_patch.py \
    -i downloads/extracted/OCTATRACK_OS1.40C.syx \
    -o OCTATRACK_OS1.40C_MAXO_R13.syx

# fix only, on stock
python3 sysex/apply_patch.py -p sysex/patches/playsfreefix-r1.json \
    -i downloads/extracted/OCTATRACK_OS1.40C.syx \
    -o OCTATRACK_OS1.40C_PLAYSFREEFIX.syx
```

```
[1/5] stock .syx checksum ok
[2/5] extracted section_3_MAIN_OS.bin (1,112,560 bytes)
[3/5] applied 31 hunks (1792 bytes)
[4/5] repacked -> OCTATRACK_OS1.40C_MAXO_R13.syx
[5/5] output checksum ok — byte-identical to the reference build
```

The script aborts before writing anything if the stock file's checksum is wrong, if the
original bytes under any hunk don't match (wrong firmware, or already patched), or if
the patched image's checksum is off. `--force` relaxes only the outer file checks — the
per-hunk byte verification always holds.

## What the patch changes

Build B (`maxolydian-r13.json`) changes ~1,490 bytes out of 1,112,560 (0.13%) of the
MAIN OS section. Build A (`playsfreefix-r1.json`) changes 72.

| id | source | effect | gate |
|---|---|---|---|
| `midi-trig-scale-fix` | `tools/patch_trigscale.s` | A Plays-Free MIDI track with trig quant *Direct* + pattern scale *Per Track* no longer stalls after step 1 on a manual trig. `FUN_4009b5c8` was seeding the per-track scale index with the audio track stride for MIDI tracks. | **always on** (bug fix) |
| `arp-key-scales` | `tools/patch_arp.s` | ARP F-knob key-scale gains 10 qualities (Greek modes + blues + phrygian-dominant / melodic / octatonic / hirajoshi); `OFF`/`maj`/`min` byte-identical to stock. | always on |
| `lazy-transitions` | `tools/patch.s`, `patch_enc.s`, `patch_led.s`, `patch_scene2.s` | Sounding tracks keep the previous Part's definition on a pattern change (track LED dimmed) until a trig, a manual trig or an encoder move; A/B scene pointers stay on the same slots. | PERSONALIZE, off |
| `no-bank-ptn-countdown` | `tools/patch_notimer.s` | SELECT BANK / SELECT PATTERN windows stop expiring. | PERSONALIZE, off |
| `personalize-options` | `tools/patch_notimer.s` | Adds the two switches above to the PERSONALIZE menu, unchecked by default. | — |
| `boot-branding` | ELEK header (`-V`) | Boot splash and SYSTEM STATUS show `MAXOLYDIAN` instead of `1.40C` (build B only). | — |

The MIDI-trig fix is a detour at `0x4009b6f2` into a 62-byte code cave at `0x400d7b00`.
The MAXOLYDIAN code lives in the same free cave region (`0x400d64e0` onward) reached by
6-byte jump detours; `tools/build.py` derives every detour target from the linker symbol
table and verifies the original bytes at each site. Design and RE notes:
[`../NOTES.md`](../NOTES.md) ("Session 6" for the fix), [`../ARCHITECTURE.md`](../ARCHITECTURE.md).

Each JSON holds every hunk with its load address, the original bytes and the replacement
bytes, so the change is auditable without running anything.

> Only the current revision is published. Earlier ones carried a GUI-in-transition patch
> that crashed the unit; it is gone — the current spec wants an encoder move to *end* the
> transition, the opposite of what that patch did. See `../NOTES.md`.

## Flashing from the CF card

`tools/make_bin.py` wraps the container into an ELUP `.bin` for the OS UPGRADE menu, which
is much faster than MIDI. Its correctness is not assumed: it regenerates Elektron's own
official `.bin` byte-for-byte from that file's container.

```sh
# build B
EFT_EMIT_CONTAINER=elek.bin elektron-firmware-tool -i stock.syx -c 3 out/mainos.bin \
    -V MAXOLYDIAN -o out.syx
python3 tools/make_bin.py elek.bin -o OCTATRACK_MAXO_R13.bin

# build A (no -V: version field stays "1.40C")
EFT_EMIT_CONTAINER=elek_a.bin elektron-firmware-tool -i stock.syx -c 3 \
    out/mainos_trigscale_only.bin -o out_a.syx
python3 tools/make_bin.py elek_a.bin -o OCTATRACK_PLAYSFREEFIX.bin
```

## Before you flash

Read [`../FLASHING.md`](../FLASHING.md) first. Short version:

- The upgrade goes over **MIDI DIN, not USB**.
- Keep the **official `.syx`** at hand — `[FUNC]` + power on → `[TRIG 3]` (MIDI UPGRADE)
  recovers the unit even if the OS is corrupt, because the bootloader is not touched.
- Never cut power during `UPDATING FLASH`.
- Your CF card, projects and samples are not affected.

## Disclaimer

This is unofficial, modified firmware, built for personal study of hardware its author
owns. It is not endorsed by or supported by Elektron, and it will not be supported by
them. Validated in a ColdFire emulator; the MAXOLYDIAN mods are also confirmed on one
MKII unit, the MIDI manual-trig fix is not yet hardware-tested. See FLASHING.md §6 for
the failure playbook. **Use at your own risk.**
