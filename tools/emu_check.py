#!/usr/bin/env python3
"""
emu_check.py — pre-flash safety net. Unit-emulate individual firmware routines with
Unicorn (m68k/ColdFire CPU) and diff a PATCHED image against pristine STOCK, so we catch
the class of bug that cost us several flash/crash/sysex-recover cycles WITHOUT flashing.

What it CAN test (where every bug so far lived):
  - what a routine writes to the DSP/peripheral registers (0x8000xxxx) and to memory,
  - whether a modified routine computes the SAME register/memory effects as stock,
  - behaviour-equivalence of "neutral" changes (state accessors, relocation code).
What it CANNOT test: the DSP56300, real peripherals, or full-system boot/audio timing.
The non-determinism we hit came from the DSP consuming a desynced count; this harness
shows that desync at the SOURCE (the register write), which is the actionable signal.

Memory map (generous, zero-initialised like fresh Unicorn RAM):
  0x10000000 +2MB   SRAM  (stock settings block 0x100b14f0..)
  0x40000000 +16MB  DDR low: OS code @0x40000400, stack, pool/reserved/DDR-settings
  0x46000000 +16MB  DDR mid: STATE table 0x46c90a78, DSP buffers 0x46c2xxxx
  0x80000000 +64KB  DSP / peripheral registers
  0x0fff0000 +4KB   return trampoline (emu stops when a routine rts's back here)

CLI:
    python3 tools/emu_check.py [patched.bin]      # default: out/mainos_phase1.bin
    STOCK defaults to out/stock_mainos.bin (decode once via elektron-firmware-tool).

Add a check: write a function check_xxx(stock, patched) -> (name, ok, detail) and append
it to CHECKS. Use Emu.call(entry, regs=..., mem=..., max_insn=...) to drive a routine.
"""
import pathlib, sys
from unicorn import *
from unicorn.m68k_const import *

LOAD = 0x40000400
RET_TRAMP = 0x0fff0000
STACK_TOP = 0x40700000

REGS = {
    "d0": UC_M68K_REG_D0, "d1": UC_M68K_REG_D1, "d2": UC_M68K_REG_D2, "d3": UC_M68K_REG_D3,
    "d4": UC_M68K_REG_D4, "d5": UC_M68K_REG_D5, "d6": UC_M68K_REG_D6, "d7": UC_M68K_REG_D7,
    "a0": UC_M68K_REG_A0, "a1": UC_M68K_REG_A1, "a2": UC_M68K_REG_A2, "a3": UC_M68K_REG_A3,
    "a4": UC_M68K_REG_A4, "a5": UC_M68K_REG_A5, "a6": UC_M68K_REG_A6, "a7": UC_M68K_REG_A7,
    "pc": UC_M68K_REG_PC,
}


class Emu:
    """One firmware image loaded into a fresh emulated address space."""

    def __init__(self, image_path):
        self.img = pathlib.Path(image_path).read_bytes()
        self.name = pathlib.Path(image_path).name

    def _fresh(self):
        mu = Uc(UC_ARCH_M68K, UC_MODE_BIG_ENDIAN)
        for base, size in [(0x10000000, 0x00200000), (0x40000000, 0x01000000),
                           (0x46000000, 0x01000000), (0x80000000, 0x00010000),
                           (RET_TRAMP & ~0xfff, 0x1000)]:
            mu.mem_map(base, size)
        # load the OS image at its link address
        span = min(len(self.img), 0x40000000 + 0x01000000 - LOAD)
        mu.mem_write(LOAD, self.img[:span])
        return mu

    def call(self, entry, regs=None, mem=None, watch_writes=True, max_insn=200000):
        """Run `entry` as a subroutine. Returns dict(regs_out, writes, stopped, reason).
        `regs`: {name:val} initial registers. `mem`: {addr:bytes} pre-writes.
        Emulation stops when the routine rts's back to RET_TRAMP or max_insn is hit."""
        mu = self._fresh()
        mu.reg_write(UC_M68K_REG_A7, STACK_TOP)
        if mem:
            for a, b in mem.items():
                mu.mem_write(a, b)
        if regs:
            for k, v in regs.items():
                mu.reg_write(REGS[k], v & 0xffffffff)
        # push the return trampoline as the subroutine's return address
        sp = STACK_TOP - 4
        mu.reg_write(UC_M68K_REG_A7, sp)
        mu.mem_write(sp, RET_TRAMP.to_bytes(4, "big"))

        writes = {}
        if watch_writes:
            def on_write(uc, access, address, size, value, user):
                writes[address] = (value & ((1 << (size * 8)) - 1), size)
            mu.hook_add(UC_HOOK_MEM_WRITE, on_write)

        reason = "ret"
        try:
            mu.emu_start(entry, RET_TRAMP, count=max_insn)
        except UcError as e:
            reason = f"UcError:{e} @pc=0x{mu.reg_read(UC_M68K_REG_PC):08x}"
        pc = mu.reg_read(UC_M68K_REG_PC)
        if reason == "ret" and pc != RET_TRAMP:
            reason = f"count-exhausted @pc=0x{pc:08x}"
        out = {k: mu.reg_read(REGS[k]) for k in REGS}
        return {"regs": out, "writes": writes, "reason": reason}


