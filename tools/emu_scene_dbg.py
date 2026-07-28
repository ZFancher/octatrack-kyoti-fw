import pathlib, struct
from unicorn import *
from unicorn.m68k_const import *
STUB_ADDR=0x400d6700; STUB=pathlib.Path("out/patch_scene.bin").read_bytes()
PROJ=0x46d00000
uc=Uc(UC_ARCH_M68K,UC_MODE_BIG_ENDIAN)
uc.mem_map(0x40000000,0x400000); uc.mem_map(0x46000000,0x1000000)
uc.mem_map(0x80000000,0x20000); uc.mem_map(0x41000000,0x20000)
uc.mem_write(STUB_ADDR,STUB)
uc.mem_write(0x46c82456,struct.pack(">I",PROJ))
uc.mem_write(PROJ+5*0x18b2+0x8ed90,bytes([0x0F,0x0F]))
uc.mem_write(PROJ+8*0x18b2+0x8ed90,bytes([0x0A,0x0B]))
uc.mem_write(0x80006c60,bytes([5])); uc.mem_write(0x80006c61,bytes([0xa5]))
sp0=0x41010000
uc.mem_write(sp0+0,struct.pack(">I",0x401f0000)); uc.mem_write(sp0+4,struct.pack(">I",1)); uc.mem_write(sp0+8,struct.pack(">I",8))
uc.reg_write(UC_M68K_REG_A7,sp0)
def code(uc,addr,size,u):
    try: b=uc.mem_read(addr,size).hex()
    except: b="?"
    print("PC %08x  sz=%d  %s  A7=%08x D0=%08x D1=%08x"%(addr,size,b,uc.reg_read(UC_M68K_REG_A7),uc.reg_read(UC_M68K_REG_D0),uc.reg_read(UC_M68K_REG_D1)))
uc.hook_add(UC_HOOK_CODE,code)
def intr(uc,intno,u): print("  >> INTR/EXC intno=%d PC=%08x"%(intno,uc.reg_read(UC_M68K_REG_PC)))
uc.hook_add(UC_HOOK_INTR,intr)
try: uc.emu_start(STUB_ADDR,0x400d6780,count=40)
except UcError as e: print("UcError:",e,"PC=%08x"%uc.reg_read(UC_M68K_REG_PC))
