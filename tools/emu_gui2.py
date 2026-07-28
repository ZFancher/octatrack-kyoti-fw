#!/usr/bin/env python3
# Valida patch_gui2.bin: la guarda de reentrada que arregla el crash VEC:0B.
# El test 3 es el que reproduce el bug original -- en el codigo viejo la segunda
# entrada pisaba SAVE_RET y la externa terminaba saltando a una direccion muerta.
import pathlib, struct
from unicorn import *
from unicorn.m68k_const import *

STUB = pathlib.Path("out/patch_gui2.bin").read_bytes()
SETUP = 0x400d6600
CLEANUP = 0x400d66a4
EDITOR_BODY = 0x40052ea0            # a donde salta setup para seguir el editor
TRACK = 0x100b14cc
DISP_PAT = 0x100b14cf
ACT_PART, ACT_PAT = 0x80000002, 0x80000003
TRK_PART, TRK_PAT = 0x8000182a, 0x80001832
SAVE_CF, SAVE_02, SAVE_03, DID_OVR, SAVE_RET = (0x80006c30, 0x80006c31, 0x80006c32,
                                                0x80006c33, 0x80006c34)


def mk():
    uc = Uc(UC_ARCH_M68K, UC_MODE_BIG_ENDIAN)
    uc.mem_map(0x40000000, 0x400000)
    uc.mem_map(0x80000000, 0x20000)
    uc.mem_map(0x41000000, 0x20000)
    uc.mem_map(0x10000000, 0x1000000)
    uc.mem_write(SETUP, STUB)
    return uc


def run_setup(track, part_of_track, pat_of_track, active_part, active_pat,
              disp_pat, did_override, ret_addr, lazy=1):
    uc = mk()
    uc.mem_write(TRACK, bytes([track]))
    uc.mem_write(DISP_PAT, bytes([disp_pat]))
    uc.mem_write(ACT_PART, bytes([active_part]))
    uc.mem_write(ACT_PAT, bytes([active_pat]))
    uc.mem_write(TRK_PART + track, bytes([part_of_track]))
    uc.mem_write(TRK_PAT + track, bytes([pat_of_track]))
    uc.mem_write(DID_OVR, bytes([did_override]))
    uc.mem_write(0x800000d8, struct.pack('>I', lazy))
    uc.mem_write(SAVE_RET, struct.pack(">I", 0xAAAAAAAA))   # marcador previo

    sp0 = 0x41018000
    uc.mem_write(sp0, struct.pack(">I", ret_addr))
    uc.reg_write(UC_M68K_REG_A7, sp0)
    res = {"reached": False}

    def hook(uc, addr, size, user):
        if addr == EDITOR_BODY:
            res["reached"] = True
            # el prologo desplazado hizo `lea -0x20(a7),a7`, asi que el retorno
            # del llamador quedo en SP+0x20
            res["stack_ret"] = struct.unpack(">I", uc.mem_read(
                uc.reg_read(UC_M68K_REG_A7) + 0x20, 4))[0]
            uc.emu_stop()

    uc.hook_add(UC_HOOK_CODE, hook)
    err = None
    try:
        uc.emu_start(SETUP, 0, count=3000)
    except UcError as e:
        err = str(e)
    return {"err": err, **res,
            "did_ovr": uc.mem_read(DID_OVR, 1)[0],
            "save_ret": struct.unpack(">I", uc.mem_read(SAVE_RET, 4))[0],
            "disp_pat": uc.mem_read(DISP_PAT, 1)[0],
            "act_part": uc.mem_read(ACT_PART, 1)[0],
            "act_pat": uc.mem_read(ACT_PAT, 1)[0]}


FAILS = []


def ck(name, cond, detail=""):
    if not cond:
        FAILS.append(name)
    print(("PASS" if cond else "FAIL"), name, detail)


