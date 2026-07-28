#!/usr/bin/env bash
# Fase 1 — helper de desensamblado del MAIN OS descomprimido (ColdFire/m68k BE).
# Uso:
#   ./disasm.sh                 abre r2 interactivo sobre el raw
#   ./disasm.sh strings         vuelca todos los strings con offset
#   ./disasm.sh pd 0x1000       desensambla N instrucciones/expresion en r2 y sale
set -euo pipefail
cd "$(dirname "$0")"

RAW="${RAW:-out/raw/section_3_MAIN_OS.bin}"
[ -f "$RAW" ] || { echo "No existe $RAW — corre ./analyze.sh primero." >&2; exit 1; }

# m68k big-endian cubre gran parte del ISA ColdFire.
# Base de CARGA determinada empiricamente (tools/find_base.py): 0x40000400
# (imagen en SDRAM 0x40000000 + 0x400 de cabecera/vectores). Datos/BSS ~0x400bxxxx.
BASE="${BASE:-0x40000400}"
# -m mapea el raw en la base (el -B no remapea ficheros crudos en r2).
R2FLAGS=(-a m68k -b 32 -e cfg.bigendian=true -m "$BASE")

case "${1:-}" in
  strings)
    r2 -q "${R2FLAGS[@]}" -c 'e bin.str.raw=true; izz~...' "$RAW" 2>/dev/null || \
      strings -t x -n 6 "$RAW"
    ;;
  "")
    echo "Abriendo r2 (m68k BE). Comandos utiles:"
    echo "  aaa            analisis   |  pd 40      desensambla   |  izz  strings"
    echo "  afl            funciones  |  s <addr>   navega        |  V    modo visual"
    exec r2 "${R2FLAGS[@]}" "$RAW"
    ;;
  *)
    r2 -q "${R2FLAGS[@]}" -c "$*" "$RAW"
    ;;
esac
