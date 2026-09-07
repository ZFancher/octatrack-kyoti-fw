#!/usr/bin/env python3
# SPDX-License-Identifier: MIT
# SPDX-FileCopyrightText: 2026 Zac-Kyoti
"""
Side-chain step-3 DSP coefficient tables -- shared by build_sidechain3.py and
emu_sc_dsp3.py so the two never drift.

Both tables are 24-bit words appended to the assembled cave (patch_sc_dsp3.asm)
by the build; the .asm reads them with `move p:(r1+n1),x1` at @GTAB@ / @FTAB@.

KEY GAIN  (16 entries, index = KGAIN >> 3, KGAIN 0..127, 64 = unity)
  stored value = (gain / 64) in Q23, so the cave does  mpy ; asl #6  -> * gain.
  gain law: dB = (KGAIN - 64) * (24 / 64)  ->  ~ +/-24 dB, ~3 dB per index step.

KEY FLT  (32 entries, exp-spaced cutoff FC_LO..FC_HI)
  stored value = f = 2 * sin(pi * fc / FS)  in Q23  (Chamberlin SVF tuning coef,
  damping q = 1.0 so the cave needs no q multiply).
  LP: index = KFLT >> 1            (KFLT 0..63  -> fc FC_LO..FC_HI, 0 = tightest)
  HP: index = (KFLT - 64) >> 1     (KFLT 65..127 -> fc FC_LO..FC_HI)
"""
import math

FS = 44100.0
FC_LO = 40.0
FC_HI = 2200.0
Q23 = 1 << 23
GAIN_N = 16
FLT_N = 32


def _q23(x):
    v = int(round(x * Q23))
    return max(-Q23, min(Q23 - 1, v)) & 0xFFFFFF


def gain_table():
    t = []
    for idx in range(GAIN_N):
        kgain = idx * 8                      # bucket base (KGAIN >> 3 == idx)
        db = (kgain - 64) * (24.0 / 64.0)
        g = 10.0 ** (db / 20.0)
        t.append(_q23(g / 64.0))
    return t


def flt_table():
    t = []
    for idx in range(FLT_N):
        fc = FC_LO * (FC_HI / FC_LO) ** (idx / (FLT_N - 1))
        f = 2.0 * math.sin(math.pi * fc / FS)
        t.append(_q23(f))
    return t


def flt_cutoff_hz(idx):
    return FC_LO * (FC_HI / FC_LO) ** (idx / (FLT_N - 1))


if __name__ == "__main__":
    g, f = gain_table(), flt_table()
    print("KEY GAIN table (16):")
    for i, v in enumerate(g):
        db = (i * 8 - 64) * (24.0 / 64.0)
        print(f"  [{i:2d}] KGAIN~{i*8:3d}  {db:+6.1f} dB   0x{v:06x}")
    print("\nKEY FLT table (32):")
    for i, v in enumerate(f):
        print(f"  [{i:2d}] fc {flt_cutoff_hz(i):7.1f} Hz   f=0x{v:06x} ({v / Q23:.4f})")
