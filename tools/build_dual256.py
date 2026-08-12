#!/usr/bin/env python3
"""
build_dual256.py — DUAL-TABLE 256-slot image generator (Wave 0: read/display de-risk).

Builds on pristine stock (DSP never touched). Installs the emu-verified redirect helper family
(tools/patch_dual256.s), boot-initialises the four B-tables in the verified-free DDR hole (zero +
FILL by copying slots 0..127 so redirected reads see valid data, not zeros), then migrates a chosen
SET of accessor functions:
   * RAISE each function's static clamp `cmpi.l #128` so idx 128..255 reach the add instead of NULL
     (respecting bhi/bhs so OOR/sentinel indices still bail to A/NULL = stock-safe),
   * REDIRECT each per-slot table add (`add #base,reg`) -> `jsr helper` (same 6 bytes).

Incremental safety: any function NOT in SET keeps its #128 clamp -> NULLs idx>=128 -> stock behaviour,
so every intermediate flash boots. This Wave migrates the READ path only (no audio-engine sites).

    python3 tools/build_dual256.py            # -> out/mainos_dual256.bin  (+ emu-gate reminder)
"""
import pathlib, sys, subprocess

BASE = 0x40000400
SRC = pathlib.Path("out/stock_mainos.bin")
OUT = pathlib.Path("out/mainos_dual256.bin")

# ---- B-table layout in the verified-free hole [0x46c96000, 0x46cb9e00) ----
ST_A, ST_B, ST_STRIDE, ST_N = 0x46c90a78, 0x46c96000, 44, 128          # STATE
S41_A, S41_B = 0x46c920a4, 0x46c97600                                   # stride4 #1
S42_A, S42_B = 0x46c93a24, 0x46c97800                                   # stride4 #2
SET_A, SET_B, SET_STRIDE = 0x100d5b30, 0x46c97a00, 0x448                # SETTINGS
HOLE_LO, HOLE_HI = 0x46c96000, 0x46cb9e00

HELP_AT = 0x400d7400          # helper family base (matches patch_dual256.s .text)
BOOT_STUB = 0x400d64e0        # boot-init stub (in the 0x400d64da.. free cave)
BOOT_HOOK = 0x4001fa64        # detour point: `lea 0x10000000,a0` (6 bytes) in the boot mem-clear

# helper VAs are resolved from the assembled ELF symbol table (name -> VA).

# ---- migration set: function -> {clamps:[(va,)], sites:[(imm_va, helper_name)]} ----
# imm_va is the census site (offset of the 4-byte immediate); the instruction to replace starts at
# imm_va-2. Clamp va points at the `cmpi.l #128,dN` (0c8N 00000080) whose branch gates the sites.
#
# WAVE 0 = GETTER ONLY. The canonical (type,idx)->settings-pointer getter is a clean leaf: ONE static
# clamp (#128) + ONE settings add, no STATE/stride4/flex adds to co-migrate. Migrating exactly it
# proves the ENTIRE foundation is boot-safe (helpers installed, 4 B-tables zeroed+filled at boot, boot
# detour intact, a real redirect site live) with zero risk of the "opened-clamp + missed-add -> OOB"
# hazard that multi-table functions carry. Later waves add multi-add functions behind an OOB emu-gate.
CORE = {
    "getter_0x4006da78": {
        "clamps": [0x4006da88],
        "sites": [(0x4006da9a, "h_set_d0")],          # settings ptr
    },
}


def off(a):
    return a - BASE


def assemble_helpers():
    subprocess.run(["m68k-elf-as", "-mcpu=5407", "-o", "out/_d256.o", "tools/patch_dual256.s"],
                   check=True)
    subprocess.run(["m68k-elf-ld", "-Ttext=0x%x" % HELP_AT, "-o", "out/_d256.elf", "out/_d256.o"],
                   capture_output=True)
    subprocess.run(["m68k-elf-objcopy", "-O", "binary", "out/_d256.elf", "out/_d256.bin"], check=True)
    nm = subprocess.run(["m68k-elf-nm", "out/_d256.elf"], capture_output=True, text=True).stdout
    sym = {p[2]: int(p[0], 16) for p in (l.split() for l in nm.splitlines()) if len(p) == 3}
    blob = pathlib.Path("out/_d256.bin").read_bytes()
    for f in ("out/_d256.o", "out/_d256.elf", "out/_d256.bin"):
        pathlib.Path(f).unlink(missing_ok=True)
    return blob, sym


