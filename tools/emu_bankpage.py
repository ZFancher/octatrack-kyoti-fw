#!/usr/bin/env python3
# Validate bank-paging PAGE cycling with file-open existence + skip-missing.
# Existence = can we open "<sibling>/bank01.strd"? The FS open/size/close vtable
# slots (uninitialized in the static image) are stubbed to simulate a card with
# the base project + _2 + _3 but NOT _4; the cycle must skip _4.
import pathlib, struct, subprocess
from unicorn import *
from unicorn.m68k_const import *
BASE=0x40000400
IMG=bytearray(pathlib.Path("out/mainos.bin").read_bytes())
subprocess.run(["m68k-elf-as","-mcpu=5407","-o","/tmp/bp.o","tools/patch_bankpage.s"],check=True)
subprocess.run(["m68k-elf-ld","-Ttext=0x400d7400","-o","/tmp/bp.elf","/tmp/bp.o"],capture_output=True)
subprocess.run(["m68k-elf-objcopy","-O","binary","/tmp/bp.elf","/tmp/bp.bin"],check=True)
bp=pathlib.Path("/tmp/bp.bin").read_bytes()
nm=subprocess.run(["m68k-elf-nm","/tmp/bp.elf"],capture_output=True,text=True).stdout
sym={p[2]:int(p[0],16) for p in (l.split() for l in nm.splitlines()) if len(p)==3}
CAVE=0x400d7400; IMG[CAVE-BASE:CAVE-BASE+len(bp)]=bp
PAGE=sym["page_cave"]; GPAGE=sym["g_page"]; POPUP=0x4006d57c; STUBH=0x4004ffcc; NAME_AT=0x100f8378
V_OPEN,V_SIZE,V_CLOSE=0x46c8242a,0x46c8241e,0x46c82422
S_OPEN,S_SIZE,S_CLOSE=0x41010000,0x41010004,0x41010008
EXISTING={"TESTPROJ_2","TESTPROJ_3"}
def cstr(uc,p):
    b=b""
    while True:
        c=uc.mem_read(p,1)
        if c==b"\0":break
        b+=c;p+=1
    return b.decode('latin1')
def projdir_of(path):
    parts=[x for x in path.split("/") if x]
    return parts[-2] if len(parts)>=2 else ""
def run(g):
    uc=Uc(UC_ARCH_M68K,UC_MODE_BIG_ENDIAN)
    for a,s in [(0x40000000,0x800000),(0x41000000,0x100000),(0x10000000,0x1000000),(0x46000000,0x1000000)]:
        uc.mem_map(a,s)
    uc.mem_write(BASE,bytes(IMG)); uc.mem_write(NAME_AT,b"TESTPROJ\0")
    uc.mem_write(0x460d1e5c,struct.pack(">I",0x11111111)); uc.mem_write(0x460d1e60,struct.pack(">I",0x4007b408))
    uc.mem_write(0x460e5cd0,struct.pack(">I",0)); uc.mem_write(GPAGE,struct.pack(">I",g))
    for s in (S_OPEN,S_SIZE,S_CLOSE): uc.mem_write(s,b"\x4e\x75")
    uc.mem_write(V_OPEN,struct.pack(">I",S_OPEN)); uc.mem_write(V_SIZE,struct.pack(">I",S_SIZE)); uc.mem_write(V_CLOSE,struct.pack(">I",S_CLOSE))
    cap={}
    def hook(uc,addr,size,u):
        if addr==S_OPEN:
            sp=uc.reg_read(UC_M68K_REG_A7); path=cstr(uc,struct.unpack(">I",uc.mem_read(sp+4,4))[0])
            uc.reg_write(UC_M68K_REG_D0, 5 if projdir_of(path) in EXISTING else (-2 & 0xffffffff))
        elif addr==S_SIZE: uc.reg_write(UC_M68K_REG_D0,100)
        elif addr==S_CLOSE: uc.reg_write(UC_M68K_REG_D0,0)
        elif addr==POPUP:
            sp=uc.reg_read(UC_M68K_REG_A7); larr=struct.unpack(">I",uc.mem_read(sp+12,4))[0]
            cap["sib"]=cstr(uc,struct.unpack(">I",uc.mem_read(larr,4))[0]); uc.emu_stop()
        elif addr==STUBH: cap["stock"]=True; uc.emu_stop()
    uc.hook_add(UC_HOOK_CODE,hook)
    sp=0x41030000; uc.reg_write(UC_M68K_REG_A7,sp)
    uc.mem_write(sp,struct.pack(">I",0x41020000)); uc.mem_write(sp+4,struct.pack(">I",0x1b)); uc.mem_write(sp+8,struct.pack(">I",1))
    try: uc.emu_start(PAGE,0,count=200000)
    except UcError as e: return {"err":str(e)}
    return cap
if __name__=="__main__":
    exp={1:"TESTPROJ_2",2:"TESTPROJ_3",3:"TESTPROJ",4:"TESTPROJ"}
    ok=True
    for g in [1,2,3,4]:
        r=run(g); good=r.get("sib")==exp[g]; ok=ok and good
        print(f"from page {g}: next={r.get('sib')!r}  {'OK' if good else 'FAIL '+str(r)}")
    print("RESULT:", "OK" if ok else "FAILURES")
