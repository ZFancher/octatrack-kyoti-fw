from unicorn import *
from unicorn.m68k_const import *
import struct, pathlib
BASE=0x40000400; PROJ=0x50000000
IMG=pathlib.Path("out/raw/section_3_MAIN_OS.bin").read_bytes()
# stubs: helpers que devuelven 0 (o puntero válido para FUN_40031f28)
DEFBUF=0x50300000   # buffer de "definición de param" que devuelve FUN_40031f28
def run(track, disp_pat, active_part, active_pat, pt_part, pt_pat, va_playing, enc=0, delta=1):
    uc=Uc(UC_ARCH_M68K,UC_MODE_BIG_ENDIAN)
    uc.mem_map(0x40000000,0x400000); uc.mem_write(BASE,IMG[:0x400000-0x400])
    uc.mem_map(0x41000000,0x20000); uc.mem_map(0x50000000,0x400000); uc.mem_map(0x80000000,0x20000)
    def um(uc,a,ad,sz,v,u): uc.mem_map(ad&~0xFFF,0x1000); return True
    uc.hook_add(UC_HOOK_MEM_UNMAPPED,um)
    for a in (0x46c82000,0x100b0000,0x460d1000,0x400bc000):
        try: uc.mem_map(a,0x2000)
        except UcError: pass
    uc.mem_write(0x46c82456,struct.pack(">I",PROJ)); uc.mem_write(PROJ,bytes([0xFF]*0x100000))
    # PTR_DAT_400bcd14 -> asegurar *(ptr+8)==0
    ptr=struct.unpack(">I",IMG[0x400bcd14-BASE:0x400bcd14-BASE+4])[0]
    try: uc.mem_map(ptr&~0xFFF,0x2000)
    except UcError: pass
    uc.mem_write(ptr+8,b'\x00\x00\x00\x00')
    # globales
    uc.mem_write(0x100b14cc,bytes([track]))          # track actual
    uc.mem_write(0x100b14cf,bytes([disp_pat]))       # pattern mostrado
    uc.mem_write(0x80000002,bytes([active_part]))    # part activo
    uc.mem_write(0x80000003,bytes([active_pat]))     # pattern activo
    uc.mem_write(0x8000182a+track,bytes([pt_part]))  # per_track_part
    uc.mem_write(0x80001832+track,bytes([pt_pat]))   # per_track_pattern
    if va_playing: uc.mem_write(0x800049d8+track*0xA8,b'\x01')
    uc.mem_write(0x460d1684,bytes([0]))              # page = 0
    # part index que lee de 0x8ed90/91 del pattern mostrado: lo pongo = active_part
    uc.mem_write(PROJ+disp_pat*0x18b2+0x8ed90,bytes([active_part,active_part]))
    STUBS={0x4002ea84:0,0x4006dbcc:0,0x4007c418:0,0x400a6994:9,0x40031f28:DEFBUF,0x40027e00:0}
    def hk(uc,addr,size,user):
        if addr in STUBS:
            sp=uc.reg_read(UC_M68K_REG_A7); ret=struct.unpack(">I",uc.mem_read(sp,4))[0]
            uc.reg_write(UC_M68K_REG_A7,sp+4); uc.reg_write(UC_M68K_REG_PC,ret)
            uc.reg_write(UC_M68K_REG_D0,STUBS[addr]); uc.reg_write(UC_M68K_REG_D1,9)
    uc.hook_add(UC_HOOK_CODE,hk)
    writes=[]
    def ow(uc,a,ad,sz,v,u):
        if (0x50000000<=ad<0x50400000) or (0x80000000<=ad<0x80020000): writes.append((ad,v,sz))
    uc.hook_add(UC_HOOK_MEM_WRITE,ow)
    sp=0x41010000; RET=0x401f0000
    for a in (delta,enc): sp-=4; uc.mem_write(sp,struct.pack(">I",a&0xffffffff))
    sp-=4; uc.mem_write(sp,struct.pack(">I",RET)); uc.reg_write(UC_M68K_REG_A7,sp)
    try: uc.emu_start(0x40052e98,RET,count=200000)
    except UcError as e: pass
    return writes
def show(w):
    for ad,v,sz in w:
        reg="PROJ+0x%x"%(ad-PROJ) if ad>=PROJ else "0x%08x"%ad
        print(f"    [{reg}] = 0x{v:x} ({sz}B)")
print("=== NORMAL (track sincronizado: per_track==activo) ===")
show(run(track=3,disp_pat=0,active_part=0,active_pat=0,pt_part=0,pt_pat=0,va_playing=1))
print("=== TRANSICIÓN (per_track_part=1 origen != activo=2 destino, sonando) ===")
show(run(track=3,disp_pat=2,active_part=2,active_pat=2,pt_part=1,pt_pat=1,va_playing=1))