# ---------------------------------------------------------------------------
# CHECKS  — each returns (name, ok: bool, detail: str)
# ---------------------------------------------------------------------------

def check_dsp_init_regs(stock, patched):
    """The DSP-init prologue at 0x40096f80 must program IDENTICAL values into the DSP
    registers 0x80006914/18/1c/20 in stock and patched. Phase-1 desynced 0x80006920
    (count 0x390A -> 0x388A) and moved the 0x40a955e0 struct — both surface here."""
    entry = 0x40096f80
    s = stock.call(entry, max_insn=12)
    p = patched.call(entry, max_insn=12)
    regs = [0x80006914, 0x80006918, 0x8000691c, 0x80006920]
    diffs = []
    for r in regs:
        sv = s["writes"].get(r, (None, 0))[0]
        pv = p["writes"].get(r, (None, 0))[0]
        if sv != pv:
            diffs.append(f"0x{r:08x}: stock={hex(sv) if sv is not None else None} "
                         f"patched={hex(pv) if pv is not None else None}")
    ok = not diffs
    detail = "DSP regs identical to stock" if ok else "DSP REGS DIVERGE -> " + "; ".join(diffs)
    return ("dsp_init_regs", ok, detail)


def check_dsp_struct_intact(stock, patched):
    """The DSP struct base 0x40a955e0 and the audio buffer bases 0x46c2e9c0/e580/e780
    must be byte-for-byte referenced the same (no relocation of live DSP memory)."""
    import_counts = lambda img, v: img.count(v.to_bytes(4, "big"))
    bad = []
    for v in (0x40a955e0, 0x46c2e9c0, 0x46c2e580, 0x46c2e780):
        sc, pc = import_counts(stock.img, v), import_counts(patched.img, v)
        if sc != pc:
            bad.append(f"0x{v:08x}: stock refs={sc} patched refs={pc}")
    ok = not bad
    return ("dsp_struct_intact", ok, "DSP struct/buffers unrelocated" if ok
            else "DSP MEMORY RELOCATED -> " + "; ".join(bad))


def check_count_consistency(stock, patched):
    """Every occurrence of the DSP buffer count 0x390A (long + word) must be unchanged:
    changing a subset desyncs the DSP view of the buffer -> non-deterministic audio crash."""
    def counts(img):
        return img.count((0x390A).to_bytes(4, "big")), img.count((0x390A).to_bytes(2, "big"))
    sl, sw = counts(stock.img)
    pl, pw = counts(patched.img)
    ok = (sl, sw) == (pl, pw)
    return ("count_consistency", ok,
            f"0x390A occurrences long {sl}->{pl}, word {sw}->{pw}"
            + ("" if ok else "  <-- COUNT DESYNC"))


CHECKS = [check_dsp_init_regs, check_dsp_struct_intact, check_count_consistency]


def main():
    patched_path = sys.argv[1] if len(sys.argv) > 1 else "out/mainos_phase1.bin"
    stock_path = "out/stock_mainos.bin"
    if not pathlib.Path(stock_path).exists():
        sys.exit(f"missing {stock_path} — decode stock once:\n"
                 f"  elektron-firmware-tool -i downloads/extracted/OCTATRACK_OS1.40C.syx -d 3 -o out/_s "
                 f"&& cp out/_s/section_3_MAIN_OS.bin {stock_path}")
    stock = Emu(stock_path)
    patched = Emu(patched_path)
    print(f"STOCK  : {stock_path}")
    print(f"PATCHED: {patched_path}\n")
    allok = True
    for chk in CHECKS:
        name, ok, detail = chk(stock, patched)
        allok &= ok
        print(f"  [{'PASS' if ok else 'FAIL'}] {name:22} {detail}")
    print(f"\n{'ALL GREEN — safe to consider flashing' if allok else 'FAILURES — DO NOT FLASH'}")
    sys.exit(0 if allok else 1)


if __name__ == "__main__":
    main()
