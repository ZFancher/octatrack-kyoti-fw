#!/usr/bin/env python3
# SPDX-License-Identifier: MIT
# SPDX-FileCopyrightText: 2026 Zac-Kyoti
"""
RELOAD FROM PROJECT -- scaled-down variant (NOTES.md "Session 43").

A trimmed sibling of build_reload.py.  build_reload.py (Session 42: SEQ / ALL
PARTS / WHOLE PATTERN, patch_reload.s) is UNCHANGED and still builds.  This one
builds a SEPARATE image from patch_reload2.s:

  stock 1.40C + the MIDI manual-trig fix + a stay-open 2-item picker window.

  UX (Session 43 revision):
    [PTN]+[NO]  opens the window (playing, no arranger, no popup).  You may
                release [PTN] -- the window stays open.
    arrows      UP/RIGHT -> SEQ DATA ; DOWN/LEFT -> PART + SEQ DATA.
    [YES]       execute the highlight, close the window.
    [NO]        close the window, execute nothing.
    While the window is open [YES]/[NO] act ONLY on the picker.  ~10 s no-input
    auto-close (re-armed on every arrow) is a walk-away safety.

  SEQ DATA        -- reload the active pattern's sequence data from bankNN.strd
                     (identical mechanism to build_reload.py's "SEQ").
  PART + SEQ DATA -- the above, plus the one Part the pattern is assigned to
                     (0x80000003, the "current part mirror"), via the stock
                     RELOAD PART path FUN_4004aab4(part).

  Dropped vs build_reload.py: "ALL PARTS" (the FUN_4004aab4(0..3) loop) and the
  "PARTS only -> skip the SEQ post" branch.

  1. patch_trigscale  -- MIDI manual-trig stall fix.  Byte-identical detour + cave
                         to build_trigscale_only.py / build_reload.py.
  2. patch_reload2    -- six detours:
       rl_no    @0x4005e25c  NO handler.  [PTN]+[NO] press -> open the window
                             (bare-text popup FUN_4005a0e0).  [NO] with the
                             window open -> close it, execute nothing.  Swallow.
       rl_yes   @0x4005e4c8  YES handler.  [YES] with the window open -> close
                             it, then: PART + SEQ DATA -> FUN_4004aab4 on the
                             pattern's own Part + the stock UI refresh; both
                             options -> arm {G_KIND=1, G_PAT=active} + post
                             FUN_40022778(1<<curbank).  Toast + swallow.  Window
                             closed -> replay the stock prologue (the DJ toggle
                             slots in here in the merged build -- G_MENU first).
       rl_arr_a @0x4004b970  UP/RIGHT key handler (keycodes 0x34/0x21).  Window
                             open -> G_SEL=0, redraw, swallow.  Closed -> replay
                             the displaced prologue, fall through (invisible).
       rl_arr_b @0x400491a0  DOWN/LEFT key handler (keycodes 0x33/0x20).  Window
                             open -> G_SEL=1, redraw, swallow.  Closed -> ditto.
       rl_tick  @0x400522ca  the engine per-control-frame handler (same splice
                             DIRECT JUMP v2 uses).  Counts G_TICKS down; at 0
                             closes the window (FUN_40056bc0) + clears G_MENU.
       rl_job   @0x40085864  the storage task's type-0x14 case.  When G_KIND==1:
                             open bankNN.strd (28 KB of the loader's own 64 KB
                             buffer), read the 22-byte header, parse patterns
                             0..P sequentially with the firmware's own per-
                             pattern parser FUN_4008cebc (0..P-1 discarded to a
                             36 KB scratch, pattern P kept), memcpy P -> the live
                             slab, set 0x46c8028a, rejoin the case's exit.  Open
                             ENOENT -> exit d0=-12 -> the stock "THIS BANK HAS
                             NEVER BEEN SAVED!" dialog for free.  Inert when
                             G_KIND==0; a re-entrant real RELOAD BANK is
                             unaffected (worker latches + clears G_KIND).

  No scratch BANK is used.  SEQ DATA writes nothing but pattern P's live slab;
  the Part reload is the stock per-part reload path.  No PERSONALIZE entry, no
  persistent state -> no 'ANDY' shadow / pea 0x64->0x70.

  STATUS: SEQ DATA emu-validated end to end (emu_reload2.py --combo + --patched).
  PART + SEQ DATA + the picker: static + assembly checked; the Part reload = the
  stock FUN_4004aab4 path.  Needs a hardware pass (FLASHING.md 4.7).

  MERGE NOTE (DJ + RELOAD2, planned): [YES] here acts only while G_MENU==1 --
  otherwise it replays the stock prologue and falls through.  DIRECT JUMP's
  [PTN]+[YES] toggle is currently a *different* image; the merged build uses one
  0x4005e4c8 handler that tests G_MENU FIRST (window open -> RELOAD execute) and
  only reaches the DJ toggle when it is clear.  The DJ toggle also gains a
  `tst.b G_MENU / bne stock` guard.  So the RELOAD window must be closed before
  DJ can operate -- which is the intended behaviour.

  HW-only from Session 42, plus: whether an arrow press reaches rl_arr_a/b while
  the FUN_4005a0e0 popup is up (the base-view arrow routing) -- if not, the
  fallback is a hook in the event dispatcher FUN_40061b60.

Usage:   python3 tools/build_reload2.py [VERSTR]      (default VERSTR = "140C_KYOTI")
Outputs: out/mainos_reload2.bin, out/elek_reload2.bin,
         out/OCTATRACK_OS1.40C_RELOAD2.syx, out/OCTATRACK_RELOAD2.bin
"""
import os, pathlib, subprocess, sys