def build_boot_stub():
    """Zero [HOLE_LO,HOLE_HI); then copy each A-table's 128 slots into its B-table (placeholder
    fill so redirected reads see valid data). Ends by running the displaced original instruction
    (lea 0x10000000,a0) and jmp back to BOOT_HOOK+6. Assembled (NOT hand-encoded — a hand-encoded
    bne.s displacement bug that spun the loops was caught by the boot-stub emu-gate)."""
    import subprocess, pathlib
    nz = (HOLE_HI - HOLE_LO) // 4
    asm = f"""    .cpu 5407
    .text
    movea.l #0x{HOLE_LO:x},%a0
    move.l  #0x{nz:x},%d0
1:  clr.l   (%a0)+
    subq.l  #1,%d0
    bne.s   1b
"""
    for src, dst, nb in [(ST_A, ST_B, ST_STRIDE * ST_N), (S41_A, S41_B, 4 * ST_N),
                         (S42_A, S42_B, 4 * ST_N), (SET_A, SET_B, SET_STRIDE * ST_N)]:
        asm += f"""    movea.l #0x{src:x},%a0
    movea.l #0x{dst:x},%a1
    move.l  #0x{nb//4:x},%d0
2:  move.l  (%a0)+,(%a1)+
    subq.l  #1,%d0
    bne.s   2b
"""
    asm += f"""    lea     0x10000000,%a0
    jmp     0x{BOOT_HOOK + 6:x}
"""
    pathlib.Path("out/_bs.s").write_text(asm)
    subprocess.run(["m68k-elf-as", "-mcpu=5407", "-o", "out/_bs.o", "out/_bs.s"], check=True)
    subprocess.run(["m68k-elf-ld", "-Ttext=0x%x" % BOOT_STUB, "-o", "out/_bs.elf", "out/_bs.o"],
                   capture_output=True)
    subprocess.run(["m68k-elf-objcopy", "-O", "binary", "out/_bs.elf", "out/_bs.bin"], check=True)
    blob = pathlib.Path("out/_bs.bin").read_bytes()
    for f in ("out/_bs.s", "out/_bs.o", "out/_bs.elf", "out/_bs.bin"):
        pathlib.Path(f).unlink(missing_ok=True)
    return blob


def raise_clamp(img, va):
    """cmpi.l #128,dN (0c8N 00000080) -> raise bound so idx..255 pass but OOR still bails.
    bhi(>128,0x62) -> #255 ; bhs/bcc(>=128,0x64) -> #256. Returns the new bound used."""
    o = off(va)
    assert img[o] == 0x0c and 0x80 <= img[o + 1] <= 0x87, f"not cmpi.l #imm,dN @0x{va:x}: {img[o]:02x}{img[o+1]:02x}"
    imm = int.from_bytes(img[o + 2:o + 6], "big")
    assert imm == 128, f"clamp @0x{va:x} imm={imm} != 128"
    br = img[o + 6]                                          # branch opcode byte after the cmpi
    if br == 0x62:            # bhi.s  (idx > 128 bails)   -> allow up to 255
        newbound = 255
    elif br in (0x64, 0x63):  # bcc/bhs / bls variants     -> allow up to 256 (>=256 bails)
        newbound = 256
    else:
        # long branch forms 0x6000.. or others: default to 255 (safe: OOR>=256 still bails via helper->A)
        newbound = 255
    img[o + 2:o + 6] = newbound.to_bytes(4, "big")
    return newbound


def redirect_site(img, imm_va, helper_va):
    """replace the 6-byte add-instruction (starts imm_va-2) with `jsr helper` (4eb9 + addr)."""
    o = off(imm_va - 2)
    b0 = img[o]
    # sanity: opcode is addi.l #imm,dN (06 8N), adda.l #imm,aN (dN fc), or folded addi
    ok = (b0 == 0x06) or (img[o] & 0x01 == 0x01 and img[o + 1] == 0xfc) or (b0 in (0xd1, 0xd3, 0xd5, 0xd7, 0xd9, 0xdb, 0xdd, 0xdf))
    assert ok, f"unexpected opcode @0x{imm_va-2:x}: {img[o]:02x}{img[o+1]:02x}"
    img[o:o + 6] = b"\x4e\xb9" + helper_va.to_bytes(4, "big")


def main():
    if not SRC.exists():
        sys.exit(f"missing {SRC}")
    img = bytearray(SRC.read_bytes())
    blob, sym = assemble_helpers()

    # 1) install helper family
    assert not any(img[off(HELP_AT):off(HELP_AT) + len(blob)]), "helper cave not empty"
    img[off(HELP_AT):off(HELP_AT) + len(blob)] = blob
    print(f"helpers: {len(blob)} B @0x{HELP_AT:08x} ({len(sym)} syms)")

    # 2) boot-init stub + detour
    stub = build_boot_stub()
    assert not any(img[off(BOOT_STUB):off(BOOT_STUB) + len(stub)]), "boot-stub cave not empty"
    img[off(BOOT_STUB):off(BOOT_STUB) + len(stub)] = stub
    o = off(BOOT_HOOK)
    assert bytes(img[o:o + 6]) == b"\x41\xf9\x10\x00\x00\x00", img[o:o + 6].hex()
    img[o:o + 6] = b"\x4e\xf9" + BOOT_STUB.to_bytes(4, "big")   # jmp stub
    print(f"boot-init: {len(stub)} B @0x{BOOT_STUB:08x}; detour @0x{BOOT_HOOK:08x}; "
          f"zero+fill [0x{HOLE_LO:08x},0x{HOLE_HI:08x})")

    # 3) migrate the core set
    nsite = nclamp = 0
    for fn, spec in CORE.items():
        for cva in spec["clamps"]:
            nb = raise_clamp(img, cva); nclamp += 1
            print(f"  clamp 0x{cva:08x} #128 -> #{nb}   [{fn}]")
        for imm_va, hn in spec["sites"]:
            assert hn in sym, f"helper {hn} missing"
            redirect_site(img, imm_va, sym[hn])
            print(f"  site  0x{imm_va-2:08x} add -> jsr {hn}(0x{sym[hn]:08x})   [{fn}]")
            nsite += 1
    print(f"migrated: {nsite} sites, {nclamp} clamps")

    OUT.write_bytes(bytes(img))
    print(f"\n{OUT}: {len(img):,} bytes")
    print("NEXT: emu-gate ->  python3 tools/emu_check.py out/mainos_dual256.bin")


if __name__ == "__main__":
    main()
