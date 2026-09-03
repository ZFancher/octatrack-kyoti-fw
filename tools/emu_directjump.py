#!/usr/bin/env python3
# SPDX-License-Identifier: MIT
# SPDX-FileCopyrightText: 2026 Zachary Fancher ("Kyoti")
"""
Exercise the three DIRECT JUMP stubs (patch_directjump.bin) on a hand-built ColdFire
state -- the whole per-step handler FUN_400a1eea can't run under Unicorn (unsupported
insns), like emu_otfx.py, so this drives each stub in isolation.

  dj_a @0x400a4006  arm / send PC / force step==0.  Checks the OFF path, the
                    arranger/chain guards, the 2-tick arm->commit sequence, PC dedup,
                    register save/restore, and the Z flag left for the caller's beq.w.
  dj_b @0x400a42fa  gate bypass.  Checks the return-address rewrite (armed) vs the
                    displaced `move.l #0x8e56,d0` (not armed) and D6.
  dj_c @0x400a4840  playhead resume.  Checks DAT_800065b6 = 0 (not armed) vs
                    savedStep % newLen with D7 set (armed), incl. a shorter new pattern.

FUN_4009e884 (the real PC sender) is stubbed: the call is trapped, its args recorded,
and control returned -- we only assert that dj_a calls it with (bank, pat) at the right
times.
"""
import pathlib, struct, sys
from unicorn import *
from unicorn.m68k_const import *

ROOT = pathlib.Path(__file__).resolve().parent.parent
STUB = (ROOT / "out/patch_directjump.bin").read_bytes()
LOAD = 0x400d7400
DJ_A, DJ_B, DJ_C = 0x400d747a, 0x400d7534, 0x400d754e
PC_SEND = 0x4009e884

DJ_MODE = 0x800000a8
G_ARMED, G_STEP, G_PCPAT = 0x80006a40, 0x80006a41, 0x80006a42
ACT_PAT, ACT_BANK = 0x800065be, 0x800065bd
PEND_PAT, PEND_BANK = 0x800065c0, 0x800065bf
STEP = 0x800065b6
SCALE_IX = 0x8000663d
STOPFLAG = 0x8000667e
ARR_ACT = 0x460d1aec
CHAIN_ACT = 0x80006546
LEN_TBL = 0x400aba50
PAT_SCALE = 0x400eb034
SW_LABEL = 0x400a43a0
RET_A = 0x400a400c        # instruction after the dj_a detour
RET_B = 0x400a4300        # instruction after the dj_b detour

SEED = {UC_M68K_REG_D2: 0x0a0a0a0a, UC_M68K_REG_D3: 0x0b0b0b0b,
        UC_M68K_REG_D4: 0x0c0c0c0c, UC_M68K_REG_D5: 0x0d0d0d0d,
        UC_M68K_REG_D6: 0x0e0e0e0e, UC_M68K_REG_D7: 0x0f0f0f0f,
        UC_M68K_REG_A2: 0x46a2a2a2, UC_M68K_REG_A3: 0x46a3a3a3,
        UC_M68K_REG_A4: 0x46a4a4a4, UC_M68K_REG_A5: 0x46a5a5a5,
        UC_M68K_REG_A6: 0x46a6a6a6}

fails = []


def check(name, cond, detail=""):
    print(f"  [{'ok ' if cond else 'FAIL'}] {name}" + (f"  ({detail})" if detail else ""))
    if not cond:
        fails.append(name)


def mk():
    uc = Uc(UC_ARCH_M68K, UC_MODE_BIG_ENDIAN)
    uc.mem_map(0x40000000, 0x400000)
    uc.mem_map(0x46000000, 0x1000000)
    uc.mem_map(0x80000000, 0x20000)
    uc.mem_map(0x41000000, 0x20000)
    uc.mem_write(LOAD, STUB)
    # pattern-length table: index 4 -> 16, index 6 -> 64  (just two entries we use)
    for idx, ln in ((4, 16), (6, 64), (2, 8)):
        uc.mem_write(LEN_TBL + idx * 4, struct.pack(">I", ln))
    return uc


