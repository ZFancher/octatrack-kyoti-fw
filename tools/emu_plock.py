#!/usr/bin/env python3
# SPDX-License-Identifier: MIT
# SPDX-FileCopyrightText: 2026 Zac-Kyoti
"""
Session-13 Phase 1: locate the p-lock *write* / *erase* handler by driving the
firmware in octabam's full-firmware emulator (`refs/octabam/tools/emu_rtos.py`,
via our `tools/emu_rtos.py` wrapper).

Step 1 (this file, `--confirm`): boot, mount the factory OT DEMO, and check that
the p-lock array *in RAM* is where octabam's bank-blob model + our disk RE say it
is — `PART_PTR blob + pattern*0x8ed8 + track*0x91a + 0x59` (the disk `TRAC+0x62`
array, minus the 9-byte chunk header). Cross-check against `inspect_bank.py`.

Step 2 (`--watch`): `watch_mem` that region and drive gestures (`[NO]` press +
an encoder delta; a `[TRIG]` hold + an encoder delta) to see which firmware
function writes it.

    python3 tools/emu_plock.py --confirm
    python3 tools/emu_plock.py --watch --ms 4000

Needs `python3 tools/refs/sync.py` (the octabam cache) + `unicorn>=2.1`.
"""
import argparse
import os
import pathlib
import struct
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
OCTABAM = ROOT / "refs" / "octabam"
OUR_IMAGE = ROOT / "out" / "raw" / "section_3_MAIN_OS.bin"
DEMO = pathlib.Path.home() / "Desktop" / "OT Backup" / "KYOTI" / "OT DEMO"
DEMO_BANK1 = DEMO / "bank01.work"

if not (OCTABAM / "tools" / "emu_rtos.py").exists():
    sys.exit("missing refs/octabam -> python3 tools/refs/sync.py")
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


# the MIDI-track CC-lock triplet FUN_40033e3c / FUN_400409f4 manage (RE Session 24) --
# probably NOT the audio per-step storage, watched here to prove/disprove that.
CC_VALUES, CC_BITMAP, CC_PARAMFLAG = 0x46c7bf2c, 0x46c7d7d8, 0x46c7e0de


def _hookall(rt, spans):
    """watch_mem several spans at once; returns the shared rt.mem_writes list."""
    rt.mem_writes = []

    def on_write(u, acc, a, size, val, user):
        pc = u.reg_read(er.eb.UC_M68K_REG_PC)
        rt.mem_writes.append((rt.sample, rt._cur(), pc, a, size, val))
    for lo, ln in spans:
        rt.uc.hook_add(er.eb.UC_HOOK_MEM_WRITE, on_write, begin=lo, end=lo + ln - 1)
    rt.uc.ctl_flush_tb()


def cmd_watch(rt, ms):
    tb = trac_base(rt, DISK_PAT, DISK_TRK)
    blob_lo = tb + PLOCK_IN_TRAC
    spans = [(blob_lo, PLOCK_LEN),          # the CONFIRMED audio p-lock array (blob copy)
             (CC_VALUES, 0x1000), (CC_BITMAP, 0x200), (CC_PARAMFLAG, 4)]
    print(f"\nwatch-mem  : audio array [{blob_lo:#x}+{PLOCK_LEN:#x}]  +  the "
          f"0x46c7bf2c/d7d8/e0de triplet")
    _hookall(rt, spans)

    # TODO(Session 24 NEXT): drive the real gesture --
    #   1. press_key_live(<REC-mode toggle handler>, 1)   -> GRID REC
    #   2. press_key_live(0x40060ce0, 1)  with keycode 0..15 in a1/d1  -> hold [TRIG n]
    #   3. call_as_main(<encoder handler>, args=(<a1>, <delta>))       -> turn a knob
    # For now: just run the sequencer so a plain playback shows ZERO writes (baseline).
    seq_bank, seq_pat = rt.seq_select_live(rt.uc.mem_read(er.CUR_BANK, 1)[0], DISK_PAT)
    print(f"seq select : bank {seq_bank} pattern {seq_pat}")
    rt.frame = True
    rt.next_frame = rt.sample + er.FRAME_PERIOD
    rt.exact_clock()
    rt.internal_clock()
    rt.press_play_live()
    rt.run(ms=ms)

    writes = getattr(rt, "mem_writes", [])
    print(f"\n{len(writes)} write(s) into the watched regions:")
    seen = {}
    for sample, task, pc, addr, size, val in writes:
        region = ("audio-array" if blob_lo <= addr < blob_lo + PLOCK_LEN else
                  "cc-values" if CC_VALUES <= addr < CC_VALUES + 0x1000 else
                  "cc-bitmap" if CC_BITMAP <= addr < CC_BITMAP + 0x200 else "cc-paramflag")
        seen.setdefault((pc, region), 0)
        seen[(pc, region)] += 1
    for (pc, region), n in sorted(seen.items()):
        print(f"  pc {pc:#010x}  {region:<12} x{n}")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--confirm", action="store_true", help="check the RAM p-lock address vs disk")
    ap.add_argument("--watch", action="store_true", help="watch_mem the p-lock array during play")
    ap.add_argument("--ms", type=int, default=3000)
    a = ap.parse_args()
    if not (a.confirm or a.watch):
        ap.error("pick --confirm or --watch")
    if not DEMO_BANK1.exists():
        sys.exit(f"missing {DEMO_BANK1} (the factory OT DEMO export)")

    rt = boot_and_load()
    ok = True
    if a.confirm:
        ok = cmd_confirm(rt)
    if a.watch:
        cmd_watch(rt, a.ms)
    sys.exit(0 if ok else 1)


if __name__ == "__main__":
    main()
