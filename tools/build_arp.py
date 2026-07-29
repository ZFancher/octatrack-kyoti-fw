#!/usr/bin/env python3
"""
Build a STANDALONE stock+arp-scales image for isolated testing.

Adds Greek modes + blues + phrygian-dominant + melodic-minor + octatonic +
hirajoshi to the arpeggiator F-knob key scale. Everything else is stock; the
extra scales only appear if you scroll the F knob past OFF/MAJ/MIN, so default
behaviour is unchanged.

    python3 tools/build_arp.py   # -> out/mainos_arp.bin
"""
import pathlib, subprocess, sys

BASE = 0x40000400
ARP_AT = 0x400d7000                      # inside the proven-free R10 cave run, clear of R10's stubs
STOCK = pathlib.Path("out/raw/section_3_MAIN_OS.bin")
OUT = pathlib.Path("out/mainos_arp.bin")


def off(a):
    return a - BASE


def jmp(t):
    return b"\x4e\xf9" + t.to_bytes(4, "big")


def assemble(name, at):
    subprocess.run(["m68k-elf-as", "-mcpu=5407", "-o", f"out/{name}.o", f"tools/{name}.s"], check=True)
    subprocess.run(["m68k-elf-ld", f"-Ttext=0x{at:x}", "-o", f"out/{name}.elf", f"out/{name}.o"],
                   capture_output=True)
    subprocess.run(["m68k-elf-objcopy", "-O", "binary", f"out/{name}.elf", f"out/{name}.bin"], check=True)
    nm = subprocess.run(["m68k-elf-nm", f"out/{name}.elf"], capture_output=True, text=True).stdout
    return (pathlib.Path(f"out/{name}.bin").read_bytes(),
            {p[2]: int(p[0], 16) for p in (l.split() for l in nm.splitlines()) if len(p) == 3})


def main():
    if not STOCK.exists():
        sys.exit(f"missing {STOCK} — run ./fetch-os.sh and ./analyze.sh first")
    img = bytearray(STOCK.read_bytes())

    blob, syms = assemble("patch_arp", ARP_AT)
    end = ARP_AT + len(blob)
    print(f"patch_arp: {len(blob)} B @ 0x{ARP_AT:08x} .. 0x{end-1:08x}")
    print(f"  decode_cave 0x{syms['decode_cave']:08x}  lookup_cave 0x{syms['lookup_cave']:08x}  "
          f"fmt_cave 0x{syms['fmt_cave']:08x}")

    if any(img[off(ARP_AT):off(end)]):
        sys.exit(f"cave at 0x{ARP_AT:08x} is NOT free")
    img[off(ARP_AT):off(end)] = blob
    print("  cave verified free, blob placed")

    # detour site -> (symbol, expected original-byte prefix hex)
    DET = [(0x4009fad2, "decode_cave", "102800316606"),  # decode block head
           (0x4009fb74, "lookup_cave", "41f9400d80a0"),  # lea 0x400d80a0,A0
           (0x4003b790, "fmt_cave",    "4e56ff2c48d7")]  # FUN_4003b790 entry
    for site, sym, exp in DET:
        o = off(site)
        if not bytes(img[o:o + len(exp) // 2]).hex().startswith(exp):
            sys.exit(f"detour 0x{site:08x} unexpected: {bytes(img[o:o+6]).hex()} (want {exp})")
        img[o:o + 6] = jmp(syms[sym])
        print(f"  detour 0x{site:08x} -> {sym} 0x{syms[sym]:08x}")

    # widen the F-knob enum: count datum 25 -> 145 (1 OFF + 12 roots * 12 qualities)
    o = off(0x400d4096)
    if bytes(img[o:o + 4]) != (25).to_bytes(4, "big"):
        sys.exit(f"count datum 0x400d4096 is not 25: {bytes(img[o:o+4]).hex()}")
    img[o:o + 4] = (145).to_bytes(4, "big")
    print("  enum count 0x400d4096: 25 -> 145")

    OUT.write_bytes(bytes(img))
    stock = STOCK.read_bytes()
    d = [i for i, (x, y) in enumerate(zip(stock, img)) if x != y]
    print(f"\n{OUT}: {len(img):,} bytes, {len(d)} changed vs stock")


if __name__ == "__main__":
    main()
