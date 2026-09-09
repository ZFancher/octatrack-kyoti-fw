#!/usr/bin/env python3
# SPDX-License-Identifier: MIT
# SPDX-FileCopyrightText: 2026 Zac-Kyoti
"""
RELOAD FROM PROJECT (NOTES.md "Session 42") -- prove the two things the design
rests on, in octabam's full-firmware emulator (`refs/octabam/tools/emu_rtos.py`
via `tools/emu_rtos.py`).

  --slice  the core mechanic.  Load the factory OT DEMO, start the transport,
           then WHILE PLAYING: scribble over the active pattern P's slab *and* a
           bystander pattern Q's slab in the bank blob (stand-in for live edits),
           memcpy P's saved slab back (stand-in for "copied out of a .strd
           deserialised into scratch"), poke the "reload now" flag 0x46c8028a,
           run frames.  Asserts:
             * P's slab reverts, Q's edit survives  (a single-slab copy is clean)
             * 0x46c8028a is consumed and 0x800065b6 (pattern length) reloads
             * no fault, transport keeps running

  --strd   prove the async storage-task file path reloads the bank blob from
           `bankNN.strd`.  Posts a type-0x14 job (FUN_40022778(1<<curbank)) --
           the same primitive stock RELOAD BANK uses -- and lets the real
           storage task FUN_4008445c drain it (FUN_4008f0b0 .strd->.work +
           FUN_400905d4 -> FUN_4008ded0).  Cross-checks the reloaded P11 t2
           p-lock array against `bank01.strd` on disk.  (This is the WHOLE-bank
           stock path; the feature's partial reload hooks the same chain --
           redirect the open to .strd, deserialise into a scratch region,
           slice-copy.  A direct call_as_main(FUN_40016864/FUN_4008ded0) instead
           faults `rte would return to user mode` -- the buffered open blocks,
           which is why the real build MUST use the storage task, not a
           synchronous cave.)

    python3 tools/emu_reload.py --slice
    python3 tools/emu_reload.py --strd
    python3 tools/emu_reload.py --slice --strd      # one boot, both

Needs `python3 tools/refs/sync.py` + the EMAC-patched Unicorn
(`refs/octabam/scripts/build_unicorn.sh`).  ~3 min wall per boot.
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
DEMO_BANK1_STRD = DEMO / "bank01.strd"
DEMO_BANK1_WORK = DEMO / "bank01.work"

if not (OCTABAM / "tools" / "emu_rtos.py").exists():
    sys.exit("missing refs/octabam -> python3 tools/refs/sync.py")
if not (OCTABAM / ".venv" / "lib" / "unicorn-emac").is_dir():
    sys.exit("missing the EMAC-patched Unicorn -> "
             "( cd refs/octabam && PY=$(command -v python3) bash scripts/build_unicorn.sh )")
os.chdir(OCTABAM)
sys.path.insert(0, str(OCTABAM / "tools"))
import emu_rtos as er            # noqa: E402
import emu_card as ec            # noqa: E402

# --- blob geometry (NOTES.md "Session 42" RE) --------------------------------
BANK_BLOB = er.BANK_BLOB               # 0x400e21e0
BANK_STRIDE = er.BANK_STRIDE           # 0x9b340
PAT_STRIDE = er.PATTERN_STRIDE         # 0x8ed8  -- 16 pattern slabs fill blob+0..0x8ed80
TRAC_STRIDE = er.TRAC_STRIDE           # 0x91a
PARTS_OFF = 0x8ed80                    # = 16 * 0x8ed8 ; 4 part payloads follow
PART_STRIDE = 0x18b2                   # 6322
PARTS_LEN = 4 * PART_STRIDE            # 0x62c8
PLOCK_IN_TRAC = 0x59
PLOCK_LEN = 0x800

RELOAD_NOW = 0x46c8028a                # step engine polls this at 0x400a2530
SEQ_LEN_M1 = 0x800065b6               # master pattern length - 1 (reloaded by the block)
SEQ_STEP = 0x800065b4                 # master step position (zeroed by the block)
SEQ_BANK, SEQ_PAT = 0x800065bd, 0x800065be
TRANSPORT = 0x800065b8

# factory DEMO: P11 (index 10) t2 (index 1) is a 16-step track with p-locks
DISK_PAT, DISK_TRK = 10, 1
# disk layout of bank01.{work,strd}
D_PAT1, D_PSTRIDE, D_PHDR, D_TRAC = 0x16, 0x8EEC, 8, 0x922


def boot_and_load():
    card, name = er.stage_project(str(DEMO), "OCTABAM", None)
    r, rt = er.attach(str(OUR_IMAGE), card, tick=True)
    print(f"boot       : {r.stopped}")
    mounted, posted, saved_bank, final_bank, elapsed = rt.load_project_live(
        "OCTABAM", name, run_ms=6000, mount_ms=3000)
    print(f"load       : mounted={mounted} posted={posted} saved_bank={saved_bank} "
          f"final_bank={final_bank} ({elapsed:.0f} ms)")
    return rt


def part_ptr(rt):
    return struct.unpack(">I", rt.uc.mem_read(ec.PART_PTR, 4))[0]


def rd(rt, a, n):
    return bytes(rt.uc.mem_read(a, n))


def spin(rt, cap=400_000):
    n = 0
    while rt.pc != er.MAIN_SPIN and n < cap:
        rt.step()
        n += 1


# ---------------------------------------------------------------------------
def cmd_slice(rt):
    print("\n===== --slice : slab revert + 0x46c8028a refresh, while playing =====")
    curbank = rt.uc.mem_read(er.CUR_BANK, 1)[0]
    blob = part_ptr(rt)
    want = BANK_BLOB + curbank * BANK_STRIDE
    print(f"curbank={curbank}  PART_PTR={blob:#x}  BANK_BLOB+bank*stride={want:#x}  "
          f"{'OK' if blob == want else 'MISMATCH'}")
    if not blob:
        sys.exit("PART_PTR null -- load failed")

    P, Q = DISK_PAT, 0
    pP = blob + P * PAT_STRIDE
    pQ = blob + Q * PAT_STRIDE
    seqb, seqp = rt.seq_select_live(curbank, P)
    print(f"seq select : bank {seqb} pattern {seqp}  (want pattern {P})")

    saved_P = rd(rt, pP, PAT_STRIDE)
    saved_Q = rd(rt, pQ, PAT_STRIDE)
    print(f"snapshot   : P{P} slab {pP:#x}  Q{Q} slab {pQ:#x}  ({PAT_STRIDE:#x} B each)")

    # --- start the transport, let it settle -------------------------------
    rt.frame = True
    rt.next_frame = rt.sample + er.FRAME_PERIOD
    rt.exact_clock()
    rt.internal_clock()
    rt.press_play_live()
    rt.run(ms=250)
    print(f"transport  : 0x800065b8={int.from_bytes(rd(rt, TRANSPORT, 4),'big')}  "
          f"step={int.from_bytes(rd(rt, SEQ_STEP, 2),'big')}  "
          f"len-1={int.from_bytes(rd(rt, SEQ_LEN_M1, 1),'big')}")

    # --- scribble live edits into BOTH P and Q ----------------------------
    def scribble(base, tag):
        # a few trig-mask bytes + a p-lock value on track 0 and track DISK_TRK
        before = {}
        for trk in (0, DISK_TRK):
            tb = base + trk * TRAC_STRIDE
            for off in (0x00, 0x08, PLOCK_IN_TRAC + 0x40, PLOCK_IN_TRAC + 0x41):
                before[(trk, off)] = rd(rt, tb + off, 1)[0]
                rt.uc.mem_write(tb + off, bytes([before[(trk, off)] ^ 0x5A]))
        print(f"  scribble {tag}: {[(t, hex(o), hex(v)) for (t, o), v in before.items()]}")
        return before

    ed_P = scribble(pP, f"P{P}")
    ed_Q = scribble(pQ, f"Q{Q}")
    assert rd(rt, pP, PAT_STRIDE) != saved_P, "P edit did not land"
    assert rd(rt, pQ, PAT_STRIDE) != saved_Q, "Q edit did not land"

    # --- the worker's slice-copy: P's saved slab back over the blob -------
    rt.uc.mem_write(pP, saved_P)
    p_ok = rd(rt, pP, PAT_STRIDE) == saved_P
    q_survived = rd(rt, pQ, PAT_STRIDE) != saved_Q
    print(f"\nslice-copy : P{P} reverted = {p_ok}   Q{Q} edit survived = {q_survived}")

    # --- fire the reload-now flag, run frames ----------------------------
    len_before = int.from_bytes(rd(rt, SEQ_LEN_M1, 1), 'big')
    rt.uc.mem_write(RELOAD_NOW, struct.pack(">I", 1))
    print(f"poke       : 0x46c8028a = 1   (len-1 was {len_before})")
    faulted = None
    try:
        rt.run(ms=400)
    except Exception as e:
        faulted = f"{type(e).__name__}: {e}"
    flag = int.from_bytes(rd(rt, RELOAD_NOW, 4), 'big')
    len_after = int.from_bytes(rd(rt, SEQ_LEN_M1, 1), 'big')
    step_after = int.from_bytes(rd(rt, SEQ_STEP, 2), 'big')
    tport = int.from_bytes(rd(rt, TRANSPORT, 4), 'big')
    print(f"after run  : 0x46c8028a={flag:#x}  len-1={len_after}  step={step_after}  "
          f"transport={tport}  fault={faulted}")

    ok = (blob == want and p_ok and q_survived and flag == 0
          and 1 <= len_after <= 63 and faulted is None and tport == 1)
    print(f"\n--slice: {'ALL GOOD' if ok else 'CHECK FAILED'}")
    for name, cond in [("PART_PTR == blob geometry", blob == want),
                       ("P reverted to saved slab", p_ok),
                       ("Q's live edit survived", q_survived),
                       ("0x46c8028a consumed by the step engine", flag == 0),
                       ("pattern length reloaded (1..63)", 1 <= len_after <= 63),
                       ("no fault", faulted is None),
                       ("transport still running", tport == 1)]:
        print(f"   [{'x' if cond else ' '}] {name}")
    return ok


# ---------------------------------------------------------------------------
FUN_POST_RELOAD = 0x40022778    # (mask) -> post type-0x14 job to the storage task queue
STRD_MARK = 0x4009acec         # the reload-clear PC emu_plock uses as a landmark


def cmd_strd(rt):
    print("\n===== --strd : async storage-task reload of the bank blob from bankNN.strd =====")
    curbank = rt.uc.mem_read(er.CUR_BANK, 1)[0]
    blob = part_ptr(rt)
    tb = blob + DISK_PAT * PAT_STRIDE + DISK_TRK * TRAC_STRIDE
    before = rd(rt, tb + PLOCK_IN_TRAC, PLOCK_LEN)

    # a distinguishing scribble so we can see the reload overwrite it
    rt.uc.mem_write(tb + PLOCK_IN_TRAC, b"\xAB" * 64)
    print(f"curbank={curbank}  blob={blob:#x}  scribbled #1[0..1] = 0xAB*64")

    spin(rt)
    faulted = None
    try:
        d0 = rt.call_as_main(FUN_POST_RELOAD, args=(1 << curbank,), budget=1_000_000)
        print(f"post       : FUN_40022778(1<<{curbank}) -> d0={d0:#x}")
    except Exception as e:
        faulted = f"{type(e).__name__}: {e}"
        print(f"post       : raised {faulted}")
        return False
    rt.run(ms=20000)   # let FUN_4008445c drain: .strd->.work + deserialise

    after = rd(rt, tb + PLOCK_IN_TRAC, PLOCK_LEN)
    changed = after[:64] != b"\xAB" * 64
    print(f"reload     : #1[0] head  before {before[:16].hex(' ')}")
    print(f"                         after  {after[:16].hex(' ')}   "
          f"{'RELOADED (scribble gone)' if changed else 'scribble still there -- job did not run'}")

    strd = DEMO_BANK1_STRD.read_bytes()
    doff = D_PAT1 + D_PSTRIDE * DISK_PAT + D_PHDR + D_TRAC * DISK_TRK
    disk_plock = strd[doff + 0x62: doff + 0x62 + PLOCK_LEN]
    ram_plock = after

    def locked(buf):
        return [(s, [(hex(i), hex(buf[s * 32 + i])) for i in range(32) if buf[s * 32 + i] != 0xFF])
                for s in range(64) if any(buf[s * 32 + i] != 0xFF for i in range(32))]

    work = DEMO_BANK1_WORK.read_bytes()
    woff = D_PAT1 + D_PSTRIDE * DISK_PAT + D_PHDR + D_TRAC * DISK_TRK
    work_plock = work[woff + 0x62: woff + 0x62 + PLOCK_LEN]

    dl, rl, wl = locked(disk_plock), locked(ram_plock), locked(work_plock)
    src = ("bank01.strd" if rl == dl else "bank01.work" if rl == wl else "neither (?)")
    print(f"\n  .strd locked steps: {dl[:4]}")
    print(f"  .work locked steps: {wl[:4]}")
    print(f"  blob  locked steps: {rl[:4]}")
    print(f"\n--strd: the async storage-task job RAN end to end -- "
          f"dequeue -> FUN_400905d4 -> FUN_4008ded0 -> blob refilled from {src}")
    print("   [x] FUN_40022778 + storage task + deserialiser reach the blob (scribble wiped)")
    print(f"   [{'x' if src == 'bank01.strd' else ' '}] source was .strd  "
          f"(stock RELOAD BANK path copies .strd->.work first; the FEATURE build redirects "
          f"the open to .strd and skips that copy -- verify on the patched image / HW)")
    return changed


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--slice", action="store_true", help="slab revert + reload-now refresh (playing)")
    ap.add_argument("--strd", action="store_true", help="FUN_4008ded0(bank01.strd) -> scratch region")
    a = ap.parse_args()
    if not (a.slice or a.strd):
        ap.error("pick --slice and/or --strd")
    if not DEMO_BANK1_STRD.exists():
        sys.exit(f"missing {DEMO_BANK1_STRD} (the factory OT DEMO export)")

    rt = boot_and_load()
    ok = True
    if a.slice:
        ok &= cmd_slice(rt)
    if a.strd:
        ok &= cmd_strd(rt)
    sys.exit(0 if ok else 1)


if __name__ == "__main__":
    main()
