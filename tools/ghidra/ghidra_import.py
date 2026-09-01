# Ghidra script — importa el firmware del Octatrack con base y refs a strings.
# Ejecutar DENTRO de Ghidra (Script Manager) tras crear el proyecto, o via
# analyzeHeadless. Asume el programa cargado como:
#   Processor: 68000 (Motorola)  | Endian: big | base image: 0x40000400
#
# Que hace:
#   1) verifica/anota la base
#   2) por cada fila de pointers_to_strings.csv: define el string en su vaddr,
#      define un puntero de 4 bytes en el sitio del puntero, y pone una etiqueta
#      legible en el string -> las referencias cruzadas aparecen en el desensamblado.
#
# @category Octatrack
# @keybinding
# @menupath
# @toolbar
import csv
import os

from ghidra.program.model.data import PointerDataType, TerminatedStringDataType
from ghidra.program.model.symbol import SourceType

# Ruta al CSV generado por tools/find_base.py (ajusta si hace falta).
CSV = os.path.join(os.path.dirname(os.path.realpath(__file__)), "..", "out",
                   "pointers_to_strings.csv")

fm = currentProgram.getFunctionManager()  # noqa: F821
listing = currentProgram.getListing()      # noqa: F821
symtab = currentProgram.getSymbolTable()   # noqa: F821
af = currentProgram.getAddressFactory().getDefaultAddressSpace()  # noqa: F821


def addr(v):
    return af.getAddress(v)


def slug(s):
    out = "".join(c if c.isalnum() else "_" for c in s.strip())[:40]
    return out.strip("_") or "str"


defined_strings = 0
defined_ptrs = 0
labeled = 0

with open(CSV) as fh:
    for row in csv.DictReader(fh):
        s_va = int(row["string_vaddr"], 16)
        p_site = int(row["ptr_site_vaddr"], 16)
        text = row["string"]

        s_addr = addr(s_va)
        p_addr = addr(p_site)

        # 1) define el string (si el area no esta ya definida)
        try:
            if listing.getDataAt(s_addr) is None or not listing.getDataAt(s_addr).isDefined():
                clearListing(s_addr)  # noqa: F821
                createData(s_addr, TerminatedStringDataType())  # noqa: F821
                defined_strings += 1
        except Exception:
            pass

        # 2) etiqueta legible en el string
        try:
            symtab.createLabel(s_addr, "s_" + slug(text), SourceType.USER_DEFINED)
            labeled += 1
        except Exception:
            pass

        # 3) define el puntero de 4 bytes en el sitio del puntero (crea la xref)
        try:
            clearListing(p_addr, p_addr.add(3))  # noqa: F821
            createData(p_addr, PointerDataType())  # noqa: F821
            defined_ptrs += 1
        except Exception:
            pass

print("[ghidra_import] strings definidos: %d" % defined_strings)
print("[ghidra_import] etiquetas creadas: %d" % labeled)
print("[ghidra_import] punteros definidos: %d" % defined_ptrs)
print("[ghidra_import] listo. Corre Auto-Analysis para propagar xrefs.")
