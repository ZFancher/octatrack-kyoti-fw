#!/usr/bin/env python3
# SPDX-License-Identifier: MIT
# SPDX-FileCopyrightText: 2026 Zac-Kyoti
"""
Session-13 Phase 1: locate the p-lock *write* / *erase* handler by driving the
firmware in octabam's full-firmware emulator (`refs/octabam/tools/emu_rtos.py`,
via our `tools/emu_rtos.py` wrapper).

`--confirm`  — **DONE (Session 24)**: boots our image in `emu_rtos`, mounts the
factory OT DEMO, and proves the RAM p-lock array is byte-identical to disk at
`PART_PTR blob + pattern*0x8ed8 + track*0x91a + 0x59`.

`--watch`  — **in progress**: `watch_mem` the p-lock structures and drive a GRID-REC
hold-trig + knob gesture to name the writer.

  Session 27: the image loads at vaddr 0x40000400 (0x400 header) -- Session 26's
  objdump used 0x40000000 so its fn addresses were 0x400 low.  Corrected:
  `call_as_main(0x40050f20, (step, 1))` ARMS the editor (0x460d172e / 0x460d174a /
  0x460d1746).  The knob->p-lock writer 0x4004ef54 then writes EXACTLY
  `blob + pat*0x8ed8 + 0x4900 + step*0x20 + param*0x8b0 + 2` (the value) and
  `0x46c7d2e4[step] |= 1<<param`.  `--s27` proves +0x4900 is a transient live-edit
  buffer (empty for the saved DEMO; #1 TRAC+0x59 holds the locks) -> a serialise
  repacks it on save/pattern-change.

    python3 tools/emu_plock.py --confirm
    python3 tools/emu_plock.py --s27
    python3 tools/emu_plock.py --watch --trig 4 --applyknob 3 90

Needs `python3 tools/refs/sync.py` (the octabam cache) + `unicorn>=2.1`.
Each run is ~2-3 min wall (the emulated LOAD PROJECT dominates).
"""
import argparse
import os
import pathlib
import struct
import sys

sys.stdout.reconfigure(line_buffering=True)

ROOT = pathlib.Path(__file__).resolve().parent.parent
OCTABAM = ROOT / "refs" / "octabam"
OUR_IMAGE = ROOT / "out" / "raw" / "section_3_MAIN_OS.bin"
DEMO = pathlib.Path.home() / "Desktop" / "OT Backup" / "KYOTI" / "OT DEMO"
DEMO_BANK1 = DEMO / "bank01.work"

if not (OCTABAM / "tools" / "emu_rtos.py").exists():
    sys.exit("missing refs/octabam -> python3 tools/refs/sync.py")
if not (OCTABAM / ".venv" / "lib" / "unicorn-emac").is_dir():
    sys.exit("missing the EMAC-patched Unicorn -> "
             "( cd refs/octabam && PY=$(command -v python3) bash scripts/build_unicorn.sh )")
os.chdir(OCTABAM)
sys.path.insert(0, str(OCTABAM / "tools"))
import emu_rtos as er            # noqa: E402
import emu_card as ec            # noqa: E402

PATTERN_STRIDE = er.PATTERN_STRIDE      # 0x8ed8
TRAC_STRIDE = er.TRAC_STRIDE            # 0x91a
PLOCK_IN_TRAC = 0x59                    # disk TRAC+0x62 minus the 9-byte chunk header
PLOCK_LEN = 0x800                       # 64 steps x 32 bytes
HDR_IN_TRAC = 0x50                      # disk TRAC+0x59 (param header: [LEN] 02 00 FF ..)

# from tools/inspect_bank.py against the DEMO: P11 (index 10), track 2 (index 1),
# 16-step track, p-lock on record byte 0x12 ramping 0x40 0x29 0x14 0x05 0x00 on
# steps 0,2,4,6,8 and byte 0x00 = 0x4f 0x5e 0x68 on steps 10,12,14.
DISK_PAT, DISK_TRK = 10, 1


