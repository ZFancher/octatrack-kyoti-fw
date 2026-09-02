#!/usr/bin/env python3
# SPDX-License-Identifier: MIT
# SPDX-FileCopyrightText: 2026 Zachary Fancher ("Kyoti")
# emu_mute.py -- Session 9. Answer: when you mute an audio track (FUNC+TRACK), does the
# firmware hard-cut the voice or trigger an amp RELEASE?  And is the FX-send tap pre- or
# post- the mute gain?
#
# Approach: run the REAL mute handlers under Unicorn against a synthetic "track 0 has a
# sounding FLEX voice" pre-state, and log (a) every jsr/bsr into the voice engine and
# (b) every write to the voice structs / playback-cursor structs / mute-mask globals /
# DSP-frame amp slots.  Compare three actions:
#     A. FUN_40083ab4(0x10, 1)   -- mute track 0 (the real FUNC+TRACK path)
#     B. FUN_40008f84(0)         -- the KNOWN soft-release primitive (reference)
#     C. FUN_400068e4(0, 0, 0, N)-- the control-rate voice updater that applies the ramp
#        after DAT_8000184c bit is set (drive it with/without the release bit)
#
# The "release" fingerprint we look for: writes of 0xf0000000 / 0xe0000000 to the
# playback-cursor struct (base 0x80004f1c, stride 0x54) at +0x24/+0x28/+0x2c/+0x40, and/or
# DAT_8000184a |= 1<<track (voice enters state 2 = releasing).
#
# Usage:  python3 tools/emu_mute.py            # all scenarios
#         python3 tools/emu_mute.py --trace    # + full instruction-level call trace
#
# NOTE: FUN_400068e4 runs off the end into a jump-table tail this bare harness can't follow
# (UcError @ 0x40007700). That is AFTER the amp-envelope logic we care about, so every write
# listed below the "RELEASE FINGERPRINT" lines is still valid; the UcError is expected.
#
# RESULT (Session 9):
#  - S1: FUN_40083ab4(mute) on a sounding FLEX voice, default settings -> writes ONLY
#        _DAT_460fab40. No voice cmd, no amp write, no release. (arranger FUN_40083544 = same)
#  - S3/S5: setting DAT_8000184c |= 1<<track makes the very next FUN_400068e4 tick write the
#        release ramp (pcurs+0x24/28/2c = 0xf0000000, +0x40 = 0xe0000000) and drive
#        FUN_40005c7c -> FUN_40095ee0 -> FUN_40099090, ramping AMPseg[trk] toward the 0x2d0
#        floor. This is the AMP stage (vframe voice 0x80+track), upstream of the track FX/mix.
#  - S6: the mask-commit path (FUN_400834d8: _DAT_460fab40<<8 -> 46c803d4/46c7ff64) does NOT
#        produce an amp ramp -> the hard mute gate lives in the voice-cmd/DSP path, not here.
#  - S7: frame_builder per-track loop (entered at 0x4000c87c). For a still-sounding sustained
#        voice:  _DAT_46c7ff64 bit set   -> SAME refresh cmd emitted (0x2210), voice kept alive
#                                           => the hard cut is the DSP output mute (post-FX).
#                _DAT_8000184e bit set (46c7ff64 clear) -> refresh slot CLEARED, no cmd emitted
#                                           => voice just stops being fed, decays naturally.
#  - S8: TWO different "stop" primitives, and they are NOT the same:
#        * DAT_8000184a (set by FUN_40008f84) -> frame_builder @0x4000bd3c reads it, OR's bit
#          0x10 (note-off/gate-release) into the voice cmd, then clears the bit. The DSP then
#          runs the voice's AMP-envelope RELEASE stage (same flag as a STATIC note-off).
#          FUN_400068e4 does NOT write the fixed fade for this. => AMP REL knob is honored.
#          Backstop: relparam_46c7dfba[t] counts down from 0x2d(45) frames (FUN_40052290 loop);
#          at 0 a voice still in release state 2 is force-freed (FUN_40006820).
#        * DAT_8000184c (FUN_40008fe4 / STOP / CHANGE-SET) -> FUN_400068e4 writes fixed
#          0xf0000000 slopes = ~ms declick fade, AMP env ignored.
#  => PROPOSED FIX (detour FUN_400836d8, the common apply-mute fn, behind a PERSONALIZE flag):
#     on phase==1, per newly-muted sounding FLEX/STATIC track t:
#         FUN_40008f84(t)              # note-off -> AMP-envelope RELEASE (honors REL knob)
#         _DAT_8000184e  |= 1<<(t+8)   # stop the sustain-refresh -> looped samples wind down
#         and DON'T let _DAT_46c7ff64 bit (t+8) get set  # keep FX/mix output open -> tails ring
#     Unmute: clear _DAT_8000184e bit, run the existing re-trig options.  (Optional: also skip
#     the 46c7dfba watchdog for soft-muted tracks if genuinely-infinite REL should sustain.)
import struct, pathlib, sys

from unicorn import *
from unicorn.m68k_const import *

HERE = pathlib.Path(__file__).parent
IMG_PATH = HERE.parent / "out/raw/section_3_MAIN_OS.bin"
if not IMG_PATH.exists():
    IMG_PATH = HERE / "section_3_MAIN_OS.bin"
IMG = IMG_PATH.read_bytes()
BASE = 0x40000400

STACK_TOP = 0x41010000
RET_MARK  = 0x401f0000
BLOB_BASE = 0x50000000          # project/pattern blob (_DAT_46c82456)
PAT_STRIDE = 0x18b2

