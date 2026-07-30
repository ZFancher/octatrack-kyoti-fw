#!/usr/bin/env python3
"""Diagnostic: R11 + a PAGE handler that shows the built sibling name, the full
path from FUN_40025230, and the FUN_40025650 existence result in the popup."""
import pathlib, subprocess, sys
BASE=0x40000400; CAVE_AT=0x400d7400
R11=pathlib.Path("out/raw/section_3_MAIN_OS.bin"); OUT=pathlib.Path("out/mainos_diag.bin")
DET=[(0x4004ffc4,"page_cave","4feffff048d7"),(0x40025244,"gate_cave","41f9100f8378"),(0x400239a2,"done_cave","4ebaff004878")]
def off(a): return a-BASE
def main():
    img=bytearray(R11.read_bytes())
    subprocess.run(["m68k-elf-as","-mcpu=5407","-o","out/pd.o","tools/patch_bankpage_diag.s"],check=True)
    subprocess.run(["m68k-elf-ld",f"-Ttext=0x{CAVE_AT:x}","-o","out/pd.elf","out/pd.o"],capture_output=True)
    subprocess.run(["m68k-elf-objcopy","-O","binary","out/pd.elf","out/pd.bin"],check=True)
    nm=subprocess.run(["m68k-elf-nm","out/pd.elf"],capture_output=True,text=True).stdout
    sym={p[2]:int(p[0],16) for p in (l.split() for l in nm.splitlines()) if len(p)==3}
    blob=pathlib.Path("out/pd.bin").read_bytes()
    print(f"diag cave {len(blob)} B @ 0x{CAVE_AT:08x}")
    if any(img[off(CAVE_AT):off(CAVE_AT)+len(blob)]): sys.exit("cave not free")
    img[off(CAVE_AT):off(CAVE_AT)+len(blob)]=blob
    for site,s,exp in DET:
        o=off(site)
        if not bytes(img[o:o+len(exp)//2]).hex().startswith(exp): sys.exit(f"detour 0x{site:08x}: {bytes(img[o:o+6]).hex()} want {exp}")
        img[o:o+6]=b"\x4e\xf9"+sym[s].to_bytes(4,"big"); print(f"  detour 0x{site:08x} -> {s}")
    OUT.write_bytes(bytes(img)); print(f"{OUT} written")
main()
