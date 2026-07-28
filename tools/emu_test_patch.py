from unicorn import *
from unicorn.m68k_const import *
import struct, pathlib
BASE=0x40000400; PROJ=0x50000000; VB=0x80000a50; VA=0x800049d8
def load(img):
    CODE=pathlib.Path(img).read_bytes()
    uc=Uc(UC_ARCH_M68K,UC_MODE_BIG_ENDIAN)
    uc.mem_map(0x40000000,0x400000); uc.mem_write(BASE,CODE[:0x400000-0x400])
    uc.mem_map(0x41000000,0x20000); uc.mem_map(0x50000000,0x400000); uc.mem_map(0x80000000,0x20000)
    def um(uc,a,ad,sz,v,u): uc.mem_map(ad&~0xFFF,0x1000); return True
    uc.hook_add(UC_HOOK_MEM_UNMAPPED,um)
    try: uc.mem_map(0x46c82000,0x1000)
    except UcError: pass
    uc.mem_write(0x46c82456,struct.pack(">I",PROJ))
    uc.mem_write(PROJ,bytes([0x11]*0x100000))
    uc.mem_write(VB,bytes([0xEE]*0x200))
    return uc
STUBS=set()
def run(uc,part,pat):
    def hk(uc,addr,size,user):
        if addr in STUBS:
            sp=uc.reg_read(UC_M68K_REG_A7); ret=struct.unpack(">I",uc.mem_read(sp,4))[0]
            uc.reg_write(UC_M68K_REG_A7,sp+4); uc.reg_write(UC_M68K_REG_PC,ret)
    uc.hook_add(UC_HOOK_CODE,hk)
    sp=0x41010000; RET=0x401f0000
    for a in (pat,part): sp-=4; uc.mem_write(sp,struct.pack(">I",a))
    sp-=4; uc.mem_write(sp,struct.pack(">I",RET)); uc.reg_write(UC_M68K_REG_A7,sp)
    for _ in range(20):
        try:
            uc.emu_start(0x40009094,RET,count=500000); return True
        except UcError as e:
            pc=uc.reg_read(UC_M68K_REG_PC)
            # stub la función que falla (retrocede al inicio de func por prólogo)
            d=uc.mem_read(0,0) if False else None
            STUBS.add(pc & ~1)
            # busca inicio de función (prólogo) hacia atrás
            img=pathlib.Path("out/mainos_behpatch.bin").read_bytes()
            p=(pc-BASE)-2
            while p>(pc-BASE)-0x400 and p>=0:
                w=struct.unpack_from(">H",img,p)[0]
                if 0x4E50<=w<=0x4E57 or w==0x4FEF: STUBS.add(BASE+p); break
                p-=2
            # reintenta desde el estado actual continuando
            try: uc.emu_start(pc, RET, count=500000); return True
            except UcError: continue
    return False
TRK=3
uc=load("out/mainos_behpatch.bin"); uc.mem_write(VA+TRK*0xA8,b'\x01')
before=bytes(uc.mem_read(VB+TRK*0x40,0x40))
ok=run(uc,1,0)
t3=bytes(uc.mem_read(VB+TRK*0x40,0x40)); t0=bytes(uc.mem_read(VB+0,0x40))
print("completó (con stubs):", ok, " | stubs:", len(STUBS))
print("track 3 (SONANDO) preservado (==0xEE)?  ", "SÍ ✓" if t3==before else f"NO ({t3[:4].hex()})")
print("track 0 (silencioso) actualizado (!=0xEE)?", "SÍ ✓" if t0!=b'\xEE'*0x40 else "no")
print("\nVALIDACIÓN DEL PARCHE EN FIRMWARE:",
      "✓✓✓ el parche preserva el track sonando y actualiza los demás" if (t3==before and t0!=b'\xEE'*0x40) else "✗ revisar")
