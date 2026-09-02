#!/usr/bin/env python3
# SPDX-License-Identifier: MIT
# SPDX-FileCopyrightText: 2026 Zachary Fancher ("Kyoti")
"""
Emulate the V7 patch_softmute `pre` hook + the real FUN_40004db8 frame builder to prove the
SOLO soft-silence: with MUTE MODE == OT+FX, a track silenced because another track is soloed
keeps its DSP-frame level words (so its FX inserts still ring) and gets a one-shot note-off.

Runs the ACTUAL bytes from out/mainos_mutemode.bin.

Usage:  python3 tools/emu_solo.py [out/mainos_mutemode.bin]
"""
import pathlib, struct, subprocess, sys
from unicorn import *
from unicorn.m68k_const import *

BASE = 0x40000400
IMGP = sys.argv[1] if len(sys.argv) > 1 else "out/mainos_mutemode.bin"
IMG = pathlib.Path(IMGP).read_bytes()
# the DT build (build_mutemode_dt.py) links its stubs as out/patch_*_dt.elf
SOFTMUTE_ELF = "out/patch_softmute_dt.elf" if IMGP.endswith("_dt.bin") else "out/patch_softmute.elf"

MUTE_STATE = 0x80000008
SOLO_FLAG  = 0x80000037
REL_STATE  = 0x8000184a
SHADOW     = 0x80006c66
GATE       = 0x800000dc
FRAME_PTR  = 0x80003c10          # FUN_40004db8 loads this -> frame dest
FRAME_DST  = 0x80040000          # where we point it
PRE   = {p[2]: int(p[0], 16) for p in
         (l.split() for l in subprocess.run(["m68k-elf-nm", SOFTMUTE_ELF],
          capture_output=True, text=True).stdout.splitlines()) if len(p) == 3}["pre"]
NOTEOFF = 0x40008f84
fail = 0


def check(c, m):
    global fail
    print(("  ok   " if c else "  FAIL ") + m)
    if not c:
        fail += 1


BACK = 0x40004dcc              # `pre` returns here; FUN_40004db8 then branches on SOLO_FLAG


def run(mute_state, solo_flag, gate=1, shadow=0, trace=False):
    """run just `pre` (stop at BACK); return (uc, noteoffs, d5)."""
    uc = Uc(UC_ARCH_M68K, UC_MODE_BIG_ENDIAN)
    uc.mem_map(0x40000000, 0x01000000)
    uc.mem_map(0x80000000, 0x00100000)
    uc.mem_map(0x00000000, 0x00010000)
    uc.mem_write(0x40000400, IMG)
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
            u.reg_write(UC_M68K_REG_PC, ret)         # emulate FUN_40008f84 as rts
        if trace:
            print(f"    {addr:08x}")
    uc.hook_add(UC_HOOK_CODE, hook)

    sp = 0xF000
    uc.reg_write(UC_M68K_REG_A7, sp)
    d5 = None
    try:
        uc.emu_start(PRE, BACK, count=20000)
        d5 = uc.reg_read(UC_M68K_REG_D5)
    except UcError as e:
        if trace:
            print("   UcError", e)
    return uc, noteoffs, d5


# In FUN_40004db8, the NOT-solo branch keeps a track's mute-gated frame word iff D5 bit 8+t
# is clear; the SOLO branch keeps every track's words iff (bit t clear AND bit 8+t clear) and
# the "any track soloed?" mask D1 == -1, which holds iff D5 low byte == 0.  So the frame-keep
# claim reduces to: after `pre`, what is D5?

def mutebits(d5):   return (d5 >> 8) & 0xFF
def solobits(d5):   return d5 & 0xFF

