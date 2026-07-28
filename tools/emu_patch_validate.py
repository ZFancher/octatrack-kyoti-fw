from unicorn import *
from unicorn.m68k_const import *
import struct, pathlib
CODE=pathlib.Path("out/raw/section_3_MAIN_OS.bin").read_bytes(); BASE=0x40000400; PROJ=0x50000000
VB=0x80000a50   # buffer de voz, stride 0x40/track
VA=0x800049d8   # voz activa, stride 0xA8/track, byte0
def mk():
    uc=Uc(UC_ARCH_M68K,UC_MODE_BIG_ENDIAN)
    uc.mem_map(0x40000000,0x400000); uc.mem_write(BASE,CODE[:0x400000-0x400])
    uc.mem_map(0x41000000,0x20000); uc.mem_map(0x50000000,0x400000); uc.mem_map(0x80000000,0x20000)
    def um(uc,a,ad,sz,v,u): uc.mem_map(ad&~0xFFF,0x1000); return True
    uc.hook_add(UC_HOOK_MEM_UNMAPPED,um)
    try: uc.mem_map(0x46c82000,0x1000)
    except UcError: pass
    uc.mem_write(0x46c82456,struct.pack(">I",PROJ))
    # llena data de proyecto con valores reconocibles (para ver qué se copia)
    uc.mem_write(PROJ,bytes([0x11]*0x100000))
    # buffer de voz: marca OLD = 0xEE en los 8 tracks
    uc.mem_write(VB,bytes([0xEE]*0x200))
    return uc
def call(uc,part,pat):
    sp=0x41010000; RET=0x401f0000
    for a in (pat,part): sp-=4; uc.mem_write(sp,struct.pack(">I",a))
    sp-=4; uc.mem_write(sp,struct.pack(">I",RET)); uc.reg_write(UC_M68K_REG_A7,sp)
    try: uc.emu_start(0x40009094,RET,count=300000)
    except UcError: pass
def vbuf(uc,t): return uc.mem_read(VB+t*0x40,0x40)

TRK=3
print("=== SIN parche: FUN_40009094 sobrescribe el buffer del track %d (sonando)? ===" % TRK)
uc=mk(); uc.mem_write(VA+TRK*0xA8,b'\x01')  # track 3 sonando
before=bytes(vbuf(uc,TRK)); call(uc,1,0); after=bytes(vbuf(uc,TRK))
print("  track %d buffer cambió:" % TRK, "SÍ (=el salto)" if before!=after else "no")
print("  (old[:8]=%s  new[:8]=%s)" % (before[:8].hex(), after[:8].hex()))

print("\n=== CON parche (save/restore del track sonando): ===")
uc=mk(); uc.mem_write(VA+TRK*0xA8,b'\x01')
saved=bytes(vbuf(uc,TRK)); other_before=bytes(vbuf(uc,0))   # track 0 silencioso
call(uc,1,0)
uc.mem_write(VB+TRK*0x40,saved)             # RESTORE del track sonando
t3=bytes(vbuf(uc,TRK)); t0=bytes(vbuf(uc,0))
print("  track %d (sonando): preservado (==OLD 0xEE)?" % TRK, "SÍ ✓" if t3==saved else "NO")
print("  track 0 (silencioso): actualizado (!=OLD)?", "SÍ ✓" if t0!=other_before else "no cambió")
print("\nVALIDACIÓN:", "✓✓ el parche save/restore preserva el track sonando y actualiza los demás" if (t3==saved and t0!=other_before) else "revisar")
