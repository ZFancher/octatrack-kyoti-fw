#!/usr/bin/env python3
"""
Verify + emulate the MUTE MODE PERSONALIZE entry in the built image.

  static : the 3 relocated menu arrays hold stock[0:2] + MUTE MODE + stock[2:16],
           the 5 array refs point at the relocated copies, `moveq #15` -> `#16`,
           and every detour jmp targets exactly a stub symbol.
  emu    : get_mutemode returns the right value string for MUTE_MODE in {-1,0,1,2},
           set_mutemode clamps ([LEFT]/[RIGHT]) and wraps ([YES]) over [0, N-1],
           and the gated patch_softmute `pre` hook only engages for MUTE_MODE==1.

Usage:  python3 tools/emu_mutemode.py [out/mainos_mutemode.bin]
"""
import pathlib, struct, subprocess, sys
from unicorn import *
from unicorn.m68k_const import *

BASE = 0x40000400
IMG = pathlib.Path(sys.argv[1] if len(sys.argv) > 1 else "out/mainos_mutemode.bin").read_bytes()
STOCK = pathlib.Path("out/raw/section_3_MAIN_OS.bin").read_bytes()

OLD_LBL, OLD_GET, OLD_SET = 0x400b2a34, 0x400b2a74, 0x400b2ac0
LBL_AT, GET_AT, SET_AT = 0x400d7700, 0x400d7760, 0x400d77c0
MUTE_MODE = 0x800000dc
fail = 0


def u32(buf, a):
    return struct.unpack(">I", buf[a - BASE:a - BASE + 4])[0]


def cstr(a):
    o = a - BASE
    return IMG[o:IMG.index(b"\0", o)].decode("latin1")


def check(cond, msg):
    global fail
    print(("  ok  " if cond else "  FAIL ") + msg)
    if not cond:
        fail += 1


print("=== static: relocated menu arrays ===")
sym = {}
nm = subprocess.run(["m68k-elf-nm", "out/patch_mutemode.elf"], capture_output=True, text=True).stdout
for l in nm.splitlines():
    p = l.split()
    if len(p) == 3:
        sym[p[2]] = int(p[0], 16)

for name, old, dst, newsym in [("labels", OLD_LBL, LBL_AT, "lbl_mutemode"),
                               ("getters", OLD_GET, GET_AT, "get_mutemode"),
                               ("setters", OLD_SET, SET_AT, "set_mutemode")]:
    want = [u32(STOCK, old + 4 * i) for i in range(2)] + [sym[newsym]] + \
           [u32(STOCK, old + 4 * i) for i in range(2, 16)]
    got = [u32(IMG, dst + 4 * i) for i in range(17)]
    check(got == want, f"{name}: 17 entries, MUTE MODE ({newsym} 0x{sym[newsym]:08x}) at index 2")

check(cstr(u32(IMG, LBL_AT + 8)) == "MUTE MODE", 'label[2] string == "MUTE MODE"')
vt = sym["val_tbl"]
check(cstr(u32(IMG, vt)) == "OT" and cstr(u32(IMG, vt + 4)) == "OT+FX",
      'val_tbl -> "OT", "OT+FX"')

print("\n=== static: refs + count ===")
for a, old, dst in [(0x40068efe, OLD_LBL, LBL_AT), (0x40068f0a, OLD_GET, GET_AT),
                    (0x40069022, OLD_SET, SET_AT), (0x4006903e, OLD_SET, SET_AT),
                    (0x40069056, OLD_SET, SET_AT)]:
    check(u32(IMG, a) == dst, f"ref 0x{a:08x} -> 0x{dst:08x} (was 0x{old:08x})")
check(IMG[0x40068fb2 - BASE:0x40068fb2 - BASE + 2] == b"\x72\x10", "count moveq #15 -> #16")

print("\n=== static: detours ===")
for site, elf, s in [(0x40004dc6, "out/patch_softmute.elf", "pre"),
                     (0x40005178, "out/patch_softmute.elf", "pre_v"),
                     (0x4009b6f2, "out/patch_trigscale.elf", "cave")]:
    nm2 = subprocess.run(["m68k-elf-nm", elf], capture_output=True, text=True).stdout
    tgt = {p[2]: int(p[0], 16) for p in (l.split() for l in nm2.splitlines()) if len(p) == 3}[s]
    o = site - BASE
    check(IMG[o:o + 2] == b"\x4e\xf9" and u32(IMG, site + 2) == tgt,
          f"detour 0x{site:08x} -> {s} 0x{tgt:08x}")

# ---------------------------------------------------------------- emulation
MEM = 0x40000000
SIZE = 0x02000000       # 32 MB covers 0x40000000 code + 0x800000xx flags window? no