print("=== not solo, track 3 muted (V6 mute path unchanged) ===")
uc, no, d5 = run(mute_state=(1 << (8 + 3)), solo_flag=0)
check(uc.mem_read(REL_STATE, 1)[0] == (1 << 3), "REL_STATE bit 3 set")
check(uc.mem_read(SHADOW, 1)[0] == (1 << 3), "SHADOW == 0x08")
check(no == [3], f"note-off once for track 3 (got {no})")
check(mutebits(d5) & (1 << 3) == 0, f"D5 mute bit 3 CLEARED -> FUN_40004db8 keeps track 3's word (D5={d5:#010x})")

print("\n=== solo active, track 0 soloed -> tracks 1..7 soft-silenced ===")
uc, no, d5 = run(mute_state=(1 << 0), solo_flag=1)
check(uc.mem_read(REL_STATE, 1)[0] == 0xFE, "REL_STATE == 0xFE (all non-soloed)")
check(uc.mem_read(SHADOW, 1)[0] == 0xFE, "SHADOW == 0xFE")
check(sorted(no) == [1, 2, 3, 4, 5, 6, 7], f"note-off once for tracks 1..7 (got {sorted(no)})")
check(solobits(d5) == 0 and mutebits(d5) == 0,
      f"D5 bits 0..15 CLEARED -> D1 becomes -1, FUN_40004db8 keeps every track's words (D5={d5:#010x})")

print("\n=== solo active, nothing soloed -> nothing silenced (stock) ===")
uc, no, d5 = run(mute_state=0, solo_flag=1)
check(uc.mem_read(REL_STATE, 1)[0] == 0 and uc.mem_read(SHADOW, 1)[0] == 0 and no == [],
      "no note-offs, REL/SHADOW clear")
check(d5 == (0 & 0xFFFFFFFF), f"D5 untouched (0) -> stock solo path (D5={d5:#010x})")

print("\n=== solo active + track 3 ALSO manually muted, track 0 soloed ===")
uc, no, d5 = run(mute_state=(1 << 0) | (1 << (8 + 3)), solo_flag=1)
check(sorted(no) == [1, 2, 3, 4, 5, 6, 7], f"note-off tracks 1..7 incl. the muted one (got {sorted(no)})")
check(solobits(d5) == 0 and mutebits(d5) == 0, f"D5 bits 0..15 cleared (D5={d5:#010x})")

print("\n=== solo edge: no double note-off when the silenced set is unchanged ===")
uc, no, d5 = run(mute_state=(1 << 1), solo_flag=1, shadow=0xFD)   # 0,2..7 already silenced
check(no == [], f"shadow already 0xFD -> no fresh note-offs (got {no})")
check(uc.mem_read(REL_STATE, 1)[0] == 0xFD, "REL_STATE still maintained at 0xFD")

print("\n=== MUTE MODE == OT: bail + clear shadow, stock cut downstream ===")
uc, no, d5 = run(mute_state=(1 << (8 + 3)), solo_flag=0, gate=0, shadow=0xAA)
check(uc.mem_read(SHADOW, 1)[0] == 0 and uc.mem_read(REL_STATE, 1)[0] == 0 and no == [],
      "OT mode: shadow cleared, no work")
check(mutebits(d5) & (1 << 3) != 0, f"D5 mute bit 3 LEFT SET -> stock FUN_40004db8 zeroes the word (D5={d5:#010x})")

print("\n=== OT -> OT+FX transition: stale shadow doesn't swallow the first note-off ===")
uc, no, d5 = run(mute_state=(1 << (8 + 5)), solo_flag=0, gate=0, shadow=0xFF)
check(uc.mem_read(SHADOW, 1)[0] == 0, "OT frame leaves shadow == 0")
uc, no, d5 = run(mute_state=(1 << (8 + 5)), solo_flag=0, gate=1, shadow=0x00)
check(no == [5], f"OT+FX frame then note-offs track 5 (got {no})")

