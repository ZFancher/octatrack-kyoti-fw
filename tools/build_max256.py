#!/usr/bin/env python3
"""
MAX256 — 256-static-slot feature by relocating BOTH state and settings to contiguous 256-slot
DDR tables in the verified-free hole. On pristine stock (DSP never touched).

    STATE-256    [0x46c96000, 0x46c98c00)   state base 0x46c90a78 -> 0x46c96000 (36 refs, clean)
    SETTINGS-256 [0x46c98c00, 0x46cdd400)   36 static-access refs 0x100d5b30 -> 0x46c98c00

Settings is walked contiguously (5 static walks + 7 COMBINED flex->static loops), so relocating
it breaks every combined loop (loop-2 continues from a2/d2/etc = old static base 0x100d5b30). Each
combined loop gets a cave trampoline: patch its loop-2 entry to a cave stub that resets the walk
register to the DDR base 0x46c98c00 and re-enters, and retarget its 0x100f7f30 end bound.

MODE: 'neutral' keeps bounds at 128 (behaviour-identical to stock; the relocation surgery only) —
flash this first to de-risk. 'feature' opens to 256 (TODO: bound guards + free-flag init + UI).

    python3 tools/build_max256.py           # -> out/mainos_max256.bin  (neutral)
"""
import pathlib, sys
from collections import Counter

BASE = 0x40000400
SRC = pathlib.Path("out/stock_mainos.bin")
OUT = pathlib.Path("out/mainos_max256.bin")

STATE_A, STATE_B = 0x46c90a78, 0x46c96000
SETT_A, SETT_B = 0x100d5b30, 0x46c98c00
SETT_END_128 = SETT_B + 128 * 0x448        # 0x46cbb000 (neutral static end)
CAVE = 0x400d7400

LEA = {0x41, 0x43, 0x45, 0x47, 0x49, 0x4b, 0x4d}
ADDA = {0xd1, 0xd3, 0xd5, 0xd7, 0xd9, 0xdb, 0xdd, 0xdf}

# reset-instruction encodings for the walk register of each combined loop
def reset_reg(reg):
    if reg == "a2": return bytes.fromhex("45f9") + SETT_B.to_bytes(4, "big")   # lea SETT_B,a2
    if reg == "a4": return bytes.fromhex("49f9") + SETT_B.to_bytes(4, "big")   # lea SETT_B,a4
    if reg == "d2": return bytes.fromhex("243c") + SETT_B.to_bytes(4, "big")   # movel #SETT_B,d2
    if reg == "d3": return bytes.fromhex("263c") + SETT_B.to_bytes(4, "big")   # movel #SETT_B,d3
    raise ValueError(reg)

# The 7 combined loops: (walk reg, loop-2 entry patch site, displaced 6-byte instr, jmp-back,
# static-END bound immediate address).
COMBINED = [
    ("a2", 0x4008f432, "49f9400204a8", 0x4008f438, 0x4008f45e),
    ("a2", 0x4008fa00, "4df940013f40", 0x4008fa06, 0x4008fa56),
    ("d2", 0x40090fc8, "47f940013f5c", 0x40090fce, 0x40091026),
    ("a4", 0x4008602a, "2c3c40016564", 0x40086030, 0x4008626a),
    ("a4", 0x40086482, "263c40016564", 0x40086488, 0x4008666c),
    ("d3", 0x40089fae, "47f9400166b8", 0x40089fb4, 0x4008a0f8),
    ("d2", 0x4008f772, "45f9460a8e48", 0x4008f778, 0x4008f7fa),
]


def off(a):
    return a - BASE


def find(img, val):
    nb = val.to_bytes(4, "big"); out = []; i = img.find(nb)
    while i != -1:
        if BASE + i < 0x400e0000:
            out.append(i)
        i = img.find(nb, i + 1)
    return out


def is_static_access(img, o):
    b0, b1 = img[o - 2], img[o - 1]
    if b0 == 0x06 and (b1 & 0xf8) == 0x80: return True
    if b1 == 0xfc and b0 in ADDA: return True
    if b1 == 0xf9 and b0 in LEA: return True
    if b1 == 0x3c and b0 in range(0x20, 0x2f, 2): return True
    return False


