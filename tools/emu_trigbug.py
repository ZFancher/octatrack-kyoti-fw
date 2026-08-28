#!/usr/bin/env python3
# emu_trigbug.py  --  Session 5 (Claude Code)
#
# Execution harness for the MIDI manual-trig bug. Loads a REAL deserialized test bank into
# RAM at the real blob base (0x400e21e0), sets the handful of globals the trig path reads,
# then calls the real manual-trig dispatcher FUN_40044584(track, press) TWICE (simulating
# pressing an already-playing track's trig key) and dumps the per-track active-state RAM
# after each press.
#
# Run it on test1_PFD (Per-Track scale OFF) vs test1_PFD_scale (Per-Track scale ON, the
# user's confirmed hardware repro) and diff: this shows directly what the +0x48fd byte
# (which flips 0->1 when Per-Track scale is enabled) changes about the dispatch.
#
# Deserialize primitive reused from emu_bankdeserialize.py (patch FUN_40016564 entry -> rts,
# feed file bytes from a code hook).
#
# Usage:  <venv>/bin/python emu_trigbug.py            # runs both banks + diff
#         <venv>/bin/python emu_trigbug.py <bank.work> [--stepping 0|1] [--track N]
import struct, pathlib, sys

from unicorn import *
from unicorn.m68k_const import *

HERE = pathlib.Path(__file__).parent
IMG_PATH = HERE.parent / "out/raw/section_3_MAIN_OS.bin"
if not IMG_PATH.exists():
    IMG_PATH = HERE / "section_3_MAIN_OS.bin"          # scratchpad fallback

BASE      = 0x40000400
READ_FN   = 0x40016564
DESTBUF   = 0x50000000
STACK_TOP = 0x41010000
RET_MARK  = 0x401f0000
BLOB_BASE = 0x400e21e0
PAT_STRIDE  = 0x8ed8
MIDI_STRIDE = 0x8b0

DISP      = 0x40044584   # FUN_40044584(track, press)  -- manual-trig dispatcher
F_B290    = 0x4009b290   # is-active getter
F_B5C8    = 0x4009b5c8   # start
F_F3A4    = 0x4009f3a4   # retrigger/stop (buggy branch lives here)
F_B95A    = 0x4009b95a   # empty stub
F_A539C   = 0x400a539c   # per-track reset
F_C3C     = 0x40000c3c   # event post   -- STUBBED
F_F2F8    = 0x4009f2f8   # midi note-off sweep
F_10BC8   = 0x40010bc8   # midi send    -- STUBBED
F_108B0   = 0x400108b0   #              -- STUBBED

STUBS = {F_C3C, F_10BC8, F_108B0}
WATCH = {DISP:"DISP", F_B290:"B290/isactive", F_B5C8:"B5C8/START",
         F_F3A4:"F3A4/RETRIG", F_B95A:"B95A/stub", F_A539C:"A539C/reset",
         F_F2F8:"F2F8/noteoff", F_C3C:"C3C/event", F_10BC8:"10BC8/midisend"}

def deserialize_blob(file_bytes):
    img = bytearray(IMG_PATH.read_bytes())
    img[READ_FN-BASE:READ_FN-BASE+2] = b"\x4e\x75"
    uc = Uc(UC_ARCH_M68K, UC_MODE_BIG_ENDIAN)
    uc.mem_map(0x40000000, 0x200000)
    uc.mem_write(BASE, bytes(img[:0x200000-0x400]))
    uc.mem_map(0x41000000, 0x20000)
    uc.mem_map(DESTBUF, 0x200000)
    uc.hook_add(UC_HOOK_MEM_UNMAPPED, lambda uc,a,ad,s,v,u:(uc.mem_map(ad & ~0xFFF,0x1000), True)[1])
    cur = {"p": 0}
    def on_code(uc, address, size, user):
        if address == READ_FN:
            sp = uc.reg_read(UC_M68K_REG_A7)
            buf = struct.unpack(">I", uc.mem_read(sp+8, 4))[0]
            ln  = struct.unpack(">I", uc.mem_read(sp+12,4))[0]
            ch = file_bytes[cur["p"]:cur["p"]+ln]
            if len(ch) < ln: ch += b"\x00"*(ln-len(ch))
            uc.mem_write(buf, ch); cur["p"] += ln
            uc.reg_write(UC_M68K_REG_D0, ln)
    uc.hook_add(UC_HOOK_CODE, on_code, begin=READ_FN, end=READ_FN)
    sp = STACK_TOP
    for a in reversed([1, DESTBUF, 0]):
        sp -= 4; uc.mem_write(sp, struct.pack(">I", a))
    sp -= 4; uc.mem_write(sp, struct.pack(">I", RET_MARK))
    uc.reg_write(UC_M68K_REG_A7, sp)
    try:
        uc.emu_start(0x4008ded0, RET_MARK, count=30_000_000)
    except UcError as e:
        print("  [deser UcError %s @ PC=0x%08x]" % (e, uc.reg_read(UC_M68K_REG_PC)))
    return bytes(uc.mem_read(DESTBUF, 0x9c000))