# ---- function addresses --------------------------------------------------------------
F = dict(
    mute_set      = 0x40083ab4,   # FUN_40083ab4(keycode, phase)  -- add track to mute mask
    mute_clr      = 0x40083e40,   # FUN_40083e40(keycode, phase)  -- remove
    apply_mask    = 0x400836d8,   # FUN_400836d8(arg, phase)      -- re-eval all 8 voices
    apply_1trk    = 0x40083544,   # FUN_40083544(track, phase)    -- arranger uses this
    rel_all_muted = 0x40083a7c,   # FUN_40083a7c()                -- for t in muted: soft_release
    commit_mask   = 0x400834d8,   # FUN_400834d8(_, phase)        -- mask<<8 -> 46c803d4/46c7ff64
    soft_release  = 0x40008f84,   # FUN_40008f84(track)           -- REFERENCE release primitive
    soft_rel_184c = 0x40008fe4,   # FUN_40008fe4(track)
    voice_upd     = 0x400068e4,   # FUN_400068e4(track,a,b,c)     -- writes the 0xf0000000 ramps
    voice_cmd     = 0x40005178,   # FUN_40005178(track, cmd, flag)
    v_5030        = 0x40005030,   # FUN_40005030(track, a, b, c)
    env_fade      = 0x40005c7c,   # FUN_40005c7c(...)
    amp_writer    = 0x40095ee0,   # FUN_40095ee0(voice, level, a, b)
    f_432c        = 0x4000432c,   # FUN_4000432c(track, level, flag)
    f_672c        = 0x4000672c,   # FUN_4000672c(track)
    cc_out        = 0x40033e3c,   # FUN_40033e3c(track, ccnum, val)
    redraw        = 0x4005a2b8,   # FUN_4005a2b8(...)  -- STUB (screen)
    g_33998       = 0x40033998,   # gates at top of FUN_400836d8
    g_33990       = 0x40033990,
    g_33970       = 0x40033970,
    h_042b4       = 0x400042b4,   # FUN_400042b4(track) -> current voice mode nibble
    h_00e50       = 0x40000e50,   # FUN_40000e50(track) -> &voice[track]
    h_00ee0       = 0x40000ee0,   # FUN_40000ee0(track) -> 0/1/2 active
    f_83208       = 0x40083208,   # FUN_40083208(track) -- mute-screen UI (want to skip)
    f_83bf8       = 0x40083bf8,   # FUN_40083bf8(track)
    f_43728       = 0x40043728,
    f_97460       = 0x40097460,
    f_00c3c       = 0x40000c3c,   # kernel event post -- STUB
    f_208d4       = 0x400208d4,   # memcpy-ish -- keep
    f_20898       = 0x40020898,
    mh_a6c        = 0x40030a6c,   # CUE/MUTE/SOLO key handler A (sets _DAT_460d10d8)
    mh_c60        = 0x40030c60,   # ...B (sets _DAT_460d10d4)
    mh_e6c        = 0x40030e6c,   # ...C (sets _DAT_460d10d0)
    f_30984       = 0x40030984,
    f_839dc       = 0x400839dc,
    f_619a4       = 0x400619a4,
    f_97924       = 0x40097924,
)
NAME = {v: k for k, v in F.items()}

# ---- patch_softmute: detour FUN_400836d8 -> cave @ 0x400d7400 --------------------------
# Always assembles the GATED build (no --defsym ALWAYS_ON) to a temp path -- distinct from
# out/patch_softmute.bin, which build_softmute.py produces ALWAYS-ON for the flash image.
def _softmute_patch():
    cave_at = 0x400d7400
    import subprocess, tempfile, os
    d = tempfile.mkdtemp(prefix="psm_")
    subprocess.run(["m68k-elf-as", "-mcpu=5407", "-o", f"{d}/psm.o",
                    str(HERE / "patch_softmute.s")], check=True)
    subprocess.run(["m68k-elf-ld", f"-Ttext=0x{cave_at:x}", "-o", f"{d}/psm.elf", f"{d}/psm.o"],
                   check=True, capture_output=True)
    subprocess.run(["m68k-elf-objcopy", "-O", "binary", f"{d}/psm.elf", f"{d}/psm.bin"], check=True)
    cave = pathlib.Path(f"{d}/psm.bin").read_bytes()
    detour = b"\x4e\xf9" + struct.pack(">I", cave_at)   # jmp cave  (6 B)
    # V4: hook the `move.l 0x80000008,D5` inside FUN_40004dbc (the per-frame mute gate).
    # (V1/V2 hooked FUN_400836d8, V3 hooked FUN_40030c60 -- both dead ends.)
    return {0x40004dc6: detour, cave_at: cave}

SOFTMUTE_PATCH = _softmute_patch()
GATE_ADDR = 0x800000dc

# functions we neutralize (return 0, pop return addr)
STUBS = {
    F["redraw"], F["f_00c3c"], F["f_83208"], F["f_43728"], F["f_97460"],
    0x40032f40, 0x400326a0, 0x4004d780, 0x400554e0, 0x400486cc, 0x4006de34,
    0x40078850, 0x4002f2f8, 0x4007b780, 0x4007ec60, 0x40031494, 0x40031abc,
    0x40055db4, 0x4003146c, 0x40058390, 0x4005829c, 0x40031a0c, 0x40081edc,
    0x40082e94, 0x40082e24, 0x40082f40, 0x40033e20, 0x40077a8c, 0x400789e4,
    0x4006dbcc, 0x400791e4, 0x4006dd18,
    0x40020898, 0x400208d4,          # memcpy-ish inside frame_builder
    # --- broad stubs for the CUE/MUTE/SOLO key-handler chain (scenario 10) ---
    0x40027e00, 0x40027de4, 0x40027e30, 0x4002e9a8, 0x4007e998, 0x4003171c,
    0x4002ff84, 0x40097924, 0x400326a0, 0x4004d780, 0x400554e0, 0x400486cc,
    0x4002ea84, 0x40077a8c, 0x400789e4, 0x4006e160, 0x4006de34, 0x4006dbcc,
    0x4002f2f8, 0x40078850, 0x4007b780, 0x40041760, 0x4009b270, 0x40000eb4,
    0x400323a0, 0x4001529c, 0x40033968, 0x4004b08c, 0x4004b528, 0x4006dd18,
}

