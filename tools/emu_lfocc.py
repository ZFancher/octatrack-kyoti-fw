#!/usr/bin/env python3
# SPDX-License-Identifier: MIT
# SPDX-FileCopyrightText: 2026 Zachary Fancher ("Kyoti")
# emu_lfocc.py -- Session 8. Reproduce / locate the "MIDI LFO SETUP knob transmits CC 28-33
# on the twin audio track's channel" bug (Elektronauts 87588) on OS 1.40C.
#
# Strategy: drive the generic param-edit apply FUN_40054cd8(track, paramIdx, delta) and the
# fine editor FUN_40055008(enc, delta) directly, with the state that the LFO SETUP page sets
# up, in BOTH audio mode and MIDI mode. Log every call to:
#   FUN_40033e3c(track, ccNum, val)   -- the AUDIO-CC-OUT enqueue (channel = audio-track chan)
#   FUN_4009eec8(midiSlot, param, val, flag) -- the MIDI-track param->CC path
#   FUN_40010bc8(nbytes, ptr)          -- raw MIDI byte out
# plus a shallow jsr/bsr call trace.
#
# Usage: <venv>/bin/python tools/emu_lfocc.py
import struct, pathlib, sys
from unicorn import *
from unicorn.m68k_const import *

HERE = pathlib.Path(__file__).parent
IMG = (HERE.parent / "out/raw/section_3_MAIN_OS.bin")
if not IMG.exists(): IMG = HERE / "section_3_MAIN_OS.bin"
IMG = IMG.read_bytes()
BASE = 0x40000400

F_54cd8  = 0x40054cd8
F_55008  = 0x40055008
F_33e3c  = 0x40033e3c   # audio CC-out enqueue
F_9eec8  = 0x4009eec8   # midi param -> cc
F_10bc8  = 0x40010bc8   # midi byte out
F_9da20  = 0x4009da20   # LFO recompute (stub)
F_31f28  = 0x40031f28   # param def (min/max) -> return a buffer
F_a6994  = 0x400a6994   # returns enable bits in D1
F_a6904  = 0x400a6904
F_27e00  = 0x40027e00
F_27e30  = 0x40027e30
F_27de4  = 0x40027de4
F_4d948  = 0x4004d948
F_44920  = 0x40044920
F_7e998  = 0x4007e998
F_42158  = 0x40042158
F_40e14  = 0x40040e14
F_2ea84  = 0x4002ea84
F_6dbcc  = 0x4006dbcc
F_7c418  = 0x4007c418

PROJ   = 0x50000000
DEFBUF = 0x50300000
STACK  = 0x41010000
RET    = 0x401f0000

# audio-track MIDI channel table @ 0x8000003f (indexed by track); 0x40171442 = midi-track chan
CHAN_AUDIO = 0x8000003f
NAMES = {F_54cd8:"FUN_40054cd8", F_55008:"FUN_40055008", F_33e3c:"FUN_40033e3c(CCq)",
         F_9eec8:"FUN_4009eec8(midiCC)", F_10bc8:"FUN_40010bc8(MIDIout)",
         F_9da20:"FUN_4009da20", F_42158:"FUN_40042158(plock)"}

def mk():
    uc = Uc(UC_ARCH_M68K, UC_MODE_BIG_ENDIAN)
    uc.mem_map(0x40000000, 0x400000); uc.mem_write(BASE, IMG[:0x400000-0x400])
    uc.mem_map(0x41000000, 0x20000)
    uc.mem_map(0x50000000, 0x400000)
    uc.mem_map(0x80000000, 0x40000)
    uc.mem_map(0x46c70000, 0x40000)
    uc.mem_map(0x100a0000, 0x80000)
    uc.mem_map(0x10160000, 0x20000)
    uc.mem_map(0xfc040000, 0x10000)
    uc.mem_map(0x460d0000, 0x10000)
    uc.mem_map(0x400b0000, 0x20000)   # overlaps? no, image is 0x40000000+0x400000=0x40400000; 0x400b0000<that. skip
    return uc