def disk_plock(pat, trk):
    """The p-lock array straight off bank01.work, for the cross-check."""
    b = DEMO_BANK1.read_bytes()
    PAT1, PSTRIDE, PHDR, TRAC = 0x16, 0x8EEC, 8, 0x922
    off = PAT1 + PSTRIDE * pat + PHDR + TRAC * trk
    return b[off + 0x59:off + 0x59 + 9], b[off + 0x62:off + 0x62 + PLOCK_LEN]


def boot_and_load():
    card, name = er.stage_project(str(DEMO), "OCTABAM", None)
    r, rt = er.attach(str(OUR_IMAGE), card, tick=True)
    print(f"boot       : {r.stopped}")
    mounted, posted, saved_bank, final_bank, elapsed = rt.load_project_live(
        "OCTABAM", name, run_ms=6000, mount_ms=3000)
    print(f"load       : mounted={mounted} posted={posted} "
          f"saved_bank={saved_bank} final_bank={final_bank} ({elapsed:.0f} ms)")
    return rt


def blob_base(rt):
    return struct.unpack(">I", rt.uc.mem_read(ec.PART_PTR, 4))[0]


def trac_base(rt, pat, trk):
    return blob_base(rt) + pat * PATTERN_STRIDE + trk * TRAC_STRIDE


# Session 27: the real grid-rec trig-press fn.
#   ⚠️ the image loads at vaddr 0x40000400 (0x400-byte header) -- Session 26's
#   fresh objdump used --adjust-vma=0x40000000 and every fn addr it derived was
#   0x400 low.  Correct chain: keymap trig keycodes -> 0x40060ce0 (unpacks
#   keycode@4/event@8; if 0x460d1736==0 -> 0x400501d8 dispatcher).  The
#   grid-rec trig dispatcher (0x40060bxx) routes event: 1(press) -> 0x40050f20,
#   2(hold) -> 0x400587d4, 0(release) -> 0x4005fb44 + 0x4003146c.
#   0x40050f20(step@4, 1@8): moveml d2-d7/a2-a5 (40 B) -> arg0 @ sp@44 = d3 = step,
#   arg1 @ sp@48 = a1.  d5 = [0x460d1e04] base step.  gate: [0x460d5db4] in {0,3}
#   to set the held bit; [0x80000012]==0 (audio) -> sets 0x460d172e=1 @ 0x4005100c,
#   0x460d174a |= 1<<step @ 0x40050fb8, 0x460d174c = basestep<<4, 0x460d1746 = that+step.
GRIDREC_TRIG_FN = 0x40050f20
BASESTEP_SRC = 0x460d1e04
GR_SUBMODE   = 0x460d5db4


