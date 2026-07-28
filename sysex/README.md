# sysex/ — building the patched firmware

This folder does **not** contain a firmware image. It contains the patch — the ~1,269
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
[3/5] applied 20 hunks (1269 bytes)
[4/5] repacked -> OCTATRACK_MAXOLYDIAN.syx
[5/5] output checksum ok — byte-identical to the reference build
```

The script aborts before writing anything if the stock file's checksum is wrong, if the
original bytes under any hunk don't match (wrong firmware, or already patched), or if
the patched image's checksum is off. `--force` relaxes only the outer file checks — the
per-hunk byte verification always holds.

## What the patch changes

1,269 bytes out of 1,112,560 (0.11%) of the MAIN OS section.

| id | source | effect |
|---|---|---|
| `lazy-part-apply` | `tools/patch.s` | Sounding tracks keep their params on pattern change; they apply the destination Part on their first trig — no volume jump. |
| `gui-in-transition` | `tools/patch_gui2.s` | While a track is in transition, the encoders edit the **source** Part and update the sound live. |
| `sticky-scenes-v2` | `tools/patch_scene2.s` | Scene A/B selection is kept across pattern/Part changes; manual assignment always wins. |
| `dirty-track-leds` | `tools/patch_led.s` | A track still sounding with the source Part's params is lit dimmer (`0xF` → `0x5`) until it is re-trigged. |
| `dirty-scene-trig` | `tools/patch_trig.s` | The selected scene trig goes amber (both dies of the bi-colour LED) while any track is in transition. |
| `no-bank-ptn-countdown` | in-place: `FUN_40056ab8` → `rts` | The SELECT BANK / SELECT PATTERN windows no longer expire after four seconds; press the same key again to abort. |
| `personalize-options` | `tools/patch_menu.s` | Adds NO BANK/PTN TIMER and LAZY TRANSITIONS to the PERSONALIZE menu. **Both unchecked by default**, so an unconfigured unit behaves exactly like stock. |
| `boot-branding` | ELEK header (`-V`) | Boot splash and SYSTEM STATUS show `MAXOLYDIAN` instead of `1.40C`. |

The code patches live in a free code cave at `0x400d64e0`–`0x400d697c`, reached by
detours at `0x40009094` (part apply), `0x40052e98` (encoder editor), `0x4003f1b4`
(crossfader), `0x40083fb4` (track LED painter) and `0x40034b5e` (trig painter). Design,
addresses and reverse-engineering notes are in [`../NOTES.md`](../NOTES.md) and
[`../ARCHITECTURE.md`](../ARCHITECTURE.md).

`patches/maxolydian-6.0.json` holds each hunk with its load address, the original bytes
and the replacement bytes, so the change is auditable without running anything.

> Only the current revision is published. Earlier ones (1.0–3.0) carried a reentrancy
> bug in the GUI patch that could crash the unit — holding `[SCENE B]` while turning an
> encoder on a track in transition made a nested call clobber the single saved return
> address, and the unit jumped to a dead address (`EXCEPTION VEC:0B`). Fixed in 4.0 by
> a guard that makes a nested entry behave like stock. See `../NOTES.md`.

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