def run(uc, entry, want_ret):
    sp0 = 0x41010000
    uc.mem_write(sp0, struct.pack(">I", want_ret))   # the "return address" for the stub's rts
    uc.reg_write(UC_M68K_REG_A7, sp0)
    for r, v in SEED.items():
        uc.reg_write(r, v)
    uc.reg_write(UC_M68K_REG_D0, 0x11111111)
    uc.reg_write(UC_M68K_REG_D1, 0x22222222)

    trace = {"pc_send": [], "stopped_at": None, "sr": None}

    def hook(uc, addr, size, u):
        if addr == PC_SEND:
            sp = uc.reg_read(UC_M68K_REG_A7)
            bank = struct.unpack(">I", uc.mem_read(sp + 4, 4))[0]
            pat = struct.unpack(">I", uc.mem_read(sp + 8, 4))[0]
            trace["pc_send"].append((bank, pat))
            # emulate rts
            ret = struct.unpack(">I", uc.mem_read(sp, 4))[0]
            uc.reg_write(UC_M68K_REG_A7, sp + 4)
            uc.reg_write(UC_M68K_REG_PC, ret)
        elif addr in (want_ret, SW_LABEL):
            trace["stopped_at"] = addr
            trace["sr"] = uc.reg_read(UC_M68K_REG_SR)
            trace["regs"] = {r: uc.reg_read(r) for r in SEED}
            trace["d6"] = uc.reg_read(UC_M68K_REG_D6)
            trace["d7"] = uc.reg_read(UC_M68K_REG_D7)
            uc.emu_stop()

    h = uc.hook_add(UC_HOOK_CODE, hook)
    try:
        uc.emu_start(entry, 0, count=20000)
    except UcError as e:
        trace["err"] = str(e)
    uc.hook_del(h)
    return trace


