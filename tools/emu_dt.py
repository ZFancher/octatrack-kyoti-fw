#!/usr/bin/env python3
# SPDX-License-Identifier: MIT
# SPDX-FileCopyrightText: 2026 Zachary Fancher ("Kyoti")
"""
Emulate the DT (MUTE MODE == 2) path in the DT build, against the real image bytes.

DT mute is a pure sequencer mute: the `pre` hook clears the same D5 mute/solo bits as OT+FX
(so FUN_40004db8 keeps every DSP-frame level word -> the sounding voice AND its FX still
reach the mix, untouched), but does NOT call the FUN_40008f84 note-off and does NOT maintain
DAT_8000184a.  `pre_v` drops new "start" voice-commands for a silenced track (no new trigs).
Net: whatever voice is playing rides its own amp envelope to its natural end (fade / sustain
/ infinite loop); only re-triggering is suppressed.

  static : get/set_mutemode now cover 3 modes (OT / OT+FX / DT); val_tbl[2] -> "DT".
  emu    : `pre`   gate 2 -> D5 bits cleared, NO note-off, REL_STATE untouched, shadow cleared;
                   gate 1 -> unchanged (note-off + REL_STATE);  gate 0 -> stock bail.
           `pre_v` gate 2 -> drops muted / solo-non-soloed starts, passes retrig / soloed.

Usage:  python3 tools/emu_dt.py [out/mainos_mutemode_dt.bin]
"""
import pathlib, struct, subprocess, sys
from unicorn import *
from unicorn.m68k_const import *

BASE = 0x40000400
IMGP = sys.argv[1] if len(sys.argv) > 1 else "out/mainos_mutemode_dt.bin"
IMG = pathlib.Path(IMGP).read_bytes()
STOCK = pathlib.Path("out/raw/section_3_MAIN_OS.bin").read_bytes()

MUTE_STATE = 0x80000008
SOLO_FLAG  = 0x80000037
REL_STATE  = 0x8000184a
SHADOW     = 0x80006c66
GATE       = 0x800000dc
NOTEOFF    = 0x40008f84
BACK       = 0x40004dcc
BACK_V     = 0x40005180
fail = 0


def check(c, m):
    global fail
    print(("  ok   " if c else "  FAIL ") + m)
    if not c:
        fail += 1


def cstr(a):
    o = a - BASE
    return IMG[o:IMG.index(b"\0", o)].decode("latin1")


SYM = {p[2]: int(p[0], 16) for p in
       (l.split() for l in subprocess.run(["m68k-elf-nm", "out/patch_softmute_dt.elf"],
        capture_output=True, text=True).stdout.splitlines()) if len(p) == 3}
MSYM = {p[2]: int(p[0], 16) for p in
        (l.split() for l in subprocess.run(["m68k-elf-nm", "out/patch_mutemode_dt.elf"],
         capture_output=True, text=True).stdout.splitlines()) if len(p) == 3}
PRE, PRE_V = SYM["pre"], SYM["pre_v"]


# ------------------------------------------------------------------ static: menu
print("=== static: MUTE MODE now has 3 values ===")
vt = MSYM["val_tbl"]
vals = [cstr(struct.unpack(">I", IMG[vt - BASE + 4 * i:vt - BASE + 4 * i + 4])[0]) for i in range(3)]
check(vals == ["OT", "OT+FX", "DT"], f'val_tbl -> {vals}')


def new_uc():
    uc = Uc(UC_ARCH_M68K, UC_MODE_BIG_ENDIAN)
    uc.mem_map(0x40000000, 0x01000000)
    uc.mem_map(0x80000000, 0x00100000)
    uc.mem_map(0x00000000, 0x00010000)
    uc.mem_write(0x40000400, IMG)
    uc.reg_write(UC_M68K_REG_A7, 0x0000F000)
    return uc


