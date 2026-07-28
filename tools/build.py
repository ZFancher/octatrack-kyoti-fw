#!/usr/bin/env python3
"""
Build the patched MAIN OS from the STOCK image. Nothing is derived from a previous
build, and every cross-patch address comes from the linker's symbol table — a stale
detour target once froze the unit on the logo screen when a stub shifted a few bytes.

Features (all OFF by default, switched on from PERSONALIZE):

  NO BANK/PTN TIMER  0x800000d4  SELECT BANK / SELECT PATTERN windows stop expiring
  LAZY TRANSITIONS   0x800000d8  on a pattern change to a different Part, sounding
                                 tracks keep the previous Part's definition (dimmed
                                 track LED) until the track is modified: a sequencer
                                 trig, a manual trig, or moving one of its encoders.
                                 Also keeps the A/B scene pointers pointing at the
                                 same slots across the Part change.

    python3 tools/build.py        # -> out/mainos.bin
"""
import pathlib, subprocess, sys

BASE = 0x40000400
STOCK = pathlib.Path("out/raw/section_3_MAIN_OS.bin")
OUT = pathlib.Path("out/mainos.bin")

# source -> load address in the free code cave (0x400d64da..0x400d7c3b)
STUBS = [("patch",         0x400d64e0),   # lazy part: save/restore + destination snapshot
         ("patch_enc",     0x400d6600),   # encoder move -> adopt destination Part
         ("patch_scene2",  0x400d6700),   # sticky A/B scene pointers
         ("patch_led",     0x400d6800),   # dimmed LED while a track is dirty
         ("patch_notimer", 0x400d6900)]   # countdown gate + the two menu entries

# detour site -> (source, symbol, what it displaces)
DETOURS = [(0x40009094, "patch_scene2",  "scene_stub",   "apply_part entry"),
           (0x40009664, "patch",         "restore_stub", "apply_part exit"),
           (0x40052e98, "patch_enc",     "enc_stub",     "encoder editor"),
           (0x4003f1b4, "patch_scene2",  "xf_stub",      "crossfader"),
           (0x40083fb4, "patch_led",     "led_stub",     "track LED painter"),
           (0x40056ab8, "patch_notimer", "cd_stub",      "BANK/PTN countdown tick")]

# original bytes each detour must find, as a guard against a wrong assumption
EXPECT = {0x40009094: "4fefff98", 0x40009664: None, 0x40052e98: "4fefffe0",
          0x4003f1b4: "4fefffc4", 0x40083fb4: "4878000f", 0x40056ab8: "4ab9460d"}

LBL_AT, GET_AT, SET_AT = 0x400d6a00, 0x400d6a50, 0x400d6aa0
OLD_LBL, OLD_GET, OLD_SET, N_OLD = 0x400b2a34, 0x400b2a74, 0x400b2ac0, 16
# the label array is loaded as an immediate into D5, NOT by lea — a lea-only sweep misses it
REFS = [(0x40068efe, OLD_LBL, "labels  move.l #imm,D5"),
        (0x40068f0a, OLD_GET, "getters lea"),
        (0x40069022, OLD_SET, "setters lea #1"),
        (0x4006903e, OLD_SET, "setters lea #2"),
        (0x40069056, OLD_SET, "setters lea #3")]
COUNT_AT = 0x40068fb2         # moveq #15 -> #17, keeping the 15/16 conditionality


def off(a):
    return a - BASE


def jmp(t):
    return b"\x4e\xf9" + t.to_bytes(4, "big")


def assemble(name, at):
    subprocess.run(["m68k-elf-as", "-mcpu=5407", "-o", f"out/{name}.o",
                    f"tools/{name}.s"], check=True)
    subprocess.run(["m68k-elf-ld", f"-Ttext=0x{at:x}", "-o", f"out/{name}.elf",
                    f"out/{name}.o"], capture_output=True)
    subprocess.run(["m68k-elf-objcopy", "-O", "binary", f"out/{name}.elf",
                    f"out/{name}.bin"], check=True)
    nm = subprocess.run(["m68k-elf-nm", f"out/{name}.elf"], capture_output=True,
                        text=True).stdout
    return (pathlib.Path(f"out/{name}.bin").read_bytes(),
            {p[2]: int(p[0], 16) for p in (l.split() for l in nm.splitlines()) if len(p) == 3})