# ---- watched memory regions ---------------------------------------------------------
def region_name(ad):
    if 0x460fab34 <= ad <= 0x460fab52:
        off = ad - 0x460fab40
        return f"MUTEMASK _DAT_460fab40{'+%#x'%off if off>0 else ('%#x'%(ad-0x460fab34)+'(win)' if ad<0x460fab40 else '')}"
    if 0x46c803d4 <= ad <= 0x46c803d7: return "_DAT_46c803d4 (cue.lo/mute.hi)"
    if 0x46c7ff64 <= ad <= 0x46c7ff67: return "_DAT_46c7ff64 (main-out silence)"
    if 0x46c7fe22 <= ad <= 0x46c7fe25: return "_DAT_46c7fe22 (cue derived)"
    if 0x8000184a <= ad <= 0x8000184b: return "DAT_8000184a (gate-release: DSP honors AMP env REL)"
    if ad == 0x8000184c: return "DAT_8000184c (FAST declick fade, ignores AMP env)"
    if 0x46c7dfba <= ad < 0x46c7dfba + 8*4:
        return f"relparam_46c7dfba[trk{(ad-0x46c7dfba)//4}]"
    if 0x8000184e <= ad <= 0x8000184f: return "_DAT_8000184e (silenced mask)"
    if 0x800049d8 <= ad < 0x800049d8 + 8*0xA8:
        t = (ad - 0x800049d8) // 0xA8; o = (ad - 0x800049d8) % 0xA8
        return f"voice[{t}]+{o:#x} (0xA8 struct)"
    if 0x80004f1c <= ad < 0x80004f1c + 2*0x2a0 + 0x100:
        rel = ad - 0x80004f1c
        bank = rel // 0x2a0; o = rel % 0x2a0
        t = o // 0x54; oo = o % 0x54
        return f"pcurs[bank{bank},trk{t}]+{oo:#x}"
    if 0x46c7e9fa <= ad < 0x46c7e9fa + 8*4:
        return f"mailbox_46c7e9fa[trk{(ad-0x46c7e9fa)//4}]"
    if 0x800018be <= ad < 0x800018be + 8*4:
        return f"mailbox_800018be[trk{(ad-0x800018be)//4}]"
    if 0x46c80354 <= ad < 0x46c80354 + 8*4:
        return f"mailbox_46c80354[trk{(ad-0x46c80354)//4}]"
    if 0x46c938d4 <= ad < 0x46c938d4 + 8*0x2c:
        return f"framelvl_46c938d4[trk{(ad-0x46c938d4)//0x2c}]+{(ad-0x46c938d4)%0x2c:#x}"
    if 0x46104d26 <= ad < 0x46104d26 + 8*2:
        return f"RESOLVEDCMD_46104d26[trk{(ad-0x46104d26)//2}] (-> DSP frame)"
    if 0x46c7faa4 <= ad < 0x46c7faa4 + 24*4:
        i = (ad - 0x46c7faa4)//4
        return f"refresh_46c7faa4[trk{i%8},slot{i//8}]"
    if 0x8000183a <= ad < 0x8000183a + 8: return f"lastbank_8000183a[trk{ad-0x8000183a}]"
    if 0x80001842 <= ad < 0x80001842 + 8: return f"lastpat_80001842[trk{ad-0x80001842}]"
    if 0x100b14f0 <= ad < 0x100b14f0 + 0x90*0x448:   # FUN_40095ee0/40005c7c per-voice frame
        rel = ad - 0x100b14f0; v = rel // 0x448; o = rel % 0x448
        vn = f"voice{v}" if v < 0x80 else f"AMPseg[trk{v-0x80}]"
        return f"vframe[{vn}]+{o:#x}"
    if 0x100d3a04 <= ad <= 0x100d3a30: return f"_DAT_100d3a04+{ad-0x100d3a04:#x} (432c env)"
    return None

WATCH_LO, WATCH_HI = 0x100b0000, 0x10200000     # broad; region_name filters
RELEASE_FINGERPRINT = {0xf0000000, 0xe0000000, 0x2d}


