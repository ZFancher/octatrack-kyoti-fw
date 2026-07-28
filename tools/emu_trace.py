from unicorn import *
from unicorn.m68k_const import *
import struct, pathlib, sys

CODE = pathlib.Path("out/raw/section_3_MAIN_OS.bin").read_bytes()
BASE = 0x40000400

def new_uc():
    uc = Uc(UC_ARCH_M68K, UC_MODE_BIG_ENDIAN)
    uc.mem_map(0x40000000, 0x200000)                 # imagen + datos bajos
    uc.mem_write(BASE, CODE[:0x200000-0x400])
    uc.mem_map(0x41000000, 0x20000)                  # stack
    def on_unmapped(uc, access, address, size, value, user):
        uc.mem_map(address & ~0xFFF, 0x1000)         # RAM cero on-demand
        return True
    uc.hook_add(UC_HOOK_MEM_UNMAPPED, on_unmapped)
    return uc

def trace(entry, args, watch=(0x80000000,0x80010000), maxins=200000):
    uc = new_uc()
    sp = 0x41010000
    RET = 0x401f0000
    for a in reversed(args):                          # empuja args (C: der->izq)
        sp -= 4; uc.mem_write(sp, struct.pack(">I", a & 0xffffffff))
    sp -= 4; uc.mem_write(sp, struct.pack(">I", RET)) # ret addr
    uc.reg_write(UC_M68K_REG_A7, sp)
    writes=[]
    def on_write(uc, access, address, size, value, user):
        if watch[0] <= address < watch[1]:
            pc = uc.reg_read(UC_M68K_REG_PC)
            writes.append((pc, address, size, value))
    uc.hook_add(UC_HOOK_MEM_WRITE, on_write)
    try:
        uc.emu_start(entry, RET, count=maxins)
    except UcError as e:
        print("  [UcError %s @ PC=0x%08x]" % (e, uc.reg_read(UC_M68K_REG_PC)))
    return writes

if __name__ == "__main__":
    print("=== trace FUN_40009094(part=0, pattern=0) — escrituras a 0x80000000-0x80010000 ===")
    w = trace(0x40009094, [0,0])
    print("  %d escrituras. Únicas por PC:" % len(w))
    seen=set()
    for pc,addr,size,val in w:
        if pc not in seen:
            seen.add(pc)
            print("    PC 0x%08x -> [0x%08x] = 0x%x (%dB)" % (pc,addr,val,size))
    # ¿escribe en el buffer de voz 0x80000a50?
    hit = any(0x80000a50<=a<0x80000e50 for _,a,_,_ in w)
    print("  ¿escribe el buffer de voz 0x80000a50+? ->", "SÍ ✓ tracer funciona" if hit else "no")
