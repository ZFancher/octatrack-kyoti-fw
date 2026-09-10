#!/usr/bin/env python3
# SPDX-License-Identifier: MIT
# SPDX-FileCopyrightText: 2026 Zac-Kyoti
"""
Isolation-exercise the QUANTIZE LIVE REC front-panel toggle (build_qlrec.py).

  qlr_play   @ detour of 0x40061778  ([PLAY] press, keycode 0x28)
       - REC not held            -> stock ([PLAY] resumes at 0x4006177e), nothing else
       - REC held, 1st press     -> stock (stock starts LIVE REC), counter = 1
       - REC held, 2nd press     -> flip 0x800000ac + shadow 0x100fff3c, re-checksum,
                                    NOTIFY("QUANT LIVE REC ON"/"OFF", dur 0), swallow
       - REC held, 3rd press     -> swallow, no transport, no flip
       - REC held, 4th press     -> flip back, NOTIFY the other string, swallow

  qlr_recrel @ detour of 0x4004883a  ([REC] release)
       - always clears REC_HELD + the counter
       - closes our toast (NOTIFY_CLOSE) iff we opened one (G_OURS)

The full keymap dispatch is not modelled -- these are the two cave routines run
against the real assembled bytes with the OS calls stubbed to `rts`.

Run after:  python3 tools/build_qlrec.py
Usage:      python3 tools/emu_qlrec.py
"""
import pathlib, struct, subprocess, sys
from unicorn import *
from unicorn.m68k_const import *

ROOT = pathlib.Path(__file__).resolve().parent.parent
_bin = ROOT / "out/patch_qlrec.bin"
_elf = ROOT / "out/patch_qlrec.elf"
if not _bin.exists():
    sys.exit(f"missing {_bin.name} -- run: python3 tools/build_qlrec.py")
STUB = _bin.read_bytes()
LOAD = 0x400d7400
_nm = subprocess.run(["m68k-elf-nm", str(_elf)], capture_output=True, text=True).stdout
SYM = {p[2]: int(p[0], 16) for p in (l.split() for l in _nm.splitlines()) if len(p) == 3}
QLR_PLAY = SYM["qlr_play"]
QLR_RECREL = SYM["qlr_recrel"]
MSG_ON, MSG_OFF = SYM["qlr_msg_on"], SYM["qlr_msg_off"]

CKSUM = 0x4001f23c
NOTIFY = 0x4005a2b8
NOTIFY_CLOSE = 0x40056bec
PROJ_GATE = 0x4009b5c0
PLAY_RESUME = 0x4006177e
SENTINEL = 0x40200000      # synthetic return address (a swallowed key `rts`es here)

REC_HELD = 0x460d1726
QLR = 0x800000ac
QLR_SH = 0x100fff3c
G_CNT = 0x80006a5c
G_OURS = 0x80006a60

fails = []


def check(name, cond, detail=""):
    print(f"  [{'ok  ' if cond else 'FAIL'}] {name}" + (f"   ({detail})" if detail else ""))
    if not cond:
        fails.append(name)


def mk():
    uc = Uc(UC_ARCH_M68K, UC_MODE_BIG_ENDIAN)
    uc.mem_map(0x40000000, 0x400000)
    uc.mem_map(0x46000000, 0x1000000)
    uc.mem_map(0x80000000, 0x20000)
    uc.mem_map(0x41000000, 0x20000)
    uc.mem_map(0x10000000, 0x1000000)
    uc.mem_write(LOAD, STUB)
    for a in (CKSUM, NOTIFY, NOTIFY_CLOSE, PROJ_GATE):
        uc.mem_write(a, b"\x4e\x75")           # rts -- record the call, return
    uc.mem_write(PLAY_RESUME, b"\x4e\x75")     # marker for the stock-play resume
    return uc


def run_play(rec_held, cnt=0, qlr=0, sh=0xdead, ours=0):
    uc = mk()
    uc.mem_write(REC_HELD, struct.pack(">I", rec_held))
    uc.mem_write(QLR, struct.pack(">I", qlr))
    uc.mem_write(QLR_SH, struct.pack(">I", sh))
    uc.mem_write(G_CNT, struct.pack(">I", cnt))
    uc.mem_write(G_OURS, struct.pack(">B", ours))
    sp0 = 0x41010000
    uc.mem_write(sp0, struct.pack(">III", SENTINEL, 0x28, 1))   # ret, keycode, event
    uc.reg_write(UC_M68K_REG_A7, sp0)
    st = dict(cksum=False, notify=None, notify_dur=None, close=False,
              proj_gate=False, resume=False, swallowed=False)

    def hook(uc, addr, size, u):
        if addr == CKSUM:
            st["cksum"] = True
        elif addr == NOTIFY:
            sp = uc.reg_read(UC_M68K_REG_A7)
            st["notify"] = struct.unpack(">I", uc.mem_read(sp + 4, 4))[0]
            st["notify_dur"] = struct.unpack(">I", uc.mem_read(sp + 8, 4))[0]
        elif addr == NOTIFY_CLOSE:
            st["close"] = True
        elif addr == PROJ_GATE:
            st["proj_gate"] = True
        elif addr == PLAY_RESUME:
            st["resume"] = True
            uc.emu_stop()
        elif addr == SENTINEL:
            st["swallowed"] = True
            uc.emu_stop()

    h = uc.hook_add(UC_HOOK_CODE, hook)
    try:
        uc.emu_start(QLR_PLAY, 0, count=20000)
    except UcError:
        pass
    uc.hook_del(h)
    st["qlr"] = struct.unpack(">I", uc.mem_read(QLR, 4))[0]
    st["sh"] = struct.unpack(">I", uc.mem_read(QLR_SH, 4))[0]
    st["cnt"] = struct.unpack(">I", uc.mem_read(G_CNT, 4))[0]
    st["ours"] = uc.mem_read(G_OURS, 1)[0]
    return st


