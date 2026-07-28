from unicorn import *
from unicorn.m68k_const import *
import struct, pathlib
BASE=0x40000400; PROJ=0x50000000; VB=0x80000a50; VA=0x800049d8
IMG=pathlib.Path("out/mainos_behpatch.bin").read_bytes()
def funcstart(pc):
    p=(pc-BASE)-2
    while p>(pc-BASE)-0x600 and p>=0:
        w=struct.unpack_from(">H",IMG,p)[0]
        if 0x4E50<=w<=0x4E57 or w==0x4FEF: return BASE+p
        p-=2
    return pc&~1
def attempt(stubs):
    uc=Uc(UC_ARCH_M68K,UC_MODE_BIG_ENDIAN)
    uc.mem_map(0x40000000,0x400000); uc.mem_write(BASE,IMG[:0x400000-0x400])
    uc.mem_map(0x41000000,0x20000); uc.mem_map(0x50000000,0x400000); uc.mem_map(0x80000000,0x20000)
    def um(uc,a,ad,sz,v,u): uc.mem_map(ad&~0xFFF,0x1000); return True
    uc.hook_add(UC_HOOK_MEM_UNMAPPED,um)
    try: uc.mem_map(0x46c82000,0x1000)
    except UcError: pass
    uc.mem_write(0x46c82456,struct.pack(">I",PROJ)); uc.mem_write(PROJ,bytes([0x11]*0x100000))
    uc.mem_write(VB,bytes([0xEE]*0x200)); uc.mem_write(VA+3*0xA8,b'\x01')
    flags={'save':0,'rest':0}
    def hk(uc,addr,size,user):
        if addr in stubs:
            sp=uc.reg_read(UC_M68K_REG_A7); ret=struct.unpack(">I",uc.mem_read(sp,4))[0]
            uc.reg_write(UC_M68K_REG_A7,sp+4); uc.reg_write(UC_M68K_REG_PC,ret); return
        if addr==0x400d64e0: flags['save']+=1
        if addr==0x400d6538: flags['rest']+=1
    uc.hook_add(UC_HOOK_CODE,hk)
    sp=0x41010000; RET=0x401f0000
    for a in (0,1): sp-=4; uc.mem_write(sp,struct.pack(">I",a))
    sp-=4; uc.mem_write(sp,struct.pack(">I",RET)); uc.reg_write(UC_M68K_REG_A7,sp)
    err=None
    try: uc.emu_start(0x40009094,RET,count=1000000)
    except UcError as e: err=(str(e),uc.reg_read(UC_M68K_REG_PC))
    savebuf=bytes(uc.mem_read(0x80006a00+3*0x40,8))
    flag3=uc.mem_read(0x80006c20+3,1)[0]
    t3=bytes(uc.mem_read(VB+3*0x40,8))
    return err,flags,savebuf,flag3,t3
stubs=set()
for i in range(12):
    err,flags,savebuf,flag3,t3=attempt(stubs)
    if err is None: break
    fs=funcstart(err[1]); stubs.add(fs)
print("stubs:",[hex(x) for x in stubs])
print("save_stub ejecutado:",flags['save']," restore_stub ejecutado:",flags['rest'])
print("saved_flag[3]:",flag3," savebuf[3][:8]:",savebuf.hex()," track3 final[:8]:",t3.hex())