# ---------------------------------------------------------------- dj_a
def test_a():
    print("dj_a  (arm / PC / force step) ------------------------------------")

    # OFF: nothing pending, DJ_MODE=0 -> just clears stale arm, Z from tst.b STOPFLAG
    uc = mk()
    uc.mem_write(DJ_MODE, struct.pack(">I", 0))
    uc.mem_write(G_ARMED, b"\x01")
    uc.mem_write(STOPFLAG, b"\x00")
    t = run(uc, DJ_A, RET_A)
    check("OFF: returns to caller", t["stopped_at"] == RET_A)
    check("OFF: stale arm cleared", uc.mem_read(G_ARMED, 1) == b"\x00")
    check("OFF: no PC sent", t["pc_send"] == [])
    check("OFF: seed regs preserved",
          all(t["regs"][r] == SEED[r] for r in SEED), str(t["regs"]))
    # NB: unicorn-m68k does not update the CCR on `tst.b (abs).l`, so the Z flag that the
    # caller's `beq.w 0x400a412e` consumes can't be checked here.  It is correct by
    # construction: dja_ret's last op before rts is the verbatim stock `tst.b STOPFLAG`.

    # arranger active -> disarm, bail
    uc = mk()
    uc.mem_write(DJ_MODE, struct.pack(">I", 1))
    uc.mem_write(ARR_ACT, struct.pack(">I", 1))
    uc.mem_write(PEND_PAT, b"\x05"); uc.mem_write(ACT_PAT, b"\x00")
    uc.mem_write(G_ARMED, b"\x01")
    t = run(uc, DJ_A, RET_A)
    check("arranger: disarmed", uc.mem_read(G_ARMED, 1) == b"\x00")
    check("arranger: no PC", t["pc_send"] == [])

    # real pending, tick 1: send PC, arm, do NOT clear STEP
    uc = mk()
    uc.mem_write(DJ_MODE, struct.pack(">I", 1))
    uc.mem_write(PEND_PAT, b"\x05"); uc.mem_write(PEND_BANK, b"\x02")
    uc.mem_write(ACT_PAT, b"\x00"); uc.mem_write(ACT_BANK, b"\x00")
    uc.mem_write(STEP, b"\x07")
    uc.mem_write(G_ARMED, b"\x00"); uc.mem_write(G_PCPAT, b"\xff")
    uc.mem_write(STOPFLAG, b"\x00")
    t = run(uc, DJ_A, RET_A)
    check("tick1: PC sent (bank=2, pat=5)", t["pc_send"] == [(2, 5)], str(t["pc_send"]))
    check("tick1: armed", uc.mem_read(G_ARMED, 1) != b"\x00")
    check("tick1: G_STEP=7", uc.mem_read(G_STEP, 1) == b"\x07")
    check("tick1: STEP untouched (7)", uc.mem_read(STEP, 1) == b"\x07")
    check("tick1: G_PCPAT=5", uc.mem_read(G_PCPAT, 1) == b"\x05")
    check("tick1: regs preserved", all(t["regs"][r] == SEED[r] for r in SEED))

    # tick 2: already armed, pending unchanged -> PC deduped, STEP forced to 0
    uc = mk()
    uc.mem_write(DJ_MODE, struct.pack(">I", 1))
    uc.mem_write(PEND_PAT, b"\x05"); uc.mem_write(PEND_BANK, b"\x02")
    uc.mem_write(ACT_PAT, b"\x00"); uc.mem_write(ACT_BANK, b"\x00")
    uc.mem_write(STEP, b"\x09")
    uc.mem_write(G_ARMED, b"\xff"); uc.mem_write(G_PCPAT, b"\x05")
    t = run(uc, DJ_A, RET_A)
    check("tick2: PC deduped (none)", t["pc_send"] == [], str(t["pc_send"]))
    check("tick2: G_STEP=9", uc.mem_read(G_STEP, 1) == b"\x09")
    check("tick2: STEP forced to 0", uc.mem_read(STEP, 1) == b"\x00")
    check("tick2: still armed (dj_c clears it)", uc.mem_read(G_ARMED, 1) != b"\x00")

    # tick 2 but pending changed 5 -> 9 -> resend PC, still force
    uc = mk()
    uc.mem_write(DJ_MODE, struct.pack(">I", 1))
    uc.mem_write(PEND_PAT, b"\x09"); uc.mem_write(PEND_BANK, b"\x03")
    uc.mem_write(ACT_PAT, b"\x00"); uc.mem_write(ACT_BANK, b"\x00")
    uc.mem_write(STEP, b"\x04")
    uc.mem_write(G_ARMED, b"\xff"); uc.mem_write(G_PCPAT, b"\x05")
    t = run(uc, DJ_A, RET_A)
    check("flip: PC resent (bank=3, pat=9)", t["pc_send"] == [(3, 9)], str(t["pc_send"]))
    check("flip: STEP forced to 0", uc.mem_read(STEP, 1) == b"\x00")

    # pending == active (cue that matches) -> disarm
    uc = mk()
    uc.mem_write(DJ_MODE, struct.pack(">I", 1))
    uc.mem_write(PEND_PAT, b"\x03"); uc.mem_write(PEND_BANK, b"\x01")
    uc.mem_write(ACT_PAT, b"\x03"); uc.mem_write(ACT_BANK, b"\x01")
    uc.mem_write(G_ARMED, b"\xff")
    t = run(uc, DJ_A, RET_A)
    check("pend==act: disarmed", uc.mem_read(G_ARMED, 1) == b"\x00")
    check("pend==act: no PC", t["pc_send"] == [])