print("\n=== pre_v: drop a 'start' voice-cmd for a silenced track ===")
PRE_V = {p[2]: int(p[0], 16) for p in
         (l.split() for l in subprocess.run(["m68k-elf-nm", SOFTMUTE_ELF],
          capture_output=True, text=True).stdout.splitlines()) if len(p) == 3}["pre_v"]
BACK_V = 0x40005180


def run_v(track, cmd, mute_state, solo_flag, gate=1):
    """call pre_v(track, cmd, flag=1); return 'drop' (rts, d0=1) or 'pass' (jmp BACK_V)."""
    uc = Uc(UC_ARCH_M68K, UC_MODE_BIG_ENDIAN)
    uc.mem_map(0x40000000, 0x01000000)
    uc.mem_map(0x80000000, 0x00100000)
    uc.mem_map(0x00000000, 0x00010000)
    uc.mem_write(0x40000400, IMG)
    uc.mem_write(MUTE_STATE, struct.pack(">I", mute_state))
    uc.mem_write(SOLO_FLAG, bytes([solo_flag]))
    uc.mem_write(GATE, struct.pack(">I", gate))
    sp = 0xF000
    # entry (jmp detour): (0)=ret (4)=track (8)=cmd (0xc)=flag
    uc.mem_write(sp, struct.pack(">I", 0xDEAD0000))
    uc.mem_write(sp + 4, struct.pack(">I", track))
    uc.mem_write(sp + 8, struct.pack(">I", cmd))
    uc.mem_write(sp + 12, struct.pack(">I", 1))
    uc.reg_write(UC_M68K_REG_A7, sp)
    out = {"where": None, "d0": None}

    def hook(u, addr, size, _):
        if addr == BACK_V:                    # pass path reached the displaced prologue
            out["where"] = "pass"
            u.reg_write(UC_M68K_REG_PC, 0xDEAD0000)   # funnel to the single stop addr
    uc.hook_add(UC_HOOK_CODE, hook)
    try:
        uc.emu_start(PRE_V, 0xDEAD0000, count=5000)
    except UcError:
        pass
    if out["where"] is None and uc.reg_read(UC_M68K_REG_PC) == 0xDEAD0000:
        out["where"] = "drop"
        out["d0"] = uc.reg_read(UC_M68K_REG_D0)
    return out["where"], out["d0"]

START = 0x80            # bit 7 set, bit 4 clear == a bare "start"
STARTSTOP = 0x90        # bit 7 + bit 4 == retrig/stop, must always pass

w, d0 = run_v(3, START, mute_state=(1 << (8 + 3)), solo_flag=0)
check(w == "drop" and d0 == 1, f"muted track 3, start -> DROP (got {w},{d0})")
w, _ = run_v(3, START, mute_state=0, solo_flag=0)
check(w == "pass", f"unmuted track 3, no solo, start -> PASS (got {w})")
w, _ = run_v(3, START, mute_state=(1 << 0), solo_flag=1)     # track 0 soloed, 3 not
check(w == "drop", f"solo on t0, start on non-soloed t3 -> DROP (got {w})")
w, _ = run_v(0, START, mute_state=(1 << 0), solo_flag=1)     # track 0 soloed
check(w == "pass", f"solo on t0, start on soloed t0 -> PASS (got {w})")
w, _ = run_v(3, START, mute_state=0, solo_flag=1)            # solo armed, nothing soloed
check(w == "pass", f"solo armed but nothing soloed, start on t3 -> PASS (got {w})")
w, _ = run_v(3, STARTSTOP, mute_state=(1 << 0), solo_flag=1)  # retrig always passes
check(w == "pass", f"non-soloed t3 retrig (stop bit set) -> PASS (got {w})")
w, _ = run_v(3, START, mute_state=(1 << 0), solo_flag=1, gate=0)  # OT mode
check(w == "pass", f"MUTE MODE == OT: no drop (got {w})")

print()
print("ALL GOOD" if not fail else f"{fail} FAILURE(S)")
sys.exit(1 if fail else 0)
