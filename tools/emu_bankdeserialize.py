#!/usr/bin/env python3
# SPDX-License-Identifier: MIT
# SPDX-FileCopyrightText: 2026 Zachary Fancher ("Kyoti")
# Run the REAL bank deserializer (FUN_4008ded0) against real bankNN.work bytes in
# Unicorn, instead of hand-deriving file-offset -> RAM-offset mappings.
#
# Every file read in FUN_4008ded0's call tree (confirmed via FUN_4008cebc,
# FUN_4008be2c) goes through one primitive, FUN_40016564(handle, buf, len). We patch
# just its 2 entry bytes to `rts` and hook that address: before the (patched) rts
# fires, copy the next `len` bytes from a real file (Python-side) into guest memory
# at `buf`, advance a cursor, set D0=len. Then call FUN_4008ded0 for real.
#
# Needs a big instruction budget (30M) -- deserializing 636KB through many small
# per-field reads with checksum bookkeeping is legitimately a lot of instructions,
# it's not a hang. Runs in well under a second once budgeted correctly.
#
# Usage: python3 emu_bankdeserialize.py <bankNN.work> [<bankNN_b.work> ...]
#   Dumps first 0x9c000 bytes of the deserialized RAM image per file; if 2+ files
#   given, also diffs each against the first and prints differing offsets.
import struct, pathlib, sys
from unicorn import *
from unicorn.m68k_const import *

BASE = 0x40000400
IMG_PATH = pathlib.Path(__file__).parent.parent / "out/raw/section_3_MAIN_OS.bin"
READ_FN = 0x40016564
DESTBUF = 0x50000000
STACK_TOP = 0x41010000
RET_MARK = 0x401f0000

def run_deserialize(file_bytes, dump_len=0x9c000, instr_budget=30_000_000):
    img = bytearray(IMG_PATH.read_bytes())
    off = READ_FN - BASE
    img[off:off+2] = b"\x4e\x75"   # patch read-helper entry -> rts; hook does the work

    uc = Uc(UC_ARCH_M68K, UC_MODE_BIG_ENDIAN)
    uc.mem_map(0x40000000, 0x200000)
    uc.mem_write(BASE, bytes(img[:0x200000-0x400]))
    uc.mem_map(0x41000000, 0x20000)
    uc.mem_map(DESTBUF, 0x200000)

    def on_unmapped(uc, access, address, size, value, user):
        uc.mem_map(address & ~0xFFF, 0x1000)
        return True
    uc.hook_add(UC_HOOK_MEM_UNMAPPED, on_unmapped)

    cursor = {"pos": 0}
    def on_code(uc, address, size, user):
        if address == READ_FN:
            sp = uc.reg_read(UC_M68K_REG_A7)
            buf    = struct.unpack(">I", uc.mem_read(sp+8, 4))[0]
            length = struct.unpack(">I", uc.mem_read(sp+12,4))[0]
            chunk = file_bytes[cursor["pos"]:cursor["pos"]+length]
            if len(chunk) < length:
                chunk = chunk + b"\x00"*(length-len(chunk))
            uc.mem_write(buf, chunk)
            cursor["pos"] += length
            uc.reg_write(UC_M68K_REG_D0, length)
    uc.hook_add(UC_HOOK_CODE, on_code, begin=READ_FN, end=READ_FN)

    sp = STACK_TOP
    for a in reversed([1, DESTBUF, 0]):   # handle=1, destbuf, checkonly=0
        sp -= 4
        uc.mem_write(sp, struct.pack(">I", a & 0xffffffff))
    sp -= 4
    uc.mem_write(sp, struct.pack(">I", RET_MARK))
    uc.reg_write(UC_M68K_REG_A7, sp)

    try:
        uc.emu_start(0x4008ded0, RET_MARK, count=instr_budget)
    except UcError as e:
        print("  [UcError %s @ PC=0x%08x]" % (e, uc.reg_read(UC_M68K_REG_PC)))

    d0 = uc.reg_read(UC_M68K_REG_D0)
    print(f"  return D0=0x{d0:x}  bytes_consumed_from_file={cursor['pos']} (file size {len(file_bytes)})")
    return bytes(uc.mem_read(DESTBUF, dump_len))

if __name__ == "__main__":
    paths = sys.argv[1:]
    if not paths:
        print(__doc__ or "usage: emu_bankdeserialize.py <bankNN.work> [more...]"); sys.exit(1)
    dumps = []
    for p in paths:
        print(f"=== deserializing {p} ===")
        dumps.append(run_deserialize(pathlib.Path(p).read_bytes()))
    if len(dumps) > 1:
        base = dumps[0]
        for p, d in zip(paths[1:], dumps[1:]):
            diffs = [i for i in range(len(base)) if base[i] != d[i]]
            print(f"\n{paths[0]} vs {p}: {len(diffs)} differing bytes (blob-relative offset):")
            for i in diffs[:50]:
                print(f"  0x{i:x} ({i}): {paths[0]}=0x{base[i]:02x}  {p}=0x{d[i]:02x}")
