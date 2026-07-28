#!/usr/bin/env python3
"""Mapa funcion -> strings de UI para el firmware ColdFire del Octatrack.

No depende del analizador de r2 (flojo en m68k). En su lugar:
  1) localiza strings (offset -> texto),
  2) busca en el codigo instrucciones que CARGAN un puntero a string como inmediato
     (pea / lea (xxx).L,An / move.l #imm,Dn / move.l #imm,-(a7)),
  3) remonta hacia atras hasta el prologo de funcion mas cercano
     (LINK An,#imm = 0x4E50-0x4E57  |  lea -n(a7),a7 = 0x4FEF),
  4) agrupa: funcion @vaddr -> lista de strings que referencia.

Uso: python3 string_func_map.py <raw.bin> [--base 0x40000400] [--out mapa.txt]
"""
import argparse
import struct
from collections import defaultdict
from pathlib import Path

# Opcodes (2 bytes BE) que preceden a un puntero de 32 bits embebido en el codigo.
LOAD_OPS = {
    0x4879: "pea",
    0x2F3C: "push.l #",           # move.l #imm,-(a7)  (empujar arg string)
}
for i, reg in enumerate("a0 a1 a2 a3 a4 a5 a6 a7".split()):
    LOAD_OPS[0x41F9 + i * 0x200] = f"lea ,{reg}"   # lea (xxx).L,An
for i, reg in enumerate("d0 d1 d2 d3 d4 d5 d6 d7".split()):
    LOAD_OPS[0x203C + i * 0x200] = f"move.l #,{reg}"  # move.l #imm,Dn


def find_strings(data, min_len=5):
    starts, i, n = {}, 0, len(data)
    while i < n:
        if 0x20 <= data[i] < 0x7f:
            j = i
            while j < n and 0x20 <= data[j] < 0x7f:
                j += 1
            if j - i >= min_len and (j < n and data[j] == 0):  # terminado en NUL
                starts[i] = data[i:j].decode("ascii", "replace")
            i = j
        else:
            i += 1
    return starts


def is_prologue(data, pos):
    if pos < 0 or pos + 1 >= len(data):
        return False
    w = struct.unpack_from(">H", data, pos)[0]
    return 0x4E50 <= w <= 0x4E57 or w == 0x4FEF


def find_func_start(data, site, limit=0x2000):
    """Remonta desde 'site' al prologo mas cercano (offsets pares)."""
    p = site - (site % 2) - 2
    lo = max(0, site - limit)
    while p >= lo:
        if is_prologue(data, p):
            return p
        p -= 2
    return None


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("file")
    ap.add_argument("--base", default="0x40000400")
    ap.add_argument("--min-str", type=int, default=5)
    ap.add_argument("--out")
    args = ap.parse_args()
    base = int(args.base, 0)

    data = Path(args.file).read_bytes()
    strings = find_strings(data, args.min_str)
    str_va_to_off = {base + off: off for off in strings}

    func_strings = defaultdict(list)   # func_off -> [(site, string)]
    code_refs = 0
    for pos in range(0, len(data) - 5, 2):
        op = struct.unpack_from(">H", data, pos)[0]
        if op not in LOAD_OPS:
            continue
        ptr = struct.unpack_from(">I", data, pos + 2)[0]
        off = str_va_to_off.get(ptr)
        if off is None:
            continue
        code_refs += 1
        fstart = find_func_start(data, pos)
        key = fstart if fstart is not None else -1
        func_strings[key].append((pos, LOAD_OPS[op], strings[off]))

    # Ordena funciones por cantidad de strings (mas "habladoras" primero).
    funcs = sorted((k for k in func_strings if k >= 0),
                   key=lambda k: -len(func_strings[k]))
    lines = []
    lines.append(f"# Mapa funcion -> strings  ({args.file})")
    lines.append(f"# base={args.base}  strings={len(strings)}  "
                 f"refs-en-codigo={code_refs}  funciones-con-strings={len(funcs)}")
    lines.append("")
    for k in funcs:
        refs = func_strings[k]
        lines.append(f"FUNC 0x{base + k:08x}  ({len(refs)} strings)")
        seen = set()
        for site, op, s in refs:
            s1 = s[:60].replace("\n", " ")
            if s1 in seen:
                continue
            seen.add(s1)
            lines.append(f"    @0x{base + site:08x} {op:10s} '{s1}'")
        lines.append("")

    orphan = func_strings.get(-1, [])
    if orphan:
        lines.append(f"# {len(orphan)} refs sin prologo de funcion identificable "
                     f"(tablas de datos o funciones hoja).")

    out = "\n".join(lines)
    print(out if not args.out else out[:1500])
    if args.out:
        Path(args.out).write_text(out + "\n")
        print(f"\n[string_func_map] -> {args.out} ({len(funcs)} funciones)")


if __name__ == "__main__":
    main()