print("\n=== emu: get_mutemode / set_mutemode over [0, 2] ===")
g = MSYM["get_mutemode"]
for mode, want in [(-1, "OT"), (0, "OT"), (1, "OT+FX"), (2, "DT"), (3, "DT"), (99, "DT")]:
    uc = new_uc()
    uc.mem_write(GATE, struct.pack(">i", mode))
    sp = 0xF000 - 4
    uc.mem_write(sp, struct.pack(">I", 0xDEADBEEF))
    uc.reg_write(UC_M68K_REG_A7, sp)
    try:
        uc.emu_start(g, 0xDEADBEEF, count=2000)
    except UcError:
        pass
    d0 = uc.reg_read(UC_M68K_REG_D0)
    try:
        s = cstr(d0)
    except Exception:
        s = f"<bad 0x{d0:08x}>"
    check(s == want, f"MUTE_MODE={mode:<3} -> \"{s}\" (want \"{want}\")")

s_ = MSYM["set_mutemode"]
# (start, delta, wrap) -> expected, N_MODES = 3
cases = [(1, 1, 0, 2), (2, 1, 0, 2),        # [RIGHT] clamps at 2
         (2, -1, 0, 1), (0, -1, 0, 0),      # [LEFT] clamps at 0
         (2, 1, 1, 0), (0, -1, 1, 2)]       # [YES] wraps 2->0 ; underflow 0->2
for start, delta, wrap, want in cases:
    uc = new_uc()
    uc.mem_write(GATE, struct.pack(">i", start))
    sp = 0xF000
    for v in (wrap, delta):
        sp -= 4
        uc.mem_write(sp, struct.pack(">i", v))
    sp -= 4
    uc.mem_write(sp, struct.pack(">I", 0xDEADBEEF))
    uc.reg_write(UC_M68K_REG_A7, sp)
    try:
        uc.emu_start(s_, 0xDEADBEEF, count=2000)
    except UcError:
        pass
    got = struct.unpack(">i", uc.mem_read(GATE, 4))[0]
    check(got == want, f"start={start} delta={delta:+d} wrap={wrap} -> {got} (want {want})")


# ------------------------------------------------------------------ emu: pre
def run_pre(mute_state, solo_flag, gate, shadow=0):
    uc = new_uc()
    uc.mem_write(MUTE_STATE, struct.pack(">I", mute_state))
    uc.mem_write(SOLO_FLAG, bytes([solo_flag]))
    uc.mem_write(GATE, struct.pack(">I", gate))
    uc.mem_write(SHADOW, bytes([shadow]))
    uc.mem_write(REL_STATE, b"\x00")
    noteoffs = []

    def hook(u, addr, size, _):
        if addr == NOTEOFF:
            t = struct.unpack(">i", u.mem_read(u.reg_read(UC_M68K_REG_A7) + 4, 4))[0]
            noteoffs.append(t)
            ret = struct.unpack(">I", u.mem_read(u.reg_read(UC_M68K_REG_A7), 4))[0]
            u.reg_write(UC_M68K_REG_A7, u.reg_read(UC_M68K_REG_A7) + 4)
            u.reg_write(UC_M68K_REG_PC, ret)
    uc.hook_add(UC_HOOK_CODE, hook)
    uc.reg_write(UC_M68K_REG_A7, 0xF000)
    d5 = None
    try:
        uc.emu_start(PRE, BACK, count=20000)
        d5 = uc.reg_read(UC_M68K_REG_D5)
    except UcError:
        pass
    return uc, noteoffs, d5


print("\n=== emu: DT `pre` (gate 2) -- keep frame words, NO note-off ===")
uc, no, d5 = run_pre(mute_state=(1 << (8 + 3)), solo_flag=0, gate=2)
check(no == [], f"no note-off (got {no})")
check(uc.mem_read(REL_STATE, 1)[0] == 0, "REL_STATE untouched (0)")
check(uc.mem_read(SHADOW, 1)[0] == 0, "SHADOW cleared")
check((d5 >> (8 + 3)) & 1 == 0, f"D5 mute bit 3 cleared -> FUN_40004db8 keeps track 3's word (D5={d5:#010x})")

