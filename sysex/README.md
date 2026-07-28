# sysex/ — building the patched firmware

This folder does **not** contain a firmware image. It contains the patch — the ~441
bytes of ColdFire code authored in this repository — plus a script that applies it to
**your own** copy of the official Elektron OS.

No Elektron binary is redistributed here. You download the stock OS yourself; the
script produces a `.syx` byte-identical to the reference build.

## Requirements

- The **official OS 1.40C for Octatrack MKII** (`OCTATRACK_OS1.40C.syx`), from
  elektron.se. `./fetch-os.sh` in the repo root downloads and extracts it.
- **`elektron-firmware-tool`** — `./setup.sh` clones and builds it into `vendor/`.
  Upstream: <https://github.com/mischa85/elektron-firmware-tool>
- Python 3.8+

## Usage

```sh
./fetch-os.sh                      # downloads the official OS into downloads/
./setup.sh                         # builds elektron-firmware-tool into vendor/

python3 sysex/apply_patch.py \
    -i downloads/extracted/OCTATRACK_OS1.40C.syx \
    -o OCTATRACK_MAXOLYDIAN.syx
```

```
[1/5] stock .syx checksum ok
[2/5] extracted section_3_MAIN_OS.bin (1,112,560 bytes)
[3/5] applied 6 hunks (537 bytes)
[4/5] repacked -> OCTATRACK_MAXOLYDIAN.syx
[5/5] output checksum ok — byte-identical to the reference build
```

The script aborts before writing anything if the stock file's checksum is wrong, if the
original bytes under any hunk don't match (wrong firmware, or already patched), or if
the patched image's checksum is off. `--force` relaxes only the outer file checks — the
per-hunk byte verification always holds.

## What the patch changes

441 bytes out of 1,112,560 (0.04%) of the MAIN OS section.

| id | source | effect |
|---|---|---|
| `lazy-part-apply` | `tools/patch.s` | Sounding tracks keep their params on pattern change; they apply the destination Part on their first trig — no volume jump. |
| `gui-in-transition` | `tools/patch_gui.s` | While a track is in transition, the encoders edit the **source** Part and update the sound live. |
| `sticky-scenes` | `tools/patch_scene.s` | Scene A/B selection is kept across pattern/Part changes instead of jumping to the destination Part's saved scenes. |
| `boot-branding` | ELEK header (`-V`) | Boot splash and SYSTEM STATUS show `MAXOLYDIAN` instead of `1.40C`. |

The three code patches live in a free code cave at `0x400d64e0`–`0x400d6785`, reached by
detours at `0x40009094` (part apply) and `0x40052e98` (encoder editor). Design,
addresses and reverse-engineering notes are in [`../NOTES.md`](../NOTES.md) and
[`../ARCHITECTURE.md`](../ARCHITECTURE.md).

`patches/maxolydian-1.0.json` holds each hunk with its load address, the original bytes
and the replacement bytes, so the change is auditable without running anything.

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
them. Validated in a ColdFire emulator and on one MKII unit. **Use at your own risk.**
