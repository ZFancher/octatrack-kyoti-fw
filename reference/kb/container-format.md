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

## `.bin` (ELUP) transport — octa-bt-pt's independent implementation

> source: `refs/octa-bt-pt/tools/make_bin.py` @ `e970dd0` · fetched 2026-09-02 · confidence: **C** (produces a byte-identical round trip of the official 1.40C image)

`.bin` = ELUP container. `word[0]` magic `0x454C5550` ("ELUP"). Body is
**XOR-with-feedback**; per-word the variant is picked by bit `0x800000` of the
key. Constants (little-endian words):

```
MAGIC        0x454C5550
XOR_A/XOR_B  0x9E3B16A2 / 0x764E28CA
C3/C7        0x360FA955 / 0xEF4A9AB6
DEFAULT_SEED 0x2F1349D2      # the seed the official 1.40C image uses
```

`enc(k,p)`: `x = k ^ (C3 if k&0x800000==0 else C7) ^ p`; then
`rot16(x) ^ XOR_A` if `k&0x800000==0` else `bswap(x) ^ XOR_B`.

Matches what our `vendor/elektron-firmware-tool` does — a useful cross-check if a
`build_*.py` round trip ever fails.

## To import / verify from `refs/`

- **elektron-firmware-tool** `format.h`, `main.c`, `compress.c`, `integrity.c` —
  check for format-field or checksum fixes newer than our vendored copy
  (`whatsnew.py elektron-firmware-tool`).
- **octabam** also rolls its own writer — third independent implementation.