def cmd_s27(rt):
    """Is blob +0x4900 (param-major, what the knob sub 0x4004eb54 writes) the
    same data as structure #1 (per-track TRAC+0x59, == disk)?  Read both for the
    DEMO's known-locked pattern and compare."""
    blob = blob_base(rt)
    pat_blk = blob + DISK_PAT * PATTERN_STRIDE
    print(f"\nblob {blob:#x}  pattern-block {pat_blk:#x}")

    tb = trac_base(rt, DISK_PAT, DISK_TRK)
    one = bytes(rt.uc.mem_read(tb + PLOCK_IN_TRAC, PLOCK_LEN))
    print(f"\n#1  TRAC+0x59 @ {tb + PLOCK_IN_TRAC:#x} (per-track, step*0x20):")
    for s in range(64):
        rec = one[s * 32:(s + 1) * 32]
        nz = [(hex(i), hex(rec[i])) for i in range(32) if rec[i] != 0xFF]
        if nz:
            print(f"    step {s:2}: {nz}")

    # +0x4900 region: 0x4900 + step*0x20 + param*0x8b0 (+2,+3).  Scan params 0..7,
    # steps 0..63, report bytes != 0xFF at rec offsets 0..5.
    print(f"\n+0x4900 region @ {pat_blk + 0x4900:#x} (param-major, param*0x8b0 + step*0x20):")
    found = False
    for p in range(8):
        for s in range(64):
            base = pat_blk + 0x4900 + p * 0x8b0 + s * 0x20
            rec = bytes(rt.uc.mem_read(base, 8))
            nz = [(i, rec[i]) for i in range(8) if rec[i] != 0xFF]
            if nz:
                found = True
                print(f"    param {p} step {s:2} @ +{base - pat_blk:#x}: "
                      f"{[(hex(i), hex(v)) for i, v in nz]}")
    if not found:
        print("    (all 0xFF -- nothing stored here for this pattern)")

    print(f"\n0x46c7d2e4 bitmap: {rt.uc.mem_read(PLK_BITMAP, 0x40).hex(' ')}")

    # now try the REAL grid-rec trig-press fn and see if the gates arm.
    print(f"\n--- calling GRIDREC_TRIG_FN {GRIDREC_TRIG_FN:#x}(step=4, 0) ---")
    print(f"  before: 0x460d5db4={int.from_bytes(rt.uc.mem_read(GR_SUBMODE, 4), 'big'):#x}  "
          f"0x80000012={int.from_bytes(rt.uc.mem_read(0x80000012, 4), 'big'):#x}  "
          f"0x460d1e04={int.from_bytes(rt.uc.mem_read(BASESTEP_SRC, 4), 'big'):#x}")
    rt.run(until=lambda r: r.pc == er.MAIN_SPIN)
    try:
        d0 = rt.call_as_main(GRIDREC_TRIG_FN, args=(4, 0), budget=400_000)
        print(f"  returned d0={d0:#x}")
    except Exception as e:
        print(f"  {type(e).__name__}: {e}")
    for a, nm in ((PLK_ARMED, "0x460d172e armed"), (PLK_HELD, "0x460d174a held"),
                  (PLK_BASESTEP, "0x460d174c base"), (0x460d1746, "0x460d1746 rec-ptr")):
        print(f"  {nm:22} = {int.from_bytes(rt.uc.mem_read(a, 4), 'big'):#x}")
    return True


def cmd_confirm(rt):
    blob = blob_base(rt)
    print(f"\nPART_PTR   : blob @ {blob:#x}")
    if not blob:
        sys.exit("PART_PTR is null -- the load did not populate the bank blob")

    d_hdr, d_plock = disk_plock(DISK_PAT, DISK_TRK)
    tb = trac_base(rt, DISK_PAT, DISK_TRK)
    r_hdr = bytes(rt.uc.mem_read(tb + HDR_IN_TRAC, 9))
    r_plock = bytes(rt.uc.mem_read(tb + PLOCK_IN_TRAC, PLOCK_LEN))

    print(f"P{DISK_PAT+1} t{DISK_TRK+1}  TRAC record @ {tb:#x}")
    print(f"  param hdr   disk {d_hdr.hex(' ')}")
    print(f"              RAM  {r_hdr.hex(' ')}   {'MATCH' if r_hdr == d_hdr else 'DIFF'}")

    def locked_steps(buf):
        out = []
        for s in range(64):
            rec = buf[s * 32:(s + 1) * 32]
            nz = [(i, rec[i]) for i in range(32) if rec[i] != 0xFF]
            if nz:
                out.append((s, nz))
        return out

    ds, rs = locked_steps(d_plock), locked_steps(r_plock)
    print(f"  disk locked steps: {[(s, [(hex(i), hex(v)) for i, v in nz]) for s, nz in ds][:6]}")
    print(f"  RAM  locked steps: {[(s, [(hex(i), hex(v)) for i, v in nz]) for s, nz in rs][:6]}")
    same = r_plock == d_plock
    print(f"\n  RAM p-lock array {'== disk (EXACT)' if same else 'differs from disk'}")
    if not same and ds == rs:
        print("  (locked-step *positions/values* match; only the 0xFF fill or padding differs)")
    return same or ds == rs


