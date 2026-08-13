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

# WAVE-0 hardware result [2026-08-12]: getter-only WITH boot-init booted fine but left static slots
# empty + reset the clock -> the boot-zero of [0x46c96000,0x46cb9e00) clobbers a RUNTIME-LIVE DDR
# region (register-relative-accessed; invisible to the static scan). So BOOTINIT is a diagnostic
# toggle: with it OFF, only the getter is migrated (idx<128 byte-identical to stock) -> isolates
# whether the getter mechanism itself is harmless. The real fix (a genuinely-free B-region) is next.
BOOTINIT = True
# Diagnostic 2: keep the getter clamp at #128 (do NOT open to #255). With RAISE_CLAMPS=False the
# migrated getter is BEHAVIOURALLY STOCK-EQUIVALENT (idx<128 -> A via helper == stock add; idx>=128
# -> NULL via the unchanged clamp, before the jsr). Isolates whether OPENING the clamp (a sentinel
# collision: many things encode "no static slot" as an idx>127 that stock NULLs) is what emptied the
# static slots -- vs the jsr-to-helper plumbing on this specific function.
RAISE_CLAMPS = True

# ---- B-table layout ABOVE the OS working-DDR region [0x46025de0, 0x4763d580) ----
# (the OS boot-clears exactly [0x46025de0, 0x4763d580) at 0x40000518/0x4000f984 and manages it;
#  putting B-tables inside it is what corrupted the project. 0x47700000 is above that boundary.)
ST_A, ST_B, ST_STRIDE, ST_N = 0x46c90a78, 0x47700000, 44, 128          # STATE
S41_A, S41_B = 0x46c920a4, 0x47701600                                   # stride4 #1
S42_A, S42_B = 0x46c93a24, 0x47701800                                   # stride4 #2
SET_A, SET_B, SET_STRIDE = 0x100d5b30, 0x47701a00, 0x448                # SETTINGS
HOLE_LO, HOLE_HI = 0x47700000, 0x47723e00

HELP_AT = 0x400d7400          # helper family base (matches patch_dual256.s .text)
BOOT_STUB = 0x400d64e0        # boot-init stub (in the 0x400d64da.. free cave)
BOOT_HOOK = 0x4001fa64        # detour point: `lea 0x10000000,a0` (6 bytes) in the boot mem-clear