class Mach:
    def __init__(self, trace=False, sounding=True, machine_type=0, mute_nibble=0,
                 stepping=1, cur_track=0, qmode10d4=0, qmode10d8=0, qmode10d0=0,
                 patch=None, softmute=False):
        self.trace = trace
        self.calls = []          # (name, [args], depth)
        self.writes = []         # (pc, addr, size, val, regionname)
        self.jtrace = []
        self.depth = 0
        uc = self.uc = Uc(UC_ARCH_M68K, UC_MODE_BIG_ENDIAN)
        img = bytearray(IMG)
        for s in STUBS:
            img[s-BASE:s-BASE+2] = b"\x4e\x75"           # rts
        for addr, b in (patch or {}).items():
            img[addr-BASE:addr-BASE+len(b)] = b
        uc.mem_map(0x40000000, 0x400000)
        uc.mem_write(BASE, bytes(img[:0x400000-0x400]))
        for a, sz in [(0x41000000, 0x20000), (0x00000000, 0x10000),
                      (0x10000000, 0x02000000), (0x46000000, 0x02000000),
                      (0x50000000, 0x00400000), (0x80000000, 0x00200000),
                      (0xfc000000, 0x00100000), (0x20000000, 0x00010000),
                      (0x90000000, 0x00010000)]:
            uc.mem_map(a, sz)
        uc.hook_add(UC_HOOK_MEM_UNMAPPED,
                    lambda uc, a, ad, s, v, u: (uc.mem_map(ad & ~0xFFF, 0x1000), True)[1])

        # ---- project blob ----
        uc.mem_write(0x46c82456, struct.pack(">I", BLOB_BASE))
        uc.mem_write(BLOB_BASE, bytes(0x300000))
        # machine-type bytes: blob + pat*0x18b2 + trk*0xc + 0x8f385  (0 = FLEX)
        # blob + pat*0x18b2 + trk + 0x8eda2   (per-track machine kind; 4 == PICKUP)
        for t in range(8):
            uc.mem_write(BLOB_BASE + 0*PAT_STRIDE + t*0xc + 0x8f385, bytes([machine_type & 0xff]))
            uc.mem_write(BLOB_BASE + 0*PAT_STRIDE + t + 0x8eda2, bytes([0]))
            # 0x8f38b: per-track "voice-cmd slot valid" byte read by FUN_40005178 (-1 => simple path)
            uc.mem_write(BLOB_BASE + 0*PAT_STRIDE + t*0xc + 0x8f38b, bytes([0xff]))

        # ---- globals ----
        uc.mem_write(0x800065b8, struct.pack(">I", stepping & 0xffffffff))  # "playing"
        uc.mem_write(0x80000003, bytes([0]))       # DAT_80000003 sounding pattern
        uc.mem_write(0x100b14cf, bytes([0]))       # DAT_100b14cf displayed pattern
        uc.mem_write(0x100b14cc, bytes([cur_track & 0xff]))   # current track
        uc.mem_write(0x80000000, bytes([cur_track & 0xff]))
        uc.mem_write(0x100b14d0, bytes([0]))
        uc.mem_write(0x80000012, bytes([0]))      # audio mode
        uc.mem_write(0x460fab34, struct.pack(">I", 0))   # mute-screen window handle = 0 -> skip UI
        uc.mem_write(0x460fab40, struct.pack(">I", 0))   # mute mask starts clear
        # mute-quantize state -- the real MUTE/CUE/SOLO key handlers (FUN_40030a6c/c60/e6c)
        # set one of these to 1 BEFORE calling FUN_400836d8.  uVar6 in FUN_400836d8 =
        #   (d0 + (d4 + d8*2)*2) * 0x1000
        uc.mem_write(0x460d10d0, struct.pack(">I", qmode10d0 & 0xffffffff))
        uc.mem_write(0x460d10d4, struct.pack(">I", qmode10d4 & 0xffffffff))
        uc.mem_write(0x460d10d8, struct.pack(">I", qmode10d8 & 0xffffffff))
        uc.mem_write(0x460d179a, struct.pack(">I", mute_nibble & 0xffffffff))
        uc.mem_write(0x460d179e, struct.pack(">I", 0))
        uc.mem_write(0x460d17a2, struct.pack(">I", 0))
        uc.mem_write(0x800000c4, struct.pack(">I", 0))
        uc.mem_write(0x46c82456, struct.pack(">I", BLOB_BASE))
        uc.mem_write(GATE_ADDR, bytes([1 if softmute else 0]))   # SOFT MUTE PERSONALIZE flag

        # ---- synthetic "sounding FLEX voice" on track 0 ----
        if sounding:
            for t in [0]:
                v = 0x800049d8 + t*0xA8
                uc.mem_write(v + 0, bytes([1]))     # byte0 active
                uc.mem_write(v + 1, bytes([1]))     # byte1 "sounding a sample"
                uc.mem_write(v + 2, bytes([1]))     # byte2 state (1 = playing, not releasing)
                # playback cursor: FUN_400042b4 reads _DAT_80004f18 bit, DAT_80004f1e[..]
                pc = 0x80004f1c + t*0x54            # bank 0
                uc.mem_write(pc + 2, bytes([1]))    # +2 == '\x01' -> "voice slot live"
                uc.mem_write(pc + 0x02, bytes([1]))
                uc.mem_write(0x80004f1e + t*0x54, bytes([1]))   # (&DAT_80004f1e)[iVar8] != 0
                uc.mem_write(pc + 0xc, struct.pack(">I", 0x4000))  # +0xc position != 0 (playing)
                uc.mem_write(pc + 0x10, struct.pack(">I", 0))
                uc.mem_write(pc + 0x18, struct.pack(">I", 0x2000))  # +0x18 "current level" (returned)
        uc.mem_write(0x80004f18, struct.pack(">I", 0))   # bank select = 0

        # gate helpers -> deterministic returns
        self.RET0 = {F["g_33998"], F["g_33990"], F["g_33970"], F["f_43728"],
                     F["f_83bf8"], 0x40033968}
        uc.hook_add(UC_HOOK_CODE, self._code)
        uc.hook_add(UC_HOOK_MEM_WRITE, self._write)

    # --- hooks --------------------------------------------------------------------
    def _code(self, uc, addr, size, user):
        # skip privileged SR/CCR moves unicorn m68k can't do
        w = struct.unpack(">H", uc.mem_read(addr, 2))[0]
        if (w & 0xFFC0) in (0x40C0, 0x42C0, 0x44C0, 0x46C0):
            uc.reg_write(UC_M68K_REG_PC, addr + (4 if w in (0x46FC, 0x44FC) else 2))
            return

        if addr in self.RET0:
            self._ret(uc, 0)
            return

        if addr in NAME:
            sp = uc.reg_read(UC_M68K_REG_A7)
            try:
                args = [struct.unpack(">i", uc.mem_read(sp + 4 + 4*i, 4))[0] for i in range(4)]
            except UcError:
                args = []
            self.calls.append((NAME[addr], args, self.depth))
            if self.trace:
                self.jtrace.append("  " * self.depth + NAME[addr] + str(tuple(args[:3])))

        if addr == RET_MARK:
            return

    def _ret(self, uc, d0):
        sp = uc.reg_read(UC_M68K_REG_A7)
        ret = struct.unpack(">I", uc.mem_read(sp, 4))[0]
        uc.reg_write(UC_M68K_REG_A7, sp + 4)
        uc.reg_write(UC_M68K_REG_PC, ret)
        uc.reg_write(UC_M68K_REG_D0, d0 & 0xffffffff)

    def _write(self, uc, access, addr, size, val, user):
        rn = region_name(addr)
        if rn is None:
            return
        pc = uc.reg_read(UC_M68K_REG_PC)
        self.writes.append((pc, addr, size, val & ((1 << (8*size)) - 1), rn))

    # --- driver ------------------------------------------------------------------
    def call(self, func, *args, limit=2_000_000):
        uc = self.uc
        sp = STACK_TOP
        for a in reversed(args):
            sp -= 4; uc.mem_write(sp, struct.pack(">I", a & 0xffffffff))
        sp -= 4; uc.mem_write(sp, struct.pack(">I", RET_MARK))
        uc.reg_write(UC_M68K_REG_A7, sp)
        self.calls.clear(); self.writes.clear(); self.jtrace.clear()
        self.err = None
        try:
            uc.emu_start(func, RET_MARK, count=limit)
        except UcError as e:
            self.err = "%s @ PC=0x%08x" % (e, uc.reg_read(UC_M68K_REG_PC))
        return self

    def run_at(self, pc, stop_pc, d=None, a=None, limit=3_000_000):
        """Enter mid-function at `pc` with a fresh big stack and optional register presets."""
        uc = self.uc
        uc.reg_write(UC_M68K_REG_A7, STACK_TOP - 0x400)
        for i, r in enumerate([UC_M68K_REG_D0, UC_M68K_REG_D1, UC_M68K_REG_D2, UC_M68K_REG_D3,
                               UC_M68K_REG_D4, UC_M68K_REG_D5, UC_M68K_REG_D6, UC_M68K_REG_D7]):
            uc.reg_write(r, (d or {}).get(i, 0))
        for i, r in enumerate([UC_M68K_REG_A0, UC_M68K_REG_A1, UC_M68K_REG_A2, UC_M68K_REG_A3,
                               UC_M68K_REG_A4, UC_M68K_REG_A5, UC_M68K_REG_A6]):
            uc.reg_write(r, (a or {}).get(i, 0))
        self.calls.clear(); self.writes.clear(); self.jtrace.clear()
        self.err = None
        self._stop_pc = stop_pc
        try:
            uc.emu_start(pc, stop_pc, count=limit)
        except UcError as e:
            self.err = "%s @ PC=0x%08x" % (e, uc.reg_read(UC_M68K_REG_PC))
        return self

    def rd(self, addr, n):
        return bytes(self.uc.mem_read(addr, n))

    def report(self, tag):
        print(f"\n{'='*78}\n{tag}\n{'='*78}")
        if self.err:
            print("  [UcError]", self.err)
        seen = [c for c in self.calls if c[0] not in ("h_042b4", "h_00e50", "h_00ee0")]
        print("  calls into voice/mute engine:")
        if seen:
            for nm, a, d in seen:
                print(f"    {'  '*d}{nm}{tuple(a[:3])}")
        else:
            print("    (none)")
        print("  writes to watched state:")
        rel = False
        if self.writes:
            for pc, ad, sz, v, rn in self.writes:
                flag = ""
                if v in RELEASE_FINGERPRINT or (sz == 4 and v in RELEASE_FINGERPRINT):
                    flag = "   <<< RELEASE FINGERPRINT"; rel = True
                print(f"    pc={pc:#010x}  [{rn}] <- {v:#0{2+sz*2}x} ({sz}B){flag}")
        else:
            print("    (none)")
        if self.trace and self.jtrace:
            print("  call tree:")
            for line in self.jtrace:
                print("    " + line)
        return rel