# --- the p-lock RAM structures (RE Session 24) --------------------------------
# The one that matters for the auto-remove feature is #1: the blob TRAC record's
# +0x59 array (--confirm proved RAM == disk). The others are downstream copies.
#
#   #2 the sequencer's LIVE per-track working set:
SEQ_VALUES  = 0x46c7ab30      # [track*32 + param] locked value
SEQ_SECOND  = 0x46c76ac0      # [track*32 + param] companion byte-array
SEQ_BITMAP  = 0x46c75fa0      # [track*4] locked-bitmap longs
#   #3 the MIDI-track CC-lock SEND queue (FUN_40033e3c writes, FUN_400409f4 -> MIDI CC):
CC_VALUES, CC_BITMAP, CC_PARAMFLAG = 0x46c7bf2c, 0x46c7d7d8, 0x46c7e0de
#
# ⚠️ the exact byte-sizes of #2/#3 are not pinned; watch NARROW windows so the
# hook doesn't catch neighbouring structures (LED buffers at 0x46c7cxxx etc).
SEQ_SPAN, CC_SPAN = 0x400, 0x400


def _hookall(rt, spans):
    """watch_mem several spans at once; returns the shared rt.mem_writes list."""
    rt.mem_writes = []

    def on_write(u, acc, a, size, val, user):
        pc = u.reg_read(er.eb.UC_M68K_REG_PC)
        rt.mem_writes.append((rt.sample, rt._cur(), pc, a, size, val))
    for lo, ln in spans:
        rt.uc.hook_add(er.eb.UC_HOOK_MEM_WRITE, on_write, begin=lo, end=lo + ln - 1)
    rt.uc.ctl_flush_tb()


# ⚠️ Session 26: these two are from the UNRELIABLE 0x400d2d54 jump table.
# 0x4000a274 disassembles to `bras 0x4000a2ca` -> a 3-byte MIDI-TX helper, NOT
# grid-rec.  call_as_main(0x40060ce0,(kc,1)) sets [0x8000003f+trk] + a held-list
# byte but does NOT arm the p-lock editor (0x460d172e/174a/174c stay 0) and does
# not clear on release.  The REAL grid-rec trig handler is the cluster
# 0x4005f260..0x4006071a (see the notes by APPLY_KNOB_SUB below).
KEY_REC = 0x4000a274
TRIG_HANDLER = 0x40060ce0                 # keymap codes 0x00..0x0f
ENCODER_HANDLER = 0x4004eb24              # the full encoder handler (redraws -> hangs under call_as_main)
FUN_40033e3c = 0x40033e3c                 # (track@16, 0x2e@20, value@24) -- what 0x4004eb24 calls at +knob
SCENE_VALUES = 0x46c7aa24                 # scene p-lock storage (step handler's d2==-1 case)
MODE_04A = 0x8000004a                     # "what does a knob turn do" bitfield

# Session 27: the [TRIG]-hold + knob "apply to all held steps" sub.
#   0x4004ef54 = fn start (Session 26 called it 0x4004eb54 -- 0x400 low).
#   args after `lea sp@(-44),sp; moveml d2-d7/a2-fp`:  arg0->d7  arg1->fp  arg2->a2
#   d7  = param index (1<<d7, 2224*d7)      fp = value to match (-1 = any unlocked)
#   a2  = new value (clamped 0..127)
# guards: 0x4002ea84()==0, 0x460e7424==0, [0x400bcd14]@8==0, 0x460e5e4c==0,
#         0x460d172e != 0 (armed), loop over 0x460d174a (held-step bits @0x4004efa0)
# inner value write gated on 0x800000cc (EXT LEN GRID-REC PERSONALIZE) and slot==0xFF.
# writes  [0x46c82456] + [0x100b14d0]*0x8ed8 + 0x4900 + step*0x20 + param*0x8b0  (+2)
#     mirror 0x1001aa50[...] and bitmap  0x46c7d2e4[step] |= 1<<param  (a5 @0x4004efb8)
# --s27 (Session 27) proved blob +0x4900 is ALL 0xFF for the saved DEMO while #1
# (TRAC+0x59) holds the locks -> +0x4900 is a transient live-edit buffer; a
# serialise step must repack it into the per-track TRAC arrays.
APPLY_KNOB_SUB = 0x4004ef54
PLK_ARMED   = 0x460d172e
PLK_HELD    = 0x460d174a          # 16-bit held-step bitmap the sub loops over
PLK_BASESTEP = 0x460d174c
PLK_BITMAP  = 0x46c7d2e4          # per-step |= 1<<param bitmap the sub maintains
CUR_PAT_MIRROR = 0x100b14d0       # GK_STOCK_CURRENT_PATTERN_PRIMARY
EXT_LEN_GRIDREC = 0x800000cc