# --- Wave 4: SIDECAR persistence of SETTINGS-B (128-255) to <projectdir>/project.256 ---
SIDECAR = True
SIDECAR_AT = 0x400d6600       # sidecar code+data (between boot stub and helper cave)
SETB_LO, SETB_HI = 0x47701a00, 0x47723e00     # SETTINGS-B extent = 128*0x448 = 140288 B
# verified CF I/O primitives + project-dir composer (from the save/load recon):
IO_OPEN, IO_READ, IO_WRITE, IO_CLOSE = 0x40016864, 0x40016564, 0x400166b8, 0x4001677c
IO_SPRINTF, DIR_OF = 0x40013a08, 0x40025230
IO_BUF, IO_BUFSZ = 0x460a8f60, 0x10000
MODE_W, MODE_R = 0x400b328b, 0x400b3289       # "w" / "r" C strings
SAVE_HOOK = 0x4008ff44        # 6B: tstl a3(4a8b) beqs+2(6702) jsr a3@(4e93) -> replicate after write
LOAD_HOOK = 0x4009021a        # 6B: moveml fp@(-576),d2-d6/a2-a4 (4cee1c7cfdc0) -> replicate after read

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
# Each entry: entry/end (TRUE extent, next-prologue), clamps to open, and EVERY per-slot add in the
# function (settings+state+stride4). The OOB gate asserts no per-slot base-add remains in [entry,end)
# after redirect -- a missed one would OOB-write the working region at runtime = project corruption.
CORE = {
    "getter_0x4006da78": {
        "entry": 0x4006da78, "end": 0x4006decc, "clamps": [0x4006da88],
        "sites": [(0x4006da9a, "h_set_d0")],
    },
    "activation_0x4009367c": {
        "entry": 0x4009367c, "end": 0x400937d4, "clamps": [0x400936b2],
        "sites": [(0x400936cc, "h_set_a3")],
    },
    "trackenable_0x40093e9c": {
        "entry": 0x40093e9c, "end": 0x400940ac, "clamps": [0x40093f6e, 0x40094044],
        "sites": [(0x40093f88, "h_set_a2"), (0x4009405a, "h_st_a0")],
    },
    "slice_0x40094334": {
        "entry": 0x40094334, "end": 0x400947c0, "clamps": [0x40094350],
        "sites": [(0x40094364, "h_st_a3"), (0x40094380, "h_set_a1"), (0x400946f8, "h_st_d0")],
    },
    # --- popup static-slot browser (render + apply): makes 128-255 browsable/selectable ---
    "ui_render_0x40077b04": {
        "entry": 0x40077b04, "end": 0x40078850,
        "clamps": [0x40077dee, 0x40077f62, 0x400787c8],
        "sites": [(0x40077e0a, "h_set_a3"), (0x40077e18, "h_st_a2"),
                  (0x40077f76, "h_st_d0"), (0x4007809c, "h_st_d0")],
    },
    "ui_apply_0x40079428": {
        "entry": 0x40079428, "end": 0x400796a0, "clamps": [0x4007943a, 0x400794e8],
        "sites": [(0x40079450, "h_set_d0"), (0x400794fc, "h_st_d0")],
    },
    "ui_0x400796a4": {
        "entry": 0x400796a4, "end": 0x40079920, "clamps": [0x400797aa],
        "sites": [(0x400797ba, "h_st_d0")],
    },
    # --- Wave 5: SLOT COPY/PASTE (FUNC+COPY / FUNC+PASTE). HW confirmed the user's "copy sample to
    # slot 128" is a slot COPY/PASTE (not a pool-assign) -> function 0x40024f00 moves a full slot
    # (settings + STATE + stride4 pointer entries) between two slot indices. Migrating it makes PASTE
    # into idx>=128 write SETTINGS-B/STATE-B/stride4-B (so project.256 gets real data). 16 sites.
    "copypaste_0x40024f00": {
        "entry": 0x40024f00, "end": 0x40025288,
        "clamps": [0x40024f14, 0x40024f60, 0x40024fa8, 0x40024fea],
        "sites": [(0x40024f24, "h_st_d5"), (0x40024f72, "h_st_d4"),
                  (0x40024fbc, "h_set_d3"), (0x40025000, "h_set_d2"),
                  (0x4002504e, "h_s42_a0"), (0x40025058, "h_s41_a0"),
                  (0x4002506a, "h_s42_a0"), (0x40025074, "h_s41_a0"),
                  (0x40025086, "h_s42_a0"), (0x40025090, "h_s41_a0"),
                  (0x400250a2, "h_s42_a0"), (0x400250ac, "h_s41_a0"),
                  (0x40025118, "h_s42_a0"), (0x40025122, "h_s41_a0"),
                  (0x40025134, "h_s42_a0"), (0x4002513e, "h_s41_a0")],
    },
    # NOTE: Wave-3 attempt to migrate load-request 0x40093984 was REVERTED -- on HW it did NOT fix the
    # "L" and it BROKE assign-to-128 ("INVALID FILENAME"): 0x40093984 validates the slot filename
    # (jsr 0x400204cc @0x40093a74) and, run for idx128 (STATE resolves to the TEMPLATE via the idx>=129
    # rule + a settings ptr whose name field it rejects), it errors. The "L" is NOT this function; it
    # needs a separate, correctly-diagnosed fix. Left un-migrated (128-capped, stock-safe).
}

# UI LIST-LENGTH caps (pea #len) that limit how far the slot cursor can scroll. Not clamps -- raw
# immediates. popup static-slot browser: pea 0x80 (128) -> pea 0x100 (256). (found via UI-cap agent)
CAPS = {
    0x40079238: (b"\x48\x78\x00\x80", b"\x48\x78\x01\x00"),   # popup browser static list length 128->256
}

# per-slot table base immediates (exact + folded) used by the OOB completeness gate
SLOT_BASES = {0x100d5b30, 0x100d5c3e, 0x100d5c59, 0x46c90a78, 0x46c920a4, 0x46c93a24}