# WITHDRAWN (Session 5 part 3): this patched FUN_40044584's ONE2 press dispatch. It fixes a
# real oddity (ONE2 retrigger -> FUN_4009f3a4 clear-only) but that is NOT the reported bug --
# the user confirmed the bug hits ALL trig modes. The real fix belongs in FUN_4009b5c8's
# per-track-scale read (MIDI stride). Kept only so --patch still runs for comparison.
FIX_ONE2 = {0x400446a2: bytes([0x60])}   # 0x400446a2  beq.b -> bra.b 0x400446c8

# ---------------------------------------------------------------------------------------------
# FIX_SCALE (Session 6, open item 1) -- the real fix for the MIDI manual-trig stall.
#
# FUN_4009b5c8's SCALE_MODE("Per Track")-gated per-track scale seed (raw asm
# 0x4009b6f2..0x4009b703) reads with the AUDIO stride 0x91a / offset +0x51 for ALL track
# indices. For MIDI tracks 8..15 that overshoots into trig data -> DAT_8000663e[track]
# (== DAT_80006646[track-8]) gets a garbage byte, and FUN_400a1eea then indexes the
# 13-entry step-length table DAT_400aba50 out of bounds -> step-advance gate always false
# -> stall after step 1.
#
# No room in place (18 bytes; D3 live as the store index at 0x4009b704) -> detour to a code
# cave at 0x400d7b00 (inside the 0x400d64da..0x400d7c3c zero cave build.py already uses,
# past patch_arp @ 0x400d7000). The cave keeps the audio math for tracks 0..7 and uses the
# MIDI stride/offset  A0 = blob + pattern*0x8ed8 + (track-8)*0x8b0 + 0x48f9  for 8..15,
# then jumps back to 0x4009b704.  Source: tools/patch_trigscale.s
FIX_CAVE_ADDR = 0x400d7b00
FIX_DETOUR_ADDR = 0x4009b6f2

def _fix_cave_bytes():
    p = HERE.parent / "out/patch_trigscale.bin"
    if p.exists():
        return p.read_bytes()
    # assembled fallback (m68k-elf-as -mcpu=5407, ld -Ttext=0x400d7b00); see patch_trigscale.s
    return bytes.fromhex(
        "7007b0836d18203c0000091a4c030800d0822041"
        "41f008514ef94009b70420035180"
        "2c3c000008b04c060800d08206800000"
        "48f92041d1c04ef94009b704")

def build_fix_scale():
    cave = _fix_cave_bytes()
    # detour: jmp 0x400d7b00 (6 bytes) + 6x nop  == 18 bytes, exactly the corrupt region
    detour = bytes([0x4e, 0xf9]) + struct.pack(">I", FIX_CAVE_ADDR) + b"\x4e\x71" * 6
    assert len(detour) == 18, len(detour)
    return {FIX_DETOUR_ADDR: detour, FIX_CAVE_ADDR: cave}

FIX_SCALE = build_fix_scale()

