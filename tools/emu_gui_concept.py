import importlib.util, pathlib
spec=importlib.util.spec_from_file_location("ee","tools/emu_editor.py")
ee=importlib.util.module_from_spec(spec)
# parcheo run() para aceptar override: reusamos su lógica copiándola
from unicorn import *
from unicorn.m68k_const import *
import struct
BASE=0x40000400; PROJ=0x50000000; DEFBUF=0x50300000
IMG=pathlib.Path("out/raw/section_3_MAIN_OS.bin").read_bytes()
def run(track,disp_pat,active_part,active_pat,pt_part,pt_pat,va_playing,override=False,enc=0,delta=1):
    uc=Uc(UC_ARCH_M68K,UC_MODE_BIG_ENDIAN)
    uc.mem_map(0x40000000,0x400000); uc.mem_write(BASE,IMG[:0x400000-0x400])
    uc.mem_map(0x41000000,0x20000); uc.mem_map(0x50000000,0x400000); uc.mem_map(0x80000000,0x20000)
    def um(uc,a,ad,sz,v,u): uc.mem_map(ad&~0xFFF,0x1000); return True
    uc.hook_add(UC_HOOK_MEM_UNMAPPED,um)
    for a in (0x46c82000,0x100b0000,0x460d1000,0x400bc000):
        try: uc.mem_map(a,0x2000)
        except UcError: pass
    uc.mem_write(0x46c82456,struct.pack(">I",PROJ)); uc.mem_write(PROJ,bytes([0xFF]*0x100000))
    ptr=struct.unpack(">I",IMG[0x400bcd14-BASE:0x400bcd14-BASE+4])[0]
    try: uc.mem_map(ptr&~0xFFF,0x2000)
    except UcError: pass
    uc.mem_write(ptr+8,b'\x00\x00\x00\x00')
    # OVERRIDE = simula el parche: redirige a source
    if override:
        disp_pat=pt_pat; active_part=pt_part; active_pat=pt_pat
    uc.mem_write(0x100b14cc,bytes([track])); uc.mem_write(0x100b14cf,bytes([disp_pat]))
    uc.mem_write(0x80000002,bytes([active_part])); uc.mem_write(0x80000003,bytes([active_pat]))
    uc.mem_write(0x8000182a+track,bytes([pt_part])); uc.mem_write(0x80001832+track,bytes([pt_pat]))
    if va_playing: uc.mem_write(0x800049d8+track*0xA8,b'\x01')
    uc.mem_write(0x460d1684,bytes([0]))
    uc.mem_write(PROJ+disp_pat*0x18b2+0x8ed90,bytes([active_part,active_part]))
    STUBS={0x4002ea84:0,0x4006dbcc:0,0x4007c418:0,0x400a6994:9,0x40031f28:DEFBUF,0x40027e00:0}
    def hk(uc,addr,size,user):
        if addr in STUBS:
            sp=uc.reg_read(UC_M68K_REG_A7); ret=struct.unpack(">I",uc.mem_read(sp,4))[0]
            uc.reg_write(UC_M68K_REG_A7,sp+4); uc.reg_write(UC_M68K_REG_PC,ret)
            uc.reg_write(UC_M68K_REG_D0,STUBS[addr]); uc.reg_write(UC_M68K_REG_D1,9)
    uc.hook_add(UC_HOOK_CODE,hk)
    W=[]
    def ow(uc,a,ad,sz,v,u):
        if (0x50000000<=ad<0x50400000) or (0x80000000<=ad<0x80020000): W.append((ad,v))
    uc.hook_add(UC_HOOK_MEM_WRITE,ow)
    sp=0x41010000; RET=0x401f0000
    for a in (delta,enc): sp-=4; uc.mem_write(sp,struct.pack(">I",a&0xffffffff))
    sp-=4; uc.mem_write(sp,struct.pack(">I",RET)); uc.reg_write(UC_M68K_REG_A7,sp)
    try: uc.emu_start(0x40052e98,RET,count=200000)
    except UcError: pass
    return W
def summarize(W):
    param=[hex(a-PROJ) for a,v in W if PROJ<=a<PROJ+0x100000 and (a-PROJ)<0xf0000 and (a-PROJ)!=0x95048 and (a-PROJ)!=0x9b332]
    live=[hex(a) for a,v in W if 0x80000e00<=a<0x80001200]
    return param, live
for label,ov in [("TRANSICIÓN sin parche",False),("TRANSICIÓN CON parche (override→source)",True)]:
    W=run(track=3,disp_pat=2,active_part=2,active_pat=2,pt_part=1,pt_pat=1,va_playing=1,override=ov)
    param,live=summarize(W)
    print(f"{label}:")
    print(f"   param escrito en offsets: {param}")
    print(f"   live-update (sonido): {live if live else 'NINGUNO (no suena el cambio)'}")
# referencia: dónde está el param de SOURCE (pat=1,part=1) vs DEST (pat=2,part=2)
def addr(pat,part,track): return pat*0x18b2 + (part*8+track)*0x20 + 0x8f3e2
print(f"\n(source pat1/part1 track3 -> 0x{addr(1,1,3):x} ; dest pat2/part2 -> 0x{addr(2,2,3):x})")