def scenario_mute_vs_release(trace):
    print("\n\n########## SCENARIO 1: mute a sounding FLEX track  vs  the known release primitive ##########")

    m = Mach(trace=trace, sounding=True, machine_type=0, mute_nibble=0)
    m.call(F["mute_set"], 0x10, 1)          # FUNC+TRACK mute of track 0
    r1 = m.report("A. FUN_40083ab4(0x10, 1)  -- real FUNC+TRACK mute, FLEX voice sounding, no mute-retrig")
    mask = struct.unpack(">I", m.rd(0x460fab40, 4))[0]
    d3d4 = struct.unpack(">H", m.rd(0x46c803d4, 2))[0]
    ff64 = struct.unpack(">H", m.rd(0x46c7ff64, 2))[0]
    s184a = m.rd(0x8000184a, 1)[0]
    print(f"  post-state: _DAT_460fab40={mask:#04x}  _DAT_46c803d4={d3d4:#06x}  "
          f"_DAT_46c7ff64={ff64:#06x}  DAT_8000184a={s184a:#04x}")

    m = Mach(trace=trace, sounding=True, machine_type=0)
    m.call(F["soft_release"], 0)
    r2 = m.report("B. FUN_40008f84(0)  -- the reference SOFT-RELEASE primitive")
    s184a = m.rd(0x8000184a, 1)[0]
    print(f"  post-state: DAT_8000184a={s184a:#04x}  (bit0 set => track0 marked releasing)")

    m = Mach(trace=trace, sounding=True, machine_type=0, mute_nibble=0x101)  # nibble != 0
    m.call(F["mute_set"], 0x10, 1)
    m.report("C. FUN_40083ab4(0x10, 1) with a mute-retrig nibble set (STARTS-SILENT/ONE/... option)")

    print("\n  ---- verdict ----")
    print(f"  real mute reaches a release fingerprint : {r1}")
    print(f"  reference primitive shows fingerprint   : {r2}")