class Machine:
    def __init__(self, blob, stepping=1, patch=None):
        img = bytearray(IMG_PATH.read_bytes())
        for s in STUBS:
            img[s-BASE:s-BASE+2] = b"\x4e\x75"          # rts
        for addr, b in (patch or {}).items():
            img[addr-BASE:addr-BASE+len(b)] = b
        self.uc = uc = Uc(UC_ARCH_M68K, UC_MODE_BIG_ENDIAN)
        uc.mem_map(0x40000000, 0x400000)               # image + blob area (0x400e21e0 lives here)
        uc.mem_write(BASE, bytes(img[:0x400000-0x400]))
        uc.mem_map(0x41000000, 0x20000)                # stack
        uc.mem_map(0x00000000, 0x10000)                # low mem (event structs land here when stubbed)
        uc.mem_map(0x10000000, 0x01000000)             # 0x100bxxxx globals
        uc.mem_map(0x46000000, 0x01000000)             # 0x460dxxxx / 0x4610xxxx / 0x46c7xxxx arrays
        uc.mem_map(0x80000000, 0x00100000)             # 0x8000xxxx globals
        uc.mem_map(0xfc000000, 0x00100000)             # mmio-ish (_DAT_fc04c010)
        uc.hook_add(UC_HOOK_MEM_UNMAPPED,
                    lambda uc,a,ad,s,v,u:(uc.mem_map(ad & ~0xFFF, 0x1000), True)[1])
        # real deserialized pattern data at the real base
        uc.mem_write(BLOB_BASE, blob)
        # globals the trig path reads
        uc.mem_write(0x800065bd, b"\x00")              # bank index
        uc.mem_write(0x800065be, b"\x00")              # pattern index
        uc.mem_write(0x800065b6, b"\x00")              # sub-step gate
        uc.mem_write(0x800065b8, struct.pack(">I", stepping & 0xffffffff))  # "stepping"
        uc.mem_write(0x80000012, struct.pack(">I", 1)) # MIDI_MODE = 1
        uc.mem_write(0x100b14d0, b"\x00")              # dispatcher's pattern index
        uc.mem_write(0x8000004c, b"\x00")
        uc.mem_write(0x46c82456, struct.pack(">I", BLOB_BASE))   # dispatcher's blob pointer
        self.log = []
        self.skipped_sr = 0
        uc.hook_add(UC_HOOK_CODE, self._trace)

    def _trace(self, uc, address, size, user):
        # Unicorn m68k can't execute the privileged SR/CCR moves the firmware uses to guard
        # critical sections -- skip them at runtime (safe for a single-threaded trace).
        w = struct.unpack(">H", uc.mem_read(address, 2))[0]
        if (w & 0xFFC0) in (0x40C0, 0x42C0, 0x44C0, 0x46C0):
            nxt = address + (4 if w in (0x46FC, 0x44FC) else 2)
            uc.reg_write(UC_M68K_REG_PC, nxt)
            self.skipped_sr += 1
            return
        if address in WATCH:
            sp = uc.reg_read(UC_M68K_REG_A7)
            try:
                arg0 = struct.unpack(">I", uc.mem_read(sp+4, 4))[0]
            except UcError:
                arg0 = -1
            self.log.append((WATCH[address], address, arg0))

    def call(self, func, *args):
        uc = self.uc
        sp = STACK_TOP
        for a in reversed(args):
            sp -= 4; uc.mem_write(sp, struct.pack(">I", a & 0xffffffff))
        sp -= 4; uc.mem_write(sp, struct.pack(">I", RET_MARK))
        uc.reg_write(UC_M68K_REG_A7, sp)
        self.log.clear()
        try:
            uc.emu_start(func, RET_MARK, count=20_000_000)
        except UcError as e:
            print("  [UcError %s @ PC=0x%08x]" % (e, uc.reg_read(UC_M68K_REG_PC)))
        return list(self.log)

    def rb(self, addr, n=1):
        return bytes(self.uc.mem_read(addr, n))
    def ru16(self, addr):
        return struct.unpack(">H", self.uc.mem_read(addr, 2))[0]

    def snapshot(self):
        return dict(
            active_80006500 = list(self.rb(0x80006500, 16)),
            active_80006508 = list(self.rb(0x80006508, 16)),
            m80006680 = self.ru16(0x80006680),
            m80006682 = self.ru16(0x80006682),
            m80006684 = self.ru16(0x80006684),
            # DAT_8000663e = audio-track scale-offset array [0..7]; DAT_80006646 = MIDI [0..7].
            # FUN_4009b5c8 writes DAT_8000663e[param_1] with param_1 8..15 for MIDI tracks,
            # i.e. it lands in DAT_80006646[0..7] by aliasing (0x8000663e+8 == 0x80006646).
            scaleoff_audio_8000663e = list(self.rb(0x8000663e, 8)),
            scaleoff_midi_80006646  = list(self.rb(0x80006646, 8)),
            b_8000663d = self.rb(0x8000663d, 1)[0],
            keybits_460d1794 = self.ru16(0x460d1794),
        )