BASE = 0x40000400
HERE = pathlib.Path(__file__).parent
ROOT = HERE.parent
STOCK_SECT = ROOT / "out/raw/section_3_MAIN_OS.bin"
STOCK_SYX = ROOT / "downloads/extracted/OCTATRACK_OS1.40C.syx"
EFT = ROOT / "vendor/elektron-firmware-tool/elektron-firmware-tool"
OUT = ROOT / "out/mainos_reload2.bin"
ELEK = ROOT / "out/elek_reload2.bin"
OUT_SYX = ROOT / "out/OCTATRACK_OS1.40C_RELOAD2.syx"
OUT_BIN = ROOT / "out/OCTATRACK_RELOAD2.bin"

VERSTR = sys.argv[1] if len(sys.argv) > 1 else "140C_KYOTI"

# (source, load addr, defsym, [(detour site, symbol, expected bytes, len, kind)])
PATCHES = [
    ("patch_trigscale", 0x400d7b00, None,
     [(0x4009b6f2, "cave", "203c0000091a", 18, "jmp")]),
    ("patch_reload2", 0x400d7400, None,
     [(0x4005e25c, "rl_no", "202f00086714", 6, "jmp"),         # NO handler: move.l 8(sp),d0 ; beq.s 0x4005e276
      (0x4005e4c8, "rl_yes", "222f0004202f0008", 8, "jmp"),    # YES handler: move.l 4(sp),d1 ; move.l 8(sp),d0
      (0x4004b970, "rl_arr_a", "4feffff448d7040c", 8, "jmp"),  # UP/RIGHT handler: lea -12(sp),sp ; movem.l d2-d3/a2,(sp)
      (0x400491a0, "rl_arr_b", "2f02206f0008", 6, "jmp"),      # DOWN/LEFT handler: move.l d2,-(sp) ; movea.l 8(sp),a0
      (0x400522ca, "rl_tick", "45f946c7dfba", 6, "jsr"),       # per-frame handler: lea 0x46c7dfba,a2
      (0x40085864, "rl_job", "2d4afd762f2a0004", 8, "jmp")]),  # 0x14 case: move.l a2,-650(fp) ; move.l 4(a2),-(sp)
]

FREE_END = 0x400d7c3c


def jmp(t):
    return b"\x4e\xf9" + t.to_bytes(4, "big")


def jsr(t):
    return b"\x4e\xb9" + t.to_bytes(4, "big")


def assemble(name, at, defsym):
    aso = ["m68k-elf-as", "-mcpu=5407"]
    for d in (defsym.split(",") if defsym else []):
        aso += ["--defsym", d]
    aso += ["-o", f"out/{name}.o", f"tools/{name}.s"]
    subprocess.run(aso, check=True, cwd=ROOT)
    subprocess.run(["m68k-elf-ld", f"-Ttext=0x{at:x}", "-o", f"out/{name}.elf", f"out/{name}.o"],
                   check=True, cwd=ROOT, capture_output=True)
    subprocess.run(["m68k-elf-objcopy", "-O", "binary", f"out/{name}.elf", f"out/{name}.bin"],
                   check=True, cwd=ROOT)
    nm = subprocess.run(["m68k-elf-nm", f"out/{name}.elf"], capture_output=True, text=True).stdout
    syms = {p[2]: int(p[0], 16) for p in (l.split() for l in nm.splitlines()) if len(p) == 3}
    return (ROOT / f"out/{name}.bin").read_bytes(), syms