def scenario_arranger(trace):
    print("\n\n########## SCENARIO 2: arranger MUTE command path (FUN_40083544) ##########")
    for mt, lbl in [(0, "FLEX"), (1, "STATIC"), (3, "NEIGHBOR")]:
        m = Mach(trace=trace, sounding=True, machine_type=mt, mute_nibble=0)
        m.call(F["apply_1trk"], 0, 1)
        m.report(f"FUN_40083544(track=0, phase=1)   machine={lbl}   (sounding, no mute nibble)")


def scenario_voice_updater(trace):
    print("\n\n########## SCENARIO 3: does DAT_8000184c drive a ramp in FUN_400068e4? ##########")
    # without the release bit
    m = Mach(trace=trace, sounding=True, machine_type=0)
    m.call(F["voice_upd"], 0, 0, 0, 0x100)
    m.report("FUN_400068e4(0,0,0,0x100)   DAT_8000184c bit NOT set")
    # with the release bit set (as FUN_40008f84 would)
    m = Mach(trace=trace, sounding=True, machine_type=0)
    m.uc.mem_write(0x8000184c, bytes([0xff]))
    m.uc.mem_write(0x8000184a, bytes([0x01]))
    m.call(F["voice_upd"], 0, 0, 0, 0x100)
    r = m.report("FUN_400068e4(0,0,0,0x100)   DAT_8000184c=0xff, DAT_8000184a bit0 set")
    print(f"\n  ramp written when release bit is set: {r}")


def scenario_rel_all_muted(trace):
    print("\n\n########## SCENARIO 4: FUN_40083a7c (the 'for t in muted: soft_release(t)' loop) ##########")
    m = Mach(trace=trace, sounding=True, machine_type=0)
    m.uc.mem_write(0x460fab40, struct.pack(">I", 0x01))   # track 0 muted
    m.call(F["rel_all_muted"])
    m.report("FUN_40083a7c()  with _DAT_460fab40=0x01")


def scenario_poc_fix(trace):
    print("\n\n########## SCENARIO 5: PROPOSED FIX -- mute sets DAT_8000184c bit, next tick ramps ##########")
    print("  (detour at FUN_40083ab4: on phase==1, for each newly-muted sounding track do")
    print("   `DAT_8000184c |= 1<<track` (+ FUN_40008f84 for the release state flag))")
    m = Mach(trace=trace, sounding=True, machine_type=0)
    m.call(F["mute_set"], 0x10, 1)                          # 1. real mute (mask bit)
    m.calls.clear(); m.writes.clear()
    m.call(F["soft_release"], 0)                            # 2a. detour: release-state flag
    cur = m.uc.mem_read(0x8000184c, 1)[0]
    m.uc.mem_write(0x8000184c, bytes([cur | 0x01]))         # 2b. detour: arm the ramp for track 0
    m.calls.clear(); m.writes.clear()
    m.call(F["voice_upd"], 0, 0, 0, 0x100)                  # 3. next control-rate tick
    r = m.report("after mute + (DAT_8000184c|=1, FUN_40008f84(0)) + one FUN_400068e4 tick")
    print(f"\n  amp release ramp produced by the fix: {r}")


def scenario_commit_then_amp(trace):
    print("\n\n########## SCENARIO 6: commit mask (FUN_400834d8) then a tick -- does the amp get hard-zeroed? ##########")
    m = Mach(trace=trace, sounding=True, machine_type=0)
    m.call(F["mute_set"], 0x10, 1)
    m.calls.clear(); m.writes.clear()
    m.call(F["commit_mask"], 0, 1)                 # push _DAT_460fab40<<8 -> 46c803d4 / 46c7ff64
    d3d4 = struct.unpack(">H", m.rd(0x46c803d4, 2))[0]
    ff64 = struct.unpack(">H", m.rd(0x46c7ff64, 2))[0]
    m.report(f"FUN_400834d8(_,1)  -> _DAT_46c803d4={d3d4:#06x} _DAT_46c7ff64={ff64:#06x}")
    m.calls.clear(); m.writes.clear()
    m.call(F["voice_upd"], 0, 0, 0, 0x100)
    r = m.report("then FUN_400068e4(0,...) one tick -- watch AMPseg / framelvl for a jump-to-0")
    print(f"\n  release fingerprint after commit-only path: {r}  (False => hard gate, no ramp)")


FB_LOOP  = 0x4000c87c   # frame_builder per-track resolution loop, top
FB_DONE  = 0x4000ca94   # first insn after the loop (before the EMAC frame assembly)

def scenario_framebuilder(trace):
    print("\n\n########## SCENARIO 7: frame_builder per-track loop -- what command does a")
    print("##########             muted, still-sounding sustained voice get resolved to? ##########")
    for lbl, ff64, s184e, slot in [
        ("unmuted (baseline)",          0x0000, 0x0000, 0x2020 | 0x100),
        ("mute committed (46c7ff64 b8)", 0x0100, 0x0000, 0x2020 | 0x100),
        ("silenced set (8000184e b8)",   0x0000, 0x0100, 0x2020 | 0x100),
        ("mute committed, slot nibble 0",0x0100, 0x0000, 0x0020 | 0x100),
    ]:
        m = Mach(trace=trace, sounding=True, machine_type=0)
        uc = m.uc
        # a sounding *sustained* voice on track 0
        uc.mem_write(0x800017b8 + 0, bytes([1]))            # *pcVar11 != 0
        uc.mem_write(0x800017f6 + 0, bytes([0x00]))         # per-track byte OR'd into uVar10
        uc.mem_write(0x46c7faa4 + 0*4, struct.pack(">I", slot))     # refresh slot0, bit0x20 active
        uc.mem_write(0x46c8028e + 0, bytes([0]))            # bank ctx
        uc.mem_write(0x46c7ff66 + 0, bytes([0]))            # pattern ctx
        uc.mem_write(0x8000183a + 0, bytes([0]))            # lastbank cache -- match => write path
        uc.mem_write(0x80001842 + 0, bytes([0]))            # lastpat cache
        uc.mem_write(0x80001828, bytes([0])); uc.mem_write(0x80001829, bytes([0]))
        uc.mem_write(0x46c7ff64, struct.pack(">H", ff64))
        uc.mem_write(0x8000184e, struct.pack(">H", s184e))
        uc.mem_write(0x46104d26, b"\xee\xee" * 8)           # sentinel in resolved-cmd array
        for i in range(8):
            uc.mem_write(0x46c7e9fa + i*4, struct.pack(">I", 0))   # no mailbox cmds
        m.run_at(FB_LOOP, FB_DONE)
        m.report(f"frame_builder loop -- {lbl}")
        rc = struct.unpack(">8H", m.rd(0x46104d26, 16))
        s184e_after = struct.unpack(">H", m.rd(0x8000184e, 2))[0]
        print(f"  RESOLVEDCMD_46104d26[0..7] = {[hex(x) for x in rc]}")
        print(f"  trk0 resolved = {rc[0]:#06x}  (0xee.. = untouched; high nibble 0xf = STOP)")
        print(f"  _DAT_8000184e after = {s184e_after:#06x}")