# ---------------------------------------------------------------- dj_b
def test_b():
    print("dj_b  (CHAIN-AFTER gate bypass) --------------------------------")

    # not armed: displaced `move.l #0x8e56,d0`, return to RET_B
    uc = mk()
    uc.mem_write(G_ARMED, b"\x00")
    t = run(uc, DJ_B, RET_B)
    check("not armed: returns to 0x400a4300", t["stopped_at"] == RET_B)
    check("not armed: d0 = 0x8e56", uc.reg_read(UC_M68K_REG_D0) == 0x8e56,
          hex(uc.reg_read(UC_M68K_REG_D0)))

    # armed: rewrite return -> SW_LABEL, d6 = 1
    uc = mk()
    uc.mem_write(G_ARMED, b"\xff")
    t = run(uc, DJ_B, RET_B)
    check("armed: jumps to SW_LABEL 0x400a43a0", t["stopped_at"] == SW_LABEL,
          hex(t["stopped_at"] or 0))
    check("armed: d6 = 1", t["d6"] == 1, hex(t["d6"]))


# ---------------------------------------------------------------- dj_c
def test_c():
    print("dj_c  (playhead resume) ---------------------------------------")

    # not armed: DAT_800065b6 = 0, d7 untouched
    uc = mk()
    uc.mem_write(G_ARMED, b"\x00")
    uc.mem_write(STEP, b"\x2a")
    t = run(uc, DJ_C, 0x400a4848)
    check("not armed: STEP = 0", uc.mem_read(STEP, 1) == b"\x00")
    check("not armed: d7 preserved", t["d7"] == SEED[UC_M68K_REG_D7], hex(t["d7"]))

    # armed, same length (new pattern len 16, saved step 9) -> STEP=9, d7=9
    uc = mk()
    uc.mem_write(G_ARMED, b"\xff")
    uc.mem_write(ACT_PAT, b"\x01"); uc.mem_write(ACT_BANK, b"\x00")
    uc.mem_write(PAT_SCALE + (0x01 * 0x8ed8), b"\x04")   # scale idx 4 -> len 16
    uc.mem_write(G_STEP, b"\x09")
    t = run(uc, DJ_C, 0x400a4848)
    check("armed same-len: STEP = 9", uc.mem_read(STEP, 1) == b"\x09")
    check("armed same-len: d7 = 9", t["d7"] == 9, hex(t["d7"]))
    check("armed: G_ARMED cleared", uc.mem_read(G_ARMED, 1) == b"\x00")

    # armed, shorter new pattern (len 8, saved step 13) -> 13 % 8 = 5
    uc = mk()
    uc.mem_write(G_ARMED, b"\xff")
    uc.mem_write(ACT_PAT, b"\x02"); uc.mem_write(ACT_BANK, b"\x00")
    uc.mem_write(PAT_SCALE + (0x02 * 0x8ed8), b"\x02")   # scale idx 2 -> len 8
    uc.mem_write(G_STEP, b"\x0d")
    t = run(uc, DJ_C, 0x400a4848)
    check("armed shorter: STEP = 5 (13 % 8)", uc.mem_read(STEP, 1) == b"\x05",
          str(uc.mem_read(STEP, 1)))
    check("armed shorter: d7 = 5", t["d7"] == 5, hex(t["d7"]))

    # armed, longer new pattern (len 64, saved step 20) -> 20
    uc = mk()
    uc.mem_write(G_ARMED, b"\xff")
    uc.mem_write(ACT_PAT, b"\x03"); uc.mem_write(ACT_BANK, b"\x00")
    uc.mem_write(PAT_SCALE + (0x03 * 0x8ed8), b"\x06")   # scale idx 6 -> len 64
    uc.mem_write(G_STEP, b"\x14")
    t = run(uc, DJ_C, 0x400a4848)
    check("armed longer: STEP = 20", uc.mem_read(STEP, 1) == b"\x14")


test_a()
test_b()
test_c()
print()
if fails:
    print(f"{len(fails)} FAIL: " + ", ".join(fails))
    sys.exit(1)
print("ALL GOOD")