def main():
    if not STOCK_SECT.exists():
        sys.exit(f"missing {STOCK_SECT} -- run ./fetch-os.sh and ./analyze.sh first")
    img = bytearray(STOCK_SECT.read_bytes())
    stock = bytes(img)

    def o(a):
        return a - BASE

    syms, spans = {}, []
    print("=== assemble + detour ===")
    for name, at, defsym, detours in PATCHES:
        blob, s = assemble(name, at, defsym)
        syms[name] = s
        co = o(at)
        if any(img[co:co + len(blob)]):
            sys.exit(f"cave 0x{at:08x} ({name}) not free: {bytes(img[co:co+16]).hex()}")
        spans.append((at, at + len(blob), name))
        img[co:co + len(blob)] = blob
        print(f"  {name:16s} {len(blob):3d} B @ 0x{at:08x} .. 0x{at+len(blob)-1:08x}")
        for site, sym, exp, n, kind in detours:
            exp = bytes.fromhex(exp)
            do = o(site)
            if bytes(img[do:do + len(exp)]) != exp:
                sys.exit(f"detour 0x{site:08x} ({name}:{sym}) unexpected: "
                         f"{bytes(img[do:do+len(exp)]).hex()} != {exp.hex()}")
            branch = jsr(s[sym]) if kind == "jsr" else jmp(s[sym])
            img[do:do + n] = branch + b"\x4e\x71" * ((n - 6) // 2)
            print(f"    0x{site:08x} -> {name}:{sym} 0x{s[sym]:08x}  ({kind}, {n} B)")

    spans.sort()
    for (a1, b1, n1), (a2, b2, n2) in zip(spans, spans[1:]):
        if b1 > a2:
            sys.exit(f"cave overlap: {n1} 0x{a1:x}..0x{b1:x} / {n2} 0x{a2:x}..0x{b2:x}")
    if spans[-1][1] > FREE_END:
        sys.exit(f"cave runs past the free zone end (0x{spans[-1][1]:x} > 0x{FREE_END:x})")
    print("  no overlaps; all within the free cave")

    OUT.write_bytes(bytes(img))
    changed = sum(1 for a, b in zip(stock, img) if a != b)
    print(f"\n  {OUT.name}: {changed} bytes changed vs stock")

    ts = ROOT / "out/mainos_trigscale_only.bin"
    if ts.exists():
        tsb = ts.read_bytes()
        tsh = [i for i, (x, y) in enumerate(zip(stock, tsb)) if x != y]
        ok = all(img[i] == tsb[i] for i in tsh)
        print(f"  manual-trig fix bytes identical to build_trigscale_only.py: {ok}")
        if not ok:
            sys.exit("  MANUAL-TRIG FIX DIVERGED")

    if not EFT.exists() or not STOCK_SYX.exists():
        print("\n  (EFT tool or stock syx missing -- skipping the .syx/.bin wrap)")
        return
    print("\n=== wrap ===")
    env = dict(os.environ, EFT_EMIT_CONTAINER=str(ELEK))
    r = subprocess.run([str(EFT), "-i", str(STOCK_SYX), "-c", "3", str(OUT),
                        "-V", VERSTR, "-o", str(OUT_SYX)], capture_output=True, text=True, env=env, cwd=ROOT)
    print("  " + "\n  ".join(l for l in r.stdout.splitlines()
                             if "version" in l or "emitted" in l or "wrote" in l))
    if "too long" in r.stdout:
        sys.exit(f'  version string "{VERSTR}" ({len(VERSTR)}) does not fit the 10-char field')
    subprocess.run(["python3", "tools/make_bin.py", str(ELEK), "-o", str(OUT_BIN)], check=True, cwd=ROOT)

    print(f"\n  {OUT_SYX.name}  (MIDI DIN)  +  {OUT_BIN.name}  (CF card)")
    print(f"  version screen / SYSTEM STATUS -> OS VERSION will read:  {VERSTR}")
    print("  [PTN] + [NO]  (while playing)  ->  opens the picker window (stays open)")
    print("  arrows                         ->  UP/RIGHT SEQ DATA / DOWN/LEFT PART + SEQ DATA")
    print("  [YES]                          ->  execute the highlight + close, no transport stop")
    print("  [NO]                           ->  close the window, execute nothing")
    print("  Revert = flash downloads/extracted/OCTATRACK_OS1.40C.syx")


if __name__ == "__main__":
    main()
