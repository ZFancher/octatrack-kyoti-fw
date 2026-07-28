#!/usr/bin/env bash
# Fase 0 — recon estatico de los artefactos de firmware del Octatrack.
# Corre entropia, binwalk, strings, y (si compila) elektron-firmware-tool.
set -euo pipefail
cd "$(dirname "$0")"

EXTRACTED=downloads/extracted
OUT=out
mkdir -p "$OUT"

mapfile -t ARTIFACTS < <(find "$EXTRACTED" -type f \( -iname '*.bin' -o -iname '*.syx' \) 2>/dev/null)
if [ "${#ARTIFACTS[@]}" -eq 0 ]; then
  echo "[analyze] no hay .bin/.syx en $EXTRACTED — corre ./fetch-os.sh primero." >&2
  exit 1
fi

for f in "${ARTIFACTS[@]}"; do
  base=$(basename "$f")
  echo "############################################################"
  echo "# $base"
  echo "############################################################"
  ls -la "$f"
  echo "sha256: $(shasum -a 256 "$f" | awk '{print $1}')"
  echo

  echo "--- [1] Entropia (comprimido/cifrado vs codigo) ---"
  python3 tools/entropy.py "$f" --csv "$OUT/${base}.entropy.csv" --png "$OUT/${base}.entropy.png" || true
  echo

  echo "--- [2] binwalk (firmas, filesystems, magia) ---"
  if command -v binwalk >/dev/null 2>&1; then
    binwalk "$f" | tee "$OUT/${base}.binwalk.txt" || true
  else
    echo "    (binwalk no instalado; corre ./setup.sh)"
  fi
  echo

  echo "--- [3] Cabecera (primeros 64 bytes) ---"
  xxd -l 64 "$f"
  echo

  echo "--- [4] Strings largos (posibles versiones, menus, rutas) ---"
  strings -n 6 "$f" | sort -u | head -40 | tee "$OUT/${base}.strings.txt" >/dev/null
  head -20 "$OUT/${base}.strings.txt"
  echo "    (completo en $OUT/${base}.strings.txt)"
  echo

  # elektron-firmware-tool solo aplica a los .syx
  if [[ "$base" == *.syx ]]; then
    echo "--- [5] elektron-firmware-tool (capas/checksums/descompresion) ---"
    EFT=vendor/elektron-firmware-tool/elektron-firmware-tool
    if [ -x "$EFT" ]; then
      "$EFT" "$f" 2>&1 | tee "$OUT/${base}.eft.txt" || \
        echo "    (la herramienta corrio con errores; revisa su uso: $EFT --help)"
    else
      echo "    (elektron-firmware-tool no compilado; corre ./setup.sh)"
    fi
    echo
  fi
done

echo "############################################################"
echo "# Resumen"
echo "############################################################"
echo "Salidas en $OUT/:"
ls -la "$OUT"
cat <<'EOF'

Como leer los resultados:
  * Entropia ~8.0 en TODO el payload  -> cifrado fuerte (dificil).
  * Entropia ~8.0 solo en bloques     -> secciones comprimidas (factible).
  * Entropia media + strings legibles -> codigo/tablas ColdFire directas.
  * binwalk detecta gzip/lzma/zlib    -> descomprime y analiza el raw.
  * Si elektron-firmware-tool extrae secciones raw -> ESE es el firmware
    a cargar en Ghidra/radare2 con target m68k/ColdFire big-endian.

Siguiente fase (desensamblado): ver NOTES.md.
EOF