def run_recrel(rec_held=1, cnt=3, ours=1):
    uc = mk()
    uc.mem_write(REC_HELD, struct.pack(">I", rec_held))
    uc.mem_write(G_CNT, struct.pack(">I", cnt))
    uc.mem_write(G_OURS, struct.pack(">B", ours))
    sp0 = 0x41010000
    uc.mem_write(sp0, struct.pack(">III", SENTINEL, 0x29, 0))
    uc.reg_write(UC_M68K_REG_A7, sp0)
    st = dict(close=False)

    def hook(uc, addr, size, u):
        if addr == NOTIFY_CLOSE:
            st["close"] = True
        elif addr == SENTINEL:
            uc.emu_stop()

    h = uc.hook_add(UC_HOOK_CODE, hook)
    try:
        uc.emu_start(QLR_RECREL, 0, count=20000)
    except UcError:
        pass
    uc.hook_del(h)
    st["rec_held"] = struct.unpack(">I", uc.mem_read(REC_HELD, 4))[0]
    st["cnt"] = struct.unpack(">I", uc.mem_read(G_CNT, 4))[0]
    st["ours"] = uc.mem_read(G_OURS, 1)[0]
    return st


def test_play():
    print("qlr_play  ([REC] held + [PLAY]) ------------------------------")

    r = run_play(rec_held=0, qlr=0)
    check("REC not held -> stock resume (0x4006177e)", r["resume"] and r["proj_gate"])
    check("REC not held -> no flip / no toast", r["qlr"] == 0 and r["notify"] is None)
    check("REC not held -> counter untouched", r["cnt"] == 0)

    r = run_play(rec_held=1, cnt=0, qlr=0)
    check("1st press -> stock resume (stock starts LIVE REC)", r["resume"] and r["proj_gate"])
    check("1st press -> counter = 1", r["cnt"] == 1)
    check("1st press -> no flip / no toast", r["qlr"] == 0 and r["notify"] is None)

    r = run_play(rec_held=1, cnt=1, qlr=0, sh=0xdead)
    check("2nd press -> QLR 0 -> 1", r["qlr"] == 1)
    check("2nd press -> shadow 0x100fff3c = 1", r["sh"] == 1)
    check("2nd press -> re-checksum called", r["cksum"])
    check('2nd press -> NOTIFY("QUANT LIVE REC ON")', r["notify"] == MSG_ON,
          f"got 0x{(r['notify'] or 0):08x} want 0x{MSG_ON:08x}")
    check("2nd press -> NOTIFY dur = 0 (persistent)", r["notify_dur"] == 0, str(r["notify_dur"]))
    check("2nd press -> G_OURS set", r["ours"] == 1)
    check("2nd press -> [PLAY] swallowed (no resume, no transport gate)",
          r["swallowed"] and not r["resume"] and not r["proj_gate"])
    check("2nd press -> counter = 2", r["cnt"] == 2)

    r = run_play(rec_held=1, cnt=2, qlr=1)
    check("3rd press -> swallowed, nothing else", r["swallowed"] and not r["resume"])
    check("3rd press -> no flip / no toast", r["qlr"] == 1 and r["notify"] is None)
    check("3rd press -> counter = 3", r["cnt"] == 3)

    r = run_play(rec_held=1, cnt=3, qlr=1)
    check("4th press -> QLR 1 -> 0", r["qlr"] == 0)
    check('4th press -> NOTIFY("QUANT LIVE REC OFF")', r["notify"] == MSG_OFF,
          f"got 0x{(r['notify'] or 0):08x} want 0x{MSG_OFF:08x}")
    check("4th press -> swallowed", r["swallowed"] and not r["resume"])


def test_recrel():
    print("qlr_recrel  ([REC] release) ---------------------------------")

    r = run_recrel(rec_held=1, cnt=3, ours=1)
    check("clears REC_HELD (0x460d1726)", r["rec_held"] == 0)
    check("clears the press counter", r["cnt"] == 0)
    check("toast was ours -> NOTIFY_CLOSE called", r["close"])
    check("toast was ours -> G_OURS cleared", r["ours"] == 0)

    r = run_recrel(rec_held=1, cnt=1, ours=0)
    check("no toast of ours -> NOTIFY_CLOSE not called", not r["close"])
    check("no toast of ours -> still clears REC_HELD + counter",
          r["rec_held"] == 0 and r["cnt"] == 0)


def test_string_len():
    print("toast strings ----------------------------------------------")
    on = STUB[MSG_ON - LOAD:STUB.index(b"\0", MSG_ON - LOAD)].decode()
    off = STUB[MSG_OFF - LOAD:STUB.index(b"\0", MSG_OFF - LOAD)].decode()
    check(f'"{on}" ({len(on)})', on == "QUANT LIVE REC ON")
    check(f'"{off}" ({len(off)})', off == "QUANT LIVE REC OFF")
    # FUN_4005a2b8 sizes the window to textpx+15; "QUANT LIVE REC OFF" (18 ch)
    # is well inside 128 px.  Still worth an eyeball on HW.
    check("toast strings <= 18 chars (fit the 128 px screen)", len(on) <= 18 and len(off) <= 18)


if __name__ == "__main__":
    test_play()
    test_recrel()
    test_string_len()
    print()
    if fails:
        print(f"FAILED: {len(fails)}")
        for f in fails:
            print(f"  - {f}")
        sys.exit(1)
    print("ALL GOOD")