def run(mode, track, paramidx, page1684, cc_out_on, entry, enc=0, delta=5, trace=True):
    uc = Uc(UC_ARCH_M68K, UC_MODE_BIG_ENDIAN)
    uc.mem_map(0x40000000, 0x400000); uc.mem_write(BASE, IMG[:0x400000-0x400])
    for a,sz in [(0x41000000,0x20000),(0x50000000,0x400000),(0x80000000,0x40000),
                 (0x46c70000,0x60000),(0x100a0000,0x80000),(0x10160000,0x20000),
                 (0xfc040000,0x10000),(0x460d0000,0x10000)]:
        uc.mem_map(a, sz)
    uc.hook_add(UC_HOOK_MEM_UNMAPPED, lambda uc,a,ad,s,v,u:(uc.mem_map(ad&~0xFFF,0x1000), True)[1])

    uc.mem_write(0x46c82456, struct.pack(">I", PROJ))
    uc.mem_write(PROJ, bytes(0x100000))

    # globals
    uc.mem_write(0x80000012, bytes([0 if mode=="audio" else 1]))   # _DAT_80000012
    uc.mem_write(0x100b14cc, bytes([track & 0xff]))                # current track (0-7)
    uc.mem_write(0x100b14cf, bytes([0]))                           # displayed pattern
    uc.mem_write(0x80000000, bytes([track & 0xff]))                # active track
    uc.mem_write(0x80000002, bytes([0]))                           # active part
    uc.mem_write(0x80000003, bytes([1]))                           # sounding pattern (nonzero!)
    uc.mem_write(0x80000004, bytes([0]))
    uc.mem_write(0x460d1684, struct.pack(">I", page1684 & 0xffffffff))  # _DAT_460d1684 (param block)
    uc.mem_write(0x460d1a50, bytes([0]))
    uc.mem_write(0x460d172a, bytes([0])); uc.mem_write(0x460d172e, bytes([0]))
    uc.mem_write(0x460d169c, struct.pack(">I",0))
    # CC-out enable: DAT_8000004a bit1 (audio cc out), bit0 relative-mode off
    uc.mem_write(0x8000004a, bytes([0x02 if cc_out_on else 0x00]))
    uc.mem_write(0x8000004c, bytes([0x03]))
    # audio-track channels: track N -> channel (N+1)+8  (repro: midi t1=ch1, audio t1=ch9)
    for t in range(8):
        uc.mem_write(CHAN_AUDIO + t, bytes([(t + 9) & 0x7f]))       # audio tracks on ch 9..16
    # midi-track channels @ per-track struct 0x40171442 (RAM copy) and PROJ blob
    uc.mem_write(0x40171442, bytes([1]))  # midi track 0 -> channel 1
    # midi-track CC-assignment cache @ 0x40171456.. (10 bytes) -> 0xff (unassigned)
    uc.mem_write(0x40171456, bytes([0xff]*10))
    # MIDI-track live state table @ 0x46c76de0 stride 0x44, first byte 0 => channel unset
    for i in range(8):
        uc.mem_write(0x46c76de0 + i*0x44, bytes([0]))
    # DEFBUF: param def buffer (min @ +0x6a[k], range @ +0x9a[k], enables @ +0x18a/+0x18e)
    uc.mem_write(DEFBUF, bytes(0x400))
    for k in range(12):
        uc.mem_write(DEFBUF + 0x6a + k*4, struct.pack(">i", 0))     # min 0
        uc.mem_write(DEFBUF + 0x9a + k*4, struct.pack(">i", 128))   # range 128
        uc.mem_write(DEFBUF + 0x12a + k*4, struct.pack(">I", 0))    # no custom fn

    calls = []
    depth = {"d": 0}
    STUB_RET = {F_31f28: DEFBUF, F_2ea84:0, F_6dbcc:0, F_7c418:0,
                F_27e00:0, F_27e30:0, F_27de4:0, F_9da20:0, F_4d948:0, F_44920:0,
                F_7e998:0, F_40e14:0, F_a6994:1, F_a6904:1, 0x4003240c:64,
                0x40033968:0, 0x4004d780:0, 0x40044920:0}
    LOG = {F_33e3c, F_9eec8, F_10bc8, F_42158}
    jtrace = []
    def rd_args(n):
        sp = uc.reg_read(UC_M68K_REG_A7)
        return [struct.unpack(">i", uc.mem_read(sp + 4 + 4*i, 4))[0] for i in range(n)]
    fnstarts = {F_54cd8:"54cd8", F_55008:"55008", F_33e3c:"33e3c", F_9eec8:"9eec8",
                F_10bc8:"10bc8", F_9da20:"9da20", F_42158:"42158", F_31f28:"31f28",
                0x40031da4:"31da4", F_a6994:"a6994", F_a6904:"a6904", 0x4009e9a8:"9e9a8",
                0x400a14f0:"a14f0", 0x40052ae8:"52ae8", 0x40033968:"33968",
                0x4003240c:"3240c", 0x4004d780:"4d780", 0x40044920:"44920"}
    last_pc = {"v": 0}
    def hook_code(uc, addr, size, user):
        if trace and addr in fnstarts:
            jtrace.append(fnstarts[addr])
        if addr == RET:
            jtrace.append("<<ret from 0x%08x" % last_pc["v"])
        last_pc["v"] = addr
        if addr in LOG:
            a = rd_args(4)
            d0 = uc.reg_read(UC_M68K_REG_D0); d1 = uc.reg_read(UC_M68K_REG_D1)
            entrymsg = ""
            if addr == F_10bc8:
                ptr = a[1]
                try: mb = uc.mem_read(ptr & 0xffffffff, max(1, a[0] & 7))
                except: mb = b""
                entrymsg = "bytes=" + mb.hex()
            calls.append((NAMES.get(addr, hex(addr)), a, entrymsg))
        if addr in STUB_RET:
            sp = uc.reg_read(UC_M68K_REG_A7)
            ret = struct.unpack(">I", uc.mem_read(sp, 4))[0]
            uc.reg_write(UC_M68K_REG_A7, sp + 4)
            uc.reg_write(UC_M68K_REG_PC, ret)
            uc.reg_write(UC_M68K_REG_D0, STUB_RET[addr] & 0xffffffff)
            uc.reg_write(UC_M68K_REG_D1, 0x0b)   # enable bits set (bit0/bit1/bit3)
    uc.hook_add(UC_HOOK_CODE, hook_code)

    midi_out = []
    def on_w(uc, access, ad, sz, val, u):
        if 0x46c7bf2c <= ad < 0x46c7bf2c + 8*0x80:
            ch = (ad - 0x46c7bf2c) // 0x80; ccn = (ad - 0x46c7bf2c) % 0x80
            midi_out.append(("CCq", ch, ccn, val & 0xff))
    uc.hook_add(UC_HOOK_MEM_WRITE, on_w)

    sp = STACK
    tk = (track + 8) if mode != "audio" else track
    args = [enc, delta] if entry == F_55008 else [tk, paramidx, delta]
    for a in reversed(args):
        sp -= 4; uc.mem_write(sp, struct.pack(">I", a & 0xffffffff))
    sp -= 4; uc.mem_write(sp, struct.pack(">I", RET)); uc.reg_write(UC_M68K_REG_A7, sp)
    err = None
    try:
        uc.emu_start(entry, RET, count=400000)
    except UcError as e:
        err = "%s @ PC=0x%08x" % (e, uc.reg_read(UC_M68K_REG_PC))
    return calls, midi_out, err, jtrace

def show(tag, entry, **kw):
    calls, mo, err, jt = run(entry=entry, **kw)
    print(f"\n--- {tag}")
    print("    trace:", " -> ".join(jt) if jt else "(none)")
    if err: print("    [UcError]", err)
    for nm, a, extra in calls:
        print(f"    CALL {nm}{tuple(a)}  {extra}")
    for row in mo:
        print(f"    >> queued CC: channel_slot={row[1]}  cc#={row[2]} (dec {row[2]})  value={row[3]}")
    if not calls and not mo:
        print("    (no CC-relevant calls)")

if __name__ == "__main__":
    for blk in range(5):
        for md in ("audio", "midi"):
            for e in (0, 3, 4, 5):
                show(f"{md}: FUN_40055008 enc={e} _DAT_460d1684={blk}", F_55008, mode=md,
                     track=0, paramidx=0, page1684=blk, cc_out_on=True, enc=e, delta=5)
    print("\n\n=================== FUN_40054cd8 (case '?' path) ===================")
    for md in ("audio", "midi"):
        for pid in range(0, 30):
            show(f"{md}: FUN_40054cd8 paramIdx={pid}", F_54cd8, mode=md, track=0,
                 paramidx=pid, page1684=1, cc_out_on=True, delta=5)
