#!/usr/bin/env python3
"""
MAX256 — the real 256-static-slot feature, by relocating BOTH state and settings to contiguous
256-slot DDR tables in the verified-free hole. Layered on pristine stock (NOT Phase 1 -> the DSP
is never touched). Built in stages, each emu-gated (tools/emu_check.py + inline checks).

Layout (verified free, tools/emu_ddr_free.py):
    STATE-256    [0x46c96000, 0x46c98c00)   256 * 44    = 0x2c00
    SETTINGS-256 [0x46c98c00, 0x46cdd400)   256 * 0x448 = 0x44800   (margin to 0x46ceb400)

STAGE 1 (this file, so far):
  - STATE: blanket rebase base 0x46c90a78 -> 0x46c96000 (36 refs, ALL static-access; the template
    at old 0x46c92078 has 0 refs and no address-bounded static walk exists -> clean rebase).
  - SETTINGS: rebase the 36 static-ACCESS refs 0x100d5b30 -> 0x46c98c00, KEEP the 7 flex-walk END
    bounds (cmpa/cmpi #0x100d5b30). Combined-loop trampolines + bound guards #128->#256 + free-flag
    init are added in later stages (each emu-gated) before the image is flashable.

    python3 tools/build_max256.py     # -> out/mainos_max256.bin
"""
import pathlib, sys
from collections import Counter

BASE = 0x40000400
SRC = pathlib.Path("out/stock_mainos.bin")
OUT = pathlib.Path("out/mainos_max256.bin")

STATE_A = 0x46c90a78
STATE_B = 0x46c96000                 # relocated contiguous 256-slot state table
SETT_A = 0x100d5b30
SETT_B = 0x46c98c00                  # relocated contiguous 256-slot settings table

LEA = {0x41, 0x43, 0x45, 0x47, 0x49, 0x4b, 0x4d}
ADDA = {0xd1, 0xd3, 0xd5, 0xd7, 0xd9, 0xdb, 0xdd, 0xdf}
CMPA = {0xb1, 0xb3, 0xb5, 0xb7, 0xb9, 0xbb, 0xbd, 0xbf}


def find(img, val):
    nb = val.to_bytes(4, "big"); out = []; i = img.find(nb)
    while i != -1:
        if BASE + i < 0x400e0000:
            out.append(i)
        i = img.find(nb, i + 1)
    return out


def is_static_access(img, o):
    b0, b1 = img[o - 2], img[o - 1]
    if b0 == 0x06 and (b1 & 0xf8) == 0x80: return True     # addi.l #imm,dN
    if b1 == 0xfc and b0 in ADDA: return True              # adda.l #imm,aN
    if b1 == 0xf9 and b0 in LEA: return True               # lea #abs,aN
    if b1 == 0x3c and b0 in range(0x20, 0x2f, 2): return True  # move.l #imm,dN
    return False                                            # cmpa/cmpi -> flex-end bound, keep


def main():
    img = bytearray(SRC.read_bytes())

    # STATE: blanket rebase (all 36 refs are static-access; verified 0 template refs)
    so = find(img, STATE_A)
    if len(so) != 36:
        sys.exit(f"expected 36 state refs, found {len(so)}")
    for o in so:
        img[o:o + 4] = STATE_B.to_bytes(4, "big")
    print(f"STATE : rebased {len(so)} refs 0x{STATE_A:08x} -> 0x{STATE_B:08x}")

    # SETTINGS: rebase static-access only, keep flex-end bounds
    go = find(img, SETT_A)
    if len(go) != 43:
        sys.exit(f"expected 43 settings refs, found {len(go)}")
    reb = 0; kept = 0
    for o in go:
        if is_static_access(img, o):
            img[o:o + 4] = SETT_B.to_bytes(4, "big"); reb += 1
        else:
            kept += 1
    print(f"SETT  : rebased {reb} static-access refs 0x{SETT_A:08x} -> 0x{SETT_B:08x}; kept {kept} flex-end")
    if reb != 36 or kept != 7:
        sys.exit(f"settings split wrong: rebased {reb} kept {kept} (want 36/7)")

    # sanity: state/settings tables fit the hole, no overlap
    s_end = STATE_B + 256 * 44
    g_end = SETT_B + 256 * 0x448
    assert s_end <= SETT_B, "state/settings overlap"
    assert g_end < 0x46ceb400, "settings overruns hole"
    print(f"layout: state [0x{STATE_B:08x},0x{s_end:08x})  settings [0x{SETT_B:08x},0x{g_end:08x})  hole-ok")
    # residuals: STATE_A gone entirely; SETT_A only the 7 kept
    assert img.count(STATE_A.to_bytes(4, "big")) == 0
    assert img.count(SETT_A.to_bytes(4, "big")) == 7
    print("residual: state base 0 (all rebased); settings base 7 (kept flex-end) OK")

    OUT.write_bytes(bytes(img))
    print(f"\n{OUT}: {len(img):,} bytes  [STAGE 1: rebase only -- trampolines/bounds/init TODO]")


if __name__ == "__main__":
    main()
