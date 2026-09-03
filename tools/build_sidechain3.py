#!/usr/bin/env python3
# SPDX-License-Identifier: MIT
# SPDX-FileCopyrightText: 2026 Zachary Fancher ("Kyoti")
"""
SIDE-CHAIN COMPRESSOR -- step 3 MENU SCAFFOLDING (no DSP).

Stock 1.40C + the Bug-1 fix + the FULL side-chain control surface on the
COMPRESSOR effect's page 2 -- KEY, KEY FLT, KEY GAIN, SC LISTEN -- with none of
the DSP hooks.  The knobs do nothing audible; this build exists to prove, on
real hardware, that all four parameters render, count, format, p-lock and
survive save/recall, and that the page-2 layout reads right -- independently of
whether the step-2 DSP framework (build_sidechain2.py) needs tweaking.

COMPRESSOR parameter descriptor (E = 0x400d5a4a), page-2 slots 6..11
(octabam packing: slot 6->r6+$c hi, 7->r6+$c lo, 8->r6+$d hi, 9->r6+$d lo,
10->r6+$e hi, 11->r6+$e lo):

  slot  6  RMS        (stock, untouched)
  slot  7  ---        (blank -> a stock-normal page-2 gap, cf. CHORUS/EQ)
  slot  8  KEY        count 5   OFF / T1..T4 or T5..T8   (key_fmt)
  slot  9  KFLT       count 128 default 64  LP / OFF / HP (kfilt_fmt)
  slot 10  KGAIN      count 128 default 64  bipolar -N/+N (stock 0x4003c7a0)
  slot 11  MON        count 2   default 0   OFF / ON       (stock 0x4003c14c)

Usage:   python3 tools/build_sidechain3.py [VERSTR]      (default "140C_KYOTI")
Outputs: out/mainos_sidechain3.bin, out/elek_sidechain3.bin,
         out/OCTATRACK_OS1.40C_SIDECHAIN3.syx, out/OCTATRACK_SIDECHAIN3.bin
"""
import os, pathlib, subprocess, sys

BASE = 0x40000400
ROOT = pathlib.Path(__file__).resolve().parent.parent
STOCK_SECT = ROOT / "out/raw/section_3_MAIN_OS.bin"
STOCK_SYX = ROOT / "downloads/extracted/OCTATRACK_OS1.40C.syx"
EFT = ROOT / "vendor/elektron-firmware-tool/elektron-firmware-tool"
OUT = ROOT / "out/mainos_sidechain3.bin"
ELEK = ROOT / "out/elek_sidechain3.bin"
OUT_SYX = ROOT / "out/OCTATRACK_OS1.40C_SIDECHAIN3.syx"
OUT_BIN = ROOT / "out/OCTATRACK_SIDECHAIN3.bin"
VERSTR = sys.argv[1] if len(sys.argv) > 1 else "140C_KYOTI"

PATCHES = [
    ("patch_trigscale", 0x400d7b00, [(0x4009b6f2, "cave", "203c0000091a", 18, "jmp")]),
    ("patch_sidechain", 0x400d7000, []),
]
FREE_END = 0x400d7c3c

E = 0x400d5a4a
FMT_BIPOLAR = 0x4003c7a0        # stock: "-N" / "+N" about centre 64
FMT_ONOFF = 0x4003c14c          # stock: "ON" / "OFF"

# (slot, name, count, default, formatter-key, tag)
#   formatter-key: "key_fmt" / "kfilt_fmt" from the cave, or a literal address, or None
PARAMS = [
    (8,  b"KEY\x00\x00\x00",  5,   0,  "key_fmt",     "KEY  (OFF / same-bank track)"),
    (9,  b"KFLT\x00\x00",     128, 64, "kfilt_fmt",   "KEY FLT  (LP / OFF / HP)"),
    (10, b"KGAIN\x00",        128, 64, FMT_BIPOLAR,   "KEY GAIN  (-N / +N about unity)"),
    (11, b"MON\x00\x00\x00",  2,   0,  FMT_ONOFF,     "SC LISTEN  (OFF / ON)"),
]
# per-slot current bytes we assert before writing
SLOT_CUR = {
    8:  dict(name="000000000000", cnt="00000080", dflt="7f", b="00000000"),
    9:  dict(name="000000000000", cnt="00000002", dflt="00", b="400475f8"),
    10: dict(name="000000000000", cnt="00000080", dflt="00", b="00000000"),
    11: dict(name="000000000000", cnt="00000080", dflt="00", b="00000000"),
}


def jmp(t):
    return b"\x4e\xf9" + t.to_bytes(4, "big")


def cf_jsr(t):
    return b"\x4e\xb9" + t.to_bytes(4, "big")


def assemble(name, at):
    subprocess.run(["m68k-elf-as", "-mcpu=5407", "-o", f"out/{name}.o", f"tools/{name}.s"],
                   check=True, cwd=ROOT)
    subprocess.run(["m68k-elf-ld", f"-Ttext=0x{at:x}", "-o", f"out/{name}.elf", f"out/{name}.o"],
                   check=True, cwd=ROOT, capture_output=True)
    subprocess.run(["m68k-elf-objcopy", "-O", "binary", f"out/{name}.elf", f"out/{name}.bin"],
                   check=True, cwd=ROOT)
    nm = subprocess.run(["m68k-elf-nm", f"out/{name}.elf"], capture_output=True, text=True).stdout
    syms = {p[2]: int(p[0], 16) for p in (l.split() for l in nm.splitlines()) if len(p) == 3}
    return (ROOT / f"out/{name}.bin").read_bytes(), syms


