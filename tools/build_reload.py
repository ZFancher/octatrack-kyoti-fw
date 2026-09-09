#!/usr/bin/env python3
# SPDX-License-Identifier: MIT
# SPDX-FileCopyrightText: 2026 Zac-Kyoti
"""
RELOAD FROM PROJECT (NOTES.md "Session 42") -- stock 1.40C + the MIDI manual-trig
fix + a [PTN]+[NO] front-panel combo that reloads the ACTIVE pattern's SEQUENCE
DATA from the CF card (bankNN.strd) without stopping the sequencer.

  1. patch_trigscale  -- MIDI manual-trig stall fix.  Byte-identical detour + cave
                         to build_trigscale_only.py / build_directjump.py.
  2. patch_reload     -- two detours:
                           rl_combo @0x4005e25c  the NO handler.  [PTN]+[NO] press
                                                 -> arm {G_KIND=1, G_PAT=active},
                                                 post the type-0x14 storage job
                                                 (FUN_40022778(1<<curbank)), flash
                                                 "RELOAD SEQ", swallow NO.  Any
                                                 other NO press replays the
                                                 displaced prologue.
                           rl_job  @0x40085864  the storage task's type-0x14 case.
                                                 When G_KIND: load bankNN.strd into
                                                 a scratch bank region, memcpy the
                                                 one pattern slab into the live
                                                 blob, restore the scratch bank,
                                                 set 0x46c8028a (seamless reload),
                                                 rejoin the case's clean exit.
                                                 Inert when G_KIND == 0.

  No PERSONALIZE entry (no menu-array surgery), no persistent state (a one-shot
  action), so no 'ANDY' shadow / pea 0x64->0x70.

  UNFLASHED / UNVERIFIED: hooks are static + isolation-checked only.  The worker
  runs on FUN_4008445c and calls the same file primitives the stock type-6 case
  (FUN_400905d4) uses.  Needs `tools/emu_reload.py --patched` then a hardware pass.
  MVP: SEQ DATA only, active pattern only, transport running only.

Usage:   python3 tools/build_reload.py [VERSTR]      (default VERSTR = "140C_KYOTI")
Outputs: out/mainos_reload.bin, out/elek_reload.bin,
         out/OCTATRACK_OS1.40C_RELOAD.syx, out/OCTATRACK_RELOAD.bin
"""
import os, pathlib, subprocess, sys

BASE = 0x40000400
HERE = pathlib.Path(__file__).parent
ROOT = HERE.parent
STOCK_SECT = ROOT / "out/raw/section_3_MAIN_OS.bin"
STOCK_SYX = ROOT / "downloads/extracted/OCTATRACK_OS1.40C.syx"
EFT = ROOT / "vendor/elektron-firmware-tool/elektron-firmware-tool"
OUT = ROOT / "out/mainos_reload.bin"
ELEK = ROOT / "out/elek_reload.bin"
OUT_SYX = ROOT / "out/OCTATRACK_OS1.40C_RELOAD.syx"
OUT_BIN = ROOT / "out/OCTATRACK_RELOAD.bin"

VERSTR = sys.argv[1] if len(sys.argv) > 1 else "140C_KYOTI"

# (source, load addr, defsym, [(detour site, symbol, expected bytes, len, kind)])
PATCHES = [
    ("patch_trigscale", 0x400d7b00, None,
     [(0x4009b6f2, "cave", "203c0000091a", 18, "jmp")]),
    ("patch_reload", 0x400d7400, None,
     [(0x4005e25c, "rl_combo", "202f00086714", 6, "jmp"),   # NO handler: move.l 8(sp),d0 ; beq.s 0x4005e276
      (0x40085864, "rl_job", "2d4afd762f2a0004", 8, "jmp")]),  # 0x14 case: move.l a2,-650(fp) ; move.l 4(a2),-(sp)
]

FREE_END = 0x400d7c3c


def jmp(t):
    return b"\x4e\xf9" + t.to_bytes(4, "big")


def jsr(t):
    return b"\x4e\xb9" + t.to_bytes(4, "big")


def assemble(name, at, defsym):
    aso = ["m68k-elf-as", "-mcpu=5407"]
    for d in (defsym.split(",") if defsym else []):
        aso += ["--defsym", d]
    aso += ["-o", f"out/{name}.o", f"tools/{name}.s"]
    subprocess.run(aso, check=True, cwd=ROOT)
    subprocess.run(["m68k-elf-ld", f"-Ttext=0x{at:x}", "-o", f"out/{name}.elf", f"out/{name}.o"],
                   check=True, cwd=ROOT, capture_output=True)
    subprocess.run(["m68k-elf-objcopy", "-O", "binary", f"out/{name}.elf", f"out/{name}.bin"],
                   check=True, cwd=ROOT)
    nm = subprocess.run(["m68k-elf-nm", f"out/{name}.elf"], capture_output=True, text=True).stdout
    syms = {p[2]: int(p[0], 16) for p in (l.split() for l in nm.splitlines()) if len(p) == 3}
    return (ROOT / f"out/{name}.bin").read_bytes(), syms


