# OS container & transport — ELUP / ELEK / aPLib / `.bin` vs `.syx`

How the firmware image is packed, checksummed, and delivered. This repo already
has the working knowledge in `ARCHITECTURE.md` + `sysex/` + `vendor/`; this file
is the merge point for anything the external tools add or correct.

## Status

Well covered here already (`COVERAGE.md`: "OS format & update — complete").
`vendor/elektron-firmware-tool` (patched, see `sysex/README.md`) does the real
pack/unpack. `refs/elektron-firmware-tool/` is the pristine upstream for diffing.

## Anchors (from this repo)

| Thing | Where |
|---|---|
| decompressed MAIN OS | `out/raw/section_3_MAIN_OS.bin`, base `0x40000400` |
| container / aPLib / checksum / ATA write / MIDI upgrade | `ARCHITECTURE.md`, `FUN_40001d4c` = DSP loader |
| local patch to the tool | `tools/elektron-firmware-tool.patch` (2 changes, documented in `sysex/README.md`) |
| build a flashable | `tools/build_*.py` → `.syx` (MIDI) + `.bin` (CF) |

## To import / verify from `refs/`

- **elektron-firmware-tool** `format.h`, `main.c`, `compress.c`, `integrity.c` —
  check for format-field or checksum fixes newer than our vendored copy
  (`whatsnew.py elektron-firmware-tool`).
- **octabam** and **octa-bt-pt** both roll their own image writers in Python —
  cross-check their checksum / section handling against ours; cheap validation
  that our `build_*.py` matches independent implementations.

_(No imported findings yet — 2026-09-02.)_
