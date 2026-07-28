#!/usr/bin/env bash
# Fase 0 — instala el tooling de RE y compila elektron-firmware-tool.
# Idempotente: puedes correrlo varias veces.
set -euo pipefail
cd "$(dirname "$0")"

echo "== 1) Herramientas del sistema (via Homebrew) =="
need_brew=()
command -v binwalk  >/dev/null 2>&1 || need_brew+=(binwalk)
command -v radare2  >/dev/null 2>&1 || need_brew+=(radare2)
# 'binwalk 3' moderno vive tambien en pip; brew suele traer una version usable.
if [ "${#need_brew[@]}" -gt 0 ]; then
  echo "   instalando: ${need_brew[*]}"
  brew install "${need_brew[@]}"
else
  echo "   binwalk y radare2 ya presentes."
fi

echo
echo "== 2) elektron-firmware-tool (mischa85) =="
if [ ! -d vendor/elektron-firmware-tool ]; then
  git clone https://github.com/mischa85/elektron-firmware-tool vendor/elektron-firmware-tool
else
  echo "   ya clonado; git pull ..."
  git -C vendor/elektron-firmware-tool pull --ff-only || true
fi

# Dos cambios locales son necesarios para reproducir este build:
#   - set_version() escribe el campo de version ELEK completo (10 chars) desde 0x08;
#     upstream solo escribe desde 0x0D, donde entran 5.
#   - EFT_EMIT_CONTAINER vuelca el contenedor reconstruido, que tools/make_bin.py
#     envuelve para generar el .bin de la CF.
PATCH=$(pwd)/tools/elektron-firmware-tool.patch
if [ -f "$PATCH" ]; then
  if git -C vendor/elektron-firmware-tool apply --check "$PATCH" 2>/dev/null; then
    git -C vendor/elektron-firmware-tool apply "$PATCH" && echo "   parche local aplicado"
  else
    echo "   parche local ya aplicado (o el upstream cambio: revisar $PATCH)"
  fi
fi

echo "   intentando compilar ..."
if [ -f vendor/elektron-firmware-tool/Makefile ]; then
  make -C vendor/elektron-firmware-tool || echo "   [!] make fallo — revisa vendor/elektron-firmware-tool/README"
else
  # Fallback: compilar cualquier .c suelto en el repo.
  src=$(find vendor/elektron-firmware-tool -maxdepth 2 -name '*.c' | tr '\n' ' ')
  if [ -n "$src" ]; then
    echo "   sin Makefile; compilando fuentes: $src"
    cc -O2 -o vendor/elektron-firmware-tool/elektron-firmware-tool $src || \
      echo "   [!] compilacion directa fallo — inspecciona el repo manualmente"
  else
    echo "   [!] no encontre fuentes C ni Makefile — revisa el repo"
  fi
fi

echo
echo "== 3) Ghidra (opcional, manual) =="
if [ ! -d "/Applications/ghidra" ] && ! command -v ghidra >/dev/null 2>&1; then
  cat <<'EOF'
   Ghidra no detectado. Para el desensamblado ColdFire:
     brew install --cask ghidra      # requiere JDK 17+ (brew install temurin)
   ColdFire NO trae procesador nativo perfecto en Ghidra; el modulo m68k
   cubre gran parte del ISA. Alternativa: radare2/rizin con -a m68k.
EOF
fi

echo
echo "== setup completo. Siguiente:  ./fetch-os.sh  y luego  ./analyze.sh =="
