#!/usr/bin/env python3
"""
Bank paging — Stage 1 build: R11 + redirected sibling bank load, crudely
triggered by the RELOAD BANK gesture. Loads the 15 non-playing banks from
"<current>_2" into RAM without stopping audio. See tools/patch_bankpage_s1.s.

Needs a sibling project "<current>_2" on the card sharing the sample pool.

    python3 tools/build_bankpage_s1.py   # -> out/mainos_s1.bin (from R11 out/mainos.bin)
"""
import pathlib, subprocess, sys

BASE = 0x40000400
CAVE_AT = 0x400d7400                      # free, just past the R11 arp cave (ends 0x400d7223)
R11 = pathlib.Path("out/mainos.bin")
OUT = pathlib.Path("out/mainos_s1.bin")

# detour site -> (cave symbol, expected original-byte hex)
DETOURS = [(0x40025244, "gate_cave", "41f9100f8378"),  # FUN_40025230 projname default
           (0x40063bfe, "trig_cave", "4eb9400a10c8"),  # FUN_40063bf8 `jsr FUN_400a10c8`
           (0x400239a2, "done_cave", "4ebaff004878")]  # FUN_40023998 re-sync + pea


def off(a):
    return a - BASE


def main():
    if not R11.exists():
        sys.exit(f"missing {R11} — run tools/build.py first (R11)")
    img = bytearray(R11.read_bytes())

    subprocess.run(["m68k-elf-as", "-mcpu=5407", "-o", "out/patch_bankpage_s1.o",
                    "tools/patch_bankpage_s1.s"], check=True)
    subprocess.run(["m68k-elf-ld", f"-Ttext=0x{CAVE_AT:x}", "-o", "out/patch_bankpage_s1.elf",
                    "out/patch_bankpage_s1.o"], capture_output=True)
    subprocess.run(["m68k-elf-objcopy", "-O", "binary", "out/patch_bankpage_s1.elf",
                    "out/patch_bankpage_s1.bin"], check=True)
    nm = subprocess.run(["m68k-elf-nm", "out/patch_bankpage_s1.elf"], capture_output=True, text=True).stdout
    sym = {p[2]: int(p[0], 16) for p in (l.split() for l in nm.splitlines()) if len(p) == 3}
    blob = pathlib.Path("out/patch_bankpage_s1.bin").read_bytes()
    print(f"cave {len(blob)} B @ 0x{CAVE_AT:08x} .. 0x{CAVE_AT+len(blob)-1:08x}")

    if any(img[off(CAVE_AT):off(CAVE_AT) + len(blob)]):
        sys.exit(f"cave at 0x{CAVE_AT:08x} is NOT free in R11 image")
    img[off(CAVE_AT):off(CAVE_AT) + len(blob)] = blob

    for site, s, exp in DETOURS:
        o = off(site)
        if not bytes(img[o:o + len(exp) // 2]).hex().startswith(exp):
            sys.exit(f"detour 0x{site:08x} unexpected: {bytes(img[o:o+6]).hex()} (want {exp})")
        img[o:o + 6] = b"\x4e\xf9" + sym[s].to_bytes(4, "big")
        print(f"  detour 0x{site:08x} -> {s} 0x{sym[s]:08x}")

    OUT.write_bytes(bytes(img))
    d = sum(1 for x, y in zip(R11.read_bytes(), img) if x != y)
    print(f"\n{OUT}: {len(img):,} bytes, {d} changed vs R11")


if __name__ == "__main__":
    main()