def fmt_calls(calls):
    return " -> ".join(f"{n}({a if a<0x1000 else hex(a)})" for n,_,a in calls) or "(none)"

def run_bank(path, stepping=1, track=8, patch=None, presses=3, quiet=False):
    fb = pathlib.Path(path).read_bytes()
    blob = deserialize_blob(fb)
    o = 0*PAT_STRIDE + (track-8)*MIDI_STRIDE
    tag = "PATCHED" if patch else "stock"
    if not quiet:
        print(f"\n=== {pathlib.Path(path).name}  [{tag}]  stepping={stepping}  track={track} (MIDI {track-8}) ===")
        print(f"    PLAYS_FREE={blob[o+0x48fc]}  TRIG_MODE(+0x48fd)={blob[o+0x48fd]} "
              f"({['ONE','ONE2','HOLD'][blob[o+0x48fd]] if blob[o+0x48fd]<3 else '?'})  "
              f"DIRECT(+0x48fe)={blob[o+0x48fe]-256 if blob[o+0x48fe]>=128 else blob[o+0x48fe]}  "
              f"SCALE_MODE(+0x8e55)={blob[0x8e55]}")
    m = Machine(blob, stepping=stepping, patch=patch)
    snaps = []
    for i in range(1, presses+1):
        calls = m.call(DISP, track, 1)          # press
        s = m.snapshot(); snaps.append(s)
        if not quiet:
            print(f"  press #{i}: {fmt_calls(calls)}")
            print(f"     active[8]={s['active_80006500'][track]}  midiActive[0]={s['active_80006508'][track-8]}"
                  f"   80006680={s['m80006680']:#06x} 82={s['m80006682']:#06x} 84={s['m80006684']:#06x}")
    return snaps


ABA50 = 0x400aba50   # int32[] scale-length table, 13 valid entries (indices 0..12)

def load_blob(name):
    for p in [HERE/"banks"/f"{name}.bank01.work", pathlib.Path.home()/"Desktop"/name/"bank01.work"]:
        if p.exists():
            return deserialize_blob(p.read_bytes()), str(p)
    raise SystemExit(f"can't find {name}")

