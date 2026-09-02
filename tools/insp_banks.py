#!/usr/bin/env python3
# SPDX-License-Identifier: MIT
# SPDX-FileCopyrightText: 2026 Zachary Fancher ("Kyoti")
# Deserialize each real test bank via the REAL firmware deserializer (FUN_4008ded0) in
# Unicorn, then dump the per-MIDI-track header bytes and pattern-level scale bytes for
# patterns 0 and 1, so we can see exactly which track/pattern each test project configured
# and what +0x48fc/+0x48fd/+0x48fe/+0x8e55 actually hold.
import struct, pathlib, sys
from unicorn import *
from unicorn.m68k_const import *

BASE = 0x40000400
HERE = pathlib.Path(__file__).parent
IMG_PATH = HERE.parent / "out/raw/section_3_MAIN_OS.bin"
if not IMG_PATH.exists():
    IMG_PATH = HERE / "section_3_MAIN_OS.bin"   # fallback if run from a scratch copy
# test banks: put bankNN.work copies at tools/banks/<name>.bank01.work, or edit `names`/paths.
READ_FN = 0x40016564
DESTBUF = 0x50000000
STACK_TOP = 0x41010000
RET_MARK = 0x401f0000

PAT_STRIDE = 0x8ed8
MIDI_STRIDE = 0x8b0

def deserialize(file_bytes):
    img = bytearray(IMG_PATH.read_bytes())
    off = READ_FN - BASE
    img[off:off+2] = b"\x4e\x75"
    uc = Uc(UC_ARCH_M68K, UC_MODE_BIG_ENDIAN)
    uc.mem_map(0x40000000, 0x200000)
    uc.mem_write(BASE, bytes(img[:0x200000-0x400]))
    uc.mem_map(0x41000000, 0x20000)
    uc.mem_map(DESTBUF, 0x200000)
    def on_unmapped(uc, access, address, size, value, user):
        uc.mem_map(address & ~0xFFF, 0x1000); return True
    uc.hook_add(UC_HOOK_MEM_UNMAPPED, on_unmapped)
    cursor = {"pos": 0}
    def on_code(uc, address, size, user):
        if address == READ_FN:
            sp = uc.reg_read(UC_M68K_REG_A7)
            buf = struct.unpack(">I", uc.mem_read(sp+8, 4))[0]
            length = struct.unpack(">I", uc.mem_read(sp+12,4))[0]
            chunk = file_bytes[cursor["pos"]:cursor["pos"]+length]
            if len(chunk) < length: chunk += b"\x00"*(length-len(chunk))
            uc.mem_write(buf, chunk); cursor["pos"] += length
            uc.reg_write(UC_M68K_REG_D0, length)
    uc.hook_add(UC_HOOK_CODE, on_code, begin=READ_FN, end=READ_FN)
    sp = STACK_TOP
    for a in reversed([1, DESTBUF, 0]):
        sp -= 4; uc.mem_write(sp, struct.pack(">I", a & 0xffffffff))
    sp -= 4; uc.mem_write(sp, struct.pack(">I", RET_MARK))
    uc.reg_write(UC_M68K_REG_A7, sp)
    try:
        uc.emu_start(0x4008ded0, RET_MARK, count=30_000_000)
    except UcError as e:
        print("  [UcError %s @ PC=0x%08x]" % (e, uc.reg_read(UC_M68K_REG_PC)))
    return bytes(uc.mem_read(DESTBUF, 0x9c000))

def sb(x):  # signed byte
    return x-256 if x >= 128 else x

names = ["test1_PF_", "test1_PFD", "test1_PFD_scale", "test1nil", "test1nil_scale"]
blobs = {}
for n in names:
    fb = (HERE / "banks" / f"{n}.bank01.work").read_bytes()
    blobs[n] = deserialize(fb)
    print(f"deserialized {n}: {len(fb)} bytes in")

for p in (0, 1):
    print(f"\n================ PATTERN {p} ================")
    pbase = p * PAT_STRIDE
    hdr = f"{'proj':16} " + "  ".join(f"m{t}:PF/TM/DIR" for t in range(8))
    for n in names:
        b = blobs[n]
        cells = []
        for t in range(8):
            o = pbase + t*MIDI_STRIDE
            cells.append(f"{b[o+0x48fc]:>2} {b[o+0x48fd]:>2} {sb(b[o+0x48fe]):>3}")
        print(f"{n:16} " + " | ".join(cells))
    print(f"{'':16} pattern-level +0x8e50..+0x8e58:")
    for n in names:
        b = blobs[n]
        vals = " ".join(f"{b[pbase+0x8e50+i]:02x}" for i in range(9))
        print(f"{n:16}   {vals}   (8e55=SCALE_MODE -> {b[pbase+0x8e55]})")

# full byte-diff PFD vs PFD_scale, blob-relative
a, c = blobs["test1_PFD"], blobs["test1_PFD_scale"]
diffs = [i for i in range(len(a)) if a[i] != c[i]]
print(f"\ntest1_PFD vs test1_PFD_scale: {len(diffs)} differing blob bytes")
for i in diffs:
    pat = i // PAT_STRIDE; rel = i % PAT_STRIDE
    where = f"pattern{pat} +0x{rel:x}"
    if rel < 8*MIDI_STRIDE and rel >= 0x48d0 % 1:
        pass
    print(f"  blob+0x{i:x}  ({where}): PFD=0x{a[i]:02x}  PFD_scale=0x{c[i]:02x}")
