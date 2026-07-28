from unicorn import *
from unicorn.m68k_const import *
import struct, pathlib

CODE = pathlib.Path("out/raw/section_3_MAIN_OS.bin").read_bytes()
BASE = 0x40000400
PROJ = 0x50000000            # base ficticia de datos de proyecto (para _DAT_46c82456)

def be32(v): return struct.pack(">I", v & 0xffffffff)

def run(entry, args, globals_):
    uc = Uc(UC_ARCH_M68K, UC_MODE_BIG_ENDIAN)
    uc.mem_map(0x40000000, 0x400000); uc.mem_write(BASE, CODE[:0x400000-0x400])
    uc.mem_map(0x41000000, 0x20000)          # stack
    uc.mem_map(0x50000000, 0x400000)         # datos de proyecto ficticios
    def on_unmapped(uc, access, address, size, value, user):
        uc.mem_map(address & ~0xFFF, 0x1000); return True
    uc.hook_add(UC_HOOK_MEM_UNMAPPED, on_unmapped)
    # setea globales (pre-mapea la página y escribe)
    for addr, size, val in globals_:
        try: uc.mem_map(addr & ~0xFFF, 0x1000)
        except UcError: pass
        uc.mem_write(addr, (be32(val) if size==4 else bytes([val&0xff])))
    sp = 0x41010000
    RET = 0x401f0000
    for a in reversed(args):
        sp -= 4; uc.mem_write(sp, be32(a))
    sp -= 4; uc.mem_write(sp, be32(RET)); uc.reg_write(UC_M68K_REG_A7, sp)
    writes=[]
    def on_write(uc, access, address, size, value, user):
        # data del Part: proyecto (0x50xxxxxx) o base fija (0x40170000+) o buffer voz (0x80000a50+)
        if (0x50000000<=address<0x50400000) or (0x40160000<=address<0x40200000):
            writes.append((uc.reg_read(UC_M68K_REG_PC), address, value, size))
    uc.hook_add(UC_HOOK_MEM_WRITE, on_write)
    try: uc.emu_start(entry, RET, count=100000)
    except UcError as e: pass
    return writes

GLOB = [(0x46c82456,4,PROJ), (0x100b14cf,1,0), (0x100b14cc,1,0),
        (0x100b14d0,1,0), (0x80000002,1,0), (0x80000003,1,0)]

for name, entry in [("0x40052b90",0x40052b90),("0x4005a918",0x4005a918),
                    ("0x4004ecfc",0x4004ecfc),("0x40052ae8",0x40052ae8),
                    ("0x4003d594",0x4003d594)]:
    for mode in (0,1):
        w = run(entry, [0,5,1,1], GLOB+[(0x460d172a,1,mode)])
        proj = [x for x in w if x[1]>=0x50000000]
        if proj:
            print(f"### {name} (modo _460d172a={mode}): ESCRIBE data del Part ({len(proj)} writes) ###")
            for pc,a,v,s in proj[:6]:
                print(f"    PC 0x{pc:08x} -> [0x{a:08x}]=0x{v:x} ({s}B)  (offset proyecto +0x{a-PROJ:x})")
            break
    else:
        print(f"{name}: no escribe data del Part (con args de prueba)")