def scale_evidence():
    """Show FUN_4009b5c8's per-track-scale read is the corruption, and it's trig-mode-independent."""
    img = pathlib.Path(IMG_PATH).read_bytes()
    def aba50_i32(i):
        o = ABA50 - BASE + i*4
        return struct.unpack(">i", img[o:o+4])[0]
    print("="*74)
    print("  MECHANISM: FUN_4009b5c8 per-track-scale read uses the AUDIO stride on MIDI tracks")
    print("="*74)
    print(f"  DAT_400aba50 as int32[] (valid scale lengths): "
          f"{[aba50_i32(i) for i in range(13)]}  ... index must be 0..12")
    print(f"  buggy formula   : blob[pattern*0x8ed8 + track*0x91a + 0x51]   (track=8 -> +0x{8*0x91a+0x51:x})")
    print(f"  correct (MIDI)  : blob[pattern*0x8ed8 + (track-8)*0x8b0 + 0x48f9] (track=8 -> +0x48f9)")
    print()
    for name in ("test1_PFD", "test1_PFD_scale"):
        blob, _ = load_blob(name)
        sm   = blob[0x8e55]
        buggy_src   = blob[8*0x91a + 0x51]                  # what FUN_4009b5c8 reads for MIDI trk 0
        correct_src = blob[0*MIDI_STRIDE + 0x48f9]          # what it should read
        pat_src     = blob[0x8e54]                          # SCALE_MODE==0 source
        m = Machine(blob, stepping=1)
        m.call(DISP, 8, 1)                                  # one press -> FUN_4009b5c8
        s = m.snapshot()
        got = s["scaleoff_midi_80006646"][0]
        idx_ok = 0 <= got <= 12
        mp = Machine(blob, stepping=1, patch=FIX_SCALE)     # same press, FIX_SCALE applied
        mp.call(DISP, 8, 1)
        gotp = mp.snapshot()["scaleoff_midi_80006646"][0]
        # audio track 0 must be unaffected by the fix
        ma = Machine(blob, stepping=1); ma.call(DISP, 0, 1)
        aud_stock = ma.snapshot()["scaleoff_audio_8000663e"][0]
        map_ = Machine(blob, stepping=1, patch=FIX_SCALE); map_.call(DISP, 0, 1)
        aud_fix = map_.snapshot()["scaleoff_audio_8000663e"][0]
        print(f"  {name:16} SCALE_MODE={sm}  trig_mode={['ONE','ONE2','HOLD'][blob[0x48fd]]}")
        print(f"      source bytes: buggy blob[+0x{8*0x91a+0x51:x}]={buggy_src:#04x}   "
              f"correct blob[+0x48f9]={correct_src:#04x}   pattern blob[+0x8e54]={pat_src:#04x}")
        print(f"      -> STOCK : after press, DAT_80006646[0] (MIDI trk0 scale index) = {got}"
              f"   {'(valid)' if idx_ok else '<<< OUT OF RANGE for the 13-entry table -> garbage step length'}")
        if idx_ok:
            print(f"                 DAT_400aba50[{got}] = {aba50_i32(got)}")
        else:
            print(f"                 DAT_400aba50[{got}] would read image offset 0x{ABA50 + got*4:x}"
                  f" (0x{got*4:x} past a 0x34-byte table) = {aba50_i32(got)}  <- nonsense")
        okp = 0 <= gotp <= 12
        print(f"      -> FIXED : DAT_80006646[0] = {gotp}   "
              f"{'(valid -> DAT_400aba50[%d] = %d)' % (gotp, aba50_i32(gotp)) if okp else '<<< STILL BAD'}")
        print(f"      -> audio trk0 scale-offset DAT_8000663e[0]:  stock={aud_stock:#04x}  fixed={aud_fix:#04x}"
              f"   {'(fix leaves audio untouched)' if aud_stock == aud_fix else '<<< FIX CHANGED AUDIO -- REGRESSION'}")
        print()
    print("  NOTE: FUN_4009b5c8 never reads +0x48fd (TRIG_MODE) -- so this corruption is")
    print("  identical for ONE / ONE2 / HOLD. Every mode calls FUN_4009b5c8 on the first")
    print("  manual trig of a PLAYS_FREE + DIRECT MIDI track.")
    print()
    print("  AUDIO tracks (param_1 0..7): blob[track*0x91a + 0x51] IS that audio track's real")
    print("  scale byte -- same expression FUN_400a1eea's audio loop uses -- so audio is")
    print("  self-consistent. e.g. test1_PFD_scale audio track 0: blob[+0x51] = "
          f"{load_blob('test1_PFD_scale')[0][0x51]:#04x} (valid). The bug is MIDI-only.")