def scan_slot_adds(img, lo, hi):
    """every per-slot BASE-ADD (addi.l/adda.l of a SLOT_BASES immediate, muls-scaled) in [lo,hi)."""
    hits = []
    for k in range(off(lo) + 2, off(hi) - 3):
        v = int.from_bytes(img[k:k + 4], "big")
        if v not in SLOT_BASES:
            continue
        b0, b1 = img[k - 2], img[k - 1]
        is_addi = (b0 == 0x06 and 0x80 <= b1 <= 0x87)
        is_adda = (b1 == 0xfc and b0 in (0xd1, 0xd3, 0xd5, 0xd7, 0xd9, 0xdb, 0xdd, 0xdf))
        if is_addi or is_adda:
            hits.append(BASE + k)
    return hits


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
    # REGISTER-TRANSPARENT: the detour point 0x4001fa64 sits between `d0 = a0+514` (0x4001fa5c) and
    # `cmpl 0x100fff00,d0` (0x4001fa6a) -- the boot code USES d0 (and a1) after the detour. Clobbering
    # them broke system init -> clock reset + empty boot state (seen on w0 AND hiregion). So save every
    # register the stub touches and restore before the displaced instruction. (a0 is overwritten by the
    # displaced `lea` anyway, but we save/restore it too for cleanliness.)
    # ColdFire movem has no predecrement mode -> save d0/a1 (the regs the boot code uses after the
    # detour) with individual pushes. a0 is overwritten by the displaced `lea`, so it needs no save.
    asm = f"""    .cpu 5407
    .text
    move.l  %d0,-(%sp)
    move.l  %a1,-(%sp)
    movea.l #0x{HOLE_LO:x},%a0
    move.l  #0x{nz:x},%d0
1:  clr.l   (%a0)+
    subq.l  #1,%d0
    bne.s   1b
"""
    # COPY-FILL 0..127 into each B-table (as wave2, which had a working assign-to-128). Zero-init was
    # tried but reverted alongside the load-zeroing: the assign path wants a valid-ish slot struct in
    # SETTINGS-B to overwrite. The sidecar overwrites SETTINGS-B on load when a project.256 exists.
    for src, dst, nb in [(ST_A, ST_B, ST_STRIDE * ST_N), (S41_A, S41_B, 4 * ST_N),
                         (S42_A, S42_B, 4 * ST_N), (SET_A, SET_B, SET_STRIDE * ST_N)]:
        asm += f"""    movea.l #0x{src:x},%a0
    movea.l #0x{dst:x},%a1
    move.l  #0x{nb//4:x},%d0
2:  move.l  (%a0)+,(%a1)+
    subq.l  #1,%d0
    bne.s   2b
"""
    asm += f"""    move.l  (%sp)+,%a1
    move.l  (%sp)+,%d0
    lea     0x10000000,%a0
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


def build_sidecar():
    """Assemble the sidecar save+load routines (persist SETTINGS-B to <projectdir>/project.256).
    Register-safe: saves the caller-saved regs it uses and REPLICATES the displaced hook bytes.
    Returns (blob, {name:VA}). Save hook replaces 6 bytes at SAVE_HOOK (tstl a3;beqs;jsr a3@) and
    the routine re-runs that logic; load hook replaces the 6-byte moveml at LOAD_HOOK and the routine
    re-emits it verbatim (raw bytes) so register restore is byte-identical."""
    import subprocess, pathlib
    ln = SETB_HI - SETB_LO
    asm = f"""    .cpu 5407
    .text
sidecar_save:
    move.l  %d0,-(%sp)
    move.l  %d1,-(%sp)
    move.l  %d2,-(%sp)
    move.l  %a0,-(%sp)
    move.l  %a1,-(%sp)
    move.l  %a3,-(%sp)
    lea     stream,%a0
    move.l  #16,%d1
1:  clr.l   (%a0)+
    subq.l  #1,%d1
    bne.b   1b
    clr.l   -(%sp)
    clr.l   -(%sp)
    jsr     0x{DIR_OF:x}
    addq.l  #8,%sp
    move.l  %d0,-(%sp)
    pea     fmt256
    pea     pathbuf
    jsr     0x{IO_SPRINTF:x}
    lea     12(%sp),%sp
    move.l  #0x{IO_BUFSZ:x},-(%sp)
    move.l  #0x{IO_BUF:x},-(%sp)
    pea     0x{MODE_W:x}
    pea     pathbuf
    pea     stream
    jsr     0x{IO_OPEN:x}
    lea     20(%sp),%sp
    tst.l   %d0
    bmi.b   2f
    move.l  #0x{ln:x},-(%sp)
    move.l  #0x{SETB_LO:x},-(%sp)
    pea     stream
    jsr     0x{IO_WRITE:x}
    lea     12(%sp),%sp
    pea     stream
    jsr     0x{IO_CLOSE:x}
    addq.l  #4,%sp
2:  move.l  (%sp)+,%a3
    move.l  (%sp)+,%a1
    move.l  (%sp)+,%a0
    move.l  (%sp)+,%d2
    move.l  (%sp)+,%d1
    move.l  (%sp)+,%d0
    tst.l   %a3
    beq.b   3f
    jsr     (%a3)
3:  jmp     0x{SAVE_HOOK + 6:x}