def main():
    if not STOCK_SECT.exists():
        sys.exit(f"missing {STOCK_SECT}")
    img = bytearray(STOCK_SECT.read_bytes())
    stock = bytes(img)

    def o(a):
        return a - BASE

    print("=== caves + detours ===")
    syms = {}
    spans = []
    for name, at, detours in PATCHES:
        blob, s = assemble(name, at)
        syms[name] = s
        co = o(at)
        if any(img[co:co + len(blob)]):
            sys.exit(f"cave 0x{at:08x} ({name}) not free")
        spans.append((at, at + len(blob)))
        img[co:co + len(blob)] = blob
        print(f"  {name:16s} {len(blob):3d} B @ 0x{at:08x}")
        for site, sym, exp, n, kind in detours:
            exp = bytes.fromhex(exp)
            do = o(site)
            if bytes(img[do:do + len(exp)]) != exp:
                sys.exit(f"detour 0x{site:08x} unexpected: {bytes(img[do:do+len(exp)]).hex()}")
            img[do:do + n] = jmp(s[sym]) + b"\x4e\x71" * ((n - 6) // 2)
            print(f"    0x{site:08x} -> {name}:{sym} 0x{s[sym]:08x}")
    if max(b for _, b in spans) > FREE_END:
        sys.exit("cave past free zone")
    fmt_addr = {"key_fmt": syms["patch_sidechain"]["key_fmt"],
                "kfilt_fmt": syms["patch_sidechain"]["kfilt_fmt"]}
    print(f"  key_fmt=0x{fmt_addr['key_fmt']:08x}  kfilt_fmt=0x{fmt_addr['kfilt_fmt']:08x}")

    print("\n=== COMPRESSOR descriptor: KEY / KFLT / KGAIN / MON ===")
    for slot, name, cnt, dflt, fmt, tag in PARAMS:
        cur = SLOT_CUR[slot]
        na, ca, da, aa, ba = (E + 0x4e + 6 * slot, E + 0xd2 + 4 * slot, E + 0x96 + slot,
                              E + 0x102 + 4 * slot, E + 0x132 + 4 * slot)
        assert bytes(img[o(na):o(na) + 6]).hex() == cur["name"], f"slot {slot} name not blank"
        assert f"{int.from_bytes(img[o(ca):o(ca)+4],'big'):08x}" == cur["cnt"], f"slot {slot} count"
        assert f"{img[o(da)]:02x}" == cur["dflt"], f"slot {slot} default"
        assert f"{int.from_bytes(img[o(ba):o(ba)+4],'big'):08x}" == cur["b"], f"slot {slot} B"
        assert int.from_bytes(img[o(aa):o(aa) + 4], "big") == 0, f"slot {slot} A not zero"
        img[o(na):o(na) + 6] = name
        img[o(ca):o(ca) + 4] = cnt.to_bytes(4, "big")
        img[o(da)] = dflt
        img[o(ba):o(ba) + 4] = (0).to_bytes(4, "big")
        a_val = fmt_addr[fmt] if isinstance(fmt, str) else fmt
        img[o(aa):o(aa) + 4] = a_val.to_bytes(4, "big")
        label = name.split(b"\x00")[0].decode()
        print(f"  slot {slot:2d}: {label:5s}  count {cnt:3d}  default {dflt:3d}  "
              f"A 0x{a_val:08x}   {tag}")

    OUT.write_bytes(bytes(img))
    changed = sum(1 for a, b_ in zip(stock, img) if a != b_)
    print(f"\n  {OUT.name}: {changed} bytes changed vs stock")

    ts = ROOT / "out/mainos_trigscale_only.bin"
    if ts.exists():
        tsb = ts.read_bytes()
        tsh = [i for i, (x, y) in enumerate(zip(stock, tsb)) if x != y]
        ok = all(img[i] == tsb[i] for i in tsh)
        print(f"  manual-trig fix identical to build_trigscale_only.py: {ok}")
        if not ok:
            sys.exit("MANUAL-TRIG FIX DIVERGED")

    if not EFT.exists() or not STOCK_SYX.exists():
        print("\n  (EFT / stock syx missing -- skipping wrap)")
        return
    print("\n=== wrap ===")
    env = dict(os.environ, EFT_EMIT_CONTAINER=str(ELEK))
    r = subprocess.run([str(EFT), "-i", str(STOCK_SYX), "-c", "3", str(OUT),
                        "-V", VERSTR, "-o", str(OUT_SYX)], capture_output=True, text=True, env=env, cwd=ROOT)
    print("  " + "\n  ".join(l for l in r.stdout.splitlines()
                             if any(k in l for k in ("version", "emitted", "wrote", "checksum", "round-trip"))))
    if "too long" in r.stdout:
        sys.exit(f'version "{VERSTR}" does not fit')
    subprocess.run(["python3", "tools/make_bin.py", str(ELEK), "-o", str(OUT_BIN)], check=True, cwd=ROOT)
    print(f"\n  {OUT_SYX.name}  +  {OUT_BIN.name}")
    print("  Test: COMPRESSOR on any track -> FX page 2. Encoders after RMS:")
    print("        (gap) KEY  KFLT  KGAIN  MON.  All inert (no DSP). Check labels,")
    print("        counts, p-locks, save/reload, and that RMS + page 1 are unchanged.")
    print("  Revert = flash downloads/extracted/OCTATRACK_OS1.40C.syx")


if __name__ == "__main__":
    main()
