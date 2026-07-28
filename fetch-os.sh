#!/usr/bin/env bash
# Fase 0 — descarga y extrae el OS oficial del Octatrack (artefacto legal y publico).
# Fuente: pagina de soporte oficial de Elektron (verificada 2026-07).
set -euo pipefail
cd "$(dirname "$0")"

OS_VER="1.40C"
ZIP_URL="https://www.elektron.se/wp-content/uploads/2025/03/OCTATRACK_OS${OS_VER}_dist.zip"
README_URL="https://www.elektron.se/wp-content/uploads/2025/03/OCTATRACK_OS${OS_VER}_readme.pdf"
DL=downloads
ZIP="$DL/OCTATRACK_OS${OS_VER}_dist.zip"

mkdir -p "$DL"

echo "[fetch] descargando OS $OS_VER ..."
curl -fL --retry 3 --max-time 120 -o "$ZIP" "$ZIP_URL"
curl -fL --retry 3 --max-time 120 -o "$DL/OCTATRACK_OS${OS_VER}_readme.pdf" "$README_URL"

echo "[fetch] hash del ZIP (anota esto para reproducibilidad):"
shasum -a 256 "$ZIP"

echo "[fetch] extrayendo ..."
unzip -o "$ZIP" -d "$DL/extracted"

echo "[fetch] artefactos de firmware encontrados:"
find "$DL/extracted" -type f \( -iname '*.bin' -o -iname '*.syx' \) -exec ls -la {} \;

echo
echo "[fetch] listo. Siguiente paso:  ./analyze.sh"