sidecar_load:
    move.l  %d0,-(%sp)
    move.l  %d1,-(%sp)
    move.l  %a0,-(%sp)
    move.l  %a1,-(%sp)
    | (No B-zeroing on load: keep the boot copy-fill so assign-to-128 sees a valid slot struct. A
    | project WITHOUT a project.256 keeps the boot placeholder; the read below overwrites SETTINGS-B
    | when the sidecar exists. Stale-on-project-switch is a known minor follow-up.)
    lea     stream,%a0
    move.l  #16,%d1
4:  clr.l   (%a0)+
    subq.l  #1,%d1
    bne.b   4b
    clr.l   -(%sp)
    clr.l   -(%sp)
    jsr     0x{DIR_OF:x}
    addq.l  #8,%sp
    move.l  %d0,-(%sp)
    pea     fmt256
    pea     pathbuf
    jsr     0x{IO_SPRINTF:x}
    lea     12(%sp),%sp
    move.l  #0x{IO_BUFSZ:x},-(%sp)
    move.l  #0x{IO_BUF:x},-(%sp)
    pea     0x{MODE_R:x}
    pea     pathbuf
    pea     stream
    jsr     0x{IO_OPEN:x}
    lea     20(%sp),%sp
    tst.l   %d0
    bmi.b   5f
    move.l  #0x{ln:x},-(%sp)
    move.l  #0x{SETB_LO:x},-(%sp)
    pea     stream
    jsr     0x{IO_READ:x}
    lea     12(%sp),%sp
    pea     stream
    jsr     0x{IO_CLOSE:x}
    addq.l  #4,%sp
5:  move.l  (%sp)+,%a1
    move.l  (%sp)+,%a0
    move.l  (%sp)+,%d1
    move.l  (%sp)+,%d0
    .byte   0x4c,0xee,0x1c,0x7c,0xfd,0xc0     | moveml %fp@(-576),%d2-%d6/%a2-%a4 (verbatim)
    jmp     0x{LOAD_HOOK + 6:x}

    .align 2
fmt256:
    .asciz  "%s/project.256"
    .align 2
pathbuf:
    .space  320
    .align 2
stream:
    .space  64