# --- 1. sin transicion: no debe overridear nada
r = run_setup(track=6, part_of_track=2, pat_of_track=1, active_part=2, active_pat=1,
              disp_pat=1, did_override=0, ret_addr=0x40052000)
ck("1 sin transicion: no overridea",
   r["reached"] and r["did_ovr"] == 0 and r["save_ret"] == 0xAAAAAAAA
   and r["stack_ret"] == 0x40052000,
   f"(DID_OVR={r['did_ovr']}, ret en stack=0x{r['stack_ret']:x})")

# --- 2. en transicion, primera entrada: instala el hook
r = run_setup(track=6, part_of_track=1, pat_of_track=5, active_part=2, active_pat=9,
              disp_pat=9, did_override=0, ret_addr=0x40052000)
ck("2 transicion, 1a entrada: instala hook y overridea",
   r["reached"] and r["did_ovr"] == 1 and r["save_ret"] == 0x40052000
   and r["stack_ret"] == CLEANUP and r["disp_pat"] == 5
   and r["act_part"] == 1 and r["act_pat"] == 5,
   f"(SAVE_RET=0x{r['save_ret']:x}, stack->0x{r['stack_ret']:x}, globals={r['disp_pat']}/{r['act_part']}/{r['act_pat']})")

# --- 3. EL FIX: en transicion pero ya hay override activo (reentrada)
r = run_setup(track=6, part_of_track=1, pat_of_track=5, active_part=1, active_pat=5,
              disp_pat=5, did_override=1, ret_addr=0x40052BBB)
ck("3 REENTRADA: no pisa SAVE_RET ni engancha el retorno",
   r["reached"] and r["save_ret"] == 0xAAAAAAAA and r["stack_ret"] == 0x40052BBB
   and r["did_ovr"] == 1,
   f"(SAVE_RET=0x{r['save_ret']:x} intacta, ret en stack=0x{r['stack_ret']:x} sin enganchar)")

# --- 4. cleanup restaura y salta al retorno real
uc = mk()
uc.mem_write(SAVE_CF, bytes([9]))
uc.mem_write(SAVE_02, bytes([2]))
uc.mem_write(SAVE_03, bytes([9]))
uc.mem_write(DID_OVR, bytes([1]))
uc.mem_write(SAVE_RET, struct.pack(">I", 0x40052000))
uc.mem_write(DISP_PAT, bytes([5]))
uc.mem_write(ACT_PART, bytes([1]))
uc.mem_write(ACT_PAT, bytes([5]))
uc.reg_write(UC_M68K_REG_A7, 0x41018000)
land = {}
uc.hook_add(UC_HOOK_CODE, lambda u, a, s, x: (land.update(pc=a), u.emu_stop())
            if a == 0x40052000 else None)
try:
    uc.emu_start(CLEANUP, 0, count=2000)
except UcError as e:
    land["err"] = str(e)
ck("4 cleanup: restaura globals y retorna al llamador real",
   land.get("pc") == 0x40052000 and uc.mem_read(DISP_PAT, 1)[0] == 9
   and uc.mem_read(ACT_PART, 1)[0] == 2 and uc.mem_read(ACT_PAT, 1)[0] == 9
   and uc.mem_read(DID_OVR, 1)[0] == 0,
   f"(volvio a 0x{land.get('pc', 0):x}, DID_OVR={uc.mem_read(DID_OVR,1)[0]})")

r = run_setup(track=6, part_of_track=1, pat_of_track=5, active_part=2, active_pat=9,
              disp_pat=9, did_override=0, ret_addr=0x40052000, lazy=0)
ck("5 GATE apagado: no overridea aunque haya transicion",
   r["reached"] and r["did_ovr"] == 0 and r["stack_ret"] == 0x40052000
   and r["disp_pat"] == 9 and r["act_part"] == 2,
   f"(DID_OVR={r['did_ovr']}, globals intactos)")

print("\n" + ("TODOS OK" if not FAILS else "FALLAN: " + ", ".join(FAILS)))