def scenario_release_honors_env(trace):
    print("\n\n########## SCENARIO 8: does FUN_40008f84 (gate-release) honor the AMP env, ")
    print("##########             or is it a fixed fast fade like DAT_8000184c? ##########")

    # (a) gate-release only: FUN_40008f84(0) -> frame_builder -> FUN_400068e4
    m = Mach(trace=trace, sounding=True, machine_type=0)
    uc = m.uc
    uc.mem_write(0x800017b8 + 0, bytes([1]))
    uc.mem_write(0x46c7faa4 + 0*4, struct.pack(">I", 0x2020 | 0x100))
    uc.mem_write(0x8000183a + 0, bytes([0])); uc.mem_write(0x80001842 + 0, bytes([0]))
    uc.mem_write(0x46c7fe00 + 0, bytes([0])); uc.mem_write(0x46c7fe00 + 1, bytes([0]))
    uc.mem_write(0x46104d15 + 0, bytes([0xee]))
    m.call(F["soft_release"], 0)
    a = m.rd(0x8000184a, 1)[0]; rp = struct.unpack(">I", m.rd(0x46c7dfba, 4))[0]
    print(f"\n  FUN_40008f84(0): DAT_8000184a={a:#04x}  relparam_46c7dfba[0]={rp:#x} (0x2d=45)")
    m.calls.clear(); m.writes.clear()
    m.run_at(FB_LOOP, FB_DONE)
    print("  -- frame_builder loop after gate-release --")
    for pc, ad, sz, v, rn in m.writes:
        print(f"    pc={pc:#010x} [{rn}] <- {v:#0{2+sz*2}x}")
    cmd = m.rd(0x46104d15, 1)[0]
    a_after = m.rd(0x8000184a, 1)[0]
    print(f"    resolved cmd byte 46104d15[0] = {cmd:#04x}   (bit 0x10 set => gate-release to DSP)")
    print(f"    DAT_8000184a after = {a_after:#04x}   (frame_builder clears it after emitting once)")
    m.calls.clear(); m.writes.clear()
    m.call(F["voice_upd"], 0, 0, 0, 0x100)
    rel = any(v in RELEASE_FINGERPRINT for _, _, _, v, _ in m.writes)
    print(f"  -- FUN_400068e4 tick after gate-release --")
    print(f"    fixed 0xf0000000 fast-fade slopes written: {rel}")
    print(f"    => {'FIXED FAST FADE (env ignored)' if rel else 'NO fixed fade -- DSP left to run the AMP envelope RELEASE'}")

    print("\n  ---- interpretation ----")
    print("  DAT_8000184a path (FUN_40008f84)  -> frame_builder emits cmd|0x10 (note-off) -> DSP")
    print("     runs the voice's AMP envelope RELEASE stage = the user's REL knob is honored.")
    print("  DAT_8000184c path (FUN_40008fe4 / STOP) -> FUN_400068e4 writes 0xf0000000 slopes")
    print("     = hard ~ms declick fade, AMP env ignored.")


def scenario_stock_mute_real(trace):
    print("\n\n########## SCENARIO 9: STOCK mute with the mute-quantize state the real key")
    print("##########             handler sets (_DAT_460d10d4=1) -- what does FUN_400836d8 emit? ##########")
    for mt, lbl in [(0, "FLEX"), (1, "STATIC")]:
        for qd4, qd8, qlbl in [(1, 0, "d4=1 (MUTE handler)"), (0, 1, "d8=1"), (0, 0, "all 0 (my old S1 -- wrong)")]:
            m = Mach(trace=trace, sounding=True, machine_type=mt, qmode10d4=qd4, qmode10d8=qd8)
            m.uc.mem_write(0x460fab40, struct.pack(">I", 0x01))   # track 0 already in mute mask
            # FUN_400042b4 needs voice state so it returns a plausible current nibble
            m.uc.mem_write(0x80004f1e + 0*0x54, bytes([1]))
            m.uc.mem_write(0x80004f1c + 0*0x54 - 2 + 6, bytes([0]))
            m.call(F["apply_mask"], -1, 1)     # FUN_400836d8(arg, phase=1)
            m.report(f"FUN_400836d8(_,1)  machine={lbl}  qmode {qlbl}")
            for nm, a, d in m.calls:
                if nm in ("voice_cmd", "v_5030", "soft_release"):
                    print(f"    >>> {nm}(track={a[0]}, cmd={a[1]:#x}, {a[2]})")


