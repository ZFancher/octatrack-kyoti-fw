from unicorn import *
from unicorn.m68k_const import *
import struct, pathlib

CODE = pathlib.Path("out/raw/section_3_MAIN_OS.bin").read_bytes()
BASE = 0x40000400
uc = Uc(UC_ARCH_M68K, UC_MODE_BIG_ENDIAN)

# gran región para código+datos (0x40000000-0x40200000)
uc.mem_map(0x40000000, 0x200000)
uc.mem_write(BASE, CODE[:0x200000-0x400])

# stack
uc.mem_map(0x41000000, 0x20000)
sp = 0x41010000

# auto-mapea memoria faltante (RAM cero) para robustez
def on_unmapped(uc, access, address, size, value, user):
    uc.mem_map(address & ~0xFFF, 0x1000)
    return True
uc.hook_add(UC_HOOK_MEM_UNMAPPED, on_unmapped)

RET = 0x40180000  # dirección de retorno mágica (dentro del mapa)
TRACK = 5
sp -= 4; uc.mem_write(sp, struct.pack(">I", TRACK))  # arg1 (queda en sp+4)
sp -= 4; uc.mem_write(sp, struct.pack(">I", RET))    # ret addr (sp)
uc.reg_write(UC_M68K_REG_A7, sp)

try:
    uc.emu_start(0x40000e50, RET, timeout=2*1000000, count=1000)
except UcError as e:
    print("UcError:", e, "PC=0x%08x" % uc.reg_read(UC_M68K_REG_PC))

d0 = uc.reg_read(UC_M68K_REG_D0)
exp = 0x800049d8 + TRACK*0xa8
print("D0        = 0x%08x" % d0)
print("esperado  = 0x%08x" % exp)
print("VALIDACIÓN:", "OK ✓ Unicorn ejecuta el ColdFire del firmware" if d0==exp else "✗ no coincide")
