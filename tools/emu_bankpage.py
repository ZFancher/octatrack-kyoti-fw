#!/usr/bin/env python3
# Validate bank-paging page cycling + target-name construction (S3b) in a
# ColdFire emulator: (page & 3) + 1 cycling, and sprintf of "<base>"/"<base>_N".
import pathlib, struct, subprocess
from unicorn import *
from unicorn.m68k_const import *
BASE=0x40000400
IMG=bytearray(pathlib.Path("out/mainos.bin").read_bytes())   # R11 (or any build)
subprocess.run(["m68k-elf-as","-mcpu=5407","-o","/tmp/bp.o","tools/patch_bankpage.s"],check=True)
subprocess.run(["m68k-elf-ld","-Ttext=0x400d7400","-o","/tmp/bp.elf","/tmp/bp.o"],capture_output=True)
subprocess.run(["m68k-elf-objcopy","-O","binary","/tmp/bp.elf","/tmp/bp.bin"],check=True)
bp=pathlib.Path("/tmp/bp.bin").read_bytes()
nm=subprocess.run(["m68k-elf-nm","/tmp/bp.elf"],capture_output=True,text=True).stdout
sym={p[2]:int(p[0],16) for p in (l.split() for l in nm.splitlines()) if len(p)==3}
CAVE=0x400d7400; IMG[CAVE-BASE:CAVE-BASE+len(bp)]=bp
PAGE=sym["page_cave"]; GPAGE=sym["g_page"]; POPUP=0x4006d57c; NAME_AT=0x100f8378
def cstr(uc,p):
    b=b""
    while True:
        c=uc.mem_read(p,1)
        if c==b"\0": break
        b+=c;p+=1
    return b.decode('latin1')
def run(g):
    uc=Uc(UC_ARCH_M68K,UC_MODE_BIG_ENDIAN)
    for a,s in [(0x40000000,0x800000),(0x41000000,0x100000),(0x10000000,0x1000000),(0x46000000,0x1000000)]:
        uc.mem_map(a,s)
    uc.mem_write(BASE,bytes(IMG)); uc.mem_write(NAME_AT,b"TESTPROJ\0")
    uc.mem_write(0x460d1e5c,struct.pack(">I",0x11111111))
    uc.mem_write(0x460d1e60,struct.pack(">I",0x4007b408))
    uc.mem_write(0x460e5cd0,struct.pack(">I",0)); uc.mem_write(GPAGE,struct.pack(">I",g))
    cap={}
    def hook(uc,addr,size,u):
        if addr==POPUP:
            sp=uc.reg_read(UC_M68K_REG_A7)
            larr=struct.unpack(">I",uc.mem_read(sp+12,4))[0]
            sibp=struct.unpack(">I",uc.mem_read(larr,4))[0]
            cap["sib"]=cstr(uc,sibp); cap["gpage"]=struct.unpack(">I",uc.mem_read(GPAGE,4))[0]
            uc.emu_stop()
    uc.hook_add(UC_HOOK_CODE,hook)
    sp=0x41030000; uc.reg_write(UC_M68K_REG_A7,sp)
    uc.mem_write(sp,struct.pack(">I",0x41020000)); uc.mem_write(sp+4,struct.pack(">I",0x1b)); uc.mem_write(sp+8,struct.pack(">I",1))
    try: uc.emu_start(PAGE,0,count=5000)
    except UcError as e: return {"err":str(e)}
    return cap
if __name__=="__main__":
    exp={1:(2,"TESTPROJ_2"),2:(3,"TESTPROJ_3"),3:(4,"TESTPROJ_4"),4:(1,"TESTPROJ")}
    ok=True
    for g in [1,2,3,4]:
        r=run(g); good = "err" not in r and r.get("gpage")==exp[g][0] and r.get("sib")==exp[g][1]
        ok=ok and good; print(f"page {g}->{r.get('gpage')}  {r.get('sib')!r}  {'OK' if good else 'FAIL '+str(r)}")
    print("RESULT:", "ALL PASS" if ok else "FAILURES")