"""
    pathlib.Path("out/_sc.s").write_text(asm)
    subprocess.run(["m68k-elf-as", "-mcpu=5407", "-o", "out/_sc.o", "out/_sc.s"], check=True)
    subprocess.run(["m68k-elf-ld", "-Ttext=0x%x" % SIDECAR_AT, "-o", "out/_sc.elf", "out/_sc.o"],
                   capture_output=True)
    subprocess.run(["m68k-elf-objcopy", "-O", "binary", "out/_sc.elf", "out/_sc.bin"], check=True)
    nm = subprocess.run(["m68k-elf-nm", "out/_sc.elf"], capture_output=True, text=True).stdout
    sym = {p[2]: int(p[0], 16) for p in (l.split() for l in nm.splitlines()) if len(p) == 3}
    blob = pathlib.Path("out/_sc.bin").read_bytes()
    for f in ("out/_sc.s", "out/_sc.o", "out/_sc.elf", "out/_sc.bin"):
        pathlib.Path(f).unlink(missing_ok=True)
    return blob, sym


def raise_clamp(img, va):
    """cmpi.l #128,dN (0c8N 00000080) -> raise bound so idx..255 pass but OOR still bails.
    bhi(>128,0x62) -> #255 ; bhs/bcc(>=128,0x64) -> #256. Returns the new bound used."""
    o = off(va)
    is_cmpi = img[o] == 0x0c and 0x80 <= img[o + 1] <= 0x87                 # cmpi.l #imm,dN
    is_cmpa = img[o] in (0xb1, 0xb3, 0xb5, 0xb7, 0xb9, 0xbb, 0xbd, 0xbf) and img[o + 1] == 0xfc  # cmpa.l #imm,aN
    assert is_cmpi or is_cmpa, f"not cmpi/cmpa #imm @0x{va:x}: {img[o]:02x}{img[o+1]:02x}"
    imm = int.from_bytes(img[o + 2:o + 6], "big")
    assert imm == 128, f"clamp @0x{va:x} imm={imm} != 128"
    br = img[o + 6]                                          # branch opcode byte after the cmp
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

    # 2) boot-init stub + detour  (toggled: OFF isolates the getter from the boot-zero regression)
    if BOOTINIT:
        stub = build_boot_stub()
        assert not any(img[off(BOOT_STUB):off(BOOT_STUB) + len(stub)]), "boot-stub cave not empty"
        img[off(BOOT_STUB):off(BOOT_STUB) + len(stub)] = stub
        o = off(BOOT_HOOK)
        assert bytes(img[o:o + 6]) == b"\x41\xf9\x10\x00\x00\x00", img[o:o + 6].hex()
        img[o:o + 6] = b"\x4e\xf9" + BOOT_STUB.to_bytes(4, "big")   # jmp stub
        print(f"boot-init: {len(stub)} B @0x{BOOT_STUB:08x}; detour @0x{BOOT_HOOK:08x}; "
              f"zero+fill [0x{HOLE_LO:08x},0x{HOLE_HI:08x})")
    else:
        print("boot-init: DISABLED (diagnostic) — no boot-zero, B-tables uninitialised "
              "(Wave-0 getter path never reads them)")

    # 2b) sidecar: persist SETTINGS-B to <projectdir>/project.256 (save + load hooks)
    if SIDECAR:
        sc, scsym = build_sidecar()
        assert not any(img[off(SIDECAR_AT):off(SIDECAR_AT) + len(sc)]), "sidecar cave not empty"
        assert SIDECAR_AT + len(sc) <= HELP_AT, "sidecar overruns into helper cave"
        img[off(SIDECAR_AT):off(SIDECAR_AT) + len(sc)] = sc
        # SAVE hook: 6 bytes tstl a3(4a8b) beqs+2(6702) jsr a3@(4e93) -> jmp sidecar_save
        o = off(SAVE_HOOK)
        assert bytes(img[o:o + 6]) == b"\x4a\x8b\x67\x02\x4e\x93", img[o:o + 6].hex()
        img[o:o + 6] = b"\x4e\xf9" + scsym["sidecar_save"].to_bytes(4, "big")
        # LOAD hook: 6 bytes moveml fp@(-576),d2-d6/a2-a4 (4cee 1c7c fdc0) -> jmp sidecar_load
        o = off(LOAD_HOOK)
        assert bytes(img[o:o + 6]) == b"\x4c\xee\x1c\x7c\xfd\xc0", img[o:o + 6].hex()
        img[o:o + 6] = b"\x4e\xf9" + scsym["sidecar_load"].to_bytes(4, "big")
        print(f"sidecar: {len(sc)} B @0x{SIDECAR_AT:08x}; save-hook 0x{SAVE_HOOK:08x}->0x{scsym['sidecar_save']:08x}"
              f"; load-hook 0x{LOAD_HOOK:08x}->0x{scsym['sidecar_load']:08x}; persists [0x{SETB_LO:08x},0x{SETB_HI:08x})")

    # 3) migrate the core set
    nsite = nclamp = 0
    for fn, spec in CORE.items():
        for imm_va, hn in spec["sites"]:
            assert hn in sym, f"helper {hn} missing"
            redirect_site(img, imm_va, sym[hn])
            print(f"  site  0x{imm_va-2:08x} add -> jsr {hn}(0x{sym[hn]:08x})   [{fn}]")
            nsite += 1
        if RAISE_CLAMPS:
            for cva in spec["clamps"]:
                nb = raise_clamp(img, cva); nclamp += 1
                print(f"  clamp 0x{cva:08x} #128 -> #{nb}   [{fn}]")
        else:
            print(f"  clamps KEPT at #128 (diagnostic, stock-equivalent)   [{fn}]")
    print(f"migrated: {nsite} sites, {nclamp} clamps")

    # 3b) OOB completeness gate: no un-redirected per-slot base-add may remain in ANY opened-clamp
    # function -- else idx 128..255 would flow to a stock add and OOB-write the working region.
    if RAISE_CLAMPS:
        bad = []
        for fn, spec in CORE.items():
            rem = scan_slot_adds(img, spec["entry"], spec["end"])
            if rem:
                bad += [(fn, va) for va in rem]
        if bad:
            for fn, va in bad:
                print(f"  OOB-GATE FAIL: un-redirected per-slot add 0x{va:08x} in opened fn [{fn}]")
            sys.exit("OOB GATE FAILED — a per-slot add is still stock in an opened function; DO NOT FLASH")
        print("OOB-gate: every opened function has 0 remaining per-slot base-adds OK")

    # 3c) UI list-length caps (only meaningful when clamps are open)
    if RAISE_CLAMPS:
        for va, (old, new) in CAPS.items():
            o = off(va)
            assert bytes(img[o:o + 4]) == old, f"cap @0x{va:x} = {img[o:o+4].hex()} != {old.hex()}"
            img[o:o + 4] = new
            print(f"  cap   0x{va:08x} {old.hex()} -> {new.hex()} (list length ->256)")

    OUT.write_bytes(bytes(img))
    print(f"\n{OUT}: {len(img):,} bytes")
    print("NEXT: emu-gate ->  python3 tools/emu_check.py out/mainos_dual256.bin")


if __name__ == "__main__":
    main()
