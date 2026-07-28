#!/usr/bin/env python3
# Valida patch_trig.bin: marca en ambar (1,1) el trig de la scene seleccionada
# cuando algun track esta en transicion. Entra por jmp, asi que NO debe tocar
# SP, A6 (recorre el array de color) ni D3 (recorre el de brillo).
import pathlib, struct
from unicorn import *
from unicorn.m68k_const import *

STUB_ADDR = 0x400d6900
STUB = pathlib.Path("out/patch_trig.bin").read_bytes()
RESUME = 0x40034b64
TRK_PART, ACT_PART = 0x8000182a, 0x80000002
PROJ, PTRLOC = 0x46d00000, 0x46c82456
DISP_PAT, SLOT_EDIT = 0x100b14cf, 0x460d169c
STRIDE, SEL = 0x18b2, 0x8ed90
ID_TABLE = 0x400a75f2
COLOUR = 0x41017000          # simula local_80 (A6 apunta aca)


def run(parts, active_part, pattern, scene_a, scene_b, slot_edit):
    uc = Uc(UC_ARCH_M68K, UC_MODE_BIG_ENDIAN)
    uc.mem_map(0x40000000, 0x400000)
    uc.mem_map(0x46000000, 0x1000000)
    uc.mem_map(0x80000000, 0x20000)
    uc.mem_map(0x41000000, 0x20000)
    uc.mem_map(0x10000000, 0x1000000)
    uc.mem_write(STUB_ADDR, STUB)
    uc.mem_write(PTRLOC, struct.pack(">I", PROJ))
    uc.mem_write(TRK_PART, bytes(parts))
    uc.mem_write(ACT_PART, bytes([active_part]))
    uc.mem_write(DISP_PAT, bytes([pattern]))
    uc.mem_write(SLOT_EDIT, bytes([slot_edit]))
    uc.mem_write(PROJ + pattern * STRIDE + SEL, bytes([scene_a, scene_b]))
    uc.mem_write(COLOUR, b"\x00" * 128)          # 32 entradas x 4 B, todo en cero

    sp0 = 0x41018000
    uc.reg_write(UC_M68K_REG_A7, sp0)
    uc.reg_write(UC_M68K_REG_A6, COLOUR)
    uc.reg_write(UC_M68K_REG_D3, 0xDEADBEEF)     # brillo walker, no debe tocarse

    res = {"resumed": False}

    def hook(uc, addr, size, user):
        if addr == RESUME:
            res["resumed"] = True
            res["sp"] = uc.reg_read(UC_M68K_REG_A7)
            res["a6"] = uc.reg_read(UC_M68K_REG_A6)
            res["d3"] = uc.reg_read(UC_M68K_REG_D3)
            res["a3"] = uc.reg_read(UC_M68K_REG_A3)
            uc.emu_stop()

    uc.hook_add(UC_HOOK_CODE, hook)
    wild = []

    def w(uc, acc, ad, sz, val, u):
        if not (COLOUR <= ad < COLOUR + 128 or 0x41000000 <= ad < 0x41020000):
            wild.append((hex(ad), sz, val))

    uc.hook_add(UC_HOOK_MEM_WRITE, w)
    err = None
    try:
        uc.emu_start(STUB_ADDR, 0, count=4000)
    except UcError as e:
        err = str(e)
    arr = [struct.unpack(">I", uc.mem_read(COLOUR + i * 4, 4))[0] for i in range(32)]
    return {"err": err, "wild": wild, "sp0": sp0, "arr": arr, **res}


FAILS = []


def check(name, r, want_amber_trig):
    m = []
    if r["err"]:
        m.append("ERROR " + r["err"])
    if not r["resumed"]:
        m.append("no llego a RESUME")
    if r["wild"]:
        m.append("WILD WRITE " + str(r["wild"][:3]))
    if r["resumed"]:
        if r["sp"] != r["sp0"]:
            m.append("SP alterado")
        if r["a6"] != COLOUR:
            m.append("A6 alterado")
        if r["d3"] != 0xDEADBEEF:
            m.append("D3 alterado")
        if r["a3"] != ID_TABLE:
            m.append("A3=0x%x, la instruccion desplazada no se ejecuto" % r["a3"])
    nonzero = [i for i, v in enumerate(r["arr"]) if v != 0]
    if want_amber_trig is None:
        if nonzero:
            m.append("no debia pintar nada, pinto %s" % nonzero)
    else:
        want = [want_amber_trig * 2, want_amber_trig * 2 + 1]
        if nonzero != want:
            m.append("pinto %s, esperado %s" % (nonzero, want))
        elif not all(r["arr"][i] == 1 for i in want):
            m.append("valores %s, esperado 1,1" % [r["arr"][i] for i in want])
    ok = not m
    if not ok:
        FAILS.append(name)
    print(("PASS" if ok else "FAIL"), name)
    print("      entradas no-cero: %s" % nonzero)
    for x in m:
        print("       -", x)


ALL_SAME = [3] * 8          # ningun track en transicion (todos en el Part activo)
ONE_DIFF = [3, 3, 5, 3, 3, 3, 3, 3]   # el track 2 quedo en el Part 5

check("1 nada en transicion: no pinta",
      run(ALL_SAME, 3, 7, 9, 4, slot_edit=1), None)
check("2 track 2 en transicion, slot A en edicion -> ambar en el trig de scene A (9)",
      run(ONE_DIFF, 3, 7, 9, 4, slot_edit=1), 9)
check("3 mismo caso pero slot B en edicion -> ambar en el trig de scene B (4)",
      run(ONE_DIFF, 3, 7, 9, 4, slot_edit=0), 4)
check("4 transicion en el track 7 (ultimo del scan)",
      run([3, 3, 3, 3, 3, 3, 3, 1], 3, 7, 15, 0, slot_edit=1), 15)
check("5 transicion en el track 0 (primero)",
      run([1, 3, 3, 3, 3, 3, 3, 3], 3, 7, 0, 2, slot_edit=1), 0)

print("\n" + ("TODOS OK" if not FAILS else "FALLAN: " + ", ".join(FAILS)))
