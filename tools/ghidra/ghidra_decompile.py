# Ghidra post-script — descompila a C las funciones clave del Octatrack.
# Se ejecuta via analyzeHeadless tras la auto-anotacion. Fuerza la creacion de
# funcion en cada direccion objetivo (prologos hallados por string_func_map.py) y
# vuelca el pseudo-C.
#
# @category Octatrack
from ghidra.app.decompiler import DecompInterface
from ghidra.util.task import ConsoleTaskMonitor
from ghidra.program.model.symbol import SourceType

# (vaddr, etiqueta) — funciones ancladas a strings de UI (ver out/string_function_map.txt)
TARGETS = [
    (0x40086d7a, "project_settings_serialize"),  # 58 claves de config del proyecto
    (0x4001fc1e, "error_code_to_string"),         # familia 'ERROR CODE: %d'
    (0x40069424, "format_card_handler"),          # 'FORMAT CARD'
    (0x400645ce, "save_project_handler"),         # 'SAVE PROJECT'
    (0x40022fdc, "collect_samples_handler"),      # 'COLLECT SAMPLES'
    (0x400867e0, "project_sample_section"),       # token '[SAMPLE]'
]

af = currentProgram.getAddressFactory().getDefaultAddressSpace()  # noqa: F821
fm = currentProgram.getFunctionManager()                          # noqa: F821

dec = DecompInterface()
dec.openProgram(currentProgram)  # noqa: F821
mon = ConsoleTaskMonitor()

for vaddr, label in TARGETS:
    a = af.getAddress(vaddr)
    f = fm.getFunctionAt(a)
    if f is None:
        try:
            disassemble(a)                       # noqa: F821
            f = createFunction(a, label)         # noqa: F821
        except Exception as e:
            print("[!] no pude crear funcion en 0x%x: %s" % (vaddr, e))
    if f is None:
        print("==== 0x%08x (%s): sin funcion ====" % (vaddr, label))
        continue
    try:
        f.setName(label, SourceType.USER_DEFINED)
    except Exception:
        pass
    res = dec.decompileFunction(f, 90, mon)
    print("\n==================== %s @ 0x%08x ====================" % (label, vaddr))
    if res and res.decompileCompleted():
        print(res.getDecompiledFunction().getC())
    else:
        print("  (descompilacion fallida: %s)" %
              (res.getErrorMessage() if res else "sin resultado"))

print("\n[ghidra_decompile] fin.")
