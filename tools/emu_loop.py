from unicorn import *
from unicorn.m68k_const import *
import struct, pathlib
from collections import Counter
CODE=pathlib.Path("out/raw/section_3_MAIN_OS.bin").read_bytes(); BASE=0x40000400
uc=Uc(UC_ARCH_M68K,UC_MODE_BIG_ENDIAN)
uc.mem_map(0x40000000,0x400000); uc.mem_write(BASE,CODE[:0x400000-0x400])
uc.mem_map(0x41000000,0x20000); uc.mem_map(0x50000000,0x400000)
def um(uc,a,ad,sz,v,u): uc.mem_map(ad&~0xFFF,0x1000); return True
uc.hook_add(UC_HOOK_MEM_UNMAPPED,um)
try: uc.mem_map(0x46c82000,0x1000)
except UcError: pass
uc.mem_write(0x46c82456, struct.pack(">I",0x50000000))
# detecta saltos hacia atrás (loops): registra (from,to) donde to<from
prev=[0]
edges=Counter()
def hook_ins(uc, address, size, user):
    if address < prev[0] and prev[0]-address < 0x400:   # salto atrás corto = loop
        edges[(prev[0],address)] += 1
    prev[0]=address
uc.hook_add(UC_HOOK_CODE, hook_ins, begin=0x40009094, end=0x4000966a)
sp=0x41010000; RET=0x401f0000
for a in (0,1): sp-=4; uc.mem_write(sp,struct.pack(">I",a))
sp-=4; uc.mem_write(sp,struct.pack(">I",RET)); uc.reg_write(UC_M68K_REG_A7,sp)
try: uc.emu_start(0x40009094,RET,count=200000)
except UcError as e: pass
print("saltos-atrás (loops) más frecuentes en FUN_40009094:")
for (frm,to),c in edges.most_common(8):
    print(f"  0x{frm:08x} -> 0x{to:08x}  (x{c})")