def main():
    if not STOCK_SECT.exists():
        sys.exit(f"missing {STOCK_SECT} -- run ./fetch-os.sh and ./analyze.sh first")
    img = bytearray(STOCK_SECT.read_bytes())
    stock = bytes(img)

    def o(a):
        return a - BASE

    syms, spans = {}, []
    print("=== assemble + detour ===")
    for name, at, defsym, detours in PATCHES:
        blob, s = assemble(name, at, defsym)
        syms[name] = s
        co = o(at)
        if any(img[co:co + len(blob)]):
            sys.exit(f"cave 0x{at:08x} ({name}) not free: {bytes(img[co:co+16]).hex()}")
        spans.append((at, at + len(blob), name))
        img[co:co + len(blob)] = blob
        print(f"  {name:16s} {len(blob):3d} B @ 0x{at:08x} .. 0x{at+len(blob)-1:08x}")
        for site, sym, exp, n, kind in detours:
            exp = bytes.fromhex(exp)
            do = o(site)
            if bytes(img[do:do + len(exp)]) != exp:
                sys.exit(f"detour 0x{site:08x} ({name}:{sym}) unexpected: "
                         f"{bytes(img[do:do+len(exp)]).hex()} != {exp.hex()}")
            branch = jsr(s[sym]) if kind == "jsr" else jmp(s[sym])
            img[do:do + n] = branch + b"\x4e\x71" * ((n - 6) // 2)
            print(f"    0x{site:08x} -> {name}:{sym} 0x{s[sym]:08x}  ({kind}, {n} B)")

    spans.sort()
    for (a1, b1, n1), (a2, b2, n2) in zip(spans, spans[1:]):
        if b1 > a2:
            sys.exit(f"cave overlap: {n1} 0x{a1:x}..0x{b1:x} / {n2} 0x{a2:x}..0x{b2:x}")
    if spans[-1][1] > FREE_END:
        sys.exit(f"cave runs past the free zone end (0x{spans[-1][1]:x} > 0x{FREE_END:x})")
    print("  no overlaps; all within the free cave")

    OUT.write_bytes(bytes(img))
    changed = sum(1 for a, b in zip(stock, img) if a != b)
    print(f"\n  {OUT.name}: {changed} bytes changed vs stock")

    ts = ROOT / "out/mainos_trigscale_only.bin"
    if ts.exists():
        tsb = ts.read_bytes()
        tsh = [i for i, (x, y) in enumerate(zip(stock, tsb)) if x != y]
        ok = all(img[i] == tsb[i] for i in tsh)
        print(f"  manual-trig fix bytes identical to build_trigscale_only.py: {ok}")
        if not ok:
            sys.exit("  MANUAL-TRIG FIX DIVERGED")

    if not EFT.exists() or not STOCK_SYX.exists():
        print("\n  (EFT tool or stock syx missing -- skipping the .syx/.bin wrap)")
        return
    print("\n=== wrap ===")
    env = dict(os.environ, EFT_EMIT_CONTAINER=str(ELEK))
    r = subprocess.run([str(EFT), "-i", str(STOCK_SYX), "-c", "3", str(OUT),
                        "-V", VERSTR, "-o", str(OUT_SYX)], capture_output=True, text=True, env=env, cwd=ROOT)
    print("  " + "\n  ".join(l for l in r.stdout.splitlines()
                             if "version" in l or "emitted" in l or "wrote" in l))
    if "too long" in r.stdout:
        sys.exit(f'  version string "{VERSTR}" ({len(VERSTR)}) does not fit the 10-char field')
    subprocess.run(["python3", "tools/make_bin.py", str(ELEK), "-o", str(OUT_BIN)], check=True, cwd=ROOT)

    print(f"\n  {OUT_SYX.name}  (MIDI DIN)  +  {OUT_BIN.name}  (CF card)")
    print(f"  version screen / SYSTEM STATUS -> OS VERSION will read:  {VERSTR}")
    print("  [PTN] + [NO]  (while playing)  ->  reloads the active pattern's sequence data")
    print("  from the CF card, no transport stop.  Flashes \"RELOAD SEQ\".")
    print("  Revert = flash downloads/extracted/OCTATRACK_OS1.40C.syx")


if __name__ == "__main__":
    main()