def poke_seq_ws(rt, trk, param, value):
    """Hand-edit the sequencer's live working set #2 for one track/param, the
    way the encoder handler's bit-0 branch would after a knob turn: value byte,
    companion byte, and the per-track locked bitmap long. Returns the prior
    (v, second, bitmap) so the caller can show the delta."""
    va = SEQ_VALUES + trk * 32 + param
    sa = SEQ_SECOND + trk * 32 + param
    ba = SEQ_BITMAP + trk * 4
    old = (rt.uc.mem_read(va, 1)[0],
           rt.uc.mem_read(sa, 1)[0],
           struct.unpack(">I", rt.uc.mem_read(ba, 4))[0])
    rt.uc.mem_write(va, bytes([value & 0xff]))
    rt.uc.mem_write(sa, bytes([value & 0xff]))
    rt.uc.mem_write(ba, struct.pack(">I", old[2] | (1 << param)))
    return old


def cmd_watch(rt, ms, do_rec, trig, knob, param, play, call3e3c, release, applyknob):
    tb = trac_base(rt, DISK_PAT, DISK_TRK)
    blob_lo = tb + PLOCK_IN_TRAC
    pat_blk = blob_base(rt) + DISK_PAT * PATTERN_STRIDE
    # NARROW: just the p-lock structures themselves + the mode byte (a wide hook makes
    # every gesture step unusably slow).
    spans = [(blob_lo, PLOCK_LEN),                    # #1  THE target: the blob p-lock array
             (SEQ_VALUES, SEQ_SPAN), (SEQ_SECOND, SEQ_SPAN), (SEQ_BITMAP, 0x40),   # #2 live set
             (SCENE_VALUES, SEQ_SPAN),                # #3 scene storage
             (CC_VALUES, CC_SPAN), (CC_BITMAP, 0x40), (CC_PARAMFLAG, 4),           # #4 MIDI CC-lock
             (MODE_04A, 1)]
    if applyknob is not None:
        # whole pattern block + the 0x4900-region bitmap: catch a write anywhere.
        spans += [(pat_blk, PATTERN_STRIDE), (PLK_BITMAP, 0x100)]
    print(f"\nwatch      : blob TRAC [{tb:#x}]  pattern-block [{pat_blk:#x}]  "
          f"+  seq working copy  +  cc triplet  +  0x8000004a")
    _hookall(rt, spans)

    seq_bank, seq_pat = rt.seq_select_live(rt.uc.mem_read(er.CUR_BANK, 1)[0], DISK_PAT)
    print(f"seq select : bank {seq_bank} pattern {seq_pat}")

    import time as _t

    def spin(cap=300_000):
        n = 0
        while rt.pc != er.MAIN_SPIN and n < cap:
            rt.step(); n += 1

    def drive(name, addr, args, budget=250_000):
        spin()
        n0 = len(rt.mem_writes)
        t0 = _t.time()
        tag = f"{name}"
        try:
            d0 = rt.call_as_main(addr, args=args, budget=budget)
            print(f"{name:11}: {addr:#x}{args} -> d0={d0:#x}  (+{len(rt.mem_writes)-n0} writes, {_t.time()-t0:.0f}s)")
        except Exception as e:
            print(f"{name:11}: {addr:#x}{args} raised {type(e).__name__}: {e}  ({_t.time()-t0:.0f}s)")
        for w in rt.mem_writes[n0:]:
            writes_by_pc.setdefault((tag, w[2]), [0, set(), set()])
            e = writes_by_pc[(tag, w[2])]
            e[0] += 1; e[1].add(w[3]); e[2].add(w[5])

    writes_by_pc = {}

    print(f"mode       : 0x8000004a = {rt.uc.mem_read(MODE_04A, 1)[0]:#04x}  "
          f"0x46c7dd26 = {struct.unpack('>I', rt.uc.mem_read(0x46c7dd26, 4))[0]:#x}")
    if do_rec:
        drive("grid rec", KEY_REC, (0,))
        print(f"             -> mode {rt.uc.mem_read(MODE_04A, 1)[0]:#04x}  "
              f"rec-arm 0x800066a0={rt.uc.mem_read(0x800066a0, 1)[0]}")
    if trig is not None:
        drive("trig hold", TRIG_HANDLER, (trig, 1))
        print(f"             -> mode {rt.uc.mem_read(MODE_04A, 1)[0]:#04x}")
        held = rt.uc.mem_read(0x8000003f + DISK_TRK, 1)[0]
        heldlist = rt.uc.mem_read(0x46c76de0, 16).hex(" ")
        print(f"             -> [0x8000003f+{DISK_TRK}]={held:#x}  held-list={heldlist}")
    if release:
        SENT = 0x2A
        blob_step = trac_base(rt, DISK_PAT, DISK_TRK) + PLOCK_IN_TRAC + trig * 32
        before_blob = bytes(rt.uc.mem_read(blob_step, 32))
        old = poke_seq_ws(rt, DISK_TRK, param, SENT)
        print(f"poke #2    : SEQ[{DISK_TRK}*32+{param}] {old[0]:#x}->{SENT:#x}  "
              f"second {old[1]:#x}->{SENT:#x}  bitmap {old[2]:#010x}->{old[2] | (1 << param):#010x}")
        print(f"blob step{trig} before: {before_blob.hex(' ')}")
        drive("trig release", TRIG_HANDLER, (trig, 0))
        after_blob = bytes(rt.uc.mem_read(blob_step, 32))
        print(f"blob step{trig} after : {after_blob.hex(' ')}   "
              f"{'CHANGED' if after_blob != before_blob else 'unchanged'}")
    if play:
        rt.frame = True
        rt.next_frame = rt.sample + er.FRAME_PERIOD
        rt.exact_clock(); rt.internal_clock(); rt.press_play_live()
        rt.run(ms=ms)
    if call3e3c is not None:
        drive(f"3e3c t{call3e3c}", FUN_40033e3c, (call3e3c, 0x2e, 100), budget=400_000)
    if knob:
        drive(f"knob p{param}", ENCODER_HANDLER, (param, knob), budget=400_000)
    if applyknob is not None:
        p, v = applyknob
        for a, nm in ((PLK_ARMED, "0x460d172e armed"), (PLK_HELD, "0x460d174a held-bits"),
                      (PLK_BASESTEP, "0x460d174c basestep"), (CUR_PAT_MIRROR, "0x100b14d0 cur-pat"),
                      (EXT_LEN_GRIDREC, "0x800000cc EXT-LEN-GRIDREC"),
                      (0x460e7424, "0x460e7424"), (0x460e5e4c, "0x460e5e4c")):
            sz = 4 if "held-bits" not in nm else 2
            val = int.from_bytes(rt.uc.mem_read(a, sz), "big")
            print(f"  guard {nm:28} = {val:#x}")
        # Session 27: arm the p-lock editor the REAL way -- call the grid-rec
        # trig-press fn 0x40050f20(step, 1) for the held step (proved to set
        # 0x460d172e / 0x460d174a / 0x460d1746 cleanly).  Then force EXT-LEN so
        # the knob sub's inner value write is reached, and call it.
        step = trig
        drive(f"arm step{step}", GRIDREC_TRIG_FN, (step, 1))
        held = int.from_bytes(rt.uc.mem_read(PLK_HELD, 2), "big")
        print(f"  armed: 0x460d172e={rt.uc.mem_read(PLK_ARMED,4).hex()}  "
              f"0x460d174a(u16)={held:#x}  0x460d1746={int.from_bytes(rt.uc.mem_read(0x460d1746,4),'big'):#x}")
        rt.uc.mem_write(EXT_LEN_GRIDREC, b"\x01")
        drive(f"applyknob p{p}", APPLY_KNOB_SUB, (p, 0xFFFFFFFF, v), budget=1_500_000)

    def which(addr):
        off = addr - blob_lo
        if 0 <= off < PLOCK_LEN:
            return f"blob-PLOCK  step{off//32} rec+{off%32:#04x}"
        po = addr - pat_blk
        if 0 <= po < PATTERN_STRIDE:
            trk = po // TRAC_STRIDE
            return f"pat-blk+{po:#x} (trk{trk} +{po - trk*TRAC_STRIDE:#x})"
        if PLK_BITMAP <= addr < PLK_BITMAP + 0x100:
            return f"PLK_BITMAP 0x46c7d2e4 +{addr-PLK_BITMAP:#x}"
        if tb <= addr < tb + TRAC_STRIDE:
            return f"blob-TRAC+{addr-tb:#x}"
        if addr == MODE_04A:
            return "mode 0x8000004a"
        for base, ln, nm in ((SEQ_VALUES, SEQ_SPAN, "SEQ_VALUES 0x46c7ab30"),
                             (SEQ_SECOND, SEQ_SPAN, "SEQ_SECOND 0x46c76ac0"),
                             (SEQ_BITMAP, 0x40, "SEQ_BITMAP 0x46c75fa0"),
                             (SCENE_VALUES, SEQ_SPAN, "SCENE_VALUES 0x46c7aa24"),
                             (CC_VALUES, CC_SPAN, "CC_VALUES 0x46c7bf2c"),
                             (CC_BITMAP, 0x40, "CC_BITMAP 0x46c7d7d8"),
                             (CC_PARAMFLAG, 4, "CC_PARAMFLAG")):
            if base <= addr < base + ln:
                return f"{nm}+{addr-base:#x}"
        return f"?{addr:#x}"

    print(f"\n=== writes into the p-lock structures, by gesture + PC ===")
    for (tag, pc), (n, addrs, vals) in sorted(writes_by_pc.items()):
        regions = sorted({which(a).split()[0] for a in addrs})
        detail = sorted({which(a) for a in addrs})[:4]
        print(f"  [{tag:9}] pc {pc:#010x}  x{n:<4} {','.join(regions):<26} "
              f"vals={sorted(v for v in vals)[:6]}")
        for d in detail:
            print(f"                {d}")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--confirm", action="store_true", help="check the RAM p-lock address vs disk")
    ap.add_argument("--s27", action="store_true",
                    help="Session 27: compare blob +0x4900 vs structure #1, and try the real "
                         "grid-rec trig-press fn 0x40050b20")
    ap.add_argument("--watch", action="store_true", help="watch the p-lock band while driving a gesture")
    ap.add_argument("--ms", type=int, default=1500)
    ap.add_argument("--rec", action="store_true", help="press [REC] (GRID REC) before the knob")
    ap.add_argument("--trig", type=int, default=None, help="hold [TRIG n] (0-15) before the knob")
    ap.add_argument("--knob", type=int, default=0, help="encoder delta to apply (e.g. 5 or -5)")
    ap.add_argument("--param", type=int, default=0, help="which encoder (0-5) the --knob turns")
    ap.add_argument("--call3e3c", type=int, default=None, metavar="TRACK", help="call FUN_40033e3c(track,0x2e,100) directly")
    ap.add_argument("--play", action="store_true", help="start the transport before the knob")
    ap.add_argument("--release", action="store_true",
                    help="after --trig hold: poke a sentinel into working set #2 for --param, "
                         "then release the trig (TRIG_HANDLER(kc,0)) and watch #1 for the commit")
    ap.add_argument("--applyknob", nargs=2, type=int, metavar=("PARAM", "VALUE"),
                    help="after --rec + --trig hold: call the 'apply to held steps' sub "
                         "0x4004eb54(PARAM, -1, VALUE) and watch the whole pattern block")
    a = ap.parse_args()
    if not (a.confirm or a.watch or a.s27):
        ap.error("pick --confirm, --watch or --s27")
    if not DEMO_BANK1.exists():
        sys.exit(f"missing {DEMO_BANK1} (the factory OT DEMO export)")

    rt = boot_and_load()
    ok = True
    if a.confirm:
        ok = cmd_confirm(rt)
    if a.s27:
        ok = cmd_s27(rt)
    if a.watch:
        cmd_watch(rt, a.ms, a.rec, a.trig, a.knob, a.param, a.play, a.call3e3c, a.release,
                  tuple(a.applyknob) if a.applyknob else None)
    sys.exit(0 if ok else 1)


if __name__ == "__main__":
    main()