def main():
    if not STOCK.exists():
        sys.exit(f"missing {STOCK} — run ./fetch-os.sh and ./analyze.sh first")
    img = bytearray(STOCK.read_bytes())

    blobs, syms = {}, {}
    print("=== stubs ===")
    for name, at in STUBS:
        blobs[name], syms[name] = assemble(name, at)
        print(f"  {name:14s} {len(blobs[name]):3d} B @ 0x{at:08x} .. 0x{at+len(blobs[name])-1:08x}")

    # no stub may run into the next, nor into the menu arrays
    spans = sorted([(a, a + len(blobs[n]), n) for n, a in STUBS] +
                   [(LBL_AT, LBL_AT + 72, "labels"), (GET_AT, GET_AT + 72, "getters"),
                    (SET_AT, SET_AT + 72, "setters")])
    for (lo, hi, n), (lo2, _, n2) in zip(spans, spans[1:]):
        if hi > lo2:
            sys.exit(f"{n} (ends 0x{hi:08x}) overlaps {n2} (starts 0x{lo2:08x})")
    print("  no overlaps")

    # every write site must be genuinely free cave
    for lo, hi, n in spans:
        if any(img[off(lo):off(hi)]):
            sys.exit(f"cave at 0x{lo:08x} ({n}) is NOT free: {bytes(img[off(lo):off(lo)+16]).hex()}")
    print("  cave verified free at every write site")

    for name, at in STUBS:
        img[off(at):off(at) + len(blobs[name])] = blobs[name]

    print("\n=== detours (targets derived from the symbol tables) ===")
    for site, name, sym, tag in DETOURS:
        o, exp = off(site), EXPECT.get(site)
        if exp and not bytes(img[o:o + len(exp) // 2]).hex().startswith(exp):
            sys.exit(f"detour site 0x{site:08x} ({tag}) unexpected: {bytes(img[o:o+6]).hex()}")
        img[o:o + 6] = jmp(syms[name][sym])
        print(f"  0x{site:08x} -> {sym:14s} 0x{syms[name][sym]:08x}   {tag}")

    print("\n=== PERSONALIZE menu ===")
    new = {OLD_LBL: (LBL_AT, ("lbl_notimer", "lbl_lazy")),
           OLD_GET: (GET_AT, ("get_notimer", "get_lazy")),
           OLD_SET: (SET_AT, ("set_notimer", "set_lazy"))}
    for old, (dst, names) in new.items():
        ents = [int.from_bytes(img[off(old + i * 4):off(old + i * 4) + 4], "big")
                for i in range(N_OLD)] + [syms["patch_notimer"][n] for n in names]
        for i, v in enumerate(ents):
            img[off(dst + i * 4):off(dst + i * 4) + 4] = v.to_bytes(4, "big")
        print(f"  array @0x{dst:08x}: {len(ents)} entries (16 stock + 2)")
    for a, old, tag in REFS:
        o = off(a)
        if bytes(img[o:o + 4]) != old.to_bytes(4, "big"):
            sys.exit(f"ref at 0x{a:08x} is not 0x{old:08x}: {bytes(img[o:o+4]).hex()}")
        img[o:o + 4] = new[old][0].to_bytes(4, "big")
        print(f"  repoint 0x{a:08x}  {tag}: -> 0x{new[old][0]:08x}")
    o = off(COUNT_AT)
    if bytes(img[o:o + 2]) != b"\x72\x0f":
        sys.exit(f"count at 0x{COUNT_AT:08x} is not moveq #15: {bytes(img[o:o+2]).hex()}")
    img[o:o + 2] = b"\x72\x11"
    print(f"  count   0x{COUNT_AT:08x}  moveq #15 -> #17  (17 or 18 items)")

    OUT.write_bytes(bytes(img))
    stock = STOCK.read_bytes()
    d = [i for i, (x, y) in enumerate(zip(stock, img)) if x != y]
    print(f"\n{OUT}: {len(img):,} bytes, {len(d)} changed vs stock")


if __name__ == "__main__":
    main()
