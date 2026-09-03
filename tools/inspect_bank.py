#!/usr/bin/env python3
# SPDX-License-Identifier: MIT
# SPDX-FileCopyrightText: 2026 Zachary Fancher ("Kyoti")
"""Static reader for an Octatrack bank file (`bankNN.work` / `.strd`).

Reads the raw on-CF format directly (no firmware, no emulation), using the
offsets from snugsound/OctaLib plus the TRAC-block layout worked out in
`reference/kb/file-format.md` (Session 16). Feed it a REAL hardware export only.

    python3 tools/inspect_bank.py <bankNN.work>            # overview
    python3 tools/inspect_bank.py <bankNN.work> -p 2 -t 7  # dump one track's p-locks
    python3 tools/inspect_bank.py <bankNN.work> --parts    # part FX-id bytes + names

Offsets (bank-file bytes):
  PTRN 1              0x16     stride 0x8EEC, 16 patterns
  PTRN header         8 B, then 8x TRAC (0x922) then 8x MTRA (0x8B9)
  TRAC  +0x08         track number
        +0x09  8 B    regular-trig bitmap (64 steps, reverse bit order)
        +0x29  8 B    rec-trig bitmap (OctaLib)
        +0x49  16 B   delimiter  AA*8 00*8
        +0x59  9 B    param header: [LEN] 02 00 FF 00*5 ; LEN 0x10/0x20/0x40 = 16/32/64 steps
        +0x62  0x800  p-lock array: 64 steps x 32 bytes, 0xFF = param not locked
        +0x862 0xC0   per-step aux: 64 x 3 B (trig conditions / microtiming?), 0 = default
  PART 1             0x8EED6   stride 0x18BB, 8 blocks (OctaLib: "two sets", .work vs saved)
  part names         0x9B4B3   stride 7, 6 chars, NUL-terminated
"""
from __future__ import annotations
import argparse
import pathlib
import sys

HEADER = bytes.fromhex("464F524D00000000445053314241" "4E4B")  # "FORM\0\0\0\0DPS1BANK"
PAT1, PSTRIDE, PHDR = 0x16, 0x8EEC, 8
TRAC, MTRA = 0x922, 0x8B9
PLOCK_OFF, PLOCK_REC, NSTEPS = 0x62, 32, 64
AUX_OFF, AUX_REC = 0x862, 3
PART1, PART_STRIDE = 0x8EED6, 0x18BB
PART_NAMES = 0x9B4B3


def trac_off(pat: int, trk: int) -> int:
    return PAT1 + PSTRIDE * pat + PHDR + TRAC * trk


def mtra_off(pat: int, trk: int) -> int:
    return PAT1 + PSTRIDE * pat + PHDR + TRAC * 8 + MTRA * trk


def popcount(bs: bytes) -> int:
    return bin(int.from_bytes(bs, "big")).count("1")


def overview(b: bytes) -> None:
    ok = b[:16] == HEADER
    print(f"file: {len(b):,} B ({len(b):#x})   header {'OK' if ok else 'BAD -- not a bank file'}")
    if not ok:
        return
    print(f"\n{'':4} " + "  ".join(f"t{t+1:<11}" for t in range(8)))
    for p in range(16):
        cells = []
        for t in range(8):
            o = trac_off(p, t)
            ntr = popcount(b[o + 9:o + 17])
            length = b[o + 0x59]
            locks = sum(
                1 for s in range(NSTEPS)
                if set(b[o + PLOCK_OFF + PLOCK_REC * s:o + PLOCK_OFF + PLOCK_REC * (s + 1)]) != {0xFF}
            )
            cells.append(f"{ntr:2d}tr {length:#04x} {locks:2d}pl")
        print(f"P{p+1:<3}" + "  ".join(cells))
    print("\n  NtR = trig count, 0xNN = step-count byte (10/20/40 = 16/32/64), Npl = steps carrying a p-lock")
    print("  -p <1-16> -t <1-8> to dump a track's locked steps.")


def dump_track(b: bytes, pat: int, trk: int) -> None:
    o = trac_off(pat, trk)
    print(f"P{pat+1} t{trk+1}  TRAC @ {o:#x}")
    print(f"  track num   +0x08 : {b[o+8]}")
    print(f"  reg trigs   +0x09 : {b[o+9:o+17].hex(' ')}  ({popcount(b[o+9:o+17])} set)")
    print(f"  rec trigs   +0x29 : {b[o+0x29:o+0x31].hex(' ')}  ({popcount(b[o+0x29:o+0x31])} set)")
    print(f"  pre-plock   +0x11..+0x28 : {b[o+0x11:o+0x29].hex(' ')}")
    print(f"  delimiter   +0x49 : {b[o+0x49:o+0x59].hex(' ')}")
    print(f"  param hdr   +0x59 : {b[o+0x59:o+0x62].hex(' ')}   (step count {b[o+0x59]:#04x})")
    print(f"  p-locks     +0x62 : 64 x 32 B, 0xFF = unlocked")
    any_lock = False
    for s in range(NSTEPS):
        rec = b[o + PLOCK_OFF + PLOCK_REC * s:o + PLOCK_OFF + PLOCK_REC * (s + 1)]
        nz = [(i, rec[i]) for i in range(PLOCK_REC) if rec[i] != 0xFF]
        if nz:
            any_lock = True
            print(f"    step {s:2d}: " + "  ".join(f"[{i:#04x}]={v:#04x}({v})" for i, v in nz))
    if not any_lock:
        print("    (no locked steps)")
    aux = b[o + AUX_OFF:o + AUX_OFF + AUX_REC * NSTEPS]
    nz = [(s, aux[s*3:s*3+3].hex(' ')) for s in range(NSTEPS) if set(aux[s*3:s*3+3]) != {0}]
    print(f"  aux array   +0x862: " + ("all default (0)" if not nz else ""))
    for s, h in nz:
        print(f"    step {s:2d}: {h}")


def dump_parts(b: bytes) -> None:
    for i in range(8):
        o = PART1 + PART_STRIDE * i
        tag = b[o:o + 4]
        fx = b[o + 8:o + 8 + 18]
        print(f"PART slot {i+1} @ {o:#x}  tag {tag!r}  bytes+8: {fx.hex(' ')}")
    print("\npart names @ 0x9B4B3 (stride 7):")
    for i in range(8):
        raw = b[PART_NAMES + 7 * i:PART_NAMES + 7 * i + 6]
        name = raw.split(b"\x00")[0].decode("latin1", "replace")
        print(f"  {i+1}: {name!r}")


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("bank", type=pathlib.Path)
    ap.add_argument("-p", "--pattern", type=int, help="1-16")
    ap.add_argument("-t", "--track", type=int, help="1-8")
    ap.add_argument("--parts", action="store_true")
    a = ap.parse_args()
    b = a.bank.read_bytes()
    if a.parts:
        dump_parts(b)
    elif a.pattern and a.track:
        dump_track(b, a.pattern - 1, a.track - 1)
    else:
        overview(b)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