def main():
    img = bytearray(SRC.read_bytes())

    # STAGE 1: rebase state (blanket) + settings (static-access only)
    so = find(img, STATE_A)
    assert len(so) == 36, f"state refs {len(so)}"
    for o in so:
        img[o:o + 4] = STATE_B.to_bytes(4, "big")
    # settings: range-rebase by DELTA so field accessors with a FOLDED offset (e.g.
    # addi.l #(base+0x10e),dN for slot*0x448 -> settings[slot].field_0x10e) move too. Missing
    # these left field 0x10e of every slot reading the boot-zeroed old SRAM -> empty samples/
    # slices/no sound. cmpa/cmpi bounds (flex-end at exactly 0x100d5b30) are kept.
    DELTA = SETT_B - SETT_A
    SETT_END = 0x100f7f30                                # static end / global-above base (exclusive)
    reb = 0; kept = 0
    for i in range(2, len(img) - 3):
        if BASE + i >= 0x400e0000:
            break
        v = (img[i] << 24) | (img[i + 1] << 16) | (img[i + 2] << 8) | img[i + 3]
        if not (SETT_A <= v < SETT_END):
            continue
        if is_static_access(img, i):
            img[i:i + 4] = (v + DELTA).to_bytes(4, "big"); reb += 1
        elif v == SETT_A:                                # cmpa/cmpi #base -> flex-walk end, keep
            kept += 1
    assert reb == 40, f"settings rebased {reb} (want 40 = 36 base + 4 interior field refs)"
    assert kept == 7, f"settings kept {kept} (want 7 flex-end)"
    print(f"STAGE 1: state 36 -> 0x{STATE_B:08x}; settings {reb} rebased +0x{DELTA:x} "
          f"(incl. 4 interior field refs); {kept} flex-end kept")

    # STAGE 2: combined-loop trampolines
    p = CAVE
    for reg, site, disp, back, bound_imm in COMBINED:
        disp_b = bytes.fromhex(disp)
        # patch loop-2 entry -> jmp cave
        o = off(site)
        assert bytes(img[o:o + 6]) == disp_b, f"@0x{site:08x} got {img[o:o+6].hex()} want {disp}"
        img[o:o + 6] = bytes.fromhex("4ef9") + p.to_bytes(4, "big")
        # cave stub: reset walk reg ; displaced instr ; jmp back
        stub = reset_reg(reg) + disp_b + bytes.fromhex("4ef9") + back.to_bytes(4, "big")
        assert not any(img[off(p):off(p) + len(stub)]), "cave overlap"
        img[off(p):off(p) + len(stub)] = stub
        p += len(stub)
        # retarget the static-END bound immediate 0x100f7f30 -> SETT_END_128
        bo = off(bound_imm)
        assert int.from_bytes(img[bo:bo + 4], "big") == 0x100f7f30, f"bound @0x{bound_imm:08x}"
        img[bo:bo + 4] = SETT_END_128.to_bytes(4, "big")
    print(f"STAGE 2: {len(COMBINED)} combined-loop trampolines @ cave 0x{CAVE:08x}..0x{p-1:08x}; "
          f"7 end bounds -> 0x{SETT_END_128:08x}")

    # STAGE 3: boot-zero the relocated DDR region. Stock zeroes the SRAM settings via the boot
    # SRAM clear [0x10000000,0x100fff00); the DDR destination is not covered, so replicate the
    # pre-zero for [STATE_B, settings-256 end). Hook 0x4001fa64 (lea 0x10000000,a0) -> stub that
    # zeroes the region, redoes the displaced lea, and continues to 0x4001fa6a. Targets the
    # verified-free hole ONLY -- never the DSP (that was Phase 1's fatal mistake).
    ZLO, ZHI = STATE_B, SETT_B + 256 * 0x448           # [0x46c96000, 0x46cdd400)
    nlongs = (ZHI - ZLO) // 4
    STUB_AT = CAVE + 0x80                               # after the 7 trampolines
    stub = bytes.fromhex(
        "4fefffc4" + "48d77fff" + "207c" + ZLO.to_bytes(4, "big").hex()
        + "203c" + nlongs.to_bytes(4, "big").hex() + "7200" + "20c1" + "5380" + "66fa"
        + "4cd77fff" + "4fef003c" + "41f910000000" + "4ef94001fa6a")
    assert not any(img[off(STUB_AT):off(STUB_AT) + len(stub)]), "cave overlap (stub)"
    img[off(STUB_AT):off(STUB_AT) + len(stub)] = stub
    o = off(0x4001fa64)
    assert bytes(img[o:o + 6]) == bytes.fromhex("41f910000000"), f"detour site {img[o:o+6].hex()}"
    img[o:o + 6] = bytes.fromhex("4ef9") + STUB_AT.to_bytes(4, "big")
    assert ZHI < 0x46ceb400, "bootzero overruns the verified hole"
    print(f"STAGE 3: boot-zero [0x{ZLO:08x},0x{ZHI:08x}) ({nlongs} longs) stub @0x{STUB_AT:08x}; "
          f"detour 0x4001fa64")

    # residuals: state base gone; settings base only 7 flex-end; static-END 0x100f7f30 only the
    # 2 global-above pea base-loads remain (the 7 loop bounds were retargeted)
    assert img.count(STATE_A.to_bytes(4, "big")) == 0
    assert img.count(SETT_A.to_bytes(4, "big")) == 7
    assert img.count((0x100f7f30).to_bytes(4, "big")) == 2, img.count((0x100f7f30).to_bytes(4, "big"))
    print("residual: state 0, settings 7 (flex-end), 0x100f7f30 = 2 (global base-loads kept) OK")

    OUT.write_bytes(bytes(img))
    print(f"\n{OUT}: {len(img):,} bytes  [NEUTRAL relocation -- bounds still 128; feature=bounds->256 TODO]")


if __name__ == "__main__":
    main()
