#!/usr/bin/env python3
"""
Experimental build: R11 + a throwaway hook that makes any RELOAD-BANK gesture
reload a NON-playing bank (current+1) instead of the current one, to test on
hardware whether a background CF bank-load glitches the audio while the
sequencer runs. See tools/patch_exp_bankload.s.

    python3 tools/build_exp.py   # -> out/mainos_exp.bin  (patched from out/mainos.bin = R11)
"""
import pathlib, subprocess, sys

BASE = 0x40000400
EXP_AT = 0x400d7400                      # free, just past the R11 arp cave (ends 0x400d7223)
R11 = pathlib.Path("out/mainos.bin")     # produced by tools/build.py
OUT = pathlib.Path("out/mainos_exp.bin")
DETOUR = 0x40063bfe                       # FUN_40063bf8: the `jsr FUN_400a10c8` (pre-step)
EXPECT = "4eb9400a10c8"                    # jsr 0x400a10c8  (skipped by the hook)


def off(a):
    return a - BASE


def main():
    if not R11.exists():
        sys.exit(f"missing {R11} — run tools/build.py first (R11)")
    img = bytearray(R11.read_bytes())

    subprocess.run(["m68k-elf-as", "-mcpu=5407", "-o", "out/patch_exp_bankload.o",
                    "tools/patch_exp_bankload.s"], check=True)
    subprocess.run(["m68k-elf-ld", f"-Ttext=0x{EXP_AT:x}", "-o", "out/patch_exp_bankload.elf",
                    "out/patch_exp_bankload.o"], capture_output=True)
    subprocess.run(["m68k-elf-objcopy", "-O", "binary", "out/patch_exp_bankload.elf",
                    "out/patch_exp_bankload.bin"], check=True)
    nm = subprocess.run(["m68k-elf-nm", "out/patch_exp_bankload.elf"], capture_output=True, text=True).stdout
    sym = {p[2]: int(p[0], 16) for p in (l.split() for l in nm.splitlines()) if len(p) == 3}
    blob = pathlib.Path("out/patch_exp_bankload.bin").read_bytes()
    exp = sym["exp_cave"]
    print(f"exp_cave {len(blob)} B @ 0x{exp:08x} .. 0x{exp+len(blob)-1:08x}")

    if any(img[off(EXP_AT):off(EXP_AT) + len(blob)]):
        sys.exit(f"cave at 0x{EXP_AT:08x} is NOT free in R11 image")
    img[off(EXP_AT):off(EXP_AT) + len(blob)] = blob

    o = off(DETOUR)
    if not bytes(img[o:o + 6]).hex().startswith(EXPECT):
        sys.exit(f"detour 0x{DETOUR:08x} unexpected: {bytes(img[o:o+6]).hex()} (want {EXPECT})")
    img[o:o + 6] = b"\x4e\xf9" + exp.to_bytes(4, "big")
    print(f"detour 0x{DETOUR:08x} -> exp_cave 0x{exp:08x}  (mask forced to non-current bank)")

    # v3: also skip the end-of-load re-sync FUN_400238a4 (delayed audio cut).
    # jsr 0x400238a4 at 0x400239a2 (4 bytes) -> two NOPs. Return unused, no args.
    RESYNC_AT, RESYNC_EXP = 0x400239a2, "4ebaff00"
    o = off(RESYNC_AT)
    if bytes(img[o:o + 4]).hex() != RESYNC_EXP:
        sys.exit(f"re-sync call 0x{RESYNC_AT:08x} unexpected: {bytes(img[o:o+4]).hex()} (want {RESYNC_EXP})")
    img[o:o + 4] = b"\x4e\x71\x4e\x71"
    print(f"skip   0x{RESYNC_AT:08x}  jsr FUN_400238a4 -> nop nop  (end-of-load re-sync)")

    OUT.write_bytes(bytes(img))
    d = sum(1 for x, y in zip(R11.read_bytes(), img) if x != y)
    print(f"\n{OUT}: {len(img):,} bytes, {d} changed vs R11")


if __name__ == "__main__":
    main()