print("\n=== emu: DT `pre` (gate 2) + solo, track 0 soloed ===")
uc, no, d5 = run_pre(mute_state=(1 << 0), solo_flag=1, gate=2)
check(no == [], f"no note-off for the non-soloed tracks (got {no})")
check(uc.mem_read(REL_STATE, 1)[0] == 0, "REL_STATE untouched")
check(d5 & 0xFFFF == 0, f"D5 bits 0..15 cleared -> every track's words kept (D5={d5:#010x})")

print("\n=== emu: DT `pre` (gate 2) + solo, nothing soloed -> stock (no-op) ===")
uc, no, d5 = run_pre(mute_state=0, solo_flag=1, gate=2)
check(no == [] and uc.mem_read(REL_STATE, 1)[0] == 0, "no note-off, REL_STATE clear")
check(d5 == 0, f"D5 untouched (D5={d5:#010x})")

print("\n=== regression: OT+FX `pre` (gate 1) still note-offs + maintains REL_STATE ===")
uc, no, d5 = run_pre(mute_state=(1 << (8 + 3)), solo_flag=0, gate=1)
check(no == [3], f"note-off track 3 (got {no})")
check(uc.mem_read(REL_STATE, 1)[0] == (1 << 3), "REL_STATE bit 3 set")

print("\n=== regression: OT `pre` (gate 0) bails, mute bit left set ===")
uc, no, d5 = run_pre(mute_state=(1 << (8 + 3)), solo_flag=0, gate=0, shadow=0xAA)
check(no == [] and uc.mem_read(SHADOW, 1)[0] == 0, "no work, shadow cleared")
check((d5 >> (8 + 3)) & 1 == 1, f"D5 mute bit 3 left SET -> stock cut (D5={d5:#010x})")


# ------------------------------------------------------------------ emu: pre_v
def run_v(track, cmd, mute_state, solo_flag, gate):
    uc = new_uc()
    uc.mem_write(MUTE_STATE, struct.pack(">I", mute_state))
    uc.mem_write(SOLO_FLAG, bytes([solo_flag]))
    uc.mem_write(GATE, struct.pack(">I", gate))
    sp = 0xF000
    uc.mem_write(sp, struct.pack(">I", 0xDEAD0000))
    uc.mem_write(sp + 4, struct.pack(">I", track))
    uc.mem_write(sp + 8, struct.pack(">I", cmd))
    uc.mem_write(sp + 12, struct.pack(">I", 1))
    uc.reg_write(UC_M68K_REG_A7, sp)
    out = {"where": None}

    def hook(u, addr, size, _):
        if addr == BACK_V:
            out["where"] = "pass"
            u.reg_write(UC_M68K_REG_PC, 0xDEAD0000)
    uc.hook_add(UC_HOOK_CODE, hook)
    try:
        uc.emu_start(PRE_V, 0xDEAD0000, count=5000)
    except UcError:
        pass
    if out["where"] is None and uc.reg_read(UC_M68K_REG_PC) == 0xDEAD0000:
        out["where"] = "drop"
    return out["where"]


print("\n=== emu: DT `pre_v` (gate 2) -- suppress new trigs on silenced tracks ===")
START, RETRIG = 0x80, 0x90
check(run_v(3, START, 1 << (8 + 3), 0, 2) == "drop", "muted t3 start -> DROP")
check(run_v(3, RETRIG, 1 << (8 + 3), 0, 2) == "pass", "muted t3 retrig (stop bit) -> PASS")
check(run_v(3, START, 0, 0, 2) == "pass", "unmuted t3, no solo -> PASS")
check(run_v(3, START, 1 << 0, 1, 2) == "drop", "solo t0, non-soloed t3 start -> DROP")
check(run_v(0, START, 1 << 0, 1, 2) == "pass", "solo t0, soloed t0 start -> PASS")
check(run_v(3, START, 1 << (8 + 3), 0, 1) == "drop", "regression: OT+FX still drops muted start")
check(run_v(3, START, 1 << (8 + 3), 0, 0) == "pass", "regression: OT lets the start through")

print()
print("ALL GOOD" if not fail else f"{fail} FAILURE(S)")
sys.exit(1 if fail else 0)
