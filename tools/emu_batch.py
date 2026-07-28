from unicorn import *
from unicorn.m68k_const import *
import struct, pathlib
CODE=pathlib.Path("out/raw/section_3_MAIN_OS.bin").read_bytes(); BASE=0x40000400; PROJ=0x50000000
def be32(v): return struct.pack(">I",v&0xffffffff)
GLOB=[(0x46c82456,4,PROJ),(0x100b14cf,1,0),(0x100b14cc,1,0),(0x100b14d0,1,0),
      (0x80000002,1,0),(0x80000003,1,0),(0x460d172a,1,0),(0x460d5c80,1,1),(0x46c7d8d8,1,0)]
def run(entry,args):
    uc=Uc(UC_ARCH_M68K,UC_MODE_BIG_ENDIAN)
    uc.mem_map(0x40000000,0x400000); uc.mem_write(BASE,CODE[:0x400000-0x400])
    uc.mem_map(0x41000000,0x20000); uc.mem_map(0x50000000,0x400000)
    def um(uc,a,ad,sz,v,u): uc.mem_map(ad&~0xFFF,0x1000); return True
    uc.hook_add(UC_HOOK_MEM_UNMAPPED,um)
    for ad,sz,vl in GLOB:
        try: uc.mem_map(ad&~0xFFF,0x1000)
        except UcError: pass
        uc.mem_write(ad, be32(vl) if sz==4 else bytes([vl&0xff]))
    sp=0x41010000; RET=0x401f0000
    for a in reversed(args): sp-=4; uc.mem_write(sp,be32(a))
    sp-=4; uc.mem_write(sp,be32(RET)); uc.reg_write(UC_M68K_REG_A7,sp)
    locs=set()
    def ow(uc,a,ad,sz,v,u):
        if 0x50000000<=ad<0x50400000: locs.add(ad-PROJ)
    uc.hook_add(UC_HOOK_MEM_WRITE,ow)
    try: uc.emu_start(entry,RET,count=60000)
    except UcError: pass
    return locs
CANDS=[0x40052b90,0x40052d38,0x40053112,0x40053334,0x40053498,0x40053904,
       0x40053a68,0x40053ed0,0x4005435c,0x4005536e,0x40055d8c,0x40056c40]
for e in CANDS:
    best=None
    for args in ([],[0],[0,1],[5,1],[0,5,1]):
        locs=run(e,args)
        pd=[l for l in locs if 0x8e000<=l<0xa0000]  # zona de params por-pattern/part
        if pd and (best is None or len(pd)<len(best[1])):
            best=(args,sorted(pd))
    if best:
        a,pd=best
        print(f"0x{e:08x}: {len(pd)} param-locs (args={a}) -> {[hex(x) for x in pd[:6]]}")
    else:
        print(f"0x{e:08x}: no escribe params")
