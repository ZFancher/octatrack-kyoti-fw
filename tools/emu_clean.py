from unicorn import *
from unicorn.m68k_const import *
import struct, pathlib
BASE=0x40000400; PROJ=0x50000000
IMG=pathlib.Path("out/mainos_behpatch.bin").read_bytes()
# PRE-stub: post_work y funciones EMAC-pesadas → rts limpio (sin fault-based stubbing)
PRESTUB={0x40000c3c, 0x40000a8e}
uc=Uc(UC_ARCH_M68K,UC_MODE_BIG_ENDIAN)
uc.mem_map(0x40000000,0x400000); uc.mem_write(BASE,IMG[:0x400000-0x400])
uc.mem_map(0x41000000,0x20000); uc.mem_map(0x50000000,0x400000); uc.mem_map(0x80000000,0x20000)
def um(uc,a,ad,sz,v,u): uc.mem_map(ad&~0xFFF,0x1000); return True
uc.hook_add(UC_HOOK_MEM_UNMAPPED,um)
try: uc.mem_map(0x46c82000,0x1000)
except UcError: pass
uc.mem_write(0x46c82456,struct.pack(">I",PROJ)); uc.mem_write(PROJ,bytes([0x11]*0x100000))
uc.mem_write(0x80000a50,bytes([0xEE]*0x200)); uc.mem_write(0x800049d8+3*0xA8,b'\x01')
cnt={0x40009638:0,0x40009664:0,0x400d64e0:0,0x400d6538:0,0x40000c3c:0}
def hk(uc,addr,size,user):
    if addr in PRESTUB:
        sp=uc.reg_read(UC_M68K_REG_A7); ret=struct.unpack(">I",uc.mem_read(sp,4))[0]
        uc.reg_write(UC_M68K_REG_A7,sp+4); uc.reg_write(UC_M68K_REG_PC,ret); return
    if addr in cnt: cnt[addr]+=1
uc.hook_add(UC_HOOK_CODE,hk)
sp=0x41010000; RET=0x401f0000
for a in (0,1): sp-=4; uc.mem_write(sp,struct.pack(">I",a))
sp-=4; uc.mem_write(sp,struct.pack(">I",RET)); uc.reg_write(UC_M68K_REG_A7,sp)
err=None
try: uc.emu_start(0x40009094,RET,count=2000000)
except UcError as e: err=(str(e),hex(uc.reg_read(UC_M68K_REG_PC)))
print("error:",err)
print("save_stub(0x400d64e0):",cnt[0x400d64e0]," | 0x40009638(jsr postwork):",cnt[0x40009638],
      " | 0x40009664(tailcall):",cnt[0x40009664]," | restore_stub(0x400d6538):",cnt[0x400d6538])
t3=bytes(uc.mem_read(0x80000a50+3*0x40,8))
print("track3 final:",t3.hex(), "-> PRESERVADO ✓" if t3==b'\xEE'*8 else "sobrescrito")