def scenario_full_keyhandler(trace):
    print("\n\n########## SCENARIO 10: full CUE/MUTE/SOLO key handlers end-to-end -- which one")
    print("##########             sets _DAT_46c7ff64 (the FX-tail-killing DSP output mute)? ##########")
    for fn, lbl in [("mh_a6c", "FUN_40030a6c"), ("mh_c60", "FUN_40030c60"), ("mh_e6c", "FUN_40030e6c")]:
        m = Mach(trace=trace, sounding=True, machine_type=0)
        uc = m.uc
        uc.mem_write(0x460fab40, struct.pack(">I", 0x01))    # track 0 in the mute mask
        uc.mem_write(0x80004f1e + 0*0x54, bytes([1]))
        for g in (0x460d10cc, 0x460d10e2, 0x460d10e6, 0x46c8d18c, 0x460d1684):
            uc.mem_write(g, struct.pack(">I", 0))
        m.call(fn if fn in F else F.get(fn), 0x10, 1, limit=4_000_000) if False else None
        m.call(F[fn], 0x10, 1, limit=4_000_000)
        d3d4 = struct.unpack(">H", m.rd(0x46c803d4, 2))[0]
        ff64 = struct.unpack(">H", m.rd(0x46c7ff64, 2))[0]
        s184a = m.rd(0x8000184a, 1)[0]; s184e = struct.unpack(">H", m.rd(0x8000184e, 2))[0]
        s184c = m.rd(0x8000184c, 1)[0]
        m.report(f"{lbl}(0x10, 1)  -- mute track 0, sounding FLEX voice")
        for nm, a, d in m.calls:
            if nm in ("voice_cmd", "v_5030", "soft_release", "soft_rel_184c", "cc_out"):
                print(f"    >>> {nm}({a[0]}, {a[1]:#x}, {a[2]})")
        print(f"  POST: 46c803d4={d3d4:#06x}  46c7ff64={ff64:#06x}  8000184a={s184a:#04x}  "
              f"8000184e={s184e:#06x}  8000184c={s184c:#04x}")


def scenario_patch(trace):
    print("\n\n########## SCENARIO 11: patch_softmute V2 (REPLACE) -- stock vs patched ##########")
    print("  V2: on mute, note-off (FUN_40008f84) + silenced bit + DELETE the stock 0x?040/0x?010")
    print("      mailbox command (the `post` wrap zeros 46c7e9fa[t] / 800018be[t]).")
    for mt, lbl in [(0, "FLEX"), (1, "STATIC")]:
        for patched in (False, True):
            m = Mach(trace=trace, sounding=True, machine_type=mt, qmode10d4=1,
                     patch=SOFTMUTE_PATCH if patched else None, softmute=patched)
            uc = m.uc
            uc.mem_write(0x460fab40, struct.pack(">I", 0x01))       # track 0 muted
            uc.mem_write(0x80004f1e + 0*0x54, bytes([1]))
            uc.mem_write(0x800017b8 + 0, bytes([1]))
            uc.mem_write(0x46c7faa4 + 0, struct.pack(">I", 0x2020 | 0x100))
            uc.mem_write(0x8000183a, b"\0"); uc.mem_write(0x80001842, b"\0")
            uc.mem_write(0x46c7fe00, b"\0\0"); uc.mem_write(0x46104d26, b"\xee\xee" * 8)
            for i in range(8):
                uc.mem_write(0x46c7e9fa + i*4, struct.pack(">I", 0))
                uc.mem_write(0x800018be + i*4, struct.pack(">I", 0))
            m.call(F["apply_mask"], -1, 1, limit=3_000_000)
            tag = "PATCHED" if patched else "stock  "
            mba = struct.unpack(">I", m.rd(0x46c7e9fa, 4))[0]
            s184a = m.rd(0x8000184a, 1)[0]
            mask = struct.unpack(">I", m.rd(0x460fab40, 4))[0]
            m.calls.clear(); m.writes.clear()
            m.run_at(0x4000c87c, 0x4000ca94)
            rc = struct.unpack(">8H", m.rd(0x46104d26, 16))[0]
            print(f"  {lbl:6} [{tag}]  46c7e9fa[0]={mba:#06x}  8000184a={s184a:#04x}  "
                  f"460fab40={mask:#04x}  -> frame_builder RESOLVEDCMD[0]={rc:#06x}"
                  f"   {'HARD-CUT CMD' if rc not in (0xeeee,) else 'nothing (decays)'}")
    # unmute round-trip
    m = Mach(trace=trace, sounding=True, machine_type=0, qmode10d4=1,
             patch=SOFTMUTE_PATCH, softmute=True)
    m.uc.mem_write(0x460fab40, struct.pack(">I", 0x01)); m.uc.mem_write(0x80004f1e, bytes([1]))
    m.call(F["apply_mask"], -1, 1); s1 = struct.unpack(">H", m.rd(0x8000184e, 2))[0]
    m.uc.mem_write(0x460fab40, struct.pack(">I", 0x00))
    m.calls.clear(); m.writes.clear()
    m.call(F["apply_mask"], -1, 0); s2 = struct.unpack(">H", m.rd(0x8000184e, 2))[0]
    print(f"\n  unmute round-trip: 8000184e mute={s1:#06x} -> unmute={s2:#06x}"
          f"   {'(cleared OK)' if s2 == 0 else '<<< NOT CLEARED'}")


if __name__ == "__main__":
    trace = "--trace" in sys.argv
    scenario_mute_vs_release(trace)
    scenario_arranger(trace)
    scenario_voice_updater(trace)
    scenario_rel_all_muted(trace)
    scenario_poc_fix(trace)
    scenario_commit_then_amp(trace)
    scenario_framebuilder(trace)
    scenario_release_honors_env(trace)
    scenario_stock_mute_real(trace)
    scenario_full_keyhandler(trace)
    scenario_patch(trace)
