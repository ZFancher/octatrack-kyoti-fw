#!/usr/bin/env python3
"""emu_sidecar_load.py -- EMULATOR proof of the persistence CLOBBER.

sidecar_load (0x400d669e, installed at LOAD_HOOK 0x4009021a = epilogue of the FLEX loader, which runs
AFTER the STATIC load-loop) does an UNCONDITIONAL block IO_READ of <projdir>/project.256 over the whole
SETTINGS-B region (0x40a955e0, ln bytes). This runs it in Unicorn with the IO calls stubbed, seeding
SET-B[0]@0 with a "parser-written" path first, and tests three project.256 states:

  (A) populated  (slot-0 path present)  -> SET-B[0]@0 = that path        (restore works)
  (B) empty      (slot-0 zeros)         -> SET-B[0]@0 = 0  (CLOBBERED)   <<< the name-blank bug
  (C) missing    (open fails)           -> SET-B[0]@0 unchanged (skip)

    python3 tools/emu_sidecar_load.py
"""
import pathlib, sys
from unicorn import *
from unicorn.m68k_const import *
sys.path.insert(0, "tools")
import build_dual256 as bd

BASE = 0x40000400
IMG = bytes(pathlib.Path("out/mainos_persist256.bin").read_bytes())
SIDE_LOAD = 0x400d669e
END = 0x40090220                       # LOAD_HOOK+6 (routine jmps here at the end)
SETB_LO = bd.SETB_LO                    # 0x40a955e0
STRIDE = 0x448
DIR_OF, IO_SPRINTF = 0x40025230, 0x40013a08
IO_OPEN, IO_READ, IO_CLOSE = 0x40016864, 0x40016564, 0x4001677c
DIRSTR = 0x00008100                     # scratch dir string


def run(mode, file_bytes):
    mu = Uc(UC_ARCH_M68K, UC_MODE_BIG_ENDIAN)
    for a, s in [(0x40000000, 0x2000000), (0x10000000, 0x400000),
                 (0x46000000, 0x1000000), (0x00008000, 0x8000)]:
        mu.mem_map(a, s)
    mu.mem_write(BASE, IMG)
    mu.mem_write(DIRSTR, b"SET\x00")
    # seed SET-B[0]@0 with a path the parser "already wrote"
    seeded = b"PARSER.wav\x00"
    mu.mem_write(SETB_LO, seeded + b"\x00" * (STRIDE - len(seeded)))

    def stub(mu, address, size, ud):
        sp = mu.reg_read(UC_M68K_REG_A7)
        def ret_with(d0):
            r = int.from_bytes(mu.mem_read(sp, 4), "big")
            mu.reg_write(UC_M68K_REG_D0, d0)
            mu.reg_write(UC_M68K_REG_A7, sp + 4)
            mu.reg_write(UC_M68K_REG_PC, r)
        if address == DIR_OF:
            ret_with(DIRSTR)                                   # return dir ptr
        elif address == IO_SPRINTF:
            ret_with(0)                                        # no-op (path content irrelevant; open stubbed)
        elif address == IO_OPEN:
            ret_with(0 if mode != "missing" else 0xffffffff)   # >=0 success / <0 fail
        elif address == IO_READ:
            # stack after jsr (top->): retaddr, stream(sp+4), dest(sp+8), len(sp+12)
            dest = int.from_bytes(mu.mem_read(sp + 8, 4), "big")
            ln = int.from_bytes(mu.mem_read(sp + 12, 4), "big")
            data = (file_bytes + b"\x00" * ln)[:ln]
            mu.mem_write(dest, data)
            ret_with(len(file_bytes))
        elif address == IO_CLOSE:
            ret_with(0)

    for f in (DIR_OF, IO_SPRINTF, IO_OPEN, IO_READ, IO_CLOSE):
        mu.hook_add(UC_HOOK_CODE, stub, begin=f, end=f)

    sp = 0x0000c000
    mu.mem_write(sp, END.to_bytes(4, "big"))
    mu.reg_write(UC_M68K_REG_A7, sp)
    mu.reg_write(UC_M68K_REG_A6, 0x0000b000)      # fp: moveml fp@(-576) lands in mapped stack
    mu.reg_write(UC_M68K_REG_PC, SIDE_LOAD)
    mu.emu_start(SIDE_LOAD, END, count=100000)
    got = bytes(mu.mem_read(SETB_LO, 16)).split(b"\x00", 1)[0]
    return got


def main():
    ln = bd.SETB_HI - bd.SETB_LO
    # a "populated" project.256: slot-0 path present
    populated = b"RESTORED.wav\x00" + b"\x00" * (ln - 13)
    empty = b"\x00" * ln
    print("sidecar_load 0x%08x, block read of %d B into SET-B 0x%08x\n" % (SIDE_LOAD, ln, SETB_LO))
    print("  seed (parser-written) SET-B[0]@0 = b'PARSER.wav'\n")
    a = run("populated", populated)
    b = run("empty", empty)
    c = run("missing", b"")
    print(f"  (A) project.256 populated (slot0='RESTORED.wav') -> SET-B[0]@0 = {a!r}   (restore)")
    print(f"  (B) project.256 empty     (slot0=zeros)          -> SET-B[0]@0 = {b!r}   (parser SURVIVES)")
    print(f"  (C) project.256 missing   (open fails)           -> SET-B[0]@0 = {c!r}   (unchanged)")
    # FIXED (skip-empty) contract: populated wins; empty does NOT clobber; missing untouched.
    okA = a == b"RESTORED.wav"
    okB = b == b"PARSER.wav"      # <-- was b'' before the fix; skip-empty preserves the parser path
    okC = c == b"PARSER.wav"
    print("\n" + ("ALL GREEN -- skip-empty FIX verified: project.256 wins for populated slots (incl slices), "
                  "an EMPTY project.256 slot no longer clobbers SET-B (parser path survives) => name shows, "
                  "and a missing file leaves parser data intact."
                  if (okA and okB and okC) else
                  f"UNEXPECTED: A={okA} B={okB} C={okC} -- re-examine"))


if __name__ == "__main__":
    main()
