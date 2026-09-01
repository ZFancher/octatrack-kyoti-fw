// Ghidra post-script (Java) — descompila a C las funciones clave del Octatrack.
// Java siempre esta disponible en headless (Ghidra 12 no trae Jython por defecto).
// Fuerza la creacion de funcion en cada prologo hallado por string_func_map.py.
//@category Octatrack
import ghidra.app.script.GhidraScript;
import ghidra.app.decompiler.DecompInterface;
import ghidra.app.decompiler.DecompileResults;
import ghidra.program.model.address.Address;
import ghidra.program.model.address.AddressSpace;
import ghidra.program.model.listing.Function;
import ghidra.program.model.listing.FunctionManager;
import ghidra.program.model.symbol.SourceType;
import ghidra.util.task.ConsoleTaskMonitor;

public class GhidraDecompile extends GhidraScript {
    @Override
    public void run() throws Exception {
        long[] targets = {
            0x40086d7aL, 0x4001fc1eL, 0x40069424L,
            0x400645ceL, 0x40022fdcL, 0x400867e0L
        };
        String[] labels = {
            "project_settings_serialize", "error_code_to_string", "format_card_handler",
            "save_project_handler", "collect_samples_handler", "project_sample_section"
        };

        AddressSpace sp = currentProgram.getAddressFactory().getDefaultAddressSpace();
        FunctionManager fm = currentProgram.getFunctionManager();
        DecompInterface dec = new DecompInterface();
        dec.openProgram(currentProgram);
        ConsoleTaskMonitor mon = new ConsoleTaskMonitor();

        for (int i = 0; i < targets.length; i++) {
            Address a = sp.getAddress(targets[i]);
            Function f = fm.getFunctionAt(a);
            if (f == null) {
                try { disassemble(a); f = createFunction(a, labels[i]); }
                catch (Exception e) { println("[!] no pude crear funcion @ " + a + ": " + e); }
            }
            if (f == null) { println("==== " + labels[i] + ": sin funcion @ " + a + " ===="); continue; }
            try { f.setName(labels[i], SourceType.USER_DEFINED); } catch (Exception e) {}

            DecompileResults res = dec.decompileFunction(f, 120, mon);
            println("\n==================== " + labels[i] + " @ " + a + " ====================");
            if (res != null && res.decompileCompleted()) {
                println(res.getDecompiledFunction().getC());
            } else {
                println("  (descompilacion fallida: " + (res != null ? res.getErrorMessage() : "null") + ")");
            }
        }
        println("\n[GhidraDecompile] fin.");
    }
}