def new_uc():
    uc = Uc(UC_ARCH_M68K, UC_MODE_BIG_ENDIAN)
    uc.mem_map(0x40000000, 0x01000000)          # code / rodata
    uc.mem_map(0x80000000, 0x00010000)          # PERSONALIZE flags + audio window (low part)
    uc.mem_map(0x00000000, 0x00010000)          # stack
    uc.mem_write(0x40000400, IMG)
    uc.reg_write(UC_M68K_REG_A7, 0x0000F000)
    return uc


def call(entry, args=(), mm=None):
    """call a leaf fn: push args + a sentinel return addr, run until it returns."""
    uc = new_uc()
    if mm:
        for a, v in mm.items():
            uc.mem_write(a, struct.pack(">I", v & 0xffffffff))
    sp = 0xF000
    for v in reversed(args):
        sp -= 4
        uc.mem_write(sp, struct.pack(">i", v))
    sp -= 4
    uc.mem_write(sp, struct.pack(">I", 0xDEADBEEF))
    uc.reg_write(UC_M68K_REG_A7, sp)
    try:
        uc.emu_start(entry, 0xDEADBEEF, count=2000)
    except UcError:
        pass
    d0 = uc.reg_read(UC_M68K_REG_D0)
    mode = struct.unpack(">i", uc.mem_read(MUTE_MODE, 4))[0]
    return d0, mode, uc


print("\n=== emu: get_mutemode ===")
g = sym["get_mutemode"]
for mode, want in [(-1, "OT"), (0, "OT"), (1, "OT+FX"), (2, "OT+FX"), (99, "OT+FX")]:
    d0, _, _ = call(g, mm={MUTE_MODE: mode})
    try:
        s = cstr(d0)
    except Exception:
        s = f"<bad ptr 0x{d0:08x}>"
    check(s == want, f"MUTE_MODE={mode:<3} -> \"{s}\"  (want \"{want}\")")

print("\n=== emu: set_mutemode (delta, wrap) ===")
s_ = sym["set_mutemode"]
# (start, delta, wrap) -> expected stored value.  wrap=0 clamp, wrap=1 wrap; N_MODES=2
cases = [
    (0, 1, 0, 1), (1, 1, 0, 1),          # [RIGHT] clamps at 1
    (1, -1, 0, 0), (0, -1, 0, 0),        # [LEFT]  clamps at 0
    (1, 1, 1, 0), (0, 1, 1, 1),          # [YES]   wraps 1->0, 0->1
    (0, -1, 1, 1),                       # wrap underflow 0->1
]
for start, delta, wrap, want in cases:
    _, mode, _ = call(s_, args=(delta, wrap), mm={MUTE_MODE: start})
    check(mode == want, f"start={start} delta={delta:+d} wrap={wrap} -> {mode}  (want {want})")

print("\n=== emu: gated patch_softmute `pre` only engages for MUTE_MODE==1 ===")
# `pre` displaced instr is `move.l 0x80000008,D5`; with SOLO clear + no muted tracks it
# should reach BACK (0x40004dcc) without touching REL_STATE.  We only check it RUNS and
# returns to BACK for both gate states (0 -> stock path, 1 -> soft path), no crash.
pre = {p[2]: int(p[0], 16) for p in
       (l.split() for l in subprocess.run(["m68k-elf-nm", "out/patch_softmute.elf"],
                                          capture_output=True, text=True).stdout.splitlines())
       if len(p) == 3}["pre"]
for gate in (0, 1, 2):
    uc = new_uc()
    uc.mem_write(0x80000008, struct.pack(">I", 1 << 8))   # track 0 muted (bit 8+t)
    uc.mem_write(0x80000037, b"\0")              # SOLO off
    uc.mem_write(0x80006c66, b"\xff")            # SHADOW: no 0->1 edge -> skip the note-off jsr
    uc.mem_write(MUTE_MODE, struct.pack(">I", gate))
    touched = {"rel": False}

    def hook_w(u, access, addr, size, value, _):
        if addr == 0x8000184a:                        # write to REL_STATE -> soft path engaged
            touched["rel"] = True
    uc.hook_add(UC_HOOK_MEM_WRITE, hook_w)
    try:
        uc.emu_start(pre, 0x40004dcc, count=4000)     # Unicorn stops when PC == BACK
    except UcError:
        pass
    at_back = uc.reg_read(UC_M68K_REG_PC) == 0x40004dcc
    want_soft = (gate == 1)
    check(at_back and touched["rel"] == want_soft,
          f"gate={gate}: reaches BACK, soft-path {'engaged' if want_soft else 'skipped'}")

print()
print("ALL GOOD" if not fail else f"{fail} FAILURE(S)")
sys.exit(1 if fail else 0)
