#!/usr/bin/env python3
# Valida scene_stub (patch_scene.bin) en el emulador ColdFire:
#  - copia scene A/B del bloque LAST_PAT al bloque del pattern entrante
#  - respeta el INIT_FLAG (primer arranque no copia)
#  - no copia si el pattern no cambió
#  - balancea el stack (movem push/pop) y encadena a save_stub (0x400d64e0)
import pathlib, struct
from unicorn import *
from unicorn.m68k_const import *

STUB_ADDR = 0x400d6700
STUB = pathlib.Path("out/patch_scene.bin").read_bytes()
PROJ = 0x46d00000                 # base de datos del proyecto (simulada)
PTRLOC = 0x46c82456               # *(PTRLOC) = PROJ
SAVE_STUB = 0x400d64e0            # marcador: si llega aquí, el chain funcionó
LAST_PAT = 0x80006c60
INIT_FLAG = 0x80006c61
STRIDE = 0x18b2
SA = 0x8ed90   # scene A off
SB = 0x8ed91   # scene B off

def blk(pat, off): return PROJ + pat*STRIDE + off

def run(init_flag, last_pat, new_pat, src_scene, dst_scene):
    uc = Uc(UC_ARCH_M68K, UC_MODE_BIG_ENDIAN)
    uc.mem_map(0x40000000, 0x400000)          # código/stubs
    uc.mem_map(0x46000000, 0x1000000)         # proyecto (16MB) cubre PROJ + 256 patterns
    uc.mem_map(0x80000000, 0x20000)           # RAM globales
    uc.mem_map(0x41000000, 0x20000)           # stack
    uc.mem_write(STUB_ADDR, STUB)
    # save_stub marcador: instrucción que detiene (usamos un hook)
    uc.mem_write(PTRLOC, struct.pack(">I", PROJ))
    # bloques de scene
    uc.mem_write(blk(last_pat, SA), bytes([src_scene[0], src_scene[1]]))
    uc.mem_write(blk(new_pat,  SA), bytes([dst_scene[0], dst_scene[1]]))
    uc.mem_write(LAST_PAT, bytes([last_pat]))
    uc.mem_write(INIT_FLAG, bytes([init_flag]))
    # stack: (sp)=ret, (4)=part arg, (8)=pattern arg(new_pat)
    sp0 = 0x41018000
    uc.mem_write(sp0+0, struct.pack(">I", 0x401f0000))   # retaddr (no usado)
    uc.mem_write(sp0+4, struct.pack(">I", 0x00000001))   # part arg
    uc.mem_write(sp0+8, struct.pack(">I", new_pat))       # pattern arg
    uc.reg_write(UC_M68K_REG_A7, sp0)
    # marcadores de registro para verificar restauración
    seed = {UC_M68K_REG_D0:0x11111111,UC_M68K_REG_D1:0x22222222,UC_M68K_REG_D2:0x33333333,
            UC_M68K_REG_D3:0x44444444,UC_M68K_REG_A0:0x55555555,UC_M68K_REG_A1:0x66666666}
    for r,v in seed.items(): uc.reg_write(r,v)
    result = {"reached_save":False, "sp_at_save":None, "regs":{}}
    def hook(uc, addr, size, user):
        if addr == SAVE_STUB:
            result["reached_save"]=True
            result["sp_at_save"]=uc.reg_read(UC_M68K_REG_A7)
            for r in seed: result["regs"][r]=uc.reg_read(r)
            uc.emu_stop()
    uc.hook_add(UC_HOOK_CODE, hook)
    wild=[]
    def w(uc,acc,ad,sz,val,u):
        if not (0x46000000<=ad<0x47000000 or 0x80006c00<=ad<0x80007000 or 0x41000000<=ad<0x41020000):
            wild.append((ad,sz,val))
    uc.hook_add(UC_HOOK_MEM_WRITE, w)
    try:
        uc.emu_start(STUB_ADDR, 0, count=2000)
    except UcError as e:
        result["error"]=str(e)
    result["new_scene"]=list(uc.mem_read(blk(new_pat,SA),2))
    result["last_pat_after"]=uc.mem_read(LAST_PAT,1)[0]
    result["init_after"]=uc.mem_read(INIT_FLAG,1)[0]
    result["wild"]=wild
    result["sp0"]=sp0
    return result

def check(name, r, expect_copy, new_pat, src, dst):
    ok=True; msgs=[]
    if not r["reached_save"]: ok=False; msgs.append("NO llegó a save_stub")
    if r.get("error"): ok=False; msgs.append("ERROR "+r["error"])
    if r["wild"]: ok=False; msgs.append("WILD WRITE "+str(r["wild"][:3]))
    if r["reached_save"] and r["sp_at_save"]!=r["sp0"]:
        ok=False; msgs.append("SP desbalanceado %x!=%x"%(r["sp_at_save"],r["sp0"]))
    seed={UC_M68K_REG_D0:0x11111111,UC_M68K_REG_D1:0x22222222,UC_M68K_REG_D2:0x33333333,
          UC_M68K_REG_D3:0x44444444,UC_M68K_REG_A0:0x55555555,UC_M68K_REG_A1:0x66666666}
    for rr,v in seed.items():
        if r["regs"].get(rr)!=v: ok=False; msgs.append("reg %s no restaurado"%rr)
    exp = src if expect_copy else dst
    if r["new_scene"]!=list(exp): ok=False; msgs.append("scene destino=%s esperado=%s"%(r["new_scene"],list(exp)))
    if r["last_pat_after"]!=new_pat: ok=False; msgs.append("LAST_PAT=%d esperado=%d"%(r["last_pat_after"],new_pat))
    if r["init_after"]!=0xa5: ok=False; msgs.append("INIT_FLAG=%x"%r["init_after"])
    print(("✅" if ok else "❌"), name, "| scene destino ahora =", r["new_scene"],
          "| LAST_PAT=", r["last_pat_after"], "| regs ok" if not [m for m in msgs if 'reg' in m] else "")
    for m in msgs: print("     -", m)
    return ok

SRC=(0x0F,0x0F)  # A16/B16 (índices 15) del pattern saliente
DST=(0x0A,0x0B)  # A11/B12 del pattern entrante (se deben sobrescribir si copy)
allok=True
allok &= check("Cambio normal (P5->P8): copia A16/B16", run(0xa5,5,8,SRC,DST), True, 8, SRC, DST)
allok &= check("Primer arranque (INIT!=A5): NO copia, solo registra", run(0x00,5,8,SRC,DST), False, 8, SRC, DST)
allok &= check("Mismo pattern (LAST==new): NO copia", run(0xa5,8,8,SRC,DST), False, 8, SRC, DST)
allok &= check("Pattern alto (P255->P0)", run(0xa5,255,0,SRC,DST), True, 0, SRC, DST)
print("\n"+("TODOS OK ✅" if allok else "HAY FALLAS ❌"))