def drift_check():
    """Open item 3: the buggy `track*0x91a + 0x51` read lands at a DIFFERENT wrong offset for
    each MIDI track 8..15, drifting 0x91a-0x8b0 = 0x6a per track past that track's real scale
    byte. Shown two ways: (a) static offset arithmetic vs the blob layout, (b) emulated -- a
    flagged what-if that forces PLAYS_FREE(+0x48fc=1)/DIRECT(+0x48fe=0xff) on ALL 8 MIDI
    tracks of a real deserialized bank, then presses each and compares stock vs FIX_SCALE."""
    img = pathlib.Path(IMG_PATH).read_bytes()
    def aba50_i32(i):
        o = ABA50 - BASE + i*4
        return struct.unpack(">i", img[o:o+4])[0]
    print("="*74)
    print("  OPEN ITEM 3: per-MIDI-track drift of the buggy `track*0x91a + 0x51` read")
    print("="*74)
    blob, path = load_blob("test1_PFD_scale")
    print(f"  bank: {path}   (pattern 0, bank 0)")
    print(f"  buggy   : blob[track*0x91a + 0x51]            (audio stride 0x91a)")
    print(f"  correct : blob[(track-8)*0x8b0 + 0x48f9]      (MIDI stride 0x8b0)")
    print(f"  drift per track = 0x91a - 0x8b0 = 0x{0x91a-0x8b0:x} bytes\n")
    print(f"  {'MIDItrk':7} {'trkidx':6} {'buggy off':>10} {'byte':>5} | {'correct off':>11} {'byte':>5}"
          f" | delta(buggy-correct)")
    for idx in range(8, 16):
        bo = idx*0x91a + 0x51
        co = (idx-8)*MIDI_STRIDE + 0x48f9
        print(f"  {idx-8:<7} {idx:<6} 0x{bo:>8x} {blob[bo]:#05x} | 0x{co:>9x} {blob[co]:#05x}"
              f" | +0x{bo-co:x}")
    print("\n  -> the buggy offset for MIDI trk0 lands 0x28 past that track's real scale byte")
    print("     (+0x48f9); each further track drifts another 0x6a, marching through trig/param")
    print("     data. None of these bytes is a scale index. They all read 0xff here only because")
    print("     these test-bank MIDI tracks are empty (0xff = no trig); a populated track would")
    print("     give assorted non-index garbage that still overflows DAT_400aba50[13].")

    # (b) emulated what-if -- force PF+Direct on all 8 MIDI tracks, press each
    print("\n  Emulated what-if (RAM blob edited: +0x48fc=1, +0x48fe=0xff for MIDI trk 0..7):")
    wb = bytearray(blob)
    for idx in range(8):
        o = idx*MIDI_STRIDE
        wb[o+0x48fc] = 1        # PLAYS_FREE
        wb[o+0x48fe] = 0xff     # DIRECT
    wb = bytes(wb)
    print(f"  {'MIDItrk':7} | {'STOCK DAT_8000663e[idx]':24} | {'FIXED':18}")
    for idx in range(8, 16):
        ms = Machine(wb, stepping=1)
        ms.call(DISP, idx, 1)
        gs = ms.snapshot()["scaleoff_audio_8000663e"][idx-8] if idx < 16 else None
        # DAT_8000663e[idx] for idx 8..15 == the 16-byte window snapshot() reads from 0x8000663e
        raw = list(ms.rb(0x8000663e, 16))
        gs = raw[idx]
        mf = Machine(wb, stepping=1, patch=FIX_SCALE)
        mf.call(DISP, idx, 1)
        gf = list(mf.rb(0x8000663e, 16))[idx]
        oks = 0 <= gs <= 12
        okf = 0 <= gf <= 12
        exp = blob[(idx-8)*MIDI_STRIDE + 0x48f9]
        print(f"  {idx-8:<7} | {gs:>3} {'(valid)' if oks else '(OOB -> garbage step len)':21}"
              f" | {gf:>3} {'ok' if okf and gf==exp else ('ok' if okf else 'BAD')}   (want {exp})")
    print("\n  STOCK: the corrupted index varies per track (the drift) and most are out of the")
    print("  0..12 range -> DAT_400aba50 OOB -> that MIDI track stalls after step 1.")
    print("  FIXED: every MIDI track gets its own real +0x48f9 scale byte.")


if __name__ == "__main__":
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    stepping = 1
    track = 8
    if "--stepping" in sys.argv:
        stepping = int(sys.argv[sys.argv.index("--stepping")+1])
    if "--track" in sys.argv:
        track = int(sys.argv[sys.argv.index("--track")+1])
    patch = FIX_ONE2 if "--patch" in sys.argv else (FIX_SCALE if "--fix" in sys.argv else None)

    if args:
        for p in args:
            run_bank(p, stepping, track, patch=patch)
    elif "--drift" in sys.argv:
        drift_check()
    else:
        scale_evidence()
        print("\n")
        drift_check()
        print("\n")
        cand = {}
        for name in ("test1_PFD", "test1_PFD_scale", "test1_PF_"):
            try: cand[name] = load_blob(name)[1]
            except SystemExit: pass
        for st in (1,):
            print("="*74 + f"\n  DISPATCH TRACE   stepping={st}\n" + "="*74)
            for name, p in cand.items():
                run_bank(p, stepping=st, track=8)
